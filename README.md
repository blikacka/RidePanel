# RidePanel

📖 Read this in [Čeština](README.cs.md)

A free, open-source Android app that mirrors your phone's screen onto a
motorcycle head-unit so you can ride with Google Maps, music, or any other
app on the dashboard instead of strapping the phone to your handlebars.

Inspired by — and protocol-compatible with — the *Carbit Ride*
ecosystem of head-units (SSDQ01 series, EasyConn-class infotainment), but
built independently from scratch as a community project. No cloud account,
no telemetry, no analytics. Everything runs locally between your phone and
your bike.

## What it does

- 📱 → 🏍️ **Screen mirroring** from your phone to the bike's 800×480
  dashboard panel via Wi-Fi Direct. H.264 video, hardware-accelerated.
- 🗺️ **One-tap Google Maps in landscape** — start the mirror and launch
  Maps with the orientation locked, perfect for navigation.
- ✅ **Connected splash** on the head-unit between pairing and screen
  sharing, so you know the phone is talking to the bike before you put
  the phone in your pocket.
- 💾 **Saved devices** — pair once via QR, reconnect with one tap next
  time.
- 🎯 **Aspect-correct image** — proper letterboxing regardless of phone
  orientation, no stretching.
- 🌍 **Multi-language**: English · Čeština · Slovenčina · Polski · Deutsch.

## How it works

Press the **top button on the left handlebar control** to bring up the
QR code on the dashboard. Scan it in RidePanel — your phone joins the
head-unit's Wi-Fi Direct network. RidePanel announces itself, opens a
small set of local TCP sockets, and waits for the head-unit to ask for
video. As soon as it does, the splash image appears on the dashboard
saying the phone is connected.

Once you tap **Start mirror** or **Open Google Maps**:

1. Android's standard `MediaProjection` captures your screen.
2. A `VirtualDisplay` feeds a GLES letterbox shader that aspect-fits the
   phone image into the head-unit's 800×480 frame (with black bars where
   needed).
3. `MediaCodec` encodes those frames as H.264 (Baseline @ Level 4, 4 Mbps,
   30 fps, 2 second key-frame interval — tuned for stable Wi-Fi P2P).
4. Frames are streamed live to the dashboard.

When you press **Stop**, the mirror tears down cleanly and your phone's
auto-rotate setting is restored to its original state.

## Tested hardware

| Phone        | OS         | Status |
|--------------|------------|--------|
| Pixel 10 Pro | Android 16 | ✅ daily-driver tested |

| Motorcycle / head-unit               | Display    | Status |
|--------------------------------------|------------|--------|
| **QJMOTO SRT 600 SX** (stock panel)  | 5" 800×480 | ✅ daily-driver tested |

Other phones and other compatible head-units in this ecosystem should
work — the protocol is the same. If you try a different combination,
please open an issue or send a PR with your findings.

## Install

The app isn't on Google Play (yet). Sideload the APK:

```bash
# Clone, build, install on a device with USB debugging on
git clone https://github.com/<your-fork>/ridepanel.git
cd ridepanel/RidePanel
./gradlew :app:installDebug
```

Or grab a pre-built `app-debug.apk` from
[GitHub Releases](../../releases) (when available) and install via
`adb install` / file manager.

**Permissions you'll be asked for**, all explained in the in-app FAQ
tab:

- *Notifications* — keeps the mirror service running in the background.
- *Modify system settings* — locks the phone to landscape while
  mirroring, so Google Maps doesn't flip to portrait when you tilt the
  bike.
- *Wi-Fi Direct* — for the QR pairing flow.
- *MediaProjection* (per session) — Android's standard "share screen"
  dialog.

## Contributing

**Pull requests are extremely welcome** 🙌 — RidePanel is a hobby
project maintained by riders who happen to also code. If you have:

- another bike / head-unit / phone combination tested,
- a translation into your language,
- a UX improvement or a bug fix,
- a new feature idea (audio passthrough? Mapy.cz integration? touch-back
  channel from the head-unit?) …

…open an issue to discuss, or just send a PR. No CLA, no gatekeeping —
don't be shy. Code style is whatever clean Kotlin looks like for the
files you're touching.

## Buy me a coffee ☕

If RidePanel saved you the price of a phone-mount-plus-cables setup, you
can drop a tip via PayPal:
[paypal.com/donate](https://www.paypal.com/donate/?hosted_button_id=XL58JUWCCWWPC)

Purely voluntary — the app is and will always be free.

## License

MIT — see [LICENSE](../LICENSE). Use it, modify it, ship it commercially
if you like; just keep the copyright notice. The software is provided
"AS IS", without warranty of any kind — riding a motorcycle is a
high-stakes activity, do not depend on the app for anything
safety-critical.
