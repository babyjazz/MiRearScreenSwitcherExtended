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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Rear-screen charging animation Activity.
 * Shows the charging icon, battery percentage, and progress bar; auto-closes after 5s and restores the cast app or the official Launcher.
 */
public class RearScreenChargingActivity extends Activity {
    private static final String TAG = "RearScreenChargingActivity";
    private int rearTaskId = -1;  // taskId of the app cast to the rear, -1 = no cast app
    private boolean autoFinishScheduled = false; // whether auto-destroy is scheduled
    private View chargingContainer; // root View of the rear content; used to schedule/cancel the auto-close
    private Runnable pendingFinishRunnable; // currently queued auto-close task; cancel the old one when reusing an instance
    
    // Static instance tracking; prevents stale instances from interfering with new ones
    private static volatile RearScreenChargingActivity currentInstance = null;
    private static volatile long currentInstanceCreateTime = 0;
    // Whether the charging animation is currently visible (false while rear sleeps / swiped home / removed by system); ChargingService uses this in always-on mode to decide whether to relaunch
    private static volatile boolean showing = false;

    public static boolean isShowing() {
        return showing;
    }

    // Last time it became visible; ChargingService uses this to detect relaunches being quickly removed by the system
    private static volatile long shownSince = 0;

    public static long getShownSince() {
        return shownSince;
    }

    // How long the last visible period lasted (onStart to onStop)
    private static volatile long lastVisibleMs = Long.MAX_VALUE;

    public static long getLastVisibleMs() {
        return lastVisibleMs;
    }

    // Whether the animation ended on its own (8s timeout / unplug / interrupted by notification), as opposed to being removed by the system or swiped home;
    // in one-shot mode ChargingService uses this to decide the round played fully and skips relaunching
    private static volatile boolean selfFinished = false;

    public static boolean isSelfFinished() {
        return selfFinished;
    }

    public static void resetSelfFinished() {
        selfFinished = false;
    }

    private void finishBySelf() {
        // A stale instance removed by the system must not let its leftover timer mark the new animation as ended
        if (isFinishing() || isDestroyed()) return;
        selfFinished = true;
        finish();
    }

    // Static battery update, called directly by ChargingService
    public static void updateBatteryLevelStatic(int newLevel) {
        if (currentInstance != null) {
            currentInstance.updateBatteryLevel(newLevel);
        }
    }
    
