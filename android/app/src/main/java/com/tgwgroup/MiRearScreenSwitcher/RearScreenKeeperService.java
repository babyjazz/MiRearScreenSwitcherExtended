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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.List;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import rikka.shizuku.Shizuku;

/**
 * Foreground Service - keeps the rear screen on.
 * 
 * Why a Service instead of an Activity:
 * - The Activity approach failed 3 times (FLAG_NOT_FOCUSABLE, off-screen, alpha=0 all get onStop)
 * - Services do not get onPause/onStop; the system rarely kills a foreground Service
 * - Can hold a WakeLock directly to keep the screen on
 * 
 * Note: a WakeLock may keep both screens on (cannot target a specific display).
 */
public class RearScreenKeeperService extends Service implements SensorEventListener {
    private static final String TAG = "RearScreenKeeperService";
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = 10001;

    private static RearScreenKeeperService instance = null;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private ITaskService taskService = null;

    // V12.3: initial kill strategy - kill once, no continuous monitoring
    private static final int INITIAL_KILL_COUNT = 1; // initial kill once
    private static final long KILL_INTERVAL_MS = 200; // 200ms between kills

    // V12.1: proximity sensor listening
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean isProximityCovered = false;
    private long lastProximityTime = 0;
    private static final long PROXIMITY_DEBOUNCE_MS = 1500; // debounce: only trigger after 1500ms of sustained cover (lower sensitivity)

    // V2.2: proximity sensor toggle state
    private boolean proximitySensorEnabled = true; // enabled by default

    // V14.5: watch whether the app is manually moved back to the main screen
    private static final long CHECK_TASK_INTERVAL_MS = 2000; // check every 2s
    private String monitoredTaskInfo = null; // format: "packageName:taskId"

    // V2.3: temporarily pause monitoring while the charging animation shows
    private boolean monitoringPaused = false;

    // V2.4: keep waking the rear screen (prevents auto-sleep)
    private static final long WAKEUP_INTERVAL_MS = 2000; // send every 2s (100ms was too frequent; each spawns a shell process)
    private static final long WAKELOCK_SAFETY_TIMEOUT_MS = 10 * 60 * 1000; // WakeLock safety-net timeout, renewed by the monitor loop
    private boolean keepScreenOnEnabled = true; // rear-screen always-on enabled by default

    public static void pauseMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = true;

