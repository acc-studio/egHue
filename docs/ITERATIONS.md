# egHue iteration log

A rolling record of each iteration: what changed, why, and what shipped. Newest first.

The app has two faces:

- **Lite** (the default): the original fast home screen, detail sheet and widget. It only gets small
  improvements that keep it light.
- **Full**: everything else, for people who want the whole toolbox. Switch between them from the
  home screen's top bar or in Settings.

---

## Iteration 10 · v0.13.0 · Polish and hardening

**Why:** after a run of features, a pass on rough edges and things a real bridge might do
differently from the simulator.

**What changed**
- **All lights bar** (Full home): one brightness bar for every light that's on ("All lights · 4 on").
  It dims only; lights that are off stay off.
- **Lite option:** Settings › Appearance › *Show individual lights on Home*. Turn it off for just
  rooms and zones (each light is still inside its room).
- **Safer renames:** names and fixture types are written to the device (what the Hue app shows)
  first, then to the light where the bridge allows it. Firmware that makes the light's copy read-only
  no longer shows a false error.

**Verified** on the emulator: Full home with the All lights bar (61%, 4 on). 63 unit tests pass.

---

## Iteration 9 · v0.12.0 · Motion sensors that switch lights

**Why:** a motion sensor was only a reading in the Devices tab. Its real job is switching the
hallway light on when you walk in.

**What changed**
- Tap a motion sensor in **Devices** to set what motion does:
  - which room or zone, at what brightness and white;
  - **at night** (from 22:00/23:00/00:00 to 06:00/07:00/08:00): same as day, a 5% night light,
    dim 20%, or stay off (night light is always the warmest white);
  - **turn off** after 1 to 30 minutes without motion (fading over 2 s);
  - **only when it's dark**, using the sensor's own light reading.
- The room is guessed from the sensor's name ("Hallway sensor" → Hallway).
- Stored as bridge rules named `egHue motion <n> on/night/off`, so it runs with every phone off.
  Saving writes the new rules before removing the old; *Stop using this sensor for lights* removes
  them. A note says to set the sensor up in one place if the Hue app also controls it.

**Verified** on the emulator: the editor opened for "Hallway sensor" with Hallway preselected; Save
posted three rules. Unit tests: day/night/off rules, condition limits, round trip from bridge
rules, "stay off" and "no night mode" variants, other sensors' rules ignored. 63 tests.

---

## Iteration 8 · v0.11.0 · Natural light

**Why:** you like warm light, but the right warmth changes through the day. A rhythm that runs
by itself beats remembering to change it.

**What changed**
- **Natural light** (Automations ☀, or the button on the empty screen): pick a room, zone or the
  whole home and egHue adds five daily steps on the bridge:

  | Time | White | Brightness |
  |---|---|---|
  | 07:00 | 3300 K | 100% |
  | 12:00 | 3500 K | 100% |
  | 17:00 | 2900 K | 85% |
  | 20:00 | 2400 K | 65% |
  | 22:30 | 2000 K | 35% |

  Each step glides over 10 minutes and only changes lights that are **on**: nothing switches on
  by itself. Each step can be edited, paused or deleted like any automation.
- **Adjust if on** is now an action for any timed automation (brightness and white, never
  switching lights on).
- Kelvin values are shown rounded to 100 K.

**Verified** on the emulator: natural light for the Living room created five schedules, listed
as "07:00 · Every day · 3300 K at 100% if on" and so on. Unit tests: Adjust never sends `on`,
round-trips; natural-light steps get warmer through the day. 58 tests.

---

## Iteration 7 · v0.10.0 · A dot per light, and dynamic scenes

**Why:** colouring a room light by light is the Hue app's signature control; one colour for the
whole room is limiting once you have several colour bulbs.

**What changed**
- **Multi-dot colour wheel** (Full view, rooms and zones with two or more colour lights): each
  light is a dot in its own colour. Drag a dot to recolour just that light (the dot grows while
  held); tap or drag anywhere else to set them all. Off lights show as faded dots.
