# Compiler architecture

## GC5 memory-model supersession

The migration branch now uses [the GC5 managed memory contract](MEMORY.md).
Canonical Memory.collect() is the only reclamation point. There is no source
free, destructor, implicit temporary destruction or managed constructor rollback.
General defer, initialization, native resource and pool protocols remain.
Historical proof discussions and examples mentioning those removed forms are
superseded by the completed GC7 consumer migration, not alternate supported semantics.
Managed bridge output requires [GC6 admission and isolated images](GC6_IMPLEMENTATION.md),
root ABI 3 / owner ABI 2 and schema 2. macOS arm64 and Linux x86-64 are qualified.
Ordinary executables retain root ABI 2 / owner ABI 1. The
[completed GC9 qualification](GC9_LINUX_X86_64_DISTRIBUTION.md) covers all three
official targets and native/managed-image distribution. arm64e stays unsupported.
Historical manual bridge ownership descriptions are superseded by
the GC6 handle/registration projection.

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
  -> final name, visibility, conversion, type, effect, native/resource and pool validation/lowering
  -> compiler-owned typed SSA/control-flow, hierarchy, exception, array, I/O and collection IR
  -> closed-world primitive-generic shape and callable specialization
  -> LLVM text emitter
  -> native-link target triple and data layout from configured Clang
  -> llvm-as (IR validation and bitcode assembly)
  -> opt (LLVM optimization pipeline)
  -> optimized trace attribution and required nonmoving statepoints
  -> final strategy/stack-region verification and llvm-as
  -> llc plus retained exact root sections and image map contract
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
`AnonymousDiamondBodyInference` uses the existing invocation planner on the
containing expression to establish the diamond arguments used for body checking.
It preserves constructor-inference templates and checks the selected allocation
against the body's inferred arguments before lowering. Failed inference grants
no type or ownership exemption; ordinary validation still rejects the source.
This staged binding never guesses a globally unique simple member name and
preserves the ownership-safe evaluation order: explicit enclosing receiver
once, immediate null check, source constructor arguments, allocation, then
construction. Feature 105 retains that ordering for catchable allocation
failure rather than adopting Java's earlier allocation attempt.

The isolated [GC3 development closure](GC3_ROOTS_AND_STATEPOINTS.md) additionally
admits compiler-owned collection IR, recomputes transitive collection effects,
preserves managed references and stack regions, and inserts statepoints after
optimized trace metadata in every optimization mode. It uses the same typed-IR,
LLVM and native object pipeline. Its exact-root reader and GC2 allocator are
linked only by the dedicated development harness; ordinary programs keep the
production pipeline and mandatory reclamation proofs above.

The [GC4 lifetime closure](GC4_RUNTIME_LIFETIMES.md) adds exact internal Throwable
references, native owner protocol 1 and root ABI 2 on macOS arm64 and Linux x86-64.
A small original LLVM-API pass preserves statepoint roots while resolving
exceptional relocations as identities for the nonmoving heap, before verification. Production emission
and the GC3 pipeline are unchanged. Unsupported owner coverage, legacy layouts
and foreign segments refuse admission. Linux arm64 remains unqualified. See the
separate [Linux x86-64 record](GC4_LINUX_X86_64_QUALIFICATION.md). On Linux the
host-only pass build explicitly selects installed GCC development headers with
`--gcc-toolchain=/usr`; generated code and the C runtime retain the pinned SDK.
These are historical GC4 limits. GC9 implements the Linux arm64 reader and
prepares the unchanged pass in IDKs with verified source, payload and host
identity. Relocated IDK links load that pass without host C++ headers. Mach-O
root maps reside in dyld-protected `__DATA_CONST`; startup requires function
starts to delimit the exact native boundary even when compact unwind coalesces
adjacent C functions.

## Managed Java Bridge producer

The closed-world producer admits only schema 2 / objects-gc-v1 images. Generated
Roots constructors and reference-returning methods provide RootHandle values;
value() reads a live facade and close() releases its anchor. Source close methods
remain ordinary resource operations. ListenerRegistration owns JNI listener
registrations separately from facade anchors. Collection does not close either
source resources or JNI registrations.

