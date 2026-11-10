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

## Stage 2 — complete the working loop

Delivered incrementally; each row documents its validation boundary.
Client contracts were re-verified against the pinned server source
(`sst/opencode` tag v1.19.5: `MessagesQuery` = `limit` + `before` cursor with
`X-Next-Cursor`; session list defaults to the newest 100 with a `limit` query;
`SnapshotFileDiff` = `{file, patch, additions, deletions, status}`;
`PATCH /session/:id` accepts `{title}`).

| ID | Delivered | Validation boundary |
|---|---|---|
| F-011 | Cursor pagination for chat history: the client requests the newest 60 messages (`limit`) and follows the `X-Next-Cursor` header with `before=`; a "Load earlier messages" action prepends older pages and the window survives throttled reloads. The sessions list sends an explicit `limit` (100) and the sessions sheet gains a search filter over the loaded list. Regression-tested against a mock server (limit/before query, cursor header, no-limit fallback). | JVM tests only. Server-side behaviour beyond the mocked header (cursor format drift) is covered by the lenient parser but not device-verified. The V1 sessions endpoint has no cursor envelope — beyond the newest 100 sessions older sessions remain unreachable; recorded as a platform boundary. |
| F-004 | Session management: every session row offers rename (`PATCH /session/:id` with `{title}`) and delete (`DELETE /session/:id`) with a confirmation dialog naming the session; deleting the active session auto-selects the next newest one or empties the timeline. | Build + mock-server tests for the client calls. Device check of the confirm dialog over the sheet pending. |
| F-005 | Diff review: a "Session changes" action in the chat header opens a sheet backed by `GET /session/:id/diff`, listing each changed file with status chip and +/- counters, and an expandable selectable monospace patch view. | Build only; the diff endpoint was not exercised against a live server in this environment. |
| F-007 | File operations: long-press on any workspace row offers rename (within the parent directory) and delete (files and directory trees) behind a confirmation dialog. Operations go through `WorkspaceFiles` with the same root containment and symlink rejection as the editor; editor buffers and drafts pointing into a deleted/renamed subtree are closed, the expanded-state and the selected project follow the rename, and deleting the selected project falls back to the next repository. Regression-tested (delete trees/root/escape, rename collisions, traversal, path separators). | JVM tests + build. Device check of long-press discovery pending. Same-UID agent remains trusted: deleting files the agent is concurrently writing is not prevented. |
| F-008 | Selective staging: `git status --porcelain -z -b` now feeds a commit dialog that lists every change with its two-letter status and lets the user pick what to stage (`git add -- <paths>`, paths passed as separate argv entries and never re-parsed from quotes) before committing; nothing is staged implicitly anymore. The quick-commit input was replaced by the dialog. | JVM/build level. Real multi-path commits (spaces, renames, untracked) are exercised only through parsing tests; a device-side git workflow remains unverified. |
| F-002 | Remote files: in Remote mode attachments can no longer point at phone-side paths the server cannot read. Text-like attachments (text mimes, code/config extensions) are delivered inline as fenced prompt text (≤ 512 KB each); binary or oversized attachments are refused at attach time and before send with an explanatory message. Local mode keeps the `file://` workspace delivery unchanged. An uploads sweep deletes week-old attachment copies so the folder cannot grow unbounded (the F-003 orphan-cleanup follow-up). | Classifier and prompt-body tests are JVM-tested. Live remote-server delivery (what the agent actually receives) is not device-verified. |
| F-013 | Settings backup and categorized deletion: Settings gains "Settings backup" (SAF export of the non-secret snapshot — server address without password, Git identity without token, model choice, appearance, onboarding choice, selected project; SAF import with confirmation that never touches secrets) and "Stored data" (clear drafts, clear workspace/uploads, clear installer downloads/temp), each behind its own confirmation dialog. | Export shape is schema-1 JSON built in SettingsStore; import is lenient (unknown fields ignored). Encrypted-prefs round-trip is not JVM-testable here, so import/export were validated by build + code review only; SAF flows need a device check. |

## Stage 3 — close the working loop

Scope note: the original audit text predates stages 1–2, which restructured the
affected code, so stage 3 was re-derived directly against the post-stage-2 tree
using the same method (client-contract surface vs. wired UI, pinned against
`sst/opencode` v1.19.5). Contracts verified at the tag: `GET /agent` returns
`Agent.Info` with `mode` (`primary`/`subagent`/`all`) and optional `hidden`;
`GET /session/:id/todo` returns `[{content, status, priority}]` and the
`todo.updated` event carries `{sessionID, todos}`; tool parts carry
`state.error` (string) when `status == "error"`; `POST /session/:id/revert`
takes `{messageID}` and stores a revert marker cleared by `/unrevert`.

| ID | Delivered | Validation boundary |
|---|---|---|
| F-006 | Agent selection: `GET /agent` is fetched at connect (hidden and subagent-only agents filtered out, degrades silently if the endpoint is unavailable), a composer chip opens the agent sheet with a "Default agent" row, and the choice persists in the encrypted settings (also part of the settings-backup export/import) and is sent as `agent` in every prompt. | Build + mock-server tests for `listAgents`. Device check of the sheet and of the agent actually driving server behaviour pending. |
| F-010 | Session todos: `GET /session/:id/todo` is loaded for the active session (connect, session switch, reconnect) and `todo.updated` events update the plan live; a compact card above the composer shows each task with a status icon (pending / in_progress / completed / cancelled). | Build + mock-server tests for `sessionTodos`. Live event ordering is covered by reasoning only; device check pending. The endpoint is the only reconciliation source (the pinned server exposes no todo list beyond it), so todos are cleared when the session changes. |
| F-012 | Tool failure details: the part parser now extracts `state.error`, and failed tool cards show an expandable, copyable "Error details" block instead of only a bare `error` status chip. | Parser regression tests (error surfaced; absent error stays null). Rendering validated by build; a live failing tool was not reproduced in this environment. |
| F-014 | Revert/unrevert: long-press on a user message offers "Revert to here" (`POST /session/:id/revert` with `{messageID}`); while the session carries a revert marker, the timeline hides the marker message and everything newer (message ids are time-ordered, matching the V1 `before` cursor) and a banner explains the state and offers "Restore" (`POST /session/:id/unrevert`). | Build + mock-server tests for the two endpoints. The client-side hiding rule mirrors the revert-marker semantics derived from the pinned server source (the message list endpoint does not filter reverted messages); a device check against a live server is pending. |
