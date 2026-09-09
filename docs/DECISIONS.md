# Decision log

Accepted decisions are architectural constraints. A later change must be
recorded as a new decision that explicitly supersedes the old one.

## D001 - Closed-world ahead-of-time compilation

- **Status:** Accepted
- **Context:** The language is intended to produce predictable native programs,
  not participate in a dynamically changing VM universe.
- **Decision:** The final link has complete knowledge of all reachable program
  code and produces a native executable or native library.
- **Consequences:** Whole-program reachability and class-hierarchy analysis are
  semantic advantages. Runtime class loading is unavailable.

## D002 - No JVM runtime distribution model

- **Status:** Accepted
- **Context:** JVM bytecode, a JIT, and class loading conflict with the native
  deployment goal.
- **Decision:** Programs are not distributed as Java bytecode and do not require
  a JVM. Arbitrary reflection, runtime bytecode generation, user class loaders,
  Java agents, and JVM method-handle linkage are outside the language model.
- **Consequences:** Similar facilities must be compile-time mechanisms or an
  explicitly designed native feature.

## D003 - Java-like object references

- **Status:** Accepted
- **Context:** Java programmers should not need a pointer or ownership syntax
  taxonomy for ordinary objects.
- **Decision:** Primitives have value semantics; objects are accessed through
  typed, nullable, Java-like references. Assignment copies a reference. Normal
  code exposes neither raw pointers nor pointer arithmetic.
- **Consequences:** The compiler/runtime owns object representation, null checks,
  bounds checks, and dispatch implementation.

## D004 - Automatic reachability-based reclamation

- **Status:** Superseded by D027
- **Context:** The original design assumed that object reachability would drive
  automatic memory reclamation.
- **Decision:** The original model kept reachable objects valid without explicit
  reclamation and reclaimed unreachable objects automatically.
- **Consequences:** This automatic-reclamation model is no longer part of
  Ironwood. D027 replaces it with explicit compiler-checked reclamation.

## D005 - `free` requires a compiler safety proof

- **Status:** Accepted
- **Context:** Deterministic reclamation is useful for native programs but cannot
  introduce use-after-free behavior.
- **Decision:** `free expression;` reclaims the referenced allocation only when
  the compiler proves that no live alias can observe it afterward. Uncertain
  cases are compile-time errors.
- **Consequences:** Ordinary references remain freely aliasable. Allocation
  identity, escape, alias, liveness, and closed-world call analysis are compiler
  responsibilities. Programmers may omit a rejected `free`, but that allocation
  then remains unreclaimed until process termination.

## D006 - Compiler-owned typed IR before LLVM

- **Status:** Accepted
- **Context:** Source semantics and whole-program analyses must remain testable
  independently of a backend.
- **Decision:** Parsing and semantic analysis lower into an immutable typed IR.
  LLVM IR is generated only after that boundary.
- **Consequences:** LLVM is a backend rather than the language's semantic IR.

## D007 - LLVM is the initial native backend

- **Status:** Superseded by D012
- **Context:** The project needs production-quality native code generation across
  macOS and Linux without building a machine backend first.
- **Decision:** Emit textual LLVM IR and invoke Clang for native code generation
  and linking.
- **Consequences:** The build must detect the LLVM/Clang toolchain and report a
  useful diagnostic when it is missing.

## D008 - Java bootstrap compiler and direct parser

- **Status:** Accepted
- **Context:** Implementation speed, clarity, and familiar compiler code matter
  more than early self-hosting.
- **Decision:** The bootstrap compiler is written in Java 21-compatible source
  and uses a hand-written lexer and recursive-descent parser.
- **Consequences:** A JVM is needed to run the compiler, never the generated
  executable. Self-hosting remains a later exploration.

## D009 - Host-native macOS and Linux builds

- **Status:** Accepted
- **Context:** Both Mach-O/macOS and ELF/Linux are initial development targets.
- **Decision:** The semantic pipeline and LLVM emitter are platform-neutral. A
  Clang installation on each host compiles the same LLVM form into that host's
  object format and executable. Hosted builds and tests run independently on
  macOS and Linux only as part of a version-tag release.
- **Consequences:** There are separate packaged artifacts per OS/architecture.
  Cross-compilation and ABI stability are not implied and remain later decisions.

## D010 - Milestone 1 entry point

- **Status:** Superseded by D043
- **Context:** The smallest complete native program needs an unambiguous entry.
- **Decision:** Milestone 1 recognizes exactly one `public static int main()` with
  no parameters and an integer-literal return. It lowers to native `i32 @main()`.
- **Consequences:** Command-line arguments, overloads, expressions, and alternate
  entry-point forms are not implemented yet. This does not settle their final
  design. D043 replaces this bootstrap-only signature.

## D011 - The language and project are named Ironwood

- **Status:** Accepted
- **Context:** The initial bootstrap deliberately deferred its final public name.
- **Decision:** The language and project are named **Ironwood**. The bootstrap
  compiler command is `ironwoodc`, its Java implementation namespace is
  `ironwood.compiler`, and packaged compiler artifacts use the `ironwood` name.
- **Consequences:** Provisional `jcc` names are removed before the first commit.
  The source-file extension remains a separate decision, subsequently accepted
  in D013.

## D012 - Pin LLVM 23 and make backend stages explicit

- **Status:** Accepted; optimization-level selection refined by D017
- **Context:** Relying only on whichever `clang` happens to be available hides
  LLVM version differences and collapses IR validation, optimization, object
  generation, and platform linking into an opaque step.
- **Decision:** LLVM 23.x is Ironwood's canonical bootstrap backend on macOS and
  Linux. The compiler validates the suite with `llvm-config`, assembles textual
  IR with `llvm-as`, runs `opt` at `default<O0>`, emits a native object with
  `llc -O=0`, and uses that suite's Clang driver for the final platform link.
  This supersedes D007's
  one-step Clang invocation and refines the backend mechanics described in D009.
- **Consequences:** Bootstrap development requires JDK 21 and LLVM 23. The
  compiler discovers a complete LLVM home, rejects a mismatched major version,
  and gives stage-specific failures. The macOS and Linux version-tag release
  jobs use the same LLVM major. Updating the canonical major requires an
  explicit decision and testing. Clang remains required for SDK/startup/linker
  orchestration; LLD is not required.

## D013 - Ironwood source files use the `.iron` extension

- **Status:** Accepted
- **Context:** The language name is settled, but the bootstrap initially retained
  a provisional extension for examples and test fixtures.
- **Decision:** Ironwood source files use the `.iron` extension.
- **Consequences:** Examples, integration fixtures, command usage, packaging, and
  diagnostics consistently identify Ironwood source files with `.iron`.

## D014 - Release prebuilt, self-contained IDKs

- **Status:** Accepted
- **Context:** Compiler users should not need to reproduce the Java bootstrap and
  LLVM build environment before compiling their first Ironwood program.
- **Decision:** Version tags publish native IDK archives for macOS ARM64, Linux
  ARM64, and Linux x86-64. Each IDK contains the bootstrap compiler and a private,
  relocatable Java 21/LLVM 23 toolchain. Before publication, fresh native runners
  download the draft release assets, extract them, and compile and run the
  packaged example without a separately installed release toolchain.
- **Consequences:** IDK users do not install Java, LLVM, Homebrew, or Conda.
  Generated programs remain native and do not inherit the compiler's Java
  dependency. Linux archives carry linker dependencies; macOS retains Apple's
  Command Line Tools requirement because the macOS SDK is distributed by Apple.
  Release archives include a third-party package manifest.

## D015 - Milestone 2 primitive and control-flow surface

- **Status:** Accepted
- **Context:** Methods, locals, expressions, and structured control flow require
  coherent observable rules rather than syntax that leaves typing, scope, or
  arithmetic behavior implicit.
- **Decision:** Milestone 2 has `int`, `boolean`, and `void` method results;
  `int`/`boolean` parameters and initialized locals; local assignment; wrapping
  32-bit `+`, `-`, `*`, and unary `-`; boolean `!`; signed integer ordering;
  same-type primitive equality; `if`/`else`; `while`; returns; and unqualified
  static calls within the single source class. Conditions require `boolean`.
  Locals have lexical scope and cannot shadow an active local or parameter.
  Methods are uniquely named (no overloading) and every value-returning path must
  return. Loop return analysis conservatively assumes a `while` may exit.
- **Consequences:** The surface is immediately familiar to Java programmers while
  avoiding object/runtime commitments before Milestone 3. Division and remainder
  are deferred until their failure behavior can be aligned with exceptions.
  Forward calls work because method declarations are collected before bodies are
  analyzed. Adding overload resolution, alternative numeric widths, or stronger
  loop termination reasoning requires explicit follow-on work.

## D016 - Basic-block SSA is the Milestone 2 compiler IR

- **Status:** Accepted
- **Context:** Mutable source locals, branches, and loops must not be lowered
  directly from AST nodes in the LLVM emitter, and later whole-program analyses
  need an inspectable compiler-owned control-flow form.
- **Decision:** Semantic lowering produces typed functions made of named basic
  blocks, typed SSA values, explicit instructions, phi nodes, and typed
  terminators. Branch joins merge changed values with phi nodes; loop headers
  carry visible values through preheader/backedge phi inputs. LLVM emission is a
  mechanical translation of this IR and never performs name resolution or source
  type checking.
- **Consequences:** Control-flow and value flow can be tested without LLVM, source
  spans remain attached through lowering, and the backend has no dependency on
  AST structure. The bootstrap may create conservative redundant loop phis;
  simplification is future optimization work, not a semantic requirement.

## D017 - Expose LLVM optimization levels with an O0 default

- **Status:** Accepted
- **Context:** LLVM already provides mature general-purpose optimization and
  machine code generation, and Milestone 2's typed SSA IR is sufficient to use
  those pipelines without introducing Ironwood-specific optimizer work early.
- **Decision:** `ironwoodc` accepts `-O0`, `-O1`, `-O2`, and `-O3`, defaulting to
  `-O0`. The selected level is passed to both LLVM's default `opt` pipeline and
  `llc` code generation. This refines D012's original fixed-O0 bootstrap policy.
- **Consequences:** Development builds remain correctness-first by default while
  users can request optimized native code. Every supported level is exercised by
  native integration tests. Language-aware optimization remains future work only
  where LLVM lacks Ironwood-specific semantic information.

## D018 - Follow Java-like visibility rules

- **Status:** Accepted
- **Context:** Ironwood is intended to feel immediately familiar to Java
  programmers, and declaration visibility is part of Java's core source and API
  model rather than optional library behavior.
- **Decision:** Top-level classes may be `public` or package-private (an omitted
  modifier); `private` and `protected` top-level classes are invalid. Fields,
  constructors, methods, and future nested types support `public`, `protected`,
  `private`, and package-private visibility with Java-like access rules.
- **Consequences:** Syntax begins with the object milestone. Enforcement is
  staged according to dependencies: class-local rules with objects,
  inheritance-dependent protected access with inheritance, and complete
  cross-package behavior with packages/imports. Staging must not introduce
  permanently weaker semantics.

## D019 - Milestone 3 object and bootstrap-runtime model

- **Status:** Superseded in part by D020
- **Context:** Objects make allocation, reference representation, initialization,
  construction, member lookup, and instance-call behavior observable. The first
  implementation must remain small without bypassing compiler-owned typed IR or
  accidentally committing the language to its final reclamation or object ABI.
- **Decision:** A compilation unit may contain multiple top-level classes in one
  implicit package. Class names are unique. Object references are typed and
  nullable; assignment copies a reference, and `null` converts to any reference
  type. In the bootstrap backend all references use LLVM opaque `ptr`, with the
  null pointer representing `null`. Reference equality is supported for the same
  class type and for comparisons with `null`. A null receiver is checked before
  field access or instance calls and terminates through a dedicated runtime
  failure function until exceptions exist.

  Each class has a compiler-owned layout whose instance fields appear in source
  declaration order. Milestone 3 adds no object header and makes no stable ABI
  promise; later inheritance, dispatch metadata, and explicit-reclamation work may refine the
  physical layout without changing source reference semantics. Allocation
  zero-initializes the complete object before constructor execution, giving
  `int` fields `0`, `boolean` fields `false`, and reference fields `null`.
  Field declaration initializers are not yet supported, so constructor body
  execution is the only subsequent initialization step.

  A class may declare at most one constructor because overload resolution is not
  implemented. A class with no constructor receives an implicit no-argument
  constructor with the class's visibility. `new` allocates, then invokes the
  selected constructor, and yields the initialized reference. `this` is an
  implicit first parameter of constructors and instance methods and is illegal
  in static methods. Unqualified names prefer locals/parameters and then fields
  of `this`; unqualified calls resolve within the current class. With no
  inheritance in this milestone, instance calls use direct closed-world targets.

  Allocation and null failure are isolated behind the C ABI functions
  `ironwood_allocate` and `ironwood_check_not_null`. The bootstrap allocator uses
  zeroing `calloc`, aborts on allocation failure, and deliberately does not
  reclaim objects. The native backend compiles and links this small runtime as a
  separate object. Source-level reclamation is added by the safe-`free`
  implementation required in Milestone 6.
- **Consequences:** Object-aware AST, semantic, typed SSA, and LLVM stages remain
  explicit and independently testable. Source code observes Java-like references
  and initialization rather than raw addresses. The no-header layout, opaque
  pointer lowering, direct dispatch, and retaining allocator are bootstrap
  implementation details, not commitments to the inheritance, reclamation, or stable ABI
  designs. Method and constructor overloading, static fields, field initializers,
  inheritance, interfaces, finalization, and explicit `free` remain outside
  Milestone 3.

## D020 - Milestone 4 hierarchy, construction, dispatch, and type identity

- **Status:** Accepted
- **Context:** Inheritance and interfaces make subtype conversion, construction
  order, member identity, dynamic dispatch, object layout, and runtime type tests
  observable. The model must remain Java-like while exposing enough
  compiler-owned information for closed-world analysis before LLVM lowering.
- **Decision:** A class may extend at most one class and implement any number of
  interfaces. An interface may extend any number of interfaces. A class cannot
  extend an interface, an interface cannot extend a class, and every class or
  interface inheritance cycle is rejected. Interfaces contain only implicitly
  public abstract instance-method declarations in this milestone; interface
  fields, constructors, static/private methods, and default method bodies remain
  deferred. Every class is concrete and must provide, directly or through a
  superclass, an implementation for every transitive interface method.

  Member lookup follows the receiver's static type and then its ancestors.
  Instance fields are inherited, but declaring a field with an inherited name is
  rejected in Milestone 4 rather than introducing Java field hiding before it is
  useful. Private members remain class-local and are not override candidates.
  A same-name inherited method is an override or static hide only when its
  parameter types are identical. Instance overrides may return the same type or
  a reference subtype, cannot narrow access, and cannot change between static
  and instance form. Static calls are direct. Private instance calls are direct.
  Other class instance methods are virtual; interface methods use interface
  dispatch.

  Every constructor begins by invoking its direct superclass constructor.
  `super(arguments);` is permitted only as the first constructor action. If it
  is omitted, `super();` is inserted. An implicit no-argument constructor is
  synthesized for a class without a declared constructor and performs the same
  chaining. A missing, inaccessible, or argument-incompatible superclass
  constructor is a compile-time error, so no constructor path can observe an
  uninitialized base subobject. The root class has no superclass invocation.

  `null` and subtype-to-supertype upcasts are implicit for assignment,
  arguments, and returns. This includes class-to-base, class-to-implemented
  interface, and interface-to-superinterface conversion; downcasts are not yet
  syntax. Reference equality is accepted only when the operand types can overlap
  in the closed type universe. `value instanceof Type` accepts a reference or
  `null` left operand and a declared class or interface target; it returns false
  for `null` and otherwise tests the object's runtime membership.

  D019's headerless physical layout and always-direct instance dispatch are
  superseded. Each allocated object begins with one compiler-owned type-descriptor
  pointer followed by inherited fields in base-first order and then the class's
  declared fields in source order. Descriptor identity is the runtime class
  identity. Descriptors carry a deterministic closed-world type id, a pointer to
  a type-membership table, and a pointer to a dispatch table. Compiler-assigned
  dispatch slots are keyed by complete instance-method signature across the
  compilation unit. Class virtual calls and interface calls use the same
  per-class slot table, so a separate itable is unnecessary in this first closed
  world ABI. Interfaces still have distinct compiler-owned identities and
  interface-call IR. These layouts are bootstrap internals and are not a stable
  native ABI.

  Compiler-owned hierarchy IR records type kind, direct parents, complete
  base-first fields, type identity/membership, dispatch slots, and per-class slot
  targets. Calls remain explicit as direct, virtual, or interface IR operations.
  Closed-world class-hierarchy analysis rewrites a virtual or interface call to
  a direct devirtualized call exactly when all concrete receiver classes allowed
  by the static receiver type resolve that signature to one linkage target. A
  call with zero or multiple possible targets remains diagnosed or indirect;
  LLVM optimization is not relied upon to recover this fact.
- **Consequences:** Source references retain D003's nullable Java-like semantics
  while allocation size, field offsets, type tests, and dispatch become metadata
  driven. Interface values remain ordinary object references rather than fat
  pointers. The compiler emits the smallest required descriptors, membership
  tables, dispatch tables, and a null-safe type-test helper; the isolated C
  runtime continues to own allocation and null failure and does not gain class
  loading, reflection, exceptions, collection, or a general dynamic linker.
  Method/constructor overloading, abstract classes, interface default methods,
  explicit downcasts, `super` member access outside constructor chaining, field
  hiding, and a stable external object ABI remain deferred.

## D021 - Javac-like default output and embedded compiler version

- **Status:** Superseded by D024
- **Context:** Requiring `-o` for every invocation adds ceremony to the common
  single-source workflow and differs from the familiar `javac Source.java`
  experience. A PATH-installed compiler also needs a toolchain-independent way
  to identify its build.
- **Decision:** `ironwoodc Source.iron` links a native executable beside the
  source, named with the source basename and no `.iron` suffix. For example,
  `ironwoodc examples/Order.iron` produces `examples/Order`. `-o <path>` remains
  the explicit override. This follows `javac`'s default output location, while
  the artifact remains Ironwood's native executable rather than a JVM class
  file. `ironwoodc --version` and `ironwoodc -v` print `ironwoodc <version>` and
  succeed without a source file or LLVM discovery. The root `VERSION` file is
  authoritative for source and host
  packages; a tagged IDK build embeds its validated release version instead.
- **Consequences:** A no-`-o` compile may replace an existing same-named native
  executable beside the source, just as an explicit output may replace its
  target. Scripts that require isolated build products should continue using
  `-o`. Compiler JARs carry the version as a resource, packages expose a matching
  `VERSION` file, and package smoke tests verify version consistency and default
  output placement.

## D022 - Java source organization and closed-world compilation sets

- **Status:** Superseded in part by D023
- **Context:** Ironwood already has Java-like classes and visibility, but a
  single monolithic compilation unit does not scale to normal Java project
  organization. Java programmers expect packages, imports, one public type per
  file, multiple compiler inputs, source-path dependency discovery, and a
  classpath. Ironwood must provide those conventions without introducing JVM
  class files, runtime class loading, or an unstable native library ABI.
- **Decision:** Source units may declare a package and single-type or wildcard
  imports, and all nominal type identity is package-qualified. Java's top-level
  legality is followed exactly: at most one public top-level type per unit, its
  name must match the `.iron` filename, and additional package-private types are
  legal even though one top-level type per file is the project style. Public,
  protected, package-private, and private access are enforced across packages,
  including Java's cross-package protected receiver restriction.

  An executable compilation accepts multiple explicit sources and forms one
  closed-world compilation set. Referenced source types are discovered
  transitively from `-sourcepath`/`--source-path`; the default source root is
  `.`, and canonical type `com.test.Foo` maps to `com/test/Foo.iron` below each
  root. This reproduces the relevant `javac` behavior: a package tree below the
  current directory works without an option, while a tree below
  `src/main/ironwood` requires that directory as the source path. Explicit source
  roots take precedence over compile-time libraries.

  `-cp`, `-classpath`, and `--class-path` select compile-time Ironwood libraries.
  The bootstrap `.ironlib` format is a versioned archive containing validated
  source units and a canonical-type index. `--library` validates a source set
  without requiring an entry point and writes that archive. During executable
  compilation, referenced library units are reparsed, rechecked, and lowered
  together with application sources, preserving the final closed-world model.
  There is no runtime classpath or class loader.
- **Consequences:** Normal projects can use Java-shaped package directories and
  compile an entry source while dependencies are found automatically. Separate
  source trees and reusable libraries work without merging source files. The
  source-bearing archive trades build speed for semantic clarity until Ironwood
  deliberately defines a stable serialized compiler IR and library ABI; format
  1 must not be presented as native binary compatibility. D018's staged
  cross-package visibility work is now complete. D019's implicit-package-only
  limitation and D021's single-source description are superseded where this
  decision is broader. All library code remains visible to final reachability,
  hierarchy, and future whole-program optimization.

## D023 - Uniform `.ironclass` artifacts and Java-like class output

- **Status:** Accepted; refined by D024, D025, and D043
- **Context:** D022's source-bearing `.ironlib` made a classpath possible, but
  requiring `ironwoodc --library` conflated class compilation with archive
  packaging. Java's ordinary reuse boundary is a uniform `.class` file;
  `javac -d` emits those files whether or not one class declares `main`, while a
  separate `jar` tool optionally packages them. Ironwood needs the same natural
  source-to-class workflow even though final execution is native and static.
- **Decision:** Every compiled top-level Ironwood type uses `.ironclass`, including
  the class that declares the valid entry method specified by D043. The main
  class differs only
  by optional entry-point metadata inside the same format. With no `-d`, class
  files are written beside their source units. `-d <directory>` writes them under
  package-relative paths and, without `-o`, selects class-only compilation that
  requires no entry point or LLVM toolchain. A source set without `main` also
  succeeds and emits class files beside its sources. `-cp`, `-classpath`, and
  `--class-path` accept package-root directories or individual `.ironclass`
  files and remain compile-time-only.

  When neither `-d` nor `-o` is supplied and the source set contains a unique
  valid entry point, `ironwoodc` additionally links the native executable beside
  that entry source. `-o` selects the native path; it never changes the class
  format. The OS-executable file is necessarily a separate artifact from
  `.ironclass`. `--main-class <qualified-name>` selects one entry method for a
  later link from compiled classes or for a closed-world set containing multiple
  valid main classes. All Ironwood methods retain package-qualified internal
  symbols; executable emission alone creates the platform `main` wrapper.

  Format 1 `.ironclass` is a deterministic container with a version manifest,
  canonical type index, optional entry-point metadata, and the validated source
  compilation unit. The source payload is an explicit bootstrap representation
  so final compilation can reconstruct hierarchy, dispatch, layouts, and typed
  IR across the complete closed world. It will be replaced by deliberately
  serialized compiler-owned IR after that open-world-to-closed-world boundary is
  designed. `--library` and `.ironlib` are removed. A later archive tool may
  package `.ironclass` files, analogous to Java's separate `jar` command.
- **Consequences:** Library and application classes follow one compilation and
  classpath model; a main class is not a special binary kind. Final native
  compilation still statically resolves all reachable code and introduces no
  JVM, runtime classpath, or class loader. Format 1 is portable and semantically
  correct but does not yet avoid reparsing or hide source, and it is not a stable
  binary ABI. D022's package/import/source-path decisions remain accepted, while
  its `.ironlib` and `--library` mechanism is superseded.

## D024 - Explicit class compilation and native link modes

- **Status:** Superseded by D025
- **Context:** Automatically emitting both `.ironclass` and a native executable
  from `ironwoodc Source.iron` obscures the difference between reusable class
  compilation and final closed-world linking. Java's familiar default command
  is class compilation, while Ironwood additionally needs an explicit native
  operation because there is no JVM. Using `-o` alone as an implicit mode switch
  is terse but does not state that a final executable is being linked.
- **Decision:** Ordinary `ironwoodc <source>...` compilation emits only uniform
  `.ironclass` files, beside each source by default or below the package-aware
  `-d <directory>` root. It does not discover LLVM or create an executable.

  `--link` explicitly selects native executable production. It may consume
  source inputs, compiled classes, or both; source inputs are compiled
  transiently and no `.ironclass` files are written in link mode. `-o <path>`
  `--emit-llvm`, `--llvm-home`, and optimization levels are valid only with
  `--link`, while `-d` and `--link` are mutually exclusive. Without `-o`, a
  source-based link writes the executable beside the selected entry source. A
  classpath-only link defaults to the main class's simple name in the current
  directory.

  With source inputs, `--main-class <qualified-name>` is optional when exactly
  one valid main method exists and otherwise selects among them. With no source
  inputs it is required: the name is both the root class loaded to begin
  dependency discovery and the selected native entry class, and therefore must
  be discoverable on the supplied classpath. `-cp` is the canonical documented
  spelling; `-classpath` and `--class-path` remain aliases.
- **Consequences:** Class compilation and native linking have separate,
  predictable side effects. Build tools can persist reusable class artifacts or
  request a final executable without unwanted class output. The main class
  remains an ordinary `.ironclass`; only final link mode synthesizes the OS-level
  `main` wrapper. D021's automatic native output and D023's combined-output
  convenience are superseded, while their versioning, uniform class format, and
  output-location decisions remain accepted.

## D025 - Link mode consumes compiled classes only

- **Status:** Accepted
- **Context:** D024 separated class and executable outputs but still allowed
  source files as transient inputs to `--link`. That created two link workflows:
  a source-rooted form that inferred an entry method and a classpath-rooted form
  that required `--main-class`. The dual behavior made the phase boundary and
  the meaning of entry selection harder to explain.
- **Decision:** `--link` accepts no positional source files and never consults a
  source path. It requires `--main-class <qualified-name>` on every invocation.
  The named class is both the root loaded from the compile-time classpath and the
  class whose valid entry method specified by D043 becomes the native entry method.
  It must be discoverable on `-cp`, which defaults to `.`; dependencies
  must likewise be available as `.ironclass` files on that classpath.

  `ironwoodc <source>...` is the only source-compilation form and emits class
  artifacts. `ironwoodc --link --main-class <name>` is the only native-link form
  and emits an executable. `--source-path`, `-d`, and source operands are errors
  in link mode. `-o`, LLVM selection/diagnostic options, and native optimization
  levels remain link-only.
- **Consequences:** The command line exposes a strict two-stage pipeline:
  `.iron` to `.ironclass`, then `.ironclass` to a closed-world native executable.
  There is no one-command source-to-executable shortcut. Entry selection has one
  invariant meaning, build side effects are predictable, and the final linker
  cannot silently fall back to source files. D024's explicit-mode separation is
  retained, while its transient source-link form and source-based default output
  location are superseded.

## D026 - Java-like unchecked exceptions over native zero-cost unwinding

- **Status:** Accepted
- **Context:** Milestone 5 makes abrupt object-valued control flow observable
  across functions and constructors. Ironwood needs Java-like `throw`, ordered
  typed catches, and `finally` without adding JVM exception tables, explicit
  error returns on every call, or a second compiler bypass around the typed IR.
  The bootstrap has no standard-library `Throwable` root yet, and Windows uses a
  different LLVM exception representation that is outside the current native
  platform set.
- **Decision:** In the Milestone 5 bootstrap, every non-null class or interface
  reference is throwable and every accessible nominal class or interface is a
  legal catch type. Primitive, `void`, and the `null` literal are rejected by
  `throw`; a nullable reference that is null at runtime terminates with a
  deterministic runtime diagnostic. This deliberately temporary all-object
  rule avoids inventing a hidden standard-library class. Introducing the
  eventual `Throwable` hierarchy requires a later decision and source migration.
  Exceptions are unchecked in this milestone; method signatures do not declare
  thrown types.

  Catch clauses are tested in source order using the same closed-world nominal
  membership relation as `instanceof`, including implemented and extended
  interfaces. A catch is rejected when an earlier catch type is the same type or
  a supertype of it, because it can never be selected. The catch variable has
  the declared catch type, is non-null on entry, and is scoped only to its catch
  block. Exceptions raised by a catch body are not reconsidered by sibling
  catches.

  `finally` executes exactly once when its `try`/`catch` completes normally,
  returns, or exits exceptionally. A return expression is evaluated before
  cleanup. Abrupt completion of `finally` supersedes a pending return or
  exception; otherwise the pending completion resumes. Constructor exceptions
  propagate without producing the not-yet-constructed reference, preserving the
  established base-before-derived construction invariant.

  On macOS ARM64 and Linux ARM64/x86-64, compiler-owned IR represents
  potentially throwing calls as explicit normal/unwind edges and represents
  landing, nominal selection, cleanup, throw, and propagation before LLVM
  lowering. LLVM emission uses the Itanium-family zero-cost model: `invoke`,
  `landingpad`, a catch-all personality clause, and out-of-line exception tables.
  The isolated runtime wraps the Ironwood object pointer in an ABI
  `_Unwind_Exception` with an Ironwood exception class and raises it with
  `_Unwind_RaiseException`. `__gxx_personality_v0` supplies portable platform
  table interpretation; Ironwood performs its own nominal match after landing.
  A handled landing takes and destroys only the native wrapper. Propagation
  rewraps the same language object and starts a new native search outside the
  completed lexical handler, allowing an outer Ironwood catch to make its own
  nominal decision without C++ RTTI or `__cxa_*` catch state.

  The generated platform `main` is a final native catch-all. This guarantees a
  phase-two unwind exists for an otherwise uncaught exception, so intervening
  Ironwood `finally` cleanups run before the runtime prints
  `uncaught Ironwood exception: <qualified-type>` and exits with status 1.
  Throwing null and unwind-runtime failures likewise print stable diagnostics
  and exit with status 1. There is no stack trace in this milestone.
- **Consequences:** Normal calls outside protected regions retain ordinary LLVM
  `call`; calls within an exception or cleanup region become `invoke`, preserving
  zero-cost normal-path behavior. Type descriptors gain a qualified-name pointer
  used only for deterministic diagnostics. The native link uses Clang's C++
  driver mode solely to provide the platform `__gxx_personality_v0` and unwind
  runtime; Ironwood objects are not C++ objects, and this adds no RTTI,
  reflection, class loading, JVM machinery, or runtime code generation. The
  bootstrap ABI remains internal and can change before native-library ABI
  stability is promised. Windows will require LLVM funclet-style EH and a
  separate explicit platform decision.

  This lowering follows LLVM's official `invoke`/`landingpad` zero-cost EH model
  and the Itanium C++ ABI's language-neutral `_Unwind_Exception` and
  `_Unwind_RaiseException` contract:
  <https://llvm.org/docs/ExceptionHandling.html> and
  <https://itanium-cxx-abi.github.io/cxx-abi/abi-eh.html>.

## D027 - Explicit compiler-checked reclamation without a collector

- **Status:** Superseded in part by D083; supersedes D004 and refines D005 and
  D019
- **Context:** Ironwood should retain Java-like source syntax, object identity,
  references, aliasing, classes, and exceptions without carrying Java's garbage
  collector into the native runtime. Native programs need a direct way to bound
  memory use, while ordinary references must remain safe and pointer-free.
- **Decision:** Ironwood has no garbage collector and does not reclaim an
  ordinary object merely because it becomes unreachable. A source-level `new`
  allocation remains allocated until a compiler-proven-safe `free` reclaims that
  exact allocation or the process terminates. Omitting `free` is legal; if a
  program continues allocating without reclaiming enough memory, allocation
  eventually fails and the process terminates under the runtime's allocation-
  failure policy.

  D005's safety rule remains mandatory. `free expression;` is accepted only when
  closed-world allocation-identity, alias, escape, and liveness analysis proves
  that no later observation can occur through a local, parameter, return value,
  field, array element, static/global, closure, call-mediated alias, widened
  class/interface reference, in-flight native exception wrapper, catch value, or
  pending cleanup path. Conservative rejection is correct. Removing a rejected
  `free` leaves the allocation unreclaimed; there is no automatic fallback.

  `free` reclaims only the allocation denoted by its expression. It neither
  recursively frees referenced fields nor runs a destructor or resource-cleanup
  hook. A successful `free local;` does not assign `null`; the old value instead
  becomes semantically dead. A second `free` and every read, comparison, call,
  return, or other use of that dead value are compile-time errors. A plain
  assignment may reuse the variable because it replaces rather than reads the
  dead value, and any newly assigned allocation is tracked independently.
  Runtime-owned storage that is not an Ironwood object may be released
  internally; transient native exception wrappers are one example. A failed
  constructor does not implicitly reclaim its ordinary object allocation. Its
  `new` expression yields no reference, but Java-like constructor code may have
  allowed `this` to escape before throwing; if it did not escape, the allocation
  remains unreclaimable.
- **Consequences:** Milestone 6 implements the first conservative safe-`free`
  proof plus its runtime deallocation boundary; the former collector milestone
  is removed. Its initial positive core is a same-function local allocation on
  one structured path, with ended-scope aliases and proven nonescaping direct or
  devirtualized calls. It rejects unknown provenance, live or escaped aliases,
  uncertain polymorphic calls, values crossing structured control flow,
  double-free, and post-free use. Long-running programs must use accepted `free`
  operations or bounded allocation patterns to avoid memory exhaustion. The
  compiler must preserve Java-like aliasing while proving each reclamation safe,
  and diagnostics should identify the concrete alias or escape blocking a proof.
  Debug poisoning or quarantine may provide defense in depth but cannot replace
  the static proof. Finalization remains absent unless a later decision adds a
  separate resource-management construct.

## D028 - Milestone 7 arrays, strings, standard output, and standard-library roots

- **Status:** Accepted
- **Context:** Useful native programs need indexed storage, text, and observable
  output without weakening closed-world compilation or the no-collector memory
  model. These features also establish the first standard-library distribution
  boundary, so their source semantics, storage ownership, runtime ABI, and
  elimination rules must be explicit before programs can depend on them.
- **Decision:** The first array slice supports one-dimensional `int[]`,
  `boolean[]`, and reference arrays. Array types use Java-shaped `T[]` syntax;
  `new T[length]` allocates; `array[index]` reads or writes; and `array.length`
  returns an `int`. Array-of-array types, initializers, covariance, casts, and
  array `instanceof` targets remain deferred. Array assignment is invariant:
  the element types must be identical, while `null` converts to any array type.
  Every allocation records a non-negative 32-bit element count in a native
  header and contains zero-initialized contiguous elements. Negative lengths,
  null access, and indices outside `0 <= index < length` terminate
  deterministically at isolated runtime checks until standard exception types
  are implemented. Compiler-owned IR explicitly represents allocation, length,
  bounds checks, loads, and stores before mechanical LLVM lowering.

  Arrays are ordinary explicit-reclamation allocations. `free` releases only
  the array container and never recursively releases referenced elements. A
  same-function array allocation can be freed under the existing local proof.
  Storing a tracked allocation into a reference-array element makes that
  allocation escaped for the current proof, even if the element is later
  overwritten; loading a reference element has unknown allocation provenance
  and cannot itself be freed. These conservative rules prevent the proof from
  assuming away element aliases. Primitive-array elements create no reference
  aliases.

  String literals use double quotes, UTF-8 source-to-runtime encoding, and the
  escapes `\\b`, `\\f`, `\\n`, `\\r`, `\\t`, `\\"`, and `\\\\`. A literal is
  an immutable, compiler-emitted, pooled object of type
  `ironwood.lang.String`. Its `length()` is the Unicode scalar-value count and
  `byteLength()` is the UTF-8 byte count. `==` and `!=` remain reference-identity
  operations; identical literals in one final program share identity because
  the compiler pools their decoded contents. Literal objects and their byte
  storage are static and immortal, are not ordinary allocator results, and
  therefore cannot satisfy a source `free` proof. Runtime-created mutable
  strings and content equality are deferred.

  As amended by D044, the output API is
  `ironwood.lang.System.out.println(String)`. It writes the exact UTF-8 bytes
  followed by one newline to standard output; a null argument prints `null`
  followed by a newline. The call uses ordinary typed static-field and instance
  resolution, while the stream method contains explicit compiler-owned output
  IR. LLVM lowers that IR to the isolated `ironwood_stdout_println` C ABI. The
  operation observes its argument synchronously and does not retain it.

  `ironwood.lang` is implicitly visible like Java's `java.lang`; other library
  packages use ordinary imports. Initial library declarations live as Ironwood
  source under `stdlib`, are compiled to ordinary format-1 `.ironclass` files
  during compiler builds, and both source and classes are packaged with host
  distributions and IDKs. The compiler resolves reserved `ironwood.lang.String`
  plus `ironwood.lang.System` and `ironwood.io.PrintStream` from that bundled
  standard-library root rather than from runtime loading. Dependency discovery
  includes only referenced library
  classes in the final closed world, establishing class-granular tree shaking;
  method-level reachability and a stable serialized library IR remain later
  work.
- **Consequences:** Array layout and the literal-string tail layout are internal
  bootstrap ABIs, not stable native-library contracts. Checked access adds small
  runtime calls at every current array operation and may later be optimized only
  after an Ironwood proof. Immortal literals and the D044 standard-output
  singleton are deliberately distinct from reclaimable arrays and objects. The
  mandatory runtime gains allocation/check and stdout byte-output boundaries
  but no collector, class loader, reflection
  registry, encoding framework, or hidden standard-library implementation.

## D029 - Contributed pool and data-structure implementations use no GC

- **Status:** Superseded in part by D102 for pool ownership and D105 for internal
  data-structure pool reclamation; otherwise accepted
- **Context:** Milestone 8 is intended to make Ironwood useful for low-allocation
  native applications by adapting proven object pools and data structures. The
  original author donated those implementations directly to Ironwood and has
  authorized their use under Ironwood's default dual license. Parts of the Java
  implementation assumed garbage collection, soft references, runtime
  reflection, and library types that Ironwood does not have.
- **Decision:** The contributed implementations are maintained directly as
  first-party Ironwood standard-library source under
  `MIT OR Apache-2.0`. They retain a brand-neutral contribution notice, but no
  former project, organization, repository, or compatibility-layer identity.
  Their origin and relicensing are recorded in
  `docs/SOURCE_PROVENANCE.md`. The Ironwood build has no external
  source-checkout dependency for these libraries.

  The object-pool API is adapted into the public `ironwood.pool` package and the
  data-structure API into `ironwood.ds`. Both are bundled, tree-shakeable parts of the Ironwood standard
  library rather than separately named compatibility packages. Java-counterpart
  support types belong in their corresponding Ironwood standard packages,
  including `ironwood.lang.StringBuilder`, `ironwood.util.Iterator`, and future
  `ironwood.nio` types. Only `ironwood.lang` is implicitly visible; applications
  import `ironwood.pool` and `ironwood.ds` types normally. The port remains
  single-threaded and preserves reusable iterator, object-pool, ordering,
  collision, and value-semantics behavior where the required language surface
  exists.

  Java-GC facilities are not reproduced. `SoftReference`, soft-reference
  retention modes, `clearSoftReferences()`, and equivalent garbage-collector
  hints are omitted. Reflection-based pool construction from a runtime `Class`
  is also omitted because unrestricted reflection and runtime class discovery
  conflict with closed-world compilation. Callers instead provide an explicit
  statically typed `ObjectBuilder`. Heap buffers may be implemented with ordinary
  Ironwood arrays; direct native buffers remain deferred until the unsafe/native
  ownership boundary is deliberately designed.

  Pool and collection storage never owns inserted user objects. `remove`,
  `clear`, pool release, or freeing a container must not recursively reclaim an
  element. Node-backed implementations and multi-array pool segments retain
  reusable active capacity up to their high-water mark. Superseded private
  backing arrays were initially retained until the compiler could prove their
  container identity exclusive; D041 now supplies that proof and permits their
  explicit deterministic reclamation. Unchecked deallocation and hidden
  reachability reclamation remain forbidden.

  The contributor's original behavioral tests are translated into compiler,
  native-execution, and packaged-library tests. JVM allocation-instrumentation
  tests are replaced with Ironwood runtime allocation counters or an equivalent
  deterministic native check of the same steady-state no-allocation invariant.
