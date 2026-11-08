# Research 02 — Running Debian on Android without root

Date: 2026-11-08. Purpose: select the mechanism for running a Debian
environment inside an Android app without root.

## Options considered

| Option | Verdict |
|---|---|
| Full virtualization (QEMU) | Rejected: far too slow on phones for a coding agent. |
| chroot | Rejected: requires root. |
| Termux dependency | Rejected: the app would not be autonomous; Termux must be installed and configured. |
| **proot (ptrace-based rootless guest)** | **Selected** — the approach used by Termux's proot-distro and UserLAnd; runs a full distro root tree inside app-private storage. |

> Updated by ADR-010: upstream Linux binaries were replaced with static
> Android-compatible Termux PRoot builds; see `tools/build_proot.py`. The
> original selection below is retained as historical research.

## proot binary: sources evaluated

1. **Termux package proot 5.1.107** — dynamically linked against
   `/system/bin/linker64` plus Termux prefix libraries (libtalloc); not usable
   standalone outside the Termux environment. Rejected.
2. **proot-me/proot GitHub releases v5.3.0** — `*-static` executables for
   aarch64 (1.4 MB), arm (1.1 MB), x86_64 (1.8 MB). Fully static, no external
   libraries. **Selected.** SHA-256 of the binaries bundled into the APK:
   - aarch64: `fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45`
   - arm: `bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69`
   - x86_64: `d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da`

The binaries are bundled as `libproot.so` under `jniLibs/<abi>/` so Gradle's
ABI splits ship exactly one proot per APK, and the extracted file at
`applicationInfo.nativeLibraryDir/libproot.so` is executable.

## Debian rootfs: sources evaluated

1. **debuerreotype/docker-debian-artifacts** (source of Docker's official
   images) — rootfs tarballs are stored via **Git LFS**; raw.githubusercontent
   returns pointer files, the media URL does not serve pinned-commit content
   reliably. Rejected for the app.
2. **Docker Hub official images with digest pinning** — selected:
   - anonymous pull token from `auth.docker.io`,
   - fetch the **pinned multi-arch manifest digest** (see
     `Pins.ROOTFS_MANIFEST_DIGESTS`), read the single layer,
   - the layer blob is **content-addressed**: SHA-256 verified while streaming.
   - debian:trixie images contain exactly one layer (~47.5 MB compressed).

Pinned digests (fetched 2026-11-08 from the registry index):

| arch | manifest digest |
|---|---|
| amd64 | `sha256:bfc36aecdece0387418759dba474aa856c026ca8087da3606b41b90ab0509c99` |
| arm64 | `sha256:e529717ccc8118a0b9d38525f2b4e78cf150dbe470ad60d733c7c0023cabc3b0` |
| arm/v7 | `sha256:4220a655ef92cf9f4bea07e6dc36b55193268feb1176b54e955e05027cf18af2` |

## proot invocation details

- Binds: `/proc`, `/sys`, `/dev`, workspace → `/root/projects`,
  app-written `resolv.conf`/`hosts` → `/etc/…`.
- `-0` (fake root), `--kill-on-exit`.
- Environment: `HOME=/root`, standard `PATH` (incl. `/usr/local/bin`),
  `LANG=C.UTF-8`, `TERM=xterm-256color`, `TMPDIR=/tmp`,
  `PROOT_NO_SECCOMP=1` (compatibility across kernels), `PROOT_TMP_DIR`
  inside app storage.
- DNS: Debian base image ships no working resolv.conf; the app writes
  `nameserver 8.8.8.8 / 1.1.1.1` and bind-mounts it.

## Extraction notes

- The layer is `tar.gz`; commons-compress handles it. Debian rootfs contains
  essential symlinks (`/bin → usr/bin` etc.) — symlinks are recreated, with a
  copy fallback for filesystems that refuse them.
- Entry paths are sanitized against path traversal.

## Consequences

- No root and no Termux dependency — the environment lives in app-private
  storage (`files/linuxenv`).
- Bootstrap is fully automated: download → verify → extract → apt tools →
  opencode CLI (Research 01 / ADR-003).

## Update (2026-11-08): SELinux exec restriction on Android 10+

Real-device testing surfaced the one hard constraint this document
originally understated: apps **targeting API 29+** run in the
`untrusted_app_29` SELinux domain where `execve()` of files under app data
is denied (Android 10 W^X policy). proot must execve guest binaries from
the rootfs in app data, so the app pins `targetSdk = 28` to stay in the
legacy `untrusted_app_27` domain — the same choice Termux and UserLAnd
make. See ADR-007 for the full analysis and rejected alternatives.
