package com.cpbridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

/**
 * Logging helper with two workarounds for this specific ROM:
 *
 * 1. The vendor image's logd drops INFO/DEBUG/WARN messages from untrusted_app
 *    (protocol 3: only error-priority entries from a third-party uid survive in
 *    `logcat`; verified with `log -p i/w/e` as the app uid).  Every bridge log
 *    line therefore goes out at ERROR priority, prefixed with its real level.
 *
 * 2. Everything is also mirrored to a file so the full session can be pulled
 *    even when logcat dies (e.g. BT teardown at the end of a wireless CarPlay
 *    handshake):
 *
 *      adb shell cat /sdcard/Android/data/com.cpbridge/files/cpbridge.log
 *      adb shell run-as com.cpbridge cat files/cpbridge.log
 *
 * Tags remain the documented CPBRIDGE_* tags.
 */
final class Cplog {

    private static final Object LOCK = new Object();
    private static Writer sWriter;
    private static String sPath = "(none)";
    private static boolean sInitialized;

    private Cplog() {
    }

    static void init(Context context) {
        synchronized (LOCK) {
            if (sInitialized || context == null) {
                return;
            }
            sInitialized = true;
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) {
                    dir = context.getFilesDir();
                }
                if (dir != null) {
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    File f = new File(dir, "cpbridge.log");
                    if (f.exists() && f.length() > 512 * 1024) {
                        f.delete();
                    }
                    sWriter = new FileWriter(f, true);
                    sPath = f.getAbsolutePath();
                }
            } catch (Throwable t) {
                sWriter = null;
                sPath = "(open failed: " + t + ")";
            }
            Log.e(BridgeConfig.TAG_SVC, "CPBRIDGE log file: " + sPath);
            append("I", BridgeConfig.TAG_SVC, "log file " + sPath, null);
        }
    }

    static void i(String tag, String msg) {
        emit(tag, "I", msg, null);
    }

    static void d(String tag, String msg) {
        emit(tag, "D", msg, null);
    }

    static void w(String tag, String msg) {
        emit(tag, "W", msg, null);
    }

    static void w(String tag, String msg, Throwable t) {
        emit(tag, "W", msg, t);
    }

    static void e(String tag, String msg) {
        emit(tag, "E", msg, null);
    }

    static void e(String tag, String msg, Throwable t) {
        emit(tag, "E", msg, t);
    }

    /**
     * Structured stage marker for the device run.  The Lead greps
     * CPBRIDGE_DIAG to get exactly where the bootstrap stopped:
     *   a_rfcomm_connect   -> b_link_sync -> c_frame_parse -> d_session
     *   -> e_auth -> f_credentials
     */
    static void stage(String stage, String detail) {
        emit("CPBRIDGE_DIAG", "E", "[stage=" + stage + "] " + detail, null);
    }

    private static void emit(String tag, String level, String msg, Throwable t) {
        String stack = (t == null) ? null : Log.getStackTraceString(t);
        String line = level + " " + msg + (stack == null ? "" : "\n" + stack);
        // error priority on purpose: see class comment
        Log.e(tag, line);
        append(level, tag, msg, stack);
    }

    private static void append(String level, String tag, String msg, String stack) {
        synchronized (LOCK) {
            if (sWriter == null) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                sWriter.write(String.valueOf(now));
                sWriter.write(' ');
                sWriter.write(level);
                sWriter.write('/');
                sWriter.write(tag);
                sWriter.write(": ");
                sWriter.write(msg == null ? "" : msg);
                sWriter.write('\n');
                if (stack != null) {
                    sWriter.write(stack);
                    sWriter.write('\n');
                }
                sWriter.flush();
            } catch (Throwable ignored) {
            }
        }
    }
}
