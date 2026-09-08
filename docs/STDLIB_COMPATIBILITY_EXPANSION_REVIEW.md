# Standard-library compatibility expansion review

## Scope and compatibility target

D122 adds a practical Java 21-shaped surface across `ironwood.lang`,
`ironwood.util`, `ironwood.io`, and `ironwood.nio`. The implementation target is
the behavior of calls admitted by Ironwood overload resolution, subject to the
explicit boundaries below.

| Area | Added surface |
| --- | --- |
| Character | Unicode digit, letter, case, whitespace and numeric queries; case mappings; radix conversion; radix and surrogate constants and predicates. |
| Numeric text and bits | Float/Double text, hashes and raw/canonical bit conversion; Long radix and unsigned text; Integer/Long binary, octal, bit count, leading zeros, rotation, sign and unsigned parse/compare helpers; size constants and floating minimum-normal constants. |
| Math | Trigonometric, exponential, logarithmic, cube-root and hypot functions; exact arithmetic; floor division/modulus; sign, clamp, random, rint and angle conversion. |
| Core types | `Runnable`, non-boxing `Number`, Java-shaped `Enum<E>` for compiler-created enums, unsynchronized `StringBuffer`, System properties/exit, mutable Throwable causes, public trace snapshots and requested cause constructors. |
| Utility | `Objects`, primitive/reference `Arrays`, `Comparator`, Java-compatible seeded `Random`, `Optional`, `StringJoiner`, and `BitSet`. |
| I/O | File readers/writers, PrintWriter, output-backed PrintStream, data streams, RandomAccessFile, stream bulk/mark operations, live StringWriter buffers, Flushable and UncheckedIOException. |
| NIO | Ordered typed ByteBuffer access, bulk/indexed operations, mark/reset, rewind, compact, slice and backing-array access; path name/prefix/relative operations; file mutation, line reading and permission-specific failure. |

The numeric and Character facades remain static and do not introduce primitive
boxing. A class named `Integer` exists only as the existing static primitive
helper, not as an instantiable boxed value.

## Deliberate boundaries

- `ironwood.io.File` remains deferred. String and Path constructors cover the
  implemented stream and random-access use cases.
- `Files.list` remains absent because its Java contract returns a Stream.
  Ironwood does not invent an eager result under that name.
- `Files.readAllLines` returns `ironwood.ds.ArrayList<String>` because the Java
  Collections facade is excluded. Exceptional construction reclaims every
  partial line and container. A successfully returned list has the established
  non-owning element contract.
- Reference-array comparator sorting uses `<T extends Comparable<T>>`. This
  preserves natural ordering for a null comparator and places an enforced
  compile-time boundary around non-comparable element types.
- `Random.nextGaussian` remains absent until Ironwood has a StrictMath-equivalent
  logarithm and square-root contract. Native libm can change Java's seeded
  Gaussian sequence by an ulp, so the Java name is not exposed with that
  silent difference.
- `Objects.toString` preserves the selected object's returned String identity,
  including borrowed results. Its ownership therefore follows the underlying
  `toString` implementation rather than promising a fresh result.
- `StringBuffer` has the Java-shaped mutable text API but no synchronization
  cost because Ironwood has no language thread model.
- `Iterator.remove()` is a default method that throws
  `UnsupportedOperationException`; implementations override it only when they
  support mutation.
- The generic `InputStream.read(byte[], int, int)` fills through repeated scalar
  reads. An IOException on the first read propagates, while a later failure
  returns the partial count. The default cannot reclaim a subclass-supplied
  throwable, which retains ordinary exception-object ownership. `Reader.read()`
  rejects a zero result from a positive-length subclass request as a
  non-progressing contract violation.
- `RandomAccessFile` accepts `r`, `rw`, `rws`, and `rwd`. File-descriptor,
  channel, and deferred File overloads are absent.
- `ByteBuffer` is heap-backed. Unaliased allocate-created storage is reclaimed
  by the buffer destructor. Slices establish compiler-tracked lifetime loans.
  Calling `array()` publishes the backing storage and prevents compiler-proven
  reclamation of an owning buffer. Wrapped arrays remain caller-owned. No
  runtime alias registry or lifetime tracking is added.
- `System.getProperty` recognizes file/path/line separators; UTF-8 encoding
  properties; OS name, architecture, and version; user name, home, and working
  directory; temporary directory; and fixed `en`/`US` language conventions.
  Unknown and JVM-only names return null. Returned values are fresh when
  present.
- `Throwable.getStackTrace()` returns a fresh caller-owned array. Its immutable
  elements and their strings are compiler-emitted process-lifetime objects.
  `setStackTrace`, Java suppression, serialization, and finalization remain
  absent. `initCause` follows the single-assignment and self-cause rules.

## Ownership and runtime design

The expansion preserves the typed-IR pipeline. Character properties, floating
bit conversions, math calls, System services, file operations, stream scalar
operations, and Throwable trace snapshots use dedicated typed instructions or
existing structured calls. The runtime contains only native mechanisms; it
does not carry JVM bytecode, reflection, registries, or caller-misuse tracking.

Formatting and array/string snapshots return fresh results where the compiler
records that ownership. Null `Arrays.toString` results are fresh as well, so
one callable never mixes reclaimable and immortal results. Array copy methods
reject null before allocating a destination. StringJoiner snapshots callback
text before mutating joiner state. Exceptional readAllLines paths release the
pending line, accumulated lines, list, and reader.

Public stack snapshots allocate only the reference array. At link time the
compiler emits immutable `StackTraceElement` objects for every source frame that
the on-demand pseudo-probe decoder can capture. The native runtime maps
captured source sites to those objects without allocating elements or
introducing a global mutable registry.

## Provenance

The new facades, algorithms, compiler integration, tests, and native mechanisms
are original or independently implemented Ironwood code under
`MIT OR Apache-2.0`. Java 21 public specifications and independently authored
differential probes define the compatibility target. No OpenJDK implementation
body, comment, Javadoc, or test was copied or adapted for this expansion.

Character property, numeric-value, digit, and case data extend the existing
generated Unicode 15.0 table. The generated file retains its complete upstream
Classpath-covered helper headers and Unicode notice. The generator and
provenance ledger record that derived-data boundary.

## Verification

Focused checks cover Unicode code points, radix and unsigned boundaries,
floating bits/text, exact and floor arithmetic, NaN and signed-zero behavior,
collection null/range/reentrancy cases, deterministic random sequences, stream
and file behavior, RandomAccessFile modes, ByteBuffer order/alias/state,
permission failures, allocation rollback, core types, System services, cause
rules, public traces, source-frame identity, enum-base rejection, and `-O3`
native execution. The repository's license audit and diff checks cover the
complete change. No full-suite or release-platform claim is made.
