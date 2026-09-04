package com.fuzzysearch.api.dto;

import com.fuzzysearch.core.search.SearchResult;

/**
 * One result, as the API renders it.
 *
 * <p>A separate type from {@link SearchResult} on purpose: the core record is free to change
 * shape as the algorithms evolve, while this is a published contract the frontend depends on.
 *
 * @param word         the suggestion
 * @param score        composite relevance in [0, 1]
 * @param matchType    {@code PREFIX} or {@code FUZZY} -- the frontend shows this so a user can
 *                     see why a suggestion appeared
 * @param editDistance edits from the query; always 0 for a prefix match
 * @param weight       raw corpus frequency, exposed so the ranking is inspectable rather than
 *                     magic
 */
public record SearchResultDto(String word, double score, String matchType, int editDistance,
                              long weight) {

    public static SearchResultDto from(SearchResult result) {
        return new SearchResultDto(result.word(), result.score(), result.matchType().name(),
                result.editDistance(), result.weight());
    }
}
