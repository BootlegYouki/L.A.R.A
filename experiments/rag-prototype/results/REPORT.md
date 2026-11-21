# L.A.R.A RAG GPU experiment report

## 1. Hardware, runtime and baseline configuration

- GPU: `Vulkan0: Radeon RX 570 Series (8192 MiB, 7367 MiB free)`.
- Driver: AMD 31.0.21923.11000; Vulkan 1.3.260.
- llama.cpp: 0.7.0-dev, build 11938, commit 19cce6919.
- CPU: AMD Ryzen 7 5700G, 16 threads; RAM 31.9 GiB.
- Baseline model: Qwen3-4B-Instruct-2507 Q4_K_M; server showed all 37/37 layers offloaded. Baseline app configuration: five slots, 2048 context tokens per slot, `-ngl 99`, four CPU threads, hybrid retrieval, rerank on, `MAX_CHUNKS=3`, threshold 0.65, batch/ubatch 2048/512.
- All model weights, PDF, databases, cache, and raw logs were held on E:. The C: mobile checkout stayed on its original branch and was not modified.

## 2. GPU versus CPU speed

`gpu_bench.ps1 -Yes` used llama-bench with three repetitions; GPU used `-ngl 99 -t 4`, CPU used `-ngl 0 -t 12`.

| workload | GPU tok/s | CPU tok/s | GPU/CPU |
|---|---:|---:|---:|
| pp512 | 191.84 | 129.37 | 1.48x |
| pp1024 | 185.97 | 127.76 | 1.46x |
| tg128 | 21.52 | 7.12 | 3.02x |

The benchmark configuration was verified from the recorded command and llama-bench JSON: Vulkan backend, RX 570, `n_gpu_layers=99`, `n_batch=2048`, `n_ubatch=512`, three samples per speed row. Baseline server logs showed `Vulkan devices seen: Radeon RX 570 Series` and `Layers on GPU: 37 of 37`.

## 3. New real PDF and verified key

