---
name: enable-carplay
description: >-
  Enable Apple CarPlay that an aftermarket Android head unit already shipped with but left
  switched off — the wired/USB path, done live on the unit over ADB. Use this whenever someone
  wants to turn on, fix, or get CarPlay working on an aftermarket Android car stereo or head
  unit (Autochips AC8257 / BWIC "Yato1 A100" and similar rootable test-keys units), e.g.
  "CarPlay doesn't show up on my head unit", "enable CarPlay on my Android car stereo", "my
  aftermarket stereo says it supports CarPlay but it's disabled", "get Apple CarPlay working
  over ADB". It connects over ADB, backs up, finds the Apple MFi chip's real I2C address,
  switches CarPlay on, and installs the on-screen CarPlay app — asking before each change and
  keeping a restore script. Wired only; it does not do the wireless bridge.
---

# Enable wired Apple CarPlay on an Autochips head unit

Many aftermarket Android head units built on Autochips AC8257 boards (BWIC "Yato1 A100" and
relatives) already have Apple's CarPlay software and a **genuine Apple MFi authentication chip**
on the board — but ship with CarPlay switched off or pointed at the wrong chip address in
software. This skill turns on the **wired (USB)** path by reconfiguring the unit to use the chip
it already has. Nothing is forged or bypassed: the genuine chip performs Apple's authentication
over USB exactly as intended.

Work live on the device over ADB, verify each step with a screenshot pulled from the unit, and
**ask the user before every change**. The whole point is a change that stays fully reversible.

## Scope — read this first

- **Wired only.** This skill does the USB path. Do **not** attempt the wireless path: editing
  `/vendor/build.prop`, flipping `ro.atc.carplay.wireless`, or building the iAP2 Bluetooth bridge
  / MFi auth daemon. That procedure can corrupt the unit's `/vendor` partition and leave it
  unreachable, so the owner runs it by hand, not an assistant. If the user asks for wireless
  here, decline and point them at `socials/carplay-article.md` in this repo to do it themselves.
- **Only on a unit you can root.** This needs `adb root` and a debuggable (`userdebug` / AOSP
  test-keys) build. If `adb root` is refused, stop and tell the user the unit is locked — none of
  this applies.
- **Reconfigure, never remove.** Never uninstall or disable a system package the unit needs to
  boot. Every change here is a property or a setting that can be put back.

## Prerequisites

- The user's laptop and the head unit are on the same Wi-Fi.
- `adb` is installed (`brew install android-platform-tools`, or Google's platform-tools).
- The user has turned on Developer options → USB/Network debugging on the unit (Settings → About
  → tap **Build number** 7 times, while parked).
- You are running from the `CarPlayAlreadyThere` repo root, so `carplay-screen/CarPlay.apk` and
  `restore-wired.sh` are to hand.

## Step 1 — Connect over ADB

Ask the user for the unit's IP (or find it on the Wi-Fi), then:

```bash
adb connect <UNIT-IP>:5555
adb root
adb connect <UNIT-IP>:5555   # reconnect: adbd restarts after `adb root`
```

Confirm `adb shell true` works and `adb root` reports running as root. If root is refused, stop
here — the unit is locked.

macOS note: if adb reports "No route to host" while the port is open, that is macOS Local Network
privacy, not the unit. Grant the terminal Local Network access, or use the Python `adb-shell`
package in a venv.

## Step 2 — Back up before any change

Capture the unit's state so everything is reversible. Put it in a `backup/` folder (the repo
`.gitignore` already keeps `backup/` out of git — it holds device-specific values):

```bash
mkdir -p backup
adb shell getprop > backup/getprop.backup.txt
adb shell settings list global > backup/settings-global.backup.txt
adb shell settings list secure > backup/settings-secure.backup.txt
adb shell pm list packages > backup/packages.backup.txt
```

`restore-wired.sh` reads `backup/getprop.backup.txt` to put the chip address back, so this step is
what makes the restore reliable.

## Step 3 — Diagnose

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
3. **Find the Apple MFi chip's REAL I2C address.** This is the usual culprit: the config points at
   the wrong address, so authentication silently fails. The chip sits on one of the `/dev/i2c-*`
   buses (bus 6 on the reference unit).
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
   authenticate but nothing shows on screen.

Summarise for the user: software present (y/n), current chip address vs. the real one, CarPlay
switch state, screen app present (y/n). Only the mismatches need fixing.

## Step 4 — Enable (confirm each change first)

Make only the changes the diagnosis found necessary, and confirm each one with the user.

1. **Point CarPlay at the real chip address** (persistent prop, value in **decimal** — `0x10` = 16):
   ```bash
   adb shell setprop persist.carplay.i2c.slave_addr <DECIMAL-ADDR>
   ```
2. **Switch CarPlay on, persistently:**
   ```bash
   adb shell settings put global carplay_on 1
   ```
   Use the switch name the diagnosis found if this unit names it differently.
3. **If the screen app is missing, install the repo's prebuilt one:**
   ```bash
   adb install carplay-screen/CarPlay.apk
   ```
   If the install fails because this unit's CarPlay framework differs, rebuild from source against
   the unit's own classes — see `carplay-screen/build.sh` and the API stubs under
   `carplay-screen/stubs/`. Only sign and install as a system app if the unit's test-keys build
   allows it.
4. **Make it survive a reboot.** `persist.` props and `settings put global` survive reboots;
   confirm by `adb reboot`, reconnecting, and re-checking the values from Step 3.

After each change, pull a screenshot and show the user:

```bash
adb shell screencap -p /sdcard/cp.png && adb pull /sdcard/cp.png backup/
```

## Step 5 — Verify

Have the user plug in an iPhone over USB. CarPlay should come up on the unit's screen with touch,
Siri, and audio through the car speakers. Pull a screenshot to confirm.

- Authenticates but the screen stays blank → the screen app is missing or not started (Step 4.3).
- Never authenticates → the chip address is still wrong (Step 3.3); re-probe and correct it.

## Restore

Everything here is reversible. The repo ships `restore-wired.sh`, which reads
`backup/getprop.backup.txt` to put the chip address back, turns `carplay_on` off, removes the
screen app, and re-enables anything that was disabled:

```bash
./restore-wired.sh <UNIT-IP>:5555
```

Keep the backups current as you change things, so the user can always back out cleanly.

## Hard rules

- Ask before every change. Reconfigure; never uninstall a system package the unit needs.
- Wired only — refuse the wireless `/vendor` edit and the Bluetooth/MFi bridge. That is the
  owner's manual procedure, documented in `socials/carplay-article.md`.
- The reference values (I2C bus 6, address `0x10`, the `carplay_on` switch) are a starting point,
  not a guarantee. Every unit can differ — when a value doesn't match, ask the user and probe,
  don't guess.
