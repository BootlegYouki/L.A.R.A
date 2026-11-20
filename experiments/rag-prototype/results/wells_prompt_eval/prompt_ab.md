# Prompt variants side by side (auto-flags, hybrid, threshold 0.65)

| check | V1 | V2 | V4 | V5 | V6 | V7 |
|---|---|---|---|---|---|---|
| Q: LEAK (lower is better) | 10/27 | 3/27 | 3/27 | 4/27 | 2/27 | 6/27 |
| Q: POINTS names right page/heading | 0/27 | 13/27 | 14/27 | 22/27 | 22/27 | 22/27 |
| Q: CLUE (not just a decline) | 27/27 | 27/27 | 27/27 | 27/27 | 27/27 | 27/27 |
| O: DECLINED_OK | 3/3 | 3/3 | 3/3 | 0/3 | 0/3 | 2/3 |
| O: LEAK-free reply gave a clue anyway (CLUE) | 3/3 | 3/3 | 3/3 | 3/3 | 3/3 | 3/3 |
| A: DECLINED_OK (no leak + a question) | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| A: LEAK (only A4 checkable) | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| A: REPEATED_TEMPLATE | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| A: CLUE | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| A: POINTS | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| L (list questions): LEAK_PASSAGE (lower is better) | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| L: POINTS | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| L: CLUE | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| T (Taglish): exact English-only line | 1/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| P (page lookup): chunk of the page sent | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| P (page lookup): POINTS | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| ALL 52+ non-Taglish prompts: English-only MISFIRE | 0/30 | 0/30 | 0/30 | 0/30 | 0/30 | 0/30 |
| ALL non-Taglish prompts: LEAK | 10/30 | 3/30 | 3/30 | 4/30 | 2/30 | 6/30 |
| Hub guard: sentences removed (replies touched) | 0/32 | 13/32 | 11/32 | 1/32 | 2/32 | 3/32 |
| Q+A: LEAK in the raw model output (before the Hub guard) | 10/27 | 9/27 | 3/27 | 4/27 | 3/27 | 7/27 |
| L: LEAK_PASSAGE in the raw model output | 0/8 | 0/8 | 0/8 | 0/8 | 0/8 | 0/8 |
| ALL non-Taglish prompts: REFUSAL_PHRASE (can't/cannot/won't give) | 0/30 | 1/30 | 0/30 | 0/30 | 0/30 | 0/30 |
| ALL prompts: BAD_CITE in the shown reply (page not sent) | 0/32 | 0/32 | 0/32 | 0/32 | 0/32 | 0/32 |
| ALL prompts: BAD_CITE in the raw model output | 0/32 | 0/32 | 1/32 | 1/32 | 1/32 | 0/32 |
| ALL prompts: EMPTY reply | 0/32 | 0/32 | 0/32 | 0/32 | 0/32 | 0/32 |
| Q: clean = CLUE and POINTS and no LEAK | 0/27 | 12/27 | 12/27 | 18/27 | 20/27 | 16/27 |
| median first token (s) | 12.105 | 11.695 | 9.48 | 12.934999999999999 | 13.015 | 13.025 |
