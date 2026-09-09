# Ironwood language specification and support status

> **Ironwood's goal is to stay as close to Java as practical while removing the
> JVM, garbage collection, and dynamic runtime machinery that conflict with
> closed-world native compilation. Think of it as Java designed to replace C++
> instead of to run on a virtual machine.**

Ironwood is a new language, not a Java implementation or a JVM. Familiar Java
source structure and semantics are the default whenever they are compatible with
ahead-of-time native compilation, deterministic memory management, and a small
runtime. Where those goals conflict, Ironwood deliberately chooses its native
systems model.

This document is the consolidated feature-status specification for the current
`0.2.6-beta` source tree. It answers three questions:

1. What can an Ironwood program use today?
2. What remains planned or open for later design?
3. What is permanently outside Ironwood's intended language and runtime model?

`Supported` means implemented and covered by the compiler test suite. `Deferred`
means compatible with the project's direction but not implemented. `Open` means
that semantics must be decided before implementation and should not be inferred
from bootstrap behavior. The detailed grammar and semantic rules remain in
[`LANGUAGE.md`](LANGUAGE.md), while milestone history is recorded in
[`ROADMAP.md`](ROADMAP.md) and accepted design decisions in
[`DECISIONS.md`](DECISIONS.md). The completed object and reference-generic
models are detailed in [`OBJECT_MODEL.md`](OBJECT_MODEL.md) and
[`GENERICS.md`](GENERICS.md). The feature-oriented Java comparison is
[`IRONWOOD_VS_JAVA.md`](IRONWOOD_VS_JAVA.md).

## Current language model

### Native, closed-world execution

| Capability | Current support |
| --- | --- |
| Source form | UTF-8 `.iron` files compiled by the Java 21 bootstrap compiler. |
| Compilation | Closed-world ahead-of-time compilation through compiler-owned typed IR and LLVM 23. |
| Program output | Native Mach-O executables on macOS and ELF executables on Linux; generated programs do not require a JVM. |
| Optimization | `-O0`, `-O1`, `-O2`, and `-O3`, with `-O0` as the default. |
| Class artifacts | One package-structured `.ironclass` per top-level type. Member/local/anonymous types are reconstructed from that owner payload; the bootstrap format embeds validated source and is not yet a stable ABI. |
| Archives | Deterministic `.ironjar` compile-time archives. They do not enable runtime class loading. |
| Native linking | Explicit `--link` mode consumes compiled classes, selects `--main-class`, tree-shakes unused library classes, and emits an executable. |
| Entry point | `public static int main(String[] args)` or `public static void main(String[] args)` on the selected main class; a no-argument `main()` is not accepted. |

The complete reachable program is known at native link time. Ironwood uses that
knowledge for hierarchy validation, type membership, dispatch tables,
class-granular standard-library inclusion, exact-target devirtualization, call
escape summaries, and safe-`free` analysis.

The entry parameter is a non-null `String[]` containing the command-line
arguments after the executable name. Each non-null element is decoded from
native UTF-8 into Ironwood's immutable UTF-16 `String` representation, with
U+FFFD replacement for each invalid byte. The entry method's `int` return is the
native exit status; normal completion of a `void` entry returns status 0. The
startup array and strings are ordinary allocations retained until process
termination.

### Source organization and names

Supported today:

- Java-shaped `package` declarations, qualified type names, single-type and
  wildcard imports, plus single-member and on-demand static imports for
  accessible static fields, methods, and member types.
- Implicit visibility of `ironwood.lang`, analogous to Java's implicit
  `java.lang` import.
- Public and package-private top-level classes and interfaces. A compilation
  unit may contain multiple top-level types, but at most one may be public and a
  public type must match its `.iron` filename.
- Multiple compiler inputs plus transitive dependency discovery through
  `-sourcepath`; package-root classpaths through `-cp`.
- Java-like `public`, `protected`, package-private, and `private` member access,
  including the protected receiver rule across packages.
- Java-shaped member type lookup and access for static nested classes, member
  inner classes, and nested interfaces, plus block-scoped local and anonymous
  classes.
- The conventional project source root `src/main/ironwood`.

Java module declarations, module imports, and JPMS readability/export rules are
not part of the current source or distribution model.

### Classes, interfaces, objects, and references

Supported today:

- `ironwood.lang.Object` is the unique root of the object graph. Every class
  implicitly or explicitly extends it, and every class, interface, and array
  reference widens to `Object`.
- Java-like nullable references. Assignment, arguments, and returns copy a
  reference rather than an object.
- Single class inheritance, multiple interface implementation, and multiple
  interface extension.
- Concrete and abstract classes, abstract methods, final classes/methods, and
  complete transitive abstract/interface obligation checking.
- Instance and static fields; instance and static methods; constructors;
  implicit default constructors; `this(...)` delegation; explicit or implicit
  superclass construction.
- One unmodified `destructor { ... }` per class, with derived-to-root execution,
  allocation-free/non-escaping effect checks, and separate failed-construction
  rollback that destroys completed owned children but never runs the incomplete
  receiver's source destructor.
