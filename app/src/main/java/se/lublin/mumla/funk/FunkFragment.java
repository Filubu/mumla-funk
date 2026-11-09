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

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import java.util.Date;
import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;

/**
 * Walkie-talkie screen of the app, shown while connected to a server that is set up as
 * walkie-talkie server. It looks like a modern handset: a dark display on top (server, ping, who
 * is talking right now, who talked last, who is online) and the big hold-to-talk button at the
 * bottom, within thumb reach. Other servers get Mumla's channel list instead (see MumlaActivity).
 *
 * <p>Two designs, chosen in Settings › Walkie-talkie › Design ({@link FunkPrefs#isHandsetDesign()}):
 * the default one ({@code fragment_funk.xml}) and a handheld radio with a glowing display and
 * channel keys ({@code fragment_funk_handset.xml}, display filled by {@link FunkHandset}). A
 * change of the setting takes effect when the screen comes back.
 */
public class FunkFragment extends HumlaServiceFragment implements FunkState.Listener,
        PttButtonView.Listener {
    /** The ping is measured every 5 s while active; refreshing the display more often is enough. */
    private static final long PING_REFRESH_MS = 2_000;
    private static final long PING_GOOD_MS = 120;
    private static final long PING_OK_MS = 300;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPingRefresh = new Runnable() {
        @Override
        public void run() {
            renderPing(FunkState.current());
            mHandler.postDelayed(this, PING_REFRESH_MS);
        }
    };

    private Settings mSettings;
    private FunkPrefs mFunkPrefs;
    @Nullable
    private Server mServer;

    /** Holds the layout of the chosen design; swapped when the setting changes. */
    private FrameLayout mFrame;
    private boolean mHandsetDesign;
    /** Display of the handheld radio design, null in the default design. */
    @Nullable
    private FunkHandset mHandset;
    @Nullable
    private View mChannelPrev;
    @Nullable
    private View mChannelNext;
    /** Whether the power key currently shows "Off" (connected); null: not yet set. */
    @Nullable
    private Boolean mPowerKeyOn;

    // Default design only (null in the handset design).
    private ImageView mStatusIcon;
    private TextView mServerName;
    private TextView mServerStatus;
    private View mPing;
    private ImageView mPingIcon;
    private TextView mPingText;
    private TextView mNow;
    private TextView mNowSub;
    private View mUsersSection;
    private TextView mUsersHeader;
    private LinearLayout mUsers;
    private View mUsersEmpty;
    private TextView mLabel;
    private TextView mHint;

    // Both designs.
    private TextView mConnectToggle;
    private View mChannels;
    private PttButtonView mPtt;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mSettings = Settings.getInstance(requireContext());
        mFunkPrefs = FunkPrefs.get(requireContext());
        mFrame = new FrameLayout(requireContext());
        inflateDesign(inflater);
        return mFrame;
    }

    /** Inflates the layout of the design chosen in the settings and binds its views. */
    private void inflateDesign(@NonNull LayoutInflater inflater) {
        if (mHandset != null) {
            mHandset.stop();
        }
        mHandsetDesign = mFunkPrefs.isHandsetDesign();
        mPowerKeyOn = null;
        mFrame.removeAllViews();
        View view = inflater.inflate(mHandsetDesign ? R.layout.fragment_funk_handset
                : R.layout.fragment_funk, mFrame, false);
        mFrame.addView(view);
        mStatusIcon = view.findViewById(R.id.funk_status_icon);
        mServerName = view.findViewById(R.id.funk_server_name);
        mServerStatus = view.findViewById(R.id.funk_server_status);
        mPing = view.findViewById(R.id.funk_ping);
        mPingIcon = view.findViewById(R.id.funk_ping_icon);
        mPingText = view.findViewById(R.id.funk_ping_text);
        mNow = view.findViewById(R.id.funk_now);
        mNowSub = view.findViewById(R.id.funk_now_sub);
        mUsersSection = view.findViewById(R.id.funk_users_section);
        mUsersHeader = view.findViewById(R.id.funk_users_header);
        mUsers = view.findViewById(R.id.funk_users);
        mUsersEmpty = view.findViewById(R.id.funk_users_empty);
        mConnectToggle = view.findViewById(R.id.funk_connect_toggle);
        mChannels = view.findViewById(R.id.funk_channels);
        mPtt = view.findViewById(R.id.funk_ptt);
        mLabel = view.findViewById(R.id.funk_label);
        mHint = view.findViewById(R.id.funk_hint);
        mChannelPrev = view.findViewById(R.id.funk_channel_prev);
        mChannelNext = view.findViewById(R.id.funk_channel_next);

        mConnectToggle.setOnClickListener(v -> toggleConnection());
        mChannels.setOnClickListener(v -> activity().showDrawerItem(DrawerAdapter.ITEM_SERVER));
        mPtt.setListener(this);
        if (mHandsetDesign) {
            mHandset = new FunkHandset(view);
            mPtt.setLook(PttButtonView.Look.KEY);
            if (mChannelPrev != null && mChannelNext != null) {
                mChannelPrev.setOnClickListener(v -> stepChannel(-1));
                mChannelNext.setOnClickListener(v -> stepChannel(1));
            }
            FunkHandset.asButton(mChannelPrev, mChannels, mChannelNext, mConnectToggle);
            TextView plate = view.findViewById(R.id.handset_plate);
            plate.setText(getString(R.string.handset_plate, BuildConfig.VERSION_NAME));
        } else {
            mHandset = null;
        }
        sizeButton();
    }

    /** As big as possible, but the display keeps room for a few names (and landscape works). */
    private void sizeButton() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / dm.density;
        float heightDp = dm.heightPixels / dm.density;
        boolean landscape = widthDp > heightDp;
        if (mHandsetDesign) {
            // A wide key, about a fifth of the screen high, plus room for its glow and edge.
            float key = landscape ? heightDp - 280 : heightDp * 0.21f;
            key = Math.max(landscape ? 84 : 110, Math.min(landscape ? 150 : 180, key));
            ViewGroup.LayoutParams lp = mPtt.getLayoutParams();
            lp.height = (int) ((key + PttButtonView.KEY_EXTRA_HEIGHT_DP) * dm.density);
            mPtt.setLayoutParams(lp);
            return;
        }
        float size = landscape ? heightDp - 230 : Math.min(widthDp - 120, heightDp * 0.32f);
        size = Math.max(132, Math.min(240, size));
        ViewGroup.LayoutParams lp = mPtt.getLayoutParams();
        lp.width = lp.height = (int) (size * dm.density);
        mPtt.setLayoutParams(lp);
    }

    private MumlaActivity activity() {
        return (MumlaActivity) requireActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFunkPrefs.isHandsetDesign() != mHandsetDesign) {
            inflateDesign(getLayoutInflater()); // changed in the settings meanwhile
        }
        FunkState.addListener(this);
        resolveServer(FunkState.current());
        render(FunkState.current());
        mHandler.post(mPingRefresh);
        if (mHandset != null) {
            mHandset.resume();
        }
    }

    @Override
    public void onPause() {
        FunkState.removeListener(this);
        mHandler.removeCallbacks(mPingRefresh);
        if (mPtt.isHolding()) {
            onPttUp();
        }
        if (mHandset != null) {
            mHandset.stop();
        }
        super.onPause();
    }

    private void resolveServer(FunkState state) {
        if (state.connection != FunkState.Connection.DISCONNECTED && state.serverId >= 0) {
            Server connected = FunkServers.find(FunkServers.loadAll(requireContext()), state.serverId);
            if (connected != null) {
                mServer = connected;
                return;
            }
        }
        if (mServer == null || state.connection == FunkState.Connection.DISCONNECTED) {
            mServer = FunkServers.resolve(requireContext(), mServer != null ? mServer.getId() : -1);
        }
    }

    @Override
    public void onFunkStateChanged(@NonNull FunkState state) {
        if (!isAdded()) {
            return;
        }
        if (state.connection != FunkState.Connection.DISCONNECTED
                && (mServer == null || mServer.getId() != state.serverId)) {
            resolveServer(state);
        }
        render(state);
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private void render(@NonNull FunkState state) {
        if (mPtt == null) {
            return;
        }
        boolean on = state.connection != FunkState.Connection.DISCONNECTED;
        boolean connected = state.connection == FunkState.Connection.CONNECTED;

        if (mHandset == null) {
            renderDisplay(state, on, connected);
            mConnectToggle.setText(on ? R.string.funk_disconnect_short : R.string.funk_connect_short);
        }

        // Controls (the handset design sets its texts in renderHandset)
        mConnectToggle.setEnabled(on || mServer != null);
        mChannels.setEnabled(connected);

        String label;
        String hint = "";
        PttButtonView.Mode mode;
        if (connected) {
            boolean ptt = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
            if (state.selfMuted) {
                mode = PttButtonView.Mode.MUTED;
                label = getString(R.string.funk_muted);
                hint = getString(R.string.funk_tap_to_unmute);
            } else if (!ptt) {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_not_ptt_mode);
            } else if (isTransmitting(state)) {
                mode = PttButtonView.Mode.TALKING;
                label = getString(R.string.funk_transmitting);
                hint = getString(R.string.funk_release_to_stop);
            } else {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_hold_to_talk);
            }
        } else if (on) {
            mode = PttButtonView.Mode.CONNECTING;
            label = getString(state.connection == FunkState.Connection.RECONNECTING
                    ? R.string.funk_reconnecting : R.string.funk_connecting);
        } else if (mServer == null) {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(R.string.funk_no_server);
            hint = getString(R.string.funk_no_server_hint);
        } else {
            mode = PttButtonView.Mode.OFFLINE;
            label = getString(R.string.funk_tap_to_connect);
        }
        mPtt.setMode(mode);
        if (mHandset != null) {
            renderHandset(state, on, mode, label, hint);
        } else {
            FunkMotion.swapText(mLabel, label);
            FunkMotion.swapText(mHint, hint);
        }
    }

    /**
     * Handheld radio design: lamps and display, the label on the key, the power key and the
     * channel keys. {@code label} and {@code hint} are those of the default design; the key
     * shows the hint, else the label, under "PTT".
     */
    private void renderHandset(@NonNull FunkState state, boolean on, PttButtonView.Mode pttMode,
                               String label, String hint) {
        FunkHandset handset = mHandset;
        if (handset == null) {
            return;
        }
        FunkHandset.Mode mode = FunkHandset.modeOf(state.connection, state, isTransmitting(state));
        String serverName = on && state.serverName != null ? state.serverName
                : mServer != null ? FunkServers.displayName(mServer) : null;
        handset.render(mode, state, serverName, !on && mServer == null
                ? getString(R.string.funk_no_server) : null);
        renderPing(state);

        mPtt.setKeyText(FunkHandset.keyLabel(requireContext(), pttMode, mode),
                hint.isEmpty() ? label : hint);
        mPtt.setReceiving(mode == FunkHandset.Mode.RX || mode == FunkHandset.Mode.BUSY);

        // Power key: amber "On" while off, a plain "Off" while on.
        if (mPowerKeyOn == null || mPowerKeyOn != on) {
            mPowerKeyOn = on;
            mConnectToggle.setText(on ? R.string.handset_power_off : R.string.handset_power_on);
            mConnectToggle.setContentDescription(getString(on ? R.string.funk_disconnect_short
                    : R.string.funk_connect_short));
            mConnectToggle.setBackgroundResource(on ? R.drawable.bg_handset_key
                    : R.drawable.bg_handset_key_power);
            mConnectToggle.setTextColor(ContextCompat.getColorStateList(requireContext(),
                    on ? R.color.handset_key_text : R.color.handset_power_text));
        }
        boolean canStep = state.connection == FunkState.Connection.CONNECTED
                && state.channelIds.size() > 1;
        if (mChannelPrev != null && mChannelNext != null) {
            mChannelPrev.setEnabled(canStep);
            mChannelNext.setEnabled(canStep);
        }
    }

    /** Channel keys of the handheld radio design: join the previous or next channel. */
    private void stepChannel(int direction) {
        FunkState state = FunkState.current();
        IMumlaService service = getService();
        int count = state.channelIds.size();
        if (state.connection != FunkState.Connection.CONNECTED || service == null || count < 2) {
            return;
        }
        int index = Math.max(0, state.channelIndex());
        int next = state.channelIds.get(((index + direction) % count + count) % count);
        try {
            service.HumlaSession().joinChannel(next);
        } catch (RuntimeException e) {
            // Disconnected meanwhile.
        }
    }

    /** Display of the default design: server, ping, who is talking and who is online. */
    private void renderDisplay(@NonNull FunkState state, boolean on, boolean connected) {
        // Display: server and channel
        String name = on && state.serverName != null ? state.serverName
                : mServer != null ? FunkServers.displayName(mServer) : getString(R.string.funk_no_server);
        mServerName.setText(name);
        mStatusIcon.setImageResource(connected ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
        ImageViewCompat.setImageTintList(mStatusIcon, ColorStateList.valueOf(
                color(connected ? R.color.funk_live : R.color.funk_on_surface_muted)));
        switch (state.connection) {
            case CONNECTED:
                int online = state.members.size();
                mServerStatus.setText(state.channelName != null
                        ? getString(R.string.funk_channel_online, state.channelName, online)
                        : getString(R.string.funk_online_count, online));
                break;
            case CONNECTING:
                mServerStatus.setText(R.string.funk_connecting);
                break;
            case RECONNECTING:
                mServerStatus.setText(R.string.funk_reconnecting);
                break;
            default:
                mServerStatus.setText(R.string.funk_not_connected);
        }
        renderPing(state);
        renderNow(state);

        // Display: who is online
        mUsersSection.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        if (connected) {
            mUsersHeader.setText(getString(R.string.funk_online_header, state.members.size()));
            FunkMemberList.fill(mUsers, state.members);
            boolean alone = true;
            for (FunkState.Member m : state.members) {
                if (!m.self) {
                    alone = false;
                    break;
                }
            }
            mUsersEmpty.setVisibility(alone ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isTransmitting(@NonNull FunkState state) {
        // While the finger is down, show "transmitting" right away (not in toggle mode).
        return state.selfTalking || (mPtt.isHolding() && !mSettings.isPushToTalkToggle());
    }

    /** The big line of the display: who is talking right now, like a radio's caller ID. */
    private void renderNow(@NonNull FunkState state) {
        String now;
        String sub = "";
        int nowColor = color(R.color.funk_on_surface);
        if (state.connection == FunkState.Connection.CONNECTED) {
            List<FunkState.Member> talking = state.talkingOthers();
            if (isTransmitting(state) && !state.selfMuted) {
                now = getString(R.string.funk_now_self);
                nowColor = color(R.color.funk_bad);
                if (!talking.isEmpty()) {
                    sub = getString(R.string.funk_x_talking, talking.get(0).name);
                }
            } else if (!talking.isEmpty()) {
                now = talking.size() == 1
                        ? getString(R.string.funk_x_talking, talking.get(0).name)
                        : getString(R.string.funk_several_talking, talking.get(0).name, talking.size() - 1);
                nowColor = color(R.color.funk_live);
            } else if (state.selfMuted) {
                now = getString(R.string.funk_muted);
                nowColor = color(R.color.funk_warn);
                sub = lastTalkerLine();
            } else {
                now = getString(R.string.funk_now_free);
                sub = lastTalkerLine();
                if (sub.isEmpty()) {
                    sub = getString(R.string.funk_now_free_sub);
                }
            }
        } else if (state.connection == FunkState.Connection.CONNECTING) {
            now = getString(R.string.funk_connecting);
            nowColor = color(R.color.funk_on_surface_muted);
        } else if (state.connection == FunkState.Connection.RECONNECTING) {
            now = getString(R.string.funk_now_reconnecting);
            nowColor = color(R.color.funk_warn);
            sub = getString(R.string.funk_reconnecting);
        } else {
            now = getString(R.string.funk_not_connected);
            nowColor = color(R.color.funk_on_surface_muted);
        }
        // The caller-ID line fades to the new text, like a radio display.
        FunkMotion.swapText(mNow, now, nowColor);
        FunkMotion.swapText(mNowSub, sub);
    }

    private String lastTalkerLine() {
        String last = FunkState.lastTalker();
        if (last == null) {
            return "";
        }
        String time = DateFormat.getTimeFormat(requireContext()).format(new Date(FunkState.lastTalkAt()));
        return getString(R.string.funk_now_last, last, time);
    }

    /** Round trip time to the server, colored by quality. In idle mode it is minutes old. */
    private void renderPing(@NonNull FunkState state) {
        if (mPing == null && mHandset == null) {
            return;
        }
        IMumlaService service = getService();
        long ms = state.connection == FunkState.Connection.CONNECTED && service != null
                ? service.getLatencyMillis() : -1;
        if (mHandset != null) {
            mHandset.renderPing(ms, ms >= 0 && service.isConnectionIdle());
            return;
        }
        if (ms < 0) {
            mPing.setVisibility(View.GONE);
            return;
        }
        mPing.setVisibility(View.VISIBLE);
        boolean idle = service.isConnectionIdle();
        int quality = ms <= PING_GOOD_MS ? R.color.funk_live
                : ms <= PING_OK_MS ? R.color.funk_warn : R.color.funk_bad;
        ImageViewCompat.setImageTintList(mPingIcon, ColorStateList.valueOf(color(quality)));
        mPingText.setText(idle ? getString(R.string.funk_ping_idle, ms) : getString(R.string.funk_ping_ms, ms));
        mPing.setContentDescription(getString(idle ? R.string.funk_ping_idle_desc : R.string.funk_ping_desc, ms));
    }

    private void toggleConnection() {
        IMumlaService service = getService();
        FunkState state = FunkState.current();
        if (state.connection != FunkState.Connection.DISCONNECTED && service != null) {
            boolean reconnecting = state.connection == FunkState.Connection.RECONNECTING;
            if (reconnecting) {
                service.cancelReconnect();
            }
            service.disconnect();
            if (reconnecting) {
                // The connection is gone already, no "disconnected" event follows.
                activity().showDrawerItem(DrawerAdapter.ITEM_FAVOURITES);
            }
        } else if (mServer != null) {
            connect(mServer);
        }
    }

    private void connect(@NonNull Server server) {
        mFunkPrefs.setLastServerId(server.getId());
        activity().connectToServer(server);
    }

    // ---------------------------------------------------------------- PTT button

    @Override
    public void onPttDown() {
        if (getService() != null) {
            getService().onTalkKeyDown();
        }
        render(FunkState.current());
    }

    @Override
    public void onPttUp() {
        if (getService() != null) {
            getService().onTalkKeyUp();
        }
        render(FunkState.current());
    }

    @Override
    public void onPttTap(PttButtonView.Mode mode) {
        if (mode == PttButtonView.Mode.OFFLINE) {
            if (mServer != null) {
                connect(mServer);
            } else {
                activity().showDrawerItem(DrawerAdapter.ITEM_FAVOURITES);
            }
        } else if (mode == PttButtonView.Mode.MUTED && getService() != null
                && getService().isConnected()) {
            try {
                getService().HumlaSession().setSelfMuteDeafState(false, false);
            } catch (RuntimeException e) {
                // Disconnected meanwhile.
            }
        }
    }
}
