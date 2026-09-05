package com.fuzzysearch.core.search;

import com.fuzzysearch.core.distance.LevenshteinDistance;
import com.fuzzysearch.core.index.WordEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Progressive relaxation: search at distance 1 first, widen only if the page is still short.
 *
 * <p>The worked example throughout is the misspelling "recieve", which is 1 edit from "relieve"
 * and 2 edits from the word the user obviously meant, "receive". It is the cleanest illustration
 * of what relaxation buys and what it costs.
 */
class SearchPolicyTest {

    /** "relieve" is one substitution away; "receive" is a transposition, so two. */
    private static final List<WordEntry> CORPUS = List.of(
            WordEntry.of("relieve", 500),
            WordEntry.of("receive", 10_000_000),
            WordEntry.of("banana", 1_000),
            WordEntry.of("zebra", 1_000),
            WordEntry.of("mango", 1_000));

    private static List<String> words(List<SearchResult> results) {
        return results.stream().map(SearchResult::word).toList();
    }

    @Test
    @DisplayName("the premise: relieve is 1 edit away, receive is 2")
    void distancesArePreciselyWhatTheTestAssumes() {
        assertThat(LevenshteinDistance.distance("recieve", "relieve")).isEqualTo(1);
        assertThat(LevenshteinDistance.distance("recieve", "receive")).isEqualTo(2);
        assertThat(FuzzyBudget.forQuery("recieve")).isEqualTo(2);
    }

    @Test
    @DisplayName("a page filled at distance 1 never widens, so distance-2 hits stay hidden")
    void doesNotWidenWhenTheFirstPassFillsThePage() {
        for (SearchService engine : engines()) {
            assertThat(words(engine.search("recieve", 1)))
                    .as("%s", engine.name())
                    .containsExactly("relieve");
        }
    }

