# Compiler architecture

## Launcher JVM configuration

The `ironwoodc`, `ironjar`, and `irondoc` launchers share the optional
installation-local `conf/jvm.options` file (D138). They pass its literal,
line-separated JVM arguments before the tool entry point. The IDK bundles the
commented configuration and the shared launcher helper, while source checkouts
use the same convention. These options do not change LLVM compilation or the
native runtime. See the [configuration template](../conf/jvm.options) for syntax
and examples.

## Current pipeline

```text
explicit UTF-8 sources
  -> lexer and recursive-descent parser (package/import-aware AST)
  -> referenced-source discovery through source path and classpath
  -> complete parsed compilation set
  -> top-level/member enum/class/interface plus local/anonymous type collection
  -> lexical-scope snapshots
  -> staged type-header, hierarchy, callable-signature, member, and test-harness collection
  -> static constant evaluation and typed global construction
  -> hierarchy resolution and cycle validation
  -> override, interface-obligation, layout, and dispatch analysis
  -> provisional typed function lowering with resolved calls and normal/exceptional control flow
  -> receiver-flow fixed point and call-site borrow targets
  -> final name, visibility, conversion, type, ownership/effect, and safe-free validation/lowering
  -> compiler-owned typed SSA/control-flow, hierarchy, exception, array, I/O, destructor, rollback, and free IR
  -> closed-world primitive-generic shape and callable specialization
  -> LLVM text emitter
  -> llvm-as (IR validation and bitcode assembly)
  -> opt (LLVM optimization pipeline)
  -> compiler-finalized on-demand trace metadata
  -> llvm-as (final bitcode assembly)
  -> llc (native program object generation)
  -> llvm-objcopy (Linux trace-section mapping)
  -> Clang (bootstrap runtime C object generation)
  -> Clang C++ driver mode (platform unwind runtime and link)
  -> Mach-O executable on macOS / ELF executable on Linux
```

Every frontend phase reports diagnostics rather than allowing malformed source
to crash the compiler. LLVM emission consumes typed IR and never inspects the
source AST. Each parsed unit retains its source, package, and imports. Semantic
analysis first collects the complete package-qualified and deterministic lexical
type universe across all units. Named and unqualified hierarchy edges establish
the signatures needed to type arbitrary qualified anonymous-construction
primaries. `AnonymousParentBinder` then plans each such primary exactly once,
binds its exact member target, owner view, parent template, and fresh diamond
variables, and freezes that result before anonymous override/layout analysis.
This staged binding never guesses a globally unique simple member name and
preserves the ownership-safe evaluation order: explicit enclosing receiver
once, immediate null check, source constructor arguments, allocation, then
construction. Feature 105 retains that ordering for catchable allocation
failure rather than adopting Java's earlier allocation attempt.

## IronDocs source documentation

D098 adds the independent `irondoc` entry point in `ironwood.compiler.doc`.
Its pipeline is source discovery -> existing lexer/parser -> documentation model
-> Markdown renderer. The lexer can retain `/** ... */` text and source spans
on request; ordinary compilation does not collect documentation text or change
its token stream. Declaration association uses parsed source spans and the next
non-trivia token, including `@Override` and `@Test`, rather than matching source
bodies.

The documentation model uses the existing AST for declarations and visibility.
It does not run semantic analysis, typed IR lowering, LLVM, or documented code.
Comment validation and selected-declaration links produce source diagnostics
before writing output. The Markdown renderer produces deterministic package/type
pages and a bundled SVG banner. It is an internal renderer, not a public Javadoc
doclet API. See [IRONDOCS.md](IRONDOCS.md) for the initial compatibility boundary.

`--doc-version` labels the documented API independently of the tool's own
`--version`. The repository's `scripts/update-irondocs.sh` passes `VERSION`,
generates the standard-library reference, and optionally commits versioned
Markdown with `--commit` or commits and pushes the current branch with
`--commitpush`. The default only generates files (D101).
D099 defines changing prerelease references and immutable stable snapshots.

## Compilation sets, source path, and classpath

The command line accepts one or more explicit `.iron` files. `SourceSetLoader`
parses those roots, scans their declarations and bodies for referenced nominal
types, and repeatedly loads missing dependencies until the source set reaches a
fixed point. For canonical type `com.test.Foo`, each `-sourcepath` root is checked
for `com/test/Foo.iron`. The default root is `.`, and source roots take precedence
over classpath libraries. Explicit imports, wildcard imports, same-package names,
and fully qualified names determine the candidates; semantic resolution reports
unknown, ambiguous, duplicate, or inaccessible types with source locations.

`-cp` accepts the platform-separated compile-time class path; `-classpath` and
`--class-path` remain compatibility aliases. A path entry may
be one `.ironclass` file or a package-root directory.
For a directory entry, `com.test.Foo` maps directly to
`com/test/Foo.ironclass`; entries are searched in order. The default classpath is
`.`.

Every top-level type, including the class containing `main`, is represented by
the same `.ironclass` format. Member, local, and anonymous declarations are
owned by that top-level compilation-unit artifact rather than emitted as
independently addressable lexical class files. Format 1 is a deterministic ZIP
containing a versioned manifest, a canonical-type index, optional entry-point
metadata, and the validated source compilation unit. The final executable compiler lazily
loads referenced class units and analyzes and lowers them with application
sources. The embedded source is an explicit bootstrap representation: it
preserves complete closed-world analysis and avoids promising a stable typed-IR
ABI before that representation has been designed. There is no runtime class
path or class loading.

The compiler build also compiles declarations below `stdlib/src/main/ironwood`
into ordinary format-1 `.ironclass` files and packages them deterministically as
`compiler/build/ironwood-stdlib.ironjar`; installed distributions place that
archive at `lib/ironwood-stdlib.ironjar`. Loose source and class roots remain
development fallbacks. Dependency loading gives the bundled
standard-library root the same compile-time treatment in source checkouts and
packaged installations. `ironwood.lang` is an implicit lookup package; other
library packages require normal imports. Only referenced library classes enter
the final closed world, providing the first class-granular tree-shaking boundary
without runtime loading. The mandatory `Object` root and its `String` return
type enter every final closed world; other library types such as `System` and
`PrintStream` remain reference-driven and removable. Application source or classpath entries
cannot redefine compiler-owned standard-library types, especially the canonical
`Object` root.

Dependency scanning descends recursively through declared bounds, wildcard
bounds, owner/member arguments, explicit callable arguments, nested/local/
anonymous bodies, `throws` declarations, thrown expressions, and catches while
treating in-scope type-parameter
names as variables rather than nominal dependencies. Format-1 class payloads
preserve generic declarations verbatim,
while `.ironclass` filenames and `.ironjar` indexes continue to use the raw
package-qualified declared type. Source-path, class-directory, individual
class-file, archive, and explicit-link workflows therefore reconstruct and
validate the same exact substitutions without runtime generic metadata.
Scanning also descends through assignment, conditional, update, cast, and `for`
nodes. A reference cast adds the bundled `ironwood.lang.ClassCastException`
dependency because final semantic analysis may select checked lowering. A
method marked `@Test` adds `ironwood.testing.TestSuite`,
`ironwood.testing.TestRunner`, and the generated dispatch failure type as
dependencies. The testing types must still be present on the explicit
classpath; this scan does not make the optional testing archive implicit. A
reachable integral division or remainder adds the bundled
`ironwood.lang.ArithmeticException` dependency, including when the source body is
reconstructed lazily from a class directory or `.ironjar` during explicit link.
Static initializer scanning descends through runtime field expressions and
static blocks, discovering `Type.field` qualifiers, call/allocation types, local
types, and any runtime exception dependencies needed when a class or archive is
reconstructed.
Static-import declarations add their canonical owner as a dependency before
unqualified uses are resolved. Single imported member types and static-on-demand
member types contribute owner/member candidates without confusing same-name
field or method imports with the type namespace. The same discovery runs over
source paths, class directories, individual `.ironclass` files, bundled classes,
and `.ironjar` indexes.
Enum scanning also descends through constant constructor arguments and member
bodies and adds the implicit `String` and `IllegalArgumentException`
dependencies used by synthesized enum operations.

## Compiler-owned typed IR

The IR represents a program as nominal class/interface metadata, typed static
globals, global dispatch slots, and typed functions. Static fields retain their
owner, source type, final flag, constant initial value, and source span;
dedicated static-load and static-store instructions keep body effects visible to
analysis. Each `IrFunction` also retains its stable `.iron` source basename and
whether it is a constructor, allowing the backend to derive a qualified
source-callable identity without consulting the AST. Functions contain:

- typed SSA parameters and value references, including class, interface,
  recursively parameterized nominal types, owner-qualified type variables,
  upper/lower-bounded wildcard captures, exact nested owner views, and
  recursively nested invariant array reference types;
- distinct byte, short, char, int, long, float, double, boolean, typed null,
  pooled string constants, and enum singleton constants carrying nominal type,
  name, and ordinal;
- arithmetic, division/remainder, shifts, bitwise/unary, comparison, explicit
  numeric conversion, phi,
  object/array allocation, explicit free,
  boolean null, array-bounds, and array-length safety predicates, field/array
  load and store, array length, exact array
  descriptor tests, standard output, Object/System identity hash, bulk array
  copy, object-to-string, and explicit reference-conversion instructions;
- distinct direct, class-virtual, and interface-call instructions;
- explicit nominal-membership and exact-array-descriptor `instanceof`
  instructions with resolved target type identities;
- explicit exception landing/take and source-catch-entry instructions plus
  normal/unwind call edges, including allocation-producing object, array,
  object-to-string, char-snapshot, String-range-snapshot, and concatenation
  operations;
- named basic blocks; and
- jump, conditional branch, typed integral switch, return, throw, invoke, and
  unreachable terminators.

