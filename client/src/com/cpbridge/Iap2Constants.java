package com.cpbridge;

/**
 * Protocol constants for the from-scratch iAP2-over-Bluetooth bootstrap.
 *
 * The vendor HAL's client-transport path is dead on this firmware, so the
 * bridge implements iAP2 itself.  The link/session constants below are aligned
 * to the vendor wire RE in <internal notes> + iap2_link.h
 * (recovered from libiapimpl.so: IAP2LinkPkt::setHeader / updateCheckSum,
 * IAP2Link::ThreadDetectIAPVersion / linkSyn / ackRet, ControlSession::setMessage,
 * Parameter::setParam).  Wi-Fi handoff ids are from <internal notes>
 * (the vendor binary has no wireless code) and are kept as configurable
 * constants so they can be adjusted from the first phone trace.
 */
final class Iap2Constants {

    private Iap2Constants() {
    }

    // ==================================================================
    // Bluetooth RFCOMM endpoints
    // ==================================================================

    /**
     * UUID of the iPhone's iAP2 RFCOMM *server*.  The proven direction on a
     * normal Android app is car -> iPhone: dial this and speak iAP2 over it
     * (DiPlay does exactly this).
     */
    static final String IPHONE_IAP2_UUID = "00000000-deca-fade-deca-deafdecacafe";

    /** UUID of the accessory-side iAP2 server (fallback: iPhone dials us). */
    static final String ACCESSORY_IAP2_UUID = "00000000-deca-fade-deca-deafdecacaff";

    /** Legacy/hidden channel-1 fallback when SDP resolution fails. */
    static final int LEGACY_RFCOMM_CHANNEL = 1;

    static final long DIAL_RETRY_INTERVAL_MS = 5000L;
    /** SDP resolution is bounded by its own thread (BluetoothSocket has no knob). */
    static final long SDP_RESOLVE_TIMEOUT_MS = 3500L;
    /** connect() watchdog for the SDP attempt. */
    static final long ATTEMPT_CONNECT_TIMEOUT_MS = 3500L;
    /** connect() watchdog for the hidden channel-1 fallback (shorter: non-iPhone peers). */
    static final long CHANNEL1_CONNECT_TIMEOUT_MS = 2500L;

    // ==================================================================
    // Link layer (AAP / iAP2 LinkPacket) — vendor wire RE
    // ==================================================================

    static final int SOP_DATA = 0xFF5A;
    static final int SOP_SYNC = 0xFF55;

    static final int LINK_HEADER_LEN = 9;
    static final int MIN_LINK_PACKET = 9;      // header-only ACK, no payload checksum

    /** Negotiated max packet size advertised in the SYN payload (0x0FC8). */
    static final int DEFAULT_MAX_LINK_PACKET = 4040;
    static final int MAX_LINK_PAYLOAD = 4040;

    static final int CTRL_ACK = 0x40;          // data AND ack: set on every non-SYN packet
    static final int CTRL_EAK = 0x20;
    static final int CTRL_SYN = 0x80;
    static final int CTRL_SYN_ACK = 0xC0;

    /** Link-level control/ACK frames use session id 0 (reference: sendAcknowledgement). */
    static final int SESSION_LINK = 0;
    /**
     * Control-session id used for CSM messages.  Both our SYN payload and the
     * iPhone's SYN descriptor advertise id 1 (descriptor kind 0 = control), and
     * a real iAP2 capture shows CSM frames with sessionID 0x01.  The peer's
     * descriptor value is adopted at runtime.
     */
    static final int SESSION_CONTROL = 1;

    static final long RETRANSMIT_TIMEOUT_MS = 1000L;   // vendor INF: 1 s
    static final int MAX_RETRANSMITS = 10;             // vendor INF: ~10
    /** NORMAL state: if no valid frame arrives for this long, the link is dead. */
    static final long LINK_WATCHDOG_MS = 8000L;

