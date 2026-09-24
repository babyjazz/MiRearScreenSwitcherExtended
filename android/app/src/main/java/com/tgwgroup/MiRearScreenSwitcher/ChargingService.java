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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * 充电状态监听服务
 * 监听电源连接事件，插电后在背屏显示充电电量动画
 */
public class ChargingService extends Service {
    private static final String TAG = "ChargingService";
    private SharedPreferences prefs;
    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;
    
    // 静态实例，供RearScreenChargingActivity访问
    private static ChargingService instance;
    
    // 防止重复触发动画（冷却时间）
    private long lastChargingAnimationTime = 0;
    private static final long CHARGING_ANIMATION_COOLDOWN_MS = 6000; // 6秒冷却时间
    
    // V3.5: 充电动画常亮模式
    private boolean chargingAlwaysOnEnabled = false;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;
    
    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }
    
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("charging_task_service")
            .debuggable(false)
            .version(1);
    
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
    
    // V3.5: 设置变化广播接收器
    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "收到设置变化广播");
            chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
            Log.d(TAG, "充电动画常亮: " + chargingAlwaysOnEnabled);
        }
    };
    
    // V3.5: 恢复充电动画广播接收器
    private BroadcastReceiver resumeChargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔋 收到恢复充电动画广播，准备恢复");
                
                // 获取当前电量
                int batteryLevel = getBatteryLevel(context);
                
                // 延迟后重新启动充电动画
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // 通知动画管理器：开始充电动画
                        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                        
                        // 启动充电动画
                        showChargingOnRearScreen(batteryLevel, false);
                        
                        // 如果常亮模式开启，启动唤醒循环
                        if (chargingAlwaysOnEnabled) {
                            Log.d(TAG, "💡 常亮模式开启，启动wakeup循环");
                            startWakeupAndUpdateLoop();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "恢复充电动画失败", e);
                    }
                }, 300);  // 300ms延迟，确保通知Activity完全销毁
            }
        }
    };
    
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                // 拔电后FLAP_WINDOW_MS内又接上 = PD掉线重协商（接触不良的口约每40秒一次），不是用户插电，不放动画
                if (System.currentTimeMillis() - lastPowerDisconnectedTime < FLAP_WINDOW_MS) {
                    Log.d(TAG, "🔌 Power reconnected within " + FLAP_WINDOW_MS + "ms of disconnect, treating as flap");
                    return;
                }
                // 接触不良的USB口会在几秒内反复插拔（PD重新协商），每次都会触发这个广播。
                // 延迟CHARGE_DEBOUNCE_MS后再放动画：期间收到拔电广播就取消，
                // 这样抖动的充电器无法反复抢占背屏。
                debounceHandler.removeCallbacks(pendingChargingRunnable);
                debounceHandler.postDelayed(pendingChargingRunnable, CHARGE_DEBOUNCE_MS);
                Log.d(TAG, "🔌 Power connected, debouncing " + CHARGE_DEBOUNCE_MS + "ms");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                lastPowerDisconnectedTime = System.currentTimeMillis();
                // 抖动期间拔电：取消待放的动画
                debounceHandler.removeCallbacks(pendingChargingRunnable);

                // 拔掉充电器，立即销毁充电动画
                Log.d(TAG, "🔌 Power disconnected, finishing charging animation");

                // V3.5: 停止唤醒循环
                stopWakeupLoop();

                finishChargingAnimation();
            }
        }
    };

    // 抖动过滤：充电连接稳定CHARGE_DEBOUNCE_MS后才放动画
    private static final long CHARGE_DEBOUNCE_MS = 3000;
    // 拔电后多久内重新接上视为抖动（观测到的PD掉线重协商约2秒）
    private static final long FLAP_WINDOW_MS = 5000;
    private long lastPowerDisconnectedTime = 0;
    private final Handler debounceHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable pendingChargingRunnable = new Runnable() {
        @Override
        public void run() {
            Context context = ChargingService.this;
            // 去抖结束后复查：必须仍在充电，否则这次只是一次抖动
            if (!isPluggedIn(context)) {
                Log.d(TAG, "⏸ 去抖结束时已不在充电，忽略本次插入");
                return;
            }
            // 检查开关状态
            boolean enabled = prefs.getBoolean("charging_animation_enabled", true);
            if (!enabled) {
                Log.d(TAG, "Charging animation disabled");
                return;
            }

            // 检查冷却时间（防止重复触发）
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastChargingAnimationTime < CHARGING_ANIMATION_COOLDOWN_MS) {
                Log.d(TAG, "⏸ Charging animation in cooldown, skipping");
                return;
            }
            
            // 检查屏幕锁定状态
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();
            
            if (isLocked) {
                Log.d(TAG, "🔓 Screen is locked, will show charging animation with screen sleep");
            } else {
                Log.d(TAG, "🔓 Screen is unlocked, will show charging animation without screen sleep");
            }
            
            int batteryLevel = getBatteryLevel(context);
            Log.d(TAG, "🔌 Power connected, battery: " + batteryLevel + "%");

            // 通知动画管理器：开始充电动画（返回被打断的旧动画）
            RearAnimationManager.AnimationType oldAnim = RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
            
            // 如果有旧动画需要打断，发送打断广播
            if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                Log.d(TAG, "检测到通知动画正在播放，发送打断广播");
                RearAnimationManager.sendInterruptBroadcast(ChargingService.this, RearAnimationManager.AnimationType.NOTIFICATION);
            }
            
            showChargingOnRearScreen(batteryLevel, isLocked);
            
            // V3.5: 如果开启了充电动画常亮，启动唤醒和更新循环
            if (chargingAlwaysOnEnabled) {
                Log.d(TAG, "充电动画常亮已开启，启动wakeup循环");
                startWakeupAndUpdateLoop();
            }
        }
    };

    /** 当前是否接着电源（去抖结束后复查用） */
    private boolean isPluggedIn(Context context) {
        try {
            Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return i != null && i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (Throwable t) {
            Log.w(TAG, "检查充电状态失败: " + t.getMessage());
            return true; // 读不到就按原行为放动画
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ChargingService created");
        
        // 保存实例
        instance = this;
        
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // 添加Shizuku监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);  // 监听拔电事件
        registerReceiver(batteryReceiver, filter);
        
        // V3.5: 注册设置变化广播接收器
        IntentFilter settingsFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_CHARGING_SETTINGS");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
        
        // V3.5: 注册恢复充电动画广播接收器
        IntentFilter resumeFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resumeChargingReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resumeChargingReceiver, resumeFilter);
        }
        
        // V3.5: 加载充电动画常亮设置
        chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
        wakeupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 绑定TaskService
        bindTaskService();
        
        // 启动前台服务保活（使用统一的内核服务通知）
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        Log.d(TAG, "✓ 前台服务已启动（使用内核服务通知）");
    }
    
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:ChargingWake");
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ChargingService started");
        
        // 确保TaskService已绑定
        if (taskService == null) {
            bindTaskService();
        }
        
        return START_STICKY;
    }
    
    private void bindTaskService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No Shizuku permission");
                return;
            }
            
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            Log.d(TAG, "Binding TaskService...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    private int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
    
    /**
     * 立即结束充电动画
     */
    private void finishChargingAnimation() {
        try {
            // 通过广播通知RearScreenChargingActivity立即结束
            Intent finishIntent = new Intent("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
            finishIntent.setPackage(getPackageName());
            sendBroadcast(finishIntent);
                Log.d(TAG, "已发送结束充电动画的广播");
        } catch (Exception e) {
            Log.e(TAG, "Failed to finish charging animation", e);
        }
    }
    
    private void showChargingOnRearScreen(int level, boolean isLocked) {
        showChargingOnRearScreenWithRetry(level, isLocked, 0);
    }
    
    private void showChargingOnRearScreenWithRetry(int level, boolean isLocked, int retryCount) {
        if (taskService == null) {
            if (retryCount < 10) {  // 最多重试10次（总共1秒）
                Log.w(TAG, "TaskService not available, retry " + (retryCount + 1) + "/10");
                // 延迟重试
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    showChargingOnRearScreenWithRetry(level, isLocked, retryCount + 1);
                }, 100);
                return;
            } else {
                Log.e(TAG, "TaskService still not available after 10 retries, aborting");
                return;
            }
        }

        // 只在确认TaskService可用、动画确实要开始播放时才记录冷却时间戳，
        // 避免因TaskService未就绪等临时失败而误锁住后续6秒内的重试
        lastChargingAnimationTime = System.currentTimeMillis();

        acquireWakeLock(8000);
        try {
            // 步骤1: 检查背屏是否有投送的应用
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            int rearTaskId = -1;
            
            if (lastTask != null && lastTask.contains(":")) {
                try {
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    
                    // 如果当前背屏前台是充电动画，说明上一次动画还没完全销毁，使用lastTask
                    if (rearForegroundApp != null && rearForegroundApp.contains("RearScreenChargingActivity")) {
                        Log.d(TAG, "充电动画正在显示，使用lastTask: " + lastTask);
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                    } else if (rearForegroundApp != null && rearForegroundApp.equals(lastTask)) {
                        // 背屏确实有投送的app在运行
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                        Log.d(TAG, "背屏有投送app: " + lastTask);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查背屏app失败", e);
                }
            }
            
            // 步骤2: 如果有投送app，暂停RearScreenKeeperService的监控
            if (rearTaskId > 0) {
                RearScreenKeeperService.pauseMonitoring();
            }
            
            // 步骤3: 禁用官方Launcher
            taskService.disableSubScreenLauncher();
            
            long startTime = System.currentTimeMillis();
            Log.d(TAG, String.format("[%tT.%tL] 开始启动充电动画", startTime, startTime));
            
            // V3.3: 移除所有唤醒和解锁代码，避免锁屏时跳转到密码界面

            // 先唤醒背屏，再启动Activity，这样动画落在已点亮的屏幕上。
            // 背屏处于DOZE/DOZE_SUSPEND时窗口flag（FLAG_TURN_SCREEN_ON）无效，
            // 只有这条命令能把它点亮（等同双击唤醒），与NotificationService一致。
            // 背屏原本熄灭时，唤醒完成那一刻HyperOS会把SubScreenLauncher拉到前台并移除我们的任务，
            // 所以要等唤醒完成（实测约1.5秒）再启动动画；背屏已亮则无需等待。
            android.view.Display rearDisplay = ((android.hardware.display.DisplayManager)
                    getSystemService(Context.DISPLAY_SERVICE)).getDisplay(1);
            boolean rearWasOn = rearDisplay != null && rearDisplay.getState() == android.view.Display.STATE_ON;
            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                if (!rearWasOn) {
                    Thread.sleep(1500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "唤醒背屏失败: " + t.getMessage());
            }

            // 步骤4: 使用MRSN的策略 - 先在主屏隐形启动，然后移动到背屏
            try {
                // 4.1: 先在主屏启动（Activity会在onCreate自动隐藏）
                String componentName = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
                String mainCmd = String.format(
                    "am start -n %s --ei batteryLevel %d --ei rearTaskId %d",
                    componentName,
                    level,
                    rearTaskId
                );
                
                Log.d(TAG, String.format("[%tT.%tL] 🔵 在主屏启动Activity", System.currentTimeMillis(), System.currentTimeMillis()));
                taskService.executeShellCommand(mainCmd);
                
                // 4.2: 轮询获取taskId（最多60次 x 30ms = 1800ms，期间重发命令）
                String chargingTaskId = null;
                int attempts = 0;
                int maxAttempts = 60;
                
                while (chargingTaskId == null && attempts < maxAttempts) {
                    Thread.sleep(30);
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenChargingActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        // 解析 taskId=XXX
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            chargingTaskId = matcher.group(1);
                            Log.d(TAG, String.format("[%tT.%tL] 找到taskId=%s (尝试%d次)", 
                                System.currentTimeMillis(), System.currentTimeMillis(), chargingTaskId, attempts + 1));
                            break;
                        }
                    }
                    attempts++;
                    if (attempts == 20 || attempts == 40) { // 中途重发一到两次启动命令
                        Log.d(TAG, String.format("[%tT.%tL] 重新发送主屏启动命令", System.currentTimeMillis(), System.currentTimeMillis()));
                        taskService.executeShellCommand(mainCmd);
                    }
                }
                
                if (chargingTaskId != null) {
                    // 4.3: 移动到背屏
                    String moveCmd = "am display move-stack " + chargingTaskId + " 1";
                    taskService.executeShellCommand(moveCmd);
                    Thread.sleep(40); // 等待移动完成
                    
                    // 4.4: 只在锁屏时关闭主屏（亮屏时不需要关闭）
                    if (isLocked) {
                        // 主屏休眠功能已移除
                        Log.d(TAG, String.format("[%tT.%tL] 锁屏状态，主屏已关闭", 
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    } else {
                        Log.d(TAG, String.format("[%tT.%tL] 亮屏状态，保持主屏开启", 
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    }
                    
                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("[%tT.%tL] 充电动画已移动到背屏 (总耗时%dms)", 
                        endTime, endTime, endTime - startTime));
                } else {
                    Log.e(TAG, String.format("[%tT.%tL] 未能找到taskId, 尝试了%d次", 
                        System.currentTimeMillis(), System.currentTimeMillis(), attempts));
                }
            } catch (Exception e) {
                long errorTime = System.currentTimeMillis();
                Log.e(TAG, String.format("[%tT.%tL] 启动充电动画失败", errorTime, errorTime), e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing charging", e);
        } finally {
            releaseWakeLock();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 停止前台服务
        stopForeground(true);
        
        // 清除静态实例
        instance = null;

        // 取消待放的充电动画，避免Service销毁后仍持有引用
        debounceHandler.removeCallbacks(pendingChargingRunnable);

        // 移除Shizuku监听器
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            Log.e(TAG, "Error removing Shizuku listeners", e);
        }
        
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering battery receiver", e);
        }
        
        // V3.5: 注销设置变化接收器
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering settings receiver", e);
        }
        
        // V3.5: 注销恢复充电动画接收器
        try {
            unregisterReceiver(resumeChargingReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering resume charging receiver", e);
        }
        
        // V3.5: 停止唤醒循环
        stopWakeupLoop();
        
        // 解绑TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding TaskService", e);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    // V3.5: 启动唤醒和更新电量循环
    private void startWakeupAndUpdateLoop() {
        if (isWakeupRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isWakeupRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning) return;
                
                // 检查开关状态
                boolean enabled = prefs.getBoolean("charging_always_on_enabled", false);
                if (!enabled) {
                    Log.d(TAG, "充电动画常亮已关闭，停止循环");
                    stopWakeupLoop();
                    return;
                }
                
                // 发送wakeup命令
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                        Log.d(TAG, "✓ Wakeup sent");
                    } else {
                        Log.w(TAG, "⚠️ TaskService is null, skipping wakeup");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "发送wakeup失败: " + t.getMessage());
                }
                
                // 更新充电动画的电量显示
                try {
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    // 直接调用静态方法更新电量
                    RearScreenChargingActivity.updateBatteryLevelStatic(batteryLevel);
                    Log.d(TAG, "🔋 电量已直接更新: " + batteryLevel + "%");
                } catch (Exception e) {
                    Log.w(TAG, "更新电量失败: " + e.getMessage());
                }
                
                // 100ms后继续
                wakeupHandler.postDelayed(this, 2000);
            }
        };
        
        // 立即开始
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup and update loop started");
    }
    
    // V3.5: 停止唤醒循环
    private void stopWakeupLoop() {
        isWakeupRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }
}