- **Consequences:** “Adapted” means behavior verified in native Ironwood tests,
  not merely a same-named stub. The implementation may land in dependency-ordered
  slices, starting with language and standard-library prerequisites, then
  object pools, primitive structures, generic structures, and buffer-backed
  structures. Coverage documentation must distinguish complete APIs, staged APIs,
  deliberately omitted GC/reflection features, and deferred native-buffer work.
  Active reusable capacity may remain at the historical high-water mark. Replaced
  backing arrays are reclaimed only under D041's compiler proof; silently
  dropping or freeing an allocation without proof is not acceptable.

## D030 - `.ironjar` is a deterministic compile-time class archive

- **Status:** Accepted
- **Context:** A useful standard library and the pool/data-structure ports produce
  many package-structured `.ironclass` files. Requiring users to distribute a
  loose directory is inconvenient, while adopting Java's runtime JAR behavior
  would conflict with Ironwood's closed-world model. D023 anticipated a future
  archive analogous to Java's `jar` but did not define it.
- **Decision:** The canonical Ironwood class archive uses the `.ironjar`
  extension and the ZIP container format. It contains a versioned
  `META-INF/IRONWOOD.MF`, a sorted type index mapping each canonical type to one
  package-relative `.ironclass` entry, the indexed class entries, and optional
  license/notice metadata below `META-INF/LICENSES`. Entries are sorted and have
  normalized timestamps so identical inputs produce identical bytes. Duplicate
  canonical types, duplicate entries, unsafe paths, malformed indexes, and
  nested archives are rejected.

  The compile-time class path accepts a class directory, one `.ironclass`, or one
  `.ironjar`. Archive lookup is lazy: resolving a referenced type opens only its
  indexed class payload, and the ordinary closed-world loader admits only that
  class and its discovered dependencies. An archive never participates in
  runtime lookup, cannot add a class after final linking, does not carry target
  object code or LLVM IR, and does not establish a stable native ABI. Manifest
  runtime entry points and transitive Java-style `Class-Path` behavior are not
  inferred; dependencies remain explicit class-path entries.

  A separate `ironjar` build tool provides Java-familiar create and list
  operations over already compiled `.ironclass` inputs. The bundled standard
  library, including `ironwood.pool` and `ironwood.ds`, may ship as one archive or
  deterministic implementation modules selected by the compiler as one library;
  this physical packaging does not make the standard packages optional runtime
  dependencies. The existing class-directory workflow remains supported.
- **Consequences:** Archive distribution does not weaken D001, D023, D025, or
  tree shaking. Because format-1 `.ironclass` still embeds validated source, an
  `.ironjar` is initially a source-bearing bootstrap artifact rather than a
  promise of long-term binary compatibility. Package and IDK smoke tests must
  compile and link from relocated archives, and reproducibility tests must
  compare archive bytes produced from the same inputs.

## D031 - Java-width primitives and UTF-16 `String` contracts

- **Status:** Accepted and implemented; supersedes D028's scalar-count String
  contract
- **Context:** Pool growth APIs use floating-point factors, while the data-structure
  APIs expose byte, character, integer, and long specializations and implement
  Java-compatible hash algorithms. `CharSequenceMap` depends directly on
  Java's UTF-16 `length()`/`charAt()` contract. Keeping D028's Unicode-scalar
  length would make `ironwood.lang.String`, `StringBuilder`, and
  `CharSequenceMap` subtly incompatible for supplementary characters.
- **Decision:** Ironwood adopts Java's source-level widths and ordinary numeric
  model for the required primitive set: signed two's-complement `byte` (8),
  `short` (16), `int` (32), and `long` (64); unsigned UTF-16-code-unit `char`
  (16); IEEE-754 binary32 `float` and binary64 `double`; and logical `boolean`.
  Integer overflow wraps at the promoted width. Java-shaped binary numeric
  promotion, narrowing casts, shift-distance masking, signed and unsigned
  shifts, and comparison behavior apply. Integral division or remainder by zero
  throws the standard arithmetic exception once that library type is available;
  it must never become LLVM undefined behavior. Floating-point finite/NaN/
  infinity behavior follows IEEE-754 and Java's comparison rules.

  `ironwood.lang.CharSequence` measures UTF-16 code units. Immutable
  `ironwood.lang.String.length()` returns that code-unit count and `charAt(int)`
  returns the selected `char`; supplementary Unicode scalars therefore occupy
  two positions. `String.equals(Object)` and `hashCode()` use Java-compatible
  content semantics, while `==` remains reference identity. `byteLength()`
  remains an Ironwood UTF-8 interop extension. `StringBuilder` is mutable,
  implements `CharSequence`, and snapshots immutable `String` values from its
  current UTF-16 contents.

  The physical string representation is one allocation containing
  `{type, utf16Length, utf8Length, trailing UTF-16 units}`. Literals emit those
  units numerically in LLVM globals and remain immortal. Runtime strings use the
  same one-allocation tail; builders are ordinary objects backed by mutable
  `char[]`. Standard-output encoding combines surrogate pairs and encodes unmatched
  units as U+FFFD, matching the stored byte length. Runtime strings and builders
  remain ordinary explicit-reclamation allocations, subject to the compiler's
  conservative allocation-identity proof.

  Public String/StringBuilder bounds checks throw catchable
  `StringIndexOutOfBoundsException`. Negative builder capacity throws
  `NegativeArraySizeException`, null sequence constructors throw
  `NullPointerException`, and detected capacity arithmetic overflow throws
  `OutOfMemoryError`. Builder integer formatting uses negative accumulation so
  `Integer.MIN_VALUE` and `Long.MIN_VALUE` never overflow through negation.
  Float/double append and source `+` concatenation were deferred by this
  decision; D059 later commits concatenation as Feature 76.
- **Consequences:** Existing BMP-only programs retain the same observed length,
  but a supplementary literal changes from scalar count one to Java-compatible
  length two. Tests must cover surrogate pairs, malformed source escapes,
  numeric boundaries, promotion, shifts, overflow, divide-by-zero, and native
  lowering at every optimization level. LLVM types and operations are backend
  details and may use wider registers only when truncation/sign extension
  preserves these source semantics.

  The implemented primitive slice retains every source width in compiler-owned
  IR, centralizes assignment/invocation/return conversions and numeric
  promotion, represents width changes explicitly, and lowers them with exact
  sign/zero extension, truncation, integer/FP conversion, and guarded Java
  saturating FP-to-integer semantics. It covers all primitive arrays and native
  execution at every optimization level without boxing primitives into Object.

## D032 - Root `Object` and reference-sharing generic types

- **Status:** Accepted
- **Context:** The pool API is intrinsically generic, and most collections
  require generic classes, interfaces, reference arrays, and
  substituted interface dispatch. Replacing them with untyped `Object` APIs or
  one hand-written class per element type would not be a faithful Java-shaped
  port. Whole-class monomorphization was considered, but all initially permitted
  type arguments have the same native pointer layout; cloning recursively generic
  pool/list/map classes would add instantiation, dispatch, metadata, and binary-
  identity complexity without a current layout benefit.
- **Decision:** `ironwood.lang.Object` is the implicit superclass of every class
  except itself and the root of the complete Ironwood reference-object graph.
  Every class instance and array widens to `Object`; an interface-typed or
  unbounded type-parameter value denotes an object and also widens to `Object`.
  Primitive values remain non-object value types as in Java until separately
  designed boxing classes are introduced. Arrays remain statically invariant;
  making them `Object` values does not introduce Java's unsafe reference-array
  covariance.

  `Object.equals(Object)` defaults to reference identity, `hashCode()` returns a
  stable opaque identity hash without exposing a raw address, and `toString()`
  produces the Java-shaped runtime type name plus identity hash. Classes such as
  `String` and value-oriented collections override these methods with content/value
  semantics. `Serializable` is not part of the initial object graph. Runtime
  `Class` remains deferred because it conflicts with unrestricted-reflection
  constraints. D048 and D052 subsequently exclude Java cloning in favor of
  type-owned ordinary copy operations; finalization is excluded because it
  assumes automatic reclamation; monitor `wait`/`notify` APIs belong with the
  future threading model.

  The first generic model supports explicitly parameterized, invariant classes
  and interfaces whose arguments are reference types. Declarations and uses keep
  type parameters and recursively nested type arguments in compiler-owned AST,
  semantic, and typed IR. Member lookup, inheritance, interface implementation,
  overload resolution, assignment, and diagnostics apply the receiver's exact
  type substitution. Raw generic types, diamond inference, bounded wildcards, bounded or
  generic methods, primitive arguments, unchecked generic casts, `new T()`, and
  concrete-parameterized/type-variable `instanceof` targets remain rejected until each is
  separately designed.

  Native lowering shares one layout, type descriptor, dispatch table, and method
  body per raw generic declaration. Type parameters erase to their `Object`
  bound only at the native ABI, where every permitted argument is already an
  opaque reference; source/IR types are not erased early. Runtime type identity
  is the raw class identity, and no runtime generic registry or class loading is
  introduced. Overload and dispatch linkage use erased parameter signatures and
  reject source overloads with the same erasure.

  Ironwood permits `new T[length]` when `T` is a reference type parameter. Its
  arrays have no reified component-class check and remain statically invariant,
  so this is sound and avoids Java's `(T[]) new Object[length]` erasure cast.
  Generic static contexts cannot refer to a class type parameter. Format-1
  `.ironclass` and `.ironjar` indexes continue to name raw declared types; their
  embedded source preserves the generic declaration for final closed-world
  validation.
- **Consequences:** This “reference-sharing” design preserves Java-like static
  safety and APIs while avoiding Java bytecode, raw-type heap pollution, and
  unnecessary native clones. Closed-world optimization may later specialize a
  measured hot instantiation in compiler-owned IR without changing source
  semantics. Primitive performance continues to use explicit
  specialized classes until primitive generic specialization is designed.
  Tests must cover arity, invariance, nested substitutions, generic inheritance
  and interfaces, generic arrays, static-context rejection, erasure clashes,
  classpath/archive round trips, dispatch, escape summaries, and native execution
  with multiple instantiations in one program.

## D033 - Java-shaped expressions and structured loop control use typed CFG

- **Status:** Accepted; established the initial `int`/`boolean` slice and was
  subsequently generalized by D031's implemented Java-width primitive model
- **Context:** The pool and collection sources rely on Java's ordinary operator
  precedence, compound updates, classic `for` loops, and early loop transfers.
  Lowering these forms as ad hoc statements would lose assignment-expression
  values, duplicate side-effecting field/array address evaluation, or bypass the
  compiler-owned SSA and exception models. Lexing `>>` as one unconditional
  token would also conflict with adjacent `>` characters closing nested generic
  arguments.
- **Decision:** Expressions follow Java-shaped precedence through assignment,
  conditional, short-circuit logical, bitwise, equality, relational,
  shift, additive, multiplicative, unary/cast, and postfix levels. Assignment is
  right-associative; conditional expressions evaluate one branch and merge a
  compatible primitive/reference result through typed SSA. `&&` and `||` lower
  to explicit branches and phis, never eager bitwise operations. The lexer keeps
  each `>` as a separate token. Only the expression parser composes contiguous
  `>>`, `>>>`, `>>=`, and `>>>=` sequences, leaving the generic type parser's
  nested closes unchanged.

  A semantic lvalue captures a local symbol or one evaluated field receiver, or
  one evaluated array receiver and index. Plain/compound assignment and
  prefix/postfix `++`/`--` read and write through that abstraction, so every
  receiver and index is evaluated exactly once in Java order. Valid discarded
  expressions are assignments, updates, calls, and object creation. The initial
  original cast slice accepted identity and already-valid widening conversions;
  checked non-parameterized reference downcasts were subsequently implemented
  under D036, while unchecked generic casts remain explicit diagnostics.
  Numeric casts are now implemented under D031.

  Integral `/` and `%` branch on zero in compiler-owned CFG. The zero edge
  constructs and throws `ironwood.lang.ArithmeticException` through the same
  typed throw/invoke/landing-pad model as source exceptions. The nonzero native
  operation is guarded against LLVM's signed `MIN_VALUE / -1` poison and selects
  Java's wrapped quotient or zero remainder. Shift distances are explicitly
  masked by 31. Classic `for` has optional declaration/expression initialization,
  condition, and update expressions. `continue` targets a while condition or the
  for-update block; `break` targets the nearest loop exit.

  Return already executes pending finally cleanup. For this slice, a loop
  transfer is accepted when its loop target has the same active finally-context
  snapshot, including a loop wholly nested in an outer try/finally. A transfer
  that would leave a newly active finally is conservatively rejected with a
  cleanup-specific diagnostic until equivalent cleanup cloning is implemented;
  it is never lowered as a cleanup-skipping jump.
- **Consequences:** AST, dependency scanning, escape summaries, typed SSA, and
  LLVM lowering all represent the new semantics before native emission.
  Division/remainder pull the bundled arithmetic exception into source-path,
  classpath, archive, and explicit-link closed worlds. Tests must cover
  precedence, short-circuit side effects, conditional/loop phis, evaluated-once
  local/field/array lvalues, shift masking and generic closers, division edge
  cases and catches, valid and invalid loop transfers, O0-O3 execution, and
  relocated packaged compilation. Java-width promotion and numeric casts were
  subsequently implemented under D031.

## D034 - `System` identity and bulk array operations are typed intrinsics

- **Status:** Accepted
- **Context:** Java-shaped pools and collections need stable identity hashing and
  efficient overlapping bulk movement across primitive and reference arrays.
  Implementing these operations as ordinary Ironwood loops cannot inspect a
  value accepted as `Object`, cannot bypass a `hashCode` override, and would
  duplicate array-width and overlap logic throughout the library. Open-ended
  runtime reflection or a VM array registry would conflict with closed-world
  compilation.
- **Decision:** `ironwood.lang.System` declares the exact static APIs
  `identityHashCode(Object)` and `arraycopy(Object, int, Object, int, int)`.
  Semantic lowering recognizes only those bundled declarations and emits
  dedicated compiler-owned IR before LLVM. Identity hashing returns zero for
  null, otherwise uses allocation identity and never virtual dispatch.

  Every compiler-emitted type descriptor carries a class-versus-array kind tag.
  Every allocated array additionally records its exact element byte width and a
  closed element-kind tag beside its native length. The LLVM and C layouts stay
  aligned while ordinary object headers remain one descriptor pointer.
  `arraycopy` validates non-null operands, both array kind tags, exact array
  descriptor/element metadata equality, nonnegative positions and length, and
  both ranges before using `memmove`. Exact descriptor equality is intentionally
  conservative and consistent with Ironwood's invariant arrays; there is no
  Java-style covariant reference-array store check. Reference slots are copied
  as ordinary aliases and ownership is never transferred.

  The current runtime failure boundary terminates with diagnostics named after
  the closest bundled Java exceptions: `NullPointerException`,
  `IllegalArgumentException`, and `IndexOutOfBoundsException`. Safe-`free`
  summaries cannot yet describe copied per-element provenance, so an
  `arraycopy` destination is conservatively marked escaping.
- **Consequences:** All current primitive widths and reference arrays share one
  checked overlap-safe ABI without raw pointers in source or runtime loading.
  The larger array header and descriptor kind field are private bootstrap ABI
  details. A future element-sensitive alias proof may allow more destination
  containers to be freed, and future typed runtime throwing may replace the
  named termination boundary without changing valid-call semantics.

## D035 - Static fields are closed-world globals with constant-only initialization

- **Status:** Accepted
- **Context:** Java-shaped pool and data-structure implementations expose public
  constants such as `Integer.MAX_VALUE` and growth factors, and also use mutable
  class state. General Java class initialization would require ordered
  `<clinit>` execution, initialization locks, failure states, and runtime class
  lifecycle machinery that conflict with the current small closed-world model.
- **Decision:** A class field may be `static`, optionally `final`, and have any
  implemented primitive, reference, or one-dimensional array type. Each field
  becomes one compiler-owned typed program global with its exact primitive width
  or native reference representation. A missing initializer supplies zero,
  false, or null. A written initializer must be a compile-time constant composed
  from primitive/null literals, supported unary/binary/conditional operations,
  numeric casts, and accessible static-final primitive constants. Same-class
  unqualified references must name an earlier declaration; qualified
  cross-class references are evaluated recursively with cycle detection.

  Calls, allocation, array creation, string/object values, assignment, update,
  and runtime field loads are rejected in initializers. Therefore Ironwood adds
  no hidden initialization method, runtime ordering, initialization lock, or
  class-loading state. `final` currently applies only to static fields and
  requires a constant initializer. Mutable fields support unqualified
  same-class access and `Type.field` loads, plain/compound assignments, and
  updates. Static-through-instance and instance-through-type access are errors.
  The existing no-field-hiding rule applies uniformly to static and instance
  declarations.

  Typed IR represents globals, loads, and stores explicitly. LLVM emits internal
  globals or constants with exact integer widths and raw floating-point
  initializers. Storing a tracked allocation into any static reference publishes
  it for safe-`free` analysis; a reference loaded from a static has unknown
  allocation identity. Format-1 `.ironclass` and `.ironjar` payloads preserve
  source declarations, so final closed-world rebuilding needs no format change.
- **Consequences:** Public library constants retain familiar Java spelling
  without introducing runtime class initialization or boxing. Constant
  evaluation and dependency scanning must agree on type-versus-value shadowing,
  visibility, numeric promotion, narrowing, overflow, floating-point behavior,
  and source order. Future general instance initializers, static allocation or
  call initializers, and Java-compatible initialization cycles require a separate
  decision and runtime model.

## D036 - Checked reference casts reuse closed-world membership and language exceptions

- **Status:** Accepted
- **Context:** Java-shaped collection equality and interface-oriented APIs need
  explicit narrowing from `Object`, base classes, and interfaces. The compiler
  already emits complete nominal membership tables for `instanceof` and typed
  native exception CFG, so a second runtime casting registry or raw-pointer API
  would duplicate authoritative closed-world information.
- **Decision:** Identity and widening reference casts remain pointer-preserving
  conversions. A non-parameterized class/interface cast that is not a widening
  conversion is checked when the complete program contains a concrete type
  compatible with both source and target. Null branches directly to success.
  A matching non-null value reaches an ordinary
  `IrReferenceConversionInstruction`; a mismatch allocates and constructs the
  bundled `ironwood.lang.ClassCastException` and throws it through the existing
  invoke/landing-pad/finally model. The compiler currently uses the exception's
  no-argument constructor; runtime-class-formatted messages are deferred.

  Closed-world-disjoint nominal casts are compile-time errors. Except for the
  all-unbounded-wildcard reifiable subset added by D037, non-widening casts to
  parameterized types, casts involving type parameters, and array
  downcasts remain deterministic errors: generic arguments are not reified and
  array descriptors do not yet have target ids suitable for exact cast tests.
  Identity array casts and array widening to `Object` remain valid. The source
  dependency scanner conservatively brings `ClassCastException` into any closed
  world containing reference cast syntax; programs without such syntax keep it
  tree-shaken.
- **Consequences:** Checked casts require no new native runtime ABI and use the
  same class/interface membership truth as `instanceof` and catch selection.
  Successful narrowing never changes the address, and semantic lowering copies
  known allocation identity to the narrowed SSA value so live aliases still
  block `free` and an otherwise unique narrowed local remains reclaimable.
  Concrete generic checked/unchecked casts and exact array casts require
  separate type-system and metadata decisions. D037 subsequently defines the
  erased all-unbounded-wildcard subset.

## D037 - Unbounded wildcard views are reifiable and statically read-only

- **Status:** Accepted
- **Context:** Java-shaped collection equality, iterator traversal, and map
  comparison naturally use `Iterator<?>`, `List<?>`, and `HashMap<?, ?>`. Replacing
  `?` with `Object` would be unsound because it would permit non-null values to
  be written into an allocation whose actual argument may be more specific.
  Fully implementing bounded wildcards and capture conversion would be much
  larger than the subset required by the pool and data-structure ports.
- **Decision:** An unbounded `?` is a first-class source-precise semantic and IR
  type argument with Object bound. An invariant `G<T>` reference may widen to
  `G<?>`; each direct wildcard argument accepts any reference argument while
  concrete arguments remain invariant. A wildcard-dependent field, parameter,
  return, or array-element read is usable as `Object`. Only `null` may be passed
  or stored through that dependent type; even a value read through a wildcard
  is not assumed to have the same capture for a later write.

  `value instanceof G<?>` (and multi-argument forms such as `HashMap<?, ?>`) is
  reifiable as the raw nominal membership test. `(G<?>) value` uses D036's
  null-success/type-test/`ClassCastException` CFG and checks only raw nominal
  membership. Its success value retains wildcard arguments and the original
  allocation identity. Casts from `Object` to concrete parameterizations remain
  rejected because their arguments cannot be checked at runtime.

  Bounded wildcards, wildcard construction, wildcard superclass/interface
  declarations, raw generic targets, concrete-parameterized `instanceof`, and
  wildcard array construction remain rejected. Wildcards erase to `Object` at
  native linkage/layout boundaries. No descriptor, runtime registry,
  `.ironclass`, `.ironjar`, or bootstrap-runtime ABI change is introduced;
  format-1 artifacts preserve the source spelling for final closed-world
  reconstruction.
- **Consequences:** The standard library can expose Java-shaped read-only
  generic views without heap pollution or runtime generic metadata. Widening
  and checked-cast reference conversions copy safe-`free` allocation identity,
  so live wildcard aliases block reclamation while unique converted aliases can
  still be freed. Tests cover parsing and diagnostics, read/write capture rules,
  nested/multi-argument forms, typed IR and nominal CFG, archive reconstruction
  and tree shaking, safe-free identity, and native execution at `-O0` through
  `-O3`.

## D038 - Unbounded type parameters inherit the Object member bound

- **Status:** Accepted
- **Context:** Java-shaped generic collections invoke `equals`, `hashCode`, and
  `toString` directly on values of type `E`, and compare a possible element
  with the collection itself to render self references. Ironwood already gives
  every concrete class, interface, and array an Object root, but initially kept
  a type-parameter receiver as an opaque semantic kind with no member lookup.
  Rewriting every collection through private Object-typed helpers would obscure
  the Java API model and duplicate unchecked erasure work in the library.
- **Decision:** Every unbounded reference type parameter has the implicit upper
  bound `ironwood.lang.Object`. Member lookup for a type-parameter receiver uses
  Object, while the source variable and substituted signatures retain their
  exact type-parameter identity. Calls use ordinary null checks and closed-world
  virtual dispatch, so overrides of `equals`, `hashCode`, and `toString` remain
  observable. Reference equality involving a type parameter or unbounded
  wildcard selects Object as the common erased operand and compares native
  reference identity. No other Object-to-parameter write conversion is added.
- **Consequences:** Generic collection implementations keep their natural Java
  spelling without runtime generic metadata, reflection, boxing, or a new ABI.
  Method calls may conservatively have multiple closed-world dispatch targets,
  and existing escape summaries continue to apply. Future bounded type
  parameters will require lookup against their declared intersection bounds
  rather than always using Object.

## D039 - Allocation-count diagnostics observe the common runtime boundaries

- **Status:** Accepted
- **Context:** The pool and reusable data-structure APIs are designed to perform
  steady-state mutation and iteration without garbage production after their
  high-water storage is prepared. Native tests need a deterministic observation
  of that property; timing, allocator addresses, and process memory statistics
  cannot prove whether a language allocation occurred.
- **Decision:** `ironwood.lang.System.allocationCount()` is a typed intrinsic
  returning a process-wide `long` count. Its `IrAllocationCountInstruction`
  lowers to an atomic runtime snapshot. Each successful ordinary object and
  array allocation increments the counter once after allocation succeeds.
  Runtime-created String snapshots use the ordinary object boundary and count
  once; immortal compiler-emitted String literals and runtime-private native
  exception wrappers are excluded. The counter is cumulative rather than a
  live-object count, is never reset by language code, and uses relaxed atomic
  ordering because it reports allocation events rather than publishing object
  memory.
- **Consequences:** Integration tests can warm pools and collection capacity,
  compare snapshots around later mutation/iteration cycles, and fail on even a
  transient language allocation. Reading the count does not allocate, reclaim,
  establish ownership, or weaken safe-`free` analysis. The C function remains
  an internal bootstrap-runtime ABI rather than a native-interop contract.

## D040 - Ironwood project sources use an Ironwood-specific source root

- **Status:** Accepted
- **Context:** The examples initially reused Maven's Java source-root
  convention even though the contained files are Ironwood source and the
  project is not compiling them as Java. Package organization may remain
  Java-shaped without assigning Ironwood files to another language's source
  directory.
- **Decision:** The conventional application and example source root is
  `src/main/ironwood`. Package paths continue below that root, so canonical type
  `org.ironwood.example.Main` resides at
  `src/main/ironwood/org/ironwood/example/Main.iron`. The Java 21 bootstrap
  compiler remains under `compiler/src/main/java` because those files really are
  Java. The compiler's default source path remains `.`, and scripts pass
  `src/main/ironwood` explicitly when using the conventional project layout.
- **Consequences:** Every packaged example, source-path fixture, smoke test, and
  user-facing command uses the language-specific root. This changes only project
  organization; package names, `.ironclass` output layout, classpaths, and
  closed-world compilation semantics are unchanged.

## D041 - Exclusive private backing arrays may be reclaimed after detachment

- **Status:** Accepted
- **Context:** Array-backed pools, lists, and hash maps replace private backing
  arrays while growing. Retaining every superseded array was safe but consumed
  memory proportional to historical growth. Private visibility alone cannot
  justify `free`: same-class code can return the array, store an aliased
  parameter, publish it through another object, or reenter a method that replaces
  the field while an old local still aliases it.
- **Decision:** The compiler computes a closed-world ownership summary for each
  private instance array field. The field qualifies only when every assignment
  installs `null` or a directly created array and no load-derived container alias
  is returned, thrown, stored elsewhere, passed to a constructor, or supplied to
  an uncertain or retaining call. Element access and `.length` do not publish the
  container. Exact `System.arraycopy` is container-nonescaping even though copied
  element provenance remains conservative.

  Within a method, repeated loads of an eligible field on `this` share one loan
  identity. A different fresh array stored into the field detaches the previous
  identity; a same-value or conditional-only store does not prove detachment.
  `free` is accepted only for a detached local with no other live local alias.
  An attached loan cannot cross a potentially reentrant call. A detached loan may
  cross a migration loop only when every SSA backedge retains the same identity.
- **Consequences:** `ArrayObjectPool`, `StackObjectPool`, the three array-list
  implementations, and seven hash-map implementations now explicitly free their
  superseded backing arrays. Three retained-array helper types and the pool's
  retained-array test hooks are removed. Freeing an array remains non-recursive:
  copied or relinked user elements and pooled entries stay alive. Multi-array pool
  segments, pooled nodes, and map entries remain allocated as active reusable
  capacity. Negative tests cover publication, aliased construction, free before
  replacement, live local aliases, conditional replacement, reentrancy, and
  non-private fields; native growth and rehash tests run at `-O0` through `-O3`.

## D042 - Original and OpenJDK-derived source use explicit file-level licenses

- **Status:** Accepted
- **Context:** Ironwood needs a broad, Java-familiar standard library. Some APIs
  can be implemented independently, while selected algorithms may be
  substantially translated from OpenJDK. OpenJDK's GPLv2 license expressly
  treats translation into another language as modification, and the Classpath
  Exception applies only to files whose headers expressly designate it.
- **Decision:** Original Ironwood code, including independently implemented
  Java-compatible APIs and the direct contribution recorded by D029, is
  dual-licensed under `MIT OR Apache-2.0`. The dual license retains a simple
  permissive option and Apache-2.0's express patent grant.

  A substantial translation or adaptation of an OpenJDK implementation may be
  accepted only after the exact upstream file is verified to carry the
  Classpath Exception. The derived Ironwood file preserves the complete
  upstream header, identifies an immutable upstream commit and path, records
  its modifications, and uses
  `GPL-2.0-only WITH Classpath-exception-2.0`. Ironwood extends the Classpath
  Exception to its own modifications in such a file. Files without an express
  Classpath Exception are not imported without a separate licensing review.

  `docs/LICENSE_MECHANICS` governs classification, headers, provenance, and
  distribution. `docs/OPENJDK_PORTING.md` governs port execution, and
  `docs/SOURCE_PROVENANCE.md` is the file ledger. Release packages and the
  bundled standard-library archive carry the license texts and notices needed
  by the mixed repository, while loose standard-library source supplies
  corresponding source for covered library code.
- **Consequences:** Package names and API compatibility do not determine a
  file's license. A permissive facade, a derived algorithm helper, and an
  original native/compiler mechanism may share a package while retaining
  different file-level licenses. If substantial derived implementation is
  mixed into one source file, that complete file is treated as derived unless
  a documented review establishes another boundary. AI-assisted translation
  never changes provenance. Automated license checks reject missing or unknown
  SPDX expressions and require every derived path to appear in the provenance
  ledger.

## D043 - Process arguments use the canonical String-array entry point

- **Status:** Accepted and implemented. D124 supersedes only the `int`-only
  source return restriction.
- **Context:** The bootstrap no-argument entry point could not receive
  command-line parameters and was intentionally left open by D010. Ironwood
  should preserve Java's familiar `String[]` entry shape while retaining its
  established native `int` return as an explicit process status.
- **Decision:** An executable entry method has exactly the source signature
  `public static int main(String[] args)`; the parameter name is conventional
  rather than significant, and the fully qualified element type is equivalent.
  A no-argument `main()` is not an entry point. The native wrapper has the
  platform C shape `int main(int argc, char **argv)`, excludes `argv[0]`, and
  converts the remaining values from UTF-8 to immutable UTF-16
  `ironwood.lang.String` objects in a typed, non-null `String[]`. Each
  invalid input byte decodes as U+FFFD. The source method's `int` result is
  returned as the native exit status.
- **Consequences:** Existing source entry methods and examples use the
  `String[]` form, and the compiler diagnoses a selected class that offers
  only `main()`. Startup argument strings and their array are ordinary
  runtime-created allocations. They are counted by allocation diagnostics and
  remain allocated until process termination because Ironwood has neither a
  collector nor implicit reclamation. This refines D023 and D025 and supersedes
  D010's bootstrap signature without adding JVM startup machinery.

## D044 - Standard output uses an immortal `System.out` stream

- **Status:** Accepted
- **Context:** Milestone 7 exposed a temporary static output helper even though
  ordinary Java-shaped source writes through `System.out`. Ironwood already owns the portable
  UTF-16-to-UTF-8 encoding and process-output boundary needed by a stream
  facade; reproducing JVM bootstrap, native registration, security-manager,
  reflection, class-loader, or finalization machinery would conflict with the
  closed-world native design.
- **Decision:** `ironwood.lang.System` and `ironwood.io.PrintStream` are
  independently implemented, Java-compatible Ironwood facades licensed
  `MIT OR Apache-2.0`. Their implementation does not translate or adapt
  OpenJDK implementation bodies, comments, Javadocs, tests, or distinctive
  internal structure. OpenJDK source inspection for this slice is limited to
  verifying exact file headers and classifying relevant public members and JVM
  mechanisms; it does not make either Ironwood file OpenJDK-derived.

  `System.out` is a `public static final PrintStream` reference to one
  compiler/runtime-owned immortal object. Calls to its supported `println`
  surface use ordinary resolved static-field and instance-member semantics,
  compiler-owned typed IR, and the existing narrow synchronous native output
  boundary. The singleton is not an ordinary allocation, does not increment
  `System.allocationCount()`, cannot be explicitly reclaimed, and introduces no
  garbage collection or general native-interface facility. Portable facade
  behavior remains in Ironwood source; process/OS I/O and deterministic
  UTF-16-to-UTF-8 encoding remain behind the original Ironwood runtime
  boundary.
- **Consequences:** The temporary helper is removed rather than retained as a
  compatibility alias. `ironwood.lang` remains implicitly visible, while the
  public field type is the familiar `ironwood.io.PrintStream`. Static final
  reference initialization and chained static-field/instance calls are
  represented consistently through semantic analysis, typed IR, LLVM lowering,
  class/archive reconstruction, reachability, and safe-free analysis rather
  than recognized by parser spelling. This slice supports only the documented
  `println` surface. Standard input/error, stream replacement, formatting,
  flushing/error state, and other `System` services remain separate future
  work; JVM-specific bootstrapping and lifecycle behavior are deliberately
  excluded.

## D045 - General annotations are outside the language model

- **Status:** Accepted
- **Context:** Ironwood aims to preserve Java's readable object-oriented source
  model without inheriting every Java metaprogramming facility. General
  annotations would require syntax, type declarations, retention and target
  rules, metadata representation, processors, and a compatibility policy even
  though Ironwood deliberately excludes unrestricted runtime reflection and
  dynamic loading.
- **Decision:** Ironwood will not implement general annotation syntax,
  annotation types, annotation metadata, or annotation processing. Override
  correctness remains a structural compiler check based on inherited
  signatures, visibility, static/instance form, and covariant returns.

  D064 subsequently answers the separate override-intent question with one
  mandatory built-in method directive spelled exactly `@Override`. The lexer
  and parser do not treat it as a general annotation instance, and its support
  does not establish annotation declarations, arguments, qualification,
  metadata, processing, or reflection.
- **Consequences:** Standard-library ports must remove annotation dependencies
  and use ordinary language/compiler mechanisms instead. Compile-time source
  generation and metadata may still be designed independently where useful,
  but they cannot depend on annotations. Documentation must not list general
  annotations or annotation processing as deferred compatibility work.

## D046 - Complete the Java-shaped source object model before further library expansion

- **Status:** Accepted and implemented
- **Context:** The post-Milestone-8 language already had native objects,
  inheritance, interfaces, overloads, an initial erased reference-generic
  subset, and safe explicit reclamation, but important Java source-level object
  rules were missing. Expanding libraries on that subset would force library
  code to work around absent abstraction, nesting, defaults, initialization,
  and generic expressiveness, creating permanent non-Java-shaped APIs.
- **Decision:** Complete the source object model as one closed-world semantic
  slice. Ironwood supports concrete/abstract/final classes and methods;
  instance field initializers and initializer blocks; blank-final definite
  assignment; owner-qualified field hiding; all object-oriented `this` and
  `super` forms; static nested, member inner, nested-interface, local, and
  anonymous types; exact enclosing instances; explicit-final locals and
  parameters, effectively-final capture; and source-nest private access.

  Interfaces support constant fields and abstract, default, static, private
  instance, and private static methods. Default selection uses class precedence,
  the unique most-specific interface, re-abstraction, deterministic unrelated-
  default conflicts, and permitted `InterfaceName.super` selection.

  Reference generics support class/interface/method/constructor parameters,
  upper/intersection/dependent bounds, first-bound erasure, `?`, `? extends`,
  `? super`, fresh receiver and argument capture, per-candidate inference,
  expected types, least-containing parameterization, diamond, exact owner/member
  types, and fixed-arity invocation. Exact static generic types
  remain in semantic data and typed IR while reference implementations share
  native layout/linkage. A concrete parameterized narrowing cast is accepted
  only when source types and the complete hierarchy prove the erased arguments;
  otherwise it is rejected as unchecked. `instanceof` still requires a
  reifiable target.

  Thrown expressions and catch classes must derive from `Throwable`. A bounded
  type variable may be thrown when its bounds prove that relationship, but a
  catch target cannot be a type variable or non-reifiable parameterization. A
  generic class cannot directly or indirectly extend `Throwable`; generic
  callables on exception classes and non-generic static nested exception
  classes in a generic owner remain valid.

  Qualified anonymous construction is bound in semantic stages. The compiler
  first establishes named signatures, then type-plans the arbitrary primary
  expression exactly once, freezes its exact member declaration and owner view,
  and only then resolves anonymous hierarchy, overrides, layout, and lowering.
  Explicit lexical enclosing and qualified superclass enclosing operands remain
  distinct. Runtime evaluation is primary, immediate null check, source
  arguments, allocation, construction. No global simple-name guess or runtime
  type discovery is permitted.

  Member, local, and anonymous declarations receive deterministic closed-world
  identities, typed hidden operands/fields, and ordinary dispatch/type metadata.
  Format-1 artifacts continue embedding the owning source unit; lexical types
  are reconstructed from the owner `.ironclass` or `.ironjar` entry and are not
  separate classpath entries. Hidden enclosing/capture references participate
  in escape summaries, devirtualization, and safe-`free` like source fields.
- **Consequences:** This decision supersedes only the earlier deferrals of
  abstract/final types, field hiding and instance initialization, interface
  bodies/defaults, non-constructor `super`, nested/local/anonymous types,
  bounded/inferred reference generics, source-provable generic casts,
  and `Throwable` enforcement recorded in D020, D026, D032, D036, D037, and the
  Milestone-8 implementation record. Their historical milestone descriptions
  remain accurate for those earlier baselines.

  General annotations remain excluded under D045; override checking is always
  structural. Raw types, primitive/value generics, boxing, `new T()`,
  non-reifiable `instanceof`, unprovable unchecked casts, runtime generic
  reflection, runtime class loading, general runtime `<clinit>`, static imports,
  array downcasts, and a stable binary object ABI remain outside this completed
  slice. The decision changes no standard-library API and does not authorize a
  library migration.

## D047 - Varargs are excluded to preserve explicit allocation ownership

- **Status:** Accepted and implemented
- **Context:** D046 initially included Java-shaped variable-arity parameters and
  calls. Every expanded call required the compiler to create an array that had
  no ordinary source-level owner. Without a garbage collector, a hidden array
  that was neither returned nor otherwise exposed could not be explicitly
  freed and remained allocated until process termination. Repeated calls could
  therefore produce unbounded memory growth. Pooling, implicit reclamation,
  stack promotion, and escape-dependent ownership would each add substantial
  machinery and would still require precise treatment of recursion, exceptions,
  escaping arrays, nested or reentrant calls, and observable array identity.
- **Decision:** Ironwood does not support variable-arity parameters or expanded
  variable-arity invocation. A contiguous `...` in a parameter declaration is
  consumed only so the compiler can issue the direct diagnostic `varargs are
  not supported; declare an explicit array parameter instead`; it creates no
  varargs AST or callable metadata. Callable applicability and lowering are
  fixed-arity only and never synthesize a hidden argument array.

  Libraries use explicit array parameters or purposefully selected fixed-arity
  overloads. When adapting Java or OpenJDK APIs, this source-level difference
  must be documented rather than concealed behind compiler-created storage.
  Java varargs used inside the Java bootstrap compiler are implementation-language
  details and do not form part of the Ironwood source language.
- **Consequences:** The varargs example, positive integration fixtures,
  variable-arity overload phase, callable metadata, inference rules, and packed
  array lowering are removed. D047 supersedes only D046's former varargs
  portion; the rest of the completed Java-shaped object and reference-generic
  model remains implemented. This deliberate incompatibility prevents
  invocation syntax from allocating an unowned heap object and keeps ordinary
  arrays under explicit source-level lifetime control.

## D048 - Java-feature status distinguishes supported, roadmap, and excluded behavior

- **Status:** Accepted as language direction; no roadmap feature is implemented
  by this decision alone
