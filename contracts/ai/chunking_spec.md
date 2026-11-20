# Parsing and chunking spec (DRAFT, version `0-draft`)

> **DRAFT. Numbers PENDING.** Every number and cleanup rule marked **PENDING** is a placeholder taken from the Hub's old chunker (`server/docs/document_chunking.md`, built in #10). The exact numbers, the cleanup rules and the golden fixtures stay **PENDING**: settling them is the first task of the clients' implementation issues ([MOBILE PD3] and [DESKTOP PD3]), measured on real phones and in Rust or TypeScript, and delivered to the Lead as a `contract-change`. Until then the fixtures in [`fixtures/`](./fixtures/) are **not normative** (`"normative": false`): they only define the file format and show the shape of the answer. Their chunk boundaries use the old Hub rule (a hard split at every heading) and **will change**, because the findings in section 8 say soft heading boundaries are better.
>
> This file is lead-owned. A change to the spec or a fixture is a `contract-change` PR and bumps `spec_version`.

## 1. What this is

Since 2026-11-20 **the devices parse documents, not the Hub** ([decision record](../../docs/decisions/2026-11-20-no-ai-on-hub.md)). A teacher uploads a PDF (text layer) or `.docx` as an ordinary handout. The Hub stores and syncs the *file*. **Lazily**, when a learner taps the AI button to open the chat, the device extracts the text of the class's allowed materials (files already on the device), cleans it, cuts it into chunks and keeps them in its **own** local SQLite `tutor_chunks` table (never synced, the Hub keeps none). Nothing is parsed in the background, at sync time or on the Hub. The on-device tutor retrieves from those chunks. They are cached per material by file hash plus `spec_version`.

Mobile (Kotlin) and desktop (Rust or TypeScript) are two implementations of the same job. **They must give the same chunks from the same handout**, or the tutor would answer differently on a phone and a laptop. This spec and its golden fixtures are what keeps them aligned:

* Everything after text extraction (cleaning and chunking) is specified here and tested with the fixtures. **Rule:** both clients must pass the same fixtures in their own tests (`rules/tutor-guardrails.md` section 3.4).
* Text *extraction* (reading the PDF or `.docx` file) uses a different library on each platform and cannot be byte-identical, so it is not specified here. The fixtures start from the extracted text (PDF pages or Word paragraphs), not from a binary file. Library choice and the cost on 3 to 4 GB phones are open (PRD section 11).

Scanned or image PDFs and OCR are **out of scope**. A document with no extractable text produces no chunks and is simply not used by the tutor. Teachers and learners see nothing (device log only).

## 2. Pipeline

`file` → **extract** (per platform, not specified) → `input` (PDF pages, or Word paragraphs with their style) → **clean** → `blocks` → **chunk** → `chunks`.

## 3. Cleaning (provisional, PENDING)

Turns `input` into an ordered list of `blocks`, each `{kind: "heading" | "paragraph", text, page}`. `page` is the 1-based PDF page, or `null` for Word.

**Word (`docx`):** each paragraph is a block. A paragraph whose style is `Title` or starts with `Heading` is a `heading`; every other paragraph is a `paragraph`. Empty paragraphs are dropped. Headers, footers and footnotes are not read.

**PDF (`pdf`), applied to each page's text in this order:**
1. Blank lines separate blocks.
2. Drop a block that is only a page number (digits) or "Page N of M".
3. A block that is one line, 2 to 60 characters, has no closing punctuation (`.` `?` `!` `:` `;`) and does not start with a lowercase letter is a `heading`. Every other block is a `paragraph`.
4. In a paragraph, a hyphen at the end of a line joins the word split across the line break (`numer-` + `ator.` gives `numerator.`); every other line break becomes one space.
5. A paragraph that starts with a lowercase letter continues the previous paragraph, unless that one ended a sentence (`.` `?` `!`).
6. Collapse runs of spaces to one space and trim.

**PENDING:** the heading rule's limits, Unicode normalisation (NFKC), ligature and artifact repair, repeated headers and footers, table handling, two-column layouts. Section 8 lists what an earlier experiment found. Add each rule that is settled as a numbered rule with a fixture.

## 4. Chunking (provisional, PENDING)

Parameters (a client must accept them as inputs so fixtures can use small values):

