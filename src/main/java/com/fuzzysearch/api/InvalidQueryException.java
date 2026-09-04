package com.fuzzysearch.api;

/** Thrown when a request fails validation; mapped to HTTP 400 by {@link ApiExceptionHandler}. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
