## Ablation, pdftotext, mean over 4 messy variants (v1, v2, v3, v4); heading chunks 250/1, BM25

| pipeline | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| all cleanup on | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |
| no cleanup at all | 72% | 89% | 89% | 89% | 83% | 94% | 97% | 93 | 100% | 99% |
| all on, minus ligature fix (NFKC) | 79% | 90% | 90% | 90% | 83% | 99% | 99% | 117 | 100% | 99% |
| all on, minus de-hyphenate | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |
| all on, minus drop repeated header/footer + page numbers | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 122 | 100% | 99% |
| all on, minus rejoin wrapped lines | 74% | 88% | 88% | 89% | 83% | 96% | 99% | 102 | 100% | 98% |
| all on, minus table rows kept as one unit | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |
| all on, minus heading detection | 74% | 86% | 86% | 86% | 88% | 91% | 98% | 140 | 100% | 99% |
| only ligature fix (NFKC) | 73% | 89% | 89% | 89% | 88% | 94% | 97% | 93 | 100% | 99% |
| only de-hyphenate | 72% | 89% | 89% | 89% | 83% | 94% | 97% | 93 | 100% | 99% |
| only drop repeated header/footer + page numbers | 78% | 89% | 89% | 89% | 75% | 96% | 98% | 91 | 100% | 98% |
| only rejoin wrapped lines | 73% | 88% | 88% | 88% | 83% | 90% | 97% | 138 | 100% | 99% |
| only table rows kept as one unit | 72% | 89% | 89% | 89% | 83% | 94% | 97% | 93 | 100% | 99% |
| only heading detection | 73% | 88% | 88% | 88% | 79% | 96% | 98% | 105 | 100% | 98% |

## Ablation, pdftotext-layout, mean over 4 messy variants (v1, v2, v3, v4); heading chunks 250/1, BM25

| pipeline | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| all cleanup on | 52% | 58% | 58% | 78% | 42% | 95% | 98% | 123 | 88% | 58% |
| no cleanup at all | 51% | 58% | 58% | 78% | 38% | 96% | 97% | 95 | 87% | 58% |
| all on, minus ligature fix (NFKC) | 51% | 58% | 58% | 78% | 38% | 95% | 98% | 123 | 88% | 58% |
| all on, minus de-hyphenate | 51% | 57% | 57% | 76% | 42% | 95% | 99% | 125 | 87% | 58% |
| all on, minus drop repeated header/footer + page numbers | 51% | 58% | 58% | 78% | 42% | 93% | 98% | 129 | 88% | 58% |
| all on, minus rejoin wrapped lines | 49% | 57% | 57% | 77% | 38% | 97% | 98% | 101 | 88% | 58% |
| all on, minus table rows kept as one unit | 54% | 58% | 58% | 79% | 42% | 99% | 99% | 122 | 88% | 58% |
| all on, minus heading detection | 53% | 58% | 58% | 78% | 42% | 92% | 97% | 135 | 88% | 58% |
| only ligature fix (NFKC) | 51% | 58% | 58% | 78% | 38% | 96% | 97% | 95 | 87% | 58% |
| only de-hyphenate | 49% | 57% | 57% | 76% | 38% | 96% | 98% | 95 | 88% | 58% |
| only drop repeated header/footer + page numbers | 51% | 58% | 58% | 79% | 38% | 93% | 98% | 93 | 87% | 58% |
| only rejoin wrapped lines | 52% | 58% | 58% | 79% | 46% | 95% | 97% | 147 | 87% | 58% |
| only table rows kept as one unit | 51% | 57% | 57% | 76% | 42% | 95% | 97% | 96 | 87% | 58% |
| only heading detection | 45% | 54% | 54% | 74% | 38% | 95% | 98% | 99 | 87% | 58% |

## Ablation, pdftotext-raw, mean over 4 messy variants (v1, v2, v3, v4); heading chunks 250/1, BM25

