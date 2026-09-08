# Differences from Java

Ironwood deliberately feels like Java, but some familiar-looking constructs
have different semantics. This file records differences that Java programmers
might otherwise miss. See `docs/IRONWOOD_VS_JAVA.md` for the broader, numbered
feature comparison.

## Chainable StringBuilder length changes

`StringBuilder.setLength(int)` returns the same builder in Ironwood, while Java
returns void. This deliberate extension (D119) allows
`builder.setLength(0).append("Hi")`. Existing statement-style calls retain their
behavior: truncation, zero filling, capacity growth and exceptions are unchanged.
Returning the receiver creates no allocation or independent ownership.
Existing Ironwood subclasses overriding `void setLength(int)` must update their
return type; chained expressions using the result do not compile in Java.

## Timestamp values and ownership

`ironwood.time.Instant` provides epoch conversion, comparison and ISO-8601
parsing/output across Java's full Instant range (D120). Every factory returns
a fresh caller-owned value, including epoch zero, while Java may reuse value
instances. The constants remain shared static objects; compare values with
`equals`. Each `toString()` returns one fresh String. Parse exceptions copy
their input, and `getParsedString()` returns an independently owned snapshot
instead of Java's retained String. Exception messages are implementation-specific.

`now()` uses the existing millisecond wall clock. Stored/parsed values retain
nanosecond precision; wall-clock resolution is not a nanosecond guarantee.
General date patterns, arithmetic, Temporal interfaces, Clock and named
timezones remain absent and cannot be called. See the
[Instant review](docs/STDLIB_INSTANT_REVIEW.md).

## Exception traces and emergency printing

Ordinary Throwable construction captures a source trace, rethrows preserve it,
and `fillInStackTrace()` refreshes it and returns the same receiver (D121).
`printStackTrace()` and its PrintStream overload follow Java's description,
cause and shared-frame conventions using the existing stdout/stderr streams.
Ironwood's established cleanup failures use `Secondary:` rather than Java's
suppression policy. `getStackTrace()` returns a fresh caller-owned array whose
immutable elements are compiler-emitted process-lifetime objects. Mutable frame
replacement remains absent. PrintWriter is available for ordinary text output,
but there is no Throwable PrintWriter overload.

Private trace storage follows the Throwable's explicit lifetime. Thrown or
published exceptions retain existing restrictions on `free`. Capture allocation
failure produces `<trace unavailable>`. If public printing cannot allocate, it
falls back to the root's stored description and frames, omitting graph traversal
and virtual callbacks; partial output may precede that fallback. Automatic
OutOfMemoryError still reuses bounded occurrence-specific state, so an old alias
may observe a later failure's trace. See the [trace review](docs/STDLIB_STACK_TRACE_REVIEW.md).

`System.getProperty` exposes a documented native subset rather than JVM
properties. Values are fresh when present; unknown and JVM-only keys return
null. File/path/line separators, UTF-8 encoding, host OS/user/directory/temp,
and fixed language/country keys are supported.

Heap ByteBuffer slices and `array()` share their backing storage. Once those
aliases are exposed, slices carry compiler-tracked lifetime loans. Calling
`array()` publishes the backing array, so an owning buffer can no longer be
compiler-proven safe to reclaim. Wrapped arrays stay caller-owned. This avoids
a runtime alias registry while preserving safe reclamation guarantees.

## Numeric formatting

Java `String.format` and `Formatter` are not implemented. Calls to
`String.format` fail at compilation, including calls with literal patterns.
D113 removes the earlier misleading overloads that accepted only an entire
`%d` or `%f` field and failed on other Java-valid patterns at runtime.

For deliberately bounded numeric displays, Ironwood provides
`String.formatDecimal(long value, int width)` and
`String.formatFixed(double value, int width, int precision)`. These return
caller-owned Strings with space padding and explicit numeric parameters. They
are Ironwood helpers, with fixed-point range and rounding limits described in
[`docs/STDLIB.md`](docs/STDLIB.md#core-object-and-text-types).

## Fixed text conventions

Ironwood provides `String.toUpperCase()` and `toLowerCase()` with fixed `en_US`
Unicode behavior, independent of environment settings. There is no
`ironwood.util.Locale`, configurable default locale, or locale-taking overload.
For example, `"i".toUpperCase()` is always `"I"`, while Java running with a
Turkish default locale produces `"İ"`. `equalsIgnoreCase()` remains
locale-independent as in Java. D117 records this deliberate product scope.

String transformations return fresh caller-owned results even for unchanged or
empty text; Java may reuse a String. This extends the existing substring/copy
ownership convention. StringBuilder `substring` and `subSequence` likewise
return one fresh String even for an empty range, independent of mutable builder
storage. `String.join` accepts explicit arrays or zero through
three individual elements. Iterable join is absent, and Ironwood has no
varargs. See the [String review](docs/STDLIB_STRING_REVIEW.md).

## Default text encoding

Ironwood's native text APIs use UTF-8, including the no-argument
`ByteArrayOutputStream.toString()`, `String.getBytes()`, and `String(byte[])`. This matches Java's ordinary UTF-8 default,
but Ironwood has no JVM-style alternate default-charset configuration or charset
overloads yet. BAOS replaces malformed sequences with U+FFFD; `Files.readString`
rejects malformed input, preserving those distinct Java contracts. See D115 and
the [T4 review](docs/STDLIB_BYTE_STREAM_REVIEW.md). String byte construction
uses the same replacement decoder; byte export encodes unmatched surrogates as
`?`, matching Java's UTF-8 encoder.

## Generic type parameter bounds

Java treats an omitted type parameter bound as an implicit `extends Object`.
Ironwood does not. The following declarations have different meanings in
Ironwood:

```java
interface Values<E> { }
interface ObjectValues<E extends Object> { }
```

- `<E>` accepts reference types and Ironwood's eight primitive types through
  closed-world native specialization.
- `<E extends Object>` accepts reference types only.

Therefore, removing an explicit `extends Object` changes an Ironwood API rather
than merely changing its formatting. Reference-only standard-library APIs retain
the explicit bound, including the object pools and every generic key, element,
and value parameter in `ironwood.ds`. Primitive collections use their dedicated
`Int` and `Long` forms instead of specializing the generic reference collections.
