package com.fuzzysearch.bench;

import com.fuzzysearch.core.index.BundledDataset;
import com.fuzzysearch.core.index.WordEntry;
import com.fuzzysearch.core.search.NaiveSearchService;
import com.fuzzysearch.core.search.OptimizedSearchService;
import com.fuzzysearch.core.search.SearchService;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared setup for the benchmarks: corpora at a range of sizes, and query sets that are valid at
 * every one of those sizes.
 *
 * <h2>Why the queries are derived from the smallest corpus</h2>
 * A query that only matches at 100,000 words would return nothing at 1,000 -- and "returns
 * nothing" is fast for both implementations, so the comparison would measure an empty result path
 * rather than search. Every query here is built from the top 1,000 words, which are present in
 * every slice by construction, so the same query does real work at every dataset size and the
 * scaling curve means something.
 */
final class BenchmarkCorpus {

    private BenchmarkCorpus() {
    }

    /** The dataset sizes swept by the scaling benchmarks. */
    static final int[] SIZES = {1_000, 10_000, 50_000, 100_000};

    /** Results requested per query -- a realistic autocomplete page. */
    static final int LIMIT = 10;

    /**
     * The top {@code size} words by corpus frequency. Slices are nested, so a bigger corpus is a
     * strict superset of a smaller one and the only variable across the sweep is N.
     */
    static List<WordEntry> corpus(int size) {
        return BundledDataset.load(size).entries();
    }

    static SearchService service(String implementation, List<WordEntry> corpus) {
        return switch (implementation) {
            case "naive" -> new NaiveSearchService(corpus);
            case "optimized" -> new OptimizedSearchService(corpus);
            default -> throw new IllegalArgumentException("unknown implementation: " + implementation);
        };
    }

    /**
     * Prefixes of exactly {@code length} characters, taken from the most common words.
     *
     * <p>Benchmarks rotate through this set rather than repeating a single query, so the reported
     * average is not an artifact of one unusually lucky or unlucky prefix.
     */
    static List<String> prefixQueries(int length, int count) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (WordEntry entry : corpus(1_000)) {
            if (entry.normalized().length() >= length) {
                queries.add(entry.normalized().substring(0, length));
            }
            if (queries.size() >= count) {
                break;
            }
        }
        return List.copyOf(queries);
    }

    /**
     * Realistic typos: a common word with one character deleted.
     *
     * <p>Built by deletion from a top-1,000 word, so each query is guaranteed to sit at edit
     * distance 1 from a word that exists in every corpus slice. The fuzzy search therefore always
     * has something to find, at every size and every distance threshold.
     */
    static List<String> typoQueries(int count) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (WordEntry entry : corpus(1_000)) {
            String word = entry.normalized();
            if (word.length() >= 6) {
                int drop = word.length() / 2;
                queries.add(word.substring(0, drop) + word.substring(drop + 1));
            }
            if (queries.size() >= count) {
                break;
            }
        }
        return List.copyOf(queries);
    }
}
