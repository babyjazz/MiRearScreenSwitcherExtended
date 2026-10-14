package com.tgwgroup.MiRearScreenSwitcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The single rear-screen UI: a horizontal pager with three fixed pages
 * (Music, Notification, Charging). Feature switching moves the pager, never
 * launches a separate Activity.
 */
public class RearPagerView {
    private static final String TAG = "RearPagerView";

    private final Context context;
    private final View root;
    private final HorizontalScrollView scrollView;
    private final FrameLayout musicContainer;
    private final FrameLayout notiContainer;
    private final FrameLayout chargeContainer;
    private final LinearLayout dots;
    private final View[] dotViews = new View[RearPagerState.PAGES];

    private final RearPagerState state = new RearPagerState();
    private int currentPage = RearPagerState.MUSIC;
    private boolean userScrolling = false;

    // last payloads so re-render after page switches is cheap
    private Bundle musicPayload;
    private Bundle notiPayload;
    private Bundle chargePayload;
    private boolean musicActive = false;
    private boolean notiActive = false;
    private boolean chargingActive = false;

    private ValueAnimator chargeAnimator;

    public RearPagerView(Context context, ViewGroup parent) {
        this.context = context;
        root = LayoutInflater.from(context).inflate(R.layout.rear_pager_view, parent, false);
        scrollView = root.findViewById(R.id.pager_scroll);
        musicContainer = root.findViewById(R.id.page_music_container);
        notiContainer = root.findViewById(R.id.page_noti_container);
        chargeContainer = root.findViewById(R.id.page_charge_container);
        dots = root.findViewById(R.id.pager_dots);

        addPage(R.layout.page_music, musicContainer);
        addPage(R.layout.page_notification, notiContainer);
        addPage(R.layout.page_charging, chargeContainer);

        buildDots();
        applySafeAreaPadding();

        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            int width = v.getWidth();
            if (width <= 0) return;
            int idx = Math.round(scrollX / (float) width);
            if (idx < 0) idx = 0;
            if (idx >= RearPagerState.PAGES) idx = RearPagerState.PAGES - 1;
            updateDots(idx);
            currentPage = idx;
            state.markUserPage(idx);
        });
    }

    private void addPage(int layoutRes, ViewGroup container) {
        LayoutInflater.from(context).inflate(layoutRes, container, true);
    }

    private void buildDots() {
        for (int i = 0; i < RearPagerState.PAGES; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            if (i > 0) lp.leftMargin = dp(7);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_pager_dot);
            dotViews[i] = dot;
            dots.addView(dot);
        }
        updateDots(currentPage);
    }

    private void updateDots(int active) {
        for (int i = 0; i < dotViews.length; i++) {
            if (dotViews[i] == null) continue;
            if (i == active) {
                dotViews[i].getLayoutParams().width = dp(16);
                dotViews[i].setBackgroundResource(R.drawable.bg_pager_dot_active);
            } else {
                dotViews[i].getLayoutParams().width = dp(7);
                dotViews[i].setBackgroundResource(R.drawable.bg_pager_dot);
            }
            dotViews[i].requestLayout();
        }
    }

    public View getView() {
        return root;
    }

    public void bindMusic(Bundle payload) {
        musicPayload = payload;
        musicActive = payload != null && payload.getBoolean("isPlaying", false);
        renderMusic();
    }

    public void bindNotification(Bundle payload) {
        notiPayload = payload;
        notiActive = payload != null && payload.containsKey("title");
        renderNotification();
    }

    public void bindCharging(Bundle payload) {
        chargePayload = payload;
        chargingActive = payload != null && payload.containsKey("batteryLevel");
        renderCharging();
    }

    public void onFeatureChanged(boolean notiActive, boolean chargingActive, boolean mediaActive) {
        this.notiActive = notiActive && notiPayload != null;
        this.chargingActive = chargingActive && chargePayload != null;
        this.musicActive = mediaActive && musicPayload != null && musicPayload.getBoolean("isPlaying", false);
        renderMusic();
        renderNotification();
        renderCharging();
        setPage(state.decidePage(this.notiActive, this.chargingActive, this.musicActive), true);
    }

    public void setPage(int index, boolean animate) {
        if (index < 0) index = 0;
        if (index >= RearPagerState.PAGES) index = RearPagerState.PAGES - 1;
        final int targetIndex = index;
        currentPage = targetIndex;
        state.markUserPage(targetIndex);
        updateDots(targetIndex);
        scrollView.post(() -> {
            int width = scrollView.getWidth();
            if (width <= 0) return;
            int target = targetIndex * width;
            if (animate) {
                scrollView.smoothScrollTo(target, 0);
            } else {
                scrollView.scrollTo(target, 0);
            }
        });
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void unbind() {
        if (chargeAnimator != null) chargeAnimator.cancel();
        chargeAnimator = null;
    }

    // ---- page rendering ----

    private void renderMusic() {
        View musicRoot = musicContainer.getChildAt(0);
        TextView title = musicRoot.findViewById(R.id.page_music_title);
        TextView sub = musicRoot.findViewById(R.id.page_music_sub);
        View row = musicRoot.findViewById(R.id.page_music_row);

        if (musicActive) {
            title.setVisibility(View.GONE);
            sub.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);

            String song = musicPayload.getString("title");
            String artist = musicPayload.getString("artist");
            ((TextView) musicRoot.findViewById(R.id.page_music_song)).setText(song == null ? "" : song);
            ((TextView) musicRoot.findViewById(R.id.page_music_artist)).setText(artist == null ? "" : artist);

            String albumArtPath = musicPayload.getString("albumArtPath");
            ImageView art = musicRoot.findViewById(R.id.page_music_art);
            android.graphics.Bitmap bmp = albumArtPath == null || albumArtPath.isEmpty()
                    ? null : BitmapFactory.decodeFile(albumArtPath);
            if (bmp != null) {
                art.setImageBitmap(bmp);
            } else {
                setAppIcon(art, musicPayload.getString("packageName"));
            }

            boolean playing = musicPayload.getBoolean("isPlaying", true);
            ImageButton play = musicRoot.findViewById(R.id.page_music_play);
            play.setImageResource(playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

            play.setOnClickListener(v -> {
                MediaController controller = NotificationService.getActiveMediaController();
                if (controller == null) return;
                boolean isPlaying = controller.getPlaybackState() != null
                        && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
                if (isPlaying) {
                    controller.getTransportControls().pause();
                    play.setImageResource(R.drawable.ic_media_play);
                } else {
                    controller.getTransportControls().play();
                    play.setImageResource(R.drawable.ic_media_pause);
                }
            });
            musicRoot.findViewById(R.id.page_music_prev).setOnClickListener(v -> {
                MediaController controller = NotificationService.getActiveMediaController();
                if (controller != null) controller.getTransportControls().skipToPrevious();
            });
            musicRoot.findViewById(R.id.page_music_next).setOnClickListener(v -> {
                MediaController controller = NotificationService.getActiveMediaController();
                if (controller != null) controller.getTransportControls().skipToNext();
            });
        } else {
            title.setVisibility(View.VISIBLE);
            sub.setVisibility(View.VISIBLE);
            row.setVisibility(View.GONE);
            title.setText("No music playing now");
            sub.setText("Play in any media app");
        }
    }

    private void renderNotification() {
        View notiRoot = notiContainer.getChildAt(0);
        TextView title = notiRoot.findViewById(R.id.page_noti_title);
        TextView sub = notiRoot.findViewById(R.id.page_noti_sub);
        View card = notiRoot.findViewById(R.id.page_noti_card);

        if (notiActive) {
            title.setVisibility(View.GONE);
            sub.setVisibility(View.GONE);
            card.setVisibility(View.VISIBLE);

            String packageName = notiPayload.getString("packageName");
            String appName = packageName;
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
            } catch (Exception e) {
                Log.w(TAG, "app label failed: " + e.getMessage());
            }
            ((TextView) notiRoot.findViewById(R.id.page_noti_app)).setText(appName);
            ((TextView) notiRoot.findViewById(R.id.page_noti_text)).setText(notiPayload.getString("title"));
            ((TextView) notiRoot.findViewById(R.id.page_noti_body)).setText(notiPayload.getString("text"));

            ImageView icon = notiRoot.findViewById(R.id.page_noti_icon);
            try {
                icon.setImageDrawable(context.getPackageManager().getApplicationIcon(packageName));
            } catch (Exception e) {
                Log.w(TAG, "app icon failed: " + e.getMessage());
            }

            card.setOnClickListener(v -> openApp(packageName));
        } else {
            title.setVisibility(View.VISIBLE);
            sub.setVisibility(View.VISIBLE);
            card.setVisibility(View.GONE);
            title.setText("No notifications");
            sub.setText("You are all caught up");
        }
    }

    private void renderCharging() {
        View chargeRoot = chargeContainer.getChildAt(0);
        TextView title = chargeRoot.findViewById(R.id.page_charge_title);
        TextView sub = chargeRoot.findViewById(R.id.page_charge_sub);
        View liquid = chargeRoot.findViewById(R.id.page_charge_liquid);

        if (chargingActive) {
            title.setVisibility(View.GONE);
            sub.setVisibility(View.GONE);
            liquid.setVisibility(View.VISIBLE);

            int level = chargePayload.getInt("batteryLevel", 0);
            ((TextView) chargeRoot.findViewById(R.id.page_charge_pct)).setText(level + "%");
            LightningShapeView v = chargeRoot.findViewById(R.id.page_charge_liquid_view);
            v.setFullScreenMode(false);
            if (chargeAnimator != null) chargeAnimator.cancel();
            chargeAnimator = ValueAnimator.ofFloat(0f, level / 100f);
            chargeAnimator.setDuration(2000);
            chargeAnimator.addUpdateListener(a -> v.setFillLevel((float) a.getAnimatedValue()));
            chargeAnimator.start();
        } else {
            title.setVisibility(View.VISIBLE);
            sub.setVisibility(View.VISIBLE);
            liquid.setVisibility(View.GONE);
            title.setText("Not charging");
            sub.setText("Plug in charger");
        }
    }

    private void openApp(String packageName) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) return;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
            m.invoke(opts, 0);
            context.startActivity(launchIntent, opts.toBundle());
        } catch (Throwable t) {
            Log.w(TAG, "openApp failed: " + t.getMessage());
        }
    }

    private void setAppIcon(ImageView view, String packageName) {
        try {
            if (packageName == null) return;
            Drawable icon = context.getPackageManager().getApplicationIcon(packageName);
            view.setImageDrawable(icon);
        } catch (Exception e) {
            Log.w(TAG, "app icon failed: " + e.getMessage());
        }
    }

    private void applySafeAreaPadding() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) return;
            int l = info.cutout.left, t = info.cutout.top, r = info.cutout.right, b = info.cutout.bottom;
            for (int i = 0; i < 3; i++) {
                View page = ((ViewGroup) ((LinearLayout) scrollView.getChildAt(0))).getChildAt(i);
                if (page != null) page.setPadding(l, t, r, b);
            }
        } catch (Exception e) {
            Log.e(TAG, "safe area failed", e);
        }
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
