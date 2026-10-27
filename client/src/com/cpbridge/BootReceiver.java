package com.cpbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the Phase B service after boot.
 *
 * The wireless-CarPlay hotspot window is short, so after a reboot the bridge
 * MUST come up on its own and dial immediately (persist.cpbridge.peer_mac
 * survives the reboot and the dialer reads it live).  It restarts only if the
 * "enabled" preference is set: BridgeService.onCreate sets it, and its
 * shutdown() clears it whenever the service stops (STOP or onDestroy).  A
 * power loss or process kill leaves it set.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Cplog.init(context);
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        boolean enabled = context.getSharedPreferences("cpbridge", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        Cplog.i(BridgeConfig.TAG_SVC, "BOOT_COMPLETED: bridge enabled=" + enabled);
        if (enabled) {
            try {
                context.startForegroundService(new Intent(context, BridgeService.class));
            } catch (Throwable t) {
                Cplog.e(BridgeConfig.TAG_SVC, "startForegroundService after boot failed", t);
            }
        }
    }
}
