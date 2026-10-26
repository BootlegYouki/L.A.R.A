# Mobile Technical Spec

> Owner: Mobile team  |  Reviewer: Lead Developer  |  Status: Approved  |  Last updated: 2026-10-26
> This is not a PRD. Product behavior lives in `docs/PRD.md` and `contracts/`. This document says how this team will build its part. Never redefine behavior here; link to the source instead.
> Issues are written `[MOBILE x.y]`. Paths below are relative to `mobile/app/src/main/java/org/lara/app/` unless they start with `mobile/` or `contracts/`.

## 1. Scope and Non-Goals
**In scope:** the Android app `org.lara.app` with a Student graph and a Teacher graph (teachers have full parity, `AGENTS.md` 1.4). All 32 `[MOBILE x.y]` issues:

| Sprint | Issues | PRD |
|---|---|---|
| 1 | 1.1 scaffold, 1.2 discovery, 1.3 Room | PRD 3.1, 6.1, 7 |
| 2 | 2.1 nav shell, 2.2 auth, 2.3 join, 2.4 approvals, 2.5 sync, 2.6 settings, 2.7 class management | FR-1.1 to FR-1.4, PRD 6.2 |
| 3 | 3.1 stream and PDF, 3.2 camera, 3.3 video, 3.4 announce, 3.5 authoring, 3.6 review, 3.7 private comments and To-do | FR-2.x, FR-3.x |
| 4 | 4.1 quiz, 4.2 controller, 4.3 offline queue, 4.4 My Work, 4.5 quiz builder, 4.6 gradebook | FR-4.x, FR-5.1 |
| 5 | 5.1 router, 5.2 tutor sheet, 5.3 prompt and lockout, 5.4 JNI, 5.5 model download | PRD 5.x |
| 6 | 6.1 profiling, 6.2 UX audit, 6.3 remote Hub screen | PRD 3.2, 8 |

**Non-goals (must not be built):** Firebase, Play Services, CDN assets, telemetry; a model chosen by us (the AI evaluation decides); queued announcements, grading or class management (they need the Hub); writing to the Hub PC's USB from the phone; admin, backup or restore screens; learners posting to the stream; per-grade AI settings; dynamic color; tablet-specific layouts; Hilt, Ktor, Prisma or any other ORM.

## 2. Architecture
Clean Architecture with MVVM and unidirectional state. Single Gradle module `app`.

```text
 ui/screens (Composable + ViewModel)  --StateFlow<UiState>-->  Compose
        |  calls (suspend / Flow)
 domain/ (models, repository interfaces, use cases; pure Kotlin)
        |  implemented by
 data/repository (Room-first)  ---reads--->  data/local (Room: LaraDatabase, DAOs)
        |            \---writes outbox--->  data/local (sync_status = QUEUED_FOR_SYNC)
        |  network only from:
 data/sync (SyncRepository, workers)   data/remote (HubClient, RealtimeClient, discovery)
```

**Entry points:** `MainActivity` (single activity, `LaraTheme`, root `NavHost`), `LaraApp : Application` (builds `AppContainer`, schedules sync), `DeltaSyncWorker`, `HomeworkUploadWorker`, `QuizPushWorker`.

**Import rules (must):** `ui/*` imports `domain/*` only (plus `ui/*`); it must not import `data/local`, DAOs, entities, `data/remote` or OkHttp. ViewModels talk to repository interfaces. Only `data/repository` and `data/sync` touch DAOs. Only `data/remote` touches OkHttp, `NsdManager`, sockets. Only `ai/` touches `cpp/` through `LlamaJniBridge`. No `!!`, no `Color(0xFF...)` in screens.

