# egHue iteration log

A rolling record of each iteration: what changed, why, and what shipped. Newest first.

The app has two faces:

- **Lite** (the default): the original fast home screen, detail sheet and widget. It only gets small
  improvements that keep it light.
- **Full**: everything else, for people who want the whole toolbox. Switch between them from the
  home screen's top bar or in Settings.

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
