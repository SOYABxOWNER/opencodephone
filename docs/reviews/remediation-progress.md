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

## Batch 2 — local auth, fail-closed storage and chat progress

| Finding | Status | Delivered / remaining |
|---|---|---|
| F-003 | Implemented, device verification pending | 256-bit random local secret is committed to encrypted settings before use. Launcher, client and probe share effective local credentials; remote settings remain separate. The launcher rejects blank passwords. Tests cover persistence, concurrent callers, failed persistence, mode separation and launch environment. A second Android APK/real-device anonymous-401 check remains outstanding. |
| F-004 | Partial | Plaintext fallback removed. Secure initialization runs off Main, with Loading/Failed/Ready startup and non-destructive retry UI; foreground service waits for initialization. Legacy plaintext files are detected and warned about, not silently loaded/deleted. Explicit legacy import/cleanup and recovery after permanent key loss remain open. Device Keystore fault-injection validation is still needed. |
| F-010 | Implemented with virtual-time tests | Conflated throttled invalidations replace the restarting debounce; refreshes occur during a continuous event stream and a final pending invalidation is delivered. Cancellation during the delay is covered. |
| F-011 | Implemented in ChatViewModel, scenario tests pending | Suspend catch paths rethrow CancellationException; required auth/provider/session/message setup failures propagate to ERROR instead of CONNECTED. Optional refresh/SSE failures are visible. App/ViewModel-level negative tests remain needed. |

A previous local user-entered password is no longer the local server credential;
local auth is generated and not editable in the remote credential fields. A
manual independently started server on the same port will not be adopted by
silently disabling authentication. There is no destructive reset/recreate action
on the secure-startup failure screen. Old plaintext files may still contain keys;
users must rotate exposed keys, and explicit migration/cleanup is not yet provided.

## Still open

F-002, F-005, F-006, F-007, F-009, F-012, F-013, F-014, F-015, F-016,
F-017, F-018; remaining parts of F-001/F-004/F-020 and scenario/device validation
listed above.

Next security batch should address PAT-free Git authentication and migration of
existing token-bearing origin URLs (F-002), plus explicit legacy secure-store
migration/recovery (remaining F-004). Credential
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
