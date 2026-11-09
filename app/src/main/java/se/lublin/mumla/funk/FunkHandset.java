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
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import java.util.Date;
import java.util.List;

import se.lublin.mumla.R;

/**
 * The handheld radio design of the walkie-talkie screen and the popup (Settings › Walkie-talkie
 * › Design): status lamps and a glowing display, filled from {@link FunkState}. The display is
 * amber while ready, green while I transmit and red while somebody else talks, like the
 * MumbleFunkClient this design comes from. At the bottom of the display the spectrum of the
 * voice ({@link FunkSpectrum}, {@link FunkSpectrumView}) has a room of its own. Layouts:
 * {@code funk_handset_lcd.xml} and {@code funk_handset_lamps.xml}; the push-to-talk key is
 * {@link PttButtonView.Look#KEY}. Call {@link #resume()} and {@link #stop()} with the screen.
 */
final class FunkHandset {
    /** What the display shows. TX is green, RX and BUSY are red, everything else is amber. */
    enum Mode {OFF, CONNECTING, LOST, READY, TX, RX, BUSY, MUTED}

    /** The mode line blinks while connecting, without signal and when the channel is busy. */
    private static final long BLINK_MS = 250;
    /** Ping thresholds for 4, 3 and 2 signal bars, in ms (like the colors of the default design). */
    private static final long[] BAR_LIMITS_MS = {60, 120, 300};
    /** Alpha of the glow around lit text (0x8C = 55 %). */
    private static final int GLOW_ALPHA = 0x8C;
    /** The whole display is dimmed while switched off. */
    private static final float OFF_ALPHA = 0.55f;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final float mDp;
    private final View mContent;
    private final TextView mLampNet;
    private final TextView mLampRx;
    private final TextView mLampTx;
    private final View[] mBars;
    private final TextView mMs;
    private final TextView mServer;
    private final TextView mChannelIndex;
    private final TextView mBig;
    private final TextView mModeText;
    private final TextView mWho;
    private final View mDivider;
    private final View mUsersScroll;
    private final ViewGroup mUsers;
    private final View mSpectrumSection;
    private final View mSpectrumDivider;
    private final FunkSpectrumView mSpectrum;
    private final FunkSpectrum.Listener mSpectrumListener;
    /** The popup shows the spectrum only on tall screens, see {@link #setSpectrumShown}. */
    private boolean mSpectrumAllowed = true;
    /** Height of the spectrum section while shown, to know when it fits again. */
    private int mSpectrumHeight;
    private boolean mResumed;
    /** Texts of the display that take its color. */
    private final TextView[] mTexts;
    @ColorInt
    private final int mAmber;
    @ColorInt
    private final int mGreen;
    @ColorInt
    private final int mRed;
    @ColorInt
    private final int mOnLcd;

    /** Rows of people the popup shows (more scroll); 0: the list fills the display. */
    private int mMaxRows;
    @ColorInt
    private int mColor;
    private int mBarsLit = -1;
    private boolean mBlinking;
    private boolean mBlinkDim;
    private final Runnable mBlink = new Runnable() {
        @Override
        public void run() {
            mBlinkDim = !mBlinkDim;
            mModeText.setAlpha(mBlinkDim ? 0.25f : 1f);
            mHandler.postDelayed(this, BLINK_MS);
        }
    };

