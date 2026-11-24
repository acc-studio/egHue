# egHue: Product Spec

**egHue** is a fast, simple Android app for Philips Hue. It doesn't compete on customisation. It
competes on three things:

| Pillar | What it means in practice |
|---|---|
| **Responsiveness** | Every tap updates the screen instantly (optimistic UI) and reaches the bridge over an already-open connection. The app opens on cached state, so there's never a blank screen. |
| **Connectivity** | Finds the bridge by itself, follows it if its IP address changes, routes over Wi-Fi even when mobile data is the default, reconnects when the network changes, and says plainly when it can't reach the bridge. |
| **Control** | Every room, zone and light is one tap away on the home screen. Drag sideways on any tile to dim it. Hue-app scenes, colour and white are one long-press away. |

All screenshots below are from the app running on an Android 15 emulator against a simulated Hue
Bridge (`tools/fake_bridge.py`). Colours follow Material You (wallpaper) on Android 12+, and in-app
light colours match the real lights.

---

## 1. Connecting to the bridge

<img src="screenshots/01-pairing.png" width="300" align="right" alt="Pairing screen">

**Goal: connected in one button press.**

1. **Automatic discovery** runs as soon as the app opens, using three methods at once:
   - mDNS (`_hue._tcp`), which is how the Hue app finds bridges,
   - Philips' discovery service (`discovery.meethue.com`),
   - and, if neither answers within 3 s, a quick scan of the local /24 subnet.

   Every candidate is confirmed by asking it for its bridge ID, so only real Hue bridges appear.
2. **One bridge found** (the usual case): egHue goes straight to *"Press the button on your bridge"*,
   with no extra tap. It polls the bridge every second for 60 s and continues the moment the button is
   pressed.
3. **Several bridges:** pick one from the list.
4. **Fallback:** *Enter IP address instead* (supports `ip:port`).

After pairing, egHue stores the app key and pins the bridge's certificate identity. All traffic is
HTTPS on the local network. No account, no cloud.

<br clear="right">

---

## 2. Home screen

<p>
<img src="screenshots/02-home-light.png" width="300" alt="Home, light theme">
&nbsp;
<img src="screenshots/03-home-dark.png" width="300" alt="Home, dark theme">
</p>

Everything from the bridge, in three sections: **Rooms**, **Zones** and **Lights**
(alphabetical, carried over exactly from the Hue Bridge).

| Gesture on a tile | Result |
|---|---|
| **Tap** | Toggle on/off (with haptic tick) |
| **Drag left/right** | Dim; the tile fills with the brightness level and the % shows live, with a tick every 10% |
| **Long-press**, or tap **›** | Open full controls |

Each tile shows:
- **Colour:** an *on* tile takes the actual colour of its lights (the average for a room or zone). An *off* tile is neutral.
- **Fill:** the brightness level, as a band from the left.
- **Status:** `65%`, `2 of 3 on · 85%`, `Off`, `Unreachable` or `No lights`.

