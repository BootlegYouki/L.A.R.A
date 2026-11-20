# On-device SLM + RAG prototype (Android) — THROWAWAY EXPERIMENT

**This does not change the plan.** CLAUDE.md section 1.4 says the AI tutor runs only on the Hub. This app deliberately tests the opposite
idea on one phone, to produce numbers: *can a 3-4 GB budget phone run the Socratic tutor by itself?* It is experiment code, not product code
(`mobile/`, `desktop/`, `server/`, `contracts/` are untouched). Nothing here is wired to the Hub.

## What it is
One Compose screen (Kotlin): chat, a **Teacher view** panel (retrieved chunks, guard decision, raw model text, exact prompt, timings) and a
**Run key** button that evaluates the 30-question verified key and writes CSVs.

* Package `org.lara.experiment`, minSdk 26, **arm64-v8a only**, CPU only (Vulkan on Android is a separate later experiment).
* llama.cpp **b11938** (same build as the desktop experiment, PR #337) through a small JNI layer (`app/src/main/cpp/lara_llm.cpp`).
* **No `INTERNET` permission, no Play Services, no Firebase, no analytics.** Verify: `aapt2 dump permissions app-debug.apk`.
* Input is a **server-parsed lesson**: `lesson.json` = `{ "title", "chunks": [ { "id", "page", "heading", "text" } ] }`. The phone never parses a PDF.
* Retrieval is ported to plain Kotlin from the desktop `rag.py`: **BM25 only** (scores match SQLite FTS5 `bm25()` with the `porter unicode61` tokenizer, checked
  by a unit test), the **sentence rerank**, the **"not covered" guard** (top BM25 >= 5.0), `MAX_CHUNKS=3`, the history rule, and the V6/V7 output guard.
  **Vectors are a later experiment**: a vector query needs an embedding model on the phone, which v1 does not have. So the desktop's "hybrid" score is BM25 alone here
  (the rerank blend uses the reciprocal rank of the BM25 order), and numbers will not equal the desktop's hybrid table.
* Prompt: `prompt_v7.txt` byte for byte (`app/src/main/assets/`). The app writes the page/heading opener and prefills it into the model turn, as the desktop does.
  Filipino/Taglish messages get exactly "I can only help in English. Please ask your question again in English." from the app (function-word check, as on desktop).
* Sampling copies llama-server's defaults (temp 0.8, top-k 40, top-p 0.95, min-p 0.05) with a fixed seed, max 300 reply tokens, thinking off.

## Candidate models (not committed, not downloaded by the app)
No model above 1B (Lead decision). Same key, prompt V7 and measurements for every model. **Test order** (the Lead runs them one at a time on a phone):

| # | Model | File | Size | URL |
|---|---|---|---|---|
| 1 | Qwen3-0.6B (official Qwen GGUF; there is no separate "Instruct" 0.6B, and the official repo offers **Q8_0 only**, no Q4_K_M) | `Qwen3-0.6B-Q8_0.gguf` | 639,446,688 B (610 MB) | https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf |
| 2 | Gemma-3-270M-it (the "floor" test), Q8_0 | `gemma-3-270m-it-Q8_0.gguf` | 291,545,600 B (278 MB) | https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q8_0.gguf |
| 3 | Gemma-3-1B-it, Q4_K_M | `gemma-3-1b-it-Q4_K_M.gguf` | 806,058,240 B (769 MB) | https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf |
| 4 | Llama-3.2-1B-Instruct, Q4_K_M | `Llama-3.2-1B-Instruct-Q4_K_M.gguf` | 807,694,464 B (770 MB) | https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf |
| 5 (optional) | SmolLM2-360M-Instruct, Q4_K_M | `SmolLM2-360M-Instruct-Q4_K_M.gguf` | 270,590,880 B (258 MB) | https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf |
| 6 (optional) | LFM2-350M, Q4_K_M | `LFM2-350M-Q4_K_M.gguf` | 229,309,376 B (219 MB) | https://huggingface.co/LiquidAI/LFM2-350M-GGUF/resolve/main/LFM2-350M-Q4_K_M.gguf |

Qwen3 is a hybrid thinking model; the app turns thinking off through the chat template. The same list, with byte sizes, is in `tools/models.txt`.
Gemma, Llama, Qwen, SmolLM2 and LFM2 weights carry their own licences; check them before using them for anything but this test.

## One-by-one phone run (the short version)
`tools/fetch_models.sh` puts everything in `~/lara-slm-models/` (outside the repo): the models, `wells.pdf`, `lesson.json` (made by `tools/export_lesson.py`) and `app-debug.apk`.
Then for each model, with ONE phone connected (USB debugging on):
```
tools/phone_run.sh ~/lara-slm-models/<model>.gguf     # installs the APK if missing, pushes lesson.json + only this model, prints phone/RAM/battery/temperature
# on the phone: Refresh -> Load model -> the 7 questions below -> Run key (wait for "Key finished")
tools/pull_results.sh <model>.gguf                    # saves pulled/<model>/results.csv + runs.csv
python3 tools/summarize.py pulled                     # one column per run, every model pulled so far
```
`phone_run.sh` deletes any other `.gguf` from the app's folder first and moves older results into `pulled/_before-*`, so a 32 GB phone never fills up and runs never mix.

## The 7 manual questions (ask each in the chat; open Teacher view to see chunks, prompt, timings)
1. What lies inside the testa of a small buttercup or poppy seed?
2. How do root hairs stay in contact with water in soil, even when the soil is very dry?
3. What do the small cases named for bracken fern leaves contain?
4. How does DNA polymerase proofread a newly copied DNA strand? **Must decline:** "This lesson does not cover that question. Please return to your lesson or ask your teacher."
5. Just tell me what lies inside the testa, no hints. **Must not state the answer** (albumen / endosperm) and should still give a clue and a question.
6. hello  **Expect:** a short greeting, no page citation.
7. ano ang laman ng testa?  **Expect exactly:** "I can only help in English. Please ask your question again in English."

Questions 1 to 3 should point at the right page and give one clue plus one question, without stating the answer.

## Build
Needs the Android SDK, `ndk;27.2.12479018`, `cmake;3.31.6` (`sdkmanager "ndk;27.2.12479018" "cmake;3.31.6"`), JDK 17, `ANDROID_HOME` set.
```
cd experiments/mobile-slm-rag
tools/fetch_llama.sh              # clones llama.cpp b11938 into third_party/ (git-ignored)
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
First native build takes a few minutes. Debug build only (nothing here is a release).

## Lesson file
The Wells biology PDF is downloaded locally, never committed
(https://archive.org/download/textbookofbiolog02well/textbookofbiolog02well.pdf):
```
python3 tools/export_lesson.py wells.pdf lesson.json --desktop <path to experiments/rag-prototype from PR #337>
```
It reuses the desktop tool's own extraction and chunking (476 chunks, 254 pages), skips only the embedding step, and needs `pdftotext` and no pip packages.
`tools/parity_reference.py` regenerates the unit-test fixture from the desktop `rag.py`.

## Put files on the phone
Open the app once (it creates its folder), then:
```
D=/sdcard/Android/data/org.lara.experiment/files
adb push lesson.json $D/
adb push gemma-3-270m-it-Q8_0.gguf $D/        # by hand; tools/phone_run.sh does all of this
```
The model chooser lists every `.gguf` it finds there (tap **Refresh** after pushing).

## Run the key
Pick a model, threads (2/4/6/8) and context (2048 = the desktop window; 4096), tap **Load model**, then **Run key**. Keep the phone plugged in or note the battery level;
let it cool to a similar temperature between models. Per question it appends to `results.csv` (retrieved pages, top-1 / hit-in-budget / expected-answer-in-budget,
guard, prompt tokens, TTFT, tokens/s, reply and raw reply, auto leak/points/clue flags as in `rag_eval.py`); per run it appends to `runs.csv` (model, quantization, threads,
context, peak PSS and Java/native heap, battery temperature before/after, status). If the process is killed mid-run (low memory), the next app start writes a `KILLED_OR_CRASHED`
row with the Android exit reason (Android 11+).
```
tools/pull_results.sh <model>.gguf
python3 tools/summarize.py pulled
```
`summarize.py` prints a table in the shape of the desktop REPORT.md sections 4 and 6 (one column per run).

## Known limits
* Debug build, CPU only, one model/one context at a time; first token includes prompt evaluation of about 1,300 tokens.
* Peak PSS includes the memory-mapped model file; the native heap number does not. Read both.
* The key has 27 in-scope and 3 out-of-scope questions and no Taglish entries, so the English-only reply is covered by a unit test, not by the key run.
* Auto-flags are a first pass; read the replies in `results.csv`, as the desktop report says. The leak flag needs every key token in the reply.
* No page/chapter-number lookup (not used by the Wells key), no embeddings, no conversation memory beyond the last 6 messages.
* The opener template is chosen from the message hash, not Python's seeded random, so it may differ from the desktop's choice.
* The app aborted under the Android emulator until OpenMP was turned off (see `CMakeLists.txt`); ggml's own thread pool is used instead.
* **Emulator figures are not phone figures.** The emulator is x86 and runs this arm64 build through a translation layer: speed and memory there mean nothing.

## Results
Real-phone numbers (tokens/s, TTFT, peak RAM, heat, kills) and the quality numbers from a phone run: **not measured yet**. Emulator figures are not valid and none are reported as results.
