package io.kellermann.bigcontainers.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A validated six-character public asset code (specification section 9, ADR-0002 - authoritative
 * and non-negotiable for this construction; its test-vector tables are the contract).
 *
 * <p>The data alphabet is Crockford Base32 with ambiguous letters excluded. Symbol values are
 * elements of the finite field {@code GF(2^5)} built from the primitive polynomial
 * {@code x^5 + x^2 + 1}. {@code g = 2} is a generator; the symbol at distance {@code j} from the
 * <strong>right</strong> end of a complete code has weight {@code g^j}, so the rightmost (check)
 * symbol always has weight {@code g^0 = 1}. This guarantees detection of every single-symbol
 * substitution and every adjacent transposition of two different symbols - see the ADR's
 * "Error-detection guarantees" section for the proof.
 *
 * <p>This is deliberately a small, dependency-free domain library, not something welded to an
 * entity: {@link io.kellermann.bigcontainers.service.AssetCodeGenerationService} uses it to
 * generate codes, and Phase 2b's {@code physical_asset} table (out of scope here) will use
 * {@link #validate(String)} for scan/manual-entry lookup once it exists.
 *
 * <p>The same construction is implemented independently in the frontend
 * ({@code frontend/apps/web/src/features/asset-code/normalizeAssetCode.ts}, Phase 0, out of
 * scope for this change) so a mistyped code gets immediate client-side feedback; this class
 * remains the authoritative, server-side implementation and both are tested against the same
 * ADR-0002 vector tables.
 */
public record AssetCode(String value) {

    /** Crockford Base32 with ambiguous letters excluded (ADR-0002 "Alphabet"). {@code U} is not accepted. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Number of random data symbols in the current code format. */
    public static final int DATA_LENGTH = 5;

    /** Total length of the current code format: data symbols plus one check symbol. */
    public static final int LENGTH = DATA_LENGTH + 1;

    // x^5 + x^2 + 1: the low 5 bits added back in on overflow during GF(2^5) multiplication (the
    // x^5 term itself is implicit in the overflow check below).
    private static final int REDUCTION_TERM = 0b00101;
    private static final int FIELD_MASK = 0b11111;
    private static final int OVERFLOW_BIT = 0b10000;
    private static final int FIELD_BITS = 5;

    /** The generator {@code g = 2} (the field element {@code x}) used for position weights. */
    private static final int GENERATOR = 2;

    /**
     * Validates that {@code value} is already a normalized, checksummed code, rejecting a
     * direct construction the same way {@link #validate(String)} would (both routes funnel
     * through {@link #rejectionReasonFor(String)}).
     */
    public AssetCode {
        Objects.requireNonNull(value, "value must not be null");
        AssetCodeRejectionReason reason = rejectionReasonFor(value);
        if (reason != null) {
            throw new IllegalArgumentException("Not a valid checked asset code (" + reason + "): " + value);
        }
    }

    /**
     * Normalizes user input (scanned or typed), before validation, for both scanned and manually
     * entered input (ADR-0002 "Normalization"): strips whitespace and hyphens, upper-cases, and
     * maps the ambiguous characters {@code O}, {@code I}, and {@code L} to their intended digits.
     */
    public static String normalize(String input) {
        Objects.requireNonNull(input, "input must not be null");
        StringBuilder normalized = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || c == '-') {
                continue;
            }
            char upper = Character.toUpperCase(c);
            switch (upper) {
                case 'O' -> normalized.append('0');
                case 'I', 'L' -> normalized.append('1');
                default -> normalized.append(upper);
            }
        }
        return normalized.toString();
    }

    /**
     * Normalizes and validates a complete public asset code (ADR-0002 "Validation order", steps
     * 1-3: normalize, reject an unsupported length/symbol, verify the check equation). Never
     * performs a database lookup; "unknown code" is a separate, later outcome a caller reports
     * once it has a {@link AssetCodeValidation.Valid} result and the lookup itself fails.
     */
    public static AssetCodeValidation validate(String rawInput) {
        String normalized = normalize(rawInput);
        AssetCodeRejectionReason reason = rejectionReasonFor(normalized);
        if (reason != null) {
            return new AssetCodeValidation.Invalid(reason, normalized);
        }
        return new AssetCodeValidation.Valid(new AssetCode(normalized));
    }

    /**
     * Computes the check symbol for a data portion of any supported length (five symbols in the
     * current format; the construction supports longer future data portions without changing
     * existing codes, per the ADR's "Check equation" section).
     */
    public static char computeCheckSymbol(String normalizedDataSymbols) {
        Objects.requireNonNull(normalizedDataSymbols, "normalizedDataSymbols must not be null");
        int n = normalizedDataSymbols.length();
        int check = 0;
        for (int i = 0; i < n; i++) {
            int value = charValue(normalizedDataSymbols.charAt(i));
            check ^= gfMultiply(positionWeight(n - i), value);
        }
        return valueChar(check);
    }

    /** Builds a complete, checked public asset code from already-normalized data symbols. */
    public static AssetCode format(String normalizedDataSymbols) {
        return new AssetCode(normalizedDataSymbols + computeCheckSymbol(normalizedDataSymbols));
    }

    private static AssetCodeRejectionReason rejectionReasonFor(String normalized) {
        if (normalized.isEmpty()) {
            return AssetCodeRejectionReason.EMPTY;
        }
        if (normalized.length() != LENGTH) {
            return AssetCodeRejectionReason.INVALID_LENGTH;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return AssetCodeRejectionReason.INVALID_SYMBOL;
            }
        }
        String dataPortion = normalized.substring(0, DATA_LENGTH);
        char providedCheck = normalized.charAt(DATA_LENGTH);
        if (providedCheck != computeCheckSymbol(dataPortion)) {
            return AssetCodeRejectionReason.CHECKSUM_MISMATCH;
        }
        return null;
    }

    private static int charValue(char symbol) {
        int index = ALPHABET.indexOf(symbol);
        if (index < 0) {
            throw new IllegalArgumentException("Not a public-code alphabet symbol: " + symbol);
        }
        return index;
    }

    private static char valueChar(int value) {
        return ALPHABET.charAt(value & FIELD_MASK);
    }

    /** Weight of the symbol at distance {@code j} from the right end: {@code g^j}. */
    private static int positionWeight(int distanceFromRight) {
        return gfPow(GENERATOR, distanceFromRight);
    }

    private static int gfPow(int base, int exponent) {
        int result = 1;
        for (int i = 0; i < exponent; i++) {
            result = gfMultiply(result, base);
        }
        return result;
    }

    /** Carry-less polynomial multiplication in {@code GF(2^5)}, reduced by the primitive polynomial. */
    private static int gfMultiply(int a, int b) {
        int product = 0;
        int x = a & FIELD_MASK;
        int y = b & FIELD_MASK;
        for (int bit = 0; bit < FIELD_BITS; bit++) {
            if ((y & 1) != 0) {
                product ^= x;
            }
            boolean overflowed = (x & OVERFLOW_BIT) != 0;
            x = (x << 1) & FIELD_MASK;
            if (overflowed) {
                x ^= REDUCTION_TERM;
            }
            y >>= 1;
        }
        return product;
    }

    @Override
    public String toString() {
        return value.toUpperCase(Locale.ROOT);
    }
}
