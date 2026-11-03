/*
 * Copyright (C) 2026 Mumla Funk contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.funk;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;

import java.security.cert.X509Certificate;
import java.util.List;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.humla.model.Server;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.app.ServerConnectTask;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;

/**
 * The walkie-talkie popup: one big hold-to-talk button for one server.
 * <p>
 * Opened from the home screen widgets and by holding the power button (when Mumla Funk is the
 * default digital assistant, via {@code ACTION_ASSIST}). It can show over the lock screen.
 * While it is open, volume-down is a push-to-talk key.
 */
public class PttActivity extends AppCompatActivity implements FunkState.Listener,
        PttButtonView.Listener {
    /** Saved server to talk on. Without it: the current connection, else the last used server. */
    public static final String EXTRA_SERVER_ID = "se.lublin.mumla.funk.extra.SERVER_ID";
    /** Connect right away if not yet connected to that server. */
    public static final String EXTRA_AUTO_CONNECT = "se.lublin.mumla.funk.extra.AUTO_CONNECT";

    private static final int REQUEST_RECORD_AUDIO = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long IDLE_CLOSE_MS = 30_000;
    private static final long SWITCH_TIMEOUT_MS = 4_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mIdleClose = this::closeAnimated;
    private final Runnable mContinueSwitch = this::continueSwitch;

    private Settings mSettings;
    private FunkPrefs mFunkPrefs;
    private MumlaDatabase mDatabase;
    private IMumlaService mService;
    private boolean mBound;

    /** The server this popup talks on, or null to just follow the current connection. */
    @Nullable
    private Server mServer;
    /** Server to connect to after permissions were granted / the old connection is closed. */
    @Nullable
    private Server mPendingConnect;
    private boolean mWaitingForDisconnect;
    /** A connect was requested and has not shown up in the service state yet. */
    private boolean mConnectRequested;
    @Nullable
    private String mError;
    private boolean mErrorNeedsApp;
    private boolean mKeyTalking;
    /** The widget's server was deleted meanwhile. */
    private boolean mServerMissing;

    private View mRoot;
    private View mCard;
    private ImageView mStatusIcon;
    private TextView mServerName;
    private TextView mServerSub;
    private PttButtonView mButton;
    private TextView mLabel;
    private TextView mHint;
    private MaterialButton mAction;

    @NonNull
    public static Intent createIntent(Context context, long serverId, boolean autoConnect) {
        Intent intent = new Intent(context, PttActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (serverId >= 0) {
            intent.putExtra(EXTRA_SERVER_ID, serverId);
        }
        intent.putExtra(EXTRA_AUTO_CONNECT, autoConnect);
        return intent;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((MumlaService.MumlaBinder) binder).getService();
            mService.registerObserver(mObserver);
            render(FunkState.current());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            mHandler.post(() -> showError(getString(R.string.funk_error_cert), true));
        }

        @Override
        public void onDisconnected(HumlaException e) {
            mHandler.post(() -> handleDisconnected(e));
        }
    };

    private void handleDisconnected(@Nullable HumlaException e) {
        if (mWaitingForDisconnect && mPendingConnect != null) {
            mHandler.removeCallbacks(mContinueSwitch);
            continueSwitch();
            return;
        }
        mConnectRequested = false;
        if (e != null && mError == null) {
            if (e.getReason() == HumlaException.HumlaDisconnectReason.REJECT && e.getReject() != null
                    && (e.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW
                    || e.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                showError(getString(R.string.funk_error_password), true);
            } else {
                showError(e.getMessage(), false);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_ptt);

        mSettings = Settings.getInstance(this);
        mFunkPrefs = FunkPrefs.get(this);
        mDatabase = new MumlaSQLiteDatabase(this);
        mDatabase.open();

        mRoot = findViewById(R.id.ptt_root);
        mCard = findViewById(R.id.ptt_card);
        mStatusIcon = findViewById(R.id.ptt_status_icon);
        mServerName = findViewById(R.id.ptt_server_name);
        mServerSub = findViewById(R.id.ptt_server_sub);
        mButton = findViewById(R.id.ptt_button);
        mLabel = findViewById(R.id.ptt_label);
        mHint = findViewById(R.id.ptt_hint);
        mAction = findViewById(R.id.ptt_action);

        mRoot.setOnClickListener(v -> closeAnimated());
        findViewById(R.id.ptt_close).setOnClickListener(v -> closeAnimated());
        findViewById(R.id.ptt_open_app).setOnClickListener(v -> openApp(false));
        mButton.setListener(this);

        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        sizeCard();

        handleIntent(getIntent());

        if (savedInstanceState == null) {
            mRoot.setAlpha(0f);
            mRoot.animate().alpha(1f).setDuration(160).start();
            mCard.setTranslationY(48 * getResources().getDisplayMetrics().density);
            mCard.animate().translationY(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    /** Cap the card width on wide screens and shrink the button on short ones (landscape). */
    private void sizeCard() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        float widthDp = dm.widthPixels / density;
        float heightDp = dm.heightPixels / density;
        if (widthDp > 464) {
            ViewGroup.LayoutParams lp = mCard.getLayoutParams();
            lp.width = (int) (440 * density);
            mCard.setLayoutParams(lp);
        }
        float size = Math.min(216, Math.min(widthDp - 96, heightDp - 290));
        size = Math.max(132, size);
        ViewGroup.LayoutParams lp = mButton.getLayoutParams();
        lp.width = lp.height = (int) (size * density);
        mButton.setLayoutParams(lp);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@Nullable Intent intent) {
        long requested = intent != null ? intent.getLongExtra(EXTRA_SERVER_ID, -1) : -1;
        boolean autoConnect = intent != null && intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false);
        FunkState state = FunkState.current();
        mError = null;
        mErrorNeedsApp = false;

        mServerMissing = false;
        if (requested < 0 && state.connection != FunkState.Connection.DISCONNECTED) {
            // Power button: talk on whatever we are connected to right now.
            mServer = state.serverId >= 0 ? FunkServers.resolve(this, state.serverId) : null;
        } else if (requested >= 0
                && FunkServers.find(FunkServers.loadAll(this), requested) == null) {
            // A widget of a deleted server: never fall back to talking on another server.
            mServer = null;
            mServerMissing = true;
        } else {
            mServer = FunkServers.resolve(this, requested);
        }
        render(state);
        if (autoConnect && mServer != null && !state.isOn(mServer.getId())) {
            connect(mServer);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        FunkState.addListener(this);
        // Flags 0: only bind if the service is running, or as soon as a connect starts it.
        mBound = bindService(new Intent(this, MumlaService.class), mConnection, 0);
        render(FunkState.current());
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleIdleClose();
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mIdleClose);
        // Never keep transmitting once the popup is gone (screen off, home button, ...).
        if (mButton.isHolding() || mKeyTalking) {
            mKeyTalking = false;
            stopTalking();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        FunkState.removeListener(this);
        if (mService != null) {
            mService.unregisterObserver(mObserver);
            mService = null;
        }
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        mDatabase.close();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        scheduleIdleClose();
        return super.dispatchTouchEvent(ev);
    }

    private void scheduleIdleClose() {
        mHandler.removeCallbacks(mIdleClose);
        mHandler.postDelayed(mIdleClose, IDLE_CLOSE_MS);
    }

    private void closeAnimated() {
        if (isFinishing()) {
            return;
        }
        if (mButton.isHolding() || mKeyTalking || FunkState.current().selfTalking) {
            scheduleIdleClose(); // never auto-close while transmitting
            return;
        }
        mCard.animate().translationY(48 * getResources().getDisplayMetrics().density)
                .setDuration(160).start();
        mRoot.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---------------------------------------------------------------- state rendering

    @Override
    public void onFunkStateChanged(@NonNull FunkState state) {
        if (state.connection != FunkState.Connection.DISCONNECTED) {
            mConnectRequested = false;
            if (mServer == null && state.serverId >= 0) {
                mServer = FunkServers.resolve(this, state.serverId);
            }
        }
        render(state);
    }

    private boolean isTargetOn(FunkState state) {
        if (mServer == null) {
            return state.connection != FunkState.Connection.DISCONNECTED;
        }
        return state.isOn(mServer.getId());
    }

    private void render(@NonNull FunkState state) {
        if (mButton == null) {
            return;
        }
        boolean targetOn = isTargetOn(state);
        String serverName = mServer != null ? FunkServers.displayName(mServer)
                : state.serverName != null ? state.serverName : getString(R.string.funk_no_server);
        mServerName.setText(serverName);

        boolean connected = targetOn && state.connection == FunkState.Connection.CONNECTED;
        mStatusIcon.setImageResource(connected ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
        ImageViewCompat.setImageTintList(mStatusIcon, ContextCompat.getColorStateList(this,
                connected ? R.color.funk_live : R.color.funk_on_surface_muted));

        String label;
        String hint = null;
        int hintColor = ContextCompat.getColor(this, R.color.funk_on_surface_muted);
        String action = null;
        PttButtonView.Mode mode;

        if (connected) {
            int online = state.members.size();
            mServerSub.setText(state.channelName != null
                    ? getString(R.string.funk_channel_online, state.channelName, online)
                    : getString(R.string.funk_online_count, online));
            boolean ptt = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
            boolean optimistic = (mButton.isHolding() || mKeyTalking) && !mSettings.isPushToTalkToggle();
            if (state.selfMuted) {
                mode = PttButtonView.Mode.MUTED;
                label = getString(R.string.funk_muted);
                hint = getString(R.string.funk_tap_to_unmute);
            } else if (!ptt) {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_not_ptt_mode);
                action = getString(R.string.funk_switch_to_ptt);
            } else if (state.selfTalking || optimistic) {
                mode = PttButtonView.Mode.TALKING;
                label = getString(R.string.funk_transmitting);
            } else {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_hold_to_talk);
            }
            List<FunkState.Member> talking = state.talkingOthers();
            if (hint == null && !talking.isEmpty()) {
                hint = talking.size() == 1
                        ? getString(R.string.funk_x_talking, talking.get(0).name)
                        : getString(R.string.funk_several_talking, talking.get(0).name, talking.size() - 1);
                hintColor = ContextCompat.getColor(this, R.color.funk_live);
            } else if (hint == null && mode == PttButtonView.Mode.READY && action == null
                    && mFunkPrefs.isVolumePttInPopup()) {
                hint = getString(R.string.funk_volume_hint);
            }
        } else if (targetOn || mConnectRequested) {
            mode = PttButtonView.Mode.CONNECTING;
            boolean reconnecting = targetOn && state.connection == FunkState.Connection.RECONNECTING;
            label = getString(reconnecting ? R.string.funk_reconnecting : R.string.funk_connecting);
            mServerSub.setText(R.string.funk_connecting);
        } else if (mServer == null) {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(mServerMissing ? R.string.funk_server_removed : R.string.funk_no_server);
            hint = getString(mServerMissing ? R.string.funk_server_removed_hint : R.string.funk_no_server_hint);
            action = getString(R.string.funk_open_app);
            mServerSub.setText(R.string.funk_not_connected);
        } else {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(R.string.funk_tap_to_connect);
            mServerSub.setText(R.string.funk_not_connected);
            if (state.connection != FunkState.Connection.DISCONNECTED && state.serverName != null) {
                hint = getString(R.string.funk_widget_other_server, state.serverName);
            }
        }

        if (mError != null && mode == PttButtonView.Mode.OFFLINE) {
            hint = mError;
            hintColor = ContextCompat.getColor(this, R.color.funk_warn);
            if (mErrorNeedsApp) {
                action = getString(R.string.funk_open_app);
            }
        }

        mButton.setMode(mode);
        mLabel.setText(label);
        mHint.setText(hint != null ? hint : "");
        mHint.setTextColor(hintColor);
        if (action != null) {
            mAction.setText(action);
            mAction.setVisibility(View.VISIBLE);
            final boolean switchToPtt = connected && !state.selfMuted
                    && !Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
            mAction.setOnClickListener(v -> {
                if (switchToPtt) {
                    mSettings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
                    render(FunkState.current());
                } else {
                    openApp(mErrorNeedsApp);
                }
            });
        } else {
            mAction.setVisibility(View.GONE);
        }
    }

    private void showError(@Nullable String message, boolean needsApp) {
        mError = message != null ? message : getString(R.string.funk_not_connected);
        mErrorNeedsApp = needsApp;
        mConnectRequested = false;
        render(FunkState.current());
    }

    // ---------------------------------------------------------------- talking

    @Override
    public void onPttDown() {
        mHandler.removeCallbacks(mIdleClose);
        if (mService != null) {
            mService.onTalkKeyDown();
        }
        render(FunkState.current());
    }

    @Override
    public void onPttUp() {
        stopTalking();
        scheduleIdleClose();
    }

    private void stopTalking() {
        if (mService != null) {
            mService.onTalkKeyUp();
        }
        render(FunkState.current());
    }

    @Override
    public void onPttTap(PttButtonView.Mode mode) {
        if (mode == PttButtonView.Mode.OFFLINE) {
            if (mServer != null) {
                connect(mServer);
            } else {
                openApp(false);
            }
        } else if (mode == PttButtonView.Mode.MUTED && mService != null && mService.isConnected()) {
            try {
                mService.HumlaSession().setSelfMuteDeafState(false, false);
            } catch (RuntimeException e) {
                // Disconnected meanwhile.
            }
        }
    }

    private boolean isPttKey(int keyCode) {
        return (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && mFunkPrefs.isVolumePttInPopup())
                || (keyCode == mSettings.getPushToTalkKey() && keyCode != KeyEvent.KEYCODE_UNKNOWN);
    }

    private boolean canTalk() {
        PttButtonView.Mode mode = mButton.getMode();
        return mService != null && (mode == PttButtonView.Mode.READY || mode == PttButtonView.Mode.TALKING);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isPttKey(keyCode) && (mKeyTalking || canTalk())) {
            if (event.getRepeatCount() == 0 && !mKeyTalking) {
                mKeyTalking = true;
                mHandler.removeCallbacks(mIdleClose);
                mButton.hapticStart();
                mService.onTalkKeyDown();
                render(FunkState.current());
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isPttKey(keyCode) && mKeyTalking) {
            mKeyTalking = false;
            mButton.hapticStop();
            stopTalking();
            scheduleIdleClose();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------------------------------------------------------- connecting

    private void connect(@NonNull Server server) {
        mError = null;
        mErrorNeedsApp = false;
        mPendingConnect = server;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mFunkPrefs.wereNotificationsAsked()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            mFunkPrefs.setNotificationsAsked();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        if (mSettings.isTorEnabled() && !OrbotHelper.isOrbotInstalled(this)) {
            mPendingConnect = null;
            showError(getString(R.string.funk_error_tor), true);
            return;
        }

        mFunkPrefs.setLastServerId(server.getId());
        mConnectRequested = true;
        FunkState state = FunkState.current();
        if (mService != null && state.connection == FunkState.Connection.RECONNECTING) {
            mService.cancelReconnect();
            startConnect();
        } else if (mService != null && state.connection != FunkState.Connection.DISCONNECTED) {
            // Connected to another server: close that connection first (connect() would not).
            mWaitingForDisconnect = true;
            mService.disconnect();
            mHandler.postDelayed(mContinueSwitch, SWITCH_TIMEOUT_MS);
        } else {
            startConnect();
        }
        render(state);
    }

    private void continueSwitch() {
        if (!mWaitingForDisconnect) {
            return;
        }
        mWaitingForDisconnect = false;
        startConnect();
    }

    private void startConnect() {
        Server server = mPendingConnect;
        mPendingConnect = null;
        if (server == null) {
            return;
        }
        mConnectRequested = true;
        new ServerConnectTask(this, mDatabase).execute(server);
        if (!mBound) {
            mBound = bindService(new Intent(this, MumlaService.class), mConnection, 0);
        }
        render(FunkState.current());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Server pending = mPendingConnect;
        if (pending == null) {
            return;
        }
        if (requestCode == REQUEST_RECORD_AUDIO
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            mPendingConnect = null;
            showError(getString(R.string.funk_error_mic_permission), false);
            return;
        }
        connect(pending);
    }

    // ---------------------------------------------------------------- open the full app

    private void openApp(boolean connectThere) {
        final Intent intent = new Intent(this, MumlaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (connectThere && mServer != null) {
            intent.putExtra(MumlaActivity.EXTRA_CONNECT_SERVER_ID, mServer.getId());
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard != null
                && keyguard.isKeyguardLocked()) {
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    startActivity(intent);
                    finish();
                }
            });
            return;
        }
        startActivity(intent);
        finish();
    }
}
