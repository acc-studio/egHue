# egHue iteration log

A rolling record of each iteration: what changed, why, and what shipped. Newest first.

The app has two faces:

- **Lite** (the default): the original fast home screen, detail sheet and widget. It only gets small
  improvements that keep it light.
- **Full**: everything else, for people who want the whole toolbox. Switch between them from the
  home screen's top bar or in Settings.

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
