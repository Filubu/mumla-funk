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

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.Locale;

import se.lublin.mumla.R;

/**
 * The big round walkie-talkie button. Hold to talk when ready; tap otherwise (e.g. to connect).
 *
 * <p>Motion (see {@link FunkMotion}): the button gives way under the finger and springs back
 * when let go, colors and icon cross-fade between states, two soft rings spread out while
 * transmitting and an arc circles while connecting. With animations turned off the rings and
 * the arc are drawn still.
 *
 * <p>In the handheld radio design ({@link Look#KEY}) it is a wide grooved key with its label
 * on it instead: pressed, it moves down onto its lower edge; its rim and glow turn green while
 * transmitting and red while somebody else talks ({@link #setReceiving(boolean)}).
 */
public class PttButtonView extends View {

    public enum Mode {OFFLINE, CONNECTING, READY, TALKING, MUTED}

    /** Round button (default design) or a wide key (handheld radio design). */
    public enum Look {ROUND, KEY}

    public interface Listener {
        /** Finger went down while the button is ready to talk. */
        void onPttDown();

        /** Finger lifted or the touch was cancelled after {@link #onPttDown()}. */
        void onPttUp();

        /** Tap while not ready to talk (offline, muted, ...). */
        void onPttTap(Mode mode);
    }

    private static final float PRESSED_SCALE = 0.94f;
    private static final long PULSE_MS = 1600;
    private static final long SPIN_MS = 1100;

    /* The key of the handheld radio design, in dp. */
    private static final float KEY_RADIUS = 26f;
    private static final float KEY_RIM = 3f;
    /** Room around the key for its glow. */
    private static final float KEY_GLOW = 8f;
    /** Visible lower edge below the face; pressed, the face moves down onto it. */
    private static final float KEY_EDGE = 7f;
    private static final float KEY_TRAVEL = 5f;
    /** Width of one groove; the grooves run at the angle of the reference design. */
    private static final float KEY_GROOVE = 7f;
    private static final float KEY_LABEL_SP = 32f;
    private static final float KEY_SUB_SP = 11.5f;
    /** Height the key look needs beyond its face (glow above and below, lower edge), in dp. */
    public static final float KEY_EXTRA_HEIGHT_DP = 2 * KEY_GLOW + KEY_EDGE;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcRect = new RectF();
    private final ArgbEvaluator mArgb = new ArgbEvaluator();
    private final float mDp;

    private Mode mMode = Mode.OFFLINE;
    private Drawable mIcon;
    private int mIconRes;
    /** The previous icon while it fades out, null otherwise. */
    @Nullable
    private Drawable mOldIcon;
    private float mIconFade = 1f;
    private Listener mListener;
    private boolean mHolding;
    private float mPressScale = 1f;
    private float mPulse;
    private float mSpin;
    private ValueAnimator mPulseAnimator;
    private ValueAnimator mSpinAnimator;
    private ValueAnimator mScaleAnimator;
    private ValueAnimator mChangeAnimator;

    /** Gradient colors drawn now, and the ones a state change fades from and to. */
    private int mTop;
    private int mBottom;
    private int mFromTop;
    private int mFromBottom;
    private int mToTop;
    private int mToBottom;
    private int mShaderTop;
    private int mShaderBottom;
    private float mShaderRadius = -1f;

    private Look mLook = Look.ROUND;
    private boolean mReceiving;
    private String mKeyLabel = "";
    private String mKeySub = "";
    private final Paint mKeyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyShadePaint = new Paint();
    private final Paint mKeyRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mKeyLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mKeySubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mKeyRect = new RectF();
    private final Path mKeyPath = new Path();
    private final Shader mKeyGrooves;
    /** Inner shadows of the face; they depend on its height (see {@link #onSizeChanged}). */
    @Nullable
    private Shader mKeyBottomShade;
    @Nullable
    private Shader mKeyTopShade;

    public PttButtonView(Context context) {
        this(context, null);
    }