- Method and constructor overloading, inherited member lookup, overriding,
  covariant reference returns, and visibility checks.
- The exact built-in `@Override` directive is mandatory on every declared
  inherited instance override or interface implementation and is rejected on a
  method with no such target. It is method-only and has no annotation grammar,
  arguments, metadata, processing, or reflection.
- The exact built-in `@Test` directive marks eligible methods in concrete
  `ironwood.testing.TestSuite` subclasses. The compiler generates deterministic
  source-order dispatch and a native test entry point. It adds no annotation
  metadata, processing, runtime discovery, or reflection, and `Test` remains a
  legal identifier outside the directive.
- Instance field/initializer-block execution in textual order, path-sensitive
  blank-final assignment, and statically selected Java-shaped field hiding.
- `this`, `this(...)`, `Outer.this`, `super(...)`, `super.field`, direct
  `super.method(...)`, and permitted `Interface.super.method(...)` forms.
- Static nested classes, member inner classes, nested interfaces, local classes,
  and anonymous classes, including exact enclosing instances,
  explicit-final/effectively-final local and parameter capture, and same-nest
  private access.
- Interface constants and abstract, default, static, private-instance, and
  private-static methods with class-wins and most-specific-default resolution.
- Top-level and implicitly static member enums as final nominal reference types
  with constructor-backed immortal constants, interface implementation,
  constant-specific compiler-owned final subtypes with per-constant concrete
  obligations, fields, instance initialization, methods, and member types,
  `name()`/`ordinal()`/default `toString()`, allocation-free
  `valueCount()`/`valueAt(int)`/`valueOf(String)`, a fresh caller-owned array
  from `values()`, the shared `Enum<E>` base, and ordinary identity, arrays,
  casts, `instanceof`, and reference-generic participation.
- Direct, virtual, and interface calls. Closed-world analysis devirtualizes a
  call when all possible concrete receivers resolve to one target.
- Reference widening, identity equality, `null`, nominal `instanceof`, checked
  reifiable casts, source-provable concrete parameterized casts whose only
  runtime question is raw nominal membership, and exact reifiable array
  casts/tests at every recursive dimension.
- Java-shaped root methods `Object.equals(Object)`, `hashCode()`, and
  `toString()`, with ordinary virtual override behavior.
- Predictable null-receiver failure rather than unchecked native memory access.

Runtime class initialization is deterministic and closed-world: named classes
accept `static { ... }`, runtime-valued static field initializers execute in
textual order, and entry, construction, static-call, and nonconstant
static-field active uses initialize the declaring type once. Compile-time
primitive constants are exempt. Superclasses and default-method interface
prerequisites initialize first; reentrant cycles expose partial state; and a
failed type rethrows the exact original exception on later use. Interfaces and
anonymous or enum-constant classes do not accept explicit static blocks. Static
imports retain the ordinary constant-folding, overload, accessibility,
active-use initialization, and artifact-reconstruction rules of their selected
members; unchecked generic casts remain deliberately unsupported.
General annotations remain outside the language model. The built-in
`@Override` and `@Test` directives do not change that exclusion.

### Primitive types and expressions

Ironwood implements the complete Java-width primitive set:

| Type | Semantics |
| --- | --- |
| `byte` | Signed 8-bit integer. |
| `short` | Signed 16-bit integer. |
| `int` | Signed 32-bit integer. |
| `long` | Signed 64-bit integer. |
| `char` | Unsigned 16-bit UTF-16 code unit. |
| `float` | IEEE-754 binary32. |
| `double` | IEEE-754 binary64. |
| `boolean` | Logical `true` or `false`; not interchangeable with integers. |

Supported expression behavior includes:

- Decimal, hexadecimal, and binary integral literals, decimal floating literals,
  character literals, ordinary String literals, cooked and raw text blocks,
  underscores, and Java-shaped suffixes.
- Binary literals use `0b`/`0B`, allow underscores only between binary digits,
  and select `int` unless suffixed with `L`/`l`. Like hexadecimal literals, a
  spelling may fill all 32 or 64 selected-width bits and denotes that exact
  two's-complement pattern.
- Java unary and binary numeric promotion, widening conversions, representable
  constant narrowing, and explicit numeric casts.
- Wrapping signed integer arithmetic, defined `MIN_VALUE / -1`, checked integer
  division by zero, Java-style remainder, and masked shift distances.
- Arithmetic, comparisons, equality, bitwise operators, shifts, boolean
  short-circuit operators, unary operators, and the conditional operator.
- Plain and compound assignment, prefix/postfix increment and decrement, and
  evaluated-once field/array lvalues.
- IEEE floating-point operations without fast-math assumptions; Java-shaped NaN
  comparisons and saturating floating-to-integral casts.

Primitives are values, not objects. Automatic boxing/unboxing is deliberately
excluded because it can create hidden wrapper allocations without a reliable
source-level owner. An unbounded generic parameter may receive a primitive
through closed-world native specialization; it never becomes a wrapper.
Java-style leading-zero octal is deliberately excluded by Feature 103, while
hexadecimal floating-point literals remain an open decision under Feature 104.
Source-wide Unicode escapes, Unicode identifiers, octal/`\s` escapes, and text-
block line continuation are not implemented.

