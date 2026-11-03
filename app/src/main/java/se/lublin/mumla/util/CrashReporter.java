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

package se.lublin.mumla.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;

/**
 * Keeps the details of a crash and shows them on the next start, so they can be copied and sent
 * without a computer (no adb needed).
 * <p>
 * Java crashes are written to a file by an uncaught exception handler. Crashes the handler
 * cannot see (native code, killed by the system) are taken from Android's exit records (11+).
 */
public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String FILE = "last_crash.txt";
    private static final String PREF_LAST_EXIT = "crash_reporter_last_exit_time";
    private static boolean sInstalled;

    private CrashReporter() {
    }

    /** Call early in every process entry point (Application.onCreate). Idempotent. */
    public static synchronized void install(Context context) {
        if (sInstalled) return;
        sInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                String report = header() + "Thread: " + thread.getName() + "\n\n" + trace;
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                Log.e(TAG, "could not save crash report", t);
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static String header() {
        return "Mumla Funk " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + "\n";
    }

    /** Shows the details of the last crash once, if there was one. Call from Activity.onResume. */
    public static void showIfCrashed(Activity activity) {
        try {
            String report = takeReport(activity);
            if (report == null || activity.isFinishing()) return;
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.crash_dialog_title)
                    .setMessage(activity.getString(R.string.crash_dialog_message) + "\n\n" + report)
                    .setPositiveButton(R.string.crash_dialog_copy, (d, w) -> copy(activity, report))
                    .setNeutralButton(R.string.crash_dialog_share, (d, w) -> share(activity, report))
                    .setNegativeButton(R.string.crash_dialog_close, null)
                    .show();
        } catch (RuntimeException e) {
            Log.e(TAG, "could not show crash report", e);
        }
    }

    private static String takeReport(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        String report = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
                int n = in.read(data);
                report = new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.e(TAG, "could not read crash report", e);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        String exit = lastAbnormalExit(context);
        if (report == null) {
            return exit;
        }
        return exit == null ? report : report + "\n\n" + exit;
    }

    /** Native crashes and the like, which the Java handler does not see (Android 11+). */
    private static String lastAbnormalExit(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return null;
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 5);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long seen = prefs.getLong(PREF_LAST_EXIT, 0);
        long newest = seen;
        StringBuilder sb = new StringBuilder();
        for (ApplicationExitInfo exit : exits) {
            if (exit.getTimestamp() <= seen) continue;
            newest = Math.max(newest, exit.getTimestamp());
            int reason = exit.getReason();
            if (reason != ApplicationExitInfo.REASON_CRASH
                    && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                    && reason != ApplicationExitInfo.REASON_ANR) {
                continue;
            }
            sb.append(reason == ApplicationExitInfo.REASON_CRASH_NATIVE ? "Native crash"
                            : reason == ApplicationExitInfo.REASON_ANR ? "ANR (not responding)" : "Crash")
                    .append(" at ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                            .format(new Date(exit.getTimestamp())))
                    .append(", process ").append(exit.getProcessName())
                    .append(", signal/status ").append(exit.getStatus())
                    .append(": ").append(exit.getDescription()).append('\n');
        }
        if (newest != seen) {
            prefs.edit().putLong(PREF_LAST_EXIT, newest).apply();
        }
        return sb.length() == 0 ? null : header() + "\nExit records:\n" + sb;
    }

    private static void copy(Context context, String report) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Mumla Funk crash", report));
            Toast.makeText(context, R.string.crash_dialog_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static void share(Activity activity, String report) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Mumla Funk crash")
                .putExtra(Intent.EXTRA_TEXT, report);
        try {
            activity.startActivity(Intent.createChooser(send, null));
        } catch (RuntimeException e) {
            copy(activity, report);
        }
    }
}
