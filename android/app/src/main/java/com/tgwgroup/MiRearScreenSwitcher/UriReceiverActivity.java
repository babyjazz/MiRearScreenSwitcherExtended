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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * V2.6: URI receiver Activity.
 * Fully transparent; only forwards the URI to UriCommandService, then finishes immediately.
 * Shows no UI, avoiding a jump to the MRSS page.
 */
public class UriReceiverActivity extends Activity {
    private static final String TAG = "UriReceiverActivity";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Set no layout; stay transparent
        
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null && "mrss".equals(uri.getScheme())) {
                Log.d(TAG, "🔗 URI received: " + uri.toString());
                
                // Forward to UriCommandService
                Intent serviceIntent = new Intent(this, UriCommandService.class);
                serviceIntent.setData(uri);
                startService(serviceIntent);
                
                Log.d(TAG, "✓ Forwarded to UriCommandService");
            }
        }
        
        // Finish immediately; show no UI
        finish();
    }
    
    @Override
    public void finish() {
        super.finish();
        // Disable transition animation; fully transparent
        overridePendingTransition(0, 0);
    }
}

