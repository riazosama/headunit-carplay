package com.cpbridge;

/**
 * Offline self-test for the pure-Java iAP2 wire-format classes
 * (Iap2Constants / Iap2Frame / Iap2Tlv).  NOT part of the APK; run with:
 *
 *   ./client/test-protocol.sh
 *
 * Vectors come from <internal notes> (vendor libiapimpl.so RE) and
 * the USENIX VehicleSec'25 Table 5 captures quoted in
 * <internal notes>
 */
public final class ProtocolSelfTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        testChecksumsAgainstCapture();
        testStartIdentificationFrame();
        testIdentificationInformationHeader();
        testHeaderOnlyAck();
        testSynFrame();
        testTlvLengthFirst();
        testIdentificationTlvRoundtrip();
        testWifiTlvRoundtrip();
        testU32AndEmptyParams();
        testStartSessionTlv();
        testCsmReassemblyFromDeviceFragments();
        testCsmReassemblyEdgeCases();
        testDetectTemplates();
        System.out.println("PASS: " + checks + " protocol checks");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new RuntimeException("FAIL: " + what);
        }
        System.out.println("ok " + checks + ": " + what);
    }

    private static void testChecksumsAgainstCapture() {
        // StartIdentification: ff 5a 00 10 40 1e e8 01 50 | 40 40 00 06 1d 00 | 5d
        byte[] header = hex("ff 5a 00 10 40 1e e8 01");
        check(Iap2Frame.checksum8(header, 0, header.length) == 0x50,
                "header two's-complement sum8 == 0x50 (StartIdentification)");
        byte[] payload = hex("40 40 00 06 1d 00");
        check(Iap2Frame.checksum8(payload, 0, payload.length) == 0x5D,
                "payload two's-complement sum8 == 0x5d (StartIdentification CSM)");
        // AuthenticationCertificate from the same table:
        // ff 5a 03 a0 40 ea 20 01 b9 | 40 40 03 96 aa 01 ...
        byte[] authHeader = hex("ff 5a 03 a0 40 ea 20 01");
        check(Iap2Frame.checksum8(authHeader, 0, authHeader.length) == 0xB9,
                "header checksum == 0xb9 (AuthenticationCertificate)");
        // Vendor link-SYN header: ff 5a 00 17 80 00 00 00 -> csum 0x10
        byte[] synHeader = hex("ff 5a 00 17 80 00 00 00");
        check(Iap2Frame.checksum8(synHeader, 0, synHeader.length) == 0x10,
                "header checksum == 0x10 (vendor link-SYN 13-byte payload)");
    }

    private static void testStartIdentificationFrame() {
        byte[] csm = buildCsm(Iap2Constants.MSG_START_IDENTIFICATION, new Iap2Tlv());
        byte[] frame = Iap2Frame.encode(Iap2Constants.CTRL_ACK, 0x1E, 0xE8,
                1 /* capture used session byte 0x01 */, csm);
        check(hex(frame).equals("ff5a0010401ee80150404000061d005d"),
                "encoded StartIdentification frame matches USENIX capture exactly: "
                        + hex(frame));

        Iap2Frame decoded = Iap2Frame.decode(frame);
        check(decoded != null, "decode returns a frame");
        check(decoded.control == 0x40, "control == 0x40 data+ACK");
        check(decoded.sequence == 0x1E, "seq == 0x1e");
        check(decoded.acknowledgement == 0xE8, "ack == 0xe8");
        check(decoded.session == 1, "session == 1 (as in the USENIX capture)");
        check(decoded.headerChecksumOk, "header checksum verifies");
        check(decoded.payloadChecksumOk, "payload checksum verifies");
        check(decoded.totalLength == 16, "total length == 16");
        check(decoded.payload.length == 6, "payload length == 6");
    }

    private static void testIdentificationInformationHeader() {
        // ff 5a 01 40 40 e9 1e 01 1e | 40 40 01 36 1d 01 ...
        byte[] tlvBytes = new byte[0x136 - Iap2Constants.CSM_HEADER_LEN];
        byte[] csm = buildCsm(Iap2Constants.MSG_IDENTIFICATION_INFO, tlvBytes);
        byte[] frame = Iap2Frame.encode(Iap2Constants.CTRL_ACK, 0xE9, 0x1E,
                1 /* capture used session byte 0x01 */, csm);
        byte[] expectedHeader = hex("ff 5a 01 40 40 e9 1e 01 1e");
        byte[] actualHeader = slice(frame, 0, 9);
        check(hex(actualHeader).equals(hex(expectedHeader)),
                "IdentificationInformation 9-byte header matches capture: "
                        + hex(actualHeader));
        check(frame.length == 0x140,
                "IdentificationInformation frame total length == 0x140");
        Iap2Frame decoded = Iap2Frame.decode(frame);
        check(decoded != null && decoded.headerChecksumOk && decoded.payloadChecksumOk,
                "IdentificationInformation frame decodes with valid checksums");
    }

    /** Vendor ackRet: 9-byte header-only ACK, NO payload checksum byte. */
    private static void testHeaderOnlyAck() {
        byte[] ack = Iap2Frame.encode(Iap2Constants.CTRL_ACK, 0x05, 0x03,
                Iap2Constants.SESSION_LINK, null);
        check(ack.length == 9, "header-only ACK is 9 bytes, got " + ack.length);
        check(hex(ack).equals("ff5a00094005030056"),
                "ACK bytes match vendor ackRet layout: " + hex(ack));
        Iap2Frame decoded = Iap2Frame.decode(ack);
        check(decoded != null && decoded.isAckOnly(), "ACK decodes as header-only");
        check(decoded.payload.length == 0, "ACK has no payload");
        check(decoded.payloadChecksumOk, "ACK has no payload checksum (always valid)");
        check(decoded.totalLength == 9, "ACK total length == 9");
    }

    /** Vendor linkSyn: control 0x80, 13-byte payload, header starts ff5a00178000 0000 10. */
    private static void testSynFrame() {
        byte[] syn = Iap2Frame.encode(Iap2Constants.CTRL_SYN, 0, 0,
                Iap2Constants.SESSION_LINK, Iap2Constants.SYN_PAYLOAD);
        check(syn.length == 23, "SYN frame length == 9+13+1 = 23, got " + syn.length);
        check(hex(slice(syn, 0, 9)).equals("ff5a001780000000 10".replace(" ", "")),
                "SYN header bytes: " + hex(slice(syn, 0, 9)));
        check(hex(slice(syn, 9, 22)).equals("010500 00a001c80f1e03010001".replace(" ", "")),
                "SYN payload matches vendor ctor defaults: " + hex(slice(syn, 9, 22)));
        Iap2Frame decoded = Iap2Frame.decode(syn);
        check(decoded != null && decoded.control == Iap2Constants.CTRL_SYN
                        && decoded.payload.length == 13
                        && decoded.headerChecksumOk && decoded.payloadChecksumOk,
                "SYN frame decodes with control 0x80 and valid checksums");
    }

    /** Vendor Parameter::setParam: LENGTH FIRST, len includes the 4-byte header. */
    private static void testTlvLengthFirst() {
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.bytes(0x0006, new byte[] {0x01, 0x02});
        byte[] wire = tlv.toByteArray();
        check(hex(wire).equals("000600060102"),
                "TLV wire order is len-first: " + hex(wire));
        Iap2Tlv empty = new Iap2Tlv();
        empty.bytes(0x1234, new byte[0]);
        byte[] emptyWire = empty.toByteArray();
        check(hex(emptyWire).equals("00041234"),
                "empty TLV length field is 4: " + hex(emptyWire));
        java.util.List<Iap2Tlv.Param> parsed = Iap2Tlv.parse(wire);
        check(parsed.size() == 1 && parsed.get(0).id == 0x0006
                        && parsed.get(0).value.length == 2,
                "length-first TLV parses back correctly");
    }

    private static void testIdentificationTlvRoundtrip() {
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.string(0, "Sonata");
        tlv.u16List(6, new int[] {0x1D01, 0xAA01, 0xAA03, 0x5703, 0x4301});
        tlv.u8(8, 0);
        tlv.u16(9, 20);
        Iap2Tlv group = new Iap2Tlv();
        group.u8(0, 1);
        group.string(1, "blue");
        group.bytes(3, new byte[] {(byte) 0xAA, 0x00, (byte) 0xA0, 0x28, 0x51, (byte) 0x9E});
        tlv.group(17, group);

        byte[] wire = tlv.toByteArray();
        java.util.List<Iap2Tlv.Param> parsed = Iap2Tlv.parse(wire);
        check(parsed.size() == 5, "identification TLV parsed back to 5 params, got "
                + parsed.size());
        check(parsed.get(0).id == 0, "param 0 id");
        check(new String(parsed.get(0).value, 0, parsed.get(0).value.length - 1,
                java.nio.charset.StandardCharsets.UTF_8).equals("Sonata"),
                "param 0 string 'Sonata' NUL-terminated");
        check(parsed.get(1).id == 6 && parsed.get(1).value.length == 10,
                "param 6 u16 list of 5 entries");
        check(parsed.get(2).id == 8 && parsed.get(2).value.length == 1,
                "param 8 u8");
        check(parsed.get(3).id == 9 && parsed.get(3).value.length == 2,
                "param 9 u16");
        check(parsed.get(4).id == 17, "param 17 group id");
        java.util.List<Iap2Tlv.Param> subs = Iap2Tlv.parse(parsed.get(4).value);
        check(subs.size() == 3, "BT transport group has 3 sub-params");
        check(subs.get(2).id == 3 && subs.get(2).value.length == 6,
                "BT transport MAC is 6 bytes");
        String described = Iap2Tlv.describe(wire);
        check(described.contains("str=\"Sonata\""), "describe shows the name string");
        check(described.contains("0x0006"), "describe shows message list param");
    }

    /** DiPlay Iap2WirelessMessages.order: 0 BSSID, 1 SSID, 2 passphrase, 3 security, 4 channel. */
    private static void testWifiTlvRoundtrip() {
        byte[] bssid = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF};
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.bytes(0, bssid);
        tlv.string(1, "YOUR-HOTSPOT-SSID");
        tlv.string(2, "YOUR-HOTSPOT-PASSPHRASE");
        tlv.u8(3, 2);   // WPA/WPA2
        tlv.u8(4, 6);   // channel
        byte[] csm = buildCsm(Iap2Constants.MSG_WIFI_CONFIG, tlv.toByteArray());
        check(csm.length <= 0x100, "Wi-Fi CSM fits a link packet: " + csm.length + "B");
        check(((csm[2] & 0xFF) << 8 | (csm[3] & 0xFF)) == csm.length,
                "CSM declared length matches actual length");
        java.util.List<Iap2Tlv.Param> parsed = Iap2Tlv.parse(
                slice(csm, Iap2Constants.CSM_HEADER_LEN, csm.length));
        check(parsed.size() == 5, "Wi-Fi CSM parses back to 5 params");
        check(parsed.get(0).id == 0 && parsed.get(0).value.length == 6,
                "param 0 is 6-byte BSSID");
        check(parsed.get(1).id == 1
                        && parsed.get(1).value.length == "YOUR-HOTSPOT-SSID".length() + 1,
                "param 1 is NUL-terminated SSID");
        check(parsed.get(2).id == 2
                        && parsed.get(2).value.length == "YOUR-HOTSPOT-PASSPHRASE".length() + 1,
                "param 2 is NUL-terminated passphrase");
        check(parsed.get(3).id == 3 && parsed.get(3).value.length == 1
                        && (parsed.get(3).value[0] & 0xFF) == 2,
                "param 3 security u8 2 (WPA/WPA2)");
        check(parsed.get(4).id == 4 && parsed.get(4).value.length == 1
                        && (parsed.get(4).value[0] & 0xFF) == 6,
                "param 4 channel u8 6");
    }

    /** u32 and empty parameters, as used by 0x4301 (port u32, optional-empty markers). */
    private static void testU32AndEmptyParams() {
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.u32(2, 7000);
        tlv.empty(17);
        byte[] wire = tlv.toByteArray();
        check(hex(wire).equals("0008000200001b58" + "00040011"),
                "u32 param 2 = 7000 and empty param 17 encode length-first: " + hex(wire));
        java.util.List<Iap2Tlv.Param> parsed = Iap2Tlv.parse(wire);
        check(parsed.size() == 2, "u32+empty parses to 2 params");
        check(parsed.get(0).id == 2 && parsed.get(0).value.length == 4
                        && (((parsed.get(0).value[2] & 0xFF) << 8)
                        | (parsed.get(0).value[3] & 0xFF)) == 7000,
                "port u32 decodes to 7000");
        check(parsed.get(1).id == 17 && parsed.get(1).value.length == 0,
                "empty parameter has zero-length value");
    }

    /** 0x4301 wireless group + port, per DiPlay Iap2CarPlayMessages.startSession. */
    private static void testStartSessionTlv() {
        Iap2Tlv wireless = new Iap2Tlv();
        wireless.string(0, "YOUR-HOTSPOT-SSID");
        wireless.string(1, "YOUR-HOTSPOT-PASSPHRASE");
        wireless.u8(2, 6);
        wireless.string(3, "192.168.43.1");
        wireless.u8(4, 2);
        Iap2Tlv tlv = new Iap2Tlv();
        tlv.group(Iap2Constants.START_PARAM_WIRELESS_GROUP, wireless);
        tlv.u32(Iap2Constants.START_PARAM_AIRPLAY_PORT, 7000);
        tlv.string(Iap2Constants.START_PARAM_DEVICE_ID, "YOUR-UNIT-BT-MAC");
        tlv.string(Iap2Constants.START_PARAM_PUBLIC_KEY, "pk-placeholder");
        tlv.string(Iap2Constants.START_PARAM_SOURCE_VERSION, "450.14.1");
        byte[] csm = buildCsm(Iap2Constants.MSG_CARPLAY_START_SESSION, tlv.toByteArray());
        java.util.List<Iap2Tlv.Param> parsed = Iap2Tlv.parse(
                slice(csm, Iap2Constants.CSM_HEADER_LEN, csm.length));
        check(parsed.size() == 5, "0x4301 has 5 top-level params");
        check(parsed.get(0).id == 1, "0x4301 wireless group id 1");
        java.util.List<Iap2Tlv.Param> subs = Iap2Tlv.parse(parsed.get(0).value);
        check(subs.size() == 5, "wireless group has 5 sub-params");
        check(subs.get(0).id == 0 && subs.get(1).id == 1 && subs.get(2).id == 2
                        && subs.get(3).id == 3 && subs.get(4).id == 4,
                "wireless sub-param ids 0..4 in order");
        check(parsed.get(1).id == 2 && parsed.get(1).value.length == 4,
                "0x4301 airplay port param 2 is u32");
        check(parsed.get(4).id == 5, "0x4301 sourceVersion param 5");
    }

    /**
     * Exact device evidence (2026-10-25): a 42-byte 0xAA02 CSM split into three
     * 14-byte link payloads.  The reassembler must yield one CSM whose param 0
     * is the full 32-byte challenge.
     */
    private static void testCsmReassemblyFromDeviceFragments() {
        byte[] frag1 = hex("40 40 00 2a aa 02 00 24 00 00 98 98 76 ee");
        byte[] frag2 = hex("2c 1b 06 7d c2 48 b8 df 54 f9 1d 16 4f 51 75");
        byte[] frag3 = hex("c5 02 8a a8 01 4c 71 ed 7d 72 3e cd b1");
        CsmReassembler r = new CsmReassembler();
        check(r.add(1, frag1).completed.isEmpty(), "no CSM after fragment 1");
        check(r.add(1, frag2).completed.isEmpty(), "no CSM after fragment 2");
        CsmReassembler.Result done = r.add(1, frag3);
        check(done.completed.size() == 1, "CSM completed after fragment 3");
        byte[] csm = done.completed.get(0);
        check(csm.length == 42, "reassembled CSM length 42, got " + csm.length);
        check(((csm[0] & 0xFF) == 0x40) && ((csm[1] & 0xFF) == 0x40),
                "reassembled CSM starts 40 40");
        check(CsmReassembler.messageId(csm) == 0xAA02,
                "reassembled message id is 0x AA02");
        java.util.List<Iap2Tlv.Param> params = Iap2Tlv.parse(
                slice(csm, Iap2Constants.CSM_HEADER_LEN, csm.length));
        check(params.size() == 1 && params.get(0).id == 0,
                "0xAA02 has param id 0");
        check(params.get(0).value.length == 32,
                "challenge param is the full 32 bytes, got "
                        + params.get(0).value.length);
        String expectedChallenge = hex(frag1).substring(20) + hex(frag2) + hex(frag3);
        check(hex(params.get(0).value).equals(expectedChallenge),
                "challenge value is exactly the 32 continuation bytes: actual="
                        + hex(params.get(0).value) + " expected=" + expectedChallenge);
        check(done.trace.toString().contains("fragment #3"),
                "trace records fragment #3");
    }

    private static void testCsmReassemblyEdgeCases() {
        CsmReassembler r = new CsmReassembler();
        // single-packet CSM
        CsmReassembler.Result single = r.add(1, hex("40 40 00 06 1d 00"));
        check(single.completed.size() == 1
                        && CsmReassembler.messageId(single.completed.get(0)) == 0x1D00,
                "single-packet CSM 0x1D00 parses");
        // two complete CSMs in one link payload
        CsmReassembler.Result two = r.add(1,
                hex("40 40 00 06 1d 00 40 40 00 06 1d 02"));
        check(two.completed.size() == 2, "two CSMs in one payload -> 2 messages");
        check(CsmReassembler.messageId(two.completed.get(1)) == 0x1D02,
                "second CSM is 0x1D02");
        // orphan continuation
        CsmReassembler.Result orphan = r.add(2, hex("2c 1b 06 7d c2 48"));
        check(orphan.completed.isEmpty(), "orphan continuation yields no CSM");
        check(orphan.trace.toString().contains("orphan"),
                "orphan continuation is logged/traced");
        // 40 40 always starts a new CSM, even while one is pending
        CsmReassembler restart = new CsmReassembler();
        restart.add(4, hex("40 40 00 2a aa 02 00 24 00 00 98 98 76 ee"));
        CsmReassembler.Result restarted = restart.add(4, hex("40 40 00 06 1d 00"));
        check(restarted.completed.size() == 1
                        && CsmReassembler.messageId(restarted.completed.get(0)) == 0x1D00,
                "0x4040 starts a new CSM and drops the incomplete one");
        // reset drops a half-assembled CSM
        r.add(3, hex("40 40 00 2a aa 02 00 24 00 00 98 98 76 ee"));
        r.reset(3);
        CsmReassembler.Result afterReset = r.add(3, hex("2c 1b 06 7d c2 48"));
        check(afterReset.completed.isEmpty() && afterReset.trace.toString().contains("orphan"),
                "reset() drops the half-reassembled CSM");
    }

    /** Sanity: the 5 vendor detect responses are non-empty and unique. */
    private static void testDetectTemplates() {
        check(Iap2Constants.DETECT_RESPONSES.length == 5,
                "5 vendor detect-response templates");
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (byte[] rsp : Iap2Constants.DETECT_RESPONSES) {
            check(rsp.length >= 5 && rsp.length <= 8, "detect template length " + rsp.length);
            check(seen.add(hex(rsp)), "detect template " + hex(rsp) + " unique");
        }
        check(hex(Iap2Constants.DETECT_PROBE).equals("ff550200ee10"),
                "detect probe is FF 55 02 00 EE 10");
        check(Iap2Constants.SYN_PAYLOAD.length == 13, "SYN payload is 13 bytes");
    }

    // ------------------------------------------------------------------

    private static byte[] buildCsm(int msgId, Iap2Tlv tlv) {
        return buildCsm(msgId, tlv == null ? new byte[0] : tlv.toByteArray());
    }

    private static byte[] buildCsm(int msgId, byte[] tlvBytes) {
        int total = Iap2Constants.CSM_HEADER_LEN + tlvBytes.length;
        byte[] csm = new byte[total];
        csm[0] = 0x40;
        csm[1] = 0x40;
        csm[2] = (byte) ((total >> 8) & 0xFF);
        csm[3] = (byte) (total & 0xFF);
        csm[4] = (byte) ((msgId >> 8) & 0xFF);
        csm[5] = (byte) (msgId & 0xFF);
        System.arraycopy(tlvBytes, 0, csm, Iap2Constants.CSM_HEADER_LEN, tlvBytes.length);
        return csm;
    }

    private static byte[] slice(byte[] data, int off, int end) {
        byte[] out = new byte[end - off];
        System.arraycopy(data, off, out, 0, out.length);
        return out;
    }

    private static byte[] hex(String s) {
        String compact = s.replace(" ", "");
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
