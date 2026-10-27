package vendor.autochips.hardware.iap.V1_0;

import android.os.IHwBinder;
import android.os.RemoteException;

/**
 * COMPILE-TIME ONLY STUB.
 *
 * Exact signatures copied from the decompiled device boot-classpath class
 * <internal notes>
 * (originally /system/framework/autochips-framework.jar).
 *
 * Do not feed this to d8: the real class is loaded from the device boot
 * classpath at runtime.  Only descriptors our code links against are declared.
 */
public interface IIAPService {

    String kInterfaceName = "vendor.autochips.hardware.iap@1.0::IIAPService";

    IIAPCarplay createCarplay(IIAPClient client) throws RemoteException;

    IIAP createTransport(IIAPTransport trans) throws RemoteException;

    int destroyCarplay(IIAPCarplay carplay) throws RemoteException;

    int destroyTransport(IIAP iap) throws RemoteException;

    boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) throws RemoteException;

    boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) throws RemoteException;

    static IIAPService getService() throws RemoteException {
        // compile-time only; the device implementation returned by
        // HwBinder.getService(kInterfaceName, "default") is used at runtime.
        return null;
    }

    static IIAPService getService(String serviceName) throws RemoteException {
        return null;
    }
}
