# Wireless CarPlay (experimental)

Read this only when the user has explicitly asked for wireless and the wired path in
[SKILL.md](SKILL.md) works on their unit. The rules there still apply, above all: ask before every
change, keep the unit's identity out of the conversation, and name the device on every adb call.

The firmware ships the receiver half of wireless CarPlay (AirPlay receiver inside
`com.autochips.carplay`, hotspot, `_airplay._tcp` on port 7000) but not the client that brings
the iPhone in. `client/` + `authd/` supply it. Expect to debug: the per-unit values in W4 are the
usual reason a session silently fails.

Before starting, say clearly:

- Screen and touch work. Sound, Siri and calls work only with the iPhone connected to the car's
  Bluetooth and the head unit's audio source set to Bluetooth. It's a manual workaround, not
  CarPlay's own audio; say so.
- The unit leaves the user's Wi-Fi and raises its own hotspot; ADB has to be re-established
  through that hotspot.
- The `/vendor` edit in W2 is the one risky step: unlikely to go wrong once, but a corrupted
  `/vendor` needs a firmware reflash. Do it once, with the unit on power.
- Network ADB stays on (the auth helper has to be started over ADB after every reboot), so
  **Network ADB gives root** applies to everyone who joins the hotspot. The bridge also keeps the
  hotspot passphrase in a property any app on the unit can read: use a strong passphrase that is
  unique to this hotspot.
- `authd` answers any app on the unit: its socket is world-writable and doesn't check who
  connects, so any installed app could have the Apple chip sign challenges. Recommend trusted
  apps only on the unit, and `authd stop` if they stop using wireless.

## W0 — Pre-checks and consent

```bash
adb shell 'grep -c "^ro.atc.carplay.wireless=" /vendor/build.prop; getprop ro.build.type
  getprop ro.boot.veritymode; getprop ro.adb.secure; grep " /vendor " /proc/mounts'
```

- `0` matching lines: the gate isn't in `/vendor/build.prop` under that name. Before ruling
  wireless out, look elsewhere — `adb shell 'getprop | grep -iE "carplay.*(wireless|wifi)"'` and
  the same grep over `/odm/build.prop /product/build.prop`. If a switch under another name turns
  up, show the user and treat it as the W2 edit only with their explicit yes. If there is
  genuinely none, the firmware has no wireless receiver and the bridge cannot substitute for it;
  a wired-to-wireless CarPlay adapter on the USB port is the alternative.
- `ro.build.type` must be `userdebug` or `eng`, and verity must not be enforcing. Never run
  `adb disable-verity`: it is another write on the `/vendor` side.

Then ask the user, and stop unless every answer is yes:

1. **Can they recover the unit?** A second laptop or phone that can join the unit's hotspot, or
   the power-cycle boot window (20–60 seconds of home Wi-Fi at boot).
2. **Will the machine running you stay online?** Once its Wi-Fi joins the unit's hotspot, that
   hotspot usually has no internet. Without a second uplink (Ethernet, or USB tethering from a
   phone) you lose contact right after W2, and the user has to finish W3–W6 alone.
3. **Did network ADB come back after the wired path's reboot** (SKILL.md Step 4.4)?
4. **Is the unit on power, and do they accept that a corrupted `/vendor` needs a firmware
   reflash?**

## W1 — Build the helpers

```bash
./client/build.sh            # -> client/CarPlayBridge.apk
./client/test-protocol.sh    # offline checks; must pass before flashing the unit
./authd/build.sh             # -> authd/authd, authd/cpauth_client
./scripts/test.sh            # offline checks of the scripts that touch the unit
```

Requirements: Android SDK build-tools 35.0.1 (exactly that version), API 35 platform, JDK 17,
NDK 26.1. If the tools live elsewhere, set `ANDROID_SDK_ROOT`, `JAVA_HOME` or `NDK` rather than
editing the scripts.

## W2 — Turn on wireless mode (the one risky edit)

With an explicit "yes" for this step, and the unit on power, run it once:

```bash
./scripts/wireless-mode.sh on <UNIT-IP>:5555
```