## 3. Key Decisions
| Decision | Choice | Why | Rejected alternative |
|---|---|---|---|
| HTTP and WebSocket | OkHttp 4.12 for both | One dependency; `WebSocketListener` is what the README names; streams uploads from disk; interceptors for bearer token | Ktor Client (second engine, more methods and APK size on 3 to 4 GB phones) |
| JSON | `kotlinx.serialization` with `@SerialName` snake_case | `contracts/naming_rules.md` | Moshi, Gson (reflection, no compile-time check) |
| Dependency injection | Manual: one `AppContainer` in `LaraApp`, ViewModel factories | 32 issues, one module, no multi-module graph; no kapt/KSP cost, smaller build, nothing to learn for new developers | Hilt (annotation processing, generated code, little gain at this size) |
| Navigation | Navigation Compose 2.8 (type-safe routes) | Stable, matches `StudentNavGraph` and `TeacherNavGraph` names; documented in `mobile/docs/navigation.md` | Navigation 3 (newer, fewer examples; revisit only if Lead asks) |
| Local DB | Room with exported schema; `Flow` DAOs | `AGENTS.md` 6.1; schema test against `contracts/schema/client_offline.sql` | Any other ORM |
| Background work | WorkManager (`CoroutineWorker`, unique work names) | Survives Transsion and realme killers better than services (`mobile/AGENTS.md`) | Foreground service, `AlarmManager` |
| Token storage | AndroidKeystore AES-GCM key (non-exportable) wrapping the token in app-private `SharedPreferences`, class `TokenStore` | `androidx.security:security-crypto` is deprecated; Keystore is in the platform, works at minSdk 26, no Play Services | `EncryptedSharedPreferences` (deprecated, [MOBILE 2.2] text; see section 14), plain prefs |
| Discovery | `NsdManager` plus UDP `DatagramSocket` :8888 under a `MulticastLock`, manual field always shown | `networking-and-lan.md` sections 2 and 4.3 | JmDNS (extra library, battery) |
| Image compression | `ImageDecoder`/`BitmapFactory` with `inSampleSize` then `Bitmap.compress(JPEG, 75)` loop to under 800 KB | Platform only, bounded memory, no library | Coil/Glide transformations, Compressor library |
| Images on screen | Coil 2 (disk-only local files, no network; not named by any issue, Lead may veto) | Sampling and lifecycle handled | Manual `BitmapFactory` in Compose |
| PDF | `android.graphics.pdf.PdfRenderer`, one page bitmap at a time | Platform, no dependency | PdfBox/MuPDF (heap) |
| Video | Media3 ExoPlayer, `OkHttpDataSource` with bearer header, `SimpleCache` for Save for Home | 206 Range, header auth | MediaPlayer, `?token=` in URL (leaks into logs) |
| Time | `SystemClock.elapsedRealtime()` plus Hub offset from `EVENT_HELLO_ACK.server_time` | Wall clock can be changed by the learner | `System.currentTimeMillis()` |
| Cleartext | `network_security_config.xml` allows cleartext only for private LAN ranges; tunnel is `https`/`wss` | Hub speaks plain HTTP on :8080 | `usesCleartextTraffic="true"` globally |

## 4. File Map
```text
mobile/build.gradle.kts, mobile/app/build.gradle.kts   plugins, versions, Room schema export dir; no feature code
ui/theme/              LaraTheme and Color/Type/Shape copied from design-system/mobile/; no screens
ui/navigation/         StudentNavGraph, TeacherNavGraph, routes; no data access
ui/components/         shared Composables (BottomNavBar, CountdownPill, PrivateComments); no ViewModels
ui/screens/<feature>/  Screen + ViewModel + UiState per feature; no DAO, no OkHttp
domain/                models, repository interfaces, use cases; no Android, Room or OkHttp types
data/local/            LaraDatabase, entities/, dao/; no network
data/repository/       Room-first repository implementations; the only DAO users with data/sync
data/remote/           HubClient, AuthApi and other *Api, RealtimeClient, dto/, discovery/
data/sync/             SyncRepository, DeltaSyncWorker, HomeworkUploadWorker, QuizPushWorker
data/quiz/             QuizAttemptRepository (path named by [MOBILE 4.3])
data/media/            DownloadManager (Save for Home)
ai/                    HardwareChecker, InferenceRouter, SocraticPromptBuilder, QuizLockout, LlamaJniBridge, ModelDownloader
cpp/                   llama-jni.cpp, CMakeLists.txt (arm64-v8a only)
res/values, res/values-tl   strings in parity; res/font Nunito; Phosphor drawables bundled
mobile/docs/           one doc per module (see mobile/docs/README.md)
```

