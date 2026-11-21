# Historical AI evaluation evidence (#363)

This archive preserves previously uncommitted output related to the closed feasibility spike #73. It adds no tutor runtime or model selection. The original 15 files are copied byte for byte from the working tree; `manifest.json` records their sizes and SHA-256 checksums.

## Contents

| Result directory under `server/ai_eval/results/` | JSONL reply records | Run records |
|---|---:|---:|
| `sim-school-pc` | 1134 | 21 |
| `sim-school-pc-cache-ram-0` | 108 | 2 |
| `en-16gb-reap15b` | 116 | 4 |
| `en-16gb-reap15b-en-prompt-side-test` | 29 | 1 |

`runs.json` records hardware, model identity, settings, prompt hashes and measured run statistics. `replies.jsonl` contains raw model output; the English runs include their prompt snapshots. `sim-school-pc/blind_key.json` maps blinded reply IDs to models. The two CSV sheets each contain 377 unique reply IDs matching that key and the same reply text after normalizing CRLF/LF line endings, with all four scoring columns blank. These are scoring templates, not completed independent ratings.

## Interpretation and limits

- These are historical experiments. Some prompts and outputs use Filipino or Taglish and predate the current English-only refusal rule. Treat prompt hashes and snapshots as provenance, not as the current canonical prompt.
- `en-16gb-reap15b-en-prompt-side-test` is exploratory output. It does not approve a system-prompt change.
- `sim-school-pc` uses a development laptop with CPU settings intended to approximate school hardware; it does not prove performance on a physical school Hub.
- The saved English-run shell script contains machine-specific paths, an old branch switch and cache settings. It is retained as provenance; inspect it before adapting it. It was not executed for this archive.
- No inference was rerun and no quality pass rate, scorer agreement, current prompt compliance, model approval or deployment readiness is claimed. Raw replies can include answer leaks and other failed behavior; they are evidence, not production responses.

## Archive validation (2026-11-21)

All JSON and nonempty JSONL records parsed successfully. Both CSV sheets have unique IDs covering the blind key exactly, their reply text matches it after normalizing CRLF/LF line endings, and their score columns are unfilled. All 15 source files matched the source working-tree bytes. No model weights, executables, runtime code, contract files or canonical prompts are included.

To check an archived file on Windows, run `Get-FileHash -Algorithm SHA256 <path>` from the repository root and compare with its entry in `manifest.json`. A successful integrity check establishes file identity, not evaluation quality. Re-running the model requires separately installed weights, llama-server and the matching historical runner/settings.
