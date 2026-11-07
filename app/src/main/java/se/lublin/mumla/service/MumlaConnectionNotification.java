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

package se.lublin.mumla.service;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Objects;

import se.lublin.mumla.R;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.funk.FunkLiveUpdate;
import se.lublin.mumla.funk.PttActivity;

/**
 * Wrapper to create Mumla notifications.
 * Created by andrew on 08/08/14.
 */
public class MumlaConnectionNotification {
    private static final String TAG = "MumlaConnectionNotif";
    private static final int NOTIFICATION_ID = 1;
    private static final String BROADCAST_MUTE = "b_mute";
    private static final String BROADCAST_DEAFEN = "b_deafen";
    private static final String BROADCAST_OVERLAY = "b_overlay";

    private Service mService;
    private OnActionListener mListener;
    private String mCustomContentText;
    private boolean mActionsShown;
    /** The service is in the foreground with this notification. */
    private boolean mForeground;
    private boolean mReceiverRegistered;

    /*
     * Mumla Funk: Live Update (status bar chip, see FunkLiveUpdate). The server name is the title
     * (a Live Update needs one), the chip shows who is talking.
     */
    @Nullable
    private String mTitle;
    @NonNull
    private FunkLiveUpdate.Status mLiveStatus = FunkLiveUpdate.Status.QUIET;
    /** Saved walkie-talkie server: a tap opens the popup for it. -1: a tap opens the app. */
    private long mPopupServerId = -1;

