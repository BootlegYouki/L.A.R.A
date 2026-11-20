# Decision record: no AI on the Hub, the devices do everything (2026-11-20)

**Status:** decided by the Lead on 2026-11-20, in five steps the same day. **Scope:** product architecture. This file is the one place that explains the change; other documents link here instead of repeating it.

## The decision

The first words (the Hub keeps a parser):

> "The hub will just serve as a server and the pdf parser for the slm, of course it will handle what the original plan is like being the google classroom and the real time test."

Then, the same day, three refinements that **supersede** the "Hub parser" part:

1. **The parsing moves to the devices too.** The Hub does not parse, chunk, embed or run any AI. It is only the server.
2. **The tutor stays Socratic, but softened and grounded.** Accuracy first, a leak is a soft metric.
3. **A teacher switch.** The tutor exists only for materials the teacher allows ("Allow AI tutor", off by default).
4. **Lazy parsing.** The learner's tap on the AI button is the only trigger for parsing (confirmed flow below).

(The parsing experiment that was briefly planned to set the chunking numbers was removed by the Lead, its PR #358 closed; its findings are kept inline, unverified, in `contracts/ai/chunking_spec.md` section 8.)

## The confirmed flow

1. **Everything except the tutor** is the typical Google Classroom CRUD between teacher and student, served by the Hub: classes, stream, classwork, materials, homework, comments, grading and real-time quizzes.
2. **The tutor.** The teacher allows it per material (switch default OFF, a plain synced field). Parsing is **lazy and on the device**: when a learner taps the AI button to open the chat, that tap extracts the text of the allowed materials' PDF or `.docx` files (already on the device), cleans and chunks it per the shared spec and stores the chunks in the device's local SQLite. Nothing is parsed in the background, at sync time or on the Hub. Then the learner chats with the on-device model, fully offline.
3. **Details.** The first tap shows a progress state (parse, then model load) that can be cancelled. Chunks are cached per material keyed by file hash plus spec version and re-made only when the teacher replaces the file, turns the switch off and on, or the spec version changes; they are deleted when the switch goes off or the material is deleted. Parse and model load run one after the other, never together, to stay inside the 3 to 4 GB phone budget. The quiz lockout is checked before any parsing. If a material's file is not on the device and the Hub is unreachable, the learner is told to download it first. If nothing in the class is allowed, the AI button is hidden or disabled with the not-allowed message. **Open:** the scope of one chat, all allowed materials of the class (the working default) or one material; and everything unmeasured (extraction time and memory on phones, hence how long the first tap takes).

## The existing chunk path (checked in the code, 2026-11-20)

Who produces handout chunks today: **the Hub.** `server/backend/src/routes/materials.rs` (lines 81 to 109) calls `services/document_parser::extract_chunks` on every PDF and `.docx` upload and writes `material_chunks` (`db/material_chunks.rs`); they are served by `GET /api/materials/{id}/chunks` and sent in `POST /api/sync/pull`. They come from the same uploaded file the clients download. They serve **reading**:

* **Desktop:** `desktop/src/pages/ClassPage.tsx` (lines 36 to 38) shows a document's text only from these chunks and disables "Save for Home" when there are none; `desktop/src-tauri/src/account.rs` (lines 771 to 781) reads them and answers `lesson_text_unavailable` without them; `desktop/src-tauri/src/sync.rs` syncs them.
* **Mobile:** `mobile/app/src/main/java/org/lara/app/ui/screens/classwork/MaterialReader.kt` renders PDFs from the file (`PdfRenderer`), already parses `.docx` on the device (`docxBlocks`, lines 94 and 238), and uses the Hub chunks only as a text fallback (lines 208 to 217) and as the gate for the AI button (line 160), which opens the old Hub-based `TutorChat`.

**Verdict, my recommendation for the Lead to confirm: keep it separate now, remove it later.** It is not reused for the tutor (no page, no spec version, every handout instead of allowed ones, and the Hub must stop making chunks) and it cannot be removed yet because the desktop reader and Save for Home depend on it. So the tutor's chunks go in a separate client-only table (`tutor_chunks`, planned), the Hub reader chunks stay deprecated, and they are removed after the defense once both clients read handouts from the file (desktop needs a new reader; mobile mostly has one).

## What changed

| | Before | After |
|---|---|---|
| Where the tutor runs | Only on the Hub (`llama-server`, 2 to 4 slots, FIFO queue, token stream) | Only on the device (mobile and desktop), a roughly 1B-parameter small language model through llama.cpp |
| Who builds the prompt | The Hub, from `contracts/ai/socratic_system_prompt.txt` | The client, from the same file bundled in the app (the file is rewritten, same name) |
| Reading documents for the tutor | The Hub extracted and chunked PDF and Word handouts (#10) and synced the chunks | Each device does it itself, lazily on the first AI tap, into its own local `tutor_chunks`; the Hub keeps none and these chunks are never synced. The Hub-made reader chunks (`material_chunks`) stay only for the readers, deprecated |
| Tutor behaviour | Never the final answer, one clue then one question | Guided and grounded: accurate information from the teacher's material with page and heading, an explanation and clues, an optional short question; strict grounding; exact decline sentence when not covered; a leak is a soft metric |
| Where the tutor is allowed | Everywhere | Only for materials with the teacher's "Allow AI tutor" switch on (default off, per material, `materials.ai_allowed`, planned) |
| Model file | Loaded by the Hub | Served by the Hub over the LAN (HTTP Range), downloaded once by each client |
| Quiz lockout | Hub refuses with HTTP 403 / `QUIZ_IN_PROGRESS` | Client refuses, from the attempt state it already holds |
| Model choice | MiniCPM5-2B baseline, then REAP-15B chosen for the Hub (#304) | **Open**, decided by the phone evaluation |

## What stays on the Hub

The Google Classroom-style server (classes, stream, classwork, handouts, videos, homework, private comments, grading, gradebook export), the real-time synchronized quiz broker and auto-grading, accounts and the sequence-cursor sync, USB export and backup, discovery, Remote/Suspension Mode, file storage and serving (handouts, videos and the model file), and the one stored `ai_allowed` flag per material.

## What was superseded

* "The AI tutor runs only on the Hub" (2026-11-02, #208) and its consequences: no on-device inference, every client on the Hub WebSocket stream, the 11 GB RAM Hub requirement.
* "The Hub ships with the AI installed; using it is optional" and `AI_NOT_AVAILABLE` as a Hub answer. Using the tutor is still optional, but the optional part is now the device's.
* The Hub's AI queue, queue status events, token streaming, Hub-built prompt and server-side tutor lockout.
* The Hub-side text chunking built in #10 and the Hub's chunk sync as the tutor's source (kept only for the readers until they read the file; then removed after the defense).
* The 2026-11-14 choice of REAP-15B (#304) and the Hub-capacity targets in the PRD (`Inference Latency`).
* The Socratic rules "never give the final answer" and "one small clue, then one leading question", and the leak-zero scoring.

## Why

* **Capacity measured in PR #337.** On the one Radeon RX 570 (8 GB) tested, the five-learner target was **not met**: with the best safe configuration, p95 time to first token was 22.0 s and the slowest stream ran at 1.2 tokens per second. A class of about 40 on one Hub PC is further away still.
* **1B-class models fit phones, in design.** The on-device prototype (PR #349) loads four candidates of at most 1B parameters on Android. It has no real-phone numbers yet, so "fits" is a bet, not a result.
* Each learner runs their own model, so there is no shared queue to overload and the tutor still works at home with no Hub.
* A teacher decides where the tutor is used, and a device only spends memory and time on materials that were allowed.

## Known trade-offs (accepted, stated plainly)

1. **Weaker quiz lockout.** A Hub refusal could not be bypassed from the client. The client-side lock can be, by a learner who edits their own local database or whose device has not synced the attempt. The Lead accepted this. It is documented in `rules/quiz-and-anti-cheat.md` section 1 and must not be described as server-enforced anywhere.
2. **Two parsers can drift.** Kotlin on mobile and Rust or TypeScript on desktop can give different chunks, so different answers, from the same handout. Mitigation: one shared spec (`contracts/ai/chunking_spec.md`) with golden fixtures that both clients must pass in their own tests. The spec numbers and fixtures are still PENDING.
3. **Chunk fixes ship as app updates**, not a Hub update. Each chunk stores the spec version so a device can re-chunk after an update.
4. **Parsing cost is paid on every device, on the first AI tap, and is unmeasured.** Forty learners each parse the same file, and the first tap waits for it. Memory and time of text extraction on 3 to 4 GB phones, in Rust and in Kotlin, are not measured (the earlier laptop numbers are unverified on those platforms).
5. **A device needs the file.** A handout the device has not downloaded has no chunks: if the Hub is unreachable the learner is told to download it first.
6. **Model file distribution and 3 to 4 GB phones.** Every device needs a model file of hundreds of MB from the Hub over classroom Wi-Fi, under the 2.0 MB/s per-client cap unless exempted; a model in RAM next to the app may not fit or may heat the phone. The tutor stays optional for exactly this reason.
7. **No Hub visibility of bad extractions.** The Hub reads nothing, so failed or empty extractions appear only in each device's own log. Nobody sees them in the product.
8. **Vectors** would need an embedding model on every device; retrieval stays BM25 only and vectors are a later option.
9. **The switch is eventually consistent.** A toggle reaches a device at its next sync; an offline device keeps the old value.
10. **Tutor quality is unproven.** Earlier tests found that small models leak answers or refuse everything (#73). Nothing here claims a 1B model passes; the phone evaluation must show it.
11. **Pedagogy risk.** Because the tutor guides rather than refuses and may let an answer slip when the learner insists, it can give a learner an answer, including a homework or practice answer, that the lesson contains. The Lead accepted this; the quiz lockout, the teacher's switch and the fact that answer keys never reach a client are the protection. It is recorded as a risk, not as something to revisit.

## Open questions

1. Which model, if any (Gemma-3-270M, Gemma-3-1B, Llama-3.2-1B, Qwen3-0.6B)? Decided by the phone evaluation. Pass marks for time to first token and memory on phones are not set.
2. The exact cleaning and chunking numbers and the golden fixtures (`contracts/ai/chunking_spec.md`, PENDING; the starting findings are in its section 8): settled by the first task of the clients' implementation issues, then handed to the Lead as a contract change.
3. Which PDF and `.docx` libraries per platform (PDFium on Android is the likely one; no AGPL such as MuPDF), and the measured memory and time on 3 to 4 GB phones.
4. The scope of one chat: all allowed materials of the class (the working default) or one chat per material.
5. The "Allow AI tutor" granularity: per material is the working reading and is to be confirmed; is a class-level master switch also wanted? What does a learner see for a chat opened with no material?
6. How does the model file reach a device when the Hub is out of range (LAN only, or also a USB copy)? May the Remote/Suspension tunnel carry it, and is it exempt from the 2.0 MB/s cap?
7. Where do the evaluation test set and runner live once the Hub AI code is removed (today `server/ai_eval/`, written for the Socratic leak scoring and to be reworked to the new criteria)?
8. What happens to the defense demo, which currently shows the Hub tutor? See the announcement.
9. Whether the Hub AI removal and the new tutor issues are post-defense (the default set when this was written) or earlier, and their milestones and sprint dates (none are set).

10. Confirm the verdict on the existing reader chunks: keep them separate from the tutor's chunks now and remove them after the defense (needs a desktop reader that reads the file itself).

## Where this lives now

[`AGENTS.md`](../../AGENTS.md) sections 0, 1.4, 2, 3 and 5 · [`rules/socratic-ai-guardrails.md`](../../rules/socratic-ai-guardrails.md) · [`rules/quiz-and-anti-cheat.md`](../../rules/quiz-and-anti-cheat.md) · [`contracts/ai/chunking_spec.md`](../../contracts/ai/chunking_spec.md) and `contracts/ai/fixtures/` · [`contracts/planned/on-device-documents.md`](../../contracts/planned/on-device-documents.md) · PRD section 5 and FR-3.10 to FR-3.14, FR-7.1 to FR-7.6 · [announcement](../announcements/2026-11-20-no-ai-on-hub.md).
