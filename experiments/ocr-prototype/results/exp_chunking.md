## Chunking, pdftotext, full cleanup, mean over messy variants v1, v2, v3, v4; BM25

| chunker | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| page 180w overlap 1 | 82% | 90% | 90% | 90% | 88% | 91% | 98% | 136 | 100% | 99% |
| window 120w overlap 0 | 76% | 88% | 88% | 88% | 100% | 81% | 99% | 234 | 100% | 99% |
| window 180w overlap 0 | 79% | 88% | 88% | 88% | 88% | 89% | 99% | 140 | 100% | 99% |
| window 250w overlap 0 | 76% | 88% | 88% | 88% | 79% | 91% | 99% | 96 | 100% | 99% |
| heading 120w overlap 0 min-fill 0 | 72% | 79% | 79% | 75% | 83% | 73% | 99% | 238 | 89% | 86% |
| heading 120w overlap 1 min-fill 0 | 76% | 81% | 81% | 76% | 88% | 86% | 99% | 258 | 89% | 86% |
| heading 180w overlap 0 min-fill 0 | 71% | 82% | 82% | 78% | 88% | 86% | 99% | 180 | 89% | 86% |
| heading 180w overlap 1 min-fill 0 | 72% | 81% | 81% | 77% | 88% | 90% | 99% | 191 | 89% | 86% |
| heading 120w overlap 1 min-fill 0.5 | 79% | 86% | 86% | 85% | 88% | 89% | 99% | 240 | 99% | 97% |
| heading 180w overlap 0 min-fill 0.5 | 75% | 86% | 86% | 87% | 88% | 93% | 98% | 147 | 99% | 98% |
| heading 180w overlap 1 min-fill 0.5 | 78% | 86% | 86% | 86% | 88% | 98% | 99% | 162 | 99% | 98% |
| heading 250w overlap 1 min-fill 0.5 | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |
| heading 350w overlap 1 min-fill 0.5 | 79% | 88% | 88% | 88% | 100% | 95% | 99% | 86 | 99% | 98% |
| heading 180w overlap 1 min-fill 1.0 | 76% | 86% | 86% | 86% | 88% | 97% | 99% | 160 | 100% | 99% |
| heading 180w overlap 1 min-fill 0.5 (tables may split) | 78% | 86% | 86% | 86% | 88% | 98% | 99% | 162 | 99% | 98% |

