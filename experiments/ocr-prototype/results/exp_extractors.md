## Extractors, full cleanup, heading-aware chunks (250 words, 1 paragraph overlap, min-fill 0.5), BM25

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdftotext / v0_orig.pdf | 80% | 90% | 90% | 90% | 83% | 93% | 99% | 113 | 100% | 100% |
| pdftotext / v1_twocol.pdf | 75% | 90% | 90% | 90% | 83% | 99% | 99% | 114 | 100% | 100% |
| pdftotext / v2_hyphen.pdf | 80% | 90% | 90% | 90% | 83% | 99% | 99% | 115 | 100% | 100% |
| pdftotext / v3_ligature.pdf | 82% | 90% | 90% | 90% | 83% | 99% | 99% | 119 | 100% | 100% |
| pdftotext / v4_messy.pdf | 80% | 90% | 90% | 90% | 100% | 99% | 99% | 120 | 100% | 98% |
| **pdftotext / mean of messy variants** | 79% | 90% | 90% | 90% | 88% | 99% | 99% | 117 | 100% | 99% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdftotext-layout / v0_orig.pdf | 82% | 90% | 90% | 90% | 83% | 99% | 99% | 113 | 100% | 100% |
| pdftotext-layout / v1_twocol.pdf | 20% | 25% | 25% | 65% | 0% | 86% | 98% | 129 | 75% | 18% |
| pdftotext-layout / v2_hyphen.pdf | 82% | 90% | 90% | 90% | 83% | 99% | 99% | 117 | 100% | 100% |
| pdftotext-layout / v3_ligature.pdf | 80% | 90% | 90% | 90% | 83% | 99% | 99% | 120 | 100% | 100% |
| pdftotext-layout / v4_messy.pdf | 25% | 25% | 25% | 65% | 0% | 98% | 98% | 126 | 75% | 15% |
| **pdftotext-layout / mean of messy variants** | 52% | 58% | 58% | 78% | 42% | 95% | 98% | 123 | 88% | 58% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdftotext-raw / v0_orig.pdf | 80% | 88% | 85% | 85% | 83% | 93% | 98% | 124 | 100% | 100% |
| pdftotext-raw / v1_twocol.pdf | 82% | 88% | 88% | 88% | 100% | 99% | 99% | 126 | 100% | 100% |
| pdftotext-raw / v2_hyphen.pdf | 90% | 90% | 90% | 90% | 100% | 93% | 97% | 127 | 100% | 100% |
| pdftotext-raw / v3_ligature.pdf | 88% | 90% | 90% | 90% | 100% | 99% | 99% | 127 | 100% | 100% |
| pdftotext-raw / v4_messy.pdf | 85% | 92% | 90% | 90% | 83% | 99% | 99% | 126 | 100% | 100% |
| **pdftotext-raw / mean of messy variants** | 86% | 90% | 89% | 89% | 96% | 97% | 98% | 126 | 100% | 100% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pypdf / v0_orig.pdf | 72% | 88% | 85% | 85% | 67% | 93% | 98% | 116 | 100% | 100% |
| pypdf / v1_twocol.pdf | 72% | 90% | 85% | 85% | 100% | 95% | 99% | 111 | 100% | 100% |
| pypdf / v2_hyphen.pdf | 75% | 92% | 90% | 90% | 67% | 91% | 98% | 113 | 100% | 100% |
| pypdf / v3_ligature.pdf | 75% | 92% | 90% | 90% | 83% | 99% | 99% | 114 | 100% | 100% |
| pypdf / v4_messy.pdf | 80% | 92% | 92% | 92% | 83% | 98% | 99% | 113 | 100% | 100% |
| **pypdf / mean of messy variants** | 76% | 92% | 89% | 89% | 83% | 96% | 99% | 113 | 100% | 100% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdfminer / v0_orig.pdf | 60% | 75% | 75% | 75% | 67% | 93% | 99% | 104 | 90% | 90% |
| pdfminer / v1_twocol.pdf | 72% | 90% | 90% | 85% | 67% | 91% | 98% | 115 | 90% | 82% |
| pdfminer / v2_hyphen.pdf | 68% | 78% | 78% | 78% | 67% | 99% | 99% | 115 | 90% | 90% |
| pdfminer / v3_ligature.pdf | 68% | 78% | 78% | 78% | 67% | 99% | 99% | 115 | 90% | 90% |
| pdfminer / v4_messy.pdf | 78% | 92% | 92% | 90% | 83% | 86% | 98% | 115 | 98% | 90% |
| **pdfminer / mean of messy variants** | 71% | 84% | 84% | 82% | 71% | 94% | 98% | 115 | 92% | 88% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdfplumber / v0_orig.pdf | 75% | 88% | 85% | 85% | 67% | 91% | 98% | 116 | 100% | 100% |
| pdfplumber / v1_twocol.pdf | 25% | 30% | 30% | 70% | 17% | 97% | 99% | 108 | 75% | 20% |
| pdfplumber / v2_hyphen.pdf | 75% | 92% | 88% | 88% | 67% | 91% | 98% | 113 | 100% | 100% |
| pdfplumber / v3_ligature.pdf | 75% | 92% | 90% | 90% | 83% | 99% | 99% | 114 | 100% | 100% |
| pdfplumber / v4_messy.pdf | 28% | 28% | 28% | 70% | 0% | 94% | 99% | 108 | 78% | 15% |
| **pdfplumber / mean of messy variants** | 51% | 61% | 59% | 79% | 42% | 95% | 99% | 111 | 88% | 59% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| pdfplumber+tables / v0_orig.pdf | 68% | 88% | 88% | 88% | 67% | 86% | 99% | 125 | 100% | 100% |
| pdfplumber+tables / v1_twocol.pdf | 25% | 38% | 35% | 70% | 17% | 98% | 99% | 115 | 80% | 30% |
| pdfplumber+tables / v2_hyphen.pdf | 75% | 92% | 88% | 88% | 67% | 91% | 98% | 113 | 100% | 100% |
| pdfplumber+tables / v3_ligature.pdf | 75% | 92% | 90% | 90% | 83% | 99% | 99% | 114 | 100% | 100% |
| pdfplumber+tables / v4_messy.pdf | 25% | 30% | 30% | 72% | 0% | 98% | 99% | 118 | 82% | 22% |
| **pdfplumber+tables / mean of messy variants** | 50% | 63% | 61% | 80% | 42% | 96% | 99% | 115 | 91% | 63% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| docx / v5_docx.docx | 75% | 88% | 88% | 88% | 67% | 99% | 99% | 117 | 100% | 100% |
| docx / v6_docx_plain.docx | 75% | 88% | 88% | 88% | 67% | 99% | 99% | 117 | 100% | 100% |
| **docx / mean of messy variants** | 75% | 88% | 88% | 88% | 67% | 99% | 99% | 117 | 100% | 100% |

| extractor / variant | hit@1 | hit@3 | hit-in-budget | answer text in budget | near-twin (6) | decline @5.0 | decline best-thr | chunks | answer tokens intact anywhere | quote intact anywhere |
|---|---|---|---|---|---|---|---|---|---|---|
| docx-text-only / v5_docx.docx | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| docx-text-only / v6_docx_plain.docx | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |
| **docx-text-only / mean of messy variants** | 80% | 90% | 90% | 90% | 83% | 93% | 98% | 109 | 100% | 100% |

