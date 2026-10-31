# Issue #24 validation — 2026-10-26

The injectable shell was built on this branch with a temporary, debug-only
`MainActivity` preview entry. That source edit was restored after building; the
installed phone APK still contains the preview entry. See [navigation.md](../../navigation.md)
for integration details.
The local preview APK is `lara-shell-preview.apk` in this folder. Git ignores
APKs, so this binary stays on this machine and is not part of the PR.

## Automated checks

- `gradlew.bat test lint` on the restored source with portable JDK 17 and Android
  SDK: **BUILD SUCCESSFUL**. Full output: [android-checks.txt](./android-checks.txt).
- `assembleDebug test lint` for the preview APK: **BUILD SUCCESSFUL**. The APK
  installed over ADB and opened on a physical Android phone.
- `python -m unittest discover tests`: **66 tests passed**. Output:
  [clean-history-tests.txt](./clean-history-tests.txt); a mock media token is redacted.
- `python -X utf8 scripts/verify_invariants.py`: passed. The scanner's forbidden
  pattern check was also applied directly to all navigation/component source,
  including the new untracked files: no violations.
- All resource XML parses; all 23 English/Filipino string keys match.
- `git diff --check`: no whitespace errors.

## Phone checks over ADB

- Learner preview opened with the four tabs, offline banner and empty state.
  See [learner-phone.png](./learner-phone.png).
- Tapping Classwork opened its empty state without an error.
- Launching the teacher preview showed People and Grades.
- Launching quiz lockout showed the explanation; the AI tab reported disabled
  in the accessibility UI tree. Tapping it did not compose AI content.
- After those checks, the learner preview was reopened and left on the phone.

The phone test proves the shell UI, not actual Hub reachability, saved lesson
data, real role authentication or real active-attempt state. Those inputs still
come from later mobile issues. Filipino rendering, large-font layout, TalkBack,
tab performance and live quiz-state transitions remain for manual checks.

Because `MainActivity` was restored, a normal new build from this source opens
the original scaffold until authentication (#49) connects `ShellState`. The
installed preview APK remains useful for inspecting the four tabs.

## Clean-history migration — 2026-10-31

Recreated from cleaned staging at `2390e21`; feature commit `3bc310e` includes the previously tested discovery integration. The old staging merge was not cherry-picked.

- `gradlew.bat test lint`: BUILD SUCCESSFUL; see `clean-history-android.txt`.
- Repository suite: 66 tests passed; see `clean-history-tests.txt` (mock media tokens redacted).
- `scripts/verify_invariants.py`: passed.
- Fresh physical-phone testing was not repeated. Earlier screenshots are historical shell-preview evidence.
- Normal startup now opens Sprint 1.2 discovery. Mobile 2.2 must supply the authenticated role and collect Sprint 1.3's active-attempt flow before full session integration is complete.

## Lead review fixes

Discovery and the role shell now reuse `ui/components/DiscoveryBanner.kt`. The shell offline state uses the amber warning fill, border, warning icon, and polite accessibility announcement. Duplicate historical repository test output was removed; `clean-history-tests.txt` is the retained run.
Validation: `gradlew.bat test lint` passed (3m 2s); output in `lead-review-android.txt`. Offline/localization invariants passed. No new physical-device screenshot was captured.
