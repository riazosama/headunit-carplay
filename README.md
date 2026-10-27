# CarPlayAlreadyThere

Turn on Apple CarPlay that your Android head unit already shipped with. Wired over USB, and wireless over Wi-Fi with two small helper apps. No new hardware.

**The story:** [link to your X thread]

My Honda City's aftermarket screen never showed CarPlay. It turned out the unit (an Autochips AC8257 board) already had Apple's CarPlay software and a genuine Apple authentication chip inside. The vendor just shipped it half-finished and switched off. A coding assistant found it, and over one weekend wired CarPlay was working properly, with sound through the car speakers, opening by itself when I plug in.

Wireless was a longer road. The unit had the receiver half of wireless CarPlay but was missing the app that would bring the iPhone in over Bluetooth. That part isn't shipped in the firmware, so I built it: a small bridge app that speaks Apple's iAP2 protocol over Bluetooth, and a root helper that signs the authentication challenge with the unit's own Apple chip. Wireless CarPlay now comes up on the screen by itself when I start the car. Sound still comes out of the phone over wireless, and I say so plainly below.

This repo is the prompt, the tools, and the manual so you can check your own unit and do the same.

## What you get

| | Wired (USB) | Wireless (Wi-Fi) |
|---|---|---|
| CarPlay on the unit's screen | Yes | Yes |
| Touch | Yes | Yes |
| Opens by itself | Yes, when you plug in | Yes, when you start the car |
| Sound through the car speakers | Yes | **No** — sound stays on the iPhone |
| Siri and calls | Yes | Screen only |
| Risk | Low, reversible | High, unit-specific, can leave the unit off your network |

If you only want a working CarPlay with sound, use the wired path. The wireless path is for people who like the challenge and can recover a head unit.

## How it works

Aftermarket Android head units have the same debug bridge (ADB) as Android phones. You put a laptop on the same Wi-Fi as the car, and a coding assistant does the rest. For wireless, you also have to reach the unit through its own hotspot after it stops being on your home network.

| Step | What happens |
|---|---|
| Back up | The unit's CarPlay settings and the files before any change, plus a restore script. |
| Diagnose | Find the Apple chip's real address, confirm CarPlay is present, find what's switched off. |
| Enable | Point CarPlay at the right chip, switch it on, install the missing screen app. |
| Wireless (separate) | Flip the firmware's wireless switch, install the bridge and auth helper, feed the iPhone the hotspot details. |

No new hardware, nothing uninstalled, and a one-line restore if you change your mind.

## Will this work on your car? (two-minute check)

This only works on an **open development unit you can get root on** (like mine). Connect a laptop (Step 1 below), then run these two checks.

```bash
adb root                                                    # must succeed
adb shell pm list packages | grep -iE "carplay|autochips|airplay"
```

If root works and something CarPlay-ish shows up, you're in the same boat I was. If root is refused, your unit is locked and most of this won't apply.

For wireless, add these checks. All of them must pass:

```bash
adb shell 'ls -l /dev/i2c-6'                                # i2c bus exists
adb shell 'cat /vendor/build.prop | grep carplay.wireless'  # vendor listens to this switch
adb shell 'getprop ro.build.type'                           # userdebug or eng
adb shell 'getprop ro.boot.veritymode'                      # disabled
```

You also need a genuine Apple MFi chip on the board. The bridge asks that chip to sign the challenge; without it, wireless authentication cannot succeed, and this repo will not help you fake it.

## Quick start

| You have | Do this |
|---|---|
| A laptop and a coding assistant (Claude Code, etc.) | Clone this repo, paste **The prompt** below into the assistant, fill in your unit model. |
| A laptop, no assistant | Run the steps yourself. The commands are in **Doing it by hand**. |
| You want wireless | Do the wired setup first, then follow **Wireless CarPlay** below. |

### Three steps (with an assistant)

