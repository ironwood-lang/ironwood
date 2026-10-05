<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M3.2 numeric, text and SHA-256 helpers

Status: passes ([D267](../../DECISIONS.md#d267---provide-the-m32-numeric-text-and-sha-256-helpers)).
These compiler-private helpers are the S3 semantic prerequisites of the M0
inventory: bounded numeric folding, StringPool/UTF-16 and name helpers, and
exact SHA-256 before ByteView analysis. `TypeResolver`'s name splitting uses
D266's `Splits`. All sources are original and use the default license; the
SHA-256 constants are the standard's definitions, recomputed from the roots of
the first primes, and no reference implementation was consulted.

## Consumer mapping

| Java demand | Native treatment | First consumers (S3) |
| --- | --- | --- |
| `new BigInteger(digits, radix)`, `bitLength`, `testBit`, range checks | `IntegerLiterals.isLong`, `status`, `value`, `error` | IntegerLiteralDecoder (FunctionAnalyzer, StaticConstantEvaluator) |
| `BigInteger` add/subtract/multiply/divide/remainder/and/or/xor/shifts/negate/not, `mod` wrapping, range comparisons | `IntegralConstants.wrap`, `defines`, `binary`, `negate`, `complement`, `compare`, `fitsNarrowing` | FunctionAnalyzer folds and switch constants, StaticConstantEvaluator |
| `Character.toCodePoint`, StringPool UTF-8 length | `Texts.codePoint`, `Texts.utf8Length` | StringPool |
| `replaceFirst` with a literal pattern, `String.join` over lists | `Texts.replaceFirst`, `Texts.join` | FunctionAnalyzer.joinNotes, switch arm names, Bridge summaries |
| `MessageDigest.getInstance("SHA-256")`, `digest`, `HexFormat.formatHex` | `Sha256.updateUtf8`/`update`, `hexDigest` | ByteViewIntrinsic.trusted, BridgeGeneration.bytesDigest |
| `Double`/`Float` `toString`, `parseDouble`, `parseFloat` | existing Ironwood conversions, verified equal | constant folding text |

## Results

| Check | Result |
| --- | --- |
| Literal decoding | 45 fixed spellings (malformed prefixes and suffixes, non-ASCII and fullwidth digits, underscores everywhere, 2^31, 2^63, 2^64 boundaries, 100-digit magnitudes), every boundary magnitude in each radix with and without leading zeros and underscores, and 20,000 seeded spellings, each plain and negated, equal J0's IntegerLiteralDecoder called by reflection: type, value or diagnostic. |
| Folding | Wrapping of 36 boundary values to all five widths, eleven folded operators and one unfolded comparison over all 1,296 operand pairs in I32 and I64, negation, complement and narrowing fits equal J0's `wrapIntegral`, `evaluateIntegralBinary` and `constantNarrowingFits`. |
| SHA-256 | The standard vectors (including one million `a`), every length 0-300 across the padding boundaries, every byte value, twelve texts with surrogate pairs and unpaired surrogates, and 64 MiB streamed through one reused buffer equal `MessageDigest`; every two-way split of a 200-byte input, offsets, reset and reuse agree; range violations are rejected. A digest costs four allocations, hashing none, a fresh result one array. |
| ByteView authority | J0 trusts the bundled declaration as read from source, from its `.ironclass` and from the library archive, and distrusts four changed copies (a comment edit, a renamed method, CRLF line endings, a dropped final newline). The native digest of each content equals J0's, with the same verdicts, from classes and archive links. |
| Text helpers | UTF-8 lengths of all 65,536 single units, all 144 boundary pairs and 2,000 seeded strings equal J0's `StringPool.utf8Length`; all 1,048,576 surrogate pairs equal `Character.toCodePoint`; the joinNotes replacement and list joins equal Java. |
| Floating text | `toString` and parse round trips of special values, boundaries and 200,000 seeded patterns of each width equal Java 21 (399,136 lines). |
| Failure | Every allocation limit up to the first succeeding one unwinds to the live-allocation baseline. |
| Ownership | All modes: inputs are borrowed only during a call; digests, diagnostics and joined texts retire; use after free and double free are rejected. |

All fixtures compile and link with `--unfreed=warn` and no diagnostics, from
classes and archive at `-O3`.

## Boundaries

- Signs in literal spellings are malformed natively; the lexer never puts one
  in an INTEGER token (D267 narrowing).
- `Texts.replaceFirst` is a literal replacement; its one call site uses a
  pattern and replacement without regex metacharacters.
- Floating-to-integral constant casts and the explicit constant
  representation are S2/S3 consumer work; the native casts' Java semantics
  must be checked there.
- J0 cannot compile the accumulated port (D267); see the M3.2 handoff.
