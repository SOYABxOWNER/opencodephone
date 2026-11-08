# ADR-011 — Start a supported server and validate its actual protocol

## Evidence from the READY / stopped screenshots

The installation now completes, but the server remains stopped and chat is
unavailable. Searching the source showed **zero callers** of `startServer()`.
The service and UI observed state but never launched the server.

The connection error is a separate, reproduced protocol mismatch. Running
`@opencode/cli-linux-x64@2.2.6` from the same package family installed by the
app gives HTTP 200 **text/html** at `/global/health` and `/doc`: these are the
V2 web SPA fallback, not JSON API routes. V2 uses `/api/info`, a different
session/model protocol and different authentication. Simply prepending `/api`
to the client's V1 endpoints does not make it compatible.

The earlier Research 01 assertion that V1 and V2 share their API was wrong.
The runtime also treated any 2xx health response as success, allowing a web
page or an unrelated service to produce a false-positive Running state.

## Supported protocol and non-destructive CLI migration

This release explicitly supports **OpenCode V1**, pinned to **1.19.5**, from
`opencode-linux-arm64` / `opencode-linux-x64` with registry SRI verification.
The existing DTOs and endpoints implement that protocol. A V2 client migration
would require a separate, full protocol implementation, not permissive parsing.
Remote V2 servers now receive an actionable V1/API compatibility error.

The previous 2.2.6 default preference is migrated. On startup, an installed
rootfs with a different CLI stamp receives **only a CLI replacement**, staged
and atomically renamed; Debian and workspace files are not removed. The pin
from Settings is now actually passed to bootstrap, including reinstall. The
CLI version is verified before marking the installation ready.

A real V1 launch also exposed another migration hazard: opening a V2-created
OpenCode database fails with "Database is not empty and has no session table".
All guest launches therefore use isolated `opencodephone-v1` XDG data, state,
cache and config directories. Existing V2 data is preserved in its old location,
not converted or deleted. V2 server sessions are not migrated into V1. Workspace
projects and app-stored provider keys remain available; keys are synced on chat
connection.

## Lifecycle

- Application-scoped autostart observes READY and local/remote configuration,
  including an already-installed environment at cold launch.
- Foreground service protection is established **before** launching the child.
- Local port or credential changes reconcile the process; remote mode stops it.
- Process startup is serialized, equal settings reuse a healthy child,
  cancellation destroys an incomplete launch, and child exit updates state.
- Readiness uses the same parsed V1 health contract as the client: `healthy=true`
  and a 1.x version. HTTP 200 HTML, malformed JSON and false health fail visibly.
- Loopback requests bypass system HTTP proxies. Non-loopback remote servers
  retain normal proxy behavior.
- Settings has start/restart, error details and bounded server logs. Connection
  testing in local mode first ensures the managed server is started.
- Chat also connects in remote mode without waiting for local Running state;
  local lifecycle updates no longer disconnect a remote connection.

## Terminal and UI

The terminal uses pipes, not a PTY (ADR-006). `bash -i` attempts terminal process
group ioctls, producing the screenshot warnings. Use a persistent
`bash --noprofile --norc -s`, echo submitted commands in the UI and retain shell
state such as cwd. This is intentionally a line-command console, not a full TUI
terminal. No job control or curses support is claimed.

Use resize/IME insets so the command composer remains above the keyboard, and
make the keyboard Send action submit a command. Fix restart/reader races so an
old shell cannot mark a replacement dead. About now displays BuildConfig's real
APK version, **0.1.3 / code 4**, instead of the hardcoded 0.1.0.

## Validation

- JVM tests cover fresh/already-installed autostart, settings/remote transitions,
  process reuse, credential restarts, bad readiness, launch failure, post-ready
  exit and startup cancellation.
- MockWebServer tests cover HTML/malformed JSON/false health, V2 rejection and
  correct V1 npm package selection.
- `python3 tools/test_proot_seccomp.py` includes pipe-shell execution without TTY
  warnings, alongside the existing Android-like seccomp/native ELF checks.
- `tools/test_opencode_api.py --binary <real 1.19.5 binary>` launches an isolated,
  authenticated real server and verifies health, sessions/messages, agents and
  providers without model prompts/API keys. Passed on Linux x86_64.
- Real Android lifecycle/keyboard and ARM CLI execution still require device
  testing. Host smoke tests do not establish those results.
