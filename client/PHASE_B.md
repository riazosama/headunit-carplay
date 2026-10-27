# Phase B — own iAP2-over-Bluetooth wireless CarPlay bootstrap

## What changed and why

The vendor HAL client-transport handoff is dead on this firmware (proven on
device: `createTransport` returns an `IIAP` whose transport is NULL,
`connectTransport` returns `0x80000000` UNKNOWN_ERROR, the HAL issues **zero**
`read()` calls, `setWiFiConfigurationInfo` / `IAP2Control::setWiFiConfigInfo`
are log-only stubs). The vendor build also has **no wireless iAP2 code at all**
(no `0x5702/0x5703`, no WirelessCarPlay transport component), so the bridge
implements the iAP2 Bluetooth bootstrap itself.

Direction change after the first device test: the iPhone never dials our RFCOMM
server (`...caff`) on its own. The proven direction is **car -> iPhone**: the
bridge now dials the iPhone's iAP2 RFCOMM server (`...cafe`) as the primary
attempt, with the hidden channel-1 API as fallback, and keeps the accessory
server as a fallback.

```
BridgeService
  ├─ client-mode dialer (primary), candidate order per cycle:
  │    0. persist.cpbridge.peer_mac -> dial ONLY that MAC (fast iteration)
  │    1. names containing iPhone/Apple
  │    2. profile-connected devices
  │    3. unknown/empty names
  │    4. everything else (clearly non-Apple) -- second pass only
  │    createRfcommSocketToServiceRecord(...cafe) bounded at 3.5 s
  │    → connect() 3.5 s watchdog → fallback createRfcommSocket(1) + 2.5 s connect
  ├─ server supervisor (fallback): listenUsingRfcommWithServiceRecord(...caff)
  └─ on either socket: Iap2Link + Iap2Session (same engine, both directions)
         link:  raw detect probe → 13-byte link SYN → FF 5A frames (checksums,
                seq/ack, ACK-only len=9, retransmit 1 s ×10)
         session: 0x1D00 → 0x1D01 identification; auth stub (AUTH_SKIP);
                  0x5702 → 0x5703 Wi-Fi credentials
```

Success criterion for the first phone run: RFCOMM connect → link sync →
identification → auth → `0x5702/0x5703`. The vendor CarPlay control process
already browses `_carplay-ctrl._tcp` and takes over once the iPhone joins the
AP, so AirPlay/Bonjour are out of scope. With `AUTH_SKIP=true` an iPhone
`0xAA04` is itself proof that framing/session layers work.

## Wire format (aligned to <internal notes> + iap2_link.h)

| Layer | Fact |
|---|---|
| Checksum | 8-bit two's-complement byte sum, NOT a CRC: `hdr[8] = -(sum hdr[0..7])`, `payload[last] = -(sum payload)` |
| Link packet | `FF 5A │ len BE16 (total, incl. final checksum) │ ctrl │ seq │ ack │ sessionID │ hdrCsum │ payload │ payloadCsum` |
| Control | `0x80` = SYN, `0x40` = data **and** ACK (ack bit set on every non-SYN packet) |
| Header-only ACK | `len == 9`, **no payload checksum**; vendor `ackRet` rule: `seq = last_tx_seq-1`, `ack = peer seq` |
| Version detect | raw (unframed) probe `FF 55 02 00 EE 10`, wait ≤1 s, up to 50 tries; accept the vendor's 5 response templates (also accept anything starting `FF55/5502/5504`, logged) |
| Link SYN | ctrl `0x80`, 13-byte payload `01 05 00 00 A0 01 C8 0F 1E 03 01 00 01` (max packet 0x0FC8=4040) |
| CSM | `40 40 │ total-len BE16 (= 6 + params) │ msgId BE16 │ params` |
| Parameter TLV | **LENGTH FIRST**: `len BE16 (includes the 4-byte header; 4 = empty) │ id BE16 │ value` |
| Message ids | vendor-confirmed `0x1D01/0x1D05/0xAA01/0xAA03/0xAA06`; from protocol report (unverified in vendor): `0x1D00/0x1D02/0x1D03/0xAA00/0xAA02/0xAA04/0xAA05/0x5702/0x5703/0x4300` |
| Wi-Fi handoff | vendor has no wireless code: `0x5703` parameter ids (SSID, passphrase, channel, security, BSSID → 0,1,2,3,4) are unverified and kept as constants |
| MFi auth | vendor path uses i2c-6 0x10 (cert len 0x30, cert data 0x31+, cert serial 0x4E, challenge 0x21, resp len 0x11, 17-byte resp 0x12); not usable from an unprivileged app, so `AUTH_SKIP=true` for the first run |

