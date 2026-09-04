package com.fuzzysearch.core.trie;

import java.util.HashMap;
import java.util.Map;

/**
 * One node of the {@link Trie}. Package-private: the node layout is an implementation detail,
 * and nothing outside this package should be able to corrupt the {@code maxSubtreeWeight}
 * invariant.
 */
final class TrieNode {

    /**
     * Child edges, keyed by the next character.
     *
     * <p><b>Rejected alternative: a fixed {@code TrieNode[26]} array.</b> An array is denser and
     * avoids hashing, and for a pure a-z dictionary it would be measurably faster. It was
     * rejected because it silently breaks on the first apostrophe ("o'clock"), hyphen
     * ("well-being"), digit ("3d printer") or accent ("cafe" vs "cafe with an acute e") -- and
     * every realistic autocomplete corpus (product names, query logs) is full of those. A
     * HashMap costs some memory and pointer chasing in exchange for accepting any character,
     * which is the right trade for a general index.
     */
    final Map<Character, TrieNode> children = new HashMap<>(4);

    /**
     * The <b>display form</b> of the word ending at this node -- the original spelling, casing
     * intact -- or null if no word ends here. Non-null is exactly what "terminal node" means.
     */
    String word;

    /**
     * Accumulated corpus weight (frequency) of the word ending here. If several differently
     * cased spellings normalize to the same key, their weights are summed into this one figure.
     */
    long weight;

    /**
     * The single largest weight any one spelling contributed, used only to decide which
     * spelling wins as the display form. Starts at -1 so that the very first insert -- even one
     * with weight 0 -- unambiguously claims the display slot.
     */
    long bestContribution = -1;

    /**
     * <b>The ranking field, and the reason prefix search is fast.</b>
     *
     * <p>The maximum {@link #weight} of any word in this node's subtree (including this node).
     * Because no descendant can score higher than this, it is an <em>admissible upper bound</em>
     * on the best result obtainable by descending here. That is exactly what a best-first search
     * needs: {@link Trie#topKWithPrefix} can order its frontier by this bound and stop as soon
     * as it has emitted K words, without ever visiting the rest of the subtree.
     *
     * <p><b>Why max and not a running sum.</b> A sum ("how much traffic flows through this
     * prefix?") is a fine popularity statistic but useless as a bound -- a subtree with a large
     * total may contain nothing individually good. Max is what makes the pruning sound.
     *
     * <p><b>Invariant, and its limit.</b> Maintained incrementally on insert, which is only
     * valid because weights are non-negative and never decrease. Supporting deletion or weight
     * reduction would require recomputing this bottom-up; the index is build-once, read-many, so
     * that capability is deliberately not built.
     */
    long maxSubtreeWeight;

    boolean isTerminal() {
        return word != null;
    }
}