## 5. Names (one name per thing)
| Name | Kind | Responsibility |
|---|---|---|
| `AppContainer` | class | Builds and holds singletons (database, `HubClient`, repositories); the DI |
| `LaraDatabase` | Room DB | 16 entities from `client_offline.sql` |
| `HubEndpoint` / `HubEndpointStore` | model / class | Chosen base URL (LAN `ip:port` or remote `https` host) saved in prefs |
| `HubClient` | class | The single OkHttp client: base URL, bearer interceptor, error envelope to `HubError(code, status)`, timeouts |
| `TokenStore` | class | Encrypted token and `AuthSession` user id |
| `AuthApi` and one `<Feature>Api` per route group | classes | Typed REST calls over `HubClient`; DTOs in `data/remote/dto/` |
| `RealtimeClient` | class | WebSocket, `EVENT_HELLO`, backoff, `SharedFlow<HubEvent>` |
| `HubDiscovery` (`NsdScanner`, `UdpScanner`, `MulticastLockManager`) | classes | `StateFlow<List<DiscoveredHub>>` |
| `SyncRepository` | class | `pull()`, `pushQueued()`; the only code that writes the cursor |
| `DeltaSyncWorker` | worker | Calls `SyncRepository.pull()` then `pushQueued()` |
| `QuizAttemptRepository` | class | Attempt rows, answers saved per change, `hasInProgressAttempt(): Flow<Boolean>`, queue and finish |
| `QuizSessionManager` | class | Countdown from `started_at` and clock offset; emits remaining time and expiry |
| `QuizLockout` | class | Exposes `Flow<Boolean>` from `hasInProgressAttempt()`; gate for any AI UI or call |
| `InferenceRouter`, `HardwareChecker` | classes | RAM gate and Hub or local choice |
| `SyncScheduler` | class | Enqueues unique one-time and periodic WorkManager work; the only caller of `WorkManager` for sync |
| `ClassroomRepository`, `EnrollmentRepository`, `ClassManagementRepository`, `StreamRepository`, `PrivateCommentRepository`, `GradingRepository`, `GradebookRepository`, `QuizRepository`, `QuizControllerRepository`, `QuizAuthoringRepository`, `ClassworkAuthoringRepository` | repository interfaces in `domain/`, impls in `data/repository/` | One per feature area named in section 6; created in the step that first needs it (section 10) |
| `UiState` | sealed interface per screen | `Loading`, `Content`, `Empty`, `Offline` as needed |
| `sync_state`, `QUEUED_FOR_SYNC`, `SYNCED`, `REJECTED` | table, enum values | As in `contracts/schema/client_offline.sql` |

## 6. Contract Usage
Unreachable Hub means: reads come from Room; writes marked "outbox" are queued; writes marked "needs Hub" are disabled with a helper line (never queued). `HubClient` timeouts: connect 3 s, read 15 s, write 30 s, uploads 120 s per call (a retry resumes via WorkManager). Every non-2xx body is `{"error":{"code","message"}}`; `401 UNAUTHORIZED` always clears the session and goes to login, `403 FORBIDDEN` shows a kind message, unknown codes show a generic kind message and are logged without payload. Per-route codes below come from `contracts/openapi.yaml` (known list at line 1327); gaps are in section 14.

