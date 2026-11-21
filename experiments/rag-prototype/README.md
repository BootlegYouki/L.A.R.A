# RAG tutor prototype (Windows + AMD GPU benchmark bundle)

**Throwaway experiment, not product code.** It is a small web app that imitates the L.A.R.A Hub AI tutor: a learner asks a question about a teacher's PDF, the app finds the right lesson part (retrieval), and a local model answers like a Socratic tutor: it names the page and heading, gives a small clue and asks one question, and never states the answer. We use it to choose a model and settings for the Hub, and to see what a GPU changes. Everything runs on your PC; nothing is sent anywhere except the one-time downloads.

**To let an AI assistant do the whole job for you, give it [AGENT_BRIEF.md](AGENT_BRIEF.md).**

## Requirements
- Windows 10/11 (Linux also works: `setup.sh`, `run.sh`, `stop.sh`, `bench_all.sh`).
- **Python 3.10 or newer** (python.org, tick "Add python.exe to PATH"). No pip packages are needed.
- RAM: **16 GB is enough for the 4B tier; 32 GB is recommended** for the optional Qwen3-30B-A3B (its experts live in system RAM).
- GPU: AMD RX 570 8 GB (any GPU with Vulkan 1.2+ works) with an up to date Adrenalin driver. The Vulkan build of llama.cpp is used.
- Internet for the downloads. Disk: see the table. The SSD can be small: put the models on another drive with `-ModelsDir`.

## Quick start
1. Install Python. Open PowerShell in this folder. If scripts are blocked: `powershell -ExecutionPolicy Bypass -File .\setup.ps1`.
2. `.\setup.ps1` (or `.\setup.ps1 -ModelsDir D:\models`). Installs llama.cpp b11938 (Vulkan), poppler (for `pdftotext`), the embedder and the Qwen3-4B baseline: about 2.6 GB of models.
3. `.\run.ps1`. It prints which Vulkan device llama-server picked and **warns loudly if it fell back to the CPU**. Allow Python and llama-server through the Windows Firewall on Private networks when asked.
4. Open the printed `http://<your-pc>:8000` on this PC or on a phone on the same Wi-Fi. The first start reads the sample lesson (1 to 3 minutes); until then the page does not answer.
5. Try questions from [TEST_QUESTIONS.md](TEST_QUESTIONS.md). Stop with `.\stop.ps1`.

## Defaults (the page starts with the best settings found so far)
Retriever **hybrid**, sentence rerank **on**, prompt **V5**, not-covered threshold **0.65** (the guard sends chunks when best bm25 >= 5 OR best cosine >= threshold), Teacher view **on**, `MAX_CHUNKS=3`, model Qwen3-4B-Instruct-2507, 5 slots and `-ngl 99` on GPU (1 slot, `-ngl 0` on CPU). Change them with environment variables or `config.env` (see `config.env.example`): `DEFAULT_RETRIEVER`, `DEFAULT_RERANK`, `DEFAULT_PROMPT`, `DEFAULT_THRESHOLD`, `MAX_CHUNKS`, `LLM_MODEL`, `LLM_SLOTS`, `LLM_NGL`, `LLM_THREADS` (12 is a CPU-only default; a GPU run uses 4), `LLM_N_CPU_MOE`, `LLM_NO_MMAP`, `EMBED_THREADS`, `EMBED_NGL` (99 = embed on the GPU, faster for a long PDF), `MODELS_DIR`, `BIG_MODELS_DIR`, ports, `LLAMA_SERVER`, `POPPLER_BIN`. A change you make on the page survives a reload of that browser tab only; a fresh tab shows the defaults again.

