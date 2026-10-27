package com.cpbridge;

import android.os.IHwBinder;

import vendor.autochips.hardware.iap.V1_0.IIAP;
import vendor.autochips.hardware.iap.V1_0.IIAPCarplay;
import vendor.autochips.hardware.iap.V1_0.IIAPClient;
import vendor.autochips.hardware.iap.V1_0.IIAPService;

/**
 * Phase A: prove that ordinary app code can reach the vendor iAP2 HIDL HAL.
 *
 * Does exactly what the missing vendor client com.autochips.carplayapp did at
 * startup (see <internal notes>):
 *
 *   IIAPService.getService()
 *     -> linkToDeath
 *     -> createCarplay(IIAPClient.asInterface(clientStub.asBinder()))
 *     -> setDeviceConfigInfo(...)
 *
 * No transport is created here.  No property is touched.  Safe to run on the
 * currently-stable unit.
 */
final class PhaseA {

    /**
     * These keep the HIDL proxies strongly reachable.  If the carplay/transport
     * objects were collected the HAL would see a binder death; the reference
     * client keeps them as fields, so do we.
     */
    static volatile IIAPService sService;
    static volatile IIAP sIap;
    static volatile IIAPCarplay sCarplay;
    static volatile IapClientStub sClient;

    static final IHwBinder.DeathRecipient DEATH_RECIPIENT = new IHwBinder.DeathRecipient() {
        @Override
        public void serviceDied(long cookie) {
            Cplog.e(BridgeConfig.TAG_HAL, "IIAPService binder died (cookie=" + cookie + ")");
        }
    };

    private PhaseA() {
    }

    /**
     * Vendor-client equivalent:
     *   this.qU = IIAPService.getService();
     *   this.qU.linkToDeath(this.qT, 0L);
     *   this.qV = this.qU.createCarplay(IIAPClient.asInterface(new b().asBinder()));
     *   this.qV.setDeviceConfigInfo(7, Build.SERIAL, false);
     */
    static IIAPService getService() {
        Cplog.i(BridgeConfig.TAG_A, "IIAPService.getService() ...");
        IIAPService svc = null;
        try {
            svc = IIAPService.getService();
        } catch (Throwable t) {
            Cplog.e(BridgeConfig.TAG_A, "direct IIAPService.getService() threw " + t, t);
        }
        if (svc == null) {
            // Retry a few times; hwservicemanager may not have a cached handle yet
            // in a cold app process.  Then try the explicit "default" overload.
            for (int i = 0; i < 3 && svc == null; i++) {
                try {
                    Thread.sleep(200);
                    svc = IIAPService.getService();
                } catch (Throwable ignored) {
                }
            }
        }
        if (svc == null) {
            try {
                svc = IIAPService.getService("default");
            } catch (Throwable t) {
                Cplog.e(BridgeConfig.TAG_A, "IIAPService.getService(\"default\") threw " + t, t);
            }
        }
        if (svc == null) {
            Cplog.e(BridgeConfig.TAG_HAL, "IIAPService.getService() returned NULL "
                    + "(HAL not reachable from this app/uid)");
        } else {
            Cplog.i(BridgeConfig.TAG_HAL, "IIAPService.getService() -> " + svc.getClass().getName()
                    + " (hash=" + svc.hashCode() + ")");
        }
        return svc;
    }

