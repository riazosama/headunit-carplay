package vendor.autochips.hardware.iap.V1_0;

import android.os.HwBinder;
import android.os.IHwBinder;
import android.os.RemoteException;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 *
 * All seven abstract callbacks are declared so our subclass implements the
 * same method set the device's real IIAPClient.Stub leaves abstract.  The
 * IBase plumbing is concrete in the device Stub and is not redeclared here.
 */
public interface IIAPClient {

    String kInterfaceName = "vendor.autochips.hardware.iap@1.0::IIAPClient";

    IHwBinder asBinder();

    void onCallStateUpdate(IAPDev dev, CallState callState) throws RemoteException;

    void onConnectStatusChanged(IAPDev dev, int status) throws RemoteException;

    void onDatabaseInfoChanged(IAPDev dev, DatabaseInfo info) throws RemoteException;

    void onEapReady(IAPDev dev, boolean ready) throws RemoteException;

    void onLocationStart(IAPDev dev, LocationEvent event) throws RemoteException;

    void onPlaybackStatusChanged(IAPDev dev, PlaybackStatus status) throws RemoteException;

    void onSamplerateChanged(IAPDev dev, int sampleRate) throws RemoteException;

    static IIAPClient asInterface(IHwBinder binder) {
        // compile-time only; device implementation is used at runtime.
        return null;
    }

    public abstract static class Stub extends HwBinder implements IIAPClient {

        @Override
        public IHwBinder asBinder() {
            return this;
        }
    }
}