| pipeline | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| all cleanup on | 86% | 90% | 89% | 89% | 96% | 97% | 98% | 126 | 100% | 100% |
| no cleanup at all | 76% | 91% | 91% | 89% | 79% | 94% | 98% | 93 | 99% | 93% |
| all on, minus ligature fix (NFKC) | 86% | 90% | 89% | 89% | 96% | 97% | 98% | 126 | 100% | 100% |
| all on, minus de-hyphenate | 86% | 90% | 89% | 88% | 96% | 97% | 98% | 128 | 99% | 94% |
| all on, minus drop repeated header/footer + page numbers | 85% | 90% | 88% | 88% | 100% | 92% | 96% | 135 | 100% | 100% |
| all on, minus rejoin wrapped lines | 80% | 89% | 89% | 89% | 88% | 92% | 98% | 93 | 100% | 99% |
| all on, minus table rows kept as one unit | 86% | 90% | 89% | 89% | 96% | 97% | 98% | 126 | 100% | 100% |
| all on, minus heading detection | 88% | 90% | 89% | 89% | 96% | 95% | 98% | 132 | 100% | 100% |
| only ligature fix (NFKC) | 77% | 91% | 91% | 89% | 88% | 94% | 98% | 93 | 99% | 93% |
| only de-hyphenate | 74% | 90% | 90% | 91% | 79% | 96% | 97% | 94 | 100% | 98% |
| only drop repeated header/footer + page numbers | 76% | 89% | 89% | 88% | 71% | 90% | 97% | 92 | 99% | 93% |
| only rejoin wrapped lines | 84% | 91% | 88% | 87% | 96% | 92% | 96% | 137 | 99% | 94% |
| only table rows kept as one unit | 76% | 91% | 91% | 89% | 79% | 94% | 98% | 93 | 99% | 93% |
| only heading detection | 76% | 91% | 91% | 89% | 79% | 94% | 98% | 93 | 99% | 93% |

## Ablation, pypdf, mean over 4 messy variants (v1, v2, v3, v4); heading chunks 250/1, BM25

| pipeline | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| all cleanup on | 76% | 92% | 89% | 89% | 83% | 96% | 99% | 113 | 100% | 100% |
| no cleanup at all | 76% | 89% | 89% | 88% | 83% | 92% | 97% | 93 | 99% | 93% |
| all on, minus ligature fix (NFKC) | 76% | 92% | 89% | 89% | 83% | 96% | 99% | 113 | 100% | 100% |
| all on, minus de-hyphenate | 76% | 92% | 89% | 88% | 83% | 96% | 98% | 114 | 99% | 94% |
| all on, minus drop repeated header/footer + page numbers | 72% | 88% | 86% | 86% | 79% | 93% | 97% | 117 | 100% | 100% |
| all on, minus rejoin wrapped lines | 81% | 89% | 89% | 89% | 92% | 92% | 98% | 92 | 100% | 99% |
| all on, minus table rows kept as one unit | 76% | 92% | 89% | 89% | 83% | 96% | 99% | 113 | 100% | 100% |
| all on, minus heading detection | 75% | 91% | 88% | 88% | 75% | 95% | 98% | 114 | 100% | 100% |
| only ligature fix (NFKC) | 76% | 89% | 89% | 88% | 88% | 92% | 97% | 93 | 99% | 93% |
| only de-hyphenate | 72% | 89% | 89% | 90% | 79% | 94% | 97% | 93 | 100% | 98% |
| only drop repeated header/footer + page numbers | 78% | 89% | 89% | 88% | 71% | 91% | 97% | 92 | 99% | 93% |
| only rejoin wrapped lines | 72% | 88% | 86% | 84% | 79% | 93% | 96% | 117 | 99% | 94% |
| only table rows kept as one unit | 76% | 89% | 89% | 88% | 83% | 92% | 97% | 93 | 99% | 93% |
| only heading detection | 76% | 89% | 89% | 88% | 83% | 92% | 97% | 93 | 99% | 93% |

## Ablation, docx-text-only, mean over 2 messy variants (v5, v6); heading chunks 250/1, BM25

| pipeline | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| all cleanup on | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| no cleanup at all | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus ligature fix (NFKC) | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus de-hyphenate | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus drop repeated header/footer + page numbers | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus rejoin wrapped lines | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus table rows kept as one unit | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| all on, minus heading detection | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only ligature fix (NFKC) | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only de-hyphenate | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only drop repeated header/footer + page numbers | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only rejoin wrapped lines | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only table rows kept as one unit | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| only heading detection | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |

