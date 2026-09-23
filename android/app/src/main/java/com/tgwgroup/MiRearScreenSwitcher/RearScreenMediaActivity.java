/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
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
 * 背屏媒体播放显示Activity（POC）
 * 显示专辑封面、曲目标题、歌手名、时钟和播放控制按钮
 */
public class RearScreenMediaActivity extends Activity {
    private static final String TAG = "RearScreenMediaActivity";

    private static volatile RearScreenMediaActivity currentInstance = null;

    private String packageName;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    // 误触被划走后的"自动找回"：3秒后如果媒体还在播放且没被别的东西正常接管背屏，就自己回到前台
    private static final long DISMISS_RECOVERY_DELAY_MS = 3000;
    private final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRecovery;

    private final BroadcastReceiver interruptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_MEDIA_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到打断广播，立即销毁");
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
            // 占位符：等待被移动到背屏（正常情况下不会走到这，直接--display 1启动）
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
        // "找回"用的intent只是把已有的task带回前台，没带曲目信息，不能覆盖当前已显示的内容
        if (intent.getBooleanExtra("bringToFront", false)) {
            return;
        }
        setIntent(intent);
        applyMediaState(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台了（无论是用户自己切回来还是找回逻辑生效），取消排队的找回任务
        if (pendingRecovery != null) {
            recoveryHandler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // isFinishing()为false说明是被划走/切到后台，不是被打断广播finish()掉的（那种情况isFinishing()已经是true）。
        // 这种情况下大概率是误触，3秒后自动找回，除非曲目已经真的停了。
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
            return; // 播放已经真的结束了，不用找回
        }
        try {
            ITaskService taskService = NotificationService.getTaskService();
            if (taskService == null) {
                taskService = ChargingService.getTaskService();
            }
            if (taskService == null) return;
            String componentName = getPackageName() + "/" + RearScreenMediaActivity.class.getName();
            // 不带--display 1：任务已经在背屏上了，只是需要重新置顶。
            // 带上--display 1会被HyperOS的ActivityStarterImpl当成"新开一个背屏任务"审核，直接拒绝（t-1，新task）；
            // 不带这个参数，系统按singleInstance语义直接把现有任务(已经在背屏的那个)带回前台。
            taskService.executeShellCommand("am start -n " + componentName + " --ez bringToFront true");
        } catch (Throwable t) {
            Log.w(TAG, "自动找回失败: " + t.getMessage());
        }
    }

    /**
     * 显示/刷新曲目信息、封面、播放按钮状态。onCreate首次展示和onNewIntent复用实例时共用。
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

        // 专辑封面：有封面文件用封面（前景+模糊背景），否则退回应用图标
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
            Log.w(TAG, "加载应用图标失败: " + e.getMessage());
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
                        Log.e(TAG, "恢复官方Launcher失败", e);
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
     * 在inflate布局之前强制使用背屏DPI（与其它背屏Activity保持一致）
     */
    /**
     * 应用安全区域适配（避开摄像头Cutout），与其它背屏Activity保持一致
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
            Log.e(TAG, "应用安全区域失败", e);
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
            Log.e(TAG, "应用背屏DPI失败", e);
        }
    }
}