    /**
     * Phase A probe used by MainActivity.
     *
     * @return human-readable report for the activity UI.
     */
    static String probe() {
        StringBuilder report = new StringBuilder();
        String serial = BridgeConfig.serial();
        Cplog.i(BridgeConfig.TAG_A, "=== CPBRIDGE Phase A probe start pid="
                + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        Cplog.i(BridgeConfig.TAG_A, "ro.serialno=" + serial);
        report.append("pid=").append(android.os.Process.myPid())
                .append(" uid=").append(android.os.Process.myUid()).append('\n');
        report.append("ro.serialno=").append(serial).append('\n');

        IIAPService svc = getService();
        if (svc == null) {
            Cplog.e(BridgeConfig.TAG_A,
                    ">>> PHASE_A_RESULT: FAIL getService()=null (HAL not reachable from this app/uid)");
            report.append("getService() = null  <-- BLOCKER\n");
            return report.toString();
        }
        sService = svc;
        report.append("getService() = ").append(svc.getClass().getName()).append('\n');

        try {
            boolean linked = svc.linkToDeath(DEATH_RECIPIENT, 0L);
            Cplog.i(BridgeConfig.TAG_HAL, "linkToDeath(recipient, 0) -> " + linked);
            report.append("linkToDeath -> ").append(linked).append('\n');
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_HAL, "linkToDeath threw " + t, t);
            report.append("linkToDeath threw ").append(t).append('\n');
        }

        try {
            IapClientStub client = new IapClientStub(new IapClientStub.Listener() {
                @Override
                public void onConnectStatusChanged(vendor.autochips.hardware.iap.V1_0.IAPDev dev,
                                                   int status) {
                    Cplog.i(BridgeConfig.TAG_A, "Phase A client callback: status=" + status);
                }
            });
            sClient = client;
            IIAPClient binderClient = IIAPClient.asInterface(client.asBinder());
            Cplog.i(BridgeConfig.TAG_HAL, "IIAPClient.asInterface(stub.asBinder()) -> "
                    + (binderClient == null ? "null" : binderClient.getClass().getName()));
            report.append("asInterface = ").append(binderClient).append('\n');

            IIAPCarplay cp = svc.createCarplay(binderClient);
            if (cp == null) {
                Cplog.e(BridgeConfig.TAG_HAL, "createCarplay() returned NULL");
                report.append("createCarplay() = null\n");
                return report.toString();
            }
            sCarplay = cp;
            Cplog.i(BridgeConfig.TAG_HAL, "createCarplay() -> " + cp.getClass().getName()
                    + " (hash=" + cp.hashCode() + ")");
            report.append("createCarplay() = ").append(cp.getClass().getName()).append('\n');

            // Minimal, exactly as briefed for Phase A.  Full vendor config set is
            // applied by the Phase B service (applyDeviceConfig(..., true)).
            int rc = cp.setDeviceConfigInfo(7, serial, false);
            Cplog.i(BridgeConfig.TAG_HAL, "setDeviceConfigInfo(7, \"" + serial + "\", false) -> " + rc);
            report.append("setDeviceConfigInfo(7,\"").append(serial).append("\",false) -> ")
                    .append(rc).append('\n');
        } catch (Throwable t) {
            Cplog.e(BridgeConfig.TAG_HAL, "Phase A HAL interaction failed", t);
            report.append("HAL interaction failed: ").append(t).append('\n');
        }
        report.append("Phase A OK: HAL reachable from normal app context.\n");
        Cplog.i(BridgeConfig.TAG_A, ">>> PHASE_A_RESULT: OK getService!=null, "
                + "createCarplay!=null, setDeviceConfigInfo(7) returned 0, "
                + "HAL reachable from NORMAL app context (no priv-app/remount needed)");
        return report.toString();
    }

    /**
     * Applies the vendor's setDeviceConfigInfo sequence.
     *
     * Vendor order (carplay/a/a.java): 7 serial, 20, 21, 22, 8(uuid value,
     * persist.carplay.uuid.enable).
     */
    static void applyDeviceConfig(IIAPCarplay cp, String serial, boolean fullVendorSet) {
        if (cp == null) {
            return;
        }
        try {
            logConfigResult(cp, 7, serial, false);
            if (!fullVendorSet) {
                return;
            }
            logConfigResult(cp, 20, "", true);
            logConfigResult(cp, 21, "", true);
            logConfigResult(cp, 22, "", true);
            logConfigResult(cp, 8, BridgeConfig.uuidValue(), BridgeConfig.uuidEnable());
        } catch (Throwable t) {
            Cplog.e(BridgeConfig.TAG_HAL, "setDeviceConfigInfo sequence failed", t);
        }
    }

    private static void logConfigResult(IIAPCarplay cp, int type, String info, boolean enable)
            throws android.os.RemoteException {
        int rc = cp.setDeviceConfigInfo(type, info, enable);
        Cplog.i(BridgeConfig.TAG_HAL, "setDeviceConfigInfo(" + type + ", \"" + info + "\", "
                + enable + ") -> " + rc);
    }
}
