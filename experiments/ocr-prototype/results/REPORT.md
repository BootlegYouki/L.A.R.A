# Parsing teacher PDFs and Word files for the on-device tutor: extraction, cleanup, chunking, retrieval

Throwaway experiment, not Hub code. All numbers come from the test laptop unless stated: Xubuntu, AMD Ryzen 5 5500U (6 cores / 12 threads), 14 GB RAM, no usable GPU, run over SSH in `~/ocr-experiment/` (user space, no sudo). Dates: 2026-11-20.

## Recommendation

* **PDF with a text layer: `pdftotext` (poppler) in its default reading-order mode, run as a subprocess, then the cleanup below.** On 4 messy PDF variants of the sample lesson it kept 90% hit-in-budget (same as the clean original) where `-layout` fell to 58% (25% on every two-column file). Do not use `-layout`, pdfplumber or pdfminer as the main path.
* **No poppler available (e.g. a Windows Hub without the binary): `pypdf`** (BSD-3, pure Python) is the closest: 89% hit-in-budget on the messy variants, 4 s for 254 pages on one core, 89 MB.
* **.docx: python-docx** (MIT), walking paragraphs and tables in document order (table rows as `a | b | c`, headings from Heading styles or bold-only short lines): 88-90% hit-in-budget, 0.2 s for the 48-page-equivalent file.
* **Cleanup (always on, all cheap):** NFKC (fixes ligatures), de-hyphenate line breaks, drop lines repeated on >= 40% of pages and `Page N`, rejoin wrapped lines, keep table rows together, detect headings. On this key they change *retrieval* by 0-3 points (inside the noise of a 40-question key), but without them the quoted sentence is intact in only 93% of the extracted text instead of 100% (hyphen breaks), so keep them as insurance for the SLM, which reads the chunk text, not just the ranking.
* **Chunking: no measured reason to change the desktop rule** (page-based, ~180 words, 1 paragraph overlap: 90% hit-in-budget). Heading-aware chunks of ~250 words with soft heading boundaries tie it (90%) and are the only option for .docx, which has no pages. Hard heading boundaries with small chunks are worse (79-82%): do not split a chunk at every sub-heading.
* **Retrieval: BM25 is not what limits the score.** All good pipelines miss the same ~10% of questions: the paraphrased ones (about 67% hit-in-budget vs 100% for direct, table, near-twin and two-part questions). A small embedding model (bge-small-en-v1.5) did not raise hit-in-budget (91% vs 90%) but hybrid BM25+vector raised hit@1 79 -> 82% and near-twin ordering 88 -> 92% (1-2 questions). Decline detection: the BM25 top score works (93-99% balanced accuracy at the desktop threshold 5.0, sensitive to chunk size); the cosine score alone is poor (67%), as in the desktop experiment.
* **Minimum Hub hardware for parsing: nothing special.** pdftotext + cleanup + chunking of a 254-page text PDF took 0.56 s and 16 MB (plus a ~20 MB poppler process) on one pinned logical CPU; a 62-page lesson 0.08 s. No GPU, no OCR. The Hub's hardware floor is set by the LLM, not by parsing.
* **Scanned PDFs are a separate, optional path.** Tesseract (3.0 s/page, 108 MB, one thread) is the practical fallback; DeepSeek-OCR was dropped (32-35 s/page, 5.3 GB). See section 8.

Ingest times measured here: 250-page text PDF **0.56 s** (Wells text layer, 254 pages); 20-page handout **< 0.1 s** (62 pages: 0.08 s). Scanned fallback, extrapolated from 20-page runs: 250-page scanned book about **12.5 min** with one Tesseract process, about **3.2 min** with six in parallel; 20-page photocopy handout about **1.7 min** single, about **30 s** with six.

## 0. What changed from the first plan

The first plan compared OCR engines (pdftotext, Tesseract, RapidOCR, DeepSeek-OCR) on scans by character error rate. The Lead redirected it: the parser exists only so the 1B-class on-device model retrieves the right chunks, so parsing is now judged only by retrieval, on **text-layer PDFs and .docx**, with messy generated variants. Consequences: DeepSeek-OCR stopped after 5 pages and its model deleted; a second batch of small OCR models (GLM-OCR, PaddleOCR-VL, LightOnOCR, granite-docling) was downloaded but never run and deleted; the Tesseract/RapidOCR/pdftotext numbers already measured are kept in section 8 and `ocr_fallback_tables.md`. Nothing the PR already referenced was renamed (`common.py`, `prep_inputs.py`, `run_engine.py`, `run_all.sh`, `score.py`, `rechunk_eval.py`, `key_wells.json` stay; the new work is in `text/`).

