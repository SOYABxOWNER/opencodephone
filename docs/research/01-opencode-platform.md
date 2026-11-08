# Research 01 — OpenCode platform (V2)

Date: 2026-11-08. Purpose: establish verified facts about OpenCode before
designing the client integration.

## What OpenCode is

- Open source AI coding agent, repository `anomalyco/opencode`, MIT license,
  ~225k stars at research time.
- Current major version: **OpenCode 2** (latest release **v2.2.6**, 2026-11-06).
  OpenCode 1 and 2 share the `opencode` command but cannot be installed side
  by side; the generic `https://opencode.ai/install` script installs **V1
  only**. The V2 installer is `https://opencode.ai/v2/install`.
- Product surfaces: terminal TUI, desktop app, mobile client (remote control),
  SDK. The mobile client connects *to a machine running OpenCode* — it does
  not run the agent on the phone. **OpenCodePhone's differentiator is running
  the whole stack locally on the device.**

## Headless server mode (the integration point)

`opencode serve` runs a headless HTTP server exposing an OpenAPI 3.1 spec.

- Default listen address: `127.0.0.1:4096`; flags `--port`, `--hostname`,
  `--cors`, `--mdns`.
- Authentication: HTTP basic auth via `OPENCODE_SERVER_PASSWORD`
  (`OPENCODE_SERVER_USERNAME` overrides the default `opencode` user).
- Spec endpoint: `GET /doc`.

### Key REST endpoints (verified against V2 docs, identical to V1 surface)

| Method | Path | Purpose |
|---|---|---|
| GET | `/global/health` | `{healthy, version}` |
| GET | `/global/event` | global SSE stream |
| GET/POST | `/session` | list / create sessions |
| GET | `/session/:id/message` | list messages (`{info, parts}`) |
| POST | `/session/:id/prompt_async` | send prompt (204, async completion) |
| POST | `/session/:id/message` | send prompt synchronously |
| POST | `/session/:id/abort` | abort a running session |
| POST | `/session/:id/permissions/:permissionID` | respond to permission request (`{response}`) |
| GET | `/provider` | `{all, default, connected}` |
| GET | `/config/providers` | providers + default models |
| GET | `/agent` | agents list |
| PUT | `/auth/:id` | store provider credentials (e.g. `{"type":"api","key":…}`) |
| GET | `/file?path=` | list files |
| GET | `/file/content?path=` | read file |
| GET | `/session/:id/diff` | session file diffs |
| GET | `/event` | SSE bus stream (`server.connected` first) |

Event bus messages arrive as `{"type": "...", "properties": {...}}`. Relevant
types: `message.*`, `session.*` (incl. `session.status`), `permission.updated`,
`permission.replied`, `file.*`.

## Distribution / installation (matters for bootstrap)

- The V2 install script downloads platform packages from the npm registry and
  needs only `curl`/`tar` — **Node.js is not required**.
- Package naming: `@opencode/cli-<target>`, targets `linux-arm64`,
  `linux-x64` (plus `-baseline` / `-musl` variants). Legacy fallback scope:
  `@opencode-ai`.
- Verified package for arm64: `@opencode/cli-linux-arm64@2.2.6`
  (tarball URL and sha512 integrity served by
  `https://registry.npmjs.org/@opencode/cli-linux-arm64/2.2.6`).
- Latest version discovery endpoint:
  `https://opencode.ai/update/api/latest/cli/npm` →
  `{"version":"2.2.6", …}`.
- **There is no armv7 (32-bit ARM) build** — 32-bit devices cannot run the
  local agent. They can still use the Linux environment, terminal, and the
  app as a client for a remote server.

## Models / providers

- 200+ providers via Models.dev, including local OpenAI-compatible endpoints.
- Credential storage on the server side: `PUT /auth/:providerID` with
  provider-schema JSON (`{"type":"api","key":…}` for API-key providers).
- OpenCode Zen (`opencode.ai/auth`) is an alternative single-key option.

## Consequences for OpenCodePhone

1. Talk to `opencode serve` over HTTP from a native Kotlin client (ADR-002).
2. Install the CLI from npm inside Debian without Node (ADR-003).
3. Pin version 2.2.6 at bootstrap; expose the pin in Settings (ADR-003).
4. armv7 APK ships without the local agent; remote-server mode remains
   available (ADR-005).

Sources (accessed 2026-11-08): opencode.ai/docs, opencode.ai/v2/docs/server,
opencode.ai/v2/install (script inspected), registry.npmjs.org, GitHub
releases API of `anomalyco/opencode`.
