# Guide: enable CarPlay on an Autochips head unit

The full step-by-step behind the [README](../README.md): the wired path, the experimental wireless path, troubleshooting and revert. Many aftermarket Android head units on Autochips AC8257 boards already have Apple's CarPlay software and a genuine Apple authentication chip inside, but ship with CarPlay switched off. This turns it on with the hardware the unit already has.

## What you get

| | Wired (USB) | Wireless (Wi-Fi) |
|---|---|---|
| CarPlay on the unit's screen | Yes | Yes |
| Touch | Yes | Yes |
| Opens by itself | Yes, when you plug in | Yes, when you start the car |
| Sound through the car speakers | Yes | Via the car's Bluetooth (workaround) |
| Siri and calls | Yes | Via the car's Bluetooth (workaround) |
| Risk | Low, fully reversible | Medium: one `/vendor` edit, unit-specific, takes the unit off your network |

If you only want a working CarPlay, use the wired path. The wireless path is experimental; read **Before you start** first.

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
| A laptop and Claude Code | Clone this repo, open it in Claude Code and run `/enable-carplay` (recommended). |
| Another coding assistant | Paste **the prompt** from the README into it and fill in your unit model. |
| A laptop, no assistant | Run the steps yourself. The commands are in **Doing it by hand**. |
| You want wireless | Do the wired setup first, then follow **Wireless CarPlay** below. |

### Three steps (with Claude Code)

| Where | What to do |
|---|---|
| 1. On the unit | While parked, Settings → About → tap **Build number** 7 times → back → **Developer options** → turn on **USB debugging** (and **Wireless/Network debugging** if shown). Connect the unit to your home Wi-Fi. |
| 2. On your laptop | Put the laptop on the same Wi-Fi. Clone this repo (`git clone https://github.com/riazosama/headunit-carplay`), open the folder in Claude Code (`claude`) and run `/enable-carplay`. |
| 3. Follow along | Accept "Allow USB debugging?" on the unit, then answer the assistant's questions about which apps you use. |

The assistant backs up, diagnoses, enables CarPlay, installs the screen app, and checks each step with screenshots pulled from the unit.

## The skill and the prompt

