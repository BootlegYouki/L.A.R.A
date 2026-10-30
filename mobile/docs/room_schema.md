# Room offline mirror (MOBILE 1.3, Issue #41)

The local database is the source of truth for cached classroom reads. No DAO or
Room repository depends on a Hub client, network state, discovery, or a token.
This change does not connect new classroom screens or implement synchronization.

## Contract and table count

`contracts/schema/client_offline.sql` is authoritative. It currently has **16**
tables. Issue #41 and the implementation brief still say 14; the approved
`TECH_SPEC.md` section 14, decision 8 resolves this in favor of 16. The user
confirmed proceeding with the necessary contract scope during implementation.
The parity test explicitly requires 16 and compares against the SQL itself.

| SQL table | Entity |
| --- | --- |
| users | UserEntity |
| classrooms | ClassroomEntity |
| enrollments | EnrollmentEntity |
| announcements | AnnouncementEntity |
| announcement_comments | AnnouncementCommentEntity |
| materials | MaterialEntity |
| material_chunks | MaterialChunkEntity |
| assignments | AssignmentEntity |
| assignment_submissions | AssignmentSubmissionEntity |
| quizzes | QuizEntity |
| quiz_questions | QuizQuestionEntity |
| quiz_attempts | QuizAttemptEntity |
| ai_chat_messages | AiChatMessageEntity |
| topics | TopicEntity |
| private_comments | PrivateCommentEntity |
| sync_state | SyncStateEntity |

Column definitions remain in the SQL contract. Entities explicitly map snake_case
columns, INTEGER timestamps to Long, 0/1 flags to Boolean, nullable values, primary
keys, foreign keys, defaults, UNIQUE constraints and named indexes with ASC/DESC
ordering. Room expresses SQL UNIQUE clauses as equivalent unique indexes.

Room cannot declare CHECK constraints through entity annotations. Entity
constructor checks enforce the contract's enum domains and the submitted-at
requirement on every typed write and copy, including repository writes. Boolean
properties restrict flags to 0/1. Raw SQLite writes bypass this protection and
must not be used in production. No extra schema columns or indexes are added.
Room warns about three unindexed foreign keys already present in the contract
(quizzes, topics, private_comments); adding indexes requires a contract change.

## Entry points and data flow

`data/local/LaraDatabase.kt` registers all entities at version 1, exports the
schema, uses private `lara.db` storage and WAL, and exposes seven DAOs. It has no
main-thread queries or destructive migration fallback. The existing manual
`LaraApp.AppContainer` owns one lazy database and the repository instances; no
second singleton or dependency injection framework is introduced.

| DAO | Responsibility |
| --- | --- |
| ClassroomDao | Observable unarchived class list |
| StreamDao | Observable announcements and comments; insert local comment |
| ClassworkDao | Observable topics, materials, assignments and ordered chunks |
| RosterDao | Transactional enrollment/user relation; user may arrive later |
| QuizDao | Quiz metadata, ordered questions, per-learner attempt and lockout |
| CacheDao | Atomic typed cache batches and guarded physical deletions |
| SyncStateDao | Observe/get/upsert client key/value state |

`domain/ClassroomRepositories.kt` defines ClassroomRepository, StreamRepository,
ClassworkRepository, EnrollmentRepository and QuizRepository. Implementations
live in `data/repository/RoomRepositories.kt`. Domain models contain no Android,
Room or transport types; entity mappings are separate.

Reads follow Room -> DAO Flow -> domain mapping -> StateFlow -> future ViewModel.
Pass a ViewModel's `viewModelScope` when creating its observer, and retain the
returned StateFlow rather than recreating it on recomposition. Sharing uses
WhileSubscribed(5000) and an explicit Cached.Loading initial value. A loaded empty
list is Cached.Content(emptyList()), and a missing attempt is Content(null).
Cancellation of the supplied scope cancels sharing; mutable flows are never
exposed. UI-specific empty/offline/error states remain ViewModel responsibilities.

StreamRepository.addComment validates nonblank text, creates a UUID and writes
QUEUED_FOR_SYNC locally. It returns only after the insert completes. The network
scheduler is deliberately deferred to MOBILE 2.5. Other future outbox workflows
and the full QuizAttemptRepository remain in their assigned issues.

## Batch integrity and future sync

CacheDao.upsertBatch runs parent-before-child writes inside one transaction.
Upsert uses INSERT/UPDATE semantics, avoiding REPLACE's cascading deletion.
A pending local comment, private comment, submission or attempt wins over an
incoming cache row with the same ID. A material refresh retains an existing
local_file_path. Secondary unique-key conflicts are explicitly checked and fail
the transaction: Room 2.6's Upsert can otherwise swallow a conflict when the
fallback UPDATE matches no primary key. Room enforces foreign keys.

CacheDao.deleteBatch handles only deletable material/topic/announcement/comment
records. It never deletes users, classrooms, attempts or submissions. Queued
comments are protected, including their parent announcements. Material chunks
are removed with their material. Reset, removal cleanup, receipt application,
parent retention for queued work, and tombstone dispatch belong to MOBILE 2.5;
do not implement reset by indiscriminately deleting parent rows.