Local declarations and assignments remain source-language concepts. Semantic
lowering maps their current values to SSA references. Explicit `final` flags on
locals and parameters prohibit every later assignment/update and feed the same
capture-eligibility analysis as effectively-final bindings. An evaluated
lvalue stores
one local identity or one already-evaluated field receiver/array receiver and
index, so compound assignments and prefix/postfix updates never repeat a
side-effecting receiver or index. `if`, short-circuit `&&`/`||`, and conditional
expression joins receive phi nodes when values differ. `while`, `do`/`while`,
classic `for`, and enhanced `for` receive loop-carried phis; multiple normal
and explicit `continue` edges are merged, `continue` targets the while header,
do-while condition, or classic/enhanced-for update block, and break edges join
the loop exit. Enhanced-for lowering evaluates its source once. Array traversal
uses typed length, comparison, bounds-check, load, and index-update operations.
`Iterable` traversal stores the one `iterator()` result in a synthetic SSA local
and emits ordinary typed `hasNext()`/`next()` calls. That temporary is a borrow
of producer-owned storage and introduces neither an allocation nor a free.
Labeled statements map names to lexical break and loop-continue contexts; an
ordinary labeled statement adds only an exit block. Empty statements add no IR.
Array initializer AST nodes retain their ordered element trees and contextual
array target. Semantic lowering emits one `IrArrayAllocateInstruction` with an
exact constant length for each written brace level, followed by one typed
`IrArrayStoreInstruction` per element in source order. Nested brace results are
registered in the same constant-slot allocation-provenance map as explicit
child-array stores, so loads, detachment, escape, and safe-`free` reuse the
existing proof. No initializer-specific LLVM operation or runtime ownership
table exists; LLVM receives the ordinary array allocation and store IR.
Classic `switch` evaluates its selector once, lowers
case constants and the default destination to `IrSwitchTerminator`, and uses
ordinary blocks, jumps, phis, and abrupt terminators for fallthrough and group
bodies. Enum selection adds a typed null check and hidden ordinal field load
before the same integer terminator. LLVM emission maps this representation
mechanically to an LLVM `switch`; case dispatch is not reconstructed from the AST. Reference locals
participate in the same SSA construction as primitives. Widening
class/interface conversions and accepted
identity/upcast expressions are represented by `IrReferenceConversionInstruction`
even though their current LLVM lowering preserves the pointer value.
Named `instanceof` patterns reuse the same
`IrInstanceOfInstruction` or `IrArrayTypeTestInstruction` as a plain test, then
bind the successful operand through an ordinary typed reference conversion.
The front end computes true/false definite-match facts through `!`, `&&`, `||`,
conditional arms, branches, guards, and supported loops. Semantic lowering
activates the binding only on proved paths and creates SSA phis when a binding
originating in a short-circuit operand must dominate a later merge. Type-
dependency scanning and lexical-capture analysis consume the same flow facts,
so separate compilation and captured pattern locals do not invent parallel
scope rules.
Checked class/interface casts expand in compiler-owned CFG: a typed null test
branches directly to success, the non-null path uses
`IrInstanceOfInstruction`, and the failure path allocates, constructs, and
throws the bundled `ClassCastException`. The success edge ends in the same
pointer-preserving reference conversion, retaining compiler-known allocation
identity for safe reclamation. No LLVM-only cast proof is performed.
Checked casts to an all-unbounded-wildcard parameterization use this same erased
nominal CFG and membership target. A concrete parameterized target uses the same
CFG only after closed-world analysis proves every overlapping implementation
has the exact requested generic view. An unprovable erased argument is an
unchecked-cast error, never a warning. Success retains exact generic arguments
and allocation identity in typed IR; they erase only at linkage and LLVM
boundaries, so no runtime generic registry is added.

After semantic checking constructs this typed program, primitive generic
arguments are materialized entirely within compiler-owned IR. Each distinct
used primitive-position/kind shape receives a deterministic internal class or
interface identity, native-width field layout, descriptor, and specialized
constructor/method bodies. Generic methods and constructors receive specialized
linkage, and polymorphic generic callable instantiations receive closed-world
dispatch slots with exact primitive signatures. Reference positions continue
to share `ptr`, static state and initialization remain attached to the raw
declaration, and exact array descriptors retain specialized leaves. The pass
rewrites parameters, returns, phis, fields, calls, arrays, and exceptional CFG
before LLVM sees them. It diagnoses a requested shape whose body still needs
`null`, reference conversion, object dispatch, or another reference-only
operation. Primitive-specialized membership excludes the raw generic wildcard
bit while retaining exact specialized parent identities for source-provable
casts, preventing a pointer-shaped `G<?>` cast from crossing the value ABI. No
AST-to-LLVM shortcut, wrapper allocation, runtime tag, registry, or loader is
introduced.

Primitive conversion policy is centralized in semantic analysis. Assignment,
field, array-store, invocation, and return contexts share widening rules;
assignment contexts additionally admit representable integral constant
narrowing. Unary and binary numeric promotion retain the selected source width
in SSA, and every width-changing step becomes an
`IrNumericConversionInstruction`. Compound assignment and updates explicitly
convert the promoted result back to the evaluated-once lvalue type.

Feature 83 extends numeric tokenization with `0b`/`0B` binary integers. The
lexer validates binary digits, separator placement, and the optional `L`/`l`
suffix before parsing. A shared semantic decoder applies the same 32/64-bit
selection and exact two's-complement interpretation to ordinary expressions,
static constants, switch labels, and all compile-time folding paths. The result
is an ordinary typed `IrConstant`; LLVM receives the same `i32` or `i64`
constant used by decimal and hexadecimal spellings, so no binary-specific IR,
runtime call, or ABI exists.

Feature 86 extends import parsing and semantic lookup with single-member and
on-demand static imports. A shared resolver selects accessible inherited or
declared static fields, methods, and member types, applies single-import
shadowing before on-demand lookup, removes duplicate candidates, and reports
missing, inaccessible, conflicting, or ambiguous names. Imported method
candidates enter the existing generic invocation planner and imported fields
reuse ordinary static load/store and constant evaluation. Consequently active
use still emits the existing declaring-type initialization ensure, compile-time
constants still bypass it, and no import-specific typed IR or LLVM operation
exists. Format-1 artifacts preserve the source declaration, while dependency
loading and final tree shaking keep the mechanism classpath-independent and
closed-world.

Feature 91 adds dedicated AST nodes for modern switch statements, switch
expressions, arrow rules, and `yield`, while retaining the existing classic
group AST and its explicit fallthrough. Semantic lowering evaluates the
selector once; validates compatible integral, exact-enum, and compile-time
String constants plus null/default placement; and checks expression
exhaustiveness. Exact enum coverage is computed from the closed-world constant
domain. Integral and ordinal dispatch reuse `IrSwitchTerminator`. String
dispatch emits source-ordered `IrStringEqualsInstruction` branches against the
existing pooled constants. Reference selectors without `case null` reuse the
ordinary catchable null-check CFG.

Each expression result or `yield` is assignment-converted to its target type
when one exists, otherwise semantic analysis merges numeric or reference
branches and creates one typed phi. A yield crossing active `finally` blocks
reuses the same inner-to-outer cleanup expansion as return and transfer; abrupt
cleanup discards the pending result. Dependency, local-type, capture, blank-
final, checked-exception, escape, owned-array, and symbolic-origin walkers all
traverse the new forms before LLVM lowering. Safe-`free` remains conservative
across uncertain result identities. Format-1 `.ironclass` payloads reconstruct
the source AST at link, so directory, individual-class, and archive inputs use
the same analysis. No switch-specific runtime operation, dispatch table,
allocation, reclamation, or native ABI is added.

Integral division and remainder first branch on a typed zero comparison. The
zero edge allocates and constructs the ordinary bundled
`ArithmeticException`, then uses the existing `IrThrowTerminator` and lexical
normal/unwind exception edges; a surrounding catch therefore observes the same
language exception mechanism as explicit `throw`. The nonzero edge retains a
typed divide/remainder instruction. LLVM lowering separately guards
`MIN_VALUE / -1` for both i32 and i64 before `sdiv`/`srem`, selects a safe divisor, and selects Java's
wrapped quotient or zero remainder so neither divide-by-zero nor signed overflow
can become LLVM poison. Shift distance masking is explicit typed integer IR at
31 or 63 according to the promoted left operand. Integer operations omit
`nsw`/`nuw`. Floating operations omit fast-math flags; comparisons use ordered
predicates except Java `!=`. Numeric conversion lowering uses sign extension,
zero extension for `char`, truncation, signed/unsigned integer-to-FP conversion,
FP extension/truncation, and guarded saturating FP-to-i32/i64 conversion with
NaN mapped to zero. Narrow FP casts pass through i32 before byte/short/char
truncation.

Each class/interface `IrClass` records its kind, direct superclass and
interfaces, dense type id, transitive membership, and source span. Concrete
classes additionally contain their complete base-first `IrField` layout and the
linkage selected for each global dispatch slot. Field instructions retain the
declaring owner, type, logical layout index, and source span. Hidden declarations
therefore occupy distinct slots, and lexical, receiver-static-type, `super`, and
type-qualified selection cannot alias accidentally.
Static fields never enter `IrClass` object layouts. A declaration-order-aware
constant evaluator applies Java-width promotion, narrowing, overflow,
floating-point, and visibility rules before function analysis. It classifies
only valid `static final` primitive expressions as compile-time constants and
rejects constant cycles. Other field initializers and named-class static blocks
lower in textual order into a synthetic `<clinit>` function. `IrProgram` records
an `IrTypeInitialization` for each type, including its ordered prerequisite
types and optional initializer linkage. Active-use sites lower to explicit
`IrEnsureTypeInitializedInstruction` operations, which can unwind through the
same typed exception regions as calls. Native lowering turns each operation
into an always-inlined fast barrier that loads the private state and continues
immediately when it is initialized. The barrier supplies LLVM with an expected
initialized-state branch hint. Every other state enters one shared, non-inlined
slow routine that retains the reentrant, prerequisite, failure and `<clinit>`
state machine. This keeps first-use behavior unchanged while removing a native
function call from the steady-state barrier.
`IrArrayType` records each reachable invariant array type's exact recursive
descriptor identity, `Object` membership, and inherited dispatch entries.
Array descriptors are sorted deterministically by displayed element type and
retain non-membership ids outside the dense class/interface range. Nominal
membership continues to use dense ids; exact array tests instead name and
compare descriptor globals directly. Closed-world pruning retains nested array
descriptors only when a reachable signature, allocation, or exact test needs
them.