- **Play dynamically:** scenes with a colour palette (most Hue-gallery scenes) get *Play
  dynamically* in their long-press menu, so the colours drift slowly within the palette.

**Verified** on the emulator: Living room's two colour lamps shared one position; dragging the
dot to blue moved one lamp (light PUTs) and left the other orange. 56 unit tests pass.

---

## Iteration 6 · v0.9.0 · Zones

**Why:** zones ("Downstairs", "Reading corner", "TV area") are how people group lights across
rooms, and before this they had to go back to the Hue app to make one.

**What changed**
- **Devices tab › Rooms & zones** (Full): every room (tap for its controls) and zone, plus
  *New zone*.
- **Zone editor:** name, an icon (Zone, Living, Dining, Kitchen, Bedroom, Work, TV, Reading,
  Hallway, Outside, Floor), and a light checklist grouped by room. Saving a new zone reloads the
  bridge state so it shows up straight away with its own on/off, dimming, presets and scenes.
- Edit any zone (including Hue-app zones): rename, change icon, add or remove lights. Delete
  removes the zone (and its scenes) but leaves the lights alone.

**Verified** on the emulator against the fake bridge (now with zone POST, PUT, DELETE): created
"Downstairs" (appeared in the list) and deleted it. 56 unit tests pass.

---

## Iteration 5 · v0.8.0 · Quick Settings tiles and launcher shortcuts

**Why:** the fastest light switch is the one you don't have to open an app for. These help both
views and add nothing to the Lite screen.

**What changed**
- **Quick Settings tiles** (add them from the Quick Settings edit screen):
  - *Lights off*: every light off.
  - *Room*: toggles one room or zone, showing its name, on/off state and brightness (Android 10+).
    Pick the room in Settings › Lights › *Quick Settings room tile* (the first room by default).
- **Launcher shortcuts:** long-press the egHue icon for *All off* and your first three rooms.
  Each toggles straight away without opening the app (an invisible activity), and shows a toast
  if the bridge can't be reached. The list follows your rooms as they change.
- Both use the widget's start-from-cold path: resync if state is older than 15 s, then switch.

**Verified** on the emulator: shortcuts published (All off, Kitchen, Hallway, Bedroom); room tile
added and clicked through `cmd statusbar` (bridge received the light commands); All-off shortcut
(home grouped_light PUT). 56 unit tests pass.

---

## Iteration 4 · v0.7.0 · Devices, effects and housekeeping

**Why:** a complete app covers more than lights and scenes: sensors, finding a bulb, naming
things, and the effects newer bulbs can play.

**What changed**
- **Devices tab** (Full): every light (status, effect, colour dot), every sensor, every remote, and the
  bridge (model, address, software version).
- **Sensors:** motion (with "last 12 min ago"), temperature, light level in lux, door/window
  contact, battery. Motion detection can be switched off and on.
