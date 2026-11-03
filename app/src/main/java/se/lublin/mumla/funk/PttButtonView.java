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
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import se.lublin.mumla.R;

/**
 * The big round walkie-talkie button. Hold to talk when ready; tap otherwise (e.g. to connect).
 */
public class PttButtonView extends View {

    public enum Mode {OFFLINE, CONNECTING, READY, TALKING, MUTED}

    public interface Listener {
        /** Finger went down while the button is ready to talk. */
        void onPttDown();

        /** Finger lifted or the touch was cancelled after {@link #onPttDown()}. */
        void onPttUp();

        /** Tap while not ready to talk (offline, muted, ...). */
        void onPttTap(Mode mode);
    }

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcRect = new RectF();
    private final float mDp;

    private Mode mMode = Mode.OFFLINE;
    private Drawable mIcon;
    private Listener mListener;
    private boolean mHolding;
    private float mPressScale = 1f;
    private float mPulse;
    private float mSpin;
    private ValueAnimator mPulseAnimator;
    private ValueAnimator mSpinAnimator;
    private ValueAnimator mScaleAnimator;
    private int mGradientFor = -1;

    public PttButtonView(Context context) {
        this(context, null);
    }

    public PttButtonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(1.5f * mDp);
        mRimPaint.setColor(0x26FFFFFF);
        mPulsePaint.setStyle(Paint.Style.STROKE);
        mPulsePaint.setStrokeWidth(3f * mDp);
        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setStrokeWidth(4f * mDp);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);
        mArcPaint.setColor(ContextCompat.getColor(context, R.color.funk_accent));
        setClickable(true);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
        setContentDescription(context.getString(R.string.funk_ptt_button_desc));
        applyMode();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public Mode getMode() {
        return mMode;
    }

    public void setMode(Mode mode) {
        if (mode == mMode) {
            return;
        }
        mMode = mode;
        if (mHolding && mode != Mode.READY && mode != Mode.TALKING) {
            // Lost the ability to talk while holding (disconnected, muted): end it cleanly.
            mHolding = false;
            animatePress(false);
            if (mListener != null) {
                mListener.onPttUp();
            }
        }
        applyMode();
    }

    /** Called by the owner when transmission was started by a hardware key. */
    public void hapticStart() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    public void hapticStop() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void applyMode() {
        int iconRes;
        switch (mMode) {
            case CONNECTING:
                iconRes = R.drawable.ic_funk_tower;
                break;
            case READY:
            case TALKING:
                iconRes = R.drawable.ic_funk_walkie;
                break;
            case MUTED:
                iconRes = R.drawable.ic_funk_mic_off;
                break;
            case OFFLINE:
            default:
                iconRes = R.drawable.ic_funk_tower_off;
        }
        Drawable icon = ContextCompat.getDrawable(getContext(), iconRes);
        if (icon != null) {
            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon, 0xFFFFFFFF);
        }
        mIcon = icon;
        mGradientFor = -1;

        if (mMode == Mode.TALKING) {
            startPulse();
        } else {
            stopPulse();
        }
        if (mMode == Mode.CONNECTING) {
            startSpin();
        } else {
            stopSpin();
        }
        invalidate();
    }

    private int[] colorsFor(Mode mode) {
        Context c = getContext();
        switch (mode) {
            case READY:
                return new int[]{ContextCompat.getColor(c, R.color.funk_accent),
                        ContextCompat.getColor(c, R.color.funk_accent_dark)};
            case TALKING:
                return new int[]{ContextCompat.getColor(c, R.color.funk_talk),
                        ContextCompat.getColor(c, R.color.funk_talk_dark)};
            default:
                return new int[]{ContextCompat.getColor(c, R.color.funk_idle),
                        ContextCompat.getColor(c, R.color.funk_idle_dark)};
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxRadius = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float baseRadius = maxRadius / 1.2f; // leave room for the pulse rings
        float radius = baseRadius * mPressScale;

        if (mMode == Mode.TALKING) {
            int talk = ContextCompat.getColor(getContext(), R.color.funk_talk);
            for (int i = 0; i < 2; i++) {
                float p = (mPulse + i * 0.5f) % 1f;
                mPulsePaint.setColor(talk);
                mPulsePaint.setAlpha((int) (150 * (1f - p)));
                canvas.drawCircle(cx, cy, radius + (maxRadius - baseRadius) * p, mPulsePaint);
            }
        }

        int key = (int) radius * 10 + mMode.ordinal();
        if (key != mGradientFor) {
            int[] colors = colorsFor(mMode);
            mFillPaint.setShader(new LinearGradient(cx, cy - radius, cx, cy + radius,
                    colors[0], colors[1], Shader.TileMode.CLAMP));
            mGradientFor = key;
        }
        canvas.drawCircle(cx, cy, radius, mFillPaint);
        canvas.drawCircle(cx, cy, radius - mRimPaint.getStrokeWidth() / 2f, mRimPaint);

        if (mMode == Mode.CONNECTING) {
            float r = baseRadius + 8f * mDp;
            mArcRect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(mArcRect, mSpin * 360f, 100f, false, mArcPaint);
        }

        if (mIcon != null) {
            int half = (int) (radius * 0.42f);
            mIcon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
            mIcon.setAlpha(mMode == Mode.OFFLINE || mMode == Mode.MUTED ? 200 : 255);
            mIcon.draw(canvas);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        boolean canTalk = mMode == Mode.READY || mMode == Mode.TALKING;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                animatePress(true);
                if (canTalk) {
                    mHolding = true;
                    hapticStart();
                    if (mListener != null) {
                        mListener.onPttDown();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                animatePress(false);
                if (mHolding) {
                    mHolding = false;
                    hapticStop();
                    if (mListener != null) {
                        mListener.onPttUp();
                    }
                } else if (isInside(event)) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                animatePress(false);
                if (mHolding) {
                    mHolding = false;
                    if (mListener != null) {
                        mListener.onPttUp();
                    }
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (mListener != null) {
            mListener.onPttTap(mMode);
        }
        return true;
    }

    private boolean isInside(MotionEvent e) {
        return e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
    }

    /** True while a finger holds the button to talk. */
    public boolean isHolding() {
        return mHolding;
    }

    private void animatePress(boolean pressed) {
        if (mScaleAnimator != null) {
            mScaleAnimator.cancel();
        }
        mScaleAnimator = ValueAnimator.ofFloat(mPressScale, pressed ? 0.95f : 1f);
        mScaleAnimator.setDuration(pressed ? 90 : 160);
        mScaleAnimator.addUpdateListener(a -> {
            mPressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        mScaleAnimator.start();
    }

    private void startPulse() {
        if (mPulseAnimator != null) {
            return;
        }
        mPulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        mPulseAnimator.setDuration(1400);
        mPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnimator.setInterpolator(new LinearInterpolator());
        mPulseAnimator.addUpdateListener(a -> {
            mPulse = (float) a.getAnimatedValue();
            invalidate();
        });
        mPulseAnimator.start();
    }

    private void stopPulse() {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
        }
    }

    private void startSpin() {
        if (mSpinAnimator != null) {
            return;
        }
        mSpinAnimator = ValueAnimator.ofFloat(0f, 1f);
        mSpinAnimator.setDuration(1100);
        mSpinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mSpinAnimator.setInterpolator(new LinearInterpolator());
        mSpinAnimator.addUpdateListener(a -> {
            mSpin = (float) a.getAnimatedValue();
            invalidate();
        });
        mSpinAnimator.start();
    }

    private void stopSpin() {
        if (mSpinAnimator != null) {
            mSpinAnimator.cancel();
            mSpinAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        stopSpin();
        if (mScaleAnimator != null) {
            mScaleAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyMode();
    }
}
