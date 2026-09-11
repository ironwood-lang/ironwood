# Standard-library usefulness roadmap

- **Status:** Active delivery; S0, U1, U2, U3, and floating parsing complete;
  U4 superseded by continuous collection development
- **Compatibility baseline:** Java SE 21 public APIs where they fit Ironwood's
  closed-world native model
- **Networking status:** Pending for the near future
- **Collection surface:** `ironwood.ds`, not a duplicate Java Collections
  Framework
- **Implementation model:** independently implemented Ironwood facades and
  native mechanisms plus carefully reviewed OpenJDK-derived translations where
  they improve compatibility
- **Primary objective:** make Ironwood capable of building useful native command-line
  programs without application-specific runtime hooks or invented substitutes for
  familiar Java APIs

## Executive recommendation

The language gate is complete: no numbered language feature remains pending.
The completed model includes the D046 object foundations, D063 native
specialization for unbounded primitive generic arguments, checked exceptions,
deterministic resource cleanup, deterministic class destruction with failed-
construction rollback, arrays, modern statements, and modern switch.
D047 deliberately excludes varargs so call syntax cannot create an unowned
hidden array. S0 and the U1 text-capable CLI tranche are complete without
requiring another numbered language feature.

The completed U2 milestone provides the checked I/O, lexical path, whole-file,
and native failure boundaries needed by useful file tools. Its
`projects/minigrep` acceptance application accepts a literal query and path,
reads strict UTF-8, handles LF/CRLF/final lines, supports `IGNORE_CASE`, keeps
matches on stdout and diagnostics on stderr, and returns distinct native
statuses. The focused Java-compatible floating parser is also complete without
managed or Ironwood-owned native heap scratch on its successful path. U3
streaming I/O is complete. Application code uses explicit
`String.formatDecimal` and `String.formatFixed` numeric fields. D113 removes the
misleading narrow `String.format` overloads; Java `String.format` and
`Formatter`, including general flags and multiple arguments, remain later
compatibility work under D117's fixed en_US convention. Configurable locale
support is excluded.

This is a better target than attempting to complete `ironwood.lang` or
`ironwood.util` package-by-package. A language becomes useful through complete
paths across its compiler, library, runtime, errors, resources, packaging, and
documentation. A large collection of isolated methods does not establish that
path.

The library should feel like the Java standard library through familiar public
types, signatures, behavior, and exceptions. Implementation should use a mixed
strategy: translate suitable OpenJDK algorithms under the mandatory
source-derivation rules, independently implement small portable facades, and
keep compiler/runtime/native mechanisms original to Ironwood. API familiarity
does not require copying JVM internals or translating every OpenJDK file.

Beyond completed U5, the following work remains open and requires separate
selection before implementation:

