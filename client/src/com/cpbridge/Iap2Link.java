package com.cpbridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * From-scratch iAP2 link layer over an RFCOMM socket.
 *
 * Wire/framing from the vendor RE (<internal notes> §1); the
 * handshake state machine follows the interoperating reference
 * (DiPlay Iap2LinkEngine, /tmp/Iap2LinkEngine.kt):
 *
 *   IDLE -> DETECTING (raw marker FF 55 02 00 EE 10, <=1 s resend)
 *        -> NEGOTIATING (FF5A SYN, control 0x80, 13-byte payload; resent until
 *           the peer both (a) sends its SYN/SYN-ACK and (b) acknowledges ours)
 *        -> NORMAL (data exchange)
 *
 * Critical rules:
 *   - On EVERY SYN/SYN-ACK: set lastReceivedInOrder = peer seq, then send a
 *     header-only ACK (ctrl 0x40, seq = last sent seq, ack = lastReceivedInOrder,
 *     session 0, len 9, no payload checksum).  This is what unblocks the phone.
 *   - NEGOTIATING only becomes NORMAL when the peer's ack field acknowledges our
 *     SYN sequence.
 *   - Control-session CSM messages (identification etc.) use sessionID 1; link
 *     ACKs use sessionID 0.
 *   - Outbound data: ctrl 0x40, seq incremented per send, ack = lastReceivedInOrder.
 *   - Inbound data: duplicate seq -> re-ACK and skip; in-order -> advance
 *     lastReceivedInOrder, ACK immediately, deliver.
 *   - Every raw byte RX/TX is hex-dumped; every frame gets a summary; every ACK
 *     and every duplicate gets a CPBRIDGE_DIAG line.
 */
final class Iap2Link {

    private static final String TAG = BridgeConfig.TAG_LINK;

    private static final int STATE_DETECT = 0;
    private static final int STATE_NEGOTIATING = 1;
    private static final int STATE_NORMAL = 2;

    interface Listener {
        void onPayload(int session, byte[] payload);

        void onLinkEvent(String event);

        void onLinkClosed();
    }

    private static final class Pending {
        final int sequence;
        final byte[] raw;
        long lastSent;
        int retries;

        Pending(int sequence, byte[] raw) {
            this.sequence = sequence;
            this.raw = raw;
            this.lastSent = System.currentTimeMillis();
        }
    }

    private final InputStream mIn;
    private final OutputStream mOut;
    private final Listener mListener;

    private final Object mWriteLock = new Object();
    private final Object mStateLock = new Object();
    private final Object mBootstrapLock = new Object();
    private final List<Pending> mPending = new ArrayList<Pending>();

    private volatile boolean mRunning;
    private volatile boolean mClosedNotified;
    private volatile int mState = STATE_DETECT;

    private volatile int mOurSeq;                       // next sequence number to use
    private volatile int mLastSynSeq = -1;              // sequence of our SYN
    private volatile int mLastSentSeq;                  // seq of the last packet we sent
    private volatile int mLastReceivedInOrder;          // sequence we ACK
    private volatile int mControlSessionId = Iap2Constants.SESSION_CONTROL;
    private volatile int mMaxPayload = Iap2Constants.DEFAULT_MAX_LINK_PACKET;
    private volatile boolean mPeerSyncReceived;
    private volatile boolean mSynAcked;
    private volatile boolean mLinkError;

    private volatile boolean mDetectResponseSeen;
    private volatile String mDetectResponseHex = "(none)";
    private volatile boolean mAnyRx;
    private volatile long mLastRxAt = System.currentTimeMillis();
    private volatile long mLastIdleLogAt;
    private volatile long mLastSynSentAt;

    private int mFrameCount;
    private int mRawRxLogs;
    private int mRawTxLogs;

    private byte[] mAcc = new byte[8192];
    private int mAccLen;

    private Thread mReaderThread;
    private Thread mBootstrapThread;

    Iap2Link(InputStream in, OutputStream out, Listener listener) {
        mIn = in;
        mOut = out;
        mListener = listener;
    }

