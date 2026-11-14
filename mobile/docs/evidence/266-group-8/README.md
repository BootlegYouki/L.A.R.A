# Group 8 — L.A.R.A-only screens

Part of #266. Branch: `mobile/266-group8-lara-screens`; implementation base: staging `2a861ba` (Group 7 merged). This PR is intended to finish MOBILE 3.1.2's existing-screen redesign scope. It does not implement the deferred features below or automatically close #266.

## Scope and behavior review

Sign-in/learner registration, Hub selection/manual connection, teacher join approval, handout reading, video playback and Save for Home reuse the established app bars, cards, rows, sheets and banners. See the [inventory and design decisions](../../classroom-layout-group8.md).

Both sign-in and learner registration replace app-rendered keypads with a standard masked Android numeric-password text field. ASCII-digit filtering, the four-digit limit, ID/name bounds, transient credential state, clearing on submit/mode change, existing validation and busy/Retry-After gating are retained. Next moves ID/LRN → name (registration) → PIN; Done/Enter submits through the same guard as the button; Back dismisses the keyboard. The footer hides while the keyboard is open, and the app bar also hides for a landscape keyboard to preserve editing space at 200% text.

The final production diff is confined to UI, strings and a bundled icon. Auth ViewModel/repository/API, token storage, authorization, endpoint validation/transport, discovery lifecycle, Room schema/DAOs, sync and offline persistence are unchanged. Approval decisions, reader rendering/caching, Media3 ownership/transport/fullscreen and download/pause/resume/removal callbacks are preserved. There are no functional changes beyond the authorized presentation and PIN input interactions.

**Updated Tech Lead instruction (13 November 2026): real-Hub testing was not performed and is not required for this UI-only scope.** This supersedes the earlier real-Hub gate for this PR; it is not a claim that real-Hub testing passed. Required CI checks and Lead review still apply.

## Deferred feature mapping

