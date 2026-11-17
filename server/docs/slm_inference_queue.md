# SLM inference queue

> **Status:** the queue, the tutor WebSocket stream and the Hub-side guardrails are built in Sprint 5. This file starts with what the AI evaluation ([SERVER 1.5], #73) decided and measured, so Sprint 5 starts from numbers. Sprint 5 adds the key files, data flow and queue behavior.

## Purpose
The Hub runs one local GGUF model with `llama-server` (llama.cpp) and answers learners' tutor questions on a fixed number of slots, in arrival order. The rules are in `rules/socratic-ai-guardrails.md`; the system prompt is `contracts/ai/socratic_system_prompt.txt`.

## Model
- **`MODEL_PATH=models/Qwen3-30B-A3B-Instruct-2507-REAP-15B-A3B-Q4_K_M.gguf`**, chosen by the Lead on 2026-11-14 (#304). The numbers behind it are in `docs/benchmarks/ai_model_evaluation.md`.
- The file is 9,303 MB, SHA-256 `53a2ab244017f6c2eb267d069bac431e11c40ad48e369a3a98f63cc917fca173`, from `12bitmisfit/Qwen3-30B-A3B-Instruct-2507_Pruned_REAP-15B-A3B-GGUF` on Hugging Face.
- It needs a 16 GB Hub ("Hub spec for the AI tutor" in `hub_hardware.md`).
- `HubConfig.model_path` reads `MODEL_PATH` (`TECH_SPEC.md`). A model swap changes only `MODEL_PATH`, not the client stream (guardrails section 1).

## `llama-server` flags (as measured)
```
llama-server -m $MODEL_PATH -c <2048 × slots> -np <slots> -ngl 0 -t 4
```
- llama.cpp build b11938, CPU only. Prompt caching is on, with llama-server's defaults. Capping its RAM (`--cache-ram`) belongs to [SERVER 5.1] #14.
- **`-c` is the total context, shared by the slots.** Each slot gets 2,048 tokens: the largest #73 prompt was 1,775 tokens (counted with the model's own tokenizer), which leaves 273 for the 256-token reply.
- **Slots:** not set yet. The Lead sets the slot count after the run on the real pilot Hub (2026-10-30).

## Measured speed and RAM
On a 16 GB laptop standing in for the Hub (Ryzen 5 7535HS, one memory module), 4 threads, full prompt set. Evidence: `server/ai_eval/results/v3-qwen3-reap/`.

| Learners at once | First token, median (short lessons) | First token, median (long lessons) | Tokens/s per reply | Peak RAM (MB) |
|---|---|---|---|---|
| 1 | 3.6 s | 22.0 s | 11.9 | 10,773 |
| 2 | 3.7 s | 25.3 s | 4.5 | 10,693 |

One learner meets the PRD's 8 to 15 tokens/s. With two at once, each reply streams at 4.5.

## Gotchas
- **Long lessons wait for the first word.** Lessons whose picked chunks fill most of the window (1,508 to 1,775 prompt tokens) wait 22 to 25 s, because the model reads the whole prompt first. The Lead recorded this as a known limit (#73, 2026-11-13).
- **Memory speed likely caps writing.** Writing stayed at 11.8 tokens/s from 4 to 12 threads on one memory module, so the Hub spec recommends two. This is inferred; it was not tested on a two-module machine.
- **On Linux, a model that does not fit slows down before it fails** (see `hub_hardware.md`). Keep the RAM reserve.
- **The decline lines come from the prompt, not the queue.** In #73 the model overused its decline template and misapplied the English-only line. Those fixes are prompt and scoring changes in #311, after the defense.
