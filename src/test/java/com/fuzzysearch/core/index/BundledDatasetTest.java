package com.fuzzysearch.core.index;

import com.fuzzysearch.core.search.MatchType;
import com.fuzzysearch.core.search.NaiveSearchService;
import com.fuzzysearch.core.search.OptimizedSearchService;
import com.fuzzysearch.core.search.SearchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real bundled dataset rather than synthetic corpora.
 *
 * <p>Everything else in the suite runs on generated data, which is right for proving algorithmic
 * properties but says nothing about whether the shipped file parses, whether the weights survive
 * the trip, or whether the thing actually corrects real typos. That is what this covers.
 */
class BundledDatasetTest {

    private static DatasetLoader.LoadReport report;
    private static List<WordEntry> corpus;
    private static OptimizedSearchService optimized;

    @BeforeAll
    static void loadOnce() {
        report = BundledDataset.load();
        corpus = report.entries();
        optimized = new OptimizedSearchService(corpus);
    }

    private static List<String> words(List<SearchResult> results) {
        return results.stream().map(SearchResult::word).toList();
    }

    // -------------------------------------------------------------------------------------
    // The file itself
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the bundled dataset loads at the expected scale")
    void loadsAtScale() {
        assertThat(corpus).hasSizeGreaterThan(99_000).hasSizeLessThanOrEqualTo(100_000);
        assertThat(report.weightSource()).isEqualTo(DatasetLoader.WeightSource.COLUMN);
        assertThat(report.skipped()).isZero();
        System.out.println("[dataset] " + report.summary());
    }

    @Test
    @DisplayName("frequencies survive parsing, and the corpus is genuinely Zipfian")
    void frequenciesArePreserved() {
        WordEntry heaviest = corpus.get(0);

        assertThat(heaviest.word()).isEqualTo("the");
        assertThat(heaviest.weight()).isEqualTo(23_135_851_162L);
        // Four-plus orders of magnitude between the most and least common term is what makes the
        // log compression in RelevanceScorer necessary rather than decorative.
        assertThat(heaviest.weight() / corpus.get(corpus.size() - 1).weight())
                .isGreaterThan(100_000L);
    }

    @Test
    @DisplayName("weights are near-unique, which is what keeps trie ranking output-sensitive")
    void weightsAreWellDifferentiated() {
        // The property the dataset was truncated at 100k to obtain. If a future change swaps in
        // a flatter dataset, this fails and points at docs/complexity.md rather than showing up
        // as a mysterious latency regression.
        long distinct = corpus.stream().map(WordEntry::weight).distinct().count();

        assertThat((double) distinct / corpus.size()).isGreaterThan(0.90);
    }

    // -------------------------------------------------------------------------------------
    // Does it actually work on real words
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the motivating example: 'aple' finds 'apple'")
    void correctsTheMotivatingTypo() {
        assertThat(words(optimized.search("aple", 10))).contains("apple");
    }

    @Test
    @DisplayName("common real misspellings are corrected")
    void correctsRealMisspellings() {
        assertThat(words(optimized.search("recieve", 10))).contains("receive");
        assertThat(words(optimized.search("seperate", 10))).contains("separate");
        assertThat(words(optimized.search("definately", 10))).contains("definitely");
        assertThat(words(optimized.search("occurence", 10))).contains("occurrence");
    }

    @Test
    @DisplayName("prefix autocomplete returns plausible, popularity-ordered completions")
    void prefixAutocompleteIsSensible() {
        List<SearchResult> results = optimized.search("sear", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).matchType()).isEqualTo(MatchType.PREFIX);
        assertThat(words(results)).contains("search");
        // Popularity ordering: "search" must outrank rarer completions like "searchable".
        assertThat(words(results).indexOf("search")).isZero();
    }

    @Test
    @DisplayName("an exact common word ranks itself first")
    void exactWordRanksFirst() {
        assertThat(optimized.search("computer", 5).get(0).word()).isEqualTo("computer");
        assertThat(optimized.search("the", 5).get(0).word()).isEqualTo("the");
    }

    @Test
    @DisplayName("a single letter returns the most popular words starting with it, fast")
    void singleLetterQuery() {
        List<SearchResult> results = optimized.search("t", 5);

        assertThat(results).hasSize(5);
        assertThat(words(results).get(0)).isEqualTo("the");
        assertThat(results).allSatisfy(r ->
                assertThat(r.matchType()).isEqualTo(MatchType.PREFIX));
    }

    @Test
    @DisplayName("a query matching nothing returns empty rather than nonsense")
    void nonsenseQueryReturnsNothing() {
        assertThat(optimized.search("zqxjkvwbmnpq", 10)).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // The equivalence guarantee, on real data
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("both engines agree on the real 100k corpus, not just on synthetic ones")
    void enginesAgreeOnRealData() {
        NaiveSearchService naive = new NaiveSearchService(corpus);

        for (String query : List.of("aple", "recieve", "seperate", "app", "sear", "t",
                "computer", "definately", "xyzzy", "programing", "acommodation")) {
            for (int limit : new int[]{1, 5, 10}) {
                assertThat(optimized.search(query, limit))
                        .as("query '%s', limit %d", query, limit)
                        .isEqualTo(naive.search(query, limit));
            }
        }
    }

    @Test
    @DisplayName("index build cost is reported and stays within a sane budget")
    void buildCostIsReported() {
        assertThat(optimized.buildTimeMillis()).isGreaterThan(0L).isLessThan(30_000L);
        System.out.println("[index] " + optimized.indexStats()
                + ", build=" + optimized.buildTimeMillis() + "ms");
    }
}
