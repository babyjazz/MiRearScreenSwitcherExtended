/*
 * Phase 1 THROWAWAY host for the Option A overlay prototype.
 * Keeps the display-1 overlay alive for locked/60s/touch tests.
 * Delete after device checks.
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class RearOverlayPrototypeService extends Service {
    private static final String TAG = "RearOverlayPrototypeService";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (RearOverlayPrototype.isShowing()) {
            RearOverlayPrototype.hide();
            Log.d(TAG, "overlay toggled off");
            return START_NOT_STICKY;
        }
        boolean ok = RearOverlayPrototype.show(this);
        Log.d(TAG, "overlay show=" + ok);
        return ok ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        RearOverlayPrototype.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
