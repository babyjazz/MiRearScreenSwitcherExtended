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

import android.graphics.Rect;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper for rear-screen display info.
 * Reads resolution, DPI, and cutout info via `dumpsys display`.
 */
public class RearDisplayHelper {
    private static final String TAG = "RearDisplayHelper";
    
    /**
     * Rear-screen info data class
     */
    public static class RearDisplayInfo {
        public int width;           // screen width (pixels)
        public int height;          // screen height (pixels)
        public int densityDpi;      // DPI
        public Rect cutout;         // cutout area (insets format)        
        public RearDisplayInfo() {
            // Defaults (Xiaomi 14 Ultra rear screen)            width = 1200;
            height = 2200;
            densityDpi = 440;
            cutout = new Rect(0, 0, 0, 0);
        }
        
        @Override
        public String toString() {
            return String.format("RearDisplayInfo{width=%d, height=%d, dpi=%d, cutout=%s}",
                width, height, densityDpi, cutout.toString());
        }
        
        /**
         * Whether a cutout exists
         */
        public boolean hasCutout() {
            return cutout.left > 0 || cutout.top > 0 || cutout.right > 0 || cutout.bottom > 0;
        }
    }
    
    /**
     * Get rear-screen info (via TaskService).
     */
    public static RearDisplayInfo getRearDisplayInfo(ITaskService taskService) {
        RearDisplayInfo info = new RearDisplayInfo();
        
        if (taskService == null) {
            Log.w(TAG, "⚠️ TaskService is null, using default rear-screen info");
            return info;
        }
        
        try {
            // Run the `dumpsys display` command
            String result = taskService.executeShellCommandWithResult("dumpsys display");
            if (result == null || result.isEmpty()) {
                Log.w(TAG, "⚠️ dumpsys display returned empty, using default rear-screen info");
                return info;
            }
            
            // 🔍 Detailed log: full dumpsys display output (first 2000 chars)
            String preview = result.length() > 2000 ? result.substring(0, 2000) : result;
            Log.d(TAG, "📋 Full dumpsys display output (first 2000 chars):\n" + preview);
            Log.d(TAG, "📏 dumpsys display total length: " + result.length() + " chars");
            
            // Parse rear-screen info (Display 1)
            parseRearDisplayInfo(result, info);
            
            Log.d(TAG, "✓ Rear-screen info: " + info.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to get rear-screen info, using defaults", e);
        }
        
        return info;
    }
    
    /**
     * Parse `dumpsys display` output.
     */
    private static void parseRearDisplayInfo(String dumpsys, RearDisplayInfo info) {
        try {
            // Method 1: parse from mViewports (most accurate)
            Pattern viewportPattern = Pattern.compile(
                "displayId=1[^}]*deviceWidth=(\\d+),\\s*deviceHeight=(\\d+)"
            );
            Matcher viewportMatcher = viewportPattern.matcher(dumpsys);
            if (viewportMatcher.find()) {
                info.width = Integer.parseInt(viewportMatcher.group(1));
                info.height = Integer.parseInt(viewportMatcher.group(2));
                Log.d(TAG, String.format("✓ Parsed resolution from mViewports: %dx%d", info.width, info.height));
            }
            
            // Method 2: find Display 1's DisplayDeviceInfo block (contains cutout)
            // Search for uniqueId="local:4630946949513469332" (Display 1's unique identifier)
            // Alternatively, search DisplayDeviceInfo blocks containing "904 x 572"
            int display1DeviceStart = -1;
            
            // First try to find the DisplayViewport with displayId=1 to get its uniqueId
            Pattern uniqueIdPattern = Pattern.compile("displayId=1[^}]*uniqueId='([^']+)'");
            Matcher uniqueIdMatcher = uniqueIdPattern.matcher(dumpsys);
            String display1UniqueId = null;
            if (uniqueIdMatcher.find()) {
                display1UniqueId = uniqueIdMatcher.group(1);
                Log.d(TAG, "🔍 Display 1 uniqueId: " + display1UniqueId);
            }
            
            // Locate Display 1's DisplayDeviceInfo via uniqueId or resolution
            int searchPos = 0;
            while (true) {
                int idx = dumpsys.indexOf("DisplayDeviceInfo", searchPos);
                if (idx == -1) break;
                
                // Check the next 2000 chars for a match
                int checkEnd = Math.min(idx + 2000, dumpsys.length());
                String snippet = dumpsys.substring(idx, checkEnd);
                
                boolean isDisplay1 = false;
                if (display1UniqueId != null && snippet.contains(display1UniqueId)) {
                    isDisplay1 = true;
                } else if (snippet.contains(info.width + " x " + info.height)) {
                    // Match by the already-parsed resolution (904 x 572)
                    isDisplay1 = true;
                }
                
                if (isDisplay1) {
                    display1DeviceStart = idx;
                    break;
                }
                searchPos = idx + 17; // "DisplayDeviceInfo".length()
            }
            
            String display1Block = "";
            if (display1DeviceStart != -1) {
                // The next "DisplayDeviceInfo" marks the end
                int nextBlockIdx = dumpsys.indexOf("DisplayDeviceInfo", display1DeviceStart + 17);
                
                display1Block = nextBlockIdx > 0 
                    ? dumpsys.substring(display1DeviceStart, nextBlockIdx)
                    : dumpsys.substring(display1DeviceStart, Math.min(display1DeviceStart + 3000, dumpsys.length()));
                
                Log.d(TAG, "🔍 Display 1 DisplayDeviceInfo block length: " + display1Block.length() + " chars");
                
                // Output first 600 chars for debugging
                String preview = display1Block.length() > 600 
                    ? display1Block.substring(0, 600) 
                    : display1Block;
                Log.d(TAG, "📋 Display 1 DisplayDeviceInfo block (first 600 chars):\n" + preview);
            } else {
                Log.w(TAG, "⚠️ Display 1 DisplayDeviceInfo block not found");
                display1Block = ""; // don't fall back to the whole output, avoid matching main-screen data
            }
            
            // Parse DPI (from the DisplayDeviceInfo block)
            // Format: density 450
            if (!display1Block.isEmpty()) {
                Pattern dpiPattern = Pattern.compile("density\\s+(\\d+)");
                Matcher dpiMatcher = dpiPattern.matcher(display1Block);
                if (dpiMatcher.find()) {
                    info.densityDpi = Integer.parseInt(dpiMatcher.group(1));
                    Log.d(TAG, "✓ Parsed DPI: " + info.densityDpi);
                }
            }
            
            // Parse cutout (MIUI-specific format)
            // Format: DisplayCutout{insets=Rect(296, 0 - 0, 0)
            // Note: MIUI uses "top - right" instead of "top, right"
            info.cutout = parseCutoutFromDumpsys(display1Block);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing rear-screen info", e);
        }
    }
    
    /**
     * Parse cutout info (MIUI-specific format).
     */
    private static Rect parseCutoutFromDumpsys(String display1Block) {
        Rect cutout = new Rect(0, 0, 0, 0);
        
        try {
            // 🔍 Find all lines containing "Cutout" or "cutout"
            String[] lines = display1Block.split("\n");
            StringBuilder cutoutLines = new StringBuilder("📋 All Cutout-related lines:\n");
            boolean foundCutout = false;
            for (String line : lines) {
                if (line.toLowerCase().contains("cutout")) {
                    cutoutLines.append("  ").append(line.trim()).append("\n");
                    foundCutout = true;
                }
            }
            if (foundCutout) {
                Log.d(TAG, cutoutLines.toString());
            } else {
                Log.d(TAG, "ℹ️ No line containing 'Cutout' found in the Display 1 block");
            }
            
            // MIUI format: Rect(296, 0 - 0, 0)
            // Standard format: Rect(left, top, right, bottom)
            // MIUI format: Rect(left, top - right, bottom)
            
            // Try the MIUI format first (has a dash)
            Pattern miuiPattern = Pattern.compile("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)\\)");
            Matcher miuiMatcher = miuiPattern.matcher(display1Block);
            
            if (miuiMatcher.find()) {
                cutout.left = Integer.parseInt(miuiMatcher.group(1));
                cutout.top = Integer.parseInt(miuiMatcher.group(2));
                cutout.right = Integer.parseInt(miuiMatcher.group(3));
                cutout.bottom = Integer.parseInt(miuiMatcher.group(4));
                Log.d(TAG, String.format("✓ Parsed cutout (MIUI format): left=%d, top=%d, right=%d, bottom=%d",
                    cutout.left, cutout.top, cutout.right, cutout.bottom));
                return cutout;
            }
            
            // Then try the standard format (no dash)
            Pattern standardPattern = Pattern.compile("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
            Matcher standardMatcher = standardPattern.matcher(display1Block);
            
            if (standardMatcher.find()) {
                cutout.left = Integer.parseInt(standardMatcher.group(1));
                cutout.top = Integer.parseInt(standardMatcher.group(2));
                cutout.right = Integer.parseInt(standardMatcher.group(3));
                cutout.bottom = Integer.parseInt(standardMatcher.group(4));
                Log.d(TAG, String.format("✓ Parsed cutout (standard format): left=%d, top=%d, right=%d, bottom=%d",
                    cutout.left, cutout.top, cutout.right, cutout.bottom));
                return cutout;
            }
            
            // Try a looser pattern (any Cutout containing Rect)
            Pattern loosePattern = Pattern.compile("cutout.*?Rect\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
            Matcher looseMatcher = loosePattern.matcher(display1Block);
            if (looseMatcher.find()) {
                String rectContent = looseMatcher.group(1);
                Log.d(TAG, "🔍 Found cutout but format unrecognized, Rect content: " + rectContent);
            }
            
            Log.d(TAG, "ℹ️ No recognizable cutout found, using default (0,0,0,0)");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing cutout", e);
        }
        
        return cutout;
    }
}