    // Broadcast receiver: end-now commands and battery updates
    private android.content.BroadcastReceiver finishReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if ("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔌 Unplug broadcast received; destroying now");
                finishBySelf();
            } else if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔄 Interrupt broadcast received (new animation); destroying without restoring the Launcher");
                // Mark as interrupted; onDestroy must not restore the Launcher
                finishBySelf();
            } else if ("com.tgwgroup.MiRearScreenSwitcher.UPDATE_CHARGING_BATTERY".equals(action)) {
                // V3.5: battery update
                int newLevel = intent.getIntExtra("batteryLevel", -1);
                Log.d(TAG, "📡 Battery update broadcast: " + newLevel + "%");
                if (newLevel >= 0) {
                    updateBatteryLevel(newLevel);
                }
            }
        }
    };
    
    public RearScreenChargingActivity() {
        super();
        long time = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 Constructor called", time, time));
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long onCreateStartTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟡 onCreate start", onCreateStartTime, onCreateStartTime));
        
        super.onCreate(savedInstanceState);
        
        // Determine the current screen
        int displayId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        Log.d(TAG, String.format("[%tT.%tL] 📍 displayId=%d", onCreateStartTime, onCreateStartTime, displayId));
        
        int level = getIntent().getIntExtra("batteryLevel", 0);
        rearTaskId = getIntent().getIntExtra("rearTaskId", -1);
        
        // ✅ If on the main display (displayId == 0), do nothing and wait to be moved to the rear
        if (displayId == 0) {
            Log.d(TAG, String.format("[%tT.%tL] 💤 Started on the main display; staying a transparent placeholder until moved", 
                onCreateStartTime, onCreateStartTime));
            return; // no content, no flags; just a transparent placeholder
        }
        
        // --- Code below only runs on the rear display (displayId == 1) ---
        Log.d(TAG, String.format("[%tT.%tL] 🎯 On the rear display; setting up content", onCreateStartTime, onCreateStartTime));
        
        // V3.3: keep the screen on + show when locked
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // Adapt to the new API: show when locked
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // V3.5: render performance (fixes DequeueBuffer timeouts)
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        
        // V3.16: removed the 120Hz override; the system manages the refresh rate
        
        // ⚠️ Critical: force the rear DPI before setContentView!
        forceRearScreenDensityBeforeInflate();
        
        setContentView(R.layout.activity_rear_screen_charging);
        
        long afterSetContentViewTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟠 setContentView done", 
            afterSetContentViewTime, afterSetContentViewTime));
        
        
        long afterGetIntentTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ⚡ Intent data: Battery=%d%%, rearTaskId=%d",
            afterGetIntentTime, afterGetIntentTime, level, rearTaskId));

        chargingContainer = findViewById(R.id.charging_container);

        applyChargingState(level);

        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✅ onCreate done (took %dms)", 
            onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
        
        // Register broadcast receivers (unplug, interrupt, and battery update)
        android.content.IntentFilter finishFilter = new android.content.IntentFilter();
        finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
        finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION");
        finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.UPDATE_CHARGING_BATTERY");  // V3.5: battery updates
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, finishFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, finishFilter);
        }
        
        // Register LocalBroadcastManager receivers (battery updates)
        // androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(finishReceiver, finishFilter);
        Log.d(TAG, String.format("[%tT.%tL] ✅ Charging broadcast receivers registered", onCreateEndTime, onCreateEndTime));
        Log.d(TAG, "📡 Broadcast receivers registered: FINISH_CHARGING_ANIMATION, INTERRUPT_CHARGING_ANIMATION, UPDATE_CHARGING_BATTERY");
        
        // Set as the current instance
        currentInstance = this;
        currentInstanceCreateTime = onCreateEndTime;
        
        // Test code removed
    }

    /**
     * With android:launchMode="singleInstance": if the previous animation has not finished() (always-on mode / killed without cleanup),
     * a new charge event does not trigger onCreate; the existing instance is reused via onNewIntent.
     * The battery/animation state must be reapplied here or it silently shows nothing.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        int level = intent.getIntExtra("batteryLevel", 0);
        rearTaskId = intent.getIntExtra("rearTaskId", -1);
        autoFinishScheduled = false;

        int displayId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
        }
        Log.d(TAG, String.format("🔁 onNewIntent reusing instance: displayId=%d, battery=%d%%", displayId, level));

        if (displayId != 1 || chargingContainer == null) {
            // Still a main-screen placeholder or content not yet initialized; rely on the onResume compensation after the move
            return;
        }

        applyChargingState(level);
    }

    /**
     * Show/reset the charging content: set the battery level, start the liquid-fill animation, and schedule (or skip) the auto-close.
     * Shared by the first onCreate display and onNewIntent instance reuse.
     */
    private void applyChargingState(int level) {
        LightningShapeView fullScreenLiquid = findViewById(R.id.full_screen_liquid);
        TextView batteryText = findViewById(R.id.battery_text);

        fullScreenLiquid.setFullScreenMode(true);
        applySafeAreaToText(batteryText);
        batteryText.setText(level + "%");

        startFullScreenLiquidAnimation(fullScreenLiquid, level);
        startCenterTextAnimation(batteryText);

        // Cancel any still-queued auto-close from the previous round so it cannot kill the new animation early
        if (pendingFinishRunnable != null) {
            chargingContainer.removeCallbacks(pendingFinishRunnable);
            pendingFinishRunnable = null;
        }

        boolean chargingAlwaysOn = getSharedPreferences("mrss_settings", MODE_PRIVATE)
            .getBoolean("charging_always_on_enabled", false);

        if (chargingAlwaysOn) {
            Log.d(TAG, "🎬 Animation started; charging always-on mode, no auto-close");
        } else {
            Log.d(TAG, "🎬 Animation started; auto-close in 8s");
            pendingFinishRunnable = this::finishBySelf;
            chargingContainer.postDelayed(pendingFinishRunnable, 8000);
        }
        autoFinishScheduled = true;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // When move-stack lands on the rear (configChanges include density/screenSize), this runs; onResume may not
        recreateIfMovedToRear();
    }

    /**
     * After the main-screen placeholder is move-stacked to the rear, onCreate already returned early (no content, no unplug receiver),
     * so recreate on the rear via the full onCreate rear branch.
     */
    private boolean recreateIfMovedToRear() {
        if (chargingContainer != null || isFinishing() || getDisplay() == null || getDisplay().getDisplayId() != 1) {
            return false;
        }
        Log.d(TAG, "🔄 Placeholder instance reached the rear; recreating content");
        recreate();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume", resumeTime, resumeTime));

        if (recreateIfMovedToRear()) return;

        // V3.3: re-assert window flags (keep on + show when locked)
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // Ensure the show-when-locked setting keeps applying
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        // Compensation: if the main-screen placeholder skipped auto-destroy, schedule it on rear resume
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            if (displayId == 1 && !autoFinishScheduled) {
                // V3.5: check the charging always-on toggle
                boolean chargingAlwaysOn = getSharedPreferences("mrss_settings", MODE_PRIVATE)
                    .getBoolean("charging_always_on_enabled", false);
                
                if (!chargingAlwaysOn) {
                    Log.d(TAG, "⏱️ Auto-destroy not scheduled; compensating with a 5s finish");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finishBySelf, 5000);
                } else {
                    Log.d(TAG, "💡 Charging always-on; no auto-destroy");
                }
                autoFinishScheduled = true;
            }
        }
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        showing = true;
        shownSince = System.currentTimeMillis();
        // With no interaction, the system sleeps the device after ~1s and pulls SubScreenLauncher to the front, removing us;
        // waking the rear again only after the animation is actually visible keeps it lit (waking right after move-stack is too early and does nothing)
        if (getDisplay() != null && getDisplay().getDisplayId() == 1) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!showing) return;
                ITaskService ts = ChargingService.getTaskService();
                if (ts == null) ts = NotificationService.getTaskService();
                try {
                    if (ts != null) ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to wake the rear after becoming visible: " + t.getMessage());
                }
            }, 500);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        showing = false;
        lastVisibleMs = System.currentTimeMillis() - shownSince;
    }

    @Override
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy called", destroyTime, destroyTime));
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(finishReceiver);
            Log.d(TAG, String.format("[%tT.%tL] ✅ Charging broadcast receivers unregistered", destroyTime, destroyTime));
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister finish receiver: " + e.getMessage());
        }
        
        // Unregister LocalBroadcastManager receivers
        // try {
        //     androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(finishReceiver);
        //     Log.d(TAG, String.format("[%tT.%tL] ✅ LocalBroadcastManager receivers unregistered", destroyTime, destroyTime));
        // } catch (Exception e) {
        //     Log.w(TAG, "Failed to unregister LocalBroadcastManager receiver: " + e.getMessage());
        // }
        
        super.onDestroy();
        
        // Check whether this is the current instance; protects the new instance from stale ones
        if (this != currentInstance) {
            Log.w(TAG, String.format("[%tT.%tL] ⚠️ Stale instance; skipping restore", destroyTime, destroyTime));
            return;
        }
        // Clear the static reference so a destroyed instance (and its View tree) is not held forever by static fields (memory leak)
        currentInstance = null;

        // Animation manager: charging animation ended
        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING);
        
        // Restore the Launcher only on a normal end; not when interrupted
        if (!shouldRestore) {
            Log.d(TAG, String.format("[%tT.%tL] 🔄 Charging animation interrupted; skipping Launcher restore", destroyTime, destroyTime));
            return;
        }
        
        // On the rear, restore the cast app or the official Launcher (only when on the rear)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            Log.d(TAG, String.format("[%tT.%tL] 📍 displayId=%d", destroyTime, destroyTime, currentDisplayId));
            
            if (currentDisplayId == 1) {
                final int finalTaskId = rearTaskId;
                
                // Run the restore on a background thread; do not block onDestroy
                new Thread(() -> {
                    try {
                        // Wait 50ms for the Activity to fully destroy
                        Thread.sleep(50);
                        
                        if (finalTaskId > 0) {
                            Log.d(TAG, "⚡ Restoring the cast app (taskId=" + finalTaskId + ")");
                            restoreProjectedApp(finalTaskId);
                        } else {
                            Log.d(TAG, "⚡ Restoring the official Launcher");
                            restoreOfficialLauncher();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in restore thread", e);
                    }
                }).start();
            }
        }
    }
    
    private void restoreProjectedApp(int taskId) {
        try {
            // Get the TaskService from ChargingService and restore the cast app
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                // Step 1: disable the official Launcher first (so it does not grab the rear screen)
                taskService.disableSubScreenLauncher();
                
                // Step 2: wait 200ms for the system to settle (extra delay)
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                
                // Step 3: move the cast app back to the rear screen
                taskService.executeShellCommand(
                    "am display move-stack " + taskId + " 1"
                );
                
                // Step 4: wait another 200ms to confirm the move
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                
                // Step 5: re-verify the move (belt and suspenders)
                taskService.executeShellCommand(
                    "am display move-stack " + taskId + " 1"
                );
                
                // Step 6: wait 300ms for the app to fully show
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                
                // Step 7: do not enable the official Launcher (keep it disabled so the cast app keeps the rear screen)
                // taskService.enableSubScreenLauncher(); // ❌ do not enable; it will grab the rear screen
                
                // Step 8: restart RearScreenKeeperService to monitor the restored app
                restartKeeperService(taskId);
                
                Log.d(TAG, "✅ Projected app restored (taskId=" + taskId + ")");
            } else {
                Log.w(TAG, "TaskService not available from ChargingService");
                // Fall back to MainActivity
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                        "am display move-stack " + taskId + " 1"
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore projected app", e);
            // If restoring the cast app failed, restore monitoring and fall back to the official Launcher
            RearScreenKeeperService.resumeMonitoring();
            restoreOfficialLauncher();
        }
    }
    
    private void restartKeeperService(int taskId) {
        try {
            // Get the package name and taskId
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            if (lastTask != null) {
                // Start RearScreenKeeperService
                Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
                serviceIntent.putExtra("lastMovedTask", lastTask);
                
                // V2.5: pass the rear-screen always-on toggle state
                try {
                    android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                    boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
                } catch (Exception e) {
                    // Default: on
                    serviceIntent.putExtra("keepScreenOnEnabled", true);
                }
                
                startService(serviceIntent);
                
                Log.d(TAG, "🔄 RearScreenKeeperService restarted for: " + lastTask);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart RearScreenKeeperService", e);
        }
    }
    
    private void restoreOfficialLauncher() {
        try {
            // Get the TaskService from ChargingService and restore the official Launcher
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                taskService.executeShellCommand(
                    "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                );
                Log.d(TAG, "✅ Official launcher restored");
            } else {
                Log.w(TAG, "TaskService not available from ChargingService");
                // Fall back to MainActivity
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                        "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore official launcher", e);
        }
    }
    
    /**
     * Force the rear-screen DPI before inflating the layout.
     */
    private void forceRearScreenDensityBeforeInflate() {
        try {
            // Get the rear DPI from the cache (works across Xiaomi dual-screen devices)
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;
            
            // If the cache is not initialized, run dumpsys now for the real DPI
            if (rearScreenDpi <= 0) {
                Log.w(TAG, "⚠️ Rear DPI not cached; fetching live");
                
                // Try to obtain TaskService (with retries)
                ITaskService taskService = null;
                for (int retry = 0; retry < 3; retry++) {
                    taskService = ChargingService.getTaskService();
                    if (taskService == null) {
                        taskService = NotificationService.getTaskService();
                    }
                    
                    if (taskService != null) {
                        break;
                    }
                    
                    Log.w(TAG, String.format("⏳ TaskService not connected; retry %d/3", retry + 1));
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                if (taskService != null) {
                    try {
                        DisplayInfoCache.getInstance().initialize(taskService);
                        info = DisplayInfoCache.getInstance().getCachedInfo();
                        rearScreenDpi = info.densityDpi;
                        Log.d(TAG, "✅ Live rear DPI: " + rearScreenDpi);
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to fetch the rear DPI live", e);
                        return;
                    }
                } else {
                    Log.e(TAG, "❌ TaskService still unavailable after 3 retries; skipping DPI force");
                    return;
                }
            }
            
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 Before inflate - current DPI=%d, rear DPI=%d", currentDpi, rearScreenDpi));
            
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;
            
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            
            getResources().updateConfiguration(config, metrics);
            
            Log.d(TAG, String.format("✅ Forced rear DPI before inflate: %d", metrics.densityDpi));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply DPI before inflate", e);
        }
    }
    
    /**
     * V3.5: fullscreen liquid-fill animation (non-linear, from 0 to the target battery level).
     */
    private void startFullScreenLiquidAnimation(LightningShapeView liquidView, int targetLevel) {
        // Target fill ratio
        float targetFillLevel = targetLevel / 100f;
        
        // Non-linear fill animation (DecelerateInterpolator - deceleration)
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, targetFillLevel);
        animator.setDuration(2000); // 2s fill animation
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f));
        
        animator.addUpdateListener(animation -> {
            float animatedValue = (float) animation.getAnimatedValue();
            liquidView.setFillLevel(animatedValue);
        });
        
        animator.start();
        Log.d(TAG, String.format("🌊 Fullscreen liquid fill started: 0%% → %d%%", targetLevel));
    }
    
    /**
     * V3.5: center battery-number fade-in.
     */
    private void startCenterTextAnimation(TextView textView) {
        textView.setAlpha(0f);
        textView.setScaleX(0.8f);
        textView.setScaleY(0.8f);
        
        textView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(600) // appear after the liquid fill starts
            .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
            .start();
    }
    
    /**
     * V3.5: update the battery display (live updates in always-on mode).
     */
    private void updateBatteryLevel(int newLevel) {
        try {
            Log.d(TAG, "🔋 Updating battery: " + newLevel + "%");
            LightningShapeView liquidView = findViewById(R.id.full_screen_liquid);
            TextView batteryText = findViewById(R.id.battery_text);
            
            if (liquidView != null && batteryText != null) {
                // Smoothly update the liquid fill
                liquidView.setFillLevel(newLevel / 100f);
                // Update the number
                batteryText.setText(newLevel + "%");
                Log.d(TAG, "🔋 Battery updated: " + newLevel + "%");
            } else {
                Log.w(TAG, "⚠️ Views not found; cannot update battery - liquidView=" + (liquidView != null) + ", batteryText=" + (batteryText != null));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update the battery: " + e.getMessage());
        }
    }
    
    /**
     * V3.5: apply the safe area to the battery number (keeps it centered in the safe area).
     */
    private void applySafeAreaToText(TextView textView) {
        try {
            // Get the rear info from the cache
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            
            if (info == null) {
                Log.w(TAG, "⚠️ Rear info cache is empty");
                return;
            }
            
            if (!info.hasCutout()) {
                Log.d(TAG, "ℹ️ No rear cutout; number is centered automatically");
                return;
            }
            
            // Set margins to center the number in the safe area
            if (textView.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params = 
                    (android.widget.FrameLayout.LayoutParams) textView.getLayoutParams();
                
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                textView.setLayoutParams(params);
                
                Log.d(TAG, String.format("✅ Battery number safe-area applied: left=%d, top=%d, right=%d, bottom=%d",
                    info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply the safe area", e);
        }
    }
}

