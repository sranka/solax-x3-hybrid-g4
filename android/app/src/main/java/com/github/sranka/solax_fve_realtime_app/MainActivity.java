package com.github.sranka.solax_fve_realtime_app;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(ModbusTcpPlugin.class);
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
    }
}
