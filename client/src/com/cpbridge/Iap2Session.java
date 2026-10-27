package com.cpbridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * iAP2 control-session (CSM) layer, aligned to the DiPlay reference
 * (<internal notes>):
 *
 *   0x1D00 StartIdentification  -> 0x1D01 IdentificationInformation
 *        (identity strings + message lists + EA group 10 + BT group 17 +
 *         WirelessCarPlay group 24; consistent minimal message lists)
 *   0x1D02 accepted / 0x1D03 rejected (retry once without unsupported messages)
 *   0xAA00 -> 0xAA01 certificate      (param 0 = DER from root auth daemon)
 *   0xAA02 -> 0xAA03 signature        (param 0 = 64-byte sig for the challenge)
 *   0xAA04 failed / 0xAA05 succeeded
 *   0x5702 -> 0x5703 accessory Wi-Fi config
 *        (param 0 optional BSSID, 1 SSID, 2 passphrase, 3 security u8, 4 channel u8)
 *   0x4300 CarPlayAvailability -> 0x4301 CarPlayStartSession (if a public key is
 *        configured; the vendor AirPlay process owns the real receiver identity)
 *   0x4E0D/0x4E0E WirelessCarPlay notifications (0x4E0E triggers a 0x5703 resend)
 *
 * CSM framing: 40 40 | total-len BE16 (= 6 + params) | msgId BE16 | params
 * Param TLV:   len BE16 (incl. 4-byte header) | id BE16 | value  (LENGTH FIRST)
 *
 * Auth: real MFi via the root daemon socket /data/local/tmp/cpauth.sock; if the
 * daemon is unavailable we log loudly and fall back to empty placeholders so the
 * run still proves the link/session layers (iPhone then answers 0xAA04).
 */
final class Iap2Session implements Iap2Link.Listener {

    private static final String TAG = BridgeConfig.TAG_IAP2;

    interface StatusListener {
        void onIdentified();

        void onAuthFailed();

        void onWifiSent();

        void onLinkClosed();
    }

    private final AuthDaemonClient mAuth = new AuthDaemonClient();
    private final CsmReassembler mCsm = new CsmReassembler();
    private volatile Iap2Link mLink;
    private volatile StatusListener mStatusListener;

    private int mIdentRetries;
    private boolean mIdentAccepted;
    private final Set<Integer> mOmittedMessages = new LinkedHashSet<Integer>();
    private final Set<Integer> mOmittedParams = new LinkedHashSet<Integer>();
    private static final int MAX_IDENT_RETRIES = 2;
    private int mAuthCertRequests;
    private int mAuthChallengeRequests;
    private int mWifiRequests;
    private int mPreTransportWifiSends;
    private int mPostTransportWifiSends;
    private int mStartSessionsSent;

    private static final int MAX_PRE_TRANSPORT_WIFI_SENDS = 5;
    private static final int MAX_POST_TRANSPORT_WIFI_SENDS = 2;

    Iap2Session() {
    }

    void attach(Iap2Link link) {
        mLink = link;
    }

    void setStatusListener(StatusListener listener) {
        mStatusListener = listener;
    }

    void start() {
        Cplog.i(TAG, "=== iAP2 session ready (CSM: 40 40 | len BE16 | msgId BE16 |"
                + " length-first TLVs; control session id="
                + Iap2Constants.SESSION_CONTROL + ") ===");
        Cplog.i(TAG, "identification: name=\"" + Iap2Constants.ACC_NAME
                + "\" model=" + Iap2Constants.ACC_MODEL
                + " manufacturer=" + Iap2Constants.ACC_MANUFACTURER
                + " serial=" + BridgeConfig.serial()
                + " fw=" + Iap2Constants.ACC_FIRMWARE
                + " hw=" + Iap2Constants.ACC_HARDWARE
                + " eaProtocol=" + Iap2Constants.EXTERNAL_ACCESSORY_PROTOCOL);
        Cplog.i(TAG, "accessory BT MAC (ident param 17)=" + BridgeConfig.btMac()
                + " source=" + BridgeConfig.btMacSource());
        Cplog.i(TAG, "Wi-Fi: ssid=\"" + BridgeConfig.ssid()
                + "\" passLen=" + BridgeConfig.passphrase().length()
                + " channel=" + BridgeConfig.wifiChannel()
                + " bssid=" + BridgeConfig.wifiBssid()
                + " apIp=" + BridgeConfig.apIpv4());
        Cplog.i(TAG, "0x4301 config: publicKey="
                + (BridgeConfig.carplayPublicKey().length() == 0 ? "(unset -> 0x4301 skipped)"
                : "len=" + BridgeConfig.carplayPublicKey().length())
                + " port=" + BridgeConfig.carplayAirplayPort()
                + " srcVers=" + BridgeConfig.carplaySourceVersion()
                + " deviceId=" + BridgeConfig.carplayDeviceIdentifier());
        Cplog.i(TAG, "AUTH_SKIP=" + Iap2Constants.AUTH_SKIP
                + " authDaemon=" + Iap2Constants.AUTH_DAEMON_SOCKET
                + " fallbackToEmpty=" + Iap2Constants.AUTH_FALLBACK_TO_EMPTY);
        Cplog.stage("d_session", "session ready; waiting for StartIdentification 0x1D00");
    }

