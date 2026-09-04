package com.fuzzysearch.bench;

import com.fuzzysearch.core.search.SearchResult;
import com.fuzzysearch.core.search.SearchService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What the API actually serves: prefix and fuzzy merged, with progressive relaxation.
 *
 * <p>The other two benchmarks isolate a single data structure each, which is what makes them
 * diagnostic. This one is the number a user would feel -- a realistic typo query going through
 * the whole pipeline.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CombinedSearchBenchmark {

    @Param({"1000", "10000", "50000", "100000"})
    public int datasetSize;

    @Param({"naive", "optimized"})
    public String implementation;

    /**
     * The two regimes the engine actually sees.
     *
     * <p>{@code typo} is a complete misspelled word: almost no prefix matches, so the prefix
     * short-circuit cannot fire and progressive relaxation escalates to distance 2. It is the
     * worst case.
     *
     * <p>{@code prefix} is a partially typed word, which is what the overwhelming majority of
     * keystrokes in a search-as-you-type box actually are. Here prefix matches fill the page, the
     * short-circuit fires, and fuzzy search is skipped as provably unnecessary.
     *
     * <p>Reporting only the first would understate the engine badly; reporting only the second
     * would hide the expensive path. Both are measured.
     */
    @Param({"typo", "prefix"})
    public String queryType;

    private SearchService service;
    private List<String> queries;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        service = BenchmarkCorpus.service(implementation, BenchmarkCorpus.corpus(datasetSize));
        queries = queryType.equals("typo")
                ? BenchmarkCorpus.typoQueries(16)
                : BenchmarkCorpus.prefixQueries(4, 16);
    }

    @Benchmark
    public List<SearchResult> search() {
        cursor = (cursor + 1) % queries.size();
        return service.search(queries.get(cursor), BenchmarkCorpus.LIMIT);
    }
}