    // ---- version detection (raw, before framing) ----
    // OBS: IAP2Link::ThreadDetectIAPVersion writes these 6 raw bytes, waits up
    // to 1000 ms, retries up to 50 times.
    static final byte[] DETECT_PROBE = {
            (byte) 0xFF, (byte) 0x55, (byte) 0x02, (byte) 0x00,
            (byte) 0xEE, (byte) 0x10
    };
    static final int DETECT_ATTEMPTS = 50;
    static final long DETECT_WAIT_MS = 1000L;
    /** After this many unanswered probes, proceed to SYN anyway (log why). */
    static final int DETECT_EARLY_GIVE_UP_ATTEMPTS = 5;
    /** With no bytes at all after this many probes, stop probing and send SYN. */
    static final int DETECT_SILENT_GIVE_UP_ATTEMPTS = 15;

    // OBS responses accepted by IAP2Link::oniAPPacket (some have no FF prefix).
    static final byte[][] DETECT_RESPONSES = {
            {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10},
            {0x55, 0x04, 0x00, 0x02, 0x04, (byte) 0xEE, 0x08},
            {0x55, 0x02, 0x00, 0x00, (byte) 0xFE},
            {(byte) 0xFF, 0x55, 0x04, 0x00, 0x02, 0x04, (byte) 0xEE, 0x08},
            {(byte) 0xFF, 0x55, 0x02, 0x00, 0x00, (byte) 0xFE},
    };

    // ---- link SYN ----
    // OBS: IAP2Link::linkSyn, 13-byte payload from IAP2Link ctor defaults.
    static final byte[] SYN_PAYLOAD = {
            0x01, 0x05, 0x00, 0x00, (byte) 0xA0, 0x01, (byte) 0xC8, 0x0F,
            0x1E, 0x03, 0x01, 0x00, 0x01
    };
    static final int SYN_RETRIES = 8;
    static final long SYN_WAIT_MS = 500L;

    // ==================================================================
    // Session layer (CSM) — vendor wire RE
    //
    //   0x40 0x40 | total-len BE16 (= 6 + params) | msgId BE16 | params
    //
    // Parameter TLV is LENGTH FIRST:
    //   len BE16 (includes this 4-byte header; 4 = empty value) | id BE16 | value
    // ==================================================================

    static final int CSM_MAGIC = 0x4040;
    static final int CSM_HEADER_LEN = 6;

    // message ids (vendor-confirmed in libiapimpl where marked; incoming ids from
    // <internal notes> because the vendor binary has no wireless code)
    static final int MSG_START_IDENTIFICATION = 0x1D00;      // phone -> acc (protocol doc)
    static final int MSG_IDENTIFICATION_INFO = 0x1D01;       // OBS vendor sends
    static final int MSG_IDENTIFICATION_ACCEPTED = 0x1D02;   // protocol doc
    static final int MSG_IDENTIFICATION_REJECTED = 0x1D03;   // protocol doc
    static final int MSG_IDENTIFICATION_CANCEL = 0x1D05;     // OBS vendor sends

    static final int MSG_REQ_AUTH_CERT = 0xAA00;             // protocol doc
    static final int MSG_AUTH_CERT = 0xAA01;                 // OBS vendor sends
    static final int MSG_REQ_AUTH_CHALLENGE_RESP = 0xAA02;   // protocol doc
    static final int MSG_AUTH_RESPONSE = 0xAA03;             // OBS vendor sends
    static final int MSG_AUTH_FAILED = 0xAA04;               // protocol doc
    static final int MSG_AUTH_SUCCEEDED = 0xAA05;            // protocol doc
    static final int MSG_AUTH_SERIAL = 0xAA06;               // OBS vendor sends

    static final int MSG_REQ_WIFI_CONFIG = 0x5702;           // protocol doc (UNVERIFIED vendor)
    static final int MSG_WIFI_CONFIG = 0x5703;               // protocol doc (UNVERIFIED vendor)

    static final int MSG_CARPLAY_AVAILABILITY = 0x4300;
    static final int MSG_CARPLAY_START_SESSION = 0x4301;

    static final int MSG_START_EA_SESSION = 0xEA00;
    static final int MSG_STOP_EA_SESSION = 0xEA01;

    static final int MSG_WIRELESS_CARPLAY_UPDATE = 0x4E0D;
    static final int MSG_DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E;