## Compare the models (one at a time)
`.\bench_all.ps1 -ModelsDir D:\models -BigModelsDir E:\models -Yes` reads `models.json` and, **for one model at a time**: checks free space (size + 1 GB, otherwise records SKIPPED), downloads only that model (resumable, size checked against the Hugging Face API), runs the whole procedure, writes the row to `results\MODEL_COMPARISON.md`, and **deletes the file it downloaded** (never anything else, only inside the models folders; the embedder is always kept; `-Keep` keeps downloads). The procedure: load check and load time, a discarded warm-up run (so a slow disk does not distort speeds), `llama-bench` pp512 / pp1024 / tg128 x3 on the GPU, a CPU baseline up to 8B, the concurrency test at 1 / 3 / 5 users (5 slots, fewer if VRAM does not fit), and the scored quality run (65 prompts, prompt V5). The two optional 30B MoE models run last, with `--n-cpu-moe` (experts in system RAM) and `--no-mmap` (whole file read into RAM at load); the report records the drive type (SSD/HDD), free RAM before each run and paging during the MoE runs.
Options: `-Only "Qwen3-8B,Gemma"`, `-Resume` (continue after Ctrl+C), `-Keep`, `-Install <name>` (download and keep the winner at the end), `-Pdf2 file.pdf -Key2 key.json` (also the second PDF for models that are fast enough). Without `-All`/`bench_all`, `.\gpu_bench.ps1` runs only the 4B (30 to 45 minutes). `.\concurrency_test.ps1 -Users 1,2,3,5,8` tests the running app. Time: the 4B alone is about 30 to 45 minutes; the whole list is a few hours (the 30B MoE files are the slowest and depend on this PC's CPU and RAM bandwidth).
**Send back:** `results\MODEL_COMPARISON.md` and `results\<computer>-<date>.md` (and the folder `results\raw` if something failed). They contain the machine description, no personal data.

### Download sizes and peak disk need
| model | size (MB) | notes |
|---|---|---|
| Qwen3-4B-Instruct-2507 Q4_K_M | 2497 | baseline, installed by setup |
| Gemma-3-4B-it Q4_K_M | 2490 | |
| Phi-4-mini-instruct Q4_K_M | 2492 | |
| Qwen3-8B Q4_K_M | 5028 | dense, fits the GPU |
| Granite-4.0-H-Tiny Q4_K_M | 4255 | unusual architecture, may not load |
| LFM2-8B-A1B Q4_K_M | 5045 | unusual architecture, may not load |
| nomic-embed-text-v1.5 Q8_0 | 146 | embedder, always kept |
| Qwen3-30B-A3B REAP-15B (optional) | 9755 | the Lead's current CPU model |
| Qwen3-30B-A3B-Instruct-2507 (optional) | 18557 | experts in system RAM |

Core set 21.95 GB, optional 28.3 GB, everything 50.3 GB. **Because models are handled one at a time, the peak disk need is the largest single model + embedder + 1 GB:** about 6.2 GB for the core set, about 19.7 GB for the 30B step (put that on `-BigModelsDir`). Minimum install (4B + embedder): 2.6 GB.

## What the page shows
- **Retriever:** hybrid (default) = keyword-style bm25 + embedding vectors fused; also bm25, vector, keyword alone.
- **Sentence rerank:** off / on (default; fixes near-twin facts such as two classes with the same question) / compress (sends fewer sentences: faster, but can cut the answer).
- **Prompt:** V1 = the contract prompt (baseline), V2 to V6 = experiments (V5 default: short, the app writes the page opener), V0 = plain Q&A for diagnosis only.
- **Not-covered threshold slider** and **Teacher view** toggle; **New chat** resets the history.
- **Model dropdown:** switches the LLM (lists `.gguf` files in the models folder).
- **Background panel under every reply (Teacher view):** the query, the top candidates of every retriever, the guard decision, the token budget, the chunks sent, the exact prompt (copy button), timings, and for sample questions the expected page (from the test key; not seen by the model).
- Upload your own PDF with the file button. Loading a long PDF runs in the background with progress in the header.

## Troubleshooting
- **"Vulkan device not found" / runs on the CPU:** update the Adrenalin driver; run `vulkaninfo` (Vulkan SDK) or check `logs\llm.log` for `ggml_vulkan` lines. The launcher prints a warning box when no layers were offloaded.
- **Out of memory (VRAM):** lower `LLM_SLOTS` (each slot adds 2048 tokens of context) or use a smaller model. The benchmark retries with 3 and then 1 slot.
- **The page says "The model is loading":** wait; a model on a hard disk can take minutes to load. See `logs\llm.log`.
- **Antivirus blocks llama-server.exe:** add the `bin` folder as an exclusion.
- **Port 8000 is in use:** set `APP_PORT` (and `LLM_PORT`, `EMBED_PORT`) in `config.env`.
- **"pdftotext not found":** rerun `setup.ps1` (Windows installs poppler into `bin`), or set `POPPLER_BIN`.
- **Downloads stop:** run the same command again; partial files resume.

## Known limits
One question at a time per slot (5 slots = 5 learners at once at best); no login (same trusted Wi-Fi only, never expose it to the internet); one lesson at a time; the 15B and 30B MoE models do not fit in 8 GB of VRAM (their experts stay in system RAM, so speed depends on the CPU and RAM); English only; the Windows scripts were written without a Windows machine to test on, so report any error text.

## Other tools in this folder
`rag_eval.py` (retrieval eval without the LLM; `--generate` pushes prompts through the tutor; `--key`, `--rerank off,on,compress`), `guard_eval.py`, `lang_eval.py`, `rerank_eval.py`, `verify_key.py` + [KEY_FORMAT.md](KEY_FORMAT.md) (answer keys for a new PDF), `rag_ingest.py` (read a PDF into a separate database), `docs/` (earlier evaluation results).

## Rules for this folder
Do not commit third-party PDFs (`uploads/`, `*.pdf` except `sample/lesson.pdf` are ignored), model files, logs or databases. The contract prompt `prompts/prompt_v1_contract.txt` is a byte-for-byte copy of `contracts/ai/socratic_system_prompt.txt` from commit `edfdf5e` (the last commit that changed it; repository base `f82768f`). V1 is never edited.
