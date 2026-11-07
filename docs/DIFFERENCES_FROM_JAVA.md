# Differences from Java

## GC5 memory-model supersession

The migration branch now uses [the GC5 managed memory contract](MEMORY.md).
Canonical Memory.collect() is the only reclamation point. There is no source
free, destructor, implicit temporary destruction or managed constructor rollback.
General defer, initialization, native resource and pool protocols remain.
Historical proof discussions and examples mentioning those removed forms are
superseded by the completed GC7 consumer migration, not alternate supported semantics.
[GC6 managed bridges](GC6_IMPLEMENTATION.md) qualify macOS arm64 and Linux
x86-64 with explicit RootHandle/ListenerRegistration lifetimes, exact suspended
roots and isolated native images. [GC9](GC9_LINUX_X86_64_DISTRIBUTION.md)
qualifies all three official targets and their distributions with direct
matching-hardware evidence. arm64e remains unsupported.

Ironwood deliberately feels like Java, but some familiar-looking constructs
have different semantics. This file records differences that Java programmers
might otherwise miss. See `docs/IRONWOOD_VS_JAVA.md` for the broader, numbered
feature comparison.

## Explicit collection

Java schedules collection automatically. Ironwood collects only when canonical
Memory.collect() executes and completes marking and sweeping before returning.
Unnamed temporaries and failed constructions use the same managed lifetime as
ordinary objects. No source destructor or temporary cleanup callback runs.
Removed reclamation syntax and options diagnose migration.

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
a fresh managed value, including epoch zero, while Java may reuse value
instances. The constants remain shared static objects; compare values with
`equals`. Each `toString()` returns one fresh String. Parse exceptions copy
their input, and `getParsedString()` returns an independent managed snapshot
instead of Java's retained String. Exception messages are implementation-specific.

`now()` uses the existing millisecond wall clock. Stored/parsed values retain
nanosecond precision; wall-clock resolution is not a nanosecond guarantee.
General date patterns, arithmetic, Temporal interfaces, Clock and named
timezones remain absent and cannot be called. See the
[Instant review](STDLIB_INSTANT_REVIEW.md).

## Explicit deferred cleanup

Block-scoped defer captures an ordinary void method call immediately and runs
it in LIFO order on every exit. Captured identity survives reassignment and
collecting cleanup. D051 primary/secondary exception ordering is unchanged.
Use defer for explicit close and pool release. See [MEMORY.md](MEMORY.md).

## Exception traces and emergency printing

Ordinary Throwable construction captures a source trace, rethrows preserve it,
and `fillInStackTrace()` refreshes it and returns the same receiver (D121).
`printStackTrace()` and its PrintStream overload follow Java's description,
cause and shared-frame conventions using the existing stdout/stderr streams.
Ironwood's established cleanup failures use `Secondary:` rather than Java's
suppression policy. `getStackTrace()` returns a fresh managed array whose
immutable elements are compiler-emitted process-lifetime objects. Mutable frame
replacement remains absent. PrintWriter is available for ordinary text output,
but there is no Throwable PrintWriter overload.

Private trace storage follows the Throwable's explicit lifetime. Thrown or
published exceptions follow ordinary managed reachability. Capture allocation
failure produces `<trace unavailable>`. If public printing cannot allocate, it
falls back to the root's stored description and frames, omitting graph traversal
and virtual callbacks; partial output may precede that fallback. Automatic
OutOfMemoryError still reuses bounded occurrence-specific state, so an old alias
may observe a later failure's trace. See the [trace review](STDLIB_STACK_TRACE_REVIEW.md).

`System.getProperty` exposes a documented native subset rather than JVM
properties. Values are fresh when present; unknown and JVM-only keys return
null. File/path/line separators, UTF-8 encoding, host OS/user/directory/temp,
and fixed language/country keys are supported.

Heap ByteBuffer slices and `array()` share their managed backing storage.
Each reachable alias keeps its referenced graph alive across explicit collection,
including after another buffer alias becomes unreachable. There is no exclusive
owner or independent-free proof and no runtime alias registry. Resource-backed
foreign ByteViews retain their separate synchronous native lifetime contract.

## Numeric formatting

