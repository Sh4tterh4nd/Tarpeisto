/**
 * Public asset code normalization, checksum, and validation, implemented
 * exactly per docs/adr/ADR-0002-public-asset-code-checksum.md. The backend
 * implements the same construction independently; the ADR's test vectors are
 * the shared contract between both implementations.
 *
 * - Alphabet: Crockford Base32 with ambiguous letters excluded.
 * - Arithmetic: GF(2^5) built from the primitive polynomial x^5 + x^2 + 1.
 * - Weights: the symbol at distance `j` from the right end of the complete
 *   code has weight `g^j`, where `g = 2` is a generator of the field. The
 *   check symbol is rightmost, so it always has weight `g^0 = 1`.
 */

export const ASSET_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** Number of random data symbols in the current code format. */
export const ASSET_CODE_DATA_LENGTH = 5;

/** Total length of the current code format: data symbols plus one check symbol. */
export const ASSET_CODE_LENGTH = ASSET_CODE_DATA_LENGTH + 1;

// x^5 + x^2 + 1, as the low 5 bits added back in on overflow during GF(2^5)
// multiplication (the x^5 term itself is implicit in the overflow check).
const REDUCTION_TERM = 0b00101;
const FIELD_MASK = 0b11111;
const OVERFLOW_BIT = 0b10000;
const FIELD_BITS = 5;

/** The generator `g = 2` (the field element `x`) used for position weights. */
const GENERATOR = 2;

function gfMultiply(a: number, b: number): number {
  let product = 0;
  let x = a & FIELD_MASK;
  let y = b & FIELD_MASK;
  for (let bit = 0; bit < FIELD_BITS; bit++) {
    if (y & 1) {
      product ^= x;
    }
    const overflowed = (x & OVERFLOW_BIT) !== 0;
    x = (x << 1) & FIELD_MASK;
    if (overflowed) {
      x ^= REDUCTION_TERM;
    }
    y >>= 1;
  }
  return product;
}

function gfPow(base: number, exponent: number): number {
  let result = 1;
  for (let i = 0; i < exponent; i++) {
    result = gfMultiply(result, base);
  }
  return result;
}

/** Weight of the symbol at distance `j` from the right end: `g^j`. */
function positionWeight(distanceFromRight: number): number {
  return gfPow(GENERATOR, distanceFromRight);
}

function charValue(symbol: string): number | undefined {
  const index = ASSET_CODE_ALPHABET.indexOf(symbol);
  return index === -1 ? undefined : index;
}

function valueChar(value: number): string {
  const symbol = ASSET_CODE_ALPHABET[value & FIELD_MASK];
  if (symbol === undefined) {
    throw new RangeError(`Field value out of range: ${value}`);
  }
  return symbol;
}

/**
 * Normalizes user input (scanned or typed) before validation: strips
 * whitespace and hyphens, upper-cases, and maps the ambiguous characters
 * `O`, `I`, and `L` to their intended digits.
 */
export function normalizeAssetCode(input: string): string {
  return input.replace(/[\s-]/g, "").toUpperCase().replace(/O/g, "0").replace(/[IL]/g, "1");
}

/**
 * Computes the check symbol for a data portion of any supported length
 * (five symbols in the current format; the construction supports longer
 * future data portions without changing existing codes, per the ADR).
 */
export function computeCheckSymbol(normalizedDataSymbols: string): string {
  const dataValues: number[] = [];
  for (const symbol of normalizedDataSymbols) {
    const value = charValue(symbol);
    if (value === undefined) {
      throw new RangeError(`Not a public-code alphabet symbol: ${symbol}`);
    }
    dataValues.push(value);
  }

  const n = dataValues.length;
  let check = 0;
  for (let i = 0; i < n; i++) {
    const value = dataValues[i];
    if (value === undefined) {
      continue;
    }
    check ^= gfMultiply(positionWeight(n - i), value);
  }
  return valueChar(check);
}

export type AssetCodeValidation =
  | { readonly valid: true; readonly normalized: string }
  | {
      readonly valid: false;
      readonly reason: "empty" | "invalid-length" | "invalid-symbol" | "checksum-mismatch";
      readonly normalized: string;
    };

/**
 * Normalizes and validates a complete public asset code, distinguishing a
 * transcription error (bad length, a symbol outside the alphabet, or a
 * checksum mismatch) from a well-formed code that simply is not known to
 * this validator (validation never performs a lookup; "unknown code" is a
 * separate, later outcome once the checksum is confirmed valid).
 */
export function validateAssetCode(input: string): AssetCodeValidation {
  const normalized = normalizeAssetCode(input);

  if (normalized.length === 0) {
    return { valid: false, reason: "empty", normalized };
  }
  if (normalized.length !== ASSET_CODE_LENGTH) {
    return { valid: false, reason: "invalid-length", normalized };
  }
  for (const symbol of normalized) {
    if (charValue(symbol) === undefined) {
      return { valid: false, reason: "invalid-symbol", normalized };
    }
  }

  const dataPortion = normalized.slice(0, ASSET_CODE_DATA_LENGTH);
  const providedCheck = normalized.slice(ASSET_CODE_DATA_LENGTH);
  const expectedCheck = computeCheckSymbol(dataPortion);

  if (providedCheck !== expectedCheck) {
    return { valid: false, reason: "checksum-mismatch", normalized };
  }
  return { valid: true, normalized };
}

/** Builds a complete, checked public asset code from its data symbols. */
export function formatAssetCode(normalizedDataSymbols: string): string {
  return `${normalizedDataSymbols}${computeCheckSymbol(normalizedDataSymbols)}`;
}
