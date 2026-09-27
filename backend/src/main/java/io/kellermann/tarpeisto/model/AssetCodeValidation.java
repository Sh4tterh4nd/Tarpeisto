package io.kellermann.tarpeisto.model;

/**
 * Closed outcome of {@link AssetCode#validate(String)} (ADR-0002 "Validation order"). The two
 * variants deliberately need different corrective action from the user: {@link Invalid} is a
 * transcription error caught before any database lookup ran at all, while a well-formed
 * {@link Valid} code that a subsequent repository lookup does not find is a separate "unknown
 * code" outcome that callers report themselves (typically via {@code NotFoundException}) - it is
 * not represented by this sealed hierarchy.
 */
public sealed interface AssetCodeValidation {

    record Valid(AssetCode code) implements AssetCodeValidation {}

    record Invalid(AssetCodeRejectionReason reason, String normalized) implements AssetCodeValidation {}
}
