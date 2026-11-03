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

import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import se.lublin.humla.HumlaService;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.TalkState;

/**
 * Immutable snapshot of what the walkie-talkie UI (popup, main screen, widgets) shows:
 * connection, server, and who is online / talking. Published by the service on the main thread.
 */
public final class FunkState {
    private static final String TAG = FunkState.class.getName();

    public enum Connection {DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING}

    public static final class Member {
        public final String name;
        @Nullable
        public final String channel;
        public final boolean talking;
        public final boolean muted;
        public final boolean self;
        public final boolean sameChannel;

        Member(String name, @Nullable String channel, boolean talking, boolean muted,
               boolean self, boolean sameChannel) {
            this.name = name;
            this.channel = channel;
            this.talking = talking;
            this.muted = muted;
            this.self = self;
            this.sameChannel = sameChannel;
        }
    }

    public interface Listener {
        void onFunkStateChanged(@NonNull FunkState state);
    }

    public static final FunkState DISCONNECTED = new FunkState(Connection.DISCONNECTED, -1, null,
            null, Collections.emptyList(), false, false);

    private static volatile FunkState sCurrent = DISCONNECTED;
    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();

    public final Connection connection;
    /** Saved server id, or -1 (not connected, or an unsaved server). */
    public final long serverId;
    @Nullable
    public final String serverName;
    @Nullable
    public final String channelName;
    /** Everyone on the server, own channel first. */
    public final List<Member> members;
    public final boolean selfTalking;
    /** Self-muted, muted by the server or suppressed: we cannot transmit. */
    public final boolean selfMuted;

    private FunkState(Connection connection, long serverId, @Nullable String serverName,
                      @Nullable String channelName, List<Member> members, boolean selfTalking,
                      boolean selfMuted) {
        this.connection = connection;
        this.serverId = serverId;
        this.serverName = serverName;
        this.channelName = channelName;
        this.members = Collections.unmodifiableList(members);
        this.selfTalking = selfTalking;
        this.selfMuted = selfMuted;
    }

    @NonNull
    public static FunkState current() {
        return sCurrent;
    }

    public static void addListener(Listener listener) {
        sListeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    @MainThread
    public static void publish(@NonNull FunkState state) {
        sCurrent = state;
        for (Listener listener : sListeners) {
            listener.onFunkStateChanged(state);
        }
    }

    /** True if connected, connecting or reconnecting to the given saved server. */
    public boolean isOn(long id) {
        return id >= 0 && id == serverId && connection != Connection.DISCONNECTED;
    }

    public boolean isConnectedTo(long id) {
        return id >= 0 && id == serverId && connection == Connection.CONNECTED;
    }

    /** Other users currently talking. */
    @NonNull
    public List<Member> talkingOthers() {
        List<Member> result = new ArrayList<>();
        for (Member member : members) {
            if (member.talking && !member.self) {
                result.add(member);
            }
        }
        return result;
    }

    /** Builds a snapshot of the service. Must be called on the main thread (model thread). */
    @MainThread
    @NonNull
    public static FunkState build(@NonNull HumlaService service) {
        Connection connection;
        switch (service.getConnectionState()) {
            case CONNECTING:
                connection = Connection.CONNECTING;
                break;
            case CONNECTED:
                connection = Connection.CONNECTED;
                break;
            case CONNECTION_LOST:
                connection = service.isReconnecting() ? Connection.RECONNECTING : Connection.DISCONNECTED;
                break;
            default:
                connection = Connection.DISCONNECTED;
        }
        Server server = service.getTargetServer();
        if (connection == Connection.DISCONNECTED || server == null) {
            return DISCONNECTED;
        }
        long serverId = server.isSaved() ? server.getId() : -1;
        String serverName = FunkServers.displayName(server);
        if (connection != Connection.CONNECTED) {
            return new FunkState(connection, serverId, serverName, null,
                    Collections.emptyList(), false, false);
        }

        String channelName = null;
        boolean selfMuted = false;
        boolean selfTalking = false;
        List<Member> members = new ArrayList<>();
        try {
            IUser self = service.getSessionUser();
            IChannel ownChannel = self != null ? self.getChannel() : null;
            if (ownChannel != null) {
                channelName = ownChannel.getName();
            }
            if (self != null) {
                selfMuted = self.isSelfMuted() || self.isMuted() || self.isSuppressed();
            }
            selfTalking = service.isTalking() && !selfMuted;
            int selfSession = self != null ? self.getSession() : -1;
            collect(service.getRootChannel(), ownChannel, selfSession, members);
        } catch (RuntimeException e) {
            // Not (or no longer) synchronized with the server; show what we have.
            Log.d(TAG, "build: " + e);
        }
        Collections.sort(members, (a, b) -> {
            if (a.sameChannel != b.sameChannel) {
                return a.sameChannel ? -1 : 1;
            }
            int c = compareNullable(a.channel, b.channel);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return new FunkState(connection, serverId, serverName, channelName, members,
                selfTalking, selfMuted);
    }

    private static void collect(@Nullable IChannel channel, @Nullable IChannel ownChannel,
                                int selfSession, List<Member> out) {
        if (channel == null) {
            return;
        }
        for (IUser user : new ArrayList<IUser>(channel.getUsers())) {
            if (user == null) {
                continue;
            }
            TalkState talkState = user.getTalkState();
            boolean talking = talkState == TalkState.TALKING || talkState == TalkState.SHOUTING
                    || talkState == TalkState.WHISPERING;
            boolean muted = user.isSelfMuted() || user.isMuted() || user.isSuppressed();
            boolean sameChannel = ownChannel != null && ownChannel.getId() == channel.getId();
            out.add(new Member(user.getName(), channel.getName(), talking, muted,
                    user.getSession() == selfSession, sameChannel));
        }
        for (IChannel sub : new ArrayList<IChannel>(channel.getSubchannels())) {
            collect(sub, ownChannel, selfSession, out);
        }
    }

    private static int compareNullable(@Nullable String a, @Nullable String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareToIgnoreCase(b);
    }
}
