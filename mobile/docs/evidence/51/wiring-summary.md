# Mobile 2.4 wiring verification

- Full `test lint assembleDebug`: passed, 2m 25s. Debug and release suites each discovered 74 tests: 73 passed and the opt-in local-mock test was skipped in the standard run.
- Isolated mock-Hub run with `LARA_APPROVAL_MOCK=1`: the opt-in test ran (1 test, 0 skipped, 0 failed), 6s build. It verified teacher/learner HELLO acknowledgements, join event, pending roster, approval event and ACTIVE learner classroom status, then rejection event and REJECTED roster status.
- Shared repository suite: all 66 tests passed, 28.306s.
- Room tests prove existing IDs and timestamps are retained, unknown enrollments are not fabricated, and a failed roster fetch leaves saved pending data readable.
- Lifecycle/refresh tests cover stopping and restarting without duplicate event consumers and a second join event arriving during a refresh.
- Class visibility test covers owner, co-teacher, unrelated teacher, malformed co-teacher metadata and archived classes.
- No physical Android device was connected. Phone UI, offline relaunch and fresh-enrollment persistence are not claimed. New canonical enrollment insertion remains with Mobile 2.5; the learner UI integration remains with Mobile 2.3.
