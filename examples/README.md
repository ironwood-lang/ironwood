# Ironwood examples

Every example follows the same Ironwood project layout. Single-program examples
have `run.sh`; a multi-program example may number its run scripts:

```text
example/
  compile.sh
  link.sh
  run*.sh
  src/main/ironwood/org/ironwood/<example>/*.iron
  target/
```

`compile.sh` writes package-structured `.ironclass` files under
`target/classes`. `link.sh` writes the native executable under `target`.
Each run script executes one program and reports its exit status. Every script
prints the command it runs.

For example:

```console
$ cd examples/basic
$ ./compile.sh
+ ironwoodc --source-path src/main/ironwood -d target/classes src/main/ironwood/org/ironwood/basic/Main.iron
built .../examples/basic/target/classes/org/ironwood/basic/Main.ironclass
$ ./link.sh
+ ironwoodc --link -cp target/classes --main-class org.ironwood.basic.Main -o target/Main -O3
built .../examples/basic/target/Main
$ ./run.sh
+ ./target/Main
exit status: 42
```

With `ironwoodc` on `PATH`, run every example from the repository root:

```console
$ ./examples/test-all.sh
...
TOTAL: 65 passed, 0 failed, 65 total
PASS: all 65 examples passed
```

The runner discovers immediate example directories containing `compile.sh` and
executes them sequentially in directory-name order. It runs each example's
compile, link, and run checks, including all 21 ownership scenarios and the four
`trycatchfinallyexception` cases. Expected compiler rejections and native exit
statuses are checked by the individual scripts. Existing warning settings are
preserved; warnings alone do not fail an example.

A failed compile skips that example's link and run steps; a failed link skips
its run steps. Other examples still run, and all four independent exception
run scripts are attempted after a successful link. The final summary lists
every example's result, failed script names and exit statuses, and totals.
The runner exits with status `0` when all pass, or `1` if any example fails
or no examples are found. The focused `test-object-model.sh` runner below
continues to cover its 30-example catalog and stops at the first failure.

The example projects and outputs are:

Larger command-line applications are kept in the separate
[`projects`](../projects/README.md) tree. The examples below remain focused
feature demonstrations.

| Example | Package | Executable | Exit status |
| --- | --- | --- | ---: |
| `basic` | `org.ironwood.basic` | `target/Main` | 42 |
| `hello` | `org.ironwood.hello` | `target/HelloWorld` | 0 |
| `controlflow` | `org.ironwood.controlflow` | `target/MethodsAndControlFlow` | 30 |
| `objects` | `org.ironwood.objects` | `target/Objects` | 30 |
| `inheritance` | `org.ironwood.inheritance` | `target/InheritanceAndInterfaces` | 42 |
| `exceptions` | `org.ironwood.exceptions` | `target/Exceptions` | 42 |
| `checkedexceptions` | `org.ironwood.checkedexceptions` | `target/CheckedExceptions` | 42 |
| `resources` | `org.ironwood.resources` | `target/DeterministicResources` | 42 |
| `trycatchfinallyexception` | `org.ironwood.trycatchfinallyexception` | four `target/Case*` executables | 42 for Case 1; expected 1 for Cases 2–4 |
| `reclamation` | `org.ironwood.reclamation` | `target/ExplicitReclamation` | 42 (destructor and live-count checks) |
| `textreclamation` | `org.ironwood.textreclamation` | `target/TextReclamation` | 42 (caller-owned text-result checks) |
| `echo` | `org.ironwood.echo` | `target/Echo` | 0 for valid input; 64 for usage/parse errors |
| `ownedhelperborrows` | `org.ironwood.ownedhelperborrows` | 11 valid executables plus 10 compile-error cases | 42 or expected compiler rejection |
| `foundations` | `org.ironwood.foundations` | `target/ArraysStringsAndIo` | 42 |
| `collections` | `org.ironwood.collections` | `target/ReusableCollections` | 42 |
| `arguments` | `org.ironwood.arguments` | `target/CommandLineArguments` | argument count |
| `stacktraces` | `org.ironwood.stacktraces` | `target/StackTraces` | 1 (expected and verified) |
| `staticinitialization` | `org.ironwood.staticinitialization` | `target/StaticInitialization` | 42 |
| `classicswitch` | `org.ironwood.classicswitch` | `target/ClassicSwitch` | 42 |
| `enums` | `org.ironwood.enums` | `target/Enums` | 42 |
| `multidimensionalarrays` | `org.ironwood.multidimensionalarrays` | `target/MultidimensionalArrays` | 42 |
| `stringconcatenation` | `org.ironwood.stringconcatenation` | `target/StringConcatenation` | 42 |
| `primitivegenerics` | `org.ironwood.primitivegenerics` | `target/PrimitiveGenerics` | 42 |
| `overridedirective` | `org.ironwood.overridedirective` | `target/OverrideDirective` | 42 |
| `textblocks` | `org.ironwood.textblocks` | `target/TextBlocks` | 42 |
| `runtimefailures` | `org.ironwood.runtimefailures` | `target/CatchableRuntimeFailures` | 42 |
| `allocationfailure` | `org.ironwood.allocationfailure` | `target/CatchableAllocationFailure` | 42 |
| `instanceofpatterns` | `org.ironwood.instanceofpatterns` | `target/InstanceOfPatterns` | 42 |
| `statements` | `org.ironwood.statements` | `target/RemainingStatements` | 42 |
| `multicatch` | `org.ironwood.multicatch` | `target/MultiCatchAndPreciseRethrow` | 42 |
| `arrayinitializers` | `org.ironwood.arrayinitializers` | `target/ArrayInitializers` | 42 |
| `binaryliterals` | `org.ironwood.binaryliterals` | `target/BinaryIntegerLiterals` | 42 |
| `staticimports` | `org.ironwood.staticimports` | `target/StaticImports` | 42 |
| `modernswitch` | `org.ironwood.modernswitch` | `target/ModernSwitch` | 42 |
| `filetree` | `org.ironwood.filetree` | `target/FileTreeFind` | 42 when at least one suffix match is found |

