# ADR-003 — Installing OpenCode and pinning integrity

Status: accepted (2026-11-08)
Context: docs/research/01-opencode-platform.md

## Decision

At bootstrap (after the Debian rootfs is extracted):

1. Install base tools via `apt-get install git curl ca-certificates ripgrep …`
   (DNS provided by bind-mounted resolv.conf).
2. Install the OpenCode CLI **without Node.js**: download the npm package
   `@opencode/cli-<target>@<version>` tarball, verify the registry-supplied
   **sha512 SRI**, extract the binary into `/usr/local/bin/opencode`.
3. Pin the version (`2.2.6` at v0.1.0) in code; the pin is editable in
   Settings and applied on environment reinstall.

## Rationale

- The official V2 install script needs curl+tar only, but routing the
  download through the app gives real progress reporting and SRI verification
  with no extra dependencies inside the guest.
- Debian base images lack git/curl; apt inside the environment is the
  standard, transparent route.

## Alternatives rejected

- Bundling the opencode binary into the APK: balloons every APK by ~40–70 MB
  and breaks the chosen "download at first run" distribution model.
- Installing Node.js + `npm i -g`: heavy and unnecessary — platform packages
  ship a self-contained binary.

## Consequences

- Bootstrap needs network; offline-first install is out of scope for v0.1.
- Upgrading opencode = change the pin → reinstall environment.
