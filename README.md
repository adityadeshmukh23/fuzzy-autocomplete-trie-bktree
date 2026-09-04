# Fuzzy Autocomplete Search Engine

**Search-as-you-type with typo tolerance over 100,000 words — a trie, a BK-tree, Levenshtein
distance and a bounded min-heap, all written from scratch.**

Typing `aple` finds **apple**. A six-character prefix query resolves in **2.56 µs**, while
scanning the same 100,000 words linearly takes **1.14 ms** — **445× slower**. No Lucene, no
Elasticsearch, no fuzzy-matching library: the point of this project is the machinery underneath
those tools.

```
203 tests · Java 21 · Spring Boot 3.3 · React 19 · JMH-measured
```

---

## The problem

Autocomplete has to answer on every keystroke, so it has a hard latency budget — a few
milliseconds at most, before the next character arrives. The obvious implementation is a loop:
walk every word in the dictionary, keep the ones starting with what the user typed. That works
fine for a few hundred words and falls apart as the corpus grows, because the work is proportional
to the size of the dictionary rather than to the number of results you actually show. Typo
tolerance makes it worse: checking whether `aple` is within two edits of every word means running
a dynamic-programming algorithm 100,000 times per keystroke. This project builds the data
structures that make both problems tractable — a trie so prefix lookup depends on the length of
what was typed rather than the size of the corpus, and a BK-tree so fuzzy matching can skip most
of the dictionary without ever comparing against it — and then benchmarks them honestly against
the naive version to show exactly where each one pays off and where it does not.

---

## Architecture

```
                        ┌──────────────────────────────────────┐
                        │  React frontend (Vite)               │
                        │  search · live compare · benchmarks  │
                        └──────────────────┬───────────────────┘
                                           │  GET /api/search
                                           │  GET /api/search/naive
                                           │  GET /api/compare
                        ┌──────────────────▼───────────────────┐
                        │  Spring Boot · SearchController      │
                        │  validation · CORS · error shape     │
                        └───────┬──────────────────────┬───────┘
                                │                      │
       ┌────────────────────────▼──────────┐   ┌───────▼─────────────────────────┐
       │ OptimizedSearchService            │   │ NaiveSearchService              │
       │                                   │   │ kept permanently as the control │
       │  SearchPolicy                     │   │ group for every benchmark       │
       │   ├─ prefix short-circuit         │   │                                 │
       │   └─ progressive relaxation d1→d2 │   │  for each of N words:           │
       │                                   │   │    word.startsWith(query)       │
       │  ┌─────────────┐ ┌──────────────┐ │   │    levenshtein(query, word) ≤ k │
       │  │    Trie     │ │   BK-tree    │ │   │                                 │
       │  │ best-first  │ │  triangle-   │ │   │  O(N·L) and O(N·m·k)            │
       │  │ over max-   │ │  inequality  │ │   │                                 │
       │  │ SubtreeWt   │ │  pruning     │ │   │                                 │
       │  └──────┬──────┘ └──────┬───────┘ │   └────────────────┬────────────────┘
       └─────────┼───────────────┼─────────┘                    │
                 │               │                              │
                 │        ┌──────▼──────────────┐               │
                 │        │ Levenshtein DP      │               │
                 │        │ exact (BK-tree)     │◄──────────────┤
                 │        │ banded cutoff(naive)│               │
                 │        └─────────────────────┘               │
                 └───────────────┬──────────────────────────────┘
                                 │
                                 │   candidates — the ONLY thing that differs
                                 ▼
                     ┌───────────────────────────────┐
                     │ RelevanceScorer      (shared) │
                     │   0.7·matchQuality            │
                     │ + 0.3·log₁₀(frequency)        │
                     ├───────────────────────────────┤
                     │ BoundedMinHeap       (shared) │
                     │   top-K in O(N log K) / O(K)  │
                     └───────────────┬───────────────┘
                                     ▼
                            ranked top-K results
                       tagged PREFIX or FUZZY + score
```

The two engines share every line of scoring, de-duplication and top-K selection. Only *candidate
generation* differs. That is deliberate: it means a measured speed difference is attributable to
the data structures and nothing else, and it makes the central guarantee testable —
`SearchServiceEquivalenceTest` asserts the two engines return **byte-identical** output: same
words, same order, same scores, same match types.