Managed admission uses exact final layouts, roots and suspended segments.
Borrowed ByteViews and copied input staging retain their confinement proofs;
unknown retaining or foreign effects refuse. Exception snapshot getters keep
managed String results rooted and confine receiver graphs that may carry an
invocation-owned JVM exception carrier. JNI cleanup completes explicitly on
success, exception and JVM reentry paths. Image identity prevents mixed or stale
payloads. The single-caller/external-serialization contract remains mandatory.

Finite generic exports, erased/wildcard Java views, arrays, primitive/String
values, callback dispatch and exact listener-slot updates retain the GC6 API.
Source/class/archive inputs reconstruct the same metadata. Generated package
identity, companion source/notices, private image exports and fail-closed loader
checks remain part of production artifacts. Legacy scalar/permanent/owned-root
projections and their proof/cache machinery are retired.

macOS arm64 and Linux x86-64 remain the supported gates. Linux arm64, arm64e,
unsupported asynchronous lifetimes and automatic collection remain excluded.
See [GC6](GC6_IMPLEMENTATION.md), [GC7](GC7_IMPLEMENTATION.md), and
[the bridge guide](JAVA_BRIDGE.md).
The separate [GC8 report](GC8_IMPLEMENTATION.md) records actual O3 callers,
critical/JNI/callback timing, collection-boundary work and repeated resident
graphs on native macOS arm64. The separate
[Linux x86-64 record](GC7_GC8_LINUX_X86_64_QUALIFICATION.md) adds current physical
GC7/GC8 checks and an exact named configuration/sysroot-receipt profile alongside
the earlier accepted profile. Unknown or mixed pairs still refuse. These records
do not extend target or distribution gates.

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

The ZIP entry method is a writer profile, not part of the format (D275). The
Java bootstrap writes `.ironclass` entries DEFLATED and `.ironjar` entries
STORED; the compiler port's native writers store every entry, spelled as
Java's ZipOutputStream writes a STORED entry with time 0, so a native archive
built from the same class files equals the Java one byte for byte. Every
reader accepts both methods, including DEFLATED class payloads inside a STORED
archive, through the compiler-private RFC 1951 decoder in the port; no zlib is
linked for archives. The frozen container contract is
[the M5.1 record](self-hosting/m5/ARCHIVES.md); the port's IronClass and
IronJar services that keep each profile's Java checks and messages, and the
standard-library discovery they serve, are recorded in D276. The port's Bridge
JAR writer (D294) stores entries the same way, the manifest first, verifies
the staged jar and publishes it with an atomic replacement; identities over
entry contents match the Java bootstrap's DEFLATED jars, while identities over
whole jars, such as the values companion's, differ.

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

After final-link reachability pruning, `UnreadFieldStoreEliminator` removes
stores to primitive instance fields with no retained typed reader. Storage
identity uses the declaring class and layout slot, including inherited and
primitive-specialized fields. Runtime-owned String, Throwable and PrintStream
layouts and fields whose addresses reach TCP/native operations are excluded.
The pass leaves reference/static stores, layouts, receiver checks, operand
evaluation, exceptional edges and source identities intact. Ownership and
exact GC/native verification run on the original program; class/archive inputs are
revalidated before this final-link optimization. See D176.

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
  object/array allocation and explicit synchronous collection,
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
child-array stores, so loads, detachment, escape, and reference provenance reuse the
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
traverse the new forms before LLVM lowering. Foreign/native effects remain conservative
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

Instance-field loads and stores carry no field-specific TBAA metadata. A
declaring-owner/name alias experiment was rejected after bounded Mac and Linux
comparisons failed to establish a repeatable performance benefit (D173).
No receiver `noalias` or final-field invariant metadata is inferred. Construction,
recursive initialization, mutable referenced contents and lifetime boundaries
still constrain optimization. The experiment and retained behavioral regressions
are recorded in [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md).

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

