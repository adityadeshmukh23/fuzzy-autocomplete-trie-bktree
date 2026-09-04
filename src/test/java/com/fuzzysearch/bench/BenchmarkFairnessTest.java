package com.fuzzysearch.bench;

import com.fuzzysearch.core.index.WordEntry;
import com.fuzzysearch.core.search.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the benchmark itself, on the principle that an unaudited benchmark is just a number
 * with a chart attached.
 *
 * <p>Two failure modes this rules out:
 *
 * <ol>
 *   <li><b>Comparing different products.</b> If the two engines returned different results, the
 *       speedup would be meaningless -- "faster" could just mean "returns less". The equivalence
 *       is asserted elsewhere on synthetic corpora; here it is asserted on the exact corpora and
 *       exact queries the benchmark runs.</li>
 *   <li><b>Measuring the empty path.</b> If a benchmark query matched nothing at 1,000 words,
 *       both engines would return instantly and the small end of the scaling curve would be
 *       measuring "found nothing" rather than search. Every query is asserted to do real work at
 *       every dataset size.</li>
 * </ol>
 */
class BenchmarkFairnessTest {

    /** A subset of the benchmark's query sets -- enough to audit, quick enough to run always. */
    private static final int QUERIES_PER_SET = 4;

    @Test
    @DisplayName("benchmark queries return real results at every dataset size")
    void benchmarkQueriesDoRealWorkAtEverySize() {
        for (int size : BenchmarkCorpus.SIZES) {
            List<WordEntry> corpus = BenchmarkCorpus.corpus(size);
            SearchService service = BenchmarkCorpus.service("optimized", corpus);

            for (int length : new int[]{1, 3, 6}) {
                for (String query : BenchmarkCorpus.prefixQueries(length, QUERIES_PER_SET)) {
                    assertThat(service.prefixSearch(query, BenchmarkCorpus.LIMIT))
                            .as("prefix '%s' at size %d must match something, or the benchmark "
                                    + "is timing an empty result path", query, size)
                            .isNotEmpty();
                }
            }

            for (String query : BenchmarkCorpus.typoQueries(QUERIES_PER_SET)) {
                assertThat(service.fuzzySearch(query, BenchmarkCorpus.LIMIT, 1))
                        .as("typo '%s' at size %d must find its source word", query, size)
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("both engines return identical results for every benchmarked configuration")
    void benchmarkedEnginesAgreeAtEverySize() {
        for (int size : BenchmarkCorpus.SIZES) {
            List<WordEntry> corpus = BenchmarkCorpus.corpus(size);
            SearchService naive = BenchmarkCorpus.service("naive", corpus);
            SearchService optimized = BenchmarkCorpus.service("optimized", corpus);

            for (int length : new int[]{1, 3, 6}) {
                for (String query : BenchmarkCorpus.prefixQueries(length, QUERIES_PER_SET)) {
                    assertThat(optimized.prefixSearch(query, BenchmarkCorpus.LIMIT))
                            .as("prefixSearch('%s') at size %d", query, size)
                            .isEqualTo(naive.prefixSearch(query, BenchmarkCorpus.LIMIT));
                }
            }

            for (String query : BenchmarkCorpus.typoQueries(QUERIES_PER_SET)) {
                for (int distance : new int[]{1, 2}) {
                    assertThat(optimized.fuzzySearch(query, BenchmarkCorpus.LIMIT, distance))
                            .as("fuzzySearch('%s', d=%d) at size %d", query, distance, size)
                            .isEqualTo(naive.fuzzySearch(query, BenchmarkCorpus.LIMIT, distance));
                }
                assertThat(optimized.search(query, BenchmarkCorpus.LIMIT))
                        .as("search('%s') at size %d", query, size)
                        .isEqualTo(naive.search(query, BenchmarkCorpus.LIMIT));
            }
        }
    }

    @Test
    @DisplayName("corpus slices are nested, so dataset size is the only variable in the sweep")
    void corpusSlicesAreNested() {
        // If a bigger slice were not a superset of a smaller one, the scaling curve would be
        // confounded by vocabulary changes rather than isolating N.
        List<String> small = BenchmarkCorpus.corpus(1_000).stream()
                .map(WordEntry::normalized).toList();
        List<String> large = BenchmarkCorpus.corpus(100_000).stream()
                .map(WordEntry::normalized).toList();

        assertThat(large).startsWith(small.toArray(new String[0]));
    }

    @Test
    @DisplayName("query sets are the documented size and free of duplicates")
    void querySetsAreWellFormed() {
        for (int length : new int[]{1, 3, 6}) {
            List<String> queries = BenchmarkCorpus.prefixQueries(length, 16);

            assertThat(queries).hasSize(16).doesNotHaveDuplicates();
            assertThat(queries).allSatisfy(q -> assertThat(q).hasSize(length));
        }
        assertThat(BenchmarkCorpus.typoQueries(16)).hasSize(16).doesNotHaveDuplicates();
    }
}
