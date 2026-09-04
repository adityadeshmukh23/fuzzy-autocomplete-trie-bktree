# Phase 1 — Complexity Reference

Notation used throughout:

| Symbol | Meaning |
|---|---|
| `N` | number of words indexed |
| `L` | length of the word or query being processed |
| `A` | alphabet branching factor (children per trie node) |
| `M` | number of words sitting under a given prefix |
| `K` | number of results requested (top-K) |
| `k` | maximum edit distance allowed (fuzzy budget) |

---

## 1. Trie — `com.fuzzysearch.core.trie.Trie`

| Operation | Time | Space | Notes |
|---|---|---|---|
| `insert(word, weight)` | **O(L)** | O(L) new nodes worst case | One walk down, one pass back up the same path to raise `maxSubtreeWeight`. Independent of `N`. |
| `contains` / `weightOf` / `hasPrefix` | **O(L)** | O(1) | Pure descent. |
| `topKWithPrefix(prefix, K)` | **O(L + K·A·log(K·A))** | O(K·A) | Best-first search. **Independent of `M`.** |
| `allWithPrefix(prefix)` | **O(L + M·A + M log M)** | O(M) | Collect whole subtree, then sort. |
| Total structure | — | O(total characters) nodes | Shared prefixes share nodes. |

**The point of the whole structure.** `topKWithPrefix` does not depend on `M`. Measured on 20,000
words all sharing the prefix `"a"`, asking for the top 10:

```
subtree size                20,000 words
nodes expanded (K=10)           38
nodes expanded (K=500)       1,072
```

38 nodes to answer a query whose match set is 20,000 words. The exhaustive
`allWithPrefix` alternative must touch all 20,000 and then sort them. This is the difference
between an O(M log M) autocomplete and an output-sensitive one, and it is why every node stores
`maxSubtreeWeight` as an *upper bound* rather than a running sum.

---

## 2. Levenshtein distance — `com.fuzzysearch.core.distance.LevenshteinDistance`

Let `m` and `n` be the two string lengths.

| Operation | Time | Space | Notes |
|---|---|---|---|
| `distanceFullTable(a, b)` | **O(m·n)** | **O(m·n)** | The textbook reference implementation. |
| `distance(a, b)` | **O(m·n)** | **O(min(m,n))** | Rolling two rows. Same results, used on hot paths. |
| `distanceWithCutoff(a, b, k)` | **O(m·k)** | **O(min(m,n))** | Banded + early exit. For `k=2` this is effectively linear. |

`distanceWithCutoff` gets its speed from three independent cuts: the length gap `|m−n|` is a
lower bound on the distance, so a big gap bails instantly; an alignment costing `≤ k` can never
stray more than `k` cells from the diagonal, so only a band of width `2k+1` is computed; and if
every cell in a row already exceeds `k`, no later row can recover.

**Why the BK-tree cannot use the cutoff version.** BK-tree pruning needs the *exact* distance at
each visited node, because that number defines the window of child edges to descend. A clamped
value produces the wrong window and silently drops valid matches. The cutoff variant is for the
Phase 2 brute-force scan — which makes the naive baseline genuinely fast, and therefore a fair
opponent in the Phase 4 benchmark rather than a straw man.

---

## 3. BK-tree — `com.fuzzysearch.core.bktree.BKTree`

| Operation | Time | Space | Notes |
|---|---|---|---|
| `add(word)` | **O(D · m·n)** | O(1) new node | `D` = tree depth. One exact distance computation per level. |
| `search(query, k)` | **O(V · m·n)**, `V` = nodes visited | O(D) stack | `V` is data-dependent; worst case `V = N`. |
| `maxDepth()` | O(N) | O(D) | Diagnostic only. |

**There is no proven sublinear bound.** The common claim that BK-tree search is O(log N) is
folklore. The honest statement is: each visited node opens at most `2k+1` of its child edges, so
pruning is strong for small `k` and decays toward a linear scan as `k` grows. That is a
measurable property, not a complexity class — hence `searchWithStats` reports
`distanceComputations` on every query.

Measured on 47,468 distinct random strings (a deliberately *unfavourable* corpus — random strings
over a 20-letter alphabet cluster into a narrow distance distribution, which is the worst case for
pruning):

