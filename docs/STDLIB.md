# Ironwood standard library

Ironwood currently ships a bundled, Java-shaped standard library from 188
source files. IronDocs covers 134 public and protected types across 10 packages:

| Package | Documented types | Purpose |
| --- | ---: | --- |
| `ironwood.lang` | 38 | Object model, text, iteration, resource cleanup, numeric helpers, system services, and exceptions |
| `ironwood.io` | 31 | Synchronous byte and character streams, standard input/output/error, and checked I/O failures |
| `ironwood.util` | 9 | Iteration, object and array helpers, comparators, randomness, optionals, joins, and bit sets |
| `ironwood.time` | 2 | Immutable epoch timestamps and date/time failures |
| `ironwood.time.format` | 1 | ISO timestamp parse failures |
| `ironwood.nio` | 5 | Checked heap byte buffers |
| `ironwood.nio.file` | 16 | POSIX paths, whole-file I/O, directory streams, visitor traversal, metadata, and file failures |
| `ironwood.nio.file.attribute` | 2 | Millisecond file times and basic attributes |
| `ironwood.pool` | 4 | Explicitly built, reusable object pools |
| `ironwood.ds` | 26 | Low-allocation lists, maps, sets, and primitive collections |

This document describes the library implemented in the current source tree. It
is an inventory and behavioral guide rather than an API-stability promise. The
`.iron` sources under [`stdlib/src/main/ironwood`](../stdlib/src/main/ironwood)
remain authoritative for exact signatures.

## Availability and imports

`ironwood.lang` is implicitly visible in every compilation unit, like
`java.lang` in Java. Its types do not require imports. All other standard-library
packages use ordinary explicit imports:

```java
import ironwood.ds.IntMap;

public class Main {
    public static int main(String[] args) {
        IntMap<String> names = new IntMap<String>();
        names.put(42, "Ironwood");
        System.out.println(names.get(42));
        return 0;
    }
}
```

The compiler discovers the bundled library automatically. A normal source
compile does not require the user to add its archive to `-cp`. The library is a
compile-time input to Ironwood's closed world; it is not loaded dynamically at
runtime. Only reachable library types enter the linked native program.

Running `./scripts/build.sh` recompiles the standard-library sources into
package-structured `.ironclass` files and recreates the deterministic
`compiler/build/ironwood-stdlib.ironjar`. Packaged compilers and IDKs install the
archive at `lib/ironwood-stdlib.ironjar`.

The client-facing `ironwood.testing` framework is also standard-library work,
but it is built and shipped as the optional `lib/ironwood-testing.ironjar`
archive. Tests add that archive to `-cp` explicitly; production compilations do
not discover it automatically. Eligible `TestSuite` methods use the
compiler-owned `@Test` directive for deterministic native registration. See
[Testing Ironwood code](TESTING.md).

## `ironwood.lang`

`ironwood.lang` defines the mandatory object graph and the language-level
foundation. Every class implicitly extends `Object`; interface and array
references can also widen to `Object`. Primitive values are not objects.

### Core object and text types

| Type | Current surface |
| --- | --- |
| `Object` | Public constructor plus identity-based `equals(Object)`, stable opaque `hashCode()`, and Java-shaped `toString()` defaults. A default runtime-created identity String is a caller-owned allocation eligible for compiler-checked `free`. |
| `CharSequence` | Interface declaring `length()`, `charAt(int)`, `subSequence(int, int)`, and `toString()`. |
| `Comparable<T>` | Interface declaring `compareTo(T)`; `String`, `Path`, and `Instant` implement their corresponding forms. |
| `String` | Final immutable UTF-16 text with copy, UTF-8 `byte[]`, and checked `char[]` constructors; length/character access; caller-owned substring, array-export, concat, and explicit `formatDecimal(long, int)`/`formatFixed(double, int, int)` numeric-field results; common contains/search/prefix/suffix/subsequence/copy operations; primitive/Object `valueOf`; lexicographic comparison; exact UTF-8 `byteLength`; content equality/hash; and identity `toString()`. Equal decoded literals and String constant expressions are pooled immortal objects; dynamic `+`/`+=` results are ordinary exact-size allocations. Runtime `intern()` is deliberately absent. |
| `StringBuilder` | Mutable UTF-16 text with empty, capacity, `String`, and `CharSequence` constructors; length/capacity queries and mutation; `isEmpty`, `charAt`, `setCharAt`, `ensureCapacity`, append and insert overloads, `delete`, `deleteCharAt`, `replace`, surrogate-aware `reverse`, and whole/offset `indexOf`. Append and insert cover `char`, whole/ranged `char[]`, `String`, whole/ranged `CharSequence`, `Object`, `boolean`, `int`, `long`, `float`, and `double`. `toString`, whole/ranged `substring`, and `subSequence` produce caller-owned String snapshots; destruction and failed-construction rollback reclaim backing storage. |
| `StringBuffer` | Java-shaped mutable UTF-16 counterpart to StringBuilder. Ironwood has no language threads, so synchronization has no observable role or cost. |
| `Runnable` | Interface declaring `void run()`. |
| `Number` | Abstract non-boxing numeric base with primitive conversion methods. |
| `Enum<E>` | Abstract base for compiler-created enum constants, with name, ordinal, comparison, identity equality/hash, and default text. User classes cannot extend it. |
| `Iterable<T>` | Interface declaring `Iterator<T> iterator()`. |
| `AutoCloseable` | Ordinary interface declaring `void close() throws Exception`; callers invoke it explicitly, normally from `finally`. Closing does not itself free the wrapper, which may be reclaimed by a separate proven-safe `free`. |

D117 adds `trim`, `strip`, `isBlank`, Unicode `toUpperCase`/`toLowerCase`,
`equalsIgnoreCase`, literal `replace(char, char)` and
`replace(CharSequence, CharSequence)`, UTF-8 `getBytes`/`String(byte[])`,
whole/ranged char-array `valueOf`, offset `lastIndexOf` and `startsWith`,
`repeat(int)`, and `join` for explicit String/CharSequence arrays or zero through
three individual elements. There is no Iterable join or implicit varargs array.

Text conventions are fixed: UTF-8 encoding and `en_US` case conversion. There
is no `ironwood.util.Locale`, locale overload, mutable default, or environment
locale discovery. Casing supports Unicode 15.0 expansions and contextual sigma;
it is not restricted to ASCII. `equalsIgnoreCase` uses Java's locale-independent
simple comparison. `trim` removes units up to U+0020; `strip`/`isBlank` use Java
21 whitespace, including its nonbreaking-space exclusions. Regex split and
charset overloads remain absent.