## 1. Setup and versions

| thing | version / source | licence |
|---|---|---|
| poppler `pdftotext` | 26.07.0 (system package on the laptop) | GPL-2/3: call as a subprocess; shipping the binary with the Hub means shipping its licence |
| pypdf | 7.1.1 (PyPI) | BSD-3 |
| pdfminer.six | 20260107 (PyPI) | MIT |
| pdfplumber | 0.11.10 (PyPI, built on pdfminer) | MIT |
| python-docx | 1.2.0 (PyPI) | MIT |
| fastembed + BAAI/bge-small-en-v1.5 | 0.9.1, ONNX runtime 1.32.0 (CPU) | Apache-2.0 / MIT |
| SQLite FTS5 BM25 (`porter unicode61`) | Python 3.12.16 (conda-forge) `sqlite3` | public domain |
| ReportLab 5.1.0, pyphen 0.18.1 | only to *generate* the variants | BSD / GPL-LGPL-MPL |

AGPL libraries (PyMuPDF) were not used. Everything ran on CPU.

## 2. Inputs, key and metrics

* **Lesson:** the 62-page sample from PR #337 (`sample/lesson.pdf`, ReportLab, Helvetica). Its text was parsed into blocks (headings, paragraphs, bullets, 23 tables) and **re-rendered** with the same words into: **v1** two columns with ruled tables; **v2** single column, justified, real line-end hyphenation; **v3** DejaVu Serif with Unicode ligature code points (fi fl ff ffi); **v4** all of those together (two-column + hyphens + ligatures); **v5** .docx with Heading styles, bullet styles, tables, header and page-number field; **v6** .docx where headings are bold paragraphs. **v0** is the original. Every variant has a running header and `Page N` on every page. All 40 key quotes were checked to exist in the re-rendered text.
* **Key:** `sample/answer_key.json` from PR #337: 40 questions (10 direct, 12 paraphrase, 8 table/list, 6 near-twin, 4 two-part) + 8 not-in-lesson + 4 answer-seeking. Page numbers change between layouts, so a hit means **a retrieved chunk contains the key's quoted sentence** (normalised, fuzzy ratio >= 88, so a hyphen break still counts as "right place"; whether the answer text survived is measured by the other metrics).
* **Metrics:** hit@1, hit@3; **hit-in-budget** = the quote is in the (max 3) chunks that fit 1472 tokens, as in the desktop experiment; **answer text in budget** = all key tokens appear verbatim in those chunks; **near-twin** = the chunk with the question's own fact outranks the twin's chunk (6 questions); **decline** = balanced accuracy of "top BM25 score >= threshold" separating the 44 in-scope from the 8 not-in-lesson questions, at the desktop's 5.0 and at the best threshold (optimistic: tuned on the same 8 negatives); **intact anywhere** = the answer tokens / the whole quoted sentence exist somewhere in the extracted text.
* **Reproduction check:** the desktop experiment's page chunker on the original lesson with `pdftotext -layout` gives hit@1 82.5 / hit@3 87.5 / hit-in-budget 87.5 / answer 87.5 / near-twin 4 of 6 here, against 82 / 90 / 92 / 90 / 4 of 6 in the desktop report (its token counts came from the real tokenizer; mine are words x 1.4). Close, not identical.
* **Noise:** one question is 2.5 points. Differences under about 5 points are not evidence.

## 3. Extractors (full cleanup, heading chunks ~250 words, BM25)

Hit-in-budget per variant; the right-hand columns are the mean over the messy variants (v1-v4 for PDFs, v5-v6 for .docx).

| extractor | v0 original | v1 two-column | v2 hyphens | v3 ligature font | v4 all PDF tricks | v5 docx styled | v6 docx bold-only | mean of messy: hit@1 | hit-in-budget | answer text | near-twin | quote intact |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| pdftotext | 90% | 90% | 90% | 90% | 90% | - | - | 79% | 90% | 90% | 88% | 99% |
| pdftotext-layout | 90% | 25% | 90% | 90% | 25% | - | - | 52% | 58% | 78% | 42% | 58% |
| pdftotext-raw | 85% | 88% | 90% | 90% | 90% | - | - | 86% | 89% | 89% | 96% | 100% |
| pypdf | 85% | 85% | 90% | 90% | 92% | - | - | 76% | 89% | 89% | 83% | 100% |
| pdfminer | 75% | 90% | 78% | 78% | 92% | - | - | 71% | 84% | 82% | 71% | 88% |
| pdfplumber | 85% | 30% | 88% | 90% | 28% | - | - | 51% | 59% | 79% | 42% | 59% |
| pdfplumber+tables | 88% | 35% | 88% | 90% | 30% | - | - | 50% | 61% | 80% | 42% | 63% |
| docx | - | - | - | - | - | 88% | 88% | 75% | 88% | 88% | 67% | 100% |
| docx-text-only | - | - | - | - | - | 90% | 90% | 80% | 90% | 90% | 83% | 100% |

