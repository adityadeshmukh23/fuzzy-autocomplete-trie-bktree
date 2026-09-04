# Benchmark Results

Generated 2026-09-04 by `BenchmarkSuite`. Measured with JMH: 1 fork, 3 warmup iterations, 5 measurement iterations, average time per query, 10 results requested.

`±` is JMH's 99.9% confidence half-width. Each benchmark rotates through 16 queries so no single lucky prefix dominates the average.

![scaling](benchmark-scaling.svg)

> ⚠️ Rows marked ⚠️ had a confidence interval wider than 25% of the measured value, usually because the machine was busy. Treat them as indicative only and re-run on an idle machine.

## Prefix search — linear scan vs. trie

### Query length 1

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 198 µs ±881 | 5,042 | 588 µs ±2,700 | 1,702 | 0.34× ⚠️ |
| 10,000 | 87.26 µs ±0.76 | 11,460 | 7.50 µs ±1.54 | 133,258 | **12×** |
| 50,000 | 432 µs ±1.94 | 2,315 | 9.34 µs ±0.26 | 107,030 | **46×** |
| 100,000 | 877 µs ±120 | 1,141 | 13.63 µs ±3.74 | 73,343 | 64× ⚠️ |

### Query length 3

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 6.25 µs ±2.16 | 159,909 | 0.92 µs ±0.23 | 1,082,999 | 6.77× ⚠️ |
| 10,000 | 54.15 µs ±1.22 | 18,468 | 3.84 µs ±2.55 | 260,740 | 14× ⚠️ |
| 50,000 | 280 µs ±2.31 | 3,575 | 3.66 µs ±0.02 | 273,454 | **77×** |
| 100,000 | 805 µs ±583 | 1,242 | 5.73 µs ±0.93 | 174,610 | 141× ⚠️ |

### Query length 6

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 4.96 µs ±4.08 | 201,480 | 0.38 µs ±0.00 | 2,653,331 | 13× ⚠️ |
| 10,000 | 1,598 µs ±5,760 | 626 | 0.82 µs ±0.04 | 1,215,468 | 1,942× ⚠️ |
| 50,000 | 565 µs ±5.20 | 1,769 | 1.80 µs ±0.01 | 554,650 | **314×** |
| 100,000 | 1,109 µs ±23.30 | 901 | 2.54 µs ±0.03 | 393,346 | **436×** |

## Fuzzy search — linear Levenshtein scan vs. BK-tree

### Max edit distance 1

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 49.42 µs ±15.80 | 20,236 | 21.37 µs ±0.84 | 46,800 | 2.31× ⚠️ |
| 10,000 | 328 µs ±10.36 | 3,053 | 172 µs ±7.76 | 5,807 | **1.90×** |
| 50,000 | 3,763 µs ±3,184 | 266 | 497 µs ±182 | 2,010 | 7.56× ⚠️ |
| 100,000 | 3,248 µs ±20.90 | 308 | 931 µs ±25.46 | 1,074 | **3.49×** |

### Max edit distance 2

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 82.92 µs ±15.29 | 12,060 | 82.77 µs ±3.72 | 12,082 | **1.00×** |
| 10,000 | 671 µs ±95.01 | 1,491 | 885 µs ±12.68 | 1,130 | **0.76×** |
| 50,000 | 7,228 µs ±6,499 | 138 | 2,575 µs ±9.68 | 388 | 2.81× ⚠️ |
| 100,000 | 6,403 µs ±121 | 156 | 7,098 µs ±1,085 | 141 | **0.90×** |

## End-to-end `search()` — prefix + fuzzy, with progressive relaxation

### Complete misspelled word — worst case, few prefix matches to short-circuit on

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 129 µs ±11.77 | 7,776 | 106 µs ±2.26 | 9,408 | **1.21×** |
| 10,000 | 1,205 µs ±12.61 | 830 | 1,051 µs ±14.29 | 951 | **1.15×** |
| 50,000 | 5,386 µs ±127 | 186 | 2,638 µs ±90.18 | 379 | **2.04×** |
| 100,000 | 10,005 µs ±98.97 | 100 | 6,810 µs ±1,934 | 147 | 1.47× ⚠️ |

### Partial word, 4 characters — a typical keystroke, prefix short-circuit fires

| dataset | naive | naive qps | optimized | optimized qps | speedup |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 55.30 µs ±28.00 | 18,085 | 28.05 µs ±8.14 | 35,645 | 1.97× ⚠️ |
| 10,000 | 373 µs ±3.31 | 2,681 | 171 µs ±2.87 | 5,845 | **2.18×** |
| 50,000 | 981 µs ±3.82 | 1,019 | 199 µs ±19.86 | 5,027 | **4.93×** |
| 100,000 | 1,563 µs ±30.61 | 640 | 7,497 µs ±26,411 | 133 | 0.21× ⚠️ |

