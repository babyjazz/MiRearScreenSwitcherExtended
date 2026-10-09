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
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

/**
 * Transparent Activity that lights up the rear screen.
 * Based on MiRearScreenNotification.
 * V2.1: supports dynamic rotation control.
 */
public class RearScreenWakeupActivity extends Activity {
    private static final String TAG = "RearScreenWakeup";
    
    // Static storage for the rear-screen rotation orientation
    private static int sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    
    /**
     * V2.1: Set the rear-screen rotation orientation (called from outside).
     * @param rotation orientation (0=0°, 1=90°, 2=180°, 3=270°)
     */
    public static void setRearDisplayRotation(int rotation) {
        // Map the rotation value to ActivityInfo constants
        switch (rotation) {
            case 0:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case 1:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case 2:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                break;
            case 3:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                break;
            default:
                sRearDisplayRotation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Apply the rotation setting
        if (sRearDisplayRotation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            setRequestedOrientation(sRearDisplayRotation);
        }
        
        // Get the current display
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        // If on the main display, do nothing
        if (displayId == 0) {
            return;
        }
        
        // --- Code below only runs on the rear display (displayId == 1) ---
        
        // Critical: light up and keep the rear screen on
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // Adapt to the new API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // Delayed close (gives the screen enough time to light up)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            finish();
        }, 1000); // close after 1s
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Ensure the screen stays lit again
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
    }
    
    @Override
    public void finish() {
        super.finish();
        // Disable transition animation
        overridePendingTransition(0, 0);
    }
}

