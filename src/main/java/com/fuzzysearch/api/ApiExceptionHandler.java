package com.fuzzysearch.api;

import com.fuzzysearch.api.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into the same JSON shape, at the right status code.
 *
 * <h2>Why this extends {@link ResponseEntityExceptionHandler}</h2>
 * A bare {@code @ExceptionHandler(Exception.class)} catch-all looks harmless and is not: it
 * swallows Spring's own web exceptions, which already carry correct statuses. An unknown path
 * throws {@code NoResourceFoundException}, which means 404 — the catch-all turned it into a 500,
 * and the integration test caught it. Extending the framework's handler keeps those mappings
 * (404, 405, 415) and leaves the catch-all for genuinely unexpected failures.
 *
 * <p>{@link #handleExceptionInternal} is overridden so framework errors come back in the same
 * {@link ApiError} shape as application errors, rather than switching to Spring's RFC 7807
 * {@code ProblemDetail} for some paths and not others. One shape means the frontend has one code
 * path for failure.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<ApiError> handleInvalidQuery(InvalidQueryException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", e.getMessage()));
    }

    /** e.g. {@code ?limit=abc} — Spring cannot bind it to an int. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request",
                "Parameter '" + e.getName() + "' must be a number."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Logged in full server-side, but never echoed to the client: exception text is exactly
        // where internal paths, class names and structure leak out.
        log.error("unexpected error handling request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "Something went wrong."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("framework error handling request", ex);
        }
        return new ResponseEntity<>(new ApiError(codeFor(statusCode), messageFor(statusCode)),
                headers, statusCode);
    }

    private static String codeFor(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            default -> statusCode.is4xxClientError() ? "invalid_request" : "internal_error";
        };
    }

    private static String messageFor(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 404 -> "No such endpoint.";
            case 405 -> "That HTTP method is not supported on this endpoint.";
            default -> statusCode.is4xxClientError()
                    ? "The request could not be processed."
                    : "Something went wrong.";
        };
    }
}
