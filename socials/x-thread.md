# X thread draft

Before posting: make the repo public (it's private right now, so the link 404s for everyone else), and record a 15–20 second video of plugging the iPhone in and CarPlay popping up on the dash. Before/after is even better.

---

**1/** (attach the video)

My Honda City's Android screen had Apple CarPlay inside it the whole time.

Switched off. Pointed at the wrong chip. Missing the app that draws it.

Gave Claude Code the job over Wi-Fi. Now my iPhone pops up on the dash the second I plug in 👇

**2/**

The screen is an aftermarket Android unit. Claude Code connected to it over ADB and went digging.

Apple's CarPlay software was already installed. So was a genuine Apple authentication chip.

The vendor just shipped it half-finished and switched off.

**3/**

Why it never worked: the config told CarPlay the chip lived at 0x11.

It actually answers at 0x10.

One wrong number, so authentication failed silently every single time.

**4/** (attach the photo of the CarPlay home screen)

The fix:
→ point CarPlay at the real chip
→ switch it on
→ Claude Code wrote the missing screen app against the unit's own CarPlay API

Touch, Siri, music through the car speakers. Opens by itself on plug-in. Fully reversible.

**5/**

Wireless was harder. The unit had the receiver half but not the app that brings the iPhone in over Bluetooth.

So I built it: an iAP2 bridge, plus a helper that signs Apple's challenge with the unit's real chip. No forged auth.

Screen works. Sound still plays from the phone.

**6/**

Will it work on your car? Only if your head unit is a rootable debug build. Two-minute check:

adb root
adb shell pm list packages | grep -iE "carplay|autochips|airplay"

Root works and CarPlay shows up = you're where I was.

**7/**

It's all open source: the prompt to paste into Claude Code, the screen app, the wireless bridge, and a one-line restore.

github.com/riazosama/CarPlayAlreadyThere

Try it on your unit and reply with the model. I'll add it to the supported list.

**8/**

What gets me: CarPlay is a basic feature, and this screen could do it all along. Someone just shipped it switched off.

How many cars have CarPlay sitting one setting away right now?

---

## Quote-post / reply version

For quoting the Honda Civic head-unit post that's going around, and for replying under the big "I hacked my TV with Claude Code" posts. Attach the same video.

> Same story with my Honda City's Android head unit, except Claude Code found Apple CarPlay already installed and switched off. Wrong chip address, missing screen app. Wired CarPlay works now. Prompt + repo in my thread.

## Posting notes

- Post 1 carries the video and the whole story. Most people never open the thread, so the hook has to stand on its own.
- Put the GitHub link in post 7, not post 1. X tends to show posts with external links to fewer people.
- Say "Claude Code" in the first line. That's the phrase people are searching for this week.
- Keep the villain accurate: the unit is aftermarket, so the vendor shipped it off, not Honda. Posts that blamed the carmaker got corrected in the replies.
- Wireless audio doesn't work yet. Never imply it does.
- Post the Reddit version (reddit-post.md) the same day. The Honda Civic head-unit story reached X through r/ClaudeCode.
- Reply to everyone in the first hour, especially people asking whether it works on their car. Point them to the two-minute check.
