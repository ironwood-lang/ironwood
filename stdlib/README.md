# Standard library

The generated [IronDocs API reference](../docs/api/README.md) begins with
[`ArrayObjectPool`](../docs/api/ironwood/pool/ArrayObjectPool.md). See the
[IronDocs guide](../docs/IRONDOCS.md) to regenerate it from source comments.

Original and independently implemented standard-library files use
`MIT OR Apache-2.0`. A future file substantially derived from OpenJDK must
retain the exact upstream notices and use
`GPL-2.0-only WITH Classpath-exception-2.0` only after its upstream header is
verified. Package names do not determine licensing. See the repository's
`LICENSE_MECHANICS`, `docs/OPENJDK_PORTING.md`, and
`docs/SOURCE_PROVENANCE.md`. The fixed en_US casing helper and generated
tables are derived files described in `docs/STDLIB_STRING_REVIEW.md`.
The independent standard-output review is in
`docs/SYSTEM_OUTPUT_SOURCE_REVIEW.md`.
The independently implemented S0 text-reclamation slice is classified in
`docs/STDLIB_S0_SOURCE_REVIEW.md`.
The independently implemented U1 text-capable CLI slice is classified in
`docs/STDLIB_U1_SOURCE_REVIEW.md`.
The independently implemented U2 files and minigrep slice is classified in
`docs/STDLIB_U2_SOURCE_REVIEW.md`.
The independently implemented U5 directory traversal slice is classified in
`docs/STDLIB_U5_SOURCE_REVIEW.md`.
The independently implemented floating-point parsing follow-up is classified
in `docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`.

The client-facing `ironwood.testing` framework is an optional standard-library
module under `src/testing/ironwood`. Distributions include it as
`lib/ironwood-testing.ironjar`. Tests select that archive explicitly with
`-cp`, so the framework is available to Ironwood programmers without becoming
an implicit production dependency. See `docs/TESTING.md` for a complete example.

The standard library contains the mandatory compiler-owned root
`ironwood.lang.Object`, `ironwood.lang.String`, a message-bearing exception
foundation, `ironwood.lang.AutoCloseable`, `ironwood.lang.System`, and
`ironwood.io.PrintStream`. Every class
implicitly extends `Object`, while class,
interface, and array references widen to it. `Object` provides identity-based
`equals(Object)`, stable opaque `hashCode()`, and Java-shaped `toString()`.
Final `String` implements `CharSequence` and `Comparable<String>`, exposes
UTF-16 construction, indexing/search/copy/comparison, caller-owned
`substring(...)`, `concat(...)`, primitive text, and `toCharArray()` results,
exact UTF-8 `byteLength()`, Java content `equals`/`hashCode`, and identity
`toString()`.
Default Object identity text and `StringBuilder.toString()` snapshots are also
caller-owned fresh Strings. `StringBuilder` provides mutable UTF-16
construction, growth, append, and immutable snapshots. It frees detached
capacity during growth, frees its final owned character array in its
destructor, and rolls that child back if construction fails. Distinct immortal
`System.out` and `System.err` singletons expose primitive/Object
`print`/`println`, flush, and error-state operations through the isolated native
output boundary. `System` also provides one-value environment lookup, LF line
separation on supported hosts, and wall/monotonic clocks.
Equal decoded String literals are canonicalized by the compiler across the
final linked program; this is not a runtime `String.intern()` facility. D059
commits source `+`/`+=` concatenation as language work and deliberately excludes
an unbounded process-global runtime interning method. A future explicit bounded
interner would be an application-owned collection.

`ironwood.lang.AutoCloseable` is an ordinary interface declaring
`void close() throws Exception`. Callers invoke it explicitly, normally from
`finally`. Closing releases a resource-defined external capability; it never
performs object-memory `free`, and a source destructor does not replace an
explicit resource-closing protocol.

`ironwood.nio.file.Path` and `Paths` provide Java-shaped lexical POSIX paths.
Each returned path owns its normalized text and can be explicitly reclaimed.
`ironwood.nio.file.Files` provides whole-file byte and strict UTF-8 String
reads/writes, metadata queries, closeable directory enumeration, and recursive
visitor traversal with explicit depth and symbolic-link policy. I/O
arguments are borrowed; successful whole-file, attribute, time, and directory
entry results are fresh and caller-owned. Failures use the checked `IOException`
family, including missing-file and filesystem exceptions. Providers, option
arrays, permissions, Java Streams, and legacy `File` remain absent.

