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
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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

    /*
     * Springs for the popup card, in the manner of iOS sheets: no fixed duration, the motion
     * follows physics, can be interrupted at any time and continues with its current speed.
     * Response (time of one swing) is about 2π / √stiffness: 420 → 0.31 s. A damping ratio
     * slightly below 1 lets the card settle with a barely visible give, 1 has no overshoot.
     */
    /** Card rising into place: quick, with a soft settle. */
    public static final float SHEET_IN_STIFFNESS = 420f;
    public static final float SHEET_IN_DAMPING = 0.82f;
    /** Card growing from slightly smaller: no overshoot, or the edges would wobble. */
    public static final float SHEET_SCALE_DAMPING = 1f;
    /** Card going away: firmer and without overshoot. */
    public static final float SHEET_OUT_STIFFNESS = 800f;
    public static final float SHEET_OUT_DAMPING = 1f;
    /** Fades: fast and without overshoot (about 0.15 s in, 0.2 s out). */
    public static final float FADE_IN_STIFFNESS = 1500f;
    public static final float FADE_OUT_STIFFNESS = 1200f;

    private FunkMotion() {
    }

    /** A spring on one property of a view, starting from the property's current value. */
    @NonNull
    public static SpringAnimation spring(@NonNull View view,
                                         @NonNull DynamicAnimation.ViewProperty property,
                                         float stiffness, float dampingRatio) {
        SpringAnimation animation = new SpringAnimation(view, property);
        animation.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(dampingRatio));
        return animation;
    }

    /** Retargets a (possibly running) spring: it keeps its current speed, so the motion stays smooth. */
    public static void springTo(@NonNull SpringAnimation animation, float finalPosition,
                                float stiffness, float dampingRatio) {
        SpringForce force = animation.getSpring();
        if (force == null) {
            force = new SpringForce();
            animation.setSpring(force);
        }
        force.setStiffness(stiffness).setDampingRatio(dampingRatio);
        animation.animateToFinalPosition(finalPosition);
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
