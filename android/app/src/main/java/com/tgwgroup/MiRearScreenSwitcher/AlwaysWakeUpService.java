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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * V3.5: Always-wake service when no app is cast.
 * Keeps waking the rear screen by repeatedly sending KEYCODE_WAKEUP.
 * ⚠️ Warning: may cause burn-in and extra battery drain.
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final int NOTIFICATION_ID = 1001; // shared ID with other services
    private static final int WAKEUP_INTERVAL_MS = 2000; // 2s interval (100ms was too frequent; each spawns a shell process)
    
    private ITaskService taskService;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isRunning = false;
    private SharedPreferences prefs;
    
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("always_wakeup_task_service")
            .debuggable(false)
            .version(1);
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            Log.d(TAG, "✓ TaskService connected");
            
            // Start sending wakeup once TaskService is connected
            startWakeupLoop();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠️ TaskService disconnected");
            taskService = null;
            
            // On disconnect, try to reconnect
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🔄 Trying to rebind TaskService...");
                bindTaskService();
            }, 1000);
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "📱 onCreate");
        
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        wakeupHandler = new Handler(Looper.getMainLooper());
        
        // Create foreground notification
        createForegroundNotification();
        
        // Bind TaskService
        bindTaskService();
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
    
    private void createForegroundNotification() {
        String channelId = "mrss_core_service";
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                getString(R.string.notif_kernel_service),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_mrss_running));
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        
        Notification notification = builder
            .setContentTitle(getString(R.string.notif_kernel_service))
            .setContentText(getString(R.string.notif_mrss_running))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
        
        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "✓ Foreground service started");
    }
    
    private void startWakeupLoop() {
        if (isRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                // Check the toggle state
                boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
                if (!enabled) {
                    Log.d(TAG, "Toggle off, stopping wakeup loop");
                    stopSelf();
                    return;
                }
                
                // Send the wakeup command
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Sending wakeup failed: " + t.getMessage());
                }
                
                // Continue after the interval
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        };
        
        // Start immediately
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup loop started (100ms interval)");
    }
    
    private void stopWakeupLoop() {
        isRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "🔴 onDestroy");
        
        stopWakeupLoop();
        
        // Unbind TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                Log.d(TAG, "✓ TaskService unbound");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind TaskService: " + e.getMessage());
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
