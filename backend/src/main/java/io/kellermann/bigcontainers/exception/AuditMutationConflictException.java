package io.kellermann.bigcontainers.exception;

public class AuditMutationConflictException extends ApplicationException {
    public AuditMutationConflictException() {
        super("AUDIT_MUTATION_CONFLICT", "This operation ID was already used with different audit details.");
    }
}
