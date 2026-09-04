package com.fuzzysearch.core.rank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedMinHeapTest {

    private static Candidate candidate(String word, double score) {
        return new Candidate(word, score);
    }

    private static List<String> words(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::word).toList();
    }

    // -------------------------------------------------------------------------------------
    // Capacity behaviour
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("fewer candidates than K -> all of them, still ranked")
    void fewerThanCapacity() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(5, Candidate.BETTER_FIRST);
        heap.offer(candidate("b", 2));
        heap.offer(candidate("a", 9));
        heap.offer(candidate("c", 5));

        assertThat(heap.size()).isEqualTo(3);
        assertThat(words(heap.drainBestFirst())).containsExactly("a", "c", "b");
    }

    @Test
    @DisplayName("exactly K candidates -> all of them, ranked")
    void exactlyCapacity() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(3, Candidate.BETTER_FIRST);
        heap.offer(candidate("low", 1));
        heap.offer(candidate("high", 10));
        heap.offer(candidate("mid", 5));

        assertThat(heap.size()).isEqualTo(3);
        assertThat(words(heap.drainBestFirst())).containsExactly("high", "mid", "low");
    }

    @Test
    @DisplayName("far more candidates than K -> only the best K are retained")
    void moreThanCapacity() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(3, Candidate.BETTER_FIRST);
        for (int i = 0; i < 1_000; i++) {
            heap.offer(candidate("w" + i, i));
        }

        assertThat(heap.size()).isEqualTo(3);
        assertThat(words(heap.drainBestFirst())).containsExactly("w999", "w998", "w997");
    }

    @Test
    @DisplayName("capacity 0 retains nothing")
    void zeroCapacity() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(0, Candidate.BETTER_FIRST);
        assertThat(heap.offer(candidate("a", 100))).isFalse();
        assertThat(heap.isEmpty()).isTrue();
        assertThat(heap.drainBestFirst()).isEmpty();
    }

    @Test
    void rejectsNegativeCapacity() {
        assertThatThrownBy(() -> new BoundedMinHeap<Candidate>(-1, Candidate.BETTER_FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("offer reports whether the candidate was retained")
    void offerReportsRetention() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(2, Candidate.BETTER_FIRST);
        assertThat(heap.offer(candidate("a", 5))).isTrue();
        assertThat(heap.offer(candidate("b", 7))).isTrue();
        assertThat(heap.offer(candidate("c", 1))).as("worse than both incumbents").isFalse();
        assertThat(heap.offer(candidate("d", 9))).as("better than the worst incumbent").isTrue();

        assertThat(words(heap.drainBestFirst())).containsExactly("d", "b");
    }

    @Test
    @DisplayName("worst() exposes the element on the bubble")
    void worstIsTheEvictionCandidate() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(3, Candidate.BETTER_FIRST);
        assertThat(heap.worst()).isNull();

        heap.offer(candidate("a", 5));
        heap.offer(candidate("b", 7));
        heap.offer(candidate("c", 1));

        assertThat(heap.worst().word()).isEqualTo("c");
    }

    @Test
    @DisplayName("drainBestFirst empties the heap")
    void drainEmptiesTheHeap() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(3, Candidate.BETTER_FIRST);
        heap.offerAll(List.of(candidate("a", 1), candidate("b", 2)));

        assertThat(heap.drainBestFirst()).hasSize(2);
        assertThat(heap.isEmpty()).isTrue();
        assertThat(heap.drainBestFirst()).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // Tie-breaking -- the documented deterministic rule
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("equal scores: shorter word wins, then lexicographic")
    void tieBreakIsShorterThenLexicographic() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(4, Candidate.BETTER_FIRST);
        heap.offer(candidate("carpentry", 100));
        heap.offer(candidate("cars", 100));
        heap.offer(candidate("card", 100));
        heap.offer(candidate("carpet", 100));

        // All four score identically, so tier 2 (length) then tier 3 (lexicographic) decide.
        assertThat(words(heap.drainBestFirst()))
                .containsExactly("card", "cars", "carpet", "carpentry");
    }

    @Test
    @DisplayName("tie-breaking is applied at the capacity boundary too, not just in the output")
    void tieBreakDecidesEviction() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(2, Candidate.BETTER_FIRST);
        heap.offer(candidate("elephant", 50));
        heap.offer(candidate("dog", 50));
        heap.offer(candidate("cat", 50));   // same score, shorter+earlier than "elephant"

        assertThat(words(heap.drainBestFirst())).containsExactly("cat", "dog");
    }

    @Test
    @DisplayName("an exact tie keeps the incumbent")
    void exactTieKeepsIncumbent() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(1, Candidate.BETTER_FIRST);
        Candidate first = candidate("same", 5);
        heap.offer(first);
        assertThat(heap.offer(candidate("same", 5))).isFalse();
        assertThat(heap.drainBestFirst()).containsExactly(first);
    }

    // -------------------------------------------------------------------------------------
    // Oracle tests: hand-built heap vs. the standard library
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("matches a full sort + limit on 500 random workloads")
    void matchesFullSortOracle() {
        Random random = new Random(42L);

        for (int trial = 0; trial < 500; trial++) {
            int n = random.nextInt(200);
            int k = random.nextInt(20);

            List<Candidate> input = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // Coarse scores and short words, so ties are frequent and the tie-break rules
                // actually get exercised rather than being drowned out by distinct scores.
                input.add(candidate(randomWord(random), random.nextInt(5)));
            }

            List<Candidate> expected = input.stream()
                    .sorted(Candidate.BETTER_FIRST)
                    .limit(k)
                    .toList();

            List<Candidate> actual = BoundedMinHeap.topK(input, k, Candidate.BETTER_FIRST);

            assertThat(actual)
                    .as("trial %d (n=%d, k=%d)", trial, n, k)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("matches a PriorityQueue-based reference implementation")
    void matchesPriorityQueueOracle() {
        Random random = new Random(7L);
        List<Candidate> input = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            input.add(candidate(randomWord(random), random.nextInt(50)));
        }
        int k = 25;

        assertThat(BoundedMinHeap.topK(input, k, Candidate.BETTER_FIRST))
                .isEqualTo(topKWithPriorityQueue(input, k, Candidate.BETTER_FIRST));
    }

    /**
     * The same bounded top-K algorithm expressed with {@link PriorityQueue}, used purely as a
     * test oracle. The hand-built heap is the shipped implementation; this exists so that any
     * bug in the hand-rolled sift logic shows up as a disagreement rather than as plausible-
     * looking wrong output.
     */
    private static <T> List<T> topKWithPriorityQueue(List<T> items, int k,
                                                     Comparator<? super T> betterFirst) {
        PriorityQueue<T> queue = new PriorityQueue<>(Math.max(1, k), betterFirst.reversed());
        for (T item : items) {
            if (queue.size() < k) {
                queue.offer(item);
            } else if (k > 0 && betterFirst.compare(item, queue.peek()) < 0) {
                queue.poll();
                queue.offer(item);
            }
        }
        List<T> out = new ArrayList<>(queue);
        out.sort(betterFirst);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Candidate validation
    // -------------------------------------------------------------------------------------

    @Test
    void candidateRejectsNaNScore() {
        assertThatThrownBy(() -> new Candidate("a", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void candidateRejectsNullWord() {
        assertThatThrownBy(() -> new Candidate(null, 1.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void heapRejectsNullCandidate() {
        BoundedMinHeap<Candidate> heap = new BoundedMinHeap<>(2, Candidate.BETTER_FIRST);
        assertThatThrownBy(() -> heap.offer(null)).isInstanceOf(NullPointerException.class);
    }

    private static String randomWord(Random random) {
        int length = 1 + random.nextInt(5);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(4)));
        }
        return sb.toString();
    }
}
