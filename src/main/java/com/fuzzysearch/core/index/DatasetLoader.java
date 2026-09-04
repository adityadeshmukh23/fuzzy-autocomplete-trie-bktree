package com.fuzzysearch.core.index;

import com.fuzzysearch.core.text.TextNormalizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a word-frequency file into the corpus both search engines are built from.
 *
 * <h2>Accepted formats</h2>
 * One entry per line, with the count separated from the term by a tab, comma, semicolon or
 * space:
 * <pre>
 *   the      23135851162
 *   New York,48210
 *   apple; 12345
 *   banana
 * </pre>
 *
 * <p>The term may itself contain spaces, so multi-word entries ("New York", "olive oil") work --
 * which matters because product names and article titles are the natural datasets for
 * autocomplete, and they are rarely single words. The count is only recognised when a separator
 * precedes it, so terms that legitimately end in digits ("covid19", "3d", "mp3") are not
 * silently truncated into a term and a count.
 *
 * <h2>Missing counts</h2>
 * A file with no counts at all is a problem, not a detail: if every word has the same weight,
 * ranking carries no information and the trie's best-first search degrades to expanding whole
 * subtrees (see {@code docs/complexity.md}). So when no counts are found, weights are assigned by
 * <b>rank</b> -- first line heaviest -- on the assumption that such lists are ordered by
 * frequency. {@link LoadReport#weightSource()} records which rule was used, so this is never a
 * silent guess.
 */
public final class DatasetLoader {

    /** Term, then a separator, then digits anchored at end of line. */
    private static final Pattern TERM_AND_COUNT = Pattern.compile("^(.+?)[\\t,; ]+(\\d+)\\s*$");

    /** How the weights in a {@link LoadReport} were derived. */
    public enum WeightSource {
        /** Parsed from a count column in the file. */
        COLUMN,
        /** Synthesised from line order, because the file had no counts. */
        RANK
    }

    /**
     * What a load produced, including everything worth logging at startup.
     *
     * @param entries      the de-duplicated corpus, ready to index
     * @param linesRead    lines consumed from the source
     * @param skipped      lines rejected as blank, comments or unusable
     * @param mergedAway   entries lost to de-duplication (case variants and exact repeats)
     * @param weightSource whether weights came from the file or from line order
     * @param millis       wall-clock parse time
     * @param source       a human-readable source name, for logs
     */
    public record LoadReport(List<WordEntry> entries, int linesRead, int skipped, int mergedAway,
                             WeightSource weightSource, long millis, String source) {

        /** One line, suitable for logging at application startup. */
        public String summary() {
            return String.format(
                    "loaded %,d terms from %s in %,d ms (%,d lines read, %,d skipped, "
                            + "%,d merged, weights from %s)",
                    entries.size(), source, millis, linesRead, skipped, mergedAway,
                    weightSource.name().toLowerCase());
        }
    }

    private DatasetLoader() {
    }

    /** Loads from a file on disk. */
    public static LoadReport load(Path path, int maxEntries) {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in, path.getFileName().toString(), maxEntries);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read dataset: " + path, e);
        }
    }

    /**
     * Loads from a classpath resource -- how the application reads its bundled dataset, so that
     * a deployed JAR carries its own index data and needs no filesystem layout.
     */
    public static LoadReport loadFromClasspath(String resource, int maxEntries) {
        try (InputStream in = DatasetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("dataset not found on classpath: " + resource);
            }
            return load(in, resource, maxEntries);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read dataset: " + resource, e);
        }
    }

    /**
     * @param maxEntries stop after this many usable lines; datasets are frequency-ordered, so
     *                   this keeps the most common terms. Non-positive means "read everything".
     */
    public static LoadReport load(InputStream in, String source, int maxEntries) {
        final long start = System.nanoTime();

        final List<String> terms = new ArrayList<>();
        final List<Long> counts = new ArrayList<>();
        int linesRead = 0;
        int skipped = 0;
        boolean sawAnyCount = false;

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (maxEntries > 0 && terms.size() >= maxEntries) {
                    break;
                }
                linesRead++;

                final String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    skipped++;
                    continue;
                }

                String term = trimmed;
                long count = -1L;

                final Matcher matcher = TERM_AND_COUNT.matcher(trimmed);
                if (matcher.matches()) {
                    term = matcher.group(1).strip();
                    try {
                        count = Long.parseLong(matcher.group(2));
                        sawAnyCount = true;
                    } catch (NumberFormatException overflow) {
                        // A count too large for a long is still a very common word; clamp rather
                        // than discard it.
                        count = Long.MAX_VALUE;
                        sawAnyCount = true;
                    }
                }

                // A surviving tab means the line had more columns than we understand, so the
                // "term" is really a mangled fragment. Better to drop it than to index garbage.
                if (term.isEmpty() || term.indexOf('\t') >= 0
                        || TextNormalizer.normalize(term).isEmpty()) {
                    skipped++;
                    continue;
                }

                terms.add(term);
                counts.add(count);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read dataset: " + source, e);
        }

        final WeightSource weightSource = sawAnyCount ? WeightSource.COLUMN : WeightSource.RANK;

        final List<WordEntry> raw = new ArrayList<>(terms.size());
        for (int i = 0; i < terms.size(); i++) {
            final long weight = switch (weightSource) {
                // Lines with a term but no count in an otherwise-counted file get weight 0
                // rather than being dropped: they are real vocabulary, just unranked.
                case COLUMN -> Math.max(0L, counts.get(i));
                // Rank-derived: first line heaviest, so a frequency-ordered list keeps its order.
                case RANK -> (long) (terms.size() - i);
            };
            raw.add(WordEntry.of(terms.get(i), weight));
        }

        final List<WordEntry> entries = Corpus.deduplicate(raw);
        final long millis = (System.nanoTime() - start) / 1_000_000L;

        return new LoadReport(entries, linesRead, skipped, raw.size() - entries.size(),
                weightSource, millis, source);
    }
}
