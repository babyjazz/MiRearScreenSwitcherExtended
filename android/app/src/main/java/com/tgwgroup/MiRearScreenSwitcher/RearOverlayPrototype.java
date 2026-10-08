/*
 * Phase 1 THROWAWAY (Option A gate). Delete after device checks.
 * Full-screen colored overlay on display 1 with label + tap counter.
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class RearOverlayPrototype {
    private static final String TAG = "RearOverlayPrototype";

    private static WindowManager windowManager;
    private static View rootView;
    private static TextView tapLabel;
    private static int tapCount = 0;

    public static synchronized boolean isShowing() {
        return windowManager != null && rootView != null;
    }

    public static synchronized void hide() {
        if (windowManager != null && rootView != null) {
            try {
                windowManager.removeView(rootView);
                Log.d(TAG, "overlay hidden");
            } catch (Throwable t) {
                Log.w(TAG, "removeView failed: " + t.getMessage());
            }
        }
        windowManager = null;
        rootView = null;
        tapLabel = null;
        tapCount = 0;
    }

    public static synchronized boolean show(Context context) {
        hide();
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                Log.e(TAG, "no DisplayManager");
                return false;
            }
            Display rear = dm.getDisplay(1);
            if (rear == null) {
                Log.e(TAG, "no display 1");
                return false;
            }

            Context rearCtx = context.createDisplayContext(rear)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            if (rearCtx == null) {
                Log.e(TAG, "createWindowContext returned null");
                return false;
            }

            WindowManager wm = (WindowManager) rearCtx.getSystemService(WindowManager.class);
            if (wm == null) {
                Log.e(TAG, "no WindowManager on rear context");
                return false;
            }

            LinearLayout root = new LinearLayout(rearCtx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(android.view.Gravity.CENTER);
            root.setBackgroundColor(Color.RED);

            TextView title = new TextView(rearCtx);
            title.setText("MRSS REAR OVERLAY");
            title.setTextSize(36);
            title.setTextColor(Color.WHITE);

            tapLabel = new TextView(rearCtx);
            tapLabel.setText("taps: 0");
            tapLabel.setTextSize(24);
            tapLabel.setTextColor(Color.WHITE);

            TextView hint = new TextView(rearCtx);
            hint.setText("tap screen to count");
            hint.setTextSize(16);
            hint.setTextColor(Color.WHITE);

            root.addView(title);
            root.addView(tapLabel);
            root.addView(hint);

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        tapCount++;
                        if (tapLabel != null) {
                            tapLabel.setText("taps: " + tapCount);
                        }
                    }
                    return true;
                }
            });

            // camera-cutout safe padding + rear density log (mirrors applySafeAreaPadding)
            try {
                RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
                if (info != null && info.hasCutout()) {
                    root.setPadding(info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom);
                    Log.d(TAG, String.format("cutout padding applied: L%d T%d R%d B%d",
                            info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
                }
                Log.d(TAG, "rear density dpi=" + (info != null ? info.densityDpi : 0));
            } catch (Throwable t) {
                Log.w(TAG, "cutout/dpi read failed: " + t.getMessage());
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );

            wm.addView(root, params);
            windowManager = wm;
            rootView = root;
            Log.d(TAG, "overlay added to display 1");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "overlay show failed", t);
            hide();
            return false;
        }
    }
}
