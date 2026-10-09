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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Charging state listener service.
 * Listens for power-connect events and shows the battery animation on the rear screen when plugged in.
 */
public class ChargingService extends Service {
    private static final String TAG = "ChargingService";
    private SharedPreferences prefs;
    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;
    
    // Static instance, accessible from RearScreenChargingActivity
    private static ChargingService instance;
    
    // Prevent duplicate animation triggers (cooldown)
    private long lastChargingAnimationTime = 0;
    private static final long CHARGING_ANIMATION_COOLDOWN_MS = 6000; // 6s cooldown
    
    // V3.5: charging animation always-on mode
    private boolean chargingAlwaysOnEnabled = false;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;
    
    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }
    
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("charging_task_service")
            .debuggable(false)
            .version(1);
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✓ TaskService connected");
            taskService = ITaskService.Stub.asInterface(binder);
            
            // Initialize the display info cache
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                Log.w(TAG, "Failed to initialize the display cache: " + e.getMessage());
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "✗ TaskService disconnected");
            taskService = null;
            // Auto-reconnect
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (taskService == null) {
                    bindTaskService();
                }
            }, 1000);
        }
    };
    
    // Shizuku listener
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            Log.d(TAG, "Shizuku binder received");
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            Log.d(TAG, "Shizuku binder dead");
            taskService = null;
            // Try to reconnect
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                bindTaskService();
            }, 1000);
        };
    
    // V3.5: settings-change broadcast receiver
    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Settings-change broadcast received");
            chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
            Log.d(TAG, "Charging always-on: " + chargingAlwaysOnEnabled);
        }
    };
    
    // V3.5: resume-charging-animation broadcast receiver
    private BroadcastReceiver resumeChargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔋 Resume-charging broadcast received; preparing to restore");
                
                // Get the current battery level
                int batteryLevel = getBatteryLevel(context);
                
                // Restart the charging animation after a delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // Animation manager: start the charging animation
                        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                        
                        // Start the charging animation
                        showChargingOnRearScreen(batteryLevel, false);
                        
                        // If always-on is enabled, start the wake loop
                        if (chargingAlwaysOnEnabled) {
                            Log.d(TAG, "💡 Always-on enabled; starting the wakeup loop");
                            startWakeupAndUpdateLoop();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore the charging animation", e);
                    }
                }, 300);  // 300ms delay so the notification Activity is fully destroyed
            }
        }
    };
    
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                // Reconnect within FLAP_WINDOW_MS after unplug = PD drop/renegotiation (a bad port does this roughly every 40s), not a real plug-in; skip the animation
                if (System.currentTimeMillis() - lastPowerDisconnectedTime < FLAP_WINDOW_MS) {
                    Log.d(TAG, "🔌 Power reconnected within " + FLAP_WINDOW_MS + "ms of disconnect, treating as flap");
                    return;
                }
                // A flaky USB port reconnects repeatedly within seconds (PD renegotiation), firing this broadcast each time.
                // Debounce: wait CHARGE_DEBOUNCE_MS before animating; if an unplug broadcast arrives in between, cancel,
                // so a jittery charger cannot keep grabbing the rear screen.
                debounceHandler.removeCallbacks(pendingChargingRunnable);
                debounceHandler.postDelayed(pendingChargingRunnable, CHARGE_DEBOUNCE_MS);
                Log.d(TAG, "🔌 Power connected, debouncing " + CHARGE_DEBOUNCE_MS + "ms");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                lastPowerDisconnectedTime = System.currentTimeMillis();
                // Unplugged during the debounce window: cancel the pending animation
                debounceHandler.removeCallbacks(pendingChargingRunnable);

                // Charger unplugged: destroy the charging animation immediately
                Log.d(TAG, "🔌 Power disconnected, finishing charging animation");

                // V3.5: stop the wake loop
                stopWakeupLoop();

                finishChargingAnimation();
            }
        }
    };

    // Debounce: only animate after the charge stays connected for CHARGE_DEBOUNCE_MS
    private static final long CHARGE_DEBOUNCE_MS = 3000;
    // Reconnect within this window after unplug counts as jitter (observed PD drop renegotiation ~2s)
    private static final long FLAP_WINDOW_MS = 5000;
    private long lastPowerDisconnectedTime = 0;
    // Min interval before relaunching in always-on mode (gives an in-flight launch room and avoids relaunch flicker)
    private static final long RELAUNCH_GRACE_MS = 5000;
    // Max watchdog time for a one-shot animation; no more relaunching after that
    private static final long SINGLE_SESSION_GUARD_MS = 30000;
    private long wakeupLoopStartTime = 0;
    // If the relaunched animation is visible for less than this before being removed by the system, count as a failure
    private static final long RELAUNCH_MIN_VISIBLE_MS = 3000;
    // After this many consecutive failures, pause relaunching until the user relights the rear screen; avoids endless HyperOS tug-of-war flicker
    private static final int MAX_FAILED_RELAUNCHES = 2;
    private int failedRelaunches = 0;
    private boolean relaunchSuspended = false;
    private boolean rearWasOnLastTick = false;
    private final Handler debounceHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable pendingChargingRunnable = new Runnable() {
        @Override
        public void run() {
            Context context = ChargingService.this;
            // Recheck after the debounce: must still be charging, otherwise it was just jitter
            if (!isPluggedIn(context)) {
                Log.d(TAG, "⏸ Not charging when debounce ended; ignoring this plug-in");
                return;
            }
            // Check the toggle state
            boolean enabled = prefs.getBoolean("charging_animation_enabled", true);
            if (!enabled) {
                Log.d(TAG, "Charging animation disabled");
                return;
            }

            // Check the cooldown (prevent duplicate triggers)
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastChargingAnimationTime < CHARGING_ANIMATION_COOLDOWN_MS) {
                Log.d(TAG, "⏸ Charging animation in cooldown, skipping");
                return;
            }
            
            // Check the screen lock state
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();
            
            if (isLocked) {
                Log.d(TAG, "🔓 Screen is locked, will show charging animation with screen sleep");
            } else {
                Log.d(TAG, "🔓 Screen is unlocked, will show charging animation without screen sleep");
            }
            
            int batteryLevel = getBatteryLevel(context);
            Log.d(TAG, "🔌 Power connected, battery: " + batteryLevel + "%");

            // Animation manager: start the charging animation (returns the interrupted old one)
            RearAnimationManager.AnimationType oldAnim = RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
            
            // If an old animation must be interrupted, send the interrupt broadcast
            if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "Notification animation playing; sending the interrupt broadcast");
                RearAnimationManager.sendInterruptBroadcast(ChargingService.this, RearAnimationManager.AnimationType.NOTIFICATION);
            }
            
            showChargingOnRearScreen(batteryLevel, isLocked);
            
            // V3.5: start the wake/update loop (in one-shot mode, only guard until this animation finishes)
            Log.d(TAG, "Starting wakeup loop; charging always-on: " + chargingAlwaysOnEnabled);
            startWakeupAndUpdateLoop();
        }
    };

    /** Whether the charger is currently connected (used for the post-debounce recheck). */
    private boolean isPluggedIn(Context context) {
        try {
            Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return i != null && i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to check the charging state: " + t.getMessage());
            return true; // if unreadable, keep original behavior (animate)
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ChargingService created");
        
        // Save the instance
        instance = this;
        
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // Add Shizuku listeners
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);  // listen for unplug events
        registerReceiver(batteryReceiver, filter);
        
        // V3.5: register the settings-change broadcast receiver
        IntentFilter settingsFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_CHARGING_SETTINGS");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
        
        // V3.5: register the resume-charging broadcast receiver
        IntentFilter resumeFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resumeChargingReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resumeChargingReceiver, resumeFilter);
        }
        
        // V3.5: load the charging always-on setting
        chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
        wakeupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // Bind TaskService
        bindTaskService();
        
        // Start as a foreground service (using the unified kernel service notification)
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        Log.d(TAG, "✓ Foreground service started (kernel service notification)");
    }
    
    private static final int NOTIFICATION_ID = 1001; // shared ID with other services

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:ChargingWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                    Log.d(TAG, "🔒 PARTIAL_WAKE_LOCK acquired for " + timeoutMs + "ms");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire wakelock: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "🔓 PARTIAL_WAKE_LOCK released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to release wakelock: " + t.getMessage());
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ChargingService started");
        
        // Ensure TaskService is bound
        if (taskService == null) {
            bindTaskService();
        }
        
        return START_STICKY;
    }
    
    private void bindTaskService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No Shizuku permission");
                return;
            }
            
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            Log.d(TAG, "Binding TaskService...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    private int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
    
    /**
     * End the charging animation immediately.
     */
    private void finishChargingAnimation() {
        try {
            // End it via broadcast to RearScreenChargingActivity
            Intent finishIntent = new Intent("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
            finishIntent.setPackage(getPackageName());
            sendBroadcast(finishIntent);
                Log.d(TAG, "End-charging broadcast sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to finish charging animation", e);
        }
    }
    
    private void showChargingOnRearScreen(int level, boolean isLocked) {
        showChargingOnRearScreenWithRetry(level, isLocked, 0);
    }
    
    private void showChargingOnRearScreenWithRetry(int level, boolean isLocked, int retryCount) {
        if (taskService == null) {
            if (retryCount < 10) {  // retry up to 10 times (1s total)
                Log.w(TAG, "TaskService not available, retry " + (retryCount + 1) + "/10");
                // Retry after a delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    showChargingOnRearScreenWithRetry(level, isLocked, retryCount + 1);
                }, 100);
                return;
            } else {
                Log.e(TAG, "TaskService still not available after 10 retries, aborting");
                return;
            }
        }

        // Only stamp the cooldown after TaskService is confirmed available and the animation will actually play,
        // so a transient TaskService-not-ready failure does not lock out retries for the next 6s
        lastChargingAnimationTime = System.currentTimeMillis();
        RearScreenChargingActivity.resetSelfFinished();

        // Phase 2 (N1): the main thread only checks state and queues; shell/sleep/polling all run on a background thread
        final ITaskService ts = taskService;
        long startTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] Starting the charging animation", startTime, startTime));
        boolean rearWasOn = isRearDisplayOn();

        acquireWakeLock(8000);
        try {
            if (!RearShell.post(() -> runChargingLaunchShell(ts, level, isLocked, rearWasOn, startTime))) {
                releaseWakeLock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing charging", e);
            releaseWakeLock();
        }
    }

    /**
     * Background thread that runs the charging launch shell (wake + main placeholder + poll + move-stack).
     * The only place allowed to Thread.sleep/executeShellCommand; all on the RearShell background thread.
     * When done, releaseWakeLock is posted back to the main thread (state work stays on the main thread).
     */
    private void runChargingLaunchShell(ITaskService ts, int level, boolean isLocked, boolean rearWasOn, long startTime) {
        try {
            // Step 1: check whether an app is cast onto the rear screen
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            int rearTaskId = -1;

            if (lastTask != null && lastTask.contains(":")) {
                try {
                    String rearForegroundApp = ts.getForegroundAppOnDisplay(1);

                    // If the rear foreground is still the charging animation, the previous animation has not fully died; reuse lastTask
                    if (rearForegroundApp != null && rearForegroundApp.contains("RearScreenChargingActivity")) {
                        Log.d(TAG, "Charging animation showing; using lastTask: " + lastTask);
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                    } else if (rearForegroundApp != null && rearForegroundApp.equals(lastTask)) {
                        // An app really is running on the rear screen
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                        Log.d(TAG, "Rear has a cast app: " + lastTask);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check the rear app", e);
                }
            }

            // Step 2: if an app is cast, pause RearScreenKeeperService monitoring
            if (rearTaskId > 0) {
                RearScreenKeeperService.pauseMonitoring();
            }

            // Step 3: disable the official Launcher
            try {
                ts.disableSubScreenLauncher();
            } catch (Throwable t) {
                Log.w(TAG, "disableSubScreenLauncher failed: " + t.getMessage());
            }

            // Step 4: MRSN strategy - launch invisibly on the main screen, then move to the rear
            String componentName = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
            String mainCmd = String.format(
                "am start -n %s --ei batteryLevel %d --ei rearTaskId %d",
                componentName,
                level,
                rearTaskId
            );

            // V3.3: removed all wake/unlock code to avoid jumping to the passcode screen while locked

            // Wake the rear screen first, then launch the Activity, so the animation lands on an already-lit screen.
            // Window flags (FLAG_TURN_SCREEN_ON) do nothing while the rear screen is DOZE/DOZE_SUSPEND;
            // only this command lights it (same as a double-tap wake), consistent with NotificationService.
            // If the rear screen was off, HyperOS pulls SubScreenLauncher to the front and removes our task the moment the wake completes,
            // so wait for the wake (measured ~1.5s) before starting the animation; if the screen is already on, no wait needed.
            try {
                ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                if (!rearWasOn) {
                    Thread.sleep(1500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to wake the rear screen: " + t.getMessage());
            }

            // 4.1: launch on the main screen first (the Activity hides itself in onCreate)
            try {
                Log.d(TAG, String.format("[%tT.%tL] 🔵 Launching the Activity on the main display", System.currentTimeMillis(), System.currentTimeMillis()));
                ts.executeShellCommand(mainCmd);

                // 4.2: poll for the taskId (up to 60 x 30ms = 1800ms; resend the command mid-way)
                String chargingTaskId = null;
                int attempts = 0;
                int maxAttempts = 60;

                while (chargingTaskId == null && attempts < maxAttempts) {
                    Thread.sleep(30);
                    String result = ts.executeShellCommandWithResult(
                        "am stack list | grep -A2 'displayId=0' | grep RearScreenChargingActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        // Parse taskId=XXX
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            chargingTaskId = matcher.group(1);
                            Log.d(TAG, String.format("[%tT.%tL] Found taskId=%s (attempt %d)",
                                System.currentTimeMillis(), System.currentTimeMillis(), chargingTaskId, attempts + 1));
                            break;
                        }
                    }
                    attempts++;
                    if (attempts == 20 || attempts == 40) { // resend the launch command once or twice mid-way
                        Log.d(TAG, String.format("[%tT.%tL] Re-sending the main-screen launch command", System.currentTimeMillis(), System.currentTimeMillis()));
                        ts.executeShellCommand(mainCmd);
                    }
                }

                if (chargingTaskId != null) {
                    // 4.3: move to the rear screen
                    String moveCmd = "am display move-stack " + chargingTaskId + " 1";
                    ts.executeShellCommand(moveCmd);
                    Thread.sleep(40); // wait for the move to complete
                    // A rear screen woken while locked goes dark again by itself after ~1s, already black during the 1.5s wake wait;
                    // wake it once more after the animation lands, then FLAG_KEEP_SCREEN_ON keeps it lit and the system leaves it alone
                    ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");

                    // 4.4: turn off the main screen only when locked (no need when it is already on)
                    if (isLocked) {
                        // Main-screen sleep removed
                        Log.d(TAG, String.format("[%tT.%tL] Locked; main screen off",
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    } else {
                        Log.d(TAG, String.format("[%tT.%tL] Unlocked; keeping the main screen on",
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    }

                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("[%tT.%tL] Charging animation moved to the rear (took %dms)",
                        endTime, endTime, endTime - startTime));
                } else {
                    Log.e(TAG, String.format("[%tT.%tL] Could not find taskId after %d attempts",
                        System.currentTimeMillis(), System.currentTimeMillis(), attempts));
                }
            } catch (Exception e) {
                long errorTime = System.currentTimeMillis();
                Log.e(TAG, String.format("[%tT.%tL] Failed to start the charging animation", errorTime, errorTime), e);
            }
        } finally {
            // Post releaseWakeLock back to the main thread (state/UI work stays on the main thread)
            RearShell.postToMain(() -> releaseWakeLock());
        }
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Stop the foreground service
        stopForeground(true);
        
        // Clear the static instance
        instance = null;

        // Cancel the pending charging animation so the destroyed Service does not keep a reference
        debounceHandler.removeCallbacks(pendingChargingRunnable);

        // Remove Shizuku listeners
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            Log.e(TAG, "Error removing Shizuku listeners", e);
        }
        
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering battery receiver", e);
        }
        
        // V3.5: unregister the settings-change receiver
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering settings receiver", e);
        }
        
        // V3.5: unregister the resume-charging receiver
        try {
            unregisterReceiver(resumeChargingReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering resume charging receiver", e);
        }
        
        // V3.5: stop the wake loop
        stopWakeupLoop();
        
        // Unbind TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding TaskService", e);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    // V3.5: start the wake / battery-update loop
    private void startWakeupAndUpdateLoop() {
        if (isWakeupRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isWakeupRunning = true;
        wakeupLoopStartTime = System.currentTimeMillis();
        failedRelaunches = 0;
        relaunchSuspended = false;
        rearWasOnLastTick = isRearDisplayOn();
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning) return;
                
                // One-shot mode: stop the loop when this animation finished on its own (8s timeout / interrupted) or exceeded the watchdog
                boolean alwaysOn = prefs.getBoolean("charging_always_on_enabled", false);
                if (!alwaysOn && (RearScreenChargingActivity.isSelfFinished()
                        || System.currentTimeMillis() - wakeupLoopStartTime > SINGLE_SESSION_GUARD_MS)) {
                    Log.d(TAG, "One-shot mode; this charging animation finished; stopping the loop");
                    stopWakeupLoop();
                    return;
                }
                
                // Rear screen went from off to on (user double-tap wake); un-pause
                boolean rearOn = isRearDisplayOn();
                if (relaunchSuspended && rearOn && !rearWasOnLastTick) {
                    Log.d(TAG, "Rear screen relit; resuming relaunch");
                    relaunchSuspended = false;
                    failedRelaunches = 0;
                }
                rearWasOnLastTick = rearOn;

                if (RearScreenChargingActivity.isShowing()) {
                    if (System.currentTimeMillis() - RearScreenChargingActivity.getShownSince() > RELAUNCH_MIN_VISIBLE_MS) {
                        failedRelaunches = 0;
                        relaunchSuspended = false;
                    }
                    // Send the wakeup command (keeps the rear screen on only while the animation is visible)
                    if (taskService != null) {
                        final ITaskService ts = taskService;
                        RearShell.post(() -> {
                            try {
                                ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                                Log.d(TAG, "✓ Wakeup sent");
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to send wakeup: " + t.getMessage());
                            }
                        });
                    } else {
                        Log.w(TAG, "⚠️ TaskService is null, skipping wakeup");
                    }
                } else if (rearOn && !relaunchSuspended && isPluggedIn(getApplicationContext())
                        && System.currentTimeMillis() - lastChargingAnimationTime > RELAUNCH_GRACE_MS) {
                    // Animation no longer on the rear (swiped home / removed after sleep / not restored after a notification);
                    // if the rear screen is lit, relaunch it - consistent with the media page auto-recovery. If it is off, do not wake; wait for the user's double-tap.
                    RearAnimationManager.AnimationType current = RearAnimationManager.getCurrentAnimation();
                    if (alwaysOn && RearScreenChargingActivity.getLastVisibleMs() < RELAUNCH_MIN_VISIBLE_MS
                            && ++failedRelaunches >= MAX_FAILED_RELAUNCHES) {
                        Log.d(TAG, "⏸️ Relaunches keep getting removed by the system; pausing until the rear screen is relit");
                        relaunchSuspended = true;
                    } else if (current == RearAnimationManager.AnimationType.NONE
                            || current == RearAnimationManager.AnimationType.CHARGING) {
                        Log.d(TAG, "🔁 Charging animation not visible and the rear is lit; relaunching");
                        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                        showChargingOnRearScreen(getBatteryLevel(getApplicationContext()), km != null && km.isKeyguardLocked());
                    }
                }
                
                // Update the charging animation's battery level
                try {
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    // Update the battery via the static method directly
                    RearScreenChargingActivity.updateBatteryLevelStatic(batteryLevel);
                    Log.d(TAG, "🔋 Battery directly updated: " + batteryLevel + "%");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to update the battery level: " + e.getMessage());
                }
                
                // Continue after 100ms
                wakeupHandler.postDelayed(this, 2000);
            }
        };
        
        // Start immediately
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup and update loop started");
    }
    
    private boolean isRearDisplayOn() {
        android.view.Display rearDisplay = ((android.hardware.display.DisplayManager)
                getSystemService(Context.DISPLAY_SERVICE)).getDisplay(1);
        return rearDisplay != null && rearDisplay.getState() == android.view.Display.STATE_ON;
    }

    // V3.5: stop the wake loop
    private void stopWakeupLoop() {
        isWakeupRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }
}

