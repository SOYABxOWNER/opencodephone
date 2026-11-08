# ADR-006 — Terminal without a pseudo-terminal (v0.1)

Status: accepted (2026-11-08)

## Decision

The v0.1 terminal runs a persistent `bash -i` inside the environment through
pipes (stdout/stderr merged into a console buffer, commands written to stdin).
No JNI/pty layer in this release.

## Rationale

- The primary UX is the native chat UI over the HTTP API; the terminal is a
  secondary power tool.
- A JNI pty implementation would force the NDK into the build and CI for a
  component that is not on the critical path.
- Pipe-based execution keeps the build NDK-free while covering everyday use:
  git commands, file inspection, apt, opencode CLI checks.

## Limitations (documented honestly)

- No full-screen TUI programs (vim, htop, the opencode TUI) in the terminal
  screen.
- No job control signals (Ctrl+C as a control character).
- Line editing is handled by the input field, not readline.

## Future

A JNI `forkpty()` layer (Termux-style terminal emulation or a permissively
licensed VT parser) is the planned upgrade path; this ADR will be superseded
when it lands.
