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

package se.lublin.mumla.funk.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.List;

import se.lublin.mumla.R;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.funk.FunkPrefs;
import se.lublin.mumla.funk.FunkState;
import se.lublin.mumla.funk.PttActivity;

/**
 * Renders both home screen widgets from the current {@link FunkState}.
 */
public final class FunkWidgets {
    private static final int ROW_HEIGHT_DP = 24;
    /** Header + action button + paddings of the large widget, in dp. */
    private static final int LARGE_CHROME_DP = 14 + 36 + 10 + 10 + 48 + 12;

    private FunkWidgets() {
    }

    /** Re-renders all widgets. Cheap enough to call on every state change (main thread). */
    public static void updateAll(@NonNull Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        FunkState state = FunkState.current();
        for (int id : manager.getAppWidgetIds(new ComponentName(context, FunkWidgetSmall.class))) {
            updateSmall(context, manager, id, state);
        }
        for (int id : manager.getAppWidgetIds(new ComponentName(context, FunkWidgetLarge.class))) {
            updateLarge(context, manager, id, state);
        }
    }

    /** Keeps the server name shown by widgets in sync after the server was edited. */
    public static void onServerRenamed(@NonNull Context context, long serverId, @NonNull String name) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        FunkPrefs prefs = FunkPrefs.get(context);
        boolean changed = false;
        for (Class<?> provider : new Class<?>[]{FunkWidgetSmall.class, FunkWidgetLarge.class}) {
            for (int id : manager.getAppWidgetIds(new ComponentName(context, provider))) {
                if (prefs.isWidgetConfigured(id) && prefs.getWidgetServerId(id) == serverId) {
                    prefs.setWidgetServer(id, serverId, name);
                    changed = true;
                }
            }
        }
        if (changed) {
            updateAll(context);
        }
    }

    static void update(@NonNull Context context, @NonNull AppWidgetManager manager, int appWidgetId,
                       boolean large) {
        if (large) {
            updateLarge(context, manager, appWidgetId, FunkState.current());
        } else {
            updateSmall(context, manager, appWidgetId, FunkState.current());
        }
    }

    private static PendingIntent popupIntent(Context context, int appWidgetId, long serverId,
                                             boolean autoConnect) {
        Intent intent = PttActivity.createIntent(context, serverId, autoConnect);
        // Distinct data per widget and action, so PendingIntents do not overwrite each other.
        intent.setData(android.net.Uri.parse("mumlafunk://widget/" + appWidgetId + "/" + autoConnect));
        return PendingIntent.getActivity(context, appWidgetId * 2 + (autoConnect ? 1 : 0), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent configureIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, FunkWidgetConfigActivity.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.setData(android.net.Uri.parse("mumlafunk://widget-config/" + appWidgetId));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent appIntent(Context context) {
        Intent intent = new Intent(context, MumlaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, -1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void updateSmall(Context context, AppWidgetManager manager, int id,
                                    FunkState state) {
        FunkPrefs prefs = FunkPrefs.get(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_funk_small);
        long serverId = prefs.getWidgetServerId(id);
        String name = prefs.getWidgetServerName(id);

        if (!prefs.isWidgetConfigured(id)) {
            views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.bg_funk_circle_idle);
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_funk_tower_off);
            views.setOnClickPendingIntent(R.id.widget_root, configureIntent(context, id));
        } else {
            int bg;
            int icon;
            if (state.isConnectedTo(serverId)) {
                bg = state.selfTalking ? R.drawable.bg_funk_circle_talk : R.drawable.bg_funk_circle_accent;
                icon = state.selfMuted ? R.drawable.ic_funk_mic_off : R.drawable.ic_funk_walkie;
            } else if (state.isOn(serverId)) {
                bg = R.drawable.bg_funk_circle_idle;
                icon = R.drawable.ic_funk_tower;
            } else {
                bg = R.drawable.bg_funk_circle_idle;
                icon = R.drawable.ic_funk_tower_off;
            }
            views.setInt(R.id.widget_button, "setBackgroundResource", bg);
            views.setImageViewResource(R.id.widget_icon, icon);
            views.setOnClickPendingIntent(R.id.widget_root,
                    popupIntent(context, id, serverId, false));
        }
        views.setContentDescription(R.id.widget_root,
                context.getString(R.string.funk_widget_small_label) + (name != null ? ": " + name : ""));
        manager.updateAppWidget(id, views);
    }

    private static void updateLarge(Context context, AppWidgetManager manager, int id,
                                    FunkState state) {
        FunkPrefs prefs = FunkPrefs.get(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_funk_large);
        views.setOnClickPendingIntent(R.id.widget_header, appIntent(context));

        if (!prefs.isWidgetConfigured(id)) {
            views.setTextViewText(R.id.widget_server, context.getString(R.string.funk_choose_server));
            views.setTextViewText(R.id.widget_sub, "");
            showOffline(context, views, context.getString(R.string.funk_widget_config_title));
            setAction(context, views, R.drawable.bg_funk_pill_idle, R.drawable.ic_funk_tower,
                    context.getString(R.string.funk_choose_server));
            views.setOnClickPendingIntent(R.id.widget_action, configureIntent(context, id));
            views.setOnClickPendingIntent(R.id.widget_offline, configureIntent(context, id));
            manager.updateAppWidget(id, views);
            return;
        }

        long serverId = prefs.getWidgetServerId(id);
        String name = prefs.getWidgetServerName(id);
        boolean connected = state.isConnectedTo(serverId);
        boolean connecting = !connected && state.isOn(serverId);
        if (connected && state.serverName != null) {
            name = state.serverName;
        }
        views.setTextViewText(R.id.widget_server, name != null ? name : "");
        views.setImageViewResource(R.id.widget_status,
                connected ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
        views.setInt(R.id.widget_status, "setColorFilter", ContextCompat.getColor(context,
                connected ? R.color.funk_live : R.color.funk_on_surface_muted));

        if (connected) {
            int online = state.members.size();
            views.setTextViewText(R.id.widget_sub, state.channelName != null
                    ? context.getString(R.string.funk_channel_online, state.channelName, online)
                    : context.getString(R.string.funk_online_count, online));
            views.setViewVisibility(R.id.widget_users, View.VISIBLE);
            views.setViewVisibility(R.id.widget_offline, View.GONE);
            fillUsers(context, manager, id, views, state);
            if (state.selfTalking) {
                setAction(context, views, R.drawable.bg_funk_pill_talk, R.drawable.ic_funk_walkie,
                        context.getString(R.string.funk_transmitting));
            } else {
                setAction(context, views, R.drawable.bg_funk_pill_accent,
                        state.selfMuted ? R.drawable.ic_funk_mic_off : R.drawable.ic_funk_walkie,
                        context.getString(R.string.funk_widget_talk));
            }
            views.setOnClickPendingIntent(R.id.widget_action, popupIntent(context, id, serverId, false));
        } else {
            String sub;
            if (connecting) {
                sub = context.getString(R.string.funk_connecting);
            } else if (state.connection != FunkState.Connection.DISCONNECTED && state.serverName != null) {
                sub = context.getString(R.string.funk_widget_other_server, state.serverName);
            } else {
                sub = context.getString(R.string.funk_not_connected);
            }
            views.setTextViewText(R.id.widget_sub, sub);
            showOffline(context, views, context.getString(
                    connecting ? R.string.funk_connecting : R.string.funk_not_connected));
            views.setImageViewResource(R.id.widget_offline_icon,
                    connecting ? R.drawable.ic_funk_tower : R.drawable.ic_funk_tower_off);
            setAction(context, views, R.drawable.bg_funk_pill_idle, R.drawable.ic_funk_tower,
                    context.getString(connecting ? R.string.funk_connecting : R.string.funk_widget_connect));
            PendingIntent connect = popupIntent(context, id, serverId, !connecting);
            views.setOnClickPendingIntent(R.id.widget_action, connect);
            views.setOnClickPendingIntent(R.id.widget_offline, connect);
        }
        manager.updateAppWidget(id, views);
    }

    private static void showOffline(Context context, RemoteViews views, String text) {
        views.setViewVisibility(R.id.widget_users, View.GONE);
        views.setViewVisibility(R.id.widget_offline, View.VISIBLE);
        views.setTextViewText(R.id.widget_offline_text, text);
    }

    private static void setAction(Context context, RemoteViews views, int background, int icon,
                                  String text) {
        views.setInt(R.id.widget_action, "setBackgroundResource", background);
        views.setImageViewResource(R.id.widget_action_icon, icon);
        views.setTextViewText(R.id.widget_action_text, text);
    }

    private static void fillUsers(Context context, AppWidgetManager manager, int id,
                                  RemoteViews views, FunkState state) {
        views.removeAllViews(R.id.widget_users);
        List<FunkState.Member> members = state.members;
        if (members.isEmpty()) {
            return;
        }
        int maxRows = maxRows(manager, id);
        boolean overflow = members.size() > maxRows;
        int shown = overflow ? Math.max(0, maxRows - 1) : members.size();
        for (int i = 0; i < shown; i++) {
            FunkState.Member m = members.get(i);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_funk_user_row);
            row.setImageViewResource(R.id.row_dot, m.talking ? R.drawable.funk_dot_talk
                    : m.sameChannel ? R.drawable.funk_dot_live : R.drawable.funk_dot_idle);
            row.setTextViewText(R.id.row_name, m.self ? context.getString(R.string.funk_you, m.name) : m.name);
            row.setTextViewText(R.id.row_channel, m.sameChannel || m.channel == null ? "" : m.channel);
            row.setTextColor(R.id.row_name, ContextCompat.getColor(context,
                    m.muted ? R.color.funk_on_surface_muted : R.color.funk_on_surface));
            views.addView(R.id.widget_users, row);
        }
        if (overflow) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_funk_user_row);
            row.setViewVisibility(R.id.row_dot, View.INVISIBLE);
            row.setTextViewText(R.id.row_name, context.getString(R.string.funk_more_users,
                    members.size() - shown));
            row.setTextColor(R.id.row_name, ContextCompat.getColor(context, R.color.funk_on_surface_muted));
            row.setTextViewText(R.id.row_channel, "");
            views.addView(R.id.widget_users, row);
        }
    }

    /** How many user rows fit, based on the widget's current height. */
    private static int maxRows(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        // Portrait uses minWidth x maxHeight, landscape maxWidth x minHeight; be conservative.
        int heightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
        if (heightDp <= 0) {
            heightDp = 180;
        }
        return Math.max(1, (heightDp - LARGE_CHROME_DP) / ROW_HEIGHT_DP);
    }
}