| `maxDistance` | distance computations | share of corpus scanned |
|---:|---:|---:|
| 0 | 9 | 0.02% |
| 1 | 5,515 | 11.6% |
| 2 | 24,248 | 51.1% |
| 3 | 36,937 | 77.8% |

The shape of that table is the real result: excellent at `k ≤ 1`, breaking even around `k = 2`,
worthless at `k = 3`. Real English words should prune better (their distances spread more widely),
and Phase 4 will measure exactly that on the real dataset. It also justifies making the fuzzy
budget query-length-dependent rather than fixed.

**Insertion order matters.** Building from a sorted word list gives a degenerate tree. Measured on
the same corpus, sorted input produced `maxDepth 21` and 20,736 distance computations at `k=2`
versus 22 / 24,248 for shuffled input — comparable here only because random strings are already
adversarial; on real vocabulary the gap is much larger. Callers should shuffle with a fixed seed.

---

## 4. Bounded min-heap — `com.fuzzysearch.core.rank.BoundedMinHeap`

| Operation | Time | Space | Notes |
|---|---|---|---|
| `offer(candidate)` | **O(log K)** retained, **O(1)** rejected | O(1) | Once warm, most candidates lose on a single comparison against the root. |
| `topK(items, K)` over N items | **O(N log K)** | **O(K)** | vs. O(N log N) time / O(N) space for sort-then-truncate. |
| `drainBestFirst()` | O(K log K) | O(K) | Destructive. |
| `worst()` | O(1) | O(1) | The root is the eviction candidate. |

**Why a min-heap when we want the best items.** The heap holds the K best so far, and its root is
the *worst* of those — exactly the element a newcomer must beat and exactly the one to evict. Both
are O(1) at the root. A max-heap would keep the one element never needed during the scan.

---

## Summary table

| Structure | Insert | Lookup |
|---|---|---|
| Trie | O(L) | O(L + K·A·log(K·A)) for ranked top-K — independent of subtree size |
| Levenshtein DP | n/a | O(m·n), or O(m·k) with the banded cutoff |
| BK-tree | O(D · m·n) | O(V · m·n), V data-dependent, worst case O(N · m·n) |
| Bounded min-heap | O(log K) | O(K log K) to drain |

---

## Phase 2 — search services

| Operation | Naive | Optimized |
|---|---|---|
| `prefixSearch(q, K)` | **O(N·L)** — `startsWith` against every word | **O(L + K·A·log(K·A))** — trie descent + best-first |
| `fuzzySearch(q, K, k)` | **O(N·m·k)** — banded cutoff against every word | **O(V·m·n)** — BK-tree, V = nodes not pruned |
| `search(q, K)` | one pass doing both tests | trie top-K + BK-tree, merged |

Both then share the identical scoring (`RelevanceScorer`), de-duplication and top-K selection
(`ResultMerger` → `BoundedMinHeap`), so the only difference between them is candidate
*generation*. `SearchServiceEquivalenceTest` asserts they return byte-identical output.

### Measured on 100,000 real English words (`/usr/share/dict/words`)

**Prefix search — the trie wins decisively:**

| query | naive | trie | speedup |
|---|---:|---:|---:|
| `app` | 596 µs | 8.5 µs | **70×** |
| `prog` | 676 µs | 7.6 µs | **89×** |
| `consti` | 738 µs | 0.9 µs | **806×** |

**Fuzzy search — the BK-tree wins at distance 1 and *loses* at distance 2:**

| query | k=1 speedup | k=2 speedup |
|---|---:|---:|
| `aple` | **1.79×** | 0.29× |
| `recieve` | **2.54×** | 0.63× |
| `acommodation` | **5.69×** | 0.94× |
| `programing` | **5.02×** | 0.90× |

The cause is structural, not a tuning problem. The BK-tree needs the *exact* distance at every
visited node to compute its pruning window, so it pays a full O(m·n) DP per visit. The naive scan
needs only a yes/no answer, so it uses `distanceWithCutoff`, whose length filter rejects most of
the corpus in O(1) and whose band makes the survivors O(m·k). At k=1 pruning is strong enough to
overcome that handicap; at k=2 the BK-tree visits roughly half the tree and loses.

