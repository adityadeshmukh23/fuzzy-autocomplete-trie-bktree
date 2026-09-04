package com.fuzzysearch.core.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corpus preparation shared by both search services.
 *
 * <p><b>Why this exists.</b> The trie merges duplicate keys automatically -- inserting "Apple"
 * and "apple" produces one entry with the summed weight. A flat list does not; the naive service
 * would hold two rows and rank the heavier one alone. That single difference would break the
 * guarantee that the two engines return identical results, and it would break it silently, in a
 * way that looks like a ranking bug rather than a data bug.
 *
 * <p>So de-duplication happens once, up front, using exactly the policy {@code Trie.insert}
 * uses, and both services run it defensively in their constructors. Real word-frequency datasets
 * do contain case variants of the same word, so this is not a hypothetical.
 */
public final class Corpus {

    private Corpus() {
    }

    /**
     * Merges entries that share a normalized key.
     *
     * <p>Policy, identical to the trie's:
     * <ul>
     *   <li>weights are <b>summed</b> -- "Apple" appearing 30 times and "apple" 70 times means
     *       the word occurs 100 times;</li>
     *   <li>the <b>display spelling</b> is whichever single spelling contributed the most weight,
     *       ties broken lexicographically so the result never depends on file order.</li>
     * </ul>
     *
     * <p>Input order is otherwise preserved, so a frequency-sorted dataset stays sorted.
     */
    public static List<WordEntry> deduplicate(List<WordEntry> entries) {
        final Map<String, Merged> byKey = new LinkedHashMap<>();

        for (WordEntry entry : entries) {
            Merged merged = byKey.get(entry.normalized());
            if (merged == null) {
                byKey.put(entry.normalized(), new Merged(entry.word(), entry.weight(),
                        entry.weight()));
                continue;
            }
            merged.totalWeight += entry.weight();
            if (entry.weight() > merged.bestContribution
                    || (entry.weight() == merged.bestContribution
                        && entry.word().compareTo(merged.displayWord) < 0)) {
                merged.bestContribution = entry.weight();
                merged.displayWord = entry.word();
            }
        }

        final List<WordEntry> out = new ArrayList<>(byKey.size());
        for (Map.Entry<String, Merged> e : byKey.entrySet()) {
            out.add(new WordEntry(e.getValue().displayWord, e.getKey(), e.getValue().totalWeight));
        }
        return out;
    }

    private static final class Merged {
        String displayWord;
        long totalWeight;
        long bestContribution;

        Merged(String displayWord, long totalWeight, long bestContribution) {
            this.displayWord = displayWord;
            this.totalWeight = totalWeight;
            this.bestContribution = bestContribution;
        }
    }
}
