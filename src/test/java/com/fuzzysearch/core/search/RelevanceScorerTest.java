package com.fuzzysearch.core.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelevanceScorerTest {

    private static final long MAX_WEIGHT = 1_000_000L;
    private final RelevanceScorer scorer = new RelevanceScorer(MAX_WEIGHT);

    @Test
    @DisplayName("scores stay within [0, 1]")
    void scoresAreBounded() {
        assertThat(scorer.score(MatchType.PREFIX, 0, MAX_WEIGHT)).isBetween(0.0, 1.0);
        assertThat(scorer.score(MatchType.FUZZY, 5, 0)).isBetween(0.0, 1.0);
        assertThat(scorer.score(MatchType.PREFIX, 0, 0)).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("the least popular prefix match still beats the most popular fuzzy match")
    void prefixTierNeverOverlapsFuzzyTier() {
        double worstPrefix = scorer.score(MatchType.PREFIX, 0, 0);
        double bestFuzzy = scorer.score(MatchType.FUZZY, 1, MAX_WEIGHT);

        // This is the tiering guarantee the whole ranking story rests on. If someone retunes the
        // weights and breaks it, the UX changes meaningfully -- so it is pinned here.
        assertThat(worstPrefix).isGreaterThan(bestFuzzy);
    }

    @Test
    @DisplayName("all else equal, a closer fuzzy match outranks a more distant one")
    void closerFuzzyMatchesRankHigherAtEqualPopularity() {
        assertThat(scorer.score(MatchType.FUZZY, 1, 5_000))
                .isGreaterThan(scorer.score(MatchType.FUZZY, 2, 5_000));
        assertThat(scorer.score(MatchType.FUZZY, 2, 5_000))
                .isGreaterThan(scorer.score(MatchType.FUZZY, 3, 5_000));
    }

    @Test
    @DisplayName("the fuzzy bands overlap: a far more popular distant match can win. By design.")
    void fuzzyTiersOverlapDeliberately() {
        // Unlike the prefix/fuzzy boundary, distance tiers are NOT strictly separated, and this
        // test documents that rather than pretending otherwise. Enforcing separation across all
        // distances would need POPULARITY_WEIGHT below 0.08 (the d=2 to d=3 quality gap is only
        // 1/3 - 1/4 = 0.083), which would make corpus frequency almost irrelevant to ranking.
        assertThat(scorer.score(MatchType.FUZZY, 2, MAX_WEIGHT))
                .isGreaterThan(scorer.score(MatchType.FUZZY, 1, 0));

        // The crossover is narrow though: it takes an enormous frequency gap, not a modest one.
        assertThat(scorer.score(MatchType.FUZZY, 2, 10_000))
                .isLessThan(scorer.score(MatchType.FUZZY, 1, 1_000));
    }

    @Test
    @DisplayName("the prefix guarantee survives only while POPULARITY_WEIGHT stays under 1/3")
    void prefixGuaranteeHasLittleHeadroom() {
        // Pinning the margin, because it is thinner than it looks: raising POPULARITY_WEIGHT
        // from 0.3 to 0.35 would silently let popular fuzzy matches outrank literal prefix
        // matches, changing the product's behaviour with no test failing anywhere else.
        double margin = scorer.score(MatchType.PREFIX, 0, 0)
                - scorer.score(MatchType.FUZZY, 1, MAX_WEIGHT);

        assertThat(margin).isGreaterThan(0.0).isLessThan(0.10);
    }

    @Test
    @DisplayName("within a tier, popularity decides")
    void popularityOrdersWithinATier() {
        assertThat(scorer.score(MatchType.PREFIX, 0, 500_000))
                .isGreaterThan(scorer.score(MatchType.PREFIX, 0, 1_000));
        assertThat(scorer.score(MatchType.FUZZY, 2, 500_000))
                .isGreaterThan(scorer.score(MatchType.FUZZY, 2, 1_000));
    }

    @Test
    @DisplayName("score is strictly increasing in weight -- the property the trie shortcut needs")
    void scoreIsStrictlyMonotoneInWeight() {
        // OptimizedSearchService asks the trie for only K prefix results, ranked by weight, and
        // treats them as the top K by score. That is valid exactly because this holds.
        double previous = -1.0;
        for (long weight : new long[]{0, 1, 10, 100, 1_000, 10_000, 100_000, 1_000_000}) {
            double score = scorer.score(MatchType.PREFIX, 0, weight);
            assertThat(score).as("weight %d", weight).isGreaterThan(previous);
            previous = score;
        }
    }

    @Test
    @DisplayName("log compression stops frequent words swamping everything else")
    void popularityIsLogCompressed() {
        // A 1000x frequency gap must not translate into a 1000x score gap, or the top of every
        // result list would be the same handful of stopwords.
        double popular = scorer.score(MatchType.PREFIX, 0, 1_000_000);
        double middling = scorer.score(MatchType.PREFIX, 0, 1_000);

        assertThat(popular - middling).isLessThan(0.16);
    }

    @Test
    @DisplayName("a corpus with no frequency information degrades gracefully")
    void uniformCorpusScoresPopularityAsZero() {
        RelevanceScorer flat = new RelevanceScorer(0);

        assertThat(flat.score(MatchType.PREFIX, 0, 0)).isEqualTo(0.7);
        assertThat(flat.score(MatchType.FUZZY, 1, 0)).isEqualTo(0.35);
    }

    @Test
    @DisplayName("weights above the declared maximum are clamped rather than exceeding 1")
    void weightAboveMaximumIsClamped() {
        assertThat(scorer.score(MatchType.PREFIX, 0, MAX_WEIGHT * 100)).isEqualTo(1.0);
    }

    @Test
    void rejectsNegativeMaxWeight() {
        assertThatThrownBy(() -> new RelevanceScorer(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------------------
    // Fuzzy budget policy
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the fuzzy budget scales with query length")
    void fuzzyBudgetScalesWithLength() {
        assertThat(FuzzyBudget.forQuery("")).isZero();
        assertThat(FuzzyBudget.forQuery("a")).isZero();
        assertThat(FuzzyBudget.forQuery("ap")).isZero();
        assertThat(FuzzyBudget.forQuery("app")).isEqualTo(1);
        assertThat(FuzzyBudget.forQuery("appl")).isEqualTo(1);
        assertThat(FuzzyBudget.forQuery("apple")).isEqualTo(2);
        assertThat(FuzzyBudget.forQuery("accommodation")).isEqualTo(2);
    }

    @Test
    @DisplayName("the budget ignores surrounding whitespace and tolerates null")
    void fuzzyBudgetHandlesEdgeInput() {
        assertThat(FuzzyBudget.forQuery("  app  ")).isEqualTo(1);
        assertThat(FuzzyBudget.forQuery(null)).isZero();
    }
}
