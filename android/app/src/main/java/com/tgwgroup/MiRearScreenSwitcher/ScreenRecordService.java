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
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import rikka.shizuku.Shizuku;

/**
 * Rear-screen recording service.
 * Features:
 * 1. floating window (record/stop button + close button)
 * 2. records the rear screen (screenrecord --display-id 1)
 * 3. kept alive as a foreground Service
 */
public class ScreenRecordService extends Service {
    private static final String TAG = "ScreenRecordService";
    private static final String CHANNEL_ID = "rear_screen_keeper"; // uses the MRSS kernel service channel
    private static final int NOTIFICATION_ID = 10004; // avoid clashing with KeeperService
    
    private static ScreenRecordService instance = null;
    private WindowManager windowManager;
    private View floatingView;
    private boolean isRecording = false;
    private String currentVideoPath;
    private int recordPid = -1; // recording process PID
    private Handler wakeupHandler = new Handler(android.os.Looper.getMainLooper());
    private static final long WAKEUP_INTERVAL_MS = 2000; // wake the rear every 2s (100ms was too frequent; each spawns a shell process)
    
    // TaskService
    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
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
    
    public static boolean isRunning() {
        return instance != null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "📹 ScreenRecordService onCreate");
        
        // Create the notification channel
        createNotificationChannel();
        
        // Bind TaskService
        bindTaskService();
        
        // Start the foreground notification
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.d(TAG, "✓ Foreground Service started");
        
