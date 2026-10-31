# MOBILE 2.5 verification

Base: `6b174a6` (latest Mobile 2.4 wiring commit).

- `test lint assembleDebug assembleDebugAndroidTest`: passed in 2m 38s.
- Debug: 94 tests, zero failures, zero skipped. Release: 94 tests, zero failures, zero skipped.
- The local mock flags enabled both the approval protocol and sync integration tests. Standard CI without those flags skips the two local-mock-only tests; other tests run normally.
- Shared transaction/queue suite: 18 tests, with an AndroidJUnit4 wrapper compiled into the test APK.
- Actual local mock: pending enrollment IDs and teacher-visible names saved; approval exposes the learner class; a queued comment receives a SYNCED receipt; a live announcement reaches Room in 66 ms (debug) and 58 ms (release).
- Shared repository tests: 66 passed in 15.868s.
- No Android device was attached. Instrumented execution, device heap, Android background scheduling on LAN without WAN, and real-phone offline relaunch remain unverified.
- The mock push endpoint is learner-only. Teacher-authored queued comments remain retained on 403; that mock/contract mismatch needs Lead alignment. No shared files were changed.
- Existing unknown GradeReceipt identities are retained in metadata rather than fabricated. Later quiz/grades integration must resolve/display them.

This evidence does not declare Mobile 2.3 or 2.4 closed. Their final feature integration and phone acceptance remain separate.
