---
name: enable-carplay
description: >-
  Enable Apple CarPlay on an aftermarket Android head unit (Autochips AC8257 / BWIC "Yato1 A100"
  and similar rootable test-keys units) — wired over USB, and (experimental) wireless over Wi-Fi.
  Use when someone wants to turn CarPlay on or fix it: "CarPlay doesn't show up", "enable CarPlay
  on my Android car stereo", "get Apple CarPlay working over ADB", "wireless CarPlay doesn't
  work", "make CarPlay work over Wi-Fi with my iPhone". Wired: back up, find the genuine MFi
  chip's I2C address, switch CarPlay on, install the screen app, verify with screenshots.
  Firmware without the stock receiver (seen on Android 11): install the DiPlay receiver app and
  point it at the chip. Wireless (only on explicit request): build the Bluetooth iAP2 bridge and the root MFi auth
  helper, flip the vendor switch once, reconnect ADB through the unit's hotspot, set the per-unit
  values, verify the handshake. Uses the genuine chip; never forges authentication. Asks before
  every change and keeps a restore path.
---

# Enable Apple CarPlay on an Autochips head unit (wired or wireless)

Many aftermarket Android head units built on Autochips AC8257 boards (BWIC "Yato1 A100" and
relatives) already have Apple's CarPlay software and a **genuine Apple MFi authentication chip**
on the board — but ship with CarPlay switched off, pointed at the wrong chip address, or missing
the app that draws it. This skill turns CarPlay on live over ADB, using the hardware the unit
already has. Nothing is forged or bypassed: the genuine chip performs Apple's authentication.
Some firmware (seen on an Android 11 build of the same unit) has the chip but not the CarPlay
software; there the skill installs DiPlay, a third-party receiver app, and points it at that chip.

There are three paths, with very different risk:

| Path | What works | Risk |
|---|---|---|
| **Wired (USB)** | Screen, touch, Siri, car audio, opens on plug-in | Low, fully reversible |
| **Wireless (Wi-Fi)** | Screen, touch, opens on car start while `authd` is running; **audio stays on the iPhone** | High: one `/vendor` write, unit leaves your network, unit-specific |
| **Receiver app (DiPlay)**, only on firmware without the stock receiver | Wired and wireless, sound included (reported on one Android 11 unit) | Medium: third-party preview app; reversible, no `/vendor` write |

Work live on the device, verify each step with a screenshot pulled from the unit, and **ask the
user before every change**. The whole point is a change that stays reversible.

## Read this first

- **Root-only.** Every path needs `adb root` on a debuggable (`userdebug` / AOSP test-keys)
  build. If `adb root` is refused, stop — none of this applies.
- **Wired is the safe path. Wireless is not.** The wireless path writes
  `ro.atc.carplay.wireless=1` into `/vendor/build.prop`. On the reference unit, repeated
  `/vendor` writes corrupted that partition (ext4 I/O errors) and a factory reset does not fix
  it. Do the edit once, never unattended, and never on a car the user depends on.
- **Genuine chip only.** Never fabricate a certificate, replay a signature, or spoof MFi. If the
  unit has no Apple MFi chip, wireless cannot work and wired authentication will fail; stop and
  say so. DiPlay signs with an identity taken from Carlinkit firmware unless told otherwise;
  this skill always switches it to the unit's chip (D4) and checks that it did (D7).
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
| Any CarPlay, on firmware without the stock receiver (Step 3.1) | Receiver-app path. The wired and wireless paths cannot work there. |
| CarPlay working, especially with sound | Wired only. Say plainly that wireless audio does not work yet. |
| Wireless CarPlay | Wired first (it proves the chip, the screen app and the vendor service), then the wireless path below. |
| A locked unit / no root | Stop. Explain that this needs a debug build. |
| A unit with no Apple MFi chip | Stop. Wireless is impossible and wired auth will fail. |

## Prerequisites (every path)

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

Android 11 units may offer only **Wireless debugging** (Developer options), which pairs with a
code and listens on a random port, not 5555. The user reads the code and both ports off that
screen. Pair once, then pin port 5555 so later steps and reboots find the unit:

```bash
adb pair <UNIT-IP>:<PAIRING-PORT>             # asks for the code shown on the unit
adb connect <UNIT-IP>:<PORT>                  # the port on the Wireless debugging screen
adb root
adb connect <UNIT-IP>:<PORT>                  # adbd restarted; the screen may show a new port
adb shell setprop persist.adb.tcp.port 5555
adb tcpip 5555 && adb connect <UNIT-IP>:5555
```

That leaves root ADB open to whatever network the unit joins. Say so, and offer to clear it at the
end (`adb shell setprop persist.adb.tcp.port ""`).

macOS note: if adb reports "No route to host" while the port is open, that is macOS Local Network
privacy, not the unit. Grant the terminal Local Network access, or use the Python `adb-shell`
package in a venv.

## Step 2 — Back up before any change

Put backups in `backup/` (the repo `.gitignore` keeps it out of git — it holds device-specific
values). Take them **once**: on a second run the unit already holds the changed values, and
overwriting the first backup loses the originals.

```bash
if [ -e backup/getprop.backup.txt ]; then
  echo "backup/ already exists: keeping the original backup"
else
  mkdir -p backup
  adb shell getprop > backup/getprop.backup.txt
  adb shell settings list global > backup/settings-global.backup.txt
  adb shell settings list system > backup/settings-system.backup.txt
  adb shell settings list secure > backup/settings-secure.backup.txt
  adb shell pm list packages > backup/packages.backup.txt
  adb pull /vendor/build.prop backup/vendor-build.prop   # wireless path needs this
fi
```

`restore-wired.sh` reads `backup/getprop.backup.txt` to put the chip address back, and the
wireless revert needs the `/vendor` backup if the edit has to be undone by hand. If CarPlay was
already changed before the first backup (or `backup/` is gone), tell the user the backup does not
hold the factory values.

## Step 3 — Diagnose (every path)

Find out what is actually wrong before touching anything, and report it to the user first.

1. **Is CarPlay software present?** Same model name, different firmware: the Android 9 reference
   unit has the stock receiver, an Android 11 build of it does not.
   ```bash
   adb shell getprop ro.build.version.release
   adb shell pm list packages | grep -iE "carplay|autochips|airplay"
   adb shell ls /system/app /system/priv-app /vendor/app 2>/dev/null | grep -iE "carplay|autochips"
   adb shell ls -l /system/framework/autochips_services_net.jar
   ```
   No `com.autochips.carplay` package and no `autochips_services_net.jar` means the firmware has
   no CarPlay receiver: nothing registers the `carplay_manager` service, so `app.carplayscreen`
   logs `carplay binder is null` and `client/` has nothing to hand the iPhone to. The wired and
   wireless paths cannot work; use the receiver-app path. Still do 3.3: it needs the chip.
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
   adb shell 'command -v i2cdetect && for i in 1 2 3 4 5; do i2cdetect -y -r 6; done'
   ```
   The MFi chip answers at a fixed 7-bit address (`0x10` on the reference unit — where the config
   was wrongly set to `0x11`). It NACKs its address while asleep: on the reference unit two probes
   in a row missed it and the next ones found it. So probe at least five times, and **a single
   answer counts** as the chip being there. If bus 6 never answers, probe the other `/dev/i2c-*`
   buses the same way before concluding there is no chip. If `i2cdetect` is not on the unit, read the vendor CarPlay config
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
6. **Does a stock iPod app claim the iPhone?**
   ```bash
   adb shell pm list packages | grep -E "com.bw.ipod"
   adb logcat -c   # then have the user plug the iPhone in, and:
   adb logcat -d | grep -E "IAPIPod|createIpod"
   ```
   On the Android 11 unit, `com.bw.ipodservice` caught the plug-in first and locked the iPhone
   into iPod music mode (`[IAPIPod]` in logcat), so no CarPlay session could get the USB link.

Summarise for the user: Android version, stock receiver present (y/n), current chip address vs.
the real one, CarPlay switch state, screen app present (y/n), iPod service claiming the phone
(y/n), and (for wireless) whether the vendor gate, verity state and `/vendor` writability allow
it. Only the mismatches need fixing.

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

   On Android 10+, if `app.carplayscreen` crashes at launch with `NoSuchMethodError`, Android is
   blocking the hidden CarPlay API it calls in `autochips.jar`. Allow it (`restore-wired.sh` puts
   these back):
   ```bash
   adb shell settings put global hidden_api_policy 1
   adb shell settings put global hidden_api_policy_pre_p_apps 1
   adb shell settings put global hidden_api_policy_p_apps 1
   ```
   If it then logs `carplay binder is null`, the firmware has no stock receiver (Step 3.1).
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

## Receiver-app path (firmware without the stock receiver)

Only when Step 3.1 found no stock receiver. Seen on a BWIC Yato1 A100 running Android 11
(`ac8257_64bit`): the vendor iAP2 service and the MFi chip (bus 6, `0x10`) are there, the CarPlay
receiver is not. [DiPlay](https://github.com/shihabal3amri/DiPlay) (`com.shihab.diplay`, built on
xcertplay) is a whole CarPlay receiver in one app: iAP2 over USB or Bluetooth, the iPhone's USB
network over a local `VpnService`, wireless over Wi-Fi Direct or a hotspot, then video, audio,
microphone and touch. On that unit 0.2.22 ran wired and wireless CarPlay, wireless with sound.
Nothing from `carplay-screen/`, `client/`, `authd/` or the `/vendor` flip is used here.

Before starting, tell the user:

- DiPlay is a third-party public preview built for BYD cars. It is not Apple-certified, not part of
  this repo, and other head units are unsupported upstream.
- Out of the box it signs with an accessory identity taken from Carlinkit firmware (its **Local
  (offline)** target). This skill does not use that: D4 points DiPlay at the unit's own chip, and
  if DiPlay cannot use the chip, stop.

### D1 — Get the APK

The user downloads it from the official releases page, `https://github.com/shihabal3amri/DiPlay/releases`,
not from a mirror or a fork. Check it before installing:

```bash
shasum -a 256 DiPlay-<VERSION>.apk   # must match SHA256SUMS.txt on the same release page
```

0.2.22 is the version tested on the Android 11 unit.

### D2 — Stop the stock iPod service claiming the iPhone

Only if Step 3.6 saw it. Neither package is needed to boot, `pm disable-user` leaves the APK in
place, and `pm enable` undoes it. USB iPod music mode is gone while they are off. Confirm first:

```bash
adb shell pm disable-user --user 0 com.bw.ipodservice
adb shell pm disable-user --user 0 com.bw.ipod
```

### D3 — Install DiPlay

```bash
adb uninstall app.carplayscreen   # only if Step 3.4 found it: there is no service for it here
adb install -r -g DiPlay-<VERSION>.apk
```

Have the user open DiPlay once and go through its first-run screens (for the DiLink generation,
pick Unknown: this is not a BYD), so it saves its settings file.

### D4 — Point DiPlay at the unit's chip (required)

DiPlay's settings screen offers only Local and CH341; the board-I2C target is set in its settings
file. Stop the app first, or it writes its old values back. Use the bus from Step 3.3. Copying the
new contents over the old file keeps its owner, mode and SELinux label:

```bash
adb shell am force-stop com.shihab.diplay
F=$(adb shell 'ls /data/user_de/0/com.shihab.diplay/shared_prefs/xcertplay_airplay.xml /data/data/com.shihab.diplay/shared_prefs/xcertplay_airplay.xml 2>/dev/null' | head -1 | tr -d '\r')
echo "$F"   # empty: DiPlay has not saved its settings yet, back to D3
adb pull "$F" backup/diplay-prefs.xml
python3 - backup/diplay-prefs.xml /dev/i2c-<BUS> > backup/diplay-prefs.new.xml <<'EOF'
import sys, xml.etree.ElementTree as ET
prefs = ET.parse(sys.argv[1]).getroot()
for e in [e for e in prefs if e.get("name") in ("mfi_target", "mfi_i2c_path")]:
    prefs.remove(e)
ET.SubElement(prefs, "string", name="mfi_target").text = "I2C"
ET.SubElement(prefs, "string", name="mfi_i2c_path").text = sys.argv[2]
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + ET.tostring(prefs, encoding="unicode"))
EOF
adb push backup/diplay-prefs.new.xml /data/local/tmp/diplay-prefs.xml
adb shell "cat /data/local/tmp/diplay-prefs.xml > $F && rm /data/local/tmp/diplay-prefs.xml"
adb shell "grep -E 'mfi_target|mfi_i2c_path' $F"   # I2C and the bus path
```

