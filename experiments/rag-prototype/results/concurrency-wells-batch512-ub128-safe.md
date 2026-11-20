# Concurrency test, 2026-11-20

20 questions in a fixed rotation (mixed types), 6 requests per learner, all learners start together. Time to first token is measured from sending the request, so it includes waiting for a free slot. Tokens/s is the server-measured generation speed of each stream (the 'done' event); total tok/s is generated tokens over wall time. A reply the app writes itself (Filipino check) has no model tokens and is left out of the speed columns.

| learners | requests | errors | first token p50 (s) | first token p95 (s) | per-stream tok/s p50 | per-stream tok/s min | total tok/s | wall (s) |
|---|---|---|---|---|---|---|---|---|
| 5 | 30 | 0 | 7.1 | 38.7 | 2.5 | 0.9 | 6.9 | 153 |

Target at 5 learners: p95 first token <= 10 s and every stream >= 8 tok/s -> **NOT MET**
