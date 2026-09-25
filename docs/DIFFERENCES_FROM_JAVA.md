# Differences from Java

Ironwood deliberately feels like Java, but some familiar-looking constructs
have different semantics. This file records differences that Java programmers
might otherwise miss. See `docs/IRONWOOD_VS_JAVA.md` for the broader, numbered
feature comparison.

## Per-allocation missing-free suppression

`@SuppressUnfreed` is a compiler-owned directive on a reference local declaration,
not a Java annotation. It exempts the initializer's tracked allocation from
Ironwood's missing-free diagnostics even under `--unfreed=error`, follows
existing aliases, and survives class/archive linking. It does not exempt
later allocations assigned to the variable or weaken mandatory safe-`free`
checks. See [the complete contract](MEMORY.md#per-allocation-suppression) and D145.

## Unnamed temporaries are reclaimed at the end of their statement

Java leaves every object to the collector. Ironwood reclaims an unnamed
temporary, an allocation that nothing can observe once the full expression
creating it completes, at the end of that expression when the safe-`free`
proof succeeds (D185). `Sink.use(new Keeper());` destroys the `Keeper` after
`use` returns, and `System.out.println("Hello " + name)` reclaims the
concatenation after `println` returns. Two consequences differ from Java:
the object's destructor runs at that point, so an object whose destructor
releases a native resource must be named if something else still uses that
resource through a copied handle; and a temporary a callee retains, for
example through a container or a field, is left alone exactly as before. Naming
the allocation, `Keeper keeper = new Keeper();`, keeps it until a source
`free`, as long as the name still holds it when the statement completes. Values that move on through `return`, `yield`, `throw`, a switch
selector, or an enhanced-for source, pattern-bound values, `defer` operands,
and allocations inside conditional, switch, or short-circuit expressions are
never reclaimed this way. See [the complete rule](MEMORY.md#unnamed-temporaries).

## Chainable StringBuilder length changes

`StringBuilder.setLength(int)` returns the same builder in Ironwood, while Java
returns void. This deliberate extension (D119) allows
`builder.setLength(0).append("Hi")`. Existing statement-style calls retain their
behavior: truncation, zero filling, capacity growth and exceptions are unchanged.
Returning the receiver creates no allocation or independent ownership.
Existing Ironwood subclasses overriding `void setLength(int)` must update their
return type; chained expressions using the result do not compile in Java.

## Allocation-free CharSequence output

Ironwood adds `PrintStream.print(CharSequence)` and
`println(CharSequence)` overloads that Java does not have (D136). They stream
UTF-16 units through `length()` and `charAt(int)`, combine surrogate pairs, and
write UTF-8 without calling `toString()` or creating a String or array snapshot.
This makes `System.out.println(builder)` allocation-free when `builder` is a
StringBuilder or another allocation-free CharSequence implementation.

The CharSequence contract requires `toString()` to return the same characters
in the same order, so this overload has the same visible text for a conforming
implementation. Java can compile a class that violates that contract, but such
a class is not a valid CharSequence implementation. String remains a
more-specific overload in Ironwood, and a value whose static type is Object
continues to use `println(Object)` and virtual `toString()` rendering.

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
[Instant review](STDLIB_INSTANT_REVIEW.md).

## Explicit deferred cleanup

The D168 Milestone 1 implementation adds `defer` for block-scoped void calls. It
captures receiver and arguments when reached and invokes them on exit in LIFO
order, retaining Ironwood's primary/secondary exception rules. Java resource
headers remain rejected, and close, pool release, and memory reclamation stay
separate operations. `defer free name;` schedules an existing local's proven-safe
reclamation and forbids writes to that binding until cleanup. It does not add
automatic reclamation or ownership privileges. Milestone 2's
[performance stage](DEFER_PERFORMANCE_VERIFICATION.md) is accepted, and a
[runnable example](../examples/deferredcleanup/README.md) demonstrates both
operations and pool release. [SimpleTcpEcho adoption](DEFER_PROJECT_VERIFICATION.md)
and the [final audit](DEFER_FINAL_VERIFICATION.md) complete Milestone 2 locally,
ready for final review and separately directed integration.
`defer` is reserved, including when older format-1 artifacts reload their source;
rename conflicting identifiers and rebuild. See [the language contract](LANGUAGE.md#explicit-deferred-cleanup-milestone-1).

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
may observe a later failure's trace. See the [trace review](STDLIB_STACK_TRACE_REVIEW.md).

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
[`docs/STDLIB.md`](STDLIB.md#core-object-and-text-types).

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
varargs. See the [String review](STDLIB_STRING_REVIEW.md).

## Default text encoding

Ironwood's native text APIs use UTF-8, including the no-argument
`ByteArrayOutputStream.toString()`, `String.getBytes()`, and `String(byte[])`. This matches Java's ordinary UTF-8 default,
but Ironwood has no JVM-style alternate default-charset configuration or charset
overloads yet. BAOS replaces malformed sequences with U+FFFD; `Files.readString`
rejects malformed input, preserving those distinct Java contracts. See D115 and
the [T4 review](STDLIB_BYTE_STREAM_REVIEW.md). String byte construction
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


## Networking compatibility

The [networking migration](NETWORKING_MIGRATION_PLAN.md) design is accepted;
Milestones 1 through 5 implement blocking TCP, literal/scoped addresses, OS DNS,
interface snapshots, best-effort reachability and explicit proxies. Milestone 5
adds the scoped [TLS client](TLS.md); Milestone 6 adds the private
[wget project](../projects/wget/README.md). Its
[verification](NETWORKING_M6_VERIFICATION.md) passed on all three platforms.
`SocketOptions` and its boxed integer-ID/`Object` protocol are omitted. Unbounded
`SocketOption<T>` and generic hooks specialize boolean/int values without
boxing. Value-kind metadata replaces `Class<T>`; supported-option inventories
use read-only `ironwood.ds` views of non-generic descriptors. Reference-only
snapshot and enumeration parameters retain explicit `extends Object` bounds.
`SocketImpl.create()` is TCP-only, and generic implementation hooks declare
`SocketException` so dedicated facade methods preserve checked failures.

Fresh default and proved-fresh factory implementations are owned; injected
implementations and caller delegates are borrowed. Cached stream, address, and
inventory getters borrow their owners. Endpoints, address copies, and exported
byte arrays are independent results. Bulk results require separate element and
shallow-array cleanup, now proved with real DNS results as well as synthetic
fixtures. Retained hostname/scope inputs are copied. Hostname and canonical
getters borrow per-address cached text; host-string access never triggers reverse
lookup. Close releases native resources; free reclaims managed graphs. Networking
exceptions copy their messages.

Implemented family policies select dual-stack capability with IPv4 fallback,
IPv4-first OS resolution and no Ironwood positive, negative or stale DNS cache.
The literal parser fixes ambiguity acceptance to false. Synchronous OS lookup
may outlast connect timeouts. Per-address name memoization is owned by that
address, not a shared resolver cache. These conventions add no system properties.
Milestone 4 implements explicit proxies without ambient property or credential
discovery and introduces `Proxy.socks5(endpoint, username, password)` and
`Proxy.httpConnectBasic(endpoint, username, password)` as named Ironwood
extensions copying endpoint and credential bytes. Explicit typed SOCKS4 selection
and user-ID configuration are separate extensions. V5 never retries V4 or direct
connection; 407 never invokes a callback or retries. Route equality ignores
credentials/version, and sockets copy their proxy configuration. See D151-D164 and the
[source review](STDLIB_N1_SOURCE_REVIEW.md) for the member and ownership matrices.
