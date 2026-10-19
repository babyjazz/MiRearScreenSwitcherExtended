package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

/**
 * The single rear-screen UI: a horizontal pager with three fixed pages.
 * Each page reuses the exact current full-screen MRSS view (MediaView /
 * NotificationView / ChargingView) and shows a simple empty state overlay when
 * that feature is inactive.
 */
public class RearPagerView {
    private static final String TAG = "RearPagerView";
    private static final long AUTO_RETURN_DELAY_MS = 4000;

    private final Context context;
    private final View root;
    private final ViewPager viewPager;
    private final LinearLayout dots;
    private final View[] dotViews = new View[RearPagerState.PAGES];

    private final RearPagerState state = new RearPagerState();
    private final Handler autoReturnHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoReturn;
    private int lastDesiredPage = -1;
    private int currentPage = RearPagerState.MUSIC;

    private final List<View> pages = new ArrayList<>();

    private final MediaView mediaView;
    private final NotificationView notificationView;
    private final ChargingView chargingView;
    private final View emptyMusic;
    private final View emptyNoti;
    private final View emptyCharge;

    private Bundle musicPayload;
    private Bundle notiPayload;
    private Bundle chargePayload;

    public RearPagerView(Context context, ViewGroup parent) {
        this.context = context;
        root = android.view.LayoutInflater.from(context).inflate(R.layout.rear_pager_view, parent, false);
        viewPager = root.findViewById(R.id.pager_vp);
        dots = root.findViewById(R.id.pager_dots);

        // Build the three pages, each hosting the exact current full-screen view.
        FrameLayout musicPage = new FrameLayout(context);
        musicPage.setBackgroundColor(Color.BLACK);
        mediaView = new MediaView(context, musicPage);
        musicPage.addView(mediaView.getView());
        emptyMusic = emptyOverlay("No music playing now", "Play in any media app");
        musicPage.addView(emptyMusic);

        FrameLayout notiPage = new FrameLayout(context);
        notiPage.setBackgroundColor(Color.BLACK);
        notificationView = new NotificationView(context, notiPage);
        notiPage.addView(notificationView.getView());
        emptyNoti = emptyOverlay("No notifications", "You are all caught up");
        notiPage.addView(emptyNoti);

        FrameLayout chargePage = new FrameLayout(context);
        chargePage.setBackgroundColor(Color.BLACK);
        chargingView = new ChargingView(context, chargePage);
        chargePage.addView(chargingView.getView());
        emptyCharge = emptyOverlay("Not charging", "Plug in charger");
        chargePage.addView(emptyCharge);

        pages.add(musicPage);
        pages.add(notiPage);
        pages.add(chargePage);

        viewPager.setAdapter(new RearPagerAdapter());
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                currentPage = position;
                state.markUserPage(position);
                updateDots(position);
            }
        });

        buildDots();
        applyDotsVisibility();
    }

    private View emptyOverlay(String title, String sub) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundColor(0x99000000);
        ll.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = new TextView(context);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(t);

        TextView s = new TextView(context);
        s.setText(sub);
        s.setTextColor(0xCCFFFFFF);
        s.setTextSize(12);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(6), 0, 0);
        ll.addView(s);

        // Empty state is centered on the raw screen, but filled views already pad for the
        // left camera cutout. Shift the empty overlay right by the same cutout so both align.
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info != null && info.hasCutout()) {
                ll.setPadding(info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom);
            }
        } catch (Exception e) {
            Log.e(TAG, "empty overlay safe area failed", e);
        }

        return ll;
    }

    private class RearPagerAdapter extends PagerAdapter {
        @Override
        public int getCount() { return pages.size(); }

        @Override
        public boolean isViewFromObject(View view, Object object) { return view == object; }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            View v = pages.get(position);
            container.addView(v);
            return v;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }
    }

    private void buildDots() {
        for (int i = 0; i < RearPagerState.PAGES; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            if (i > 0) lp.leftMargin = dp(12);
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
                dotViews[i].getLayoutParams().width = dp(9);
                dotViews[i].getLayoutParams().height = dp(9);
                dotViews[i].setBackgroundResource(R.drawable.bg_pager_dot_active);
            } else {
                dotViews[i].getLayoutParams().width = dp(7);
                dotViews[i].getLayoutParams().height = dp(7);
                dotViews[i].setBackgroundResource(R.drawable.bg_pager_dot);
            }
            dotViews[i].requestLayout();
        }
    }

    public View getView() { return root; }

    public void bindMusic(Bundle payload) {
        musicPayload = payload;
        boolean active = payload != null;
        if (active) {
            mediaView.bind(payload);
            ((ViewGroup) pages.get(0)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyMusic.setVisibility(View.GONE);
        } else {
            mediaView.unbind();
            ((ViewGroup) pages.get(0)).getChildAt(0).setVisibility(View.GONE);
            emptyMusic.setVisibility(View.VISIBLE);
        }
    }

    public void bindNotification(Bundle payload) {
        notiPayload = payload;
        boolean active = payload != null && payload.containsKey("title");
        if (active) {
            notificationView.bind(payload);
            ((ViewGroup) pages.get(1)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyNoti.setVisibility(View.GONE);
        } else {
            notificationView.unbind();
            ((ViewGroup) pages.get(1)).getChildAt(0).setVisibility(View.GONE);
            emptyNoti.setVisibility(View.VISIBLE);
        }
    }

    public void bindCharging(Bundle payload) {
        chargePayload = payload;
        boolean active = payload != null && payload.containsKey("batteryLevel");
        Breadcrumb.log(context, "RearPagerView", "bindCharging active=" + active
            + " level=" + (payload == null ? -1 : payload.getInt("batteryLevel", -1)));
        if (active) {
            chargingView.bind(payload);
            ((ViewGroup) pages.get(2)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyCharge.setVisibility(View.GONE);
        } else {
            chargingView.unbind();
            ((ViewGroup) pages.get(2)).getChildAt(0).setVisibility(View.GONE);
            emptyCharge.setVisibility(View.VISIBLE);
        }
    }

    public void onFeatureChanged(boolean notiActive, boolean chargingActive, boolean mediaActive) {
        boolean notiOn = notiActive && notiPayload != null && notiPayload.containsKey("title");
        boolean chargeOn = chargingActive && chargePayload != null && chargePayload.containsKey("batteryLevel");
        boolean mediaOn = mediaActive && musicPayload != null;
        Breadcrumb.log(context, "RearPagerView", "featureChanged noti=" + notiOn + " charge=" + chargeOn + " media=" + mediaOn);
        if (notiOn) {
            notificationView.bind(notiPayload);
            ((ViewGroup) pages.get(1)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyNoti.setVisibility(View.GONE);
        } else {
            notificationView.unbind();
            ((ViewGroup) pages.get(1)).getChildAt(0).setVisibility(View.GONE);
            emptyNoti.setVisibility(View.VISIBLE);
        }
        if (chargeOn) {
            chargingView.bind(chargePayload);
            ((ViewGroup) pages.get(2)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyCharge.setVisibility(View.GONE);
        } else {
            chargingView.unbind();
            ((ViewGroup) pages.get(2)).getChildAt(0).setVisibility(View.GONE);
            emptyCharge.setVisibility(View.VISIBLE);
        }
        if (mediaOn) {
            mediaView.bind(musicPayload);
            ((ViewGroup) pages.get(0)).getChildAt(0).setVisibility(View.VISIBLE);
            emptyMusic.setVisibility(View.GONE);
        } else {
            mediaView.unbind();
            ((ViewGroup) pages.get(0)).getChildAt(0).setVisibility(View.GONE);
            emptyMusic.setVisibility(View.VISIBLE);
        }
        int desired = state.decidePage(notiOn, chargeOn, mediaOn);
        if (desired != lastDesiredPage) {
            // A feature just became active (or priority changed): jump to it now.
            lastDesiredPage = desired;
            cancelAutoReturn();
            setPage(desired, false); // automatic switch: hard cut, no half-scrolled blank frame
        } else if (desired == currentPage) {
            cancelAutoReturn();
        } else {
            // Same feature is still active but the user is looking elsewhere:
            // don't yank them back on every 2s wake tick; give them time.
            if (pendingAutoReturn == null) scheduleAutoReturn(desired);
        }
    }

    private void cancelAutoReturn() {
        if (pendingAutoReturn != null) {
            autoReturnHandler.removeCallbacks(pendingAutoReturn);
            pendingAutoReturn = null;
        }
    }

    private void scheduleAutoReturn(int desired) {
        cancelAutoReturn();
        pendingAutoReturn = () -> {
            pendingAutoReturn = null;
            setPage(desired, false);
        };
        autoReturnHandler.postDelayed(pendingAutoReturn, AUTO_RETURN_DELAY_MS);
    }

    public void setPage(int index, boolean animate) {
        if (index < 0) index = 0;
        if (index >= RearPagerState.PAGES) index = RearPagerState.PAGES - 1;
        currentPage = index;
        state.markUserPage(index);
        updateDots(index);
        viewPager.setCurrentItem(index, animate);
    }

    public int getCurrentPage() { return currentPage; }

    public void setDotsVisible(boolean visible) {
        dots.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void applyDotsVisibility() {
        boolean visible = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getBoolean("flutter.pager_dots_visible", false);
        setDotsVisible(visible);
    }

    public void unbind() {
        cancelAutoReturn();
        mediaView.unbind();
        notificationView.unbind();
        chargingView.unbind();
    }

    /** Persist the page to restore across sleeps. */
    public int saveForSleep(Context ctx) { return state.saveForSleep(ctx); }

    /** After wake: the persisted page is only the fallback; the next onFeatureChanged jumps to the active feature. */
    public void restoreAfterWake(Context ctx) {
        state.markUserPage(state.restoreAfterWake(ctx));
        lastDesiredPage = -1;
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
