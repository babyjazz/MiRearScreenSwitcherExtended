package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.util.Log;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The only Activity on the rear screen. Renders the single rear pager
 * (Music / Notification / Charging pages) and restores the rear on teardown.
 */
public class RearHostActivity extends Activity {
    private static final String TAG = "RearHostActivity";

    private static volatile RearHostActivity instance = null;
    private static volatile boolean onRear = false;
    private static volatile boolean visible = false;
    private static volatile long shownSince = 0;
    private static volatile long lastVisibleMs = Long.MAX_VALUE;
    private static volatile boolean homeRequested = false;

    public static boolean consumeHomeRequested() {
        boolean h = homeRequested;
        homeRequested = false;
        return h;
    }

    /** Any live instance, including a main-display placeholder waiting to be moved. */
    public static RearHostActivity getInstance() { return instance; }
    /** The instance, only once it is initialized on the rear display. */
    public static RearHostActivity getRearInstance() { return onRear ? instance : null; }
    public static boolean isVisible() { return visible; }
    public static long getShownSince() { return shownSince; }
    public static long getLastVisibleMs() { return lastVisibleMs; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GestureDetector gestureDetector;
    private FrameLayout rootFrame;
    private RearPagerView pagerView;
    private boolean finishedByUs = false;

    public RearPagerView getPagerView() { return pagerView; }

    public static void setDotsVisible(boolean visible) {
        RearHostActivity host = getInstance();
        if (host != null && host.pagerView != null) {
            host.pagerView.setDotsVisible(visible);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        // HyperOS handles rear double-tap itself (go home) unless the window opts out.
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            // Hidden-API blocklist hides the field from plain reflection; look it up through Class.getDeclaredField reflectively.
            java.lang.reflect.Method getField = Class.class.getDeclaredMethod("getDeclaredField", String.class);
            java.lang.reflect.Field f = (java.lang.reflect.Field) getField.invoke(WindowManager.LayoutParams.class, "miuiFlags");
            f.setAccessible(true);
            f.setInt(lp, f.getInt(lp) | 256); // MIUI_FLAG_DISABLE_SUBSCREEN_DOUBLE_TAP
            getWindow().setAttributes(lp);
            Breadcrumb.log(getApplicationContext(), "Gesture", "miuiFlags set");
        } catch (Throwable t) {
            Breadcrumb.log(getApplicationContext(), "Gesture", "miuiFlags failed " + t);
        }
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private boolean homeTriggered = false;

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!onRear) return false;
                Breadcrumb.log(getApplicationContext(), "Gesture", "onDoubleTap page="
                        + (pagerView != null ? pagerView.getCurrentPage() : -1)
                        + " notiActive=" + RearStack.contains(RearStack.Type.NOTIFICATION));
                // Double tap is only used by MRSS while the pager is front. Consume it so
                // HyperOS does not treat it as "go home".
                if (pagerView != null
                        && pagerView.getCurrentPage() == RearPagerState.NOTIFICATION
                        && RearStack.contains(RearStack.Type.NOTIFICATION)) {
                    RearHost.hide(getApplicationContext(), RearStack.Type.NOTIFICATION);
                } else {
                    Breadcrumb.log(getApplicationContext(), "Gesture", "dim rear screen");
                    RearHost.dimRearScreen();
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                homeTriggered = false;
                return false;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (homeTriggered || !onRear || e1 == null || e2 == null) return false;
                Breadcrumb.log(getApplicationContext(), "Gesture", "scroll dy=" + (e2.getY() - e1.getY()) + " dx=" + (e2.getX() - e1.getX()));
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (dy < -dp(60) && Math.abs(dy) > Math.abs(dx)) {
                    homeTriggered = true;
                    RearHost.goHome(getApplicationContext());
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (onRear && e1 != null && e2 != null
                        && e2.getY() - e1.getY() < -dp(20)
                        && Math.abs(velocityY) > Math.abs(velocityX) * 1.2f
                        && velocityY < -600) {
                    homeTriggered = true;
                    RearHost.goHome(getApplicationContext());
                    return true;
                }
                return false;
            }
        });
        // Launched on the main display it is only a placeholder: wait to be moved, onResume initializes on the rear
        initIfOnRear();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        initIfOnRear();
        onFeatureChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        RearHost.cancelMediaRecovery();
        initIfOnRear(); // moved from the main placeholder to the rear
        if (pagerView != null) {
            pagerView.restoreAfterWake(getApplicationContext());
            onFeatureChanged();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        shownSince = System.currentTimeMillis();
        // Locked and idle, HyperOS sleeps the rear ~1s after wake and removes our task. Waking again once the content is
        // actually visible keeps it lit (a wake right after move-stack is too early).
        if (onRear) {
            handler.postDelayed(() -> {
                if (!visible) return;
                try {
                    ITaskService ts = RearHost.taskService();
                    if (ts != null) ts.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                } catch (Throwable t) {
                    Log.w(TAG, "Wake after visible failed: " + t.getMessage());
                }
            }, 500);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        lastVisibleMs = System.currentTimeMillis() - shownSince;
        // Not finishing = swiped away / covered / rear slept; the task survives in the background
        if (isFinishing() || !onRear) return;
        if (pagerView != null) pagerView.saveForSleep(getApplicationContext());
        // With the pager as the idle rear home, a swipe-away that leaves the task alive is fine:
        // bring it back if the rear is still lit and the task was simply covered.
        if (RearHostActivity.isVisible() == false) {
            // No per-feature Activity to recover: the pager host is the whole rear UI.
            // Media recovery logic below is kept for parity with the old media page.
            if (RearStack.top() == RearStack.Type.MEDIA) {
                RearHost.scheduleMediaRecovery(this);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pagerView != null) pagerView.unbind();
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "destroy onRear=" + onRear + " finishedByUs=" + finishedByUs);
        if (this != instance) return;
        boolean wasOnRear = onRear;
        instance = null;
        onRear = false;
        visible = false;

        if (!wasOnRear) {
            if (RearStack.isEmpty()) RearHost.restoreRear(getApplicationContext());
            return;
        }
        if (!finishedByUs) {
            // Removed by HyperOS or the user: with the pager as the idle rear home we do not relaunch here;
            // keep old parity: drop notification and let media recovery decide.
            RearStack.remove(RearStack.Type.NOTIFICATION);
            if (RearStack.top() == RearStack.Type.MEDIA) {
                RearHost.scheduleMediaRecovery(getApplicationContext());
            }
        }
        if (RearStack.isEmpty()) RearHost.restoreRear(getApplicationContext());
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** End the host because the rear is no longer wanted. */
    public void finishHost() {
        finishedByUs = true;
        runOnUiThread(this::finish);
    }

    public void updateBattery(int level) {
        runOnUiThread(() -> {
            Bundle b = RearStack.get(RearStack.Type.CHARGING);
            if (b != null) b.putInt("batteryLevel", level);
            onFeatureChanged();
        });
    }

    /** Refresh only the music page (e.g. track change while the rear is dim/stopped). Safe from any thread. */
    public void refreshMusic() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::refreshMusic);
            return;
        }
        if (!onRear || isDestroyed() || pagerView == null) return;
        pagerView.bindMusic(RearStack.get(RearStack.Type.MEDIA));
    }

    /** Re-bind all three pages and auto-switch the pager. Safe from any thread. */
    public void onFeatureChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::onFeatureChanged);
            return;
        }
        if (!onRear || isDestroyed() || pagerView == null) return;
        pagerView.bindMusic(RearStack.get(RearStack.Type.MEDIA));
        pagerView.bindNotification(RearStack.get(RearStack.Type.NOTIFICATION));
        pagerView.bindCharging(RearStack.get(RearStack.Type.CHARGING));
        pagerView.onFeatureChanged(
            RearStack.contains(RearStack.Type.NOTIFICATION),
            RearStack.contains(RearStack.Type.CHARGING),
            RearStack.contains(RearStack.Type.MEDIA));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (onRear && gestureDetector != null && gestureDetector.onTouchEvent(ev)) {
            return true;
        }
        Breadcrumb.log(getApplicationContext(), "Gesture", "touch ev=" + ev.getAction() + " x=" + ev.getX() + " y=" + ev.getY());
        return super.dispatchTouchEvent(ev);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int displayId() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return getDisplay() != null ? getDisplay().getDisplayId() : 0;
        }
        return 0;
    }

    // The move-stack from the main-display placeholder to the rear does not always re-run onResume
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initIfOnRear();
    }

    private void initIfOnRear() {
        if (onRear || displayId() != 1) return;
        Log.d(TAG, "init on rear");
        forceRearScreenDensityBeforeInflate();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && getDisplay() != null) {
            android.view.DisplayCutout c = getDisplay().getCutout();
            if (c != null) DisplayInfoCache.getInstance().setCutout(new android.graphics.Rect(
                c.getSafeInsetLeft(), c.getSafeInsetTop(), c.getSafeInsetRight(), c.getSafeInsetBottom()));
            Breadcrumb.log(getApplicationContext(), "RearHost", "cutout=" + DisplayInfoCache.getInstance().getCachedInfo().cutout);
        }
        rootFrame = new FrameLayout(this);
        pagerView = new RearPagerView(this, rootFrame);
        rootFrame.addView(pagerView.getView());
        setContentView(rootFrame);
        onRear = true;
        onFeatureChanged();
    }

    /** Must run before inflating any layout, or it renders at the main screen's DPI. */
    private void forceRearScreenDensityBeforeInflate() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;
            if (rearScreenDpi <= 0) {
                ITaskService ts = null;
                for (int retry = 0; retry < 3 && ts == null; retry++) {
                    ts = RearHost.taskService();
                    if (ts == null) {
                        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                if (ts == null) return;
                DisplayInfoCache.getInstance().initialize(ts);
                rearScreenDpi = DisplayInfoCache.getInstance().getCachedInfo().densityDpi;
            }
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            getResources().updateConfiguration(config, metrics);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply rear-screen DPI", e);
        }
    }
}
