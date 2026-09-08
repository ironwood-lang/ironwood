# String formatting compatibility review

## T2: zero-padding flag consumed as width

**Status:** The silent wrong-output path was removed by D113 with T1.
Java-compatible zero padding through `String.format` remains unimplemented.
Compilation rejection prevents misleading output but does not satisfy the
formatting compatibility goal. This review does not schedule that work.

### Evidence and cause

D097 introduced `String.format(String, long)` for a `%6d` application display
field. At commit `ab2fda954cb5ad4096c3fdecde7e2d566d1d457b`, the last source before
D113, `parseFormatWidth` consumed every digit after `%`. For `%05d`, it consumed
`0` and `5` as the number five. The renderer then unconditionally filled the
padding with spaces. No representation of flags survived parsing because the
parser did not distinguish flags from width in the first place.

The [Java SE 21 Formatter specification](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Formatter.html)
defines flags and width separately. For decimal integers, the `0` flag requests
zero padding after any sign; width specifies the minimum total field length.
Thus `%05d` and `%5d` are different requests.

The 2026-09-06 review recompiled that historical `String.iron` with the current
compiler and ran a native `-O3` executable. `String.format("%05d", 42)` produced
exactly three spaces followed by `42`, confirming the report. Compiling the
same caller with the current library failed at `format` with
`type 'ironwood.lang.String' has no method 'format'`.

This was both a grammar bug and a compatibility-review failure. D097 described
flags as unsupported, yet the parser accepted a flag as another grammar element
and silently assigned different semantics. An intentionally reduced parser
still needs an exact acceptance boundary. The two application patterns and
their output comparison could not reveal this distinction.

### Assessment of the implementation choice

Independent integer rendering is small enough to be a reasonable implementation
choice. T2 alone does not justify copying all of OpenJDK Formatter. Correctly
distinguishing a flag from width, or rejecting an unsupported flag, does not
require a mature numeric algorithm, a JVM, or a garbage collector.

The wrong approach was treating a general Java format string as a convenient
application-specific shorthand without reviewing its grammar. Full formatting
has a much larger contract and should receive a separate implementation and
provenance review. Neither derivation nor independent implementation excuses
changing the meaning of a Java-valid call.

### Pending zero-padding acceptance cases

These are future compatibility requirements, not passing Ironwood formatting
tests. Results use `Locale.ROOT` to isolate padding from locale behavior. They
were checked with the local Java 23.0.1 runtime against the Java SE 21 contract.
Quotes preserve spaces in the displayed results.

| Format | Argument | Required result or exception |
| --- | --- | --- |
| `%05d` | `42` | `"00042"` |
| `%05d` | `-42` | `"-0042"` |
| `%05d` | `0` | `"00000"` |
| `%05d` | `123456` | `"123456"` |
| `%5d` | `42` | `"   42"` |
| `%022d` | `Long.MIN_VALUE` | `"-009223372036854775808"` |
| `%+05d` | `42` | `"+0042"` |
| `%0d` | `42` | `MissingFormatWidthException` |
| `%00d` | `42` | `DuplicateFormatFlagsException` |
| `%-05d` | `42` | `IllegalFormatFlagsException` |

Future executable coverage must compare equivalent int and long calls, literal
and dynamic patterns, sign placement, minimum width without truncation, and
invalid flag combinations with Java. Formatting also needs its own allocation
and reclamation checks. These cases are a minimum for T2, not an exhaustive
Formatter specification.

### Current protection and follow-up

The existing compiler regression named
`String.format is rejected at the caller during compilation` already includes
`String.format("%05d", 42)`. No production parser remains to patch, and this
review does not reintroduce one or add a second numeric helper API.
`String.formatDecimal(value, width)` deliberately produces space-padded fields;
it is not a replacement for Java zero padding.

The [porting review](OPENJDK_PORTING.md#behavioral-contract-review) now explicitly
requires tests at grammar boundaries. The existing `AGENTS.md` rule requires
that review for every standard-library API addition or semantic change.
Keep T2's Java-compatibility requirement open until equivalent formatting
produces the required results; API removal alone is not completion.
