---
name: enable-carplay
description: >-
  Enable Apple CarPlay that an aftermarket Android head unit already shipped with but left
  switched off — wired over USB, and (experimental) wireless over Wi-Fi. Use this whenever
  someone wants to turn on, fix, or get CarPlay working on an aftermarket Android car stereo or
  head unit (Autochips AC8257 / BWIC "Yato1 A100" and similar rootable test-keys units), e.g.
  "CarPlay doesn't show up on my head unit", "enable CarPlay on my Android car stereo", "my
  aftermarket stereo says it supports CarPlay but it's disabled", "get Apple CarPlay working
  over ADB", "wireless CarPlay doesn't work", "make CarPlay work over Wi-Fi with my iPhone". The
  wired path backs up, finds the Apple MFi chip's real I2C address, switches CarPlay on, installs
  the on-screen app and verifies with screenshots. The wireless path builds and installs the
  Bluetooth iAP2 bridge (com.cpbridge) and the root MFi auth helper (authd), flips the vendor
  wireless switch once, reconnects ADB through the unit's own hotspot, sets the per-unit values,
  and verifies the handshake end to end. It only works on units that have the genuine Apple chip,
  and it never forges or bypasses authentication. Always asks before each change and keeps a
  restore path.
---

# Enable Apple CarPlay on an Autochips head unit (wired or wireless)

Many aftermarket Android head units built on Autochips AC8257 boards (BWIC "Yato1 A100" and
relatives) already have Apple's CarPlay software and a **genuine Apple MFi authentication chip**
on the board — but ship with CarPlay switched off, pointed at the wrong chip address, or missing
the app that draws it. This skill turns CarPlay on live over ADB, using the hardware the unit
already has. Nothing is forged or bypassed: the genuine chip performs Apple's authentication.

There are two paths, with very different risk:

| Path | What works | Risk |
|---|---|---|
| **Wired (USB)** | Screen, touch, Siri, car audio, opens on plug-in | Low, fully reversible |
| **Wireless (Wi-Fi)** | Screen, touch, opens on car start; **audio stays on the iPhone** | High: one `/vendor` write, unit leaves your network, unit-specific |

Work live on the device, verify each step with a screenshot pulled from the unit, and **ask the
user before every change**. The whole point is a change that stays reversible.

## Read this first

- **Root-only.** Both paths need `adb root` on a debuggable (`userdebug` / AOSP test-keys)
  build. If `adb root` is refused, stop — none of this applies.
- **Wired is the safe path. Wireless is not.** The wireless path writes
  `ro.atc.carplay.wireless=1` into `/vendor/build.prop`. On the reference unit, repeated
  `/vendor` writes corrupted that partition (ext4 I/O errors) and a factory reset does not fix
  it. Do the edit once, never unattended, and never on a car the user depends on.
- **Genuine chip only.** Never fabricate a certificate, replay a signature, or spoof MFi. If the
  unit has no Apple MFi chip, wireless cannot work and wired authentication will fail; stop and
  say so.