    // identification parameter ids (public/protocol report; vendor binary shows
    // component ids 0x12 IAP2-HID, 0x14 vehicle info, 0x16 location, 0x22 UUID)
    static final int IDENT_PARAM_NAME = 0;
    static final int IDENT_PARAM_MODEL_ID = 1;
    static final int IDENT_PARAM_MANUFACTURER = 2;
    static final int IDENT_PARAM_SERIAL = 3;
    static final int IDENT_PARAM_FIRMWARE = 4;
    static final int IDENT_PARAM_HARDWARE = 5;
    static final int IDENT_PARAM_MSGS_SENT = 6;
    static final int IDENT_PARAM_MSGS_RECEIVED = 7;
    static final int IDENT_PARAM_POWER_PROVIDING = 8;
    static final int IDENT_PARAM_MAX_CURRENT = 9;
    static final int IDENT_PARAM_CURRENT_LANGUAGE = 12;
    static final int IDENT_PARAM_SUPPORTED_LANGUAGES = 13;
    static final int IDENT_PARAM_EA_PROTOCOL = 10;
    static final int IDENT_PARAM_BLUETOOTH_TRANSPORT = 17;
    static final int IDENT_PARAM_LOCATION = 22;
    static final int IDENT_PARAM_WIRELESS_CARPLAY_TRANSPORT = 24;
    static final int IDENT_PARAM_ROUTE_GUIDANCE = 30;

    /** External-accessory-protocol group sub-ids (DiPlay group(10)). */
    static final int EA_SUB_INDEX = 0;
    static final int EA_SUB_PROTOCOL = 1;
    static final int EA_SUB_OPTIONS = 2;

    /**
     * External-accessory protocol advertised in identification param 10.
     * Deliberately our own dotted id: the reference does not claim Apple's
     * CarPlay EA flag.  CarPlay capability is declared via identification
     * params 17/24 and the 0x4300/0x4301 message ids.
     */
    static final String EXTERNAL_ACCESSORY_PROTOCOL = "com.cpbridge.carplay";

    /** CarPlayStartSession defaults (vendor field log: port 7000, srcVer 450.14.1). */
    static final int CARPLAY_AIRPLAY_PORT = 7000;
    static final String CARPLAY_SOURCE_VERSION = "450.14.1";

    /**
     * 0x5703 parameter ids, from the interoperating DiPlay reference
     * (Iap2WirelessMessages.accessoryWiFiConfiguration):
     *   0 = optional BSSID (6 bytes), 1 = SSID, 2 = passphrase,
     *   3 = u8 security type, 4 = u8 channel.
     */
    static final int WIFI_PARAM_BSSID = 0;
    static final int WIFI_PARAM_SSID = 1;
    static final int WIFI_PARAM_PASSPHRASE = 2;
    static final int WIFI_PARAM_SECURITY = 3;
    static final int WIFI_PARAM_CHANNEL = 4;

    /** 0x4301 CarPlayStartSession parameter ids (DiPlay Iap2CarPlayMessages.startSession). */
    static final int START_PARAM_WIRED_GROUP = 0;
    static final int START_PARAM_WIRELESS_GROUP = 1;
    static final int START_PARAM_AIRPLAY_PORT = 2;      // u32
    static final int START_PARAM_DEVICE_ID = 3;         // string
    static final int START_PARAM_PUBLIC_KEY = 4;        // string
    static final int START_PARAM_SOURCE_VERSION = 5;    // string
    static final int START_PARAM_SDK_VERSION = 6;       // optional string
    static final int START_PARAM_CLUSTER_GROUP = 7;
    static final int START_PARAM_MUTUAL_AUTH = 8;       // optional u8

    /** 0x4301 wireless sub-group parameter ids. */
    static final int START_WIFI_PARAM_SSID = 0;
    static final int START_WIFI_PARAM_PASSPHRASE = 1;
    static final int START_WIFI_PARAM_CHANNEL = 2;
    static final int START_WIFI_PARAM_IP_ADDRESSES = 3; // repeated strings
    static final int START_WIFI_PARAM_SECURITY = 4;