Offline verification: `./client/test-protocol.sh` = **61 checks**, including
exact byte equality with the USENIX capture, the vendor 9-byte ACK layout, the
vendor SYN frame/payload, length-first TLV wire bytes and the 5 detect templates.

## Build / install / run (exact commands)

```bash
cd <repo>
./client/build.sh && ./client/test-protocol.sh
adb -s <unit-ip>:5555 install -r client/CarPlayBridge.apk

# Phase B (wireless bootstrap)
adb -s <unit-ip>:5555 shell am start-foreground-service -n com.cpbridge/.BridgeService

# live log: the ROM drops I/W from untrusted_app, all bridge lines are E
adb -s <unit-ip>:5555 logcat -v time | grep -E 'CPBRIDGE_'
# just the diagnostic funnel:
adb -s <unit-ip>:5555 logcat -v time | grep CPBRIDGE_DIAG
# full session log (survives link teardown):
adb -s <unit-ip>:5555 shell cat /sdcard/Android/data/com.cpbridge/files/cpbridge.log

# stop
adb -s <unit-ip>:5555 shell am stopservice -n com.cpbridge/.BridgeService
```

## Failure classification — grep `CPBRIDGE_DIAG` (one line per milestone)

| stage | meaning | success marker |
|---|---|---|
| `a_rfcomm_connect` | RFCOMM dial/accept | `OK client-mode peer=... mode=sdp\|channel1` (or `OK server-mode`) |
| `b_link_sync` | detect probe + link SYN | `VERSION DETECT OK ...` then `SYN/SYN-ACK EXCHANGED` then `LINK RUNNING`; `idle: no frames ...` if the phone is silent |
| `c_frame_parse` | framing/checksum | `first FF5A frame decoded: ...`; `CHECKSUM ERROR` / `undecodable FF5A` on errors |
| `d_session` | CSM messages | `RX msgId=0x1D00`, `IDENTIFICATION ACCEPTED`, `CarPlayAvailability` |
| `e_auth` | MFi auth | `RequestAuthenticationCertificate (0xAA00)`, then `AUTH REJECTED by phone (0xAA04)` expected with AUTH_SKIP |
| `f_credentials` | Wi-Fi handoff | `REQUEST AccessoryWiFiConfiguration (0x5702)`, `SENT 0x5703 ssid="YOUR-HOTSPOT-SSID" ...` |
| `g_carplay` | availability/session start | `CarPlayAvailability (0x4300)`, `SENT 0x4301 CarPlayStartSession ...` (only when a public key is configured) |

Full raw byte logs: `CPBRIDGE_LINK` (RX RAW/TX RAW + per-frame summary with
direction, ctrl, seq, ack, session, length, CSM msgId, hex).

**Dead-link watchdog**: in NORMAL, if no valid frame arrives for 8 s (or the
RFCOMM read returns EOF/IOException) the link is torn down: one
`LINK WATCHDOG: no RX for Ns in NORMAL -> closing and re-dialing` line, socket
closed, link/session/CSM reassembly/seq/ack reset, and the dialer wakes
immediately (it no longer waits out the 5 s cycle).

**Auth daemon readiness**: each auth request retries the connect every 250 ms for
up to 20 s before falling back (`AUTH daemon ready after Nms`, and throttled
`AUTH daemon not ready yet (attempt N, waited Xms)` lines).  Both the 0xAA00
certificate and the 0xAA02 signature use this window, so challenge responses are
not lost while the init service is still starting.

