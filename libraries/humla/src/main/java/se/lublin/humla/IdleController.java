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

package se.lublin.humla;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import se.lublin.humla.net.HumlaConnection;

/**
 * Energy saving for a connection that stays open all day (walkie-talkie standby).
 * <p>
 * <b>Active</b> while someone talks and for {@link #IDLE_DELAY_MS} afterwards: the phone is kept
 * awake, pings every 5 s, voice over UDP. This is how it always worked.
 * <p>
 * <b>Idle</b> the rest of the time. Voice is tunneled over TCP (UDP NAT mappings of mobile networks
 * expire within a minute of silence) and only one keep-alive ping is sent every N seconds. The
 * server's timeout has to be longer than that (mumble-server.ini, {@code timeout=}, default 30).
 * <ul>
 * <li><b>Deep</b> (if the app is exempt from battery optimization): no wake lock at all, the phone
 * may sleep. Incoming data wakes it up (the network hardware does that); a short wake lock then
 * covers handling it. Keep-alives come from exact alarms, which Android only delivers in Doze
 * without throttling for exempt apps.</li>
 * <li><b>Light</b> (otherwise): the phone is kept awake as before, but the radio stays quiet.</li>
 * </ul>
 * A keep-alive that gets no answer within {@link #PONG_TIMEOUT_MS} means the connection is dead
 * (e.g. dropped by a NAT); it is reported as lost, so the service reconnects.
 * <p>
 * All methods are called on the main thread, except {@link #onDataArriving()}.
 */
public class IdleController {
    private static final String TAG = IdleController.class.getName();

    /** Stay active this long after the last talk activity before going idle. */
    private static final long IDLE_DELAY_MS = 30_000;
    /** A keep-alive ping not answered within this time means the connection is dead. */
    private static final long PONG_TIMEOUT_MS = 15_000;
    /** Wake lock after incoming data while deeply idle, refreshed by further data. */
    private static final long RX_WAKE_MS = 3_000;
    private static final long RX_WAKE_REFRESH_MS = 1_000;
    /** Wake lock for a connection attempt (TCP, TLS, sync). */
    private static final long CONNECT_WAKE_MS = 30_000;
    /** Keep-alive interval that also works with the default server timeout of 30 s. */
    public static final int SAFE_KEEPALIVE_SECONDS = 20;
    public static final int DEFAULT_KEEPALIVE_SECONDS = 120;

    private static final int STOPPED = 0;
    private static final int ACTIVE = 1;
    private static final int IDLE_LIGHT = 2;
    private static final int IDLE_DEEP = 3;

    public interface Host {
        /** @return true while someone (we or others) is talking. */
        boolean isTalkActivity();
        /** A scheduled reconnect is due. */
        void onReconnectDue();
    }

    private final Context mContext;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    /** Held while active and while lightly idle. */
    private final PowerManager.WakeLock mActiveLock;
    /** Timed: incoming data, keep-alive round trip, connection attempts. */
    private final PowerManager.WakeLock mShortLock;
    private final String mActionKeepAlive;
    private final String mActionReconnect;
    private final PendingIntent mKeepAliveIntent;
    private final PendingIntent mReconnectIntent;

    private boolean mEnabled = true;
    private int mKeepAliveSeconds = DEFAULT_KEEPALIVE_SECONDS;
    /** The server dropped us while idle, twice: use {@link #SAFE_KEEPALIVE_SECONDS}. */
    private boolean mKeepAliveFallback;
    private int mSuspectedServerTimeouts;

    private HumlaConnection mConnection;
    private volatile int mState = STOPPED;
    private volatile long mLastRxWake;
    /** Time of the first keep-alive of the current idle period, 0 if none sent yet. */
    private long mFirstKeepAliveTime;

