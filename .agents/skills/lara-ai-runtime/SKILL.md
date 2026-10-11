---
name: lara-ai-runtime
description: Running small GGUF models with llama.cpp for the L.A.R.A Socratic tutor on three paths: llama-server on the Hub (slots, queue, streaming), a sidecar on desktop, and JNI on 6 GB+ Android phones. Context and memory budgeting, grounding, quiz lockout and how to record evidence. Use when working on anything under an ai/ folder or the AI issues.
---

# L.A.R.A AI runtime: llama.cpp on weak hardware

Read `rules/socratic-ai-guardrails.md` first. It decides **what** the tutor may say; this skill covers **how** to run the model. AI quality on weak hardware is the project's biggest risk, so measure everything and write the numbers down.

## 0. Three facts

* **The model is not chosen yet.** It comes from the evaluation in `rules/socratic-ai-guardrails.md` section 5, recorded as `MODEL_PATH` in `docs/benchmarks/ai_model_evaluation.md`. Never hard-code a model file name, a slot count or a tokens-per-second figure.
* **One behavior on every path:** never give the final answer, one small clue then one leading question, reply in the learner's language, ground in the teacher's lesson chunks, and say "I cannot help with that, go back to your lesson" when the lesson does not cover it.
* **Quiz lockout everywhere:** while the learner has an `IN_PROGRESS` attempt the Hub rejects the request (`QUIZ_IN_PROGRESS`) and clients never compose the chat UI or call a local model.

## 1. Routing

| Device | Path |
|---|---|
| Phone under 6 GB, or a phone whose measured RAM says so | Hub over WebSocket. Never load a model on the phone. |
| Phone with 6 GB or more, model file present | JNI, fully offline |
| Laptop with 4 GB or more, model file present | Sidecar, fully offline |
| Anything else, or the model file is missing | Hub over WebSocket |

Android reports less RAM than the number on the box, so a 6 GB phone can read about 5.5 GB. Measure the real phones and set the gate from the readings.

## 2. `llama-server` on the Hub

* Run it as a **managed child process**: spawn with `tokio::process::Command`, `kill_on_drop(true)`, wait for `GET /health` before accepting requests, restart with backoff if it dies, stop it when the Hub exits.
* **Bind to `127.0.0.1` only** (`--host 127.0.0.1`). Only the Hub's own code talks to it; learners never reach it directly.
* Flags: `-m <MODEL_PATH>`, `-c <context>`, `-np <slots>` (2 to 4 from the evaluation), continuous batching. **Budget the context per slot.** `-c` is the server's total context, and with several slots each sequence gets a share of it unless the KV cache is unified. Read the startup log for the per-slot context and make sure each slot has room for the system prompt, the lesson chunks (about 2048 tokens) and the reply. If it does not, raise `-c` and re-measure RAM. This is the easiest mistake to make: `-c 2048 -np 4` can leave only a quarter of the window per learner.
* Request `POST /v1/chat/completions` with `"stream": true` and `max_tokens` capped (a hint is short; 200 to 256 is plenty). Low temperature. Convert the server's SSE chunks into `EVENT_AI_TOKEN_STREAM` frames; do not forward raw OpenAI chunks to clients.
* **Cancel upstream** when the learner's socket closes, or a generation keeps burning a slot for nobody.
* Keep a **bounded FIFO queue** in front of the slots. Send `EVENT_QUEUE_STATUS` with `position` and `estimated_wait_seconds`; clients show the localized text ("Pangalawa ka sa pila - est. 4s"). When the queue is full, answer with a friendly error, not a hang. Cap concurrent generation so video and quizzes still run on the same machine.
* Return `grounded_chunk_id` in the stream so the UI can show which part of the lesson the hint came from.

## 3. Prompt and grounding

* Select chunks from `material_chunks` by `order_index` and `token_estimate` to fit about 2048 tokens. Files with no extracted text have no chunks: the answer is the "cannot help with that file" message, not an invented hint.
* **One prompt file:** `contracts/ai/socratic_system_prompt.txt`. Load it byte for byte on every path (the Hub reads it at start; Android and desktop copy it into the app at build time) and fill `{reply_language}` and `{lesson_chunks}`. Never retype or reword it in code; a wording change is a `contract-change` PR.
* Test each builder against the same learner prompts (`server/ai_eval/`), in English, Filipino and Taglish, and add a regression test when you fix a leak.

## 4. Android (JNI, arm64-v8a only)

* One inference thread. Set the thread count to the phone's performance cores, not all cores.
* Load the model with `mmap` (the default) so pages can be evicted under pressure. The Java heap number does not include native memory, but the Android low-memory killer counts it. Watch **PSS** with `adb shell dumpsys meminfo org.lara.app`.
* Context window 2048. Stream tokens to the UI through a `Flow`.
* Unload on `onTrimMemory`. Never load below the measured RAM gate.
* Test on a real 6 GB+ phone and record tokens per second and peak memory in `mobile/docs/`. An emulator cannot tell you this.

## 5. Desktop sidecar

* Ship `llama-server` as a Tauri sidecar (`bundle.externalBin`, with the target-triple suffix in the file name), run it on a free port bound to `127.0.0.1`, and kill it when the app exits.
* Prefer `llama-server` over parsing `llama-cli` text: the stream then has the same format as the Hub path, so one client can read both.
* Fall back to the Hub stream when the laptop has under 4 GB or the model file is missing.

## 6. Evidence to record

For every model and slot count: pass rate per criterion from the evaluation, time to first token, tokens per second, peak RAM, and the hardware it ran on. Label results "developer-scored" as the rules require, and write them to `docs/benchmarks/`. Do not say "good enough" without these numbers.
