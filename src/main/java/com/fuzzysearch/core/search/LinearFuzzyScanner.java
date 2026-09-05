package com.fuzzysearch.core.search;

import com.fuzzysearch.core.distance.LevenshteinDistance;
import com.fuzzysearch.core.index.WordEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Brute-force fuzzy candidate generation: compute an edit distance against every word.
 *
 * <p>Extracted so both engines can use it. It is the whole fuzzy story for
 * {@link NaiveSearchService}, and it is what {@link OptimizedSearchService} falls back to at edit
 * distance 2, where measurement showed the BK-tree loses to it (see {@link SearchPolicy}).
 *
 * <p>Time is O(N·m·k). That looks worse than a pruned tree search and frequently is not, because
 * of two things the shape of the loop buys:
 *
 * <ul>
 *   <li>{@link LevenshteinDistance#distanceWithCutoff} rejects most of the corpus in O(1) on the
 *       length filter alone, before any dynamic programming runs;</li>
 *   <li>it is a sequential walk over one array — cache-friendly and branch-predictable — whereas
 *       tree traversal is pointer-chasing through hash maps. On constrained hardware that
 *       difference is worth more than the asymptotics suggest.</li>
 * </ul>
 */
final class LinearFuzzyScanner {

    private final List<WordEntry> corpus;

    LinearFuzzyScanner(List<WordEntry> corpus) {
        this.corpus = corpus;
    }

    /**
     * Every word within {@code budget} edits of the key, excluding prefix matches.
     *
     * <p>Prefix matches are skipped because they are a stronger signal handled elsewhere; labelling
     * one {@code FUZZY} here would contradict that and break the two engines' agreement.
     */
    List<RawHit> scan(String key, int budget) {
        final List<RawHit> hits = new ArrayList<>();
        for (WordEntry entry : corpus) {                       // <-- every word, every query
            if (entry.normalized().startsWith(key)) {
                continue;
            }
            int distance = LevenshteinDistance.distanceWithCutoff(key, entry.normalized(), budget);
            if (distance <= budget) {
                hits.add(new RawHit(entry.word(), entry.normalized(), entry.weight(),
                        MatchType.FUZZY, distance));
            }
        }
        return hits;
    }
}
