import { describe, expect, it } from "vitest";
import {
  ASSET_CODE_ALPHABET,
  computeCheckSymbol,
  formatAssetCode,
  normalizeAssetCode,
  validateAssetCode,
} from "./normalizeAssetCode";

// Test vectors are taken verbatim from docs/adr/ADR-0002-public-asset-code-checksum.md.
// They are the shared contract between the frontend and backend implementations.

describe("computeCheckSymbol / formatAssetCode (ADR-0002 generation vectors)", () => {
  const vectors: Array<[data: string, code: string]> = [
    ["00000", "000000"],
    ["00001", "000012"],
    ["0000Z", "0000ZV"],
    ["12345", "123452"],
    ["7K3MX", "7K3MXY"],
    ["91TRQ", "91TRQJ"],
    ["A72KQ", "A72KQ5"],
    ["M39TX", "M39TX1"],
    ["HJKMN", "HJKMNE"],
    ["PQRST", "PQRSTJ"],
    ["VWXYZ", "VWXYZM"],
    ["ZZZZZ", "ZZZZZ1"],
  ];

  it.each(vectors)("data %s produces public code %s", (data, code) => {
    expect(formatAssetCode(data)).toBe(code);
    expect(computeCheckSymbol(data)).toBe(code.slice(-1));
  });

  it.each(vectors)("public code %s for data %s validates as valid", (data, code) => {
    expect(validateAssetCode(code)).toEqual({ valid: true, normalized: code });
  });
});

describe("normalizeAssetCode (ADR-0002 normalization vectors)", () => {
  const inputs = ["7k3mxy", "7K3-MXY", " 7K 3M XY ", "7K3MXY"];

  it.each(inputs)("%s normalizes to the canonical code 7K3MXY", (input) => {
    expect(normalizeAssetCode(input)).toBe("7K3MXY");
    expect(validateAssetCode(input)).toEqual({ valid: true, normalized: "7K3MXY" });
  });
});

describe("normalizeAssetCode (ADR-0002 ambiguous-symbol vectors)", () => {
  const cases: Array<[input: string, normalized: string]> = [
    ["O0OO0O", "000000"],
    ["I23452", "123452"],
    ["L23452", "123452"],
    ["l23452", "123452"],
  ];

  it.each(cases)("%s normalizes to %s and validates", (input, normalized) => {
    expect(normalizeAssetCode(input)).toBe(normalized);
    expect(validateAssetCode(input)).toEqual({ valid: true, normalized });
  });
});

describe("validateAssetCode (ADR-0002 rejected inputs)", () => {
  const cases: Array<[input: string, reason: string]> = [
    ["7K3MX", "invalid-length"],
    ["7K3MXYZ", "invalid-length"],
    ["7K3MXU", "invalid-symbol"],
    ["7K3MX!", "invalid-symbol"],
    ["7K3MXZ", "checksum-mismatch"],
    ["K73MXY", "checksum-mismatch"],
  ];

  it.each(cases)("%s is rejected as %s", (input, reason) => {
    const result = validateAssetCode(input);
    expect(result.valid).toBe(false);
    expect(!result.valid && result.reason).toBe(reason);
  });

  it("distinguishes a transcription error from an empty input", () => {
    expect(validateAssetCode("")).toEqual({ valid: false, reason: "empty", normalized: "" });
  });
});

describe("validateAssetCode alphabet coverage", () => {
  it("computes a checksum for a data portion using every alphabet symbol at least once", () => {
    // 32 symbols across five-symbol windows of a string that cycles the whole alphabet.
    const cycled = ASSET_CODE_ALPHABET + ASSET_CODE_ALPHABET.slice(0, 4);
    for (let start = 0; start < ASSET_CODE_ALPHABET.length; start++) {
      const data = cycled.slice(start, start + 5);
      const code = formatAssetCode(data);
      expect(validateAssetCode(code)).toEqual({ valid: true, normalized: code });
    }
  });
});

// --- Randomized property tests -------------------------------------------
//
// ADR-0002 states the construction guarantees exactly zero undetected
// single-symbol substitutions and exactly zero undetected adjacent
// transpositions, for any code length it supports. These tests assert an
// exact zero over a large, reproducibly-seeded sample rather than a rate.

function mulberry32(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function randomDataSymbols(random: () => number): string {
  let data = "";
  for (let i = 0; i < 5; i++) {
    data += ASSET_CODE_ALPHABET[Math.floor(random() * ASSET_CODE_ALPHABET.length)];
  }
  return data;
}

const SAMPLE_SIZE = 2000;
const random = mulberry32(0x424947); // fixed seed ("BIG" in hex-ish) for reproducible failures

describe("randomized single-symbol substitution detection", () => {
  it(`detects every single-symbol substitution across ${SAMPLE_SIZE} random codes`, () => {
    let checked = 0;
    let undetected = 0;

    for (let sample = 0; sample < SAMPLE_SIZE; sample++) {
      const code = formatAssetCode(randomDataSymbols(random));

      for (let position = 0; position < code.length; position++) {
        for (const replacement of ASSET_CODE_ALPHABET) {
          if (replacement === code[position]) {
            continue;
          }
          const mutated = code.slice(0, position) + replacement + code.slice(position + 1);
          checked++;
          if (validateAssetCode(mutated).valid) {
            undetected++;
          }
        }
      }
    }

    expect(checked).toBeGreaterThan(0);
    expect(undetected).toBe(0);
  });
});

describe("randomized adjacent-transposition detection", () => {
  it(`detects every adjacent transposition of different symbols across ${SAMPLE_SIZE} random codes`, () => {
    let checked = 0;
    let undetected = 0;

    for (let sample = 0; sample < SAMPLE_SIZE; sample++) {
      const code = formatAssetCode(randomDataSymbols(random));

      for (let position = 0; position < code.length - 1; position++) {
        const a = code[position];
        const b = code[position + 1];
        if (a === b) {
          continue; // not a real transposition
        }
        const mutated = code.slice(0, position) + b + a + code.slice(position + 2);
        checked++;
        if (validateAssetCode(mutated).valid) {
          undetected++;
        }
      }
    }

    expect(checked).toBeGreaterThan(0);
    expect(undetected).toBe(0);
  });
});