| Where | What to do |
|---|---|
| 1. On the unit | While parked, Settings → About → tap **Build number** 7 times → back → **Developer options** → turn on **USB debugging** (and **Wireless/Network debugging** if shown). Connect the unit to your home Wi-Fi. |
| 2. On your laptop | Put the laptop on the same Wi-Fi. Clone this repo (`git clone ...`), open the folder in your assistant, paste **The prompt** and fill in your unit model. |
| 3. Follow along | Accept "Allow USB debugging?" on the unit, then answer the assistant's questions about which apps you use. |

The assistant backs up, diagnoses, enables CarPlay, installs the screen app, and checks each step with screenshots pulled from the unit.

## The prompt

Paste this into Claude Code (or another coding assistant with shell access) on a laptop that is on the same Wi-Fi as the car. This enables **wired** CarPlay only, using the unit's own genuine Apple chip as it was meant to be used. It does not touch wireless — wireless is a separate, manual procedure (see **Wireless CarPlay**), and it is not something to hand to an assistant unattended.

---

I have an aftermarket Android head unit in my car. My model is: `<UNIT NAME HERE>`. Apple CarPlay is not showing up, but I think the hardware may already support it. Work on the real device over ADB, step by step, verify everything with screenshots pulled from the unit, and ask me before any change.

**Connect**
- Find the head unit on my Wi-Fi (ask me for its IP if needed) and connect with ADB on port 5555. Confirm `adb root` works. If it doesn't, stop and tell me this unit is locked and this won't work.
- On macOS, if adb fails with "No route to host" while the port is open, that's Local Network privacy, not the unit. Use the Python `adb-shell` package in a venv instead.

**Back up first**
- Save `getprop`, all `settings` (global/system/secure), and the full package list to a backup folder before changing anything. Copy out any CarPlay-related config or app files before you replace them. Write a restore script that puts every original value and file back.

**Diagnose**
- Check whether Apple CarPlay software is already installed (look for `carplay`, `autochips`, `airplay` packages and native libraries). Tell me what you find before changing anything.
- Find the Apple MFi authentication chip on the I2C buses and note its real address. Compare it to whatever address the CarPlay config is pointing at. A mismatch here is a common reason CarPlay never works.
- Check whether CarPlay is switched on in settings, and whether the app that draws CarPlay on screen is actually installed.

**Enable (ask me before each change)**
- If the config points at the wrong chip address, correct it to the real one, and switch CarPlay on. Make both survive a reboot.
- If the on-screen CarPlay app is missing, build a minimal replacement against the unit's own CarPlay interface (render the video full screen, forward touches, request CarPlay from the phone), plus a small service that opens it when a phone connects and starts at boot. Only sign and install as a system app if the unit runs a test-keys build that allows it.
- After each step, pull a screenshot and show me.

**Rules**
- Never uninstall or disable system packages the unit needs to run. Reconfigure, don't remove.
- This prompt is wired only. Do not attempt the wireless path in this repo; that is a separate procedure the owner runs by hand.
- If anything is unclear, ask me rather than guessing. Keep the restore script current so I can undo everything.

---

## Doing it by hand

