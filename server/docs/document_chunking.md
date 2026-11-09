# Lesson text for the AI tutor (step 3.1, #10)

When a teacher uploads a PDF or Word handout, the Hub extracts its text, splits it into ordered `material_chunks` of at most 500 tokens, syncs them to the class, and serves them from `GET /api/materials/{id}/chunks`. Phones never parse documents (PRD FR-3.2). The tutor's prompt builder (step 5.2) picks from these chunks (`rules/socratic-ai-guardrails.md` section 3.1).

## Key files

| File | Purpose |
|---|---|
| `backend/src/services/document_parser.rs` | `Block`, `ChunkDraft`, `ParseError`, and `extract_chunks`: picks the reader by format and never fails |
| `backend/src/services/document_parser/docx.rs` | Word: the paragraphs of `word/document.xml`, headings from their style |
| `backend/src/services/document_parser/pdf.rs` | PDF: the text layer (`pdf-extract`), split into headings and paragraphs by layout |
| `backend/src/services/document_parser/chunk.rs` | Packs blocks into chunks of at most 500 tokens. Pure, no I/O |
| `backend/src/db/material_chunks.rs` | `insert_all` (rows and their sync revisions), `for_material` |
| `backend/src/routes/materials.rs` | Extraction during upload; the chunks route |
| `backend/tests/chunks.rs` | Route access, extraction on upload, sync |
| `backend/tests/fixtures/` | `lesson.docx.zip`, `lesson.pdf`, `scanned.pdf`, rebuilt by `source/make_fixtures.py` (Python and a local Chrome, no network) |

## Data flow

1. **Store.** The upload is stored as in step 3.3 (`materials_and_submissions.md`).
2. **Extract.** For a PDF or DOCX (`DOCUMENT` or `WORKSHEET`; images and videos have no lesson text), `extract_chunks` runs in `spawn_blocking` with a 20-second limit, before the write transaction opens. No CPU work happens under the write lock (TECH_SPEC section 9).
3. **Split into blocks.**
   - **Word:** each `w:p` is a block with the text of its `w:t` runs. A tab or line break inside a run is a space. A paragraph styled `Title` or `Heading…` is a heading. Table cells are ordinary paragraphs. Headers, footers and footnotes are other parts of the ZIP, so they are not read.
   - **PDF:** blank lines separate blocks. A block that is one line of 2 to 60 characters, with no closing punctuation and no lowercase start, is a heading. Bare page numbers and "Page N of M" are dropped. A hyphen at a line end joins the split word; other line breaks are spaces. `pdf-extract` puts a blank line at some ordinary wraps, so a block that starts lowercase continues the block before it unless that one ended a sentence.
4. **Chunk.** A heading closes the open chunk and names the next ones; text before the first heading takes the material's title, as the mock does. Whole paragraphs fill a chunk up to 500 tokens. A longer paragraph splits at sentence ends (`.`, `?`, `!` before a space), and a longer sentence by words. `order_index` starts at 1. At most 1,000 chunks per material.
5. **Commit.** The chunks are inserted in the material's own transaction, each with a class-wide `sync_revisions` row, so they reach phones with the material in the next pull.
6. **Serve.** `GET /api/materials/{id}/chunks` answers class teachers and `ACTIVE` learners (also in an archived class) with `MaterialChunk` objects in `order_index` order. Bearer token only: `?token=` is for the media routes.

## Limits

| Limit | Value | Past it |
|---|---|---|
| Tokens per chunk | 500 (words x 1.4, rounded up; 357 words) | The text moves to the next chunk |
| Chunks per material | 1,000 (`MAX_CHUNKS`) | The rest of the document is left out |
| Extraction time | 20 s (`EXTRACT_TIMEOUT`) | Zero chunks and a warning; the upload still succeeds |
| `word/document.xml` size | 64 MB decompressed | The rest is not read (guards against a ZIP bomb) |
| PDF file size | 50 MB (`MAX_PDF_BYTES`) | Not read: zero chunks and a warning; the upload still succeeds |