- **Get explicit consent for the wireless path.** The user must ask for it, understand that the
  unit can be left unreachable, and have a way to recover it (a second machine that can join the
  unit's hotspot, or the power-cycle boot window).
- **Never publish the unit's identity.** MFi certificate and serial, AirPlay pairing keychain,
  Bluetooth/Wi-Fi MAC addresses, hotspot SSID/passphrase, serial number. Keep them on the unit
  and out of git, logs, screenshots and chat.
- **Reconfigure, never remove.** Never uninstall or disable a system package the unit needs to
  boot. Every change here is a property, a setting, or a file that can be put back.

## Choose the path

| The user wants | Do this |
|---|---|
| CarPlay working, especially with sound | Wired only. Say plainly that wireless audio does not work yet. |
| Wireless CarPlay | Wired first (it proves the chip, the screen app and the vendor service), then the wireless path below. |
| A locked unit / no root | Stop. Explain that this needs a debug build. |
| A unit with no Apple MFi chip | Stop. Wireless is impossible and wired auth will fail. |

## Prerequisites (both paths)

- You are running from the `CarPlayAlreadyThere` repo root, so `carplay-screen/CarPlay.apk`,
  `restore-wired.sh`, `client/`, `authd/` and `scripts/` are to hand.
- The laptop and the head unit are on the same Wi-Fi.
- `adb` installed (`brew install android-platform-tools`, or Google's platform-tools).
- Developer options → USB/Network debugging enabled on the unit (Settings → About → tap **Build
  number** 7 times, while parked).
- The unit is parked and powered; the user is present and can watch the screen.

## Step 1 — Connect over ADB

Ask for the unit's IP (or find it), then:

```bash
adb connect <UNIT-IP>:5555
adb root
adb connect <UNIT-IP>:5555   # reconnect: adbd restarts after `adb root`
```

Confirm `adb shell true` works and `adb root` reports running as root. If root is refused, stop —
the unit is locked.

macOS note: if adb reports "No route to host" while the port is open, that is macOS Local Network
privacy, not the unit. Grant the terminal Local Network access, or use the Python `adb-shell`
package in a venv.

## Step 2 — Back up before any change

Put backups in `backup/` (the repo `.gitignore` keeps it out of git — it holds device-specific
values):

```bash
mkdir -p backup
adb shell getprop > backup/getprop.backup.txt
adb shell settings list global > backup/settings-global.backup.txt
adb shell settings list secure > backup/settings-secure.backup.txt
adb shell pm list packages > backup/packages.backup.txt
adb pull /vendor/build.prop backup/vendor-build.prop   # wireless path needs this
```

`restore-wired.sh` reads `backup/getprop.backup.txt` to put the chip address back, and the
wireless revert needs the `/vendor` backup if the edit has to be undone by hand.

## Step 3 — Diagnose (both paths)

Find out what is actually wrong before touching anything, and report it to the user first.

1. **Is CarPlay software present?**
   ```bash
   adb shell pm list packages | grep -iE "carplay|autochips|airplay"
   adb shell ls /system/app /system/priv-app /vendor/app 2>/dev/null | grep -iE "carplay|autochips"
   ```
2. **What is CarPlay pointed at now?**
   ```bash
   adb shell getprop | grep -iE "carplay|i2c|mfi"
   adb shell settings get global carplay_on
   ```
   Note `persist.carplay.i2c.slave_addr` and whether the CarPlay switch is on.
3. **Find the Apple MFi chip's REAL I2C address.** This is the usual wired culprit: the config
   points at the wrong address, so authentication silently fails. The chip sits on one of the
   `/dev/i2c-*` buses (bus 6 on the reference unit).
   ```bash
   adb shell ls -l /dev/i2c-*
   adb shell "command -v i2cdetect && i2cdetect -y -r 6"   # if i2c-tools is present
   ```
   The MFi chip answers at a fixed 7-bit address (`0x10` on the reference unit — where the config
   was wrongly set to `0x11`). If `i2cdetect` is not on the unit, read the vendor CarPlay config
   for the address it probes and watch `adb logcat` for MFi/auth errors while a phone is plugged
   in. **Do not assume `0x10`** — confirm it for this unit.
4. **Is the on-screen CarPlay app installed?**
   ```bash
   adb shell pm list packages | grep -i carplayscreen
   ```
   `app.carplayscreen` draws the CarPlay video and forwards touch. Without it, CarPlay can
   authenticate but nothing shows on screen. The repo ships `carplay-screen/CarPlay.apk`.
5. **Wireless-only checks** (only if the user asked for wireless; report before proceeding):
   ```bash
   adb shell 'cat /vendor/build.prop | grep atc.carplay.wireless'  # vendor gate must exist
   adb shell 'getprop ro.build.type'                               # userdebug or eng
   adb shell 'getprop ro.boot.veritymode'                          # disabled
   adb shell 'ls -l /dev/i2c-6'                                    # MFi bus
   adb shell 'mount | grep " /vendor "'                            # remount-able?
   ```
   If `ro.atc.carplay.wireless` is not in `/vendor/build.prop`, this unit does not have the
   vendor wireless path — stop, the bridge cannot substitute for it.

Summarise for the user: software present (y/n), current chip address vs. the real one, CarPlay
switch state, screen app present (y/n), and (for wireless) whether the vendor gate, verity state
and `/vendor` writability allow it. Only the mismatches need fixing.

## Wired path

Do this first even if the user wants wireless: it proves the chip works, installs the screen app
and confirms the vendor service runs. It also gives you a working baseline to fall back to.

### Step 4 — Enable (confirm each change first)

1. **Point CarPlay at the real chip address** (persistent prop, value in **decimal** — `0x10` = 16):
   ```bash
   adb shell setprop persist.carplay.i2c.slave_addr <DECIMAL-ADDR>
   ```
2. **Switch CarPlay on, persistently:**
   ```bash
   adb shell settings put global carplay_on 1
   ```
3. **If the screen app is missing, install the repo's prebuilt one:**
   ```bash
   adb install carplay-screen/CarPlay.apk
   ```
   If the install fails because this unit's CarPlay framework differs, rebuild from source against
   the unit's own classes — see `carplay-screen/build.sh` and the API stubs under
   `carplay-screen/stubs/`. Only sign and install as a system app if the unit's test-keys build
   allows it.
4. **Make it survive a reboot.** `persist.` props and `settings put global` survive reboots;
   confirm with `adb reboot`, reconnect, and re-check the values from Step 3.

After each change, pull a screenshot and show the user:

```bash
adb shell screencap -p /sdcard/cp.png && adb pull /sdcard/cp.png backup/
```

### Step 5 — Verify (wired)

Have the user plug in an iPhone over USB. CarPlay should come up with touch, Siri, and audio
through the car speakers. Pull a screenshot to confirm.

- Authenticates but screen stays blank → screen app missing or not started (Step 4.3).
- Never authenticates → chip address still wrong (Step 3.3); re-probe and correct it.

Undo everything with `./restore-wired.sh <adb-serial>`.

## Wireless path

**Experimental, and only on explicit request.** The firmware ships the receiver half of wireless
CarPlay (AirPlay receiver inside `com.autochips.carplay`, hotspot, `_airplay._tcp` on port 7000)
but not the client that brings the iPhone in. `client/` + `authd/` supply it. Expect to debug:
the per-unit values in W2 are the usual reason a session silently fails.

Before starting, say clearly:

- Screen and touch work; **audio stays on the iPhone**. Do not promise wireless sound.
- The unit leaves the user's Wi-Fi and raises its own hotspot; ADB has to be re-established
  through that hotspot.
- The `/vendor` edit below is the dangerous step. It can corrupt the partition and must be done
  once, with the unit on power.

### W0 — Confirm the user can recover the unit

Ask: how will they reach ADB if the hotspot comes up and the unit is off the home network? The
good answers are a second laptop/phone that can join the unit's AP, or the power-cycle boot
window (20–60 seconds of home Wi-Fi at boot). If there is no recovery path, do not do the flip.

### W1 — Build the helpers

```bash
./client/build.sh            # -> client/CarPlayBridge.apk
./client/test-protocol.sh    # offline checks; must pass before flashing the unit
./authd/build.sh             # -> authd/authd, authd/cpauth_client
```

Requirements: Android SDK build-tools 35, API 35 platform, JDK 17, NDK 26.1. If the tools live
elsewhere, set `ANDROID_SDK_ROOT`, `JAVA_HOME` or `NDK` rather than editing the scripts.

### W2 — Collect this unit's values

The bridge ships with placeholders. Override every value with the unit's own, or it will
authenticate and then silently fail to start the session.

| Value | How to get it | Used for |
|---|---|---|
| Hotspot SSID / passphrase | Unit Settings → Hotspot, or `dumpsys wifi` with root | Wi-Fi credentials (`0x5703`) |
| Hotspot channel | The channel the unit's AP actually runs on; pin it | Credentials, session request |
| AP interface MAC | `ip link show ap0` once the hotspot is up (not the BT MAC) | BSSID / device ID |
| Hotspot IP | The gateway the laptop gets after joining the AP; it changes on every start | Session request |
| Unit Bluetooth MAC | `settings get secure bluetooth_address` | Identification |
| iPhone Bluetooth MAC | From the unit's bonded devices | Which phone the bridge dials |
| AirPlay public key | `/data/vendor/carplay/default.keychain`, "AirPlay Pairing Identity" item: `hex(private32)+"+"+hex(public32)`; use the public half (64 hex chars) | Session request (`0x4301`) |
| AirPlay source version | The version the running receiver reports (e.g. `320.17.3`) | Session request |
| AirPlay port | 7000 | Session request |

```bash
adb root
adb pull /data/vendor/carplay/default.keychain
plutil -p default.keychain | grep -A6 "AirPlay Pairing Identity"
```

**Never copy the private half, the certificate or the keychain into the repo, a log, an issue or
a chat.** Do not screenshot it either.

### W3 — Turn on wireless mode (the one dangerous edit)

Do this once, with explicit user confirmation, and keep the unit on power:

```bash
adb root
adb remount
adb pull /vendor/build.prop backup/vendor-build.prop
adb shell 'sed -i "s/^ro.atc.carplay.wireless=0$/ro.atc.carplay.wireless=1/" /vendor/build.prop'
adb shell 'sync; grep -n atc.carplay.wireless /vendor/build.prop'   # must say =1
adb reboot
```

If `adb remount` is unavailable: `adb shell 'mount -o rw,remount /vendor'`.

`settings put global carplay_on 0` does **not** clear this — the build.prop line is the switch.

### W4 — Reconnect ADB through the unit's own hotspot

After the reboot the unit leaves the home network and raises its AP. Join the AP from the laptop,
read the gateway, and connect to that:

```bash
# macOS
networksetup -setairportnetwork en0 "<HOTSPOT-SSID>" "<HOTSPOT-PASSPHRASE>"
netstat -nr | grep default          # gateway column = unit AP IP

# Linux
nmcli device wifi connect "<HOTSPOT-SSID>" password "<HOTSPOT-PASSPHRASE>"
ip route | grep default

# Windows: connect in the Wi-Fi list, then
ipconfig | findstr /i "Default Gateway"

adb connect <AP-IP>:5555
adb shell true
```

The AP IP is **not** reliably `192.168.43.1` — Android randomizes it each start. If you are
locked out, power-cycle and catch the boot window; `./scripts/wireless-recovery.sh` waits for the
unit and flips the property back.

### W5 — Deploy the auth helper and the bridge, set the values

```bash
# authd must live in /data/local/tmp so it can dlopen the vendor MFi library
adb push authd/authd authd/cpauth_client /data/local/tmp/
adb shell chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client
adb shell /data/local/tmp/authd spawn
adb shell /data/local/tmp/authd status

adb install -r client/CarPlayBridge.apk

adb shell setprop persist.cpbridge.peer_mac       "<IPHONE-BT-MAC>"
adb shell setprop persist.cpbridge.ssid           "<HOTSPOT-SSID>"
adb shell setprop persist.cpbridge.passphrase     "<HOTSPOT-PASSPHRASE>"
adb shell setprop persist.cpbridge.channel        "<AP-CHANNEL>"
adb shell setprop persist.cpbridge.bssid          "<AP-MAC>"
adb shell setprop persist.cpbridge.ap_ip          "<AP-IP>"
adb shell setprop persist.cpbridge.bt_mac         "<UNIT-BT-MAC>"
adb shell setprop persist.cpbridge.public_key     "<64-HEX-AIRPLAY-PUBLIC-KEY>"
adb shell setprop persist.cpbridge.source_version "<RECEIVER-VERSION>"
adb shell setprop persist.cpbridge.airplay_port   "7000"

adb shell am start-foreground-service -n com.cpbridge/.BridgeService
```

`authd` is not a boot service: after every reboot run `adb shell /data/local/tmp/authd spawn`
again. Do not install it under `/vendor` or `/system`; the linker namespace blocks the vendor
library there.

### W6 — Bring the iPhone in and verify

- iPhone Bluetooth on, already bonded to the unit, **not** plugged into USB.
- Watch the funnel — every milestone is one line (all at error priority on this ROM):

```bash
adb logcat -v time | grep CPBRIDGE_DIAG
```

Expected ladder:

```
[a_rfcomm_connect] OK client-mode peer=...
[b_link_sync] VERSION DETECT OK ... SYN/SYN-ACK EXCHANGED ... LINK RUNNING
[d_session] RX msgId=0x1D00 ... IDENTIFICATION ACCEPTED (0x1D02)
[e_auth] RequestAuthenticationCertificate (0xAA00) ... AUTH SUCCEEDED (0xAA05)
[f_credentials] SENT 0x5703 ssid="..." channel=...
[g_carplay] CarPlayAvailability (0x4300) ... SENT 0x4301 CarPlayStartSession
```

Then the iPhone drops Bluetooth (normal), joins the hotspot, and the AirPlay session starts on
port 7000; the CarPlay screen appears in `app.carplayscreen` within about a minute. Pull a
screenshot and show the user.

If the ladder stops early, use this before guessing:

```bash
adb shell cat /sdcard/Android/data/com.cpbridge/files/cpbridge.log
```

| Symptom | Check |
|---|---|
| `a_rfcomm_connect` fails | iPhone bonded and Bluetooth on; correct `peer_mac` |
| `e_auth` returns `0xAA04` | authd not running (`authd status`, `authd.log`); the chip is shared with the vendor service, retries are normal |
| Credentials sent, no AP join | Wrong SSID/passphrase/channel |
| Joins AP, no CarPlay screen | The four values below, then port 7000 and `_airplay._tcp` |
| Screen up, audio on phone | Expected: wireless audio is not solved (see below) |

### The four things that must match

Each failure looked identical — authentication succeeded, credentials went out, no CarPlay. The
cause was always one of these:

1. **Device ID / BSSID must be the AP interface MAC** (`ip link show ap0`), not the Bluetooth MAC.
2. **AirPlay source version must match the running receiver** — an old hardcoded value makes it
   refuse the session.
3. **Hotspot IP must be read live** — Android randomizes the `192.168.43.x` address on every
   hotspot start.
4. **Channel must be pinned** to the AP's real channel.

### W7 — Revert

```bash
./scripts/wireless-revert.sh <adb-serial>     # if ADB is available (hotspot or LAN)
./scripts/wireless-recovery.sh [unit-ip ...]  # otherwise: wait for the boot window and revert
```

Or by hand: mount `/vendor` read-write, put `ro.atc.carplay.wireless=0` back, `sync`, reboot.

### Audio over wireless

Does not work yet. The receiver advertises only PCM, Apple sends AAC-LC over Wi-Fi, and the unit
is missing `libAudioConverter.so`, so audio setup is torn down and sound stays on the iPhone.
`audio/` contains a replacement decoder that matches the ffmpeg reference in offline tests but has
never been exercised in a live session. Do not claim wireless audio works; recommend the cable
when sound matters.

## Hard rules

- Ask before every change. For the `/vendor` flip, get an explicit "yes" in the conversation and
  do it once; never script it to run unattended.
- Wired first. Do the wireless path only when the user asks for it, and only after the wired path
  proves the chip and the screen app.
- Reconfigure; never uninstall or disable a system package the unit needs to boot.
- Genuine chip only. Never fabricate, replay or bypass authentication; if the chip is missing or
  locked, stop and explain instead of working around it.
- Never write the unit's identity into the repo, logs, screenshots or the conversation: MFi
  certificate/serial, keychain/private keys, MAC addresses, hotspot credentials, serial number.
- The reference values (I2C bus 6, address `0x10`, channel 6, the `carplay_on` switch, port 7000)
  are a starting point, not a guarantee. When a value doesn't match, ask and probe — don't guess.

## Reference

- `README.md` — condensed overview, requirements, and the paste-in wired prompt.
- `socials/carplay-article.md` — the full procedure in narrative form.
- `client/PHASE_B.md` — bridge implementation notes (wire format, stages, configuration).
- `authd/README.md` — MFi helper: what it does, how it talks to the chip, quirks.
- `carplay-screen/` — the on-screen app; rebuild against the unit's framework if the APK fails.
- `scripts/wireless-recovery.sh`, `scripts/wireless-revert.sh`, `restore-wired.sh` — undo paths.
