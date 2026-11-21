# RAG retrieval eval (nomic embedder)

476 chunks over 254 pages; lesson-token budget for a first message = 1472 (2048 - 276 prompt - 0 history - 300 reply). Hit = a chunk on the key's page. `ans` = the expected answer text is inside the chunks that fit the budget.

## hit@1

| retriever | all | direct (10) | paraphrase (6) | table (4) | distractor (4) | two-part (3) |
|---|---|---|---|---|---|---|
| keyword | 48% | 40% | 33% | 50% | 50% | 100% |
| bm25 | 59% | 50% | 50% | 50% | 100% | 67% |
| vector | 81% | 100% | 50% | 75% | 100% | 67% |
| hybrid | 85% | 80% | 67% | 100% | 100% | 100% |

## hit@3

| retriever | all | direct (10) | paraphrase (6) | table (4) | distractor (4) | two-part (3) |
|---|---|---|---|---|---|---|
| keyword | 59% | 60% | 50% | 50% | 50% | 100% |
| bm25 | 85% | 80% | 67% | 100% | 100% | 100% |
| vector | 100% | 100% | 100% | 100% | 100% | 100% |
| hybrid | 89% | 90% | 67% | 100% | 100% | 100% |

## hit-in-budget

| retriever | all | direct (10) | paraphrase (6) | table (4) | distractor (4) | two-part (3) |
|---|---|---|---|---|---|---|
| keyword | 56% | 60% | 33% | 50% | 50% | 100% |
| bm25 | 78% | 80% | 67% | 50% | 100% | 100% |
| vector | 96% | 100% | 83% | 100% | 100% | 100% |
| hybrid | 85% | 80% | 67% | 100% | 100% | 100% |

## answer text in budget

| retriever | all | direct (10) | paraphrase (6) | table (4) | distractor (4) | two-part (3) |
|---|---|---|---|---|---|---|
| keyword | 56% | 50% | 17% | 75% | 75% | 100% |
| bm25 | 70% | 70% | 33% | 75% | 100% | 100% |
| vector | 81% | 90% | 33% | 100% | 100% | 100% |
| hybrid | 78% | 80% | 33% | 100% | 100% | 100% |

## Distractor pairs: does the right member outrank the wrong one?

| retriever | right above wrong | detail (question: right page rank vs wrong page rank) |
|---|---|---|
| keyword | 3/4 | Q21: 1 vs 56; Q22: 4 vs 1; Q23: 1 vs 153; Q24: 11 vs 144 |
| bm25 | 4/4 | Q21: 1 vs 32; Q22: 1 vs 2; Q23: 1 vs 36; Q24: 1 vs 38 |
| vector | 4/4 | Q21: 1 vs 6; Q22: 1 vs 3; Q23: 1 vs 8; Q24: 1 vs 99 |
| hybrid | 4/4 | Q21: 1 vs 9; Q22: 1 vs 2; Q23: 1 vs 14; Q24: 1 vs 56 |

## Misses in budget (page not sent)

- keyword: Q01(direct), Q04(direct), Q06(direct), Q10(direct), Q12(paraphrase), Q14(paraphrase), Q15(paraphrase), Q16(paraphrase), Q19(table), Q20(table), Q22(distractor), Q24(distractor)
- bm25: Q01(direct), Q04(direct), Q12(paraphrase), Q15(paraphrase), Q19(table), Q20(table)
- vector: Q12(paraphrase)
- hybrid: Q01(direct), Q04(direct), Q12(paraphrase), Q15(paraphrase)

## Not-covered guard (top-1 vector cosine)

- in-scope Q+A (27): min 0.702 · median 0.790 · max 0.858
- out-of-scope O (3): min 0.553 · median 0.589 · max 0.602
- out-of-scope scores: O1=0.589, O2=0.553, O3=0.602
- lowest in-scope: Q13=0.702, Q10=0.721, Q12=0.729, Q16=0.733, Q15=0.735
- cosine alone is a weak guard; the guard in use (bm25 >= 5.0 OR cosine >= 0.65) is compared in guard_eval_results.md


## Sentence rerank (hybrid retriever; 'compress' sends fewer tokens but can cut the answer)

| mode | top-1 page right | key page in sent chunks | right above wrong | answer text in sent | avg lesson tokens sent |
|---|---|---|---|---|---|
| off | 85% | 85% | 100% | 78% | 690 |
| on | 81% | 85% | 100% | 78% | 688 |
| compress | 81% | 85% | 100% | 56% | 339 |
