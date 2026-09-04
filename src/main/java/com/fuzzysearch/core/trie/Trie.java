package com.fuzzysearch.core.trie;

import com.fuzzysearch.core.rank.Candidate;
import com.fuzzysearch.core.text.TextNormalizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A prefix tree (trie) supporting <b>ranked</b> autocomplete.
 *
 * <p>A plain trie answers "which words start with this prefix?". That is not enough for
 * search-as-you-type: typing "a" over a 100k-word corpus matches ~20,000 words, and the user
 * wants the best 10. The interesting engineering is getting those 10 <em>without</em> walking
 * all 20,000.
 *
 * <p>Each node therefore carries {@link TrieNode#maxSubtreeWeight}, an upper bound on the best
 * word beneath it, and {@link #topKWithPrefix} runs a best-first search over those bounds. See
 * that method for the argument that it is correct.
 *
 * <p><b>Case policy:</b> case-insensitive. Keys are normalized by
 * {@link TextNormalizer#normalize}; the original spelling is preserved for display.
 *
 * <p><b>Thread safety:</b> none. The intended lifecycle is single-threaded bulk load at startup,
 * then concurrent read-only queries, which is safe provided the load happens-before the reads
 * (guaranteed here by Spring's singleton initialisation in Phase 5). Concurrent writes would
 * need external synchronisation.
 */
public final class Trie {

    private final TrieNode root = new TrieNode();
    private int wordCount;
    private int nodeCount = 1;   // the root

    // -------------------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------------------

    /**
     * Inserts a word with the given weight, or merges into an existing entry.
     *
     * <p>Time {@code O(L)} where L is the word length: one walk down to create/find the path,
     * then one pass back over that same path to raise {@code maxSubtreeWeight}. Both are bounded
     * by L, so the cost is linear in the word and independent of how many words are indexed.
     *
     * @param rawWord the word, original casing; must be non-null and non-blank
     * @param weight  corpus frequency, must be {@code >= 0}
     */
    public void insert(String rawWord, long weight) {
        if (rawWord == null) {
            throw new NullPointerException("word must not be null");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be >= 0, got " + weight);
        }
        final String key = TextNormalizer.normalize(rawWord);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("word must not be blank");
        }

        // Walk down, creating nodes as needed, remembering the path. We cannot update
        // maxSubtreeWeight on the way down: the node may already hold weight from an earlier
        // insert, so the final total is not known until we arrive.
        final List<TrieNode> path = new ArrayList<>(key.length() + 1);
        TrieNode node = root;
        path.add(root);
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            TrieNode child = node.children.get(c);
            if (child == null) {
                child = new TrieNode();
                node.children.put(c, child);
                nodeCount++;
            }
            node = child;
            path.add(node);
        }

        if (!node.isTerminal()) {
            wordCount++;
        }
        node.weight += weight;

        // Pick the display spelling: whichever single spelling contributed the most weight,
        // breaking ties lexicographically so the choice does not depend on file order.
        if (weight > node.bestContribution
                || (weight == node.bestContribution && rawWord.compareTo(node.word) < 0)) {
            node.bestContribution = weight;
            node.word = rawWord;
        }

        // Raise the bound along the path. Weights only grow, so max is monotone and a single
        // upward pass is enough -- no recomputation needed.
        for (TrieNode onPath : path) {
            if (node.weight > onPath.maxSubtreeWeight) {
                onPath.maxSubtreeWeight = node.weight;
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------------------

    /**
     * <b>Ranked top-K autocomplete via best-first search.</b>
     *
     * <p>Time {@code O(P + K * B * log F)} where P is the prefix length, B the branching factor
     * and F the frontier size -- notably <em>independent of the number of words under the
     * prefix</em>. Contrast {@link #allWithPrefix}, which is {@code O(M log M)} in the subtree
     * size M.
     *
     * <h3>The algorithm</h3>
     * Walk to the prefix node, then run a best-first (greedy) traversal from it. The frontier is
     * a max-heap holding two kinds of entry:
     * <ul>
     *   <li><b>node entries</b>, keyed by {@code maxSubtreeWeight} -- an <em>upper bound</em> on
     *       anything reachable below;</li>
     *   <li><b>word entries</b>, keyed by the word's <em>actual</em> weight.</li>
     * </ul>
     * Repeatedly pop the largest key. Pop a word entry, and it can be emitted immediately: every
     * other entry in the frontier has a key {@code <=} this one, and each of those keys is an
     * upper bound on everything it represents, so nothing anywhere in the unexplored space can
     * beat it. Pop a node entry, and expand it: push its own word (if it is terminal) and each
     * of its children. Stop after K emissions.
     *
     * <p>This is A* with an admissible heuristic, specialised to a tree.
     *
     * <h3>Why the tie-break rule has that extra clause</h3>
     * When a node bound ties with a word's weight, the <b>node is expanded first</b>. Otherwise a
     * word could be emitted while an equally-weighted but lexicographically earlier word was
     * still hidden inside an unexpanded subtree, and the output order would disagree with
     * {@link Candidate#BETTER_FIRST}. With that clause the two orders agree exactly -- which is
     * what the oracle test asserts.
     *
     * @param rawPrefix the prefix, matched case-insensitively; "" returns the global top K
     * @param k         maximum results; {@code <= 0} returns empty
     * @return up to k candidates, best first
     */
    public List<Candidate> topKWithPrefix(String rawPrefix, int k) {
        return searchPrefixWithStats(rawPrefix, k).results();
    }

    /**
     * Ranked top-K autocomplete, plus the instrumentation that makes the "output-sensitive"
     * claim above checkable rather than merely asserted.
     *
     * <p>{@link PrefixSearchResult#nodesExpanded()} is the number of trie nodes actually opened.
     * Compare it against {@code allWithPrefix(prefix).size()} to see how much of the subtree was
     * never touched -- for a one-letter prefix over a large corpus the gap is three orders of
     * magnitude. Counters are locals returned in the result, never shared mutable state, so
     * concurrent queries cannot corrupt each other.
     */
    public PrefixSearchResult searchPrefixWithStats(String rawPrefix, int k) {
        if (k <= 0) {
            return new PrefixSearchResult(List.of(), 0, 0);
        }
        final TrieNode start = descend(TextNormalizer.normalize(rawPrefix));
        if (start == null) {
            return new PrefixSearchResult(List.of(), 0, 0);
        }
        int nodesExpanded = 0;
        int frontierPushes = 0;

        final PriorityQueue<Entry> frontier = new PriorityQueue<>(Trie::compareFrontier);
        long sequence = 0;
        frontier.add(Entry.forNode(start, sequence++));
        frontierPushes++;

        final List<Candidate> results = new ArrayList<>(k);
        while (!frontier.isEmpty() && results.size() < k) {
            final Entry entry = frontier.poll();

            if (entry.isWord) {
                results.add(new Candidate(entry.word, entry.key));
                continue;
            }

            final TrieNode node = entry.node;
            nodesExpanded++;
            if (node.isTerminal()) {
                frontier.add(Entry.forWord(node.word, node.weight, sequence++));
                frontierPushes++;
            }
            for (TrieNode child : node.children.values()) {
                frontier.add(Entry.forNode(child, sequence++));
                frontierPushes++;
            }
        }
        return new PrefixSearchResult(List.copyOf(results), nodesExpanded, frontierPushes);
    }

    /**
     * Prefix search results plus traversal statistics.
     *
     * @param results        up to k candidates, best first
     * @param nodesExpanded  trie nodes opened during the best-first walk
     * @param frontierPushes items pushed onto the priority queue
     */
    public record PrefixSearchResult(List<Candidate> results, int nodesExpanded,
                                     int frontierPushes) {
    }

    /**
     * Every word under the prefix, fully ranked.
     *
     * <p>Time {@code O(M + M log M)} in the subtree size M: collect the whole subtree, then
     * sort. This is the straightforward implementation, kept for two reasons -- it is the oracle
     * the best-first search is tested against, and it is the honest "simple trie autocomplete"
     * to compare against in the benchmark.
     */
    public List<Candidate> allWithPrefix(String rawPrefix) {
        final TrieNode start = descend(TextNormalizer.normalize(rawPrefix));
        if (start == null) {
            return List.of();
        }
        final List<Candidate> out = new ArrayList<>();
        final Deque<TrieNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            final TrieNode node = stack.pop();
            if (node.isTerminal()) {
                out.add(new Candidate(node.word, node.weight));
            }
            for (TrieNode child : node.children.values()) {
                stack.push(child);
            }
        }
        out.sort(Candidate.BETTER_FIRST);
        return out;
    }

    /** @return true if the exact word (case-insensitively) is indexed */
    public boolean contains(String rawWord) {
        final TrieNode node = descend(TextNormalizer.normalize(rawWord));
        return node != null && node.isTerminal();
    }

    /**
     * Looks up a single word.
     *
     * <p>The trie already <em>is</em> a word-to-weight map, which is why the optimised search
     * service needs no separate {@code HashMap} to resolve the bare normalized strings the
     * BK-tree returns back into display spellings and weights. One O(L) descent, no duplicated
     * index.
     *
     * @return the display spelling and weight, or null if the word is not indexed
     */
    public Candidate lookup(String rawWord) {
        final TrieNode node = descend(TextNormalizer.normalize(rawWord));
        if (node == null || !node.isTerminal()) {
            return null;
        }
        return new Candidate(node.word, node.weight);
    }

    /** @return the accumulated weight of the word, or 0 if it is not indexed */
    public long weightOf(String rawWord) {
        final TrieNode node = descend(TextNormalizer.normalize(rawWord));
        return (node != null && node.isTerminal()) ? node.weight : 0L;
    }

    /** @return true if any indexed word starts with this prefix */
    public boolean hasPrefix(String rawPrefix) {
        return descend(TextNormalizer.normalize(rawPrefix)) != null;
    }

    /** @return the number of distinct words indexed */
    public int size() {
        return wordCount;
    }

    /** @return the number of nodes allocated, for memory reporting in the README */
    public int nodeCount() {
        return nodeCount;
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    /** Walks the already-normalized key from the root. @return the node, or null if absent. */
    private TrieNode descend(String key) {
        TrieNode node = root;
        for (int i = 0; i < key.length(); i++) {
            node = node.children.get(key.charAt(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /**
     * A frontier item: either an unexplored node (key = upper bound) or a word ready to emit
     * (key = its exact weight).
     */
    private static final class Entry {
        final long key;
        final boolean isWord;
        final TrieNode node;     // null for word entries
        final String word;       // null for node entries
        final long sequence;     // insertion counter, purely to make the order total

        private Entry(long key, boolean isWord, TrieNode node, String word, long sequence) {
            this.key = key;
            this.isWord = isWord;
            this.node = node;
            this.word = word;
            this.sequence = sequence;
        }

        static Entry forNode(TrieNode node, long sequence) {
            return new Entry(node.maxSubtreeWeight, false, node, null, sequence);
        }

        static Entry forWord(String word, long weight, long sequence) {
            return new Entry(weight, true, null, word, sequence);
        }
    }

    /**
     * Orders the frontier. Deliberately mirrors {@link Candidate#BETTER_FIRST} so that emission
     * order and final ranking are the same order, tier for tier.
     */
    private static int compareFrontier(Entry a, Entry b) {
        // 1. Highest key first -- the actual best-first criterion.
        int c = Long.compare(b.key, a.key);
        if (c != 0) {
            return c;
        }
        // 2. On a tie, expand nodes before emitting words (see topKWithPrefix's javadoc).
        c = Boolean.compare(a.isWord, b.isWord);   // false (node) sorts before true (word)
        if (c != 0) {
            return c;
        }
        // 3. Between two words, apply the same tie-break the final ranking uses.
        if (a.isWord) {
            c = Integer.compare(a.word.length(), b.word.length());
            if (c != 0) {
                return c;
            }
            c = a.word.compareTo(b.word);
            if (c != 0) {
                return c;
            }
        }
        // 4. Total order. Between two nodes the choice is arbitrary -- tier 2 already guarantees
        //    every equally-bounded node is expanded before any word of that weight is emitted,
        //    so node ordering cannot affect the output.
        return Long.compare(a.sequence, b.sequence);
    }
}
