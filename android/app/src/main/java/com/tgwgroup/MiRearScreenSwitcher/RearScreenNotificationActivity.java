/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 *
 * Chief Tester: 汐木泽 (Xi Mu Ze)
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Rear-screen notification Activity.
 * Shows the app icon, name, and notification content with non-linear animations.
 */
public class RearScreenNotificationActivity extends Activity {
    private static final String TAG = "RearScreenNotificationActivity";
    
    // Static instance tracking
    private static volatile RearScreenNotificationActivity currentInstance = null;
    
    private String packageName;
    private boolean contentInitialized = false;  // whether the content is initialized
    private boolean interrupted = false;         // interrupted by a new animation (the rear was taken over)

    // Broadcast receiver: interrupt commands
    private android.content.BroadcastReceiver interruptReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔄 Interrupt broadcast received (new animation); destroying without restoring the Launcher");
                // The new animation has taken the rear: onDestroy must not endAnimation/restore the Launcher, or it clears the new animation's state and fights it for the screen
                interrupted = true;
                finish();
            }
        }
    };
    
    public RearScreenNotificationActivity() {
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
        
        // Get the Intent data
        packageName = getIntent().getStringExtra("packageName");
        String title = getIntent().getStringExtra("title");
        String text = getIntent().getStringExtra("text");
        long when = getIntent().getLongExtra("when", System.currentTimeMillis());
        boolean darkMode = getIntent().getBooleanExtra("darkMode", false);
        
        // ⚠️ Critical: force the rear DPI before setContentView
        forceRearScreenDensityBeforeInflate();
        
        // ✅ Unified layout setup so the main-placeholder -> rear move renders correctly
        setContentView(R.layout.activity_rear_screen_notification);
        
        // Apply dark mode or the regular layout adjustments
        if (darkMode) {
            applyDarkMode();
        } else {
            applyRegularLayout();
        }
        
        // V3.2: keep the screen on + show when locked
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // Adapt to the new API: show when locked
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // Set the window background to avoid white showing when swiped home
        getWindow().setBackgroundDrawableResource(R.drawable.bg_gradient_rear_screen);
        
        // If launched on the main display, it is only a placeholder: hide the content and wait for the move to the rear
        if (displayId == 0) {
            Log.d(TAG, String.format("[%tT.%tL] 💤 Started on the main display (placeholder); hiding content until moved", 
                onCreateStartTime, onCreateStartTime));
            View container = findViewById(R.id.notification_container);
            container.setVisibility(View.INVISIBLE);
            // Mark the content uninitialized; onResume initializes it on the rear
            contentInitialized = false;
            Log.d(TAG, String.format("[%tT.%tL] ⏸️ Placeholder mode, contentInitialized=false", 
                onCreateStartTime, onCreateStartTime));
            
            // Register broadcast receivers (needed even for a placeholder)
            android.content.IntentFilter interruptFilter = new android.content.IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(interruptReceiver, interruptFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(interruptReceiver, interruptFilter);
            }
            currentInstance = this;
            return;
        }
        
        // --- Code below only runs on the rear display ---
        Log.d(TAG, String.format("[%tT.%tL] 🎯 On the rear display; setting up content", onCreateStartTime, onCreateStartTime));
        
        contentInitialized = true;
        
        // Get the rear info and apply safe-area padding
        applySafeAreaPadding();
        
        // Get the views
        ImageView appIconCenter = findViewById(R.id.app_icon_center);
        ImageView appIconSmall = findViewById(R.id.app_icon_small);
        TextView appNameText = findViewById(R.id.app_name);
        TextView notificationTitle = findViewById(R.id.notification_title);
        TextView notificationContent = findViewById(R.id.notification_content);
        View container = findViewById(R.id.notification_container);
        View appNameContainer = findViewById(R.id.app_name_container);
        View contentContainer = findViewById(R.id.notification_content_container);
        
        // Load the app info
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            String appName = pm.getApplicationLabel(appInfo).toString();
            Drawable icon = pm.getApplicationIcon(packageName);
            
            // Set the icon and app name
            appIconCenter.setImageDrawable(icon);
            appIconSmall.setImageDrawable(icon);
            appNameText.setText(appName);
            
            // Set the title and content
            if (title != null && !title.isEmpty()) {
                notificationTitle.setText(title);
                notificationTitle.setVisibility(View.VISIBLE);
            } else {
                notificationTitle.setVisibility(View.GONE);
            }
            
            if (text != null && !text.isEmpty()) {
                notificationContent.setText(text);
                notificationContent.setVisibility(View.VISIBLE);
            } else {
                notificationContent.setVisibility(View.GONE);
            }
            
            // Hide the spacing if the title is empty
            if (title == null || title.isEmpty()) {
                notificationContent.setPadding(
                    notificationContent.getPaddingLeft(),
                    0,
                    notificationContent.getPaddingRight(),
                    notificationContent.getPaddingBottom()
                );
            }
            
            Log.d(TAG, String.format("[%tT.%tL] 📱 Notification: %s - %s: %s", 
                onCreateStartTime, onCreateStartTime, appName, title, text));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app info", e);
            appNameText.setText(packageName);
            notificationTitle.setText(title);
            notificationContent.setText(text);
        }
        
        // Start the animation
        startNotificationAnimation(appIconCenter, appNameContainer, contentContainer);
        
        // Click opens the app (prefer launching on the main display)
        container.setOnClickListener(v -> {
            long clickTime = System.currentTimeMillis();
            Log.d(TAG, String.format("[%tT.%tL] 👆 Click received; preparing to open package=%s", clickTime, clickTime, packageName));
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                Log.d(TAG, String.format("[%tT.%tL] 🔍 getLaunchIntentForPackage -> %s", clickTime, clickTime, (launchIntent == null ? "null" : String.valueOf(launchIntent.getComponent()))));
                if (launchIntent == null) {
                    Log.w(TAG, String.format("[%tT.%tL] ⚠️ Could not get the launch Intent: %s", clickTime, clickTime, packageName));
                    finish();
                    return;
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                boolean started = false;
                // Try to launch directly on the main display (ActivityOptions -> display=0)
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        // setLaunchDisplayId works on some ROMs; if unavailable it throws and we fall back
                        java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                        m.invoke(opts, 0);
                        Log.d(TAG, String.format("[%tT.%tL] 🚀 Trying ActivityOptions launch on display=0 (main)", clickTime, clickTime));
                        // Light up the main screen
                        try {
                            ITaskService tsWake = NotificationService.getTaskService();
                            if (tsWake != null) {
                                tsWake.executeShellCommand("// main-screen wake removed");
                                Log.d(TAG, String.format("[%tT.%tL] ✓ Woke the main screen", clickTime, clickTime));
                            }
                        } catch (Throwable ignored) {}
                        startActivity(launchIntent, opts.toBundle());
                        started = true;
                        Log.d(TAG, String.format("[%tT.%tL] ✓ ActivityOptions main launch succeeded", clickTime, clickTime));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, String.format("[%tT.%tL] 🔁 ActivityOptions unavailable; falling back: %s", clickTime, clickTime, t.getMessage()));
                }

                if (!started) {
                    // Fallback: launch on the main display via shell
                    try {
                        String component = null;
                        if (launchIntent.getComponent() != null) {
                            component = launchIntent.getComponent().flattenToShortString();
                        }
                        if (component == null) {
                            // Resolve the default LAUNCHER Activity
                            android.content.pm.PackageManager pm = getPackageManager();
                            Intent resolve = new Intent(Intent.ACTION_MAIN);
                            resolve.addCategory(Intent.CATEGORY_LAUNCHER);
                            resolve.setPackage(packageName);
                            android.content.pm.ResolveInfo ri = pm.resolveActivity(resolve, 0);
                            if (ri != null && ri.activityInfo != null) {
                                component = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                            }
                        }
                        Log.d(TAG, String.format("[%tT.%tL] 🧭 Resolved component: %s", clickTime, clickTime, String.valueOf(component)));
                        if (component != null) {
                            ITaskService ts = NotificationService.getTaskService();
                            if (ts != null) {
                                // Wake the main screen first
                                try {
                                    ts.executeShellCommand("// main-screen wake removed");
                                    Log.d(TAG, String.format("[%tT.%tL] ✓ Woke the main screen", clickTime, clickTime));
                                } catch (Throwable ignored) {}
                                String cmd = "am start --display 0 -n " + component;
                                boolean ok = ts.executeShellCommand(cmd);
                                if (!ok) {
                                    // Some ROMs do not support --display 0; degrade to the default display
                                    cmd = "am start -n " + component;
                                    ok = ts.executeShellCommand(cmd);
                                    Log.d(TAG, String.format("[%tT.%tL] 🔁 Launching on the default display; result=%s", clickTime, clickTime, ok));
                                }
                                Log.d(TAG, String.format("[%tT.%tL] ✓ Fallback shell main launch %s component=%s", clickTime, clickTime, ok ? "succeeded" : "failed", component));
                                started = ok;
                            } else {
                                Log.w(TAG, String.format("[%tT.%tL] ⚠️ TaskService is null; cannot launch via shell", clickTime, clickTime));
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, String.format("[%tT.%tL] Fallback shell start error: %s", clickTime, clickTime, t.getMessage()));
                    }
                }

                Log.d(TAG, String.format("[%tT.%tL] 🧹 Ending notification Activity, started=%s", clickTime, clickTime, started));
                // Always end the current notification Activity
                finish();
            } catch (Exception e) {
                Log.e(TAG, String.format("[%tT.%tL] ❌ Launch failed: %s", clickTime, clickTime, e.getMessage()), e);
                finish();
            }
        });
        
        // V3.4: auto-close after the configured duration
        int duration = getSharedPreferences("mrss_settings", MODE_PRIVATE).getInt("notification_duration", 10);
        container.postDelayed(this::finish, duration * 1000L);
        
        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✓ onCreate done (took %dms)", 
            onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
        
        // Register broadcast receivers (interrupt events)
        android.content.IntentFilter interruptFilter = new android.content.IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(interruptReceiver, interruptFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(interruptReceiver, interruptFilter);
        }
        Log.d(TAG, String.format("[%tT.%tL] ✓ Notification animation broadcast receivers registered", onCreateEndTime, onCreateEndTime));
        
        // Set as the current instance
        currentInstance = this;
    }
    
    /**
     * Run the notification animation.
     * 1. large icon scales in from the center
     * 2. app-name frosted-glass container fades in
     * 3. notification-content frosted-glass container fades in
     */
    private void startNotificationAnimation(ImageView iconCenter, View appNameContainer, View contentContainer) {
        // Initial state
        appNameContainer.setAlpha(0f);
        appNameContainer.setScaleX(0.9f);
        appNameContainer.setScaleY(0.9f);
        contentContainer.setAlpha(0f);
        contentContainer.setTranslationY(30f);
        
        // Enable hardware acceleration
        iconCenter.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        appNameContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        contentContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        // Phase 1: center icon scales up (0-300ms)
        iconCenter.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(300)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                // Phase 1.5: hold (300-800ms) - extra 500ms dwell
                iconCenter.postDelayed(() -> {
                    // Phase 2: center icon shrinks and fades out (800-1000ms)
                    iconCenter.animate()
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            iconCenter.setVisibility(View.GONE);
                            iconCenter.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                    
                    // Phase 3: app-name frosted container fades in and scales (800-1050ms)
                    appNameContainer.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(250)
                        .setStartDelay(50)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            appNameContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                    
                    // Phase 4: content frosted container slides in from below (950-1250ms)
                    contentContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(300)
                        .setStartDelay(150)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            contentContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                }, 500); // dwell 500ms
            })
            .start();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume", resumeTime, resumeTime));
        
        // V3.2: re-assert window flags (keep on + show when locked)
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // Ensure the show-when-locked setting keeps applying
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // Check whether we moved from the main placeholder to the rear (placeholder -> real display)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            View container = findViewById(R.id.notification_container);
            
            Log.d(TAG, String.format("[%tT.%tL] 🔍 Checking move: displayId=%d, contentInitialized=%s, container=%s", 
                resumeTime, resumeTime, currentDisplayId, contentInitialized, (container != null ? "present" : "null")));
            
            // If on the rear and the content is not yet initialized (moved from the main display)
            if (currentDisplayId == 1 && !contentInitialized && container != null) {
                Log.d(TAG, String.format("[%tT.%tL] 🔄 Main->rear move detected; initializing content", resumeTime, resumeTime));
                
                // Show the content
                container.setVisibility(View.VISIBLE);
                
                // Get the Intent data and initialize the content
                String title = getIntent().getStringExtra("title");
                String text = getIntent().getStringExtra("text");
                
                // Apply the safe-area padding (once)
                applySafeAreaPadding();
                contentInitialized = true;
                
                // Get the views
                ImageView appIconCenter = findViewById(R.id.app_icon_center);
                ImageView appIconSmall = findViewById(R.id.app_icon_small);
                TextView appNameText = findViewById(R.id.app_name);
                TextView notificationTitle = findViewById(R.id.notification_title);
                TextView notificationContent = findViewById(R.id.notification_content);
                View appNameContainer = findViewById(R.id.app_name_container);
                View contentContainer = findViewById(R.id.notification_content_container);
                
                // Load the app info
                try {
                    PackageManager pm = getPackageManager();
                    android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    String appName = pm.getApplicationLabel(appInfo).toString();
                    Drawable icon = pm.getApplicationIcon(packageName);
                    
                    // Set the icon and app name
                    appIconCenter.setImageDrawable(icon);
                    appIconSmall.setImageDrawable(icon);
                    appNameText.setText(appName);
                    
                    // Set the title and content
                    if (title != null && !title.isEmpty()) {
                        notificationTitle.setText(title);
                        notificationTitle.setVisibility(View.VISIBLE);
                    } else {
                        notificationTitle.setVisibility(View.GONE);
                    }
                    
                    if (text != null && !text.isEmpty()) {
                        notificationContent.setText(text);
                        notificationContent.setVisibility(View.VISIBLE);
                    } else {
                        notificationContent.setVisibility(View.GONE);
                    }
                    
                    // Hide the spacing if the title is empty
                    if (title == null || title.isEmpty()) {
                        notificationContent.setPadding(
                            notificationContent.getPaddingLeft(),
                            0,
                            notificationContent.getPaddingRight(),
                            notificationContent.getPaddingBottom()
                        );
                    }
                    
                    Log.d(TAG, String.format("[%tT.%tL] 📱 Notification: %s - %s: %s", 
                        resumeTime, resumeTime, appName, title, text));
                    
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load app info in onResume", e);
                    appNameText.setText(packageName);
                    if (title != null) notificationTitle.setText(title);
                    if (text != null) notificationContent.setText(text);
                }
                
                // Start the animation
                startNotificationAnimation(appIconCenter, appNameContainer, contentContainer);
                
                // Set up the click handler and auto-close
                container.setOnClickListener(v -> {
                    long clickTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("[%tT.%tL] 👆 Click received; preparing to open package=%s", clickTime, clickTime, packageName));
                    try {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                        Log.d(TAG, String.format("[%tT.%tL] 🔍 getLaunchIntentForPackage -> %s", clickTime, clickTime, (launchIntent == null ? "null" : String.valueOf(launchIntent.getComponent()))));
                        if (launchIntent == null) {
                            Log.w(TAG, String.format("[%tT.%tL] ⚠️ Could not get the launch Intent: %s", clickTime, clickTime, packageName));
                            finish();
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                        boolean started = false;
                        // Try to launch directly on the main display (ActivityOptions -> display=0)
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                                java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                                m.invoke(opts, 0);
                                Log.d(TAG, String.format("[%tT.%tL] 🚀 Trying ActivityOptions launch on display=0 (main)", clickTime, clickTime));
                                // Light up the main screen
                                try {
                                    ITaskService tsWake = NotificationService.getTaskService();
                                    if (tsWake != null) {
                                        tsWake.executeShellCommand("// main-screen wake removed");
                                        Log.d(TAG, String.format("[%tT.%tL] ✓ Woke the main screen", clickTime, clickTime));
                                    }
                                } catch (Throwable ignored) {}
                                startActivity(launchIntent, opts.toBundle());
                                started = true;
                                Log.d(TAG, String.format("[%tT.%tL] ✓ ActivityOptions main launch succeeded", clickTime, clickTime));
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, String.format("[%tT.%tL] 🔁 ActivityOptions unavailable; falling back: %s", clickTime, clickTime, t.getMessage()));
                        }

                        if (!started) {
                            // Fallback: launch on the main display via shell
                            try {
                                String component = null;
                                if (launchIntent.getComponent() != null) {
                                    component = launchIntent.getComponent().flattenToShortString();
                                }
                                if (component == null) {
                                    // Resolve the default LAUNCHER Activity
                                    android.content.pm.PackageManager pm = getPackageManager();
                                    Intent resolve = new Intent(Intent.ACTION_MAIN);
                                    resolve.addCategory(Intent.CATEGORY_LAUNCHER);
                                    resolve.setPackage(packageName);
                                    android.content.pm.ResolveInfo ri = pm.resolveActivity(resolve, 0);
                                    if (ri != null && ri.activityInfo != null) {
                                        component = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                                    }
                                }
                                Log.d(TAG, String.format("[%tT.%tL] 🧭 Resolved component: %s", clickTime, clickTime, String.valueOf(component)));
                                if (component != null) {
                                    ITaskService ts = NotificationService.getTaskService();
                                    if (ts != null) {
                                        // Wake the main screen first
                                        try {
                                            ts.executeShellCommand("// main-screen wake removed");
                                            Log.d(TAG, String.format("[%tT.%tL] ✓ Woke the main screen", clickTime, clickTime));
                                        } catch (Throwable ignored) {}
                                        String cmd = "am start --display 0 -n " + component;
                                        boolean ok = ts.executeShellCommand(cmd);
                                        if (!ok) {
                                            cmd = "am start -n " + component;
                                            ok = ts.executeShellCommand(cmd);
                                            Log.d(TAG, String.format("[%tT.%tL] 🔁 Launching on the default display; result=%s", clickTime, clickTime, ok));
                                        }
                                        Log.d(TAG, String.format("[%tT.%tL] ✓ Fallback shell main launch %s component=%s", clickTime, clickTime, ok ? "succeeded" : "failed", component));
                                        started = ok;
                                    } else {
                                        Log.w(TAG, String.format("[%tT.%tL] ⚠️ TaskService is null; cannot launch via shell", clickTime, clickTime));
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, String.format("[%tT.%tL] Fallback shell start error: %s", clickTime, clickTime, t.getMessage()));
                            }
                        }

                        Log.d(TAG, String.format("[%tT.%tL] 🧹 Ending notification Activity, started=%s", clickTime, clickTime, started));
                        finish();
                    } catch (Exception e) {
                        Log.e(TAG, String.format("[%tT.%tL] ❌ Launch failed: %s", clickTime, clickTime, e.getMessage()), e);
                        finish();
                    }
                });
                
                // V3.4: auto-close after the configured duration
                int duration = getSharedPreferences("mrss_settings", MODE_PRIVATE).getInt("notification_duration", 10);
                container.postDelayed(this::finish, duration * 1000L);

                Log.d(TAG, String.format("[%tT.%tL] ✓ Post-move initialization done", resumeTime, resumeTime));
            }
        }
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        long configTime = System.currentTimeMillis();
        
        // Log config changes (debugging)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            int densityDpi = newConfig.densityDpi;
            
            Log.d(TAG, String.format("[%tT.%tL] ⚙️ Config change: displayId=%d, densityDpi=%d", 
                configTime, configTime, currentDisplayId, densityDpi));
        }
    }
    
    @Override
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy called", destroyTime, destroyTime));

        // Unregister broadcast receivers
        try {
            unregisterReceiver(interruptReceiver);
            Log.d(TAG, String.format("[%tT.%tL] ✓ Notification animation broadcast receivers unregistered", destroyTime, destroyTime));
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister interrupt receiver: " + e.getMessage());
        }
        
        super.onDestroy();
        
        // Check whether this is the current instance
        if (this != currentInstance) {
            Log.w(TAG, String.format("[%tT.%tL] ⚠️ Stale instance; skipping restore", destroyTime, destroyTime));
            return;
        }
        // Clear the static reference so a destroyed instance (and its View tree) is not held forever by static fields (memory leak)
        currentInstance = null;

        if (interrupted) {
            Log.d(TAG, String.format("[%tT.%tL] 🔄 Interrupted by a new animation; the rear was taken over, skipping end/restore", destroyTime, destroyTime));
            return;
        }

        // Animation manager: notification animation ended
        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        
        // Restore the Launcher only on a normal end; not when interrupted
        if (!shouldRestore) {
            Log.d(TAG, String.format("[%tT.%tL] 🔄 Notification interrupted; skipping Launcher restore", destroyTime, destroyTime));
            return;
        }
        
        // V3.5: check whether to resume the charging animation (always-on mode)
        if (RearAnimationManager.shouldResumeChargingAnimation()) {
            Log.d(TAG, String.format("[%tT.%tL] 🔋 Charging always-on detected; sending resume broadcast", destroyTime, destroyTime));
            
            // Send the resume-charging broadcast
            android.content.Intent resumeIntent = new android.content.Intent("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
            resumeIntent.setPackage(getPackageName());
            sendBroadcast(resumeIntent);
            
            // Clear the flag
            RearAnimationManager.clearChargingAlwaysOnFlag();
            return;  // do not restore the official Launcher
        }

        // Check whether to resume media playback (interrupted by this notification)
        if (RearAnimationManager.consumeMediaInterruptedByNotificationFlag()) {
            Log.d(TAG, String.format("[%tT.%tL] 🎵 Media playback was interrupted; resuming it", destroyTime, destroyTime));
            NotificationService.resumeMediaIfInterrupted();
            return;  // do not restore the official Launcher; the media display takes over the rear itself
        }

        // On the rear, restore the official Launcher
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            Log.d(TAG, String.format("[%tT.%tL] 📍 displayId=%d", destroyTime, destroyTime, currentDisplayId));
            
            if (currentDisplayId == 1) {
                new Thread(() -> {
                    try {
                        // Get TaskService via ChargingService (NotificationService binds one too)
                        ITaskService taskService = ChargingService.getTaskService();
                        if (taskService != null) {
                            taskService.executeShellCommand(
                                "am start --display 1 -n com.xiaomi.mirror/.SubscreenLauncher"
                            );
                            Log.d(TAG, "✓ Official launcher restored");
                        } else {
                            Log.w(TAG, "TaskService not available");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore launcher", e);
                    }
                }).start();
            }
        }
    }
    
    @Override
    public void finish() {
        super.finish();
        // Disable transition animation
        overridePendingTransition(0, 0);
    }
    
    /**
     * Force the rear DPI before inflating the layout (critical!).
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
                    taskService = NotificationService.getTaskService();
                    if (taskService == null) {
                        taskService = ChargingService.getTaskService();
                    }
                    
                    if (taskService != null) {
                        break;
                    }
                    
                    Log.w(TAG, String.format("⚠️ TaskService not connected; retry %d/3", retry + 1));
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
                        Log.d(TAG, "✓ Live rear DPI: " + rearScreenDpi);
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to fetch the rear DPI live", e);
                        return;
                    }
                } else {
                    Log.e(TAG, "❌ TaskService still unavailable after 3 retries; skipping DPI force");
                    return;
                }
            }
            
            // Get the current DisplayMetrics
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 Before inflate - current DPI=%d, rear DPI=%d", currentDpi, rearScreenDpi));
            
            // Force it to the rear DPI
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;
            
            // Also update the Configuration
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            
            // Apply the new config
            getResources().updateConfiguration(config, metrics);
            
            Log.d(TAG, String.format("✓ Forced rear DPI before inflate: %d (density=%.2f)", 
                metrics.densityDpi, metrics.density));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply DPI before inflate", e);
        }
    }
    
    /**
     * Force the rear DPI (overrides the system DPI).
     */
    private void applyRearScreenDensity() {
        try {
            // Get the rear DPI
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;
            
            // Get the current DisplayMetrics
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 Current DPI=%d, rear DPI=%d", currentDpi, rearScreenDpi));
            
            // If the current DPI differs from the rear DPI, force it
            if (currentDpi != rearScreenDpi) {
                Log.d(TAG, String.format("⚠️ DPI mismatch! Forcing rear DPI: %d", rearScreenDpi));
                
                // Modify DisplayMetrics
                metrics.densityDpi = rearScreenDpi;
                metrics.density = rearScreenDpi / 160f;
                metrics.scaledDensity = metrics.density;
                
                // Also update the Configuration
                android.content.res.Configuration config = getResources().getConfiguration();
                config.densityDpi = rearScreenDpi;
                
                // Update Resources
                getResources().updateConfiguration(config, metrics);
                
                Log.d(TAG, String.format("✓ Forced rear DPI: %d, density=%.2f", 
                    metrics.densityDpi, metrics.density));
            } else {
                Log.d(TAG, "✓ DPI matches; no adjustment needed");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply the rear DPI", e);
        }
    }
    
    /**
     * Apply safe-area padding (clear the cutout).
     */
    private void applySafeAreaPadding() {
        try {
            // Get the rear info from the cache
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            
            // No cutout; nothing to do
            if (!info.hasCutout()) {
                Log.d(TAG, "ℹ️ No rear cutout; no layout adjustment needed");
                return;
            }
            
            // Get the root layout container (RelativeLayout with id=notification_container)
            android.view.View contentLayout = findViewById(R.id.notification_container);
            if (contentLayout != null && contentLayout.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = 
                    (android.view.ViewGroup.MarginLayoutParams) contentLayout.getLayoutParams();
                
                // Check whether margins are already set (avoid double-applying)
                if (params.leftMargin == info.cutout.left && 
                    params.topMargin == info.cutout.top && 
                    params.rightMargin == info.cutout.right && 
                    params.bottomMargin == info.cutout.bottom) {
                    Log.d(TAG, "ℹ️ Safe-area margins already set; skipping");
                    return;
                }
                
                // Set the margins (clear the cutout); the gradient background still fills the cutout area
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                contentLayout.setLayoutParams(params);
                
                Log.d(TAG, String.format("✓ Safe-area margins applied: left=%d, top=%d, right=%d, bottom=%d",
                    info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply the safe area", e);
        }
    }
    
    /**
     * Apply dark mode.
     * Shows a black background, removes the frost, turns text white, and adjusts the layout.
     */
    private void applyDarkMode() {
        try {
            Log.d(TAG, "🌙 Applying dark mode");
            
            // Show the black background layer
            View darkBackground = findViewById(R.id.dark_mode_background);
            if (darkBackground != null) {
                darkBackground.setVisibility(View.VISIBLE);
                Log.d(TAG, "✓ Black background shown");
            }
            
            // Get the whole notification container - keep it vertically centered
            View wrapperContainer = findViewById(R.id.notification_wrapper);
            if (wrapperContainer != null) {
                Log.d(TAG, "✓ Notification container acquired; keeping it centered");
            }
            
            // Get the frosted container - drop the frost, make it transparent
            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                
                // Remove the container padding so text can reach the screen edges
                contentContainer.setPadding(0, 0, 0, 0);
                Log.d(TAG, "✓ Frost removed; layout adjusted");
            }
            
            // Get the app-name/icon container - align left with the title content
            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null) {
                android.widget.LinearLayout.LayoutParams containerParams = (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0; // align left with the title content
                appNameContainer.setLayoutParams(containerParams);
                Log.d(TAG, "✓ App-name container aligned left with the title content");
            }
            
            // Get the app name - make it white
            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
                Log.d(TAG, "✓ App name set to white");
            }
            
            // Get the title - white, 1 line, adjust spacing
            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setMaxLines(1); // 1 line in dark mode
                
                // Match the title margin to the icon-to-title spacing
                android.widget.LinearLayout.LayoutParams titleParams = (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                titleParams.topMargin = 8; // matches the icon-to-title spacing
                notificationTitle.setLayoutParams(titleParams);
                Log.d(TAG, "✓ Title set to white, 1 line, spacing adjusted");
            }
            
            // Get the content - white, up to 6 lines, adjust spacing
            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setMaxLines(6); // 6 lines in dark mode
                
                // Match the content margin to the title-to-content spacing
                android.widget.LinearLayout.LayoutParams contentParams = (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                contentParams.topMargin = 8; // matches the title-to-content spacing
                notificationContent.setLayoutParams(contentParams);
                Log.d(TAG, "✓ Content set to white, 6 lines, spacing adjusted");
            }
            
            Log.d(TAG, "✓ Dark mode applied - black background, frost removed, white text, layout adjusted");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply dark mode", e);
        }
    }
    
    /**
     * Apply the regular layout adjustments.
     * Same layout changes as dark mode, but keeps the original colors.
     */
    private void applyRegularLayout() {
        try {
            Log.d(TAG, "🎨 Applying regular layout adjustments");
            
            // Get the whole notification container - keep it vertically centered
            View wrapperContainer = findViewById(R.id.notification_wrapper);
            if (wrapperContainer != null) {
                Log.d(TAG, "✓ Notification container acquired; keeping it centered");
            }
            
            // Get the frosted container - drop the frost, make it transparent
            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                // Drop the frost; make it transparent
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                
                // Remove the container padding so text can reach the screen edges
                contentContainer.setPadding(0, 0, 0, 0);
                Log.d(TAG, "✓ Regular layout container adjusted; frost removed");
            }
            
            // Get the app-name/icon container - align left with the title content
            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null) {
                android.widget.LinearLayout.LayoutParams containerParams = (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0; // align left with the title content
                appNameContainer.setLayoutParams(containerParams);
                Log.d(TAG, "✓ App-name container aligned left with the title content");
            }
            
            // Get the app name - white with a shadow
            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
                appName.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                Log.d(TAG, "✓ App name set to white with a shadow");
            }
            
            // Get the title - white with a shadow; adjust spacing and lines
            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                // White text with a shadow
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationTitle.setMaxLines(1); // 1 line
                
                // Match the title margin to the icon-to-title spacing
                android.widget.LinearLayout.LayoutParams titleParams = (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                titleParams.topMargin = 8; // matches the icon-to-title spacing
                notificationTitle.setLayoutParams(titleParams);
                Log.d(TAG, "✓ Title set to white with a shadow; spacing adjusted");
            }
            
            // Get the content - adjust spacing/lines, keep the original color
            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                // White text with a shadow
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationContent.setMaxLines(6); // 6 lines
                
                // Match the content margin to the title-to-content spacing
                android.widget.LinearLayout.LayoutParams contentParams = (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                contentParams.topMargin = 8; // matches the title-to-content spacing
                notificationContent.setLayoutParams(contentParams);
                Log.d(TAG, "✓ Content set to white with a shadow; spacing adjusted");
            }
            
            Log.d(TAG, "✓ Regular layout adjusted - frost removed, white text with shadows");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to apply the regular layout adjustments", e);
        }
    }
}