    /** @param root a view containing {@code funk_handset_lcd} and {@code funk_handset_lamps}. */
    FunkHandset(@NonNull View root) {
        mContext = root.getContext();
        mDp = mContext.getResources().getDisplayMetrics().density;
        FrameLayout lcd = root.findViewById(R.id.handset_lcd);
        lcd.setForeground(new LcdOverlay(mDp));
        mContent = root.findViewById(R.id.handset_lcd_content);
        mLampNet = root.findViewById(R.id.handset_lamp_net);
        mLampRx = root.findViewById(R.id.handset_lamp_rx);
        mLampTx = root.findViewById(R.id.handset_lamp_tx);
        mBars = new View[]{root.findViewById(R.id.handset_bar1), root.findViewById(R.id.handset_bar2),
                root.findViewById(R.id.handset_bar3), root.findViewById(R.id.handset_bar4)};
        mMs = root.findViewById(R.id.handset_ms);
        mServer = root.findViewById(R.id.handset_server);
        mChannelIndex = root.findViewById(R.id.handset_channel_index);
        mBig = root.findViewById(R.id.handset_big);
        mModeText = root.findViewById(R.id.handset_mode);
        mWho = root.findViewById(R.id.handset_who);
        mDivider = root.findViewById(R.id.handset_divider);
        mUsersScroll = root.findViewById(R.id.handset_users_scroll);
        mUsers = root.findViewById(R.id.handset_users);
        mSpectrumSection = root.findViewById(R.id.handset_spectrum_section);
        mSpectrumDivider = root.findViewById(R.id.handset_spectrum_divider);
        mSpectrum = root.findViewById(R.id.handset_spectrum);
        mSpectrumListener = mSpectrum::setLevels;
        mTexts = new TextView[]{mMs, mServer, mChannelIndex, mBig, mModeText, mWho,
                root.findViewById(R.id.handset_spectrum_label)};
        // The screen: the spectrum goes when the list would get less than two rows.
        mUsersScroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                                                oldRight, oldBottom) -> fitSpectrum(bottom - top));
        mAmber = ContextCompat.getColor(mContext, R.color.handset_amber);
        mGreen = ContextCompat.getColor(mContext, R.color.handset_green);
        mRed = ContextCompat.getColor(mContext, R.color.handset_red);
        mOnLcd = ContextCompat.getColor(mContext, R.color.handset_on_lcd);
        mMs.setText(R.string.handset_no_ping);
        applyColor(mAmber);
        renderBars(0);
    }

    /**
     * The popup shows at most this many people (more scroll) and leaves out the list while not
     * connected; the screen (0, the default) lets the list fill the display.
     */
    void setMaxRows(int rows) {
        mMaxRows = rows;
    }

    /** The popup: whether its display has room for the spectrum (the screen decides itself). */
    void setSpectrumShown(boolean shown) {
        mSpectrumAllowed = shown;
        mSpectrumSection.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (mResumed) {
            if (shown) {
                FunkSpectrum.addListener(mSpectrumListener);
            } else {
                FunkSpectrum.removeListener(mSpectrumListener);
            }
        }
    }

    /** The display is on screen: the spectrum starts listening to the audio. */
    void resume() {
        mResumed = true;
        if (mSpectrumAllowed) {
            FunkSpectrum.addListener(mSpectrumListener);
        }
    }

    /** What the display shows for a state; {@code transmitting} includes a finger on the key. */
    @NonNull
    static Mode modeOf(@NonNull FunkState.Connection connection, @NonNull FunkState state,
                       boolean transmitting) {
        switch (connection) {
            case CONNECTING:
                return Mode.CONNECTING;
            case RECONNECTING:
                return Mode.LOST;
            case CONNECTED:
                boolean others = !state.talkingOthers().isEmpty();
                if (state.selfMuted) {
                    return others ? Mode.RX : Mode.MUTED;
                }
                if (transmitting) {
                    return others ? Mode.BUSY : Mode.TX;
                }
                return others ? Mode.RX : Mode.READY;
            default:
                return Mode.OFF;
        }
    }

    /**
     * Fills lamps and display.
     *
     * @param state      the current state; channel and people are only read in connected modes
     * @param serverName shown while not connected
     * @param offLine    the small line while switched off (null: "ready to switch on")
     */
    void render(@NonNull Mode mode, @NonNull FunkState state, @Nullable String serverName,
                @Nullable String offLine) {
        boolean connected = mode != Mode.OFF && mode != Mode.CONNECTING && mode != Mode.LOST;
        int color = mode == Mode.TX ? mGreen : mode == Mode.RX || mode == Mode.BUSY ? mRed : mAmber;
        if (color != mColor) {
            applyColor(color);
        }
        mContent.setAlpha(mode == Mode.OFF ? OFF_ALPHA : 1f);
        setLamp(mLampNet, connected, R.drawable.handset_lamp_net);
        setLamp(mLampRx, mode == Mode.RX || mode == Mode.BUSY, R.drawable.handset_lamp_rx);
        setLamp(mLampTx, mode == Mode.TX, R.drawable.handset_lamp_tx);

        String name = serverName != null ? serverName : state.serverName;
        String server = "";
        String index = "";
        String big;
        String who = "";
        if (connected) {
            server = name != null ? name : "";
            int position = state.channelIndex();
            if (position >= 0) {
                index = mContext.getString(R.string.handset_channel_index, position + 1,
                        state.channelIds.size());
            }
            big = state.channelName != null && !state.channelName.isEmpty() ? state.channelName : "—";
            who = whoLine(mode, state);
        } else {
            big = name != null ? name : "—";
            if (mode == Mode.LOST) {
                who = mContext.getString(R.string.handset_reconnecting);
            } else if (mode == Mode.OFF) {
                who = offLine != null ? offLine : mContext.getString(R.string.handset_ready_to_switch_on);
            }
        }
        setText(mServer, server);
        setText(mChannelIndex, index);
        setText(mBig, big);
        setText(mModeText, modeName(mode));
        setText(mWho, who);
        if (!connected) {
            renderPing(-1, false);
        }
        renderUsers(connected ? state.members : null);
        setBlinking(mode == Mode.CONNECTING || mode == Mode.LOST || mode == Mode.BUSY);
    }

    /** Signal bars and the ping in ms; {@code ms} below 0: no value. In idle mode it is old. */
    void renderPing(long ms, boolean idle) {
        int bars = 0;
        if (ms >= 0) {
            bars = 1;
            for (int i = 0; i < BAR_LIMITS_MS.length; i++) {
                if (ms <= BAR_LIMITS_MS[i]) {
                    bars = BAR_LIMITS_MS.length + 1 - i;
                    break;
                }
            }
        }
        renderBars(bars);
        if (ms < 0) {
            setText(mMs, mContext.getString(R.string.handset_no_ping));
            mMs.setContentDescription(null);
        } else {
            setText(mMs, idle ? mContext.getString(R.string.handset_idle)
                    : mContext.getString(R.string.funk_ping_ms, ms));
            mMs.setContentDescription(mContext.getString(
                    idle ? R.string.funk_ping_idle_desc : R.string.funk_ping_desc, ms));
        }
    }

    /** Big label on the push-to-talk key: "PTT", or what it does right now. */
    @NonNull
    static String keyLabel(@NonNull Context context, @NonNull PttButtonView.Mode pttMode,
                           @NonNull Mode mode) {
        if (pttMode == PttButtonView.Mode.TALKING) {
            return context.getString(mode == Mode.BUSY ? R.string.handset_mode_busy : R.string.handset_mode_tx);
        }
        if (pttMode == PttButtonView.Mode.MUTED) {
            return context.getString(R.string.handset_mode_muted);
        }
        return context.getString(R.string.handset_ptt);
    }

    /** Off screen: stops the blinking and the spectrum. {@link #render} restarts the blinking. */
    void stop() {
        mResumed = false;
        setBlinking(false);
        FunkSpectrum.removeListener(mSpectrumListener);
        mSpectrum.clear();
    }

    /**
     * The screen's display gets shorter on small phones and in split screen: there the list
     * of people keeps at least two rows and the spectrum goes, it comes back when there is
     * room again. Never anything on top of something else.
     */
    private void fitSpectrum(int usersHeight) {
        if (mMaxRows > 0 || !mSpectrumAllowed) {
            return;
        }
        int minimum = 2 * mContext.getResources().getDimensionPixelSize(R.dimen.handset_user_row_height);
        if (mSpectrumSection.getVisibility() == View.VISIBLE) {
            mSpectrumHeight = mSpectrumSection.getHeight();
            if (usersHeight < minimum) {
                mSpectrumSection.post(() -> mSpectrumSection.setVisibility(View.GONE));
            }
        } else if (mSpectrumHeight > 0 && usersHeight >= minimum + mSpectrumHeight) {
            mSpectrumSection.post(() -> mSpectrumSection.setVisibility(View.VISIBLE));
        }
    }

    /** Lets accessibility services announce the key-styled text views as buttons. */
    static void asButton(@NonNull View... views) {
        for (View view : views) {
            if (view == null) {
                continue;
            }
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                              @NonNull AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
        }
    }

    // ---------------------------------------------------------------- internals

    /** The state comes several times a second while people talk: only set what changed. */
    private static void setText(@NonNull TextView view, @NonNull CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private String modeName(@NonNull Mode mode) {
        switch (mode) {
            case CONNECTING:
                return mContext.getString(R.string.handset_mode_connecting);
            case LOST:
                return mContext.getString(R.string.handset_mode_lost);
            case READY:
                return mContext.getString(R.string.handset_mode_ready);
            case TX:
                return mContext.getString(R.string.handset_mode_tx);
            case RX:
                return mContext.getString(R.string.handset_mode_rx);
            case BUSY:
                return mContext.getString(R.string.handset_mode_busy);
            case MUTED:
                return mContext.getString(R.string.handset_mode_muted);
            case OFF:
            default:
                return mContext.getString(R.string.handset_mode_off);
        }
    }

    /** Who is talking; while the channel is clear, who talked last (or my own name). */
    private String whoLine(@NonNull Mode mode, @NonNull FunkState state) {
        List<FunkState.Member> talking = state.talkingOthers();
        if (mode == Mode.RX || mode == Mode.BUSY) {
            StringBuilder names = new StringBuilder();
            for (FunkState.Member m : talking) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(m.name);
            }
            return mode == Mode.BUSY ? mContext.getString(R.string.handset_x_sending, names) : names.toString();
        }
        String last = FunkState.lastTalker();
        if (mode == Mode.READY && last != null) {
            String time = DateFormat.getTimeFormat(mContext).format(new Date(FunkState.lastTalkAt()));
            return mContext.getString(R.string.funk_now_last, last, time);
        }
        for (FunkState.Member m : state.members) {
            if (m.self) {
                return mContext.getString(R.string.handset_me, m.name);
            }
        }
        return "";
    }

    private void setLamp(@Nullable TextView lamp, boolean on, int onDrawable) {
        if (lamp == null) {
            return;
        }
        Object tag = lamp.getTag();
        if (tag instanceof Boolean && (Boolean) tag == on) {
            return;
        }
        lamp.setTag(on);
        lamp.setCompoundDrawablesRelativeWithIntrinsicBounds(
                on ? onDrawable : R.drawable.handset_lamp_off, 0, 0, 0);
        lamp.setTextColor(ContextCompat.getColor(mContext, on ? R.color.handset_text : R.color.handset_muted));
    }

    /** Display color: all texts, their glow, the bars, the divider and the people. */
    private void applyColor(@ColorInt int color) {
        mColor = color;
        int glowColor = ColorUtils.setAlphaComponent(color, GLOW_ALPHA);
        for (TextView text : mTexts) {
            glow(text, color, glowColor);
        }
        mDivider.setBackground(new Dashes(ColorUtils.setAlphaComponent(color, 0x66), 3f * mDp));
        mSpectrumDivider.setBackground(new Dashes(ColorUtils.setAlphaComponent(color, 0x66), 3f * mDp));
        mSpectrum.setColor(color);
        int lit = mBarsLit;
        mBarsLit = -1;
        renderBars(Math.max(lit, 0));
        for (int i = 0; i < mUsers.getChildCount(); i++) {
            bindRowColor(mUsers.getChildAt(i));
        }
    }

    private void glow(@NonNull TextView text, @ColorInt int color, @ColorInt int glow) {
        text.setTextColor(color);
        text.setShadowLayer(6f * mDp, 0f, 0f, glow);
    }

    private void renderBars(int lit) {
        if (lit == mBarsLit) {
            return;
        }
        mBarsLit = lit;
        int off = ColorUtils.blendARGB(0xFF0A0E0B, mColor, 0.22f);
        for (int i = 0; i < mBars.length; i++) {
            mBars[i].setBackgroundColor(i < lit ? mColor : off);
        }
    }

    private void setBlinking(boolean blink) {
        blink = blink && FunkMotion.enabled();
        if (blink == mBlinking) {
            return;
        }
        mBlinking = blink;
        mHandler.removeCallbacks(mBlink);
        mBlinkDim = false;
        mModeText.setAlpha(1f);
        if (blink) {
            mHandler.postDelayed(mBlink, BLINK_MS);
        }
    }

    /** Everyone on the server, own channel first. Rows are reused, this runs while people talk. */
    private void renderUsers(@Nullable List<FunkState.Member> members) {
        if (members == null || members.isEmpty()) {
            int hidden = mMaxRows > 0 ? View.GONE : View.INVISIBLE;
            mUsersScroll.setVisibility(hidden);
            mDivider.setVisibility(hidden);
            return;
        }
        mUsersScroll.setVisibility(View.VISIBLE);
        mDivider.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        while (mUsers.getChildCount() > members.size()) {
            mUsers.removeViewAt(mUsers.getChildCount() - 1);
        }
        while (mUsers.getChildCount() < members.size()) {
            mUsers.addView(inflater.inflate(R.layout.funk_handset_user_row, mUsers, false));
        }
        for (int i = 0; i < members.size(); i++) {
            bindRow(mUsers.getChildAt(i), members.get(i));
        }
        if (mMaxRows > 0) {
            // The popup: room for a few rows, more scroll.
            int rows = Math.min(members.size(), mMaxRows);
            int height = rows * mContext.getResources().getDimensionPixelSize(R.dimen.handset_user_row_height);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mUsersScroll.getLayoutParams();
            if (lp.height != height || lp.weight != 0f) {
                lp.height = height;
                lp.weight = 0f;
                mUsersScroll.setLayoutParams(lp);
            }
        }
    }

    private void bindRow(@NonNull View row, @NonNull FunkState.Member m) {
        TextView name = row.findViewById(R.id.handset_user_name);
        TextView tag = row.findViewById(R.id.handset_user_tag);
        setText(name, m.name);
        String tagText = m.self ? mContext.getString(R.string.handset_me_tag)
                : !m.sameChannel && m.channel != null ? m.channel
                : m.muted ? mContext.getString(R.string.handset_mode_muted) : "";
        setText(tag, tagText);
        Object was = row.getTag();
        if (!(was instanceof Boolean) || (Boolean) was != m.talking) {
            row.setTag(m.talking);
            bindRowColor(row);
        }

        StringBuilder description = new StringBuilder(m.self ? mContext.getString(R.string.funk_you, m.name) : m.name);
        if (m.talking) {
            description.append(", ").append(mContext.getString(R.string.funk_member_talking));
        }
        if (m.muted) {
            description.append(", ").append(mContext.getString(R.string.funk_member_muted));
        }
        if (!m.sameChannel && m.channel != null) {
            description.append(", ").append(m.channel);
        }
        row.setContentDescription(description);
    }

    /** Talking: the row lights up in the display color with dark text, without glow. */
    private void bindRowColor(@NonNull View row) {
        boolean talking = Boolean.TRUE.equals(row.getTag());
        TextView name = row.findViewById(R.id.handset_user_name);
        TextView tag = row.findViewById(R.id.handset_user_tag);
        if (talking) {
            GradientDrawable lit = new GradientDrawable();
            lit.setColor(mColor);
            lit.setCornerRadius(3f * mDp);
            row.setBackground(lit);
            for (TextView text : new TextView[]{name, tag}) {
                text.setTextColor(mOnLcd);
                text.setShadowLayer(0f, 0f, 0f, 0);
            }
        } else {
            row.setBackground(null);
            int glowColor = ColorUtils.setAlphaComponent(mColor, GLOW_ALPHA);
            glow(name, mColor, glowColor);
            glow(tag, mColor, glowColor);
        }
        tag.setAlpha(talking ? 0.8f : 0.6f);
    }

    /**
     * A dashed line, drawn as small rectangles: dashed strokes are not drawn the same on all
     * Android versions.
     */
    private static final class Dashes extends Drawable {
        private final Paint mPaint = new Paint();
        private final float mDash;

        Dashes(@ColorInt int color, float dash) {
            mPaint.setColor(color);
            mDash = dash;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            for (float x = bounds.left; x < bounds.right; x += 2 * mDash) {
                canvas.drawRect(x, bounds.top, Math.min(x + mDash, bounds.right), bounds.bottom, mPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            // Fixed look.
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // Fixed look.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Scan lines and a dark inner edge over the screen of the display, like an old LCD. The
     * screen area matches {@code bg_handset_lcd.xml}.
     */
    private static final class LcdOverlay extends Drawable {
        private final float mDp;
        private final Paint mLines = new Paint();
        private final Paint mShade = new Paint();
        private final Path mClip = new Path();
        private final RectF mScreen = new RectF();
        private final RectF mEdge = new RectF();
        private final Shader[] mShades = new Shader[4];

        LcdOverlay(float dp) {
            mDp = dp;
            // One dark line (1 dp) every 3 dp.
            int period = Math.max(3, Math.round(3f * dp));
            int line = Math.max(1, Math.round(dp));
            Bitmap tile = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < line; y++) {
                tile.setPixel(0, y, 0x33000000);
            }
            mLines.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            mScreen.set(bounds.left + 5f * mDp, bounds.top + 5f * mDp,
                    bounds.right - 5f * mDp, bounds.bottom - 6f * mDp);
            mClip.reset();
            mClip.addRoundRect(mScreen, 13f * mDp, 13f * mDp, Path.Direction.CW);
            float depth = 14f * mDp;
            int dark = 0x8C000000;
            int clear = 0x00000000;
            mShades[0] = new LinearGradient(0f, mScreen.top, 0f, mScreen.top + depth, dark, clear, Shader.TileMode.CLAMP);
            mShades[1] = new LinearGradient(0f, mScreen.bottom, 0f, mScreen.bottom - depth, dark, clear, Shader.TileMode.CLAMP);
            mShades[2] = new LinearGradient(mScreen.left, 0f, mScreen.left + depth, 0f, dark, clear, Shader.TileMode.CLAMP);
            mShades[3] = new LinearGradient(mScreen.right, 0f, mScreen.right - depth, 0f, dark, clear, Shader.TileMode.CLAMP);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mScreen.isEmpty() || mShades[0] == null) {
                return;
            }
            float depth = 14f * mDp;
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawRect(mScreen, mLines);
            for (int i = 0; i < mShades.length; i++) {
                switch (i) {
                    case 0:
                        mEdge.set(mScreen.left, mScreen.top, mScreen.right, mScreen.top + depth);
                        break;
                    case 1:
                        mEdge.set(mScreen.left, mScreen.bottom - depth, mScreen.right, mScreen.bottom);
                        break;
                    case 2:
                        mEdge.set(mScreen.left, mScreen.top, mScreen.left + depth, mScreen.bottom);
                        break;
                    default:
                        mEdge.set(mScreen.right - depth, mScreen.top, mScreen.right, mScreen.bottom);
                }
                mShade.setShader(mShades[i]);
                canvas.drawRect(mEdge, mShade);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            // Fixed look.
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // Fixed look.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