After semantic, ownership, effects and primitive-generic validation,
`InitializedTypeSpecializer` can version a bounded, profitable loop-containing
function or explicit native-library export root and its direct callees. Export
roots receive priority so their callees share the entry facts (D223).
`IrTypeInitializedInstruction` only reads whether a type is in state 2; it never initiates initialization. Entry guards
select a copied fast CFG or the unchanged original body for states 0, 1 and 3.
The fast CFG omits ensures of the proven types and calls guardless internal
callee clones under the same facts. State 2 is permanent in the current
synchronous language; successful ordinary ensures do not imply that state.
Final compiler-owned enum fields can become direct immortal object addresses
only with this proof and a verified single publication of that same object by
the declaring initializer. Mutable fields retain loads. Clones preserve source
identity, source spans and exceptional CFGs; root versioning adds no wrapper
trace frame. See D171 and the bounded policy in
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md#37-guarded-fully-initialized-specialization).

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
immutable aliases and participate in escape summaries and reference provenance. A class
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
  -> one ordinary immutable String allocation with exact managed layouts
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
constructor borrow edges and factory allocation snapshots preserve pool/foreign origins
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
Library inputs without an entry point are unknown. Empty receiver flow falls
back to the full compatible target set. Actual call-site targets refine
conservative escape, return-origin, pool and foreign effects. Final lowering
reruns declaration and protocol diagnostics and emits GC-managed IR.
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
the default localized getter delegates there. A deferred helper call ensures cleanup
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
exception and collection-effect analysis. Buffer bounds come from the stream's
private buffer/count invariant. Ordinary source dispatch and D089/D111 rendering
consumption still apply; no new descriptor ownership bit is needed (D115).

D117 adds explicit String case/repeat/replace/join instructions and reuses the
UTF-8 decode instruction for `String(byte[])`. Allocation instructions carry
catchable failure effects through generic specialization, pruning, borrowing,
collection effects and LLVM call/invoke lowering. Nonallocating
`IrStringEqualsIgnoreCaseInstruction` calls the fixed Unicode helper. Case
conversion has only a source and upper/lower flag, with no locale operand.
CharSequence replacement and join refine borrowing only when all reachable
rendering callbacks lack non-return receiver escapes. Native compilation also
builds the isolated, derived Unicode helper and removes unreachable C functions
and data at the final link. The pruner retains a virtual/interface dispatch entry
only when a reachable call uses its slot, preserving slot indices and revisiting
already-reachable receiver types when a new slot is discovered. Direct calls,
exceptional edges and initialization retain their required roots. This keeps
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

## Reference effects, collection and native protocols

SemanticAnalyzer binds types/callables, computes actual dispatch/reference
summaries and lowers functions without the former field/return/temporary
free-proof refinement loop or relowering from reclaimed arguments. The compiler
has no FreeStatement, DeferredFreeStatement, DestructorDeclaration, managed
release/deallocation/rollback instructions, missing-free modes or witnesses.

Escape and symbolic-return summaries retain conservative fixed points needed
by pool builders and native/foreign confinement. The exclusive/private-field
publication checker and copied/detached-element proof passes are removed.
FinalFieldOrigins uses final instance-reference metadata after definite
initialization to name stable value-flow edges; it grants no nonretaining effect.
Function
protocol state follows pool origins and contained references across aliases,
phis and exceptional edges. It grants no authority to reclaim managed objects.
Unknown targets and effects remain conservative.

GcEffects computes MayCollect through actual dispatch and initialization. Exact
layouts, late nonmoving statepoints and root verification protect collecting
operations. Ordinary executable root ABI 2 / owner ABI 1 and managed bridge root
ABI 3 / owner ABI 2 remain unchanged. Physical descriptor tail slots remain
reserved null/false ABI padding; no ownership or cleanup dispatch is represented.
Final verification rejects raw legacy managed-deallocation calls even without a
source collection. Source/class/archive reconstruction rechecks current semantics.

Initialization, null guards, exception ordering, native acquisition rollback,
foreign confinement and pool release protocols remain independent analyses.
See [GC7](GC7_IMPLEMENTATION.md) for their consumer map and verification.

## Exception and cleanup lowering

Exceptions, general defer and finally lower to typed blocks, locals, invoke
edges and landing pads. Deferred receivers and arguments are evaluated once at
registration and replayed in LIFO order at each supported exit. There are no
heap action lists, source cleanup callbacks or deferred-free actions.

Pending return/yield values, exception objects and already evaluated call operands
remain live through cleanup. GcEffects and exact late statepoints protect them
when any cleanup path may collect. Constructor failure leaves a zero-initialized
managed graph, including published partial aliases. Native acquisition rollback
and JNI completion still run explicitly where required by their protocols.

