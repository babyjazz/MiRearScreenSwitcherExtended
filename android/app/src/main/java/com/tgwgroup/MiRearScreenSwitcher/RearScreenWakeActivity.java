/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Persistent always-on rear-screen Activity.
 * Strategy changes:
 * 1. no longer auto-closes; stays alive to keep FLAG_KEEP_SCREEN_ON
 * 2. TYPE_APPLICATION_OVERLAY keeps it visible, without FLAG_NOT_TOUCHABLE so touches still work
 * 3. fullscreen + fully transparent (alpha=0); invisible to the user but considered visible by the system
 * 
 * Key findings (3 iterations):
 * V1: FLAG_NOT_FOCUSABLE -> immediate onPause/onStop (18ms)
 * V2: no FLAG_NOT_FOCUSABLE + off-screen (-1000,-1000) -> still onStop (109ms)
 * V3: window must be on-screen (0,0) + alpha=0 transparent -> final solution ✅
 */
public class RearScreenWakeActivity extends Activity {
    private static final String TAG = "RearScreenWakeActivity";
    private static RearScreenWakeActivity instance = null;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Save the instance reference
        instance = this;
        
        // Get the current display
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        // V6: distinguish main-screen vs rear-screen strategies
        boolean isMainDisplay = (displayId == 0);
        
        if (isMainDisplay) {
        } else {
        }
        
        // --- Common settings (main and rear) ---
        
        // Set pure-black transparent content (OLED optimization)
        View rootView = new View(this);
        rootView.setBackgroundColor(0x00000000); // fully transparent
        setContentView(rootView);
        
        // Key change: TYPE_APPLICATION_OVERLAY keeps the window visible
        // This window type is not auto-hidden by the system
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        
        // Different window configs for main vs rear
        WindowManager.LayoutParams params = getWindow().getAttributes();
        
        if (isMainDisplay) {
            // Main: tiny window + no focus, completely non-intrusive
            params.width = 1;   // 1px wide
            params.height = 1;  // 1px tall
            params.x = 0;
            params.y = 0;
            params.alpha = 0.0f;  // fully transparent
        } else {
            // Rear: fullscreen + focusable, keeps the screen on
            params.screenBrightness = 0.01f;  // 1% brightness
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.x = 0;
            params.y = 0;
            params.alpha = 0.0f;  // fully transparent
        }
        getWindow().setAttributes(params);
        
        // Different flags for main vs rear
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        
        if (isMainDisplay) {
            // Main: add FLAG_NOT_FOCUSABLE to avoid affecting user input
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            // Rear: add FLAG_KEEP_SCREEN_ON to keep the screen on
            // Drop FLAG_SHOW_WHEN_LOCKED to avoid interfering with touch gestures while locked
            flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        
        getWindow().addFlags(flags);
        
        // Removed setShowWhenLocked to avoid touch interference while locked
        // The Activity hides while locked, but the Service keeps the Launcher disabled
        // **No longer auto-closes** - stays alive to keep the screen on
        // Removed the old postDelayed(finish())
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Get the current display
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        boolean isMainDisplay = (displayId == 0);
        // Ensure the flags stay in effect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // Re-apply flags (per display)
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        
        if (isMainDisplay) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        
        getWindow().addFlags(flags);
        
        // Re-apply window params
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (isMainDisplay) {
            params.width = 1;
            params.height = 1;
            params.x = 0;
            params.y = 0;
            params.alpha = 0.0f;
        } else {
            params.screenBrightness = 0.01f;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.x = 0;
            params.y = 0;
            params.alpha = 0.0f;
        }
        getWindow().setAttributes(params);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
    }
    
    @Override
    protected void onStop() {
        super.onStop();
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Immediately return focus (attempt to give it back to the cast app)
            // Use moveTaskToBack instead of finish to keep the Activity alive
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    moveTaskToBack(true);
                }
            }, 100); // 100ms delay
        } else {
            // Try to get the current foreground task info
            logCurrentTaskStack();
        }
    }
    
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
    }
    
    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }
    
    /**
     * Try to record the current task-stack info (debugging).
     */
    private void logCurrentTaskStack() {
        try {
            // Log simply; avoid permission-gated APIs
        } catch (Exception e) {
            Log.e(TAG, "Error logging task stack", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }
    
    /**
     * Static method to close the Activity from outside.
     * Call RearScreenWakeActivity.closeIfExists() to stop always-on.
     */
    public static void closeIfExists() {
        if (instance != null) {
            instance.finish();
        }
    }
    
    /**
     * Whether the Activity is alive.
     */
    public static boolean isAlive() {
        return instance != null;
    }
    
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0); // disable transition animation
    }
}

