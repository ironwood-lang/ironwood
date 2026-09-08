# Instant compatibility and source review

## Scope

D120 adds a focused immutable `ironwood.time.Instant`, suitable for timestamped
logs, persisted epoch values, and ISO-8601 interchange. It does not implement
the entire Java time library or general formatting.

| Group | Supported surface |
| --- | --- |
| Constants | `EPOCH`, `MIN`, `MAX` |
| Factories | `now()`, `ofEpochSecond(long)`, `ofEpochSecond(long, long)`, `ofEpochMilli(long)`, `parse(CharSequence)` |
| Epoch access | `getEpochSecond()`, `getNano()`, `toEpochMilli()` |
| Values and order | `Comparable<Instant>`, `compareTo`, `isBefore`, `isAfter`, `equals`, `hashCode` |
| Text | `toString()` producing canonical UTC ISO-8601 |
| Failures | `DateTimeException` and `DateTimeParseException`, with message/cause constructors and parsed-text/error-index access |

No public Temporal hierarchy, Clock, Duration, calendar date classes,
DateTimeFormatter, arithmetic, custom patterns, timezone database, or Locale is
added. Calls to absent members fail during compilation. Full Formatter remains
deferred independently of this addition.

The behavioral references are the public Java 21 APIs for
[Instant](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html),
[ISO_INSTANT](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html#ISO_INSTANT),
and [DateTimeParseException](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeParseException.html),
plus independently written differential probes. No OpenJDK parser, formatter,
Instant implementation, comments, Javadocs, or tests were translated.

## Behavior

Seconds and nanoseconds cover Java's complete Instant range:
`-1000000000-01-01T00:00:00Z` through
`+1000000000-12-31T23:59:59.999999999Z`. Nanosecond adjustments normalize across
seconds, including negative adjustments. Arithmetic overflow throws
`ArithmeticException`; a representable second outside Instant's range throws
`DateTimeException`. Millisecond conversion handles negative fractions and
both signed-long boundaries without overflowing an intermediate unnecessarily.

`now()` uses `System.currentTimeMillis()`. It has millisecond wall-clock
resolution, not monotonic elapsed-time semantics or a nanosecond-resolution
clock guarantee. `System.nanoTime()` remains the elapsed-time API. Stored,
parsed and rendered timestamps preserve full nanosecond precision.

Parsing follows the ISO_INSTANT grammar, rather than restricting the familiar
method to the examples needed by one application. It accepts signed extended
years, lowercase `t`/`z`, zero through nine digits after a decimal point, and
numeric offsets with mandatory hours/minutes and optional seconds, up to
18 hours. Dates and time fields are validated. Java's `24:00:00` and
`23:59:60` conventions are handled, including fractional/offset edge cases.
Trailing characters, invalid fields, overlong fractions and out-of-range
results throw `DateTimeParseException`. Null input throws
`NullPointerException`. Callback runtime failures are wrapped as parse errors;
callbacks used to produce the diagnostic can themselves throw.

Output is always UTC with `Z`, padded date/time fields and zero, three, six or
nine fractional digits. A parsed numeric offset changes the instant, not the
output zone. For example, parsing `2000-02-29T12:34:56.123456789+01:30` renders
`2000-02-29T11:04:56.123456789Z`. There is no locale or environment dependence.
Exception message wording and internal cause chains are implementation details;
the public failure classes, explicit constructor causes, error indices and
parsed-text snapshots are tested.

## Allocation and ownership

Every successful factory, including `now` and `parse`, returns one fresh owned
Instant. Java may reuse value instances; Ironwood instead follows its existing
fresh-result ownership convention. Use `equals` for values. The three constants
are shared static instances initialized once, separately from per-call counts.

Epoch access, comparison, hashing, parsing and calendar arithmetic use primitive
state without heap scratch. Each `toString` branch is one typed concatenation
that fills one final String, with no builder, array, or component String
allocation. The compiler records this exact final-class method as a fresh text
conversion, so printing, concatenation and Object append can release the
temporary rendering using the existing descriptor protocol.

Successful parsing retains no source reference. On failure the parse exception
owns a copied char array. A custom CharSequence's `toString()` may create a
temporary; the existing conditional rendering cleanup releases only a proven
fresh, unpublished result, including if the subsequent array allocation fails.
Borrowed, cached and published renderings keep their existing ownership.
Exception destruction and failed-construction rollback reclaim the owned array.

`DateTimeParseException.getParsedString()` returns a fresh String snapshot, or
null if a custom source rendered null. Unlike Java's retained immutable String,
this snapshot can outlive the exception and be independently freed. Inherited
message and cause references retain the existing Throwable borrowing rules.
Caught exceptions do not automatically become owned allocations that can be
freed; this addition does not change the language's exception ownership rules.

A call-site borrowing refinement for `Instant.parse` checks all possible
`length`, `charAt`, and `toString` implementations before permitting source
reclamation. Actual publication by any callback still prevents `free`.
Conservative analysis through additional user wrappers may still reject a
reclamation that is not proved safe. No runtime ownership check is introduced.

## Implementation category and licensing

The Instant facade, exception types, compiler changes, and regression fixtures
are independent or original Ironwood under `MIT OR Apache-2.0`. Their design
uses primitive parsing state and Ironwood's existing allocation mechanisms.
Translating the complete Java Formatter/Temporal framework would add unrelated
APIs and GC-oriented helper lifetimes to this focused timestamp feature.

The package-private `InstantCalendar.iron` instead adapts the two mature
Gregorian epoch-day conversion algorithms from OpenJDK `LocalDate.java`:

- Repository: `openjdk/jdk21u`.
- Immutable revision: `060c4f7589e7f13febd402f4dac3320f4c032b08` (21.0.1+12).
- [Exact upstream file](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/time/LocalDate.java).
- The file expressly carries the Classpath Exception. The complete Oracle and
  original JSR-310 notices are retained, along with the Ironwood modification
  and exception-extension notice.
- The helper uses `GPL-2.0-only WITH Classpath-exception-2.0`. It returns packed
  primitive date fields, avoiding LocalDate allocations, and handles Instant's
  extra boundary years under validation by the caller.

The provenance ledger and third-party notices include this helper. Existing
archive mechanics embed its complete source in the `.ironclass`; standard
library archives carry the GPL, Classpath Exception and provenance notices.
Host/IDK packaging already copies standard-library source. No packaging
mechanism or native ABI changes are needed.

## Verification and lessons

Focused Java 21 comparisons cover the public epoch factories, overflow and
range failures, negative milliseconds, equality/hash/order, nulls, callback
failures, canonical text, offset and extended-year parsing, malformed-input
indices, and diagnostic snapshots. The generated corpus mutates every position
of a timestamp and tests every truncated prefix. Round trips cover all 146,097
days of a Gregorian cycle and 2,000 samples across the full Instant range.

Separate native tests check exact result allocations, mutable-input reclamation,
snapshot independence, ordinary Object consumers, omitted-member diagnostics,
callback-publication rejection, and pruning of unused time code. Allocation
limits fail every allocation in five operations, including exception rendering
and subsequent array allocation. These are focused macOS ARM64 `-O3` checks,
not a full-suite or multi-platform release validation.

The lesson from T1 applies directly: fewer public methods are a useful scope
boundary; accepting only a convenient fraction of a supported method's input
grammar is not. The implementation therefore keeps a small API while testing
the full value range and non-obvious Java input forms. Choosing original code
for the facade and a derived calendar helper serves the same behavioral target.
Existing agent and porting rules already require that distinction and review;
no additional AGENTS.md rule is needed.