Constructors and instance methods lower to ordinary functions with a typed
hidden `this` parameter. Inner, local, and anonymous constructors additionally
carry compiler-owned enclosing, captured-value, and qualified-super-enclosing
operands that never enter source overload signatures. Capture fields are
immutable aliases and participate in escape summaries and safe-`free`. A class
with no declared constructor receives a real synthesized no-argument
constructor in IR. Every non-root constructor begins
with a direct superclass-constructor call: explicit `super(arguments)` supplies
its arguments, otherwise the compiler inserts `super()`. An explicit first
`this(arguments)` instead calls the selected same-class constructor, and a
semantic delegation graph rejects every recursive cycle. Consequently no body
or early `return;` can bypass base construction.

Declared methods and constructors are indexed by source name and full parameter
signature. Call lowering evaluates argument types, filters to
assignment-compatible signatures, and selects the unique most-specific
candidate. No-match and ambiguous calls are semantic errors. Override checks,
interface requirements, dispatch entries, devirtualization targets, and
constructor calls all retain that full signature. Existing non-overloaded
functions keep their historical native linkage names; members in an overloaded
name group receive deterministic parameter-derived suffixes so LLVM and native
linkage remain unique.

Generic member lookup carries exact receiver/owner substitution across fields,
constructors, methods, and hierarchy edges. The common invocation planner
performs fresh receiver and argument capture conversion, explicit/inferred
callable arguments, bound solving, expected-type propagation, diamond, and
fixed-arity applicability for every candidate. Callable views retain
substituted source types while pointing at one declaration and native linkage
body. Interface obligations and virtual overrides alpha-compare generic shapes;
dispatch slots retain first-bound-erased signatures. Same-erasure source
overloads are rejected before dispatch construction.

The object path is now:

```text
new Plus(4, 3)
  -> typed allocation of Plus
  -> ironwood_allocate(size, Plus descriptor, immortal failure error)
  -> zeroed complete-object storage with the descriptor installed
  -> normal construction edge or allocation-failure unwind edge
  -> direct typed call ironwood.Plus.<init>(Plus, int, int)
  -> direct super call ironwood.Base.<init>(Base, int)
  -> direct implicit call ironwood.lang.Object.<init>(Object)
  -> constructor bodies execute root-first
```

No object, hierarchy, dispatch, conversion, type-test, array, string, I/O,
exception, or cleanup syntax is lowered directly from AST nodes in the LLVM
emitter.

The array and literal-string paths are:

```text
new int[n]
  -> typed nonnegative-length predicate and exceptional branch
  -> typed array allocation carrying element type and i32 length on success
  -> ironwood_allocate_array(length, element-size, element-kind,
       int[] descriptor, immortal failure error)
  -> normal result edge or allocation-failure unwind edge
  -> zeroed { descriptor, native length, element size/kind, contiguous elements }
  -> typed null and bounds predicates plus exceptional branches before each load/store

"Ironwood 🌲"
  -> decoded UTF-8 value canonicalized in the finite final-program literal pool
  -> immutable compiler-emitted ironwood.lang.String object and UTF-16 tail
  -> ordinary typed String reference to immortal storage

""" ... """ / r""" ... """
  -> normalize CRLF/CR to LF and remove Java-style incidental indentation
  -> decode ordinary Ironwood escapes only for the cooked form
  -> use the same finite literal-pool path as an ordinary decoded String

prefix + value + suffix
  -> left-to-right evaluation and Java-shaped String conversion
  -> one typed string-concatenation instruction with ordered parts
  -> native two-pass exact UTF-16 sizing and writing
  -> conditional cleanup of owned temporary object renderings
  -> one ordinary immutable String allocation with safe-free provenance
```

D059 names this implemented path Feature 75. It does not call or provide a
runtime `String.intern()` pool. D061 implements Feature 76 String `+`/`+=`:
constant expressions enter the same finite pool, while a dynamic chain lowers
through dedicated compiler-owned typed IR to one ordinary exact-size String
result allocation with tracked provenance, not a hidden StringBuilder and
backing array. D065 implements Feature 78 in the lexer: cooked and raw blocks
produce the same decoded `STRING` token consumed by the existing parser,
semantic, typed-IR, pooling, artifact, and backend paths.

`System.out` and `System.err` resolve as normal typed static-field loads followed
by instance calls on `ironwood.io.PrintStream`. Each field initializer is an
`IrImmortalObject` rather than an allocator result; its compiler-supplied
`channel` field selects stdout or stderr. LLVM emits both objects with the
normal `PrintStream` descriptor and initializes their static-final references
to image addresses. `print`/`println` overloads lower to
`IrPrintStreamWriteInstruction`; `flush` and `checkError` use their own typed
instructions. The backend maps those operations to the isolated stream ABI, so
stream choice, payload type, newline behavior, and error checks remain
inspectable before LLVM.

U3 adds typed `IrStreamInstruction` descriptor operations through the private
`ironwood.io.StreamSupport` bridge, including an exceptional open/OOM edge.
Buffering, UTF-8 state, lines, validation, and resource state remain in source.
`IrImmortalObject.storageType` supplies the concrete StandardInputStream layout
behind System.in's abstract InputStream type; specialization and pruning retain
that layout. `IrFileInstruction.SAME_FILE` compares device/inode identity for cp.
Compiler escape summaries join proven non-retaining primitive/void calls;
constructor borrow edges and factory allocation snapshots preserve safe free
across wrappers and exceptional cleanup (D094).

D096 binds ownership call targets from provisional typed IR. `BorrowDispatchAnalysis`
uses direct linkage or the resolved dispatch slot and the hierarchy's existing
class/default implementation selection. A monotone receiver-type fixed point
propagates allocations and immortal concrete types through SSA conversions/phis,
arguments (including constructor delegation), returns, and instance/static fields.
Every lowered body and exceptional edge contributes; fields are joined across
instances and generic instantiations. Reference-array loads and native results
include every type-compatible concrete class. Landing-pad exception objects start
as unknown Throwable subtypes; catch/cast conversions restrict compatible types.
Destructor receivers are seeded
for implicit native cleanup; library inputs without an entry point are unknown.
Empty receiver flow falls back to the full compatible target set.

The resulting call-site target sets refine escape and owned-field summaries.
Final lowering reruns all diagnostics and emits reclamation IR using those
summaries; provisional ownership failures cannot survive as executable IR.
Reference-returning calls keep their symbolic-return analysis. This proof uses
neither backend pruning nor an assumption that every same-name overload/default
can be invoked. Source reconstruction at final class/archive linking repeats the
analysis, so a newly supplied retaining implementation invalidates the proof.

The canonical `Object.hashCode()` and `Object.toString()` bodies lower to
`IrObjectHashCodeInstruction` and `IrObjectToStringInstruction`. LLVM emission
maps only those typed operations to the isolated runtime ABI. Default
`Object.equals(Object)` lowers as ordinary typed reference equality, and normal
virtual dispatch still selects class overrides through the shared slot table.
The compiler-owned allocation-result table marks the canonical
`Object.toString()`, `String.fromChars(...)`, and `String.fromRange(...)`
intrinsics as fresh-result producers. `IrStringFromRangeInstruction` carries
the source String, checked begin index, and length to a narrow runtime ABI that
performs one exact-size allocation with no scratch array. U1 adds
`IrStringCopyInstruction` and `IrStringFromCharRangeInstruction`; public String
storage constructors validate before those one-allocation copy boundaries and
are treated as non-retaining by escape summaries. Package-private
`String.fromInteger(long, int)` and `String.fromCharacter(char)` lower to
`IrStringFromIntegerInstruction` and `IrStringFromCharacterInstruction`:
fresh, allocation-failure-aware results filled directly without helper arrays.
`StringBuilder.substring` and `subSequence` check live bounds in source and use
the existing char-range copy operation, not a full-builder snapshot.
`IrSystemGetenvInstruction`
returns a fresh-or-null String through the same allocation proof, while typed
clock and unary/binary math instructions keep native services and LLVM
`sqrt`/`pow` lowering out of ad hoc source-library code.
Private Object-rendering consumption points in StringBuilder and PrintStream
lower to `IrReleaseOwnedToStringResultInstruction`. The runtime consults the
concrete descriptor bit and deallocates only a proven fresh result; borrowed or
mixed-result overrides remain untouched. String concatenation emits the same
instruction for every object conversion after the result has been copied. It
also creates cleanup landing paths for already produced renderings if a later
conversion or the final concatenation throws; a null source object makes the
release operation a no-op. D118 reuses this boundary for Object insertion.
Its call-site borrowing refinement checks every possible `toString` target for
receiver publication. Symbolic return analysis preserves the String type of a
literal receiver, allowing a factory call on a literal to prove a fresh result
without treating the literal itself or a published result as owned.

`Throwable.toString()` and its virtual localized getter remain source-written.
The private description helper lowers to `IrThrowableDescriptionInstruction`,
which copies the existing concrete type name and nullable message into one
exact-size String through a catchable allocation boundary. No intermediate
class-name String or character array is needed. The private cleanup helper
lowers to `IrReleaseOwnedThrowableMessageInstruction`; its descriptor bit is
derived from the concrete `getLocalizedMessage()` target, or `getMessage()` when
the default localized getter delegates there. Source `finally` ensures cleanup
on both success and description allocation failure. Borrowed, retained, and
uncertain message results are preserved. The audited receiver-borrowing contract
for this exact facade requires all closed-world message getters to lack
non-return receiver escapes; publishing or unknown getters stay conservative.
See D114. Artifact reconstruction recomputes these results from callable bodies.

