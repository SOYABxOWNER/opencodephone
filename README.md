# OpenCodePhone

[![CI](https://github.com/oustery/opencodephone/actions/workflows/ci.yml/badge.svg)](https://github.com/oustery/opencodephone/actions/workflows/ci.yml)
[![Release](https://github.com/oustery/opencodephone/actions/workflows/release.yml/badge.svg)](https://github.com/oustery/opencodephone/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-teal.svg)](LICENSE)

**Run the OpenCode AI coding agent entirely on your Android phone** — inside a
rootless Debian environment, with a fully native Kotlin / Jetpack Compose
(Material 3 Expressive) client.

> [Русская версия ниже](#opencodephone-на-русском)

## What it does

OpenCodePhone runs [OpenCode](https://opencode.ai) — the open source AI
coding agent — **locally on your device**:

- a full **Debian (trixie) environment** in app-private storage via
  [proot](https://proot-me.github.io/) — no root, no Termux;
- the **OpenCode CLI** (`opencode serve`) installed inside that environment
  and exposed to the app over its HTTP API on localhost;
- a native UI: **Chat** (sessions, streaming replies, tool activity, permission
  approvals), **Files** (workspace browser, editor, git clone/commit/push),
  **Terminal** (persistent shell in Debian), **Settings** (providers, models,
  server, git identity);
- works with **200+ LLM providers** (Anthropic, OpenAI, Google, OpenRouter,
  Ollama/anything OpenAI-compatible — bring your own API keys, stored
  encrypted on-device);
- a **remote mode** that points the same UI at any machine running
  `opencode serve` (handy for 32-bit devices — see limitations).

## Architecture

```
┌──────────────────────────────────────────────────┐
│  OpenCodePhone — Kotlin + Jetpack Compose        │
│  Material 3 Expressive (light/dark themes)       │
│  ┌──────────┐ ┌───────────┐ ┌──────────────┐     │
│  │   Chat   │ │ Files+Git │ │   Terminal   │     │
│  └────┬─────┘ └─────┬─────┘ └──────┬───────┘     │
│       └──────── service layer ────────┘          │
└────────────────────┬─────────────────────────────┘
                     │ HTTP localhost:4096 (REST + SSE)
┌────────────────────▼─────────────────────────────┐
│  Debian trixie (proot, rootless)                 │
│   ├── opencode serve (headless HTTP API)         │
│   ├── git, curl, ripgrep, …                      │
│   └── /root/projects  ◄── app-shared workspace   │
└────────────────────┬─────────────────────────────┘
                     │
              LLM API (your providers)
```

First launch performs a guided bootstrap: downloads Debian (digest-pinned,
SHA-256 verified while streaming), installs tools, installs the OpenCode CLI
(SRI-verified), configures the environment. Every design decision is recorded:

- [Research: OpenCode platform](docs/research/01-opencode-platform.md)
- [Research: Debian on Android](docs/research/02-android-linux-env.md)
- [ADRs 001–008](docs/adr/) (environment, native UI, installation,
  design system, ABI splits, terminal, targetSdk, toolset bootstrap)

## Releases

Per-architecture APKs are built automatically:

| APK | Devices |
|---|---|
| `OpenCodePhone-vX.Y.Z-arm64-v8a.apk` | modern phones (recommended) |
| `OpenCodePhone-vX.Y.Z-armeabi-v7a.apk` | 32-bit devices (terminal + remote mode only) |
| `OpenCodePhone-vX.Y.Z-x86_64.apk` | emulators, ChromeOS |

- Every push to `main` → the **Release** workflow itself tags the commit and
  publishes two signed builds:
  - `vX.Y.Z` — stable **release** (marked *Latest*; this is exactly the
    channel the in-app updater installs from);
  - `vX.Y.Z-dev.N` — signed **dev pre-release** built from the same commit
    (`OpenCodePhone Dev`, separate application id, installs side by side with
    the stable app; never selected by the in-app updater).
- Tags and Android version codes are derived automatically (latest
  `vX.Y.Z` tag / `versionName` + patch bump; versionCode = run number × 10),
  so each release updates cleanly over the previous one. Manual runs:
  Actions → Release → Run workflow (optionally pin a version); pushing any
  `vX.Y.Z` tag builds exactly that version.
- The digest-pinned `toolset-<arch>.tar.gz` bundles live on the `v0.1.1`
  release (`Pins.TOOLSET_URL_PREFIX`); they are not rebuilt per release.
  Rebuild them with `tools/build_toolset.py <arch>…` and update the digests
  in `Pins` when the toolset itself changes.

## Server compatibility

The native client supports **OpenCode V1**, pinned to **1.19.5**. OpenCode V2
has a different API; its HTML web fallback is not a valid V1 health response.
Since v0.1.3, a local server starts automatically after setup and on subsequent
launches. Settings provides restart controls, errors and server logs.

Existing installations replace only the incompatible CLI, without re-extracting
Debian or deleting projects. V1 uses isolated XDG directories so it does not open
or overwrite a V2 database; V2 sessions/config are preserved but not converted.
See `docs/adr/ADR-011-server-autostart-and-api-compatibility.md`.

The terminal is a persistent pipe-based command console, not a PTY: no full-screen
TUI programs or job control. Normal shell commands and persistent `cd` work.

## Troubleshooting

- **"Environment self-check failed (exit 255)".** This code alone does not
  identify the cause. The preflight probe now logs proot's terminating signal.
  **SIGSYS** (31 on arm64/x86_64) indicates a forbidden syscall, not battery
  optimization. Debian glibc uses syscalls that Android app policies may reject,
  including `set_robust_list` and `rseq`. Since v0.1.2 the app bundles a static
  Android-compatible Termux PRoot (Termux itself is not required), which handles
  outer-filter SIGSYS and logs the trapped syscall number. Disabling rseq alone
  was insufficient. See ADR-010 and `python3 tools/test_proot_seccomp.py`.
  Rebuild all bundled native binaries with `python3 tools/build_proot.py`.
  For **SIGKILL** (9), Android's child-process restrictions, memory pressure or
  aggressive battery management may be responsible. Keep the app on screen
  and disable battery optimization for OpenCodePhone. On supported devices,
  *Developer options → Disable child process restrictions* may also help.
- **Where are the setup logs?** The failure dialog shows the last log lines;
  the full log is stored at `Android/data/io.github.oustery.opencodephone/
  files/linuxenv/bootstrap.log`.


## Building locally

Requirements: JDK 21, Android SDK (platform 36).

```bash
git clone https://github.com/oustery/opencodephone.git
cd opencodephone
./gradlew assembleDebug          # per-ABI debug APKs
./gradlew test                   # unit tests
```

Output: `app/build/outputs/apk/debug/app-<abi>-debug.apk`.

Release signing (optional): provide `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
as environment variables.

## Project structure

```
app/                  UI: screens, navigation, foreground service, DI wiring
core/model/           opencode DTOs + bootstrap model (pure Kotlin, lenient parsing)
core/opencode/        HTTP client for `opencode serve` (REST + SSE)
core/linuxenv/        proot bootstrap, rootfs download/verify/extract,
                      opencode installer, process/server runtime
core/settings/        encrypted settings (Android Keystore)
core/designsystem/    M3 Expressive theme, tokens, shared components
```

Version pins live in `gradle/libs.versions.toml` and
`core/model/.../Pins.kt` (Debian digests, OpenCode version).

## Security notes

- Provider API keys and the GitHub token are stored in
  `EncryptedSharedPreferences` (Android Keystore); keys are injected into the
  selected opencode server on connect. A remote server receives those provider
  keys only with explicit endpoint/account-specific consent, so it must be trusted.
  Git uses a child-environment credential helper restricted to HTTPS github.com;
  stored remote/push URLs are sanitized when a project is accessed. Rotate old
  exposed tokens; removing a URL does not remove copies from backups/history.
- The local server is bound to `127.0.0.1` and requires an automatically generated
  256-bit secret stored in encrypted settings, separate from remote credentials.
  The app and its health probe use the same stable local secret.
- If secure settings cannot be opened, startup shows a retry screen without
  deleting data or falling back to plaintext. Old plaintext settings are not
  automatically read/imported/deleted; Settings warns if the legacy file exists.
  Retry may resolve a temporarily unavailable Keystore. Explicit confirmed reset
  deletes encrypted settings and their key, preserving projects/rootfs; lost keys
  cannot be recovered. Settings offers an explicit legacy import/cleanup action.
- Non-loopback remote hosts use **HTTPS with certificate validation**. Enter a
  bare hostname/IP in Settings and set the TLS listener's port (usually 443).
  Put a plain OpenCode V1 server behind a trusted HTTPS reverse proxy that also
  forwards SSE, or use an authenticated encrypted tunnel terminating on the
  phone's localhost. HTTP is allowed only for explicit loopback hosts, never as
  a fallback after a failed TLS connection. Redirects are not followed.
- Provider synchronization is endpoint/account-scoped. Only provider credentials
  previously managed by this app are deleted after local removal; independently
  configured server credentials are not removed. See the [remediation tracker](docs/reviews/remediation-progress.md)
  for regression coverage and remaining device-validation limits.
- No secrets are ever committed; CI release signing uses repository Secrets.

## Limitations (v0.1, honest)

- **armv7 devices**: OpenCode has no 32-bit ARM build, so the local agent is
  unavailable there — terminal + remote-server mode still work
  ([ADR-005](docs/adr/ADR-005-abi-splits.md)).
- **Terminal** is pipe-based in v0.1: commands and output work, full-screen
  TUI programs (vim/htop) do not ([ADR-006](docs/adr/ADR-006-terminal.md)).
- Chat rendering is plain text + structured tool cards; rich markdown is
  planned.
- Bootstrap requires internet; offline install is future work.

## Roadmap

1. Markdown rendering & diff viewer in chat.
2. JNI pty terminal ([ADR-006](docs/adr/ADR-006-terminal.md) supersession).
3. Session search, project-level `AGENTS.md` management.
4. Per-APK Play publishing tracks.

## Contributing

Issues and PRs are welcome. Please keep changes aligned with the ADRs —
before a major change, add a research note and a new ADR (the project's own
development ritual).

## License

[MIT](LICENSE). OpenCode is developed by [Anomaly](https://opencode.ai) —
OpenCodePhone is an independent client and is not affiliated with Anomaly.

---

# OpenCodePhone (на русском)

**Запускает ИИ-агент OpenCode целиком на вашем Android-телефоне** — в
rootless-окружении Debian, с полностью нативным клиентом на Kotlin /
Jetpack Compose (Material 3 Expressive).

- Полноценное **окружение Debian (trixie)** в приватном хранилище приложения
  через proot — без root и без Termux.
- **OpenCode CLI** (`opencode serve`) устанавливается внутрь окружения при
  первом запуске; приложение общается с ним по HTTP API на localhost.
- Нативный интерфейс: **Чат** (сессии, потоковые ответы, активность
  инструментов, запросы разрешений), **Файлы** (браузер рабочей папки,
  редактор, git clone/commit/push), **Терминал** (постоянный шелл в Debian),
  **Настройки** (провайдеры, модели, сервер, git).
- **200+ провайдеров моделей** — принесите свои API-ключи (хранятся
  шифрованно); есть **режим удалённого сервера**.
- Первый запуск скачивает Debian с проверкой SHA-256 и OpenCode CLI с
  проверкой SRI — все версии зафиксированы (см. `Pins.kt` и
  [ADR-003](docs/adr/ADR-003-opencode-install.md)).

Сборка: `./gradlew assembleDebug` (JDK 21, Android SDK 36). Релизы создаются
автоматически: каждый push в `main` — workflow сам ставит теги, публикует
подписанные стабильный `vX.Y.Z` и dev-пререлиз `vX.Y.Z-dev.N` с APK (см. раздел
Releases). CI-артефакты (debug APK) — в разделе Actions. Подробности, решения и
ограничения — в англоязычной части README и в `docs/`.

## In-app updates and server controls

Settings → App updates checks the latest stable GitHub Release **on request**.
It selects an exact per-ABI release APK, downloads to private cache (150 MiB
limit, cancellable), checks the published SHA-256 when available, and always
requires the same package/signing certificate and a higher Android version code.
Android's installer still asks for confirmation. If unknown-source permission is
needed, allow this app, return to Settings and press Install again. A cancelled
installer can be opened again. Debug APKs cannot update to differently signed
release APKs; signing-key rotation is intentionally not supported by this initial
updater. No background checks, silent installation or GitHub credentials are used.
Releases must keep the workflow's `OpenCodePhone-vVERSION-ABI.apk` names and bump
both the stable version and Android version code. A failed check/download can be
retried; no update replaces the currently installed APK without user consent.

Settings → Local server has Stop/Start as well as Restart. Stop terminates the
local server and disables automatic restart for the current app process, including
configuration changes, chat reconnects and environment reinstall. Start explicitly
re-enables it. A new app process restores the existing autostart default. This does
not delete the environment/projects or terminate terminal sessions. Remote servers
are never stopped by these controls.
