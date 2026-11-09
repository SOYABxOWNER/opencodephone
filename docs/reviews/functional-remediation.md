# Functional completeness remediation — stage 1

Reference: functional completeness audit of `f4757a8` (delivered 2026-11-10),
which measured ~59% coverage of the self-sufficient mobile coding-tool loop and
grouped 26 findings (F-001…F-026) into three delivery stages. These F-IDs are
independent of the F-IDs in `remediation-progress.md` (the 2026-11-08 security
audit). This document tracks delivery; findings are closed only when their
acceptance scenario is reproducible.

## Stage 1 — protect the core scenario

| ID | Delivered | Validation boundary |
|---|---|---|
| F-009 | Dirty editor dismiss (Back/outside) now asks "Discard changes?" with Discard/Keep editing; unchanged editors close silently as before. Dirty check compares the buffer against the open-time snapshot. | Build + existing editor tests. Device check of Back gesture vs IME and the dialog over the fullscreen editor pending. |
| F-003 | `DraftStore` (app-private JSON, atomic temp+rename) mirrors the chat composer (text + pending attachment metadata) and the file editor buffer, debounced 400 ms plus immediate writes on structural changes and a final flush on `onCleared`. Both restore after process death; attachment entries whose workspace copy is gone are dropped; the editor reopens against a fresh snapshot so the save-time external-change check still applies. | JVM build; no instrumentation test of process death yet. Per-session/project draft slots remain future work. |
| F-001 | Shared project context: the repository selected in Files is published to `AppGraph.selectedProject` (workspace-relative, persisted). New chat sessions are titled with the project name; a "Project: …" chip above the composer shows the active context; the terminal shell starts in the selected project directory and prints it in the banner. | Consistency is client-side only: the agent's own working directory is decided by the opencode server, and the V1 `POST /session` body carries only a title (verified against the pinned client contract). No cross-screen rewrite enforcement yet. |
| F-022 | Remote-first onboarding: the bootstrap screen offers "Install local environment" vs "Connect to a server" before any download; the choice is persisted (`SetupChoice`). Remote-only users land in the main experience immediately, the foreground service stays quiet, and Settings → Linux environment offers "Set up local environment" later. Existing installs (environment already present) keep the old auto-bootstrap. | Routing/service effects verified by build + reasoning; a clean-install device walkthrough (blocked Debian sources, reachable remote server) pending. |
| F-023 | README promises aligned with the OpenCode-first catalog in both languages: the "200+ LLM providers" claim was replaced by the live server-reported model catalog wording (standard/free models without a key; optional encrypted custom keys). Provider-sync security sections stay accurate (they describe optional keys). | Documentation only. |

## Verification and limits

- Kotlin compilation, lint, unit and render tests are validated by PR CI on the
  final head; no local Android build exists in this environment.
- No new experimentally-gated Compose APIs were introduced.
- Not performed: device execution, TalkBack/IME interaction checks, clean-install
  onboarding walkthrough, process-death instrumentation tests. These remain the
  audit's stated validation boundaries and are not claimed as verified.
- Stage 2 items (F-005 diff review, F-007/F-008 file & Git operations, F-004/F-011
  session management & pagination, F-002 remote files, F-013 export/restore) are
  not started and are tracked in the audit plan.
