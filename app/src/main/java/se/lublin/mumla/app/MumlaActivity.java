/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.app;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.NotNull;
import org.spongycastle.util.encoders.Hex;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.MumbleURLParser;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.channel.AccessTokenFragment;
import se.lublin.mumla.channel.ChannelFragment;
import se.lublin.mumla.channel.ServerInfoFragment;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.db.PublicServer;
import se.lublin.mumla.funk.FunkFragment;
import se.lublin.mumla.funk.FunkPrefs;
import se.lublin.mumla.funk.widget.FunkWidgets;
import se.lublin.mumla.preference.MumlaCertificateGenerateTask;
import se.lublin.mumla.preference.SettingsActivity;
import se.lublin.mumla.servers.FavouriteServerListFragment;
import se.lublin.mumla.servers.PublicServerListFragment;
import se.lublin.mumla.servers.ServerEditFragment;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;
import se.lublin.mumla.util.HumlaServiceProvider;
import se.lublin.mumla.util.MumlaTrustStore;

public class MumlaActivity extends AppCompatActivity implements ListView.OnItemClickListener,
        FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider, DatabaseProvider,
        SharedPreferences.OnSharedPreferenceChangeListener, DrawerAdapter.DrawerDataProvider,
        ServerEditFragment.ServerEditListener {
    private static final String TAG = MumlaActivity.class.getName();

    /**
     * If specified, the provided integer drawer fragment ID is shown when the activity is created.
     */
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";
    /** Mumla Funk: connect to this saved server id (e.g. from the popup, to confirm a certificate). */
    public static final String EXTRA_CONNECT_SERVER_ID = "se.lublin.mumla.funk.extra.CONNECT_SERVER_ID";

    private IMumlaService mService;
    private MumlaDatabase mDatabase;
    private Settings mSettings;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private DrawerAdapter mDrawerAdapter;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;
    private Server mServerPendingPerm = null;
    private boolean mPermPostNotificationsAsked = false;

    private ListView mDrawerList;
    /** The drawer row of the screen shown in the content frame. */
    private int mShownDrawerItem = -1;
    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;

    /** Mumla Funk: on the first bind after start, jump to the screen of a running connection. */
    private boolean mShowConnectedOnBind;
    /** Mumla Funk: whether we were connected when the activity last went to the background. */
    private boolean mConnectedWhenPaused;
    private boolean mPausedOnce;

    /**
     * List of fragments to be notified about service state changes.
     */
    private final List<HumlaServiceFragment> mServiceFragments = new ArrayList<HumlaServiceFragment>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((MumlaService.MumlaBinder) service).getService();
            mService.setSuppressNotifications(true);
            mService.registerObserver(mObserver);
            mService.clearChatNotifications(); // Clear chat notifications on resume.
            mDrawerAdapter.notifyDataSetChanged();

            for (HumlaServiceFragment fragment : mServiceFragments)
                fragment.setServiceBound(true);

            if (mService.isConnected()) {
                // Mumla Funk: opened while connected, or connected meanwhile (e.g. from the
                // walkie-talkie popup): show that connection instead of the server list.
                boolean connectedMeanwhile = mPausedOnce && !mConnectedWhenPaused;
                if ((mShowConnectedOnBind || connectedMeanwhile) && isServerListShown()) {
                    showConnectedScreen();
                }
            } else if (isConnectionDependentFragmentShown() && !isFunkReconnecting()) {
                // Re-show the server list if we're showing a fragment that depends on the service.
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            mShowConnectedOnBind = false;
            updateConnectionState(getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {
            showConnectedScreen();

            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            // Re-show the server list if we're showing a fragment that depends on the service.
            // Mumla Funk: the walkie-talkie screen stays while it reconnects, it shows that itself.
            if (isConnectionDependentFragmentShown() && !isFunkReconnecting()) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            if (chain.length == 0) {
                return;
            }
            final Server lastServer = getService().getTargetServer();
            try {
                final X509Certificate x509 = chain[0];
                View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
                TextView textView = layout.findViewById(R.id.certificate_info_text);
                try {
                    MessageDigest digest1 = MessageDigest.getInstance("SHA-1");
                    MessageDigest digest2 = MessageDigest.getInstance("SHA-256");
                    String hexDigest1 = new String(Hex.encode(digest1.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");
                    String hexDigest2 = new String(Hex.encode(digest2.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");

                    textView.setText(getString(R.string.certificate_info,
                            x509.getSubjectDN().getName(),
                            x509.getNotBefore().toString(),
                            x509.getNotAfter().toString(),
                            hexDigest1.substring(0, hexDigest1.length() - 1),
                            hexDigest2.substring(0, hexDigest2.length() - 1)));
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                    textView.setText(x509.toString());
                }
                // Mumla Funk: explain first; most private servers use a self-signed certificate.
                new MaterialAlertDialogBuilder(MumlaActivity.this)
                        .setTitle(R.string.funk_cert_title)
                        .setMessage(getString(R.string.funk_cert_message, lastServer.getHost()))
                        .setView(layout)
                        .setPositiveButton(R.string.funk_cert_trust, (dialog, which) -> {
                            // Try to add to trust store
                            try {
                                String alias = lastServer.getHost();
                                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                                trustStore.setCertificateEntry(alias, x509);
                                MumlaTrustStore.saveTrustStore(MumlaActivity.this, trustStore);
                                Toast.makeText(MumlaActivity.this, R.string.trust_added, Toast.LENGTH_LONG).show();
                                connectToServer(lastServer);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(MumlaActivity.this, R.string.trust_add_failed, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPermissionDenied(String reason) {
            new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.perm_denied)
                    .setMessage(reason)
                    .show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSettings = Settings.getInstance(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mService != null && mService.isConnected()) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.disconnectSure, mService.getTargetServer().getName()))
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                mService.disconnect();
                                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new MumlaSQLiteDatabase(this); // TODO add support for cloud storage
        mDatabase.open();

        mDrawerLayout = findViewById(R.id.drawer_layout);
        mDrawerList = findViewById(R.id.left_drawer);

        View headerView = getLayoutInflater().inflate(R.layout.list_drawer_headerlogo, mDrawerList, false);
        mDrawerList.addHeaderView(headerView, null, false);

        mDrawerList.setOnItemClickListener(this);
        mDrawerAdapter = new DrawerAdapter(this, this);
        mDrawerList.setAdapter(mDrawerAdapter);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerClosed(View drawerView) {
                supportInvalidateOptionsMenu();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                super.onDrawerStateChanged(newState);
                // Prevent push to talk from getting stuck on when the drawer is opened.
                if (getService() != null && getService().isConnected()) {
                    IHumlaSession session = getService().HumlaSession();
                    if (session.isTalking() && !mSettings.isPushToTalkToggle()) {
                        session.setTalkingState(false);
                    }
                }
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                supportInvalidateOptionsMenu();
            }
        };

        mDrawerLayout.setDrawerListener(mDrawerToggle);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        if (savedInstanceState == null) {
            // Mumla Funk: the server list is home. Connecting opens the walkie-talkie screen or
            // the channel list, depending on the server.
            if (getIntent() != null && getIntent().hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(getIntent().getIntExtra(EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES));
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                mShowConnectedOnBind = true;
            }
            handleConnectExtra(getIntent());
        }

        // If we're given a Mumble URL to show, open up a server edit fragment.
        if (getIntent() != null &&
                Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String url = getIntent().getDataString();
            try {
                Server server = MumbleURLParser.parseURL(url);

                // Open a dialog prompting the user to connect to the Mumble server.
                DialogFragment fragment = ServerEditFragment.createServerEditDialog(
                        MumlaActivity.this, server, ServerEditFragment.Action.CONNECT_ACTION, true);
                fragment.show(getSupportFragmentManager(), "url_edit");
            } catch (MalformedURLException e) {
                Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }

        setVolumeControlStream(mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);

        if (savedInstanceState == null) {
            // Got no instance bundle: this is run only on real app startup -- not when Android
            // recreates the activity on configuration change, like screen rotation.
            if (mSettings.isFirstRun()) {
                generateFirstCertificate();
            }
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleConnectExtra(intent);
    }

    /** Mumla Funk: the popup asks us to connect (to show certificate / password dialogs). */
    private void handleConnectExtra(@Nullable Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_CONNECT_SERVER_ID)) {
            return;
        }
        long id = intent.getLongExtra(EXTRA_CONNECT_SERVER_ID, -1);
        intent.removeExtra(EXTRA_CONNECT_SERVER_ID);
        for (Server server : mDatabase.getServers()) {
            if (server.getId() == id) {
                connectToServer(server);
                return;
            }
        }
    }

    /** True if the shown fragment only makes sense while connected (walkie-talkie, channels, ...). */
    private boolean isConnectionDependentFragmentShown() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        return fragment instanceof HumlaServiceFragment;
    }

    private boolean isServerListShown() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        return fragment instanceof FavouriteServerListFragment;
    }

    /** The walkie-talkie screen is shown and the connection is being re-established. */
    private boolean isFunkReconnecting() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        return fragment instanceof FunkFragment && mService != null && mService.isReconnecting();
    }

    /**
     * Mumla Funk: after connecting, walkie-talkie servers get the walkie-talkie screen, all
     * others (and unsaved ones, e.g. public servers) Mumla's channel list.
     */
    private void showConnectedScreen() {
        if (mSettings.shouldStartUpInPinnedMode()) {
            loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS);
            return;
        }
        Server server = mService != null ? mService.getTargetServer() : null;
        boolean funk = server != null && FunkPrefs.get(this).isFunkServer(server.getId());
        loadDrawerFragment(funk ? DrawerAdapter.ITEM_FUNK : DrawerAdapter.ITEM_SERVER);
    }

    /** Shows one of the drawer's screens. */
    public void showDrawerItem(int drawerItemId) {
        loadDrawerFragment(drawerItemId);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent connectIntent = new Intent(this, MumlaService.class);
        bindService(connectIntent, mConnection, 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mErrorDialog != null)
            mErrorDialog.dismiss();
        if (mConnectingDialog != null)
            mConnectingDialog.dismiss();

        mConnectedWhenPaused = mService != null && mService.isConnected();
        mPausedOnce = true;
        if (mService != null) {
            for (HumlaServiceFragment fragment : mServiceFragments) {
                fragment.setServiceBound(false);
            }
            mService.unregisterObserver(mObserver);
            mService.setSuppressNotifications(false);
        }
        unbindService(mConnection);
    }

    @Override
    protected void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        mDatabase.close();
        super.onDestroy();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem disconnectButton = menu.findItem(R.id.action_disconnect);
        disconnectButton.setVisible(mService != null && mService.isConnected());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.mumla, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NotNull MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item))
            return true;
        if (item.getItemId() == R.id.action_disconnect) {
            getService().disconnect();
            return true;
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(@NotNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            mService.onTalkKeyDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            mService.onTalkKeyUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mDrawerLayout.closeDrawers();
        loadDrawerFragment((int) id);
        // Settings is a separate screen: keep the highlight on the screen shown here.
        markDrawerItem(mShownDrawerItem);
    }

    /** Mumla Funk: highlights the row of the screen shown (a pill in the drawer). */
    private void markDrawerItem(int drawerItemId) {
        if (mDrawerList == null || mDrawerAdapter == null) {
            return;
        }
        for (int i = 0; i < mDrawerAdapter.getCount(); i++) {
            if (mDrawerAdapter.getItemId(i) == drawerItemId) {
                mDrawerList.setItemChecked(i + mDrawerList.getHeaderViewsCount(), true);
                return;
            }
        }
        mDrawerList.clearChoices();
    }

    /**
     * Mumla Funk: some servers need a client certificate (e.g. to register). Mumla asked about it
     * in a dialog on the first start; we create it quietly instead, one dialog less for new users.
     */
    private void generateFirstCertificate() {
        mSettings.setFirstRun(false);
        if (mSettings.isUsingCertificate()) {
            return;
        }
        new MumlaCertificateGenerateTask(this, true) {
            @Override
            protected void onPostExecute(DatabaseCertificate result) {
                super.onPostExecute(result);
                if (result != null) mSettings.setDefaultCertificateId(result.getId());
            }
        }.execute();
    }

    /**
     * Loads a fragment from the drawer.
     */
    private void loadDrawerFragment(int fragmentId) {
        Class<? extends Fragment> fragmentClass = null;
        Bundle args = new Bundle();
        switch (fragmentId) {
            case DrawerAdapter.ITEM_FUNK:
                fragmentClass = FunkFragment.class;
                break;
            case DrawerAdapter.ITEM_SERVER:
                fragmentClass = ChannelFragment.class;
                break;
            case DrawerAdapter.ITEM_INFO:
                fragmentClass = ServerInfoFragment.class;
                break;
            case DrawerAdapter.ITEM_ACCESS_TOKENS:
                fragmentClass = AccessTokenFragment.class;
                Server connectedServer = getService().getTargetServer();
                args.putLong("server", connectedServer.getId());
                args.putStringArrayList("access_tokens", (ArrayList<String>) mDatabase.getAccessTokens(connectedServer.getId()));
                break;
            case DrawerAdapter.ITEM_PINNED_CHANNELS:
                fragmentClass = ChannelFragment.class;
                args.putBoolean("pinned", true);
                break;
            case DrawerAdapter.ITEM_FAVOURITES:
                fragmentClass = FavouriteServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_PUBLIC:
                fragmentClass = PublicServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_SETTINGS:
                Intent prefIntent = new Intent(this, SettingsActivity.class);
                startActivity(prefIntent);
                return;
            default:
                return;
        }
        Fragment fragment = Fragment.instantiate(this, fragmentClass.getName(), args);
        // Mumla Funk: the new screen fades in and settles by a few dp, the old one just fades.
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.funk_screen_enter, R.anim.funk_screen_exit)
                .replace(R.id.content_frame, fragment, fragmentClass.getName())
                .commit();
        mShownDrawerItem = fragmentId;
        markDrawerItem(fragmentId);
        requireNonNull(getSupportActionBar()).setTitle(mDrawerAdapter.getItemWithId(fragmentId).title);
    }

    public void connectToServer(final Server server) {
        mServerPendingPerm = server;
        connectToServerWithPerm();
    }

    public void connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MumlaActivity.this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mPermPostNotificationsAsked) {
            if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MumlaActivity.this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
                return;
            }
        }

        if (mServerPendingPerm == null) {
            Log.w(TAG, "No pending server after getting permissions");
            return;
        }

        Server server = mServerPendingPerm;
        mServerPendingPerm = null;
        if (server.isSaved()) {
            FunkPrefs.get(this).setLastServerId(server.getId());
        }

        // Check if we're already connected to a server; if so, inform user.
        if (mService != null && mService.isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.reconnect_dialog_message)
                    .setPositiveButton(R.string.connect, (dialog, which) -> {
                        // Register an observer to reconnect to the new server once disconnected.
                        mService.registerObserver(new HumlaObserver() {
                            @Override
                            public void onDisconnected(HumlaException e) {
                                connectToServer(server);
                                mService.unregisterObserver(this);
                            }
                        });
                        mService.disconnect();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        if (mSettings.isTorEnabled()) {
            if (!OrbotHelper.isOrbotInstalled(this)) {
                mSettings.disableTor();
                new MaterialAlertDialogBuilder(MumlaActivity.this)
                        .setMessage(R.string.orbot_not_installed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            } else {
                if (!isPortOpen(HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT, 2000)) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.orbot_tor_failed, HumlaConnection.TOR_PORT))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
            }
        }

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0) {
            return;
        }

        switch (requestCode) {
            case PERMISSIONS_REQUEST_RECORD_AUDIO:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm();
                } else {
                    Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_microphone),
                            Toast.LENGTH_LONG).show();
                }
                break;
            case PERMISSIONS_REQUEST_POST_NOTIFICATIONS:
                mPermPostNotificationsAsked = true;
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // This is inspired by https://stackoverflow.com/a/34612503
                    if (ActivityCompat.shouldShowRequestPermissionRationale(MumlaActivity.this,
                            Manifest.permission.POST_NOTIFICATIONS)) {
                        Toast.makeText(MumlaActivity.this,
                                getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG).show();
                    }
                }
                connectToServerWithPerm();
                break;
        }
    }

    private boolean isPortOpen(final String host, final int port, final int timeout) {
        final AtomicBoolean open = new AtomicBoolean(false);
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(host, port), timeout);
                        socket.close();
                        open.set(true);
                    } catch (Exception e) {
                        Log.d(TAG, "isPortOpen() run()" + e);
                    }
                }
            });
            thread.start();
            thread.join();
            return open.get();
        } catch (Exception e) {
            Log.d(TAG, "isPortOpen() " + e);
        }
        return false;
    }

    public void connectToPublicServer(final PublicServer server) {
        final Settings settings = Settings.getInstance(this);
        final EditText usernameField = new EditText(this);
        usernameField.setHint(settings.getDefaultUsername());
        FrameLayout layout = new FrameLayout(this);
        layout.addView(usernameField);
        int horizontalPadding = (int) getResources().getDimension(R.dimen.padding_medium);
        layout.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        new MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setTitle(R.string.connectToServer)
                .setPositiveButton(R.string.connect, (dialog, which) -> {
                    if (usernameField.getText().toString().isEmpty()) {
                        server.setUsername(settings.getDefaultUsername());
                    } else {
                        server.setUsername(usernameField.getText().toString());
                    }
                    connectToServer(server);
                })
                .show();
    }

    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Updates the activity to represent the connection state of the given service.
     * Will show reconnecting dialog if reconnecting, dismiss otherwise, etc.
     * Basically, this service will do catch-up if the activity wasn't bound to receive
     * connection state updates.
     *
     * @param service A bound IHumlaService.
     */
    private void updateConnectionState(IHumlaService service) {
        if (mConnectingDialog != null) {
            mConnectingDialog.dismiss();
        }
        if (mErrorDialog != null)
            mErrorDialog.dismiss();

        switch (mService.getConnectionState()) {
            case CONNECTING:
                Server server = service.getTargetServer();
                // SRV lookup is done later, so we no longer show the port in the connection
                // progress dialog (and only the configured hostname)
                mConnectingDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.connecting_to_server, server.getHost()) + (mSettings.isTorEnabled() ? " (Tor)" : ""))
                        .setView(R.layout.dialog_progress)
                        .setCancelable(true)
                        .setOnCancelListener(dialog -> {
                            mService.disconnect();
                            Toast.makeText(MumlaActivity.this, R.string.cancelled,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .create();
                mConnectingDialog.show();
                break;
            case CONNECTION_LOST:
                // Only bother the user if the error hasn't already been shown.
                // Mumla Funk: the walkie-talkie screen shows the reconnect itself.
                if (getService() != null && !getService().isErrorShown() && !isFunkReconnecting()) {
                    // TODO? bail out if service gone -- it is happening!
                    if (getService() == null) {
                        break;
                    }
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MumlaActivity.this);
                    builder.setTitle(getString(R.string.connectionRefused) + (mSettings.isTorEnabled() ? " (Tor)" : ""));
                    HumlaException error = getService().getConnectionError();
                    if (error != null && mService.isReconnecting()) {
                        builder.setMessage(error.getMessage() + "\n\n"
                                + getString(R.string.attempting_reconnect,
                                error.getCause() != null ? error.getCause().getMessage() : "unknown"));
                        builder.setPositiveButton(R.string.cancel_reconnect, (dialog, which) -> {
                            if (getService() != null) {
                                getService().cancelReconnect();
                                getService().markErrorShown();
                            }
                        });
                    } else if (error != null &&
                            error.getReason() == HumlaException.HumlaDisconnectReason.REJECT &&
                            (error.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW ||
                                    error.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                        final EditText passwordField = new EditText(this);
                        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        passwordField.setHint(R.string.password);
                        builder.setTitle(R.string.invalid_password);
                        builder.setMessage(error.getMessage());
                        builder.setView(passwordField);
                        builder.setPositiveButton(R.string.reconnect, (dialog, which) -> {
                            Server server1 = getService().getTargetServer();
                            if (server1 == null) {
                                return;
                            }
                            String password = passwordField.getText().toString();
                            server1.setPassword(password);
                            if (server1.isSaved()) {
                                mDatabase.updateServer(server1);
                            }
                            connectToServer(server1);
                        });
                        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                            if (getService() != null) {
                                getService().markErrorShown();
                            }
                        });
                    } else {
                        String msg = error != null ? error.getMessage() : getString(R.string.unknown);
                        builder.setMessage(msg);
                        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (getService() != null) {
                                getService().markErrorShown();
                            }
                        });
                    }
                    builder.setCancelable(false);
                    mErrorDialog = builder.show();
                }
                break;
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    @Override
    public IMumlaService getService() {
        return mService;
    }

    @Override
    public MumlaDatabase getDatabase() {
        return mDatabase;
    }

    @Override
    public void addServiceFragment(HumlaServiceFragment fragment) {
        mServiceFragments.add(fragment);
    }

    @Override
    public void removeServiceFragment(HumlaServiceFragment fragment) {
        mServiceFragments.remove(fragment);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case Settings.PREF_STAY_AWAKE:
                setStayAwake(mSettings.shouldStayAwake());
                break;
            case Settings.PREF_HANDSET_MODE:
                setVolumeControlStream(mSettings.isHandsetMode() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
        }
    }

    @Override
    public boolean isConnected() {
        return mService != null && mService.isConnected();
    }

    @Override
    public String getConnectedServerName() {
        if (mService != null && mService.isConnected()) {
            Server server = mService.getTargetServer();
            return server.getName().isEmpty() ? server.getHost() : server.getName();
        }
        if (BuildConfig.DEBUG)
            throw new RuntimeException("getConnectedServerName should only be called if connected!");
        return "";
    }

    @Override
    public void onServerEdited(ServerEditFragment.Action action, Server server, boolean funk) {
        FunkPrefs funkPrefs = FunkPrefs.get(this);
        switch (action) {
            case ADD_ACTION:
                mDatabase.addServer(server);
                funkPrefs.setFunkServer(server.getId(), funk);
                funkPrefs.setLastServerId(server.getId());
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                // Offer to connect right away; the list also says "tap to connect".
                getSupportFragmentManager().executePendingTransactions();
                Fragment list = getSupportFragmentManager().findFragmentById(R.id.content_frame);
                View anchor = list != null && list.getView() != null
                        ? list.getView() : findViewById(R.id.content_frame);
                Snackbar.make(anchor, getString(R.string.server_added, se.lublin.mumla.funk.FunkServers.displayName(server)), 6000)
                        .setAction(R.string.connect, v -> connectToServer(server))
                        .show();
                break;
            case EDIT_ACTION:
                mDatabase.updateServer(server);
                boolean modeChanged = funkPrefs.isFunkServer(server.getId()) != funk;
                funkPrefs.setFunkServer(server.getId(), funk);
                FunkWidgets.onServerRenamed(this, server.getId(), server.getName());
                Server connected = mService != null && mService.isConnected()
                        ? mService.getTargetServer() : null;
                if (modeChanged && connected != null && connected.getId() == server.getId()) {
                    showConnectedScreen(); // Switch between walkie-talkie and channel list now.
                } else {
                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                }
                break;
            case CONNECT_ACTION:
                connectToServer(server);
                break;
        }
    }
}
