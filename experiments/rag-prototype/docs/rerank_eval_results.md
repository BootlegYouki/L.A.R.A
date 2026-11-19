# Sentence rerank: off vs on vs compress (retrieval only, V1 prompt budget, no generation)

Questions: 40 key + 27 filler. 'right above wrong' = the distractor pair's right page ranks above its twin (6 questions: Q12/Q24, Q21/Q34, Q30/Q39). top-1/grounded = the chunk the opener would name is on the key page. 'answer in sent' = the key answer text is inside the chunks actually sent (compress cuts text, so this is its risk). Tokens = lesson tokens sent.

| mode | retriever | top-1 page right (key 40) | top-1 (filler 27) | grounded right (key+filler) | key page sent | right above wrong | answer text in sent (key 40) | avg lesson tokens sent |
|---|---|---|---|---|---|---|---|---|
| off | hybrid | 88% (35/40) | 89% (24/27) | 88% (59/67) | 96% (64/67) | 67% (4/6) | 95% (38/40) | 813 |
| off | vector | 80% (32/40) | 70% (19/27) | 76% (51/67) | 94% (63/67) | 67% (4/6) | 98% (39/40) | 819 |
| off | bm25 | 82% (33/40) | 85% (23/27) | 84% (56/67) | 90% (60/67) | 67% (4/6) | 88% (35/40) | 813 |
| on | hybrid | 90% (36/40) | 81% (22/27) | 87% (58/67) | 96% (64/67) | 83% (5/6) | 98% (39/40) | 812 |
| on | vector | 90% (36/40) | 78% (21/27) | 85% (57/67) | 96% (64/67) | 83% (5/6) | 95% (38/40) | 814 |
| on | bm25 | 90% (36/40) | 81% (22/27) | 87% (58/67) | 93% (62/67) | 83% (5/6) | 92% (37/40) | 818 |
| compress | hybrid | 90% (36/40) | 81% (22/27) | 87% (58/67) | 96% (64/67) | 83% (5/6) | 85% (34/40) | 417 |
| compress | vector | 90% (36/40) | 78% (21/27) | 85% (57/67) | 96% (64/67) | 83% (5/6) | 82% (33/40) | 415 |
| compress | bm25 | 90% (36/40) | 81% (22/27) | 87% (58/67) | 93% (62/67) | 83% (5/6) | 82% (33/40) | 415 |

## Where hybrid top-1 is wrong

- off: Q05, Q07, Q12, Q13, Q39, F21, F22, F27
- on: Q05, Q07, Q13, Q39, F17, F21, F22, F23, F27
- compress: Q05, Q07, Q13, Q39, F17, F21, F22, F23, F27