Measured on a release build (Windows laptop, 2026-11-09): a 24-page Chrome-printed handout gave 28 chunks in 79 to 107 ms over five uploads, `lesson.pdf` 5 chunks in 11 to 17 ms, and `lesson.docx.zip` 4 chunks in 0 to 1 ms. Issue #10 asks for under 2 seconds.

## Gotchas

- **Extraction never fails an upload.** A scan or photo has no text layer and gets zero chunks (no OCR, PRD FR-3.2). An unreadable file, a parser panic (`lopdf` has known panic paths on broken fonts, so parsing runs under `catch_unwind`) or the timeout also give zero chunks. The tutor then says it cannot help with that file.
- **`token_estimate` is a size hint, not the model's count.** Since #261 the prompt budget is counted with the model's own tokenizer; words x 1.4 undercounts math text by about 8%. The estimate only keeps chunks near 500 tokens and is never sent to clients (contract `MaterialChunk`).
- **Logs name the material, never its text.** One `info` line per upload (`lesson text extracted material_id=… chunks=… ms=…`), and a `warn` with a fixed reason when extraction fails, since a parser's own message can quote the file.
- **A timed-out parse keeps running** on its blocking thread until it ends; the upload has already moved on. The PDF size limit bounds that work: the PDF reader holds the whole file and its objects in memory (about twice the file size), and on the 4 GB reference Hub the model already takes about 2.4 GB. Word files are not limited by size, since only their text part is read.
- **PDF quirks** (`pdf-extract` on Chrome-printed files): some kerned letter pairs come out with a space after the first letter ("H igh", "C louds"), and a table row reads as one line, so a short row such as "Rain Snow" can be taken for a heading. Word documents have neither problem.
- **No backfill.** Materials uploaded before 3.1 have no chunks; uploading the file again creates them.
- **TXT is not extracted.** Since #53 the Hub refuses `.txt` uploads (not a format it keeps), so only PDF and DOCX have lesson text.

## Decisions for the Lead

1. **TXT:** the Tech Spec listed PDF, DOCX and TXT, but the Hub refuses `.txt` uploads since #53. Extract PDF and DOCX only?
2. **Limits:** 1,000 chunks per material and a 20-second extraction limit. Past the cap the material keeps the chunks made so far; after a timeout it gets none.
3. **Heading fallback:** text before the first heading takes the material's title as its heading, as the mock does.
4. **No backfill:** materials uploaded before 3.1 keep zero chunks until uploaded again.
5. **PDF size limit:** PDFs over 50 MB are not read (uploads allow 250 MB). Handouts with a text layer are rarely more than a few MB; most files past the limit are scans with no text anyway.

## Acceptance evidence

| Criterion | Evidence |
|---|---|
| Multi-page PDF extracted in under 2 seconds | Release build: 24 pages, 28 chunks, 79 to 107 ms (log lines in the PR) |
| Clients get JSON chunks without parsing PDFs | `chunks_route_answers_members_only`, `uploading_a_pdf_stores_ordered_chunks`, `uploading_a_docx_stores_chunks_with_its_headings`, `chunks_reach_learners_through_sync` |
| Files with no text get zero chunks | `files_without_text_get_no_chunks`, `a_broken_pdf_still_uploads_with_no_chunks`, `scanned_pdf_has_no_blocks` |
| Chunks stay within 500 tokens, nothing lost | `paragraphs_fill_a_chunk_up_to_500_tokens`, `a_long_paragraph_is_split_at_sentence_ends`, `a_run_without_sentence_ends_is_split_by_words`, `chunk_count_is_capped` |
| `cargo check --all-targets && cargo test` pass | In the PR |

## Manual check (WAN unplugged)

1. Start the Hub (`cargo run --bin lara-hub` in `backend/`) and sign in a teacher.
2. Upload a real handout PDF and a Word file as `DOCUMENT` (`POST /api/materials`, as in `materials_and_submissions.md`).
3. The Hub log shows `lesson text extracted material_id=… chunks=… ms=…` for each.
4. As a learner in the class, `GET /api/materials/<id>/chunks`: the chunks come back in order, each under a heading from the document.
5. Upload a photo of a page as `DOCUMENT`: it uploads, and its chunks are `[]`.