1. review Java `String.format` and `Formatter` support when a concrete consumer
   requires flags, multiple arguments, or broader precision;
   any Java-shaped entry point must pass the behavioral contract review,
   including the open [T2 zero-padding acceptance cases](STDLIB_FORMATTING_REVIEW.md#pending-zero-padding-acceptance-cases);
2. begin the near-future networking milestone after streaming, resource
   ownership, and cross-platform error handling are stable.

`ironwood.ds` continues to expand only when useful programs or ported library
code expose a concrete missing capability. It is ongoing library work rather
than a separate application gate.

Java Collections compatibility, regular expressions, configurable locale support,
and broad framework compatibility are excluded.
Networking is supported roadmap work with status **Pending for the near
future**. Threads, lambdas, and Java object serialization are deliberately
excluded rather than postponed.

## Language gate 0: Java-like object-oriented foundations (complete)

Ironwood's philosophy should be recognizably Java-like at the object-model
level even when its closed-world native execution and explicit reclamation lead
to different runtime choices. The present support matrix is:

| Feature | Current support | Important limitation |
| --- | --- | --- |
| Classes and encapsulation | Yes | Instance initialization, blank-final analysis, visibility, and field hiding are enforced. |
| Class inheritance | Yes | Single class inheritance includes abstract/final classes and methods. |
| Interfaces | Yes | Multiple implementation/extension includes constants and abstract, default, static, and private methods. |
| Runtime polymorphism | Yes | Class-virtual and interface dispatch, widening references, covariant reference returns, casts, and `instanceof` work. Closed-world devirtualization may replace provably monomorphic calls. |
| Method overriding | Yes | Signature, visibility, static/instance form, finality, interface obligations, and covariant reference returns are checked; every declared override/implementation requires the built-in `@Override` directive. |
| Method/constructor overloading | Yes | Parameter-signature overloads, generic methods, and most-specific reference overload selection work; invocation is fixed-arity because varargs are deliberately excluded. |
| `this` | Yes | `this`, `this(...)`, and qualified enclosing `Outer.this` work. |
| `super` | Yes | Constructor chaining, direct superclass fields/methods, qualified inner-superclass construction, and qualified interface defaults work. |
| Abstract classes/methods | Yes | Obligations and transitive concrete-class completeness are checked. |
| Static nested, member inner, and local classes | Yes | Exact enclosing instances, lexical identities, and access rules are preserved. |
| Anonymous classes | Yes | Class extension/interface implementation, enclosing instances, captures, and generic construction work. |
| Generics | Yes, bounded reference model plus native primitive shapes | Bounds, wildcards/capture, generic methods/constructors, inference, diamond, exact owners, source-provable casts, and unbounded primitive arguments work. Raw, unchecked, and boxing forms are excluded. |
| `final` object-oriented constraints | Yes | Final classes/methods and initialized or blank-final instance fields are enforced. |
| Annotations | No, by design | General annotation syntax and processing are excluded. The built-in `@Override`, `@Test`, and `@SuppressUnfreed` directives are not annotation instances. |

The complete rules, deliberate limits, runnable examples, and verification
commands are recorded in `OBJECT_MODEL.md` and `GENERICS.md`. D047's fixed-arity
rule is part of this completed gate rather than a pending implementation item.

This gate does not mean copying every Java feature. Raw generic types are
rejected for safety, annotations remain out of scope, and reflection, dynamic
class loading, runtime code generation, and JVM binary compatibility remain
contrary to Ironwood's design. Primitive generic arguments use the D063 native
specialization/value-layout design rather than Java boxing.

## What “useful” means

Ironwood reaches its first useful standard-library baseline when a programmer
can write, test, package, and distribute native tools that can:

- consume command-line arguments and standard input;
- distinguish standard output from standard error;
- represent, resolve, inspect, read, write, copy, move, and delete filesystem
  paths;
- process files incrementally rather than requiring every input to fit in one
  allocation;
- search, slice, compare, and construct UTF-16 strings with Java-familiar APIs;
- parse and format primitive values;
- use Ironwood's existing lists, maps, sets, iterators, arrays, and object
  helpers under `ironwood.ds`;
- report I/O failures as typed Ironwood exceptions carrying actionable path and
  operating-system context;
- close native resources deterministically and separately from `free`;
- run without a JVM and without exposing raw pointers or file descriptors to
  ordinary source; and
- include only reachable standard-library implementation in the final native
  executable.

The baseline does not require the whole Java standard library. In particular,
it does not require the Java Collections Framework, regular expressions, GUI
APIs, database APIs, XML, object serialization, a security manager, runtime
class loading, reflection, or Java Streams. Networking is not a baseline gate,
but it is explicitly tracked as near-future work rather than excluded.

## Lessons from Rust's getting-started projects

The official Rust learning sequence uses projects as capability tests:

| Rust project | Capabilities exercised | Ironwood implication |
| --- | --- | --- |
| [Hello World](https://doc.rust-lang.org/book/ch01-02-hello-world.html) | Native compilation and standard output | Complete through `System.out.println(String)` and `examples/hello`. |
| [Guessing game](https://doc.rust-lang.org/book/ch02-00-guessing-game-tutorial.html) | Standard input, mutable text, numeric parsing, errors, formatted output, and an external random package | U1 completes integer parsing and primitive output. Add `System.in` and buffered line input; random generation need not block the core library because Rust itself uses an external crate here. |
| [Minigrep](https://doc.rust-lang.org/book/ch12-00-an-io-project.html) | Arguments, file paths, file reading, string search, tests, environment configuration, stdout/stderr separation, and error handling | Make a literal-search `minigrep` the U2 file-capable acceptance program. |
| [Multithreaded web server](https://doc.rust-lang.org/book/ch21-00-final-project-a-web-server.html) | TCP networking, protocol parsing, concurrency, and worker management | Ironwood will not reproduce the multithreaded architecture. The planned near-future networking milestone should use a single-threaded/event-driven native design. |

The useful lesson is not to reproduce Rust APIs. It is to adopt the same
vertical-project discipline while keeping Ironwood source Java-shaped.

## Compatibility policy

### Names and source shape

- Use familiar Java names under Ironwood packages: `ironwood.lang.String`,
  `ironwood.io.BufferedReader`, `ironwood.nio.file.Files`, and
  `ironwood.nio.file.Path`.
- Do not invent a convenience type when Java already has a clear, suitable
  surface. A new output helper must not replace `System.out`; a new file helper
  must not replace `Path`, `Paths`, or `Files` merely to save implementation
  work.
- Preserve Java signatures and behavior when Ironwood can express them without
  conflicting with explicit reclamation or closed-world compilation.
- When Ironwood cannot yet express a Java signature, record the language
  prerequisite and defer that member instead of publishing a permanently
  incompatible substitute.
- When a Java API uses varargs, provide useful fixed-arity overloads or an
  explicit array parameter and document the deliberate source incompatibility.
  For example, `Paths.get(String)` can preserve the common one-argument source
  call without introducing a hidden array allocation. Ironwood has no Java
  binary linkage or reflective method lookup to preserve.
- Fixed arity reduces the number of arguments, not the behavior promised for
  those arguments. A Java-valid input must not become an unsupported-pattern
  runtime trap or silent approximation behind a familiar method. Until that
  behavior is implemented, omit the member, enforce a compile-time boundary,
  or expose a distinctly named Ironwood helper. Documentation alone does not
  establish that boundary. See D113 and the
  [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review).
- Omit JVM-only interfaces such as `Serializable`, the `Cloneable` marker and
  `Object.clone()` machinery, runtime constants, security-manager checks, and
  reflection hooks unless Ironwood independently assigns them useful native
  semantics. Add array or collection copy operations only for concrete library
  needs, with explicit ownership contracts; do not rebuild a universal cloning
  protocol.
- Collections are an intentional exception to Java API mirroring. Public
  Ironwood code uses `ironwood.ds`; do not add a parallel `Collection` or `List`
  facade, or duplicate existing Ironwood collection types under Java package
  names, merely for familiarity. Keep the existing `ironwood.lang.Iterable` and
  `ironwood.util.Iterator` iteration contracts.
- When ported library code depends on Java collections, adapt its internals and,
  where necessary, its Ironwood-facing signature to the closest suitable
  `ironwood.ds` type. Add or extend a data structure only for a demonstrated
  semantic requirement, and document deliberate API differences.

The public API reference is the [Java SE 21 API](https://docs.oracle.com/en/java/javase/21/docs/api/).
Java 21 is a compatibility target for names and documented behavior, not a
promise that every Java member will exist.

### Exceptions and failure contracts

Preserve Java's fundamental exception hierarchy and checkedness rules across
future library work. Ironwood already provides `Throwable`, `Error`,
`Exception`, and `RuntimeException` in the same root arrangement as Java, plus
representative runtime exceptions and `OutOfMemoryError`. A `Throwable` subtype
is checked unless it derives from `RuntimeException` or `Error`; `throw`, typed
`catch`, method/interface/constructor `throws` declarations, catch-or-declare
analysis, generic throws substitution, and checked-exception override rules are
implemented under
[Features 57 and 58](IRONWOOD_VS_JAVA.md#feature-57).

New Java-shaped library failures should use their familiar Java simple name,
corresponding `ironwood.*` package, relative superclass, and checked/unchecked
category wherever Ironwood provides the corresponding API. In particular:

- recoverable failures that callers are expected to handle, such as I/O
  failures, should derive from `Exception` and appear in API `throws` clauses;
- programmer errors and ordinary operation failures should derive from
  `RuntimeException` when the matching Java contract does; and
- serious compiler/runtime failures should derive from `Error`, rather than
  turning recoverable library failures into unchecked errors.

The current concrete hierarchy is a deliberate native subset, not an exact copy
of every Java intermediate type. Java's `VirtualMachineError` is a JVM-failure
category between `Error` and `OutOfMemoryError`; Ironwood has no virtual machine,
so `OutOfMemoryError` derives directly from `Error`. Do not add the JVM-specific
intermediate class solely for taxonomic symmetry. Revisit it only for a concrete
source-compatibility requirement in which code must name or catch
`VirtualMachineError`. Other exception families should still preserve Java's
relative superclass when the underlying concept maps to Ironwood.

This compatibility direction does not imply that every JDK exception class or
every Java `Throwable` member must be added eagerly. The present `Throwable`
surface provides messages, constructor-supplied causes, Java-shaped descriptions
through `toString()` and `getLocalizedMessage()` (D114), compiler/runtime-private
source traces captured at construction, public `printStackTrace` and
`fillInStackTrace` (D121), and allocation-free indexed access to Ironwood's
ordered secondary failures. D122 adds single-assignment mutable causes and
fresh caller-owned public stack-trace arrays backed by immutable compiler-owned
elements. Java suppression APIs, mutable stack-trace replacement, PrintWriter
destinations, serialization, and finalization remain absent; any future surface
must preserve explicit ownership and avoid hidden unowned allocations. D121
supersedes D054's first-throw rule and D122 extends the
[public trace contract](STDLIB_STACK_TRACE_REVIEW.md). Add concrete exception subclasses with the library operations that
need them and document any deliberate divergence.

### Implementation choice

Each source file must be classified before implementation as original,
independently compatible, or OpenJDK-derived, following
[`LICENSE_MECHANICS`](LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

The standard-library program is expected to include OpenJDK-derived
translations. Translation is selected file by file, however, rather than used
as a blanket rule for an entire Java package. Before implementation begins for
a type, record which public surface is supported and classify each substantial
mechanism as a portable translation, independent compatibility work, or an
original Ironwood native/compiler boundary.

Use these defaults:

- independently implement public facades, small loops, argument validation,
  simple string operations, path wrappers, exception types, data-structure
  adaptations, and Ironwood-specific native integration;
- use an OpenJDK-derived port for a substantial portable algorithm when
  translation materially improves Java compatibility or reduces correctness
  risk;
- adapt collection-dependent internals to `ironwood.ds` rather than translating
  the Java Collections Framework as a compatibility layer; and
- never translate a source merely because it is long. Complexity does not
  remove the need to verify the exact upstream Classpath Exception, retain its
  complete header, use the derived-file SPDX classification, record immutable
  provenance, distribute corresponding source, and keep license notices.

Likely candidates for a later derived-source review include floating-point
parsing/formatting, Unicode case mapping, sophisticated sorting, and date/time
algorithms. These are candidates for review, not
pre-approved ports; each exact upstream file must pass the licensing gate
before its implementation body is inspected or translated.

Native filesystem, clock, terminal, and process integration remains original
Ironwood runtime/compiler work. OpenJDK's JVM bootstrap and native registration
machinery should not be ported.

### Explicit reclamation gate for library code

OpenJDK implementation code assumes garbage collection. A translation is not
correct merely because it returns the same values: it must be redesigned where
necessary so every ordinary Ironwood allocation has a visible, testable
lifetime. No standard-library slice may ship with an unbounded allocation that
is unreachable but unreclaimed.

Apply these rules to independent and translated code alike:

- classify every object, array, String, iterator, node, buffer, cache entry, and
  native wrapper as caller-owned, type-owned, borrowed/reused, or immortal;
- make each ordinary allocation-producing result retain compiler-visible
  allocation identity across the call so its caller can eventually use
  `free`; if the current compiler cannot prove that reclamation, the method is
  blocked rather than released with a permanent leak;
- give a type that owns ordinary child allocations an explicit, source-visible
  way to detach and reclaim them before its wrapper is freed, unless the
  storage belongs to a documented immortal singleton or a strictly bounded
  runtime-private process mechanism;
- treat `close()` as release of an external resource, never as implicit
  reclamation of the wrapper or its Ironwood fields; close and `free` remain
  separate operations;
- keep borrowed iterators and views allocation-free or owner-retained, and do
  not manufacture fresh helper objects whose ownership disappears behind an
  interface return;
- redesign or omit finalizers, cleaners, weak/soft-reference behavior,
  unbounded caches, GC-sensitive heuristics, and temporary-object patterns
  whose only cleanup in OpenJDK is reachability;
- reclaim partially built internal state on every normal and exceptional path
  where ownership has not escaped, while preserving the primary exception and
  ordered secondary cleanup failures; and
- prove the contract with safe-`free` compile tests, bounded stress tests,
  steady-state hidden-allocation checks where applicable, and native-resource
  leak checks. `System.allocationCount()` detects cumulative allocations;
  `System.liveAllocationCount()` is the current-live gauge for destruction and
  failed-construction rollback.

This gate applies especially to APIs such as `substring`, `toCharArray`,
primitive formatting, `Files.readAllBytes`, `Files.readString`, path-producing
operations, `BufferedReader.readLine`, and in-memory output streams. Their
allocating results cannot be considered complete until the caller has a proven
reclamation path. This is a standard-library/compiler readiness requirement,
not new ownership syntax for application code. The existing proof and runtime
rules in [`MEMORY.md`](MEMORY.md) remain authoritative.

## Deterministic-destruction checkpoint (complete)

The language/runtime foundation now supplies one allocation-free,
non-escaping `destructor { ... }` per class, dynamic derived-to-root execution
for an accepted object `free`, and compiler-generated rollback for failed
construction. Rollback destroys completed proven-owned children in reverse
layout order, does not run the incomplete receiver's source destructor, and
preserves the original exception. Closed-world effects reject destructor
allocation, outward throws, resurrection, and in-progress constructor
publication.

The existing standard library has been audited against that model.
`StringBuilder`, owned `ByteBuffer` storage, array-backed collections and maps,
and applicable object pools reclaim their proven-owned terminal containers.
Borrowed buffers and collection user elements remain non-owned. Reusable
iterators belong to their containers. Under D104 pools own builder-created values, including
checked-out values, and active high-water storage. `get()` borrows; `release()`
returns a checkout. External objects are unsupported and not destroyed.
`takeRetained()` is removed.
Collection rendering reclaims its temporary builder on both successful and
exceptional exits through ownership-proven `finally` cleanup.
Object append/output consumers conditionally reclaim a rendered String only
when closed-world analysis proves the concrete `toString()` override always
returns fresh, unescaped text; alias-returning overrides remain borrowed.

The second allocation-audit repair stage removes numeric/character conversion
arrays and the full-builder intermediate snapshot in `StringBuilder.subSequence`.
Each successful operation now allocates only its required immutable String;
range errors are checked before copying and allocation failure strands no
helper storage. The later numeric-field helpers retain bounded integer and
floating rendering under the explicit `formatDecimal` and `formatFixed` names
(D113). Java `String.format` and broad `Formatter` compatibility remain deferred.

The third and final stage of this audit repairs filesystem/path scratch use:
built-in path transformations allocate only their owned result, whole-file
reads fill their result directly, and immutable String writes avoid snapshots.
Native path buffers use bounded stack storage with a long-path fallback.
Arbitrary `CharSequence` writes retain one exception-safe snapshot to preserve
single-observation and validation-before-truncation behavior. This completes
the three repair stages without claiming that all library operations allocate
nothing or that floating wrapper-class `toString` methods are implemented. U3
was delivered separately after this audit.

## Current baseline and important gaps

Ironwood already has native arguments, UTF-16 String construction/search,
integer parsing, `StringBuilder`, arrays, exceptions, stdout/stderr primitive
output, environment lookup, clocks/math, a heap `ByteBuffer`,
generic/primitive data structures, and explicit `try`/`catch`/`finally`.

The main usability gaps are:

- D117 implements common String operations and fixed en_US Unicode casing;
  configurable locales and regex remain excluded, while Unicode digit parsing
  and general formatting remain gaps; explicit decimal and bounded fixed-point
  numeric fields are available for application code;
- source String `+`/`+=` is implemented by D061 as the language/compiler-owned
  Feature 76 rather than a library port;
- fixed stdout/stderr streams are available, while `System.in`, stream
  construction/replacement, and general stream hierarchies are not;
- U2 provides lexical paths and focused whole-file I/O, while directory
  traversal/mutation, encoders, readers, writers, and streams remain absent;
- explicit deterministic cleanup in ordinary `finally` and `AutoCloseable` are
  available, while `free` deliberately does not close operating-system
  resources;
- `ironwood.ds` is the selected collection surface and may need focused
  additions as real programs exercise it;
- S0/U1/U2 establish compiler-visible fresh-result ownership for representative
  text, conversion, and native-service calls; every future allocating API still
  must opt into and verify that contract under the explicit reclamation gate;
- checked exceptions, `throws` declarations, and first-exception-preserving
  `finally` are available; Java try-with-resources is deliberately rejected,
  closed-world enums are available, and lambdas and Java Streams are
  deliberately excluded.

The roadmap below uses current capabilities where possible and identifies the
language work that should not be papered over with incompatible APIs.

## Immediate tranche A: text, conversion, and diagnostics

**Status: Completed under D086.** The source, API reductions, ownership, and
native boundaries are recorded in
[`STDLIB_U1_SOURCE_REVIEW.md`](STDLIB_U1_SOURCE_REVIEW.md).

### `ironwood.lang.String`

S0 implemented caller-reclaimable `substring(int)`,
`substring(int, int)`, and `toCharArray()`. U1 adds the remaining Java-shaped
subset most command-line tools need:

- `String(String original)` as an explicit copy constructor that rejects null,
  creates one distinct ordinary heap object with equal UTF-16 content, and
  participates in compiler-checked `free` through its visible `new` allocation;
- constructors from `char[]` and a checked `char[]` range;
- `isEmpty()`;
- `contains(CharSequence)`;
- `indexOf(int)`, `indexOf(int, int)`, `indexOf(String)`, and
  `indexOf(String, int)`;
- `lastIndexOf(int)` and `lastIndexOf(String)`;
- `startsWith(String)` and `endsWith(String)`;
- `subSequence(int, int)`;
- `concat(String)`;
- `getChars(int, int, char[], int)`;
- `valueOf(Object)`, `valueOf(boolean)`, `valueOf(char)`, `valueOf(int)`, and
  `valueOf(long)`; and
- `compareTo(String)` through `Comparable<String>`.

D116 adds `valueOf(float)` and `valueOf(double)` with fresh caller-owned results,
plus the previously missing `StringBuilder.append(float)`. Both floating append
overloads preserve the argument type and reclaim their temporary conversion.
See the [T5 review](STDLIB_FLOATING_TEXT_REVIEW.md); general formatting and
floating wrapper-class `toString` methods remain separate future work.

These operations should retain Java's UTF-16 code-unit indexing. Do not make
`split(String)` a simple delimiter split: Java's method is regex-based. A
literal split helper should wait for an existing Java-shaped API that actually
has literal semantics. Regex-defined `String` methods are outside this roadmap.
The copy constructor does not replace Feature 75 literal pooling, Feature 76
concatenation, or the Feature 77 decision against runtime `String.intern()`.

D117 adds trim/strip/isBlank, full Unicode case conversion with fixed en_US
semantics, locale-independent equalsIgnoreCase, literal replace overloads,
UTF-8 byte conversion/construction, char-array valueOf, offset reverse searches
and startsWith, repeat, array join and fixed joins of zero through three elements.
See the [String review](STDLIB_STRING_REVIEW.md). Results are fresh even for
unchanged or empty input. `ironwood.util.Locale`, mutable default locales,
locale-taking overloads and regex methods are excluded, with no implementation
commitment. Iterable join, full expanding Unicode case folding, normalization,
and general formatting remain separate gaps. Do not silently substitute ASCII
for the selected Unicode behavior.

### Everyday StringBuilder edits

D118 adds whole/ranged `append(char[])`, all twelve Java-shaped `insert`
overloads for supported types, `delete`, `deleteCharAt`, `reverse`, `setCharAt`,
`replace`, whole/offset `indexOf`, both `substring` forms, and `isEmpty`.
`append(float)` was already completed by D116. These methods preserve UTF-16,
null, self-insertion and range behavior with direct buffer edits and fresh
substring results. See the [StringBuilder review](STDLIB_STRINGBUILDER_REVIEW.md).
Further builder members such as `getChars`, `lastIndexOf`, code-point methods,
`repeat` and `trimToSize` are not added by this slice.

### Primitive conversion helpers

U1 completes this static, non-boxing subset:

| Type | Immediate methods |
| --- | --- |
| `Boolean` | `parseBoolean(String)`, `toString(boolean)`, `hashCode(boolean)`, `compare(boolean, boolean)` |
| `Integer`, `Long` | Signed/unsigned parsing and comparison; decimal/radix/binary/octal/hex text; range/size constants and common bit operations. |
| `Byte`, `Short`, `Character` | Existing primitive helpers plus Unicode Character classification, case, numeric/radix and surrogate operations. |
| `Float`, `Double` | Parsing, shortest text, hashes, raw/canonical bits, finite predicates, comparisons, size and minimum-normal constants. |

Do not add automatic boxing merely to complete wrapper-class shape. Static
conversion methods are independently useful and compatible with Ironwood's
current primitive model.

The focused parsing follow-up is complete and classified in
[`STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`](STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md).
It targets Java SE 21 public behavior with independently authored differential
and optimization-level tests while keeping successful parsing free of managed
and Ironwood-owned native heap scratch. D122 completes the selected floating
wrapper text and bit helpers without adding boxed primitive objects.

### `ironwood.lang.Math`

U1 and D122 complete the selected Java-shaped arithmetic, exact/floor, clamp,
angle and transcendental helpers. Operations backed by LLVM/platform math have
focused edge tests but do not claim StrictMath cross-platform reproducibility.

### Output and system diagnostics

U1 adds:

- an immortal `public static final PrintStream System.err`;
- `PrintStream.print` and `println` overloads for `String`, `Object`, `char`,
  `boolean`, `int`, `long`, `float`, and `double` as their conversion support
  becomes exact;
- `PrintStream.flush()` and `checkError()` when buffered/native error state
  exists;
- `System.lineSeparator()`;
- `System.getenv(String)` for simple CLI configuration, without exposing a
  mutable process-environment map; and
- `System.currentTimeMillis()` and `nanoTime()` as small, separately tested
  native services when timing is needed.

Stream replacement, a security manager, `System.console()`, `System.gc()`,
finalization, JVM properties, native-library loading, inherited channels, and
caller-sensitive operations are not part of this tranche.

### Acceptance programs

- `examples/hello` remains the smallest output program.
- `examples/echo` covers arguments, primitive output overloads, and
  stderr usage diagnostics.
- `projects/minigrep` covers checked whole-file I/O, literal line search,
  optional case-insensitive configuration, and distinct native statuses.
- Independently authored differential String/conversion tests compare against
  documented Java 21 behavior.

## Immediate tranche B: paths and whole-file I/O (complete)

U2 completed this tranche and its literal `minigrep` acceptance application.

### Language prerequisite: checked I/O failures

The D049 checked-exception policy and `throws` syntax provide the language
foundation now used by checked `IOException` file operations, preserving the
Java source shape and compiler diagnostics that Java programmers expect. D051
provides explicit deterministic
`finally` cleanup with ordered secondary exceptions; streaming I/O can use that
language behavior directly in tranche C.

### I/O exception hierarchy

U2 added the Java-shaped types needed by its file operations:

- `ironwood.io.IOException`;
- `FileNotFoundException`;
- `EOFException`;
- `UncheckedIOException` remains deferred until an API genuinely needs to carry an I/O
  failure through a callback or iterator that cannot declare it; and
- `NoSuchFileException` is implemented; `FileAlreadyExistsException`,
  `DirectoryNotEmptyException`, and `NotDirectoryException` remain tied to
  later manipulation operations.

Each failure should preserve a useful message, the relevant path where
applicable, and the underlying platform error category. Do not expose raw
`errno` values as the primary public contract.

### `ironwood.nio.file.Path` and `Paths`

U2 preserves `Path` as an interface rather than publishing an incompatible concrete
class. Because Ironwood deliberately excludes varargs, it begins with
`Paths.get(String)` and `Path.of(String)` for the common single-component call;
use documented fixed-arity overloads or an explicit `String[]` for additional
components.

The first `Path` surface should include:

- `toString()`, `equals(Object)`, and `hashCode()`;
- `isAbsolute()`;
- `getFileName()`, `getParent()`, and `getRoot()`;
- `resolve(Path)`, `resolve(String)`, `resolveSibling(Path)`, and
  `resolveSibling(String)`;
- `normalize()`;
- `toAbsolutePath()`; and
- name-count/name-element operations when their iterator contract is ready.

Paths are lexical values. Constructing or normalizing one must not perform
filesystem I/O. Platform separators and roots belong behind the path provider,
not in application code.

### `ironwood.nio.file.Files`

This API lands in two increments.

The first, sufficient for `minigrep`, is complete:

- `readAllBytes(Path)`;
- `readString(Path)` with Java 21's UTF-8 default;
- `write(Path, byte[])`;
- `writeString(Path, CharSequence)` with a UTF-8 default;
- `exists(Path)`, `isRegularFile(Path)`, `isDirectory(Path)`, and `size(Path)`.

The second completes basic file manipulation:

- `createFile`, `createDirectory`, and `createDirectories`;
- `delete` and `deleteIfExists`;
- `copy` and `move` with a deliberately selected initial option subset;
- `getLastModifiedTime` and basic readable/writable checks; and
- temporary files/directories once secure randomness and permissions have a
  documented native design.

Do not invent booleans to replace Java option enums. `StandardOpenOption`,
`StandardCopyOption`, and `LinkOption` should follow enum support. Until then,
publish only overloads whose Java default behavior is unambiguous.

The older `ironwood.io.File` facade is not the initial path model. Modern code
should start with `Path` and `Files`; `File` can be added later as a compatible
legacy facade when real porting demand justifies it.

### Encoding

Whole-file text begins with UTF-8, matching Java 21's `Files.readString(Path)`
and `writeString(Path, CharSequence)` defaults. Then add:

- `ironwood.nio.charset.Charset` as a closed set of supported encodings rather
  than a dynamically loaded provider system;
- immortal `StandardCharsets.UTF_8`, `US_ASCII`, `ISO_8859_1`, `UTF_16`,
  `UTF_16BE`, and `UTF_16LE` singletons as support becomes complete; and
- `String(byte[], Charset)` and `String.getBytes(Charset)`; and
- charset overloads on file and stream APIs.

Dynamic charset providers, service loading, and JVM default-charset discovery
are excluded. Ironwood's native text APIs use a fixed UTF-8 default, including
`ByteArrayOutputStream.toString()` under D115. Java's configurable alternate
default encodings and explicit charset overloads are not implemented.

### `minigrep` acceptance gate

The completed `projects/minigrep` has behavior equivalent in scope to Rust's
teaching project, not GNU grep:

```text
minigrep <literal-query> <path>
```

The first version:

- use `String[] args`, `Paths.get`, and `Files.readString`;
- use the presence of `IGNORE_CASE` for the teaching project's optional ASCII
  case-insensitive mode;
- search literal UTF-16 text without regex semantics;
- emit matching lines to `System.out`;
- emit usage and I/O diagnostics to `System.err`;
- return distinct success, no-match, usage-error, and I/O-error statuses;
- handle LF, CRLF, and a final line without a terminator;
- preserve supplementary Unicode and deterministic malformed UTF-8 behavior;
- include positive, empty-file, large-file, missing-file, permission, and
  Unicode tests; and
- run from source, loose `.ironclass`, `.ironjar`, host package, and IDK at
  `-O0` through `-O3` where the existing test architecture supports it.

Literal search is intentional. Java regex compatibility is outside this
roadmap.

## Immediate tranche C: streaming I/O and deterministic resources

Whole-file helpers make Ironwood useful quickly, but streaming is required for
large inputs, pipelines, and long-running programs.

### Language prerequisites

The implemented prerequisites are:

1. the checked `IOException` type and contracts for whole-file operations, using
   the implemented D049 `throws` foundation;
2. the implemented ordinary `ironwood.lang.AutoCloseable` interface and the
   implemented `ironwood.io.Closeable` refinement;
3. the implemented D051 rule that source-written `finally` cleanup preserves
   the first exception and records later cleanup failures in occurrence order;
4. the implemented compiler safe-free distinction between closing a resource
   and freeing the Ironwood object that represents it.

Do not model `free` as `close`. Closing releases an external resource; freeing
reclaims an allocation after alias proof. Applications may need to close an
object and later free it as two separate operations.

### Byte streams

U3 implements the Java-shaped core:

- `InputStream`: `read()`, `read(byte[])`, `read(byte[], int, int)`, `skip`,
  `available`, and `close`;
- `OutputStream`: `write(int)`, `write(byte[])`, `write(byte[], int, int)`,
  `flush`, and `close`;
- `FileInputStream` and `FileOutputStream`;
- `BufferedInputStream` and `BufferedOutputStream`;
- `ByteArrayInputStream` and `ByteArrayOutputStream` for reusable in-memory
  composition and independent tests; BAOS includes fresh UTF-8 `toString()`
  snapshots with replacement of malformed sequences (D115); and
- `Files.newInputStream` and `Files.newOutputStream`.

Pushback, sequence streams and piped streams remain deferred. D122 adds the
selected mark/reset, bulk transfer, data stream and random-access surface.

### Character streams

Implemented for U3 and D122:

- `Reader` and `Writer`;
- `InputStreamReader` and `OutputStreamWriter`;
- `BufferedReader.readLine()` and core buffered reads;
- `BufferedWriter.write`, `newLine`, `flush`, and `close`;
- `StringReader` and `StringWriter`, including the live StringBuffer view;
- `Files.newBufferedReader` and `Files.newBufferedWriter`; and
- `FileReader`, `FileWriter`, and `PrintWriter` with the documented UTF-8 and
  error-state behavior.

U3 adds the immortal `public static final InputStream System.in`. `System.out` and
`System.err` remain `PrintStream` objects. Closing a standard stream is a no-op for input and a flush for output;
none is disabled. Wrapper close still closes that wrapper.

### Streaming acceptance programs

- `cat`: copy a file or stdin to stdout without loading the complete input;
- `wc`: count bytes, UTF-16 characters, words, and lines incrementally;
- `cp`: copy arbitrary binary data, including zero bytes and invalid UTF-8;
- [`minitee`](../projects/minitee/README.md): duplicate stdin to stdout and one
  file, with append mode, one reusable buffer, and a custom borrowed-output tee;
- an interactive input example, with a guessing game becoming practical once
  integer parsing and a random source exist; and
- resource tests that repeatedly open and close files without descriptor growth
  or retained native buffers.

U3's API/provenance and ownership choices are recorded in
[`STDLIB_U3_SOURCE_REVIEW.md`](STDLIB_U3_SOURCE_REVIEW.md), D094, and D095.
`Files.isSameFile` is included specifically to keep cp from truncating its own
source through a hard-link/symlink alias. No U4/U5 or networking scope is added.
Host validation includes O0-O3 behavior and Java 21 comparisons, malformed UTF-8
across one-byte and 8 KiB boundaries, zero steady-state helper allocations,
constructor/result OOM, low-descriptor-limit stress, native short/interrupted
I/O and close failures, class/archive reconstruction, and package/IDK smoke.
Cross-platform release verification remains a separate release matrix.

## Collection policy: use and extend `ironwood.ds`

Ironwood will not reproduce the Java Collections Framework for this roadmap.
The existing `ironwood.ds` lists, maps, sets, primitive-specialized structures,
buffer-key structures, and reusable iterators are the standard collection
surface. `ironwood.lang.Iterable` and `ironwood.util.Iterator` remain the
Java-shaped traversal contracts.

When a useful program or a ported standard-library class needs missing
functionality:

- first select an existing `ironwood.ds` type whose ownership and reuse
  semantics fit;
- add operations to that type when they preserve its established contract;
- add a new `ironwood.ds` type when the required data structure or semantics
  are genuinely different;
- preserve primitive-specialized and allocation-conscious variants where they
  benefit Ironwood's native systems goals; and
- adapt OpenJDK-oriented implementation code to this surface rather than
  creating a parallel Java collection hierarchy solely to make a port compile.

Any expansion still requires explicit null, ownership, iterator, mutation, and
safe-`free` semantics. Removing an element or freeing a container must not
silently free user-owned elements.

D128 adds direct value containment to every public list and map variant that
lacked it. The scan implementations add no allocation and do not reset reusable
iterators; user-defined equality can still perform arbitrary work. U4's
CSV/record-summary acceptance program is superseded by this core-query pass and
continuous application-driven collection development; no CSV-specific or broad
Java Collections surface is introduced.

D129 completes the bounded U4 follow-up with indexed replacement plus
forward/reverse value lookup on the generic, `int`, and `long` array-list
families. The read-only generic view delegates the lookup queries and rejects
replacement. Initial-capacity and growth-factor constructors, map load-factor
constructors, `System.arraycopy`, and `Arrays.copyOf` remain the selected
capacity and bulk-copy foundation. `ensureCapacity`, `trimToSize`, `addAll`,
`toArray`, and collection copy constructors remain demand-driven rather than
release requirements.

Java `Scanner` is also outside this roadmap because its contract depends heavily
on regular expressions and locale-aware numeric parsing. `BufferedReader` is
the initial line-input API.

## Next tranche: directories and file metadata

Stage 1 is complete under D130:

- `DirectoryStream<Path>` and `Files.newDirectoryStream` provide deterministic,
  strict-UTF-8 entry enumeration without Java Streams or lambdas; and
- `BasicFileAttributes`, `FileTime`, and `Files.readAttributes` provide the
  common file kind, size, and millisecond timestamp fields.

Stage 2 is complete under D131:

- `FileVisitResult`, `FileVisitor<Path>`, and `SimpleFileVisitor<Path>` provide
  Java-shaped traversal callbacks and control;
- `Files.walkFileTree` provides no-follow default traversal plus fixed-arity
  maximum-depth and follow-link selection, with cycle, error, and cleanup tests;
  and
- [`examples/filetree`](../examples/filetree) demonstrates find-style suffix
  discovery with borrowed callback values.

`Files.list`, `Files.walk`, and `Files.lines` return Java Streams and should wait
for an intentional Ironwood Streams/lambda design. Directory traversal must not
invent eager array-returning replacements under those Java names.

## Later useful-library tranches

These matter, but they should not delay the first file-capable release:

1. **Time:** D120 completes a focused `ironwood.time.Instant` for epoch
   conversion, comparisons and full-range ISO parsing/output, plus required
   failures. `now()` uses the millisecond wall clock. See the
   [Instant review](STDLIB_INSTANT_REVIEW.md). Duration, arithmetic, LocalDate,
   LocalTime, LocalDateTime, ZoneOffset, general date patterns and named
   timezones remain deferred. Configurable locales remain excluded.
   Monotonic elapsed time remains `System.nanoTime()`.
2. **Formatting:** complete the public primitive conversion helpers, build on
   Feature 76 concatenation, then add a carefully scoped `Formatter`/`printf`
   API using the accepted fixed en_US convention. Configurable locales are
   excluded. Java's global runtime `String.intern()`
   is excluded; a future application-owned bounded interner may be considered
   as an ordinary collection.
3. **Randomness:** D122 supplies Java-shaped seeded `Random` for deterministic
   pseudorandom tests. A separate secure source waits for native entropy and
   cryptographic contracts.
4. **Processes:** a reduced `ProcessBuilder`/`Process` design after pipes,
   environment, cleanup, and native handle ownership are stable.
5. **Compression and checksums:** CRC and ZIP/GZIP APIs after streaming I/O;
   they are useful for tooling and distribution but are not prerequisites for
   basic file utilities.
6. **Networking, pending for the near future:** sockets, DNS, and HTTP after
   streaming, deterministic resources, error mapping, a single-threaded
   event-loop design, and cross-platform release validation are mature.

## Native/runtime architecture for I/O

The file library must preserve Ironwood's existing architecture:

```text
Java-shaped Ironwood facade
    -> portable validation/buffering/decoding in Ironwood source
    -> explicit compiler-owned typed I/O operation or private stdlib intrinsic
    -> narrow original Ironwood runtime ABI
    -> macOS/Linux system calls and C library
```

The runtime boundary should expose only operations that are genuinely native,
such as opening, reading, writing, seeking where supported, closing, statting,
directory iteration, environment lookup, and clocks. Path composition,
buffering, line splitting, copying loops, validation, and most encoding logic
belong in Ironwood source when the language can express them.

Requirements:

- no public file-descriptor integers, native pointers, `FILE *`, or host structs;
- explicit typed IR for native operations and exceptional/control-flow edges;
- reliable partial-read/partial-write and interrupted-call handling;
- platform error translation into stable Ironwood exception categories while
  retaining useful diagnostic text;
- no implicit resource release by `free` and no finalizer fallback;
- double-close behavior and post-close failures specified and tested;
- I/O-created arrays, strings, paths, stream objects, and buffers represented
  with compiler-visible allocation provenance so safe `free` can reason about
  them rather than treating every result as permanently unknown;
- standard streams represented as immortal process-owned objects distinct from
  ordinary closeable file streams;
- PIC/PIE-safe native objects and no JVM, JNI, native registration, or dynamic
  class loading; and
- macOS ARM64, Linux ARM64, and Linux x86-64 behavior covered before a release
  is described as cross-platform.

A private standard-library native bridge is acceptable. A general public FFI is
not required for this roadmap and should not be smuggled in as part of file I/O.

## Completion rules for every library slice

A class is not complete merely because its source compiles. Each slice must:

1. record its API baseline, implementation category, unsupported Java members,
   and native/JVM substitutions before implementation;
2. carry the correct file-level license and immutable provenance when derived;
3. use independently written behavioral tests unless copied/translated tests
   receive their own licensing review;
4. cover success, boundary, malformed-input, exception, resource, alias, and
   safe-free behavior;
5. prove a reclamation path for every ordinary allocation, including returned
   results, owned children, temporary storage, and partial construction on
   exceptional paths;
6. expose semantic operations in compiler-owned typed IR before LLVM lowering
   whenever compiler/runtime integration is required;
7. execute natively at `-O0` through `-O3` on the supported host;
8. verify tree shaking, source-path/classpath/archive reconstruction, and
   separate compile/link behavior;
9. ship corresponding source, licenses, and provenance in host packages and
   IDKs;
10. pass `./scripts/check-licenses.sh` and the full compiler suite; and
11. add or extend an end-to-end example that would be useful outside a unit
    test.

## Standard-library milestone tracker

Update this table in the same change that completes or materially reschedules a
milestone. “Pending for the near future” is a committed later milestone, not an
exclusion.

| Gate | Status | Required end-to-end result | Main library surface |
| --- | --- | --- | --- |
| L0: language fundamentals | Complete | representative object-model, generic, exception, resource, array, statement, and switch programs | completed language foundations; varargs and other deliberate exclusions remain excluded |
| S0: porting and reclamation foundation | Complete | independently reviewed String slice with packaged provenance and caller-reclaimable `substring`, `toCharArray`, builder snapshots, and default object identity text | independent/native boundaries, allocation-result provenance, deterministic owned-child teardown and rollback, safe-`free`, live-count, artifact, native, and package tests |
| U1: text-capable CLI | Complete | `echo` with stdout/stderr, checked integer parsing, and primitive conversion | String construction/search/value conversion, primitive helpers including the completed floating parser follow-up, common Math, `PrintStream` overloads, `System.err`, environment, and clocks |
| U2: file-capable CLI | Complete | literal `minigrep` over UTF-8 files | `IOException`, `Path`/`Paths`, `Files` whole-file operations |
| U3: streaming CLI | Complete | `projects/streaming`: binary cat/cp, incremental wc, and interactive prompt; `projects/minitee`: stdin-to-stdout/file tee with append and deterministic close/reclamation | byte and character hierarchies, owned/borrowed wrappers, UTF-8, immortal System.in, Files factories and same-file guard |
| U4: data processing | Superseded | no standalone application gate; collection gaps are handled continuously | D128 value-containment queries and D129 array-list replacement/index lookup, plus future additions only when real consumers expose a gap |
| U5: filesystem tooling | Complete | recursive `find`-style program | D130 directory stream and basic attributes; D131 controlled visitor traversal and file-tree example |
| N1: networking foundation | Pending for the near future | single-threaded TCP/DNS acceptance programs, followed by a deliberately scoped HTTP client | Java-shaped socket/address APIs, deterministic close plus wrapper reclamation, native error mapping, event-loop integration, cross-platform tests |

L0, S0, U1, U2, and U3 are complete. S0 proved that Java-compatible allocation results can
remain caller-reclaimable across source, class, archive, and link boundaries;
U1 applies that proof to the text-capable CLI surface, and U2 extends it across
paths, native whole-file operations, and the first useful file program. The
floating-point parsing follow-up is complete; U3 delivers the streaming file-I/O
baseline. U4 is superseded by continuous application-driven collection work,
and U5 now completes recursive filesystem tooling. Completing U5 does not select
or authorize the next implementation target. N1 is planned near-future work after
those ownership and streaming foundations; networking is not deliberately
excluded.

## Explicitly not immediate

Networking is tracked separately as N1 with status **Pending for the near
future**; it is not part of the exclusion list below.

- reflection, `Class` metadata APIs, dynamic proxies, class loaders, method
  handles, and runtime-generated code;
- Java object serialization and GC/reference-queue APIs;
- security-manager and access-controller machinery;
- the Java Collections Framework and parallel `ironwood.util` collection
  facades; `ironwood.ds` is the selected collection API;
- regular expressions, including `Pattern`, `Matcher`, regex-defined `String`
  methods, and `Scanner`;
- general annotations and annotation processing; mandatory `@Override` and
  testing-only `@Test` remain narrow built-in compiler directives rather than
  annotations;
- Java Streams, collectors, lambdas, closures, method references, and parallel
  operations;
- threads, thread-local storage, concurrent collections, executors, atomics,
  object monitors, and synchronization;
- `ironwood.util.Locale`, configurable locale behavior, and complete locale,
  currency, calendar, and time-zone databases;
- arbitrary charset/service providers;
- desktop, graphics, sound, database, XML, compiler, and JVM-management APIs;
  and
- compatibility layers whose only purpose is running existing JVM applications.

The standard library can be large because final linking is closed-world and
tree-shaken. The implementation order should nevertheless remain driven by
complete useful programs, portability, deterministic resources, and clear
semantics rather than by the number of Java classes copied.

## Reference surfaces

- [Java SE 21 `String`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html)
- [Java SE 21 `System`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html)
- [Java SE 21 `Math`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html)
- [Java SE 21 `java.io`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/package-summary.html)
- [Java SE 21 `PrintStream`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/PrintStream.html)
- [Java SE 21 `Path`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html)
- [Java SE 21 `Files`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html)
- [The Rust Programming Language: command-line I/O project](https://doc.rust-lang.org/book/ch12-00-an-io-project.html)

These references define compatibility targets and project inspiration. Any use
of implementation source remains governed separately by Ironwood's mandatory
licensing and provenance workflow.
