package com.fuzzysearch.core.search;

import java.util.List;

/**
 * The contract both the naive and the optimised engines implement.
 *
 * <p>Having one interface with two implementations is the point of the whole project: the
 * benchmark swaps them, the API exposes both, and the equivalence test asserts they return
 * <em>identical</em> results, which is what licenses claiming the optimised one is a pure speed
 * win rather than a different (possibly worse) answer.
 *
 * <p>{@link #prefixSearch} and {@link #fuzzySearch} are exposed separately from {@link #search}
 * so Phase 4 can benchmark the trie and the BK-tree independently instead of only measuring
 * their combination.
 */
public interface SearchService {

    /** Human-readable name, used in benchmark output and API responses. */
    String name();

    /** Number of indexed words. */
    int size();

    /** Milliseconds spent building the index at construction time. */
    long buildTimeMillis();

    /**
     * Prefix matches only, ranked.
     *
     * <p>An empty query matches everything, so it returns the most popular words overall.
     */
    List<SearchResult> prefixSearch(String query, int limit);

    /** Fuzzy matches only, within an explicit edit budget, ranked. */
    List<SearchResult> fuzzySearch(String query, int limit, int maxEditDistance);

    /**
     * The real search: prefix and fuzzy candidates merged, deduplicated and ranked together.
     * The fuzzy budget comes from {@link FuzzyBudget#forQuery}.
     */
    List<SearchResult> search(String query, int limit);
}