- **Light extras** in the light sheet (Full view): **effects** the bulb supports (candle, fire,
  prism, sparkle, opal, glisten and others), **Blink** to find a bulb, **Rename**, and
  **Fixture type** (which also decides the light's role in presets; the sheet says which).
- **Room extras** (Full): rename a room or zone.
- Remotes open from the Devices tab too, and Back returns there.

**Verified** on the emulator against the fake bridge (now with effects, a motion sensor and a
bridge device): Devices tab, Candle effect (light PUT), Blink (device identify). Unit tests: effects,
device and product name on lights, sensor readings (motion, temperature, lux maths, battery).
56 tests.

---

## Iteration 3 · v0.6.0 · Automations

**Why:** a Hue app isn't complete without waking up to light and lights that go off by themselves.

**What changed**
- **Automations tab** (Full): lists every schedule on the bridge with an on/off switch, live
  countdowns for timers, and pull to refresh. Schedules made by other apps are shown read-only.
- **Editor** with four kinds:
  - *Wake up:* lights fade up over 5 to 60 min ("sunrise") and are fully up at the chosen time,
    at a brightness and white (Candle, Warm, Neutral, Daylight) you pick. Fades that start before
    midnight shift to the previous day correctly.
  - *Bedtime:* lights fade out over 5 to 60 min from the chosen time.
  - *Timer:* off after 5 min to 2 h, fading over the last minute.
  - *At a time:* turn on (brightness and white), turn off, or play one of the room's scenes.
  - Any room, zone or the whole home; any days (none means once).
- **Lite addition:** a sleep-timer row in every room's sheet: *Off in 15 / 30 / 60 / 90 min*, then a
  live *Off in 23 min · Cancel* chip.
- Everything is stored as bridge schedules (tagged `eghue:<kind>`), so it runs with phones off.

**Verified** on the emulator against the fake bridge (which now speaks v1 schedules): created a
wake-up (POST, listed as "07:00 · Weekdays · 30 min sunrise to 100%"), started a sleep timer from
the room sheet (countdown chip shown). Unit tests: wake-up lead time, midnight wrap, bedtime, one-off
date, timer end time, foreign schedules, name length, day descriptions (54 tests in all).

---

## Iteration 2 · v0.5.0 · Full view and your own scenes

**Why:** the app could only play scenes. Making and changing scenes is the most-wanted missing
feature, and it needs a home that doesn't clutter the Lite screen.

**What changed**
- **Lite / Full view.** A top-bar button (⚡ / ▦) and Settings › Appearance › View switch between
  them. Lite is unchanged apart from small additions. Full adds a bottom bar with tabs, starting
  with *Home* (the Lite screen) and *Scenes*.
- **Scenes tab** (Full): every room and zone with its presets, its own scenes and a *New scene* tile.
- **Scene editor** (Full): name it, pick a starting point (any preset, previewed without saving
  anything, or an existing scene), then shape the real lights with the usual tiles and colour
  controls. *Save* stores exactly what the lights show. Editing an existing scene plays it first.
- **Lite additions:** a *Save current look* tile under each room's scenes, and long-press on any
  scene for *Update to current look*, *Rename* and *Delete* (plus *Edit lights* in Full).
- egHue-made scenes are tagged (`eghue:my:1`) so later iterations can tell them apart.

**Verified** on the emulator against the fake bridge: Full view, Scenes tab, Neon preview on the
lights, Save (scene POSTed), long-press menu, Delete (scene DELETEd). Fixed a status-bar overlap
in Full view found that way. Unit tests: scene capture (white, colour, off, minimum brightness),
unique names.

---

## Iteration 1 · v0.4.0 · Presets, redesigned

**Why:** the first presets (Evening, Unwind, Dinner, Late) were one warm look at four brightness
levels. Presets should be four different moods, each one designed.

**What changed**
- New presets, designed per fixture role (ceiling, pendant, lamp, accent):
  - **Candlelit** (romantic): overhead off, a faint 2000 K pendant, flame-amber lamps at 30%, rose
    accents. Several lamps step down (100/85/70%) so the light pools rather than floods.
  - **Reading**: lamps at 100% in 2900 K (warm but crisp, never blue), ceiling fill at 65%,
    pendants 80%, accents a quiet 35% white so they don't distract.
  - **Neon** (fun): every colour-capable light gets a different saturated colour, in an order
    where neighbours clash on purpose (magenta, teal, tangerine, violet, hot pink, chartreuse).
    White-only lights drop to a low warm white so they don't wash the colours out.
  - **Lounge** (chill): honey-amber lamps at 45%, a dusky violet accent glow (warm against cool),
    overhead lights off, pendants low.
- A room is never left dark: with no lamps, overhead lights take the lamp look; if a look would
  switch everything off, the first light stays on.
- The old presets' scenes are hidden at once and deleted from the bridge the next time a preset is
  used in that room.
- Preset tiles preview the colours the room will actually get.

**Tests:** preset designs per role, fallbacks, Neon palette spread, retired-preset detection.
