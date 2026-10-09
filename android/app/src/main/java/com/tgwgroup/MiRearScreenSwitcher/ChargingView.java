package com.tgwgroup.MiRearScreenSwitcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen liquid-fill battery animation. Payload: batteryLevel. */
public class ChargingView implements RearView {
    private static final String TAG = "ChargingView";
    private static final long ONE_SHOT_MS = 8000;

    private final Context context;
    private final View root;
    private final LightningShapeView liquid;
    private final TextView batteryText;
    private ValueAnimator fillAnimator;
    private Runnable finishRunnable;

    public ChargingView(Context context, ViewGroup parent) {
        this.context = context;
        root = LayoutInflater.from(context).inflate(R.layout.activity_rear_screen_charging, parent, false);
        liquid = root.findViewById(R.id.full_screen_liquid);
        batteryText = root.findViewById(R.id.battery_text);
        liquid.setFullScreenMode(true);
        applySafeAreaToText();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void bind(Bundle p) {
        int level = p.getInt("batteryLevel", 0);
        batteryText.setText(level + "%");
        startFill(level);

        batteryText.setAlpha(0f);
        batteryText.setScaleX(0.8f);
        batteryText.setScaleY(0.8f);
        batteryText.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(800).setStartDelay(600) // after the fill starts
            .setInterpolator(new DecelerateInterpolator(2.0f))
            .start();

        // Cancel the previous round's timer so it cannot end the new animation early
        cancelTimer();
        boolean alwaysOn = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
            .getBoolean("charging_always_on_enabled", false);
        if (!alwaysOn) {
            finishRunnable = () -> RearHost.hide(context, RearStack.Type.CHARGING);
            root.postDelayed(finishRunnable, ONE_SHOT_MS);
        }
    }

    @Override
    public void unbind() {
        cancelTimer();
        if (fillAnimator != null) fillAnimator.cancel();
        batteryText.animate().cancel();
    }

    /** Live battery update while visible (always-on mode). */
    public void updateBatteryLevel(int level) {
        liquid.setFillLevel(level / 100f);
        batteryText.setText(level + "%");
    }

    private void cancelTimer() {
        if (finishRunnable != null) {
            root.removeCallbacks(finishRunnable);
            finishRunnable = null;
        }
    }

    private void startFill(int level) {
        if (fillAnimator != null) fillAnimator.cancel();
        fillAnimator = ValueAnimator.ofFloat(0f, level / 100f);
        fillAnimator.setDuration(2000);
        fillAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
        fillAnimator.addUpdateListener(a -> liquid.setFillLevel((float) a.getAnimatedValue()));
        fillAnimator.start();
    }

    /** Keep the number centered in the safe area. */
    private void applySafeAreaToText() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) return;
            if (batteryText.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) batteryText.getLayoutParams();
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                batteryText.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply safe area", e);
        }
    }
}
