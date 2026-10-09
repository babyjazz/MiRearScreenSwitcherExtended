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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.PowerManager;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * Notification listener service.
 * Listens for system notifications and shows selected apps' notifications on the rear screen.
 */
public class NotificationService extends NotificationListenerService {
    private static final String TAG = "NotificationService";
    private static final int NOTIFICATION_ID = 1001; // shared ID with other services
    
    private Set<String> selectedApps = new HashSet<>();
    private boolean privacyHideTitle = false; // V3.2: privacy mode - hide the title
    private boolean privacyHideContent = false; // V3.2: privacy mode - hide the content
    private boolean followDndMode = true; // follow system DND (on by default)
    private boolean onlyWhenLocked = false; // notify only when locked (off by default)
    private boolean notificationDarkMode = false; // notification dark mode (off by default)
    private boolean serviceEnabled = false; // whether the service is enabled
    private ITaskService taskService; // own TaskService instance
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private String lastShownSignature; // last shown notification (key|title|content), used to filter duplicate posts
    
    // Static instance, accessible from outside
    private static NotificationService instance;

    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }

    // Media playback (POC): currently tracked MediaController, used by the rear-screen control buttons
    private MediaSessionManager mediaSessionManager;
    private MediaController activeMediaController;
    private String activeMediaPackage;

    public static MediaController getActiveMediaController() {
        return instance != null ? instance.activeMediaController : null;
    }

    /**
     * Called when the notification ends after interrupting media playback: if media is still active (not truly stopped), show it again.
     */
    public static void resumeMediaIfInterrupted() {
        if (instance != null && instance.activeMediaController != null) {
            instance.scheduleShowMediaOnRearScreen(
                instance.activeMediaController.getMetadata(),
                instance.activeMediaController.getPlaybackState()
            );
        }
    }

    // MediaController.Callback fires several times for one logical track/state change (onMetadataChanged+onPlaybackStateChanged
    // often arrive together and repeat). Each showMediaOnRearScreen is a synchronous, blocking wake+launch flow (with Thread.sleep),
    // so bursts race each other and destabilize the rear screen. Debounce here: only run the last one in a short window.
    private static final long MEDIA_UPDATE_DEBOUNCE_MS = 250;
    private final android.os.Handler mediaUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingMediaUpdate;

    private void scheduleShowMediaOnRearScreen(MediaMetadata metadata, PlaybackState state) {
        if (pendingMediaUpdate != null) {
            mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
        }
        pendingMediaUpdate = () -> showMediaOnRearScreen(metadata, state);
        mediaUpdateHandler.postDelayed(pendingMediaUpdate, MEDIA_UPDATE_DEBOUNCE_MS);
    }

    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleShowMediaOnRearScreen(metadata, activeMediaController != null ? activeMediaController.getPlaybackState() : null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            if (state.getState() == PlaybackState.STATE_STOPPED || state.getState() == PlaybackState.STATE_NONE) {
                if (pendingMediaUpdate != null) {
                    mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
                }
                RearAnimationManager.sendInterruptBroadcast(NotificationService.this, RearAnimationManager.AnimationType.MEDIA);
                return;
            }
            scheduleShowMediaOnRearScreen(activeMediaController != null ? activeMediaController.getMetadata() : null, state);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
        controllers -> pickActiveMediaController(controllers);

    /**
     * Pick a playing/recently-used session from the active MediaSession list and register a callback to track it.
     */
    private void pickActiveMediaController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (activeMediaController != null) {
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            }
            detachMediaController();
            return;
        }

        // Prefer the first playing session (the system returns them by recency)
        MediaController chosen = null;
        for (MediaController c : controllers) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                chosen = c;
                break;
            }
        }
        if (chosen == null) {
            // No session is playing; fall back to the first session with real state (e.g. paused).
            // Do not blindly take controllers.get(0): some apps (e.g. Taobao's TbAliveMedia) keep a
            // state=null placeholder session registered. Selecting it locks us onto a dead session that never updates,
            // and real playing sessions that appear later get blocked by the "already have a session" dedup logic.
            for (MediaController c : controllers) {
                if (c.getPlaybackState() != null) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null) {
            // No session has real state; nothing to display
            if (activeMediaController != null) {
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            }
            detachMediaController();
            return;
        }
        if (activeMediaController != null && activeMediaController.getSessionToken().equals(chosen.getSessionToken())) {
            return; // still the same session; callback already registered
        }

        detachMediaController();
        activeMediaController = chosen;
        activeMediaPackage = chosen.getPackageName();
        activeMediaController.registerCallback(mediaControllerCallback);
        scheduleShowMediaOnRearScreen(activeMediaController.getMetadata(), activeMediaController.getPlaybackState());
    }

    private void detachMediaController() {
        if (activeMediaController != null) {
            try {
                activeMediaController.unregisterCallback(mediaControllerCallback);
            } catch (Throwable ignored) {}
        }
        activeMediaController = null;
        activeMediaPackage = null;
    }

    // Broadcast receiver: listens for settings reload
    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS".equals(intent.getAction())) {
                Log.d(TAG, "🔄 Settings reload broadcast received");
                loadNotificationServiceSettings(); // reload the toggle state
                loadSettings(); // reload other settings
            }
        }
    };
    
    // Broadcast receiver: wake the rear screen when locked (optional, off by default)
    // Since Android 3.1, ACTION_SCREEN_OFF/ON no longer reach manifest-declared receivers;
    // they only arrive via a receiver registered dynamically in a running component, so this lives in the persistent NotificationService.
    private BroadcastReceiver wakeOnLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                return;
            }
            // If a rear task is already running, do not interfere; avoids fighting RearScreenKeeperService's keep-alive
            if (RearScreenBroadcastReceiver.hasActiveTask()) {
                return;
            }
            if (!prefs.getBoolean("wake_on_lock_enabled", false)) {
                return;
            }
            try {
                if (taskService == null) return;
                if (activeMediaController != null) {
                    // When media is playing, just waking the screen does not guarantee the media UI is what shows
                    // (the rear screen has its own on/off timing and easily races the official Launcher),
                    // so re-show the media UI to guarantee it is what appears on the locked rear screen.
                    scheduleShowMediaOnRearScreen(activeMediaController.getMetadata(), activeMediaController.getPlaybackState());
                } else {
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    Log.d(TAG, "✓ Woke rear screen while locked");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to wake rear screen while locked: " + t.getMessage());
            }
        }
    };

    // Shizuku service config
    private final Shizuku.UserServiceArgs serviceArgs =
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("notification_task_service")
            .debuggable(false)
            .version(1);
    
    // TaskService connection
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
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 NotificationService created");
        
        // Save the instance
        instance = this;
        
        // Initialize SharedPreferences
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // Register broadcast receivers (settings changes)
        IntentFilter filter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, filter);
        }
        Log.d(TAG, "✓ Broadcast receivers registered");

        // Register the wake-on-lock receiver (ACTION_SCREEN_OFF only reaches dynamically registered receivers, not manifest-declared ones)
        IntentFilter screenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeOnLockReceiver, screenOffFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wakeOnLockReceiver, screenOffFilter);
        }

        // Add Shizuku listeners
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        
        // Bind TaskService
        bindTaskService();
        
        // V2.4: load the notification service toggle state
        Log.d(TAG, "🔧 Loading notification service toggle state...");
        loadNotificationServiceSettings();
        Log.d(TAG, "🔧 Notification service toggle loaded: " + serviceEnabled);
        
        // Run as a foreground service so the system does not kill it
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        Log.d(TAG, "✓ Foreground service started");

        loadSettings();

        // Media playback (POC): register MediaSession listening to track the current track
        try {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listenerComponent = new ComponentName(this, NotificationService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, listenerComponent);
            // The listener only sees future changes; manually grab currently active sessions at startup
            pickActiveMediaController(mediaSessionManager.getActiveSessions(listenerComponent));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register MediaSession listener: " + t.getMessage());
        }
    }
    
    private void bindTaskService() {
        try {
            if (taskService != null) {
                Log.d(TAG, "TaskService already bound");
                return;
            }
            
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            Log.d(TAG, "🔗 Binding TaskService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    /**
     * Load the notification service toggle state.
     */
    private void loadNotificationServiceSettings() {
        try {
            Log.d(TAG, "🔧 Reading FlutterSharedPreferences...");
            // Read the toggle state from FlutterSharedPreferences
            SharedPreferences flutterPrefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            Log.d(TAG, "🔧 FlutterSharedPreferences read OK");
            
            serviceEnabled = flutterPrefs.getBoolean("flutter.notification_service_enabled", false);
            Log.d(TAG, "🔧 Notification service toggle restored: " + serviceEnabled);
            
            // NotificationListenerService is owned by the system and cannot be stopped manually;
            // if the toggle is off, the service keeps running but ignores notifications
            if (!serviceEnabled) {
                Log.d(TAG, "⏸️ Notification service disabled; ignoring all notifications");
            } else {
                Log.d(TAG, "✅ Notification service enabled; processing notifications");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to load notification service settings", e);
            serviceEnabled = false; // off by default
        }
    }
    
    private void loadSettings() {
        try {
            selectedApps = prefs.getStringSet("notification_selected_apps", new HashSet<>());
            privacyHideTitle = prefs.getBoolean("notification_privacy_hide_title", false);
            privacyHideContent = prefs.getBoolean("notification_privacy_hide_content", false);
            followDndMode = prefs.getBoolean("notification_follow_dnd_mode", true);
            onlyWhenLocked = prefs.getBoolean("notification_only_when_locked", false);
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            // Note: do not reset serviceEnabled here; keep the value from loadNotificationServiceSettings()
            
            Log.d(TAG, "⚙️ Settings loaded");
            Log.d(TAG, "   - enabled: " + serviceEnabled + " (set by loadNotificationServiceSettings)");
            Log.d(TAG, "   - selected apps: " + selectedApps.size());
            Log.d(TAG, "   - hide title: " + privacyHideTitle);
            Log.d(TAG, "   - hide content: " + privacyHideContent);
            
            if (!selectedApps.isEmpty()) {
                Log.d(TAG, "📋 selected apps list: " + selectedApps.toString());
            } else {
                Log.w(TAG, "⚠️ No apps selected");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load settings", e);
            selectedApps = new HashSet<>();
            // Do not reset serviceEnabled here
        }
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        
        // V2.4: reload the toggle state on every notification
        loadNotificationServiceSettings();
        
        // V2.4: ignore notifications when the service toggle is off
        if (!serviceEnabled) {
            Log.d(TAG, "⏸️ Notification service disabled; ignoring");
            return;
        }
        
        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            
            Log.d(TAG, "📢 Notification received: " + packageName);

            // Skip media-playback notifications: those go through the dedicated media display flow (MediaSessionManager),
            // not the normal notification popup, otherwise every track/state change pops a chat-style notification and interrupts the media UI.
            // Some apps' playback notifications (e.g. some YouTube Music builds) do not set FLAG_ONGOING_EVENT,
            // so do not rely on that flag alone; check whether a MediaSession is attached.
            if (notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null) {
                Log.d(TAG, "⏭️ Skipping media notification (dedicated media flow): " + packageName);
                return;
            }

            // Skip persistent notifications
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                Log.d(TAG, "⏭️ Skipping persistent notification: " + packageName);
                return;
            }
            
            // Skip our own notifications
            if (packageName.equals(getPackageName())) {
                Log.d(TAG, "⏭️ Skipping our own notification");
                return;
            }
            
            // Reload settings every time (keep them live)
            loadSettings();
            
            // Check whether the service is enabled
            if (!serviceEnabled) {
                Log.d(TAG, "⏭️ Notification service disabled; skipping");
                return;
            }
            
            // Check system DND mode
            if (followDndMode) {
                try {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                        Log.d(TAG, "⏭️ System DND on; skipping notification animation");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check DND mode: " + e.getMessage());
                }
            }
            
            // Check "notify only when locked"
            if (onlyWhenLocked) {
                try {
                    android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        Log.d(TAG, "⏭️ Not locked and only-when-locked mode is on; skipping");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check lock state: " + e.getMessage());
                }
            }
            
            Log.d(TAG, "📋 Selected app count: " + selectedApps.size());
            Log.d(TAG, "📋 Selected apps: " + selectedApps.toString());
            
            // Check whether the app is on the selected list
            if (!selectedApps.contains(packageName)) {
                Log.d(TAG, "⏭️ App not on the selected list: " + packageName);
                return;
            }
            
            Log.d(TAG, "✓ App is on the selected list: " + packageName);
            
            // Extract the notification content
            String title = notification.extras.getString(Notification.EXTRA_TITLE, "");
            String text = notification.extras.getString(Notification.EXTRA_TEXT, "");
            long when = notification.when;
            
            Log.d(TAG, "📝 Notification title: " + title);
            Log.d(TAG, "📝 Notification content: " + text);

            // Duplicate posts of the same notification (e.g. Telegram updates 4x in 1.5s): ignore while showing the same content,
            // otherwise every one interrupts and reloads the notification Activity, appearing as a rear-screen flicker.
            // Compare the raw pre-privacy content so new messages in the same session still replace normally in privacy mode.
            String signature = sbn.getKey() + "|" + title + "|" + text;
            if (signature.equals(lastShownSignature)
                    && RearAnimationManager.getCurrentAnimation() == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "⏭️ Duplicate identical notification still showing; ignoring: " + packageName);
                return;
            }
            lastShownSignature = signature;
            
            // V3.2: privacy-mode handling (title vs content)
            if (privacyHideTitle) {
                Log.d(TAG, "🔒 Hiding notification title");
                title = getString(R.string.privacy_mode_enabled);
            }
            if (privacyHideContent) {
                Log.d(TAG, "🔒 Hiding notification content");
                text = getString(R.string.new_message_placeholder);
            }
            
            Log.d(TAG, "🚀 Showing rear notification: " + packageName);
            
            // Animation manager: start the notification animation (returns the interrupted old one)
            RearAnimationManager.AnimationType oldAnim = RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
            
            // If an old animation must be interrupted, send the interrupt broadcast
            if (oldAnim == RearAnimationManager.AnimationType.CHARGING) {
                Log.d(TAG, "🔄 Charging animation playing; sending interrupt broadcast");
                
                // V3.5: check whether the interrupted charging animation was always-on
                boolean chargingAlwaysOn = prefs.getBoolean("charging_always_on_enabled", false);
                RearAnimationManager.markInterruptedChargingAsAlwaysOn(chargingAlwaysOn);
                
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.CHARGING);
            } else if (oldAnim == RearAnimationManager.AnimationType.MEDIA) {
                Log.d(TAG, "🔄 Media playback showing; sending interrupt broadcast");
                // When the notification ends, resume media playback instead of the official Launcher
                RearAnimationManager.markMediaInterruptedByNotification();
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            } else if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "🔄 Notification animation playing; interrupting and reloading");
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.NOTIFICATION);
                
                // Relaunch the notification animation after 600ms so the old one fully stops (needs more time when locked + app cast)
                final String finalPackageName = packageName;
                final String finalTitle = title;
                final String finalText = text;
                final long finalWhen = when;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "🔄 Reloading notification animation");
                    // The previous animation was just interrupted, so the screen is clearly lit; skip the wake to avoid interrupting this one
                    showNotificationOnRearScreen(finalPackageName, finalTitle, finalText, finalWhen, true);
                }, 600);
                return; // return early to avoid a duplicate launch
            }

            // Trigger the rear notification display
            showNotificationOnRearScreen(packageName, title, text, when, false);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling notification", e);
        }
    }

    private void showNotificationOnRearScreen(String packageName, String title, String text, long when, boolean skipWake) {
        // Modeled on ChargingService\'s retry mechanism
        if (taskService == null) {
            Log.w(TAG, "⚠️ TaskService not connected; trying to rebind...");
            bindTaskService();

            // Retry after a 500ms delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                showNotificationOnRearScreenDirect(packageName, title, text, when, skipWake);
            }, 500);
        } else {
            showNotificationOnRearScreenDirect(packageName, title, text, when, skipWake);
        }
    }
    
    private void showNotificationOnRearScreenDirect(String packageName, String title, String text, long when, boolean skipWake) {
        try {
            if (taskService == null) {
                Log.e(TAG, "❌ TaskService still unavailable; giving up on the notification");
                return;
            }
            
            // Local short keep-alive to avoid suspension while locked/heavily loaded
            acquireWakeLock(6000);
            Log.d(TAG, "🎯 Preparing to launch the Activity to show the notification");
            
            // Lock-state check
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();
            
            // Read the main-screen foreground app (guards the same-package-in-foreground case)
            String mainForegroundApp = null;
            try {
                mainForegroundApp = taskService.getForegroundAppOnDisplay(0);
                Log.d(TAG, "📱 Main-screen foreground app: " + mainForegroundApp);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to get the main-screen foreground app: " + t.getMessage());
            }
            
            // V3.3: removed wake code to avoid jumping to the passcode screen while locked
            
            try {
                // Pause monitoring so it does not get killed
                RearScreenKeeperService.pauseMonitoring();
            } catch (Throwable t) {
                Log.w(TAG, "pauseMonitoring failed: " + t.getMessage());
            }
            
            try {
                // Disable the official rear-screen Launcher so it does not steal the screen
                taskService.disableSubScreenLauncher();
            } catch (Throwable t) {
                Log.w(TAG, "disableSubScreenLauncher failed: " + t.getMessage());
            }
            
            // V3.3: removed the `wm dismiss-keyguard` command to avoid jumping to the passcode screen while locked
            
            // Wake the rear screen first, then launch the Activity, so the animation lands on an already-lit screen.
            // Window flags (FLAG_TURN_SCREEN_ON) do nothing while the rear screen is DOZE/DOZE_SUSPEND;
            // only this command lights it (same as a double-tap wake), consistent with RearScreenKeeperService/AlwaysWakeUpService.
            // Must run before both launch strategies (direct --display 1 and placeholder+move) because both need the screen lit.
            // skipWake: skip when reloading right after the previous notification animation was interrupted; the screen is clearly already lit,
            // and waking again just adds a pointless wait that looks like a "wake animation interrupted the notification".
            if (!skipWake) {
                try {
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // Wait for the physical wake (backlight ramp) so the animation does not draw before the screen is lit
                    Thread.sleep(300);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to wake the rear screen: " + t.getMessage());
                }
            }

            // 2) pick the launch strategy from lock state and foreground app
            String componentName = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            
            // When locked and the main-screen foreground is this notification's own app, skip the main-placeholder strategy and launch directly on the rear to avoid a system conflict
            // Exact package match to avoid false positives (e.g. com.tencent.mm vs com.tencent.mobileqq)
            boolean forceDirectRearDueToSameApp = false;
            if (isLocked && mainForegroundApp != null && !mainForegroundApp.isEmpty()) {
                // Extract the package of the main-screen foreground app (format may be "com.example.app/com.example.app.MainActivity")
                String foregroundPackage = mainForegroundApp;
                if (mainForegroundApp.contains("/")) {
                    foregroundPackage = mainForegroundApp.split("/")[0];
                }
                forceDirectRearDueToSameApp = foregroundPackage.equals(packageName);
                Log.d(TAG, String.format("🔍 Locked same-package check: main foreground=[%s] vs notification package=[%s] -> %s",
                    foregroundPackage, packageName, forceDirectRearDueToSameApp ? "match (direct rear)" : "no match (placeholder)"));
            }
            
            // ✅ Unified strategy: launch directly on the rear screen regardless of lock state (avoids DPI mismatch)
            // Launching directly on the rear screen ensures the layout uses the correct DPI (450), avoiding size issues from a main-screen move
            
            // Ensure the dark-mode setting is current
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            Log.d(TAG, "🌙 Current dark-mode setting: " + notificationDarkMode);
            
            String directCmd = String.format(
                "am start --display 1 -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                componentName,
                packageName,
                title.replace("\"", "\\\""),
                text.replace("\"", "\\\""),
                when,
                notificationDarkMode
            );
            
            boolean started = false;
            // When locked, HyperOS always rejects --display 1 (ActivityStarterImpl); go straight to the main-placeholder+move below and save ~1s of wasted tries.
            // Unlocked: launch once, then poll. The Activity only shows up in `am stack list` after ~0.5s;
            // repeating --display 1 during that window can create phantom tasks.
            if (!isLocked) {
                try {
                    taskService.executeShellCommand(directCmd);
                    Log.d(TAG, "✓ Unlocked; launching the notification Activity directly on the rear");
                    for (int i = 0; i < 6 && !started; i++) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        String check = taskService.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                        started = check != null && !check.trim().isEmpty();
                    }
                    Log.d(TAG, started ? "✓ Notification animation started on the rear" : "⚠️ Direct rear launch did not appear");
                } catch (Throwable t) {
                    Log.w(TAG, "Direct rear launch failed: " + t.getMessage());
                }
            }
            
            // If the direct launch failed, use the fallback (main placeholder + move)
            if (!started) {
                Log.w(TAG, isLocked ? "🔒 Locked; using main placeholder + move" : "⚠️ Direct rear launch failed; falling back to main placeholder + move");
                
                // Launch on the main screen (the Activity acts as its own placeholder)
                String startOnMainCmd = String.format(
                    "am start -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                    componentName,
                    packageName,
                    title.replace("\"", "\\\""),
                    text.replace("\"", "\\\""),
                    when,
                    notificationDarkMode
                );
                Log.d(TAG, "🔵 Launching the notification Activity on the main display (placeholder)");
                taskService.executeShellCommand(startOnMainCmd);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                
                // Poll for the taskId
                String notifTaskId = null;
                int attempts = 0;
                int maxAttempts = 60;
                while (notifTaskId == null && attempts < maxAttempts) {
                    try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            notifTaskId = matcher.group(1);
                            Log.d(TAG, "🎯 Found notification taskId=" + notifTaskId);
                            break;
                        }
                    }
                    attempts++;
                }
                
                if (notifTaskId != null) {
                    // 4) move to the rear screen
                    String moveCmd = "am display move-stack " + notifTaskId + " 1";
                    taskService.executeShellCommand(moveCmd);
                    try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    
                    // 5) turn off the main screen while locked to avoid focus stealing
                    // main-screen sleep removed
                    Log.d(TAG, "🔒 Locked; main screen off");
                    
                    Log.d(TAG, "✓ Notification animation moved to the rear");
                } else {
                    Log.e(TAG, "❌ Could not find the notification Activity taskId; last-ditch direct rear launch");
                    try {
                        String fallbackCmd = String.format(
                            "am start --display 1 -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                            componentName,
                            packageName,
                            title.replace("\"", "\\\""),
                            text.replace("\"", "\\\""),
                            when,
                            notificationDarkMode
                        );
                        taskService.executeShellCommand(fallbackCmd);
                        Log.d(TAG, "🟦 Tried direct --display 1 launch (fallback)");
                    } catch (Throwable t) {
                        Log.w(TAG, "Fallback direct rear launch failed: " + t.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show the rear notification", e);
        } finally {
            releaseWakeLock();
        }
    }

    /**
     * Media display (POC): shows the current track info + album art on the rear screen, with playback controls.
     * Reuses the notification service toggle and "selected apps" whitelist as the gate; no separate switch.
     */
    private void showMediaOnRearScreen(MediaMetadata metadata, PlaybackState state) {
        try {
            if (taskService == null || activeMediaPackage == null) return;
            if (!serviceEnabled) return;
            if (!prefs.getStringSet("notification_selected_apps", new HashSet<>()).contains(activeMediaPackage)) return;
            if (metadata == null) return;

            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

            String albumArtPath = null;
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art != null) {
                try {
                    File f = new File(getCacheDir(), "media_album_art.png");
                    FileOutputStream fos = new FileOutputStream(f);
                    art.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    albumArtPath = f.getAbsolutePath();
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to write the album art: " + t.getMessage());
                }
            }

            RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.MEDIA);

            String componentName = getPackageName() + "/" + RearScreenMediaActivity.class.getName();
            String extras = String.format(
                "--es packageName \"%s\" --es title \"%s\" --es artist \"%s\" --es albumArtPath \"%s\" --ez isPlaying %b",
                activeMediaPackage,
                title == null ? "" : title.replace("\"", "\\\""),
                artist == null ? "" : artist.replace("\"", "\\\""),
                albumArtPath == null ? "" : albumArtPath,
                isPlaying
            );

            // When locked, HyperOS rejects a new --display 1 task launch (ActivityStarterImpl's rearDisplay check);
            // like the notification popup, it needs a main placeholder first, then move-stack
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();

            boolean started = false;
            if (!isLocked) {
                taskService.executeShellCommand("am start --display 1 -n " + componentName + " " + extras);
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String check = taskService.executeShellCommandWithResult("am stack list | grep RearScreenMediaActivity");
                started = check != null && !check.trim().isEmpty();
            }

            if (!started) {
                taskService.executeShellCommand("am start -n " + componentName + " " + extras);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                String mediaTaskId = null;
                for (int attempts = 0; attempts < 60 && mediaTaskId == null; attempts++) {
                    try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenMediaActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("taskId=(\\d+)").matcher(result);
                        if (matcher.find()) mediaTaskId = matcher.group(1);
                    }
                }
                if (mediaTaskId != null) {
                    taskService.executeShellCommand("am display move-stack " + mediaTaskId + " 1");
                } else {
                    Log.w(TAG, "⚠️ Could not find the media Activity taskId");
                }
            }

            Log.d(TAG, "🎵 Media playback showing on the rear: " + title);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show rear media playback", e);
        }
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:NotificationWake");
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
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "🔗 NotificationListener connected");
        loadSettings();
        Log.d(TAG, "✓ Notification listener ready");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔴 NotificationService destroyed");

        // Media playback (POC): unregister the MediaSession listener
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            }
            if (pendingMediaUpdate != null) {
                mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
            }
            detachMediaController();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to unregister MediaSession listener: " + t.getMessage());
        }

        // Unregister broadcast receivers
        try {
            unregisterReceiver(settingsReceiver);
            Log.d(TAG, "✓ Broadcast receivers unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister receiver", e);
        }
        try {
            unregisterReceiver(wakeOnLockReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister wakeOnLockReceiver", e);
        }

        // Remove Shizuku listeners
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove Shizuku listeners", e);
        }
        
        // Unbind TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind TaskService", e);
        }
        
        // Clear the instance
        instance = null;
        
        stopForeground(true);
    }
}

