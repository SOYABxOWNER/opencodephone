# ADR-002 — Native UI over the opencode HTTP API

Status: accepted (2026-11-08)
Context: docs/research/01-opencode-platform.md

## Decision

Build the entire user interface natively in Kotlin/Jetpack Compose and talk
to `opencode serve` through its HTTP API (REST + SSE). No WebView-based TUI.

## Rationale

- The user explicitly chose "native only".
- opencode's server is a first-class product surface (its own desktop/mobile
  clients are built on it), documented via OpenAPI at `/doc`.
- A native client matches the Material 3 Expressive design system; embedding
  a TUI in a WebView would fight both accessibility and theming.

## Consequences

- Message parts, sessions, permissions and diffs are rendered as Compose
  components; unknown part/event types degrade gracefully (lenient parsing).
- Completion events arrive over SSE; the chat reloads the active session
  message list on `message.*` events (simple and drift-proof; incremental
  part patching is a future optimization).
- The server lifecycle must be managed by the app (ADR-003 environment).
