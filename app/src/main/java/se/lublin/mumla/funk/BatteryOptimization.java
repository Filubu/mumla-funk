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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Exemption from battery optimization. Only with it, the energy saving mode lets the phone sleep
 * between keep-alive pings: Android delivers the keep-alive alarms in Doze without throttling only
 * to exempt apps. Without it, the phone is kept awake while connected (as before), it just uses
 * the radio less.
 * <p>
 * The app does not ask for the exemption itself (that needs the permission
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play restricts). It only opens the system
 * list, where the user can exempt it.
 */
public final class BatteryOptimization {
    private BatteryOptimization() {
    }

    /** @return true if there is something to allow (Android 6+). */
    public static boolean isRelevant() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static boolean isExempt(Context context) {
        if (!isRelevant()) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Opens the system list of battery optimization exemptions, or the app info as fallback.
     *
     * @return false if neither could be opened.
     */
    public static boolean openSettings(Activity activity) {
        if (!isRelevant()) return false;
        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        for (Intent intent : new Intent[]{list, appInfo}) {
            try {
                activity.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next one.
            }
        }
        return false;
    }
}
