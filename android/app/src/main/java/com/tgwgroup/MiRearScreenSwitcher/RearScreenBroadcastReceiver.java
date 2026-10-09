/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Listens for Xiaomi rear-screen state broadcasts.
 * When the rear screen turns on/off, automatically restores the always-on Activity so the system Launcher doesn't cover it.
 */
public class RearScreenBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "RearScreenReceiver";

    // Last cast app info
    private static String lastMovedPackage = null;
    private static int lastTaskId = -1;
    private static boolean rearScreenActive = false;

    /**
     * Save the last cast app info.
     * Called by TaskService.
     */
    public static void saveLastTask(String packageName, int taskId) {
        lastMovedPackage = packageName;
        lastTaskId = taskId;
        rearScreenActive = true;
    }

    /**
     * Clear the saved task info.
     */
    public static void clearLastTask() {
        lastMovedPackage = null;
        lastTaskId = -1;
        rearScreenActive = false;
    }

    /**
     * Whether there are active rear-screen tasks.
     */
    public static boolean hasActiveTask() {
        return rearScreenActive && lastMovedPackage != null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long timestamp = System.currentTimeMillis();
        if (hasActiveTask()) {
        }
        if ("miui.intent.action.SUB_SCREEN_ON".equals(action)) {
            // Rear screen turned on
            handleScreenOn(context);
        } else if ("miui.intent.action.SUB_SCREEN_OFF".equals(action)) {
            // Rear screen turned off
            handleScreenOff(context);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            // System screen off (could be double-tap to sleep)
            handleSystemScreenOff(context);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            // System screen on
            handleSystemScreenOn(context);
        }
    }

    /**
     * Handle the rear screen turning on.
     * Tries to restore the previous always-on Activity and cast app.
     */
    private void handleScreenOn(Context context) {
        if (hasActiveTask()) {
            // Activity mechanism removed - rely entirely on the Service
            // The Activity transparent window interferes with touch events while locked, making swipes stick
            // No restore broadcast needed; the Service keeps the Launcher disabled
        } else {
        }
    }

    /**
     * Handle the rear screen turning off.
     */
    private void handleScreenOff(Context context) {
        // When the rear screen turns off, keep the task info so it can be restored on next wake
        if (hasActiveTask()) {
        } else {
        }
    }

    /**
     * Handle system screen off (double-tap to sleep, etc).
     */
    private void handleSystemScreenOff(Context context) {
        if (hasActiveTask()) {
            // Ensure the Service is still running
            if (!RearScreenKeeperService.isRunning()) {
                Intent serviceIntent = new Intent(context, RearScreenKeeperService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
            }
        }
    }

    /**
     * Handle system screen on.
     */
    private void handleSystemScreenOn(Context context) {
        if (hasActiveTask()) {
            // Ensure the Service is still running
            if (!RearScreenKeeperService.isRunning()) {
                Intent serviceIntent = new Intent(context, RearScreenKeeperService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}

