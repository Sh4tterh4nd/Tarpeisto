package io.kellermann.tarpeisto.exception;

/** The public first-run setup endpoint was called after an Owner had already been created. */
public class SetupAlreadyCompletedException extends ApplicationException {

    public SetupAlreadyCompletedException() {
        super("SETUP_ALREADY_COMPLETED", "Initial setup has already been completed.");
    }
}
