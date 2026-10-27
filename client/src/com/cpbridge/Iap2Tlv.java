package com.cpbridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * iAP2 session-layer parameter TLV: u16 parameter id, u16 length (bytes),
 * value.  Strings are NUL-terminated UTF-8 (iAP2 convention); lists are
 * concatenated big-endian u16s; groups contain nested TLVs.
 *
 * Layout verified against the USENIX VehicleSec'25 captures
 * (e.g. identification information `40 40 01 36 1d 01 ...`).
 */
final class Iap2Tlv {

    static final class Param {
        final int id;
        final byte[] value;

        Param(int id, byte[] value) {
            this.id = id;
            this.value = value;
        }
    }

    private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

    byte[] toByteArray() {
        return mOut.toByteArray();
    }

    int size() {
        return mOut.size();
    }

    Iap2Tlv u8(int id, int value) {
        writeHeader(id, 1);
        mOut.write(value & 0xFF);
        return this;
    }

    Iap2Tlv u16(int id, int value) {
        writeHeader(id, 2);
        mOut.write((value >> 8) & 0xFF);
        mOut.write(value & 0xFF);
        return this;
    }

    Iap2Tlv u32(int id, long value) {
        writeHeader(id, 4);
        mOut.write((int) ((value >> 24) & 0xFF));
        mOut.write((int) ((value >> 16) & 0xFF));
        mOut.write((int) ((value >> 8) & 0xFF));
        mOut.write((int) (value & 0xFF));
        return this;
    }

    /** Empty parameter value (TLV total length 4). */
    Iap2Tlv empty(int id) {
        writeHeader(id, 0);
        return this;
    }

    Iap2Tlv bytes(int id, byte[] value) {
        byte[] v = value == null ? new byte[0] : value;
        writeHeader(id, v.length);
        mOut.write(v, 0, v.length);
        return this;
    }

    Iap2Tlv u16List(int id, int[] values) {
        int[] v = values == null ? new int[0] : values;
        writeHeader(id, v.length * 2);
        for (int value : v) {
            mOut.write((value >> 8) & 0xFF);
            mOut.write(value & 0xFF);
        }
        return this;
    }

    Iap2Tlv string(int id, String value) {
        byte[] raw = utf8(value);
        if (Iap2Constants.STRING_PARAMS_NUL_TERMINATED) {
            byte[] v = new byte[raw.length + 1];
            System.arraycopy(raw, 0, v, 0, raw.length);
            v[raw.length] = 0;
            raw = v;
        }
        writeHeader(id, raw.length);
        mOut.write(raw, 0, raw.length);
        return this;
    }