Top bar: **All off** (turns the whole home off through the bridge's "all lights" group) and
**Settings**. **Pull down** to force a refresh. If the bridge can't be reached, a banner says so
and offers **Retry**, while the last known state stays visible.

---

## 3. Full controls (detail sheet)

<p>
<img src="screenshots/04-detail-white.png" width="300" alt="Room controls, white">
&nbsp;
<img src="screenshots/05-detail-colour.png" width="300" alt="Room controls, colour wheel">
</p>

Opens as a bottom sheet over the home screen:

- **Header:** name, status and an on/off switch.
- **Brightness bar:** tap or drag anywhere on it.
- **White | Colour:** shown according to what the lights support.
  - *White:* a warm-to-cool temperature bar plus presets (*Warm*, *Soft*, *Bright*, *Daylight*).
  - *Colour:* a hue/saturation wheel; drag to change colour live.
- **Presets:** four warm, designed looks in every room and zone: *Evening*, *Unwind*, *Dinner*
  and *Late*. Each fixture gets its own light by type (ceiling, pendant, lamp, strip/accent):
  lamps carry the room, overhead lights sit lower, strips glow amber or ember. No cool white.
  The first tap saves the preset to the bridge as a real scene, so it then also works on remotes
  and in other Hue apps; it is rewritten automatically if the room's lights change.
- **Scenes:** **every scene saved on the bridge, including those created in the Hue app**, shown
  as tiles in the scene's own colours. Tap to activate; the active scene is outlined and ticked.
- **Lights** (rooms and zones): every light in the group, with the same tap, drag and open gestures.
  Opening a light shows its own controls, with a back link to the room.

---

## 4. The widget

<p>
<img src="screenshots/06-widget-config.png" width="300" alt="Widget setup">
&nbsp;
<img src="screenshots/07-widget.png" width="300" alt="Widget on the home screen">
</p>

**"Put it on the home screen, tap a room."** That's the whole design.

- **Setup:** when you add the widget, pick which **rooms, zones and/or lights** it shows.
  All rooms are ticked to start with. Long-press the widget › *Reconfigure* to change it later.
- **One tap toggles**, and the tile changes at once. It works even when the app isn't running: the
  widget starts the process, checks the current state if its cache is older than 15 s, then switches.
- **Clear state:** *on* tiles glow in the light's real colour and show the brightness; *off* tiles are
  muted and say `Off`.
- **Responsive layout:** 1 column on narrow widgets, 2 at 4 cells wide, 3 when wider. Tiles grow or
  shrink to fill the height and scroll when there are too many.
- **Stays current:** it refreshes when the app changes something, when another Hue app or switch
  changes something while egHue is open, and every 30 min in the background.
- Follows the system theme (and Material You colours).
- If the bridge can't be reached, a small "Can't reach the bridge" line appears.

---

## 5. Power-on behaviour ("default on")

<img src="screenshots/09-power-on.png" width="300" align="right" alt="Power-on behaviour editor">

Each Hue light has a **power-on behaviour**: what it does when it gets power, for example from a
wall switch or after a power cut. It's the same setting the Hue app edits under *Power-on behaviour*,
and it's stored in the light, so it works with any app or none.

- Open any light's (or room's) controls › **Power-on behaviour**:
  - **Hue default:** warm white, full brightness.
  - **Power loss recovery:** back to how it was, on or off, before the power went.
  - **Last colour:** on, in the colour and brightness it last had.
  - **Custom:** on, at a brightness and colour/white you choose (with *Use the light's current look*).
- From a room or zone, the setting applies to **all its lights** at once ("Different per light"
  is shown when they differ).

**Switch on to power-on settings** (Settings › Lights, on by default) makes egHue use the same
defaults whenever *it* switches lights on, from the app, the widget or a remote set up in egHue.
Lights with a custom or Hue-default power-on behaviour come on in that state. Lights on *last
colour* / *power loss recovery* come back as they were. Turned off, egHue behaves like the Hue
app (always the last state).

<br clear="right">

---

## 6. Remotes

<p>
<img src="screenshots/10-remote.png" width="300" alt="Remote button setup">
&nbsp;
<img src="screenshots/11-remote-edit.png" width="300" alt="Remote with a scene cycle">
</p>

Settings › **Remotes** lists every Hue switch on the bridge (dimmer switches, smart buttons, tap
dials, and so on) with its battery level. Opening one shows:

- **Controls:** the room or zone the buttons act on.
- For **each button**, a **Press** and a **Hold** action:

| Press | Hold |
|---|---|
| Toggle on / off · Turn on · Turn off · Scene… · Cycle through scenes… · Brighter · Dimmer · All lights off · Does nothing | Brighten while held · Dim while held · Turn off · Turn on · Scene… · All lights off · Does nothing |

How it works:
- **Runs on the bridge.** Button actions are stored as bridge rules, the same mechanism as the
  dimmer's factory setup, so remotes work with every phone off.
- **Reads what's there.** The current setup (e.g. the factory configuration) is shown in plain
  words. Anything egHue doesn't model is shown as **Keep current: …** and left exactly as it is.
- **Changes only what you change.** On *Save to bridge*, new rules are written first. The old rules
  for the changed buttons are removed only after that succeeds, so a failure leaves the remote
  working as before.
- **Scene cycling** steps through the scenes in the order you tick them, and starts again from the
  first scene 10 s after the last press (like the Hue app).
- **Fast presses:** a press with no hold action fires the moment the button goes down. When the
  button also has a hold action, the press fires on release so the two don't clash.
- *Turn on* and *Toggle* follow **Switch on to power-on settings**, using the values at the time you save.

Verified against the dimmer switch's real factory rules (read from a real bridge) and end-to-end
with simulated button presses.

---

## 7. Settings

<img src="screenshots/08-settings.png" width="300" align="right" alt="Settings">

- **Lights:** *Switch on to power-on settings* (see §5).
- **Remotes:** your Hue switches (see §6).
- **Bridge:** name, address and live connection status. **Reconnect** forces a resync and
  re-runs discovery if the IP changed. **Disconnect** forgets the bridge (or **Pair again** if the
  bridge revoked access).
- **Appearance:** Theme **System** (default; follows the device), **Light** or **Dark**.
  **Colours from wallpaper** (Material You, Android 12+); otherwise a warm amber theme.
- **Updates** (see §8): source, the *Automatic updates* switch, the one-time permission to
  install updates, and **Check for updates**.
- **About:** version.

<br clear="right">

---

## 8. Automatic updates

Two builds ("flavors") from one codebase:

| | `github` (now) | `play` (later) |
|---|---|---|
| Delivered by | GitHub Releases of `acc-studio/egHue` | Google Play |
| Checks | On launch (at most every 30 min) and every 6 h in the background | Play's own auto-update, plus an in-app *flexible update* check on launch |
| Install | Downloads in the background. **Silent install on Android 12+** once "Install unknown apps" has been allowed for egHue. On older versions, or before that permission, one tap on the system prompt (or the notification). | Downloads while you use the app |
| First update | The first self-update after installing the APK by hand asks once (*"Do you want to update this app?"*). After that, egHue is the app's installer of record and updates install silently. | n/a |
| Timing | If you're using the app, waits until you leave it, so it never closes under you | Same: completes when you leave the app |
| Safety | Only installs a newer `versionCode` of the same package. Android rejects anything not signed with the same key. | Play-managed |

Verified end to end: an installed 0.1.0 found, downloaded, verified and installed 0.1.1 from this
repo's GitHub Releases, keeping its pairing and settings.

**Shipping an update** is a single command: `git tag v0.2.0 && git push origin v0.2.0`.
GitHub Actions tests, builds and signs both flavors, then publishes the release. Installed apps pick
it up on their own.

---

## 9. How it stays fast and connected (technical)

- **API:** Hue CLIP v2 over HTTPS (`/clip/v2/resource`), plus the bridge's **event stream** (SSE,
  `/eventstream/clip/v2`). Changes from the Hue app, wall switches, automations or other phones show up
  within moments.
