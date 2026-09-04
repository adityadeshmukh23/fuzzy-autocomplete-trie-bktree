package com.fuzzysearch.core.search;

import com.fuzzysearch.core.index.WordEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the baseline on its own terms. The equivalence test proves the two engines agree; this
 * proves the baseline is actually right, so "they agree" means "both are correct" rather than
 * "both are wrong in the same way".
 */
class NaiveSearchServiceTest {

    private static final List<WordEntry> CORPUS = List.of(
            WordEntry.of("apple", 5_000),
            WordEntry.of("apply", 3_000),
            WordEntry.of("application", 8_000),
            WordEntry.of("ape", 400),
            WordEntry.of("banana", 900),
            WordEntry.of("band", 2_000),
            WordEntry.of("orange", 100));

    private static NaiveSearchService service() {
        return new NaiveSearchService(CORPUS);
    }

    private static List<String> words(List<SearchResult> results) {
        return results.stream().map(SearchResult::word).toList();
    }

    // -------------------------------------------------------------------------------------
    // Prefix search
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("no prefix matches -> empty")
    void prefixZeroMatches() {
        assertThat(service().prefixSearch("zebra", 10)).isEmpty();
    }

    @Test
    @DisplayName("one prefix match")
    void prefixOneMatch() {
        assertThat(words(service().prefixSearch("bana", 10))).containsExactly("banana");
    }

    @Test
    @DisplayName("many prefix matches, ordered by popularity")
    void prefixManyMatches() {
        assertThat(words(service().prefixSearch("app", 10)))
                .containsExactly("application", "apple", "apply");
    }

    @Test
    @DisplayName("prefix results are tagged PREFIX with zero edit distance")
    void prefixResultsAreTagged() {
        assertThat(service().prefixSearch("app", 10))
                .allSatisfy(result -> {
                    assertThat(result.matchType()).isEqualTo(MatchType.PREFIX);
                    assertThat(result.editDistance()).isZero();
                });
    }

    @Test
    @DisplayName("limit truncates to the best results")
    void prefixRespectsLimit() {
        assertThat(words(service().prefixSearch("app", 2)))
                .containsExactly("application", "apple");
    }

    @Test
    @DisplayName("an empty query returns the most popular words overall")
    void emptyQueryReturnsGlobalTopK() {
        assertThat(words(service().prefixSearch("", 3)))
                .containsExactly("application", "apple", "apply");
    }

    // -------------------------------------------------------------------------------------
    // Fuzzy search
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a one-character typo is corrected")
    void fuzzyFindsTypo() {
        assertThat(words(service().fuzzySearch("aple", 10, 1))).contains("apple", "ape");
    }

    @Test
    @DisplayName("fuzzy results carry their real edit distance and are tagged FUZZY")
    void fuzzyResultsAreTagged() {
        List<SearchResult> results = service().fuzzySearch("aple", 10, 1);

        assertThat(results).allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.FUZZY));
        assertThat(results)
                .filteredOn(r -> r.word().equals("apple"))
                .singleElement()
                .extracting(SearchResult::editDistance)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("closer matches outrank more distant ones regardless of popularity")
    void closerMatchesOutrankMoreDistantOnes() {
        // "band" is 1 edit from "bend"; "banana" is far. Even though both are in the corpus,
        // the distance-1 hit must come first.
        List<SearchResult> results = service().fuzzySearch("bend", 10, 2);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).word()).isEqualTo("band");
        assertThat(results).isSortedAccordingTo(SearchResult.BETTER_FIRST);
    }

    @Test
    @DisplayName("a budget of 0 is an exact-match search")
    void fuzzyZeroBudget() {
        assertThat(words(service().fuzzySearch("apple", 10, 0))).containsExactly("apple");
        assertThat(service().fuzzySearch("aple", 10, 0)).isEmpty();
    }

    @Test
    void fuzzyRejectsNegativeBudget() {
        assertThatThrownBy(() -> service().fuzzySearch("apple", 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------------------
    // Combined search
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("combined search returns prefix matches ahead of fuzzy ones")
    void combinedSearchTiersPrefixAboveFuzzy() {
        List<SearchResult> results = service().search("appl", 10);

        assertThat(results).isNotEmpty();
        int firstFuzzy = -1;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).matchType() == MatchType.FUZZY) {
                firstFuzzy = i;
                break;
            }
        }
        if (firstFuzzy >= 0) {
            assertThat(results.subList(0, firstFuzzy))
                    .allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.PREFIX));
            assertThat(results.subList(firstFuzzy, results.size()))
                    .allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.FUZZY));
        }
    }

    @Test
    @DisplayName("the motivating example: 'aple' finds 'apple'")
    void motivatingExample() {
        assertThat(words(service().search("aple", 5))).contains("apple");
    }

    @Test
    @DisplayName("a word appears at most once even when reachable both ways")
    void noDuplicatesAcrossMatchTypes() {
        List<String> words = words(service().search("app", 10));

        assertThat(words).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a two-character query does no fuzzy matching, by policy")
    void shortQueriesSkipFuzzyMatching() {
        assertThat(service().search("ap", 10))
                .allSatisfy(r -> assertThat(r.matchType()).isEqualTo(MatchType.PREFIX));
    }

    @Test
    @DisplayName("results are always sorted by the shared ranking order")
    void resultsAreSorted() {
        assertThat(service().search("appl", 10)).isSortedAccordingTo(SearchResult.BETTER_FIRST);
        assertThat(service().prefixSearch("a", 10)).isSortedAccordingTo(SearchResult.BETTER_FIRST);
        assertThat(service().fuzzySearch("aple", 10, 2))
                .isSortedAccordingTo(SearchResult.BETTER_FIRST);
    }

    @Test
    @DisplayName("service metadata is exposed for the API and benchmark")
    void metadata() {
        NaiveSearchService service = service();

        assertThat(service.name()).isEqualTo("naive");
        assertThat(service.size()).isEqualTo(7);
        assertThat(service.buildTimeMillis()).isGreaterThanOrEqualTo(0L);
    }
}
