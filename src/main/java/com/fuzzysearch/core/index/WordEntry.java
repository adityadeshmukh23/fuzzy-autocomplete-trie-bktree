package com.fuzzysearch.core.index;

import com.fuzzysearch.core.text.TextNormalizer;

/**
 * One entry of the corpus: a word, its index key, and its frequency.
 *
 * @param word       the original spelling, shown to the user
 * @param normalized the lower-cased index key, precomputed
 * @param weight     corpus frequency; higher means more popular
 */
public record WordEntry(String word, String normalized, long weight) {

    public WordEntry {
        if (word == null || normalized == null) {
            throw new NullPointerException("word and normalized must not be null");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be >= 0, got " + weight);
        }
    }

    /**
     * Builds an entry, normalizing once.
     *
     * <p><b>Precomputing the normalized form is a benchmark-fairness decision, not a
     * micro-optimisation.</b> The naive service scans this list on every query. If it had to
     * call {@code toLowerCase} on each of 100,000 words per keystroke, Phase 4 would be
     * measuring string allocation rather than the difference between a linear scan and a trie --
     * and the "optimised" numbers would look good for entirely the wrong reason. Both services
     * get the same precomputed keys.
     */
    public static WordEntry of(String word, long weight) {
        return new WordEntry(word, TextNormalizer.normalize(word), weight);
    }
}
