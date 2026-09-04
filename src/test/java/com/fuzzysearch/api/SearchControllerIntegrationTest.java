package com.fuzzysearch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests over the real Spring context and the real 100,000-word index.
 *
 * <p>Deliberately not {@code @WebMvcTest} with a mocked service. Mocking the search service would
 * test only that the controller calls a method, which is the least interesting thing here. This
 * boots the actual application, loads the actual dataset and asserts the actual JSON a browser
 * would receive -- including that both engines still agree when reached over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode getJson(String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // -------------------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/search returns ranked results tagged with their match type")
    void searchReturnsRankedTaggedResults() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "sear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("sear"))
                .andExpect(jsonPath("$.engine").value("optimized"))
                .andExpect(jsonPath("$.results[0].word").value("search"))
                .andExpect(jsonPath("$.results[0].matchType").value("PREFIX"))
                .andExpect(jsonPath("$.results[0].score").isNumber())
                .andExpect(jsonPath("$.results[0].weight").isNumber())
                .andExpect(jsonPath("$.latencyMicros").isNumber());
    }

    @Test
    @DisplayName("the motivating example survives the whole stack: 'aple' finds 'apple'")
    void typoIsCorrectedOverHttp() throws Exception {
        JsonNode body = getJson("/api/search", "q", "aple", "limit", "10");

        assertThat(body.get("results").findValuesAsText("word")).contains("apple");
    }

    @Test
    @DisplayName("fuzzy results carry a non-zero edit distance, prefix results carry zero")
    void editDistanceIsReported() throws Exception {
        JsonNode body = getJson("/api/search", "q", "aple", "limit", "10");

        for (JsonNode result : body.get("results")) {
            if ("PREFIX".equals(result.get("matchType").asText())) {
                assertThat(result.get("editDistance").asInt()).isZero();
            } else {
                assertThat(result.get("editDistance").asInt()).isPositive();
            }
        }
    }

    @Test
    @DisplayName("limit is respected")
    void limitIsRespected() throws Exception {
        JsonNode body = getJson("/api/search", "q", "a", "limit", "3");

        assertThat(body.get("results")).hasSize(3);
        assertThat(body.get("resultCount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("a query matching nothing returns 200 with an empty list, not an error")
    void noMatchesIsNotAnError() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "zqxjkvwbmnpq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0));
    }

    // -------------------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a missing q is a 400 with a usable message")
    void missingQueryIsRejected() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("q")));
    }

    @Test
    @DisplayName("a blank query returns empty rather than the corpus's most popular words")
    void blankQueryReturnsEmpty() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0));
    }

    @Test
    @DisplayName("an over-long query is rejected, since edit distance cost scales with it")
    void overlongQueryIsRejected() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "a".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("a query at exactly the length limit is accepted")
    void maximumLengthQueryIsAccepted() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "a".repeat(64)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "1000"})
    @DisplayName("out-of-range limits are rejected")
    void outOfRangeLimitIsRejected(String limit) throws Exception {
        mockMvc.perform(get("/api/search").param("q", "app").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("a non-numeric limit gets a clear message, not a stack trace")
    void nonNumericLimitIsRejected() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "app").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("must be a number")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a'b", "a\"b", "<script>alert(1)</script>", "a%20b", "../../etc/passwd",
            "café", "日本語", "a&b=c", "100%", "a\\b", "*", "?"})
    @DisplayName("special characters are searched, not executed and not crashed on")
    void specialCharactersAreHandled(String query) throws Exception {
        // There is no SQL, no shell and no template engine behind this, so these are not
        // injection vectors -- but they are exactly the inputs that crash a naive parser, and
        // the normalizer and DP table must handle them as ordinary text.
        mockMvc.perform(get("/api/search").param("q", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").isNumber());
    }

    // -------------------------------------------------------------------------------------
    // The naive endpoint and the live comparison
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the naive endpoint returns byte-identical results to the optimized one")
    void naiveEndpointAgreesWithOptimized() throws Exception {
        for (String query : new String[]{"aple", "sear", "recieve", "a", "computer"}) {
            JsonNode optimized = getJson("/api/search", "q", query);
            JsonNode naive = getJson("/api/search/naive", "q", query);

            assertThat(naive.get("engine").asText()).isEqualTo("naive");
            assertThat(naive.get("results"))
                    .as("engines must agree over HTTP for '%s'", query)
                    .isEqualTo(optimized.get("results"));
        }
    }

    @Test
    @DisplayName("GET /api/compare runs both engines and reports that they agreed")
    void compareRunsBothEngines() throws Exception {
        JsonNode body = getJson("/api/compare", "q", "sear", "limit", "10");

        assertThat(body.get("identicalResults").asBoolean())
                .as("the equivalence guarantee, demonstrated live rather than asserted")
                .isTrue();
        assertThat(body.get("optimizedMicros").asDouble()).isPositive();
        assertThat(body.get("naiveMicros").asDouble()).isPositive();
        assertThat(body.get("speedup").asDouble()).isPositive();
        assertThat(body.get("results")).isNotEmpty();
    }

    @Test
    @DisplayName("compare validates its input like the search endpoints do")
    void compareValidatesInput() throws Exception {
        mockMvc.perform(get("/api/compare")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/compare").param("q", "app").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/compare").param("q", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty());
    }

    // -------------------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("health reports index metadata, so a deployment that loaded nothing is visible")
    void healthReportsIndexState() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.corpusSize").value(org.hamcrest.Matchers.greaterThan(99_000)))
                .andExpect(jsonPath("$.indexStats").isString());
    }

    @Test
    @DisplayName("health is reachable at both paths, for platform probes and for the frontend")
    void healthIsReachableAtBothPaths() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown path is a 404")
    void unknownPathIs404() throws Exception {
        mockMvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }
}
