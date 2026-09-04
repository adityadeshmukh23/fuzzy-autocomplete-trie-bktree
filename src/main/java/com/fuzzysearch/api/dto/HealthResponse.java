package com.fuzzysearch.api.dto;

/**
 * Health and index metadata.
 *
 * @param status                always {@code UP} if the process is serving; the index is built
 *                              eagerly at startup, so a reachable process necessarily has a
 *                              usable index
 * @param corpusSize            indexed terms
 * @param optimizedBuildMillis  time to build the trie and BK-tree
 * @param naiveBuildMillis      time to build the flat list
 * @param indexStats            trie node count and BK-tree shape
 */
public record HealthResponse(String status, int corpusSize, long optimizedBuildMillis,
                             long naiveBuildMillis, String indexStats) {
}
