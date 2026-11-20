# On-device documents, model file and tutor lockout (PLANNED)

> **PLANNED, not implemented, not covered by tests.** Nothing in this file is part of the contract yet. It becomes `contracts/openapi.yaml`, `contracts/schema/*.sql` and tests in a `contract-change` PR when the post-defense issues start, and only then may the Server, Mobile and Desktop teams build against it. Until then do not code against it, and do not add its routes to `openapi.yaml`: `tests/test_contract_coverage.py` requires every OpenAPI path to exist in `server/backend/src`.
>
> Decision: [`docs/decisions/2026-11-20-no-ai-on-hub.md`](../../docs/decisions/2026-11-20-no-ai-on-hub.md). Product requirements: PRD FR-3.10 to FR-3.14 and FR-7.1 to FR-7.6. Marks: **BUILT** (in the contract and on `staging` today), **PLANNED** (this file only), **OPEN** (needs the Lead or an experiment).
>
> The draft parsing and chunking spec is **not** planned, it is already a contract file: [`contracts/ai/chunking_spec.md`](../ai/chunking_spec.md) and [`contracts/ai/fixtures/`](../ai/fixtures/) (numbers PENDING).

## 0. What the Hub does and does not do

The Hub stores and serves files: the teacher's handouts and videos, and the tutor's model file. **It does not read documents, keeps no chunks and runs no AI.** The tutor and the document parsing run on the devices (`AGENTS.md` section 5).

## 1. Teacher upload of a PDF or Word handout