### Generics

Supported today:

- Generic classes, interfaces, methods, and constructors with exact reference
  arguments, upper/intersection/dependent bounds, and first-bound erasure.
- All eight primitive arguments for parameters that omit an explicit upper
  bound; explicit bounds, including `extends Object`, remain reference-only.
- Explicit, invariant reference arguments, including nested parameterized
  types, arrays, and other type variables.
- Exact substitution through fields, constructors, method signatures,
  inheritance, overload resolution, overriding, and dispatch.
- Java-shaped `?`, `? extends`, and `? super` wildcard views with fresh capture
  conversion for receiver selection and invocation arguments.
- Explicit callable type arguments, argument/receiver/expected-type inference,
  least-containing parameterization, and diamond construction.
- Generic owner/member types across static nested, inner, local, and anonymous
  declarations.
- Fixed-arity invocation for ordinary and generic callables.
- Generic reference arrays such as `new T[length]`.
- One erased native implementation per reference shape while retaining full
  static generic types in semantic analysis and typed IR.
- Deterministic final-program primitive layouts, callable bodies, generic
  dispatch slots, and exact array descriptors, with all reference positions
  sharing `ptr`.

Rejected by design: raw uses, automatic boxing/unboxing, `new T()`, varargs,
wildcard construction/inheritance, non-reifiable `instanceof`, and parameterized
casts whose erased type arguments cannot be proved from source and the closed
world. Primitive shapes do not convert to wildcard views, and specialization
rejects a generic body that needs `null`, `Object` conversion/member dispatch,
throwing the value, or another reference-only operation.

### Arrays

Supported today:

- Recursive arrays of every primitive, class, interface, generic reference,
  type-parameter, and array element type.
- Zero initialization, checked allocation length and indexes, `.length`, indexed
  reads and writes, identity equality, and widening to `Object`.
- Invariant array assignment. Unlike Java, `Child[]` does not widen to `Base[]`.
- Exact `instanceof` and checked casts for reifiable array targets such as
  `int[][]` and erased `Box<?>[]`; mismatched checked casts throw
  `ClassCastException` and `null` retains Java-shaped test/cast behavior.
- One visible allocation per `new`: `new T[n][]` allocates only the outer
  container and zero-initializes its child slots. A later sized dimension is
  rejected, so every child array has an ordinary source-level owner.
- Java-shaped declaration initializers and `new T[] { ... }` array-creation
  initializers, including empty and trailing-comma forms. Lengths are exact,
  element assignment conversions and stores occur left to right, and nested
  braces recursively create one ordinary array per written brace level.
- Overlap-safe `System.arraycopy` when source and destination have the same exact
  invariant array descriptor.
- Explicit safe reclamation of the array container. Freeing an array never
  recursively frees referenced elements.

The compiler can prove direct child detachment through a known local constant
slot: an explicitly created or initializer-created child remains live until
that slot is overwritten with `null` or the outer container is freed. Dynamic
indices, observable calls, copies, and escaped containers remain conservative.
Java array covariance is deliberately excluded because it is statically
unsound and requires runtime store failures. Additional array APIs remain
separately deferred.

### Strings, mutable text, and basic I/O

Supported today:

- Immutable, pooled, immortal `ironwood.lang.String` literals stored as UTF-16
  code units. Equal decoded literals share one final-program identity across
  source and class/archive inputs without a runtime pool call.
- Feature 78 cooked `"""` text blocks with Java-shaped newline and incidental-
  indentation normalization followed by ordinary Ironwood escape processing,
  plus escape-free `r"""` blocks with the same layout rules. Neither form
  interpolates, and both feed the ordinary final-program literal pool.
- Java-compatible UTF-16 `length()`, `charAt(int)`, content `equals`, and
  `hashCode`; reference identity remains the meaning of `==`.
- Java-compatible UTF-16 `substring(int)`/`substring(int, int)` and
  `toCharArray()`, each returning a fresh caller-owned allocation with
  compiler-visible identity and catchable range/allocation failures.
- Final `String` with copy and checked `char[]` constructors, common UTF-16
  search/prefix/suffix/subsequence/copy operations, `Comparable<String>`, and
  caller-owned concat and primitive text results.
- Everyday String whitespace operations, literal replacements, offset searches,
  repeat, byte/char snapshots and explicit joins. Unicode case conversion uses
  fixed `en_US` semantics; `equalsIgnoreCase` is locale-independent. D117
  excludes `ironwood.util.Locale` and configurable locale state. See STDLIB.md.
- Exact UTF-8 `byteLength()` and deterministic surrogate handling at the native
  output boundary.
