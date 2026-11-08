# ADR-008: Bootstrap installs a pre-resolved toolset instead of running apt

Status: Accepted (2026-11-08)

## Context

The first bootstrap pipeline ran `apt-get update && apt-get install git curl
…` inside proot. On real devices this failed reproducibly with
`Tool installation failed (exit 255)` and **empty output**, even though the
identical command works flawlessly in a desktop sandbox.

Two facts pin down the cause:

* proot returns the exit status of the last guest process that exits
  normally. If every guest process is killed by a signal, its internal
  `last_exit_status` stays `-1`, which becomes exit code **255**
  (`exit(-1)`).
* `apt-get update -qq` is silent during the (CPU-heavy) decompression of
  package indexes, so a kill during that phase produces no output at all.

On Android 12+ the **phantom process killer** SIGKILLs app child processes
that use excessive CPU (and keeps the system-wide phantom count below 32).
apt/dpkg under proot is exactly that: a burst of short-lived, ptraced,
CPU-hungry processes. Vendor battery savers behave similarly. Google's own
recommendation for important background work is a foreground service, but
the CPU-based killing cannot be avoided from app code — the reliable fix is
to not run that kind of workload at all.

## Decision

1. The bootstrap no longer executes apt/dpkg inside the environment.
2. Each app release ships digest-pinned `toolset-<arch>.tar.gz` assets
   (git, curl, ripgrep, ca-certificates and their full dependency closure,
   pre-resolved from the Debian trixie index). The app downloads the
   matching tarball, verifies sha256 and extracts it **app-side** into the
   rootfs — the same code path as the rootfs itself.
3. The tarball carries `var/lib/dpkg/status.d/<pkg>` fragments and
   `info/<pkg>.list` files; the app merges them into `/var/lib/dpkg/status`
   so apt/dpkg inside the environment see the packages as installed
   (`apt-get install …` keeps working for interactive use).
4. Only short, low-CPU commands still run inside proot: a `/bin/true`
   preflight probe after extraction, a `git/curl --version` sanity check and
   `git config` during final configuration. Their exit codes are mapped to
   readable explanations (255 → "processes killed by the OS…").
5. The bootstrap runs under the existing foreground service, keeps the
   screen on, and records a timestamped log (also persisted to
   `linuxenv/bootstrap.log`) that the failure dialog shows.

## Consequences

* Bootstrap has no dependency on guest networking and no CPU-heavy guest
  workload — the phantom killer has nothing left to kill during setup.
* First setup downloads ~155 MB in total (Debian ~50 MB, toolset ~58–65 MB,
  opencode ~40 MB) instead of ~90 MB, but the toolset replaces the previous
  apt phase rather than adding to it.
* Toolsets must be rebuilt and re-pinned whenever the Debian suite or seed
  tools change (`tools/build_toolset.py <arch>…`, digests in `Pins`).
* The dpkg database is synthesized (no maintainer scripts ran). For the
  pinned tool set this is equivalent in practice; `dpkg --configure -a`
  inside the environment remains available if a user ever needs it.
