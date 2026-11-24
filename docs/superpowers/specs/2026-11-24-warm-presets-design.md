# Warm presets: design

## Why

We like warm light, and the Hue app's stock scenes are either cold or named like perfume. egHue
gets four warm presets that feel *designed*: each fixture in a room gets its own treatment, so a
room looks lit by a designer rather than just dimmed. (Creating and editing your own scenes is a
separate, later piece of work.)

## Goals

- Four presets in every room and zone: **Evening**, **Unwind**, **Dinner**, **Late**.
- Different fixtures get different light, based on what each fixture is.
- Warm only. No bright or cool white.
- They work like real Hue scenes: on remote buttons, in the Hue app, and the active one is marked.

## Non-goals

- Creating or editing scenes (next project).
- Presets on the widget.
- Making the presets configurable.

## Approaches

1. **Save to the bridge on first use, then switch to it (chosen).** The first tap in a room creates
   the bridge scene, then recalls it. Later taps just recall it. One way to apply a preset, and the
   bridge tracks which scene is active.
2. Set the lights directly and save in the background. Marginally faster first tap, but two ways of
   applying a preset that must agree, and a failed save leaves lights changed with no scene.
3. Save all presets into every room up front. Rejected: every room in the Hue app gets four new
   scenes whether it's used or not.

## Design

**Fixture roles.** Each light's role comes from its archetype (the fixture type set in the Hue app),
then its name as a fallback:

| Role | Archetypes (examples) | Name hints |
|---|---|---|
| Ceiling | `ceiling_*`, `recessed_ceiling`, `single_spot`, `double_spot`, `hue_centris` | "ceiling", "spot" |
| Pendant | `pendant_*` | "pendant" |
| Lamp | `floor_*`, `table_*`, `wall_shade`, `wall_lantern`, `flexible_lamp`, `hue_go` | "lamp" |
| Accent | `hue_lightstrip*`, `hue_play`, `hue_iris`, `hue_bloom`, `hue_signe`, `hue_tube`, `string_light`, `wall_washer` | "strip", "led" |

Plain bulbs (`classic_bulb`, `sultan_bulb`, …) whose name gives no hint count as **Lamp**, because
most bulbs sit in lamps.

**Presets.** K = colour temperature. Accent tones are xy colours: amber (0.565, 0.405), deep amber
(0.595, 0.385), ember (0.62, 0.36).

| | Ceiling | Pendant | Lamp | Accent |
|---|---|---|---|---|
| **Evening** | 2700 K, 45% | 2700 K, 55% | 2400 K, 75% | amber, 60% |
| **Unwind** | 2200 K, 15% | 2200 K, 25% | 2200 K, 45% | deep amber, 35% |
| **Dinner** | 2700 K, 40% | 2700 K, 80% | 2200 K, 30% | ember, 25% |
| **Late** | off | off | first lamp 2000 K, 10%, others off | ember, 5% |

- **Colour fallback:** accent lights that can't show colour get their warmest white. Colour
  temperatures are clamped to each light's own range. Lights that only dim get the brightness alone.
- **Room fallbacks:** if a room has no lamps, its ceiling lights and pendants take the Lamp values,
  so a ceiling-only room still gets a lit evening. In Late, if there's no lamp, the first light by
  name takes the lamp's value. In Dinner, a room with no pendant leaves its ceiling lights at 60%.
- **Variety:** when several lights share a role, their brightness steps ×1.0, ×0.85, ×0.7 (repeating,
  ordered by name), so the room doesn't look flat.

**Saving and recalling.**
- Saved presets are tagged with the scene's `metadata.appdata`, e.g. `eghue:evening` (Hue allows
  16 characters).
- Tapping a preset: if the room has no tagged scene for it, egHue creates one (`POST
  /clip/v2/resource/scene`, named after the preset), then recalls it. If the saved scene's lights
  differ from the room's current lights, egHue rewrites its actions first. Then it recalls the
  scene with the existing optimistic display.
- If saving fails, the usual "Couldn't reach your Hue Bridge" message shows and nothing changes.

**Room sheet.**

```
Presets
[ Evening  ✓ ] [ Unwind   ]
[ Dinner     ] [ Late     ]
Scenes
[ Relax      ] [ Read     ]   ← bridge scenes, minus egHue's own presets
```

Preset tiles use the existing scene-tile style, with a gradient of the preset's colours for that
room. A preset is ticked when its saved scene is active. Saved presets are hidden from the Scenes
list so they don't show twice. On remotes, saved presets appear in the scene picker like any other
scene.

## Testing

- Unit tests (pure Kotlin): role mapping, the per-room actions each preset produces (including the
  fallbacks and colour clamping), and the hiding of tagged scenes from the Scenes list.
- `tools/fake_bridge.py` gains scene creation and update, so it can be checked end to end in the app.

## Left to the builder

The exact layout spacing, and code structure.