- `ironwood.lang.CharSequence` and `Comparable<T>`.
- Mutable `ironwood.lang.StringBuilder` with Java-shaped constructors, capacity
  and length operations, character access/mutation, growth, append/insert
  overloads, deletion/replacement, surrogate-aware reversal, substring search,
  `isEmpty`, caller-owned `substring`/`subSequence`/`toString` snapshots,
  deterministic backing-array destruction, and failed-construction rollback.
- Synchronous UTF-8 primitive/Object `print`/`println`, allocation-free
  `CharSequence` output, flush, and error-state checks through distinct immortal
  `System.out` and `System.err` PrintStreams.
- Static primitive parsing/comparison helpers, common `Math`, LF line
  separation, fresh-or-null environment lookup, and wall/monotonic clocks.

Source-level String `+`/`+=` concatenation is implemented as Feature 76 with
Java-shaped conversion and evaluation order, pooled constant-expression
results, and one exact-size ordinary allocation per maximal dynamic chain.
`StringBuilder.append(float)`/`append(double)`, matching floating `String.valueOf`
overloads, and whole/ranged `CharSequence` append are available. Floating
conversion preserves the original argument type (D116). U2/U3 provide paths,
whole-file and streaming I/O, UTF-8 readers/writers, and immortal System.in; see
STDLIB.md for the supported methods and deferred overloads. Java's process-global
runtime `String.intern()` pool is deliberately
unsupported by Feature 77.

### Statements and control flow

Supported today:

- Initialized lexical locals with block scope.
- Explicit `final` locals and method, constructor, and catch parameters, with
  assignment/update rejection and capture eligibility.
- `if`/`else`, `while`, `do`/`while`, classic and enhanced `for`, classic
  integral/enum `switch`, nested blocks, empty and expression statements,
  labeled statements and transfers, `break`, `continue`, `return`, `throw`,
  `try`, `catch`, and `finally`.
- Evaluated-once enhanced `for` over arrays and `ironwood.lang.Iterable`.
  Array traversal uses checked ascending-index loads. Iterable traversal calls
  `iterator()` once and borrows the producer-owned iterator without hidden
  allocation or implicit reclamation before ordinary `hasNext()`/`next()`
  calls. The iteration variable has body scope and may be `final` or captured.
- `switch` over `byte`, `short`, `char`, `int`, one exact enum type, or String,
  evaluated once. Classic colon statements retain consecutive labels,
  source-order fallthrough, and nearest-switch/loop `break`. Modern non-pattern
  forms add comma constant labels, `case null`, `case null, default`,
  non-fallthrough arrow rules, expressions, `yield`, target-aware result
  merging, and required `default` or complete exact-enum coverage.
- Explicit resource cleanup through ordinary `try`/`finally`; Java's
  `try (...)` resource syntax is deliberately rejected.
- Boolean-only conditions; values are not implicitly truthy.
- Named reifiable reference patterns in `instanceof`, with evaluated-once typed
  aliases, optional `final`, Java-shaped definite-match scope through boolean
  expressions, branches, guards, and supported loops, plus ordinary lexical
  capture and safe-`free` identity tracking.
- Control-flow-aware return checking, unreachable-statement diagnostics, SSA phi
  construction, and loop update semantics.
- Return, exception propagation, and labeled or unlabeled `break`/`continue`
  through every crossed `finally`, with inner-to-outer cleanup, abrupt-cleanup
  precedence for transfers, and ordered secondary exceptions when cleanup also
  fails during exception propagation.

A classic switch body is one lexical scope, and direct entry to a later label
cannot use an earlier declaration unless it is initialized on every incoming
path. Arrow rules have independent block scope and never fall through. A
`yield` executes every crossed `finally`; abrupt cleanup supersedes the pending
result. Integral/enum dispatch reuses typed switch IR, String dispatch uses
source-ordered equality against pooled constants, and all normal expression
results meet in a typed phi without hidden allocation.
Feature 102 deliberately excludes `assert`. Reference type patterns for
`switch`, record/unnamed patterns, and preview primitive patterns
remain deliberately excluded Features 106–108. Synchronized blocks remain
deliberately excluded.

### Exceptions

Supported today:

- Java-shaped `throw`, ordered typed `catch`, rethrow, and `try`/`finally`.
- Native cross-frame unwinding on macOS and Linux, including constructor calls,
  catch matching by Ironwood nominal type membership, and uncaught diagnostics.
- A standard `Throwable`/`Error`/`Exception`/`RuntimeException` hierarchy and
  common unchecked exception classes.
- Java-shaped `Throwable.toString()` descriptions through virtual
  `getLocalizedMessage()`/`getMessage()`, with fresh caller-owned results (D114).
- Catchable exceptions produced by supported language/library operations such as
  integer division by zero, checked casts, and bounds-checked String operations.
- Exception identity preservation through runtime-owned native unwind wrappers.
- Ordered secondary-exception retention when `finally` fails during exception
  propagation, with allocation-free count/index inspection on `Throwable`.
- Automatic uncaught reports with the qualified type, optional message, and
  ordered qualified callable, `.iron` filename, and line frames for the primary
  and every directly associated secondary exception.
- `ironwood.lang.AutoCloseable` as an ordinary optional interface for closeable
  APIs; it has no compiler-recognized automatic-cleanup behavior.