Per question type (mean over messy variants, hit-in-budget / answer text in budget):

| extractor | direct (10) | paraphrase (12) | table (8) | near-twin (6) | two-part (4) |
|---|---|---|---|---|---|
| pdftotext | 100 / 100 | 67 / 67 | 100 / 100 | 100 / 100 | 100 / 100 |
| pdftotext -layout | 55 / 95 | 35 / 52 | 84 / 84 | 50 / 88 | 88 / 81 |
| pdftotext -raw | 100 / 100 | 69 / 69 | 97 / 97 | 96 / 96 | 100 / 100 |
| pypdf | 100 / 100 | 69 / 69 | 97 / 97 | 96 / 96 | 100 / 100 |
| pdfminer.six | 100 / 100 | 67 / 60 | 72 / 72 | 100 / 100 | 100 / 100 |
| pdfplumber + tables | 58 / 95 | 42 / 58 | 81 / 81 | 54 / 92 | 94 / 88 |
| python-docx | 100 / 100 | 58 / 58 | 100 / 100 | 100 / 100 | 100 / 100 |

Reading it: `-layout` and pdfplumber keep the physical line order, so in two columns they glue the left and right column together line by line; the words are still there (answer text 65-78%) but the quoted sentences are cut apart (quote intact 15-30% on the two-column variants), so retrieval collapses. Default pdftotext and pypdf follow the reading order and do not care. pdfminer is slower and loses table cells. pdfplumber's table finder only sees ruled tables and, in two columns, still mixes the columns; it did not help.

## 4. What each cleanup step contributes (ablation)

Mean over the 4 messy PDF variants, same chunker. "Minus X" removes one step from the full pipeline.

pdftotext (default):

| pipeline | hit@1 | hit-in-budget | answer text in budget | near-twin (6) | answer tokens intact anywhere | quote intact anywhere |
| --- | --- | --- | --- | --- | --- | --- |
| all cleanup on | 79% | 90% | 90% | 88% | 100% | 99% |
| no cleanup at all | 72% | 89% | 89% | 83% | 100% | 99% |
| all on, minus ligature fix (NFKC) | 79% | 90% | 90% | 83% | 100% | 99% |
| all on, minus de-hyphenate | 79% | 90% | 90% | 88% | 100% | 99% |
| all on, minus drop repeated header/footer + page numbers | 79% | 90% | 90% | 88% | 100% | 99% |
| all on, minus rejoin wrapped lines | 74% | 88% | 89% | 83% | 100% | 98% |
| all on, minus table rows kept as one unit | 79% | 90% | 90% | 88% | 100% | 99% |
| all on, minus heading detection | 74% | 86% | 86% | 88% | 100% | 99% |

pdftotext -raw:

| pipeline | hit@1 | hit-in-budget | answer text in budget | near-twin (6) | answer tokens intact anywhere | quote intact anywhere |
| --- | --- | --- | --- | --- | --- | --- |
| all cleanup on | 86% | 89% | 89% | 96% | 100% | 100% |
| no cleanup at all | 76% | 91% | 89% | 79% | 99% | 93% |
| all on, minus ligature fix (NFKC) | 86% | 89% | 89% | 96% | 100% | 100% |
| all on, minus de-hyphenate | 86% | 89% | 88% | 96% | 99% | 94% |
| all on, minus drop repeated header/footer + page numbers | 85% | 88% | 88% | 100% | 100% | 100% |
| all on, minus rejoin wrapped lines | 80% | 89% | 89% | 88% | 100% | 99% |
| all on, minus table rows kept as one unit | 86% | 89% | 89% | 96% | 100% | 100% |
| all on, minus heading detection | 88% | 89% | 89% | 96% | 100% | 100% |

pypdf:

