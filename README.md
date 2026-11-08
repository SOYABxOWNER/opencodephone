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
- [ADRs 001–006](docs/adr/) (environment, native UI, installation,
  design system, ABI splits, terminal)

## Releases

Per-architecture APKs are built automatically:

| APK | Devices |
|---|---|
| `OpenCodePhone-vX.Y.Z-arm64-v8a.apk` | modern phones (recommended) |
| `OpenCodePhone-vX.Y.Z-armeabi-v7a.apk` | 32-bit devices (terminal + remote mode only) |
| `OpenCodePhone-vX.Y.Z-x86_64.apk` | emulators, ChromeOS |

- Every push to `main` → CI runs unit tests and uploads **debug APK artifacts**.
- Every `v*` tag → signed **release APKs** attached to a GitHub Release
  (release keystore lives in repository Secrets, never in the repository).

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
  local opencode server on connect and never leave the device except to the
  LLM provider / GitHub you configure.
- The local server is bound to `127.0.0.1`; a password can be set in Settings.
- Remote mode uses HTTP — use it only on networks you trust.
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

Сборка: `./gradlew assembleDebug` (JDK 21, Android SDK 36). Артефакты CI и
подписанные релизы — в разделах Actions и Releases. Подробности, решения и
ограничения — в англоязычной части README и в `docs/`.
