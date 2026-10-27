package com.cpbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase B: wireless CarPlay Bluetooth bootstrap, implemented from scratch.
 *
 * Direction (proven on device): the iPhone does NOT dial our RFCOMM server on
 * its own, so the bridge PRIMARILY DIALS the iPhone's iAP2 RFCOMM server
 * (UUID ...cafe), with a hidden channel-1 fallback when SDP fails.  The
 * accessory-side RFCOMM server (...caff) is kept running as a fallback.
 *
 * Once a socket exists — either direction — the same engine runs:
 *   Iap2Link:      raw detect probe FF 55 02 00 EE 10 -> 13-byte link SYN ->
 *                  FF 5A framing / two's-complement checksums / seq+ack / ACK /
 *                  retransmit, every byte hex-logged;
 *   Iap2Session:   CSM identification (0x1D00 -> 0x1D01), auth stub
 *                  (AUTH_SKIP=true; real MFi later), and (if the phone asks)
 *                  the Wi-Fi credential handoff (0x5702 -> 0x5703).
 *
 * The vendor CarPlay control process already browses _carplay-ctrl._tcp and
 * takes over once the phone joins the AP, so AirPlay/Bonjour are out of scope.
 *
 * Diagnostics: every milestone also emits a CPBRIDGE_DIAG line with
 *   [stage=a_rfcomm_connect|b_link_sync|c_frame_parse|d_session|e_auth|f_credentials]
 *
 * Start manually (never auto-started by Phase A):
 *   adb shell am start-foreground-service -n com.cpbridge/.BridgeService
 * Stop:
 *   adb shell am stopservice -n com.cpbridge/.BridgeService
 *   adb shell am startservice -a com.cpbridge.STOP -n com.cpbridge/.BridgeService
 */
public final class BridgeService extends Service {

    private static final String CHANNEL_ID = "cpbridge";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.cpbridge.STOP";
    private static final String PLACEHOLDER_MAC = "02:00:00:00:00:00";

    private ExecutorService mWork;
    private Handler mMain;

    private volatile boolean mRunning;
    private volatile boolean mShutdown;

    private final Object mLinkLock = new Object();
    private final Object mDialLock = new Object();

    private volatile Iap2Link mLink;
    private volatile Iap2Session mSession;
    private volatile BluetoothSocket mActiveSocket;
    private volatile String mActiveDirection = "";
    private volatile String mPeerId = "";
    /** Set when a link dies so waitDial() returns immediately and we re-dial. */
    private volatile boolean mRetryNow;

    private volatile BluetoothServerSocket mServerSocket;

    private BroadcastReceiver mBtStateReceiver;

    private final Iap2Session.StatusListener mSessionStatus = new Iap2Session.StatusListener() {
        @Override
        public void onIdentified() {
            updateNotification("iPhone identified -- waiting for auth/Wi-Fi request");
        }

        @Override
        public void onAuthFailed() {
            updateNotification("iAP2 auth failed (AUTH_SKIP=true, MFi needed)");
        }

        @Override
        public void onWifiSent() {
            updateNotification("Wi-Fi credentials sent to iPhone");
        }

        @Override
        public void onLinkClosed() {
            BridgeService.this.onLinkClosed();
        }
    };