| pipeline | hit@1 | hit-in-budget | answer text in budget | near-twin (6) | answer tokens intact anywhere | quote intact anywhere |
| --- | --- | --- | --- | --- | --- | --- |
| all cleanup on | 76% | 89% | 89% | 83% | 100% | 100% |
| no cleanup at all | 76% | 89% | 88% | 83% | 99% | 93% |
| all on, minus ligature fix (NFKC) | 76% | 89% | 89% | 83% | 100% | 100% |
| all on, minus de-hyphenate | 76% | 89% | 88% | 83% | 99% | 94% |
| all on, minus drop repeated header/footer + page numbers | 72% | 86% | 86% | 79% | 100% | 100% |
| all on, minus rejoin wrapped lines | 81% | 89% | 89% | 92% | 100% | 99% |
| all on, minus table rows kept as one unit | 76% | 89% | 89% | 83% | 100% | 100% |
| all on, minus heading detection | 75% | 88% | 88% | 75% | 100% | 100% |

* **De-hyphenate** is the one that protects the text: without it the quoted sentence is intact in 94% of the text instead of 100% (raw, pypdf). Retrieval barely moves because BM25 only needs the other words of the sentence.
* **Ligature fix (NFKC):** no effect measurable here because none of the 40 questions or answers contains an fi/fl/ff word. It is free and correct, but this key cannot show its value. The *bad* case, where the font gives no Unicode for the ligature glyph (text comes out as `e cient`), was not generated.
* **Header/footer removal:** 0-3 points, inside the noise; it removes about 5 junk chunks per 120. Without it, `pypdf` loses 3 points of hit-in-budget.
* **Rejoin wrapped lines:** needed for sensible chunks; without it each line is a unit and chunking behaves like fixed windows (same retrieval here, but no paragraph integrity). With `pypdf` and `-raw`, which put no blank lines between paragraphs, rejoin builds giant paragraphs, so the chunker also cuts any paragraph over 1.3x the chunk size at sentence ends (this fix is in `pipeline.py`; before it `pypdf` scored 58%).
* **Table rows as one unit** and **heading detection:** no gain on the PDFs in default mode. Unruled tables come out of pdftotext as readable cell lines inside the same paragraph block, so the table questions stay at 97-100% without any table logic. Detecting headings *hurts* if headings force chunk boundaries (see section 5).
* **.docx:** the cleanup steps are not applied (python-docx returns paragraphs and tables directly: no hyphens, no ligatures, headers excluded), so all rows are identical. Heading styles and bold-only headings gave identical scores (the bold rule recovers them), and ignoring headings entirely scored the same (90 vs 88%, one question).

## 5. Chunking (pdftotext default, full cleanup, BM25, mean over messy PDFs)

| chunker | hit@1 | hit-in-budget | answer text in budget | near-twin (6) | chunks | quote intact anywhere |
| --- | --- | --- | --- | --- | --- | --- |
| page 180w overlap 1 | 82% | 90% | 90% | 88% | 136 | 99% |
| window 120w overlap 0 | 76% | 88% | 88% | 100% | 234 | 99% |
| window 180w overlap 0 | 79% | 88% | 88% | 88% | 140 | 99% |
| window 250w overlap 0 | 76% | 88% | 88% | 79% | 96 | 99% |
| heading 120w overlap 0 min-fill 0 | 72% | 79% | 75% | 83% | 238 | 86% |
| heading 120w overlap 1 min-fill 0 | 76% | 81% | 76% | 88% | 258 | 86% |
| heading 180w overlap 0 min-fill 0 | 71% | 82% | 78% | 88% | 180 | 86% |
| heading 180w overlap 1 min-fill 0 | 72% | 81% | 77% | 88% | 191 | 86% |
| heading 120w overlap 1 min-fill 0.5 | 79% | 86% | 85% | 88% | 240 | 97% |
| heading 180w overlap 0 min-fill 0.5 | 75% | 86% | 87% | 88% | 147 | 98% |
| heading 180w overlap 1 min-fill 0.5 | 78% | 86% | 86% | 88% | 162 | 98% |
| heading 250w overlap 1 min-fill 0.5 | 79% | 90% | 90% | 88% | 117 | 99% |
| heading 350w overlap 1 min-fill 0.5 | 79% | 88% | 88% | 100% | 86 | 98% |
| heading 180w overlap 1 min-fill 1.0 | 76% | 86% | 86% | 88% | 160 | 99% |
| heading 180w overlap 1 min-fill 0.5 (tables may split) | 78% | 86% | 86% | 88% | 162 | 98% |

