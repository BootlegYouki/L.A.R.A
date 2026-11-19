# RAG retrieval eval (nomic embedder)

134 chunks over 62 pages; lesson-token budget for a first message = 1472 (2048 - 276 prompt - 0 history - 300 reply). Hit = a chunk on the key's page. `ans` = the expected answer text is inside the chunks that fit the budget.

## hit@1

| retriever | all | direct (10) | paraphrase (12) | table (8) | distractor (6) | two-part (4) |
|---|---|---|---|---|---|---|
| keyword | 88% | 100% | 75% | 88% | 83% | 100% |
| bm25 | 82% | 100% | 58% | 100% | 67% | 100% |
| vector | 80% | 90% | 67% | 88% | 67% | 100% |
| hybrid | 88% | 100% | 75% | 100% | 67% | 100% |

## hit@3

| retriever | all | direct (10) | paraphrase (12) | table (8) | distractor (6) | two-part (4) |
|---|---|---|---|---|---|---|
| keyword | 92% | 100% | 75% | 100% | 100% | 100% |
| bm25 | 90% | 100% | 67% | 100% | 100% | 100% |
| vector | 100% | 100% | 100% | 100% | 100% | 100% |
| hybrid | 98% | 100% | 92% | 100% | 100% | 100% |

## hit-in-budget

| retriever | all | direct (10) | paraphrase (12) | table (8) | distractor (6) | two-part (4) |
|---|---|---|---|---|---|---|
| keyword | 95% | 100% | 83% | 100% | 100% | 100% |
| bm25 | 92% | 100% | 75% | 100% | 100% | 100% |
| vector | 100% | 100% | 100% | 100% | 100% | 100% |
| hybrid | 100% | 100% | 100% | 100% | 100% | 100% |

## answer text in budget

| retriever | all | direct (10) | paraphrase (12) | table (8) | distractor (6) | two-part (4) |
|---|---|---|---|---|---|---|
| keyword | 92% | 100% | 75% | 100% | 100% | 100% |
| bm25 | 90% | 100% | 67% | 100% | 100% | 100% |
| vector | 98% | 100% | 92% | 100% | 100% | 100% |
| hybrid | 100% | 100% | 100% | 100% | 100% | 100% |

## Distractor pairs: does the right member outrank the wrong one?

| retriever | right above wrong | detail (question: right page rank vs wrong page rank) |
|---|---|---|
| keyword | 5/6 | Q12: 2 vs 1; Q21: 1 vs 4; Q24: 1 vs 3; Q30: 1 vs 7; Q34: 1 vs 2; Q39: 1 vs 2 |
| bm25 | 4/6 | Q12: 2 vs 1; Q21: 1 vs 4; Q24: 1 vs 3; Q30: 1 vs 2; Q34: 1 vs 6; Q39: 2 vs 1 |
| vector | 4/6 | Q12: 1 vs 2; Q21: 1 vs 3; Q24: 2 vs 1; Q30: 1 vs 2; Q34: 1 vs 2; Q39: 2 vs 1 |
| hybrid | 4/6 | Q12: 2 vs 1; Q21: 1 vs 4; Q24: 1 vs 2; Q30: 1 vs 2; Q34: 1 vs 3; Q39: 2 vs 1 |

## Misses in budget (page not sent)

- keyword: Q02(paraphrase), Q05(paraphrase)
- bm25: Q02(paraphrase), Q05(paraphrase), Q07(paraphrase)
- vector: none
- hybrid: none

## Not-covered guard (top-1 vector cosine)

- in-scope Q+A (44): min 0.574 · median 0.726 · max 0.850
- out-of-scope O (8): min 0.493 · median 0.695 · max 0.759
- out-of-scope scores: O1=0.759, O2=0.753, O3=0.693, O4=0.697, O5=0.756, O6=0.493, O7=0.504, O8=0.532
- lowest in-scope: A1=0.574, A3=0.581, A2=0.596, Q19=0.603, Q25=0.639
- cosine alone is a weak guard; the guard in use (bm25 >= 5.0 OR cosine >= 0.65) is compared in guard_eval_results.md

