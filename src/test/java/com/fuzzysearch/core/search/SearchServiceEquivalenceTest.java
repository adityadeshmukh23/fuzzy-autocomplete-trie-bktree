package com.fuzzysearch.core.search;

import com.fuzzysearch.core.index.WordEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The most important test in the project.
 *
 * <p>Phase 4 will claim the optimised engine is dramatically faster than the naive one. That
 * claim is only worth anything if the two produce the <em>same answers</em> -- otherwise the
 * benchmark is comparing two different products, and "faster" might just mean "returns less".
 *
 * <p>These tests assert byte-identical output: same words, same order, same scores, same match
 * types, same edit distances. Every shortcut the optimised engine takes -- best-first trie
 * traversal that never sees most of the subtree, BK-tree branches pruned without inspection,
 * asking the trie for only K prefix results instead of all of them -- has to survive this.
 */
class SearchServiceEquivalenceTest {

    private record Pair(NaiveSearchService naive, OptimizedSearchService optimized) {
    }

    private static Pair build(List<WordEntry> corpus) {
        return new Pair(new NaiveSearchService(corpus), new OptimizedSearchService(corpus));
    }

    // -------------------------------------------------------------------------------------
    // Randomised equivalence across corpora, queries and limits
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("combined search is identical on random corpora, queries and limits")
    void combinedSearchIsIdentical() {
        Random random = new Random(20260904L);

        for (int trial = 0; trial < 40; trial++) {
            List<WordEntry> corpus = randomCorpus(random, 400);
            Pair engines = build(corpus);

            for (int probe = 0; probe < 25; probe++) {
                String query = randomWord(random, 0, 8);
                int limit = 1 + random.nextInt(15);

                assertThat(engines.optimized().search(query, limit))
                        .as("trial %d, query '%s', limit %d", trial, query, limit)
                        .isEqualTo(engines.naive().search(query, limit));
            }
        }
    }

    @Test
    @DisplayName("prefix-only search is identical, including when the trie returns only K of many")
    void prefixSearchIsIdentical() {
        Random random = new Random(777L);

        for (int trial = 0; trial < 40; trial++) {
            List<WordEntry> corpus = randomCorpus(random, 400);
            Pair engines = build(corpus);

            for (int probe = 0; probe < 25; probe++) {
                // Short prefixes on purpose: they match hundreds of words, so the trie returns a
                // tiny slice of the match set while the naive scan collects all of it. If the
                // "top-K by weight is top-K by score" argument were wrong, this would catch it.
                String query = randomWord(random, 0, 3);
                int limit = 1 + random.nextInt(15);

                assertThat(engines.optimized().prefixSearch(query, limit))
                        .as("trial %d, prefix '%s', limit %d", trial, query, limit)
                        .isEqualTo(engines.naive().prefixSearch(query, limit));
            }
        }
    }