The private `ByteArrayOutputStream.decodeSnapshot(byte[], int)` helper lowers
to `IrStringFromUtf8Instruction`. Its two-pass native decoder measures the
written prefix, allocates one String, and fills UTF-16 units with Java-compatible
replacement of malformed sequences. The compiler marks the result owned and
fresh, the arguments remain borrowed, and allocation failure stays visible to
exception and destructor-effect analysis. Buffer bounds come from the stream's
private buffer/count invariant. Ordinary source dispatch and D089/D111 rendering
consumption still apply; no new descriptor ownership bit is needed (D115).

D117 adds explicit String case/repeat/replace/join instructions and reuses the
UTF-8 decode instruction for `String(byte[])`. Allocation instructions carry
catchable failure effects through generic specialization, pruning, borrowing,
destructor analysis, and LLVM call/invoke lowering. Nonallocating
`IrStringEqualsIgnoreCaseInstruction` calls the fixed Unicode helper. Case
conversion has only a source and upper/lower flag, with no locale operand.
CharSequence replacement and join refine borrowing only when all reachable
rendering callbacks lack non-return receiver escapes. Native compilation also
builds the isolated, derived Unicode helper and removes unreachable C functions
and data at the final link. The pruner retains a virtual/interface dispatch entry
only when a reachable call uses its slot, preserving slot indices and revisiting
already-reachable receiver types when a new slot is discovered. Direct calls,
destructors, rollback and initialization retain their existing roots. This keeps
unused String casing methods from retaining Unicode tables through metadata.

Calls to the exact `PrintStream.print(Object)` and `println(Object)` methods add
a call-site escape refinement. The compiler enumerates `toString()` dispatch
targets from the argument's static type and clears the outer argument escape
only when every target lacks a non-return receiver escape. Unknown or
publishing targets retain the ordinary conservative summary. Return-only
borrows do not count as publication because PrintStream consumes the rendered
String before returning and the descriptor protocol independently decides
whether that String is owned.
The exact static `System.identityHashCode(Object)`, `System.allocationCount()`,
`System.liveAllocationCount()`,
and `System.arraycopy(...)` bodies similarly lower to
`IrIdentityHashCodeInstruction`, `IrAllocationCountInstruction`, and
`IrSystemArrayCopyInstruction`. Their runtime calls therefore remain explicit
in typed IR; reading the diagnostic count is side-effect-free with respect to
language allocation, identity hashing bypasses virtual dispatch, and bulk copy
retains ordinary reference aliases rather than transferring ownership.

## Safe-free analysis and lowering

Before emitting an `IrFreeInstruction`, semantic lowering proves the identity of
the target allocation and conservatively accounts for its aliases and escapes.
The proof accepts local `new` allocations, fresh-or-null call results identified
by a fixed-point symbolic-return summary, compatible control-flow joins, and
detached or destructor-owned private reference fields. The ownership field
summary admits only fresh/null writes plus confined sibling aliases, and rejects
load-derived aliases returned, thrown, externally stored, or passed to a
retaining call. Closed-world escape summaries admit resolved observing calls,
including `System.arraycopy`, without class-name privilege in the allocation
effect analysis.

Allocation-result summaries track distinct fresh origins rather than one
method-wide fresh bit. A wrapper may therefore reclaim one method-local
allocation and return a different fresh result without falsely escaping the
first. Escape summaries separately model return-only receiver/argument aliases
and non-return publication. The same source bodies are reconstructed from
format-1 `.ironclass` and `.ironjar` inputs at final link, so these contracts
survive source paths, loose classpaths, archive classpaths, pruning, and
separate compilation/linking.

D120 adds an audited fresh-result contract for the final `Instant.toString()`
method, whose branches each contain one dynamic text concatenation. Existing
rendering descriptors therefore permit cleanup by printing, concatenation and
Object append. `DateTimeParseException` uses the existing private rendered-text
cleanup intrinsic when snapshotting a CharSequence. An `Instant.parse`
call-site borrow refinement checks every possible `length`, `charAt` and
`toString` callback for receiver publication. It does not suppress real callback
effects or add runtime ownership checks. Epoch/calendar logic and the ISO parser
remain ordinary Ironwood library source, with no new IR or native ABI.

