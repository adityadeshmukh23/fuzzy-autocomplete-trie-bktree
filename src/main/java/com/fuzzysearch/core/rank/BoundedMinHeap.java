package com.fuzzysearch.core.rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A hand-built, fixed-capacity binary heap that selects the top K items from a stream without
 * ever holding more than K of them.
 *
 * <h2>Why not just sort</h2>
 * Sorting all N candidates and taking the first K is {@code O(N log N)} time and {@code O(N)}
 * memory. This is {@code O(N log K)} time and {@code O(K)} memory. With N in the tens of
 * thousands (every word under the prefix "a", plus every fuzzy hit) and K = 10, that is the
 * difference between touching 20,000 items and keeping 10 in an array of 10.
 *
 * <h2>Why a MIN-heap when we want the BEST items</h2>
 * This is the part worth being able to explain cleanly. The heap holds the K best items seen so
 * far, and the element sitting at the root is the <b>worst</b> of those -- the one on the bubble.
 * That is precisely the element a new candidate must beat, and precisely the element to evict
 * when it does. Both are O(1) to find because it is at the root. A max-heap would put the best
 * item at the root, which is the one item never needed during the scan.
 *
 * <h2>One comparator, no chance of disagreement</h2>
 * A classic bug here is defining the output order and the eviction order separately and getting
 * them subtly out of sync, so the heap evicts the wrong element. This class takes exactly one
 * comparator -- {@code betterFirst} -- and derives the heap ordering from it by swapping the
 * arguments ({@link #worstFirstCompare}). They cannot drift apart.
 *
 * <p>Generic in T rather than fixed to {@link Candidate} because Phase 5 ranks a richer result
 * type carrying match provenance; the selection algorithm is the same either way.
 */
public final class BoundedMinHeap<T> {

    private final Object[] heap;
    private final int capacity;
    private final Comparator<? super T> betterFirst;
    private int size;

    /**
     * @param capacity    K, the number of items to retain; 0 is legal and retains nothing
     * @param betterFirst orders items best-first. Should be a <em>total</em> order (never
     *                    returning 0 for distinct items) if deterministic output matters --
     *                    {@link Candidate#BETTER_FIRST} is one.
     */
    public BoundedMinHeap(int capacity, Comparator<? super T> betterFirst) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be >= 0, got " + capacity);
        }
        this.capacity = capacity;
        this.betterFirst = Objects.requireNonNull(betterFirst, "betterFirst must not be null");
        this.heap = new Object[capacity];
    }

    /**
     * Offers a candidate to the heap.
     *
     * <p>Time {@code O(log K)} when the item is kept, {@code O(1)} when it is rejected -- and
     * once the heap is warm, the overwhelming majority of candidates are rejected on that single
     * comparison against the root.
     *
     * @return true if the candidate was retained
     */
    public boolean offer(T candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (capacity == 0) {
            return false;
        }

        if (size < capacity) {
            // Still filling: append at the end and bubble it up to restore the heap property.
            heap[size] = candidate;
            size++;
            siftUp(size - 1);
            return true;
        }

        // Full. heap[0] is the worst item currently retained -- the bar to clear.
        if (betterFirst.compare(candidate, get(0)) < 0) {
            heap[0] = candidate;      // evict the worst, drop the new item in its place
            siftDown(0);              // and sink it to where it belongs
            return true;
        }
        // Strictly better only: an exact tie keeps the incumbent. With a total-order comparator
        // a tie means the items are equivalent anyway, so this just avoids pointless writes.
        return false;
    }

    /** Offers every item in the iterable. */
    public void offerAll(Iterable<? extends T> items) {
        for (T item : items) {
            offer(item);
        }
    }

    /**
     * Removes and returns everything in the heap, best item first.
     *
     * <p>Repeatedly polls the root -- which yields items <em>worst</em> first, since the root is
     * the worst -- then reverses. {@code O(K log K)}, and it empties the heap.
     */
    public List<T> drainBestFirst() {
        final List<T> out = new ArrayList<>(size);
        while (size > 0) {
            out.add(get(0));
            heap[0] = heap[size - 1];
            heap[size - 1] = null;
            size--;
            if (size > 0) {
                siftDown(0);
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** @return the worst item currently retained (the eviction candidate), or null if empty */
    public T worst() {
        return size == 0 ? null : get(0);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * One-shot convenience: the top K of a collection, best first.
     *
     * <p>This is the entry point the search services use.
     */
    public static <T> List<T> topK(Iterable<? extends T> items, int k,
                                   Comparator<? super T> betterFirst) {
        final BoundedMinHeap<T> heap = new BoundedMinHeap<>(k, betterFirst);
        heap.offerAll(items);
        return heap.drainBestFirst();
    }

    // -------------------------------------------------------------------------------------
    // Heap mechanics
    // -------------------------------------------------------------------------------------

    /**
     * The heap's internal ordering: worst-first, which is {@code betterFirst} with the arguments
     * swapped. Deriving it rather than declaring it separately is what keeps eviction order and
     * output order provably consistent.
     */
    private int worstFirstCompare(T a, T b) {
        return betterFirst.compare(b, a);
    }

    /**
     * Moves the item at {@code index} up until its parent is no better-ordered than it is.
     *
     * <p>The array is an implicit complete binary tree: node i has parent (i-1)/2 and children
     * 2i+1, 2i+2. No node objects, no pointers -- that is why a heap is so cache-friendly.
     */
    private void siftUp(int index) {
        while (index > 0) {
            final int parent = (index - 1) / 2;
            if (worstFirstCompare(get(index), get(parent)) >= 0) {
                break;   // parent is already "worse or equal": heap property restored
            }
            swap(index, parent);
            index = parent;
        }
    }

    /** Moves the item at {@code index} down until both children are worse-ordered than it. */
    private void siftDown(int index) {
        while (true) {
            final int left = 2 * index + 1;
            final int right = left + 1;
            int smallest = index;   // "smallest" under worst-first order == the worst item

            if (left < size && worstFirstCompare(get(left), get(smallest)) < 0) {
                smallest = left;
            }
            if (right < size && worstFirstCompare(get(right), get(smallest)) < 0) {
                smallest = right;
            }
            if (smallest == index) {
                return;
            }
            swap(index, smallest);
            index = smallest;
        }
    }

    private void swap(int i, int j) {
        final Object tmp = heap[i];
        heap[i] = heap[j];
        heap[j] = tmp;
    }

    @SuppressWarnings("unchecked")
    private T get(int index) {
        return (T) heap[index];
    }
}
