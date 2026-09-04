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
 * Typo tolerance: brute-force Levenshtein against every word versus BK-tree pruning.
 *
 * <p>Swept across dataset size and edit-distance threshold. The threshold is the axis that
 * matters most: each visited BK-tree node opens up to {@code 2k+1} child edges, so pruning
 * weakens sharply as {@code k} grows, while the linear scan's banded cutoff gets *cheaper*
 * relative to its own worst case. Phase 2 predicted the two curves cross somewhere around
 * {@code k = 2}; this measures where.
 *
 * <p>Note both sides call {@code fuzzySearch} directly rather than {@code search}, so this
 * isolates the fuzzy path with no prefix results and no progressive relaxation involved.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FuzzySearchBenchmark {

    @Param({"1000", "10000", "50000", "100000"})
    public int datasetSize;

    @Param({"naive", "optimized"})
    public String implementation;

    @Param({"1", "2"})
    public int maxEditDistance;

    private SearchService service;
    private List<String> queries;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        service = BenchmarkCorpus.service(implementation, BenchmarkCorpus.corpus(datasetSize));
        queries = BenchmarkCorpus.typoQueries(16);
    }

    @Benchmark
    public List<SearchResult> fuzzySearch() {
        cursor = (cursor + 1) % queries.size();
        return service.fuzzySearch(queries.get(cursor), BenchmarkCorpus.LIMIT, maxEditDistance);
    }
}
