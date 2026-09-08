# U1 text and command-line source review

This review records the API, licensing, ownership, failure, and architecture
boundary used for the U1 text-capable command-line slice. The governing
policies are [`LICENSE_MECHANICS`](LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

## Classification and provenance

The U1 standard-library facades, compiler IR and analysis, native runtime
boundaries, tests, example, scripts, and documentation are original or
independently implemented Ironwood work under
`SPDX-License-Identifier: MIT OR Apache-2.0`.

Java SE 21 public names, signatures, specified behavior, and independently
observed results are compatibility targets. No OpenJDK implementation body,
comment, Javadoc, test, algorithm, or distinctive internal structure was
inspected, copied, translated, or adapted. U1 therefore adds no
OpenJDK-derived file, third-party notice, upstream path, or upstream revision.

## Supported surface

U1 completes the immediate tranche A surface from
[`STDLIB_ROADMAP.md`](STDLIB_ROADMAP.md):

- final `String` with copy and checked `char[]` constructors, common UTF-16
  search/prefix/suffix operations, `subSequence`, `concat`, `getChars`, static
  `valueOf` overloads, and `Comparable<String>` ordering;
- static non-boxing helpers on `Boolean`, `Byte`, `Short`, `Character`,
  `Integer`, `Long`, `Float`, and `Double`, plus
  `NumberFormatException`;
- common `Math` constants and `abs`, `min`, `max`, `round`, `floor`, `ceil`,
  `sqrt`, and `pow` overloads;
- immortal `System.err`, standard line separation, one-value environment
  lookup, wall-clock milliseconds, and monotonic nanoseconds;
- `PrintStream.print` and `println` overloads for no value, `String`,
  `CharSequence`, `Object`, `char`, `boolean`, `int`, `long`, `float`, and
  `double`, plus `flush` and `checkError`; and
- the commented `examples/echo` program for arguments, parsing, output,
  diagnostics, and native exit status.

Text indices and ranges use UTF-16 code units. Code-point `indexOf(int)` and
`lastIndexOf(int)` recognize supplementary pairs. Integer conversion supports
radices 2 through 36, signs, overflow checks, and ASCII digits and letters.
Float and double output reuse Ironwood's existing Java-compatible
shortest-round-trip formatter.

## Deliberate boundaries

U1 keeps Java source shape wherever Ironwood can support it without JVM
machinery or hidden ownership:

- wrapper classes remain static primitive utilities; there is no boxing,
  wrapper construction, or wrapper-object identity; the later heap-scratch-free
  `Float.parseFloat` and `Double.parseDouble` Ironwood path is recorded in
  [`STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`](STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md);
- integer parsing currently recognizes ASCII digits and letters rather than
  Java's broader Unicode digit repertoire;
- locale-sensitive text, normalization, regex methods, formatting APIs, and
  runtime interning remain deferred;
- `sqrt` and `pow` use explicit LLVM/platform-math operations; U1 does not
  promise `StrictMath` reproducibility across every platform;
- supported host packages are macOS and Linux, where `lineSeparator()` is the
  immortal LF String;
- environment lookup exposes one fresh-or-null value, not a mutable map; and
- stream replacement, arbitrary `PrintStream` construction, close semantics,
  formatting, `System.in`, JVM properties, security managers, runtime class
  loading, and native-library loading remain absent.

`String.concat` produces a fresh caller-owned String even for an empty suffix.
That uniform identity rule is an Ironwood reclamation contract; Java source
must not depend on the identity of a concatenation result. `String.valueOf(Object)`
and the `Object` output overload preserve Java's `null` and virtual
`toString()` behavior. Their result ownership is therefore the ownership of
the selected `toString()` implementation; the U1 facade does not retain the
object or returned text.

D136 adds Ironwood-specific `CharSequence` output overloads. They treat the
interface's character-access contract as authoritative and do not invoke
`toString()`. String remains the more-specific overload, and Object-typed calls
retain the existing virtual rendering behavior. The implementation is original
Ironwood source and streams UTF-8 through the existing output boundary without
managed or native heap scratch.

D116 adds the floating `String.valueOf` overloads omitted by U1. Each returns
one fresh String using the existing typed concatenation conversion. The matching
builder overloads consume and reclaim that text. The [T5 review](STDLIB_FLOATING_TEXT_REVIEW.md)
records why adding only `append(double)` under D097 silently changed float
output despite the existing float-aware output and concatenation paths.

## Mechanism boundary

| Scope | Classification | U1 treatment |
| --- | --- | --- |
| Java-shaped library declarations | Independent compatible implementation | Small source-level validation, search, comparison, and conversion loops with no JVM dependencies. |
| String storage constructors | Original Ironwood compiler/runtime mechanism | Dedicated typed IR validates before one exact-size allocation and copies UTF-16 storage without retaining the source. |
| String and integer/character factories | Independent facade plus original typed intrinsics | Fresh ordinary results retain compiler-visible allocation identity through wrappers and distribution artifacts. Integer/character formatting writes directly into the result using bounded scalar state. |
| Standard streams | Original Ironwood compiler/runtime mechanism | Two immutable compiler-emitted stream objects carry stdout/stderr channel data in the native image. |
| Environment and clocks | Original Ironwood runtime mechanism | Narrow calls expose one copied environment value and integer timestamps; no process map or JVM service is recreated. |
| Square root and power | Original Ironwood compiler mechanism | Typed math instructions lower to LLVM intrinsics without embedding library semantics in textual LLVM generation. |
| Artifacts and pruning | Original Ironwood compiler mechanism | New typed operations survive specialization and class/archive reconstruction and remain visible to reachability analysis. |
| Tests and example | Original Ironwood work | Independently authored behavior, failure, ownership, Java-differential, optimization, package, and CLI cases. |

## Allocation and failure audit

| Allocation | Ownership | Normal completion | Exceptional completion |
| --- | --- | --- | --- |
| `new String(String)` | Caller-owned | One distinct exact-size String; the source is borrowed and not retained. | Null is rejected before allocation; allocation failure returns no partial result. |
| `new String(char[])` and range form | Caller-owned | One exact-size String; the array is borrowed and remains independently reclaimable. | Null and range validation precede allocation; failure leaves the array unchanged. |
| `concat`, character/integer/long text | Caller-owned | One ordinary String with fresh-result provenance; integer/character conversion uses no temporary array or native heap scratch. | Allocation failure returns no partial result or helper allocation and leaves borrowed inputs unchanged. |
| `StringBuilder.subSequence` | Caller-owned immutable String, exposed as `CharSequence` | One direct range copy, including empty ranges; no intermediate full snapshot and no retained builder storage. | Bounds are checked against the live length before allocation; range or result-allocation failure strands no snapshot. |
| `System.getenv` result | Caller-owned when non-null | Native bytes are copied into one ordinary UTF-16 String; native scratch storage is released before return. | Invalid names allocate nothing; allocation failure releases native scratch storage and returns no result. |
| literals, `System.out`, `System.err`, line separator, boolean text | Immortal | Reused without entering allocation counts and rejected by `free`. | Not applicable. |
| primitive stream output and clocks | None | No Ironwood heap allocation or retained argument. | Native stream error state is observable through `checkError`; clock failure yields zero. |
| `CharSequence` stream output | None | Reads the length once and streams UTF-16 units without a String or array snapshot. | A caller-defined `length` or `charAt` failure may follow partial output; the overload itself creates no cleanup obligation. |

The compiler treats String storage construction as a copying, non-retaining
operation in both escape and symbolic-return summaries. Fresh identity is
preserved through source paths, loose `.ironclass` files, `.ironjar` archives,
closed-world pruning, and separate link. Tests cover normal reclamation and
forced allocation failure for copy construction, character-range
construction, environment values, integer/character formatting, and builder
subsequences. The stage-2 allocation repair extends the existing first-party
native integer digit writer; it introduces no OpenJDK-derived implementation.
Tests assert cumulative and live allocation counts, all UTF-16 character
values, all integer radices, Java 21 differential output, zero/one-allocation
formatting budgets, and builder bounds/failure precedence at `-O0` through `-O3`.

## Verification boundary

U1 completion requires parser/semantic compilation of the new declarations,
typed-IR and LLVM inspection, Java 21 differential results, malformed-input
exceptions, live-allocation baselines, forced allocation failure, native
`-O0` through `-O3`, source/class/archive round trips, pruning, host-package
and IDK smoke paths, the license gate, and the runnable `echo` example.
