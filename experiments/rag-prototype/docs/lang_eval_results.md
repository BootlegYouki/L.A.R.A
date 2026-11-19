# App-side Filipino/Taglish check

| set | n | result |
|---|---|---|
| English key prompts (Q, O, A) | 52 | false positives 0 |
| English with Filipino names/places | 12 | false positives 0 |
| Taglish / Filipino messages | 14 | false negatives 2 (caught 12) |

False positives: none
False negatives (slipped through to the model): ['tulungan mo ako sa assignment ko', 'salamat teacher, gets ko na']

Marker words: ['ang', 'ano', 'bakit', 'hindi', 'ilan', 'kailan', 'kasi', 'lang', 'mga', 'naman', 'namin', 'natin', 'ng', 'nito', 'paano', 'po', 'pwede', 'saan', 'sagot', 'sino', 'yung']
