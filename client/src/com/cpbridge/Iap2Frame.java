package com.cpbridge;

/**
 * iAP2 link-layer packet (the "FF 5A" family) with the 8-bit two's-complement
 * checksums used by AAP/iAP2.
 *
 * Layout OBS in libiapimpl.so (IAP2LinkPkt::setHeader @0xd70e0,
 * updateCheckSum @0xd6c80; see <internal notes> §1.1/§1.2):
 *
 *   off  size field
 *   0    2    SoP 0xFF 0x5A
 *   2    2    total length, big-endian (9 header + payload + 1 payload checksum)
 *   4    1    control byte: 0x80 = SYN, 0x40 = data AND ack
 *   5    1    sequence number (incremented per transmitted packet)
 *   6    1    acknowledgement number
 *   7    1    session id (0 = control session per the vendor mapping)
 *   8    1    header checksum
 *   9    n    payload (one CSM frame for the control session)
 *   last 1    payload checksum -- ABSENT when total length == 9 (header-only ACK)
 *
 * Header checksum:  csum8(bytes[0..7]) stored at byte 8 (SOF bytes included).
 * Payload checksum: csum8(payload) stored at last byte, only when total > 9.
 * csum8(x) = (byte)(-(sum of bytes)) — two's complement, NOT a CRC.
 */
final class Iap2Frame {

    static final int HEADER_LEN = Iap2Constants.LINK_HEADER_LEN;

    int totalLength;
    int control;
    int sequence;
    int acknowledgement;
    int session;
    byte[] payload = new byte[0];

    /** True when the header checksum byte made the header block sum to 0. */
    boolean headerChecksumOk;
    /** True when the payload checksum made the payload block sum to 0 (always true for 9-byte ACK). */
    boolean payloadChecksumOk;

    static int checksum8(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += data[offset + i] & 0xFF;
        }
        return (0x100 - (sum & 0xFF)) & 0xFF;
    }

    static int be16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /** True for a header-only ACK (len == 9, no payload checksum byte). */
    boolean isAckOnly() {
        return totalLength == Iap2Constants.MIN_LINK_PACKET;
    }

    /** @return frame or null when length/format is invalid. */
    static Iap2Frame decode(byte[] raw) {
        if (raw == null || raw.length < Iap2Constants.MIN_LINK_PACKET) {
            return null;
        }
        if ((raw[0] & 0xFF) != 0xFF || (raw[1] & 0xFF) != 0x5A) {
            return null;
        }
        Iap2Frame f = new Iap2Frame();
        f.totalLength = be16(raw, 2);
        if (f.totalLength != raw.length
                || f.totalLength < Iap2Constants.MIN_LINK_PACKET) {
            return null;
        }
        f.control = raw[4] & 0xFF;
        f.sequence = raw[5] & 0xFF;
        f.acknowledgement = raw[6] & 0xFF;
        f.session = raw[7] & 0xFF;
        f.headerChecksumOk = checksum8(raw, 0, HEADER_LEN - 1) == (raw[HEADER_LEN - 1] & 0xFF);
        if (f.totalLength == Iap2Constants.MIN_LINK_PACKET) {
            // header-only ACK: no payload, no payload checksum
            f.payload = new byte[0];
            f.payloadChecksumOk = true;
            return f;
        }
        int payloadLen = f.totalLength - HEADER_LEN - 1;
        if (payloadLen < 0) {
            return null;
        }
        f.payload = new byte[payloadLen];
        System.arraycopy(raw, HEADER_LEN, f.payload, 0, payloadLen);
        f.payloadChecksumOk = payloadLen == 0
                || checksum8(raw, HEADER_LEN, payloadLen) == (raw[f.totalLength - 1] & 0xFF);
        return f;
    }

    /**
     * Encode a link packet.  With an empty payload this produces a 9-byte
     * header-only ACK (no payload checksum byte), exactly like the vendor's
     * IAP2Link::ackRet.
     */
    static byte[] encode(int control, int sequence, int acknowledgement, int session,
                         byte[] payload) {
        int payloadLen = payload == null ? 0 : payload.length;
        int total = HEADER_LEN + payloadLen + (payloadLen > 0 ? 1 : 0);
        byte[] raw = new byte[total];
        raw[0] = (byte) 0xFF;
        raw[1] = (byte) 0x5A;
        raw[2] = (byte) ((total >> 8) & 0xFF);
        raw[3] = (byte) (total & 0xFF);
        raw[4] = (byte) control;
        raw[5] = (byte) sequence;
        raw[6] = (byte) acknowledgement;
        raw[7] = (byte) session;
        raw[8] = (byte) checksum8(raw, 0, HEADER_LEN - 1);
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, raw, HEADER_LEN, payloadLen);
            raw[total - 1] = (byte) checksum8(raw, HEADER_LEN, payloadLen);
        }
        return raw;
    }

    String controlString() {
        StringBuilder sb = new StringBuilder();
        if ((control & Iap2Constants.CTRL_SYN) != 0) {
            sb.append("SYN|");
        }
        if ((control & Iap2Constants.CTRL_ACK) != 0) {
            sb.append("ACK|");
        }
        if ((control & Iap2Constants.CTRL_EAK) != 0) {
            sb.append("EAK|");
        }
        if (sb.length() == 0) {
            return String.format("0x%02X", control);
        }
        return sb.substring(0, sb.length() - 1) + String.format("(0x%02X)", control);
    }

    String summary() {
        return "ctrl=" + controlString()
                + " seq=0x" + String.format("%02X", sequence)
                + " ack=0x" + String.format("%02X", acknowledgement)
                + " session=" + session
                + " len=" + totalLength
                + " payload=" + payload.length + "B"
                + (isAckOnly() ? " (ACK,no-payload-csum)" : "")
                + (headerChecksumOk ? "" : " HDR-CKSUM-BAD")
                + (payloadChecksumOk ? "" : " PAYLOAD-CKSUM-BAD");
    }
}
