package com.fuzzysearch.core.search;

import com.fuzzysearch.core.rank.BoundedMinHeap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores, de-duplicates and top-K-selects candidates. Shared by both search services.
 *
 * <p><b>Why this is shared rather than duplicated.</b> The naive and optimised engines must
 * differ in exactly one dimension -- how candidates are <em>found</em> -- so that a measured
 * performance gap can only be attributed to the data structures. Everything downstream of
 * candidate generation is identical code, which is also what makes
 * {@code SearchServiceEquivalenceTest} able to assert byte-identical output.
 *
 * <p>It does mean {@code NaiveSearchService} is not 100% standalone code. The alternative --
 * duplicating scoring and merging into both -- would let the two drift apart and quietly turn
 * the benchmark into a comparison of two different products.
 */
final class ResultMerger {

    private ResultMerger() {
    }

    /**
     * Merges prefix and fuzzy candidates into a single ranked list.
     *
     * <p><b>Prefix wins ties.</b> A word can legitimately be found both ways: querying "app"
     * prefix-matches "apps" and also sits one edit away from it. It should appear once, tagged
     * with the stronger signal. Prefix always scores higher anyway (see {@link RelevanceScorer}),
     * so keeping the prefix version is both the honest label and the higher rank.
     *
     * <p>Selection is {@link BoundedMinHeap} rather than a sort: O(N log K) with O(K) memory
     * instead of O(N log N) with O(N).
     */
    static List<SearchResult> merge(List<RawHit> prefixHits, List<RawHit> fuzzyHits, int limit,
                                    RelevanceScorer scorer) {
        if (limit <= 0) {
            return List.of();
        }

        final Set<String> seen = new HashSet<>(prefixHits.size() * 2);
        final List<SearchResult> scored =
                new ArrayList<>(prefixHits.size() + fuzzyHits.size());

        // Prefix candidates first, so they claim their normalized key before fuzzy hits do.
        for (RawHit hit : prefixHits) {
            if (seen.add(hit.normalized())) {
                scored.add(toResult(hit, scorer));
            }
        }
        for (RawHit hit : fuzzyHits) {
            if (seen.add(hit.normalized())) {
                scored.add(toResult(hit, scorer));
            }
        }

        return BoundedMinHeap.topK(scored, limit, SearchResult.BETTER_FIRST);
    }

    /** Ranks a single source of candidates -- used by the prefix-only and fuzzy-only entry points. */
    static List<SearchResult> rank(List<RawHit> hits, int limit, RelevanceScorer scorer) {
        return merge(hits, List.of(), limit, scorer);
    }

    private static SearchResult toResult(RawHit hit, RelevanceScorer scorer) {
        double score = scorer.score(hit.matchType(), hit.editDistance(), hit.weight());
        return new SearchResult(hit.word(), score, hit.matchType(), hit.editDistance(),
                hit.weight());
    }
}