Everything the assistant does, you can run yourself. You need ADB (`brew install android-platform-tools` on macOS, or Google's platform-tools on Windows).

```bash
# 1. Connect (replace with your unit's IP, on the same Wi-Fi)
adb connect <UNIT-IP>:5555
adb root
adb connect <UNIT-IP>:5555

# 2. Back up before touching anything
adb shell getprop > getprop.backup.txt
adb shell settings list global > settings-global.backup.txt

# 3. Point CarPlay at the real chip address and switch it on
#    (0x10 / decimal 16 was mine; find YOURS by probing the I2C bus first)
adb shell setprop persist.carplay.i2c.slave_addr 16
adb shell settings put global carplay_on 1

# 4. Install the missing on-screen CarPlay app (built against the unit's own API)
adb install CarPlay.apk
```

Then plug in your iPhone. CarPlay comes up, with touch, Siri, and sound through the car speakers.

**The chip address is the catch.** Mine was wrongly set to `0x11` when the chip actually answers at `0x10`. Yours may differ. The assistant probes the bus to find it. By hand, you'd read the I2C bus to see which address the chip responds on before setting it.

## Optional — clean up what CarPlay replaces

With CarPlay running, the unit's own maps and mirroring apps are dead weight.

```bash
adb shell pm uninstall -k --user 0 com.google.android.apps.maps   # keeps data; restore with:
adb shell cmd package install-existing com.google.android.apps.maps
```

---

# Wireless CarPlay

This is the part the vendor shipped half-finished. It works on my unit: the iPhone hands over the Wi-Fi details over Bluetooth, joins the head unit's hotspot, and the CarPlay screen opens by itself. Sound still comes out of the phone.

Read all of this before you touch the unit. The wireless path has one genuinely dangerous step (writing to `/vendor`), it takes your unit off your home network, and it is the most unit-specific thing in this repo.

## Status, honestly

| Item | Status on my unit | Notes |
|---|---|---|
| Wireless CarPlay screen | **Works** | AirPlay session starts, screen stream renders in the CarPlay screen app |
| Touch | **Works** | Touch rides the same vendor rendering path as wired |
| Auto-connect at car start | **Works** | Up to about a minute after boot |
| Audio over wireless | **Does not work** | Audio stays on the iPhone. Root-caused, decoder built and tested in isolation, not verified in a live session — see **Audio over wireless** |
| Other units | Untested | The four values in **The four things that must match** are the usual reasons it silently fails |

Wired CarPlay has no such limitation: screen, touch and car audio all work over the cable.

## Why the unit never did it by itself

The firmware has both halves of the wireless story — except one:

- `com.autochips.carplay` runs the CarPlay service. When `ro.atc.carplay.wireless=1`, it starts Bluetooth, starts an Android tethering hotspot, tries to bring up its own iAP2 path, advertises over Bonjour (`_airplay._tcp`, port 7000), and runs Apple's AirPlay receiver in-process.
- The missing half is the client app `com.autochips.carplayapp`. That is the app that would open a Bluetooth iAP2 connection to the iPhone, identify the unit as a wireless CarPlay accessory, answer Apple's authentication challenge, and hand the phone the hotspot name and password. The firmware references it; it was never shipped.
- The vendor HAL cannot substitute. Its wireless transport is a stub: the client transport is dropped, `connectTransport` returns an error, and the Wi-Fi configuration calls are log-only.

So the unit can receive a wireless CarPlay session, but nothing brings the phone in. That is what this repo adds:

| Component | What it does |
|---|---|
| `client/` — `com.cpbridge` bridge app | Speaks iAP2 over Bluetooth to the iPhone. Opens the RFCOMM connection, runs the link handshake, identification, MFi authentication, sends the Wi-Fi credentials, then requests the CarPlay session. |
| `authd/` — root helper | Gives the unprivileged bridge app access to the genuine Apple MFi chip over a local socket, so authentication uses a real Apple certificate and real chip signatures. |
| `app.carplayscreen` | The screen app from the wired setup. The vendor's own app is missing too; this draws the CarPlay video and forwards touch. |

**On authentication.** There is no bypass, no forged certificate, no replay. `authd` loads the vendor's own MFi platform layer and asks the unit's real chip (`/dev/i2c-6`, address `0x10`) to sign Apple's 32-byte challenge with its real Apple-issued certificate. The iPhone accepts it because the hardware is genuine. What is reimplemented is the protocol around the chip, not the chip. That distinction is why this only works on units that actually have the chip, and why it is worth being careful about what you publish from your own unit.

## How the handshake works

1. **Flip the switch.** `ro.atc.carplay.wireless=1` (then reboot) makes the vendor service start Bluetooth, raise the hotspot (`ap0`), and start the AirPlay receiver on port 7000.
2. **Bluetooth.** The bridge dials the iPhone's iAP2 RFCOMM service (`00000000-deca-fade-deca-deafdecacafe`). This is the reverse of what you might expect: on this firmware, the car dials the phone.
3. **Link.** A raw version probe, then the iAP2 link handshake, then framed messages with sequence numbers and checksums.
4. **Identification.** The unit describes itself as a wireless CarPlay accessory, including its Bluetooth address and the Wi-Fi transport it offers.
5. **Authentication.** The iPhone asks for a certificate and then a signature. The bridge fetches both from the real MFi chip through `authd`, and the iPhone accepts.
6. **Credentials.** The bridge sends the hotspot SSID, passphrase, channel, BSSID and security type.
7. **Session.** The iPhone says CarPlay is available. The bridge sends `CarPlayStartSession` with the unit's AirPlay public key and the live hotspot IP.
8. **AirPlay.** The iPhone joins the hotspot and opens an AirPlay connection to port 7000. The vendor receiver renders it, and the screen app shows it. The iPhone drops the Bluetooth link at this point — that is normal, and the bridge re-dials for the next session.

The whole cycle is slow at first: boot is about a minute, and the full Bluetooth-to-screen sequence took minutes in my early runs. Once it is set up, starting the car is the only input.

## What you need

- The same family of unit: BWIC "Yato1 A100", Autochips AC8257, Android 9, `userdebug`, AOSP test keys, `adb root`, verity disabled.
- The genuine Apple MFi chip on I2C bus 6, answering at `0x10`. Check with `ls -l /dev/i2c-6` and by probing the bus.
- The wired setup already done: chip address fixed (`persist.carplay.i2c.slave_addr=16`), `carplay_on=1`, and `app.carplayscreen` installed.
- An iPhone already paired and bonded to the unit over Bluetooth.
- A laptop that can join the unit's hotspot. This is how you keep ADB access once the unit leaves your home network.
- Willingness to recover a head unit. See the risks below.

## Before you start — back up, and know the risk

The one dangerous change is writing to `/vendor`. On my unit, repeated writes eventually corrupted that partition (ext4 "structure needs cleaning", I/O errors). That is not recoverable with a factory reset.

```bash
# Back up the file you are about to edit, and keep it somewhere else
adb root
adb pull /vendor/build.prop vendor-build.prop.backup
adb shell 'cp /vendor/build.prop /data/local/tmp/build.prop.orig'
```

Rules that keep you out of trouble:

- **Make the `/vendor` edit once.** Don't loop on it. Get everything else ready first.
- **Never edit `/vendor` on a unit you cannot afford to lose.** If yours is your daily car, do the wired path and stop there.
- **Keep the unit on power.** A brown-out mid-write is how partitions die.
- **You will lose ADB over Wi-Fi** in wireless mode. The Wi-Fi chip cannot be a station and an access point at the same time, so the hotspot takes the interface and the unit leaves your home network. Reconnect through the unit's own hotspot (Step 4).
- **Reverting needs the same edit**, so keep the commands somewhere you can reach without the unit (this file, or your phone).

## Step 1 — Build the two helpers

Prerequisites on the laptop: Android SDK build-tools 35, `android.jar` for API 35, JDK 17, and NDK 26.1 for `authd`. The build scripts assume macOS Homebrew paths; edit them for your machine.

```bash
cd CarPlayAlreadyThere

# Bridge app (no Gradle; javac + d8 + aapt2 + zipalign + apksigner)
./client/build.sh
./client/test-protocol.sh          # offline protocol checks, no device needed

# Root MFi auth helper (aarch64, API 28)
./authd/build.sh
```

Outputs: `client/CarPlayBridge.apk` and `authd/authd` plus `authd/cpauth_client`.

## Step 2 — Collect your unit's values

The bridge has defaults baked in from the unit it was developed on. **Override every one of them with your own values**, or it will authenticate and then quietly fail to start the session.

With the unit still on your home Wi-Fi (wired mode), read what you can:

```bash
adb shell settings get secure bluetooth_address        # unit Bluetooth MAC
adb shell ip link show wlan0                           # Wi-Fi MAC (AP MAC will match / derive from it)
adb shell dumpsys bluetooth_manager | grep -i -A3 iphone   # bonded iPhone name and MAC
```

Then, on the unit, read the values that only exist in wireless mode: the hotspot name, password and channel, the AP interface MAC, the AirPlay receiver's public key and version. The easiest order is to do a first pass of Steps 3–4, read the values, then come back and set them.

| What | Where it comes from | Used for |
|---|---|---|
| Hotspot SSID | Unit Settings → Hotspot, or `dumpsys wifi` with root | Wi-Fi credentials message |
| Hotspot passphrase | Same | Wi-Fi credentials message |
| Hotspot channel | The channel the unit's AP actually runs on (mine was 6). Pin it; don't let it float. | Credentials, session request |
| AP interface MAC | `ip link show ap0` (or the wlan0 MAC) while the hotspot is up | BSSID, device ID |
| Hotspot IP | The gateway your laptop gets after joining the AP. Mine changed on every hotspot start (`192.168.43.x`, last octet random). The app also reads it live; the property is a fallback. | Session request |
| Unit Bluetooth MAC | `settings get secure bluetooth_address` | Identification |
| iPhone Bluetooth MAC | From the unit's bonded devices | Which phone to dial |
| AirPlay public key | `/data/vendor/carplay/default.keychain` — the "AirPlay Pairing Identity" item stores `hex(private32) + "+" + hex(public32)`. Use the public half (64 hex characters). | Session request |
| AirPlay source version | The version the running receiver reports (mine was `320.17.3`). Watch the receiver's log or its Bonjour record. | Session request |
| AirPlay port | 7000 | Session request |

For the keychain: it is root-only, so pull it with root, print it with `plutil`, and copy only the public half.

```bash
adb root
adb pull /data/vendor/carplay/default.keychain
plutil -p default.keychain | grep -A6 "AirPlay Pairing Identity"
```

**Do not paste the private half, the certificate, or the keychain file into a repo, an issue, or a chat.** They are your unit's identity. If you publish your unit's values, someone can impersonate your car.

## Step 3 — Turn on wireless mode

This is the risky edit. Do it once.

```bash
adb root
adb remount
adb shell 'sed -i "s/^ro.atc.carplay.wireless=0$/ro.atc.carplay.wireless=1/" /vendor/build.prop'
adb shell 'sync; grep -n atc.carplay.wireless /vendor/build.prop'    # confirm it says =1
adb reboot
```

`ro.atc.carplay.wireless` lives around line 414 of `/vendor/build.prop` on this firmware. `settings put global carplay_on 0` does **not** switch wireless off — the build.prop line is the switch.

If `adb remount` is not available, mount manually:

```bash
adb shell 'mount -o rw,remount /vendor'
```

## Step 4 — Get ADB back through the unit's own hotspot

After the reboot the unit raises its hotspot and leaves your home Wi-Fi. Join the hotspot from the laptop, then connect ADB to the gateway.

```bash
# macOS
networksetup -setairportnetwork en0 "YOUR-HOTSPOT-SSID" "YOUR-HOTSPOT-PASSWORD"
netstat -nr | grep default          # gateway column is the unit's AP IP

# Linux
nmcli device wifi connect "YOUR-HOTSPOT-SSID" password "YOUR-HOTSPOT-PASSWORD"
ip route | grep default

# Windows: connect in the Wi-Fi list, then
ipconfig | findstr /i "Default Gateway"
```

Then:

```bash
adb connect <AP-IP>:5555
adb shell true
```

Two things that bite here:

- **The AP IP is not reliably `192.168.43.1`.** Android picks a different `192.168.43.x` on each hotspot start. Read the gateway from your laptop, or read `ip addr` on the unit once connected.
- **There is a boot window.** The unit reconnects to your home Wi-Fi for roughly 20–60 seconds at boot before the hotspot takes over. If the hotspot is up and the gateway trick is not working, power-cycle and catch ADB on your LAN during that window (`research/recovery/auto-revert.sh` does this and can flip the property back).

## Step 5 — Deploy the auth helper and the bridge

With ADB through the hotspot:

```bash
# 1. MFi auth helper: must live in /data/local/tmp (a /vendor copy cannot load the vendor library)
adb push authd/authd authd/cpauth_client /data/local/tmp/
adb shell chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client
adb shell /data/local/tmp/authd spawn
adb shell /data/local/tmp/authd status

# 2. Bridge app
adb install -r client/CarPlayBridge.apk

# 3. Tell the bridge your unit's values (persist across reboots)
adb shell setprop persist.cpbridge.peer_mac      "YOUR-IPHONE-BT-MAC"
adb shell setprop persist.cpbridge.ssid          "YOUR-HOTSPOT-SSID"
adb shell setprop persist.cpbridge.passphrase    "YOUR-HOTSPOT-PASSWORD"
adb shell setprop persist.cpbridge.channel       "YOUR-AP-CHANNEL"
adb shell setprop persist.cpbridge.bssid         "YOUR-AP-MAC"
adb shell setprop persist.cpbridge.ap_ip         "YOUR-AP-IP"
adb shell setprop persist.cpbridge.bt_mac        "YOUR-UNIT-BT-MAC"
adb shell setprop persist.cpbridge.public_key    "YOUR-64-HEX-AIRPLAY-PUBLIC-KEY"
adb shell setprop persist.cpbridge.source_version "YOUR-RECEIVER-VERSION"
adb shell setprop persist.cpbridge.airplay_port  "7000"

# 4. Start it
adb shell am start-foreground-service -n com.cpbridge/.BridgeService
```

`authd` does not survive a reboot as a service. After every boot, run `adb shell /data/local/tmp/authd spawn` again (the bridge retries the socket for a while before giving up). Do not install it into `/vendor` or `/system`; the linker namespace will not let it load the vendor MFi library from there.

## Step 6 — Bring the iPhone in

- Bluetooth on, the unit already bonded, iPhone **not** plugged into USB.
- Make sure the hotspot is actually up (`adb shell ip addr show ap0` should show an address and a MAC). If the vendor service didn't bring it up, the wireless switch or boot ordering is off — revert and retry.
- Start the bridge, then watch the funnel. Every milestone is one line, all at error priority because this ROM drops lower priorities for normal apps:

```bash
adb logcat -v time | grep CPBRIDGE_DIAG
```

Expected progression:

```
[a_rfcomm_connect] OK client-mode peer=...
[b_link_sync] VERSION DETECT OK ... SYN/SYN-ACK EXCHANGED ... LINK RUNNING
[d_session] RX msgId=0x1D00 ... IDENTIFICATION ACCEPTED (0x1D02)
[e_auth] RequestAuthenticationCertificate (0xAA00) ... AUTH SUCCEEDED (0xAA05)
[f_credentials] SENT 0x5703 ssid="..." channel=...
[g_carplay] CarPlayAvailability (0x4300) ... SENT 0x4301 CarPlayStartSession
```

Then the iPhone drops Bluetooth, joins the hotspot, and the AirPlay session starts on port 7000. If you want more detail, the full session log survives link teardown:

```bash
adb shell cat /sdcard/Android/data/com.cpbridge/files/cpbridge.log
```

## Step 7 — What success looks like

- The iPhone joins the unit's hotspot (you'll see it in the unit's Wi-Fi settings if you can get to them, or in `dumpsys`).
- The CarPlay screen appears in `app.carplayscreen` on the unit, usually within about a minute of the session request. The app opens itself when the receiver has a connection.
- Touch works.
- Sound stays on the iPhone. That is the known audio limitation, not a failure of the rest.

If the screen does not appear, the vendor AirPlay receiver is almost always the reason. It must be listening on 7000 and advertising `_airplay._tcp`. If the vendor service did not start it (a `Carplay plugin ... not enabled` line in logcat is the tell), the unit needs another nudge: have the native service start, then check port 7000 again. The app that draws the screen is `app.carplayscreen`; if the session is up but the screen is blank, the screen app did not get started.

## The four things that must match

This is the practical summary of why wireless silently fails, and it is the part worth understanding. Every one of these was wrong at some point and each failure looked identical: authentication succeeded, credentials were sent, and no CarPlay appeared.

1. **Device ID / BSSID must be the AP interface's MAC.** On my unit the hotspot interface MAC and the unit's Bluetooth MAC differ in the first byte. Use the AP interface's MAC (`ip link show ap0`), not the Bluetooth address. The session request and the credentials message must agree with the interface the phone is actually joining. A BT MAC here means the phone rejects or ignores the session.
2. **The AirPlay source version must match the running receiver.** The vendor reports one version (mine ended up `320.17.3`); an older hardcoded value (`450.14.1`) made the receiver refuse the session.
3. **The hotspot IP must be read live.** Android randomizes the `192.168.43.x` address on every hotspot start. Hardcoding it works exactly once.
4. **The channel must be pinned to the AP's real channel.** If the credentials message says one channel and the AP runs another, the phone associates but the session never starts.

## Troubleshooting

| Symptom | Check |
|---|---|
| `a_rfcomm_connect` never succeeds | iPhone Bluetooth on and bonded to the unit? Correct `persist.cpbridge.peer_mac`? Try dialing only the phone (that property is already that). |
| Dial succeeds, phone stays silent | iPhone locked or Bluetooth sleeping. Unlock it and retry. The bridge re-dials; you don't need to restart the service. |
| `0xAA04` (auth rejected) | `authd` is not running or not reachable. `adb shell /data/local/tmp/authd status`, then `spawn`. Check its log at `/data/local/tmp/authd.log`. The chip is shared with the vendor stack and can return transient I/O errors; `authd` retries heavily for that reason. |
| Credentials sent, phone never joins the hotspot | Wrong SSID or passphrase. Confirm the unit's actual hotspot name and password, then set both properties again. |
| Phone joins, no CarPlay screen | Recheck the four values above, in order. Then confirm port 7000 is listening and `_airplay._tcp` is advertised. |
| Session starts, screen blank | `app.carplayscreen` not installed or not started. Reinstall it from the wired setup, then start the bridge again. |
| Hotspot never appears after reboot | The wireless property did not stick, or the vendor service failed. `grep atc.carplay.wireless /vendor/build.prop`; if it is 1 but no `ap0`, check the vendor service logs. |
| Unit unreachable after switching | Expected: it is off your LAN. Join the hotspot, or power-cycle and catch the ~20–60 s boot window on your home Wi-Fi. |
| Repeated re-dial loops after the session starts | Normal for the first seconds — the iPhone drops Bluetooth after credentials. If it never settles, check whether the screen came up at all. |

## Audio over wireless

This is the honest part. **Over wireless, audio stays on the iPhone.** Music, Siri and calls play from the phone's own speaker. Wired CarPlay has audio; wireless does not.

Why, in order:

1. The unit's wireless CarPlay receiver advertises only **PCM** audio formats. Apple sends **AAC-LC** over Wi-Fi for wireless CarPlay.
2. When the receiver tries to set up AAC audio, it loads `libAudioConverter.so` to decode it — and that library is **missing from this firmware**. Audio setup is torn down, so the phone never sends an audio stream.
3. The repo contains a replacement decoder (`audio/libAudioConverter.so`) that provides the five CoreAudio symbols the receiver's shim expects and decodes AAC-LC to 16-bit PCM through Android's MediaCodec. It was tested against the vendor shim on the unit and matches the ffmpeg reference to within about 66–70 dB SDR, decoding 1024-frame buffers correctly.
4. It is **not verified in a real CarPlay session.** The test harness never opened an audio stream because no music was playing at the time, so the receiver never called the decoder. Deploying it also requires a boot hook that copies it into `/system/lib64` on every boot, because `/system` on this unit is ramdisk-backed and does not keep writes. The hook installation hit the `/vendor` corruption issue described above.

So: the decoder exists and works in isolation, but nobody has heard wireless CarPlay audio come out of the car speakers, and the repo does not claim it. If you get further than I did, PRs welcome — the pieces are in `audio/` and `research/audio/`.

Until then, if you want sound, plug in the cable. Wired CarPlay works fully.

## Revert

Two ways back. If you still have ADB through the hotspot:

```bash
adb shell 'mount -o rw,remount /vendor'
adb shell 'sed -i "s/^ro.atc.carplay.wireless=1$/ro.atc.carplay.wireless=0/" /vendor/build.prop'
adb shell 'sync; grep -n atc.carplay.wireless /vendor/build.prop'
adb reboot
```

If you are locked out of the hotspot, power-cycle and catch the boot window on your home Wi-Fi (`research/recovery/auto-revert.sh` automates it). Turning the unit's Bluetooth off also stops the hotspot, which can hand the Wi-Fi interface back to station mode.

After the revert you are on your home network again, normal wired CarPlay still works, and the wireless bridge simply never connects.

## Wireless risks and the law

- **This is the most unit-specific, least portable part of the repo.** The values, the partition layout and the vendor stack differ between units and firmware builds. Treat it as experimental.
- **Apple's protocols are proprietary.** This work reverse-engineers the accessory side for interoperability on hardware you own, using the genuine Apple authentication chip that is already on the board. It does not bypass or forge that authentication. Depending on where you live, reverse-engineering and modifying your own device may still conflict with Apple's MFi terms or local law; that is your call to make.
- **Do not publish your unit's identity.** The MFi certificate, the keychain, your Bluetooth and Wi-Fi MAC addresses, the hotspot credentials and the serial number are all yours. The repo's examples use placeholders; if you fork it, scrub the baked-in defaults first.
- **No warranty.** You can leave a head unit unreachable or corrupt its `/vendor` partition. Back up, go slowly, and don't do this on a car you depend on.

---

## Supported units

Seeded with mine. Add yours so the next person with the same unit skips the diagnosis (see CONTRIBUTING).

| Unit | Build | Wired | Wireless |
|---|---|---|---|
| Autochips AC8257 (BWIC "Yato1 A100"), fitted in a Honda City | Android 9, `userdebug`, AOSP test keys, root over ADB | CarPlay, screen, touch, car audio, auto-open, boot persistence | Screen and touch work; audio stays on the phone |

Your unit isn't here? The prompt diagnoses it anyway. Add your results afterwards.

## How this was made

I didn't write this by hand. A coding assistant did the diagnosis, the firmware reading, the wired enable, the screen app and the boot fixes, while I steered and tested in the car. The wireless bridge I built separately after that assistant declined to build it: the Bluetooth iAP2 bootstrap, the auth helper for the genuine chip, and the four fixes that finally made the session start. The prompt above is the reproducible version of the wired path. The wireless path is documented here as a manual procedure, because it is too risky and too unit-specific to hand to an assistant unattended.

---

*One specific unit (Autochips AC8257, test-keys `userdebug` build, root over ADB). Relies on root and a debuggable build. Everything lives in user storage or `/vendor`, so a factory reset does not undo the wireless switch; the revert commands above do. A wrong change can stop CarPlay from starting until you undo it, so back up every file you replace. Unit-specific secrets (Apple key material, the AirPlay pairing keychain, Bluetooth and Wi-Fi addresses, hotspot password, serial) are left out of this repo and must not be added. Not affiliated with Apple or Honda. CarPlay is a trademark of Apple Inc.*
