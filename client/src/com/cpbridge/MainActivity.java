package com.cpbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Phase A entry point.
 *
 * On launch: probes the vendor iAP2 HIDL HAL (IIAPService.getService ->
 * createCarplay -> setDeviceConfigInfo(7, ro.serialno, false)) and shows /
 * logs the result.  It never starts a transport and never touches properties.
 *
 * Optional: launching with --ez startBridge true additionally starts
 * BridgeService (Phase B, wireless).  Phase B is normally started explicitly:
 *   adb shell am start-foreground-service -n com.cpbridge/.BridgeService
 */
public final class MainActivity extends Activity {

    private TextView mText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Cplog.init(this);
        mText = new TextView(this);
        mText.setTextSize(16f);
        mText.setPadding(48, 48, 48, 48);
        mText.setGravity(Gravity.START);
        mText.setText("CPBRIDGE Phase A probe running...\n");
        setContentView(mText);

        final boolean startBridge = getIntent() != null
                && getIntent().getBooleanExtra("startBridge", false);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = PhaseA.probe();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mText.setText(report);
                    }
                });
                if (startBridge) {
                    try {
                        startForegroundService(new Intent(MainActivity.this, BridgeService.class));
                    } catch (Throwable e) {
                        Cplog.e(BridgeConfig.TAG_SVC,
                                "startForegroundService(BridgeService) failed", e);
                    }
                }
            }
        }, "cpbridge-phasea");
        t.setDaemon(true);
        t.start();
    }
}
