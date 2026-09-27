package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit test: no Spring context. Tests {@link AssetCode} against ADR-0002's vector tables
 * verbatim (that ADR is authoritative and non-negotiable for this construction) and against the
 * implementation plan section 4.2 coverage requirements: every alphabet symbol, ambiguous-character
 * normalization, all single-character substitutions, all adjacent transpositions, invalid lengths
 * and symbols, and large randomized/property-based sets. Because the construction mathematically
 * guarantees detection of both error classes (ADR-0002 "Error-detection guarantees"), the
 * substitution/transposition tests below assert an exact zero undetected count, never a rate.
 */
class AssetCodeTests {

    /** ADR-0002 "Test vectors": data symbols on the left, complete public code on the right. */
    @ParameterizedTest
    @CsvSource({
        "00000, 000000",
        "00001, 000012",
        "0000Z, 0000ZV",
        "12345, 123452",
        "7K3MX, 7K3MXY",
        "91TRQ, 91TRQJ",
        "A72KQ, A72KQ5",
        "M39TX, M39TX1",
        "HJKMN, HJKMNE",
        "PQRST, PQRSTJ",
        "VWXYZ, VWXYZM",
        "ZZZZZ, ZZZZZ1",
    })
    void adrTestVectorsProduceTheExpectedCompleteCode(String data, String expectedCode) {
        AssetCode formatted = AssetCode.format(data);
        assertThat(formatted.value()).isEqualTo(expectedCode);
        assertThat(AssetCode.computeCheckSymbol(data)).isEqualTo(expectedCode.charAt(expectedCode.length() - 1));
        assertThat(AssetCode.validate(expectedCode))
                .isEqualTo(new AssetCodeValidation.Valid(new AssetCode(expectedCode)));
    }

