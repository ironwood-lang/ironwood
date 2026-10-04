<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood self-hosting assessment and migration plan

Assessment date: 2026-10-03. Source baseline:
`37e2dda14a1de7088bd0a079492a6fbec6c156a7` (`0.6.1-beta`).
The [preparation plan](BEFORE_SELF_HOSTING_PLAN.md) shares this source baseline.

Status: proposed engineering plan based on a source audit. No compiler has been
ported or bootstrapped by this work. The milestones below are pending, not
implementation authorization or evidence of completed qualification. D008's
Java bootstrap policy remains in effect; accepting a production cutover requires
an explicit decision superseding the relevant part of D008.

## 1. Recommendation and readiness

**Ironwood is mature enough to start a carefully staged self-hosting project.
It is not yet ready for a direct translation of the current Java compiler or
for retiring the Java implementation.** The strongest next step is a bounded
feasibility phase that ports the lexer/parser and a representative ownership
snapshot workload before committing to the entire compiler.

The language already has the essential expressive machinery: objects,
interfaces, generics, primitive specialization, enums, arrays, exceptions,
pattern `instanceof`, non-pattern switch expressions, and explicit resource
cleanup. The library has text, binary and file I/O, paths, directory traversal, useful
collections, and bit sets. The backend already compiles these constructs to
native code. A new language feature is not inherently necessary to express a
compiler.

The harder question is whether this particular compiler can be expressed,
compiled, and run efficiently with today's library and reclamation proofs.
That has not been established. Its pervasive Java collection operations,
immutable snapshots, boxed numbers, reflection-based IR walks, and reliance on
GC require deliberate adaptations. Process execution, archive support, and
hashing also need solutions before complete tool parity.

Java similarity helps substantially. Most algorithms, phase boundaries,
object models, diagnostics, and tests can survive. Familiar syntax makes
side-by-side review much easier than a rewrite in an unrelated language.
However, the implementation uses modern Java idioms that Ironwood deliberately
excludes. Ironwood is Java-shaped, rather than a strict subset: `free`,
destructors, `defer`, and primitive generic specialization add semantics Java
does not have. Sharing syntax does not make Java allocation, ownership,
library, or equality contracts interchangeable.

| Readiness dimension | Assessment | Consequence |
| --- | --- | --- |
| Core language expressiveness | Sufficient to begin | Preserve the compiler design and rewrite excluded idioms. |
| Compiler support library | Useful but incomplete | Reuse actual supported members; implement only demonstrated gaps. |
| Memory model for compiler workloads | Highest feasibility uncertainty | Prove representative graph/snapshot lifetimes and measure retained memory early. |
| Native backend and tools | Existing pipeline is suitable | Preserve LLVM 23 and the C runtime boundary; migrate host orchestration. |
| Correctness at compiler scale | Unverified | Small existing programs and library compilation do not establish self-build capacity. |
| Immediate production replacement | Premature | Require differential, bootstrap, resource, tool-parity, and platform gates. |

This assessment is based on local production source, library implementations,
registered tests, build scripts, and authoritative documentation. It is not a
claim that every line was manually inspected or that the future port was run.
No compiler or native suite was run for this documentation-only audit.

## 2. What is being migrated

### Source inventory

Counts cover all `.java` files under `compiler/src/main/java`, including comments,
blank lines, and embedded generated-source templates. Directory counts are
organizational, not independent modules or porting effort estimates.

| Area under `ironwood/compiler` | Files | Physical lines | Migration character |
| --- | ---: | ---: | --- |
| Top-level package | 42 | 6,731 | CLI, source discovery, artifacts, optimization, Bridge orchestration |
| `source` and `diagnostic` | 6 | 214 | Small foundational data and formatting layer |
| `lexer` | 5 | 793 | Good first executable slice; text and numeric details matter |
| `parser` | 2 | 3,141 | Existing recursive descent can be retained |
| `ast` | 91 | 2,010 | Mostly data structures; record contracts must be preserved |
| `semantic` | 82 | 44,133 | Main cost and correctness risk; about 57% of production lines |
| `ir` | 123 | 2,936 | Typed data model, structural equality, traversal coverage |
| `backend` | 18 | 5,183 | LLVM emission, trace finalization, toolchain and runtime linking |
| `bridge` | 86 | 11,290 | Models, proofs, and generated Java/C text; not all runs as compiler code |
| `doc` | 5 | 1,177 | IronDocs model, parsing, Markdown generation, CLI |
| **Total** | **460** | **77,608** | Substantial compiler port, not an extension rename |

There are also 208 Java test files with 64,859 physical lines. They should remain
a usable independent harness during migration. The production import inventory
contains JDK dependencies and compiler-local dependencies, with no third-party
Java import found. This avoids a separate parser-framework migration, but it
does not make the JDK dependencies small.

A lightweight lexical scan that removed comments and literals found 527 record
declarations in 331 files, 13 sealed declarations in 12 files, 1,373 `.stream()`
calls in 181 files, 949 method references in 148 files, and 1,753 `var`
declarations in 150 files. These are source-search counts, not a Java semantic
inventory. In particular, generated Bridge Java text must not be mistaken for
features needed by the compiler's own runtime. Recompute against the chosen
implementation baseline; do not turn these counts into a completion percentage.

### Preserve the existing architecture

The real entry points are
[`Main`](../compiler/src/main/java/ironwood/compiler/Main.java),
[`CompilerPipeline`](../compiler/src/main/java/ironwood/compiler/CompilerPipeline.java),
[`SourceSetLoader`](../compiler/src/main/java/ironwood/compiler/SourceSetLoader.java),
and [`NativeLinkPipeline`](../compiler/src/main/java/ironwood/compiler/NativeLinkPipeline.java).
The intended migration preserves this sequence:

1. Read UTF-8 sources, discover dependencies, and parse compilation units.
2. Collect types, lexical scopes, signatures, inheritance, and constants.
3. Bind calls and lower provisional typed functions.
4. Resolve receiver flow, effects, borrowing, owned fields, and safe reclamation
   through the existing refinement stages.
5. Produce validated compiler-owned typed IR and primitive generic specializations.
6. Perform the existing native-link transformations and reachability pruning.
7. Emit LLVM, run the pinned LLVM tools, finalize trace metadata, compile the
   isolated C runtime with Clang, and link native output.

The port changes the implementation language, not Ironwood's source semantics
or backend architecture. Do not move semantic decisions into LLVM text
generation, replace typed IR with C, or combine the migration with a new
optimizer, artifact ABI, or language redesign.

The largest files deserve explicit review boundaries:
[`FunctionAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java)
has 14,502 lines, [`LlvmEmitter`](../compiler/src/main/java/ironwood/compiler/backend/LlvmEmitter.java)
3,581, [`Parser`](../compiler/src/main/java/ironwood/compiler/parser/Parser.java)
3,125, and [`SemanticAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java)
3,082. Do not make their complete translation one review unit. Extract a helper
only where a demonstrated portability boundary warrants it, with independent
behavioral verification before translating it.

### Define completion precisely

Three different outcomes must not be conflated:

- **Native compiler core:** Ironwood source performs parsing, semantic and
  ownership analysis, typed IR construction, transformations, and LLVM emission.
  A shell driver may coordinate LLVM and a native trace-finalization mode.
- **Self-hosting:** that native implementation compiles its own complete source
  closure into the next working compiler, without calling the Java frontend or
  Java semantic analysis at any stage of that rebuild.
- **Production tool parity:** source/class/archive workflows, diagnostics,
  `ironjar`, `irondoc`, Java Bridge production, installation discovery, packaging,
  and IDE integration have defined and verified replacements.

