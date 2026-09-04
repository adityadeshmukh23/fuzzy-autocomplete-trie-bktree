package com.fuzzysearch.core.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LevenshteinDistanceTest {

    /**
     * Asserts the expected distance against all three implementations at once, plus symmetry.
     * Keeping this in one helper is what stops the fast variants drifting from the reference.
     */
    private static void assertDistance(String a, String b, int expected) {
        assertThat(LevenshteinDistance.distanceFullTable(a, b))
                .as("full table: '%s' -> '%s'", a, b).isEqualTo(expected);
        assertThat(LevenshteinDistance.distance(a, b))
                .as("rolling rows: '%s' -> '%s'", a, b).isEqualTo(expected);

        int generousBudget = Math.max(a.length(), b.length()) + 1;
        assertThat(LevenshteinDistance.distanceWithCutoff(a, b, generousBudget))
                .as("cutoff: '%s' -> '%s'", a, b).isEqualTo(expected);

        assertThat(LevenshteinDistance.distance(b, a))
                .as("symmetry: '%s' -> '%s'", b, a).isEqualTo(expected);
    }

    // -------------------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("both strings empty -> 0")
    void bothEmpty() {
        assertDistance("", "", 0);
    }

    @Test
    @DisplayName("empty vs non-empty -> the other string's length")
    void oneEmpty() {
        assertDistance("", "apple", 5);
        assertDistance("apple", "", 5);
    }

    @Test
    @DisplayName("identical strings -> 0")
    void identical() {
        assertDistance("apple", "apple", 0);
        assertDistance("a", "a", 0);
    }

    @Test
    @DisplayName("completely dissimilar, equal length -> length (all substitutions)")
    void completelyDissimilar() {
        assertDistance("abc", "xyz", 3);
    }

    // -------------------------------------------------------------------------------------
    // The three single-edit operations, isolated
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("one deletion -> 1")
    void singleDeletion() {
        assertDistance("apple", "aple", 1);   // the project's motivating typo
    }

    @Test
    @DisplayName("one insertion -> 1")
    void singleInsertion() {
        assertDistance("aple", "apple", 1);
    }

    @Test
    @DisplayName("one substitution -> 1")
    void singleSubstitution() {
        assertDistance("apple", "applf", 1);
    }

    // -------------------------------------------------------------------------------------
    // Classic worked examples -- the ones to be able to reproduce on a whiteboard
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("kitten -> sitting is 3")
    void kittenSitting() {
        // k->s substitute, e->i substitute, append g.
        assertDistance("kitten", "sitting", 3);
    }

    @Test
    @DisplayName("saturday -> sunday is 3")
    void saturdaySunday() {
        assertDistance("saturday", "sunday", 3);
    }

    @Test
    @DisplayName("flaw -> lawn is 2")
    void flawLawn() {
        assertDistance("flaw", "lawn", 2);
    }

    // -------------------------------------------------------------------------------------
    // Transposition: documenting a deliberate design decision, not a bug
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a transposition costs 2, because plain Levenshtein has no transposition edit")
    void transpositionCostsTwo() {
        // "teh" -> "the" is one of the most common real typos, and a human reads it as a single
        // slip. Plain Levenshtein charges 2 (substitute e->h, substitute h->e) because its edit
        // alphabet is insert/delete/substitute only.
        //
        // The obvious fix -- "optimal string alignment", which adds an adjacent-swap edit -- is
        // NOT usable here: it is not a metric, so it would break BK-tree pruning. See
        // OsaTriangleInequalityTest for the counterexample that proves it.
        assertDistance("teh", "the", 2);
        assertDistance("recieve", "receive", 2);
    }

    // -------------------------------------------------------------------------------------
    // Non-ASCII behaviour, documented rather than assumed
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("accented characters are single chars and cost one edit")
    void accentedCharacters() {
        assertDistance("café", "cafe", 1);
    }

    @Test
    @DisplayName("known limitation: a non-BMP character is two UTF-16 units, so it costs two")
    void nonBmpCharacterCostsTwoEdits() {
        // U+1F600 GRINNING FACE is a surrogate pair. This documents the limitation stated in
        // the class javadoc: the implementation works on chars, not codepoints.
        String emoji = "😀";
        assertDistance(emoji, "", 2);
    }

    // -------------------------------------------------------------------------------------
    // The cutoff variant's contract
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("cutoff returns the exact distance when within budget")
    void cutoffExactWithinBudget() {
        assertThat(LevenshteinDistance.distanceWithCutoff("apple", "aple", 2)).isEqualTo(1);
        assertThat(LevenshteinDistance.distanceWithCutoff("kitten", "sitting", 3)).isEqualTo(3);
    }

    @Test
    @DisplayName("cutoff returns the maxDistance+1 sentinel when over budget")
    void cutoffSentinelBeyondBudget() {
        assertThat(LevenshteinDistance.distanceWithCutoff("kitten", "sitting", 2)).isEqualTo(3);
        assertThat(LevenshteinDistance.distanceWithCutoff("abc", "xyz", 1)).isEqualTo(2);
        assertThat(LevenshteinDistance.distanceWithCutoff("abc", "xyz", 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("cutoff with budget 0 is an equality test")
    void cutoffZeroBudget() {
        assertThat(LevenshteinDistance.distanceWithCutoff("apple", "apple", 0)).isEqualTo(0);
        assertThat(LevenshteinDistance.distanceWithCutoff("apple", "apples", 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("the length filter rejects wildly different lengths without doing DP work")
    void cutoffLengthFilter() {
        assertThat(LevenshteinDistance.distanceWithCutoff("a", "abcdefghij", 2)).isEqualTo(3);
    }

    @Test
    @DisplayName("isWithin agrees with the distance")
    void isWithin() {
        assertThat(LevenshteinDistance.isWithin("apple", "aple", 1)).isTrue();
        assertThat(LevenshteinDistance.isWithin("apple", "aple", 0)).isFalse();
        assertThat(LevenshteinDistance.isWithin("apple", "apple", 0)).isTrue();
    }

    // -------------------------------------------------------------------------------------
    // Property tests: the strongest evidence that the fast paths match the reference
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("all three implementations agree on 5000 random pairs")
    void implementationsAgreeOnRandomInput() {
        Random random = new Random(20260904L);   // fixed seed: reproducible failures
        for (int i = 0; i < 5_000; i++) {
            String a = randomWord(random, 0, 12);
            String b = randomWord(random, 0, 12);

            int reference = LevenshteinDistance.distanceFullTable(a, b);
            assertThat(LevenshteinDistance.distance(a, b))
                    .as("rolling vs full table for '%s' / '%s'", a, b).isEqualTo(reference);

            // Sweep the budget across, at, and beyond the true distance.
            for (int budget = 0; budget <= reference + 2; budget++) {
                int cutoff = LevenshteinDistance.distanceWithCutoff(a, b, budget);
                if (reference <= budget) {
                    assertThat(cutoff)
                            .as("cutoff should be exact within budget %d for '%s' / '%s'", budget, a, b)
                            .isEqualTo(reference);
                } else {
                    assertThat(cutoff)
                            .as("cutoff should report the sentinel at budget %d for '%s' / '%s'", budget, a, b)
                            .isEqualTo(budget + 1);
                }
            }
        }
    }

    @Test
    @DisplayName("the metric axioms hold on random input")
    void satisfiesMetricAxioms() {
        Random random = new Random(11235L);
        for (int i = 0; i < 3_000; i++) {
            String a = randomWord(random, 0, 10);
            String b = randomWord(random, 0, 10);
            String c = randomWord(random, 0, 10);

            int ab = LevenshteinDistance.distance(a, b);
            int bc = LevenshteinDistance.distance(b, c);
            int ac = LevenshteinDistance.distance(a, c);

            assertThat(ab).as("non-negativity").isGreaterThanOrEqualTo(0);
            assertThat(LevenshteinDistance.distance(b, a)).as("symmetry").isEqualTo(ab);
            assertThat(ab == 0).as("identity of indiscernibles").isEqualTo(a.equals(b));

            // This is the axiom the BK-tree's pruning is built on.
            assertThat(ac)
                    .as("triangle inequality: d(%s,%s)=%d must be <= %d + %d", a, c, ac, ab, bc)
                    .isLessThanOrEqualTo(ab + bc);
        }
    }

    // -------------------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------------------

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> LevenshteinDistance.distance(null, "a"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LevenshteinDistance.distance("a", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LevenshteinDistance.distanceFullTable(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNegativeMaxDistance() {
        assertThatThrownBy(() -> LevenshteinDistance.distanceWithCutoff("a", "b", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDistance");
    }

    @Test
    @DisplayName("buildTable exposes the DP table with correct base cases")
    void buildTableBaseCases() {
        int[][] table = LevenshteinDistance.buildTable("ab", "xyz");
        assertThat(table).hasDimensions(3, 4);
        assertThat(table[0]).containsExactly(0, 1, 2, 3);   // "" -> prefixes of "xyz"
        assertThat(table[1][0]).isEqualTo(1);               // "a" -> ""
        assertThat(table[2][0]).isEqualTo(2);               // "ab" -> ""
        assertThat(table[2][3]).isEqualTo(3);
    }

    private static String randomWord(Random random, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(6)));   // small alphabet -> more near-misses
        }
        return sb.toString();
    }
}