Without that test, "the optimized engine is 445× faster" would be an unfalsifiable claim. Faster
could just mean *returns less*.

---
<!-- BENCHMARK-SECTION-START -->
## Benchmarks

*JMH 1.37 — 2 forks, 3 warmup + 5 measurement iterations, average time per query, 10 results
requested. Each benchmark rotates through 16 queries so no single lucky prefix dominates.*

![naive vs optimized scaling](docs/benchmark-scaling.svg)

### The headline

One 6-character prefix query against 100,000 words:

| | time per query | queries/sec |
|---|---:|---:|
| brute-force linear scan | 1.14 ms | 879 |
| **trie** | **2.56 µs** | **391,236** |

**445× faster.**

### Prefix search — the speedup grows with the corpus

Speedup of the trie over the linear scan, by query length:

| corpus | 1-char query | 3-char query | 6-char query |
|---:|---:|---:|---:|
| 1,000 | 2.41× | 7.72× | 8.58× |
| 10,000 | **15.7×** | **24.7×** | **120×** |
| 50,000 | **44.2×** | **75.8×** | **317×** |
| 100,000 | **68.3×** | **131×** | **445×** |

**This is the result the project is built around.** The naive scan is O(N) and quadruples as
the corpus quadruples. The trie barely moves — its cost depends on the length of what was
typed and how many results are wanted, not on how many words are in the index. So the gap
does not merely persist as the corpus grows, it *widens*. That is a different complexity
class, not a constant-factor win.

### Fuzzy search — the speedup does not grow

Speedup of the BK-tree over the linear Levenshtein scan:

| corpus | edit distance 1 | edit distance 2 |
|---:|---:|---:|
| 1,000 | 1.57× | 0.82× |
| 10,000 | 1.82× | 0.74× |
| 50,000 | 4.15× | 1.20× |
| 100,000 | 3.46× | 0.93× |

**The BK-tree wins at edit distance 1 and roughly breaks even at distance 2**, where values
near or below 1.00× mean the brute-force scan is as fast or faster. Both curves stay O(N)-ish:
the BK-tree improves the constant factor, it does not change the complexity class.

The cause is structural. BK-tree pruning needs the *exact* distance at every visited node to
compute its window of child edges, so it pays a full O(m·n) dynamic-programming pass per node.
The linear scan only needs a yes/no answer, so it uses a banded cutoff whose length filter
rejects most of the corpus in O(1). At distance 1, pruning overcomes that handicap. At distance
2 the tree visits roughly half its nodes and loses.

### End-to-end, through the full pipeline

| corpus | full-word typo (worst case) | 4-char prefix (typical keystroke) |
|---:|---:|---:|
| 1,000 | 1.09× | 1.84× |
| 10,000 | 1.15× | 2.05× |
| 50,000 | 2.19× | 5.15× |
| 100,000 | 1.65× | 5.27× |

Both regimes matter. A complete misspelled word has almost no prefix matches, so the prefix
short-circuit cannot fire and relaxation escalates to distance 2 — the expensive path. A
partially typed word is what the overwhelming majority of keystrokes actually are, and there
the short-circuit skips fuzzy search entirely as provably unnecessary.

### What this actually shows

> **Prefix speedup grows with corpus size. Fuzzy speedup does not.**

The trie changed the asymptotics. The BK-tree improved a constant, and at edit distance 2 not
even that. Reporting a single averaged "our engine is N× faster" would hide both halves of
that, and the second half is the more interesting one: it says exactly where this optimisation
stops paying, and why. The engineering response was the prefix short-circuit — provably
lossless, and worth ~200× on the path real users take.
<!-- BENCHMARK-SECTION-END -->

---

## Why not just use Elasticsearch, or a database?

**You should. For a production system, use Elasticsearch, OpenSearch, Meilisearch, or Postgres
full-text search.** They handle persistence, replication, sharding, analyzers, stemming,
multi-language tokenization, incremental indexing and a decade of edge cases this project does
not.

`LIKE 'query%'` in Postgres can use a B-tree index and is genuinely fast for prefix matching. Once
you want typo tolerance, `pg_trgm` with a GIN index handles it well. Neither needs anything here.