Once DiPlay has a hotspot saved, this file holds its passphrase: keep the copies in `backup/` and
never print them. The last command prints only the two MFi lines.

### D5 — Let DiPlay open the chip

```bash
adb shell 'ls -lZ /dev/i2c-<BUS>; getenforce'
```

DiPlay runs as an ordinary app user, which the bus node does not normally let in. Give DiPlay's
app user access, not every app (`chmod 666` would let any app drive the bus):

```bash
adb shell 'chgrp $(stat -c %u /data/data/com.shihab.diplay) /dev/i2c-<BUS> && chmod 660 /dev/i2c-<BUS>'
```

- `Enforcing`: SELinux will probably refuse the app whatever the mode bits say (an `avc: denied`
  line for the bus in logcat). Stop and ask; switching SELinux off weakens the whole unit and is
  the user's call.
- `/dev` is rebuilt at every full boot, so the grant is lost on a reboot, like `authd` on the
  wireless path. A car start that only wakes the unit keeps it. After a full reboot, run the
  command again and reconnect the phone.

### D6 — Consent screens the unit may lack

Some head units strip Android's confirmation dialogs. Grant only what DiPlay says is missing:

```bash
adb shell appops set com.shihab.diplay ACTIVATE_VPN allow   # USB CarPlay's local network link
```

If the USB-access prompt never appears when the iPhone is plugged in, DiPlay has an accessibility
helper that confirms it. `enabled_accessibility_services` is one colon-separated list: add to it,
don't overwrite it.

```bash
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
svc=com.shihab.diplay/com.shilapi.xcertplay.UsbAutoConfirmService
case "$cur" in null|"") list=$svc ;; *) list="$cur:$svc" ;; esac
adb shell settings put secure enabled_accessibility_services "$list"
adb shell settings put secure accessibility_enabled 1
```

The Android 11 unit also had `PROJECT_MEDIA`, `GET_USAGE_STATS`, `SYSTEM_ALERT_WINDOW` and
`WRITE_SETTINGS` allowed; add those only if DiPlay asks for them.

### D7 — Connect, and check that the chip signed

```bash
adb logcat -c
```

- Wired: iPhone into a USB **data** port with a data cable, **Connect with USB** in DiPlay, then
  approve Trust and CarPlay on the phone.
- Wireless: DiPlay **Settings → Connection → Connection setup**. Wi-Fi Direct worked on the
  Android 11 unit; the built-in car hotspot is DiPlay's default. Pair the iPhone with the unit
  over Bluetooth, choose it in DiPlay, tap **Connect phone**.

Then check which identity signed:

```bash
adb logcat -d | grep -E "mfi (discovery|Linux|local)"
```

```
mfi discovery backend=Linux I2C path=/dev/i2c-6
mfi Linux I2C coprocessor ready path=/dev/i2c-6
```

- `backend=LocalOffline` or `mfi local offline ready`: DiPlay signed with the Carlinkit identity.
  Disconnect the phone, tell the user, and redo D4.
- `mfi Linux discovery failed`, a permission error or `avc: denied` on the bus: back to D5.

Pull a screenshot and show the user. For deeper trouble, DiPlay's **Settings → Diagnostics**
report helps; it can hold hotspot details, so the user reads it before sharing it.

### D8 — Revert

`./restore-wired.sh <adb-serial>` covers this path too: it uninstalls DiPlay (its app-ops go with
it), re-enables the iPod service, and puts the accessibility and hidden-API settings back from
`backup/`. Reboot afterwards; that also resets `/dev/i2c-<BUS>`.

## Wireless path

**Experimental, and only on explicit request.** The firmware ships the receiver half of wireless
CarPlay (AirPlay receiver inside `com.autochips.carplay`, hotspot, `_airplay._tcp` on port 7000)
but not the client that brings the iPhone in. `client/` + `authd/` supply it. Expect to debug:
the per-unit values in W4 are the usual reason a session silently fails. Firmware without that
receiver (Step 3.1) cannot use this section; DiPlay's own wireless mode replaces it.

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

### W2 — Turn on wireless mode (the one dangerous edit)

Do this once, with explicit user confirmation, and keep the unit on power:

