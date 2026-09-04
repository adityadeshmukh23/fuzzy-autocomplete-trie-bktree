package com.fuzzysearch.api.dto;

import com.fuzzysearch.core.search.SearchResult;

import java.util.List;

/**
 * A search response.
 *
 * @param query        the query as received
 * @param engine       {@code optimized} or {@code naive}
 * @param limit        results requested
 * @param resultCount  results returned, which may be fewer
 * @param latencyMicros server-side time for the search call alone, excluding HTTP and JSON
 *                      serialisation
 * @param results      ranked best-first
 */
public record SearchResponse(String query, String engine, int limit, int resultCount,
                             double latencyMicros, List<SearchResultDto> results) {

    public static SearchResponse of(String query, String engine, int limit,
                                    List<SearchResult> results, double latencyMicros) {
        return new SearchResponse(query, engine, limit, results.size(), latencyMicros,
                results.stream().map(SearchResultDto::from).toList());
    }
}