Thrown expressions and catch types must derive from `Throwable`; bounded type
variables may be thrown only when their bound proves that relationship. Catch
targets cannot be type variables or non-reifiable parameterizations, and a
generic class cannot directly or indirectly extend `Throwable`. Exceptions
under `RuntimeException` or `Error` are unchecked; every other `Throwable`
subtype is checked. Methods, interface methods, and constructors may declare a
comma-separated `throws` clause. A checked `throw` or selected invocation must
be covered by an enclosing catch or by the current callable's declaration.
Generic throws variables require a `Throwable`-compatible bound and are
substituted at the call site. Overrides may narrow or remove checked exceptions
but cannot add an incompatible one. A checked catch with no overlapping checked
source in its try body is unreachable and rejected; catching a subtype of a
broader declared source does not fully handle that broader contract. If a
protected body or catch propagates `A` and its `finally` throws `B`, `A` remains
primary and `B` is appended to its ordered secondary list. Nested cleanup
failures are flattened onto the same list in occurrence order.
`getSecondaryExceptionCount()` and
`getSecondaryException(int)` inspect the list without creating a hidden array;
an invalid index fails deterministically. Ordinary construction invokes virtual
`fillInStackTrace()` to capture source frames on demand from native unwind and
read-only compiler metadata; rethrows preserve them and explicit
refresh replaces them (D121). Throwable construction/fill frames are omitted;
callers use call-expression lines. Other constructors are named `<init>`, and
existing `$`-qualified nested/anonymous type identities are retained. Trace
storage belongs to the Throwable but does not count as Ironwood allocations.
`printStackTrace()` and its PrintStream overload render virtual descriptions,
causes and secondary failures. `getStackTrace()` returns a fresh caller-owned
array whose immutable elements are compiler-emitted process-lifetime objects.
Constructor-supplied and single-assignment mutable causes are supported;
mutable trace replacement and Java-compatible suppression remain absent. A catch may name disjoint
`|`-separated alternatives; the shared binding has their least common nominal
supertype, is implicitly final, and remains capturable. A final or
effectively-final catch parameter rethrown directly uses the exact checked
types that reached its catch after prior-catch filtering and generic `throws`
substitution. Union dispatch uses existing type tests and one shared body, with
no new allocation or runtime representation. Feature 100 makes null
field/method/array use, out-of-bounds array load/store, negative array lengths,
and `throw null` raise ordinary catchable unchecked exceptions. These failures
use the same captured trace, catch, `finally`, and ordered secondary exception
machinery as explicit throws.

## Explicit memory reclamation

Ironwood has no garbage collector. `new` has familiar Java allocation and
aliasing semantics, but an ordinary allocation remains allocated until an
accepted source-level `free` reclaims it or the process terminates.

```java
Buffer value = new Buffer(1024);
use(value);
free value;
```

The compiler accepts `free` only when it proves that the exact allocation cannot
subsequently be observed through any live alias. The current proof supports:

- Object and array allocations whose identities remain known through supported
  path-sensitive control flow, including direct `new` and fresh-or-null factory
  results derived by closed-world return summaries.
- Local aliases that are dead before the `free`.
- Direct and devirtualized calls proven not to retain, return, throw, or
  otherwise escape the allocation.
- A superseded private backing array after closed-world analysis proves the
  field exclusive and a fresh replacement detaches the old container.
- Detached backing arrays carried through migration loops when their SSA
  identity remains unchanged.
- Child allocations stored in a known constant slot of a local array, after a
  direct overwrite proves detachment or after the outer container is freed.
- Direct cleanup of a declaring class's compiler-proven owned fields from its
  destructor.
- Dependent borrows of encapsulated compiler-owned reusable helpers. The owner
  destructor may reclaim the helper when no later observation exists; direct
  helper `free`, post-owner use, and escaped or uncertain borrows are rejected.
- Fresh results from compiler-owned allocation intrinsics and their
  closed-world wrappers, including default `Object.toString()`,
  String constructors, `String.substring(...)`, `String.concat(...)`,
  primitive text factories, `String.toCharArray()`, `System.getenv(...)`, and
  `StringBuilder.toString()`.
- Existing local allocations whose identity and non-escape proof remain common
  across `try`/`catch` paths, including exactly-once cleanup written in
  `finally` for normal, return, catch, and exceptional exits.

The compiler conservatively rejects unknown provenance, live aliases, parameters,
arbitrary fields or call results, publication through fields/statics or unknown
array slots, escaping or uncertain calls, unsafe polymorphism, conditional
detachment, double-free, and post-free use. Failure to prove safety is a
compile-time error; removing `free` is safe but leaves the allocation
unreclaimed.