| Contract route or event | Our module | On error codes | On timeout or Hub unreachable |
|---|---|---|---|
| `POST /api/auth/login`, `register` | `AuthApi` | login 401 wrong-PIN message; register 409 "already registered" | Show "cannot reach the Hub", keep manual IP; cached session opens offline |
| `POST /api/auth/logout` | `AuthApi` (Settings) | any: still clear local token after queued-row check | Clear locally, ignore failure |
| `GET/POST /api/classrooms` | `ClassroomRepository` | 403 | Room data; create is needs Hub |
| `POST /api/classrooms/join` | `EnrollmentRepository` | 404 `CLASS_CODE_INVALID` bilingual message | Disabled with helper line |
| `PATCH /api/classrooms/{id}`, `.../remove`, `.../teachers`, `DELETE .../teachers/{teacher_id}` | `ClassManagementRepository` | 403 (co-teacher owner-only) | Needs Hub |
| `GET .../roster`, `POST .../approve`, `.../reject` | `RosterViewModel` via `ClassManagementRepository` | 403; optimistic update rolled back | Cached roster; actions need Hub |
| `POST /api/sync/pull` | `SyncRepository.pull` | 401 relogin; other: roll back, keep cursor, retry by WorkManager backoff | Keep cursor, schedule retry on connectivity |
| `POST /api/sync/push` | `SyncRepository.pushQueued` | per-item receipt `SYNCED` or `REJECTED(reason)`; request error: rows stay queued | Rows stay `QUEUED_FOR_SYNC` |
| `POST /api/topics`, `PATCH/DELETE /api/topics/{id}` | `ClassworkAuthoringRepository` | 403 | Needs Hub |
| `POST /api/announcements`, `PATCH/DELETE .../{id}` | `ClassworkAuthoringRepository` | 403 | Needs Hub (not queued, [MOBILE 3.4]) |
| `GET/POST /api/announcements/{id}/comments` | `StreamRepository` | 403 `COMMENTS_DISABLED` | POST is outbox (`announcement_comments`); GET from Room |
| `POST /api/materials` | `ClassworkAuthoringRepository` + upload worker | 413 `FILE_TOO_LARGE` (also pre-check 250 MB) | WorkManager retry; progress kept |
| `PATCH/DELETE /api/materials/{id}` | `ClassworkAuthoringRepository` | 403 | Needs Hub |
| `GET /api/materials/{id}/download` | `DownloadManager` | 404 mark file gone | Use `local_file_path` copy |
| `GET /api/materials/{id}/chunks` | via pull (`material_chunks`) | 404 no text: say tutor unavailable for the file | Room |
| `GET /api/materials/{id}/stream` | Media3 `OkHttpDataSource` | 401 relogin; 416 restart range | Player offline state; downloaded copy plays |
| `GET /api/model`, `GET /api/model/file` | `ModelDownloader` | 404 hide card; 416 restart from 0; sha256 mismatch retry once | Resume with `Range` from `.part` length |
| `POST /api/assignments`, `PATCH/DELETE .../{id}` | `ClassworkAuthoringRepository` | 403 | Needs Hub |
| `POST /api/assignments/{id}/submit` | `HomeworkUploadWorker` | 413 `FILE_TOO_LARGE` recompress; 403 | Row stays `QUEUED_FOR_SYNC`, same `submission_id` on every retry |
| `GET/POST /api/assignments/{id}/private-comments` | `PrivateCommentRepository` | 403 | POST is outbox via sync push; GET from Room |
| `GET .../{id}/submissions`, `GET /api/submissions/{id}/file` | `GradingRepository` | 403 kind message | Cached list read-only |
| `PATCH /api/submissions/{id}` | `GradingRepository` | 422 or 400 points above max (also blocked in UI) | Needs Hub |
| `POST /api/quizzes`, `PATCH /api/quizzes/{id}` | `QuizAuthoringRepository` | 409 `QUIZ_NOT_DRAFT` read-only message | Unsaved edits live only in the ViewModel (memory); save needs Hub |
| `GET /api/quizzes/active`, `GET /api/quizzes/{id}` | `QuizRepository` | 404 none active (not an error screen) | Room metadata; questions fetched once on start and cached |
| `POST /api/quizzes/{id}/start`, `.../close` | `QuizControllerRepository` | 403; 409 undocumented (section 14) | Needs Hub, banner and polling |
| `POST /api/quizzes/{id}/begin` | `QuizAttemptRepository` | 403 `FORBIDDEN` | Cannot begin offline; if already begun, continue local |
| `POST /api/quizzes/{id}/submit` | `QuizAttemptRepository`, `QuizPushWorker` | 422 `TIME_LIMIT_EXCEEDED`: keep row `REJECTED`, kind message | Queue as `QUEUED_FOR_SYNC` |
| `GET /api/quizzes/{id}/results` | `GradebookRepository` | 403 | Cached results |
| `POST /api/export/class-record` | `GradebookRepository` (download to phone, no `target_path`) | 403 | Needs Hub |
| `EVENT_HELLO` / `EVENT_HELLO_ACK` | `RealtimeClient` | close 4401: relogin; `hub_id` or `sync_epoch` mismatch: `SyncRepository.pull` | Backoff 1, 2, 4, max 10 s, no dialog |
| `EVENT_JOIN_REQUEST` (teacher), `EVENT_JOIN_APPROVAL` | `RealtimeClient` to repositories | n/a | Missed: next pull or `GET /api/classrooms` |
| `EVENT_ANNOUNCEMENT_PUSH` | `RealtimeClient` then enqueue sync | n/a | Missed: periodic sync |
| `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED` | `QuizSessionManager` | n/a | Missed: `GET /api/quizzes/active`; local timer continues |
| `EVENT_GRADE_CONFIRMED` | `QuizAttemptRepository` | n/a | Next pull carries score (null while held) |
| `EVENT_PRESENCE` (teacher) | `QuizControllerRepository` | n/a | Polling `GET /api/quizzes/{id}/results` |
| `EVENT_QUIZ_SUBMIT` | not used (REST submit only) | n/a | n/a |
| `EVENT_AI_CHAT_REQUEST`, `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM` | `InferenceRouter` (Hub path) | `EVENT_ERROR` `QUIZ_IN_PROGRESS`, `AI_QUEUE_FULL` keep typed text, `AI_NOT_AVAILABLE` show unavailable state | Socket drop: offline state, keep conversation so far |

## 7. Data and Offline Behavior
Source of truth: `contracts/schema/client_offline.sql` and `rules/database-and-sync.md`. Details in `mobile/docs/room_schema.md` and `sync_worker.md`.

