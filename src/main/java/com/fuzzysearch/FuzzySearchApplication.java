package com.fuzzysearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>Everything under {@code com.fuzzysearch.core} is deliberately free of Spring: the trie,
 * BK-tree, edit distance and ranking are plain Java, unit-testable without a container, and would
 * drop into any other application unchanged. Spring appears only at this boundary and in
 * {@code com.fuzzysearch.api} / {@code com.fuzzysearch.config}.
 */
@SpringBootApplication
public class FuzzySearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuzzySearchApplication.class, args);
    }
}
