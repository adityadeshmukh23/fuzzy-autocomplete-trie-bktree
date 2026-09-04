package com.fuzzysearch.api;

import com.fuzzysearch.api.dto.HealthResponse;
import com.fuzzysearch.core.search.NaiveSearchService;
import com.fuzzysearch.core.search.OptimizedSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check, exposed at both {@code /health} and {@code /api/health}.
 *
 * <p>Two paths because they serve different callers: hosting platforms (Render, Fly, Railway)
 * default to probing {@code /health} at the root, while the frontend reaches everything else
 * under the CORS-enabled {@code /api} prefix.
 *
 * <p>It reports index metadata alongside liveness, which makes it useful for confirming a
 * deployment actually loaded its dataset rather than starting up empty.
 */
@RestController
public class HealthController {

    private final OptimizedSearchService optimized;
    private final NaiveSearchService naive;

    public HealthController(OptimizedSearchService optimized, NaiveSearchService naive) {
        this.optimized = optimized;
        this.naive = naive;
    }

    @GetMapping({"/health", "/api/health"})
    public HealthResponse health() {
        // The index is built eagerly during context startup, so if this method can run at all,
        // the index exists and is queryable. There is no half-initialised state to report.
        return new HealthResponse("UP", optimized.size(), optimized.buildTimeMillis(),
                naive.buildTimeMillis(), optimized.indexStats());
    }
}
