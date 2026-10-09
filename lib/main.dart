/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪 (Luodao T0 Yu Xie Shu Shi Cheng Shan)
 * 
 * Chief Tester: 汐木泽 (Xi Mu Ze)
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:ui';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'l10n/app_localizations.dart';

void main() {
  // Set immersive status bar (transparent)
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  runApp(const DisplaySwitcherApp());
}

class DisplaySwitcherApp extends StatelessWidget {
  const DisplaySwitcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MRSS',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      locale: const Locale('en'),
      supportedLocales: const [Locale('en')],
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

enum ShizukuStatus { checking, running, error }

class _HomePageState extends State<HomePage> {
  static const platform = MethodChannel('com.display.switcher/task');

  // Status Enum
  ShizukuStatus _shizukuStatus = ShizukuStatus.checking;
  bool _shizukuRunning = false;
  // String _statusMessage = 'Checking Shizuku...'; // Removed
  String _customErrorTitle = ''; // For specific error types
  bool _isLoading = false;
  bool _hasError = false; // whether an error occurred
  String _errorDetail = ''; // error detail

  // V15: rear-screen DPI
  int _currentRearDpi = 0;
  bool _dpiLoading = true; // DPI loading state
  final TextEditingController _dpiController = TextEditingController();
  final FocusNode _dpiFocusNode = FocusNode();

  // V2.1: display control
  int _currentRotation = 0; // current rotation (0=0°, 1=90°, 2=180°, 3=270°)

  // V2.2: proximity sensor toggle
  bool _proximitySensorEnabled = true; // on by default

  // V2.3: charging animation toggle
  bool _chargingAnimationEnabled = true; // on by default

  // V2.5: rear-screen always-on toggle
  bool _keepScreenOnEnabled = true; // on by default

  // V3.5: always-wake-when-no-app-cast toggle (mutually exclusive with rear always-on)
  bool _alwaysWakeUpEnabled = false; // off by default

  // V3.5: charging always-on toggle
  bool _chargingAlwaysOnEnabled = false; // off by default

  // Wake-on-lock toggle
  bool _wakeOnLockEnabled = false; // off by default

  // V2.4: notification features
  bool _notificationEnabled = false; // off by default (needs permission)

  @override
  void initState() {
    super.initState();
    _checkShizuku();
    _loadSettings(); // load all settings
    _setupMethodCallHandler();
    _loadProximitySensorSetting(); // load the proximity sensor setting

    // Notification permission is requested automatically once Shizuku is authorized (see _checkShizuku)

    // Fetch DPI/rotation lazily once TaskService is connected
    Future.delayed(const Duration(seconds: 2), () {
      _getCurrentRearDpi();
      _getCurrentRotation();
    });
  }

  @override
  void dispose() {
    _dpiController.dispose();
    _dpiFocusNode.dispose();
    super.dispose();
  }

  void _setupMethodCallHandler() {
    platform.setMethodCallHandler((call) async {
      if (call.method == 'onShizukuPermissionChanged') {
        final granted = call.arguments as bool;
        print('Shizuku permission changed: $granted');
        // Refresh state
        await _checkShizuku();

        // Once Shizuku is authorized, immediately request the notification permission
        if (granted) {
          print('✓ Shizuku authorized; requesting notification permission now');
          _requestNotificationPermission();
        }
      }
    });
  }

  Future<void> _requestNotificationPermission() async {
    // Android 13+ requires requesting the notification permission
    try {
      await platform.invokeMethod('requestNotificationPermission');
      print('Notification permission request sent');
    } catch (e) {
      print('Failed to request notification permission: $e');
    }
  }

  // V15: get the current rear-screen DPI
  Future<void> _getCurrentRearDpi() async {
    setState(() {
      _dpiLoading = true;
    });

    // Retry up to 5 times, 1s apart
    for (int i = 0; i < 5; i++) {
      try {
        final int dpi = await platform.invokeMethod('getCurrentRearDpi');
        setState(() {
          _currentRearDpi = dpi;
          _dpiController.text = dpi.toString();
          _dpiLoading = false;
        });
        print('Current rear DPI: $dpi');
        return; // success; stop
      } catch (e) {
        print('Failed to get rear DPI (attempt ${i + 1}/5): $e');
        if (i < 4) {
          await Future.delayed(const Duration(seconds: 1));
        }
      }
    }

    // All retries failed
    setState(() {
      _dpiLoading = false;
      _currentRearDpi = 0;
    });
    print('Getting rear DPI ultimately failed');
  }

