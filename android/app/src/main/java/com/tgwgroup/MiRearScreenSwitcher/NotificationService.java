/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.PowerManager;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 通知监听服务
 * 监听系统通知，将选中应用的通知显示到背屏
 */
public class NotificationService extends NotificationListenerService {
    private static final String TAG = "NotificationService";
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    
    private Set<String> selectedApps = new HashSet<>();
    private boolean privacyHideTitle = false; // V3.2: 隐私模式 - 隐藏标题
    private boolean privacyHideContent = false; // V3.2: 隐私模式 - 隐藏内容
    private boolean followDndMode = true; // 跟随系统勿扰模式（默认开启）
    private boolean onlyWhenLocked = false; // 仅在锁屏时通知（默认关闭）
    private boolean notificationDarkMode = false; // 通知暗夜模式（默认关闭）
    private boolean serviceEnabled = false; // 服务是否启用
    private ITaskService taskService; // 自己的TaskService实例
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private String lastShownSignature; // 最近一次显示的通知（key|标题|内容），用于过滤重复发布
    
    // 静态实例，供外部访问
    private static NotificationService instance;

    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }

    // 媒体播放（POC）：当前追踪的MediaController，供背屏播放控制按钮直接调用
    private MediaSessionManager mediaSessionManager;
    private MediaController activeMediaController;
    private String activeMediaPackage;

    public static MediaController getActiveMediaController() {
        return instance != null ? instance.activeMediaController : null;
    }

    /**
     * 通知打断媒体播放显示后，通知结束时调用：如果媒体还在（没被真正停止），重新显示出来
     */
    public static void resumeMediaIfInterrupted() {
        if (instance != null && instance.activeMediaController != null) {
            instance.scheduleShowMediaOnRearScreen(
                instance.activeMediaController.getMetadata(),
                instance.activeMediaController.getPlaybackState()
            );
        }
    }

    // MediaController.Callback会为同一次曲目/状态变化连续触发好几次（onMetadataChanged+onPlaybackStateChanged
    // 常常一起来，有时还会重复），每次showMediaOnRearScreen都是同步阻塞的唤醒+启动流程（含Thread.sleep），
    // 密集触发会互相打架导致背屏显示不稳定，所以这里做去抖：短时间内只真正执行最后一次。
    private static final long MEDIA_UPDATE_DEBOUNCE_MS = 250;
    private final android.os.Handler mediaUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingMediaUpdate;

    private void scheduleShowMediaOnRearScreen(MediaMetadata metadata, PlaybackState state) {
        if (pendingMediaUpdate != null) {
            mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
        }
        pendingMediaUpdate = () -> showMediaOnRearScreen(metadata, state);
        mediaUpdateHandler.postDelayed(pendingMediaUpdate, MEDIA_UPDATE_DEBOUNCE_MS);
    }

    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleShowMediaOnRearScreen(metadata, activeMediaController != null ? activeMediaController.getPlaybackState() : null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            if (state.getState() == PlaybackState.STATE_STOPPED || state.getState() == PlaybackState.STATE_NONE) {
                if (pendingMediaUpdate != null) {
                    mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
                }
                RearAnimationManager.sendInterruptBroadcast(NotificationService.this, RearAnimationManager.AnimationType.MEDIA);
                return;
            }
            scheduleShowMediaOnRearScreen(activeMediaController != null ? activeMediaController.getMetadata() : null, state);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
        controllers -> pickActiveMediaController(controllers);

    /**
     * 从当前活跃的MediaSession里选一个正在播放/最近使用的，注册回调追踪
     */
    private void pickActiveMediaController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (activeMediaController != null) {
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            }
            detachMediaController();
            return;
        }

        // 优先选第一个正在播放的session（系统按最近活跃排序返回）
        MediaController chosen = null;
        for (MediaController c : controllers) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                chosen = c;
                break;
            }
        }
        if (chosen == null) {
            // 没有正在播放的，退而求其次选第一个"有真实状态"的session（比如暂停中的）。
            // 不能无脑取controllers.get(0)：有些app（如淘宝的TbAliveMedia）会一直挂着一个
            // state=null的占位session，选中它会让我们卡死在一个永远不会更新的死session上，
            // 之后新出现的真实播放session反而因为"已经有session了"被去重逻辑挡在外面。
            for (MediaController c : controllers) {
                if (c.getPlaybackState() != null) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null) {
            // 所有session都没有真实状态，没什么可显示的
            if (activeMediaController != null) {
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            }
            detachMediaController();
            return;
        }
        if (activeMediaController != null && activeMediaController.getSessionToken().equals(chosen.getSessionToken())) {
            return; // 还是同一个session，回调已经注册过
        }

        detachMediaController();
        activeMediaController = chosen;
        activeMediaPackage = chosen.getPackageName();
        activeMediaController.registerCallback(mediaControllerCallback);
        scheduleShowMediaOnRearScreen(activeMediaController.getMetadata(), activeMediaController.getPlaybackState());
    }

    private void detachMediaController() {
        if (activeMediaController != null) {
            try {
                activeMediaController.unregisterCallback(mediaControllerCallback);
            } catch (Throwable ignored) {}
        }
        activeMediaController = null;
        activeMediaPackage = null;
    }

    // 广播接收器：监听设置重新加载
    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS".equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到重新加载设置的广播");
                loadNotificationServiceSettings(); // 重新加载开关状态
                loadSettings(); // 重新加载其他设置
            }
        }
    };
    
    // 广播接收器：锁屏时唤醒背屏（可选开关，默认关闭）
    // ACTION_SCREEN_OFF/ON自Android 3.1起就不会送达manifest静态声明的接收器，
    // 只能像这样在运行中的组件里动态注册才能收到，所以放在这个常驻的NotificationService里。
    private BroadcastReceiver wakeOnLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                return;
            }
            // 已有背屏任务在跑时不插手，避免和RearScreenKeeperService的保活逻辑打架
            if (RearScreenBroadcastReceiver.hasActiveTask()) {
                return;
            }
            if (!prefs.getBoolean("wake_on_lock_enabled", false)) {
                return;
            }
            try {
                if (taskService == null) return;
                if (activeMediaController != null) {
                    // 媒体正在播放时，光唤醒屏幕不保证亮起来后看到的还是媒体界面
                    // （背屏亮灭有自己的时序，容易和官方Launcher抢位置），
                    // 直接重新显示媒体播放界面，保证锁屏后背屏上一定是它。
                    scheduleShowMediaOnRearScreen(activeMediaController.getMetadata(), activeMediaController.getPlaybackState());
                } else {
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    Log.d(TAG, "✓ 锁屏时已唤醒背屏");
                }
            } catch (Throwable t) {
                Log.w(TAG, "锁屏唤醒背屏失败: " + t.getMessage());
            }
        }
    };

    // Shizuku服务配置
    private final Shizuku.UserServiceArgs serviceArgs =
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("notification_task_service")
            .debuggable(false)
            .version(1);
    
    // TaskService连接
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✓ TaskService connected");
            taskService = ITaskService.Stub.asInterface(binder);
            
            // 初始化显示屏信息缓存
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                Log.w(TAG, "初始化显示屏缓存失败: " + e.getMessage());
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "✗ TaskService disconnected");
            taskService = null;
            // 自动重连
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (taskService == null) {
                    bindTaskService();
                }
            }, 1000);
        }
    };
    
    // Shizuku监听器
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            Log.d(TAG, "Shizuku binder received");
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            Log.d(TAG, "Shizuku binder dead");
            taskService = null;
            // 尝试重连
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                bindTaskService();
            }, 1000);
        };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 NotificationService created");
        
        // 保存实例
        instance = this;
        
        // 初始化SharedPreferences
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // 注册广播接收器（监听设置变化）
        IntentFilter filter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, filter);
        }
        Log.d(TAG, "✓ 广播接收器已注册");

        // 注册锁屏时唤醒背屏的接收器（ACTION_SCREEN_OFF只能动态注册接收，manifest静态声明收不到）
        IntentFilter screenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeOnLockReceiver, screenOffFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wakeOnLockReceiver, screenOffFilter);
        }

        // 添加Shizuku监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        
        // 绑定TaskService
        bindTaskService();
        
        // V2.4: 加载通知服务开关状态
        Log.d(TAG, "🔧 开始加载通知服务开关状态...");
        loadNotificationServiceSettings();
        Log.d(TAG, "🔧 通知服务开关状态加载完成: " + serviceEnabled);
        
        // 启动为前台服务，防止被系统杀死
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        Log.d(TAG, "✓ 前台服务已启动");

        loadSettings();

        // 媒体播放（POC）：注册MediaSession监听，追踪当前播放的曲目
        try {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listenerComponent = new ComponentName(this, NotificationService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, listenerComponent);
            // 监听只对之后的变化生效，启动时手动取一次当前已有的session
            pickActiveMediaController(mediaSessionManager.getActiveSessions(listenerComponent));
        } catch (Throwable t) {
            Log.w(TAG, "注册MediaSession监听失败: " + t.getMessage());
        }
    }
    
    private void bindTaskService() {
        try {
            if (taskService != null) {
                Log.d(TAG, "TaskService already bound");
                return;
            }
            
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            Log.d(TAG, "🔗 开始绑定TaskService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    /**
     * 加载通知服务开关状态
     */
    private void loadNotificationServiceSettings() {
        try {
            Log.d(TAG, "🔧 开始读取FlutterSharedPreferences...");
            // 从FlutterSharedPreferences读取开关状态
            SharedPreferences flutterPrefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            Log.d(TAG, "🔧 FlutterSharedPreferences读取成功");
            
            serviceEnabled = flutterPrefs.getBoolean("flutter.notification_service_enabled", false);
            Log.d(TAG, "🔧 通知服务开关状态已恢复: " + serviceEnabled);
            
            // NotificationListenerService由系统管理，不能手动停止
            // 如果开关关闭，服务仍会运行但不处理通知
            if (!serviceEnabled) {
                Log.d(TAG, "⏸️ 通知服务已禁用，将忽略所有通知");
            } else {
                Log.d(TAG, "✅ 通知服务已启用，将处理通知");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载通知服务设置失败", e);
            serviceEnabled = false; // 默认关闭
        }
    }
    
    private void loadSettings() {
        try {
            selectedApps = prefs.getStringSet("notification_selected_apps", new HashSet<>());
            privacyHideTitle = prefs.getBoolean("notification_privacy_hide_title", false);
            privacyHideContent = prefs.getBoolean("notification_privacy_hide_content", false);
            followDndMode = prefs.getBoolean("notification_follow_dnd_mode", true);
            onlyWhenLocked = prefs.getBoolean("notification_only_when_locked", false);
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            // 注意：不在这里重新设置 serviceEnabled，保持 loadNotificationServiceSettings() 的值
            
            Log.d(TAG, "⚙️ 已加载设置");
            Log.d(TAG, "   - 启用状态: " + serviceEnabled + " (由loadNotificationServiceSettings设置)");
            Log.d(TAG, "   - 选中应用: " + selectedApps.size() + " 个");
            Log.d(TAG, "   - 隐藏标题: " + privacyHideTitle);
            Log.d(TAG, "   - 隐藏内容: " + privacyHideContent);
            
            if (!selectedApps.isEmpty()) {
                Log.d(TAG, "📋 选中应用列表: " + selectedApps.toString());
            } else {
                Log.w(TAG, "⚠️ 没有选中任何应用");
            }
        } catch (Exception e) {
            Log.e(TAG, "加载设置失败", e);
            selectedApps = new HashSet<>();
            // 不在这里重置 serviceEnabled
        }
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        
        // V2.4: 每次收到通知时重新加载开关状态
        loadNotificationServiceSettings();
        
        // V2.4: 如果通知服务开关关闭，不处理通知
        if (!serviceEnabled) {
            Log.d(TAG, "⏸️ 通知服务已禁用，忽略通知");
            return;
        }
        
        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            
            Log.d(TAG, "📢 收到通知: " + packageName);

            // 忽略媒体播放通知：这类通知交给专门的媒体播放显示流程（MediaSessionManager）处理，
            // 不能走普通通知弹窗，否则每次曲目/播放状态更新都会弹出聊天式通知，还会打断媒体播放界面。
            // 有些应用（如YouTube Music的部分版本）的播放通知不带FLAG_ONGOING_EVENT，
            // 所以不能只看这个flag，要直接看是否挂了MediaSession。
            if (notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null) {
                Log.d(TAG, "⏭️ 忽略媒体播放通知（走专门的媒体播放流程）: " + packageName);
                return;
            }

            // 忽略常驻通知
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                Log.d(TAG, "⏭️ 忽略常驻通知: " + packageName);
                return;
            }
            
            // 忽略自己的通知
            if (packageName.equals(getPackageName())) {
                Log.d(TAG, "⏭️ 忽略自己的通知");
                return;
            }
            
            // 每次都重新加载设置（确保实时生效）
            loadSettings();
            
            // 检查服务是否启用
            if (!serviceEnabled) {
                Log.d(TAG, "⏭️ 通知服务未启用，跳过");
                return;
            }
            
            // 检查系统勿扰模式
            if (followDndMode) {
                try {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                        Log.d(TAG, "⏭️ 系统勿扰模式已开启，跳过通知动画");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查勿扰模式失败: " + e.getMessage());
                }
            }
            
            // 检查是否仅在锁屏时通知
            if (onlyWhenLocked) {
                try {
                    android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        Log.d(TAG, "⏭️ 当前未锁屏，仅锁屏通知模式已开启，跳过");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查锁屏状态失败: " + e.getMessage());
                }
            }
            
            Log.d(TAG, "📋 当前选中应用数量: " + selectedApps.size());
            Log.d(TAG, "📋 选中应用列表: " + selectedApps.toString());
            
            // 检查是否在选中列表中
            if (!selectedApps.contains(packageName)) {
                Log.d(TAG, "⏭️ 应用不在选中列表中: " + packageName);
                return;
            }
            
            Log.d(TAG, "✓ 应用在选中列表中: " + packageName);
            
            // 提取通知内容
            String title = notification.extras.getString(Notification.EXTRA_TITLE, "");
            String text = notification.extras.getString(Notification.EXTRA_TEXT, "");
            long when = notification.when;
            
            Log.d(TAG, "📝 通知标题: " + title);
            Log.d(TAG, "📝 通知内容: " + text);

            // 同一条通知的重复发布（如Telegram 1.5秒内更新4次）：正在显示同样内容时忽略，
            // 否则每次都会打断当前通知Activity再重载，表现为背屏闪一下却不显示。
            // 用隐私处理前的原始内容比较，隐私模式下同一会话的新消息也能正常替换。
            String signature = sbn.getKey() + "|" + title + "|" + text;
            if (signature.equals(lastShownSignature)
                    && RearAnimationManager.getCurrentAnimation() == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "⏭️ 重复发布的相同通知且仍在显示，忽略: " + packageName);
                return;
            }
            lastShownSignature = signature;
            
            // V3.2: 隐私模式处理（区分标题和内容）
            if (privacyHideTitle) {
                Log.d(TAG, "🔒 隐藏通知标题");
                title = getString(R.string.privacy_mode_enabled);
            }
            if (privacyHideContent) {
                Log.d(TAG, "🔒 隐藏通知内容");
                text = getString(R.string.new_message_placeholder);
            }
            
            Log.d(TAG, "🚀 开始显示背屏通知: " + packageName);
            
            // 通知动画管理器：开始通知动画（返回被打断的旧动画）
            RearAnimationManager.AnimationType oldAnim = RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
            
            // 如果有旧动画需要打断，发送打断广播
            if (oldAnim == RearAnimationManager.AnimationType.CHARGING) {
                Log.d(TAG, "🔄 检测到充电动画正在播放，发送打断广播");
                
                // V3.5: 检查充电动画是否是常亮模式
                boolean chargingAlwaysOn = prefs.getBoolean("charging_always_on_enabled", false);
                RearAnimationManager.markInterruptedChargingAsAlwaysOn(chargingAlwaysOn);
                
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.CHARGING);
            } else if (oldAnim == RearAnimationManager.AnimationType.MEDIA) {
                Log.d(TAG, "🔄 检测到媒体播放显示正在播放，发送打断广播");
                // 通知结束后要恢复媒体播放显示，而不是回到官方Launcher
                RearAnimationManager.markMediaInterruptedByNotification();
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.MEDIA);
            } else if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "🔄 检测到通知动画正在播放，发送打断广播并重载");
                RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.NOTIFICATION);
                
                // 延迟600ms后重新启动通知动画，确保旧动画完全停止（锁屏+投送app下需要更多时间）
                final String finalPackageName = packageName;
                final String finalTitle = title;
                final String finalText = text;
                final long finalWhen = when;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "🔄 重载通知动画");
                    // 背屏上一条通知动画刚被打断，屏幕显然已经点亮，跳过唤醒避免打断本次动画
                    showNotificationOnRearScreen(finalPackageName, finalTitle, finalText, finalWhen, true);
                }, 600);
                return; // 提前返回，避免重复启动
            }

            // 触发背屏通知显示
            showNotificationOnRearScreen(packageName, title, text, when, false);

        } catch (Exception e) {
            Log.e(TAG, "❌ 处理通知时出错", e);
        }
    }

    private void showNotificationOnRearScreen(String packageName, String title, String text, long when, boolean skipWake) {
        // 参考ChargingService的重试机制
        if (taskService == null) {
            Log.w(TAG, "⚠️ TaskService未连接，尝试重新绑定...");
            bindTaskService();

            // 延迟500ms后重试
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                showNotificationOnRearScreenDirect(packageName, title, text, when, skipWake);
            }, 500);
        } else {
            showNotificationOnRearScreenDirect(packageName, title, text, when, skipWake);
        }
    }
    
    private void showNotificationOnRearScreenDirect(String packageName, String title, String text, long when, boolean skipWake) {
        try {
            if (taskService == null) {
                Log.e(TAG, "❌ TaskService仍然不可用，放弃显示通知");
                return;
            }
            
            // 短时局部保活，避免在锁屏/重负载下被挂起
            acquireWakeLock(6000);
            Log.d(TAG, "🎯 准备启动Activity显示通知");
            
            // 锁屏状态检查
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();
            
            // 读取主屏前台应用（用于同包名前台场景的保护）
            String mainForegroundApp = null;
            try {
                mainForegroundApp = taskService.getForegroundAppOnDisplay(0);
                Log.d(TAG, "📱 主屏前台应用: " + mainForegroundApp);
            } catch (Throwable t) {
                Log.w(TAG, "获取主屏前台应用失败: " + t.getMessage());
            }
            
            // V3.3: 移除唤醒代码，避免锁屏时跳转到密码界面
            
            try {
                // 暂停监控，防止被误杀
                RearScreenKeeperService.pauseMonitoring();
            } catch (Throwable t) {
                Log.w(TAG, "pauseMonitoring failed: " + t.getMessage());
            }
            
            try {
                // 禁用背屏官方Launcher，避免抢占
                taskService.disableSubScreenLauncher();
            } catch (Throwable t) {
                Log.w(TAG, "disableSubScreenLauncher failed: " + t.getMessage());
            }
            
            // V3.3: 移除 wm dismiss-keyguard 命令，避免锁屏时跳转到密码界面
            
            // 先唤醒背屏，再启动Activity，这样动画落在已点亮的屏幕上。
            // 背屏处于DOZE/DOZE_SUSPEND时窗口flag（FLAG_TURN_SCREEN_ON）无效，
            // 只有这条命令能把它点亮（等同双击唤醒），与RearScreenKeeperService/AlwaysWakeUpService一致。
            // 必须放在所有启动策略之前：直接--display 1启动和占位+移动两条路径都要点亮。
            // skipWake：上一条通知动画刚被打断重载时跳过，此时屏幕显然已经点亮，
            // 再次唤醒只会多出一次无意义的等待，看起来像"唤醒动画打断了通知"。
            if (!skipWake) {
                try {
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // 等待背屏完成物理唤醒（背光渐亮），避免动画在屏幕还没亮起来时就开始绘制
                    Thread.sleep(300);
                } catch (Throwable t) {
                    Log.w(TAG, "唤醒背屏失败: " + t.getMessage());
                }
            }

            // 2) 根据锁屏状态与前台应用选择启动策略
            String componentName = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            
            // 当锁屏且主屏前台就是本条通知所属应用时，避免主屏占位策略，改为直接背屏启动，防止系统冲突
            // 精确匹配包名，避免误判（如 com.tencent.mm 和 com.tencent.mobileqq）
            boolean forceDirectRearDueToSameApp = false;
            if (isLocked && mainForegroundApp != null && !mainForegroundApp.isEmpty()) {
                // 提取主屏前台应用的包名（格式可能是 "com.example.app/com.example.app.MainActivity"）
                String foregroundPackage = mainForegroundApp;
                if (mainForegroundApp.contains("/")) {
                    foregroundPackage = mainForegroundApp.split("/")[0];
                }
                forceDirectRearDueToSameApp = foregroundPackage.equals(packageName);
                Log.d(TAG, String.format("🔍 锁屏同包检查: 主屏前台=[%s] vs 通知包名=[%s] -> %s",
                    foregroundPackage, packageName, forceDirectRearDueToSameApp ? "匹配(直接背屏)" : "不匹配(占位策略)"));
            }
            
            // ✅ 统一策略：无论锁屏与否，都直接在背屏启动（避免DPI不匹配问题）
            // 直接在背屏启动可以确保布局使用正确的DPI（450），避免从主屏移动导致的尺寸问题
            
            // 确保暗夜模式设置是最新的
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            Log.d(TAG, "🌙 当前暗夜模式设置: " + notificationDarkMode);
            
            String directCmd = String.format(
                "am start --display 1 -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                componentName,
                packageName,
                title.replace("\"", "\\\""),
                text.replace("\"", "\\\""),
                when,
                notificationDarkMode
            );
            
            boolean started = false;
            // 锁屏时HyperOS必定拒绝 --display 1（ActivityStarterImpl），直接走下面的主屏占位+移动，省掉约1秒无效尝试。
            // 非锁屏只启动一次再轮询：Activity要约0.5秒才出现在am stack list里，
            // 期间重复 --display 1 会被当成新任务启动，可能产生phantom任务。
            if (!isLocked) {
                try {
                    taskService.executeShellCommand(directCmd);
                    Log.d(TAG, "✓ 非锁屏状态，直接在背屏启动通知Activity");
                    for (int i = 0; i < 6 && !started; i++) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        String check = taskService.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                        started = check != null && !check.trim().isEmpty();
                    }
                    Log.d(TAG, started ? "✓ 通知动画已在背屏启动" : "⚠️ 直接背屏启动未出现");
                } catch (Throwable t) {
                    Log.w(TAG, "直接背屏启动失败: " + t.getMessage());
                }
            }
            
            // 如果直接启动失败，使用备用策略（主屏占位+移动）
            if (!started) {
                Log.w(TAG, isLocked ? "🔒 锁屏状态，使用主屏占位+移动策略" : "⚠️ 直接背屏启动失败，回退到主屏占位+移动策略");
                
                // 主屏启动（Activity 自行占位）
                String startOnMainCmd = String.format(
                    "am start -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                    componentName,
                    packageName,
                    title.replace("\"", "\\\""),
                    text.replace("\"", "\\\""),
                    when,
                    notificationDarkMode
                );
                Log.d(TAG, "🔵 在主屏启动通知Activity（占位符）");
                taskService.executeShellCommand(startOnMainCmd);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                
                // 轮询获取taskId
                String notifTaskId = null;
                int attempts = 0;
                int maxAttempts = 60;
                while (notifTaskId == null && attempts < maxAttempts) {
                    try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            notifTaskId = matcher.group(1);
                            Log.d(TAG, "🎯 找到通知taskId=" + notifTaskId);
                            break;
                        }
                    }
                    attempts++;
                }
                
                if (notifTaskId != null) {
                    // 4) 移动到背屏
                    String moveCmd = "am display move-stack " + notifTaskId + " 1";
                    taskService.executeShellCommand(moveCmd);
                    try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    
                    // 5) 锁屏时关闭主屏，避免主屏抢焦点
                    // 主屏休眠功能已移除
                    Log.d(TAG, "🔒 锁屏状态，主屏已关闭");
                    
                    Log.d(TAG, "✓ 通知动画已移动到背屏");
                } else {
                    Log.e(TAG, "❌ 未能找到通知Activity的taskId，最后尝试直接在背屏启动");
                    try {
                        String fallbackCmd = String.format(
                            "am start --display 1 -n %s --es packageName \"%s\" --es title \"%s\" --es text \"%s\" --el when %d --ez darkMode %b",
                            componentName,
                            packageName,
                            title.replace("\"", "\\\""),
                            text.replace("\"", "\\\""),
                            when,
                            notificationDarkMode
                        );
                        taskService.executeShellCommand(fallbackCmd);
                        Log.d(TAG, "🟦 已尝试直接 --display 1 启动通知Activity（fallback）");
                    } catch (Throwable t) {
                        Log.w(TAG, "Fallback直接在背屏启动失败: " + t.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 显示背屏通知失败", e);
        } finally {
            releaseWakeLock();
        }
    }

    /**
     * 媒体播放显示（POC）：把当前播放的曲目信息+专辑封面显示到背屏，带播放控制按钮。
     * 复用通知服务的开关和"选中应用"名单做门槛，不新增单独开关。
     */
    private void showMediaOnRearScreen(MediaMetadata metadata, PlaybackState state) {
        try {
            if (taskService == null || activeMediaPackage == null) return;
            if (!serviceEnabled) return;
            if (!prefs.getStringSet("notification_selected_apps", new HashSet<>()).contains(activeMediaPackage)) return;
            if (metadata == null) return;

            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

            String albumArtPath = null;
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art != null) {
                try {
                    File f = new File(getCacheDir(), "media_album_art.png");
                    FileOutputStream fos = new FileOutputStream(f);
                    art.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    albumArtPath = f.getAbsolutePath();
                } catch (Throwable t) {
                    Log.w(TAG, "写入专辑封面失败: " + t.getMessage());
                }
            }

            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(300);
            } catch (Throwable t) {
                Log.w(TAG, "唤醒背屏失败: " + t.getMessage());
            }

            RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.MEDIA);

            String componentName = getPackageName() + "/" + RearScreenMediaActivity.class.getName();
            String extras = String.format(
                "--es packageName \"%s\" --es title \"%s\" --es artist \"%s\" --es albumArtPath \"%s\" --ez isPlaying %b",
                activeMediaPackage,
                title == null ? "" : title.replace("\"", "\\\""),
                artist == null ? "" : artist.replace("\"", "\\\""),
                albumArtPath == null ? "" : albumArtPath,
                isPlaying
            );

            // 锁屏时HyperOS会拒绝直接--display 1的新Task启动（ActivityStarterImpl的rearDisplay检查），
            // 与通知弹窗一样，需要先在主屏占位再move-stack过去
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();

            boolean started = false;
            if (!isLocked) {
                taskService.executeShellCommand("am start --display 1 -n " + componentName + " " + extras);
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String check = taskService.executeShellCommandWithResult("am stack list | grep RearScreenMediaActivity");
                started = check != null && !check.trim().isEmpty();
            }

            if (!started) {
                taskService.executeShellCommand("am start -n " + componentName + " " + extras);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                String mediaTaskId = null;
                for (int attempts = 0; attempts < 60 && mediaTaskId == null; attempts++) {
                    try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenMediaActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("taskId=(\\d+)").matcher(result);
                        if (matcher.find()) mediaTaskId = matcher.group(1);
                    }
                }
                if (mediaTaskId != null) {
                    taskService.executeShellCommand("am display move-stack " + mediaTaskId + " 1");
                } else {
                    Log.w(TAG, "⚠️ 未能找到媒体播放Activity的taskId");
                }
            }

            Log.d(TAG, "🎵 已在背屏显示媒体播放: " + title);
        } catch (Exception e) {
            Log.e(TAG, "❌ 显示背屏媒体播放失败", e);
        }
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:NotificationWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                    Log.d(TAG, "🔒 PARTIAL_WAKE_LOCK acquired for " + timeoutMs + "ms");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire wakelock: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "🔓 PARTIAL_WAKE_LOCK released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to release wakelock: " + t.getMessage());
        }
    }
    
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "🔗 NotificationListener connected");
        loadSettings();
        Log.d(TAG, "✓ 通知监听器已就绪");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔴 NotificationService destroyed");

        // 媒体播放（POC）：注销MediaSession监听
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            }
            if (pendingMediaUpdate != null) {
                mediaUpdateHandler.removeCallbacks(pendingMediaUpdate);
            }
            detachMediaController();
        } catch (Throwable t) {
            Log.w(TAG, "注销MediaSession监听失败: " + t.getMessage());
        }

        // 注销广播接收器
        try {
            unregisterReceiver(settingsReceiver);
            Log.d(TAG, "✓ 广播接收器已注销");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister receiver", e);
        }
        try {
            unregisterReceiver(wakeOnLockReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister wakeOnLockReceiver", e);
        }

        // 移除Shizuku监听器
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove Shizuku listeners", e);
        }
        
        // 解绑TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind TaskService", e);
        }
        
        // 清除实例
        instance = null;
        
        stopForeground(true);
    }
}