* **Storage:** one entity per table in `data/local/entities/`, same names and nullability; `sync_state` rows `hub_id`, `sync_epoch`, `cursor`, `current_user_id`. A schema test fails on drift. Never a `correct_answer`, `pin_hash`, other LRN or server `file_path` field in any entity or DTO.
* **`sync_status`:** a row is created `QUEUED_FOR_SYNC` on the client with a UUID v4 generated on the device. It becomes `SYNCED` only from a push receipt (or submit response). `REJECTED` (with reason) stays visible and is never deleted; the UI shows a kind explanation. Rows are never deleted by sign-out; sign-out warns with the queued count.
* **Cursor:** integer `next_cursor` from `SyncRepository.pull`, stored in `sync_state` in the same `@Transaction` as the applied rows, tombstones and `users`. On any exception the transaction rolls back and the old cursor stays. Loop while `has_more`. `server_time` only feeds the clock offset.
* **Reset:** if the pull response has `reset: true` (hub_id or sync_epoch differs, also reported by `EVENT_HELLO_ACK`), the same transaction first deletes every mirrored table row except rows with `sync_status = 'QUEUED_FOR_SYNC'`, then applies the response and stores new `hub_id`, `sync_epoch`, cursor.
* **Triggers:** unique periodic work (15 min, network connected, not low battery), one-time work on `NetworkCallback` available, on `EVENT_ANNOUNCEMENT_PUSH`, after login, after any outbox write.
* **Remote/Suspension Mode** (`AGENTS.md` 3.5, `rules/networking-and-lan.md` section 5): the client tries LAN discovery first; only when it fails and a remote URL is saved does `HubEndpointStore` return the `https` host. Same token, same contract, same cursor. Discovery is never attempted over the tunnel. Over the tunnel `QuizSessionManager` does not rely on `EVENT_QUIZ_START`: it anchors to `started_at` from `POST /api/quizzes/{id}/begin` or `GET /api/quizzes/active` and counts down on `elapsedRealtime()`.

| Screen group | No Hub behavior |
|---|---|
| Discovery and connection | Shows found Hubs, manual field, saved remote URL; never a blocking dialog |
| Login | Cached session opens Room home; new login needs Hub and says so kindly |
| Class list, Stream, Classwork, People, To-do, Due soon, My Work | Room only; offline banner |
| PDF, chunks, downloaded video | Local file; not downloaded: "available when connected to the classroom Hub" |
| Join class, class management, post announcement, authoring, grading, quiz start/close | Action disabled with helper line (not queued) |
| Comments, private comments, homework photo | Saved `QUEUED_FOR_SYNC` with queued badge |
| Quiz (learner) | Continues from local timer and Room answers; finish queues |
| Tutor | Hub unreachable and no local model: "L.A.R.A AI is available when connected to the classroom Hub"; local model works fully offline |
| Gradebook, results | Cached from Room |

## 8. Code Patterns
**Offline-first read:**
```kotlin
class StreamRepositoryImpl(private val dao: AnnouncementDao) : StreamRepository {
    override fun stream(classroomId: String): Flow<List<Announcement>> =
        dao.observeByClassroom(classroomId).map { rows -> rows.map { it.toDomain() } }
}
```
**Outbox write (client id, no network call):**
```kotlin
suspend fun addComment(announcementId: String, body: String) {
    val id = UUID.randomUUID().toString()
    dao.insert(CommentEntity(id, announcementId, body, syncStatus = "QUEUED_FOR_SYNC", createdAt = clock.nowMs()))
    syncScheduler.enqueueNow()   // unique one-time work; marks SYNCED only from the receipt
}
```
**Sync transaction:**
```kotlin
@Transaction
suspend fun applyPull(r: SyncPullDto) {
    if (r.reset) wipeMirroredKeepQueued()
    upsertAll(r); deleteTombstones(r.deleted)
    stateDao.put("hub_id", r.hubId); stateDao.put("sync_epoch", r.syncEpoch.toString())
    stateDao.put("cursor", r.nextCursor.toString())   // same transaction as the data
}
```
**Quiz lockout gate (the chat is not composed, not hidden):**
```kotlin
@Composable
fun TutorEntry(lockout: QuizLockout) {
    val locked by lockout.active.collectAsStateWithLifecycle(initialValue = true)
    if (locked) DisabledTutorTab(reason = R.string.tutor_locked_quiz) else SocraticBottomSheetHost()
}
```
`InferenceRouter` also checks `lockout.active.first()` before any request and handles `QUIZ_IN_PROGRESS`.

**Screen UiState:**
```kotlin
sealed interface StreamUiState {
    data object Loading : StreamUiState
    data class Content(val items: List<Announcement>, val offline: Boolean) : StreamUiState
    data object Empty : StreamUiState
}
// ViewModel: repository.stream(id).map { if (it.isEmpty()) Empty else Content(it, !hub.reachable.value) }
//            .onStart { emit(Loading) }.stateIn(viewModelScope, WhileSubscribed(5_000), Loading)
```

