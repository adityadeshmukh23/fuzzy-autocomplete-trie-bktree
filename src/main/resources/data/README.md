# Dataset provenance

**File:** `word-frequencies.txt` — 100,000 English words with corpus frequencies, tab-separated.

**Source:** Peter Norvig, *Natural Language Corpus Data* — `https://norvig.com/ngrams/count_1w.txt`
Derived from the Google Web Trillion Word Corpus (Brants & Franz, 2006), published by Norvig
alongside the book chapter *Natural Language Corpus Data* (in *Beautiful Data*, O'Reilly 2009)
and made freely available for research and education.

**What was done to it:** the source file holds 333,333 entries ordered by descending frequency.
This is the first 100,000 lines, unmodified otherwise. Regenerate with:

```bash
curl -sL https://norvig.com/ngrams/count_1w.txt | head -100000 > word-frequencies.txt
```

**Why the top 100,000 and not all 333,333.** Not only for size. Frequency ties matter to this
project: the trie's best-first search must expand every node whose ranking bound *ties* the
current best, so a corpus where many words share a weight degrades it toward scanning whole
subtrees (see `docs/complexity.md`).

| slice | distinct counts | entries in a tied group | largest tie group |
|---|---:|---:|---:|
| top 100,000 | 93.0% | 13.2% | 5 words |
| all 333,333 | 47.5% | 67.6% | 152 words |

The source file's tail bottoms out at a floor count of 12,711 shared by 152 words, and ties get
dense well before that. Cutting at 100,000 keeps the well-differentiated head — which is also the
half that is real vocabulary rather than web typos (`gooblle`, `gollgo`, `golgw` are genuine
entries near the end of the full file).

**Counts range** from 23,135,851,162 (`the`) down to 99,133 at rank 100,000.
