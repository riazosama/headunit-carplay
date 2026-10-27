package vendor.autochips.hardware.iap.V1_0;

import android.os.RemoteException;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 *
 * Subset of the decompiled IIAPCarplay that the bridge calls.
 * Real interface extends IIAPCommon too; irrelevant for linkage.
 */
public interface IIAPCarplay {

    String kInterfaceName = "vendor.autochips.hardware.iap@1.0::IIAPCarplay";

    int setDeviceConfigInfo(int type, String info, boolean enable) throws RemoteException;

    int setWiFiConfigurationInfo(IAPDev dev, WiFiConfig config) throws RemoteException;

    int selectTransport(String id) throws RemoteException;
}
