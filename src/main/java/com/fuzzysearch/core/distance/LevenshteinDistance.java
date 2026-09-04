package com.fuzzysearch.core.distance;

/**
 * Levenshtein edit distance, implemented from scratch three ways.
 *
 * <h2>What the distance means</h2>
 * The minimum number of single-character <b>insertions</b>, <b>deletions</b> and
 * <b>substitutions</b> needed to turn string {@code a} into string {@code b}. Each edit costs 1.
 *
 * <h2>The recurrence (the part to be able to derive from memory)</h2>
 * Let {@code D[i][j]} be the edit distance between the first {@code i} characters of {@code a}
 * and the first {@code j} characters of {@code b}. Then:
 *
 * <pre>
 *   D[0][j] = j                    // a is empty: insert all j characters of b
 *   D[i][0] = i                    // b is empty: delete all i characters of a
 *
 *   D[i][j] = min(
 *       D[i-1][j]   + 1,           // DELETE a[i-1]      (consume a, not b)
 *       D[i][j-1]   + 1,           // INSERT b[j-1]      (consume b, not a)
 *       D[i-1][j-1] + cost         // SUBSTITUTE/MATCH   (consume both)
 *   )
 *   where cost = (a[i-1] == b[j-1]) ? 0 : 1
 * </pre>
 *
 * <p>The intuition: to align {@code a[0..i)} with {@code b[0..j)} the last alignment step must
 * be one of exactly three things -- a is one longer (deletion), b is one longer (insertion), or
 * the two final characters are paired up (a match if equal, a substitution if not). Each case
 * reduces to a strictly smaller subproblem already solved, so we take the cheapest. The answer
 * is {@code D[m][n]}.
 *
 * <h2>Why three implementations</h2>
 * <ul>
 *   <li>{@link #distanceFullTable} -- the literal textbook table. Kept because it is the
 *       reference: readable, obviously correct, and the version to reason about on a whiteboard.
 *       It also exposes {@link #buildTable} so a caller can inspect or replay the DP.</li>
 *   <li>{@link #distance} -- the same recurrence with a rolling two-row buffer. Identical
 *       results, {@code O(min(m,n))} memory instead of {@code O(m*n)}. This is the hot-path
 *       version, and it is the one the BK-tree uses.</li>
 *   <li>{@link #distanceWithCutoff} -- banded + early-exit. When the caller only cares
 *       "is this within k edits?", most of the table need never be computed.</li>
 * </ul>
 * The unit tests cross-check all three against each other on random inputs, so the fast ones
 * cannot drift away from the reference.
 *
 * <h2>Known limitation: UTF-16, not codepoints</h2>
 * These operate on Java {@code char} units. Characters outside the Basic Multilingual Plane
 * (emoji, some CJK extensions) are stored as surrogate pairs and therefore count as two edits,
 * not one. For an English/product-name autocomplete corpus this never bites; fixing it would
 * mean iterating codepoints, which costs a conversion pass on every comparison. Documented
 * rather than silently wrong.
 */
public final class LevenshteinDistance {

    private LevenshteinDistance() {
    }

    // ---------------------------------------------------------------------------------------
    // 1. Reference implementation: the full O(m*n) DP table
    // ---------------------------------------------------------------------------------------

    /**
     * Textbook full-table Levenshtein distance.
     *
     * <p>Time {@code O(m*n)}, space {@code O(m*n)}.
     *
     * @return the exact edit distance between {@code a} and {@code b}
     */
    public static int distanceFullTable(String a, String b) {
        int[][] table = buildTable(a, b);
        return table[a.length()][b.length()];
    }