Java `String.format` and `Formatter` are not implemented. Calls to
`String.format` fail at compilation, including calls with literal patterns.
D113 removes the earlier misleading overloads that accepted only an entire
`%d` or `%f` field and failed on other Java-valid patterns at runtime.

For deliberately bounded numeric displays, Ironwood provides
`String.formatDecimal(long value, int width)` and
`String.formatFixed(double value, int width, int precision)`. These return
managed Strings with space padding and explicit numeric parameters. They
are Ironwood helpers, with fixed-point range and rounding limits described in
[`docs/STDLIB.md`](STDLIB.md#core-object-and-text-types).

## Fixed text conventions

Ironwood provides `String.toUpperCase()` and `toLowerCase()` with fixed `en_US`
Unicode behavior, independent of environment settings. There is no
`ironwood.util.Locale`, configurable default locale, or locale-taking overload.
For example, `"i".toUpperCase()` is always `"I"`, while Java running with a
Turkish default locale produces `"İ"`. `equalsIgnoreCase()` remains
locale-independent as in Java. D117 records this deliberate product scope.

String transformations return fresh managed results even for unchanged or
empty text; Java may reuse a String. This extends the existing substring/copy
fresh-result convention. StringBuilder `substring` and `subSequence` likewise
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

## Temporary files and directory traversal

`java.io.tmpdir`, and so the default directory of `Files.createTempFile` and
`createTempDirectory`, is a nonempty `TMPDIR` or else `/tmp` on every supported
host. Java takes the per-user Darwin temporary directory on macOS and `/tmp` on
Linux, whatever `TMPDIR` holds. Generated names have Java's shape (prefix,
unsigned decimal value, suffix), but a creation failure names the directory
rather than the generated path in its exception. Ironwood's `SimpleFileVisitor`
declares `throws IOException` only on `visitFileFailed` and
`postVisitDirectory`; a subclass whose `visitFile` or `preVisitDirectory`
throws fails to compile and implements `FileVisitor` directly instead. See D270.

The publication moves have no Java counterpart by name. `moveAtomicReplacing`
behaves as Java's `ATOMIC_MOVE` on POSIX hosts. `moveReplacing` copies only a regular file across file systems, where Java's
`REPLACE_EXISTING` move also copies links and empty directories and reports a
non-empty one as DirectoryNotEmptyException. `moveAtomicNoReplace` never
copies and treats a target that is already the same file as existing, where
Java's default move copies across file systems and treats the same file as a
no-op. See D271.

## Checksums

`ironwood.util.zip` provides only `CRC32`. Its constructor, `reset`, the byte,
whole-array and range updates and `getValue` behave as Java 21's, including
widened arguments, range failures and subclass dispatch of the whole-array
update. The `Checksum` interface and `update(ByteBuffer)` are absent, so code
naming them fails to compile; a null whole-array argument throws
NullPointerException without the JVM's helpful-message text. Java's ZIP, GZIP,
Deflater and Inflater APIs are absent. See D274.

## Process launching

Java's `ProcessBuilder` and `Process` are absent. `ProcessRunner.runToFile`
covers synchronous launches only: the executable must be an absolute path (no
PATH search), the environment is inherited without a map, standard input is
empty, and standard output and standard error are merged into a file. Java
encodes an unpaired surrogate in an argument as `?`; Ironwood passes U+FFFD,
its host-string convention. Java's launcher closes every descriptor above 2
in the child; Ironwood relies on close-on-exec, so a descriptor inherited by
the caller without that flag reaches the program. `exitValue()` follows Java's
POSIX convention of 128 plus the signal number. See D272.

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

Default and proved-fresh factory implementations retain their native-acquisition
protocol; injected implementations and delegates are held by managed references.
Cached streams, addresses and inventories can be shared. Endpoint copies and
exported byte arrays are independent results; bulk result arrays and their
elements follow managed reachability without element/shallow-array free.
Retained hostname/scope inputs are copied. Hostname and canonical getters may
share per-address cached text; host-string access never triggers reverse lookup.
Close releases native resources; Memory.collect() reclaims unreachable managed
graphs. Networking exceptions copy their messages.

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