D051 preserves a pending primary exception and appends later cleanup failures in
execution order. Independent deferred calls continue after cleanup failure;
ordinary statements need nested finally where later operations must run. Catch
and rethrow preserve object identity, source frames and ordered secondary links.

Source traces use immutable native site/function metadata. Managed Throwable
metadata retains message/cause/trace/secondary graphs with exact layouts.
Capture, refresh and printing keep their existing allocation/failure contracts;
Memory.collect() reclaims unreachable metadata and never invokes source cleanup.
The reusable allocation-failure singleton retains its bounded emergency path.

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

FunctionAnalyzer omits a null check dominated by a null/type test or an earlier
check of the same SSA value, reference conversion or stable final-field load.
Constructor final loads remain conservative until initialized. The finished CFG
verifies every omission. RedundantNullChecks then computes genuine non-null
facts over predecessor edges and phis, including loop back edges. Stores end
field facts; allocations are non-null. Unreachable failure blocks and their phi
entries are removed. These optimization fixed points remain independent of GC.

The former ConstructedFields/ReentrantOperations destructor-only proof is gone.
Ordinary methods keep checks needed during partial construction; no global
post-construction non-null premise is substituted for the actual CFG proof.

Both allocators are declared `noalias nonnull`, the receiver parameter of every
instance callable is `nonnull noundef`, and `ironwood_throw` is `noreturn cold`,
so LLVM folds redundant null checks on fresh objects and receivers and lays out
throwing blocks as cold code. `String.charAt` reads the UTF-16 unit directly
from the runtime string layout rather than calling the runtime.

Failure paths are outlined. A block that allocates an exception, runs its
constructor and throws with no local handler, and the lowering of
`throw new X(...)` outside any local handler (type-initialization barrier,
allocation, constructor invoke with an ordinary exceptional edge, and throw, with or
without the null check of the fresh object that D288 omits), each become one
call to a shared `ironwood.throw.<constructor>` helper marked
`cold noinline noreturn`, followed by `unreachable`. The helper takes the
constructor arguments, performs the same allocation, construction, exception propagation and
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
  class-versus-array kind tag, reserved ABI slots and closed-world
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

At the final native-link boundary, the configured LLVM 23 Clang emits an empty
C translation unit with the runtime's CPU and optional TLS SDK/deployment flags.
The backend takes its target triple and data layout and attaches both to a
temporary program module before `llvm-as` and `opt`. Assembly, optimization,
trace-metadata finalization and `llc` therefore agree on field offsets, allocation
sizes and implicit load/store alignment. Runtime compilation and the final
Clang link use the same triple. This includes the TLS SDK's minimum macOS
version without a separate late code-generation override (D172).

Class files, archives and `--emit-llvm` output remain target-neutral inputs to
this boundary. Native object layout is selected anew for each final link;
default CPU selection and `-march=native` remain distinct. No host layout is
hard-coded, and this does not add a cross-compilation option or a stable native
object ABI. LLVM's generic layout is not a portable optimization layout: it can
fold different offsets before the native target is selected.

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
  managed language results, and report categorized failures for checked
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
retained by exact roots and reachable managed graphs until explicit collection. The Feature 105 singleton is the exception: its compiler-emitted
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
Path and whole-file results use ordinary managed lifetime. Native acquisition
failure still closes native scratch/resources. No managed destructor or rollback
restricts publication of the resulting graph.

M4 adds `IrFileInstruction` operations for exclusive temporary files and
directories, real paths, access checks and the three publication moves (D270,
D271), with the same audited borrowing and fresh-result classification. A
temporary or real path is adopted inside its UnixPath constructor, so no
managed allocation follows the native creation. `ProcessRunner.runToFile`
lowers its private launch to `IrProcessInstruction` (D272): an I64 status from
a String[] command, a nullable directory String and an output String, emitted
as `ironwood_process_run` with an unwind edge for argument-encoding allocation
failure and counted as a closed-world effect, so an unused result still
launches. Two audited contracts admit the command's elements: by exact
signature, `runToFile` borrows the array's Strings without exposing them, and
the creation-array proof (D163) lets an owner lend its storage to that one
call (D273). Every other call keeps the conservative array rules.

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
By default, because the whole closed-world program is one module, `-O3` also passes
`-inline-threshold=1000` and `-enable-partial-inlining` to `opt`: Java-shaped
code is call-heavy with many small accessors and early-return guards, and
LLVM's C-oriented default budget leaves them out of line. `-O2` keeps LLVM's
defaults. [IRONWOOD_PERFORMANCE_ADVANTAGES.md](IRONWOOD_PERFORMANCE_ADVANTAGES.md)
explains how these choices affect different application shapes.
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md) is the detailed
account of this configuration, of the cold-path outlining and guarded dispatch
described above, and of the Linux benchmark guidance for evaluating them.