The numeric helpers are static-only Java-shaped APIs with private constructors.
Boolean, byte, short, character, integer, and long helpers provide the U1
non-boxing parsing/formatting/hash/comparison subset; float and double helpers
also provide Java-shaped parsing without Ironwood-owned heap scratch,
finite/NaN/infinity predicates, and comparisons. Common `Math`
overloads cover extrema, absolute value, rounding, floor/ceil, square root, and
power with tested NaN, infinity, signed-zero, and saturation behavior. These
classes are not boxed primitive values. Range, infinity, NaN, `E`, and `PI`
constants are
public static-final compile-time constants.
`ironwood.lang.System` has a private constructor and exposes Java-shaped
static-final `out`/`err` references, `identityHashCode(Object)`,
`arraycopy(Object, int, Object, int, int)`, environment, line-separator, and
clock services. It also exposes the Ironwood diagnostics `allocationCount()`
for cumulative successful allocations and `liveAllocationCount()` for the
current live count. The streams are compiler-emitted immortal objects, create
no allocation, and cannot be freed.
Identity hashing is null-safe and bypasses overrides; array copy
validates exact invariant array types and signed ranges and preserves overlap
and reference-alias behavior.

`ironwood.lang.Throwable` stores a nullable message exposed by `getMessage()`.
Its count and indexed-access methods expose later exceptions raised while an
ordinary `finally` is unwinding an earlier primary exception.
Its initial Java-shaped branches are `Error` (including `OutOfMemoryError`) and
`Exception`/`RuntimeException`, with the common argument, state, index,
unsupported-operation, arithmetic, null-pointer, negative-array-size,
string-index, number-format, and class-cast runtime
exceptions.
`ironwood.util.NoSuchElementException` supports iterator and collection APIs.
Each type has no-argument and `String`-message constructors. This initial
hierarchy deliberately omits cause chaining, Java's suppressed-exception API,
serialization, finalization, and monitor APIs. Uncaught source-level traces are
captured and printed by compiler/runtime-private machinery; `Throwable` exposes
no mutable stack-trace array or programmatic trace API in this first slice.

`ironwood.lang.Iterable<T>` and `ironwood.util.Iterator<E>` provide the
Java-shaped iteration foundation used by pools and data structures. `Iterable`
declares `iterator()`, while `Iterator` declares `hasNext()`, `next()`, and
`remove()`. They intentionally add no default methods, spliterator, enhanced-for
lowering, allocation, reclamation, or ownership transfer. Library collections
may return a borrowed reusable iterator and reset that same object on every
`iterator()` call; callers must follow the concrete collection's documented
reentrancy and removal contract.

`ironwood.pool.ObjectBuilder<E>` and `ObjectPool<E>` provide the explicit,
statically typed construction and reuse boundary for object pools.
`ArrayObjectPool<E>` and `MultiArrayObjectPool<E>` are the only implementations.
Both support builder preload, generic interface dispatch, and owning reuse.
`get()` lends a pool-created value. `release()` returns it for reuse. A growing
creation array records every builder result once; freeing the pool destroys all
recorded objects, including those still checked out, and its private storage.
The builder remains borrowed. Compiler checks reject cached factory results,
dangling uses, conflicting ownership, and unproved reclamation. Duplicate returns
are unchecked; external objects are unsupported and never recorded for destruction.
Null returns fail. Collection keys and values remain borrowed.

`ArrayObjectPool<E>` checks preloaded slots from front to back and grows on
exhaustion and over-release. It uses `ArraySizing` and `System.arraycopy` and
reclaims superseded backing arrays after proven detachment.
`MultiArrayObjectPool<E>` traverses linked array segments without copying them;
segments remain reusable until pool destruction. Both pools are single-threaded.
Generic and primitive linked lists use `MultiArrayObjectPool` internally. Reflection
constructors and garbage-collector retention operations are absent.

The first `ironwood.ds` utilities are `IntHolder`, `LongHolder`, and
`MathUtils`. The holder interfaces retain their primitive-returning `get()`
contracts without boxing. `MathUtils.isPowerOfTwo(long)` handles the complete
signed `long` domain, and `ensurePowerOfTwo(long)` throws
`IllegalArgumentException` for zero, negative, and non-power values. Its message
is temporarily the stable literal `Not a power of two`; embedding the rejected
number remains pending String concatenation.

