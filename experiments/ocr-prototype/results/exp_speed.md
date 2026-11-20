## Extraction speed and RAM (this machine), per extractor

| extractor | file | pages | total s | s/page | median ms/page* | peak RSS of the process (MB) |
|---|---|---|---|---|---|---|
| pdftotext | v0_orig.pdf | 62 | 0.08 | 0.001 | - | 23 |
| pdftotext | v4_messy.pdf | 30 | 0.09 | 0.003 | - | 24 |
| pdftotext-layout | v0_orig.pdf | 62 | 0.08 | 0.001 | - | 24 |
| pdftotext-layout | v4_messy.pdf | 30 | 0.09 | 0.003 | - | 24 |
| pdftotext-raw | v0_orig.pdf | 62 | 0.07 | 0.001 | - | 24 |
| pdftotext-raw | v4_messy.pdf | 30 | 0.08 | 0.003 | - | 24 |
| pypdf | v0_orig.pdf | 62 | 0.28 | 0.005 | - | 52 |
| pypdf | v4_messy.pdf | 30 | 0.31 | 0.010 | - | 54 |
| pdfminer | v0_orig.pdf | 62 | 1.37 | 0.022 | - | 61 |
| pdfminer | v4_messy.pdf | 30 | 1.23 | 0.041 | - | 63 |
| pdfplumber | v0_orig.pdf | 62 | 3.08 | 0.050 | - | 415 |
| pdfplumber | v4_messy.pdf | 30 | 2.99 | 0.100 | - | 427 |
| pdfplumber+tables | v0_orig.pdf | 62 | 3.24 | 0.052 | - | 427 |
| pdfplumber+tables | v4_messy.pdf | 30 | 3.19 | 0.106 | - | 427 |
| docx | v5_docx.docx | 48 (est.) | 0.20 | 0.004 | - | 427 |
| docx-text-only | v5_docx.docx | 48 (est.) | 0.19 | 0.004 | - | 427 |

*total = extraction + cleanup + chunking, best of 3 runs. Peak RSS is cumulative for this one process (it only grows), so read it as an upper bound per row.
