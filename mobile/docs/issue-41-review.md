# Issue #41 implementation review

Branch: `feat/41-mobile-room-database`.
Base: fetched `origin/staging`, `2136fc4905559ed793433a57d1bc76db09f9ca1e`.
MOBILE 1.1 PR #160 and MOBILE 1.2 PR #164 are ancestors of this base.
The initial working tree was clean on `docs/mobile-ch3-173`. No thesis files,
prior feature branches, shared contracts or other team folders were changed.
The initial implementation was left uncommitted for human review. The user
subsequently authorized committing, pushing and opening the feature PR. Merging
remains subject to Lead Developer review.

## Delivered

All **16** current contract entities are registered and exported at schema version
1, including topics, private_comments and sync_state. This follows the approved
Tech Spec decision 8 and the user's authorization to use the necessary contract
scope despite the stale 14-table wording. See [room_schema.md](room_schema.md)
for the complete entity/table mapping, DAO coverage and data flow.

Seven DAOs provide local stream, classwork, roster, quiz-state, class-list and
sync-state access plus transactional cache batches/deletions. Queue guards retain
local comments, submissions, private comments and attempts. Cache refreshes retain
cached material paths. Explicit secondary-key checks prevent silent row loss in
Room 2.6 Upsert. Foreign-key and uniqueness failures roll back atomic work.

Five domain repository interfaces and Room implementations expose read-only
StateFlow with explicit Loading and loaded-empty states, under the caller's
coroutine scope. Per-learner hasInProgressAttempt remains a Flow for later AI
lockout. Comment writes are local UUID-based queued inserts. No repository read
requires Hub discovery, authentication, HTTP or Wi-Fi.

Sync DTOs cover the documented pull/push envelopes, public users, metadata,
comments, submissions, quiz metadata, grade receipts and tombstones. Snake_case
SerialName annotations, optional collections, nullable scores and mapping tests
are included. No client-only storage fields or answer keys appear in wire models.
No synchronization engine or new UI screens are implemented.

## Verification

Run from mobile/:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home --no-daemon assembleDebug test lint assembleDebugAndroidTest --console=plain
```

Result: **BUILD SUCCESSFUL**. 34 debug and 34 release unit tests passed with no
failures or skipped tests. This includes 11 shared Room behavior tests, two schema
tests and five DTO tests per variant; all 16 earlier tests remain passing.
Lint completes with zero errors and 18 warnings. Warnings include dependency
update advice, existing manifest/theme issues and the added kapt recommendation
to use KSP. Kapt's stub-language fallback and three missing-FK-index compiler
warnings are documented in room_schema.md; indexes remain contract-exact.

Schema parity compares the actual SQL contract with the compiled exported JSON:
16 tables, exact columns, storage types, nullability, defaults, primary keys,
foreign keys, unique constraints, named indexes and sort direction. A test
removes a column from an in-memory export copy and confirms the parity assertion
fails. Nullable users.lrn_or_id and forbidden-column absence are checked.

Run from repository root:

```powershell
$env:PYTHONUTF8 = '1'
python scripts/verify_invariants.py
python -m unittest discover tests
```

Result: invariant scanner passes; **66 tests pass**. The suite exercises the
mock Hub. Python's default Windows cp1252 output initially could not print the
scanner's success symbol; UTF-8 mode resolves that without editing shared files.
Gradle initially reused a sandboxed daemon unable to download dependencies;
the fresh --no-daemon process completed successfully with normal network access.

Physical device: model 2511FPC34G, Android API 36. Both debug APKs installed with
normal network settings after the user enabled installation. Then, with confirmed
`airplane_mode_on=1` and `wifi_on=0`, the command below passed **all 11 instrumented
tests**. It exercises every contract table, local reads/writes and observations,
transaction rollback, foreign keys, queue protection and persistence after reopen.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Enable airplane mode and disable Wi-Fi only after installing both APKs.
adb shell am instrument -w -r -e class org.lara.app.data.RoomDeviceTest org.lara.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -W -n org.lara.app/.MainActivity
```

Instrumentation result: **OK (11 tests)**. Offline startup returned **Status: ok**,
**LaunchState: COLD**, with a running app PID. The original airplane=0, wifi=1
settings were restored in a finally block and confirmed afterward. No Hub was
required by the tests. No screenshots or physical RAM measurements were taken.

The earlier `connectedDebugAndroidTest` attempts were blocked before tests ran by
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. That Gradle task is
not reported as passing. Separating installation from offline execution allowed
the same compiled AndroidJUnit4 suite to run successfully via ADB. The failed
installer log and successful direct runner log are both retained in evidence.

Final `git diff --check` passes. All changed/new files are under mobile/.
Discovery source, MainActivity, navigation, network policy, resources and fonts
are unchanged; their existing unit tests remain passing.

## Figma and existing UI review

The Figma integration was discovered but is not connected, and direct retrieval
of the supplied file failed. No frames were inspected. Connect Figma or provide
exported frames for a future visual comparison.

The repository-based source audit is in room_schema.md. Existing Nunito, token
usage, localized strings, minimum button sizes, manual fallback and textual
connection feedback remain in place. No category A UI fixes were needed or made.
Category B findings for separate review are the discovery loading skeleton's
pulse/reduced-motion behavior and avoiding generic use of the AI-purple secondary
theme role. No Figma-driven redesign is included.

## Limits and deferred work

The lara-mobile skill requested in the brief does not exist. Existing
lara-architect and lara-android-offline guidance were used. No skill was created.

Live sync, reset handling, tombstone dispatch, push receipt application, queued
submission uploads, authentication, background scheduling, and classroom UI
integration belong to their later issues. The DTOs preserve wire metadata gaps
rather than inventing missing SQL timestamps or paths. Future mappings need
explicit cache/pull context. Future receipt handling must explicitly acknowledge
queued rows rather than route receipts through the cache-refresh guard.

No real-Hub interoperability, router-WAN-unplugged classroom cycle, discovery
regression walk-through, 3/4 GB phone memory profiling, bilingual screenshots or
full accessibility audit is claimed. Mock-Hub contract tests and data-layer tests
are separate evidence; neither substitutes for that later integration work.

## Suggested commit

`feat(mobile): add contract-aligned Room persistence for #41`

## Suggested PR title

`[MOBILE 1.3] Add Room offline database and repository layer`

## Suggested PR description

### Summary of Changes

Cached classroom data can now be read through Room-backed repositories while the
Hub is unreachable. Add the 16-table contract mirror, observable DAOs,
transactional cache helpers, pure domain models, StateFlow repositories and
serialized sync payloads. Preserve queued work and cached paths during refresh.
Include exported schema parity, rollback, offline persistence and DTO tests.

Closes #41

The contract contains 16 tables; the issue's 14-table wording is superseded by
approved Mobile Tech Spec section 14 decision 8. No contract or UI changes.

### Subsystems Touched

- [x] mobile/
- [x] Mobile documentation

### How to Test

1. Run the Gradle command above to build and run schema, DAO and repository tests.
   The parity test must fail if an exported column is removed.
2. Install both debug APKs on a test device, allow USB installation, disable its
   Wi-Fi and enable airplane mode, then run RoomDeviceTest. Synthetic cached
   records must remain readable and observers must update without a Hub.
3. Restore the device's original network state. Launch the app; the existing
   discovery/manual-address screen should still open.

### Verification & Testing

- [x] assembleDebug, test and lint pass
- [x] Exported schema and deliberate drift detection pass
- [x] 11 physical-device instrumentation tests pass in airplane mode
- [x] Offline cold startup succeeds; original device network state restored
- [x] Repository invariant check and 66 contract/mock-Hub tests pass
- [ ] Full classroom LAN test with WAN unplugged
- [ ] Verified on a measured 3/4 GB budget phone
- [ ] Full bilingual visual/accessibility review

### Evidence

See mobile/docs/evidence/41/ and mobile/docs/issue-41-review.md.

### Not Verified

See the limits above and the device result recorded in this report. Figma frames
are unavailable. No live sync implementation or classroom UI is added here.

### Non-Regression Checklist

- [x] No cloud/CDN runtime dependencies
- [x] Mobile documentation updated
- [x] No answer-key or PIN fields in client data models
- [x] Per-learner IN_PROGRESS lockout query covered
- [ ] No compile warnings (known warnings documented above)

## Changed files

- `mobile/app/build.gradle.kts`
- `mobile/app/schemas/org.lara.app.data.local.LaraDatabase/1.json`
- `mobile/app/src/androidTest/java/org/lara/app/data/RoomDeviceTest.kt`
- `mobile/app/src/main/java/org/lara/app/LaraApp.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/LaraDatabase.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/CacheDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/ClassroomDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/ClassworkDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/QuizDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/RosterDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/StreamDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/dao/SyncStateDao.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/AiChatMessageEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/AnnouncementCommentEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/AnnouncementEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/AssignmentEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/AssignmentSubmissionEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/ClassroomEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/EnrollmentEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/MaterialChunkEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/MaterialEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/PrivateCommentEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/QuizAttemptEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/QuizEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/QuizQuestionEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/SyncStateEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/TopicEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/local/entities/UserEntity.kt`
- `mobile/app/src/main/java/org/lara/app/data/remote/dto/DtoMappings.kt`
- `mobile/app/src/main/java/org/lara/app/data/remote/dto/SyncDtos.kt`
- `mobile/app/src/main/java/org/lara/app/data/repository/EntityMappings.kt`
- `mobile/app/src/main/java/org/lara/app/data/repository/RoomRepositories.kt`
- `mobile/app/src/main/java/org/lara/app/domain/ClassroomRepositories.kt`
- `mobile/app/src/main/java/org/lara/app/domain/model/CachedModels.kt`
- `mobile/app/src/sharedTest/java/org/lara/app/data/RoomBehaviorTests.kt`
- `mobile/app/src/test/java/org/lara/app/data/RoomBehaviorTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/SchemaParityTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/SyncDtoTest.kt`
- `mobile/build.gradle.kts`
- `mobile/docs/README.md`
- `mobile/docs/evidence/41/adb-install.txt`
- `mobile/docs/evidence/41/airplane-mode-instrumentation.txt`
- `mobile/docs/evidence/41/contract-tests.txt`
- `mobile/docs/evidence/41/device-network-state.txt`
- `mobile/docs/evidence/41/gradle-build-test-lint.txt`
- `mobile/docs/evidence/41/gradle-device-install-failure.txt`
- `mobile/docs/evidence/41/invariants.txt`
- `mobile/docs/evidence/41/offline-startup.txt`
- `mobile/docs/evidence/41/unit-test-summary.txt`
- `mobile/docs/issue-41-review.md`
- `mobile/docs/room_schema.md`
