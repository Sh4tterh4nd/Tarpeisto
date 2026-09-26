# ADR-0002: Public asset code checksum construction

Status: Accepted

Date: 2026-09-25

## Context

[The specification](../SPECIFICATION.md) requires a six-character public asset code whose first five
characters are random Crockford Base32 data symbols and whose sixth character is an
"order-sensitive checksum encoded with the same Crockford Base32 alphabet". The checksum is
validated before any database lookup so that a mistyped code reports a transcription error rather
than `not found`.

[The implementation plan](../IMPLEMENTATION_PLAN.md) requires the public-code library to be tested
against every alphabet symbol, ambiguous-character normalization, single-character substitutions,
adjacent transpositions, invalid lengths and symbols, and large randomized test sets. The plan also
requires that the schema accept longer future codes with the final character remaining the
checksum.

The exact construction was left open and is decided here.

## Decision

### Alphabet

The data alphabet is the Crockford Base32 excluded-ambiguity alphabet:

```text
0123456789ABCDEFGHJKMNPQRSTVWXYZ
```

The symbol at index `n` (0-based) represents the value `n`. The same alphabet encodes the check
symbol, so a public code is one uninterrupted string over a single 32-symbol alphabet.

### Normalization

Normalization runs before validation, for both scanned and manually entered input:

1. Remove all whitespace and `-` characters.
2. Convert to upper case.
3. Map `O` to `0`.
4. Map `I` and `L` to `1`.

Any remaining character outside the alphabet makes the input invalid. `U` is not accepted; Crockford
reserves it and it is not part of the data alphabet.

### Arithmetic

Symbol values are elements of the finite field `GF(2^5)` built from the primitive polynomial

```text
x^5 + x^2 + 1        (0b100101)
```

A symbol value is the bit pattern of its polynomial coefficients. Addition is `XOR`. Multiplication
is carry-less polynomial multiplication reduced by the primitive polynomial.

`g = 2` (the element `x`) is a generator: its powers `g^0 .. g^30` are the 31 distinct nonzero field
elements. The first six powers are:

| `g^j` | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---:|---:|---:|---:|---:|---:|
| value | 1 | 2 | 4 | 8 | 16 | 5 |

### Check equation

Position weights are assigned **from the right**. In a complete code of length `L`, the symbol at
distance `j` from the right end has weight `g^j`. The check symbol is the rightmost symbol and
therefore has weight `g^0 = 1`.

A complete code `x[0] .. x[L-1]` is valid when

```text
XOR over i of ( g^(L-1-i) * value(x[i]) ) == 0
```

Equivalently, the check symbol for data symbols `d[0] .. d[n-1]` is

```text
check = XOR over i of ( g^(n-i) * value(d[i]) )
```

Weighting from the right means that lengthening the data portion in a future release only adds
higher powers on the left. The weight of every position in an existing code is unchanged, so
existing six-character codes stay valid under the longer-code rule. The generator has order 31, so
the construction supports codes up to 31 characters in total, which is far beyond any planned
format.

For the initial six-character format the weights, left to right, are:

```text
5   16   8   4   2   | 1
d0  d1   d2  d3  d4  | check
```

### Error-detection guarantees

Both properties follow from the field arithmetic and hold for every code length the construction
supports.

- **Every single-symbol error is detected.** Changing the symbol at distance `j` from the right by
  a nonzero field difference `e` changes the sum by `g^j * e`. A field has no zero divisors and
  `g^j != 0`, so the sum becomes nonzero.
- **Every adjacent transposition of two different symbols is detected.** Swapping neighbouring
  positions with weights `w_a` and `w_b` and values `a != b` changes the sum by
  `(w_a + w_b) * (a + b)`. In characteristic two `a + b != 0` because `a != b`, and
  `w_a + w_b != 0` because distinct powers of `g` are distinct field elements. The sum becomes
  nonzero.

