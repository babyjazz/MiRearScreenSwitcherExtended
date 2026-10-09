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
import android.os.Bundle;
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
    
    // Static instance, accessible from other rear-screen classes
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
    
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                // Reconnect within FLAP_WINDOW_MS after unplug = PD drop/renegotiation (a bad port does this roughly every 40s), not a real plug-in.
                // A pending unplug is cancelled so a charging animation already on screen survives the flap; no new animation is started.
                debounceHandler.removeCallbacks(pendingUnplugRunnable);
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

                // Debounced like connect: a PD flap reconnects within a couple of seconds and must not end the animation
                debounceHandler.removeCallbacks(pendingUnplugRunnable);
                debounceHandler.postDelayed(pendingUnplugRunnable, UNPLUG_DEBOUNCE_MS);
                Log.d(TAG, "🔌 Power disconnected, debouncing " + UNPLUG_DEBOUNCE_MS + "ms");
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
    private static final long UNPLUG_DEBOUNCE_MS = 3000;
    private final Handler debounceHandler = new Handler(android.os.Looper.getMainLooper());
    // Confirmed unplug: end the charging experience and cancel every pending retry
    private final Runnable pendingUnplugRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPluggedIn(ChargingService.this)) {
                Log.d(TAG, "⏸ Still plugged in after unplug debounce; ignoring");
                return;
            }
            Log.d(TAG, "🔌 Power disconnected, finishing charging animation");
            // Unplug confirmed: a reconnect after this is a fresh plug-in, not a flap
            lastPowerDisconnectedTime = 0;
            debounceHandler.removeCallbacksAndMessages(null);
            stopWakeupLoop();
            RearHost.hide(ChargingService.this, RearStack.Type.CHARGING);
        }
    };
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

            showChargingOnRearScreen(batteryLevel);
            
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
    
    private void showChargingOnRearScreen(int level) {
        showChargingOnRearScreenWithRetry(level, 0);
    }

    private void showChargingOnRearScreenWithRetry(int level, int retryCount) {
        if (taskService == null) {
            if (retryCount < 10 && isPluggedIn(this)) {  // retry up to 10 times (1s total), only while still plugged in
                Log.w(TAG, "TaskService not available, retry " + (retryCount + 1) + "/10");
                debounceHandler.postDelayed(() -> showChargingOnRearScreenWithRetry(level, retryCount + 1), 100);
                return;
            }
            Log.e(TAG, "TaskService not available, aborting");
            return;
        }
        if (!prefs.getBoolean("charging_animation_enabled", true)) return;

        // Only stamp the cooldown after TaskService is confirmed available and the animation will actually play,
        // so a transient TaskService-not-ready failure does not lock out retries for the next 6s
        lastChargingAnimationTime = System.currentTimeMillis();

        Bundle payload = new Bundle();
        payload.putInt("batteryLevel", level);
        RearHost.show(this, RearStack.Type.CHARGING, payload);
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
                if (!alwaysOn && (RearHost.isChargingSelfFinished()
                        || System.currentTimeMillis() - wakeupLoopStartTime > SINGLE_SESSION_GUARD_MS)) {
                    Log.d(TAG, "One-shot mode; this charging animation finished; stopping the loop");
                    stopWakeupLoop();
                    return;
                }
                
                if (!prefs.getBoolean("charging_animation_enabled", true)) {
                    Log.d(TAG, "Charging animation disabled; stopping the loop");
                    stopWakeupLoop();
                    RearHost.hide(ChargingService.this, RearStack.Type.CHARGING);
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

                if (RearHost.isShowing()) {
                    if (System.currentTimeMillis() - RearHost.getShownSince() > RELAUNCH_MIN_VISIBLE_MS) {
                        failedRelaunches = 0;
                        relaunchSuspended = false;
                    }
                    // Send the wakeup command (keeps the rear screen on only while the animation is visible)
                    try {
                        if (taskService != null) {
                            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                            Log.d(TAG, "✓ Wakeup sent");
                        } else {
                            Log.w(TAG, "⚠️ TaskService is null, skipping wakeup");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to send wakeup: " + t.getMessage());
                    }
                } else if (rearOn && !relaunchSuspended && isPluggedIn(getApplicationContext())
                        && System.currentTimeMillis() - lastChargingAnimationTime > RELAUNCH_GRACE_MS) {
                    // Animation no longer on the rear (swiped home / removed after sleep / not restored after a notification);
                    // if the rear screen is lit, relaunch it - consistent with the media page auto-recovery. If it is off, do not wake; wait for the user's double-tap.
                    if (alwaysOn && RearHost.getLastVisibleMs() < RELAUNCH_MIN_VISIBLE_MS
                            && ++failedRelaunches >= MAX_FAILED_RELAUNCHES) {
                        Log.d(TAG, "⏸️ Relaunches keep getting removed by the system; pausing until the rear screen is relit");
                        relaunchSuspended = true;
                    } else if (RearStack.top() == RearStack.Type.CHARGING) {
                        Log.d(TAG, "🔁 Charging animation not visible and the rear is lit; relaunching");
                        showChargingOnRearScreen(getBatteryLevel(getApplicationContext()));
                    }
                }
                
                // Update the charging animation's battery level
                try {
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    // Update the battery via the static method directly
                    RearHost.updateBattery(batteryLevel);
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

