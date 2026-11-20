# Document pipeline and on-device tutor support (PLANNED)

> **PLANNED, not implemented, not covered by tests.** Nothing in this file is part of the contract yet. It becomes `contracts/openapi.yaml`, `contracts/schema/*.sql` and tests in a `contract-change` PR when the post-defense issues start, and only then may the Server, Mobile and Desktop teams build against it. Until then do not code against it, and do not add its routes to `openapi.yaml`: `tests/test_contract_coverage.py` requires every OpenAPI path to exist in `server/backend/src`.
>
> Decision: [`docs/decisions/2026-11-20-no-ai-on-hub.md`](../../docs/decisions/2026-11-20-no-ai-on-hub.md). Product requirements: PRD FR-3.10 to FR-3.13 and FR-7.1 to FR-7.5. Marks used below: **BUILT** (in the contract and on `staging` today), **PLANNED** (this file only), **OPEN** (needs the Lead or an experiment).

## 0. What the Hub does and does not do

The Hub parses teacher documents into lesson chunks, syncs them like any other content, and serves the tutor's model file over the LAN. It does no inference, no prompt building and no tutor traffic. The tutor runs on the device (`AGENTS.md` section 5).

## 1. Teacher upload of PDF and DOCX

* **BUILT, reuse it.** `POST /api/materials` (multipart, 250 MB cap) already takes a PDF or `.docx`, extracts the text on the Hub, and writes `material_chunks`. **PLANNED adds no new upload route.** Desktop and mobile teacher upload screens call the same route (those screens are client work).
* Supported: PDF with a **text layer** and Word `.docx`. **Out of scope for now:** scanned or image PDFs and OCR (measured too slow on a CPU). Such a file uploads normally and simply has no chunks.
* **Teachers see nothing about parsing or retrieval.** The upload response, the Classwork list and every client screen carry no parse status, no "chunks" count and no "text not found" warning. This is a product decision (`AGENTS.md` section 1.4), not an omission.

## 2. Parse status per document (Hub-only)

Recorded only for the Hub admin console and logs; never synced, never returned by a teacher or learner route.

| Field | Meaning |
|---|---|
| `material_id` | The document |
| `status` | `OK`, `EMPTY` (no text layer, for example a scan), `FAILED` (unreadable or encrypted), `TIMEOUT`, `TOO_LARGE` |
| `reason` | Short code or message for the admin |
| `chunk_count` | Chunks written |
| `parser_version` | So a settings change can re-parse old documents |
| `parsed_at` | Epoch ms |

* **PLANNED shape:** a server-only table (for example `document_parse_log`) and an admin-only listing that the Hub's own console page reads (for example `GET /api/admin/documents`, role `ADMIN`, loopback only like the other admin routes). The exact names are for the contract-change PR.
* A document with status other than `OK` has no chunks and is simply not used by the tutor. The tutor's "lesson text is not available yet" reply (`rules/socratic-ai-guardrails.md` section 3) is the only learner-visible effect.
* **PLANNED, automatic cleanup** (page furniture, hyphenation, ligature artifacts) and heading-aware chunking run inside the existing parse step. **OPEN:** the settings (target chunk size, overlap, how headings and tables are treated) are fixed from the parsing experiment `experiments/ocr-prototype` (a text-layer parsing experiment, still running, numbers pending). The Hub today chunks at most 500 tokens (`server/docs/document_chunking.md`); that value is a placeholder until the experiment reports.

## 3. Chunk records and sync

