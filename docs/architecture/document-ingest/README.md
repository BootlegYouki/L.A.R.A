# Architecture: document ingest and the tutor options

A reviewed diagram set for the Lead (2026-11-20). It shows the system as it is on `origin/staging`, what the document pipeline does for retrieval, and the two undecided tutor designs. Nothing here changes a contract, a rule or code; it is a discussion aid.

Open the `.html` files in a browser. They are drawn with the `diagram-design` skill and the L.A.R.A profile, use the bundled Nunito font, and need no network.

## Status words
| Word | Meaning |
|---|---|
| BUILT | The code is on `origin/staging` |
| PARTLY BUILT | Hub code exists; the listed part is still pending |
| PLANNED | In the plan or contract, no code yet |
| NEW | New in this proposal, nothing built |
| OPTION A / OPTION B | The two tutor designs, both undecided |

## Diagrams
| # | File | What it shows |
|---|---|---|
| 1 | [`01-system-architecture.html`](./01-system-architecture.html) | The Hub, the clients, discovery, the remote tunnel and the document pipeline, each box marked built, partly built, planned or new. The tutor path is drawn twice, with the Option B conflict as a visible warning and the hybrid only as a note. |
| 2 | [`02-architecture-delta.html`](./02-architecture-delta.html) | Before and after with a ledger: the mock hub is removed, the Hub becomes headless with a 6-digit PIN, the pipeline gains cleanup and heading-aware chunks, and the on-device tutor is added. It stays inside the delta budget, so it names only seven changes. |
| 3a | [`03a-document-ingest-flow.html`](./03a-document-ingest-flow.html) | Swimlane from the teacher upload to chunks stored on the Hub and pulled by the clients. Failure paths: not a class teacher, no text found; scanned PDFs and OCR are only a grey out-of-scope note. |
| 3b | [`03b-ask-and-answer-flow.html`](./03b-ask-and-answer-flow.html) | Swimlane of a learner question: Option A on the Hub (quiz check, queue, chunk pick, prompt, stream) against Option B on the device (local lockout check, BM25, local model). Failure paths: Hub unreachable, quiz in progress, queue full, no chunks. Split from 3a because one page would pass the node budget. |
| 4 | [`04-data-model-slice.html`](./04-data-model-slice.html) | Column-level slice of `materials`, `material_chunks`, `ai_chat_messages` and `sync_revisions` from the real SQL, plus a proposed `material_vectors` table. Proposed columns (`page`, `text_status`) and the proposed table are blue; everything else is copied from the schema. |

## Sources
- `CLAUDE.md` §1.4, §3, §5; `rules/*.md`; `contracts/schema/server_master.sql`, `client_offline.sql`, `openapi.yaml`
- `server/docs/document_chunking.md`, `server/docs/slm_inference_queue.md`, `server/backend/src/`
- `docs/thesis/chapter-3/3.2.1.6-system-architecture.md`, `docs/architecture/developer-ecosystem-and-workflow.md`
- PR #337 (`experiments/rag-prototype`), PR #349 (`experiments/mobile-slm-rag`). `experiments/ocr-prototype` does not exist on `origin` yet, so the cleanup and chunk settings are drawn as "pending".

## Assumptions marked in the diagrams
- The Hub serves the Option B model file over REST on the LAN.
- A learner can edit their own local SQLite file, so a local quiz lockout is weaker than the Hub saying no.
- `material_vectors`, `material_chunks.page` and `materials.text_status` are proposals, not contract fields.
- Desktop teacher upload is drawn as "no upload code found" (planned).
- The OCR timings (DeepSeek-OCR about 35 s per page, Tesseract about 3.6 s per page) come from the Lead briefing; they are not in the repo.