On x86-64, the default target machine also passes `-mattr=-slow-unaligned-mem-16`
to `opt` and `llc` at every optimization level, for executables and shared
images alike (D242, D244). LLVM's baseline x86-64 model otherwise assumes slow
unaligned 16-byte memory access, an assumption that holds only for processors
older than SSE4.2/SSE4A, and zeroes or copies adjacent fields one 8-byte word at
a time. The argument selects no instruction-set extension: the image stays
baseline x86-64 and uses SSE2's unaligned 16-byte moves, so the raw
`--emit-llvm` module and the optimizer's decisions are unchanged and only
instruction selection differs. `-march=native` passes `-mcpu=native` instead and
takes the host processor's own tuning. ARM64 targets receive no tuning argument.
The Clang-compiled runtime objects are not tuned.

Within existing initialized-state fast paths, the compiler folds proven
enum `int`/`long` final-field reads and pure accessor calls on exact receivers.
It proves literal construction and publication from typed IR, preserves final
metadata through generic rebuilding, and declines unsupported constructor CFGs,
delegation, mutable fields, native address exposure and constant-specific bodies.
Original initialization and fallback code remains. No new guards or runtime
bookkeeping are added. This is accepted as a small OrderBook latency gain,
without an established throughput or extreme-tail gain; see D177 and
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md#37-guarded-fully-initialized-specialization).

After initialized-state specialization, bounded enum-argument specialization
clones direct callees for already-proven constant enum identities. It retains
all evaluated arguments, signatures, initialization guards, mutable field loads,
exception edges and source traces. Dynamic arguments keep the original callee.
This typed-IR transformation precedes LLVM and does not force inlining. See D174.

Exact field value forwarding runs next, after all semantic and ownership
validation. It reuses integer/reference field values for exact
receivers along single-predecessor paths and models bounded leaf getters/setters.
Possible same-slot alias writes invalidate facts; unknown effects, initialization,
reclamation, joins and exceptional edges discard them. Stores, operand evaluation,
checks and layouts remain. This is retained for measured OrderBook latency and
throughput gains in an independent Linux comparison against `9217418`; combined
performance with enum-field propagation remains unmeasured. See D178 and
[PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-retained-field-value-forwarding).

The compiler also uses a typed structural planner to mark selected medium-sized
loop methods `alwaysinline`. Eligibility
requires small direct callers, finite body/site/growth budgets, no recursion in
the direct-call graph and no selected function reaching another selected function;
dispatch and lifecycle entries are
excluded. It preserves instructions, guards, signatures and trace metadata and
can apply at O0 as well as O3. Both Stage 3 passes are retained together in the
working compiler after focused Mac/Linux checks and measured improvements.
Their expected performance benefit is an engineering judgment, not a guarantee
that every application becomes faster.

Three link-only controls expose the profitability policy:

- `--inline-threshold <integer>` sets LLVM's ordinary inline budget, from 0 to
  2147483647. Without it, O3 uses 1000 and other levels use LLVM defaults.
  It does not change the selected optimization pipeline, force a call to inline,
  or override `alwaysinline`. Zero does not mean all inlining is disabled.
- `--selective-inlining=on|off` defaults to `on`. `off` disables the structural
  planner's annotations, leaving LLVM's ordinary inliner, enum specialization
  and existing initialization-helper inlining active. It changes final link
  decisions, so compiled classes and archives can be relinked without rebuilding.
  The structural loop-body bound is 256 operations for executables and 512 for
  native libraries. The larger library bound exposes medium loops through small
  callers when an outer application loop is unavailable to native optimization.
  Direct-call, recursion, lifecycle and export-entry exclusions remain unchanged
  (D222); no safety check or trace metadata is removed.
