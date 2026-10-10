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
  failure-precedence rules. D168 supersedes only the requirement to express all
  guaranteed resource cleanup through ordinary `finally`; deferred calls and
  local-bound deferred free are implemented in D168 Milestone 1.
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
  specially. A return from `finally` retains its existing precedence.
  [D076](#d076---complete-java-shaped-statement-control-flow-without-hidden-ownership)
  supersedes the original rejection of loop transfers across `finally`:
  `break` and `continue` execute crossed cleanup blocks inner-to-outer before
  reaching their targets. Abrupt cleanup supersedes the pending transfer.

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
- **Deferred-cleanup follow-up (2026-09-19):**
  [D168](#d168---plan-explicit-block-scoped-defer) permits explicit deferred
  actions while retaining both resource-header rejections, separate close/free
  operations, and this decision's exception rules. Its detailed
  [implementation plan](DEFER_PLAN.md) is reviewed. Both Milestone 1 stages are
  implemented. Feature 72 remains an Ironwood alternative, with Java resource
  headers rejected and ordinary `finally` supported. Milestone 2's
  [performance stage](DEFER_PERFORMANCE_VERIFICATION.md) is accepted, and a
  [focused example](../examples/deferredcleanup/README.md) demonstrates the
  operations. [SimpleTcpEcho adoption](DEFER_PROJECT_VERIFICATION.md) and the
  [final audit](DEFER_FINAL_VERIFICATION.md) complete Milestone 2. Both milestones
  and their separately selected adoption follow-ups are committed on `main`.

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
  Infer escape effects for the private failure-dispatch helpers: callbacks may
  retain or rethrow their exception arguments. These helpers must not inherit
  the blanket Files borrowing contract or produce false unfreed diagnostics.
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
  tail-call elimination, including for recursive tail calls. Tail-call-heavy
  programs can retain real call frames that an otherwise equivalent native build
  could remove. Executables retain read-only
  trace metadata and immutable public frame objects, increasing binary size.
  LLVM 23 installations and packaged IDKs must include `llvm-objcopy`. Exact
  source traces remain available at `-O0` through `-O3` and through inlining.
- **Verification:** Focused native tests cover exact uncaught, caught, refreshed,
  rethrown, constructor, public, secondary, emergency and allocation-failure
  traces. Trace output is identical at `-O0` through `-O3` in the focused
  fixture and on macOS ARM64, Linux ARM64 and Linux x86-64. LLVM assertions
  reject the old shadow-stack ABI and require pseudo-probe metadata.
  Machine-code inspection finds none of the former trace entry, line, unwind or
  leave calls. Official Linux workload measurements are recorded in
  [BENCHMARK.md](BENCHMARK.md).

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
  pays one additional call from the inline barrier to the shared slow path. The
  outlined state machine avoids duplicating initialization and exception code at
  every active-use site. Initialization behavior is preserved at every
  optimization level.
- **Verification:** Typed-IR and LLVM assertions require the inline barrier and
  outlined state machine. Focused native tests cover source, class-directory and
  archive reconstruction, superclass and default-interface order, reentrant
  cycles, stored failures, exact failure traces and enum initialization. Native
  disassembly confirms that `-O3` removes the fast-path routine calls from the
  measured order-book loop.

## D134 - Cold failure paths are outlined and small dispatch sets are guarded

- **Context:** Small list, pool, builder, guard, and listener methods can remain
  out of line when their failure paths dominate LLVM's inline cost. The original
  emitter expanded implicit failure paths (null, bounds, length and division
  checks) and explicit
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
  listener callbacks inline through guards. The compiler mechanisms are
  documented in [IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md).
  The official Linux results for the maintained OrderBook engine are in
  [BENCHMARK.md](BENCHMARK.md). The guard fallback keeps correctness independent
  of the instantiability heuristic. Outlining currently covers failure sequences
  with no local handler; sequences inside a `try` remain inline.
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

## D139 - Keep Linux release output compatible with glibc 2.17

- **Status:** Accepted and implemented. Refines D009 and D014 for official Linux
  IDKs without adding cross-compilation.
- **Context:** Letting the release environment select its newest available
  sysroot made generated programs inherit the build runner's glibc baseline. A
  program built on Ubuntu 24.04 could therefore fail on an otherwise supported
  older Linux system before reaching Ironwood code.
- **Decision:** Build each official Linux IDK with its architecture-matched
  conda-forge glibc 2.17 sysroot. Keep LLVM, Java, and the release runner current.
  Require Linux packaging to find exactly that sysroot version, and inspect the
  GLIBC symbol requirements of generated ELF programs during IDK smoke testing.
  Reject any requirement newer than 2.17.
- **Consequences:** Official Linux IDKs and their generated native programs
  support glibc 2.17 and newer on the matching architecture. This does not add a
  user-selectable target, cross-compilation, or musl support. Current compiler
  optimization and code generation remain unchanged.
- **Verification:** The dependency solver is checked for Linux ARM64 and x86-64
  with the 2.17 pin. Release packaging verifies the sysroot metadata. Build and
  downloaded-archive smoke jobs use packaged `llvm-readelf` to audit every
  generated executable exercised by the IDK smoke test.


## D140 - Warn by default about proven abandoned allocations

- **Status:** Accepted and implemented. Amends D027's diagnostic policy for
  omitted reclamation while retaining its explicit lifetime model and D005's
  mandatory safe-`free` proof.
- **Context:** Java-shaped expressions can discard native allocations without
  a visible `new`, such as a dynamic String concatenation passed directly to
  `println`. A short process may deliberately retain that memory, but silently
  accepting the same pattern makes accidental accumulation hard to notice.
- **Decision:** Add `--unfreed=off|warn|error` to compilation and linking, with
  `warn` as the default. One analysis produces the findings; the option controls
  their severity. Warnings permit compilation and output, strict mode rejects
  them, and off leaves every existing safety error enabled. Point diagnostics
  to allocation expressions and describe the missing reclamation without
  inferring programmer intent. Do not repair existing omissions as part of
  this compiler change, add implicit destruction, or introduce suppression
  syntax. Reconstructed class/archive bodies are checked again at final link.
- **Analysis:** Track completed source objects/arrays, dynamic concatenations,
  and existing proven non-null fresh factories. Observe loss of local references
  at statement boundaries, normal scope exits, and completed returns. Retain
  aliases and existing array/container/pool ownership dependencies. Keep this
  diagnostic's lifetime snapshots separate from safe-free states. Account for
  constructor rollback, factory failure, finally copies, pending expression
  operands, implicit rendering cleanup, and possible transitive library
  reclamation. Only final lowering reports findings; provisional summaries
  cannot produce warnings. Diagnostic severity is preserved in CLI output,
  compilation-artifact success predicates, and LSP diagnostics.
- **Limits:** This is not a proof that all memory is eventually reclaimed.
  Escapes, uncertain aliases/effects, nullable or mixed factory results,
  conflicting control-flow states, and unproved loop/exception transfers do
  not justify a definite-abandonment diagnostic. Conditional cleanup and
  outward exceptional exits are not exhaustively diagnosed. In particular,
  this decision does not extend the symbolic return analysis's currently
  unknown general concatenation results. No runtime instructions, allocation
  counters, registries, destructor behavior, or safe-free permissions change.
- **Verification:** Focused diagnostics cover the Chatter omission, inline and
  named concatenation, overwrites, compound assignment, arrays, fresh factories,
  aliases, retained results, cleanup, failed construction, and nested expression
  evaluation. CLI checks cover default/off/error, invalid selections, source
  and archive linking, native output/allocation counts, and identical typed IR
  and LLVM with warnings on or off. LSP checks cover warning severity, source
  ranges, and clearing a warning after an unsaved edit restores `free`.

## D141 - Testing harness reclaims reporting allocations

- **Status:** Accepted and implemented.
- **Context:** D140's default warnings exposed discarded reporting strings and
  an unfreed generated `TestRunner`. The compiler's standard-library regression
  also merged compiler diagnostics into an exact comparison of successful
  suite output, making warnings fail the check.
- **Decision:** Amend D110's generated entry point to save `finish()`'s status,
  explicitly free the runner, then return the status. The library runner binds
  and frees its concatenated reporting strings after printing. The suite may
  escape through user callbacks and remains process-lived. Ordinary source
  allocation and safe-free rules are unchanged.
  Keep compiler diagnostics visible on standard error while comparing the
  standard-library script's standard output. Retain the default warning mode;
  compilation errors and native verification failures still fail the check.
- **Verification:** Generated typed IR contains the runner's `free` without an
  unfreed-runner diagnostic. A native `-O3` regression checks passing, skipped,
  and failing reports, both summaries, and restoration of the live-allocation
  baseline. The standard-library check retains its exact output and totals.

## D142 - Infer anonymous diamond body types before validation

- **Status:** Accepted and implemented.
- **Supersedes:** D046's validation sequencing for anonymous diamond bodies
  only. Its object-model, access, generic-safety, and reclamation rules remain.
- **Context:** Checking a concrete override against an unresolved synthetic
  diamond variable rejected the documented `Box<String>` anonymous subclass,
  including its `String get()` override and `super.get()` expression.
- **Decision:** After member collection, use the existing pure invocation
  planner and lexical context to infer the containing expression. Bind the
  selected anonymous allocation's inferred arguments for inherited-member and
  body checking. Keep the constructor's generic template for ordinary overload
  inference; reject a selected allocation that conflicts with the body view.
  Unresolved inference never authorizes a type conversion, override, or `free`.
- **Consequences:** Concrete reference overrides use the inferred parent type
  without unchecked casts, runtime type discovery, or generated bookkeeping.
  Invalid overrides, unknown ownership, live aliases, use after free, and double
  free retain their mandatory diagnostics in every missing-free mode.
- **Verification:** Focused semantic and typed-IR coverage exercises local,
  field, return, and argument contexts, expected-type inference from null,
  incompatible return/parameter overrides, and unsafe reclamation. A native
  `-O3` fixture checks generic parameter/return overrides and `super` dispatch.

## D143 - Allow explicitly selected stable release versions

- **Status:** Accepted and implemented.
- **Supersedes:** D100's requirement that a new release match the current
  development version. Its clean-main, synchronization, and tag guards remain.
- **Decision:** Accept any valid stable `MAJOR.MINOR.PATCH` chosen by the
  maintainer. Do not infer or require intermediate releases. Set the source and
  API versions to that number, retire the previous development API reference
  during the stable preparation, and preserve frozen stable snapshots. The
  next development version increments the selected patch number and adds
  `-beta`; `0.2.6-beta` can therefore release as `0.3.0` and advance to
  `0.3.1-beta`. Resume still requires the exact prepared release and successor.
- **Verification:** Local Git fixtures exercise explicit version selection,
  malformed versions, existing tags, the `0.2.6-beta` to `0.3.0` CLI flow,
  previous-beta retirement, frozen history, custom status, and resume/tag checks.

## D144 - Reject unreachable statements after literal-true loops

- **Status:** Accepted and implemented.
- **Context:** The documented assumption that every loop may exit admitted
  unreachable statements after `while (true)`, `do`/`while (true)`, and `for (;;)`.
  It also required redundant trailing returns in value-returning methods ending
  in those loops.
- **Decision:** Replace that assumption for literal-true conditions and omitted
  classic `for` conditions. Emit a typed jump to the body instead of a branch
  with an impossible condition-false edge. Exclude that edge from exit merges;
  an exit without predecessors is unreachable, so the existing unreachable
  statement error and return analysis apply. Keep labeled transfers, nested
  break destinations, exception edges, and crossed `finally` handling intact.
- **Compatibility:** Preserve Java's acceptance of statements after
  `if (true) return;`, `if (1 == 1) return;`, and conditional breaks, including
  those in constant-false `if` branches. The new rejection cases agree with
  Java 21. General constant-expression evaluation, variable or method-based
  nontermination proofs, and constant-false loop-body diagnostics remain
  outside this change.
- **Consequences:** This is source reachability and typed-IR lowering, with no
  runtime bookkeeping or new IR operations. Existing reclamation proofs and
  loop-back-edge validation remain mandatory. Missing-free policy and
  suppression syntax are separate concerns and are not changed here.
- **Verification:** Focused source and typed-IR regressions cover rejected
  tails, non-void endless loops, real and overridden breaks, nested transfers,
  Java-compatible constant `if` behavior, and accepted/rejected reclamation.
  Native `-O3` coverage exercises all three loop forms, SSA values, continue
  targets, exception handlers, and `finally` transfers.

## D145 - Exempt individual allocations from missing-free diagnostics

- **Status:** Accepted and implemented.
- **Supersedes:** D140's exclusion of per-site suppression syntax. Its default
  severity, diagnostic coverage, and separation from mandatory safety remain.
- **Decision:** Add the exact compiler-owned `@SuppressUnfreed` directive on
  reference local declarations with initializers, including classic `for`
  initializers. Allow it before the type, interleaved with `final`, with no
  arguments or duplicates. Reject primitive locals and other targets,
  including enhanced-for and catch variables. The name remains an ordinary
  identifier elsewhere; no annotation type, import, or annotation framework
  is introduced.
- **Scope:** Exempt only the initializer's allocation as identified by existing
  compiler tracking, including existing aliases and fresh factory results.
  An annotated alias exempts that identity across branches, independently of
  analysis order.
  Do not exempt subsequent different allocations assigned to the same variable,
  unrelated allocations in called methods, nested temporary allocations, or
  array/container contents. A null or untracked initializer grants no exemption
  to future assignments. Repeated executions of a marked declaration are
  covered; an explicit exemption can therefore hide repeated leaks.
- **Severity and transport:** Suppress that allocation's missing-free finding
  in both `--unfreed=warn` and `--unfreed=error`. Preserve all other findings
  and errors. Class/archive source preservation carries the directive through
  reconstruction and native linking; the command-line mode stays per invocation.
- **Implementation:** Preserve a local AST flag and record allocation identities
  only in the diagnostic tracker. Do not change reclamation proofs, ownership
  state, allocation lifetimes, destructors, typed IR, LLVM, or runtime metadata.
  The directive grants no permission for unsafe `free` or use after free.
- **Verification:** Focused parser and semantic regressions cover placement,
  aliases, reassignment, loops, duplicated `finally`, supported initializer
  kinds, unrelated findings, and unchanged safety failures in all modes.
  Typed-IR/LLVM equality checks and strict class/archive native links verify
  unchanged generated code and live allocation counts without original source.

## D146 - Preserve dynamic concatenation ownership across method returns

- **Status:** Accepted and implemented.
- **Supersedes:** D140's stated limit that symbolic return analysis leaves
  general concatenation results unknown, for dynamic binary String
  concatenations. D061, D089, D111, and all mandatory safe-`free` rules remain.
- **Context:** A dynamic String `+` expression was already an explicit fresh
  allocation in typed IR and could be freed inside its declaring method. The
  symbolic return analyzer nevertheless classified every binary expression as
  unknown. Returning that same allocation from `toString()` therefore blocked
  a direct caller `free` and left D089/D111 rendering consumers unable to
  release it. Marking every source `+` expression fresh would be unsound because
  constant concatenations are folded into immortal literals.
- **Decision:** After provisional call binding and typed lowering, index every
  `IrStringConcatInstruction` by callable linkage and source span, including
  instructions carried by invoke terminators. Supply that compiler-proven set
  to refined fixed-point return analysis. A matching source binary expression
  contributes one fresh String origin after its operands and effects are
  analyzed. Local assignments, branches, wrapper returns, publication, and
  aliases continue through the existing symbolic rules.

  Grant a call result caller-owned status only when the existing summary proves
  every non-null returned value fresh, no returned origin borrowed or unknown,
  and no fresh result published elsewhere. Constant-folded concatenations have
  no dynamic-concatenation instruction and receive no new permission. Mixed,
  borrowed, escaping, unresolved, and live-aliased values remain conservative.
  Do not add an annotation, runtime check, registry, counter, hidden release,
  or general automatic reclamation.
- **Consequences:** A method such as `toString()` may return dynamic
  concatenation text that its direct caller can explicitly free. The same proof
  sets the existing concrete descriptor bit, so PrintStream, StringBuilder, and
  object concatenation consumers reclaim that temporary under D089/D111. The
  refinement is recomputed from preserved bodies during class/archive linking
  and changes no runtime ABI or valid-path machine-code bookkeeping.
- **Verification:** Focused semantic coverage accepts direct reclamation and
  checks the descriptor bit. Negative coverage preserves live-alias rejection
  and denies ownership to constant-folded, borrowed, mixed, and published
  results. A native `-O3` compile/link/run check verifies explicit caller cleanup,
  automatic Object-output cleanup, exact output, and restoration of the live
  allocation baseline. The memory-management example now reclaims both of its
  distinct dynamic String results explicitly.

## D147 - Track proven receiver-retained method borrows

- **Status:** Accepted and implemented. Extends D094's constructor-retained
  borrow edges and D140's definite local abandonment coverage without adding
  ownership transfer.
- **Context:** An ordinary method that stored a caller allocation only in its
  receiver's private field caused the allocation to enter the general escaped
  state. Even after the receiver was freed, safe-`free` rejected caller cleanup
  and missing-free diagnostics ignored definite abandonment. Treating all
  escaped allocations as abandoned would create false positives for returns,
  global publication, unknown calls, and intentional process-lifetime state.
- **Decision:** Record the exact retained field for ordinary instance methods as
  well as constructors. At a resolved call, create a receiver-to-child borrow
  edge only when the receiver and child are known active local allocations, the
  argument is retained only by that receiver, and the unique target field is
  private and closed-world encapsulated. Reject child reclamation while any
  retaining receiver remains live. Freeing a receiver removes its borrow edges
  without reclaiming the children; a child then becomes independently
  reclaimable and reportable by the existing missing-free checker.

  Propagate receiver publication through every retained child. Preserve general
  escape behavior for unknown receivers, retention outside the receiver,
  exposed or ambiguous fields, unresolved dispatch, uncertain ownership, and
  non-local publication. Repeated stores conservatively retain every proven
  child until receiver destruction. A self-reference needs no separate edge.
  Do not infer a consuming call, permit a destructor to free caller-owned state,
  inject cleanup, or add runtime metadata or checks.
- **Consequences:** Java-shaped setter calls can retain caller objects safely
  without becoming ownership transfers. The caller can reclaim an object only
  after all proven receiver borrows end. If it does not, default and strict
  missing-free modes now report the definite local abandonment. Generated typed
  IR, LLVM, object layouts, and runtime behavior are unchanged.
- **Verification:** Focused semantic regressions cover the new warning and
  strict error, successful cleanup after receiver destruction, repeated stores,
  live-receiver rejection, receiver publication, and exposed-field fallback.
  Existing constructor, container, pool, escape, and unfreed groups protect
  surrounding behavior. A class-artifact native `-O3` check restores the live
  allocation baseline after explicit receiver-then-child reclamation.

## D148 - Add native latency benchmarking with reusable primitive storage

- **Decision:** Add `ironwood.bench.Bench` and `NanoBench`, contributed by the
  original author under Apache-2.0 with neutral naming. Preserve warmup,
  mark/measure, reset, report, and rounded-rank percentile conventions. Supply
  native examples in `examples/bench`, using `ironwood.ds.IntMap` for the map
  demonstration. No C/C++ implementation is imported.
- **Native adaptation:** Use `ironwood.ds.LongArrayList` durations and counts
  with a primitive hash index for Bench's histogram. This permits deterministic
  ownership and allocation-free recording within capacity without introducing
  compiler exemptions for a nested counter pool. Reset reuses storage; reporting
  sorts only distinct durations and aggregates bucket counts. Keep verbosity
  explicit through `Bench(long, boolean, int)` and fixed English formatting
  through existing numeric helpers. No locale or VM-property subsystem is added.
- **Contracts:** Bench consumes a mark once. NanoBench retains its mark and
  performs no pairing validation. Caller-supplied durations are nonnegative;
  counts and summed durations must fit their documented primitive ranges.
  `reset(false)` permanently disables the current warmup count. Reports own
  their String snapshots, and printing reclaims or avoids temporary text.
  NanoBench adds caller-supplied measurements, statistic queries, and snapshots.
  Its mean uses integer division, avoiding floating conversion loss for large
  totals. Bench avoids saturation when rounding very large averages.
- **Verification:** Native `ironwood.testing` suites cover statistics,
  percentile bucket boundaries, clock pairing, warmup/reset, histogram growth,
  allocation-free reuse, independent reports, and reclamation. Fresh-process
  allocation budgets cover failed construction, growth, and reporting. Example
  workloads and documentation programs are compiled and run at `-O3`.
  [BENCH.md](BENCH.md) records usage, ownership, and the API adaptation review.
- **Test harness:** Mark only the synthetic process-lived suite allocation
  with D145's missing-free omission flag. D141 already leaves suite instances
  process-lived because callbacks can publish them. A non-publishing suite now
  also compiles under strict missing-free diagnostics. Test-body allocations
  remain checked; memory-safety enforcement and generated runtime behavior do
  not change.

## D149 - Measure OrderBook latency in fixed batches of complete cycles

- **Decision:** Keep paired throughput runs in `projects/OrderBook/throughput.sh`
  and `java/throughput.sh`. Add paired native and Java `latency.sh` scripts with
  defaults of 10,000 warmup batches, 50,000 measured batches, and 1,000 cycles
  per batch, targeting runs below ten seconds on the benchmark hosts. Each
  cycle executes the throughput driver's same eight operations and restores
  the empty book and full pools. Both native drivers share the cycle method
  and final workload validation; the paired Java cycle remains equivalent.
- **Measurement:** A sample brackets the complete batch with two
  `System.nanoTime()` calls. Store raw durations after the closing clock read
  in a preallocated array, and build the `ironwood.bench.Bench` report only
  after all sampling finishes. Warmup uses the same path and is excluded when
  feeding the report. Check allocation counts around the complete collection
  loop, with no per-cycle instrumentation. Sample storage and report storage
  are reclaimed; the existing book pool graph retains process lifetime.
  The Java driver uses the same timed loop and a post-processing helper that
  sorts measured samples and follows the same reporting conventions. Native
  cleanup and allocation diagnostics stay outside the shared timed region.
- **Interpretation:** Fixed batching reduces clock overhead and quantization
  relative to the interval. Print an empty-interval clock diagnostic, retain
  timing overhead in results, and compare distributions only at the same
  batch size. These are synchronous batch execution latencies; dividing a
  batch percentile does not yield an operation or cycle latency percentile.
  The workload does not model queueing or external arrivals.
  Fifty thousand measured batches support p99 and p99.9 better than extreme
  tails: p99.99 leaves five observations above its boundary, and the rounded
  p99.999 rank is the maximum. Fixed counts do not impose a wall-clock deadline.
- **Performance reporting:** Linux is the official platform for published
  Ironwood performance measurements and comparisons. Use
  [BENCHMARK.md](BENCHMARK.md) as the source for official application results,
  and state the measured workload, environment, and protocol. Compiler/test
  timing claims and claims about an individual optimization require their own
  Linux measurements; the application benchmark does not establish them.
- **Verification:** Native tests cover shared workload totals, pool recovery,
  allocation-free collection at multiple batch sizes, and argument bounds.
  Java counterparts cover the same behavior and reporting. Compare synthetic
  reports byte for byte, and verify shared source methods. A separate Java 25
  compilation-log diagnostic reached C2 before the 80-million-operation warmup
  ended, with no later application compilation events during measurement.
  Command-line checks exercise warmup exclusion and invalid inputs. Record the
  benchmark protocol and official results in [BENCHMARK.md](BENCHMARK.md).

## D150 - Author package documentation in package-info.iron

- **Status:** Accepted and implemented. Supersedes D098's deferral of package
  comments; module comments and the remaining deferred facilities stay deferred.
- **Decision:** Use one `package-info.iron` per named package, containing a
  documentation comment immediately before `package`, optional imports, and no
  types. Reuse the compiler parser and declaration spans. Ordinary compilation
  accepts these units without emitting a type, class artifact, or runtime
  metadata. Other source files still require type declarations.
  IronDocs models package comments separately from types and resolves their
  links using the package and imports. Duplicate package documentation is an
  error. Invalid comments retain source diagnostics and prevent output writes.
- **Presentation:** Reuse the existing first-sentence summary rule for the
  overview's package-description column. Render the entire comment above the
  package's type table. No explicit summary tag is introduced. A selected
  package-info file can document a package without visible types; packages
  without comments retain blank summaries. Package comments accept reference
  and metadata tags, but not parameter, return, or exception tags.
- **Publication:** Standard-library package descriptions live in source,
  replacing the publishing script's insertion from `STDLIB.md`. Its numeric
  inventory remains checked and includes the package-info source files. Generate
  these descriptions for `0.4.2-beta` and later; do not rewrite past snapshots.
- **Boundary:** No package annotations, legacy `package.html`, module comments,
  or additional Javadoc facilities. See [IRONDOCS.md](IRONDOCS.md) for authoring
  and selection rules.
- **Verification:** Focused IronDocs tests cover summaries, full descriptions,
  imports and relative links, selection, visibility, malformed input, duplicate
  packages, deterministic output, and publication history. A focused compiler
  test covers parsing, typed IR, absent package class artifacts, and native
  execution alongside package documentation.

## D151 - Stage blocking TCP before event-loop integration

- **Status:** Design accepted 2026-09-14. Milestone 1 is complete. Milestone 2
  was separately selected on 2026-09-14 and implements blocking socket/address
  APIs, literal/scoped addresses and OS DNS. Milestone 3 was separately
  selected on 2026-09-15 and is implemented. Milestone 4 was separately selected
  on 2026-09-16; see D164 and [M4 verification](NETWORKING_M4_VERIFICATION.md).
  Milestone 5 was separately selected on 2026-09-16; see D165. Milestone 6
  was separately selected on 2026-09-17 and completed on 2026-09-18 under D166.
  This supersedes the event-loop design prerequisite for initial socket/DNS/HTTP work in `STDLIB_ROADMAP.md`, item 6.
  N1's event-loop completion requirement and the exclusion of language threads
  remain in force.
  The plan's [acceptance checklist](NETWORKING_MIGRATION_PLAN.md#acceptance-and-documentation-updates)
  records the coordinated documentation and packaged-review updates, separately
  from implementation completion.
- **Context:** The blocking `Socket`/`ServerSocket` migration excludes channels
  and selectors, while N1 requires event-loop integration and
  `JAVA_EXCLUSIONS.md` recommends event-driven concurrency. Blocking socket
  clients and sequential servers are useful intermediate capabilities, but
  they cannot establish the roadmap's multi-client event-loop result. Internal
  polling to enforce one operation's deadline does not multiplex application
  work across connections.
- **Decision:** Stage N1 in two phases. First deliver the
  [blocking TCP migration](NETWORKING_MIGRATION_PLAN.md), including its scoped
  HTTP/HTTPS client. Follow it with a separately designed non-blocking surface
  and event-loop integration. Keep N1 incomplete until both phases satisfy
  their application gates. This changes delivery order, not the long-term
  single-threaded, event-driven server target.
- **Implementation selection:** Milestone 1 was selected on 2026-09-14.
  Its required documentation and packaging metadata are included.
  After the Milestone 1 exit review and regression fixes, the maintainer
  separately selected Milestone 2 on 2026-09-14: complete the remaining blocking
  socket/address APIs, literal parsing, scopes and synchronous OS DNS, with
  the reviewed ownership, provenance and verification gates. This does not
  select host-network queries, reachability, proxies, TLS or the downloader.
  On 2026-09-15 the maintainer separately selected Milestone 3 host-network
  queries, reference-bounded enumeration, interface-valued scopes and
  deterministic IPv4/IPv6 reachability. Required review, documentation and
  focused verification are included. On 2026-09-16 the maintainer separately
  selected Milestone 4: explicit SOCKS4/5 and HTTP CONNECT, copied credentials,
  target endpoint reporting, negotiation deadlines, and the reviewed verification
  and provenance gates. The maintainer separately selected Milestone 5 on
  2026-09-16: scoped TLS clients and the complete optional static dependency,
  trust, packaging and verification mechanism. Milestone 6 was subsequently selected under D166.
  The six-milestone first phase is an architectural
  roadmap, not a single implementation assignment. Selecting Milestone 1 does
  not select Milestones 2 through 6. Its
  [exit checkpoint](NETWORKING_MIGRATION_PLAN.md#milestone-1-selection-checkpoint)
  reviews the foundation's evidence and remaining scope/dependencies; each later
  milestone requires separate maintainer selection before work begins. Passing
  an earlier gate or accepting the overall design does not select the next one.
- **Native boundary:** Separate individual I/O attempts from readiness waits.
  Preserve partial progress, would-block, pending connection, EOF, and native
  error results. Blocking facades retry and wait according to their contracts;
  future non-blocking callers return control to their event loop instead.
  Reuse the descriptor operations and readiness/error mapping when adding a
  multi-descriptor wait backend. Preserve the direct untimed blocking syscall
  path under the plan's [untimed TCP I/O budget](NETWORKING_MIGRATION_PLAN.md#untimed-tcp-io-budget):
  one receive for an ordinary uninterrupted positive-length read, no polling,
  clock reads, descriptor-flag work, or avoidable helper calls. Preserve this path
  after timed operations and timeout resets; setup/configuration costs remain
  separate. Required EINTR and short-write retries remain supported. This
  applies D132/D133 without changing their existing invariants.
  Do not add speculative selector registrations, schedulers, runtime
  ownership bookkeeping, or a public non-blocking API to the first phase.
- **Consequences:** A blocked operation stalls other application work in that
  process. Sequential server examples must say so, and completing `wget` must
  not mark N1 complete. The planned synchronous resolver, proxy negotiation,
  and TLS client also need explicit treatment in the later event-loop design;
  transport reuse alone does not make them non-blocking. No future public
  selector API, wait backend, or threading feature is selected here.
- **Planned verification:** The first phase tests the attempt/wait boundary,
  partial transfers, deadlines, and unchanged safe reclamation. Inspect linked
  `-O3` generated/native paths and native-call counts, including clocks that may
  bypass syscall tracing. Benchmark timed and untimed transfers separately and
  verify that timed-to-untimed transitions restore the direct path. The later N1
  gate requires a multi-client application that progresses while a peer
  stalls, handles partial-write backpressure, and closes and reclaims its
  connections safely.

## D152 - Establish socket extension contracts in the first TCP milestone

- **Status:** Design accepted 2026-09-14 with the
  [networking migration plan](NETWORKING_MIGRATION_PLAN.md). Milestone 1 implements the representative extension protocol; later milestones
  are unselected and their APIs unavailable. This refines that plan's extension scope and delivery
  order; it does not supersede D151's N1 sequencing or existing boxing,
  ownership, and provenance rules.
- **Context:** The first TCP milestone needs custom implementations to test
  polymorphic retention. Deferring their delegation and ownership design until
  Milestone 3 would invalidate that proof. Java's `SocketImpl` also inherits a
  boxed `SocketOptions` protocol, while both global factory hooks have been
  deprecated since Java 17. These require explicit compatibility choices.
- **Decision:** Establish `SocketImpl`, a concrete managed native
  delegate, injected implementations, both factory hooks, protected acceptance,
  and typed option dispatch in Milestone 1's representative slice. Milestone 2
  extends the protocol with remaining socket members and options. Milestone 3
  adds host networking, not prerequisites for earlier ownership tests.
- **Option protocol:** Omit `SocketOptions` and the integer-ID/`Object` methods.
  Use primitive-specialized generic `getOption`/`setOption` hooks throughout,
  declaring `SocketException` so dedicated facade methods keep their checked
  exception contracts. Boolean and integer native operations carry no boxed
  intermediate. Dedicated methods share the same hooks, with typed timeout and
  out-of-band-inline tokens and integer `-1` for disabled linger. Address queries
  remain separate. The plan specifies discovery and error behavior.
- **Deprecation policy:** Judge deprecated members by selected capability and
  native compatibility. Retain the requested process-wide factory hooks and
  their registration contracts; prefer per-instance injection for new code.
  Omit whole UDP-selecting constructor overloads because they admit unsupported
  transport, not merely because they are deprecated. Omit the boxed option
  protocol because its representation conflicts with Ironwood.
- **Ownership:** Fresh default and proven-fresh factory results can be owned;
  explicitly injected implementations and externally supplied delegates are
  borrowed. Stream and descriptor access preserves dependent borrows. Factory
  registration retains its object and captured graph for the process lifetime.
  Custom overrides and result publication must be analyzed without exemptions.
- **Planned verification:** Gate Milestone 1 on default, injected, factory, and
  custom-accept paths; safe and retaining overrides; both primitive option
  shapes through generic dispatch; factory lifetime and registration behavior;
  and compile-time rejection of omitted APIs and unsafe reclamation. Preserve
  descriptor cleanup and allocation-free steady-state I/O.

## D153 - Select the native TLS dependency after closed-world pruning

- **Status:** Design accepted 2026-09-14 with the
  [networking migration plan](NETWORKING_MIGRATION_PLAN.md#optional-tls-build-and-packaging-mechanism).
  Milestone 1 implements ABI separation; TLS implementation and
  dependency imports were separately selected on 2026-09-16 and completed under
  D165 on 2026-09-17. Applies D139's Linux baseline to TLS; does not supersede
  D139, D151, or D152.
- **Context:** The backend always compiles the core and casing runtime units
  and uses a fixed link command. The IDK environment does not explicitly supply
  an application TLS SDK, and its package inventory only reads Conda metadata.
  Putting OpenSSL calls in an always-built runtime unit would require its
  headers even for programs whose TLS code is pruned.
- **Decision:** Derive native-link requirements from retained typed
  operations after specialization and closed-world pruning. Pass them to the
  backend explicitly. Select a separate original TLS adapter translation unit,
  CA data, static `libssl.a`/`libcrypto.a`, and their platform link requirements
  only for TLS links. Keep ordinary runtime/TCP headers independent of OpenSSL.
  Include component headers, build identity, and target/sysroot arguments in
  native object cache keys. No runtime discovery or dynamic provider dependency
  is introduced.
- **Dependencies:** Maintain one pinned source/build manifest and recipe for
  macOS ARM64, Linux ARM64, and Linux x86-64. Build each Linux archive against
  the architecture-matched glibc 2.17 sysroot, also used for adapter compilation
  and final linking. Record the macOS SDK/deployment target. Package a separate
  `toolchain/ironwood-tls` prefix. The planned `IRONWOOD_TLS_HOME` override and the
  same preparation recipe support source trees; dependency validation happens
  only on TLS native links. No compiler-triggered download or implicit system
  OpenSSL fallback is selected.
- **Distribution:** Record the actual OpenSSL and CA inputs in source
  provenance, notices, license texts, the packaged TSV, and a build/checksum
  manifest. Merge separately built dependency records with Conda metadata.
  Validate relocation, static dependency closure, and the GLIBC requirements
  of generated TLS and downloader executables under D139.
- **Milestones and verification:** Milestone 1 reserves the typed-operation and
  ABI separation. Milestone 5 implements the adapter, dependency selection,
  builds, discovery, and packaging; Milestone 6 validates the delivered SDK and
  downloader. Test pruned TLS without an SDK, reachable TLS through source and
  archive links, cache invalidation, dependency diagnostics, and relocated
  package fixtures on all supported platforms.

## D154 - Define network result ownership and connection allocation budgets

- **Status:** Design accepted 2026-09-14 with the
  [networking result matrix](NETWORKING_MIGRATION_PLAN.md#non-stream-results-and-input-ownership).
  Milestones 1 through 6 are implemented; see D166 for the blocking migration's
  completion evidence.
  Refines D152's socket graph without
  superseding D151/D153 or changing ordinary array, collection, and exception
  reclamation rules.
- **Context:** Stream borrows alone do not describe address getters, resolver
  results, option inventories, interface traversal, or accepted sockets. Java
  mixes retained objects and snapshots without Ironwood's explicit reclamation.
  Steady-state I/O allocation tests miss connection setup and result costs.
- **Decision:** Return a fresh independently owned socket from accept.
  Cache simple socket address getters as borrows, preserving Java's distinct
  client/listener post-close values. Return independent endpoint snapshots and
  byte arrays; add `InetAddress.copy()` for explicit independent address copies.
  Address construction and built-in bind/connect copy retained input values.
  Resolver calls return fresh addresses; `getAllByName` returns a fresh array
  plus individually owned fresh elements, requiring separate element cleanup
  and shallow array cleanup.
- **Inventories and interface graphs:** Supported-option inventories are
  retained read-only borrows, with explicit backing-list, iterator, and token
  owners. Interface lookups and top-level enumeration return fresh snapshot
  owners; metadata and parent/child views borrow from their snapshot. Nested
  enumerations are fresh cursors borrowing it. Interface-address lists are fresh
  `ironwood.ds` lists of borrowed entries. Flat snapshot storage avoids recursive
  parent/child ownership; no generic collection or array gains ownership of
  elements by implication. The plan specifies each method family and null cases.
- **Exception messages:** Follow U3's copied-message behavior for networking
  exceptions, preserving their Java inheritance. Message getters borrow owned
  text; temporary source/native message buffers can be released. Causes and
  secondary exceptions retain their ordinary lifetime rules.
- **Milestones and verification:** Milestone 1 proves accepted-socket and
  result independence, borrowed getter lifetimes, interface navigation on
  synthetic snapshots, explicit bulk-element cleanup, and message-copy failure
  handling. Record exact connection allocation baselines by component and
  IPv4/IPv6/custom-implementation profile. Measure first getters, repeated
  getters, fresh snapshots, successful close/free cycles, and failure cleanup
  separately; count native allocations and descriptors in addition to managed
  allocations. Milestones 2 and 3 now exercise actual DNS and host-network graphs.
  Unproved lifetimes or unexplained allocations block expansion.

## D155 - Fix networking property conventions explicitly

- **Status:** Design accepted 2026-09-14 with the
  [fixed networking policy inventory](NETWORKING_MIGRATION_PLAN.md#fixed-networking-policies).
  Milestones 1 through 6 are implemented; see D166 for the blocking migration's
  completion evidence.
  Refines the migration's previously
  unspecified fixed policies without superseding D151-D154 or expanding the
  supported `System.getProperty` subset.
- **Context:** Java socket behavior depends on system/security properties and
  startup-cached settings, including inside the two selected derived helpers.
  Removing property infrastructure does not decide the resulting grammar,
  address order, caching, proxy protocol, or authentication behavior. The
  porting guide requires an explicit product convention for each difference.
- **Address conventions (NP1-NP3):** Choose Java's default dual-stack capability
  policy and IPv4-first resolution, with OS order preserved within each family.
  Retain explicit IPv6 use, IPv4 fallback when IPv6 is unavailable, and the
  selected Java loopback/wildcard behavior. Fix ambiguous-literal handling to
  the pinned default `false`: preserve decimal short forms and leading zeros,
  while rejecting BSD-only forms before OS resolution. Preserve each public
  facade's failure behavior. No global family or permissive-parser switch is
  introduced; these choices preserve Java defaults rather than narrow the
  admitted literal grammar.
- **Name-service conventions (NP4-NP7):** Use OS resolution and OS host mappings,
  with no alternate Java hosts file or dynamic provider. Omit positive,
  negative, and stale Ironwood DNS caches explicitly. OS caching and blocking
  remain possible; connect deadlines do not bound DNS. Object-local cached
  getter results retain D154's ownership rules. The tradeoff is repeated OS
  lookup work instead of a shared retained address cache and refresh machinery.
- **Proxy conventions (NP8-NP10):** Default to direct connections; proxy
  endpoints, ports, protocol version, and credentials are explicit. Select V5
  by default and retain typed V4 selection. Deliberately remove the upstream
  V5-to-V4 handshake retry so the configured protocol is not changed after a
  negotiation failure. Retain V4's resolved-IPv4 target requirement and V5's
  proxy-side DNS/IPv6. No implicit property/environment discovery, bypass list,
  or OS-user credential fallback is added. Explicit V5 credentials require
  username/password negotiation; an unconfigured client offers only
  no-authentication. V4 has a
  separate explicit user ID, empty by default. These protocol/authentication
  differences must be stated when documenting the Java-shaped proxy facade.
- **Diagnostics and dependency boundary (NP11):** Disable optional automatic
  host-info enrichment while preserving ordinary error context and D154's
  message copies. The inventory also identifies URL-only parser switches,
  platform-specific socket switches, and unrelated URLConnection/JSSE settings
  that are not dependencies of the selected helpers. Audit each new dependency
  for additional reads before translation; record new conventions rather than
  silently deleting those reads or selecting defaults.
- **Milestones and verification:** Resolve the inventory and native/configuration
  boundary in Milestone 1, including dual-stack wildcard acceptance and the
  IPv4-only fallback. Milestone 2 tests grammar, failure mapping, family order,
  and cache behavior with controlled fixtures. Milestone 4 verifies explicit
  proxy/version/authentication behavior and failure without protocol downgrade.
  Use Java differential tests with explicit selected settings only where its
  behavior is the contract. Keep fixed choices off the steady-state I/O path
  and preserve compile-time exclusions for unsupported configuration APIs.


## D156 - Separate reachability contract tests from host smoke checks

- **Status:** Design accepted 2026-09-14 with the
  [reachability plan](NETWORKING_MIGRATION_PLAN.md#reachability-contract-and-verification).
  Milestone 3 was selected on 2026-09-15 and is implemented; see D151 and
  [M3 verification](NETWORKING_M3_VERIFICATION.md). Refines Milestone 3 verification;
  it does not supersede D151-D155 or remove the selected reachability APIs.
- **Context:** Live ICMP depends on privileges, network namespaces, routing, and
  firewall policy. The Linux VM/container and Rosetta setup cannot provide a
  fixed live result, nor can arbitrary native hosts. The Java-compatible TCP
  port-7 fallback treats connection refusal as reachable, so a positive result
  does not establish ICMP availability or an open application service.
- **Decision:** Keep both `InetAddress.isReachable` overloads in
  Milestone 3. Require deterministic public-contract and native-fixture tests
  for ICMP-unavailable fallback, reply handling, immediate/asynchronous refusal,
  timeouts/errors, interface/TTL/family behavior, and cleanup. Fixtures control
  syscall and clock/wait outcomes only in test builds; no production hook or
  instrumentation overhead is introduced. Preserve best-effort semantics and
  refusal-as-reachable rather than changing behavior to obtain stable live tests.
- **Live verification:** Make actual probes opt-in host smoke checks using
  loopback or controlled targets. Exclude live boolean assertions from default
  compiler/platform, package/IDK smoke, and hosted release gates. Record host
  context and observed outcomes, distinguishing unavailable/inconclusive
  coverage from verified behavior. Do not elevate privileges, alter network
  configuration, require an echo daemon, or infer ICMP success from `true`.
  Crashes, hangs, leaks, and demonstrated contract violations still require
  investigation. Java live results are observations, not deterministic oracles.
- **Consequences:** The complete API and deterministic tests remain Milestone 3
  requirements. Environmental inability to exercise live ICMP does not block
  TCP applications or `wget`; those applications connect directly without a
  reachability precheck. Smoke observations cannot replace the required tests.


## D157 - Scope the downloader as a project with private URL and HTTP policies

- **Status:** Design accepted 2026-09-14 with the
  [downloader contract](NETWORKING_MIGRATION_PLAN.md#downloader-application-and-protocol-contract).
  Milestone 4 implements CONNECT; Milestone 6 implements the downloader under
  D166. The current selection is recorded in D151/D166. Refines Milestones 4 and 6 without
  superseding D151-D156 or introducing a public URI/HTTP framework.
- **Context:** An HTTP downloader needs authority parsing and relative-reference
  resolution even without `java.net.URI`. Redirect and response framing defaults
  also affect correctness. The application belongs under `projects/` according
  to AGENTS.md, while focused socket demonstrations belong under `examples/`.
- **Placement and parser:** Create `projects/wget/` in Milestone 6 with the
  established source layout, README, compile/link/run scripts, focused tests,
  and ignored `target/` output. Implement private owned URL/reference helpers
  from RFC contracts under the original source license. Use RFC 3986 resolution
  against the current URL, preserving query distinctions and encoded delimiters,
  plus HTTP fragment inheritance. Accept ASCII HTTP(S) URI input with DNS/IP
  authorities; no implicit IRI/IDNA conversion, userinfo, or browser recovery.
  Keep fragments off the wire and reject invalid/unsupported targets before
  connection. No OpenJDK URI translation or public `ironwood.net.URI` is needed.
- **Redirects:** Follow 301/302/303/307/308 as GET for at most 20 hops, with one
  valid Location per redirect. Allow cross-host redirects and HTTP-to-HTTPS;
  reject HTTPS-to-HTTP with no first-phase override. Recompute Host and TLS
  identity per hop, scope proxy credentials to the proxy, and close each old
  connection. Copy the next owned URL before reclaiming old URL/header storage.
  Output comes only from the final successful response; later failures may
  leave partial output and must report failure.
- **Responses:** Consume bounded informational responses before the final
  response, including unexpected and unknown 1xx; reject 101 upgrades. Select
  no-body semantics before framing. For body-bearing responses, reject TE+CL;
  TE takes precedence and never falls back to CL. Support chunked alone, exact
  valid CL, or close delimiting when neither field is present; reject unsupported
  transfer codings and malformed/conflicting lengths. Consume bounded chunk
  extensions/trailers without letting them change framing. Content decoding
  remains identity-only and separate. The plan specifies metadata/interim
  limits, error handling, and the single response-head deadline.
- **CONNECT boundary:** Milestone 4 handles informational heads and its final
  2xx tunnel transition independently of origin-response body framing. Ignore
  length fields on successful CONNECT and preserve buffered tunnel bytes. Do
  not make proxy support depend on the later private URL parser.
- **Verification:** Use local scripted peers and RFC resolution cases for
  relative redirects, downgrade rejection, per-hop TLS identity, interim heads,
  TE/CL precedence, chunks/trailers, incomplete responses, and CONNECT handoff.
  Verify the project workflow, binary stdout, streaming allocations, and cleanup
  through relocated packages. No live internet or new public URI API is needed
  for acceptance.

## D158 - Bound TLS trust, revocation, and session behavior

- **Status:** Design accepted 2026-09-14 with the
  [TLS scope](NETWORKING_MIGRATION_PLAN.md#tls-client-scope-and-exclusions).
  Milestone 5 is implemented under D165; Milestone 6 was subsequently selected under D166.
  The current selection is recorded in D151. Refines Milestones 5 and 6 and
  D153's optional adapter/dependency design without superseding D151-D157.
- **Context:** A bundled CA set alone does not define revocation checks, ambient
  trust discovery, or session reuse. These are independent product choices;
  OpenSSL defaults and the optional-link mechanism do not establish the contract.
- **Revocation:** Exclude CRL checking and online/stapled OCSP validation, with
  no revocation fetches, refresh, or cache. Keep certificate-chain, validity-time,
  server-purpose, and hostname/IP verification mandatory. A successful handshake
  does not establish revocation status and may accept an otherwise valid revoked
  certificate. Root-bundle maintenance is not a replacement for revocation checks.
- **Trust:** Use only bundled roots or an explicitly supplied custom CA bundle
  replacing them for that client. Invalid, empty, or unreadable custom bundles
  fail without fallback. No automatic merge, system/JVM/OpenSSL default trust
  discovery, environment-selected roots, ambient configuration, or automatic
  refresh. Callers may explicitly supply a combined bundle. Bundled-root changes
  require rebuilding/relinking; D153's SDK override is a build-time input, not
  runtime trust discovery. No verification-bypass option is introduced.
- **Sessions:** Exclude session resumption and early data. Use a fresh native
  connection object, disable session caching, and do not save or install sessions
  across connections. Peer-issued TLS 1.3 tickets may be processed during the
  connection but must not survive its cleanup or be reused. Every new connection,
  including a same-host redirect, pays for a full authenticated handshake.
- **Consequences and verification:** Apply the same policy to `TlsClient` and
  `projects/wget`, with unsupported configuration/session APIs omitted. Verify
  root replacement and rejection without fallback in isolated fixtures, absence
  of revocation fetches, and actual full TLS 1.2/1.3 handshakes despite offered
  tickets, including allocation/cleanup checks. Future additions require an
  explicit scope and policy decision.

## D159 - Name explicit proxy credential factories as Ironwood extensions

- **Status:** Design accepted 2026-09-14 with the
  [credential extension contract](NETWORKING_MIGRATION_PLAN.md#ironwood-proxy-credential-extensions).
  Milestone 4 was separately selected on 2026-09-16 and implements these factories;
  the current selection is recorded in D151/D164. Refines D155's NP10 and D154's
  retained-input ownership without superseding D151-D158.
- **Context:** Java's `Proxy` has no credential-bearing constructor. Omitting
  `Authenticator` while promising authenticated proxies requires an explicit
  replacement API. Explicit configuration is a product choice, not a language
  prohibition on implementing callbacks.
- **API:** Add original static factories on `ironwood.net.Proxy`:
  `socks5(InetSocketAddress, byte[], byte[])` and
  `httpConnectBasic(InetSocketAddress, byte[], byte[])`, both returning `Proxy`.
  Label them Ironwood extensions throughout the API/member matrix. Inputs are
  username/password octets with protocol-specific validation and no implicit
  transcoding. Keep `Socket(Proxy)` as the connection entry point and reuse the
  representation in `TlsClient` and `projects/wget`. Ordinary proxies remain
  credential-free; SOCKS4 user IDs retain their separate protocol meaning.
- **Ownership:** Factories return fresh immutable proxy graphs owning copies of
  endpoint and credential data. Connections copy retained configuration so the
  source proxy can be reclaimed independently. Helpers borrow connection-owned
  bytes only for negotiation. Expose no credential getters/setters or global
  cache; do not include credentials in rendering or diagnostics. Account for
  copies and failure cleanup without per-I/O allocation or lookup.
- **Authentication scope:** Require SOCKS5 username/password when configured.
  Explicit Basic credentials produce `Proxy-Authorization` on the initial
  CONNECT request to that proxy only; 407 fails without prompting or retry.
  Never forward proxy credentials to the origin. Omit `Authenticator` and
  `PasswordAuthentication` rather than exposing incomplete Java callbacks.
- **Milestones and verification:** Reserve names and ownership in Milestone 1;
  deliver and test the factories/protocols in Milestone 4. Verify wire bytes,
  invalid-input boundaries, absent fallback, credential scope, independent input
  reclamation, allocation counts, and excluded API diagnostics; reuse the path
  for TLS and downloader checks in Milestones 5 and 6.

## D160 - Declare networking reference bounds without disabling primitive options

- **Status:** Design accepted 2026-09-14 with the
  [networking bound rules](NETWORKING_MIGRATION_PLAN.md#generic-parameter-bounds).
  Milestone 1 implements primitive option specialization; Milestone 3 enumeration
  was selected on 2026-09-15 and is implemented. Later APIs remain unselected. Applies the existing
  D063/D112 distinction to the planned APIs and refines D152/D154 without
  superseding those decisions or changing the language's generic rules.
- **Decision:** Declare `ironwood.util.Enumeration<E extends Object>` and matching
  reference bounds on generic snapshot/cursor implementations and helpers.
  Audit public/private type, method, and constructor parameters representing
  reference-only data, including collection and pool elements. Preserve narrower
  reference bounds where applicable. Primitive arguments must fail at the
  caller's type or invocation site, not inside a specialized library body.
- **Primitive options:** Keep `SocketOption<T>` and generic option methods
  unbounded across facades, overrides, and delegation. Their values remain
  primitive; option-token objects and inventory descriptors remain references.
  Do not constrain the existing general-purpose `Iterator`/`Iterable` interfaces
  merely because networking adapters use reference elements. A reference bound
  does not establish ownership or replace borrowed-result safety proofs.
- **Milestones and verification:** Freeze the declaration matrix in Milestone 1
  alongside the boolean/int option-dispatch tests. On introduction in Milestone 3,
  verify reference enumerations, rejection of primitive enumeration/helper
  arguments at caller sites, and preserved bounds through class/archive inputs.
  No runtime checks, boxing, or new generic mechanism are required.


## D161 - Prove the representative TCP graph with compile-time ownership facts

- **Status:** Implemented under the explicit Milestone 1 selection. Refines
  D107 and D154's source-proved ownership cases without changing their caller
  contracts or D132/D133's valid-path performance requirements. The M1 exit
  review was followed by separate M2, M3, M4 and M5 selections; Milestone 6
  was subsequently selected under D166.
- **Dispatch:** Use actual typed closed-world target sets for reference and
  primitive effects. Refine owned-field and return summaries to convergence.
  Preserve constructor-input field identity through delegation. A return joining
  a process value and an owner-dependent view remains conservatively dependent;
  unknown/non-return publication still prevents affected reclamation. Preserve
  existing audited data-structure receiver effects when a primitive wrapper
  delegates through a reference-returning method; custom overrides retain their
  own effects.
- **Fresh results:** Prove bounded fresh wrapper factories with encapsulated
  private final borrows, including helper/interface forwarding. Preserve the
  snapshot root when a cursor is freed. Prove fresh distinct reference-array
  elements only with immediate exact-slot detachment, including every duplicated
  cleanup path. Array destruction remains shallow; missing-free diagnostics
  remain separate from safety enforcement.
- **Collections:** Keep D107 loans for borrowed list entries and caller insertion.
  A source-validated ArrayList get can preserve a single known lifetime root
  through an unexposed exact list. An owned read-only inventory may borrow its
  owner's private final backing list only with validated construction, explicit
  view-before-list destruction and matching reverse-layout rollback order.
  Publication, multiple unknown roots, wrong cleanup order and altered factory
  effects do not acquire the proof.
- **Native boundary:** Typed TCP operations return primitive progress/status plus
  captured error and borrow caller buffers only for the call. Original POSIX TCP
  code is isolated from the core/casing components and future TLS. Omit guards
  only for types whose initialization and all required prerequisites have no
  work; retain all observable initialization ordering/failure behavior.
- **Shutdown compatibility:** A native ENOTCONN during shutdown completes that
  direction successfully, including after bilateral EOF on macOS. The facade
  still rejects unconnected, closed and repeated-direction calls. Other native
  errors retain their captured status. This follows observed Java 21 behavior
  and adds no work to successful I/O paths.
- **Evidence:** [Milestone 1 verification](STDLIB_N1_VERIFICATION.md) contains
  accepted and rejected source cases, allocation failure/cleanup probes, archive
  reconstruction, exact graph costs and optimized/native-call measurements.
  No ownership registry, per-call bookkeeping, boxing, property subsystem,
  selector infrastructure or TLS dependency selection is introduced.


## D162 - Preserve copying constructor and fresh resolver-result proofs

- **Status:** Implemented within the separately selected networking Milestone 2.
  Refines D161's compile-time effect precision and D154's actual resolver results;
  no earlier ownership contract or safety requirement is superseded.
- **Construction:** Use the resolved typed constructor target when deriving
  escape and symbolic return-origin effects. Copying an input does not publish
  that input. Unknown construction retains conservative effects; selected
  retaining/publishing overloads still prevent unsafe reclamation. Captures and
  enclosing-instance lifetimes retain their existing checks.
- **Bulk results:** A direct array-element assignment from an already-proved
  fresh factory may establish a distinct fresh element just as a direct `new`
  does. This does not assume arbitrary calls are fresh. Cached/published results,
  duplicated aliases, borrowed replacement slots and uncertain detachment remain
  rejected. Ordinary arrays still require separate element and shallow cleanup.
- **Boundary:** Resolver acquisition, primitive result iteration and release are
  typed operations. Native OS storage is released on success and failure;
  managed names and snapshots have source-proved owners. No ownership registry,
  runtime misuse checks or per-operation bookkeeping is added.
- **Evidence:** [M2 verification](NETWORKING_M2_VERIFICATION.md) records positive
  and negative source tests, controlled resolver results, allocation-failure
  cleanup, local platform coverage and preserved optimized TCP I/O. This decision
  does not select host interfaces, reachability, proxies, TLS or the downloader.

## D163 - Prove flat host snapshots and nullable borrowed traversal

- **Status:** Implemented under the separate networking Milestone 3 selection.
  Refines D154/D161/D162 without superseding their ownership contracts. D156's
  deterministic versus live reachability split and D160's reference bounds stay
  in force. Milestones 4 and 5 were subsequently selected under D164/D165;
  Milestone 6 was subsequently selected under D166.
- **Contained storage:** A private final creation array may own fresh helpers
  whose encapsulated backlinks borrow the surrounding flat graph. Validate the
  actual constructor, every element write, and the explicit canonical destructor
  loop. A direct indexed getter lends an element under the storage owner;
  arbitrary loads, duplicate entries, publication and hidden-owner exposure do
  not gain this proof. Ordinary arrays remain shallow.
- **Fresh lists:** Prove canonical bounded ArrayList population using borrowed
  receiver entries and explicit failure cleanup. Count/capacity calls must be
  resolved non-retaining int queries on this; primitive getter indices must be
  simple reads or literals. Pre-sizing avoids transient backing-array growth.
  Preserve ordinary caller-insertion loans and conservative exposed/mixed-root
  collection behavior. There is no API-name ownership exemption.
- **Nullable joins:** Null contributes no owner. Preserve a common dependent
  borrow and a single known root when its normal or exceptional join adds only
  null. Fresh, unknown or conflicting roots retain conservative rejection.
- **Host boundary:** Original getifaddrs capture/index, primitive output fields,
  live ioctl/hardware queries and bounded-stack ICMP/TCP probing are isolated in
  ironwood_host.c. Scope copies own independent flat graphs. One monotonic
  deadline covers the probe and fallback; both immediate and asynchronous TCP
  refusal mean reachable. No production test hooks, ownership registry, runtime
  tags, reference count or per-operation bookkeeping are introduced.
- **Evidence:** [M3 verification](NETWORKING_M3_VERIFICATION.md) records actual
  public/native tests, constructor-publication negatives, allocation failures,
  bounds through archives, optimized code and native-call workloads. Live probe
  availability is not inferred from deterministic tests or a positive boolean.


## D164 - Implement explicit proxy snapshots with negotiation confined to connect

- **Status:** Milestone 4 separately selected by the maintainer on 2026-09-16.
  Implements D151's fourth milestone, NP8-NP10 and D159 without superseding
  their compatibility, ownership or authentication requirements. Milestone 5
  was subsequently selected under D165; Milestone 6 was subsequently selected under D166. Evidence is in
  [NETWORKING_M4_VERIFICATION.md](NETWORKING_M4_VERIFICATION.md).
- **Public values:** Independent Proxy/Type/NO_PROXY and Socket(Proxy) preserve
  route equality/hash and overridable type/address contracts. Proxy owns copied
  endpoint and credential bytes; Socket owns another configuration copy. No
  credential getter, selector or ambient authentication is introduced. Explicit
  version selection uses Proxy.SocksVersion and Proxy.socks; Proxy.socks4 adds
  a distinct copied user ID, defaulting to empty and rejecting NUL.
- **Configuration policy:** Default V5 offers only no-authentication; explicit
  socks5 credentials require RFC 1929. Failed V5 never retries V4 or direct.
  V4 requires resolved IPv4. HTTP Basic encodes exact validated octets once in
  the initial CONNECT request; 407 fails without retry. Explicit proxies apply
  to loopback and ignore properties, environment and PAC. NO_PROXY honors the
  socket factory; explicit HTTP/SOCKS select the built-in proxy transport.
- **Setup boundary:** One monotonic deadline covers elapsed synchronous proxy
  DNS, native connect and negotiation. A call-scoped helper owns one reusable
  512-byte scratch array and a private primitive handle lent by the live socket.
  It neither closes nor retains the socket's managed descriptor. This source
  structure proves confinement with existing ownership analysis; it does not
  grant unknown effects a borrowing exemption or change reclamation semantics.
  Resolved proxy endpoints are borrowed without another temporary copy.
- **HTTP tunnel boundary:** Original CONNECT-only parsing enforces the reviewed
  64 KiB aggregate head and 16 informational-response limits, rejects 101 and
  informational body framing, then ends at the final 2xx terminator. A typed
  PEEK_BYTES operation, with ordinary null/range checks, maps to recv(MSG_PEEK).
  Only parsed header bytes are consumed, so coalesced tunnel bytes stay in the
  kernel. Negotiation scratch is reclaimed before connect returns. There is no
  retained prefix buffer, body decoder or new work on established TCP I/O.
- **Provenance:** Only SocksProtocol is derived from the pinned Classpath-covered
  SocksSocketImpl wire algorithm. Public Proxy/Socket behavior, ownership,
  deadlines, HTTP CONNECT, native operations and tests remain independent or
  original source. The full upstream header, immutable pin, source ledger and
  notices ship with the derived helper.
- **Verification:** Actual configuration-copy mutation/publication negatives,
  source/class/archive callers, typed options, exact wire bytes, absent property
  keys, native errors, deadlines, descriptor cleanup, allocation limits and
  optimized machine code gate delivery. This M4 selection included no TLS
  adapter or downloader framework; TLS was subsequently selected under D165.
  No runtime ownership tracking or per-operation proxy bookkeeping is added.


## D165 - Implement the scoped verified TLS client and optional static dependency

- **Status:** Milestone 5 separately selected by the maintainer on 2026-09-16.
  Implementation and verification completed on 2026-09-17.
  Implements D151/D153/D158 without superseding their policy or ownership
  requirements. Milestone 6 was subsequently selected under D166; the N1 event loop remains unselected. Focused
  verification is recorded in [NETWORKING_M5_VERIFICATION.md](NETWORKING_M5_VERIFICATION.md).
- **API:** Original `ironwood.net.tls.TlsClient` owns one concrete native transport,
  copied proxy/CA-path configuration and its TLS state. NativeSocketImpl.forProxy
  selects the existing built-in protocols without the global Socket factory.
  Lazy stream views borrow the client; stream close closes it. Close precedes
  managed destruction. Connect can separate routing from the verified identity.
  ASCII DNS labels/A-labels exclude a trailing dot; numeric identities use IP
  verification without SNI. No JSSE classes or implicit IDN conversion are added.
- **Failure:** One connect deadline includes synchronous trust loading, DNS,
  proxy setup and TLS handshake. The synchronous local configuration/resolver
  calls charge elapsed time on return. Read/write deadlines cover retries.
  Failed connects, record failures and operation timeouts close the connection.
  Close attempts one nonblocking notification without waiting for the peer,
  then releases TLS state and the descriptor, preserving primary failures.
  Ordinary thrown exception objects retain the existing D154/MEMORY lifetime.
- **Native boundary:** Typed TLS operations survive specialization and pruning.
  Their bridge is nested privately inside TlsClient, so same-package application
  code cannot call raw-handle operations. Existing visibility rules enforce this.
  A small immutable requirements value selects the isolated original adapter
  only at final native link. No OpenSSL includes leak into shared TCP/runtime
  headers, and no runtime feature lookup or ownership bookkeeping is added.
  Valid scalar/bulk I/O allocates no Ironwood managed objects or adapter heap
  buffers; native OpenSSL record-framing allocations are measured separately.
- **Dependency:** Pin OpenSSL 3.5.8 and the 2026-08-13 Mozilla CA export by SHA-256,
  LLVM 23.1.0, Perl 5.32.1, GNU Make 4.4.1 and Python 3.14. Use the builtin default
  provider with no shared modules, config autoloading, engines or threading.
  The configuration matches the current single-thread execution model. Linux
  compilation and linking use the matching glibc 2.17 sysroot. macOS uses the
  selected recorded Apple SDK and deployment target 11.0. The compiler validates
  SDK inputs and never downloads or searches ambient libraries.
- **Distribution:** Source/tool packages carry the adapter, recipe and pins.
  IDKs additionally carry the separately built SDK, source archives, CA original
  and generated data, license texts and checksum manifest, with distinct TSV
  entries. TLS SDK discovery uses the explicit override or distribution-relative
  prefix. License/provenance boundaries for the existing IP/SOCKS helpers remain
  unchanged; no OpenJDK TLS implementation is imported.

## D166 - Select the private streaming HTTP/HTTPS downloader

- **Status:** Milestone 6 separately selected by the maintainer on 2026-09-17;
  completed on 2026-09-18 with [focused evidence](NETWORKING_M6_VERIFICATION.md).
  Implements D151/D157/D158
  without superseding their protocol, trust, ownership or performance rules.
  N1's event-loop phase remains unselected. Work stays local on
  `socket-tcp-support`, without pushing.
- **Scope:** Original `projects/wget` private URL/reference and HTTP/1.1 helpers,
  bounded redirect and response processing, streamed binary file/stdout output,
  explicit SOCKS4/5 and CONNECT configuration, and verified TLS through M5.
  No public URI/HTTP framework, connection pool, cookies, resume or HTTP/2.
- **Prerequisite:** Expose the existing address literal parser through the
  distinctly documented Ironwood extension `InetAddress.parseLiteral(String)`.
  It returns a fresh literal value or null for a nonliteral, without DNS, and
  retains NP3 rejection and copied-message behavior. This avoids a second IP
  grammar in the application; it does not broaden the derived helper boundary.
- **Gate passed:** Local RFC/protocol fixtures, strict ownership and allocation checks,
  native-call/optimized-code evidence, project workflows, and relocated package
  and IDK checks on macOS ARM64 and both Linux targets, including GLIBC <= 2.17.
  Completion ends the blocking migration only; N1 remains pending.

## D167 - Stage NIO TCP channels and selectors as one public capability

- **Status:** Roadmap staging accepted on 2026-09-18. NIO1 through NIO4 remain
  pending and unselected. This refines D151's second phase without superseding
  its N1 completion gate, the completed D166 blocking migration, or the
  exclusion of language threads. Planning does not authorize implementation.
- **Context:** Public non-blocking sockets alone would leave general-purpose
  applications to probe every connection. The intended Java NIO model combines
  channels with selectors, while Ironwood must prove explicit reclamation of
  keys, registrations, views, and attachments and retain its `ironwood.ds`
  collection policy. Those contracts need review before a public API is fixed.
- **Decision:** Follow the [NIO networking roadmap](NIO_NETWORKING_PLAN.md):
  NIO1 reviews contracts, ownership, sources and backends; NIO2 implements the
  private native foundation; NIO3 delivers TCP channels and selectors together;
  NIO4 completes multi-client acceptance and delivery evidence. Do not publish
  a channels-only milestone. Existing blocking sockets retain their behavior.
- **Compatibility boundary:** Target Java SE 21 behavior for the selected
  surface. Resolve hierarchy, overloads, key-set semantics, cancellation,
  close/reclamation, provider and thread-related omissions explicitly under
  `OPENJDK_PORTING.md`. This decision does not pre-approve a reduced contract,
  a new collection framework, a native backend, or runtime safety overhead.
- **Exit and scope:** Keep N1 pending until the multi-client gate demonstrates
  progress with stalled peers, partial-write backpressure, safe cleanup, and
  focused allocation, optimized-code, native-call, platform and distribution
  evidence. File/datagram/asynchronous channels and non-blocking DNS/proxy/TLS
  are separate future scope, not implicit additions to this TCP plan. Each
  milestone requires its own selection after review of the preceding result.

## D168 - Plan explicit block-scoped defer

- **Status:** Detailed plan reviewed on 2026-09-19. The call checkpoint was accepted
  and Stage 2 selected explicitly. Both Milestone 1 stages were implemented and
  reviewed. Milestone 2's performance stage and focused example are accepted.
  SimpleTcpEcho adoption is verified. The separately selected
  [final audit](DEFER_FINAL_VERIFICATION.md) completes Milestone 2 on
  2026-09-20. Both milestones and subsequent adoption are committed on `main`;
  the audit records the verified integration status.
  Supersedes only D051's requirement to express all guaranteed resource
  cleanup through ordinary `finally` and its
  rationale against adding a separate cleanup construct. Preserves D051's
  rejection of both Java `try (...)` resource forms, separate closure and
  reclamation, and first-exception/ordered-secondary-exception behavior.
- **Context:** Repeated source-written `try`/`finally` blocks obscure ordinary
  application logic when each allocation needs exception-safe reclamation.
  Explicit cleanup decisions should remain visible without requiring another
  level of nesting for each acquisition.
- **Decision:** Reserve one new keyword, `defer`, for explicit block-scoped cleanup.
  A reached operation runs when its enclosing block exits. Operations run in
  reverse order, including on normal completion, return, exception, and crossed
  `break`/`continue`/`yield` paths. Capture the chosen reference or call operands
  when execution reaches `defer`; later local reassignment must not redirect
  cleanup. Attempt remaining operations even when an earlier cleanup throws.
  Preserve ordinary checked-exception analysis and D051 failure order.

  Cleanup stays explicit: `defer free buffer` requests a
  compiler-proven free, `defer client.close()` requests closure, and
  `defer pool.release(message)` requests reuse under the existing pool contract.
  No `scoped` modifier, automatic cleanup of ordinary `new`, privileged
  `AutoCloseable`, implicit ownership transfer, or weakened safe-free proof is
  introduced. Captured references remain visible to lifetime and escape
  analysis until their operations finish. Ordinary `finally` remains supported.
- **Performance gate:** Reuse typed cleanup control flow without a runtime
  action stack, callback allocation, registration calls, or per-operation
  bookkeeping. Acceptance requires allocation measurements, executable text
  sizes, optimized machine code inspection, and deterministic benchmark evidence
  of parity with semantically equivalent
  handwritten cleanup under D132/D133. Any unavoidable performance regression
  requires maintainer review.
- **Plan and scope:** Follow [DEFER_PLAN.md](DEFER_PLAN.md). Detailed syntax
  boundaries, capture rules, two implementation milestones, and verification
  gates are reviewed; both Milestone 1 stages were explicitly selected. Milestone 1 includes an
  internal review checkpoint for the call form and generalized exit machinery
  before adding deferred free; both forms are required to complete the milestone.
  The accepted detailed plan requires rejecting reassignment of a local while its
  deferred free is pending, so that
  form uses the existing binding without another reference capture. Deferred
  calls retain operand value/identity capture; ordinary alias and free proofs
  remain mandatory for both forms. The plan keeps the outer receiver null check
  at invocation time to match cleanup through a saved receiver in ordinary
  `finally`, accepting delayed diagnosis and D051 secondary-exception status
  when another failure is pending. Performance comparisons must preserve that
  timing and distinguish emitted cleanup copies from executed checks.
  The call checkpoint implements dedicated AST/parser support, typed saved
  operands and invocation emission, a shared source-finally/deferred-call cleanup
  stack, pending-capture observers, complete exit integration, and artifact
  reconstruction. Source visitors retain captured-input effects; checked cleanup
  scopes are lexical, and closed-world destructor effects exclude unwind paths
  proved unreachable. [Focused evidence](DEFER_CALLS_VERIFICATION.md) covers the
  original call boundary. Stage 2 adds local-bound deferred frees, compile-time
  pending-write guards, scope-aware alias expiration, and ordinary reclamation
  proofs on all cleanup predecessors. Its [combined evidence](DEFER_FREE_VERIFICATION.md)
  includes negative safety cases in every unfreed mode and artifact reconstruction.
  Milestone 2's [performance verification](DEFER_PERFORMANCE_VERIFICATION.md)
  covers the full Section 8 matrix on macOS ARM64, including allocation counts,
  linked text size, instruction work, and controlled repeated timings. It found
  no lowering regression requiring a compiler change. The separately selected
  [example stage](../examples/deferredcleanup/README.md) demonstrates a temporary
  buffer, separate close/free operations on success and failure, and per-iteration
  pool release. Its strict O3 workflow checks output, order, reuse and live counts.
  The selected [SimpleTcpEcho adoption](DEFER_PROJECT_VERIFICATION.md) changes only
  server cleanup. Focused project, failure-order and allocation checks preserve
  its behavior, and linked text size is unchanged. The final Milestone 2 audit
  is complete. The maintainer subsequently selected broader cleanup
  adoption in examples, projects and documentation on 2026-09-20, excluding
  the root `README.md` and preserving dedicated finally demonstrations and
  handwritten performance baselines. See [migration verification](DEFER_ADOPTION_VERIFICATION.md).
  The maintainer committed that follow-up as `b6ce3a8`, then separately selected
  standard-library and testing-library cleanup adoption. Its
  [verification record](DEFER_STDLIB_VERIFICATION.md) covers retained finally
  semantics, focused regressions and compiled-code comparison. That adoption was
  committed as `bacf9f5` without changing the accepted language or safety contract.
  The implementation and both adoption commits are now on `main`. The original
  `new-defer-keyword` restriction required a stable base and separately directed
  integration; it is historical, not an active override of the main-only workflow.
  D169/D170 record the subsequently selected helper-proof refinements. Their
  [implementation and adoption](OWNERSHIP_HELPER_INVESTIGATION.md#completion-status)
  are complete; no further defer milestone remains.

## D169 - Preserve originating-pool identity across helper calls

- **Decision:** Prove bundled pool checkout/release identity in provisional typed
  SSA and share the proof across escape summaries, symbolic returns, owned-field
  analysis, and final call lowering. Returning a checkout to its exact originating
  pool permits reuse; ownership stays with that pool. The proof must cover every
  bound target and every emitted cleanup copy, including captured deferred operands.
  Unknown identities and retaining implementations remain conservative.
- **Reason:** The networking M1 change to accurate typed call binding exposed a
  missing helper-summary proof. It rejected safe helper extraction while correctly
  rejecting wrong-pool helpers that an earlier compiler accepted. Reverting that
  binding or exempting every `release` would restore unsafe acceptance. A proved
  fresh return must also use non-return publication effects without discarding
  actual input publication. See the [regression analysis and verification](POOL_RELEASE_HELPER_REGRESSION.md).
- **Consequences:** This clarifies the existing fixed-ownership and D168 cleanup
  contracts; it does not supersede them or add ownership transfer. The proof adds
  no runtime bookkeeping or allocation. Shared-analysis changes need paired safe
  helper/inline controls and unsafe publication, identity, dispatch, cleanup, and
  artifact regressions, selected according to affected contracts.

## D170 - Preserve confined temporary borrowers across helper calls

- **Decision:** Derive temporary constructor-borrow and single-root ArrayList
  facts from provisional typed SSA, control flow, and a completed conservative
  summary pass. Require confined uses and destruction or constructor rollback
  on every exit after acquisition, without publication of retained fields during
  destruction. Every lowering copy must agree. Feed these facts into subsequent
  escape and symbolic-return refinement; share constructor
  confinement with owned-field analysis. Final lowering keeps ordinary lifetime
  enforcement. Recompute proofs for source, class, archive, and final-link inputs.
- **Reason:** Safe Socket and wget wrapper helpers were rejected because their
  temporary constructor loans appeared to publish inputs. A networking list
  helper additionally lost the returned entry's original owner. The bounded
  proof distinguishes temporary retention, actual publication, list identity,
  and the returned element's continuing input dependency. See the
  [investigation and implementation evidence](OWNERSHIP_HELPER_INVESTIGATION.md).
- **Consequences:** This refines D096/D107/D163/D168 without superseding their
  safety or cleanup contracts. Unknown or retaining dispatch, exposed borrowers,
  mixed list roots and incomplete cleanup remain conservative in every unfreed
  mode. There is no ownership transfer, implicit reclamation, runtime tracking,
  or lowering instrumentation. Refinement stops before rebuilding unchanged
  summaries; performance validation includes compiler time/RSS, native allocation
  counts, and optimized code. General container analysis is outside this change.

## D171 - Guard profitable regions with fully initialized type-state proofs

- **Decision:** After semantic and mandatory ownership validation, permit bounded
  typed-IR function-body versioning under read-only state-2 guards. The guard
  never invokes an initializer. Preserve the original body for states 0, 1 and
  3; omit ensures only inside regions dominated by the complete-state proof.
  Propagate that proof through guardless clones of existing direct callees.
  Substitute an enum constant's immortal address only with its declaring type's
  state-2 proof and verified immutable publication identity. Normal ensure
  completion, which also permits state 1, supplies no complete-state fact.
- **Contracts:** This refines D133 without superseding D055 initialization timing,
  prerequisites, recursive partial state or cached exception identity. It retains
  D132 exact source traces and zero continuous bookkeeping. Root alternatives
  occupy one source frame; callee versions keep source identity and spans with
  unique native metadata. Validation and source/class/archive reconstruction
  precede specialization, preserving safety diagnostics in every unfreed mode.
- **Bounds:** Use application-independent type, body, call-group and total-copy
  budgets, a static removable-operation estimate, and finite recursive graph
  propagation. Preserve ordinary indirect dispatch. The initial policy and
  evidence are in [IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md#37-guarded-fully-initialized-specialization)
  and [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md). Native code size
  can grow to retain both paths; no universal performance improvement is claimed.
  Threads or suspension would require revisiting the permanence proof. This
  adds no eager initialization, per-operation state, allocation, TLS, PGO,
  global inline-policy change or array/bounds behavior.

## D172 - Resolve native target layout before LLVM assembly and optimization

- **Decision:** At final native link, query the configured Clang with an empty
  C translation unit and the runtime's CPU and TLS SDK/deployment flags. Attach
  its target triple and data layout to a temporary module before `llvm-as` and
  `opt`; retain them through trace finalization and `llc`. Runtime compilation
  and final linking use the same triple. Missing queried specifications and
  conflicting module specifications fail the link.
- **Reason:** An unspecified LLVM layout uses generic ABI alignment, including
  four-byte `i64` alignment. Optimizations can fold field offsets and allocation
  sizes before late native code generation chooses a different layout. Native
  CPU code generation already worked; this decision also supplies the target
  information needed by earlier optimization.
- **Contracts:** Keep class/archive artifacts and raw emitted LLVM target-neutral,
  preserve default versus native CPU selection and TLS minimum-OS behavior, and
  derive layouts from the configured toolchain rather than a host-specific
  constant. This refines the native backend without superseding source semantics,
  mandatory reclamation proofs, D132/D133 or D171. There is no runtime bookkeeping,
  new target CLI, stable external object ABI or universal speed claim.

## D173 - Reject the field-only alias metadata performance experiment

- **Status:** Rejected after Mac and Linux measurement; no Stage 2 production
  optimization is retained. This final disposition replaces the provisional
  uncommitted D173 experiment description; it does not supersede D172.
- **Decision:** Remove the declaring-owner/name TBAA emitter and its dedicated
  typed-field storage-identity helper. Keep the accepted Stage 1 compiler
  behavior. Preserve the evidence and alias/final-observation/safety regressions,
  not the test coupled to the rejected metadata representation. No additional
  final-value propagation was implemented.
- **Rationale:** The initial Linux tail improvement did not repeat convincingly
  in twenty confirmation pairs. Average batch latency was 0.28% higher, p99.99
  was lower in only 10/20 pairs, and earlier throughput elapsed was 0.46% higher.
  Mac results were also mixed. These results fail to demonstrate a repeatable
  benefit; they do not prove alias metadata is universally harmful or unsafe.
- **Contracts:** Preserve same-object aliases, inherited/generic storage,
  constructor default observations, recursive initialization and failure timing,
  mutable final-reference contents, pools and mandatory reclamation safety.
  Do not infer receiver `noalias`, pointee immutability or blanket invariant
  loads. D055, D132/D133 and D171 remain unchanged.
- **Evidence:** Focused compiler and OrderBook checks passed on Mac and Linux.
  Machine-code observations, all bounded measurements and the final cleanup
  scope are recorded in [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md).

## D174 - Retain bounded constant enum argument specialization

- **Status:** Accepted Round 2 Stage 3A, independently of selective inlining.
  This does not supersede D171, D172 or D173.
- **Decision:** After initialized-state specialization, clone direct callees
  for existing enum SSA constants, including reference-copy chains. Preserve
  complete signatures and argument evaluation. Dynamic, null, load-derived and
  join-derived arguments keep the original callee; indirect calls are unchanged.
- **Bounds:** Exclude direct-call recursion; allow two clones per target, 32
  overall, original cost at most 256 and additional cost at most 2048 typed
  instructions/terminators. Generated clones are not recursively specialized.
  Retain the measured policy without additional tuning.
- **Contracts:** Preserve initialization states and failure timing, mutable
  field observations, CFG and exception edges, source/trace identity, ownership
  validation and source/class/archive behavior. Add no runtime checks, state or
  profiling. Do not assume mutable enum or pooled-object contents are constant.
  Keep LLVM 23, the existing ordinary inlining policy and D132/D133 unchanged.
- **Rationale:** Proven argument identities expose constant-folding opportunities
  to LLVM without requiring inlining or runtime speculation. The expected
  benefit is supported by the mechanism and independent Mac/Linux gains;
  this is not a guarantee that every specialization makes execution faster.
- **Evidence:** Twelve focused compiler tests and four OrderBook correctness/
  allocation checks passed independently on Mac and Linux. Linux median average
  batch latency improved 4.09% (20/20 pairs lower), p99 improved 5.26%, and
  throughput elapsed time improved 0.49%. Exact source and archive identities
  are recorded in the Stage 3A section of
  [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-3a-retained-enum-argument-specialization).

## D175 - Retain selective inlining with link controls

- **Status:** Accepted. The maintainer delegates the 3B technical decision and
  authorizes its separate commit with the link controls. This supersedes the earlier experimental
  disposition requiring diverse-application evidence before acceptance. D174
  records independently accepted 3A; D171, D172 and D173 remain unchanged.
- **Selective inlining (3B):** Use validated typed CFG/call-graph structure to
  select loop methods of 64-256 operations with 1-4 direct sites, all in callers of at most
  48 operations. Exclude dispatch/lifecycle/entry functions, recursion in the
  direct-call graph and reachability between selected functions. Limit selection to eight functions
  and 4096 estimated copied operations. Emit `alwaysinline` only for that set.
  These bound selection, not final LLVM code size after ordinary optimization.
- **Contracts:** Preserve signatures, argument evaluation, instructions,
  initialization, exception edges, source/probe metadata and mandatory ownership
  validation. Add no runtime state, allocation, guard or profiling. LLVM 23,
  native target configuration and D132/D133 are unchanged.
- **Controls:** Keep the ordinary O3 inline threshold at 1000. Expose link-only
  `--inline-threshold <integer>` for a nonnegative 32-bit budget override, and
  `--selective-inlining=on|off`, default on. Lowering the ordinary threshold
  cannot cancel `alwaysinline`; the separate switch disables 3B while retaining
  3A and initialization-helper inlining. A global increase remains a separate
  performance experiment, not a consequence of seeing a cost above 1000.
- **Independent evidence:** Exact independent source snapshots remain preserved.
  Both passed focused Mac and Linux correctness checks and bounded measurements. On Linux,
  selective inlining reduced median average batch latency by 6.87% across 20
  reversed-order pairs and throughput elapsed time by 5.24% across eight pairs.
  Average latency was lower in 20/20 pairs and elapsed time in 8/8. These are
  workload-specific results; extreme tails remain variable. Details,
  source identities and raw evidence are in the Stage 3 performance report.
- **Combined evidence:** A separately preserved 3A+3B candidate passed 17 focused
  compiler checks and four OrderBook checks on both Mac and Linux. Against 3B
  alone, Linux median average batch latency improved another 5.04% (20/20 pairs)
  and throughput elapsed improved 2.74% (8/8 pairs). No policies were tuned for
  composition. These are incremental measurements against 3B, not a fresh
  original-baseline comparison.
- **General-performance assessment:** Retain 3B alongside D174. Proven enum identities expose
  constants to ordinary LLVM simplification; narrow loop/caller selection
  removes boundaries LLVM's current budget rejects. Neither adds dynamic checks
  or bookkeeping. Together with consistent average gains in the independent
  ARM/x86 experiments and the incremental Linux combination gain, this supports
  a favorable expected tradeoff. This is engineering judgment, not measured
  coverage of all applications or a numerical probability. Confidence is higher
  for 3A; 3B can change register pressure, spills and scheduling adversely, and
  does not prove hotness or useful post-inline simplification. The explicit
  fallback supports investigating such regressions. Code size is not an
  acceptance criterion. Further tests should answer concrete code-generation
  questions, not seek universal performance proof.
- **Deferred:** No source `@Inline` directive. Existing LLVM metadata supports
  this automatic experiment; the current evidence does not establish a need for
  user annotations. Stage 4 application-work changes remain outside this task.

## D176 - Proven bounds simplification and unread primitive stores

- **Status:** Accepted. The maintainer explicitly approved retaining and
  committing both optimizations together after the independent and combined
  Linux review. No D175 eligibility or threshold changes, PGO, source annotations
  or application rewrites are involved.
- **Array bounds:** All array producers enforce lengths in [0, INT32_MAX].
  Lower the bounds predicate as `unsigned32(index) < unsigned32(length)`.
  This is equivalent for every signed int index, including INT_MIN/INT_MAX and
  empty arrays. Preserve the size_t/i64 header layout and all existing null,
  allocation, negative-length, exception and reclamation behavior.
- **Unread stores:** After validation and final-link reachability pruning, remove
  primitive instance-field stores only when no retained typed load observes the
  declaring owner/layout slot. Preserve hidden and inherited storage identities,
  reference/static stores, evaluated operands, receiver checks, calls, exception
  edges, source identity and all object layouts. Do not transform open libraries.
- **Native observers:** Retain fields owned by String, Throwable and PrintStream,
  whose layouts are read directly by the C runtime. Also retain any field whose
  address is passed by a TCP/native instruction. New native field observers or
  future volatile/reflection/FFI features must extend this proof before using it.
- **Safety and scope:** Both changes add no runtime bookkeeping. All ownership
  and destructor checks precede the store pass, including after artifact
  reconstruction. No field-alias metadata or general final-field propagation is
  introduced. The earlier rejected D173 experiment remains rejected.
- **Evidence:** Native Image's optimized no-PGO Linux code exposes the redundant
  checks and unread Order.resting stores. These motivate general compiler rules,
  not source-name special cases. Separate Linux comparisons passed all checks:
  bounds-only average batch latency/throughput elapsed changed -3.09%/-7.32%,
  stores-only -1.13%/-4.75%, and both -0.79%/-7.09%. Gains are not additive;
  bounds-only had the strongest average result. The Mac throughput regressions
  remain recorded. Retention rests on removing proven unobservable work, the
  independently favorable Linux results and the combined gain, not a claim
  that every binary or architecture improves. Code interactions remain a
  performance risk. See PERFORMANCE_IMPROVEMENTS.md for identities, tails,
  pair counts, machine code and the general-performance assessment.

## D177 - Retain initialized enum payload propagation

- **Status:** Accepted as a small Round 2 Stage 4 latency win. The maintainer
  approved retention and a commit after the isolated Linux comparison against
  `9217418`, with unchanged Ironwood and Java application sources. This replaces
  the provisional experiment status and retention restriction.
- **Proof:** In existing state-2 fast paths, substitute exact enum final
  `int`/`long` payloads established by literal arguments, bounded constructor
  evaluation and exactly-once straight-line construction/publication. Audit
  foreign writes, native field addresses and constructor call sites. Carry
  validated final metadata through typed field construction and generic
  rebuilding; artifact links reconstruct and revalidate source semantics.
- **Boundary:** Decline dynamic arguments, unsupported operations/CFGs,
  constructor delegation and constant-specific subclasses. Fold an accessor
  only when its resolved body returns the exact constant with no effects or
  possible exceptions for that receiver. Preserve constructors, initializer
  execution/publication, original fallback CFGs, caller checks, operand
  evaluation, layouts, source spans and all mandatory ownership proofs.
- **Relationship:** Extend guarded enum identity propagation without changing
  its guard/selection policy or D174/D175 specialization and inline controls.
  This narrowly extends the earlier payload-load retention policy; it does not
  revive rejected D173 alias metadata or supersede D176 safety constraints.
  No new runtime guards, bookkeeping, PGO or application-specific rules.
- **Evidence:** Eleven focused compiler tests and both variants' four OrderBook
  correctness/allocation checks passed on Mac and Linux. Linux O3 machine code
  removes the targeted enum-field loads. Median paired average batch latency
  and p99 changed -1.98% and -2.02%, each lower in 19/20 pairs and in both
  execution-order groups. This supports a modest workload-specific latency
  benefit; throughput and extreme tails do not establish a consistent gain.
  See [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-retained-enum-field-propagation)
  for exact identities, protocol and aggregation limits. No fresh Native Image
  comparison or general speedup is claimed. Value propagation/load forwarding
  was subsequently retained in D178; the bounded overwritten-store experiment
  was subsequently rejected in D179.

## D178 - Retain exact field value forwarding

- **Status:** Accepted as a Round 2 Stage 4 latency and throughput improvement.
  The maintainer approved retention and a commit after the independent Linux
  comparison against `9217418`, excluding D177 from both measured snapshots.
  This replaces the provisional experiment status and retention restriction.
  The existing checkout retains D177 and passed separate composition checks;
  combined performance remains unmeasured. Application, runtime and library
  sources remain unchanged. Overwritten-store elimination is still a separate
  pending task.
- **Proof:** Cache integer/reference values by exact receiver and declaring
  owner/layout slot after a typed load, store or supported leaf call. On every
  slot write, invalidate facts for all possible receiver aliases before recording
  the exact new value. Carry facts along single-predecessor paths only. Joins,
  loops' entry merges and exceptional edges begin empty. Do not infer pooled
  object freshness, array-element identity, receiver disjointness or final-field
  stability. Floating-point, array and static values are not forwarded.
- **Effects:** Unknown calls, native operations, initialization and reclamation
  are barriers. Bounded acyclic leaf getter/setter proofs admit only parameter
  copies, receiver checks, field accesses and normal returns. A getter call can
  disappear only when an exact prior field value proves its result and nonnull
  receiver. Preserve setter calls, original stores, checks and operand evaluation;
  repair exceptional CFG/phi edges when removing a getter invoke.
- **Contracts:** Run after semantic and mandatory ownership validation. Preserve
  layouts, source identity, lazy/recursive/failed initialization and artifact
  reconstruction. Add no runtime state or checks, alias metadata, language syntax
  or safety exemptions. This extends the optimization pipeline without
  superseding D132/D133, D171, D174-D177 or reviving rejected D173 metadata.
- **Verification:** Twelve focused compiler tests passed in the Mac checkout
  composition; ten passed in the independent Mac and Linux candidate snapshots.
  Coverage pairs exact forwarding with possible-alias writes, unknown effects,
  loops, nulls, exceptional cleanup, generic/inherited/hidden storage, reuse and
  safe/unsafe reclamation in every mode. Both variants passed four unchanged
  OrderBook correctness/allocation checks and produced identical deterministic
  reports. O3 ARM and x86 code removes redundant pool-counter memory reads.
- **Evidence:** All 56 Linux timed process records and source/binary identities
  passed the audit. Median paired average batch latency, p99 and p99.9 changed
  -4.93%, -5.08% and -3.58%, each lower in 19/20 pairs. Throughput elapsed time
  changed -3.32%, lower in 7/8 pairs. Central latency and throughput gains appear
  in both execution-order groups; extreme tails remain mixed. These results
  support retention on this workload and host, not a universal speedup or an
  additive gain with D177. No new Native Image comparison is claimed. See
  [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-retained-field-value-forwarding)
  for exact identities, protocol and aggregation limits.

## D179 - Reject the bounded overwritten-field store experiment

- **Status:** Accepted negative disposition after the independent Linux review.
  Remove the uncommitted pass, its final-link registration, dedicated tests and
  fixture. Keep the frozen candidate patch and evidence in the Stage 4 workspace.
  D177 enum-field propagation and D178 value forwarding remain retained.
- **Scope:** Compare independently against `9217418`, excluding D177 and D178
  from both snapshots. The candidate removed earlier instance-field writes only
  after proving an exact receiver/storage-slot overwrite before any observation
  or exceptional exit. Possible-alias reads and uncertain control flow or effects
  ended the proof. Application, runtime and library sources stayed unchanged.
- **Evidence:** Fifteen focused compiler checks passed in the local composition;
  ten passed in the independent local and Linux candidates. Both variants passed
  unchanged OrderBook correctness/allocation checks and deterministic reports.
  The pass eliminated stores in regression fixtures but none in OrderBook.
  Compiler LLVM, optimized LLVM and native instruction streams were identical
  across variants on ARM and x86. Linux executable differences were confined to
  trace metadata. The unchanged-code gate correctly skipped timing; there is no
  measured speedup or measured zero-percent result.
- **Rationale:** Correctness alone does not justify retaining an additional pass
  for this performance target without generated-code benefit. Broader proofs
  across calls or uncertain aliases would require a separate experiment; this
  result does not establish that all dead-store optimization is exhausted.
- **Contracts:** Removal returns compiler/test sources to accepted `85288ab`.
  Preserve mandatory ownership validation, checks, effects and layouts. No
  runtime overhead, alias metadata or application-specific rules are introduced.
  This does not supersede D132/D133 or D174-D178, or revive rejected D173 metadata.
  All three planned Stage 4 experiments have now been investigated. Combined
  performance of D177/D178 and a fresh Native Image comparison remain unmeasured.
  See [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-overwritten-store-elimination-experiment)
  for the frozen identities and negative result.

## D180 - Preserve Java assignment failure order and proven destructor receivers

- **Status:** Accepted frontend correctness repair, independent of the Round 2
  performance experiments. Both defects reproduce at `3bb64fc` and remain
  present through the accepted Stage 3 and Stage 4 optimization commits.
- **Simple assignment:** Evaluate an instance-field receiver or array receiver
  and index exactly once, then evaluate the right operand, then perform the
  store's null and bounds validation. A right-side exception therefore wins
  over a null or bounds failure. Static-field initialization remains triggered
  at the eventual store. Preserve the assignment expression's converted result.
- **Read-modify-write forms:** Compound assignments and prefix/postfix updates
  first validate and read their field or array target, then evaluate any right
  operand, and finally write the result. They do not inherit simple assignment's
  deferred validation.
- **Destructor receivers:** Treat the callable's `this` operand and its direct
  reference conversions or SSA aliases as non-null. Do not emit a synthetic
  null-failure path for their field reads. Continue to check genuinely nullable
  receivers, and retain closed-world rejection of destructor allocation,
  escaping exceptions, publication or resurrection of `this`.
- **Proof boundary:** This is not a general nullability analysis. Parameters,
  field values, call results and control-flow joins remain nullable unless their
  existing lowering has a separate proof. The correction removes only failure
  edges contradicted by the instance-call receiver invariant.
- **Safety and cost:** The change adds no runtime bookkeeping or valid-path
  checks and grants no ownership exemption. Mandatory dangling-reference,
  use-after-free and double-free diagnostics remain independent of every
  `--unfreed` mode. Focused regressions execute source, loose-class and archive
  paths at O0/O3 and pair accepted destructor reads with nullable, allocating,
  publishing and use-after-free cases.
- **Verification:** On 2026-09-22, the two registered frontend tests passed on
  macOS ARM64 with Java 25.0.4.1 and LLVM 23.1.0. All source, loose-class and
  archive binaries exited 42 at O0/O3. Six focused adjacent array, destructor,
  runtime failure, field-alias and forwarding tests also passed. O3 disassembly
  places simple-assignment effects before location failure branches, keeps
  compound validation first, and emits the destructor field read without a null
  branch. The full suite was not run.

## D181 - Report the selected LLVM toolchain with the compiler version

- **Status:** Accepted. Supersedes D021's version-command rule that skipped
  LLVM discovery; preserves the identical `--version` and `-v` aliases and the
  embedded compiler version convention retained by D024 and D137.
- **Decision:** Both commands print `ironwoodc <version>`, then `LLVM version:`,
  `LLVM home:`, `LLVM clang:`, and `Clang version:` lines for the validated
  toolchain selected by normal native-link discovery. The home and executable paths are absolute.
  Failure prints `LLVM not found:` with the discovery diagnostic. Version
  commands require no source input and return success even when LLVM is absent.
  The Clang version preserves the first line of that executable's `--version`
  output, including vendor and revision details. A failed or empty Clang query
  reports `Clang version: unavailable:` with a diagnostic without changing
  native-link discovery or the version command's successful exit status.
- **Consumers:** OrderBook's C++ scripts query these labeled lines through the
  installed launcher and use its exact Clang executable in C++ driver mode.
  They fail if discovery is unsuccessful or the compiler predates these details;
  they do not independently search for another installation. IDK launcher
  overrides therefore apply equally to the Ironwood and C++ builds. Package
  version checks validate the additional information and both aliases.
- **Scope:** Toolchain reporting and benchmark build selection only. Native-link
  discovery order, compiler optimization policies, application source, and
  runtime semantics are unchanged.

## D182 - Expose partial inlining as a link-only override

- **Status:** Accepted additive control. Extends D175's link controls without
  changing its threshold or selective-inlining defaults. Supersedes only the
  absence of a user override for the existing O3 partial-inlining policy.
- **Decision:** Accept `--partial-inlining=on|off` with `--link`. Omission retains
  exactly the existing `opt` arguments: `-enable-partial-inlining` at O3 and no
  partial-inlining override at O0/O1/O2. Explicit values pass
  `-enable-partial-inlining=true` or `=false`; they do not select another LLVM
  pipeline or promise a transformation at every optimization level.
- **Independence:** Preserve the O3 ordinary inline threshold of 1000, explicit
  threshold overrides, selective inlining, enum specialization, and initialization
  helper attributes. Disabling partial inlining does not disable ordinary
  inlining. The setting applies at final native link and is not serialized into
  `.ironclass` or `.ironjar` artifacts.
- **Contracts:** Invalid values and compile-only use produce usage diagnostics.
  No application source, language semantics, ownership validation, runtime
  bookkeeping, or default generated-code policy changes. This enables a future
  Linux experiment; it makes no performance claim and changes no benchmark
  configuration by default.

## D183 - Expose optional LLVM optimization reports at native link

- **Status:** Accepted additive diagnostic output. Preserves D175/D182's
  optimization policies and all default tool arguments.
- **Decision:** `--optimization-report <file.yaml>` is accepted only with
  `--link`. Pass `-pass-remarks-output=<absolute path>`,
  `-pass-remarks-format=yaml`, and `-remarks-section=false` to the existing
  `opt` invocation only when requested. Do not add passes, profile collection,
  runtime instrumentation, or report metadata to the native object.
- **Output contract:** Save LLVM's available remarks without a custom reporting
  engine. This excludes `llc` and runtime C compilation remarks and does not
  promise an explanation for every optimization decision. Empty reports are
  valid. Create missing parent directories; overwrite an existing regular report
  file. Reject collisions with executable or emitted LLVM output before writing
  those outputs. Report preparation or LLVM report-writing errors fail linking;
  report existence alone does not imply link success.
- **Compatibility:** No source, artifact-format, ownership, exception, or
  optimization-default change. Existing backend overloads retain their behavior.
  Reporting can cost build time and disk space. Focused regressions compare
  optimized IR, native objects excluding stack-trace probe record ordering,
  execution, and exception traces with reporting on/off, and cover invalid
  arguments and output paths. Probe stripping is test-only; delivered binaries
  retain their original trace data.

## D184 - Add structured notes for rejected reclamation diagnostics

- **Status:** Accepted; superseded in part by D185, under which a located
  warning also carries notes. M1b implements the shared diagnostic API and renderer;
  M1c adds eligibility/readiness notes and a nullable semantic observer; M1d
  adds bounded local evidence; M1e exposes the public option. M2a adds
  selected direct store, known-array-slot, and conditional-reference sites.
  M2b adds selected call operand and constructor sites from final local
  effects, and parameter/current-expression sites for missing identity.
  M2c adds selected container/wrapper relationships, current owner identity,
  dependent-helper sources, and attached-field loads. M2d adds proved
  originating-pool checkout sites and conservative external-release argument
  sites without implying runtime ownership of external objects. M3 adds
  bounded incoming-path alternatives, deferred-action operands, cleanup exit
  context, and loop back-edge notes. Its storage gate selects 65,536-unit
  function and snapshot limits while preserving the separate invocation stop.
  M4a retains bounded analyzer-owned final summary facts and immutable call
  dependencies. M4b renders selected final method and dispatch chains, with
  four-hop and eight-note limits and explicit unsupported boundaries.
  M4c retains first failed predicates in the selected private-field analyzer,
  field-load associations independent of proof identities, and supported
  cross-file field-call chains. M4d locates the selected late owned-element
  predicate and recognized destructor cleanup while preserving the field
  primary and validator ordering. M4e verifies bounded whole-program witness
  storage across refinement, retirement, disabled construction, local-cap
  isolation, and the separate invocation stop. The measured compiler costs
  are recorded in the verification record. M5a verifies legal source, class,
  and archive dependency compilation and linking, exact accepted class,
  archive, and LLVM bytes, and native behavior/reclamation/trace controls.
  M5b publishes the final 65,536-unit function and snapshot limits,
  2,048-unit summary-method and 64-unit fact-chain limits, the separate
  1,048,576-unit invocation stop, and the eight-note/four-hop output limits.
  The final cost comparison and coverage audit are in the verification record;
  supported boundaries remain explicit in the memory guide. This decision
  supersedes no ownership or reclamation decision.
- **Decision:** The boolean option is disabled by default for each compile or
  link invocation, independent of missing-free policy. When enabled,
  it can add immutable `DiagnosticNote` entries to a located eligible error.
  Each note owns its message and optional source/span pair; its source may differ
  from the primary. A warning or primary lacking source or span carries no notes.
  Existing primary message, severity, span, and order remain authoritative.
- **API and text:** `Diagnostic` retains its four- and three-argument
  constructors and primary accessors while adding an immutable note list.
  Full record equality, hashing, and string representation include notes;
  primary-only parity compares primary components explicitly. The formatter
  prints the complete primary block first, then each `note:` and its own
  location block if available. Gutters depend on each location's line number;
  multiline spans display their first line and one caret. No-note output is
  byte-compatible, and the formatter itself omits a final newline.
- **Boundaries:** Notes are compiler-only diagnostic evidence, never proof
  state, runtime bookkeeping, or serialized class/archive data. They do not
  change safety outcomes, generated code, or missing-free handling. The output
  design caps explanations at eight notes per primary and four summary hops;
  bounded collection and truthful omission follow the
  [implementation plan](EXPLAIN_REJECTED_FREE.md). IronDoc continues using the
  shared formatter, while Eclipse and the language server retain their existing
  primary-only behavior until separately enabled with consumer support.

## D185 - Reclaim unnamed temporaries at the end of their full expression

- **Status:** Accepted and implemented through Milestone 4 of
  [TEMPORARIES_RULE_PLAN.md](TEMPORARIES_RULE_PLAN.md). Supersedes, for
  unnamed temporaries only, D027's rule that an ordinary allocation remains
  until a source `free` or process termination, D140's exclusion of implicit
  destruction, and D168's statement that ordinary `new` receives no automatic
  cleanup. Supersedes D184's rule that a warning carries no notes. D005's
  mandatory proof, D083's destructor semantics, D140's default severity and
  coverage, and D145's suppression are unchanged.
- **Context:** Passing a `new`, an array, a concatenation, or a fresh factory
  result straight to a call is the most common allocation shape in Java-shaped
  code. Reclaiming it required binding a throwaway local and freeing it, so the
  default `--unfreed=warn` reported ordinary greetings. A build option that
  inserted frees was rejected: a diagnostic is not a proof, a flag must not
  change program behavior, and libraries would behave according to the
  application's link options.
- **Decision:** An unnamed temporary is a fresh allocation produced while
  evaluating one full expression that, when the full expression completes, no
  local, parameter, field, static, array element, container, pool, pending
  deferred operation, pending result, return value, or thrown exception can
  observe. The producing expressions are source `new`, array creation and
  initializers, dynamic String concatenation results, and proven non-null fresh
  factory results. The full expressions are expression statements, including
  the expression body of a switch statement rule, assignment
  statements, local variable initializers, field initializers, the conditions
  of `if`, `while`, `do`, and classic `for`, classic `for` initializers and
  updates, the source of an enhanced `for`, switch selectors, the operands of
  `return`, `yield`,
  and `throw`, and explicit `this(...)` and `super(...)` invocations.

  At the end of the full expression the compiler reclaims each temporary for
  which the ordinary D005 proof succeeds, in reverse creation order, exactly as
  a hidden local freed there would be; the destructor chain runs. A temporary
  is reclaimed on an exceptional exit of the full expression only if it is
  reclaimed on normal completion and the proof also holds at every point
  where an exception can leave the expression, through a typed cleanup region
  per temporary with no runtime action stack. An allocation that something
  observes at the end of the expression is not a temporary and keeps its
  ordinary finding. An allocation nothing ever observed that the proof still
  declines can never be reclaimed; it is reported at that statement as
  discarded, with the blocking fact as one note. An allocation a local held at
  any point in the statement keeps its ordinary findings instead, even when
  the name was cleared again. The rule holds in every `--unfreed` mode and in
  source, class, and archive links. Naming an allocation is the opt-out: a
  local, field, static, or array element that still holds the object when the
  full expression completes. A name assigned and cleared again inside the same
  expression observes nothing at that point and does not opt out.

  Never candidates: a value that moves on through `return`, `yield`, `throw`,
  a switch selector, or an enhanced-for source; the value bound by a pattern
  condition; a captured `defer` operand; a `toString()` result rendered inside
  a concatenation, which the rendering protocol releases; an argument a callee
  may itself reclaim; and an allocation made inside a conditional, switch, or
  short-circuit expression, whose definition does not dominate the end of the
  statement. A `yield` statement in a switch-expression block is its own full
  expression, so its temporaries other than the yielded value are reclaimed
  when the `yield` completes.
- **Diagnostics:** `Diagnostic` keeps notes for any located primary of either
  severity. The formatter already printed notes for any diagnostic; the
  language server does not read them; existing note-free assertions concern
  explanation-off errors and are unaffected.
- **Implementation:** The safe-free proof is split into a side-effect-free
  probe returning a sealed result, the previous emission, and per-rejection
  renderers with unchanged text. Full-expression scopes register candidates,
  push a cleanup region per candidate nested like constructor rollback, and
  reclaim at the end through the probe and the ordinary emission. The tracker
  consumes reclaimed candidates and records declined reasons. Implementation
  exposed and fixed a pre-existing unsoundness: a synthesized anonymous
  constructor now forwards its parameters to the superclass constructor's
  escape effects, so an argument retained by an anonymous subclass of a generic
  class is no longer freeable.
- **Performance:** The lowering matches the hand-written hidden-local
  `try`/`finally` form in typed IR; at `-O3` a declined temporary followed by a
  call produces machine code identical to the named form, and a reclaimed
  temporary in a hot loop compiles to one allocation and one deallocation per
  iteration with cold landing pads. No bookkeeping is added on valid paths,
  preserving D132 and D133. The plan's deterministic benchmark comparison was
  closed by maintainer decision without a run: the existing deterministic
  benchmarks already reclaim every allocation and contain no temporary the
  rule could affect, and the machine code evidence covers both the reclaimed
  and the declined shapes.
- **Verification:** Registered tests cover the recorded diagnostics of every
  proof rejection, reclaim and keep cases, exceptional paths, hand-written
  parity, provisional and final summaries, anonymous constructor retention,
  every full-expression context natively, transferred values, and loose-class
  and archive reconstruction. The listed proof, explanation, deferred,
  concatenation, destructor, finally, pool, and library tests pass unchanged
  apart from three deliberately updated expectations. The standard-library
  suite, all 73 examples, and the four project suites pass.

## D186 - Track references read from the fields of other objects

- **Status:** Accepted and implemented.
- **Decision:** A reference read from a field of a known allocation other than
  `this` carries the identity the proof can establish. A final encapsulated
  field holds exactly the argument its constructor retained, so the read value
  is that allocation, and freeing it by another name while the read value is
  live is rejected as a live alias. A field the receiver's destructor releases
  is the receiver's own storage, an allocation the analyzer does not know:
  the read value cannot be freed, the receiver cannot be freed while the value
  is held, and a value stored through it escapes. Any other reference field
  yields a value that may be any child the receiver retains (a value stored
  into such a field later has already escaped): while a local, a slot, a
  wrapper, or a pending operation holds it, freeing one of those children by
  name is rejected as a live alias, and once nothing holds it they are
  provable again, so `int tag = l.held.tag; free l; free x;` is accepted.
  The receiver itself keeps its ordinary proof.
- **Rationale:** Such a value previously had no identity at all, so
  `Keeper k = holder.held; free holder; free x;` was accepted and `k` read a
  destroyed object. Review of D185 found it; the fix is compile-time only
  and, for final encapsulated fields, exact.
- **Verification:** `safe free tracks values read from wrapper fields` pairs
  the accepted forms, reads used within a statement, a compared pair, and a
  read whose only other name is the wrapper, with the rejected forms, a bound
  read of each field kind and a store through the owner's storage.

## D187 - Elements loaded with a non-constant index stay observed

- **Status:** Accepted and implemented.
- **Decision:** A reference loaded from a known array with a non-constant
  index is some element of that array: every element the analyzer knew the
  array held at the load, and every element of a known array stored in one.
  It cannot be freed itself: "value is an element loaded with a non-constant
  index and may be any element of that array". While a local in scope, a
  known array slot, a retaining wrapper, a pending deferred call, or a pending
  yield holds the value, freeing an element it may be by that element's own
  name is rejected as a live alias, "allocation may still be observed through
  local 'needle'". Once nothing holds it, as after an enhanced `for` over the
  array or a computed read consumed within its statement, the elements are
  provable again. A value that escapes, or whose identity is lost in a join,
  leaves the elements it may be uncertain from then on. The array keeps its
  ordinary proof, and a constant index still aliases exactly one slot.
- **Rationale:** `Box loaded = values[i]; free values; free box;` was accepted
  and `loaded` read a destroyed object. The proof tracks one allocation per
  identity and has no "one of these" identity, so the loaded value behaves as
  an alias of every element it may be, for exactly as long as something holds
  it. That keeps the common shapes, `parts[a] + parts[b]` and
  `for (String needle : needles)` followed by freeing the elements, accepted.
  Review of D185 found it; the fix is compile-time only.
- **Verification:** `safe free accounts for reference-array element aliases`
  pairs the rejected bound read, the rejected free of the read value, and the
  rejected free inside an enhanced `for` with the accepted constant, computed,
  and loop reads followed by freeing the elements.

## D188 - Java Bridge confinement is a caller obligation

- **Status:** Accepted design contract; the Java Bridge is not implemented.
- **Decision:** The Java Bridge preserves Ironwood's single-threaded model.
  Applications must confine access to a loaded native world to one calling
  thread, including its objects, statics, runtime state, and destruction.
  Multithreaded access is unsupported caller misuse and may produce
  unpredictable behavior or crash the host JVM. The bridge does not detect,
  serialize, or repair it. Do not inject thread checks, per-object/world locks,
  or executor dispatch solely to enforce confinement. Calls execute on the
  Java calling thread; synchronous callbacks obey the same contract and source
  reentrancy restrictions. A multithreaded Java host is allowed, but other
  threads must not enter that native world. Distinct facade objects in the same
  image do not establish independent thread-safe worlds.
- **Consequences:** Generated cleanup must also obey confinement. A Java Cleaner
  thread cannot directly invoke native destruction; any automatic policy needs
  an owner-thread scheduling design and an explicit idle-thread limitation.
  Thread handoff, virtual-thread migration, and asynchronous native callbacks
  are not promised. D189 subsequently selects explicit `free()`.
- **Scope:** Replaces the first bridge plan's candidate requirement to detect
  invalid host-thread entry, not an earlier accepted language decision. Does
  not supersede D132/D133, introduce multithreading, or relax compiler-proven
  reclamation for supported single-threaded code. It grants no exemption for
  dangling aliases, unsafe close, or unknown callback retention within that
  supported execution model.
- **Verification:** Documentation-only decision. Future bridge checks must
  verify calling-thread execution and absence of injected thread-enforcement
  work. Tests must not promise a defined rejection or deterministic crash for
  multithreaded misuse. See [the implementation plan](JAVA_BRIDGE_PLAN.md).

## D189 - Java Bridge native reclamation uses explicit free()

- **Status:** Accepted API decision; the Java Bridge is not implemented.
- **Decision:** Java callers request native reclamation through generated
  `free()`. It invokes an approved native destruction capability on the calling
  thread. Preserve source `close()` semantics; do not add a reclamation alias
  named `close()` or implicitly implement `AutoCloseable` for this purpose.
  Java callers can use explicit calls or `try/finally`.
- **Scope:** Replaces the historical bridge proposal's `close()` choice and
  resolves D188's open explicit-versus-automatic cleanup choice for the initial
  design. Does not supersede native source reclamation rules, D132/D133, or
  D188's thread contract. Exporting an object does not transfer ownership or
  make borrowed, pooled, or immortal objects independently reclaimable.
- **Follow-up:** D190 accepts shared lifetime state, retention accounting,
  callback guards, and their stated boundary costs. D191 settles owner/view
  representation, idempotence, and failure details. No automatic cleanup
  fallback is implied.
- **Verification:** Documentation only. The plan records proposed paired
  ownership and alias tests; no bridge behavior or performance is claimed.

## D190 - Java Bridge combines native ownership proofs with Java lifetime state

- **Status:** Callback-free P3 lifetime implementation and focused macOS ARM64
  checks pass; see [P3 evidence](JAVA_BRIDGE_P3CD_EVIDENCE.md). P5 callback guards
  and final P6 qualification remain pending.
- **Decision:** Combine compiler ownership proofs for the native graph with
  shared Java lifetime state for its facades. Distinguish owned and borrowed
  objects. Refuse independent free of borrowed objects. Freeing an eligible
  owner invalidates its dependent facades; subsequent native access through any
  alias or borrowed view throws before dereferencing dead storage. Account for
  native retention between owners and refuse free while a native dependency
  remains. Guard callback-capable paths against freeing an object needed by a
  suspended native invocation. Reject unprovable export contracts at producer
  build time rather than weakening native ownership analysis.
- **Accepted cost:** Stored Java lifetime state, liveness checks before native
  access, bookkeeping when supported retention relationships change, and
  active-use guards on callback-capable paths. This accepts the host-boundary
  costs proposed in the plan and resolves D189's open enforcement/cost review.
  It is not authorization for arbitrary bookkeeping in ordinary native code,
  global lookups on every scalar call, thread checks, locks, GC, or executors.
  D188's caller-thread obligation remains unchanged.
- **Performance goal:** Scalar JNI calls can be inexpensive. Keep primitive-only
  bridge methods close to the cost of a plain JNI call, with the accepted
  ownership checks intact. Measure JNI transition, conversion, and additional
  bridge work separately. This is a target, not an established timing result.
  D132/D133 remain in force for ordinary native execution; this decision records
  the explicitly approved host-boundary work. Further material costs or
  regressions require review, not renewed approval of these same checks.
- **Follow-up:** D191 settles Java owner/view representation, idempotence,
  exception types, destruction failure transitions, identity mapping, and the
  first-release compiler-proved retention protocol.
- **Verification:** Documentation only. Future focused checks cover safe and
  refused free, aliases, borrowed views, retention changes and exceptional
  rollback, callback reentrancy, and plain-JNI comparisons. No implementation
  or benchmark result is claimed.

## D191 - Java Bridge first-release implementation contracts are settled

- **Status:** Accepted implementation design under maintainer-delegated choice;
  the Java Bridge remains unimplemented. The maintainer explicitly defers
  numerical performance acceptance to the end of implementation.
- **Decision:** Adopt [section 14 of the implementation plan](JAVA_BRIDGE_PLAN.md#14-settled-implementation-contracts).
  Use generated Java 21 facades, isolated C JNI adapters, exact-package exports,
  and automatically loaded single-jar artifacts for Java 21-23. Keep Java 24+
  deferred. First-release platforms are macOS ARM64, Linux ARM64 and Linux
  x86-64, with official IDK native baselines and compatible tested JVM hosts.
- **Lifetime:** One Java class may represent owned and borrowed instances;
  only an eligible root has a destruction capability. Repeated successful
  `free()` is a no-op. Dead native access and borrowed, immortal, retained or
  active free throw `IllegalStateException`; wrong-world arguments throw
  `IllegalArgumentException` (inherited identity equality compares unequal).
  Rejected free leaves the root live. Destruction
  must be proved nonthrowing and callback-free, with LIVE/FREEING/FREED states.
  No automatic reclamation or recovery from partial native destruction is added.
- **Identity and costs:** One live facade per native object, backed by shared
  root state, a world-owned root index and weak facade cache values. Identity
  lookup/creation occurs on object conversion, never on scalar calls. Root
  state survives Java wrapper collection; Java GC never frees native storage.
  Returning an alias of an already-owned object preserves its existing owner
  capability. Borrowed children cannot independently destroy their root.
- **Retention:** Admit fixed, compiler-proved dependency slots and acyclic
  dependencies between roots. Typed entries report actual final slot references
  on successful and exceptional exits; preallocated Java bookkeeping reconciles
  counts before result/error delivery. Reject hidden publication, unrepresentable
  origins, unsafe constructor rollback and unproved cycles. The first release
  excludes Java reentry, so this protocol cannot be bypassed by a callback.
  Native-only execution gains no bridge bookkeeping.
- **Surface:** Constructors, primitive/string methods, concrete facades, enums,
  static nested types, owned/borrowed results and supported exception snapshots.
  Inherited identity equality remains available; arbitrary Java object arguments,
  including source overrides of `equals(Object)`, are deferred. Arrays, exported
  generics, general inheritance, Java subclassing, mutable public fields and
  callbacks are also deferred. Unsupported public signatures fail producer build.
  The actual OrderBook keeps its existing process-lifetime graph; a separate
  owner/child fixture proves reclamation without changing the benchmark engine.
- **Loading:** Support standard class/module paths and executable jars using
  standard dependency loading. Allow multiple distinct artifacts, with one
  defining loader per artifact per JVM. Diagnose duplicate independent loads;
  defer custom nested-jar loaders, relocation, duplicate worlds and hot reload.
- **Milestones and performance:** First release follows P0-P4 then P6; P5/P7
  are later extensions. P0 validates the chosen contracts. Correctness and
  structural performance requirements apply throughout; numerical thresholds
  and measured accept/optimize decisions belong to P6's final release review.
  No numerical threshold is a prerequisite for beginning P1.
- **Scope:** Resolves D189/D190's open implementation details and supersedes
  the plan's earlier requirement to settle a numerical budget before P1.
  Preserves D188-D190's accepted safety, thread and lifetime rules, D132/D133,
  and mandatory native ownership proofs. Callback guards remain required when
  P5 is implemented; callback-plus-retention mutation needs a separate proved
  protocol before it can be admitted. This decision authorizes no source
  implementation, release or new branch by itself.
- **Verification:** Documentation consistency, local links and `git diff --check`.
  Required future proof cases, focused tests and phase exits remain in the plan;
  no compiler behavior, benchmark result or supported feature status is claimed.

## D192 - Java Bridge admits compiler-proved non-reclaimable exports

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** D191 requires the actual OrderBook milestone while the plan's
  blanket unknown-origin/publication rejection provides no route for its pooled
  results and escaping receivers. Process-lifetime intent alone is not a proof,
  and a pool-array result is not automatically a borrow from a known owner.
- **Decision:** Add a closed-world non-reclaimable classification for successfully
  exposed storage. Admit unknown-origin results and receiver publication only
  when every possible dynamic type is proved immortal or non-reclaimable for
  the native world's lifetime. Keep ordinary escape/return summaries conservative;
  this separate bridge-admission fact cannot weaken source free proofs.
- **Proof:** Include all exported roots, call orders, dynamic targets, generated
  adapters, source/deferred free, destructor cleanup, rollback, temporaries,
  pool deallocation and runtime effects. Unknown reclamation effects reject
  the classification. Exclude failed-constructor or other cleanup only with a
  proof that its allocations are unpublished and disjoint from exposed storage.
  Pool reset/reuse is not deallocation; a deallocating pool release is. Enum
  constants retain their existing immortal category, not their heap dependencies.
  Revalidate reconstructed and specialized artifacts including synthesized cleanup.
- **Facades:** Use world-level weak-value identity caching for non-reclaimable
  objects, immutable world identity, no per-object liveness state or incoming
  retention counts, and no generated `free()`. Keep the image alive while Java
  facades can access it. No runtime generation checks, native-object scans or
  scalar-call identity lookups are introduced. Pool checkout obligations remain.
- **Dependencies:** Publication/cycles solely among permanent objects need no
  reclamation bookkeeping. A permanent holder retaining reclaimable storage
  still requires the proved retention protocol, or export fails. Permanent
  self-storage is not an exemption for dangling fields or other unsafe effects.
- **Milestones:** P3 delivers the proof and cache before P4. P4 must export the
  actual `createLimit`, `cancel` and `reduceTo` API through that proof. Retention
  and cross-owner argument misuse checks belong to the separate reclaimable
  fixture: the actual OrderBook has no public method taking an `Order`.
- **Scope:** Supersedes D190/D191's blanket bridge rejection of unknown origins
  and publication only for storage covered by this proof, and amends D191's
  facade and retention rules accordingly. Preserves mandatory native ownership
  safety, D188's threading contract, D189's explicit-free API, and D132/D133.
  No compiler or OrderBook implementation is changed by this decision.
- **Verification:** Documentation consistency and local links, `git diff --check`.
  The plan adds positive/negative cases for permanent pooled results, all
  reclamation paths, unpublished rollback, mixed-lifetime dependencies and
  source/class/archive parity. No bridge execution result is claimed.

## D193 - Java Bridge packages and native registration belong to one artifact

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** Two generated jars can define the same Java binary name. A shared
  loader resolves only one class, and another artifact's `RegisterNatives` can
  redirect that class to the wrong native world despite its facade checks.
  Shared packages also conflict with the supported module-path deployment.
- **Decision:** Each source API package is owned exclusively by one bridge
  artifact. Multiple artifacts must have disjoint generated packages. Signature
  closure cannot silently generate source facades outside the explicit `--export`
  set; diagnose the public member and missing package. Mapped JVM types are
  reused. Artifact-private support has its own generated reserved namespace.
- **Identity:** Embed artifact-generation identity annotations on generated
  classes and a reserved ownership marker in every generated API package.
  Identity covers the producing artifact and complete program generation, not
  merely a shared type or matching API. Platform payloads assembled into one
  artifact share that identity and generation manifest. Package markers detect
  overlap even when public class names differ; class annotations detect mixed
  or stale classes despite a matching package marker.
- **Registration:** Use artifact-specific bootstrap classes/symbols. Before
  any `RegisterNatives`, validate every actual resolved class and marker, its
  defining loader, identity and expected registration descriptors. Inspect
  metadata without initializing facades or entering source-native code. On
  mismatch, throw `LinkageError` before any registration/unregistration, leaving
  an already usable artifact unchanged. Register only the validated class
  objects. Failed registration cannot expose a partially ready artifact or
  clean up another artifact's bindings. Repeated bootstrap is idempotent.
- **Scope:** Amends D191's signature-closure, multi-artifact and wrong-world
  assumptions. The first release has no shared generated parameter types or
  public cross-world argument scenario; reject collisions at bootstrap instead.
  Retain internal world identity and define additional validation when P5 or
  a later shared-type feature needs it. D192's non-reclaimable facades require
  the same registration protection. Checks occur at load time, with no extra
  scalar-call lookup or package registry.
- **Verification:** P2 owns the package/registration gate; P3 extends its cases
  to object facades. Test disjoint packages successfully, overlapping packages
  with matching and distinct class names, both class-path and first-use orders,
  mixed-generation classes, failure before rebinding and preservation of an
  already usable artifact. Module-path overlap must fail before native entry.
  This edit verifies documentation only; no runtime test result is claimed.

## D194 - Java Bridge enum conversion initializes the native declaring enum

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** Native enum storage is immortal but its source fields begin
  zeroed. The public static constant field is published only after construction.
  Mapping a Java constant directly to private storage can silently read defaults,
  such as 0 for `Side.SELL.index()` when the enum method is the first native call.
- **Decision:** Pass a paired, name-mapped constant token through JNI. For every
  non-null enum receiver/argument, the typed native entry ensures the declaring
  enum is initialized, then loads its source-visible public static constant
  field before using the reference. Do not substitute a private `IrEnumConstant`
  address or assume ordinal equivalence. Include the check/load in root and
  specialization analysis; eliminate checks or fold the load only with native
  initialization and publication proof.
- **Thread and failure boundary:** Perform conversion on the Java calling thread,
  within the entry's native exception containment. Java enum static initialization
  performs no native loading, registration or Ironwood initialization. A native
  initialization failure follows ordinary exception translation before the target
  body runs. Preserve D055's recursion and stored-failure semantics. Null enum
  arguments remain null without triggering initialization merely for conversion.
- **Scope:** Refines D191's enum mapping and lazy initialization; D192's immortal
  category proves lifetime only. Preserves D188's thread contract and D193's
  registration preflight. No change to ordinary native enum semantics or runtime
  implementation is authorized by this documentation edit.
- **Verification:** P3 includes fresh-child-JVM tests of an enum method as the
  first native call, asymmetric enum-argument mapping, constant-specific bodies,
  nulls, initializer failure/repeated failure and Java-only initialization on a
  different thread before native entry. Check typed IR, O0/O3 and artifact parity.
  Documentation checks only are performed now; no bridge test result is claimed.

## D195 - Java Bridge exception translation is scheduled before release

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** D191 requires native-to-Java exception mapping for release, but
  the phase table groups native snapshots with optional P5 callbacks. Native
  unwinding containment alone does not deliver usable Java exceptions.
- **Decision:** P1 establishes containment and native trace support. P2 delivers
  built-in checked/unchecked mapping, Java catch hierarchy and `throws`
  declarations, messages, representable causes/secondary failures and Ironwood
  source-frame snapshots followed by the Java call site. P3 delivers custom
  Java exception types, hierarchy, checked declarations and supported snapshot
  getters, with producer diagnostics for unsupported projections. Both phases
  have explicit Java consumer exit tests and are mandatory before release.
- **Lifetime and failures:** Preserve independent native throwable ownership;
  never blindly free stored initializer failures, borrowed or emergency objects.
  Java snapshots require no native handle or explicit cleanup. Verify repeated
  failure, bounded cause/cycle handling and translation resource exhaustion in
  P2, extending coverage to custom snapshots in P3.
- **P5 scope:** Its new exception work is callback-originated Java throwable
  propagation, including unchanged identity, nested invocation state and foreign
  carrier cleanup when native code catches, replaces or retains it. Reuse the
  P2/P3 native-to-Java translator rather than deferring that translator to P5.
- **Scope:** Corrects D191's milestone allocation without expanding the supported
  exception surface or changing native exception semantics. Mandatory custom
  exception snapshot hierarchies are separate from deferred general facade
  inheritance. D194 enum initialization failures use the same P2/P3 translator.
- **Verification:** Documentation consistency, local links and `git diff --check`.
  Future consumer tests check type catches, declarations, data/getters, traces,
  cleanup and failure paths; no implementation or execution result is claimed.

## D196 - Java Bridge retention requires root-slot write proofs

- **Status:** Implemented through the reused P0 proofs and P3 generated adapters;
  [P3 evidence](JAVA_BRIDGE_P3CD_EVIDENCE.md) passes. Final P6 qualification remains.
- **Problem:** Escape summaries do not establish complete slot writes/releases,
  destination owners or propagation of loaded slot values. Untracked copies can
  undercount dependencies and permit unsafe free. Borrowed-child slot records
  cannot fit fixed root fields or survive in weakly cached facades.
- **Decision:** Prototype retention-slot write analysis in P0 and implement it
  in P3 before admitting retaining exports. Interprocedural semantic dataflow
  covers all reachable helpers, dispatch targets, initialization and exceptional
  cleanup. Attribute every tracked-field store/overwrite/clear to a known root
  available at the exported entry, including writes to other argument roots.
  Unknown destination owners/effects fail export. Produce immutable entry
  contracts before final free validation; revalidate after specialization and
  reconstruct the same proofs from source/class/archive inputs.
- **Slot values:** Reject copying or moving values loaded from slots into any
  other retaining storage, including other tracked slots; clearing the source
  later does not authorize a transfer. Proved non-retaining temporary use is
  allowed; returned values still require result/owner proofs. New slot values
  are null or known input roots; unchanged slots preserve their dependencies.
  Only complete effects can prove a scalar entry needs no reconciliation.
- **Representation:** Slots are fixed fields on reclaimable roots themselves,
  with records in persistent host root state. Borrowed values may be retained,
  but borrowed children cannot own slots. Facade collection cannot lose records.
  Purely permanent graphs keep D192's exemption; permanent holders without
  root slot state cannot retain reclaimable targets in the first release.
- **Scope:** Narrows D191's first-release retention shapes to root-only fields
  and supersedes D192's conditional admission of permanent holders retaining
  reclaimable targets for this release. Preserves native ownership safety,
  normal/exceptional final-slot reconciliation and D190's accepted boundary
  costs. No native-only bookkeeping, GC cleanup or runtime graph scans are added.
- **Verification:** Plan requires paired safe root writes/clears and rejected
  transfers, child slots and unknown owner/effect cases, including helper and
  exceptional paths, repeated dependencies, facade GC and artifact parity.
  Documentation consistency and `git diff --check` only for this correction;
  no compiler implementation or runtime test result is claimed.

## D197 - Java Bridge contains raising conversions inside typed entries

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** Runtime string construction can raise on allocation failure.
  Called directly by a C JNI adapter, it has no Ironwood handler and failed
  unwinding exits the JVM process. Protecting only the source method is too late.
- **Decision:** C transports primitives, handles and raw buffer/length descriptors.
  All potentially raising native work runs inside compiler-owned typed entries
  with catch-all protection: argument construction, allocation, type/enum
  initialization, target execution, result conversion, exception extraction and
  fallible cleanup. Bootstrap and follow-up conversion entries obey the same
  rule. No C call directly invokes a raising runtime helper or native getter.
- **Allocation and failure:** Establish a valid catchable allocation-failure
  context before fallible native allocation; null-context allocations can be
  fatal and bypass the diagnostic limit. Snapshot extraction needs a separate
  protected region after ordinary native catch/occurrence cleanup. Do not
  allocate while an implicit out-of-memory occurrence remains active. If
  extraction fails, return a bounded preallocated status without further native
  allocation or recursive snapshot attempts. Preserve native throwable ownership.
- **JNI:** Acquire/check raw buffers before entry; construct Java results or
  throwables only after native work returns. Map native allocation failures to
  Java `OutOfMemoryError`; preserve a pending JNI allocation exception. Cleanup
  outside typed entries must be proved nonthrowing and obey pending-exception
  restrictions. C buffer allocation uses checked status, not Ironwood unwinding.
- **Scope:** Refines D191's C adapter and D195's translator, preserving D194's
  initialization semantics and D196's exceptional retention reporting. It does
  not promise recovery from existing fatal runtime failures or change ordinary
  native execution. P1 proves containment; P2 tests Java conversion failures;
  P3 extends coverage to custom exception getters.
- **Verification:** Planned fresh-JVM allocation-limit regressions cover input,
  partial construction, initialization, output and snapshot failures, cleanup,
  repeated failure, JVM survival and a subsequent allocation-free native call.
  Inspect generated unwind edges at O0/O3. This documentation correction has
  consistency and whitespace checks only; no implementation or runtime result
  is claimed.

## D198 - Inherited Java facade identity methods survive native free

- **Status:** Implemented for permanent and reclaimable facades; post-free identity,
  hash collections and safely published logging pass in [P3](JAVA_BRIDGE_P3CD_EVIDENCE.md).
- **Problem:** Liveness checks on inherited `equals` and native dispatch for
  inherited `hashCode`/`toString` break equality symmetry, hash-collection removal
  and logging after free. An asynchronous logger can also enter the native world
  from the wrong thread unnecessarily.
- **Decision:** For concrete facades, project inherited `Object.equals` as
  `this == other` without lifetime/world checks. Compute inherited `hashCode`
  in Java using the paired runtime's address-based identity algorithm. Format
  inherited `toString` from the captured native type name and identity hash,
  matching native hexadecimal formatting. Do not call a virtual `hashCode`
  override from this formatter. These methods perform no native access, JNI,
  bootstrap, cache lookup or mutable root-state access.
- **Identity:** Store address bits and native type-name metadata as final Java
  fields before facade publication and preserve them after free. Borrowed views
  retain the same behavior after owner destruction. Reused native addresses may
  collide in hash/text, but distinct Java facades never become equal. Address
  metadata is private and cannot authorize native access after reclamation.
- **Overrides and threading:** Choose the projection from the resolved native
  implementation per method. Source `equals(Object)` overrides remain unsupported
  in the first release; supported `hashCode`/`toString` overrides keep native
  dispatch, liveness and D188 confinement. Only inherited Java-only operations
  are safe on other Java threads after safe publication, including logging after
  free. This does not authorize concurrent native access or change enum behavior.
- **Scope:** Supersedes D191's inherited-equality liveness check and inherited
  hash/text native dispatch; clarifies D188's native-world confinement boundary.
  P3 implements this projection and verifies it before release. Native ownership
  and source override semantics remain unchanged.
- **Verification:** Planned P3 cases cover equality laws, hash-collection removal,
  stable post-free hash/text, borrowed views, address reuse, native result parity,
  override dispatch and logger-thread execution without native entry. This edit
  has documentation consistency and whitespace checks only; no implementation
  or runtime test result is claimed.

## D199 - Java Bridge milestone exits require explicit evidence

- **Status:** Accepted plan correction after review; not implemented.
- **Problem:** P0 lacks an enumerated proof checklist, P4's allocation checks do
  not distinguish live cache hits from weak-facade recreation, and P6 leaves its
  JVM/target matrix unspecified. These exits can be declared complete without
  repeatable evidence for the intended contracts.
- **P0:** Require the plan's nine cases: scalar/exception containment, shared-image
  trace registration, loader/artifact refusal, enum first use, retention and
  failed construction, repeated argument-allocation failure, identity/address
  reuse, actual OrderBook classification, and scalar disassembly. Runtime cases
  run on macOS ARM64, Linux ARM64 and Linux x86-64 at O0/O3 with the pinned
  Temurin 21 build and `-Xcheck:jni`. Compiler-only proofs have an explicit host
  and lowering scope. Prototypes establish feasibility before P1-P3 integrate
  the full generator; no fabricated proof result or missing target counts as a pass.
- **P4:** Require zero native/Java allocations for warmed scalar and object-return
  cache-hit loops with strongly held facades. Cache misses may create a facade
  and documented support objects; do not promise zero Java allocation after
  collection or strengthen weak caches to meet a benchmark. Check actual weak
  reference clearing, recreation and bounded cache records separately. Record
  raw allocation deltas and distinguish harness work from bridge work.
- **P6:** Pin Eclipse Temurin HotSpot 21.0.12.1+1, 22.0.2+9 and 23.0.2+7 on each
  target, yielding nine required cells. Exact official release links and per-cell
  tests are in the plan. Record JDK hashes/full versions and host/payload details;
  patch changes require matrix updates and affected-cell reruns. Other JVM
  vendors are not claimed verified. Keep Java 24+ deferred.
- **Scope:** Refines D191's phase exits and D192-D198's verification obligations.
  Use focused local/maintainer-controlled runs, not new hosted development or
  full-compiler-suite jobs. Missing/inconclusive cases block completion. Timings
  run without JNI diagnostics and numerical acceptance remains a final P6 review.
  This decision authorizes no implementation, branch creation or test execution.
- **Verification:** Documentation consistency, local links and whitespace;
  official Temurin release metadata checked for the nine binary combinations.
  No bridge execution or performance result is claimed.

## D200 - Java Bridge commits bookkeeping before returning to Java

- **Status:** Implemented in generated P3 adapters; normal/exceptional and failure
  checks pass in [P3](JAVA_BRIDGE_P3CD_EVIDENCE.md). Final P6 qualification remains.
- **Problem:** Java failure during post-call count updates can expose native
  dependencies that are no longer protected by incoming counts. Increment-first
  ordering alone cannot protect a newly retained target before its first increment.
  Optional preallocation also allows native roots to outlive failed registration.
- **Decision:** Select adapter-side completion. After the typed entry returns,
  the C JNI adapter finishes root registration, incoming-count deltas and slot
  records before Java result/error materialization, Java helper calls or return.
  Apply increments before decrements and complete every normal/exceptional path,
  including store-then-throw and destruction. Do not defer repair to Java code.
- **Commit contract:** Use pre-resolved JNI fields/references and bounded arithmetic,
  with no allocation, Java calls, callbacks, native raising helpers or early exit
  in the commit epilogue. Prove it has no recoverable failure point. Preflight
  count headroom before native mutation, account for aliased holders/inputs, and
  reject any implementation path that cannot establish completion. Fatal process
  failures and unsupported concurrent native access remain outside the contract.
- **Registration:** Reserve all state for every possible new reclaimable root
  before the native call, including index entries/capacity, root/slot state,
  required cache-registration storage, strong references and commit records.
  Unbounded or unreservable result shapes fail producer build; reservation failure
  skips native execution. Bind identities without allocation; existing aliases
  reuse their state. Later Java facade allocation may fail only with the native
  root already registered or safely reclaimed by proved unpublished rollback.
- **Scope:** Supersedes D191's optional preallocation and ambiguous Java-side
  reconciliation allowance. Refines D196's slot protocol and D197's adapter
  sequence, preserving D198's final facade metadata. No extra scalar-path
  transaction tracking, locks, thread checks or native-only bookkeeping is added.
- **Verification:** P0/P3 check reservation failures, bounded-result diagnostics,
  commit control flow, aliases, counter headroom, store-then-throw and destruction.
  Inject Java failure immediately after adapter return and during facade/error
  materialization; counts must remain exact and roots accounted for. P6 repeats
  the cases across its JVM matrix. This edit has documentation consistency and
  whitespace checks only; no runtime result is claimed.

## D201 - Java Bridge validates native dependencies, stack and world lifetime

- **Status:** Accepted plan refinement after risk review; not implemented.
- **Dependencies:** P1 audits both Linux payloads' DT_NEEDED, relative loader
  paths, transitive libraries and GLIBC/GLIBCXX/CXXABI/GCC version requirements.
  C++ personality/unwind support cannot be assumed available from the JVM.
  Minimal-runtime scalar/exception experiments must pass without installing
  extra compiler-runtime packages. Resolve missing dependencies through reviewed
  private packaging or proved static support, preserving unwind/isolation and
  licensing. P6 repeats the final audit, including macOS install names.
- **Stack:** Calls use the Java caller's remaining stack. Native exhaustion is
  potentially fatal; there is no universal safe recursion depth or automatic
  Java StackOverflowError conversion. P0 adds a tenth case: non-tail recursive
  reference depths 1/8/32/64 from Java depths 0/64 at O0/O3 on all three targets,
  with default-stack platform threads. Record actual frame/stack sizes. These
  fixture depths do not guarantee arbitrary code's safety. Limit probes use
  separate disposable JVMs; default bounded workloads cannot require -Xss flags.
  Callers must provide enough stack and recursive producer APIs must document
  their bounded workload. No stack switching, signal recovery or hot-path checks
  are introduced. P1/P6 verify production footprint and matrix coverage.
- **OOM:** D197's catch cleanup explicitly includes ironwood_exception_caught;
  ironwood_exception_take alone leaves active_implicit_failure set. Preserve
  primary/secondary and stored-throwable semantics rather than zeroing globals.
  P0/P2 repeat OOM through different entries, initialization and snapshot failure,
  checking both cleared implicit state and released emergency delivery resources.
- **Binding:** Pin the first successfully bound defining classloader with a
  strong JNI global reference until JVM termination, allocated before readiness
  or source initialization. Also reject rebinding of a mapped bound image in
  JNI_OnLoad/bootstrap. An image-local flag alone cannot survive full unloading.
  Never clear the binding or run Ironwood destruction from JNI_OnUnload; release
  of all facades does not authorize reload. P0/P2/P6 exercise GC pressure and
  second-loader refusal, plus a retained-image test harness for the bound guard.
- **Scope:** Strengthens D191's one-loader-per-artifact policy to an explicit
  process-lifetime anchor, refines D197's failure cleanup and extends D199's P0
  checklist from nine to ten cases. No implementation or performance result is
  claimed; focused experiments remain future work, without hosted full suites.
- **Verification:** Runtime/linker/audit source review, documentation consistency,
  local links and whitespace checks. JNI lifecycle rules checked against the
  official Java 21 invocation specification linked in the plan.

## D202 - Java Bridge tightens first-release loading and ownership boundaries

- **Status:** P0 ownership proofs and P1 eager binding/dependency gates pass;
  production P2/P3/P6 repetitions remain pending.
- **ELF binding:** P1 links Linux bridge payloads with `-Wl,-z,now` and verifies
  BIND_NOW/NOW in the resulting ELF flags. A required unresolved relocation must
  cause catchable System.load failure before source execution, not a fatal first
  call. P6 verifies packaged payloads. This does not eagerly initialize Ironwood
  types, validate arbitrary later dlsym lookups or change macOS/executable links.
- **Trust boundary:** Safety applies to generated public facades, including
  ordinary reflective invocation of their public methods. Privileged reflection,
  method handles, Unsafe, instrumentation or JNI that bypass private entries or
  forge handles/state are outside the contract. Keep native entries and handles
  private, without adding redundant checks to defend against hostile JVM code.
  Supported public calls retain every existing safety guarantee.
- **Result ownership:** Preserve all origins in analysis, but reject first-release
  reclaimable results mixing fresh Java-owned storage and existing borrowed/alias
  references across branches, helpers or dispatch. Null is not an ownership mode:
  fresh-or-null and proved borrowed-or-null remain eligible. D192 results uniformly
  proved immortal/non-reclaimable remain eligible despite mixed allocation origins.
  Cache absence, pointer value or Java type cannot select ownership. A later
  extension could supply a compiler-produced ownership tag with full proofs;
  the first-release ABI does not contain one.
- **Scope:** Narrows D191/D200's admitted result alternatives while preserving
  mandatory preallocation for the remaining shapes. Extends D201's Linux audit
  and clarifies the existing facade safety boundary without new hot-path overhead.
- **Verification:** P0/P3 negative mixed-result proofs, positive nullable/permanent
  controls and source/class/archive parity; P1/P6 eager-binding flags and isolated
  missing-symbol load tests; P2/P3 public reflective-call/private-visibility checks.
  P1 O0/O3 final ELF audits and minimal-JVM scalar, failure, missing-relocation
  and two-image checks pass on Linux ARM64 and translated x86-64. Privately
  delivered pinned support libraries preserve glibc 2.17 and require no consumer
  setup. See [native support](JAVA_BRIDGE_NATIVE_SUPPORT.md) and the progress
  log; real x86-64 hardware remains pending under D213.

## D203 - Java Bridge refuses unsupported JVM versions before native loading

- **Status:** Accepted plan refinement; not implemented.
- **Problem:** Deferring Java 24+ support without defining loader behavior leaves
  consumers unsure whether a newer JVM is supported with a warning or rejected.
- **Decision:** Admit only Java 21-23 through a Java-only runtime feature-version
  check in one-time loading. On Java 24+, first native use throws
  `UnsatisfiedLinkError` with artifact identity, the detected full runtime version
  and supported range before extraction, System.load or native bootstrap.
  No bypass or native-access flag workaround is provided. Pure Java enum
  initialization and type inspection need not trigger loading. Older JVMs may
  reject the Java 21 class-file version before this diagnostic can run.
- **Scope:** Clarifies D191's deferred Java 24+ support; no warmed-call version
  check or extension of the supported JVM matrix. D192's non-reclaimable
  OrderBook gate, D193's strict package ownership and D198's post-free inherited
  identity methods remain settled and unchanged.
- **Verification:** P2 implements the guard and predicate tests; P6 repeats the
  refusal smoke test on Temurin HotSpot 24.0.2+12 on macOS ARM64, on class path
  and module path. Require the clear error, zero extraction/load attempts and no
  bridge-caused native-access warning. Check accepted 21/22/23 and rejected
  24/25/higher predicate inputs. This negative test supplements the nine supported
  JVM/target cells; it does not claim Java 24 compatibility. Current verification
  is documentation consistency and whitespace checks only.

## D204 - Java Bridge root registration uses a native index

- **Status:** Implemented in P3 with preallocation, authoritative recovery and
  explicit destruction; [P3 evidence](JAVA_BRIDGE_P3CD_EVIDENCE.md) passes.
- **Problem:** A Java root index cannot be updated by D200's adapter commit,
  which forbids allocation and Java collection calls. Deferring insertion until
  Java resumes can leave a live root unindexed after allocation failure, allowing
  duplicate ownership state and double reclamation on later exposure.
- **Decision:** The world owns an authoritative native root index. Reserve its
  records/capacity and create strong JNI global references to preallocated Java
  root-state objects before the typed call. Adapter commit binds native identities
  into those records without growth, allocation or Java helper calls. Lifetime,
  counts and fixed slots remain in Java, updated through pre-resolved JNI fields.
  Only weak facade caching happens in Java after commit/return; cache failure or
  collection cannot remove registration or authorize a second ownership state.
- **Cleanup:** Discard unused preparation records/global references on failure,
  null or existing-alias results only when no new root needs them. Keep registered
  roots anchored independently of facade delivery/collection. Successful free
  commits FREED, removes the native index record and releases its global reference
  before return; old facades retain the dead Java state. Proved unpublished
  rollback also releases registration resources. Address reuse gets fresh state.
- **Scope:** Supersedes D191 section 14.A's ambiguous index residency and D200's
  inclusion of Java cache-registration storage in mandatory root reservation.
  Preserves D200's nonthrowing commit and D190's accepted boundary costs; no root
  lookup on primitive-only calls or change to D192's permanent-object cache.
- **Verification:** Extend P0-7 and P3 with native-index reservation/global-reference
  failures before native execution and Java weak-cache insertion failure after
  commit. Re-exposure must recover exactly the same root state; eligible free
  destroys once, including across facade collection and forced address reuse.
  Check index/global-reference cleanup and inspect commit for forbidden calls
  or table growth. P6 repeats the focused cases. Current changes are documentation
  only; no JNI execution or performance result is claimed.

## D205 - Java Bridge distinguishes translated evidence and hardware prerequisites

- **Status:** Accepted plan refinement; not implemented.
- **Problem:** P0/P6 demand pinned Temurin and three targets without scheduling
  JDK provisioning or identifying hardware-dependent evidence. The local Linux
  images select conda OpenJDK, and their x86-64 runner uses Rosetta.
- **Preparation:** P0 schedules bridge-specific image/JDK preparation with pinned
  URLs/checksums, cache inputs, explicit JDK selection and vendor/version/architecture
  preflight. Record physical host, guest, virtualization and translator metadata;
  guest architecture alone is insufficient. Identify access to actual x86-64
  hardware before scheduling stack/release work. Missing access blocks the
  relevant gate; it does not prevent independent prototype work.
- **Evidence:** Rosetta may satisfy the bounded functional P0-1 through P0-7
  assertions, explicitly labeled translated. Compiler proofs may run on any
  prepared host. P0-9 static disassembly may inspect cross-built/emulation-built
  target binaries; it proves no timing or runtime stack behavior. P0-10 requires
  matching hardware on each target, including physical x86-64 for Linux.
  Same-architecture hardware virtualization/containers are allowed, including
  Colima Linux ARM64 on Apple Silicon. CPU translation cannot pass stack probes.
- **Release:** All nine P6 runtime cells and performance evidence require matching
  hardware, repeating applicable P0 functional checks there. Provision Temurin
  22/23 and the separate macOS Java 24 refusal baseline before P6. No new paid
  hardware or hosted development job is authorized; no full suite is added.
- **Scope:** Refines D199's evidence matrix and D201's stack experiments without
  reducing the ten P0 cases or nine P6 cells. The local testing document's
  networking-specific Rosetta caveat is not a blanket exclusion of functional
  tests. This change defines bridge evidence rules explicitly.
- **Verification:** Source review of platform setup/images, documentation
  consistency, local links and whitespace only. No provisioning or host run has
  been performed by this documentation change.

## D206 - Java Bridge uses noncritical JNI string buffers

- **Status:** Accepted plan correction; not implemented.
- **Problem:** Leaving the UTF-16 acquisition API unspecified could keep a JNI
  critical region open through arbitrary native work or a P5 Java callback.
- **Decision:** Use GetStringChars by default, or GetStringRegion into checked
  adapter-owned storage. Do not use GetStringCritical for this path or hold any
  JNI critical region across initialization, target execution, adapter commit,
  result/error materialization or callbacks. This applies before P5 as well.
  Preserve exact length-based UTF-16 conversion and D197's protected native
  materialization; raw JNI storage is never an Ironwood object.
- **Cleanup:** Keep the source reference valid and release each successful
  GetStringChars acquisition with ReleaseStringChars, including when isCopy is
  false. Region copies have adapter-owned cleanup. Partial acquisition failure
  skips native execution and releases earlier buffers; nested calls own separate
  buffers. No raw buffer escapes its invocation.
- **Verification:** P2 covers the selected APIs, null/empty/NUL/surrogate content
  and normal/exceptional/partial-failure cleanup. P5 adds Java callbacks that
  allocate, reenter with another string and throw while the outer argument is
  live, under -Xcheck:jni. Refines D191/D197 without admitting arrays or adding
  scalar-path work. Current verification is documentation checks and the official
  Java 21 JNI specification linked in the plan; no runtime result is claimed.

## D207 - Java Bridge lifetime refusals have an artifact-private exception type

- **Status:** Implemented in P3; exact refusal type, no-entry counters and producer
  exception controls pass in [P3](JAVA_BRIDGE_P3CD_EVIDENCE.md). P4 adds OrderBook controls.
- **Problem:** A producer can throw IllegalStateException itself, as OrderBook
  does on capacity exhaustion. A superclass-only assertion can therefore mistake
  producer failure for a successful bridge lifetime refusal.
- **Decision:** Generate final BridgeLifetimeException extending Java
  IllegalStateException in the artifact-unique support namespace, constructed
  by internal support helpers. Use it for dead receiver/argument access and
  borrowed/immortal/retained/active free refusals. Producer exceptions keep their
  normal mapping; no producer failure is translated into this subtype. Consumers
  retain the existing superclass catch contract without a new public API.
- **Verification:** P0/P3 refusal fixtures assert exact Class identity using a
  test-only support-package helper and unchanged native-entry/destruction counters.
  Pair with a producer IllegalStateException that must not satisfy the refusal
  assertion. P4 adds actual OrderBook capacity exhaustion as a producer control;
  P5 covers active-callback refusal. Throwable allocation failure cannot permit
  the native operation or count as proof of the expected refusal type.
- **Scope:** Refines D191's IllegalStateException contract without changing
  D195's producer exception translation, adding checks to valid calls or changing
  repeated owner free from a no-op. No free method is added to permanent types.
  Verification now consists of OrderBook source review, documentation consistency
  and whitespace checks; no runtime result is claimed.

## D208 - Validate actual OrderBook rollback in P0

- **Status:** P0 proof/runtime experiment passed; production-artifact repetition pending.
- **Decision:** Add the real OrderBook constructor's partial Order/PriceLevel
  population and later array-allocation failure paths to P0-8 before P1. Inspect
  typed cleanup and ownership facts, then use calibrated allocation limits in
  isolated JNI prototypes. Prove each rollback-reclaimed allocation is unpublished
  and disjoint from exposed storage; protect a previously successful book/order
  while a second construction fails. Unknown effects or unexplained cleanup block
  P0-8, without weakening D192 or delaying the proof until P4.
- **Caveat:** Current array-element rollback depends on recognized destructor
  loops; OrderBook has none. Verify actual cleanup rather than assuming pooled
  Orders are destroyed. Record surviving allocations separately from the
  non-reclamation safety proof; that proof does not establish leak-free rollback.
- **Coverage:** P0-8 now has runtime and compiler-proof portions. Extend D205's
  translated functional allowance to its O0/O3 three-target runtime checks;
  compiler proofs still need one host and stack/P6 hardware rules are unchanged.
  Repeat with production artifacts in P3/P4. No new experiment number is added.
- **Verification:** Actual constructor/rollback proofs and source/class/archive
  reconstruction pass. O0/O3 JNI failures on both ARM64 targets and translated
  x86-64 match the generated book/tail cleanup and preserve exposed controls.
  Unpublished survivors are reported separately. See the
  [P0 evidence audit](JAVA_BRIDGE_P0_EVIDENCE.md); P3/P4 must repeat these cases
  through production artifacts, and translated evidence is not hardware qualification.

## D209 - Reconsider Java Bridge version refusal with a P2 Java 25 experiment

- **Status:** P2 experiment executed on macOS ARM64. The maintainer retains
  Java 21-23 and D203's Java 24+ refusal for this implementation run, keeping
  the bounded qualification scope; Java 25 admission remains a later review.
- **Problem:** D203 rejects Java 25 even though its default native-access policy
  permits JNI with warnings. The restriction is a product choice that excludes
  an LTS release, not an unavoidable JVM authorization requirement.
- **Experiment:** Once P2 exists, compare the guarded artifact with a separately
  identified artifact admitting exactly Java 25 and preserving all other bridge
  contracts. Use pinned Temurin 25.0.4.1+1 on macOS ARM64 with default flags and
  no implicit grants across class path, module path and executable-jar launches.
  Capture P2 values/exceptions, warnings and continued operation; use separate
  -Xcheck:jni and explicit deny controls. No consumer bypass is shipped.
- **Checkpoint:** P2 produces the evidence/report, including failures. The
  maintainer decides before P6 whether documented warnings are preferable to
  refusal. P3/P4 may proceed while the decision is pending. A successful scalar
  probe does not establish full bridge support or admit every newer JVM.
- **Scope:** Supersedes D203's treatment as a settled first-release product choice;
  its guard remains the ordinary artifact baseline pending the decision. Selecting
  broader operation requires an explicit D203 supersession for named versions,
  revised support/loader docs and a pinned expanded P6 matrix with full production
  coverage. Retaining refusal requires a recorded rationale. Java 21-23 remains
  the supported baseline until then; the experiment need not succeed to inform
  the decision. D205's hardware/evidence rules remain unchanged.
- **Verification:** The paired production/experimental producers pass 36 O0/O3
  child launches on pinned macOS ARM64 Temurin 21/24/25. Experimental Java 25
  works in all three launch forms with native-access warnings and no observed
  checked-JNI misuse. Explicit deny leaves the extracted image unmapped; ordinary
  Java 24/25 refusal precedes extraction. See the reproducible commands, payload
  identities, limitations and recommendation in [the report](JAVA_BRIDGE_JAVA25.md).

## D210 - Verify macOS bridge signatures and extracted-library loading in P1

- **Status:** P1 private fixture passes all six pinned macOS cells; generated
  P2/P6 repetitions remain pending.
- **Decision:** Extend P1's dependency/install-name audit with O0/O3 ARM64 dylib
  signature verification before and after fixture-jar extraction. Require the
  same payload bytes and valid ad-hoc signature; finish binary edits before
  signing/digest creation. Record linker-provided signing or any needed producer
  signing step, including privately packaged dylib dependencies.
- **Launchers:** Provision pinned macOS Temurin 21.0.12.1+1, 22.0.2+9 and
  23.0.2+7 in P1. Each unmodified launcher must load the extracted path and pass
  scalar, contained-exception and continued-call checks in separate subprocesses,
  under ordinary settings and separately with -Xcheck:jni. Archive hashes,
  codesign results, launcher signatures/entitlements and host/load diagnostics.
- **Gates:** Invalid signatures, byte changes, load refusal or crashes block P1.
  Resolve them in producer packaging, not by re-signing JDKs, changing launcher
  entitlements, stripping quarantine attributes or requiring consumer signing.
  P2 repeats with its actual jar/loader; P6 repeats with final payloads and selected
  launchers. D209's Java 25 experiment also records this evidence.
- **Scope:** Refines D201's macOS audit and advances supported macOS JDK setup
  from P6 to P1. A private extraction fixture avoids a P1 dependency on P2.
  Ad-hoc signing is not a claim of notarization or arbitrary launcher support.
- **Evidence:** O0/O3 linker-signed payloads preserve their bytes, ad-hoc
  signatures and CDHashes after private atomic extraction. All three pinned
  launchers pass ordinary and checked-JNI scalar/exception/continued-call runs
  without modification or consumer configuration. The reproducible private
  runner and matched evidence are recorded in
  [the progress log](JAVA_BRIDGE_PROGRESS.md).

## D211 - Carry P0 compiler analysis foundations forward into P3

- **Status:** P0 reusable foundations delivered; P3a production admission in progress.
- **Problem:** P0-5/P0-8 require substantive compiler analyses. Treating them as
  bounded throwaway experiments understates the work and risks a duplicate P3
  implementation with different proof behavior.
- **Decision:** Start P3's retention-slot and non-reclamation analyses in P0 as
  reusable semantic modules with immutable contracts, source diagnostics and
  paired regression fixtures. Use an internal analysis-only compiler/test option
  to select the additional work, disabled for ordinary builds. P0 still requires
  real proofs for its specified positive/negative cases and actual OrderBook
  closure; a skeleton or unknown positive result cannot satisfy the checkpoint.
- **Safety:** The option never bypasses existing free checks or permits unproved
  exports. P3 enables required analysis automatically for bridge builds and
  rejects exports if proof is unavailable. Compare focused ordinary-program
  diagnostics/lowering with the option off/on across unfreed modes; new reports
  are separate and native-only runtime behavior stays unchanged.
- **Handoff:** P3 extends the same modules to full admitted coverage and production
  export/adapter integration, retaining P0 tests and source/class/archive parity.
  Temporary JNI harnesses may remain experimental; proof logic is durable code.
  Estimate platform experiments and compiler foundations separately.
- **P3a implementation:** Concrete signature selection and root/String proof
  composition reuse these foundations. String getters with a proved live owner
  produce copied Java values, not native facade or destruction capabilities.
  Nullable owned-field returns retain their original owner through conditional
  and early-null forms. Unknown origins, copied-input publication and mixed
  fresh/existing cleanup remain rejected. Uniform permanent objects compose
  copied String conversion through a separately bound retention query: D192
  publication of proved permanent references cannot hide temporary String capture.
  These entries reuse protected conversion and add no destruction capability.
  Public object production stays gated
  until the dependent conversion and lifetime adapters pass their checkpoints.
- **Scope:** Refines D196/D199/D208's prototype-versus-implementation split without
  moving the P0-5/P0-8 proof gates after P1 or reducing required evidence. No public
  CLI switch, Java consumer configuration or implementation is introduced now.
  P0 verification includes paired proof tests, ordinary-diagnostic/IR isolation,
  artifact reconstruction and proof-authorized JNI fixtures; see the
  [P0 evidence audit](JAVA_BRIDGE_P0_EVIDENCE.md).

## D212 - Divide Java Bridge phases into explicit dependency checkpoints

- **Status:** Accepted planning refinement; not implemented.
- **Problem:** Accumulated requirements left P0 and P3 too broad for a single
  implementation task, placed the shared export model after its first consumers,
  and mixed P6 distribution construction with final qualification. The historical
  proposal also retains a different phase sequence.
- **Decision:** Keep existing phase IDs and the first-release order
  P0 -> P1 -> P2 -> P3 -> P4 -> P6. Split P0 into preparation/shared contracts,
  reusable analyses/native fixtures and the complete ten-case feasibility gate.
  Establish the minimal root/contract/ABI model in P0, integrate it into the
  production linker in P1 and extend it with package discovery/generation in P2.
  Preserve one source of proof facts and case assertions through these handoffs.
- **Objects:** Split P3 into production proof/admission, permanent facades/enums
  and custom snapshots, reclaimable roots/views, then independent-root retention
  and combined safety qualification. Reject retention exports until their complete
  adapter protocol exists. General native-facade inheritance remains deferred;
  custom exception hierarchies remain mandatory. Early OrderBook smoke tests do
  not replace the reclaimable fixture or close P3/P4.
- **Delivery:** P2's first generated jar is macOS ARM64, with supported-launcher,
  class-path/module-path/executable-jar tests and required notices/source
  availability. P6 first builds the multi-target distribution candidate, then
  qualifies it on the selected matrix and reviews numerical performance last.
  D209's product decision remains required before any P6 work begins.
- **Scope:** Refines D191/D199/D211 sequencing without changing release gates,
  safety contracts, required hardware/evidence or the deferred P5/P7 scope.
  Submilestones are progress checkpoints, not separately supported releases.
  The older proposal's phase list is historical; section 12 of the implementation
  plan is authoritative. Verification is documentation consistency and diff
  checking; no implementation or experiment was performed.

## D213 - Defer Linux x86-64 hardware qualification to final P6b

- **Status:** Accepted implementation sequencing; hardware validation pending.
- **Decision:** Develop and validate on macOS ARM64 and Linux ARM64, preserving
  early x86-64 translated functional checks and static disassembly. Run real
  Linux x86-64 hardware tests at the end of P6b: the deferred P0-10 O0/O3 stack
  cases and all three pinned x86-64 release cells. Prepare the tests earlier.
- **Progress:** P0 may close for implementation with explicitly recorded
  `pending x86-64 hardware` evidence. Missing x86-64 host access does not block
  P1-P4, P6a or ARM64 P6b work. Required compiler proofs, ARM64 hardware evidence
  and permitted x86-64 functional/static checks remain in their original phases.
  P1-P4 may use labeled Rosetta functional evidence, including dependency/load
  checks; translated execution proves neither native stack limits nor release
  allocation/performance behavior. Known failures still block affected work.
- **Handoff:** Supply a focused runner, pinned environment setup, revision/payload
  identities and evidence instructions for manual execution or authorized SSH
  access to the maintainer's Linux x86-64 host. Do not assume remote access or
  provisioning authorization. Both execution routes must satisfy the same gates.
- **Release:** All deferred hardware checks remain mandatory. Late defects may
  require implementation changes and renewed affected cross-target verification.
  P6b, final numerical performance acceptance and release readiness cannot close
  with missing hardware evidence. No x86-64 pass or reduced support is implied.
- **Supersession:** Supersedes D199/D201/D205/D212 only where unavailable x86-64
  hardware prevents leaving P0 or starting later implementation; extends D205's
  translated functional allowance to P1-P4. Preserves proof requirements,
  hardware evidence standards, the supported matrix and all other release gates.
  Documentation checks only; no implementation, SSH session or experiment ran.

## D214 - Bound Java Bridge exception snapshot graphs

- **Status:** Implemented within D195 and qualified through the P2 value producer.
- **Decision:** A copied snapshot holds at most 32 native throwable nodes,
  32 secondary edges per node and 32 native frames per node. Append at most
  64 Java call-site frames, reserving the last slot for an explicit truncation
  frame when needed. Native traversal must likewise identify omitted edges and
  frames rather than silently losing them.
- **Representation:** Preserve shared node identity, secondary ordering and
  representable cycles. Java forbids self-causation and self-suppression, so
  self edges and graph-capacity omissions point to a graph-local IOException
  marker identifying self-reference or a copy limit. This also supplies a
  representable omitted cause for wrappers requiring IOException. Construct
  ordinary nodes first, then required-cause wrappers, then attach other edges.
  Invalid internal indices or required-cause types fail with LinkageError.
- **Safety:** The Java assembler consumes copied data and grants no native
  ownership or getter permission. JNI must finish extracted nonfinal message
  fields before exposing any node. Allocation failure propagates without
  recursive graph construction; native extraction still uses D197 protection
  and its separately bounded failure status. No success-path bookkeeping is
  added. No public exception API or supported producer surface expands here.
- **Evidence:** Generated Java 21 factory/graph classes pass constructor,
  identity/cycle/limit/trace and child-heap-exhaustion recovery checks on pinned
  macOS ARM64 Temurin 21/22/23. Native transport tests and public source/archive
  producer jars additionally pass graph/field/trace and allocation-exhaustion
  checks on those launchers; see [the P2 audit](JAVA_BRIDGE_P2_EVIDENCE.md).
- **Scope:** Refines D195's bounded copying convention without superseding its
  mapping, source trace, containment or independent reclamation requirements.

## D215 - Java Bridge preview artifact and module naming

- **Status:** Implemented producer convention within D193's artifact boundary.
- **Decision:** The producing jar basename is the preview's logical artifact
  name. Its Automatic-Module-Name is `ironwood.bridge.a` followed by the SHA-256
  of that name. Implementation updates under the same name keep the Java module
  name stable while the complete generation and native build identities change.
  Independent modules require distinct producing names. Renaming an already
  produced jar changes neither its declared module nor its artifact identity.
- **Distribution:** Package generated sources/Javadoc and the JDK tool's legal
  output, exact standard-library/runtime source and required notices. Preserve
  used archive notices with archive-content identities and accept repeated
  `--license` application notice/source-availability files. Recheck inputs before
  atomic publication; do not infer application licenses or expose its source.
- **Verification:** Source/class/archive generation parity, all three ordinary
  launch forms, exact content inventory, notice preservation and failed-output
  preservation are covered by focused producer tests. This convention does not
  complete P2 or the future P6 Maven/Gradle and multi-target qualification.

## D216 - Preserve representable Java catch hierarchies in custom snapshots

- **Status:** Enforced by P3 snapshot discovery and the macOS permanent-object
  producer; remaining lifetime protocols and final qualification remain pending.
- **Decision:** Custom snapshots retain the actual mapped Java catch hierarchy.
  Reject direct or indirect descendants of `ironwood.nio.file.DirectoryIteratorException`
  because its Java counterpart is final. Do not flatten that hierarchy or substitute
  a different exception type. Ordinary Ironwood inheritance remains unchanged.
- **Data:** Discover inherited path/parse constructor getters and transfer-count
  fields through the native hierarchy. Exact protected getter targets and String
  ownership proofs apply equally to inherited and source-overridden properties.
- **Verification:** Focused layout/projection regressions cover inherited data,
  unsafe String overrides and explicit rejection of the unrepresentable hierarchy;
  existing custom proof tests retain source/class/archive and all-mode checks.
- **Scope:** Refines D195's requirement to reject unsupported snapshot projections;
  it supersedes no supported mapping or ordinary source-language behavior.

## D217 - Construct custom snapshots from copied Java data

- **Status:** Implemented Java/native P3 component and macOS permanent-object
  producer integration; final lifetime and release qualification remain pending.
- **Decision:** Generate non-public constructors accepting artifact-local copied
  data and invoke them from the generated factory within the same Java module.
  Never execute source exception constructors while translating a failure.
  Preserve abstract catch declarations and concrete checked/unchecked ancestry.
- **Data:** Generated getters read captured primitive bits, String values and
  graph edges. Use valid placeholder arguments for built-in superclass
  construction because legal source getter overrides can return values that
  those Java constructors reject. Captured values remain authoritative.
- **Bounded failure:** Apply D214's graph limits and marker. Validate covariant
  cause/secondary types before exposing any snapshot. If an omitted-edge marker
  cannot inhabit the declared custom return type, fail with LinkageError rather
  than expose a getter that later fails a cast. Allocation failure propagates
  without a recursive snapshot attempt. No snapshot owns a native handle.
- **Scope:** Refines D195/D214 construction and representation; it grants no new
  getter invocation or lifetime permission and does not supersede D216's rejected
  final Java superclass boundary.

## D218 - Java Bridge local distribution and build-tool conventions

- **Status:** P6a implementation; final candidate and hardware qualification
  remain separate gates.
- **Decision:** Use explicit standard Maven coordinates and `sources`/`javadoc`
  classifiers. The distribution command copies a verified host or assembled jar
  unchanged, derives IDE companions from its embedded generated artifacts,
  retains their notices, and records generation and output hashes. It publishes
  only to a new local directory and performs no repository upload.
- **Integration:** Invoke the existing producer/assembly/distribution commands
  through ordinary Maven execution/install-file or Gradle Exec/Maven publication
  facilities. Require no Ironwood-specific plugin, global service or consumer
  toolchain. Native production still uses matching host toolchains; local Maven
  coordinates do not imply a supported platform or Java version.
- **Identity:** D215's producing basename remains the logical artifact name;
  repository filenames can include the public version without regenerating or
  relabeling the private native generation. A POM version change alone never
  changes a generation. Keep covered runtime/source delivery in the main jar.
- **Scope:** Records section 10's fixed convention, without superseding D193,
  D203, D209 or D215 and without authorizing remote publishing.

## D219 - Deterministic Java Bridge shared-image metadata

- **Status:** P6a reproducibility correction; candidate qualification remains
  separate from implementation checks.
- **Decision:** Give macOS shared images a stable relocatable install name based
  on their output basename. Canonicalize independent LLVM pseudo-probe root
  groups in shared Mach-O/ELF objects before linking. Preserve all records,
  nested order, section extent, code and function addresses. Ad-hoc signing
  happens after the normal link; assembly retains the signed bytes unchanged.
- **Boundary:** This is build-time metadata processing for the pinned LLVM
  output format. Reject malformed metadata and relocations into reordered
  sections. Do not discard trace information, normalize runtime state, change
  reclamation facts, or introduce per-call work. Ordinary executable linking
  keeps its existing path.
- **Verification:** Repeated public builds in different staging parents must
  produce identical jars. Preserve exact source traces, isolated artifact
  loading, failure containment and source/class/archive behavior. An unfamiliar
  toolchain format requires investigation and requalification, not a silent
  fallback. This refines distribution reproducibility without superseding
  D132/D133 or the private identity/loader contracts.

## D220 - Keep permanent facade cache hits in generated Java

- **Status:** Performance implementation under maintainer direction; refreshed
  candidate and numerical qualification remain required.
- **Decision:** A proved permanent object result may cross the paired private
  JNI boundary as an address after native execution, commit and exception
  containment. The generated Java method maps null and checks the existing weak
  identity cache directly. A private registered native helper performs existing
  wrapper creation on misses. No public address constructor is added. Artifact
  support exposes only read-only lookup; cache insertion remains nonpublic.
- **Reason:** Calling back into Java from the native adapter for every cache hit
  adds substantial cost to fine-grained APIs. The Java-side lookup can be inlined
  without changing the user's source API, native engine or benchmark workload.
- **Invariants:** Preserve one live facade per native object, weak recreation,
  exact generation binding, exceptional delivery and committed retention. No
  new cache, strong ownership or automatic reclamation is introduced. Root/view
  results keep their separate lifetime transport. Cold fallback remains subject
  to host allocation failure and class initialization checks.
- **Scope:** Refines implementation of D190/D191/D192 identity conversion;
  supersedes no safety contract or version policy. Changed private declarations
  and helper inventories are bound to their exact generated native adapters.

## D221 - Private primitive transport for Java Bridge enum arguments

- **Status:** Performance implementation; refreshed qualification is required.
- **Decision:** Generated Java callers map declaration ordinals to the exact
  generation's name-assigned enum tokens and pass private integer JNI arguments.
  Null remains the existing null token. Protected typed native entries continue
  to own native initialization, conversion and exception containment.
- **Reason:** Valid generated enum values need no JNI field access merely to
  recover a token already known to the paired Java projection. Declaration order
  need not equal token order; generate the complete explicit mapping.
- **Invariants:** Preserve public signatures, null behavior, cold initialization,
  source/class/archive parity, retention ordering and preparation cleanup. No
  public raw-token API or new supported enum shape is introduced. Paired private
  descriptors and transport selections remain generation-bound.
- **Scope:** Refines D190/D191 enum transport without superseding safety,
  ownership, thread confinement or Java-version contracts.

## D222 - Inline medium loops through small native-library callers

- **Status:** Maintainer-directed performance improvement, with focused checks
  passed; refreshed production qualification remains required.
- **Decision:** Raise D175's structural loop-body selection bound from 256 to
  512 typed operations for native libraries with explicit export roots. Preserve
  the executable bound and every other direct-call, caller, recursion, lifecycle,
  entry, reachability and compilation-work bound. LLVM's ordinary O3 threshold
  remains 1000. This changes optimization selection, not supported APIs.
- **Reason:** A native library cannot see the enclosing Java application loop.
  OrderBook's 362-operation matching method sat behind its small creation
  methods, blocking inlining and simplification of their known state. Inlining
  removes this call without changing the engine or Java workload.
- **Evidence:** Three interleaved warmed forks on physical x86host improve the
  Java21 bridge cycle from 192.22 to 178.84 ns; pure Ironwood is 124.47 ns and
  Java21 is 240.28 ns in the latter comparison. Linux ARM64 also improves, from
  82.58 to 78.16 ns, but still trails Java's 65.24 ns. These are development
  observations, not final numerical acceptance. Exact inputs and all observations
  are retained in `JAVA_BRIDGE_OPTIMIZATION.md`'s referenced evidence.
- **Invariants:** Typed proof input is unchanged. Preserve evaluation, exceptions,
  cleanup, source traces and all memory-safety enforcement. Focused tests cover
  library selection and recursive fallback, unchanged executable selection,
  exact native traces/cleanup, cold enums, mandatory final lifetime/root rejection
  and actual OrderBook zero-allocation/weak-recreation behavior.
- **Scope:** Supersedes only D175's 256-operation limit for explicit native
  libraries. It does not supersede D132/D133, reclamation proofs or JNI contracts.

## D223 - Share initialized-type contexts from native export roots

- **Status:** Maintainer-directed performance refinement; focused correctness and
  Linux measurements passed, refreshed candidate qualification still required.
- **Decision:** D171's initialized-type specialization also considers non-looping
  explicit library export roots and orders those roots before inner loops.
  Their direct callees can share one guarded initialized context. Executable
  root eligibility and ordering remain unchanged.
- **Proof boundary:** Keep the exact immutable enum publication checks, explicit
  state-2 guards, original cold/reentrant/failed fallback, mutable field loads,
  clone provenance and transformation budgets. A successful ensure alone still
  does not establish complete initialization. No eager initialization or Java
  bookkeeping is introduced. Final bridge lifetime/retention proofs revalidate
  the actual transformed program before any producer can emit it.
- **Evidence:** Paired three-fork development comparisons improve Linux ARM64
  from 78.45 to 76.71 ns/cycle and physical x86host from 178.39 to 176.81.
  Ten focused tests pass, covering non-looping library guards, ordinary safety,
  cold/recursive/failed initialization, exact traces/cleanup, final lifetime/root
  rejection, retention commits, artifact parity and actual OrderBook allocation
  and weak recreation. See `JAVA_BRIDGE_OPTIMIZATION.md` for exact evidence.
- **Scope:** Supersedes D171's loop-only region selection for explicit native
  exports. All proof requirements and D132/D133 performance constraints remain.

## D224 - Specialize private enum entries and reduce permanent cache collisions

- **Status:** Maintainer-directed Linux performance implementation. Numerical
  acceptance and final distribution qualification remain separate gates.
- **Decision:** Add two private constant entries for an admitted permanent
  instance method with exactly one nullable two-constant enum argument, at
  least two other primitive arguments, no other reference argument and no String
  or enum result. Generated Java selects the exact constant entry without that
  argument in its private JNI ABI. Null uses the original generic entry. There
  is no public overload or new supported API. Selection is linear per method,
  without combinations of multiple enum arguments.
- **Proof boundary:** Prove the original full surface first, retaining generic
  roots and conservative effects. Include every added typed entry in generated
  construction facts, exception closure, native export roots and final lifetime
  proofs. Fixed conversion still ensures native initialization before loading
  its exact constant; only existing state-2 proofs may eliminate that work.
  Preserve cold, reentrant, failed and null behavior. Root/view transport is
  unchanged. Validate exact private descriptors and register each shared result
  converter once, including in the packaged manifest used by assembly.
- **Cache:** Start the per-artifact permanent weak table at 256 buckets instead
  of 16; per-root tables remain 16. This adds 240 reference slots once per
  artifact and no new hit-path operation. Preserve hashing, weak identity,
  collection/recreation, delayed-queue removal, growth and failed-insertion
  behavior. Allocation tests calculate the growth threshold from actual capacity
  and still require exact zero allocations on warmed hits.
- **Evidence:** Ten paired physical Linux x86-64 Java21 forks improve the
  settled cycle median from 174.59 to 167.90 ns; all ten pairs improve. Linux
  ARM64 improves from 78.12 to 75.56 ns, nine of ten pairs, but still trails Java.
  Keep the original protocol, volatile-enum control, all fork variation and the
  negative initial Java22 latency cell. See `JAVA_BRIDGE_OPTIMIZATION.md` and
  its artifact identities. These results are not a promise for every method or
  a claim of numerical acceptance.
- **Scope:** Refines D220/D221's implementation and supersedes only the permanent
  cache's initial capacity. No reclamation, D132/D133, version, API, retention or
  exception contract is superseded. Java21-23 and Java24+ refusal remain.

## D225 - Accept the measured Java Bridge implementation and defer further tuning

- **Status:** Accepted by the maintainer on 2026-09-28 after reviewing the
  three-scenario Linux results and the optimization investigation.
- **Decision:** Accept the implemented and tested bridge at its measured
  performance and close this optimization run. Further performance work is
  deferred. Linux x86-64 is faster than Java in the recorded OrderBook workload;
  Linux ARM64 remains slower than Java, and both remain below standalone
  Ironwood. These limitations are accepted for moving forward, not relabeled
  as achieved speed targets or proof that further optimization is impossible.
- **Evidence:** `JAVA_BRIDGE_D224_PERFORMANCE.md` records the final three-target
  measurements and matching artifacts. The accepted OrderBook jar SHA-256 is
  `b2f4a19d10a49290133259de2faef67cd699c52b96c2930e98f31dec7205f17e`,
  produced from implementation commit `694ada30`; later local commits record
  verification and rejected experiments without changing production code.
- **Follow-up:** Consolidate documentation of the accepted candidate, supported
  platforms and known limitations, preserving P6b's technical qualification
  requirements and exact evidence. The OrderBook Java consumer already provides
  real Java-to-Ironwood integration; another application is optional, not a
  missing phase. Release preparation and publication belong to the maintainer
  and are outside this agent task. P5 callbacks, P7 extensions and Java24+
  support remain deferred. Later tuning may use OrderBook or another selected
  application workload.
- **Scope:** Supersedes this run's requirement to continue optimizing until the
  bridge beats Java on every Linux target or approaches standalone performance.
  Those objectives become deferred performance work. No API, reclamation,
  exception, D132/D133, supported-version or qualification contract is weakened.

## D226 - Implement P5 with a dedicated listener example

- **Status:** Authorized by the maintainer on 2026-09-28.
- **Decision:** Start P5 using a separate result-processor/listener example in
  `examples/java-bridge/listeners/`. Preserve the official OrderBook project
  and its benchmark definitions. Compare equivalent native processor/native
  listener, Java processor/Java listener and native processor/Java listener
  workloads, with Linux as the performance judge.
- **Contract:** Follow the implementation plan's synchronous calling-thread
  callback scope, typed foreign calls, conservative escape/effect proofs,
  explicit invocation contexts, nested exception containment, retained-listener
  lifecycle and active-use protection. Unknown Java behavior may retain,
  allocate, throw and reenter. Incomplete capabilities remain producer errors.
  P5 completion still requires every listed safety and validation exit.
- **Scope:** Supersedes D225's P5 deferral. P7 and further OrderBook tuning stay
  deferred. No supported Java version, reclamation or D132/D133 contract changes.
  The maintainer intends to evaluate P5 and P7 before considering a candidate;
  this does not authorize implementing P7 or taking over their release work.

## D227 - Preserve native exception lifetimes for retained Java callback carriers

- **Status:** Accepted by the maintainer on 2026-09-28 during P5 implementation.
- **Decision:** A Java exception carrier retained by native code follows the
  existing process-lifetime rule for caught or escaping native exceptions. Its
  owned Java global reference remains live with the carrier, preserving later
  unchanged rethrow identity. Unknown retention is treated as retention.
- **Cleanup:** Reclaim only compiler-owned callback carriers proved not to
  escape the invocation, after all native aliases, pending cleanup and unwind
  uses have ended. Catching or replacing an exception is not itself that proof.
  Exercise both reclaimed nonescaping carriers and deliberately retained carriers;
  distinguish intentional retention from leaked temporary JNI references.
- **Scope:** Clarifies P5 carrier ownership without changing ordinary exception
  ownership or permitting source `free` of caught/thrown objects. Reclaimable
  retained exceptions require a separate ownership change and are outside P5.
  No mandatory proof, exception identity or boundary containment rule is relaxed.
- **Implementation:** Public producer composition selects carrier cleanup per
  entry at compile time. Only the invocation-owned subset receives the existing
  strict destruction proof. Built-in Throwable static slots and bounded native
  cause/secondary graphs may retain carriers; unknown lifetime remains retained.
  No runtime policy flag, owner publication permission or source free exemption
  is introduced.

## D228 - Wrap Java callback failures enriched by native code

- **Status:** Accepted by the maintainer on 2026-09-28 during P5 implementation.
- **Decision:** When native code adds a cause or secondary cleanup failure to a
  Java callback carrier, translate that carrier as a wrapper containing the
  unchanged original Java throwable and the native additions. An unchanged
  carrier still rethrows its original Java throwable by identity.
- **Representation:** The wrapper's cause is the original Java throwable. Native
  secondary failures become suppressed snapshots on the wrapper. A native-added
  cause is represented by a labeled suppressed wrapper whose cause is that
  native snapshot, preserving its distinction from a secondary cleanup failure.
  Preserve the original throwable's cause, suppression and stack trace, including
  when suppression is disabled. Repeated retained rethrows build fresh wrappers
  without modifying or accumulating additions on the original Java object.
- **Scope:** Refines P5's previously unspecified modified-carrier translation.
  Existing bounded graph copying and D227 ownership still apply. This adds no
  source reclamation permission; public callback exports still require their
  independent complete admission proofs.


## D229 - Preserve stable owner facades in synchronous callbacks

- **Status:** Implementation convention within D226 and the accepted P5 lifetime contract.
- **Decision:** Exact final owners admitted by the bounded callback proof may be
  passed to Java listeners as stable facades. The complete native closure proves
  that exposed owners originate only from guarded entry inputs. Java can retain
  the facade; later explicit owner free invalidates all aliases through shared
  root state. Resolve existing authoritative ownership and weak identity caching;
  never retarget a Java reference or create a second native owner.
- **Safety:** This host projection proof does not declare foreign arguments
  borrowed. Typed reference arguments retain unknown Java effects in ordinary
  source analysis, including mandatory free rejection. Native publication, hidden
  owner creation and unguarded owner origins remain producer errors. Balance
  temporary JNI references on success, callback failure and partial conversion.
- **Boundary:** Primitive/void callback results remain required. Other reference
  arguments, arbitrary escaping graphs and asynchronous callbacks remain rejected.
  Primitive-only callbacks gain no identity conversion or lookup on dispatch.

## D230 - Dispatch JNI callbacks through artifact-private Java relays

- **Status:** Measured implementation optimization within the accepted P5 contract.
- **Decision:** Generate static Java relays in the artifact-private support
  package. Each relay invokes the original listener method with its exact typed
  arguments and result. JNI uses cached static method IDs and a cached class
  reference, validated and released with the existing binding metadata.
- **Contract:** No event batching, callback deferral, extra hot-path allocation,
  exception wrapping or foreign-effect exemption. Typed native context and proxy
  lowering stay unchanged. Every callback still checks pending JNI exceptions,
  preserving native carrier transport and unchanged Java throwable identity.
  Generated relay source and class declarations participate in paired artifact
  inventory and loading. Ordinary native-only code is unchanged.
- **Evidence:** x86host Java 21 measures 103.021 ns/event for the original
  interface JNI path and 98.833 ns/event for the relay candidate. Java 22 improves
  from 107.616 to 100.895; Java 23 is nearly unchanged at 108.923 versus 108.407.
  This is a modest improvement and does not meet the maintainer's request for
  callback throughput close to pure Java/native. See
  [the investigation](JAVA_BRIDGE_CALLBACK_OPTIMIZATION.md). Numerical acceptance
  is not implied by functional qualification.

## D231 - Prove automatic batching for eligible callback loops

- **Status:** Explicitly authorized by the maintainer on 2026-09-28 after the
  JNI/FFM/batching control experiments.
- **Decision:** Extend the current callback performance task to compiler-proved
  automatic batching for eligible loops. Keep per-event Java listener calls,
  their order, exception identity and reentrant behavior. Use ordinary JNI
  dispatch whenever equivalence cannot be proved. No developer wiring or public
  callback signature changes are required by this optimization.
- **Proof boundary:** Computing future primitive results early must have no
  observable native effects or possible intervening failures. Preserve the
  listener captured by the original invocation and isolated buffers for nested
  invocations. A failing callback stops further delivery and follows the same
  protected native exception/carrier path. Unknown effects never acquire a
  borrowing or reclamation exemption. Original native-only code stays unchanged.
- **Transport convention:** For the initial proved counted loops, compute up to
  1,024 events into private reusable direct storage and deliver them through one
  JNI relay per chunk. Keep native computation in native code and Java listener
  work in Java. Batch from two events onward; missing optional storage selects
  ordinary JNI. The first callback may wait for chunk computation. Amortized
  throughput/latency measurements do not establish callback arrival percentiles.
- **Scope:** Supersedes the P7 batching deferral only for this measured automatic
  callback optimization. Other P7 extensions, including production FFM and new
  public array/zero-copy APIs, remain deferred. Java 21-23 and the Java 24+ refusal
  remain unchanged. The control's 2.48 ns/event is research evidence, not a
  promised or qualified production result.


## D232 - Plan the remaining P7 extensions in bounded submilestones

- **Status:** Planning requested by the maintainer after P5 completion; the
  maintainer separately deferred further callback performance optimization.
- **Decision:** Record the [P7 planning breakdown](JAVA_BRIDGE_PLAN.md#p7-submilestones-and-api-boundaries-d232):
  P7a preserves delivered automatic batching; P7b covers copied primitive arrays;
  P7c defines bounded byte buffers with an explicit API/lifetime gate; P7d covers
  finite factory-created generic facades and final-bounded mutation; P7e evaluates optional
  FFM while preserving default JNI; P7f qualifies the combined implemented surface.
- **Authority:** This records a plan, not acceptance of a new public buffer API,
  FFM deployment policy or authorization to implement the remaining extensions.
  It adds the missing P7 breakdown without superseding D191/D231's current
  producer boundaries. Unsafe/unimplemented capabilities stay rejected.
- **Continuity:** Preserve Java 21-23, Java 24+ refusal, all existing safety and
  ownership contracts, source/class/archive parity and unchanged official
  OrderBook sources. Deferring callback tuning does not claim its numerical
  target was met. Any material API or lifetime change is resolved at its stated
  gate before dependent implementation.


## D233 - Admit proved copied primitive array values

- **Status:** P7b implementation authorized by the maintainer on 2026-09-28;
  qualification checkpoints are recorded in the [array log](JAVA_BRIDGE_ARRAY_PROGRESS.md).
- **Decision:** Supersede D191/D231's public-array exclusion and D232's planning-only
  status for P7b only. Admit one-dimensional primitive arrays on proved methods,
  including mutable inputs, input-alias results and fresh invocation-owned results.
  Preserve all existing P0 borrowing, retention, origin and reclamation requirements.
  Source, class and archive reconstruction must confer identical authority.
- **Transport:** Use noncritical JNI regions and valid native arrays allocated
  inside protected typed entries. Coalesce repeated Java identities per call.
  Copy back only for closures that can write, once per identity in parameter order.
  Return the original Java object for an input alias. Copy fresh array results to
  Java and destroy their native storage on success and every delivery failure.
  Reclaim all conversion temporaries. Unrelated scalar calls acquire no array state.
- **Failures:** The maintainer chose original-failure precedence. Attempt later
  copy-backs after an earlier failure, preserving the native failure as primary or
  the first copy-back failure after native success. Attach indexed diagnostics;
  if aggregation cannot allocate or suppression is unavailable, explicitly report
  unavailable diagnostics on stderr without replacing the primary. Do not allocate
  reserved Java diagnostic objects on successful calls. Thrown native exceptions
  keep their previously accepted process lifetime.
- **Boundary:** Reject native retention, free of inputs, callbacks/reentry,
  unproved effects, retained/shared/native-field array results, constructors with array parameters,
  object/multidimensional arrays, varargs and listener array signatures. The caller
  excludes concurrent mutation. `System.arraycopy` remains rejected in these
  closures until its runtime failures have a proved protected boundary. Other
  result types still require their existing separate transport/lifetime proof.
  P7c-P7f are not authorized by this decision. Java 21-23 and Java 24+ refusal remain.


## D234 - Bounded byte views use JVM-owned storage and shared Java support

- **Status:** P7c0 accepted, P7c1 authorized on 2026-09-28. The maintainer approved
  JVM-managed storage, then requested P7c1 after the remaining shared-dependency
  gate was reported. That instruction accepts the documented public type and
  dependency and supersedes the earlier stop before P7c1 only. P7c1 is now
  implemented and qualified on all three targets; numerical acceptance remains
  maintainer review.
- **Ownership:** Java allocates and owns reusable backing storage; each view
  keeps its owner reachable. Native code borrows only for a synchronous call.
  Expose no address, backing buffer, externally closable storage or close/free
  operation. Release is JVM-managed with no deterministic deadline. No lifetime
  counters, locks or scans. D188 confinement and D189 reclamation of native
  Ironwood objects remain unchanged.
- **API and packaging:** The [accepted design](JAVA_BRIDGE_BUFFER_DESIGN.md)
  specifies `ironwood.bridge.ByteView`, exact source/Java methods, bounds and
  permission semantics, immediate writes and typed alias/lifetime proofs.
  A shared Java-only `ironwood-bridge-values.jar` permits reuse across independent
  artifacts. This supersedes D191's single-jar delivery and exact signature
  closure only for this explicit builtin dependency in buffer-using artifacts.
  Other artifacts keep their existing delivery and export rules.
- **Boundary:** D232's planning-only P7c status is superseded for P7c0/P7c1 only.
  Implementation authorization does not admit unfinished capabilities or weaken
  proofs. P7d-P7f remain pending; Java 21-23 and Java 24+ refusal remain unchanged.
- **Verification:** P7c0 was documentation/source/specification review only.
  The [P7c1 evidence](JAVA_BRIDGE_BUFFER_EVIDENCE.md) records focused positive
  and negative checks, Java 21-23 consumers, exact artifact/dependency identities,
  allocation and optimized machine-code evidence, and Linux measurements. The
  zero-copy path has useful read/update gains over copied arrays; overlapping
  writes retain a measured gap. The [log](JAVA_BRIDGE_BUFFER_PROGRESS.md) records
  checkpoints. No later P7 submilestone is authorized by this completion.


## D235 - Preserve exact generic source identities before bridge admission

- **Status:** P7d0 implemented under the maintainer's 2026-09-29 authorization
  for this checkpoint only. This supersedes D232's planning-only status solely
  for generic signature foundations. D236/D237 separately admit P7d1/P7d2;
  P7e-P7f remain pending.
- **Metadata:** Preserve scoped class/method variables, ordered full bounds,
  declaration erasure separately from substituted bounds, implicit-bound
  primitive eligibility, exact applied receiver/declaring-owner views and member
  parameters/results/throws. Public constructors, setters and unsupported
  members remain in the inventory; do not omit them to make an API admissible.
- **Identity and proof:** `BridgeCallableId.SourceSignature` is separate from the
  native callable/ABI identity. Exact signature resolution returns metadata
  evidence only. A generic source view does not acquire an exact native target
  through equal erasure, linkage or explicit parameter types alone; compare its
  receiver too. The inventory retains native candidates for separately proved
  dispatch protocols, including inherited empty-enum Java identity methods;
  these candidates are not exact direct-call bindings. Every lifetime, retention
  and reclamation proof remains required.
- **Revalidation:** Source-only secondary-bound changes can produce equal lowered
  programs. API facts therefore match only the final program instance from their
  semantic analysis. Source/class/archive reconstruction derives fresh facts;
  stable complete source signatures and native targets are checked against them.
  Existing artifact source reconstruction suffices; no format or runtime state is
  added. Unknown, absent or stale facts cannot authorize an export.
- **Boundary:** Generic class/method exports remain rejected, including unused
  type variables whose ABI is otherwise scalar. P7d0 does not generate generic
  Java declarations, enable raw Ironwood types, interpret Java unchecked casts
  as native layout evidence, or implement generic lifetime conversion. Later
  admission must support every Java-valid raw/wildcard/overloaded client use of
  its emitted declaration. Native ABI, transport and hot lowering are unchanged.
- **Verification:** The [P7d0 log](JAVA_BRIDGE_GENERIC_PROGRESS.md) records focused
  signature/proof parity, same-IR changed-bound rejection, producer refusals and
  adjacent bridge/ownership regressions. No new native performance or expanded
  platform claim follows from this compiler-only foundation.

## D236 - Read-only factory-produced generic Java facades

- **Status:** Accepted by the maintainer's separate P7d1 implementation request
  on 2026-09-29. P7d2 and later phases are not authorized by this checkpoint.
- **Supersedes:** D235's blanket generic-class export refusal only for this
  proved P7d1 boundary. D235's exact source identities/revalidation, P0 lifetime
  proofs, D192 non-reclamation and D132/D133 performance constraints remain.
- **Surface:** Final top-level generic classes with inaccessible constructors,
  no public generic methods and no type-dependent inputs can expose ordinary
  `Box<T>` declarations and native factories returning concrete applications.
  Arguments are exported final nongeneric native facade classes. Complete
  source allocations and exported results define the finite domain; unresolved
  or primitive production is rejected. Bounds may name Object, an admitted
  final facade or another class variable. Generic facade parameters, even fixed
  applications, remain rejected because client unchecked casts cannot prove
  native input types. Generic inheritance, arrays and listeners stay outside
  this boundary. Never hide an unsupported public member to accept a package.
- **Conversion:** Reference applications retain their existing shared native
  layout and code. Java raw/wildcard/unchecked views preserve the actual value
  identity and ordinary JVM cast behavior. Erased client hints never select a
  native layout. A cache miss dispatches on the actual result's native type ID
  within the proved finite final-class set; warmed reads reuse the existing
  address/identity-cache path. No generic tags, dynamic native specialization,
  new registry or generic-specific per-call bookkeeping are introduced.
- **Ownership:** Exact API identities remain separate from the shared storage
  identity used by native ownership/destruction. Fresh factory products require
  confined construction and the existing cleanup/retention proofs. Borrowed
  products share the exact owner's validity. Published generic families and
  unknown-origin variable results require complete non-reclamation proofs for
  every concrete alternative; no exemption follows from a generic declaration.
  Source allocation domains are bound to semantic facts and carried only through
  recorded additive adapters/native passes. Unknown effects and deallocation
  scans remain conservative. Missing-free mode cannot disable safety errors.
- **Verification:** The [generic log](JAVA_BRIDGE_GENERIC_PROGRESS.md) records
  signature and ownership parity, Java cast/identity behavior, permanent and
  borrowed products, negative APIs/unsafe frees, child-process native OOM,
  checked JNI, Java 21-23 consumers and matched getter allocation/code evidence.
  Numerical measurements are evidence for maintainer review, not a new release
  or a claim that P7d2 is implemented.

## D237 - Final-bounded generic construction and inputs

- **Status:** Accepted by the maintainer's separate P7d2 implementation request
  on 2026-09-29. P7e and P7f remain outside this checkpoint.
- **Supersedes:** D236's generic constructor and input refusal only for classes
  whose every variable has one exported final nongeneric facade class bound.
  D235 exact source identities, D192 non-reclamation, P0 ownership proofs and
  D132/D133 performance constraints remain mandatory.
- **Surface:** Final top-level reference-generic classes may expose public
  constructors, type-dependent inputs and applied facade inputs under these
  final bounds. Every Java-valid type argument is represented, including raw,
  wildcard and client-cast uses. Unrestricted or partially bounded mutable
  classes remain rejected; unsupported public members are never hidden.
  Generic methods, listeners, arrays, inheritance and primitive projections
  remain outside the accepted bridge surface.
- **Proof and conversion:** A final bound proves the sole native argument
  independently of observed allocations. Source signatures remain exact;
  generated private static JNI inputs use the bound's erased facade type.
  Existing shared native storage and root/permanent conversion apply. No type
  tags, runtime specialization, extra registry, allocation or generic-specific
  check is added. Ordinary Ironwood type and reclamation analysis is unchanged.
- **Ownership:** Inputs reuse existing exact owner, alias, retention-slot and
  repeated-call acyclicity proofs. Borrowed inputs retain their actual owner.
  Actual mutations are committed before exception translation; failed
  unpublished construction uses existing rollback. Unknown getter origins
  still require permanent-value proof. Loaded-slot transfers and unknown
  effects remain conservative rather than acquiring generic exemptions.
- **Verification:** The [generic log](JAVA_BRIDGE_GENERIC_PROGRESS.md) records
  source/class/archive parity, raw/wildcard clients, identity/null behavior,
  retained and borrowed lifetimes, normal/exceptional mutation, allocation
  failure, negative APIs, unsafe frees and matched setter code/measurements.
  Numerical performance acceptance remains maintainer review.

## D238 - Retain JNI after the P7e experiment and qualify the combined bridge

- **Status:** The maintainer accepted the keep-JNI recommendation, explicitly
  selected JNI only, and authorized P7f in the existing task on 2026-09-29.
- **Decision:** Close D232's P7e evaluation through its permitted keep-JNI
  outcome. [P7e0 measurements](JAVA_BRIDGE_FFM_EXPERIMENT.md) show only about
  0.23-0.39 ns/call improvement on physical Linux x86-64 for ordinary FFM,
  alongside additional native-access launch requirements. P7e1/P7e2 remain
  deliberately unimplemented; no optional FFM artifact or policy is selected.
- **Supersession:** Replace P7e's pending deployment-selection status with this
  accepted outcome. Preserve default JNI, Java 21-23 and the Java 24+ refusal.
  This does not weaken any proof or label an unimplemented FFM backend complete.
- **Next authorized work:** P7f combined qualification and documentation for the
  implemented callbacks/batching, arrays, byte views and bounded generics.
  Preserve the official OrderBook sources. Record matching artifacts, focused
  positive/negative tests, three-target/JVM evidence and numerical measurements
  in the [P7f log](JAVA_BRIDGE_P7_QUALIFICATION.md). Numerical acceptance remains
  the maintainer's review; publishing remains outside this task.


## D239 - Support JDK 21, 22 and 23 throughout the JNI bridge workflow

- **Status:** Authorized by the maintainer's JDK compatibility implementation
  request on 2026-09-29. Verification is recorded in the
  [JDK progress log](JAVA_BRIDGE_JDK_PROGRESS.md).
- **Decision:** The compiler build/run, bridge source/class/archive producer,
  standalone ByteView companion producer, assembly, distribution packaging and
  ordinary Java consumer workflow support JDK 21, 22 and 23. Compile Java at
  `--release 21`; preserve Ironwood's language level and the Java 21 public API
  and class-file baseline. JNI remains the only supported transport.
- **Selection:** JAVA_HOME explicitly selects Java and its build tools. Otherwise
  use the IDK bundle or resolve the PATH runtime's home. Java compiler/Javadoc
  APIs and JNI headers come from the running producer JDK. Missing components
  fail clearly, without substituting another JDK. Maven/Gradle producer examples
  use their running JVM's home. IDK native tooling stays bundled on Java override.
- **Identity:** Preserve exact compiler/runtime generation, common assembly
  bytes, native JDK version/vendor/header hashes, content inventories and failed
  publication preservation. Use a matching compiler build and matching producer
  JDKs for host assembly. Different JDK compiler/Javadoc outputs are not treated
  as interchangeable. Assembly may run on any supported JDK but still checks
  its exact compiler/runtime against its inputs. Reproducibility remains an
  identical-input contract.
- **Scope:** Supersedes the Java-21-only producer/build-tool restriction in the
  implementation and usage instructions. Extends D215/D218 without weakening
  artifact pairing. D203/D238's Java 24+ refusal, native-access behavior, memory
  safety, ownership, exception transport and D132/D133 remain unchanged. No
  warmed-call code changes or new runtime checks are introduced.

## D240 - Include native bridge dependencies in IDKs and use Apple's selected linker

- **Status:** Authorized by the maintainer's out-of-box macOS/Linux repair request
  on 2026-09-30. Verification is recorded in the
  [JDK progress log](JAVA_BRIDGE_JDK_PROGRESS.md).
- **Decision:** macOS native builds select the SDK and Apple linker through the
  installed Command Line Tools/Xcode developer environment. Pass their paths
  explicitly to the pinned LLVM 23 Clang driver for ordinary executables and
  shared bridge images. LLVM retains compilation and optimization under D012;
  Apple's development tools remain the macOS build prerequisite under D014.
  Honor SDKROOT/DEVELOPER_DIR and reject invalid explicit selections. Native
  bridge identity includes actual SDK settings/stub hashes and linker version/hash.
- **Packaging:** Linux IDKs include the complete pinned bridge support SDK,
  source, recipes, license texts and manifests at the installed discovery path.
  Verify its closure before and after staging; do not substitute system runtimes
  or weaken checksum checks. Source/host packages include preparation pins/recipe.
- **Supersession and scope:** Replace the SDK 26.5 workaround in IDK instructions
  and the requirement for Linux IDK end-users to prepare support separately.
  Extend D239's Java 21/22/23 workflow. Preserve Java 21 APIs/classes, Java 24+
  refusal, JNI transport, artifact pairing, memory-safety proofs, exception and
  native-access behavior, D132/D133 and existing runtime performance constraints.
  No compiler IR, runtime lowering or steady-state JNI code changes are made.

## D241 - Producer-selected critical calls for proved object entries

- **Status:** Implemented on 2026-10-02 under the maintainer's direction to make
  the OrderBook bridge as fast as possible on the ordinary Linux host. The
  default-off selection, the duration obligation and the Java 21 mechanism below
  are recorded for maintainer review.
- **Finding:** On the host, eight JNI calls cost about 61 ns of a 154 ns bridge
  cycle. Ordinary FFM keeps the same thread-state transition; only the critical
  linker option removes it. See the
  [investigation and measurements](JAVA_BRIDGE_CRITICAL_CALLS.md).
- **Decision:** `ironwoodc --java-bridge --critical-calls=on` adds, for each
  qualifying binding of an object projection, a second native adapter and a
  constant method handle linked with the critical option. The default is off and
  generates the unchanged JNI-only artifact. The selection is part of the
  generation identity (`calls=critical-v1`). Every selected binding keeps its
  registered JNI method, and generated Java uses it whenever a handle is absent.
  No call is re-executed.
- **Selection:** Static or instance methods only, with primitive or enum-token
  parameters and a void, primitive or permanent-address result, and without root
  state, reservations or retention slots. `BridgeCriticalCalls` additionally
  requires the entry's complete native closure, including initializers and
  cleanup, to contain only resolved native calls and memory-only operations.
  Java callbacks, unresolved calls and every unclassified operation refuse.
  The analysis feeds transport selection only. It admits no export and replaces
  no ownership, retention, non-reclamation or exception proof.
- **Producer obligation:** A critical call delays every JVM safepoint until it
  returns. The compiler proves the absence of Java reentry and of blocking
  runtime services. It does not bound running time. Selecting the option states
  that exported operations are short.
- **Failure transport:** The adapter calls the same protected entry with the
  same result frame. On failure it parks the status and exception for the calling
  thread and reports failure in the returned word: a status for void, 1 for an
  address, a high bit for 32-bit and smaller values. `long` and `double` results
  have no spare value, so Java reads one pending-failure counter after those
  calls. A JNI helper then delivers the existing translated exception. Only a
  failing call touches thread-local storage; D132/D133 are preserved.
- **Java and launch policy:** Java 21-23 and the Java 24+ refusal are unchanged,
  and classes stay Java 21 class files. Handles are created reflectively: the
  preview API on Java 21 without `--enable-preview`, the final API on 22/23.
  Linking is a restricted operation. The artifact never grants itself native
  access. With no option the JVM prints its own warning; a launch that enables
  native access only for other modules, or `-Dironwood.bridge.calls=jni`, selects
  JNI. Any linkage failure selects JNI.
- **Supersession:** Supersedes D238's JNI-only outcome for artifacts built with
  the option, and replaces the unimplemented P7e1/P7e2 candidate boundaries.
  P7e2 required a proved extremely short bound with no allocation, loops,
  initialization or raising work. D241 instead admits those under an explicit
  producer selection and proves only the absence of Java reentry and blocking
  services. D238 remains the default. Callback, value and rooted-state transport,
  D220-D224 and every lifetime contract are unchanged.
- **Evidence:** Median of 15 round-robin processes on the host, Oracle JDK
  21.0.1: bridge 1544.8 ms before, 1090.8 ms with critical calls, Java 1483.6 ms,
  standalone 734.7 ms for 80M operations. The remaining standalone gap is
  structural and is not claimed closed. Focused tests cover selection, exact
  inventories, every result carrier, failure delivery and each fallback route.

## D242 - Tune portable x86-64 bridge images for fast unaligned access

- **Status:** Implemented on 2026-10-02 with D241.
- **Finding:** LLVM's baseline x86-64 model assumes slow unaligned 16-byte
  memory access unless SSE4.2 or SSE4A is enabled. Portable bridge images
  therefore cleared and copied adjacent fields one word at a time. Feature
  isolation on the standalone OrderBook shows that this assumption, and no
  newer instruction, explains the difference from `-march=native`.
- **Decision:** x86-64 shared bridge images pass `-mattr=-slow-unaligned-mem-16`
  to `opt` and `llc`. The instruction set remains baseline x86-64 and the
  recorded `native.cpu` remains `baseline-x86_64`. The argument is a native
  build input (`cpu.tuning`). ARM64 images and ordinary executables are
  unchanged. `-march=native` is still not accepted by the bridge producer.
- **Compatibility:** Every x86-64 processor executes the emitted SSE2 moves.
  Processors older than SSE4.2/SSE4A may run them more slowly; they remain
  correct.
- **Evidence:** The native-only floor of the OrderBook bridge library improves
  from about 96 to 88 ns per cycle, and the JNI bridge from 1544.8 to 1464.0 ms.
  Applying the same tuning to portable executables is a separate decision.

## D243 - Reuse verified Java Bridge extractions across JVMs

- **Status:** Implemented on 2026-10-02. The maintainer selected this scope over
  per-JVM deletion after reviewing the alternatives below.
- **Finding:** The generated loader extracted into
  `jvm-<pid>-<start hash>/<generation>/<target>/` and never removed it. On the
  Linux x86-64 host each launch left about 25 MB: 23.9 MB of `libstdc++.so.6`,
  0.9 MB of `libgcc_s.so.1` and a 0.36 MB image. 74 leftover directories held
  1.8 GB with one runtime digest and three image digests. That `/tmp` is only
  emptied at boot. The per-JVM directory existed because the image filename is
  independent of its digest: two native builds of one generation would
  otherwise contend for one path, and the second would be refused forever.
- **Decision:** Extract into
  `ironwood-java-bridge-<owner hash>/<generation>/<target>/<payload digest>/`,
  where the digest covers the relative path and SHA-256 of every file selected
  for the target. The path carries no process identity, so later JVMs select it,
  repeat the owner, mode and SHA-256 checks on every file, and load it without
  writing. Each distinct file is published once as
  `ironwood-java-bridge-<owner hash>/blobs/<sha256>` and hard-linked into each
  build directory, so the private runtime is stored once per user rather than
  once per build. The loader deletes nothing.
- **Preserved:** Owner-only directories and files, validated at every level
  without following links. Exclusive partial files, verification before
  publication, and atomic hard-link publication that cannot replace an existing
  file. No overwrite or repair of an existing file or directory. One canonical
  path for identical payloads, so the JVM still refuses the same image in a
  second defining loader (D191, D201). D203's version refusal still precedes
  extraction. No process-global Java registry, shutdown hook or warmed-call
  work is added.
- **Changed behavior:** A broken or unsafe cached file is now refused by every
  launch until the user removes it, where a new JVM formerly started from an
  empty directory. A damaged blob is refused before any build links to it and
  blocks every build that shares it. A different native build of one generation
  in a second loader of the same JVM formerly failed with a digest mismatch; it
  now selects its own directory and loads as an independent image, exactly as a
  different generation in a second loader already did. Isolated duplicate
  worlds remain unsupported. Stale partial files from a killed extraction stay
  in `blobs` and are never selected. The cache grows by one image per distinct
  build and is bounded by builds, not launches; users may delete it between
  launches.
- **Alternatives rejected:** Deleting the per-JVM directory at exit misses
  killed or crashed JVMs, and a sweep of directories whose owner is gone
  misjudges liveness when a temporary directory is shared across PID
  namespaces, so it could delete a live JVM's image before it loads; it also
  makes the loader delete paths it did not create. Unlinking after load leaves
  nothing on success but removes the on-disk image that debuggers, profilers
  and crash reports resolve, extracts again for a refused second loader, and
  would need D210's macOS signature qualification repeated. Both keep the full
  write on every launch.
- **Supersession:** Replaces the JVM PID/start component of the P2 loader cache
  recorded in `COMPILER.md` and the progress log's statement that a later JVM
  gets its own cache. Refines the plan's loader step 4. D191, D201, D203 and
  D210 are unchanged.
- **Verification:** The generated-loader source test covers path identity,
  write-free reuse by a fresh loader, coexisting builds of one generation, a
  runtime file shared between builds, and refusal without repair of a damaged
  image, damaged blob, symlink and unsafe directory. The permanent-facade loader
  test reruns a consumer JVM against a populated cache and against a cache
  holding another optimization level's build of the same generations. On the
  Linux x86-64 host five OrderBook launches left 24.6 MB in total where the
  previous loader added 24.6 MB per launch, and a second build added 0.4 MB.

## D244 - Tune every portable x86-64 image for fast unaligned access

- **Status:** Implemented on 2026-10-02. The maintainer accepted the default
  after reviewing the measurements below.
- **Finding:** D242 cleared LLVM's `slow-unaligned-mem-16` tuning for x86-64
  shared bridge images only, and left ordinary executables on the baseline
  model, which zeroes and copies adjacent fields one 8-byte word at a time.
  Reproducing D242's feature isolation on portable OrderBook executables showed
  the same gap: 817.2 ms at baseline against 733.6 ms with only the tuning and
  734.8 ms with `-march=native` (medians of 15 round-robin processes, 8M warmup
  and 80M measured operations). The latency benchmark's median mean batch
  improved from 82.50 µs to 73.78 µs against 75.70 µs for `-march=native`. The
  `examples/bench` programs were unchanged within variation.
- **Decision:** `NativeBackend.portableTuning` passes
  `-mattr=-slow-unaligned-mem-16` to `opt` and `llc` for every image whose
  target triple is x86-64 and whose target machine is the default, executables
  and shared images alike, at every optimization level. The instruction set
  remains baseline x86-64. `-march=native` keeps `-mcpu=native` and the host
  processor's own tuning without the argument. ARM64 targets, the raw
  `--emit-llvm` module, the Clang-compiled runtime objects and the bridge's
  recorded `cpu.tuning` input are unchanged.
- **Machine code:** In the OrderBook `Bench` executable the optimized IR differs
  only in the function attribute groups that record the feature; the mid-end
  makes the same decisions and `llc` alone reproduces the tuned assembly.
  Instruction selection merges runs of `movq $0, n(%reg)` into `xorps` and
  `movups` stores and copies through `movups` and `movdqu`. No mnemonic appears
  that the baseline build did not already use; `Bench.run` shrinks from 903 to
  857 instructions. D132 and D133 are untouched: no bookkeeping or helper call
  is added on any path.
- **Compatibility:** Every x86-64 processor executes the emitted SSE2 moves.
  Processors without SSE4.2/SSE4A may run them more slowly; they remain correct
  and were not measured. The official `BENCHMARK.md` results use `-march=native`
  and are unaffected.
- **Supersession:** Refines D242, whose "ordinary executables are unchanged"
  boundary no longer holds; D242's shared-image behavior and recorded identity
  are unchanged. The D134 `-O3` pipeline options are unchanged.
- **Verification:** The focused test `portable x86-64 tuning merges adjacent
  stores with baseline SSE2` asserts the triple selection, the merged stores and
  the SSE2-only instruction set on an x86-64 host, and links every target
  machine on every host. On the Linux x86-64 host with the tuning active,
  `scripts/test-bench.sh`, the OrderBook demo output and seven focused native
  `-O3` tests (pool release helpers, stack-trace round trips, native target
  layout, field value forwarding, field aliases, initialized specialization and
  Throwable rendering) passed unchanged.

## D245 - Admit Java 24 and 25 under the JDK's native-access policy

- **Status:** Implemented on 2026-10-02 at the maintainer's direction, so the
  bridge can be measured on the Java 25 JVMs that `BENCHMARK.md`'s pure-Java rows
  use. The qualification below is the review basis for that measurement, not a
  published release.
- **Decision:** The producer (`BridgeBuildTools`), the generated `Support`
  loader and the generation metadata admit exactly Java 21, 22, 23, 24 and 25.
  `java.supported` becomes `21,22,23,24,25`, which changes every generation
  identity; `java.release` stays 21 and generated classes remain Java 21 class
  files. Java 26 or later and anything below 21 are still refused by the same
  Java-only predicate before extraction, `System.load` or native bootstrap, with
  the diagnostic now naming Java 21-25. No bypass or self-granted access exists.
- **JEP 472 behavior:** On Java 24 and 25 `System.load` is a restricted method.
  With no option the JVM prints its own four-line warning once per module, naming
  `java.lang.System::load`, the artifact's `Support` class and jar, and
  suggesting `--enable-native-access=ALL-UNNAMED`; loading then proceeds. The
  warning is the JDK's, and the bridge neither prints nor hides it. D241's
  critical-call handles link under the same per-module grant, so one grant covers
  both. The grants are `--enable-native-access=ALL-UNNAMED` for the class path,
  `--enable-native-access=<Automatic-Module-Name>` for the module path and the
  `Enable-Native-Access: ALL-UNNAMED` manifest attribute for an executable jar
  started with `java -jar`. Under `--illegal-native-access=deny`, `System.load`
  throws `IllegalCallerException` inside the facade's class initializer, before
  native bootstrap: the first use fails with `ExceptionInInitializerError`, later
  uses with `NoClassDefFoundError`, no partially bound world exists and the
  extracted image is never mapped. `-Dironwood.bridge.calls=jni` still selects
  JNI and `-Xcheck:jni` passes. Native access enabled only for other modules now
  follows the default warn policy on 24/25, so the handles link with the
  warning, where Java 21-23 keep the silent JNI fallback.
- **Qualified:** macOS ARM64 with pinned Temurin 24.0.2+12 and 25.0.4.1+1 as
  producer and consumer, and Linux x86-64 with Oracle JDK 25.0.4.1 and Oracle
  GraalVM 25.0.4 as producer and consumer, through the focused tests below and
  the OrderBook bridge workflow. Not verified: Linux ARM64 on Java 24/25, Temurin
  24/25 on Linux, other vendors' JVMs, and the IDK, Maven/Gradle and
  full-matrix qualification runs on the new releases. Single observations from
  the Linux runs are recorded in the [Java 25 report](JAVA_BRIDGE_JAVA25.md);
  they are not benchmark results.
- **Supersession:** Supersedes D203's refusal, and D209's retained-refusal
  outcome, for exactly Java 24 and 25; the "Java 24+ refusal" statements carried
  by D238, D239 and D241 are superseded to the same extent. D203's mechanism
  remains for every other version. D241's fallback routes, D243's extraction and
  every ownership, retention and exception proof are unchanged. The pinned
  Temurin 24 and 25 macOS launchers become supported matrix pins instead of
  refusal controls. Their pin files are byte-identical to the provisioned
  installations' recorded pins, so they keep their original `purpose` text;
  Linux pins for them are not provisioned.
- **Verification:** `BridgeBuildToolsTests` and `BridgeLoaderSourceTests` check
  the predicates with 21-25 accepted and 8, 17, 20, 26, 27 and 99 refused. The
  object, root and retaining producer tests run their consumers on pinned Temurin
  22-25, and on 24/25 additionally assert the exact default warning, silence
  under each of the three grants, and clean denial. The enum facade test keeps
  Java-only inspection extraction-free on 24/25 before the first native load. The
  critical-call test asserts the warn-policy handle linking and clean denial with
  and without the JNI override when the runner is Java 24+. All of these, with
  the loader, identity, permanent facade, private enum, OrderBook producer,
  OrderBook allocation and assembly tests, passed on macOS ARM64 with JAVA_HOME
  set to the pinned 21, 24 and 25 JDKs and on Linux x86-64 with Oracle JDK 25 and
  GraalVM 25, as listed in the Java 25 report.

## D246 - Present the Java Bridge as implemented and accept the recorded measurements

- **Status:** Accepted by the maintainer on 2026-10-03 after that day's
  three-platform compiler suite runs.
- **Decision:** The Java Bridge is an implemented, supported feature. Current
  documentation, the `BridgeExportSurface` selectors (`scalarValues`,
  `staticValues`) and the producer diagnostics no longer call it a preview or
  experimental. The recorded P5 listener and P7 extension measurements in
  `JAVA_BRIDGE_P5_EVIDENCE.md`, `JAVA_BRIDGE_ARRAY_EVIDENCE.md`,
  `JAVA_BRIDGE_BUFFER_EVIDENCE.md`, `JAVA_BRIDGE_GENERIC_PROGRESS.md`,
  `JAVA_BRIDGE_CRITICAL_CALLS.md` and `JAVA_BRIDGE_P7_QUALIFICATION.md` are
  accepted as the reference results of the implementation; no numerical review
  remains open, and D225's deferral of further performance tuning stands.
- **Scope:** The supported API is what the producer guide documents. General
  object inheritance, object and multidimensional arrays, listener shapes outside
  the proved subsets and optional TLS dependencies in bridge images are not
  supported and are rejected at compile time; the implementation plan records
  them as deferred work, not as pending parts of this feature. D215 keeps its
  historical title, and dated evidence and progress logs keep their wording.
  This supersedes the pending-qualification wording in D215's scope and the
  "remains review" status of the P5 and P7 measurements in earlier decisions.
  No API, reclamation, exception, safety or supported-version contract changes.
  Merging `java-bridge` into `main` and publication remain the maintainer's
  release steps.
- **Evidence:** The 2026-10-03 full compiler suites on macOS ARM64, Linux ARM64
  and Linux x86-64 pass after three test-only fixes, each rerun on all three
  platforms; `LOCAL_TESTING.md` describes the platform workflow.

## D247 - Preserve current array-store order through ownership snapshots

- **Status:** Implemented as the M0 preparatory ordering contract on 2026-10-04.
- **Decision:** FunctionAnalyzer's immutable array-slot snapshot preserves the
  current-store insertion order of its LinkedHashMap builder. A store removes
  and reinserts its slot, so overwriting a slot updates that current-store order.
  Ownership merges retain incoming-path list precedence, then traverse each
  path's slot membership in that order before choosing a blocking store witness.
  Use an independent ordered shallow copy with immutable membership and null
  entry rejection. ArraySlot equality remains container identity plus index;
  allocation values remain identity references. Minimum-index/container-allocation
  precedence in the ordinary free probe stays explicit and unchanged.
- **Reason:** Original Map.copyOf erased the builder order. Fresh M0 resource
  processes chose either of two incoming array stores for the same SlotOrder
  explanation, while preserving its mandatory error. JDK hash iteration must
  not choose the first explanation store or become a native compatibility rule.
- **Boundary:** This changes the selected diagnostic witness, not mandatory
  safety, ownership, alias facts, or emitted runtime bookkeeping. It does not
  settle other snapshot maps, retained-owner traversal or optional-budget
  prefixes. No earlier decision is superseded. Original J0 inputs and differing
  evidence are retained; repaired references need a distinct source/seed identity.
- **Verification:** The focused array-snapshot regression covers immutable
  independent membership/null rejection and forward/reverse stores over
  8/32/128 slots, all missing-free modes, explain parity and accepted fully
  detached controls. Results and the focused existing-consumer checks are
  recorded in the M0 qualification record; this decision does not establish S0.


## D248 - Derive list copy item loans from verified storage reads

- **Status:** Implemented during M1.1 on 2026-10-04.
- **Decision:** Add `ironwood.ds.ArrayList.copy()` with independent ordered
  mutable membership and iterator storage, borrowed non-null items, default
  growth factor and `max(1, size())` initial capacity. Use indexed traversal and
  explicit failure cleanup; do not touch the source reusable iterator.
- **Proof:** Extend the existing body-verified fresh borrowing factory proof
  with a container-element input. It requires the exact audited bounds-checked
  ArrayList read, owned private storage, the exact private read-only primitive
  bounds guard and a single
  resolved target. At the caller, an exact unexposed local ArrayList supplies
  its actual item loans to the fresh result. Self-items and nested containers
  remain loans. Unknown, dependent or exposed sources retain the conservative
  source root. Temporary-list summaries do not treat element inputs as a
  single source-root value. No method spelling receives a copy exemption.
- **Reason:** The previous factory proof classified reads from owned list
  storage as unknown results and exposed all caller items. The structural
  distinction permits builder retirement while preserving destination loans.
  Exact-sized storage avoids geometric growth allocations in snapshot copying.
- **Boundary:** Existing removal, publication, subclass and callback restrictions
  remain intact. This does not qualify arbitrary map copies, private snapshot
  constructors or S1/G1. No runtime bookkeeping or lowering changes are added.
  D129's copy deferral is fulfilled only for this method; its other deferred
  operations remain absent. No other earlier decision is superseded.
- **Verification:** Focused accepted/rejected lifetime checks in all unfreed
  modes, nested/self/publishing-subclass controls, delegated factory acceptance,
  source/class/archive reconstruction, native ordered membership and iterator
  continuation, 8/32/128/512 allocation scaling, and every allocation-failure
  boundary. See [the M1 list-copy record](self-hosting/m1/ARRAY_LIST.md).

## D249 - Prove private copied membership and its read lifetimes structurally

- **Status:** Implemented during M1.1 on 2026-10-05.
- **Decision:** Add compiler-private `SnapshotList` and `SnapshotBits` helpers.
  A list snapshot owns independent ordered storage and borrows its items; its
  read interface lends aliases under the snapshot lifetime. Logical bit copies
  use existing BitSet construction and `or`, with capacity pre-sizing for
  nonempty ordinary bounds, one-word empty storage and overflow-safe growth.
- **Proof:** Recognize a final Object-derived wrapper with one private final
  owned field, no captured owner/delegation/initializer, a null-only guard and
  one body-proved list-copy assignment. Transfer actual local list item loans,
  retaining whole roots for unknown/exposed/dependent sources. Verify primitive
  count purity only for item projections. A single delegated owned-list read
  lends reference results under the wrapper. Unknown wrapper methods expose
  copied payloads conservatively. Proved primitive payload observations do not
  expose wrapper membership; every resolved override must satisfy that proof.
- **Reason:** Ordinary constructor exposure rejected safe builder retirement.
  Merely suppressing it left getter aliases untracked and admitted a use after
  free. Construction, reads and conservative exposure therefore form one
  qualified contract. No method-name ownership exemption or runtime metadata
  is introduced, and LLVM lowering is unchanged.
- **Boundary:** List aliases may conservatively require a live snapshot even
  when the payload has its own owner. Unknown mutation/publication keeps ordinary
  safety checks. The BitSet capacity query costs roughly one nanosecond in the
  measured one-word case and removes a growth allocation for wider sets; measured
  wider copies are faster. M1 map/set, composition, traversal and B7 work remain.
  This extends D248 for the private wrapper and count proof only. It does not
  supersede other decisions or establish S1/G1.
- **Verification:** All-mode alias/nested/self rejection, primitive observation
  and publication/override pairs, unrecognized constructor controls, Java 21
  contract checks, class/archive loan reconstruction, null and trailing-zero
  cases, maximum-bit overflow, native allocation scaling and every representative
  failure boundary. See [the private snapshot record](self-hosting/m1/SNAPSHOTS.md).

## D250 - Derive independent map-copy loans from direct membership traversal

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Decision:** Add original `copy()` helpers to value, identity and linked maps,
  with independently owned storage and borrowed non-null key/value references.
  Traverse private membership without touching the source iterator. Preserve
  linked order and ordinary value-key callback behavior, including exceptions.
- **Proof:** Qualify the actual constructor, traversal, insertion and cleanup
  bodies. Preserve concrete typed-call result arguments for callback analysis.
  Transfer current local item loans, retaining whole roots for unknown sources.
  Monotonic possible-key observation metadata preserves nested callback exposure
  across joins/copies without misclassifying ordinary nested values. Fallback
  roots remain possible keys; metadata cannot establish reclamation safety.
- **Boundary:** Existing getter/cursor exposure and individual-removal limits
  remain conservative. No runtime bookkeeping or lowering change is introduced.
  This extends D248's fresh membership proofs; it supersedes no earlier decision
  and establishes neither a complete M1 checkpoint nor S1/G1.
- **Verification:** All-mode lifetime/publication pairs, conditional insertion,
  copy-of-copy, nested key/value controls, source/class/archive reconstruction,
  Java 21 logical contracts, geometric allocation counts, every representative
  OOM boundary and ordinary throwing-callback cleanup. See [the map-copy record](self-hosting/m1/MAPS.md).

## D251 - Prove set copying through owned backend and confined iterator construction

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Decision:** Add original `copy()` helpers to value, identity and linked
  sets using independent map storage and a fresh owned iterator. Borrow non-null
  items, preserve linked order and source cursor state, and propagate ordinary
  value callbacks after failure cleanup. Identity copying invokes no callbacks.
- **Proof:** Require the actual private two-assignment constructor, a proved
  backend copy and confined iterator owner capture. Carry callback obligations
  on borrowing inputs through delegation and copied-wrapper construction, with
  actual value-item dispatch checked at each caller. Transfer local item loans;
  retain whole roots for unknown/exposed/dependent sources and preserve nested
  key publication. The original general constructor boundary is superseded by
  D252's mandatory helper-confinement correction. Bodyless copy
  targets receive no proof and bad-source diagnostics do not crash the compiler.
- **Boundary:** Clear/destruction and retained aliases govern item lifetime;
  individual removal does not discharge loans. Combined lookup on an exposed
  source's copy remains conservative. No runtime bookkeeping or lowering change
  is introduced. This extends D250 for selected sets, supersedes no earlier
  decision and establishes neither full M1 nor S1/G1.
- **Verification:** All-mode safe/unsafe lifetime and callback controls,
  constructor/reset publication, Java logical contracts, source/class/archive
  reconstruction, geometric allocation counts, ordinary callback failures and
  every representative OOM boundary. See [the set-copy record](self-hosting/m1/SETS.md).

## D252 - Confine helper captures of an in-progress constructor receiver

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Problem:** A set iterator's reset can publish its retained parent and throw.
  Previously, programs without explicit frees admitted this construction under
  off/warn; error only diagnosed a missing free. Automatic rollback then reclaims
  the observable incomplete parent. No unsafe admitted native program was run.
- **Decision:** Require argument confinement when a constructor passes its
  in-progress receiver or a proved alias to another constructor. A retained
  argument must live only in a private encapsulated helper field. Reuse the
  existing confinement proof; do not bypass ordinary ownership or publication
  rules. Apply the check in every unfreed mode without requiring caller frees.
- **Proof:** Validate after all branch and loop phis are complete. Follow
  reference conversions and returned receiver/parameter origins, including all
  possible virtual/interface implementations. Cover both ordinary and planned
  generic constructor lowering. Confined captures and throwing helpers remain
  valid. The check changes diagnostics only; valid typed IR and native lowering
  gain no runtime bookkeeping.
- **Supersedes:** D251's unchanged general-constructor boundary and its earlier
  admitted publishing-reset control. Independent storage and item-loan contracts
  remain. This correction establishes neither full M1 nor S1/G1.
- **Verification:** All-mode source/class/archive publication-and-throw
  rejection, direct/converted/joined/loop/returned/dispatch aliases, accepted
  confined counterparts, native normal/failure cleanup and focused shared
  consumers. See [the set-copy correction](self-hosting/m1/SETS.md).

## D253 - Follow receiver identity through constructor-held fields

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Problem:** After D252, a constructor could store `this` in a private self
  field and pass, publish or call through that field. The typed effect check
  exempted constructor stores into the receiver's own fields but gave later
  loads no origin. It also accepted a store target that only may be `this`,
  and it lost what helper constructors stored. Ten compile-only probes were
  admitted, including a static store of a self field, a static or virtual call
  on it, a helper built in an instance method, a joined store target and a
  published local helper. Linking a publishing helper from classes or an
  archive was admitted even under `--unfreed=error`. Destructors could likewise
  resurrect `this` through a self field or a caught exception. No admitted
  program was executed.
- **Decision:** Keep D252's argument check and add a retention-aware
  publication analysis to the mandatory constructor and destructor checks.
  Each value tracks the parameters it may be, the parameter identities it may
  reach through fields, and the parameters whose contents it may reach. Only a
  constructor store into exactly its receiver, or into an element of that
  receiver's compiler-proven owned array, is retention. Every other store,
  static store, outward throw and foreign call publishes everything the value
  can reach. Locally thrown values reach the function's landing pads.
- **Proof:** Closed-world field marks only grow. A field a constructor wrote
  with its receiver or a retainer may return its holder; a field written with
  parameter-derived data may return what the holder retains. Constructor
  summaries expose retained parameters, so building a helper records what it
  holds, publishing the helper publishes those arguments, and a helper method
  that publishes held content publishes them as well. Callees cannot see
  retention established by their callers, so content effects resolve at the
  caller. The existing effect kernel's reclamation, returned-origin and
  projection facts are unchanged, so relowering, Bridge cleanup and the M0
  effect workload keep their inputs.
- **Boundary:** Diagnostics only: valid typed IR and LLVM are byte-identical
  and no runtime bookkeeping is added. The analysis is conservative: an array
  element store outside an owned receiver array still publishes its value.
  Existing escape-summary limits that keep some confined parents unfreeable are
  unchanged. This completes D252's rollback confinement without superseding it
  and establishes neither full M1 nor S1/G1.
- **Verification:** Fourteen unsafe probes are rejected in off/warn/error from
  source, and publishing helpers are rejected from class and archive links.
  Safe self fields, confined helpers, self-retaining collections, owned child
  arrays and a dropped caught `this` keep identical LLVM and native exits. The
  strict library, all 79 example/project trees, the D252 suite and focused
  copy/snapshot/pool/Bridge consumers pass. See [the field-alias record](self-hosting/m1/FIELD_ALIAS.md).

## D254 - Prove private keyed snapshot reads from copied bucket bodies

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Decision:** Add compiler-private `SnapshotMap`, `SnapshotIdentityMap`,
  `SnapshotLinkedMap` and `SnapshotIdentitySet`. Each final wrapper owns one
  D250/D251 copy, borrows keys, values and immutable child versions, and
  exposes only size, emptiness and lookup. No iterator or mutable view escapes.
  Null sources fail before storage acquisition.
- **Proof:** Reuse D249's one-field copied-wrapper constructor proof for map
  and set copies. Lookup bodies are proved structurally: they may read owned
  private buckets, entries, stored keys and values, private key helpers and
  null guards, and may return only stored entry values. Count reads must be
  pure primitive field expressions. Value-key `hashCode`/`equals` calls are
  reported as callbacks; each caller must then prove its actual key and query
  dispatch non-retaining. Callback-bearing lookups expose stored and queried
  key contents. Copied stored keys stay possible keys through nested copies.
  Lookup aliases conservatively retain the wrapper. Unknown, changed or
  publishing bodies keep ordinary effects, and no method name grants a proof.
- **Boundary:** Ordered traversal and restore are not part of this API; the
  seven-field operation composition adds them separately. Identity lookup runs
  no callbacks. No runtime bookkeeping or lowering change is introduced. This
  extends D249-D251 and D253 without superseding them and establishes neither
  full M1 nor S1/G1.
- **Verification:** All-mode loan, shared-child, changed-body, publishing
  callback, stored count/lookup callback and nested publication controls;
  class/archive loan reconstruction; Java 21 reference; native allocation
  scaling, membership, null rollback, callback failure and every OOM limit
  0-80. See [the keyed snapshot record](self-hosting/m1/KEYED.md).

## D255 - Use a compiler-private ring FIFO for the selected pilot worklist

- **Status:** Implemented during M1.2 on 2026-10-05.
- **Decision:** Replace the effect analyzer's selected `ArrayDeque` FIFO
  (WORKLIST_CONTRACTS Q20) with compiler-private `WorkQueue<E>`: append at the
  tail, take from the head, `isEmpty`, `size` and `clear`. Null items throw
  `NullPointerException` and an empty take throws `NoSuchElementException`, as
  in Java. A power-of-two ring reuses consumed slots, so takes never shift
  membership and storage is bounded by the largest simultaneous membership.
  Growth installs the doubled ring before copying and leaves the queue
  unchanged if allocation fails. The selected evidence-store iterator removal
  uses the existing `HashMap` iterator `remove()` and current-key accessor.
- **Proof:** No analysis change. The ring is an ordinary owned array field
  with the existing detached-backing growth shape. Queued items escape
  conservatively: freeing one while queued is rejected, and so is a later free
  after the queue is gone. The selected consumer queues block labels owned by
  the IR, so no loan discharge is needed.
- **Boundary:** No stack, general deque, sorting or iterator type is added; no
  stack consumer is reached by the pilot. Other WORKLIST_CONTRACTS queues stay
  with their M3/M6 owners. Item loan discharge remains conditional on a
  demonstrated consumer. This establishes neither full M1 nor S1/G1.
- **Verification:** Java `ArrayDeque` transcript parity for wraparound,
  growth and the analyzer's CFG order; zero allocations over 16,000 bounded
  adds/takes; ordinary null/empty failure lifetimes; every OOM limit; all-mode
  ownership controls; class/archive artifacts; retain-filter parity with the
  Java `retainArrayStores` loop. See [the worklist record](self-hosting/m1/WORKLIST.md).

## D256 - Normalize text blocks with a private stripIndent helper

- **Status:** Implemented during M1.3 on 2026-10-05.
- **Decision:** Add compiler-private `TextBlocks.stripIndent(String)` with
  Java 21 `String.stripIndent()` behavior, as the lexer's text-block step
  requires (FRONTEND_CONTRACTS API0166). Lines end at `\n`, `\r` or `\r\n`;
  incidental indentation is the minimum leading `Character.isWhitespace` count
  over nonblank lines and a blank last line; a trailing terminator disables
  outdent and is kept as one `\n`; trailing whitespace is removed and blank
  lines become empty. Escapes are untouched, so cooked decoding still follows.
  The implementation is original and is not a public `String` API.
- **Proof:** No analysis change. The input is borrowed; the result is a fresh
  owned String and the intermediate builder is freed before return.
- **Boundary:** Cooked escape decoding, raw/cooked token construction and the
  rest of the lexer remain M2.1 port work. This establishes neither full M1
  nor S1/G1.
- **Verification:** 20,012 inputs, including all terminator forms, tab, form
  feed, vertical tab, em space and non-whitespace no-break space, match Java 21
  exactly from classes and archive at `-O3`; each call leaves only its result
  live. All-mode ownership controls pass. See [the text-block record](self-hosting/m1/TEXT.md).

## D257 - Compose seven-field ownership snapshots from private snapshot helpers

- **Status:** Implemented during M1.1/M1.2 on 2026-10-05.
- **Decision:** The native `OwnershipSnapshot` is a final composite of the
  qualified helpers. States, retained-child versions and pool owners use
  identity maps; array slots and borrowed-field names use linked maps; the
  diagnostic live set uses an identity set. Keyed snapshots expose no
  iteration, so every field that restore or join traverses also keeps a
  `SnapshotList` of keys or members copied when the snapshot is taken: slot
  keys in D247 current-store order, and field names, retained-borrow owners,
  pool values, exposed members and live members in the live container's own
  order. States need no list: restore and join walk the append-only allocation
  list and look each node up. Exposed membership is only traversed, so it is a
  list alone. Retained-child versions are immutable `SnapshotList`s shared by
  reference; a join builds a new version rather than mutating one. State
  values are immutable versions owned by the invocation: snapshots share a
  node's current version, and a state change installs a new one. Value records
  and slot keys have explicit `equals`/`hashCode`; nodes keep identity. Live
  borrowed fields use a `LinkedHashMap`, so restore order is deterministic
  where Java's `Map.copyOf` order is incidental and later-only. Native builders
  reject null keys, values and members at insertion
  (`IllegalArgumentException`), so copies only see the non-null domain that
  Java's copies enforce with `NullPointerException`.
- **Proof:** No analysis change. Builders and traversal lists retire through
  ordinary local proofs; a snapshot retires exactly the storage it acquired; a
  null at any constructor position rolls back the fields already copied.
  Nodes, state versions and child versions read through a composite are
  conservatively exposed, so their later frees are rejected; they are
  invocation-lived, as the analyzer's own state is. Freeing a node or child
  version while a snapshot observes it, or using a retired snapshot, is
  rejected in every mode.
- **Boundary:** Retiring allocation nodes and versions is the M2.2 native
  lifetime proof the M0 handoff assigns to M2; this decision does not choose
  it. Sharing state versions changes only object identity of equal values,
  which no consumer compares. The fixtures' join is a demonstration of
  sufficiency; the real save, restore and join are ported in M2.2. No runtime
  bookkeeping or lowering change is introduced. This establishes neither full
  M1 nor S1/G1.
- **Verification:** A native save/mutate/save/restore/join projection equal to
  a Java reference of FunctionAnalyzer's seven-field rules; a 43-check native
  replay of the M0 value/null/copy contract; D247 order; snapshot storage of
  exactly 6n+75 allocations at n = 8/32/128 with full retirement; all-mode
  safe/unsafe controls; classes/archive at `-O3` with one classified
  `--unfreed=warn` diagnostic per fixture. See [the composition record](self-hosting/m1/COMPOSITION.md).

## D258 - Provide M1.3 value helpers and fix the pilot port conventions

- **Status:** Implemented during M1.3 on 2026-10-05.
- **Decision:** Add compiler-private `CharEscapes.decodeSimple` (the lexer's
  simple escapes as an `int` UTF-16 unit, or `ABSENT`, instead of a nullable
  boxed `Character`), `SnapshotInts` (an immutable int sequence replacing
  immutable `List<Integer>` segment counts, with wrapping sum and the
  specified `List.hashCode`) and `Lists.single` (a null-rejecting immutable
  one-item list). Ironwood rejects unchecked generic casts, so there is no
  generic shared empty list: each element type that needs one declares a
  process-lived static empty `SnapshotList`, which holders borrow and never
  free. Record value semantics become hand-written `equals`/`hashCode` on final
  classes, comparing each component as Java's record does: `==` for primitives
  and for classes without an equality override (allocation nodes, source
  files, evidence events), value equality for records and Strings, null-safe
  for nullable components, logical `BitSet` equality for summaries; compact
  constructor checks keep their order and messages.
- **Conventions:** The pilot port also applies these source rewrites, none of
  which needs a helper. `Optional` fields and results become nullable
  references, presence branches evaluate a fallback or projection exactly once,
  and the parser's two `orElseThrow` sites keep `NoSuchElementException`;
  `OptionalInt` becomes an index with -1 for absent. Streams, `forEach`,
  lambdas and method references become ordered loops with the same
  short-circuiting and empty-input results. `contiguousKinds(TokenKind...)`
  becomes fixed two- and three-argument overloads. The keyword table becomes a
  `switch` on the lexeme with identifier fallback, allocating nothing per
  token. Evidence counters are primitive. `var` gets its declared type, and
  each uninitialized local gets an explicit initial value without changing its
  guarded assignments. Joined and restored states walk the append-only
  allocation list. `String.matches` in the operation factory admits only its
  fixed literal target by equality; general regex stays with M3.1.
- **Boundary:** Ported record classes, the lexer's cooked string assembly and
  the keyword switch itself are M2.1/M2.2 consumer code. The generic singleton
  factory conservatively exposes its item. Eight M2.2 RECORD inventory rows
  whose simple names merely coincide with selected records (Binding, Site,
  Origin, Summary in Bridge, binder, scope and borrow-analysis classes) are
  excluded from the pilot. No runtime bookkeeping or lowering change is
  introduced. This establishes neither full M1 nor S1/G1.
- **Verification:** A native transcript equal to a Java 21 reference in four
  fresh JVMs: all 131,072 escape inputs against the real
  `Lexer.decodeSimpleEscape`, 64 generated sequences against
  `List.copyOf`/`IntStream.sum`/`List.hashCode`, `List.of(item)` behavior, and
  record semantics against the real `SourcePosition`/`SourceSpan` plus records
  with the selected component types. All-mode ownership controls and
  classes/archive artifacts with zero `--unfreed=warn` diagnostics. See
  [the value helper record](self-hosting/m1/VALUES.md).

## D259 - Let proof snapshots own their saved explanation evidence

- **Status:** Implemented during M1.1/M1.3 on 2026-10-05.
- **Problem:** RejectedFreeEvidence keys saved versions by a `WeakReference`
  to the proof snapshot, hashes it with `System.identityHashCode` and retires
  dead versions by polling a `ReferenceQueue`. Ironwood has no garbage
  collector, and a map-keyed native store could not free a saved version it
  removes, because a value taken out of a map is not a proven fresh allocation.
- **Decision:** Each native proof snapshot owns its optional saved evidence as a
  private field installed from the store's fresh-or-null `save()` factory.
  Version identity is the snapshot object; no identity hash, weak reference,
  queue or registry remains. Before freeing a snapshot, its owner calls the
  store's explicit release (the E6 unit, association and reference-count
  release); destroying the snapshot frees the saved storage. Saving reserves
  the whole version before copying (E2) or saves nothing; restoring a snapshot
  without evidence clears current state and reports truncation, as Java does
  for an absent version. Sites carry primitive reference counts that equality
  ignores; `close` releases the current associations.
- **Boundary:** This is the mechanism and its ownership proof, demonstrated on
  origin associations. The real store's six maps, budget arithmetic, merge
  intersections and limits are ported in M2.2. Java's collector could retire a
  version earlier than its last use; the selected pilot holds every version
  until verification, so accounting at close is unchanged. Sites remain
  invocation-lived. No runtime bookkeeping or lowering change is introduced.
  This establishes neither full M1 nor S1/G1.
- **Verification:** Budgets admitting two, one and zero saved versions all
  return every unit and site reference to zero and restore the storage
  baseline; all-mode controls accept retirement and reject a saved-version
  alias after its snapshot is freed, a separate free of the owned version and
  use of a freed store; classes/archive at `-O3` with zero `--unfreed=warn`
  diagnostics. See [the evidence store record](self-hosting/m1/EVIDENCE_STORE.md).

## D260 - Enforce the pilot's finite input model with exhaustive variant switches

- **Status:** Implemented during M1.3 on 2026-10-05.
- **Decision:** Add compiler-private `OperationVariants`, with one closed enum
  per restricted operation-model declaration: the `IrInstruction`,
  `IrTerminator` and `IrOperand` sealed roots and the `IrCallKind`,
  `IrCallableKind`, `IrType.Kind`, `AllocationOrigin`, `AllocationState`,
  `EventKind`, observer kind/phase and `UnfreedMode` enums. Each admission
  function is a `switch` expression without `default` whose rejection arm
  names every non-admitted variant, so the compiler's own exhaustiveness check
  is the variant coverage check: a new variant or a deleted treatment fails
  compilation. Native pilot input factories must reject any input whose
  variant is not admitted before analyzer entry. The effect analyzer's pattern
  `switch` over instructions becomes an ordered `instanceof` chain that
  evaluates its selector once and keeps the `null` default.
- **Admitted:** direct and foreign calls, value-returning returns, value
  references, DIRECT calls, METHOD callables, REFERENCE types, LOCAL_NEW
  origins, ACTIVE/ESCAPED inputs with UNCERTAIN as a join result, REASON
  events, the EFFECT/INITIAL observer and WARN mode, as OPERATION_MODEL states.
- **Boundary:** M2 writes the factories and consumers that call these
  functions; the frontend's AST variant treatments use the same exhaustive
  pattern in M2.1. Later-only roles keep their M3.1 gates. No runtime
  bookkeeping or lowering change is introduced. This establishes neither full
  M1 nor S1/G1.
- **Verification:** A test derives every list from the live Java sealed roots
  and enums by reflection and requires equality; removing a rejection, adding
  an untreated variant and dropping an admitted arm each fail compilation in
  every mode; the native admitted sets equal the model from classes and
  archive. See [the variant record](self-hosting/m1/VARIANTS.md).

## D261 - Port the frontend slice with frozen child lists and invocation-lived trees

- **Status:** Implemented during M2.1 on 2026-10-05.
- **Decision:** The selected Java frontend slice (in-memory `SourceFile.of` and
  `lineText`, source positions and spans, diagnostics and notes, tokens, the
  lexer, the parser with its ten private records, and the AST model) is ported
  under `compiler/src/main/ironwood/ironwood/compiler/`. Records become final
  classes with private final fields and accessors in record order; `Optional`
  components are nullable; list components are frozen `SnapshotList`s, and
  `List<Integer>` segment counts are `SnapshotInts`. `AstLists` freezes a
  builder into a fresh independent copy, or into the element type's
  process-lived empty constant for an empty builder, which mirrors
  `List.copyOf`. Nodes borrow their frozen lists. The parser owns every
  builder and parser-private holder and retires each with `defer` or `free` on
  every exit; nodes, frozen lists, tokens, spans and text are invocation-lived.
  Each sealed root becomes an interface whose implementations report a variant
  enum, and consumers dispatch with a `switch` expression without `default`
  (D260). The `destructor` record component is renamed
  `destructorDeclaration` because `destructor` is an Ironwood keyword.
- **Conventions:** The D258 rewrites apply. In addition, numeric validation and
  line-terminator normalization read index ranges of the source instead of
  substrings; the constructor-body statements are collected once instead of
  rebuilt into a second block; the type-argument and switch-label holders
  carry their span as its two positions, because no output retains that span;
  `defer free` reads its expression directly instead of building a discarded
  `FreeStatement`; and a labeled statement's message is built only when it is
  reported. Each rewrite keeps Java's tokens, spans, tree, diagnostics and
  their order.
- **Boundary:** `SourceFile.read`, `DiagnosticFormatter`, `TypeName` display and
  qualifier splitting, `DeclaredType`, `DeclaredTypes` and `PatternFlow` stay
  later-only (M3.1/M3.3). Java leaves three conversion residues and the
  subtrees abandoned by error recovery to its collector; natively they are
  invocation-lived and counted exactly: the receiver name of a qualified
  `this` or interface `super` (one allocation), a qualified superclass
  invocation expression and its span (two), and the subtree a failed class
  parse discards. Native code computes no digest: the test-only wire adapter
  prints the SHA-256 the harness supplies, and B5 SHA-256 stays with M3.2. No
  analysis, runtime or lowering change is introduced. This establishes M2.1,
  not S1/G1.
- **Verification:** Tokens, lexical diagnostics, ASTs and parse diagnostics are
  byte-identical to J0 for the 36 frozen frontend workloads, for the 125-unit
  combined source bundle, for every tracked `.iron` source and for 3,000 seeded
  mutations; `Character.isDigit`, `isWhitespace` and `digit(_, 16)` equal Java
  21 for all 65,536 UTF-16 units. The 376-method closure and the 119-declaration
  model are reconciled to native treatments. The pilot compiles and links under
  `--unfreed=warn` with no findings; an allocation census shows no temporaries
  left by accepted inputs; every allocation failure unwinds cleanly; paired
  ownership controls hold in every mode; and all runs fit the fixed frontend
  budget. See [the frontend record](self-hosting/m2/FRONTEND.md).

## D262 - Port the ownership slice with join owners and invocation-lived payloads

- **Status:** Implemented during M2.2 on 2026-10-05.
- **Decision:** The selected ownership, explanation and effect operations are
  ported under `compiler/src/main/ironwood/ironwood/compiler/` (`ir`,
  `semantic`, and `port/BitRows`). `FunctionOwnership` holds the seven
  ownership fields with Java's snapshot, restore, join, blocking and
  array-store rules; `RejectedFreeEvidence` keeps the six maps, budgets,
  limits and merge intersections on D259 snapshot-owned saved versions;
  `UnfreedAllocationTracker` keeps registration order and the live set; and
  `ClosedWorldEffectAnalyzer` computes Java's fixed point over the admitted IR
  with an ordered `instanceof` chain for its pattern switch. Records become
  final classes with nullable `Optional` components; streams and lambdas are
  ordered loops (D258); `PilotInputs` admits only the D260 variants and rejects
  every other input role before an analyzer exists.
- **Native lifetime proof:** Allocation nodes, state versions, child versions,
  evidence payloads (sites, bindings, events, joins and retention keys) and the
  admitted IR are invocation-lived, as the plan's lifetime table starts shared
  graphs; nodes, child versions and IR enter the analyzer's live containers,
  where a later `free` is rejected. Everything else retires by an ordinary
  local proof: snapshots with their copies, key lists and saved evidence,
  join owners, builders, the evidence merge's common maps, and the effect
  analyzer with its summaries and scratch rows. Before a version is freed its
  saved evidence is released through the analyzer
  (`FunctionOwnership.releaseOwnership`), so a closed store ends at zero units.
- **Representation:** Java passes `mergeOwnership` a list of snapshots, but a
  native list lent to an analyzed method exposes its contents and leaves
  every listed version unfreeable (the retained M1 limit). A join's
  predecessors therefore live in an `OwnershipPaths` owner: a private D163
  creation array of fresh snapshots, recorded once by
  `FunctionOwnership.captureOwnership`, lent through direct indexed getters,
  visited in path order (a capture may be inserted at any position) and
  retired by the destructor loop; the store's merge reads them through the
  `RejectedFreeEvidence.Paths` interface. The snapshot constructor takes the
  borrowed fields and services, not the analyzer, and builds from
  `SnapshotBuilders` made in the caller's frame, since builders freed in the
  frame that copies them stay lent. The analyzer has no getter for its
  borrowed services: such a getter, like a helper frame that loops over a
  holder, leaves every service the holder retains conservatively escaping
  (the retained D253 summary limit). The effect analyzer keeps three
  summary rows per function in one analyzer-owned `BitRows` matrix and
  updates them in place each round instead of allocating vectors.
- **Boundary:** Nonempty join paths and alternatives, non-`LOCAL_NEW`
  allocation origins, and every IR role outside the effect fixture remain
  later-only (OPERATION_MODEL). Retiring nodes and versions individually would
  need precise loans through the live containers, which no consumer has shown
  to be worth the analysis; the measured payload is bounded per operation.
  No analysis, runtime or lowering change is introduced. This establishes
  M2.2, not S1/G1.
- **Verification:** Native results are byte-identical to the retained J0
  kernel results for all 47 configurations (24 ownership, 3 evidence, 10
  effect chains and 10 effect cycles) from classes and archive. After
  retirement exactly the derived payload and result text stay live: per
  ownership iteration 7 state versions, 2 child versions of 4 allocations
  and, with recorded explanations, 5 events; per evidence iteration one join
  and one event; zero temporaries otherwise. The input boundary rejects seven
  effect-input deviations and a freed allocation. Callback counts are
  recorded separately, paired controls hold in every mode, every allocation
  failure unwinds cleanly, and all runs fit the fixed budgets within a 32-KiB
  stack. See [the ownership record](self-hosting/m2/OWNERSHIP.md).

## D263 - Sort array lists stably with an explicit comparator

- **Status:** Implemented during M3.1 on 2026-10-05.
- **Decision:** Add `ironwood.ds.ArrayList.sortWithComparator(Comparator<?
  super E>)`, B2's list-level sort. It sorts `[0, size())` stably in place,
  never touches inactive slots, keeps size, capacity, growth and the reusable
  iterator's index, and frees no element. A null comparator throws
  `NullPointerException` even for an empty list. The distinct name keeps
  D122's Comparable-bounded array sorts and their null-comparator natural
  order unchanged and claims no Java `List.sort(null)` behavior; `sort` and
  `toArray` stay absent (D129). The original implementation is a top-down
  merge sort with insertion-sorted runs of at most sixteen and a skipped merge
  when the halves are already ordered: O(n log n) comparisons, n - 1 for
  sorted input, and one `size() / 2` workspace array for longer lists, freed
  on every exit. Every access re-reads the backing field, because a
  comparator that grows the list frees the old array; each helper restores a
  permutation in `finally`, so a throwing comparator leaves the same elements
  in an unspecified order.
- **Proof:** No analysis change. The method is not an audited container
  operation, so a call conservatively exposes the stored elements, as an
  unaudited read does: they cannot be freed afterwards, and a retaining or
  publishing comparator keeps its ordinary effects. The list, the comparator
  and the workspace retire by ordinary proofs. Copying the left run uses an
  element loop, because an `arraycopy` into a parameter array escapes it.
- **Boundary:** An audited sort proof would only help lists whose elements
  have a single lifetime root, since a multi-root `get` already exposes them;
  no consumer needs it. The port keeps sorted elements invocation-lived.
  Programs that do not call the method keep the same functions and bodies;
  only the closed-world type IDs, dispatch slots and string-constant names
  are renumbered. No runtime bookkeeping is added. Supersedes no decision.
- **Verification:** A 720-line transcript of seeded lists with many equal
  keys under three comparators equals Java 21 `ArrayList.sort` in four JVMs,
  from classes and archive at `-O3`; native checks of null comparators,
  inactive slots, capacity, the iterator, every comparator failure point,
  workspace allocations, comparison bounds, a supertype comparator and a
  list-growing comparator; every allocation failure unwinds; all-mode
  ownership pairs; O3 code shows the devirtualized comparator inlined into
  one compare. See [the sort record](self-hosting/m3/SORT.md).

## D264 - Provide the M3.1 stack, order, value and callback helpers

- **Status:** Implemented during M3.1 on 2026-10-05.
- **Decision:** Add compiler-private helpers under
  `compiler/src/main/ironwood/ironwood/compiler/port/` for the S2 and S3
  consumers that the M0 inventory assigns to M3.1. `ScopeStack<E>` replaces
  the analyzers' LIFO `ArrayDeque`s (WORKLIST_CONTRACTS Q21-Q41): head
  push/pop/peek, depth-from-head reads for innermost-first traversal,
  head-to-tail `snapshot()` like `List.copyOf(deque)` and `restore()` like
  `restoreDeque`, `NoSuchElementException` on an empty pop and null from an
  empty peek. The FIFO queues keep D255's `WorkQueue`. `StringOrder` is
  natural UTF-16 String order for the `TreeMap`/`TreeSet` and `sorted()`
  rewrites, which sort a hash container's keys at each observation;
  `Extremes` keeps Java's first-tie rule for `Stream.min` and `max`.
  `SnapshotSet` is the value-set member of the D254 family for `Set.copyOf`
  lookups. `Lists.of` with two to four items, `Lists.equal`/`hash` and
  `Maps.equal`/`hash` give Java list and map value semantics over snapshots
  and their D257 key lists. `BooleanSource`, `Mapper`, `Condition`,
  `Action`, `Source`, `PairAction` and `IdentityMapper` replace the surviving
  `java.util.function` uses (CALLBACK_CONTRACTS); every port generic spells
  `extends Object`.
- **Proof:** No analysis change. `ScopeStack` reuses the owned-array growth
  shape of `WorkQueue`; its restore pushes each saved item, because storing a
  snapshot read straight into the owned array makes the array's ownership
  uncertain. Stacked items escape as queued items do. The set snapshot reuses
  D251's copy and D254's lookup proof shape. Fixed-arity lists expose their
  items conservatively, as `Lists.single` does (D258). Holders free before
  the callbacks they retain; captured state stays invocation-lived, as M2
  found, because freeing a callback never frees what it captured.
- **Boundary:** No public API, runtime bookkeeping or lowering change.
  Primitive `Arrays.sort` stays an insertion sort; the Integer natural-order
  sites sort small inputs, and a faster primitive sort waits for a measured
  need (B2). Consumer rewrites are S2/S3 work. Supersedes no decision.
- **Verification:** Native transcripts equal Java 21 for 3,000 seeded
  `ArrayDeque` stack operations, for String order over surrogates and U+E000,
  first-tie extremes, list and map equality and hashes and set membership,
  and for every callback shape, from classes and archive at `-O3` with no
  diagnostics; allocation checks (a stack costs two allocations, a callback
  one, a call none); every allocation-failure limit unwinds; all-mode
  ownership pairs; an audit that every port generic has a reference bound and
  primitive arguments are rejected. See [the helper record](self-hosting/m3/HELPERS.md).

## D265 - Share a generated, checked IR variant inventory

- **Status:** Implemented during M3.1 on 2026-10-05.
- **Decision:** `compiler/src/main/ironwood/ironwood/compiler/port/IrModel.iron`
  is the shared variant inventory for every native IR consumer. It lists all
  113 Java IR records and all 20 IR enums, with each record's sealed root, its
  components in declaration order, the components that the reflective walkers
  (`ClosedWorldPruner.scanRecord`, `SemanticAnalyzer.collectArrayTypes`)
  follow, and each enum's constants. Every function is an exhaustive switch
  without `default` (D260), so a record or enum without a treatment, or a
  deleted treatment, fails compilation. The file is generated by the
  test-side `IrModelInventory` from the Java model declarations by
  reflection, independently of every dispatch arm, and checked in; native
  builds only compile it, so no self-build depends on Java. A test fails
  while the file and the Java model disagree, and checks that the pilot root
  lists in `OperationVariants` have the same sizes as the shared lists.
- **Walk contract:** The S2 walkers must follow exactly
  `IrModel.walked(record)`: IR types, operands, string constants, dispatch
  slots, enum constants, fields, static fields and nested IR records, directly
  or through `Optional` and `List`/`Set`. Maps, text, primitives, enums and
  source spans are not followed, which is the reflective walkers' behavior
  today (for example `IrCallInstruction.specializationArguments`). A changed
  rule needs a recorded decision and a regenerated inventory.
- **Boundary:** No analysis, runtime or lowering change; the pilot admission
  functions keep their D260 lists. The explicit walkers themselves need the
  complete IR model and are S2 work: S2 first replaces the Java baseline's
  reflection, then ports the same traversal against this inventory.
  Supersedes no decision.
- **Verification:** The generated source equals the checked-in file; removing
  a root, walked or constants arm, adding an untreated record or adding an
  untreated enum fails compilation in every unfreed mode; a native program
  prints the whole inventory identically to the Java-derived transcript from
  classes and archive at `-O3`. See [the variant record](self-hosting/m3/VARIANTS.md).

## D266 - Bring split bounds, prefix copies and IR names forward to M3.1

- **Status:** Implemented during M3.1 on 2026-10-05.
- **Decision:** The S2 `TypeName` rendering that M2 left later-only is M3.1's
  first consumer of three helpers, so they move ahead of their M3.2/M3.3
  phases. `Splits.bounds(text, delimiter, limit)` implements Java 21
  `String.split` for a regex matching one literal character, with Java's
  no-match, leading-empty, positive-limit and trailing-empty rules, and
  returns one fresh `int[]` of field bounds instead of a String per field;
  `Splits.field` takes only the substrings a caller keeps. It also serves
  `TypeResolver` (S3) and `CommandLine.parsePathList` (S4). `Lists.prefix`
  copies the first items of a `SnapshotList` or a `SnapshotInts`, for the
  `subList(0, count)` record components of `qualifierReference`.
  `IrModel.javaName(record)` (generated, D265) gives the Java simple name for
  the Bridge diagnostics that print `getClass().getSimpleName()`.
- **Proof:** No analysis change. Bounds and prefix copies are fresh results
  of builders freed on every exit; a trimmed split frees its first array.
- **Boundary:** No regex engine: the multi-character and Unicode
  line-separator patterns (`\R`, `\s+`) stay with their M5 documentation
  consumers. Supersedes no decision.
- **Verification:** Every string over four characters up to length five,
  split on two delimiters with limits -1 to 3 (13,650 cases), equals Java 21;
  prefix copies, their hashes and the out-of-range rejection equal
  `List.copyOf(subList)`; the generated names equal the Java simple names;
  the allocation-failure sweep covers both; classes and archive at `-O3`. See
  [the helper record](self-hosting/m3/HELPERS.md).

## D267 - Provide the M3.2 numeric, text and SHA-256 helpers

- **Status:** Implemented during M3.2 on 2026-10-05.
- **Decision:** Add compiler-private S3 prerequisites. `IntegerLiterals`
  replaces IntegerLiteralDecoder's `BigInteger` with a scan that removes
  underscores, reads digits with `Character.digit` (so non-ASCII decimal
  digits count, as `BigInteger` reads them) and keeps an unsigned 64-bit
  magnitude with a saturating overflow flag, so arbitrarily long spellings
  are checked without big integers; it reports Java's diagnostics in Java's
  order (malformed before range). `IntegralConstants` folds in I32 or I64
  with Java's wrapping arithmetic, which equals J0's exact arithmetic
  followed by `wrapIntegral` because every operand already lies in its
  promoted range; `MIN / -1` and `MIN % -1` wrap without native signed
  division, and division by zero yields no constant. `Texts` supplies
  StringPool's UTF-8 length (an unpaired surrogate counts as U+FFFD),
  `toCodePoint`, a literal `replaceFirst` and `join` over lists. `Sha256` is
  an independent FIPS 180-4 implementation, its constants recomputed from
  their definition, with a reusable state, no per-block allocation, a
  caller-supplied or fresh result, reset on finalization, a streaming UTF-8
  update that matches `getBytes(UTF_8)` (an unpaired surrogate becomes `?`)
  and a lowercase `hexDigest` for every `HexFormat.formatHex` site. The
  existing `Double`/`Float` text and parsing already equal Java 21.
- **Narrowing:** The literal scan treats `+` and `-` as malformed. Every
  caller passes an INTEGER token's lexeme, and `Lexer.scanNumber` consumes
  neither sign in an integral literal, so `BigInteger`'s sign grammar is
  unreachable.
- **Proof:** No analysis change. Results are fresh; inputs are borrowed for
  one call. The digest owns its arrays and frees them in its destructor.
- **Boundary:** No public API, big-integer library, regex engine or locale
  subsystem. The floating-to-integral casts and constant representation stay
  with the S2/S3 consumers. J0 (6bde84df) cannot compile the accumulated
  port: it lacks the M1 library additions and proofs (D248-D254), so S3's
  capacity tracking needs a newer, recorded seed. Supersedes no decision.
- **Verification:** 42,058 literal and fold lines equal J0's own
  IntegerLiteralDecoder and FunctionAnalyzer folds called by reflection;
  SHA-256 equals `MessageDigest` on the standard vectors, every length 0-300,
  every byte, surrogate text and 64 MiB streamed, with split, reset, range and
  allocation checks; UTF-8 lengths equal J0's StringPool over every unit and
  boundary pair; floating text equals Java on 399,136 conversions; J0 trusts
  the ByteView declaration from source, class and archive, distrusts four
  changed copies (comment, rename, CRLF, final newline), and the native digest
  gives the same digests and verdicts; every allocation failure unwinds. See
  [the semantic helper record](self-hosting/m3/SEMANTIC.md).

## D268 - Provide the M3.3 backend helpers

- **Status:** Implemented during M3.3 on 2026-10-05.
- **Decision:** Add compiler-private S4 prerequisites. `Md5` is an
  independent RFC 1321 implementation whose sine table is computed from its
  definition; `linkageGuid` returns the little-endian first eight digest
  bytes of a linkage name's UTF-8 encoding, as OptimizedTraceMetadata and
  LlvmEmitter's TracePlan compute pseudo-probe GUIDs, with no allocation.
  `Bytes` supplies unsigned widening, Java's unsigned byte-array order, a
  bounded range copy, a US-ASCII name comparison without decoding, and
  little-endian short, int and long reads. `LlvmText` spells
  `0x%016X` raw bits, `%.17e` (Java's Formatter pads the `Double.toString`
  digits to eighteen), LlvmEmitter's byte escapes and
  OptimizedTraceMetadata's symbol decoding (each UTF-16 unit encoded alone,
  so every surrogate becomes `?`, then UTF-8 decoding with U+FFFD).
  `LlvmScan` replaces NativeTarget's and OptimizedTraceMetadata's three
  regexes with scanners that keep java.util.regex's multiline anchors and
  line terminators; the quoted-symbol alternative's greedy backtracking
  depends only on its position, so it is evaluated right to left over the
  reachable span. `PropertiesText` parses the admitted `key=value` format of
  the pinned and generated inventories with `Properties.load`'s whitespace,
  separator, comment, terminator and duplicate rules, and owns a copy of its
  text. `HeaderScan.defines` is the sysroot check's two `String.matches`
  patterns. `Double.doubleToRawLongBits` is added to the standard library
  with Java's contract: the binary64 layout, NaN sign and payload included;
  it inlines to a bit move.
- **Narrowing:** `Bytes.slice` never zero-pads, since all three callers
  validate their extents; `PropertiesText` rejects any backslash (an escape
  or continuation), which no admitted file contains; `LlvmText.scientific`
  rejects NaN and infinities, which the emitter spells as bits; the scanners
  and `decodeSymbol` return fresh Strings where Java may return its input,
  and `decodeSymbol` of the bare prefix `@"`, which no FUNCTION match yields,
  returns an empty name where Java throws.
- **Proof:** No analysis change. Inputs are borrowed for one call; results
  are fresh; a digest owns its arrays and a parse its copy and bounds. Two
  conservative limits shape the API: a wrapping `ByteBuffer` keeps its array
  from being freed, so SharedTraceOrder reads the object bytes directly, and
  a parse that borrowed its text would keep the text from being freed.
- **Boundary:** No regex engine, format or locale subsystem, Properties
  class or ByteBuffer change. `stringPropertyNames()` iterates in hash
  order; a consumer whose report depends on the first failing entry must
  establish that order itself. Supersedes no decision.
- **Verification:** MD5 equals `MessageDigest` on the RFC suite, every length
  0-300, every byte, surrogate text and 64 MiB streamed, and 2,018 GUIDs equal
  both Java implementations, called by reflection; the binary helpers,
  little-endian reads and the trace-root sort of 600 groups equal Java 21;
  100,030 floating constants, 2,257 escapes and 3,014 decoded symbols equal
  the Java emitter's own methods; the scanners equal the Java patterns on
  663 corpus files (Clang and opt output, five line-terminator variants and
  adversarial and seeded texts); the Properties subset equals
  `Properties.load` on 445 files; the header check equals `String.matches`
  on 4,013 texts; every allocation failure unwinds. See
  [the backend helper record](self-hosting/m3/BACKEND.md).

## D269 - Give the source-only route explicit installation and identity inputs

- **Status:** Implemented during M3.3 on 2026-10-05.
- **Decision:** The native compiler takes its installation from the launcher
  and its version from generated source, and links without the
  runtime-object cache.
  - **Installation.** The launcher passes the compiler's canonical location
    (its executable or directory), which stands where Java reads its jar or
    class directory through the code source. `Installation.runtimeSource`
    and `librarySourceRoots` repeat RuntimeLibrary.discover and
    StandardLibrary.discover's source roots: the IRONWOOD_RUNTIME_HOME and
    IRONWOOD_STDLIB_HOME overrides, the location's root and then the current
    directory with their ancestors, and Java's messages. `LibraryRoots` owns
    the roots in a D163 creation array. Archive and class roots stay S6
    facilities, so the source-only route never prefers an installed archive.
  - **Build identity.** `scripts/self-hosting/build-identity.sh` generates
    `BuildIdentity.version()` from IRONWOOD_VERSION or VERSION with
    scripts/build.sh's own whitespace removal, validation and messages, so it
    equals `CompilerVersion.current()` of a jar built from the same input. The
    output is byte-identical across runs and needs no Java tool. Java's
    `unknown` fallback has no native counterpart: without a valid version the
    build fails, as build.sh does.
  - **Runtime objects.** The native port compiles each runtime object
    directly and omits `RUNTIME_OBJECTS` with its key, including the
    SHA-256 of every runtime header. A link prepares each object once under a
    distinct key (four, five with TLS, since the key holds the source path),
    so a process that links once never reuses one. The Java bootstrap keeps
    its cache. ByteView's, the TLS SDK's and Bridge's SHA-256 consumers are
    unchanged; the TLS file hash streams `Files.newInputStream` into
    `Sha256`.
  - **Shell driver.** In the source-only route the shell driver owns a
    link's temporary LLVM file and output alias check and supplies SDKROOT in
    place of the xcrun probe. Linux Bridge support delivery runs only after a
    shared link, which only Bridge production (S7) makes. Their native forms
    remain M4.1-M4.3 and S7 work.
- **Proof:** No analysis change. Every intermediate Path is freed on every
  exit, including allocation failure; recorded roots are fresh copies that
  no call has seen, as the creation-array proof requires.
- **Boundary:** Implements B5's proposed cache omission and B7's
  installation and build identity rows for the native port only. The
  launcher must canonicalize the location, as Java's class path entry is.
  Supersedes no decision.
- **Verification:** Ten scenarios (checkout classes and jar, installed and
  bare layouts, two roots, valid, invalid, blank and relative overrides, an
  unknown location) print the Java baseline's discovery results (those
  sources are unchanged since J0) from classes and archive links; generation is reproducible, honors IRONWOOD_VERSION and
  fails with build.sh's messages, and the generated version equals
  `CompilerVersion.current()`; a fresh process's first link inserts four
  distinct runtime objects and reuses none, a second link reuses all four,
  and direct compilation equals the cached bytes twice. See
  [the backend helper record](self-hosting/m3/BACKEND.md).

## D270 - Provide exclusive temporary paths, real paths and access checks

- **Status:** Implemented during M4.1 on 2026-10-06.
- **Decision:** `ironwood.nio.file` gains the B3 scratch, cleanup, real-path
  and access surface, and the compiler port replaces reverse-sorted
  `Files.walk` cleanup with a post-order visitor.
  - **Temporary files and directories.** `Files.createTempFile(Path, String,
    String)`, `createTempFile(String, String)`, `createTempDirectory(Path,
    String)` and `createTempDirectory(String)` create the entry exclusively
    (`O_CREAT | O_EXCL | O_CLOEXEC` or `mkdir`) with mode 0600 or 0700 before
    the umask, retrying a bounded number of existing names. The name is the
    prefix, an unsigned decimal 64-bit value and the suffix, Java's spelling.
    The value comes only from a secure source: `arc4random_buf` on macOS, the
    raw `getrandom` syscall on Linux (guarded numbers, no glibc 2.25 wrapper)
    or `/dev/urandom` when the kernel lacks it; without one the call fails.
    Prefix null means empty and a file suffix null means `.tmp`; NUL throws
    InvalidPathException and a name with a parent IllegalArgumentException,
    in Java's order. The default-directory overloads use `java.io.tmpdir`
    (nonempty `TMPDIR`, else `/tmp`) and never fall back further.
    Attribute-varargs overloads are omitted.
  - **Real paths.** `Path.toRealPath()` resolves the path's own spelling
    with `realpath(3)`, the empty path as the current directory; a non-UTF-8
    result fails. The LinkOption overload is omitted.
  - **Access, deletion and attributes.** `Files.isReadable` and
    `isExecutable` are advisory `access(2)` checks; `deleteIfExists` returns
    false only for absence; `readAttributesNoFollow` is an Ironwood helper for
    Java's no-follow `readAttributes` call.
  - **Ownership.** The returned UnixPath is allocated before the native
    creation and adopts the fresh native String in its constructor, so no
    managed allocation follows creation; a failed String allocation removes
    the entry before propagating. Every new facade borrows its parameters
    (audited `isBorrowingFilesFacade` entries), and temporary paths, real
    paths and no-follow attributes are fresh results.
  - **Traversal rewrite.** The port's `TreeDeletion` deletes entries on visit
    and directories in `postVisitDirectory`, root last, links unfollowed,
    with NativeBackend's best-effort policy or Bridge staging's propagating
    one. Unlike the Java stream, the quiet policy also skips an unreadable
    subtree instead of letting UncheckedIOException escape.
- **Proof:** New typed `IrFileInstruction` operations (CREATE_TEMP_FILE,
  CREATE_TEMP_DIRECTORY, REAL_PATH, ACCESS) reuse the existing file
  machinery; no analysis rule or hot lowering changed. Programs that do not
  call the new members keep identical function bodies once closed-world and
  debug numbering is normalized.
- **Boundary:** Java's per-user Darwin `java.io.tmpdir` and Linux `/tmp` are
  not reproduced; a creation failure names the directory, not the generated
  path. Ironwood's SimpleFileVisitor lacks Java's `throws IOException` on
  `visitFile` and `preVisitDirectory` (a compile-time gap). A caught
  exception cannot be freed, so each failure ignored by the quiet deletion
  keeps its exception. Supersedes no decision.
- **Verification:** A 74-case fixture prints Java 21's lines from class and
  archive links (links, dangling links and loops, locked and read-only
  directories, Unicode, NUL and separator names), the created entries' modes
  match Java's, and six `TMPDIR` settings select Java's directory for the same
  `java.io.tmpdir`. Every allocation limit unwinds to the baseline without a
  leftover entry; the native harness covers collisions, exhaustion, a failed
  close, a failed String after creation, long stems and invalid UTF-8; the
  tree deletion matches NativeBackend.deleteTree and Bridge staging cleanup on
  eight trees. See [the M4.1 record](self-hosting/m4/SCRATCH.md).

## D271 - Publish with three distinct move guarantees

- **Status:** Implemented during M4.2 on 2026-10-06.
- **Decision:** `ironwood.nio.file.Files` gains three distinctly named
  publication moves, one per B3 policy, and `AtomicMoveNotSupportedException`.
  None falls back to a weaker guarantee.
  - **Atomic replacement.** `moveAtomicReplacing(source, target)` is one
    `rename(2)`: it replaces an existing target atomically, renames a link
    itself, leaves names that already denote one file alone, and fails across
    file systems with AtomicMoveNotSupportedException without copying
    (BridgeJarArchive.publish's policy).
  - **Permitted fallback.** `moveReplacing(source, target)` renames the same
    way; only a regular file across file systems is copied, with mode and
    times, into an exclusive temporary beside the target, which replaces the
    target atomically before the source is unlinked (IronJar.write's
    policy). A failure before that replacement keeps the earlier target and
    the source. A source that cannot be unlinked afterwards is reported as a
    FileSystemException with the reason `Target replaced; source not
    removed`, and the target is not rolled back. Directories and links have
    no fallback.
  - **No-replace.** `moveAtomicNoReplace(source, target)` decides and renames
    in one host operation: `renamex_np(RENAME_EXCL)` on macOS (10.12+, within
    the 11.0 baseline) and the raw `renameat2` syscall with
    `RENAME_NOREPLACE` on Linux (kernel 3.15+ and filesystem support; the
    glibc 2.28 wrapper is excluded by the 2.17 baseline; syscall numbers are
    guarded per architecture). An existing target, including the same file
    on every host, fails with FileAlreadyExistsException; ENOTSUP, ENOSYS,
    and an EINVAL that is not a directory moved into itself fail with
    AtomicMoveNotSupportedException, as does a cross-device move. There is
    never a check-then-rename fallback (Bridge distribution and support
    staging's policy). The link-and-unlink alternative is not used: it cannot
    publish directories.
  - **Existing move.** `Files.move` keeps Java's default behavior, a check
    followed by a rename, and is documented as not atomic under a competing
    creator; no race fix changes it.
  - **Ownership.** Each move borrows both paths (audited
    `isBorrowingFilesFacade` entries) and returns the caller's target as an
    alias, exactly as `Files.move` does.
- **Proof:** Three new typed `IrFileInstruction` operations (MOVE_ATOMIC,
  MOVE_REPLACING, MOVE_EXCLUSIVE) reuse the existing file machinery; no
  analysis rule or hot lowering changed. The runtime's new error categories
  (cross-device, unsupported, source retained) are set only by these moves.
- **Boundary:** Atomic visibility is not crash durability; no fsync policy is
  added. Java's `REPLACE_EXISTING` move also copies links and empty
  directories across file systems, and Java's default move copies and
  treats the same file as a no-op; these helpers do not. Supersedes no
  decision.
- **Verification:** Sixteen cases per move print Java 21's lines and leave
  Java's trees from class and archive links, differing only where an
  exclusive target is the same file; a second real file system (an HFS+
  image on macOS, tmpfs on Linux) shows the cross-device behavior and the
  copy's mode and times; eight competing processes over 40 rounds of files
  and directories admit exactly one winner whose content survives; a native
  harness demonstrates Files.move's check-then-rename race and covers
  competing creators, unsupported and cross-device results and every failure
  of the copy; every allocation failure of a staged publication unwinds
  without leftovers. See [the M4.2 record](self-hosting/m4/PUBLICATION.md).

## D272 - Run external programs synchronously by absolute path

- **Status:** Implemented during M4.3 on 2026-10-06.
- **Decision:** `ironwood.process` provides B4's narrower synchronous
  facility, `ProcessRunner.runToFile(String[] command, Path directory, Path
  output)` returning a primitive-only `ProcessResult`, ahead of roadmap item
  4's reduced ProcessBuilder/Process, which it neither implements nor
  cancels.
  - **Contract.** `command[0]` is an absolute executable executed directly
    (no PATH search, no shell); the environment is inherited; a null
    directory keeps the caller's; standard input is empty; standard output
    and error are merged into `output`, opened by the caller so a relative
    spelling is the caller's. Empty commands, relative executables and NUL
    throw IllegalArgumentException before launch. A completed program, any
    exit status including 127 or a signal, is a result with Java's POSIX
    `exitValue` (128 plus the signal) and explicit `signaled`/`signal`; a
    launch failure (executable, directory, output, ENOEXEC without shell
    fallback, resources) is an exception naming the failing path.
  - **Native design.** One `fork`/`execv` implementation serves macOS and
    glibc 2.17: `posix_spawn` lacks a spawn-time `chdir` on that glibc, and
    one code path is qualified on both hosts. The command, directory and
    output spellings are encoded into one block before forking; the child
    only redirects descriptors, changes directory and executes, reporting a
    pre-exec failure through a close-on-exec pipe; the parent retries EINTR
    and always reaps. Runtime descriptors are close-on-exec and kept clear of
    0-2.
  - **Signals.** The program stays in the caller's process group, so a
    terminal interrupt reaches both; no global signal machinery is added. A
    signal sent to the caller alone, or an uncatchable termination, does not
    stop a running program.
  - **Caller adaptations.** Executable discovery stays in compiler callers.
    The Java seed's TlsDependency now launches `/usr/bin/xcrun` (shared
    with MacNativeTools as `MacNativeTools.XCRUN`) when `SDKROOT` is unset
    or blank, so a PATH-selected `xcrun` is no longer used, and
    LlvmToolchain resolves `brew` with the compiler-owned
    `ExecutableSearch` before launching it by absolute path. The search
    tries PATH entries in order, skips empty entries (never the working
    directory), resolves relative entries against the working directory
    without lexical normalization and takes the first executable regular
    file; without one, Homebrew discovery finds nothing, as before.
  - **Compiler.** A typed `IrProcessInstruction` (I64 status; String[],
    String and String operands) is bound only to the exact
    `ProcessRunner.runProcessValue` intrinsic and joins closed-world effects,
    allocation-failure reachability, borrow dispatch, specialization, CFG
    renaming, invoke and LLVM emission. `runToFile` has an audited borrowing
    contract for its parameters and, by exact signature, for the command's
    elements (String is final, and the launch encodes and retains nothing);
    other `String[]` parameters keep exposing their elements.
- **Proof:** The element contract is the only analysis change; paired
  regressions free a Path spelling and a fresh String placed in a command
  after the call, and reject freeing an element while the array is live,
  freeing an element of a published array, double free and use after free of
  the result, and an ordinary method taking `String[]` (the negative
  control).
- **Boundary:** Descriptors the caller inherited without close-on-exec reach
  the program (Java's launcher closes them). Environment maps, pipes,
  asynchronous waits, timeouts and kill APIs are deferred. Arguments use the
  runtime's host encoding (U+FFFD for an unpaired surrogate). Supersedes no
  decision.
- **Verification:** Twenty-eight cases against a controlled helper from
  class and archive links (exit codes, signals, literal argv, environment,
  child and inherited directories, parent-relative output, empty stdin,
  2 MiB of output, descriptors, missing, non-executable, directory and
  ENOEXEC executables, scripts, directory and output failures, invalid
  commands, 300 repeated launches) with a PATH of empty entries and decoys
  that never run; a native harness injecting open, pipe, fork and encoding
  failures, interrupting waits with a real signal and checking reaping and
  descriptors over 200 launches; SIGINT to the job's process group ends the
  program and its tool, SIGTERM to the program alone leaves the tool; every
  allocation failure unwinds. ExecutableSearch's candidate rules hold on
  real trees, a child JVM with empty, non-executable and decoy PATH entries
  launches the resolved absolute `brew` (and finds no prefix without one),
  and TLS and Apple tool discovery run only `/usr/bin/xcrun` under a PATH
  offering another. See [the M4.3 record](self-hosting/m4/PROCESS.md).

## D273 - Drive native tools through invocation-scoped port adapters

- **Status:** Implemented during M4.3 on 2026-10-06.
- **Decision:** The compiler port gains the native driver's process adapters
  that S4 and S7 consumers use in place of the Java baseline's
  `ProcessBuilder` readers; the Java seed is unchanged by this decision.
  - **Command.** One tool invocation's arguments in a private creation array
    of exactly the command's length (D163): each slot receives a fresh copy,
    an indexed getter lends it, the destructor loop retires it, and a run
    lends the storage to `ProcessRunner.runToFile`. The creation-array proof
    admits exactly that call as the storage's one consumer, because D272's
    audited contract borrows the array and its Strings for the call only;
    every other call still may not receive creation-array storage.
  - **Probes.** One invocation's discovery context. An uncached probe writes
    its merged output to a fresh log in one lazily created scratch directory
    (B3 temporary paths), is read back within 1 MiB (more is an explicit
    failure, never a truncated answer), decoded with replacement as Java
    decodes tool output, stripped, and its log deleted on every path. Each
    probe is a `ProbeRecord` whose constructor runs it and owns its key and
    output, so no allocation failure strands either; a successful record is
    reused for the same command, PATH, SDKROOT and DEVELOPER_DIR, a failed
    one never. `close()` removes the empty scratch directory without
    allocating, falling back to TreeDeletion only for leftovers; outputs are
    lent until the context is freed. No static cache, process handle or log
    outlives the invocation.
  - **ExecutableSearch.** The port's PATH search equals the Java seed's
    (D272), for brew and other caller-owned discovery.
  - **LlvmPipeline.** NativeBackend's O3 executable pipeline (target probe,
    `LlvmScan` target application, `llvm-as`, `opt`, finalization, `llvm-as`,
    `llc`, Linux section rename, four runtime objects compiled directly per
    D269, Clang link) with every stage launched by absolute path, logged in
    a scratch directory beside the output and reported as NativeBackend
    reports it. Staged files are deleted as their scopes end and the empty
    directory afterwards, without allocating. Trace finalization stays an
    explicit executable step until S4's native mode exists.
- **Proof:** The creation-array admission of `runToFile` is the only analysis
  change; paired cases accept an owner lending its storage to that call and
  reject the same storage passed to `String.join`, freeing a lent argument,
  using an argument or probe output after its owner is freed, and double
  free.
- **Boundary:** An allocation failure inside the pipeline's own cleanup ends
  the process through the runtime's emergency path and leaves its staging
  directory. The reuse key holds the full PATH, so retained memory grows
  with the environment. The shell driver of D269 remains available; these
  adapters are prerequisites, not S4's port of NativeBackend, LlvmToolchain
  or MacNativeTools. Supersedes no decision.
- **Verification:** The adapters own and lend as specified in every unfreed
  mode; the port search equals the Java seed on the same trees; the probes
  answer as LlvmToolchain and MacNativeTools do, launch nothing when
  repeated, probe again in a new invocation, never keep a failure, fail
  explicitly on oversized output and leave no log or scratch directory; the
  pipeline links the Java compiler's emitted module into an executable whose
  output, status and stack trace equal the Java link's, from class and
  archive links, and names a failing stage with the tool's output without
  leaving output or staging; every allocation failure of the adapters
  unwinds without leftovers. See [the M4.3 record](self-hosting/m4/PROCESS.md).

## D274 - Provide the public CRC32 slice of compression and checksums

- **Status:** Implemented during M5.1 on 2026-10-06.
- **Decision:** `ironwood.util.zip.CRC32` is the first public slice of
  STDLIB_ROADMAP item 5, ahead of its ZIP and GZIP APIs, which it neither
  implements nor cancels. B5's archive services and the compiler port consume
  it; MD5 and SHA-256 stay compiler-private (D267, D268).
  - **Surface.** `CRC32()`, `reset()`, `update(int)`, `update(byte[])`,
    `update(byte[], int, int)` and `getValue()`, with Java 21's behavior for
    every admitted call: a new or reset value is 0; the integer update uses the
    argument's low eight bits, including widened byte, short, char and
    negative values; `getValue()` is the unsigned 32-bit value in a `long` and
    does not disturb further updates. A null array throws
    NullPointerException, and a negative offset or length, or a range past
    the end, throws ArrayIndexOutOfBoundsException with Java's
    `Range [off, off + len) out of bounds for length n` text before any byte
    is consumed.
  - **Dispatch.** The class is not final, as Java's is not. `update(byte[])`
    is declared on the class instead of inherited from `Checksum`, and calls
    `update(b, 0, b.length)` dynamically as Java's default method does, so a
    subclass overriding the range update observes whole-array updates; the
    range update never calls `update(int)`.
  - **Omissions.** The `Checksum` interface and `update(ByteBuffer)` are
    absent, so a Java call using either fails to compile rather than failing
    at run time.
  - **Implementation.** Original code from the CRC-32/ISO-HDLC definition
    (reflected polynomial `0xEDB88320`, initial and final value
    `0xFFFFFFFF`), processing eight bytes per step with eight 256-entry tables
    built at class initialization. The tables are one process-lived
    allocation on first use; updates, reads and resets allocate nothing and
    borrow their array for the call. One length test before the loop lets the
    optimizer drop every table bounds check, and reading each group's last
    byte first leaves one array bounds check per eight bytes at `-O3`. No
    hardware CRC instruction is used.
- **Proof:** No analysis, runtime or lowering change. The ordinary analysis
  accepts freeing an array after an update, also in a program that has a
  retaining subclass, and rejects freeing it after an update through a
  retaining override, reached directly, through whole-array delegation or
  through a CRC32 reference.
- **Boundary:** A null whole-array argument reports a NullPointerException
  without Java's helpful-message text. Supersedes no decision.
- **Verification:** A 675-line transcript equals Java 21's from class and
  archive links: the check value, every length 0-300 and byte value, widened
  and negative integers, 81 offset and length pairs, every two-piece split,
  repeated reads, reset, 64 MiB streamed through one buffer, null and range
  failures with Java's types and text, and the dispatch of three subclasses.
  The ownership pairs hold in every unfreed mode, omitted members fail to
  compile, updates allocate nothing, and every allocation failure unwinds.
  See [the M5.1 record](self-hosting/m5/CRC32.md).

## D275 - Read legacy archives with Ironwood inflate and write STORED entries natively

- **Status:** Accepted during M5.2 on 2026-10-06. The decoder and writer are
  implemented in the compiler port; the reader policies below take effect in
  M5.3's archive services. The Java bootstrap's writers are unchanged.
- **Decision:** B6's reader and writer profiles for every native archive
  consumer, chosen against the frozen [M5.1 contract](self-hosting/m5/ARCHIVES.md).
  - **Reader profile.** Native readers accept STORED and DEFLATED entries in
    all three profiles, including DEFLATED class payloads nested in STORED
    `.ironjar` entries and wholly DEFLATED legacy archives. DEFLATE data is
    decoded by the port's `Inflate`, an original RFC 1951 decoder that admits
    exactly the code sets Java's zlib-based Inflater admits. Each profile keeps
    its Java container model: IronClass reads local headers in order, as
    `ZipInputStream` does, and IronJar reads the central directory, as
    `ZipFile` does, with the contract's verdicts, apart from four explicit
    native policies: every entry read has its CRC-32 and size verified in both
    models (Java's `ZipFile` verifies neither); a malformed UTF-8 entry name is
    an artifact error rather than an unchecked exception escaping the reader;
    an archive or decoded entry beyond the array range fails with a size-policy
    message, the same bound Java's byte-array readers have; and
    container-level messages are native and name the artifact, while
    profile-level messages keep Java's text.
  - **Writer profile.** Native `.ironclass`, `.ironjar` and Bridge `.jar`
    output use STORED entries spelled exactly as Java's ZipOutputStream writes
    a STORED entry with explicit size and CRC and time 0: version 10, the
    UTF-8 name flag, DOS date 1980-01-01 at 00:00, the 9-byte extended
    timestamp with time 0, no descriptor, zero attributes, no comments, and
    ZIP64 end records from 65,535 entries. Each profile's entry order is
    unchanged: IronClass's fixed order, IronJar's sorted order, and Bridge's
    manifest first, then sorted. A STORED writer never narrows a reader.
  - **Options.** (a) Ironwood inflate with STORED writers, chosen: no codec
    dependency and no compressor. (b) A pinned zlib behind a typed runtime
    boundary, with the existing 1.3.1 Bridge support source pin as the
    candidate: not needed once (a) met the contract, so no zlib release, build
    flags or notices are selected. (c) Ironwood inflate and deflate: compressor
    work that no requirement justifies. The existing delivery of zlib source
    with the Linux Bridge support files is unaffected.
  - **Byte and identity effects.** A native `.ironjar` built from the same
    `.ironclass` payloads equals the Java archive byte for byte. Native
    `.ironclass` and Bridge JAR bytes differ from the Java bootstrap's
    DEFLATED output while their decoded entries, names, order and timestamps
    agree; archives that embed native class artifacts therefore differ too, and
    any identity computed over those bytes changes with the writer, which
    M6.2 records for Bridge. Cross-writer byte equality is required only for
    `.ironjar` from equal payloads; every writer stays deterministic. STORED
    output makes the standard library's 252 class artifacts 3.0 times larger
    (431,739 to 1,312,492 bytes) and its archive about 2.0 times (887,851 to
    about 1,768,604 bytes); reading and writing it does no compression work.
  - **Boundary of the package.** `Inflate` and `ZipWriter` are compiler-private
    (`ironwood.compiler.port`); the public surface stays CRC32 (D274), and no
    public ZIP, GZIP, Deflater or Inflater API is added or implied. No runtime,
    native library, build or packaging change is made; programs that use no
    archive code keep none of it.
- **Proof:** No analysis, runtime or lowering change. Two conservative
  rejections were met in source form: a field-element store whose value comes
  from a call on the same object is uncertain to the destructor proof, so the
  value is read first; and an array used as a copy destination cannot be freed
  in that frame, so such copies are returned from a helper.
- **Boundary:** Decoding is one-shot over an in-memory range, not a stream
  that arrives in pieces; the writer builds the archive in memory and so never
  needs ZIP64 sizes or offsets; the writer does not check distinct names,
  which each profile guarantees. This refines D023's and D024's container
  description and supersedes no decision.
- **Verification:** The decoder gives Java 21's Inflater verdict on 194
  streams: 160 from eight inputs at six levels, three strategies and two
  flush modes, four trailing, truncated and empty inputs, and 30 hand-built
  stored, fixed and dynamic blocks covering each defect zlib rejects; 171 are
  accepted with Java's bytes and consumed lengths and 23 rejected. Natively
  each accepted stream also decodes at an offset inside other bytes and into
  an exact-size array, and fails with an off-by-one size or a truncated
  prefix. The writer reproduces the
  frozen `lib.ironjar`, equals Java's STORED bytes for a class artifact,
  Unicode, empty and missing entries and 65,534 to 65,536 entries, and its
  STORED class artifact and JAR open in IronClass.read, ZipFile, JarFile,
  JarInputStream, the `jar` tool, `java -jar` and a class loader. Ownership
  pairs hold in every unfreed mode and every allocation failure unwinds. See
  [the M5.2 record](self-hosting/m5/CODEC.md).

## D276 - Give the compiler port its archive services and library discovery

- **Status:** Implemented during M5.3 on 2026-10-06.
- **Decision:** The compiler port gains the S6 artifact services on D275's
  profiles, so the ports of IronClass, IronJar, SourceSetLoader,
  StandardLibrary, Main's class outputs and IronJarMain can use them; the Java
  seed is unchanged.
  - **Containers.** `ZipStream` reads local headers in order, as
    `ZipInputStream` does for IronClass, and `ZipArchive` reads the central
    directory, as `ZipFile` does for IronJar (end-record search tolerating
    trailing bytes whose directory checks out, ZIP64 end records and extras,
    prefixes, iteration by directory size), each with D275's native policies.
    Archive bytes are passed to each call, never kept by a view.
  - **Profiles.** `IronClassArtifact` reads with IronClass.read's checks,
    order and messages and writes STORED entries directly to the destination,
    from the fields Java derives from the parsed unit (type, kind, entry point,
    source name and content). `IronJarArchive` reads with IronJar.read's
    checks, order and messages, looks payloads up lazily through nested class
    artifacts (DEFLATED or STORED), and creates archives with IronJar.create's
    input handling and messages, then publishes them staged beside the
    destination with `Files.moveReplacing` (D271), deleting the stage on
    every other exit. Inputs and licenses are `TextList` owners: a String
    array parameter exposes its elements to every analyzed call, so a caller
    could never free them.
  - **Helpers.** `TextList` owns many texts in one UTF-16 buffer with String
    and code point (Java Path) orders, binary search and a String-hash index;
    `ArchiveEntries` owns names and contents of an archive being built;
    `FileCollector` is B3's walk replacing `Files.walk` inventories, keeping
    spellings of regular files (links followed) with an extension, in Path
    order.
  - **Discovery.** `Installation.libraryArchives` and `libraryClassRoots` add
    StandardLibrary.discover's archive and class roots that D269 left to S6,
    and `LibraryTypes` its owned types: the core types, archive indexes, and
    class and source root inventories, with Java's names.
  - **Walk fix.** `Files.walkFileTree` now releases a directory's visited
    attributes when an allocation failure interrupts building its stream or its
    traversal-loop check; before, each such failure kept them.
- **Recorded differences from the Java baseline:** CRC and size are verified
  for every entry read (Java's `ZipFile` verifies neither); a malformed entry
  name is an artifact error (Java's `ZipInputStream` lets an unchecked
  IllegalArgumentException escape `IronClass.read`); container messages are
  native; when several indexed entries are missing, the first in index order
  is named (Java names the first in HashSet order); a traversal failure in an
  archive input or library root is an IOException, so creation reports it and
  discovery skips the root (Java's stream throws an unchecked exception);
  native class artifacts are STORED (D275). Profile messages are Java's text.
- **Proof:** No analysis, runtime or lowering change. Three conservative
  rejections shaped the code: a field-element store whose value or index comes
  from a call, which leaves the field's ownership uncertain (computed into a
  local first); a copy destination freed in the copying frame (copies come
  from `Bytes.slice` or the decoder's fresh result); and a String array
  parameter, which exposes its elements (owners instead). Readers and
  collectors do their work in constructors, whose rollback releases a partly
  built object on any failure.
- **Boundary:** Archives and decoded entries are held in memory, limited to
  the array range; a native archive build holds each input's bytes, the
  entry contents and the assembled archive at once. The AST-derived class
  fields, the source file wrapper and `StandardLibrary.locate`'s search order
  remain S6's port. A caught exception cannot be freed, so each archive or
  root discovery skips keeps its exception and message. Supersedes no
  decision.
- **Verification:** On 144 corpus variants the native readers give the frozen
  Java verdicts, profile messages exactly, apart from the five recorded policy
  cases; every Java-built standard-library class artifact and archive and the
  frozen fixtures read natively with Java's types, entry points, sources and
  paths; native rewrites of all 252 standard-library class artifacts equal
  Java's STORED spelling and read back in Java; a native archive of Java's
  class directory equals Java's standard-library archive byte for byte, and
  one of the native class directory reads back in Java; repeated, directory
  and reordered-file builds are byte-identical; 14 invalid creations report
  Java's messages and publish nothing; publication replaces, keeps the
  earlier archive on failure, creates parents and leaves no stage; ownership
  pairs hold in every unfreed mode; every allocation failure unwinds with no
  stage left; discovery equals Java's in eight layouts; and the walk sweep
  that leaked before the fix now unwinds. See
  [the M5.3 record](self-hosting/m5/ARTIFACTS.md).

## D277 - Provide the S6 documentation and command-line helpers

- **Status:** Implemented during M5.4 on 2026-10-06.
- **Decision:** The compiler port gains B7's S6 helpers for the ports of
  DocComment, DocModel, MarkdownDoclet, IronDoc, IronDocOptions and
  IronJarMain; the Java seed is unchanged.
  - **DocText.** Purpose-specific scans with Java 21's semantics for exactly
    the expressions they replace: `split("\\R", -1)` (CRLF one break; LF, VT,
    FF, CR, U+0085, U+2028, U+2029), the `split("\\s+", 2)` head and tail
    (unflagged `\s` only), `replaceFirst("^\\s*\\* ?", "")`, the
    `(?i)^<code>` and `(?i)</code>$` strips (ASCII-only case, `$` before one
    final terminator), the entity replacement with `parseInt` and
    `Character.toChars` semantics and its diagnostic, the backtick run,
    `replaceAll("\\s+", "")`, `replaceAll("\\s*\\R\\s*", " ")`, the
    identifier-run matcher, IronDoc's package-name regex, `stripLeading`,
    `regionMatches(true, ...)`, DocModel's member anchors and DocComment's tag
    scan, which keeps the UTF-16 `char` scan with the part predicate at the
    first position and never pairs surrogates.
  - **JavaIdentifiers.** Java 21's `Character.isJavaIdentifierPart` for chars
    and code points, as 798 ranges generated from JDK 21's answer for every
    code point and checked by regeneration; S7's qualified-name validator
    (M6.1) shares it and adds the start predicate and keyword rules.
  - **LinkRenderer.** DocComment.render's link callback, with a primitive
    boolean instead of `BiFunction<String, Boolean, String>`.
  - **FileCollector.** Public, with a bounded depth for IronDoc's depth-one
    package selection.
  - **Conventions.** IronDoc's version resource is the generated
    BuildIdentity (D269); IronDocOptions' `splitAsStream` path list is
    `Splits.bounds(value, ':', 0)` and its `split(":", -1)` lists keep -1;
    MarkdownDoclet's banner `formatted` concatenates its literal pieces;
    `Locale.ROOT` casing is the fixed en_US casing (D117), equal to ROOT;
    `ArrayDeque` lists are ScopeStack (D264); Path-keyed TreeMaps and
    TreeSets sort in TextList code point order (D276); the remaining calls
    follow the M3 conventions.
- **Proof:** No analysis, runtime or lowering change. A concatenation of
  constants is an immortal literal and is not freed; an interface-typed alias
  kept in scope holds its object, so callbacks are invoked through a parameter.
- **Boundary:** The scans implement their call sites' expressions, not a
  regular-expression engine. `regionMatchesIgnoreCase` compares UTF-16 units,
  which equals Java for the ASCII literals its callers pass. Supersedes no
  decision.
- **Provenance:** Original code under the default license. The identifier
  ranges are Unicode 15.0 character data observed through JDK 21's
  `Character` with no OpenJDK source consulted, treated as the project
  treats observed Character data, and carry the Unicode notice in
  `LICENSES/Unicode-15.0.txt` (SOURCE_PROVENANCE and THIRD_PARTY_NOTICES).
- **Verification:** For 3,083 corpus inputs, 438 tag suffixes, every char and
  every code point, the 47,935-line transcript equals the Java tools' own
  methods and expressions from class and archive links; IronDoc's depth-one
  and recursive walks select and order Java's files; the table regenerates
  exactly; ownership pairs hold in every unfreed mode; every allocation
  failure unwinds. See [the M5.4 record](self-hosting/m5/DOC.md).

## D278 - Refine ownership facts after declaration errors

- **Status:** Accepted and implemented; its boundary for missing
  implementations is superseded by D279. Supersedes the readiness rule of D184's
  M1c milestone, which attached a limited-analysis note to every ownership
  rejection after an earlier error, and resolves the limitation recorded in
  [EXPLAIN_REJECTED_FREE.md](EXPLAIN_REJECTED_FREE.md) section 3.3.
- **Context:** Closed-world ownership refinement ran only when no error had been
  reported before it. After any declaration error, final lowering still ran with
  the initial facts, without borrow dispatch, temporary-borrow or reclamation
  effects, and reported their consequences as final rejections and missing-free
  findings, contrary to D096 and D140. One unrelated declaration error gave every
  program false rejections in bundled `Throwable`, gave about a quarter of the
  examples false errors in their own code, reported genuine errors with vaguer
  messages, and could hide them behind a conservative rejection probed first.
- **Decision:** Refinement runs whenever analysis reaches it; only an inheritance
  cycle still stops earlier. Ownership verdicts are reported only from converged
  facts: rejected and deferred frees, destructor, loop and owned-element proofs,
  use after free, pool transfer, publication and effect validation, and
  missing-free findings. When refinement does not converge, final lowering still
  reports other errors but no ownership verdict, and the non-convergence error is
  reported only when no earlier error exists, because earlier errors may cause it.
  A program is built only from converged facts. The limited-analysis note is
  removed; explanations always describe refined evidence.
- **Analysis:** Refinement already tolerated method-body errors. Over declaration
  errors it describes the declarations as written: erroneous declarations are
  omitted, substituted or left without targets, so verdicts for code that does not
  use them equal those of the corrected program. A failing compilation's ownership
  diagnostics are therefore not exhaustive: verdicts that depend on an erroneous
  declaration or on an unconverged analysis appear once the errors are fixed. A
  failing compilation never certifies ownership.
- **Boundary:** A polymorphic call whose candidate class lacks the called
  implementation, from a missing interface or abstract method implementation,
  leaves its target unknown, so frees around that call and in callers whose
  summaries depend on it can still be rejected next to the declaration error;
  suppressing those cascades is a separate change. No safety proof, reclamation,
  runtime or lowering of a valid program changes, and error-free compilations are
  identical. No unfreed mode or explanation setting can produce output after an
  error.
- **Verification:** 72 examples, each compiled with one of 11 kinds of unrelated
  declaration error, report nothing outside the file holding that error; the old
  gate added 74 errors in the examples' own code and 472 in bundled code for every
  kind that reached it. 1,120 source fragments from the compiler's tests compile
  without a crash or non-convergence, the 187 valid ones unchanged, and each
  diagnostic the change adds equals the output of the same program without its
  declaration error, including two leaks the old path missed. 160 programs with
  genuine ownership errors keep identical diagnostics beside two kinds of
  unrelated error. 51 examples linked with a main class lacking `main` report
  only that error, where the gate added 200. Focused tests cover unrelated and
  used declaration errors in `off` and `error` modes, the command-line and link
  paths, non-convergence through an injected pass budget, and the explanation,
  dependency, bundled-source and observer contracts; the standard library still
  builds with `--unfreed=error`.

## D279 - Complete missing implementations with permissive placeholders

- **Status:** Accepted and implemented. Supersedes D278's boundary for missing
  implementations.
- **Context:** A class missing an implementation is omitted from a call's target
  set. When every candidate lacked it, the empty set meant an unknown call that may
  retain its arguments, so frees around the call, and in callers whose summaries
  depend on it, were rejected next to the declaration error with messages that do
  not name it, such as "allocation escapes through argument 2 of method
  'passTwice'". Suppressing rejections by function or by message would also hide
  genuine errors in the same body.
- **Decision:** After a missing interface or abstract method implementation is
  reported, the class gets a compiler placeholder for it; an abstract method
  declared in a concrete class is replaced by one. Ownership analysis treats a
  placeholder as the most permissive implementation a correction could have: it
  retains, allocates and throws nothing, and its reference result counts as a fresh
  allocation the caller may free. It may also reclaim its reference arguments and
  return null, which only suppresses missing-free findings, including through
  callers. Placeholder bodies add no diagnostics.
- **Analysis:** Rejections and effect checks only grow with what a callee does,
  so those that remain hold for every correction. Missing-free findings shrink with
  it, so the possible reclamation and null result keep them from depending on the
  correction. The placeholder applies the
  completion that mixed target sets already had to every analyzer, without new rules
  in ownership analysis: D140's nullable fresh results never justify an abandonment
  finding, and the closed-world may-reclaim effect suppresses such findings without
  permitting or rejecting a free.
- **Boundary:** A class that already declares a static or private method with the
  required signature, or that inherits unrelated default methods, gets no
  placeholder for that requirement; its declaration error remains, and no cascade
  from those cases has been observed. Valid programs never contain placeholders, so their compilation is
  unchanged, and no program with a placeholder is built.
- **Verification:** Of 13 kinds of broken declaration used around a `free`, each
  with a corrected twin that compiles cleanly, all now report only their
  declaration errors; the two missing-implementation kinds previously added
  rejections. Calls that reach a placeholder two methods away are proved while a
  genuine error in the same body remains; a leak is reported only once the
  implementation exists; a retaining sibling implementation still rejects; freed,
  deferred, wrapped and discarded results add nothing, and a published one is
  rejected for its publication; generic, anonymous and primitive-returning
  requirements and destructor checks behave as their corrections do. 72 examples
  with three kinds of unrelated declaration error, including a missing
  implementation, report exactly one diagnostic each, and 1,120 compiler-test
  fragments give the same output as before this decision, the 187 valid ones
  unchanged from before D278.

## D280 - Check missing frees across the whole analyzed program in every entry path

- **Status:** Accepted and implemented. Amends D140's scope, which its implementation
  limited to the sources a caller passed to the pipeline.
- **Context:** The command line loads bundled standard-library and class-path
  sources as inputs, so its missing-free checks covered them. The in-process
  compiler API and the language server pass only their own sources and let the
  pipeline add bundled units, which the check then skipped. The same program could
  therefore report a leak through one path and not through another. Leaving bundled
  code out was reasonable while the standard library had unrepaired omissions
  (D140); it is now built with `--unfreed=error`.
- **Decision:** `--unfreed` governs proven abandonment in the whole analyzed
  program: application sources, class-path and archive dependencies, and the
  bundled standard library, identically in a command-line compile or link, the
  language server, the bridge producer and the in-process API. The per-source
  filter is removed from `SemanticAnalyzer` and `CompilerPipeline`.
- **Analysis:** D140 already re-checks reconstructed dependency bodies at final
  link, and mandatory safety errors already report closed-world consequences in
  bundled code, such as a library destructor that a retaining user override makes
  unprovable. A leak in library code that user code causes is a leak of the final
  program, so strict mode must not succeed while one is proved. Only definite
  abandonment is reported (D140), so uncertain library facts produce no finding.
- **Boundary:** No safety proof, lowering or runtime behavior changes; only which
  existing findings are reported. The language server publishes a finding to the
  file that contains it, as it already does for bundled safety errors.
- **Verification:** A leak planted in a copy of the standard library is reported
  identically through the command line and the in-process pipeline as a warning
  or an error, and by neither with `off`; before this change only the command line
  reported it. Under refined facts, compiling 72 examples, 1,120 compiler-test
  fragments and 160 stability programs through the command line, which already
  checked bundled code, produced no bundled missing-free finding, so in-process
  and language-server results for those programs are unchanged.

## D281 - A field loan cannot cross any code that may run while it is aliased

- **Status:** Accepted and implemented. Amends D041's reentrancy rule, which its
  implementation applied only to source calls and constructions.
- **Context:** D041 lets a method free a private field's superseded storage after
  detaching it, provided an attached loan cannot cross a potentially reentrant call.
  The owned-field proof checked that syntactically, so code that runs without a
  source call went unseen; a reentrant free of the field then left a live local
  alias dangling. Thirteen shapes compiled and, at run time, freed an array twice or
  wrote into a freed one: string conversion of an object (`"x" + hook`,
  `text += hook`), the first use of another class's non-constant static field, enum
  constant or interface field (class initialization), enhanced-for iteration over an
  `Iterable`, an element store whose value runs such code, a loop condition that runs
  after the body made an alias, a catch handler entered while the try body's alias
  is live, a deferred call that runs at scope exit while an alias is live, a static
  initializer that holds an alias across a call, and a destructor that publishes the
  field before freeing it. The per-loan check in function lowering that seemed to
  cover calls never applied: uncertainty is not recorded for owned-field loans.
- **Decision:** While a local alias of an attached field allocation is live, no code
  other than fixed runtime operations may run. The proof takes the operations that
  may run code from the provisional typed IR, where implicit ones are explicit:
  calls of every kind, class initialization of a type with an initializer,
  destructors of a freed value or element, construction rollback and foreign calls.
  An audited list names the operations that run only fixed runtime code; a new kind
  of operation counts as running code until it is classified. Three operations stay
  fixed: constructing the six exception types that lowering emits for failed runtime
  checks, whose constructors reach only `Throwable()` or `Throwable(String)`; class
  initialization of the running code's own class or superclasses, which has already
  started; and exact `System.arraycopy`, as before. Loops are scanned until the
  aliases live at the start of an iteration stop growing; catch handlers start with
  any alias the try body may have made; while a deferred action is pending, no
  local declared outside its scope may hold a live alias, since that local outlives
  the action; and the static initializers and destructors of the owning nest are
  scanned like its methods. Freeing anything but the alias may run a
  destructor unless typed IR shows that the value has none. Function lowering's
  no-op check is removed; it only forgets loans that no local holds.
- **Analysis:** The rule keeps its kind: D041 already rejected every source call
  while an alias is attached. Each new rejection is the field's ownership-proof
  failure, which `--explain-rejected-free` shows for the free that needed it. Safe
  forms stay accepted, among them the same code before the load or after the
  detaching store, element reads, casts, division, String and primitive
  concatenation and the owner's own statics inside a loan. Implicit code that cannot
  in fact reach the field is rejected like an explicit call that cannot. No runtime
  instruction, check or cost changes.
- **Verification:** Each of the 13 shapes now fails the field's proof for its own
  reason and its safe twin compiles; the previous compiler accepted all 13, and
  probes of them crashed under Guard Malloc while a safe control ran cleanly. The
  standard library builds with `--unfreed=error`, its owned-field facts (161 owned
  fields and every borrow and rejection entry) are identical before and after, and
  74 examples compile identically.

## D282 - Report construction publication at the class whose construction publishes

- **Status:** Accepted and implemented. Refines where the existing in-progress
  publication error is reported; the rule itself is unchanged.
- **Context:** Both publication analyses summarize each constructor once, for every
  class whose construction may run it, so a call on the object reaches the
  overrides of every subclass. One user exception class whose `fillInStackTrace`
  override stores `this` therefore made 55 bundled exception constructors, which
  all reach `Throwable()`, report the error, although building any of those classes
  never runs that override. Only the user's class was at fault.
- **Decision:** A constructor is reported when building an exact instance of its
  own class through it publishes the object. While the object is exactly the
  constructor's own receiver or a callee's first argument derived from it, calls
  on it dispatch on that class and the callees are evaluated in the same context;
  every other call keeps its ordinary summary, so a receiver that may be another
  object keeps every override. The existing context-free results still select the
  constructors to check. An override that publishes is reported at the subclass
  whose construction runs it; a superclass constructor is reported only when its
  own class's construction publishes.
- **Analysis:** Every construction of a concrete class is still checked, at that
  class's constructors, with the dispatch it actually performs, so no publishing
  construction goes unreported. A superclass whose own construction publishes is
  reported together with each subclass that runs it, as before. Abstract classes
  are checked with their own implementations, so a hook that only a subclass
  implements counts for that subclass. No runtime behavior changes.
- **Verification:** The user exception example reports one error at the user's
  class instead of 55. Overrides, abstract and interface hooks, delegating
  constructors and a field round trip through an override are reported only at the
  subclass; publication by a base constructor itself, also through its own field,
  is reported at the base and each subclass as before; a receiver that may be
  another object stays reported; a safe override is accepted. The standard library
  builds with `--unfreed=error` without diagnostics, and 74 examples compile
  identically.

## D283 - Report destructor effects at the class whose destruction has them

- **Status:** Accepted and implemented. Applies D282 to destructors; the
  destructor rules themselves are unchanged.
- **Context:** A destructor must not allocate, let an exception escape, or publish
  or resurrect `this`, and every subclass destructor runs its superclass
  destructor. The effect summaries took each destructor once for every class whose
  destruction may run it, so an override called on the object counted for every
  superclass. One subclass whose override of a cleanup hook allocated, threw or
  published `this` made the base destructor and every other subclass's destructor
  report the error, although destroying none of those classes runs that override.
- **Decision:** A destructor is reported when destroying an exact instance of its
  own class through it allocates, lets an exception escape or publishes the object.
  Constructors and destructors now share one exact-class summary: while the object
  is exactly the summarized function's first argument, calls on it dispatch on that
  class, use the same exact summaries for their callees, and also decide whether an
  exceptional edge is reachable, so a hook wrapped in `finally` counts only where
  it can throw. Every other call keeps its ordinary summary, so a receiver that may
  be another object keeps every override. The context-free summaries still select
  the functions to check.
- **Analysis:** Every destruction of a concrete class runs that class's destructor
  with the dispatch it actually performs, and that destructor is checked, so no
  allocating, throwing or publishing destruction goes unreported. A base destructor
  that allocates by itself is reported at the base and at each subclass, as before.
  Effects reached through a field whose declared type has subclasses are unchanged:
  `free` of such a field still considers every subclass destructor.
- **Verification:** For one overriding subclass among others, allocation, an
  escaping exception, publication and a throwing hook under `finally` are each
  reported only at that subclass, where three or six errors were reported before.
  A base destructor's own allocation and a receiver that may be another object
  report as before, a safe override is accepted, and the D282 constructor cases are
  unchanged on the shared summary. The standard library builds with
  `--unfreed=error` without diagnostics, and 74 examples compile identically.

## D284 - Release only the classes a freed value may be

- **Status:** Accepted and implemented. Supersedes D283's limitation that `free` of a
  field whose declared type has subclasses considers every subclass destructor.
- **Context:** A `free` or owned-element destruction was taken to run the destructor
  of every subclass of the operand's static type. Pooled objects are erased to
  `Object`, so one user class with an allocating destructor made the bundled
  `ArrayObjectPool`, `HashMap` and `HashSet` destructors report it, although none of
  them can hold that class; a destructor freeing an owned field that only ever holds
  an exact class was blamed for every subclass the same way.
- **Decision:** For the destructor and constructor verdicts, a free or element
  destruction runs only the destructors of the classes its value may be, as a
  closed-world, context-insensitive value flow over typed IR proves them. An object
  allocation gives its class and an array allocation its own site; fields join what
  is stored into them, calls what their targets return, and each array site what is
  stored into arrays that may be it. A store into an array of unknown origin joins
  every site whose element type it fits. Parameters join their call arguments where
  every caller is visible: for private methods and constructors, and for all
  callables of an executable with an entry point and no foreign calls; the entry
  point, destructors and rollbacks keep unknown parameters, as does every other
  source. `System.arraycopy` is modeled at its calls as a copy.
- **Analysis:** The flow is a sound over-approximation: reference fields are written
  only by field stores, since immortal objects carry primitive field values and the
  runtime keeps traces and secondary exceptions in its own tables; arrays reach code
  only through tracked values or as arrays of unknown origin; and unknown values keep
  every subclass. Precision is per class and allocation site, not per instance, so a
  pool that really holds a misbehaving class still makes every holder of that pool
  class report it. Provisional analyses keep their targets; no safety proof, lowering
  or runtime behavior changes. A small program compiles about 8 percent slower.
- **Verification:** A user map subclass with an allocating destructor beside
  `HashSet` reports one error instead of four, also in a library without an entry
  point; an owned field of the base class leaves its holder unreported. A holder of
  the misbehaving class, a pool built with it, an array of unknown origin and a
  builder handing out an array element of it all stay reported. The standard library
  builds with `--unfreed=error` without diagnostics and identical timing, and 74
  examples compile identically.

## D285 - Check destructors per object with object-sensitive value flow

- **Status:** Accepted and implemented. Supersedes D284's limitation that a pool
  holding a misbehaving class makes every holder of that pool class report it, and
  its value flow running on every compilation.
- **Context:** D284's value flow merged all instances of a class: every
  `ArrayObjectPool` shared one builder field and one set of creation-array elements.
  Pooling a user class with an allocating destructor therefore made `HashMap` and
  `HashSet` report it through their own pools of map entries.
- **Decision:** Value flow is object-sensitive. An object is an allocation site
  qualified by the object its allocating body ran on, so each pool keeps its builder
  and its arrays. A body is analyzed once per object in its first parameter, and a
  call whose first argument is a known object dispatches on that object's class and
  passes it alone. Every allocated object may be destroyed, so each object's
  destructor runs on it, and a destructor flagged by the context-free summary is
  judged for each object of its class the program allocates, through the bodies value
  flow recorded for that object; a class with no allocated object keeps the D283
  verdict. Static fields join their initial value and stores. A closed executable
  is analyzed from its entry point and class initializers; elsewhere every
  non-private body is also called by unseen code with unknown arguments. Value flow
  runs only when a context-free summary flags a destructor or constructor, since it
  can only narrow those verdicts.
- **Analysis:** The flow stays a sound over-approximation for the code it analyzes:
  every way a body runs is followed, through calls, dispatch, class initialization,
  frees, element destruction and rollback, and unknown receivers, arguments and
  arrays keep every object they could be. Each destruction of an object is judged
  with that object's contents, so no allocating, throwing or publishing destruction
  goes unreported. Code that never runs in a closed executable no longer contributes,
  so an unused constructor that installs a misbehaving part does not implicate the
  objects that exist. Compilations without such a verdict skip value flow.
- **Verification:** A pool of a user class with an allocating destructor, beside
  `HashSet`, reports the class and that pool but not `HashMap` or `HashSet`. A
  holder of a pool with a quiet builder stays unreported beside a holder of a pool of
  the misbehaving class; boxes built only with the base part stay unreported, and a
  loud box makes them reported. A public method storing into an array it receives,
  a holder of the misbehaving class and a builder handing out an array element keep
  their reports. Correct programs compile as fast as before D284, a program with a
  destructor error about 6 percent slower than with D284, and the standard library
  builds with `--unfreed=error` without diagnostics.

## D286 - Judge construction and destruction publication per object

- **Status:** Accepted and implemented. Extends D285's per-object verdicts to
  publication of an object under construction or destruction.
- **Context:** D285 judged destructor allocation and escaping exceptions per object,
  but publication still had class-wide parts: the constructor verdict summarized
  each constructor for any exact instance of its class (D282), and the receiver-field
  analysis judged constructors and destructors the same way for both. A call on an
  argument or field then reached every implementation in the program, so a
  constructor `Wrapper(Sink sink) { sink.accept(this); }` was rejected because some
  `Sink` stores its argument, although only quiet sinks were ever passed; a
  destructor calling `listener.closed(this)` was rejected the same way.
- **Decision:** A flagged constructor or destructor is judged for each object of its
  class that it runs on, in that object's value-flow context, in the effect summaries
  and in the receiver-field analysis: every call, free and element destruction takes
  the bodies value flow recorded for that object, solved in their own contexts. A
  constructor that builds no object of its class in the analyzed program, such as an
  unused one or a base constructor only subclasses run, keeps the exact-class verdict.
- **Analysis:** Value flow follows every object that reaches a constructor or
  destructor through calls, fields and arrays, and unknown values keep every
  implementation, so a publishing implementation that can be passed in is still
  reported. Publication through a subclass override stays attributed to the subclass
  (D282, D283). No proof, lowering or runtime behavior changes.
- **Verification:** The constructor given only quiet sinks and the destructor holding
  only quiet listeners are no longer reported for publication; given a storing
  implementation, both are. An unused publishing constructor and a base constructor
  run only by subclasses keep their verdicts, and the D282 to D285 cases are
  unchanged. The standard library builds with `--unfreed=error` without diagnostics,
  and 74 examples compile identically.

## D287 - Omit null checks that a dominating null test makes redundant

- **Status:** Accepted and implemented. Narrows the nullable-receiver effect of
  destructor validation (D283 to D286) without changing any proof.
- **Context:** Lowering checked every explicit dereference for null unless the
  reference was `this` or a conversion of it. A destructor written the Java way,
  `if (listener != null) { listener.closed(this); }`, kept the receiver check: the
  test and the call load the final field separately, and even a local tested for
  null was checked again. The check's failure path allocates and throws a
  `NullPointerException`, so the closed-world validation reported "destructor may
  allocate" and "an exception may escape this destructor" for a destructor that can
  do neither. Ordinary bodies carried the same infeasible paths into the typed IR
  that ownership analysis and the effect summaries read.
- **Decision:** A reference compared with `null`, tested with `instanceof` or checked
  for null is non-null on the branch edge that the outcome selects, through `!`,
  `&&` and `||` as for pattern variables. Lowering records that edge's target,
  whose only predecessor is the test, and omits a later null check of the same
  reference wherever the target dominates it in the control-flow graph built so
  far. A reference is an SSA value, a reference conversion of one, or a final
  instance field loaded again from the same receiver value. Constructors may store
  final fields, so their loads are not named. A destructor that frees an owned
  field of its class stores null into it, so the field's guards end where the free
  is lowered and do not cover a loop entered after the guard. The use-after-free
  check still runs where a null check is omitted.
- **Analysis:** SSA values never change, and outside constructors a final field
  changes only through such a destructor free. Lowering places code in a block only
  after every forward edge into it exists; later edges are loop back edges, which do
  not change dominance, and the only way a later free can reach an earlier use. At
  the end of each function every omission is checked again on the finished graph:
  the guard edge must still be its target's only entry, the target must dominate the
  check, and no store to a guarded field may lie between them. A failure is an
  internal compiler error, never emitted code. Only checks that cannot fail are
  removed, so `NullPointerException` behavior and its throwing site are unchanged,
  and nothing is added on valid paths (D132, D133); infeasible failure paths no
  longer reach ownership analysis or the effect summaries. A value merged at a join
  or loop header is not covered by a guard of its inputs, and an unguarded call on
  an owned field that is never null keeps the null-check effects.
- **Verification:** Destructors that guard a final field, a local, an `&&` or `||`
  operand, a conditional arm or a pattern binding, an owned field before its free,
  or a field inside the loop that frees it are accepted, as is the destructor
  holding quiet listeners (D286). An unguarded field, a guarded mutable field, a
  local reassigned after its guard, a use after the guarded branch, an owned field
  freed before its use, a free in a loop entered after the guard and a loop
  condition after the guard keep both reports. Typed IR loses only guarded checks,
  and a native program at `-O0` and `-O3` throws the same catchable
  `NullPointerException`s for reassigned values, merges, loops, catch handlers,
  labeled breaks, switch fallthrough and `finally`. At `-O3` LLVM had already folded
  the guarded checks of the inspected code, whose machine code is unchanged apart
  from fewer trace sites; the deterministic benchmarks lose unreachable failure
  paths and run within noise. The standard library builds with `--unfreed=error`,
  79 examples and projects compile with identical diagnostics, and the 72 linked
  examples run with identical output.

## D288 - Omit null checks that every merged value makes redundant

- **Status:** Accepted and implemented. Extends D287 to values merged at joins and
  loop headers; D287's omission under a dominating test is unchanged.
- **Context:** D287 omits a null check only where one null test dominates it.
  Lowering gives every local a new phi at each loop header and merges differing
  values at joins, so a local guarded before a loop was checked again inside it, and
  a value merged from guarded values or an allocation, such as
  `a != null ? a : new Node(...)`, kept its check with its `NullPointerException`
  allocation and throw. A destructor that guarded a local and then used it in a loop
  was still reported as allocating and throwing. A loop header's back edges are not
  known while its body is lowered, so lowering cannot decide these checks without
  speculating.
- **Decision:** After lowering, `RedundantNullChecks` solves the references known
  non-null at each block entry over the finished graph of the function, as the
  greatest fixed point of a must analysis. A block keeps what every reached
  predecessor knows on its edge into it; an edge adds what its null comparison,
  `instanceof` test, null check or recorded D287 condition proves; a phi is non-null
  when each incoming value is non-null on its edge. Allocation results and the
  references lowering proves are never null, facts follow reference conversions and
  D287's final-field loads, and a store to a final field ends that field's facts. A
  null check whose reference is known becomes a jump to its valid path, and the
  failure blocks that only it reached are removed with their phi entries. The
  emitter outlines `throw new X(...)` with or without the null check of the fresh
  object.
- **Analysis:** Loop headers start from every fact and keep only what each back edge
  preserves, so a value reassigned in a loop to anything not proven non-null is still
  checked. The greatest fixed point is sound because a merged value is always one of
  its incoming values. Facts name SSA values, which never change, and final fields,
  which change only through a destructor free whose store the analysis sees. The
  pass runs after ownership analysis, so it weakens no proof and affects only
  typed-IR consumers and LLVM. `NullPointerException` behavior and its throwing site
  are unchanged, and nothing is added on valid paths (D132, D133). Without the
  emitter change, omitting the check in `throw new` stopped its outlining, so the
  throw sequences of `Integer.parseInt` were emitted inline and changed inlining
  and register allocation enough for a deterministic benchmark's hot loop to reload
  its array from the stack. With it, the benchmark programs compile to the same
  machine code as under D287 apart from fewer trace sites and cold helpers.
- **Verification:** Guarded locals used in or after loops, nested loops, a loop exit
  after a break, joins of guarded values, a join and a conditional with an
  allocation, and a destructor fallback merged from a guarded final field lose their
  checks. A loop that advances or nulls the value, a join with a possibly-null
  value and a destructor that advances its local keep them, and the D287 cases are
  unchanged. Native programs at `-O0` and `-O3` throw the same catchable
  `NullPointerException`s, and an explicit throw stays outlined. The standard
  library builds with `--unfreed=error`, 79 examples and projects compile with
  identical diagnostics, every linked example program runs with identical output,
  and build time is unchanged.

## D289 - Trust final fields that construction leaves non-null in destructors

- **Status:** Accepted and implemented. Extends D287 and D288 to final fields that no
  test guards in the destructor.
- **Context:** A destructor written `private final Part part = new Part(); destructor
  { part.touch(); free part; }` kept the null check on `part`, so validation reported
  "destructor may allocate" and "an exception may escape this destructor" although no
  constructed object can reach the destructor with `part` null. D287 and D288 only
  trust values a test, a check or a merge proves non-null within the function.
- **Decision:** After the final lowering, `ConstructedFields` keeps a final reference
  instance field as constructed non-null when each of its stores is a constructor of
  its class storing, into its own object, a value that `RedundantNullChecks` proves
  non-null at that point, apart from the null that its class's destructor stores when
  it frees an owned field. Each destructor is solved again with those fields of its
  object, declared by its class or a superclass, known non-null at entry, and the
  checks they prove are removed. `RedundantNullChecks` now names final fields of one
  object from the loads in the IR, through reference conversions, in every function,
  and a store of a value known non-null to a final field starts that field's fact.
- **Analysis:** The language makes every constructor that completes normally assign
  each blank final field of its class exactly once, and runs field initializers in
  every constructor that does not delegate, so each completed construction stores
  such a field a non-null value. A destructor runs only on a fully constructed
  object, and the subclass destructors that run before it cannot store the fields of
  its class or its superclasses, so those fields keep their values until its own
  free, whose store ends the fact, also across loop back edges. A subclass field is
  not trusted in a superclass destructor, since the subclass destructor runs first
  and may free it, and methods are not covered, since one may run during
  construction or after the free. The pass only removes checks after ownership
  analysis and adds nothing on valid paths (D132, D133); the deterministic benchmarks
  compile to the same machine code as under D288.
- **Verification:** Destructors using a field initialized or constructor-assigned with
  an allocation, a field assigned a parameter checked for null, or an inherited field
  are accepted. A field assigned an unchecked parameter, a field one constructor sets
  to null, a use after the free, a free inside a loop, a subclass field read by the
  superclass destructor and a field used through a method keep both reports. A native
  program at `-O0` and `-O3` runs those destructors and rejects a null argument. The
  standard library builds with `--unfreed=error`, 79 examples and projects compile
  with identical diagnostics, every linked example program runs with identical
  output, and build time is unchanged.

## D290 - Judge the methods a destructor runs on its object with its constructed fields

- **Status:** Accepted and implemented. Extends D289 from the destructor body to the
  methods it calls on its object.
- **Context:** D289 trusts constructed non-null fields only in destructor bodies. A
  destructor written `destructor { flush(); free buffer; }`, where `flush` uses
  `buffer`, was still reported as allocating and throwing, because `flush` keeps its
  null check: a method may also run during construction, before the field is
  assigned, or after the free.
- **Decision:** The destructor verdict judges the methods it runs on its object with
  the fields known there, and their code keeps its checks. `ClosedWorldEffectAnalyzer`
  summarizes a flagged destructor with its object's constructed fields known at
  entry. A call whose first argument is the object passes on the fields the caller's
  dataflow still knows at that call, and the callee is summarized, in the exact-class
  and value-flow contexts alike, as a view of its body with the null checks those
  fields prove removed. Summaries are kept per body and set of known fields.
- **Analysis:** Only constructors and the object's own destructors store its final
  fields, so the fields known at a call stay non-null for the whole call, through
  nested calls on the object. A field freed before the call is no longer known there,
  and the superclass destructor chain receives only the fields a subclass destructor
  left in place, so an override reached after a subclass free keeps its report. Calls
  on other objects, or with the object in another position, pass no fields. The
  views only exclude failure paths that cannot run during that destruction, so no
  allocation, escaping exception or publication goes unreported; emitted code and
  ownership proofs are unchanged (D132, D133). A superclass destructor is now also
  judged with the fields a subclass destructor leaves in place, so destroying a
  subclass that never frees a field no longer reports the superclass's use of it.
- **Verification:** Destructors calling a method, nested methods or a static method
  given the object before the free are accepted, as is a subclass destructor calling
  its own method before its free. A method called after the free, an override that
  the superclass destructor reaches after the subclass freed the field, and a method
  of another object keep both reports. The standard library builds with
  `--unfreed=error`, 79 examples and projects compile with identical diagnostics, 69
  examples emit identical LLVM, and the standard library compiles in slightly more
  CPU time.

## D291 - Validate Bridge export names with Java 21's qualified-name rules in the port

- **Status:** Implemented during M6.1 on 2026-10-07.
- **Decision:** The compiler port gains B7's Java 21 qualified-name validation
  for the S7 ports of BridgePackageInputs and BridgeExportSurface; the Java seed
  keeps `SourceVersion.isName`.
  - **JavaIdentifiers.** Adds `isJavaIdentifierStart(int)`: 683 ranges generated,
    like D277's 798 part ranges, from JDK 21's answer for every code point by the
    same generator and checked by the same regeneration test.
  - **JavaNames.** `isName` splits at every dot, empty components included, and
    accepts a component when its first code point is an identifier start, every
    later one an identifier part (surrogates paired as `codePointAt` pairs them,
    an unpaired surrogate neither) and it is not a keyword: JLS 21's reserved
    keywords, `_` included, and `true`, `false` and `null`. Contextual keywords
    are identifiers. The Ironwood lexer's narrower grammar is not used, and
    DocComment's UTF-16 tag scan is unchanged.
  - **BridgeExports.** Both callers' export loops: request order for
    diagnostics, `invalid Java Bridge export package: '<name>'`, the export
    surface's separate `ironwood.bridge` reservation, the package inputs'
    `Java Bridge requires at least one exact export package`, and accepted
    packages kept once each in String order. A native request list has no null
    element, so the callers' null branch has no native input.
- **Proof:** No analysis, runtime or lowering change. The selection is built in
  its constructor, so any failure rolls it back; names and diagnostics are
  handed out as fresh Strings.
- **Boundary:** The validator needs no `javax.lang.model` API or JDK at run
  time. Callers that stop on a diagnostic ignore the accepted packages, as the
  Java callers do. Supersedes no decision.
- **Provenance:** Original code under the default license; the start ranges are
  Unicode 15.0 character data observed through JDK 21's `Character` with no
  OpenJDK source consulted, carrying the Unicode notice like D277's part ranges.
  The keyword list comes from JLS 21 sections 3.9 and 3.10.
- **Verification:** For 4,245 names (every reserved and contextual keyword,
  keyword-qualified forms, empty, leading, trailing and repeated dots,
  non-ASCII letters and digits, identifier-ignorable characters, combining
  marks, letter numbers, paired and isolated surrogates, and 4,000 seeded
  mixes), 70 export groups for both callers, and the start and part predicates
  over every code point, the 6,224-line transcript equals JDK 21's
  `SourceVersion` and the callers' loops from class and archive links; the
  ownership pairs hold in every unfreed mode and every allocation failure
  unwinds. See [the M6.1 names record](self-hosting/m6/NAMES.md).

## D292 - Serialize Bridge identities, inventories and manifests exactly in the port

- **Status:** Implemented during M6.1 on 2026-10-07.
- **Decision:** The compiler port gains B5, B6 and B7's exact Bridge
  serialization helpers for the S7 ports of BridgeGeneration,
  BridgePackageManifest, BridgePairedArchive, BridgeAssembler,
  BridgeDistributionCommand, BridgeJarArchive and BridgeValuesLibrary; the Java
  seed is unchanged.
  - **TextMap.** An owned String-ordered map of texts for the inventories Java
    keeps in `TreeMap<String, String>`: `put` replaces, `putIfAbsent` refuses,
    lookups and positions hand out fresh Strings or lend single units, so the
    map never exposes Strings it holds (the TextList precedent of D276).
  - **BridgeIdentity.** `BridgeGeneration.digest`'s framing (the domain string
    `ironwood-java-bridge-identity-v1`, then every key and value in String
    order, each as a big-endian 32-bit UTF-16 count and big-endian units),
    `bytesDigest` and `contentIdentity` with its three failure texts, over
    D267's `Sha256`; `isHash` is the `[0-9a-f]{64}` check.
  - **BridgeProperties.** BridgePackageManifest's canonical writer (header,
    String order, `\u` and four lowercase hex digits for every unit at or below
    space, above `~` or among `\:=#!`, US-ASCII), a reader with
    `Properties.load(InputStream)`'s semantics written from its published
    specification (ISO-8859-1, comments, continuations, separators, escapes,
    later keys replacing earlier ones, and its `Malformed \uxxxx encoding.`
    failure), and the pairing readers' canonical check.
  - **JarManifest.** `Manifest.write` for a main section (version first, other
    attributes in insertion order, lines broken after 72 and then every 71
    UTF-8 bytes even inside a character, CRLF, a closing empty line, nothing
    else without a version) and a reader with Java's verdicts and messages for
    the whole manifest (512-byte lines, CR, LF and CRLF ends, an ignored
    unterminated short tail, space continuations joined before UTF-8 decoding,
    case-insensitive `Name:` sections), keeping main attributes with
    `Attributes.Name`'s case-insensitive names.
- **Proof:** No analysis, runtime or lowering change. Two element loads whose
  index was a call failed the field-ownership proof (D281); the index is now a
  local first. A JAR manifest's bytes collect as units so its result is the
  last allocation, after the allocation sweep found the first version leaking
  its result when a later line failed.
- **Boundary:** Java logs a warning for a repeated manifest attribute; the
  reader keeps the later value without logging. The Linux support manifest,
  whose `stringPropertyNames()` Java visits in hash order, is read in String
  order, so a native consumer naming the first changed file names the first in
  String order (M3.3's precedent). TLS's own inventory keeps D268's
  `PropertiesText`. Supersedes no decision.
- **Provenance:** Original code under the default license, written from the
  `Properties`, `Manifest` and JAR File Specification documentation and JDK 21's
  observed behavior; no OpenJDK source was consulted.
- **Verification:** For 414 maps, 66 byte vectors, 2,559 properties texts
  (hand-picked edge cases, the pinned TLS and Bridge support inventories, every
  map's canonical form, 1,500 seeded texts and 600 mutations of canonical
  ones), 211 attribute lists and 1,483 manifest texts, the 13,591-line
  transcript equals the baseline's BridgeGeneration and BridgePackageManifest
  methods and JDK 21's `Properties` and `Manifest` from class and archive
  links; the ownership pairs hold in every unfreed mode and every allocation
  failure unwinds. See [the M6.1 inventories record](self-hosting/m6/INVENTORIES.md).

## D293 - Give the port the Bridge generators' text conversions, patterns and file inventories

- **Status:** Implemented during M6.1 on 2026-10-07.
- **Decision:** The compiler port gains B7's last Bridge text helpers and B3's
  Bridge file-inventory selection for S7; the Java seed is unchanged.
  - **BridgeText.** `Float.toHexString` and `Double.toHexString` from the
    binary layout as Java 21 documents them (subnormal floats keep `p-126`,
    every other float prints as its exact double), `String.format`'s
    `\%03o` and `\u%04x` escapes as unsigned 32-bit values, and
    `stripTrailing` with Java's white-space set.
  - **BridgePatterns.** The Bridge's `String.matches` patterns as scans: the
    Maven group, Maven artifact and version, the C root name, the macOS
    minimum version, the extracted dependency path and the ensure method;
    `[0-9a-f]{64}` is D292's `BridgeIdentity.isHash`.
  - **ReadelfScan.** BridgeLinuxPayload.auditDynamic's four regular
    expressions over `llvm-readelf` output (`FLAGS[^\n]*NOW`, the rpath and
    runpath brackets, shared libraries, `Name: GLIBC_([0-9.]+)`) with
    `Matcher.find`'s retry and resume semantics, collecting distinct groups
    in String order as the caller's `TreeSet` does.
  - **SourceFiles.** BridgeDistributionInputs' and BridgeProducerInputs'
    runtime selection, `Files.walk` filtered to regular `.c` and `.h` files in
    Path order with `/`-spelled relative names, as two D276 `FileCollector`
    walks merged in code point order. The other Bridge walks and listings use
    `FileCollector` directly: export package directories and the distribution
    stage at depth one, the assembler's compiled classes recursively; staging
    cleanups use M4.1's `TreeDeletion`.
  - **Conventions.** `BridgeGeneration.constant` hashes a float constant's raw
    bits; the native constant representation keeps those bits (an `int`
    payload, as D268 kept double payloads) instead of adding a public
    `Float.floatToRawIntBits`. Generated Java and C stay byte for byte;
    `Character.toUpperCase` only capitalizes a primitive type name for JNI's
    `Get<Kind>ArrayRegion`, which is ASCII casing.
- **Proof:** No analysis, runtime or lowering change.
- **Boundary:** The scans implement exactly their call sites' patterns, not a
  regular-expression engine. A public `Float.floatToRawIntBits`, like D268's
  double method, remains a separate public API decision. Supersedes no
  decision.
- **Provenance:** Original code under the default license, from Java 21's API
  documentation and observed results; no OpenJDK source was consulted.
- **Verification:** For 4,523 float and 4,518 double bit patterns (zeros,
  subnormals, normal boundaries, extremes, infinities, NaN payloads and seeded
  patterns), 810 integers, 3,353 strings and 607 `llvm-readelf` outputs, the
  13,811-line transcript equals JDK 21's conversions, patterns and regular
  expressions and the baseline's own `quote` and `cString`; on a tree with links to files, directories and nothing,
  directories named like sources and Unicode names, the runtime inventory,
  identity, listings and class walk equal Java's `Files.walk` and
  `Files.list`; the ownership pairs hold and every allocation failure unwinds.
  See [the M6.1 text and inventories record](self-hosting/m6/TEXT.md).

## D294 - Write and verify Bridge JARs natively in the STORED profile

- **Status:** Implemented during M6.2 on 2026-10-07. The native producer's JDK
  selection is D295.
- **Decision:** The compiler port gains BridgeJarArchive.publish's writer
  profile and the values companion's manifest lookup for S7; the Java seed is
  unchanged.
  - **BridgeJar.** Contents with `Map.put` semantics, each copied once.
    Publishing checks every name in String order with the baseline's rule
    (nonempty; no backslash or NUL; no empty, `.` or `..` part), requires a
    `META-INF/MANIFEST.MF` that reads as a manifest (D292's messages pass
    through) with main `Manifest-Version: 1.0`, and refuses a destination that
    exists without following a final link and is not a regular file
    (`Files.isSymbolicLink` or `exists` and not `isRegularFile`). It stages
    `createTempFile(parent, ".ironwood-bridge-", ".jar")` with the manifest
    first and the other entries in String order, all STORED in Java's spelling
    with time 0 (D275), reads the stage back with `ZipArchive` and compares
    the entry count and each entry's presence, size and bytes, then publishes
    with `moveAtomicReplacing` (D271) and deletes the stage on every exit.
    Comparing bytes replaces the baseline's comparison of their SHA-256
    digests.
  - **JarStreams.** `JarInputStream.getManifest()`: the first entry, or the
    second after a `META-INF/` directory entry, named `META-INF/MANIFEST.MF`
    without regard to case, read by `ZipStream`.
  - **JDK tools.** S7's producer runs `javac` and `javadoc` by absolute path
    through D273's `Command` and D272's `runToFile` with BridgeBuildTools'
    flags; with the same JDK they write the in-process tools' class files,
    pages and diagnostics.
- **Identity effects:** STORED Bridge jars hold the Java bootstrap's entry
  bytes, so every identity over entry contents is unchanged
  (`content.sha256.<entry>`, `native.sha256`, the projection and distribution
  input identities). Identities over whole jars change: the values
  companion's `java.values.sha256`, the distribution inventory's
  `sha256.<file>`, and the companion bytes that BridgeValuesLibrary.copy and
  assembly compare, so a Java-built and a native-built companion are not
  interchangeable in one output directory or one assembly. Assembly already
  refuses mixed producers through `compiler.sha256`, which S7's producer
  identity design replaces.
- **Proof:** No analysis, runtime or lowering change. An element load whose
  index was a call failed D281's field proof and is now computed into a local;
  the allocation sweep found JarStreams leaking an entry's bytes when its name
  check failed, now released before the failure propagates.
- **Boundary:** No fsync or durability policy. JarStreams does not verify jar
  signatures, which the companion never carries. A staged jar that fails to
  parse reports the native container message. Supersedes no decision.
- **Provenance:** Original code under the default license; Java 21's API
  documentation and observed behavior are the references.
- **Verification:** For four content sets (a paired artifact with a loadable
  class, companions, a values jar and 3,000 entries), native jars list
  Java's entries in Java's order with Java's bytes, equal Java's STORED
  spelling byte for byte, and open in `ZipFile`, `JarFile`, `JarInputStream`,
  a class loader, `jar tf` and `jar --describe-module`; 20 invalid names and
  manifests and three non-regular destinations give the publisher's messages
  and keep the earlier jar, while a lowercase `manifest-version` publishes as
  in Java; a read-only parent keeps the earlier jar; every
  allocation failure keeps it and leaves no stage; 144 companion and metadata
  combinations give BridgeValuesLibrary.validate's verdicts; the distribution
  inventory and the assembler's target order match; and `javac` and `javadoc`
  through `runToFile` equal the in-process tools. See
  [the M6.2 record](self-hosting/m6/JAR.md).

## D295 - Select the native Bridge producer's JDK as the Java producer's launcher does

- **Status:** Accepted by the maintainer on 2026-10-07 (option B with R2 of
  [the M6.2 options](self-hosting/m6/JDK_SELECTION.md)) and implemented in the
  compiler port during M6.2. Extends D239's selection to the native producer;
  the Java producer, its launchers and `scripts/jdk.sh` are unchanged.
- **Decision:** The port's `JdkSelection` gives the native producer the JDK
  the Java producer's launcher would give it.
  - **Selection.** A nonempty `JAVA_HOME` selects `$JAVA_HOME/bin/java`, and an
    invalid one fails without fallback (`selected Java is missing; set
    JAVA_HOME to a JDK: <path>`); otherwise the installation's
    `toolchain/lib/jvm/bin/java` when it is executable; otherwise `java` found
    on `PATH` by D273's ExecutableSearch (`... JDK: java on PATH` when none
    is). Relative spellings resolve against the working directory.
  - **Inspection and recording.** The selected `java
    -XshowSettings:properties -version` runs through `runToFile` into a scratch
    log, read within 1 MiB and always deleted. Its `java.home`,
    `java.specification.version`, `java.runtime.version` and `java.vendor`
    give the home, the feature release and the `jdk.version` and `jdk.vendor`
    identity inputs, the values the Java producer records from its running JVM;
    a nonzero exit or a missing value gives `could not inspect selected Java:
    <path>`.
  - **Gates and tools.** BridgeBuildTools' checks keep their messages: feature
    21 to 25, the `javac` and `javadoc` launchers in place of the in-process
    tools, and the JNI headers. `javac` and `javadoc` run by absolute path with
    BridgeBuildTools' flags (the assembler passes its host jar as the class
    path), their diagnostics read back from a deleted log.
- **Proof:** No analysis, runtime or lowering change. The inspected values
  live in a TextList owner and are handed out as fresh Strings, because a
  String field copied with `new String(field)` or used through a local alias
  during calls fails the field-ownership proof.
- **Boundary:** `PATH` empty entries are skipped, as the native driver's
  ExecutableSearch does, where `command -v` would search the working
  directory. A selected program that cannot start reports ProcessRunner's
  failure instead of `could not inspect`. Where the native producer's
  installation root comes from is S7 and S8 packaging. Usage documentation
  changes when S7 ships the native producer. Supersedes no decision.
- **Provenance:** Original code under the default license.
- **Verification:** 31 selections (JAVA_HOME, the installation, PATH and their
  precedence, empty, relative and missing selections, nothing on PATH, JDK
  doubles that fail, omit a value, are out of range or lack javac, javadoc or
  a header, and 13 installed JDKs from Oracle, GraalVM, Eclipse Temurin, IBM
  Semeru and Azul Zulu, two of them out of range) select what
  `scripts/jdk.sh` selects, report its messages, record each JDK's own
  `System.getProperty` values and give BridgeBuildTools' gate messages;
  `javac` and `javadoc` through the selection equal the in-process tools; the
  ownership pairs hold; and 125 allocation limits each unwind and leave no
  log. See [the M6.2 record](self-hosting/m6/JAR.md).

## D296 - Judge a rejected free by its local's type in field-loan proofs

- **Status:** Accepted and implemented. Amends D281.
- **Context:** D281's field-loan proof counts a free inside a loan as code that
  may run unless typed IR shows the freed value has no destructor. A free that
  lowering rejects emits no instruction, so typed IR showed nothing and the
  proof failed even for a class without a destructor. The free stayed
  rejected, but the wrapper field lost its encapsulation proof, the
  constructor argument then escaped, and that escape became the reported
  reason in place of the live alias that observes the allocation (`cannot free
  'x': allocation may still be observed through local 'k'`).
- **Decision:** Provisional lowering records each rejected free of a local
  with the local's static type, and the field-loan proof counts it as running
  no code when no class of that type in the closed world has a destructor.
  Those destructors include every one that any free of the local could run, so
  the judgment holds even if a later lowering accepts the free. Arrays and
  other types stay possible, since freeing them can destroy elements, and so
  do frees of expressions other than a local.
- **Boundary:** A free whose class has a destructor still fails the proof
  while an alias is live. Freeing the owner `o` while `k` aliases `o.held`,
  when its destructor frees `held`, stays rejected at the destructor's free
  of the field, as D281 decided; `--explain-rejected-free` names `free o`.
  (Superseded by D297, which rejects that free where it happens.)
  Field facts change only where provisional lowering rejected a free of a
  destructor-free local inside a loan. No runtime instruction, check or cost
  changes. Supersedes no decision.
- **Verification:** The four wrapper-field rejections in the safe-free tests
  report the live alias again. D281's reentry routes gain a free that runs a
  destructor, with its safe twin, and a paired test keeps a rejected free of a
  class with a reentrant destructor failing the field's proof while a rejected
  free of a class without one leaves only that free's own error and the field
  owned; on the D281 analysis all three changed tests fail. The standard
  library builds, every port source compiles without diagnostics, 69 examples
  emit the same `-O3` LLVM as before, and the focused field, wrapper, owner and
  pool tests pass.

## D297 - Reject at the free what an owner's destructor would make a field-proof failure

- **Status:** Accepted and implemented. Amends D281 and supersedes D296's
  boundary that freeing the owner stays rejected at the destructor.
- **Context:** Under D281 a free that may run code while a local alias of an
  attached field allocation is live fails the field's ownership proof. When
  the owner's destructor frees that field, the failure surfaced at the
  destructor (`cannot prove destructor free of field 'held' safe: field
  ownership is uncertain`), away from its cause. Before D281, freeing the owner
  was rejected where it happened, for the alias it would leave dangling
  (`cannot free 'o': allocation may still be observed through local 'k'`).
- **Decision:** When the owner's own destructor frees the field in a top-level
  statement, the ownership proof leaves such a free of a local to lowering:
  it records the statement, by source and span, with the alias and field it
  would cross, and the field stays owned unless something else fails.
  Lowering rejects every recorded free. Its own proof usually does, as for the
  owner, whose destructor would free the field the alias observes; otherwise
  it reports `cannot free 'victim': it can run a destructor while local 'k'
  aliases field 'held'`. Provisional proofs, which have no typed IR, and
  encapsulation proofs keep D281's rejection.
- **Analysis:** Without the exemption the field fails its proof, the
  destructor's top-level free of it is an error, and the program is invalid.
  With it, the recorded free is an error instead. A program with a recorded
  free is therefore invalid either way: no recorded free runs during a loan,
  and no valid program's field facts or code change. The destructor statement
  is top-level so that lowering always reaches it and resolves it to the
  field.
- **Boundary:** A destructor that frees the field only through a call, a
  deferred free, or a free of something other than a local keeps D281's
  report (D298 recognizes calls and branches). A recorded free in an instance
  initializer is rejected once for each constructor that lowers it. No runtime
  instruction, check or cost changes.
- **Verification:** The owner case in the wrapper-field safe-free test
  reports the alias again. A new test rejects, at the free and with the field
  still owned, freeing an object whose destructor reenters the owner during a
  loan; its safe twin compiles; and freeing the owner names the alias. On the
  D296 analysis both tests fail. Probes with a dead alias, a destructor that
  frees the field through a call, and instance and static initializers behave
  as described. The focused field, wrapper, owner, pool and explanation tests
  pass, 69 examples emit the same `-O3` LLVM, the standard library builds and
  every port source compiles without diagnostics.

## D298 - Recognize a field's reclamation through methods and branches

- **Status:** Accepted and implemented. Amends D297 and supersedes its boundary
  that a destructor freeing the field through a call keeps D281's report.
- **Context:** D297 rejects at the free a free of a local that may run code
  during a loan, but only when the owner's destructor frees the field in a
  top-level statement. A destructor that frees it inside a branch, or through
  a method such as `release()` that detaches and frees the field, still
  reported the failure at that method's free (`cannot prove free of 'old'
  safe: ...`) instead of at `free o`.
- **Decision:** The field's ownership counts as needed, and D297's handling
  applies, when either witness holds. First, the owner's destructor frees the
  field at any depth, as lowering resolves the name: `this.name`, or `name`
  with no local of that name in scope. Second, one of the owner's instance
  methods, other than its constructors and destructor, declares a local at its
  top level from the field through `this`, never assigns it (D303: writes no
  other value to it before the free), and frees it later at any depth. A
  probe scan of the destructor or method finds both.
- **Analysis:** Lowering reaches every such free (D302: except in a finally
  block that no route reaches) and accepts it only for an owned field: a
  destructor's free of the field is checked against the field's proof, and
  in an instance method the receiver has no tracked allocation, so the
  loaded value is detached only when the field is owned. Without
  ownership each witness free is an error, so a program with a recorded free
  is invalid either way, as in D297, and no valid program's facts or code
  change. Java scoping makes every later free of that local name refer to the
  top-level declaration.
- **Boundary:** A reclamation outside both witnesses, such as a detached
  local declared inside a branch, keeps D281's report at that reclamation
  (D299 recognizes branch declarations). A free through another receiver is
  no reclamation (D301). Supersedes no other decision.
- **Verification:** Freeing the owner while `k` aliases `o.held` now names
  the alias when the destructor frees the field in a branch or through a
  top-level or guarded `release()`, each isolated on a store with no other
  witness. D281's free route and D296's paired test moved to that store, where
  they still fail the field's proof. A probe that frees a same-named local from
  an earlier block stays valid. The focused field, wrapper, owner, pool and
  explanation tests pass, 69 examples emit the same `-O3` LLVM, the standard
  library builds and every port source compiles without diagnostics.

## D299 - Recognize a detached local declared in a branch

- **Status:** Accepted and implemented. Amends D298 and supersedes its
  boundary that a detached local declared inside a branch keeps D281's report.
- **Context:** D298's method witness required the local loaded from the
  field to be declared at the method's top level. A method that detaches and
  frees the field inside a check, `if (held != null) { Keeper old = held;
  held = null; free old; }`, still left the failure at that free (`cannot
  prove free of 'old' safe: ...`) instead of at the free of the owner.
- **Decision:** The method witness accepts a local declared in any block of
  the method, from the field through `this` as lowering resolves the name.
  The probe scan records each such declaration with its block, and a free of
  that name counts when it lies inside that block after the declaration and
  the method never assigns the name.
- **Analysis:** Java forbids redeclaring a local while it is in scope, so a
  later free of that name inside the block refers to that local or makes the
  program invalid. Its value is then the field loaded through a receiver with
  no tracked allocation, which lowering frees only when the field is owned.
  As in D297 and D298, a program with a recorded free is invalid either way,
  and no valid program's facts or code change. A free of the same name in a
  later sibling block refers to another local and does not count.
- **Boundary:** A reclamation outside the witnesses, such as a detaching free
  in a constructor or a deferred free, keeps D281's report at that
  reclamation (D300 recognizes constructors and D301 deferred frees). A free
  through another receiver is no reclamation (D301). Supersedes no other
  decision.
- **Verification:** Freeing the owner while `k` aliases `o.held` names the
  alias when the destructor calls a method that detaches and frees the field
  inside a branch, also isolated on a store whose only other reclamation is
  in its constructor; on the D298 analysis that report stays at the method's
  free. A method that frees a same-named fresh local in a later sibling block
  stays valid. D281's free route and D296's paired test now use that store.
  The focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D300 - Recognize a field's reclamation in constructors, initializers and destructors

- **Status:** Accepted and implemented. Amends D299 and supersedes its
  boundary that a detaching free in a constructor keeps D281's report.
- **Context:** D298 and D299 limited their witness to instance methods,
  excluding constructors in case lowering tracked the receiver under
  construction. A class that detaches and frees the field in a constructor,
  `int[] old = values; values = new int[2]; free old;`, still reported a free
  that may run code during a loan at the constructor's free (`cannot prove
  free of 'old' safe: ...`) instead of at that free.
- **Decision:** The witness covers all of the owner's instance code: its
  constructors, methods and destructor, each scanned by its own probe, and its
  instance initializers, scanned together as one scope chain. A local declared
  in a block from the field through `this`, never assigned in that code and
  freed later in that block makes the field's ownership needed.
- **Analysis:** Lowering never tracks an allocation for `this` (the
  known-receiver path applies only to other receivers), so a field read through
  `this` in any instance function is an attached loan that only an owned
  field's detachment can free. Without ownership the witness free is an error,
  so a program with a recorded free is invalid either way, as in D297-D299, and
  no valid program's facts or code change.
- **Boundary:** A deferred free of the detached local keeps D281's report
  (D301 recognizes a deferred free that lowering reaches), and a free through
  another receiver is no reclamation (D301). Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected at
  the free, with the field owned, when the store's only other reclamation is a
  constructor or an instance initializer that detaches and frees the field; on
  the D299 analysis the report stays at that reclamation. D281's free route and
  D296's paired test now use a store that reclaims with a deferred free. The
  focused field, wrapper, owner, pool and explanation tests pass, 69 examples
  emit the same `-O3` LLVM, the standard library builds and every port source
  compiles without diagnostics.

## D301 - Recognize a field's reclamation by a deferred free

- **Status:** Accepted and implemented. Amends D300 and supersedes its
  boundary that a deferred free of the detached local keeps D281's report.
- **Context:** D298-D300 counted only a `free` statement of a local loaded
  from the field. A class that reclaims the field with `int[] old = values;
  values = null; defer free old;` still reported a free that may run code
  during a loan at the deferred free (`cannot defer free of 'old': target
  must be a live, proven owned local reference`) instead of at that free.
- **Decision:** A deferred free of such a local counts as the witness's free
  when the statements after it in its block contain, at any statement depth,
  no `while` or `do` loop whose condition is the literal `true` and no `for`
  loop whose condition is absent or that literal. The other D300 conditions
  are unchanged: the local is declared in a block from the field through
  `this`, never assigned, and freed later in that block.
- **Analysis:** Lowering frees a deferred target at the normal completion of
  the rest of its block and at every return, break, continue, yield or
  exception that leaves it. Only such a loop can make all of those
  unreachable: lowering keeps a statement's normal completion reachable
  whatever its expressions do, and a local class body is another function.
  Each lowering of the deferred free accepts it only for a detached owned
  field, as for a direct free; without ownership the local stays attached or
  has no identity, so its registration or its lowering is an error. A program
  with a recorded free is therefore invalid either way, as in D297-D300, and
  no valid program's facts or code change.
- **Boundary:** A deferred free after such a loop, even one the loop's
  `break` leaves (D302 recognizes a loop with a route out), a deferred free
  placed directly in an old-style switch group (D302: the parser rejects a
  defer outside a braced block, and a braced case is recognized), and a local
  assigned from the field after its declaration keep D281's report at that
  reclamation. A free through another receiver is no
  reclamation and has no D281 report to move: D041's loan exists only for
  loads through `this`, so the field's ownership never proves a free of a
  value read through another receiver, and assigning the field through
  another receiver fails the field's proof by itself. The D298-D300
  boundaries, which said such a free kept D281's report, are corrected to
  match. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, when the store's only other reclamation
  is a deferred free at the end of its block or before a counted loop, and
  each safe twin compiles; after a `while (true)` loop that breaks, D281
  reports at the deferred free. D281's free route and D296's paired test now
  use a store that frees a local assigned from the field after its
  declaration, and assert the proof's failure note there. A store that also
  assigns the field through another receiver fails the proof by that write
  alone, and one that frees a value read through another receiver keeps the
  field owned and rejects that free. On the D300 analysis the deferred cases
  still report at the reclamations, while the relocated D281 and D296 checks
  pass. The focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D302 - Count only witness frees that lowering reaches

- **Status:** Accepted and implemented. Amends D298-D301: supersedes D301's
  boundary that a deferred free after a literal-`true` loop keeps D281's
  report, and corrects D298's analysis that lowering reaches every witness
  free.
- **Context:** D301 excluded a deferred free followed by a `while (true)`,
  `do ... while (true)` or `for (;;)` loop even when a `break`, `return` or
  `throw` leaves the loop, so those programs still reported a free that may
  run code during a loan at the deferred free. Lowering also lowers a finally
  block only on a route that reaches it, and the D298-D300 witness counted a
  free there too. After a try body that never leaves, as in `try { while
  (true) { } } finally { free old; }`, that free is never lowered, so the
  program compiles under D281, and the witness made it an error at the free
  during the loan: a valid program became invalid.
- **Decision:** `LoweredRoutes` under-approximates the routes lowering takes
  out of statements: their normal completion, where a loop ends without a
  break only when its condition is not the literal `true`, or a return,
  yield, break or continue that leaves them, or a throw outside every try
  statement there, through finally blocks that complete. A witness probe
  records a deferred free only when the rest of its block has such a route,
  and records no free in a finally block, the destructor's free of the field
  included, unless the try body completes or has such a route (a throw only
  when the try statement has no catch clause) or a catch body transfers out.
- **Analysis:** Lowering lowers a deferred action or a finally block on
  exactly those routes, and every other statement where it stands, reporting
  the unreachable ones; a catch body is analyzed even when its try body
  cannot throw, with the finally context but no exception region, so only
  its transfers count. Every route found is one lowering takes, so each
  recorded free is lowered and, without ownership, rejected: a program with a
  recorded free is invalid either way, and no valid program's facts or code
  change (D297-D301). Exceptions from calls, the completion of catch bodies
  and do-while exits through `continue` are not counted, which only leaves
  D281's report in place.
- **Boundary:** A deferred free whose remaining block has no such route, such
  as an empty `while (true)` loop or one whose only `break` passes a finally
  block that cannot complete, a free in a finally block that no such route
  reaches, and a local assigned from the field after its declaration (D303
  recognizes an assignment statement) keep D281's report. A deferred free
  placed directly in an old-style switch group, which D301 listed, has no
  report to move: the parser accepts a defer only as a direct statement of a
  braced block (LANGUAGE.md), and in a braced case the rest of the block,
  such as its `break`, routes out, so it is a witness. Supersedes no other
  decision.
- **Verification:** Deferred frees before a `while (true)` loop that a break
  leaves, a `for (;;)` loop that returns and a `while (true)` loop that
  throws, and one in a braced switch case, are witnesses, while an empty loop
  and a break stopped by a finally block that cannot complete keep D281's
  report; a defer placed directly in a switch group is rejected by the
  parser. A program whose only reclamation frees in a finally block after an
  empty `while (true)` loop compiles, as under D281, and a finally block
  after a completing body is a witness. Through a field that escapes to a
  sibling field, which keeps a load's identity attached, lowering reports
  each free the witness counts and not the unreached finally block; a
  29-case local differential of these shapes found no counted free that
  lowering did not lower. On the D301 analysis the restored loop case fails.
  The focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D303 - Recognize a field's reclamation through an assigned local

- **Status:** Accepted and implemented. Amends D298-D302: supersedes D302's
  boundary that a local assigned from the field after its declaration keeps
  D281's report, and replaces the D298-D302 condition that the local is never
  assigned with no write between the load and the free.
- **Context:** The witness counted only a local declared from the field and
  never assigned. A class that reclaims with `int[] old = null; old = values;
  values = null; free old;`, frees a parameter it assigned from the field, or
  sets the local to `null` after freeing it, still reported a free that may
  run code during a loan at its reclamation instead of at that free.
- **Decision:** A block statement that assigns the field through `this` to a
  local or parameter with a plain `=` is a field load, like a declaration
  from the field. A free of that local counts when it follows in the block
  where lowering is certain to lower it (D302) and no write to the local lies
  between the load's statement and the end of the block statement that holds
  the free, or the end of a deferred free's block. The probe records the
  position of every write to a local, and now also scans a fresh-borrowing
  factory's return value for writes, so that record does not depend on that
  proof's admitted shapes.
- **Analysis:** The load's statement precedes the free on every path through
  the block, and a write after the statement that holds the free runs only
  after that free, which is reached again only through the load's statement.
  The free therefore sees the loaded value, which without ownership is
  attached or has no identity, so lowering rejects it: a program with a
  recorded free is invalid either way, and no valid program's facts or code
  change (D297-D302). Java forbids redeclaring a local in its scope, so the
  freed name is that local.
- **Boundary:** An assignment inside an expression, such as `if ((old =
  values) != null)` (D304 recognizes one its statement always evaluates), a
  cast (D305 recognizes it) or other expression around the load, another
  write between the load and the free (D306 recognizes one that writes a
  field load), and a free that no route is proven to reach keep D281's
  report. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside a reclamation through a local
  assigned from the field, through an assigned parameter, and through a
  declared local set to `null` after its free; each safe twin compiles, and
  through a field that escapes to a sibling field lowering rejects each of
  those frees. Another write between the assignment and the free keeps
  D281's report. D281's free route and D296's paired test now use a store
  that assigns the field inside a condition. On the D302 analysis the
  assigned case reports at the reclamations, while the relocated D281 and
  D296 checks pass. The focused field, wrapper, owner, pool and explanation
  tests pass, 69 examples emit the same `-O3` LLVM, the standard library
  builds and every port source compiles without diagnostics.

## D304 - Recognize a field's reclamation through an assignment in an expression

- **Status:** Accepted and implemented. Amends D303 and supersedes its
  boundary that an assignment inside an expression keeps D281's report.
- **Context:** D303 counted only a block statement that is itself the
  assignment `old = values;`. The common form `if ((old = values) != null) {
  values = null; free old; }`, or a load assigned in an initializer such as
  `int size = (old = values).length;`, still reported a free that may run
  code during a loan at its reclamation instead of at that free.
- **Decision:** A plain `=` assignment of the field through `this` to a local
  or parameter is a field load when a block statement always evaluates it
  first: in an expression statement, a declaration's initializer, an `if` or
  `while` condition, a `for` condition, an enhanced-for iterable or a switch
  selector, reached through operands Java always evaluates (not the right of
  `&&` or `||`, a conditional's branches, a switch expression's arms or an
  anonymous class body). A free counts when it follows the assignment in
  that statement's nested statements or in a later statement of the block,
  where lowering is certain to lower it (D302), with no write to the local
  between the assignment and the end of the block statement that holds the
  free (D303). D303's statement form is the simplest case.
- **Analysis:** Java evaluates operands left to right, so source order is
  evaluation order. Such an assignment runs whenever control passes its
  statement and before the statement's nested statements: a condition or
  selector runs before its branches and arms, and a `while` or `for`
  condition before each iteration of the body and before the loop ends. A
  do-while condition runs after its body, so it is not searched. The free
  therefore sees the loaded value, which lowering rejects unless the field is
  owned: a program with a recorded free is invalid either way, and no valid
  program's facts or code change (D297-D303).
- **Boundary:** An assignment that may not run, such as one on the right of
  `&&`, in a conditional's branch or in a do-while condition, a cast (D305
  recognizes it) or other expression around the load, another write between
  the load and the free (D306 recognizes one that writes a field load), and a
  free that no route is proven to reach keep D281's report. Supersedes no
  other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside reclamations that assign the
  field in an `if` condition and free in the branch or after the `if`, in a
  declaration's initializer, and in a switch selector; each safe twin
  compiles, and through a field that escapes to a sibling field lowering
  rejects each of those frees. An assignment on the right of `&&` keeps
  D281's report. D281's free route and D296's paired test now use a store
  that declares the local from a cast of the field. On the D303 analysis the
  expression cases report at the reclamations, while the relocated D281 and
  D296 checks pass. The focused field, wrapper, owner, pool and explanation
  tests pass, 69 examples emit the same `-O3` LLVM, the standard library
  builds and every port source compiles without diagnostics.

## D305 - Recognize a field's reclamation through a cast load

- **Status:** Accepted and implemented. Amends D304 and supersedes its
  boundary that a cast around the load keeps D281's report.
- **Context:** A class that reclaims with `int[] old = (int[]) values;
  values = null; free old;`, or loads the field into an `Object` local
  through a cast, still reported a free that may run code during a loan at
  its reclamation instead of at that free.
- **Decision:** A field load through `this` may sit under any number of
  casts, in a declaration and in an assignment that D303 or D304 counts.
- **Analysis:** A reference cast creates no object; a failed checked cast
  throws, so the free is not reached on that path. Lowering gives an
  assignable or checked reference cast's result the operand's allocation
  identity, and an invalid cast's result none, with an error. Without
  ownership the freed value is still attached or has no identity, so lowering
  rejects the free: a program with a recorded free is invalid either way, and
  no valid program's facts or code change (D297-D304).
- **Boundary:** Another expression around the load, such as a conditional or
  a copy through another local, an assignment that may not run, another
  write between the load and the free, and a free that no route is proven to
  reach keep D281's report (D306 recognizes copies, conditionals of loads and
  writes that write a field load). Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside reclamations through
  `(int[]) values`, through `(Object) this.values` into an `Object` local,
  and through `(old = (int[]) values)` in a condition; each safe twin
  compiles, and through a field that escapes to a sibling field lowering
  rejects each of those frees. D281's free route and D296's paired test now
  use a store that writes the local again between the load and the free. On
  the D304 analysis the cast cases report at the reclamations, while the
  relocated D281 and D296 checks pass. The focused field, wrapper, owner,
  pool and explanation tests pass, 69 examples emit the same `-O3` LLVM, the
  standard library builds and every port source compiles without
  diagnostics.

## D306 - Recognize a field's reclamation across writes that keep a field load

- **Status:** Accepted and implemented. Amends D303-D305: supersedes their
  boundaries that a write between the load and the free, a copy through
  another local and a conditional around the load keep D281's report, and
  replaces D303's rule of no write between the load and the free.
- **Context:** The witness rejected any write to the local between its load
  and its free. A reclamation that reloads the field in a branch, `int[] old
  = values; if (grown) { old = values; } values = null; free old;`, or
  writes `old = flag ? values : old`, still reported a free that may run code
  during a loan at the reclamation instead of at that free, although the
  local holds a load of the field on every path.
- **Decision:** A local holds a field load where it is read or freed when a
  load of it that dominates the point, as in D303 and D304, writes a holding
  value and every write to the local after that load and up to the end of
  the block statement that holds the point, or a deferred free's block,
  writes one too. A holding value is a load of the field through `this`,
  possibly under casts (D305), a local that holds a field load where the
  value reads it, a conditional whose branches both hold one, or an
  assignment of one. The probe takes the greatest set of such points and
  counts a free of a local that holds a field load there, where lowering is
  certain to lower the free (D302).
- **Analysis:** Execution keeps every point in that set true: a read sees the
  dominating load or a later write in the window, and that write's value is
  read before it runs, so by induction over execution the value is a field
  load; a write such as `old = old` is read inside its own window and keeps
  the property. The freed value is therefore a load of the field, possibly
  merged with others at joins, which lowering without ownership gives no
  identity, an attached one or a merge of those, so it rejects the free: a
  program with a recorded free is invalid either way, and no valid
  program's facts or code change (D297-D305). A write of any other value,
  such as `old = new int[2]`, excludes the free, so the program keeps its
  D281 behavior.
- **Boundary:** A write of a value that is not a field load, an assignment
  that may not run, such as one in a switch expression's arm (D307 recognizes
  one every path to the free runs), and a free that no route is proven to
  reach keep D281's report. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside reclamations that write `old =
  old`, reload the field in a branch, or write `old = flag ? values : old`
  between the load and the free; each safe twin compiles, and through a
  field that escapes to a sibling field lowering rejects each of those frees.
  A write of a new array between them leaves only the store's D281 report,
  and another write of a parameter keeps D281's report at both reclamations.
  D281's free route and D296's paired test now use a store that loads the
  field in a switch expression's arm. The D302 differential of deferred and
  finally shapes against lowering still agrees in every counted case. On the
  D305 analysis the rewritten cases report at the reclamations, while the
  relocated D281 and D296 checks pass. The focused field, wrapper, owner,
  pool and explanation tests pass, 69 examples emit the same `-O3` LLVM, the
  standard library builds and every port source compiles without
  diagnostics.

## D307 - Recognize a field's reclamation by a must-analysis of held field loads

- **Status:** Accepted and implemented. Amends D297-D306: supersedes D306's
  boundary that an assignment that may not run keeps D281's report, and
  replaces the witness probes of D297-D306 (destructor frees of the field,
  dominating loads, write windows and the holding fixed point) with one
  analysis.
- **Context:** D304 and D306 counted a load only where a block statement
  always evaluates it. A reclamation that loads the field where a condition
  or switch decides, as in `if (flag && (old = values) != null) { values =
  null; free old; }` or a switch expression's arm, still reported a free that
  may run code during a loan at its reclamation, although every path to the
  free runs the load. Each new form needed another syntactic rule.
- **Decision:** `HeldFieldLoads` runs a forward must-analysis over each
  instance function and instance initializer of the owner: the set of locals
  that hold a load of the field through `this` on every path lowering takes
  to a point. A value holds one when it is such a load, possibly under casts,
  a local that holds one, a conditional whose reachable branches both hold
  one, a switch expression whose arms all yield one, or an assignment of one;
  any other write, and a pattern binding, holds none. Conditions split the
  state: the right operand of `&&` or `||` runs only when its left operand
  selects it, so code the whole condition guards sees its writes. Loops
  iterate to a fixed point and count frees only on its pass; break, continue
  and yield carry their states to their targets less what finally blocks on
  the way may write; a catch handler, and a finally block's check, start
  from the meet of every state the try statement passed through; only a
  literal `true` loop condition ends a path, as in lowering; and code the
  analysis does not know writes every local. The field's ownership is needed
  when a free of a value that holds a field load stands where lowering is
  certain to lower it (D302), or a deferred free of such a local has a route
  out of its block with no write to it there. This covers D297's destructor
  free of the field and every D298-D306 form.
- **Analysis:** Every path the analysis follows is one lowering follows, so
  where the analysis says a local holds a field load, lowering's value for it
  includes such a load on some incoming path, even where lowering merges the
  paths of a condition that the analysis keeps apart. Without ownership a
  field load is not freeable, and lowering's mandatory safety rejects a free
  of any value that may be one, so every recorded free is rejected: a program
  with one is invalid either way, and no valid program's facts or code change
  (D297-D306). A pattern binding named like the field no longer passes for a
  load of the field, which the D306 probe assumed.
- **Boundary:** A write of a value that is not a field load, an assignment a
  path to the free may skip, a pattern binding of the field (D308 recognizes
  it), and a free that no route is proven to reach keep D281's report.
  Supersedes no other decision.
- **Verification:** Every D297-D306 witness and boundary test passes on the
  new analysis. A free that runs a destructor during a loan is now rejected
  at the free beside a reclamation that loads the field in a switch
  expression's arm, whose safe twin compiles, and beside one guarded by
  `flag && (old = values) != null`; through a field that escapes to a
  sibling field lowering rejects both frees. A free the skipped assignment
  may reach keeps D281's report. D281's free route and D296's paired test now
  use a store that frees a pattern binding of the field. A 20-case local
  differential of catch, finally, labeled break, loop, switch fallthrough,
  `||` and pattern-binding shapes, and the D302 route differential, found no
  counted free that lowering accepts without ownership. On the D306 analysis
  the arm case reports at the reclamations, while the relocated D281 and
  D296 checks pass. The focused field, wrapper, owner, pool and explanation
  tests pass, 69 examples emit the same `-O3` LLVM, the standard library
  builds and every port source compiles without diagnostics.

## D308 - Recognize a field's reclamation through a pattern binding

- **Status:** Accepted and implemented. Amends D307 and supersedes its
  boundary that a pattern binding of the field keeps D281's report.
- **Context:** D307 gave pattern bindings no field load. A reclamation such as
  `if (values instanceof int[] old) { values = null; free old; }`, or one
  that returns when `!(this.values instanceof int[] old)` and frees `old`
  afterwards, still reported a free that may run code during a loan at its
  reclamation instead of at that free.
- **Decision:** A type test with a binding splits the state: where the test is
  true the binding holds a field load when its operand does, and where it is
  false the binding holds none. Outside a condition the binding holds none.
  Writes to a binding are tracked like writes to any local, and a binding is
  a local wherever its name is read; one named like the field makes later
  reads of the field count as reads of the binding, which hold a load only if
  the binding did.
- **Analysis:** A matching type test binds its operand's value, as a checked
  cast of it, and lowering gives the binding the operand's allocation
  identity or none (D305), so where the analysis says the binding holds a
  field load, lowering's value includes one, and without ownership it rejects
  the free: a program with a recorded free is invalid either way, and no
  valid program's facts or code change (D297-D307).
- **Boundary:** A binding of a value that is not a field load, a write of
  such a value, an assignment a path to the free may skip, and a free that no
  route is proven to reach keep D281's report, as does a free in a catch
  handler of a load that the try body's entry state lacks (D309 recognizes
  one after the entry's null literal). Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside `if (values instanceof int[] old)
  { values = null; free old; }` and beside a reclamation that returns when
  `!(this.values instanceof int[] old)`; each safe twin compiles, and through
  a field that escapes to a sibling field lowering rejects both frees. A
  binding of a parameter keeps D281's report. D281's free route and D296's
  paired test now use a store that frees, in a catch handler, a local loaded
  in the try body. The D307 dataflow differential, now with seven binding
  shapes, and the D302 route differential found no counted free that
  lowering accepts without ownership. On the D307 analysis the pattern cases
  report at the reclamations, while the relocated D281 and D296 checks pass.
  The focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D309 - Start a catch handler from the states where lowering may throw

- **Status:** Accepted and implemented. Amends D307 and D308: replaces their
  rule that a catch handler starts from the meet of every state its try
  statement passed through.
- **Context:** A reclamation that loads the field in a try body before an
  operation that may throw and frees it in the handler, as in `int[] old =
  null; try { old = values; old[1] = 0; } catch (RuntimeException failure) {
  values = null; free old; }`, still reported a free that may run code during
  a loan at its reclamation: the handler's state included the try body's
  entry, where `old` is null.
- **Decision:** A try region also records the states where lowering may add
  an exception edge: after the operands of a call, a construction, an array
  access or creation, a cast, an arithmetic, comparison or string operator,
  a compound assignment or update, a store that is not to a field of
  `this`, a read of another receiver's field or of a name that may be a
  static field, a switch selector, a throw, a free, each enhanced-for step,
  and the completion of and transfers out of a block with pending deferred
  actions. A catch handler starts from a local holding a field load when the
  local holds one at every such state and, at the try body's entry, holds
  one or is the null literal, which the analysis now tracks; with no such
  state it starts from the entry. A finally block's check still starts from
  every state the try statement passed through.
- **Analysis:** Lowering adds exception edges only for calls and throws,
  including the bundled exceptions of runtime checks, all of which arise at
  recorded states, and starts a handler from its edges' states, or, when no
  edge reaches it, from the try body's entry state. On an edge the local
  holds a field load; from the entry it holds one or is null, and lowering
  rejects freeing either without ownership, so a recorded free is rejected
  and no valid program's facts or code change (D297-D308). A handler that no
  edge reaches with a freeable value at the entry, such as a new array, is
  why the entry still counts: lowering accepts that free.
- **Boundary:** A free in a catch handler of a local that holds neither a
  field load nor the null literal at the try body's entry, a write of a value
  that is not a field load, an assignment a path to the free may skip (D310
  recognizes it), and a free that no route is proven to reach keep D281's
  report. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside the reclamation above, whose safe
  twin compiles, and through a field that escapes to a sibling field
  lowering rejects that free. D281's free route and D296's paired test now
  use a store whose handler frees a local that holds a parameter at the try
  body's entry. The D307 dataflow differential, now with seven catch shapes,
  found no counted free that lowering accepts without ownership, and showed
  lowering accepting the free of a new array from the entry in a handler no
  edge reaches, which the analysis does not count; the D302 route
  differential still agrees. On the D308 analysis the catch case reports at
  the reclamations, while the relocated D281 and D296 checks pass. The
  focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D310 - Recognize a free that a skipped load may reach

- **Status:** Accepted and implemented. Amends D307-D309: supersedes their
  boundary that a free an assignment's skipped path may reach keeps D281's
  report, and generalizes D309's null-literal rule for catch handlers.
- **Context:** The analysis counted a free only when every path gave the
  freed local a field load. `int[] old = null; if (flag && (old = values) !=
  null) { values = null; } free old;`, or a load in one branch of an `if`
  after `old = new int[1]`, still reported a free that may run code during a
  loan at the reclamation instead of at that free.
- **Decision:** The analysis tracks, beside a field load on every path,
  whether some path gives a local a field load, whether every path gives it
  a field load or the null literal, and whether every path gives it the null
  literal; merges keep the first only where some path has it and the others
  only where every path has them. A free counts when its value may be a
  field load, or is a field load or the null literal on every path without
  always being null. Try regions, the finally block's check and a catch
  handler's entry keep only what holds at every one of their states,
  including whether some path gives a load, so D309's rule becomes the
  handler entry keeping a load or null literal that both its entry and its
  exception edges carry. A free of a local that is always null, or never
  holds a load, does not count.
- **Analysis:** Every path the analysis follows is one lowering follows, so
  where some path gives a field load, lowering's value at the free includes
  that load, and lowering's mandatory safety rejects freeing a value that may
  be a field's live storage without ownership; a sibling-escaped field shows
  it rejecting a merge of a load with a new array or with null. Where every
  path gives a load or null, no path gives a known allocation. A catch
  handler that no edge reaches starts from the try body's entry instead of
  the edges, so the handler entry keeps only properties both sources have.
  A program with a recorded free is invalid either way, and no valid
  program's facts or code change (D297-D309). With the field owned, lowering
  cannot prove such a merged free either, so these programs are invalid
  regardless, and only where the error is reported changes.
- **Boundary:** A write of a value that is not a field load on every path to
  the free, a free in a catch handler of a local that neither the try body's
  entry nor every exception edge gives a load or null (D311 drops the entry
  when an edge is certain), and a free that no route is proven to reach keep
  D281's report. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free beside a reclamation whose `&&` may skip the load after `old =
  null`, and beside one whose branch may skip it after `old = new int[1]`;
  through a field that escapes to a sibling field lowering rejects both
  frees. A free of a local that is always null, or only ever holds new
  arrays, keeps D281's report. The D307 dataflow differential, now 39 shapes
  with skipped loads and their copies, found no counted free that lowering
  accepts without ownership, and the D302 route differential still agrees.
  On the D309 analysis the skipped-load case reports at the reclamations,
  while the D281 and D296 checks pass. The focused field, wrapper, owner,
  pool and explanation tests pass, 69 examples emit the same `-O3` LLVM, the
  standard library builds and every port source compiles without
  diagnostics.

## D311 - Start a catch handler from its edges when one is certain

- **Status:** Accepted and implemented. Amends D309 and D310: supersedes their
  boundary that a catch handler's free keeps D281's report when the try
  body's entry gives the local neither a field load nor the null literal.
- **Context:** A handler started from what holds at the try body's entry as
  well as at its exception edges, because lowering analyzes a handler that no
  edge reaches from that entry. `int[] old = spare; try { old = values;
  old[1] = 0; } catch (RuntimeException failure) { values = null; free old;
  }` therefore still reported a free that may run code during a loan at the
  reclamation, although the array store gives lowering an edge.
- **Decision:** A try body is live when, at a reachable point outside every
  inner try statement and every finally block lowering may never reach, it
  reaches a throw statement or an array element access. A live body's
  handler starts from what holds at its recorded exception edges alone.
- **Analysis:** Lowering lowers a throw statement with an exception edge to the
  innermost region, and every array element access, read or written, with a
  null check and a bounds check whose failure path constructs and throws a
  bundled exception. Inside the try body, outside inner try statements, that
  region is the try statement's own or a deferred action's, which rethrows to
  it, so lowering starts the handler from its edges, all at recorded states.
  Calls, other runtime checks and null checks that lowering may elide are
  not counted as certain. A program with a recorded free is invalid either
  way, and no valid program's facts or code change (D297-D310).
- **Boundary:** A handler whose try body has only exception edges this
  analysis does not count as certain, such as a call, an elidable null check
  or an arithmetic check, still starts from the entry too, and a write of a
  value that is not a field load and a free that no route is proven to reach
  keep D281's report. Supersedes no other decision.
- **Verification:** A free that runs a destructor during a loan is rejected
  at the free, with the field owned, beside reclamations whose handler frees
  a local that holds a parameter at the try's entry when the body reaches an
  array store or a throw statement; each safe twin compiles, and through a
  field that escapes to a sibling field lowering rejects both frees. D281's
  free route and D296's paired test now use a store whose try body can throw
  only through `old.length`. The D307 dataflow differential, now 44 shapes
  with a new array at the entry beside certain edges, an earlier possible
  throw, an edge inside a nested try and an elidable null check, found no
  counted free that lowering accepts without ownership, and the D302 route
  differential still agrees. On the D310 analysis the live catch cases
  report at the reclamations, while the relocated D281 and D296 checks pass.
  The focused field, wrapper, owner, pool and explanation tests pass, 69
  examples emit the same `-O3` LLVM, the standard library builds and every
  port source compiles without diagnostics.

## D312 - Keep compressed evidence outside the repository

- **Status:** Accepted and implemented. Supersedes no other decision.
- **Context:** The before-self-hosting milestones committed 899 compressed
  evidence files under `docs/self-hosting/`: capture and measurement
  archives, inventory and reconciliation data and run logs, about 150 MiB,
  one archive over GitHub's recommended 50 MB. Every clone downloaded them,
  and text audits could not see inside them.
- **Decision:** Compressed files stay out of `docs/`, and no tracked file
  exceeds 5 MiB. Evidence is committed as manifests, hashes and summaries;
  the files themselves are kept elsewhere. The 899 files were removed from
  the history of the 157 commits of that work, which renamed 156 of them. Documents and manifests keep citing the original identifiers, so
  their recorded file hashes stay valid, and
  `docs/self-hosting/COMMIT_MAP.txt` maps each original commit to its new one.
- **Analysis:** Only the ownership pilot test read the evidence, unpacking
  three archives to compare 47 native kernel results with retained J0
  results. The M0 manifests record the SHA-256 of every archived file, so
  the test compares result hashes instead; no compiler or language behavior
  changes.
- **Boundary:** The `scripts/self-hosting/` qualification scripts and some
  `run-evidence.sh` scripts still read the removed files and need them
  restored before they can run again.
- **Verification:** Each rewritten commit keeps its author, committer, dates
  and parents and differs from the original only by the removed files, with
  15 commit messages differing only in the commit identifiers they cite.
  `scripts/check-tracked-files.sh` passes on the new tree and rejects the
  original one, and the ownership pilot artifacts test passes without the
  archives.

## D313 - Use neutral account and host names in committed records

- **Status:** Accepted and implemented. Amends D312: supersedes its statement
  that the documents and manifests keep their recorded file hashes valid,
  for the files this decision edits.
- **Context:** Evidence records and Java Bridge documents carried the local
  account name in about 1,900 absolute paths and the private names of the
  Linux x86-64 host and the Linux arm64 VM in about 280 places.
- **Decision:** Committed records use the neutral account name `developer` in
  absolute paths and name hosts by role: `x86host` for the physical Linux
  x86-64 host and `armvm` for the Linux arm64 VM. The 111 affected files were
  edited at the tip; history keeps the earlier text.
- **Analysis:** The SHA-256 values that manifests and identity snapshots
  record for the edited files describe their earlier content. Identity
  snapshots already describe the tree at their freeze commit, and the
  removed archives' manifests describe archived copies, so neither is
  rewritten. Scripts that read paths from these records need them adjusted
  to the local machine; no compiler, library or test behavior changes.
- **Boundary:** Released packages that ship `JAVA_BRIDGE_JDK_IDENTITIES.json`
  or `JAVA_BRIDGE_JDK_PROGRESS.md` keep the earlier text until a new release.
- **Verification:** No tracked file contains the earlier account name or
  host names, even as substrings; every edited JSON file parses, every
  edited shell script passes `bash -n`, and each replacement keeps the
  file's length.
