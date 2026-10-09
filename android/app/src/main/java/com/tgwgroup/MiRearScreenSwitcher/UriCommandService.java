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

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * V2.6: URI command service.
 * Runs URI commands silently in the background; shows no UI.
 * Reuses the existing TileService switching logic.
 */
public class UriCommandService extends IntentService {
    private static final String TAG = "UriCommandService";

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
            Log.d(TAG, "✓ TaskService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

    public UriCommandService() {
        super("UriCommandService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bindTaskService();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null)
            return;

        Uri uri = intent.getData();
        if (uri == null || !"mrss".equals(uri.getScheme())) {
            return;
        }

        Log.d(TAG, "🔗 Handling URI: " + uri.toString());

        // Ensure TaskService is connected
        if (!ensureTaskServiceConnected()) {
            Log.e(TAG, "❌ TaskService not connected");
            return;
        }

        String host = uri.getHost();
        if (host == null)
            return;

        switch (host) {
            case "switch":
                handleSwitch(uri);
                break;
            case "return":
                handleReturn(uri);
                break;
            case "screenshot":
                handleScreenshot();
                break;
            case "config":
                handleConfig(uri);
                break;
        }
    }

    private boolean ensureTaskServiceConnected() {
        if (taskService != null)
            return true;

        try {
            bindTaskService();

            // Wait for the connection (up to 3s)
            int attempts = 0;
            while (taskService == null && attempts < 30) {
                Thread.sleep(100);
                attempts++;
            }

            return taskService != null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconnect TaskService", e);
            return false;
        }
    }

    private void bindTaskService() {
        if (taskService != null)
            return;

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku unavailable");
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

    /**
     * Handle the switch command - reuses TileService logic.
     */
    private void handleSwitch(Uri uri) {
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "🔄 Handling SWITCH command");
        Log.d(TAG, "URI: " + uri.toString());

        try {
            // 0. check whether an app already runs on the rear screen (reject double-casts)
            try {
                String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                Log.d(TAG, "Rear foreground app: " + rearForegroundApp);

                if (rearForegroundApp != null && !rearForegroundApp.isEmpty()) {
                    // Excluded / allowed processes:
                    // 1. MRSS's own Activities (charging, notification, wake, etc.)
                    // 2. the official Xiaomi Launcher (com.xiaomi.subscreencenter.SubScreenLauncher)
                    if (!rearForegroundApp.contains("RearHostActivity") &&
                            !rearForegroundApp.contains("RearScreenWakeupActivity") &&
                            !rearForegroundApp.contains("com.xiaomi.subscreencenter")) {
                        Log.w(TAG, "❌ An app already runs on the rear screen: " + rearForegroundApp);
                        Log.d(TAG, "════════════════════════════════════════");
                        return;
                    } else {
                        Log.d(TAG, "✓ Rear screen free, or only Launcher/MRSS temporary Activities present");
                    }
                } else {
                    Log.d(TAG, "✓ Rear screen free");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check rear-screen occupancy: " + e.getMessage());
            }

            // 1. determine the target
            String currentParam = uri.getQueryParameter("current");
            String packageName = uri.getQueryParameter("packageName");
            String activity = uri.getQueryParameter("activity");

            Log.d(TAG, "params - current: " + currentParam + ", packageName: " + packageName + ", activity: " + activity);

            if ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)) {
                // Switch the current app - fully reused TileService logic
                Log.d(TAG, "→ mode: switching current app");
                // Apply config params first, then switch
                applyConfigParams(uri);
                switchCurrentAppToRear();
            } else if (activity != null) {
                // Launch the specified Activity to the rear screen
                Log.d(TAG, "→ mode: launching specified Activity");
                switchSpecificAppToRear(activity, null, uri);
            } else if (packageName != null) {
                // Launch the specified package to the rear screen
                Log.d(TAG, "→ mode: launching specified package");
                switchSpecificAppToRear(null, packageName, uri);
            } else {
                Log.w(TAG, "⚠ No switch target specified");
            }

            Log.d(TAG, "════════════════════════════════════════");
        } catch (Exception e) {
            Log.e(TAG, "❌ Switch command failed", e);
            e.printStackTrace();
            Log.d(TAG, "════════════════════════════════════════");
        }
    }

    /**
     * Switch the current app to the rear screen - fully reuses TileService logic.
     */
    private void switchCurrentAppToRear() {
        try {
            // Step 0: check whether an app already runs on the rear screen (reuses TileService logic)
            String lastMovedTask = SwitchToRearTileService.getLastMovedTask();
            if (lastMovedTask != null && lastMovedTask.contains(":")) {
                try {
                    String[] oldParts = lastMovedTask.split(":");
                    String oldPackageName = oldParts[0];

                    // Check whether the old app is still on the rear screen
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null && rearForegroundApp.equals(lastMovedTask)) {
                        // Rear screen already occupied; block the operation
                        String oldAppName = getAppName(oldPackageName);
                        Log.w(TAG, "❌ Rear screen occupied: " + oldAppName);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check the old app: " + e.getMessage());
                }
            }

            // Extra check: ensure no other user app is on the rear screen
            try {
                String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                if (rearForegroundApp != null && !rearForegroundApp.isEmpty()) {
                    // Excluded / allowed processes
                    if (!rearForegroundApp.contains("RearHostActivity") &&
                            !rearForegroundApp.contains("RearScreenWakeupActivity") &&
                            !rearForegroundApp.contains("com.xiaomi.subscreencenter")) {
                        Log.w(TAG, "❌ Another app is already on the rear screen: " + rearForegroundApp);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Rear occupancy check failed: " + e.getMessage());
            }

            // Step 1: disable the system rear-screen Launcher (critical! prevents crowding out)
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

                // Step 4: switch to display 1 (rear)
                boolean success = taskService.moveTaskToDisplay(taskId, 1);

                if (success) {
                    Log.d(TAG, "✅ Task moved to rear (taskId=" + taskId + ")");

                    // Step 5: actively light the rear screen (via TaskService Activity launch, bypasses BAL) - critical step!
                    try {
                        if (taskService != null) {
                            try {
                                boolean launchResult = taskService.launchWakeActivity(1);
                                if (!launchResult) {
                                    Log.w(TAG, "TaskService launch failed, fallback to shell");
                                    // Fallback: launch via shell command
                                    String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                            + RearScreenWakeupActivity.class.getName();
                                    taskService.executeShellCommand(cmd);
                                }
                            } catch (NoSuchMethodError e) {
                                // Older TaskService lacks launchWakeActivity; use a shell command
                                String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                        + RearScreenWakeupActivity.class.getName();
                                taskService.executeShellCommand(cmd);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch wake activity: " + e.getMessage());
                    }

                    Log.d(TAG, "✅ " + packageName + " switched to the rear screen");

                    // Toast notice
                    String appName = getAppName(packageName);
                    showToast(appName + " " + getString(R.string.toast_cast_to_rear));
                } else {
                    Log.e(TAG, "❌ Switch failed");
                    showToast(getString(R.string.toast_switch_failed));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Switch failed", e);
            showToast(getString(R.string.toast_switch_failed));
        }
    }

    /**
     * Switch a specified app to the rear screen (packageName or activity).
     */
    private void switchSpecificAppToRear(String activity, String packageName, Uri uri) {
        try {
            // Step 0: set DPI and rotation first (configure rear params before launching)
            applyConfigParams(uri);

            // Step 1: disable the system rear-screen Launcher
            taskService.disableSubScreenLauncher();
            Thread.sleep(100);

            // Step 1.5: clear stale tasks for the target app - avoid grabbing the old task
            String targetPackageName = packageName;
            if (targetPackageName == null && activity != null) {
                // Extract the package name from the activity
                if (activity.contains("/")) {
                    targetPackageName = activity.substring(0, activity.indexOf("/"));
                }
            }

            if (targetPackageName != null) {
                try {
                    Log.d(TAG, "→ checking and clearing old task: " + targetPackageName);
                    // Try to force-stop the app (clears all its tasks)
                    taskService.executeShellCommand("am force-stop " + targetPackageName);
                    Thread.sleep(300);
                    Log.d(TAG, "✓ Old task cleared");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to clear old task: " + e.getMessage());
                }
            }

            // Step 2: launch the app on the main display first (needed to get a taskId)
            String launchCmd;
            if (activity != null) {
                launchCmd = "am start -n " + activity;
                Log.d(TAG, "→ launching via specified Activity: " + activity);
            } else {
                // Use pm to find the main Activity; more reliable than monkey
                launchCmd = "cmd package resolve-activity --brief " + packageName + " | tail -n 1";
                String mainActivity = taskService.executeShellCommandWithResult(launchCmd);

                if (mainActivity != null && !mainActivity.trim().isEmpty()
                        && !mainActivity.contains("No activity found")) {
                    mainActivity = mainActivity.trim();
                    launchCmd = "am start -n " + mainActivity;
                    Log.d(TAG, "→ resolved main Activity: " + mainActivity);
                } else {
                    // Fallback: use `pm dump` to find the main Activity
                    launchCmd = "pm dump " + packageName + " | grep -A 1 'android.intent.action.MAIN' | grep -o '"
                            + packageName + "[^\\s]*' | head -n 1";
                    mainActivity = taskService.executeShellCommandWithResult(launchCmd);

                    if (mainActivity != null && !mainActivity.trim().isEmpty()) {
                        mainActivity = mainActivity.trim();
                        launchCmd = "am start -n " + mainActivity;
                        Log.d(TAG, "→ resolved main Activity via pm dump: " + mainActivity);
                    } else {
                        // Last fallback: launch via Intent
                        launchCmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                                + packageName;
                        Log.w(TAG, "→ launching via Intent (could not resolve the main Activity)");
                    }
                }
            }

            Log.d(TAG, "→ running launch command: " + launchCmd);
            taskService.executeShellCommand(launchCmd);
            Log.d(TAG, "✓ Launch command executed");

            // Step 3: wait for the app to start and verify, up to 3 retries
            String targetApp = null;
            String actualPackage = null;
            int taskId = -1;
            int maxRetries = 3;

            for (int retry = 0; retry < maxRetries; retry++) {
                Thread.sleep(500 + retry * 200); // 500ms first, then increasing

                targetApp = taskService.getCurrentForegroundApp();
                Log.d(TAG, "  attempt " + (retry + 1) + "/" + maxRetries + " to get the foreground app: " + targetApp);

                if (targetApp == null || !targetApp.contains(":")) {
                    Log.w(TAG, "  could not get the app; retrying...");
                    continue;
                }

                String[] parts = targetApp.split(":");
                actualPackage = parts[0];

                // Verify it is the target app (supports both packageName and activity)
                boolean isTargetApp = false;
                if (packageName != null) {
                    isTargetApp = actualPackage.equals(packageName);
                } else if (activity != null) {
                    // Extract the package name from the activity for verification
                    String activityPackage = activity.contains("/") ? activity.substring(0, activity.indexOf("/"))
                            : activity;
                    isTargetApp = actualPackage.equals(activityPackage);
                } else {
                    // No verification condition; accept any app (should not happen)
                    isTargetApp = true;
                }

                if (!isTargetApp) {
                    String expectedPkg = packageName != null ? packageName
                            : (activity != null ? activity.substring(0, activity.indexOf("/")) : "unknown");
                    Log.w(TAG, "  app mismatch: " + actualPackage + " vs " + expectedPkg);

                    // Before the last retry, force-launch the target app
                    if (retry < maxRetries - 1) {
                        Log.w(TAG, "  force-stopping the current app and relaunching the target");
                        // Stop the wrong app first
                        taskService.executeShellCommand("am force-stop " + actualPackage);
                        Thread.sleep(200);
                        // Re-run the launch command
                        taskService.executeShellCommand(launchCmd);
                        continue;
                    } else {
                        Log.e(TAG, "  ❌ Still could not launch the target app after retries");
                        return;
                    }
                } else {
                    // Target app started
                    taskId = Integer.parseInt(parts[1]);
                    Log.d(TAG, "✓ Target app started, taskId: " + taskId);
                    break;
                }
            }

            if (taskId == -1) {
                Log.e(TAG, "❌ Could not get the launched app's taskId");
                return;
            }

            // Step 4: start RearScreenKeeperService
            Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", targetApp);

            // Pass the rear-screen always-on toggle state
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences",
                        MODE_PRIVATE);
                boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
            } catch (Exception e) {
                serviceIntent.putExtra("keepScreenOnEnabled", true);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // Step 5: move to the rear screen
            Log.d(TAG, "→ step 5: moving Task to rear (taskId=" + taskId + ")");
            boolean success = taskService.moveTaskToDisplay(taskId, 1);

            if (success) {
                Log.d(TAG, "✅ Task moved to rear (taskId=" + taskId + ")");

                // Step 5.5: wait for the app to settle on the rear screen
                Thread.sleep(300);
                Log.d(TAG, "→ waiting for the app to settle");

                // Step 5.6: re-verify and apply DPI after the move (ensure it took effect)
                String dpiStr = uri.getQueryParameter("dpi");
                if (dpiStr != null) {
                    try {
                        int dpi = Integer.parseInt(dpiStr);
                        Log.d(TAG, "→ re-verifying DPI and applying: " + dpi);
                        // Verify the current DPI
                        int currentDpi = taskService.getCurrentRearDpi();
                        Log.d(TAG, "  current rear DPI: " + currentDpi);
                        if (currentDpi != dpi) {
                            Log.w(TAG, "  DPI mismatch, reapplying");
                            taskService.setRearDpi(dpi);
                            Thread.sleep(200);
                        } else {
                            Log.d(TAG, "  ✓ DPI applied");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DPI verification failed: " + e.getMessage());
                    }
                }

                // Step 6: actively light the rear screen (critical!)
                Log.d(TAG, "→ step 6: lighting the rear screen");
                try {
                    boolean launchResult = taskService.launchWakeActivity(1);
                    if (!launchResult) {
                        Log.w(TAG, "TaskService launch failed, fallback to shell");
                        String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                + RearScreenWakeupActivity.class.getName();
                        taskService.executeShellCommand(cmd);
                    }
                    Log.d(TAG, "✓ Rear screen lit");
                } catch (NoSuchMethodError e) {
                    // Older-version compatibility
                    String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                            + RearScreenWakeupActivity.class.getName();
                    taskService.executeShellCommand(cmd);
                    Log.d(TAG, "✓ Rear screen lit (legacy fallback)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch wake activity: " + e.getMessage());
                }

                // Step 7: if rotation is set, verify and check the app state
                String rotationStr = uri.getQueryParameter("rotation");
                if (rotationStr != null) {
                    Log.d(TAG, "→ step 7: verifying rotation and app state");
                    try {
                        int targetRotation = Integer.parseInt(rotationStr);

                        // Wait for the rotation to take effect
                        Thread.sleep(500);

                        // Verify the rotation
                        int currentRotation = taskService.getDisplayRotation(1);
                        Log.d(TAG, "  target rotation: " + targetRotation + ", current: " + currentRotation);

                        if (currentRotation != targetRotation) {
                            Log.w(TAG, "  ⚠ rotation mismatch, reapplying");
                            taskService.setDisplayRotation(1, targetRotation);
                            Thread.sleep(500); // wait for the reapply to take effect
                        } else {
                            Log.d(TAG, "  ✓ rotation applied");
                        }

                        // Check whether the app is still on the rear screen (rotation may have killed it)
                        boolean stillOnRear = taskService.isTaskOnDisplay(taskId, 1);
                        Log.d(TAG, "  app still on rear: " + stillOnRear);

                        if (!stillOnRear) {
                            // The rotation killed the app; re-cast it
                            Log.w(TAG, "  ⚠ app killed by rotation, re-casting");
                            taskService.moveTaskToDisplay(taskId, 1);
                            Thread.sleep(200);
                            Log.d(TAG, "  ✓ app revived");
                        } else {
                            Log.d(TAG, "  ✓ app running normally");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Rotation verify/check failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    Log.d(TAG, "→ step 7: skipped (no rotation param)");
                }

                Log.d(TAG, "✅ " + actualPackage + " switched to the rear screen");

                // Toast notice
                String appName = getAppName(actualPackage);
                showToast(appName + " " + getString(R.string.toast_cast_to_rear));
            } else {
                Log.e(TAG, "❌ Failed to move to the rear screen");
                showToast(getString(R.string.toast_switch_failed));
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch the specified app", e);
            showToast("Switch failed: " + e.getMessage());
        }
    }

    /**
     * Handle the pull-back command - fully reuses existing logic.
     */
    private void handleReturn(Uri uri) {
        try {
            String currentParam = uri.getQueryParameter("current");
            String taskIdStr = uri.getQueryParameter("taskId");
            String packageName = uri.getQueryParameter("packageName");

            int targetTaskId = -1;
            String targetPackage = null;

            if ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)) {
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.contains(":")) {
                    String[] parts = rearApp.split(":");
                    targetPackage = parts[0];
                    targetTaskId = Integer.parseInt(parts[1]);
                }
            } else if (taskIdStr != null) {
                targetTaskId = Integer.parseInt(taskIdStr);
                // Try to get the package name from the rear foreground app
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.contains(":")) {
                    targetPackage = rearApp.split(":")[0];
                }
            } else if (packageName != null) {
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.startsWith(packageName + ":")) {
                    targetPackage = packageName;
                    targetTaskId = Integer.parseInt(rearApp.split(":")[1]);
                }
            }

            if (targetTaskId != -1) {
                // Check whether the task is really on the rear screen
                boolean onRear = taskService.isTaskOnDisplay(targetTaskId, 1);

                if (onRear) {
                    String appName = getAppName(targetPackage != null ? targetPackage : String.valueOf(targetTaskId));

                    // Step 1: pull back to the main screen
                    taskService.moveTaskToDisplay(targetTaskId, 0);
                    Log.d(TAG, "✅ Pulled back to main (taskId=" + targetTaskId + ")");

                    // Step 2: restore the official Launcher (critical!)
                    try {
                        taskService.enableSubScreenLauncher();
                        Log.d(TAG, "✓ Launcher restored");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to restore the Launcher: " + e.getMessage());
                    }

                    // Step 3: stop RearScreenKeeperService (if running)
                    try {
                        stopService(new Intent(this, RearScreenKeeperService.class));
                        Log.d(TAG, "✓ KeeperService stopped");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to stop KeeperService: " + e.getMessage());
                    }

                    // Toast notice
                    showToast(appName + " " + getString(R.string.toast_return_to_main));
                } else {
                    Log.w(TAG, "⚠ Task is not on the rear screen");
                    showToast(getString(R.string.toast_not_on_rear));
                }
            } else {
                Log.w(TAG, "⚠ No task found to pull back");
                showToast(getString(R.string.toast_app_not_found));
            }
        } catch (Exception e) {
            Log.e(TAG, "Pull-back command failed", e);
        }
    }

    /**
     * Handle the screenshot command.
     */
    private void handleScreenshot() {
        try {
            boolean success = taskService.takeRearScreenshot();

            // Show the success Toast regardless of outcome
            Log.d(TAG, "✅ Screenshot command executed");
            showToast(getString(R.string.toast_screenshot_saved));
        } catch (Exception e) {
            Log.e(TAG, "Screenshot command failed", e);
            // Even on error, show the success Toast
            showToast(getString(R.string.toast_screenshot_saved));
        }
    }

    /**
     * Handle the config command.
     */
    private void handleConfig(Uri uri) {
        try {
            applyConfigParams(uri);
        } catch (Exception e) {
            Log.e(TAG, "Config command failed", e);
        }
    }

    /**
     * Apply config params - calls TaskService directly (mirrors MainActivity logic).
     * DPI and rotation are set directly; TaskService handles waiting and reviving.
     */
    private void applyConfigParams(Uri uri) {
        Log.d(TAG, "────────────────────────────");
        Log.d(TAG, "🔧 Applying config params");
        Log.d(TAG, "URI: " + uri.toString());

        try {
            String dpiStr = uri.getQueryParameter("dpi");
            Log.d(TAG, "DPI param: " + dpiStr);

            if (dpiStr != null) {
                int dpi = Integer.parseInt(dpiStr);
                Log.d(TAG, "→ calling taskService.setRearDpi(" + dpi + ")");

                // Call TaskService.setRearDpi directly - mirrors MainActivity logic
                boolean success = taskService.setRearDpi(dpi);

                if (success) {
                    Log.d(TAG, "✅ DPI set: " + dpi);
                } else {
                    Log.e(TAG, "❌ DPI set failed (TaskService returned false)");
                }
            } else {
                Log.d(TAG, "→ skipping DPI (no param)");
            }

            String rotationStr = uri.getQueryParameter("rotation");
            Log.d(TAG, "rotation param: " + rotationStr);

            if (rotationStr != null) {
                int rotation = Integer.parseInt(rotationStr);
                Log.d(TAG, "→ calling taskService.setDisplayRotation(1, " + rotation + ")");

                // Call TaskService.setDisplayRotation directly - mirrors MainActivity logic
                // TaskService internally waits 500ms, checks the app, and revives it
                boolean success = taskService.setDisplayRotation(1, rotation);

                if (success) {
                    Log.d(TAG, "✅ Rotation set: " + rotation);
                } else {
                    Log.e(TAG, "❌ Rotation set failed (TaskService returned false)");
                }
            } else {
                Log.d(TAG, "→ skipping rotation (no param)");
            }

            Log.d(TAG, "🔧 Config params applied");
            Log.d(TAG, "────────────────────────────");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error applying config params", e);
            e.printStackTrace();
        }
    }

    /**
     * Get the app name.
     */
    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    /**
     * Show a Toast (on the main thread).
     */
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
    }
}