    /**
     * Messages this accessory can SEND (identification param 6). CarPlay bits
     * (0x4301) and the auth responses must be declared, or the iPhone refuses
     * to treat us as a CarPlay accessory.
     */
    static final int[] IDENT_MSGS_SENT = {
            MSG_AUTH_CERT,                  // 0xAA01
            MSG_AUTH_RESPONSE,              // 0xAA03
            MSG_WIFI_CONFIG,                // 0x5703
            MSG_CARPLAY_START_SESSION,      // 0x4301
            MSG_START_EA_SESSION,           // 0xEA00 (EA protocol declared in param 10)
            MSG_STOP_EA_SESSION,            // 0xEA01
    };

    /** Messages this accessory can RECEIVE (identification param 7). */
    static final int[] IDENT_MSGS_RECEIVED = {
            MSG_REQ_AUTH_CERT,              // 0xAA00
            MSG_REQ_AUTH_CHALLENGE_RESP,    // 0xAA02
            MSG_AUTH_FAILED,                // 0xAA04
            MSG_AUTH_SUCCEEDED,             // 0xAA05
            MSG_CARPLAY_AVAILABILITY,       // 0x4300
            MSG_WIRELESS_CARPLAY_UPDATE,    // 0x4E0D
            MSG_DEVICE_TRANSPORT_IDENTIFIER,// 0x4E0E
            MSG_REQ_WIFI_CONFIG,            // 0x5702
            MSG_START_EA_SESSION,           // 0xEA00 (reference lists it here too)
            MSG_STOP_EA_SESSION,            // 0xEA01
    };

    // identification values
    static final String ACC_NAME = "CarPlay";
    static final String ACC_MODEL = "BWIC-A100";
    static final String ACC_MANUFACTURER = "Autochips";
    static final String ACC_FIRMWARE = "1.0.0";
    static final String ACC_HARDWARE = "AC8257";
    static final String ACC_LANGUAGE = "en";

    /** Wireless accessory: DiPlay sends 0 for wireless / 2 for wired. */
    static final int POWER_PROVIDING_CAPABILITY = 0;
    static final int MAX_CURRENT_DRAWN_MA = 20;

    /** Wi-Fi security type 2 = WPA/WPA2 (SecurityType value). */
    static final int WIFI_SECURITY_WPA_WPA2 = 2;

    // ==================================================================
    // Behaviour switches (first hardware test)
    // ==================================================================

    /**
     * AUTH_SKIP=true forces the old no-auth behaviour (empty certificate /
     * signature placeholders).  Default false: perform the real MFi exchange by
     * asking the root auth daemon (AuthDaemonClient).
     */
    static final boolean AUTH_SKIP = false;

    /**
     * If the auth daemon is unavailable, still answer with empty placeholders
     * (and a loud CPBRIDGE error) so a run completes and proves the link /
     * session layers; the iPhone will then send 0xAA04.
     */
    static final boolean AUTH_FALLBACK_TO_EMPTY = true;

    /** Root MFi auth daemon (started by the Lead): unix socket + framing. */
    static final String AUTH_DAEMON_SOCKET = "/data/local/tmp/cpauth.sock";
    static final int AUTH_OP_CERTIFICATE = 1;
    static final int AUTH_OP_SIGN = 2;
    static final int AUTH_OP_SERIAL = 3;
    static final long AUTH_CONNECT_TIMEOUT_MS = 20000L;   // init service may lag a few s
    static final long AUTH_READY_RETRY_MS = 250L;
    static final long AUTH_READY_DEADLINE_MS = 20000L;
    static final long AUTH_CERT_TIMEOUT_MS = 20000L;   // server deadline is 15 s; client a bit longer
    static final long AUTH_SIGN_TIMEOUT_MS = 20000L;
    /** Verified daemon sanity values (30/30 runs on this unit). */
    static final int AUTH_EXPECTED_CERT_LENGTH = 607;
    static final int AUTH_EXPECTED_SIGNATURE_LENGTH = 64;
    static final int AUTH_MAX_CHALLENGE_LENGTH = 32;

    /** iAP2 strings are NUL-terminated UTF-8. */
    static final boolean STRING_PARAMS_NUL_TERMINATED = true;

    /** Log the first N link frames in full hex (all frames get a one-line summary). */
    static final int FULL_HEX_FRAMES = 60;

    /** Accessory Bluetooth MAC used in identification param 17. */
    static final String DEFAULT_ACCESSORY_BT_MAC = "YOUR-UNIT-BT-MAC";
}
