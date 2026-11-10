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

package se.lublin.humla.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Mumla Funk: turns music and other media down while somebody talks, the way a navigation app
 * does for its announcements. It asks Android for the transient "may duck" audio focus when
 * voice starts to play and gives it back shortly after the voice ended; players then lower
 * their volume (or pause, for spoken content such as podcasts) and come back by themselves.
 *
 * <p>{@link AudioOutput} holds the voice back for {@link #LEAD_MS} when media was playing, so
 * the first words are not spoken over music that is still fading.
 */
public final class MediaDucker {
    private static final String TAG = MediaDucker.class.getName();

    /** How long the voice is held back while the media fades down. */
    public static final int LEAD_MS = 300;
    /**
     * The focus is kept this long after the voice ended: in a back-and-forth the music stays
     * down instead of swelling up between two transmissions.
     */
    private static final long HOLD_MS = 1500;

    private static volatile boolean sEnabled = true;

    private final AudioManager mAudioManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private final Runnable mAbandon = this::abandonNow;
    private final AudioManager.OnAudioFocusChangeListener mListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // Somebody else took over (a call, a navigation announcement): ask again next time.
            synchronized (mLock) {
                mHeld = false;
            }
        }
    };
    /** API 26+: an AudioFocusRequest; kept as Object so the class loads on older versions. */
    private Object mRequest;
    private boolean mHeld;

    public MediaDucker(Context context) {
        mAudioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    /** Setting of the app; applies from the next transmission on. */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * Voice is about to be played. Any thread.
     *
     * @return true if media was playing and has just been asked to duck: the caller should
     * hold the voice back for {@link #LEAD_MS}.
     */
    public boolean acquire() {
        mHandler.removeCallbacks(mAbandon);
        synchronized (mLock) {
            if (mHeld || !sEnabled || mAudioManager == null) {
                return false; // already ducked, or switched off
            }
            boolean mediaPlaying = mAudioManager.isMusicActive();
            int result;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioFocusRequest request = new AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .setOnAudioFocusChangeListener(mListener, mHandler)
                            .build();
                    mRequest = request;
                    result = mAudioManager.requestAudioFocus(request);
                } else {
                    result = mAudioManager.requestAudioFocus(mListener, AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "requestAudioFocus: " + e);
                return false;
            }
            mHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            return mHeld && mediaPlaying;
        }
    }

    /** The voice ended. The media comes back after a short while, unless voice starts again. */
    public void release() {
        mHandler.removeCallbacks(mAbandon);
        mHandler.postDelayed(mAbandon, HOLD_MS);
    }

    /** Gives the focus back right away (the audio output stops). */
    public void abandonNow() {
        mHandler.removeCallbacks(mAbandon);
        synchronized (mLock) {
            if (mAudioManager == null) {
                return;
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (mRequest != null) {
                        mAudioManager.abandonAudioFocusRequest((AudioFocusRequest) mRequest);
                        mRequest = null;
                    }
                } else {
                    mAudioManager.abandonAudioFocus(mListener);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "abandonAudioFocus: " + e);
            }
            mHeld = false;
        }
    }
}