The first collection tranche provides `ArrayList<E>`, `LinkedList<E>`, and
`ArrayLinkedList<E>`, plus primitive `IntArrayList`, `LongArrayList`,
`IntLinkedList`, and `LongLinkedList` variants. The public package is flattened
to `ironwood.ds`; builders, entries, holders, and iterators are package-private
top-level helpers. Iterators are borrowed reusable objects, and primitive
iterators return the same mutable holder on each `next()`. Linked variants
recycle detached nodes through `ironwood.pool` and clear user references before
reuse. Array variants copy live values into a fresh backing and explicitly free
the detached old container; their generic, `int`, and `long` forms support
indexed replacement and forward/reverse value lookup. The generic read-only
view delegates those queries and rejects replacement. There is no collector or
soft-reference cleanup API.

`ByteMap<E>` covers all 256 signed-byte bit patterns with one fixed backing
array. `CharMap<E>` covers checked ASCII keys 0 through 127. Both reject null
values, return a borrowed reusable iterator, expose the last iterated primitive
key, support iterator removal, and implement mapping equality, hashing, and
Java-shaped text output without allocating an iterator per traversal.

The hash-map tranche adds primitive-key `IntMap<E>` and `LongMap<E>`, value-key
`HashMap<K,E>`, identity-key `IdentityHashMap<K,E>`, insertion-ordered
`LinkedHashMap<K,E>`, and copied-key `CharSequenceMap<E>`. They use separate chaining
with pooled entries, configurable load factors, reusable iterators with current
key access, and deterministic reclamation of superseded bucket arrays after
entries are relinked.
`IdentityHashMap` bypasses overrides through `System.identityHashCode`; `LinkedHashMap`
maintains an independent insertion-order chain; `CharSequenceMap` snapshots each
key into its entry's reusable `StringBuilder` so caller mutation cannot change a
mapping. Soft references and collector-cleanup APIs are intentionally absent.

`HashSet<E>`, `IdentityHashSet<E>`, and `LinkedHashSet<E>` are thin map-backed adapters with
value, identity, and insertion-order behavior respectively. `IntSet` and
`LongSet` use the primitive-key maps and return reused mutable holders from
their borrowed iterators. The original Java static filler objects become
instance-owned sentinels because Ironwood deliberately has no runtime class
initializer for allocating reference-valued static state. Hash-set equality and
hash codes remain order-independent; linked iteration and rendering preserve
insertion order.

`ironwood.nio.ByteBuffer` is an original heap-only compatibility foundation for
the final buffer-key map. It supports allocate/wrap, position and limit state,
relative and absolute byte reads, bulk array/buffer writes, clear, and flip.
`wrap(byte[])` shares borrowed storage, while `allocate(int)` records owned
storage that its destructor frees. The borrowed and owned aliases are distinct
so destroying a wrapped buffer never reclaims caller storage. `BufferUnderflowException` and
`BufferOverflowException` are catchable runtime exceptions. Direct memory,
mapping, native addresses, slices, typed views, and byte-order APIs are omitted.

`ByteBufferMap<E>` completes the supported data-structure inventory with
heap-backed binary keys. It accepts complete byte arrays, array ranges, and the
remaining bytes of a heap `ByteBuffer`, copying key bytes into reusable private
entry buffers without changing caller buffer position or limit. Values remain
non-owned retained aliases. Direct-buffer configuration and GC cleanup methods
are intentionally absent with the corresponding NIO and collector facilities.

`scripts/build.sh` compiles these Ironwood sources into ordinary format-1
`.ironclass` files and creates the deterministic
`compiler/build/ironwood-stdlib.ironjar`. Host packages and IDKs install that
archive as `lib/ironwood-stdlib.ironjar`; loose sources and classes remain
development fallbacks. The compiler discovers the bundled root without runtime loading.
`ironwood.lang` is implicitly visible; `ironwood.io`, `ironwood.util`,
`ironwood.pool`, and `ironwood.ds` use ordinary imports.
The mandatory `Object` and its `String` return type enter every closed world;
other library classes remain dependency-driven, so unused `System` and
`PrintStream` code is absent from the generated program. The library can grow
independently of the mandatory runtime while retaining that elimination model.
