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

import android.graphics.Rect;
import android.util.Log;

/**
 * Display info cache.
 * Fetched once at app startup, then reused from cache.
 */
public class DisplayInfoCache {
    private static final String TAG = "DisplayInfoCache";
    
    // Singleton
    private static volatile DisplayInfoCache instance;
    
    // Cached rear-screen info
    private RearDisplayHelper.RearDisplayInfo cachedInfo;
    private boolean initialized = false;
    
    private DisplayInfoCache() {}
    
    public static DisplayInfoCache getInstance() {
        if (instance == null) {
            synchronized (DisplayInfoCache.class) {
                if (instance == null) {
                    instance = new DisplayInfoCache();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize the cache (call once at app startup).
     */
    public synchronized void initialize(ITaskService taskService) {
        if (initialized) {
            Log.d(TAG, "ℹ️ Already initialized, skipping");
            return;
        }
        
        try {
            Log.d(TAG, "🔄 Fetching rear-screen info...");
            cachedInfo = RearDisplayHelper.getRearDisplayInfo(taskService);
            initialized = true;
            
            Log.d(TAG, String.format("✅ Rear-screen info cached: %dx%d, DPI=%d, Cutout=%s",
                cachedInfo.width, cachedInfo.height, cachedInfo.densityDpi,
                cachedInfo.hasCutout() ? cachedInfo.cutout.toString() : "none"));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Initialization failed", e);
            // Set defaults
            cachedInfo = new RearDisplayHelper.RearDisplayInfo();
            cachedInfo.width = 904;
            cachedInfo.height = 572;
            cachedInfo.densityDpi = 450;
            cachedInfo.cutout = new Rect(0, 0, 0, 0);
            initialized = true;
            Log.w(TAG, "⚠️ Using default rear-screen info");
        }
    }
    
    /**
     * Get the cached rear-screen info.
     */
    public RearDisplayHelper.RearDisplayInfo getCachedInfo() {
        if (!initialized) {
            Log.w(TAG, "⚠️ Cache not initialized, returning defaults");
            RearDisplayHelper.RearDisplayInfo defaultInfo = new RearDisplayHelper.RearDisplayInfo();
            defaultInfo.width = 904;
            defaultInfo.height = 572;
            defaultInfo.densityDpi = 450;
            defaultInfo.cutout = new Rect(0, 0, 0, 0);
            return defaultInfo;
        }
        return cachedInfo;
    }
    
    /**
     * Force a refresh (to invalidate the cache).
     */
    public synchronized void refresh(ITaskService taskService) {
        initialized = false;
        initialize(taskService);
    }
    
    /**
     * Whether the cache has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Clear the cache (for testing or reset).
     */
    public synchronized void clear() {
        cachedInfo = null;
        initialized = false;
        Log.d(TAG, "🗑️ Cache cleared");
    }
}

