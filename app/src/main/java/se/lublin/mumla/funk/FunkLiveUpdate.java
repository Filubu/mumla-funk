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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Objects;

import se.lublin.mumla.R;

/**
 * Live Update (Android 16+): the connection notification asks to be promoted, so while connected
 * and while the app is not on screen, the status bar shows a chip ("Live-Pille") for it. The chip
 * shows who is talking.
 * <p>
 * What the app controls: the chip's icon (the notification's small icon) and a short text
 * ({@link #EXTRA_SHORT_CRITICAL_TEXT}). The system picks the chip's colors. Android 17 adds
 * semantic styles: while someone talks the text is marked as "danger", which the system shows in
 * red. Tapping the chip shows the notification; tapping that opens the walkie-talkie popup.
 * <p>
 * The keys and the Android 17 API are used by name, since compileSdk is 36. Older Android versions
 * ignore all of it.
 */
public final class FunkLiveUpdate {
    private static final String TAG = FunkLiveUpdate.class.getName();

    /** {@code Notification.EXTRA_REQUEST_PROMOTED_ONGOING} (Android 16 QPR1). */
    public static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    /** {@code Notification.EXTRA_SHORT_CRITICAL_TEXT} (Android 16): the chip's text. */
    public static final String EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText";

    /** {@code Notification.SEMANTIC_STYLE_DANGER} (Android 17), shown in red. */
    private static final int SEMANTIC_STYLE_DANGER = 4;
    /** {@code Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS} (Android 16). */
    private static final String ACTION_PROMOTION_SETTINGS =
            "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS";
    /** {@code Settings.ACTION_APP_NOTIFICATION_SETTINGS}, fallback. */
    private static final String ACTION_NOTIFICATION_SETTINGS =
            "android.settings.APP_NOTIFICATION_SETTINGS";
    /** {@code Settings.EXTRA_APP_PACKAGE}. */
    private static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";

    private FunkLiveUpdate() {
    }

    /** What the notification and its chip show about talking. */
    public static final class Status {
        public static final Status QUIET = new Status(null, null, false);

        /** Chip text ("Max", "Max +1", "Du"), null: icon only. */
        @Nullable
        public final String chipText;
        /** Notification text ("Max spricht"), null: the connection status. */
        @Nullable
        public final String text;
        /** Someone (we or others) is talking: talking icon, red text on Android 17. */
        public final boolean talking;

        Status(@Nullable String chipText, @Nullable String text, boolean talking) {
            this.chipText = chipText;
            this.text = text;
            this.talking = talking;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Status)) return false;
            Status other = (Status) o;
            return talking == other.talking && Objects.equals(chipText, other.chipText)
                    && Objects.equals(text, other.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chipText, text, talking);
        }
    }

    /** Who talks, for the notification. We come first: then we are on air. */
    @NonNull
    public static Status statusOf(@NonNull Context context, @NonNull FunkState state) {
        if (state.connection != FunkState.Connection.CONNECTED) {
            return Status.QUIET;
        }
        if (state.selfTalking) {
            return new Status(context.getString(R.string.funk_chip_self),
                    context.getString(R.string.funk_now_self), true);
        }
        List<FunkState.Member> talking = state.talkingOthers();
        if (talking.isEmpty()) {
            return Status.QUIET;
        }
        String first = talking.get(0).name;
        if (talking.size() == 1) {
            return new Status(first, context.getString(R.string.funk_x_talking, first), true);
        }
        int more = talking.size() - 1;
        return new Status(context.getString(R.string.funk_chip_several, first, more),
                context.getString(R.string.funk_several_talking, first, more), true);
    }

    /** Live Updates exist on this Android version. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
    }

    /** The user allows Live Updates for the app (Android 16+). */
    public static boolean isAllowed(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        try {
            return manager != null && manager.canPostPromotedNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot check Live Updates", e);
            return false;
        }
    }

    /** Opens the system setting for the app's Live Updates. @return false if impossible. */
    public static boolean openSettings(@NonNull Activity activity) {
        Intent promotion = new Intent(ACTION_PROMOTION_SETTINGS)
                .putExtra(EXTRA_APP_PACKAGE, activity.getPackageName());
        Intent notifications = new Intent(ACTION_NOTIFICATION_SETTINGS)
                .putExtra(EXTRA_APP_PACKAGE, activity.getPackageName());
        for (Intent intent : new Intent[]{promotion, notifications}) {
            try {
                activity.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next one.
            }
        }
        return false;
    }

    /**
     * Android 17: marks the chip text as "danger", so the system shows it in red. Uses the public
     * API {@code Notification.Builder#setCompactContent} by reflection (not in compileSdk 36).
     *
     * @return the notification with the style, or the given one if not available.
     */
    @SuppressLint("PrivateApi") // Public API of Android 17, just newer than compileSdk 36.
    @NonNull
    public static Notification withTalkingStyle(@NonNull Context context,
                                                @NonNull Notification notification) {
        if (Build.VERSION.SDK_INT < 37) { // Android 17
            return notification;
        }
        try {
            Class<?> iconClass = Class.forName("android.app.Notification$CompactIcon");
            Class<?> textClass = Class.forName("android.app.Notification$CompactText");
            Class<?> contentClass = Class.forName("android.app.Notification$CompactContent");
            Class<?> basicClass = Class.forName("android.app.Notification$BasicCompactContent");
            Object icon = iconClass.getMethod("useSmallIcon").invoke(null);
            Object text = textClass.getMethod("useShortCriticalText").invoke(null);
            Object content = basicClass.getConstructor(iconClass, textClass).newInstance(icon, text);
            basicClass.getMethod("setSemanticStyle", int.class).invoke(content, SEMANTIC_STYLE_DANGER);
            Notification.Builder builder = Notification.Builder.recoverBuilder(context, notification);
            Notification.Builder.class.getMethod("setCompactContent", contentClass)
                    .invoke(builder, content);
            return builder.build();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.d(TAG, "no semantic style for the chip: " + e);
            return notification;
        }
    }
}
