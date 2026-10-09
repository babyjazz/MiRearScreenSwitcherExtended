/*
 * Author: AntiOblivionis
 * Copied header convention from other files in this package.
 *
 * Single background thread that serially runs all TaskService shell launch/poll work (Phase 2, N1).
 * The main thread calls post() and returns immediately, no longer sleeping/polling on the main loop.
 * State/UI cleanup still gets posted back to the main loop (e.g. releaseWakeLock).
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Background executor for rear-screen shell work.
 * The three launch flows (charging, notification, media) share this single HandlerThread,
 * keeping shell commands/sleeps/polling off the main thread and naturally serialized so the flows never fight. */
public class RearShell {
    private static final String TAG = "RearShell";

    private static RearShell instance;

    private final Handler handler;

    private RearShell() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private static RearShell get() {
        synchronized (RearShell.class) {
            if (instance == null) {
                instance = new RearShell();
            }
            return instance;
        }
    }

    /**
     * Run shell work serially on a single background thread; the main thread returns immediately.
     * @return whether it was successfully queued.
     */
    public static boolean post(Runnable r) {
        Handler h = get().handler;
        boolean ok = h.post(r);
        if (!ok) {
            Log.w(TAG, "Failed to queue background shell work (thread may not be ready)");
        }
        return ok;
    }

    /** Post state-cleanup work back to the main thread (e.g. releaseWakeLock). */
    public static void postToMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