* Page-based and ~250-word heading-aware tie at 90%. Fixed windows are 88%.
* **Hard heading boundaries are the loser** (79-82%): sections in this lesson are 80-150 words, so splitting at each one makes small chunks, three of which cover too little text, and some answers fall on a boundary (quote intact 86%). `min-fill 0.5` (start a new chunk at a heading only if the current one is at least half full) removes the loss.
* Overlap of 1 paragraph: 0-1 points. Table kept whole vs allowed to split: no difference on this lesson (tables are 30-60 words).
* Near-twin ordering varies 79-100% between chunkers; with only 6 questions that is 1 question.

## 6. Retrieval: BM25 vs embeddings (heading 250/1, messy PDFs)

| retriever | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline best-thr |
| --- | --- | --- | --- | --- | --- | --- |
| bm25 | 79% | 90% | 90% | 90% | 88% | 99% |
| vector | 73% | 91% | 91% | 91% | 67% | 67% |
| hybrid | 82% | 90% | 90% | 90% | 92% | 67% |

Embeddings: bge-small-en-v1.5 (ONNX, CPU) at 9 chunks/s on this laptop, so a 62-page lesson (117 chunks) takes about 13 s and a 254-page book (about 450 chunks) about 50 s. Process peak RAM was 3.1 GB, but that process had embedded all four variants in one go, so treat it as an upper bound, not a requirement. Decline for vector/hybrid is the best cosine threshold, tuned on the same 8 negatives; 67% means the cosine score does not separate the not-in-lesson questions at all.

## 7. Speed and RAM of the chosen pipelines (this laptop)

Extraction + cleanup + chunking, best of 3, all cores available:

| extractor | file | pages | total s | s/page |
| --- | --- | --- | --- | --- |
| pdftotext | v0_orig.pdf | 62 | 0.08 | 0.001 |
| pdftotext | v4_messy.pdf | 30 | 0.09 | 0.003 |
| pdftotext-layout | v0_orig.pdf | 62 | 0.08 | 0.001 |
| pdftotext-layout | v4_messy.pdf | 30 | 0.09 | 0.003 |
| pdftotext-raw | v0_orig.pdf | 62 | 0.07 | 0.001 |
| pdftotext-raw | v4_messy.pdf | 30 | 0.08 | 0.003 |
| pypdf | v0_orig.pdf | 62 | 0.28 | 0.005 |
| pypdf | v4_messy.pdf | 30 | 0.31 | 0.010 |
| pdfminer | v0_orig.pdf | 62 | 1.37 | 0.022 |
| pdfminer | v4_messy.pdf | 30 | 1.23 | 0.041 |
| pdfplumber | v0_orig.pdf | 62 | 3.08 | 0.050 |
| pdfplumber | v4_messy.pdf | 30 | 2.99 | 0.100 |
| pdfplumber+tables | v0_orig.pdf | 62 | 3.24 | 0.052 |
| pdfplumber+tables | v4_messy.pdf | 30 | 3.19 | 0.106 |
| docx | v5_docx.docx | 48 (est.) | 0.20 | 0.004 |
| docx-text-only | v5_docx.docx | 48 (est.) | 0.19 | 0.004 |

(.docx "pages" are an estimate of characters / 2500; the docx file has no pages.)

Isolated runs, one process each (`results/exp_rss.txt`, `exp_onecore.txt`): pdftotext child process 19.6 MB; Python around it 13-16 MB; pypdf 39 MB (62 pages) / 89 MB (254 pages); pdfminer 38 MB; python-docx 34 MB; pdfplumber 271-278 MB. **Pinned to one logical CPU (`taskset -c 0`):** Wells text layer, 254 pages: pdftotext 0.54 s total, pypdf 4.1 s. A bare Python 3 process here is 9 MB.

**Minimum hardware for parsing:** any 64-bit PC with one core and about 100 MB free RAM, no GPU, no OCR, for text PDFs and .docx. This is inferred from the one-core run on a Ryzen 5 5500U (a fast core); a 10-year-old Celeron was not tried and could be 3-5x slower, which would still be seconds per book.

## 8. Scanned-PDF fallback (first plan, measured before the redirect)

Inputs: A = the Wells scan (254 pages, 300 ppi, archive.org text layer), B = 20 pages of the lesson rendered clean, C = those 20 pages degraded (1 degree rotation, grey background, noise sigma 14, JPEG q40, 150 ppi, image-only PDF). Full tables: `ocr_fallback_tables.md`.