## 9. Pitfalls
| Trap | Fix |
|---|---|
| Transsion and realme kill background work | WorkManager only; test with vendor battery optimization on; document the "allow auto-start" steps in `mobile/docs/sync_worker.md` |
| UDP beacons dropped by Wi-Fi power saving | Hold `WifiManager.createMulticastLock("lara_discovery_lock")` only while the discovery screen scans; release in `finally` |
| Cleartext HTTP blocked on API 28+ | `network_security_config.xml` with `cleartextTrafficPermitted` for 10.x, 172.16-31.x, 192.168.x, loopback and emulator 10.0.2.2; remote host is https |
| Full-size bitmaps cause OOM (heap under 250 MB) | `inSampleSize` decode; one PDF page at a time; Coil with size hints; never `readBytes()` a file; stream uploads |
| `NsdManager` resolve fails with "already active" and listener leaks | Serialize `resolveService`, stop discovery on screen exit, retry once; the UDP beacon and manual IP are the fallback |
| Router client isolation blocks discovery | Manual IP field always visible; IPv4 regex from [MOBILE 1.2] |
| Device clock wrong or changed | Offset from `EVENT_HELLO_ACK.server_time`, countdown on `elapsedRealtime()`, send `started_at`/`submitted_at` from synchronized time |
| WebSocket reconnect storm or blocking error | Backoff 1, 2, 4, max 10 s; surface only a status pill |
| Android kills app mid-quiz | Save answers to Room on every change; rebuild timer from stored `started_at` |
| Room cannot express CHECK or partial indexes | Enforce in repository (for example one attempt per quiz) |
| `totalMem` reads below printed RAM | Measure real phones and record the gate in `mobile/docs/ai_routing.md` ([MOBILE 5.1]) |
| Camera EXIF rotation | Apply EXIF orientation before compress; delete full-size original after the compressed copy exists |
| Tokens and answer keys in logs | No logging of tokens, PINs, LRNs, bodies; teacher quiz draft never logged |
| Heap while downloading models, videos | Stream with fixed buffer; verify SHA-256 by streaming |

## 10. Build Order
One open PR at a time. A later step imports the named piece; it never re-creates it.

| Step | Issue | Builds | Needs first |
|---|---|---|---|
| 1 | [MOBILE 1.1] | `mobile/build.gradle.kts`, `mobile/app/build.gradle.kts`, `ui/theme/*`, `MainActivity`, `LaraApp`, `AppContainer`, `network_security_config.xml`, strings files | Sprint 0 |
| 2 | [MOBILE 1.2] | `data/remote/discovery/*`, `HubEndpoint`, `HubEndpointStore`, discovery screen | 1.1 |
| 3 | [MOBILE 1.3] | `LaraDatabase`, entities, DAOs, `domain/` repository interfaces (incl. `hasInProgressAttempt()`), repositories, DTOs, schema test | 1.1 |
| 4 | [MOBILE 2.2] | `HubClient`, `TokenStore`, `AuthApi`, auth screens, role routing | 1.1, 1.2, 1.3 |
| 5 | [MOBILE 2.1] | `ui/navigation/*`, `BottomNavBar`, both nav graphs | 1.1, 2.2 |
| 6 | [MOBILE 2.5] | `SyncRepository`, `DeltaSyncWorker`, `SyncScheduler` | 1.3, 2.2 (needs `HubClient`) |
| 7 | [MOBILE 2.3] | `RealtimeClient`, join screen | 2.1, 2.2, 2.5 |
| 8 | [MOBILE 2.4] | `RosterViewModel`, `ApprovalSheet` | 2.3 (`RealtimeClient`) |
| 9 | [MOBILE 2.6] | settings screens | 2.1, 2.2 |
| 10 | [MOBILE 2.7] | `ui/screens/teacher/classes/*`, `ClassManagementRepository` | 2.4, 2.5 |
| 11 | [MOBILE 3.1] | stream and classwork screens, PDF reader | 2.5 |
| 12 | [MOBILE 3.2] | camera screen, `HomeworkUploadWorker` | 2.5 |
| 13 | [MOBILE 3.3] | video player, `DownloadManager` | 2.5 |
| 14 | [MOBILE 3.4] | `PostAnnouncement` | 2.1 |
| 15 | [MOBILE 3.5] | `ClassworkAuthoringRepository`, authoring screens | 3.1, 3.4 |
| 16 | [MOBILE 3.6] | review and grading screens, `GradingRepository` | 3.5 |
| 17 | [MOBILE 3.7] | `PrivateComments`, To-do, Due soon | 3.2, 3.6 |
| 18 | [MOBILE 4.1] | quiz screens, `QuizSessionManager`, `QuizAttemptRepository` | 2.5 |
| 19 | [MOBILE 4.3] | queue and flush in `QuizAttemptRepository`, `QuizPushWorker` | 4.1, 2.5 |
| 20 | [MOBILE 4.2] | `QuizControllerScreen`, `QuizControllerRepository` | 2.1 |
| 21 | [MOBILE 4.4] | My Work screens | 3.2, 4.1 |
| 22 | [MOBILE 4.5] | quiz builder (drafts live on the Hub as `DRAFT` quizzes) | 4.2 |
| 23 | [MOBILE 4.6] | grades screens | 4.2, 3.6 |
| 24 | [MOBILE 5.1] | `HardwareChecker`, `InferenceRouter` | 2.5 |
| 25 | [MOBILE 5.3] | `SocraticPromptBuilder`, `QuizLockout` | 5.1, 4.1 |
| 26 | [MOBILE 5.4] | `LlamaJniBridge`, `cpp/*` | 5.1 |
| 27 | [MOBILE 5.5] | `ModelDownloader`, `ModelDownloadCard` | 5.1 |
| 28 | [MOBILE 5.2] | `SocraticBottomSheet` | 5.1, 5.3 |
| 29 | [MOBILE 6.1] | profiling report | Sprint 5 |
| 30 | [MOBILE 6.2] | UX audit fixes | Sprint 5 |
| 31 | [MOBILE 6.3] | remote Hub screen in discovery module | 1.2, 2.2 |

