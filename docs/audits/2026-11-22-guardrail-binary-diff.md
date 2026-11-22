# Guardrail binary diff failure

The offline invariant check on PR #401 failed with UnicodeDecodeError while
decoding a forced-text Git diff. That PR introduced a fictional `.xlsx` workbook
and a bundled spreadsheet parser `.tgz`; neither suffix was in the existing binary
asset exclusions. No forbidden dependency was reported before the decoding crash.

The scanner now excludes those two archive formats alongside `.zip` and `.gz`.
Diff output is decoded as UTF-8 with replacement for unknown file types, preserving
the ASCII forbidden-dependency patterns and the fail-closed behavior for Git errors.
The forced-text scan still prevents `.gitattributes` from hiding source files.

Regression tests create real temporary Git repositories containing non-UTF-8
workbook/archive bytes, and unknown binary bytes alongside a forbidden source
dependency. They check that binary data cannot crash the scan or hide a violation.

Verification commands: `python -m unittest discover tests` and
`BASE_SHA=6c89844847030110f365cc431a3121444da29e04 python scripts/verify_invariants.py`.
The latter replays the actual diff containing the new binary artifacts.
