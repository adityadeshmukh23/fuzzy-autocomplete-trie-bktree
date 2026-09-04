package com.fuzzysearch.core.search;

import com.fuzzysearch.core.bktree.BKTree;
import com.fuzzysearch.core.index.Corpus;
import com.fuzzysearch.core.index.WordEntry;
import com.fuzzysearch.core.rank.Candidate;
import com.fuzzysearch.core.text.TextNormalizer;
import com.fuzzysearch.core.trie.Trie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The real engine: a trie for prefixes, a BK-tree for typos, a bounded heap for ranking.
 *
 * <p>Produces results <em>identical</em> to {@link NaiveSearchService} -- asserted by
 * {@code SearchServiceEquivalenceTest} -- but reaches them without looking at the whole corpus.
 * That equivalence is what makes the Phase 4 speed comparison a fair one rather than a
 * comparison of two different products.
 */
public final class OptimizedSearchService implements SearchService {

    /**
     * Fixed seed for the BK-tree insertion shuffle.
     *
     * <p>A BK-tree built from a sorted word list degenerates: consecutive dictionary words differ
     * by tiny distances, so they pile into a handful of edges and the tree turns into a long
     * chain. Shuffling fixes the shape; using a <em>fixed</em> seed means every build produces
     * the identical tree, so benchmark runs are comparable and a bad number is reproducible.
     */
    private static final long SHUFFLE_SEED = 20260904L;

    private final Trie trie = new Trie();
    private final BKTree bkTree = new BKTree();
    private final RelevanceScorer scorer;
    private final int size;
    private final long buildTimeMillis;

    public OptimizedSearchService(List<WordEntry> entries) {
        long start = System.nanoTime();

        // Same defensive de-duplication the naive service performs, so both build from an
        // identical view of the corpus.
        final List<WordEntry> unique = Corpus.deduplicate(entries);

        long maxWeight = 0L;
        for (WordEntry entry : unique) {
            trie.insert(entry.word(), entry.weight());
            maxWeight = Math.max(maxWeight, entry.weight());
        }

        List<String> keys = new ArrayList<>(unique.size());
        for (WordEntry entry : unique) {
            keys.add(entry.normalized());
        }
        Collections.shuffle(keys, new Random(SHUFFLE_SEED));
        bkTree.addAll(keys);

        this.scorer = new RelevanceScorer(maxWeight);
        this.size = trie.size();
        this.buildTimeMillis = (System.nanoTime() - start) / 1_000_000L;
    }

    @Override
    public String name() {
        return "optimized";
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public long buildTimeMillis() {
        return buildTimeMillis;
    }

    /** Structure statistics, for the README and the benchmark page. */
    public String indexStats() {
        return "trie nodes=" + trie.nodeCount() + ", bk-tree size=" + bkTree.size()
                + ", bk-tree maxDepth=" + bkTree.maxDepth();
    }

    /**
     * Prefix search via a trie descent plus best-first traversal.
     *
     * <p>Asks the trie for exactly {@code limit} results rather than collecting the whole
     * subtree. <b>That is only correct because the composite score is strictly increasing in
     * corpus weight within the PREFIX tier</b> -- every prefix candidate has the same match
     * quality, so ordering by weight and ordering by score are the same ordering, and the trie's
     * top-K by weight is the top-K by score. The tie-break tiers match too
     * ({@link Candidate#BETTER_FIRST} and {@link SearchResult#BETTER_FIRST} are the same three
     * rules), so ties resolve identically.
     */
    @Override
    public List<SearchResult> prefixSearch(String query, int limit) {
        final String key = TextNormalizer.normalize(query);
        final List<RawHit> hits = toPrefixHits(trie.topKWithPrefix(key, limit));
        return ResultMerger.rank(hits, limit, scorer);
    }

    /**
     * Fuzzy search via BK-tree, which visits only the branches the triangle inequality cannot
     * rule out. The tree returns normalized keys, so each hit is resolved back to its display
     * spelling and weight through {@link Trie#lookup} -- the trie doubles as the word-to-weight
     * map, so there is no third copy of the vocabulary in memory.
     */
    @Override
    public List<SearchResult> fuzzySearch(String query, int limit, int maxEditDistance) {
        final String key = TextNormalizer.normalize(query);
        final List<RawHit> hits = toFuzzyHits(bkTree.search(key, maxEditDistance));
        return ResultMerger.rank(hits, limit, scorer);
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        final String key = TextNormalizer.normalize(query);
        final List<RawHit> prefixHits = toPrefixHits(trie.topKWithPrefix(key, limit));

        return SearchPolicy.progressiveSearch(prefixHits, limit, FuzzyBudget.forQuery(key),
                budget -> bkTreeFuzzyHits(key, budget), scorer);
    }

    private List<RawHit> bkTreeFuzzyHits(String key, int budget) {
        final List<RawHit> hits = new ArrayList<>();
        for (BKTree.Match match : bkTree.search(key, budget)) {
            // A word that literally starts with the query is a PREFIX match, full stop -- even
            // when it also happens to sit within the edit budget. Without this filter, a prefix
            // match that fell outside the trie's top-K could come back through the BK-tree
            // mislabelled as FUZZY, while the naive service (which tests startsWith first) would
            // never label it that way.
            //
            // The strict scoring tiers happen to make such a result lose anyway, but relying on
            // that would make correctness depend on two tuning constants. This makes it
            // structural instead.
            if (match.word().startsWith(key)) {
                continue;
            }
            RawHit hit = toFuzzyHit(match);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private static List<RawHit> toPrefixHits(List<Candidate> candidates) {
        final List<RawHit> hits = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            hits.add(new RawHit(candidate.word(), TextNormalizer.normalize(candidate.word()),
                    (long) candidate.score(), MatchType.PREFIX, 0));
        }
        return hits;
    }

    private List<RawHit> toFuzzyHits(List<BKTree.Match> matches) {
        final List<RawHit> hits = new ArrayList<>(matches.size());
        for (BKTree.Match match : matches) {
            RawHit hit = toFuzzyHit(match);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private RawHit toFuzzyHit(BKTree.Match match) {
        final Candidate indexed = trie.lookup(match.word());
        if (indexed == null) {
            return null;   // defensive: the two structures are built from the same corpus
        }
        return new RawHit(indexed.word(), match.word(), (long) indexed.score(), MatchType.FUZZY,
                match.distance());
    }
}
