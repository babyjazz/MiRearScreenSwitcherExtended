package com.tgwgroup.MiRearScreenSwitcher;

interface ITaskService {
    void destroy() = 16777114;  // Shizuku required
    
    /**
     * Get the package name and taskId of the currently foreground app
     * @return "package:taskId" format
     */
    String getCurrentForegroundApp() = 1;
    
    /**
     * Get the taskId for a package name
     * @param packageName package name
     * @return taskId, or -1 on failure
     */
    int getTaskIdByPackage(String packageName) = 2;
    
    /**
     * Move a task to the specified display
     * @param taskId task ID
     * @param displayId display ID (0=main, 1=rear)
     * @return whether it succeeded
     */
    boolean moveTaskToDisplay(int taskId, int displayId) = 3;
    
    /**
     * Launch an Activity on the specified display (attempts to keep the screen on)
     * Note: active screen-waking was removed; only sets FLAG_KEEP_SCREEN_ON
     * @param displayId display ID (0=main, 1=rear)
     * @return whether it succeeded
     */
    boolean launchWakeActivity(int displayId) = 4;
    
    /**
     * Force SystemUI (status bar) to stay on the main display
     * @return whether it succeeded
     */
    boolean forceStatusBarToMainDisplay() = 5;
    
    /**
     * Disable the Xiaomi rear-screen Launcher (prevents it crowding out apps)
     * @return whether it succeeded
     */
    boolean disableSubScreenLauncher() = 6;
    
    /**
     * Enable the Xiaomi rear-screen Launcher (restores system function)
     * @return whether it succeeded
     */
    boolean enableSubScreenLauncher() = 7;
    
    /**
     * V9: Check whether the Launcher process is running
     * @return true=process running, false=process absent
     */
    boolean isLauncherProcessRunning() = 8;
    
    /**
     * V9: Kill the Launcher process (lightweight operation)
     * @return whether it succeeded
     */
    boolean killLauncherProcess() = 9;
    
    // Removed unused wakeUpDisplay method declaration
    
    /**
     * V14.4: Collapse the status bar / control center
     * @return whether it succeeded
     */
    boolean collapseStatusBar() = 11;
    
    /**
     * V15: Get the current rear-screen DPI
     * @return DPI value
     */
    int getCurrentRearDpi() = 12;
    
    /**
     * V15: Set the rear-screen DPI
     * @param dpi DPI value
     * @return whether it succeeded
     */
    boolean setRearDpi(int dpi) = 13;
    
    /**
     * V15: Reset the rear-screen DPI to default
     * @return whether it succeeded
     */
    boolean resetRearDpi() = 14;
    
    /**
     * V15: Capture the rear-screen display
     * @return whether it succeeded
     */
    boolean takeRearScreenshot() = 15;
    
    /**
     * V15.1: Check whether a task runs on the specified display
     * @param taskId task ID
     * @param displayId display ID (0=main, 1=rear)
     * @return true=task runs on the display, false=task absent or on another display
     */
    boolean isTaskOnDisplay(int taskId, int displayId) = 16;
    
    /**
     * V15.2: Get the foreground app on the specified display
     * @param displayId display ID (0=main, 1=rear)
     * @return "package:taskId" format, or null on failure
     */
    String getForegroundAppOnDisplay(int displayId) = 17;
    
    /**
     * V2.1: Set the display rotation orientation
     * @param displayId display ID (0=main, 1=rear)
     * @param rotation rotation angle (0=0°, 1=90°, 2=180°, 3=270°)
     * @return whether it succeeded
     */
    boolean setDisplayRotation(int displayId, int rotation) = 18;
    
    /**
     * V2.1: Get the current display rotation orientation
     * @param displayId display ID (0=main, 1=rear)
     * @return rotation angle (0-3), or -1 on failure
     */
    int getDisplayRotation(int displayId) = 19;
    
    /**
     * V2.3: Execute a shell command
     * @param cmd command to run
     * @return whether it succeeded
     */
    boolean executeShellCommand(String cmd) = 20;
    String executeShellCommandWithResult(String cmd) = 21;
}