| Parameter | Provisional value | |
|---|---|---|
| `max_chunk_tokens` | 500 | **PENDING** |
| `max_chunks` | 1,000 per document; the rest of the document is left out | **PENDING** |
| overlap between chunks | none | **PENDING** |
| tokens of a text | `ceil(1.4 × number of whitespace-separated words)` | an estimate for packing only; the prompt budget uses the loaded model's own tokenizer (`rules/tutor-guardrails.md` section 3.1) |

Rules:
1. Walk the blocks in order. A `heading` block closes the open chunk and becomes the heading of the chunks after it. Text before the first heading takes the material's `title` as its heading.
2. A `paragraph` is added to the open chunk (joined with a blank line, `\n\n`) while the joined text stays within `max_chunk_tokens`; otherwise the open chunk is closed and the paragraph starts a new chunk under the same heading.
3. A paragraph longer than `max_chunk_tokens` is first cut at sentence ends (`.`, `?` or `!` followed by a space), packing whole sentences greedily into pieces that fit (joined with one space). A single sentence that is still too long is cut by words. Each piece is then added by rule 2.
4. `order_index` starts at 1 and has no gaps.
5. `page` of a chunk is the `page` of its first block (`null` for Word).
6. `token_estimate` is the chunk text's token count above.
7. Empty text produces no chunk.

## 5. The chunk record

Stored by each device in its local `tutor_chunks` table (planned, defined in [`planned/on-device-documents.md`](../planned/on-device-documents.md); it is not the Hub-made reader table `material_chunks` of `contracts/schema/client_offline.sql`):

| Field | Rule |
|---|---|
| `id` | **Deterministic**, so every device gives a chunk the same id: UUIDv5 with the `material_id` as the namespace and the name `"{spec_version}:{order_index}"` |
| `material_id` | The handout |
| `order_index` | From 1, no gaps |
| `heading`, `text`, `token_estimate` | As above |
| `page` | PDF page or `null` (planned column) |
| `spec_version` | The spec version that made the chunk (planned column). After an app update that changes it, the device re-chunks the document and replaces the old rows, so a chunk fix ships as an app update, not a Hub update |

## 6. Failure

If extraction fails, finds no text or hits a limit, the document gets no chunks. The device writes one line to its own log and nothing else: no teacher or learner screen, no Hub call. A document whose file is not yet on the device has no chunks either: if the Hub is unreachable the learner is told to download the material first, and the tutor cannot use it until they have the file.

## 7. Fixtures

[`fixtures/*.json`](./fixtures/) hold one case each: `input` → `expected` (`blocks`, `cleaned_text` = block texts joined by a blank line, and `chunks`). A client test loads every fixture, runs its own clean and chunk code with the fixture's `params`, and compares `blocks` and `chunks` exactly (`id`, `order_index`, `heading`, `page`, `text`, `token_estimate`). `tests/test_chunking_fixtures.py` checks that the fixtures themselves are well formed. When the clients' first task settles the PENDING values, replace them, add the cases it found, set `"normative": true` and bump `spec_version`.

## 8. Starting findings (provisional, unverified on the target platforms)

Measured on a laptop with poppler and Python, in an experiment that was later removed (closed PR #358). **Unverified on Android and Rust.** The experiment's files are gone; these numbers are only a starting point for the clients' first task.

* **PDF text layer:** use the library's **default reading order, not a layout mode.** Layout mode and pdfplumber fell to about 25% retrieval on two-column files; default order gave 90% on every messy variant tried.
* **Cleanup that helped:** NFKC normalisation, de-hyphenation (it keeps quoted sentences intact, 94% to 100%), dropping repeated headers and footers and page numbers, rejoining lines, keeping tables as units, and detecting headings.
* **Word (`.docx`):** read paragraphs and tables in order (88% to 90%).
* **Chunking:** the plain page rule ties with heading-aware chunks of about 250 words with **soft** heading boundaries. **Hard splits at every heading were worse** (79% to 82%).
* **Retrieval:** most misses were paraphrased questions, not parsing problems. The top BM25 score was a good "not covered" guard (93% to 99%).
* **Cost:** extraction was cheap on the laptop (254 pages in 0.56 s with poppler).
* **UNMEASURED:** extraction speed, memory and reading order on phones, in Rust and in Kotlin/PDFium.