    public PttButtonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = context.getResources().getDisplayMetrics().density;
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(1.5f * mDp);
        mRimPaint.setColor(0x26FFFFFF);
        mPulsePaint.setStyle(Paint.Style.STROKE);
        mPulsePaint.setStrokeWidth(2.5f * mDp);
        mPulsePaint.setColor(ContextCompat.getColor(context, R.color.funk_talk));
        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setStrokeWidth(4f * mDp);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);
        mArcPaint.setColor(ContextCompat.getColor(context, R.color.funk_accent));
        mKeyRimPaint.setStyle(Paint.Style.STROKE);
        mKeyRimPaint.setStrokeWidth(KEY_RIM * mDp);
        mKeyGlowPaint.setStyle(Paint.Style.STROKE);
        mKeyGlowPaint.setStrokeWidth(3f * mDp);
        mKeyLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mKeyLabelPaint.setTextAlign(Paint.Align.CENTER);
        mKeyLabelPaint.setLetterSpacing(0.3f);
        mKeySubPaint.setTypeface(Typeface.MONOSPACE);
        mKeySubPaint.setTextAlign(Paint.Align.CENTER);
        mKeySubPaint.setLetterSpacing(0.1f);
        mKeySubPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, KEY_SUB_SP,
                context.getResources().getDisplayMetrics()));
        mKeySubPaint.setColor(ContextCompat.getColor(context, R.color.handset_muted));
        // Grooves at 115° (pointing right and slightly down), one light and one dark band each.
        double angle = Math.toRadians(115);
        float period = 2 * KEY_GROOVE * mDp;
        int grooveA = ContextCompat.getColor(context, R.color.handset_ptt_stripe_a);
        int grooveB = ContextCompat.getColor(context, R.color.handset_ptt_stripe_b);
        mKeyGrooves = new LinearGradient(0, 0, (float) (Math.sin(angle) * period),
                (float) (-Math.cos(angle) * period), new int[]{grooveA, grooveA, grooveB, grooveB},
                new float[]{0f, 0.5f, 0.5f, 1f}, Shader.TileMode.REPEAT);
        setClickable(true);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
        setContentDescription(context.getString(R.string.funk_ptt_button_desc));
        applyMode(false);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public Mode getMode() {
        return mMode;
    }

    /** Round button or the wide key of the handheld radio design. */
    public void setLook(Look look) {
        if (look == mLook) {
            return;
        }
        mLook = look;
        updateLoops();
        invalidate();
    }

    /**
     * Key look only: the big label on the key ("PTT", "SENDING", ...) and the small line under
     * it. Both are shown in capitals.
     */
    public void setKeyText(@Nullable String label, @Nullable String sub) {
        Locale locale = Locale.getDefault();
        String newLabel = label != null ? label.toUpperCase(locale) : "";
        String newSub = sub != null ? sub.toUpperCase(locale) : "";
        if (!newLabel.equals(mKeyLabel) || !newSub.equals(mKeySub)) {
            mKeyLabel = newLabel;
            mKeySub = newSub;
            invalidate();
        }
    }

    /** Key look only: somebody else is talking; the key gets a red rim and glow. */
    public void setReceiving(boolean receiving) {
        if (receiving != mReceiving) {
            mReceiving = receiving;
            invalidate();
        }
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
        applyMode(true);
    }

    /** Called by the owner when transmission was started by a hardware key. */
    public void hapticStart() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    public void hapticStop() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void applyMode(boolean animate) {
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
        int[] colors = colorsFor(mMode);
        boolean sameIcon = mIcon != null && iconRes == mIconRes;
        mIconRes = iconRes;

        if (mChangeAnimator != null) {
            mChangeAnimator.cancel();
        }
        if (animate && FunkMotion.enabled() && isLaidOut()) {
            mFromTop = mTop;
            mFromBottom = mBottom;
            mToTop = colors[0];
            mToBottom = colors[1];
            mOldIcon = sameIcon ? null : mIcon;
            mIcon = sameIcon ? mIcon : icon;
            mIconFade = sameIcon ? 1f : 0f;
            mChangeAnimator = ValueAnimator.ofFloat(0f, 1f);
            mChangeAnimator.setDuration(FunkMotion.CHANGE_MS);
            mChangeAnimator.setInterpolator(FunkMotion.STANDARD);
            mChangeAnimator.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                mTop = (int) mArgb.evaluate(f, mFromTop, mToTop);
                mBottom = (int) mArgb.evaluate(f, mFromBottom, mToBottom);
                if (mOldIcon != null) {
                    mIconFade = f;
                }
                if (f >= 1f) {
                    mOldIcon = null;
                }
                invalidate();
            });
            mChangeAnimator.start();
        } else {
            mTop = mToTop = colors[0];
            mBottom = mToBottom = colors[1];
            mIcon = icon;
            mOldIcon = null;
            mIconFade = 1f;
        }
        updateLoops();
        invalidate();
    }

    /** Starts or stops the looping animations for the current mode. */
    private void updateLoops() {
        if (mMode == Mode.TALKING && mLook == Look.ROUND) {
            startPulse();
        } else {
            stopPulse();
        }
        if (mMode == Mode.CONNECTING && mLook == Look.ROUND) {
            startSpin();
        } else {
            stopSpin();
        }
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mKeyBottomShade = null;
        mKeyTopShade = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mLook == Look.KEY) {
            drawKey(canvas);
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxRadius = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float baseRadius = maxRadius / 1.2f; // leave room for the pulse rings
        float radius = baseRadius * mPressScale;

        if (mMode == Mode.TALKING) {
            if (mPulseAnimator != null) {
                // Two rings, half a period apart, spreading out and fading.
                for (int i = 0; i < 2; i++) {
                    float p = (mPulse + i * 0.5f) % 1f;
                    float spread = 1f - (1f - p) * (1f - p);
                    float fade = 1f - p;
                    mPulsePaint.setAlpha((int) (140 * fade * fade));
                    canvas.drawCircle(cx, cy, radius + (maxRadius - baseRadius) * spread, mPulsePaint);
                }
            } else {
                // Animations off: one still ring says "transmitting".
                mPulsePaint.setAlpha(110);
                canvas.drawCircle(cx, cy, radius + (maxRadius - baseRadius) * 0.45f, mPulsePaint);
            }
        }

        if (radius != mShaderRadius || mTop != mShaderTop || mBottom != mShaderBottom) {
            mFillPaint.setShader(new LinearGradient(cx, cy - radius, cx, cy + radius,
                    mTop, mBottom, Shader.TileMode.CLAMP));
            mShaderRadius = radius;
            mShaderTop = mTop;
            mShaderBottom = mBottom;
        }
        canvas.drawCircle(cx, cy, radius, mFillPaint);
        canvas.drawCircle(cx, cy, radius - mRimPaint.getStrokeWidth() / 2f, mRimPaint);

        if (mMode == Mode.CONNECTING) {
            float r = baseRadius + 8f * mDp;
            mArcRect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(mArcRect, mSpin * 360f - 90f, 100f, false, mArcPaint);
        }

        int baseAlpha = mMode == Mode.OFFLINE || mMode == Mode.MUTED ? 200 : 255;
        if (mOldIcon != null) {
            drawIcon(canvas, mOldIcon, cx, cy, radius, 1f - mIconFade, 1f - 0.08f * mIconFade, baseAlpha);
        }
        if (mIcon != null) {
            drawIcon(canvas, mIcon, cx, cy, radius, mIconFade, 0.92f + 0.08f * mIconFade, baseAlpha);
        }
    }

    /**
     * The grooved key of the handheld radio design: a dark lower edge, the face with a light
     * top edge and inner shadows, a rim and the label. Pressed, the face moves down onto the
     * edge and gets a shadow at the top.
     */
    private void drawKey(Canvas canvas) {
        Context c = getContext();
        float glow = KEY_GLOW * mDp;
        float edge = KEY_EDGE * mDp;
        float left = glow;
        float right = getWidth() - glow;
        float top = glow;
        float bottom = getHeight() - glow - edge;
        if (right - left < 2 * KEY_RIM * mDp || bottom - top < 2 * KEY_RIM * mDp) {
            return;
        }
        float radius = Math.min(KEY_RADIUS * mDp, (bottom - top) / 2f);
        // 0 at rest, 1 fully pressed; slightly below 0 while it springs back.
        float press = Math.max(-0.3f, Math.min(1f, (1f - mPressScale) / (1f - PRESSED_SCALE)));
        boolean talking = mMode == Mode.TALKING;
        int accent = mReceiving ? ContextCompat.getColor(c, R.color.handset_red)
                : talking ? ContextCompat.getColor(c, R.color.handset_green) : 0;

        // Lower edge, it stays where it is.
        mKeyPaint.setShader(null);
        mKeyPaint.setColor(ContextCompat.getColor(c, R.color.handset_key_edge));
        mKeyRect.set(left, top + edge, right, bottom + edge);
        canvas.drawRoundRect(mKeyRect, radius, radius, mKeyPaint);

        canvas.save();
        canvas.translate(0f, KEY_TRAVEL * mDp * press);
        mKeyRect.set(left, top, right, bottom);
        if (accent != 0) {
            // Glow: a few soft rings around the face.
            int[] alphas = {80, 44, 18};
            for (int i = 0; i < alphas.length; i++) {
                float spread = (i + 1) * 2.2f * mDp;
                mKeyGlowPaint.setColor(accent);
                mKeyGlowPaint.setAlpha(alphas[i]);
                canvas.drawRoundRect(left - spread, top - spread, right + spread, bottom + spread,
                        radius + spread, radius + spread, mKeyGlowPaint);
            }
        }
        // Face: a light top edge, then the grooves.
        mKeyPaint.setColor(ContextCompat.getColor(c, R.color.handset_ptt_light));
        canvas.drawRoundRect(mKeyRect, radius, radius, mKeyPaint);
        mKeyPaint.setShader(mKeyGrooves);
        canvas.drawRoundRect(left, top + 2f * mDp, right, bottom, radius, radius, mKeyPaint);
        mKeyPaint.setShader(null);

        // Inner shadows: always at the bottom, at the top while pressed.
        if (mKeyBottomShade == null || mKeyTopShade == null) {
            mKeyBottomShade = new LinearGradient(0f, bottom - 18f * mDp, 0f, bottom,
                    0x00000000, 0x99000000, Shader.TileMode.CLAMP);
            mKeyTopShade = new LinearGradient(0f, top, 0f, top + 22f * mDp,
                    0xCC000000, 0x00000000, Shader.TileMode.CLAMP);
        }
        canvas.save();
        mKeyPath.reset();
        mKeyPath.addRoundRect(mKeyRect, radius, radius, Path.Direction.CW);
        canvas.clipPath(mKeyPath);
        mKeyShadePaint.setShader(mKeyBottomShade);
        mKeyShadePaint.setAlpha(255);
        canvas.drawRect(mKeyRect, mKeyShadePaint);
        if (press > 0f) {
            mKeyShadePaint.setShader(mKeyTopShade);
            mKeyShadePaint.setAlpha((int) (255 * press));
            canvas.drawRect(mKeyRect, mKeyShadePaint);
        }
        canvas.restore();

        // Rim
        float inset = KEY_RIM * mDp / 2f;
        mKeyRimPaint.setColor(accent != 0 ? accent : ContextCompat.getColor(c, R.color.handset_housing_edge));
        canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset,
                radius - inset, radius - inset, mKeyRimPaint);

        // Label and the line under it, centered on the face. Long labels get smaller.
        float maxWidth = right - left - 2 * (KEY_RIM + 14f) * mDp;
        float labelSize = Math.min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                KEY_LABEL_SP, getResources().getDisplayMetrics()), (bottom - top) * 0.26f);
        mKeyLabelPaint.setTextSize(labelSize);
        float labelWidth = mKeyLabelPaint.measureText(mKeyLabel);
        if (labelWidth > maxWidth && labelWidth > 0f) {
            mKeyLabelPaint.setTextSize(labelSize * maxWidth / labelWidth);
        }
        mKeyLabelPaint.setColor(accent != 0 && talking ? accent
                : ContextCompat.getColor(c, R.color.handset_ptt_text));
        mKeyLabelPaint.setAlpha(mMode == Mode.READY || talking ? 255 : 190);
        CharSequence sub = TextUtils.ellipsize(mKeySub, mKeySubPaint, maxWidth, TextUtils.TruncateAt.END);
        Paint.FontMetrics label = mKeyLabelPaint.getFontMetrics();
        Paint.FontMetrics small = mKeySubPaint.getFontMetrics();
        float gap = sub.length() > 0 ? 4f * mDp : 0f;
        float subHeight = sub.length() > 0 ? small.descent - small.ascent : 0f;
        float height = label.descent - label.ascent + gap + subHeight;
        float y = (top + bottom) / 2f - height / 2f;
        float cx = (left + right) / 2f;
        canvas.drawText(mKeyLabel, cx, y - label.ascent, mKeyLabelPaint);
        if (sub.length() > 0) {
            y += label.descent - label.ascent + gap;
            canvas.drawText(sub, 0, sub.length(), cx, y - small.ascent, mKeySubPaint);
        }
        canvas.restore();
    }

    private static void drawIcon(Canvas canvas, Drawable icon, float cx, float cy, float radius,
                                 float alpha, float scale, int baseAlpha) {
        int half = (int) (radius * 0.42f * scale);
        icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        icon.setAlpha((int) (baseAlpha * alpha));
        icon.draw(canvas);
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

    /** Gives way quickly under the finger, springs back gently when let go. */
    private void animatePress(boolean pressed) {
        if (mScaleAnimator != null) {
            mScaleAnimator.cancel();
        }
        mScaleAnimator = ValueAnimator.ofFloat(mPressScale, pressed ? PRESSED_SCALE : 1f);
        if (pressed) {
            mScaleAnimator.setDuration(FunkMotion.PRESS_MS);
            mScaleAnimator.setInterpolator(FunkMotion.STANDARD);
        } else {
            mScaleAnimator.setDuration(FunkMotion.RELEASE_MS);
            mScaleAnimator.setInterpolator(new OvershootInterpolator(1.6f));
        }
        mScaleAnimator.addUpdateListener(a -> {
            mPressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        mScaleAnimator.start();
    }

    private void startPulse() {
        if (mPulseAnimator != null || !FunkMotion.enabled()) {
            return;
        }
        mPulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        mPulseAnimator.setDuration(PULSE_MS);
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
        if (mSpinAnimator != null || !FunkMotion.enabled()) {
            return;
        }
        mSpinAnimator = ValueAnimator.ofFloat(0f, 1f);
        mSpinAnimator.setDuration(SPIN_MS);
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
        if (mChangeAnimator != null) {
            mChangeAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyMode(false);
    }
}
