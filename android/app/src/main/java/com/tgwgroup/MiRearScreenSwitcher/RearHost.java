package com.tgwgroup.MiRearScreenSwitcher;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for putting experiences on the rear screen.
 * Services call show()/hide(); RearStack decides what is visible and RearHostActivity renders it.
 * Launch work is blocking, run it on the caller's thread (same as the old per-Activity launch code).
 */
public class RearHost {
    private static final String TAG = "RearHost";
    private static final String HOST_COMPONENT =
        "com.tgwgroup.MiRearScreenSwitcher/com.tgwgroup.MiRearScreenSwitcher.RearHostActivity";
    private static final String STOCK_LAUNCHER = "com.xiaomi.subscreencenter/.SubScreenLauncher";
    private static final String WAKE_CMD = "input -d 1 keyevent KEYCODE_WAKEUP";

    // Task of the app projected to the rear before we took the screen; restored on teardown. -1 = none
    private static int projectedTaskId = -1;
    // True when charging ended on its own (timeout / unplug / dropped by a notification); ChargingService reads it in one-shot mode
    private static volatile boolean chargingSelfFinished = false;
    private static PowerManager.WakeLock wakeLock;

    public static boolean isChargingSelfFinished() { return chargingSelfFinished; }
    public static void resetChargingSelfFinished() { chargingSelfFinished = false; }
    public static boolean isShowing() { return RearHostActivity.isVisible(); }
    public static long getShownSince() { return RearHostActivity.getShownSince(); }
    public static long getLastVisibleMs() { return RearHostActivity.getLastVisibleMs(); }

    public static ITaskService taskService() {
        ITaskService ts = NotificationService.getTaskService();
        return ts != null ? ts : ChargingService.getTaskService();
    }

    /** Show this experience; it becomes visible if it is the highest-priority entry. */
    public static void show(Context ctx, RearStack.Type type, Bundle payload) {
        synchronized (RearHost.class) {
            RearStack.put(type, payload);
            // One-shot charging is not resumed after a notification; only always-on charging comes back
            if (type == RearStack.Type.NOTIFICATION && RearStack.contains(RearStack.Type.CHARGING)
                    && !ctx.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
                        .getBoolean("charging_always_on_enabled", false)) {
                RearStack.remove(RearStack.Type.CHARGING);
                chargingSelfFinished = true;
            }
        }
        cancelHomeRecovery();
        Log.d(TAG, "show " + type + " host=" + RearHostActivity.getInstance()
            + " onRear=" + (RearHostActivity.getRearInstance() != null));
        if (type == RearStack.Type.CHARGING) chargingSelfFinished = false;

        ITaskService ts = taskService();
        RearHostActivity host = RearHostActivity.getRearInstance();
        boolean ok;
        if (host != null) {
            ok = true;
            if (type == RearStack.Type.NOTIFICATION) {
                exec(ts, WAKE_CMD);
                disableLauncher(ts);
            }
            if (RearHostActivity.isVisible()) {
                // Pager already front: update pages immediately.
                host.onFeatureChanged();
            } else {
                // Pager is alive but backgrounded (stock rear home on top).
                // Bring it front first; onResume will bind the current stack and start
                // animations only when the pager is actually visible.
                // Media: also refresh the music page now; a dim/sleeping rear may never resume the activity.
                if (type == RearStack.Type.MEDIA) host.refreshMusic();
                exec(ts, "am start -n " + HOST_COMPONENT);
            }
        } else {
            ok = launchHost(ctx, ts, type);
        }
        if (!ok) {
            Log.w(TAG, "Launch failed for " + type + "; dropping entry");
            hide(ctx, type);
        }
    }

    /** Remove an experience. The pager host stays alive and shows empty states. */
    public static void hide(Context ctx, RearStack.Type type) {
        if (type == RearStack.Type.CHARGING) chargingSelfFinished = true;
        boolean had = RearStack.contains(type);
        Log.d(TAG, "hide " + type + " had=" + had + " host=" + RearHostActivity.getInstance());
        if (!had) return; // nothing to remove: re-rendering would replay the top view and reset its timers
        RearStack.remove(type);
        RearHostActivity host = RearHostActivity.getInstance();
        if (host == null) {
            // Launch failed or host already gone: nothing will tear down, so restore here (only if we actually owned the rear)
            if (had && RearStack.isEmpty()) restoreRear(ctx);
            return;
        }
        // Notification timeout with nothing else running: hide the pager to stock rear home (pager stays alive).
        // When media or charging is still active the pager stays and just updates its pages.
        if (type == RearStack.Type.NOTIFICATION && RearStack.isEmpty()) {
            RearHostActivity rear = RearHostActivity.getRearInstance();
            if (rear != null) {
                goHome(ctx);
                return;
            }
        }
        // Keep the host as the idle rear home; feature removal only updates its pages.
        host.onFeatureChanged();
    }

    // Swipe-up home: dismiss MRSS to stock rear home; bring it back on an interval if a feature is still active.
    private static final long HOME_RECOVERY_DELAY_MS = 4000;
    private static Runnable pendingHomeRecovery;

