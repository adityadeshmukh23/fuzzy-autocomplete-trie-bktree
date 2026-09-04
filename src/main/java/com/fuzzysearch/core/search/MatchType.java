package com.fuzzysearch.core.search;

/** How a result was found -- surfaced to the UI so the user can see why a suggestion appeared. */
public enum MatchType {

    /** The query is a literal prefix of the word. */
    PREFIX,

    /** The word is within the allowed edit distance of the query. */
    FUZZY
}