Compiler-generated copies of one source `finally` block are mutually exclusive
runtime paths. Safe-free analysis snapshots ownership per predecessor before
lowering those copies and conservatively rejects any real path disagreement;
the duplication itself therefore neither causes a false double-free diagnostic
nor permits an actual double free. This path-specific proof currently covers
normal, return, catch, exceptional, `break`, `continue`, and `yield` completion.
Transfer destinations retain post-cleanup ownership, possibly-freed joins reject
observation, and pending reference-valued yields cannot be freed by cleanup.
Loop back edges permit per-iteration local reclamation but reject newly freed
carried references or incompatible entry ownership for a repeated free.

`free` releases only the target allocation and any owned children explicitly
freed by its destructor; it does not null arbitrary aliases or recursively free
array elements. Object destruction runs declared bodies from the dynamic class
to the root. Constructors that throw use compiler-generated ownership rollback,
not source destructors, and rethrow the original exception. The runtime may
release its own transient native storage, which is not an Ironwood object.

A successful `free local;` leaves the old local value semantically dead rather
than assigning `null`. Reading, comparing, passing, returning, or freeing that
value again is rejected. Plain assignment may reuse the variable without
reading the dead value; the replacement allocation then has an independent
identity and lifetime. Compound assignment reads the old value and is rejected.

This design retains normal Java reference assignment without adding ownership,
borrow, or lifetime syntax to ordinary source code. Safety is enforced at the
operation that requests reclamation.

## Current standard library

The current source-file and documented-API inventory is maintained in
[`STDLIB.md`](STDLIB.md). Only reachable classes enter the final closed-world
program.

| Package | Current surface |
| --- | --- |
| `ironwood.lang` | `Object`, final UTF-16 `String`, `StringBuilder`, `CharSequence`, `Comparable`, `System` (including stdout/stderr, environment, clocks, identity/allocation diagnostics, and array copy), common `Math`, static primitive conversion utilities, `Iterable`, `AutoCloseable`, and the initial exception hierarchy. This package is implicitly visible. |
| `ironwood.io` | `PrintStream` primitive/Object `print`/`println`, allocation-free `CharSequence` output, flush, and error checks for fixed `System.out` and `System.err` singletons. |
| `ironwood.nio` | Checked heap-only `ByteBuffer` plus overflow/underflow exceptions. |
| `ironwood.util` | Generic `Iterator` with a throwing `remove()` default, `NoSuchElementException`, `Objects`, `Arrays`, `Comparator`, `Random`, `Optional`, `StringJoiner`, and `BitSet`. |
| `ironwood.time`, `ironwood.time.format` | Immutable `Instant`: epoch factories/access, comparisons, full-range ISO parsing/rendering and millisecond-clock `now`; date/time and parse exceptions. General formatting, Temporal APIs and named timezones remain absent. See D120 and `STDLIB_INSTANT_REVIEW.md`. |
| `ironwood.pool` | `ArrayObjectPool` and `MultiArrayObjectPool`, using explicit `ObjectBuilder` implementations. |
| `ironwood.ds` | Generic and primitive lists, maps, linked maps, sets, character-sequence maps, fixed-domain maps, and `ByteBufferMap`, with reusable iterators and pooled internal entries. |

