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
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;

import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;

/**
 * Walkie-talkie screen of the app, shown while connected to a server that is set up as
 * walkie-talkie server: server + status, one big hold-to-talk button and the list of who is
 * online. Other servers get Mumla's channel list instead (see MumlaActivity).
 */
public class FunkFragment extends HumlaServiceFragment implements FunkState.Listener,
        PttButtonView.Listener {

    private Settings mSettings;
    private FunkPrefs mFunkPrefs;
    @Nullable
    private Server mServer;

    private ImageView mStatusIcon;
    private TextView mServerName;
    private TextView mServerStatus;
    private MaterialButton mConnectToggle;
    private PttButtonView mPtt;
    private TextView mLabel;
    private TextView mHint;
    private View mUsersSection;
    private TextView mUsersHeader;
    private LinearLayout mUsers;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_funk, container, false);
        mSettings = Settings.getInstance(requireContext());
        mFunkPrefs = FunkPrefs.get(requireContext());
        mStatusIcon = view.findViewById(R.id.funk_status_icon);
        mServerName = view.findViewById(R.id.funk_server_name);
        mServerStatus = view.findViewById(R.id.funk_server_status);
        mConnectToggle = view.findViewById(R.id.funk_connect_toggle);
        mPtt = view.findViewById(R.id.funk_ptt);
        mLabel = view.findViewById(R.id.funk_label);
        mHint = view.findViewById(R.id.funk_hint);
        mUsersSection = view.findViewById(R.id.funk_users_section);
        mUsersHeader = view.findViewById(R.id.funk_users_header);
        mUsers = view.findViewById(R.id.funk_users);

        mConnectToggle.setOnClickListener(v -> toggleConnection());
        view.findViewById(R.id.funk_channels).setOnClickListener(v ->
                activity().showDrawerItem(DrawerAdapter.ITEM_SERVER));
        mPtt.setListener(this);
        return view;
    }

    private MumlaActivity activity() {
        return (MumlaActivity) requireActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        FunkState.addListener(this);
        resolveServer(FunkState.current());
        render(FunkState.current());
    }

    @Override
    public void onPause() {
        FunkState.removeListener(this);
        if (mPtt.isHolding()) {
            onPttUp();
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

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private void render(@NonNull FunkState state) {
        if (mPtt == null) {
            return;
        }
        boolean on = state.connection != FunkState.Connection.DISCONNECTED;
        boolean connected = state.connection == FunkState.Connection.CONNECTED;
        String name = on && state.serverName != null ? state.serverName
                : mServer != null ? FunkServers.displayName(mServer) : getString(R.string.funk_no_server);
        mServerName.setText(name);

        mStatusIcon.setImageResource(connected ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
        ImageViewCompat.setImageTintList(mStatusIcon, connected
                ? ContextCompat.getColorStateList(requireContext(), R.color.funk_live_text)
                : ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)));

        switch (state.connection) {
            case CONNECTED:
                mServerStatus.setText(state.channelName != null
                        ? getString(R.string.funk_connected_channel, state.channelName)
                        : getString(R.string.funk_connected));
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
        mConnectToggle.setText(on ? R.string.funk_disconnect_short : R.string.funk_connect_short);
        mConnectToggle.setEnabled(on || mServer != null);

        String label;
        String hint = "";
        int hintColor = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        PttButtonView.Mode mode;
        if (connected) {
            boolean ptt = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
            boolean optimistic = mPtt.isHolding() && !mSettings.isPushToTalkToggle();
            if (state.selfMuted) {
                mode = PttButtonView.Mode.MUTED;
                label = getString(R.string.funk_muted);
                hint = getString(R.string.funk_tap_to_unmute);
            } else if (!ptt) {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_not_ptt_mode);
            } else if (state.selfTalking || optimistic) {
                mode = PttButtonView.Mode.TALKING;
                label = getString(R.string.funk_transmitting);
            } else {
                mode = PttButtonView.Mode.READY;
                label = getString(R.string.funk_hold_to_talk);
            }
            List<FunkState.Member> talking = state.talkingOthers();
            if (!talking.isEmpty() && !state.selfMuted) {
                hint = talking.size() == 1
                        ? getString(R.string.funk_x_talking, talking.get(0).name)
                        : getString(R.string.funk_several_talking, talking.get(0).name, talking.size() - 1);
                hintColor = ContextCompat.getColor(requireContext(), R.color.funk_live_text);
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
        mLabel.setText(label);
        mHint.setText(hint);
        mHint.setTextColor(hintColor);

        mUsersSection.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) {
            renderUsers(state);
        }
    }

    private void renderUsers(FunkState state) {
        mUsersHeader.setText(getString(R.string.funk_online_header, state.members.size()));
        mUsers.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int others = 0;
        for (FunkState.Member m : state.members) {
            if (!m.self) {
                others++;
            }
            View row = inflater.inflate(R.layout.funk_user_row, mUsers, false);
            ((ImageView) row.findViewById(R.id.row_dot)).setImageResource(m.talking
                    ? R.drawable.funk_dot_talk : m.sameChannel ? R.drawable.funk_dot_live : R.drawable.funk_dot_idle);
            ((TextView) row.findViewById(R.id.row_name)).setText(
                    m.self ? getString(R.string.funk_you, m.name) : m.name);
            ((TextView) row.findViewById(R.id.row_channel)).setText(
                    m.sameChannel || m.channel == null ? "" : m.channel);
            row.findViewById(R.id.row_muted).setVisibility(m.muted ? View.VISIBLE : View.GONE);
            mUsers.addView(row);
        }
        if (others == 0) {
            TextView empty = new TextView(requireContext());
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            empty.setPadding(pad, pad / 2, pad, pad / 2);
            empty.setText(R.string.funk_nobody_online);
            empty.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            mUsers.addView(empty);
        }
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
