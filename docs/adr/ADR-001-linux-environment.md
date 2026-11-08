# ADR-001 — Linux environment: proot without root

Status: accepted (2026-11-08)
Context: docs/research/02-android-linux-env.md

## Decision

Run the Debian environment with **proot** (ptrace-based rootless guest root),
bundling a static proot binary per ABI inside the APK and downloading a
digest-pinned Debian trixie rootfs at first launch.

## Rationale

- The app must be autonomous: no root, no Termux, no external setup.
- proot is the proven pattern (Termux proot-distro, UserLAnd) and runs in
  app-private storage on any device.
- Full virtualization is too slow for an interactive coding agent on phones.

## Alternatives rejected

- chroot — requires root privileges.
- QEMU full system emulation — performance unacceptable.
- Termux as a host — external dependency breaks autonomy.

## Consequences

- Slight syscall overhead vs chroot (acceptable for an agent driven by HTTP
  and git).
- `-0` fake-root identity required so apt/dpkg behave.
- `PROOT_NO_SECCOMP=1` for kernel compatibility.
