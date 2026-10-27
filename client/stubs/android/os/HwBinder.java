package android.os;

/**
 * COMPILE-TIME ONLY STUB (see IHwBinder.java in this directory).
 *
 * Mirrors the parts of the hidden android.os.HwBinder that matter for type
 * identity: it is the superclass of every generated HIDL *Stub class, so our
 * client/transport stubs must extend it and be assignable to IHwBinder.
 *
 * The device's real HwBinder (in autochips-framework.jar) provides
 * onTransact/transact/registerService etc.; we deliberately do not call them.
 */
public abstract class HwBinder implements IHwBinder {

    public HwBinder() {
    }

    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie)
            throws RemoteException {
        return true;
    }

    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return true;
    }
}
