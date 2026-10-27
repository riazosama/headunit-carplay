package com.cpbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reassembles iAP2 control-session messages (CSM) that the iPhone fragments
 * across several link packets.
 *
 * Observed on the unit (2026-10-25): the phone advertises maxLength=65535 in its
 * link SYN yet still splits a 42-byte CSM into three 14-byte link payloads:
 *
 *   #1  40 40 00 2a aa 02 00 24 00 00 98 98 76 ee   (start, declared total 42)
 *   #2  2c 1b 06 7d c2 48 b8 df 54 f9 1d 16 4f 51 75 (continuation)
 *   #3  c5 02 8a a8 01 4c 71 ed 7d 72 3e cd b1 e2 cf (continuation)
 *
 * Rules:
 *   - a payload starting with 0x40 0x40 begins a new CSM; its declared total is
 *     the BE16 at [2..3] (includes the 6-byte CSM header);
 *   - any other payload is a continuation of the pending CSM for that session;
 *   - when buffered length reaches the declared total, emit exactly that many
 *     bytes; extra trailing bytes immediately start the next CSM (a link packet
 *     can contain a whole CSM followed by another);
 *   - fragments are normal, never malformed.
 *
 * Pure Java (no Android dependencies) so it can be unit-tested on the host.
 */
final class CsmReassembler {

    static final int MAX_CSM_BYTES = 8192;

    static final class Result {
        /** Complete CSM messages produced by this add(). */
        final List<byte[]> completed = new ArrayList<byte[]>();
        /** Human-readable fragment/completion trace for CPBRIDGE logging. */
        final List<String> trace = new ArrayList<String>();
    }

    private static final class State {
        byte[] buf = new byte[256];
        int len;
        int declared;
        int fragments;

        void reset() {
            len = 0;
            declared = 0;
            fragments = 0;
        }

        void append(byte[] data, int off, int count) {
            if (len + count > buf.length) {
                int capacity = buf.length;
                while (capacity < len + count) {
                    capacity <<= 1;
                }
                byte[] bigger = new byte[capacity];
                System.arraycopy(buf, 0, bigger, 0, len);
                buf = bigger;
            }
            System.arraycopy(data, off, buf, len, count);
            len += count;
        }

        byte[] copy() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    private final Map<Integer, State> mStates = new HashMap<Integer, State>();

    /** Feed one link-packet payload; returns any CSMs completed by it. */
    Result add(int session, byte[] payload) {
        Result result = new Result();
        if (payload == null || payload.length == 0) {
            return result;
        }
        State state = mStates.get(session);
        if (state == null) {
            state = new State();
            mStates.put(session, state);
        }

        int offset = 0;
        while (offset < payload.length) {
            int remaining = payload.length - offset;
            if (state.len == 0) {
                // Expecting a new CSM start.
                if (remaining < Iap2Constants.CSM_HEADER_LEN) {
                    result.trace.add("session=" + session + " dropping short CSM"
                            + " start fragment (" + remaining + "B) -- cannot read"
                            + " the declared length");
                    return result;
                }
                int b0 = payload[offset] & 0xFF;
                int b1 = payload[offset + 1] & 0xFF;
                if (b0 != 0x40 || b1 != 0x40) {
                    result.trace.add("session=" + session + " dropping orphan"
                            + " continuation (" + remaining
                            + "B) with no pending CSM");
                    return result;
                }
                int declared = ((payload[offset + 2] & 0xFF) << 8)
                        | (payload[offset + 3] & 0xFF);
                if (declared < Iap2Constants.CSM_HEADER_LEN
                        || declared > MAX_CSM_BYTES) {
                    result.trace.add("session=" + session + " dropping CSM with"
                            + " invalid declared length " + declared);
                    return result;
                }
                state.reset();
                state.declared = declared;
                state.fragments = 1;
                int take = Math.min(remaining, declared);
                state.append(payload, offset, take);
                offset += take;
                result.trace.add("session=" + session + " CSM fragment #1"
                        + " START declared=" + declared + " payload="
                        + payload.length + "B this=" + take + "B buffered="
                        + state.len + "/" + declared);
            } else if (remaining >= Iap2Constants.CSM_HEADER_LEN
                    && (payload[offset] & 0xFF) == 0x40
                    && (payload[offset + 1] & 0xFF) == 0x40) {
                // A new CSM start while a previous one is still incomplete: the
                // documented rule is that 0x40 0x40 always starts a new message.
                result.trace.add("session=" + session + " new CSM start while"
                        + " previous incomplete (" + state.len + "/" + state.declared
                        + ") -- resetting");
                state.reset();
                continue;
            } else {
                int need = state.declared - state.len;
                int take = Math.min(remaining, need);
                state.fragments++;
                state.append(payload, offset, take);
                offset += take;
                result.trace.add("session=" + session + " CSM fragment #"
                        + state.fragments + " CONT payload=" + payload.length
                        + "B this=" + take + "B buffered=" + state.len + "/"
                        + state.declared);
            }

            if (state.len >= state.declared) {
                byte[] complete = state.copy();
                int fragments = state.fragments;
                state.reset();
                result.completed.add(complete);
                result.trace.add("session=" + session + " CSM COMPLETE len="
                        + complete.length + " from " + fragments
                        + " fragment(s), msgId=0x"
                        + String.format("%04X", messageId(complete)));
            }
            // Any trailing bytes now start a new CSM on the next loop iteration.
        }
        return result;
    }

    /** Drops any half-reassembled CSM for a session (e.g. on reconnect). */
    void reset(int session) {
        mStates.remove(session);
    }

    void resetAll() {
        mStates.clear();
    }

    static int messageId(byte[] csm) {
        if (csm == null || csm.length < Iap2Constants.CSM_HEADER_LEN) {
            return -1;
        }
        return ((csm[4] & 0xFF) << 8) | (csm[5] & 0xFF);
    }
}