    @Override
    public void onLinkEvent(String event) {
        Cplog.d(TAG, "link event: " + event);
    }

    @Override
    public void onLinkClosed() {
        mCsm.resetAll();
        Cplog.stage("b_link_sync", "link CLOSED by peer");
        StatusListener l = mStatusListener;
        if (l != null) {
            try {
                l.onLinkClosed();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Entry point from the link layer: each link packet payload is a CSM
     * fragment (the iPhone splits even tiny CSMs).  Reassemble per session,
     * then hand complete CSMs to parseCsm().
     */
    @Override
    public void onPayload(int session, byte[] payload) {
        CsmReassembler.Result result = mCsm.add(session, payload);
        for (String line : result.trace) {
            Cplog.i(TAG, "CSM FRAG " + line);
            if (line.contains("COMPLETE")) {
                Cplog.stage("c_frame_parse", line);
            } else if (!line.contains("fragment #1")
                    && !line.contains("fragment #2")
                    && !line.contains("fragment #3")) {
                Cplog.stage("c_frame_parse", "CSM reassembly: " + line);
            }
        }
        for (byte[] complete : result.completed) {
            parseCsm(session, complete);
        }
    }

    /** Parses one complete CSM message (already reassembled). */
    private void parseCsm(int session, byte[] payload) {
        if (payload == null || payload.length < Iap2Constants.CSM_HEADER_LEN) {
            Cplog.w(TAG, "short CSM payload (" + (payload == null ? 0 : payload.length)
                    + "B): " + BridgeConfig.hexAll(payload));
            Cplog.stage("c_frame_parse", "short CSM payload "
                    + (payload == null ? 0 : payload.length) + "B");
            return;
        }
        int magic = u16(payload, 0);
        int declaredLen = u16(payload, 2);
        int msgId = u16(payload, 4);
        if (magic != Iap2Constants.CSM_MAGIC) {
            Cplog.w(TAG, "CSM magic 0x" + String.format("%04X", magic)
                    + " (expected 0x4040) session=" + session
                    + " hex=" + BridgeConfig.hexAll(payload));
            Cplog.stage("c_frame_parse", "bad CSM magic 0x"
                    + String.format("%04X", magic));
        }
        int tlvLen = payload.length - Iap2Constants.CSM_HEADER_LEN;
        byte[] tlvs = new byte[Math.max(tlvLen, 0)];
        if (tlvLen > 0) {
            System.arraycopy(payload, Iap2Constants.CSM_HEADER_LEN, tlvs, 0, tlvLen);
        }
        Cplog.i(TAG, "CSM RX msgId=0x" + String.format("%04X", msgId)
                + " (" + messageName(msgId) + ")"
                + " session=" + session
                + " declaredLen=" + declaredLen
                + " actualLen=" + payload.length
                + (declaredLen == payload.length ? "" : " CSM-LEN-MISMATCH")
                + " tlv=" + tlvLen + "B");
        if (tlvLen > 0) {
            Cplog.i(TAG, "  params:" + Iap2Tlv.describe(tlvs));
        }
        dispatch(msgId, tlvs);
    }

    private void dispatch(int msgId, byte[] tlvs) {
        Cplog.stage("d_session", "RX msgId=0x" + String.format("%04X", msgId)
                + " (" + messageName(msgId) + ")");
        switch (msgId) {
            case Iap2Constants.MSG_START_IDENTIFICATION:
                Cplog.i(TAG, "phone sent StartIdentification (0x1D00) -> replying"
                        + " IdentificationInformation (0x1D01)");
                sendIdentification();
                break;

            case Iap2Constants.MSG_IDENTIFICATION_ACCEPTED:
                mIdentAccepted = true;
                Cplog.i(TAG, ">>> IDENTIFICATION ACCEPTED by iPhone (0x1D02) <<<");
                Cplog.stage("d_session", "IDENTIFICATION ACCEPTED (0x1D02)");
                StatusListener identified = mStatusListener;
                if (identified != null) {
                    try {
                        identified.onIdentified();
                    } catch (Throwable ignored) {
                    }
                }
                break;

            case Iap2Constants.MSG_IDENTIFICATION_REJECTED:
                handleIdentificationRejected(tlvs);
                break;

            case Iap2Constants.MSG_REQ_AUTH_CERT:
                mAuthCertRequests++;
                Cplog.i(TAG, "phone sent RequestAuthenticationCertificate (0xAA00) #"
                        + mAuthCertRequests);
                Cplog.stage("e_auth", "RequestAuthenticationCertificate (0xAA00) #"
                        + mAuthCertRequests);
                sendAuthCertificate();
                break;

            case Iap2Constants.MSG_REQ_AUTH_CHALLENGE_RESP: {
                mAuthChallengeRequests++;
                byte[] challenge = firstParamValue(tlvs);
                Cplog.i(TAG, "phone sent RequestAuthenticationChallengeResponse (0xAA02) #"
                        + mAuthChallengeRequests + " challengeLen="
                        + (challenge == null ? 0 : challenge.length)
                        + " challenge=" + BridgeConfig.hexAll(challenge));
                Cplog.stage("e_auth", "RequestAuthenticationChallengeResponse (0xAA02)"
                        + " challengeLen=" + (challenge == null ? 0 : challenge.length));
                sendAuthResponse(challenge);
                break;
            }

            case Iap2Constants.MSG_AUTH_FAILED:
                Cplog.e(TAG, ">>> AUTHENTICATION FAILED (0xAA04) from iPhone <<<");
                Cplog.stage("e_auth", "AUTH REJECTED by phone (0xAA04)"
                        + (Iap2Constants.AUTH_SKIP ? " [AUTH_SKIP=true]" : ""));
                notifyAuthFailed();
                break;

            case Iap2Constants.MSG_AUTH_SUCCEEDED:
                Cplog.i(TAG, ">>> AUTHENTICATION SUCCEEDED (0xAA05) from iPhone <<<");
                Cplog.stage("e_auth", "AUTH SUCCEEDED (0xAA05) -- real MFi accepted");
                break;

            case Iap2Constants.MSG_AUTH_SERIAL:
                Cplog.i(TAG, "phone sent auth-serial (0xAA06) -- logged");
                break;

            case Iap2Constants.MSG_REQ_WIFI_CONFIG:
                mWifiRequests++;
                Cplog.i(TAG, "phone requested Wi-Fi config (0x5702) #" + mWifiRequests
                        + " -> replying 0x5703");
                Cplog.stage("f_credentials", "REQUEST AccessoryWiFiConfiguration (0x5702) #"
                        + mWifiRequests);
                sendWifiConfiguration(false);
                break;

            case Iap2Constants.MSG_CARPLAY_AVAILABILITY:
                Cplog.i(TAG, "phone sent CarPlayAvailability (0x4300): "
                        + describeAvailability(tlvs));
                Cplog.stage("g_carplay", "CarPlayAvailability (0x4300): "
                        + describeAvailability(tlvs));
                sendCarPlayStartSession();
                break;

            case Iap2Constants.MSG_WIRELESS_CARPLAY_UPDATE:
                Cplog.i(TAG, "phone sent WirelessCarPlayUpdate (0x4E0D): "
                        + BridgeConfig.hexAll(tlvs));
                Cplog.stage("g_carplay", "WirelessCarPlayUpdate (0x4E0D)");
                break;

            case Iap2Constants.MSG_DEVICE_TRANSPORT_IDENTIFIER:
                Cplog.i(TAG, "phone sent DeviceTransportIdentifierNotification (0x4E0E): "
                        + BridgeConfig.hexAll(tlvs));
                Cplog.stage("g_carplay", "DeviceTransportIdentifierNotification (0x4E0E)"
                        + " -> resending 0x5703");
                sendWifiConfiguration(true);
                break;

            case Iap2Constants.MSG_START_EA_SESSION:
                Cplog.i(TAG, "phone sent StartExternalAccessoryProtocolSession (0xEA00)");
                break;

            case Iap2Constants.MSG_STOP_EA_SESSION:
                Cplog.i(TAG, "phone sent StopExternalAccessoryProtocolSession (0xEA01)");
                break;

            case Iap2Constants.MSG_IDENTIFICATION_CANCEL:
                Cplog.i(TAG, "phone sent IdentificationCancel (0x1D05)");
                break;

            case Iap2Constants.MSG_CARPLAY_START_SESSION:
                Cplog.w(TAG, "unexpected RX 0x4301 (accessory->phone direction)");
                break;

            case Iap2Constants.MSG_WIFI_CONFIG:
                Cplog.w(TAG, "unexpected RX 0x5703 (accessory->phone direction)");
                break;

            default:
                Cplog.w(TAG, "UNKNOWN message id 0x" + String.format("%04X", msgId)
                        + " tlv=" + BridgeConfig.hexAll(tlvs));
                break;
        }
    }

    // ------------------------------------------------------------------
    // identification (DiPlay Iap2IdentificationClient.layout)
    // ------------------------------------------------------------------

    private void sendIdentification() {
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.string(Iap2Constants.IDENT_PARAM_NAME, Iap2Constants.ACC_NAME);
        tlv.string(Iap2Constants.IDENT_PARAM_MODEL_ID, Iap2Constants.ACC_MODEL);
        tlv.string(Iap2Constants.IDENT_PARAM_MANUFACTURER, Iap2Constants.ACC_MANUFACTURER);
        tlv.string(Iap2Constants.IDENT_PARAM_SERIAL, BridgeConfig.serial());
        tlv.string(Iap2Constants.IDENT_PARAM_FIRMWARE, Iap2Constants.ACC_FIRMWARE);
        tlv.string(Iap2Constants.IDENT_PARAM_HARDWARE, Iap2Constants.ACC_HARDWARE);
        tlv.u16List(Iap2Constants.IDENT_PARAM_MSGS_SENT,
                filterOmitted(Iap2Constants.IDENT_MSGS_SENT));
        tlv.u16List(Iap2Constants.IDENT_PARAM_MSGS_RECEIVED,
                filterOmitted(Iap2Constants.IDENT_MSGS_RECEIVED));
        tlv.u8(Iap2Constants.IDENT_PARAM_POWER_PROVIDING, 0);   // wireless
        tlv.u16(Iap2Constants.IDENT_PARAM_MAX_CURRENT, 20);

        if (!mOmittedParams.contains(Iap2Constants.IDENT_PARAM_EA_PROTOCOL)) {
            Iap2Tlv ea = new Iap2Tlv();
            ea.u8(Iap2Constants.EA_SUB_INDEX, 1);
            ea.string(Iap2Constants.EA_SUB_PROTOCOL,
                    Iap2Constants.EXTERNAL_ACCESSORY_PROTOCOL);
            ea.u8(Iap2Constants.EA_SUB_OPTIONS, 0);
            tlv.group(Iap2Constants.IDENT_PARAM_EA_PROTOCOL, ea);
        }

        tlv.string(Iap2Constants.IDENT_PARAM_CURRENT_LANGUAGE, Iap2Constants.ACC_LANGUAGE);
        tlv.stringList(Iap2Constants.IDENT_PARAM_SUPPORTED_LANGUAGES,
                new String[] {Iap2Constants.ACC_LANGUAGE});

        if (!mOmittedParams.contains(Iap2Constants.IDENT_PARAM_BLUETOOTH_TRANSPORT)) {
            Iap2Tlv bt = new Iap2Tlv();
            bt.u16(0, 0);                              // index
            bt.string(1, "blue");                      // transport name
            bt.empty(2);
            bt.bytes(3, BridgeConfig.btMacBytes());    // accessory BT MAC
            bt.string(4, "blue");
            bt.empty(5);
            tlv.group(Iap2Constants.IDENT_PARAM_BLUETOOTH_TRANSPORT, bt);
        }

        if (!mOmittedParams.contains(
                Iap2Constants.IDENT_PARAM_WIRELESS_CARPLAY_TRANSPORT)) {
            Iap2Tlv wireless = new Iap2Tlv();
            wireless.u16(0, 1);                        // index
            wireless.string(1, BridgeConfig.ssid());   // AP SSID
            wireless.empty(2);
            wireless.u16(3, 1);
            wireless.empty(4);
            wireless.empty(5);
            tlv.group(Iap2Constants.IDENT_PARAM_WIRELESS_CARPLAY_TRANSPORT, wireless);
        }

        Cplog.i(TAG, "CSM TX IdentificationInformation: tlv=" + tlv.size() + "B"
                + " eaProtocol=" + Iap2Constants.EXTERNAL_ACCESSORY_PROTOCOL
                + " msgsSent=" + hexIds(filterOmitted(Iap2Constants.IDENT_MSGS_SENT))
                + " msgsReceived=" + hexIds(filterOmitted(Iap2Constants.IDENT_MSGS_RECEIVED))
                + (mOmittedMessages.isEmpty() ? "" : " omittedMsgs=" + mOmittedMessages)
                + (mOmittedParams.isEmpty() ? "" : " omittedParams=" + mOmittedParams));
        sendCsm(Iap2Constants.MSG_IDENTIFICATION_INFO, tlv);
    }

    private void handleIdentificationRejected(byte[] tlvs) {
        List<Iap2Tlv.Param> params = Iap2Tlv.parse(tlvs);
        Set<Integer> rejectedParams = new LinkedHashSet<Integer>();
        Set<Integer> unsupportedMessages = new LinkedHashSet<Integer>();
        Set<Integer> unsupportedAuthMessages = new LinkedHashSet<Integer>();
        for (Iap2Tlv.Param p : params) {
            rejectedParams.add(p.id);
            if ((p.id == Iap2Constants.IDENT_PARAM_MSGS_SENT
                    || p.id == Iap2Constants.IDENT_PARAM_MSGS_RECEIVED)
                    && p.value.length >= 2 && p.value.length % 2 == 0) {
                for (int i = 0; i < p.value.length; i += 2) {
                    int id = ((p.value[i] & 0xFF) << 8) | (p.value[i + 1] & 0xFF);
                    if (id >= 0xAA00 && id <= 0xAA06) {
                        unsupportedAuthMessages.add(id);   // never omit auth
                    } else {
                        unsupportedMessages.add(id);
                    }
                }
            }
        }
        Cplog.e(TAG, ">>> IDENTIFICATION REJECTED (0x1D03) rejectedParams="
                + rejectedParams + " unsupportedMessages=" + unsupportedMessages
                + (unsupportedAuthMessages.isEmpty() ? ""
                : " unsupportedAuthMessages=" + unsupportedAuthMessages) + " <<<");
        Cplog.stage("d_session", "IDENTIFICATION REJECTED (0x1D03) params="
                + rejectedParams + " messages=" + unsupportedMessages);

        if (mIdentRetries >= MAX_IDENT_RETRIES) {
            Cplog.e(TAG, "identification rejected again after " + mIdentRetries
                    + " retries -- giving up (params=" + rejectedParams + ")");
            Cplog.stage("d_session", "IDENTIFICATION GIVE UP after "
                    + mIdentRetries + " retries");
            return;
        }
        mIdentRetries++;
        for (Integer id : rejectedParams) {
            if (id == Iap2Constants.IDENT_PARAM_MSGS_SENT
                    || id == Iap2Constants.IDENT_PARAM_MSGS_RECEIVED) {
                // message-list params are handled by dropping the ids below
                continue;
            }
            mOmittedParams.add(id);
            if (id == Iap2Constants.IDENT_PARAM_EA_PROTOCOL) {
                // Without the EA protocol group the EA message ids are no longer
                // truthful; drop them from both lists for the retry.
                mOmittedMessages.add(Iap2Constants.MSG_START_EA_SESSION);
                mOmittedMessages.add(Iap2Constants.MSG_STOP_EA_SESSION);
            }
        }
        mOmittedMessages.addAll(unsupportedMessages);
        Cplog.i(TAG, "IDENTIFICATION RETRY #" + mIdentRetries
                + " without params=" + mOmittedParams
                + " messages=" + mOmittedMessages
                + (unsupportedAuthMessages.isEmpty() ? ""
                : " (cannot omit auth messages " + unsupportedAuthMessages + ")"));
        Cplog.stage("d_session", "IDENTIFICATION RETRY #" + mIdentRetries
                + " without params=" + mOmittedParams
                + " messages=" + mOmittedMessages);
        sendIdentification();
    }

    private static boolean onlyMessageListParams(Set<Integer> ids) {
        for (Integer id : ids) {
            if (id != Iap2Constants.IDENT_PARAM_MSGS_SENT
                    && id != Iap2Constants.IDENT_PARAM_MSGS_RECEIVED) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAuthMessage(Set<Integer> ids) {
        for (Integer id : ids) {
            if (id >= 0xAA00 && id <= 0xAA06) {
                return true;
            }
        }
        return false;
    }

    private int[] filterOmitted(int[] ids) {
        if (mOmittedMessages.isEmpty()) {
            return ids;
        }
        List<Integer> kept = new java.util.ArrayList<Integer>();
        for (int id : ids) {
            if (!mOmittedMessages.contains(id)) {
                kept.add(id);
            }
        }
        int[] out = new int[kept.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kept.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // MFi authentication (real via root daemon, fallback placeholders)
    // ------------------------------------------------------------------

    private volatile boolean mSerialLogged;

    private void sendAuthCertificate() {
        byte[] certificate = null;
        if (!Iap2Constants.AUTH_SKIP) {
            certificate = mAuth.readCertificate();
        }
        if (certificate == null) {
            if (Iap2Constants.AUTH_SKIP) {
                Cplog.w(TAG, "AUTH_SKIP=true: no MFi certificate requested from daemon");
            } else if (!Iap2Constants.AUTH_FALLBACK_TO_EMPTY) {
                Cplog.e(TAG, "no MFi certificate and fallback disabled; not replying");
                return;
            } else {
                Cplog.e(TAG, "MFi certificate unavailable (auth daemon " 
                        + Iap2Constants.AUTH_DAEMON_SOCKET
                        + " missing?); falling back to EMPTY certificate so the run"
                        + " continues -- the iPhone will answer 0xAA04");
                Cplog.stage("e_auth", "FALLBACK: auth daemon unavailable, sending"
                        + " empty certificate");
            }
            certificate = new byte[0];
        }
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.bytes(0, certificate);
        Cplog.i(TAG, "CSM TX AuthenticationCertificate (0xAA01) cert="
                + certificate.length + "B"
                + (certificate.length > 0
                ? " first16=" + BridgeConfig.hex(certificate, 16) : ""));
        sendCsm(Iap2Constants.MSG_AUTH_CERT, tlv);
        if (certificate.length > 0 && !mSerialLogged) {
            mSerialLogged = true;
            mAuth.readSerial();   // op=3 diagnostic, already logged by the client
        }
    }

    private void sendAuthResponse(byte[] challenge) {
        if (challenge == null || challenge.length == 0) {
            Cplog.w(TAG, "0xAA02 carried no challenge parameter; nothing to sign");
            return;
        }
        if (challenge.length < 1
                || challenge.length > Iap2Constants.AUTH_MAX_CHALLENGE_LENGTH) {
            Cplog.w(TAG, "0xAA02 challenge length " + challenge.length
                    + " outside the documented 1.."
                    + Iap2Constants.AUTH_MAX_CHALLENGE_LENGTH
                    + " range; passing it to the daemon as-is");
            Cplog.stage("e_auth", "challenge length " + challenge.length
                    + " outside 1.." + Iap2Constants.AUTH_MAX_CHALLENGE_LENGTH
                    + " -- passing through");
        } else if (challenge.length != 32) {
            Cplog.w(TAG, "0xAA02 challenge is " + challenge.length
                    + "B (the auth daemon expects 32); passing it as-is");
            Cplog.stage("e_auth", "challenge length " + challenge.length
                    + "B (daemon wants 32) -- passing through");
        } else {
            Cplog.i(TAG, "0xAA02 challenge is 32B (full challenge after CSM"
                    + " reassembly) -- signing");
            Cplog.stage("e_auth", "challenge 32B -> auth daemon sign");
        }
        byte[] signature = null;
        if (!Iap2Constants.AUTH_SKIP) {
            signature = mAuth.signChallenge(challenge);
        }
        if (signature == null) {
            if (!Iap2Constants.AUTH_FALLBACK_TO_EMPTY) {
                Cplog.e(TAG, "no MFi signature and fallback disabled; not replying");
                return;
            }
            Cplog.e(TAG, "MFi signature unavailable (auth daemon problem); falling back"
                    + " to " + challenge.length + " zero bytes -- expect 0xAA04");
            Cplog.stage("e_auth", "FALLBACK: auth daemon unavailable, sending zeros");
            signature = new byte[challenge.length];
        }
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.bytes(0, signature);
        Cplog.i(TAG, "CSM TX AuthenticationResponse (0xAA03) signature="
                + signature.length + "B"
                + (signature.length > 0
                ? " first16=" + BridgeConfig.hex(signature, 16) : ""));
        sendCsm(Iap2Constants.MSG_AUTH_RESPONSE, tlv);
    }

    // ------------------------------------------------------------------
    // Wi-Fi credentials (0x5702 -> 0x5703), DiPlay parameter order
    // ------------------------------------------------------------------

    private void sendWifiConfiguration(boolean postTransport) {
        int already = postTransport ? mPostTransportWifiSends : mPreTransportWifiSends;
        int limit = postTransport ? MAX_POST_TRANSPORT_WIFI_SENDS
                : MAX_PRE_TRANSPORT_WIFI_SENDS;
        if (already >= limit) {
            Cplog.w(TAG, "0x5703 ignored: maximum " + (postTransport ? "post" : "pre")
                    + "-transport sends reached (" + already + "/" + limit + ")");
            return;
        }

        String ssid = BridgeConfig.ssid();
        String pass = BridgeConfig.passphrase();
        int channel = BridgeConfig.wifiChannel();
        String bssid = BridgeConfig.wifiBssid();

        Iap2Tlv tlv = new Iap2Tlv();
        if (bssid != null && bssid.length() > 0) {
            byte[] bssidBytes = BridgeConfig.macToBytes(bssid);
            tlv.bytes(Iap2Constants.WIFI_PARAM_BSSID, bssidBytes);   // param 0, optional
        }
        tlv.string(Iap2Constants.WIFI_PARAM_SSID, ssid);             // param 1
        tlv.string(Iap2Constants.WIFI_PARAM_PASSPHRASE, pass);       // param 2
        tlv.u8(Iap2Constants.WIFI_PARAM_SECURITY,
                Iap2Constants.WIFI_SECURITY_WPA_WPA2);               // param 3
        tlv.u8(Iap2Constants.WIFI_PARAM_CHANNEL, channel & 0xFF);    // param 4

        if (postTransport) {
            mPostTransportWifiSends++;
        } else {
            mPreTransportWifiSends++;
        }
        Cplog.i(TAG, "CSM TX AccessoryWiFiConfigurationInformation (0x5703)"
                + (postTransport ? " [post-transport]" : "")
                + ": bssid=" + (bssid == null || bssid.length() == 0 ? "(omitted)" : bssid)
                + " ssid=\"" + ssid + "\""
                + " passphrase=\"" + pass + "\""
                + " security=" + Iap2Constants.WIFI_SECURITY_WPA_WPA2 + " (WPA/WPA2)"
                + " channel=" + channel
                + " params=(0:BSSID,1:SSID,2:PASS,3:SEC,4:CH)"
                + " [channelSource=" + BridgeConfig.wifiChannelSource()
                + ", bssidSource=" + BridgeConfig.wifiBssidSource() + "]");
        sendCsm(Iap2Constants.MSG_WIFI_CONFIG, tlv);
        Cplog.stage("f_credentials", "SENT 0x5703" + (postTransport ? " (post-transport)" : "")
                + " ssid=\"" + ssid + "\" passLen=" + pass.length()
                + " channel=" + channel + " security="
                + Iap2Constants.WIFI_SECURITY_WPA_WPA2
                + " bssid=" + (bssid == null || bssid.length() == 0 ? "(omitted)" : bssid));
        StatusListener wifiSent = mStatusListener;
        if (wifiSent != null) {
            try {
                wifiSent.onWifiSent();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // CarPlayStartSession (0x4300 -> 0x4301)
    // ------------------------------------------------------------------

    private void sendCarPlayStartSession() {
        String publicKey = BridgeConfig.carplayPublicKey();
        if (publicKey.length() == 0) {
            Cplog.w(TAG, "0x4300 CarPlayAvailability received, but 0x4301 needs the"
                    + " AirPlay receiver public key; set persist.cpbridge.public_key"
                    + " to send it (the vendor AirPlay process owns the real identity)."
                    + " Keeping the link alive.");
            Cplog.stage("g_carplay", "0x4300 received; 0x4301 SKIPPED (no public_key)");
            return;
        }
        String ssid = BridgeConfig.ssid();
        String pass = BridgeConfig.passphrase();
        int channel = BridgeConfig.wifiChannel();

        Iap2Tlv wireless = new Iap2Tlv();
        wireless.string(Iap2Constants.START_WIFI_PARAM_SSID, ssid);
        wireless.string(Iap2Constants.START_WIFI_PARAM_PASSPHRASE, pass);
        wireless.u8(Iap2Constants.START_WIFI_PARAM_CHANNEL, channel & 0xFF);
        wireless.string(Iap2Constants.START_WIFI_PARAM_IP_ADDRESSES,
                BridgeConfig.apIpv4());
        wireless.u8(Iap2Constants.START_WIFI_PARAM_SECURITY,
                Iap2Constants.WIFI_SECURITY_WPA_WPA2);

        Iap2Tlv tlv = new Iap2Tlv();
        tlv.group(Iap2Constants.START_PARAM_WIRELESS_GROUP, wireless);
        tlv.u32(Iap2Constants.START_PARAM_AIRPLAY_PORT,
                BridgeConfig.carplayAirplayPort());
        tlv.string(Iap2Constants.START_PARAM_DEVICE_ID,
                BridgeConfig.carplayDeviceIdentifier());
        tlv.string(Iap2Constants.START_PARAM_PUBLIC_KEY, publicKey);
        tlv.string(Iap2Constants.START_PARAM_SOURCE_VERSION,
                BridgeConfig.carplaySourceVersion());

        mStartSessionsSent++;
        Cplog.i(TAG, "CSM TX CarPlayStartSession (0x4301) #" + mStartSessionsSent
                + ": port=" + BridgeConfig.carplayAirplayPort()
                + " deviceId=" + BridgeConfig.carplayDeviceIdentifier()
                + " publicKeyLen=" + publicKey.length()
                + " srcVers=" + BridgeConfig.carplaySourceVersion()
                + " ssid=\"" + ssid + "\" channel=" + channel
                + " apIp=" + BridgeConfig.apIpv4());
        sendCsm(Iap2Constants.MSG_CARPLAY_START_SESSION, tlv);
        Cplog.stage("g_carplay", "SENT 0x4301 CarPlayStartSession port="
                + BridgeConfig.carplayAirplayPort() + " deviceId="
                + BridgeConfig.carplayDeviceIdentifier());
    }

    private static String describeAvailability(byte[] tlvs) {
        List<Iap2Tlv.Param> params = Iap2Tlv.parse(tlvs);
        StringBuilder sb = new StringBuilder();
        for (Iap2Tlv.Param p : params) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("group").append(p.id).append("(");
            for (Iap2Tlv.Param sub : Iap2Tlv.parse(p.value)) {
                if (sub.value.length == 1) {
                    sb.append("id").append(sub.id).append("=")
                            .append(sub.value[0] & 0xFF);
                } else {
                    sb.append("id").append(sub.id).append(":len")
                            .append(sub.value.length);
                }
                sb.append(",");
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append(")");
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    // ------------------------------------------------------------------
    // CSM encoding / helpers
    // ------------------------------------------------------------------

    private void sendCsm(int msgId, Iap2Tlv tlv) {
        byte[] tlvBytes = tlv == null ? new byte[0] : tlv.toByteArray();
        int total = Iap2Constants.CSM_HEADER_LEN + tlvBytes.length;
        byte[] csm = new byte[total];
        csm[0] = 0x40;
        csm[1] = 0x40;
        csm[2] = (byte) ((total >> 8) & 0xFF);
        csm[3] = (byte) (total & 0xFF);
        csm[4] = (byte) ((msgId >> 8) & 0xFF);
        csm[5] = (byte) (msgId & 0xFF);
        System.arraycopy(tlvBytes, 0, csm, Iap2Constants.CSM_HEADER_LEN, tlvBytes.length);
        Cplog.i(TAG, "CSM TX msgId=0x" + String.format("%04X", msgId)
                + " (" + messageName(msgId) + ") len=" + total
                + " tlv=" + tlvBytes.length + "B");
        Cplog.i(TAG, "  tx csm: " + BridgeConfig.hexAll(csm));
        Iap2Link link = mLink;
        if (link == null) {
            Cplog.e(TAG, "no link attached; dropping TX msgId=0x"
                    + String.format("%04X", msgId));
            return;
        }
        link.sendPayload(csm);
    }

    private void notifyAuthFailed() {
        StatusListener l = mStatusListener;
        if (l != null) {
            try {
                l.onAuthFailed();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static String hexIds(int[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("0x%04X", values[i]));
        }
        return sb.append(']').toString();
    }

    private static byte[] firstParamValue(byte[] tlvs) {
        List<Iap2Tlv.Param> params = Iap2Tlv.parse(tlvs);
        for (Iap2Tlv.Param p : params) {
            if (p.value.length > 0) {
                return p.value;
            }
        }
        return null;
    }

    static String messageName(int msgId) {
        switch (msgId) {
            case Iap2Constants.MSG_START_IDENTIFICATION: return "StartIdentification";
            case Iap2Constants.MSG_IDENTIFICATION_INFO: return "IdentificationInformation";
            case Iap2Constants.MSG_IDENTIFICATION_ACCEPTED: return "IdentificationAccepted";
            case Iap2Constants.MSG_IDENTIFICATION_REJECTED: return "IdentificationRejected";
            case Iap2Constants.MSG_IDENTIFICATION_CANCEL: return "IdentificationCancel";
            case Iap2Constants.MSG_REQ_AUTH_CERT: return "RequestAuthenticationCertificate";
            case Iap2Constants.MSG_AUTH_CERT: return "AuthenticationCertificate";
            case Iap2Constants.MSG_REQ_AUTH_CHALLENGE_RESP:
                return "RequestAuthenticationChallengeResponse";
            case Iap2Constants.MSG_AUTH_RESPONSE: return "AuthenticationResponse";
            case Iap2Constants.MSG_AUTH_FAILED: return "AuthenticationFailed";
            case Iap2Constants.MSG_AUTH_SUCCEEDED: return "AuthenticationSucceeded";
            case Iap2Constants.MSG_AUTH_SERIAL: return "AuthenticationSerialNumber";
            case Iap2Constants.MSG_REQ_WIFI_CONFIG:
                return "RequestAccessoryWiFiConfigurationInformation";
            case Iap2Constants.MSG_WIFI_CONFIG:
                return "AccessoryWiFiConfigurationInformation";
            case Iap2Constants.MSG_CARPLAY_AVAILABILITY: return "CarPlayAvailability";
            case Iap2Constants.MSG_CARPLAY_START_SESSION: return "CarPlayStartSession";
            case Iap2Constants.MSG_START_EA_SESSION:
                return "StartExternalAccessoryProtocolSession";
            case Iap2Constants.MSG_STOP_EA_SESSION:
                return "StopExternalAccessoryProtocolSession";
            case Iap2Constants.MSG_WIRELESS_CARPLAY_UPDATE: return "WirelessCarPlayUpdate";
            case Iap2Constants.MSG_DEVICE_TRANSPORT_IDENTIFIER:
                return "DeviceTransportIdentifierNotification";
            default: return "UNKNOWN";
        }
    }

    /** For diagnostics from BridgeService. */
    boolean isIdentified() {
        return mIdentAccepted;
    }
}
