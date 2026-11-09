---
name: lara-ai-runtime
description: Running GGUF models with llama-server on the Hub for the L.A.R.A Socratic tutor (slots, queue, streaming). Context and memory budgeting, grounding, quiz lockout and how to record evidence. Use when working on anything under an ai/ folder or the AI issues.
---

# L.A.R.A AI runtime: Hub llama-server

Read `rules/socratic-ai-guardrails.md` first. It decides **what** the tutor may say; this skill covers **how** to run the model. AI quality on weak hardware is the project's biggest risk, so measure everything and write the numbers down.

## 0. Three facts

* **The model is not chosen yet.** It comes from the evaluation in `rules/socratic-ai-guardrails.md` section 5, recorded as `MODEL_PATH` in `docs/benchmarks/ai_model_evaluation.md`. Never hard-code a model file name, a slot count or a tokens-per-second figure.
* **Hub tutor behavior:** never give the final answer, one small clue then one leading question, reply in English for the pilot, ground in the teacher's lesson chunks, and say "I cannot help with that, go back to your lesson" when the lesson does not cover it.
* **Quiz lockout everywhere:** while the learner has an `IN_PROGRESS` attempt the Hub rejects the request (`QUIZ_IN_PROGRESS`) and clients never compose the chat UI.

## 1. Hub-only routing

Every phone, laptop and lab PC sends the learner's message through `EVENT_AI_CHAT_REQUEST` and renders `EVENT_QUEUE_STATUS` / `EVENT_AI_TOKEN_STREAM`. Only the Hub builds prompts and runs inference; there is no client RAM check or model download. The phone heap remains under 250 MB on the 3 to 4 GB target.

When the Hub is unreachable, clients show "The AI tutor needs the classroom Hub" and may read past chats from local `ai_chat_messages`. Other features keep working offline. Remote/Suspension Mode carries the same stream through the Hub tunnel. If the Hub model is missing or fails to load, return `AI_NOT_AVAILABLE`; AI remains optional.

## 2. `llama-server` on the Hub

* Run it as a **managed child process**: spawn with `tokio::process::Command`, `kill_on_drop(true)`, wait for `GET /health` before accepting requests, restart with backoff if it dies, stop it when the Hub exits.
* **Bind to `127.0.0.1` only** (`--host 127.0.0.1`). Only the Hub's own code talks to it; learners never reach it directly.
* Flags: `-m <MODEL_PATH>`, `-c <context>`, `-np <slots>` (2 to 4 from the evaluation), continuous batching. **Budget the context per slot.** `-c` is the server's total context, and with several slots each sequence gets a share of it unless the KV cache is unified. Read the startup log for the per-slot context and make sure each slot has room for the system prompt, the lesson chunks (about 2048 tokens) and the reply. If it does not, raise `-c` and re-measure RAM. This is the easiest mistake to make: `-c 2048 -np 4` can leave only a quarter of the window per learner.
* Request `POST /v1/chat/completions` with `"stream": true` and `max_tokens` capped (a hint is short; 200 to 256 is plenty). Low temperature. Convert the server's SSE chunks into `EVENT_AI_TOKEN_STREAM` frames; do not forward raw OpenAI chunks to clients.
* **Cancel upstream** when the learner's socket closes, or a generation keeps burning a slot for nobody.
* Keep a **bounded FIFO queue** in front of the slots. Send `EVENT_QUEUE_STATUS` with `position` and `estimated_wait_seconds`; clients show the localized text ("Pangalawa ka sa pila - est. 4s"). When the queue is full, answer with a friendly error, not a hang. Cap concurrent generation so video and quizzes still run on the same machine.
* Return `grounded_chunk_id` in the stream so the UI can show which part of the lesson the hint came from.

## 3. Prompt and grounding

* Only the Hub selects chunks using `rules/socratic-ai-guardrails.md` section 3.1 and includes recent history from its `ai_chat_messages` using section 3.2. Files with no extracted text have no chunks: return the lesson-text-unavailable response rather than inventing a hint.
* **One prompt file:** `contracts/ai/socratic_system_prompt.txt`. Only the Hub loads it byte for byte at start and fills `{reply_language}` and `{lesson_chunks}`. Never retype or reword it in code; a wording change is a `contract-change` PR.
* Test the Hub builder against learner prompts (`server/ai_eval/`), with English replies even to Filipino and Taglish messages, and add a regression test when you fix a leak.

## 4. Evidence to record

For every model and slot count: pass rate per criterion from the evaluation, time to first token, tokens per second, peak RAM, and the hardware it ran on. Label results "developer-scored" as the rules require, and write them to `docs/benchmarks/`. Do not say "good enough" without these numbers.