An eligible private helper field may additionally have a summarized borrowed
return. `FunctionAnalyzer` maps that result to the root owner allocation while
retaining the helper's concrete closed-world type through interface conversions
and loop phis. This permits precise summaries for normal virtual/interface
iterator calls, rejects an independent helper `free`, propagates nested holder
borrows, and diagnoses any observation after owner destruction. Publishing the
borrow escapes the root owner. A control-flow phi joining borrows from different
owners makes each possible owner uncertain, so none can be freed while the
merged borrow may remain observable. Exact dependent-borrow provenance is
preserved through wrapper-method returns, so returning `list.iterator()` does
not become an ownership transfer or an unknown allocation. Constructor
summaries distinguish an argument retained solely by the fresh receiver from
an outward escape; containment is used only when a second field analysis proves
the private backlink never leaves the owned helper. These summaries iterate to
a fixed point. See
[Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

D107 reuses the constructor-borrow graph for caller-item loans from known local
bundled data structures. Audited insertions and `ArrayList.set` on exact
constructed types record retained arguments before an invoke's exceptional edge,
while clear discharges loans only on the normal continuation. Ownership snapshots
union possible loans and content exposure across branches and handlers. Exposed
contents keep later insertions and replacements conservative; key callbacks
require closed-world non-retention proofs. The
verified direct `Collections.unmodifiableList` factory installs a backing-list
loan only after successful construction. This analysis emits no runtime ownership
checks or implicit caller-item destruction.

U5 recursive traversal adds a closed-world callback boundary for
`Files.walkFileTree`. Semantic analysis checks every reachable
`FileVisitor<Path>` implementation and accepts the call only when path and
attribute parameters are not retained. The source traversal may then reclaim
each temporary callback value and closed directory-stream wrapper through exact
private cleanup intrinsics. Callback exceptions and traversal control remain
ordinary typed source flow; no runtime ownership registry, visited set, or
callback wrapper is added.

D102 adds audited ownership contracts for the two bundled object pools (D103). A
checkout carries both its root lifetime and its direct pool provenance; references
to an owned child are dependent borrows without permission to transfer that child.
Release conservatively associates an external allocation and its aliases with
the receiving owner, even though D104 excludes external objects from the runtime
contract and destruction. Exceptional call edges retain their earlier state.
Conflicting owners and escaping aliases prevent reclamation. Polymorphic pool
calls use the contract only when every closed-world target is an audited pool.
Concrete `ObjectBuilder.newInstance` bodies must prove fresh unescaped reference
results or null and no receiver publication.

D104 replaces runtime pool identity tables with ordinary private creation arrays.
`OwnedArrayElementAnalyzer` validates the bounded explicit destructor loop
`for (int i = 0; i < this.items.length; i++) { free this.items[i]; }`.
The field must be a private owned reference array. Typed analysis requires
non-null entries to come from distinct fresh allocations or proved fresh factory
results, with no independent publication. Repeating a store without repeating
its allocation is rejected. Full shallow copying into fresh replacement storage
is supported for growth; arbitrary reads, copies, and aliases are not accepted.
Private bundled pool creation helpers may return recorded values to their audited
availability algorithms. Public checkout still has its conservative borrow contract.

The loop becomes `IrDestroyArrayElementsInstruction`. LLVM lowering walks the
array, clears each slot, and invokes ordinary descriptor-based destruction. The
array container is freed by the separate source `free`. Constructor rollback
also drains proved creation arrays, reclaiming partial construction. Destructor
effect analysis still rejects allocation and escaping exceptions, including in
element destructors. Ordinary array `free` remains shallow. No pool-specific C
runtime operations, identity hashing, duplicate checks, or checkout flags remain.

`FunctionAnalyzer` gives repeated loads of an eligible field on `this` one loan
identity. Replacing that field with a different fresh array detaches the loan;
`free` may then consume a sole live local alias. A same-value store does not
detach it. Calls while the loan remains attached are treated as potentially
reentrant, and structured-flow merges preserve a detached identity only when
every loop backedge carries that same identity. SSA reference conversions retain
allocation identity. An alias may cease to block the proof when its lexical
scope ends. Direct and closed-world-devirtualized calls use summaries computed
from all callable bodies; a local allocation is nonescaping only when the callee
does not retain, return, throw, or pass it to an uncertain operation.

A matched pattern local carries the tested operand's existing allocation
identity. It is therefore an ordinary live alias for escape analysis and
safe-`free`: no ownership is transferred, but a fresh allocation may be freed
through the binding when that binding is its sole observable source reference.

Hidden enclosing-instance and captured-reference fields are explicit escape
edges. Constructing an inner, local, or anonymous object that retains a tracked
allocation blocks a later `free` while that hidden alias may remain live.
Source-visible and hidden constructor operands use the same allocation-identity
model; compiler-generated capture does not create a separate ownership rule.

The proof rejects unknown provenance, live aliases, attached or incompatibly
detached field loans, field/return/throw escapes, escaping constructors and
calls, uncertain polymorphic dispatch, double free, and later use.
After a successful free, analysis marks that allocation identity as freed rather
than synthesizing a `null` assignment. Simple assignment may replace the dead
local value and start tracking a new identity; every operation that reads the
old value, including a second free or compound assignment, is rejected.
Constructor escape checking is closed-world and rejects publication of
in-progress `this` across direct and indirect calls. `this(...)` delegation
shares one allocation identity.
Loops may allocate, use, and free an allocation entirely within one iteration.
Arrays allocated locally use the same identity proof, and freeing one releases
only its container. A constant-index store through a known local array records
the child allocation identity; a matching load preserves it, and overwriting
the slot with `null` or freeing the outer container proves detachment. A child
cannot be freed while a known slot retains it. Dynamic indices, escaped
containers, and calls that may observe elements conservatively escape the
child. Primitive array elements create no aliases, and immortal string literals
are never valid free targets. Because the current summary
representation cannot express per-element aliases created by
`System.arraycopy`, an ordinary tracked destination is conservatively marked
escaping and a later `free` of that local allocation is rejected. This element-
provenance taint is distinct from publishing an owned field's container identity;
the latter remains private and may be reclaimed after proven detachment.
This deliberately leaves safe but unproven programs uncompilable at the `free`
site. LLVM emission performs no memory-safety reasoning: it lowers accepted
`IrFreeInstruction` values through the null-safe `ironwood.destroy` helper,
which calls the descriptor's dynamic destructor entry and then
`ironwood_deallocate`.

## Destructor effects and constructor rollback

Callable symbols and `IrFunction` carry an explicit kind: method, constructor,
destructor, class initializer, or constructor rollback. Each class descriptor
contains optional destructor and rollback entries. Semantic lowering appends a
direct superclass-destructor call on every normally completing destructor path;
the descriptor therefore needs only the most-derived entry.

`ClosedWorldEffectAnalyzer` computes allocation, outward-throw, publication,
and returned-origin summaries to a fixed point over direct calls and all
closed-world virtual/interface targets and active-use class-initialization
prerequisites. It rejects allocating destructors, outward exceptions,
destructor resurrection/publication, and constructor publication. A locally
caught exceptional call remains permitted.

Within one effect analysis, instruction targets are cached against its fixed IR
and class snapshot. Virtual/interface implementations, initializer prerequisites,
and destructor targets do not change during the fixed point. Their allocation,
throw, publication, and return summaries continue to evolve and are read afresh;
caching a summary together with its targets would make the safety check unsound.

Every ordinary class also receives a compiler-only rollback function. A `new`
constructor invoke unwinds to that rollback after storage acquisition. Rollback
loads compiler-proven owned fields in reverse layout order, recursively invokes
normal destruction for those completed child allocations, uses
`IrRawDeallocateInstruction` for the incomplete receiver, and rethrows the exact
landing-pad object. It never calls the incomplete receiver's source destructor.
`IrLiveAllocationCountInstruction` exposes the live runtime
counter independently of the cumulative allocation diagnostic.

## Exception and cleanup lowering

Semantic lowering resolves every catch type and rejects an unreachable ordered
catch before LLVM emission. It also requires thrown expression bounds and catch
classes to derive from `Throwable`, rejects type-variable/non-reifiable catch
targets, and rejects every direct or indirect generic `Throwable` subclass. A
callable symbol retains the resolved throws list, including type variables.
After overload selection and generic substitution, each checked exception from
an explicit throw, method call, object construction, or `this`/`super`
constructor delegation must be covered by an active catch or the current
callable declaration. Subtypes of `RuntimeException` and `Error` are unchecked.
Override validation permits narrowing/removal but rejects incompatible checked
exceptions, including inherited class implementations of interface contracts.
Checked catches with no compatible checked source in the try body are rejected;
unchecked catches remain reachable without a statically declared source. A
catch may contain disjoint `|`-separated alternatives. Semantic analysis rejects
duplicate and subtype-related alternatives, checks reachability per alternative,
and gives the implicitly-final shared binding their least common nominal
supertype. Direct rethrow of a final or effectively-final catch binding uses the
exact checked source types that reach the catch after prior-catch filtering and
generic substitution; any write to an ordinary single-type binding disables
that precision. A
potentially throwing call inside a protected region becomes an
`IrInvokeTerminator` with explicit normal and exceptional
successors; calls outside such a region remain ordinary call instructions.
Exceptional predecessors merge visible outer local values with SSA phis at an
`IrExceptionLandingPadInstruction`. Catch selection then uses resolved type ids
and ordinary compiler-owned `IrInstanceOfInstruction` branches. A union emits
one test per alternative, joins the typed booleans with `BITWISE_OR`, and enters
one shared body with an SSA alias of the existing exception object.
`IrThrowTerminator` records whether a throw propagates directly across frames or
first enters a lexical handler.
Compiler-generated checked-cast failures enter this same CFG and native unwind
model, so surrounding catches and finally blocks observe an ordinary
`ClassCastException`; the bootstrap runtime needs no cast-specific ABI.
Feature 100 uses the same structure for ordinary implicit safety failures.
Typed null, array-bounds, and nonnegative-length predicates branch to valid and
failure blocks; the failure block allocates and constructs the matching
`NullPointerException`, `ArrayIndexOutOfBoundsException`, or
`NegativeArraySizeException`, then reaches the ordinary throw/invoke path.
`throw null` enters the null-failure path before the runtime throw boundary.
Only a taken failure path creates its exception allocation.
Checkedness changes no IR or native ABI: it is a compile-time contract over the
existing exceptional CFG.

Finally blocks are lowered as cleanup paths for normal fallthrough, evaluated
returns, catch completion, and exceptional exits. Return values are computed
before cleanup. On an exceptional exit, lowering installs a compiler-owned
landing region around the `finally` body. If cleanup throws, an
`IrAddSecondaryExceptionInstruction` associates the landed cleanup object with
the already pending primary object, then rethrows the primary. Nested cleanup
regions therefore append later failures in occurrence order. On a normal or
returning path, a cleanup exception remains primary. A return from `finally`
retains its existing abrupt-completion precedence. Constructor calls use the
same invoke form in protected regions, so an exceptional constructor never
reaches the continuation that publishes the new reference.
Loop and labeled-transfer contexts remember the active finally-context snapshot
at the target. A `break`/`continue` whose target has the same snapshot jumps
directly, including a loop nested inside an outer try/finally. When a transfer
crosses new cleanup regions, semantic lowering clones each `finally` body in
inner-to-outer order before its target jump and merges the resulting environment
at that target. An abrupt cleanup terminates that path and supersedes the pending
transfer, reusing the same completion rule as return lowering.

Ownership state is captured with each normal, exceptional, and transfer
predecessor and restored before lowering each mutually exclusive generated
cleanup copy. Exceptional local-value phis preserve a common allocation identity.
Rejoining paths keep a common state or become uncertain when ownership states
conflict; a possibly-freed state also forbids subsequent observation. This permits
source-written exactly-once `free` in `finally`, including nested cleanup and return/catch/throw
paths, without treating repeated lowering as repeated runtime execution or
relaxing the ordinary safe-free proof. `break`, `continue`, and `yield` carry
post-cleanup ownership to loop exits, conditions, updates, labels, and switch
joins. Switch dispatch and fallthrough snapshots isolate sibling arms. A
pending reference-valued yield retains its allocation during cleanup.
Saved reference operands are checked again when consumed, so cleanup in a later
argument or assignment RHS cannot invalidate an earlier receiver or argument.
Loop back edges must not carry newly freed references, and any entry allocation
freed inside a loop must retain the same live ownership and identity on every
back edge. Body-local allocations can therefore be created and freed on each
iteration; uncertain loop-carried reclamation remains a diagnostic. Ownership
effects from earlier iterations also participate in loop-exit joins (D091).

Java's `try (...)` resource syntax is rejected by the parser with a focused
migration diagnostic. `AutoCloseable` is an ordinary interface and `close()` an
ordinary checked call; neither receives special lowering. Resources are
declared before a `try` and closed in source-written `finally` blocks. Existing
allocations retain exact identity across this structured region when SSA and
escape analysis can prove that identity unchanged, permitting a separate
source-visible `free` inside the cleanup itself while still rejecting uncertain
or conflicting ownership paths.

On supported macOS and Linux targets, the emitter maps that IR mechanically to
LLVM's Itanium-family zero-cost EH form: `invoke`, `landingpad { ptr, i32 } catch
ptr null`, and `__gxx_personality_v0`. The catch-all native clause transfers the
language-owned wrapper to generated code; catch typing remains Ironwood nominal
membership rather than C++ RTTI. A nonmatching handler or completed cleanup
rewraps the same language object and starts the next native search outside that
lexical handler. The generated native `main` is the final catch-all, ensuring
phase-two cleanup occurs before deterministic uncaught termination.
Runtime-private association nodes retain secondary language-object references
without creating Ironwood arrays or affecting `System.allocationCount()`.
Throwable count/index intrinsics lower to direct runtime lookups.

For Feature 73, the emitter creates immutable callable/file constants and
pseudo-probe source sites for each retained `IrFunction`. LLVM carries the
probes through optimization and inlining, then lowers them to read-only address
metadata without executable instructions. After optimization, the compiler
adds an immutable table of the surviving native function addresses. The runtime
uses native unwind only when ordinary Throwable construction invokes virtual
`fillInStackTrace` (D121), or when source explicitly refreshes it, and decodes
the captured addresses into the original source and inlining sequence. Throws
and rethrows preserve that snapshot, and each secondary exception keeps its
own. Private typed
`IrThrowableTraceInstruction` operations capture, release, compare suffixes and
print stored frames. Immutable compiler metadata identifies Throwable
constructors and fill overrides to omit at the top of a capture; filtering runs
only during capture. LLVM tail-call elimination is disabled for retained
Ironwood source functions because an eliminated dynamic call frame cannot be
reconstructed from immutable metadata. Other optimizations remain available.
The generated native `main`
passes the compiler-known `Throwable.message` field offset to the uncaught
reporter, avoiding reflection and preserving the ordinary object layout.
Function/file constants and metadata are emitted only with retained closed-world
functions, so class-level tree shaking remains effective.

Throwable adds one private native metadata slot after message and cause. The
compiler validates this bundled layout and requires rebuilding older stdlib
artifacts. Native storage belongs to the Throwable: its source destructor and
compiler-generated failed-constructor rollback release it without freeing
borrowed message, cause or secondary objects. Ordinary object layouts and
deallocation paths are unchanged. Public printing traverses the graph in
Ironwood source using a short-lived owned `ironwood.ds.ArrayList`; borrowing
refinements retain conservative publication effects from virtual callbacks.
The emergency OutOfMemoryError singleton uses writable immortal storage for
its bounded occurrence-specific trace. See [D121's review](STDLIB_STACK_TRACE_REVIEW.md).

## Hierarchy and dispatch analysis

The hierarchy supports `ironwood.lang.Object` as the unique implicit class root,
one explicit superclass per class, multiple implemented
interfaces, and multiple parent interfaces. It rejects kind mismatches,
duplicate parents, and class/interface cycles before body analysis. Member lookup
walks the receiver's static type and ancestors; interface and array receivers
also expose `Object`'s public methods. Override checking requires equal
parameter types, permits covariant reference returns, rejects access narrowing,
static/instance changes, and final overrides, and validates every concrete
class's transitive abstract obligations. Interface default resolution applies
class precedence, most-specific-interface selection, re-abstraction, conflict
diagnosis, and explicit direct-interface-super selection before dispatch.

Cycle validation is a phase boundary: any detected class or interface cycle
returns the accumulated source diagnostics before ownership or dispatch analysis.
Recording the error and continuing is invalid because later superclass walks
require an acyclic graph. No typed program or class artifacts are produced.

All non-private instance-method signatures receive deterministic global
`IrDispatchSlot` indices. The key contains the method name and parameter types;
covariant overrides therefore reuse the inherited slot. Each concrete class
emits one table spanning the global slot universe, with its resolved target or a
null entry. Class-virtual and interface calls remain distinct IR instructions,
but both lower through this same per-class table. A separate itable and fat
interface references are unnecessary in the current closed-world ABI.

Installing the slots also enables a per-compilation cache of dispatch selection
for each declared receiver and slot key, after parent and member binding is
complete. Exact inferred or captured receiver views still resolve independently.
Only callable selection is cached; ownership and descriptor metadata are computed
from the current analysis summaries.

For generic declarations, a type variable erases to its first upper bound and a
capture erases to its upper bound only in linkage and native-layout identities.
Exact generic arguments, including wildcard/capture identity, remain available
to assignment, overload, override, and call analysis. Each concrete class table
maps inherited erased slots to the implementation selected from its substituted
supertype view, including generic interface extension and generic superclass
overrides.

A type-parameter receiver exposes the compatible members guaranteed by all of
its upper bounds, then lowers through ordinary closed-world dispatch using its
first-bound ABI erasure. An unbounded variable has the bundled `Object` bound.
Typed variables, intersection members, and substituted signatures remain
source-precise even though every reference is one native pointer.

Class-hierarchy analysis enumerates every declared concrete receiver compatible
with a call's static receiver type. If they all resolve the signature to exactly
one linkage target, semantic lowering emits a direct call marked
`DEVIRTUALIZED_VIRTUAL` or `DEVIRTUALIZED_INTERFACE`. Zero-target calls remain
diagnosed or indirect as appropriate; multi-target calls remain virtual or
interface dispatch. This exact singleton-target rule is inspectable in Ironwood
IR and applies independently of LLVM's `-O0` through `-O3` pipelines.

The LLVM emitter adds a second, backend-only layer over the pruned closed
world. For each dispatch slot it collects the instantiable receiver classes and
array types that fill the slot. A virtual or interface call whose slot has at
most four such receivers and at most three distinct targets lowers to a chain of
descriptor-pointer comparisons with one direct call per target, joined by a phi,
and keeps the ordinary dispatch-table call as the fallback for any receiver the
chain does not name. The typed IR still records the call as virtual or
interface dispatch; the guard only exposes direct callees that LLVM can inline,
much as a JIT inlines through a monomorphic or bimorphic type profile. Invoke
edges are preserved: each direct call unwinds to the original landing pad, and
successor phis are rewritten for the split predecessor.

## LLVM object and metadata lowering

The backend emits one named LLVM structure per concrete class:

```text
{ type-descriptor pointer, inherited fields root-first, declared fields source-order }
```

The header occupies physical field zero. Compiler-owned field indices remain
logical base-first field indices, so LLVM field GEPs add one for the header.
Primitive types lower to their exact `i1`, `i8`, `i16`, `i32`, `i64`, `float`,
or `double` width; every class/interface, type-variable, or array reference
lowers to LLVM's opaque `ptr`, and `null` lowers to the null pointer. A subclass
pointer, superclass pointer, and interface pointer therefore share one object
address.

Allocation size is calculated from the most-derived named structure and passed
to `ironwood_allocate(i64, descriptor, failure)`. The allocator installs the
concrete class descriptor before construction and raises the immortal failure
object when storage is unavailable. Field operations become typed
`getelementptr` plus load/store. Explicit receiver dereferences first lower the
typed null predicate and exceptional branch; successful paths then access or
dispatch without a separate runtime check call.

Both allocators are declared `noalias nonnull`, the receiver parameter of every
instance callable is `nonnull noundef`, and `ironwood_throw` is `noreturn cold`,
so LLVM folds redundant null checks on fresh objects and receivers and lays out
throwing blocks as cold code. `String.charAt` reads the UTF-16 unit directly
from the runtime string layout rather than calling the runtime.

Failure paths are outlined. A block that allocates an exception, runs its
constructor and throws with no local handler, and the lowering of
`throw new X(...)` outside any local handler (type-initialization barrier,
allocation, constructor invoke with rollback landing pad, null check and throw),
each become one call to a shared `ironwood.throw.<constructor>` helper marked
`cold noinline noreturn`, followed by `unreachable`. The helper takes the
constructor arguments, performs the same allocation, construction, rollback and
throw sequence, and carries no trace probes, so on-demand traces skip its frame
and report the source throw site whose probes remain in the caller. The call
carries `nomerge` so LLVM does not fold distinct throw sites into one block.
This keeps small hot methods such as list access or pool reuse within LLVM's
inline cost budget; the exception semantics are unchanged.

Each static field lowers independently to an `internal global` or `internal
constant`. Only compile-time primitive constants and the compiler-owned
`System.out`/`System.err` intrinsics carry preinitialized values; all other
globals begin at zero/null. Integer and boolean constants retain exact widths;
finite, infinite,
NaN, and signed-zero floating constants use raw bit-preserving LLVM spellings.
Static loads and stores lower mechanically from dedicated typed IR.

Each retained enum constant has private mutable image storage with its concrete
enum or compiler-owned final subtype descriptor and object layout. Typed
`IrEnumConstant` operands distinguish that storage type from the source-visible
enum type. The hidden pooled name pointer and ordinal are preinitialized while
source fields begin zeroed. The source-visible `public static final` constant
field begins null; enum `<clinit>` runs the selected enum constructor and any
subtype initialization against the immortal operand and stores its enum-typed
pointer only after successful completion. Constants therefore have permanent
identity, can retain constructor- and body-initialized fields, contribute
nothing to the ordinary allocation counter, and participate in D055
failure/reentrancy rules without an enum runtime entry point or registry.

For every retained type, the backend emits private state and failure globals
plus an ensure routine. The routine handles uninitialized, initializing,
initialized, and failed states, initializes superclass/default-method-interface
prerequisites in compiler-defined order, invokes `<clinit>`, and caches an exact
escaping exception object on failure. Reentrant requests during initialization
return immediately; failed requests throw the cached object. The native entry
wrapper ensures the selected main type before calling its source `main`. Ensure
operations carry caller source provenance into D054 traces and remain explicit
through `-O0` to `-O3`. This is closed-world generated code, not a runtime
loading registry, and the no-threads language has no initialization locks.

For each concrete class the compiler emits:

- an immutable global dispatch table;
- an immutable byte membership table covering every class/interface type id;
- an immutable descriptor containing the class type id and pointers to those two
  tables plus the qualified type name used by native rendering and diagnostics, a
  class-versus-array kind tag, destructor/rollback entries, and closed-world
  bits proving whether the resolved `toString()` and Throwable localized-message
  targets return fresh, unescaped text. The localized-message bit is false for
  non-Throwable types.

Each allocated array type receives the same descriptor shape. Its membership
table contains `Object`, and its dispatch table maps inherited Object slots to
the root implementations. The array storage header is `{ descriptor pointer,
native length, element size, element kind, contiguous elements }`, so widening an array to `Object`, virtual
Object calls, and `instanceof Object` preserve one address and remain safe.
An array whose element is itself an array stores ordinary pointer slots and uses
the nested element descriptor as part of its own exact identity; allocating the
outer container never allocates any child.

`instanceof` lowers through the compiler-emitted internal
`ironwood.is_instance` LLVM helper. It returns false for null, otherwise loads the
descriptor and indexes its membership table. This helper is generated code, not
a reflection or runtime-class-loading facility. A named pattern adds no helper,
C ABI, metadata, runtime registry, or allocation; its successful binding is the
same tested pointer under a more precise static type.

Reifiable array tests and checked casts lower through
`IrArrayTypeTestInstruction` and a separate generated
`ironwood.is_exact_array` helper. It returns false for null and otherwise
compares the object's leading descriptor pointer with the exact target array
descriptor. Checked casts branch to the ordinary `ClassCastException` unwind
path on mismatch and preserve the original reference on success. No C runtime
ABI or array covariance/store-check mechanism is added.

The descriptor header, flat base-first layouts, global signature table, dense
type ids, and opaque pointers are bootstrap implementation details, not a stable
native ABI. The deallocation boundary may later refine allocation metadata while
preserving source reference semantics and object identity.

## Bootstrap native runtime

The runtime boundary lives separately under `runtime/include` and `runtime/src`.
For every native link, Clang compiles `ironwood_runtime.c` into its own object and
the final link combines that runtime object with the LLVM-generated program
object. Both objects are position-independent so the platform Clang driver can
use its native executable-linking defaults, including PIE-by-default Linux
toolchains. The C runtime exports:

- `ironwood_allocate`, a zeroing `calloc`-backed allocator that installs the
  compiler-provided descriptor and raises the compiler-provided immortal error
  on a catchable source-allocation failure;
- `ironwood_allocate_array`, which validates the length and allocation-size
  calculation, records the compiler-provided descriptor and element size/kind,
  and either returns zeroed array storage or raises the same immortal error;
- `ironwood_allocation_count`, which atomically snapshots the number of
  successful ordinary object, array, and runtime-created String allocations;
- `ironwood_identity_hash_code`, which is null-safe and observes allocation
  identity without dispatch;
- `ironwood_system_arraycopy`, which validates both runtime arrays, exact
  element compatibility, signed ranges, and copies with overlap-safe `memmove`;
- the U2/U5 whole-file read/write, metadata, directory iteration,
  current-directory, and lexical path helpers, which receive compiler-validated
  values, keep native handles and scratch storage private, return exact
  caller-owned language results, and report categorized failures for checked
  Ironwood exceptions;
- `ironwood_parse_float` and `ironwood_parse_double`, which receive
  grammar-validated UTF-16, normalize into bounded stack storage, and return
  primitive IEEE values without managed or Ironwood-owned native heap scratch;
- `ironwood_object_hash_code`, which returns a stable mixed identity hash without
  exposing the native address;
- `ironwood_object_to_string`, which creates the default qualified-name and
  lowercase-hex identity string;
- unchecked UTF-16 String helpers for code-unit loads, content equality,
  Java-compatible hash calculation, and one-allocation snapshots from char
  arrays; public bounds and exception semantics remain compiler-owned Ironwood
  control flow;
- `ironwood_string_concat`, which converts ordered compiler-typed parts, checks
  the exact UTF-16 length, and writes one ordinary immutable String allocation;
- `ironwood_deallocate`, which releases storage reached by compiler-validated
  free IR;
- `ironwood_stdout_println`, which encodes a String's UTF-16 tail to exact UTF-8
  (using U+FFFD for unmatched surrogates) or writes `null`, followed by a newline;
- `ironwood_throw`, which wraps a non-null language object in `_Unwind_Exception`
  and calls `_Unwind_RaiseException`;
- `ironwood_exception_take`, which transfers the language object from a landed
  exception and destroys only its native wrapper;
- `ironwood_exception_caught`, which releases active bounded allocation-failure
  state when the implicit error reaches source catch code;
- `ironwood_trace_register`, which receives immutable source-site and optimized
  function-address metadata once during native entry; and
- `ironwood_uncaught_exception`, which prints qualified type, optional message,
  and captured frames for the primary and its ordered secondaries, then exits
  with status 1.

Type descriptors, membership, dispatch tables, and the type-test helper are
compiler-emitted. The C runtime gains no class loader, reflection registry, or
dispatch linker. Exception objects remain ordinary Ironwood allocations and are
retained unless a later compiler-validated source `free` releases them or the
process exits. The Feature 105 singleton is the exception: its compiler-emitted
storage, bounded emergency trace, and required primary/secondary association do
not use or count as ordinary allocations. A second allocation failure while it
is active terminates deterministically. There is no automatic collection. The
native link uses Clang's C++ driver mode only to supply the platform personality
and unwind library; Ironwood objects are not C++ objects. Runtime discovery
searches the source checkout or packaged IDK root; `IRONWOOD_RUNTIME_HOME` can
select an explicit Ironwood root.

U2 library intrinsics lower first to `IrFileInstruction`, not directly to C or
LLVM text. That typed family distinguishes whole-file byte/String reads and
writes (including direct character-array snapshots), metadata, error retrieval,
current-directory lookup, fused absolute/sibling resolution, and lexical path
operations. Specialization, dependency scanning, pruning, invoke/unwind edges,
loose-class reconstruction, and archive linking preserve those operations
before the backend emits calls to the isolated runtime ABI.
Audited fresh path-helper results may initialize a path's owned String directly;
constructor rollback therefore reclaims only the final owner's completed
storage. File/path operations remain visible to allocation-effect analysis,
including native fallback allocation, and cannot bypass destructor restrictions.

`Float.parseFloat` and `Double.parseDouble` validate Java-shaped syntax in the
library facade, then lower the private conversion step to
`IrFloatingParseInstruction`. The typed operation borrows a String and returns
a primitive without allocation or an unwind edge; the backend alone selects
the float or double runtime entry point.

## Primitive control-flow lowering

Milestone 2's SSA representation remains unchanged. For example, loops still
lower through compiler-owned phis before LLVM emission:

```llvm
while.header.0:
  %v2 = phi i32 [ 0, %entry ], [ %v8, %while.body.1 ]
  %v4 = icmp sle i32 %v3, 5
  br i1 %v4, label %while.body.1, label %while.exit.2
```

LLVM local numbering and scratch pointers are emitter details; semantic types,
hierarchy, layouts, dispatch slots, normal/exceptional control-flow edges, field
identities, cleanup paths, source spans, per-function source identity, and phi
inputs are already explicit in Ironwood IR.
All Ironwood methods, including a source `main(String[] args)`, use
package-qualified internal LLVM linkage. Executable emission adds one native
`i32 @main(i32 %argc, ptr %argv)` wrapper. It asks the isolated runtime to
exclude `argv[0]`, decode the remaining native arguments from UTF-8 into
immutable UTF-16 Ironwood strings, and construct the typed `String[]` passed
to the selected entry method. The wrapper returns the Ironwood method's
`int` result and catches otherwise uncaught exceptions.

## Optimization levels

`ironwoodc` accepts `-O0`, `-O1`, `-O2`, and `-O3`, defaulting to `-O0`. The
selected level drives LLVM's `opt` default pipeline, `llc` machine-code
generation, and compilation of the bootstrap runtime object. For example, `-O3`
uses `opt -passes=default<O3>`, `llc -O=3`, and `clang -O3` for the runtime.
Because the whole closed-world program is one module, `-O3` also passes
`-inline-threshold=1000` and `-enable-partial-inlining` to `opt`: Java-shaped
code is call-heavy with many small accessors and early-return guards, and
LLVM's C-oriented default budget leaves them out of line. `-O2` keeps LLVM's
defaults. [IRONWOOD_PERFORMANCE_ADVANTAGES.md](IRONWOOD_PERFORMANCE_ADVANTAGES.md)
explains how these choices affect different application shapes.
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md) is the detailed
account of this configuration, of the cold-path outlining and guarded dispatch
described above, and of the measurements behind them.
After `opt`, the compiler finalizes the surviving function-address table and
reassembles the module. Linux additionally uses `llvm-objcopy` to map the LLVM
pseudo-probe section into the executable; Mach-O maps it directly.
During exception capture, the unwinder's function-start address is matched
exactly against that table. This preserves cold source frames when the linker
reorders functions and excludes runtime frames without relying on an end marker.
Native tests exercise the methods/control-flow, Java-width numeric, object,
inheritance/interfaces, exception/source-trace, safe-free, array, Object graph,
and string/I/O fixtures across the supported optimization levels. The trace
sites emit no executable instructions. LLVM may inline normally at every
optimization level without losing the source-call sequence, which the runtime
reconstructs only during capture from native return addresses and probe metadata.

## Command-line compilation, output, and versioning

`ironwoodc` accepts one or more `.iron` sources. `-sourcepath` and
`--source-path` select source roots; canonical `-cp` and its `-classpath` and
`--class-path` aliases select compiled classes. Ordinary compilation emits one
`.ironclass` for each top-level type. With no `-d`, those files are written
beside their source units; `-d <directory>` writes them below a
package-structured class-output root. Compilation accepts a source set with or
without `main` and does not discover LLVM.

`--link` selects the separate native executable mode. Link mode accepts no
positional source files and does not consult a source path. It loads only
`.ironclass` inputs, performs complete closed-world analysis and native linking,
and emits only the executable. `-o <path>` overrides the native output path and
is valid only with `--link`; without it, the selected main class's simple name is
written in the current directory. `--emit-llvm`, `--llvm-home`, and `-O0` through
`-O3` are likewise restricted to link mode. `-d` and `--source-path` are invalid
in link mode.

`--main-class <qualified-name>` is mandatory in link mode. It identifies both
the root class loaded to begin dependency discovery and the class supplying the
native entry method, so it must be discoverable on `-cp` (default `.`).
For example, `ironwoodc --link -cp build/classes --main-class
com.example.Main -o build/Main` links from compiled classes. Entry selection
affects only the native launcher, never the `.ironclass` representation.

`ironwoodc --version` and its `-v` alias print `ironwoodc <version>` and exit
before source loading or toolchain discovery. The repository root `VERSION` file
is authoritative for development and host-package builds. `scripts/build.sh`
embeds it as a JAR resource; release IDK packaging overrides the embedded value
with its validated tag-derived version. The source-checkout launcher rebuilds a
missing or stale compiler JAR, while packaged launchers always use their bundled
JAR.

## Build layout

- `compiler/src/main/java`: Java 21 bootstrap compiler
- `compiler/src/test/java`: dependency-free compiler test harness
- `integration-tests/cases`: source programs used in native end-to-end tests
- `runtime/include`, `runtime/src`: isolated bootstrap native runtime ABI and implementation
- `stdlib/src/main/ironwood`: initial tree-shakeable Ironwood library declarations
- `stdlib/src/testing/ironwood`: optional client-facing assertions and
  compiler-registered native test runner
- `stdlib/test`: native Ironwood standard-library behavior suites
- `scripts`: reproducible developer, test, and packaging commands
- `packaging`: pinned inputs for self-contained IDK archives

`scripts/build.sh` targets Java 21 bytecode, creates `ironwoodc.jar`, and compiles
the bundled library declarations into `compiler/build/stdlib`.
`scripts/check-licenses.sh` validates source SPDX expressions and requires
every OpenJDK-derived source path to appear in the provenance ledger.
`scripts/build.sh` emits the client-selectable `ironwood-testing.ironjar`
alongside the implicit production standard-library archive.
`scripts/test-stdlib.sh` compiles against that optional archive, verifies exact
runner output and process statuses, and runs the migrated pool and
data-structure cases at `-O3`.
`scripts/test.sh` runs that policy check and the current lexer, parser,
package/import, source-path, classpath,
hierarchy, semantic, scope, visibility, control-flow, object, dispatch, runtime,
exception, resource-cleanup, safe-free, array, text-block, string/I/O,
standard-library, tree-shaking,
generic, iteration, Java-width numeric/conversion, static-field/constant,
expression/operator, `instanceof` pattern-flow, archive, diagnostic, toolchain, CLI,
typed-IR, and native integration tests. Packaged
compiler and IDK archives include the runtime and the library sources/classes.
Package smoke testing covers the packaged milestone and post-milestone examples,
including native primitive-generic specialization,
caller-owned String-result reclamation, plus source discovery and `.ironclass`
classpath consumption using only bundled inputs.

## Toolchain discovery

Ironwood requires LLVM 23.x and verifies the version with `llvm-config`. It also
checks that `clang`, `llvm-as`, `opt`, `llc`, and `llvm-objcopy` are executable.
Discovery uses, in order:

1. `--llvm-home <directory>`;
2. `IRONWOOD_LLVM_HOME`;
3. `brew --prefix llvm@23`, then Homebrew's current `llvm` formula;
4. standard Homebrew and `/usr/lib/llvm-23` locations;
5. an LLVM 23 `llvm-config` found through `PATH`.

An explicitly supplied directory is authoritative: Ironwood reports why it is
invalid rather than silently selecting another installation. A missing tool or
wrong LLVM major becomes a compiler diagnostic.

The version-tag release jobs provide LLVM 23 through the pinned micromamba
environment on macOS and Ubuntu 24.04. Builds are host-native today; adding a
target triple, sysroot management, and cross-linking requires an explicit future
decision.

## Current architectural slice

Milestone 8 and the post-Milestone-8 source-level object-model completion are
complete. Features 69, 65, 61, 55, 76, 74, 51, 60, and 78 are implemented
under D055, D056, D057, D060, D061, D062, D063, D064, and D065; Features 100
and 66 are implemented under D074 and D075. Feature 74 retains optional bodies in
the enum AST,
discovers deterministic compiler-owned final subtypes, checks their members and
concrete obligations, and distinguishes the visible enum type from concrete
storage/runtime type in typed IR. The existing lexical-class layout,
initialization, dispatch, reachability, safe-`free`, and artifact-reconstruction
pipeline carries those hidden types through native output. The compiler
carries abstraction/finality, instance initialization and
field hiding, all `this`/`super` forms, nested/local/anonymous types and capture,
interface method kinds/default resolution, and the bounded inferred generic
model with native primitive specializations through semantic analysis, typed IR,
native execution,
devirtualization, safe-`free`, and artifact reconstruction. D047 removes
variable-arity applicability and packed-array lowering; all calls are
fixed-arity. It also carries automatic active-use static initialization through
synthetic typed-IR callables, explicit ensure operations, private native state,
artifact reconstruction, and tree shaking. The primary suite
includes classic integral/enum switch dispatch, enum singleton construction and
lookup, modern integral/enum/String/null switch and result phis, `-O0` through
`-O3`, safe-`free`,
allocation-free steady-state,
archive/tree-shaking, and native behavior. Existing local macOS ARM64
Milestone-8 host-package and self-contained IDK smoke results remain historical
facts; no new cross-platform release verification is claimed. D116/D118 provide
float/double StringBuilder append and insertion using the existing native
conversion; broader library families remain future work. D059
excludes runtime `String.intern()` as Feature 77, while D061 completes source
String concatenation as Feature 76. D060 completes recursive invariant arrays
and exact descriptor casts/tests. D063 completes primitive generic arguments
without boxing or runtime type-argument machinery. D064 lexes `@` separately
and recognizes the exact built-in `@Override` directive in method modifiers;
semantic analysis requires it on every declared inherited instance override or
interface implementation and rejects it when no such target exists.
D110 recognizes the exact built-in `@Test` directive, restricts it to eligible
`TestSuite` methods, and synthesizes ordinary typed-IR `run(int)` and native
`main(String[] args)` methods in declaration order. It adds no reflection,
runtime registry, or general annotation facility.
Feature 78 lexically normalizes cooked and raw text blocks into the established
pooled String-literal representation; it adds no typed-IR or runtime operation.
D066 audits the final Java SE 26 language surface as Features 79–100 in
`IRONWOOD_VS_JAVA.md`; it documents existing behavior and exclusions but makes
no compiler or architecture change.
D067 separates `volatile` from the other rejected Java modifiers as Feature
101 and classifies the remaining audit decisions; it likewise changes no
compiler or architecture behavior.
D068 separates the deliberately excluded `assert` statement from the still-open
Feature 90 as Feature 102; it changes no compiler or architecture behavior.
D069 commits Features 83, 92, and 100 as unranked pending work, confirms
Features 99 and 103 as excluded, and separates still-open Features 104 and 105.
It changes no compiler or architecture behavior and selects no implementation
target.
D070 commits Feature 105's bounded immortal allocation-failure design as
unranked pending work. The current allocator and exception lowering remain
unchanged, and no implementation target is selected.
D071 commits Features 86, 89, 90, and 101 as unranked pending language work.
The parser, name resolver, ownership analysis, control-flow lowering, and field
memory operations remain unchanged, and no implementation target is selected.
D072 separates reference type patterns for `instanceof`, non-pattern modern
switch, reference type patterns in switch, record/unnamed patterns, and preview
primitive patterns as Features 66, 91, and 106–108. It changes no compiler or
architecture behavior and selects no implementation target.
D073 commits Features 66 and 91, returns Feature 101 to excluded, confirms
Features 106–108 as excluded, and ranks the nine pending features 100, 66, 90,
92, 89, 83, 86, 105, and 91. The parser, semantic flow analysis, typed IR,
ownership analysis, and backend remain unchanged, and no implementation target
is selected.
D074 implements Feature 100 in semantic lowering, compiler-owned typed IR, and
LLVM exceptional CFG. The old null/bounds failure entry points are removed from
the C ABI; allocation, construction, throwing, trace capture, catch dispatch,
and cleanup reuse existing mechanisms. The remaining pending order is 66, 90,
92, 89, 83, 86, 105, and 91, with no subsequent target selected.
D075 implements Feature 66 in the parser, shared definite-match analysis,
dependency and capture scanning, semantic scope/SSA lowering, and safe-`free`
identity tracking. Nominal, reifiable-generic, and exact-array patterns reuse
the existing type-test IR and LLVM helpers, while format-1 artifacts reconstruct
the source rules at link. The remaining pending order is 90, 92, 89, 83, 86,
105, and 91, with no subsequent target selected.
D076 implements Feature 90 in the lexer/parser, shared statement walkers,
semantic scope/SSA analysis, and existing typed CFG. Array traversal reuses
typed array operations; `Iterable` traversal reuses ordinary calls and borrowed
producer-owned iterators; and transfers crossing `finally` reuse source cleanup
lowering. Format-1 artifacts reconstruct the source rules at link. The remaining
pending order is 92, 89, 83, 86, 105, and 91, with no subsequent target selected.
D077 implements Feature 92 in catch parsing, dependency and capture scanning,
semantic checked-exception flow, and existing typed exception dispatch. Union
alternatives reuse nominal type tests and boolean IR; precise rethrow retains
the exact reachable checked types for final/effectively-final catch parameters.
Format-1 artifacts reconstruct the source rules at link. No runtime or native
ABI changes are required. The remaining pending order is 89, 83, 86, 105, and
91, with no subsequent target selected.
D078 implements Feature 89 in declaration and array-creation parsing, shared
expression walkers, contextual type checking, typed array allocation/store
lowering, and safe-`free` provenance. Format-1 artifacts reconstruct nested
initializers at link, and the existing LLVM/runtime array boundary lowers them.
The remaining pending order is 83, 86, 105, and 91, with no subsequent target
selected.
D079 implements Feature 83 in numeric tokenization and shared integer-constant
decoding. Typed constants, static folding, switch labels, invocation typing,
format-1 reconstruction, and LLVM lowering reuse their existing integer paths.
The remaining pending order is 86, 105, and 91, with no subsequent target
selected.
D080 implements Feature 86 in import parsing, dependency discovery, type and
member lookup, static constant evaluation, lvalue lowering, and ordinary
invocation planning. Source, class-directory, individual-class, archive, and
explicit-link flows reconstruct the same rules. No runtime, native ABI,
allocation, ownership, or new typed-IR operation is added. The remaining
pending order is 105 and 91, with no subsequent target selected.
D081 implements Feature 105 by retaining `OutOfMemoryError` as an implicit
dependency of allocation-capable closed-world code, recording one immortal
failure object in `IrProgram`, lowering allocation-producing instructions as
invoke operations when a local handler exists, and marking source catch entry
to release emergency runtime state. The LLVM/runtime boundaries receive exact
type and failure descriptors, install headers inside successful allocators, and
use bounded allocation-free unwind/trace/association storage on failure.
Source/class/archive/tree-shaken programs reconstruct the same behavior.
Feature 91 is the sole remaining pending feature; no subsequent target is
selected.
D082 implements Feature 91 in the lexer/parser, shared AST walkers, semantic
constant and exhaustiveness checking, cleanup-aware result flow, and existing
typed CFG. Integral and enum selection reuse `IrSwitchTerminator`; String
selection reuses pooled literals and `IrStringEqualsInstruction`; result values
merge through ordinary typed phis. Source/class/archive programs reconstruct
the same rules. No runtime or native ABI changes are required, no numbered
feature remains pending, and no subsequent target is selected.
Cross-platform Milestone 8 CI is not yet claimed.