| Redesign group | Verified feature issue | Status in this PR |
| --- | --- | --- |
| Group 4: teacher announcement posting | [MOBILE 3.4 — #60](https://github.com/BootlegYouki/L.A.R.A/issues/60) | Deferred to the feature, not implemented or abandoned |
| Group 4: materials, assignments, topics and attachments | [MOBILE 3.5 — #127](https://github.com/BootlegYouki/L.A.R.A/issues/127) | Deferred to the feature, not implemented or abandoned |
| Group 6: To-do and Due soon | [MOBILE 3.7 — #129](https://github.com/BootlegYouki/L.A.R.A/issues/129) | Deferred to the feature, not implemented or abandoned |

MOBILE 3.4 covers announcements; the rest of Group 4 maps to MOBILE 3.5. These groups will not be implemented under MOBILE 3.1.2. Their UI will accompany the underlying functionality using the established design system. [Issue snapshots](publication-issue-status.json) preserve the checked mapping and #276 status.

## Before/after phone evidence

Genuine USB-connected Infinix X6731 / Android 14 captures, using the isolated `org.lara.app.group8` package and synthetic data. The regular app's data was preserved. `before/` is unchanged staging production UI with only the fixture harness added. Normal final captures use the original 0.85 font scale; the enlarged portrait/landscape captures use 2.0. Images are raw screenshots, without editing or generation. Group 8 has no direct Classroom reference screenshot.

| Surface | Before | Final |
| --- | --- | --- |
| Sign-in | [Before](before/sign-in.png) | [After](final-after/sign-in.png) |
| PIN input | [Custom keypad](before/pin-keypad.png) | [System field](final-after/pin-system-field.png) |
| Learner registration | [Before](before/registration.png) | [After](final-after/registration.png) |
| Manual Hub connection | [Before](before/hub-manual.png) | [After](final-after/hub-manual.png) |
| Nearby Hubs | [Before](before/hub-found.png) | [After](final-after/hub-found.png) |
| Join approval | [Before](before/approval-pending.png) | [After](final-after/approval-pending.png) |
| PDF handout | [Before](before/reader-pdf.png) | [After](final-after/reader-pdf.png) |
| Cached lesson text | [Before](before/group8-text.png) | [After](final-after/group8-text.png) |
| Video playback | [Before](before/video-playing.png) | [After](final-after/video-playing.png) |
| Save for Home | [Before](before/save-progress.png) | [After](final-after/save-progress.png) |

The capture directories also cover busy/rate-limit/validation, connection errors, offline, loading, empty, missing-file, fullscreen and download progress/paused/failed/saved states. Reader/video fixtures include the existing class app bar; they are controlled component presentations, not full navigation-shell captures. Live mock checks are distinguished from presentation fixtures below.

| Stable blank-PIN system keyboard | Sign-in | Learner registration |
| --- | --- | --- |
| Original text size | [Sign-in](final-pin-normal/sign-in-pin-system-keyboard.png) | [Registration](final-pin-normal/registration-pin-system-keyboard.png) |
| 200% portrait | [Sign-in](final-pin-font200/sign-in-pin-system-keyboard.png) | [Registration](final-pin-font200/registration-pin-system-keyboard.png) |
| 200% landscape | [Sign-in](final-pin-landscape200/sign-in-pin-system-keyboard.png) | [Registration](final-pin-landscape200/registration-pin-system-keyboard.png) |

There are **131 final raw captures**: 45 normal, 40 enlarged portrait, 40 enlarged landscape and six dedicated settled-keyboard captures. All **36 original before captures are byte-for-byte unchanged** ([preservation check](final-preservation-check.json), [before hashes](resume-before-sha256.json), [final capture hashes](final-capture-sha256.txt)). [Before-device log](before-device.txt) records the baseline fixture run. Stable keyboard captures above are the presentation reference; some earlier interaction screenshots caught IME transition frames.

## Actual verification

| Check | Result | Evidence |
| --- | --- | --- |
| Debug assembly | Passed | [Complete Gradle run](final-verification-test-lint-build.txt) |
| Lint | Passed: 0 errors, 48 warnings | [Report](final-lint-results.txt); [final capture-harness lint](final-pin-capture-lint.txt) |
| Debug unit suite | 194 passed, six skipped, one failure / 201 | [Results](final-unit-results.json) |
| Release unit suite | 194 passed, six skipped, one failure / 201 | [Results](final-unit-results.json) |
| Shared contract/mock suite | 71 passed | [Log](resume-shared-tests-unrestricted.txt) |
| Offline invariants | Passed | [Publication recheck](publication-invariants.txt) |
| Normal phone/mock flow | Six of six passed | [Log](final-after-device.txt) |
| 200% portrait layout | Four of four passed | [Log](final-font200-device.txt) |
| 200% landscape layout | Four of four passed | [Log](final-landscape200-device.txt) |
| Settled blank-PIN capture check | Passed in each of three layouts | [Results/settings](final-pin-capture-results.json); [normal](final-pin-normal-device.txt), [portrait](final-pin-font200-device.txt), [landscape](final-pin-landscape200-device.txt) |
| Focused hardware key events | Passed | [Log](final-hardware-device.txt) |
| Isolated APK/test builds | Passed | [UI build](final-keyboard-layout-build.txt), [final capture-harness build](final-pin-capture-build.txt) |

The aggregate `test lint assembleDebug` command **failed**, because both variants failed `SchemaParityTest.allSixteenTablesMatchActualContractIncludingConstraints`: Room lacks `enrollments.created_at`. [#276](https://github.com/BootlegYouki/L.A.R.A/issues/276) remains open at publication review. Terminal logs omit trailing blank lines only. No tests are suppressed, no deadline/assertion was relaxed, and no schema repair is included. Ready for review does not mean CI is green; required CI results remain pending after push.

The normal device run covers filtering/masking/clearing, callback values, keyboard dismissal, hardware Tab/Enter and incomplete-PIN gating, plus live mock discovery, sign-in, learner registration, sequential approval, handout reading, advancing streamed video and a fresh Save for Home download. The four-case enlarged suites cover presentation and input interaction without live network calls. The settled-keyboard capture test was added afterward as the seventh test in the final harness and passed separately in all three layouts. [Phone settings](final-phone-settings.json) and [capture settings](final-pin-capture-results.json) confirm font scale, rotation and stay-awake settings were restored.

### Auth timeout investigation

Earlier workspace focused auth runs hit the unchanged five-second registration wait ([first attempt](final-auth-build-lint.txt), [isolated retry](auth-unit-retry.txt)). The timeout was **not established as a baseline failure**. Untouched staging and the final workspace both passed the same two focused tests using Java 17, one worker, standard application ID and the same test temp directory: [staging log](resume-untouched-staging-auth.txt)/[XML](resume-untouched-staging-auth.xml), [workspace log](resume-current-focused-auth-lint.txt)/[XML](resume-current-focused-auth.xml). [235 checked source/build files](resume-staging-source-verification.json) match the untouched base with no extra production sources. The final full suites also pass both auth tests. The intermittent timeout's root cause is unproven; the earlier failures are retained, not counted as passes.

The handoff's temporary clean-copy run also completed: 194 passed, six skipped, one schema failure, with both auth tests passing ([recovered results](recovered-clean-copy-results.json), [log](clean-copy-current-tests.txt)). Its [overlay manifest](clean-copy-current-overlay.json) identifies the previous Group 8 overlay; it is not mislabeled untouched staging. [Untouched-staging schema check](staging-schema-check.txt) reproduces the audit-column mismatch.

## Design and review limits

L.A.R.A tokens, bundled Nunito/Phosphor, English resources and 52dp/56dp targets remain authoritative. UI UX Pro Max guidance was consulted locally; the skill, installation files, caches, scripts and configuration are excluded from this PR.

Deliberate layout deviations: a single native masked PIN field replaces design-system section 5.2's individual boxes at the user's request; manual Hub connection precedes discovered results; authentication hides the footer while editing and the app bar for a landscape keyboard; shared lesson headers add no second system-bar inset. Representative captures of every changed surface, error feedback, cached reader controls, streamed/saved video and enlarged PIN/approval layouts were visually reviewed. Full TalkBack traversal, an attached physical keyboard, WAN-unplugged multi-device discovery and 3–4 GB heap certification were not newly tested; hardware-keyboard support was exercised through injected key events. Real-Hub testing was not performed and is not required for this UI-only scope under the updated Tech Lead instruction.

## Reproduction

From `mobile/`, with Java 17 and the Android SDK configured:

```sh
./gradlew --continue --max-workers=1 -I docs/evidence/266-group-8/unit.init.gradle test lint assembleDebug
./gradlew --max-workers=1 -I docs/evidence/266-group-8/device.init.gradle assembleDebug assembleDebugAndroidTest
```

The unit init script isolates temporary test files; the device init script changes only the application ID. Start the repository mock with HTTP/WS ports 28080/28081, reverse phone ports 8080/8081, and install only the isolated APKs. Then run `python3 mobile/docs/evidence/266-group-8/resume-device-verification.py` from the repo root. This helper clears **only the isolated test package**, captures raw PNGs and restores settings in `finally`. The current full class contains seven tests; the recorded six-case functional run predates the separate capture-only addition. Do not overwrite original evidence when reproducing. The fixture harness refuses to run against the regular app package. No filled credentials or real learners' personal data belong in captures.

Final source hashes: [source-sha256.txt](source-sha256.txt). Publication includes only explicit Group 8 source/test/docs paths and the evidence linked here; local installation material and duplicate development captures remain outside the commit.
