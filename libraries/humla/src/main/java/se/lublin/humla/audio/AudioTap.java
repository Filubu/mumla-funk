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

import org.jetbrains.annotations.Nullable;

/**
 * Mumla Funk: lets the app look at the audio for a visualization (the spectrum of the
 * walkie-talkie display), without touching what is sent or played. Two places feed it: the
 * microphone while transmitting, and the mix of everybody else right before it is played.
 * Without a listener (the usual case) a feed is one volatile read.
 */
public final class AudioTap {
    public interface Listener {
        /**
         * Called on an audio thread. Must return quickly and must not keep {@code pcm}, which is
         * reused for the next frame.
         *
         * @param input true for the own microphone (transmitting), false for what is played
         */
        void onAudio(short[] pcm, int offset, int length, int sampleRate, boolean input);
    }

    @Nullable
    private static volatile Listener sListener;

    private AudioTap() {
    }

    public static void setListener(@Nullable Listener listener) {
        sListener = listener;
    }

    static void feed(short[] pcm, int offset, int length, int sampleRate, boolean input) {
        Listener listener = sListener;
        if (listener != null) {
            listener.onAudio(pcm, offset, length, sampleRate, input);
        }
    }

    /** For the protocol package, which handles the microphone frames. */
    public static void feedInput(short[] pcm, int length, int sampleRate) {
        feed(pcm, 0, length, sampleRate, true);
    }
}
