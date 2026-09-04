package com.fuzzysearch.core.text;

import java.util.Locale;

/**
 * Single source of truth for how raw text becomes an <em>index key</em>.
 *
 * <p><b>Case policy (decided and documented, per project spec):</b> the index is
 * case-insensitive. Every word is keyed by its lower-cased form; the original spelling is
 * kept separately as the <em>display form</em> so results render as "iPhone", not "iphone".
 *
 * <p><b>Why {@link Locale#ROOT} and not the default locale:</b> {@code String.toLowerCase()}
 * uses the JVM's default locale. In a Turkish locale, {@code "I".toLowerCase()} yields the
 * dotless 'i' (U+0131), not 'i'. That would mean an index built on a machine in Istanbul does
 * not match queries served from a machine in London -- a genuinely nasty, locale-dependent
 * correctness bug. Pinning to {@code Locale.ROOT} makes normalization deterministic everywhere.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Normalizes raw text into an index key: trimmed of surrounding whitespace and lower-cased
     * in a locale-independent way.
     *
     * @param raw the raw word or query, must not be null
     * @return the normalized index key (possibly empty if the input was blank)
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new NullPointerException("text must not be null");
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }
}