- `--partial-inlining=on|off` controls LLVM's partial-inlining pass independently
  of the threshold and selective-inlining policy. Omission preserves the existing
  defaults: enabled at O3, LLVM's default at O0/O1/O2. Explicit `on` or `off`
  passes `-enable-partial-inlining=true` or `=false` to `opt`; it does not change
  the optimization-level pipeline or guarantee that a function is partially
  inlined. `off` leaves ordinary inlining and compiler-selected `alwaysinline`
  attributes active. Like the other controls, this is a per-link setting, not
  metadata stored in compiled classes or archives.

The optional link-only `--optimization-report <file.yaml>` saves LLVM `opt`
optimization remarks in YAML. It records the remarks emitted by the selected
pipeline, including successful and missed inlining decisions and available
analysis remarks. It does not add optimization passes, enable profiling, change
inlining defaults, or include the separate runtime C compilation or `llc`
code-generation remarks. Reports are not a complete explanation of every
surviving check or missed optimization. LLVM function names and available source
locations are preserved as emitted; the schema and contents follow LLVM 23.

Reporting is disabled by default. Enabling it adds report-generation and file-I/O
cost during linking, not runtime instrumentation. The report is a separate file;
remark metadata sections are explicitly disabled. The compiler creates missing
parent directories and overwrites an existing regular report file. The report
must differ from the executable and `--emit-llvm` output, including aliases.
Missing/invalid arguments and compile-only use are usage errors; directory,
unwritable, or otherwise unusable output paths fail the link with a diagnostic.
A report can be empty when no remarks are emitted, and a report left by a failed
link does not indicate a successful build. Omitting the option preserves the
existing tool invocation and output behavior.

Raising the global budget affects more calls than the selective policy. Its
default remains 1000; an individual rejected call's cost is not a new default.
For explicit native libraries, initialized-type specialization prioritizes export
roots, including non-looping roots, so direct callees share a guarded context.
The original cold, recursive-initialization and failure paths remain intact;
only proved complete state permits the specialized path (D223).
See D174/D175 and the
[Stage 3 report](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-3-independent-specialization-and-inlining-candidates).

After `opt`, the compiler finalizes the surviving function-address table and
reassembles the module. Linux additionally uses `llvm-objcopy` to map the LLVM
pseudo-probe section into the executable; Mach-O maps it directly.
During exception capture, the unwinder's function-start address is matched
exactly against that table. This preserves cold source frames when the linker
reorders functions and excludes runtime frames without relying on an end marker.
Native tests exercise the methods/control-flow, Java-width numeric, object,
inheritance/interfaces, exception/source-trace, managed reference, array, Object graph,
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

Removed --unfreed, --explain-rejected-free and @SuppressUnfreed forms receive
concise migration diagnostics. No option changes the memory model, and no
missing-free tracker, suppression flag, explanation witness or diagnostic
refinement pass remains. Ordinary free/destructor names use normal lookup.
Diagnostic severity, primary/related source spans and embedded-source paths
remain available for current language, initialization and native/protocol errors.

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

`ironwoodc --version` and its `-v` alias print `ironwoodc <version>`, followed by
`LLVM version: <version>`, `LLVM home: <absolute directory>`,
`LLVM clang: <absolute executable>`, and `Clang version: <version banner>`.
The Clang banner is the first line of the selected executable's `--version`
output, preserving vendor and revision information. If that query fails or
returns no output, its line reports `Clang version: unavailable: <diagnostic>`.
They use the same toolchain discovery as native linking, including the
launcher's bundled IDK override. If discovery
fails, they instead print `LLVM not found: <diagnostic>` after the compiler
version. Both commands exit successfully without loading sources, even when
LLVM is unavailable. The labeled LLVM lines are also consumed by the OrderBook
C++ scripts to select the same Clang executable without duplicating discovery.
The repository root `VERSION` file is authoritative for development and
host-package builds. `scripts/build.sh`
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
runner output and process statuses, and runs the pool, data-structure,
benchmark and networking Milestone 1 through 5 suites at `-O3` with
the managed model. Each suite runs in a fresh native process; networking uses
loopback sockets and read-only host queries, without live reachability probes.
The runner's final native link requires the optional SDK described in [TLS.md](TLS.md).
`scripts/test.sh` runs that policy check and the current lexer, parser,
package/import, source-path, classpath,
hierarchy, semantic, scope, visibility, control-flow, object, dispatch, runtime,
exception, resource-cleanup, managed reference, array, text-block, string/I/O,
standard-library, tree-shaking,
generic, iteration, Java-width numeric/conversion, static-field/constant,
expression/operator, `instanceof` pattern-flow, archive, diagnostic, toolchain, CLI,
typed-IR, and native integration tests. Packaged
compiler and IDK archives include the runtime and the library sources/classes.
Package smoke testing covers the packaged milestone and post-milestone examples,
including native primitive-generic specialization,
managed String results and explicit phase collection, plus source discovery and `.ironclass`
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
environment on macOS and Ubuntu 24.04. Linux release toolchains use a controlled
glibc 2.17 sysroot, so generated executables run on glibc 2.17 and newer.
Release smoke tests reject a newer GLIBC symbol requirement. Builds remain
host-native for their operating system and architecture; user-selectable target
triples, sysroots, and cross-linking remain deferred.

