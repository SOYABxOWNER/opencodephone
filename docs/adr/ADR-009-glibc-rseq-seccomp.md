# ADR-009 — Disable guest glibc rseq on Android

> Follow-up: ADR-010 supersedes the rseq-only fix after a second device log
> confirmed SIGSYS despite the tunable. The native tracer must support Android.

## Evidence and limits

The RMO-NX1 / Android 13 / arm64 screenshot reports that extraction succeeds,
then `/bin/true` immediately returns 255 with no output. This happens before
any toolset or apt workload. Battery restrictions are not established by
that code alone. In bundled upstream proot v5.3.0 (`src/tracee/event.c`),
`last_exit_status` starts at -1 and is updated only on normal guest exit;
if the guest dies from *any* signal the result can be 255. Signal messages
are emitted only at verbosity 1 or higher.

Debian trixie uses modern glibc, which attempts rseq registration during
startup, including for `/bin/true`. An inherited Android app seccomp policy
can trap this syscall with SIGSYS. `PROOT_NO_SECCOMP=1` only disables proot's
own acceleration filter; it does not remove the Android filter.

The committed Linux regression test installs a small inherited seccomp filter
that traps rseq and runs the **actual bundled x86_64 proot** with host glibc:

- rseq enabled: `/bin/true` returns 255, verbosity 1 reports SIGSYS (31).
- production environment: `/bin/true` exits 0; shell children also work.

This reproduces a concrete cause matching the screenshot, not proof of the
specific phone's signal. A real-device run is still required to confirm it;
the new probe diagnostics distinguish it from SIGKILL and other failures.

## Decision

Set `GLIBC_TUNABLES=glibc.pthread.rseq=0` in the shared guest environment so
bootstrap, configuration, terminal, git and OpenCode children inherit it.
This disables an optional glibc optimization without weakening Android's
sandbox or requiring root, adb, or system-setting changes.

Run the bootstrap probe with `-v 1`, stream its output to the bootstrap log,
and include the tail in the error. Stop asserting that exit 255 means the
phantom process killer. Also use Process.waitFor's timed overload: coroutine
timeouts cannot interrupt the previous blocking waitFor(), so the advertised
probe deadline was ineffective.

## Validation

`python3 tools/test_proot_seccomp.py` requires Linux x86_64 and glibc >= 2.35;
run it before Gradle tests. Kotlin unit tests lock the environment value
and probe argument construction. The model does not cover every vendor
seccomp/SELinux policy, ARM execution, or full Android installation.