This includes a transposition of the last data symbol with the check symbol, because `g^1 = 2` and
`g^0 = 1` are distinct.

Modular-sum alternatives were evaluated and rejected. A weighted sum modulo 32 cannot detect every
adjacent transposition: detecting a swapped pair whose values differ by 16 requires an odd weight
difference, while detecting every single-symbol substitution requires all weights to be odd, and two
odd weights always differ by an even number. A prime modulus such as 31 leaves the symbol pair
`Z`/`0` indistinguishable. A Damm quasigroup of order 32 would also satisfy both properties but
requires a searched 1024-entry table in place of six lines of field arithmetic.

### Generation and uniqueness

- The five data symbols come from a cryptographically secure random source, drawn so that all 32
  symbols are equally likely.
- The check symbol is computed from the data symbols.
- Uniqueness is enforced by a database unique constraint on `(organization_id, public_code)`.
- A unique-constraint violation causes regeneration and retry with a bounded attempt count.
- Codes are immutable and are never reused, including after an asset is lost, destroyed, retired, or
  archived.

### Validation order

1. Normalize.
2. Reject a length or symbol that the format does not allow.
3. Verify the check equation.
4. Only then look the code up in the database.

A checksum failure reports a transcription error. A checksum success with no matching record reports
an unknown code. The two outcomes are distinguishable to the user because they need different
corrective action.

The same normalization and validation apply to camera-scanned payloads and manually typed input.
The frontend performs the identical check for immediate feedback; the server repeats it and remains
authoritative.

## Test vectors

Data symbols on the left, complete six-character public code on the right.

| Data | Public code |
|---|---|
| `00000` | `000000` |
| `00001` | `000012` |
| `0000Z` | `0000ZV` |
| `12345` | `123452` |
| `7K3MX` | `7K3MXY` |
| `91TRQ` | `91TRQJ` |
| `A72KQ` | `A72KQ5` |
| `M39TX` | `M39TX1` |
| `HJKMN` | `HJKMNE` |
| `PQRST` | `PQRSTJ` |
| `VWXYZ` | `VWXYZM` |
| `ZZZZZ` | `ZZZZZ1` |

Normalization vectors, all of which must validate as `7K3MXY`:

| Input | Reason |
|---|---|
| `7k3mxy` | lower case |
| `7K3-MXY` | hyphen |
| ` 7K 3M XY ` | whitespace |
| `7K3MXY` | canonical |

Ambiguous-symbol vectors, all of which must normalize to a valid code:

| Input | Normalizes to |
|---|---|
| `O0OO0O` | `000000` |
| `I23452` | `123452` |
| `L23452` | `123452` |
| `l23452` | `123452` |

Rejected inputs:

| Input | Reason |
|---|---|
| `7K3MX` | too short |
| `7K3MXYZ` | not an accepted length for the current format |
| `7K3MXU` | `U` is not in the alphabet |
| `7K3MX!` | symbol outside the alphabet |
| `7K3MXZ` | single-symbol substitution of a valid code |
| `K73MXY` | adjacent transposition of a valid code |

The specification's illustrative codes (`7K3MXP`, `91TRQW`, `A72KQF`, `M39TXC`) are prose examples
of the code *shape*. They predate this decision and are not valid checksummed codes; the table above
is authoritative for tests.

## Consequences

- The public-code library is small, deterministic, and provable rather than table-driven.
- Both error classes the plan calls out are detected with certainty, so randomized property tests
  assert an exact zero rather than a rate.
- The construction is shared by the backend (authoritative) and the frontend (immediate feedback),
  so it must be implemented twice and tested against the same vectors in both languages.
- A future longer code format keeps all existing codes valid and only extends the weight sequence.
- Changing the alphabet, the polynomial, the generator, or the weight direction would invalidate
  every printed label and requires a new ADR.

## Related decisions

- [ADR-0001: Application technology stack](ADR-0001-application-stack.md)
