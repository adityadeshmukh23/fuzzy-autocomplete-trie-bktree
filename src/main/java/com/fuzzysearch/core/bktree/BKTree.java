package com.fuzzysearch.core.bktree;

import com.fuzzysearch.core.distance.LevenshteinDistance;
import com.fuzzysearch.core.distance.StringMetric;
import com.fuzzysearch.core.text.TextNormalizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A BK-tree: an index over a metric space that answers "which indexed words are within k edits
 * of this query?" without comparing the query to every word.
 *
 * <p>This is the structure that makes typo tolerance affordable. The brute-force alternative
 * computes an edit distance against all 100,000 words on every keystroke. The BK-tree computes
 * a few hundred.
 *
 * <h2>Structure</h2>
 * Each node holds one word. Each edge is <b>labelled with an integer</b>: the edit distance
 * between the parent's word and the child's word. Insertion routes from the root by that same
 * distance -- compute {@code d = distance(newWord, current.word)}, follow the edge labelled
 * {@code d}, create it if it does not exist.
 *
 * <h2>The invariant that makes pruning work</h2>
 * Because every routing decision at a node {@code u} is made purely from the distance to
 * {@code u.word}, the following holds by construction:
 *
 * <blockquote><b>Every node in the subtree hanging off the edge labelled {@code L} from node
 * {@code u} sits at distance exactly {@code L} from {@code u.word}.</b></blockquote>
 *
 * <p>Not just the immediate child -- the whole subtree. That is stronger than the version of
 * this argument usually quoted, and it is what licenses pruning an entire branch rather than a
 * single node.
 *
 * <h2>The pruning rule</h2>
 * Searching for query {@code q} within {@code maxDistance}, at node {@code u}, having computed
 * {@code d = distance(q, u.word)}: for any descendant {@code x} under edge {@code L}, the
 * triangle inequality gives
 *
 * <pre>
 *   distance(q, x)  &gt;=  | distance(q, u.word) - distance(u.word, x) |  =  | d - L |
 * </pre>
 *
 * <p>So if {@code |d - L| &gt; maxDistance}, <em>nothing</em> in that subtree can be a hit, and
 * the whole branch is skipped without a single further distance computation. Only edges
 * labelled within {@code [d - maxDistance, d + maxDistance]} are worth descending -- at most
 * {@code 2*maxDistance + 1} of them per visited node.
 *
 * <h2>Honest complexity</h2>
 * There is <b>no proven sublinear bound</b> for BK-tree search; the worst case is O(n) and the
 * real cost is entirely data-dependent. Its reputation as "logarithmic" is folklore. What is
 * true and measurable is that the {@code 2k+1} branching window prunes hard for small k and
 * degrades toward a linear scan as k grows -- which is exactly why every search reports
 * {@link SearchResult#distanceComputations()}, and why Phase 4 benchmarks across a range of k
 * instead of quoting a complexity class.
 *
 * <h2>Thread safety</h2>
 * Reads are safe once the tree is built; writes are not. Note that search keeps its counters in
 * local variables and returns them in the result rather than mutating shared state, so
 * concurrent queries cannot corrupt each other's statistics.
 */
public final class BKTree {

    /** One hit: the indexed word and its exact edit distance from the query. */
    public record Match(String word, int distance) {
    }

    /**
     * Search results plus the instrumentation that makes pruning visible.
     *
     * @param matches              hits, ordered by distance then by the standard tie-break
     * @param nodesVisited         nodes actually inspected
     * @param distanceComputations edit-distance calls made; compare against {@link #size()} to
     *                             see the pruning ratio directly
     */
    public record SearchResult(List<Match> matches, int nodesVisited, int distanceComputations) {
    }

    /**
     * Hits sorted closest-first, then by the same shorter-then-lexicographic rule used
     * everywhere else, so results are deterministic regardless of traversal order.
     */
    private static final Comparator<Match> CLOSEST_FIRST =
            Comparator.comparingInt((Match m) -> m.distance())
                    .thenComparingInt((Match m) -> m.word().length())
                    .thenComparing((Match m) -> m.word());

    private static final class Node {
        final String word;
        /** Child edges keyed by edit distance from this node's word. */
        final Map<Integer, Node> children = new HashMap<>(4);

        Node(String word) {
            this.word = word;
        }
    }

    private final StringMetric metric;
    private Node root;
    private int size;

    /** Builds a BK-tree over exact Levenshtein distance. */
    public BKTree() {
        this(LevenshteinDistance::distance);
    }

    /**
     * Builds a BK-tree over a caller-supplied metric.
     *
     * <p>Injecting the metric keeps this class a general metric-space index rather than a
     * string-specific one, and means swapping in a different distance later touches nothing
     * here. The metric <b>must</b> satisfy the axioms in {@link StringMetric} -- in particular
     * the triangle inequality, without which the pruning above is unsound.
     */
    public BKTree(StringMetric metric) {
        if (metric == null) {
            throw new NullPointerException("metric must not be null");
        }
        this.metric = metric;
    }

    // -------------------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------------------

    /**
     * Inserts a word, normalized to lower case.
     *
     * <p>Cost is one distance computation per level descended, so it is proportional to tree
     * depth -- which depends on insertion order, see {@link #addAll}.
     *
     * @return true if the word was added, false if it was already present
     */
    public boolean add(String rawWord) {
        final String key = TextNormalizer.normalize(rawWord);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("word must not be blank");
        }
        if (root == null) {
            root = new Node(key);
            size = 1;
            return true;
        }

        Node current = root;
        while (true) {
            final int d = metric.distance(key, current.word);
            if (d == 0) {
                // Identity of indiscernibles: distance 0 means it is the same word. This is also
                // why edge label 0 can never exist, which search relies on below.
                return false;
            }
            final Node child = current.children.get(d);
            if (child == null) {
                current.children.put(d, new Node(key));
                size++;
                return true;
            }
            current = child;
        }
    }

    /**
     * Bulk insert.
     *
     * <p><b>Insertion order matters.</b> Feeding in a sorted word list produces a badly
     * unbalanced tree, because consecutive words differ by tiny distances and pile into the same
     * few edges. Callers should shuffle first (with a fixed seed, so benchmarks stay
     * reproducible). This method does not shuffle for you -- the choice belongs to the caller
     * that knows the corpus.
     */
    public void addAll(Collection<String> words) {
        for (String word : words) {
            add(word);
        }
    }

    // -------------------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------------------

    /** Convenience wrapper around {@link #searchWithStats} when the counters are not needed. */
    public List<Match> search(String rawQuery, int maxDistance) {
        return searchWithStats(rawQuery, maxDistance).matches();
    }

    /**
     * Finds every indexed word within {@code maxDistance} edits of the query.
     *
     * <p>Iterative rather than recursive: a pathological insertion order can produce a deep tree,
     * and blowing the stack on a bad corpus would be an embarrassing way to fail.
     */
    public SearchResult searchWithStats(String rawQuery, int maxDistance) {
        if (maxDistance < 0) {
            throw new IllegalArgumentException("maxDistance must be >= 0, got " + maxDistance);
        }
        final String query = TextNormalizer.normalize(rawQuery);
        if (root == null) {
            return new SearchResult(List.of(), 0, 0);
        }

        final List<Match> matches = new ArrayList<>();
        int nodesVisited = 0;
        int distanceComputations = 0;

        final Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            final Node node = stack.pop();
            nodesVisited++;

            // The exact distance is required, not a cutoff-clamped one: it defines the window
            // below. A clamped value would compute the wrong window and silently lose matches.
            final int d = metric.distance(query, node.word);
            distanceComputations++;

            if (d <= maxDistance) {
                matches.add(new Match(node.word, d));
            }

            // The triangle-inequality window. Edge label 0 cannot exist (see add), so start at 1.
            final int lowest = Math.max(1, d - maxDistance);
            final int highest = d + maxDistance;

            // Probe the window rather than iterating this node's children and filtering: the
            // window holds at most 2*maxDistance+1 labels (7 at maxDistance=3), whereas a node
            // for a long word can easily have twenty-odd children. Probing is the smaller loop.
            for (int label = lowest; label <= highest; label++) {
                final Node child = node.children.get(label);
                if (child != null) {
                    stack.push(child);
                }
            }
        }

        matches.sort(CLOSEST_FIRST);
        return new SearchResult(List.copyOf(matches), nodesVisited, distanceComputations);
    }

    /** @return the number of distinct words indexed */
    public int size() {
        return size;
    }

    /**
     * @return the length of the longest root-to-leaf path, or 0 for an empty tree. Reported in
     *         the README to show how much insertion order affects tree shape.
     */
    public int maxDepth() {
        if (root == null) {
            return 0;
        }
        int deepest = 0;
        final Deque<Node> nodes = new ArrayDeque<>();
        final Deque<Integer> depths = new ArrayDeque<>();
        nodes.push(root);
        depths.push(1);
        while (!nodes.isEmpty()) {
            final Node node = nodes.pop();
            final int depth = depths.pop();
            deepest = Math.max(deepest, depth);
            for (Node child : node.children.values()) {
                nodes.push(child);
                depths.push(depth + 1);
            }
        }
        return deepest;
    }
}
