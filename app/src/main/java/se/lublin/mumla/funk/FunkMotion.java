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

import android.animation.ValueAnimator;
import android.os.Build;
import android.text.TextUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Motion of the Mumla Funk design: a few short, calm transitions that explain a change (a new
 * screen, someone starts talking, the button is pressed). Same curves as res/interpolator and
 * the durations of res/values/integers_motion.xml. All animations follow the system animation
 * scale; looping ones are not started when animations are turned off.
 */
public final class FunkMotion {
    /** Quick start, long gentle settle: most state changes. */
    public static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Things coming in. */
    public static final Interpolator DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Things going away. */
    public static final Interpolator ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    public static final long PRESS_MS = 110;
    public static final long RELEASE_MS = 320;
    public static final long CHANGE_MS = 220;
    public static final long ENTER_MS = 300;
    public static final long EXIT_MS = 150;

    private FunkMotion() {
    }

    /** False when the user turned animations off (developer options or accessibility). */
    public static boolean enabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    /**
     * Shows a new text. If it differs from the current one, it fades in and settles up by a few
     * dp, like a line on a radio display; the same text is left alone, so frequent updates with
     * unchanged content do not flicker.
     */
    public static void swapText(@NonNull TextView view, @Nullable CharSequence text, @ColorInt int color) {
        view.setTextColor(color);
        swapText(view, text);
    }

    /** @see #swapText(TextView, CharSequence, int) */
    public static void swapText(@NonNull TextView view, @Nullable CharSequence text) {
        CharSequence current = view.getText();
        if (TextUtils.equals(current, text)) {
            return;
        }
        view.setText(text);
        if (!enabled() || !view.isLaidOut() || TextUtils.isEmpty(text)) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        float rise = 4f * view.getResources().getDisplayMetrics().density;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(rise);
        view.animate().alpha(1f).translationY(0f).setDuration(CHANGE_MS)
                .setInterpolator(DECELERATE).start();
    }
}
