# MOBILE 3.3 follow-up review verification

Date: 2026-11-09 (Asia/Manila). Existing [PR #258](https://github.com/BootlegYouki/L.A.R.A/pull/258), Issue #59. Branch `feat/59-mobile-3.3-video-offline`; tested changes are **uncommitted** on `132417f417804e3387d085fc0d3aade749dd4ad5`. See [source-manifest.txt](source-manifest.txt) for the exact source hashes. No commit, push, merge, review-thread resolution or readiness change was performed.

## Cancellation root cause and fix

`VideoViewModel.save()` previously excluded only `CancellationException` from error classification. A cancelled blocking transport can instead throw another exception during cleanup, so the generic catch replaced PAUSED with FAILED/UNAVAILABLE. It could also report late progress after a new transfer started.

The transfer now captures its coroutine context, calls `ensureActive()` before classifying exceptions and publishing completion, and checks that context inside the atomic progress update. Cancelled transfers cannot publish stale progress, completion or failure. Genuine active network and file errors retain the existing handling; the download architecture, private partial files, finalization and Room persistence are unchanged.

Two deterministic ViewModel regressions use a controlled transfer, without wall-clock delays:

- Cancel after 500/1000 bytes, then throw `IllegalStateException` during cleanup: final state must remain exactly PAUSED/500 bytes, with no download problem or screen problem.
- Hold cancelled cleanup with a deferred gate, start a resumed transfer at 750 bytes, then release the old callback and exception: the resumed state must remain unchanged and finish successfully.

The generic exception is a controlled reproduction of the reported class of failure, not a claim that this exact exception class was captured from the lead's emulator. Both tests failed against unchanged production code ([red run](cancellation-before.txt)) and passed after the fix. The existing network-retry and invalid-file failure tests remain green.

The physical Compose screen test now cancels an actual throttled transfer, waits for cleanup, asserts Resume with no failure/unavailable text, checks retained partial bytes, resumes, and verifies completed local-file SHA-256 and playback. This test uses a synthetic loopback server, separately from the LAN mock check below. [Paused screenshot](device-download-paused.png), [progress screenshot](device-download-progress.png), [instrumentation results](device-tests.txt).

## Physical phone and actual offline playback

- Authorized physical Infinix X6731, Android 14 / API 34, arm64. This is not a 3–4 GB budget-phone memory test.
- Existing packages were checked before installation. Only the existing isolated `org.lara.app.sprint33video` and its test APK were updated with `install --user 0 -r`. No app data was cleared, and no package was uninstalled or production installation overwritten.
- Unmodified mock Hub from extracted `origin/staging` **`16a52980b5a87119306a68340f850ba1b5a5fc83`**, containing merged PR #259 (`002873af57269b5abdc208a49344c08889ac4fc1`). `git archive` extracted it under ignored `mobile/build/59-staging`; no staging merge/reset or shared-source edits were needed.
- PC Ethernet `192.168.100.12`; phone Wi-Fi `192.168.100.21`, same router. Hub HTTP `8080`, WebSocket `8081`. Discovery and actual HTTP sign-in/sync succeeded. ICMP ping was unanswered; HTTP connectivity was verified independently. No emulator address or USB port forwarding was used.
- Used the documented enrolled learner seed account and PIN through the normal app sign-in UI. Opened Classwork → `Video: Food Chain`, streamed the 30-second fixture, sought forward/backward, tapped Save for home, and observed Download complete / Saved on this device. [Streaming](stream-playing.png), [saved](saved.png).
- Enabled airplane mode, explicitly disabled Wi-Fi, force-stopped **only** the isolated app, cold-reopened it, opened the saved video through normal app navigation, tapped Play, and sought forward/backward. Repeated this check on the final APK after the landscape correction. [Device result](offline-device-check.txt).
- **Actual moving video frames were visually inspected:** the final captures show changing embedded frame timestamps and shapes, with airplane icon, Hub-unreachable banner and saved badge. This is stronger evidence than duration alone. [Frame A](offline-playing-a.png), [frame B](offline-playing-b.png), [forward seek](offline-seek-forward.png), [backward seek](offline-seek-back.png).
- Final observed UI positions: after Play `0:01`, after forward seek `0:18`, after backward seek `0:13`, of `0:30`. No Mobile playback fix was needed. [Sanitized current-process media Logcat](offline-logcat.txt) also records the native AVC decoder; logs were not used as the sole playback proof.
- Restored/read back airplane mode `0`, Wi-Fi `1`, font scale `0.85`, user rotation `0`, accelerometer rotation `0`. Mobile-data setting was not changed.

## Smaller findings

| Finding | Result |
|---|---|
| Save label capitalization | Player now matches Classwork and design-system §§5.12/9.5: **Save for home**. One resource changed. |
| Landscape player | Explicit bounded viewport height in landscape; portrait keeps 16:9. Existing vertical scrolling keeps controls reachable. An initial `heightIn` plus `aspectRatio` combination could still overflow, so the final landscape viewport uses explicit height. Physical instrumentation checks a fully visible seek target and actionable Play, then reaches the saved status. Also manually exercised Play in the actual class shell. [Player controls](device-landscape-play-seek.png), [shell controls](landscape-shell-controls.png), [shell saved status](landscape-shell-saved.png). Controls below the viewport require scrolling; there is no redesign or overlapping fixed control bar. |
| 200% bottom-tab labels | **Deferred shared-navigation accessibility follow-up.** Reproduced on the physical phone: Settings font size 200%, open a class, observe Stream/Classwork/Quizzes/AI Tutor ellipsized. [Screenshot](font-200-navigation.png). `BottomNavBar.kt` reserves additional height but deliberately limits labels to one line (earlier approved treatment). Full labels need wrapping/reflow across every screen and validation at narrow widths; this focused video fix does not change the approved shell or screen map. Proposed follow-up: “Mobile bottom navigation: readable complete labels at 200% font scale.” No issue was opened automatically. |
| Gradle wrapper | `git update-index --chmod=+x mobile/gradlew` records **100755**; wrapper blob/content is unchanged. This requested mode change is the only staged change. |

## Final checks

| Check | Result / evidence |
|---|---|
| `assembleDebug test lint` | BUILD SUCCESSFUL; [full output](gradle-build-test-lint.txt). Existing Windows Robolectric temporary-directory init and mock environment used, as in prior evidence. |
| JVM suites | **392 passed**, 196 debug + 196 release, zero failures/errors/skips. Includes six ViewModel tests. [Counts](test-counts.txt). |
| Android lint | **0 errors, 39 warnings** (+1 informational item), existing warnings retained. |
| Final physical instrumentation | **4 passed**: actual cancel/resume/save screen, both preserved button regressions, and new landscape reachability test. [Output](device-tests.txt). Earlier native stream/seek test also passed during this session; final real-mock offline check is independent. |
| Branch shared tests | **68 passed**; [output](shared-tests.txt). |
| Updated staging contracts/mock suite | **69 passed**; [output](updated-staging-tests.txt). Includes playable MP4 streaming and download Range/If-Range acceptance tests. [Focused Range run](updated-mock-range.txt). |
| Invariant guardrail | Passed; [output](invariants.txt). |
| Diff whitespace and scope | `git diff --check` and `git diff --cached --check` passed; reviewed changes are Mobile only. |

Build helpers, staging extraction and transient captures stay ignored under `mobile/build/`. Team skill remains a separate documentation/process follow-up; no skill files were created or modified.

## Remaining limits

Real Rust Hub, router WAN-unplugged, remote-tunnel playback, budget-phone memory and a physical Wi-Fi drop mid-download were not verified in this follow-up. The local environment has no `cargo`; the requested actual playback check used the updated Python mock. Genuine network retry is covered by existing tests; throttled socket cancellation/resume is covered on the phone. No new-head CI run exists because the user requested stopping before commit/push. The 200% navigation finding remains explicitly deferred above.

## Review handoff

Changed implementation/test files: `VideoViewModel.kt`, `VideoPlayerScreen.kt`, English `strings.xml`, `VideoViewModelTest.kt`, `VideoDeviceTest.kt`; plus `mobile/gradlew` executable mode, `mobile/docs/video_player.md`, the parent evidence index and this evidence directory. No dependency, API, schema or shared-file changes.

Recommended commit message:

`fix(mobile): preserve paused downloads and verify offline playback (#59)`

Suggested PR review reply (not posted):

> Fixed cancellation-to-FAILED: cancelled transfers now check coroutine activity before error/completion handling and cannot publish stale progress over PAUSED or a resumed transfer. Two deterministic regressions failed before the fix and pass afterward; active failure/retry tests remain green. Physical Infinix X6731 / Android 14 passed cancel → retained progress → Resume → saved playback, both existing Compose button regressions, and landscape controls. Against merged #259's mock over LAN, the 30-second video streamed/sought/saved and actually rendered moving frames and sought after airplane mode + force-stop + cold reopen. Build, 392 JVM tests, lint (0 errors/39 warnings), 68 branch shared tests, 69 updated-staging shared tests and invariants passed. Save label and gradlew executable mode fixed; 200% bottom-nav labels documented as a shared-navigation follow-up. Evidence: `mobile/docs/evidence/59/follow-up/`. Ready for your inspection; no commit/push or review-state change yet.
