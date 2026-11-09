# MOBILE 3.3: fullscreen and remove from device

Verification checkpoint before publication: 2026-11-09 (Asia/Manila). Existing [PR #258](https://github.com/BootlegYouki/L.A.R.A/pull/258), [Issue #59](https://github.com/BootlegYouki/L.A.R.A/issues/59). Branch `feat/59-mobile-3.3-video-offline`, HEAD `65f1b061960a6ac961aa3c32363c5fc59b67ee02`. Changes are uncommitted. No staging, commit, push, PR, merge, readiness change, external comment, or review-thread resolution was performed.

The author subsequently authorized committing and pushing this verified continuation to the existing PR. The test logs, source hashes and original handoff below preserve the pre-publication checkpoint; the PR head and Git history identify the publication commit. Implementation/test hashes were rechecked before publication. No new PR, merge or review-thread resolution is requested.

## Preflight and lead instructions

The initial worktree was clean. `origin` is the expected repository. A safe fetch and GitHub PR query confirmed the local and remote feature heads matched (0 ahead, 0 behind); PR base remains staging. No reset, stash, staging merge or new branch was used.

Ponytail 5.3.3 is installed and full mode is active. Its skill and documented activation/tracking hooks were read; no hook command or substitute plugin was installed or run manually. The Ponytail review workflow was applied to callers, lifecycle, deletion boundaries and regression tests. Architecture, Android offline and Compose skills were loaded. The Mobile team skill is absent; no skill files were changed. Current root/Mobile English-only rules take precedence over older translated-string guidance in fallback skills and Issue #59.

Announcements #194 (process/sprint dates), #215 (real-Hub integration), #238 (screen click-through/state screenshots), #242 (real-Hub main-flow evidence), their comments, Issue #59, PR #258 comments/reviews and inline comments were checked before editing. No newer discussion announcement superseded them. The latest PR comments request [fullscreen](https://github.com/BootlegYouki/L.A.R.A/pull/258#issuecomment-6736047207) and [removal](https://github.com/BootlegYouki/L.A.R.A/pull/258#issuecomment-6736082239); earlier lead comments defer real-Hub video/WAN-unplugged to integration day and track 200% bottom-tab clipping in #266. No lead-facing messages were posted.

The current-staging mock was extracted without modification under ignored `mobile/build/59-current-staging`, from `326f79ae251b600be0f6548eab134bfc36e39fdb`, including the playable #259 fixture. No shared or Server source was changed.

## Implementation

- `VideoPlaybackSession` retains one player across configuration recreation. Video ViewModels use the Activity store, keyed by account and material, because the account shell otherwise clears its composition-owned store on rotation. Rendering views use Media3 `switchTargetView`; only a weak view reference is retained. True background stop, navigation disposal and final ViewModel clearing release playback. Source replacement retains position/play intent.
- Fullscreen is saved presentation state. A full-window black ComponentDialog hosting Compose covers the regular classroom UI, fits the entire frame without cropping, permits orientation changes, and keeps the same player. CornersOut/CornersIn have 52dp targets and resource TalkBack labels. The Dialog owns immersive insets, transient edge-swipe bars, cutout handling and safe control bounds. It restores its saved system-bar visibility/appearance/behavior on stop/disposal; the Activity window is unchanged.
- Compose controls overlay fullscreen video. Play/pause is 56dp; seek and exit are at least 52dp. Controls hide after about three seconds while playing, with Android's recommended accessibility timeout. Tap/interaction restarts that timeout; scrubbing suspends hiding; pause retains controls. Button or Android Back exits fullscreen only.
- A valid local video shows Saved on this device and the Trash removal action. The exact lead-requested confirmation uses Remove/Cancel resources. Its body scrolls when 200% text exceeds landscape height; the normal fullscreen button has an independent 52dp target on the video so the transport row cannot squeeze it. Cancel does not change playback/files/Room. Confirm releases local playback, cancels and joins the material's download, blocks another save, and serializes deletion with the existing repository transfer mutex.
- Removal accepts only this account's current VIDEO material and private hashed `.video` path. All canonical paths and file types are checked before deletion. It removes only this material/version's completed video, partial and validator, never directories or unrelated files. Another video's saved path is rejected. The existing conditional `clearFile` clears Room when the saved file is gone, including partial-failure cleanup. The short disk/Room operation is non-cancellable after obtaining the lock. Storage failure preserves remaining saved state and displays the existing storage message.
- Removal needs no network/session token. Download state returns to IDLE, then ordinary source resolution shows the Hub/offline state. Save for home downloads the same material again when available. No schema, contract, dependency, background-service or other-team change was needed.

The implementation reference is [video_player.md](../../../video_player.md). Android API references checked: [immersive mode](https://developer.android.com/develop/ui/views/layout/immersive), [Media3 PlayerView](https://developer.android.com/reference/androidx/media3/ui/PlayerView).

## Automated coverage

New JVM tests cover save/remove/offline/no-auth/Room-cleared/redownload, preservation of a second video and unrelated file types, removal outside the private root, deletion failure, waiting for cancelled transfer cleanup, blocking concurrent Save, offline state after removal and recoverable storage errors. Existing cancellation, bounded Range, If-Range, atomic finalization, Room persistence and auth tests remain included.

New instrumentation covers entering fullscreen, portrait/landscape rotation, exact player/ViewModel identity, advancing position, overlay auto-hide/reveal, paused control visibility, return rotation, button/Back exit, restored Activity bars, full-display window bounds and hidden status/navigation bars in both orientations. Removal instrumentation covers the exact dialog, non-destructive Cancel, deleted file/partial/validator, null Room path, unrelated-file preservation, no media request with expired auth, and successful redownload/SHA-256. Tests run only in the isolated app package. The existing resume assertion now checks the actual retained byte offset instead of assuming every socket read ends at exactly 32768 bytes.

Published text logs are normalized to UTF-8, with trailing spaces from Windows command echoes removed for Git whitespace checks; test/build result lines are unchanged.

## Results and device evidence

| Check | Result |
|---|---|
| Debug assemble, JVM test, lint | BUILD SUCCESSFUL; [full log](gradle-build-test-lint.txt), [lint](lint.txt): 0 errors, 42 warnings |
| Physical instrumentation | 9 passed; [log](device-tests.txt), [APK build](isolated-device-build.txt) |
| JVM suites | 402 passed: 201 debug + 201 release, no failures/errors/skips; [counts](test-counts.txt) |
| Branch contract/schema/mock tests | 68 passed; [log](shared-tests.txt) |
| Extracted current-staging mock tests | 69 passed, including playable Range/If-Range media; [log](current-staging-tests.txt) |
| Invariant guardrail | Passed in UTF-8 mode; [log](invariants.txt) |
| Physical device | Infinix X6731 / Android 14 / API 34 |
| Final diff checks | `git diff --check` and cached check passed; no staged files; all changed paths are Mobile only; [record](final-checks.txt) |

The PC used Ethernet `192.168.100.12`, the phone Wi-Fi `192.168.100.21`, HTTP 8080 and WebSocket 8081. Actual automatic discovery, manual IP connection, seed sign-in and normal class/Classwork navigation were exercised without USB port forwarding, firewall changes or emulator addresses. Both streamed fullscreen orientations rendered the playable 30-second mock fixture; elapsed time continued through rotation. The native full-window host fixed an inset page border found during this actual-app click-through. The video was saved via the LAN Hub; the confirmation and Cancel were exercised through the ordinary UI.

## Physical LAN/offline walkthrough

The ordinary app, not an injected test UI, streamed the current mock video and entered fullscreen in portrait and landscape. Actual rendered frame timestamps and elapsed time advanced across rotation. Save for home completed; the exact confirmation opened and Cancel preserved the saved state.

Then airplane mode was enabled and Wi-Fi explicitly disabled. Only the isolated app was force-stopped and reopened. The saved video played locally and rotated fullscreen in both orientations. Confirming Remove deleted exactly one completed video from the existing private-media file set, cleared the seed material's Room path, preserved all other media, and showed the existing offline-unavailable state and Save for home. After connectivity returned, the same video downloaded again, Room regained its path, the original file set was restored and playback started. Room assertions use fresh copies of only the documented synthetic seed account's database after force-stop; no database or credentials are published. [Sanitized walkthrough and restored settings](offline-removal-check.txt).

Final readback confirmed airplane mode 0, Wi-Fi 1, font scale 0.85, user rotation 0, accelerometer rotation 0. The temporary current-staging mock was stopped after verification; the isolated app and its saved video remain installed for review. No firewall/router configuration or mobile-data setting was changed. No app data was cleared, and no personal file was touched.

At 200% font scale, normal fullscreen entry, fullscreen controls in portrait/landscape, dialog actions and Cancel were exercised. Visual inspection found a clipped confirmation body in landscape and a squeezed entry icon; the final implementation scrolls the body and gives entry its own 52dp video overlay target. The separate #266 bottom-tab truncation remains visible outside fullscreen.

Genuine physical screenshots (all synthetic lesson content):

- [LAN discovery](mock-discovery.png)
- Streamed fullscreen: [portrait](mock-stream-fullscreen-portrait.png), [landscape](mock-stream-fullscreen-landscape.png)
- Cold-start offline fullscreen: [portrait](mock-offline-fullscreen-portrait.png), [landscape](mock-offline-fullscreen-landscape.png)
- [Removal confirmation](mock-remove-dialog.png), [offline confirmation](mock-offline-remove-dialog.png), [offline removed state](mock-offline-removed.png)
- [Redownload complete](mock-redownloaded.png), [redownloaded playback](mock-redownloaded-playing.png)
- 200% text: [normal player/independent fullscreen target](font200-normal.png), [portrait fullscreen](font200-fullscreen-portrait.png), [landscape fullscreen](font200-fullscreen-landscape.png), [portrait dialog](font200-remove-dialog-portrait.png), [landscape dialog start](font200-remove-dialog-landscape-start.png), [landscape dialog scrolled to the end](font200-remove-dialog-landscape-end.png). [Check result](font200-check.txt).

An actual Android edge-back swipe was also verified with gesture navigation enabled (mode 2): fullscreen closed to the existing video page. [Result](device-gesture-check.txt).

## Changed files and review

See [changed-files.txt](changed-files.txt) for every tracked/untracked path added by this continuation and [source-manifest.txt](source-manifest.txt) for exact implementation/test source hashes. All paths are under `mobile/`. Changes consist of the player screen/session/ViewModel, existing video repository/downloader/domain interface, English strings, three bundled Phosphor vector exports, two JVM test files, the existing device test, Mobile docs and this evidence folder.

Ponytail review traced the production account-shell caller, configuration recreation, true-stop release, rendering-view handoff, file/Room cleanup, cancelled transfer completion and redownload. No unresolved correctness or deletion-scope finding remains in the reviewed change. No test, mock helper, credential file or dependency was added outside Mobile. Only ignored build-time helpers and an unmodified staging extraction were used for tooling.

## Limits and installation transparency

The physical phone is an Infinix X6731, Android 14 / API 34, arm64; reported RAM is 11,746,152 kB. This does not prove performance on a 3-4 GB phone. No emulator was substituted. Existing test-app data was preserved with `adb install --user 0 -r`; no app data was cleared.

One verification command used the wrong working directory after successful unit/lint checks. Its failed isolated rebuild was followed by installation of the standard `org.lara.app` APK. Preflight showed that package was absent. The run was stopped; that newly installed package had no `files` directory and was uninstalled for user 0. Existing isolated packages/data were preserved. Subsequent installation validates APK metadata and each command's success before proceeding.

At the verification checkpoint, no CI run existed for the uncommitted changes. Publication triggers new-head CI; its result is reported on PR #258. Local verification does not replace that required CI before merge.

Real Rust Hub playback is not verified: `cargo` is unavailable, and the lead already deferred these video routes to integration day. No real-Hub 501 response is claimed. Router WAN-unplugged, remote tunnel, a physical Wi-Fi drop mid-download, budget-phone peak heap and spoken TalkBack feedback require separate verification. Socket interruption/cancellation/resume is covered by the JVM and synthetic physical tests. Existing bottom-tab label clipping at 200% belongs to #266. Three added lint warnings are the bundled Phosphor vectors' path-length advisories; existing warnings remain visible.

## Original review handoff (before publication)

Suggested commit message:

`feat(mobile): add fullscreen lesson video and remove saved copies (#59)`

Suggested reply to the lead (not posted):

> Added fullscreen playback and confirmed Remove from device on the existing PR. Fullscreen keeps the same player across rotation, uses fit-center black immersive presentation and accessible overlay controls, and exits via the button or Android Back. Removal releases local playback, cancels/joins the material's transfer, deletes only its private video/partial/validator files, and clears Room through clearFile; Cancel is non-destructive and the video can be saved again. Build, 402 JVM tests, nine physical device tests and lint (0 errors/42 warnings) passed. The actual LAN mock streamed fullscreen in both orientations; cold-start airplane-mode playback, scoped removal, null Room path and redownload passed on Infinix X6731 / Android 14. At 200% text the dialog scrolls and fullscreen entry retains its own target. Evidence is in mobile/docs/evidence/59/fullscreen-removal/. Real-Hub/WAN-unplugged and budget-phone checks remain explicitly unverified. Changes are uncommitted for author review; no push/readiness change was made.