        // Show the floating window
        try {
            showFloatingWindow();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show the floating window", e);
            e.printStackTrace();
            Toast.makeText(this, "Failed to show floating window: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
        Log.d(TAG, "═══════════════════════════════════════");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // auto-restarts if killed
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void bindTaskService() {
        if (taskService != null) {
            Log.d(TAG, "TaskService connected; skipping bind");
            return;
        }
        
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "❌ Shizuku unavailable");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ No Shizuku permission");
                return;
            }
            
            Log.d(TAG, "→ binding TaskService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to bind TaskService", e);
            e.printStackTrace();
        }
    }
    
    private void createNotificationChannel() {
        // No new channel; use the MRSS kernel service channel (already exists)
    }
    
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        
        // Use the MRSS kernel service notification style everywhere
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_kernel_service))
            .setContentText(getString(R.string.notif_mrss_running))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    /**
     * Show the floating window.
     */
    private void showFloatingWindow() {
        Log.d(TAG, "→ preparing to show the floating window");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "❌ Could not get WindowManager");
            return;
        }
        Log.d(TAG, "✓ WindowManager acquired");
        
        // Create the floating-window layout
        Log.d(TAG, "→ creating floating-window view");
        floatingView = createFloatingView();
        if (floatingView == null) {
            Log.e(TAG, "❌ Failed to create the view");
            return;
        }
        Log.d(TAG, "✓ View created");
        
        // Set the floating-window params
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 200;
        
        Log.d(TAG, "→ params set; about to add the view");
        Log.d(TAG, "  TYPE: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "TYPE_APPLICATION_OVERLAY" : "TYPE_PHONE"));
        
        try {
            windowManager.addView(floatingView, params);
            Log.d(TAG, "✅ Floating window added to WindowManager");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to add the floating window", e);
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * Create the floating-window view.
     */
    private View createFloatingView() {
        Log.d(TAG, "→ creating the floating-window layout");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(16, 16, 16, 16);
        layout.setGravity(android.view.Gravity.CENTER); // centered
        
        Log.d(TAG, "✓ LinearLayout created");
        
        // Background - four-color gradient (consistent with the rest of the UI)
        GradientDrawable background = new GradientDrawable();
        background.setOrientation(GradientDrawable.Orientation.TL_BR);
        background.setColors(new int[]{
            0xE0FF9D88,  // coral orange (88% opaque)
            0xE0FFB5C5,  // pink (88% opaque)
            0xE0E0B5DC,  // purple (88% opaque)
            0xE0A8C5E5   // blue (88% opaque)
        });
        background.setCornerRadius(60);
        layout.setBackground(background);
        
        // Close button (x) - declared first
        final android.widget.TextView closeButton = new android.widget.TextView(this);
        closeButton.setText("×");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setTextSize(32);
        closeButton.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.gravity = android.view.Gravity.CENTER; // vertically centered
        closeParams.leftMargin = 24;
        closeButton.setLayoutParams(closeParams);
        
        closeButton.setOnClickListener(v -> {
            // Closing is disabled while recording
            if (isRecording) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show();
                return;
            }
            // Stop the service (closes the floating window)
            stopSelf();
        });
        
        // Record/stop button (round, red)
        final View recordButton = new View(this);
        int buttonSize = 120;
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        recordParams.gravity = android.view.Gravity.CENTER; // vertically centered
        recordButton.setLayoutParams(recordParams);
        
        // Initial state: record button (solid circle)
        updateRecordButtonState(recordButton, false);
        
        // Click handler
        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
                updateRecordButtonState(recordButton, true);
                // Hide the close button while recording
                closeButton.setVisibility(View.GONE);
            } else {
                stopRecordingInternal(recordButton, closeButton);
                updateRecordButtonState(recordButton, false);
                // Note: the close button reappears only after recording stops (in stopRecordingInternal's Toast callback)
            }
        });
        
        layout.addView(recordButton);
        layout.addView(closeButton);
        
        Log.d(TAG, "✓ Buttons added to the layout");
        
        // Drag support
        final WindowManager.LayoutParams[] params = new WindowManager.LayoutParams[1];
        layout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params[0] == null) {
                    params[0] = (WindowManager.LayoutParams) floatingView.getLayoutParams();
                }
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params[0].x;
                        initialY = params[0].y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        params[0].x = initialX + (int) (initialTouchX - event.getRawX());
                        params[0].y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params[0]);
                        return true;
                }
                return false;
            }
        });
        
        Log.d(TAG, "✓ Floating-window layout created");
        return layout;
    }
    
    /**
     * Update the record button state.
     */
    private void updateRecordButtonState(View button, boolean recording) {
        GradientDrawable drawable = new GradientDrawable();
        
        if (recording) {
            // Stopped: square
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(20);
            drawable.setColor(Color.RED);
            drawable.setSize(60, 60); // slightly smaller inside the square
        } else {
            // Recording: circle
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(Color.RED);
        }
        
        drawable.setStroke(6, Color.WHITE); // white border
        button.setBackground(drawable);
    }
    
    /**
     * Ensure TaskService is connected.
     */
    private boolean ensureTaskServiceConnected() {
        if (taskService != null) {
            Log.d(TAG, "✓ TaskService connected");
            return true;
        }
        
        Log.w(TAG, "⚠ TaskService disconnected; retrying bind...");
        
        // Try to bind
        bindTaskService();
        
        // Wait for the connection (up to 3s)
        int attempts = 0;
        while (taskService == null && attempts < 30) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            attempts++;
        }
        
        if (taskService != null) {
            Log.d(TAG, "✅ TaskService reconnected");
            return true;
        } else {
            Log.e(TAG, "❌ TaskService reconnect failed (3s timeout)");
            return false;
        }
    }
    
    /**
     * Continuous rear-wake task - keeps the rear screen on while recording.
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && taskService != null) {
                try {
                    // Send WAKEUP to the rear display (displayId=1)
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // No logging to avoid spam
                } catch (Exception e) {
                    Log.w(TAG, "Rear-screen wake failed: " + e.getMessage());
                }
            }
            
            // Keep sending every 2s
            if (isRecording) {
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };
    
    /**
     * Start the continuous rear wake.
     */
    private void startRearScreenWakeup() {
        if (wakeupHandler != null) {
            // Fire once immediately, then keep sending
            wakeupHandler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ Continuous rear wake started (2s interval)");
        }
    }
    
    /**
     * Stop the continuous rear wake.
     */
    private void stopRearScreenWakeup() {
        if (wakeupHandler != null) {
            wakeupHandler.removeCallbacks(wakeupRearScreenRunnable);
            Log.d(TAG, "⏸️ Continuous rear wake stopped");
        }
    }
    
    /**
     * Start recording.
     */
    private void startRecording() {
        new Thread(() -> {
            // Ensure TaskService is connected
            if (!ensureTaskServiceConnected()) {
                Log.e(TAG, "TaskService not connected");
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "Service not ready, please try again", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            
            // Send one keycode wakeup to the rear before recording
            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(200); // wait for the wakeup to take effect
            } catch (Exception e) {
                Log.w(TAG, "Rear keycode wakeup before start failed: " + e.getMessage());
            }
            
            try {
                // Generate the filename
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                    .format(new java.util.Date());
                currentVideoPath = "/storage/emulated/0/Movies/MRSS_" + timestamp + ".mp4";
                
                // Create the save directory
                taskService.executeShellCommand("mkdir -p /storage/emulated/0/Movies");
                Log.d(TAG, "✓ Directory created");
                
                // Get the real rear display ID (mirrors the screenshot logic)
                String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
                String displayId = taskService.executeShellCommandWithResult(getDisplayIdCmd);
                
                if (displayId == null || displayId.trim().isEmpty()) {
                    displayId = "1"; // default to 1
                    Log.w(TAG, "⚠ Could not get the display ID; using default: 1");
                } else {
                    displayId = displayId.trim();
                    Log.d(TAG, "✓ Rear display ID: " + displayId);
                }
                
                // First check whether `screenrecord` is available
                String testCmd = "which screenrecord";
                String testResult = taskService.executeShellCommandWithResult(testCmd);
                Log.d(TAG, "screenrecord path: " + testResult);
                
                if (testResult == null || testResult.trim().isEmpty()) {
                    Log.e(TAG, "❌ screenrecord command not found");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "Device does not support the screenrecord command", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                
                // Launch recording using the full path
                String screenrecordPath = testResult.trim();
                String pidFile = "/data/local/tmp/mrss_record.pid";
                String logFile = "/data/local/tmp/mrss_record.log";
                
                // Start recording in the background, saving output to a log
                String recordCmd = String.format(
                    "%s --display-id %s --bit-rate 20000000 %s > %s 2>&1 & echo $! > %s",
                    screenrecordPath, displayId, currentVideoPath, logFile, pidFile
                );
                
                Log.d(TAG, "→ running record command: " + recordCmd);
                
                // Runs via TaskService (has Shizuku permission)
                boolean cmdSuccess = taskService.executeShellCommand(recordCmd);
                Log.d(TAG, "command result: " + cmdSuccess);
                
                if (!cmdSuccess) {
                    Log.e(TAG, "❌ Failed to start the record command");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "Failed to start screen recording", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // Wait for the process to start and the PID file to appear
                Thread.sleep(800);
                
                // Read the PID
                String pidStr = taskService.executeShellCommandWithResult("cat " + pidFile);
                Log.d(TAG, "PID file contents: " + pidStr);
                
                if (pidStr != null && !pidStr.trim().isEmpty()) {
                    try {
                        recordPid = Integer.parseInt(pidStr.trim());
                        Log.d(TAG, "✓ Recording PID: " + recordPid);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "⚠ Failed to parse PID: " + pidStr);
                    }
                } else {
                    Log.e(TAG, "❌ Could not read the PID file");
                }
                
                // Read the start log for errors
                String logContent = taskService.executeShellCommandWithResult("cat " + logFile);
                if (logContent != null && !logContent.trim().isEmpty()) {
                    Log.d(TAG, "Record process log: " + logContent);
                }
                
                // Verify the process is really running (several ways)
                Log.d(TAG, "→ verifying the record process...");
                
                // Method 1: ps aux
                String checkCmd1 = "ps -A | grep screenrecord";
                String checkResult1 = taskService.executeShellCommandWithResult(checkCmd1);
                Log.d(TAG, "ps -A result: " + checkResult1);
                
                // Method 2: ps -p
                String checkCmd2 = "ps -p " + recordPid;
                String checkResult2 = taskService.executeShellCommandWithResult(checkCmd2);
                Log.d(TAG, "ps -p result: " + checkResult2);
                
                // Method 3: check whether the file is being written
                Thread.sleep(500);
                String checkFile = "ls -l " + currentVideoPath;
                String fileCheck = taskService.executeShellCommandWithResult(checkFile);
                Log.d(TAG, "file check: " + fileCheck);
                
                // If the process is running or the file is being written, count it as success
                boolean processRunning = (checkResult1 != null && checkResult1.contains("screenrecord")) ||
                                       (checkResult2 != null && checkResult2.contains(String.valueOf(recordPid)));
                boolean fileExists = (fileCheck != null && !fileCheck.contains("No such file"));
                
                if (processRunning || fileExists) {
                    Log.d(TAG, "✓ Recording started (process running=" + processRunning + ", file exists=" + fileExists + ")");
                    isRecording = true;
                    
                    // Once recording starts, begin the continuous rear wake
                    startRearScreenWakeup();
                } else {
                    Log.e(TAG, "❌ Record process did not start");
                    
                    // Check the error cause
                    String errorCheck = "screenrecord --display-id 1 --help 2>&1 | head -n 5";
                    String errorMsg = taskService.executeShellCommandWithResult(errorCheck);
                    Log.e(TAG, "error: " + errorMsg);
                    
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "Recording process did not start", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // Update the notification and Toast
                new Handler(Looper.getMainLooper()).post(() -> {
                    Notification notification = buildNotification();
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, notification);
                    }
                    
                    Toast.makeText(this, "Rear screen recording started", Toast.LENGTH_SHORT).show();
                });
                
                Log.d(TAG, "✅ Recording started: " + currentVideoPath);
                
            } catch (Exception e) {
                Log.e(TAG, "Recording failed", e);
                e.printStackTrace();
                isRecording = false;
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "Screen recording failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    /**
     * Stop recording (with a button reference for state updates).
     */
    private void stopRecordingInternal(final View recordButton, final android.widget.TextView closeButton) {
        if (!isRecording) {
            return;
        }
        
        new Thread(() -> {
            // Ensure TaskService is connected (actively reconnect)
            if (!ensureTaskServiceConnected()) {
                Log.e(TAG, "❌ Failed to stop: TaskService not connected");
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "Service not ready, cannot stop recording", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            
            try {
                if (recordPid > 0) {
                    Log.d(TAG, "→ stopping the record process (PID=" + recordPid + ")");
                    
                    // Send SIGINT to stop recording (graceful)
                    String killCmd = "kill -2 " + recordPid;
                    boolean killed = taskService.executeShellCommand(killCmd);
                    
                    if (killed) {
                        Log.d(TAG, "✓ SIGINT sent");
                    } else {
                        Log.w(TAG, "⚠ SIGINT failed, trying SIGTERM");
                        taskService.executeShellCommand("kill " + recordPid);
                    }
                    
                    Thread.sleep(1000); // wait for the process to exit and save the file
                    
                    isRecording = false;
                    recordPid = -1;
                    
                    // Stop the continuous rear wake
                    stopRearScreenWakeup();
                    
                    // Verify the file exists
                    String checkFile = "ls -lh " + currentVideoPath;
                    String fileInfo = taskService.executeShellCommandWithResult(checkFile);
                    Log.d(TAG, "file info: " + fileInfo);
                    
                    // Refresh the media library
                    String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + currentVideoPath;
                    taskService.executeShellCommand(refreshCmd);
                    Log.d(TAG, "✓ Media library refreshed");
                    
                    // Update the notification and Toast
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Notification notification = buildNotification();
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) {
                            nm.notify(NOTIFICATION_ID, notification);
                        }
                        
                        if (fileInfo != null && !fileInfo.contains("No such file")) {
                            Toast.makeText(this, "Recording saved to Movies folder", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Recording may have failed, please check the Movies folder", Toast.LENGTH_LONG).show();
                        }
                        
                        // Show the close button
                        if (closeButton != null) {
                            closeButton.setVisibility(View.VISIBLE);
                        }
                    });
                    
                    Log.d(TAG, "✅ Recording stopped and saved: " + currentVideoPath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recording", e);
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * Stop recording (compatibility method).
     */
    private void stopRecording() {
        stopRecordingInternal(null, null);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Stop the continuous rear wake
        stopRearScreenWakeup();
        
        // Stop recording
        if (isRecording) {
            stopRecording();
        }
        
        // Remove the floating window
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
                Log.d(TAG, "✓ Floating window removed");
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove the floating window", e);
            }
        }
        
        // Unbind TaskService
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
        
        instance = null;
        Log.d(TAG, "⚠ Service destroyed");
    }
}
