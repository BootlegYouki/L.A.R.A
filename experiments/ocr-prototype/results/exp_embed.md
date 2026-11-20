## BM25 vs bge-small-en-v1.5 (ONNX, CPU) vs hybrid (RRF), pdftotext, heading 250/1, messy variants

| retriever | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| bm25 | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |
| vector | 73% | 91% | 91% | 91% | 67% | 0% | 67% | 117 | 100% | 99% |
| hybrid | 82% | 90% | 90% | 90% | 92% | 0% | 67% | 117 | 100% | 99% |

(decline @5.0 is BM25-only and shown as 0 for vector/hybrid; use 'decline best-thr', which sweeps the cosine/BM25 threshold)

embedding speed: 468 chunks in 50.5 s = 9 chunks/s; peak RSS so far 3125 MB

