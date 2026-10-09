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

    // MediaController.Callback fires several times for one logical track/state change (onMetadataChanged+onPlaybackStateChanged
    // often arrive together and repeat). Each showMediaOnRearScreen is a synchronous, blocking wake+launch flow (with Thread.sleep),
    // so bursts race each other and destabilize the rear screen. Debounce here: only run the last one in a short window.
    private static final long MEDIA_UPDATE_DEBOUNCE_MS = 250;
    private final android.os.Handler mediaUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingMediaUpdate;
    // Paused media stays up this long before it is hidden
    private static final long MEDIA_PAUSED_GRACE_MS = 5000;
    private Runnable pendingPausedHide;
    private String lastMediaSignature; // last media state pushed to the rear (pkg|title|artist|playing|art)

    private void scheduleShowMediaOnRearScreen(MediaMetadata metadata, PlaybackState state) {
        if (pendingMediaUpdate != null) {
            mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
        }
        pendingMediaUpdate = () -> showMediaOnRearScreen(metadata, state);
        mediaUpdateHandler.postDelayed(pendingMediaUpdate, MEDIA_UPDATE_DEBOUNCE_MS);
    }

    // Media is the idle page: callbacks only fire on change and the first show can be dropped (TaskService not bound yet,
    // metadata not ready, failed launch), so re-check periodically and put playing media back on the stack if missing.
    private static final long MEDIA_RECONCILE_MS = 5000;
    private final Runnable mediaReconcile = new Runnable() {
        @Override
        public void run() {
            try {
                if (mediaSessionManager != null && !RearStack.contains(RearStack.Type.MEDIA)) {
                    pickActiveMediaController(mediaSessionManager.getActiveSessions(new ComponentName(NotificationService.this, NotificationService.class)));
                    if (activeMediaController != null) {
                        showMediaOnRearScreen(activeMediaController.getMetadata(), activeMediaController.getPlaybackState());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Media reconcile failed: " + t.getMessage());
            }
            mediaUpdateHandler.postDelayed(this, MEDIA_RECONCILE_MS);
        }
    };

    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleShowMediaOnRearScreen(metadata, activeMediaController != null ? activeMediaController.getPlaybackState() : null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            if (state.getState() == PlaybackState.STATE_STOPPED || state.getState() == PlaybackState.STATE_NONE) {
                hideMedia();
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
            hideMedia();
            detachMediaController();
            return;
        }

        // Only consider sessions from apps the user selected; never track (or show) another app's session
        Set<String> selected = prefs.getStringSet("notification_selected_apps", new HashSet<>());
        java.util.ArrayList<MediaController> candidates = new java.util.ArrayList<>();
        for (MediaController c : controllers) {
            if (selected.contains(c.getPackageName())) candidates.add(c);
        }
        controllers = candidates;
        if (controllers.isEmpty()) {
            hideMedia();
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
            hideMedia();
            detachMediaController();
            return;
        }
        if (activeMediaController != null && activeMediaController.getSessionToken().equals(chosen.getSessionToken())) {
            return; // still the same session; callback already registered
        }

        hideMedia(); // the previous app's page must not linger under the new session
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
            Log.d(TAG, "Screen off received");
            // If a rear task is already running, do not interfere; avoids fighting RearScreenKeeperService's keep-alive
            if (RearScreenBroadcastReceiver.hasActiveTask()) {
                Log.d(TAG, "Screen off: rear task active, not waking");
                return;
            }
            if (!prefs.getBoolean("wake_on_lock_enabled", false)) {
                Log.d(TAG, "Screen off: wake_on_lock disabled");
                return;
            }
            try {
                if (taskService == null) {
                    Log.w(TAG, "Screen off: TaskService unavailable, cannot wake rear");
                    return;
                }
                // Media page already on the rear: leave it alone, waking would just bring up the home screen
                if (RearStack.contains(RearStack.Type.MEDIA)) {
                    Log.d(TAG, "Screen off: media showing, not waking");
                    return;
                }
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Log.d(TAG, "✓ Woke rear screen while locked");
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
            mediaUpdateHandler.postDelayed(mediaReconcile, MEDIA_RECONCILE_MS);
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
            
            Log.d(TAG, "📝 Notification title: " + title);
            Log.d(TAG, "📝 Notification content: " + text);

            // Duplicate posts of the same notification (e.g. Telegram updates 4x in 1.5s): ignore while showing the same content,
            // otherwise every one interrupts and reloads the notification Activity, appearing as a rear-screen flicker.
            // Compare the raw pre-privacy content so new messages in the same session still replace normally in privacy mode.
            String signature = sbn.getKey() + "|" + title + "|" + text;
            if (signature.equals(lastShownSignature) && RearStack.contains(RearStack.Type.NOTIFICATION)) {
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
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            showNotificationOnRearScreen(packageName, title, text, notificationDarkMode);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling notification", e);
        }
    }

    private void showNotificationOnRearScreen(String packageName, String title, String text, boolean darkMode) {
        android.os.Bundle payload = new android.os.Bundle();
        payload.putString("packageName", packageName);
        payload.putString("title", title);
        payload.putString("text", text);
        payload.putBoolean("darkMode", darkMode);
        if (taskService == null) {
            Log.w(TAG, "⚠️ TaskService not connected; trying to rebind...");
            bindTaskService();
            // Retry after a short delay; give up if still unavailable
            mediaUpdateHandler.postDelayed(() -> {
                if (taskService == null) {
                    Log.e(TAG, "❌ TaskService still unavailable; giving up on the notification");
                    return;
                }
                RearHost.show(this, RearStack.Type.NOTIFICATION, payload);
            }, 500);
        } else {
            RearHost.show(this, RearStack.Type.NOTIFICATION, payload);
        }
    }

    /**
     * Media display: shows the current track + album art on the rear screen, with playback controls.
     * Reuses the notification service toggle and "selected apps" whitelist as the gate; no separate switch.
     * Only pushes to the rear when title, artist or play state actually changed.
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

            if (isPlaying) {
                cancelPausedHide();
            } else {
                // Paused: don't pop media up from nothing; if already showing, hide it after a grace period
                if (!RearStack.contains(RearStack.Type.MEDIA)) return;
                schedulePausedHide();
            }

            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);

            String signature = activeMediaPackage + "|" + title + "|" + artist + "|" + isPlaying + "|" + (art != null);
            if (signature.equals(lastMediaSignature) && RearStack.contains(RearStack.Type.MEDIA)) {
                return; // nothing changed; apps that post state every second must not re-front the media page
            }
            lastMediaSignature = signature;

            String albumArtPath = null;
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

            android.os.Bundle payload = new android.os.Bundle();
            payload.putString("packageName", activeMediaPackage);
            payload.putString("title", title == null ? "" : title);
            payload.putString("artist", artist == null ? "" : artist);
            payload.putString("albumArtPath", albumArtPath == null ? "" : albumArtPath);
            payload.putBoolean("isPlaying", isPlaying);
            RearHost.show(this, RearStack.Type.MEDIA, payload);
            Log.d(TAG, "🎵 Media playback showing on the rear: " + title);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show rear media playback", e);
        }
    }

    private void hideMedia() {
        if (pendingMediaUpdate != null) {
            mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
        }
        cancelPausedHide();
        lastMediaSignature = null;
        RearHost.hide(this, RearStack.Type.MEDIA);
    }

    private void schedulePausedHide() {
        if (pendingPausedHide != null) return; // grace period already running
        pendingPausedHide = () -> {
            pendingPausedHide = null;
            hideMedia();
        };
        mediaUpdateHandler.postDelayed(pendingPausedHide, MEDIA_PAUSED_GRACE_MS);
    }

    private void cancelPausedHide() {
        if (pendingPausedHide != null) {
            mediaUpdateHandler.removeCallbacks(pendingPausedHide);
            pendingPausedHide = null;
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
            mediaUpdateHandler.removeCallbacks(mediaReconcile);
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