This is the honest result and it belongs in the README as-is. It is also the argument for
progressive relaxation — search at k=1 first and only widen when too few results come back —
which keeps the engine in the regime where the BK-tree actually pays.

### Known limitation: the trie's best-first search is sensitive to weight ties

`nodesExpanded` for `topKWithPrefix("a", 10)` over the same 7,154-word subtree:

| weight distribution | distinct weights | nodes expanded |
|---|---:|---:|
| Zipfian by rank (realistic) | 6,224 | **98** |
| coarse, 7 distinct values | 7 | 5,762 |
| all weights identical | 1 | whole subtree |

The "expand nodes before emitting words" tie-break forces every node whose bound *ties* the
current best to be opened before any word at that weight can be emitted. So the cost is driven by
how many words are tied at the subtree maximum, not by subtree size. Real frequency data has
near-unique weights and behaves like the first row; heavily quantized weights degrade toward full
expansion.

**The fix, if it turns out to be needed:** make the node bound a composite of
`(maxSubtreeWeight, minWordLengthInSubtree, minLexWordInSubtree)` so bounds are totally ordered
consistently with `BETTER_FIRST` and ties never force expansion. Costs one int and one reference
per node (~5 MB at this corpus size). Deferred until Phase 3's real dataset shows whether tie
density is actually a problem.

---

## Phase 3 — the real dataset

**100,000 English words with Google Web Trillion Word Corpus frequencies** (Norvig's
`count_1w.txt`, top 100k of 333,333). Provenance and regeneration steps in
`src/main/resources/data/README.md`.

| | |
|---|---|
| parse time | 201 ms |
| index build (trie + BK-tree) | 440 ms |
| trie nodes | 233,569 |
| BK-tree max depth | 28 |
| approximate heap for the full index | 45 MB |
| weight range | 23,135,851,162 (`the`) → 99,133 |

### The tie-density risk from Phase 2 did not materialise

`nodesExpanded` for a top-10 query on the real corpus:

| prefix | words under it | nodes expanded |
|---|---:|---:|
| `""` (global top 10) | 100,000 | **19** |
| `"a"` | 6,531 | **16** |
| `"s"` | 10,014 | **28** |
| `"co"` | 2,859 | **43** |

19 nodes to rank the ten most popular words out of 100,000. Real Zipfian frequencies are 93%
distinct in this slice, so the tie-forced expansion described in the Phase 2 section never
triggers. The composite-bound fix stays unbuilt, and stays justified.

### The corpus contains the misspellings, and that is not a bug

Web text is full of common misspellings, so `recieve`, `definately`, `seperate` and
`acommodation` are all themselves frequent enough to be in the top 100,000. They therefore win as
literal **prefix** matches over the corrections they should be corrected to:

```
query 'definately'
  definately     PREFIX  d=0  score 0.8806   weight  1,728,545
  definitely     FUZZY   d=1  score 0.5585   weight 15,922,257
  definatly      FUZZY   d=1  score 0.5052   weight    229,419
```

The engine is behaving exactly as designed: prefix matches outrank fuzzy ones, and the correction
lands immediately below. This is also what a real search engine does — show results for what was
typed, offer the correction alongside.

**Filtering the corpus against a dictionary was measured and rejected.** Only 34,586 of the
100,000 terms appear in the system dictionary (`/usr/share/dict/words`, the 1934 Webster's
Second). The 65,414 it would discard include `accessories`, `accounts`, `activities`,
`addresses`, `applications`, `americans`, `africa` — the dictionary predates plurals-as-entries,
proper nouns and all modern vocabulary. The filter would remove more real words than misspellings.

The cheap improvement, if the demo needs it, is a frontend affordance rather than a data change:
surface the top `FUZZY` result as a "did you mean" when it is far more frequent than the top
`PREFIX` result. That is a Phase 6 decision.

---

## Phases 4-5 — benchmarking and the API

Full results in `benchmarks.md`, regenerated by `./mvnw test-compile exec:exec@benchmark`.

