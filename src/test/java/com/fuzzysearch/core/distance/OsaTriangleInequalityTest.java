package com.fuzzysearch.core.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, rather than asserts, why this project uses plain Levenshtein instead of the popular
 * "Damerau-lite" variant that handles transpositions.
 *
 * <p><b>The temptation.</b> Plain Levenshtein charges 2 for "teh" -> "the", even though a human
 * sees one slip. Adding a single adjacent-transposition edit fixes that, and the cheap way to do
 * it -- <b>optimal string alignment (OSA)</b>, also called restricted Damerau-Levenshtein -- is
 * four extra lines in the DP loop. Most tutorials stop there.
 *
 * <p><b>The problem.</b> OSA restricts each substring to being edited at most once, and that
 * restriction breaks the triangle inequality. OSA is therefore <em>not a metric</em>, and the
 * BK-tree's entire pruning argument is derived from the triangle inequality. Dropping OSA into
 * the BK-tree would not throw or crash -- it would silently return incomplete results, because
 * the search would prune branches that actually contain matches. That is the worst kind of bug:
 * invisible, data-dependent, and correct-looking in a demo.
 *
 * <p>The test below pins the concrete counterexample and confirms Levenshtein does not have the
 * same flaw. If someone later "improves" the metric, this test tells them why not to.
 *
 * <p><b>The alternative that would work:</b> true (unrestricted) Damerau-Levenshtein is a proper
 * metric and would be safe in the BK-tree. It is meaningfully more complex -- it needs a last-
 * occurrence table over the alphabet -- so it was left out of scope, deliberately, not by
 * oversight.
 */
class OsaTriangleInequalityTest {

    @Test
    @DisplayName("OSA violates the triangle inequality, so it cannot back a BK-tree")
    void osaViolatesTriangleInequality() {
        String a = "CA";
        String b = "AC";
        String c = "ABC";

        int ab = osa(a, b);   // one transposition
        int bc = osa(b, c);   // one insertion
        int ac = osa(a, c);

        assertThat(ab).isEqualTo(1);
        assertThat(bc).isEqualTo(1);
        assertThat(ac).isEqualTo(3);

        // d(a,c) <= d(a,b) + d(b,c)  would require  3 <= 2.  It does not hold.
        assertThat(ac)
                .as("OSA: d(CA,ABC)=%d is greater than d(CA,AC)+d(AC,ABC)=%d", ac, ab + bc)
                .isGreaterThan(ab + bc);
    }

    @Test
    @DisplayName("plain Levenshtein satisfies the triangle inequality on the same counterexample")
    void levenshteinSurvivesTheSameCase() {
        int ab = LevenshteinDistance.distance("CA", "AC");     // 2, no transposition edit
        int bc = LevenshteinDistance.distance("AC", "ABC");    // 1
        int ac = LevenshteinDistance.distance("CA", "ABC");    // 3

        assertThat(ac).isLessThanOrEqualTo(ab + bc);
    }

    /**
     * Optimal string alignment distance, implemented here in the test only -- it is deliberately
     * absent from production code. Same recurrence as Levenshtein plus one extra case: if the
     * last two characters of each string are each other's swap, an adjacent transposition may be
     * charged as a single edit.
     */
    private static int osa(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[][] d = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            d[0][j] = j;
        }

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);

                // The transposition case, and the source of the broken axiom: it looks back two
                // cells, which quietly assumes the swapped pair was not already edited.
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[m][n];
    }
}