  Future<void> _moveCurrentAppToRear() async {
    if (_isLoading) return;
    setState(() => _isLoading = true);
    try {
      final connected =
          await platform.invokeMethod('ensureTaskServiceConnected') ?? false;
      if (connected != true) {
        _showSnack('Shizuku/TaskService not ready');
        return;
      }
      final success =
          await platform.invokeMethod('moveCurrentAppToRear') ?? false;
      _showSnack(success ? 'Sent current app to rear screen' : 'Failed to move app');
    } catch (e) {
      _showSnack('Failed: $e');
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _returnRearAppToMain() async {
    if (_isLoading) return;
    setState(() => _isLoading = true);
    try {
      final connected =
          await platform.invokeMethod('ensureTaskServiceConnected') ?? false;
      if (connected != true) {
        _showSnack('Shizuku/TaskService not ready');
        return;
      }
      final success =
          await platform.invokeMethod('returnRearAppToMain') ?? false;
      _showSnack(success ? 'Returned rear app to main screen' : 'No rear app to return');
    } catch (e) {
      _showSnack('Failed: $e');
    } finally {
      setState(() => _isLoading = false);
    }
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  // V15: set the rear-screen DPI
  Future<void> _setRearDpi(int dpi) async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // Try reconnecting TaskService first to ensure a healthy connection
      await platform.invokeMethod('ensureTaskServiceConnected');

      // Wait for the connection
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('setRearDpi', {'dpi': dpi});

      // Refresh the current DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_dpi_set')} $dpi',
            ),
          ),
        );
      }
    } catch (e) {
      print('Failed to set rear DPI: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_set_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_shizuku')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  // V15: reset the rear-screen DPI
  Future<void> _resetRearDpi() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // Try reconnecting TaskService first to ensure a healthy connection
      await platform.invokeMethod('ensureTaskServiceConnected');

      // Wait for the connection
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('resetRearDpi');

      // Refresh the current DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(context).translate('toast_dpi_reset'),
            ),
          ),
        );
      }
    } catch (e) {
      print('Failed to reset rear DPI: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_reset_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_shizuku')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _checkShizuku() async {
    setState(() {
      _shizukuStatus = ShizukuStatus.checking;
      _hasError = false;
      _errorDetail = '';
    });

    try {
      // Simplified check: call the Java layer directly, with a timeout
      final result = await platform
          .invokeMethod('checkShizuku')
          .timeout(const Duration(seconds: 3));

      if (!mounted) return;

      setState(() {
        _shizukuRunning = result == true;
        _hasError = false;
        _errorDetail = '';

        if (_shizukuRunning) {
          _shizukuStatus = ShizukuStatus.running;

          // Shizuku authorized; immediately request the notification permission
          print('✓ Shizuku authorized; requesting notification permission now');
          _requestNotificationPermission();
        } else {
          _hasError = true;
          _shizukuStatus = ShizukuStatus.error;
          _customErrorTitle = ''; // Use default "Permission Required"
          _errorDetail = AppLocalizations.of(
            context,
          ).translate('shizuku_permission_denied');
          // Get details to help diagnose
          _getDetailedStatus();
        }
      });
    } catch (e) {
      if (!mounted) return;

      // Parse the exception type
      String errorType = '';
      String errorMsg = e.toString();

      if (errorMsg.contains('binder') || errorMsg.contains('Binder')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_shizuku_communication');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_shizuku_service_crashed');
      } else if (errorMsg.contains('permission') ||
          errorMsg.contains('Permission')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_permission_denied');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_grant_in_shizuku');
      } else if (errorMsg.contains('RemoteException')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_service_call_failed');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_task_service_no_response');
      } else if (errorMsg.contains('TimeoutException')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_check_timeout');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_shizuku_timeout');
      } else {
        errorType = AppLocalizations.of(context).translate('error_unknown');
        _errorDetail = errorMsg.length > 50
            ? '${errorMsg.substring(0, 50)}...'
            : errorMsg;
      }
      setState(() {
        _shizukuRunning = false;
        _hasError = true;
        _shizukuStatus = ShizukuStatus.error;
        _customErrorTitle = errorType;
      });
    }
  }

  Future<void> _getDetailedStatus() async {
    try {
      final info = await platform.invokeMethod('getShizukuInfo');
      setState(() {
        _errorDetail = info.toString();
      });
    } catch (e) {
      // Failed to get details; keep the current error
    }
  }

  // V2.1: restart the app
  Future<void> _restartApp() async {
    if (_isLoading) return;

    setState(() => _isLoading = true);

    try {
      // Ensure TaskService is connected
      await platform.invokeMethod('ensureTaskServiceConnected');
      await Future.delayed(const Duration(milliseconds: 500));

      // Check whether an app is on the rear screen
      final result = await platform.invokeMethod('returnRearAppAndRestart');

      if (result == true) {
        // Successfully back on the main screen; exit
        SystemNavigator.pop();
      } else {
        // No app on the rear screen; exit directly
        SystemNavigator.pop();
      }
    } catch (e) {
      // Exit even on error
      SystemNavigator.pop();
    }
  }

  // V2.2: load all settings
  Future<void> _loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
        _chargingAnimationEnabled =
            prefs.getBool('charging_animation_enabled') ?? true;
        _chargingAlwaysOnEnabled =
            prefs.getBool('charging_always_on_enabled') ??
            false; // V3.5: load the charging always-on toggle
        _keepScreenOnEnabled = prefs.getBool('keep_screen_on_enabled') ?? true;
        _alwaysWakeUpEnabled =
            prefs.getBool('always_wakeup_enabled') ??
            false; // V3.5: load the always-wake toggle
        _wakeOnLockEnabled = prefs.getBool('wake_on_lock_enabled') ?? false;

        _notificationEnabled =
            prefs.getBool('notification_service_enabled') ??
            false; // V2.4: load the rear notification toggle
      });

      // Start the charging service (if enabled)
      if (_chargingAnimationEnabled) {
        _startChargingService();
      }

      // Check the notification listener permission (do not override the toggle)
      _checkNotificationPermission();

      // V2.4: start NotificationService if the toggle is on
      if (_notificationEnabled) {
        _startNotificationService();
      }
    } catch (e) {
      print('Failed to load settings: $e');
    }
  }

  // V2.2: load the proximity sensor setting
  Future<void> _loadProximitySensorSetting() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
      });
    } catch (e) {
      print('Failed to load the proximity sensor setting: $e');
    }
  }

  // V2.4: check the notification listener permission
  Future<void> _checkNotificationPermission() async {
    try {
      final bool hasPermission = await platform.invokeMethod(
        'checkNotificationListenerPermission',
      );
      // Update only the permission state; do not override the toggle
      // _notificationEnabled is now controlled by the SharedPreferences toggle
      print('Notification listener permission: $hasPermission');
    } catch (e) {
      print('Failed to check the notification permission: $e');
    }
  }

  // V2.4: start the notification service
  Future<void> _startNotificationService() async {
    try {
      await platform.invokeMethod('startNotificationService');
      print('NotificationService started');
    } catch (e) {
      print('Failed to start NotificationService: $e');
    }
  }

  // V2.4: toggle the notification service
  Future<void> _toggleNotificationService(bool enabled) async {
    if (enabled) {
      // First check the permission
      final bool hasPermission = await platform.invokeMethod(
        'checkNotificationListenerPermission',
      );
      if (!hasPermission) {
        // Open the settings page to grant it
        await platform.invokeMethod('openNotificationListenerSettings');
        return;
      }
    }

    try {
      // Save to SharedPreferences first
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_service_enabled', enabled);

      // Have the Service update its state
      await platform.invokeMethod('toggleNotificationService', {
        'enabled': enabled,
      });

      // Start NotificationService when enabled
      if (enabled) {
        await _startNotificationService();
      }

      setState(() {
        _notificationEnabled = enabled;
      });
      print('Rear notification service ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle the rear notification service: $e');
      // Restore the previous state on failure
      setState(() {
        _notificationEnabled = !enabled;
      });
    }
  }

  // V2.4: open the app selection page
  Future<void> _openAppSelectionPage() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const AppSelectionPage()),
    );
  }

  // V2.2: toggle the proximity sensor
  Future<void> _toggleProximitySensor(bool enabled) async {
    try {
      // Save to SharedPreferences first
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('proximity_sensor_enabled', enabled);

      // Have the Service update its state
      await platform.invokeMethod('setProximitySensorEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _proximitySensorEnabled = enabled;
      });
      print('Proximity sensor ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle the proximity sensor: $e');
      // Restore the previous state on failure
      setState(() {
        _proximitySensorEnabled = !enabled;
      });
    }
  }

  // V2.3: toggle the charging animation
  Future<void> _toggleChargingAnimation(bool enabled) async {
    try {
      // Save to SharedPreferences first
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_animation_enabled', enabled);

      // Start or stop the charging service
      await platform.invokeMethod('toggleChargingService', {
        'enabled': enabled,
      });

      setState(() {
        _chargingAnimationEnabled = enabled;
      });
      print('Charging animation ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle the charging animation: $e');
      // Restore the previous state on failure
      setState(() {
        _chargingAnimationEnabled = !enabled;
      });
    }
  }

  // V2.3: start the charging service
  Future<void> _startChargingService() async {
    try {
      await platform.invokeMethod('toggleChargingService', {'enabled': true});
    } catch (e) {
      print('Failed to start the charging service: $e');
    }
  }

  // V2.5: toggle rear-screen always-on
  Future<void> _toggleKeepScreenOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('keep_screen_on_enabled', enabled);

      // V3.5: if enabling, disable always-wake
      if (enabled && _alwaysWakeUpEnabled) {
        await prefs.setBool('always_wakeup_enabled', false);
        await platform.invokeMethod('setAlwaysWakeUpEnabled', {
          'enabled': false,
        });
      }

      // Notify RearScreenKeeperService via Intent
      await platform.invokeMethod('setKeepScreenOnEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _keepScreenOnEnabled = enabled;
        if (enabled) _alwaysWakeUpEnabled = false; // V3.5: mutually exclusive
      });
      print('Rear always-on ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle rear always-on: $e');
      // Restore the previous state on failure
      setState(() {
        _keepScreenOnEnabled = !enabled;
      });
    }
  }

  // V3.5: toggle always-wake
  Future<void> _toggleAlwaysWakeUp(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('always_wakeup_enabled', enabled);

      // V3.5: if enabling, disable rear always-on
      if (enabled && _keepScreenOnEnabled) {
        await prefs.setBool('keep_screen_on_enabled', false);
        await platform.invokeMethod('setKeepScreenOnEnabled', {
          'enabled': false,
        });
      }

      // Notify AlwaysWakeUpService via Intent
      await platform.invokeMethod('setAlwaysWakeUpEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _alwaysWakeUpEnabled = enabled;
        if (enabled) _keepScreenOnEnabled = false; // V3.5: mutually exclusive
      });
      print('Always-wake ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle always-wake: $e');
      // Restore the previous state on failure
      setState(() {
        _alwaysWakeUpEnabled = !enabled;
      });
    }
  }

  // V3.5: toggle charging always-on
  Future<void> _toggleChargingAlwaysOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_always_on_enabled', enabled);

      // Notify ChargingAlwaysOnService via Intent
      await platform.invokeMethod('setChargingAlwaysOnEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _chargingAlwaysOnEnabled = enabled;
      });
      print('Charging always-on ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle charging always-on: $e');
      // Restore the previous state on failure
      setState(() {
        _chargingAlwaysOnEnabled = !enabled;
      });
    }
  }

  // Toggle wake-on-lock
  Future<void> _toggleWakeOnLock(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('wake_on_lock_enabled', enabled);

      await platform.invokeMethod('setWakeOnLockEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _wakeOnLockEnabled = enabled;
      });
      print('Wake-on-lock ${enabled ? "enabled" : "disabled"}');
    } catch (e) {
      print('Failed to toggle wake-on-lock: $e');
      // Restore the previous state on failure
      setState(() {
        _wakeOnLockEnabled = !enabled;
      });
    }
  }

  String _getDisplayStatus(BuildContext context) {
    switch (_shizukuStatus) {
      case ShizukuStatus.checking:
        return AppLocalizations.of(context).translate('check_shizuku');
      case ShizukuStatus.running:
        return AppLocalizations.of(context).translate('status_ready');
      case ShizukuStatus.error:
        return _customErrorTitle.isNotEmpty
            ? _customErrorTitle
            : AppLocalizations.of(context).translate('permission_required');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        title: const Text(
          'MRSS',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt),
            onPressed: _restartApp,
            tooltip: 'Restart app',
          ),
        ],
      ),
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88), // coral orange
              Color(0xFFFFB5C5), // pink
              Color(0xFFE0B5DC), // purple
              Color(0xFFA8C5E5), // blue
            ],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            physics: const BouncingScrollPhysics(), // always allow scrolling
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // Unified status and permission card (frosted glass)
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  _shizukuRunning
                                      ? Icons.check_circle
                                      : (_hasError
                                            ? Icons.error_outline
                                            : Icons.warning_rounded),
                                  size: 28,
                                  color: _shizukuRunning
                                      ? Colors.green
                                      : (_hasError
                                            ? Colors.red
                                            : Colors.orange),
                                ),
                                const SizedBox(width: 10),
                                Text(
                                  _getDisplayStatus(context),
                                  style: const TextStyle(
                                    fontSize: 16,
                                    color: Colors.black87,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ],
                            ),
                            if (_hasError && _errorDetail.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              Text(
                                _errorDetail,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.black54,
                                  height: 1.3,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V15: rear DPI adjustment card
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.all(20),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('dpi_settings'),
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium
                                          ?.copyWith(
                                            color: Colors.black87,
                                            fontWeight: FontWeight.bold,
                                          ),
                                    ),
                                    if (_dpiLoading) ...[
                                      const SizedBox(width: 12),
                                      const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          valueColor:
                                              AlwaysStoppedAnimation<Color>(
                                                Colors.black54,
                                              ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  _dpiLoading
                                      ? AppLocalizations.of(
                                          context,
                                        ).translate('checking_dpi')
                                      : '${AppLocalizations.of(context).translate('current_dpi').replaceAll('%d', _currentRearDpi.toString())}  ${AppLocalizations.of(context).translate('recommended_range')}',
                                  style: const TextStyle(
                                    color: Colors.black54,
                                    fontSize: 14,
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    Expanded(
                                      child: TextField(
                                        controller: _dpiController,
                                        focusNode: _dpiFocusNode,
                                        enabled: !_dpiLoading && !_isLoading,
                                        keyboardType: TextInputType.number,
                                        style: const TextStyle(
                                          color: Colors.black87,
                                        ),
                                        decoration: InputDecoration(
                                          labelText: AppLocalizations.of(
                                            context,
                                          ).translate('new_dpi'),
                                          labelStyle: const TextStyle(
                                            color: Colors.black54,
                                          ),
                                          hintText: AppLocalizations.of(
                                            context,
                                          ).translate('input_number'),
                                          hintStyle: const TextStyle(
                                            color: Colors.black38,
                                          ),
                                          border: const OutlineInputBorder(
                                            borderRadius: BorderRadius.all(
                                              Radius.circular(
                                                _SquircleRadii.small,
                                              ),
                                            ),
                                            borderSide: BorderSide(
                                              color: Colors.black26,
                                            ),
                                          ),
                                          enabledBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black26,
                                                ),
                                              ),
                                          focusedBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black54,
                                                  width: 2,
                                                ),
                                              ),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
                                      child: Container(
                                        decoration: const BoxDecoration(
                                          gradient: LinearGradient(
                                            begin: Alignment.topLeft,
                                            end: Alignment.bottomRight,
                                            colors: [
                                              Color(0xFFFF9D88), // coral orange
                                              Color(0xFFFFB5C5), // pink
                                              Color(0xFFE0B5DC), // purple
                                              Color(0xFFA8C5E5), // blue
                                            ],
                                          ),
                                        ),
                                        child: ElevatedButton(
                                          onPressed: (_isLoading || _dpiLoading)
                                              ? null
                                              : () {
                                                  final dpi = int.tryParse(
                                                    _dpiController.text,
                                                  );
                                                  if (dpi != null && dpi > 0) {
                                                    _setRearDpi(dpi);
                                                  } else {
                                                    ScaffoldMessenger.of(
                                                      context,
                                                    ).showSnackBar(
                                                      SnackBar(
                                                        content: Text(
                                                          AppLocalizations.of(
                                                            context,
                                                          ).translate(
                                                            'input_number',
                                                          ), // Reusing input_number or need invalid_input
                                                        ),
                                                      ),
                                                    );
                                                  }
                                                },
                                          style: ElevatedButton.styleFrom(
                                            backgroundColor: Colors.transparent,
                                            foregroundColor: Colors.white,
                                            shadowColor: Colors.transparent,
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 20,
                                              vertical: 16,
                                            ),
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                            ),
                                          ),
                                          child: Text(
                                            AppLocalizations.of(
                                              context,
                                            ).translate('set_dpi'),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 12),
                                SizedBox(
                                  width: double.infinity,
                                  child: CustomPaint(
                                    painter: _SquircleBorderPainter(
                                      radius: _SquircleRadii.small,
                                      color: Colors.black26,
                                      strokeWidth: 1,
                                    ),
                                    child: ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
                                      child: Material(
                                        color: Colors.transparent,
                                        child: InkWell(
                                          onTap: (_isLoading || _dpiLoading)
                                              ? null
                                              : _resetRearDpi,
                                          child: Padding(
                                            padding: EdgeInsets.symmetric(
                                              vertical: 12,
                                            ),
                                            child: Row(
                                              mainAxisAlignment:
                                                  MainAxisAlignment.center,
                                              children: [
                                                Icon(
                                                  Icons.restore,
                                                  color: Colors.black87,
                                                  size: 20,
                                                ),
                                                SizedBox(width: 8),
                                                Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate(
                                                    'restore_default_dpi',
                                                  ),
                                                  style: TextStyle(
                                                    color: Colors.black87,
                                                    fontSize: 14,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),

                                const SizedBox(height: 16),
                                const Divider(color: Colors.black26, height: 1),
                                const SizedBox(height: 16),

                                // V2.1: rotation controls
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('rotation_title'),
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: Colors.black87,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                    const Spacer(),
                                    _buildRotationButton('0°', 0),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('90°', 1),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('180°', 2),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('270°', 3),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.2: rear cover-detection card (standalone)
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 20,
                              vertical: 16,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            child: Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_cover_detection_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _proximitySensorEnabled,
                                  onChanged: _toggleProximitySensor,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.5: rear always-on card
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Rear always-on toggle
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_screen_always_on_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _keepScreenOnEnabled,
                                  onChanged: _toggleKeepScreenOn,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // Always-wake toggle
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('always_wake_up_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _alwaysWakeUpEnabled,
                                  onChanged: _toggleAlwaysWakeUp,
                                ),
                              ],
                            ),
                            if (_alwaysWakeUpEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(
                                    _SquircleRadii.small,
                                  ),
                                  border: Border.all(
                                    color: Colors.orange.withOpacity(0.4),
                                    width: 1,
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('warning_burn_in'),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.black87,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // Wake-on-lock toggle
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('wake_on_lock_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _wakeOnLockEnabled,
                                  onChanged: _toggleWakeOnLock,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V2.3: charging animation card (standalone)
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Charging animation toggle
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('charging_animation_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAnimationEnabled,
                                  onChanged: _toggleChargingAnimation,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // Charging always-on toggle
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('charging_always_on_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAlwaysOnEnabled,
                                  onChanged: _toggleChargingAlwaysOn,
                                ),
                              ],
                            ),
                            if (_chargingAlwaysOnEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(
                                    _SquircleRadii.small,
                                  ),
                                  border: Border.all(
                                    color: Colors.orange.withOpacity(0.4),
                                    width: 1,
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('warning_burn_in'),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.black87,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V2.4: notification features card
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Title row
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('notification_service_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                // Hamburger button (choose apps)
                                IconButton(
                                  icon: const Icon(Icons.menu, size: 24),
                                  color: Colors.black87,
                                  onPressed: _openAppSelectionPage,
                                  tooltip: AppLocalizations.of(
                                    context,
                                  ).translate('select_apps'),
                                  padding: EdgeInsets.zero,
                                  constraints: const BoxConstraints(),
                                ),
                                const SizedBox(width: 8),
                                _GradientToggle(
                                  value: _notificationEnabled,
                                  onChanged: _toggleNotificationService,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // Rear/Main quick actions
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: _returnRearAppToMain,
                          splashColor: Colors.white.withOpacity(0.25),
                          highlightColor: Colors.white.withOpacity(0.15),
                          child: Container(
                            height: 52,
                            padding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 12,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            child: const Center(
                              child: Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  Icon(Icons.south_west,
                                      size: 18, color: Colors.black87),
                                  SizedBox(width: 6),
                                  Text(
                                    'Return rear app to main',
                                    style: TextStyle(
                                      color: Colors.black87,
                                      fontSize: 14,
                                      fontWeight: FontWeight.w600,
                                    ),
                                    textAlign: TextAlign.center,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: build rotation buttons (squircle, uniform 12px radius)
  Widget _buildRotationButton(String label, int rotation) {
    bool isSelected = _currentRotation == rotation;

    return SizedBox(
      width: 50,
      height: 32,
      child: ClipPath(
        clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
        child: Container(
          decoration: BoxDecoration(
            gradient: isSelected
                ? const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      Color(0xFFFF9D88), // coral orange
                      Color(0xFFFFB5C5), // pink
                      Color(0xFFE0B5DC), // purple
                      Color(0xFFA8C5E5), // blue
                    ],
                  )
                : null,
            color: isSelected ? null : Colors.white70,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: (_isLoading || _dpiLoading)
                  ? null
                  : () => _setRotation(rotation),
              child: Center(
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 12,
                    color: isSelected ? Colors.white : Colors.black54,
                    fontWeight: isSelected
                        ? FontWeight.w500
                        : FontWeight.normal,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: get the current rotation
  Future<void> _getCurrentRotation() async {
    try {
      final rotation = await platform.invokeMethod('getDisplayRotation', {
        'displayId': 1,
      });
      if (rotation != null && rotation >= 0) {
        setState(() {
          _currentRotation = rotation;
        });
      }
    } catch (e) {
      print('Failed to get rotation: $e');
    }
  }

  // V2.1: set the rotation
  Future<void> _setRotation(int rotation) async {
    print('[Flutter] 🔄 Starting rotation: $rotation (${rotation * 90}°)');

    if (!_shizukuRunning) {
      print('[Flutter] ❌ Shizuku not running');
      return;
    }
    if (_isLoading) {
      print('[Flutter] ⚠️ Still loading; skipping');
      return;
    }

    setState(() => _isLoading = true);

    try {
      // Ensure TaskService is connected
      print('[Flutter] 🔗 Ensuring TaskService connection...');
      final connected = await platform.invokeMethod(
        'ensureTaskServiceConnected',
      );
      print('[Flutter] 🔗 TaskService connection status: $connected');
      await Future.delayed(const Duration(milliseconds: 500));

      print(
        '[Flutter] 📡 Calling setDisplayRotation: displayId=1, rotation=$rotation',
      );
      final result = await platform.invokeMethod('setDisplayRotation', {
        'displayId': 1,
        'rotation': rotation,
      });
      print('[Flutter] 📡 setDisplayRotation returned: $result');

      if (result == true) {
        setState(() => _currentRotation = rotation);
        print('[Flutter] ✅ Rotation OK: ${rotation * 90}°');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                '${AppLocalizations.of(context).translate('toast_rotation_set')} ${rotation * 90}°',
              ),
              duration: const Duration(seconds: 1),
            ),
          );
        }
      } else {
        print('[Flutter] ❌ Rotation failed: result=$result');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                AppLocalizations.of(context).translate('toast_rotation_failed'),
              ),
            ),
          );
        }
      }
    } catch (e) {
      print('[Flutter] ❌ Rotation error: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_error')} $e',
            ),
          ),
        );
      }
    } finally {
      setState(() => _isLoading = false);
      print('[Flutter] 🏁 Rotation done');
    }
  }
}

// Gradient toggle: uniform four-segment gradient, replacing the system green switch
class _GradientToggle extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;
  const _GradientToggle({required this.value, required this.onChanged});

  @override
  State<_GradientToggle> createState() => _GradientToggleState();
}

class _GradientToggleState extends State<_GradientToggle> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => widget.onChanged(!widget.value),
        onHighlightChanged: (h) => setState(() => _pressed = h),
        customBorder: _SquircleShapeBorder(cornerRadius: _SquircleRadii.tiny),
        splashColor: Colors.white.withOpacity(0.2),
        highlightColor: Colors.white.withOpacity(0.1),
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.tiny),
          child: SizedBox(
            width: 52,
            height: 30,
            child: Stack(
              children: [
                // Base background
                Container(color: Colors.white.withOpacity(0.25)),
                // Gradient overlay with fade
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOut,
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // Knob
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 4,
                  ),
                  child: AnimatedAlign(
                    duration: const Duration(milliseconds: 220),
                    curve: Curves.easeOut,
                    alignment: widget.value
                        ? Alignment.centerRight
                        : Alignment.centerLeft,
                    child: AnimatedScale(
                      duration: const Duration(milliseconds: 120),
                      scale: _pressed ? 0.95 : 1.0,
                      child: Container(
                        width: 22,
                        height: 22,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(11),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.15),
                              blurRadius: 3,
                              offset: const Offset(0, 1),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// Optimized app list item widget (fewer rebuilds)
class _AppListItem extends StatelessWidget {
  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
  final bool isSelected;
  final VoidCallback onToggle;

  const _AppListItem({
    required this.appName,
    required this.packageName,
    required this.iconBytes,
    required this.isSelected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onToggle,
        splashColor: const Color(0x20FFB5C5), // soft pink (four-color gradient mid)
        highlightColor: const Color(0x10E0B5DC), // soft purple highlight
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Row(
            children: [
              // Icon (full resolution, lossless)
              if (iconBytes != null)
                Image.memory(
                  iconBytes!,
                  width: 48,
                  height: 48,
                  fit: BoxFit.contain,
                  gaplessPlayback: true,
                  filterQuality: FilterQuality.high,
                  isAntiAlias: true,
                )
              else
                const Icon(Icons.android, size: 48, color: Colors.white),
              const SizedBox(width: 12),
              // Text
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 15),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      packageName,
                      style: const TextStyle(
                        fontSize: 11,
                        color: Colors.white70,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // Gradient checkbox
              _GradientCheckbox(
                value: isSelected,
                onChanged: (_) => onToggle(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// Gradient checkbox (replaces the system green Checkbox) - with transition animation
class _GradientCheckbox extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;

  const _GradientCheckbox({required this.value, required this.onChanged});

  @override
  State<_GradientCheckbox> createState() => _GradientCheckboxState();
}

class _GradientCheckboxState extends State<_GradientCheckbox> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => setState(() => _pressed = true),
      onTapUp: (_) => setState(() => _pressed = false),
      onTapCancel: () => setState(() => _pressed = false),
      onTap: () => widget.onChanged(!widget.value),
      child: AnimatedScale(
        duration: const Duration(milliseconds: 120),
        scale: _pressed ? 0.9 : 1.0,
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.checkbox),
          child: SizedBox(
            width: 24,
            height: 24,
            child: Stack(
              children: [
                // Translucent base background
                Container(color: Colors.white.withOpacity(0.25)),
                // Gradient layer (fade in/out)
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // Border (fading) - squircle border drawn with CustomPaint
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 0.0 : 1.0,
                  child: CustomPaint(
                    painter: _SquircleBorderPainter(
                      radius: _SquircleRadii.checkbox,
                      color: Colors.white.withOpacity(0.4),
                      strokeWidth: 2,
                    ),
                  ),
                ),
                // Checkmark (scale pop)
                Center(
                  child: AnimatedScale(
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutBack,
                    scale: widget.value ? 1.0 : 0.0,
                    child: const Icon(
                      Icons.check,
                      size: 18,
                      color: Colors.white,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Squircle corner radii
/// Based on the screen's physical corner radius of 16.4mm, squircle exponent n=2.84
/// Fixed values for visual consistency (computed for the standard 420 DPI)
class _SquircleRadii {
  // 16.4mm @ 420dpi ≈ 27dp; the actual screen is a bit larger, so use 32dp
  static const double large = 32.0; // large card radius
  static const double small = 12.0; // small widget radius (large * 0.375)
  static const double tiny = 16.0; // toggle radius
  static const double checkbox = 6.0; // checkbox radius
}

/// Precise squircle shape outline - for InkWell ripples
/// Uses the 2.84 exponent for a curve matching the screen corners
class _SquircleShapeBorder extends ShapeBorder {
  final double cornerRadius;
  static const double n = 2.84; // squircle exponent

  const _SquircleShapeBorder({required this.cornerRadius});

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}

  @override
  ShapeBorder scale(double t) =>
      _SquircleShapeBorder(cornerRadius: cornerRadius * t);

  static Path _createSquirclePath(Size size, double radius) {
    final double width = size.width;
    final double height = size.height;
    final double effectiveRadius = radius.clamp(
      0.0,
      math.min(width, height) / 2,
    );

    final path = Path();

    // Top-left corner
    path.moveTo(0, effectiveRadius);
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // Top edge
    path.lineTo(width - effectiveRadius, 0);

    // Top-right corner
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // Right edge
    path.lineTo(width, height - effectiveRadius);

    // Bottom-right corner
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2 + math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // Bottom edge
    path.lineTo(effectiveRadius, height);

    // Bottom-left corner
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2 + math.pi;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    path.close();
    return path;
  }
}

/// Precise squircle clipper
/// Uses the 2.84 exponent for a curve matching the screen corners
class _SquircleClipper extends CustomClipper<Path> {
  final double cornerRadius;
  static const double n = 2.84; // squircle exponent

  _SquircleClipper({required this.cornerRadius});

  @override
  Path getClip(Size size) {
    return _createSquirclePath(size, cornerRadius);
  }

  Path _createSquirclePath(Size size, double radius) {
    final w = size.width;
    final h = size.height;
    final r = radius;

    final path = Path();

    // Draw clockwise from the top left
    path.moveTo(0, r);

    // Top-left squircle
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);

    // Top edge
    path.lineTo(w - r, 0);

    // Top-right squircle
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);

    // Right edge
    path.lineTo(w, h - r);

    // Bottom-right squircle
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);

    // Bottom edge
    path.lineTo(r, h);

    // Bottom-left squircle
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);

    path.close();
    return path;
  }

  void _drawSquircleArc(
    Path path,
    double cx,
    double cy,
    double radius,
    double startAngle,
    double endAngle,
  ) {
    const int segments = 30;

    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;

      final cosA = math.cos(angle);
      final sinA = math.sin(angle);

      // Squircle formula: r * sgn(t) * |t|^(2/n)
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);

      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldReclip(_SquircleClipper oldClipper) =>
      oldClipper.cornerRadius != cornerRadius;
}

/// Precise squircle border painter
/// Draws squircles with borders
class _SquircleBorderPainter extends CustomPainter {
  final double radius;
  final Color color;
  final double strokeWidth;
  static const double n = 2.84; // squircle exponent

  _SquircleBorderPainter({
    required this.radius,
    required this.color,
    required this.strokeWidth,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;

    final path = _createSquirclePath(size, radius);
    canvas.drawPath(path, paint);
  }

  Path _createSquirclePath(Size size, double r) {
    final w = size.width;
    final h = size.height;

    final path = Path();
    path.moveTo(0, r);

    // Top-left
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    path.lineTo(w - r, 0);

    // Top-right
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    path.lineTo(w, h - r);

    // Bottom-right
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    path.lineTo(r, h);

    // Bottom-left
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);

    path.close();
    return path;
  }

  void _drawSquircleArc(
    Path path,
    double cx,
    double cy,
    double radius,
    double startAngle,
    double endAngle,
  ) {
    const int segments = 30;
    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);
      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldRepaint(_SquircleBorderPainter oldDelegate) {
    return oldDelegate.radius != radius ||
        oldDelegate.color != color ||
        oldDelegate.strokeWidth != strokeWidth;
  }
}

/// V2.4: app selection page
class AppSelectionPage extends StatefulWidget {
  const AppSelectionPage({super.key});

  @override
  State<AppSelectionPage> createState() => _AppSelectionPageState();
}

class _AppSelectionPageState extends State<AppSelectionPage> {
  static const platform = MethodChannel(
    'com.display.switcher/task',
  ); // ✅ fixed channel name

  List<Map<String, dynamic>> _apps = [];
  List<Map<String, dynamic>> _visibleApps = [];
  Set<String> _selectedApps = {};
  bool _isLoading = true;

  bool _includeSystemApps = false; // whether to show system apps
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  // Start the permission-check loop (background async)
  void _startPermissionCheckLoop() async {
    print('→ Starting the permission-check loop');
    int checkAttempts = 0;

    while (checkAttempts < 30 && mounted) {
      // Check at most 30 times (30s)
      await Future.delayed(const Duration(seconds: 1));

      if (!mounted) break; // page destroyed; stop the loop

      try {
        final bool granted = await platform.invokeMethod(
          'checkQueryAllPackagesPermission',
        );
        if (granted) {
          print('✓ Permission granted; refreshing the app list');

          // Permission granted; refresh the list
          if (mounted) {
            setState(() {
              _isLoading = true;
            });

            await _loadAppsInternal();

            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(
                  AppLocalizations.of(
                    context,
                  ).translate('permission_granted_refresh'),
                ),
              ),
            );
          }
          return; // success; exit the loop
        }
      } catch (e) {
        print('Permission check failed: $e');
      }

      checkAttempts++;
    }

    print('⚠ Permission check timed out (30s); user may not have granted it');

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context).translate('grant_permission_manual'),
          ),
        ),
      );
    }
  }

  // Internal load method (no permission check)
  Future<void> _loadAppsInternal() async {
    try {
      // Load the selected apps
      final List<dynamic> selectedApps = await platform.invokeMethod(
        'getSelectedNotificationApps',
      );
      _selectedApps = selectedApps.cast<String>().toSet();

      // Load all apps
      final List<dynamic> apps = await platform.invokeMethod(
        'getInstalledApps',
      );

      setState(() {
        _apps = apps.map((app) => Map<String, dynamic>.from(app)).toList();
        _isLoading = false;
      });

      _applyFilters();

      print('Loaded ${_apps.length} apps');
    } catch (e) {
      print('Failed to load the app list: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  void _applyFilters() {
    final String q = _searchController.text.trim().toLowerCase();
    List<Map<String, dynamic>> filtered = _apps.where((app) {
      final String name = (app['appName'] ?? '').toString().toLowerCase();
      final String pkg = (app['packageName'] ?? '').toString().toLowerCase();
      final bool matchesQuery =
          q.isEmpty || name.contains(q) || pkg.contains(q);
      if (!_includeSystemApps && _isSystemApp(app)) {
        return false;
      }
      return matchesQuery;
    }).toList();

    // Sort: selected apps first, then by app name
    filtered.sort((a, b) {
      final String pkgA = a['packageName'] ?? '';
      final String pkgB = b['packageName'] ?? '';
      final bool selectedA = _selectedApps.contains(pkgA);
      final bool selectedB = _selectedApps.contains(pkgB);

      // If one is selected and the other is not, the selected one comes first
      if (selectedA && !selectedB) return -1;
      if (!selectedA && selectedB) return 1;

      // If both are selected or both unselected, sort by name
      final String nameA = (a['appName'] ?? '').toString().toLowerCase();
      final String nameB = (b['appName'] ?? '').toString().toLowerCase();
      return nameA.compareTo(nameB);
    });

    setState(() {
      _visibleApps = filtered;
    });
  }

  bool _isSystemApp(Map<String, dynamic> app) {
    final pkg = (app['packageName'] ?? '').toString();
    final dynamic flag1 = app['isSystem'];
    final dynamic flag2 = app['isSystemApp'];
    if (flag1 == true || flag2 == true) return true;
    return pkg.startsWith('com.android.') ||
        pkg.startsWith('com.google.android.') ||
        pkg.startsWith('android');
  }

  Future<void> _selectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.add(pkg);
      }
    });
    // Re-apply the filter to update the sort
    _applyFilters();
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('Failed to save select-all: $e');
    }
  }

  Future<void> _deselectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.remove(pkg);
      }
    });
    // Re-apply the filter to update the sort
    _applyFilters();
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('Failed to save select-none: $e');
    }
  }

  Future<void> _loadApps() async {
    setState(() => _isLoading = true);

    try {
      // ✅ Actively check QUERY_ALL_PACKAGES permission
      print('🔍 Checking QUERY_ALL_PACKAGES permission...');
      final bool hasPermission = await platform.invokeMethod(
        'checkQueryAllPackagesPermission',
      );
      print('🔍 Permission check result: $hasPermission');

      if (!hasPermission) {
        print('❌ No QUERY_ALL_PACKAGES permission; showing the dialog');
        // No permission; prompt and go to settings
        setState(() => _isLoading = false);

        if (mounted) {
          final shouldOpenSettings = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: Text(
                AppLocalizations.of(
                  context,
                ).translate('no_permission_dialog_title'),
              ),
              content: Text(
                AppLocalizations.of(
                  context,
                ).translate('no_permission_dialog_content'),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: Text(AppLocalizations.of(context).translate('cancel')),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: Text(
                    AppLocalizations.of(context).translate('go_to_settings'),
                  ),
                ),
              ],
            ),
          );

          if (shouldOpenSettings == true) {
            await platform.invokeMethod('requestQueryAllPackagesPermission');

            // Start the background check (does not block the UI)
            _startPermissionCheckLoop();
          }
        }
        return;
      }

      // ✅ Has permission; continue loading
      await _loadAppsInternal();
    } catch (e) {
      print('Failed to load the app list: $e');
      setState(() => _isLoading = false);
    }
  }

  Future<void> _toggleApp(String packageName, bool selected) async {
    setState(() {
      if (selected) {
        _selectedApps.add(packageName);
      } else {
        _selectedApps.remove(packageName);
      }
    });

    // Re-apply the filter to update the sort (selected apps on top)
    _applyFilters();

    // Save in the background
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('Failed to save the selection: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          '${AppLocalizations.of(context).translate('select_app_title')} (${_selectedApps.length})',
        ),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const NotificationSettingsPage(),
                ),
              );
            },
            tooltip: AppLocalizations.of(
              context,
            ).translate('notification_settings_tooltip'),
          ),
        ],
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88), // coral orange
              Color(0xFFFFB5C5), // pink
              Color(0xFFE0B5DC), // purple
              Color(0xFFA8C5E5), // blue
            ],
          ),
        ),
        child: SafeArea(
          child: _isLoading
              ? const Center(
                  child: CircularProgressIndicator(color: Colors.white),
                )
              : Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    children: [
                      // Filter and batch-operation card
                      CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: 32,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(
                            cornerRadius: _SquircleRadii.large,
                          ),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 12,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.25),
                              ),
                              child: Column(
                                children: [
                                  TextField(
                                    controller: _searchController,
                                    onChanged: (_) => _applyFilters(),
                                    style: const TextStyle(
                                      color: Colors.black87,
                                    ),
                                    decoration: InputDecoration(
                                      hintText: AppLocalizations.of(
                                        context,
                                      ).translate('search_hint'),
                                      hintStyle: const TextStyle(
                                        color: Colors.black45,
                                      ),
                                      prefixIcon: Icon(
                                        Icons.search,
                                        color: Colors.black54,
                                      ),
                                      border: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black26,
                                        ),
                                      ),
                                      enabledBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black26,
                                        ),
                                      ),
                                      focusedBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black54,
                                          width: 2,
                                        ),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 10),
                                  Row(
                                    children: [
                                      // Select all / select none
                                      ClipPath(
                                        clipper: _SquircleClipper(
                                          cornerRadius: _SquircleRadii.small,
                                        ),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _selectAllVisible,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 12,
                                                      vertical: 8,
                                                    ),
                                                child: Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate('select_all'),
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                  ),
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const SizedBox(width: 8),
                                      ClipPath(
                                        clipper: _SquircleClipper(
                                          cornerRadius: _SquircleRadii.small,
                                        ),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _deselectAllVisible,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 12,
                                                      vertical: 8,
                                                    ),
                                                child: Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate('deselect_all'),
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                  ),
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const Spacer(),
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('show_system_apps'),
                                        style: const TextStyle(
                                          color: Colors.black87,
                                          fontSize: 11,
                                        ),
                                      ),
                                      const SizedBox(width: 6),
                                      _GradientToggle(
                                        value: _includeSystemApps,
                                        onChanged: (v) {
                                          setState(
                                            () => _includeSystemApps = v,
                                          );
                                          _applyFilters();
                                        },
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      // App list
                      Expanded(
                        child: ListView.builder(
                          itemCount: _visibleApps.length,
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          itemExtent: 72,
                          cacheExtent: 500,
                          addAutomaticKeepAlives: false,
                          addRepaintBoundaries: true,
                          physics: const ClampingScrollPhysics(),
                          itemBuilder: (context, index) {
                            final app = _visibleApps[index];
                            final String appName = app['appName'];
                            final String packageName = app['packageName'];
                            final Uint8List? iconBytes = app['icon'];
                            final bool isSelected = _selectedApps.contains(
                              packageName,
                            );
                            return _AppListItem(
                              appName: appName,
                              packageName: packageName,
                              iconBytes: iconBytes,
                              isSelected: isSelected,
                              onToggle: () =>
                                  _toggleApp(packageName, !isSelected),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

/// V3.4: notification settings page
class NotificationSettingsPage extends StatefulWidget {
  const NotificationSettingsPage({super.key});

  @override
  State<NotificationSettingsPage> createState() =>
      _NotificationSettingsPageState();
}

class _NotificationSettingsPageState extends State<NotificationSettingsPage> {
  static const platform = MethodChannel('com.display.switcher/task');

  bool _privacyHideTitle = false;
  bool _privacyHideContent = false;
  bool _followDndMode = true;
  bool _onlyWhenLocked = false;
  bool _notificationDarkMode = false;
  int _notificationDuration = 10;
  final TextEditingController _durationController = TextEditingController();
  final FocusNode _durationFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _loadAllSettings();
  }

  @override
  void dispose() {
    _durationController.dispose();
    _durationFocusNode.dispose();
    super.dispose();
  }

  Future<void> _loadAllSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _privacyHideTitle =
            prefs.getBool('notification_privacy_hide_title') ?? false;
        _privacyHideContent =
            prefs.getBool('notification_privacy_hide_content') ?? false;
        _followDndMode = prefs.getBool('notification_follow_dnd_mode') ?? true;
        _onlyWhenLocked =
            prefs.getBool('notification_only_when_locked') ?? false;
        _notificationDarkMode =
            prefs.getBool('notification_dark_mode') ?? false;
        _notificationDuration = prefs.getInt('notification_duration') ?? 10;
        _durationController.text = _notificationDuration.toString();
      });
    } catch (e) {
      print('Failed to load notification settings: $e');
    }
  }

  Future<void> _togglePrivacyHideTitle(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideTitle', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_title', enabled);
      setState(() {
        _privacyHideTitle = enabled;
      });
    } catch (e) {
      print('Failed to toggle hide-title: $e');
    }
  }

  Future<void> _togglePrivacyHideContent(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideContent', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_content', enabled);
      setState(() {
        _privacyHideContent = enabled;
      });
    } catch (e) {
      print('Failed to toggle hide-content: $e');
    }
  }

  Future<void> _toggleFollowDndMode(bool enabled) async {
    try {
      await platform.invokeMethod('setFollowDndMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_follow_dnd_mode', enabled);
      setState(() {
        _followDndMode = enabled;
      });
    } catch (e) {
      print('Failed to set follow-DND: $e');
    }
  }

  Future<void> _toggleOnlyWhenLocked(bool enabled) async {
    try {
      await platform.invokeMethod('setOnlyWhenLocked', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_only_when_locked', enabled);
      setState(() {
        _onlyWhenLocked = enabled;
      });
    } catch (e) {
      print('Failed to set notify-only-when-locked: $e');
    }
  }

  Future<void> _toggleNotificationDarkMode(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationDarkMode', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_dark_mode', enabled);
      setState(() {
        _notificationDarkMode = enabled;
      });
    } catch (e) {
      print('Failed to toggle notification dark mode: $e');
    }
  }

  Future<void> _setNotificationDuration(int seconds) async {
    try {
      await platform.invokeMethod('setNotificationDuration', {
        'duration': seconds,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('notification_duration', seconds);
      setState(() {
        _notificationDuration = seconds;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(
                context,
              ).translate('toast_duration_set').replaceAll('{0}', '$seconds'),
            ),
          ),
        );
      }
    } catch (e) {
      print('Failed to set the notification auto-destroy time: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppLocalizations.of(context).translate('notification_settings_title'),
        ),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88),
              Color(0xFFFFB5C5),
              Color(0xFFE0B5DC),
              Color(0xFFA8C5E5),
            ],
          ),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              // Privacy-mode card
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              const Icon(
                                Icons.lock_outline,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('hide_notification_title'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideTitle,
                                onChanged: _togglePrivacyHideTitle,
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          const Divider(color: Colors.black26, height: 1),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              const Icon(
                                Icons.lock_outline,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('hide_notification_content'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideContent,
                                onChanged: _togglePrivacyHideContent,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // Follow system DND
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.notifications_paused,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('follow_system_dnd'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _followDndMode,
                            onChanged: _toggleFollowDndMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // Notify only when locked
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.screen_lock_portrait,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('only_when_locked'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _onlyWhenLocked,
                            onChanged: _toggleOnlyWhenLocked,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // Notification dark mode
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.dark_mode,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('notification_dark_mode'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _notificationDarkMode,
                            onChanged: _toggleNotificationDarkMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // Auto-destroy time
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(
                                Icons.timer_outlined,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('auto_destroy_time'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: TextField(
                                  controller: _durationController,
                                  focusNode: _durationFocusNode,
                                  keyboardType: TextInputType.number,
                                  style: const TextStyle(color: Colors.black87),
                                  decoration: InputDecoration(
                                    labelText: AppLocalizations.of(
                                      context,
                                    ).translate('new_time_seconds'),
                                    labelStyle: const TextStyle(
                                      color: Colors.black54,
                                    ),
                                    hintText: AppLocalizations.of(
                                      context,
                                    ).translate('input_seconds'),
                                    hintStyle: const TextStyle(
                                      color: Colors.black38,
                                    ),
                                    border: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black26,
                                      ),
                                    ),
                                    enabledBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black26,
                                      ),
                                    ),
                                    focusedBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black54,
                                        width: 2,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 12),
                              ClipPath(
                                clipper: _SquircleClipper(
                                  cornerRadius: _SquircleRadii.small,
                                ),
                                child: Container(
                                  decoration: const BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topLeft,
                                      end: Alignment.bottomRight,
                                      colors: [
                                        Color(0xFFFF9D88),
                                        Color(0xFFFFB5C5),
                                        Color(0xFFE0B5DC),
                                        Color(0xFFA8C5E5),
                                      ],
                                    ),
                                  ),
                                  child: ElevatedButton(
                                    onPressed: () {
                                      final seconds = int.tryParse(
                                        _durationController.text,
                                      );
                                      if (seconds != null && seconds > 0) {
                                        _setNotificationDuration(seconds);
                                      } else {
                                        ScaffoldMessenger.of(
                                          context,
                                        ).showSnackBar(
                                          SnackBar(
                                            content: Text(
                                              AppLocalizations.of(
                                                context,
                                              ).translate('input_valid_number'),
                                            ),
                                          ),
                                        );
                                      }
                                    },
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.transparent,
                                      foregroundColor: Colors.white,
                                      shadowColor: Colors.transparent,
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 20,
                                        vertical: 12,
                                      ),
                                    ),
                                    child: Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('confirm'),
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