Keep the existing C runtime and external LLVM/Clang tools. Self-hosting does not
mean rewriting the runtime, LLVM, the system linker, shell scripts, or every
repository tool in Ironwood. Optional Java Bridge production can still require
an external JDK to compile generated Java. Java-hosted tests and the Eclipse
plugin can remain Java without making the native compiler Java-hosted.

## 3. Compatibility findings and required adaptations

### Language and data representation

| Current Java dependency | Concrete evidence | Porting approach |
| --- | --- | --- |
| Records and sealed hierarchies | AST `Expression`, `TypeName`; IR `IrFunction`, `IrType`; nested semantic result/snapshot records | Use final classes with constructors/accessors and explicit equality/hash behavior where needed; use ordinary interfaces. Preserve constructor validation and traversal completeness. |
| Type-pattern `switch` | 536 arms in 19 production files; [`IrCfgRenamer`](../compiler/src/main/java/ironwood/compiler/ir/IrCfgRenamer.java) has 92 and [`BorrowDispatchAnalysis`](../compiler/src/main/java/ironwood/compiler/semantic/BorrowDispatchAnalysis.java) has 91; several Bridge analyzers have 20-41 each | Rewrite as ordered `instanceof`-pattern `if`/`else` chains. Preserve dispatch behavior and replace sealed-switch exhaustiveness with the per-consumer [variant coverage check](#reflection-really-occurs-in-the-compiler) below. |
| Streams, lambdas, method references | Dependency discovery, almost every semantic pass, emitter joins and filtering | Use explicit loops and direct helpers; implement the compiler-local callback interfaces below with named or anonymous classes where callbacks remain necessary. Preserve encounter order, short circuiting, duplicate handling, and exception timing. |
| Functional callback types | 25 generic type references in 12 files, including `IrCfgRenamer` fields and `FunctionAnalyzer` parameters; two `BooleanSupplier` parameters and four compiler-defined `@FunctionalInterface` declarations | Replace retained `java.util.function` types with minimal compiler-local interfaces; retain the compiler-defined interfaces without the annotation. Inventory callback allocations, captures, and owners under section 4. |
| `var`, uninitialized locals, multiple declarators | `Main`, Bridge code, backend; Feature 88 remains unsupported | Spell types and one initialized variable per declaration. Restructure branches where choosing a dummy initial value would obscure state. |
| Varargs and convenience factories | `Parser.contiguousKinds(TokenKind...)`, `List.of`, `Set.of`, `Map.ofEntries` | Fixed-arity helpers or explicit arrays/builders with clear ownership. Do not add varargs to the language. |
| Boxing and nullable wrappers | `TypeName` uses `List<Integer>`; lexer escape decoding returns nullable `Character`; backend uses nullable `Integer`/`Boolean` options | Primitive lists/arrays, explicit presence flags, or small typed results. `ironwood.ds` generics have reference bounds and cannot simply become `ArrayList<int>`. |
| Numeric values behind `Number`/`Object` | `ConstantValue`, `IrConstant`, `FunctionAnalyzer` constant results | An explicit constant kind with primitive payloads and a separate string/null representation. Ironwood's abstract `Number` is not a Java wrapper hierarchy. |
| Try-with-resources | Artifact readers/writers, version loading, backend file walks | Existing `try/finally` or `defer`, preserving acquisition order, reverse close order, partial failure, and primary/secondary exceptions; `close` and `free` are distinct. |
| Class literals and reflective type filters | `SomeIrInstruction.class::isInstance`, `class::cast`, `getClass().getSimpleName()` | Pattern `instanceof`, explicit casts, and explicit node names/kinds. Do not introduce runtime `Class`. |
| Array cloning and library cloning | `IronJar.ClassPayload`, `SharedTraceOrder`, effect-analysis `BitSet.clone()` | Explicit copies of the required shallow/deep extent. A supported type name does not imply a supported `clone` method. |
| Annotations and synchronization | `@FunctionalInterface`; synchronized runtime-object cache | Remove host-only annotations; retain required Ironwood `@Override`. Make cache ownership explicit for the single-threaded native process. Do not silently change the Java LSP's concurrency contracts. |

Ironwood already supports many constructs that should be retained: nominal
`instanceof` patterns, generic bounds/wildcards and inference, inner/local and
anonymous classes, enum switches, non-pattern switch expressions, and text blocks.

The production-source audit also identifies the following supported idioms;
counts exclude comments, literals, and generated Java text:

| Supported construct | Compiler evidence | Porting treatment |
| --- | --- | --- |
| Multi-catch | 10 clauses, including driver errors and reflective traversal failures | Retain the syntax and catch ordering for surviving exception types; reflection removal changes those consumers separately. |
| Interface default methods | Seven methods in `InvocationPlanningContext` and `AnonymousParentBinder.PrimaryPlanningContext` | Retain default implementations and normal interface resolution. |
| `yield` | 19 statements in semantic, IR, documentation, and Bridge code | Retain in non-pattern switch expressions, preserving result and cleanup behavior; move the result logic when an enclosing pattern switch is rewritten. |
| `case null` | No executable arms in the audited compiler; occurrences are diagnostic text | Supported for enum and String selectors, including `case null, default`; no feature addition is needed. |
| Labeled statements and transfers | None used in the audited compiler | Already supported; no migration work is required for this construct. |
| String `switch` | `DocComment` rendering/entities and Bridge exception-source generators | Retain constant labels, arrow rules, and switch expressions. |

These are implemented [object-model](LANGUAGE_SPECS.md#classes-interfaces-objects-and-references),
[control-flow](LANGUAGE_SPECS.md#statements-and-control-flow), and
[exception](LANGUAGE_SPECS.md#exceptions-cleanup-and-allocation-failure) features. Among the switch forms used
by this compiler, only pattern dispatch requires a syntax rewrite.
There is no reason to wait for records, sealed types, lambdas, reflection, GC,
or the Java Collections Framework. Several are permanent non-goals in
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md#permanent-non-goals).

Reference type patterns in `switch` are Feature 106, within the deliberately
excluded Features 106-108. Their heavy use in compiler dispatch is migration
work, not a reason to add them to Ironwood. The counts above include qualified
type names and exclude comments and literals; the audited production source has
no executable `when` guard. Evaluate each rewritten selector once, preserve arm
order and null behavior, and retain result values, binding scopes, and abrupt
exits. If a later inventory finds guarded arms, evaluate the guard only after its
type matches and continue to subsequent arms when it is false.

An `instanceof` chain loses Java's compile-time exhaustiveness checking over
sealed hierarchies. Tie every rewritten dispatch to the same finite variant
inventory used for explicit traversal below. Compare each consumer's treatment
of every relevant variant, including intentional no-ops and phase-order
rejections; a catch-all `else` must not silently admit a newly added IR variant.

Record replacement is semantically significant. For example,
[`IrType`](../compiler/src/main/java/ironwood/compiler/ir/IrType.java) is a
structural type key, while symbol/allocation objects often have identity
semantics. `SemanticAnalyzer` tests equality of newly calculated maps/sets to
detect refinement convergence. Replacing record equality with object identity
could change overload resolution, duplicate detection, convergence, and safety
proofs. Replacing identity with value equality could merge distinct allocations.
Document each key's equality, hash, mutability, and lifetime contract before
translating its consumers. Array fields in records also require an explicit
decision about preserving existing array identity versus content comparison.

Anonymous classes still need declared target interfaces. For the surviving uses
of `Function`, `BiConsumer`, `Supplier`, `Predicate`, `UnaryOperator`, `Consumer`,
`BiFunction`, and `BooleanSupplier`, define ordinary single-method interfaces in
the compiler's own packages, using domain-specific signatures where practical.
Use existing `Runnable` or `Comparator` only where their contracts fit. Keep
only the callback operations needed after stream rewrites; this is not a public
`java.util.function` compatibility layer. Preserve reference generic bounds,
argument/result types, and exception behavior; use primitive boolean signatures
for `BooleanSupplier` and the boolean parameter of `DocComment.render`'s link
callback instead of introducing boxed `Boolean` merely to copy its Java
`BiFunction` type.

The four existing compiler-defined interfaces are `SemanticAnalyzerFactory`,
`FunctionAnalyzer.LValueWriter`, `AnonymousParentBinder.PlanningContextFactory`,
and `InvocationPlanningContext.ExpressionProbe`. Port their method contracts as
ordinary interfaces and remove `@FunctionalInterface`. Prefer direct helpers
with explicit state for immediately invoked callbacks where that preserves
evaluation and cleanup order. Where a callback is retained, as in `IrCfgRenamer`
or a specialization pass, record that field relationship and its lifetime rather
than treating the callback parameter as automatically non-retaining.

Audit generic declarations as well as their call sites. An omitted Java bound
still describes reference values; an omitted Ironwood bound also permits
primitive specialization. Spell `extends Object` when the port requires
reference-only operations. Preserve invariant arrays and source-provable casts;
do not use raw types, unchecked casts, or Java array covariance to repair a
translation that no longer type-checks.

### Collections are available, but are not drop-in replacements

The current library already contains `ArrayList`, `IntArrayList`, `LongArrayList`,
`HashMap`, `LinkedHashMap`, `IdentityHashMap`, corresponding sets, and primitive
maps. [`Optional`](../stdlib/src/main/ironwood/ironwood/util/Optional.iron),
[`BitSet`](../stdlib/src/main/ironwood/ironwood/util/BitSet.iron), `Arrays`,
`Objects`, `Comparator`, and `StringJoiner` also exist. Audit members and
contracts, rather than listing these types as missing.

The important differences are documented in
[`STDLIB.md`](STDLIB.md#collection-contracts-and-limitations) and visible in
[`IdentityHashMap`](../stdlib/src/main/ironwood/ironwood/ds/IdentityHashMap.iron):

- Iterators are borrowed, reused, and generally not reentrant. A recursive walk,
  callback, `equals`, or nested loop that requests another iterator on the same
  collection can disturb the outer traversal. Prefer indexed list traversal;
  use explicit stable key snapshots or a demonstrated collection extension for
  maps. Test nested traversal, not just a simple loop.
- Maps iterate values and expose the current key separately. They do not supply
  Java's `Map.Entry`, `keySet`, `values`, and stream-view API wholesale. Read the
  current key before any operation that might reset iteration.
- Maps reject null keys/values and generic lists reject null elements. Audit
  every Java null sentinel and every distinction between absent and present
  before replacing a container. Do not silently reinterpret a legal state.
- Containers borrow user elements. Clearing/removing elements is not recursive
  destruction, and individual removal does not generally discharge caller-item
  loans. Keep the rules in [CONTAINER_REMOVAL_LOANS.md](CONTAINER_REMOVAL_LOANS.md).
- `List.copyOf`, `Map.copyOf`, and `Set.copyOf` snapshots cannot become live
  read-only views. `Collections.unmodifiableList` is a live view. AST/IR and
  branch-state snapshots require independently stable storage, with the
  original shallow-versus-deep copying semantics explicitly preserved.
- Identity-key lookup is available, but collection equality must still be
  reviewed. The Ironwood identity map's value comparisons call `equals`;
  similarity of its name is insufficient to assume all Java identity-map
  equality behavior. Analysis keys must retain the intended identity relation.
- `TreeMap`/`TreeSet` and `ArrayDeque` are not present as equivalent APIs. Audit
  sorted-map usage: insertion-ordered maps plus sorting at serialization may
  suffice for output-only ordering; ordered lookup/worklists may need a focused
  implementation. Use existing list ends or a measured deque extension for
  stacks/queues. Avoid replacing a fast worklist with repeated front-shifting.
- `Optional` has basic presence/value methods, but no Java functional
  `map`/`flatMap`/`ifPresent` family. Its factories allocate wrappers, including
  empty results. Preserve explicit optional semantics where useful and remove
  short-lived wrapper churn where a typed result or nullable reference is clear.
- `BitSet` supports the required style of set operations, but effect analysis
  copies sets through `clone`. Implement an explicit verified copy strategy;
  aliasing a mutable bit set changes fixed-point results.

Extend `ironwood.ds` only for reusable, demonstrated needs. Keep compiler-domain
objects such as ownership snapshots, type keys, and ordered proof facts in the
compiler. Do not reproduce `java.util` behind a broad compatibility facade.
Comparator use also needs member-level review: the current reference-array sort
overloads require `T extends Comparable<T>`, even with a comparator. Arbitrary
IR nodes sorted by Java comparators need an adapted representation or a focused
contract-reviewed library extension.

### Reflection really occurs in the compiler

Two production walkers depend on Java record introspection:

- `SemanticAnalyzer.collectArrayTypes` walks record components to discover exact
  array descriptors.
- [`ClosedWorldPruner.scanRecord`](../compiler/src/main/java/ironwood/compiler/ClosedWorldPruner.java)
  walks typed IR to retain referenced types, fields, constants, and code.

Replace these with explicit typed traversal over every relevant IR variant,
including nested lists, optionals, operands, and type arguments. A shared,
narrow traversal may remove duplicate coverage work, but do not change each
consumer's semantic rules. Missing an edge can remove required code or metadata,
so this is a correctness blocker, not cosmetic cleanup. Establish a coverage
check that fails when a new IR variant has no traversal treatment. Extend that
check to each rewritten type-pattern dispatch: derive the variant inventory from
compiler model declarations or a checked finite schema independently of the dispatch
arms, and require an explicit treatment for every variant relevant to that
consumer. Record intentional no-ops and rejections individually; a generic
fallback is not coverage. Demonstrate that adding an untreated variant or
removing a treatment fails the build-time check. Development generation from a
finite schema is possible, but checked-in generated Ironwood and its reproduction
procedure must not hide a Java dependency in self-rebuilds.

The Bridge source generators contain Java class-loader, reflection, and
synchronization code inside generated text. That Java still runs in the Java
consumer. It is not a reason to add those mechanisms to Ironwood. Separate
generator execution from the language of its output throughout the inventory.

### Numbers, text, and deterministic output

[`IntegerLiteralDecoder`](../compiler/src/main/java/ironwood/compiler/semantic/IntegerLiteralDecoder.java),
[`StaticConstantEvaluator`](../compiler/src/main/java/ironwood/compiler/semantic/StaticConstantEvaluator.java),
and `FunctionAnalyzer` use `BigInteger`. There is no corresponding general
library implementation. The observed requirement is mainly bounded integer
literal validation and Java-width arithmetic, not arbitrary-precision public
arithmetic. Prefer a compiler-local checked magnitude parser plus width-aware
primitive operations. Validate arbitrary-length invalid spellings without
overflow or a false successful parse. A full big-integer library should require
evidence that the bounded design is insufficient.

Preserve signed decimal boundaries, the special negated minimum value,
hexadecimal/binary bit patterns, narrow wrapping, unsigned shifts, shift-distance
masking, division overflow, division by zero diagnostics, and casts. Float/double
parsing and raw-bit helpers exist, but constant folding still needs exact
single-precision rounding, NaN, infinities, signed zero, and conversion tests.
`LlvmEmitter` uses `String.format(Locale.ROOT, ...)` for hexadecimal floating
bits and scientific text; use explicit deterministic formatting or verified
library operations, not the locale/format-string subsystem Ironwood excludes.

[`Lexer`](../compiler/src/main/java/ironwood/compiler/lexer/Lexer.java) also uses
`String.stripIndent` for text blocks and nullable boxed characters for escape
decoding. `stripIndent` is not supplied by the current Ironwood `String`.
Implement the exact required normalization helper and test its blank lines,
closing delimiter, indentation, and newline behavior. Keep source spans and
UTF-16 indexing stable while reading UTF-8. Identifier starts are limited to
ASCII letters, `_`, and `$`; continuations also accept `Character.isDigit(char)`,
including non-ASCII digits. Ironwood's existing `Character` supplies the Java 21
whitespace set and Unicode 15.0 digit predicates, so no new lexer Unicode
classification feature is needed. Reuse these operations and preserve the
current `char`-based grammar with differential tests. Java identifier predicates
remain separate work in
[B7](BEFORE_SELF_HOSTING_PLAN.md#10-b7-compiler-local-portability-helpers):
`DocComment` needs `Character.isJavaIdentifierPart(char)` for tag-name scanning
by S6, before S7's Bridge qualified-name validation. Share the character
properties while preserving the documentation scanner's UTF-16 `char` traversal.

Replace regex uses with small purpose-specific scanners where practical:
qualified names, command-line path lists, documentation tags, and LLVM symbol
recognition. Preserve escaping and malformed-input behavior. This does not
require adding a general regex engine. Ironwood's `Files.readString` already
rejects malformed UTF-8, whereas byte-array String construction replaces it;
archive text validation must choose the appropriate behavior deliberately.

Small missing APIs also need explicit replacements: `Arrays.compareUnsigned`
orders probe payload bytes when GUIDs tie in `SharedTraceOrder`;
`Arrays.copyOfRange`, `Integer.toUnsignedLong`, `Byte.toUnsignedInt`, and
`Short.toUnsignedInt` support binary validation and emission;
`Character.toCodePoint` supports `StringPool`, and `Character.toChars` supports
documentation entities. Assign these to compiler-local B7 helpers with exact
bit, range, UTF-16, and ownership contracts. The
[B7 helper inventory](BEFORE_SELF_HOSTING_PLAN.md#101-small-binary-and-text-helpers)
also covers 20 compiler-executed `String.split` calls (17 regex literals and
three host-path separators) and four `String.lines` calls, excluding split calls
inside generated Java. Preserve each split limit and empty-field policy, and
distinguish documentation Unicode line breaks from `lines()` terminators.
Complete helpers before their consuming slice, including name splitting reached
by S1, semantic string handling at S3, and trace ordering at S4; artifact,
documentation, and Bridge helpers follow their respective stages.

### Host services and artifacts

| Service | Evidence and current gap | Recommended treatment |
| --- | --- | --- |
| Subprocesses | `NativeBackend`, `LlvmToolchain`, `MacNativeTools`, Bridge build tools use `ProcessBuilder`/`Process`; no Ironwood equivalent | Start with explicit shell orchestration. For a native driver, follow [B4](BEFORE_SELF_HOSTING_PLAN.md#7-b4-a-synchronous-process-facility): direct argument-vector execution with an absolute executable path in `command[0]`, no PATH search, inherited environment, optional child working directory, noninteractive stdin, merged stdout/stderr redirected to a file, synchronous wait/exit status, and failure cleanup. Executable discovery stays in compiler callers. |
| Filesystem details | Files/path basics exist, but compiler code uses temporary files/directories, executable/readable checks, real paths, no-follow options, and atomic replacement | Inventory exact calls. Reuse `walkFileTree`/directory streams; fill narrowly scoped gaps or let the initial driver perform them. `Files.move` currently has no-replace semantics and is not an atomic-replace substitute. |
| Installation discovery | `StandardLibrary`, `RuntimeLibrary`, `CompilerVersion` use Java code-source/resource locations | Use an explicit installation root passed by the launcher and a build-generated version/identity resource. Preserve supported environment overrides and source-checkout operation. |
| ZIP and CRC | `IronClass`, `IronJar`, Bridge archives use ZIP readers/writers and CRC32; no current ZIP library | Preserve reader compatibility, including existing compressed payloads and existing consumers reading native output. A focused archive facility needs bounds/duplicate/path validation and checksum handling; writer-profile changes require a recorded decision under B6. |
| Hashes | `OptimizedTraceMetadata` and `LlvmEmitter` use MD5 for LLVM GUIDs at S4; `ByteViewIntrinsic.trusted` uses SHA-256 for exact source authority at S3; `NativeBackend.prepareRuntimeObject` hashes runtime headers for its in-process cache at S4; TLS and Bridge inventories also use SHA-256 | Supply exact algorithms through reviewed portable helpers or a narrowly justified native dependency. SHA-256 must be bit-exact before S3 handles ByteView programs, even though full Bridge production waits until S7. Do not replace established identity algorithms with an ad hoc hash. |
| Binary object inspection | `SharedTraceOrder` reads Mach-O/ELF, byte order, unsigned fields, and probe groups | Reuse supported `ByteBuffer` members or explicit byte access; port exact extent validation and ordering. |
| Properties/manifests | Bridge distribution and dependency inventories use `Properties`, JAR manifests, hex formatting | Specify and implement the accepted data formats, including required escaping, continuation, and duplicate rules. Do not assume a naive key/value split is equivalent. |
| Java tool APIs | `BridgeBuildTools` and `BridgeAssembler` use `javax.tools`; export validation uses `SourceVersion` | Invoke selected external `javac`/`javadoc` for Java artifacts and implement the required Java-name checks. Preserve supported JDK selection and existing generation flags. |

The proposed S4 port decision in
[B5](BEFORE_SELF_HOSTING_PLAN.md#8-b5-exact-checksums-and-digests) is to omit
`RUNTIME_OBJECTS` and its cache-key construction from the initial
one-native-link-per-process driver, compiling runtime objects directly. This
removes only cache-related header hashing; ByteView, TLS, and artifact SHA-256
consumers remain. If the cache is retained, preserve header hashing and verify
invalidation. The proposed omission applies to the native port, leaving the
Java bootstrap's cache in place.

The native boundary is a real design gate. General FFI is not currently
available. A future process facility needs ordinary typed frontend/IR support
and an isolated runtime implementation, or an approved smaller dependency;
declaring an arbitrary native method in Ironwood is not an available shortcut.
Redirecting child output to files is a useful initial convention that avoids
two-pipe deadlocks and unbounded in-memory output buffering. Preserve arguments
as arguments, never assemble a shell command from source paths.

The archive details matter:
[`IronClass`](../compiler/src/main/java/ironwood/compiler/IronClass.java) writes
ZIP containers with source payloads using the default compressed entry method;
[`IronJar`](../compiler/src/main/java/ironwood/compiler/IronJar.java) writes
deterministic STORED entries, including class payloads, and reads archives with
strict text validation. A STORED-only ZIP reader cannot consume all existing
artifacts. Preserve the accepted reader behavior, nested payloads, type indexes,
entry points, notices, entry ordering, and failure-preserving publication.
Do not make a new serialized typed-IR format a self-hosting prerequisite.

Here, preserving artifact compatibility means reader interoperability and
unchanged payload/validation contracts. [B6](BEFORE_SELF_HOSTING_PLAN.md#9-b6-archive-codec-and-artifact-compatibility)
proposes evaluating STORED native `.ironclass` and Bridge JAR output alongside
the already-STORED `.ironjar` writer. Native readers must still accept legacy
DEFLATED input, and existing Java readers and JAR consumers must accept the new
output. Any writer-profile change needs a recorded decision in `DECISIONS.md`
and the artifact specifications before canonical output fixtures change,
including its metadata, archive-byte, and identity effects. Changed embedded
`.ironclass` bytes also change the enclosing `.ironjar`. Retain same-writer
determinism; Java/native byte equality is a separate writer-contract decision,
as specified in [B6's verification gate](BEFORE_SELF_HOSTING_PLAN.md#94-verification-and-exit-gate).

The backend is also more than emitting `.ll`:
[`NativeBackend`](../compiler/src/main/java/ironwood/compiler/backend/NativeBackend.java)
queries target layout from Clang, assembles, optimizes, injects optimized trace
metadata, assembles again, generates objects, prepares Linux trace sections,
builds runtime objects, and links. Shared outputs additionally canonicalize
trace groups. Omitting
[`OptimizedTraceMetadata`](../compiler/src/main/java/ironwood/compiler/backend/OptimizedTraceMetadata.java)
or hashing would lose existing behavior even if a hello-world binary ran.
Preserve D132/D133's on-demand traces without adding steady-state bookkeeping.

## 4. Memory management is the primary design gate

The port has two separate obligations: safely manage the compiler's own memory,
and preserve the compiler's proof of safe reclamation in user programs. Neither
can be weakened to help the other compile.

The Java implementation allocates many AST/IR nodes, strings, lists, maps,
optional values, worklist entries, and copied branch states. Nodes and symbols
form shared graphs, and `FunctionAnalyzer.snapshotOwnership` copies several
maps and sets at control-flow boundaries. `SemanticAnalyzer` constructs
successive effect/owned-field analyzers until proofs converge. Keeping all of
this until process exit is legal Ironwood, but may have an unacceptable peak.

Recommended lifetime classification for the pilot:

| Data | Initial lifetime strategy | What must be demonstrated |
| --- | --- | --- |
| Source text, AST, symbols, final IR, diagnostic source spans | Retain through one compiler invocation initially | Complete self-sized workload fits an agreed peak-memory budget; no accidental persistent cache |
| Per-function builders, traversal stacks, formatting buffers | Reuse or reclaim at a proved boundary | Results do not retain reclaimed storage; failure paths preserve ownership |
| Callback objects and captured state | Prefer direct helpers; otherwise allocate with an explicit owner and reuse per pass where state, identity, and reentrancy permit; retain field-stored callbacks for the holder's required lifetime | Allocation counts per AST/IR operation; enclosing/captured-reference retention; safe cleanup on normal/exceptional exits and rejection of freeing still-observable callbacks or captures |
| Branch snapshots and iterative analysis facts | Independent immutable snapshots, then explicit retirement/reuse where provable | Restore/join behavior, identity distinctions, convergence, and bounded high-water growth |
| File/directory/process resources | Close at lexical/driver boundaries, including failures | No descriptor/child/temp-output leak across repeated invocations |
| Cross-invocation caches | Disabled or explicitly bounded for the first native CLI | No unbounded retention; cache invalidation remains correct |

Each named or anonymous callback instance is an ordinary allocation; do not
assume Java lambda caching or allocation elimination carries over to the port.
Measure immediate and field-retained cases separately in S1 and later slices,
including `FunctionAnalyzer` callbacks created during expression lowering.
Include captured-state helper allocations and references in the inventory, and
avoid a new wrapper per node where a direct helper or safe reuse suffices. Apply the existing
[capture ownership rules](MEMORY.md): hidden enclosing/capture fields are aliases
and can mark referenced allocations escaped. Freeing a callback neither frees
its captured objects nor automatically restores permission to free them. A
borrowing consumer must not destroy a caller-owned callback; normal ownership
and closed-world effect proofs must establish every proposed cleanup boundary.

For the first one-shot compiler, retaining shared semantic graphs until process
termination is a defensible temporary implementation choice. Record it
explicitly and measure it. Resource closure is still required.

**Port build policy:** pass `--unfreed=warn` explicitly to every source compile
and native link that builds the Ironwood compiler port, from S1 pilots through
S5 self-builds and the initial one-shot CLI. This is a scoped exception to the
`--unfreed=error` convention used by [`scripts/build.sh`](../scripts/build.sh)
for the standard library and by the project compile/link scripts. Keep those
strict builds unchanged. If a source-only self-build includes the standard
library in the same `warn` invocation, retain a separate strict standard-library
check. Capture the warnings; `off` is not the port's build policy. The mode is
per invocation, so pass it at both compile and link rather than expecting an
artifact to retain it. Preserve the native compiler's public CLI mode behavior
and test user-program safety under all three modes.

This exception permits reported missing-free findings; every mandatory
memory-safety error still fails the build. Do not weaken a reclamation proof to
reduce warning counts. Revisit strict-mode readiness using the S1 measurements
and later self-build evidence; a resident compiler needs S7's separate lifetime
gate rather than inheriting the one-shot retention strategy.

[`@SuppressUnfreed`](MEMORY.md#per-allocation-suppression) exempts the initializer's
tracked allocation at one reference local declaration, including aliases and
fresh factory results. It does not exempt unrelated allocations in factories,
fields, nested graphs, or container contents. Use it only for individually
justified sites, recording them with the diagnostic baseline; it is not the
mechanism for making the whole port pass `--unfreed=error`. As
[MEMORY_MANAGEMENT.md](MEMORY_MANAGEMENT.md#allocations-that-are-not-freed)
explains, returned/stored objects and uncertain control flow can fall outside
the check. The practical warning volume is unknown until S1; even zero warnings
would not prove that the shared graphs are reclaimed or fit the memory budget.

Do not propose an unimplemented arena as if it solved ownership. Current ordinary
array free is shallow, containers borrow elements, and a list of allocations does
not automatically prove safe bulk destruction of an arbitrarily shared graph.
Pool ownership/reuse is also not a universal GC replacement. First test a real
representative graph with today's proofs. If it cannot be reclaimed, retain it
for the invocation or evaluate typed index-based tables at the affected boundary.
Indices are ordinary compiler data, not exposed native pointers. A new general
region facility would be a separate language decision, outside this plan's
recommended initial scope.

[`RejectedFreeEvidence`](../compiler/src/main/java/ironwood/compiler/semantic/RejectedFreeEvidence.java)
contains a concrete GC dependency: weak snapshot keys and a `ReferenceQueue`
retire optional explanation associations. Replace that mechanism with explicit
snapshot ownership/retirement and the existing bounded-budget/fallback model.
Do not emulate weak references, add reference counting for ordinary objects, or
let evidence budget exhaustion change mandatory safety facts. Explain-on and
explain-off runs must preserve primary diagnostics, acceptance, and generated
program behavior. If explicit retirement changes optional truncation behavior,
document and verify that change rather than promising identical GC timing.

The feasibility gate must include nested branch/loop snapshots, generic
inference, temporary borrowers, retained container items, exceptional cleanup,
and multiple refinement rounds. A parser alone cannot establish this result.
Measure wall time, peak RSS, allocation counts where available, retained bytes,
snapshot high-water marks, and native stack depth. Also measure Java/LLVM's cost
of building the port: self-hosting stresses both the compiler being built and
the compiler doing the building.

Existing [collector-cost measurements](EXPLAIN_REJECTED_FREE_VERIFICATION.md#m1d-initial-collector-cost-and-completion)
record Java wall time and sampled heap for smaller workloads and standard-library
analysis. They exclude JVM startup and native linking and do not qualify a full
compiler build. S3 and S4 must establish that capacity separately from the
native port's own memory budget.

## 5. Migration milestones and exit gates

All new paths and commands in this section are proposals, not existing tooling.
Use `compiler/src/main/ironwood/ironwood/compiler` for the maintained port,
alongside the Java bootstrap. Use ignored build/scratch directories for pilot
output. Keep development changes small and independently reviewable, following
the repository's prescribed Git workflow.

### S0. Establish the baseline and comparison harness

[B0a](BEFORE_SELF_HOSTING_PLAN.md#b0a-inventory-and-java-baseline) details this
stage's compatibility inventory, frozen Java fixtures, resource measurements
and budgets, and hash-iteration ordering audit. These are shared S0 deliverables;
S0 remains the baseline gate definition, including the J0 qualification
requirements below.

Freeze a known Java compiler revision as `J0`, the standard library/runtime revision,
LLVM 23 toolchain identity, flags, platform, and fixtures. Inventory Java member
uses by module and classify each as directly supported, local rewrite, library
extension, or host service. Complete the call-level inventory before declaring
any large module portable; the counts above are only a starting map.

Pin J0's exact JDK vendor/build and seed JAR hash, launcher command, and numeric
JVM limits: maximum heap (`-Xmx`) and thread stack (`-Xss`), with initial heap and
collector settings recorded. The current [launcher](../bin/ironwoodc) reads
[`conf/jvm.options`](../conf/jvm.options), whose shipped contents leave JVM
defaults active; use that existing mechanism for the qualification installation
and save its exact contents with the evidence. Apply the same profile to direct
Java harness invocations. Clear inherited Java option variables or record their
values, and verify the effective JVM settings so environment overrides cannot
silently change the limits. Use a prebuilt frozen J0 rather than including an
automatic seed rebuild in the measured command.

Choose numeric wall-time, heap, and stack budgets for supported development
machines before evaluating the growing port. Record fresh-process compile/link
wall time, peak used Java heap and peak process RSS separately, and maximum
observed Java frame depth under the pinned `-Xss` and OS limits. Record stack-byte
high-water use where measurable. State the measurement method and sampling
interval; sampled heap peaks are lower bounds, and observed Java frame depth is
not a byte high-water measurement. Include deep control-flow/type/ownership
inputs to test completion within the stack limit. Separate J0 measurements from
LLVM/Clang child costs while also recording total pipeline time and memory.

Add focused differential entry points for tokens/spans, AST shape, diagnostics,
typed IR, and final LLVM. Define stable structural output rather than relying on
record `toString` or object addresses. Keep semantic IR identities and ordering
visible. Add a way for the harness to invoke either compiler without Java
in-process APIs. Keep the current harness working throughout.

Compare partially ported phases through fixture outputs or a test-only protocol.
Do not assume Java and Ironwood can share live AST/symbol graphs through an
in-process interface. Their object and lifetime models differ, and the Java
Bridge is not a general compiler-object interop mechanism.

Exit: a selected corpus has captured reference outcomes and repeatable resource
measurements; the harness detects an intentionally changed diagnostic, missing
IR edge, and generated-output mismatch. Select numerical performance/memory
budgets from these measurements before evaluating the port against them. The
J0 launcher profile, numeric limits/budgets, and measurement procedure must be
recorded before S0 exits; unspecified JVM defaults do not satisfy this gate.

### S1. Prove portability and memory feasibility

[B0](BEFORE_SELF_HOSTING_PLAN.md#3-b0-inventory-and-the-s1-pilot) details this pilot's prerequisite work and evidence; S1 remains the gate definition.

Port source spans, diagnostics, tokens, enough AST data, the lexer, and then the
parser using supported syntax and actual library APIs. Independently port a
small but representative ownership-snapshot/refinement workload from the current
implementation. Exercise record equality, identity maps, nested traversal,
independent copies, bit-set copying, and explanation snapshot lifetimes.

Include excluded-syntax rewrites, text-block indentation, nullable escape
results, numeric boundaries, and source failures. Scale input up to representative
compiler source volume and difficult control-flow/generic shapes; ordinary
source length alone is not enough. Include immediate and field-retained callback
cases, with allocation counts and paired safe/unsafe capture-cleanup evidence.

Measure missing-free diagnostics emitted while compiling and linking the
Ironwood pilot under the section 4 `warn` policy, initially with the Java
bootstrap. Retain full logs and report counts per command, plus unique source
sites so repeated compile/link reports are visible without double-counting sites.
Classify findings by intentional invocation-lifetime retention, temporary cleanup
work, or diagnostic/proof limits; inventory justified local suppressions
separately. Compare counts across input scales and revisions alongside measured
memory use, since warning counts do not measure retained bytes.

Exit: Java/native tokens, AST structures, and diagnostics agree on the selected
positive and malformed inputs; snapshot operations agree; valid reclamation is
accepted and nearby invalid reclamation rejected; memory/stack/time measurements
meet the agreed pilot budgets, with missing-free counts and their classifications
recorded. Zero missing-free warnings is not an exit requirement. If the snapshot
model needs new ownership semantics, stop the broad port and resolve that design
first.

### S2. Prepare the shared representations and narrow dependencies

Complete the AST/IR data model, explicit value equality/hashing, immutable
snapshot storage, primitive constant representation, numeric helpers, and
required collection operations. Replace reflective IR walking in the Java
baseline first with verified explicit traversal, then port the same traversal.
Keep each semantic change separate from translation and compare against the
original behavior. Introduce host-service boundaries around processes,
installation discovery, archives, and output publication.

Implement any required intrinsic/runtime support in the Java bootstrap before
the native compiler source depends on it, and implement the corresponding typed
support in the port. Keep the original reference revision for comparisons; if
prerequisites require a newer seed, record its reviewed revision and complete
input manifest explicitly. Freeze that buildable seed again before S5. The
compiler being bootstrapped must not depend on a feature only it can compile.

Exit: every AST/IR variant needed by the port has explicit data/dispatch/traversal
coverage; copied state cannot change through its mutable builder; value keys
and identity keys remain distinct; integer/float edge cases and deterministic
ordering pass focused comparisons. Required library extensions have behavioral,
ownership, allocation, and provenance review.

### S3. Port semantic analysis in dependency order

Start with type/symbol representation, resolution, lexical scopes, hierarchy,
members, access, initialization, constants, and invocation planning. Then port
generic inference and source-precise typing, function lowering and control flow,
escape/effect/return-origin analysis, owned fields/arrays, pool and container
borrows, exception cleanup, and diagnostic evidence. Preserve provisional
binding and final refinement order; do not replace it with a simplified pass.

Keep `FunctionAnalyzer` work divided by a named behavior and its callers. Read
and follow the [regression pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#before-a-substantial-change)
for every change touching shared facts. Stage the Bridge-specific analysis
after its ordinary proof dependencies, but retain all ordinary safety rules in
the first native core.

Exit: each migrated slice agrees on accepted and rejected programs, diagnostic
locations, relevant typed IR, and proof outcomes. The accumulated native frontend
analyzes the chosen compiler source subset with mandatory safety enabled.
No compiler-only exemption, unknown-effect assumption, or diagnostic downgrade
is permitted to clear this gate.

J0 must also compile and link the accumulated Ironwood port plus its required
library closure, using representative pilot entry points, the section 4 `warn`
policy, and S0's pinned JVM profile. Track wall time, Java heap, and stack evidence
at each substantial increase in the port and at S3 exit, including deep-input
cases. The current accumulated closure must fit the recorded budgets without
heap/stack exhaustion. A capacity failure blocks progression until the seed or
workload is fixed, or a revised supported-machine budget is explicitly recorded
and the gate rerun; do not defer it to S5.

### S4. Complete native code generation and a source-only compiler

Port primitive specialization, initialized-type/enum specialization, field
forwarding, unread-store elimination, closed-world pruning,
[`SelectiveInlining`](../compiler/src/main/java/ironwood/compiler/backend/SelectiveInlining.java)
(invoked by `LlvmEmitter`), and LLVM emission.
Port trace finalization, exact GUID hashing, target handling, and necessary
binary utilities. Initially a checked shell driver may execute LLVM/Clang and
manage temporary files, with native Ironwood modes doing compiler-specific
transformations before and after `opt`.

An explicit bootstrap source mode may consume the full source closure and pinned
standard-library sources, postponing ZIP support. It must not silently pick an
installed archive or Java-generated semantic result. This mode is an interim
capability, not a replacement for the public class/archive CLI.

Exit: the native core builds and runs representative programs at O0 and O3,
preserving safety, exceptions, traces, specialization, and native output.
Compare `SelectiveInlining` candidate sets and emitted `alwaysinline` attributes
between Java and native compilers with `--selective-inlining` on and off,
preserving the existing eligibility rules, budgets, and exclusions. Use its
focused structural/native regressions and
[Stage 3 benchmark history](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-3-independent-specialization-and-inlining-candidates)
to guide verification of this hot-lowering policy.
Compare generated program machine code and deterministic benchmarks where hot
lowering differs. All compiler-specific work on this route is native, even if
the outer driver is shell.

Before S4 exits, J0 must compile and native-link the complete source closure
planned for S5, including the pinned standard-library/runtime inputs and the
same entry point, target, and optimization profile. Record J0 wall time, peak
Java heap, and stack evidence against S0's budgets, alongside LLVM/Clang costs.
The complete build must succeed within those limits and its native compiler must
run the selected corpus. A successful subset or front-end-only analysis is not
this gate. Requalify if the seed, closure, JVM profile, or build settings change.

### S5. Demonstrate the bootstrap fixed point

Let `J0` be the frozen Java bootstrap, and `S` the same complete, immutable
Ironwood compiler source closure plus its pinned library/runtime inputs:

1. Reproduce the S4-qualified `J0(S)` build of native compiler `I1` through the
   pinned native pipeline and JVM profile; its capacity is already an S4 exit
   requirement.
2. `I1(S)` builds `I2`, without invoking the Java compiler implementation.
3. `I2(S)` builds `I3`, again without Java compiler implementation code.
4. `I1`, `I2`, and `I3` compile the same focused acceptance/rejection corpus;
   compare outcomes, diagnostics, typed/LLVM output, and native executions.
5. Compare `I2` and `I3` build artifacts under identical inputs and paths.

All three self-builds use the section 4 `--unfreed=warn` policy explicitly at
compile and link. Save and compare their missing-free diagnostics as part of
the bootstrap evidence, separately from the acceptance/rejection corpus's modes.

Start on the local supported host. Pin source ordering, flags, target/data
layout, tool versions, standard-library resolution, and all generated inputs.
Require exact deterministic LLVM equality where possible. Class/archive
comparison must distinguish deterministic logical contents from compression
metadata; native comparison must isolate only documented platform differences
such as signatures or build-path metadata. Never normalize away instructions,
ownership facts, symbol identities, or unexplained differences. Investigate
every exception to byte equality and record its evidence.

Exit: all three generations work; `I2`/`I3` reach the required reproducibility
checks; self-build resources fit the agreed budgets. Successful self-compilation
alone does not prove semantic correctness or eliminate a shared compiler bug.
Keep the independent Java differential and explicit negative safety corpus.

### S6. Reach complete command-line and artifact parity

Port source/class/archive discovery and `ironjar`, preserving reader compatibility
with existing format-1 source-bearing artifacts and reconstruction-time
validation. Implement verified ZIP/CRC and publication behavior. Complete native
host services or retain an
explicitly accepted shell-driver boundary; do not make a full process library a
prerequisite if the smaller convention suffices. Port `irondoc` using the same
parser/model and purpose-specific text helpers, including B7's shared Java 21
identifier-part predicate. Require equivalent tag boundaries and diagnostics
before S6 exits; this predicate cannot wait for the S7 Bridge validators.

Exercise Java-written artifacts read by native tools, native-written artifacts
read by Java tools, both compilers' class/archive round trips, and invalid
artifacts. Check CLI exit codes, diagnostic streams, flags, source-path/classpath
resolution, explicit main selection, `--unfreed`, rejected-free explanation,
tool discovery, optimization reports, runtime object compilation or the recorded
cache omission, and output failures.

Exit: ordinary installed compiler, archiver, and documentation workflows work
without the Java compiler jar. Rebuilding the native compiler and its library
artifacts remains reproducible. Earlier source-only bootstrap restrictions can
be removed only after this gate.

### S7. Port Bridge production and preserve IDE consumers

Port Bridge models/proofs, admission, native-entry transformations, generators,
packaging, assembly, and distribution workflows after their prerequisites. Keep
the generated Java/C API and lifetime protocols stable. Replace in-process Java
tool APIs with selected JDK subprocesses, including argument-file handling where
needed and the existing Java release/diagnostic flags.

[`BridgeProducerInputs`](../compiler/src/main/java/ironwood/compiler/BridgeProducerInputs.java)
currently requires `ironwood/compiler/Main.class` and a compiler resource in a
class-directory/jar inventory. A native producer cannot satisfy that identity
scheme unchanged. Design a versioned native producer/build manifest that binds
the actual compiler, generation inputs, runtime, and dependencies. Specify
Java/native producer pairing and assembly compatibility; preserve fail-closed
identity checks instead of inventing a fake `Main.class` entry.

The [language server](../ide/langserver/src/main/java/ironwood/lsp/AnalysisEngine.java)
calls `CompilerPipeline.analyze` in-process, and its source resolution and symbol
queries also use Java lexer/parser/AST APIs. Keep it working during migration.
Before retiring the maintained Java frontend, move these consumers to a native
analysis interface that supports unsaved source text, source identities,
diagnostics, and symbol queries. A short-lived native worker per request avoids
requiring a reclaimable daemon immediately; measure latency and preserve editor
behavior. A resident native service requires a separate repeated-request memory
gate. Do not leave two permanently diverging semantic implementations.

Exit: selected Bridge families and generated consumers pass focused proof,
artifact, failure, and native lifetime checks; producer identities and notices
are complete; IDE diagnostics and symbol operations use the maintained compiler
semantics. The Bridge's external JDK requirement remains explicit.

### S8. Qualify and make the native compiler the default

Repeat focused bootstrap, artifact, native-execution, resource, and installation
checks locally for macOS ARM64, Linux ARM64, and Linux x86-64. Platform
qualification is required before claiming replacement on that platform; a
translated or cross-target build is labeled accurately. Do not add hosted
three-platform development builds.

Update launchers, `scripts/build.sh`, IDK assembly, smoke paths, tool-version
reporting, and documentation. The current [IDK packager](../scripts/package-idk.sh)
requires a complete bundled JDK for Bridge work, so self-hosting alone does not
authorize deleting it. Distinguish the JVM-free core compiler from optional Java
tools and the chosen IDK distribution contents.

Exit: explicit maintainer acceptance, all platform claims supported, a
documented clean-room rebuild from the recorded seed, and a tested fallback to
the last accepted Java bootstrap/native release. Archive the bootstrap source,
build recipe, input hashes, notices, and artifacts before reducing duplicate
maintenance. Preserve the ability to reproduce the seed. A release's final full
suite is run only when explicitly requested or as part of a human-requested
release, following repository policy.

The critical path is S0 -> S1 -> S2 -> S3 -> S4 -> S5. Artifact/host-service work
can progress alongside independent core slices after contracts are established,
but S6 and S7 cannot be omitted from a claim of complete tool replacement.
Schedule the work by exit evidence, not file conversion counts. Estimate total
effort after S1 reveals memory behavior and actual review throughput; the
semantic subsystem makes a credible promise of a quick bulk translation
inappropriate.

## 6. Verification plan and protected contracts

These are future implementation checks, not tests performed for this document.
Use exact registered selections from `scripts/test.sh --list` and
[`LOCAL_TESTING.md`](LOCAL_TESTING.md). The current script builds and invokes
Java tests against Java APIs; it does not automatically test a native replacement.
S0 must adapt selected fixtures to a compiler-neutral command/protocol and add
native structural outputs for assertions previously made on Java objects.

| Changed contract and consumers | Paired or equivalent cases to retain | Evidence required |
| --- | --- | --- |
| AST/IR value and identity rules; type resolver, inference, all proof maps | Equal type keys versus distinct equal-looking allocation nodes; immutable snapshot versus later builder mutation | Structural comparisons, correct deduplication/convergence, identity-key tests |
| Explicit IR traversal; array descriptors, pruning, Bridge roots | Reachable nested array/constant/field retained; unrelated unreachable code removed | Variant coverage, typed IR, LLVM, native array/cast and reachability fixtures |
| Type-pattern dispatch rewrites; CFG renaming, borrow dispatch, Bridge analyzers | Every relevant variant, including no-op/rejected cases; selector evaluation, null behavior, and true/false guards where present | Per-consumer Java/native behavior and IR agreement; adding an untreated variant or removing a treatment fails the shared coverage check |
| Compiler-local callback interfaces; lowering, CFG renaming, specialization, documentation | Immediate versus field-retained callbacks; noncapturing versus captured state; normal/exceptional exits and reentrant use | Same results and invocation order; measured callback allocations; accepted independent cleanup and rejected frees of observable callbacks/captures under every unfreed mode |
| Effects, borrowing, owned fields, pools and containers | Same-pool release versus wrong-pool release; independent return versus input publication; clear versus escaped iterator/item | Safe acceptance and unsafe rejection under every unfreed mode, source/class/archive parity |
| Cleanup and exceptions; streams, constructors, deferred operations | Normal and exceptional exits, partial construction, nested `finally`/`defer`, multiple cleanup failures | Ordered effects, primary/secondary diagnostics, native allocation/resource checks |
| Constants/text; lexer, folding, emitter, diagnostics | Min/max/out-of-range literals, shifts/casts, NaN/signed zero, valid/malformed UTF-8 and text blocks | Exact tokens/spans/constants and compiled behavior; Java oracle only for Java-compatible behavior |
| Explanation evidence | Explain on/off, exhausted budgets, retired/restored/joined snapshots | Same primary safety verdicts and program output; bounded optional storage and truthful fallback |
| Host/artifact services | Missing/nonzero tool, large output, paths with spaces, malformed/duplicate/compressed archive entries, write failure | Correct exit status, preserved previous outputs, cleanup, cross-reader compatibility |
| J0 build capacity | Growing S3 port and deep-shape inputs; complete S4 compiler/library closure through native linking | Pinned JDK/JVM profile; J0 wall time, heap, and stack evidence within recorded budgets; separate LLVM/Clang costs; full closure qualified before S5 |
| Bootstrap and scale | Real compiler/library closure plus control-flow/generic stress; repeated worker processes | Generation and missing-free diagnostic comparison under the port's `warn` policy, classified S1 warning counts, wall time/RSS/stack measurements, no hidden Java compiler dependency |

Useful existing exact test names include:

- `cooked and raw text blocks normalize decode and pool`
- `minimum integer literal is accepted`
- `numeric literal and conversion failures have deterministic diagnostics`
- `reference generic types retain substitutions in typed IR`
- `primitive generic arguments specialize native value shapes`
- `generic calls preserve conservative safe-free summaries`
- `exact array tests and casts lower through descriptor identity IR`
- `pool release helper proofs preserve mandatory safety`
- `pool release helper proofs survive artifact reconstruction`
- `rejected-free evidence snapshots retain identity and enforce storage limits`
- `rejected-free evidence limits preserve pipeline safety and truthful fallback`
- `caller-owned library results survive source class archive and tree-shaking round trips`
- `Java Bridge producer inventories preserve content and reject incomplete inputs`
- `Java Bridge shared traces preserve records under deterministic root ordering`

This is a starting selection, not an instruction to run every name on every
change. Add focused regressions for new contracts and select existing consumers
when their shared machinery changes. Compare normal and explanation-enabled
diagnostics, including notes and source spans. Retain negative tests even when
the port self-compiles; the compiler's own source does not exercise every unsafe
program. Fix newly discovered baseline bugs in separate reviewed changes with
explicit expected outcomes, instead of copying unsafe acceptance for parity.

For performance, separately measure compiler throughput/resource use and the
runtime performance of programs it generates. Native execution does not
guarantee a faster compiler: allocation retention, string copies, collection
layout, and fixed-point work can dominate. Do not accept slower generated hot
paths as an incidental porting cost. When hot lowering changes, inspect O3
machine code and run the relevant deterministic benchmark before acceptance.
Do not constrain inlining or specialization just to make the compiler binary
smaller; executable size is not a project objective.

## 7. Decisions to resolve before implementation and cutover

The plan recommends these defaults, subject to the indicated evidence gates:

1. Preserve the Java-shaped language and translate excluded idioms. Do not add
   records, streams, GC, reflection, or Java collection compatibility for the port.
2. Target a one-shot native compiler first; retain shared invocation graphs
   initially, then optimize measured hotspots with proved lifetimes. S1 must
   establish that this is practical before broad translation.
3. Use a small shell driver for the first bootstrap. Decide the later native
   process/filesystem API from concrete call requirements, including whether a
   focused native dependency is smaller and safer than a public subsystem.
4. Preserve `.ironclass`/`.ironjar` reader compatibility in both directions.
   Select an archive implementation only after reviewing compressed-input
   requirements, licensing, and validation. Record any writer-profile change,
   including STORED `.ironclass` or Bridge JAR output, in `DECISIONS.md` and the
   artifact specifications before changing canonical output fixtures, following B6.
5. Retain the Java bootstrap and independent test harness until fixed-point,
   full tool parity, IDE transition, and qualified platform gates pass.
6. Preserve source/license provenance through translation. Read
   [LICENSE_MECHANICS](LICENSE_MECHANICS) and apply the
   [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review)
   to every new or changed API. New OpenJDK-derived helpers need exact covered
   upstream provenance and their required headers/notices; do not copy JDK
   implementation code into default-licensed compiler files.

No new accepted language decision is made by this assessment. Record accepted
host-service, memory-lifetime, artifact-identity, and cutover conventions in
`DECISIONS.md` when those milestones are selected. Synchronize `COMPILER.md`,
`LANGUAGE_SPECS.md`, `README.md`, `IDK.md`, and affected library/compatibility
documents when behavior actually changes, without marking this proposed work
implemented in advance.

The first deliverable should be **S0/S1 evidence**, not a mass-converted tree:
a working native parser plus a representative ownership/snapshot pilot,
compared against the frozen Java compiler and measured at useful scale. Passing
that gate would justify a full migration with substantially less uncertainty.
