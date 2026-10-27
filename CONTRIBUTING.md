# Contributing

This repo exists so the next person with the same head unit skips the weekend of diagnosis. The most useful contribution is usually not code — it is a confirmed data point about a unit or a firmware build.

## What to contribute

1. **Supported-unit entries.** Add a row to the table in the README with your unit model, board, Android version, build type (`userdebug`/`eng`/`user`), whether root works, and exactly which parts work: wired screen, touch, car audio, wireless screen, wireless audio.
2. **Fixes and recovery notes.** If a step needed a different value or command on your unit, say which and why. Unit-specific quirks are the whole point.
3. **Cleanup of the docs.** Typos, unclear steps, missing prerequisites — all welcome.

## Rules

- **No unit-specific secrets, ever.** That includes the hotspot SSID and passphrase, the home Wi-Fi name, any Bluetooth or Wi-Fi MAC address, IP addresses from your network, the serial number, the MFi certificate or its serial/SHA-256, the AirPlay pairing keychain or its keys, and any log or capture that contains them. Use placeholders (`YOUR-HOTSPOT-SSID`, `<unit-ip>`, `AA:BB:CC:DD:EE:FF`) and keep real values on your own unit.
- **No vendor binaries.** Do not commit `libAirPlay*.so`, `libCoreUtils.so`, `libcarplay_plugin_jni.so`, or anything else copied out of a head unit's partitions. They are not redistributable, and shipping them would sink the repo.
- **No raw dumps.** Full logcat files, packet captures and firmware images almost always contain identifiers. Summarise the finding instead.
- **Keep the defaults generic.** The code ships with placeholder Wi-Fi values and a placeholder Bluetooth address on purpose; users set their own with `setprop`. Do not "helpfully" commit the values from your car.
- **One change per pull request.** Keep the wireless path and the wired path separate; they have very different risk profiles.

## Build and test

```bash
./client/test-protocol.sh     # offline protocol checks, no device needed
./client/build.sh             # -> client/CarPlayBridge.apk
./authd/build.sh              # -> authd/authd, authd/cpauth_client
./audio/build.sh              # -> audio/libAudioConverter.so
```

Requirements are in the README (Android SDK build-tools 35, API 35 platform, JDK 17, NDK 26.1). If your build tools live somewhere else, set `ANDROID_SDK_ROOT`, `JAVA_HOME` or `NDK` instead of editing the scripts.

`./client/test-protocol.sh` must pass before a pull request that touches the protocol code. If you add a wire-format test, base it on a public capture or on your own device capture with the identifiers stripped.

## Adding a supported unit

Copy the row format:

```markdown
| Unit (board) | Build | Wired | Wireless |
|---|---|---|---|
| Make/model, Autochips AC8257 | Android 9, `userdebug`, test keys, root | CarPlay, touch, audio, auto-open | Screen and touch; audio stays on phone |
```

If something did not work, add it as a note under the table rather than leaving the row blank — a negative result is still a result.

## Security

Do not open an issue with your unit's key material, certificate or logs. If you think you have found a security problem in this tooling, describe it without including any device-specific values.