```bash
adb root
adb remount
[ -e backup/vendor-build.prop ] || adb pull /vendor/build.prop backup/vendor-build.prop  # keep the original
adb shell 'sed -i "s/^ro.atc.carplay.wireless=0$/ro.atc.carplay.wireless=1/" /vendor/build.prop'
adb shell 'sync; grep -n atc.carplay.wireless /vendor/build.prop'   # must say =1
adb reboot
```

If `adb remount` is unavailable: `adb shell 'mount -o rw,remount /vendor'`.

`settings put global carplay_on 0` does **not** clear this — the build.prop line is the switch.

### W3 — Reconnect ADB through the unit's own hotspot

After the reboot the unit leaves the home network and raises its AP. Have the user join the AP
from the laptop's Wi-Fi menu (the passphrase is on the unit under Settings → Hotspot), so the
passphrase never passes through you. Then read the gateway and connect to that:

```bash
netstat -nr | grep default                 # macOS: gateway column = unit AP IP
ip route | grep default                    # Linux
ipconfig | findstr /i "Default Gateway"    # Windows

adb connect <AP-IP>:5555
adb root                                   # the reboot dropped root; W4 needs it
adb connect <AP-IP>:5555
adb shell true
```

The AP IP is **not** reliably `192.168.43.1` — Android randomizes it each start. If you are
locked out, power-cycle and catch the boot window; `./scripts/wireless-recovery.sh` waits for the
unit and flips the property back.

**If the unit is still on the home Wi-Fi after the reboot**, check for the hotspot interface:

```bash
adb shell 'getprop ro.atc.carplay.wireless; ip -o link show ap0'
```

`1` with no `ap0` means wireless mode is on but the hotspot is not up, and W4 cannot work: the
bridge has no hotspot to hand the iPhone. The vendor service raises the hotspot at boot, and
turning Bluetooth off stops it until the next boot. Make sure Bluetooth is on, reboot once and
check again. If `ap0` is still missing, stop and report it. Do **not** edit `/vendor` again.

### W4 — Deploy the auth helper and the bridge, set this unit's values

The hotspot only exists in wireless mode, so this is the first point where every value below can
be read.

```bash
# authd must live in /data/local/tmp so it can dlopen the vendor MFi library
adb push authd/authd authd/cpauth_client /data/local/tmp/
adb shell chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client
adb shell /data/local/tmp/authd spawn
adb shell /data/local/tmp/authd status

adb install -r client/CarPlayBridge.apk
```

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the bridge already on the unit was
signed with another key (`client/build.sh` makes a new `client/debug.keystore` when there is
none). Ask, then `adb uninstall com.cpbridge` (a user app, not a system package; the
`persist.cpbridge.*` values stay) and install again. Keep `client/debug.keystore` for later
updates.

The bridge ships with placeholders. Override every value with the unit's own, or it will
authenticate and then silently fail to start the session.

| Value | How to get it | Used for |
|---|---|---|
| Hotspot SSID / passphrase | Unit Settings → Hotspot; the user sets these two (below) | Wi-Fi credentials (`0x5703`) |
| Hotspot channel | The channel the unit's AP actually runs on; pin it | Credentials, session request |
| AP interface MAC | Read on the unit from `ap0` (not the BT MAC), below | BSSID / device ID |
| Hotspot IP | **Leave unset**: the bridge reads it live from `ap0` each session; it changes on every hotspot start | Session request |
| Unit Bluetooth MAC | Read on the unit, below | Identification |
| iPhone Bluetooth MAC | From the unit's bonded devices | Which phone the bridge dials |
| AirPlay public key | Public half of the "AirPlay Pairing Identity" keychain item, below | Session request (`0x4301`) |
| AirPlay source version | `srcvers` in the receiver's Bonjour record, below (`320.17.3` on the reference unit) | Session request |
| AirPlay port | 7000 | Session request |

Set the unit's own addresses on the unit itself, so they never pass through the conversation:

```bash
adb shell 'setprop persist.cpbridge.bt_mac "$(settings get secure bluetooth_address)"'
adb shell 'setprop persist.cpbridge.bssid "$(cat /sys/class/net/ap0/address)"'
adb shell 'setprop persist.cpbridge.ap_ip ""'   # unset: a pinned IP goes stale on the next hotspot start
```

Ask the user to run these two in their own terminal, not through you:

```bash
adb shell setprop persist.cpbridge.ssid       "<HOTSPOT-SSID>"
adb shell setprop persist.cpbridge.passphrase "<HOTSPOT-PASSPHRASE>"
```

