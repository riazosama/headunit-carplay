# Receiver app: firmware without the stock CarPlay receiver

Read this only when Step 3.1 in [SKILL.md](SKILL.md) found no stock receiver. The rules there
still apply, above all: ask before every change, genuine chip only, keep the unit's identity out
of the conversation, and name the device on every adb call. D4 and D5 need root by whichever
route Step 1 found; with `cmdclient`, mind its length cap and pull root-only files through
`/sdcard` as Step 1 describes.

Seen on a BWIC Yato1 A100 running Android 11 (`BWIC/Yato1/ac8257_64bit:11/RP1A.200720.011/mp1V8241`):
the vendor iAP2 service and the MFi chip (bus 6, `0x10`) are there, the CarPlay receiver
(`com.autochips.carplay`, `autochips_services_net.jar`) is not. Nothing registers
`carplay_manager`, so `app.carplayscreen` logs `carplay binder is null`, and `client/` has nothing
to hand the iPhone to. Neither the wired nor the wireless path can work there.

[DiPlay](https://github.com/shihabal3amri/DiPlay) (`com.shihab.diplay`, built on xcertplay) is a
whole CarPlay receiver in one app: iAP2 over USB or Bluetooth, the iPhone's USB network over a
local `VpnService`, wireless over Wi-Fi Direct or a hotspot, then video, audio, microphone and
touch. On that unit 0.2.22 ran wired and wireless CarPlay, wireless with sound. Nothing from
`carplay-screen/`, `client/`, `authd/` or the `/vendor` edit is used here.

Before starting, tell the user:

- DiPlay is a third-party public preview built for BYD cars. It is not Apple-certified, not part
  of this repo, and other head units are unsupported upstream.
- Out of the box it signs with an accessory identity taken from Carlinkit firmware (its **Local
  (offline)** target). This skill does not use that: D4 points DiPlay at the unit's own chip, and
  if DiPlay cannot use the chip, stop.

## D1 — Get the APK

The user downloads it from the official releases page,
`https://github.com/shihabal3amri/DiPlay/releases`, not from a mirror or a fork. Check it before
installing:

```bash
shasum -a 256 DiPlay-<VERSION>.apk   # must match SHA256SUMS.txt on the same release page
```

0.2.22 is the version tested on the Android 11 unit.

## D2 — Stop the stock iPod service claiming the iPhone

Only if Step 3.5 saw it. Neither package is needed to boot, `pm disable-user` leaves the APK in
place, and `pm enable` undoes it. USB iPod music mode is gone while they are off. Confirm first:

```bash
adb shell pm disable-user --user 0 com.bw.ipodservice
adb shell pm disable-user --user 0 com.bw.ipod
```

## D3 — Install DiPlay

```bash
adb uninstall app.carplayscreen   # only if Step 3.4 found it: there is no service for it here
adb install -r -g DiPlay-<VERSION>.apk
```

Have the user open DiPlay once and go through its first-run screens (for the DiLink generation,
pick Unknown: this is not a BYD), so it saves its settings file.

## D4 — Point DiPlay at the unit's chip (required)

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

## D5 — Let DiPlay open the chip

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

## D6 — Consent screens the unit may lack

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

## D7 — Connect, and check that the chip signed

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

## D8 — Revert

`./restore-wired.sh <UNIT-IP>:5555` covers this path too: it uninstalls DiPlay (its app-ops go
with it), re-enables the iPod service, and puts the accessibility and hidden-API settings back
from `backup/`. Reboot afterwards; that also resets `/dev/i2c-<BUS>`.
