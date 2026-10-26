# Nothing OS 5.0 — design research notes

How Helm's look and behaviour were worked out. No Nothing code was decompiled or copied: Nothing's
launcher APK isn't ours to take apart. Everything here comes from public material (Nothing's own
community posts and design boards, the official changelog, press coverage), worked through as a
checklist of questions. Each finding is marked OBSERVED (seen in primary material), INFERRED
(derived from it), or UNKNOWN. Where the facts ran out, Helm made a design choice and says so.

## Evidence
- E1 Nothing community post "Nothing OS Evolution" (nothing.community/d/61774) — primary, first-party text.
- E2 Design boards attached to E1 (cdn.nothing.community): material board (deck-12), colour engine
  board with NOS5 home mock (new-colour-engine-1), type board + Settings (deck-13), OS 2→5 evolution (frame-9),
  hardware/material vocabulary (deck-2), wallpaper studies (workhub-08-25).
- E3 Official changelog as reproduced by AndroidSage 2026-10-13 (build C5.0-260924-2115). Its narrative
  section ("Smart Drawer", "Shared Widgets") is NOT corroborated anywhere → treated as unreliable.
- E4 Android Authority 2026-08-25, Beebom 2026-08-25, GadgetPilipinas, Droid-Life, Android Central.

## Findings
| # | Question | Finding | Status | Evidence |
|---|----------|---------|--------|----------|
| F1 | System typeface | Geist replaces Ndot/NType for everyday UI; Geist Mono already in Playground | OBSERVED | E1, E2 deck-13 |
| F2 | Dot-matrix role | Kept "more intentionally" for character: widget data ("8H19M", "27°"), lock clock | OBSERVED | E1, E2 colour-engine |
| F3 | Headline face | Light display serif for big titles/numbers ("Settings", "120 BPM", "35mm") | OBSERVED (face proprietary → Instrument Serif, OFL) | E2 deck-13, frame-9 |
| F4 | Materials | Three: Transparent α0.25, Translucent α0.6 + frost (the new category), Opaque α1 | OBSERVED | E2 deck-12 |
| F5 | Colour engine | Wallpaper's primary colour tints icons, widgets, controls; monochrome stays underneath; alongside Classic theme and Dynamic Colours | OBSERVED | E1, E4 |
| F6 | Content tokens | onSolid 100/60/30 %, onTranslucent 96/60/30 %, Accent ← {primary} | OBSERVED | E2 colour-engine table |
| F7 | New home theme | Additional theme (not replacement): transparent widgets + translucent search bar | OBSERVED | E1, E4 |
| F8 | Widget shapes | 1×1 toggles = circles; 2×1 = full pill; 2×2/1×1 squares with deep radius (~14–18% of side); analog clock = round face | OBSERVED | E2 colour-engine crop |
| F9 | Analog clock | Line ticks (thick at 5-min), dark hands with tails, accent second hand with tail dot, accent centre | OBSERVED | E2 crop |
| F10 | Dock & icons | 4 large discs (~18% screen width), monochrome glyph, no labels; page dots above dock; search pill below, text centred | OBSERVED | E2 crop |
| F11 | Accent use | Active toggle fill, second hand, small glyphs (✳) — sparing | OBSERVED | E2 crop |
| F12 | Classic language | Light grey/white or black, opaque tiles, red accents, round icons, dot world clock "LONDON 22:10" | OBSERVED | E2 frame-9 |
| F13 | Batch editing | Long-press home → Arrange; move multiple apps/widgets at once | OBSERVED | E3, E4 |
| F14 | New widgets | Away Time (screen-free time, longest break, weekly), Collector (photo cut-outs as stickers), Glyph Timer | OBSERVED | E1, E3, E4 |
| F15 | Wallpapers | Grainy gradient blobs, thin circle lines, dashed lines, dot grids | OBSERVED | E2 workhub, colour-engine |
| F16 | NOS 4 carry-over | Hide apps from home/drawer ("Hidden icons" in drawer), search scope control, 1×1/2×1 widget sizes, widget drawer | OBSERVED (secondary) | E4 NOS4 coverage |
| F17 | Lock clocks | Gooey, Micrographics (date/time/weather/meetings) | OBSERVED (text) | E3, E4 |
| F18 | Smart Drawer AI categories | Only in uncorroborated narrative | UNKNOWN | E3 narrative |
| F19 | Exact radii, blur radius, motion curves | Not published | UNKNOWN → measured from boards / design choice |

## How Helm applies it (design choices, not claims about Nothing's code)
- Two themes like NOS 5: **Classic** (opaque, mono + red) and **Translucent** (frosted, wallpaper accent).
- Real frost needs the pixels behind a surface: Helm draws its own wallpaper (generated Atmosphere,
  your photo, or solid) and samples a pre-blurred copy behind every translucent surface. With the system
  wallpaper Android does not let apps read it, so surfaces fall back to tint-only.
- Geist / Geist Mono / Instrument Serif bundled (SIL OFL). Dot-matrix only for data and accents.
- Away Time uses Android usage events (needs the user to grant Usage access). Collector → "Photo"
  widget with a sticker style. Glyph Timer has no Glyph on a Galaxy → omitted.
- Smart Drawer: Android's own app categories (offline, no AI claim).

## v3 additions (2026-10-26) — design choices, not claims about Nothing's code
- Quick panel: NOS's header time in dots, two wide Internet/Bluetooth pills, round toggles, wide brightness bar.
  Helm's own overlay (Android reserves the real shade for SystemUI). Opens from home swipe-down.
- Recents: Nothing-style tall cards from UsageStats (no task snapshots: third-party launchers can't get them).
  ACTION_ASSIST filter so "digital assistant app" = Helm maps long-press Home to it.
- Lock screen: live wallpaper with a live clock (Dots / Stacked / Serif / Analog). One UI's own clock still draws on top.
- Drawer groups: user folders + optional grouping by Android app category (offline, no AI claim).
- PiP: no Helm-side cause found; likely One UI gesture-nav handoff with third-party home (INFERRED, untested).
- v3.1 sources: Beebom NOS 5.0 (Gooey clockface, hardware-inspired clock face, wallpaper-matched clock colours,
  Lucent theme, 1x1 QS widgets tinted by wallpaper, redesigned Recents); TechRepublic/Digital Trends (two new lock faces).
  Helm: Gooey (fused dot-matrix), Terminal (mono + readouts + day dot bar), Clock colour Mono/Wallpaper.
