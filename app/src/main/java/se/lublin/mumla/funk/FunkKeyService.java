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

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service that only watches the volume-down key: holding it for 2 seconds opens
 * the walkie-talkie popup, a short press is passed on as a normal volume change.
 * <p>
 * Android delivers hardware keys to apps in the background only through an accessibility
 * service. It does not work with the screen off (the system then routes volume keys straight
 * to media playback).
 */
public class FunkKeyService extends AccessibilityService {
    private static final String TAG = FunkKeyService.class.getName();
    static final long HOLD_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mDown;
    private boolean mFired;

    private final Runnable mLongPress = () -> {
        if (!mDown) {
            return;
        }
        mFired = true;
        try {
            startActivity(PttActivity.createIntent(this, -1, false));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the walkie-talkie popup: " + e);
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (PttActivity.isVisible() && !mDown) {
            // The popup handles volume-down itself (push-to-talk key).
            return false;
        }
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0 && !mDown) {
                    mDown = true;
                    mFired = false;
                    mHandler.postDelayed(mLongPress, HOLD_MS);
                }
                return true;
            case KeyEvent.ACTION_UP:
                mHandler.removeCallbacks(mLongPress);
                boolean wasShortPress = mDown && !mFired;
                mDown = false;
                mFired = false;
                if (wasShortPress) {
                    lowerVolume();
                }
                return true;
            default:
                return false;
        }
    }

    /** A short press was swallowed while waiting for the long press: do what it would have. */
    private void lowerVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not interested in UI events, only in key events.
    }

    @Override
    public void onInterrupt() {
        mHandler.removeCallbacks(mLongPress);
        mDown = false;
        mFired = false;
    }

    /** Whether the user enabled this service in the system accessibility settings. */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName me = new ComponentName(context, FunkKeyService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String component : splitter) {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (me.equals(cn)) {
                return true;
            }
        }
        return false;
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
