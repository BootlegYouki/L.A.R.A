# Architecture: document ingest and the tutor options

A reviewed diagram set for the Lead (2026-11-20). It shows the system as it is on `origin/staging`, where documents are parsed and how the tutor answers; both happen on the device. Nothing here changes a contract, a rule or code; it is a discussion aid.

Open the `.html` files in a browser. They are drawn with the `diagram-design` skill and the L.A.R.A profile, use the bundled Nunito font, and need no network.

## Status words
| Word | Meaning |
|---|---|
| BUILT | The code is on `origin/staging` |
| PARTLY BUILT | Hub code exists; the listed part is still pending |
| PLANNED | In the plan or contract, no code yet |
| NEW | New in this proposal, nothing built |

## Diagrams
| # | File | What it shows |
|---|---|---|
| 1 | [`01-system-architecture.html`](./01-system-architecture.html) | The Hub as a server only (classroom, quizzes, sync, export, file server incl. the model file; no AI) and the devices that parse allowed handouts lazily, keep tutor chunks locally and run the tutor, with discovery and the remote tunnel. Boxes are marked built, partly built, planned or new; the existing Hub reader chunks are flagged as an open decision. |
| 2 | [`02-architecture-delta.html`](./02-architecture-delta.html) | Before and after with a ledger: the mock hub, the Hub parser (for the tutor) and the Hub tutor are removed; the Hub becomes headless, serves the model file and an `ai_allowed` flag; a lazy on-device pipeline and tutor are added. It stays inside the delta budget. |
| 3a | [`03a-document-ingest-flow.html`](./03a-document-ingest-flow.html) | Swimlane: a handout is uploaded and downloaded like any material; the first AI tap checks the quiz lock, the teacher switch and the local file, then extracts, cleans and chunks on the device and caches by file hash and spec version. Failure paths and the cache, nothing-allowed and scope notes are drawn; memory and time on 3-4 GB phones are unmeasured. |
| 3b | [`03b-ask-and-answer-flow.html`](./03b-ask-and-answer-flow.html) | A learner question on the device: quiz lock before any parsing, lazy parse on the first tap, model load after it, retrieval over all allowed materials of the class (open), guided grounded answer. Failure paths: quiz in progress, not allowed or no file, model or memory problem, not covered, Hub unreachable. |
| 4 | [`04-data-model-slice.html`](./04-data-model-slice.html) | Column-level slice from the real SQL: Hub `materials` and the existing reader `material_chunks`, device `materials` and `ai_chat_messages`, plus a proposed `tutor_chunks` table. Proposed: `ai_allowed`, `tutor_chunks` with `source_hash` and `spec_version`. |

## Sources
- `CLAUDE.md` §1.4, §3, §5; `rules/*.md`; `contracts/schema/server_master.sql`, `client_offline.sql`, `openapi.yaml`
- `server/docs/document_chunking.md`, `server/docs/slm_inference_queue.md`, `server/backend/src/`
- `docs/thesis/chapter-3/3.2.1.6-system-architecture.md`, `docs/architecture/developer-ecosystem-and-workflow.md`
- PR #337 (`experiments/rag-prototype`), PR #349 (`experiments/mobile-slm-rag`). `experiments/ocr-prototype` does not exist on `origin` yet, so the cleanup and chunk settings are drawn as "pending".

## Assumptions marked in the diagrams
- The Hub serves the small-model file over REST on the LAN.
- A learner can edit their own local SQLite file, so a client-side quiz lockout is weaker than the Hub saying no.
- Lead correction 2026-11-20: no AI on the Hub. Repo docs (CLAUDE.md §1.4, §5, the guardrails rule, the PRD, the thesis page) still describe a Hub tutor and are being rewritten by a lead PR.
- `materials`-adjacent proposals: `materials.ai_allowed` and `material_chunks.spec_version`; vectors are undecided and not drawn.
- The shared chunking spec and golden fixtures (`contracts/ai`) are planned, not present.
- Lead decisions 2026-11-20: no AI and no parser on the Hub, chunks are not synced, the tutor is guided and grounded (hints, cites page and heading, declines when uncovered, a few slips tolerated) and exists only for materials the teacher allows with a per-material switch, default OFF (`materials.ai_allowed`, proposed; granularity is an open question). Repo docs still say otherwise.
- Existing reader chunks (Hub parser, `material_chunks`, desktop `ClassPage.tsx`, mobile `MaterialReader.kt`) are drawn as an open decision; the diagrams keep them separate from the new `tutor_chunks`.
- Desktop teacher upload is drawn as "no upload code found" (planned).
- The OCR timings (DeepSeek-OCR about 35 s per page, Tesseract about 3.6 s per page) come from the Lead briefing; they are not in the repo.
