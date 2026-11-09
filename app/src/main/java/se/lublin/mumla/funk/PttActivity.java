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
import android.app.WallpaperColors;
import android.app.WallpaperManager;
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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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
    /** At most this many people are visible in the list above the button; more scroll. */
    private static final int MAX_LIST_ROWS = 5;
    private static final long SWITCH_TIMEOUT_MS = 4_000;
    /** Opening: the card starts this far down and this small. */
    private static final float ENTER_OFFSET_DP = 64f;
    private static final float ENTER_SCALE = 0.92f;
    /** Closing: the card shrinks to this while it slides down. */
    private static final float EXIT_SCALE = 0.96f;

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

    /** Handheld radio design (Settings › Walkie-talkie › Design), read when the popup opens. */
    private boolean mHandsetDesign;
    /** Display of the handheld radio design, null in the default design. */
    @Nullable
    private FunkHandset mHandset;
    private View mRoot;
    private View mCard;
    /** Springs of the card for opening and closing; closing retargets them (see FunkMotion). */
    private SpringAnimation mCardAlpha;
    private SpringAnimation mCardY;
    private SpringAnimation mCardScaleX;
    private SpringAnimation mCardScaleY;
    private boolean mClosing;
    private ImageView mStatusIcon;
    private TextView mServerName;
    private TextView mServerSub;
    private PttButtonView mButton;
    private TextView mLabel;
    private TextView mHint;
    private TextView mAction;
    private View mUsersScroll;
    private ViewGroup mUsers;
    /** Rows of the list that fit on this screen (up to {@link #MAX_LIST_ROWS}). */
    private int mListRows = MAX_LIST_ROWS;

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
        mFunkPrefs = FunkPrefs.get(this);
        mHandsetDesign = mFunkPrefs.isHandsetDesign();
        setContentView(mHandsetDesign ? R.layout.activity_ptt_handset : R.layout.activity_ptt);

        mSettings = Settings.getInstance(this);
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
        mUsersScroll = findViewById(R.id.ptt_users_scroll);
        mUsers = findViewById(R.id.ptt_users);
        if (mHandsetDesign) {
            // Handheld radio design: the display takes the place of server line and list.
            mHandset = new FunkHandset(mCard);
            mButton.setLook(PttButtonView.Look.KEY);
            FunkHandset.asButton(mAction);
        }

        mCardAlpha = FunkMotion.spring(mCard, DynamicAnimation.ALPHA,
                FunkMotion.FADE_IN_STIFFNESS, SpringForce.DAMPING_RATIO_NO_BOUNCY);
        mCardY = FunkMotion.spring(mCard, DynamicAnimation.TRANSLATION_Y,
                FunkMotion.SHEET_IN_STIFFNESS, FunkMotion.SHEET_IN_DAMPING);
        mCardScaleX = FunkMotion.spring(mCard, DynamicAnimation.SCALE_X,
                FunkMotion.SHEET_IN_STIFFNESS, FunkMotion.SHEET_SCALE_DAMPING);
        mCardScaleY = FunkMotion.spring(mCard, DynamicAnimation.SCALE_Y,
                FunkMotion.SHEET_IN_STIFFNESS, FunkMotion.SHEET_SCALE_DAMPING);

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

        // Only the card is drawn, no dimmed background: the system's own animations (the launcher
        // grows the window out of the widget, the notification expands into it) then show no
        // dark rectangle. The card's motion is ours alone, so the window itself does not animate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
        matchSystemBarsToWallpaper();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeAnimated();
            }
        });

        if (savedInstanceState == null && FunkMotion.enabled()) {
            playEnter();
        }
    }

    /**
     * The card rises a little, grows from {@link #ENTER_SCALE} and fades in, on springs (see
     * {@link FunkMotion#SHEET_IN_STIFFNESS}). It starts with the first frame that is drawn, so
     * that inflating the layout does not swallow the beginning of the motion.
     */
    private void playEnter() {
        float density = getResources().getDisplayMetrics().density;
        mCard.setAlpha(0f);
        mCard.setTranslationY(ENTER_OFFSET_DP * density);
        mCard.setScaleX(ENTER_SCALE);
        mCard.setScaleY(ENTER_SCALE);
        OneShotPreDrawListener.add(mCard, () -> {
            if (mClosing) {
                return;
            }
            // Grow out of the bottom edge, where the card sits, like a sheet.
            mCard.setPivotX(mCard.getWidth() / 2f);
            mCard.setPivotY(mCard.getHeight());
            mCardAlpha.animateToFinalPosition(1f);
            mCardY.animateToFinalPosition(0f);
            mCardScaleX.animateToFinalPosition(1f);
            mCardScaleY.animateToFinalPosition(1f);
        });
    }

    /**
     * Without a dimmed background the status and navigation bars show the wallpaper (or the lock
     * screen) behind the popup: use dark icons on a light wallpaper, as the launcher does.
     */
    private void matchSystemBarsToWallpaper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return; // The wallpaper's hints are public from Android 12 on; keep light icons.
        }
        try {
            WallpaperManager wallpaper = WallpaperManager.getInstance(this);
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            WallpaperColors colors = null;
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                colors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_LOCK);
            }
            if (colors == null) {
                colors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            }
            if (colors == null) {
                return;
            }
            boolean darkIcons =
                    (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
            WindowInsetsControllerCompat bars =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            bars.setAppearanceLightStatusBars(darkIcons);
            bars.setAppearanceLightNavigationBars(darkIcons);
        } catch (RuntimeException e) {
            // Not worth failing for; the theme's light icons stay.
        }
    }

    /**
     * Cap the card width on wide screens. On short screens (landscape, small phones) show fewer
     * rows in the list and shrink the button, so that the card always fits.
     */
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
        mListRows = heightDp >= 720 ? MAX_LIST_ROWS : heightDp >= 600 ? 4 : heightDp >= 480 ? 3 : 2;
        if (mHandset != null) {
            // Housing, lamps, display and hint take about 400 dp besides the rows and the key.
            // The spectrum (about 70 dp) only where the key keeps most of its height.
            float rowDp = getResources().getDimension(R.dimen.handset_user_row_height) / density;
            mHandset.setMaxRows(mListRows);
            float spectrumDp = getResources().getDimension(R.dimen.handset_spectrum_height) / density + 24;
            boolean spectrum = heightDp - 400 - mListRows * rowDp - spectrumDp >= 140;
            mHandset.setSpectrumShown(spectrum);
            float key = Math.min(170, heightDp - 400 - mListRows * rowDp - (spectrum ? spectrumDp : 0));
            key = Math.max(84, key);
            ViewGroup.LayoutParams lp = mButton.getLayoutParams();
            lp.height = (int) ((key + PttButtonView.KEY_EXTRA_HEIGHT_DP) * density);
            mButton.setLayoutParams(lp);
            return;
        }
        float rowDp = getResources().getDimension(R.dimen.funk_member_row_height) / density;
        float listDp = mListRows * rowDp + 8 + 12; // rows, padding, margin
        float size = Math.min(216, Math.min(widthDp - 96, heightDp - 290 - listDp));
        size = Math.max(120, size);
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
        if (mHandset != null) {
            mHandset.resume();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mFunkPrefs.isHandsetDesign() != mHandsetDesign) {
            recreate(); // the design was changed in the settings while the popup was in the background
            return;
        }
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
        if (mHandset != null) {
            mHandset.stop();
        }
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
        for (SpringAnimation spring : new SpringAnimation[]{mCardAlpha, mCardY, mCardScaleX, mCardScaleY}) {
            spring.cancel();
        }
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
        if (mClosing) {
            return;
        }
        mClosing = true;
        if (!FunkMotion.enabled() || !mCard.isLaidOut()) {
            finishWithoutAnimation();
            return;
        }
        // The card slides back down and fades. The springs take over the current motion, also
        // when the popup is closed while it is still opening.
        float drop = mCard.getHeight() * 0.6f;
        FunkMotion.springTo(mCardY, drop, FunkMotion.SHEET_OUT_STIFFNESS, FunkMotion.SHEET_OUT_DAMPING);
        FunkMotion.springTo(mCardScaleX, EXIT_SCALE, FunkMotion.SHEET_OUT_STIFFNESS, FunkMotion.SHEET_OUT_DAMPING);
        FunkMotion.springTo(mCardScaleY, EXIT_SCALE, FunkMotion.SHEET_OUT_STIFFNESS, FunkMotion.SHEET_OUT_DAMPING);
        mCardAlpha.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) {
                finishWithoutAnimation();
            }
        });
        FunkMotion.springTo(mCardAlpha, 0f, FunkMotion.FADE_OUT_STIFFNESS,
                SpringForce.DAMPING_RATIO_NO_BOUNCY);
    }

    private void finishWithoutAnimation() {
        if (isFinishing()) {
            return;
        }
        finish();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0); // From Android 14 on: overrideActivityTransition()
        }
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
        boolean connected = targetOn && state.connection == FunkState.Connection.CONNECTED;

        String serverSub;
        String label;
        String hint = null;
        // The hint says who is talking; the handset design shows that in its display instead.
        boolean hintIsTalking = false;
        int hintColor = ContextCompat.getColor(this,
                mHandset != null ? R.color.handset_muted : R.color.funk_on_surface_muted);
        String action = null;
        PttButtonView.Mode mode;

        if (connected) {
            int online = state.members.size();
            serverSub = state.channelName != null
                    ? getString(R.string.funk_channel_online, state.channelName, online)
                    : getString(R.string.funk_online_count, online);
            boolean ptt = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
            if (state.selfMuted) {
                mode = PttButtonView.Mode.MUTED;
                label = getString(R.string.funk_muted);
                hint = getString(R.string.funk_tap_to_unmute);
            } else if (!ptt) {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_not_ptt_mode);
                action = getString(R.string.funk_switch_to_ptt);
            } else if (isTransmitting(state)) {
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
                hintIsTalking = true;
            } else if (hint == null && mode == PttButtonView.Mode.READY && action == null
                    && mFunkPrefs.isVolumePttInPopup()) {
                hint = getString(R.string.funk_volume_hint);
            }
        } else if (targetOn || mConnectRequested) {
            mode = PttButtonView.Mode.CONNECTING;
            boolean reconnecting = targetOn && state.connection == FunkState.Connection.RECONNECTING;
            label = getString(reconnecting ? R.string.funk_reconnecting : R.string.funk_connecting);
            serverSub = getString(R.string.funk_connecting);
        } else if (mServer == null) {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(mServerMissing ? R.string.funk_server_removed : R.string.funk_no_server);
            hint = getString(mServerMissing ? R.string.funk_server_removed_hint : R.string.funk_no_server_hint);
            action = getString(R.string.funk_open_app);
            serverSub = getString(R.string.funk_not_connected);
        } else {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(R.string.funk_tap_to_connect);
            serverSub = getString(R.string.funk_not_connected);
            if (state.connection != FunkState.Connection.DISCONNECTED && state.serverName != null) {
                hint = getString(R.string.funk_widget_other_server, state.serverName);
            }
        }

        if (mError != null && mode == PttButtonView.Mode.OFFLINE) {
            hint = mError;
            hintColor = ContextCompat.getColor(this, R.color.funk_warn);
            hintIsTalking = false;
            if (mErrorNeedsApp) {
                action = getString(R.string.funk_open_app);
            }
        }

        mButton.setMode(mode);
        if (mHandset != null) {
            // On the key under "PTT": what to do now; the line below keeps the other hints.
            String keySub = mode == PttButtonView.Mode.MUTED ? getString(R.string.funk_tap_to_unmute)
                    : mode == PttButtonView.Mode.TALKING ? getString(R.string.funk_release_to_stop)
                    : label;
            renderHandset(mHandset, state, targetOn, serverName, mode, label, keySub);
            boolean showHint = hint != null && !hintIsTalking && !hint.equals(keySub);
            FunkMotion.swapText(mHint, showHint ? hint : "", hintColor);
        } else {
            mServerName.setText(serverName);
            mStatusIcon.setImageResource(connected ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
            ImageViewCompat.setImageTintList(mStatusIcon, ContextCompat.getColorStateList(this,
                    connected ? R.color.funk_live : R.color.funk_on_surface_muted));
            mServerSub.setText(serverSub);
            renderUsers(connected ? state.members : null);
            FunkMotion.swapText(mLabel, label);
            FunkMotion.swapText(mHint, hint != null ? hint : "", hintColor);
        }
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

    /** While the finger or a key is down, show "transmitting" right away (not in toggle mode). */
    private boolean isTransmitting(@NonNull FunkState state) {
        return state.selfTalking
                || ((mButton.isHolding() || mKeyTalking) && !mSettings.isPushToTalkToggle());
    }

    /**
     * Handheld radio design: lamps and display for the server of this popup, the labels on the
     * key. While the popup's server is not the connected one, the display is switched off.
     */
    private void renderHandset(@NonNull FunkHandset handset, @NonNull FunkState state,
                               boolean targetOn, @NonNull String serverName,
                               @NonNull PttButtonView.Mode pttMode, @NonNull String label,
                               @NonNull String keySub) {
        FunkState.Connection connection = targetOn ? state.connection
                : mConnectRequested ? FunkState.Connection.CONNECTING
                : FunkState.Connection.DISCONNECTED;
        FunkHandset.Mode mode = FunkHandset.modeOf(connection, state, isTransmitting(state));
        handset.render(mode, state, serverName, mServer == null ? label : null);
        long ms = connection == FunkState.Connection.CONNECTED && mService != null
                ? mService.getLatencyMillis() : -1;
        handset.renderPing(ms, ms >= 0 && mService.isConnectionIdle());
        mButton.setKeyText(FunkHandset.keyLabel(this, pttMode, mode), keySub);
        mButton.setReceiving(mode == FunkHandset.Mode.RX || mode == FunkHandset.Mode.BUSY);
    }

    /** The list above the button: everyone on the server, at most {@link #mListRows} visible. */
    private void renderUsers(@Nullable List<FunkState.Member> members) {
        if (members == null || members.isEmpty()) {
            mUsersScroll.setVisibility(View.GONE);
            return;
        }
        FunkMemberList.fill(mUsers, members);
        int rows = Math.min(members.size(), mListRows);
        int height = rows * getResources().getDimensionPixelSize(R.dimen.funk_member_row_height)
                + mUsersScroll.getPaddingTop() + mUsersScroll.getPaddingBottom();
        ViewGroup.LayoutParams lp = mUsersScroll.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            mUsersScroll.setLayoutParams(lp);
        }
        mUsersScroll.setVisibility(View.VISIBLE);
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