## Current architectural slice

Milestone 8 and the post-Milestone-8 source-level object-model completion are
complete. Features 69, 65, 61, 55, 76, 74, 51, 60, and 78 are implemented
under D055, D056, D057, D060, D061, D062, D063, D064, and D065; Features 100
and 66 are implemented under D074 and D075. Feature 74 retains optional bodies in
the enum AST,
discovers deterministic compiler-owned final subtypes, checks their members and
concrete obligations, and distinguishes the visible enum type from concrete
storage/runtime type in typed IR. The existing lexical-class layout,
initialization, dispatch, reachability, reference provenance, and artifact-reconstruction
pipeline carries those hidden types through native output. The compiler
carries abstraction/finality, instance initialization and
field hiding, all `this`/`super` forms, nested/local/anonymous types and capture,
interface method kinds/default resolution, and the bounded inferred generic
model with native primitive specializations through semantic analysis, typed IR,
native execution,
devirtualization, reference provenance, and artifact reconstruction. D047 removes
variable-arity applicability and packed-array lowering; all calls are
fixed-arity. It also carries automatic active-use static initialization through
synthetic typed-IR callables, explicit ensure operations, private native state,
artifact reconstruction, and tree shaking. The primary suite
includes classic integral/enum switch dispatch, enum singleton construction and
lookup, modern integral/enum/String/null switch and result phis, `-O0` through
`-O3`, reference provenance,
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
runtime registry, or general annotation facility. D141 makes the generated
entry point retain its runner through finish() and return the status.
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
dependency and capture scanning, semantic scope/SSA lowering, and reference provenance
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
lowering, and reference provenance provenance. Format-1 artifacts reconstruct nested
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


## Typed TCP foundation

IrTcpInstruction represents separate descriptor creation, bind/listen,
connect/accept attempts, completion, scalar/bulk I/O, readiness, options,
endpoint queries, availability, shutdown, close, resolver-list acquisition,
iteration/release, reverse/local names, literal scope lookup and family defaults,
interface capture/iteration/release, live interface status and reachability.
Private TcpNative declarations
are matched by exact owner/signature during typed analysis. Arrays receive
mandatory bounds/null checks and are borrowed for one operation. Primitive
status plus captured native error flows through source exception construction.
The typed instruction survives specialization/pruning and is reconstructed from
class/archive source before LLVM lowering; ordinary source never sees pointers.
Resolver handles/cursors use typed 64-bit fields; counts, scope IDs and address
words use 32-bit fields. Output pointers are compiler-selected, without a C
dependency on managed query layout. NativeBackend builds the original
ironwood_tcp.c and ironwood_host.c separately from the core and casing runtime.
M5 adds distinct `IrTlsInstruction` operations. `NativeLinkRequirements` scans
only the final pruned program and is passed explicitly to NativeBackend. A retained
TLS operation selects `ironwood_tls.c`, the validated static OpenSSL SDK and
pinned CA input; class-only and pruned/plain links do not discover those inputs.
The cache includes runtime headers, component arguments and SDK build identity.
No runtime feature lookup is introduced. See [TLS.md](TLS.md).

Dispatch and reference summaries converge over actual closed-world targets and
exception paths. Unknowns remain conservative. Narrow field confinement, return
origin and pool helper facts retain only their actual native/foreign/protocol
consumers; there is no managed-reclamation refinement loop. Initialization guards
still follow superclass/default-interface prerequisites and exact CFG facts.
