# Remediation tracker

Reference: [2026-11-08 audit](2026-11-08-kotlin-audit.md). That document is a
historical snapshot, not a list of changes already implemented.

## Batch 1 — transport, process ownership and small UI fixes

| Finding | Status | Delivered / remaining |
|---|---|---|
| F-001 | Partial | Non-loopback hosts default to HTTPS; explicit remote HTTP is rejected. REST and SSE share the policy, certificate verification is unchanged, redirects (including cross-origin HTTPS and HTTP downgrade) are disabled. Requests capture one connection snapshot. Endpoint-specific consent before syncing stored provider keys is **not yet implemented**. |
| F-008 | Implemented with regression tests | Start/stop use a generation ticket; a late child is terminated instead of published. Late launch errors cannot replace Stopped. Cancellation immediately after launch is checked before readiness. |
| F-019 | Implemented with regression tests | The original manifest response bytes must match the pinned SHA-256 before parsing. HTTP-path tests cover a valid pin and substituted valid JSON. |
| F-020 | Open (permission blocked) | Adding native tests to CI was rejected by GitHub because the Arena app lacks `workflows` permission. The workflow is unchanged; the ready-to-apply step is below. Blocking lint, app/device tests and release/R8 checks remain open. |
| F-021 | Implemented, device verification pending | Password, PAT and API-key inputs are masked and use password keyboard semantics. A reveal toggle can be added separately. |
| F-022 | Implemented, device verification pending | All models are selectable; provider headers and model rows are individual lazy items rather than eagerly composing an entire provider catalog. |

### Compatibility and verification

- Existing remote bare hostname settings now use HTTPS on the saved port. For
  an old plain HTTP listener, configure a valid TLS reverse proxy and its port,
  or an encrypted tunnel to localhost. No silent downgrade or insecure-cert
  override is offered. Local proot HTTP remains unchanged.
- Full URLs are not accepted in the Host field; IPv6 URL formatting is delegated
  to OkHttp's HttpUrl builder. The settings screen explains this in RU/EN.
- TLS tests use local MockWebServer certificates: trusted peer works, the default
  trust store rejects an untrusted self-signed peer, and redirects must not send
  provider credentials to a second server. No real secrets or external endpoints
  are used in these tests.
- Launch/stop tests use latches rather than sleeps to control the critical race.
- Local native regressions passed (7 tests). Local Gradle cannot start without a
  JDK in this sandbox; JVM tests and APK compilation are checked by PR CI. No
  claim of Android/ARM device or release-R8 validation is made.

## Still open

F-002, F-003, F-004, F-005, F-006, F-007, F-009, F-010, F-011, F-012,
F-013, F-014, F-015, F-016, F-017, F-018; remaining parts of F-001/F-020.

Next security batch should address PAT-free Git authentication and migration of
existing token-bearing origin URLs (F-002), authenticated local API (F-003), and
secure-store recovery without silent plaintext fallback (F-004). Credential
consent/scoping must be addressed before marking F-001 fully resolved. Process
cancellation/reset, filesystem safety and SSE/session ownership need separate
focused patches and regression tests; they are not implied fixed by this batch.

## CI step awaiting workflow permission

Insert after JVM tests in `.github/workflows/ci.yml` once GitHub workflow write
permission is granted. This is documentation only; it does not change CI yet.

```yaml
      - name: Native ELF and Android-like seccomp regressions
        run: python3 tools/test_proot_seccomp.py
```