The `allocationfailure` run script sets the runtime-private
`IRONWOOD_ALLOCATION_LIMIT=0` diagnostic boundary so its first source
allocation fails deterministically. Ordinary programs do not need this
variable.

The file-tree example uses `Files.walkFileTree` and `SimpleFileVisitor<Path>`
to print regular files with a requested suffix. Its callback borrows each path
and attribute object, while the traversal closes directory handles and reclaims
temporary path and attribute values after the callback returns.

## Java-shaped object-model examples

The completed source-level object model has one focused example project per
feature slice. Each one follows the same `compile.sh`, `link.sh`, and `run.sh`
workflow above and exits with status `42` when its checks pass. The complete
feature explanation and source links are in
[`docs/OBJECT_MODEL.md`](../docs/OBJECT_MODEL.md#hands-on-example-catalog).

| Example | Main class | Primary feature |
| --- | --- | --- |
| `abstractclasses` | `AbstractClasses` | Abstract classes, methods, obligations, and base-reference dispatch |
| `finality` | `Finality` | Final classes and final methods |
| `initialization` | `Initialization` | Field initializers, initializer blocks, constructor order, and `this(...)` |
| `finalbindings` | `FinalBindings` | Blank-final fields and final parameters, locals, and catch variables |
| `fieldhiding` | `FieldHiding` | Owner-qualified field storage and static receiver-type selection |
| `superaccess` | `SuperAccess` | `super(...)`, `super.field`, and `super.method(...)` |
| `qualifiedsuper` | `QualifiedSuper` | `outer.super(...)` construction for a subclass of an inner class |
| `staticnested` | `StaticNestedTypes` | Static nested classes |
| `innerclasses` | `InnerClasses` | Inner classes, qualified construction, and `Outer.this` |
| `nestedinterfaces` | `NestedInterfaces` | Interfaces nested in classes and interfaces |
| `localclasses` | `LocalClasses` | Block-scoped local classes |
| `anonymousclasses` | `AnonymousClasses` | Anonymous class extension and interface implementation |
| `qualifiedanonymous` | `QualifiedAnonymous` | Exact generic owner binding for qualified anonymous construction |
| `anonymousenclosing` | `AnonymousEnclosing` | Evaluation order and distinct lexical/superclass enclosing instances |
| `anonymousdiamond` | `AnonymousDiamond` | Diamond inference for anonymous classes |
| `lexicalcapture` | `LexicalCapture` | Explicit-final and effectively-final lexical capture |
| `nestaccess` | `NestAccess` | Private access across one source nest |
| `lexicalmembertypes` | `LexicalMemberTypes` | Member classes and interfaces inside local and anonymous classes |
| `capturedaliases` | `CapturedAliases` | Hidden capture aliases and safe explicit `free` |
| `interfacemembers` | `InterfaceMembers` | Interface constants plus abstract, default, static, and private methods |
| `interfacedefaults` | `InterfaceDefaults` | Class-wins, most-specific defaults, re-abstraction, and explicit interface-super |
| `boundedgenerics` | `BoundedGenerics` | Upper, intersection, dependent, and recursive bounds |
| `genericcallables` | `GenericCallables` | Generic methods, constructors, overrides, and dispatch |
| `wildcardcapture` | `WildcardCapture` | `?`, `? extends`, `? super`, and argument capture conversion |
| `genericinference` | `GenericInference` | Nested/expected inference and overload specificity |
| `diamond` | `DiamondInference` | Diamond with dependent, intersection, and constructor bounds |
| `nestedgenerics` | `NestedGenerics` | Exact generic owner/member types and static generic boundaries |
| `innerdiamond` | `InnerDiamond` | Diamond and generic constructors on inner/anonymous-inner classes |
| `genericcasts` | `GenericCasts` | Source-provable generic narrowing and runtime cast failure |
| `throwabletypes` | `ThrowableTypes` | Throwable bounds, reifiable catches, and nested exception rules |

Run the full object-model example catalog from the repository root:

```console
$ ./examples/test-object-model.sh
PASS: 30 object-model examples
```

The exception example combines a cross-frame throw, superclass catch matching,
return-through-`finally`, and normal `finally` completion. All examples link with
`-O3`. The reclamation example repeatedly allocates an object with owned array
storage, explicitly frees it, observes the destructor count, and verifies that
`System.liveAllocationCount()` returns to its baseline. All example `target`
directories are ignored by Git.
The text-reclamation example slices UTF-16 input, exports a fresh `char[]`,
builds and snapshots decorated text, frees every caller-owned result and the
builder, and verifies that `System.liveAllocationCount()` returns to baseline.
The echo example validates `String[]` arguments, parses a decimal integer,
prints String/integer/boolean values through U1 overloads, and sends usage or
parse diagnostics to `System.err`. Its run script checks both the successful
summary and the status-64 usage path.
The hello example is the minimal Java-shaped output program and prints exactly
`Hello World!` through `System.out.println(String)`.
The foundations example combines primitive and reference arrays, pooled UTF-16
strings, implicit `ironwood.lang.String` and `System`, standard output, array
container reclamation, and native execution.
The collections example combines a reusable object pool, generic list,
primitive set, and heap buffer-key map from the bundled standard library.
The arguments example receives the native process arguments as `String[]`,
prints each value, and returns the argument count as its native exit status.
Its run script verifies spaces and supplementary Unicode.
The checked-exception example combines method, interface, and constructor
declarations, cross-method propagation, and a reachable catch; it exits with
status `42`.
The resource example closes `AutoCloseable` objects explicitly in ordinary
`finally`, preserves a body failure as primary when `close()` also fails, and
keeps closing separate from compiler-checked wrapper reclamation.
The stack-trace example throws a message-bearing primary exception through
several methods and then raises a secondary cleanup exception. Its run script
expects status `1` and compares the full source-level diagnostic, including
callable names, `StackTraces.iron` line numbers, messages, and exception order.
The static-initialization example demonstrates superclass and default-method
interface prerequisites followed by runtime field initialization and a static
initializer block in exact source order before `main`; its script verifies both
the printed order and status `42`.
The classic-switch example demonstrates evaluated-once integral selection,
consecutive labels, explicit fallthrough, `default` in source order, and a
static-final case constant; its script verifies the output and status `42`.
The enum example demonstrates constructor arguments, final fields,
constant-specific fields, initialization and interface implementations, stable
identity/name/ordinal operations, allocation-free ordered lookup, and enum
`switch`; its script verifies the output and status `42`.
The multidimensional-array example demonstrates recursive invariant types,
exact reifiable descriptor tests and a checked cast, and separately owned child
arrays that are detached and reclaimed explicitly; its script verifies the
output and status `42`.
The primitive-generics example uses unbounded generic parameters with native
`int` and `double` arguments, interface dispatch without boxing, an unchanged
allocation counter across generic value calls, and compiler-checked reclamation
of a specialized generic object; its script verifies the output and status `42`.
The override-directive example uses mandatory `@Override` on an interface
refinement, an abstract implementation, and concrete class overrides, including
same-line, next-line, and interleaved modifier placement; its script verifies
the output and status `42`.
The text-block example covers cooked and escape-free raw blocks, leading and
trailing newline control, pooling against an ordinary literal, non-interpolation,
and comment markers as content; its script verifies the output and status `42`.
The runtime-failures example catches null field use, an out-of-bounds array
store, negative array allocation, and `throw null`; its script verifies the
output and status `42`.
The remaining-statements example combines `do`/`while`, enhanced `for` over an
array and a producer-owned reusable `Iterator`, empty and labeled statements,
and labeled or unlabeled loop transfers through `finally`. It verifies
evaluated-once traversal, exact cleanup counts, allocation-neutral iteration,
and status `42`.
The multi-catch example combines disjoint union alternatives, capture of the
implicitly-final binding by a local class, precise rethrow from an
effectively-final broad catch, and first-exception preservation when `finally`
adds a secondary cleanup failure. It exits with status `42`.
The array-initializer example combines contextual declaration and
array-creation forms, empty and recursive nested braces, exact inferred lengths,
source-ordered calls and stores, reference conversion, and explicit detachment
and reclamation of every nested child array. It verifies the exact allocation
count and exits with status `42`.
The binary-literal example combines lowercase and uppercase binary prefixes,
digit separators, `L` suffix typing, full-width signed bit patterns, static
constant folding, and a classic-switch label. It also verifies that a leading
zero remains decimal and exits with status `42`.
The static-import example combines single-member and on-demand imports for a
constant, mutable field, overloaded and generic methods, and a member type. It
verifies single-import shadowing, a constant switch label, declaring-type
initialization, exact output, and status `42`.
The `trycatchfinallyexception` example uses one compile script, one link script,
and `run1.sh` through `run4.sh` to verify all four protected-body/`finally`
completion combinations. Cases 2–4 rethrow their verified exception so the run
scripts can compare the complete uncaught source trace; Case 4 includes the
secondary cleanup exception's message and trace.
The `ownedhelperborrows` example gives every dependent-borrow outcome an
isolated, commented program. Its compile script builds all valid programs and
checks every intentional compiler rejection, its link script produces each
valid native executable, and its 21 numbered compile scripts plus 21 numbered
run scripts verify one scenario apiece.
