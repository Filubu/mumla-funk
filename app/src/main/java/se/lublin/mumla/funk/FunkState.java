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

    /** A channel of the server, for the channel switch of the walkie-talkie screen. */
    public static final class Channel {
        public final int id;
        public final String name;
        /** 0 for the root channel, 1 for its subchannels, and so on. */
        public final int depth;
        /** People in this channel (not in its subchannels). */
        public final int users;

        Channel(int id, String name, int depth, int users) {
            this.id = id;
            this.name = name;
            this.depth = depth;
            this.users = users;
        }
    }

    public interface Listener {
        void onFunkStateChanged(@NonNull FunkState state);
    }

    public static final FunkState DISCONNECTED = new FunkState(Connection.DISCONNECTED, -1, null,
            null, -1, Collections.emptyList(), Collections.emptyList(), false, false);

    private static volatile FunkState sCurrent = DISCONNECTED;
    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();

    /** Who talked last on the current server (not us), for the display; null if nobody yet. */
    @Nullable
    private static String sLastTalker;
    private static long sLastTalkAt;
    @Nullable
    private static String sLastTalkServer;

    public final Connection connection;
    /** Saved server id, or -1 (not connected, or an unsaved server). */
    public final long serverId;
    @Nullable
    public final String serverName;
    @Nullable
    public final String channelName;
    /** Id of the own channel, -1 if unknown. */
    public final int channelId;
    /** All channels of the server in the order of the channel list, for the channel keys. */
    public final List<Integer> channelIds;
    /** The same channels, in the same order, with their names. */
    public final List<Channel> channels;
    /** Everyone on the server, own channel first. */
    public final List<Member> members;
    public final boolean selfTalking;
    /** Self-muted, muted by the server or suppressed: we cannot transmit. */
    public final boolean selfMuted;

    private FunkState(Connection connection, long serverId, @Nullable String serverName,
                      @Nullable String channelName, int channelId, List<Channel> channels,
                      List<Member> members, boolean selfTalking, boolean selfMuted) {
        this.connection = connection;
        this.serverId = serverId;
        this.serverName = serverName;
        this.channelName = channelName;
        this.channelId = channelId;
        List<Integer> ids = new ArrayList<>(channels.size());
        for (Channel channel : channels) {
            ids.add(channel.id);
        }
        this.channelIds = Collections.unmodifiableList(ids);
        this.channels = Collections.unmodifiableList(channels);
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
        rememberLastTalker(state);
        sCurrent = state;
        for (Listener listener : sListeners) {
            listener.onFunkStateChanged(state);
        }
    }

    private static void rememberLastTalker(@NonNull FunkState state) {
        String server = state.connection == Connection.DISCONNECTED ? null
                : state.serverId + "/" + state.serverName;
        if (server == null || !server.equals(sLastTalkServer)) {
            sLastTalker = null;
            sLastTalkAt = 0;
            sLastTalkServer = server;
        }
        if (state.connection == Connection.CONNECTED) {
            List<Member> talking = state.talkingOthers();
            if (!talking.isEmpty()) {
                sLastTalker = talking.get(0).name;
                sLastTalkAt = System.currentTimeMillis();
            }
        }
    }

    /** Name of whoever talked last on this server (not us), or null. Main thread. */
    @Nullable
    public static String lastTalker() {
        return sLastTalker;
    }

    /** {@link System#currentTimeMillis()} of {@link #lastTalker()}'s transmission. */
    public static long lastTalkAt() {
        return sLastTalkAt;
    }

    /** True if connected, connecting or reconnecting to the given saved server. */
    public boolean isOn(long id) {
        return id >= 0 && id == serverId && connection != Connection.DISCONNECTED;
    }

    public boolean isConnectedTo(long id) {
        return id >= 0 && id == serverId && connection == Connection.CONNECTED;
    }

    /** Position of the own channel in {@link #channelIds}, -1 if unknown. */
    public int channelIndex() {
        return channelIds.indexOf(channelId);
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
            return new FunkState(connection, serverId, serverName, null, -1,
                    Collections.emptyList(), Collections.emptyList(), false, false);
        }

        String channelName = null;
        int channelId = -1;
        List<Channel> channels = new ArrayList<>();
        boolean selfMuted = false;
        boolean selfTalking = false;
        List<Member> members = new ArrayList<>();
        try {
            IUser self = service.getSessionUser();
            IChannel ownChannel = self != null ? self.getChannel() : null;
            if (ownChannel != null) {
                channelName = ownChannel.getName();
                channelId = ownChannel.getId();
            }
            if (self != null) {
                selfMuted = self.isSelfMuted() || self.isMuted() || self.isSuppressed();
            }
            selfTalking = service.isTalking() && !selfMuted;
            int selfSession = self != null ? self.getSession() : -1;
            collect(service.getRootChannel(), ownChannel, selfSession, members, channels, 0);
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
        return new FunkState(connection, serverId, serverName, channelName, channelId,
                channels, members, selfTalking, selfMuted);
    }

    /**
     * Collects the users of a channel and its subchannels, and the channels depth-first.
     * Humla keeps subchannels sorted by position and name, so they come in the order of the
     * channel list.
     */
    private static void collect(@Nullable IChannel channel, @Nullable IChannel ownChannel,
                                int selfSession, List<Member> out, List<Channel> channels,
                                int depth) {
        if (channel == null) {
            return;
        }
        List<IUser> users = new ArrayList<IUser>(channel.getUsers());
        channels.add(new Channel(channel.getId(), channel.getName(), depth, users.size()));
        for (IUser user : users) {
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
            collect(sub, ownChannel, selfSession, out, channels, depth + 1);
        }
    }

    private static int compareNullable(@Nullable String a, @Nullable String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareToIgnoreCase(b);
    }
}
