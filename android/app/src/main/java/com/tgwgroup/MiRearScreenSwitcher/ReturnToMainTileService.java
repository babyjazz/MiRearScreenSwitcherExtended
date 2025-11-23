package com.tgwgroup.MiRearScreenSwitcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * Quick Settings Tile - Return active rear-screen task to the main display.
 */
public class ReturnToMainTileService extends TileService {
    private static final String TAG = "ReturnToMainTile";

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

    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                bindTaskService();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000);
            }
        }
    };

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
        unbindTaskService();
    }

    @Override
    public void onClick() {
        super.onClick();
        unlockAndRun(this::returnToMainDisplay);
    }

    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return;
            }
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    private void unbindTaskService() {
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
    }

    private void returnToMainDisplay() {
        new Thread(() -> {
            try {
                if (taskService == null) {
                    showToast(getString(R.string.toast_switch_failed));
                    scheduleReconnectTaskService();
                    return;
                }

                // Prefer the last moved task tracked by SwitchToRearTileService.
                String target = SwitchToRearTileService.getLastMovedTask();

                // Fallback to whatever is currently on the rear display.
                if (target == null || !target.contains(":")) {
                    target = taskService.getForegroundAppOnDisplay(1);
                }

                if (target == null || !target.contains(":")) {
                    showToast(getString(R.string.toast_not_on_rear));
                    return;
                }

                String[] parts = target.split(":");
                String packageName = parts[0];
                int taskId = Integer.parseInt(parts[1]);

                boolean success = taskService.moveTaskToDisplay(taskId, 0);
                if (success) {
                    showToast(getString(R.string.toast_return_to_main));
                    Tile tile = getQsTile();
                    if (tile != null) {
                        tile.setState(Tile.STATE_INACTIVE);
                        tile.updateTile();
                    }
                } else {
                    showToast(getString(R.string.toast_switch_failed));
                }
            } catch (Exception e) {
                Log.e(TAG, "Return to main failed", e);
                showToast(getString(R.string.toast_switch_failed));
            }
        }).start();
    }

    private void showToast(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }
}
