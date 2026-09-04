package com.fuzzysearch.core.rank;

import java.util.Comparator;
import java.util.Objects;

/**
 * A scored search candidate: the word that will be shown to the user, plus its relevance score.
 *
 * <p>{@code score} is a {@code double} rather than a {@code long} because it carries two
 * different things at different layers of the system: raw corpus frequency straight out of the
 * trie (always integral), and, later, a composite relevance score blending frequency, match
 * type and edit distance. Doubles represent integers exactly up to 2^53, far above any
 * frequency we will ever see, so nothing is lost by using one type throughout.
 */
public record Candidate(String word, double score) {

    /**
     * The one and only definition of "better" in this system.
     *
     * <p>Three tiers, in order:
     * <ol>
     *   <li><b>Higher score first.</b> The actual ranking signal.</li>
     *   <li><b>Shorter word first.</b> For autocomplete this is the right tie-break: given
     *       equally popular completions of "car", "cars" is a more likely intent than
     *       "carpentry".</li>
     *   <li><b>Lexicographic ascending.</b> Purely to make the order <em>total</em>.</li>
     * </ol>
     *
     * <p>That third tier matters more than it looks. Because no two distinct words can ever
     * compare equal, ranking is fully deterministic: the same index and the same query always
     * produce byte-identical output. That is what makes the unit tests stable and the Phase 4
     * benchmark reproducible instead of flapping run to run.
     */
    public static final Comparator<Candidate> BETTER_FIRST =
            Comparator.comparingDouble((Candidate c) -> c.score()).reversed()
                    .thenComparingInt((Candidate c) -> c.word().length())
                    .thenComparing((Candidate c) -> c.word());

    public Candidate {
        Objects.requireNonNull(word, "word must not be null");
        // NaN would poison the comparator: NaN compares as "largest" under Double.compare but
        // is unordered under <, so a single NaN can make ranking non-transitive and silently
        // corrupt heap invariants. Reject it at the boundary.
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score must not be NaN");
        }
    }
}
