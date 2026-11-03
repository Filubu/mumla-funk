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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;

/**
 * Helpers to look up the user's saved (favourite) servers.
 */
public final class FunkServers {
    private FunkServers() {
    }

    @NonNull
    public static List<Server> loadAll(Context context) {
        MumlaDatabase db = new MumlaSQLiteDatabase(context);
        try {
            db.open();
            return db.getServers();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        } finally {
            db.close();
        }
    }

    @Nullable
    public static Server find(List<Server> servers, long id) {
        if (id < 0) {
            return null;
        }
        for (Server server : servers) {
            if (server.getId() == id) {
                return server;
            }
        }
        return null;
    }

    /**
     * The server the walkie-talkie should use when nothing more specific is known:
     * the requested one, else the last used one, else the first saved one.
     */
    @Nullable
    public static Server resolve(Context context, long requestedId) {
        List<Server> servers = loadAll(context);
        Server server = find(servers, requestedId);
        if (server == null) {
            server = find(servers, FunkPrefs.get(context).getLastServerId());
        }
        if (server == null && !servers.isEmpty()) {
            server = servers.get(0);
        }
        return server;
    }

    @NonNull
    public static String displayName(@NonNull Server server) {
        String name = server.getName();
        return name == null || name.trim().isEmpty() ? server.getHost() : name;
    }
}