- **Context:** A single “unsupported” or “partial” label obscured an important
  distinction. Some Java features are required for Ironwood's intended
  Java-shaped usability, while others conflict with explicit allocation,
  closed-world AOT compilation, or the deliberately small language/runtime.
  Several Java topics also mixed a safe implemented subset with a separate
  unsafe or deferred subset, for example ordinary casts versus unchecked generic
  casts, and one-dimensional invariant arrays versus Java array covariance.
- **Decision:** Public feature comparisons use exactly three states:

  - ✅ means implemented, tested, and working now;
  - ⏳ means committed to the roadmap but not yet implemented; and
  - ❌ means deliberately excluded unless a later explicit decision reverses
    the exclusion.

  This classification supersedes older generic “outside this slice” or
  “deferred” wording only where the feature lists below now make a definite
  roadmap commitment or exclusion. It does not rewrite the historical scope or
  implementation status of earlier milestones.

  Mixed topics are split so one row never combines statuses. The following
  current behavior remains supported: ordinary class/interface casts and
  `instanceof`, reifiable `G<?>` tests, closed-world source-provable concrete
  parameterized casts, explicit safe `free`, and one-dimensional invariant
  arrays.

  The roadmap now includes:

  - primitive generic arguments only through native specialization or a
    compatible allocation-free value layout, never implicit wrapper boxing;
  - multidimensional arrays with visible ownership plus exact invariant array
    casts and type tests, without array covariance;
  - Java-shaped checked exceptions and `throws` declarations;
  - a dedicated override-intent directive that does not introduce general
    annotations (subsequently implemented by D064 as mandatory `@Override`);
  - enums and classic value-based `switch`;
  - deterministic static initializer blocks and automatic one-time class
    initialization without runtime class loading; and
  - a deterministic try-with-resources construct and `AutoCloseable`-shaped
    contract that closes resources separately from freeing wrapper memory.

  D064 subsequently implements the dedicated override-intent item as mandatory
  `@Override`; the rest of this list records the roadmap classification at the
  time of D048 and is not a statement that Feature 60 remains pending.

  The following are deliberately excluded:

  - automatic garbage collection, automatic boxing/unboxing, varargs, and
    GC-triggered finalization because they conflict with visible explicit
    allocation and reclamation;
  - raw generic types, unchecked generic casts, non-reifiable generic
    `instanceof`, and Java array covariance because they intentionally weaken
    static type safety or require runtime failures for legacy compatibility;
  - general annotations and annotation processing; records; sealed types;
    lambdas, closures, and method references; and pattern variables/pattern
    `switch`;
  - unrestricted runtime reflection, runtime class loading, dynamic proxies,
    and runtime-generated language classes;
  - threads, object monitors, `synchronized`, atomics, thread-local storage,
    and concurrent collections for the foreseeable future; and
  - Java object serialization compatibility and `Object.clone()` machinery.

  Explicit application serializers, copy methods, compile-time generation, and
  closed-world registries remain possible because they do not recreate the
  excluded dynamic or hidden-ownership mechanisms.
- **Consequences:** `IRONWOOD_VS_JAVA.md` becomes the approachable status
  matrix, while `LANGUAGE_SPECS.md`, `OBJECT_MODEL.md`, `GENERICS.md`,
  `ROADMAP.md`, and `AGENTS.md` carry the same commitments and exclusions.
  At the time of D048, classic `switch` was correctly recorded as roadmap work
  rather than current support. Checked exceptions, enums, static initialization,
  resource cleanup, primitive specialization, and expanded array support also
  required separate design decisions. D049 subsequently completes checked
  exceptions, D050 implemented an initial resource construct, D051 supersedes
  it with ordinary first-failure `finally`, D055 completes static
  initialization, and D056 completes classic integral `switch`. D048 adds no
  compiler or standard-library API by itself.

## D049 - Checked exceptions are source contracts over native unwinding

- **Status:** Accepted and implemented
- **Context:** File and resource APIs need visible failure contracts that feel
  natural to Java programmers. Ironwood already has `Throwable` objects,
  ordered catches, `finally`, and cross-frame native unwinding, so checkedness
  should strengthen compile-time API contracts without creating a second
  runtime exception mechanism or error-return ABI.
- **Decision:** A `Throwable` subtype is checked unless it derives from
  `RuntimeException` or `Error`. Class methods, interface methods, and
  constructors may declare a comma-separated `throws` clause. Every checked
  exception raised explicitly or declared by the compile-time selected method
  or constructor must be assignable to an active enclosing catch or to one of
  the current callable's declared thrown types. Enforcement happens after
  overload selection, exact generic owner substitution, and callable inference.

  A throws type must be a non-parameterized exception class or a type variable
  whose bounds prove `Throwable`. Generic throws variables are substituted at
  each invocation. An override may remove or narrow checked exceptions but may
  not add a broader or unrelated checked exception; the same rule applies when
  an inherited class method supplies an interface implementation. A checked
  catch is rejected when no overlapping checked exception can arise from its
  try body. A narrow catch can therefore be reachable for a broader declaration
  without fully handling it. Catch targets that are themselves unchecked, or
  that admit an unchecked subtype, remain reachable without a declared source.

  Format-1 `.ironclass` files continue to embed validated source, so throws
  declarations are reconstructed and revalidated through source paths, loose
  class files, and `.ironjar` archives without an artifact-format change.
  Checked and unchecked exceptions use the same compiler-owned exceptional CFG,
  LLVM unwind ABI, runtime wrapper, and nominal catch dispatch. Compiler/runtime
  failures already represented by `RuntimeException` or `Error` subclasses stay
  unchecked. Existing fail-fast null receiver, array bounds, allocator, and
  similar native failures do not acquire an implicit checked contract.
- **Consequences:** Feature 58 is complete without standard-library file-I/O
  migration. The dedicated checked-exception example and tests cover explicit
  throws, propagation, catches, methods, constructors, interfaces, generic
  bounds, override compatibility, unreachable catches, class/archive round
  trips, and native execution at `-O0` through `-O3`. Multi-catch, causes,
  suppression, stack traces, try-with-resources, and catchable forms of current
  fail-fast native checks remain separate work.

## D050 - Try-with-resources closes existing resources without reclaiming wrappers

- **Status:** Superseded by D051; the implementation described here existed
  between D050 and D051
- **Context:** Native file, stream, socket, and handle wrappers need dependable
  cleanup on normal and abrupt exits. Java's declaration form would allow the
  construct itself to create a hidden-lifetime allocation, which conflicts with
  Ironwood's requirement that ordinary allocations retain a source-visible
  owner and reclamation point. Resource closure also must not be confused with
  reclaiming the wrapper object's memory.
- **Decision:** `try (resource)` accepts one or more semicolon-separated names of
  existing local variables or parameters. Each static type must implement the
  new `ironwood.lang.AutoCloseable` interface, whose contract is
  `void close() throws Exception`. The compiler captures the selected references
  once in source order, skips null captures, and invokes `close()` in reverse
  order on normal fallthrough, return, or exception propagation. User catches
  and finally clauses surround the generated cleanup. Repeated resource names,
  arrays, declarations, constructor calls, and arbitrary resource expressions
  are rejected. A trailing semicolon is accepted.

  Close selection uses the ordinary static receiver and generic-bound member
  rules. Declared checked close failures participate in the D049 catch-or-declare
  analysis. Cleanup is normalized to nested compiler-owned try/finally control
  flow, so it reuses typed calls, invoke/landing edges, return cleanup, native
  unwinding, and nominal catch dispatch without a new IR operation or runtime
  ABI. Format-1 class files continue to preserve validated source and therefore
  require no format change.

  `close()` releases only the external capability defined by the resource. It
  never performs `free`, recursively reclaims fields, or introduces automatic
  reclamation. A compiler-owned capture blocks `free` while cleanup is active;
  after the construct completes on a continuing path, the wrapper may be freed
  only if the ordinary safe-free proof succeeds. Until exception suppression is
  implemented, cleanup uses the already-defined finally precedence: an abrupt
  close replaces a pending body or inner-close failure. Break and continue that
  would leave the cleanup boundary remain conservatively rejected under the
  existing finally rule.
- **Historical consequences:** Feature 72 was completed as an ownership-honest deterministic
  cleanup slice. The runnable resource example demonstrates two captures,
  reverse closing, and subsequent explicit wrapper reclamation. Focused tests
  cover syntax and diagnostics, null resources, normal/return/exception cleanup,
  close ordering and failure precedence, checked close contracts, generic
  bounds, safe-free interaction, source/class/archive reconstruction, and native
  execution at `-O0` through `-O3`. Suppressed-exception storage, causes, stack
  traces, `ironwood.io.Closeable`, and actual file/stream APIs remained later
  work. D051 removes this resource syntax and replaces its failure precedence.

## D051 - Ordinary finally preserves the first exception

- **Status:** Accepted and implemented; supersedes D050's resource syntax and
  failure-precedence rules
- **Context:** D050 avoided Java's hidden resource allocation by accepting only
  existing variables in `try (resource)`, but it retained two deeper problems.
  Resource cleanup still used a special syntactic construct, and ordinary
  `finally` still discarded a pending body exception when cleanup also threw.
  The body failure happened first and normally describes the operation the
  caller requested; a later cleanup failure must remain observable without
  replacing it or being mislabeled as its cause.
- **Decision:** Ironwood deliberately rejects Java's complete `try (...)`
  resource syntax, including declaration and existing-variable forms. A
  resource is declared normally and closed by an explicit call in an ordinary
  `finally` block. `AutoCloseable` remains available only as a normal interface
  contract and receives no compiler privilege. `close()` releases an external
  capability and remains independent from compiler-proven object-memory
  `free`.

  If a try body or catch is propagating exception `A` and its `finally` block
  throws `B`, `A` remains primary. The compiler appends `B` to an ordered list
of secondary exceptions associated with `A`, then continues propagating the
same `A` object. Nested cleanup failures flatten onto that same list in
occurrence order. If no
  exception was pending, `B` is primary as usual. This applies to every
  exception escaping `finally`; the compiler does not recognize `close()`
  specially. A return from `finally` retains its existing precedence, and loop
  transfers crossing a `finally` boundary remain conservatively rejected.

  `Throwable.getSecondaryExceptionCount()` reports the list size and
  `Throwable.getSecondaryException(int)` reads by occurrence order without
  allocating a result array. An invalid index terminates with a deterministic
  diagnostic. Association has no public mutator. Secondary exceptions are not
  causes: cause chaining remains separate future work.

  Exceptional-finally lowering installs a compiler-owned landing region and
  records the association with `IrAddSecondaryExceptionInstruction` before
  rethrowing the primary. The native runtime keeps ordered association nodes in
  runtime-private storage, outside Ironwood allocation accounting, and exposes
  direct count/index operations. Uncaught diagnostics print the primary type
  followed by its directly associated secondary types. Thrown exception
  objects already escape safe-`free` analysis, so this metadata introduces no
  untracked reclaimable source alias. Format-1 `.ironclass` and `.ironjar`
  payloads remain unchanged.

  Ordinary try lowering now preserves an existing allocation's exact identity
  across the structured region when SSA and escape analysis prove it unchanged.
  This permits an explicit wrapper `free` after source-written cleanup on a
  continuing path, while frees within or across uncertain paths remain
  rejected.
- **Consequences:** Feature 72 is complete as a 💡 Ironwood alternative rather
  than a Java syntax clone. The parser gives a focused migration diagnostic for
  `try (...)`. The resource example uses only ordinary declarations,
  `try`/`finally`, explicit `close()`, and separate `free`. Focused tests cover
  parser rejection, checked cleanup calls, typed-IR/runtime association,
  first-exception identity and type, nested occurrence order, allocation-free
  count/index inspection, deterministic bounds and uncaught diagnostics,
  safe-free interaction, source/class/archive reconstruction, and native
  execution at `-O0` through `-O3`. Causes, stack traces,
  `ironwood.io.Closeable`, and actual file/stream APIs remain later work.

## D052 - Copying is type-owned ordinary code, not `Object.clone()` machinery

- **Status:** Accepted and implemented
- **Context:** Java's inherited `Object.clone()` performs a privileged
  field-by-field copy without ordinary construction and uses the empty
  `Cloneable` marker to authorize it. That universal shallow-copy mechanism does
  not describe whether a type should copy referenced state, share it, duplicate
  native resources, or transfer ownership. Ironwood needs copying when a type's
  domain calls for it, but does not need a hidden runtime operation whose default
  behavior is ambiguous under explicit reclamation.
- **Decision:** `ironwood.lang.Object` does not declare `clone()`, Ironwood does
  not provide `Cloneable`, and neither the compiler nor runtime synthesizes or
  performs object copying. There is no automatically generated copy constructor
  or copy method.

  A type that supports copying exposes ordinary source code: for example, a copy
  constructor, `copy()`, `duplicate()`, `snapshot()`, or another domain-specific
  operation. Its API owns the complete contract, including whether each
  reference is shared or recursively copied, how external resources are handled,
  whether a fresh allocation is returned, and which party may eventually
  `free` it. Implementations use normal constructors, allocations, field access,
  method calls, checked exceptions, and safe-`free` rules.

  The identifier `clone` is not reserved and has no compiler privilege. A class
  may declare a method named `clone()`; it participates in ordinary visibility,
  overload, override, dispatch, throws, allocation, and reclamation analysis.
  It gains no inherited implementation, marker-interface relationship,
  field-copying behavior, or constructor bypass. Future specialized array or
  collection copy APIs may be introduced when concrete library requirements
  justify them, but they remain ordinary APIs with explicit ownership contracts
  rather than a universal cloning protocol.
- **Consequences:** Feature 71 remains ❌ because Ironwood does not support
  Java cloning. Explicit type-owned copying is possible without being presented
  as an alternative implementation of `Object.clone()`. No compiler intrinsic,
  runtime ABI, root-object member, or standard-library marker is added. The
  object tests exercise an application-defined `clone()` that calls a normal
  copy constructor, confirming that the spelling works solely through ordinary
  method and construction semantics.

## D053 - Java-shaped uncaught exception stack traces are committed roadmap work

- **Status:** Accepted roadmap commitment and selected next implementation
  target; not implemented
- **Context:** Ironwood already propagates language exceptions across native
  frames and deterministically reports an uncaught exception's qualified type.
  D051 also retains later cleanup failures as secondary exceptions. A type name
  without the throwing call path is nevertheless inadequate for diagnosing
  failures in realistic native programs. Stack traces were previously described
  only as unscheduled deferred work.
- **Decision:** Feature 73 commits automatic Java-shaped stack traces for
  uncaught Ironwood `Throwable` objects and places them first in the pending
  feature priority. At minimum, an uncaught report will contain the qualified
  exception type, its message when present, and ordered source-level frames that
  identify the Ironwood callable, `.iron` filename, and line number. The result
  must remain useful from `-O0` through `-O3` and when the closed world is
  reconstructed through source paths, `.ironclass` files, or `.ironjar`
  archives. Embedded trace metadata does not create runtime reflection, runtime
  class loading, or a public stable native ABI.

  Before implementation, a separate design must define capture timing, rethrow
  behavior, representation of optimized and inlined calls, primary/secondary
  trace formatting, metadata retention and tree shaking, runtime-private
  storage and failure behavior, and whether the first slice exposes
  `printStackTrace()` or programmatic inspection. Java's allocated mutable
  `StackTraceElement[]` API is not automatically adopted because its ownership
  and reclamation would need an explicit Ironwood contract. Existing fail-fast
  native diagnostics are outside Feature 73 until they become catchable
  `Throwable` failures through separate decisions.
- **Consequences:** Stack traces change from merely deferred to explicit ⏳
  roadmap work, without claiming present support. Milestone 5 and D026 remain
  accurate historical records of the current type-only uncaught diagnostic.
  Causes, Java-compatible suppression, multi-catch, and conversion of fail-fast
  checks remain separately deferred. D053 changes documentation and priority
  only; it adds no compiler, runtime, standard-library API, metadata, or test.
  The selected implementation task must add a subsequent decision that freezes
  the representation before making stack-trace behavior observable.

## D054 - Uncaught traces snapshot compiler-maintained source frames on first throw

- **Status:** Accepted and implemented; D121 supersedes first-throw capture,
  process-lifetime metadata storage and the public trace API omission. D132
  supersedes the continuously maintained source-frame mechanism.
- **Context:** D053 commits source-level uncaught traces, but native return
  addresses alone do not preserve deterministic Ironwood call sites through
  LLVM optimization and would require platform symbolization. Java's mutable
  `StackTraceElement[]` surface would also introduce a hidden language
  allocation without a source-level owner. Ironwood already owns exact source
  spans in typed IR and keeps runtime-private metadata for D051 secondary
  exceptions, so the trace can remain a closed-world crash-report facility.
- **Decision:** Every emitted Ironwood function carries its stable source
  filename and constructor identity in compiler-owned typed IR. LLVM lowering
  gives the function a runtime-private shadow frame in native stack storage.
  The frame names the qualified callable and basename-only `.iron` source file.
  Before an Ironwood call, the caller frame records that call expression's
  starting line; before an explicit or compiler-created catchable throw, the
  current frame records the throwing operation's starting line. Thus a trace
  runs from the failure outward: its first entry is the throw site, and each
  remaining entry is the caller's call-site line.

  A callable is printed as `<qualified-owner>.<method>`. Constructors use
  `<qualified-owner>.<init>`. Member, local, and anonymous owners use their
  deterministic closed-world binary names, including existing `$` nesting and
  lexical ordinals, so no reflection or runtime name discovery is required.

  `ironwood_throw` snapshots the active source frames immediately before the
  first native unwind of an exception object. Throwing that same object again,
  whether with `throw caught;` or through compiler propagation, preserves the
  original snapshot and object identity. Landing pads reset the live shadow
  chain to their own frame after native unwinding, while normal returns remove
  their frame. Shadow-frame operations are explicit consequences of typed-IR
  function and operation provenance; they are not AST-to-LLVM exception
  lowering.

  The uncaught primary header is
  `uncaught Ironwood exception: <qualified-type>` and a non-null message adds
  `: <message>`. A null message adds no suffix. Source frames follow as
  `\tat <qualified-callable>(<file>.iron:<line>)`. Each directly associated
  D051 failure then uses
  `secondary Ironwood exception: <qualified-type>`, the same optional-message
  rule, and its own independently captured frames, in occurrence order. The
  existing flattened nested-cleanup ordering is unchanged.

  Active frames and compiler-emitted UTF-8 names are runtime-private native
  storage and constants. Captured frame arrays and exception metadata use the
  C allocator, do not increment `System.allocationCount()`, are not Ironwood
  objects, and live until process exit because thrown objects cannot be proven
  safe to `free`. If private metadata allocation fails, throwing and exception
  identity still proceed; an eventual uncaught report prints the type/message
  and `\tat <trace unavailable>` instead of replacing the language exception.

  Emitted metadata exists only for functions in the reconstructed closed
  world, is referenced from those functions, and therefore follows ordinary
  function reachability and LLVM dead stripping. LLVM optimization and
  inlining remain enabled at `-O0` through `-O3`: inlined Ironwood functions
  retain their shadow-frame enter/update/leave operations, so source-level
  frames do not depend on physical native frame boundaries. No global
  no-inline rule is introduced.

  Filenames are `.iron` basenames rather than absolute or source-root-relative
  build paths. A basename is stable across relocated source paths, class
  directories, individual `.ironclass` inputs, and `.ironjar` inputs; the
  qualified callable disambiguates equal basenames. Format 1 already embeds
  validated source, so reconstruction derives the same filename and source
  spans without changing the serialized format.

  The first slice is crash-report-only. `Throwable` gains no `printStackTrace`,
  mutable trace, frame-array, or programmatic inspection API. The runtime
  boundary remains private and unstable; the only language-visible change is
  automatic stderr output for otherwise uncaught catchable `Throwable`
  objects. Fail-fast null, bounds, allocation, and similar native diagnostics
  remain outside this decision.
- **Consequences:** Feature 73 is complete without raw-address symbolization,
  external tools, runtime reflection, class loading, automatic language
  reclamation, or a stable native ABI. Trace capture preserves checked-
  exception rules, first-failure precedence, secondary occurrence order,
  allocation accounting, and safe-`free` conservatism. A future public trace
  API, causes, Java-compatible suppression, trace elision/compression, or
  multithreaded runtime metadata requires a separate decision.

## D055 - Active use performs deterministic one-time type initialization

- **Status:** Accepted and implemented. D133 refines the native fast-path
  lowering without changing these semantics.
- **Context:** Ironwood static fields currently accept only compiler-evaluated
  initializers, and every static value is present before native entry. That
  restriction avoids initialization order entirely, but it also excludes
  Java-shaped static initializer blocks, runtime-valued static fields, and the
  familiar rule that a type initializes immediately before its first active
  use. The closed-world native model can provide those semantics directly
  without a class loader, reflection, JVM linkage state, or a general runtime
  registry.
- **Decision:** A named class may contain `static { ... }` blocks. Interface and
  anonymous-class bodies may not contain explicit static blocks; interfaces
  may still have runtime-valued field initializers. Static field initializers
  and static blocks execute in textual order within their declaring type.
  Static storage has its ordinary zero or null value before those actions run,
  so reentrant initialization can observe partially initialized state.

  A `static final` primitive field whose initializer is a valid compile-time
  constant retains the existing constant representation, is available without
  initializing its owner, and contributes no runtime action. All other source
  initializers, including literal initializers of mutable static fields and
  runtime-valued `static final` fields, execute in source order. A blank
  `static final` class field may be assigned exactly once by the declaring
  type's static initialization actions and must be definitely assigned when
  they complete. Interface fields continue to require an initializer. The
  compiler-owned immortal `System.out` singleton remains a preinitialized
  implementation intrinsic rather than a source initialization action.

  A type's initialization is triggered immediately before any of these active
  uses:

  - native invocation of the selected entry class's `main`;
  - creation of an instance of a class;
  - invocation of a static method declared by the type; or
  - a read or write of a non-constant static field declared by the type.

  Reading a compile-time constant does not initialize its owner. Type mentions,
  imports, class literals if later introduced, casts, `instanceof`, and access
  to an inherited static member through a subtype do not initialize the
  mentioned subtype; the member's declaring owner is the active type. Receiver,
  qualifier, and argument expressions retain their existing evaluation order,
  while the required initialization completes before allocation, field access,
  or entry into the invoked static method.

  Before a class performs its own actions, it initializes its superclass. It
  then initializes, in deterministic source-order depth-first order, each
  superinterface that declares a default method and the prerequisite
  superinterfaces needed to reach it; an already visited interface is skipped.
  Initializing an interface directly does not initialize its superinterfaces.
  These rules follow Java's useful observable ordering without adopting JVM
  loading or linkage phases. Member, static nested, local, and named nested
  types have independent state and initialize only on their own active use.

  Each reachable class or interface has compiler-emitted private state:
  uninitialized, initializing, initialized, or failed, plus a private failure
  reference. On an uninitialized request, the state changes to initializing
  before prerequisites or source actions run. A recursive request for a type
  already initializing returns immediately, which makes cycles deterministic
  and exposes the current partially initialized static values. An initialized
  request is a no-op. Threads remain outside Ironwood, so the first
  implementation requires no locks, waiting protocol, or thread ownership.

  Static initialization has no `throws` declaration. Checked exceptions must
  be caught within the field initializer or block under the normal
  catch-or-declare analysis. If an unchecked `Throwable` escapes a prerequisite
  or source action, the dependent type becomes failed and stores that exact
  exception object. The same object continues outward, preserving D054's
  first-throw trace and D051 secondary failures. Every later active use of the
  failed type throws that same stored object. Ironwood deliberately does not
  synthesize Java's `ExceptionInInitializerError` or
  `NoClassDefFoundError`; doing so would replace identity, require additional
  allocations, and obscure the original native failure.

  The compiler represents each type's ordered initialization actions and
  prerequisite types in compiler-owned typed IR. A synthetic source-level
  callable named `<clinit>` contains runtime field assignments and blocks, so
  ordinary expression, checked-exception, control-flow, escape, explicit-free,
  and D054 trace rules apply. Its frames are reported as
  `<qualified-owner>.<clinit>` at the exact failing field or block line.
  Explicit typed-IR ensure operations mark active-use sites and can unwind
  through ordinary exception regions. LLVM lowering emits closed-world private
  state, failure storage, and one ensure routine per retained type; this is not
  AST-to-LLVM lowering and introduces no runtime type lookup.

  Initialization state and failure slots are private native globals. They are
  not Ironwood allocations, do not affect `System.allocationCount()`, and do
  not weaken safe-`free` analysis. Objects allocated by source initialization
  code remain ordinary Ironwood allocations, and storing one in a static field
  retains the existing escaping-reference consequences. A stored initialization
  failure is already an escaping thrown object and cannot be reclaimed through
  a source `free`.

  Reachability keeps initialization metadata only for types retained in the
  final closed world. The optimizer may inline ordinary code and initializer
  bodies, but it may not remove or reorder observable ensure operations,
  prerequisite ordering, or source actions; behavior is identical at `-O0`
  through `-O3`. Format-1 `.ironclass` source payloads preserve initializer
  syntax and spans, so source-path, class-directory, individual-class, and
  `.ironjar` reconstruction produce the same state graph and diagnostics
  without changing the artifact format.
- **Consequences:** Feature 69 adds lazy, deterministic static initialization
  while preserving ahead-of-time closed-world compilation and explicit
  reclamation. Programs may intentionally use partial zero/null state to break
  reentrant cycles, although simpler acyclic initialization is easier to
  understand. Initialization failures are allocation-free at the mechanism
  boundary and retain their original exception identity and trace. There is no
  public initialization-state API, runtime class loading, reflection, JVM
  compatibility layer, stable native ABI, or multithreaded initialization
  protocol. General threads or a future public type-metadata facility would
  require a new decision that revisits synchronization and observability.

## D056 - Classic switch is an integral value statement with explicit fallthrough

- **Status:** Accepted and implemented
- **Context:** Feature 65 is the highest-priority remaining Java-shaped language
  feature. Ironwood already has structured branches and loops, integral
  constant propagation, static-final primitive constants, compiler-owned SSA
  control flow, and LLVM lowering, but source code must currently spell every
  value dispatch as an `if` chain. Implementing `switch` before enums also
  establishes the control-flow and backend representation that Feature 61 can
  extend without coupling enum design to parser or CFG mechanics.
- **Decision:** Ironwood supports the classic colon-form `switch` statement:
  `switch (selector) { case constant: statements default: statements }`.
  The selector is evaluated exactly once. The first slice accepts `byte`,
  `short`, `char`, and `int` selectors, matching Java's primitive classic-switch
  domain. `boolean`, `long`, floating-point, reference, boxed, String, and enum
  selectors are rejected; Feature 61 may add enum selectors after it defines
  enum identity and initialization.

  Every `case` label is a side-effect-free compile-time integral constant
  expression. Integer and character literals, integral unary/binary/conditional
  expressions and casts, and accessible `static final` primitive constants are
  accepted under the existing constant-expression and numeric-conversion rules.
  A label must be assignment-compatible with the selector type, including the
  existing representable-constant narrowing from `int` to `byte`, `short`, or
  `char`. Labels whose converted values are equal are duplicates and are
  rejected even if their source spelling or original primitive type differs.
  At most one `default` label is allowed. Compile-time constant references do
  not actively initialize their declaring types.

  Labels may be consecutive and select the same statement group. Execution
  enters the matching group, or `default` when no case matches, and then falls
  through subsequent groups in source order until an abrupt statement or the
  closing brace. An unmatched switch without `default`, an empty switch, the
  reachable end of the final group, or a reachable switch-targeting `break`
  completes normally. Consequently, a switch with `default` whose every
  possible entry completes abruptly can satisfy value-returning control-flow
  analysis.

  The switch braces form one lexical scope, as in a classic Java switch. A
  declaration is not initialized on a direct jump to a later label; use of such
  a value is rejected unless every incoming path has initialized it. Nested
  braces remain the straightforward way to give individual groups independent
  scopes. Unlabeled `break` exits the nearest loop or switch. `continue` still
  targets the nearest enclosing loop, including from inside a switch nested in
  that loop. Labeled statements and labeled transfers remain unsupported. The
  existing restriction on transfers across a newly active `finally` boundary
  continues to apply, so a switch-targeting `break` may not skip required
  cleanup.

  The AST preserves switch labels, groups, constants, and source spans. Semantic
  lowering validates labels and produces a dedicated `IrSwitchTerminator` with
  typed constant destinations plus ordinary blocks, jumps, phis, and abrupt
  terminators for group bodies and fallthrough. LLVM lowering maps that typed
  terminator mechanically to LLVM's `switch`; it does not re-read the AST or
  synthesize a runtime dispatch table. Safe-`free` treats the construct as
  structured branching and conservatively rejects reclamation whose proof
  crosses uncertain case flow.

  Arrow rules, switch expressions, `yield`, comma-separated modern labels,
  pattern matching, null cases, exhaustiveness analysis, and implicit breaks
  are not part of Feature 65. The construct adds no runtime API, allocation,
  reflection metadata, class-loading behavior, or stable native ABI. Source
  payloads in format-1 `.ironclass` files retain the syntax and spans, so source
  paths, class directories, individual class files, and `.ironjar` archives
  reconstruct identical semantics. LLVM optimization remains enabled and the
  observable behavior must agree from `-O0` through `-O3`.
- **Consequences:** Feature 65 provides familiar allocation-free value dispatch
  and establishes a reusable typed-IR path for future enum selectors while
  keeping modern pattern-switch machinery excluded. Parser, semantic,
  reachability, blank-final and local scope, safe-`free`, typed-IR, LLVM, native
  optimization-level, artifact-round-trip, example, package, and documentation
  coverage now enforce the completed design.

## D057 - Enums are closed-world immortal singleton domains

- **Status:** Accepted and implemented. D122 supersedes the missing shared-base
  boundary, and D124 supersedes the missing `values()` boundary.
- **Context:** Feature 61 was the next selected Java-shaped language feature.
  Ironwood needs named finite domains with stable identity, declaration order,
  ordinary nominal typing, constructors and members, and classic `switch`
  integration. Java's enum surface is useful, but its implicit `Enum<E>` base,
  reflection-backed lookup, and fresh mutable array returned by `values()` are
  not suitable foundations for Ironwood's closed-world native and explicit-
  reclamation model. In particular, a compiler-created result array would have
  no source-level owner responsible for a safe `free`.
- **Decision:** Ironwood supports top-level and member `enum` declarations.
  A member enum is implicitly static; an enum declared in an interface is also
  implicitly public. Local enums, anonymous enum declarations, generic enum
  declarations, explicit enum `extends`, and constant-specific class bodies
  are not in the first slice. An enum may implement ordinary interfaces and
  may declare fields, instance/static initializer blocks, constructors,
  methods, and member types under the existing access, override, initialization,
  checked-exception, and generic-member rules.

  Every enum is a distinct nominal reference type, implicitly final and
  non-abstract, with `ironwood.lang.Object` as its direct superclass. The first
  slice introduces no common `Enum` base class. Enum values therefore widen to
  `Object` and implemented interfaces, participate in ordinary overload
  resolution, arrays, reference generics, casts, `instanceof`, and `==`/`!=`
  identity comparisons, but unrelated enum types never interconvert. Source
  code cannot invoke an enum constructor with `new`, subclass an enum, or
  assign an enum constant field.

  The comma-separated constant list is the first part of the enum body, permits
  a trailing comma, and is followed by an optional semicolon and ordinary
  members. Each constant may pass a fixed argument list to an enum constructor.
  Enum constructors are implicitly private; explicit `public` or `protected`
  access and explicit `super(...)` are rejected. Private or unmodified source
  spelling is accepted, `this(...)` delegation remains available, and a
  no-argument private constructor is synthesized when none is declared.
  Generic and variable-arity constructor machinery is not added by this
  feature. Existing field/instance-initializer order runs after the implicit
  `Object` construction and before the selected constructor body exactly once.

  Each constant has one compiler-emitted object in private native image
  storage. That storage is immortal, has ordinary enum layout and descriptor
  identity, and is mutable only as required to run instance initialization and
  the enum constructor. It is not an Ironwood allocator result, never changes
  `System.allocationCount()`, and cannot be reclaimed by source `free`. The
  corresponding source-visible field is implicitly `public static final` and
  is initially null. D055's enum `<clinit>` constructs constants in declaration
  order as its first source-ordered actions, assigning each field only after
  its constructor completes, then runs user static field initializers and
  static blocks. A constructor or initializer may therefore observe already
  completed earlier constants and null for the current or later unassigned
  fields during a reentrant request. Superclass and default-method-interface
  prerequisites, reentrant partial state, exact-object failure caching,
  secondary exceptions, and first-throw traces remain exactly those of D055,
  D051, and D054. Checked exceptions may not escape enum initialization because
  `<clinit>` has no `throws` contract.

  Every enum exposes compiler-synthesized `public final String name()`,
  `public final int ordinal()`, default `public String toString()`,
  `public static int valueCount()`, `public static E valueAt(int)`, and
  `public static E valueOf(String)`. `name()` returns the pooled immutable name
  literal and `ordinal()` returns the zero-based declaration index.
  `toString()` returns `name()` unless the enum declares its own valid override.
  `valueAt` returns the constant at that ordinal; a negative or too-large value
  throws a catchable `IllegalArgumentException`. `valueOf` uses String content
  equality and throws the same catchable type for null or an unknown name.
  These synthesized operations reserve their exact signatures and preserve
  ordinary initialization-on-static-use and receiver null checks.

  Java's `values()` method is deliberately absent in this slice. Returning a
  fresh array would create an unowned hidden allocation, while returning a
  shared array would expose mutable immortal global state. Source code uses
  `valueCount()` plus `valueAt(int)` when ordered traversal is needed. A future
  immutable, non-owning view may add a closer collection surface only after a
  separate ownership decision.

  Classic enum `switch` accepts a selector of one exact enum type and
  unqualified constant labels from that same type. Labels do not evaluate or
  initialize fields, duplicate constants and duplicate `default` are rejected,
  and all D056 scope, fallthrough, transfer, and definite-return rules remain
  unchanged. The selector is evaluated once, checked for null through the
  existing fail-fast null boundary, and dispatched by its immutable ordinal.
  Qualified labels, labels from another enum, null labels, exhaustiveness-based
  return completion, arrow rules, expressions, and patterns remain unsupported.

  Enum constants and their initialization are explicit in the AST/semantic
  model and compiler-owned typed IR. An `IrEnumConstant` identifies each
  immortal object; enum `<clinit>` contains typed constructor calls and static
  stores; ordinary typed field loads expose the hidden name and ordinal; and
  `IrSwitchTerminator` continues to carry the final integer destinations.
  LLVM lowering mechanically emits private mutable object storage and existing
  initialization/control-flow operations. No enum-specific C runtime entry,
  runtime registry, reflection metadata, class loading, public native ABI, or
  AST-to-LLVM shortcut is introduced.

  Closed-world reachability retains enum object storage, name literals,
  initialization metadata, constructors, and synthesized methods only with the
  reachable enum/type operations that reference them. Format-1 source payloads
  retain enum syntax and spans, so source paths, class directories, individual
  `.ironclass` files, and `.ironjar` archives reconstruct the same constants,
  identities, order, diagnostics, and private metadata. LLVM optimization and
  inlining remain enabled at `-O0` through `-O3`.
- **Consequences:** Feature 61 supplies finite nominal domains, stable singleton
  identity, constructors and members, allocation-free lookup/traversal, and
  classic enum switching without a collector or hidden ordinary allocations.
  It intentionally differs from Java at the common base type, `values()` API,
  invalid-lookup exception detail, constant-specific bodies, and local enums.
  D058 separately commits constant-specific bodies. A future shared enum base,
  immutable values view, or richer exhaustive switch requires a separate
  decision.

## D058 - Enum constant bodies are compiler-owned final subtypes

- **Status:** Accepted roadmap design; implemented by D062
- **Context:** D057 deliberately shipped the common enum core without Java's
  constant-specific class bodies. This leaves an important Java idiom
  unavailable: an enum can implement an interface while each constant supplies
  its own behavior, alongside shared constructor state and shared methods. The
  Java model treats each such body as an anonymous direct subclass of the enum;
  see [JLS 8.9.1](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.9.1).
  Ironwood already has closed-world anonymous-class identities, member and
  capture analysis, layouts, constructor forwarding, virtual/interface
  dispatch, static initialization, tree shaking, and artifact reconstruction.
  The remaining question is whether enum constants may reuse that machinery
  without adding ordinary allocations, source-level enum subclassing, or a
  general sealed-type feature.
- **Decision:** Feature 74 commits Java-shaped constant-specific enum class
  bodies as future language work. A body may use the ordinary member surface
  supported for Ironwood anonymous classes, including fields, instance
  initializers, methods, and member types, but cannot declare a constructor or
  an abstract method. Each bodied constant has a deterministic compiler-only
  final subtype whose direct superclass is the declaring enum. An unbodied
  constant uses the enum itself as its concrete type. The constant's public
  static-final field and every source reference retain the declared enum type;
  the hidden concrete type participates in normal layout, runtime type
  identity, virtual/interface dispatch, casts to its visible supertypes, and
  closed-world devirtualization.

  The compiler validates obligations per concrete constant. A method declared
  by an implemented interface may be supplied by the enum body for all
  constants or by every constant's concrete body. The enum body may also
  declare an abstract instance method only when every constant has a body that
  supplies it. Any unbodied constant must receive a concrete implementation
  from the enum or its inherited defaults. No constant body may leave an
  abstract obligation unresolved. Members unique to a constant body are not
  available through the source-visible enum type; ordinary calls can reach its
  methods only when they override an accessible enum or interface member. The
  enum remains impossible to construct with source `new` or extend in source;
  its compiler-owned finite subtype set does not add Feature 63 sealed classes
  or interfaces.

  Each constant still occupies one compiler-emitted immortal object and is
  built by the enum's D055 initialization in declaration order. For a bodied
  constant, hidden construction initializes the enum prefix through the
  selected private enum constructor, then initializes the subtype body under
  the same constructor/unwind rules used by anonymous classes. Its private
  storage, descriptor, and dispatch table use the concrete subtype while
  `name`, `ordinal`, enum identity, lookup, traversal, and classic enum switch
  continue to operate through the inherited enum prefix. Construction failure,
  reentrant partial state, exact-object failure caching, source traces, and
  secondary failures retain D051, D054, D055, and D057 behavior.

  The AST must retain the optional body instead of diagnosing and discarding
  it. Semantic discovery assigns its stable lexical identity and checks body
  members and per-constant contracts. Compiler-owned typed IR distinguishes the
  source-visible enum type from the constant's concrete storage/runtime type;
  LLVM emission then creates the corresponding immortal layout, descriptor,
  initialization calls, and dispatch entries mechanically. Reachability and
  format-1 source reconstruction retain a body subtype exactly when its enum
  constant or behavior is reachable. Source, class-directory, individual-class,
  archive, tree-shaking, safe-`free`, initialization-failure, allocation-count,
  typed-IR, LLVM, and native `-O0` through `-O3` coverage are required before
  Feature 74 can become implemented.
- **Consequences:** The requested per-constant strategy pattern fits Ironwood's
  closed-world object model and requires no runtime registry, class loading,
  collector, ordinary allocation, public native ABI, or source-level sealed
  hierarchy. It is nevertheless medium cross-cutting compiler work rather than
  a parser-only relaxation. D062 subsequently implements this design. Its prior
  roadmap rank was after multidimensional/exact arrays and before
  primitive-generic specialization; D058 itself selected no implementation
  target. Source String concatenation used by some enum formatting methods
  remains a separate expression feature implemented as Feature 76.