            // ✅ Cancel all pending check tasks
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                Log.d(TAG, "⏸️ Monitoring paused, all checks cancelled");
            } else {
                Log.d(TAG, "⏸️ Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = false;
            Log.d(TAG, "▶️ Monitoring resumed");

            // ✅ Start checking only after 5s, giving the cast app time to return to the foreground
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                Log.d(TAG, "⏰ Next check scheduled in 5 seconds");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Create the notification channel
        createNotificationChannel();

        // Create the Handler for scheduled tasks
        handler = new Handler(Looper.getMainLooper());

        // V2.2: restore the sensor toggle state from SharedPreferences
        loadProximitySensorSetting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // V14.6: handle notification click returning to the main screen
        if (intent != null && "ACTION_RETURN_TO_MAIN".equals(intent.getAction())) {

            // Move the monitored task back to the main screen
            if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":") && taskService != null) {
                try {
                    String[] parts = monitoredTaskInfo.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // Get the app name
                    String appName = getAppName(packageName);

                    taskService.moveTaskToDisplay(taskId, 0);

                    // Remove the foreground notification first
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);

                    // Delay the Toast
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " returned to the main screen", Toast.LENGTH_SHORT).show();
                    }, 100);

                    // Stop the service
                    stopSelf();
                    return START_NOT_STICKY;

                } catch (Exception e) {
                    Log.w(TAG, "Failed to return task to main", e);
                }
            }
        }

        // V2.2: handle the proximity sensor toggle setting
        if (intent != null && "ACTION_SET_PROXIMITY_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            proximitySensorEnabled = enabled;

            Log.d(TAG, "🔧 Sensor toggle state updated: " + enabled);

            // If the sensor is off and we are listening, unregister
            if (!enabled && sensorManager != null && proximitySensor != null) {
                sensorManager.unregisterListener(this);
                Log.d(TAG, "⏸️ Sensor listener unregistered");
            }
            // If the sensor is on and we are not listening, register
            else if (enabled && sensorManager != null && proximitySensor != null) {
                boolean registered = sensorManager.registerListener(this, proximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                if (registered) {
                    Log.d(TAG, "✅ Sensor listener registered");
                } else {
                    Log.w(TAG, "⚠ Sensor listener registration failed");
                }
            }

            return START_STICKY;
        }

        // V2.5: handle the rear-screen always-on toggle setting
        if (intent != null && "ACTION_SET_KEEP_SCREEN_ON_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepScreenOnEnabled = enabled;

            Log.d(TAG, "🔆 Rear-screen always-on " + (enabled ? "enabled" : "disabled"));

            // If always-on is off, stop sending WAKEUP
            if (!enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                Log.d(TAG, "⏸️ Rear-screen WAKEUP sending stopped");
            }
            // If always-on is on, start sending WAKEUP
            else if (enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                startRearScreenWakeup();
            }

            return START_STICKY;
        }

        try {
            // V14.7: read the monitored task info from the Intent first
            if (intent != null) {
                String newMonitoredTask = intent.getStringExtra("lastMovedTask");
                if (newMonitoredTask != null) {
                    monitoredTaskInfo = newMonitoredTask;
                }
            }

            // V2.5: read the rear-screen always-on toggle from the Intent
            if (intent != null) {
                keepScreenOnEnabled = intent.getBooleanExtra("keepScreenOnEnabled", true);
                Log.d(TAG, "🔆 Rear-screen always-on state: " + (keepScreenOnEnabled ? "enabled" : "disabled"));
            }

            // V15.1: show the notification immediately, do not wait for other work
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);

            // Do the slow work on a background thread so it does not block the notification
            new Thread(() -> {
                // Bind Shizuku TaskService
                bindTaskService();

                // Initialize the proximity sensor
                initProximitySensor();
            }).start();

            // 2. acquire WakeLock to keep the screen on
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

                // Use SCREEN_BRIGHT_WAKE_LOCK to keep the screen lit
                // Note: keeps the screen lit, but may not target a specific display
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK, // dropped ACQUIRE_CAUSES_WAKEUP to avoid waking the main screen
                        "MRSS::RearScreenKeeper");

                // Set a safety-net timeout in case release() is skipped by an exception, leaving the lock held forever
                // The monitor loop (wakeupRunnable) renews it periodically via renewWakeLock()
                wakeLock.acquire(WAKELOCK_SAFETY_TIMEOUT_MS);

            } else {
            }

            // 3. V12.2: initial process kills (a few, then no monitoring)
            performInitialKills();

            // 4. V14.5: start the periodic check task
            if (monitoredTaskInfo != null) {
                startTaskMonitoring();
            }

            // 5. V2.5: start the continuous rear wake (every 2s, per toggle)
            startRearScreenWakeup();

        } catch (Exception e) {
            Log.e(TAG, "✗ Error starting service", e);
        }

        // START_STICKY: auto-restarts if the system kills it
        return START_STICKY;
    }

    /**
     * V15.2: start task monitoring - detect whether the app is in the foreground.
     * Monitors the app cast to the rear screen; if it leaves the foreground (closed/switched), stops the service and clears the notification.
     */
    private final Runnable checkTaskRunnable = new Runnable() {
        @Override
        public void run() {
            // V2.3: skip this check while monitoring is paused (charging animation showing)
            if (monitoringPaused) {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }

            if (monitoredTaskInfo != null && taskService != null) {
                try {
                    // V15.2: check whether the rear display (displayId=1) foreground is still the monitored app
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);

                    // V2.3: exclude our rear host (charging/notification/media; temporary rear-screen owner; must not destroy the service
                    if (rearForegroundApp != null && rearForegroundApp.contains("RearHostActivity")) {
                        // Charging animation is showing; skip this check
                        handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                        return;
                    }

                    // If the rear foreground is not the monitored app, it was closed or switched
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // The app left the rear foreground (closed/switched); stop the service
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        stopSelf();
                        return;
                    }

                    // Keep monitoring
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);

                } catch (Exception e) {
                    Log.w(TAG, "Task check failed: " + e.getMessage());
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                }
            } else {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
            }
        }
    };

    private void startTaskMonitoring() {
        if (monitoredTaskInfo != null && handler != null) {
            handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
        }
    }

    /**
     * V2.5: continuous rear wake task - sends WAKEUP periodically to prevent auto-sleep.
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            // Renew the WakeLock safety-net timeout to keep it from being released mid-monitoring
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.acquire(WAKELOCK_SAFETY_TIMEOUT_MS);
            }

            // Check the toggle state
            if (keepScreenOnEnabled && taskService != null) {
                try {
                    // Send WAKEUP to the rear display (displayId=1)
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // Log.d(TAG, "✨ Rear keep-alive wake sent"); // commented out to reduce log noise
                } catch (Exception e) {
                    Log.w(TAG, "Rear-screen wake failed: " + e.getMessage());
                }
            }

            // Send continuously every 2s
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };

    private void startRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            // Fire once immediately, then keep sending
            handler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ Rear-screen continuous wake started (0.5s interval)");
        }
    }

    /**
     * V12.3: initial process kill - once, with no continuous monitoring.
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (taskService != null) {
                        try {
                            taskService.killLauncherProcess();
                        } catch (Exception e) {
                            Log.w(TAG, "⚠ Kill #" + killNumber + " failed: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "⚠ TaskService not available for kill #" + killNumber);
                    }

                    // Summary after the final kill
                    if (killNumber == INITIAL_KILL_COUNT) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                            }
                        }, 100);
                    }
                }
            }, i * KILL_INTERVAL_MS);
        }
    }

    /**
     * Shizuku TaskService connection callback.
     */
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);

            // Cancel the reconnect task (if any)
            if (handler != null) {
                handler.removeCallbacks(reconnectTaskServiceRunnable);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠ TaskService disconnected - will attempt to reconnect");
            taskService = null;

            // Start the reconnect task
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
                handler.postDelayed(this, 1000);
            } else {
            }
        }
    };

    /**
     * Schedule a TaskService reconnect.
     */
    private void scheduleReconnectTaskService() {
        if (handler != null) {
            handler.postDelayed(reconnectTaskServiceRunnable, 300);
        }
    };

    /**
     * Bind the Shizuku TaskService.
     */
    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), TaskService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("task_service")
                    .debuggable(false)
                    .version(1);

            Shizuku.bindUserService(args, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to bind TaskService", e);
        }
    }

    /**
     * Unbind the TaskService.
     */
    private void unbindTaskService() {
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(
                        new Shizuku.UserServiceArgs(
                                new ComponentName(getPackageName(), TaskService.class.getName()))
                                .daemon(false)
                                .processNameSuffix("task_service")
                                .debuggable(false)
                                .version(1),
                        taskServiceConnection,
                        true);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "⚠ Service onDestroy called");

        // Remove the foreground notification immediately
        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        // Clear all pending tasks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // V12.2: restore and actively wake the Launcher
        if (taskService != null) {
            try {

                // 1. restore the Launcher (unsuspend)
                taskService.enableSubScreenLauncher();

                // 2. brief delay so the unsuspend takes effect
                Thread.sleep(300);

                // 3. actively launch the Launcher Activity to wake it

            } catch (Exception e) {
                Log.w(TAG, "Failed to restore launcher", e);
            }
        }

        // Unbind the TaskService
        unbindTaskService();

        // Release the WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Unregister the proximity sensor
        unregisterProximitySensor();

        instance = null;
        Log.w(TAG, "═══════════════════════════════════════");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Binding not supported
        return null;
    }

    /**
     * Create the notification channel (required on Android 8.0+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW // low importance, less intrusive
            );
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            channel.setShowBadge(false); // no badge
            channel.enableLights(false); // no LED flashes
            channel.enableVibration(false); // no vibration

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

        }
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
        return packageName; // fall back to package name on failure
    }

    /**
     * V2.4: create a shared Service foreground notification (used by several Services).
     */
    public static Notification createServiceNotification(Context context) {
        // Create the notification channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_kernel_service))
                .setContentText(context.getString(R.string.notif_mrss_running))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * Build the foreground notification.
     */
    private Notification buildNotification() {
        // Get the app name
        String appName = getString(R.string.app_name);

        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            String packageName = monitoredTaskInfo.split(":")[0];
            appName = getAppName(packageName);
        } else {
            Log.w(TAG, "⚠ Invalid monitored task info: " + monitoredTaskInfo);
        }

        // Clicking the notification switches back to the main screen
        Intent returnIntent = new Intent(this, RearScreenKeeperService.class);
        returnIntent.setAction("ACTION_RETURN_TO_MAIN");
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, returnIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " " + getString(R.string.notif_running_on_rear))
                .setContentText(getString(R.string.notif_click_to_return, appName))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // low priority
                .setOngoing(true) // ongoing notification; cannot be swiped away
                .setShowWhen(false) // no timestamp
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * Whether the Service is running.
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * Stop the Service.
     */
    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    // ========================================
    // Proximity sensor methods
    // ========================================

    /**
     * Load the sensor toggle state from SharedPreferences.
     */
    private void loadProximitySensorSetting() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            proximitySensorEnabled = prefs.getBoolean("flutter.proximity_sensor_enabled", true);
            Log.d(TAG, "🔧 Sensor toggle state restored: " + proximitySensorEnabled);
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to load sensor settings", e);
            proximitySensorEnabled = true; // enabled by default
        }
    }

    /**
     * Initialize the proximity sensor (rear-screen proximity sensor).
     */
    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            if (sensorManager != null) {
                // Get all sensors
                List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);

                // Find the rear proximity sensor (name contains "Back" and "Proximity")
                // Prefer the Wakeup variant; fall back to the Non-wakeup variant
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;

                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && name.contains("Back")) {
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }

                // Prefer the Wakeup variant
                if (wakeupSensor != null) {
                    proximitySensor = wakeupSensor;
                } else if (nonWakeupSensor != null) {
                    proximitySensor = nonWakeupSensor;
                    Log.w(TAG, "→ Using NON-WAKEUP sensor (may not provide continuous data)");
                }

                // If no rear sensor is found, fall back to the default sensor
                if (proximitySensor == null) {
                    Log.w(TAG, "⚠ Rear proximity sensor not found, using default");
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }

                if (proximitySensor != null) {
                    // V2.2: only register the listener when the sensor toggle is on
                    if (proximitySensorEnabled) {
                        boolean registered = sensorManager.registerListener(
                                this,
                                proximitySensor,
                                SensorManager.SENSOR_DELAY_NORMAL);

                        if (registered) {
                            Log.d(TAG, "✅ Proximity sensor registered (toggle: " + proximitySensorEnabled + ")");
                        } else {
                            Log.w(TAG, "⚠ Failed to register proximity sensor");
                        }
                    } else {
                        Log.d(TAG, "⏸️ Proximity sensor disabled, skipping registration");
                    }
                } else {
                    Log.w(TAG, "⚠ No proximity sensor available");
                }
            } else {
                Log.w(TAG, "⚠ SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing proximity sensor", e);
        }
    }

    /**
     * Unregister the proximity sensor.
     */
    private void unregisterProximitySensor() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error unregistering proximity sensor", e);
        }
    }

    /**
     * Sensor data change callback.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        // V2.2: ignore events when the sensor is off
        if (!proximitySensorEnabled) {
            return;
        }

        // Check whether this is our rear proximity sensor
        if (event.sensor == proximitySensor) {
            float distance = event.values[0];
            float maxRange = proximitySensor.getMaximumRange();

            // Detailed log - record every sensor change

            // Triggers when distance approaches 0 (covered)
            boolean isCovered = (distance < maxRange * 0.2f); // below 20% of max distance counts as covered

            long currentTime = System.currentTimeMillis();

            if (isCovered && !isProximityCovered) {
                // Transitioned from uncovered to covered
                isProximityCovered = true;
                lastProximityTime = currentTime;

                Log.w(TAG, "👋 PROXIMITY COVERED! Distance: " + distance + " cm");
                Log.w(TAG, "👋 Starting debounce timer (" + PROXIMITY_DEBOUNCE_MS + "ms)...");

                // Debounce: check after a delay
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isProximityCovered &&
                                (System.currentTimeMillis() - lastProximityTime >= PROXIMITY_DEBOUNCE_MS)) {
                            // Confirmed covered for over 500ms; trigger the pull-back to the main screen
                            Log.w(TAG, "👋 Debounce timer expired - triggering return to main display!");
                            handleProximityCovered();
                        } else {
                        }
                    }
                }, PROXIMITY_DEBOUNCE_MS);

            } else if (!isCovered && isProximityCovered) {
                // Transitioned from covered to uncovered
                isProximityCovered = false;
            } else if (isCovered && isProximityCovered) {
                // Still covered
            } else {
                // Still uncovered
            }
        } else {
            // Log other sensors too
        }
    }

    /**
     * Sensor precision change callback (no handling needed).
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No handling needed
    }

    /**
     * Handle proximity sensor cover: pull back to the main screen and stop the Service.
     */
    private void handleProximityCovered() {
        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "🤚 PROXIMITY TRIGGER - Return to main display");
        Log.w(TAG, "═══════════════════════════════════════");

        try {
            if (taskService != null) {
                // Get the last moved task info
                String lastTask = SwitchToRearTileService.getLastMovedTask();

                if (lastTask != null && lastTask.contains(":")) {
                    String[] parts = lastTask.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // Get the app name
                    String appName = getAppName(packageName);

                    // Pull back to the main screen
                    boolean success = taskService.moveTaskToDisplay(taskId, 0);

                    if (success) {
                        // Delay the Toast
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(RearScreenKeeperService.this, appName + " returned to the main screen", Toast.LENGTH_SHORT).show();
                        }, 100);
                    } else {
                        Log.w(TAG, "⚠ Failed to return task (may already be on main display)");
                    }
                } else {
                    Log.w(TAG, "⚠ No active rear screen task found");
                }

                // Remove the foreground notification first
                stopForeground(Service.STOP_FOREGROUND_REMOVE);

                // Stop the Service (auto-restores the system Launcher)
                stopSelf();

            } else {
                Log.w(TAG, "⚠ TaskService not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error handling proximity event", e);
        }
    }
}
