/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Rear-screen media playback Activity (POC).
 * Shows album art, track title, artist, clock, and playback controls.
 */
public class RearScreenMediaActivity extends Activity {
    private static final String TAG = "RearScreenMediaActivity";

    private static volatile RearScreenMediaActivity currentInstance = null;

    private String packageName;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    // Auto-recover after an accidental swipe-away: if media is still playing after 3s and nothing else took over the rear screen, return to the foreground
    private static final long DISMISS_RECOVERY_DELAY_MS = 3000;
    private final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRecovery;

    private final BroadcastReceiver interruptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_MEDIA_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔄 Interrupt broadcast received, destroying now");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int displayId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
        }
        if (displayId == 0) {
            // Placeholder: waits to be moved to the rear screen (normally launched directly with --display 1)
            return;
        }

        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        forceRearScreenDensityBeforeInflate();
        setContentView(R.layout.activity_rear_screen_media);
        applySafeAreaPadding();

        applyMediaState(getIntent());

        IntentFilter filter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_MEDIA_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(interruptReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(interruptReceiver, filter);
        }

        currentInstance = this;
        startClock();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The "recover" intent only brings the existing task back; it carries no track info, so it must not overwrite what is showing
        if (intent.getBooleanExtra("bringToFront", false)) {
            return;
        }
        setIntent(intent);
        applyMediaState(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back in the foreground (user returned or the recovery fired); cancel the queued recover task
        if (pendingRecovery != null) {
            recoveryHandler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // isFinishing()==false means swiped away/backgrounded, not finished by an interrupt broadcast (that path already has isFinishing()==true).
        // This is usually an accidental swipe; auto-recover after 3s, unless the track really stopped.
        if (isFinishing()) {
            return;
        }
        pendingRecovery = this::attemptDismissRecovery;
        recoveryHandler.postDelayed(pendingRecovery, DISMISS_RECOVERY_DELAY_MS);
    }

    private void attemptDismissRecovery() {
        pendingRecovery = null;
        MediaController controller = NotificationService.getActiveMediaController();
        if (controller == null) {
            return; // playback really ended; no recovery needed
        }
        try {
            ITaskService taskService = NotificationService.getTaskService();
            if (taskService == null) {
                taskService = ChargingService.getTaskService();
            }
            if (taskService == null) return;
            String componentName = getPackageName() + "/" + RearScreenMediaActivity.class.getName();
            // No --display 1: the task is already on the rear screen; it only needs to come back to the front.
            // With --display 1, HyperOS ActivityStarterImpl treats it as a new rear-screen task launch and rejects it (t-1, new task);
            // Without it, the system uses singleInstance semantics to bring the existing (already-on-rear) task back to the front.
            taskService.executeShellCommand("am start -n " + componentName + " --ez bringToFront true");
        } catch (Throwable t) {
            Log.w(TAG, "Auto-recovery failed: " + t.getMessage());
        }
    }

    /**
     * Show/refresh track info, cover, and play button state. Shared by onCreate first display and onNewIntent instance reuse.
     */
    private void applyMediaState(Intent intent) {
        packageName = intent.getStringExtra("packageName");
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        String albumArtPath = intent.getStringExtra("albumArtPath");
        boolean isPlaying = intent.getBooleanExtra("isPlaying", true);

        TextView titleView = findViewById(R.id.media_title);
        TextView artistView = findViewById(R.id.media_artist);
        ImageView albumArtView = findViewById(R.id.media_album_art);
        ImageView albumArtBgView = findViewById(R.id.media_album_art_bg);
        ImageButton playPauseBtn = findViewById(R.id.media_btn_play_pause);
        ImageButton prevBtn = findViewById(R.id.media_btn_prev);
        ImageButton nextBtn = findViewById(R.id.media_btn_next);

        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
        playPauseBtn.setImageResource(isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

        // Album art: use the cover file (foreground + blurred background) when present, else fall back to the app icon
        if (albumArtPath != null && !albumArtPath.isEmpty()) {
            android.graphics.Bitmap bmp = BitmapFactory.decodeFile(albumArtPath);
            if (bmp != null) {
                albumArtView.setImageBitmap(bmp);
                albumArtBgView.setImageBitmap(bmp);
            } else {
                setAppIconAsAlbumArt(albumArtView);
            }
        } else {
            setAppIconAsAlbumArt(albumArtView);
        }

        playPauseBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller == null) return;
            boolean playing = controller.getPlaybackState() != null
                && controller.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING;
            if (playing) {
                controller.getTransportControls().pause();
                playPauseBtn.setImageResource(R.drawable.ic_media_play);
            } else {
                controller.getTransportControls().play();
                playPauseBtn.setImageResource(R.drawable.ic_media_pause);
            }
        });
        prevBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        nextBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller != null) controller.getTransportControls().skipToNext();
        });
    }

    private void setAppIconAsAlbumArt(ImageView albumArtView) {
        try {
            if (packageName == null) return;
            PackageManager pm = getPackageManager();
            Drawable icon = pm.getApplicationIcon(packageName);
            albumArtView.setImageDrawable(icon);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load app icon: " + e.getMessage());
        }
    }

    private void startClock() {
        TextView clockView = findViewById(R.id.media_clock);
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                if (clockView != null) {
                    clockView.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date()));
                }
                clockHandler.postDelayed(this, 30000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (pendingRecovery != null) {
            recoveryHandler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }

        if (clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }

        try {
            unregisterReceiver(interruptReceiver);
        } catch (Exception ignored) {}

        if (this != currentInstance) {
            return;
        }
        currentInstance = null;

        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.MEDIA);
        if (!shouldRestore) {
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            if (currentDisplayId == 1) {
                new Thread(() -> {
                    try {
                        ITaskService taskService = NotificationService.getTaskService();
                        if (taskService == null) {
                            taskService = ChargingService.getTaskService();
                        }
                        if (taskService != null) {
                            taskService.executeShellCommand(
                                "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore the official Launcher", e);
                    }
                }).start();
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Force the rear-screen DPI before inflating the layout (consistent with other rear-screen Activities).
     */
    /**
     * Apply safe-area padding (clears camera cutout), consistent with other rear-screen Activities.
     */
    private void applySafeAreaPadding() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) return;

            android.view.View contentLayout = findViewById(R.id.media_container);
            if (contentLayout != null && contentLayout.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) contentLayout.getLayoutParams();
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                contentLayout.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply safe area", e);
        }
    }

    private void forceRearScreenDensityBeforeInflate() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;

            if (rearScreenDpi <= 0) {
                ITaskService taskService = null;
                for (int retry = 0; retry < 3; retry++) {
                    taskService = NotificationService.getTaskService();
                    if (taskService == null) {
                        taskService = ChargingService.getTaskService();
                    }
                    if (taskService != null) break;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (taskService != null) {
                    DisplayInfoCache.getInstance().initialize(taskService);
                    info = DisplayInfoCache.getInstance().getCachedInfo();
                    rearScreenDpi = info.densityDpi;
                } else {
                    return;
                }
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