Transformations always produce fresh caller-owned results, even unchanged/empty
ones. Immutable operations allocate one exact result; CharSequence rendering
and generic join may require temporary snapshots/builder storage, reclaimed
before return or on failure. Byte construction replaces malformed UTF-8 with
U+FFFD; byte export replaces unmatched surrogates with `?`. See the
[contract, ownership and source review](STDLIB_STRING_REVIEW.md).

Source-level String `+`/`+=` concatenation is implemented as compiler-owned
Feature 76, including every primitive, null/reference conversion, pooled
constant expressions, and one exact-size ordinary result allocation per
maximal dynamic chain. Object conversions may obtain temporary text from
`toString()`. Concatenation conditionally reclaims each fresh owned rendering
after copying it, including when a later conversion or the final concatenation
throws; borrowed and mixed-result renderings remain untouched.
`String.valueOf(float)` and `valueOf(double)` each return one fresh caller-owned
String through that same floating conversion. `StringBuilder.append(float)` and
`append(double)` select the matching valueOf overload, copy the result, and
reclaim the temporary String even if builder growth fails. A float stays float
during conversion: `0.1f` renders as `0.1`, while explicitly widening it to
double preserves the corresponding longer double spelling. See D116 and the
[T5 review](STDLIB_FLOATING_TEXT_REVIEW.md). `Float.toString(float)` and
`Double.toString(double)` use the same matching conversions. General Formatter
support remains a separate deferred API.

D118 completes the requested everyday builder edits. Indices and ranges count
UTF-16 code units. `delete` and `replace` clamp an oversized end to the live
length; `substring` rejects it. `reverse` preserves surrogate pairs and can
form a pair from previously unpaired units, as Java does. CharSequence insertion
reads live source characters after moving the suffix, including self-insertion;
Object insertion snapshots `toString()` first. Exceptions from custom callbacks
can leave partial edits, matching Java. Bounds, null handling and overload
selection are covered by direct Java comparisons.

Under D119, `StringBuilder.setLength(int)` returns `this`, allowing
`builder.setLength(0).append("Hi")`. This is a deliberate extension of Java's
void-returning method; length changes and failures retain their prior behavior.
The return value aliases the builder and adds no allocation or ownership.

Array/text edits, integer insertion, reversal and search allocate no helper
objects within existing capacity. Growth replaces and reclaims the backing
array. Floating insertion uses the same temporary conversion and cleanup as
floating append. Object insertion preserves borrowed renderings and reclaims
only proven fresh, unescaped results. Source arguments are borrowed while their
characters are copied; callback publication still prevents an unsafe `free`.
See the [StringBuilder review](STDLIB_STRINGBUILDER_REVIEW.md).

D113 removes D097's narrow `String.format` overloads. Java `String.format` and
`Formatter` remain unimplemented, so calls fail at compilation for both literal
and dynamic format strings. There is no restricted format-string parser.

The Ironwood-specific `String.formatDecimal(long value, int width)` and
`String.formatFixed(double value, int width, int precision)` helpers return one
caller-owned numeric field, left-padded with spaces to a nonnegative minimum
width without truncation. Width zero disables padding. Fixed precision must be
zero through nine; use six explicitly for six fractional digits. Finite fixed
formatting retains the bounded binary scaling and `Math.round` algorithm, with
a magnitude limited by the `long` scaling range. It preserves negative zero;
NaN and infinities produce their names without fractional digits. These helpers
do not promise Java Formatter decimal rounding, flags, locale, or format syntax.

Migrate `String.format("%6d", value)` to `String.formatDecimal(value, 6)` and
`String.format("%9.2f", value)` to `String.formatFixed(value, 9, 2)` when the
explicit numeric-field behavior is intended. General Java format strings have
no compatible replacement yet.

The [T2 formatting review](STDLIB_FORMATTING_REVIEW.md) records the old `%05d`
silent space-padding defect, its removal through D113, and the still-open
Java zero-padding acceptance cases. The numeric-field helpers do not supply
that missing behavior.

The S0/U1/U2 allocation-result contract preserves fresh identity through direct
and wrapped calls, so a caller may reclaim `String.substring(...)`,
`String.toCharArray()`, `String.concat(...)`, primitive text conversions,
`StringBuilder.toString()`/`substring(...)`/`subSequence(...)`, and the default
`Object.toString()` result after all aliases are dead. String copy and `char[]` construction validate
before one exact-size allocation, copy rather than retain their source, and
remain visible to normal safe-`free` proof. See the [S0 review](STDLIB_S0_SOURCE_REVIEW.md),
[U1 review](STDLIB_U1_SOURCE_REVIEW.md), [U2 review](STDLIB_U2_SOURCE_REVIEW.md),
and [floating parsing review](STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md).

Integer/character text conversion (`Byte`, `Short`, `Integer`, `Long`,
`Character`, and the corresponding `String.valueOf` overloads) allocates
exactly its one fresh result, with no temporary managed array or native heap
scratch. `StringBuilder.substring(start, end)` and `subSequence(start, end)`
validate against the live length before copying only that range into one fresh
immutable String; even an empty result is independently owned. Neither creates a full-builder
snapshot nor retains the builder's mutable storage. Allocation failure leaves
no intermediate text or conversion arrays to reclaim.

```java
String literal = "value";          // Immortal compiler storage.
String copy = new String(literal); // One distinct caller-owned allocation.
free copy;                         // Reclaims only the heap copy.
```

### System and numeric helpers

The numeric classes below are static utility classes, not boxed primitive
objects.

| Type | Current surface |
| --- | --- |
| `System` | Immortal non-null `in`, `out`, and `err`; identity/allocation diagnostics; overlap-safe invariant-array copy; LF `lineSeparator()`; fresh-or-null environment/property lookup; process exit; wall-clock milliseconds; and monotonic nanoseconds. |
| `Boolean` | `parseBoolean`, primitive `toString`, `hashCode`, and `compare`. |
| `Byte`, `Short` | Range constants, `hashCode`, `compare`, radix-aware parsing, and primitive `toString`. |
| `Character` | Range/radix/surrogate constants and predicates; Unicode 15.0 digit, letter, case, whitespace and numeric queries; simple case mapping; primitive text/hash/compare and radix conversion. |
| `Integer`, `Long` | Range/size constants; signed and unsigned parsing/comparison; decimal/radix, hexadecimal, octal and binary text; hash/compare, bit count, leading zeros, rotation and sign. |
| `Float`, `Double` | Range/size/minimum-normal/infinity/NaN constants; Java-shaped parsing and shortest text; finite predicates, ordered comparison and canonical/raw bit/hash conversion. |
| `Math` | Common min/max/round/floor/ceil/pow and trigonometric, exponential, logarithmic, cube-root and hypot functions; checked arithmetic; floor division/modulus; sign, clamp, random, rint, angles and constants. Platform math does not claim `StrictMath` cross-platform reproducibility. |