A future sync repository must wrap all applied batches, tombstones and state
writes in database.withTransaction. CacheBatch can carry sync_state rows for an
atomic batch, but there is no pull loop, reset policy, REST client, WorkManager
scheduler or push-receipt handler here. Only the future sync repository should
advance the cursor. An IN_PROGRESS attempt remains a lockout even offline.

## Wire DTOs and privacy

`data/remote/dto/SyncDtos.kt` uses kotlinx.serialization and explicit SerialName
for the OpenAPI pull request/response, public users, metadata, tombstones, push
items and per-item receipts. Optional collections default to empty; optional
metadata and held scores preserve null. The quiz_attempts pull array contains
GradeReceiptDto, as the contract specifies, rather than imaginary full attempts.
Client-only paths, sync flags and state are never wire fields. PublicUser has no
wire timestamps; its mapping requires explicit caller-supplied timestamps.
Announcement mapping uses the SQL allow_comments default when omitted. Push
comment mapping requires a queued row and omits local author/sync bookkeeping.

Wire metadata does not contain every local SQL column. This issue does not invent
those fields or silently assemble incomplete entities: additional mappings need
explicit cache/pull context in the later sync implementation. Submission payloads
contain no local or server path. Teacher quiz authoring payloads are out of scope.

Entities and DTOs contain no answer-key or PIN fields. users.lrn_or_id is nullable;
a plain PublicUser name does not require an LRN. The Hub must authorize and redact
who can receive identifiers and private records; local storage cannot substitute
for server authorization. No learner records or payloads are logged. Tests use
synthetic values only.

## Schema export, migrations and tests

The existing Kotlin and Room versions are retained (2.0.21 and 2.6.1). Kapt and the
matching Room compiler are required to generate DAOs and schema JSON; no runtime
DI framework is added. Kapt reports its Kotlin 1.9 stub-language fallback.
The schema output is `app/schemas/org.lara.app.data.local.LaraDatabase/1.json`.
Check it into version control. Future schema changes must increase the version,
provide tested nondestructive migrations, and retain earlier exported versions.
No migration is needed before this first database version.

From mobile/ on Windows (omit the local Gradle-home flag on CI):

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home --no-daemon assembleDebug test lint
.\gradlew.bat --gradle-user-home .gradle-user-home --no-daemon connectedDebugAndroidTest
```

From the repository root:

```powershell
$env:PYTHONUTF8 = '1'
python scripts/verify_invariants.py
python -m unittest discover tests
```

SchemaParityTest reads the real contract as a test resource configured by Gradle,
executes it in SQLite, and compares compiled Room export fields, table/column
sets, affinity, nullability, defaults, primary keys, foreign keys, uniqueness,
named indexes and sort order. A negative test removes an exported field in memory
and verifies that the same parity assertion fails. It also checks nullable identity
and forbidden column absence. Missing export files fail the test.

RoomBehaviorTests is shared by Robolectric JVM and AndroidJUnit4 device wrappers.
It exercises local Flow updates, StateFlow initialization, offline comment writes,
pending work protection, rollback with an unchanged cursor, unique/FK integrity,
late user arrival, lockout, classwork paths, deletion batches and file persistence
after reopening. SyncDtoTest covers wire names, round trips, defaults, nullable
scores, public-user mapping and rejected receipt preservation.

Device tests require only ADB, not a running Hub. To establish airplane-mode
evidence, record the original network state, enable airplane mode and disable
Wi-Fi, run the device suite, then restore the original state. A local repository
test cannot establish LAN discovery, live sync or real-Hub interoperability.

## UI consistency review

Figma reference: https://www.figma.com/design/nTUHevNDiscAMRWAtNrlzu/Untitled?node-id=0-1

No frames were retrieved. The Figma integration was found but is not connected;
direct retrieval also failed. An authenticated connection or exported frames is
needed to inspect actual Figma layouts. No Figma-specific visual claims are made.

The source review covered existing DiscoveryScreen, LaraNavigation, Theme and Type
against the repository design system. Nunito is bundled and applied to all type
roles. Screens use Lara colors/spacing/shapes, resource strings and 52/56dp action
minimums. Discovery retains its manual fallback, textual connection statuses,
icon-and-text banners, and nonblocking empty/offline states. This is a source
review, not a screenshot or full accessibility certification.

Category A (Issue #41 integration): no UI adjustment is necessary or implemented.
Category B (separate UI review/approval): DiscoveryScreen's scanning placeholders
show text inside solid surfaces rather than the documented opacity-pulse skeleton;
review reduced-motion handling before changing it. Theme assigns the generic
secondary role to AI purple, so future generic Material components must avoid
using it outside AI surfaces or receive an approved role-specific theme. These
findings do not authorize a layout or navigation redesign. Figma comparisons
remain unavailable until frames can be retrieved.

Verification results and proposed PR wording are recorded in issue-41-review.md.
