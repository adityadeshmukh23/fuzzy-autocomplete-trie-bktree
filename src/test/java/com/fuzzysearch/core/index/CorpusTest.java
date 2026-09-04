package com.fuzzysearch.core.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusTest {

    @Test
    @DisplayName("entries sharing a normalized key are merged and their weights summed")
    void mergesCaseVariants() {
        List<WordEntry> merged = Corpus.deduplicate(List.of(
                WordEntry.of("Apple", 30),
                WordEntry.of("apple", 70),
                WordEntry.of("APPLE", 5),
                WordEntry.of("banana", 10)));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).word()).isEqualTo("apple");
        assertThat(merged.get(0).weight()).isEqualTo(105);
        assertThat(merged.get(1).word()).isEqualTo("banana");
    }

    @Test
    @DisplayName("the display spelling is the one with the largest single contribution")
    void displaySpellingIsTheHeaviestContributor() {
        List<WordEntry> merged = Corpus.deduplicate(List.of(
                WordEntry.of("iphone", 10),
                WordEntry.of("iPhone", 900),
                WordEntry.of("IPHONE", 20)));

        assertThat(merged).singleElement().satisfies(entry -> {
            assertThat(entry.word()).isEqualTo("iPhone");
            assertThat(entry.weight()).isEqualTo(930);
        });
    }

    @Test
    @DisplayName("equal contributions break lexicographically, so file order never decides")
    void tiesBreakLexicographically() {
        List<WordEntry> forward = Corpus.deduplicate(List.of(
                WordEntry.of("Apple", 50), WordEntry.of("apple", 50)));
        List<WordEntry> reversed = Corpus.deduplicate(List.of(
                WordEntry.of("apple", 50), WordEntry.of("Apple", 50)));

        assertThat(forward.get(0).word()).isEqualTo("Apple");   // 'A' (65) sorts before 'a' (97)
        assertThat(forward).isEqualTo(reversed);
    }

    @Test
    @DisplayName("input order is otherwise preserved, so a frequency-sorted file stays sorted")
    void preservesInputOrder() {
        List<WordEntry> merged = Corpus.deduplicate(List.of(
                WordEntry.of("zebra", 3), WordEntry.of("apple", 2), WordEntry.of("mango", 1)));

        assertThat(merged).extracting(WordEntry::word).containsExactly("zebra", "apple", "mango");
    }

    @Test
    @DisplayName("an already-unique corpus is unchanged")
    void uniqueCorpusIsUnchanged() {
        List<WordEntry> input = List.of(WordEntry.of("a", 1), WordEntry.of("b", 2));

        assertThat(Corpus.deduplicate(input)).isEqualTo(input);
    }

    @Test
    void emptyCorpus() {
        assertThat(Corpus.deduplicate(List.of())).isEmpty();
    }
}
