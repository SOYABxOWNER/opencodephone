# Material 3 Expressive remediation — PR #8

Historical reference: [audit](2026-11-08-m3-expressive-audit.md). That snapshot is
not rewritten to imply it described the fixed code. This document tracks delivery.

| ID | Delivered | Validation boundary |
|---|---|---|
| E-001 | Separate OcActionChip using AssistChip, 48 dp minimum, localized action label, bounded/ellipsized model text. Passive status labels stay passive. | Native Compose semantics/action/height probe at 2× font scale; real TalkBack pending. |
| E-002 | File row minimum 48 dp, Button role, real expanded/collapsed semantics, bounded indentation and weighted filename. | Device adjacent-hit-target/folder interaction pending. |
| E-003 | Compact header places actions below title; expanded header allocates title/action width. Server status is below Test rather than squeezed alongside it. | Narrow/large-font device review pending. |
| E-004 | Info rows stack at narrow widths/large font scale; expanded layouts allocate label/value width. Values remain selectable, not silently truncated. | Native large-font render probe; device path-copy review pending. |
| E-005 | Offline and connection error states have a real callback to Settings through AppNavHost. | Empty-state action invocation regression; actual navigation/device scenario pending. |
| E-006 | Apply/Cancel enabled only for dirty draft, Test is secondary, runtime-aware primary Start/Stop, Restart only when running, explanation behind disclosure. | Existing lifecycle tests retained; device action hierarchy review pending. |
| E-007 | Shared monospace CodeTypography derives from theme body roles for terminal input/output and editor; no screen-level sp overrides. | Source-policy CI; device code selection/IME review pending. |
| E-008 | Reasoning is collapsed by default in stateful accessible disclosure; final answer remains primary. | Native disclosure hide/show semantics probe; real streaming/device review pending. |
| E-009 | Tool output uses the same saved disclosure and AnimatedVisibility. No per-token animation or custom bouncy timing. | Native expand/collapse probe; rapid streaming/reduced-motion device review pending. |
| E-010 | Adaptive Dialog editor: fullscreen compact/short windows, bounded wide surface, safe drawing and IME insets, weighted field above actions. | Build/lint; actual IME/landscape checks pending. |
| E-011 | Expanded chat has a 260 dp sessions pane at 840 dp content width; compact remains a sheet. Lazy bounded sessions and content-relative bubble width up to 560 dp. | Tablet/fold hardware review pending. No hinge/posture detection claimed. |
| E-012 | Dedicated clear-output and session-conversation vector icons; localized action descriptions retained. | Build/XML; device discoverability review pending. |
| E-013 | AppMessageShapes centralizes composer/incoming/outgoing roles, preserving intentional asymmetric corners. | Build; optional decorative polygon morph/variable fonts are not required remediation. |
| E-014 | Update checking has indeterminate wavy progress; download uses real percentage and wavy determinate progress with local expressive opt-in. | Build/lint; actual update permission/installer remains device work. |
| E-015 | Offline Preview gallery, Robolectric 4.17 native graphics/semantics tests, light/dark surface pixel assertions, PNG render export, source-policy Gradle gate. | These are focused render probes, **not a full-screen golden suite**. Hardware rendering, TalkBack and IME are not verified by them. |
| E-016 | Normalize server timestamps to newest-first once; remove connection/progress pseudo-messages from reverse list; keep reading position and only follow new messages near bottom. | Two-turn/scrambled/equal/missing timestamp regressions. Streaming/reading position device tests pending. |

## Message ordering contract

Upstream `anomalyco/opencode` tag `v1.19.5`,
`packages/opencode/src/session/message-v2.ts` queries messages by descending
`time_created` / ID and reverses hydrated page items before returning them.
This confirms chronological page order; the UI now explicitly normalizes by
`info.time.created` descending for its reverse-layout contract. Equal/missing
values use reverse API order as a deterministic compatibility fallback. The
normalizer handles malformed/nonprimitive created values without parsing crashes.

## Verification and developer workflow

- Run `python3 tools/check_ui_policy.py`: no M2 imports or screen-level hex/sp
  literals. DS brand palettes/type definitions remain allowed; fixed layout dp
  and vector path colors are deliberately not banned.
- Existing `./gradlew test` also requires lintDebug, assembleRelease (R8), native
  regressions and the new UI source policy. Workflow files are unchanged.
- `:core:designsystem:testDebugUnitTest` executes native graphics and semantics
  probes without app startup/network/Keystore. Images are generated under that
  module's `build/reports/ui-renders/`; don't commit generated PNGs.
- Use the component gallery previews for light 320 dp, dark 2× font scale and
  expanded width. Golden coverage can grow after device-approved baselines; no
  unreviewed bulk image baselines are added merely to make tests green.
- Check the **final PR head's** CI, not an earlier successful commit. No local
  JDK is available; compilation/render execution depends on PR CI.

No claim of Android/ARM device execution, exact screenshot parity, TalkBack,
IME, fold posture handling or signed-release installation is made. No version
bump or release publication is included. The implementation addresses the audit
findings; optional decorative extensions remain optional, not hidden blockers.