    /**
     * Hide the pager behind the stock rear home without destroying it.
     * The task stays alive so media/notification/charging can bring it back instantly.
     */
    public static void goHome(Context ctx) {
        cancelHomeRecovery();
        restoreRear(ctx);
        scheduleHomeRecovery(ctx);
    }

    /**
     * If a feature is active, make the pager frontmost again.
     * - Pager alive: bring it front with a bare am start (no cold start).
     * - Pager gone (e.g. after display sleep removed it): cold-relaunch the top feature.
     */
    public static void bringPagerToFrontIfActive(Context ctx) {
        if (RearStack.isEmpty()) return;
        ITaskService ts = taskService();
        if (RearHostActivity.getRearInstance() != null) {
            if (ts != null) {
                exec(ts, "am start -n " + HOST_COMPONENT);
            }
            return;
        }
        RearStack.Type top = RearStack.top();
        if (top != null) {
            show(ctx, top, RearStack.get(top));
        }
    }

    /** Put the rear display to sleep (dim). Used when the user double-taps on a feature page. */
    public static void dimRearScreen() {
        ITaskService ts = taskService();
        if (ts != null) {
            exec(ts, "input -d 1 keyevent KEYCODE_SLEEP");
        }
    }

    private static boolean isMediaActive() {
        MediaController c = NotificationService.getActiveMediaController();
        PlaybackState ps = c == null ? null : c.getPlaybackState();
        if (ps == null) return false;
        int state = ps.getState();
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_NONE;
    }

    public static void cancelHomeRecovery() {
        if (pendingHomeRecovery != null) {
            recoveryHandler.removeCallbacks(pendingHomeRecovery);
            pendingHomeRecovery = null;
        }
    }

    private static void scheduleHomeRecovery(Context ctx) {
        cancelHomeRecovery();
        final Context app = ctx.getApplicationContext();
        pendingHomeRecovery = () -> homeRecover(app);
        recoveryHandler.postDelayed(pendingHomeRecovery, HOME_RECOVERY_DELAY_MS);
    }

    private static void homeRecover(Context ctx) {
        pendingHomeRecovery = null;
        if (RearHostActivity.isVisible()) return;
        boolean notiActive = RearStack.contains(RearStack.Type.NOTIFICATION);
        boolean chargingActive = RearStack.contains(RearStack.Type.CHARGING);
        boolean mediaActive = isMediaActive();
        RearStack.Type top;
        if (notiActive) top = RearStack.Type.NOTIFICATION;
        else if (chargingActive) top = RearStack.Type.CHARGING;
        else if (mediaActive) top = RearStack.Type.MEDIA;
        else return;
        show(ctx, top, RearStack.get(top));
    }

    // Media recovery after the user swipes media away or HyperOS removes it: only if it is still playing and still on top
    private static final long MEDIA_RECOVERY_DELAY_MS = 3000;
    private static final int MEDIA_RECOVERY_MAX = 3; // per MEDIA_RECOVERY_WINDOW_MS, so we never fight HyperOS endlessly
    private static final long MEDIA_RECOVERY_WINDOW_MS = 60000;
    private static final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingRecovery;
    private static int recoveryCount = 0;
    private static long recoveryWindowStart = 0;

    public static void scheduleMediaRecovery(Context ctx) {
        cancelMediaRecovery();
        final Context app = ctx.getApplicationContext();
        pendingRecovery = () -> recoverMedia(app);
        recoveryHandler.postDelayed(pendingRecovery, MEDIA_RECOVERY_DELAY_MS);
    }

