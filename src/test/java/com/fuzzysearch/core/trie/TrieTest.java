package com.fuzzysearch.core.trie;

import com.fuzzysearch.core.rank.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrieTest {

    private static Trie trieOf(String... words) {
        Trie trie = new Trie();
        for (int i = 0; i < words.length; i++) {
            trie.insert(words[i], words.length - i);   // earlier words weigh more
        }
        return trie;
    }

    private static List<String> words(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::word).toList();
    }

    // -------------------------------------------------------------------------------------
    // Insertion and membership
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("inserted words are found, others are not")
    void insertAndContains() {
        Trie trie = trieOf("apple", "application", "banana");

        assertThat(trie.contains("apple")).isTrue();
        assertThat(trie.contains("banana")).isTrue();
        assertThat(trie.contains("app")).as("a prefix is not a word").isFalse();
        assertThat(trie.contains("applesauce")).isFalse();
        assertThat(trie.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("hasPrefix distinguishes 'is a path' from 'is a word'")
    void hasPrefix() {
        Trie trie = trieOf("apple");

        assertThat(trie.hasPrefix("app")).isTrue();
        assertThat(trie.contains("app")).isFalse();
        assertThat(trie.hasPrefix("axe")).isFalse();
    }

    @Test
    @DisplayName("re-inserting a word merges weights rather than duplicating it")
    void reinsertMergesWeight() {
        Trie trie = new Trie();
        trie.insert("apple", 10);
        trie.insert("apple", 5);

        assertThat(trie.size()).isEqualTo(1);
        assertThat(trie.weightOf("apple")).isEqualTo(15);
    }

    @Test
    @DisplayName("shared prefixes share nodes")
    void sharedPrefixesShareNodes() {
        Trie trie = new Trie();
        trie.insert("car", 1);
        int afterFirst = trie.nodeCount();

        trie.insert("cart", 1);
        // "cart" only needs one new node -- 'c','a','r' already exist.
        assertThat(trie.nodeCount()).isEqualTo(afterFirst + 1);
    }

    // -------------------------------------------------------------------------------------
    // Case policy
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("lookup is case-insensitive in both directions")
    void caseInsensitiveLookup() {
        Trie trie = new Trie();
        trie.insert("iPhone", 100);

        assertThat(trie.contains("iphone")).isTrue();
        assertThat(trie.contains("IPHONE")).isTrue();
        assertThat(words(trie.topKWithPrefix("IPH", 5))).containsExactly("iPhone");
    }

    @Test
    @DisplayName("the original spelling is preserved for display")
    void preservesDisplayCasing() {
        Trie trie = new Trie();
        trie.insert("iPhone", 100);

        assertThat(words(trie.topKWithPrefix("ip", 5))).containsExactly("iPhone");
    }

    @Test
    @DisplayName("differently cased spellings merge, and the heaviest spelling is displayed")
    void caseCollisionMergesAndPicksHeaviestSpelling() {
        Trie trie = new Trie();
        trie.insert("Apple", 30);
        trie.insert("apple", 70);
        trie.insert("APPLE", 5);

        assertThat(trie.size()).as("one entry, not three").isEqualTo(1);
        assertThat(trie.weightOf("apple")).as("weights summed").isEqualTo(105);
        assertThat(words(trie.topKWithPrefix("app", 5)))
                .as("display form is the spelling with the largest single contribution")
                .containsExactly("apple");
    }

    // -------------------------------------------------------------------------------------
    // Prefix search: 0 / 1 / many matches
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("no matches -> empty list")
    void zeroMatches() {
        Trie trie = trieOf("apple", "banana");

        assertThat(trie.topKWithPrefix("z", 10)).isEmpty();
        assertThat(trie.allWithPrefix("z")).isEmpty();
        assertThat(trie.topKWithPrefix("applesauce", 10)).isEmpty();
    }

    @Test
    @DisplayName("exactly one match")
    void oneMatch() {
        Trie trie = trieOf("apple", "banana");

        assertThat(words(trie.topKWithPrefix("ban", 10))).containsExactly("banana");
    }

    @Test
    @DisplayName("many matches come back ordered by weight, highest first")
    void manyMatchesRankedByWeight() {
        Trie trie = new Trie();
        trie.insert("car", 50);
        trie.insert("cart", 900);
        trie.insert("carpet", 300);
        trie.insert("carbon", 10);
        trie.insert("dog", 10_000);   // not under the prefix

        assertThat(words(trie.topKWithPrefix("car", 10)))
                .containsExactly("cart", "carpet", "car", "carbon");
    }

    @Test
    @DisplayName("a prefix that is itself a word is included in its own results")
    void prefixThatIsAlsoAWord() {
        Trie trie = new Trie();
        trie.insert("app", 500);
        trie.insert("apple", 100);

        assertThat(words(trie.topKWithPrefix("app", 10))).containsExactly("app", "apple");
    }

    @Test
    @DisplayName("an empty prefix returns the global top K")
    void emptyPrefixReturnsGlobalTopK() {
        Trie trie = new Trie();
        trie.insert("zebra", 900);
        trie.insert("apple", 100);
        trie.insert("mango", 500);

        assertThat(words(trie.topKWithPrefix("", 2))).containsExactly("zebra", "mango");
    }

    @Test
    @DisplayName("surrounding whitespace in the query is ignored")
    void queryIsTrimmed() {
        Trie trie = trieOf("apple");

        assertThat(words(trie.topKWithPrefix("  app  ", 5))).containsExactly("apple");
    }

    // -------------------------------------------------------------------------------------
    // K handling and tie-breaking
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("k larger than the match count returns everything")
    void kLargerThanMatches() {
        Trie trie = trieOf("car", "cart");

        assertThat(trie.topKWithPrefix("car", 100)).hasSize(2);
    }

    @Test
    @DisplayName("k <= 0 returns empty")
    void nonPositiveK() {
        Trie trie = trieOf("car", "cart");

        assertThat(trie.topKWithPrefix("car", 0)).isEmpty();
        assertThat(trie.topKWithPrefix("car", -5)).isEmpty();
    }

    @Test
    @DisplayName("equal weights break by shorter word, then lexicographically")
    void tieBreaking() {
        Trie trie = new Trie();
        trie.insert("carpentry", 100);
        trie.insert("carpet", 100);
        trie.insert("cars", 100);
        trie.insert("card", 100);

        assertThat(words(trie.topKWithPrefix("car", 10)))
                .containsExactly("card", "cars", "carpet", "carpentry");
    }

    @Test
    @DisplayName("a tie that spans the K boundary still resolves deterministically")
    void tieAcrossTheKBoundary() {
        Trie trie = new Trie();
        trie.insert("carpentry", 100);
        trie.insert("carpet", 100);
        trie.insert("cars", 100);
        trie.insert("card", 100);

        // This is the case the "expand nodes before emitting words" rule exists for: "card" and
        // "cars" sit under an unexpanded subtree whose bound ties with words already queued.
        assertThat(words(trie.topKWithPrefix("car", 2))).containsExactly("card", "cars");
    }

    @Test
    @DisplayName("zero-weight words are still indexed and returned")
    void zeroWeightWords() {
        Trie trie = new Trie();
        trie.insert("apple", 0);
        trie.insert("apricot", 0);

        assertThat(words(trie.topKWithPrefix("ap", 10))).containsExactly("apple", "apricot");
    }

    // -------------------------------------------------------------------------------------
    // The oracle test: best-first must agree with collect-everything-then-sort
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("best-first top-K equals the exhaustive ranking, truncated, on random corpora")
    void bestFirstMatchesExhaustiveOracle() {
        Random random = new Random(20260904L);

        for (int trial = 0; trial < 200; trial++) {
            Trie trie = new Trie();
            List<String> corpus = new ArrayList<>();
            int wordCount = 1 + random.nextInt(300);

            for (int i = 0; i < wordCount; i++) {
                String word = randomWord(random, 1, 6);
                corpus.add(word);
                // Small weight range so ties are common and the tie-break path is exercised.
                trie.insert(word, random.nextInt(4));
            }

            for (int probe = 0; probe < 10; probe++) {
                String prefix = probe == 0 ? "" : randomWord(random, 0, 3);
                int k = random.nextInt(12);

                List<Candidate> expected = trie.allWithPrefix(prefix).stream().limit(k).toList();
                List<Candidate> actual = trie.topKWithPrefix(prefix, k);

                assertThat(actual)
                        .as("trial %d, prefix '%s', k=%d", trial, prefix, k)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("results are identical regardless of insertion order")
    void insertionOrderDoesNotAffectResults() {
        List<String> corpus = new ArrayList<>(
                List.of("car", "cart", "carpet", "carbon", "care", "cargo", "carve"));

        Trie forward = new Trie();
        for (int i = 0; i < corpus.size(); i++) {
            forward.insert(corpus.get(i), i % 3);
        }

        Trie shuffled = new Trie();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, new Random(99L));
        for (int i : order) {
            shuffled.insert(corpus.get(i), i % 3);
        }

        assertThat(forward.topKWithPrefix("car", 10)).isEqualTo(shuffled.topKWithPrefix("car", 10));
    }

    // -------------------------------------------------------------------------------------
    // The performance claim, measured rather than asserted
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("top-K cost is driven by K, not by how many words sit under the prefix")
    void bestFirstIsOutputSensitive() {
        // 20,000 words all sharing the prefix "a" -- the pathological search-as-you-type case,
        // where the user has typed one letter and expects ten suggestions.
        Trie trie = new Trie();
        Random random = new Random(31L);
        for (int i = 0; i < 20_000; i++) {
            trie.insert("a" + i, random.nextInt(1_000_000));
        }

        int subtreeSize = trie.allWithPrefix("a").size();
        Trie.PrefixSearchResult result = trie.searchPrefixWithStats("a", 10);

        assertThat(result.results()).hasSize(10);
        assertThat(subtreeSize).isEqualTo(20_000);

        // The exhaustive implementation must touch all 20,000. Best-first opens a tiny fraction.
        assertThat(result.nodesExpanded())
                .as("expanded %d of %d words' worth of subtree", result.nodesExpanded(), subtreeSize)
                .isLessThan(subtreeSize / 100);
    }

    @Test
    @DisplayName("asking for more results costs proportionally more, as the complexity implies")
    void costScalesWithK() {
        Trie trie = new Trie();
        Random random = new Random(17L);
        for (int i = 0; i < 20_000; i++) {
            trie.insert("a" + i, random.nextInt(1_000_000));
        }

        int atTen = trie.searchPrefixWithStats("a", 10).nodesExpanded();
        int atFiveHundred = trie.searchPrefixWithStats("a", 500).nodesExpanded();

        assertThat(atFiveHundred).isGreaterThan(atTen);
        assertThat(atFiveHundred).isLessThan(20_000);
    }

    // -------------------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------------------

    @Test
    void rejectsNullWord() {
        assertThatThrownBy(() -> new Trie().insert(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankWord() {
        assertThatThrownBy(() -> new Trie().insert("   ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new Trie().insert("apple", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");
    }

    private static String randomWord(Random random, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(4)));
        }
        return sb.toString();
    }
}
