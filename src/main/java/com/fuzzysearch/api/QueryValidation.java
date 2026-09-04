package com.fuzzysearch.api;

/**
 * Request validation rules, in one place so the two search endpoints and the compare endpoint
 * cannot drift apart.
 */
final class QueryValidation {

    /**
     * Maximum query length.
     *
     * <p>This is a robustness limit, not a cosmetic one. Levenshtein distance is O(m·n) per word,
     * so the cost of the naive scan -- which is reachable at {@code /api/search/naive} -- scales
     * with query length across the whole corpus. A 10,000-character query would turn one HTTP
     * request into billions of cell computations. 64 characters is far beyond anything a person
     * types into an autocomplete box.
     */
    static final int MAX_QUERY_LENGTH = 64;

    static final int DEFAULT_LIMIT = 10;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 50;

    private QueryValidation() {
    }

    /**
     * @return the query, unchanged. Normalisation (trimming, case folding) belongs to the core's
     *         {@code TextNormalizer}, not here -- one policy, one place.
     */
    static String requireValidQuery(String query) {
        if (query == null) {
            throw new InvalidQueryException("Missing required parameter 'q'.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidQueryException("Query is too long: " + query.length()
                    + " characters, maximum is " + MAX_QUERY_LENGTH + ".");
        }
        return query;
    }

    static int requireValidLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new InvalidQueryException("Parameter 'limit' must be between " + MIN_LIMIT
                    + " and " + MAX_LIMIT + ", got " + limit + ".");
        }
        return limit;
    }

    /**
     * A blank query returns nothing rather than the globally most popular words.
     *
     * <p>The engine happily answers an empty prefix with the corpus's top terms, and that costs
     * almost nothing -- but "the, of, and" is not a useful suggestion list for an empty search
     * box, and returning it would make the UI look broken on first paint.
     */
    static boolean isBlank(String query) {
        return query.isBlank();
    }
}