    @Test
    @DisplayName("fuzzy-only search is identical across every edit budget")
    void fuzzySearchIsIdentical() {
        Random random = new Random(31337L);

        for (int trial = 0; trial < 25; trial++) {
            List<WordEntry> corpus = randomCorpus(random, 400);
            Pair engines = build(corpus);

            for (int maxDistance = 0; maxDistance <= 3; maxDistance++) {
                for (int probe = 0; probe < 8; probe++) {
                    String query = randomWord(random, 1, 8);
                    int limit = 1 + random.nextInt(15);

                    assertThat(engines.optimized().fuzzySearch(query, limit, maxDistance))
                            .as("trial %d, query '%s', d=%d, limit %d",
                                    trial, query, maxDistance, limit)
                            .isEqualTo(engines.naive().fuzzySearch(query, limit, maxDistance));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // The specific cases where the two engines are structurally most likely to disagree
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a word matching both ways is labelled PREFIX by both engines")
    void prefixMatchIsNeverMislabelledAsFuzzy() {
        // "apps" starts with "app" AND sits one edit from it, so it is reachable through either
        // path. The naive engine tests startsWith first and never fuzzy-tests it; the optimised
        // engine must filter it out of its BK-tree hits to agree.
        List<WordEntry> corpus = List.of(
                WordEntry.of("app", 100),
                WordEntry.of("apps", 90),
                WordEntry.of("apple", 80),
                WordEntry.of("ape", 70),
                WordEntry.of("axe", 60));
        Pair engines = build(corpus);

        List<SearchResult> results = engines.optimized().search("app", 10);

        assertThat(results).isEqualTo(engines.naive().search("app", 10));
        assertThat(results)
                .filteredOn(r -> r.word().equals("apps"))
                .singleElement()
                .extracting(SearchResult::matchType)
                .isEqualTo(MatchType.PREFIX);
    }

    @Test
    @DisplayName("a prefix match outside the trie's top-K never reappears as a fuzzy result")
    void prefixMatchBelowTheCutIsNotResurrectedAsFuzzy() {
        // Many prefix matches, small limit: the trie hands back only 3 of them. Several of the
        // rest are also within the edit budget and would come back through the BK-tree.
        List<WordEntry> corpus = new ArrayList<>();
        corpus.add(WordEntry.of("cat", 1_000_000));
        for (int i = 0; i < 40; i++) {
            corpus.add(WordEntry.of("cat" + (char) ('a' + i % 26) + i, 1_000 - i));
        }
        corpus.add(WordEntry.of("cats", 5));
        corpus.add(WordEntry.of("cot", 900_000));
        Pair engines = build(corpus);

        for (int limit = 1; limit <= 8; limit++) {
            assertThat(engines.optimized().search("cat", limit))
                    .as("limit %d", limit)
                    .isEqualTo(engines.naive().search("cat", limit));
        }
    }

    @Test
    @DisplayName("case variants in the corpus do not split the two engines apart")
    void caseVariantsAreMergedIdentically() {
        // The trie merges these into one entry automatically; the flat list would not, without
        // the shared Corpus.deduplicate step.
        List<WordEntry> corpus = List.of(
                WordEntry.of("Apple", 30),
                WordEntry.of("apple", 70),
                WordEntry.of("APPLE", 5),
                WordEntry.of("apply", 200),
                WordEntry.of("apt", 50));
        Pair engines = build(corpus);

        List<SearchResult> results = engines.optimized().search("app", 10);

        assertThat(results).isEqualTo(engines.naive().search("app", 10));
        assertThat(results).extracting(SearchResult::word).containsOnlyOnce("apple");
        assertThat(results)
                .filteredOn(r -> r.word().equals("apple"))
                .singleElement()
                .extracting(SearchResult::weight)
                .isEqualTo(105L);
        assertThat(engines.naive().size()).isEqualTo(engines.optimized().size());
    }

    @Test
    @DisplayName("an empty query returns the same global top-K from both")
    void emptyQueryIsIdentical() {
        Random random = new Random(5L);
        Pair engines = build(randomCorpus(random, 500));

        assertThat(engines.optimized().search("", 10)).isEqualTo(engines.naive().search("", 10));
        assertThat(engines.optimized().search("   ", 10))
                .isEqualTo(engines.naive().search("   ", 10));
    }

    @Test
    @DisplayName("a query matching nothing returns empty from both")
    void noMatchIsIdentical() {
        Pair engines = build(List.of(WordEntry.of("apple", 10), WordEntry.of("banana", 20)));

        assertThat(engines.optimized().search("zzzzzzzzzzzz", 10))
                .isEqualTo(engines.naive().search("zzzzzzzzzzzz", 10))
                .isEmpty();
    }

    @Test
    @DisplayName("limit 0 returns empty from both")
    void zeroLimitIsIdentical() {
        Pair engines = build(List.of(WordEntry.of("apple", 10), WordEntry.of("apply", 20)));

        assertThat(engines.optimized().search("app", 0))
                .isEqualTo(engines.naive().search("app", 0))
                .isEmpty();
    }

    @Test
    @DisplayName("a single-word corpus behaves identically")
    void singleWordCorpus() {
        Pair engines = build(List.of(WordEntry.of("apple", 42)));

        assertThat(engines.optimized().search("app", 5)).isEqualTo(engines.naive().search("app", 5));
        assertThat(engines.optimized().search("aple", 5))
                .isEqualTo(engines.naive().search("aple", 5));
    }

    @Test
    @DisplayName("a corpus where every weight is zero behaves identically")
    void uniformZeroWeights() {
        List<WordEntry> corpus = new ArrayList<>();
        for (String word : List.of("car", "cart", "care", "cars", "card", "cat")) {
            corpus.add(WordEntry.of(word, 0));
        }
        Pair engines = build(corpus);

        assertThat(engines.optimized().search("car", 4)).isEqualTo(engines.naive().search("car", 4));
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private static List<WordEntry> randomCorpus(Random random, int wordCount) {
        List<WordEntry> corpus = new ArrayList<>(wordCount);
        for (int i = 0; i < wordCount; i++) {
            // Weights spanning several orders of magnitude, like a real Zipfian frequency list,
            // plus deliberate collisions so the tie-break and de-duplication paths get exercised.
            long weight = switch (random.nextInt(4)) {
                case 0 -> random.nextInt(10);
                case 1 -> random.nextInt(1_000);
                case 2 -> random.nextInt(100_000);
                default -> random.nextInt(5);
            };
            corpus.add(WordEntry.of(randomWord(random, 1, 8), weight));
        }
        return corpus;
    }

    private static String randomWord(Random random, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(5)));
        }
        return sb.toString();
    }
}