**The headline asymmetry.** Prefix speedup *grows with corpus size*; fuzzy speedup does not.

| | 1,000 | 10,000 | 50,000 | 100,000 |
|---|---:|---:|---:|---:|
| prefix, 6-char query | 13× | — | 314× | **436×** |
| fuzzy, distance 1 | 2.3× | 1.9× | — | **3.5×** |
| fuzzy, distance 2 | 1.0× | 0.76× | — | **0.90×** |

The trie changes the complexity class: 0.38 µs at 1,000 words and 2.54 µs at 100,000, against a
linear scan that grows in proportion to N. The BK-tree only improves a constant factor, and at
distance 2 not even that. Both of those are worth stating plainly rather than averaging into a
single flattering number.

**Why the BK-tree loses at distance 2.** It needs the *exact* distance at every visited node to
compute its pruning window, so it pays a full O(m·n) DP per visit. The linear scan only needs a
yes/no answer, so it uses the banded cutoff, whose length filter rejects most of the corpus in
O(1). At distance 1 pruning overcomes that handicap; at distance 2 it visits roughly half the tree
and loses.

**The prefix short-circuit.** Because prefix scores [0.70, 1.00] never overlap fuzzy scores
[0.35, 0.65], a full page of prefix matches *is* the final answer and fuzzy search can be skipped
with no possible change in results. Measured on real queries: ~4 µs when it fires versus ~950 µs
when it does not — a 200× cliff at exactly `limit` prefix matches. It fires for 11 of 16 typical
4-character queries.

**Measurement honesty.** `BenchmarkSuite` flags any measurement whose 99.9% confidence half-width
exceeds 25% of its value, excludes it from bold, and warns on the console. This exists because a
contended run reported 131,951 µs ±582,064 for a configuration whose true value is ~1,100 µs —
which would have been published as a "50,623× speedup". One absurd number discredits every honest
number beside it.

---

## Phase 6 — frontend

React + Vite, plain CSS, no UI framework and no charting library. Two views behind a tab state
variable rather than a router.

**The comparison is the page, not a feature of it.** The search box calls `/api/compare` by
default, so every keystroke shows the trie/BK-tree engine and the brute-force scan racing on the
same index, with an "identical results" badge. Unticking a checkbox switches to `/api/search`
alone. Measured live on the running server: `aple` returns in ~880 µs against ~7.5 ms, an 8.6x
gap, with both engines returning the same ranked list.

**Timing has to happen on the server.** If the browser called both endpoints and timed them with
`performance.now()`, it would be measuring the network round-trip -- milliseconds either way --
which completely swamps the 5 µs vs 550 µs difference the demo exists to show. Both engines are
timed in-process, best-of-3, and the numbers are returned in one response.

**JIT warmup at startup.** The first live comparison on a cold JVM reported 12.13 ms for the
optimised engine against ~880 µs once warm: an order of magnitude, purely from the interpreter
running before C2 compiles the hot loops. `SearchIndexConfig` now runs 3 rounds of 14 mixed
prefix and typo queries against both engines before publishing the beans, which costs 525 ms of
startup (total 2.3 s) and makes the first visitor's number honest.

**The chart data needed the reliability flag too.** `BenchmarkSuite` flagged shaky measurements in
the markdown but not in the JSON the frontend reads, so `benchmark-data.json` carried three
contention artifacts -- including a 1,942x "speedup" -- indistinguishable from real data. The JSON
writer now emits `reliable`, and the chart draws flagged points hollow with dashed segments. A
guard that only covers the channel a human reads is not a guard.

---

## Design decisions recorded in Phase 1

