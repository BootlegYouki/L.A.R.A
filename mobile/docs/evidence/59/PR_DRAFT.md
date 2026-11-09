## Summary of Changes

Video materials now open from Classwork and Stream attachments in an accessible Media3 player. Streaming uses authenticated, bounded HTTP Range requests. Save for Home resumes partial downloads, validates response metadata and completed bytes, finalizes app-private files, and records `materials.local_file_path` in Room. Saved videos play before Hub authentication is checked.

The player releases on stop/disposal and retains playback position for configuration recreation. Downloads show progress, pause/cancel safely, retry foreground transport failures, and preserve partial bytes across process death. Completed-file receipts and pruning are serialized. The bottom-nav label fix requested in #252 uses ellipsis at large font sizes.

Closes #59

This is a **draft for review**: implementation and physical-phone checks passed, while real-Hub, WAN-unplugged LAN and budget-hardware checks remain open. Mobile only; no API, SQL schema, shared mock, skills or other team folder changed. English-only resources follow current repository policy and the user's explicit decision.

## Subsystems Touched

- [x] `mobile/` (Android Native Jetpack Compose)
- [ ] `desktop/` (Tauri + React Desktop Client)
- [ ] `server/` (Local Hub Tauri/Rust Server)
- [ ] `contracts/` (API & Event Schemas)
- [x] Documentation / Specs

## How to Test (for the person reviewing as an end user)

1. Start the unchanged Python mock with `python scripts/mock_hub.py`. Sign in as the documented seeded learner (`123456789012`, PIN `1234`) on the classroom network and open Classwork, then a video material. Authenticated byte ranges work; the current mock's zero-filled stream is not decodable video, so expect a calm playback error. Its PDF download placeholder must never become a completed saved video.
2. For actual decoding/save verification, build the isolated `org.lara.app.sprint33video` app and instrumentation APK using the init-file procedure in the evidence report. Install both on an authorized ARM phone without clearing the production app.
3. Run the four `VideoDeviceTest` methods in the documented order. A permitted synthetic HTTP video fixture verifies frame rendering, forward/backward seeking, cancel/resume with If-Range, SHA-256 and Room persistence.
4. With the fixture server stopped, enable airplane mode and disable Wi-Fi. Cold-open the saved video test: it must render and seek with an expired session. Restore the original phone settings afterward.
5. Run the actual player UI checks at normal/200% text and the Save for Home UI test. It must show progress, then completion, and play the verified local file. These native tests are synthetic-fixture checks, not real-Hub or classroom-LAN evidence.

## Verification & Testing

- [ ] Tested on local LAN / Wi-Fi router with WAN cable unplugged
- [ ] Verified on 3GB/4GB physical Android phone or low-spec emulator
- [x] Verified offline persistence in Room/SQLite DB
- [ ] Main flow run against the real Hub on `staging`
- [x] Verified Material Design 3 accessibility: 52dp layout targets, actual 56dp clickable Play target, normal/200% text
- [x] Verified English text in resources
- [x] Screen uses design-system tokens and Classwork/material structure; seek/progress tracks explicitly use green and neutral colors

Local validation:

- `gradlew.bat -I build/59-test-env.gradle assembleDebug test lint`: **BUILD SUCCESSFUL**. 194 Debug + 194 Release tests, zero failures/errors/skips (388 total). Includes 28 media tests per variant and all existing mock integrations enabled. The init file only selects a short isolated Windows temp path for existing SQLite/path tests.
- Lint: **0 errors, 39 warnings**; warnings remain visible.
- `python -m unittest discover tests`: **68 tests, OK**.
- `python scripts/verify_invariants.py`: **PASSED**.
- Four final native instrumentation invocations on **Infinix X6731 / Android 14 (SDK 34)**: **OK (1 test)** each. Additional instrumentation build/lint passed.

## Screens and real-device tests

- Actual player at normal and 200% text, visible download progress, completion/local playback, and native airplane-mode seeking are captured below and in the phone report. Screens use synthetic media and test identities. Personal floating-overlay captures were excluded and recaptured after the user dismissed the overlay.
- Initial UI-test failure measured the 32dp icon instead of its 56dp clickable parent; the corrected test retains the 56dp requirement and passes. Phone inspection also caught the inherited lavender seek track, now corrected to approved tokens.
- Original airplane/Wi-Fi/mobile-data/font settings were verified restored. Production app data was untouched.

![Player controls](https://github.com/BootlegYouki/L.A.R.A/raw/refs/heads/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/phone/video-ui-en-1.png)

![Save for Home progress](https://github.com/BootlegYouki/L.A.R.A/raw/refs/heads/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/phone/video-download-progress.png)

![Player at 200% text](https://github.com/BootlegYouki/L.A.R.A/raw/refs/heads/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/phone/video-ui-en-2.png)

## Evidence

- [Complete evidence index and limitations](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/README.md)
- [Build, full tests and lint output](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/gradle-build-test-lint.txt)
- [JUnit counts](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/test-counts.txt), [lint report](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/lint.txt)
- [Physical-phone report, logs and screenshots](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/phone/README.md)
- [Mock Range/auth/refusal probes](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/mock-ranges.txt)
- [Shared tests](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/shared-tests.txt), [invariants](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/invariants.txt)
- [Actual real-Hub launch attempt](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/evidence/59/real-hub.txt)
- [Implementation, lifecycle and data flow](https://github.com/BootlegYouki/L.A.R.A/blob/feat/59-mobile-3.3-video-offline/mobile/docs/video_player.md)

## Not Verified

- **Real staging Hub:** launch was attempted, but `cargo` is unavailable. No real-Hub HTTP response was observed; source inspection finds material-route stubs. SERVER #254 remains an unmerged draft and was not used.
- **WAN-unplugged LAN streaming/save:** not run; no router/WAN changes were made. Phone streaming/download checks used a synthetic loopback server.
- **Shared mock limitation:** `/stream` returns ASCII zeros and `/download` returns a 29-byte PDF placeholder for VIDEO, ignoring download Range. Actual Mobile transport verifies ranges and rejects invalid downloads; valid video decoding/save integrity cannot be demonstrated against those mock bytes. Lead/Server follow-up: provide matching decodable fixtures through a separate shared change.
- **Budget hardware / peak heap:** the phone reports about 11.2 GiB RAM, so it does not satisfy the 3?4 GB hardware target. Maximum recorded Java/native allocation sample was 53.33 MiB; samples are not peak heap or PSS.
- **Screens:** empty/loading states and a separate full navigation walk from the main Classwork list were not captured on-device. Player detail, progress, local playback, large text and native airplane-mode playback were checked directly.
- **Architecture/process follow-ups:** the original Tech Spec's cache-only Save for Home suggestion was replaced with finalized private files/Room paths as explicitly required by the implementation request; the team spec/doc is updated. Missing `lara-mobile` skill remains a separate process follow-up; existing Android skills were explicitly authorized.

## Contract Changes

Not applicable. No contract, schema, mock or shared prompt changed.

## Non-Regression Checklist

- [x] Zero external cloud/CDN runtime dependencies added
- [x] Subsystem documentation updated in `mobile/docs/`
- [x] No direct answers output by Socratic AI tutor (AI paths unchanged)
- [x] AI quiz-lockout paths retained; regression suite passes
- [ ] No compile warnings or type errors: builds have no errors, but existing kapt/Room and dependency warnings remain documented
