---
name: lara-ai-runtime
description: Running a small GGUF model with llama.cpp on the device (phone or laptop) for the L.A.R.A Socratic tutor. Context and memory budgeting, on-device retrieval, grounding, client-side quiz lockout and how to record evidence. Use when working on the tutor or its issues.
---

# L.A.R.A AI runtime: on-device llama.cpp

Read `rules/socratic-ai-guardrails.md` first. It decides **what** the tutor may say; this skill covers **how** to run the model on the device. There is no AI on the Hub (decided 2026-11-20, `docs/decisions/2026-11-20-no-ai-on-hub.md`). Tutor quality and memory on 3 to 4 GB phones are the project's biggest risk, so measure everything on real phones and write the numbers down.

## 0. Three facts

* **The model is not chosen yet.** It comes from the phone evaluation in `rules/socratic-ai-guardrails.md` section 5, recorded in `docs/benchmarks/ai_model_evaluation.md`. Never hard-code a model file name, a context size or a tokens-per-second figure, and never name a winner before that data exists.
* **Tutor behavior:** never give the final answer, one small clue then one leading question, reply in English, ground in the teacher's lesson chunks, and say you cannot help with that and point back to the lesson when the lesson does not cover it.
* **Quiz lockout is the client's job:** while the learner has an `IN_PROGRESS` attempt in the local database, never load the model, never compose the chat UI, and stop any running generation. It is weaker than a Hub refusal and is an accepted limitation (`rules/quiz-and-anti-cheat.md` section 1).

## 1. What the Hub gives you, and what it does not

The Hub syncs `material_chunks` (it parses the teacher's PDF or `.docx`) and serves the model file over HTTP with Range (`contracts/planned/document-pipeline.md`, planned). It never sees a tutor question. There is no chat request, queue or token stream. Before the model is downloaded and the Hub is unreachable, show "The AI tutor needs the classroom Hub"; once it is on the device the tutor needs no Hub at all.

## 2. Running llama.cpp on the device

* Load the model **only when the tutor opens** and free it when the learner leaves, backgrounds the app or starts a quiz. One generation at a time, cancellable.
* **Budget memory first.** The app heap stays under 250 MB (`AGENTS.md` section 2); the model's own RAM is separate and is measured on real 3 GB and 4 GB phones. Prefer `mmap` loading, a small context and few threads. If the device cannot load it, show "AI is not available right now" and leave everything else working.
* **Budget the context:** model window minus the system prompt, the lesson chunks, the chat history and a reply reserve (`rules/socratic-ai-guardrails.md` section 3.1). Count with the loaded model's tokenizer, never words x 1.4.
* Run inference off the UI thread, stream tokens to the screen, cap `max_tokens` (a hint is short) and use a low temperature.
* A low-memory kill must leave no corrupted state: the next launch starts normally with the tutor off.
* The CPU is the target. Emulator numbers are not phone numbers.

## 3. Prompt and grounding

* **One prompt file:** `contracts/ai/socratic_system_prompt.txt`, bundled byte for byte by the app and filled with `{reply_language}` and `{lesson_chunks}`. Never retype or reword it in code; a wording change is a `contract-change` PR.
* Pick chunks from the synced `material_chunks` with the keyword rule in `rules/socratic-ai-guardrails.md` section 3.1 and include recent history from the local `ai_chat_messages` per section 3.2. A document with no chunks gives the lesson-text-unavailable reply, never an invented hint.
* Test the prompt builder against the learner prompts (`server/ai_eval/` until the Lead moves them), including Filipino and Taglish messages that must get the English-only decline, and add a regression test when you fix a leak.

## 4. Evidence to record

For every model: pass rate per criterion from the evaluation, time to first token, tokens per second, peak RAM, battery and temperature, and the phone model it ran on. Label results "developer-scored" as the rules require, and write them to `docs/benchmarks/`. Do not say "good enough" without these numbers.
