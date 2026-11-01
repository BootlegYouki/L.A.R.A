# Mobile 2.5 follow-up fixes

## Changes

- Preserve the authenticated user's and authorized teacher-roster identifiers when a valid PublicUser update omits the optional identifier. New supplied values remain authoritative. A learner's foreign-profile identifiers are still stripped, including previously cached values.
- Probe Hub reachability immediately on network-change hints, rather than waiting for the ten-second periodic fallback. Restart the foreground shared socket on those hints even if the last reachability value remained true.
- Give reconnect work an independent replaceable unique chain, so an offline retry chain cannot postpone it. Existing queued work stays in Room.
- Share the actual-mock test between Robolectric and Android, including the network-regained signal/probe/pull/Room path.

## Automated results

- Full test, lint, debug APK and Android-test APK build: BUILD SUCCESSFUL (5m 13s, isolated test copy).
- Debug and release: 108 passed each, no failures or skipped tests; both opt-in mock tests enabled.
- Targeted tests cover identifier retention/replacement/privacy, prompt network-hint probing and a real WorkManager reconnect job starting while an older job remains backed off.
- Live computer mock benchmark before the shared-test move: announcement on an established socket 57 ms; injected network-regained signal through the actual Hub probe and pull into Room 40 ms. Both tests enforce under one second. This is not a physical router-outage measurement.
- Connected Xiaomi 2311DRK48G: OK (23 tests), 16.334 seconds. Twenty shared Room sync tests, one real-mock integration flow (including the timing assertions) and two English/Filipino approval UI cases.
- Shared contracts/mock tests: 66 passed (41.409 seconds). Offline/localization guardrail passed.

## Normal phone flow

Two isolated apps used the final production code: learner org.lara.app.sprint25fixed and teacher org.lara.app.sprint25teacher. Only application ID and displayed app label differ; the regular org.lara.app was not replaced, cleared or uninstalled.

1. Seed learner ending 013 signed in, entered K7M-4QX through the real join dialog, and reached the saved pending state.
2. Removed only test USB forwards 18080/18081 and force-stopped/relaunched the learner app. The pending request survived without a blocking error.
3. Teacher signed in and synced canonical classroom/pending-enrollment rows. A newly registered synthetic learner joined while the teacher app was foregrounded; the real event opened the saved-roster sheet automatically.
4. Two teacher pending rows survived an offline cold launch, shown by the saved class and pending-count screenshot.
5. Restored test forwards and used the teacher phone's Accept action on the older seed request. The learner app became Ready to open and opened Science 4 through its normal navigation.
6. Another offline cold launch retained the approved class and allowed opening its shell. The later Stream UI is outside this issue; its placeholder does not represent a missing sync write.

USB forwards were restored afterward. Screenshots committed here contain class/status data only; roster views containing numeric seed identifiers were not published. No real learner data was used. A loaded offline learner snapshot reported total PSS 129503 KiB (about 126 MiB), Java heap summary 17040 KiB and native heap summary 23284 KiB. This is a snapshot on this phone, not target-device certification.

## Still outstanding

- The actual mock rejects teacher sync/push with HTTP 403 / FORBIDDEN. Queued teacher comments remain saved; resolving the shared mock/contract mismatch needs the Lead. No alternate endpoint or shared-file edit was added.
- Physical classroom Wi-Fi with WAN disconnected and long-running vendor/battery behavior are unverified. USB-local testing does not establish those conditions.
- A Hub restart without an Android network-change callback still relies on periodic probing/socket backoff. The timing claim is limited to the measured event/network-regained signal paths.
- No 2.5 PR or full issue-completion claim is made. The Lead's decision on 2.4/2.5 merge order remains separate from these fixes.