Order deviates from numbers in three places (2.2 before 2.1, 4.3 before 4.2, 5.3 before 5.2); see section 14. Sprints 3 to 6 are rougher: open decisions are in sections 12 and 14.

## 11. Dependencies on Other Teams
Default: build against `python3 scripts/mock_hub.py`; switch on integration day.
| Need | From | When |
|---|---|---|
| Teacher routes (classwork, quizzes, grading) real behavior | [SERVER 2.5], [SERVER 3.4], [SERVER 3.5], [SERVER 4.5] | Sprints 2 to 4; mock hub until merged |
| AI evaluation results (model, device model) | [SERVER 1.5] | Before [MOBILE 5.2] (Dec 2026) |
| Model file endpoint | [SERVER 5.4] | Before [MOBILE 5.5] |
| Tunnel hostname and WS exposure | [SERVER 6.2] | Before [MOBILE 6.3] |
| Lead decisions in section 14 | Lead Developer | Before the step that needs each |

## 12. Risks and Unknowns
| Risk or spike | Owner | Decision date | Fallback |
|---|---|---|---|
| Heap under 250 MB on 3 to 4 GB phones (PDF, video, upload) | Mobile team | 2026-11-20 spike with [MOBILE 3.1] on a real phone | Smaller sampling, page lists, drop Coil for manual decode |
| Transsion and realme kill WorkManager | Mobile team | 2026-11-27 on Infinix and realme with [MOBILE 2.5] | Expedited work plus visible "Sync now"; vendor auto-start guide |
| NSD and UDP discovery reliability | Mobile team | 2026-11-13 (end of [MOBILE 1.2]) | Manual IP prominent; remembered Hub |
| 6 GB RAM gate value from `totalMem` | Mobile team | 2026-12-04 with [MOBILE 5.1] | Hub-only for all phones |
| llama.cpp JNI size and speed | Mobile team | 2026-12-18 | Ship Hub-only AI; JNI behind a flag |
| Token storage via Keystore on old Android 8 devices | Mobile team | 2026-11-13 | Fall back to app-private prefs without wrapping (flag to Lead) |

## 13. Commands and Verification
**Run (from `mobile/`):** `./gradlew test lint`, `./gradlew assembleDebug`. **From repo root:** `python3 scripts/mock_hub.py` (emulator host `10.0.2.2`, else PC LAN IP), `python3 scripts/verify_invariants.py`, `python3 -m unittest discover tests`. Seed accounts (PIN `1234`): `T-0001` (class code `K7M4QX`), `123456789012`, `123456789013`, `ADMIN-0001`. Quick check that the mock hub is up:
`curl -s -X POST localhost:8080/api/auth/login -H 'content-type: application/json' -d '{"lrn_or_id":"T-0001","pin":"1234"}'` must return a token.

**Unit tests:** DTO round trip, schema test, `applyPull` rollback, reset keeps queued rows, countdown math, lockout gate, prompt builder. **Instrumented against mock hub:** sync, discovery, upload, quiz offline. **Integration day:** same flows on the real Hub with WAN unplugged.

