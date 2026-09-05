package com.fuzzysearch.core.search;

import com.fuzzysearch.core.distance.LevenshteinDistance;
import com.fuzzysearch.core.index.Corpus;
import com.fuzzysearch.core.index.WordEntry;
import com.fuzzysearch.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * The obvious implementation: keep every word in a list and look at all of them, every time.
 *
 * <p><b>This is not throwaway code.</b> It ships permanently, it is reachable from the API, and
 * it is the control group for every number in the README. A benchmark against a straw man proves
 * nothing, so this baseline is written to be as fast as a linear scan can honestly be:
 *
 * <ul>
 *   <li>index keys are precomputed in {@link WordEntry}, so no per-query lower-casing;</li>
 *   <li>fuzzy matching uses {@link LevenshteinDistance#distanceWithCutoff}, which the BK-tree
 *       structurally cannot use -- it needs exact distances to compute its pruning windows. The
 *       baseline gets an optimisation the "optimised" path is denied;</li>
 *   <li>ranking is the same shared code both services use, so no ranking overhead is smuggled
 *       into the comparison.</li>
 * </ul>
 *
 * <p>What is left is the actual algorithmic difference: O(N·L) prefix scanning versus an O(L)
 * trie descent, and O(N·m·k) distance computation versus BK-tree pruning. That is the comparison
 * worth making.
 */
public final class NaiveSearchService implements SearchService {

    private final List<WordEntry> corpus;
    private final LinearFuzzyScanner scanner;
    private final RelevanceScorer scorer;
    private final long buildTimeMillis;

    public NaiveSearchService(List<WordEntry> entries) {
        long start = System.nanoTime();
        // Defensive: both services must see the same de-duplicated view of the corpus,
        // otherwise the trie's automatic key merging alone would break their equivalence.
        this.corpus = List.copyOf(Corpus.deduplicate(entries));
        long maxWeight = 0L;
        for (WordEntry entry : this.corpus) {
            maxWeight = Math.max(maxWeight, entry.weight());
        }
        this.scanner = new LinearFuzzyScanner(this.corpus);
        this.scorer = new RelevanceScorer(maxWeight);
        this.buildTimeMillis = (System.nanoTime() - start) / 1_000_000L;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    public int size() {
        return corpus.size();
    }

    @Override
    public long buildTimeMillis() {
        return buildTimeMillis;
    }

    /**
     * Brute-force prefix search: look at every word, keep the ones that start with the query.
     *
     * <p>Time O(N·L). The cost is paid in full on every keystroke and grows linearly with the
     * corpus -- there is no structure to exploit, because a flat list has none.
     */
    @Override
    public List<SearchResult> prefixSearch(String query, int limit) {
        final String key = TextNormalizer.normalize(query);
        final List<RawHit> hits = new ArrayList<>();

        for (WordEntry entry : corpus) {                       // <-- every word, every query
            if (entry.normalized().startsWith(key)) {
                hits.add(new RawHit(entry.word(), entry.normalized(), entry.weight(),
                        MatchType.PREFIX, 0));
            }
        }
        return ResultMerger.rank(hits, limit, scorer);
    }

    /**
     * Brute-force fuzzy search: compute an edit distance against every word in the corpus.
     *
     * <p>Time O(N·m·k) with the banded cutoff (O(N·m·n) without it). This is the operation the
     * BK-tree exists to avoid, and the one where the gap should be widest.
     */
    @Override
    public List<SearchResult> fuzzySearch(String query, int limit, int maxEditDistance) {
        if (maxEditDistance < 0) {
            throw new IllegalArgumentException("maxEditDistance must be >= 0");
        }
        final String key = TextNormalizer.normalize(query);
        final List<RawHit> hits = new ArrayList<>();

        for (WordEntry entry : corpus) {                       // <-- every word, every query
            int distance = LevenshteinDistance.distanceWithCutoff(key, entry.normalized(),
                    maxEditDistance);
            if (distance <= maxEditDistance) {
                hits.add(new RawHit(entry.word(), entry.normalized(), entry.weight(),
                        MatchType.FUZZY, distance));
            }
        }
        return ResultMerger.rank(hits, limit, scorer);
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        final String key = TextNormalizer.normalize(query);

        final List<RawHit> prefixHits = new ArrayList<>();
        for (WordEntry entry : corpus) {
            if (entry.normalized().startsWith(key)) {
                prefixHits.add(new RawHit(entry.word(), entry.normalized(), entry.weight(),
                        MatchType.PREFIX, 0));
            }
        }

        return SearchPolicy.progressiveSearch(prefixHits, limit, FuzzyBudget.forQuery(key),
                budget -> scanner.scan(key, budget), scorer);
    }


}
