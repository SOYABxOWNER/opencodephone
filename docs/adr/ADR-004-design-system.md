# ADR-004 — Design system: Material 3 Expressive

Status: accepted (2026-11-08)

## Decision

Implement the UI with **Material 3 Expressive** (material3 `1.5.0-rc01`,
Compose BOM `2026.09.00`) per the provided M3E skill: intensity "balanced",
one focal point per screen, tokens-first.

## Tokens

- **Color**: brand teal (`#00695E` light / `#80D5C8` dark) with amber tertiary
  accents on warm-neutral teal-tinted surfaces. Light and dark schemes are
  designed independently (not inversions). Dynamic color available on
  Android 12+ but off by default to keep brand identity.
- **Type**: system font stack, editorial contrast — semibold display/headline,
  regular body, semibold labels.
- **Shape**: radius family 12 / 20 / 32 / 48 dp.
- **Motion**: `MotionScheme.expressive()` (spring-based, interruptible).

## App icon

Adaptive icon (background color + foreground glyph): a terminal prompt
(chevron + underscore) in brand teal. `values-night` variants flip the
palette so launchers that support themed icons render light/dark correctly;
a monochrome layer is included for Android 13+.

## Consequences

- Expressive components used where they fit: wavy progress (bootstrap,
  busy states), pill-shaped input actions, large editorial headers.
- Restraint rules from the skill apply: no decoration without meaning,
  utility regions stay quiet.