    // ==================================================================
    // lifecycle
    // ==================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Cplog.init(this);
        mMain = new Handler(Looper.getMainLooper());
        mWork = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        startForegroundInternal("starting");
        getSharedPreferences("cpbridge", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        registerBtStateReceiver();
        Cplog.i(BridgeConfig.TAG_SVC, "BridgeService created (enabled=true)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Cplog.i(BridgeConfig.TAG_SVC, "STOP requested via intent");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!mRunning) {
            mRunning = true;
            mWork.execute(new Runnable() {
                @Override
                public void run() {
                    startBridge();
                }
            });
        } else {
            Cplog.i(BridgeConfig.TAG_SVC, "onStartCommand: already running");
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Cplog.i(BridgeConfig.TAG_SVC, "BridgeService onDestroy");
        shutdown();
        super.onDestroy();
    }

    // ==================================================================
    // start-up
    // ==================================================================

    private void startBridge() {
        Cplog.i(BridgeConfig.TAG_SVC,
                "=== CPBRIDGE Phase B start (own iAP2 stack, client-mode dial primary)"
                        + " pid=" + android.os.Process.myPid() + " ===");
        Cplog.i(BridgeConfig.TAG_SVC, "flags: AUTH_SKIP=" + Iap2Constants.AUTH_SKIP
                + " authDaemon=" + Iap2Constants.AUTH_DAEMON_SOCKET
                + " fallbackToEmpty=" + Iap2Constants.AUTH_FALLBACK_TO_EMPTY
                + " stringNul=" + Iap2Constants.STRING_PARAMS_NUL_TERMINATED);
        Cplog.i(BridgeConfig.TAG_SVC, "config: ssid=\"" + BridgeConfig.ssid()
                + "\" passLen=" + BridgeConfig.passphrase().length()
                + " dialUuid=" + Iap2Constants.IPHONE_IAP2_UUID
                + " serverUuid=" + Iap2Constants.ACCESSORY_IAP2_UUID);
        Cplog.stage("a_rfcomm_connect", "service start; looking for an iPhone");
        try {
            wireBluetooth();
            startServerSupervisor();
            startDialer();
        } catch (Throwable t) {
            Cplog.e(BridgeConfig.TAG_SVC, "startBridge failed", t);
            updateNotification("failed: " + t);
        }
    }

    private void wireBluetooth() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Cplog.e(BridgeConfig.TAG_BT, "BluetoothAdapter.getDefaultAdapter() = null"
                    + " (no BT hardware/service); supervisor will keep retrying");
            return;
        }
        Cplog.i(BridgeConfig.TAG_BT, "adapter name=" + safeName(adapter)
                + " address=" + safeAddress(adapter)
                + " state=" + btStateName(adapter.getState())
                + " enabled=" + adapter.isEnabled()
                + " scanMode=" + safeScanMode(adapter));
        if (!adapter.isEnabled()) {
            Cplog.i(BridgeConfig.TAG_BT, "adapter off -- requesting enable()");
            try {
                boolean requested = adapter.enable();
                Cplog.i(BridgeConfig.TAG_BT, "adapter.enable() -> " + requested);
            } catch (Throwable t) {
                Cplog.w(BridgeConfig.TAG_BT, "adapter.enable() failed", t);
            }
            for (int i = 0; i < 50 && !adapter.isEnabled(); i++) {
                sleepQuietly(200);
            }
            Cplog.i(BridgeConfig.TAG_BT, "after enable wait: enabled="
                    + adapter.isEnabled() + " state=" + btStateName(adapter.getState()));
        }
        if (BridgeConfig.getPropBool("persist.cpbridge.discoverable", false)) {
            // BluetoothAdapter#setScanMode(int,int) is @hide, not in android.jar.
            try {
                Method m = BluetoothAdapter.class.getMethod("setScanMode", int.class);
                Object r = m.invoke(adapter,
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                Cplog.i(BridgeConfig.TAG_BT,
                        "setScanMode(CONNECTABLE_DISCOVERABLE) -> " + r);
            } catch (Throwable t) {
                Cplog.w(BridgeConfig.TAG_BT, "setScanMode failed", t);
            }
        }
    }

    // ==================================================================
    // client mode: dial the iPhone's iAP2 RFCOMM server (...cafe)
    //
    // Candidate strategy (fast, because non-iPhones cannot have an iAP2
    // service and each wasted attempt costs seconds):
    //   0. persist.cpbridge.peer_mac set -> dial ONLY that MAC
    //   1. devices whose name contains iPhone/Apple (every cycle)
    //   2. currently profile-connected devices
    //   3. devices with an unknown/empty name
    //   4. everything else (clearly non-Apple names) -- second pass only
    // ==================================================================

    private static final class DialPlan {
        final List<BluetoothDevice> firstPass = new ArrayList<BluetoothDevice>();
        final List<BluetoothDevice> secondPass = new ArrayList<BluetoothDevice>();
        String strategy;

        DialPlan(String strategy) {
            this.strategy = strategy;
        }
    }

