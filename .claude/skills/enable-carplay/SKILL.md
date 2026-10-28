---
name: enable-carplay
description: >-
  Enable Apple CarPlay on an aftermarket Android head unit (Autochips AC8257 / BWIC "Yato1 A100"
  and similar rootable test-keys units) — wired over USB, and (experimental) wireless over Wi-Fi.
  Use when someone wants to turn CarPlay on or fix it: "CarPlay doesn't show up on my head unit",
  "enable CarPlay on my Android car stereo", "get Apple CarPlay working over ADB", "wireless
  CarPlay doesn't work", "make CarPlay work over Wi-Fi with my iPhone". Wired: back up, find the
  genuine MFi chip's I2C address, switch CarPlay on, install the screen app, verify with
  screenshots. Wireless (only on explicit request): build the Bluetooth iAP2 bridge and the root
  MFi auth helper, flip the vendor switch once, reconnect ADB through the unit's hotspot, set the
  per-unit values, verify the handshake. Uses the genuine chip; never forges authentication.
  Asks before every change and keeps a restore path.
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
| **Wireless (Wi-Fi)** | Screen, touch, opens on car start while `authd` is running; sound, Siri and calls via the car's Bluetooth (manual workaround) | Medium: one `/vendor` edit, unit leaves your network, unit-specific |

This file covers the wired path. The wireless path is in [wireless.md](wireless.md): read it only
once the user has asked for wireless and the wired path works on their unit.

## Rules

- **Root-only.** Both paths need `adb root` on a debuggable (`userdebug` / AOSP test-keys)
  build. If `adb root` is refused, stop — none of this applies.
- **Ask before every change**, work live on the device, and show the user a screenshot pulled
  from the unit after each one. The whole point is a change that stays reversible.
- **Wired first; wired is the low-risk path, wireless is medium.** Do the wireless path only
  when the user asks for it, and only after the wired path proves the chip and the screen app.
  Its `/vendor` write is done once, with a backup and an explicit "yes" in the conversation,
  never unattended or in a loop. On the reference unit `/vendor` corrupted (ext4 I/O errors) only
  after many repeated writes during earlier experiments; one edit and one revert is a small
  risk. If it does go wrong, a factory reset does not fix it and the unit needs a firmware
  reflash.