It refuses any device without the `ro.atc.carplay.wireless` line. It backs up `/vendor/build.prop`
if `backup.sh` did not, gets root and reconnects, and writes the line at most once. It reboots only
after the edit reads back with the file size unchanged. Its exit code says what to do next:

| Exit | Meaning | Next |
|---|---|---|
| 0 | Line set, unit rebooting | W3 |
| 1 | Nothing written: no transport, no root, or `/vendor` read-only | Fix the cause it printed, then it may run again |
| 3 | Not the head unit | Check the serial |
| 4 | Verity is on, the line has an unexpected value, or the read-back was wrong | Stop and tell the user. Do not retry, do not reboot. After a bad read-back, put the original back while ADB still works: `adb root`, reconnect, `adb remount`, `adb push backup/vendor-build.prop /vendor/build.prop`, `adb shell sync` |

`settings put global carplay_on 0` does **not** clear this — the build.prop line is the switch.

## W3 — Reconnect ADB through the unit's own hotspot

**If the unit is still on the home Wi-Fi after the reboot**, check for the hotspot interface:

```bash
adb shell 'getprop ro.atc.carplay.wireless; ip link show ap0 >/dev/null 2>&1 && echo ap0-up || echo no-ap0'
```

`1` with `no-ap0` means wireless mode is on but the hotspot is not up, and W4 cannot work: the
bridge has no hotspot to hand the iPhone. The vendor service raises the hotspot at boot, and
turning Bluetooth off stops it until the next boot. Make sure Bluetooth is on, reboot once and
check again. If `ap0` is still missing, find out why before giving up: read
`adb logcat -d | grep -iE "softap|hostapd|carplay"` for the vendor service's hotspot errors, and
have the user turn the hotspot on once from Settings to see whether the radio can run an AP at
all. Report what you find. Do **not** edit `/vendor` again.

**Otherwise the unit has left the home network.** Have the user join the unit's AP from the
laptop's Wi-Fi menu (the passphrase is on the unit under Settings → Hotspot), so the passphrase
never passes through you. Then read the gateway of the Wi-Fi interface (with a second uplink there
are two default routes) and connect to it:

```bash
netstat -nr | grep default                 # macOS: gateway column = unit AP IP
ip route | grep default                    # Linux
ipconfig | findstr /i "Default Gateway"    # Windows

adb disconnect <UNIT-IP>:5555              # the old LAN address is dead now
adb connect <AP-IP>:5555
adb -s <AP-IP>:5555 root                   # the reboot dropped root; W4 needs it
adb connect <AP-IP>:5555
adb -s <AP-IP>:5555 shell id -u            # 0
```

From here on the serial is `<AP-IP>:5555`. The AP IP is **not** reliably `192.168.43.1` —
Android randomizes it on each hotspot start. If you are locked out, see W6.

## W4 — Deploy the auth helper and the bridge, set this unit's values

The hotspot only exists in wireless mode, so this is the first point where every value below can
be read.

```bash
# authd must live in /data/local/tmp so it can dlopen the vendor MFi library
adb push authd/authd authd/cpauth_client /data/local/tmp/
adb shell 'chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client
  /data/local/tmp/authd spawn >/dev/null; sleep 3; /data/local/tmp/authd status'   # running pid=...

adb install -r client/CarPlayBridge.apk
```

`spawn` writes its pidfile only once the chip is open (about 2 seconds), hence the wait before
`status`.

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the bridge already on the unit was
signed with another key (`client/build.sh` makes a new `client/debug.keystore` when there is
none). Ask, then `adb uninstall com.cpbridge` (a user app, not a system package; the
`persist.cpbridge.*` values stay) and install again. Keep `client/debug.keystore` for later
updates.

These values decide whether a session starts. When one is wrong, every failure looks the same:
authentication succeeds, the credentials go out, and no CarPlay appears.

