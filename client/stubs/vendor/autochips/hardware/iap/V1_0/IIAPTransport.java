package vendor.autochips.hardware.iap.V1_0;

import android.os.HwBinder;
import android.os.IHwBinder;
import android.os.RemoteException;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 *
 * read()/write() are the CLIENT-IMPLEMENTED side of the HAL: the device's
 * IIAPService.createTransport(IIAPTransport) keeps this binder and calls
 * read(1024, cb) / write(buffer) on it.  read() is a synchronous HIDL method
 * whose reply is sent later by cb.onValues(status, buffer) -- the generated
 * device Stub.onTransact() intentionally sends nothing after read() returns,
 * so our implementation may park the callback and answer from another thread.
 */
public interface IIAPTransport {

    String kInterfaceName = "vendor.autochips.hardware.iap@1.0::IIAPTransport";

    public interface readCallback {
        void onValues(int status, Buffer buffer);
    }

    IHwBinder asBinder();

    void read(long len, readCallback callback) throws RemoteException;

    int write(Buffer buffer) throws RemoteException;

    static IIAPTransport asInterface(IHwBinder binder) {
        return null;
    }

    public abstract static class Stub extends HwBinder implements IIAPTransport {

        @Override
        public IHwBinder asBinder() {
            return this;
        }
    }
}
