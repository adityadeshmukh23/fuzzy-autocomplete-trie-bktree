package com.fuzzysearch.core.search;

/**
 * Decides how many edits to tolerate for a given query.
 *
 * <p>A fixed budget is wrong at both ends. Allow 2 edits on a 3-letter query and half the
 * dictionary matches -- "cat" reaches "car", "cot", "chat", "coat", "at", "bat" and hundreds
 * more, so the results are noise. Allow only 1 edit on a 12-letter query and a user who
 * fat-fingers twice while typing "accommodation" gets nothing at all.
 *
 * <p>The budget therefore scales with query length. The thresholds are a judgement call, not a
 * derived optimum; they are collected here so there is exactly one place to change them, and
 * Phase 4 measures what each costs.
 *
 * <p>This also matters for latency: BK-tree pruning weakens sharply as the budget grows (each
 * visited node opens up to {@code 2k+1} child edges), so a length-aware budget keeps the
 * expensive case rare rather than routine.
 */
public final class FuzzyBudget {

    private FuzzyBudget() {
    }

    /**
     * @return the <b>widest</b> edit distance allowed for this query. {@link SearchPolicy} starts
     *         at 1 and only widens toward this ceiling when a narrower search came up short, so
     *         this is a cap rather than the distance actually searched.
     */
    public static int forQuery(String query) {
        int length = query == null ? 0 : query.strip().length();
        if (length <= 2) {
            return 0;   // too short to correct meaningfully: everything is "close"
        }
        if (length <= 4) {
            return 1;
        }
        return 2;
    }
}
