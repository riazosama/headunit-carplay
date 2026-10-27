# Reddit post draft

Primary: r/ClaudeCode. Also fits r/CarPlay and r/AndroidHeadUnits, but read each sub's self-promotion rules first and post them a day apart, not all at once. Attach the CarPlay home-screen photo (or the video). Make the repo public before posting.

---

**Title:** Claude Code found Apple CarPlay already installed (and switched off) on my car's Android head unit

**Body:**

My Honda City has an aftermarket Android screen (Autochips AC8257 board) that never showed CarPlay. After everyone here started cleaning up their smart TVs with Claude Code, I pointed it at the car.

I connected the laptop to the unit over ADB on the same Wi-Fi and asked Claude Code to figure out whether CarPlay was possible at all. It turned out CarPlay was already there. Three things were wrong:

1. **CarPlay was switched off** in the system settings.
2. **The config pointed at the wrong chip.** The board has a genuine Apple MFi authentication chip that answers at `0x10`, but CarPlay was told to look at `0x11`. So every iPhone failed authentication silently.
3. **The app that draws CarPlay on screen was never shipped.** Claude Code wrote a minimal one against the unit's own CarPlay API: full-screen video, touch forwarding, opens when a phone connects.

After that, wired CarPlay just works: touch, Siri, music through the car speakers, and it opens by itself when I plug in. Every change is a setting or an app, and there's a restore script.

**Wireless** was a much longer road. The firmware has the receiver half of wireless CarPlay, but not the app that brings the iPhone in over Bluetooth. I built that part: an iAP2 bridge, plus a small root helper that has the unit's real Apple chip sign the authentication challenge (nothing forged). The screen and touch work over Wi-Fi. **Audio still plays from the phone.** The unit is missing the AAC decoder, and my replacement isn't verified in a live session yet.

**Will it work on yours?** Only if the unit is a rootable debug build. Two-minute check:

```
adb root
adb shell pm list packages | grep -iE "carplay|autochips|airplay"
```

If root works and something CarPlay-ish shows up, you're where I was.

Repo with the prompt to paste into Claude Code, the screen app, the wireless bridge, a Claude Code skill, and the restore scripts: https://github.com/riazosama/CarPlayAlreadyThere

Do the wired path only. The wireless path writes to `/vendor`, which corrupted that partition on my unit after repeated edits. Don't try it on a car you depend on.

If you try it, I'd love to know your unit model and whether the chip address was wrong on yours too.
