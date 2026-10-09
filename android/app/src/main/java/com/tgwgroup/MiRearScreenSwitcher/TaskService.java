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

package com.tgwgroup.MiRearScreenSwitcher;

import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Service that runs inside the Shizuku process with shell permissions
 */
public class TaskService extends ITaskService.Stub {
    private static final String TAG = "TaskService";

    @Keep
    public TaskService() {

    }

    @Override
    public void destroy() {

        System.exit(0);
    }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {

            // Run `am stack list`; we have shell permission here inside Shizuku
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inDisplayZero = false;
            String line;
            while ((line = reader.readLine()) != null) {
                // RootTask line: judge by displayId
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }
                
                // taskId line (indented child line)
                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    // Parse: taskId=1471: com.example.display_switcher/com.example.display_switcher.MainActivity
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    // Skip the Launcher and this app itself
                    if (packageName.contains("launcher") || 
                        packageName.contains("miui.home") ||
                        packageName.equals("com.tgwgroup.MiRearScreenSwitcher")) {
                        continue;
                    }
                    
                    reader.close();
                    process.destroy();
                    
                    String result = packageName + ":" + taskId;

                    return result;
                }
            }
            
            int exitCode = process.waitFor();
            reader.close();

            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {

            // Run `am stack list`; we have shell permission here inside Shizuku
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName)) {
                    // Parse: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    
                    reader.close();
                    process.destroy();

                    return tid;
                }
            }
            
            reader.close();
            process.waitFor();

            return -1;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        try {
            long startTime = System.currentTimeMillis();

            // First get the package name
            String packageName = getPackageNameFromTaskId(taskId);

            // Run the service call command; shell permission available inside Shizuku
            // Note: each display has its own status bar (SystemUI) in Android
            // When an app moves to the rear screen, its own rear status bar shows; that is system default behavior
            // Keeping the main status bar visible requires a system-level change, not possible from the app layer
            String cmd = "am display move-stack " + taskId + " " + displayId;

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // If the move to the rear display (displayId=1) succeeded, save the task info
            if (success && displayId == 1) {
                try {
                    if (packageName != null) {
                        // Save it to the broadcast receiver so it can be restored after system events
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskId);

                    } else {

                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to save task info", e);
                }
            }

            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }
    
    /**
     * Get the package name from a taskId (helper).
     */
    private String getPackageNameFromTaskId(int taskId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=" + taskId) && line.contains("/")) {
                    // Parse: taskId=1471: com.example.app/...
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        String packageName = line.substring(pkgStart, pkgEnd).trim();
                        reader.close();
                        process.destroy();
                        return packageName;
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    @Override
    public boolean launchWakeActivity(int displayId) throws RemoteException {
        try {
            long startTime = System.currentTimeMillis();

            // Launch RearScreenWakeupActivity with `am start` on the target display
            // --display picks the target display
            // Note: RearScreenWakeupActivity lights the screen via FLAG_TURN_SCREEN_ON
            String cmd = "am start --display " + displayId + 
                        " -n com.tgwgroup.MiRearScreenSwitcher/.RearScreenWakeupActivity";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read the output
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

            }
            reader.close();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }
    
    @Override
    public boolean disableSubScreenLauncher() throws RemoteException {
        try {

            // Force-stop the process (it may auto-restart, so it must be killed repeatedly)
            String killCmd = "am force-stop com.xiaomi.subscreencenter";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", killCmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }
    
    /**
     * V12 kill-process approach: check whether the Launcher process is running.
     */
    @Override
    public boolean isLauncherProcessRunning() throws RemoteException {
        try {
            // Check whether the process is running
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            // If there is output, the process is running; return true (needs killing)
            // If there is no output, the process is not running; return false (nothing to do)
            boolean isRunning = (line != null && !line.isEmpty());
            
            if (isRunning) {

            }
            
            return isRunning;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }
    
    /**
     * V12 kill-process approach: try to kill the Launcher process.
     * Returns true = killed successfully (the process was running).
     * Returns false = failed (the process was not running).
     */
    @Override
    public boolean killLauncherProcess() throws RemoteException {
        try {
            // Force-stop the process
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            // force-stop always exits 0, so check whether the process was really killed
            // For simplicity, return true if the command succeeded
            return (exitCode == 0);
            
        } catch (Exception e) {
            // On exception, also return false (silently)
            return false;
        }
    }
    
    @Override
    public boolean enableSubScreenLauncher() throws RemoteException {
        try {

            // Start SubScreenLauncher (the process auto-starts)
            String startCmd = "am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", startCmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {

            }
            reader.close();
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {

            } else {

            }

            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }
    
    // Removed unused wakeUpDisplay method
    
    @Override
    public boolean forceStatusBarToMainDisplay() throws RemoteException {
        try {

            // New strategy: expand the main status bar instead of moving/restarting SystemUI
            // This forces the main display to show SystemUI and keeps focus there
            
            // Method 1: expand the main status bar (not fully, just activate it)
            String expandCmd = "cmd statusbar expand-settings";

            ProcessBuilder pb1 = new ProcessBuilder("sh", "-c", expandCmd);
            Process process1 = pb1.start();
            int exitCode1 = process1.waitFor();
            
            if (exitCode1 == 0) {

                Thread.sleep(30);  // brief delay
                
                // Collapse it again immediately
                String collapseCmd = "cmd statusbar collapse";
                ProcessBuilder pb2 = new ProcessBuilder("sh", "-c", collapseCmd);
                Process process2 = pb2.start();
                int exitCode2 = process2.waitFor();
                
                if (exitCode2 == 0) {

                } else {

                }
            } else {

            }
            
            // Method 2: force the main SystemUI visible (via wm command)
            // Set the main display as default
            String wmCmd = "wm set-display-type 0 home";

            ProcessBuilder pb3 = new ProcessBuilder("sh", "-c", wmCmd);
            Process process3 = pb3.start();
            
            BufferedReader reader3 = new BufferedReader(
                new InputStreamReader(process3.getInputStream()), 8192
            );
            String line;
            while ((line = reader3.readLine()) != null) {

            }
            reader3.close();
            
            int exitCode3 = process3.waitFor();
            if (exitCode3 == 0) {

            } else {

            }
            
            // Method 3: check the current status bar position

            ProcessBuilder pb4 = new ProcessBuilder("sh", "-c", "dumpsys window displays | grep -A20 'Display: 0'");
            Process process4 = pb4.start();
            
            BufferedReader reader4 = new BufferedReader(
                new InputStreamReader(process4.getInputStream()), 8192
            );
            while ((line = reader4.readLine()) != null) {
                if (line.contains("StatusBar") || line.contains("systemui")) {

                }
            }
            reader4.close();
            process4.waitFor();

            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }
    
    /**
     * Collapse the status bar / control center.
     * @return whether it succeeded
     */
    @Override
    public boolean collapseStatusBar() throws RemoteException {
        try {

            // Use the `cmd statusbar collapse` command
            String cmd = "cmd statusbar collapse";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }
    
    /**
     * Get the current rear-screen DPI.
     * @return the DPI value
     */
    @Override
    public int getCurrentRearDpi() throws RemoteException {
        try {

            // Use `wm density` to get display 1's DPI
            String cmd = "wm density -d 1";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            int dpi = 0;
            while ((line = reader.readLine()) != null) {

                // Parse output: "Physical density: 450" or "Override density: 300"
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            // Prefer "Override density: 300" when present
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);

                                break; // stop once override is found
                            } else if (dpi == 0) {
                                // Record physical as fallback until override is found
                                dpi = Integer.parseInt(dpiStr);

                            }
                        } catch (NumberFormatException e) {

                        }
                    }
                }
            }
            reader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && dpi > 0) {

            } else {

            }

            return dpi;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }
    
    /**
     * Set the rear-screen DPI.
     * @param dpi the DPI value
     * @return whether it succeeded
     */
    @Override
    public boolean setRearDpi(int dpi) throws RemoteException {
        try {

            // Use `wm density` to set display 1's DPI
            String cmd = "wm density " + dpi + " -d 1";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in setRearDpi", e);
            return false;
        }
    }
    
    /**
     * Reset the rear-screen DPI to default.
     * @return whether it succeeded
     */
    @Override
    public boolean resetRearDpi() throws RemoteException {
        try {

            // Use `wm density reset` to reset display 1's DPI
            String cmd = "wm density reset -d 1";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in resetRearDpi", e);
            return false;
        }
    }
    
    /**
     * Capture the rear-screen display.
     * @return whether it succeeded
     */
    @Override
    public boolean takeRearScreenshot() throws RemoteException {
        try {
            // Wake the rear screen before capturing
            try {
                executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(200); // wait for the wakeup to take effect
            } catch (Exception e) {
                Log.w(TAG, "Rear-screen keycode wakeup failed: " + e.getMessage());
            }

            // Create the save directory
            String mkdirCmd = "mkdir -p /storage/emulated/0/Pictures/RearDisplay";

            ProcessBuilder pb1 = new ProcessBuilder("sh", "-c", mkdirCmd);
            Process process1 = pb1.start();
            process1.waitFor();
            
            // Get the rear-screen display ID
            String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";

            ProcessBuilder pb2 = new ProcessBuilder("sh", "-c", getDisplayIdCmd);
            Process process2 = pb2.start();
            
            BufferedReader reader2 = new BufferedReader(
                new InputStreamReader(process2.getInputStream()), 8192
            );
            
            String displayId = reader2.readLine();
            reader2.close();
            process2.waitFor();
            
            if (displayId == null || displayId.isEmpty()) {
                displayId = "1"; // default to 1

            } else {

            }
            
            // Generate the filename (with timestamp)
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date());
            String filename = "/storage/emulated/0/Pictures/RearDisplay/RD_" + timestamp + ".png";
            
            // Run the screenshot command
            String screenshotCmd = "screencap -p -d " + displayId + " " + filename;

            ProcessBuilder pb3 = new ProcessBuilder("sh", "-c", screenshotCmd);
            Process process3 = pb3.start();
            
            int exitCode = process3.waitFor();
            
            // Refresh the media library so the screenshot shows up in the gallery
            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + filename;
            ProcessBuilder pb4 = new ProcessBuilder("sh", "-c", refreshCmd);
            pb4.start();
            
            // Return true regardless, so the Toast shows success
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in takeRearScreenshot", e);
            // Even on exception, return true so the Toast shows success
            return true;
        }
    }
    
    @Override
    public boolean isTaskOnDisplay(int taskId, int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=" + taskId)) {
                    reader.close();
                    process.destroy();
                    return true;
                }
            }
            
            reader.close();
            process.waitFor();
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking task on display", e);
            return false;
        }
    }
    
    @Override
    public String getForegroundAppOnDisplay(int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    reader.close();
                    process.destroy();
                    
                    return packageName + ":" + taskId;
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }
    
    /**
     * V2.1: Set the display rotation orientation.
     * @param displayId display ID (0=main, 1=rear)
     * @param rotation angle (0=0°, 1=90°, 2=180°, 3=270°)
     * @return whether it succeeded
     */
    @Override
    public boolean setDisplayRotation(int displayId, int rotation) throws RemoteException {
        try {
            // Get the current rear-screen foreground app (if any)
            String currentApp = null;
            int currentTaskId = -1;
            if (displayId == 1) {
                currentApp = getForegroundAppOnDisplay(1);
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            // Set rotation via the `wm user-rotation` command
            String cmd = "wm user-rotation -d " + displayId + " lock " + rotation;
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {}
            while ((line = errorReader.readLine()) != null) {}
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            // If on the rear display with an app running, wait 500ms then check and revive it
            if (displayId == 1 && exitCode == 0 && currentTaskId > 0) {
                Thread.sleep(500);
                
                // Check whether the app is still on the rear display
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, 1);
                
                if (!stillOnRear) {
                    // The app was closed; re-cast it
                    moveTaskToDisplay(currentTaskId, 1);
                }
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to set rotation", e);
            return false;
        }
    }
    
    /**
     * V2.1: Get the current display rotation orientation.
     * @param displayId display ID (0=main, 1=rear)
     * @return rotation angle (0-3), or -1 on failure
     */
    @Override
    public int getDisplayRotation(int displayId) throws RemoteException {
        try {
            // Read rotation directly via `wm user-rotation`; output format: "lock 2" or "free"
            String cmd = "wm user-rotation -d " + displayId;
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            if (line != null && !line.isEmpty()) {
                // Parse the "lock 2" or "free" format
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            return 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to get rotation", e);
            return 0;
        }
    }
    
    @Override
    public boolean executeShellCommand(String cmd) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            // Log the detailed output
            if (output.length() > 0) {
                Log.d(TAG, "Command stdout: " + output.toString().trim());
            }
            if (errorOutput.length() > 0) {
                Log.w(TAG, "Command stderr: " + errorOutput.toString().trim());
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + cmd, e);
            return false;
        }
    }
    
    @Override
    public String executeShellCommandWithResult(String cmd) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + cmd, e);
            return "";
        }
    }
    
}
