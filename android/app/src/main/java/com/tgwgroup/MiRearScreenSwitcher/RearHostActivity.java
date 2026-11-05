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

    /** Swipe up: dismiss the notification on the noti page, otherwise home the pager. */
    private void swipeUpAction() {
        if (pagerView != null
                && pagerView.getCurrentPage() == RearPagerState.NOTIFICATION
                && RearStack.contains(RearStack.Type.NOTIFICATION)) {
            RearHost.hide(getApplicationContext(), RearStack.Type.NOTIFICATION);
        } else {
            RearHost.goHome(getApplicationContext());
        }
    }

    /** Noti page with an active notification: swipes up/down dismiss the notification, not the pager. */
    private boolean isNotiDismissContext() {
        return pagerView != null
                && pagerView.getCurrentPage() == RearPagerState.NOTIFICATION
                && RearStack.contains(RearStack.Type.NOTIFICATION);
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

    // Drag-to-dismiss: pager follows the finger down, dismiss past 40% of the screen.
    private boolean dragging = false;
    private boolean dragPossible = false;
    private float downX, downY;

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
                Breadcrumb.log(getApplicationContext(), "Gesture", "dim rear screen");
                RearHost.dimRearScreen();
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
                    swipeUpAction();
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
                    swipeUpAction();
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
        boolean wasBackgrounded = !visible;
        visible = true;
        shownSince = System.currentTimeMillis();
        // Slide up from the bottom whenever the pager appears (cold launch, bring-front
        // after goHome, wake from rear sleep). Snap-back drags never stop/start, no replay.
        if (wasBackgrounded && onRear && rootFrame != null) {
            handler.removeCallbacksAndMessages(null);
            rootFrame.animate().cancel();
            rootFrame.setTranslationY(getResources().getDisplayMetrics().heightPixels);
            rootFrame.animate()
                    .translationY(0)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
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
        // Cleanup follows visibility, not intent: the user put the stock rear back, so revive the
        // launcher (killed by the locked frontHost path) or official rear apps stay gone until reboot.
        // A feature still active means goHome's auto-return owns the rear; a second launcher start
        // only races the bring-front `am start` and can land the launcher above the pager.
        if (RearStack.isEmpty()) {
            new Thread(() -> {
                try {
                    ITaskService ts = RearHost.taskService();
                    if (ts != null) ts.executeShellCommand("am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher");
                } catch (Throwable t) {
                    Log.w(TAG, "Launcher revive on stop failed: " + t.getMessage());
                }
            }).start();
        }
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
        // Always-on charging: the rear must not time out, or the animation freezes behind a dozing panel.
        boolean keepAwake = RearStack.top() == RearStack.Type.CHARGING
            && getSharedPreferences("mrss_settings", MODE_PRIVATE).getBoolean("charging_always_on_enabled", false);
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (onRear && handleDragToDismiss(ev)) {
            return true;
        }
        if (onRear && !dragging && gestureDetector != null && gestureDetector.onTouchEvent(ev)) {
            return true;
        }
        Breadcrumb.log(getApplicationContext(), "Gesture", "touch ev=" + ev.getAction() + " x=" + ev.getX() + " y=" + ev.getY());
        return super.dispatchTouchEvent(ev);
    }

    /** Pager follows the finger downward; release past 40% of the screen dismisses it. */
    private boolean handleDragToDismiss(MotionEvent ev) {
        if (rootFrame == null || !onRear) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragPossible = true;
                dragging = false;
                downX = ev.getX();
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && dragPossible) {
                    // Vertical intent only; horizontal swipes keep paging.
                    if (Math.abs(dy) > dp(8) && Math.abs(dy) > Math.abs(dx)) {
                        if (dy < 0) {
                            dragPossible = false; // upward is goHome's job, not a drag
                        } else {
                            dragging = true;
                        }
                    }
                }
                if (dragging) rootFrame.setTranslationY(dy);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    if (isNotiDismissContext()) {
                        // On the noti page the drag always dismisses the notification (release = commit),
                        // then the pager slides back up to whatever remains; hide() ends the pager if the stack is empty.
                        finishDragToNotiDismiss();
                    } else {
                        float moved = ev.getY() - downY;
                        boolean dismiss = ev.getActionMasked() == MotionEvent.ACTION_UP
                                && moved > 0
                                && getResources().getDisplayMetrics().heightPixels > 0
                                && moved > 0.4f * getResources().getDisplayMetrics().heightPixels;
                        finishDrag(dismiss);
                    }
                }
                dragPossible = false;
                dragging = false;
                break;
        }
        return dragging;
    }

    /** Snap back or slide the rest of the way out, then go home with the translation reset. */
    private void finishDrag(boolean dismiss) {
        int height = getResources().getDisplayMetrics().heightPixels;
        rootFrame.animate()
                .translationY(dismiss ? height : 0)
                .setDuration(dismiss ? 150 : 200)
                .withEndAction(() -> {
                    // Reset in the animation's end action, never postDelayed: a stale timer
                    // fires mid-entrance if a bring-front races the dismiss (e.g. charger plug).
                    rootFrame.setTranslationY(0);
                })
                .start();
        if (dismiss) {
            RearHost.goHome(getApplicationContext());
        }
    }

    /** Noti-page drag release: drop the notification and slide the pager back to whatever remains. */
    private void finishDragToNotiDismiss() {
        RearHost.hide(getApplicationContext(), RearStack.Type.NOTIFICATION);
        rootFrame.animate()
                .translationY(0)
                .setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
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
