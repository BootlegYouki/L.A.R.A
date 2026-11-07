# MOBILE 3.2 automated checkpoint

Branch `codex/9-mobile-3.2-homework-camera`, based on staging `4a01df0` after #250 merged. See [prerequisites and implementation](../../homework-capture.md).

## Passed

- Full `test lint`, normal application ID. Debug and Release each passed 164 tests with zero failures, errors or skips. Mock-backed integration tests were enabled.
- Thirteen new tests cover compression/orientation, malformed images, multipart uploads, redirect refusal, receipt ownership, durable queued state, connection-failure retry, rejection preservation, foreign-Hub bindings, replacement identity, canonical-ID reconciliation and recovery when another queued file is missing.
- All 67 shared contract/mock tests passed.
- Offline invariants passed. Room schema stays at version 2.
- The separate test APK built successfully as `org.lara.app.sprint32homework`; build output is in `phone-build.txt`. The current build is installed in the separate phone test app.

`test-lint.txt`, `test-counts.txt`, `shared-checks.txt` and `invariants.txt` contain the results. The image decoder's `incomplete input` diagnostic is expected from the intentionally malformed-image recovery test, which passes. Early setup and sampling failures were corrected before this successful checkpoint.

## Phone verification

Xiaomi 2311DRK48G, isolated `org.lara.app.sprint32homework`, with the contract mock reached through USB ADB forwarding. Passed actual camera capture, denied-permission fallback, gallery selection, offline cold relaunch, queued-work sign-out warning and retention, sign-in recovery, automatic upload and replacement of the existing submission. The physical camera JPEG was 1080×1440 and 112,387 bytes. Its full-resolution private capture original was removed. Public evidence uses synthetic page images.

`device/compression-report.txt` records native Android 12MP compression measurements. The final recorded three runs were approximately 100, 88 and 85 ms. The phone reports 7,495,244 kB of physical memory. A post-capture sample showed total PSS 164,904 kB. Budget-device timing and peak profiling remain pending.

`device/gallery-upload.txt` records that the gallery replacement reached the Hub byte-for-byte with exactly one submission. The source gallery file remained through import and was removed during test-fixture cleanup. `device/foreground-reconnect.txt` records automatic replacement upload in 9.03 seconds, with the app kept open and Wi-Fi unchanged. The successful Hub handshake now schedules the fresh reconnect work instead of waiting behind an earlier offline retry.

The screenshot pairs show queued work, permission fallback, the sign-out warning, a replacement draft requiring Turn in, the synced result and enlarged text. Large-text verification exposed fixed-height navigation clipping; navigation height now follows two lines of scaled labels. System font scale is restored to 1.0, USB stay-awake to its original 0, and this task's forwarding routes and gallery fixture are removed after verification.

Compression under 500 ms on the target 3–4 GB budget hardware, peak-memory certification and a physically isolated Wi-Fi network remain pending.

The real test Hub was unreachable during the route probe, recorded in `real-hub-probe.txt`. Staging's server source maps the homework upload endpoint to its authenticated student stub. Populated assignment/upload integration awaits SERVER 3.3 (#53) and must be repeated against the real Hub.

Graded work stays read-only under the current design while the PRD's graded-resubmission question is unresolved. Gallery input is limited to a 64 MB private source copy before decoding and compression. These choices are documented for review.

## OpenCV automatic preview

The final build passed 164 Debug and 164 Release tests with no failures or skips, plus lint. Four native phone tests passed in 2.438 seconds. They cover perspective correction, enhancement, automatic previews for detected and unclear page boundaries, cancellation cleanup, and preservation of saved queued work. `scanner/capture-first-device.txt` contains the final native results.

Capture and gallery import now open an enhanced preview automatically. Adjust corners is optional. When page edges are unclear, the full image is enhanced. The regression test exercises this through the homework view model and verifies that accepting a preview creates a JPEG under 800 KB without queuing it before Turn in.

The synthetic source and processed outputs are in `scanner/`. The recorded final JPEG is 1098×851 and 65,673 bytes. Detection plus two renders took about 720 ms on this phone. This measures the scanner pipeline, separate from the JPEG-compression timing above. The bundled OpenCV universal debug APK is approximately 80 MB.

Capture-first processing is the final design after phone testing exposed instability in a live outline under phone-cast shadows. The camera retains a static framing guide, and processing starts after capture or gallery import. The regression covers a mostly shadowed plain sheet, bright surrounding objects, and rejection of internal table borders. A private shadowed capture was detected and its full-sheet crop inspected. It is excluded from repository evidence. Native regression coverage passes. Target budget-phone timing and peak-memory profiling, physical isolated-LAN testing and real-Hub submission integration remain pending.

A live preview experiment exposed a drawing callback conflict and preview overflow, both corrected during testing. The live scanner was subsequently removed at the user's request. Its bitmap sampling API and strings were removed too. An overlapping Gradle validation briefly failed because its generated resource file was replaced by the other build. Final validation runs sequentially.


## Follow-up crop regression

A later phone photo exposed a merged paper/desk candidate. Returning non-null corners did not establish a correct crop. The detector now checks contrast immediately inside and outside all four candidate edges, and retains ordinary and brighter paper masks alongside the shadow-tolerant mask. The native regression also accepts expected paper corners for private device calibration, checking each normalized coordinate within 0.06. The failed phone photo was compared against its measured paper boundary, and its corrected crop was visually inspected. No private photo is included in this evidence folder. The synthetic regression includes surrounding gray shelf/side regions that previously favored a larger background candidate, separately from the shadow case.

The first follow-up check caught the original bad crop, but a fresh capture still fell back to the full image. Its irregular boundary needed the brighter mask and fitted-rectangle candidate. The final detector passed corner checks for both failed captures and the earlier strong-shadow capture. The fresh-capture suite passed four tests in 2.398 seconds, and each additional saved-photo calibration passed separately. See `scanner/fresh-boundary-device.txt`, `scanner/previous-boundary-device.txt` and `scanner/shadow-boundary-device.txt`. Corrected private crops were inspected for retained page content and excluded furniture. More lighting conditions and target budget hardware remain unverified.

Final crop-fix validation passed 164 Debug and 164 Release tests with zero failures or skips, lint, and offline invariants. The mock services were enabled. Synthetic detection plus two renders took 719.675692 ms on this phone. This includes two preview renders and is distinct from the JPEG-compression benchmark.
