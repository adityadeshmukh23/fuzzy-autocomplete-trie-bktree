package com.fuzzysearch.api.dto;

import java.util.List;

/**
 * Both engines run against the same live index, for the frontend's benchmark page.
 *
 * <h2>Why the comparison happens on the server</h2>
 * The obvious alternative is for the browser to call both endpoints and time them itself. That
 * would measure the network round-trip, which on any real deployment is milliseconds and would
 * swamp the difference between a 4 microsecond trie descent and a 500 microsecond scan. Timing
 * both in the same process, on the same index, in the same JVM state, is the only way for a live
 * demo to show the actual algorithmic difference.
 *
 * @param query            the query as received
 * @param limit            results requested
 * @param optimizedMicros  best-of-N server-side time for the trie + BK-tree engine
 * @param naiveMicros      best-of-N server-side time for the brute-force scan
 * @param speedup          {@code naiveMicros / optimizedMicros}
 * @param identicalResults whether both engines returned exactly the same ranked list. Should
 *                         always be true -- it is the guarantee the whole comparison rests on,
 *                         and showing it live is more convincing than asserting it in a README.
 * @param results          the ranked list (identical from both, so returned once)
 */
public record CompareResponse(String query, int limit, double optimizedMicros, double naiveMicros,
                              double speedup, boolean identicalResults,
                              List<SearchResultDto> results) {
}