| Decision | Alternative rejected | Reason |
|---|---|---|
| `HashMap` trie children | fixed `TrieNode[26]` array | Array is faster and denser but breaks on apostrophes, hyphens, digits, accents — all present in real corpora. |
| `maxSubtreeWeight` = **max** | running **sum** of frequencies | A sum is a popularity statistic but not an upper bound, so it cannot license pruning. Max is admissible and makes best-first search sound. |
| Plain Levenshtein | optimal string alignment (cheap Damerau) | OSA is **not a metric** — it violates the triangle inequality, which would silently break BK-tree pruning. Proven by counterexample in `OsaTriangleInequalityTest`. |
| Plain Levenshtein | true (unrestricted) Damerau-Levenshtein | *Is* a valid metric and would handle transpositions properly, but needs a last-occurrence table over the alphabet. Deliberately out of scope, not overlooked. |
| Exact distance in BK-tree search | cutoff-clamped distance | Pruning windows require the exact value; clamping loses matches silently. |
| Hand-built bounded min-heap | `PriorityQueue` | Written by hand as a core deliverable; `PriorityQueue` is retained as a *test oracle* so sift-logic bugs surface as disagreements. |
| Case-insensitive index, display form preserved | case-sensitive | Users do not capitalise while typing. Normalisation is pinned to `Locale.ROOT` so a Turkish-locale JVM cannot corrupt the index. |
| Tie-break: score → shorter → lexicographic | leave ties unordered | Makes the order **total**, so results are byte-identical run to run — required for stable tests and reproducible benchmarks. |
| No delete support | maintain deletability | `maxSubtreeWeight` is maintained incrementally, valid only under non-decreasing weights. The index is build-once, read-many. |

## Design decisions recorded in Phase 2

| Decision | Alternative rejected | Reason |
|---|---|---|
| Shared `RelevanceScorer` / `ResultMerger` across both engines | duplicate scoring in each | The two must differ *only* in candidate generation, or a measured speed gap could be ranking policy rather than data structures — and identical output could not be asserted. |
| Precomputed normalized keys in `WordEntry` | normalize during the scan | Otherwise the naive scan lower-cases 100k strings per query and the benchmark measures allocation, not algorithms. |
| Naive fuzzy uses `distanceWithCutoff` | full DP, to make the baseline look worse | A straw-man baseline proves nothing. The baseline gets an optimisation the BK-tree structurally cannot use. |
| `Corpus.deduplicate` run by both services | trust the caller | The trie merges duplicate keys automatically and a flat list does not; that difference alone would break equivalence, silently and in a way that looks like a ranking bug. |
| Prefix matches filtered out of BK-tree hits in `search` | rely on scoring tiers to demote them | A prefix match outside the trie's top-K could return through the BK-tree mislabelled `FUZZY`. The scoring tiers happen to hide it, but correctness should not depend on two tuning constants. |
| Score bands: prefix strictly above fuzzy; fuzzy tiers overlap | strict tiers at every distance | Strict separation across all distances needs `POPULARITY_WEIGHT < 0.08`, making corpus frequency nearly irrelevant. Prefix-vs-fuzzy separation is guaranteed and pinned by test; distance tiers deliberately are not. |
| Length-scaled fuzzy budget (`FuzzyBudget`) | a fixed budget of 2 | 2 edits on a 3-letter query matches half the dictionary; 1 edit on a 13-letter query finds nothing. Also keeps the expensive BK-tree case rare. |
| Always run fuzzy alongside prefix | skip fuzzy when prefix results suffice | A real product should skip it. Running both unconditionally keeps the Phase 4 comparison clean; noted as a deliberate non-optimisation. |

## Design decisions recorded in Phase 3

| Decision | Alternative rejected | Reason |
|---|---|---|
| Progressive relaxation (search d=1, widen only if short) | always search the full budget | The BK-tree beats a linear scan at d=1 and loses at d=2, so this keeps the engine in the regime where it pays. It also restores "closer matches first", which the overlapping score bands do not guarantee. |
| Norvig `count_1w` top 100,000 | OpenSubtitles frequencies; Wikipedia titles + pageviews | Best weight distribution for the trie (93% distinct counts), single file, no assembly step. Wikipedia would demo better but needs two dumps joined and hundreds of MB of preprocessing. |
| Truncate at 100,000 of 333,333 | use the whole file | Not just size: the tail is 67.6% tied, with 152 words sharing the floor count of 12,711. Ties are what degrade the trie's best-first search. |
| Dataset bundled as a classpath resource | read a path from disk at boot | A deployed JAR carries its own index data — no volume mount, no download step, which matters for free-tier hosting in Phase 8. |
| Keep misspellings in the corpus | filter against a system dictionary | Measured: the filter would drop 65% of the vocabulary including `accounts`, `activities`, `americans`. The engine already surfaces the correction directly below the literal match. |
| Rank-derived weights when a file has no counts | default every weight to 0 or 1 | Uniform weights make ranking meaningless *and* degrade the trie to full-subtree expansion. Frequency-ordered lists are common, so line order is real information. |
| Count recognised only after an explicit separator | any trailing digits | Otherwise `covid19` parses as term `covid` with count 19. |

