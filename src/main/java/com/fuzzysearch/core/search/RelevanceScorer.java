package com.fuzzysearch.core.search;

/**
 * Turns (match type, edit distance, corpus frequency) into a single relevance score in [0, 1].
 *
 * <p><b>Both search services share this class.</b> That is deliberate and it is what makes the
 * Phase 4 benchmark meaningful: the naive and optimised implementations differ only in how they
 * <em>find</em> candidates, never in how they rank them. If each had its own scoring, a
 * performance difference could be ranking policy rather than data structures, and the two could
 * not be asserted to produce identical output.
 *
 * <h2>The formula</h2>
 * <pre>
 *   score = 0.7 * matchQuality + 0.3 * popularity
 *
 *   matchQuality(PREFIX)    = 1.0
 *   matchQuality(FUZZY, d)  = 1 / (1 + d)          // d=1 -&gt; 0.50, d=2 -&gt; 0.33
 *   popularity(w)           = log10(1 + w) / log10(1 + maxWeight)
 * </pre>
 *
 * <h2>Why log for popularity</h2>
 * Word frequencies are Zipfian -- "the" outweighs a mid-frequency word by four or five orders of
 * magnitude. Used raw, popularity would swamp every other signal and the top of every result list
 * would be the same handful of stopwords. The log compresses that range into something a 0.3
 * weight can actually balance against.
 *
 * <h2>What the weights actually guarantee (and what they do not)</h2>
 * With 0.7/0.3 the score bands are:
 * <pre>
 *   PREFIX      [0.70, 1.00]
 *   FUZZY d=1   [0.35, 0.65]
 *   FUZZY d=2   [0.23, 0.53]
 *   FUZZY d=3   [0.18, 0.48]
 * </pre>
 *
 * <p><b>Guaranteed: every prefix match outranks every fuzzy match.</b> The prefix band starts at
 * 0.70 and the best possible fuzzy score is 0.65. That is the right default for
 * search-as-you-type -- what the user literally typed is strong evidence of intent, and demoting
 * a literal prefix match beneath a guess feels broken. The guarantee holds only while
 * {@code POPULARITY_WEIGHT < 1/3}; at 0.3 the headroom is thin, so
 * {@code RelevanceScorerTest.prefixTierNeverOverlapsFuzzyTier} pins it.
 *
 * <p><b>Not guaranteed: that a closer fuzzy match always beats a more distant one.</b> The fuzzy
 * bands overlap, so a sufficiently more popular distance-2 word can outrank an obscure
 * distance-1 word. This is deliberate, not an oversight. Strict separation across all distances
 * is not achievable at any useful popularity weight: the match-quality gap shrinks as distance
 * grows ({@code 1/3 - 1/4 = 0.083} between d=2 and d=3), which would force
 * {@code POPULARITY_WEIGHT} below 0.08 and make corpus frequency almost irrelevant to ranking.
 * Given the choice, a meaningful popularity signal is worth more than a tier boundary nobody
 * would notice.
 *
 * <p>The crossover is narrow in practice: overtaking a distance-1 match from distance 2 needs a
 * normalized popularity gap above 0.39, which on a Zipfian corpus means roughly three orders of
 * magnitude more frequency.
 *
 * <h2>A known limitation worth being able to state</h2>
 * Edit distance does not model how typos actually happen. Given the misspelling "recieve",
 * "relieve" is 1 edit away and "receive" is 2, so this scorer ranks the wrong word first unless
 * the frequency gap is enormous -- and it is not. Fixing this properly needs a distance that
 * knows transpositions are common (true Damerau-Levenshtein) or that keyboard-adjacent
 * substitutions are cheap, not a different set of weights. Out of scope here, and named rather
 * than hidden.
 *
 * <p><b>Rejected alternative:</b> making match type a hard sort key ahead of score, rather than
 * folding it into the score. That would restore strict tiers everywhere, but it means two
 * competing definitions of "better" -- one in the comparator, one in the scorer -- and the
 * frontend could no longer explain a result's position from its score alone.
 *
 * <p><b>Also rejected:</b> a prefix-coverage term rewarding "app" -&gt; "app" over "app" -&gt;
 * "application". The shorter-word tie-break in {@link SearchResult#BETTER_FIRST} already handles
 * this whenever scores tie, without another tuning constant to defend.
 */
public final class RelevanceScorer {

    private static final double MATCH_QUALITY_WEIGHT = 0.7;
    private static final double POPULARITY_WEIGHT = 0.3;

    private final double logMaxWeight;

    /**
     * @param maxWeight the largest frequency in the corpus, used to normalise popularity into
     *                  [0, 1]. A corpus where everything has weight 0 scores popularity 0.
     */
    public RelevanceScorer(long maxWeight) {
        if (maxWeight < 0) {
            throw new IllegalArgumentException("maxWeight must be >= 0, got " + maxWeight);
        }
        this.logMaxWeight = Math.log10(1.0 + maxWeight);
    }

    public double score(MatchType matchType, int editDistance, long weight) {
        return MATCH_QUALITY_WEIGHT * matchQuality(matchType, editDistance)
                + POPULARITY_WEIGHT * popularity(weight);
    }

    private static double matchQuality(MatchType matchType, int editDistance) {
        if (matchType == MatchType.PREFIX) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.max(0, editDistance));
    }

    private double popularity(long weight) {
        if (logMaxWeight <= 0.0) {
            return 0.0;   // uniform or empty corpus: popularity carries no information
        }
        double normalized = Math.log10(1.0 + Math.max(0L, weight)) / logMaxWeight;
        return Math.min(1.0, normalized);
    }
}