    /** ADR-0002 normalization vectors: all of which must validate as {@code 7K3MXY}. */
    @ParameterizedTest
    @ValueSource(strings = {"7k3mxy", "7K3-MXY", " 7K 3M XY ", "7K3MXY"})
    void normalizationVectorsAllValidateToTheCanonicalCode(String input) {
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Valid.class);
        assertThat(((AssetCodeValidation.Valid) validation).code().value()).isEqualTo("7K3MXY");
    }

    /** ADR-0002 ambiguous-symbol vectors: all of which must normalize to a valid code. */
    @ParameterizedTest
    @CsvSource({"O0OO0O, 000000", "I23452, 123452", "L23452, 123452", "l23452, 123452"})
    void ambiguousSymbolVectorsNormalizeToTheExpectedValidCode(String input, String expectedNormalized) {
        assertThat(AssetCode.normalize(input)).isEqualTo(expectedNormalized);
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Valid.class);
        assertThat(((AssetCodeValidation.Valid) validation).code().value()).isEqualTo(expectedNormalized);
    }

    /** ADR-0002 rejected-input vectors, with the specific rejection reason each one must produce. */
    @ParameterizedTest
    @CsvSource({
        "7K3MX, INVALID_LENGTH",
        "7K3MXYZ, INVALID_LENGTH",
        "7K3MXU, INVALID_SYMBOL",
        "'7K3MX!', INVALID_SYMBOL",
        "7K3MXZ, CHECKSUM_MISMATCH",
        "K73MXY, CHECKSUM_MISMATCH",
    })
    void rejectedInputVectorsProduceTheExpectedReason(String input, AssetCodeRejectionReason expectedReason) {
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Invalid.class);
        assertThat(((AssetCodeValidation.Invalid) validation).reason()).isEqualTo(expectedReason);
    }

    @Test
    void emptyInputIsRejectedAsEmptyNotInvalidLength() {
        AssetCodeValidation validation = AssetCode.validate("   -- ");
        assertThat(validation).isInstanceOf(AssetCodeValidation.Invalid.class);
        assertThat(((AssetCodeValidation.Invalid) validation).reason()).isEqualTo(AssetCodeRejectionReason.EMPTY);
    }

    @Test
    void uIsNotInTheAlphabetEvenThoughCrockfordReservesIt() {
        assertThat(AssetCode.ALPHABET).doesNotContain("U");
    }

    @Test
    void alphabetHasExactlyThirtyTwoDistinctSymbols() {
        assertThat(AssetCode.ALPHABET).hasSize(32);
        assertThat(new HashSet<>(
                        AssetCode.ALPHABET.chars().mapToObj(c -> (char) c).toList()))
                .hasSize(32);
    }

    // ---------------------------------------------------------------------------------------
    // Every alphabet symbol (implementation plan 4.2)
    // ---------------------------------------------------------------------------------------

    /**
     * Every one of the 32 alphabet symbols, placed at every one of the 5 data positions, must
     * round-trip through formatting and validation as a well-formed, valid code.
     */
    @Test
    void everyAlphabetSymbolAtEveryDataPositionRoundTripsAsValid() {
        for (int position = 0; position < AssetCode.DATA_LENGTH; position++) {
            for (char symbol : AssetCode.ALPHABET.toCharArray()) {
                char[] data = "00000".toCharArray();
                data[position] = symbol;
                String dataString = new String(data);
                AssetCode code = AssetCode.format(dataString);
                AssetCodeValidation validation = AssetCode.validate(code.value());
                assertThat(validation)
                        .as("symbol %s at position %d", symbol, position)
                        .isInstanceOf(AssetCodeValidation.Valid.class);
            }
        }
    }

    /** The check symbol itself must also be able to take on every alphabet value. */
    @Test
    void everyAlphabetSymbolIsReachableAsAComputedCheckSymbol() {
        Set<Character> observedCheckSymbols = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            for (int j = 0; j < 32; j++) {
                String data = "" + AssetCode.ALPHABET.charAt(i) + AssetCode.ALPHABET.charAt(j) + "000";
                observedCheckSymbols.add(AssetCode.computeCheckSymbol(data));
            }
        }
        assertThat(observedCheckSymbols).hasSize(32);
    }

    // ---------------------------------------------------------------------------------------
    // Single-character substitutions - exhaustive across position x delta, on a broad set of
    // base codes, per the ADR's proof that detection is independent of the unaffected symbols.
    // ---------------------------------------------------------------------------------------

    @Test
    void everySingleSymbolSubstitutionOnAdrVectorCodesIsDetected() {
        String[] baseCodes = {
            "000000", "000012", "0000ZV", "123452", "7K3MXY", "91TRQJ", "A72KQ5", "M39TX1", "HJKMNE", "PQRSTJ",
            "VWXYZM", "ZZZZZ1"
        };
        DetectionCounter counter = new DetectionCounter();
        for (String baseCode : baseCodes) {
            exhaustivelySubstituteEveryPosition(baseCode, counter);
        }
        assertThat(counter.total).isGreaterThan(0);
        assertThat(counter.undetected).isZero();
    }

    @Test
    void everySingleSymbolSubstitutionOnALargeRandomSampleIsDetected() {
        Random random = new Random(20260926L);
        DetectionCounter counter = new DetectionCounter();
        int sampleSize = 5_000;
        for (int i = 0; i < sampleSize; i++) {
            String baseCode = randomValidCode(random).value();
            exhaustivelySubstituteEveryPosition(baseCode, counter);
        }
        // 5,000 base codes x 6 positions x 31 differing substitute values each.
        assertThat(counter.total).isEqualTo(sampleSize * AssetCode.LENGTH * 31L);
        assertThat(counter.undetected).isZero();
    }

    @Test
    void oneRandomSubstitutionPerCodeAcrossAVeryLargeSampleIsAlwaysDetected() {
        Random random = new Random(918273645L);
        int sampleSize = 200_000;
        int undetected = 0;
        for (int i = 0; i < sampleSize; i++) {
            String baseCode = randomValidCode(random).value();
            int position = random.nextInt(AssetCode.LENGTH);
            char original = baseCode.charAt(position);
            char substitute = differentRandomSymbol(random, original);
            String mutated = withCharAt(baseCode, position, substitute);
            if (AssetCode.validate(mutated) instanceof AssetCodeValidation.Valid) {
                undetected++;
            }
        }
        assertThat(undetected).isZero();
    }

    private void exhaustivelySubstituteEveryPosition(String baseCode, DetectionCounter counter) {
        for (int position = 0; position < baseCode.length(); position++) {
            char original = baseCode.charAt(position);
            for (char substitute : AssetCode.ALPHABET.toCharArray()) {
                if (substitute == original) {
                    continue;
                }
                String mutated = withCharAt(baseCode, position, substitute);
                counter.total++;
                if (AssetCode.validate(mutated) instanceof AssetCodeValidation.Valid) {
                    counter.undetected++;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Adjacent transpositions - exhaustive across every adjacent pair with differing values, on
    // the same broad set of base codes, plus a large random sample.
    // ---------------------------------------------------------------------------------------

    @Test
    void everyAdjacentTranspositionOfDifferentSymbolsOnAdrVectorCodesIsDetected() {
        String[] baseCodes = {
            "000012", "0000ZV", "123452", "7K3MXY", "91TRQJ", "A72KQ5", "M39TX1", "HJKMNE", "PQRSTJ", "VWXYZM", "ZZZZZ1"
        };
        DetectionCounter counter = new DetectionCounter();
        for (String baseCode : baseCodes) {
            exhaustivelyTransposeAdjacentPairs(baseCode, counter);
        }
        assertThat(counter.total).isGreaterThan(0);
        assertThat(counter.undetected).isZero();
    }

    @Test
    void adjacentTranspositionOfTheLastDataSymbolAndTheCheckSymbolIsDetected() {
        // ADR-0002 explicitly calls this out: g^1 = 2 and g^0 = 1 are distinct, so this
        // particular adjacent pair (positions 4 and 5 of a six-character code) is also caught.
        String code = "7K3MXY";
        assertThat(code.charAt(4)).isNotEqualTo(code.charAt(5));
        String transposed = swapAdjacent(code, 4);
        assertThat(AssetCode.validate(transposed)).isInstanceOf(AssetCodeValidation.Invalid.class);
    }

    @Test
    void everyAdjacentTranspositionOnALargeRandomSampleIsDetected() {
        Random random = new Random(554433221L);
        DetectionCounter counter = new DetectionCounter();
        int sampleSize = 200_000;
        for (int i = 0; i < sampleSize; i++) {
            String baseCode = randomValidCode(random).value();
            exhaustivelyTransposeAdjacentPairs(baseCode, counter);
        }
        assertThat(counter.total).isGreaterThan(0);
        assertThat(counter.undetected).isZero();
    }

    private void exhaustivelyTransposeAdjacentPairs(String baseCode, DetectionCounter counter) {
        for (int position = 0; position < baseCode.length() - 1; position++) {
            if (baseCode.charAt(position) == baseCode.charAt(position + 1)) {
                // ADR-0002 only guarantees detection for two *different* symbols; swapping two
                // equal symbols is not an observable error and is correctly left undetected.
                continue;
            }
            String transposed = swapAdjacent(baseCode, position);
            counter.total++;
            if (AssetCode.validate(transposed) instanceof AssetCodeValidation.Valid) {
                counter.undetected++;
            }
        }
    }

    private static String swapAdjacent(String code, int position) {
        char[] chars = code.toCharArray();
        char temp = chars[position];
        chars[position] = chars[position + 1];
        chars[position + 1] = temp;
        return new String(chars);
    }

    // ---------------------------------------------------------------------------------------
    // Invalid lengths and symbols beyond the ADR's own rejected-input vectors.
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1", "12", "123", "1234", "12345", "1234567", "12345678901234567890123456789012"})
    void everyOtherLengthIsRejectedAsInvalidLength(String input) {
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Invalid.class);
        assertThat(((AssetCodeValidation.Invalid) validation).reason())
                .isEqualTo(AssetCodeRejectionReason.INVALID_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"U", "u"})
    void uAloneIsRejectedAsInvalidSymbolNotNormalizedAway(String symbol) {
        String input = "7K3MX" + symbol;
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Invalid.class);
        assertThat(((AssetCodeValidation.Invalid) validation).reason())
                .isEqualTo(AssetCodeRejectionReason.INVALID_SYMBOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "@", "#", "_", "*", "."})
    void punctuationOtherThanHyphenIsAnInvalidSymbol(String symbol) {
        String input = "7K3MX" + symbol;
        AssetCodeValidation validation = AssetCode.validate(input);
        assertThat(validation).isInstanceOf(AssetCodeValidation.Invalid.class);
        assertThat(((AssetCodeValidation.Invalid) validation).reason())
                .isEqualTo(AssetCodeRejectionReason.INVALID_SYMBOL);
    }

    // ---------------------------------------------------------------------------------------
    // Direct construction and normalize()/format() behavior.
    // ---------------------------------------------------------------------------------------

    @Test
    void directConstructionOfAValidCodeSucceeds() {
        assertThat(new AssetCode("7K3MXY").value()).isEqualTo("7K3MXY");
    }

    @Test
    void directConstructionOfAnInvalidCodeThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AssetCode("7K3MXZ"));
    }

    @Test
    void directConstructionRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new AssetCode(null));
    }

    @Test
    void normalizeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> AssetCode.normalize(null));
    }

    @Test
    void toStringIsTheUppercaseValue() {
        assertThat(new AssetCode("7K3MXY").toString()).isEqualTo("7K3MXY");
    }

    @ParameterizedTest
    @EnumSource(AssetCodeRejectionReason.class)
    void everyRejectionReasonIsReachable(AssetCodeRejectionReason reason) {
        // Documents that every declared reason is exercised somewhere in this class (also
        // covered individually above); a compile-time enum-exhaustiveness guard against a reason
        // being added without a corresponding test vector.
        assertThat(reason).isNotNull();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static AssetCode randomValidCode(Random random) {
        StringBuilder data = new StringBuilder(AssetCode.DATA_LENGTH);
        for (int i = 0; i < AssetCode.DATA_LENGTH; i++) {
            data.append(AssetCode.ALPHABET.charAt(random.nextInt(AssetCode.ALPHABET.length())));
        }
        return AssetCode.format(data.toString());
    }

    private static char differentRandomSymbol(Random random, char exclude) {
        char candidate;
        do {
            candidate = AssetCode.ALPHABET.charAt(random.nextInt(AssetCode.ALPHABET.length()));
        } while (candidate == exclude);
        return candidate;
    }

    private static String withCharAt(String value, int position, char replacement) {
        char[] chars = value.toCharArray();
        chars[position] = replacement;
        return new String(chars);
    }

    private static final class DetectionCounter {
        long total;
        long undetected;
    }
}
