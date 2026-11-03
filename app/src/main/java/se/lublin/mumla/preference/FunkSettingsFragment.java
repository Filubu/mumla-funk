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

package se.lublin.mumla.preference;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import se.lublin.mumla.R;
import se.lublin.mumla.funk.BatteryOptimization;

/**
 * Settings of the walkie-talkie: power button shortcut (digital assistant), volume-down in the
 * popup and energy saving. The power button and the battery exemption live in system settings;
 * we show their status and link there.
 */
public class FunkSettingsFragment extends MumlaPreferenceFragment {
    private static final String KEY_ASSISTANT = "funk_assistant";
    private static final String KEY_BATTERY = "funk_battery_optimization";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_funk, rootKey);
        Preference assistant = findPreference(KEY_ASSISTANT);
        if (assistant != null) {
            assistant.setOnPreferenceClickListener(p -> {
                // "Digital assistant app" lives under voice input settings; else the default apps.
                Intent defaultApps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        : new Intent(android.provider.Settings.ACTION_SETTINGS);
                openFirst(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
                        defaultApps, new Intent(android.provider.Settings.ACTION_SETTINGS));
                return true;
            });
        }
        Preference battery = findPreference(KEY_BATTERY);
        if (battery != null) {
            if (BatteryOptimization.isRelevant()) {
                battery.setOnPreferenceClickListener(p -> {
                    if (!BatteryOptimization.openSettings(requireActivity())) {
                        Toast.makeText(requireContext(), R.string.pref_funk_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            } else {
                battery.setVisible(false); // Android 5: no Doze, nothing to allow.
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = requireContext();
        Preference assistant = findPreference(KEY_ASSISTANT);
        if (assistant != null) {
            assistant.setSummary(isAssistant(context)
                    ? R.string.pref_funk_assistant_on : R.string.pref_funk_assistant_off);
        }
        Preference battery = findPreference(KEY_BATTERY);
        if (battery != null) {
            battery.setSummary(BatteryOptimization.isExempt(context)
                    ? R.string.pref_funk_battery_on : R.string.pref_funk_battery_off);
        }
    }

    private static boolean isAssistant(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = context.getSystemService(RoleManager.class);
            return roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                    && roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        }
        return false;
    }

    private void openFirst(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next one.
            }
        }
        Toast.makeText(requireContext(), R.string.pref_funk_settings_unavailable, Toast.LENGTH_LONG).show();
    }
}
