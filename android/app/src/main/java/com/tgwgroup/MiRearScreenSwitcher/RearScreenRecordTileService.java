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

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * Quick Settings Tile - rear-screen recording.
 * Toggles the recording floating window.
 */
public class RearScreenRecordTileService extends TileService {
    private static final String TAG = "RearScreenRecordTile";
    
    @Override
    public void onStartListening() {
        super.onStartListening();
        
        Tile tile = getQsTile();
        if (tile != null) {
            // Check whether the floating window is showing
            boolean isRecording = ScreenRecordService.isRunning();
            tile.setState(isRecording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
    
    @Override
    public void onClick() {
        super.onClick();
        
        unlockAndRun(() -> {
            // Check overlay permission
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "No overlay permission");
                
                Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_LONG).show();
                
                // Jump to the overlay permission settings page
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                
                return;
            }
            
            // Check whether already running
            if (ScreenRecordService.isRunning()) {
                // Floating window already showing; dismiss it (stop service)
                stopService(new Intent(this, ScreenRecordService.class));
                Log.d(TAG, "✓ Recording floating window closed");
                
                // Update tile state
                Tile tile = getQsTile();
                if (tile != null) {
                    tile.setState(Tile.STATE_INACTIVE);
                    tile.updateTile();
                }
            } else {
                // Start the recording service (show the floating window)
                Intent intent = new Intent(this, ScreenRecordService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                
                Log.d(TAG, "✓ Recording floating window started");
                
                // Update tile state
                Tile tile = getQsTile();
                if (tile != null) {
                    tile.setState(Tile.STATE_ACTIVE);
                    tile.updateTile();
                }
            }
        });
    }
}





