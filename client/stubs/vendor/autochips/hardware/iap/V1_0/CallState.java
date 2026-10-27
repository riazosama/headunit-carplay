package vendor.autochips.hardware.iap.V1_0;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 * Callback payload types are referenced only in method signatures; the real
 * HIDL structs live on the device boot classpath.
 */
public final class CallState {
    public String remoteID = new String();
    public String displayName = new String();
    public byte status;
    public byte direction;
    public String callUUID = new String();
    public long addrBookID;
    public String label = new String();
    public byte service;
    public byte isConferenced;
    public int conferenceGroup;
    public int disconnectReason;
    public long startTimeStamp;
}
