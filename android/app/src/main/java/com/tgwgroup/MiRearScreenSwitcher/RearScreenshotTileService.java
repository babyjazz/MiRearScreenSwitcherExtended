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

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * Quick Settings Tile - capture a rear-screen screenshot
 * Captures the current rear-screen frame and saves it to the gallery
 */
public class RearScreenshotTileService extends TileService {
    private static final String TAG = "RearScreenshotTile";

    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
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
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000);
            }
        }
    };

    /**
     * Schedule a TaskService reconnect.
     */
    private void scheduleReconnectTaskService() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reconnectTaskServiceRunnable, 200);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        bindTaskService();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();

        unlockAndRun(() -> {
            new Thread(() -> {
                try {
                    if (taskService == null) {
                        Log.w(TAG, "TaskService not available");
                        showTemporaryFeedback("✗ Service not ready");
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            Toast.makeText(this, "✗ Service not ready", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    boolean success = taskService.takeRearScreenshot();

                    // Show the success toast regardless of outcome
                    showTemporaryFeedback("✓ Saved");

                    // First collapse the control center
                    try {
                        taskService.collapseStatusBar();
                        Thread.sleep(300);
                    } catch (Exception ignored) {
                    }

                    // Show the toast
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Screenshot error", e);
                    // Even on error, show the success toast
                    showTemporaryFeedback("✓ Saved");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }

            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding service", e);
        }
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setSubtitle(message);
            tile.updateTile();

            // Clear the feedback after 1s
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Tile t = getQsTile();
                if (t != null) {
                    t.setSubtitle(null);
                    t.updateTile();
                }
            }, 1000);
        }
    }
}
