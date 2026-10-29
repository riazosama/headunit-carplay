# headunit-carplay

Turn on Apple CarPlay that an aftermarket **Autochips AC8257** Android head unit already shipped with. **Wired** over USB works fully (screen, touch, Siri, car audio). **Wireless** over Wi-Fi brings up the screen and touch; sound, Siri and calls work through the car's Bluetooth for now (a manual workaround, see Status). No new hardware — the unit's genuine Apple MFi chip does the authentication, nothing is forged or bypassed.

> Full step-by-step (wired, wireless, troubleshooting, revert): **[docs/GUIDE.md](docs/GUIDE.md)**

## Status

| | Wired (USB) | Wireless (Wi-Fi) |
|---|---|---|
| CarPlay on the unit's screen | Yes | Yes |
| Touch | Yes | Yes |
| Auto-open | On plug-in | On car start, while `authd` is running |
| Audio through car speakers | Yes | Via the car's Bluetooth (workaround, below) |
| Siri / calls | Yes | Via the car's Bluetooth (workaround, below) |
| Risk | Low, fully reversible | Medium: one `/vendor` edit, unit leaves your Wi-Fi, unit-specific |

**Wireless sound is a workaround for now.** CarPlay's own audio doesn't come through the wireless session, because the bridge and the head unit's Bluetooth conflict. With the iPhone connected to the car's Bluetooth and the head unit's audio source set to Bluetooth, music, Siri and calls all play through the car speakers. It's a manual step, not CarPlay's native audio path. Making it seamless, like wired, is in progress.

If you just want working CarPlay, use the wired path. The wireless path is experimental; read its risks below first.

## What's in this repo

| Path | What it is |
|---|---|
| `carplay-screen/` | On-screen CarPlay app (`app.carplayscreen`) — renders the CarPlay video, forwards touch. Needed for both paths. |
| `client/` | Wireless bridge (`com.cpbridge`): Bluetooth iAP2 transport, identification, MFi auth client, Wi-Fi handoff, CarPlay session request. |
| `authd/` | Root helper that lets the bridge use the unit's genuine Apple MFi chip for authentication. |
| `scripts/` | Wireless on/off (`wireless-mode.sh`), boot-window recovery, a redacted bridge log, and offline tests against a fake adb. |
| `enable-carplay.command` | One-click wired enable, no AI needed: double-click on a Mac, or run it in a terminal. |
| `backup.sh` | Back up the unit's original values before any change. |
| `restore-wired.sh` | Undo the wired enable from that backup. |
| `docs/GUIDE.md` | The full step-by-step guide: wired, wireless, troubleshooting, revert. |
| `.claude/skills/` | Agent skill (`enable-carplay`), the recommended way to run this: walks Claude Code through the wired path, and the wireless path when you ask for it. |

Build outputs, keystores, keychains and device captures are gitignored and never committed.

## Requirements