| Value | Set it | Why |
|---|---|---|
| Hotspot SSID / passphrase | The user, in their own terminal (below) | Wi-Fi credentials (`0x5703`); the defaults are placeholders |
| Unit Bluetooth MAC | On the unit (below) | Identification; the default is a placeholder |
| AP interface MAC | On the unit, from `ap0` (below) | BSSID and device ID. It must be the AP MAC: if the bridge cannot read it, it falls back to the Bluetooth MAC, which is the silent failure |
| Hotspot channel | On the unit, from `iw` (below) | Must be the AP's real channel; it had to be pinned on the reference unit |
| Hotspot IP | Leave empty (below) | Android picks a new `192.168.43.x` on every hotspot start; the bridge reads `ap0` itself |
| AirPlay public key | From the keychain, never printed (below) | Session request (`0x4301`); without it no session is requested |
| AirPlay source version | Only if `srcvers` differs from the default `320.17.3` (below) | An old value makes the receiver refuse the session |
| iPhone Bluetooth MAC | Leave unset: the bridge dials the bonded devices | With several bonded phones, the user sets `persist.cpbridge.peer_mac` the same way as the SSID |

`client/PHASE_B.md` lists every `persist.cpbridge.*` value and its default.

Set the unit's own addresses and channel on the unit itself, so they never pass through the
conversation (the channel is not a secret):

```bash
adb shell '
m=$(settings get secure bluetooth_address)
case $m in ??:??:??:??:??:??) setprop persist.cpbridge.bt_mac "$m"; echo bt_mac ok ;; *) echo "bt_mac: no address; is Bluetooth on?" ;; esac
b=$(cat /sys/class/net/ap0/address 2>/dev/null)
[ -n "$b" ] && setprop persist.cpbridge.bssid "$b" && echo bssid ok || echo "bssid: no ap0"
c=$(iw dev ap0 info 2>/dev/null | sed -n "s/.*channel \([0-9]*\).*/\1/p")
[ -n "$c" ] && setprop persist.cpbridge.channel "$c" && echo "channel $c" || echo "channel: unknown; ask the user"
setprop persist.cpbridge.ap_ip ""'
```

Ask the user to set the hotspot credentials at the unit's own prompt, not through you. Typing them
there keeps them out of the laptop's shell history, and stops the laptop shell from parsing them a
second time:

```bash
adb -s <AP-IP>:5555 shell
setprop persist.cpbridge.ssid '<HOTSPOT-SSID>'
setprop persist.cpbridge.passphrase '<HOTSPOT-PASSPHRASE>'
exit
```

Inside the single quotes everything is literal except `'` itself, which is written `'\''`. Check
that both are set without showing them:

```bash
adb shell 'for p in ssid passphrase; do [ -n "$(getprop persist.cpbridge.$p)" ] && echo "$p set" || echo "$p MISSING"; done'
```

The keychain holds the unit's private key next to the public one
(`hex(private32)+"+"+hex(public32)`). This takes the public half straight into the property; no
file is written and nothing is printed:

```bash
K=$(adb exec-out cat /data/vendor/carplay/default.keychain | python3 -c 'import plistlib,sys; print(next((i["kSecValueData"].decode().split("+")[1] for i in plistlib.loads(sys.stdin.buffer.read()) if i.get("kSecAttrService")=="AirPlay Pairing Identity"), ""))' 2>/dev/null)
[ ${#K} = 64 ] && adb shell setprop persist.cpbridge.public_key "$K" && echo "public key set" || echo "no public key found"
```

The key pair is stored as binary data, which `plutil -p` shows as hex bytes, so don't swap in a
`plutil | grep`: it prints nothing. If it says no public key was found, stop and ask; do not
inspect the keychain to find out why.

Check the receiver's AirPlay version from the laptop while it is on the unit's hotspot. Print only
`srcvers`: the record also carries the unit's device ID. A Mac also lists its own AirPlay
Receiver, so pick the unit's name:

```bash
( dns-sd -B _airplay._tcp local. & sleep 3; kill $! )      # macOS: note the unit's name
( dns-sd -L "<UNIT-NAME>" _airplay._tcp local. & sleep 3; kill $! ) | grep -o 'srcvers=[0-9.]*'
avahi-browse -rpt _airplay._tcp | grep -F "<UNIT-NAME>" | grep -o 'srcvers=[0-9.]*' | sort -u   # Linux
```

Only if it is not `320.17.3`:
`adb shell setprop persist.cpbridge.source_version "<RECEIVER-VERSION>"`.

