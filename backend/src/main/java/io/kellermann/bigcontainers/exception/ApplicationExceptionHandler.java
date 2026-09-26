package io.kellermann.bigcontainers.exception;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the stable {@link ApplicationException} hierarchy to RFC 9457 {@link ProblemDetail}
 * responses.
 *
 * <p>This advice intentionally handles only {@link ApplicationException} subtypes and does not
 * add a catch-all {@code Exception} handler: Spring Boot's own {@code
 * spring.mvc.problemdetails.enabled=true} already converts standard MVC exceptions (unmapped
 * routes, unsupported methods, bean-validation failures, ...) into RFC 9457 problem documents
 * with the correct status, and a broad handler here would shadow that more specific handling.
 */
@RestControllerAdvice
public class ApplicationExceptionHandler {
    @ExceptionHandler(AuditMutationConflictException.class)
    public ProblemDetail handleAuditMutationConflict(AuditMutationConflictException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Audit mutation conflict", exception);
    }

    private static final String PROBLEM_TYPE_PREFIX = "urn:bigcontainers:problem:";

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException exception) {
        return problemDetail(HttpStatus.NOT_FOUND, "Not Found", exception);
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ProblemDetail handleValidationFailed(ValidationFailedException exception) {
        return problemDetail(HttpStatus.BAD_REQUEST, "Validation Failed", exception);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception) {
        return problemDetail(HttpStatus.UNAUTHORIZED, "Invalid Credentials", exception);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail handleRateLimitExceeded(RateLimitExceededException exception) {
        return problemDetail(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", exception);
    }

    @ExceptionHandler(InvalidAssetCodeException.class)
    public ProblemDetail handleInvalidAssetCode(InvalidAssetCodeException exception) {
        ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, "Invalid Asset Code", exception);
        problem.setProperty("reason", exception.reason().name());
        return problem;
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Insufficient Stock", exception);
    }

    @ExceptionHandler(StaleMediaVersionException.class)
    public ProblemDetail handleStaleMediaVersion(StaleMediaVersionException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Stale Media Version", exception);
    }

    @ExceptionHandler(StalePlacementVersionException.class)
    public ProblemDetail handleStalePlacementVersion(StalePlacementVersionException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Stale Placement Version", exception);
    }

    @ExceptionHandler(StalePackingRequirementVersionException.class)
    public ProblemDetail handleStalePackingVersion(StalePackingRequirementVersionException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Stale Packing Version", exception);
    }

    @ExceptionHandler(StaleBookingVersionException.class)
    public ProblemDetail handleStaleBookingVersion(StaleBookingVersionException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Stale Booking Version", exception);
    }

    @ExceptionHandler(PackingConflictException.class)
    public ProblemDetail handlePackingConflict(PackingConflictException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Packing Conflict", exception);
    }

    @ExceptionHandler(StaleLocationVersionException.class)
    public ProblemDetail handleStaleLocationVersion(StaleLocationVersionException exception) {
        return problemDetail(HttpStatus.CONFLICT, "Stale Location Version", exception);
    }

    @ExceptionHandler(PackingRemovalConfirmationException.class)
    public ProblemDetail handlePackingRemovalConfirmation(PackingRemovalConfirmationException exception) {
        ProblemDetail p = problemDetail(HttpStatus.CONFLICT, "Packing removal confirmation required", exception);
        p.setProperty("affectedBookingIds", exception.affectedBookingIds());
        return p;
    }

    @ExceptionHandler(BookingMutationConflictException.class)
    public ProblemDetail handleBookingMutationConflict(BookingMutationConflictException e) {
        return problemDetail(HttpStatus.CONFLICT, "Booking mutation conflict", e);
    }

    private ProblemDetail problemDetail(HttpStatus status, String title, ApplicationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + toKebabCase(exception.getErrorCode())));
        problem.setProperty("errorCode", exception.getErrorCode());
        return problem;
    }

    private static String toKebabCase(String errorCode) {
        return errorCode.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
