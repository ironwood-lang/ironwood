# Floating-point parsing source review

This review records the API, provenance, allocation, and native boundary for
`Float.parseFloat(String)` and `Double.parseDouble(String)`. The governing
policies are [`LICENSE_MECHANICS`](LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

## Classification and provenance

The facade, grammar validator, typed IR, runtime normalization, tests, and
documentation are original or independently implemented Ironwood work under
`SPDX-License-Identifier: MIT OR Apache-2.0`.

Java SE 21 public signatures and observed behavior are the compatibility
target. No OpenJDK implementation body, comment, Javadoc, test, algorithm, or
distinctive internal structure was inspected, copied, translated, or adapted.
The native conversion uses the supported host's C11 `strtof` and `strtod` after
Ironwood has independently validated and normalized the input.

## Supported behavior

Both methods accept Java-shaped decimal and hexadecimal forms, optional signs,
decimal or binary exponents, `f`/`F`/`d`/`D` suffixes, exact `NaN` and
`Infinity` spellings, and leading/trailing characters no greater than U+0020.
They preserve signed zero and produce the expected IEEE infinity or zero on
overflow or underflow. Invalid non-null input raises `NumberFormatException`;
null raises `NullPointerException`.

The parser does not add boxing, locale-sensitive decimal syntax, numeric
separators, Unicode digits, alternate infinity/NaN spellings, or public static
floating formatting methods.

## Mechanism and allocation boundary

Package-private `FloatingPointParser` validates the complete grammar directly
over borrowed UTF-16 code units without allocating. Private validated methods
on `Float` and `Double` lower to `IrFloatingParseInstruction`; specialization,
pruning, class/archive reconstruction, and LLVM emission preserve that typed
operation before it reaches the isolated runtime ABI.

The runtime normalizes directly from UTF-16 into fixed stack storage. It keeps
1,200 significant digits plus a sticky digit, a conservative bound beyond the
decimal precision needed to distinguish binary64 rounding boundaries, and
saturates irrelevant oversized exponents. Successful parsing creates no
Ironwood object, and Ironwood's parser and runtime bridge issue no `malloc` or
`realloc` call; its String argument is borrowed and retained nowhere. Malformed
input may allocate the ordinary exception object on the failure path.

## Verification

The compiler suite inspects typed IR and LLVM and differentially compares the
native result with Java 21 across `-O0` through `-O3`. Cases cover decimal and
hexadecimal syntax, suffixes, accepted and rejected whitespace, signed zero,
NaN, infinities, overflow, underflow, exact halfway values, adversarially long
significands, invalid Unicode and punctuation, exception categories, and an
unchanged successful-path allocation count.
