package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;

/**
 * Temporary diagnostic breadcrumbs (AGENTS.md: native Log.d often does not
 * surface; write to a file instead). Read with:
 * adb shell run-as com.tgwgroup.MiRearScreenSwitcher cat files/rear_debug.log
 */
public final class Breadcrumb {
    private Breadcrumb() {}

    public static void log(Context ctx, String tag, String msg) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "rear_debug.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.append(String.valueOf(System.currentTimeMillis()))
                .append(" [").append(tag).append("] ").append(msg).append("\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
