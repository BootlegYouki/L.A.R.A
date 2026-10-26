# Desktop Technical Spec

> Owner: Desktop team (Aki-QA and kerrV) | Reviewer: Lead Developer | Status: Draft — pending Kiervy and Lead review | Last updated: 2026-10-26
> Sprint 0: [#37](https://github.com/BootlegYouki/L.A.R.A/issues/37). Kiervy may revise these implementation choices before approval. This draft does not claim his review or replace product requirements. Lead approval must precede a Sprint 1 merge.

## 1. Scope and Non-Goals

Desktop owns the learner and teacher client described in [PRD sections 3–6](../../docs/PRD.md): discovery, accounts/classes, offline learning, homework, quizzes, gradebook and optional grounded tutor. Sprints 1–2 are specified in section 10; later issues retain their contract-defined scope.

Sources, in order: [contracts](../../contracts/), [root guide](../../AGENTS.md) and [rules](../../rules/), [design system](../../design-system/design-system.md), PRD, then this document. Paths below are relative to `desktop/` unless prefixed otherwise. Proposed modules are created only in their issue.

Non-goals: Hub administration, Android code, new endpoints/schema columns, cloud backends, grade categories, automatic model downloads, Electron/Prisma, or a model choice before the AI evaluation. Classroom and assessment features must work without AI. No feature development is authorized by an unresolved question here.

Existing scaffold [PR #161](https://github.com/BootlegYouki/L.A.R.A/pull/161) stays draft while Sprint 0 is reviewed. This spec adopts its `src/`, `store/`, `i18n/`, `services/db.ts`, `tests/` and `src-tauri/migrations/` names; it does not merge that PR. Only the spec is changed by this docs branch. Resume one desktop implementation issue at a time after approval; do not open another feature PR alongside the scaffold.

## 2. Architecture

```text
main.tsx → App.tsx → pages/ + components/
                       ↓
                  store/app.ts
                       ↓
          services/ (typed HTTP, realtime, SQL)
                ↓                   ↓
        Hub REST/WS           Tauri commands
                              discovery / secure token / atomic sync
                                      ↓
                         sqlite:lara_client.db + local files
```

`src-tauri/src/main.rs` delegates to `lib.rs::run()`. Pages must use stores/services, never SQL, fetch or native IPC directly. Services validate network JSON as `unknown` before typed use; wire fields stay `snake_case`. Native commands own operations requiring a single connection or OS access. Render local data first; network refresh updates local storage and then the store. Network loss must not discard the current view.

## 3. Key Decisions

| Decision | Choice | Why | Rejected alternative |
|---|---|---|---|
| Foundation | Tauri 2, React 19, strict TypeScript 5, Vite 6, Tailwind 4; lockfiles | Required stack, small native client | Electron, Prisma, new framework |
| State and strings | Zustand `useAppStore`; `i18n/en.json` and `fil.json` | Matches scaffold; one runtime language toggle | Multiple competing stores/dictionaries |
| Data | Official plugin-sql; `services/db.ts`; immutable numbered native migrations | Canonical client schema; reusable reads | Browser-only cache; modifying applied migrations |
| Atomic writes | Rust `apply_sync_pull` command with one SQLx transaction on the same database file | JS plugin calls can use different pooled connections | Separate JS `BEGIN`/`execute`/`COMMIT` calls |
| REST transport | Tauri HTTP plugin through `services/api.ts`; browser WebSocket in `realtime.ts` | Avoid platform CORS differences for REST; supported WS contract | Duplicated browser/native REST implementations |
| Token | Rust `secure_token.rs` using OS credential storage via `keyring`; expose narrowly scoped commands | Windows Credential Manager, macOS Keychain, Linux Secret Service; no plaintext token file | `localStorage`, SQLite, unprotected Tauri store |
| Preferences | `services/settings.ts` owns nonsecret language/Hub preferences; retain scaffold localStorage language | No extra schema/table; shared accessor for Settings and Connection | Inventing a `settings` SQL table |
| UI | Bundled Nunito/Phosphor, canonical theme import, Radix Dialog when needed | Required accessible visual vocabulary | CDN assets, copied prototype runtime/demo data |
| Tutor later | Opt-in `llama-server` sidecar, shared prompt byte for byte, Hub fallback | Same grounded behavior; no model fixed before evaluation | Separate local prompt or unconditional RAM/model load |

Network permissions must be added with the first network issue, not the scaffold. The HTTP plugin scope and WebSocket/media CSP must permit only Hub transport and required loopback development. Because LAN IPs are dynamic, broad LAN-capable scope is not itself an authorization boundary: `api.ts` must restrict requests to the selected validated Hub and fixed contract paths, reject redirects to other origins, and never forward a token to a newly discovered address automatically. Remote URLs must be explicitly saved HTTPS Hub addresses; use WSS, never a TLS bypass. Confirm exact production capability/CSP patterns in the #40 spike before merging network code.

If the OS credential service is unavailable, keep the token only in memory for that session, explain that sign-in will not persist, and retain cached offline access. Do not fall back to plaintext. Linux/macOS persistence must be tested before their packages ship.

## 4. File Map

```text
src/main.tsx, App.tsx       React entry and role/layout routing; no transport logic
src/pages/                 LoginPage, HomePage, ConnectionPage, RosterPage, SettingsPage
src/pages/teacher/         ClassSettings; owner-only class management controls
src/components/            Cards, banners, Sidebar, TopAppBar, Radix dialogs; no SQL
src/store/app.ts           useAppStore; loading/ready/error, cached view and language
src/services/              db.ts, api.ts, discovery.ts, auth.ts, classrooms.ts,
                           realtime.ts, sync.ts, settings.ts; feature services added later
src/services/types.ts      Contract-matching public types and runtime guards; no answer keys
src/i18n/                  en.json, fil.json, index.ts; equal keys including accessibility labels
src/assets/design-system/  Bundled fonts/icons/licenses; no remote asset references
src/index.css              Shared theme/tokens, focus and reduced-motion rules
src-tauri/src/             main.rs, lib.rs; discovery.rs, secure_token.rs, sync.rs as needed
src-tauri/migrations/      001_client_offline.sql; later additive migrations only
src-tauri/capabilities/    Main-window least-privilege permissions
src-tauri/binaries/        Approved sidecar binaries only in Sprint 5
tests/                     Vitest service/store/schema tests (directory is desktop/tests/)
docs/                      This spec and feature verification/spike evidence
```

The schematic `src-tauri/src/db.rs` in the README is not a second migration owner: `lib.rs` registers SQL plugin migrations from `src-tauri/migrations/`. Do not introduce a competing schema. Prefer existing scaffold components before extracting a reusable one.

## 5. Names (one name per thing)

| Name | Kind | Responsibility |
|---|---|---|
| `useAppStore` | Zustand store | UI state, cached classrooms, language and bounded initialization |
| `openDatabase`, `readSavedClasses`, `SavedClassroom` | Existing DB service API/type | Deduplicated open and account-scoped class reads |
| `Language`, `Dictionary`, `dictionaries` | Existing i18n exports | `en`/`fil` and matching text keys |
| `HubInfo`, `get_discovered_hubs`, `hub-found` | Native type/command/local event | Validated discovery list and incremental notification; not Hub WS events |
| `api.ts`, `ApiError` | Transport module/error type | Selected-Hub requests, bearer attachment, validation and error mapping |
| `realtime.ts` | WS module | One socket per active session; HELLO and reconnect backoff |
| `SyncPullResponse`, `isSyncPullResponse` | Contract type/guard | Validated pull payload; no speculative fields |
| `apply_sync_pull` | Native command in `sync.rs` | One atomic records/tombstones/cursor/reset operation |
| `sync.ts` | Orchestrator | Single-flight push/pull, paging and refresh notifications |
| `settings.ts` | Preferences service | Language and saved Hub settings; no credentials |
| `secure_token.rs` | Native credential module | Store/read/delete current account and Hub scoped token |

## 6. Contract Usage

All REST entries below refer to [OpenAPI](../../contracts/openapi.yaml); types and exact payloads must match it. Prefix `/api` is omitted in this table only. Lists served through pull must not gain invented GET endpoints.

Common handling **R**: 10-second metadata-request deadline; transport loss shows cached data/offline banner. Retry reads on explicit retry or reconnect with 1, 2, 4, maximum 10-second backoff, one request in flight; stop on sign-out. Never blindly replay non-idempotent mutations after a timeout: refresh state first. 400/422 maps validation feedback, 401/WS 4401 removes active credentials and requests online sign-in while protecting cached account data, 403 disables the forbidden operation, 404 refreshes/removes stale selection, 409 refreshes conflicting state. Unknown errors show a translated generic message without raw server content. 5xx preserves data; retry only safe reads or identity-stable queued operations. Honor Retry-After on 429; do not invent a wire error code.

| Route(s), including methods | Owning module | Specific errors / unreachable behavior in addition to R |
|---|---|---|
| `POST /auth/register`, `/auth/login`, `/auth/logout` | `auth.ts` | Invalid credentials stay on login; registration/login need Hub. Offline logout clears local token and reports revocation not delivered |
| `GET /classrooms`; `POST /classrooms`, `/classrooms/join`; `PATCH /classrooms/{id}` | `classrooms.ts` | `CLASS_CODE_INVALID` at join input; pending is not access; create/join/rename/archive require Hub |
| `GET /classrooms/{id}/roster`; `POST /classrooms/{id}/approve`, `/reject`, `/remove` | `classrooms.ts` | Teacher authorization enforced by Hub; cached roster read-only offline |
| `POST /classrooms/{id}/teachers`; `DELETE /classrooms/{id}/teachers/{teacher_id}` | `classrooms.ts` | Owner-only controls; no offline queue |
| `POST /sync/pull`, `/sync/push` | `sync.ts` | Roll back incomplete pull; receipts alone confirm push; rejected work remains visible |
| `POST /topics`; `PATCH`, `DELETE /topics/{id}` | Later `classwork.ts` | Offline list from mirror; edits need Hub |
| `POST /announcements`; `PATCH`, `DELETE /announcements/{id}`; `GET`, `POST /announcements/{id}/comments` | Later `stream.ts` | Cached feed; comments may queue with UUID; moderation rejection remains visible |
| `POST /materials`; `PATCH`, `DELETE /materials/{id}`; `GET /materials/{id}/chunks`, `/download`, `/stream` | Later `materials.ts`, `video_downloader.ts` | Uploads need Hub; read cached text/files. Handle 206 and 416, keep partial downloads; scoped `?token=` only where contract permits |
| `POST /assignments`; `PATCH`, `DELETE /assignments/{id}`; `GET`, `POST /assignments/{id}/private-comments` | Later `assignments.ts` | Offline classwork and own thread; UUID comments queue; teacher edits need Hub; delete archives assignment |
| `POST /assignments/{id}/submit`; `GET /assignments/{id}/submissions`; `PATCH /submissions/{id}`; `GET /submissions/{id}/file` | Later `homework.ts` | Multipart retains `submission_id` on retry; queued file persists locally; grading needs Hub |
| `POST /quizzes`; `GET /quizzes/active`; `GET`, `PATCH /quizzes/{id}` | Later `quizzes.ts` | `QUIZ_NOT_DRAFT` opens read-only; teacher authoring is online; answer keys must not enter client persistence |
| `POST /quizzes/{id}/start`, `/begin`, `/close`, `/submit`; `GET /quizzes/{id}/results` | Later `quizzes.ts` | Hub begins/starts; existing attempt can finish offline. Reject late work visibly; null held scores remain null |
| `POST /export/class-record` | Later `gradebook.ts` | Follow contracted response/download behavior; no invented USB/admin parameter; unavailable offline |
| `GET /model`, `/model/file` | Later `sidecar_ai.ts` / download service | Missing offer leaves Hub AI path; resume ranges, verify checksum and space before install; opt-in only |

Excluded desktop routes: `GET /download` is distribution, and `/api/admin/*`, `/api/backup`, `/api/restore` belong to Hub admin. No desktop admin login flow.

All events use [event schemas and handshake](../../contracts/events/README.md) and are owned by `realtime.ts`; invalid frames must be ignored with a non-sensitive diagnostic, never unsafe-cast. Events are hints; pull/REST is authoritative.

| Events | Consumer and handling |
|---|---|
| `EVENT_HELLO`, `EVENT_HELLO_ACK` | Send token; establish Hub/epoch and clock offset; mismatch triggers reset pull |
| `EVENT_JOIN_REQUEST`, `EVENT_JOIN_APPROVAL` | Roster/join state refresh; ACTIVE unlocks, REJECTED/REMOVED locks access |
| `EVENT_ANNOUNCEMENT_PUSH` | Trigger coalesced sync, then refresh feed |
| `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED` | Start monotonic countdown; close locks inputs and saves/submits once |
| `EVENT_QUIZ_SUBMIT` | Contracted alternative only; use REST/queued push as desktop default, never duplicate both paths |
| `EVENT_GRADE_CONFIRMED`, `EVENT_PRESENCE` | Refresh own results or teacher monitor; no guessing missing identifiers |
| `EVENT_AI_CHAT_REQUEST`, `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM` | Later tutor request, translated queue status, incremental text; no automatic duplicate chat replay |
| `EVENT_ERROR` | Map schema code; `QUIZ_IN_PROGRESS` unmounts tutor; unavailable AI leaves lesson usable |

WS reconnect: 1/2/4/10-second maximum backoff; one socket; reconnect triggers pull. Retain `last_event_id` only if a contracted event actually supplies it; never manufacture IDs. Do not replay non-idempotent events on reconnect.

## 7. Data and Offline Behavior

Use [client_offline.sql](../../contracts/schema/client_offline.sql) and [sync rules](../../rules/database-and-sync.md), not copied column definitions. `lara_client.db` lives in the SQL plugin app directory. Enable foreign keys on every connection, including native transaction connections; test this rather than relying on one JS PRAGMA. Media lives under app-managed local files; paths stay out of network payloads. localStorage contains nonsecret preferences only.

One account/Hub owns an active mirror. Key credentials and cached data by that context. Switching accounts/Hubs must not expose the previous account's mirror or upload its queued rows; retain the old context until its work is acknowledged or explicitly handled. Implement isolated account/Hub database contexts as part of #45 without changing contract tables; keep `lara_client.db` as the initial scaffold filename. No offline authentication by saved PIN; cached access is the previously signed-in local profile, not proof of a new sign-in.

Queue only the four contract-supported row families: homework submissions, quiz attempts, announcement comments and private comments. Save UUID, author/context and local file before acknowledging UI success. `QUEUED_FOR_SYNC` becomes `SYNCED` only on a matching receipt. Rejection remains visible with its reason; the schema has no REJECTED sync status. Until durable reason storage is agreed (section 14), do not claim restart-safe rejection explanations or implement a retry loop that repeatedly sends permanent failures.

Pull is single-flight: validate complete response, open native transaction, apply parent-first upserts and child-first tombstones, write Hub/epoch/cursor, commit, then notify UI; repeat `has_more`. A network failure before a complete response must make no DB changes. An apply failure/crash must roll back including cursor. Reset must preserve queued rows AND their foreign-key parents; do not cascade-delete queued work. Resolve the exact reset/revoked-access boundary in #48 before shipping it. New membership must recover its full visible snapshot; no client-created cursor rewind workaround without contract review.

| Screen family | No Hub behavior |
|---|---|
| Home, Stream, Classwork, People, My Work, gradebook | Cached account-scoped rows, meaningful empty state and banner; no blocking launch error |
| Login/register, Connection | Cached previous session can open; new sign-in/register waits for Hub; manual entry remains available |
| Join, class settings, teacher authoring/grading/export | Show cached read-only data; disable network-only actions with translated helper text |
| Homework/comments | Save locally and show queued state; attachments kept until receipt |
| PDF/video | Open downloaded files; uncached content explains it needs the Hub; no endless spinner |
| Existing quiz attempt | Continue monotonic countdown; lock/queue at zero or close; never fabricate a new offline attempt |
| Tutor | Local only if evaluated model installed and RAM permits; otherwise explanatory offline state; never mounted during active attempt |
| Settings | Change language and local Hub preferences; warn/count queued rows before logout; preserve them |

## 8. Code Patterns

Reuse scaffold `readSavedClasses()` for account-scoped reads. UI may show an error/retry alongside cached rows; `loadSavedClasses` must deduplicate concurrent loads and bound stalled initialization.

```ts
// services/api.ts: validate at the boundary; helpers belong to this one module.
const payload: unknown = await response.json()
if (!isSyncPullResponse(payload)) throw new ApiError('invalid_response')
return payload // narrowed SyncPullResponse; not `as SyncPullResponse`
```

```rust
// sync.rs: schematic control flow, not a second schema definition.
let mut tx = pool.begin().await?;
if page.reset { preserve_queued_and_reset(&mut tx).await?; }
apply_records_and_tombstones(&mut tx, &page).await?;
write_sync_state(&mut tx, &page).await?;
tx.commit().await?; // return success only after cursor and records commit together
```

```ts
// sync.ts: preserve identity; mark only acknowledged rows.
const receipt = await uploadQueued(row) // homework uses multipart submission_id
if (receipt.status === 'SYNCED') await markSynced(row.id)
else showRejected(row.id, receipt.reason) // never delete or auto-mark SYNCED
```

These are patterns, not implemented APIs. Reset helper must first satisfy the preservation tests in section 13. Runtime guards must inspect required nested records, not just `next_cursor`. Parameterize all SQL; user text must never become SQL syntax. Render text as React text, never untrusted HTML.

## 9. Pitfalls

- SQL plugin pools invalidate JS transaction assumptions: native transaction spike is mandatory. Native writes must target the exact plugin database and serialize competing syncs.
- Scaffold `Database.load`/query timeout bounds UI waiting; it cannot cancel a stalled native call. Preserve retry state and cached rows.
- Webview networking is platform dependent: test production CSP, native HTTP scope, browser WS and media separately. The scaffold's IPC-only CSP deliberately has no Hub access.
- A 401 probe before login proves reachability, not authentication or class access. #40 must not send an unauthenticated `GET /classrooms` and call the resulting 401 “offline”.
- IPv4 regex alone accepts `999.999.999.999`: parse octets and port 1–65535; reject URL credentials, fragments and non-Hub redirects. Do not send existing credentials to unverified beacon candidates.
- `<video>` cannot attach Authorization: use contracted scoped `?token=`; never log the resulting URL, and do not persist it with the token.
- Tauri store is not automatically encrypted. Credential-service failure must never silently persist plaintext.
- TeacherQuizInput contains authoring answers: transient teacher editor state is distinct from redacted learner types. A local persistent question bank conflicts with the no-answer-keys rule; section 14 blocks that part until Lead resolves it.
- Quiz time uses server offset plus `performance.now()`, not wall-clock countdown. Remote timing anchors to fetched Hub `started_at`; elapsed time survives transport loss. Resume after process restart requires Hub timing reconciliation, not assumed monotonic continuity.
- Tailwind 4 must explicitly load canonical tokens/theme; copying a config filename alone does nothing. All labels, ARIA text, errors and skeleton descriptions need both dictionaries.

## 10. Build Order

Both developers work each issue together, one issue at a time. “Creates” assigns ownership once; subsequent rows import/reuse it. These are proposed work steps, not completed claims. Check section 13 for proof.

| Step | Issue | Creates / reuses | Needs first |
|---|---|---|---|
| 0.1 | #37 | This spec; Kiervy review then Lead approval | Contract foundation |
| 1.1 | #29 | Existing draft #161: shell, `db.ts`, `store/app.ts`, dictionaries, migration, tests/assets | Approved #37 |
| 1.2 | #5 | `discovery.rs`, `services/discovery.ts`, `HubInfo`, `get_discovered_hubs`, `hub-found`; register in `lib.rs` | #29 |
| 1.3 | #40 | `ConnectionPage.tsx`, `HubStatusBanner.tsx`, `api.ts`, `types.ts`, `settings.ts` accessors and Hub preferences; reuse discovery | #5; network scope spike |
| 2.1 | #45 | `LoginPage.tsx`, `HomePage.tsx`, `Sidebar.tsx`, `ClassroomCard.tsx`, `auth.ts`, `classrooms.ts`, `secure_token.rs`; initial profile/class snapshot and context isolation | #40; reuse DB/store/preferences |
| 2.2 | #46 | `JoinClassModal.tsx`, `realtime.ts`; pending/approval state | #45; reuse API/classroom types |
| 2.3 | #47 | `RosterPage.tsx`, `CreateClassroomDialog.tsx`, `TopAppBar.tsx`; extend `classrooms.ts` | #46; reuse realtime/auth |
| 2.4 | #48 | `sync.ts`, native `sync.rs`, `apply_sync_pull`, transaction/crash tests | #47; reuse DB and contract types; reset spike |
| 2.5 | #105 | `SettingsPage.tsx`; extend existing `settings.ts` and auth logout; no second language store | #48; reuse queued count and credentials |
| 2.6 | #123 | `pages/teacher/ClassSettings.tsx`; extend classroom service, archived Home filter | #105; mock owner/co-teacher routes |

Until #48, #45–47 persist only their explicitly fetched, validated snapshots through `db.ts`; they must not impersonate the delta engine or advance its cursor. #48 becomes the one refresh/queue owner. Remove the scaffold `foundation_note` when sign-in lands.

Later order: Sprint 3 #30, #55–58, #124–125; Sprint 4 #31, #62–63, #104, #108; Sprint 5 #32, #67–68, #103; Sprint 6 #71. Reuse the same transport, SQL, context, realtime and dictionaries. AI UI waits for server evaluation evidence; sidecar packaging/model selection/performance remain explicit later gates.

## 11. Dependencies on Other Teams

Use `scripts/mock_hub.py` immediately; real backend availability must not block client work. Server supplies contract parity, permission filtering, snapshots/tombstones, byte ranges, quiz timing/redaction and scored AI evaluation. Mobile shares behavior/contracts, not desktop implementation files. Lead owns contract changes, design-system changes, CI and approval. Do not import proposed server-spec wire changes before contracts/mock/tests merge. Real-Hub integration must repeat enrollment, reset, roles, media, quiz and queue tests before release.

## 12. Risks and Unknowns

Owners below are proposed team responsibilities; no personal commitment by Kiervy is claimed. All spikes are proposed for scheduling at the 2026-10-27 Sprint 0 review; their merge gates below must be met before the affected work ships. These are proposed dates, not promises of availability.

| Risk or spike | Owner | Decision date | Fallback |
|---|---|---|---|
| Draft alignment with partner and #161 | Desktop team; Kiervy review requested | 2026-10-27 proposed review; before Lead approval | Revise this draft; scaffold remains draft |
| Dynamic Hub permissions, production HTTP/WS, remote WS URL mapping | Desktop team + Lead for contract gaps | Before #40 merge; schedule at Sprint 0 review | Manual verified LAN address; no remote URL guessing |
| OS token persistence across Windows/Linux/macOS | Desktop team | Before #45 merge | Session-only token, cached local access; never plaintext |
| Single transaction, reset parent retention, receipts/revoked access | Desktop team + Lead/server for wire gaps | Before #48 implementation | Keep earlier read-only snapshots; block unsafe sync |
| Question bank answer-key conflict | Lead + desktop team | Before #31 implementation | No persisted answer-key bank |
| AI RAM, quality, sidecar lifecycle and package sizes | Desktop + server AI evaluation owner | Before #32/#67 implementation | Hub-only tutor, or unavailable state if Hub missing |

## 13. Commands and Verification

From repository root, with Python 3 and PyYAML installed:

```sh
python scripts/mock_hub.py
# Separate terminal, same root:
python scripts/verify_invariants.py
python -m unittest discover tests
```

After #29 merges, in `desktop/` (Node 22.13+ for node:sqlite tests, native Tauri prerequisites):

```sh
npm ci
npm run lint
npm test
npm run build
cargo check --manifest-path src-tauri/Cargo.toml
npm run tauri dev
```

Use the mock's documented seed accounts (`T-0001`, enrolled learner, joining learner; PIN from root guide), never real learner data. Start with authenticated classroom list, join/approve and pull. The shared tests start/stop a mock instance themselves; a manual instance uses REST 8080/WS 8081/UDP 8888. Mock supplies UDP; real mDNS discovery still needs a real advertised service.

| Deliverable | Required proof before its PR is complete |
|---|---|
| #29 shell | Native launch creates schema; Hub stopped gives cached/empty view; English/Filipino toggle; reopen DB and reject wrong-account rows |
| #5 discovery | Parse malformed/wrong-app beacons, deduplicate; UDP discovery around 3 seconds; mDNS on actual LAN; Wi-Fi reconnect and scanner shutdown |
| #40 connection | Manual address works without broadcast; invalid octets/port rejected; 401 reachability separated from login; stop mock gives banner; production HTTP/WS scope smoke test |
| #45 accounts | Teacher/learner mock login and registration; secure token absent from SQLite/localStorage/logs; relaunch with Hub stopped; switch accounts/Hubs with queued rows and prove no cross-context access |
| #46 join | Joining seed learner enters grouped code, sees PENDING, teacher approval unlocks without refresh; invalid/rejected/removed states and WS 4401/backoff |
| #47 roster | Create class, join from second session, accept/reject/remove with retained scores; forbidden role checks and offline read-only roster |
| #48 sync | Paging/tombstones and users; fail halfway through apply and kill/reopen: rows and cursor unchanged; reset with queued child/parent; duplicate receipts; rejected item; revoked access; new-member full snapshot; refresh under 1 second on mock |
| #105 settings | Toggle live and full-process relaunch; queued count warning before logout; offline logout clears local credential, preserves queued rows/context and reports unsent revocation |
| #123 class settings | Rename/archive appears after sync, archived class read-only; add/remove seeded second teacher; co-teacher cannot manage ownership; controls disabled offline |

Every feature also requires local loading/empty/offline states, both dictionary keys, keyboard/focus/contrast and desktop 40px controls (48px tabs, 56px quiz choices). Test service state/unit logic plus actual native boundaries; mocked SQL alone cannot prove atomicity or OS credentials.

Sprint 0 evidence (2026-10-26): `python scripts/verify_invariants.py` passed; `python -m unittest discover tests` passed all 66 tests (12.237 seconds), exercising the running localhost mock. The suite emitted socket ResourceWarnings; no test failed. Guide-prompt self-review checked all ten criteria, corrected an inaccurate grade-event identifier concern, clarified preferences/context isolation, and retained the explicitly gated questions in section 14. Desktop builds/native feature tests belong to #161 and later feature PRs; do not describe them as testing this docs-only branch. Physical WAN-unplugged LAN, real-Hub, Linux/macOS native runs and Lead/Kiervy approval are not established by a localhost mock run.

## 14. Open Questions

1. #40 specifies a “settings table”, but the canonical client schema has none. Proposed resolution is nonsecret `settings.ts` preferences (section 3); Lead must confirm or merge a contract change before adding a table. Define remote HTTP/WSS address pairing through a contracted configuration; do not assume port 8081 is publicly exposed by the tunnel.
2. #48 reset, new ACTIVE membership snapshots and revoked access need explicit server behavior. How do queued parent rows remain valid while inaccessible mirrored data is hidden? Confirm with fixture/contract tests before implementing a purge.
3. Push rejection reasons have no durable client column. Push receipts identify items by `id`; `EVENT_GRADE_CONFIRMED` provides `quiz_id` and `attempt_id`, so use those exact identifiers and recover full records through pull. Ask Lead for any genuinely missing schema/event extension. Permanent rejection must not become a silent retry storm.
4. #31 requests a local question bank with answers, while the higher rules forbid storing answer keys on clients. Keep authoring input transient; do not implement persisted keys without a reviewed contract/rule resolution.
5. WS reconnect mentions `last_event_id` without defining IDs on every event. Pull is the recovery path until an approved replay contract exists. Remote quiz resume must obtain authoritative timing; do not invent a new start timestamp.
6. Server spec proposals about USB export/authorization are not automatically accepted API changes. Follow current OpenAPI and raise discrepancies to Lead before #104.

Kiervy and Lead may change implementation choices in this draft. Any product/contract change still follows [team workflow](../../rules/team-workflow-and-prs.md); draft approval does not silently resolve these questions. Once reviewed, Lead records `Status: Approved` in the spec PR.

## 15. Invariants Checklist

These checks describe the proposed design, not proof that all features are implemented.

- [x] No internet or CDN dependency (fonts and icons come from `design-system/assets/`; only opt-in Hub tunnel transport is permitted)
- [x] Offline state defined for every screen family
- [x] English and Filipino strings required for all UI text
- [x] Touch targets at least 52dp on phones (56dp primary and quiz options), 40px buttons on desktop; desktop tabs/drawer 48px
- [x] `correct_answer` never stored on a client; persistent question bank conflict explicitly blocked
- [x] AI unavailable during an active quiz; drawer/buttons unmounted and local generation stopped
- [ ] Kiervy review and Lead approval recorded; affected open questions resolved before their implementation gates
