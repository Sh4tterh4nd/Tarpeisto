package io.kellermann.bigcontainers.model;

/**
 * Why a candidate public asset code failed validation <strong>before</strong> any database
 * lookup (specification section 9.2, ADR-0002 "Validation order"). Every one of these is a
 * transcription error the user can correct by re-reading or re-scanning the label; none of them
 * mean "unknown code" - that is a separate outcome reported only after the checksum has already
 * passed and a database lookup has actually run, per the ADR's "distinguish a transcription
 * error from an unknown code" requirement.
 */
public enum AssetCodeRejectionReason {
    /** Normalized input was empty. */
    EMPTY,
    /** Normalized input is not the length the current code format accepts. */
    INVALID_LENGTH,
    /** Normalized input contains a character outside {@link AssetCode#ALPHABET}. */
    INVALID_SYMBOL,
    /** Every character was a valid alphabet symbol, but the check equation does not hold. */
    CHECKSUM_MISMATCH
}