    private void startDialer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                dialerLoop();
            }
        }, "cpbridge-iap2-dialer");
        t.setDaemon(true);
        t.start();
        Cplog.i(BridgeConfig.TAG_BT, "client-mode dialer thread started (uuid="
                + Iap2Constants.IPHONE_IAP2_UUID
                + ", peer_mac=" + (BridgeConfig.peerMac() == null ? "unset"
                : BridgeConfig.peerMac()) + ")");
    }

    private void dialerLoop() {
        // Wait for the MFi auth daemon BEFORE dialing: the root daemon can start a
        // few seconds after boot (init service), and dialing without it guarantees
        // an empty certificate, an authentication failure and a dead session.
        long daemonDeadline = System.currentTimeMillis() + 120000L;
        while (mRunning && !mShutdown && !AuthDaemonClient.socketPresent()) {
            if (System.currentTimeMillis() > daemonDeadline) {
                Cplog.w(BridgeConfig.TAG_AUTH,
                        "auth daemon still absent after 120s; dialing anyway");
                break;
            }
            Cplog.i(BridgeConfig.TAG_AUTH,
                    "waiting for auth daemon before dialing (socket "
                            + Iap2Constants.AUTH_DAEMON_SOCKET + ")");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            }
        }

        while (mRunning && !mShutdown) {
            if (hasActiveLink()) {
                waitDial(2000);
                continue;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Cplog.i(BridgeConfig.TAG_BT, "dialer: adapter "
                        + (adapter == null ? "missing" : btStateName(adapter.getState()))
                        + "; waiting");
                waitDial(2000);
                continue;
            }
            DialPlan plan = buildDialPlan(adapter);
            logDialCycle(plan);
            for (BluetoothDevice device : plan.firstPass) {
                if (!mRunning || mShutdown || hasActiveLink()) {
                    break;
                }
                attemptDial(adapter, device);
            }
            if (mRunning && !mShutdown && !hasActiveLink()
                    && !plan.secondPass.isEmpty()) {
                Cplog.i(BridgeConfig.TAG_BT, "dial cycle: second pass ("
                        + plan.secondPass.size() + " non-Apple-looking candidate(s))");
                for (BluetoothDevice device : plan.secondPass) {
                    if (!mRunning || mShutdown || hasActiveLink()) {
                        break;
                    }
                    attemptDial(adapter, device);
                }
            }
            if (!hasActiveLink()) {
                Cplog.stage("a_rfcomm_connect", "cycle finished without a link;"
                        + " retry in " + (Iap2Constants.DIAL_RETRY_INTERVAL_MS / 1000) + "s");
                waitDial(Iap2Constants.DIAL_RETRY_INTERVAL_MS);
            }
        }
        Cplog.i(BridgeConfig.TAG_BT, "dialer loop ended");
    }

    private void logDialCycle(DialPlan plan) {
        String peerMac = BridgeConfig.peerMac();
        String line = "dial cycle: peer_mac=" + (peerMac == null ? "unset" : peerMac)
                + " strategy=" + plan.strategy
                + " firstPass=[" + describeDevices(plan.firstPass) + "]"
                + " secondPass=[" + describeDevices(plan.secondPass) + "]";
        Cplog.i(BridgeConfig.TAG_BT, line);
        Cplog.stage("a_rfcomm_connect", line);
    }

    private DialPlan buildDialPlan(BluetoothAdapter adapter) {
        List<BluetoothDevice> connected = collectConnectedDevices();
        List<BluetoothDevice> bonded = collectBondedDevices(adapter);
        String peerMac = BridgeConfig.peerMac();

        if (peerMac != null) {
            BluetoothDevice match = findDeviceByMac(peerMac, connected, bonded);
            DialPlan plan = new DialPlan("peer_mac_override=" + peerMac);
            if (match != null) {
                plan.firstPass.add(match);
                Cplog.i(BridgeConfig.TAG_BT, "peer_mac override matched: "
                        + safeAddress(match) + " name=" + safeName(match));
            } else {
                plan.strategy = "peer_mac_override=" + peerMac
                        + " (NO MATCH -> normal ordering)";
                Cplog.w(BridgeConfig.TAG_BT, "peer_mac=" + peerMac
                        + " not found among bonded/connected devices;"
                        + " falling back to name/connected ordering");
                addStrategisedCandidates(plan, connected, bonded);
            }
            return plan;
        }
        DialPlan plan = new DialPlan(
                "appleName > connected > unknownName; second pass = other named");
        addStrategisedCandidates(plan, connected, bonded);
        return plan;
    }

    private void addStrategisedCandidates(DialPlan plan,
                                          List<BluetoothDevice> connected,
                                          List<BluetoothDevice> bonded) {
        LinkedHashMap<String, BluetoothDevice> all =
                new LinkedHashMap<String, BluetoothDevice>();
        for (BluetoothDevice d : connected) {
            if (d != null) {
                all.put(deviceKey(d), d);
            }
        }
        for (BluetoothDevice d : bonded) {
            if (d != null && !all.containsKey(deviceKey(d))) {
                all.put(deviceKey(d), d);
            }
        }
        LinkedHashMap<String, BluetoothDevice> appleNames =
                new LinkedHashMap<String, BluetoothDevice>();
        LinkedHashMap<String, BluetoothDevice> connectedOthers =
                new LinkedHashMap<String, BluetoothDevice>();
        LinkedHashMap<String, BluetoothDevice> unknownNames =
                new LinkedHashMap<String, BluetoothDevice>();
        LinkedHashMap<String, BluetoothDevice> clearlyNotApple =
                new LinkedHashMap<String, BluetoothDevice>();
        for (java.util.Map.Entry<String, BluetoothDevice> e : all.entrySet()) {
            BluetoothDevice d = e.getValue();
            String key = e.getKey();
            String name = safeName(d);
            boolean connectedNow = isConnectedDevice(d, connected);
            if (isAppleName(name)) {
                appleNames.put(key, d);
            } else if (connectedNow) {
                connectedOthers.put(key, d);
            } else if (!hasUsableName(name)) {
                unknownNames.put(key, d);
            } else {
                clearlyNotApple.put(key, d);
            }
        }
        plan.firstPass.addAll(appleNames.values());
        plan.firstPass.addAll(connectedOthers.values());
        plan.firstPass.addAll(unknownNames.values());
        plan.secondPass.addAll(clearlyNotApple.values());
        if (!clearlyNotApple.isEmpty()) {
            Cplog.i(BridgeConfig.TAG_BT, "deferring " + clearlyNotApple.size()
                    + " clearly non-Apple device(s) to the second pass (they cannot"
                    + " expose an iAP2 service)");
        }
    }

    private List<BluetoothDevice> collectConnectedDevices() {
        List<BluetoothDevice> connected = new ArrayList<BluetoothDevice>();
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (bm == null) {
                Cplog.w(BridgeConfig.TAG_BT,
                        "BluetoothManager null; cannot query connected profiles");
                return connected;
            }
            int[] profiles = {
                    BluetoothProfile.HEADSET,   // 1
                    BluetoothProfile.A2DP,      // 2
                    12,                         // AVRCP controller (hidden constant)
            };
            for (int profile : profiles) {
                try {
                    List<BluetoothDevice> devs = bm.getConnectedDevices(profile);
                    if (devs == null || devs.isEmpty()) {
                        Cplog.i(BridgeConfig.TAG_BT,
                                "getConnectedDevices(profile=" + profile + ") = empty");
                        continue;
                    }
                    for (BluetoothDevice d : devs) {
                        if (d != null && !connected.contains(d)) {
                            connected.add(d);
                            Cplog.i(BridgeConfig.TAG_BT, "profile " + profile
                                    + " connected: " + safeAddress(d) + " ("
                                    + safeName(d) + ")");
                        }
                    }
                } catch (Throwable t) {
                    Cplog.w(BridgeConfig.TAG_BT, "getConnectedDevices(profile="
                            + profile + ") failed: " + t);
                }
            }
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_BT, "collectConnectedDevices failed: " + t);
        }
        return connected;
    }

    private List<BluetoothDevice> collectBondedDevices(BluetoothAdapter adapter) {
        List<BluetoothDevice> bonded = new ArrayList<BluetoothDevice>();
        try {
            java.util.Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) {
                bonded.addAll(set);
            }
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_BT, "getBondedDevices failed: " + t);
        }
        Cplog.i(BridgeConfig.TAG_BT, "bonded devices: " + bonded.size());
        return bonded;
    }

    private static BluetoothDevice findDeviceByMac(String peerMac,
                                                   List<BluetoothDevice> connected,
                                                   List<BluetoothDevice> bonded) {
        for (BluetoothDevice d : connected) {
            if (macMatches(peerMac, safeAddress(d))) {
                return d;
            }
        }
        for (BluetoothDevice d : bonded) {
            if (macMatches(peerMac, safeAddress(d))) {
                return d;
            }
        }
        return null;
    }

    private static boolean macMatches(String peerMac, String address) {
        if (peerMac == null || address == null) {
            return false;
        }
        String normalized = address.trim().toUpperCase(java.util.Locale.US)
                .replace('-', ':');
        return peerMac.equals(normalized);
    }

    private static boolean isConnectedDevice(BluetoothDevice device,
                                             List<BluetoothDevice> connected) {
        for (BluetoothDevice d : connected) {
            if (d.equals(device) || macMatches(safeAddress(d), safeAddress(device))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableName(String name) {
        return name != null && name.length() > 0 && !"?".equals(name);
    }

    private static boolean isAppleName(String name) {
        if (!hasUsableName(name)) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.contains("iphone") || lower.contains("ipad")
                || lower.contains("ipod") || lower.contains("apple");
    }

    private static String describeDevices(List<BluetoothDevice> devices) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (BluetoothDevice d : devices) {
            if (shown >= 12) {
                sb.append(", +").append(devices.size() - shown).append(" more");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(safeAddress(d)).append("(").append(safeName(d)).append(")");
            shown++;
        }
        return sb.toString();
    }

    private void attemptDial(BluetoothAdapter adapter, BluetoothDevice device) {
        String address = safeAddress(device);
        String name = safeName(device);
        Cplog.stage("a_rfcomm_connect", "DIAL " + address + " (" + name
                + ") via SDP " + Iap2Constants.IPHONE_IAP2_UUID);
        Cplog.i(BridgeConfig.TAG_BT, "dial attempt peer=" + address + " name=" + name
                + " bond=" + bondStateName(device));
        try {
            adapter.cancelDiscovery();
        } catch (Throwable ignored) {
        }

        BluetoothSocket socket = null;
        String mode = "sdp";
        boolean connected = false;

        // 1) SDP resolution to the iPhone's iAP2 UUID (bounded by our own thread;
        //    BluetoothSocket#connect has no timeout, BluetoothDevice SDP fetch is
        //    a blocking call).
        socket = createSdpSocketWithTimeout(device,
                Iap2Constants.SDP_RESOLVE_TIMEOUT_MS);
        if (socket != null) {
            try {
                Cplog.i(BridgeConfig.TAG_BT, "connecting (sdp) peer=" + address + " ...");
                connectWithTimeout(socket, Iap2Constants.ATTEMPT_CONNECT_TIMEOUT_MS);
                connected = true;
            } catch (Throwable t) {
                Cplog.w(BridgeConfig.TAG_BT, "SDP connect failed peer=" + address + ": "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                Cplog.stage("a_rfcomm_connect", "SDP connect failed peer=" + address
                        + " (" + t.getClass().getSimpleName() + "); trying channel-1");
            }
        }
        if (!connected) {
            closeQuietly(socket);
            // 2) hidden channel-1 fallback (iPhone SDP record reportedly uses ch 1)
            socket = createChannel1Socket(device);
            mode = "channel1";
            if (socket != null) {
                try {
                    Cplog.i(BridgeConfig.TAG_BT, "connecting (channel1) peer="
                            + address + " ...");
                    connectWithTimeout(socket,
                            Iap2Constants.CHANNEL1_CONNECT_TIMEOUT_MS);
                    connected = true;
                } catch (Throwable t) {
                    Cplog.w(BridgeConfig.TAG_BT, "channel1 connect failed peer="
                            + address + ": " + t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                    Cplog.stage("a_rfcomm_connect", "FAILED peer=" + address
                            + " mode=channel1 " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }
        }
        if (!connected) {
            closeQuietly(socket);
            Cplog.stage("a_rfcomm_connect", "FAILED peer=" + address
                    + " (SDP+channel-1 exhausted)");
            return;
        }
        Cplog.i(BridgeConfig.TAG_BT, ">>> RFCOMM CTRL CONNECTED (client mode) peer="
                + address + " mode=" + mode + " <<<");
        Cplog.stage("a_rfcomm_connect", "OK client-mode peer=" + address
                + " mode=" + mode);
        adoptSocket(socket, "client", address);
    }

    /**
     * BluetoothDevice#createRfcommSocketToServiceRecord() blocks on SDP and has
     * no timeout knob, so run it on a throwaway thread and bound the wait.  If
     * it completes after we gave up, the socket is closed on the spot.
     */
    private BluetoothSocket createSdpSocketWithTimeout(BluetoothDevice device,
                                                       long timeoutMs) {
        final BluetoothSocket[] holder = new BluetoothSocket[1];
        final Throwable[] error = new Throwable[1];
        final Object lock = new Object();
        final boolean[] timedOut = {false};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket created = null;
                try {
                    created = device.createRfcommSocketToServiceRecord(
                            UUID.fromString(Iap2Constants.IPHONE_IAP2_UUID));
                } catch (Throwable e) {
                    error[0] = e;
                }
                boolean late;
                synchronized (lock) {
                    holder[0] = created;
                    late = timedOut[0];
                }
                if (late && created != null) {
                    Cplog.w(BridgeConfig.TAG_BT, "SDP create finished after timeout;"
                            + " closing late socket");
                    closeQuietly(created);
                }
            }
        }, "cpbridge-sdp-create");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            if (t.isAlive()) {
                timedOut[0] = true;
                Cplog.w(BridgeConfig.TAG_BT, "SDP resolution timed out after "
                        + timeoutMs + " ms");
                return null;
            }
            if (error[0] != null) {
                Cplog.w(BridgeConfig.TAG_BT, "SDP createRfcommSocketToServiceRecord"
                        + " failed: " + error[0]);
                return null;
            }
            if (holder[0] != null) {
                Cplog.i(BridgeConfig.TAG_BT,
                        "createRfcommSocketToServiceRecord(...cafe) OK");
            }
            return holder[0];
        }
    }

    private BluetoothSocket createChannel1Socket(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            Object obj = m.invoke(device, Iap2Constants.LEGACY_RFCOMM_CHANNEL);
            Cplog.i(BridgeConfig.TAG_BT, "createRfcommSocket(int "
                    + Iap2Constants.LEGACY_RFCOMM_CHANNEL + ") via reflection -> " + obj);
            return (BluetoothSocket) obj;
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_BT, "reflection createRfcommSocket failed: " + t);
            return null;
        }
    }

    /** connect() with a watchdog that closes the socket after a timeout. */
    private void connectWithTimeout(BluetoothSocket socket, long timeoutMs)
            throws Exception {
        final BluetoothSocket s = socket;
        final boolean[] finished = {false};
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (finished) {
                    if (!finished[0]) {
                        Cplog.w(BridgeConfig.TAG_BT, "connect timed out after "
                                + timeoutMs + " ms -- closing socket");
                        closeQuietly(s);
                    }
                }
            }
        }, "cpbridge-connect-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            socket.connect();
        } finally {
            synchronized (finished) {
                finished[0] = true;
            }
            watchdog.interrupt();
        }
    }

    // ==================================================================
    // server mode fallback: iPhone dials our ...caff server
    // ==================================================================

    private void startServerSupervisor() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                serverSupervisor();
            }
        }, "cpbridge-bt-server");
        t.setDaemon(true);
        t.start();
        Cplog.i(BridgeConfig.TAG_BT, "fallback RFCOMM server supervisor thread started");
    }

    private void serverSupervisor() {
        int failures = 0;
        while (mRunning && !mShutdown) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                sleepQuietly(2000);
                continue;
            }
            BluetoothServerSocket server = null;
            try {
                UUID uuid = UUID.fromString(Iap2Constants.ACCESSORY_IAP2_UUID);
                server = adapter.listenUsingRfcommWithServiceRecord(
                        BridgeConfig.sdpName(), uuid);
                mServerSocket = server;
                failures = 0;
                Cplog.i(BridgeConfig.TAG_BT, "RFCOMM server listening (fallback): SDP name=\""
                        + BridgeConfig.sdpName() + "\" uuid=" + uuid
                        + " secure=true.");
                acceptLoop(server, adapter);
            } catch (Throwable t) {
                failures++;
                Cplog.w(BridgeConfig.TAG_BT, "RFCOMM server error #" + failures + ": " + t);
            } finally {
                mServerSocket = null;
                closeQuietly(server);
            }
            if (mRunning && !mShutdown) {
                sleepQuietly(Math.min(1000L * Math.max(failures, 1), 5000L));
            }
        }
        Cplog.i(BridgeConfig.TAG_BT, "BT server supervisor ended");
    }

    private void acceptLoop(BluetoothServerSocket server, BluetoothAdapter adapter) {
        while (mRunning && !mShutdown) {
            if (!adapter.isEnabled()) {
                return;
            }
            BluetoothSocket socket;
            try {
                socket = server.accept();
            } catch (Throwable t) {
                if (mRunning && !mShutdown) {
                    Cplog.w(BridgeConfig.TAG_BT, "accept() ended: " + t);
                }
                return;
            }
            BluetoothDevice remote = socket.getRemoteDevice();
            String peer = peerIdFor(safeAddress(remote));
            Cplog.i(BridgeConfig.TAG_BT, ">>> RFCOMM CTRL CONNECTED (server mode) peer="
                    + peer + " <<<");
            Cplog.stage("a_rfcomm_connect", "OK server-mode peer=" + peer);
            adoptSocket(socket, "server", peer);
        }
    }

    // ==================================================================
    // shared link adoption + close handling
    // ==================================================================

    private boolean hasActiveLink() {
        synchronized (mLinkLock) {
            return mLink != null;
        }
    }

    private void adoptSocket(BluetoothSocket socket, String direction, String peer) {
        synchronized (mLinkLock) {
            if (mLink != null) {
                Cplog.w(BridgeConfig.TAG_BT, "link already active (dir="
                        + mActiveDirection + "); closing duplicate " + direction
                        + " socket peer=" + peer);
                closeQuietly(socket);
                return;
            }
            Iap2Session session = new Iap2Session();
            session.setStatusListener(mSessionStatus);
            Iap2Link link;
            try {
                link = new Iap2Link(socket.getInputStream(), socket.getOutputStream(),
                        session);
            } catch (Throwable t) {
                Cplog.e(BridgeConfig.TAG_BT, "failed to get RFCOMM streams", t);
                Cplog.stage("a_rfcomm_connect", "FAILED to open streams: " + t);
                closeQuietly(socket);
                return;
            }
            session.attach(link);
            mSession = session;
            mLink = link;
            mActiveSocket = socket;
            mActiveDirection = direction;
            mPeerId = peer;
            try {
                link.start();
                session.start();
            } catch (Throwable t) {
                Cplog.e(BridgeConfig.TAG_BT, "failed to start iAP2 link", t);
                mLink = null;
                mSession = null;
                closeQuietly(socket);
                return;
            }
        }
        Cplog.i(BridgeConfig.TAG_BT, "iAP2 engine attached over " + direction
                + " socket peer=" + peer);
        updateNotification("iAP2 link up (" + direction + ") with "
                + (peer.length() > 0 ? peer : "iPhone"));
    }

    private void onLinkClosed() {
        boolean cleared = false;
        synchronized (mLinkLock) {
            Iap2Link link = mLink;
            if (link != null) {
                if (link.isRunning()) {
                    link.stop();
                }
                Cplog.i(BridgeConfig.TAG_BT, "clearing dead link (dir="
                        + mActiveDirection + "): reset link/session/CSM state,"
                        + " seq/ack fresh on next dial");
                Cplog.stage("b_link_sync", "link torn down (dir=" + mActiveDirection
                        + ") -> re-dialing immediately");
                mLink = null;
                mSession = null;
                closeQuietly(mActiveSocket);
                mActiveSocket = null;
                mActiveDirection = "";
                cleared = true;
            }
        }
        mRetryNow = true;
        if (cleared) {
            updateNotification("link dead -- re-dialing");
        }
        synchronized (mDialLock) {
            mDialLock.notifyAll();
        }
    }

    /** Sleep up to ms, but return immediately when a link just died. */
    private void waitDial(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        synchronized (mDialLock) {
            while (mRunning && !mShutdown) {
                if (mRetryNow) {
                    mRetryNow = false;
                    return;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    mRetryNow = false;
                    return;
                }
                try {
                    mDialLock.wait(Math.min(left, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ==================================================================
    // Bluetooth adapter state receiver
    // ==================================================================

    private void registerBtStateReceiver() {
        try {
            mBtStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    Cplog.i(BridgeConfig.TAG_BT, "ACTION_STATE_CHANGED -> "
                            + btStateName(state) + " (raw=" + state + ")");
                    if (state == BluetoothAdapter.STATE_OFF
                            || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        closeQuietly(mServerSocket);
                        closeQuietly(mActiveSocket);
                        Iap2Link link = mLink;
                        if (link != null) {
                            link.stop();
                        }
                    }
                    synchronized (mDialLock) {
                        mDialLock.notifyAll();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiver(mBtStateReceiver, filter);
            Cplog.i(BridgeConfig.TAG_BT, "registered ACTION_STATE_CHANGED receiver");
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_BT, "registerReceiver failed", t);
        }
    }

    // ==================================================================
    // shutdown / notification plumbing
    // ==================================================================

    private void shutdown() {
        if (mShutdown) {
            Cplog.i(BridgeConfig.TAG_SVC, "shutdown: already shutting down");
            return;
        }
        mShutdown = true;
        mRunning = false;
        Cplog.i(BridgeConfig.TAG_SVC, "shutting down (identified="
                + (mSession != null && mSession.isIdentified()) + ")");
        synchronized (mDialLock) {
            mDialLock.notifyAll();
        }
        if (mBtStateReceiver != null) {
            try {
                unregisterReceiver(mBtStateReceiver);
            } catch (Throwable ignored) {
            }
            mBtStateReceiver = null;
        }
        closeQuietly(mServerSocket);
        mServerSocket = null;
        closeQuietly(mActiveSocket);
        mActiveSocket = null;

        synchronized (mLinkLock) {
            Iap2Link link = mLink;
            if (link != null) {
                link.stop();
            }
            mLink = null;
            mSession = null;
        }
        getSharedPreferences("cpbridge", MODE_PRIVATE).edit()
                .putBoolean("enabled", false).apply();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Cplog.i(BridgeConfig.TAG_SVC, "shutdown complete");
    }

    private void createNotificationChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "CarPlay Bridge", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_SVC, "createNotificationChannel failed", t);
        }
    }

    private void startForegroundInternal(String text) {
        Notification n = buildNotification(text);
        if (n == null) {
            // Must not throw here (would crash the service at start).
            n = new Notification();
            n.icon = android.R.drawable.ic_dialog_info;
            n.tickerText = "CarPlay Bridge";
        }
        try {
            startForeground(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Cplog.e(BridgeConfig.TAG_SVC, "startForeground failed", t);
        }
    }

    private Notification buildNotification(String text) {
        try {
            Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CarPlay Bridge")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true);
            return b.build();
        } catch (Throwable t) {
            Cplog.w(BridgeConfig.TAG_SVC, "buildNotification failed", t);
            return null;
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                Notification n = buildNotification(text);
                if (n != null) {
                    nm.notify(NOTIFICATION_ID, n);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String peerIdFor(String rawAddress) {
        if (rawAddress == null || rawAddress.length() == 0
                || "?".equals(rawAddress) || PLACEHOLDER_MAC.equals(rawAddress)) {
            Cplog.w(BridgeConfig.TAG_BT, "peer BT MAC unavailable (raw=" + rawAddress
                    + "); using empty peer id");
            return "";
        }
        return rawAddress;
    }

    private static String btStateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF: return "STATE_OFF";
            case BluetoothAdapter.STATE_TURNING_OFF: return "STATE_TURNING_OFF";
            case BluetoothAdapter.STATE_ON: return "STATE_ON";
            case BluetoothAdapter.STATE_TURNING_ON: return "STATE_TURNING_ON";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /** BluetoothDevice#getAddress may be the 02:00 placeholder for unprivileged
     * apps; key by name in that case so multiple bonded devices stay distinct. */
    private static String deviceKey(BluetoothDevice device) {
        String address = safeAddress(device);
        if (address == null || address.length() == 0
                || PLACEHOLDER_MAC.equals(address) || "?".equals(address)) {
            return "name:" + safeName(device) + "@" + device.getBondState();
        }
        return address;
    }

    private static String bondStateName(BluetoothDevice device) {
        try {
            int state = device.getBondState();
            switch (state) {
                case BluetoothDevice.BOND_BONDED: return "BONDED";
                case BluetoothDevice.BOND_BONDING: return "BONDING";
                case BluetoothDevice.BOND_NONE: return "NONE";
                default: return String.valueOf(state);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeAddress(BluetoothAdapter adapter) {
        try {
            return adapter.getAddress();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeName(BluetoothAdapter adapter) {
        try {
            return adapter.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeScanMode(BluetoothAdapter adapter) {
        try {
            return String.valueOf(adapter.getScanMode());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(BluetoothServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
