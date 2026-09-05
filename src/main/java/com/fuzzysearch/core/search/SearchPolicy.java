package com.fuzzysearch.core.search;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Decides how hard to look. Shared by both engines so their behaviour cannot drift apart.
 *
 * <h2>Progressive relaxation</h2>
 * Rather than always searching at the maximum edit budget, search at distance 1 first and widen
 * only if that did not produce enough results.
 *
 * <p><b>Why: performance.</b> Phase 2 measured the BK-tree at 1.8x-5.7x faster than a linear scan
 * at distance 1, but 0.29x-0.94x -- i.e. <em>slower</em> -- at distance 2, because it must
 * compute an exact O(m*n) distance at every visited node while the linear scan gets to reject
 * most of the corpus in O(1) with a length filter. Relaxation keeps the engine in the regime
 * where the BK-tree actually pays, and the expensive case only happens when the cheap one failed.
 *
 * <p><b>Why: result quality.</b> This is not only an optimisation, and it does change results.
 * {@link RelevanceScorer}'s score bands guarantee that prefix matches outrank fuzzy ones, but
 * they deliberately do <em>not</em> guarantee that a distance-1 match outranks a distance-2 one
 * -- the bands overlap, so a far more popular distant match can win. Relaxation restores that
 * ordering in the common case: if distance 1 fills the page, distance-2 candidates are never
 * considered at all. Closer matches are preferred, and the wider net is only cast when the
 * narrow one came up short.
 *
 * <p>The trade-off, stated plainly: a query that fills its page at distance 1 will never surface
 * a hugely popular distance-2 correction. That is the intended semantics, not an accident.
 *
 * <h2>The prefix short-circuit</h2>
 * If prefix matching alone fills the page, fuzzy search is skipped entirely.
 *
 * <p><b>This is exact, not a heuristic.</b> {@link RelevanceScorer} guarantees that the prefix
 * score band [0.70, 1.00] never overlaps the best fuzzy band [0.35, 0.65], so every prefix match
 * outranks every fuzzy match. If {@code limit} prefix candidates exist, they are the top
 * {@code limit} of the union, and no fuzzy candidate could have displaced one of them. Skipping
 * the fuzzy search therefore cannot change a single result -- it only avoids computing candidates
 * that were guaranteed to lose.
 *
 * <p>Phase 4 measured why this matters: the end-to-end query on a 100,000-word corpus cost
 * 6,424 microseconds, because relaxation escalated to distance 2 on nearly every query. For a
 * user who is actually typing -- where prefix matches exist -- the short-circuit turns that into
 * a 4.25 microsecond trie descent.
 *
 * <p><b>The guarantee this depends on is load-bearing.</b> It is pinned by
 * {@code RelevanceScorerTest.prefixTierNeverOverlapsFuzzyTier}. If someone raises
 * {@code POPULARITY_WEIGHT} past 1/3, the bands overlap, and this short-circuit silently starts
 * dropping results that should have ranked. That test is the tripwire.
 */
final class SearchPolicy {

    /**
     * The widest edit distance at which the BK-tree is actually the faster choice.
     *
     * <p><b>Set from measurement, not intuition.</b> Benchmarked over 100,000 words, the BK-tree
     * beats a brute-force scan by 3.5x at edit distance 1 and *loses* at distance 2 (0.93x). On
     * the CPU-throttled deployment the gap is far worse — a full-word typo measured 21-83 ms
     * through the tree against 7-14 ms for the linear scan, 3-10x slower.
     *
     * <p>The cause is structural rather than tunable. BK-tree pruning needs the <em>exact</em>
     * distance at every visited node to compute its window of child edges, so it pays a full
     * O(m·n) dynamic-programming pass per node. The linear scan only needs a yes/no answer, so it
     * uses a banded cutoff whose length filter rejects most of the corpus in O(1). At distance 1
     * pruning overcomes that handicap; at distance 2 the tree visits roughly half its nodes and
     * the per-node cost dominates.
     *
     * <p>So the engine routes: tree where the tree wins, scan where the scan wins. Both
     * implementations return identical candidate sets — {@code BKTreeTest.pruningIsLossless}
     * proves the tree finds exactly what a brute-force scan finds — so this is purely a
     * performance decision and cannot change a single result.
     */
    static final int BK_TREE_BUDGET_CEILING = 1;

    private SearchPolicy() {
    }

    /**
     * Which fuzzy implementation to use at a given edit budget.
     *
     * <p>A pure function rather than a branch buried in the service, so the routing rule can be
     * tested directly and stays visible next to the reasoning that produced it.
     */
    static boolean shouldUseBkTree(int budget) {
        return budget <= BK_TREE_BUDGET_CEILING;
    }

    /**
     * Runs prefix search plus progressively widened fuzzy search.
     *
     * @param prefixHits candidates from the prefix path, already generated
     * @param limit      how many results the caller wants
     * @param maxBudget  the widest edit distance allowed, from {@link FuzzyBudget#forQuery}
     * @param fuzzyAt    generates fuzzy candidates at a given edit distance. Called at most
     *                   {@code maxBudget} times, and usually exactly once.
     */
    static List<SearchResult> progressiveSearch(List<RawHit> prefixHits, int limit, int maxBudget,
                                                IntFunction<List<RawHit>> fuzzyAt,
                                                RelevanceScorer scorer) {
        // Exact short-circuit: prefix matches strictly outrank every possible fuzzy match, so a
        // full page of them is already the final answer. See the class javadoc for the argument.
        //
        // Note this stays equivalent across both engines even though they arrive with different
        // sized candidate lists. The naive engine collects every prefix match (possibly hundreds);
        // the optimised engine asks the trie for at most `limit`. Whenever at least `limit` prefix
        // matches exist, both lists satisfy `size() >= limit` and both short-circuit; whenever
        // fewer exist, both hold exactly that many and neither does.
        if (prefixHits.size() >= limit) {
            return ResultMerger.merge(prefixHits, List.of(), limit, scorer);
        }

        if (maxBudget <= 0) {
            return ResultMerger.merge(prefixHits, List.of(), limit, scorer);
        }

        List<SearchResult> results = ResultMerger.merge(prefixHits, fuzzyAt.apply(1), limit, scorer);

        // Widen only while the page is still short. Each step redoes the merge from scratch,
        // which is cheap next to the search itself and keeps the result set unambiguous -- the
        // returned list is always exactly "the best `limit` at the budget we settled on".
        for (int budget = 2; budget <= maxBudget && results.size() < limit; budget++) {
            results = ResultMerger.merge(prefixHits, fuzzyAt.apply(budget), limit, scorer);
        }
        return results;
    }
}
