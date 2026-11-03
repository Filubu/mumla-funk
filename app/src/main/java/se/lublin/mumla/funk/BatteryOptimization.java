/*
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import se.lublin.mumla.R;

/**
 * Exemption from battery optimization. Only with it, the energy saving mode lets the phone sleep
 * between keep-alive pings: Android delivers the keep-alive alarms in Doze without throttling only
 * to exempt apps. Without it, the phone is kept awake while connected (as before), it just uses
 * the radio less.
 */
public final class BatteryOptimization {
    private BatteryOptimization() {
    }

    /** @return true if there is something to ask for (Android 6+). */
    public static boolean isRelevant() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static boolean isExempt(Context context) {
        if (!isRelevant()) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Asks for the exemption (system dialog), or opens the list if that is not possible. */
    @SuppressLint("BatteryLife") // Core feature: a walkie-talkie that has to stay reachable.
    public static void request(Activity activity) {
        if (!isRelevant()) return;
        Intent ask = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName()));
        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        for (Intent intent : isExempt(activity) ? new Intent[]{list} : new Intent[]{ask, list}) {
            try {
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next one.
            }
        }
    }

    /**
     * Explains and asks once, if energy saving is on and the app is not exempt yet.
     * Later on, the walkie-talkie settings offer it.
     */
    public static void maybeAskOnce(Activity activity) {
        if (!isRelevant() || activity.isFinishing()) return;
        FunkPrefs prefs = FunkPrefs.get(activity);
        if (prefs.wasBatteryOptimizationAsked()
                || !se.lublin.mumla.Settings.getInstance(activity).isEnergySavingEnabled()
                || isExempt(activity)) {
            return;
        }
        prefs.setBatteryOptimizationAsked();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.funk_battery_dialog_title)
                .setMessage(R.string.funk_battery_dialog_message)
                .setPositiveButton(R.string.funk_battery_dialog_allow, (d, w) -> request(activity))
                .setNegativeButton(R.string.funk_battery_dialog_later, null)
                .show();
    }
}
