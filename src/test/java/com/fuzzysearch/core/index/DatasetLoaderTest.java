package com.fuzzysearch.core.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetLoaderTest {

    private static DatasetLoader.LoadReport load(String content, int maxEntries) {
        return DatasetLoader.load(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "test-data", maxEntries);
    }

    // -------------------------------------------------------------------------------------
    // Separator handling
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("tab, comma, semicolon and space separators all parse")
    void acceptsCommonSeparators() {
        DatasetLoader.LoadReport report = load("""
                the\t23135851162
                apple,12345
                banana; 999
                cherry 42
                """, 0);

        assertThat(report.entries()).extracting(WordEntry::word)
                .containsExactly("the", "apple", "banana", "cherry");
        assertThat(report.entries()).extracting(WordEntry::weight)
                .containsExactly(23_135_851_162L, 12_345L, 999L, 42L);
        assertThat(report.weightSource()).isEqualTo(DatasetLoader.WeightSource.COLUMN);
    }

    @Test
    @DisplayName("multi-word terms survive, because product names and titles are not single words")
    void keepsMultiWordTerms() {
        DatasetLoader.LoadReport report = load("""
                New York\t48210
                olive oil,900
                United States of America 12
                """, 0);

        assertThat(report.entries()).extracting(WordEntry::word)
                .containsExactly("New York", "olive oil", "United States of America");
        assertThat(report.entries().get(2).weight()).isEqualTo(12L);
    }

    @Test
    @DisplayName("terms that legitimately end in digits are not split into term and count")
    void doesNotTruncateTermsEndingInDigits() {
        // The failure mode this guards against: "covid19" parsed as term "covid" with count 19.
        DatasetLoader.LoadReport report = load("""
                covid19
                mp3
                3d
                a1
                """, 0);

        assertThat(report.entries()).extracting(WordEntry::word)
                .containsExactly("covid19", "mp3", "3d", "a1");
        assertThat(report.weightSource()).isEqualTo(DatasetLoader.WeightSource.RANK);
    }

    // -------------------------------------------------------------------------------------
    // Weight derivation
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a file with no counts gets rank-derived weights, heaviest first")
    void assignsRankWeightsWhenCountsAreAbsent() {
        DatasetLoader.LoadReport report = load("""
                first
                second
                third
                """, 0);

        assertThat(report.weightSource()).isEqualTo(DatasetLoader.WeightSource.RANK);
        assertThat(report.entries()).extracting(WordEntry::weight).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("rank weights are distinct, which is what keeps trie ranking meaningful")
    void rankWeightsAreDistinct() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("word").append(i).append('\n');
        }
        DatasetLoader.LoadReport report = load(sb.toString(), 0);

        assertThat(report.entries()).extracting(WordEntry::weight).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("in a counted file, an uncounted line is kept with weight 0 rather than dropped")
    void uncountedLineInACountedFileIsKept() {
        DatasetLoader.LoadReport report = load("""
                the\t100
                orphan
                apple\t50
                """, 0);

        assertThat(report.weightSource()).isEqualTo(DatasetLoader.WeightSource.COLUMN);
        assertThat(report.entries()).extracting(WordEntry::word)
                .containsExactly("the", "orphan", "apple");
        assertThat(report.entries().get(1).weight()).isZero();
    }

    @Test
    @DisplayName("a count too large for a long is clamped, not discarded")
    void clampsOverflowingCount() {
        DatasetLoader.LoadReport report = load("the\t99999999999999999999999999\n", 0);

        assertThat(report.entries()).singleElement()
                .extracting(WordEntry::weight).isEqualTo(Long.MAX_VALUE);
    }

    // -------------------------------------------------------------------------------------
    // Filtering and de-duplication
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("blank lines and comments are skipped and counted")
    void skipsBlanksAndComments() {
        DatasetLoader.LoadReport report = load("""
                # a comment
                apple\t10

                banana\t5
                   
                """, 0);

        assertThat(report.entries()).hasSize(2);
        assertThat(report.skipped()).isEqualTo(3);
        assertThat(report.linesRead()).isEqualTo(5);
    }

    @Test
    @DisplayName("case variants are merged, so both engines see the same corpus")
    void mergesCaseVariants() {
        DatasetLoader.LoadReport report = load("""
                apple\t70
                Apple\t30
                banana\t5
                """, 0);

        assertThat(report.entries()).hasSize(2);
        assertThat(report.mergedAway()).isEqualTo(1);
        assertThat(report.entries().get(0).weight()).isEqualTo(100L);
    }

    @Test
    @DisplayName("maxEntries keeps the most frequent terms, since datasets are frequency-ordered")
    void maxEntriesTruncates() {
        DatasetLoader.LoadReport report = load("""
                the\t1000
                of\t900
                and\t800
                rare\t1
                """, 2);

        assertThat(report.entries()).extracting(WordEntry::word).containsExactly("the", "of");
    }

    @Test
    @DisplayName("an empty file loads to an empty corpus rather than failing")
    void emptyFile() {
        DatasetLoader.LoadReport report = load("", 0);

        assertThat(report.entries()).isEmpty();
        assertThat(report.summary()).contains("loaded 0 terms");
    }

    // -------------------------------------------------------------------------------------
    // Sources and reporting
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("loads from a file on disk")
    void loadsFromDisk() throws Exception {
        Path file = Files.createTempFile("corpus", ".txt");
        Files.writeString(file, "apple\t10\nbanana\t5\n");
        try {
            DatasetLoader.LoadReport report = DatasetLoader.load(file, 0);

            assertThat(report.entries()).hasSize(2);
            assertThat(report.source()).endsWith(".txt");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("a missing classpath resource fails loudly, not silently empty")
    void missingClasspathResourceThrows() {
        assertThatThrownBy(() -> DatasetLoader.loadFromClasspath("/data/nope.txt", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found on classpath");
    }

    @Test
    @DisplayName("the report carries everything worth logging at startup")
    void reportSummary() {
        DatasetLoader.LoadReport report = load("apple\t10\nApple\t5\n\nbanana\t1\n", 0);

        assertThat(report.summary())
                .contains("loaded 2 terms")
                .contains("test-data")
                .contains("weights from column");
        assertThat(report.millis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("the loaded corpus feeds both engines and they still agree")
    void loadedCorpusFeedsBothEngines() {
        List<WordEntry> corpus = load("""
                receive\t10000000
                relieve\t500
                receipt\t9000
                banana\t100
                """, 0).entries();

        var naive = new com.fuzzysearch.core.search.NaiveSearchService(corpus);
        var optimized = new com.fuzzysearch.core.search.OptimizedSearchService(corpus);

        assertThat(optimized.search("recieve", 5)).isEqualTo(naive.search("recieve", 5));
        assertThat(optimized.search("rec", 5)).isEqualTo(naive.search("rec", 5));
    }
}
