# ADR-007 — targetSdk pinned to 28

Status: accepted (2026-11-08)
Supersedes: the implicit "latest targetSdk" choice of the initial build.

## Context

First real-device test (Android 10+, `targetSdk 36`) failed with:

```
proot error: execve("/usr/bin/bash"): Permission denied
```

Since Android 10 (API 29), apps **targeting API 29+** run in the
`untrusted_app_29` SELinux domain, which forbids `execute` on
`app_data_file` (the W^X restriction): an app may no longer `execve()`
files inside its own writable data directory. proot works precisely that
way — it ptrace-attaches to a child and the child execve()s the guest
binary resolved to its **host** path under the rootfs
(`/data/user/0/<pkg>/files/linuxenv/rootfs/usr/bin/bash`), which lives in
app data. Hence EACCES for every guest binary, while proot itself runs
fine because it is bundled as `libproot.so` in `nativeLibraryDir`
(execution there stays allowed).

This is the same reason Termux and UserLAnd keep `targetSdk 28`.

## Decision

Pin `targetSdk = 28` (minSdk stays 26). Apps targeting API ≤ 28 run in the
legacy `untrusted_app_27` domain, which still allows `execute` on
`app_data_file`, so proot can exec rootfs binaries on every Android
version from 8.0 through 15+.

Consequences handled in the build:

- `lint { checkReleaseBuilds = false }` — the ExpiredTargetSdkVersion
  vital check would otherwise fail the release build; we do not ship
  through Play Store, so its targetSdk floor does not apply.
- No `FOREGROUND_SERVICE` type declarations are required (that
  requirement starts at targetSdk 34).
- `POST_NOTIFICATIONS` is auto-prompted for targetSdk ≤ 32 apps, so the
  foreground-service notification still shows on Android 13+.

## Alternatives considered

- **targetSdk 29+ with binaries in jniLibs** — impossible for the rootfs:
  it is ~200 MB unpacked, downloaded at runtime, and must be writable;
  `nativeLibraryDir` is read-only APK-internal storage.
- **chroot** — requires root; the project targets unrooted phones.
- **qemu-user emulation from an exec-allowed location** — rejected again
  (see ADR-005) and it does not remove the exec restriction anyway.
- **targetSdk 23 floor abuse** — Android 14 blocks installs below 23 and
  Android 15 below 24; 28 keeps comfortable headroom while preserving the
  legacy SELinux domain.