    public static void cancelMediaRecovery() {
        if (pendingRecovery != null) {
            recoveryHandler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
    }

    private static void recoverMedia(Context ctx) {
        pendingRecovery = null;
        if (RearStack.top() != RearStack.Type.MEDIA || RearHostActivity.isVisible()) return;
        if (!isMediaActive()) return;
        long now = System.currentTimeMillis();
        if (now - recoveryWindowStart > MEDIA_RECOVERY_WINDOW_MS) {
            recoveryWindowStart = now;
            recoveryCount = 0;
        }
        if (++recoveryCount > MEDIA_RECOVERY_MAX) {
            Log.w(TAG, "Media recovery suspended (keeps getting removed)");
            return;
        }
        show(ctx, RearStack.Type.MEDIA, RearStack.get(RearStack.Type.MEDIA));
    }

    public static void updateBattery(int level) {
        Bundle b = RearStack.get(RearStack.Type.CHARGING);
        if (b != null) b.putInt("batteryLevel", level);
        RearHostActivity host = RearHostActivity.getRearInstance();
        if (host != null) host.updateBattery(level);
    }

    private static void exec(ITaskService ts, String cmd) {
        try {
            if (ts != null) ts.executeShellCommand(cmd);
        } catch (Throwable t) {
            Log.w(TAG, "shell failed: " + cmd + " " + t.getMessage());
        }
    }

    private static void disableLauncher(ITaskService ts) {
        try {
            if (ts != null) ts.disableSubScreenLauncher();
        } catch (Throwable t) {
            Log.w(TAG, "disableSubScreenLauncher failed: " + t.getMessage());
        }
    }

    private static boolean isRearOn(Context ctx) {
        android.view.Display d = ((android.hardware.display.DisplayManager)
            ctx.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(1);
        return d != null && d.getState() == android.view.Display.STATE_ON;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean waitOnRear(long timeoutMs) {
        for (long t = 0; t < timeoutMs; t += 50) {
            if (RearHostActivity.getRearInstance() != null) return true;
            sleep(50);
        }
        return RearHostActivity.getRearInstance() != null;
    }

    /** Foreground app task on the rear before we take it, so teardown can put it back. */
    private static void captureProjectedTask(ITaskService ts) {
        projectedTaskId = -1;
        try {
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            if (lastTask == null || !lastTask.contains(":")) return;
            String rearFg = ts.getForegroundAppOnDisplay(1);
            if (rearFg != null && rearFg.equals(lastTask)) {
                projectedTaskId = Integer.parseInt(lastTask.split(":")[1]);
                RearScreenKeeperService.pauseMonitoring();
            }
        } catch (Throwable t) {
            Log.w(TAG, "captureProjectedTask failed: " + t.getMessage());
        }
    }

    /** Cold start of the host on the rear. Returns true once the host is rendering on display 1. */
    private static boolean launchHost(Context ctx, ITaskService ts, RearStack.Type type) {
        if (ts == null) return false;
        acquireWakeLock(ctx, 8000);
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();

            if (RearHostActivity.getInstance() == null) captureProjectedTask(ts);
            if (type != RearStack.Type.MEDIA) disableLauncher(ts);

            // Window flags do not wake a dozing secondary display; only this command does.
            // Media refreshes deliberately do not wake the rear.
            if (type == RearStack.Type.NOTIFICATION) {
                exec(ts, WAKE_CMD);
                sleep(300);
            } else if (type == RearStack.Type.CHARGING) {
                boolean rearWasOn = isRearOn(ctx);
                exec(ts, WAKE_CMD);
                if (!rearWasOn) sleep(1500); // HyperOS removes our task if the wake completes after the launch
            }

            // Locked: HyperOS rejects --display 1; go straight to placeholder + move.
            // A leftover placeholder on the main display is reused by the bare start below.
            if (!locked && RearHostActivity.getInstance() == null) {
                exec(ts, "am start --display 1 -n " + HOST_COMPONENT);
                if (waitOnRear(600)) return true;
            }

            exec(ts, "am start -n " + HOST_COMPONENT);
            sleep(50);
            String taskId = null;
            for (int i = 0; i < 60 && taskId == null; i++) {
                sleep(40);
                String r = ts.executeShellCommandWithResult("am stack list | grep RearHostActivity");
                if (r != null) {
                    Matcher m = Pattern.compile("taskId=(\\d+)").matcher(r);
                    if (m.find()) taskId = m.group(1);
                }
            }
            if (taskId == null) {
                Log.e(TAG, "No host taskId found");
                return false;
            }
            exec(ts, "am display move-stack " + taskId + " 1");
            // A rear that is asleep (media never wakes it) does not resume the Activity until it lights up; the host then
            // initializes itself from RearStack, so a slow init is not a launch failure.
            if (!waitOnRear(1000)) Log.d(TAG, "Host moved but not initialized yet (rear asleep?)");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "launchHost failed", t);
            return false;
        } finally {
            releaseWakeLock();
        }
    }

    /** Called by the host once its task is gone and the stack is empty. Puts back the projected app or the stock launcher. */
    public static void restoreRear(Context ctx) {
        final int taskId = projectedTaskId;
        projectedTaskId = -1;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ITaskService ts = taskService();
                if (ts == null) {
                    Log.w(TAG, "No TaskService for restore");
                    return;
                }
                if (taskId > 0) {
                    ts.disableSubScreenLauncher();
                    sleep(200);
                    ts.executeShellCommand("am display move-stack " + taskId + " 1");
                    sleep(200);
                    ts.executeShellCommand("am display move-stack " + taskId + " 1");
                    sleep(300);
                    restartKeeper(app);
                } else {
                    ts.executeShellCommand("am start --display 1 -n " + STOCK_LAUNCHER);
                }
            } catch (Throwable t) {
                Log.e(TAG, "restoreRear failed", t);
            } finally {
                RearScreenKeeperService.resumeMonitoring();
            }
        }).start();
    }

    private static void restartKeeper(Context ctx) {
        String lastTask = SwitchToRearTileService.getLastMovedTask();
        if (lastTask == null) return;
        Intent i = new Intent(ctx, RearScreenKeeperService.class);
        i.putExtra("lastMovedTask", lastTask);
        boolean keepOn = ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getBoolean("flutter.keep_screen_on_enabled", true);
        i.putExtra("keepScreenOnEnabled", keepOn);
        ctx.startService(i);
    }

    private static synchronized void acquireWakeLock(Context ctx, long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:RearHostWake");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "wakelock acquire failed: " + t.getMessage());
        }
    }

    private static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable t) {
            Log.w(TAG, "wakelock release failed: " + t.getMessage());
        }
    }
}