`Float.parseFloat(String)` and `Double.parseDouble(String)` accept Java 21
decimal and hexadecimal syntax, exponents, suffixes, whitespace, NaN, and
infinities. Successful parsing borrows its String and creates no managed
allocation or Ironwood-owned native heap scratch; malformed non-null text raises
`NumberFormatException` and null raises `NullPointerException`. See the
[source review](STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md).

`System.arraycopy` validates nulls, array kinds, exact invariant element types,
and signed source/destination ranges. `System.identityHashCode` is null-safe and
bypasses an object's `hashCode()` override.

`System.in` is an immortal InputStream backed by descriptor zero.
`System.out` and `System.err` are compiler-emitted immortal
`ironwood.io.PrintStream` objects with distinct native channels. Loading them
creates no allocation, excludes them from `allocationCount()`, and cannot make
them eligible for `free`. A non-null `System.getenv` result is a caller-owned
String and must be reclaimed after its aliases die. Closing standard input is a
no-op; closing either standard output flushes it without disabling it.
At an Object `print` or `println` call, the compiler checks every possible
closed-world `toString()` target for the argument's static type. The call
borrows the object when none can publish its receiver, so code may print a
container or other proven-safe object and then `free` it. A missing, unknown,
or genuinely publishing target preserves the ordinary rejection.

`PrintStream.print(CharSequence)` and `println(CharSequence)` are allocation-free
Ironwood overloads. They read the sequence length once, consume UTF-16 units
through `charAt`, combine valid surrogate pairs, and write UTF-8 without a
String or array snapshot. The overload itself never calls `toString`; work
inside a caller-defined `length` or `charAt` remains the implementation's
responsibility. The existing String overload remains more specific, and an
Object-typed argument retains Object output behavior.

