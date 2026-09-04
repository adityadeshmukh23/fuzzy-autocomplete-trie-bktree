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
 * Prefix autocomplete: brute-force linear scan versus trie descent plus best-first traversal.
 *
 * <p>Swept across dataset size and query length. Query length matters because it changes how much
 * work each side does in opposite directions: a longer prefix means fewer matches for the trie to
 * rank, but the linear scan still touches every word regardless.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PrefixSearchBenchmark {

    @Param({"1000", "10000", "50000", "100000"})
    public int datasetSize;

    @Param({"naive", "optimized"})
    public String implementation;

    @Param({"1", "3", "6"})
    public int queryLength;

    private SearchService service;
    private List<String> queries;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        service = BenchmarkCorpus.service(implementation, BenchmarkCorpus.corpus(datasetSize));
        queries = BenchmarkCorpus.prefixQueries(queryLength, 16);
    }

    @Benchmark
    public List<SearchResult> prefixSearch() {
        cursor = (cursor + 1) % queries.size();
        return service.prefixSearch(queries.get(cursor), BenchmarkCorpus.LIMIT);
    }
}