- An open development unit you can get root on: Autochips AC8257 family (e.g. BWIC "Yato1 A100"), Android 9, `userdebug` / AOSP test keys, `adb root`, verity disabled.
- A genuine Apple MFi chip on I2C bus 6 (answers at `0x10` on this unit).
- A laptop on the same Wi-Fi as the car, with ADB (`brew install android-platform-tools`, or Google's platform-tools).
- [Claude Code](https://claude.com/claude-code) to run the skill (recommended), or another coding assistant with shell access.

### Will this work on your car? (two-minute check)

```bash
adb root                                                    # must succeed
adb shell pm list packages | grep -iE "carplay|autochips|airplay"
```

Root refused means the unit is locked and most of this won't apply. For wireless, also check:

```bash
adb shell 'ls -l /dev/i2c-6'                 # i2c bus exists
adb shell 'getprop ro.build.type'            # userdebug or eng
adb shell 'getprop ro.boot.veritymode'       # disabled
```

## Wired CarPlay (recommended)

### With Claude Code: use the skill (recommended)

On a laptop on the car's Wi-Fi:

```bash
git clone https://github.com/riazosama/headunit-carplay
cd headunit-carplay
claude
```

Then run `/enable-carplay`, or just say "Enable CarPlay on my head unit, the model is <UNIT NAME>". The skill (`.claude/skills/enable-carplay/`) backs up, diagnoses, finds the chip, switches CarPlay on, installs the screen app and checks each step with a screenshot from the unit. It asks before every change. It does the wired path; it does the wireless path only if you ask for it, because of the `/vendor` risk.

### With another assistant: the prompt

If your assistant doesn't support skills, paste this into it instead. Wired only — it does not touch wireless.

```
I have an aftermarket Android head unit in my car. My model is: <UNIT NAME HERE>. Apple
CarPlay is not showing up, but I think the hardware may already support it. Work on the real
device over ADB, step by step, verify everything with screenshots pulled from the unit, and
ask me before any change.

Connect
- Find the head unit on my Wi-Fi (ask me for its IP if needed) and connect with ADB on port
  5555. Confirm `adb root` works. If it doesn't, stop and tell me this unit is locked.
- On macOS, if adb fails with "No route to host" while the port is open, that's Local Network
  privacy, not the unit. Use the Python `adb-shell` package in a venv instead.

Back up first
- Save `getprop`, all `settings` (global/system/secure), and the full package list to a backup
  folder before changing anything. Copy out any CarPlay config or app files before you replace
  them. Write a restore script that puts every original value and file back.

Diagnose
- Check whether Apple CarPlay software is already installed (carplay/autochips/airplay packages
  and native libraries). Tell me what you find before changing anything.
- Find the Apple MFi chip on the I2C buses and note its real address. Compare it to whatever
  address the CarPlay config points at — a mismatch is a common reason CarPlay never works.
- Check whether CarPlay is switched on, and whether the app that draws CarPlay on screen exists.

Enable (ask me before each change)
- If the config points at the wrong chip address, correct it to the real one and switch CarPlay
  on. Make both survive a reboot.
- If the on-screen CarPlay app is missing, build a minimal replacement against the unit's own
  CarPlay interface (render video full screen, forward touches, request CarPlay from the phone),
  plus a service that opens it on connect and starts at boot. Only sign/install as a system app
  if the unit runs a test-keys build that allows it.
- After each step, pull a screenshot and show me.

Rules
- Never uninstall or disable system packages the unit needs. Reconfigure, don't remove.
- This prompt is wired only. Do not attempt the wireless path.
- If anything is unclear, ask rather than guessing. Keep the restore script current.
```

### One click (no AI subscription needed)

1. Install ADB: `brew install android-platform-tools` on a Mac (the script offers to do it), `sudo apt install adb` on Linux. On Windows, skip this: `enable-carplay.cmd` (step 3) installs Git for Windows and adb with winget, asking first.
2. On the head unit: Settings → About → tap **Build number** 7 times, then turn on network/USB debugging in Developer options. Note its IP address.
3. Download this repo (Code → Download ZIP), unzip it, and double-click **`enable-carplay.command`** on a Mac, or **`enable-carplay.cmd`** on Windows. On Linux, run `./enable-carplay.command` from the repo folder.
   If Windows warns about it: click **More info → Run anyway** (or **Run**).
   If a Mac blocks it: on macOS 15 and later, open System Settings → Privacy & Security and click **Open Anyway** next to the message about it; on macOS 14 and earlier, right-click it → Open.
4. Type the unit's IP when asked. The script checks root, backs up, finds the MFi chip, shows what it will change, and asks once before touching anything.
5. Plug in your iPhone.

Undo: in a terminal (on Windows: right-click in the repo folder → **Open Git Bash here**), `cd` into the repo folder, then `./restore-wired.sh <UNIT-IP>:5555`. The script prints this exact command when it finishes. Wired only — the script never touches wireless or `/vendor`.

Something went wrong? Every run is saved to `enable-carplay.log` in the repo folder. Send that file along with your issue.

### By hand

```bash
adb connect <UNIT-IP>:5555 && adb root && adb connect <UNIT-IP>:5555
./backup.sh <UNIT-IP>:5555                            # originals into backup/, once
adb shell setprop persist.carplay.i2c.slave_addr 16   # 0x10 = 16; probe YOUR bus first
adb shell settings put global carplay_on 1
adb install carplay-screen/CarPlay.apk
```

Then plug in the iPhone. The chip address is the usual catch — mine was wrongly set to `0x11` while the chip answers at `0x10`; yours may differ, so read the I2C bus first.

Undo the wired changes: `./restore-wired.sh [adb-serial]`.

## Wireless CarPlay (experimental)

The firmware ships the receiver half of wireless CarPlay but not the client that brings the iPhone in over Bluetooth. `client/` + `authd/` add it. Authentication goes through the real Apple chip on `/dev/i2c-6`, so this only works on a unit that actually has one — nothing is forged.

Outline (full step-by-step is in [docs/GUIDE.md](docs/GUIDE.md); implementation notes in [client/PHASE_B.md](client/PHASE_B.md) and [authd/README.md](authd/README.md)):

1. Build the helpers — `client/build.sh` and `authd/build.sh`.
2. **The one risky step:** `./scripts/wireless-mode.sh on <adb-serial>` sets `ro.atc.carplay.wireless=1` in `/vendor/build.prop`, reads it back and reboots. Back up `build.prop` (`backup.sh` does), keep the unit on power and make the edit once. On my unit `/vendor` only corrupted after many repeated writes during earlier experiments; if it ever does, a factory reset won't fix it and you'd need to reflash the firmware.
3. The unit leaves your home Wi-Fi and raises its own hotspot. Reconnect ADB through the hotspot gateway (it is not reliably `192.168.43.1`; read it from your laptop).
4. Push `authd` to `/data/local/tmp` and `spawn` it, install `client/CarPlayBridge.apk`, set the `persist.cpbridge.*` values for your unit, start the service.
5. Start the car; the CarPlay screen comes up within about a minute. After a full reboot, `spawn` `authd` again.

Revert: `./scripts/wireless-mode.sh off <adb-serial>`, or `./scripts/wireless-recovery.sh` to catch the boot window if you are locked out of the hotspot. Then `adb uninstall com.cpbridge` and remove `authd` from `/data/local/tmp` (the skill's W6 has the commands).

### The four things that must match

Every wireless failure looked identical — auth succeeded, credentials sent, no screen. The cause was always one of these:

1. **Device ID / BSSID must be the AP interface MAC** (`ip link show ap0`), not the Bluetooth MAC.
2. **AirPlay source version must match the running receiver** — an old hardcoded value makes it refuse the session.
3. **Hotspot IP must be read live** — Android randomizes the `192.168.43.x` address on every hotspot start.
4. **Channel must be pinned** to the AP's real channel.

## Supported units

Add yours so the next person with the same unit skips the diagnosis (see [CONTRIBUTING.md](CONTRIBUTING.md)).

| Unit | Build | Wired | Wireless |
|---|---|---|---|
| Autochips AC8257 (BWIC "Yato1 A100"), fitted in a Honda City | Android 9, `userdebug`, AOSP test keys, root over ADB | CarPlay, screen, touch, car audio, auto-open, boot persistence | Screen and touch work; sound, Siri and calls via the car's Bluetooth (workaround) |

Your unit isn't here? The prompt diagnoses it anyway. Add your results afterwards.

## Safety and law

- Experimental and unit-specific. Values, partition layouts and vendor stacks differ between units and firmware builds.
- This reverse-engineers the accessory side for interoperability on hardware you own, using the genuine Apple chip already on the board. It does not bypass or forge authentication. Depending on where you live it may still conflict with Apple's MFi terms or local law — that is your call.
- **Never commit your unit's identity:** MFi certificate, keychain, Bluetooth/Wi-Fi MACs, hotspot credentials, serial number. Everything in this repo is a placeholder; scrub the baked-in defaults if you fork it.
- **Network ADB gives root.** These steps turn on network debugging with root. Check that `adb shell getprop ro.adb.secure` returns `1` (each new computer then has to be approved on the unit). If it returns `0`, anyone on the same network, or on the unit's hotspot in wireless mode, can open a root shell. Use a strong hotspot password and turn network debugging off when you're done; in wireless mode you need it again after a full reboot to restart `authd`.
- **`authd` answers any app on the unit.** Its socket (`/data/local/tmp/cpauth.sock`) is world-writable and doesn't check who connects, so any app installed on the head unit could have the Apple chip sign challenges. Only install apps you trust on the unit, and run `authd stop` if you stop using wireless.
- No warranty. The worst case on the wireless path is a corrupted `/vendor` partition, which needs a firmware reflash. Back up `build.prop` and keep the revert commands handy.

---

MIT licensed — see [LICENSE](LICENSE).

*One specific unit (Autochips AC8257, test-keys `userdebug` build, root over ADB). Not affiliated with Apple or Honda. CarPlay is a trademark of Apple Inc.*