`System.getProperty` returns fresh values for the documented file/path/line,
UTF-8 encoding, host OS/user/directory/temp, and fixed language/country keys.
Unknown and JVM-only keys return null. `System.exit` terminates with the supplied
status. See [D122's compatibility review](STDLIB_COMPATIBILITY_EXPANSION_REVIEW.md).

`ironwood.lang` is implicit,
so ordinary output source does not import `System`; code that names
`PrintStream` directly imports it from `ironwood.io`.

### Exception hierarchy and checkedness

Ironwood deliberately follows Java's root exception shape and checked/unchecked
classification as closely as its native runtime permits. The current
`ironwood.lang` hierarchy is:

```text
Throwable
|-- Error
|   `-- OutOfMemoryError
`-- Exception
    |-- IOException
    |   |-- EOFException
    |   |-- FileNotFoundException
    |   `-- FileSystemException
    |       |-- NoSuchFileException
    |       |-- AccessDeniedException
    |       |-- FileAlreadyExistsException
    |       `-- DirectoryNotEmptyException
    `-- RuntimeException
        |-- ArithmeticException
        |-- ClassCastException
        |-- IllegalArgumentException
        |   |-- NumberFormatException
        |   `-- InvalidPathException
        |-- IllegalStateException
        |-- IndexOutOfBoundsException
        |   |-- ArrayIndexOutOfBoundsException
        |   `-- StringIndexOutOfBoundsException
        |-- NegativeArraySizeException
        |-- NullPointerException
        `-- UnsupportedOperationException
```

Any `Throwable` subtype that does not derive from `RuntimeException` or `Error`
is checked. The compiler therefore requires a checked exception to be caught or
declared, while `RuntimeException`, `Error`, and their descendants are unchecked,
as in Java. Standard-library additions should preserve the familiar Java
superclass and checkedness of an exception whenever that type has a Java
counterpart: recoverable checked failures derive from `Exception`, ordinary
programming/runtime failures derive from `RuntimeException`, and serious
runtime failures derive from `Error`.

Both language operations behind this model are implemented. `throw` accepts a
newly constructed or existing value whose type derives from `Throwable`.
Methods, interface methods, and constructors accept one or more types in a
`throws` clause; checked calls obey catch-or-declare analysis, generic
`Throwable`-bounded throws types are substituted at invocation, and overriding
declarations may omit or narrow but not broaden checked exceptions. See
[Features 57 and 58](IRONWOOD_VS_JAVA.md#feature-57) and the runnable
[`checkedexceptions`](../examples/checkedexceptions) example. These are
Ironwood classes and native compiler contracts, not JVM classes or JVM binary
compatibility.

Feature 100 uses `NullPointerException`, `ArrayIndexOutOfBoundsException`, and
`NegativeArraySizeException` for catchable implicit safety failures. The
[`runtimefailures`](../examples/runtimefailures) example exercises those paths
plus `throw null` through the ordinary native exception machinery.

Feature 105 uses one compiler-emitted immortal `OutOfMemoryError` for automatic
source-allocation exhaustion. It has a null message, direct `Error` ancestry,
bounded runtime-private trace/association state, and reusable identity; it is
not constructed through the ordinary allocator and cannot be freed. The
[`allocationfailure`](../examples/allocationfailure) example exercises object,
array, and dynamic String-result boundaries.

`IOException` and its descendants are checked, so callers of fallible
whole-file operations must catch or declare them. `FileSystemException`
preserves the file, optional other file, reason, and Java-shaped message;
`NoSuchFileException` identifies the common missing-path category.

`NoSuchElementException` in `ironwood.util` and `BufferOverflowException` and
`BufferUnderflowException` in `ironwood.nio` also derive from
`RuntimeException`. The concrete hierarchy intentionally omits Java's
`VirtualMachineError` layer: Java places that JVM-failure category between
`Error` and `OutOfMemoryError`, but Ironwood compiles to native executables and
has no virtual machine. `OutOfMemoryError` therefore derives directly from
`Error`. Do not add `VirtualMachineError` merely to copy the Java taxonomy;
reconsider it only if a concrete source-compatibility requirement needs code to
name or catch that type.

`Throwable` stores an optional message and cause exposed by `getMessage()` and
`getCause()`. `Throwable`, `Exception`, `RuntimeException`, `Error`,
`IOException`, `IllegalArgumentException`, `IllegalStateException`, and
`UnsupportedOperationException` provide the familiar no-argument, message,
cause, and message/cause constructors. A cause-only constructor uses the
cause's description as its message, or null for a null cause.
`getLocalizedMessage()` delegates to the virtual `getMessage()` by default.
`Throwable.toString()` calls the virtual
localized getter once and returns a fresh caller-owned String containing the
concrete qualified class name, followed by `: ` and the message when non-null.
An empty message retains the separator; a null message produces only the name.
Causes and traces are not part of this description. Object printing,
concatenation, and `StringBuilder.append(Object)` use this override and reclaim
its temporary result. A fresh, unescaped getter result is also reclaimed after
copying, including when description allocation fails; borrowed, retained, and
uncertain getter results remain untouched. See D114 and the
[T3 review](STDLIB_THROWABLE_REVIEW.md).

`Throwable` also
exposes `getSecondaryExceptionCount()` and indexed
`getSecondaryException(int)` access. If an ordinary `finally` throws while
another exception is already propagating, the first exception stays primary
and the later one is appended to that ordered secondary sequence. Each type in
this hierarchy has no-argument and `String`-message constructors. Ironwood does
not implement Java's suppressed-exception API, serialization, or finalization.
`initCause` supplies Java's single-assignment/self-cause checks.

D121 adds `printStackTrace()` to `System.err`, `printStackTrace(PrintStream)` to
the supported stdout/stderr streams, and chainable `fillInStackTrace()`.
Ordinary constructors call the virtual fill method, capturing source frames
before any throw. Throws and rethrows preserve that snapshot; explicit refresh
replaces it. A no-op override can keep an exception stackless. Printing uses
virtual descriptions and causes, shared-tail compression and cycle detection.
Ironwood cleanup failures are labeled `Secondary:`, separately from causes.
Private trace storage is released with a compiler-approved `free` or failed
construction; printing releases its owned traversal helpers and proven-fresh
renderings. `getStackTrace()` returns a fresh caller-owned array containing
immutable compiler-emitted process-lifetime `StackTraceElement` objects.
Mutable trace replacement remains absent.
See the [stack-trace review](STDLIB_STACK_TRACE_REVIEW.md) for emergency behavior,
ownership, compatibility limits and verification.

## `ironwood.io`

| Type | Current surface |
| --- | --- |
| `Closeable` | Ordinary AutoCloseable refinement declaring `close() throws IOException`. |
| `InputStream` | Abstract scalar and byte-array/range reads, skip, available, close, readAllBytes, transferTo and Java-shaped mark/reset defaults. The range default fills by scalar reads, propagates a first-read IOException, and returns a partial count after a later one. EOF is -1 and empty reads are zero. |
| `OutputStream` | Abstract scalar write; byte-array/range writes, flush, close. |
| `FileInputStream`, `FileOutputStream` | String and Path constructors; checked descriptor I/O. Output creates/truncates; its String/boolean and Path/boolean overloads append when true. |
| `BufferedInputStream`, `BufferedOutputStream` | Borrowed underlying stream, default 8192-byte or positive explicit capacity, retained buffers. |
| `ByteArrayInputStream` | Borrowed array/range, scalar/bulk read, skip, available; close is a no-op. Ranges are checked without clipping. |
| `ByteArrayOutputStream` | Default/explicit initial capacity, writes, size, reset, fresh toByteArray and UTF-8 toString snapshots, writeTo; close is a no-op. Malformed text uses replacement characters. |
| `Reader` | Scalar/array/range reads, skip, ready, close. The scalar bridge rejects a non-progressing positive-length subclass read. |
| `Writer` | Scalar/array/range/String writes, flush, close. |
| `InputStreamReader`, `OutputStreamWriter` | Incremental UTF-8 adapters; borrowed underlying stream. Malformed input uses U+FFFD; unpaired output surrogates use '?'. getEncoding returns borrowed "UTF8" while open, null after close. |
| `BufferedReader` | Buffered reads, ready, skip, close, and fresh-or-null readLine; LF/CR/CRLF terminators are omitted. Retains reusable line-building storage. |
| `BufferedWriter` | Buffered writes, LF newLine, flush, close. |
| `StringReader` | Borrows immutable text; read, ready, bounded forward/backward skip, close. |
| `StringWriter` | Retained live StringBuffer returned by getBuffer, writes, fresh toString, plus size/reset extensions; close is a no-op. |
| `PrintStream`, `PrintWriter` | Java-shaped text output/error state over supported native or borrowed OutputStream/Writer destinations. Text is UTF-8; null prints `null`. PrintStream adds allocation-free `CharSequence` output overloads. |
| `DataInputStream`, `DataOutputStream` | Big-endian primitive and modified-UTF data input/output over borrowed streams. |
| `FileReader`, `FileWriter` | Path/String character-file adapters using the established UTF-8 stream rules. |
| `RandomAccessFile` | String/Path construction with `r`, `rw`, `rws`, and `rwd`; scalar/bulk and primitive data operations; 64-bit position, seek, length and truncation. |
| `IOException`, `EOFException`, `UncheckedIOException` | Checked I/O, premature-EOF and unchecked-wrapper exception types. Ordinary stream EOF returns -1/null. |
| `FileNotFoundException` | Checked stream-open failure; a message constructor owns a copy of its input. |

Portable validation, buffering, line splitting, and incremental UTF-8 conversion
live in Ironwood. Native operations handle descriptor
open/read/write/available/close and exact-size String snapshots of byte storage.
Reads retry EINTR and may return a prefix; writes finish the range or throw,
possibly after partial external output. Flush drains buffers without fsync.
Output close attempts the underlying close even after a drain/flush failure;
D051 preserves the primary and ordered secondary exceptions. Double close is
harmless. File/wrapper/StringReader I/O after close throws IOException, including
empty requests; byte-array streams and StringWriter remain usable. Standard
streams stay open when wrapper close reaches them. Available/ready concern
buffered or immediately available input, not EOF or total input length; an
incomplete encoded prefix may need further input to finish a character.

Close releases external resources. Free reclaims proven-owned managed storage
and never closes a descriptor. Ordinary wrappers borrow their underlying object,
cascade close, and reclaim only their own buffers. Free outer wrappers before
borrowed inner objects; closing alone leaves the borrow active. Character-stream constructors
that take Path build owned graphs (an Ironwood extension used by Files factories)
and use strict UTF-8. They allocate all buffers before opening the file last,
so failed construction cannot strand a descriptor. Destructors reclaim owned
graphs after explicit close. Returned lines and output snapshots are independent,
caller-reclaimable results. The base scalar Reader/Writer bridges retain one
lazily allocated char slot; built-in overrides avoid that helper.

`ByteArrayOutputStream.toString()` decodes only the written prefix using
Ironwood's fixed UTF-8 default. It returns one fresh caller-owned String,
including for an empty stream, with no intermediate byte/char array. Malformed
sequences become U+FFFD as in Java's UTF-8 conversion; this differs from the
strict malformed-input failure of `Files.readString`. Reset, later writes,
growth, close, and stream reclamation do not change a prior snapshot. Printing,
concatenation, and builder append consume the override and reclaim fresh
renderings through the existing ownership protocol. See D115 and the
[T4 review](STDLIB_BYTE_STREAM_REVIEW.md).

Deferred members include charset objects/overloads, channels, file descriptors,
Appendable and CharBuffer overloads, stream replacement, and networking. The
generic InputStream range read uses Java's filling default: failure on the first
scalar read propagates, while a later IOException returns the bytes already read.
The default cannot reclaim a subclass-supplied throwable; its language object
follows the ordinary exception-ownership rules and is not implicitly collected.
`Reader.read()` rejects a zero result from a positive-length subclass read as a
non-progressing subclass contract violation; conforming subclasses are unaffected.
Byte skip of a negative count returns zero. See the exact API/ownership review in
[`STDLIB_U3_SOURCE_REVIEW.md`](STDLIB_U3_SOURCE_REVIEW.md) and runnable
[`projects/streaming`](../projects/streaming/README.md) and
[`projects/minitee`](../projects/minitee/README.md). Minitee composes two borrowed
outputs behind an application-defined `OutputStream`, reusing one copy buffer
and freeing the wrapper before its underlying file stream.

## `ironwood.time` and `ironwood.time.format`

D120 adds final `Instant` with `EPOCH`, `MIN`, `MAX`, `now()`, epoch-second and
epoch-millisecond factories, getters, `toEpochMilli()`, value equality/hash,
`Comparable<Instant>`, `isBefore`, `isAfter`, `parse(CharSequence)`, and ISO
`toString()`. `DateTimeException` represents date/range failures;
`DateTimeParseException` adds the parsed-text snapshot and error index.

Instant supports Java's full billion-year range and nanosecond precision.
Parsing accepts ISO extended years, numeric offsets, fractional seconds and
Java's leap-second/end-of-day forms. Output is canonical UTC with `Z` and
zero, three, six or nine fractional digits. `now()` uses the existing
millisecond wall clock; elapsed-time measurement remains `System.nanoTime()`.
Invalid grammar/ranges and numeric overflow use the corresponding Java-shaped
exceptions. Error wording is implementation-specific.

Every factory returns one fresh caller-owned Instant, and `toString()` returns
one fresh String. Parsing and rendering use primitive state without temporary
builders or arrays. Parse exceptions own copied diagnostic characters;
`getParsedString()` returns an independent fresh String. The constants remain
shared static values. See the [Instant review](STDLIB_INSTANT_REVIEW.md) for
exact signatures, grammar, ownership, source provenance and verification.

General date patterns, DateTimeFormatter, Temporal APIs, date arithmetic,
calendar classes, Clock, named timezones and Locale are absent. This feature
is independent of the deferred general `String.format`/Formatter APIs.

## `ironwood.util`

| Type | Current surface |
| --- | --- |
| `Iterator<E>` | Interface declaring `hasNext()` and `next()` with a default `remove()` that throws `UnsupportedOperationException`. |
| `NoSuchElementException` | Runtime exception with no-argument and message constructors, used when iteration has no next element. |
| `Objects` | Null-safe equality/hash/text and requireNonNull helpers. |
| `Arrays` | Primitive/reference fill, sort, equality, copy, text and hash helpers, including ranged forms. |
| `Comparator<T>` | Comparison interface used by reference-array sorting; lambdas are not required. |
| `Random` | Java-compatible 48-bit seeded generator with scalar, bounded and byte methods. |
| `Optional<T>` | Empty/present values with get, orElse, predicates and text. |
| `StringJoiner` | Prefix/delimiter/suffix joining, merge, empty value, length and text. |
| `BitSet` | Long-word bit storage, range/single-bit operations, scans, logical operations, conversion, equality/hash and text. |

There is no `forEachRemaining` default or spliterator surface. Enhanced `for`
and explicit iteration borrow the reusable iterator returned by current
collections rather than allocating one iterator per traversal; see the
collection rules below and [Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

## `ironwood.nio`

| Type | Current surface |
| --- | --- |
| `ByteBuffer` | Heap-backed buffer with relative/absolute typed access, bulk get/put, byte order, mark/reset, rewind, compact, slice and backing-array access. |
| `BufferUnderflowException` | Runtime exception raised by a relative read without enough remaining bytes. |
| `BufferOverflowException` | Runtime exception raised by a write without enough remaining space. |
| `InvalidMarkException` | Runtime exception raised by reset without a current mark. |
| `ByteOrder` | Big-endian and little-endian singleton values. |

`ByteBuffer.wrap(byte[])`, `array()`, and `slice()` expose shared storage.
Wrapped arrays remain caller-owned. An allocate-created buffer reclaims its
backing array when the compiler permits the buffer to be freed. Slices create
tracked lifetime loans, while `array()` publishes the backing array and makes an
owning buffer ineligible for compiler-proven reclamation. Ironwood adds no
runtime alias registry or lifetime tracking. Direct, mapped, and typed view
buffers remain absent.

## `ironwood.nio.file`

U2 provides lexical POSIX paths and Java-shaped whole-file operations on the
supported macOS and Linux hosts. U5 adds deterministic directory enumeration,
basic file attributes, and controlled recursive visitor traversal.

| Type | Current surface |
| --- | --- |
| `Path` | `Comparable<Path>` interface with one/two-component `of`, text/equality/hash, absolute/root/file-name/parent/name-count/index queries, prefixes, resolution, relativization, normalization, and absolute conversion. Returned path values are caller-owned. |
| `Paths` | Fixed-arity `get(String)` factory corresponding to Java's common one-component varargs call. |
| `Files` | Existing whole-file/factory/metadata calls plus delete, recursive createDirectories, no-replace copy/move, strict-UTF-8 readAllLines returning `ironwood.ds.ArrayList<String>`, and recursive `walkFileTree`. Calls borrow paths/content and retain no caller reference. |
| `DirectoryStream<T>` | Closeable, single-iterator directory view. `hasNext()` performs allocation-free lookahead, and ownership-aware `nextEntry()` returns a fresh caller-owned path. Explicit close releases the native handle. |
| `FileVisitResult`, `FileVisitor<T>`, `SimpleFileVisitor<T>` | Java-shaped traversal control, callback contract, and default continue/rethrow behavior. |
| `BasicFileAttributes` | Common size, timestamp, and file-kind queries. The default read follows symbolic links; `fileKey()` is null. |
| `FileTime` | Millisecond `fromMillis`/`toMillis`, comparison, equality, and hashing. Attribute getters return fresh values. |
| `DirectoryIteratorException`, `ClosedDirectoryStreamException` | Unchecked iteration-failure wrapper and use-after-close failure. |
| `InvalidPathException` | Unchecked invalid-input exception with input, reason, index, and message access. U2 rejects embedded NUL. |
| `FileSystemException` | Checked path-aware I/O exception with file, optional other file, reason, and message access. |
| `FileSystemLoopException` | Checked `FileSystemException` delivered when followed-link traversal reaches an ancestor directory. |
| `NoSuchFileException` | Checked `FileSystemException` specialization used by missing-path whole-file operations. |
| `AccessDeniedException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException` | Specific FileSystemException failures for permission, no-replace and non-empty-directory cases. |

Construction and `normalize()` are lexical and perform no filesystem access.
Each non-null built-in path result allocates only its wrapper and owned String;
normalization, resolution, sibling resolution, and absolute conversion do not
create intermediate managed paths or Strings. Lexical normalization uses
linear passes and scalar state rather than input-sized native scratch arrays.

Directory streams omit `.` and `..`, admit one iterator, and translate each
strict-UTF-8 host entry into one fresh `Path`. The native handle retains only
its directory cursor and lookahead entry. Callers must close the stream before
freeing its managed wrapper; destructors do not close external resources.

`Files.readAttributes(Path)` is the deliberately fixed basic-attribute overload
because Ironwood has no reflective `Class<A>` token or varargs options. It
follows links, while `Files.isSymbolicLink(Path)` performs a no-follow query.
Creation time is epoch zero when the host does not report it. Attribute times
have millisecond resolution.

`Files.walkFileTree(Path, FileVisitor<Path>)` performs a no-follow depth-first
walk. The fixed-arity `(Path, int, boolean, FileVisitor<Path>)` overload selects
maximum depth and link following without introducing Java's absent
`Set<FileVisitOption>` facade. Traversal results control termination, subtree
skipping, and sibling skipping. Streams close before post-visit callbacks and
also close on abrupt callback completion. Follow-link traversal compares each
directory with its ancestors and reports cycles through
`FileSystemLoopException`.

Callback paths and attributes are borrowed until the callback returns. The
compiler checks every possible closed-world visitor target and rejects a walk
whose callback can retain either value. Successful traversal therefore reclaims
its temporary paths, attributes, and closed stream wrappers without a runtime
ownership registry. See the runnable
[`examples/filetree`](../examples/filetree) suffix-search example.

Whole-file reads fill one unpublished result allocation directly from bounded
native chunks, resizing it if the file size is unknown or changes. No separate
full-file buffer is retained alongside the result. String writes borrow
immutable input without allocating a managed object. Other `CharSequence`
implementations use one exception-safe `char[]` snapshot: this preserves a
single observation of arbitrary mutable content and validates it before the
destination is opened/truncated. Byte writes and metadata queries allocate no
managed objects on success. Native path encoding and current-directory lookup
use 4 KiB stack buffers, with a reclaimed heap fallback only for longer host
spellings; this adds no new path-length limit.

The fixed-arity factories and write calls preserve Java source calls without a
hidden varargs allocation. Whole-file lengths are limited to Ironwood's signed
32-bit array range. Charset overloads, option enums, temporary paths,
`Files.list`, and an `ironwood.io.File` legacy facade remain absent after U5.
`Files.list` waits for the separately excluded
Streams/lambda design.
See also the runnable [`projects/minigrep`](../projects/minigrep) application.

## `ironwood.pool`

The [versioned IronDocs reference](api/README.md) includes an authored
`ArrayObjectPool` page covering its constructors, reuse contract, allocation
behavior, and a native-tested example.

All public pools own their values. `get()` lends an object to the caller;
`release(E)` returns a checked-out value to the same pool. Independently created
external objects are unsupported. `takeRetained()` has been removed.
The object type parameter has an explicit reference bound. Builders must return
fresh unescaped objects or null, and must not publish themselves during a call.
The compiler checks that contract. Reflection-based construction is absent.

| Type | Current surface |
| --- | --- |
| `ObjectBuilder<E>` | Factory interface declaring `E newInstance()`. |
| `ObjectPool<E>` | Owning pool interface declaring borrowed `E get()` and return-only `void release(E)`. |
| `ArrayObjectPool<E>` | Resizable array-backed pool with configurable preload and growth factor. Checkout is front-to-back; it grows on exhaustion and over-release. |
| `MultiArrayObjectPool<E>` | LIFO pool that adds linked array segments as it grows without copying existing segments. |

`ArrayObjectPool` and `MultiArrayObjectPool` are the only pool implementations.
`ArrayObjectPool` exposes `DEFAULT_GROWTH_FACTOR = 1.75f`. Both provide builder-based
constructors with initial capacity and optional preload count; the array pool
also accepts a custom growth factor. Generic and primitive linked lists use
`MultiArrayObjectPool` for internal entry reuse, preserving existing pool arrays
when capacity grows.

Pools are single-threaded. A growing creation array records every non-null
builder result once. Destruction frees every recorded object, including checked-out
objects, and all private storage. The builder remains borrowed. Multi-array
segment holders also appear in that array and free their own array containers.
Creation-array capacity is reserved before calling the builder, so growth failure
cannot lose a successfully created object. Failed construction reclaims objects
already recorded. Ordinary array free remains shallow.

Checkout and return of existing objects do not access the creation array or
perform identity checks; reuse allocates nothing after warmup. Return each
checkout at most once, only to the originating pool, and stop using it until
another checkout. Duplicate returns are unchecked and can corrupt reuse, but
cannot duplicate destruction entries. Null returns throw `IllegalArgumentException`.
External objects are unsupported and are not recorded or destroyed by the pool.

The compiler requires fresh, unescaped builder results, rejects independent free
of pooled values and access after pool destruction, and rejects escaped or
conflicting ownership. It conservatively ties even unsupported external returns
to the pool lifetime. No unsafe fallback or general static checkout-exclusivity
proof is provided. See D104 for the current contract.

`ArraySizing` and the private nested `MultiArrayObjectPool.ArrayHolder` are
implementation helpers. No runtime ownership registry is needed.

## `ironwood.ds`

`ironwood.ds` provides low-allocation, single-threaded data structures. It
contains 26 public types and 47 package-private entry, builder, iterator, and
mutable-holder implementations.

All generic `ironwood.ds` key, element, and value parameters declare
`extends Object` and accept reference types only. Supplying a primitive type
argument is rejected at the use site. Use the dedicated `Int` and `Long`
collection forms where available; a bare Ironwood type parameter would advertise
primitive specialization and is therefore not equivalent to these APIs.

### Utilities and primitive iterator values

| Type | Current surface |
| --- | --- |
| `Collections` | Focused utilities for `ironwood.ds`, currently `unmodifiableList(ArrayList<E>)`. |
| `IntHolder` | Interface declaring primitive `int get()`. |
| `LongHolder` | Interface declaring primitive `long get()`. |
| `MathUtils` | Static `isPowerOfTwo(long)` and validating `ensurePowerOfTwo(long)`. |

Primitive collection iterators return `IntHolder` or `LongHolder` so the
generic `Iterator<E>` interface can expose primitive values without allocating
a new boxed object on every step. The returned holder is mutable and reused;
read its value before advancing the iterator again.

### Lists

| Type | Storage and behavior |
| --- | --- |
| `ArrayList<E>` | Resizable generic array list with configurable initial capacity/growth, front/back addition and removal, indexed insertion/access/replacement/removal, forward/reverse value lookup and removal, clearing, value equality/hash/text, and reusable iteration. |
| `UnmodifiableList<E>` | Live read-only view of an `ArrayList<E>` with indexed access, forward/reverse lookup, containment, and reusable iteration. Every exposed mutation, including `set`, and iterator removal raises `UnsupportedOperationException`. |
| `LinkedList<E>` | Generic doubly linked list with pooled nodes, front/back operations, value containment, clearing, value equality/hash/text, and reusable iteration/removal. |
| `ArrayLinkedList<E>` | Fixed array plus pooled linked overflow, with tail addition/removal, value containment, `clear(boolean nullifyLiveArrayPrefix)`, value equality/hash/text, and composite reusable iteration. |
| `IntArrayList` | Primitive `int` counterpart to `ArrayList<E>` with indexed replacement, forward/reverse value lookup, containment, and a reusable `IntHolder` iterator. |
| `LongArrayList` | Primitive `long` counterpart to `ArrayList<E>` with indexed replacement, forward/reverse value lookup, containment, and a reusable `LongHolder` iterator. |
| `IntLinkedList` | Primitive `int` counterpart to `LinkedList<E>` with pooled nodes, value containment, and a reusable holder iterator. |
| `LongLinkedList` | Primitive `long` counterpart to `LinkedList<E>` with pooled nodes, value containment, and a reusable holder iterator. |

Reference list containment, `indexOf`, `lastIndexOf`, and value removal invoke
`equals` on the non-null query object for each stored reference, matching
Java's ordinary list direction. The two index queries return `-1` for null or a
missing value. Primitive variants use direct primitive equality. Array-list
`set` returns the previous value without changing size or resetting the reusable
iterator. Generic `set` rejects null after validating the index.

Collection-level `ensureCapacity`, `trimToSize`, `addAll`, `toArray`, and copy
constructors are absent. Callers can select initial capacity and growth when
constructing a list, use ordinary iteration for list-to-list copying, and use
`System.arraycopy` or `Arrays.copyOf` for bulk array operations.

Resizable list implementations copy live values into a fresh backing array and
explicitly free the detached old array. Linked implementations clear user
references before recycling detached nodes.

### Maps

Map iterators traverse values. After `next()`, the current key is available
through the concrete map's `getCurrIteratorKey()` method. Iterators support
removal and are reused by the owning map.
Hash-map iterator removal unlinks the last returned entry from its own bucket
and returns that entry to its pool. Other mappings, including colliding entries
and entries in previously visited buckets, remain intact.

Every map provides `containsValue` through a direct linear scan of its private
storage. As in Java's ordinary collections, the query object's `equals` method
tests each stored reference. `IdentityHashMap` keeps its established Ironwood rule
that identity applies only to keys. A null query returns false because maps
store no null values. The scan allocates nothing, changes no mapping, and does
not reset or consume the map's reusable iterator. A user-defined value `equals`
method can still perform arbitrary work, including allocation.

| Type | Key semantics and storage |
| --- | --- |
| `HashMap<K, E>` | Generic hash map using key `equals`/`hashCode`, separate chaining, pooled entries, configurable initial capacity/load factor, and mapping equality/hash/text. |
| `IdentityHashMap<K, E>` | Generic hash map comparing keys by identity and hashing with `System.identityHashCode`. |
| `LinkedHashMap<K, E>` | Generic value-key map with an additional insertion-order chain; iteration and rendering preserve insertion order. |
| `IntMap<E>` | Separate-chaining hash map with primitive `int` keys. |
| `LongMap<E>` | Separate-chaining hash map with primitive `long` keys. |
| `ByteMap<E>` | Fixed-array map covering all 256 signed `byte` bit patterns. |
| `CharMap<E>` | Fixed-array map for checked ASCII `char` keys from 0 through 127. |
| `CharSequenceMap<E>` | Content-keyed map that copies each `CharSequence` key into reusable private UTF-16 storage, preventing later caller mutation from changing the mapping. |
| `ByteBufferMap<E>` | Binary-key map accepting complete byte arrays, array ranges, or the remaining bytes of a heap `ByteBuffer`; keys are copied without changing the caller's buffer position or limit. |

Resizable hash maps relink pooled entries into new bucket arrays, then
explicitly free the detached bucket arrays without scrubbing their migrated
entry references first. `CharSequenceMap` and
`ByteBufferMap` expose configurable maximum key lengths. `ByteBufferMap` is
limited to the current heap-backed NIO surface.

### Sets

| Type | Membership semantics |
| --- | --- |
| `HashSet<E>` | Hash-map-backed generic set using value equality and hashing. |
| `IdentityHashSet<E>` | Generic set using reference identity. |
| `LinkedHashSet<E>` | Value-based set with insertion-order iteration and rendering. |
| `IntSet` | Primitive `int` set with a reusable `IntHolder` iterator. |
| `LongSet` | Primitive `long` set with a reusable `LongHolder` iterator. |

`HashSet`, `LinkedHashSet`, `IntSet`, and `LongSet` each use one private static
`Object` as the non-null value stored in their backing map. The first active use
of each set class lazily creates that ordinary allocation. Its static reference
keeps it live for the rest of the process, so `System.liveAllocationCount()`
rises by one even after the first set instance is freed; later instances of the
same class add no further filler allocation. Closed-world tree shaking and lazy
class initialization limit this to set classes that are retained and initialized,
with a maximum of four objects. Generic specializations share their declaring
class's static state. `IdentityHashSet` instead stores each element as both key and
value and needs no filler. This is D084's deliberate replacement for one filler
per set instance, not an accumulating leak. Allocation-sensitive code and tests
should initialize the set classes they use before recording a live-allocation
baseline.

Hash-set equality and hash codes are order-independent. `LinkedHashSet` preserves
insertion order only for iteration and rendering. Its equality check uses a
hash lookup for each element instead of repeatedly scanning the other set.
For equal-sized sets of size `n`, expected work is O(n) with well-distributed
hashes; collisions can increase the cost. Equality creates no helper allocations
and does not reset or advance the other set's reusable iterator.

All current collection `toString()` implementations use a temporary
`StringBuilder`, reclaim that builder and its backing array on normal and
exceptional exits, and return exactly one caller-owned String on success. Fresh
element text used only by Object append is conditionally reclaimed from
closed-world concrete-type metadata; borrowed or mixed-result overrides are
never reclaimed implicitly.

### Collection contracts and limitations

- Collections are currently single-threaded.
- A collection's `iterator()` normally resets and returns the same borrowed
  iterator object. Traversals of the same collection are therefore not
  reentrant, and retaining an iterator across another `iterator()` call is not
  supported.
- The collection owns that iterator. An accepted container `free` destroys it;
  callers cannot free it independently. A later use is rejected, while an
  escaped or uncertain borrow blocks the container `free`.
- Primitive iterators similarly own and reuse one mutable holder object. The
  holder is a nested borrow tied to the root collection.
- Maps reject null values; reference-key maps also reject null keys.
- Containers retain aliases to inserted values but do not own them. Removing or
  clearing a value does not free it.
- For known local bundled containers, the compiler tracks loans to inserted
  items. Successful `clear()` or destruction releases that container's loans,
  permitting caller `free` after all retaining containers release the item.
  Individual removal or indexed replacement keeps the previous loan because
  duplicates or other keys may retain the same object. The detailed limitations
  and a possible future proof design are recorded in
  [Container Removal and Caller-Item Loans](CONTAINER_REMOVAL_LOANS.md).
  Discarded removal or replacement results permit later clear/destruction.
  `ArrayLinkedList.clear(boolean)` retains loans until destruction because its
  unchecked package accessor can read inactive slots. Extracted item results,
  iterators, unknown calls, uncertain callbacks, subclasses and publication
  remain conservative, including insertions after contents were exposed.
  Copied-key maps borrow inputs during copying when all key reads prove safe.
- An unmodifiable list view borrows its backing list. Destroy every live view
  before freeing the list. Freeing a view destroys its iterator and leaves the
  backing list and elements alive. The compiler preserves this dependency through
  the verified `Collections.unmodifiableList` factory, including failure paths.
- Array-backed lists use logical size as the visibility boundary. Inactive
  slots may retain unobservable aliases until a later insertion overwrites
  them. `ArrayList.clear()` is therefore constant-time, while
  `ArrayLinkedList.clear(true)` clears only its current fixed-array prefix and
  does not scrub a stale tail left by an earlier `clear(false)`.
- Inactive pooled map/list entries may likewise retain user key/value aliases
  until checkout overwrites them. Structural links are still reset wherever
  reuse does not initialize every required link.
- Nulling remains required when it represents logical structure, including
  `ByteMap`/`CharMap` occupancy, hash bucket heads, and active linked-node
  connections.
- Collections borrow user elements and keys. Pool destruction owns its registered
  values and storage; it is separate from the non-owning collection element API.
- Superseded and terminal private backing arrays are reclaimed only where
  ownership analysis proves exclusive storage; terminal reclamation occurs in
  the declaring container's destructor.
- Every `ironwood.ds` iterable reclaims its compiler-proven-owned reusable
  iterator; primitive iterator destructors also reclaim their reusable holder.
  Set backing maps and hybrid-list overflow collections are destroyed in owner
  dependency order. Each pooled map and linked list owns its private entry pool
  and builder. Destruction frees the iterator, shallow bucket storage where
  present, the pool, and then the builder. The pool destroys every entry it
  created exactly once, including entries still in the container, and its
  retained capacity. Entries do not destroy caller-supplied keys or values.
  Failed construction follows the same pool-before-builder dependency order.
  Internal entry accessors return dependent borrows, just like reusable iterators.
- `CharSequenceMap` and `ByteBufferMap` entries own their copied-key objects.
  Entry destruction frees the copied key, whose destructor frees its backing
  array. This also applies during constructor rollback. Original caller keys
  remain borrowed and are never destroyed by the map.
- In those two maps, `getCurrIteratorKey()` returns a dependent borrow of copied
  storage owned by the map. It cannot be freed independently or used after map
  destruction; publishing it prevents reclamation when the compiler cannot
  bound its lifetime. The key is reusable storage, so its contents can change
  when an entry is reused. An explicit String snapshot has an independent lifetime.

## Current omissions

Beyond U1/U2/U3 text, file, and streaming operations, the library
does not yet provide broader filesystem manipulation,
networking, calendar and named-timezone APIs beyond Instant,
threading, synchronization, concurrent collections, atomics, general charsets,
cryptography, TLS, general math coverage, boxed primitives, general-purpose
Java collection interfaces, or a native FFI. These remain future library or
language work rather than hidden runtime dependencies.

The library also deliberately excludes facilities whose semantics depend on a
JVM or garbage collector, including runtime class loading, reflection-based
construction, soft references, and collector-control APIs. See
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md) for the broader supported-language
surface and roadmap. The proposed Java-shaped path from the current inventory
to useful file and command-line programs is in
[`STDLIB_ROADMAP.md`](STDLIB_ROADMAP.md).

## Licensing and provenance

The String/Character facades remain original or independently implemented
Ironwood code under `MIT OR Apache-2.0`. The fixed en_US native casing helper
and generated tables use `GPL-2.0-only WITH Classpath-exception-2.0`, with
Unicode 15.0 notices. The Instant facade is independent; its isolated Gregorian
calendar helper also uses the derived license and retains the complete Oracle
and original JSR-310 notices. See [the String review](STDLIB_STRING_REVIEW.md)
and [the Instant review](STDLIB_INSTANT_REVIEW.md). The independent System/PrintStream provenance and pinned
OpenJDK mechanism review are recorded in
[`SYSTEM_OUTPUT_SOURCE_REVIEW.md`](SYSTEM_OUTPUT_SOURCE_REVIEW.md); the U1
classification is recorded in
[`STDLIB_U1_SOURCE_REVIEW.md`](STDLIB_U1_SOURCE_REVIEW.md), and the U2 file/path
classification is recorded in
[`STDLIB_U2_SOURCE_REVIEW.md`](STDLIB_U2_SOURCE_REVIEW.md), and U3 streaming in
[`STDLIB_U3_SOURCE_REVIEW.md`](STDLIB_U3_SOURCE_REVIEW.md). The independent
floating parser is classified in
[`STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`](STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md).
Future source
substantially derived from OpenJDK must follow
the file-level policy in [`LICENSE_MECHANICS`](LICENSE_MECHANICS), the
workflow in [`OPENJDK_PORTING.md`](OPENJDK_PORTING.md), and the ledger in
[`SOURCE_PROVENANCE.md`](SOURCE_PROVENANCE.md).
