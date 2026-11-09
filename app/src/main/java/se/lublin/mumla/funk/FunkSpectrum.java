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

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import se.lublin.humla.audio.AudioTap;

/**
 * Spectrum of the voice for the display of the handheld radio design: {@link #BANDS} levels
 * from 100 Hz to 8 kHz, about 25 times a second, separately for the own microphone (while
 * transmitting) and for what is played (others talking). It listens to Humla's
 * {@link AudioTap} only while a display shows it, and the audio only flows while somebody
 * talks: in the quiet it costs nothing.
 */
final class FunkSpectrum implements AudioTap.Listener {
    static final int BANDS = 16;

    interface Listener {
        /**
         * Main thread. {@code levels} (0 to 1, one per band) belongs to the listener.
         *
         * @param input true for the own microphone, false for the others
         */
        void onSpectrum(@NonNull float[] levels, boolean input);
    }

    private static final FunkSpectrum INSTANCE = new FunkSpectrum();
    /** Lowest and highest band edge, in Hz: the range of a voice. */
    private static final float LOW_HZ = 100f;
    private static final float HIGH_HZ = 8000f;
    /** Levels below this (dB of full scale, after the tilt) show nothing, above the top all. */
    private static final float FLOOR_DB = -72f;
    private static final float TOP_DB = -12f;
    /** Voices are quieter in the highs: lift them by this many dB per octave above 400 Hz. */
    private static final float TILT_DB_PER_OCTAVE = 3f;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Analyzer mInput = new Analyzer(true);
    private final Analyzer mOutput = new Analyzer(false);

    private FunkSpectrum() {
    }

    @MainThread
    static void addListener(@NonNull Listener listener) {
        if (!INSTANCE.mListeners.contains(listener)) {
            INSTANCE.mListeners.add(listener);
        }
        AudioTap.setListener(INSTANCE);
    }

    @MainThread
    static void removeListener(@NonNull Listener listener) {
        INSTANCE.mListeners.remove(listener);
        if (INSTANCE.mListeners.isEmpty()) {
            AudioTap.setListener(null);
        }
    }

    /** Audio threads: the microphone thread for {@code input}, the playback thread otherwise. */
    @Override
    public void onAudio(short[] pcm, int offset, int length, int sampleRate, boolean input) {
        (input ? mInput : mOutput).feed(pcm, offset, length, sampleRate);
    }

    private void deliver(@NonNull float[] levels, boolean input) {
        mMain.post(() -> {
            for (Listener listener : mListeners) {
                listener.onSpectrum(levels, input);
            }
        });
    }

    /**
     * One audio source: a ring buffer of the latest samples and a Hann-windowed FFT over it,
     * once per {@link #mHop} new samples. Only ever used by one audio thread.
     */
    private final class Analyzer {
        private final boolean mIsInput;
        private int mRate;
        private int mSize;
        private int mHop;
        private float[] mRing;
        private int mPos;
        private int mSinceLast;
        private float[] mWindow;
        private float[] mRe;
        private float[] mIm;
        private float[] mCos;
        private float[] mSin;
        private int[] mBandFrom;
        private int[] mBandTo;
        private float[] mBandTilt;

        Analyzer(boolean input) {
            mIsInput = input;
        }

        void feed(short[] pcm, int offset, int length, int rate) {
            if (rate <= 0 || length <= 0) {
                return;
            }
            if (rate != mRate) {
                setUp(rate);
            }
            int mask = mSize - 1;
            for (int i = 0; i < length; i++) {
                mRing[mPos] = pcm[offset + i] / 32768f;
                mPos = (mPos + 1) & mask;
            }
            mSinceLast += length;
            if (mSinceLast >= mHop) {
                // Playback comes in chunks of up to 120 ms: one spectrum of the newest samples.
                mSinceLast = 0;
                deliver(analyze(), mIsInput);
            }
        }

        private void setUp(int rate) {
            mRate = rate;
            // About 20 ms per window (a power of two), a new spectrum every 40 ms.
            int size = 128;
            while (size * 2 <= rate / 40) {
                size *= 2;
            }
            mSize = size;
            mHop = rate / 25;
            mRing = new float[size];
            mPos = 0;
            mSinceLast = 0;
            mWindow = new float[size];
            mRe = new float[size];
            mIm = new float[size];
            mCos = new float[size / 2];
            mSin = new float[size / 2];
            for (int i = 0; i < size; i++) {
                mWindow[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1)));
            }
            for (int i = 0; i < size / 2; i++) {
                mCos[i] = (float) Math.cos(-2 * Math.PI * i / size);
                mSin[i] = (float) Math.sin(-2 * Math.PI * i / size);
            }
            // Bands evenly spaced on a log scale, at least one FFT bin each.
            float binHz = (float) rate / size;
            float high = Math.min(HIGH_HZ, rate * 0.45f);
            mBandFrom = new int[BANDS];
            mBandTo = new int[BANDS];
            mBandTilt = new float[BANDS];
            for (int b = 0; b < BANDS; b++) {
                double lo = LOW_HZ * Math.pow(high / LOW_HZ, (double) b / BANDS);
                double hi = LOW_HZ * Math.pow(high / LOW_HZ, (double) (b + 1) / BANDS);
                int from = (int) Math.ceil(lo / binHz);
                int to = (int) Math.floor(hi / binHz);
                if (to < from) {
                    from = to = (int) Math.round(Math.sqrt(lo * hi) / binHz);
                }
                mBandFrom[b] = Math.max(1, from);
                mBandTo[b] = Math.min(size / 2 - 1, Math.max(mBandFrom[b], to));
                double center = Math.sqrt(lo * hi);
                mBandTilt[b] = (float) (TILT_DB_PER_OCTAVE * Math.log(center / 400.0) / Math.log(2));
            }
        }

        private float[] analyze() {
            int n = mSize;
            for (int i = 0; i < n; i++) {
                mRe[i] = mRing[(mPos + i) & (n - 1)] * mWindow[i]; // oldest sample first
                mIm[i] = 0f;
            }
            fft(mRe, mIm);
            float[] levels = new float[BANDS];
            for (int b = 0; b < BANDS; b++) {
                float power = 0f;
                for (int k = mBandFrom[b]; k <= mBandTo[b]; k++) {
                    power += mRe[k] * mRe[k] + mIm[k] * mIm[k];
                }
                power /= mBandTo[b] - mBandFrom[b] + 1;
                // Amplitude of a sine in this band: 2/N for one side, 2 for the Hann window.
                double amplitude = Math.sqrt(power) * 4.0 / n;
                double db = 20.0 * Math.log10(amplitude + 1e-9) + mBandTilt[b];
                levels[b] = (float) Math.max(0.0, Math.min(1.0, (db - FLOOR_DB) / (TOP_DB - FLOOR_DB)));
            }
            return levels;
        }

        /** In-place radix-2 FFT; the length is a power of two. */
        private void fft(float[] re, float[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    float t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1;
                int step = n / len;
                for (int i = 0; i < n; i += len) {
                    for (int k = 0; k < half; k++) {
                        float wr = mCos[k * step];
                        float wi = mSin[k * step];
                        int a = i + k;
                        int b = a + half;
                        float xr = re[b] * wr - im[b] * wi;
                        float xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }
}
