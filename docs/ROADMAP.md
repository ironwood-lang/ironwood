# Roadmap

For the consolidated current support matrix, deferred language/library work,
and permanent non-goals, see [`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md). This file
retains milestone history and the active design checkpoints. The proposed
usefulness-driven library sequence is in
[`STDLIB_ROADMAP.md`](STDLIB_ROADMAP.md).

## Milestone 0 - Skeleton

- **Status:** Completed
- Repository layout, documentation, decision log, Java build, direct lexer/parser,
  source spans, diagnostics, tests, pinned LLVM 23 backend, and macOS/Linux
  hosted-release/package structure

## Milestone 1 - Smallest native program

- **Status:** Completed
- Parse and type-check `public static int main(String[] args)`
- Lower through compiler-owned typed IR to LLVM IR
- Link and execute a JVM-free native program on macOS and Linux
- Exercise successful, parser-error, and type-error paths automatically

## Milestone 2 - Methods and control flow

- **Status:** Completed
- Implemented initialized lexical locals, assignment, `int`/`boolean` values,
  wrapping arithmetic, comparisons, `if`/`else`, `while`, typed returns, method
  parameters, and unqualified static calls
- Added declaration collection, name resolution, scope/type checking,
  control-flow-aware return/unreachable checks, and source-span preservation
- Lowered through compiler-owned basic blocks, typed SSA values, phi nodes, and
  terminators before mechanical LLVM emission
- Added a native multi-method integration program and expanded the primary suite
  from 15 to 44 tests while retaining Milestone 1 behavior and verifying native
  execution at `-O0` through `-O3`

## Milestone 3 - Objects

- **Status:** Completed
- Implemented multiple top-level classes in one implicit package, source-ordered
  instance fields, constructors and implicit default constructors, `new`,
  `this`, reference-typed locals/parameters/returns/fields, `null`, direct
  instance calls, reference equality, and deterministic null-receiver failure
- Extended compiler-owned typed SSA with class layouts, reference types,
  allocation, field load/store, null-check, and direct object-call operations
  before mechanical LLVM lowering
- Added the isolated `ironwood_allocate`/`ironwood_check_not_null` native runtime
  boundary with a documented zeroing allocator that retains ordinary objects
  until source `free` is implemented
- Implemented public, protected, private, and package-private members plus public
  and package-private top-level classes, enforcing private class-local access and
  all meaningful rules in the current single-package, no-inheritance subset
- Added parser, semantic, visibility, typed-IR, runtime, null-failure, native,
  packaging, and `-O0` through `-O3` object coverage; the primary suite now runs
  68 tests while preserving every Milestone 1 and 2 path

## Milestone 4 - Inheritance and interfaces

- **Status:** Completed
- Implemented single class inheritance, multiple interface implementation and
  extension, hierarchy-kind/cycle validation, inherited member lookup, widening
  reference conversions, closed-world reference equality, and `instanceof`
- Added explicit first-action `super(arguments)` or implicit `super()`
  constructor chaining, synthesized constructors, root-to-leaf initialization,
  covariant reference returns, override/interface-obligation validation, and
  inherited `protected` access; inherited field hiding is deliberately rejected
- Replaced the headerless object bootstrap with a descriptor header and
  base-first field layout, and added compiler-owned dense type identity,
  membership metadata, global signature dispatch slots, class/interface call IR,
  and a compiler-emitted null-safe type-test helper
- Used one per-class global signature table for both class and interface dispatch
  rather than separate itables, retaining ordinary one-pointer interface values
- Added exact closed-world devirtualization when every possible concrete receiver
  resolves a call signature to one linkage target, while genuinely polymorphic
  sites remain indirect
- Added parser, hierarchy, override, visibility, conversion, typed-IR, metadata,
  native, packaging, and `-O0` through `-O3` coverage while preserving earlier
  milestone behavior; subsequent javac-like CLI defaults and version reporting
  bring the primary suite to 92 tests

## Milestone 4.1 - Java source organization and compilation sets

- **Status:** Completed
- Added package declarations, single-type and wildcard imports, qualified type
  names, and package-qualified identity across multiple compilation units
- Enforced Java's top-level rules: at most one public type per source, its name
  must match the `.iron` filename, package-private extra types remain legal, and
  the examples use one public type per file
- Completed cross-package public, protected, package-private, and private access,
  including Java's protected receiver restriction
- Added multiple explicit inputs and javac-like automatic dependency discovery;
  `-sourcepath`/`--source-path` default to `.`, map qualified type names to package
  paths, and compile referenced sources transitively
- Added uniform `.ironclass` output for every top-level type, including the entry
  class; `-d` package-structured compilation; canonical `-cp` with compatible
  `-classpath`/`--class-path` aliases; and strict `--link` native mode
  that requires `--main-class`, consumes only compiled classes, and emits no
  class files. Format 1 embeds validated source for final
  closed-world rebuilding; no runtime loading is introduced
- Split all examples into Ironwood-specific
  `src/main/ironwood/org/ironwood/<example>`
  package trees with one public type per file, ignored `target` output, and
  command-printing compile/link/run scripts that use `-cp` and `-O3`
- Published version `0.0.4` as the completed Milestone 4 and 4.1 baseline after
  native build and exact-asset verification on macOS ARM64, Linux ARM64, and
  Linux x86-64; the primary suite runs 100 tests

## Milestone 5 - Exceptions

- **Status:** Completed
- Added `throw`, ordered typed `catch` clauses, and `try`/`finally`, including
  catch-variable scope, nominal superclass/interface matching, conservative
  catch reachability, rethrow, and Java-like abrupt-completion precedence
- Recorded the bootstrap all-object throwable rule, unchecked exception model,
  deterministic uncaught/null behavior, cleanup semantics, and Itanium-family
  native ABI in D026 before observable implementation
- Extended compiler-owned typed SSA with explicit invoke normal/unwind edges,
  exception landing/take operations, exceptional phis, throw terminators, catch
  selection, and duplicated cleanup paths before mechanical LLVM lowering
- Added a language-owned `_Unwind_Exception` wrapper, LLVM
  `__gxx_personality_v0`/`invoke`/`landingpad` lowering, native unwinding across
  calls and constructors, qualified uncaught diagnostics, and a final native
  entry catch without adding C++ object semantics or JVM machinery
- Added parser, semantic, diagnostic, source-path, typed-IR, runtime, native,
  packaged-example, and `-O0` through `-O3` coverage; the primary suite runs 110
  tests while preserving every earlier milestone path

## Milestone 6 - Safe `free`

- **Status:** Completed
- Added `free expression;` syntax and a conservative allocation-identity, alias,
  escape, liveness, and structured-control-flow proof with focused rejection
  diagnostics
- Added closed-world callable escape summaries for direct and devirtualized
  calls, while rejecting uncertain polymorphic calls and unknown provenance
- Added explicit compiler-owned free IR, mechanical LLVM lowering, and the
  isolated `ironwood_deallocate` runtime boundary
- The initial milestone conservatively retained failed-construction storage;
  D083 later superseded that behavior with compiler-generated rollback after
  prohibiting publication of in-progress `this`
- Exception-aware safety covering in-flight native wrappers, caught values,
  rethrows, and pending cleanup paths
- No reachability-based reclamation: omitted or rejected `free` leaves the
  allocation retained and sustained allocation may exhaust memory
- Added parser, semantic, diagnostic, IR, runtime ABI, native, packaged-example,
  and `-O0` through `-O3` coverage; the primary suite runs 120 tests

## Milestone 6.1 - Deterministic destruction and failed-construction rollback

- **Status:** Completed
- Added one unmodified `destructor { ... }` body per class, dynamic
  most-derived selection, derived-to-root chaining, and terminal raw
  deallocation for compiler-accepted object `free`
- Added closed-world typed-IR effect summaries, including active-use class
  initialization, that reject allocating or outward-throwing destructors,
  receiver publication/resurrection, and constructor publication of
  in-progress `this`
- Extended safe reclamation to fresh-or-null factory results, directly owned
  destructor fields, and unaffected structured-flow merges while retaining
  conservative diagnostics for unknown or conflicting ownership
- Added compiler-generated failed-construction rollback that visits
  proven-owned fields in reverse layout order, destroys completed owned
  children, never runs the incomplete receiver's source destructor,
  raw-deallocates that receiver, and rethrows the original exception
- Extended descriptors, callable kinds, typed IR, LLVM helpers, and runtime
  accounting with destructor/rollback entries and
  `System.liveAllocationCount()`
- Audited standard-library backing storage and pool transfers, including
  the earlier `ObjectPool.takeRetained()` API (superseded by D102),
  owned-vs-borrowed `ByteBuffer` storage, and
  terminal container destructors
- Added syntax, effect, ownership, typed-IR/LLVM, live-count, rollback, archive,
  example, and native `-O0` through `-O3` coverage; the primary suite runs 355
  tests

## Milestone 7 - Useful foundations

- **Status:** Completed
- Added invariant one-dimensional primitive/reference arrays with Java-shaped
  types, zero initialization, `.length`, indexed loads/stores, deterministic
  negative-length/null/bounds failures, typed array IR, and native lowering
- Integrated arrays with explicit reclamation: containers may be proven safe to
  free, reference-element stores escape tracked objects, and element loads have
  unknown provenance
- Added pooled immutable String literals; D031 subsequently changed their
  representation and `length()` contract to UTF-16 code units while retaining
  exact UTF-8 `byteLength()`
- Added synchronous standard output through explicit compiler-owned I/O IR and
  an isolated native output boundary; D044 later replaced the temporary static
  helper with `System.out.println(String)`
- Established bundled standard-library source/classes, implicit
  `ironwood.lang`, class-granular closed-world tree shaking, packaged discovery,
  and a Java-style foundations example
- Added parser, semantic, diagnostic, IR, runtime, native, package/IDK smoke, and
  `-O0` through `-O3` coverage; the primary suite runs 133 tests

## Milestone 8 - Pools, data structures, and Java-shaped collection foundations

- **Status:** Completed
- Accept the original author's direct source contribution recorded by D029,
  preserve its brand-neutral provenance, license it under the default
  `MIT OR Apache-2.0` terms recorded by D042, and maintain it directly as
  Ironwood standard-library code
- Add the Java-like expression, numeric, declaration, overload, root-object,
  reference-generic, iteration, exception, mutable-string, and core utility
  facilities required by the ports without introducing a collector, runtime
  class loading, or unrestricted reflection
- Added the initial message-bearing `Throwable`/`Error`/`Exception` hierarchy
  and common unchecked exceptions used by collections; stack traces, causes,
  suppression, serialization, and compiler enforcement of the throwable root
  remained deferred at Milestone 8 (throwable-root enforcement was completed
  later by D046)
- Added explicit invariant reference-sharing generic classes and interfaces,
  exact nested substitution through inheritance and dispatch, generic reference
  arrays, erased native sharing without JVM bridges or runtime type registries,
  and deterministic rejection of raw, unsafe, and deferred generic forms
- Added Java-shaped integer division/remainder, bitwise and shift operators,
  boolean bitwise and short-circuit logic, conditional expressions, assignment
  and update expressions with evaluated-once lvalues, classic `for`, `break`,
  and `continue`. Divide-by-zero throws the bundled arithmetic exception through
  native unwinding; `MIN_VALUE / -1` is poison-free, shifts mask by 31, and loop
  transfers that would cross `finally` are conservatively diagnosed
- Added the complete Java-width primitive model: signed byte/short/int/long,
  unsigned UTF-16 char, IEEE float/double, literal validation, Java widening and
  constant narrowing, unary/binary promotion, numeric casts, width-correct
  compound/update behavior, i32/i64 checked division, masked shifts, and
  zero-initialized arrays for every primitive width. Typed conversion IR and
  poison-free/non-fast-math LLVM lowering are verified at `-O0` through `-O3`
  and across source-path, `.ironclass`, and `.ironjar` workflows
- Added `ironwood.lang.System.identityHashCode(Object)` and overlap-safe
  `System.arraycopy(...)` intrinsics, including exact primitive/reference array
  metadata, runtime validation, and conservative safe-`free` alias handling
- Added Java-shaped mutable static fields and static-final primitive constants,
  initially with constant-only compile-time initialization and no `<clinit>`
  runtime,
  explicit typed global/load/store IR, exact LLVM globals, `Type.field` and
  same-class access, safe-`free` publication rules, and source/class/archive plus
  O0-O3 native coverage; D055 subsequently adds runtime initialization
- Added checked non-parameterized class/interface casts using closed-world
  membership tests, null-preserving success, ordinary caught
  `ClassCastException` failures, safe-`free` allocation-identity propagation,
  and deterministic rejection of disjoint, unchecked generic/type-parameter,
  and array-downcast forms
- Completed the D031 UTF-16 string foundation: one-allocation literal/runtime
  String tails, `CharSequence`, Java content equality/hash/indexing,
  `StringBuilder` construction/growth/mutation/append/snapshots, catchable bounds
  and constructor failures, exact surrogate-aware standard-output encoding, and typed
  intrinsic IR with no backing array for immutable strings
- Added reifiable unbounded generic wildcards for declarations and signatures,
  `instanceof Type<?>`, and erased checked `(Type<?>)` casts. Wildcard identity
  remains in typed IR so reads are Object-bounded, only null may flow through
  wildcard-dependent writes, widening/casts preserve safe-`free` identity, and
  no runtime generic metadata or ABI was added
- Made every unbounded type parameter Object-bound for ordinary instance calls
  and reference equality, preserving Java-shaped `E.equals`, `E.hashCode`,
  `E.toString`, and `E == object` while retaining virtual dispatch and erased
  native pointer layout
- Completed all seven generic/primitive list targets plus fixed-domain
  `ByteMap` and ASCII `CharMap`, with reusable helpers, deterministic reclamation
  of replaced array backings, value semantics, iterator removal, and native
  `-O0` through `-O3` coverage
- Completed primitive-key, value-key, identity-key, insertion-ordered, and
  copied-character-sequence hash maps with pooled entries, collision/rehash
  coverage, reusable iterators and current keys, deterministic reclamation of
  superseded bucket arrays, Java-shaped value/identity semantics, and native
  `-O0` through `-O3` coverage
- Completed value, identity, linked-order, int, and long set adapters with
  reused iterators/primitive holders, then added a heap-only
  `ironwood.nio.ByteBuffer` with checked state and safe-`free`-visible shared
  wrapping as the final buffer-map prerequisite
- Completed the heap-backed `ironwood.ds.ByteBufferMap<E>` surface with copied
  array, ranged-array, and remaining-buffer keys, pooled entries, reusable
  iteration, native `-O0` through `-O3` coverage, and deliberate omission of
  direct-buffer and GC-only APIs
- Implemented the object-pool APIs first under standard-library package
  `ironwood.pool`, using
  explicit `ObjectBuilder` implementations and omitting reflection- and GC-only
  APIs
- Implemented the data-structure APIs under standard-library package `ironwood.ds`
  in dependency order: utilities,
  primitive and generic lists, maps, linked variants, sets, character-sequence
  maps, and finally heap-buffer maps
- Preserved single-threaded reusable-object/iterator behavior and validated native
  steady-state allocation invariants. Added a closed-world exclusive-private-
  array summary and detach proof so replaced pool/list/map backings are freed,
  while active pooled nodes and multi-array segments retain reusable capacity
- Added deterministic jar-like Ironwood class archives accepted on the compile-time
  class path, with lazy class-granular closed-world loading and bundled license
  metadata; archives must not enable runtime class loading
- Translated the contributor's original behavioral tests to native Ironwood
  fixtures and covered the adaptations at `-O0` through `-O3`, package/IDK
  smoke, and relocated installation workflows
- Added the `System.allocationCount()` diagnostic and proved representative
  pool/list/map/set/buffer-map operations make zero language allocations after
  warmup. The primary suite runs 204 tests; a fresh local macOS ARM64 host
  package and self-contained `0.0.5-dev` IDK both pass their relocated smoke
  suites. Cross-platform CI and release assets remain unverified.

## Post-Milestone 8 entry-point update

- Replaced the bootstrap-only no-argument entry form with the required
  `public static int main(String[] args)` signature.
- Added a native `argc`/`argv` bridge that excludes the executable name,
  decodes UTF-8 arguments into immutable UTF-16 Ironwood strings, and returns
  the source method's `int` as the process exit status.
- Migrated the existing examples and native fixtures and added a dedicated
  command-line-arguments example covering spaces and supplementary Unicode.
  The primary suite now runs 205 tests.

D124 later adds the classic Java `void` return form while retaining this
String-array entry shape and the native `int` status extension.

## Post-Milestone 8 Java-shaped standard-output update

- Replaced the temporary static output helper with the implicitly visible
  `ironwood.lang.System.out` field and `ironwood.io.PrintStream` type.
- Added a typed static-final immortal-object initializer. The singleton is
  emitted in the native image, carries the normal `PrintStream` descriptor,
  creates no language allocation, and cannot pass the safe-`free` proof.
- Preserved the existing deterministic UTF-16-to-UTF-8 stdout behavior behind
  renamed `IrPrintStreamPrintlnInstruction` and `ironwood_stdout_println`
  boundaries. No JNI, JVM bootstrap, collector, or general FFI was added.
- Migrated every example and native fixture, added an exact tracked HelloWorld
  fixture, verified stdout at `-O0` through `-O3`, and added a negative
  resolution test for the removed helper. The primary suite now runs 207 tests.

## Post-Milestone 8 source-level object-model completion

- **Status:** Completed
- Completed concrete/abstract/final class and method semantics, transitive
  abstract obligations, Java-shaped overriding, instance field/initializer-
  block order, blank-final definite assignment, and owner-qualified field
  hiding.
- Completed `this`, `this(...)`, `Outer.this`, `super(...)`, direct superclass
  field/method access, and permitted direct-interface default selection.
- Added static nested classes, member inner classes, nested interfaces, local
  classes, and anonymous classes with exact enclosing instances, explicit-final
  local/parameter declarations, effectively-final capture, same-nest private
  access, deterministic nominal identities, and safe-`free` escape edges.
- Added interface constants and abstract, default, static, private-instance, and
  private-static methods with class-wins, most-specific-default,
  re-abstraction, and unrelated-default conflict rules.
- Completed bounded reference generics: generic methods/constructors,
  intersection and dependent bounds, `?`/`? extends`/`? super`, receiver and
  argument capture, per-candidate/expected-type inference, diamond, exact nested
  owners, and fixed-arity calls. Varargs were implemented in this phase and
  subsequently removed by D047 to avoid hidden unowned allocations.
- Added source-provable parameterized casts using exact closed-world generic
  views while continuing to reject unprovable unchecked casts and
  non-reifiable `instanceof` targets.
- Enforced `Throwable` for throws and catches, allowed bounded throwable type
  variables in `throw`, and rejected generic exception classes plus type-
  variable/non-reifiable catch targets.
- Staged arbitrary qualified anonymous binding after callable signatures so the
  primary is planned once and its exact member owner is frozen before anonymous
  hierarchy/layout analysis. Preserved separate lexical and superclass
  enclosing operands and Java evaluation/null-check order.
- Carried the completed model through typed IR, native dispatch and
  devirtualization, capture-aware safe-`free`, and reconstruction from source,
  class directories, individual owner `.ironclass` files, and `.ironjar`
  archives. Lexical types remain owned by the top-level source artifact rather
  than becoming independently addressable classpath entries.
- D046 records the accepted semantics and exclusions. This update introduced no
  standard-library API or runtime class-loading mechanism. Validation covers
  positive and rejection behavior, native `-O0` through `-O3`, and artifact
  round trips; no new cross-platform release result is claimed here.

## Post-object-model Java feature direction

- **Status:** Accepted roadmap classification; Feature 58 implemented by D049,
  the current Feature 72 design implemented by D051, Feature 73 implemented by
  D054, Feature 69 implemented by D055, Feature 65 implemented by D056, and
  Feature 61 implemented by D057, Feature 74 designed by D058 and implemented
  by D062, Feature 75
  recorded as implemented and Feature 77 excluded by D059, Feature 55
  implemented by D060, Feature 76 implemented by D061, Feature 51 implemented
  by D063, Feature 60 implemented by D064, Feature 78 implemented by D065, and
  the final Java SE 26 language surface audited as Features 79–100 by D066,
  with Feature 100 implemented by D074, Feature 66 implemented by D075, and
  Feature 90 implemented by D076, Feature 92 implemented by D077, and Feature
  89 implemented by D078, Feature 83 implemented by D079, Feature 86
  implemented by D080, and Feature 105 implemented by D081
- D048 introduced public feature status, and the comparison now distinguishes
  ✅ implemented support that feels like Java at the source level, ❌ named Java
  mechanisms Ironwood currently chooses not to support, ⏳ planned or committed
  work not usable yet, and 💡 implemented and documented Ironwood-native
  alternatives to the exact Java mechanism. A manual rewrite using other
  features does not change an excluded feature's status, and an unimplemented
  idea does not qualify as 💡.
- D064 completed the prior numbered ⏳ set with a mandatory built-in
  `@Override` directive. D065 implements the subsequently selected Feature 78
  with cooked and raw text blocks. D066 adds the documentation-only Java SE 26
  audit without changing behavior or status to pending.
  D067 confirms the selected audit exclusions, separates `volatile` as Feature
  101, and leaves eight audit items open for a later promotion decision.
  D068 removes `assert` from Feature 90 and confirms it as excluded Feature 102
  without changing that open set.
  D069 commits Features 83, 92, and 100 as unranked pending work; confirms
  Features 99 and 103 as excluded; and separates hexadecimal floating-point and
  allocation-failure decisions as still-open Features 104 and 105. It selects
  no next implementation target.
  D070 commits Feature 105's bounded immortal allocation-failure design as
  unranked pending work and leaves Features 86, 89, 90, 101, and 104 open. It
  likewise selects no next implementation target.
  D071 commits Features 86, 89, 90, and 101 as unranked pending work, leaves
  Feature 104 as the sole open promotion decision, and selects no next
  implementation target.
  D072 separates `instanceof` type patterns, modern non-pattern switch,
  reference type patterns in switch, record/unnamed patterns, and preview
  primitive patterns as Features 66, 91, and 106–108 without promoting or
  selecting any of them.
  D073 promotes Features 66 and 91, returns Feature 101 to excluded, confirms
  Features 106–108 as excluded, and ranks the pending features 100, 66, 90, 92,
  89, 83, 86, 105, and 91 without selecting an implementation target.
  D074 implements Feature 100 and leaves the remaining order 66, 90, 92, 89,
  83, 86, 105, and 91 without selecting a subsequent target.
  D075 implements Feature 66 with named reifiable `instanceof` bindings,
  Java-shaped definite-match scope, allocation-neutral typed aliases, capture
  and safe-`free` integration, and artifact reconstruction. The remaining order
  is 90, 92, 89, 83, 86, 105, and 91, with no subsequent target selected.
  D076 implements Feature 90 with `do`/`while`, evaluated-once enhanced `for`,
  empty and labeled statements, labeled transfers, and inner-to-outer cleanup
  for transfers crossing `finally`. Iterable traversal borrows a
  producer-owned iterator without hidden allocation. The remaining order is
  92, 89, 83, 86, 105, and 91, with no subsequent target selected.
  D077 implements Feature 92 with disjoint union catches, implicitly-final
  bindings, precise final/effectively-final rethrow, existing typed exception
  dispatch, and artifact reconstruction. The remaining order is 89, 83, 86,
  105, and 91, with no subsequent target selected.
  D078 implements Feature 89 with contextual declaration and array-creation
  initializers, exact inferred lengths, left-to-right element evaluation and
  conversion, recursive nested forms, typed array IR, and explicit child-array
  ownership. The remaining order is 83, 86, 105, and 91, with no subsequent
  target selected.
  D079 implements Feature 83 with binary token validation, shared 32/64-bit
  constant decoding, exact two's-complement bit patterns, typed constants,
  switch/static folding, LLVM lowering, and artifact reconstruction. The
  remaining order is 86, 105, and 91, with no subsequent target selected.
  D080 implements Feature 86 with Java-shaped single-member and on-demand
  static imports, owner discovery, accessible field/method/member-type lookup,
  shadowing and ambiguity, ordinary generic overload selection, active-use
  initialization, and artifact reconstruction. The remaining order is 105 and
  91, with no subsequent target selected.
- D049 completes Java-shaped checked exceptions and `throws` declarations for
  methods, interface methods, constructors, generic throws variables,
  overriding, separate compilation, and native execution. Checkedness is a
  source contract over the existing native unwind mechanism; no runtime ABI or
  standard-library file API was added. The full local macOS ARM64 suite passes
  251 compiler tests, including native `-O0` through `-O3` execution and
  source/class/archive round trips; no new cross-platform result is claimed.
- D050's existing-variable try-with-resources experiment added
  `ironwood.lang.AutoCloseable`, but D051 supersedes its syntax and exception
  precedence. `AutoCloseable` remains an ordinary interface. Java's complete
  `try (...)` construct is rejected; source-written `try`/`finally` performs
  cleanup, preserves a pending primary exception, and records later cleanup
  failures in occurrence order. Throwable count/index access avoids a hidden
  result array, and closure remains separate from explicit wrapper-memory
  `free`. The full local macOS ARM64 suite passes 256 compiler tests, including
  native `-O0` through `-O3` execution and source/class/archive round trips; no
  new cross-platform result is claimed.
- D054 completes Feature 73 with first-throw source-trace capture, preserved
  rethrow traces, per-exception primary/secondary reporting, and stable
  qualified callable/basename/line frames through source, class, and archive
  reconstruction at `-O0` through `-O3`. Trace metadata is runtime-private and
  allocation-count neutral, and no public `Throwable` trace API is added. The
  full local macOS ARM64 suite passes 265 compiler tests, and relocated host-
  package and fresh self-contained `0.1.0-dev` IDK smoke tests include the
  expected-failure trace example; no new cross-platform result is claimed.
  D121 later supersedes first-throw capture and the public API omission with
  construction-time snapshots, explicit refresh and `printStackTrace`; see the
  [public trace review](STDLIB_STACK_TRACE_REVIEW.md) for focused verification.
  D122 further adds immutable public frame snapshots and mutable cause
  initialization while leaving mutable trace replacement absent. D132 replaces
  continuous shadow-frame updates with instruction-free pseudo-probe metadata
  and on-demand native unwind, retaining exact traces without ordinary-path
  bookkeeping.
- D055 completes Feature 69 with source-ordered runtime field initializers and
  named-class static blocks, automatic active-use ensures, deterministic
  superclass/default-method-interface prerequisites, reentrant partial-state
  cycles, exact-object failure caching with ordered secondary failures, and
  independent nested-type state.
  Compiler-owned typed IR drives private LLVM state and preserves behavior
  through source paths, class directories, individual `.ironclass` inputs,
  `.ironjar` archives, tree shaking, and `-O0` through `-O3`. The full local
  macOS ARM64 suite passes 267 compiler tests, and the exact host package passes
  relocated smoke tests including the new example. No new self-contained IDK or
  cross-platform result is claimed.
- D056 completes Feature 65 with evaluated-once `byte`, `short`, `char`, and
  `int` selection, constant labels, explicit fallthrough, nearest-target
  `break`/`continue`, one-scope definite assignment, and a dedicated typed-IR
  terminator that lowers mechanically to LLVM `switch`. Source, class-directory,
  individual-class, archive, tree-shaking, safe-`free`, and `-O0` through `-O3`
  coverage preserve the closed-world allocation-free model. The full local
  macOS ARM64 suite passes 273 compiler tests, and the host package includes and
  runs the classic-switch example. No new self-contained IDK or cross-platform
  result is claimed.
- D057 completes Feature 61 with top-level and member final nominal enum types,
  declaration-ordered constructor-backed immortal constants, synthesized
  allocation-free name/ordinal/traversal/lookup operations, and exact enum
  selectors over D056's typed-IR switch. The model preserves interfaces,
  arrays, reference generics, casts, identity, D055 initialization failure, and
  source/class/archive reconstruction without a shared `Enum` base, hidden
  `values()` array, runtime registry, or ordinary allocation. The full local
  macOS ARM64 suite passes 279 compiler tests, and the exact host package runs
  the enum example. No new self-contained IDK or cross-platform result is
  claimed. D122 later adds the shared Java-shaped `Enum<E>` base while retaining
  immortal constants and allocation-free traversal. D124 later adds a fresh,
  caller-owned `values()` array while retaining the allocation-free helpers.
- D058 committed Feature 74 constant-specific enum class bodies as a bounded
  extension of the existing anonymous-class and enum machinery. Each bodied
  constant uses a compiler-owned final subtype with its own layout,
  initialization, and dispatch while its visible field retains the enum type.
  Concrete obligations are checked per constant, and immortal storage,
  allocation counts, initialization failure, enum lookup/switch, safe-`free`,
  closed-world reachability, and artifact reconstruction retain their prior
  contracts. D058 was roadmap design only; D062 fulfills it.
- D059 makes String status explicit. Feature 75 records the implemented finite
  final-program pool for immutable, immortal double-quoted literals. Feature 76
  commits Java-shaped `+`/`+=` concatenation with constant folding and one
  ordinary source-visible result allocation for a dynamic chain, avoiding a
  hidden builder/backing-array allocation and extending allocation provenance
  for safe-`free`. Feature 77 excludes Java's unbounded process-global
  `String.intern()` contract in favor of any future runtime canonicalization
  being an explicit application-owned collection. This is documentation-only;
  the suite remains at 279 tests and no target is selected.
- D060 completes Feature 55 with recursively typed invariant arrays, one visible
  allocation per `new`, exact reifiable descriptor tests/casts, and local
  constant-slot child ownership tracking. It rejects hidden sized child
  dimensions, covariance, and non-reifiable array targets; dynamic indices,
  observable calls, copies, and escaped containers remain conservative for
  safe `free`. Typed IR, LLVM descriptor identity, native `-O0` through `-O3`,
  source/class/archive reconstruction, tree shaking, the full 285-test macOS
  ARM64 suite, and the packaged multidimensional-array example cover the
  implementation. No new self-contained IDK or cross-platform result is
  claimed, and no subsequent feature is selected.
- D061 completes Feature 76 with Java-shaped String `+` and `+=`, exact
  left-to-right conversion for every primitive plus null and object references,
  pooled constant-expression results, and one exact-size ordinary String
  allocation per maximal dynamic chain. Dedicated typed IR and a two-pass
  native operation avoid a hidden builder/backing-array graph while retaining
  safe-`free` provenance. Source/class/archive reconstruction, tree shaking,
  allocation counts, exact Java float/double spelling, and native `-O0` through
  `-O3` behavior are covered by the full 288-test macOS ARM64 suite and the
  packaged String-concatenation example. The exact macOS ARM64 host package
  passes relocated smoke tests including that example at `-O3`. No new
  self-contained IDK or cross-platform result is claimed. At that checkpoint,
  the human selected Feature 74 next and Feature 51 after its completion.
- D062 completes Feature 74 with retained constant bodies, deterministic hidden
  final subtypes, per-concrete-type obligations, inherited enum construction,
  body initialization, and ordinary virtual/interface dispatch over immortal
  storage. Typed IR distinguishes each constant's declared enum type from its
  concrete storage type; LLVM emits the corresponding layouts, descriptors, and
  constructor calls without ordinary allocation. Safe-`free`, initialization
  failures, lookup, switch, source/class/archive reconstruction, tree shaking,
  and allocation counts retain their contracts. Native `-O0` through `-O3`
  coverage is part of the full 290-test macOS ARM64 suite. The exact macOS ARM64
  host package passes relocated smoke tests including the updated enum
  constant-body example at `-O3`. No new self-contained IDK, cross-platform, or
  release-asset result is claimed. The human selects Feature 51 next.
- D063 completes Feature 51 with exact primitive arguments for unbounded class,
  interface, method, and constructor parameters. Closed-world typed-IR
  specialization emits native value layouts, callable bodies, polymorphic
  dispatch slots, and exact array descriptors without wrappers, tags, or a
  runtime generic registry. Explicit bounds remain reference-only; wildcard
  views cannot cross primitive value ABIs; reference-only generic bodies are
  diagnosed. All eight primitives, inference, diamond, generic constructors,
  mixed shapes, equality, source-provable exact casts, direct/virtual/interface
  dispatch, allocation counts, safe `free`,
  source/class/archive reconstruction, tree shaking, and native `-O0` through
  `-O3` behavior are covered by the full 295-test macOS ARM64 suite. The exact
  host package passes relocated smoke tests including the primitive-generics
  example at `-O3`. No new self-contained IDK, cross-platform, or release-asset
  result is claimed, and no subsequent feature is selected.
- D064 completes Feature 60 with the exact built-in `@Override` directive,
  lexed as `@` plus a contextual name and accepted only in method modifiers.
  Every source-declared inherited instance override or interface implementation
  must use it, and a marked non-override is rejected; existing structural
  diagnostics retain precedence. Parser and semantic diagnostics,
  source/class/archive reconstruction, and native `-O0` through `-O3` behavior
  are covered by the full 299-test macOS ARM64 suite. The exact host package
  passes relocated smoke tests including the override-directive example at
  `-O3`. No new self-contained IDK, cross-platform, or release-asset result is
  claimed, general annotations remain excluded, and no subsequent feature is
  selected.
- D065 implements Feature 78 with Java-shaped cooked `"""` layout and an
  escape-free `r"""` Ironwood form. Both normalize line endings, remove
  incidental indentation, remain non-interpolating, and feed decoded UTF-16
  values into the existing finite literal pool. Lexer/diagnostic, pooling,
  source/class/archive reconstruction, allocation-count, and native `-O0` through
  `-O3` behavior are covered by the full 303-test macOS ARM64 suite. The exact
  host package passes relocated smoke tests including the text-block example
  at `-O3`. No new self-contained IDK, cross-platform, or release-asset result
  is claimed, and no subsequent feature is selected.
- D066 audits JLS Chapters 3–18 plus Java SE 26 documentation comments and adds
  Features 79–100. It records implemented ordinary comments, primitive
  semantics, packages/imports, and the closed-world artifact model; makes
  Javadoc and the remaining lexical, declaration, statement, exception,
  constructor, entry-point, modifier, module, class-literal, intersection-cast,
  and implicit-runtime-failure gaps explicit; and accounts for the current
  primitive-pattern preview without adopting it. This is documentation-only,
  leaves the D065 303-test baseline unchanged, creates no ⏳ entry, and selects
  no implementation target.
- D067 originally confirmed Features 79, 81, 82, 87, 88, 91, and 93–97 as ❌ and
  separates `volatile` as Feature 101. Features 83, 86, 89, 90, 92, 99, 100,
  and 101 remain open for a promotion decision but stay ❌ until one is made.
  This is documentation-only and selects no implementation target.
- D068 separates `assert` from the still-open Feature 90 and confirms it as ❌
  Feature 102. The eight remaining promotion decisions are unchanged. This is
  documentation-only and selects no implementation target.
- D069 commits binary integer literals, multi-catch/precise rethrow, and
  catchable ordinary implicit safety failures as unranked ⏳ Features 83, 92,
  and 100. It confirms intersection casts and Java-style octal literals as ❌
  Features 99 and 103, while hexadecimal floating-point literals and catchable
  allocation failure remain open ❌ Features 104 and 105. This is
  documentation-only and selects no implementation target.
- D070 promotes Feature 105 to unranked ⏳ work with one immortal emergency
  error, bounded allocation-free delivery, an exact failing-site frame,
  ownership-safe evaluation order, and explicit repeated-failure and recovery
  limits. The expected implemented status is 💡. This is documentation-only and
  selects no implementation target.
- D071 promotes static imports, ownership-safe array initializer syntax,
  remaining statement/transfer forms, and Java-shaped `volatile` field
  semantics as unranked ⏳ Features 86, 89, 90, and 101. Feature 104 remains the
  sole open promotion decision. This is documentation-only and selects no
  implementation target.
- D072 narrows Feature 66 to reference type patterns for `instanceof`, retains
  Feature 91 for modern non-pattern switch, and adds excluded Features 106–108
  for reference type patterns in switch, record/unnamed patterns, and Java SE
  26's preview primitive-pattern family. This is documentation-only, changes no
  pending rank, and selects no implementation target.
- D073 promotes Feature 66 and Feature 91 to ⏳, returns Feature 101 to ❌,
  confirms Features 106–108 as excluded, and ranks the nine pending features
  100, 66, 90, 92, 89, 83, 86, 105, and 91 by usefulness and dependencies.
  This is documentation-only and selects no implementation target.
- D074 implements Feature 100 with compiler-owned null, array-bounds, and
  nonnegative-length predicates, failure-only Java-shaped exception allocation,
  and the existing source-trace, catch, `finally`, and native unwind machinery.
  The local 306-test compiler suite covers source/class/archive round trips and
  native `-O0` through `-O3` behavior. The exact host package passes relocated
  smoke tests including the new runtime-failures example at `-O3`. No new
  self-contained IDK or cross-platform result is claimed, and no subsequent
  feature is selected.
- D075 implements Feature 66 through shared definite-match analysis for
  boolean expressions and supported statements, typed SSA aliases of existing
  nominal/exact-array tests, capture and allocation-identity integration, and
  source/class/archive reconstruction. The local 311-test compiler suite covers
  native `-O0` through `-O3` behavior, and the exact host package includes and
  runs the new `instanceofpatterns` example. No new self-contained IDK or
  cross-platform result is claimed, and no subsequent feature is selected.
- D076 implements Feature 90 through parser and statement-walker coverage,
  typed SSA/CFG lowering for `do`/`while` and array/Iterable enhanced `for`, and
  cleanup-preserving labeled and unlabeled transfers. The local 317-test
  compiler suite covers native `-O0` through `-O3` behavior and
  source/class/archive reconstruction; the exact host package includes and runs
  the new `statements` example at `-O3`. Iterable traversal creates no hidden
  allocation and borrows producer-owned iterator storage. No new self-contained
  IDK or cross-platform result is claimed, and no subsequent feature is
  selected.
- D077 implements Feature 92 through parser and shared-walker coverage,
  per-alternative validity and reachability, least-common-supertype binding
  typing, precise checked-type flow for final/effectively-final rethrow, and
  existing typed membership/boolean dispatch. The local 323-test compiler suite
  covers native `-O0` through `-O3` behavior and source/class/archive
  reconstruction; the exact host package includes and runs the new `multicatch`
  example at `-O3`. The feature adds no allocation, ownership transfer, runtime
  operation, or native ABI. No new self-contained IDK or cross-platform result
  is claimed, and no subsequent feature is selected.
- D078 implements Feature 89 through contextual parser and shared-walker
  coverage, exact constant-length array allocation, source-ordered conversion
  and stores, recursive nested initializer lowering, and existing constant-slot
  child ownership. The local 329-test compiler suite covers native `-O0`
  through `-O3` behavior and source/class/archive reconstruction; the exact host
  package includes and runs the new `arrayinitializers` example at `-O3`. The
  feature adds no runtime operation, native ABI, hidden array, or recursive
  reclamation. No new self-contained IDK or cross-platform result is claimed,
  and no subsequent feature is selected.
- D079 implements Feature 83 through binary lexer validation and a shared
  width-aware integer decoder used by ordinary expressions, static constants,
  switch labels, and constant folding. The local 334-test compiler suite covers
  native `-O0` through `-O3` behavior and source/class/archive reconstruction;
  the exact host package includes and runs the new `binaryliterals` example at
  `-O3`. The feature adds no IR operation, runtime operation, native ABI,
  allocation, or ownership rule. No new self-contained IDK or cross-platform
  result is claimed, and no subsequent feature is selected.
- D080 implements Feature 86 through import parsing, owner dependency
  discovery, separate field/method/type lookup spaces, ordinary generic and
  overload selection, static constant and lvalue integration, and format-1
  reconstruction. The local 339-test compiler suite covers native `-O0`
  through `-O3` behavior and source/class/archive/tree-shaking round trips; the
  exact host package includes and runs the new `staticimports` example at
  `-O3`. The feature adds no IR operation, runtime operation, native ABI,
  allocation, or ownership rule. No new self-contained IDK or cross-platform
  result is claimed, and no subsequent feature is selected.
- D081 implements Feature 105 through allocation-capable typed invoke edges,
  one compiler-emitted immortal `OutOfMemoryError`, bounded runtime-private
  unwind, trace, and association storage, and source-catch state release. The
  local 345-test compiler suite covers source/class/archive/tree-shaking
  reconstruction, primary/secondary ordering, deterministic recursive-failure
  termination, exact bounded traces, and native `-O0` through `-O3` execution;
  the exact host package includes and runs the new `allocationfailure` example
  at `-O3`. The feature remains ownership-safe and allocation-count neutral on
  failure. No new self-contained IDK or cross-platform result is claimed, and
  no subsequent feature is selected.
- D082 implements Feature 91 through modern switch syntax and shared semantic
  walkers, evaluated-once integral/enum/String/null dispatch, exact-enum
  exhaustiveness, typed result merging, and cleanup-preserving `yield`. The
  local 351-test compiler suite covers diagnostics, typed IR/LLVM,
  source/class/archive reconstruction, exception/finally behavior, and native
  `-O0` through `-O3` execution; the exact host package includes and runs the
  new `modernswitch` example at `-O3`. The feature adds no runtime operation,
  native ABI, hidden allocation, or ownership escape. No numbered feature
  remains pending, no subsequent feature is selected, and no new self-contained
  IDK or cross-platform result is claimed.
- D083 implements deterministic destruction and failed-construction rollback
  through explicit callable kinds, descriptor entries, closed-world effect and
  ownership summaries, dynamic derived-to-root destruction, reverse owned-field
  rollback, and current-live allocation accounting. The local 355-test compiler
  suite covers diagnostics, typed IR/LLVM, source/class/archive reconstruction,
  standard-library ownership, the reclamation example, and native `-O0` through
  `-O3` execution. This refines the implemented Ironwood way under Features 10
  and 11 without changing the 65 ✅, 11 💡, 32 ❌, and 0 ⏳ feature totals.
- D084 makes compiler-owned reusable iterators and primitive holders dependent
  borrows. The compiler preserves concrete helper provenance through interface
  calls, rejects independent helper destruction and post-owner use, blocks
  escaped or uncertain borrows, and accepts completed traversal followed by
  owner destruction. Every `ironwood.ds` iterable now destroys its cached
  iterator, and primitive iterator destructors destroy their cached holder. The
  local 357-test compiler suite adds closed-world borrow diagnostics and native
  `-O0` through `-O3` collection-helper coverage without changing numbered
  feature totals.
- Deliberate exclusions include automatic boxing/unboxing, raw/unchecked
  generics, Java array covariance, general annotations, records, sealed types,
  lambdas/closures/method references, pattern switch, record/unnamed patterns,
  preview primitive patterns, source-level `volatile`, threads/monitors,
  unrestricted reflection/runtime class loading, Java object serialization,
  `Object.clone()`/`Cloneable` machinery, compiler-generated copying, GC
  finalization, Java's runtime `String.intern()` pool, and the D047 varargs
  model. D066 records the exclusions identified by its Java SE 26 audit;
  D067–D085 subsequently classify, split, commit, rank, or implement the evaluated
  candidates.
  Type-owned copy constructors and
  methods, including an ordinary method named `clone()`, remain normal source
  code rather than roadmap cloning features.
- The current compiler still supports ordinary class/interface casts,
  reifiable `G<?>` tests, closed-world source-provable parameterized casts,
  recursively nested invariant arrays with exact reifiable casts/tests, checked
  exception contracts, and explicit safe `free`, plus classic integral/enum
  `switch`, closed-world enums, Java-shaped binary integer literals, and
  single-member/on-demand static imports for fields, methods, and member types.
  Ordinary, cooked-text-block, and raw-text-block String literals are pooled
  once across the final linked program; source String `+`/`+=` concatenation is
  implemented with one ordinary exact-size result allocation for each maximal
  dynamic chain.

## Post-Milestone 8 S0 porting and reclamation foundation

- **Status:** Completed
- Recorded a pre-implementation API, license, provenance, ownership, failure,
  and architecture review for an independently implemented Java-shaped String
  slice; no OpenJDK-derived source was introduced.
- Added UTF-16 `String.substring(int)`, `substring(int, int)`, and
  `toCharArray()` with fresh caller-owned results, exact bounds behavior, and a
  typed one-allocation String-range intrinsic that uses no scratch array.
- Made default `Object.toString()` identity text and
  `StringBuilder.toString()` snapshots compiler-visible fresh results; fresh
  provenance survives ordinary wrappers, source paths, loose classes,
  archives, pruning, and separate link.
- Refined symbolic return and call-escape summaries so distinct local/result
  allocations, return-only aliases, and outward publication remain separate,
  while ambiguous aliases, double free, and post-free observation are still
  rejected.
- Preserved deterministic StringBuilder backing-array destruction and
  failed-construction rollback, including exceptional constructor input.
- Added typed-IR/LLVM, normal and forced-allocation-failure live-count,
  `-O0` through `-O3`, artifact round-trip, package/IDK, license, and runnable
  text-reclamation example coverage. The primary suite runs 359 tests.

## Post-Milestone 8 U1 text-capable command-line slice

- **Status:** Completed
- Classified the U1 source as independent Java-compatible Ironwood code plus
  original typed-IR/native mechanisms; no OpenJDK implementation source was
  inspected, copied, translated, or adapted.
- Added final UTF-16 `String` copy/character constructors, common search and
  comparison, concatenation, copying, `valueOf`, `Comparable<String>`, and
  caller-owned allocation results with non-retaining constructor summaries.
- Added static non-boxing primitive conversion helpers,
  `NumberFormatException`, common `Math` operations/constants, and tested NaN,
  infinity, signed-zero, saturation, and native-math edges.
- Added separate immortal stdout/stderr streams, primitive/Object
  `print`/`println`, flush/error state, one-value environment lookup, LF line
  separation, and realtime/monotonic clocks through inspectable typed IR.
- Preserved the new operations through specialization, dependency scanning,
  closed-world pruning, LLVM exception edges, loose classes, archives, and
  separate linking.
- Added Java 21 differential tests, malformed/failure and live-allocation
  tests, native `-O0` through `-O3`, package/IDK coverage, and the commented
  `examples/echo` acceptance program. The primary suite runs 364 tests.

## Post-Milestone 8 U2 files and minigrep slice

- **Status:** Completed
- Classified U2 as independent Java-compatible Ironwood source and original
  compiler/runtime work; no OpenJDK implementation source was inspected,
  copied, translated, or adapted.
- Added checked I/O exceptions, Java-shaped `Path`/`Paths`, and a focused
  whole-file `Files` surface for strict UTF-8 reads/writes, byte reads/writes,
  metadata, lexical POSIX paths, and familiar host-error categories.
- Added a typed file/path IR family and isolated native ABI with exact language
  allocations, native scratch cleanup, closed-file failure paths, and strict
  UTF-8 validation on supported macOS and Linux hosts.
- Preserved caller-owned path, String, and byte-array results plus borrowed
  arguments through source, loose-class, archive, pruning, and link workflows.
- Added the fully commented `projects/minigrep` application with literal line
  search, `IGNORE_CASE`, stdout/stderr separation, and 0/1/64/74 statuses.
- Added path/file/error/allocation tests and minigrep acceptance coverage at
  `-O0` through `-O3`. At U2 completion, the primary suite ran 368 tests.

## Post-Milestone 8 floating-point parsing follow-up

- Classified the implementation as independent Java-compatible Ironwood code
  plus original compiler/runtime mechanisms in
  `STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`; no OpenJDK source was adapted.
- Added `Float.parseFloat(String)` and `Double.parseDouble(String)` with Java 21
  decimal/hexadecimal grammar, special values, suffixes, whitespace, rounding,
  overflow/underflow, and exception categories.
- Added `IrFloatingParseInstruction` and an isolated runtime conversion ABI.
  Successful parsing borrows its String and uses neither managed nor
  Ironwood-owned native heap scratch.
- Added typed-IR/LLVM inspection, allocation-count checks, Java 21 differential
  cases, exact-halfway and adversarial long inputs, and `-O0` through `-O3`
  native coverage. The primary suite runs 370 tests.

## Standard-library compatibility expansion

- D122 adds the selected Character, numeric, Math, core type, util, IO, NIO,
  path, and filesystem APIs documented in
  [the compatibility review](STDLIB_COMPATIBILITY_EXPANSION_REVIEW.md).
- Java-shaped behavior remains bounded by explicit compile-time exclusions:
  boxed primitives, the legacy File facade, Stream-returning Files operations,
  mutable stack-trace replacement, channels, and deferred thread machinery.
- Focused native and differential checks cover behavior, ownership, allocation
  rollback, source traces, file failures, and the compiler/runtime typed-IR
  boundary. No full-suite or new release-platform result is claimed.

## Near-term design checkpoint

- **Selection:** Feature 91 is the latest implemented numbered feature under
  D082. No numbered feature remains pending, and no subsequent numbered
  implementation target is selected.
- **Feature 91 completed checkpoint:** D082 preserves evaluated-once selection,
  classic fallthrough, arrow non-fallthrough, null behavior, exact-enum
  exhaustiveness, target-aware result merging, cleanup-preserving `yield`,
  explicit ownership, source-artifact reconstruction, and native optimization.
- **Deterministic destruction completed checkpoint:** D083 preserves
  compiler-proven explicit reclamation, keeps resource closing separate,
  forbids resurrection and constructor publication, and rolls back only
  compiler-proven-owned partial state without running the incomplete receiver's
  source destructor.
- **Owned helper borrow checkpoint:** D084 preserves Java-shaped iterator
  source without per-call allocation, ties cached iterators and holders to their
  root collection, and rejects every unproven or observable post-destruction
  path rather than adding unsafe reclamation or lifetime syntax.
- **Standard-library S0/U1/U2 checkpoint:** D085 preserves caller-reclaimable
  allocation results and deterministic owned-child teardown/rollback. D086
  applies that foundation to the text-capable CLI surface, exact typed-IR
  allocation/native-service boundaries, and packaged provenance. D087 adds the
  file-capable slice and standalone minigrep project. D088 completes
  Java-compatible floating-point parsing without Ironwood heap scratch. D094/D095
  deliver U3 streaming, checked wrapper borrowing, and the cat/wc/cp/prompt
  application without selecting a numbered language feature or the next tranche.
  The standalone `projects/minitee` example applies U3 and D096's abstract-call
  borrow proof to stdin duplication, append mode, and deterministic cleanup.
- **Allocation-audit repair checkpoint:** D089 reclaims temporary Object and
  collection rendering state; D090 makes collection builder cleanup complete on
  exceptional exits by preserving path-specific ownership through duplicated
  `finally` lowering, without weakening safe-free rejection. D091 extends that
  proof to `break`, `continue`, and `yield` destinations and loop back edges.
  D092 removes temporary numeric/character formatting arrays and the
  intermediate snapshot from `StringBuilder.subSequence`; each operation
  allocates only its required result and preserves safe failure behavior.
  D093 completes the audit's third repair stage: direct whole-file results,
  zero-snapshot immutable writes, fused path results, bounded native scratch,
  and exception-safe fallbacks where stable snapshots or long spellings require them.
- **Feature 105 completed checkpoint:** D081 preserves D070's one immortal
  `OutOfMemoryError`, null message, non-freeable and allocation-neutral
  identity, bounded runtime-private unwind/association storage, exact
  allocation-site frame, explicit trace truncation, deterministic recursive-
  failure termination, ownership-safe evaluation order, and first/secondary
  failure ordering. Catch-and-retry remains permitted but never guaranteed.

- D098 implements Feature 79's initial IronDocs subset: Javadoc-style block
  comments, declaration association, common tags, selected-member links, and
  deterministic Markdown generation with the `irondoc` command. The first
  authored standard-library reference is `ArrayObjectPool`; its embedded example
  is exercised natively. Full Javadoc conformance and release-package inclusion
  remain deferred. No subsequent implementation target is selected.

## Future semantic and optimization checkpoints

- **Compiled-class representation review:** Replace the source payload in the
  format-1 `.ironclass` bootstrap container only after a stable serialized
  compiler IR and native library ABI are deliberately designed. Preserve final
  closed-world validation and optimization. A future archive tool may package
  `.ironclass` files analogously to Java's `jar` without changing class semantics.
- **Language-aware optimization review:** After objects, dispatch, and the
  first safe-`free` analysis exist, explicitly evaluate which closed-world
  optimizations Ironwood must perform in compiler-owned IR beyond LLVM's
  `-O0` through `-O3` pipelines. Cover reachability/tree shaking,
  devirtualization, escape and stack allocation, bounds-check elimination, and
  safe-`free`-aware transformations, guided by benchmarks rather than
  assumptions.
- **Profile-guided optimization review:** Design an opt-in workflow that builds
  an instrumented native program, gathers representative execution profiles,
  and applies the merged profile during an optimized rebuild. Use profile data
  to guide branch probabilities, hot and cold layout, and inlining decisions
  where measurements justify them. Ordinary builds must carry no profiling
  instrumentation or runtime overhead, and mismatched or stale profiles must
  never affect correctness.
- **Compiler-owned `@Inline` directive review:** Define a narrow method directive
  that carries source intent through compiled-class and archive reconstruction
  to the final closed-world optimization pipeline. Treat it as a strong inlining
  hint rather than a guarantee so recursion, indirect targets, unavailable
  bodies, and unacceptable code growth remain valid reasons not to inline. The
  directive must add no runtime bookkeeping and must not introduce general
  annotations, metadata, processing, or reflection.
- **Java host bridge review:** [`IRONWOOD_JAVA_BRIDGE.md`](IRONWOOD_JAVA_BRIDGE.md)
  proposes a shared-library link mode whose export surface is the public API,
  compiler-emitted trampolines with a generated Java facade, and ownership
  shaping driven by the existing closed-world analyses. It is a proposal only:
  no decision has been accepted and no supported status changes.

Cross-cutting work includes Linux x86-64 and macOS development, reproducible
toolchain diagnostics, native debug information, benchmarks kept separate from
correctness tests, and explicit decision records before deferred semantics land.