**Boot autostart**: `BootReceiver` starts the service unconditionally on
BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / DIRECT_BOOT_COMPLETED; the dialer reads
`persist.cpbridge.peer_mac` live, so after a reboot the bridge dials the iPhone
as soon as Bluetooth is up.

Dialing is summarised once per cycle in `CPBRIDGE_BT`:
`dial cycle: peer_mac=… strategy=… firstPass=[MAC(name), …] secondPass=[…]`.
Per-attempt budget is small so a wrong device costs <10 s: SDP resolution
3.5 s + SDP connect 3.5 s + channel-1 connect 2.5 s.  Clearly non-Apple names
are deferred to a second pass; with `peer_mac` set only that device is dialed.

Every candidate device is logged with address/name/bond state/connected flag;
dial failures log the exception class + message; channel-1 fallback is tried
after both SDP-resolution and SDP-connect failures.

## Configuration (optional; `setprop` needs root)

| property | default | meaning |
|---|---|---|
| `persist.cpbridge.ssid` | `YOUR-HOTSPOT-SSID` | SSID pushed in 0x5703 |
| `persist.cpbridge.passphrase` | `YOUR-HOTSPOT-PASSPHRASE` | passphrase pushed in 0x5703 |
| `persist.cpbridge.channel` | live `iw dev` | channel pushed in 0x5703 (0 = unknown) |
| `persist.cpbridge.bssid` | live `ip link` of ap0/wlan0 | BSSID pushed in 0x5703 |
| `persist.cpbridge.bt_mac` | `YOUR-UNIT-BT-MAC` | accessory BT MAC in identification param 17 |
| `persist.cpbridge.sdp_name` | `CarPlay` | fallback server SDP service-record name |
| `persist.cpbridge.discoverable` | unset | if `1`, request discoverable mode on start |
| `persist.cpbridge.peer_mac` | unset | dial ONLY this Bluetooth MAC, e.g. `YOUR-IPHONE-BT-MAC` |
| `persist.cpbridge.public_key` | unset | AirPlay public key for `0x4301`; unset = skip `0x4301` |
| `persist.cpbridge.airplay_port` | `7000` | `0x4301` AirPlay port |
| `persist.cpbridge.source_version` | `320.17.3` | `0x4301` source version; must match the receiver's Bonjour `srcvers` |
| `persist.cpbridge.ap_ip` | live `ip addr` of AP iface | wireless IP address list in `0x4301` |

Compile-time switches in `Iap2Constants.java`: `AUTH_SKIP`,
`AUTH_SKIP_SEND_EMPTY`, `STRING_PARAMS_NUL_TERMINATED`, `DETECT_PROBE`,
`DETECT_RESPONSES`, `SYN_PAYLOAD`, `RETRANSMIT_TIMEOUT_MS`,
`MAX_RETRANSMITS`, `WIFI_PARAM_*`.

## Not verified until the device run

1. **Client-mode dialing** — does iOS accept an RFCOMM connection to its
   `...cafe` server from an unprivileged, bonded Android app (caller must be
   connected via HFP/A2DP, which the previous test confirmed)?
2. Which RAW detect response the iPhone gives (all 5 templates + prefix rule are
   logged), and whether it answers detect at all in client mode.
3. SYN/SYN-ACK session-id mapping (`payload[8]/[9]`): we adopt `payload[8]` as
   the control session id when sane and otherwise echo whatever session id the
   phone uses on inbound CSMs — logs will show the right value.
4. Identification TLV layout (param ids/groups 17/24, NUL-terminated strings) —
   validated against the public capture format, not against the iPhone.
5. `0x5703` parameter ids/order — the vendor has no wireless reference, so this
   is the most likely remaining wire mismatch; values are logged both ways.
6. Real MFi auth — intentionally skipped (`AUTH_SKIP=true`); expect `0xAA04`
   before `0x5702`.

## Phase A (HAL probe) status

Unchanged and passing: `IIAPService.getService()` → `createCarplay()` →
`setDeviceConfigInfo(7, ro.serialno, false)` works from a normal app (uid
10044). The Phase A code paths (`PhaseA.java`, `IapClientStub.java`) remain in
the APK; `BridgeService` does not use the HAL transport APIs.
