/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 * 
 * Chief Tester: 汐木泽 (Xi Mu Ze)
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * Quick Settings Tile - switch to the rear screen
 * Moves the current foreground app to the rear screen on click.
 */
public class SwitchToRearTileService extends TileService {
    private static final String TAG = "SwitchToRearTile";

    // Static: last task moved to the rear screen (used by the proximity sensor to restore)
    private static String lastMovedTask = null; // format: "packageName:taskId"

    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            scheduleReconnectTaskService();
        }
    };

    /**
     * TaskService reconnect task.
     */
    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                bindTaskService();
                // If still disconnected, try again in 1s
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000);
            }
        }
    };

    /**
     * Schedule a TaskService reconnect.
     */
    private void scheduleReconnectTaskService() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reconnectTaskServiceRunnable, 200);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        bindTaskService();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        unbindTaskService();
    }

    /**
     * Static helper: restore a task to the rear screen.
     * Called by RearScreenBroadcastReceiver.
     */
    public static void restoreTaskToRearDisplay(int taskId) {
        // Left empty; the broadcast receiver actually triggers the restore by launching the Activity
        // The Activity applies FLAG_KEEP_SCREEN_ON automatically
    }

    /**
     * Get the last task moved to the rear screen.
     * 
     * @return "packageName:taskId" format, or null if none
     */
    public static String getLastMovedTask() {
        return lastMovedTask;
    }

    public static void setLastMovedTask(String taskInfo) {
        lastMovedTask = taskInfo;
    }

    @Override
    public void onClick() {
        super.onClick();
        switchCurrentAppToRearDisplay();
    }

    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return;
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No Shizuku permission");
                return;
            }

            Shizuku.bindUserService(serviceArgs, taskServiceConnection);

        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    private void unbindTaskService() {
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding TaskService", e);
            }
            taskService = null;
        }
    }

    private void switchCurrentAppToRearDisplay() {
        if (taskService == null) {
            Log.w(TAG, "TaskService not available!");
            showTemporaryFeedback("服务未就绪");

            // Try to rebind
            bindTaskService();

            // Retry after a delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (taskService != null) {
                    performSwitch();
                } else {
                    showTemporaryFeedback("请先打开应用授权");
                }
            }, 1000);
            return;
        }

        performSwitch();
    }

    private void performSwitch() {
        // Show in-progress state - keep the button look, change only the subtitle
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE); // keep the tile off
            tile.setSubtitle("切换中...");
            // Do not show "enabled"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        try {
            // Step 0: check whether an app is already running on the rear screen
            if (lastMovedTask != null && lastMovedTask.contains(":")) {
                try {
                    String[] oldParts = lastMovedTask.split(":");
                    String oldPackageName = oldParts[0];
                    int oldTaskId = Integer.parseInt(oldParts[1]);

                    // Check whether the old app is still on the rear screen
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null && rearForegroundApp.equals(lastMovedTask)) {
                        // Rear screen already occupied; block the operation
                        String oldAppName = getAppName(oldPackageName);

                        // Collapse the control center first so the Toast can show
                        try {
                            taskService.collapseStatusBar();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to collapse for toast: " + e.getMessage());
                        }

                        // Delay the Toast to ensure the control center is collapsed
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(this, getString(R.string.toast_please_switch_back, oldAppName),
                                    Toast.LENGTH_LONG).show();
                        }, 300);

                        showTemporaryFeedback("✗ 背屏已占用");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check previous app: " + e.getMessage());
                }
            }

            // Step 1: disable the system rear-screen Launcher (critical! prevents it crowding out)
            try {
                taskService.disableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "Failed to disable SubScreenLauncher", e);
            }

            // Step 2: get the current foreground app
            String currentApp = taskService.getCurrentForegroundApp();

            // Step 3: start the foreground Service immediately so the notification appears fast
            Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", currentApp);

            // V2.5: pass the rear-screen always-on toggle state
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences",
                        MODE_PRIVATE);
                boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
            } catch (Exception e) {
                // Default: on
                serviceIntent.putExtra("keepScreenOnEnabled", true);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            if (currentApp != null && currentApp.contains(":")) {
                String[] parts = currentApp.split(":");
                String packageName = parts[0];
                int taskId = Integer.parseInt(parts[1]);

                // Get the app name
                String appName = getAppName(packageName);

                // Step 4: switch to display 1 (rear screen)
                boolean success = taskService.moveTaskToDisplay(taskId, 1);

                if (success) {
                    // Save the last moved task (for proximity-sensor restore)
                    lastMovedTask = currentApp;

                    // Auto-collapse the control center (better UX)
                    try {
                        new Thread(() -> {
                            try {
                                if (taskService != null) {
                                    taskService.collapseStatusBar();
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to collapse: " + e.getMessage());
                            }
                        }).start();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to start collapse thread: " + e.getMessage());
                    }

                    // Delay the Toast to ensure the control center is collapsed
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " " + getString(R.string.toast_cast_to_rear), Toast.LENGTH_SHORT)
                                .show();
                    }, 300);

                    // Step 5: actively light the rear screen (via TaskService launching the Activity, bypasses BAL limits)
                    try {
                        if (taskService != null) {
                            try {
                                boolean launchResult = taskService.launchWakeActivity(1);
                                if (!launchResult) {
                                    Log.w(TAG, "TaskService launch failed");
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "launchWakeActivity exception: " + e.getMessage());
                            }
                        } else {
                            Log.w(TAG, "TaskService not available");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch wakeup activity", e);
                    }

                    showTemporaryFeedback("✓ 已切换");
                } else {
                    // First collapse the control center
                    try {
                        taskService.collapseStatusBar();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to collapse: " + e.getMessage());
                    }

                    // Delay the Toast
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, getString(R.string.toast_switch_failed), Toast.LENGTH_SHORT).show();
                    }, 300);

                    showTemporaryFeedback("✗ 失败");
                }
            } else {
                Log.w(TAG, "No foreground app found");
                showTemporaryFeedback("✗ 未找到应用");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error switching app", e);
            showTemporaryFeedback("✗ 操作失败");
        }
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(message);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Tile resetTile = getQsTile();
            if (resetTile != null) {
                resetTile.setState(Tile.STATE_INACTIVE);
                resetTile.setSubtitle(null);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    resetTile.setStateDescription("");
                }
                resetTile.updateTile();
            }
        }, 1500);
    }

    /**
     * Get the app name.
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get app name: " + e.getMessage());
        }
        return packageName; // fall back to the package name on failure
    }
}
