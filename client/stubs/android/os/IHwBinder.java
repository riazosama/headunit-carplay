package android.os;

/**
 * COMPILE-TIME ONLY STUB.
 *
 * Mirrors the hidden framework class android.os.IHwBinder from the unit's
 * boot classpath (extracted & decompiled at
 * <internal notes>).
 *
 * This file is compiled into a stub jar that is used ONLY as a javac/d8
 * classpath.  It must NEVER be fed to d8 as a program input: the real
 * implementation is on the device boot classpath
 * (/system/framework/autochips-framework.jar, which is a dex).  Only the
 * member descriptors that our code links against are declared here.
 */
public interface IHwBinder {

    public interface DeathRecipient {
        void serviceDied(long cookie);
    }

    boolean linkToDeath(DeathRecipient recipient, long cookie) throws RemoteException;

    boolean unlinkToDeath(DeathRecipient recipient);
}
