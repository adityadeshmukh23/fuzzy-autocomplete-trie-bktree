package com.fuzzysearch.config;

import com.fuzzysearch.core.index.BundledDataset;
import com.fuzzysearch.core.index.DatasetLoader;
import com.fuzzysearch.core.index.WordEntry;
import com.fuzzysearch.core.search.NaiveSearchService;
import com.fuzzysearch.core.search.OptimizedSearchService;
import com.fuzzysearch.core.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds both indexes once at startup.
 *
 * <h2>Why singletons are safe here</h2>
 * The trie and BK-tree are mutated only during construction and are read-only afterwards. Spring
 * publishes a fully-constructed singleton to all threads with the necessary happens-before
 * relationship, so concurrent requests read a consistent, immutable structure with no locking and
 * no contention. Concurrent <em>writes</em> would not be safe -- which is why the index is
 * build-once, read-many, and there is no runtime insert API.
 */
@Configuration
public class SearchIndexConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexConfig.class);

    /** Mixed prefix and typo queries, so both the trie and the BK-tree paths get compiled. */
    private static final List<String> WARMUP_QUERIES =
            List.of("a", "se", "sea", "sear", "compu", "informa", "th", "prog",
                    "aple", "recieve", "seperate", "definately", "acommodation", "programing");

    private static final int WARMUP_ROUNDS = 3;

    /** 0 loads the whole bundled dataset; lower values are useful for testing at smaller scale. */
    @Value("${fuzzysearch.dataset.max-entries:0}")
    private int maxEntries;

    @Bean
    public List<WordEntry> corpus() {
        DatasetLoader.LoadReport report = BundledDataset.load(maxEntries);
        log.info("dataset: {}", report.summary());
        return report.entries();
    }

    @Bean
    public OptimizedSearchService optimizedSearchService(List<WordEntry> corpus) {
        OptimizedSearchService service = new OptimizedSearchService(corpus);
        log.info("optimized index built in {} ms ({})", service.buildTimeMillis(),
                service.indexStats());
        warmUp(service);
        return service;
    }

    /**
     * The brute-force baseline, wired up as a first-class bean rather than kept in a test folder.
     * It backs {@code /api/search/naive} and {@code /api/compare} so the benchmark page can show
     * the difference against a live index rather than a screenshot.
     */
    @Bean
    public NaiveSearchService naiveSearchService(List<WordEntry> corpus) {
        NaiveSearchService service = new NaiveSearchService(corpus);
        log.info("naive baseline built in {} ms", service.buildTimeMillis());
        warmUp(service);
        return service;
    }

    /**
     * Runs a handful of queries before the bean is published, so the first real request does not
     * pay for JIT compilation.
     *
     * <p>This is not cosmetic. Measured on a freshly started process, the first live comparison
     * reported 12 ms for the optimised engine against roughly 1 ms once warm -- an order of
     * magnitude, entirely from the interpreter running before C2 compiles the hot loops. A demo
     * whose headline number is 10x wrong for the first visitor is worse than no demo.
     *
     * <p>The queries deliberately cover both paths: short prefixes exercise the trie's best-first
     * traversal, and misspellings force the BK-tree and progressive relaxation. Doing this inside
     * the {@code @Bean} method rather than in an {@code ApplicationRunner} guarantees it finishes
     * before the context is published and the servlet container starts accepting requests.
     */
    private static void warmUp(SearchService service) {
        long start = System.nanoTime();
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            for (String query : WARMUP_QUERIES) {
                service.search(query, 10);
            }
        }
        log.info("warmed {} engine in {} ms", service.name(),
                (System.nanoTime() - start) / 1_000_000L);
    }
}
