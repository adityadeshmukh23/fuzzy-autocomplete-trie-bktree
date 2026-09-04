package com.fuzzysearch.core.distance;

/**
 * A distance function over strings.
 *
 * <p>Implementations handed to {@link com.fuzzysearch.core.bktree.BKTree} <b>must be a true
 * metric</b>. That is not a stylistic preference; the BK-tree's pruning rule is derived from
 * the metric axioms and silently returns wrong (incomplete) results if they do not hold:
 *
 * <ol>
 *   <li>non-negativity: {@code d(a,b) >= 0}</li>
 *   <li>identity of indiscernibles: {@code d(a,b) == 0} if and only if {@code a.equals(b)}</li>
 *   <li>symmetry: {@code d(a,b) == d(b,a)}</li>
 *   <li><b>triangle inequality: {@code d(a,c) <= d(a,b) + d(b,c)}</b></li>
 * </ol>
 *
 * <p>Levenshtein distance satisfies all four. "Optimal string alignment" (the cheap, popular
 * Damerau variant that adds adjacent transpositions) satisfies the first three but
 * <b>violates the triangle inequality</b> -- see {@code OsaTriangleInequalityTest}, which
 * demonstrates the violation with a concrete counterexample rather than asserting it.
 */
@FunctionalInterface
public interface StringMetric {

    /**
     * @return the distance between {@code a} and {@code b}; never negative
     */
    int distance(String a, String b);
}