## D059 - String constants are finite; concatenation owns its result; runtime interning is excluded

- **Status:** Accepted; Feature 75 implemented, Feature 76 committed roadmap,
  and Feature 77 deliberately unsupported
- **Context:** D028 introduced compiler-pooled literal Strings and D031 amended
  their representation and behavior to Java-compatible UTF-16 contracts.
  Source String `+` remained deferred, and the Java-shaped comparison did not
  give literals, concatenation, or `String.intern()` independent status. These
  mechanisms have importantly different lifetimes. Java literals and String
  constant expressions have canonical identity, dynamic concatenation creates
  a new String, and `String.intern()` may retain an arbitrary runtime value in a
  private global pool, as specified by
  [JLS 3.10.5](https://docs.oracle.com/javase/specs/jls/se25/html/jls-3.html#jls-3.10.5),
  [JLS 15.18.1](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.18.1),
  and the [`String.intern()` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#intern()).
  Ironwood must preserve the useful source behavior without hiding allocations
  or introducing input-dependent immortal retention.
- **Decision:** Feature 75 records the existing double-quoted literal behavior.
  Equal decoded literal values from every retained source, `.ironclass`, and
  `.ironjar` input are canonicalized once while building the final closed world.
  Each value is an immutable, immortal UTF-16 `ironwood.lang.String` in
  compiler-emitted storage. Literal identity is stable across compilation-unit
  and artifact boundaries, literal storage does not affect
  `System.allocationCount()`, and source `free` cannot reclaim it. This is a
  compiler/link semantic operation, not a call to a runtime String pool.

  Feature 76 commits String `+` and String compound `+=`. A binary `+` is String
  concatenation when either operand has type `String`; otherwise its existing
  numeric rules remain unchanged. String conversion preserves Java-shaped
  behavior for `null`, every primitive type, and object `toString()`, without
  adding automatic boxing. Binary grouping remains left-associative and all
  operand expressions and conversions occur exactly once in left-to-right
  order. Compound `+=` evaluates its target once and stores the result without
  implicitly reclaiming the prior reference; ordinary source code remains
  responsible for any provable `free` before overwriting owned storage. A
  String-valued compile-time constant expression is folded into Feature 75's
  finite final-program pool.

  A nonconstant concatenation produces an ordinary immutable runtime String
  allocation that is not interned. Its result is the only compiler-created
  Ironwood allocation needed for one maximal uninterrupted concatenation chain:
  lowering evaluates and converts operands in order, checks the combined UTF-16
  length, and writes the exact tail object directly rather than allocating a
  hidden `StringBuilder` or backing array. The allocation is explicit in
  compiler-owned typed IR and allocation provenance. A locally stored,
  nonescaping result may therefore become a valid `free` target after the proof
  is extended to this allocation origin; an escaping or unbound result remains
  retained under the ordinary explicit-reclamation rules. Float/double text
  conversion, checked size overflow/failure, constant evaluation, source/class/
  archive reconstruction, tree shaking, allocation counts, safe-`free`, and
  native `-O0` through `-O3` behavior are part of Feature 76's completion gate.

  Feature 77 deliberately excludes Java's `String.intern()` API. An arbitrary
  runtime String never enters the compiler's literal pool. A Java-compatible
  process-global interner would retain ordinary allocations through hidden
  aliases for an input-dependent lifetime, make subsequent `free` unsafe, and
  require unbounded mutable runtime state. Java delegates those lifetimes to its
  garbage collector; Ironwood deliberately has no collector to remove
  unreachable pool entries. If runtime canonicalization is useful, a future
  standard-library collection should instead be an explicit
  application-owned interner with caller-selected capacity, lifetime, and
  reclamation behavior; it is not `String.intern()` compatibility.
- **Consequences:** Literal behavior does not wait for broader standard-library
  work: it is already a language/compiler guarantee, while ordinary String
  methods remain standard-library surface. Feature 76 is medium, bounded work
  because parsing, literal/runtime String representation, most conversions,
  and one-allocation String creation already exist, but semantic lowering,
  ownership tracking, and complete conversions/tests do not. It is ranked after
  multidimensional/exact arrays and before Feature 74. D059 selects no next
  implementation target and changes no compiler behavior or verification count.
  D059 amends D031's classification of source concatenation from merely deferred
  to committed roadmap work and supersedes D058's same wording without changing
  the enum-body decision.

## D060 - Multidimensional arrays are explicit arrays of arrays with exact descriptor tests

- **Status:** Accepted; implements Feature 55
- **Context:** Feature 55 committed multidimensional arrays and exact invariant
  array casts/tests, but deliberately left the allocation spelling open because
  Java's rectangular `new T[outer][inner]` form creates child arrays that have no
  individual source-level owner. Ironwood must preserve useful Java-shaped
  arrays-of-arrays, descriptor-based runtime checks, and compiler-checked
  explicit reclamation without introducing hidden ordinary allocations or
  Java's covariant array conversions.
- **Decision:** Array types may contain array element types recursively, so
  `T[][]` means an invariant array whose elements have exact type `T[]`.
  Declaration, field, parameter, return, generic-argument, cast, `instanceof`,
  indexing, and assignment grammar all use that recursive type. Each array
  creation expression allocates exactly one container. `new T[outer][]` and
  further trailing empty dimensions are accepted because they allocate only
  the named outer array with zero-initialized null child slots. Any creation
  expression that sizes a second or later dimension, including
  `new T[outer][inner]`, is rejected with a diagnostic directing the programmer
  to allocate each child with a separate source `new` expression.

  Array assignment remains exact and invariant at every dimension. In
  particular, neither `Child[]` to `Base[]` nor `Child[][]` to `Base[][]`
  converts. Every leaf primitive, nominal reference erasure, and dimension is
  part of the compiler-emitted array descriptor identity. Generic arguments
  retain the existing erasure model: a recursively reifiable target such as
  `Box<?>[][]` can be tested by its erased array descriptor, while
  `Box<String>[]`, bounded-wildcard arrays, and type-variable arrays are not
  reifiable runtime targets. This does not add a runtime generic-argument
  registry.

  `value instanceof T[]...` is null-safe and compares the value's leading
  descriptor pointer with the exact target array descriptor. A checked cast
  from `Object` to a reifiable array type uses the same exact test, preserves
  the original address and compiler allocation identity on success, and throws
  `ClassCastException` through the existing language exception CFG on mismatch;
  null succeeds. Casts between distinct invariant array types and casts between
  an array and an unrelated nominal type are statically impossible. Exact array
  tests are represented by dedicated compiler-owned typed IR and lower to a
  generated LLVM helper; they add no C runtime ABI, registry, reflection, class
  loading, or covariance check.

  An outer array allocation and every child allocation are independent ordinary
  allocation identities and each successful `free` releases only that one
  container. A tracked child stored directly into a constant slot of a known
  local array is recorded as a visible alias rather than immediately becoming
  globally escaped. The child cannot be freed while any such slot retains it.
  A direct overwrite of the same proven slot with null or another value removes
  that one alias, and freeing the unique outer container removes its remaining
  slot aliases. Unknown containers or indexes, bulk copies, calls that can
  observe a container's elements, escaped containers, and loaded elements whose
  slot provenance is not proved retain conservative escape/unknown behavior.
- **Consequences:** Tables, matrices, jagged structures, and nested generic
  array signatures use familiar recursive `[]` syntax while every ordinary
  allocation remains visible and independently reclaimable in source. Exact
  casts after widening to `Object` need only immutable closed-world descriptor
  identity and preserve safe-`free` allocation provenance. Source/class/archive
  reconstruction continues to use format-1 source payloads, while reachability
  keeps only the recursively needed array descriptors. Feature 55 completion
  requires parser, semantic, diagnostics, safe-`free`, typed-IR, LLVM, native
  `-O0` through `-O3`, tree-shaking, artifact-round-trip, example, package, and
  documentation coverage. D060 selects no feature after 55.

## D061 - String concatenation is a typed one-allocation operation

- **Status:** Accepted; implements Feature 76
- **Context:** D059 committed Java-shaped String `+`/`+=` provided dynamic
  concatenation did not create an unowned builder graph, constant expressions
  joined the finite literal pool, and ordinary dynamic results retained visible
  ownership. The remaining work had to preserve Java's left association,
  conversion spelling, and evaluation order across source and reconstructed
  artifacts while keeping allocation behavior explicit in compiler-owned IR.
- **Decision:** Binary `+` produces a String whenever either operand has String
  type. Maximal String-valued `+` trees are flattened only across String-typed
  children, so parenthesized numeric subexpressions retain numeric promotion.
  Each operand is evaluated once from left to right. Null becomes `"null"`;
  booleans, characters, signed integral values, floats, and doubles use their
  Java-shaped text; and non-null references dispatch `toString()` while null
  references produce `"null"`. No automatic boxing is introduced. String
  compound `+=` resolves and reads its variable, field, or array target once,
  concatenates it with the right operand, and writes the new reference without
  implicitly freeing the old value.

  The constant evaluator folds String-valued constant expressions, including
  static-final String constants and primitive subexpressions, into D059's
  final-program literal pool. Dynamic concatenation emits one
  `IrStringConcatInstruction` containing typed ordered parts. LLVM materializes
  a native descriptor array, and `ironwood_string_concat` performs two passes:
  first it converts and totals the UTF-16 length, then it allocates exactly one
  ordinary immutable String and writes its tail. The descriptor ABI carries raw
  float/double bits so the runtime can produce Java-matching decimal spelling,
  including signed zero, infinity, and NaN. Length overflow and allocation
  failure are detected before an invalid result is exposed and retain the
  runtime's current fail-fast allocation policy. No hidden ordinary
  `StringBuilder`, backing array, boxing object, or runtime interning entry is
  created.

  The result instruction is an allocation origin in the existing ownership
  proof. A named, nonescaping dynamic result can therefore be passed to `free`;
  a result that escapes through an observable operation remains retained. The
  same source semantics survive class-directory, individual `.ironclass`, and
  `.ironjar` reconstruction, and use of concatenation alone does not keep
  `StringBuilder` reachable.
- **Consequences:** Feature 76 now feels like Java at its source boundary while
  exposing Ironwood's ordinary dynamic result lifetime. The full local macOS
  ARM64 suite contains 288 tests covering typed IR/LLVM evidence, constant
  identity, all primitive/reference conversions, side-effect order, exact
  float/double edge spelling, target evaluation, allocation counts, safe
  `free`, tree shaking, artifact round trips, and native `-O0` through `-O3`.
  The exact macOS ARM64 host package passes relocated smoke tests, including
  the String-concatenation example at `-O3`. No new self-contained IDK,
  cross-platform, or release-asset result is claimed. At the D061 checkpoint,
  the human selected Feature 74 next and Feature 51 after its completion and
  synchronization; D062 subsequently completes Feature 74.

## D062 - Enum constant bodies use immortal concrete subtype storage

- **Status:** Accepted; implements Feature 74
- **Context:** D058 fixed the source and ownership contract for enum
  constant-specific class bodies but left it unimplemented. The implementation
  had to add per-constant behavior without weakening enum finality, exposing a
  hidden subtype in source, allocating an ordinary object, changing enum
  lookup/switch identity, or bypassing the existing typed-IR and closed-world
  object pipelines.
- **Decision:** The enum AST retains each optional constant body. Source-ordered
  lexical discovery assigns every body a deterministic compiler-owned identity,
  and the existing lexical-type collector turns it into a final direct subtype
  of the declaring enum. The body supports ordinary anonymous-class fields,
  instance initializers, methods, and member types. Constructors, abstract
  methods, and static initializer blocks remain illegal. Constant arguments are
  analyzed in the enum's static context; the hidden body itself has no enclosing
  instance or captured source locals.

  A bodied constant's source field and all source expressions retain the enum
  type. Its hidden subtype receives ordinary hierarchy, layout, membership,
  dispatch, devirtualization, visibility, same-nest private access, field
  initialization, and member-type analysis. The enum may declare abstract
  instance methods when every constant is bodied. Interface and abstract-method
  obligations are checked independently on every concrete constant type, while
  an unbodied constant requires a concrete enum or inherited-default
  implementation. Body-only members remain unavailable through the enum-typed
  field.

  `IrEnumConstant` records both the declared enum type and concrete storage
  type. A bodied constant's static image storage uses the hidden subtype layout
  and descriptor. Enum initialization selects the generated forwarding
  constructor for that subtype: it invokes the chosen private enum constructor
  over the same immortal object, then runs the subtype field and block
  initialization before publishing the enum-typed constant field. No
  `IrAllocateInstruction` or `ironwood_allocate` call is introduced. Name,
  ordinal, traversal, lookup, identity, enum switch, construction-failure
  caching, reentrant partial state, source traces, and allocation counts keep
  the D054, D055, and D057 contracts.

  Reachability scans constant arguments and bodies, retains the hidden subtree
  when its constant is reachable, and prunes unused enum/body subtrees.
  Format-1 source payloads reconstruct the same lexical types from source,
  class directories, individual `.ironclass` files, and `.ironjar` archives.
  Safe-`free` continues to reject immortal constant identities.
- **Consequences:** Per-constant strategy implementations now use the familiar
  Java-shaped source form with no new runtime registry, ABI, loader, collector,
  source-level sealed hierarchy, or ordinary allocation. Parser and diagnostic
  tests cover retained bodies and illegal declarations; semantic tests cover
  per-constant obligations, abstract enum methods, final overrides, visibility,
  and reclamation; typed-IR/LLVM tests cover declared-versus-storage types,
  layouts, descriptors, construction, dispatch, and absence of allocation.
  Native tests cover body state, initialization order/failure, private and
  protected enum access, member types, interface/virtual dispatch, lookup,
  ordinal switch, and allocation neutrality at `-O0` through `-O3`.
  Source/class/archive/tree-shaking round trips cover distribution behavior.
  The full local macOS ARM64 suite contains 290 tests, and the exact host package
  passes relocated smoke tests including the updated enum constant-body example
  at `-O3`. No new self-contained IDK, cross-platform, or release-asset result
  is claimed. The human selects Feature 51 primitive-generic specialization as
  the next implementation target.

## D063 - Primitive generic arguments use closed-world native shape specialization

- **Status:** Accepted; implements Feature 51
- **Context:** Ironwood's reference-generic model deliberately shares one
  pointer-shaped native declaration across reference arguments. Java's answer
  for a primitive argument is automatic wrapper conversion, but an invisible
  wrapper allocation has no reliable source-level owner in Ironwood's
  explicit-reclamation model. Feature 51 therefore requires primitive values
  to remain values throughout generic storage and calls without adding a
  collector, runtime generic registry, or source-visible family of duplicated
  container declarations.
- **Decision:** Any of Ironwood's eight primitive types may be supplied for a
  type parameter that declares no explicit upper bound. An explicitly bounded
  parameter, including one spelling `extends Object` or depending on another
  parameter, remains reference-only. Primitive arguments are proper exact type
  arguments for invariant generic classes, interfaces, methods, constructors,
  explicit invocation arguments, and inference. They do not participate in
  wildcard containment, are never assignable to `Object`, and never trigger
  boxing or unboxing.

  After ordinary source resolution, overload selection, ownership analysis,
  and typed-IR construction, the final closed world materializes native
  specialization shapes. Each primitive parameter position and primitive kind
  is part of a deterministic shape; every reference-shaped position continues
  to use the existing shared pointer representation. Thus `Box<int>` stores an
  `i32` directly, `Box<long>` stores an `i64`, and `Pair<int, String>` may share
  its native layout with `Pair<int, Object>` while source typing remains exact.
  Compiler-owned specialized class records retain applicable non-generic and
  exact specialized membership and receive their own layouts, descriptors,
  dispatch targets, and specialized method/constructor bodies. Generic
  callable instantiations receive deterministic specialized linkage and, when
  polymorphic, closed-world specialization dispatch slots. Static fields and
  one-time initialization remain owned by the unspecialized declaration and are
  not duplicated.

  Specialization substitutes types throughout compiler-owned SSA/CFG IR before
  LLVM lowering. Fields, parameters, returns, phis, equality, arrays, direct
  calls, virtual/interface calls, exceptional edges, and explicit
  reclamation therefore keep their native types without an erased value cell
  or runtime tag. A generic body may be specialized only when every operation
  remains valid for the primitive substitution. Value transport, primitive
  equality, and arrays specialize naturally; a path that requires `null`, a
  reference conversion, object-member dispatch, throwing the value, or another
  reference-only operation receives a source diagnostic instead of boxing.

  No parameterized runtime registry is added. A primitive specialization does
  not carry the raw generic declaration's membership bit, including when a
  non-generic class fixes a primitive argument for a generic parent. It remains
  an `Object`, retains ordinary non-generic parent memberships, and carries the
  corresponding specialized parent identity for source-provable exact casts. A
  reifiable wildcard test cannot turn it into a pointer-shaped generic view.
  Invariant primitive instantiations therefore do not widen or cast to wildcard
  instantiations, closing the mixed primitive/reference ABI escape hatch.
  Array descriptors contain the specialized leaf shape when applicable and
  preserve recursively exact invariant tests.
- **Consequences:** Generic boxes, pairs, value buffers, and suitably
  value-neutral algorithms can operate directly on primitive values with zero
  wrapper allocations at every optimization level. Reference instantiations
  keep their established shared ABI and do not multiply merely because their
  exact source arguments differ. The compiler may emit several native layouts
  or bodies for one source declaration, but closed-world reachability removes
  unused shapes and methods. Format-1 source payloads and archives reconstruct
  specialization deterministically at final link; they do not become a stable
  generic binary ABI commitment. Safe-`free` continues to track only reference
  allocation identities, primitive fields and array elements create no aliases,
  and specialization cannot weaken an existing rejection. Positive and
  rejection semantics, inference and overload tests, typed-IR/LLVM evidence,
  direct and polymorphic dispatch, primitive arrays, source-provable exact
  casts, allocation-count and safe-`free` checks, source/class/archive
  reconstruction, tree shaking, and native `-O0` through `-O3` cover the
  implementation. The full local macOS ARM64 suite contains 295 tests,
  licensing checks pass, and the exact host package passes relocated smoke
  tests including the primitive-generics example at `-O3`. No new
  self-contained IDK, cross-platform, or release-asset result is claimed, and
  no subsequent numbered implementation target is selected.

## D064 - `@Override` is a mandatory built-in method directive

- **Status:** Accepted and implemented; implements Feature 60
- **Context:** Java's optional `@Override` annotation catches misspelled or
  stale override intent when it is present, but it does not require a programmer
  to state that intent. Ironwood already validates override structure, finality,
  visibility, return compatibility, static/instance form, and interface
  obligations. Requiring the marker in both directions makes an unintentional
  override and an intended method that no longer overrides equally visible at
  compile time. The source should retain Java's familiar spelling without
  reversing D045's exclusion of general annotations.
- **Decision:** The lexer emits `@` as its own token. The modifier parser
  recognizes the exact, case-sensitive identifier `Override` contextually after
  it, producing one built-in `@Override` directive rather than an annotation
  AST. `Override` remains usable as an ordinary identifier outside that
  context. Parentheses, qualified names, arguments, other names after `@`,
  duplicates, and placement on types, fields, constructors, or initializer
  blocks are rejected.

  Every source-declared class or interface method that overrides an inherited
  instance method, implements an inherited interface method, or redeclares an
  inherited interface method must carry `@Override`. This includes abstract and
  default methods, anonymous-class methods, and enum constant-body methods. A
  marked method must resolve a valid inherited instance target. Public
  `Object`-equivalent methods redeclared by an interface count as override
  targets consistently with Java. A class that declares no new method merely
  inherits its implementation and has no directive to supply. Compiler-created
  methods are exempt because there is no source declaration to mark.

  The directive participates in the method-modifier parser, so it may appear on
  the method's line, on a preceding line, or interleaved with ordinary
  modifiers; whitespace and comments are insignificant. Existing structural
  diagnostics remain primary for an invalid final override, incompatible
  return, reduced visibility, or static/instance mismatch rather than adding a
  redundant missing-directive diagnostic.
- **Consequences:** Ironwood source looks like ordinary Java at the marker site
  while enforcing a stricter contract than Java: omission on a real override
  and presence on a non-override are both compiler errors. General annotations
  remain deliberately unsupported. All standard-library sources, integration
  fixtures, and affected examples now state their override intent explicitly.
  Lexer/parser, semantic, diagnostic, source/class/archive reconstruction, and
  native `-O0` through `-O3` tests cover the implementation. The full local
  macOS ARM64 suite contains 299 tests, licensing checks pass, and the exact
  host package passes relocated smoke tests including the override-directive
  example at `-O3`. No new self-contained IDK, cross-platform, or release-asset
  result is claimed, no general annotation work is authorized, and no
  subsequent numbered implementation target is selected.

## D065 - Text blocks add an escape-free Ironwood form

- **Status:** Accepted and implemented; implements Feature 78
- **Context:** Java text blocks make structured multiline content readable and
  define useful, platform-independent newline and indentation behavior. Their
  cooked escape processing is awkward for regular expressions and other text
  where backslashes are data. Ironwood already has a finite compile-time
  literal pool, UTF-16 String representation, constant folding, and explicit
  ownership rules; multiline syntax must reuse those mechanisms rather than
  introduce a runtime builder, interpolation engine, or hidden allocation.
- **Decision:** Ironwood accepts cooked `"""` and raw `r"""` text blocks. The
  lowercase raw prefix is adjacent and is part of one lexical token. Both
  opening delimiters require a following line terminator, optionally preceded
  by spaces, tabs, or form feeds. That terminator is structural. Content CRLF
  and CR terminators normalize to LF, after which Java-compatible incidental
  indentation and trailing whitespace removal is applied. The indentation of
  nonblank content and a separate closing-delimiter line determines the common
  margin. A terminator before a delimiter on its own line remains content, a
  delimiter beside the final content character adds none, and an additional
  opening blank line is retained. This compatibility behavior is independently
  implemented against Java SE 21 JLS 3.10.6.

  The cooked form then interprets the existing Ironwood String escapes `\b`,
  `\f`, `\n`, `\r`, `\t`, `\"`, and `\\`. Delaying escape processing keeps
  escaped whitespace out of margin calculation and permits a content run of
  three quotes by escaping at least its first quote. The raw form interprets no
  escapes: every backslash is content. Structural normalization still applies.
  The first `"""` closes a raw block and cannot be escaped; variable-length
  delimiters and single-line `r"..."` literals are outside Feature 78.

  Neither form performs interpolation, so `${...}` is text. Comment markers
  inside a block are also text. Missing opening terminators, unsupported cooked
  escapes, and missing cooked or raw closing delimiters receive explicit lexer
  diagnostics. After transformation, both forms emit the same decoded `STRING`
  token as an ordinary literal. The existing parser, semantic analysis,
  constant evaluator, typed IR, final-program pool, class/archive source
  reconstruction, LLVM emitter, and runtime therefore need no new literal
  representation or operation.
- **Consequences:** Cooked blocks retain Java's familiar layout while raw blocks
  make regexes and backslash-heavy text direct and readable. Decoded-equal
  ordinary, cooked, and raw literals share one immutable immortal UTF-16 object,
  do not change `System.allocationCount()`, and cannot be reclaimed with
  `free`. Tests cover opening and closing placement, leading and trailing
  newlines, mixed source terminators, indentation, trailing whitespace, cooked
  escapes and embedded delimiters, raw backslashes, comment and interpolation
  markers as content, pooling, explicit diagnostics, source/class/archive
  reconstruction, and native `-O0` through `-O3`. The full local macOS ARM64
  suite contains 303 tests, and the exact host package passes relocated smoke
  tests including the text-block example at `-O3`. No new self-contained IDK,
  cross-platform, or release-asset result is claimed, and no subsequent
  numbered implementation target is selected.

## D066 - Audit the complete Java SE 26 language surface

- **Status:** Accepted; documentation-only audit defining Features 79–100
- **Context:** The numbered comparison had detailed coverage of Ironwood's
  object model and deliberate runtime differences, but its stated Java SE 21
  baseline and object-centered boundary could hide omissions elsewhere in the
  language. Feature 78 exposed that risk when multiline literals were added
  only after the original comparison was complete. Java SE 25 also permanently
  added module imports, compact source files and expanded `main` forms, and
  flexible constructor bodies. The final Java SE 26 JLS is therefore the
  appropriate current baseline. Standard-library breadth remains a separate
  project and is not part of this language audit. Javadoc is included only
  because source documentation support was explicitly requested for
  classification.
- **Decision:** Audit JLS Chapters 3–18 and the separate Java SE 26
  documentation-comment specification against the lexer, parser, semantic
  model, typed IR, native entry contract, compiled-artifact model, tests, and
  existing design decisions. Add numbered entries for every material language
  family found missing from `IRONWOOD_VS_JAVA.md`:

  - Feature 79 records Javadoc as unsupported for now, while Feature 80 records
    implemented ordinary line and block comments.
  - Features 81–83 record the deliberate lexical differences for Unicode
    translation/identifiers, additional escapes, and additional numeric literal
    spellings. Feature 84 records the already implemented Java-width primitive,
    conversion, operator, and evaluation-order surface.
  - Features 85–87 separate implemented packages and ordinary imports from
    unsupported static imports, module declarations, and module imports.
  - Features 88–95 record unsupported declarator conveniences, array
    initializers, remaining statement forms and transfers, modern non-pattern
    `switch`, multi-catch/precise rethrow, class literals, flexible constructor
    bodies, and local enum/interface declarations.
  - Features 96–97 record the deliberate native entry contract and rejected
    Java special-purpose modifiers.
  - Feature 98 classifies compile-time `.ironclass`/`.ironjar` reuse as the
    implemented Ironwood alternative to Java binary compatibility.
  - Feature 99 records that intersection bounds are implemented but Java
    intersection cast expressions are not. Feature 100 separates catchable
    implicit Java runtime failures from Ironwood's remaining native fail-fast
    safety boundaries.

  At the time of this audit, Java SE 26's preview primitive-pattern family was
  covered by the Feature 66 umbrella and did not become a commitment. D072
  later splits that preview into Feature 108 without changing its ❌ status.
  Withdrawn string templates are not a Java SE 26 feature and receive no row.
  The audit creates no ⏳ status, priority, or selected implementation target.
  It identifies an unranked set of potentially useful future language designs
  while leaving each ❌ until a separate decision changes its status.
- **Consequences:** The comparison now means “audited language inventory” rather
  than “object-model sample,” while still excluding ordinary standard-library
  APIs. Javadoc's `/** ... */` and `///` spellings continue to lex only as
  ordinary comments; there is no declaration association, documentation AST,
  tag/link processing, DocLint, doclet model, or generated output. No compiler,
  runtime, standard-library, build, packaging, example, or test behavior changes
  under D066, so the verified D065 baseline remains 303 compiler tests. The
  documentation-only gate is focused consistency checking, `git diff --check`,
  and the repository license audit.

## D067 - Confirm audit exclusions and isolate `volatile`

- **Status:** Accepted; documentation-only classification adding Feature 101
- **Context:** D066 exposed eighteen previously unnumbered Java-language
  exclusions. Before promoting any of them to committed roadmap work, the
  exclusions that Ironwood should retain need to be separated from the smaller
  set still under consideration. Feature 97 also grouped `volatile` with four
  modifiers whose rationales are unrelated, obscuring its distinct memory-model
  importance.
- **Decision:** Confirm that Features 79, 81, 82, 87, 88, 91, and 93–97 remain
  ❌. Remove `volatile` from Feature 97, whose scope remains `native`,
  `strictfp`, `synchronized`, and `transient`. Add Feature 101 for Java
  `volatile` field visibility, ordering, atomicity, and happens-before
  semantics. Feature 101 remains ❌ while its promotion is considered; an
  implementation cannot treat LLVM volatile operations as a substitute for a
  defined Java-shaped memory-ordering contract.

  Features 83, 86, 89, 90, 92, 99, 100, and 101 are the remaining audit items
  open for a promotion decision. They keep their current ❌ status until an
  explicit decision commits exact scope and semantics. D067 promotes nothing
  to ⏳, assigns no rank, and selects no implementation target.
- **Consequences:** `IRONWOOD_VS_JAVA.md` now has 101 stable numbered entries:
  57 ✅, 10 💡, 34 ❌, and no ⏳. This classification changes no compiler,
  runtime, standard-library, test, example, packaging, or build behavior, so
  the D065 303-test compiler baseline remains unchanged. Focused documentation
  consistency and licensing checks are sufficient.

## D068 - Keep `assert` outside the remaining statement decision

- **Status:** Accepted; documentation-only classification adding Feature 102
- **Context:** Feature 90 grouped several still-open control-flow constructs
  with Java's `assert` statement. Ironwood should not support `assert`, so
  leaving it in that open bundle would make a later Feature 90 promotion
  ambiguous.
- **Decision:** Remove `assert` from Feature 90 and add Feature 102 as a
  confirmed ❌. Ironwood will use explicit conditions and explicit thrown
  failures rather than a statement whose condition and detail expression can be
  externally enabled or disabled at runtime. Feature 90 remains ❌ and open for a
  later decision about `do`/`while`, enhanced `for`, empty and labeled
  statements, labeled transfers, and `break`/`continue` through `finally`.
  Features 83, 86, 89, 90, 92, 99, 100, and 101 remain the complete open audit
  set; D068 promotes none of them and selects no implementation target.
- **Consequences:** `IRONWOOD_VS_JAVA.md` now has 102 stable numbered entries:
  57 ✅, 10 💡, 35 ❌, and no ⏳. This classification changes no executable,
  compiler, runtime, standard-library, test, example, packaging, or build
  behavior. The D065 303-test compiler baseline remains unchanged, and focused
  documentation consistency and licensing checks are sufficient.

## D069 - Split and commit selected Java audit features

- **Status:** Accepted; documentation-only roadmap classification adding
  Features 103–105
- **Context:** The remaining D068 audit set grouped three decisions whose parts
  have materially different value or implementation constraints. Feature 83
  combined readable binary literals, surprising leading-zero octal semantics,
  and exact hexadecimal floating-point notation. Feature 100 combined ordinary
  implicit safety failures with allocation exhaustion, even though exhaustion
  must be reported when normal allocation has already failed. Feature 92 and
  Feature 99 were already independently scoped and ready for disposition.
- **Decision:** Commit Java-shaped binary integer literals as ⏳ Feature 83,
  including `0b`/`0B`, underscores, suffixes, and ordinary integer-width
  selection. Add confirmed-❌ Feature 103 for Java-style leading-zero octal
  literals. Add Feature 104 for hexadecimal floating-point literals and retain
  ❌ while its independent promotion decision remains open.

  Commit Feature 92 as ⏳ with both Java-shaped union catch parameters and
  precise thrown-type analysis for effectively-final rethrows. Confirm
  intersection cast expressions as ❌ Feature 99 for now; this does not affect
  the implemented intersection bounds or generic inference model.

  Narrow Feature 100 to null receiver/use, null or out-of-bounds array access,
  negative array length, and `throw null`, and commit those ordinary implicit
  failures as catchable ⏳ behavior. Add Feature 105 for allocator exhaustion
  and catchable `OutOfMemoryError`; retain ❌ until its emergency-storage,
  source-trace, unwinding, repeated-failure, and recovery contract is decided.
  D069 ranks none of the three pending features and selects no next
  implementation target.
- **Consequences:** `IRONWOOD_VS_JAVA.md` now has 105 stable numbered entries:
  57 ✅, 10 💡, 35 ❌, and 3 ⏳. The remaining open promotion decisions are
  Features 86, 89, 90, 101, 104, and 105. This classification changes no
  compiler, runtime, standard-library, test, example, packaging, or build
  behavior. The D065 303-test compiler baseline remains unchanged, and focused
  documentation consistency and licensing checks are sufficient.

## D070 - Catch allocator exhaustion with a bounded immortal emergency error

- **Status:** Accepted; documentation-only roadmap commitment promoting
  Feature 105 to ⏳
- **Context:** The native object and array allocators currently abort when
  `calloc` returns no storage. Merely preallocating a language-level
  `OutOfMemoryError` is insufficient: ordinary throwing also allocates a native
  unwind wrapper, trace metadata, and secondary-failure associations. Exact
  Java object-allocation timing would additionally attempt allocation before
  constructor arguments, which can strand unowned storage when an argument
  throws in a language without garbage collection. The existing
  `ironwood.lang.OutOfMemoryError` type and explicit source throws already use
  the ordinary exception system; only implicit native exhaustion is at issue.
- **Decision:** Commit automatic catchable `OutOfMemoryError` for failed source-
  evaluated object and array allocations and for compiler/runtime operations
  that allocate an ordinary source-visible String result. Treat an allocator
  null result and checked allocation-size overflow as exhaustion. Operating-
  system overcommit termination, fatal memory-access signals, runtime-private
  bookkeeping failure, and pre-entry runtime setup remain outside catchable
  source behavior.

  Emit one immortal compiler-owned `OutOfMemoryError` with a null message. It
  has no source owner, is not freeable, does not increment allocation counts,
  and may have the same identity on later failures. Reserve runtime-private
  native storage sufficient to deliver one active implicit error and one
  required primary/secondary association without ordinary allocation. A second
  allocation failure while the implicit error is unwinding terminates with a
  deterministic diagnostic rather than recursively attempting another unwind.
  Once it has landed in a source catch, a later automatic occurrence may reuse
  the singleton and replace its bounded trace and association state; retained
  aliases denote that singleton rather than immutable per-occurrence objects.

  Capture the exact failing Ironwood allocation-site frame without allocation.
  Additional caller frames may use bounded emergency storage and must report
  explicit truncation if capacity is exhausted. A source catch may free values
  it already owns and retry, but recovery and retry success are not guaranteed.
  Nonessential trace or diagnostic metadata continues to degrade rather than
  recursively throwing.

  Retain Ironwood's ownership-safe object-creation order: enclosing receiver and
  source constructor arguments, allocation, then construction. Array dimensions
  and dynamic String operands likewise precede result allocation. Failed
  allocation creates no ordinary object, changes no allocation count, and
  creates nothing source must free. This observable ordering, immortal identity,
  and bounded emergency behavior deliberately differ from exact Java VM
  semantics. Feature 105 remains ⏳ until implementation and is expected to
  become 💡 when verified, not ✅.

  Feature 105 receives no priority and is not selected as the next
  implementation target.
- **Consequences:** `IRONWOOD_VS_JAVA.md` retains 105 stable numbered entries:
  57 ✅, 10 💡, 34 ❌, and 4 ⏳. The remaining open promotion decisions are
  Features 86, 89, 90, 101, and 104. This decision changes no compiler,
  runtime, standard-library, test, example, packaging, or build behavior. The
  D065 303-test compiler baseline remains unchanged, and focused documentation
  consistency and licensing checks are sufficient.

## D071 - Commit the remaining imports, arrays, statements, and volatile candidates

- **Status:** Accepted; documentation-only roadmap commitment promoting
  Features 86, 89, 90, and 101 to ⏳
- **Context:** D070 left static imports, array initializer syntax, remaining
  statement and transfer forms, Java-shaped `volatile` fields, and hexadecimal
  floating-point literals open for individual promotion decisions. Static
  imports are a bounded compile-time name-resolution facility. Array
  initializers and enhanced statements remove significant Java source gaps but
  must preserve Ironwood's explicit allocation and cleanup rules. `volatile`
  requires a genuine memory-ordering contract rather than LLVM's unrelated
  volatile-access marker.
- **Decision:** Commit Java-shaped single-member and on-demand static imports
  under Feature 86, including accessible static fields, methods, and member
  types plus the corresponding lookup, ambiguity, shadowing, and accessibility
  rules.

  Commit declaration and array-creation initializer syntax under Feature 89,
  including contextual element conversions, inferred lengths, left-to-right
  evaluation, empty forms, and recursive nested initializers. The eventual
  design must keep every implicit nested child allocation visible to ownership
  and safe-`free` analysis rather than permitting unowned hidden arrays.

  Commit Feature 90's `do`/`while`, enhanced `for` over arrays and `Iterable`,
  empty and labeled statements, labeled `break`/`continue`, and transfers that
  cross `finally` while preserving cleanup. Enhanced `Iterable` lowering must
  define ownership of the iterator obtained by the language construct.
  Feature 102's `assert` exclusion is unchanged.

  Commit Feature 101's field modifier and Java-shaped volatile read/write
  atomicity, visibility, ordering, synchronization order, and happens-before
  guarantees. Feature 70 continues to exclude source-level threads, monitors,
  and `synchronized`, so Feature 101's design must identify its operational
  concurrent/native observer boundary without silently changing Feature 70.
  LLVM volatile loads and stores alone are explicitly insufficient.

  All four features are unranked, and no next implementation target is
  selected. Feature 104 remains the sole open promotion decision from D070.
- **Consequences:** `IRONWOOD_VS_JAVA.md` retains 105 stable numbered entries:
  57 ✅, 10 💡, 30 ❌, and 8 ⏳. This classification changes no compiler,
  runtime, standard-library, test, example, packaging, or build behavior. The
  D065 303-test compiler baseline remains unchanged, and focused documentation
  consistency and licensing checks are sufficient.

## D072 - Separate `instanceof` patterns from modern and pattern switch

- **Status:** Accepted; documentation-only classification adding Features
  106–108
- **Context:** Feature 66 combined stable `instanceof` type-pattern variables,
  stable reference and record patterns in `switch`, unnamed patterns, and Java
  SE 26's preview primitive-pattern family. Feature 91 separately described
  modern non-pattern switch rules and expressions. That division made it
  unclear whether promoting either number would commit `instanceof`, switch
  structure, pattern labels, record deconstruction, or preview behavior.
- **Decision:** Narrow Feature 66 to named reference type patterns used by the
  `instanceof` operator, including their flow-sensitive variable scope. Retain
  Feature 91 exclusively for modern non-pattern switch structure: arrow rules,
  expressions, `yield`, comma-separated constant labels, String and null
  selection, and non-pattern exhaustiveness.

  Add Feature 106 for named reference type patterns in switch labels, guards,
  dominance, applicability, flow bindings, and pattern-specific exhaustiveness.
  Add Feature 107 for record deconstruction and unnamed patterns, which are
  shared pattern vocabulary rather than an `instanceof`-only or switch-only
  facility. Add Feature 108 for Java SE 26's preview primitive types in
  patterns, `instanceof`, and `switch`, including its exactness and numeric edge
  rules. Keeping the preview isolated prevents a stable-feature promotion from
  adopting it implicitly.

  Features 66, 91, and 106–108 all remain ❌ pending individual decisions. D072
  changes no pending rank and selects no implementation target. Feature 104
  remains the sole open promotion decision inherited from the D070 candidate
  set; the newly clarified pattern entries may be reconsidered independently.
- **Consequences:** `IRONWOOD_VS_JAVA.md` now has 108 stable numbered entries:
  57 ✅, 10 💡, 33 ❌, and 8 ⏳. This classification changes no compiler,
  runtime, standard-library, test, example, packaging, or build behavior. The
  D065 303-test compiler baseline remains unchanged, and focused documentation
  consistency and licensing checks are sufficient.

## D073 - Commit selected pattern/switch work and rank all pending features

- **Status:** Accepted; documentation-only roadmap decision promoting Features
  66 and 91, excluding Feature 101, and ranking all pending features
- **Context:** D072 separated five previously entangled pattern and switch
  decisions. Named reference type patterns for `instanceof` are a bounded,
  allocation-free improvement over an existing type test plus checked cast.
  Modern non-pattern switch has independent value through non-fallthrough arrow
  rules and expression results, but requires broader expression typing,
  exhaustiveness, control-flow, String/null selection, and ownership-aware
  branch merging. Reference pattern switch has lower value without records or
  sealed source hierarchies and duplicates many open-hierarchy uses already
  served by virtual/interface dispatch. Record patterns depend on the excluded
  Feature 62 record model, and Java SE 26 primitive patterns remain preview.
  Java `volatile` is an inter-thread synchronization contract; without threads,
  monitors, or shared-memory native interoperability, it has no useful
  source-observable semantics in Ironwood.
- **Decision:** Promote Feature 66 to priority 2 ⏳ work. It commits
  Java-shaped named reference type patterns for `instanceof`, evaluated-once
  non-null matching, pattern bindings, and flow-sensitive scope. A binding is
  an alias of the tested reference and must add no allocation, ownership
  transfer, or safe-`free` escape.

  Promote Feature 91 to priority 9 ⏳ work. It commits modern non-pattern
  switch rules and expressions: arrow rules, `yield`, comma-separated constant
  labels, String and null selection, expression result typing, and non-pattern
  exhaustiveness. It does not commit any pattern label or alter Feature 65's
  classic explicit-fallthrough statement.

  Return Feature 101 to ❌, superseding D071's pending commitment. Do not treat
  C or LLVM volatile memory operations as a substitute for Java synchronization;
  reconsider the source modifier only with a defined thread model or explicit
  shared-memory native interoperability contract.

  Confirm Features 106–108 as ❌: reference type patterns in switch,
  record/unnamed patterns, and Java SE 26's preview primitive-pattern family.
  Feature 106 may be reconsidered if records, sealed source hierarchies, or a
  compelling type-dispatch use case changes its value. Feature 107 may be split
  if unnamed patterns become independently useful. Feature 108 may be
  reconsidered after Java finalizes it.

  Rank all nine pending features by usefulness and dependency order, not
  estimated implementation difficulty: Feature 100 first, then 66, 90, 92, 89,
  83, 86, 105, and 91. Catchable common runtime failures are the most
  foundational. The ranking guides the roadmap but selects no next
  implementation target.
- **Consequences:** `IRONWOOD_VS_JAVA.md` retains 108 numbered entries with
  57 ✅, 10 💡, 32 ❌, and 9 ⏳. This decision changes no compiler, runtime,
  standard-library, test, example, packaging, or build behavior. The D065
  303-test compiler baseline remains unchanged, and focused documentation
  consistency and licensing checks are sufficient.

## D074 - Ordinary implicit runtime safety failures use language exceptions

- **Status:** Accepted and implemented
- **Context:** Feature 100 committed null receiver/use, null or out-of-bounds
  array access, negative array length, and `throw null` as catchable ordinary
  failures. The previous null and array-bounds C helpers terminated outside
  Ironwood's exception control flow, negative array allocation exited in the
  allocator, and a literal `throw null` was rejected. Those boundaries bypassed
  typed catch selection, source traces, `finally`, and D051's primary/secondary
  failure ordering even though the compiler already had all of those mechanisms
  for explicit throws, checked casts, and integral division by zero.
- **Decision:** Lower every Feature 100 safety check in compiler-owned typed IR.
  `IrNullCheckInstruction`, `IrArrayBoundsCheckInstruction`, and the new
  `IrArrayLengthCheckInstruction` produce boolean predicates. Semantic lowering
  branches each predicate to a valid continuation and a failure block. A taken
  failure block ordinarily allocates and invokes the no-argument constructor of
  `NullPointerException`, `ArrayIndexOutOfBoundsException`, or
  `NegativeArraySizeException`, then uses the existing `IrThrowTerminator` or
  protected-region invoke/unwind edge. A successful path allocates no exception.

  Preserve source evaluation order. Field receivers are evaluated before their
  null check. Method receivers and every argument are evaluated before an
  instance-call null check. Array references and indices are each evaluated
  once before null and bounds checks. Array lengths are evaluated once before
  their nonnegative check. A literal `throw null` and a null value whose static
  type derives from `Throwable` enter the same null-failure path at the throw
  statement.

  Add `ironwood.lang.ArrayIndexOutOfBoundsException` as an ordinary unchecked
  subclass of `IndexOutOfBoundsException`. Dependency scanning retains the
  applicable exception classes when source, loose `.ironclass`, or `.ironjar`
  inputs reconstruct a closed world. LLVM lowers the predicates and branches
  mechanically; it does not recreate the exception rule from AST.

  Remove `ironwood_check_not_null` and `ironwood_check_array_bounds` from the C
  ABI. `ironwood_allocate_array` and `ironwood_throw` retain aborting internal
  preconditions for invalid calls, but generated source paths establish their
  nonnegative/non-null inputs first. `System.arraycopy` validation remains a
  library-specific runtime boundary outside Feature 100. Exception-object
  allocation is ordinary and visible to `System.allocationCount()`; exhaustion
  while constructing one remains governed by pending Feature 105.
- **Consequences:** Feature 100 is ✅. Its implicit failures use the existing
  first-throw source traces, typed catches, `finally`, and ordered secondary
  exception reporting. The runnable `examples/runtimefailures` project and the
  full 306-test local macOS ARM64 compiler suite cover typed IR/LLVM structure,
  source/class/archive reconstruction, ordinary allocation accounting,
  evaluation order, uncaught diagnostics, nested primary/secondary failure,
  and native `-O0` through `-O3` execution. The standard library contains 117
  source-derived types. The exact host package passes relocated smoke tests,
  including the new example at `-O3`. `IRONWOOD_VS_JAVA.md` now contains 58 ✅,
  10 💡, 32 ❌, and 8 ⏳ entries; the remaining pending priority is 66, 90, 92,
  89, 83, 86, 105, and 91. No subsequent implementation target is selected,
  and no new self-contained IDK, cross-platform, or release-asset result is
  claimed.

## D075 - Reference type patterns use definite-match aliases

- **Status:** Accepted and implemented
- **Context:** Feature 66 committed Java-shaped named reference patterns in
  `instanceof` after D072 separated them from modern switch, record/unnamed
  patterns, and preview primitive patterns. Ironwood already had evaluated-once
  nominal and exact-array type tests, checked casts, SSA control flow, lexical
  capture, and allocation-identity analysis. Requiring a repeated checked cast
  after a successful test remained unnecessary source noise, while a pattern
  binding could not be added honestly without defining its boolean/statement
  scope, artifact dependencies, capture identity, and reclamation behavior.
- **Decision:** Parse `operand instanceof Type name` and
  `operand instanceof final Type name` as an `InstanceOfExpression` carrying an
  optional named binding. Keep plain `instanceof` unchanged. Accept the same
  reifiable reference targets: nominal types, all-unbounded-wildcard generic
  views, and recursively reifiable exact arrays. Reject primitive targets,
  non-reifiable parameterizations and type variables, unnamed `_` bindings,
  and all pattern families assigned to Features 106–108.

  Compute definite-match facts once in a shared AST flow analysis. A direct
  pattern introduces its binding when true; `!` swaps true and false facts;
  `&&` exposes left-true bindings to its right operand and combines true facts;
  `||` exposes left-false bindings to its right operand and combines false
  facts; conditional-expression arms receive the corresponding condition
  facts but the whole conditional introduces none. Reject same-name bindings
  whose true or false scopes overlap.

  Apply those facts to `if`/`else`, non-completing guards, `while`, and basic
  `for`. Loop bodies and `for` updates receive condition-true bindings. A
  condition-false binding survives after a loop only when no reachable `break`
  targeting that loop can bypass it. Type-dependency scanning, semantic name
  lookup, and final/effectively-final lexical-capture analysis use the same
  rules. A pattern binding conflicts with an already active local or parameter
  and follows ordinary assignment restrictions when declared `final`.

  Lower the test through the existing `IrInstanceOfInstruction` or
  `IrArrayTypeTestInstruction`, then represent the successful binding with an
  ordinary typed `IrReferenceConversionInstruction`. Insert SSA phis only where
  short-circuit paths require the alias operand to dominate a later merge. The
  binding carries the tested operand's allocation identity, so it is an
  ordinary source-visible alias for escape and safe-`free`; a fresh allocation
  may be reclaimed through the matched name when no other observable alias
  remains. Add no allocation, runtime type registry, C ABI, LLVM helper, or
  ownership transfer. Format-1 `.ironclass` payloads and `.ironjar` inputs
  reconstruct these source rules during final closed-world link.
- **Consequences:** Feature 66 is ✅. The runnable
  `examples/instanceofpatterns` project and the full 311-test local macOS ARM64
  compiler suite cover positive and negative definite-match scope, conflicts,
  `final`, capture, nominal/reifiable-generic/exact-array bindings, typed IR,
  safe reclamation through a matched alias, zero hidden allocation,
  source/class/archive reconstruction, and native `-O0` through `-O3`
  execution. The exact host package passes its relocated smoke tests, including
  the new example at `-O3`. `IRONWOOD_VS_JAVA.md` contains 59 ✅, 10 💡, 32 ❌,
  and 7 ⏳ entries. Remaining pending priority is 90, 92, 89, 83, 86, 105, and
  91. No subsequent implementation target is selected, and no new
  self-contained IDK, cross-platform, or release-asset result is claimed.

## D076 - Complete Java-shaped statement control flow without hidden ownership

- **Status:** Accepted and implemented
- **Context:** Feature 90 committed `do`/`while`, enhanced `for` over arrays and
  `Iterable`, empty and labeled statements, labeled `break`/`continue`, and
  transfers through active `finally` regions. Ironwood already had typed SSA
  loops, checked array access, generic interface calls, lexical capture,
  explicit ownership, and cleanup lowering for return and exception paths. The
  remaining gap was source and control-flow integration, with enhanced
  iteration requiring an honest lifetime contract for its synthetically held
  iterator.
- **Decision:** Accept Java-shaped `do statement while (condition);`,
  `for ([final] Type name : expression) statement`, `label: statement`, optional
  labels on `break` and `continue`, and the empty statement. Labels are lexical:
  duplicate active names are rejected, `break label` may target any enclosing
  labeled statement, and `continue label` requires an enclosing labeled loop.
  Feature 102's excluded `assert` statement remains unchanged.

  Evaluate an enhanced-for source once. For an array, retain that reference,
  null-check it, read its immutable length, and traverse ascending indices with
  the existing typed bounds-check/load operations. For an
  `ironwood.lang.Iterable<T>`, call `iterator()` once and then issue ordinary
  typed `hasNext()` and `next()` calls with generic substitution. The loop
  borrows the returned `ironwood.util.Iterator<T>` and performs no hidden
  allocation or implicit `free`. The producer owns the iterator and must retain
  an explicit lifetime path; bundled collections already store, reset, and
  return reusable iterators. The iteration variable has body scope, supports
  `final` and lexical capture, and uses ordinary assignment conversion and
  ownership analysis.

  Lower `do`/`while` and both enhanced-for forms into ordinary compiler-owned
  basic blocks, phis, branches, array instructions, and call instructions; add
  no new runtime operation or LLVM-only semantics. Empty statements emit
  nothing. Labeled statements add lexical target contexts and, when necessary,
  one merge block.

  Record the active `finally` snapshot at every break/continue target. When a
  transfer crosses new cleanup regions, lower their source blocks in
  inner-to-outer order before the target jump. If a cleanup returns, throws, or
  performs another transfer, that abrupt completion supersedes the pending
  transfer. A direct transfer within the same active cleanup snapshot remains a
  direct jump. All shared dependency, final-field, effective-final capture,
  local-type discovery, return-origin, escape, owned-array, and pattern-flow
  walkers understand the new statements. Format-1 `.ironclass` and `.ironjar`
  inputs reconstruct the same source rules at final link.
- **Consequences:** Feature 90 is ✅. The runnable `examples/statements` project
  and the full 317-test local macOS ARM64 compiler suite cover parser and type
  diagnostics, evaluated-once array and Iterable traversal, final/captured
  iteration variables, typed IR, nested labeled and unlabeled transfers through
  `finally`, source/class/archive reconstruction, allocation-neutral reusable
  iteration, and native `-O0` through `-O3` execution. The exact host package
  passes relocated smoke tests including the new example at `-O3`.
  `IRONWOOD_VS_JAVA.md` contains 60 ✅, 10 💡, 32 ❌, and 6 ⏳ entries.
  Remaining pending priority is 92, 89, 83, 86, 105, and 91. No subsequent
  implementation target is selected, and no new self-contained IDK,
  cross-platform, or release-asset result is claimed.

## D077 - Implement multi-catch and precise rethrow as compile-time exception typing

- **Status:** Accepted and implemented
- **Context:** Feature 92 committed Java-shaped union catch parameters,
  disjoint-alternative validation, implicitly-final union bindings, and precise
  rethrow for final or effectively-final catch parameters. Ironwood already had
  checked `throws` contracts, generic thrown-type substitution, reifiable catch
  validation, typed exception dispatch, first-throw traces, ordered secondary
  failures, lexical capture, and one native unwind path. The missing work was
  source, flow, and typed-IR integration; no runtime representation change was
  required.
- **Decision:** Accept one or more `|`-separated reifiable `Throwable` subtypes
  before a catch binding. Reject duplicate alternatives and every pair related
  by subtyping, diagnose checked-catch reachability for each alternative, and
  expose one binding whose static type is the least common nominal supertype of
  the valid alternatives. A union binding is implicitly final, so assignment or
  update is rejected, while ordinary final/effectively-final capture rules let
  local and anonymous classes retain the same exception alias.

  For each catch, retain the exact checked thrown types that can reach it after
  prior catches and generic `throws` substitution. A direct rethrow of a final
  or effectively-final catch binding checks those precise types against the
  enclosing callable's contract. Reassigning or updating an ordinary
  single-type catch binding disables precision and restores its declared static
  thrown type. This analysis changes neither the thrown object nor its trace.

  Lower a union catch to one existing nominal membership test per alternative,
  combine the predicates with typed boolean OR operations, and enter one shared
  body. The binding is an SSA alias of the existing exception with no allocation,
  copy, wrapper, ownership transfer, runtime helper, C ABI addition, or LLVM-only
  semantic. Existing catch ordering, native unwind, `finally`, primary/secondary
  failure preservation, and safe-`free` conservatism remain unchanged. Shared
  dependency, local-type, capture, and planning passes carry all alternatives.
  Format-1 `.ironclass` and `.ironjar` inputs reconstruct the same source rules
  at final closed-world link.
- **Consequences:** Feature 92 is ✅. The runnable `examples/multicatch` project
  and the full 323-test local macOS ARM64 compiler suite cover parsing,
  alternative validity and reachability, implicit finality, common-member
  typing, lexical capture, precise and reassignment-widened rethrow, typed IR,
  source/class/archive reconstruction, `finally` secondary failures, and native
  `-O0` through `-O3` execution. The exact host package passes relocated smoke
  tests including the new example at `-O3`. `IRONWOOD_VS_JAVA.md` contains
  61 ✅, 10 💡, 32 ❌, and 5 ⏳ entries. Remaining pending priority is 89, 83,
  86, 105, and 91. No subsequent implementation target is selected, and no new
  self-contained IDK, cross-platform, or release-asset result is claimed.

## D078 - Implement array initializers as explicit typed array construction

- **Status:** Accepted and implemented
- **Context:** Feature 89 committed declaration initializers such as
  `int[] values = {1, 2};` and explicit creation expressions such as
  `new int[] {1, 2}`, including empty and recursive nested forms. Ironwood
  already had recursively invariant array types, checked allocation/access,
  typed array IR, exact descriptors, assignment conversion, allocation
  accounting, and compiler-proven child detachment. The missing work was to
  preserve initializer structure until a contextual array type was known and
  to expose every brace-created child as an ordinary source-owned allocation.
- **Decision:** Accept brace initializers after field, interface-field, and local
  declarations with an array target, and after `new ElementType[]`. Permit an
  optional trailing comma and recursive braces only where the contextual element
  type is itself an array. Infer the exact length from each brace's element
  count. Reject an initializer without an array context, an explicit dimension
  combined with braces, an unsized creation without braces, and every element
  that cannot undergo the existing assignment conversion to its contextual
  element type.

  Allocate the array for one brace before evaluating its elements, then
  evaluate, convert, and store elements strictly from left to right. Lower every
  brace into one ordinary constant-length `IrArrayAllocateInstruction` and each
  element into one typed `IrArrayStoreInstruction`; use the existing LLVM array
  lowering and `ironwood_allocate_array` boundary without an initializer-only
  instruction or runtime helper. The allocation counter therefore increments
  once per written brace and empty initializers still create one zero-length
  array.

  Register every brace-created array in ordinary allocation provenance. When a
  nested child is stored into the parent's known constant slot, retain that
  exact child identity through the existing array-slot proof. A matching load
  may detach the child by overwriting the slot with `null` and then reclaim it;
  freeing the parent never recursively frees an attached child. Observable
  calls, dynamic indices, copies, and escape of the parent remain conservative.
  All dependency, capture, escape, final-field, local-type, return-origin,
  owned-array, and invocation-planning walkers traverse initializer elements.
  Format-1 `.ironclass` and `.ironjar` inputs reconstruct the same contextual
  source rules at final closed-world link.
- **Consequences:** Feature 89 is ✅. The runnable
  `examples/arrayinitializers` project and the full 329-test local macOS ARM64
  compiler suite cover declaration and explicit forms, empty and trailing-comma
  syntax, malformed and type diagnostics, constant narrowing and reference
  conversion, exact allocation counts, source-ordered calls and stores, typed
  IR/LLVM, recursive child ownership and reclamation, source/class/archive
  reconstruction, and native `-O0` through `-O3` execution. The exact host
  package passes relocated smoke tests including the new example at `-O3`.
  `IRONWOOD_VS_JAVA.md` contains 62 ✅, 10 💡, 32 ❌, and 4 ⏳ entries.
  Remaining pending priority is 83, 86, 105, and 91. No subsequent
  implementation target is selected, and no new self-contained IDK,
  cross-platform, or release-asset result is claimed.

## D079 - Implement binary integer literals as ordinary typed constants

- **Status:** Accepted and implemented
- **Context:** Feature 83 committed Java-shaped `0b`/`0B` integer spellings,
  separators, suffixes, and ordinary width selection. Ironwood already had
  decimal and hexadecimal integer tokens, `int`/`long` constant typing,
  full-width hexadecimal two's-complement patterns, static constant folding,
  switch constants, typed integer IR, LLVM integer lowering, and format-1
  source reconstruction. The missing work was radix-specific binary validation
  and consistent value decoding through every constant consumer.
- **Decision:** Accept `0b` or `0B` followed by binary digits, with underscores
  only between digits and an optional terminal `L`/`l`. An unsuffixed literal
  selects `int` and may contain up to 32 significant bits; a suffixed literal
  selects `long` and may contain up to 64. As for hexadecimal literals, a
  spelling that fills the selected width denotes the exact two's-complement bit
  pattern, including negative values when the high bit is set. Reject a missing
  digit, non-binary digit, underscore adjacent to the prefix or suffix,
  trailing underscore, floating suffix, and a magnitude wider than the
  selected type with source-local diagnostics.

  Keep Java-style leading-zero octal excluded under Feature 103: a source value
  such as `010` remains decimal ten. Keep hexadecimal floating-point literals
  outside Feature 83 under Feature 104. Route binary, hexadecimal, and decimal
  integer values through one semantic decoder so ordinary expression lowering,
  decimal-minimum unary handling, compile-time String/conditional folding,
  static-final constants, and classic-switch labels cannot disagree. Emit the
  decoded value as the existing typed `IrConstant` and existing LLVM `i32` or
  `i64` constant; add no binary-specific IR, runtime helper, native ABI,
  allocation, or ownership rule. Format-1 `.ironclass` and `.ironjar` inputs
  reconstruct and validate the same source token at final closed-world link.
- **Consequences:** Feature 83 is ✅. The runnable `examples/binaryliterals`
  project and the full 334-test local macOS ARM64 compiler suite cover prefix
  case, separators, suffix-based overload selection, full-width signed bit
  patterns, malformed and range diagnostics, static folding, switch labels,
  typed IR/LLVM, source/class/archive reconstruction, and native `-O0` through
  `-O3` execution. The exact host package passes relocated smoke tests including
  the new example at `-O3`. `IRONWOOD_VS_JAVA.md` contains 63 ✅, 10 💡, 32 ❌,
  and 3 ⏳ entries. Remaining pending priority is 86, 105, and 91. No subsequent
  implementation target is selected, and no new self-contained IDK,
  cross-platform, or release-asset result is claimed.

## D080 - Implement static imports as compile-time member lookup

- **Status:** Accepted and implemented
- **Context:** Feature 86 committed Java-shaped single-member and on-demand
  static imports for fields, methods, and member types. Ironwood already had
  package and ordinary import parsing, source/class/archive dependency
  discovery, Java-shaped access control, inherited member lookup, fixed-arity
  overload and generic invocation planning, static constants and fields,
  deterministic active-use initialization, format-1 source reconstruction, and
  closed-world tree shaking. The missing work was to introduce imported members
  into the correct name spaces without creating a runtime alias or weakening
  those existing rules.
- **Decision:** Accept `import static p.Owner.member;` and
  `import static p.Owner.*;` where the owner is an accessible canonical type.
  A single-member declaration imports every accessible static field, method,
  and member type of that name, including inherited members permitted by Java;
  an on-demand declaration contributes accessible static members only when an
  unqualified use requires them. Reject a missing owner, missing or entirely
  inaccessible single member, and a single declaration that names only
  non-static members.

  Keep fields, methods, and types in their existing lookup spaces. Lexical
  declarations shadow imported declarations. A single-static field shadows
  same-name static-on-demand fields, a single-static method shadows an
  on-demand method with the same signature, and a single imported member type
  follows Java's type-name shadowing and conflict rules. Deduplicate repeated
  imports and repeated paths to the same declaration. Diagnose ambiguous field
  uses, ambiguous overloads after ordinary generic applicability and
  most-specific selection, conflicting member-type imports, and inaccessible
  members at source locations.

  Add every static-import owner to dependency scanning. Let single imported and
  on-demand member types contribute source-path, class-directory, individual
  `.ironclass`, bundled-class, and `.ironjar` candidates while preserving the
  independence of the value, method, and type namespaces. Format-1 artifacts
  reconstruct the import declaration at final link. Imported constants reuse
  static constant evaluation and classic-switch constant handling; imported
  mutable fields reuse evaluated-once static lvalues; imported methods reuse
  checked exceptions, generic/primitive specialization, and escape summaries.
  Nonconstant field or method use retains the declaring type's existing
  initialization ensure. Add no static-import IR, runtime helper, native ABI,
  allocation, ownership, or reclamation rule.
- **Consequences:** Feature 86 is ✅. The runnable `examples/staticimports`
  project and the full 339-test local macOS ARM64 compiler suite cover syntax,
  duplicate imports, accessibility and missing-member diagnostics, field and
  member-type ambiguity, method overload ambiguity, lexical and single-import
  shadowing, generic and primitive calls, constant initializers and switch
  labels, mutable field loads/stores and initialization, typed IR/LLVM,
  source/class/archive reconstruction, tree shaking, and native `-O0` through
  `-O3` execution. The exact host package passes relocated smoke tests including
  the new example at `-O3`. `IRONWOOD_VS_JAVA.md` contains 64 ✅, 10 💡, 32 ❌,
  and 2 ⏳ entries. Remaining pending priority is 105 and 91. No subsequent
  implementation target is selected, and no new self-contained IDK,
  cross-platform, or release-asset result is claimed.

## D081 - Implement bounded catchable source-allocation exhaustion

- **Status:** Accepted and implemented
- **Context:** D070 committed Feature 105's Ironwood-native allocation-failure
  contract. Ordinary exception propagation previously depended on allocating a
  native unwind wrapper and trace/association metadata, while the object and
  array allocators aborted when `calloc` returned null. Source-evaluated object,
  array, and String-result operations therefore needed an allocation-free path
  that preserved the existing typed exceptional CFG, source traces,
  first/secondary cleanup precedence, allocation accounting, and explicit
  ownership rules.
- **Decision:** Emit one immortal compiler-owned
  `ironwood.lang.OutOfMemoryError` with a null message whenever retained code can
  perform a source allocation. Store it in `IrProgram`, retain it through
  closed-world tree shaking, and pass it with exact type metadata to object,
  array, `Object.toString()`, `String.fromChars(...)`, and dynamic String-
  concatenation runtime boundaries. Treat these typed operations as invoke-
  capable in protected regions. Mark source catch entry explicitly in typed IR
  so the runtime can release active emergency state only after the implicit
  error has landed in source code.

  Install object and array descriptors inside their successful allocator
  boundaries and increment `System.allocationCount()` only after storage has
  been obtained. On native allocation failure or checked size overflow, reuse
  the immortal error through one runtime-private unwind wrapper, fixed trace
  storage, and one required emergency primary/secondary association. Preserve
  the exact failing frame, retain up to 63 additional frames, and report an
  explicit truncation marker when more frames exist. Preserve the first
  exception when allocation fails in `finally`, and retain the later exception
  as a secondary in occurrence order. A second allocation failure while the
  implicit error remains active terminates with a deterministic diagnostic.
  After source catch, a later occurrence may reuse the singleton and replace
  its bounded trace and association state.

  Preserve Ironwood's receiver/argument/dimension/String-operand-before-result-
  allocation ordering. A failed allocation creates no ordinary object, adds no
  allocation count, and creates no source reclamation obligation. Catch code
  may release owned capacity and retry, but success is not guaranteed. Keep
  pre-entry argument construction, runtime-private bookkeeping failure,
  operating-system overcommit termination, and fatal memory signals outside
  the catchable source contract. Provide `IRONWOOD_ALLOCATION_LIMIT` only as a
  runtime-private deterministic diagnostic hook; it is not a source API or a
  general memory quota.
- **Consequences:** Feature 105 is 💡. The runnable
  `examples/allocationfailure` project and the full 345-test local macOS ARM64
  compiler suite cover object, array, and String-result failure boundaries,
  evaluation order, singleton identity, null message, allocation counts,
  source/class/archive/tree-shaking reconstruction, primary/secondary ordering,
  deterministic recursive failure, bounded exact traces, and native `-O0`
  through `-O3` execution. The exact host package passes relocated smoke tests
  including the new example at `-O3`. `IRONWOOD_VS_JAVA.md` contains 64 ✅,
  11 💡, 32 ❌, and 1 ⏳ entry. Feature 91 is the sole remaining pending feature
  and priority 1. No subsequent implementation target is selected, and no new
  self-contained IDK, cross-platform, or release-asset result is claimed.

## D082 - Implement modern non-pattern switch rules and expressions

- **Status:** Accepted and implemented
- **Context:** D073 committed Feature 91 after D072 separated it from reference
  type patterns in switch, record/unnamed patterns, and preview primitive
  patterns. Classic Feature 65 already supplied explicit-fallthrough integral
  and enum statements. The remaining work needed modern non-pattern source
  structure without weakening closed-world native compilation, explicit
  ownership, typed-IR lowering, catchable null behavior, static initialization,
  or the exclusions recorded as Features 106–108.
- **Decision:** Accept arrow-rule switch statements and switch expressions over
  `byte`, `short`, `char`, `int`, one exact enum type, or String. Evaluate the
  selector once. Accept compatible compile-time constant labels, comma-separated
  constants, `case null`, and the exact combined `case null, default` rule.
  Arrow rules never fall through. Preserve classic colon-statement fallthrough;
  permit colon-form expressions to produce results with `yield`.

  Require every switch expression to declare `default` or cover all constants
  of its exact closed-world enum selector. Use target typing when available;
  otherwise merge numeric results through promotion, null with a reference, or
  references through their closed-world least upper type. Lower normal results
  to a typed phi. A `yield` executes all crossed `finally` blocks from inner to
  outer; abrupt cleanup supersedes the pending result. An unlabeled `break`
  cannot exit a switch expression. Keep ordinary labeled transfers, loop
  `continue`, exception propagation, first/secondary failure behavior, final-
  field analysis, capture, and static active-use initialization.

  Reuse `IrSwitchTerminator` for integral and enum ordinal dispatch. Lower
  String selection as source-ordered equality branches against pooled
  compile-time constants, with the existing catchable null-check path when no
  null label exists. Extend dependency, lexical-type, capture, definite-field,
  pattern-flow, invocation, escape, owned-array, and symbolic-return analysis
  over the new AST. Preserve conservative safe-`free` proofs across uncertain
  branch identities. Format-1 `.ironclass` and `.ironjar` inputs reconstruct
  the same source rules at final link. Add no runtime operation, hidden result
  allocation, dispatch table, reclamation action, reflection metadata, or
  native ABI. Reject pattern-switch families with direct diagnostics under
  Features 106–108.
- **Consequences:** Feature 91 is ✅. The runnable `examples/modernswitch`
  project and full 351-test local macOS ARM64 compiler suite cover syntax,
  malformed and pattern diagnostics, integral/enum/String/null selection,
  comma and combined null/default labels, classic and arrow statements,
  colon/arrow expressions, result typing, exact-enum exhaustiveness,
  evaluated-once selectors, static initialization, final fields, exception and
  `finally` behavior, ownership analysis, typed IR/LLVM, source/class/archive
  reconstruction, and native `-O0` through `-O3` execution. The exact host
  package passes relocated smoke tests including the new example at `-O3`.
  `IRONWOOD_VS_JAVA.md` contains 65 ✅, 11 💡, 32 ❌, and 0 ⏳ entries. No
  numbered feature remains pending, Feature 104 remains an open promotion
  decision with its current ❌ status, no subsequent implementation target is
  selected, and no new self-contained IDK, cross-platform, or release-asset
  result is claimed.

## D083 - Implement deterministic destruction and failed-construction rollback

- **Status:** Superseded in part by D084 and D102; supersedes D027's no-destructor and
  failed-construction-retention rules and refines D005
- **Context:** Compiler-proven `free` previously raw-deallocated exactly one
  allocation. That was safe but left class-owned terminal storage without a
  compositional teardown mechanism and deliberately retained every receiver
  whose constructor threw. Ironwood needed deterministic object teardown and
  leak-free partial construction without introducing a collector, raw pointers,
  unsafe `free`, JVM finalization, or Rust-style lifetime syntax.
- **Decision:** Permit a class, including abstract, nested, local, or anonymous
  classes, to declare at most one unmodified `destructor { ... }`. Interfaces,
  enums, and enum-constant bodies cannot declare one. A destructor has no name,
  parameters, result, modifiers, `@Override`, or explicit `return`. An accepted
  object `free` selects the most-derived descriptor entry, executes declared
  bodies from derived class to root superclass, and raw-deallocates only after
  normal completion. Arrays have no source destructor.

  Represent methods, constructors, destructors, class initializers, and
  constructor rollback with an explicit typed-IR callable kind. Store optional
  destructor and rollback entries in each closed-world descriptor. Lower source
  destruction, raw deallocation, rollback, and current-live allocation queries
  as compiler-owned typed IR before mechanical LLVM emission.

  Compute closed-world callable effects to a fixed point, including active-use
  class-initialization prerequisites. Every reachable destructor path must be
  allocation-free, must handle any thrown exception internally, and must not
  publish or resurrect `this`. Construction must not publish in-progress
  `this`. If a destructor nevertheless unwinds across the backend boundary,
  terminate deterministically rather than continue with a partially destroyed
  object.

  Extend the D005 proof with symbolic fresh-or-null factory returns and
  compiler-proven-owned private reference fields. A declaring destructor may
  destroy such a field directly; lowering clears the field before recursive
  child destruction. Preserve conservative rejection for live aliases,
  retained arguments, unknown or conflicting branch identity, escaped fields,
  and uncertain polymorphic effects. Control-flow that leaves an allocation's
  proof state unchanged no longer invalidates it merely for crossing a branch.

  For each ordinary class, emit a constructor-rollback callable that visits its
  compiler-proven-owned layout fields in reverse order. Stored child values have
  completed construction and use normal destruction; the incomplete receiver is
  raw-deallocated without running its source destructor. When construction
  unwinds, invoke rollback and rethrow the exact original exception. Track both
  cumulative successful allocations and a current-live count; expose the latter as
  `System.liveAllocationCount()` for deterministic verification.

  Audit standard-library ownership under these rules. Reclaim only exclusively
  owned backing containers, distinguish allocated from wrapped `ByteBuffer`
  storage, and make object-pool checkout transfer explicit with
  `takeRetained()` while retaining `get()` as a compatibility delegate. Never
  recursively free inserted user values, borrowed iterators, wrapped arrays, or
  pooled elements.
- **Consequences:** The local 355-test compiler suite covers grammar and
  placement, effect and publication rejection, fresh factories, owned-field
  teardown, derived-to-root order, current-live counts, failed-construction
  rollback, typed IR and LLVM descriptor/helper lowering, standard-library
  ownership, source/class/archive reconstruction, and native `-O0` through
  `-O3` execution. The updated reclamation example verifies destructor count
  and a stable live-allocation baseline. Features 10 and 11 remain 💡 because
  destruction and `free` are Ironwood-native alternatives rather than Java
  garbage-collection syntax; the matrix remains 65 ✅, 11 💡, 32 ❌, and 0 ⏳.
  No automatic reachability reclamation, Java finalization/cleaner semantics,
  unsafe escape hatch, or new numbered implementation target is introduced.

## D084 - Treat compiler-owned reusable helpers as dependent borrows

- **Status:** Accepted and implemented, with pooled-entry and retained-capacity
  lifetime clauses superseded by D105 and monotonic insertion limits superseded
  by D107; supersedes D083's process-lifetime rule
  for reusable iterators and holders and further refines D005
- **Context:** D083 could destroy compiler-proven-owned private fields only when
  their identities were never returned. `ironwood.ds` intentionally returns a
  cached heap iterator, and primitive iterators return a cached holder, to avoid
  allocation on every traversal. Treating those helpers as process-lifetime
  capacity leaked a collection's private helper graph. Treating the return as
  an ownership transfer would instead let callers invalidate the collection
  and create double destruction. Ironwood needed the ordinary Java-shaped
  iterator API with deterministic, compiler-proven owner teardown.
- **Decision:** A freshly installed, encapsulated private helper may remain
  owned by its containing object while selected methods return dependent borrows
  of that helper. The compiler associates each borrow, including interface
  reference conversions and nested reusable-holder results, with the root owner
  allocation and the helper's closed-world concrete type.

  Exact summaries preserve that dependency through ordinary wrapper-method
  returns. A caller of a method returning `list.iterator()` therefore receives
  the same borrow rather than a fresh allocation or transferred ownership.

  The caller may observe the borrow through closed-world-proven non-retaining
  calls but may not `free` it. A local borrow does not block owner destruction
  merely because its variable remains in lexical scope; any source observation
  after accepted owner destruction is diagnosed as use-after-free. Publication
  through a field, static, array, an outward return from the current allocation
  region, capture, retaining call, or uncertain polymorphic call escapes the
  root owner and rejects its `free`. Double-free is
  still an error, never a no-op, and no undefined-behavior fallback is added.

  Distinguish constructor containment from publication. Passing the owner to a
  freshly installed owned helper is safe only when the constructor retains it
  solely in a private field whose complete closed-world use is encapsulated.
  Returning or otherwise publishing that backlink makes owner construction
  escaping. Compute call and constructor summaries to a fixed point and use
  unique closed-world dispatch where available; uncertainty remains rejection.

  Update every `ironwood.ds` iterable destructor to reclaim its reusable
  iterator. Primitive iterator destructors also reclaim their reusable holder.
  Composite sets and the hybrid list destroy owned helper components in
  dependency order. Preserve the separate non-owning contracts for inserted
  keys/values, pooled entries, pooled/cyclic nodes, and retained high-water
  capacity. Replace per-set instance filler allocations with one private
  class-owned sentinel per set implementation.

  Treat logical collection state, rather than physical nulling, as the
  visibility boundary for inactive storage. Ironwood has no tracing collector,
  and its current retaining-argument escape analysis is monotonic, so clearing
  an unobservable stale key/value/element reference neither reclaims that user
  object nor makes a later `free` provable. Generic array-list removal and
  `ArrayList.clear()` may therefore leave inactive slots unchanged;
  `ArrayLinkedList.clear(true)` clears only its current fixed-array prefix, not
  stale tail slots left by an earlier `clear(false)`. Reused map/list entries
  may retain user key/value fields until checkout overwrites them, and rehash
  may free its detached bucket array without first nulling migrated entries.
  Preserve nulling that is semantically structural: direct-index map slots are
  membership markers, bucket heads must be unlinked, and linked-node ordering
  fields must be reset when reuse does not overwrite them.
- **Consequences:** Java developers keep ordinary `Iterator<E>` source with no
  annotations, regions, or Rust-style lifetime syntax. Completed traversal
  followed by `free list;` is valid; using or independently freeing the cached
  iterator is rejected; and an escaped or unprovable borrow prevents list
  destruction. The local 357-test compiler suite covers live/dead local
  observations, direct borrowed-helper free, escape, encapsulated backlinks,
  reusable holder composition, conflicting-owner control-flow merges, all
  standard-library destructor compilation, stable `ArrayList` live-allocation
  counts, and native `-O0` through `-O3` execution. The detailed behavior
  matrix is [Owned Helper Borrows](OWNED_HELPER_BORROWS.md). No numbered Java
  feature or comparison-matrix total changes.

## D085 - Complete the standard-library S0 porting and reclamation foundation

- **Status:** Accepted and implemented; builds on D083 and D084 without
  selecting a numbered Java-language feature
- **Context:** The standard-library roadmap required a representative
  Java-shaped slice to pass the licensing/provenance and explicit-reclamation
  gates before U1 could publish a broader family of allocating APIs. Existing
  safe-`free` summaries recognized source `new` and selected factories, but did
  not classify default `Object.toString()` or the private String snapshot
  intrinsics as fresh results. A method-wide fresh flag also could not safely
  distinguish a reclaimed method-local temporary from a different returned
  allocation. The initial StringBuilder facade owned backing storage, but its
  snapshot and constructor call graph needed exact result/escape summaries for
  deterministic caller teardown and rollback.
- **Decision:** Classify the S0 source before implementation in
  `STDLIB_S0_SOURCE_REVIEW.md`. Implement `String.substring(int)`,
  `substring(int, int)`, and `toCharArray()` independently under
  `MIT OR Apache-2.0`; inspect or adapt no OpenJDK implementation body, comment,
  Javadoc, test, algorithm, or distinctive structure. Treat the compiler,
  typed IR, runtime ABI, artifact handling, tests, examples, and packaging as
  original Ironwood mechanisms under the same default license.

  Every successful substring, character-array export, StringBuilder snapshot,
  and default Object identity-text result is a fresh caller-owned ordinary
  allocation. Empty and whole-value substrings remain distinct allocations.
  Invalid ranges throw the existing catchable
  `StringIndexOutOfBoundsException`. Lower a validated substring through a new
  `IrStringFromRangeInstruction` and narrow runtime boundary that copies UTF-16
  units directly into one exact-size String tail; allocate no scratch array and
  expose no source storage. A failed allocation therefore returns no result and
  leaves no partial source-visible state.

  Centralize intrinsic result classification for default `Object.toString()`,
  `String.fromChars(...)`, and `String.fromRange(...)`. Track a set of symbolic
  fresh origins per expression instead of one method-wide bit, and distinguish
  return-only receiver/argument aliases from outward non-return escapes. This
  permits a wrapper to reclaim one local allocation and return another fresh
  result without weakening conservative rejection of publication, ambiguity,
  live aliases, double free, or post-free use. Preserve summaries through
  format-1 source reconstruction so source-path, loose `.ironclass`,
  `.ironjar`, pruning, and separate-link workflows produce the same proof.

  Retain StringBuilder's explicit backing-array ownership. Successful `free`
  runs its destructor after snapshots and borrowed inputs are no longer
  observable. If construction fails after the backing array is installed, the
  existing compiler-generated rollback destroys that child and raw-deallocates
  the incomplete receiver while preserving the original failure.

  Package the S0 review and provenance ledger with the standard-library archive,
  host distribution, and IDK. Require safe-`free` positive/negative coverage,
  normal and forced-failure live-count baselines, typed-IR and LLVM inspection,
  native `-O0` through `-O3`, source/class/archive and tree-shaking round trips,
  package smoke tests, license checks, and a runnable text-reclamation example.
- **Consequences:** U1 may build additional allocating text and conversion APIs
  on an executable ownership contract rather than leaking call results until
  process exit. The local 359-test compiler suite covers the S0 semantics,
  native behavior, allocation failure, rollback, artifact reconstruction, and
  optimization levels. The new example slices UTF-16 text, exports code units,
  snapshots a builder, frees every ordinary result and owned wrapper, and
  verifies that `System.liveAllocationCount()` returns to baseline. No garbage
  collector, automatic reachability reclamation, ownership/lifetime syntax,
  unsafe escape hatch, runtime interning, derived OpenJDK source, or comparison-
  matrix total change is introduced.

## D086 - Complete the U1 text-capable command-line standard-library slice

- **Status:** Accepted and implemented; builds on D085 without selecting a
  numbered Java-language feature
- **Context:** S0 established fresh-result provenance and reclamation across
  source, class, archive, pruning, and native-link boundaries, but an ordinary
  command-line tool still lacked familiar String search and construction,
  integer parsing, stderr, primitive output, and small platform services. U1
  needed to complete that vertical slice without importing JVM machinery,
  OpenJDK implementation source, hidden retained storage, or a duplicate
  collections framework.
- **Decision:** Classify the complete slice in
  `STDLIB_U1_SOURCE_REVIEW.md` as independently implemented Java-compatible
  source plus original Ironwood compiler/runtime mechanisms under
  `MIT OR Apache-2.0`. Inspect, copy, translate, or adapt no OpenJDK
  implementation body, comment, Javadoc, test, algorithm, or distinctive
  structure.

  Make `String` final and implement its copy and checked `char[]`
  constructors as compiler-owned storage operations. Validate null and ranges
  before one exact-size allocation, copy UTF-16 units, retain no source alias,
  and classify constructor arguments as nonescaping in both escape-summary
  analyses. Add the U1 Java-shaped UTF-16 search, prefix/suffix,
  subsequence, concatenation, copy, conversion, and comparison surface. Keep
  allocating `concat`, constructor, character, integer, long, and environment
  results caller-owned and compiler-reclaimable; literals and fixed boolean
  text remain immortal.

  Add non-boxing primitive utility facades and `Comparable<T>` rather than
  automatic boxing. Integer parsing accepts signs, ASCII digits and letters,
  radices 2 through 36, and rejects malformed or overflowing input with
  `NumberFormatException`. Defer Java's wider Unicode digit repertoire and
  exact floating parsing APIs until dedicated Unicode/algorithm review.

  Add common `Math` overloads and constants. Lower `sqrt` and `pow` through
  typed math instructions to LLVM intrinsics; keep NaN, infinity, signed-zero,
  overflow, and rounding checks explicit and covered at every optimization
  level. This is ordinary `Math`, not a cross-platform `StrictMath`
  reproducibility promise.

  Emit `System.out` and `System.err` as distinct immortal PrintStream objects
  with compiler-initialized native channel fields. Lower primitive/String
  `print` and `println`, `flush`, and `checkError` through typed stream
  instructions. Add a narrow fresh-or-null `System.getenv(String)` boundary
  that releases native scratch storage, plus LF line separation on supported
  hosts and realtime/monotonic integer clocks. Do not add stream replacement,
  arbitrary construction, `System.in`, a mutable environment map, JVM
  properties, dynamic loading, or security-manager machinery.

  Preserve new instructions through specialization, dependency scanning,
  pruning, LLVM exception edges, and format-1 source reconstruction. Package
  the U1 review and the commented `examples/echo` acceptance program in host
  distributions and IDKs.
- **Consequences:** A native Ironwood CLI can validate arguments, parse an
  integer, search and copy UTF-16 text, print primitives to stdout, diagnose
  errors on stderr, read one environment value, and use basic clocks/math with
  Java-familiar source. The local 364-test compiler suite covers typed IR,
  Java 21 differential results, malformed inputs, live allocation baselines,
  forced failures, source/class/archive round trips, pruning, and native
  `-O0` through `-O3`. At D086 completion, U2 whole-file I/O was the next
  standard-library tranche.
  U1 introduces no garbage collector, boxing, runtime interning, ownership
  syntax, unsafe escape hatch, OpenJDK-derived source, or comparison-matrix
  total change.

## D087 - Complete the U2 files and minigrep standard-library slice

- **Status:** Accepted and implemented; builds on D086 without selecting a
  numbered Java-language feature
- **Context:** U1 made text-capable native command-line programs practical, but
  they could not yet read a user-selected file. U2 needed a familiar path and
  whole-file I/O surface, checked failure categories, and a substantial
  acceptance application without importing JVM filesystem machinery or
  weakening explicit reclamation.
- **Decision:** Classify U2 in `STDLIB_U2_SOURCE_REVIEW.md` as independently
  implemented Java-compatible library source plus original Ironwood compiler,
  runtime, test, and project work under `MIT OR Apache-2.0`. Inspect, copy,
  translate, or adapt no OpenJDK implementation body, comment, Javadoc, test,
  algorithm, or distinctive structure.

  Add checked `IOException`, `EOFException`, and `FileNotFoundException`, plus
  `Path`, `Paths`, a private POSIX `UnixPath`, `InvalidPathException`,
  `FileSystemException`, and `NoSuchFileException`. Keep the Java call shape
  where Ironwood's fixed-arity and closed-world model permits it. Paths are
  lexical UTF-16 values represented natively as strict UTF-8; they support
  roots, parents, names, normalization, absolute conversion, resolution,
  equality, hashing, and ordering without providers or runtime loading.

  Add whole-file `Files.readAllBytes`, strict UTF-8 `readString`, byte and UTF-8
  writes with create/truncate behavior, existence/type queries, and size.
  Categorize missing, permission, non-directory, directory, malformed-text,
  oversized-file, and other host failures as familiar checked exceptions.
  Keep streams, directory mutation/traversal, options, charsets, file times,
  permissions, providers, and the legacy `File` facade outside U2.

  Lower the surface through one typed file/path IR family and an isolated POSIX
  C ABI. Close handles and release native scratch on success, host failure,
  malformed input, oversized input, and source-allocation failure. Successful
  paths and whole-file reads are fresh caller-owned allocations. File and path
  operations borrow their inputs; each concrete path owns and destroys exactly
  one internal normalized String. Preserve those summaries through source,
  loose-class, archive, specialization, pruning, exception, and link workflows.

  Place substantial applications under a new `projects/` tree that mirrors the
  examples' compile/link/run workflow. Ship a thoroughly commented
  `projects/minigrep` based on the Rust Book Chapter 12 behavior: two arguments,
  literal line search, `IGNORE_CASE`, stdout for matches, stderr for errors, and
  0/1/64/74 statuses. Keep exact mode Unicode-preserving and document that the
  case-insensitive teaching mode folds ASCII letters only.

  Package the U2 review and project in host distributions and IDKs. Require
  typed-IR/LLVM inspection, file/path/error and forced-allocation coverage,
  loose-class/archive round trips, native `-O0` through `-O3`, package smoke
  tests, and the license gate.
- **Consequences:** Ironwood can now build a useful native file-search utility
  with Java-shaped source, checked I/O failures, predictable ownership, and no
  JVM or garbage collector. The local 368-test compiler suite covers U2 across
  optimization and artifact modes. Focused `Float.parseFloat` and
  `Double.parseDouble` work remains next before U3. U2 introduces no OpenJDK-
  derived source, streams, filesystem-provider registry, regex engine, full
  Unicode case folding, ownership syntax, unsafe escape hatch, or numbered
  comparison-matrix change.

## D088 - Complete Java-compatible floating parsing without Ironwood heap scratch

- **Status:** Accepted and implemented; builds on D086 and D087 without
  selecting a numbered Java-language feature
- **Context:** U1 deliberately deferred `Float.parseFloat(String)` and
  `Double.parseDouble(String)` until their grammar, rounding, provenance, and
  ownership boundary could be reviewed. U2 then made this focused follow-up the
  last planned compatibility task before U3. Parsing is also a hot pure
  operation, so successful calls should not create managed objects or native
  heap scratch merely to adapt UTF-16 to a platform conversion routine.
- **Decision:** Classify the implementation in
  `STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md` as independently implemented
  Java-compatible library/compiler/runtime work under `MIT OR Apache-2.0`; no
  OpenJDK implementation body, comment, Javadoc, test, algorithm, or distinctive
  structure was inspected or adapted.

  Add the exact Java-shaped public methods to the static primitive utility
  classes. Accept decimal and hexadecimal forms, signs, exponents, suffixes,
  exact NaN/infinity spellings, and Java trim whitespace; preserve signed zero,
  overflow, underflow, and null versus malformed-input exception categories.

  Validate the complete grammar over borrowed UTF-16 in package-private
  Ironwood code. Lower only validated finite numeric text through
  `IrFloatingParseInstruction` to a narrow native ABI. Normalize into fixed
  stack storage, retain a conservative 1,200 significant digits plus sticky
  state for long rounding-boundary inputs, and use the supported host's C11
  `strtof`/`strtod` conversion. Successful parsing creates no Ironwood object
  and the Ironwood parser and runtime bridge call no `malloc` or `realloc`;
  malformed text may allocate its ordinary `NumberFormatException` on the
  failure path.

  Require typed-IR/LLVM inspection, unchanged successful-path allocation
  counts, Java 21 differential behavior, adversarial long and exact-halfway
  inputs, malformed/null cases, and native `-O0` through `-O3` coverage. Carry
  the source review in the standard-library archive, host distribution, and
  IDK.
- **Consequences:** Native programs now have familiar float and double parsing
  without a success-path heap cost, boxing, a JVM, or OpenJDK-derived source.
  The local 370-test compiler suite covers the new API and its native boundary.
  Public static floating formatting remains deferred; U3 streaming I/O is the
  next standard-library milestone.

## D089 - Reclaim temporary standard-library object and collection rendering

- **Status:** Accepted and implemented; first repair stage from the current
  standard-library allocation audit
- **Context:** Collection `toString()` implementations returned their required
  caller-owned String but abandoned the temporary `StringBuilder` and its
  backing array. `StringBuilder.append(Object)` and PrintStream Object output
  also abandoned a fresh String returned by `Object.toString()` or a compatible
  override, while blindly freeing every override result would corrupt borrowed
  aliases such as `String.toString()`.
- **Decision:** Keep Java-shaped public rendering APIs and implement an original
  Ironwood closed-world ownership protocol under `MIT OR Apache-2.0`; inspect or
  adapt no OpenJDK implementation source. Record one Boolean in every concrete
  class and array descriptor indicating that its resolved `toString()` target
  always returns fresh, unescaped text according to fixed-point ownership
  analysis. Lower private StringBuilder/PrintStream consumption points through
  typed IR to a narrow runtime release boundary. Reclaim the consumed String
  only when the concrete descriptor carries that proof; leave borrowed,
  identity, unknown, and mixed-result overrides untouched. Run Object append
  cleanup from `finally` so a later builder failure does not strand an already
  produced fresh rendering.

  Update every current collection renderer to retain the required result,
  reclaim its method-local builder on successful rendering, and return the
  result. Treat overloaded StringBuilder append operations as receiver-returning
  borrowing calls in both ownership-summary analyses rather than conservatively
  publishing the builder or appended argument merely because the lightweight
  summary resolver cannot distinguish those overloads by argument count.
- **Consequences:** Successful collection rendering now leaves exactly its
  caller-owned result live; freeing that result returns the rendering operation
  to its prior live-allocation baseline. Object append and output likewise leave
  no fresh temporary rendering live, while alias-returning overrides remain
  valid. The 372-test compiler suite covers fresh and borrowed overrides,
  typed-IR/LLVM metadata and release lowering, native `-O0` through `-O3`, and
  the existing allocation, collection, safe-`free`, and artifact behaviors.
  This protocol does not add general automatic reclamation, a garbage
  collector, ownership syntax, or OpenJDK-derived source. At the time of D089,
  exceptional cleanup of a collection's own builder was deferred to broader
  structured-flow ownership work rather than implemented through an unsafe
  library escape hatch.

## D090 - Preserve ownership independently across duplicated finally cleanup

- **Status:** Accepted and implemented; supersedes D089's exceptional-rendering
  cleanup limitation
- **Context:** Semantic lowering emits mutually exclusive copies of one source
  `finally` block for normal completion, evaluated returns, catch completion,
  and exceptional unwinding. Safe-free state was previously mutated while
  those CFG copies were generated sequentially. A valid `free builder` could
  therefore make a later generated copy appear to double-free the same
  allocation even though only one copy can execute at runtime. This prevented
  collection renderers from reclaiming their builder when element rendering
  threw.
- **Decision:** Keep safe `free` a compile-time proof and implement the repair as
  original Ironwood compiler and library work under `MIT OR Apache-2.0`; inspect
  or adapt no OpenJDK implementation source. Capture ownership with every
  normal and exceptional predecessor, restore the appropriate snapshot before
  lowering each cleanup copy, preserve common allocation identity through
  exceptional SSA phis, and conservatively merge genuinely conflicting active,
  freed, escaped, or detached states as uncertain. Apply the same isolation to
  return cleanup, catch dispatch, nested secondary-exception cleanup, and
  constructor rollback paths.

  Put every current collection renderer's temporary `StringBuilder` in
  source-written `try`/`finally`, returning its caller-owned String from the
  protected body and freeing the builder in cleanup. Retain ordinary rejection
  for real double free, post-free use, escape, and alias conflicts; add no
  runtime idempotence, unsafe bypass, hidden collector, or ownership syntax.
- **Consequences:** Collection rendering now returns only its required String on
  success and reclaims its builder on exceptions. Source code may use an
  ownership-proven `free` in `finally` across normal, return, catch, throw, and
  nested-cleanup paths. Focused diagnostics and native `-O0` through `-O3`
  allocation-count tests cover accepted cleanup and adversarial double-free,
  post-free-use, and escape cases. The local 373-test compiler suite covers the
  change. `free` within cleanup crossed by `break`, `continue`, or `yield`
  remains a conservative diagnostic pending transfer-target ownership
  snapshots. No OpenJDK-derived source is introduced.

## D091 - Carry ownership through cleanup transfers and loop back edges

- **Status:** Accepted and implemented; supersedes D090's transfer-cleanup
  limitation and its treatment of conflicting freed states as ordinary uncertainty
- **Context:** A `break`, `continue`, or `yield` can execute source cleanup and
  then resume within the same method. The destination must see the cleanup's
  ownership effects, and loop re-entry must not replay a free on a dead or
  differently owned allocation.
- **Decision:** Capture post-cleanup ownership with transfer and yield edges,
  isolate duplicated cleanup and switch-arm lowering, and merge state at labels,
  switch results, loop exits, conditions, and updates. Keep possibly-freed state
  distinct from ordinary uncertainty so observation as well as repeated free
  remains rejected. Retain a pending reference-valued yield as an observable
  alias during cleanup and recheck previously evaluated references at their
  point of consumption. Track allocations present on each path, including those
  first created inside a switch arm.

  Check loop back edges before accepting reclamation: do not carry newly freed
  references, and require unchanged live ownership and identity on continuing
  paths for an entry allocation freed in the loop. Include earlier-iteration
  effects in exit ownership. Per-iteration body-local allocations remain
  reclaimable; uncertain loop-carried identities are conservatively rejected.
- **Consequences:** Proven-safe `free` now works in cleanup crossed by all three
  transfers, including nested labeled jumps, without runtime guards, implicit
  reclamation, or new source syntax. Regression coverage checks destination
  reuse, repeated free, pending result aliases, cleanup replacement and failure,
  typed IR, source/class/archive reconstruction, and native allocation balance
  at `-O0` through `-O3`. This is original Ironwood compiler work under
  `MIT OR Apache-2.0`; no OpenJDK implementation was copied or adapted.

## D092 - Allocate only retained numeric, character, and builder-slice text

- **Status:** Accepted and implemented; supersedes U1's temporary-array
  implementation of integer/character text conversion
- **Context:** The second standard-library allocation-audit repair stage found
  short-lived character arrays in integer/character conversion and a complete
  intermediate snapshot in `StringBuilder.subSequence`. These added allocations
  to immediate work and could remain allocated if the subsequent result failed.
- **Decision:** Preserve the public Java-shaped APIs and fresh-result identity.
  Lower package-private integer and character String factories through explicit
  typed IR to the native runtime, extending Ironwood's existing digit writer
  to radices 2 through 36. Fill the exact-size result directly using bounded
  scalar state; retain invalid-radix fallback to decimal and signed-minimum
  correctness. Validate builder subsequence bounds against its live length in
  source, then use the existing direct char-range copy boundary. Neither path
  creates managed helpers or native heap scratch.
- **Consequences:** Every successful conversion or subsequence allocates exactly
  one independently reclaimable String, including empty slices and isolated
  surrogate code units. Failed result allocation publishes nothing and strands
  no helper; invalid slice bounds precede result allocation. Typed-IR, ownership,
  Java 21 differential, allocation-budget, UTF-16, distribution round-trip, and
  native `-O0` through `-O3` regressions cover the behavior. This is original
  Ironwood work under `MIT OR Apache-2.0`; no OpenJDK implementation was copied
  or adapted. Public floating-point formatting and further audit stages remain
  outside this change.

## D093 - Minimize filesystem and path scratch without weakening I/O semantics

- **Status:** Accepted and implemented; completes the third allocation-audit
  repair stage and supersedes D087's full-file staging and intermediate path copies
- **Context:** Whole-file reads staged complete native buffers before copying
  results, path transformations repeatedly copied temporary Strings/paths, and
  writes snapshotted even immutable Strings. Native path normalization and
  ordinary host spelling also allocated immediate-use heap buffers.
- **Decision:** Preserve the U2 public APIs and implement the repair as original
  Ironwood code under `MIT OR Apache-2.0`, without importing OpenJDK source.
  Construct each path's owned String inside the final wrapper; add typed fused
  sibling/absolute operations and admit audited fresh helpers to owned-field
  construction. Normalize lexical paths using two linear scalar-state passes.
  Use 4 KiB stack spelling/cwd buffers with a reclaimed long-path heap fallback,
  preserving the existing path-length surface. Read through descriptors and
  bounded 8 KiB chunks directly into one unpublished result, resizing it as
  necessary rather than trusting file metadata or staging a full-file copy.

  Borrow immutable String writes directly. Snapshot arbitrary `CharSequence`
  content once into one character array, then validate and encode it directly;
  keep this necessary snapshot to preserve observation and failure ordering
  before destination truncation. Source `finally` always reclaims it. Native
  failure paths close descriptors and reclaim unpublished results/fallbacks;
  failed optional compaction keeps the valid result. Preserve allocation-effect
  checks for native helpers, including destructor rejection.
- **Consequences:** Built-in path results need two managed allocations, whole-
  file reads publish one, and successful immutable String/byte writes and
  metadata queries need no managed helper. Long host spellings and arbitrary
  mutable sequences retain documented fallbacks; resizing result storage is
  not claimed to require only one native allocator call. Regression coverage
  combines Java 21 path comparisons, managed/native allocation counts,
  allocation and I/O faults, UTF-8 chunk boundaries, stable snapshots, native
  optimization levels, and class/archive reconstruction. No stream API, new
  public formatting API, GC, unsafe ownership transfer, or automatic reclamation
  is introduced.

## D094 - Prove caller-owned wrapper borrows across cleanup

- **Status:** Accepted and implemented; extends D084 and D090/D091
- **Context:** Streaming wrappers retain caller-owned inputs and buffers. Treating
  every constructor argument as permanently escaped prevents deterministic
  reclamation after the wrapper dies; ignoring retention permits dangling input
  references. Fresh factory results also lacked entries in cleanup snapshots,
  allowing generated mutually exclusive finally copies to share mutated state.
- **Decision:** Record constructor borrow edges only for arguments retained solely
  in private, encapsulated fields. Reject input destruction while any edge remains;
  remove an edge when the retaining wrapper is freed, not closed. Propagate escape
  through dependencies, preserve edges across exception/branch snapshots, and
  propagate delegated constructor retention. Exposed fields, unresolved
  delegation, and unproven superconstructor arguments remain conservative.

  Join every closed-world target/overload for non-reference-returning borrowing
  calls before permitting retained storage to cross them. Primitive arraycopy
  cannot create reference aliases. Keep reference results under symbolic-return
  analysis. Register fresh factory allocations in the same ownership snapshot
  list as source new; preserve rejection of actual double free and unsafe loop
  re-entry. No runtime ownership bypass, automatic reclamation, or lifetime syntax
  is introduced.
  Conditional and short-circuit expressions use existing typed CFG ownership
  snapshots and joins instead of a blanket rejection of every live allocation.
  This preserves established factory-result cleanup while detecting aliases,
  conditional publication, incompatible identities, and partial frees. Factory
  results are included in snapshots rather than omitted from state tracking.

  Constructor argument publication is recorded before the invoke's exceptional
  ownership snapshot, including retention through an escaped receiver. A catch
  block cannot reclaim a published argument merely because construction threw.
  A rolled-back, non-publishing wrapper leaves no constructor borrow edge.

- **Consequences:** Source wrappers can be freed outside-in while their inputs
  remain caller-owned. Unknown or retaining stream overrides cannot invalidate
  destructor buffer safety. Regression tests cover accepted cleanup, escaped/live
  wrapper rejection, polymorphic retention, factory cleanup, allocation failure,
  and artifact reconstruction. All changes are original Ironwood implementation.

## D095 - Deliver U3 streaming CLI and deterministic stream resources

- **Status:** Accepted and implemented; supersedes D087's streaming deferral and
  D044's output-only standard-stream boundary, building on D094
- **Decision:** Add the Closeable byte/character stream hierarchy, file/buffered/
  in-memory implementations, incremental UTF-8 adapters, four Files stream
  factories, immortal System.in, and binary OutputStream-compatible PrintStream
  writes. Implement portable buffering, validation, and decoding in Ironwood;
  lower only private native descriptor operations through typed stream IR.
  The full API/provenance/member review precedes implementation in
  STDLIB_U3_SOURCE_REVIEW.md. No OpenJDK implementation source is imported.

  Ordinary wrappers borrow underlying objects and cascade close. Path factory
  constructors construct owned graphs, allocating buffers before acquiring the
  descriptor last. Free destroys managed state only; close releases descriptors
  only. Output close attempts the underlying close after flush failure; D051
  preserves ordered secondary failures. Double close is harmless. Standard close
  is input no-op/output flush, leaving each process-owned stream usable.

  Reads use unsigned bytes/UTF-16 units and -1 EOF (readLine null); empty reads
  return zero. UTF-8 adapters replace malformed input/unpaired output surrogates;
  Files buffered factories use strict UTF-8. Partial sequences/pairs cross calls.
  File opens throw FileNotFoundException with an owned message; I/O and closed
  state throw IOException. PrintStream retains its sticky error protocol.
  Flush does not fsync. PrintWriter, charset objects, mark/reset, random access,
  networking, U4/U5, and public floating formatting remain deferred.
- **Acceptance:** projects/streaming provides binary cat, incremental wc, binary
  cp including NUL/invalid UTF-8, and an interactive prompt. Files.isSameFile is
  included specifically to prevent cp from truncating a source inode alias.
  Tests cover native O0-O3, Java 21 shared behavior, allocation and OOM cleanup,
  descriptor stress and native I/O faults, class/archive/separate-link forms,
  and host/IDK packaging. Host verification does not substitute for the release
  platform matrix. Completing U3 does not authorize the next library tranche.


## D096 - Resolve borrowing calls with typed receiver flow

- **Status:** Accepted and implemented; supersedes D094's blanket target/overload
  join, preserving its constructor-borrow and cleanup rules
- **Context:** Joining every declared subtype and same-name/arity overload rejects
  safe buffer reclamation when an unrelated implementation retains its argument.
  Joining overridden interface defaults also disagrees with executable dispatch.
- **Decision:** Bind borrowing calls from provisional typed IR, using exact direct
  linkage or dispatch slots and the existing hierarchy/default resolver. Compute
  a monotone, context-insensitive receiver-type fixed point over allocations,
  immortal concrete storage, SSA conversions/phis, arguments, returns, and fields,
  including constructor delegation and exceptional flow. Join every possible
  target's receiver/reference-parameter escape facts for primitive/void calls.
  Primitive parameter origins do not carry reference ownership. Reference results
  continue through symbolic-return analysis.

  Every lowered body contributes, fields join all instances and generic shapes,
  and method inputs join callers. Array-element/native reference producers use
  all compatible concrete types; destructor receivers account for implicit native
  cleanup. Without an entry point, library reference inputs remain unknown.
  Empty receiver flow falls back to all type-compatible implementations and never
  grants a vacuous proof. These conservative joins can still reject safe programs.

  Recompute escape/owned-field summaries, then rerun final typed lowering and all
  diagnostics. Do not emit provisional reclamation decisions. Final linking
  repeats the proof after source reconstruction from `.ironclass`/`.ironjar`
  inputs; no artifact carries an irrevocable borrowing guarantee.
- **Acceptance:** Regressions cover unrelated live retaining classes, exact and
  generic overloads, inherited methods, generic interfaces, class/default
  precedence, arguments/factory results, local/field/conditional/exception flow,
  unknown library inputs, caught-exception receivers, and retaining replacements.
  A native fixture checks
  outside-in reclamation and live-allocation balance at O0-O3 through class/archive
  links; replacing the safe implementation at final link must be rejected.
  This is compiler proof precision, with no runtime bypass, GC, new borrowing
  syntax, or change to constructor publication and retained-borrow lifetimes.

## D097 - Add an application-driven compatibility slice

- **Status:** Accepted and implemented; supersedes D031's floating-builder
  deferral, D051's cause-chaining deferral, and D088/D095's blanket public
  floating-formatting deferral for the focused APIs below. D113 supersedes the
  `String.format` overloads and their restricted format-string contract.
- **Context:** Reviewing a complete low-latency limit order matching engine
  exposed a small set of Java library calls that were absent from Ironwood.
  Rewriting the application around different APIs would obscure the source
  comparison and change application logic. Importing broad collection or
  formatting frameworks would add scope without serving the application.
- **Decision:** Add original, application-driven Java-shaped APIs:
  `ArrayList.contains(E)` and `remove(E)`; a live read-only
  `Collections.unmodifiableList(ArrayList<E>)` view; ranged
  `StringBuilder.append(CharSequence, int, int)` and `append(double)`;
  `Integer.toHexString(int)`; `Math.subtractExact(long, long)`; immutable
  constructor-supplied `Throwable` causes; and fixed-arity
  `String.format(String, long)`/`String.format(String, double)` overloads for
  one `%d` or `%f` conversion with the documented bounded width and precision.
  These files are independently maintained under `MIT OR Apache-2.0`.

  Formatting returns one caller-owned String and reclaims temporary arrays and
  floating text on every path. The read-only list view borrows its backing list,
  owns one reusable non-reentrant iterator, and frees that iterator in its
  destructor; it never owns or destroys the list or its elements. A Throwable
  retains but does not own or destroy its cause. Application objects intended
  for reuse remain process-lifetime pool capacity and return to their owning
  pools on terminal paths. Source-written temporary builders and formatted
  Strings use compiler-checked `free` in `finally`.

  Keep general `Formatter` syntax, multiple arguments, flags, locale behavior,
  mutable cause initialization, and a general collections framework outside
  this decision. Preserve the representative application's production structure
  and control flow, with only mechanical closed-world adaptations for
  explicit builders, enum enumeration, entry-point status, and reclamation.
- **Acceptance:** Focused native checks cover lifecycle, traversal, validation,
  pooling, ownership, and every library API added by this decision. Application
  fixtures compile and link, produce their checked output, and preserve their
  capacity-stable allocation boundaries.


## D098 - Generate IronDocs Markdown from Javadoc-style source comments

- **Status:** Accepted and implemented initial subset; supersedes D066 and
  D067's blanket Feature 79 exclusion for the surface described here
- **Context:** The standard library needs source-maintained API documentation
  that can be browsed directly in a GitHub repository. Familiar Javadoc comment
  syntax and command options provide a migration path, while GitHub Markdown
  avoids a separate hosted documentation site.
- **Decision:** Add the `irondoc` bootstrap tool. Reuse the existing lexer and
  parser, retain documentation trivia only on request, associate comments with
  parsed declaration spans, and build a separate immutable documentation model.
  Generate deterministic Markdown indexes and declared-member reference pages
  with relative links, overload-specific anchors, and a local SVG banner.
  Ordinary compilation retains its existing tokens, AST, typed IR, ownership
  checks, and native pipeline. Documentation generation executes no source code
  and does not require LLVM.

  Preserve traditional `/** ... */` syntax and the supported Javadoc option/tag
  meanings listed in `IRONDOCS.md`. Unknown options and unsupported comment tags
  are errors. Check parameter names and selected-member links with source
  diagnostics before writing output. Do not overwrite hand-written files or
  symbolic-link output. Types outside the selected output remain textual
  references. This validation is deliberately smaller than DocLint and full
  semantic compilation.

  Implement independently from specifications under `MIT OR Apache-2.0`;
  no OpenJDK implementation bodies, comments, Javadocs, or tests are imported.
  Document `ironwood.pool.ArrayObjectPool` as the first complete authored page,
  preserving its existing allocation, builder, reuse, and non-owning contracts.
  GitHub controls Markdown styling; color comes from the bundled SVG rather
  than custom CSS or a deployment requirement.
- **Boundary:** Feature 79 becomes an implemented IronDocs subset (💡).
  `///` documentation, inherited/synthetic member documentation, package/module
  comments, remaining tags/options, arbitrary HTML, HTML output, DocLint,
  custom doclets, and release-package inclusion remain deferred. No later
  implementation target is selected and no existing semantic decision changes.
- **Verification:** Focused tests cover trivia retention, AST extraction,
  visibility, generics, overload links, malformed input, CLI selection and
  argument files, UTF-8, deterministic output, overwrite protection, the current
  standard-library declarations, and all generated Markdown links. The first
  reference is regenerated byte for byte in the primary suite, which also
  compiles and runs its embedded example at `-O3`. Run `./scripts/test.sh`,
  `./scripts/check-licenses.sh`, and `git diff --check` for the final gate.

## D099 - Version and preserve the standard-library IronDocs reference

- **Status:** Accepted; supersedes D098's single curated publication layout and
  routine full-suite verification requirement for documentation maintenance.
  D101 supersedes the default commit behavior and the prohibition on pushing.
- **Context:** Development source can differ from the latest published binary.
  Readers need an accurate version label and stable API references for past
  releases without a separate hosted site.
- **Decision:** `scripts/update-irondocs.sh` reads `VERSION` and generates all
  public and protected standard-library declarations into `docs/api/<version>`.
  The permanent `docs/api/README.md` indexes versions. The script commits only
  generated documentation, preserving unrelated staged and unstaged work. It
  never pushes, tags, publishes a release, or increments `VERSION`.

  Maintain one changing prerelease reference. An explicit `--release` with a
  stable `VERSION` creates an immutable snapshot and retires prereleases for
  that same release number. Include the documentation commit in the release tag.
  The next beta gets its own folder while past stable snapshots remain intact.
  Never label current source as an older release. The source fingerprint records
  the documented inputs without making output depend on timestamps or Git HEAD.

  `--no-commit` previews working source; normal committing requires clean source
  and generator inputs. `--check` verifies generated output without replacing it.
  An explicit `--doc-version` option labels a documented API independently of
  the tool version; the repository wrapper supplies `VERSION`. The banner uses
  the selected title and version rather than hard-coded library metadata.
- **Boundary:** This is repository documentation automation, not release or IDK
  packaging automation. It does not backfill older releases, author comments for
  undocumented declarations, or change language semantics or Javadoc tag meanings.
- **Verification:** `scripts/test-irondocs.sh` covers the documentation generator,
  deterministic versioned output, links, Git commit scope, no-op updates, failure
  preservation, and the beta-to-stable-to-next-beta lifecycle. Apply `AGENTS.md`'s
  proportional verification policy; this workflow does not require the full
  compiler or packaging suites.

## D100 - Release a stable source version and frozen API from synchronized main

- **Status:** Accepted; builds on D099's versioned documentation lifecycle
- **Context:** Maintainers need one manual release command that keeps the source
  version, API reference, release tag, and packaged version aligned.
- **Decision:** Add `scripts/release.sh <stable-version>`. Require a clean `main`
  exactly synchronized with `origin/main` before new release preparation. Reject
  mismatched development versions and existing release tags. Update `VERSION`
  and source-tree version prose, generate the stable IronDocs snapshot, and
  retire its prerelease reference. Commit those changes together before creating
  the annotated release tag. Push `main` and the tag atomically without forcing
  either remote ref.

  Provide a dry run, local-only preparation, and explicit resume. Preserve local
  preparation when a push fails; recognize already delivered refs after an
  uncertain result, and reject divergent main history or mismatched tags. Do not
  automatically merge, rebase, reset, move tags, or advance the next beta version.

  The GitHub workflow verifies tag/main ancestry, the exact source version, and
  matching frozen API metadata before creating its draft. Release notes link to
  the API at the release tag. Existing cross-platform build, package, uploaded
  archive verification, and final publication gates remain in place.
- **Boundary:** Frozen API generation is a repository convention, not a GitHub
  write-access restriction. Preserving the release tag preserves its reference
  even if the version folder on `main` is edited later. No repository permission
  rules are configured. IDK packaging commands and archive layout remain unchanged.
- **Verification:** Focused release integration tests use standalone local Git
  fixtures and bare remotes to verify publication refs, snapshot consistency,
  guards, no-op retry, failed/atomic pushes, remote races, and the CI tag check.
  Actual releases retain the hosted compiler and package smoke tests. Testing
  release orchestration does not itself publish a real release.

## D101 - Make IronDocs commits and branch publication explicit

- **Status:** Accepted; supersedes D099's default commit behavior and prohibition
  on pushing from the documentation update script
- **Context:** Maintainers need to preview documentation, commit it locally, or
  publish their latest source and API changes with one familiar command.
- **Decision:** `scripts/update-irondocs.sh` generates documentation without
  committing or pushing by default. `--commit` generates and commits only
  `docs/api`. `--commitpush` does the same and pushes the current branch to the
  same branch name on `origin`, including its earlier local source commits.
  The existing `--no-commit` spelling remains an alias for default generation.

  Require committed documentation inputs for either commit mode and preserve
  unrelated staged and unstaged work. Reject conflicting modes. Create no commit
  for identical output, but still push when requested so interrupted publication
  can be retried. Retain local commits if the push fails. Push no other branches
  or tags, never force a push, and do not merge remote history automatically.
- **Boundary:** D099's source version labels and frozen snapshot lifecycle remain
  unchanged. The script neither increments `VERSION` nor creates a release.
  D100's stable release command remains responsible for release commits and tags.
- **Verification:** Focused Git fixtures cover default generation, explicit
  commits, branch publication, commit scope, no-op retries, failed pushes,
  divergent history, and exclusion of unrelated branches and tags.


## D102 - Make object pools own their values across checkout

- **Status:** Superseded in part by D104; implementation scope narrowed by D103; supersedes the non-owning pool and
  `takeRetained` clauses of D029 and D083. Extends D084's dependent borrows to
  dynamically populated pools. Collection user-element ownership is unchanged.
- **Decision:** Remove `takeRetained()`. `get()` lends a pool-owned object.
  `release(object)` returns a checked-out object or transfers ownership of an
  independently owned external object on normal completion. Failure leaves the
  external object with its caller. Null and duplicate returns are rejected.
  The builder is borrowed and must return a fresh unescaped reference or null;
  the compiler validates every concrete factory and rejects receiver publication.

  A successful pool destruction destroys every owned object, including checked-out
  objects, and its private storage. Maintain a per-pool native identity table through
  typed ownership IR. Reserve capacity before construction, register without further
  allocation, and cancel reservations on exceptional exits. Existing-value reuse
  remains allocation-free. Construction rollback destroys installed registries.
  Ordinary object layouts and non-pool allocation semantics do not change.

  Track local ownership transfers, aliases, exceptional edges, and conflicting
  control-flow owners in typed analysis. Checkout provenance is distinct from a
  borrow of an owned child, preventing duplicate ownership of that child. Reject
  independent free of a pooled value and later access after owner destruction.
  Reject pool destruction if an escaped alias, uncertain dispatch, or ownership
  conflict prevents proof. Apply built-in contracts only to audited pool methods;
  polymorphic calls require every possible target to share the contract.
- **Limits:** Return does not reset objects. Callers must stop using an available
  value until checkout; static enforcement of checkout exclusivity is not added.
  Cross-pool transfer and unproved nested-pool returns are not supported. Some
  wrapper and polymorphic flows remain conservatively unreclaimable. Collection
  keys and values remain borrowed; this does not make arbitrary collections own
  user object graphs or reclaim unrelated allocations left behind by a factory.
- **Verification:** Focused alias/transfer diagnostics, native destruction and
  warm-reuse allocation checks for all five pools, and injected allocation failures
  across construction, growth, and transfer. Existing pool and owned-helper
  regressions are selected individually. No full development suite is required.


## D103 - Retain only the array and multi-array object pools

- **Status:** Accepted and implemented; supersedes D029's broader pool selection
  and narrows D102's implementation scope without changing its ownership contract.
- **Decision:** Keep `ArrayObjectPool` and `MultiArrayObjectPool` as the only
  standard-library pool implementations. Remove `StackObjectPool`, `LinkedObjectPool`,
  and `TieredObjectPool`, along with their unused private linked-list storage,
  node, and iterator helpers. Retain `ObjectPool`, `ObjectBuilder`, `ArraySizing`,
  `MultiArrayObjectPoolArrayHolder`, and `PoolOwnership`.
  Migrate generic, int, and long linked-list entry reuse to `MultiArrayObjectPool`
  so growth keeps existing pool arrays instead of replacing and copying them.
  Those collections preserve their public ordering, iteration, and allocation-free
  reuse contracts. Remove obsolete pool compiler contracts and regression fixtures.
- **Compatibility:** Code naming a removed pool must choose one of the two retained
  implementations. No aliases or deprecated implementations remain. Update current
  IronDocs and package checks; published version snapshots retain their old API.
- **Verification:** Focused native pool/list behavior, ownership, allocation,
  compiled/archive contents, and development IronDocs checks. No full development
  suite or hosted platform run is required.

## D104 - Record pool creations without runtime ownership policing

- **Status:** Accepted and implemented; supersedes D102's runtime registry,
  external ownership transfer, and duplicate-return rejection. D103's two-pool
  selection and conservative compiler lifetime protections remain.
- **Decision:** Each pool records every non-null builder result once in a growing
  creation array. Checkout and return of existing values do not touch that array,
  scan identities, or update checkout flags. Preserve the original argument and
  null validation. Return only a checkout from the same pool, at most once per
  checkout. These reuse obligations are documented, not dynamically policed.
  External objects are unsupported and never recorded or destroyed by the pool.
- **Destruction:** Destroy the recorded objects, including checked-out ones,
  then reclaim array containers. Never destroy objects by traversing availability
  slots. Duplicate returns can corrupt reuse but cannot add destruction records.
  Multi-array pools additionally record fresh segment holders; each holder frees
  its own segment array without destroying slot contents. Builders remain borrowed.
- **Failure and proof:** Reserve creation-array space before creating an object.
  Failed construction reclaims recorded objects and private storage. Require
  fresh unescaped factory results at compile time, rejecting cached/borrowed
  instances and publication. The compiler proves the bounded private creation-array
  cleanup loop and lowers it to an explicit typed-IR element-destruction operation.
  Ordinary array free stays shallow. Destructors still cannot allocate or let
  exceptions escape. Pool-dependent references cannot outlive pool destruction.
  The compiler conservatively bounds unsupported external returns too; this does
  not grant runtime ownership or promise later reclamation of such objects.
- **Cost:** One reference append per creation, geometric creation-array growth,
  and a linear destruction walk. No identity registry or runtime misuse checks
  during checkout/return. This is deliberate ownership storage, not hidden safety
  bookkeeping. There is no optional ownership mode in this change.
- **Verification:** Focused factory/alias rejection, creation-array proof,
  native destruction, duplicate and external return behavior, allocation-failure
  rollback, and allocation-free reuse coverage. No full suite during development.

## D105 - Destroy private data-structure pools with their containers

- **Status:** Accepted and implemented; the copied-key reclamation limitation is
  superseded by D106 and the monotonic insertion limit by D107. Supersedes the internal pooled-entry
  and retained-capacity lifetime clauses of D029 and D084. Extends D104's pool
  destruction contract to the containers that allocate those pools.
- **Decision:** The seven pooled maps and three linked lists retain their
  builders and concrete pools in private final fields. Destroy the reusable
  iterator first, shallow bucket storage where present, then the pool, then its
  builder. The pool destroys every recorded entry exactly once, including
  checked-out entries. Do not independently walk and destroy bucket or list
  nodes, and do not destroy caller-supplied keys, values, or elements.
  Existing set and hybrid-list destruction reaches the same cleanup chain.
- **Proof:** Extend the compiler's audited bundled-library ownership summaries
  to internal entry-pool calls and entry-accessor results. Node results are
  dependent borrows of the container; publication, independent node frees,
  and use after container destruction remain rejected. Preserve ordinary
  inserted-parameter escape effects. An internal builder may be retained only
  by the proved-owned sibling pool through an encapsulated constructor borrow.
  Require final fields, builder-before-pool layout for constructor rollback,
  and the explicit dependency-ordered destructor. These bounded contracts do
  not grant arbitrary user classes inferred ownership of pool-backed graphs.
- **Cost and limits:** No runtime ownership checks, identity scans, or reuse-path
  allocations are added. Container destruction now reaches the existing pool
  destruction walk. The copied-key objects and arrays inside `CharSequenceMap`
  and `ByteBufferMap` entries remain a separate leak requiring key-result lifetime
  tracking before reclamation. Inserted-object escape analysis remains monotonic.
- **Verification:** Native allocation balance for empty, populated, reused and
  cleared containers, caller-item survival, allocation-free warmed reuse,
  injected construction/growth failures, dependent-entry rejection, and negative
  builder containment/order checks. Focused existing pool, destructor, list,
  map and set regressions cover the affected behavior.

## D106 - Reclaim copied map keys through their owning entries

- **Status:** Accepted and implemented; completes the copied-key reclamation
  gap left by D105 without changing caller-item ownership or pool reuse rules.
- **Decision:** `CharSequenceMapEntry` owns its private final `StringBuilder`
  key, and `ByteBufferMapEntry` owns its private final allocated `ByteBuffer`
  key. Each entry destructor frees only that key. Key destruction frees its
  backing array, so normal pool destruction and failed-construction rollback
  reclaim the complete entry storage graph. Never free original caller keys
  or values, and do not free a key when its entry is returned for reuse.
- **Proof and API:** Final internal key accessors lend compiler-proven-owned
  fields. Iterator visits accept entries rather than arbitrary key objects.
  Extend the bundled-map result summaries so `getCurrIteratorKey()` returns a
  dependent borrow of the copied key tied to the map. Preserve the concrete key
  helper type through aliases, wrapper calls, and fluent returns. Reject an
  independent key free, an owner free after key publication, and key use after
  owner destruction. The public signatures remain unchanged. Reused entry keys
  remain mutable storage; an explicit String snapshot owns independent storage.
  Permit `ByteBuffer.allocate` in owned-field initialization only when its
  source-derived summary proves a fresh unescaped result. A cached or published
  factory result cannot grant field ownership.
- **Cost:** No runtime ownership checks, identity scans, or allocations during
  warmed reuse. Destruction follows the existing pool-to-entry chain and adds
  the missing key and backing-array reclamation.
- **Verification:** Native allocation balance for empty, populated, grown,
  reused and cleared copied-key maps; caller-key/value survival; independent
  String snapshots; injected construction and growth failures; key-loan rejection;
  and rejection of cached or published buffer factory results. Focused map and
  allocation regressions run without the full suite.

## D107 - Track releasable caller-item loans from local data structures

- **Status:** Accepted and implemented. Supersedes the monotonic insertion
  limitations of D084 and D105, while preserving non-owning container payloads,
  logical visibility boundaries, and dependent internal-helper borrows.
- **Decision:** For exact, known local allocations of bundled generic lists,
  maps and sets, record insertion as a loan from the container to each known
  caller allocation. Use the compiler's existing constructor-borrow graph;
  this is neither ownership transfer nor irreversible publication. Successful
  `clear()` and container destruction discharge that container's loans. The
  caller can then explicitly free an item only after all retaining containers
  and ordinary aliases permit it. Containers never destroy external elements,
  keys or values. Copied-key map inputs are observed only during copying when
  their key-reading callbacks prove non-retaining.
- **Proof:** Recognize the audited implementations on exact constructed types,
  not arbitrary subclasses or method names. Hash/equality callbacks must prove
  non-retention for every closed-world dispatch target. Insertion records its
  loan before the call's exceptional edge, since a call can partially mutate
  before throwing. Clear discharges loans only on its successful continuation.
  Branch and exception joins union possible loans and content exposure, so a
  clear on one path cannot hide retention on another. Escaping a container
  recursively escapes its borrowed items. Existing loop and alias checks remain.
- **Views:** Preserve a constructor loan from the fresh result of
  `Collections.unmodifiableList(list)` to `list`. Require the direct
  constructor-forwarding factory body, the source-proven fresh unescaped result,
  and the constructor's encapsulated receiver-only borrow. Install the loan
  after successful construction. Free all live views before freeing the backing
  list. View destruction reclaims its iterator, never the list or its items.
- **Limits:** Individual removal or replacement does not discharge item loans:
  another position or key may retain the same allocation. Discarded audited
  removal results allow eventual whole-container clear/destruction. Extracted
  references, iterator access, unknown calls and uncertain callbacks remain
  conservative, as do later insertions into a container whose contents have
  been exposed. `ArrayLinkedList.clear(boolean)` does not discharge loans because
  its unchecked package accessor can read inactive slots; destruction does.
  Unknown allocation identities, arbitrary factories and unaudited subclasses
  gain no new reclamation permission. Clearing never reverses real publication.
  [Container Removal and Caller-Item Loans](CONTAINER_REMOVAL_LOANS.md) records
  the detailed rationale and requirements for a possible future compile-time
  refinement without changing this decision.
- **Cost:** All new tracking runs in the compiler. No runtime scans, ownership
  registries, state checks, allocations, slot scrubbing or implicit item frees.
  Array-list clear remains constant-time and pooled entries retain their reuse
  behavior.
- **Verification:** Native explicit caller destruction and live-allocation
  balance across all fifteen reference-container families at O0 through O3;
  copied input keys; duplicates, shared items, branch joins, removed entries and
  multiple views; injected list-growth and view-construction failures; negative
  exposure, publication, callback, subclass, exceptional-path and live-loan
  cases; and the updated caller-reclamation example. Focused regression groups
  verify existing helper, pool, array and constructor ownership proofs.

## D108 - Keep native test support opt-in and explicitly registered

- **Status:** Superseded in part by D109, D110, and D127
- **Context:** The original pool and data-structure Java suites contain 449
  source test methods and use a narrow JUnit 4 subset: test markers, before-each
  setup, two parameterized runners, expected exceptions, assumptions, and basic
  assertions. Ironwood excludes general annotations, class literals, lambdas,
  runtime reflection, and runtime class discovery. Bundling test APIs into the
  production standard library would also expose support that applications do
  not need.
- **Decision:** Add an opt-in `ironwood.testing` source module outside
  `stdlib/src/main/ironwood`. `TestRunner` executes each registration
  immediately in deterministic source order and returns process status zero
  only when no case failed. `TestSuite` dispatches explicit integer case ids and
  offers before-each and after-each hooks. The runner catches skips, assertion
  failures, and unexpected `Throwable` values, reports stable messages, and
  continues.

  `Assertions` provides only the fixed-arity truth, falsehood, nullness,
  identity, equality, inequality, floating-delta, and failure overloads observed
  in the source-suite audit. `Assumptions` supplies the observed conditional
  skip. Exact expected exceptions remain ordinary typed `try` and `catch`
  control flow. Do not add an untyped helper that accepts any throwable.

  Do not add `@Test` as a decorative built-in directive. D045's exclusion of
  general annotations remains in force, and explicit registration already
  supplies the closed-world runner roots. A future compiler-owned directive
  must define useful compile-time registration semantics and supersede this
  decision before changing the language surface. No test-support type is
  bundled, reserved, or implicitly available to production source.
- **Provenance:** The framework is original Ironwood work under
  `MIT OR Apache-2.0`. Migrated pool and data-structure tests follow D029's
  direct original-author contribution and relicensing, use neutral Ironwood
  names, and are recorded in `docs/SOURCE_PROVENANCE.md`.
- **Verification:** Passing, skipped, intentionally failing, and unexpected
  throwable self-tests verify exact native output, continued execution, and
  statuses zero and one. The initial migration adds 24 pool cases and 41
  data-structure cases at `-O3`. Existing compiler fixtures retain ownership,
  destruction, allocation-failure, and negative-proof coverage.

## D109 - Ship testing as an optional standard-library module

- **Status:** Accepted and implemented; supersedes D108's source-placement and
  distribution boundary. D110 supersedes its retention of D108's explicit
  registration and no-`@Test` clauses. D127 supersedes its retention of D108's
  testing API only for optional message ordering and character equality.
- **Context:** D108 correctly kept test APIs out of the implicit production
  standard-library archive, but placed their sources in a repository-level
  support project that distributions did not ship. That made the framework
  available to Ironwood's own tests without making it available to Ironwood
  programmers. Testing is a supported standard-library facility even though it
  is not a production dependency.
- **Decision:** Maintain `ironwood.testing` under
  `stdlib/src/testing/ironwood` and build it as
  `ironwood-testing.ironjar`. Host packages and IDKs ship that archive and its
  sources alongside the implicit `ironwood-stdlib.ironjar` archive. Client test
  compilations and links select `ironwood-testing.ironjar` explicitly with
  `-cp`. The compiler does not discover it as an implicit standard-library
  root, so ordinary production compilations neither see nor reserve its types.

  Retain D108's lifecycle, assertion, assumption, reporting, and failure-status
  decisions. D110 subsequently replaces handwritten integer registration with
  compiler-generated registration. D127 subsequently standardizes optional
  messages in last position and adds dedicated character equality. Rename the
  repository suite command to `scripts/test-stdlib.sh`, because it verifies the
  standard library and its optional testing module.
- **Verification:** The build produces the optional archive. Framework and
  migrated standard-library suites compile and link against that archive. Host
  package and IDK smoke policies require the archive, sources, and client
  testing documentation, while focused native verification covers deterministic
  output and statuses.

## D110 - `@Test` is a compiler-owned TestSuite directive

- **Status:** Accepted and implemented. Supersedes D108 and D109 only for
  handwritten integer dispatch, explicit case registration, and the exclusion
  of a compiler-owned `@Test` directive. The optional testing archive boundary,
  lifecycle, reporting, assertion, assumption, and failure-status decisions
  remain in force. D127 supersedes that retention only for optional message
  ordering and dedicated character equality.
- **Context:** Ironwood programmers should be able to write native tests without
  repeating a `run(int)` switch and a `main(String[] args)` registration list.
  General annotations, runtime reflection, class loading, and discovery remain
  excluded. The compiler already owns the closed world and can generate the
  required calls without runtime machinery.
- **Decision:** Recognize the exact spelling `@Test` as a contextual built-in
  method directive. It is not an annotation, takes no arguments, creates no
  metadata, and does not reserve `Test` as an identifier elsewhere. Accept it
  only on a concrete, parameterless, non-generic instance method returning
  `void` and having a body. The declaring class must be a concrete, non-generic
  subclass of `ironwood.testing.TestSuite`, must be top-level or static, and
  must have a no-argument constructor. A class with marked methods cannot
  declare its own `run(int)` or `main(String[] args)`.

  During semantic member collection, generate a public dispatch method matching
  `void run(int) throws Throwable`, with a deterministic switch over marked
  methods in source declaration order. Generate a public static
  `main(String[] args)` that
  constructs one `TestRunner` and one suite instance, invokes the runner for
  each method using its exact source identifier and generated index, then
  returns `TestRunner.finish()`. Generated methods enter the existing typed IR,
  ownership analysis, tree shaking, and native pipeline as ordinary synthetic
  methods. No runtime registry, scan, lookup, reflection facility, or new C ABI
  is added.

  A marked method adds the canonical testing types to dependency discovery, but
  does not make them implicitly available. Client compilation and linking must
  still select `ironwood-testing.ironjar` with `-cp`, preserving D109's optional
  standard-library dependency boundary.
- **Consequences:** Test names now match method identifiers. One suite instance
  serves every marked method, with the generated integer index passed to
  `beforeEach(int)` and `afterEach(int)`. All repository framework, pool,
  data-structure, and destruction suites use the directive. General annotation
  syntax and processing remain excluded under D045.
- **Verification:** Parser coverage preserves contextual placement and rejects
  invalid directive forms. Semantic coverage verifies subtype and method rules,
  generated entry and dispatch IR, registration count, and declaration order.
  Focused native testing runs the framework fixtures plus 24 pool, 41
  data-structure, 10 pool-destruction, and 24 data-structure-destruction cases
  through the optional archive.

## D111 - Rendering consumers preserve source-object reclamation

- **Status:** Accepted and implemented. Complements D089's rendering-result
  protocol and supersedes D061 only for the lifetime of implicit object
  renderings used by concatenation.
- **Context:** Dynamic String concatenation copied each object operand's
  `toString()` result into the final exact-size String but retained a fresh
  result afterward. Separately, the context-independent summary of
  `PrintStream.print(Object)` and `println(Object)` treated their internal
  virtual `toString()` call as publishing every argument. The output operation
  therefore blocked a later `free` even when all possible rendering targets
  only observed their receiver. Blindly weakening either path would be unsound
  for borrowed rendering results and for overrides that genuinely publish
  `this`.
- **Decision:** Keep the D089 concrete-descriptor ownership bit and conditional
  release boundary. During concatenation lowering, retain each source object
  together with its implicit rendered String. After the final String has copied
  those contents, emit conditional release instructions in reverse conversion
  order. Protect each later conversion and the final concatenation with
  compiler-generated exceptional cleanup for all renderings already produced.
  A null source object makes the narrow release operation a no-op; borrowed,
  identity, unknown, and mixed-result renderings remain untouched by the
  descriptor check.

  Specialize the escape effect of the exact PrintStream Object overloads at
  each call site. Starting from the argument's static type, enumerate every
  possible closed-world `toString()` dispatch target. Treat the outer output
  call as borrowing its argument only when the target set is nonempty and no
  target has a non-return receiver escape. A rendering that merely returns
  borrowed text remains safe because PrintStream consumes it synchronously and
  the D089 protocol separately preserves its ownership. Missing summaries,
  unknown target sets, and any target that stores, throws, or otherwise
  publishes its receiver retain the conservative argument-escape result.
- **Consequences:** Object concatenation leaves only its required result live
  after successful rendering and does not strand earlier owned renderings when
  later work throws. `System.out.print(container)` and
  `System.out.println(container)` no longer prevent a subsequent proven-safe
  container `free`. Final and other closed-world classes receive the same
  treatment. An override that deliberately publishes `this` still produces the
  existing safe-`free` diagnostic. No syntax, annotation, runtime registry,
  collector, or general escape exemption is introduced.
- **Verification:** Focused typed-IR coverage checks normal and exceptional
  conditional-release paths. Native allocation-count coverage exercises fresh,
  borrowed, multiple, and null concatenation operands. PrintStream coverage
  compiles and runs set, map, fresh-result final-class, and borrowed-result
  final-class cases through both Object output methods, and negative coverage
  rejects both methods for a deliberately publishing `toString()` override.

## D112 - Declare data-structure generics as reference-only

- **Status:** Accepted and implemented.
- **Context:** An unbounded Ironwood type parameter accepts primitive arguments
  through native specialization. The generic `ironwood.ds` implementations are
  reference collections: they rely on null sentinels, reference arrays,
  reference conversions, identity operations, and virtual Object methods.
  Their public declarations nevertheless omitted bounds, so primitive uses were
  accepted initially and then failed during bundled standard-library analysis.
  `ArrayList<int>` could also expose two specialized `remove(int)` candidates.
- **Decision:** Declare `extends Object` on every generic key, element, and value
  parameter in the 16 public generic `ironwood.ds` types. Apply matching bounds
  to their package-private entries, builders, iterators, and nested helpers.
  Give `Collections.unmodifiableList` the same explicit reference bound.
  Keep the primitive-specific list, map, and set families unchanged.
- **Consequences:** Primitive arguments are rejected at the programmer's type or
  invocation site instead of reaching reference-only standard-library bodies.
  Existing reference instantiations retain the same API behavior and storage.
  The bounds accurately distinguish these collections from genuinely unbounded
  generic abstractions such as `Iterable<E>` and `Iterator<E>`.
- **Verification:** Focused compiler coverage names every public generic
  data-structure type, exercises primitive keys and values separately, and
  verifies that diagnostics contain the declared reference bounds without a
  bundled-source path, primitive-specialization failure, or `remove` ambiguity.

## D113 - Make the incomplete String formatting contract explicit

- **Status:** Accepted and implemented; supersedes D097's `String.format`
  overloads and restricted format-string parser only.
- **Context:** D097 supplied `String.format(String, long)` and
  `String.format(String, double)` for `%6d` and `%9.2f` application display
  fields.
  Ordinary invocation widening admitted Java calls such as
  `String.format("%d items", 5)`, but the parser required an entire single
  decimal or fixed-point field. Java-valid literal text, flags, and other
  conversions failed only when executed; a leading zero flag was even consumed
  as part of a space-padded width. The application comparison and its two
  formatting checks established application behavior and cleanup, not the
  public method's Java contract. These restrictions came from implementation
  scope, not closed-world compilation or explicit reclamation.
- **Decision:** Remove both `String.format` overloads and the parser. Keep the
  original independent numeric rendering algorithms as
  `String.formatDecimal(long value, int width)` and
  `String.formatFixed(double value, int width, int precision)`. Width is a
  nonnegative minimum with space padding and no truncation. Fixed precision is
  explicit and ranges from zero through nine; finite values retain bounded
  binary scaling and `Math.round`, including the existing long-based magnitude
  limit. Preserve negative zero and nonfinite text. These Ironwood-specific
  helpers make no Java Formatter rounding or format-language promise. Results
  remain caller-owned, and temporary storage is reclaimed as before.

  Migrate the motivating display calls to explicit width and precision. Do not
  leave a forwarding `format` alias that recreates the same trap. Ordinary
  missing-member analysis now rejects literal and dynamic `String.format` calls
  at the caller during compilation, without a compiler formatting special case
  or runtime pattern checks. Java `String.format` and `Formatter` remain
  deferred.

  Apply the behavioral contract review in `OPENJDK_PORTING.md` to all provenance
  categories. Fixed arity must not silently reduce accepted argument semantics.
  An incomplete Java-shaped member needs an enforced compile-time boundary,
  omission, or a distinct name. Independently authored Java comparisons must
  include ordinary and boundary inputs outside the motivating application.
- **Consequences:** This is an intentional source break and a repair to the
  compatibility boundary, not completion of Java formatting. Existing numeric
  display use has an explicit migration; general format strings do not yet have
  a compatible replacement. Choosing original code was not inherently wrong,
  but treating a small application slice as the general API contract was.
  Full formatting requires a separate grammar, conversion, rounding, failure,
  and ownership review, including suitable OpenJDK helpers case by case.
- **Verification:** The compilation regression covers all seven T1 examples,
  the former application floating field, the leading-zero case, and dynamic
  patterns with int, long, and double arguments. A native fixture compares bounded
  numeric displays with Java, checks range failures, and checks retained and
  total allocations plus caller reclamation. A migrated numeric-display fixture
  is compiled, linked, and compared with its checked Java output; focused
  library checks verify the numeric fields and cleanup.

## D114 - Throwable descriptions preserve Java text and native ownership

- **Status:** Accepted and implemented. Complements D089 and D111; D054's
  crash-report-only trace contract is unchanged.
- **Context:** Throwable omitted `toString()`, so ordinary exception printing,
  concatenation, and builder append inherited Object's identity description and
  lost the message. The native uncaught reporter separately read the stored
  message, hiding the omission from crash-output coverage. This was an inherited
  behavioral contract gap, not a native-model restriction. Reading only the
  message field in a new override would also miss Java's virtual message-getter
  semantics. See the [T3 review](STDLIB_THROWABLE_REVIEW.md).
- **Decision:** Add virtual `getLocalizedMessage()` delegating to `getMessage()`
  and override `Throwable.toString()`. Evaluate the localized getter once;
  produce the concrete qualified class name alone for null, or append `: ` and
  the message otherwise, including the separator for empty text. Preserve
  subclass overrides and getter exceptions. The description contains no cause
  or trace and is a fresh caller-owned String.

  Keep dispatch and cleanup in Ironwood source. A private typed allocation
  operation fills the exact-size result directly from the existing native type
  name and message, with catchable allocation failure and no scratch objects.
  A second descriptor ownership bit records whether the concrete localized
  getter returns fresh, unescaped text. For the inherited default localized
  getter, use the concrete `getMessage()` summary. A private release operation
  consults that bit after copying; source `finally` also releases an owned
  message when description allocation fails. Borrowed, retained, unknown, and
  mixed-result getter text remains untouched. A getter failure propagates
  without attempting description allocation.

  Give the exact description facade an audited receiver-borrowing contract only
  if every closed-world message getter lacks a non-return receiver escape.
  Publishing or unknown getters preserve conservative safe-`free` behavior.
  Reconstruct these proofs from source or library artifacts at final link.
  This is a narrow extension of the existing rendering-consumption protocol,
  with no runtime registry, misuse scan, or general escape exemption.
- **Consequences:** Familiar exception rendering now includes the message
  through all ordinary Object consumers. Independent implementation remains a
  reasonable choice for this small rule; omitting the inherited behavioral
  audit was the mistake. Add consumer and override checks to the required
  porting review instead of relying on uncaught reports or application-specific
  accessors. Public `printStackTrace` remains absent pending its own review,
  particularly D054's first-throw capture versus Java's construction-time trace.
  D121 subsequently implements that public trace contract.
- **Verification:** Java-output comparison and native `-O3` allocation checks
  cover direct descriptions, Object consumers, null/empty and Unicode messages,
  subtype names, getter overrides, fresh/borrowed/retained results, and caught
  exceptions. Focused tests also cover typed operations and descriptor proofs,
  getter failures, exhausted description allocation with message cleanup,
  destructor allocation rejection, and receiver publication rejection. Existing
  rendering-consumer and uncaught-trace regressions remain passing.

## D115 - ByteArrayOutputStream supplies UTF-8 text snapshots

- **Status:** Accepted and implemented. Supersedes the U3 review's deferral of
  the no-argument BAOS `toString()` override; complements D095, D089, and D111.
- **Context:** BAOS omitted its text override while StringWriter implemented
  one. Grouping BAOS text conversion with deferred charset support left the
  no-argument call valid through Object, silently returning identity text.
  Unlike an absent overload, this was an observable compatibility defect. The
  [T4 review](STDLIB_BYTE_STREAM_REVIEW.md) records the native reproduction and
  comparison with Java. Strict `Files.readString` decoding and the existing
  per-invalid-byte argv decoder have different malformed-input policies, so
  neither can supply BAOS's replacement contract unchanged.
- **Decision:** Override `ByteArrayOutputStream.toString()` to decode exactly
  its written prefix into one fresh caller-owned String, including for empty
  input. Ironwood's native text APIs use a fixed UTF-8 default. Preserve Java's
  UTF-8 replacement behavior, including truncated prefixes, invalid
  continuations, and surrogate encodings. Reset, growth, further writes, no-op
  close, and stream reclamation do not alter previously returned snapshots.
  Existing subclass dispatch remains ordinary virtual dispatch.

  A private source helper lowers to `IrStringFromUtf8Instruction`. The native
  implementation borrows the valid buffer/count pair, measures the UTF-16 and
  encoded lengths, then allocates and fills one exact-size String. It creates
  no byte copy, char array, wrapper, builder, or native heap scratch. Source
  encapsulation maintains the prefix bounds. Size or result allocation failure
  uses the existing catchable `OutOfMemoryError` path and leaves stream state
  unchanged. The fresh-result contract participates in standard safe-`free`,
  destructor-effect checks, and D089/D111 consumer cleanup without another
  ownership descriptor field or runtime misuse check.

  Keep charset-name, Charset, and deprecated high-byte overloads absent.
  Do not emulate configurable JVM default encodings. Preserve strict Files
  decoding and the existing argv/environment malformed-byte policy.
- **Consequences:** Captured output has the familiar text meaning through
  direct calls, printing, concatenation, and builder append. Independent
  implementation remains appropriate for the small facade and native result
  allocation. The mistake was deferring an override without accounting for the
  callable inherited behavior. Compare analogous types and malformed-input
  policies during the required behavioral review; the no-argument contract
  does not depend on implementing a general charset framework.
- **Verification:** A Java oracle covers 71,977 byte vectors, comparing UTF-16
  units and both lengths, with one-allocation and reclamation checks per call.
  Native `-O3` fixtures cover ordinary consumers, immutable snapshots, buffer
  reuse/growth, close, subclass overrides, and allocation failure. Typed tests
  verify fresh results, stream reclamation before the snapshot, allocating
  destructor rejection, and compilation rejection of the charset-name overload.
  Existing U3 ownership, stream cleanup, and Java-comparison tests pass.

## D116 - Preserve floating argument types across text overloads

- **Status:** Accepted and implemented. Complements D097's double append and
  supersedes the remaining D031 float-append deferral and D088/D095 public
  floating-formatting deferral only for the String valueOf overloads below.
- **Context:** `StringBuilder.append(float)` was missing, so float arguments
  widened to D097's double overload and rendered longer double expansions.
  Numeric widening preserved the value but selected a different text contract.
  Output and concatenation already supported float-specific spelling, while
  `String.valueOf` lacked both floating overloads. This was an incomplete
  overload-group review, not a compiler conversion bug or native-model limit.
  See the [T5 review](STDLIB_FLOATING_TEXT_REVIEW.md).
- **Decision:** Add `String.valueOf(float)` and `String.valueOf(double)`, each
  producing one fresh caller-owned String through the existing typed dynamic
  concatenation conversion. Preserve FLOAT versus DOUBLE payload kinds. Add
  `StringBuilder.append(float)` and have both floating append overloads use the
  matching valueOf, copy the text, reclaim it in source `finally`, and return
  the same builder. An explicit float-to-double cast still selects double
  spelling. Existing primitive overloads retain their selection and behavior.

  Extend the compiler-owned fresh-text result contract to the two new valueOf
  overloads. Reuse existing allocation failure, ownership, and cleanup paths;
  introduce no new runtime ABI or decimal algorithm. Within existing builder
  capacity, append retains no new allocation after its one temporary is freed.
  If conversion or subsequent growth fails, preserve the prior builder state
  and reclaim any completed temporary. General Formatter support and floating
  wrapper-class `toString` methods remain outside this decision.
- **Consequences:** Float append, primitive output, valueOf, and concatenation
  now preserve the same type-dependent text conversion. Choosing original code
  and reusing the existing converter remains appropriate. Choosing only the
  application-requested overload without auditing widening callers was the
  mistake. The required porting review now explicitly checks type-dependent
  formatting even when numeric widening is lossless.
- **Verification:** Typed tests preserve FLOAT/DOUBLE conversion kinds and
  reject accidental widening in the float append path. A native Java comparison
  covers 39 representative values across four output paths, plus mixed primitive
  chaining and allocation/reclamation checks. Allocation limits exercise both
  conversion failure and builder-growth failure for float and double. The
  existing floating concatenation regression remains passing.


## D117 - Everyday String APIs with fixed en_US text conventions

- **Status:** Accepted and implemented. Extends D031, D087/D088, D097 and D115.
  Supersedes any implication in earlier library plans that configurable Locale
  support or locale-dependent String casing must be implemented later.
- **Context:** Common String operations were missing. Implementing configurable
  locale state solely to expose familiar casing methods expanded the work beyond
  Ironwood's product priorities. The human selected fixed `en_US` behavior while
  retaining no-argument case conversion. Ironwood targets high-performance
  Java-shaped AOT applications and is not a full replacement for Java.
- **Decision:** Provide no `ironwood.util.Locale`, locale overloads, mutable
  default locale, or host-locale discovery. Locale support is excluded, with no
  future implementation commitment. Use fixed `en_US` conventions for supported
  locale-sensitive library behavior. Each future API still requires its own
  behavioral review. No-argument String case conversion matches Java 21 with
  `Locale.US`, including Unicode expansions and contextual Greek sigma, and
  ignores environment locale settings. `equalsIgnoreCase` retains Java's
  locale-independent simple comparison semantics.

  Add `trim`, `strip`, `isBlank`, both literal `replace` overloads, UTF-8
  `getBytes` and `String(byte[])`, char-array `valueOf` overloads, offset
  `lastIndexOf` and `startsWith`, `repeat`, explicit array joins, and joins
  with zero through three elements. Regex split, charset overloads, Iterable
  join and D047 varargs remain absent. Returned transformations and snapshots
  are fresh caller-owned allocations, including unchanged or empty results.
  UTF-8 replacement preserves D115 decoding and Java's `?` encoding of lone
  surrogates; Files decoding remains strict.

  Small facades and native allocation mechanisms are independently implemented.
  Fixed-convention Unicode casing uses a verified Classpath-covered OpenJDK
  helper with recorded source, generated data and complete notices. Retain
  dispatch entries only for slots used by reachable calls, with existing slot
  indices and direct-call/destructor/rollback roots preserved. This enables
  final linking to remove Unicode tables when no casing/comparison uses them.
  Typed IR
  carries allocation and failure effects. Direct immutable transformations
  require one result allocation; CharSequence renderings and join buffers are
  reclaimed using existing ownership descriptors and `finally`. Borrow proofs
  preserve real callback publication and add no runtime ownership tracking.
- **Consequences:** Java-shaped everyday calls work within explicit text
  conventions without introducing a locale subsystem. Fixed `en_US` and fresh
  ownership remain documented differences, rather than accidental approximations.
  The [String review](STDLIB_STRING_REVIEW.md) records provenance, scope,
  reproduction, allocation behavior and the lessons learned.
- **Verification:** Focused native comparisons cover ordinary/boundary calls,
  5,832 casing contexts, all Unicode code points, foreign locale environment
  variables, ownership, omission diagnostics, and injected allocation failures.

## D118 - Everyday StringBuilder editing and query APIs

- **Status:** Accepted and implemented. Extends D031, D087/D088, D097 and D116.
- **Context:** Common builder edits and queries were absent. As T5 demonstrated,
  an incomplete overload group can silently select an incompatible conversion;
  char arrays likewise need text overloads instead of inherited Object rendering.
- **Decision:** Add whole/ranged char-array append; all twelve Java-shaped
  insertion overloads for supported types; deletion, character mutation,
  replacement, surrogate-aware reversal, whole/offset substring search, both
  substring forms, and `isEmpty`. Keep D116 float append. Match Java's UTF-16,
  null, range, overload, self-insertion and callback-failure behavior. Immutable
  snapshots are fresh caller-owned Strings, including empty results.

  Independently implement these operations over the existing owned char array.
  Edits and queries avoid helper allocations within capacity. Growth replaces
  the owned backing array. Reuse existing integer and floating conversion and
  conditional Object-rendering cleanup; preserve borrowed/published renderings.
  Check possible Object callback targets before refining source borrowing.
  Retain literal receiver types in symbolic return analysis so source-derived
  fresh factories can be recognized without treating literals or published
  results as owned. No native ABI or runtime safety mechanism is added.
- **Consequences:** The requested builder surface works with Java semantics and
  Ironwood's established explicit ownership. This does not complete unrelated
  StringBuilder members or broaden CharSequence. The
  [StringBuilder review](STDLIB_STRINGBUILDER_REVIEW.md) records the boundary,
  provenance, allocation design and lessons. Existing agent rules suffice.
- **Verification:** Focused native differential checks, 55,987 surrogate cases,
  typed float selection, allocation counters, snapshot/source independence,
  negative alias/publication checks, published-result preservation and failure
  injection across thirteen operations. Existing builder, floating and Object
  rendering regressions remain covered by focused tests.

## D119 - Chainable StringBuilder setLength

- **Status:** Accepted and implemented at the human's explicit request.
  Extends D031 and D118; supersedes the previous void-returning
  `StringBuilder.setLength(int)` API. All length-change semantics remain in force.
- **Context:** Resetting and reusing a builder is a common operation. Returning
  its receiver enables `builder.setLength(0).append("Hi")` while retaining the
  behavior of ordinary statement-style calls.
- **Decision:** Change the return type to `StringBuilder` and return `this`
  after a successful length change. Keep truncation, zero filling, capacity
  growth, bounds errors and allocation failure unchanged. The result aliases
  the existing builder and conveys no new ownership. Use the existing compiler
  return-origin and safe-free proofs without special handling.
- **Consequences:** This is an intentional Java API extension. Chains using the
  returned value are Ironwood-only. Existing Ironwood overrides with a void
  return must migrate; none exist in the repository. Rebuild compiled consumers
  with the updated standard library. The change adds no helper allocation.
- **Verification:** Focused native checks cover chaining, same-instance return,
  ignored results, helper-returned aliases, reset/truncate/zero-fill/growth,
  allocation counts and final reclamation. Negative checks reject freeing a
  builder while its returned alias remains live and reject obsolete void
  overrides. Existing builder behavior and copied-key map reuse remain covered.

## D120 - Focused Instant values and ISO timestamps

- **Status:** Accepted and implemented. Advances the time portion of the
  standard-library roadmap; D113's general Formatter deferral remains in force.
- **Context:** Applications need epoch timestamps, logs and ISO interchange
  before a complete date/time or formatting framework. T1 demonstrated why
  narrowing a familiar method's accepted input grammar creates hidden traps.
- **Decision:** Add final `ironwood.time.Instant` with EPOCH/MIN/MAX, epoch
  factories and accessors, `now`, comparisons, equality/hash, ISO parse and
  toString, plus DateTimeException and DateTimeParseException. Match Java's
  full Instant range, normalization, overflow behavior, ISO offset/extended-year
  grammar, leap-second/end-of-day forms and canonical UTC output. `now()` uses
  the existing millisecond wall clock; arbitrary values retain nanoseconds.

  Keep the facade, parser and exceptions independently implemented. Adapt only
  the two calendar conversion algorithms from the pinned Classpath-covered
  OpenJDK LocalDate helper, retaining its complete Oracle and original JSR-310
  notices and recording the derived license separately. Use primitive state
  and existing typed text concatenation, without a new native ABI or framework.

  Factories return one fresh owned Instant even for epoch zero. Rendering
  returns one fresh String and participates in existing Object-consumer cleanup.
  Parse exceptions own copied diagnostic characters; `getParsedString` returns
  a fresh independent snapshot. Preserve callback publication when proving
  source borrowing. Existing exception/message/cause ownership rules apply.
- **Consequences:** Timestamp interchange works without Locale, date patterns,
  Temporal interfaces, Clock, Duration, arithmetic or named timezones. Omitted
  APIs fail during compilation. Fresh result identity and millisecond clock
  resolution are explicit conventions. The
  [Instant review](STDLIB_INSTANT_REVIEW.md) records exact scope, behavioral
  references, provenance, ownership and remaining limitations.
- **Verification:** Focused Java 21/native comparisons, every day in a Gregorian
  cycle, full-range samples, malformed-input mutations, exact allocation counts,
  mutable-source and snapshot lifetimes, Object consumers, omitted-member and
  callback-publication negatives, unused-code pruning and allocation-failure
  injection. No full-suite or multi-platform release claim is made.

## D121 - Construction-time Throwable traces and public printing

- **Status:** Accepted and implemented. Supersedes D054's first-throw capture,
  process-lifetime metadata storage and public trace API omission. Preserves
  D051 secondary semantics and D081 emergency occurrence reuse. D132 supersedes
  the inherited D054 source-frame instrumentation. Completes the trace follow-up
  left open by D114.
- **Context:** Java developers commonly print caught exceptions. Exposing the
  old crash reporter alone would misrepresent construction sites and provide
  no trace for never-thrown exceptions. The human accepts exception-path cost
  while requiring no added work on ordinary paths without exception construction
  or throwing.
- **Decision:** Ordinary Throwable constructors invoke virtual `fillInStackTrace`.
  Capture source frames at construction; preserve them on throw/rethrow; permit
  explicit refresh returning the receiver and stackless overrides. Add public
  stderr/PrintStream printing using virtual descriptions and causes, shared-tail
  compression and cycle handling. Keep cleanup failures labeled `Secondary:`.
  Compiler-generated ArithmeticException now has message `/ by zero`.

  Independently implement graph traversal using an owned existing ds.ArrayList.
  Add private typed capture/release/frame-print operations and immutable metadata
  for filtering Throwable constructor/fill frames only during capture. Preserve
  existing frame entry, line, return and unwind instrumentation. Store native
  metadata in a compiler-validated private Throwable slot; release it through
  the source destructor and failed-constructor rollback. Borrow message, cause
  and secondary objects. Retain callback-publication and returned-alias checks.

  Preserve bounded emergency traces and use writable storage for the immortal
  OutOfMemoryError. Failed capture marks the trace unavailable. Failed public
  printing falls back to a root-only raw description and stored frames after
  cleaning up completed traversal helpers; partial output may precede fallback.
- **Consequences:** Caught, preconstructed and never-thrown exceptions support
  familiar Java tracing. Exception construction itself now pays capture cost.
  No public frame arrays, PrintWriter overload, Java suppression, serialization,
  reflection or extra ordinary-path instrumentation is introduced. Rebuild
  bundled artifacts for the private layout change. The
  [trace review](STDLIB_STACK_TRACE_REVIEW.md) records ownership, emergency limits,
  provenance and the distinction between public printing and fatal reporting.
- **Verification:** Focused Java/native differential tests; source identity and
  artifact round trips; typed rollback release; callback/alias/destructor
  negatives; native allocation-failure injection and release accounting; managed
  printing failure injection; existing bounded emergency and secondary-order
  regressions. A representative hot loop has identical before/after optimized
  main IR. No full-suite, release-platform or general benchmark claim is made.

## D122 - Practical Java-shaped standard-library compatibility expansion

- **Status:** Accepted and implemented. Extends D071, D085 through D095, and
  D114 through D121. Supersedes D071's Character digit and broader Math
  deferrals, D121's public frame-array and mutable-cause omissions, and D057's
  lack of a shared enum base. Preserves the exclusions in D047 and the
  Stream-returning Files boundary in the standard-library roadmap.
- **Context:** Real Java-shaped applications and first-party data structures
  need a broader set of small core, utility, file, buffer, numeric, and text
  helpers. Adding isolated signatures without reviewing overload, ownership,
  failure, and alias behavior would create Java-valid traps. The requested set
  also contains APIs whose Java shape depends on excluded subsystems.
- **Decision:** Add the Character, numeric, Math, core-type, util, IO, NIO,
  path, and filesystem surface recorded in
  `docs/STDLIB_COMPATIBILITY_EXPANSION_REVIEW.md`. Match Java 21 behavior for
  admitted calls, including Unicode 15.0 classification, unsigned boundaries,
  floating bits/text, exact/floor arithmetic, null/range/exception precedence,
  seeded Random sequences, stream data encodings, buffer state, path syntax,
  no-replace file mutations, RandomAccessFile modes, Throwable cause rules, and
  source trace identity.

  Keep primitive helper classes non-boxing. Add Number as a non-boxing abstract
  base, Runnable as an ordinary interface, and Enum<E> solely as the compiler-
  assigned base of enum declarations. StringBuffer provides Java-shaped mutable
  text without synchronization because Ironwood has no thread model.

  Keep File deferred and Files.list absent because the latter requires the
  excluded Streams/lambda design. Adapt readAllLines to
  `ironwood.ds.ArrayList<String>` with its established non-owning element
  contract. Bound reference comparator sorting to Comparable elements so null
  comparators preserve natural ordering and unsupported element types fail at
  compile time. Keep RandomAccessFile channels/descriptors and deferred File
  overloads absent.

  Preserve typed IR and original native mechanisms. System properties return
  fresh values from a documented native/fixed subset; unknown and JVM-only keys
  return null. Public stack snapshots allocate one caller-owned array whose
  immutable elements and Strings are compiler-emitted process-lifetime objects.
  ByteBuffer array/slice aliases share storage; allocate-created backing remains
  process-scoped after alias-capable use, while wrapped arrays stay caller-owned.
  Add no runtime ownership registry, alias tracker, or caller-misuse scan.

  Independently implement the new source, algorithms, tests, compiler work and
  runtime mechanisms under `MIT OR Apache-2.0`. Extend the existing generated
  Unicode table for Character data while retaining its complete provenance and
  notices. Copy or adapt no OpenJDK implementation body, comment, Javadoc, test,
  algorithm, or distinctive structure.
- **Consequences:** Common Java-shaped applications gain the requested
  compatibility without importing a JVM, Java Collections facade, Streams,
  threads, reflection, or a broad legacy filesystem layer. Explicit ownership
  differs where Java relies on garbage collection: returned Strings/arrays are
  caller-owned, public StackTraceElement objects are immortal, successful
  readAllLines elements follow non-owning list semantics, and exposed
  ByteBuffer backing can remain process-scoped. The compatibility review is the
  authoritative detailed boundary.
- **Verification:** Focused Java 21 differential and native checks cover Unicode,
  radix/unsigned/floating/math edges, util null/range/reentrancy behavior,
  deterministic random sequences, stream/file/RandomAccessFile behavior,
  ByteBuffer state and aliasing, permission failures, allocation rollback,
  core types, properties/exit, causes, public traces, enum-base enforcement,
  license compliance and diff hygiene. No full-suite, release-platform, or
  general performance claim is made.

## D123 - Reclaim unexposed ByteBuffer backing storage

- **Status:** Accepted and implemented. Corrects D122's overly broad
  process-lifetime treatment and restores D106's copied-key reclamation rule.
- **Context:** D122 removed the ByteBuffer destructor to avoid invalidating
  storage published through `array()` or shared with `slice()`. That also leaked
  every unaliased allocate-created backing array, including private
  ByteBufferMap entry keys whose complete ownership was already proved by D106.
- **Decision:** Restore destruction of `ownedStorage`. A slice retains its source
  through the existing compiler-tracked constructor loan. Treat `array()` as
  publishing its receiver, so an owning buffer cannot be freed after exposing
  the backing array. Wrapped arrays remain caller-owned. Add no reference count,
  registry, scan, or other runtime ownership mechanism.
- **Consequences:** Ordinary unaliased buffers and private copied map keys again
  reclaim their backing arrays. Published arrays stay valid because the compiler
  rejects reclamation of their owning buffer. The conservative `array()` rule
  also keeps a wrapped buffer wrapper live after publication, even though its
  caller-owned backing array does not require that wrapper.
- **Verification:** Run the focused ByteBuffer safe-free check, copied-key native
  destruction and allocation-failure fixtures, the complete standard-library
  test command, and diff/license audits. No full compiler-suite claim is made.

## D124 - Close entry, enum, iterator, and stream compatibility gaps

- **Status:** Accepted and implemented. Supersedes D043's `int`-only entry
  return, D057's missing `values()` API, and U3's one-scalar generic
  InputStream boundary. Preserves D043's String-array arguments and native
  status extension, D057's immortal constants and allocation-free traversal,
  and D095's explicit stream ownership model.
- **Context:** Four documented differences made familiar Java source either
  fail to compile or behave differently without a closed-world requirement.
  The language already supports interface defaults, the compiler can express
  an owned array result, and the native wrapper can map a void source return to
  a C status. The Reader scalar bridge also needs a clear boundary between
  compatible callers and invalid subclass implementations.
- **Decision:** A selected executable entry method may return `int` or `void`
  while remaining `public static main(String[] args)`. An `int` result remains
  the native process status. Normal completion of a `void` entry returns status
  0, while `System.exit(int)` continues to terminate immediately with its
  supplied status. Parameterless and instance entry methods remain unsupported.

  Every enum additionally synthesizes `public static E[] values()`. Each call
  returns a fresh mutable caller-owned array in declaration order. The array is
  shallowly reclaimable; its enum-constant elements remain immortal. The
  allocation-free `valueCount()` and `valueAt(int)` helpers remain available.
  A source declaration cannot replace the reserved synthesized signature.

  `Iterator.remove()` becomes a default method that throws
  `UnsupportedOperationException`. Iterators with supported mutation continue
  to override it. No lambda, stream, or spliterator machinery is introduced.

  The generic `InputStream.read(byte[], int, int)` performs repeated scalar
  reads after normal null and range validation. Failure on the first scalar
  read propagates. An IOException after at least one byte returns the partial
  count, matching Java's base-class contract. No helper object or buffer is
  allocated by this path. The default cannot safely reclaim a throwable supplied
  by an arbitrary subclass, so that language object retains ordinary exception
  ownership and is not implicitly collected. `Reader.read()` retains its
  IOException guard when a subclass reports zero for a positive-length read,
  because such a result violates the subclass progress contract and would
  otherwise create an infinite non-progress path.
- **Consequences:** Classic Java void entry methods, enum `values()` calls,
  minimal Iterator implementations, and InputStream subclasses work with their
  familiar source contracts. Ironwood's explicit ownership remains visible at
  the enum array boundary, and allocation-free enum traversal remains available
  to callers that do not need a mutable snapshot.
- **Verification:** Focused semantic, artifact, typed-IR, and optimized native
  checks cover void-entry execution and invalid returns; fresh enum-array
  identity, declaration order, mutation isolation, and shallow reclamation;
  the Iterator throwing default; complete and partial InputStream fills; first
  read failure propagation; and the non-progressing Reader guard. License and
  diff audits cover the complete change. No full-suite or release-platform
  claim is made.

## D125 - Mechanically check the standard-library documentation inventory

- **Status:** Accepted and implemented. Extends D099 and D101's versioned
  IronDocs lifecycle.
- **Context:** The versioned IronDocs reference is generated from current
  source, but the source-file, documented-type, and per-package counts in
  `STDLIB.md` were maintained separately. Standard-library additions could
  therefore leave both that inventory and the current prerelease reference
  stale until a release check happened to detect the latter.
- **Decision:** Keep package descriptions in `STDLIB.md` hand-authored. During
  every generation or check, derive the `.iron` source-file count from the
  source tree and the documented type totals from the newly generated IronDocs
  package index. Reject any mismatch in the introductory totals or package
  rows before replacing checked-in output. Treat `STDLIB.md` as a documentation
  input that must be committed before a commit-mode generation. Regenerate the
  current prerelease reference after standard-library API changes.
- **Consequences:** The guide can still explain package purposes, while all of
  its numeric inventory fields now have the same mechanical freshness boundary
  as the versioned API reference. A standard-library addition must update the
  short inventory table before IronDocs generation can succeed.
- **Verification:** Focused lifecycle tests cover accepted matching inventory,
  rejection of a stale source count, commit-input handling, regeneration, and
  checked-in output consistency. Diff and license audits cover the change.

## D126 - Complete the common exception cause constructors

- **Status:** Accepted and implemented. Extends D122's initial cause-bearing
  constructor additions.
- **Context:** `Throwable`, `Exception`, and `RuntimeException` exposed both
  cause constructor forms, while several common sibling and descendant types
  exposed only message/cause or neither form. This left an internal API
  asymmetry with no native or ownership requirement behind it.
- **Decision:** Add `Type(Throwable)` to `Error`, `IOException`,
  `IllegalArgumentException`, `IllegalStateException`, and
  `UnsupportedOperationException`. Also add
  `UnsupportedOperationException(String, Throwable)`. Delegate all forms to the
  existing superclass constructors. Cause-only construction preserves the Java
  behavior of using the cause description as the message and null when the
  cause is null. Implement the additions independently under
  `MIT OR Apache-2.0`.
- **Consequences:** The common cause-bearing exception types now share the
  familiar no-argument, message, cause, and message/cause constructor family.
  No suppressed-exception, serialization, finalization, or protected
  four-argument Throwable surface is added.
- **Verification:** A focused optimized-native fixture constructs every added
  form, checks cause and message behavior including a null cause, and proves
  that unobserved wrappers reclaim their cause-derived message storage. The
  standard-library license and generated-documentation checks cover the source
  and API update.

## D127 - Use JUnit Jupiter message ordering in the testing API

- **Status:** Accepted and implemented. Supersedes D108, D109, and D110 only
  for the argument order of optional assertion and assumption messages, and
  extends D108's audit-derived assertion surface with character equality.
- **Context:** The first migrated suites used JUnit 4 and placed optional
  messages first. Ironwood's durable testing API instead uses the JUnit Jupiter
  names `Assertions` and `Assumptions`, together with `@Test` and before-each
  and after-each terminology. Keeping JUnit 4 argument order behind those names
  would create a permanent mixed convention for new Ironwood tests.
- **Decision:** Put every optional assertion and assumption message last. The
  canonical forms are `assertTrue(condition, message)`,
  `assertEquals(expected, actual, message)`,
  `assertEquals(expected, actual, delta, message)`, and
  `assumeTrue(condition, message)`, with the same ordering applied across the
  remaining truth, nullness, identity, equality, and inequality overloads.
  `fail(message)` is unchanged.

  Do not provide message-first compatibility overloads. Reference equality
  calls would be ambiguous when several arguments are strings or other
  references. Calls with three string arguments have the same static signature
  under either convention and therefore require explicit source migration.

  Add exact `assertEquals(char, char)` and
  `assertEquals(char, char, String)` overloads so character comparison and
  failure text do not route through integer widening. Keep `assertThrows`
  absent while Ironwood has no class-literal and lambda mechanism capable of
  preserving its typed contract. Keep `assertArrayEquals` outside this focused
  convention change until an array overload family is justified independently.
- **Consequences:** New tests use one internally consistent JUnit Jupiter-shaped
  convention. Existing message-first source is intentionally incompatible and
  must migrate atomically with the API. The testing module remains optional,
  compiler registration remains closed-world, and no reflection, runtime
  discovery, allocation, or native mechanism is added.
- **Verification:** Framework self-tests compile and execute every message-last
  overload plus both character equality forms. A negative fixture rejects
  representative message-first truth, integer equality, and assumption calls.
  All repository users of `ironwood.testing` are migrated in the same change,
  and the focused standard-library suite preserves deterministic output and
  statuses.

## D128 - Complete direct collection value-containment queries

- **Status:** Accepted and implemented. Supersedes U4's standalone CSV/record-
  summary acceptance gate while preserving the application-driven collection
  policy.
- **Context:** The `ironwood.ds` families already provide their foundational
  storage, mutation, lookup, iteration, equality, hashing, and reclamation
  behavior. Value containment remained inconsistent: generic `ArrayList` and
  every set exposed it, while the other public list variants and every map did
  not. A CSV-specific application would exercise only an arbitrary subset of
  these types and would not establish broader collection completeness.
- **Decision:** Add `contains` to `ArrayLinkedList`, `LinkedList`,
  `IntArrayList`, `LongArrayList`, `IntLinkedList`, and `LongLinkedList`. Add
  `containsValue` to `HashMap`, `IdentityHashMap`, `LinkedHashMap`, `IntMap`, `LongMap`,
  `ByteMap`, `CharMap`, `CharSequenceMap`, and `ByteBufferMap`.

  Use a direct linear scan of each container's private live storage. This
  follows the Java 21 ArrayList, LinkedList, and HashMap complexity model
  without adding a reverse index, secondary storage, or mutation overhead.
  Primitive lists use exact primitive equality. As in Java's ordinary
  collections, reference lists and map values invoke `equals` on the query
  object for each stored reference. Null queries return false because the
  containers store no null elements or values. Align the pre-existing
  `ArrayList.contains` with that direction as part of this consistency change.
  `IdentityHashMap` retains its established Ironwood rule: keys use allocation
  identity while values use ordinary equality. This differs deliberately from
  Java's `IdentityHashMap`, which applies identity to values as well.

  The container implementation allocates nothing, mutates nothing, stores no
  query reference, and does not reset or consume reusable iterators. A
  user-defined `equals` method can still perform arbitrary work. Do not add
  `getOrDefault`, `putIfAbsent`, bulk operations, sorting, or a Java Collections
  facade as part of this change. Future `ironwood.ds` additions remain driven
  by demonstrated application or porting needs rather than a standalone U4
  gate.
- **Provenance:** The Java 21 implementations at OpenJDK jdk21u revision
  `060c4f7589e7f13febd402f4dac3320f4c032b08` were consulted only for public
  behavior and complexity. The Ironwood methods and tests are independently
  implemented under `MIT OR Apache-2.0`; no OpenJDK implementation body,
  comment, Javadoc, test, or distinctive structure is copied or adapted.
- **Consequences:** Membership queries are coherent across public list and map
  variants without changing insertion cost, storage size, ownership, or
  iterator state. U4 no longer blocks release readiness, while focused
  collection development remains active.
- **Verification:** Focused standard-library tests cover empty, present,
  missing, null, asymmetric-equality, primitive-boundary, fixed-array,
  linked-overflow, copied-key, and every map-family path. Iterator-position and
  allocation-count checks enforce non-interference and zero helper allocation
  with non-allocating equality. License, generated API, and diff checks cover
  the complete change.

## D129 - Complete basic indexed array-list operations

- **Status:** Accepted and implemented. Extends D128's continuous,
  application-driven collection policy without reinstating U4 as a standalone
  application gate.
- **Context:** The generic, `int`, and `long` array lists support indexed access,
  insertion, and removal but cannot replace an element in place or report the
  first and last positions of duplicate values. Callers can reproduce the
  searches with loops and replacement with remove plus insert, but that is
  boilerplate and turns constant-time replacement into two linear shifts.
  Capacity and bulk-copy needs are different: constructors already select
  initial capacity and growth, maps select capacity and load factor, and
  `System.arraycopy` plus `Arrays.copyOf` provide the foundational array
  operations.
- **Decision:** Add Java-shaped `set`, `indexOf`, and `lastIndexOf` to
  `ArrayList`, `IntArrayList`, and `LongArrayList`. `set` validates the index,
  replaces exactly one live slot, returns the previous value, preserves size,
  and does not reset the reusable iterator. Generic `set` then rejects null in
  accordance with the existing no-null element contract. Primitive variants
  use exact equality.

  Generic index queries scan forward or backward, invoke `equals` on the query
  object, and return `-1` for a missing or null query. Keep the established
  strongly typed `E` query boundary instead of Java's broader `Object`
  parameter; an incompatible query is rejected by overload resolution. Make
  `contains` delegate to `indexOf`, and make value removal use the same query
  direction. `UnmodifiableList` delegates both index queries and exposes `set`
  only as a mutation that raises `UnsupportedOperationException`.

  Classify the new reference passed to an exact bundled `ArrayList.set` as a
  retained caller-item loan. Preserve D107's conservative rule: replacement
  does not prove that the old element's loan ended, even when `set` returns it;
  successful `clear` or list destruction releases all list loans. Calls through
  subclasses or exposed containers remain conservative. The method bodies add
  no allocation; user-defined equality can still perform arbitrary work.

  Do not add `ensureCapacity`, `trimToSize`, `addAll`, `toArray`, collection copy
  constructors, list sorting, or map/set ordering in this change. These remain
  demand-driven conveniences rather than release requirements.
- **Provenance:** Java 21 public contracts guide the API names, return values,
  search direction, and index behavior. The Ironwood source, compiler
  classification, and tests are independently implemented under
  `MIT OR Apache-2.0`; no OpenJDK implementation body, comment, Javadoc, test,
  or distinctive structure is copied or adapted.
- **Consequences:** The array-list family now provides the basic indexed
  mutation and duplicate-aware lookup expected of a practical sequence without
  expanding into a Java Collections facade. U4 remains superseded and no
  longer blocks movement to U5. Capacity tuning and collection bulk operations
  can be selected later by a concrete consumer.
- **Verification:** Focused standard-library tests cover replacement return
  values, duplicate and missing indexes, null behavior, primitive extremes,
  invalid-index precedence, iterator position, asymmetric equality, the live
  read-only view, rejected view mutation, and zero helper allocation. Compiler
  tests reject freeing either a live replacement loan or an individually
  replaced old loan, and accept reclamation after `clear`. Generated API,
  license, and diff checks cover the complete change.

## D130 - Establish the U5 directory and attribute foundation

- **Status:** Accepted and implemented as U5 stage 1.
- **Context:** Whole-file operations can act only on paths already known to an
  application. Native tools need safe directory enumeration and basic metadata
  before they can discover source trees, assets, packages, or recursive work.
  Java's general `readAttributes` signature depends on reflection and varargs,
  and Java's stream-returning directory APIs depend on a deliberately absent
  Streams/lambda subsystem.
- **Decision:** Add `DirectoryStream<Path>`, `Files.newDirectoryStream(Path)`,
  `BasicFileAttributes`, `FileTime`, `Files.readAttributes(Path)`, and
  `Files.isSymbolicLink(Path)`. The stream is closeable, admits one ordinary
  iterator, excludes `.` and `..`, and maps iteration failure and use after close
  to `DirectoryIteratorException` and `ClosedDirectoryStreamException`.

  Add `hasNext()` and `nextEntry()` directly to the directory contract as an
  ownership-aware Ironwood complement to `Iterator<Path>`. Lookahead allocates
  no managed object. `nextEntry()` creates one caller-owned `Path`, allowing the
  compiler to prove its explicit reclamation. Closing is idempotent at source,
  invalidates the handle before the native close attempt, and remains separate
  from freeing the managed wrapper. Destruction does not silently close an
  external resource.

  Use the fixed `readAttributes(Path)` overload because reflective class tokens
  and varargs options are absent. It follows symbolic links. The public
  `isSymbolicLink` query uses an internal no-follow read and returns false on
  ordinary lookup failure, matching Java's query shape. `FileTime` is a
  millisecond value with conversion, comparison, equality, and hashing. Each
  time getter returns a fresh caller-owned value. `fileKey()` returns null, as
  Java permits. Linux creation time is epoch zero when unavailable.

  Lower directory open, allocation-free lookahead, entry conversion, close, and
  attribute reads through typed file IR and the isolated POSIX C runtime. Keep
  strict UTF-8 path behavior, categorized failures, bounded stack path encoding,
  and closed-world pruning. The native handle owns only its directory cursor
  and required lookahead state.
- **Provenance:** This is independently implemented Java-compatible Ironwood
  source under `MIT OR Apache-2.0`. Java 21 public contracts guide the selected
  names and behavior. No OpenJDK implementation body, comment, Javadoc, test,
  algorithm, or distinctive structure is copied or adapted. The full review is
  in `STDLIB_U5_SOURCE_REVIEW.md`.
- **Consequences:** Ironwood applications can discover directory contents and
  inspect common metadata without a JVM, garbage collector, Streams subsystem,
  provider registry, eager array replacement, or hidden resource cleanup.
  Recursive visitor traversal remains U5 stage 2.
- **Verification:** Focused typed-IR/LLVM tests cover all five native operations.
  A native O3 fixture covers regular files, directories, followed and
  non-followed symlinks, file size, timestamps, enumeration, exhaustion,
  idempotent close, use after close, missing directories, caller-owned entry
  reclamation, and wrapper reclamation. Generated API, license, and diff checks
  cover the complete stage.

## D131 - Complete U5 with controlled recursive file-tree traversal

- **Status:** Accepted and implemented as U5 stage 2.
- **Context:** D130 lets applications enumerate one directory safely, but every
  recursive tool would otherwise have to reproduce directory-handle cleanup,
  error callbacks, depth limits, symlink policy, cycle detection, and traversal
  control. Those rules are easy to get subtly wrong and are foundational for
  native search, packaging, synchronization, and asset tooling. Java's general
  overload uses `Set<FileVisitOption>`, while Ironwood deliberately has no Java
  Collections facade.
- **Decision:** Add `FileVisitResult`, `FileVisitor<T>`,
  `SimpleFileVisitor<T>`, `FileSystemLoopException`, and
  `Files.walkFileTree(Path, FileVisitor<Path>)`. The Java-shaped two-argument
  form performs a depth-first walk without following symbolic links. Add the
  fixed-arity Ironwood overload
  `walkFileTree(Path, int, boolean, FileVisitor<Path>)` for maximum depth and
  explicit link following instead of accepting an incomplete Java option-set
  signature.

  Open each directory before `preVisitDirectory`, close it before
  `postVisitDirectory`, and close every active stream when a callback throws or
  terminates traversal. Honor `CONTINUE`, `TERMINATE`, `SKIP_SUBTREE`, and
  `SKIP_SIBLINGS` at the Java callback boundaries. Treat a directory at maximum
  depth as a file visit. Reject null callback results.

  In follow-link mode, compare each candidate directory by filesystem identity
  with the lexical ancestors inside the selected start tree. Deliver a match to
  `visitFileFailed` as `FileSystemLoopException`. Perform this only when links
  are followed and add no runtime visited registry, identity hash table, or
  permanent traversal state.

  Traversal owns temporary entry paths and attribute objects. Treat them as
  callback borrows and require closed-world semantic analysis to reject a walk
  when any reachable visitor implementation can retain either parameter. Exact
  private cleanup intrinsics then reclaim paths, attributes, and closed stream
  wrappers after callbacks return. Keep failure exceptions under the ordinary
  Ironwood exception lifetime rather than adding runtime ownership machinery.
- **Provenance:** The API behavior is guided by Java 21 public contracts. The
  Ironwood source, compiler analysis, tests, and example are independently
  implemented under `MIT OR Apache-2.0`. No OpenJDK implementation body,
  comment, Javadoc, test, algorithm, or distinctive internal structure is
  copied or adapted. The full review is in
  `STDLIB_U5_SOURCE_REVIEW.md`.
- **Consequences:** Ironwood now has a reusable recursive traversal layer for
  filesystem tools without Java Streams, lambdas, a provider registry, or a
  duplicate Collections Framework. The fixed overload makes the absent option
  set explicit. Follow-link cycle checks trade an additional identity query per
  ancestor for bounded state and deterministic behavior. U5 is complete and no
  longer blocks an initial filesystem-tooling release.
- **Verification:** Focused compiler tests accept non-retaining visitors,
  reject retained callback paths, and inspect exact cleanup frees. A native O3
  fixture covers no-follow and follow-link traversal, a real ancestor symlink
  cycle, maximum depth, all four traversal results, null-result rejection,
  abrupt cleanup, post-visit placement, and allocation-neutral successful
  recursion. The `examples/filetree` compile, link, and run path demonstrates a
  suffix search. Generated API, license, and diff checks cover the complete
  stage.

## D132 - Stack traces use on-demand native decoding without runtime bookkeeping

- **Status:** Accepted and implemented. Supersedes D054's continuously updated
  shadow frames and D121's preservation of that instrumentation. Preserves
  D054 source identity, D121 construction-time capture and refresh behavior,
  D122 public immutable frame snapshots, D051 secondary semantics and D081
  emergency occurrence reuse.
- **Context:** Updating a runtime-private frame on every function entry, call,
  return and unwind imposes work on all code, including programs that never
  construct an exception. That conflicts with Ironwood's performance priority.
  Removing stack traces is not acceptable for production diagnostics. Native
  unwind alone also loses source frames after LLVM inlining, so a useful design
  needs optimizer-aware metadata without hot-path instructions.
- **Decision:** Emit LLVM pseudo probes for retained Ironwood function entries,
  call sites and catchable failure sites, with source locations that preserve
  inline context. LLVM carries those probes through the selected optimization
  pipeline and lowers them to read-only binary metadata. Pseudo probes emit no
  executable instructions. After `opt`, the compiler adds immutable source-site
  and surviving native-function address tables, reassembles the module, and
  continues through `llc`. The generated native entry registers those table
  addresses once before source execution.

  A Throwable capture invokes `_Unwind_Backtrace`, resolves physical return
  addresses against the finalized function table, and decodes pseudo-probe
  inline trees into the original leaf-to-caller Ironwood sequence. Constructor,
  `fillInStackTrace` and private capture frames are filtered only during that
  decoding. LLVM tail-call elimination is disabled for retained Ironwood source
  functions because immutable metadata cannot reconstruct a dynamic frame that
  was removed. Other optimizations remain available.

  Native frame lookup matches `_Unwind_GetRegionStart` against the registered
  function addresses. It does not infer function ownership from address order
  or a final code marker: linkers can move cold functions past that marker and
  interleave generated functions with runtime code.

  Mach-O uses LLVM's mapped `__PSEUDO_PROBE,__probes` section. On Linux,
  `llvm-objcopy` renames and maps `.pseudo_probe` as `ironwood_trace` before the
  final link, allowing the runtime to use linker-provided section bounds.
  Mach-O's assembler-symbol prefix receives an address-table GUID alias. No
  runtime symbolizer, debug-file lookup, frame registry, per-call TLS access or
  mutable trace state is added to ordinary execution.

  Ordinary capture may allocate runtime-private PC and decoded-frame storage.
  The reusable OutOfMemoryError path instead uses bounded stack/static storage,
  performs no allocation, and retains the existing truncation marker. Capture
  or metadata failure preserves exception delivery and reports `<trace
  unavailable>`.
- **Consequences:** Ironwood has one performance behavior rather than separate
  trace modes. Normal code executes zero trace bookkeeping instructions, while
  exception construction or explicit refresh pays native unwind and
  metadata-decoding cost. Preserving every dynamic source frame gives up LLVM
  tail-call elimination, including for recursive tail calls. The deterministic
  order-book benchmark shows no measurable cost from pseudo probes or that
  restriction, but tail-call-heavy programs can retain real call frames that an
  otherwise equivalent native build could remove. Executables retain read-only
  trace metadata and immutable public frame objects, increasing binary size.
  LLVM 23 installations and packaged IDKs must include `llvm-objcopy`. Exact
  source traces remain available at `-O0` through `-O3` and through inlining.
- **Verification:** Focused native tests cover exact uncaught, caught, refreshed,
  rethrown, constructor, public, secondary, emergency and allocation-failure
  traces. Trace output is identical at `-O0` through `-O3` in the focused
  fixture and on macOS ARM64, Linux ARM64 and Linux x86-64. LLVM assertions
  reject the old shadow-stack ABI and require pseudo-probe metadata.
  Machine-code inspection finds none of the former trace entry, line, unwind or
  leave calls. A deterministic order-book workload verifies the recovered
  hot-path throughput.

## D133 - Type initialization uses an inline fast barrier and outlined slow path

- **Status:** Accepted and implemented. Refines D055 native lowering without
  changing initialization timing, ordering, reentrancy or failure behavior.
- **Context:** D055 lowered every active-use barrier as a call to the complete
  initialization state machine. Once a type was initialized, that call returned
  immediately, but hot code still paid a native call and return for every static
  method, allocation or non-constant static field access. The deterministic
  order-book benchmark performs many enum constant accesses and exposed those
  calls in its measured loop.
- **Decision:** Lower each retained type to two internal routines. The active-use
  routine is mandatory-inline and contains only one private state load, an
  initialized-state comparison and a branch. The branch carries an LLVM
  expectation that state 2 is the common case, which changes code layout but
  emits no extra steady-state machine instruction. State 2 continues
  immediately. States 0, 1 and 3 call a shared non-inlined slow routine, passing
  the state observed by the barrier. No source operation occurs between that
  load and the slow call, and threads remain outside Ironwood, so the value
  cannot change in between.

  The slow routine preserves the D055 state machine exactly. State 0 changes to
  initializing, runs ordered prerequisites and `<clinit>`, then changes to
  initialized. State 1 returns for reentrant active use. State 3 rethrows the
  stored failure object. A new failure stores the same object before rethrowing.
  The slow routine retains synthetic pseudo-probe metadata so on-demand stack
  traces preserve exact `<clinit>` and source-caller frames.

  The compiler does not hoist a barrier above its source active use and does not
  eagerly initialize a type. Those transformations could change whether or when
  initialization side effects occur. LLVM may optimize the inlined state load
  and branch using its ordinary whole-program pipeline.
- **Consequences:** The initialized path has no native initialization-routine
  call, allocation, TLS access, registry lookup or synchronization. First use
  pays one additional call from the inline barrier to the shared slow path, an
  insignificant one-time cost compared with running initialization itself. The
  outlined state machine avoids duplicating initialization and exception code at
  every active-use site. The order-book benchmark improves while preserving
  initialization behavior at every optimization level.
- **Verification:** Typed-IR and LLVM assertions require the inline barrier and
  outlined state machine. Focused native tests cover source, class-directory and
  archive reconstruction, superclass and default-interface order, reentrant
  cycles, stored failures, exact failure traces and enum initialization. Native
  disassembly confirms that `-O3` removes the fast-path routine calls from the
  measured order-book loop.

## D134 - Cold failure paths are outlined and small dispatch sets are guarded

- **Context:** The deterministic order-book benchmark ran about 18 percent
  slower than the equivalent Java program on macOS and Linux. Profiles showed
  the time spread across many tiny methods that stayed out of line: list
  access, pool reuse, builder capacity checks, reentrancy checks and listener
  callbacks. LLVM's inline cost for each of them was dominated by implicit
  failure paths (null, bounds, length and division checks) and explicit
  `throw new X(...)` statements, each of which allocated, constructed and threw
  inline. A JIT treats those paths as uncommon traps; LLVM counted them at full
  cost and refused to inline the surrounding method. Interface callbacks with
  two implementations also stayed as table dispatch, which LLVM cannot inline,
  and `String.charAt` called the C runtime for every character.
- **Decision:** Keep the typed IR unchanged and improve the LLVM emitter and
  pipeline. The emitter outlines each failure sequence into one shared
  `cold noinline noreturn` helper per constructor, called with `nomerge` so
  distinct throw sites keep their probes; the helper carries no probes, so
  on-demand traces skip its frame and report the caller's throw site. For each
  dispatch slot the emitter collects the instantiable receivers of the pruned
  closed world; a slot with at most four receivers and three targets lowers to
  descriptor comparisons with direct calls and the table dispatch as fallback.
  The receiver parameter is `nonnull noundef`, the allocators are
  `noalias nonnull`, `ironwood_throw` is `noreturn cold`, and `charAt` loads the
  UTF-16 unit from the string layout. `-O3` passes `-inline-threshold=1000`
  and `-enable-partial-inlining` to `opt`; `-O2` keeps LLVM's defaults.

  Partial inlining moves part of a function into an LLVM-outlined body whose
  new debug subprogram files callees inlined into it under the outlined
  symbol's GUID, which owns no trace sites. The D132 decoder now records the
  top-level probe nodes that share a function symbol and retries a missing
  site against those sibling GUIDs. Because the caller keeps an inlined copy
  of the function's entry, the frame that called an outlined body would also
  report the same function again; the decoder treats an outlined body and its
  caller's innermost site for the same function as one activation. Real
  recursion is unaffected because a recursive frame runs in the function's own
  symbol, not an outlined body.
- **Consequences:** Exception semantics, unwinding, rollback and trace lines are
  unchanged; typed-IR tests and inspection see the same IR. Small standard
  library and application methods inline into their callers, two-implementation
  listener callbacks inline through guards, and the development benchmark ran
  about 15 percent faster than Java on the same machine with a 4 percent larger
  executable. The guard fallback keeps correctness independent of the
  instantiability heuristic. Outlining currently covers failure sequences with
  no local handler; sequences inside a `try` remain inline.
- **Verification:** The trace, exception, safe-free, dispatch, string and
  standard-library native suites pass, the order-book checks, normalized example
  output and steady-state allocation boundary are unchanged, and uncaught
  bounds, explicit and null failures report the same frames and lines at `-O0`
  and `-O3`.

## D135 - Let an explicit IronDocs source path select the complete tree

- **Status:** Accepted and implemented. Refines D098's command selection
  behavior without removing its explicit file, package, or subpackage forms.
- **Context:** A regular project already identifies its complete source tree with
  `-sourcepath`. Requiring a package name through `-subpackages` repeats that
  information and makes the basic documentation command harder to explain.
  Adding `-public` to that command is also misleading because IronDocs already
  defaults to public and protected declarations.
- **Decision:** When `-sourcepath` or `--source-path` is explicitly supplied and
  no source file, package operand, or `-subpackages` selection is present,
  recursively select every `.iron` file below the supplied roots. Preserve all
  explicit selection forms. Keep public and protected declarations as the
  default visibility; `-public` remains available only when callers deliberately
  want to exclude protected declarations.
- **Consequences:** A regular project can generate its complete API with an
  output directory, a source path, and an optional title. Running `irondoc`
  without an explicit source path or source selector still reports that no
  sources were selected, preventing an accidental scan of the working tree.
- **Verification:** Focused IronDocs tests cover recursive source-path-only
  discovery, nested packages, default visibility, generated links, explicit
  package selection, and the existing standard-library workflow.

## D136 - Stream CharSequence output without a String snapshot

- **Status:** Accepted and implemented. Extends D086's PrintStream surface and
  removes the pooling-guide hot-path allocation left by D089's safe temporary
  rendering protocol.
- **Context:** PrintStream had String and Object output overloads but no
  CharSequence overload. Passing a StringBuilder therefore selected
  `println(Object)`, created a fresh String through `toString()`, wrote it, and
  conditionally reclaimed it. The operation did not leak, but it still paid one
  allocation and deallocation in a loop intended to demonstrate allocation-free
  object reuse. The CharSequence contract already requires `toString()` to
  contain the same characters in the same order, although the Java type system
  cannot prevent a deliberately nonconforming implementation.
- **Decision:** Add independently implemented Ironwood
  `PrintStream.print(CharSequence)` and `println(CharSequence)` overloads under
  `MIT OR Apache-2.0`. Read `length()` once, visit UTF-16 units through
  `charAt(int)`, combine valid surrogate pairs, and write UTF-8 through the
  existing byte-output boundary. Create no String, array, object, native heap
  scratch, registry entry, or compiler-injected bookkeeping. Preserve each
  destination's established isolated-surrogate replacement and sticky-error
  behavior.

  Do not call `toString()` from these overloads. String remains the
  more-specific overload, and an Object-typed argument continues to use Object
  output and its virtual rendering protocol. A null CharSequence prints
  `null`. Work performed by a caller-defined `length()` or `charAt(int)` is the
  caller implementation's responsibility and does not justify runtime misuse
  tracking in PrintStream.
- **Consequences:** `System.out.println(builder)` and the corresponding print
  call add no allocation when the builder is viewed as StringBuilder or
  CharSequence. Supplementary characters retain correct UTF-8 encoding, and
  isolated surrogates follow the same policy as existing String output for the
  selected destination. The overload is an explicit Ironwood library extension
  because Java PrintStream exposes only String and Object for these reference
  shapes. Existing String, Object, and primitive calls retain their prior code
  paths and behavior.
- **Verification:** A focused native `-O3` fixture uses a conforming
  CharSequence whose `toString()` allocates, a StringBuilder, a null sequence,
  a supplementary pair, and isolated surrogates. Exact stdout and cumulative
  allocation counts prove that the new overloads select character streaming and
  create no managed allocation. The pooling guide compiles and runs with direct
  StringBuilder output, and generated IronDocs expose both overloads.

## D137 - Ship IronDocs and a concise installation guide in release IDKs

- **Status:** Accepted and implemented. Supersedes D098's deferral of
  `irondoc` release-package inclusion.
- **Context:** A downloaded IDK already contains the compiler, archive tool,
  examples, projects, and private toolchain, but it omitted the documented
  `irondoc` launcher. Installation guidance also existed only inside the longer
  IDK reference.
- **Decision:** Package the existing `irondoc` launcher beside `ironwoodc` and
  `ironjar`. Add and package a concise quick-start guide covering the three
  release platforms, extraction, `IRONWOOD_HOME`, `PATH`, compiler version
  verification, and the packaged hello example. Keep `ironwoodc --version` as
  the compiler version command established by D009.
- **Consequences:** After adding `$IRONWOOD_HOME/bin` to `PATH`, an IDK user can
  invoke all three tools from any directory. Release archives continue to ship
  the complete `examples/` and `projects/` trees.
- **Verification:** Host-package and self-contained IDK smoke policies require
  the executable `irondoc` launcher. The IDK policy also checks its version and
  requires the packaged quick-start guide.

## D138 - Share installation-local JVM options across development tools

- **Status:** Accepted and implemented. Extends D137's packaged tool convention.
- **Context:** Users need to configure the bootstrap JVM without editing
  launchers or changing unrelated Java applications. This includes heap limits
  and an opt-in SVE workaround for affected Linux ARM environments.
- **Decision:** `ironwoodc`, `ironjar`, and `irondoc` read the optional
  `conf/jvm.options` relative to the invoked installation. A shared Bash helper
  trims surrounding whitespace, skips blank and full-line `#` comments, and
  forwards each remaining line as one literal JVM argument before the tool
  entry point. Entries must start with `-`; no shell evaluation, interpolation,
  word splitting, globbing, inline comments, or `@argument-file` syntax is added.
  Missing configuration retains defaults; unreadable or malformed configuration
  reports an error, and Java diagnoses invalid JVM flags.
- **Consequences:** Host packages and all three GitHub-built IDKs ship the helper
  and a comments-only configuration. SVE stays enabled where the JVM would
  normally use it. Options affect development tools, not native applications or
  LLVM. Settings belong to one installation and must be reviewed and carried
  over on upgrade. Existing JVM environment-variable handling is unchanged.
- **Verification:** Focused launcher tests cover both Java selection paths,
  relocated paths with spaces, LF/CRLF, comments, empty/missing configuration,
  literal arguments, diagnostic locations, and exit status. Host and IDK archive
  smoke tests exercise the real JVM on all three tools, require default-only
  shipped settings, and verify configured properties, invalid flags, and the
  missing-file fallback. GitHub repeats the checks on downloaded release assets.
