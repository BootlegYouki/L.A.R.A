# Decision record: no AI on the Hub (2026-11-20)

**Status:** decided by the Lead on 2026-11-20. **Scope:** product architecture. This file is the one place that explains the change; other documents link here instead of repeating it.

## The decision

> "The hub will just serve as a server and the pdf parser for the slm, of course it will handle what the original plan is like being the google classroom and the real time test."

## What changed

| | Before | After |
|---|---|---|
| Where the tutor runs | Only on the Hub (`llama-server`, 2 to 4 slots, FIFO queue, token stream) | Only on the device (mobile and desktop), a roughly 1B-parameter small language model through llama.cpp |
| Who builds the prompt | The Hub, from `contracts/ai/socratic_system_prompt.txt` | The client, from the same file bundled in the app |
| Lesson text | Hub chunks, selected by the Hub | Hub parses PDF text layers and `.docx` into chunks and syncs them; the client retrieves from its local copy |
| Model file | Loaded by the Hub | Served by the Hub over the LAN (HTTP Range), downloaded once by each client |
| Quiz lockout | Hub refuses with HTTP 403 / `QUIZ_IN_PROGRESS` | Client refuses, from the attempt state it already holds |
| Model choice | MiniCPM5-2B baseline, then REAP-15B chosen for the Hub (#304) | **Open**, decided by the phone evaluation |

## What stays on the Hub

The Google Classroom-style server (classes, stream, classwork, handouts, videos, homework, private comments, grading, gradebook export), the real-time synchronized quiz broker and auto-grading, accounts and the sequence-cursor sync, USB export and backup, discovery, Remote/Suspension Mode, and the new document parser.

## What was superseded

* "The AI tutor runs only on the Hub" (2026-11-02, #208) and its consequences: no on-device inference, every client on the Hub WebSocket stream, the 11 GB RAM Hub requirement.
* "The Hub ships with the AI installed; using it is optional" and `AI_NOT_AVAILABLE` as a Hub answer. Using the tutor is still optional, but the optional part is now the device's.
* The Hub's AI queue, queue status events, token streaming, Hub-built prompt and server-side tutor lockout.
* The 2026-11-14 choice of REAP-15B (#304) and the Hub-capacity targets in the PRD (`Inference Latency`).

## Why

* **Capacity measured in PR #337.** On the one Radeon RX 570 (8 GB) tested, the five-learner target was **not met**: with the best safe configuration, p95 time to first token was 22.0 s and the slowest stream ran at 1.2 tokens per second. A class of about 40 on one Hub PC is further away still.
* **1B-class models fit phones.** The on-device prototype (PR #349) loads four candidates of at most 1B parameters on Android. It has no real-phone numbers yet, so "fits" is a design bet, not a result.
* Each learner runs their own model, so there is no shared queue to overload and the tutor still works at home with no Hub.

## Known trade-offs (accepted)

1. **Weaker quiz lockout.** A Hub refusal could not be bypassed from the client. The client-side lock can be, by a learner who edits their own local database or whose device has not synced the attempt. The Lead accepted this. It is documented in `rules/quiz-and-anti-cheat.md` section 1 and must not be described as server-enforced anywhere.
2. **Model file distribution.** Every device needs a model file of hundreds of MB from the Hub over classroom Wi-Fi, under the 2.0 MB/s per-client cap unless exempted. Getting the file onto the Hub is an admin task from USB.
3. **3 to 4 GB phones.** A model in RAM next to the app may not fit, or may heat the phone. The tutor stays optional for exactly this reason, and the phone evaluation decides whether any candidate is usable.
4. **Document quality.** Scanned PDFs and OCR are out of scope (measured too slow on a CPU), so only PDFs with a text layer and `.docx` feed the tutor. A bad extraction means the document is not used; teachers are never told why (admin console and logs only).
5. **Tutor quality is unproven.** Earlier tests found that small models leak answers or refuse everything (#73). Nothing here claims a 1B model passes; the phone evaluation must show it.

## Open questions

1. Which model, if any (Gemma-3-270M, Gemma-3-1B, Llama-3.2-1B, Qwen3-0.6B)? Decided by the phone evaluation. Pass marks for time to first token and memory on phones are not set.
2. Do the retrieval and the chunk settings need vectors, or is BM25 enough? Depends on `experiments/ocr-prototype` (text-layer parsing experiment, numbers pending) and the phone evaluation.
3. How does the model file reach a device when the Hub is out of range (LAN only, or also a USB copy)? May the Remote/Suspension tunnel carry it, and is it exempt from the 2.0 MB/s cap?
4. Where do the evaluation test set and runner live once the Hub AI code is removed (today `server/ai_eval/`)?
5. What happens to the defense demo, which currently shows the Hub tutor? See the announcement.
6. Whether the Hub AI removal and the new tutor issues are post-defense (the default set when this was written) or earlier, and their milestones and sprint dates (none are set).

## Where this lives now

[`AGENTS.md`](../../AGENTS.md) sections 0, 1.4, 2, 3 and 5 · [`rules/socratic-ai-guardrails.md`](../../rules/socratic-ai-guardrails.md) · [`rules/quiz-and-anti-cheat.md`](../../rules/quiz-and-anti-cheat.md) · [`contracts/planned/document-pipeline.md`](../../contracts/planned/document-pipeline.md) · PRD sections 5 and FR-3.10 to FR-3.13, FR-7.1 to FR-7.5 · [announcement](../announcements/2026-11-20-no-ai-on-hub.md).
