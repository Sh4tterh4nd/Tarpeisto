package io.kellermann.bigcontainers.exception;

public class ReviewMutationConflictException extends ApplicationException {
    public ReviewMutationConflictException(String message) {
        super("REVIEW_MUTATION_CONFLICT", message);
    }
}
