package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The only Activity on the rear screen. Renders whichever experience RearStack says is on top
 * (NotificationView / ChargingView / MediaView) and tears the rear down when the stack empties.
 */
public class RearHostActivity extends Activity {
    private static final String TAG = "RearHostActivity";

    private static volatile RearHostActivity instance = null;
    private static volatile boolean onRear = false;
    private static volatile boolean visible = false;
    private static volatile long shownSince = 0;
    private static volatile long lastVisibleMs = Long.MAX_VALUE;

    /** Any live instance, including a main-display placeholder waiting to be moved. */
    public static RearHostActivity getInstance() { return instance; }
    /** The instance, only once it is initialized on the rear display. */
    public static RearHostActivity getRearInstance() { return onRear ? instance : null; }
    public static boolean isVisible() { return visible; }
    public static long getShownSince() { return shownSince; }
    public static long getLastVisibleMs() { return lastVisibleMs; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout rootFrame;
    private MediaView mediaView;
    private NotificationView notificationView;
    private ChargingView chargingView;
    private RearView current;
    private boolean finishedByUs = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        // Launched on the main display it is only a placeholder: wait to be moved, onResume initializes on the rear
        initIfOnRear();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        initIfOnRear();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        RearHost.cancelMediaRecovery();
        initIfOnRear(); // moved from the main placeholder to the rear
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        shownSince = System.currentTimeMillis();
        // Locked and idle, HyperOS sleeps the rear ~1s after wake and removes our task. Waking again once the content is
        // actually visible keeps it lit (a wake right after move-stack is too early).
        if (onRear && RearStack.top() != RearStack.Type.MEDIA) {
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
        // NOTIFICATION: its own timer ends it. CHARGING: ChargingService's watchdog decides whether to bring it back.
        if (RearStack.top() == RearStack.Type.MEDIA) {
            RearHost.scheduleMediaRecovery(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current != null) current.unbind();
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "destroy onRear=" + onRear + " finishedByUs=" + finishedByUs + " top=" + RearStack.top());
        if (this != instance) return;
        boolean wasOnRear = onRear;
        instance = null;
        onRear = false;
        visible = false;

        if (!wasOnRear) {
            if (RearStack.isEmpty()) RearHost.restoreRear(getApplicationContext());
            return;
        }
        if (finishedByUs && !RearStack.isEmpty()) {
            // Something was shown between hide() and the Activity actually dying; bring the host back
            final android.content.Context app = getApplicationContext();
            final RearStack.Type top = RearStack.top();
            new Thread(() -> RearHost.show(app, top, RearStack.get(top))).start();
            return;
        }
        if (!finishedByUs) {
            // Removed by HyperOS or the user: a notification is not worth relaunching
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

    /** End the host because the stack is empty. */
    public void finishHost() {
        finishedByUs = true;
        runOnUiThread(this::finish);
    }

    public void updateBattery(int level) {
        runOnUiThread(() -> {
            if (chargingView != null && current == chargingView) chargingView.updateBatteryLevel(level);
        });
    }

    /** Show the highest-priority entry. Safe from any thread. */
    public void render() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::render);
            return;
        }
        if (!onRear || isDestroyed()) return;
        RearStack.Type top = RearStack.top();
        if (top == null) return;
        android.os.Bundle payload = RearStack.get(top);
        if (payload == null) return;

        RearView next;
        switch (top) {
            case NOTIFICATION:
                if (notificationView == null) notificationView = new NotificationView(this, rootFrame);
                next = notificationView;
                break;
            case CHARGING:
                if (chargingView == null) chargingView = new ChargingView(this, rootFrame);
                next = chargingView;
                break;
            default:
                if (mediaView == null) mediaView = new MediaView(this, rootFrame);
                next = mediaView;
        }
        if (next != current) {
            if (current != null) current.unbind();
            rootFrame.removeAllViews();
            rootFrame.addView(next.getView());
            current = next;
            if (top == RearStack.Type.NOTIFICATION) {
                getWindow().setBackgroundDrawableResource(R.drawable.bg_gradient_rear_screen);
            } else {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
        }
        next.bind(payload);
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
        rootFrame = new FrameLayout(this);
        setContentView(rootFrame);
        onRear = true;
        render();
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
