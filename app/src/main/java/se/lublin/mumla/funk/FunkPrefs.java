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
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

/**
 * Preferences of the walkie-talkie ("Funk") features.
 */
public final class FunkPrefs {
    /** Server last used for talking; the default for the popup and the main screen. */
    private static final String PREF_LAST_SERVER = "funk_last_server_id";
    /** In the popup, the volume-down key acts as push-to-talk key. */
    public static final String PREF_VOLUME_PTT_IN_POPUP = "funk_volume_ptt_in_popup";
    public static final boolean DEFAULT_VOLUME_PTT_IN_POPUP = true;
    private static final String PREF_NOTIFICATIONS_ASKED = "funk_notifications_asked";
    private static final String PREF_WIDGET_SERVER = "funk_widget_server_";
    /** Per saved server: show the walkie-talkie UI (true) or Mumla's channel list (false). */
    private static final String PREF_SERVER_FUNK = "funk_server_mode_";
    /** Servers saved before 1.0.5 had no setting; they were all used with the walkie-talkie UI. */
    public static final boolean DEFAULT_SERVER_FUNK = true;
    private static final String PREF_WIDGET_SERVER_NAME = "funk_widget_server_name_";

    private final SharedPreferences mPrefs;

    private FunkPrefs(Context context) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static FunkPrefs get(Context context) {
        return new FunkPrefs(context);
    }

    public long getLastServerId() {
        return mPrefs.getLong(PREF_LAST_SERVER, -1);
    }

    public void setLastServerId(long serverId) {
        if (serverId >= 0) {
            mPrefs.edit().putLong(PREF_LAST_SERVER, serverId).apply();
        }
    }

    public boolean isVolumePttInPopup() {
        return mPrefs.getBoolean(PREF_VOLUME_PTT_IN_POPUP, DEFAULT_VOLUME_PTT_IN_POPUP);
    }

    public boolean wereNotificationsAsked() {
        return mPrefs.getBoolean(PREF_NOTIFICATIONS_ASKED, false);
    }

    public void setNotificationsAsked() {
        mPrefs.edit().putBoolean(PREF_NOTIFICATIONS_ASKED, true).apply();
    }

    /** Whether the saved server uses the walkie-talkie UI. Unsaved servers (id -1) never do. */
    public boolean isFunkServer(long serverId) {
        return serverId >= 0 && mPrefs.getBoolean(PREF_SERVER_FUNK + serverId, DEFAULT_SERVER_FUNK);
    }

    public void setFunkServer(long serverId, boolean funk) {
        if (serverId >= 0) {
            mPrefs.edit().putBoolean(PREF_SERVER_FUNK + serverId, funk).apply();
        }
    }

    /** Forget everything about a deleted server. */
    public void removeServer(long serverId) {
        SharedPreferences.Editor editor = mPrefs.edit().remove(PREF_SERVER_FUNK + serverId);
        if (getLastServerId() == serverId) {
            editor.remove(PREF_LAST_SERVER);
        }
        editor.apply();
    }

    public long getWidgetServerId(int appWidgetId) {
        return mPrefs.getLong(PREF_WIDGET_SERVER + appWidgetId, -1);
    }

    @Nullable
    public String getWidgetServerName(int appWidgetId) {
        return mPrefs.getString(PREF_WIDGET_SERVER_NAME + appWidgetId, null);
    }

    public boolean isWidgetConfigured(int appWidgetId) {
        return mPrefs.contains(PREF_WIDGET_SERVER + appWidgetId);
    }

    public void setWidgetServer(int appWidgetId, long serverId, String serverName) {
        mPrefs.edit()
                .putLong(PREF_WIDGET_SERVER + appWidgetId, serverId)
                .putString(PREF_WIDGET_SERVER_NAME + appWidgetId, serverName)
                .apply();
    }

    public void removeWidget(int appWidgetId) {
        mPrefs.edit()
                .remove(PREF_WIDGET_SERVER + appWidgetId)
                .remove(PREF_WIDGET_SERVER_NAME + appWidgetId)
                .apply();
    }
}
