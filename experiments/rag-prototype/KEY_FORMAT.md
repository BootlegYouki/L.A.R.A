# Answer key format (`sample/answer_key.json`)

A JSON list. One object per question. `rag_eval.py --key your_key.json` and `verify_key.py` read it.

| field | needed for | meaning |
|---|---|---|
| `id` | all | `Q01`.. for questions the book answers, `O1`.. for questions it does not answer (out of scope), `A1`.. for answer-seeking messages ("just tell me the answer to ..."). The first letter matters: the scripts split on it. |
| `type` | `Q` | `direct`, `paraphrase`, `table` (tables and lists), `distractor`, `two-part` (answer needs two adjacent sentences) |
| `question` | all | what the learner types |
| `expected_answer` | `Q` | the answer in words, as the book states it |
| `token` | optional, `Q` | the exact text/number that must appear for the answer check (string, or a list when several must appear). Without it, `expected_answer` is used. |
| `page` | `Q`, `A` | PDF page where the quote is (a number; a list `[26, 27]` for `two-part`). Absent or null for `O` entries. Count pages as `pdftotext -f N -l N` does. |
| `quote` | `Q`, `A` | the exact sentence from the book (whitespace and curly quotes do not matter). For two-part facts, a list of the two sentences (or one string with ` / ` between them). |
| `chapter` | optional | chapter number, for your own sorting |
| `pair` | optional, `distractor` | the `id` of the twin question whose answer sits on a different page; the eval checks that the right page ranks above the twin's. |

Example:
```json
{"id": "Q12", "type": "distractor", "question": "what was the july 2025 monthly rainfall of the sampaguita class?",
 "expected_answer": "214 millimeters", "token": "214 millimeters", "page": 20,
 "quote": "In July 2025 the Sampaguita class rain gauge recorded a monthly total of 214 millimeters.", "chapter": 4, "pair": "Q24"}
```
Out-of-scope entry: `{"id": "O1", "type": "out_of_scope", "question": "how does a tornado form?", "expected_answer": "The lesson does not cover this."}`

Run `python verify_key.py your_key.json your.pdf` before using a key: it must print no FAIL.