    private final BroadcastReceiver mAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mActionKeepAlive.equals(intent.getAction())) {
                keepAlive();
            } else if (mActionReconnect.equals(intent.getAction())) {
                mShortLock.acquire(CONNECT_WAKE_MS);
                mHost.onReconnectDue();
            }
        }
    };

    private final Runnable mIdleCheck = new Runnable() {
        @Override
        public void run() {
            if (mState != ACTIVE || !mEnabled) return;
            if (mHost.isTalkActivity()) {
                scheduleIdleCheck();
                return;
            }
            goIdle();
        }
    };

    private final Runnable mKeepAliveTick = new Runnable() {
        @Override
        public void run() {
            keepAlive();
        }
    };

    private final Runnable mPongCheck = new Runnable() {
        @Override
        public void run() {
            HumlaConnection connection = mConnection;
            if (!isIdle() || connection == null) return;
            long ping = connection.getLastPingSentTime();
            long answer = connection.getLastTCPReceiveTime();
            if (ping > 0 && answer < ping) {
                Log.w(TAG, "keep-alive ping not answered, connection is dead");
                connection.reportConnectionLost("No answer to the keep-alive ping");
            }
        }
    };

    private final Runnable mReconnectTick = new Runnable() {
        @Override
        public void run() {
            mHost.onReconnectDue();
        }
    };

    public IdleController(Context context, Host host) {
        mContext = context;
        mHost = host;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mActiveLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:Active");
        mActiveLock.setReferenceCounted(false);
        mShortLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:Short");
        mShortLock.setReferenceCounted(false);

        String pkg = context.getPackageName();
        mActionKeepAlive = pkg + ".humla.KEEPALIVE";
        mActionReconnect = pkg + ".humla.RECONNECT";
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        mKeepAliveIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(mActionKeepAlive).setPackage(pkg), flags);
        mReconnectIntent = PendingIntent.getBroadcast(context, 1,
                new Intent(mActionReconnect).setPackage(pkg), flags);

        IntentFilter filter = new IntentFilter();
        filter.addAction(mActionKeepAlive);
        filter.addAction(mActionReconnect);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mAlarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(mAlarmReceiver, filter);
        }
    }

    /** Call when the service is destroyed. */
    public void release() {
        stop();
        cancelReconnect();
        try {
            mContext.unregisterReceiver(mAlarmReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "alarm receiver was not registered");
        }
        if (mShortLock.isHeld()) mShortLock.release();
    }

    /** Off: always active while connected, exactly as without energy saving. */
    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (mState == STOPPED) return;
        if (enabled) {
            scheduleIdleCheck();
        } else {
            goActive();
            mHandler.removeCallbacks(mIdleCheck);
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    /** Interval of the keep-alive pings while idle. The server's timeout must be longer. */
    public void setKeepAliveSeconds(int seconds) {
        seconds = Math.max(SAFE_KEEPALIVE_SECONDS, seconds);
        if (mKeepAliveSeconds == seconds) return;
        mKeepAliveSeconds = seconds;
        resetKeepAliveFallback();
        if (isIdle()) {
            scheduleNextKeepAlive(); // Replaces the pending one.
        }
    }

    /** Forget a server timeout detected earlier, e.g. when the user connects anew. */
    public void resetKeepAliveFallback() {
        mKeepAliveFallback = false;
        mSuspectedServerTimeouts = 0;
    }

    private long keepAliveMs() {
        int seconds = mKeepAliveFallback ? SAFE_KEEPALIVE_SECONDS : mKeepAliveSeconds;
        return seconds * 1000L;
    }

    /** @return the keep-alive interval in use, in seconds. */
    public int getEffectiveKeepAliveSeconds() {
        return (int) (keepAliveMs() / 1000);
    }

    /** A connection attempt starts: keep the phone awake for it. */
    public void onConnecting() {
        mShortLock.acquire(CONNECT_WAKE_MS);
    }

    /** The connection is synchronized with the server. Starts out active. */
    public void start(HumlaConnection connection) {
        mConnection = connection;
        mState = STOPPED; // so goActive() does not touch the (new, not idle) connection
        goActive();
    }

    /**
     * The connection ended.
     * @param error true if it was lost by an error (not a regular disconnect).
     * @return true if this showed that the server's timeout is shorter than the keep-alive
     * interval, so a shorter one is used from now on; the user should be told.
     */
    public boolean onDisconnected(boolean error) {
        boolean fallbackStarted = false;
        HumlaConnection connection = mConnection;
        if (error && isIdle() && connection != null) {
            boolean keepAliveAnswered = mFirstKeepAliveTime > 0
                    && connection.getLastTCPReceiveTime() >= mFirstKeepAliveTime;
            if (keepAliveAnswered) {
                mSuspectedServerTimeouts = 0;
            } else if (!mKeepAliveFallback && keepAliveMs() > SAFE_KEEPALIVE_SECONDS * 1000L) {
                // Dropped before the first keep-alive of this idle period was answered. Once is
                // chance (network), twice in a row means the server times us out.
                mSuspectedServerTimeouts++;
                if (mSuspectedServerTimeouts >= 2) {
                    Log.w(TAG, "server drops idle connections, keep-alive every "
                            + SAFE_KEEPALIVE_SECONDS + " s from now on");
                    mKeepAliveFallback = true;
                    fallbackStarted = true;
                }
            }
        }
        stop();
        // Give the service a moment to schedule the reconnect before the phone may sleep.
        mShortLock.acquire(2_000);
        return fallbackStarted;
    }

    private void stop() {
        mState = STOPPED;
        mConnection = null;
        mHandler.removeCallbacks(mIdleCheck);
        mHandler.removeCallbacks(mKeepAliveTick);
        mHandler.removeCallbacks(mPongCheck);
        mAlarmManager.cancel(mKeepAliveIntent);
        if (mActiveLock.isHeld()) mActiveLock.release();
    }

    /** Someone started or stopped talking (we or others): be active, and stay a while. */
    public void onActivity() {
        if (mState == STOPPED) return;
        if (mState != ACTIVE) {
            goActive();
        } else {
            scheduleIdleCheck();
        }
    }

    /** Called on a network thread when data arrives. */
    public void onDataArriving() {
        if (mState != IDLE_DEEP) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastRxWake < RX_WAKE_REFRESH_MS) return;
        mLastRxWake = now;
        mShortLock.acquire(RX_WAKE_MS);
    }

    /** Schedules a reconnect attempt that also happens while the phone sleeps. */
    public void scheduleReconnect(long delayMs) {
        cancelReconnect();
        if (canSleepDeeply()) {
            setExactAlarm(SystemClock.elapsedRealtime() + delayMs, mReconnectIntent);
        } else {
            mShortLock.acquire(delayMs + CONNECT_WAKE_MS);
            mHandler.postDelayed(mReconnectTick, delayMs);
        }
    }

    public void cancelReconnect() {
        mHandler.removeCallbacks(mReconnectTick);
        mAlarmManager.cancel(mReconnectIntent);
    }

    private boolean isIdle() {
        int state = mState;
        return state == IDLE_LIGHT || state == IDLE_DEEP;
    }

    private void goActive() {
        int old = mState;
        mState = ACTIVE;
        mActiveLock.acquire();
        mHandler.removeCallbacks(mKeepAliveTick);
        mHandler.removeCallbacks(mPongCheck);
        mAlarmManager.cancel(mKeepAliveIntent);
        if ((old == IDLE_LIGHT || old == IDLE_DEEP) && mConnection != null) {
            mConnection.setIdle(false);
            Log.i(TAG, "active");
        }
        if (mEnabled) {
            scheduleIdleCheck();
        }
    }

    private void goIdle() {
        if (mConnection == null) return;
        boolean deep = canSleepDeeply();
        mState = deep ? IDLE_DEEP : IDLE_LIGHT;
        mFirstKeepAliveTime = 0;
        mConnection.setIdle(true);
        scheduleNextKeepAlive();
        Log.i(TAG, (deep ? "idle (phone may sleep)" : "idle (phone kept awake)")
                + ", keep-alive every " + getEffectiveKeepAliveSeconds() + " s");
        if (deep) {
            mActiveLock.release();
        }
    }

    private void scheduleIdleCheck() {
        mHandler.removeCallbacks(mIdleCheck);
        mHandler.postDelayed(mIdleCheck, IDLE_DELAY_MS);
    }

    private void keepAlive() {
        HumlaConnection connection = mConnection;
        if (!isIdle() || connection == null) return;
        if (mState == IDLE_DEEP) {
            // Shortened again as soon as the answer arrives, see onDataArriving().
            mShortLock.acquire(PONG_TIMEOUT_MS + 2_000);
        }
        if (mFirstKeepAliveTime == 0) {
            mFirstKeepAliveTime = SystemClock.elapsedRealtime();
        }
        connection.sendPing();
        mHandler.removeCallbacks(mPongCheck);
        mHandler.postDelayed(mPongCheck, PONG_TIMEOUT_MS);
        scheduleNextKeepAlive();
    }

    private void scheduleNextKeepAlive() {
        mHandler.removeCallbacks(mKeepAliveTick);
        mAlarmManager.cancel(mKeepAliveIntent);
        if (mState == IDLE_DEEP) {
            if (!setExactAlarm(SystemClock.elapsedRealtime() + keepAliveMs(), mKeepAliveIntent)) {
                // Exact alarms are not allowed after all: keep the phone awake instead.
                mState = IDLE_LIGHT;
                mActiveLock.acquire();
                mHandler.postDelayed(mKeepAliveTick, keepAliveMs());
            }
        } else if (mState == IDLE_LIGHT) {
            mHandler.postDelayed(mKeepAliveTick, keepAliveMs());
        }
    }

    /** @return false if exact alarms may not be used. */
    private boolean setExactAlarm(long elapsedRealtime, PendingIntent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        elapsedRealtime, intent);
            } else {
                mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtime,
                        intent);
            }
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "exact alarms not allowed", e);
            return false;
        }
    }

    /**
     * The phone may only sleep if keep-alive alarms reliably wake it up in time: in Doze, Android
     * throttles such alarms to about one per 15 minutes, unless the app is exempt from battery
     * optimization. That exemption also allows exact alarms (Android 12+).
     */
    public boolean canSleepDeeply() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // No Doze.
        }
        if (!mPowerManager.isIgnoringBatteryOptimizations(mContext.getPackageName())) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mAlarmManager.canScheduleExactAlarms();
    }
}
