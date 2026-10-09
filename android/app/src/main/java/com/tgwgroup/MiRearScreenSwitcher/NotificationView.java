package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App icon, name and notification text with the intro animation. Payload: packageName, title, text, darkMode. */
public class NotificationView implements RearView {
    private static final String TAG = "NotificationView";

    private final Context context;
    private final View root;
    private final View container;
    private final ImageView iconCenter;
    private final View appNameContainer;
    private final View contentContainer;
    private Runnable dismissRunnable;
    private Runnable phase2Runnable;

    public NotificationView(Context context, ViewGroup parent) {
        this.context = context;
        root = LayoutInflater.from(context).inflate(R.layout.activity_rear_screen_notification, parent, false);
        container = root.findViewById(R.id.notification_container);
        iconCenter = root.findViewById(R.id.app_icon_center);
        appNameContainer = root.findViewById(R.id.app_name_container);
        contentContainer = root.findViewById(R.id.notification_content_container);
        applySafeAreaPadding();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void bind(Bundle p) {
        String packageName = p.getString("packageName");
        String title = p.getString("title");
        String text = p.getString("text");
        boolean darkMode = p.getBoolean("darkMode", false);

        ImageView appIconSmall = root.findViewById(R.id.app_icon_small);
        TextView appNameText = root.findViewById(R.id.app_name);
        TextView notificationTitle = root.findViewById(R.id.notification_title);
        TextView notificationContent = root.findViewById(R.id.notification_content);

        applyLayout(darkMode);

        try {
            PackageManager pm = context.getPackageManager();
            String appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
            Drawable icon = pm.getApplicationIcon(packageName);
            iconCenter.setImageDrawable(icon);
            appIconSmall.setImageDrawable(icon);
            appNameText.setText(appName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app info", e);
            appNameText.setText(packageName);
        }

        boolean hasTitle = title != null && !title.isEmpty();
        notificationTitle.setText(title);
        notificationTitle.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        boolean hasText = text != null && !text.isEmpty();
        notificationContent.setText(text);
        notificationContent.setVisibility(hasText ? View.VISIBLE : View.GONE);
        // No title: drop the gap above the content
        notificationContent.setPadding(notificationContent.getPaddingLeft(), 0,
            notificationContent.getPaddingRight(), notificationContent.getPaddingBottom());

        startAnimation();

        container.setOnClickListener(v -> openApp(packageName));

        // Newest notification restarts the dismiss timer
        cancelTimers();
        int duration = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
            .getInt("notification_duration", 10);
        dismissRunnable = () -> RearHost.hide(context, RearStack.Type.NOTIFICATION);
        container.postDelayed(dismissRunnable, duration * 1000L);
    }

    @Override
    public void unbind() {
        cancelTimers();
        iconCenter.animate().cancel();
        appNameContainer.animate().cancel();
        contentContainer.animate().cancel();
    }

    private void cancelTimers() {
        if (dismissRunnable != null) {
            container.removeCallbacks(dismissRunnable);
            dismissRunnable = null;
        }
        if (phase2Runnable != null) {
            iconCenter.removeCallbacks(phase2Runnable);
            phase2Runnable = null;
        }
    }

    /**
     * 1. large icon scales up  2. icon shrinks out, app-name container fades in
     * 3. content container slides in. Resets the views first so it can replay on a new notification.
     */
    private void startAnimation() {
        iconCenter.animate().cancel();
        appNameContainer.animate().cancel();
        contentContainer.animate().cancel();

        iconCenter.setVisibility(View.VISIBLE);
        iconCenter.setAlpha(1f);
        iconCenter.setScaleX(1f);
        iconCenter.setScaleY(1f);
        appNameContainer.setAlpha(0f);
        appNameContainer.setScaleX(0.9f);
        appNameContainer.setScaleY(0.9f);
        contentContainer.setAlpha(0f);
        contentContainer.setTranslationY(30f);

        iconCenter.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        appNameContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        contentContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        iconCenter.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(300)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                phase2Runnable = () -> {
                    iconCenter.animate()
                        .scaleX(0.5f).scaleY(0.5f).alpha(0f)
                        .setDuration(200)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            iconCenter.setVisibility(View.GONE);
                            iconCenter.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                    appNameContainer.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(250).setStartDelay(50)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> appNameContainer.setLayerType(View.LAYER_TYPE_NONE, null))
                        .start();
                    contentContainer.animate()
                        .alpha(1f).translationY(0f)
                        .setDuration(300).setStartDelay(150)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> contentContainer.setLayerType(View.LAYER_TYPE_NONE, null))
                        .start();
                };
                iconCenter.postDelayed(phase2Runnable, 500); // dwell
            })
            .start();
    }

    /** Dark mode only adds a black background; otherwise the layout is identical. */
    private void applyLayout(boolean darkMode) {
        View darkBackground = root.findViewById(R.id.dark_mode_background);
        if (darkBackground != null) darkBackground.setVisibility(darkMode ? View.VISIBLE : View.GONE);

        // Drop the frost and let text reach the container edges
        contentContainer.setBackgroundColor(Color.TRANSPARENT);
        contentContainer.setPadding(0, 0, 0, 0);

        LinearLayout.LayoutParams nameParams = (LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
        nameParams.leftMargin = 0;
        appNameContainer.setLayoutParams(nameParams);

        TextView appName = root.findViewById(R.id.app_name);
        TextView title = root.findViewById(R.id.notification_title);
        TextView content = root.findViewById(R.id.notification_content);
        int shadowColor = Color.parseColor("#40000000");
        for (TextView t : new TextView[]{appName, title, content}) {
            t.setTextColor(Color.WHITE);
            t.setShadowLayer(3, 0, 1, shadowColor);
        }
        title.setMaxLines(1);
        content.setMaxLines(6);
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) title.getLayoutParams();
        titleParams.topMargin = 8;
        title.setLayoutParams(titleParams);
        LinearLayout.LayoutParams contentParams = (LinearLayout.LayoutParams) content.getLayoutParams();
        contentParams.topMargin = 8;
        content.setLayoutParams(contentParams);
    }

    /** Tap: open the app on the main display and dismiss. */
    private void openApp(String packageName) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                Log.w(TAG, "No launch intent for " + packageName);
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            boolean started = false;
            try {
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                m.invoke(opts, 0);
                context.startActivity(launchIntent, opts.toBundle());
                started = true;
            } catch (Throwable t) {
                Log.w(TAG, "ActivityOptions launch unavailable, falling back: " + t.getMessage());
            }
            if (!started && launchIntent.getComponent() != null) {
                ITaskService ts = RearHost.taskService();
                if (ts != null) {
                    String component = launchIntent.getComponent().flattenToShortString();
                    if (!ts.executeShellCommand("am start --display 0 -n " + component)) {
                        ts.executeShellCommand("am start -n " + component);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Open app failed", t);
        } finally {
            RearHost.hide(context, RearStack.Type.NOTIFICATION);
        }
    }

    /** Clear the camera cutout. */
    private void applySafeAreaPadding() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) return;
            if (container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                container.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply safe area", e);
        }
    }
}