Collections borrow inserted user objects. Object pools own objects created by
builders or transferred through `release`; `get` lends them to callers. Pool
destruction includes checked-out values and requires a compiler safety proof.
`takeRetained` is removed. Replaced and terminal proven backing storage is reclaimed;
borrowed reusable iterators and primitive holders remain producer-owned. Their
destructors reclaim the cached helpers, and the compiler rejects every
observable or uncertain borrow that would outlive the producer. See
[Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

## Roadmap: later support

Features 73, 69, 65, 61, 55, 76, 74, 51, 60, 78, 100, 66, 90, 92, 89, 83, 86, and 105 are
implemented under D054, D055, D056, D057, D060, D061, D062, D063, D064,
D065, D074, D075, D076, D077, D078, D079, D080, and D081. D059 records
implemented literal pooling as Feature 75 and excludes Feature 77 runtime
interning. D066 audits the final Java SE 26 language surface as Features 79–100
without changing compiler behavior or selecting a new implementation target.
D067 confirms the selected exclusions and separates `volatile` as Feature 101;
no feature is promoted or selected for implementation.
D068 removes `assert` from the still-open Feature 90 and confirms it separately
as Feature 102; the eight open audit decisions otherwise remain unchanged.
D069 commits Features 83, 92, and 100 as unranked pending work, confirms
Features 99 and 103 as excluded, and separates still-open Features 104 and 105.
D070 commits Feature 105's bounded immortal allocation-failure contract without
ranking it or selecting an implementation target.
D071 commits Features 86, 89, 90, and 101 as unranked pending work without
changing current behavior or selecting an implementation target.
D072 separates reference type patterns for `instanceof`, non-pattern modern
switch, reference type patterns in switch, record/unnamed patterns, and preview
primitive patterns as Features 66, 91, and 106–108. It changes no current
behavior or pending rank.
D073 commits Features 66 and 91, returns Feature 101 to excluded, confirms
Features 106–108 as excluded, and ranks all pending work in the order 100, 66,
90, 92, 89, 83, 86, 105, and 91. It changes no current behavior and selects no
implementation target.
D074 implements Feature 100 through typed safety predicates, exceptional CFG,
and the existing native unwind machinery. It leaves the remaining priority
order 66, 90, 92, 89, 83, 86, 105, and 91 and selects no subsequent target.
D075 implements Feature 66 through Java-shaped definite-match flow, typed SSA
aliases of existing type tests, capture and ownership integration, and artifact
reconstruction. It leaves the remaining priority order 90, 92, 89, 83, 86,
105, and 91 and selects no subsequent target.
D076 implements Feature 90 through Java-shaped remaining statement forms,
borrowed producer-owned Iterable traversal, cleanup-preserving transfers,
typed CFG lowering, and artifact reconstruction. It leaves the remaining
priority order 92, 89, 83, 86, 105, and 91 and selects no subsequent target.
D077 implements Feature 92 through disjoint union catches, implicitly-final
shared bindings, precise final/effectively-final rethrow analysis, typed union
dispatch, and artifact reconstruction. It leaves the remaining priority order
89, 83, 86, 105, and 91 and selects no subsequent target.
D078 implements Feature 89 through contextual declaration and array-creation
initializers, exact typed lengths, ordered element stores, recursive allocation,
constant-slot child provenance, and artifact reconstruction. It leaves the
remaining priority order 83, 86, 105, and 91 and selects no subsequent target.
D079 implements Feature 83 through binary-token validation, shared width-aware
constant decoding, typed constant and switch lowering, LLVM integer constants,
and artifact reconstruction. It leaves the remaining priority order 86, 105,
and 91 and selects no subsequent target.
D080 implements Feature 86 through single-member and on-demand static-import
parsing, owner discovery, member lookup, Java-shaped shadowing and ambiguity,
ordinary overload/generic invocation, active-use initialization, and format-1
artifact reconstruction. It leaves the remaining priority order 105 and 91
and selects no subsequent target.
D081 implements Feature 105 through unwind-capable typed allocation operations,
one compiler-owned immortal `OutOfMemoryError`, bounded runtime-private trace
and association storage, and source-catch state release. Feature 91 is the sole
remaining pending feature, and no subsequent target is selected.
D082 implements Feature 91 through modern switch AST forms, constant-label and
exhaustiveness validation, cleanup-aware result flow, typed integral/enum/
String dispatch, result phis, ownership analysis, and source-artifact
reconstruction. No numbered feature remains pending, and no subsequent target
is selected.

### Committed language surface

No numbered feature is currently committed and pending. Feature 104 remains an
open promotion decision with its current unsupported status.

### Uncommitted Java-shaped language candidates

- Hexadecimal floating-point literals under Feature 104.

### Other deferred language surface

- A final nullability model, including whether optional non-null reference types
  should be added without making ordinary Java-shaped code cumbersome.

### Exceptions, cleanup, and allocation failure

- Causes and Java-compatible suppression remain separately deferred.
- Multi-catch/precise rethrow is implemented as Feature 92. Catchable ordinary
  implicit runtime safety failures are implemented as Feature 100.
- Feature 105 implements catchable source-evaluated allocator exhaustion with
  one immortal error, one bounded allocation-free delivery path, an exact
  failing-site frame, explicit trace truncation, deterministic termination on
  repeated exhaustion while active, and permitted but unguaranteed catch-and-
  retry recovery. Failed allocation produces no owned source object and does
  not increment the allocation count.

### Memory analysis and optimization

- Broaden safe-`free` proofs for consumed parameters, more precise dynamic array
  element provenance, and additional closed-world polymorphic call patterns.
- Escape-driven stack allocation, scalar replacement, dead-allocation removal,
  and safe-`free`-aware optimization while preserving observable identity.
- Method-level reachability and dead-code elimination, broader
  devirtualization/inlining, and proven bounds-check elimination.
- Optional debug poisoning or quarantine of explicitly freed allocations as a
  compiler/runtime bug detector, never as a substitute for static proof.

### Runtime, libraries, platforms, and tooling

- Broader filesystem operations, time/date, sockets/networking, richer NIO,
  general charsets, and cryptography/TLS. The supported model remains single-threaded.
- A native FFI with a clearly marked low-level unsafe boundary; raw native
  addresses must not leak into ordinary Ironwood code.
- Stable object/exception layouts, a native ABI, a serialized compiled-library
  format, incremental compilation, and compilation caching.
- Native library output, package/module distribution, static-versus-dynamic
  native dependency policy, debug information, and benchmarking.
- Windows exception lowering, additional supported operating systems,
  cross-compilation, and user-selectable target/sysroot handling.
- Compile-time metadata and source generation where they can replace JVM-style
  runtime reflection cleanly without an annotation facility.
- Full Javadoc tooling remains deferred. D098 implements the IronDocs subset:
  `/** ... */` declaration association, common tags and selected-member links,
  and Markdown generation through `irondoc`. `///`, inherited documentation,
  DocLint, and custom doclets remain unsupported. See [IRONDOCS.md](IRONDOCS.md).
- Eventual self-hosting after the language and compiler are sufficiently stable.

## Permanent non-goals

The following features will not be part of Ironwood's ordinary language/runtime
model. Adding one would reverse a governing project decision rather than merely
complete a roadmap item.

### Garbage collection and GC-dependent behavior

- No tracing, reference-counting, or other automatic reachability-based
  reclamation of ordinary objects.
- No silent reclamation merely because an allocation becomes unreachable.
- No Java finalization model or GC-dependent `SoftReference`, `WeakReference`,
  `PhantomReference`, `ReferenceQueue`, or `System.gc()` semantics.
- No hidden collector fallback when `free` is omitted or rejected.

Ironwood may optimize a proven nonescaping allocation into stack storage or
eliminate it entirely when observable semantics do not change. That is a
compile-time storage optimization, not garbage collection.

### Variable-arity parameters

- No `T...` parameter declarations or expanded variable-arity calls.
- A parameter ellipsis receives a direct diagnostic recommending an explicit
  array parameter.
- Libraries use explicit arrays or selected fixed-arity overloads instead of
  compiler-created argument arrays.

This is a deliberate explicit-ownership rule, not an unimplemented JVM
compatibility feature. It prevents call syntax from creating a hidden allocation
that source code may be unable to reclaim.

### Hidden allocation and legacy type-safety compromises

- No automatic boxing/unboxing. Implemented primitive generic support uses
  native specialization rather than invisible wrapper objects.
- No raw generic types or unchecked generic casts. Ironwood has no pre-generics
  compatibility requirement and does not permit heap-polluting escape hatches.
- No non-reifiable generic `instanceof`; Ironwood will not add pervasive runtime
  type-argument metadata solely to enable it.
- No Java array covariance or its corresponding runtime store-failure model.

### Deliberately excluded language families

- No general annotations or annotation processing. The implemented
  `@Override` and `@Test` method directives do not imply annotations.
- No records or sealed types.
- No lambdas, closures, or method references; explicit anonymous classes remain
  available.
- No reference type patterns for `switch`, record/unnamed patterns, or preview
  primitive patterns.
- No threads, thread-local storage, object monitors, `synchronized`, source-level
  `volatile`, atomics, or concurrent collections for the foreseeable future.
- No Java object serialization compatibility, `Object.clone()`/`Cloneable`
  machinery, compiler-generated copying, or GC-triggered finalization. Types may
  define ordinary copy constructors or methods with explicit shallow/deep and
  resource-ownership contracts. `clone` remains a legal ordinary method name
  without inherited or runtime semantics.

### JVM execution and runtime dynamism

- No JVM requirement for generated programs, Java bytecode execution format,
  bytecode interpreter, JIT compiler, or JVM deoptimization machinery.
- No runtime loading of Ironwood classes, user-defined class loaders,
  `Class.forName`, or dynamically arriving language modules.
- No unrestricted runtime reflection such as `Method.invoke` or reflective
  field mutation.
- No runtime-generated proxy classes, runtime bytecode/code generation,
  `invokedynamic`-style arbitrary linkage, or method-handle machinery used as an
  open-ended runtime linker.
- No Java agents or `java.lang.instrument` equivalent.
- No runtime `.ironjar` loading. Ironwood archives are compile-time inputs to a
  closed-world link.
- No Java-style process-global `String.intern()` pool for arbitrary runtime
  content. Runtime canonicalization should be an explicit application-owned
  collection with bounded, inspectable lifetime.

Compile-time source generation, metadata, service registration, and specialized
dispatch may provide useful equivalents without making the runtime open-ended.

### Unsafe ordinary source and Java compatibility baggage

- No general annotation syntax, annotation types, or annotation processing.
  Mandatory `@Override` and testing-only `@Test` are narrow built-in method
  directives, not annotation instances or general annotation grammar.
- No raw pointers or pointer arithmetic in ordinary source code.
- No JNI compatibility requirement. Native interoperability will use an
  Ironwood-specific FFI and explicit unsafe boundary if and when it is designed.
- No Rust-style ownership, borrow, or lifetime syntax imposed merely to make
  `free` possible. Ironwood keeps Java-like reference aliases and places the
  proof obligation on explicit reclamation.
- No promise to compile arbitrary Java source, consume Java class/JAR files, or
  reproduce APIs whose meaning depends on JVM internals.
- No class loader, bytecode verifier, runtime compiler, or hidden JVM-like
  subsystem inside the native runtime.

## Maintaining this specification

Update this file whenever a language or standard-library capability moves
between unsupported, deferred, experimental, and supported status. A semantic
change must also update the detailed rules in [`LANGUAGE.md`](LANGUAGE.md), its
compiler/runtime consequences, relevant tests, and an accepted entry in
[`DECISIONS.md`](DECISIONS.md) when it changes a governing decision.

Do not mark a feature supported based only on parser acceptance. Supported
features require semantic validation, compiler-owned IR where applicable,
native behavior, diagnostics, and regression tests. Do not promote an open item
to a promise merely because Java has it; compatibility remains subordinate to
closed-world native execution and deterministic memory safety.
