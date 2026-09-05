package com.fuzzysearch.api;

import com.fuzzysearch.api.dto.CompareResponse;
import com.fuzzysearch.api.dto.SearchResponse;
import com.fuzzysearch.api.dto.SearchResultDto;
import com.fuzzysearch.core.search.NaiveSearchService;
import com.fuzzysearch.core.search.OptimizedSearchService;
import com.fuzzysearch.core.search.SearchResult;
import com.fuzzysearch.core.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * The search API.
 *
 * <p>Both engines are exposed. The optimised one is the product; the naive one exists so the
 * benchmark page can run a live head-to-head against the same index rather than showing numbers
 * from a file.
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    /**
     * Repeats each timed call and keeps the fastest.
     *
     * <p>Latency distributions are right-skewed -- a GC pause or a descheduled thread can add
     * milliseconds -- so the minimum of a few runs is a far more stable estimate of the real cost
     * than a single sample. Three is enough to discard an unlucky one without making the endpoint
     * expensive.
     *
     * <p>These numbers are indicative, for a live demo. The figures in the README come from JMH,
     * which forks a fresh JVM and handles warmup properly.
     */
    private static final int TIMING_REPEATS = 3;

    private final OptimizedSearchService optimized;
    private final NaiveSearchService naive;

    public SearchController(OptimizedSearchService optimized, NaiveSearchService naive) {
        this.optimized = optimized;
        this.naive = naive;
    }

    /** Ranked autocomplete results from the trie + BK-tree engine. */
    @GetMapping("/search")
    public SearchResponse search(@RequestParam(name = "q", required = false) String q,
                                 @RequestParam(name = "limit",
                                         defaultValue = "" + QueryValidation.DEFAULT_LIMIT)
                                 int limit) {
        return run(optimized, q, limit);
    }

    /**
     * The same query against the brute-force baseline.
     *
     * <p>Kept reachable in production purely so the difference can be demonstrated live. It
     * returns byte-identical results to {@code /api/search} -- that equivalence is asserted by
     * the test suite and can be checked at runtime through {@code /api/compare}.
     */
    @GetMapping("/search/naive")
    public SearchResponse searchNaive(@RequestParam(name = "q", required = false) String q,
                                      @RequestParam(name = "limit",
                                              defaultValue = "" + QueryValidation.DEFAULT_LIMIT)
                                      int limit) {
        return run(naive, q, limit);
    }

    /**
     * Runs both engines on the same query and returns both timings plus whether they agreed.
     *
     * <p>Timing both server-side is deliberate: see {@link CompareResponse}.
     */
    @GetMapping("/compare")
    public CompareResponse compare(@RequestParam(name = "q", required = false) String q,
                                   @RequestParam(name = "limit",
                                           defaultValue = "" + QueryValidation.DEFAULT_LIMIT)
                                   int limit) {
        String query = QueryValidation.requireValidQuery(q);
        QueryValidation.requireValidLimit(limit);

        if (QueryValidation.isBlank(query)) {
            return new CompareResponse(query, limit, 0, 0, 1.0, true, List.of());
        }

        Comparison run = timedInterleaved(() -> optimized.search(query, limit),
                () -> naive.search(query, limit));
        Timed optimizedRun = run.first();
        Timed naiveRun = run.second();

        return new CompareResponse(
                query,
                limit,
                optimizedRun.micros(),
                naiveRun.micros(),
                naiveRun.micros() / optimizedRun.micros(),
                optimizedRun.results().equals(naiveRun.results()),
                optimizedRun.results().stream().map(SearchResultDto::from).toList());
    }

    private SearchResponse run(SearchService service, String q, int limit) {
        String query = QueryValidation.requireValidQuery(q);
        QueryValidation.requireValidLimit(limit);

        if (QueryValidation.isBlank(query)) {
            return SearchResponse.of(query, service.name(), limit, List.of(), 0);
        }

        Timed run = timed(() -> service.search(query, limit));
        return SearchResponse.of(query, service.name(), limit, run.results(), run.micros());
    }

    private record Timed(List<SearchResult> results, double micros) {
    }

    private record Comparison(Timed first, Timed second) {
    }

    /**
     * Times two engines by alternating them, rather than running all repeats of one and then all
     * repeats of the other.
     *
     * <p><b>This is a correctness fix, not a refinement.</b> The deployed free tier throttles CPU
     * once burst credit is exhausted, so in a back-to-back layout the engine measured *second*
     * absorbs the penalty. Observed on the live instance: the brute-force scan reported ~9 ms when
     * requested on its own and ~95 ms when it ran second inside this endpoint — inflating the
     * headline speedup roughly tenfold, purely as an artifact of measurement order.
     *
     * <p>Interleaving exposes both engines to the same throttling pattern, so a slowdown mid-request
     * hits them roughly equally instead of landing entirely on one. It cannot eliminate the noise
     * — nothing running inside a shared container can — which is exactly why the README's numbers
     * come from JMH in a dedicated process and these are labelled indicative.
     */
    private static Comparison timedInterleaved(Supplier<List<SearchResult>> first,
                                               Supplier<List<SearchResult>> second) {
        List<SearchResult> firstResults = List.of();
        List<SearchResult> secondResults = List.of();
        long fastestFirst = Long.MAX_VALUE;
        long fastestSecond = Long.MAX_VALUE;

        for (int i = 0; i < TIMING_REPEATS; i++) {
            long start = System.nanoTime();
            firstResults = first.get();
            fastestFirst = Math.min(fastestFirst, System.nanoTime() - start);

            start = System.nanoTime();
            secondResults = second.get();
            fastestSecond = Math.min(fastestSecond, System.nanoTime() - start);
        }
        return new Comparison(new Timed(firstResults, fastestFirst / 1_000.0),
                new Timed(secondResults, fastestSecond / 1_000.0));
    }

    private static Timed timed(Supplier<List<SearchResult>> query) {
        List<SearchResult> results = List.of();
        long fastestNanos = Long.MAX_VALUE;
        for (int i = 0; i < TIMING_REPEATS; i++) {
            long start = System.nanoTime();
            results = query.get();
            fastestNanos = Math.min(fastestNanos, System.nanoTime() - start);
        }
        return new Timed(results, fastestNanos / 1_000.0);
    }
}
