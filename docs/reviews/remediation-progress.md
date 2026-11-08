# Remediation tracker

Reference: [2026-11-08 audit](2026-11-08-kotlin-audit.md), retained as a historical
snapshot. PR #5 implements the changes below; “implemented” does not mean that
all Android/device integration scenarios have been verified.

Batches: `56a0713` transport/generation/digest/UI; `9308b1d` local auth,
fail-closed storage and refresh scheduling; `45f2f8a` cancellation, filesystem,
Git, credentials, session ownership and settings; subsequent fixes include dot
symlinks, cancellation-safe remote migration and confirmed storage recovery.

| Finding | Implementation and validation limits |
|---|---|
| F-001 | Non-loopback HTTPS, certificate validation, no redirects/downgrade; REST/SSE connection snapshots. Provider sync requires endpoint/account-specific remote consent. TLS/redirect helper regression tests; consent UI/device scenarios not instrumented. |
| F-002 | Git HTTPS URLs exclude userinfo/query/fragment; token only in child environment, helper restricted to HTTPS github.com[:443], helper chain reset, TLS required, redirects/hooks disabled. Existing remote/push URL userinfo removed on project access. Migration remove/repopulate cannot be interrupted by coroutine cancellation; each process is time-bounded. JVM helper/config tests use a fake token. Old backups require token rotation. |
| F-003 | Stable encrypted 256-bit local secret shared by launcher/client/probe. Blank-password launch rejected; persistence/concurrency/mode regression tests. Independent APK anonymous-401 test remains device work. |
| F-004 | No plaintext fallback; async Loading/Failed/Ready with retry. Explicit allowlisted legacy import commits secure values before cleanup; existing secure values win. Confirmed destructive secure reset preserves rootfs/projects and warns about credential loss. Permanent loss is not recoverable; device Keystore failure injection remains unverified. |
| F-005 | Component/canonical archive containment, guest-absolute links converted relative, dot/relative links and deferred hardlinks, cancellation during copy. Archive traversal/link/order/merge regressions. |
| F-006 | Bootstrap actor serializes start/retry/reset and cancels/joins old work before replacement/deletion. Interactive processes registered and stopped before reset. Full Android lifecycle/reset race tests remain unimplemented. |
| F-007 | Bounded process output, cancellable wait, timeout/kill/reap/stream cleanup; HTTP cancellation cancels underlying call through body consumption; archive copy checks cancellation. JVM cancellation/process tests; device network/process stress remains unverified. |
| F-008 | Generation tickets prevent late processes/errors from overriding stop or replacement. Deterministic launch/stop regression tests. |
| F-009 | SSE closure/overflow reconnects with bounded exponential backoff+jitter; 401/403 fatal. Reconciles health/providers/sessions/status/pending permissions after reconnect. Full reconnect/backpressure ViewModel tests remain unimplemented. |
| F-010 | Conflated throttled invalidations avoid continuous-event starvation. Virtual-time regression tests. |
| F-011 | Cancellation propagated, required setup failure cannot publish CONNECTED. App-level negative scenarios remain uninstrumented. |
| F-012 | Endpoint epoch/client/session identity checks, selection-job cancellation, endpoint-state reset and session-scoped permissions. Full endpoint/session-switch race suite remains unimplemented. |
| F-013 | Serialized provider sync, endpoint check, explicit consent; only previously managed IDs eligible for deletion. Ownership persisted before PUT and deferred deletion survives offline/endpoint switching. Real-server/device and ownership-failure scenario tests remain unimplemented. DELETE contract checked against upstream v1.19.5 route source. |
| F-014 | Selected project determines Git cwd; clone selects new project, expanding a repository selects it. Git actions serialized. Interactive multi-project device check remains. |
| F-015 | File scan on IO, cancellable generation, depth limit, no symlink traversal. Device responsiveness measurement remains. |
| F-016 | Editor uses bounded UTF-8/NUL validation, content snapshot/hash and identity checks, atomic temp+fsync+move saves. Workspace regression tests. |
| F-017 | Root containment and symlink-component rejection for workspace operations, exclusive create and external-change checks. Workspace regression tests. Same-UID agent is trusted: not an adversarial filesystem sandbox. |
| F-018 | Explicit server draft Apply/Cancel, port/host validation; no per-keystroke persistence/restart. Test/restart unavailable while draft dirty. UI/device scenarios remain. |
| F-019 | Raw manifest bytes must match pinned SHA-256 before parsing; valid and substituted HTTP-response regressions. |
| F-020 | Existing Gradle `test` entrypoint now requires blocking lintDebug, native regressions and assembleRelease (R8/resource shrinking). Only intentional ExpiredTargetSdkVersion policy exception suppressed. Workflow file unchanged (workflow write permission unavailable). CI results recorded in PR; device/instrumentation coverage remains incomplete. |
| F-021 | Password/API-key/PAT masking and keyboard semantics. IME/device verification pending. |
| F-022 | All models selectable with lazy provider/model rows. Large-catalog device measurement pending. |

## Verification boundaries

Local native suite: 7/7; XML parsing and `git diff --check` pass. No local JDK:
Kotlin compilation, JVM tests, lint, release shrinking and per-ABI debug APKs are
validated through PR CI. The first batch-3 CI caught a dot-symlink extraction
bug, fixed without removing the regression test. See the PR checks for the final
head result; an earlier green run is not evidence for a later commit.

Not performed: Android/ARM device execution, IME/accessibility interaction,
Keystore-loss injection, independent-app local authentication, full ViewModel
and lifecycle-race integration suites, signed-release installation or real
secret-bearing Git/server workflows. Do not describe these as verified. No
version bump or release publication is part of this remediation PR.