* **BUILT:** `material_chunks` (`id`, `material_id`, `order_index`, `heading`, `text`, `token_estimate`, timestamps) in both `server_master.sql` and `client_offline.sql`; `MaterialChunk` in `openapi.yaml`; chunks reach clients inside `POST /api/sync/pull` (`material_chunks`) through the existing `seq` cursor, with a class-wide `sync_revisions` row per chunk and `DELETE` tombstones when a material is deleted; `GET /api/materials/{id}/chunks` for direct reads. Cursor rules: `rules/database-and-sync.md` section 3.
* **PLANNED, additive column:** `material_chunks.page INTEGER NULL` (the source page for PDFs, NULL for `.docx`), so the grounding tag can say "page N". Nullable, so existing rows and clients stay valid.
* **PLANNED, optional vectors:** a chunk may carry a vector computed on the Hub, for retrieval that finds a chunk by meaning. Proposed as a separate table (for example `material_chunk_vectors`: `chunk_id`, `model_id`, `dim`, `vector` BLOB) that syncs as its own entity in the pull response, so clients that do not use vectors skip it. **OPEN:** whether vectors are needed at all. The prototype in PR #337 measured BM25-style keyword retrieval at 89% hit-in-budget and vector retrieval at 100% on one test PDF, and the phone prototype (PR #349) uses BM25 only. The Lead decides after the phone evaluation. If vectors are used, the embedding model, the dimension and where the query vector is computed on the device are decided with that evaluation.
* **PLANNED, re-parse:** changing `parser_version` re-parses a material. Old chunks are deleted and new ones inserted in one transaction with their revisions, so a client's next pull replaces them (tombstones for deleted ids).
* Chunks stay class-scoped: only the class's teachers and `ACTIVE` learners receive them (`rules/database-and-sync.md` section 4). They contain only the teacher's own lesson text, never an answer key.

## 4. Model file listing and download

The tutor's model is a GGUF file. The Hub stores it; the client downloads it once.

* **PLANNED, listing:** a bearer-token route (for example `GET /api/models`) returning, per available file: `id`, `file_name`, `size_bytes`, `sha256`, and a human label. The client verifies `sha256` after download. The listing says nothing about which model is best; the model choice is OPEN (`AGENTS.md` section 5.3).
* **PLANNED, download:** a bearer-token route (for example `GET /api/models/{id}/file`) with HTTP Range, `206 Partial Content`, `ETag` and `If-Range` / `If-Match` exactly as the video download already behaves (`GET /api/materials/{id}/download`), so a phone resumes after a Wi-Fi drop. `?token=` follows the existing media-route rule only if a download manager needs it.
* **How a file gets on the Hub:** an admin copies it in from a USB drive through the Hub console. No internet, ever (`AGENTS.md` section 3.1). **OPEN:** exact console flow and storage folder.
* **Limits:** the per-client **2.0 MB/s** cap (`rules/networking-and-lan.md` section 3) applies unless the Lead exempts model downloads; at 2.0 MB/s a 700 MB file takes about six minutes for one client. **OPEN:** exempt or not, and whether the tunnel may carry the download (the tutor itself needs no Hub once the file is on the device).
* The client stores the file in its own private storage; it is never uploaded back.

## 5. Quiz attempt state for the local lockout

The tutor is locked from the learner's own `IN_PROGRESS` attempt (`rules/quiz-and-anti-cheat.md` section 1). No new route is planned for this:

* **BUILT:** `POST /api/quizzes/{id}/begin` creates the attempt and returns `attempt_id` and `started_at`; the client keeps an `IN_PROGRESS` row in its local `quiz_attempts` (`client_offline.sql`), and `POST /api/sync/pull` returns the caller's own attempts (`quiz_attempts`); `GET /api/quizzes/active` gives the active quiz.
* **PLANNED, to verify before promoting:** the pull response includes the caller's own `IN_PROGRESS` attempt (for a learner who began on another device or after clearing the app), and the client treats any local `IN_PROGRESS` row as "quiz active" until the Hub says `SUBMITTED`. If the pull does not carry it, the contract-change PR adds that single guarantee.
* **Known limit (accepted):** a learner can edit their own local database, and a device that has not synced does not know about the attempt, so this lock is weaker than a Hub refusal. See the decision record.

## 6. Removed from the contract by this change

`EVENT_AI_CHAT_REQUEST`, `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM` (their event files), the error codes `AI_QUEUE_FULL`, `AI_NOT_AVAILABLE` and `QUIZ_IN_PROGRESS`, and the Hub-side meaning of the tutor lockout. The legacy server table `ai_chat_messages` stays in `server_master.sql`, marked legacy, until the Hub AI code is removed after the defense (it must change together with the server migration).
