package com.fuzzysearch.core.index;

/**
 * The dataset shipped inside the JAR.
 *
 * <p>Bundling it as a classpath resource rather than reading a path from disk means a deployed
 * artifact carries its own index data and needs no filesystem layout, no volume mount and no
 * download step at boot -- which matters for the free-tier hosting this is aimed at in Phase 8.
 *
 * <p>See {@code src/main/resources/data/README.md} for provenance and for why the source file is
 * truncated where it is.
 */
public final class BundledDataset {

    /** 100,000 English words with Google Web Trillion Word Corpus frequencies. */
    public static final String RESOURCE = "/data/word-frequencies.txt";

    /** Entries to load by default -- the whole bundled file. */
    public static final int DEFAULT_MAX_ENTRIES = 0;

    private BundledDataset() {
    }

    /** Loads the entire bundled dataset. */
    public static DatasetLoader.LoadReport load() {
        return load(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Loads the bundled dataset, optionally truncated.
     *
     * @param maxEntries keep only the first N (most frequent) terms; 0 means all of them. The
     *                   benchmark uses this to build indexes at a range of corpus sizes.
     */
    public static DatasetLoader.LoadReport load(int maxEntries) {
        return DatasetLoader.loadFromClasspath(RESOURCE, maxEntries);
    }
}