## Design decisions recorded in Phases 4-5

| Decision | Alternative rejected | Reason |
|---|---|---|
| JMH | a hand-written timing loop | JIT warmup, dead-code elimination and on-stack replacement make hand-rolled Java microbenchmarks systematically wrong at the microsecond-to-millisecond scale we measure. |
| JMH driven programmatically, reading `RunResult` | parsing JMH's JSON/CSV output | The report cannot drift from the run that produced it. |
| `exec:exec` | `exec:java` | `exec:java` runs inside Maven's JVM, so `java.class.path` is Maven's classpath — and JMH forks a fresh JVM using exactly that property. The forks would start without the project on their classpath. |
| Queries derived from the top 1,000 words | queries drawn from the full corpus | A query that only matches at 100k returns nothing at 1k, and "returns nothing" is fast for both engines — the small end of the curve would measure the empty path. |
| Variance guard on the report | publish whatever JMH returns | A single contention artifact becomes a false headline claim that discredits the whole document. |
| Prefix short-circuit | always run fuzzy search | Exactly lossless given the score-band separation, and worth ~200× on the common path. Depends on a guarantee pinned by `RelevanceScorerTest`. |
| Comparison timed server-side (`/api/compare`) | browser calls both endpoints and times them | Network round-trip is milliseconds and would swamp the 62 µs vs 3,091 µs difference the demo exists to show. |
| `ResponseEntityExceptionHandler` as the base | a bare `@ExceptionHandler(Exception.class)` | The bare catch-all swallows Spring's own web exceptions, turning an unknown path's 404 into a 500. Caught by integration test. |
| 64-character query cap | no limit | Levenshtein is O(m·n) per word and `/api/search/naive` is publicly reachable; a 10,000-character query would turn one request into billions of cell computations. |
| Integration tests against the real context and real index | `@WebMvcTest` with a mocked service | Mocking would only prove the controller calls a method. The real wiring is what broke (the 404). |

## Design decisions recorded in Phase 6

| Decision | Alternative rejected | Reason |
|---|---|---|
| Search box calls `/api/compare` by default | call `/api/search`, hide comparison behind a button | The server-timed head-to-head is the strongest thing this project has to show. Burying it behind a click means most visitors never see it. |
| Tab state, no router | react-router | Two views. A router would also force an SPA-fallback controller on the Spring side if the built frontend is ever served from the jar. |
| Hand-rolled SVG charts | Recharts / Chart.js | Two small line charts do not justify a dependency in a project whose value is the backend. |
| Break-even line drawn at 1x | plot speedups alone | It is the reference the fuzzy chart is read against: without it, "0.9x" does not visibly mean "the naive scan won". |
| Flagged points drawn hollow and dashed | plot all points identically | One absurd point presented as fact discredits every honest point beside it. |
| Render gated on `result.mode`, not the checkbox | gate on `compareMode` | Toggling re-renders before the refetch lands, so for one frame the control and the data disagree. Reading the response's own shape is the only version that cannot desync. |
| Error boundary around each view | let React unmount on throw | A single undefined field white-screened the whole app during testing. Failing visibly and locally beats failing invisibly and globally. |
| `AbortController` per keystroke | debounce only | Debouncing alone still lets a slow earlier response land after a fast later one and paint stale results. |
| Vite proxy for `/api` in dev | direct cross-origin calls | Keeps the frontend free of environment-specific URLs. Caveat: dev is then same-origin and does *not* exercise CORS, so a split-origin deployment must be verified separately. |
| JIT warmup before publishing beans | `ApplicationRunner` | The servlet container starts during context refresh, so a runner could race the first request. Warming inside the `@Bean` method guarantees it finishes first. |
