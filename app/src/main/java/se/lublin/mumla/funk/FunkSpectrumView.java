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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import java.util.Arrays;

/**
 * Bars of the spectrum in the display of the handheld radio design, made of segments like an
 * LED meter. Unlit segments are faintly visible, lit ones in the display color. The bars rise
 * quickly and fall slowly, like an analyzer, and stop moving (and drawing frames) once quiet.
 * While I transmit and somebody else talks too, each band shows the louder of both.
 */
public final class FunkSpectrumView extends View {
    /** Without new levels for this long, a source counts as silent. */
    private static final long STALE_MS = 150;
    private static final float ATTACK = 0.65f;
    private static final float DECAY = 0.16f;
    /** Segment height, gap between segments and between bars, in dp. */
    private static final float SEGMENT = 4f;
    private static final float SEGMENT_GAP = 2f;
    private static final float BAR_GAP = 4f;

    private final int mBands = FunkSpectrum.BANDS;
    private final float[] mShown = new float[mBands];
    private final float[] mInput = new float[mBands];
    private final float[] mOutput = new float[mBands];
    private long mInputAt;
    private long mOutputAt;
    private final Paint mLit = new Paint();
    private final Paint mDim = new Paint();
    private final float mDp;
    private boolean mAnimating;
    private final Runnable mFrame = this::frame;

    public FunkSpectrumView(Context context) {
        this(context, null);
    }

    public FunkSpectrumView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = context.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mLit.setColor(0xFFFFB23E); // FunkHandset sets the display color
        mDim.setColor(ColorUtils.setAlphaComponent(0xFFFFB23E, 0x24));
    }

    /** The display color; unlit segments use it faintly. */
    public void setColor(@ColorInt int color) {
        mLit.setColor(color);
        mDim.setColor(ColorUtils.setAlphaComponent(color, 0x24));
        invalidate();
    }

    /** New levels (0 to 1 per band) of the microphone ({@code input}) or of the others. */
    public void setLevels(@NonNull float[] levels, boolean input) {
        float[] target = input ? mInput : mOutput;
        System.arraycopy(levels, 0, target, 0, Math.min(levels.length, mBands));
        if (input) {
            mInputAt = SystemClock.uptimeMillis();
        } else {
            mOutputAt = SystemClock.uptimeMillis();
        }
        if (!mAnimating) {
            mAnimating = true;
            postOnAnimation(mFrame);
        }
    }

    /** Back to silence at once, e.g. when the display is switched off. */
    public void clear() {
        removeCallbacks(mFrame);
        mAnimating = false;
        mInputAt = 0;
        mOutputAt = 0;
        Arrays.fill(mShown, 0f);
        invalidate();
    }

    private void frame() {
        long now = SystemClock.uptimeMillis();
        boolean inputLive = now - mInputAt <= STALE_MS;
        boolean outputLive = now - mOutputAt <= STALE_MS;
        boolean smooth = FunkMotion.enabled();
        boolean moving = false;
        for (int i = 0; i < mBands; i++) {
            float target = Math.max(inputLive ? mInput[i] : 0f, outputLive ? mOutput[i] : 0f);
            float shown = mShown[i];
            float next = !smooth ? target
                    : target > shown ? shown + (target - shown) * ATTACK : shown + (target - shown) * DECAY;
            if (target == 0f && next < 0.015f) {
                next = 0f;
            }
            mShown[i] = next;
            if (next > 0f || target > 0f) {
                moving = true;
            }
        }
        invalidate();
        if (moving || inputLive || outputLive) {
            if (smooth) {
                postOnAnimation(mFrame);
            } else {
                // Animations off: no frame loop, just look again when the levels go stale.
                postDelayed(mFrame, STALE_MS);
            }
        } else {
            mAnimating = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mFrame);
        mAnimating = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float width = getWidth() - left - getPaddingRight();
        float height = getHeight() - top - getPaddingBottom();
        float gap = BAR_GAP * mDp;
        float barWidth = (width - gap * (mBands - 1)) / mBands;
        float step = (SEGMENT + SEGMENT_GAP) * mDp;
        int segments = (int) ((height + SEGMENT_GAP * mDp) / step);
        if (barWidth <= 0f || segments <= 0) {
            return;
        }
        float bottom = top + height;
        for (int b = 0; b < mBands; b++) {
            float x = left + b * (barWidth + gap);
            int lit = Math.round(mShown[b] * segments);
            for (int s = 0; s < segments; s++) {
                float segBottom = bottom - s * step;
                canvas.drawRect(x, segBottom - SEGMENT * mDp, x + barWidth, segBottom,
                        s < lit ? mLit : mDim);
            }
        }
    }
}