- **State model:** a single in-memory copy of the bridge's resources, patched by events. Each command
  adds a short-lived **optimistic overlay**, so the UI updates instantly and a dragging slider isn't
  pulled back by in-flight events. The last state is **cached on disk**, so the app and widget open
  instantly.
- **Command pipeline:** per resource, the first command goes out immediately. Rapid follow-ups (a
  drag) are merged into one request with the latest value, at most every 100 ms per light and
  300 ms per room, following Hue's rate guidance.
- **Connection:** kept-alive HTTPS connections with 3 s connect timeouts. The live connection runs only
  while the app is visible and drops 10 s after you leave (battery). It resyncs on every return.
- **Self-healing:** reconnects with backoff (0.3 s → 8 s). When the network changes it reconnects at
  once. If the bridge doesn't answer, it **rediscovers it by bridge ID** (DHCP moved it) and updates
  the stored address.
- **Network binding:** LAN traffic is pinned to the Wi-Fi network whose subnet contains the bridge.
  This works on Wi-Fi without internet and alongside VPNs.
- **Security:** the bridge certificate must name the paired bridge ID or match the key pinned at
  pairing. Discovery accepts any bridge; after pairing, only yours.
- **Reachability:** lights reported unreachable by the bridge (e.g. switched off at the wall) are
  labelled *Unreachable*.

## 10. Feature parity with the Hue app

| Hue app feature | egHue |
|---|---|
| Rooms and zones from the bridge | ✅ Same rooms, zones and room types (icons) |
| Individual lights | ✅ On the home screen |
| On/off, brightness | ✅ One tap / one drag |
| Colour and white ambience | ✅ Colour wheel, temperature bar, presets |
| Scenes (incl. Hue-app scenes) | ✅ View and activate, with the active scene shown |
| Warm presets | ✅ Evening, Unwind, Dinner, Late; designed per fixture, saved as bridge scenes on first use |
| All off | ✅ |
| Power-on behaviour per light | ✅ All four presets incl. custom; also per room |
| Remote / switch button setup | ✅ Press & hold per button: toggle, scenes, scene cycle, dimming, all off |
| Live sync with other apps and switches | ✅ Event stream |
| Widget | ✅ Configurable, one-tap |
| Light/dark/system theme | ✅ Plus Material You |
| Creating or editing your own scenes | ⏳ Planned |
| Creating or editing rooms, zones | ❌ Out of scope (do it in the Hue app; egHue picks it up live) |
| Automations, routines, Hue Sync, entertainment areas | ❌ Out of scope |
| Control away from home | ❌ Local only for now (needs Hue Remote API credentials; possible later) |
| Adding new lights to the bridge | ❌ Use the Hue app |

## 11. Platform

- Android 8.0+ (minSdk 26), targets Android 15 (API 35). Kotlin, Jetpack Compose, Material 3,
  Jetpack Glance (widget), OkHttp, WorkManager.
- Release APK size: about 2.4 MB.
- Permissions: Internet and network state. The `github` build also requests *Install packages* (self-update) and *Notifications* (update notices).
- Verified by 22 unit tests (state derivation, merging, colour maths, SSE parsing, command
  coalescing, version comparison, power-on behaviour, remote rule parsing and generation) and end-to-end on an emulator against `tools/fake_bridge.py`.
