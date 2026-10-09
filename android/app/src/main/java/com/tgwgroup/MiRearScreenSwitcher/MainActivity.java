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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import rikka.shizuku.Shizuku;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.display.switcher/task";
    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    
    // Static instance, accessible from other classes
    private static MainActivity currentInstance;
    
    public static MainActivity getCurrentInstance() {
        return currentInstance;
    }
    
    private ITaskService taskService;
    private MethodChannel methodChannel;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);
    
    // Shizuku listener (critical!)
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            taskService = null;
            
            // Start the reconnect task
            scheduleReconnectTaskService();
        };
    
    /**
     * TaskService reconnect task.
     */
    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                bindTaskService();
                
                // If still disconnected, try again in 2s
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 2000);
            } else {
            }
        }
    };

    /**
     * Schedule a TaskService reconnect.
     */
    private void scheduleReconnectTaskService() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reconnectTaskServiceRunnable, 2000);
    };
    
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = 
        (requestCode, grantResult) -> {
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                bindTaskService();
            }
            // Notify Flutter to refresh state
            if (methodChannel != null) {
                runOnUiThread(() -> {
                    methodChannel.invokeMethod("onShizukuPermissionChanged", granted);
                });
            }
        };
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Save the instance
        currentInstance = this;
        
        // Add Shizuku listeners (critical! sticky version)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
        
        // Auto-check and request Shizuku permission
        checkAndRequestShizukuPermission();
        
        // Handle the incoming notification intent
        handleIncomingIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }
    
    /**
     * Handle a notification Intent from the Service.
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        
        // Handle the incoming notification intent
        if ("SHOW_NOTIFICATION_ON_REAR_SCREEN".equals(action)) {
            String packageName = intent.getStringExtra("packageName");
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            
            Log.d(TAG, "Received notification intent for: " + packageName);
            startNotificationOnRearScreen(packageName, title, text);
        }
    }
    
    /**
     * Show a notification on the rear screen via the rear host.
     */
    private void startNotificationOnRearScreen(String packageName, String title, String text) {
        android.os.Bundle payload = new android.os.Bundle();
        payload.putString("packageName", packageName);
        payload.putString("title", title);
        payload.putString("text", text);
        new Thread(() -> RearHost.show(this, RearStack.Type.NOTIFICATION, payload)).start();
    }
    
    /**
     * Execute a shell command (for the rear host).
     */
    public void executeShellCommand(String cmd) {
        if (taskService != null) {
            try {
                taskService.executeShellCommand(cmd);
            } catch (Exception e) {
                Log.e(TAG, "Failed to execute command: " + cmd, e);
            }
        }
    }
    
    private void checkAndRequestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0);
                } else {
                    bindTaskService();
                }
            } else {
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to check Shizuku permission", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Clear the static instance
        currentInstance = null;
        
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
    }
    
    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        
        methodChannel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        methodChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "checkShizuku": {
                        try {
                            boolean isRunning = Shizuku.pingBinder();
                            boolean hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                            result.success(isRunning && hasPermission);
                        } catch (Exception e) {
                            result.success(false);
                        }
                        break;
                    }
                    
                    case "requestShizukuPermission": {
                        try {
                            Shizuku.requestPermission(0);
                            result.success(null);
                        } catch (Exception e) {
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "getShizukuInfo": {
                        try {
                            boolean isRunning = Shizuku.pingBinder();
                            boolean hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                            int uid = Shizuku.getUid();
                            int version = Shizuku.getVersion();
                            String info = "Running: " + isRunning + "\n" +
                                         "Permission: " + hasPermission + "\n" +
                                         "UID: " + uid + "\n" +
                                         "Version: " + version;
                            result.success(info);
                        } catch (Exception e) {
                            result.success("Error: " + e.getMessage());
                        }
                        break;
                    }
                    
                    case "getCurrentApp": {
                        if (taskService != null) {
                            try {
                                String currentApp = taskService.getCurrentForegroundApp();
                                result.success(currentApp);
                            } catch (Exception e) {
                                Log.e(TAG, "TaskService error: " + e.getMessage(), e);
                                result.success(null);
                            }
                        } else {
                            result.success(null);
                        }
                        break;
                    }
                    
                    case "requestNotificationPermission": {
                        // Android 13+ requires requesting the notification permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                                != PackageManager.PERMISSION_GRANTED) {
                                ActivityCompat.requestPermissions(this, 
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                                    NOTIFICATION_PERMISSION_REQUEST_CODE);
                                result.success(null);
                            } else {
                                result.success(null);
                            }
                        } else {
                            // Android 12 and below do not require the notification permission
                            result.success(null);
                        }
                        break;
                    }
                    
                    case "getCurrentRearDpi": {
                        if (taskService != null) {
                            try {
                                int dpi = taskService.getCurrentRearDpi();
                                result.success(dpi);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to get rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "setRearDpi": {
                        if (taskService != null) {
                            try {
                                int dpi = (int) call.argument("dpi");
                                boolean success = taskService.setRearDpi(dpi);
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to set rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "resetRearDpi": {
                        if (taskService != null) {
                            try {
                                boolean success = taskService.resetRearDpi();
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to reset rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "openCoolApkProfile": {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.coolapk.market", "com.coolapk.market.view.AppLinkActivity");
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("coolmarket://u/8158212"));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open CoolApk profile", e);
                            result.error("ERROR", "请先安装酷安应用", null);
                        }
                        break;
                    }
                    
                    case "openCoolApkProfileXmz": {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.coolapk.market", "com.coolapk.market.view.AppLinkActivity");
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("coolmarket://u/4279097"));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open CoolApk profile", e);
                            result.error("ERROR", "请先安装酷安应用", null);
                        }
                        break;
                    }
                    
                    case "openTutorial": {
                        // Open the Tencent Docs tutorial
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("https://docs.qq.com/doc/DVWxpT3hQdHNPR3Zy?dver="));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open tutorial", e);
                            result.error("ERROR", "打开失败: " + e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "openDonationPage": {
                        // Open the donation page
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("https://tgwgroup.ltd/2025/10/19/%e5%85%b3%e4%ba%8e%e6%89%93%e8%b5%8f/"));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open donation page", e);
                            result.error("ERROR", "打开失败: " + e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "openQQGroup": {
                        // Open the MRSS community group page
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("https://tgwgroup.ltd/2025/10/21/%e5%85%b3%e4%ba%8emrss%e4%ba%a4%e6%b5%81%e7%be%a4/"));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open QQ group page", e);
                            result.error("ERROR", "打开失败: " + e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "ensureTaskServiceConnected": {
                        // Ensure TaskService is connected
                        try {
                            if (taskService == null) {
                                // Try to rebind
                                bindTaskService();
                            }
                            result.success(taskService != null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to ensure TaskService connection", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "moveCurrentAppToRear": {
                        if (taskService != null) {
                            try {
                                String currentApp = taskService.getCurrentForegroundApp();
                                if (currentApp == null || !currentApp.contains(":")) {
                                    result.success(false);
                                    break;
                                }

                                String[] parts = currentApp.split(":");
                                String packageName = parts[0];
                                int taskId = Integer.parseInt(parts[1]);

                                boolean success = taskService.moveTaskToDisplay(taskId, 1);
                                if (success) {
                                    SwitchToRearTileService.setLastMovedTask(currentApp);
                                    try {
                                        taskService.collapseStatusBar();
                                    } catch (Exception ignored) {}
                                }
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to move current app to rear", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }

                    case "returnRearAppToMain": {
                        if (taskService != null) {
                            try {
                                String target = SwitchToRearTileService.getLastMovedTask();
                                if (target == null || !target.contains(":")) {
                                    target = taskService.getForegroundAppOnDisplay(1);
                                }

                                if (target == null || !target.contains(":")) {
                                    result.success(false);
                                    break;
                                }

                                String[] parts = target.split(":");
                                int taskId = Integer.parseInt(parts[1]);

                                boolean success = taskService.moveTaskToDisplay(taskId, 0);
                                if (success) {
                                    try {
                                        taskService.enableSubScreenLauncher();
                                    } catch (Exception ignored) {}
                                }
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to return rear app", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "setDisplayRotation": {
                        if (taskService != null) {
                            try {
                                int displayId = (int) call.argument("displayId");
                                int rotation = (int) call.argument("rotation");
                                boolean success = taskService.setDisplayRotation(displayId, rotation);
                                result.success(success);
                            } catch (Exception e) {
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "getDisplayRotation": {
                        if (taskService != null) {
                            try {
                                int displayId = (int) call.argument("displayId");
                                int rotation = taskService.getDisplayRotation(displayId);
                                result.success(rotation);
                            } catch (Exception e) {
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "returnRearAppAndRestart": {
                        // Pull rear-screen apps back before restarting
                        if (taskService != null) {
                            try {
                                // Get the last moved task info
                                String lastTask = SwitchToRearTileService.getLastMovedTask();
                                
                                if (lastTask != null && lastTask.contains(":")) {
                                    String[] parts = lastTask.split(":");
                                    int taskId = Integer.parseInt(parts[1]);
                                    
                                    // Check whether the task is still on the rear screen
                                    boolean onRear = taskService.isTaskOnDisplay(taskId, 1);
                                    
                                    if (onRear) {
                                        // Pull it back to the main screen
                                        taskService.moveTaskToDisplay(taskId, 0);
                                        
                                        // Restore the official Launcher
                                        taskService.enableSubScreenLauncher();
                                        
                                        result.success(true);
                                    } else {
                                        // No app on the rear screen
                                        result.success(false);
                                    }
                                } else {
                                    // No record
                                    result.success(false);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to return rear app", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "setProximitySensorEnabled": {
                        // V2.2: toggle proximity sensor
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        // Notify RearScreenKeeperService to update state
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_PROXIMITY_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setKeepScreenOnEnabled": {
                        // V2.5: set rear-screen always-on
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        // Notify RearScreenKeeperService to update state
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_KEEP_SCREEN_ON_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setAlwaysWakeUpEnabled": {
                        // V3.5: set always-wake when no app is cast
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("always_wakeup_enabled", enabled).apply();
                        
                        Intent intent = new Intent(this, AlwaysWakeUpService.class);
                        if (enabled) {
                            startService(intent);
                            Log.d(TAG, "AlwaysWakeUpService started");
                        } else {
                            stopService(intent);
                            Log.d(TAG, "AlwaysWakeUpService stopped");
                        }
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setWakeOnLockEnabled": {
                        // Toggle wake-on-lock
                        boolean enabled = (boolean) call.argument("enabled");

                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("wake_on_lock_enabled", enabled).apply();

                        result.success(true);
                        break;
                    }

                    case "setChargingAlwaysOnEnabled": {
                        // V3.5: set charging always-on
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("charging_always_on_enabled", enabled).apply();
                        
                        // Notify ChargingService to reload settings
                        sendBroadcast(new Intent("com.tgwgroup.MiRearScreenSwitcher.RELOAD_CHARGING_SETTINGS"));
                        
                        Log.d(TAG, "Charging always on set to: " + enabled);
                        result.success(true);
                        break;
                    }
                    
                    case "toggleChargingService": {
                        // V2.3: toggle the charging service
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        Intent intent = new Intent(this, ChargingService.class);
                        if (enabled) {
                            startService(intent);
                            Log.d(TAG, "ChargingService started");
                        } else {
                            stopService(intent);
                            Log.d(TAG, "ChargingService stopped");
                        }
                        
                        result.success(true);
                        break;
                    }
                    
                    case "startNotificationService": {
                        // V2.4: start the notification service
                        Intent intent = new Intent(this, NotificationService.class);
                        startService(intent);
                        Log.d(TAG, "NotificationService started");
                        result.success(true);
                        break;
                    }
                    
                    case "toggleNotificationService": {
                        // V2.4: toggle the notification service
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit()
                            .putBoolean("notification_service_enabled", enabled)
                            .apply();
                        
                        if (enabled) {
                            // Start the service when enabled
                            Intent intent = new Intent(this, NotificationService.class);
                            startService(intent);
                            Log.d(TAG, "NotificationService started");
                        } else {
                            // Stop the service when disabled
                            Intent intent = new Intent(this, NotificationService.class);
                            stopService(intent);
                            Log.d(TAG, "NotificationService stopped");
                        }
                        
                        Log.d(TAG, "Notification service enabled: " + enabled);
                        result.success(true);
                        break;
                    }
                    
                    case "checkNotificationListenerPermission": {
                        // V2.4: check notification listener permission
                        boolean hasPermission = isNotificationListenerEnabled();
                        result.success(hasPermission);
                        break;
                    }
                    
                    case "openNotificationListenerSettings": {
                        // V2.4: open notification listener settings
                        try {
                            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open notification settings", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "checkQueryAllPackagesPermission": {
                        // V2.4: check QUERY_ALL_PACKAGES permission
                        boolean hasPermission = checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
                        Log.d(TAG, "🔍 QUERY_ALL_PACKAGES permission check: " + hasPermission);
                        result.success(hasPermission);
                        break;
                    }
                    
                    case "requestQueryAllPackagesPermission": {
                        // V2.4: request QUERY_ALL_PACKAGES permission (open app details page)
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open app settings", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "getInstalledApps": {
                        // V2.4: get installed apps (async)
                        new Thread(() -> {
                            try {
                                // First check the permission
                                boolean hasPermission = checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
                                if (!hasPermission) {
                                    Log.w(TAG, "⚠️ No QUERY_ALL_PACKAGES permission; app list may be incomplete");
                                }
                                
                                java.util.List<java.util.Map<String, Object>> apps = getInstalledApps();
                                runOnUiThread(() -> result.success(apps));
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to get installed apps", e);
                                runOnUiThread(() -> result.error("ERROR", e.getMessage(), null));
                            }
                        }).start();
                        break;
                    }
                    
                    case "getSelectedNotificationApps": {
                        // V2.4: get the selected notification apps
                        try {
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            java.util.Set<String> selectedApps = prefs.getStringSet("notification_selected_apps", new java.util.HashSet<>());
                            result.success(new java.util.ArrayList<>(selectedApps));
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to get selected apps", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setSelectedNotificationApps": {
                        // V2.4: save the selected notification apps
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.List<String> selectedApps = (java.util.List<String>) call.arguments;
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putStringSet("notification_selected_apps", new java.util.HashSet<>(selectedApps))
                                .apply();
                            Log.d(TAG, "Saved " + selectedApps.size() + " selected apps");
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to save selected apps", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationPrivacyHideTitle": {
                        // V3.2: set hide notification title
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_privacy_hide_title", enabled)
                                .apply();
                            
                            // Notify NotificationService to reload settings
                            sendBroadcast(new Intent("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS"));
                            
                            Log.d(TAG, "Privacy hide title set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set privacy hide title", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationPrivacyHideContent": {
                        // V3.2: set hide notification content
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_privacy_hide_content", enabled)
                                .apply();
                            
                            // Notify NotificationService to reload settings
                            sendBroadcast(new Intent("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS"));
                            
                            Log.d(TAG, "Privacy hide content set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set privacy hide content", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setFollowDndMode": {
                        // V3.0: set follow system DND mode
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_follow_dnd_mode", enabled)
                                .apply();
                            Log.d(TAG, "Follow DND mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set follow DND mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setOnlyWhenLocked": {
                        // V3.0: set notify only when locked
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_only_when_locked", enabled)
                                .apply();
                            Log.d(TAG, "Only when locked mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set only when locked mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationDarkMode": {
                        // V3.1: set notification dark mode
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_dark_mode", enabled)
                                .apply();
                            Log.d(TAG, "Notification dark mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set notification dark mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationDuration": {
                        // V3.4: set notification auto-destroy time
                        try {
                            int duration = (int) call.argument("duration");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putInt("notification_duration", duration)
                                .apply();
                            Log.d(TAG, "Notification duration set to: " + duration + " seconds");
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set notification duration", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    default:
                        result.notImplemented();
                }
            });
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            } else {
            }
        }
    }
    
    /**
     * V2.4: whether the notification listener service is enabled.
     */
    private boolean isNotificationListenerEnabled() {
        String packageName = getPackageName();
        String flat = android.provider.Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        
        String[] names = flat.split(":");
        for (String name : names) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(name);
            if (cn != null && packageName.equals(cn.getPackageName())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * V2.4: get the installed apps list.
     */
    private java.util.List<java.util.Map<String, Object>> getInstalledApps() {
        java.util.List<java.util.Map<String, Object>> apps = new java.util.ArrayList<>();
        
        try {
            PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            
            Log.d(TAG, "Total packages found: " + packages.size());
            
            // Whitelist: user apps + important system apps
            java.util.Set<String> importantSystemApps = new java.util.HashSet<>();
            importantSystemApps.add("com.tencent.mm"); // WeChat
            importantSystemApps.add("com.tencent.mobileqq"); // QQ
            importantSystemApps.add("com.coolapk.market"); // CoolApk
            importantSystemApps.add("com.sina.weibo"); // Weibo
            importantSystemApps.add("com.taobao.taobao"); // Taobao
            importantSystemApps.add("com.eg.android.AlipayGphone"); // Alipay
            importantSystemApps.add("com.netease.cloudmusic"); // NetEase Music
            importantSystemApps.add("com.ss.android.ugc.aweme"); // Douyin
            importantSystemApps.add("com.bilibili.app.in"); // Bilibili
            importantSystemApps.add("com.android.mms"); // SMS
            importantSystemApps.add("com.android.contacts"); // Contacts
            
            for (android.content.pm.ApplicationInfo appInfo : packages) {
                // Skip self
                if (appInfo.packageName.equals(getPackageName())) {
                    continue;
                }
                
                boolean isSystemApp = (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUserApp = !isSystemApp;
                boolean isImportantSystemApp = importantSystemApps.contains(appInfo.packageName);
                
                // Only user apps and important system apps
                if (!isUserApp && !isImportantSystemApp) {
                    continue;
                }
                
                java.util.Map<String, Object> app = new java.util.HashMap<>();
                app.put("appName", pm.getApplicationLabel(appInfo).toString());
                app.put("packageName", appInfo.packageName);
                app.put("isSystemApp", isSystemApp);  // V3.3: add system-app flag
                
                // Get the app icon (full resolution, uncompressed)
                try {
                    Drawable icon = pm.getApplicationIcon(appInfo);
                    // Use the original icon size; do not constrain it
                    int iconSize = Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                    if (iconSize <= 0) iconSize = 192; // fallback high-res size if unavailable
                    
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        iconSize, iconSize, android.graphics.Bitmap.Config.ARGB_8888
                    );
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                    icon.setBounds(0, 0, iconSize, iconSize);
                    icon.draw(canvas);
                    
                    java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); // 100% quality, lossless
                    app.put("icon", stream.toByteArray());
                    
                    bitmap.recycle();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get icon for " + appInfo.packageName);
                }
                
                apps.add(app);
            }
            
            // Sort by app name
            apps.sort((a, b) -> {
                String nameA = (String) a.get("appName");
                String nameB = (String) b.get("appName");
                return nameA.compareToIgnoreCase(nameB);
            });
            
            Log.d(TAG, "Found " + apps.size() + " user apps");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to get installed apps", e);
        }
        
        return apps;
    }
}
