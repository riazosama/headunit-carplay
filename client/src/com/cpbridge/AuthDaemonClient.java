package com.cpbridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Client for the root MFi auth daemon on the unit.
 *
 * Socket: /data/local/tmp/cpauth.sock (unix, filesystem namespace)
 * Request:  u8 op (1 = certificate, 2 = sign, 3 = MFi serial)
 *           u16 BE payload length
 *           payload bytes (empty for op 1/3, the challenge for op 2)
 * Response: u16 BE length + bytes; length 0 = error.
 *
 * The Apple CP2.0C coprocessor is on i2c-6 0x10 and is only usable by a root
 * process; this app (untrusted_app) therefore asks the daemon.  Signing has been
 * measured at ~420 ms with ERMOTEIO retries, so allow up to 15 s.
 */
final class AuthDaemonClient {

    private static final String TAG = BridgeConfig.TAG_AUTH;

    private volatile boolean mUnavailable;
    private volatile boolean mReadyLogged;
    private volatile long mFirstRequestAt;
    private volatile int mCertReads;
    private volatile int mSigns;

    private volatile boolean mMissingLogged;

    /**
     * Quick, non-blocking-ish check whether the daemon socket exists and accepts a
     * connection right now. Used by the dialer so it never dials the iPhone before
     * the root daemon is ready (a dial without a certificate wastes the whole
     * hotspot window and leaves a dead link behind).
     */
    static boolean socketPresent() {
        LocalSocket probe = new LocalSocket();
        try {
            probe.connect(new LocalSocketAddress(Iap2Constants.AUTH_DAEMON_SOCKET,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { probe.close(); } catch (Throwable ignored) { }
        }
    }

    boolean isUnavailable() {
        return mUnavailable;
    }

    /** @return DER certificate bytes, or null when the daemon is unavailable/error. */
    byte[] readCertificate() {
        if (mUnavailable) {
            return null;
        }
        Cplog.stage("e_auth", "AUTH daemon: requesting certificate (op=1)");
        byte[] certificate = request(Iap2Constants.AUTH_OP_CERTIFICATE, new byte[0],
                Iap2Constants.AUTH_CERT_TIMEOUT_MS);
        if (certificate == null) {
            warnMissingOnce();
            mUnavailable = true;
            return null;
        }
        mCertReads++;
        Cplog.i(TAG, "AUTH daemon certificate #" + mCertReads + ": "
                + certificate.length + "B, first16="
                + BridgeConfig.hex(certificate, 16));
        if (certificate.length != Iap2Constants.AUTH_EXPECTED_CERT_LENGTH) {
            Cplog.e(TAG, "AUTH cert length " + certificate.length
                    + " != expected " + Iap2Constants.AUTH_EXPECTED_CERT_LENGTH
                    + " (DER should start 30 82 02 5B) -- continuing, phone will"
                    + " accept/reject it");
        }
        Cplog.stage("e_auth", "AUTH daemon certificate OK: " + certificate.length
                + "B first16=" + BridgeConfig.hex(certificate, 16));
        return certificate;
    }

    /** @return signature bytes for the challenge, or null when unavailable/error. */
    byte[] signChallenge(byte[] challenge) {
        if (mUnavailable) {
            return null;
        }
        Cplog.stage("e_auth", "AUTH daemon: signing challenge (op=2) len="
                + (challenge == null ? 0 : challenge.length));
        byte[] signature = request(Iap2Constants.AUTH_OP_SIGN, challenge,
                Iap2Constants.AUTH_SIGN_TIMEOUT_MS);
        if (signature == null) {
            warnMissingOnce();
            mUnavailable = true;
            return null;
        }
        mSigns++;
        Cplog.i(TAG, "AUTH daemon signature #" + mSigns + ": " + signature.length
                + "B, first16=" + BridgeConfig.hex(signature, 16));
        if (signature.length != Iap2Constants.AUTH_EXPECTED_SIGNATURE_LENGTH) {
            Cplog.e(TAG, "AUTH signature length " + signature.length
                    + " != expected " + Iap2Constants.AUTH_EXPECTED_SIGNATURE_LENGTH
                    + " (raw ECDSA r||s) -- continuing");
        }
        Cplog.stage("e_auth", "AUTH daemon signature OK: " + signature.length + "B");
        return signature;
    }

    /** MFi serial number (ASCII) from daemon op=3; null when unavailable. */
    String readSerial() {
        if (mUnavailable) {
            return null;
        }
        byte[] serial = request(Iap2Constants.AUTH_OP_SERIAL, new byte[0],
                Iap2Constants.AUTH_CERT_TIMEOUT_MS);
        if (serial == null) {
            // optional diagnostic only: do not latch the daemon unavailable
            Cplog.w(TAG, "AUTH daemon serial request failed (optional; continuing)");
            return null;
        }
        String value;
        try {
            value = new String(serial, "US-ASCII").trim();
        } catch (Exception e) {
            value = new String(serial).trim();
        }
        Cplog.i(TAG, "AUTH daemon MFi serial: " + serial.length + "B value=\""
                + value + "\"");
        Cplog.stage("e_auth", "AUTH daemon MFi serial=\"" + value + "\"");
        return value;
    }

    private void warnMissingOnce() {
        if (mMissingLogged) {
            return;
        }
        mMissingLogged = true;
        Cplog.e(TAG, "AUTH daemon socket " + Iap2Constants.AUTH_DAEMON_SOCKET
                + " unavailable (not started? `adb root; adb shell"
                + " /data/local/tmp/authd spawn`); continuing with the link alive,"
                + " the iPhone will fail auth (0xAA04)");
        Cplog.stage("e_auth", "FALLBACK: auth daemon socket unavailable -- link"
                + " stays up, phone will reject at auth");
    }

    private byte[] request(int op, byte[] payload, long timeoutMs) {
        long attemptStart = System.currentTimeMillis();
        if (mFirstRequestAt == 0) {
            mFirstRequestAt = attemptStart;
        }
        long readyDeadline = attemptStart + Iap2Constants.AUTH_READY_DEADLINE_MS;
        LocalSocket socket = null;
        int connectAttempts = 0;
        while (true) {
            connectAttempts++;
            try {
                socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(Iap2Constants.AUTH_DAEMON_SOCKET,
                        LocalSocketAddress.Namespace.FILESYSTEM));
                if (!mReadyLogged) {
                    mReadyLogged = true;
                    long readyMs = System.currentTimeMillis() - mFirstRequestAt;
                    Cplog.i(TAG, "AUTH daemon ready after " + readyMs + "ms"
                            + " (connect attempt " + connectAttempts + ")");
                    Cplog.stage("e_auth", "AUTH daemon ready after " + readyMs + "ms");
                }
                break;
            } catch (Throwable t) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                    socket = null;
                }
                long now = System.currentTimeMillis();
                if (now >= readyDeadline) {
                    Cplog.e(TAG, "AUTH daemon not connectable after "
                            + (now - attemptStart) + "ms (" + connectAttempts
                            + " attempts): " + t.getClass().getSimpleName()
                            + ": " + t.getMessage() + " -- giving up on auth");
                    Cplog.stage("e_auth", "AUTH daemon NOT ready after "
                            + (now - attemptStart) + "ms (" + connectAttempts
                            + " attempts) -- auth fallback");
                    return null;
                }
                if (connectAttempts == 1 || connectAttempts == 2
                        || connectAttempts % 8 == 0) {
                    Cplog.w(TAG, "AUTH daemon not ready yet (attempt "
                            + connectAttempts + ", waited " + (now - attemptStart)
                            + "ms): " + t.getClass().getSimpleName() + ": "
                            + t.getMessage() + "; retrying every "
                            + Iap2Constants.AUTH_READY_RETRY_MS + "ms up to "
                            + Iap2Constants.AUTH_READY_DEADLINE_MS + "ms");
                }
                try {
                    Thread.sleep(Iap2Constants.AUTH_READY_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        try {
            socket.setSoTimeout((int) timeoutMs);
            int payloadLen = payload == null ? 0 : payload.length;
            OutputStream out = socket.getOutputStream();
            out.write(op & 0xFF);
            out.write((payloadLen >> 8) & 0xFF);
            out.write(payloadLen & 0xFF);
            if (payloadLen > 0) {
                out.write(payload, 0, payloadLen);
            }
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] header = new byte[2];
            readFully(in, header, 2);
            int len = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
            if (len == 0) {
                Cplog.e(TAG, "AUTH daemon op=" + op + " returned error (length 0)");
                return null;
            }
            byte[] response = new byte[len];
            readFully(in, response, len);
            return response;
        } catch (Throwable t) {
            Cplog.e(TAG, "AUTH daemon op=" + op + " failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new java.io.EOFException("auth daemon closed early");
            }
            off += n;
        }
    }
}
