# Concurrency test, 2026-11-19

20 questions in a fixed rotation (mixed types), 6 requests per learner, all learners start together. Time to first token is measured from sending the request, so it includes waiting for a free slot. Tokens/s is the server-measured generation speed of each stream (the 'done' event); total tok/s is generated tokens over wall time. A reply the app writes itself (Filipino check) has no model tokens and is left out of the speed columns.

| learners | requests | errors | first token p50 (s) | first token p95 (s) | per-stream tok/s p50 | per-stream tok/s min | total tok/s | wall (s) |
|---|---|---|---|---|---|---|---|---|
| 1 | 6 | 0 | 14.7 | 17.8 | 17.9 | 17.9 | 3.0 | 82 |
| 2 | 12 | 0 | 5.7 | 19.3 | 10.2 | 0.9 | 5.2 | 74 |
| 3 | 18 | 0 | 5.8 | 15.7 | 7.7 | 1.8 | 8.5 | 78 |
| 5 | 30 | 0 | 6.1 | 22.0 | 2.2 | 1.2 | 7.5 | 142 |
| 8 | 48 | 0 | 21.2 | 36.6 | 4.1 | 0.7 | 8.8 | 197 |

Target at 5 learners: p95 first token <= 10 s and every stream >= 8 tok/s -> **NOT MET**
