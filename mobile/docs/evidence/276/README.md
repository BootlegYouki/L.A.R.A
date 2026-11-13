# Issue #276 validation — 14 November 2026

Branch: `mobile/276-room-schema-parity`, based on fetched `origin/staging`
`316d747` (PR #303). Results below were captured before publication.

## Reused implementation

Reviewed and reused Osborne's implementation from
`origin/codex/276-mobile-audit-timestamps` (`e027748`, checkpoint `00cb573`).
At validation time no dedicated #276 PR existed. The original checkout, ignored UI/UX Pro Max skills,
and dirty `/private/tmp/lara-276` worktree were preserved.

Room v4 adds the four contract #273 audit columns. The migration copies all
existing fields into replacement tables with the specified legacy timestamp as
created_at, then restores indexes and foreign keys inside Room's transaction.
There is no destructive fallback or artificial SQL default. Sync, roster and
homework writes retain existing creation times. No API or DTO changes.

The populated v3 migration regression additionally checks original joined,
submitted, started and updated timestamps, rejection reasons and a second open.
Existing v1/v2 migration tests exercise the full chain to v4.

## Verification

- Debug: 203 tests, zero failures/errors, six opt-in integration skips.
- Release: 203 tests, zero failures/errors, six opt-in integration skips.
- SchemaParityTest: both tests passed in each variant against current contract.
- Populated v3→v4 migration, v1/v2 upgrades, Room/repository and sync tests passed.
- `test lint assembleDebug --continue`: BUILD SUCCESSFUL (4m 7s).
- Lint: 0 errors, 46 warnings. Both ARM debug APKs built.
- Shared contract/mock suite: 71 tests passed using the existing Python environment.
- Offline invariant scanner passed. `git diff --check` passed.

The initial targeted run passed parity/migrations but two existing repository
path assertions encountered macOS `/var` versus `/private/var` aliases. The full
run passed using `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp`, with no
production or assertion changes for that environment issue.

Command (mobile directory, Android SDK already installed):

```sh
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp \
ANDROID_HOME=/Users/Nash/Library/Android/sdk \
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' \
  -Dkotlin.compiler.execution.strategy=in-process \
  test lint assembleDebug --continue
```

## Limits

No APK was installed and no phone app data was read, cleared or migrated.
Read-only `adb devices -l` identified the connected Infinix X6731.
The six skipped tests require explicit live mock/integration setup. No real-Hub
or on-device flow was run. These remain pending under Lead announcement #242
and are explicitly disclosed in the PR; the user authorized publication with these limits. No Rust setup was started for this local DB change.

Lead announcement #297 and the latest Lead comment on PR #305 were reviewed:
#276 takes priority; Group 8 resumes only after this fix, with a November 18
merge deadline. No next-issue work was performed.
