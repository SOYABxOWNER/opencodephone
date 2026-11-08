# ADR-005 — Per-ABI APK splits and rootfs delivery

Status: accepted (2026-11-08)
Context: docs/research/02-android-linux-env.md

## Decision

Ship **one APK per architecture** (`arm64-v8a`, `armeabi-v7a`, `x86_64`) via
Gradle ABI splits. Each APK bundles only its matching static proot binary
(`jniLibs/<abi>/libproot.so`, extracted executable). The Debian rootfs and
the OpenCode CLI are downloaded at first launch (digest- and SRI-verified).

## Rationale

- The user explicitly requested per-architecture APKs.
- proot is the only arch-dependent native artifact, so splits make APKs
  minimal while still meaningful.
- Downloading the ~50 MB rootfs keeps the APK small (~10 MB) and lets us pin
  registry digests independent of app releases.

## Known limitation

OpenCode has **no armv7 build** (npm targets are arm64/x64 only). The
armeabi-v7a APK therefore ships the Linux environment and terminal but no
local agent; on such devices the app can still act as a client for a remote
opencode server (Settings → Remote). Bundling qemu-user emulation was
considered and rejected for v0.1 (slow, fragile).