| Item | Proof |
|---|---|
| [MOBILE 1.1] | `./gradlew assembleDebug lint` passes; app launches on emulator; theme has no `Color(0xFF` in `ui/screens` (grep) |
| [MOBILE 1.2] | Mock hub running: Hub appears in under 3 s on the same Wi-Fi; manual `10.0.2.2:8080` connects; log shows lock acquired and released |
| [MOBILE 1.3] | `./gradlew test` includes the schema test (fails when a column is removed); airplane mode repository reads work |
| [MOBILE 2.2] | Log in as `123456789012` and `T-0001`; wrong PIN shows the bilingual message; relaunch in airplane mode opens cached home |
| [MOBILE 2.1] | Teacher and learner sessions show different graphs; AI tab disabled when an `IN_PROGRESS` attempt exists |
| [MOBILE 2.5] | Instrumented test: kill the mock hub mid-pull, DB and cursor unchanged; restart, announcements sync in under 1 s |
| [MOBILE 2.3] | `123456789013` joins with `K7M-4QX`, teacher approves, class unlocks without refresh |
| [MOBILE 2.4] | Join request opens the sheet on the teacher phone; Accept unlocks the learner |
| [MOBILE 2.6] | Language switch applies without restart and survives relaunch; sign out with a queued row warns first |
| [MOBILE 2.7] | Class created on phone is joinable by code; removed learner loses the class after sync |

## 14. Open Questions
Answered by the Lead on 2026-10-26 (decisions, now binding for this spec):
1. **Yes.** Build [MOBILE 2.2] before [MOBILE 2.1].
2. **Yes.** `HubEndpoint`/`HubEndpointStore` are created in [MOBILE 1.2]; `HubClient` and `TokenStore` in [MOBILE 2.2].
3. **No client-side draft store.** `POST /api/quizzes` already creates a `DRAFT` quiz on the Hub and `PATCH /api/quizzes/{id}` edits it while it is `DRAFT`, so the draft lives on the Hub. The builder keeps unsaved edits only in ViewModel memory, never in Room, files or prefs, and saving needs the Hub. `correct_answer` from `GET /api/quizzes/{id}` (owning teacher) is held in memory only and never logged.
4. **Yes.** `hasInProgressAttempt()` lands in [MOBILE 1.3]; build [MOBILE 5.3] before [MOBILE 5.2].
5. **Yes.** `QuizPushWorker` is thin expedited work calling `SyncRepository.pushQueued()`; [MOBILE 4.1] creates `QuizAttemptRepository`.
6. **Accepted.** `data/quiz/` and `data/media/` are sub-packages of `data/`; `ModelDownloader` stays in `ai/`. [MOBILE 6.1] writes its report to `mobile/docs/budget_device_benchmarks.md` (team folder), not `docs/benchmarks/`.
7. **Yes.** Keystore-wrapped `TokenStore`, not `EncryptedSharedPreferences`.
8. **16 tables.** The contract wins over the issue text.
12. **Accepted.** No `last_event_id`; the client relies on pull.
13. **Yes.** The PENDING card comes from the join response, `GET /api/classrooms` and `EVENT_JOIN_APPROVAL`.
15. **Navigation Compose 2.8** (section 3). AI tab placement on phones is decided in [MOBILE 5.2] from `design-system/design-system.md` 9.16.

9. **Remote URL (tentative).** There is no Cloudflare plan yet, so [MOBILE 6.3] stays blocked until [SERVER 6.2] adds the remote address format to the contract. Working assumption until then: one `https://` hostname with REST at `/api/...` and the WebSocket at `wss://<host>/ws`. `HubEndpoint` must keep the REST base and the WebSocket URL as two fields (LAN: `http://<ip>:8080` and `ws://<ip>:8081`) so the remote form drops in without changing callers. Sprints 1 to 5 build LAN only; the IPv4 regex applies to LAN entries only.
10. **Phone export: "Download to phone".** [MOBILE 4.6] calls `POST /api/export/class-record` without `target_path` and saves the returned `.xlsx`/`.csv` to the phone's Downloads (MediaStore), with a share action. Never send `target_path` from the phone; writing to the Hub PC's USB stays on the Hub window (FR-5.2). Needs the Hub; disabled with a helper line when unreachable.
11. **Error codes.** Until a `contract-change` adds per-route codes (to be merged before Sprint 4, owned by the Lead), clients map by HTTP status plus the known codes and show a kind generic message for anything else. No mobile step before [MOBILE 4.1] is blocked.

Still open (answer before the step named):
14. [MOBILE 5.2] waits for [SERVER 1.5] results; the 6 GB gate is measured in [MOBILE 5.1].

## 15. Invariants Checklist
- [x] No internet or CDN dependency (fonts and icons bundled; the only off-LAN path is the Hub's tunnel URL)
- [x] Offline state defined for every screen (section 7)
- [x] English and Filipino strings for all UI text (`values/` and `values-tl/` parity)
- [x] Touch targets at least 52dp (56dp primary and quiz options)
- [x] `correct_answer` never stored on a client (Room mirror never has it; the teacher builder holds it in memory only, section 14 question 3)
- [x] AI unavailable during an active quiz (`QuizLockout`, section 8)