    void start() {
        mOurSeq = 0;
        mLastSentSeq = 0;
        mLastSynSeq = -1;
        mRunning = true;
        mState = STATE_DETECT;
        Cplog.i(TAG, "iAP2 link starting: our initial seq=0x00, detect probe="
                + BridgeConfig.hexAll(Iap2Constants.DETECT_PROBE));
        Cplog.stage("b_link_sync", "link starting; sending version-detect probe "
                + BridgeConfig.hexAll(Iap2Constants.DETECT_PROBE));

        mReaderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readerLoop();
            }
        }, "cpbridge-iap2-rx");
        mReaderThread.setDaemon(true);
        mReaderThread.start();

        mBootstrapThread = new Thread(new Runnable() {
            @Override
            public void run() {
                bootstrapLoop();
            }
        }, "cpbridge-iap2-bootstrap");
        mBootstrapThread.setDaemon(true);
        mBootstrapThread.start();
    }

    void stop() {
        boolean wasRunning = mRunning;
        mRunning = false;
        synchronized (mBootstrapLock) {
            mBootstrapLock.notifyAll();
        }
        if (mBootstrapThread != null) {
            mBootstrapThread.interrupt();
        }
        if (mReaderThread != null) {
            mReaderThread.interrupt();
        }
        if (wasRunning) {
            Cplog.i(TAG, "iAP2 link stopped (frames=" + mFrameCount
                    + ", lastOurSeq=0x" + String.format("%02X", mOurSeq)
                    + ", lastRxInOrder=" + String.format("0x%02X", mLastReceivedInOrder)
                    + ")");
        }
    }

    boolean isRunning() {
        return mRunning;
    }

    int state() {
        return mState;
    }

    /** Send a CSM payload on the control session (sessionID 1), consuming a seq. */
    void sendPayload(byte[] payload) {
        if (payload != null && payload.length > mMaxPayload) {
            Cplog.e(TAG, "payload too large to send: " + payload.length + "B > "
                    + mMaxPayload);
            return;
        }
        if (mState != STATE_NORMAL) {
            Cplog.w(TAG, "sending CSM payload before link is NORMAL (state="
                    + stateName() + ")");
        }
        int seq = mOurSeq;
        mOurSeq = (mOurSeq + 1) & 0xFF;
        mLastSentSeq = seq;
        byte[] raw = Iap2Frame.encode(Iap2Constants.CTRL_ACK, seq,
                mLastReceivedInOrder, mControlSessionId, payload);
        boolean ok = writeRaw(raw, "TX DATA seq=0x" + String.format("%02X", seq)
                + " ack=0x" + String.format("%02X", mLastReceivedInOrder)
                + " session=" + mControlSessionId);
        if (ok && payload != null && payload.length > 0) {
            synchronized (mStateLock) {
                mPending.add(new Pending(seq, raw));
            }
        }
    }

    /**
     * Header-only ACK: ctrl 0x40, seq = last sent sequence, ack =
     * lastReceivedInOrder, session 0, length 9 (NO payload checksum).
     */
    private void sendAckRet(String reason) {
        int seq = mLastSentSeq;
        int ack = mLastReceivedInOrder;
        byte[] raw = Iap2Frame.encode(Iap2Constants.CTRL_ACK, seq, ack,
                Iap2Constants.SESSION_LINK, null);
        writeRaw(raw, "TX ACK (" + reason + ") seq=0x" + String.format("%02X", seq)
                + " ack=0x" + String.format("%02X", ack) + " session=0 len=9");
        Cplog.stage("b_link_sync", "TX ACK (" + reason + ") seq=0x"
                + String.format("%02X", seq) + " ack=0x"
                + String.format("%02X", ack) + " len=9");
    }

    private void sendSyn(String label) {
        // SYNs (including retransmissions) MUST reuse the same sequence number:
        // the peer acknowledges the first SYN, and a fresh seq per retry makes
        // that ack unmatchable (observed: peer ack=0x00 while our seq became 0x01).
        int seq = 0;
        mLastSynSeq = seq;
        mLastSentSeq = seq;
        mLastSynSentAt = System.currentTimeMillis();
        byte[] raw = Iap2Frame.encode(Iap2Constants.CTRL_SYN, seq,
                mLastReceivedInOrder, Iap2Constants.SESSION_LINK,
                Iap2Constants.SYN_PAYLOAD);
        writeRaw(raw, "TX LINK-SYN " + label + " seq=0x"
                + String.format("%02X", seq)
                + " ack=0x" + String.format("%02X", mLastReceivedInOrder)
                + " payload=" + BridgeConfig.hexAll(Iap2Constants.SYN_PAYLOAD));
    }

    private boolean writeRaw(byte[] raw, String what) {
        synchronized (mWriteLock) {
            try {
                mOut.write(raw);
                mOut.flush();
            } catch (Throwable t) {
                mLinkError = true;
                Cplog.e(TAG, what + " write failed (" + raw.length + "B): " + t);
                return false;
            }
        }
        mRawTxLogs++;
        if (raw.length <= 4200 && (mRawTxLogs <= Iap2Constants.FULL_HEX_FRAMES
                || raw.length <= 64)) {
            Cplog.i(TAG, what + " RAW " + raw.length + "B: " + BridgeConfig.hexAll(raw));
        } else {
            String all = BridgeConfig.hexAll(raw);
            Cplog.i(TAG, what + " RAW " + raw.length + "B: "
                    + all.substring(0, Math.min(160, all.length())) + "...");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // bootstrap: detect marker -> SYN -> wait for peer SYN + SYN ack
    // ------------------------------------------------------------------

    private void bootstrapLoop() {
        // ---- 1. version detect: raw marker, <=1 s wait, up to 50 tries ----
        int attempts = 0;
        while (mRunning && attempts < Iap2Constants.DETECT_ATTEMPTS
                && !mDetectResponseSeen) {
            attempts++;
            writeRaw(Iap2Constants.DETECT_PROBE,
                    "TX DETECT (" + attempts + "/" + Iap2Constants.DETECT_ATTEMPTS + ")");
            synchronized (mBootstrapLock) {
                if (!mDetectResponseSeen && !mSynAcked) {
                    try {
                        mBootstrapLock.wait(Iap2Constants.DETECT_WAIT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (!mDetectResponseSeen && mAnyRx
                    && attempts >= Iap2Constants.DETECT_EARLY_GIVE_UP_ATTEMPTS) {
                Cplog.w(TAG, "RX bytes seen but no DETECT template matched after "
                        + attempts + " probes; proceeding to link SYN");
                break;
            }
            if (!mDetectResponseSeen && !mAnyRx
                    && attempts >= Iap2Constants.DETECT_SILENT_GIVE_UP_ATTEMPTS) {
                Cplog.w(TAG, "no bytes at all after " + attempts
                        + " detect probes; proceeding to link SYN");
                break;
            }
        }
        if (!mRunning) {
            return;
        }
        Cplog.stage("b_link_sync", (mDetectResponseSeen
                ? "VERSION DETECT OK after " + attempts + " probe(s): "
                + mDetectResponseHex
                : "VERSION DETECT FAILED after " + attempts
                + " probes (proceeding to link SYN)"));

        // ---- 2. link SYN; stay NEGOTIATING until peer also acks our SYN ----
        mState = STATE_NEGOTIATING;
        int synAttempts = 0;
        while (mRunning && !mSynAcked && synAttempts < Iap2Constants.SYN_RETRIES) {
            synAttempts++;
            sendSyn(synAttempts + "/" + Iap2Constants.SYN_RETRIES);
            synchronized (mBootstrapLock) {
                if (!mSynAcked) {
                    try {
                        mBootstrapLock.wait(Iap2Constants.SYN_WAIT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
        if (!mRunning) {
            return;
        }
        if (mSynAcked) {
            Cplog.i(TAG, ">>> LINK NEGOTIATION COMPLETE: peer acked our SYN"
                    + " (ctrlSession=" + mControlSessionId
                    + ", maxPayload=" + mMaxPayload + ") <<<");
            mOurSeq = (mLastSynSeq + 1) & 0xFF;
            Cplog.stage("b_link_sync", "NEGOTIATION COMPLETE: peer acked our SYN"
                    + " -> NORMAL (ctrlSession=" + mControlSessionId
                    + ", maxPayload=" + mMaxPayload + ")");
        } else {
            Cplog.w(TAG, "peer did not ACK our SYN after " + synAttempts
                    + " attempts; staying NEGOTIATING and resending in timer");
            Cplog.stage("b_link_sync", "SYN NOT ACKED after " + synAttempts
                    + " attempts (staying NEGOTIATING)");
        }
        timerLoop();
    }

    // ------------------------------------------------------------------
    // receive path
    // ------------------------------------------------------------------

    private void readerLoop() {
        byte[] buf = new byte[4096];
        try {
            while (mRunning) {
                int n = mIn.read(buf);
                if (n < 0) {
                    Cplog.i(TAG, "RFCOMM input stream closed by peer");
                    break;
                }
                if (n == 0) {
                    continue;
                }
                mAnyRx = true;
                mLastRxAt = System.currentTimeMillis();
                mRawRxLogs++;
                if (mRawRxLogs <= Iap2Constants.FULL_HEX_FRAMES || n <= 32) {
                    Cplog.i(TAG, "RX RAW " + n + "B: " + BridgeConfig.hexAll(buf, n));
                } else {
                    Cplog.i(TAG, "RX RAW " + n + "B: " + BridgeConfig.hex(buf, n) + "...");
                }
                append(buf, n);
                parseAccumulator();
            }
        } catch (Throwable t) {
            if (mRunning) {
                Cplog.w(TAG, "RFCOMM read loop error: " + t);
            }
        } finally {
            Cplog.i(TAG, "iAP2 RX loop ended (frames=" + mFrameCount + ")");
            Cplog.stage("b_link_sync", "link read loop ended (frames="
                    + mFrameCount + ")");
            notifyClosedOnce("RFCOMM EOF/error");
        }
    }

    private void append(byte[] data, int len) {
        if (mAccLen + len > mAcc.length) {
            int capacity = mAcc.length;
            while (capacity < mAccLen + len) {
                capacity <<= 1;
            }
            byte[] bigger = new byte[capacity];
            System.arraycopy(mAcc, 0, bigger, 0, mAccLen);
            mAcc = bigger;
        }
        System.arraycopy(data, 0, mAcc, mAccLen, len);
        mAccLen += len;
    }

    private void consume(int n) {
        if (n >= mAccLen) {
            mAccLen = 0;
            return;
        }
        System.arraycopy(mAcc, n, mAcc, 0, mAccLen - n);
        mAccLen -= n;
    }

    private byte[] take(int n) {
        byte[] out = new byte[n];
        System.arraycopy(mAcc, 0, out, 0, n);
        consume(n);
        return out;
    }

    private void parseAccumulator() {
        while (mAccLen >= 2) {
            if (mState == STATE_DETECT) {
                int b0 = mAcc[0] & 0xFF;
                int b1 = mAcc[1] & 0xFF;
                if (b0 == 0xFF && b1 == 0x5A) {
                    Cplog.i(TAG, "RX FF5A before any detect response; skipping detect");
                    mState = STATE_NEGOTIATING;
                    onDetectAccepted("FF5A direct (peer skipped detect)");
                } else {
                    handleDetectBytes();
                    return;
                }
            }
            if (mAccLen < 2) {
                return;
            }
            int b0 = mAcc[0] & 0xFF;
            int b1 = mAcc[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0x5A) {
                if (mAccLen < 4) {
                    return;
                }
                int total = Iap2Frame.be16(mAcc, 2);
                if (total < Iap2Constants.MIN_LINK_PACKET
                        || total > Iap2Constants.MAX_LINK_PAYLOAD
                        + Iap2Constants.LINK_HEADER_LEN + 1) {
                    Cplog.w(TAG, "RX FF5A with impossible length " + total
                            + "; resyncing");
                    Cplog.stage("c_frame_parse", "impossible frame length " + total);
                    consume(1);
                    continue;
                }
                if (mAccLen < total) {
                    return;
                }
                byte[] raw = take(total);
                Iap2Frame frame = Iap2Frame.decode(raw);
                if (frame == null) {
                    Cplog.w(TAG, "RX undecodable FF5A frame: " + BridgeConfig.hexAll(raw));
                    Cplog.stage("c_frame_parse", "undecodable FF5A frame: "
                            + BridgeConfig.hexAll(raw));
                    continue;
                }
                mFrameCount++;
                if (mFrameCount == 1) {
                    Cplog.stage("c_frame_parse", "first FF5A frame decoded: "
                            + frame.summary());
                }
                if (!frame.headerChecksumOk || !frame.payloadChecksumOk) {
                    Cplog.stage("c_frame_parse", "CHECKSUM ERROR hdr="
                            + frame.headerChecksumOk + " payload="
                            + frame.payloadChecksumOk + " " + frame.summary());
                }
                logFrame("RX", frame, raw);
                handleFrame(frame);
                continue;
            }
            Cplog.w(TAG, "RX unexpected byte 0x" + String.format("%02X", b0)
                    + " (next 0x" + String.format("%02X", b1) + "); dropping 1 byte");
            consume(1);
        }
    }

    private void handleDetectBytes() {
        while (mAccLen >= 2) {
            int b0 = mAcc[0] & 0xFF;
            int b1 = mAcc[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0x55) {
                if (mAccLen < 6) {
                    return;
                }
                int len = ((mAcc[2] & 0xFF) == 0x04) ? 8 : 6;
                if (mAccLen < len) {
                    return;
                }
                onDetectAccepted(BridgeConfig.hexAll(take(len)));
                return;
            }
            if (b0 == 0x55) {
                int len;
                if (b1 == 0x04) {
                    len = 7;
                } else if (b1 == 0x02) {
                    len = 5;
                } else {
                    Cplog.w(TAG, "RX in DETECT state: unknown 0x55 0x"
                            + String.format("%02X", b1) + "; dropping 1 byte");
                    consume(1);
                    continue;
                }
                if (mAccLen < len) {
                    return;
                }
                onDetectAccepted(BridgeConfig.hexAll(take(len)));
                return;
            }
            Cplog.w(TAG, "RX in DETECT state: unexpected byte 0x"
                    + String.format("%02X", b0) + " (next 0x"
                    + String.format("%02X", b1) + "); dropping 1 byte");
            consume(1);
        }
    }

    private void onDetectAccepted(String hex) {
        if (mDetectResponseSeen) {
            return;
        }
        mDetectResponseSeen = true;
        mDetectResponseHex = hex;
        Cplog.i(TAG, "RX VERSION-DETECT RESPONSE: " + hex
                + " (template match=" + templateIndex(hex) + ")");
        synchronized (mBootstrapLock) {
            mBootstrapLock.notifyAll();
        }
    }

    private static int templateIndex(String hex) {
        String compact = hex.replace(" ", "").toLowerCase();
        for (int i = 0; i < Iap2Constants.DETECT_RESPONSES.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (byte b : Iap2Constants.DETECT_RESPONSES[i]) {
                sb.append(String.format("%02x", b & 0xff));
            }
            if (sb.toString().equals(compact)) {
                return i + 1;
            }
        }
        return 0;
    }

    private void logFrame(String direction, Iap2Frame frame, byte[] raw) {
        String csm = "";
        if (frame.payload != null && frame.payload.length >= Iap2Constants.CSM_HEADER_LEN
                && ((frame.payload[0] & 0xFF) == 0x40) && ((frame.payload[1] & 0xFF) == 0x40)) {
            int msgId = ((frame.payload[4] & 0xFF) << 8) | (frame.payload[5] & 0xFF);
            int declared = ((frame.payload[2] & 0xFF) << 8) | (frame.payload[3] & 0xFF);
            csm = " csm msgId=0x" + String.format("%04X", msgId)
                    + " declaredLen=" + declared;
            if (declared != frame.payload.length) {
                csm += " (CSM-LEN-MISMATCH payload=" + frame.payload.length + ")";
            }
        }
        String hex = "";
        if (mFrameCount <= Iap2Constants.FULL_HEX_FRAMES) {
            hex = "\n      raw: " + BridgeConfig.hexAll(raw);
            if (frame.payload != null && frame.payload.length > 0) {
                hex += "\n      payload: " + BridgeConfig.hexAll(frame.payload);
            }
        }
        Cplog.i(TAG, direction + " " + frame.summary() + csm + hex);
    }

    // ------------------------------------------------------------------
    // frame handling (handshake + data/ACK discipline)
    // ------------------------------------------------------------------

    private void handleFrame(Iap2Frame frame) {
        boolean syn = (frame.control & Iap2Constants.CTRL_SYN) != 0;
        boolean ackBit = (frame.control & Iap2Constants.CTRL_ACK) != 0;
        boolean eak = (frame.control & Iap2Constants.CTRL_EAK) != 0;
        boolean hasPayload = frame.payload != null && frame.payload.length > 0;
        if (eak) {
            Cplog.w(TAG, "peer sent EAK ack=0x"
                    + String.format("%02X", frame.acknowledgement));
        }

        if (syn) {
            if (mState == STATE_NEGOTIATING) {
                parseSynPayload(frame.payload);
                mPeerSyncReceived = true;
                mLastReceivedInOrder = frame.sequence & 0xFF;
                Cplog.i(TAG, "peer SYN/SYN-ACK seq=0x"
                        + String.format("%02X", frame.sequence) + " ack=0x"
                        + String.format("%02X", frame.acknowledgement)
                        + " -> ACK immediately (lastReceivedInOrder=0x"
                        + String.format("%02X", mLastReceivedInOrder) + ")");
                sendAckRet("peer SYN/SYN-ACK");
                if (ackBit) {
                    handleAcknowledgement(frame.acknowledgement);
                }
            } else {
                Cplog.i(TAG, "RX DUPLICATE SYN seq=0x"
                        + String.format("%02X", frame.sequence) + " (state="
                        + stateName() + ") -> re-ACK, skip processing");
                Cplog.stage("b_link_sync", "RX DUPLICATE SYN seq=0x"
                        + String.format("%02X", frame.sequence) + " -> re-ACK");
                sendAckRet("duplicate SYN");
            }
            return; // SYN frames never carry CSM payload
        }

        if (ackBit) {
            handleAcknowledgement(frame.acknowledgement);
        }
        if (!hasPayload) {
            // header-only ACK (len == 9)
            Cplog.i(TAG, "peer ACK frame ack=0x"
                    + String.format("%02X", frame.acknowledgement));
            return;
        }

        int distance = (frame.sequence - mLastReceivedInOrder) & 0xFF;
        if (distance == 0) {
            Cplog.i(TAG, "RX DUPLICATE data seq=0x"
                    + String.format("%02X", frame.sequence) + " -> re-ACK, skip");
            Cplog.stage("c_frame_parse", "RX DUPLICATE data seq=0x"
                    + String.format("%02X", frame.sequence) + " -> re-ACK");
            sendAckRet("duplicate data");
            return;
        }
        if (distance > 1) {
            Cplog.w(TAG, "RX out-of-order data seq=0x"
                    + String.format("%02X", frame.sequence) + " expected 0x"
                    + String.format("%02X", (mLastReceivedInOrder + 1) & 0xFF)
                    + " -> ACK current, drop");
            Cplog.stage("c_frame_parse", "RX out-of-order seq=0x"
                    + String.format("%02X", frame.sequence));
            sendAckRet("out-of-order data");
            return;
        }

        mLastReceivedInOrder = frame.sequence & 0xFF;
        if (frame.session != mControlSessionId && frame.session <= 0x0F) {
            Cplog.i(TAG, "adopting inbound control session id=" + frame.session
                    + " (was " + mControlSessionId + ")");
            mControlSessionId = frame.session;
        }
        Cplog.i(TAG, "RX data in-order seq=0x"
                + String.format("%02X", frame.sequence) + " session="
                + frame.session + " -> ACK");
        sendAckRet("data");
        if (mListener != null) {
            try {
                mListener.onPayload(frame.session, frame.payload);
            } catch (Throwable t) {
                Cplog.e(TAG, "payload listener threw", t);
            }
        }
    }

    /** NEGOTIATING -> NORMAL only when the peer's ack acknowledges our SYN. */
    private void handleAcknowledgement(int ack) {
        if (mState == STATE_NEGOTIATING) {
            boolean acked = mPeerSyncReceived && mLastSynSeq >= 0
                    && ackCovers(ack, mLastSynSeq);
            Cplog.i(TAG, "peer ack=0x" + String.format("%02X", ack)
                    + " for our SYN seq=0x" + String.format("%02X", Math.max(mLastSynSeq, 0))
                    + " -> " + (acked ? "NEGOTIATING -> NORMAL" : "not yet"));
            Cplog.stage("b_link_sync", "peer ack=0x" + String.format("%02X", ack)
                    + " ourSYN=0x" + String.format("%02X", Math.max(mLastSynSeq, 0))
                    + (acked ? " -> NORMAL" : " (not matching yet)"));
            if (acked) {
                mState = STATE_NORMAL;
                mSynAcked = true;
                synchronized (mBootstrapLock) {
                    mBootstrapLock.notifyAll();
                }
            }
        }
        synchronized (mStateLock) {
            processAcksLocked(ack);
        }
    }

    private void parseSynPayload(byte[] payload) {
        if (payload == null || payload.length < 10) {
            Cplog.w(TAG, "SYN payload short (" + (payload == null ? 0 : payload.length)
                    + "B): " + BridgeConfig.hexAll(payload));
            return;
        }
        int version = payload[0] & 0xFF;
        int maxOutgoing = payload[1] & 0xFF;
        int maxLength = Iap2Frame.be16(payload, 2);
        int retransmissionTimeout = Iap2Frame.be16(payload, 4);
        int acknowledgementTimeout = Iap2Frame.be16(payload, 6);
        int maxRetransmissions = payload[8] & 0xFF;
        int maxAcknowledgements = payload[9] & 0xFF;
        Cplog.i(TAG, "peer SYN payload: version=" + version
                + " maxOutgoing=" + maxOutgoing
                + " maxLength=" + maxLength
                + " retransmitTimeout=" + retransmissionTimeout
                + " ackTimeout=" + acknowledgementTimeout
                + " maxRetransmissions=" + maxRetransmissions
                + " maxAcknowledgements=" + maxAcknowledgements
                + " raw=" + BridgeConfig.hexAll(payload));
        int offset = 10;
        while (offset + 3 <= payload.length) {
            int id = payload[offset] & 0xFF;
            int kind = payload[offset + 1] & 0xFF;
            int ver = payload[offset + 2] & 0xFF;
            Cplog.i(TAG, "  session descriptor: id=" + id + " kind=" + kind
                    + " version=" + ver);
            if (kind == 0 && id <= 0x0F) {
                mControlSessionId = id;
            }
            offset += 3;
        }
        Cplog.stage("b_link_sync", "peer sync: ctrlSession=" + mControlSessionId
                + " maxLength=" + maxLength + " maxOutgoing=" + maxOutgoing);
        if (maxLength > Iap2Constants.LINK_HEADER_LEN + 1
                && maxLength <= 0xFFFF) {
            mMaxPayload = maxLength - Iap2Constants.LINK_HEADER_LEN - 1;
        }
    }

    private boolean ackCovers(int ack, int sequence) {
        return ((ack - sequence) & 0xFF) < 0x80;
    }

    private void processAcksLocked(int ack) {
        for (int i = mPending.size() - 1; i >= 0; i--) {
            Pending p = mPending.get(i);
            if (ackCovers(ack, p.sequence)) {
                mPending.remove(i);
            }
        }
    }

    // ------------------------------------------------------------------
    // timers: SYN retry while NEGOTIATING, data retransmit, idle warning
    // ------------------------------------------------------------------

    /** Exactly one onLinkClosed() per link, whatever discovered the death. */
    private void notifyClosedOnce(String reason) {
        boolean notify;
        synchronized (mStateLock) {
            notify = !mClosedNotified;
            mClosedNotified = true;
            mRunning = false;
        }
        synchronized (mBootstrapLock) {
            mBootstrapLock.notifyAll();
        }
        if (notify) {
            Cplog.i(TAG, "link dead (" + reason + ") -> notifying service");
            if (mListener != null) {
                try {
                    mListener.onLinkClosed();
                } catch (Throwable t) {
                    Cplog.e(TAG, "onLinkClosed listener threw", t);
                }
            }
        }
    }

    private void timerLoop() {
        while (mRunning) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();

            if (mState == STATE_NEGOTIATING && mRunning
                    && now - mLastSynSentAt >= 1000L) {
                sendSyn("retry while NEGOTIATING");
            }

            long idle = now - mLastRxAt;

            // DEAD-LINK WATCHDOG: NORMAL + no valid frame for 8 s means the peer is
            // gone (a healthy link exchanges CSM/ACKs; before this the socket death
            // was indistinguishable from idle and the dialer never retried).
            if (mState == STATE_NORMAL && mRunning
                    && idle > Iap2Constants.LINK_WATCHDOG_MS) {
                Cplog.e(TAG, "LINK WATCHDOG: no RX for " + (idle / 1000)
                        + "s in NORMAL -> closing and re-dialing");
                Cplog.stage("b_link_sync", "LINK WATCHDOG: no RX for "
                        + (idle / 1000) + "s in NORMAL -> closing + re-dialing");
                notifyClosedOnce("watchdog no-RX " + (idle / 1000) + "s");
                return;
            }

            if (idle > 10000L && now - mLastIdleLogAt > 10000L) {
                mLastIdleLogAt = now;
                Cplog.w(TAG, "no bytes from phone for " + (idle / 1000)
                        + "s (state=" + stateName() + ", frames=" + mFrameCount + ")");
                Cplog.stage("b_link_sync", "idle: no frames from phone for "
                        + (idle / 1000) + "s (state=" + stateName()
                        + ", frames=" + mFrameCount + ")");
            }

            List<byte[]> resend = null;
            synchronized (mStateLock) {
                for (int i = mPending.size() - 1; i >= 0; i--) {
                    Pending p = mPending.get(i);
                    if (now - p.lastSent < Iap2Constants.RETRANSMIT_TIMEOUT_MS) {
                        continue;
                    }
                    if (p.retries >= Iap2Constants.MAX_RETRANSMITS) {
                        mPending.remove(i);
                        Cplog.e(TAG, "no ACK for seq=0x"
                                + String.format("%02X", p.sequence) + " after "
                                + Iap2Constants.MAX_RETRANSMITS + " retransmits");
                        continue;
                    }
                    p.retries++;
                    p.lastSent = now;
                    if (resend == null) {
                        resend = new ArrayList<byte[]>();
                    }
                    resend.add(p.raw);
                }
            }
            if (resend != null) {
                for (byte[] raw : resend) {
                    Iap2Frame f = Iap2Frame.decode(raw);
                    Cplog.i(TAG, "TX retransmit seq=0x"
                            + String.format("%02X", f == null ? 0 : f.sequence));
                    writeRaw(raw, "TX-RETRANSMIT");
                }
            }
        }
        Cplog.i(TAG, "iAP2 timer loop ended");
    }

    private String stateName() {
        switch (mState) {
            case STATE_DETECT: return "DETECT";
            case STATE_NEGOTIATING: return "NEGOTIATING";
            case STATE_NORMAL: return "NORMAL";
            default: return String.valueOf(mState);
        }
    }

    boolean hasLinkError() {
        return mLinkError;
    }
}