The recommended way is the repo's agent skill (`.claude/skills/enable-carplay/`): open the repo in Claude Code and run `/enable-carplay`. It asks before every change, does the wired path, and does the wireless path only when you ask for it, step by step. If your assistant doesn't support skills, use the paste-in prompt in the [README](../README.md#with-another-assistant-the-prompt) instead; it does the wired path only.

## Doing it by hand

Everything the assistant does, you can run yourself. You need ADB (`brew install android-platform-tools` on macOS, or Google's platform-tools on Windows).

```bash
# 1. Connect (replace with your unit's IP, on the same Wi-Fi)
adb connect <UNIT-IP>:5555
adb root
adb connect <UNIT-IP>:5555

# 2. Back up before touching anything (getprop, settings, packages, /vendor/build.prop -> backup/)
./backup.sh <UNIT-IP>:5555

# 3. Point CarPlay at the real chip address and switch it on
#    (0x10 / decimal 16 was mine; find YOURS by probing the I2C bus first)
adb shell setprop persist.carplay.i2c.slave_addr 16
adb shell settings put global carplay_on 1

# 4. Install the missing on-screen CarPlay app (built against the unit's own API)
adb install carplay-screen/CarPlay.apk
```

Then plug in your iPhone. CarPlay comes up, with touch, Siri, and sound through the car speakers.

**The chip address is the catch.** Mine was wrongly set to `0x11` when the chip actually answers at `0x10`. Yours may differ. The assistant probes the bus to find it. By hand, you'd read the I2C bus to see which address the chip responds on before setting it.

## Optional — clean up what CarPlay replaces

With CarPlay running, the unit's own maps and mirroring apps are dead weight. Maps isn't something the unit needs to run, so removing it for your user doesn't break the rule above, and `restore-wired.sh` puts it back.

```bash
adb shell pm uninstall -k --user 0 com.google.android.apps.maps   # keeps data; restore with:
adb shell cmd package install-existing com.google.android.apps.maps
```

---

# Wireless CarPlay

This is the part the vendor shipped half-finished. It works on my unit: the iPhone hands over the Wi-Fi details over Bluetooth, joins the head unit's hotspot, and the CarPlay screen opens by itself. Sound, Siri and calls work through the car's Bluetooth for now (see the status table).

Read all of this before you touch the unit. The wireless path has one risky step (a one-line edit to `/vendor`), it takes your unit off your home network, and it is the most unit-specific thing in this repo.

## Status, honestly

| Item | Status on my unit | Notes |
|---|---|---|
| Wireless CarPlay screen | **Works** | AirPlay session starts, screen stream renders in the CarPlay screen app |
| Touch | **Works** | Touch rides the same vendor rendering path as wired |
| Auto-connect at car start | **Works** | Up to about a minute after boot, as long as `authd` is still running. After a full reboot, run `authd spawn` again (Step 5) |
| Sound, Siri, calls | **Workaround** | With the iPhone connected to the car's Bluetooth and the head unit's audio source set to Bluetooth, everything plays through the car speakers. CarPlay's own audio doesn't come through the wireless session (the bridge and the head unit's Bluetooth conflict), so it's a manual step for now. Making it seamless, like wired, is in progress |
| Other units | Untested | The four values in **The four things that must match** are the usual reasons it silently fails |

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
- The backup and the revert commands somewhere you can reach without the unit. See the risks below.

## Before you start — back up, and know the risk

The one risky change is the one-line edit to `/vendor/build.prop`. On my unit, `/vendor` corrupted (ext4 "structure needs cleaning", I/O errors) only after many repeated writes during earlier experiments that are no longer in this repo. One edit and one revert is a small risk, but if it does go wrong, a factory reset won't fix `/vendor`; you'd need to reflash the firmware.

```bash
# Back up the file you are about to edit, and keep it somewhere else
adb root
adb pull /vendor/build.prop vendor-build.prop.backup
adb shell 'cp /vendor/build.prop /data/local/tmp/build.prop.orig'
```

Rules that keep you out of trouble:

- **Make the `/vendor` edit once.** Don't loop on it. Get everything else ready first.
- **If you can't afford a firmware reflash,** stick to the wired path.
- **Keep the unit on power.** A power cut mid-write is the main way a single edit goes wrong.
- **You will lose ADB over Wi-Fi** in wireless mode. The Wi-Fi chip cannot be a station and an access point at the same time, so the hotspot takes the interface and the unit leaves your home network. Reconnect through the unit's own hotspot (Step 4).
- **Reverting needs the same edit**, so keep the commands somewhere you can reach without the unit (this file, or your phone).

## Step 1 — Build the two helpers

Prerequisites on the laptop: Android SDK build-tools 35, `android.jar` for API 35, JDK 17, and NDK 26.1 for `authd`. The build scripts assume macOS Homebrew paths; edit them for your machine.

```bash
cd headunit-carplay

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
| Hotspot IP | The gateway your laptop gets after joining the AP. Mine changed on every hotspot start (`192.168.43.x`, last octet random). Leave `persist.cpbridge.ap_ip` unset: the app reads it live, and a pinned value goes stale on the next hotspot start. | Session request |
| Unit Bluetooth MAC | `settings get secure bluetooth_address` | Identification |
| iPhone Bluetooth MAC | From the unit's bonded devices | Which phone to dial |
| AirPlay public key | `/data/vendor/carplay/default.keychain` — the "AirPlay Pairing Identity" item stores `hex(private32) + "+" + hex(public32)`. Use the public half (64 hex characters). | Session request |
| AirPlay source version | The version the running receiver reports (mine was `320.17.3`). Watch the receiver's log or its Bonjour record. | Session request |
| AirPlay port | 7000, the app's default; nothing to set | Session request |

For the keychain: it is root-only, so pull it with root and extract only the public half. The item holds your private key too, so don't print it whole.

```bash
adb root
adb exec-out cat /data/vendor/carplay/default.keychain | python3 -c 'import plistlib,sys; print(next((i["kSecValueData"].decode().split("+")[1] for i in plistlib.loads(sys.stdin.buffer.read()) if i.get("kSecAttrService")=="AirPlay Pairing Identity"), ""))'   # public half only, no file
```

The key pair is stored as binary data, which `plutil -p` shows as hex bytes, so a `plutil | grep` prints nothing.

**Do not paste the private half, the certificate, or the keychain file into a repo, an issue, or a chat.** They are your unit's identity. If you publish your unit's values, someone can impersonate your car.

## Step 3 — Turn on wireless mode

This is the risky edit. Do it once.

```bash
./scripts/wireless-mode.sh on <UNIT-IP>:5555
```

It backs up `/vendor/build.prop` if `backup.sh` did not, gets root, remounts `/vendor`, writes the line once, and reboots only after the edit reads back. If it exits 4, stop: don't retry and don't reboot (it prints why). Never run `adb disable-verity`.

`ro.atc.carplay.wireless` lives around line 414 of `/vendor/build.prop` on this firmware. `settings put global carplay_on 0` does **not** switch wireless off — the build.prop line is the switch.

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
- **There is a boot window.** The unit reconnects to your home Wi-Fi for roughly 20–60 seconds at boot before the hotspot takes over. If the hotspot is up and the gateway trick is not working, power-cycle and catch ADB on your LAN during that window (`scripts/wireless-recovery.sh` does this and can flip the property back).

## Step 5 — Deploy the auth helper and the bridge

With ADB through the hotspot:

```bash
# 1. MFi auth helper: must live in /data/local/tmp (a /vendor copy cannot load the vendor library)
adb push authd/authd authd/cpauth_client /data/local/tmp/
adb shell chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client
adb shell '/data/local/tmp/authd spawn; sleep 3; /data/local/tmp/authd status'   # the pidfile appears once the chip is open

# 2. Bridge app
adb install -r client/CarPlayBridge.apk

# 3. Tell the bridge your unit's values (persist across reboots)
adb shell setprop persist.cpbridge.peer_mac      "YOUR-IPHONE-BT-MAC"
adb shell setprop persist.cpbridge.ssid          "YOUR-HOTSPOT-SSID"
adb shell setprop persist.cpbridge.passphrase    "YOUR-HOTSPOT-PASSWORD"
adb shell setprop persist.cpbridge.channel       "YOUR-AP-CHANNEL"
adb shell setprop persist.cpbridge.bssid         "YOUR-AP-MAC"
adb shell setprop persist.cpbridge.bt_mac        "YOUR-UNIT-BT-MAC"
adb shell setprop persist.cpbridge.public_key    "YOUR-64-HEX-AIRPLAY-PUBLIC-KEY"
adb shell setprop persist.cpbridge.source_version "YOUR-RECEIVER-VERSION"
# leave persist.cpbridge.ap_ip unset: the bridge reads the hotspot IP live, and it changes on every start

# 4. Start it
adb shell am start-foreground-service -n com.cpbridge/.BridgeService
```

`authd` does not survive a reboot as a service. After every boot, run `adb root`, reconnect, and `adb shell /data/local/tmp/authd spawn` again (the bridge retries the socket for a while before giving up). Do not install it into `/vendor` or `/system`; the linker namespace will not let it load the vendor MFi library from there.

## Step 6 — Bring the iPhone in

- Bluetooth on, the unit already bonded, iPhone **not** plugged into USB.
- Make sure the hotspot is actually up (`adb shell ip addr show ap0` should show an address and a MAC). If the vendor service didn't bring it up, the wireless switch or boot ordering is off — revert and retry.
- Start the bridge, then watch the funnel. Every milestone is one line, all at error priority because this ROM drops lower priorities for normal apps:

```bash
adb logcat -v time | grep CPBRIDGE_DIAG
```

Expected progression:

```
[stage=a_rfcomm_connect] OK client-mode peer=...
[stage=b_link_sync] VERSION DETECT OK ... NEGOTIATION COMPLETE: peer acked our SYN -> NORMAL
[stage=d_session] RX msgId=0x1D00 ... IDENTIFICATION ACCEPTED (0x1D02)
[stage=e_auth] RequestAuthenticationCertificate (0xAA00) ... AUTH SUCCEEDED (0xAA05)
[stage=f_credentials] SENT 0x5703 ssid="..." channel=...
[stage=g_carplay] CarPlayAvailability (0x4300) ... SENT 0x4301 CarPlayStartSession
```

Then the iPhone drops Bluetooth, joins the hotspot, and the AirPlay session starts on port 7000. If you want more detail, the full session log survives link teardown:

```bash
adb shell cat /sdcard/Android/data/com.cpbridge/files/cpbridge.log
```

That log holds your hotspot SSID, MAC addresses, the MFi serial and hex dumps that include the passphrase. To share it, use `./scripts/bridge-log.sh <adb-serial> all`, which strips them.

## Step 7 — What success looks like

- The iPhone joins the unit's hotspot (you'll see it in the unit's Wi-Fi settings if you can get to them, or in `dumpsys`).
- The CarPlay screen appears in `app.carplayscreen` on the unit, usually within about a minute of the session request. The app opens itself when the receiver has a connection.
- Touch works.
- Sound, Siri and calls play through the car speakers with the iPhone connected to the car's Bluetooth and the head unit's audio source set to Bluetooth.

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
| Screen up, no sound | Connect the iPhone to the car's Bluetooth and set the head unit's audio source to Bluetooth. |
| Session starts, screen blank | `app.carplayscreen` not installed or not started. Reinstall it from the wired setup, then start the bridge again. |
| Hotspot never appears after reboot | The wireless property did not stick, or the vendor service failed. `grep atc.carplay.wireless /vendor/build.prop`; if it is 1 but no `ap0`, check the vendor service logs. |
| Unit unreachable after switching | Expected: it is off your LAN. Join the hotspot, or power-cycle and catch the ~20–60 s boot window on your home Wi-Fi. |
| Repeated re-dial loops after the session starts | Normal for the first seconds — the iPhone drops Bluetooth after credentials. If it never settles, check whether the screen came up at all. |

## Revert

Two ways back. If you still have ADB through the hotspot:

```bash
./scripts/wireless-mode.sh off <AP-IP>:5555   # gets root, writes /vendor once, reads it back, then reboots
```

If you are locked out of the hotspot, power-cycle and catch the boot window on your home Wi-Fi (`scripts/wireless-recovery.sh` automates it). Turning the unit's Bluetooth off also stops the hotspot, which can hand the Wi-Fi interface back to station mode.

After the revert you are on your home network again and normal wired CarPlay still works. Remove the bridge as well (`adb uninstall com.cpbridge`, and `authd` from `/data/local/tmp`): left installed, it keeps trying to reach your iPhone over Bluetooth.

## Risks, security and the law

- **This is the most unit-specific, least portable part of the repo.** The values, the partition layout and the vendor stack differ between units and firmware builds. Treat it as experimental.
- **Apple's protocols are proprietary.** This work reverse-engineers the accessory side for interoperability on hardware you own, using the genuine Apple authentication chip that is already on the board. It does not bypass or forge that authentication. Depending on where you live, reverse-engineering and modifying your own device may still conflict with Apple's MFi terms or local law; that is your call to make.
- **Do not publish your unit's identity.** The MFi certificate, the keychain, your Bluetooth and Wi-Fi MAC addresses, the hotspot credentials and the serial number are all yours. The repo's examples use placeholders; if you fork it, scrub the baked-in defaults first.
- **Network ADB gives root.** Both paths turn on network debugging with root. Check that `adb shell getprop ro.adb.secure` returns `1` (each new computer then has to be approved on the unit). If it returns `0`, anyone on the same network, or on the unit's hotspot in wireless mode, can open a root shell. Use a strong hotspot password and turn network debugging off when you're done; in wireless mode you need it again after a full reboot to restart `authd`.
- **`authd` answers any app on the unit.** Its socket (`/data/local/tmp/cpauth.sock`) is world-writable and doesn't check who connects, so any app installed on the head unit could have the Apple chip sign challenges. Only install apps you trust on the unit, and run `authd stop` if you stop using wireless.
- **No warranty.** The worst case on the wireless path is a corrupted `/vendor` partition, which needs a firmware reflash. Back up `build.prop` and keep the revert commands handy.

---

*One specific unit (Autochips AC8257, test-keys `userdebug` build, root over ADB). Relies on root and a debuggable build. Everything lives in user storage or `/vendor`, so a factory reset does not undo the wireless switch; the revert commands above do. A wrong change can stop CarPlay from starting until you undo it, so back up every file you replace. Unit-specific secrets (Apple key material, the AirPlay pairing keychain, Bluetooth and Wi-Fi addresses, hotspot password, serial) are left out of this repo and must not be added. Not affiliated with Apple or Honda. CarPlay is a trademark of Apple Inc.*