    /**
     * Builds and returns the complete DP table, mostly so tests (and a curious reader) can
     * inspect the intermediate state rather than just the final number.
     *
     * @return a table of size {@code (a.length()+1) x (b.length()+1)}
     */
    public static int[][] buildTable(String a, String b) {
        requireNonNulls(a, b);
        final int m = a.length();
        final int n = b.length();
        int[][] d = new int[m + 1][n + 1];

        // Base cases. Turning a prefix of length i into the empty string costs i deletions;
        // turning the empty string into a prefix of length j costs j insertions.
        for (int i = 0; i <= m; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            d[0][j] = j;
        }

        // Fill row by row. Every cell depends only on the cell above, the cell to the left, and
        // the cell diagonally up-left -- all already computed in this traversal order.
        for (int i = 1; i <= m; i++) {
            final char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                final char cb = b.charAt(j - 1);
                final int substitutionCost = (ca == cb) ? 0 : 1;

                final int deletion = d[i - 1][j] + 1;
                final int insertion = d[i][j - 1] + 1;
                final int substitution = d[i - 1][j - 1] + substitutionCost;

                d[i][j] = Math.min(Math.min(deletion, insertion), substitution);
            }
        }
        return d;
    }

    // ---------------------------------------------------------------------------------------
    // 2. Hot-path implementation: same recurrence, rolling two rows
    // ---------------------------------------------------------------------------------------

    /**
     * Exact Levenshtein distance using a rolling two-row buffer.
     *
     * <p>Time {@code O(m*n)}, space {@code O(min(m,n))}. The observation is simply that row
     * {@code i} of the table only ever reads row {@code i-1}, so keeping the whole table is
     * waste. Arguments are swapped if needed so the row width is the <em>shorter</em> of the
     * two strings.
     *
     * <p>This is the metric the BK-tree is built on. It must return the <b>exact</b> distance
     * (see {@link #distanceWithCutoff} for why the cheaper cutoff variant cannot be used there).
     */
    public static int distance(String a, String b) {
        requireNonNulls(a, b);

        // Cheap exits before touching any array.
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }

        // Symmetry lets us put the shorter string on the inner axis, minimising allocation.
        if (a.length() < b.length()) {
            String swap = a;
            a = b;
            b = swap;
        }

        final int m = a.length();
        final int n = b.length();

        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];

        // previous = row 0 of the table: distance from "" to each prefix of b.
        for (int j = 0; j <= n; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            current[0] = i;                       // distance from a[0..i) to ""
            final char ca = a.charAt(i - 1);

            for (int j = 1; j <= n; j++) {
                final int substitutionCost = (ca == b.charAt(j - 1)) ? 0 : 1;

                final int deletion = previous[j] + 1;
                final int insertion = current[j - 1] + 1;
                final int substitution = previous[j - 1] + substitutionCost;

                current[j] = Math.min(Math.min(deletion, insertion), substitution);
            }

            // Row i becomes row i-1 for the next iteration. Swapping references beats copying.
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[n];
    }

    // ---------------------------------------------------------------------------------------
    // 3. Bounded implementation: banded DP with early exit
    // ---------------------------------------------------------------------------------------

    /**
     * Answers "is the distance at most {@code maxDistance}?" far more cheaply than computing
     * the full distance.
     *
     * <p><b>Contract:</b> returns the exact distance when it is {@code <= maxDistance}, and
     * {@code maxDistance + 1} (a sentinel meaning "further than you asked about") otherwise.
     * The exact value beyond the threshold is never computed, and callers must not read the
     * sentinel as a real distance.
     *
     * <p>Two independent optimisations:
     * <ol>
     *   <li><b>Length filter.</b> {@code |m - n|} is a lower bound on the distance -- you cannot
     *       reconcile a length difference with fewer than that many insertions or deletions. If
     *       the lengths differ by more than {@code maxDistance}, bail before any work.</li>
     *   <li><b>Banding.</b> Every step away from the main diagonal costs at least one edit, so
     *       an alignment of total cost {@code <= k} can never stray further than {@code k} cells
     *       from the diagonal. Only cells with {@code |i - j| <= maxDistance} can matter; the
     *       rest are treated as "beyond the threshold". That turns {@code O(m*n)} into
     *       {@code O(m * (2k+1))}, which for {@code k = 2} is effectively linear.</li>
     * </ol>
     * A third exit falls out for free: if every cell in a row already exceeds the threshold,
     * no later row can come back under it (costs only ever increase downward), so we stop.
     *
     * <p><b>Why the BK-tree cannot use this.</b> BK-tree pruning needs the <em>exact</em>
     * distance from the query to every node it visits, because that number defines the window
     * of child edges worth descending into. Feeding it a clamped value would compute the wrong
     * window and silently drop valid matches. This variant is for the brute-force scan (Phase
     * 2), where only the yes/no answer is needed -- which, note, makes the naive baseline
     * genuinely fast and therefore a fair opponent in Phase 4.
     */
    public static int distanceWithCutoff(String a, String b, int maxDistance) {
        requireNonNulls(a, b);
        if (maxDistance < 0) {
            throw new IllegalArgumentException("maxDistance must be >= 0, got " + maxDistance);
        }

        final int beyond = maxDistance + 1;   // the "too far" sentinel

        if (a.equals(b)) {
            return 0;
        }
        // Optimisation 1: the length gap alone already exceeds the budget.
        if (Math.abs(a.length() - b.length()) > maxDistance) {
            return beyond;
        }
        if (a.isEmpty()) {
            return b.length() <= maxDistance ? b.length() : beyond;
        }
        if (b.isEmpty()) {
            return a.length() <= maxDistance ? a.length() : beyond;
        }

        if (a.length() < b.length()) {
            String swap = a;
            a = b;
            b = swap;
        }

        final int m = a.length();
        final int n = b.length();

        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];

        // Row 0, clamped: anything already past the budget is recorded as the sentinel so it
        // can never be read back as a usable value.
        for (int j = 0; j <= n; j++) {
            previous[j] = (j <= maxDistance) ? j : beyond;
        }

        for (int i = 1; i <= m; i++) {
            // Optimisation 2: this row's band of possibly-relevant columns.
            final int lo = Math.max(1, i - maxDistance);
            final int hi = Math.min(n, i + maxDistance);

            current[0] = (i <= maxDistance) ? i : beyond;

            // Sentinel immediately left of the band. Cells further left are never read: next
            // row's band starts at lo or lo+1, so it reads at worst column lo-1.
            if (lo - 1 >= 1) {
                current[lo - 1] = beyond;
            }

            int rowMin = beyond;
            for (int j = lo; j <= hi; j++) {
                final int substitutionCost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;

                final int deletion = previous[j] + 1;
                final int insertion = current[j - 1] + 1;
                final int substitution = previous[j - 1] + substitutionCost;

                // Clamp at the sentinel so arithmetic on "beyond" values cannot drift upward
                // and eventually overflow.
                final int best = Math.min(Math.min(deletion, insertion), substitution);
                current[j] = Math.min(best, beyond);

                if (current[j] < rowMin) {
                    rowMin = current[j];
                }
            }

            // Sentinel immediately right of the band, for the same reason as the left one.
            if (hi + 1 <= n) {
                current[hi + 1] = beyond;
            }

            // Optimisation 3: the cheapest alignment in this row already blew the budget, and
            // distances never decrease as we go down, so nothing below can qualify.
            if (rowMin > maxDistance) {
                return beyond;
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[n] <= maxDistance ? previous[n] : beyond;
    }

    /**
     * @return true if {@code a} and {@code b} are within {@code maxDistance} edits of each other
     */
    public static boolean isWithin(String a, String b, int maxDistance) {
        return distanceWithCutoff(a, b, maxDistance) <= maxDistance;
    }

    private static void requireNonNulls(String a, String b) {
        if (a == null || b == null) {
            throw new NullPointerException("both strings must be non-null");
        }
    }
}
