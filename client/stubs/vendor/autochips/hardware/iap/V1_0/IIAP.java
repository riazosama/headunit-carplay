package vendor.autochips.hardware.iap.V1_0;

import android.os.RemoteException;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 */
public interface IIAP {

    String kInterfaceName = "vendor.autochips.hardware.iap@1.0::IIAP";

    int connectTransport(IAPDev dev, boolean recovery) throws RemoteException;

    int disconnectTransport(IAPDev dev) throws RemoteException;

    int setEapReady(IAPDev dev, boolean ready) throws RemoteException;

    int setInterfaceNumber(byte interfaceNumber) throws RemoteException;
}
