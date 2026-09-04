package com.fuzzysearch.core.search;

/**
 * An unscored candidate produced by either engine, before merging and ranking.
 *
 * <p>Package-private: it exists only to give {@link ResultMerger} a uniform input regardless of
 * whether the candidate came from a linear scan or from a trie/BK-tree.
 *
 * @param word         display spelling
 * @param normalized   index key, used for de-duplication
 * @param weight       corpus frequency
 * @param matchType    how it was found
 * @param editDistance edits from the query (0 for prefix matches)
 */
record RawHit(String word, String normalized, long weight, MatchType matchType, int editDistance) {
}
