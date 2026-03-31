package com.github.sranka.solax_fve_live_app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(ModbusTcpPlugin.class);
        registerPlugin(NetworkScannerPlugin.class);
        registerPlugin(SettingsTransferPlugin.class);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        // Android TV: let JS close modals on back press; exit app only if no modal is open
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getBridge() == null || getBridge().getWebView() == null) {
                    finish();
                    return;
                }
                getBridge().getWebView().evaluateJavascript(
                    "(function(){if(typeof _focusStack!=='undefined'&&_focusStack.length>0){" +
                    "var e=new KeyboardEvent('keydown',{key:'Escape',bubbles:true});document.dispatchEvent(e);return true;" +
                    "}return false;})()",
                    result -> {
                        if (!"true".equals(result)) {
                            finish();
                        }
                    }
                );
            }
        });

        // Handle deep link on initial launch
        Uri launchUri = getIntent().getData();
        if (launchUri != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                dispatchDeepLink(launchUri);
            }, 500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = intent.getData();
        if (uri != null) {
            dispatchDeepLink(uri);
        }
    }

    private void dispatchDeepLink(Uri uri) {
        if (getBridge() != null && getBridge().getWebView() != null) {
            String escaped = uri.toString().replace("\\", "\\\\").replace("'", "\\'");
            getBridge().getWebView().evaluateJavascript(
                    "window._handleDeepLink&&window._handleDeepLink('" + escaped + "')", null);
        }
    }
}