    Iap2Tlv stringList(int id, String[] values) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (values != null) {
            for (String value : values) {
                byte[] raw = utf8(value);
                buf.write(raw, 0, raw.length);
                if (Iap2Constants.STRING_PARAMS_NUL_TERMINATED) {
                    buf.write(0);
                }
            }
        }
        byte[] v = buf.toByteArray();
        writeHeader(id, v.length);
        mOut.write(v, 0, v.length);
        return this;
    }

    Iap2Tlv group(int id, Iap2Tlv sub) {
        byte[] v = sub == null ? new byte[0] : sub.toByteArray();
        writeHeader(id, v.length);
        mOut.write(v, 0, v.length);
        return this;
    }

    private static byte[] utf8(String value) {
        if (value == null) {
            return new byte[0];
        }
        try {
            return value.getBytes("UTF-8");
        } catch (Exception e) {
            return value.getBytes();
        }
    }

    /**
     * Vendor wire order is LENGTH FIRST (OBS Parameter::setParam @0xd71f8):
     * total length BE16 (includes this 4-byte header; 4 for an empty value),
     * then parameter id BE16, then the value.
     */
    private void writeHeader(int id, int length) {
        int total = length + 4;
        mOut.write((total >> 8) & 0xFF);
        mOut.write(total & 0xFF);
        mOut.write((id >> 8) & 0xFF);
        mOut.write(id & 0xFF);
    }

    // ------------------------------------------------------------------
    // parsing / logging
    // ------------------------------------------------------------------

    static List<Param> parse(byte[] data) {
        List<Param> list = new ArrayList<Param>();
        int off = 0;
        while (off + 4 <= data.length) {
            // LENGTH FIRST: total includes the 4-byte TLV header.
            int total = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
            int id = ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
            if (total < 4) {
                // malformed: record the raw tail and stop
                byte[] v = new byte[data.length - off];
                System.arraycopy(data, off, v, 0, v.length);
                list.add(new Param(id, v));
                break;
            }
            int valueLen = total - 4;
            if (off + total > data.length) {
                // tolerate a truncated tail (log shows what we got)
                int avail = data.length - (off + 4);
                byte[] v = new byte[Math.max(avail, 0)];
                if (avail > 0) {
                    System.arraycopy(data, off + 4, v, 0, avail);
                }
                list.add(new Param(id, v));
                break;
            }
            byte[] v = new byte[valueLen];
            System.arraycopy(data, off + 4, v, 0, valueLen);
            list.add(new Param(id, v));
            off += total;
        }
        return list;
    }

    private static boolean isGroupId(int id) {
        return id == Iap2Constants.IDENT_PARAM_BLUETOOTH_TRANSPORT
                || id == Iap2Constants.IDENT_PARAM_WIRELESS_CARPLAY_TRANSPORT
                || id == 10            // ExternalAccessoryProtocol
                || id == 16            // USBHostTransportComponent
                || id == 22            // LocationInformationComponent
                || id == 30;           // RouteGuidanceComponent
    }

    /** Human-readable dump, one line per parameter (groups expanded). */
    static String describe(byte[] data) {
        List<Param> params = parse(data);
        StringBuilder sb = new StringBuilder();
        for (Param p : params) {
            sb.append("\n      param 0x").append(String.format("%04X", p.id))
                    .append(" len=").append(p.value.length);
            if (isGroupId(p.id)) {
                sb.append(" group={");
                for (Param s : parse(p.value)) {
                    sb.append("\n        sub 0x").append(String.format("%04X", s.id))
                            .append("=").append(shortValue(s.id, s.value));
                }
                sb.append("}");
            } else {
                sb.append(" ").append(shortValue(p.id, p.value));
            }
        }
        return sb.toString();
    }

    private static String shortValue(int id, byte[] v) {
        switch (id) {
            case 6:
            case 7:
                return "u16list=" + u16ListString(v);
            case Iap2Constants.IDENT_PARAM_POWER_PROVIDING:
                return v.length >= 1 ? "u8=" + (v[0] & 0xFF) : "(empty)";
            case Iap2Constants.IDENT_PARAM_MAX_CURRENT:
                return v.length >= 2 ? "u16=" + u16(v) : "(empty)";
            default:
                break;
        }
        String ascii = asciiString(v);
        if (ascii != null) {
            return "str=\"" + ascii + "\"";
        }
        if (v.length == 1) {
            return "u8=" + (v[0] & 0xFF);
        }
        if (v.length == 2) {
            return "u16=" + u16(v);
        }
        return "hex=" + hex(v);
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length && i < 4096; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", data[i] & 0xff));
        }
        return sb.toString();
    }

    private static int u16(byte[] v) {
        return ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
    }

    private static String u16ListString(byte[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < v.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("0x%04X", u16(new byte[] {v[i], v[i + 1]})));
        }
        return sb.append(']').toString();
    }

    /** @return printable NUL-terminated string, else null. */
    private static String asciiString(byte[] v) {
        if (v.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (byte value : v) {
            int c = value & 0xFF;
            if (c == 0) {
                break;
            }
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
            sb.append((char) c);
            if (sb.length() > 128) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