- *Text-book of Biology*, Volume II: Invertebrates and Plants, H. G. Wells and G. B. Howes (1893), 254 PDF pages. [PDF source](https://archive.org/download/textbookofbiolog02well/textbookofbiolog02well.pdf); public-domain catalog record: [Wellcome Collection](https://wellcomecollection.org/works/tt9bxn7g).
- `pdftotext` produced readable text on five sampled pages. It has broken ligatures, split words, OCR spelling artifacts, and illustration pages with little selectable text. Tables are flattened; mathematical formula quality was not assessed.
- Ingest completed on the embedding GPU: 254 pages, 476 chunks and 476 vectors, 84.2 seconds.
- Key page numbers follow PDF page order (`pdfinfo`/`pdftotext`), not printed page labels. `verify_key.py` result after quote corrections: 30 entries, 0 FAIL, 0 WARN; the three out-of-scope entries were intentionally skipped.
- The source brief’s requested category counts sum to 34 (10 direct + 6 paraphrase + 4 table/list + 8 questions for four pairs + 3 two-part + 3 out-of-scope). A 30-question key cannot satisfy all as disjoint categories. This key has two near-duplicate pairs (four distractor entries); the contradiction is documented rather than pretending it contains four pairs.

## 4. Retrieval evaluation

The original sample PDF result is shown beside the new PDF. New-PDF scores use the corrected key.

| metric (hybrid) | sample PDF | Wells PDF |
|---|---:|---:|
| hit@1 | 88% | 85% |
| hit@3 | 98% | 89% |
| hit-in-budget | 100% | 89% |
| expected-answer text in budget | 100% | 81% |
| distractor right outranks wrong | 4/6 | 4/4 |

On the Wells PDF, vector retrieval hit-in-budget was 100%, compared with hybrid 89%. Hybrid missed Q01, Q12 and Q15; the lowest in-scope cosine was 0.702, while the three out-of-scope questions ranged 0.553–0.602. The Wells result is clearly worse on hit-in-budget and answer text than the sample, which is consistent with OCR artifacts, long-context chunking, and paraphrase retrieval. Table layout was flattened by extraction.

Sentence rerank hybrid results (off/on/compress) were: correct top page 85/81/81%; page sent 89/93/93%; pair ordering 100/100/100%; expected answer text present 81/81/59%. Compress used fewer lesson tokens (513 average) but sharply reduced answer coverage. With `MAX_CHUNKS=2`, hit-in-budget fell to 85% and answer text to 78%; compression plus two chunks reduced answer text to 56%. These are unsafe context reductions and were not retained.

## 5. Concurrency and five-learner target

Primary baseline used six requests per learner; “per-stream min” is the slowest generated stream in that run. TTFT includes queue wait. All runs had zero errors.

| users | requests | TTFT p50 s | TTFT p95 s | stream tok/s p50 | stream tok/s min | total tok/s | wall s |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 6 | 14.7 | 17.8 | 17.9 | 17.9 | 3.0 | 82 |
| 2 | 12 | 5.7 | 19.3 | 10.2 | 0.9 | 5.2 | 74 |
| 3 | 18 | 5.8 | 15.7 | 7.7 | 1.8 | 8.5 | 78 |
| 5 | 30 | 6.1 | 22.0 | 2.2 | 1.2 | 7.5 | 142 |
| 8 | 48 | 21.2 | 36.6 | 4.1 | 0.7 | 8.8 | 197 |

At five users, the target (p95 TTFT <=10s and every stream >=8 tok/s) failed both clauses. Optimization attempts, all five users, six requests each, zero errors:

| setting | p50/p95 TTFT s | stream p50/min tok/s | total tok/s | outcome |
|---|---:|---:|---:|---|
| baseline: 5 slots, 3 chunks, rerank on, b2048/ub512 | 6.1/22.0 | 2.2/1.2 | 7.5 | best safe setting; target fails |
| 4 slots | 18.8/61.1 | 2.2/0.4 | 3.9 | worse |
| 2 chunks | 12.0/69.4 | 1.6/0.4 | 3.7 | worse and lower retrieval coverage |
| 2 chunks + compress | 9.3/40.7 | 1.8/0.6 | 5.1 | worse and unsafe answer coverage |
| 2 chunks + compress + b512/ub128 | 4.0/14.9 | 3.2/1.5 | 10.7 | faster throughput, target fails and context unsafe |
| full context + b512/ub128 | 7.1/38.7 | 2.5/0.9 | 6.9 | worse than baseline |

Best measured safe configuration remains five slots, three chunks, rerank on, batch/ubatch 2048/512. It fails the target; one RX 570 cannot meet the stated five-learner service target with the tested models/settings.

## 6. Prompt comparison (corrected Wells key)

Prompt `rag_eval.py` auto-flags, hybrid retrieval, threshold 0.65. `POINTS` is right page/heading, `CLEAN` requires clue + points + no automated leak. Raw leakage can be higher than shown leakage because the Hub guard removes some sentences.

| metric | V1 | V2 | V4 | V5 | V6 | V7 |
|---|---:|---:|---:|---:|---:|---:|
| Q leaks / 27 (lower better) | 10 | 3 | 3 | 4 | 2 | 6 |
| Q right page/heading / 27 | 0 | 13 | 14 | 22 | 22 | 22 |
| O declined / 3 | 3 | 3 | 3 | 0 | 0 | 2 |
| raw Q+A leaks / 27 | 10 | 9 | 3 | 4 | 3 | 7 |
| bad citations raw / 32 | 0 | 1 | 1 | 1 | 1 | 0 |
| clean / 27 | 0 | 12 | 12 | 18 | 20 | 16 |
| median TTFT s | 12.11 | 11.70 | 9.48 | 12.93 | 13.02 | 13.03 |
| exact English-only Taglish response / 2 | 1 | 2 | 2 | 2 | 2 | 2 |

V6 gave the fewest automated in-scope leaks and the highest clean count, but declined none of the three out-of-scope prompts and manual inspection showed it answered from unrelated lesson text. V7 was created because V6 did not enforce direct coverage and failed all out-of-scope declines. V7 improves declines to 2/3 and prevents some off-topic explanations, but has six flagged leaks and one remaining misfire; it is not an overall improvement. Prompt auto-flags also undercount semantic leakage: V5 produced a general DNA answer to O1, yet the automated flag did not catch it.

V7 adds an explicit direct-coverage check: if the retrieved material does not directly support the asked fact, decline instead of using a related topic or page opener. It still fails O3 by citing an unrelated mollusk section and asking for clarification. V7 source: `prompts/prompt_v7.txt`; implementation is registered in `rag.py` and the prompt dropdown in `index.html`.

### Manual review of 15 replies and examples

I reviewed three Wells-PDF replies each from V1, V2, V4, V5, V6 and V7 (18 total). The evaluation flags are a first pass, not a semantic safety judgment.

Five better attempts (short excerpts):

- V2 Q03: “What part of the seed is described as being ‘embedded’?” — leads the learner back to the clue without naming the keyed term.
- V4 Q03: “What is described as the mass of store material inside the testa?” — asks the learner to locate the sentence.
- V6 Q20: “What do these cases produce?” — points to page 104 and asks a short question.
- V7 Q03: “What do you think the ‘store material’ inside the testa is used for?” — grounded prompt without naming the expected term.
- V7 O1: “This lesson does not cover that question. Please return to your lesson or ask your teacher.” — suitable out-of-scope handling.

Five failures:

- V1 Q03 names “albumen” and “endosperm” directly.
- V1 Q07 repeats the definition of optimum temperature and asks the learner to restate it.
- V5 O1 answers DNA proofreading from general knowledge despite no coverage.
- V6 O3 analogizes mollusk body movement to tectonic plate movement.
- V7 O3 cites an unrelated shell section and asks the learner to clarify.

## 7. Model comparison and optional downloads

Six small models (baseline plus five candidates) ran one at a time and were removed after their runs. The REAP-15B MoE was also downloaded one at a time and tried in two expert-placement configurations; both paged and missed the target. The original 30B file (17.28 GiB) was not downloaded: only 4.86 GiB RAM would remain from the measured available 22.14 GiB, before runtime/OS overhead, and the smaller REAP runs already showed heavy paging in the mixed placement trial. `MODEL_COMPARISON.md` contains measured speeds, concurrency, caveats and exact model results.

## 8. Windows errors and corrections

- `error: invalid argument: --no-mmap` — this llama.cpp build lacks the legacy flag. The experiment-local launcher now uses `--load-mode none`; retry loaded REAP-15B.
- WMI `Get-CimInstance Win32_OperatingSystem` returned Access denied. RAM was checked with `GlobalMemoryStatusEx` instead; it reported 31.9 GiB total and 22.14 GiB available before the 30B decision.
- Initial retrieval rerun failed with `ConnectionRefusedError: [WinError 10061] No connection could be made because the target machine actively refused it`; the embedding service had stopped. Restarted `run.ps1` on E: and retrieval completed.
- Windows PowerShell `Set-Content -Encoding UTF8` inserted a BOM which `verify_key.py` rejected as `JSONDecodeError: Unexpected UTF-8 BOM`; normalized the file to UTF-8 without BOM and reran verification successfully.

## 9. Five-line result

- Vulkan sees the Radeon RX 570 and Qwen3-4B offloads all 37/37 layers.
- GPU speed: 192/186/21.5 tok/s for pp512/pp1024/tg128; CPU: 129/128/7.1 tok/s.
- The best measured safe setting is five slots, three chunks, rerank on, batch/ubatch 2048/512.
- At five users it measured p95 TTFT 22.0 s and minimum stream 1.2 tok/s; the target fails.
- No tested model or V1–V7 prompt combination meets the five-user service target; V6 has the best automated clean score, V7 partially improves out-of-scope handling.