Then start the bridge:

```bash
adb shell am start-foreground-service -n com.cpbridge/.BridgeService
```

Once started, the bridge restarts itself at boot. `authd` does not: wireless opens by itself on
car start only while `authd` is still running. After any full reboot (power loss, `adb reboot`),
reconnect as in W3 (the AP IP may have changed), then start it again, or authentication fails
with `0xAA04`:

```bash
adb -s <AP-IP>:5555 root; adb connect <AP-IP>:5555
adb -s <AP-IP>:5555 shell '/data/local/tmp/authd spawn >/dev/null; sleep 3; /data/local/tmp/authd status'
```

Do not install it under `/vendor` or `/system`; the linker namespace blocks the vendor library
there.

## W5 — Bring the iPhone in and verify

- iPhone Bluetooth on, already bonded to the unit, **not** plugged into USB.
- Give it about a minute, then read the funnel. Every milestone is one stage line:

```bash
./scripts/bridge-log.sh <AP-IP>:5555
```

Expected ladder:

```
[stage=a_rfcomm_connect] OK client-mode peer=<mac> ...
[stage=b_link_sync] VERSION DETECT OK ... NEGOTIATION COMPLETE: peer acked our SYN -> NORMAL
[stage=d_session] IDENTIFICATION ACCEPTED (0x1D02)
[stage=e_auth] AUTH SUCCEEDED (0xAA05) -- real MFi accepted
[stage=f_credentials] SENT 0x5703 ssid=<redacted> passLen=... channel=...
[stage=g_carplay] CarPlayAvailability (0x4300) ... SENT 0x4301 CarPlayStartSession
```

Then the iPhone drops Bluetooth (normal), joins the hotspot, and the AirPlay session starts on
port 7000; the CarPlay screen appears in `app.carplayscreen` within about a minute. Pull a
screenshot and show the user.

If the ladder stops early, `./scripts/bridge-log.sh <AP-IP>:5555 all` shows every line, still
redacted. Never `cat` `cpbridge.log` or grep logcat yourself: the raw lines carry the SSID, MAC
addresses, the MFi serial, and the passphrase inside hex dumps. If the redacted view hides what you
need, ask the user to read the raw log in their own terminal.

| Symptom | Check |
|---|---|
| `a_rfcomm_connect` fails | iPhone bonded and Bluetooth on; with several bonded phones, `peer_mac` (the user sets it) |
| `e_auth` returns `0xAA04` | authd not running (`authd status`, `authd.log`; restart it as in W4); the chip is shared with the vendor service, retries are normal |
| Credentials sent, no AP join | SSID/passphrase/channel |
| Joins AP, no CarPlay screen | The W4 table: AP MAC, source version, empty hotspot IP, channel; then port 7000 and `_airplay._tcp` |
| Screen up, no sound | Have the user connect the iPhone to the car's Bluetooth and set the head unit's audio source to Bluetooth; CarPlay's own audio doesn't come through because the bridge and the unit's Bluetooth conflict |

## W6 — Revert

1. Remove the bridge and the helper, over the hotspot or the LAN:
   ```bash
   adb shell '/data/local/tmp/authd stop; cd /data/local/tmp && rm -f authd cpauth_client authd.pid authd.log cpauth.sock'
   adb uninstall com.cpbridge      # also deletes its log
   adb shell 'for p in $(getprop | grep -o "persist\.cpbridge\.[a-z_]*"); do setprop "$p" ""; done'
   ```
2. Turn wireless mode off (same exit codes as W2):
   ```bash
   ./scripts/wireless-mode.sh off <adb-serial>
   ```

**Locked out of the hotspot?** Run the recovery in the background (its default `TIMEOUT` is
1800 seconds, longer than a foreground command may run), then have the user power-cycle the
unit. It waits for the boot window and turns wireless mode off:

```bash
./scripts/wireless-recovery.sh [unit-ip ...]   # log: scripts/wireless-recovery.log
```

Exit 0 means the revert is applied; exit 4 means stop and tell the user. Do step 1 afterwards,
once the unit is back on the LAN.

If the user no longer needs ADB, suggest turning Network debugging off.