| | pdftotext | Tesseract 5.5.4 (eng, 300 dpi) | RapidOCR 3.10 (onnxruntime, PP-OCRv6 small) | DeepSeek-OCR (ggml-org Q8_0, llama.cpp b11938) |
|---|---|---|---|---|
| image-only scan C: CER / WER | 100% / 100% (no text) | 1.2% / 3.4% | 2.6% / 3.4% | not run |
| clean render B: CER / WER | 0 (it is the ground truth) | 0.7% / 1.7% | 3.7% / 4.4% | not run |
| Wells key: answer tokens on the key page | 25/27, misses Q08, Q15 (the key was written from this text layer) | 24/27 (Q08, Q15; Q27 is an OCR typo copied into the key) | 24/27 (same three) | not run |
| median s/page incl. rasterising (A, 20 pages) | 0.03 | 3.0 | 3.4 (p95 4.0) | 32-35 on 5 pages, then stopped |
| peak RAM, one process | 20 MB | 108 MB | 758 MB | 5.3 GB |

Tesseract with 6 parallel processes did 20 pages in 15.3 s wall (0.77 s/page). Pages of C cost more (Tesseract 5.2 s, RapidOCR 5.4 s per page). These are lower than the 3.6 and 10+ s/page quoted in the redirect; the first RapidOCR run was slowed by a 10 s/page poppler upscaling of C that I replaced with a Pillow upscale, and everything above is from the corrected runs. DeepSeek-OCR v2 needs an unmerged llama.cpp pull request (not a drop-in) and was not tried; the iGPU (Radeon Vega, no CUDA) was not used by any candidate. Parsing a text-layer PDF with OCR makes no sense, and a scan has to be OCR'd: Tesseract is the only candidate with an acceptable cost (CPU, 108 MB).

## 9. What each layout trick costs (hit-in-budget, default pdftotext unless noted)

| trick | cost with pdftotext default | cost with the wrong tool |
|---|---|---|
| two columns | 0 (90% -> 90%) | `-layout` 90% -> 25%; pdfplumber 85% -> 30% |
| running header/footer + page numbers | 0-3 points without removal; about 5 junk chunks per 120 | pypdf -3 points |
| hyphenated line breaks | 0 on retrieval, but the quote is only 94% intact without de-hyphenating | none |
| ligature font | 0 here (key has no fi/fl/ff answers) | untested for glyphs without Unicode |
| tables (unruled, and ruled in two columns) | 0 (table questions 100%) | pdfminer 72%, pdfplumber 81% |
| .docx tables, headings | 0 (100% on table questions) | none |

## 10. Not verified

* **Real teacher files.** All variants were made from one lesson by ReportLab and python-docx. Output of Word, LibreOffice, Google Docs, Canva or phone scanners (different content-stream order, tagged PDFs, text drawn as vectors) was not tried; `pdftotext -raw` follows the content stream and could scramble on files where the default order is right.
* **Image-only pages inside text PDFs** (a figure with a caption baked into the image), embedded fonts without Unicode maps (broken ligatures, `e cient`), password-protected and corrupt PDFs.
* **Filipino/Taglish text, math formulas, handwriting, scanned tables beyond the pages tried.** The key is English prose and tables.
* **A 40-question key is small:** one question = 2.5 points. Chunking and cleanup differences of 1-3 points are noise; only the layout/extractor collapses (`-layout`, pdfplumber on two columns) and the 79-82% hard-heading loss are clearly above it. The decline threshold was tuned on the same 8 negatives.
* **The key was not rewritten per layout.** Hits are matched by quoted text, so a question whose answer sat across two columns or pages is credited if either chunk has the quote.
* **Embeddings:** one small model, one configuration, no per-type breakdown; peak RAM not isolated. The phone's model and its real tokenizer were not used (tokens = words x 1.4).
* **Other CPUs, GPU, low-RAM machines:** only the Ryzen 5 5500U (also pinned to one logical CPU); no run on a weak school PC, no GPU run.
* **.docx** was generated by python-docx and read by python-docx; Word-authored files (text boxes, tracked changes, nested tables, images) were not tried; .doc/.odt/.pptx not covered.
* **Scanned fallback:** no end-to-end retrieval run on OCR output (stopped by the redirect); DeepSeek-OCR measured on 5 pages only; GLM-OCR, PaddleOCR-VL, LightOnOCR and granite-docling were downloaded and deleted without being run.