- **Network ADB gives root.** If `getprop ro.adb.secure` is `0`, anyone on the user's network
  (or on the unit's hotspot, in wireless mode) can open a root shell. Tell the user, and
  recommend a strong hotspot password and turning network debugging off when they're done.
- **Genuine chip only.** Never fabricate a certificate, replay a signature, or spoof MFi. If the
  unit has no Apple MFi chip, wireless cannot work and wired authentication will fail; stop and
  say so.
- **Keep the unit's identity out of the conversation, the repo, logs and screenshots:** MFi
  certificate and serial, AirPlay pairing keychain, Bluetooth/Wi-Fi MAC addresses (the unit's
  and the iPhone's), hotspot SSID/passphrase, serial number. The commands here set those values
  on the unit or have the user type them; the bridge log is read only through
  `scripts/bridge-log.sh`.
- **Reconfigure, never remove.** Never uninstall or disable a system package the unit needs to
  boot. Every change here is a property, a setting, or a file that can be put back.
- **Name the device on every adb call.** Your shell does not keep environment variables between
  commands, so start each command block with `export ANDROID_SERIAL=<serial>` (or pass `-s`).
  Otherwise adb can pick a phone on USB, or fail with "more than one device".
- **Reference values are a starting point** (I2C bus 6, address `0x10`, channel 6, the
  `carplay_on` switch, port 7000), not a guarantee. When a value doesn't match, ask and probe —
  don't guess.

## Choose the path

| The user wants | Do this |
|---|---|
| CarPlay working, with the least risk | Wired only. |
| Wireless CarPlay | Wired first (it proves the chip, the screen app and the vendor service), then [wireless.md](wireless.md). |
| A locked unit / no root | Stop. Explain that this needs a debug build. |
| A unit with no Apple MFi chip | Stop. Wireless is impossible and wired auth will fail. |

## Prerequisites

- You are running from this repo's root, so `backup.sh`, `restore-wired.sh`,
  `carplay-screen/CarPlay.apk`, `client/`, `authd/` and `scripts/` are to hand.
- The laptop and the head unit are on the same Wi-Fi.
- `adb` installed (`brew install android-platform-tools`, or Google's platform-tools).
- Developer options → USB/Network debugging enabled on the unit (Settings → About → tap **Build
  number** 7 times, while parked).
- The unit is parked and powered; the user is present and can watch the screen.

## Step 1 — Connect over ADB

Ask for the unit's IP (or find it), then:

```bash
adb connect <UNIT-IP>:5555
adb -s <UNIT-IP>:5555 root
adb connect <UNIT-IP>:5555   # reconnect: adbd restarts after `adb root`
```

Confirm `adb -s <UNIT-IP>:5555 shell id -u` prints `0`. If root is refused, stop — the unit is
locked. Run `adb -s <UNIT-IP>:5555 shell getprop ro.adb.secure`; if it is `0`, warn the user (see
**Network ADB gives root**). From here on, `<UNIT-IP>:5555` is the serial for `ANDROID_SERIAL`.

macOS note: if adb reports "No route to host" while the port is open, that is macOS Local Network
privacy, not the unit. Grant the terminal Local Network access, or use the Python `adb-shell`
package in a venv.

## Step 2 — Back up before any change

```bash
./backup.sh <UNIT-IP>:5555
```

It saves `getprop`, the global/system/secure settings, the package list and `/vendor/build.prop`
into `backup/` (git-ignored: it holds device-specific values), which `restore-wired.sh` and the
wireless undo read back. It takes the backup once and keeps it on later runs, because by then the
unit holds the changed values. If CarPlay was already changed before the first backup (or
`backup/` is gone), tell the user the backup does not hold the factory values.

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
3. **Find the Apple MFi chip's REAL I2C address.** This is the usual wired culprit: the config
   points at the wrong address, so authentication silently fails. MFi chips answer at `0x10` or
   `0x11` (`0x10` on the reference unit, where the config was wrongly set to `0x11`), on bus 6 of
   the reference unit:
   ```bash
   adb shell 'command -v i2cdetect >/dev/null || { echo no-i2cdetect; exit; }
     for i in 1 2 3 4 5; do i2cdetect -y -r 6 0x10 0x11; done' | grep -oE ' 1[01]|no-i2cdetect' | sort -u
   ```
   It prints ` 10` and/or ` 11` for each address that answered. The chip NACKs its address while
   asleep (on the reference unit two probes in a row missed it), so it probes five times and
   **a single answer counts**. **Do not assume `0x10`** — confirm it for this unit.
   (`enable-carplay.command` runs this same probe; change both together.)
   - `slave_addr` sets the address only; the vendor library opens bus 6 itself. If nothing
     answers there, check `adb shell ls /dev/i2c-*` and the Step 3.2 output for a bus property.
     Without one, stop and report: a chip on another bus cannot be fixed with an address.
   - If `i2cdetect` is not on the unit, read the vendor CarPlay config for the address it probes
     and watch `adb logcat` for MFi/auth errors while a phone is plugged in.
4. **Is the on-screen CarPlay app installed?**
   ```bash
   adb shell pm list packages | grep -i carplayscreen
   ```
   `app.carplayscreen` draws the CarPlay video and forwards touch. Without it, CarPlay can
   authenticate but nothing shows on screen. The repo ships `carplay-screen/CarPlay.apk`.

Summarise for the user: software present (y/n), current chip address vs. the real one, CarPlay
switch state, screen app present (y/n). Only the mismatches need fixing.

## Wired path

Do this first even if the user wants wireless: it proves the chip works, installs the screen app
and confirms the vendor service runs. It also gives you a working baseline to fall back to.

### Step 4 — Enable (confirm each change first)

After each change, pull a screenshot and show the user:

```bash
adb shell screencap -p /sdcard/cp.png && adb pull /sdcard/cp.png backup/
```

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
   confirm with `adb reboot`, reconnect (Step 1), and re-check the values from Step 3. This also
   proves network ADB comes back after a reboot, which the wireless path depends on.

### Step 5 — Verify (wired)

Have the user plug in an iPhone over USB. CarPlay should come up with touch, Siri, and audio
through the car speakers. Pull a screenshot to confirm.

- Authenticates but screen stays blank → screen app missing or not started (Step 4.3).
- Never authenticates → chip address still wrong (Step 3.3); re-probe and correct it.

Undo the wired changes with `./restore-wired.sh <UNIT-IP>:5555`. It puts back the chip address and
the CarPlay switch from `backup/`, and removes the screen app unless the unit came with it.

## Reference

- [wireless.md](wireless.md) — the wireless path (W0–W6).
- `README.md` — condensed overview, requirements, and the paste-in wired prompt.
- `client/PHASE_B.md` — bridge implementation notes: wire format, stages, every
  `persist.cpbridge.*` value.
- `authd/README.md` — MFi helper: what it does, how it talks to the chip, quirks.
- `carplay-screen/` — the on-screen app; rebuild against the unit's framework if the APK fails.
- `backup.sh`, `restore-wired.sh`, `scripts/wireless-mode.sh`, `scripts/wireless-recovery.sh`,
  `scripts/bridge-log.sh` — the scripts that touch the unit. `scripts/test.sh` checks them all
  offline against a fake adb.
- `docs/GUIDE.md` — the full procedure step by step, for people. Where it differs from these
  steps, the steps win.
