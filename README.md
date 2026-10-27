# CarPlayAlreadyThere

Turn on Apple CarPlay that an aftermarket **Autochips AC8257** Android head unit already shipped with. **Wired** over USB works fully (screen, touch, Siri, car audio). **Wireless** over Wi-Fi brings up the screen and touch; audio stays on the phone (see below). No new hardware — the unit's genuine Apple MFi chip does the authentication, nothing is forged or bypassed.

> Narrative write-up (the story, for sharing): **[socials/carplay-article.md](socials/carplay-article.md)**

## Status

| | Wired (USB) | Wireless (Wi-Fi) |
|---|---|---|
| CarPlay on the unit's screen | Yes | Yes |
| Touch | Yes | Yes |
| Auto-open | On plug-in | On car start |
| Audio through car speakers | Yes | **No** — stays on the iPhone |
| Siri / calls | Yes | Screen only |
| Risk | Low, reversible | High, writes `/vendor`, unit-specific |

If you just want working CarPlay with sound, use the wired path. The wireless path is for people who can recover a head unit.

## What's in this repo

| Path | What it is |
|---|---|
| `carplay-screen/` | On-screen CarPlay app (`app.carplayscreen`) — renders the CarPlay video, forwards touch. Needed for both paths. |
| `client/` | Wireless bridge (`com.cpbridge`): Bluetooth iAP2 transport, identification, MFi auth client, Wi-Fi handoff, CarPlay session request. |
| `authd/` | Root helper that lets the bridge use the unit's genuine Apple MFi chip for authentication. |
| `audio/` | Replacement `libAudioConverter.so` decoder (AAC-LC → PCM) for the wireless audio work; not yet verified in a live session. |
| `scripts/` | Wireless revert / recovery helpers. |
| `restore-wired.sh` | Undo the wired enable. |
| `socials/` | The narrative write-up for posting. |

Build outputs, keystores, keychains and device captures are gitignored and never committed.

## Requirements

- An open development unit you can get root on: Autochips AC8257 family (e.g. BWIC "Yato1 A100"), Android 9, `userdebug` / AOSP test keys, `adb root`, verity disabled.
- A genuine Apple MFi chip on I2C bus 6 (answers at `0x10` on this unit).
- A laptop on the same Wi-Fi as the car, with ADB (`brew install android-platform-tools`, or Google's platform-tools).

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

### With a coding assistant

Paste the prompt below into Claude Code (or another assistant with shell access) on a laptop that is on the car's Wi-Fi. Wired only — it does not touch wireless.

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

### By hand

```bash
adb connect <UNIT-IP>:5555 && adb root && adb connect <UNIT-IP>:5555
adb shell getprop > getprop.backup.txt
adb shell settings list global > settings-global.backup.txt
adb shell setprop persist.carplay.i2c.slave_addr 16   # 0x10 = 16; probe YOUR bus first
adb shell settings put global carplay_on 1
adb install carplay-screen/CarPlay.apk
```

Then plug in the iPhone. The chip address is the usual catch — mine was wrongly set to `0x11` while the chip answers at `0x10`; yours may differ, so read the I2C bus first.

Undo everything: `./restore-wired.sh [adb-serial]`.

## Wireless CarPlay (experimental)

The firmware ships the receiver half of wireless CarPlay but not the client that brings the iPhone in over Bluetooth. `client/` + `authd/` add it. Authentication goes through the real Apple chip on `/dev/i2c-6`, so this only works on a unit that actually has one — nothing is forged.

Outline (full step-by-step is in [socials/carplay-article.md](socials/carplay-article.md); implementation notes in [client/PHASE_B.md](client/PHASE_B.md) and [authd/README.md](authd/README.md)):

1. Build the helpers — `client/build.sh` and `authd/build.sh`.
2. **The one dangerous step:** set `ro.atc.carplay.wireless=1` in `/vendor/build.prop` and reboot. Repeated `/vendor` writes corrupted that partition on my unit — do it once, keep the unit on power, and don't do this on a car you depend on.
3. The unit leaves your home Wi-Fi and raises its own hotspot. Reconnect ADB through the hotspot gateway (it is not reliably `192.168.43.1`; read it from your laptop).
4. Push `authd` to `/data/local/tmp` and `spawn` it, install `client/CarPlayBridge.apk`, set the `persist.cpbridge.*` values for your unit, start the service.
5. Start the car; the CarPlay screen comes up within about a minute.

Revert: `./scripts/wireless-revert.sh <adb-serial>`, or `./scripts/wireless-recovery.sh` to catch the boot window if you are locked out of the hotspot.

### The four things that must match

Every wireless failure looked identical — auth succeeded, credentials sent, no screen. The cause was always one of these:

1. **Device ID / BSSID must be the AP interface MAC** (`ip link show ap0`), not the Bluetooth MAC.
2. **AirPlay source version must match the running receiver** — an old hardcoded value makes it refuse the session.
3. **Hotspot IP must be read live** — Android randomizes the `192.168.43.x` address on every hotspot start.
4. **Channel must be pinned** to the AP's real channel.

### Audio over wireless

Does not work yet. The receiver advertises PCM, but Apple sends AAC-LC over wireless and the unit is missing `libAudioConverter.so`. `audio/` contains a replacement decoder that matches the ffmpeg reference in offline tests (~66–70 dB SDR) but has never been exercised in a live CarPlay session. Until that is solved, plug in the cable for sound.

## Safety and law

- Experimental and unit-specific. Values, partition layouts and vendor stacks differ between units and firmware builds.
- This reverse-engineers the accessory side for interoperability on hardware you own, using the genuine Apple chip already on the board. It does not bypass or forge authentication. Depending on where you live it may still conflict with Apple's MFi terms or local law — that is your call.
- **Never commit your unit's identity:** MFi certificate, keychain, Bluetooth/Wi-Fi MACs, hotspot credentials, serial number. Everything in this repo is a placeholder; scrub the baked-in defaults if you fork it.
- No warranty. You can leave a head unit unreachable or corrupt its `/vendor` partition. Back up, go slowly, and don't do this on a car you depend on.

---

*One specific unit (Autochips AC8257, test-keys `userdebug` build, root over ADB). Not affiliated with Apple or Honda. CarPlay is a trademark of Apple Inc.*