    @Test
    @DisplayName("a short page widens to distance 2 and finds the word the user meant")
    void widensWhenTheFirstPassComesUpShort() {
        for (SearchService engine : engines()) {
            List<SearchResult> results = engine.search("recieve", 5);

            assertThat(words(results)).as("%s", engine.name())
                    .containsExactlyInAnyOrder("relieve", "receive");
            // Once both are in play, the score bands overlap and the far more popular
            // distance-2 word wins -- which is the right answer here.
            assertThat(results.get(0).word()).isEqualTo("receive");
            assertThat(results.get(0).editDistance()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("relaxation is the documented trade-off, not an accident")
    void narrowPageHidesAMorePopularDistantMatch() {
        // Stated plainly so nobody later "fixes" this: at limit 1 the engine returns the
        // distance-1 match even though the distance-2 match would have outranked it had both
        // been considered. Preferring closer matches is the intended semantics.
        SearchService engine = new OptimizedSearchService(CORPUS);

        assertThat(words(engine.search("recieve", 1))).containsExactly("relieve");
        assertThat(words(engine.search("recieve", 2))).contains("receive");
    }

    @Test
    @DisplayName("both engines relax identically")
    void bothEnginesRelaxIdentically() {
        NaiveSearchService naive = new NaiveSearchService(CORPUS);
        OptimizedSearchService optimized = new OptimizedSearchService(CORPUS);

        for (int limit = 1; limit <= 6; limit++) {
            assertThat(optimized.search("recieve", limit))
                    .as("limit %d", limit)
                    .isEqualTo(naive.search("recieve", limit));
        }
    }

    @Test
    @DisplayName("prefix matches still fill the page ahead of any fuzzy candidate")
    void prefixResultsStillDominate() {
        List<WordEntry> corpus = List.of(
                WordEntry.of("receipt", 100),
                WordEntry.of("receiptless", 90),
                WordEntry.of("receipted", 80),
                WordEntry.of("receive", 10_000_000));

        for (SearchService engine : engines(corpus)) {
            assertThat(engine.search("receipt", 3))
                    .as("%s", engine.name())
                    .allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.PREFIX));
        }
    }

    @Test
    @DisplayName("a query too short for any fuzzy budget skips fuzzy search entirely")
    void shortQueriesNeverRelax() {
        for (SearchService engine : engines()) {
            assertThat(engine.search("ba", 10))
                    .as("%s", engine.name())
                    .allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.PREFIX));
        }
    }

    // -------------------------------------------------------------------------------------
    // The prefix short-circuit
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("fuzzy candidates are never generated when prefix matches fill the page")
    void skipsFuzzySearchEntirelyWhenPrefixFillsThePage() {
        // Driving SearchPolicy directly is the only way to prove the fuzzy work did not happen.
        // Asserting on results alone cannot distinguish "skipped it" from "computed it and then
        // ranked it below everything", and the whole point of the short-circuit is the former.
        AtomicInteger fuzzyGenerations = new AtomicInteger();
        List<RawHit> prefixHits = prefixHits("alpha", "alpine", "album", "alter", "always");

        List<SearchResult> results = SearchPolicy.progressiveSearch(prefixHits, 5, 2,
                budget -> {
                    fuzzyGenerations.incrementAndGet();
                    return List.of();
                },
                new RelevanceScorer(1_000));

        assertThat(fuzzyGenerations).hasValue(0);
        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("fuzzy search still runs when prefix matches leave the page short")
    void stillSearchesFuzzyWhenPrefixComesUpShort() {
        AtomicInteger fuzzyGenerations = new AtomicInteger();
        List<RawHit> prefixHits = prefixHits("alpha", "alpine");

        SearchPolicy.progressiveSearch(prefixHits, 5, 2,
                budget -> {
                    fuzzyGenerations.incrementAndGet();
                    return List.of();
                },
                new RelevanceScorer(1_000));

        // Once at distance 1, then again at distance 2 because nothing was found.
        assertThat(fuzzyGenerations).hasValue(2);
    }

    @Test
    @DisplayName("short-circuiting cannot change results: a full prefix page IS the whole answer")
    void shortCircuitIsLossless() {
        // The claim being pinned: when prefix matches fill the page, search() and prefixSearch()
        // must agree exactly, because no fuzzy candidate could have outranked a prefix one.
        List<WordEntry> corpus = List.of(
                WordEntry.of("search", 10_000_000),
                WordEntry.of("searched", 5_000_000),
                WordEntry.of("searching", 4_000_000),
                WordEntry.of("searches", 3_000_000),
                WordEntry.of("searchable", 100),
                WordEntry.of("seatch", 900_000_000),   // 1 edit away and hugely popular
                WordEntry.of("scarch", 800_000_000));  // ditto

        for (SearchService engine : engines(corpus)) {
            assertThat(engine.search("search", 3))
                    .as("%s", engine.name())
                    .isEqualTo(engine.prefixSearch("search", 3));
        }
    }

    // -------------------------------------------------------------------------------------
    // Routing between the two fuzzy implementations
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the BK-tree is used at distance 1 and the linear scan from distance 2 up")
    void routesFuzzyWorkToWhicheverIsFaster() {
        // Pinning the measured boundary. The BK-tree beats a brute-force scan by 3.5x at edit
        // distance 1 and loses at 2 (0.93x locally, 3-10x worse on throttled deployment hardware),
        // because it needs exact distances and so cannot use the banded cutoff the scan enjoys.
        assertThat(SearchPolicy.shouldUseBkTree(0)).isTrue();
        assertThat(SearchPolicy.shouldUseBkTree(1)).isTrue();
        assertThat(SearchPolicy.shouldUseBkTree(2)).isFalse();
        assertThat(SearchPolicy.shouldUseBkTree(3)).isFalse();
    }

    @Test
    @DisplayName("routing changes latency, never results")
    void routingIsResultPreserving() {
        // The engine switches implementation mid-query when relaxation escalates from distance 1
        // to distance 2. If the two disagreed about candidates, that switch would silently change
        // what a user sees. It cannot: the BK-tree returns exactly what a brute-force scan
        // returns, which BKTreeTest.pruningIsLossless establishes independently.
        //
        // "recieve" is the case that actually crosses the boundary: nothing at distance 1 fills
        // the page, so relaxation widens to 2 and the scan takes over.
        NaiveSearchService naive = new NaiveSearchService(CORPUS);
        OptimizedSearchService optimized = new OptimizedSearchService(CORPUS);

        for (String query : List.of("recieve", "relieve", "receive", "banana")) {
            for (int limit = 1; limit <= 6; limit++) {
                assertThat(optimized.search(query, limit))
                        .as("query '%s', limit %d", query, limit)
                        .isEqualTo(naive.search(query, limit));
            }
        }
    }

    @Test
    @DisplayName("the isolated fuzzySearch probe still uses the BK-tree at every distance")
    void fuzzySearchProbeDoesNotRoute() {
        // fuzzySearch is what the benchmark uses to measure the BK-tree on its own. If it routed
        // like search() does, the distance-2 fuzzy benchmark would be measuring the linear scan
        // against itself and would report a meaningless 1.00x.
        OptimizedSearchService optimized = new OptimizedSearchService(CORPUS);
        NaiveSearchService naive = new NaiveSearchService(CORPUS);

        for (int distance = 1; distance <= 3; distance++) {
            assertThat(optimized.fuzzySearch("recieve", 10, distance))
                    .as("distance %d", distance)
                    .isEqualTo(naive.fuzzySearch("recieve", 10, distance));
        }
    }

    private static List<RawHit> prefixHits(String... words) {
        List<RawHit> hits = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            hits.add(new RawHit(words[i], words[i], 100L - i, MatchType.PREFIX, 0));
        }
        return hits;
    }

    private static List<SearchService> engines() {
        return engines(CORPUS);
    }

    private static List<SearchService> engines(List<WordEntry> corpus) {
        return List.of(new NaiveSearchService(corpus), new OptimizedSearchService(corpus));
    }
}
