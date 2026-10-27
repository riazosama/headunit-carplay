package vendor.autochips.hardware.iap.V1_0;

/** COMPILE-TIME ONLY STUB (see IIAPService.java in this directory). */
public final class ConnectionStatus {
    public static final int CONNECTED = 1;
    public static final int CONNECTING = 0;
    public static final int CONNECT_FAIL = 255;
    public static final int DISCONNECTED = 3;
    public static final int DISCONNECTING = 2;

    public static final String toString(int o) {
        switch (o) {
            case CONNECTING: return "CONNECTING";
            case CONNECTED: return "CONNECTED";
            case DISCONNECTING: return "DISCONNECTING";
            case DISCONNECTED: return "DISCONNECTED";
            case CONNECT_FAIL: return "CONNECT_FAIL";
            default: return "UNKNOWN(" + o + ")";
        }
    }
}
