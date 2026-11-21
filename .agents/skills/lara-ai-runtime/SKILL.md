---
name: lara-ai-runtime
description: Delivering the GGUF model from the Hub and running it on the device for the L.A.R.A Socratic tutor (download, on-device inference, streaming). Context and memory budgeting, grounding, quiz lockout and how to record evidence. Use when working on anything under an ai/ folder or the AI issues.
---

# L.A.R.A AI runtime: model from the Hub, inference on the device

Read `rules/socratic-ai-guardrails.md` first. It decides **what** the tutor may say; this skill covers **how** to run the model. AI quality on weak hardware is the project's biggest risk, so measure everything and write the numbers down.

## 0. Three facts

* **The model is not chosen yet.** It comes from the evaluation in `rules/socratic-ai-guardrails.md` section 5, recorded as `MODEL_PATH` in `docs/benchmarks/ai_model_evaluation.md`. Never hard-code a model file name or a tokens-per-second figure. Device RAM and size limits are open until the evaluation.
* **Tutor behavior:** never give the final answer, one small clue then one leading question, reply in English for the pilot, ground in the teacher's lesson chunks, and say "I cannot help with that, go back to your lesson" when the lesson does not cover it.
* **Quiz lockout everywhere:** while the learner has an `IN_PROGRESS` attempt the app does not run the model and never composes the chat UI. How the lockout reaches an offline device is to be defined in a contract-change PR.

## 1. Delivery: download from the Hub, run on the device (decided 2026-11-21)

The Hub stores the model file and serves it over HTTP `:8080`, resumable with Range like video. Each phone and desktop downloads it and runs inference locally with no network. The Hub runs no `llama-server`, no queue and no token stream.

A device without the model shows "Download the AI model from the Hub"; a device that cannot run it shows "The AI tutor is not available on this device". Past chats may be read from local `ai_chat_messages`. Other features keep working offline. Remote/Suspension Mode carries only the model download through the Hub tunnel. AI remains optional.

## 2. On-device engine

* The inference engine each client embeds, the model file name and size, the minimum device RAM and whether the phone heap limit of 250 MB holds are open (to be decided from the evaluation and recorded in a contract-change PR). Do not guess them.
* Stream tokens from the engine to the UI incrementally and cancel generation when the learner closes the sheet or a quiz attempt starts.
* Return the `grounded_chunk_id` with each reply so the UI can show which part of the lesson the hint came from.
* Run the download as a resumable background task; verify the file's checksum before loading it.

## 3. Prompt and grounding

* The app on the device selects chunks using `rules/socratic-ai-guardrails.md` section 3.1 and includes recent history from its local `ai_chat_messages` using section 3.2. How the chunks reach the device is to be defined in a contract-change PR. Files with no extracted text have no chunks: return the lesson-text-unavailable response rather than inventing a hint.
* **One prompt file:** `contracts/ai/socratic_system_prompt.txt`. The app loads it byte for byte at start and fills `{reply_language}` and `{lesson_chunks}`. Never retype or reword it in code; a wording change is a `contract-change` PR.
* Test the on-device builder against learner prompts (`server/ai_eval/`), with English replies even to Filipino and Taglish messages, and add a regression test when you fix a leak.

## 4. Evidence to record

For every model and device class: pass rate per criterion from the evaluation, time to first token, tokens per second, peak RAM, and the hardware it ran on. Label results "developer-scored" as the rules require, and write them to `docs/benchmarks/`. Do not say "good enough" without these numbers.
