package com.fuzzysearch.api.dto;

/**
 * A consistently shaped error body, so the frontend never has to parse a stack trace or guess at
 * a bare status code.
 *
 * @param error   short machine-readable code
 * @param message what went wrong and what to do instead
 */
public record ApiError(String error, String message) {
}