The keychain holds the unit's private key next to the public one
(`hex(private32)+"+"+hex(public32)`). Extract only the public half; never print the item:

```bash
adb pull /data/vendor/carplay/default.keychain backup/
python3 -c 'import plistlib,sys; [print(i["kSecValueData"].decode().split("+")[1]) for i in plistlib.load(open(sys.argv[1],"rb")) if i.get("kSecAttrService")=="AirPlay Pairing Identity"]' \
  backup/default.keychain    # public half only
rm backup/default.keychain
```

The key pair is stored as binary data, which `plutil -p` shows as hex bytes, so don't swap in a
`plutil | grep`: it prints nothing. If the command above prints nothing, stop and ask; do not
inspect the keychain to find out why.

Read the receiver's AirPlay version from its Bonjour record, from a laptop on the same network
as the unit (the hotspot, or the home Wi-Fi if the unit is still on it). Print only `srcvers`:
the record also carries the unit's device ID.

```bash
dns-sd -B _airplay._tcp local.                                      # macOS: note the unit's name, Ctrl-C
dns-sd -L "<UNIT-NAME>" _airplay._tcp local. | grep -o 'srcvers=[0-9.]*'   # Ctrl-C after it prints
avahi-browse -rt _airplay._tcp | grep -o 'srcvers=[0-9.]*'           # Linux
```

The rest are not secrets:

```bash
adb shell setprop persist.cpbridge.peer_mac       "<IPHONE-BT-MAC>"
adb shell setprop persist.cpbridge.channel        "<AP-CHANNEL>"
adb shell setprop persist.cpbridge.public_key     "<64-HEX-AIRPLAY-PUBLIC-KEY>"
adb shell setprop persist.cpbridge.source_version "<RECEIVER-VERSION>"
adb shell setprop persist.cpbridge.airplay_port   "7000"

adb shell am start-foreground-service -n com.cpbridge/.BridgeService
```

Once started, the bridge restarts itself at boot. `authd` does not: wireless opens by itself on
car start only while `authd` is still running. After any full reboot (power loss, `adb reboot`)
run `adb shell /data/local/tmp/authd spawn` again, or authentication fails with `0xAA04`. Do not
install it under `/vendor` or `/system`; the linker namespace blocks the vendor library there.

### W5 — Bring the iPhone in and verify

- iPhone Bluetooth on, already bonded to the unit, **not** plugged into USB.
- Watch the funnel — every milestone is one line (all at error priority on this ROM):

```bash
adb logcat -v time | grep CPBRIDGE_DIAG
```

Expected ladder:

```
[stage=a_rfcomm_connect] OK client-mode peer=...
[stage=b_link_sync] VERSION DETECT OK ... SYN/SYN-ACK EXCHANGED ... LINK RUNNING
[stage=d_session] RX msgId=0x1D00 ... IDENTIFICATION ACCEPTED (0x1D02)
[stage=e_auth] RequestAuthenticationCertificate (0xAA00) ... AUTH SUCCEEDED (0xAA05)
[stage=f_credentials] SENT 0x5703 ssid="..." channel=...
[stage=g_carplay] CarPlayAvailability (0x4300) ... SENT 0x4301 CarPlayStartSession
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
2. **AirPlay source version must match the running receiver** (`srcvers` from Bonjour) — an old
   hardcoded value makes it refuse the session.
3. **Hotspot IP must be read live** — Android randomizes the `192.168.43.x` address on every
   hotspot start. Keep `persist.cpbridge.ap_ip` empty so the bridge reads `ap0` itself.
4. **Channel must be pinned** to the AP's real channel.

### W6 — Revert

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
  locked, stop and explain instead of working around it. On DiPlay that means the `I2C` target
  only: never Local (the bundled Carlinkit identity) or Remote (someone else's chip).
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
- [DiPlay](https://github.com/shihabal3amri/DiPlay) — the third-party receiver app for firmware
  without the stock receiver; its `docs/INSTALL.md` covers its own settings.
- `scripts/wireless-recovery.sh`, `scripts/wireless-revert.sh`, `restore-wired.sh` — undo paths.
  `scripts/test-revert.sh` checks the wireless undo logic offline against a fake adb.