    private BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_MUTE.equals(intent.getAction())) {
                mListener.onMuteToggled();
            } else if (BROADCAST_DEAFEN.equals(intent.getAction())) {
                mListener.onDeafenToggled();
            } else if (BROADCAST_OVERLAY.equals(intent.getAction())) {
                mListener.onOverlayToggled();
            }
        }
    };

    /**
     * Creates a foreground Mumla notification for the given service.
     * @param service The service to register a foreground notification for.
     * @param listener An listener for notification actions.
     * @return A new MumlaNotification instance.
     */
    public static MumlaConnectionNotification create(Service service, String contentText,
                                                     OnActionListener listener) {
        return new MumlaConnectionNotification(service, contentText, listener);
    }

    private MumlaConnectionNotification(Service service, String contentText,
                                        OnActionListener listener) {
        mService = service;
        mListener = listener;
        mCustomContentText = contentText;
        mActionsShown = false;
    }

    public void setCustomContentText(String text) {
        mCustomContentText = text;
    }

    public void setActionsShown(boolean actionsShown) {
        mActionsShown = actionsShown;
    }

    /**
     * Mumla Funk: title and talk status for the Live Update. Call {@link #show()} afterwards if
     * this returns true.
     *
     * @param popupServerId saved walkie-talkie server whose popup a tap opens, or -1 for the app
     * @return true if anything changed
     */
    public boolean setLive(@Nullable String title, @NonNull FunkLiveUpdate.Status status,
                           long popupServerId) {
        if (Objects.equals(mTitle, title) && mLiveStatus.equals(status)
                && mPopupServerId == popupServerId) {
            return false;
        }
        mTitle = title;
        mLiveStatus = status;
        mPopupServerId = popupServerId;
        return true;
    }

    /**
     * Shows (or updates) the notification and registers the notification action button receiver.
     */
    public void show() {
        createNotification();

        if (mReceiverRegistered) {
            return; // Registering again would add the filter twice: every action would run twice.
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_DEAFEN);
        filter.addAction(BROADCAST_MUTE);
        filter.addAction(BROADCAST_OVERLAY);
        try {
            // Mumla Funk: not exported on every Android version (before 13, a plain
            // registerReceiver() let any app send these actions).
            ContextCompat.registerReceiver(mService, mNotificationReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        } catch (IllegalArgumentException e) {
            // Thrown if receiver is already registered.
            e.printStackTrace();
        }
    }

    /**
     * Hides the notification and unregisters the action receiver.
     */
    public void hide() {
        try {
            mService.unregisterReceiver(mNotificationReceiver);
        } catch (IllegalArgumentException e) {
            // Thrown if receiver is not registered.
            e.printStackTrace();
        }
        mReceiverRegistered = false;
        mService.stopForeground(true);
        mForeground = false;
        NotificationManager manager =
                (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID); // In case it was only a normal notification.
        }
    }

    /**
     * Called to update/create the service's foreground Mumla notification.
     */
    private Notification createNotification() {
        String channelId = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = "connected_channel";
            String channelName = mService.getString(R.string.connected);
            NotificationChannel chan = new NotificationChannel(channelId, channelName,
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = mService.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(chan);
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mService, channelId);

        // app name is always displayed in notification on >= O
        if (mTitle != null) {
            builder.setContentTitle(mTitle); // Mumla Funk: the server (a Live Update needs a title)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            builder.setContentTitle(mService.getString(R.string.app_name));
        }
        builder.setContentText(mLiveStatus.text != null ? mLiveStatus.text : mCustomContentText);
        // The small icon is also the icon of the status bar chip.
        builder.setSmallIcon(mLiveStatus.talking ? R.drawable.ic_funk_tower : R.drawable.ic_stat_notify);
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setShowWhen(false);
        builder.setOngoing(true);
        // Updated on every talk change: never make a sound or vibrate again.
        builder.setOnlyAlertOnce(true);

        // Mumla Funk: Live Update (Android 16+, ignored before). Not colorized, no custom views.
        Bundle live = new Bundle();
        live.putBoolean(FunkLiveUpdate.EXTRA_REQUEST_PROMOTED_ONGOING, true);
        if (mLiveStatus.chipText != null) {
            live.putString(FunkLiveUpdate.EXTRA_SHORT_CRITICAL_TEXT, mLiveStatus.chipText);
        }
        builder.addExtras(live);

        if (mActionsShown) {
            // Add notification triggers
            Intent muteIntent = new Intent(BROADCAST_MUTE);
            muteIntent.setPackage(mService.getPackageName());
            Intent deafenIntent = new Intent(BROADCAST_DEAFEN);
            deafenIntent.setPackage(mService.getPackageName());
            Intent overlayIntent = new Intent(BROADCAST_OVERLAY);
            overlayIntent.setPackage(mService.getPackageName());

            builder.addAction(R.drawable.ic_action_microphone,
                    mService.getString(R.string.mute), PendingIntent.getBroadcast(mService, 1,
                            muteIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
            builder.addAction(R.drawable.ic_action_audio,
                    mService.getString(R.string.deafen), PendingIntent.getBroadcast(mService, 1,
                            deafenIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
            builder.addAction(R.drawable.ic_action_channels,
                    mService.getString(R.string.overlay), PendingIntent.getBroadcast(mService, 2,
                            overlayIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
        }

        PendingIntent pendingIntent;
        if (mPopupServerId >= 0) {
            // Mumla Funk: walkie-talkie server, the tap (also after tapping the status bar chip)
            // opens the popup. It has a button for the full app.
            pendingIntent = PendingIntent.getActivity(mService, 3,
                    PttActivity.createIntent(mService, mPopupServerId, false),
                    PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
        } else {
            Intent channelListIntent = new Intent(mService, MumlaActivity.class);
            channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER);
            // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
            pendingIntent = PendingIntent.getActivity(mService, 0,
                    channelListIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        }
        builder.setContentIntent(pendingIntent);

        Notification notification = builder.build();
        if (mLiveStatus.talking) {
            notification = FunkLiveUpdate.withTalkingStyle(mService, notification); // Android 17: red
        }
        if (mForeground) {
            // Only update it. Calling startForeground() again re-checks whether the app may start
            // a microphone foreground service, which Android 14+ refuses while in the background
            // (e.g. on a status change or an automatic reconnect), and that crashed the app.
            NotificationManager manager =
                    (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
            return notification;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mService.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                mService.startForeground(NOTIFICATION_ID, notification);
            }
            mForeground = true;
        } catch (SecurityException | IllegalStateException e) {
            // Not allowed right now (in the background). Do not crash: show the status anyway; the
            // next update from the foreground tries again.
            Log.w(TAG, "could not start the foreground service", e);
            NotificationManager manager =
                    (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
        return notification;
    }

    public interface OnActionListener {
        void onMuteToggled();
        void onDeafenToggled();
        void onOverlayToggled();
    }
}
