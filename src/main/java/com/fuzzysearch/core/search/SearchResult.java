package com.fuzzysearch.core.search;

import java.util.Comparator;
import java.util.Objects;

/**
 * One ranked result.
 *
 * @param word         the display spelling
 * @param score        composite relevance, see {@link RelevanceScorer}
 * @param matchType    how it was found
 * @param editDistance edits between query and word. Always 0 for {@link MatchType#PREFIX} --
 *                     a literal prefix needs no edits to match -- and the real distance for
 *                     {@link MatchType#FUZZY}.
 * @param weight       the raw corpus frequency behind the score, exposed for debugging and for
 *                     the frontend to show why something ranked where it did
 */
public record SearchResult(String word, double score, MatchType matchType, int editDistance,
                           long weight) {

    /**
     * Same three tiers as {@link com.fuzzysearch.core.rank.Candidate#BETTER_FIRST}: score, then
     * shorter word, then lexicographic. Match type is deliberately <em>not</em> a tier here --
     * it is already baked into the score, and having it in both places would mean two competing
     * definitions of "better".
     */
    public static final Comparator<SearchResult> BETTER_FIRST =
            Comparator.comparingDouble((SearchResult r) -> r.score()).reversed()
                    .thenComparingInt((SearchResult r) -> r.word().length())
                    .thenComparing((SearchResult r) -> r.word());

    public SearchResult {
        Objects.requireNonNull(word, "word must not be null");
        Objects.requireNonNull(matchType, "matchType must not be null");
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score must not be NaN");
        }
    }
}