This project exists for the opposite reason: **to understand what those tools are doing.** A
search engine is a set of data structures with specific trade-offs, and "we use Elasticsearch" is
not an answer to *why is prefix search fast* or *how does fuzzy matching avoid comparing against
every document*. Building the trie, the BK-tree and the ranking heap by hand — and then measuring
where each one stops paying — is a way to be able to answer those questions rather than defer
them.

The honest limits of what is built here are stated plainly in
[Known limitations](#known-limitations): the index is in-memory and build-once, there is no
persistence, no incremental update, no sharding, no stemming, and no multi-word query handling.
Those are the features that make a real search engine hard, and they were scoped out on purpose
rather than half-built.

---

## Running it

Requires **JDK 21** and **Node 18+**. Maven and the dataset are both bundled — nothing else to
install or download.

```bash
# Backend — http://localhost:8080
./mvnw spring-boot:run
```

```bash
# Frontend — http://localhost:5173
cd frontend && npm install && npm run dev
```

Then open <http://localhost:5173>. The search box races both engines on every keystroke.

```bash
# Full test suite (203 tests)
./mvnw test
```

```bash
# Regenerate the benchmarks (JMH, ~18 minutes; run it on an idle machine)
./mvnw test-compile exec:exec@benchmark -Dbench.thorough=true
```

```bash
# Fast benchmark sanity check (under a minute, not publication quality)
./mvnw test-compile exec:exec@benchmark -Dbench.quick=true
```

### API

| Endpoint | Purpose |
|---|---|
| `GET /api/search?q=&limit=` | Ranked results from the trie + BK-tree engine |
| `GET /api/search/naive?q=&limit=` | Same query against the brute-force baseline |
| `GET /api/compare?q=&limit=` | Both engines, both timings, plus an `identicalResults` flag |
| `GET /health`, `GET /api/health` | Liveness and index metadata |

```bash
curl 'http://localhost:8080/api/compare?q=aple&limit=3'
```

```json
{
  "query": "aple", "limit": 3,
  "optimizedMicros": 902.4, "naiveMicros": 7564.1,
  "speedup": 8.38, "identicalResults": true,
  "results": [
    { "word": "aplenty", "score": 0.851, "matchType": "PREFIX", "editDistance": 0, "weight": 163607 },
    { "word": "able",    "score": 0.583, "matchType": "FUZZY",  "editDistance": 1, "weight": 109389038 },
    { "word": "apple",   "score": 0.573, "matchType": "FUZZY",  "editDistance": 1, "weight": 50551171 }
  ]
}
```

`/api/compare` times both engines **server-side, in the same JVM, on the same index**. Timing them
from the browser instead would measure the network round-trip — milliseconds either way — which
would completely swamp the 5 µs versus 550 µs difference the comparison exists to show.

---
## Design decisions

Each entry is a choice actually made in this codebase, the alternative that was rejected, and the
reason.

### Trie — prefix matching

**Children stored in a `HashMap<Character, TrieNode>`, not a `TrieNode[26]` array.**
An array is denser and avoids hashing, and for a pure a–z dictionary it would be measurably
faster. It was rejected because it breaks on the first apostrophe (`o'clock`), hyphen
(`well-being`), digit (`3d`) or accent — and every realistic corpus has them. The trade is memory
and pointer-chasing for accepting arbitrary input.

**Each node stores `maxSubtreeWeight` — the maximum weight beneath it, not a running sum.**
This is the single most important design choice in the project. A sum ("how much traffic flows
through this prefix?") is a fine popularity statistic but useless for search, because a subtree
with a large total may contain nothing individually good. A **maximum** is an *admissible upper
bound*: no word beneath a node can score higher than it. That makes best-first search sound.

**Top-K prefix search is best-first (A\* with an admissible heuristic), not collect-then-sort.**
The naive trie autocomplete collects every descendant and sorts — O(M log M) in subtree size. Here
a priority queue is seeded at the prefix node and ordered by `maxSubtreeWeight`; popping a word
entry means it can be emitted immediately, because every remaining bound is an upper bound on
everything it represents. Cost becomes **output-sensitive** — driven by K, not by M.

> Measured: ranking the top 10 of 100,000 words expands **19 trie nodes**. For the prefix `"a"`,
> which covers 6,531 words, it expands **16**.

The simpler collect-then-sort version would be fine at 100k words for prefixes of 2+ characters.
It only falls apart on 1–2 character prefixes — which is exactly the search-as-you-type case.

**Known limitation, measured rather than assumed:** best-first is only output-sensitive to the
extent that weights are *distinct*. The tie-break rule expands every node whose bound ties the
current best before emitting any word at that weight, so a corpus with heavily quantised weights
degrades toward full expansion — 5,762 nodes instead of 98 on a corpus with only 7 distinct
weights. Real Zipfian frequency data is 93% distinct in the slice used here, so this never
triggers. The fix, if a future dataset needed it, is a composite bound of
`(maxSubtreeWeight, minWordLength, minLexWord)`; it is deliberately not built.

**No delete support.** `maxSubtreeWeight` is maintained incrementally on insert, which is valid
only because weights never decrease. Deletion would require recomputing it bottom-up. The index is
build-once, read-many.

### Levenshtein distance — edit distance

**Plain Levenshtein, not "optimal string alignment" (the cheap Damerau variant with
transpositions).**
Plain Levenshtein charges **2** for `teh → the`, even though a human reads it as one slip. Adding
an adjacent-transposition edit fixes that in four lines — and would silently break the BK-tree.
OSA **is not a metric**: it violates the triangle inequality.

```
OSA:  d(CA, AC) = 1     d(AC, ABC) = 1     d(CA, ABC) = 3      →  3 > 1 + 1
```

BK-tree pruning is *derived* from the triangle inequality, so an OSA-backed tree would not throw
or crash — it would silently return incomplete results, prune branches containing real matches,
and look fine in a demo. `OsaTriangleInequalityTest` pins that counterexample and confirms plain
Levenshtein does not have the flaw, so nobody later "improves" the metric.

True (unrestricted) Damerau-Levenshtein **is** a proper metric and would be safe. It needs a
last-occurrence table over the alphabet, and was scoped out deliberately, not overlooked.

**Three implementations, cross-checked against each other.**

| Implementation | Time | Space | Role |
|---|---|---|---|
| `distanceFullTable` | O(m·n) | O(m·n) | Textbook reference — the version to reason about on a whiteboard |
| `distance` | O(m·n) | O(min(m,n)) | Rolling two rows; the hot path, used by the BK-tree |
| `distanceWithCutoff` | O(m·k) | O(min(m,n)) | Banded + early exit; used by the brute-force scan |

Property tests assert all three agree on 5,000 random pairs across every budget, so the fast ones
cannot drift from the reference.

**The BK-tree cannot use the cutoff variant, and this is not an oversight.**
BK-tree pruning needs the **exact** distance at every visited node, because that number defines
the window of child edges worth descending. A clamped value produces the wrong window and silently
drops matches. So the *brute-force baseline* gets an optimisation the "optimized" path is
structurally denied — which makes the baseline a genuinely fast opponent rather than a straw man,
and is a large part of why the BK-tree loses at edit distance 2.

### BK-tree — fuzzy matching

**Edges labelled with the edit distance from parent to child; search descends only
`[d − k, d + k]`.**
The invariant that makes this work is stronger than usually stated: because routing at a node is
decided purely by distance from that node's word, **every node in the subtree under edge label ℓ
sits at distance exactly ℓ from the parent's word** — not just the immediate child. So for any
descendant `x` under edge ℓ, the triangle inequality gives

```
distance(query, x)  ≥  | distance(query, node) − ℓ |  =  | d − ℓ |
```

If `|d − ℓ| > k`, *nothing* in that entire subtree can be a hit, and the branch is skipped without
a single further distance computation. That licenses pruning a whole subtree rather than one node.

**No sublinear bound is claimed.** The common assertion that BK-tree search is O(log n) is
folklore. The worst case is O(n) and the real cost is data-dependent. What is true and measurable
is that each visited node opens at most `2k+1` child edges, so pruning is strong for small `k` and
decays toward a linear scan as `k` grows. Every search therefore reports its own
`distanceComputations`, and the benchmark sweeps `k` instead of quoting a complexity class.

**Insertion order is shuffled with a fixed seed.** A BK-tree built from a sorted word list
degenerates: consecutive dictionary words differ by tiny distances and pile into a handful of
edges. The seed is fixed so every build produces an identical tree and a bad benchmark number is
reproducible.

**The metric is injected** (`StringMetric`), which keeps the tree a general metric-space index
rather than a string-specific one, and means swapping the distance function later touches nothing
inside it.

### Bounded min-heap — top-K selection

**Hand-written binary heap over an array, capacity K.** `PriorityQueue` would have worked, and is
retained — as a **test oracle**. The hand-rolled version is what ships; the standard-library
version exists so that any bug in the sift logic surfaces as a disagreement rather than as
plausible-looking wrong output.

**A min-heap, even though we want the *best* items.** The heap holds the K best seen so far, and
its root is the **worst** of those — exactly the element a newcomer must beat and exactly the one
to evict when it does. Both are O(1) at the root. A max-heap would keep the one element never
needed during the scan.

**One comparator, not two.** A classic bug here is defining output order and eviction order
separately and getting them subtly out of sync. This class takes a single `betterFirst` comparator
and derives the heap ordering by swapping its arguments, so they cannot drift.

O(N log K) time and O(K) space, versus O(N log N) and O(N) for sort-then-truncate.

### Ranking

```
score = 0.7 · matchQuality + 0.3 · popularity

matchQuality(PREFIX)   = 1.0
matchQuality(FUZZY, d) = 1 / (1 + d)
popularity(w)          = log₁₀(1 + w) / log₁₀(1 + maxWeight)
```

**Popularity is logarithmic** because word frequencies are Zipfian — `the` outweighs a
mid-frequency word by four orders of magnitude. Used raw, popularity would swamp every other
signal and every result list would be the same handful of stopwords.

**Guaranteed: every prefix match outranks every fuzzy match.** The prefix band is [0.70, 1.00] and
the best possible fuzzy score is 0.65. What the user literally typed is strong evidence of intent.
This guarantee is load-bearing — the prefix short-circuit below depends on it — so
`RelevanceScorerTest.prefixTierNeverOverlapsFuzzyTier` pins it, with a comment noting the margin
is thinner than it looks (it holds only while the popularity weight stays under ⅓).

**Not guaranteed: that a closer fuzzy match always beats a more distant one.** The fuzzy bands
overlap deliberately. Strict separation at every distance would need a popularity weight below
0.08 (the quality gap between d=2 and d=3 is only `⅓ − ¼ = 0.083`), making corpus frequency
almost irrelevant to ranking. A meaningful popularity signal is worth more than a tier boundary
nobody would notice.

**Tie-break: score → shorter word → lexicographic.** The third tier exists to make the order
*total*, so no two distinct words ever compare equal. That is what makes results byte-identical
run to run — required for stable tests and reproducible benchmarks.

**A known ranking limitation, named rather than hidden:** edit distance does not model how typos
happen. Given `recieve`, `relieve` is 1 edit away and `receive` is 2, so this scorer ranks the
wrong word first unless the frequency gap is enormous — and it is not. Fixing it properly needs a
typo-aware distance (true Damerau, or keyboard-adjacency weighting), not different constants.

### Search policy

**Prefix short-circuit — exact, not heuristic.** If prefix matches alone fill the page, fuzzy
search is skipped entirely. Because the score bands cannot overlap, `limit` prefix candidates
*are* the top `limit` of the union, so skipping cannot change a single result.

> Measured: ~4 µs when it fires, ~950 µs when it does not — a 200× cliff at exactly `limit`
> prefix matches. It fires for 11 of 16 typical 4-character queries.

**Progressive relaxation — search at distance 1 first, widen only if the page is short.** Two
reasons. Performance: the BK-tree beats a linear scan at d=1 and loses at d=2, so this keeps the
engine in the regime where it pays. Quality: it restores "closer matches first", which the
overlapping score bands do not guarantee. The trade-off is stated plainly in the code — a query
that fills its page at distance 1 will never surface a hugely popular distance-2 correction.

**The edit budget scales with query length** (0 / 1 / 2 for ≤2, ≤4, longer). A fixed budget is
wrong at both ends: 2 edits on a 3-letter query matches half the dictionary; 1 edit on a
13-letter query finds nothing when someone fat-fingers twice.

### Dataset

**100,000 words from Norvig's `count_1w.txt`** (Google Web Trillion Word Corpus), bundled as a
classpath resource so a deployed jar carries its own index — no volume mount, no download at boot.

**Truncated at 100,000 of 333,333 — and not for size.** Frequency ties degrade the trie's
best-first search, so tie density was measured before choosing:

| slice | distinct counts | entries in a tied group | largest tie group |
|---|---:|---:|---:|
| top 100,000 | **93.0%** | 13.2% | 5 words |
| all 333,333 | 47.5% | 67.6% | 152 words |

The tail bottoms out at a floor count of 12,711 shared by 152 words. Cutting at 100,000 keeps the
well-differentiated head — which is also the half that is real vocabulary rather than web typos.

**Misspellings are left in the corpus.** Web text contains them often enough that `recieve`,
`definately` and `seperate` are themselves in the top 100,000, so they win as literal *prefix*
matches over the corrections. The engine is behaving correctly, and the correction lands directly
below — the same thing Google does.

Filtering against a dictionary was **measured and rejected**: only 34,586 of the 100,000 terms
appear in `/usr/share/dict/words`, and the 65,414 it would discard include `accessories`,
`accounts`, `activities`, `applications` and `americans`. The system dictionary is Webster's
Second from 1934. The filter would remove far more real words than misspellings.

### Benchmark methodology

**JMH, not a hand-written timing loop.** JIT warmup, dead-code elimination and on-stack
replacement make hand-rolled Java microbenchmarks systematically wrong at the microsecond-to-
millisecond scale measured here. JMH is driven programmatically and reads `RunResult` objects
directly rather than parsing its output, so the report cannot drift from the run that produced it.

**Queries are derived from the top 1,000 words**, so the same query is valid at every corpus size.
A query that only matched at 100k would return nothing at 1k — and "returns nothing" is fast for
both engines, so the small end of the scaling curve would be measuring the empty path.

**A variance guard, because a contended run produces nonsense.** Any measurement whose 99.9%
confidence interval exceeds 25% of its value is flagged, excluded from bold, and warned about on
the console. This exists because a real run reported `131,951 µs ±582,064` for a configuration
whose true value is ~1,100 µs — which would have been published as a **50,623× speedup**. One
absurd number discredits every honest number beside it. The flag travels in the JSON the frontend
reads as well as the markdown, since a guard that only covers the channel a human reads is not a
guard.

**`BenchmarkFairnessTest` audits the benchmark itself** — asserting both engines return identical
results for every benchmarked configuration, that every query does real work at every size, and
that corpus slices are nested so N is the only variable in the sweep.

### API and concurrency

**The index is build-once, read-many, and shared without locks.** The trie and BK-tree are mutated
only during construction; Spring publishes fully-constructed singletons with the necessary
happens-before relationship, so concurrent requests read an immutable structure with no
contention. Concurrent *writes* would not be safe — which is why there is no runtime insert API.

**Search statistics are returned in the result, not stored in fields**, so concurrent queries
cannot corrupt each other's counters.

**A 64-character query cap.** Levenshtein is O(m·n) per word and `/api/search/naive` is publicly
reachable, so a 10,000-character query would turn one HTTP request into billions of cell
computations.

**JIT warmup before the beans are published.** The first live comparison on a cold JVM reported
12.13 ms for the optimized engine against ~880 µs once warm — an order of magnitude, purely from
the interpreter running before C2 compiles the hot loops. A demo whose headline number is 10×
wrong for the first visitor is worse than no demo. Warming inside the `@Bean` method (rather than
an `ApplicationRunner`) guarantees it finishes before the servlet container accepts requests.

---
## What is tested

**203 tests.** The suite is built around a handful that carry disproportionate weight:

| Test | What it rules out |
|---|---|
| `SearchServiceEquivalenceTest` | That "faster" secretly means "returns less". Asserts the two engines produce byte-identical output across random corpora, queries and limits. |
| `BKTreeTest.pruningIsLossless` | That triangle-inequality pruning silently drops real matches. 30 corpora × 5 distance budgets × 5 queries against a brute-force oracle. |
| `TrieTest.bestFirstMatchesExhaustiveOracle` | That the clever traversal disagrees with collect-then-sort. 200 corpora × 10 probes, ties included. |
| `OsaTriangleInequalityTest` | That someone "improves" the metric by adding transpositions and quietly breaks the BK-tree. Pins the counterexample. |
| `LevenshteinDistanceTest` (property tests) | That the two fast distance implementations drift from the reference. 5,000 random pairs across every budget. |
| `BenchmarkFairnessTest` | That the benchmark itself is measuring the wrong thing — empty result paths, mismatched engines, or a confounded corpus. |
| `SearchControllerIntegrationTest` | That the wiring works. Runs against the real Spring context and the real 100,000-word index, not a mock. |

Performance claims are measured rather than asserted: both the trie and the BK-tree report their
own traversal statistics, and tests assert on those counters directly.

---

## Repository layout

```
src/main/java/com/fuzzysearch/
├── core/                          ← zero framework dependencies, testable standalone
│   ├── trie/          Trie, TrieNode          best-first ranked prefix search
│   ├── bktree/        BKTree                  metric-space fuzzy index
│   ├── distance/      LevenshteinDistance     three implementations, cross-checked
│   │                  StringMetric            documents the axioms pruning depends on
│   ├── rank/          BoundedMinHeap          hand-written top-K selector
│   │                  Candidate               the single definition of "better"
│   ├── search/        SearchService           the contract both engines implement
│   │                  NaiveSearchService      brute force, kept permanently
│   │                  OptimizedSearchService  trie + BK-tree
│   │                  SearchPolicy            short-circuit + progressive relaxation
│   │                  RelevanceScorer         shared scoring — the fairness guarantee
│   │                  ResultMerger            shared dedup + top-K
│   ├── index/         DatasetLoader, Corpus, BundledDataset
│   └── text/          TextNormalizer          the case policy, in one place
├── api/               SearchController, HealthController, validation, error shape
└── config/            SearchIndexConfig (index build + JIT warmup), WebConfig (CORS)

frontend/src/          React: search page, live comparison, benchmark charts
docs/                  benchmarks.md · benchmark-data.json · benchmark-scaling.svg
                       complexity.md — the full running design record, phase by phase
```

`core` has **no Spring imports**. The algorithms compile and test without a container and would
drop into any other application unchanged.

---

## Known limitations

Scoped out deliberately, not overlooked:

- **In-memory, build-once.** No persistence, no incremental updates, no deletes. A production
  version would need a write path, and `maxSubtreeWeight` would need bottom-up recomputation to
  support it. Restart cost is ~2.3 s including JIT warmup.
- **Single node.** No sharding or replication. At 10M+ entries the index would need partitioning
  by prefix range, with a scatter-gather merge across shards — the top-K heap already does the
  merge step.
- **Single-token queries.** No multi-word matching, phrase queries, or field weighting. The loader
  and index accept multi-word terms, but the query path treats input as one string.
- **No stemming, lemmatisation, or analyzers.** `running` will not find `run`.
- **ASCII-oriented.** Distance works on UTF-16 code units, so a non-BMP character (emoji) counts
  as two edits. Documented in the class javadoc with a test pinning the behaviour.
- **Fuzzy matching is the bottleneck**, not prefix matching. At edit distance 2 the BK-tree does
  not beat a well-optimised linear scan. The measured fix is the prefix short-circuit, which
  avoids the fuzzy path entirely for most real keystrokes.
- **Ranking is untuned.** The 0.7/0.3 weights are a judgement call, not learned. A real system
  would tune them on click data.

---

## Dataset

Peter Norvig, *Natural Language Corpus Data* — [`count_1w.txt`](https://norvig.com/ngrams/count_1w.txt),
derived from the Google Web Trillion Word Corpus (Brants & Franz, 2006) and published freely for
research and education. The top 100,000 entries are bundled; provenance, regeneration steps and
the tie-density analysis are in [`src/main/resources/data/README.md`](src/main/resources/data/README.md).

---

## Further reading

- [`docs/complexity.md`](docs/complexity.md) — Big-O reference for every operation, plus the full
  phase-by-phase design record with every rejected alternative
- [`docs/benchmarks.md`](docs/benchmarks.md) — complete benchmark tables
