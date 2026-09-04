package com.fuzzysearch.core.bktree;

import com.fuzzysearch.core.distance.LevenshteinDistance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BKTreeTest {

    private static BKTree treeOf(String... words) {
        BKTree tree = new BKTree();
        tree.addAll(List.of(words));
        return tree;
    }

    private static List<String> words(List<BKTree.Match> matches) {
        return matches.stream().map(BKTree.Match::word).toList();
    }

    // -------------------------------------------------------------------------------------
    // Basic search behaviour
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("distance 0 finds the exact word only")
    void exactMatch() {
        BKTree tree = treeOf("apple", "apply", "ape", "banana");

        assertThat(words(tree.search("apple", 0))).containsExactly("apple");
    }

    @Test
    @DisplayName("a single typo is found within distance 1")
    void singleTypo() {
        BKTree tree = treeOf("apple", "apply", "ape", "banana", "orange");

        // "aple" is one deletion from "apple" -- the project's motivating example. It is also
        // one deletion from "ape", so BOTH are legitimate distance-1 hits. Fuzzy matching is
        // genuinely ambiguous; narrowing to the intended word is the ranking layer's job, not
        // the BK-tree's. Ordering here is closest-first, then shorter-first.
        assertThat(words(tree.search("aple", 1))).containsExactly("ape", "apple");
    }

    @Test
    @DisplayName("multiple typos are found as the budget widens")
    void multipleTypos() {
        BKTree tree = treeOf("apple", "apply", "ape", "banana", "orange");

        assertThat(words(tree.search("appl", 1))).containsExactlyInAnyOrder("apple", "apply");
        assertThat(words(tree.search("aplle", 2))).contains("apple", "apply");
    }

    @Test
    @DisplayName("matches come back closest-first")
    void matchesAreOrderedByDistance() {
        BKTree tree = treeOf("apple", "apples", "ample", "maple");

        List<BKTree.Match> matches = tree.search("apple", 2);
        assertThat(matches.get(0).word()).isEqualTo("apple");
        assertThat(matches.get(0).distance()).isZero();
        assertThat(matches).isSortedAccordingTo(
                (a, b) -> Integer.compare(a.distance(), b.distance()));
    }

    @Test
    @DisplayName("maxDistance is an inclusive boundary")
    void maxDistanceBoundaryIsInclusive() {
        BKTree tree = treeOf("apple");

        // "apply" is exactly 1 edit from "apple".
        assertThat(LevenshteinDistance.distance("apply", "apple")).isEqualTo(1);
        assertThat(words(tree.search("apply", 1))).containsExactly("apple");
        assertThat(words(tree.search("apply", 0))).isEmpty();
    }

    @Test
    @DisplayName("a query matching nothing returns empty, not an error")
    void noMatches() {
        BKTree tree = treeOf("apple", "banana");

        assertThat(tree.search("zzzzzzzz", 1)).isEmpty();
    }

    @Test
    @DisplayName("searching an empty tree is safe")
    void emptyTree() {
        BKTree tree = new BKTree();

        assertThat(tree.size()).isZero();
        assertThat(tree.maxDepth()).isZero();
        assertThat(tree.search("anything", 2)).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // Insertion semantics
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("duplicates are rejected without growing the tree")
    void duplicatesRejected() {
        BKTree tree = new BKTree();

        assertThat(tree.add("apple")).isTrue();
        assertThat(tree.add("apple")).isFalse();
        assertThat(tree.add("APPLE")).as("case-insensitive, so also a duplicate").isFalse();
        assertThat(tree.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("indexing and querying are both case-insensitive")
    void caseInsensitive() {
        BKTree tree = treeOf("Apple", "Banana");

        assertThat(words(tree.search("APPLE", 0))).containsExactly("apple");
        assertThat(words(tree.search("aple", 1))).containsExactly("apple");
    }

    @Test
    void rejectsBlankWord() {
        assertThatThrownBy(() -> new BKTree().add("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeMaxDistance() {
        assertThatThrownBy(() -> treeOf("apple").search("apple", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDistance");
    }

    // -------------------------------------------------------------------------------------
    // THE critical test: pruning must be lossless
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("BK-tree search returns exactly what a brute-force scan returns")
    void pruningIsLossless() {
        // If the triangle-inequality reasoning in BKTree is wrong in any way, this test fails:
        // a broken pruning window drops real matches, and nothing else in the suite would
        // notice, because the results would still look plausible.
        Random random = new Random(20260904L);

        for (int trial = 0; trial < 30; trial++) {
            List<String> corpus = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                corpus.add(randomWord(random, 1, 8));
            }

            BKTree tree = new BKTree();
            tree.addAll(corpus);

            for (int maxDistance = 0; maxDistance <= 4; maxDistance++) {
                for (int probe = 0; probe < 5; probe++) {
                    String query = randomWord(random, 1, 8);

                    List<String> expected = bruteForce(corpus, query, maxDistance);
                    List<String> actual = words(tree.search(query, maxDistance));

                    assertThat(actual)
                            .as("trial %d, query '%s', maxDistance %d", trial, query, maxDistance)
                            .containsExactlyInAnyOrderElementsOf(expected);
                }
            }
        }
    }

    @Test
    @DisplayName("results do not depend on insertion order")
    void insertionOrderDoesNotAffectResults() {
        Random random = new Random(5L);
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            corpus.add(randomWord(random, 1, 7));
        }

        BKTree sorted = new BKTree();
        List<String> ascending = new ArrayList<>(corpus);
        Collections.sort(ascending);
        sorted.addAll(ascending);

        BKTree shuffled = new BKTree();
        List<String> mixed = new ArrayList<>(corpus);
        Collections.shuffle(mixed, new Random(77L));
        shuffled.addAll(mixed);

        for (String query : List.of("abc", "bcd", "aaaa", "dcba")) {
            assertThat(words(sorted.search(query, 2)))
                    .as("query '%s'", query)
                    .containsExactlyInAnyOrderElementsOf(words(shuffled.search(query, 2)));
        }
    }

    // -------------------------------------------------------------------------------------
    // Pruning has to actually prune, or the whole structure is pointless
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a distance-1 search touches only a small fraction of the tree")
    void pruningSkipsMostOfTheTree() {
        Random random = new Random(1234L);
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            corpus.add(randomWord(random, 3, 9));
        }
        Collections.shuffle(corpus, new Random(1L));

        BKTree tree = new BKTree();
        tree.addAll(corpus);

        BKTree.SearchResult result = tree.searchWithStats("abcde", 1);

        assertThat(result.distanceComputations())
                .as("a brute-force scan would need %d distance computations", tree.size())
                .isLessThan(tree.size() / 4);
        assertThat(result.nodesVisited()).isEqualTo(result.distanceComputations());
    }

    @Test
    @DisplayName("widening maxDistance costs more work -- the structure degrades, as documented")
    void widerSearchesPruneLess() {
        Random random = new Random(4321L);
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            corpus.add(randomWord(random, 3, 9));
        }
        Collections.shuffle(corpus, new Random(2L));

        BKTree tree = new BKTree();
        tree.addAll(corpus);

        int atOne = tree.searchWithStats("abcde", 1).distanceComputations();
        int atThree = tree.searchWithStats("abcde", 3).distanceComputations();

        assertThat(atThree)
                .as("the 2k+1 branching window widens with k, so more of the tree is visited")
                .isGreaterThan(atOne);
    }

    @Test
    @DisplayName("search statistics are per-call, not shared mutable state")
    void statisticsAreCallScoped() {
        BKTree tree = treeOf("apple", "apply", "ample", "banana");

        BKTree.SearchResult first = tree.searchWithStats("apple", 1);
        BKTree.SearchResult second = tree.searchWithStats("apple", 1);

        assertThat(first.distanceComputations()).isEqualTo(second.distanceComputations());
        assertThat(first.matches()).isEqualTo(second.matches());
    }

    @Test
    @DisplayName("a custom metric can be injected")
    void acceptsInjectedMetric() {
        BKTree tree = new BKTree(LevenshteinDistance::distanceFullTable);
        tree.addAll(List.of("apple", "apply", "banana"));

        assertThat(words(tree.search("aple", 1))).containsExactly("apple");
    }

    @Test
    void rejectsNullMetric() {
        assertThatThrownBy(() -> new BKTree(null)).isInstanceOf(NullPointerException.class);
    }

    /** The Phase 2 baseline, inlined here as a test oracle. */
    private static List<String> bruteForce(List<String> corpus, String query, int maxDistance) {
        return corpus.stream()
                .distinct()
                .filter(word -> LevenshteinDistance.distance(query, word) <= maxDistance)
                .toList();
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
