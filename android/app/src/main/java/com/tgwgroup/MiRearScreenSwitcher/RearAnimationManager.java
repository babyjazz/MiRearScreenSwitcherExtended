/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 *
 * Chief Tester: 汐木泽 (Xi Mu Ze)
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.util.Log;

/**
 * Rear-screen animation manager.
 * Manages charging and notification animations and their interrupt mechanism.
 */
public class RearAnimationManager {
    private static final String TAG = "RearAnimationManager";
    
    // Animation types
    public enum AnimationType {
        NONE,           // No animation
        CHARGING,       // Charging animation
        NOTIFICATION,   // Notification animation
        MEDIA           // Media playback animation
    }
    
    // Currently playing animation type
    private static volatile AnimationType currentAnimation = AnimationType.NONE;
    
    // Whether the current animation should restore the official Launcher (no restore when interrupted by a newer animation)
    private static volatile boolean shouldRestoreOnDestroy = true;
    
    // V3.5: whether the interrupted charging animation was always-on
    private static volatile boolean interruptedChargingWasAlwaysOn = false;

    // Whether media playback was interrupted by a notification (restore media when the notification ends)
    private static volatile boolean mediaInterruptedByNotification = false;
    
    /**
     * Start playing an animation.
     * @param type animation type
     * @return the interrupted old animation type (NONE if there was none)
     */
    public static synchronized AnimationType startAnimation(AnimationType type) {
        if (type == AnimationType.NONE) {
            Log.w(TAG, "⚠️ Attempted to start NONE animation, ignoring");
            return AnimationType.NONE;
        }
        
        AnimationType oldAnimation = currentAnimation;
        
        if (oldAnimation != AnimationType.NONE) {
            Log.d(TAG, String.format("🔄 New animation [%s] interrupted old [%s]", type, oldAnimation));
            // Mark the old animation as not restoring the official Launcher
            shouldRestoreOnDestroy = false;
        } else {
            Log.d(TAG, String.format("▶️ Starting animation [%s]", type));
        }
        
        // Set the new animation as current
        currentAnimation = type;
        shouldRestoreOnDestroy = true;  // new animations restore by default
        
        return oldAnimation;  // return the interrupted old animation
    }
    
    /**
     * V3.5: Mark the interrupted charging animation as always-on.
     */
    public static synchronized void markInterruptedChargingAsAlwaysOn(boolean alwaysOn) {
        interruptedChargingWasAlwaysOn = alwaysOn;
        Log.d(TAG, "🔖 Interrupted charging always-on flag: " + alwaysOn);
    }
    
    /**
     * V3.5: Whether the interrupted charging animation should resume.
     */
    public static synchronized boolean shouldResumeChargingAnimation() {
        return interruptedChargingWasAlwaysOn;
    }
    
    /**
     * V3.5: Clear the charging always-on flag.
     */
    public static synchronized void clearChargingAlwaysOnFlag() {
        interruptedChargingWasAlwaysOn = false;
    }

    /**
     * Mark that media playback was interrupted by a notification (resume media when the notification ends).
     */
    public static synchronized void markMediaInterruptedByNotification() {
        mediaInterruptedByNotification = true;
    }

    /**
     * Consume the "media interrupted by notification" flag (cleared immediately to avoid duplicate resumes).
     */
    public static synchronized boolean consumeMediaInterruptedByNotificationFlag() {
        boolean was = mediaInterruptedByNotification;
        mediaInterruptedByNotification = false;
        return was;
    }
    
    /**
     * End an animation.
     * @param type animation type
     * @return whether the official Launcher should be restored
     */
    public static synchronized boolean endAnimation(AnimationType type) {
        if (currentAnimation != type) {
            Log.w(TAG, String.format("⚠️ Tried to end animation [%s], but current is [%s]", type, currentAnimation));
            return false;  // not the current animation, no restore needed
        }
        
        boolean shouldRestore = shouldRestoreOnDestroy;
        
        if (shouldRestore) {
            Log.d(TAG, String.format("⏹️ Animation [%s] ended normally; restoring official Launcher", type));
        } else {
            Log.d(TAG, String.format("⏹️ Animation [%s] ended interrupted; not restoring official Launcher", type));
        }
        
        currentAnimation = AnimationType.NONE;
        shouldRestoreOnDestroy = true;
        
        return shouldRestore;
    }
    
    /**
     * Whether an animation is currently playing.
     */
    public static synchronized boolean isAnimationPlaying() {
        return currentAnimation != AnimationType.NONE;
    }
    
    /**
     * Get the current animation type.
     */
    public static synchronized AnimationType getCurrentAnimation() {
        return currentAnimation;
    }
    
    /**
     * Interrupt an animation of the given type.
     */
    private static void interruptAnimation(AnimationType type) {
        android.content.Intent intent;
        String action;
        
        switch (type) {
            case CHARGING:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION";
                break;
            case MEDIA:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_MEDIA_ANIMATION";
                break;
            default:
                return;
        }
        
        try {
            // Broadcast via a static context (would need the Service);
            // for now just log; actual send is handled by the caller
            Log.d(TAG, String.format("🔔 Preparing to send interrupt broadcast: %s", action));
        } catch (Exception e) {
            Log.e(TAG, "Failed to interrupt animation", e);
        }
    }
    
    /**
     * Send the interrupt broadcast (called by the Service).
     */
    public static void sendInterruptBroadcast(android.content.Context context, AnimationType type) {
        String action;
        
        switch (type) {
            case CHARGING:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION";
                break;
            case MEDIA:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_MEDIA_ANIMATION";
                break;
            default:
                return;
        }
        
        try {
            android.content.Intent intent = new android.content.Intent(action);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            Log.d(TAG, String.format("✓ Interrupt broadcast sent: %s", action));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send interrupt broadcast", e);
        }
    }
}

