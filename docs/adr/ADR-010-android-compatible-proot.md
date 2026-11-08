# ADR-010 — Use an Android-compatible PRoot, not upstream Linux binaries

## New device evidence

The second Android 13 / arm64 screenshot, after ADR-009's change, explicitly
reports `vpid 1: terminated with signal 31` (SIGSYS). Disabling glibc rseq was
not sufficient. The screenshot does **not** reveal the rejected syscall's
number, so it would be incorrect to claim its precise identity is established.

The bundled proot-me v5.3.0 binaries are general-purpose Linux builds. They
forward SIGSYS to the guest rather than providing Android syscall compatibility.
Android app children inherit a seccomp filter. Modern Debian glibc calls more
than rseq during startup: notably `set_robust_list`, which Android app policies
can also reject. Later workloads can encounter other blocked syscalls.

## Reproduction and correction of the previous test

On Linux x86_64 / glibc >= 2.35, trap `set_robust_list` with an inherited
SECCOMP_RET_TRAP filter, and keep `GLIBC_TUNABLES=glibc.pthread.rseq=0`:

- Previous APK's proot-me v5.3.0: `/bin/true` exits **255**, signal **31**.
- Replacement Termux PRoot: `/bin/true` exits **0** under the same filter.

The previous test trapped only rseq, so it could pass while the real Android
startup still failed. `tools/test_proot_seccomp.py` now traps robust-list
registration, rseq, fork and clone3 together, verifies the filter really kills an
untraced glibc process, and checks probe, shell children, threads and subprocess
creation through the actual APK binary, including `-0` fake-root mode. It also
checks rseq enabled, demonstrating that the tracer, not just the tunable, handles
the syscall rejection.

## Decision

Replace **all three** bundled ELFs with static musl builds of Termux's PRoot
at commit `52d65a4dc140ca554d241a1438ddd4f6b00c9813`. Its Android-specific
`tracee/seccomp.c` and `event.c` handle outer-filter SIGSYS with emulation,
rewrites or ENOSYS fallbacks. This preserves Android's sandbox; it does not
remove its filter. Termux itself and its libraries are **not** required.

Keep ADR-009's tunable as a harmless reduction of unsupported syscalls. Add a
small native diagnostic patch that logs the trapped syscall number and audit
architecture at verbosity 1, rather than reporting only the final signal.
Log the pinned native version during bootstrap and bump the APK to 0.1.2/code 3.

## Build and provenance

`python3 tools/build_proot.py` rebuilds all ABIs from pinned sources using
Zig 0.13.0 as a static musl cross compiler, with no NDK or vendor shared libraries.
The script verifies the compiler archive and talloc archive digests. An existing
compiler can be supplied with `--zig /path/to/zig`. Scratch sources/objects stay
under ignored `.work/proot`; only the resulting binaries are shipped.

Build support under `tools/native` contains the exact patches and compatibility
headers. Loader linking uses a linker script equivalent to upstream's text
address option. Segments use 16 KB page-compatible offsets and alignment. Loader
bytes are embedded with assembly `.incbin`, using the same symbol names as GNU
objcopy; a content digest prevents stale Zig cache hits. ARM musl register and
raw IPC command aliases are adapted in the compatibility header. No Android
shared-memory library is linked. musl's `_Fork.c` is rebuilt with its existing
clone branch selected on all ABIs, retaining its atfork/locking/TLS handling.
Otherwise the untraced PRoot process itself would call Android-blocked `fork`
on x86_64 before it could handle any guest SIGSYS. The expanded filter tests
also cover this case. Licenses and source links accompany the APK
under `assets/native-licenses`.

## Validation and limits

- All three ELF builds complete; inspect program headers for static linking,
  correct machine type, non-executable stack and 16 KB segment congruence.
- Expanded seccomp integration tests pass using the shipped x86_64 binary.
- Rebuilding from scratch produces identical binary hashes.
- These are Linux-host tests, **not** an ARM device/emulator test. An APK must
  still be built and installed on the affected phone. If it fails, the new
  native log will report the specific trapped syscall. Neither a complete
  Android seccomp policy nor vendor SELinux behavior is simulated here.

This supersedes ADR-009's rseq-only diagnosis and Research 02's selection of
upstream Linux proot-me release binaries.