* **BUILT, nothing new.** `POST /api/materials` (multipart, 250 MB cap) takes the file as an ordinary handout, the Hub stores it, and it syncs and downloads like any handout (`GET /api/materials/{id}/download`, `materials.download_url`). **PLANNED adds no upload route.** Desktop and mobile teacher upload screens are client work.
* Supported for the tutor: PDF with a **text layer** and Word `.docx`. **Out of scope for now:** scanned or image PDFs and OCR (measured too slow on a CPU). Such a file uploads normally and the tutor does not use it.
* **Teachers and learners see nothing about parsing or retrieval.** No upload response, list or screen carries a parse status, a chunk count or a "text not found" warning. The only AI-related thing a teacher sees is the "Allow AI tutor" switch (section 4).
* **Today (BUILT): the Hub-made reader chunks.** The Hub extracts and chunks every PDF and `.docx` on upload (`server/backend/src/routes/materials.rs` calls `services/document_parser::extract_chunks`, then `db/material_chunks::insert_all`, #10), serves them at `GET /api/materials/{id}/chunks` and sends them in `POST /api/sync/pull` as `material_chunks`. They come from the same uploaded file the clients download. They exist for **reading**, not for the tutor. Who uses them:
  * **Desktop** `desktop/src/pages/ClassPage.tsx` (lines 36 to 38) shows a document's text **only** from these chunks, and its "Save for Home" button is disabled when a material has none; `desktop/src-tauri/src/account.rs` (lines 771 to 781) reads them from the local database and refuses with `lesson_text_unavailable` when there are none; `desktop/src-tauri/src/sync.rs` (the `material_chunks` entity) syncs them.
  * **Mobile** `mobile/.../ui/screens/classwork/MaterialReader.kt` renders PDFs from the downloaded file (`PdfRenderer`) and **already parses `.docx` on the device** from the file (`docxBlocks`, lines 94 and 238); the Hub chunks are only a text fallback (lines 208 to 217) and the gate for the AI button (line 160, `chunks.isEmpty()`).
  * **Verdict (kept separate, then removed; to be confirmed by the Lead):** this path is **not reused** for the tutor (it has no page, no spec version, covers every handout instead of allowed ones, and the Hub must stop making chunks) and **cannot be removed yet**, because the desktop reader and Save for Home depend on it. So: the tutor's chunks live in a **separate** local table, `tutor_chunks` (section 2), and the Hub reader chunks stay, deprecated in `openapi.yaml`, until both clients read handouts from the file itself (a desktop issue; mobile mostly does). Only then does a contract-change drop the route, the pull entity and the Hub table, and the server code goes.

## 2. Document parsing happens on the device, lazily, on the first AI tap

**Trigger:** the learner's tap on the AI button to open the chat. Nothing is parsed in the background, at sync time or on the Hub. Flow (the rule is `rules/socratic-ai-guardrails.md` section 3.4):

1. The **quiz lockout is checked first**, before any parsing or model loading.
2. If nothing in the class is allowed the AI button is hidden or disabled with the not-allowed message (section 4).
3. For each allowed material in the chat's scope whose chunks are missing or stale, the device extracts the text of its PDF or `.docx` (the file must already be on the device), cleans it and chunks it per [`contracts/ai/chunking_spec.md`](../ai/chunking_spec.md), and stores the chunks in `tutor_chunks`. If the file is not downloaded and the Hub is unreachable, show "download the material first".
4. The first tap shows a **progress state** (parsing, then loading the model) that the learner can **cancel**; nothing half-made is kept.
5. **Parse, then load the model, one after the other, never together** (3 to 4 GB phones).
6. The learner then chats locally with the on-device model, fully offline.

* **PLANNED, local table `tutor_chunks`** (client-only, never synced, the Hub keeps none): `id` (deterministic UUIDv5, see the spec), `material_id`, `order_index`, `heading`, `text`, `page` (PDF page, NULL for Word), `token_estimate`, `spec_version`, `file_hash`, `created_at`. It is separate from the Hub-made reader table `material_chunks` (section 1), which has a different lifecycle (synced, every handout, no spec version) and the same unique key, so mixing them would collide. **OPEN:** once the reader no longer needs the Hub chunks the two could be merged; not decided.
* **Cache:** chunks are cached per material, keyed by `file_hash` plus `spec_version`, and reused on later taps. They are re-made only when the teacher replaces the file (the hash changes), the switch goes off and on again, or the spec version changes (an app update). They are deleted when the switch goes off or the material is deleted.
* **Scope of one chat (OPEN, for the Lead):** the working default is all allowed materials of the class in one chat; the alternative is one chat per material.
* **Needs the file on the device.** A document the device has not downloaded has no chunks. **OPEN:** whether the client may fetch a handout for this on demand (and how that interacts with the 2.0 MB/s per-client cap) or only tells the learner to download it first (the confirmed behaviour is the message).
* **Starting findings, not verified on the target platforms:** see section 8 of the spec (default PDF reading order, NFKC, de-hyphenation, header and footer removal, soft heading boundaries at about 250 words). The numbers are PENDING and settled by the first task of the client issues.
* **Failure is silent to people.** No text, a failed extraction or a limit hit gives no chunks, one line in the device's own log, and nothing on any screen.
* **Cost is paid on every device, on the first tap.** Forty learners each parse the same file. **OPEN, not measured:** the memory and time of text extraction on 3 to 4 GB phones, so how long the first-tap progress state lasts is unknown. PDFium on Android is the likely library; avoid AGPL libraries (MuPDF is excluded); use pure-Rust crates on desktop. The measurement is a task for the phone prototype (issue [QA PD1]).
* **Two implementations can drift.** Mitigation: one spec, golden fixtures, and a rule that both clients pass the same fixtures in their own tests (`rules/socratic-ai-guardrails.md` section 3.4).
* **Vectors:** retrieval is BM25 only. Chunk vectors would need an embedding model on every device and are a later option, not planned now (the earlier Hub-side vectors are dropped).

## 3. Model file listing and download

The tutor's model is a GGUF file. The Hub stores it; each device downloads it once.

* **PLANNED, listing:** a bearer-token route (for example `GET /api/models`) returning, per available file: `id`, `file_name`, `size_bytes`, `sha256` and a human label. The client verifies `sha256` after download. The listing says nothing about which model is best; the model choice is OPEN (`AGENTS.md` section 5.3).
* **PLANNED, download:** a bearer-token route (for example `GET /api/models/{id}/file`) with HTTP Range, `206 Partial Content`, `ETag` and `If-Range` / `If-Match` exactly as the video download already behaves, so a phone resumes after a Wi-Fi drop. `?token=` follows the existing media-route rule only if a download manager needs it.
* **How a file gets on the Hub:** an admin copies it in from a USB drive through the Hub console. No internet, ever (`AGENTS.md` section 3.1). **OPEN:** exact console flow and storage folder.
* **Admin visibility:** the Hub admin console shows which model file is published (name, size, checksum, whether it is available). Nothing else about the tutor appears on the Hub.
* **Limits:** the per-client **2.0 MB/s** cap (`rules/networking-and-lan.md` section 3) applies unless the Lead exempts model downloads; at 2.0 MB/s a 700 MB file takes about six minutes for one client. **OPEN:** exempt or not, and whether the tunnel may carry the download (the tutor itself needs no Hub once the file is on the device).
* The client stores the file in its own private storage; it is never uploaded back.

## 4. Teacher switch: "Allow AI tutor" (`materials.ai_allowed`)

The tutor exists only where the teacher allows it (decision of the Lead, 2026-11-20). **To confirm with the Lead:** the granularity is per material (the Lead said "for example the learning materials"); a class-level master switch is an open question.

* **PLANNED, field:** `materials.ai_allowed INTEGER NOT NULL DEFAULT 0 CHECK (ai_allowed IN (0, 1))` in both SQL files and `ai_allowed` (boolean) in the `Material` schema, so learners' devices receive it. **Default OFF for every new material and for every existing one.** It is an ordinary synced field: the Hub writes a `sync_revisions` row in the same transaction as any change, and it reaches devices in the normal pull.
* **PLANNED, change:** `PATCH /api/materials/{id}` (teacher; it already renames and moves a material) accepts `ai_allowed`. The Hub authorises by class ownership like every material edit: only the class's owner or a co-teacher; a learner gets 403. Nothing else about the tutor is stored or computed on the Hub. Upload (`POST /api/materials`) takes no `ai_allowed` (always starts at 0).
* **Meaning for videos and non-document files:** the field exists on every material but only PDF and `.docx` handouts can feed the tutor; the teacher UI shows the switch only for those.
* **Client enforcement:** a device retrieves only from allowed materials, parses and stores chunks only for allowed materials and only on the learner's first AI tap (section 2), and deletes a material's tutor chunks when the switch goes off or the material is deleted.
* **Sync latency (documented, accepted):** a toggle reaches a device at its next sync. An offline device keeps the old value, so it may keep using a material the teacher just turned off, or miss one just turned on, until it syncs.
* **Strings plan (English only, in `strings.xml` and the desktop dictionary; wording for the Lead's read):**

| Where | Text |
|---|---|
| Teacher switch label | Allow AI tutor |
| Teacher switch helper | Learners can ask the AI tutor questions about this handout. Off until you turn it on. |
| Learner opens the tutor on a material that is not allowed | Your teacher has not turned on the AI tutor for this handout. |
| A class where no material is allowed (tutor tab or button) | The AI tutor is not available in this class yet. |
| Allowed material whose file is not on the device and the Hub is unreachable | Download this handout first, then ask the AI tutor. |
| Model not on the device and the Hub unreachable | The AI tutor needs the classroom Hub |
| Device cannot run it | AI is not available right now |
| Allowed material, question not covered | This lesson does not cover that question. Please return to your lesson or ask your teacher. |

The "not allowed" message is distinct from the decline sentence, which is only for an allowed material that does not cover the question. **OPEN:** what a learner sees for a chat opened without a material.

## 5. Quiz attempt state for the local lockout

The tutor is locked from the learner's own `IN_PROGRESS` attempt (`rules/quiz-and-anti-cheat.md` section 1). No new route is planned:

* **BUILT:** `POST /api/quizzes/{id}/begin` creates the attempt and returns `attempt_id` and `started_at`; the client keeps an `IN_PROGRESS` row in its local `quiz_attempts` (`client_offline.sql`), `POST /api/sync/pull` returns the caller's own attempts, and `GET /api/quizzes/active` gives the active quiz.
* **PLANNED, to verify before promoting:** the pull response includes the caller's own `IN_PROGRESS` attempt (for a learner who began on another device or after clearing the app), and the client treats any local `IN_PROGRESS` row as "quiz active" until the Hub says `SUBMITTED`. If the pull does not carry it, the contract-change PR adds that single guarantee.
* **Known limit (accepted):** a learner can edit their own local database, and a device that has not synced does not know about the attempt, so this lock is weaker than a Hub refusal. See the decision record.

## 6. Removed from the contract by this change

`EVENT_AI_CHAT_REQUEST`, `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM` (their event files), the error codes `AI_QUEUE_FULL`, `AI_NOT_AVAILABLE` and `QUIZ_IN_PROGRESS`, and the Hub-side meaning of the tutor lockout. The legacy server tables `ai_chat_messages` and `material_chunks` and the chunks route stay, marked legacy or deprecated, until the Hub AI and parser code is removed after the defense, and the readers no longer need them (they must change together with the server migration).
