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
  -> native-link target triple and data layout from configured Clang
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

## Java Bridge producer and analysis foundations

The `ironwoodc --java-bridge` producer composes scalar/String,
proved copied primitive arrays and borrowed byte views, permanent-object,
root/view, bounded-retention and bounded callback protocols on macOS ARM64 and
both Linux targets. The
[P3 audit](JAVA_BRIDGE_P3CD_EVIDENCE.md) and [P4 OrderBook audit](JAVA_BRIDGE_P4_EVIDENCE.md)
record their implementation gates. P6 implementation was accepted under D225;
P5, P7b and P7c implementation and qualification are complete. The
[array evidence](JAVA_BRIDGE_ARRAY_EVIDENCE.md) and
[byte-view evidence](JAVA_BRIDGE_BUFFER_EVIDENCE.md) record supported boundaries
and measurements. P7d0 foundations and P7d1 read-only generic facade admission
are implemented; P7d2 bounded construction/mutation is implemented and qualified
on all three targets, with evidence in the generic log. P7e-P7f remain pending.
[The implementation plan](JAVA_BRIDGE_PLAN.md) defines the
phase gates, and [the progress log](JAVA_BRIDGE_PROGRESS.md) records evidence.

`BridgeApiFacts` preserves complete ordered type-variable bounds, declaration
erasure, implicit-bound primitive eligibility, applied owner views and generic
member signatures. `BridgeCallableId.SourceSignature` identifies the source
contract separately from its optional exact typed native target. Signature proofs
bind the complete identity and final program, without granting export or lifetime
permission. The inventory's native candidates remain available to specialized
dispatch proofs; direct bindings additionally require the exact applied receiver.
Facts match their producing program instance: source-only bound
changes can leave lowered IR equal. Artifact reconstruction reprojects facts and
revalidates signatures. P7d1 uses these proofs for final factory-produced generic
facades. `BridgeGenericDomain` closes the exact allocation/signature domain;
source construction facts independently project finite variable alternatives.
Only matching facts narrow exposed variable types, while deallocation effects
remain conservative. Reference applications share native storage identity for
root ownership and destruction; source signatures stay exact. Unknown result
origins and publication require family-wide D192 non-reclamation proofs.
Cold result conversion uses the actual native type ID, with no generic-specific
work on the warmed getter path. P7d2 admits inputs and constructors only when
every class variable has one exported final facade bound. These declarations
prove a singleton domain even without native factory allocations. The surface
admits exact variable/application inputs, Java static native formals use their
erasures, and existing root conversion/retention uses the proved storage type.
No general semantic ownership analysis or runtime protocol is weakened.
Unrestricted generic inputs and generic methods remain refused.
See [D235](DECISIONS.md#d235---preserve-exact-generic-source-identities-before-bridge-admission),
[D236](DECISIONS.md#d236---read-only-factory-produced-generic-java-facades),
[D237](DECISIONS.md#d237---final-bounded-generic-construction-and-inputs)
and the [generic verification log](JAVA_BRIDGE_GENERIC_PROGRESS.md).

`BridgeArrayInputs` combines final P0 borrowing, retention, result-origin and
non-reclamation facts with complete typed effect closure. A descriptor alone
cannot admit an array export. Protected `IrBridgeArrayCopyInstruction` lowering
constructs valid native arrays; generated adapters coalesce Java input identities
and reclaim conversion storage. `BridgeArrayValueSources` emits ordered mutable
copy-back and fresh/alias-result delivery, preserving the primary failure even
when diagnostic aggregation fails. These protocols add no array state to
unrelated scalar entries.

`BridgeByteViews` proves non-retention/non-reclamation and complete borrowed
effect closure for the exact trusted ByteView declaration. Dedicated typed
`IrByteViewInstruction` operations use stack descriptors rather than object or
array headers. Descriptor loads are immutable during the call; payload memory
retains ordinary aliasing. Typed accessors expand before LLVM argument promotion
to preserve these facts. JNI roots the Java-owned backing storage without
copying it. The exact shared values jar is verified before native initialization
and travels through producer, assembler, Maven distribution and IDK packaging.

`BridgeAssembler` combines independently produced host jars only when complete
generation, Java declarations and common source/license/content inventories match.
It checks native build identities and platform/dependency delivery, regenerates
only the loader's target table with the matching producer, preserves native bytes
and publishes atomically. A changed input order produces the same assembled jar.
Assembly performs no native compilation and grants no new ownership proof.

macOS shared-image linking records a stable relocatable install name instead of
the staging directory. Before shared-image linking, `SharedTraceOrder` sorts
independent Mach-O/ELF pseudo-probe root groups while retaining each nested group byte-for-byte.
It changes no code, function address or runtime decoder and refuses malformed or
relocated probe sections. This removes LLVM metadata ordering differences from
repeat payloads; executable linking keeps its existing path.

`BridgeDistributionCommand` verifies paired content through `BridgePairedArchive`,
copies the main jar unchanged, derives standard source/Javadoc companions and
emits explicit Maven coordinates plus a hash inventory into a new directory.
It performs no upload or toolchain invocation. D218 and the runnable Maven/Gradle
examples define ordinary local producer/consumer conventions.

`BridgeRootSet` resolves explicit compiler-owned callable identities against typed
IR, retaining source spans and native value ABI distinctions. Reconstruction and
specialization require revalidation; missing or changed identities expose no
partial root set. An opaque reference descriptor does not authorize a facade or
ownership conversion. `BridgeProof` distinguishes proved contracts from unknown
and rejected outcomes; an unproved outcome cannot carry an accepted contract.

The opt-in `BridgeRetentionAnalyzer` computes immutable reference-store
attribution from typed bodies, including exceptional blocks, helper substitution,
initializers and all resolved dispatch targets. It preserves loaded-reference
provenance through helpers and rejects transfers into slots, static storage or
arrays, and writes through unresolved or child destinations. Only supported,
fully attributed effects can produce a slot list, including an empty list for
observing entries. Recursive summaries converge over a finite origin/store
lattice before unresolved origins acquire unknown status. Missing targets and
unclassified runtime effects remain unknown. Ordinary compilation does not call
this analysis.

These are store-attribution facts, not complete export permissions. P0 combines
them with owning-root validation, acyclic dependencies and native fixture evidence.
Production bridge admission
must combine those proofs and revalidate synthesized/specialized code before
enabling any export. Existing mandatory reclamation analysis is unchanged.

`BridgeRootRetentionContract` also records possible independent root owners for
each exposed reference type. A retained borrowed view protects its root rather
than its child storage; if that type also has standalone construction, its own
root identity remains an alternative. Repeated-call cycle checks include every
possible owner edge, including multiple declaring owners of the same view type.
Borrowed types cannot hold persistent slots, unknown owner origins remain rejected,
and loaded slot values cannot be transferred. Typed slot payloads still report
the actual stored reference. A host adapter must use the bound input/view owner
state when reconciling those values; this analysis alone does not enable a public
reclaimable adapter.

`BridgeNonReclamationAnalyzer` separately traverses the complete explicit export
closure, including initialization, dynamic dispatch and native cleanup. It
compares possible deallocation types against every possible exposed dynamic type,
including array covariance. `BridgeCallTargets` supplies shared resolved call and
initialization edges to both analyses. Unknown runtime effects remain unknown;
reachable candidate deallocation refuses the classification. The internal
`CompilerPipeline.analyzeForBridge` option projects final constructor escape,
owned-storage and borrowed-input facts only after ordinary validation succeeds.
These immutable facts bind to the complete IR. Exact additive entry/getter
synthesis preserves unchanged source facts after checking the complete function
inventory and metadata, without granting facts to generated methods.
`NativeLinkTransformation` records the exact input/output and actual clone
origins while executing the fixed native-link passes. Only that result can carry
conservative facts into final bridge analysis; arbitrary edits fail binding.
Cloned reference-result origins remain unknown because constant substitution
can change parameter provenance. Actual final calls, generated rollback and
cleanup are still revalidated before any permanent classification is accepted.
`BridgeRollbackAnalysis` attributes exact allocation/invoke/unwind edges and
generated entry failure cleanup to unpublished construction storage, checks the
actual rollback body and keeps destructor effects in the ordinary closure.
The resulting contracts record each excluded allocation origin and cleanup.
Descriptor cleanup uses a restricted retention query over destructor/rollback
bodies. It does not add implicit initialization of their owning class, which
cleanup dispatch does not invoke. Actual typed initialization and helper effects
inside those bodies remain in the closure; ordinary bridge entries still include
their implicit initialization. This prevents unrelated static storage publication
from invalidating otherwise proved cleanup without waiving source destructor
restrictions or publication checks.
Actual OrderBook lifetime proofs survive source/class/archive reconstruction;
P0-8 records JNI constructor-failure cleanup and continued use of exposed controls.

`BridgeEntryModule` begins the private native fixture mechanism with scalar
entries authorized by resolved roots and complete retention facts. Its copied
String mode additionally requires final non-retaining/non-invalidating input
proofs for static methods with scalar results. It rejects other object/conversion
shapes. Generated typed CFGs protect both type
initialization and target invocation, perform ordinary exception occurrence
cleanup and write into a bounded adapter-local result frame. The result-store
operation has no source syntax. Initialization barriers may be omitted only when
the complete resolved prerequisite closure has no initializer body.
`IrBridgeStringCopyInstruction` materializes raw UTF-16 buffers with a non-null
allocation-failure context inside protected typed control flow. Native null uses
an explicit negative-length transport sentinel; empty and malformed-surrogate
strings retain their UTF-16 content. Each acquisition prefix has its own unwind
cleanup, freeing only successfully copied, proved temporary String storage.
Success and target/initializer failure clean the complete prefix. JNI fixtures
acquire/release noncritical buffers on all paths, including partial acquisition.
Retention analysis follows resolved free/rollback destructor effects while
non-reclamation analysis continues to treat the deallocation itself separately.
Failure type/source-frame extraction uses `IrBridgeFailureSnapshotInstruction`
under a separate typed unwind edge after catch cleanup. Its fallback catches
and cleans the extraction failure without another extraction attempt. The
private transport header bounds trace storage; extraction copies immutable
image metadata pointers without allocating. It does not yet snapshot messages,
causes or custom getters. C fixtures consume the returned data without calling
source getters after the entry returns.
`NativeBackend.linkShared` uses the existing
LLVM pipeline and separately compiled C adapters with private runtime symbols.
These internal facilities do not constitute a public producer command or a
complete Java exception translation contract.

P2's internal `SourceSetLoader.loadBridge` seeds the ordinary dependency loader
from exact package unions in source paths, class directories, individual classes
and archives. Nested declarations retain their binary identities; selecting a
package does not recursively select child packages. Invalid/missing packages,
malformed artifacts and mismatched source packages are diagnosed. This does not
yet enable the public producer or admit an unsupported API surface.

`BridgeApiFacts` is an opt-in immutable projection of final resolved semantic
types, enclosing visibility, inherited public methods/fields, constants, exact
callable targets and declared exceptions. It remains bound to its analyzed IR;
ordinary analysis does not collect it, and native transformations do not carry
it as fresh evidence. Enum inventory records named constants in declaration order,
their resolved constant-specific native types and source spans. Synthesized
callables remain distinct from source overloads such as `values(int)`. This
metadata supplies no native conversion token, initialization or lifetime proof.
Exact instance dispatch slots let `BridgeEnumDispatch` inventory each named
constant's resolved implementation, including abstract declarations with no base
function. Synthesized enum identity methods and inherited identity implementations
remain Java behavior; a source override on one constant does not change another
constant's projection. This target inventory grants no invocation capability.
`BridgeEnumInvocation` composes these actual bodies with complete enum
non-reclamation and copied-String confinement proofs. Its receiver parameters
admit only constants selecting each body; ordinary enum arguments remain
nullable. Conversion initializers enter the proof closure, and Java identity
alternatives add no native entry. String values retain separate result cleanup
contracts. Enum results require complete declared-name mappings and include
result-only initialization and non-reclamation obligations. Ordinary object
results remain outside this enum-only invocation mode.
`enumValues` uses the root/String protected-entry builder and the same named
conversion blocks as P0. String copies precede active-use conversion; normal,
conversion-failure and target-failure exits preserve their cleanup contracts.
Constant-specific receivers are loaded from their declaring enum's public fields
and converted only to the proved body receiver type. An instance entry adds no
separate active-use guard for the synthetic constant class; actual source-body
initialization is preserved. Native enum results map back by comparing the paired
public fields under the same protected active-use rules; a null result maps to
the reserved null token without initialization. Result-conversion failure releases
all acquired String copies before snapshot extraction, including when the target
returns a published constant after its declaring initializer failed. Successful
enum results have no reclamation or facade-state operation. This internal
transport still does not enable public
enum generation or promise a boundary for privileged private-entry bypass.
`BridgeEnumConstants` binds producer tokens by name to those exact typed fields
and their complete initialization roots. Default tokens follow sorted names,
independent of native ordinals. Empty enum metadata is valid; it does not grant
an instance invocation. P0 input proofs reuse this mapping contract and retain
their separate retention, non-reclamation and scalar/final-dispatch restrictions.
`BridgeExportSurface.scalarValues` validates the complete
public package union, signature accessibility and explicit-package closure before
selecting P0 callable roots. It reserves `_IronwoodBridgePackage` in each owned
API package. Unsupported constructors, instance/reference/generic surfaces,
inheritance, custom exceptions and nonconstant fields produce located errors,
with no partial selection. `staticValues` adds String-result signature selection
for the generated value transport. Signature selection supplies no lifetime
permission: copied inputs and String results still require their borrowing,
cleanup and retention proofs before typed lowering.

The internal P3 `concreteObjects` selector additionally inventories final concrete
constructors, instance methods and static nested types. It excludes inherited
Object identity methods from native roots while retaining supported source
overrides. General inheritance, arrays, generics, custom exception snapshots and
enums remain outside this incremental selector. Source/class/archive selection
agrees. This is signature validation only; public production additionally requires
complete final admission and the implemented adapter for every selected role.
`objectValues` composes those concrete signatures with declared enums, copied
Strings and scalar values. It selects the exact native implementation for each
inhabited enum constant, including abstract public declarations and partial
constant-specific overrides. Synthesized enum values/valueOf and inherited identity
remain Java projections; source overloads still undergo complete signature checks.
Ironwood's valueCount/valueAt traversal helpers retain native entries and their
source initialization and exceptional behavior.
An empty enum's unsupported members cannot disappear merely because it has no
constant targets. Custom exception declarations use the separate copied-snapshot
inventory, including abstract catch parents and declared checked exceptions.
Their constructors are not native entry roots. Catch parents and getter result
and throws types must remain inside the exact exported-package closure.
Ordinary object inheritance, arrays and generics retain their separate admission
boundaries. This combined selector
does not grant lifetime permission or enable public generation.
A constant-only API surface may have no native entry roots.
`BridgeEnumConversions` binds the complete signature surface's enum parameter,
result and exact receiver mappings, together with the used conversion initializer
roots, independently of ownership or non-reclamation permission. It preserves
ordinary object/String parameters for the later admission consumer and refuses
missing enum dispatch, tokens or entry roots. The enum-only invocation proof
reuses this inventory while retaining its stricter lifetime and confinement gates.

`BridgeStringResults` supplies separate, proof-only cleanup contracts for fresh,
input-alias and immortal/null String results. Fresh and alias origins reuse final
semantic summaries; literal/null returns reuse the retention solver through
helpers. Return-only borrowing keeps input copies alive through result consumption
and does not replace ordinary borrowing. Missing producers remain unknown through
phi/conversion/helper propagation, after local forward references reach a fixed
point. Publication, invalidation, mixed fresh/alias results and unknown effects
remain rejected. These facts do not enable String-result transport or public
producer admission on their own.

P3's `proveForRoots` additionally binds String conversion to a matching complete
root-retention contract. Copied String parameters require ordinary or return-only
borrowing; they cannot become persistent root slots. A getter's exact owned-field
origin permits copying its borrowed String while the root or view owner is live,
without freeing that storage afterward. Fresh results and temporary input aliases
retain their distinct cleanup authority. `rootObjects` consumes these contracts
in the shared protected lowering: copies precede initialization/allocation,
partial-copy and constructor failures release acquired temporaries, and actual
root slots are reported before failure snapshots. This internal entry capability
does not enable public object facades. Nullable owned-field
returns preserve dependent-borrow facts through conditional/cast and early-null
forms; unrelated non-null origins and publication remain conservative.

`BridgePermanentValues` binds independently proved concrete permanent candidates
to a mixed root surface, optionally including its enum conversions. Root
admission uses the complete entry/initializer closure and preserves separate
constructed-root types, dependencies and destruction capabilities. Permanent
fields and purely permanent publication need no root slots; a permanent holder
capturing a reclaimable input remains rejected. String results still require
their copied or borrowed ownership proof. The final lifetime check includes all
generated root destruction, rollback and protected snapshot getters before a
permanent candidate can authorize a native payload. A root destructor that
reclaims that candidate fails the complete proof. This internal capability
does not choose candidates by retrying failed root proofs or enable Java facade
production; host lifetime transport remains separate.

`BridgeObjectAdmission` performs automatic internal selection for concrete object
surfaces and enum/custom-snapshot value projections. A proved value module with
no object references has an empty lifetime inventory and no destruction state;
copied-String and protected snapshot proofs remain mandatory. Unknown semantic
object results and explicit receiver publication into
non-input storage request independent permanent proofs; ordinary root arguments
are not promoted merely because another object captures them. A proved dependent
view follows its permanent owner. The existing retention solver supplies receiver
publication sites without changing source escape facts. All candidates must pass
complete non-reclamation analysis, and the selected root, mixed or uniform
permanent protocol then passes final generated/snapshot closure checks. Failed
cycle, slot-transfer and destruction proofs are not retried as permanent APIs.
The immutable result binds the source artifact, full surface, entry module and
final program. It does not enable the unfinished Java object adapters.

`proveForPermanent` binds the same copied-value contracts to complete D192
non-reclamation facts. Its separate retention projection allows publication of
proved permanent references while preserving copied String input provenance
through helpers, field/static/array stores and exception capture. Ordinary
retention analysis keeps its original publication restrictions. String types
never acquire permanent facade identity; owned-field getters borrow their live
permanent owner for copying, and only fresh or temporary-alias results permit
release. `permanentObjects` reuses the protected String lowering without generated
root state. Its mixed-value overload requires the bound enum conversion inventory,
adds every used conversion initializer to the lifetime/rollback proof closure and
preserves exact constant-specific receiver restrictions. Constructors and object
methods can consume and return enums while enum methods consume/return permanent
objects. Copied String confinement is still mandatory across that full closure;
unknown effects, reachable exposed-type reclamation and unproved rollback prevent
admission. Conversion initializers are proof roots, not exported callable entries.
The uniform permanent path remains separate from reclaimable-object admission.
The original overload retains the same proof and lowering behavior without enum
conversion metadata. Both paths avoid generated destruction, root state or slot
commits. This uniform internal module does not
admit mixed permanent/reclaimable surfaces or expose public object adapters.

The retention solver recognizes fixed runtime String copies, concatenation of
converted values, char-prefix/range and UTF-8 snapshots, default identity text
and Throwable descriptions as fresh inline storage without source-reference
retention. Owned-text release helpers only deallocate proved fresh String storage;
ordinary reclamation and getter ownership proofs remain separate. Producer
overrides retain ordinary call effects, and unclassified array copying or
secondary-exception origins remain unknown.

Stores between independently fresh objects introduce no entry-input retention.
This classification runs after complete helper/store propagation: a fresh child
that captures an input is still rejected at its own capture site, as are loaded
references, mixed origins and untracked publication. Purely native graph cycles
may therefore have empty retention contracts without acquiring a reclamation
or result-ownership proof.

Outward throwable origins also participate in the fixed point. Protected invoke
and throw predecessors supply landing-pad object provenance through the exact
retained unwind edges; native wrapper handles stay opaque. Secondary associations
are independent only when both occurrences have fresh or immortal native origins,
with nested stores and unknown helper effects still preserved. Known implicit
allocation failures carry independence, never fresh cleanup authority. A classified
producer with no returned/thrown value differs from an absent producer; missing
values/callees remain unknown. Raw null throw edges cannot yield a caught object;
typed null-throw lowering supplies its explicit NullPointerException path.
Ordinary escape, reclamation and result ownership analysis remain separate.

Primitive arraycopy effects carry exact primitive array types through aliases,
field loads, returns and erased helper parameters. The solver keeps conditional
copy effects until caller substitution, accepting only matching known primitive
element representations. Reference arrays, mixed or unknown alternatives and
mismatched primitive types remain unproved. This is a retention proof, not a
new reclamation permission, array signature capability or bounds/failure proof.

`BridgeControlFlow` supplies an analysis-only view of reachable blocks and phi
edges. An invoke loses its unwind edge only after final closed-world effects
and complete callee/native-operation validation prove the whole helper closure
nonraising and allocation-free. Missing targets, unclassified operations and
potentially raising work retain the edge. Retention analysis uses this view to
exclude impossible cleanup associations without granting associations a general
non-retention exemption. Emitted IR and ordinary reclamation facts are unchanged.

`BridgeEntryModule.stringValues` consumes these contracts in the existing typed
String entry lowering. It reclaims every input copy except the exact returned
alias, then returns the live result pointer in the private result frame. The JNI
consumer copies UTF-16 with NewString before releasing a fresh/aliased result,
including Java allocation failure; immortal/null results require no release.
Native String's existing inline layout is shared in `ironwood_bridge.h`, with
runtime ABI assertions retained. This needs no extra result-buffer allocation,
registry, or raising C-side native conversion. Generated value facades and JNI
transport now consume these proofs together; unsupported general object results
and String input publication remain rejected.

`BridgeExceptionProjection` resolves the built-in throwable catalog to exact
per-type getter targets, constructor snapshot data and inherited transfer-count
fields. It distinguishes final fresh String results from proved non-fresh
literal/input/field results; an unknown getter does not acquire cleanup authority.
Covariant causes keep their resolved native type. The immutable projection binds
to its entire input program, including private type IDs; reconstruction compares
semantic properties and rebinds IDs rather than carrying them between programs.
This projection itself grants no native invocation or Java construction permission.
Custom descendants retain inherited path/parse constructor getters and transfer
counts, with the same exact target and String-ownership checks. A custom catch
hierarchy cannot extend Java's final `DirectoryIteratorException`, directly or
indirectly; snapshot discovery diagnoses that unrepresentable hierarchy.

`BridgeCustomSnapshotLayout` binds copied primitive/String slots to one exact
custom projection. Name/type keys preserve inherited and overridden getter slots;
abstract catch declarations do not acquire instance extractors. Message and
cause/secondary graph roles remain separate, and built-in ancestor metadata
selects the eventual Java constructor shape. Layout metadata supplies no lifetime
or getter-effect permission; transport still requires its exact protected entries.

`BridgeExceptionClosure` derives the P2 projection by reusing native link
reachability. Starting with the implicit allocation failure, it attaches protected
getters and repeats closed-world pruning until the reachable Throwable type set
stabilizes. Reachable custom types, including helper/initializer dependencies,
are rejected pending P3; unreachable private exception methods do not force a
projection. This includes conservative class-layout dependencies retained by the
linker. Exact original-program and entry/projection binding remain mandatory.
The producer integration must use this discovery rather than a caller-selected
subset of exceptions; the lower-level projection constructor remains useful for
focused compiler tests.

P3's internal `snapshots` discovery uses the same fixed point with custom
snapshot declaration/getter proofs, including further exceptions introduced by
getters. Given the complete selected object surface, it also seeds every exported
custom catch declaration, even one never thrown by reachable source code, and
rejects reachable custom snapshot types outside the exported packages. The surface
must match fresh signature selection and the module's exact entry roots. Both
final lifetime validators accept this surface to include its protected getters
before checking actual optimized effects. The scalar producer retains builtin-only
discovery; the object producer consumes this complete custom closure.
`BridgeFinalNonReclamation` derives every permanent/enum candidate from the
proved entry module, adds this complete exception closure, performs recorded
native linking and rechecks all emitted roots. Generated destruction and getter
deallocation remain visible; no caller-selected subset can certify lifetime.
Its immutable result binds the exact entry module, final program and per-type
proofs. This is storage-lifetime evidence only, not root retention validation or
permission to emit unfinished Java object/custom-exception adapters.

`BridgeFinalRootRetention` rechecks the original root protocol against that final
program. Its actual source-entry slot summaries must match the generated ordered
holder/field, value-input and clearability payload exactly; cloned diagnostic
site names are retained separately. Getters and initializers may introduce no
unreported slots. All normal generated entry/getter roots must preserve root and
view storage, and descriptor destruction/rollback must again prove nonthrowing,
allocation-free cleanup. Only exact generated destruction capabilities are
outside this normal-invocation query, while permanent/enum queries include them.
The result binds both the original protocol metadata and the final emitted
program; it does not provide the pending Java/C lifetime-state implementation.

`BridgeExceptionEntries` attaches exact getter/field and trace follow-up functions
to a matching typed entry module. Getter and trace calls use explicit unwind
edges; failure paths perform ordinary occurrence cleanup and distinguish native
allocation failure from another extraction failure without recursively taking
another snapshot. Exact field reads are nonraising. The final native root set
preserves every generated accessor through optimization. Entry and String-result
retention validation consume matching final construction facts for private owned
storage; copied inputs retain their ordinary borrowing and publication checks.
A private JNI harness exercises protected DateTime getters and generated Java
construction, including native allocation and Java delivery failure cleanup.
Native graph traversal and complete Java exception delivery still require
integration and runtime validation.

`BridgeExceptionSources`, through the exception-aware `BridgeJavaSources`
overload, generates a private Java constructor factory for the exact projected
native type IDs. It preserves constructor fields, required IOException causes,
transfer counts and nullable parsed text; its helper classes carry the generation
annotation and belong to loader preflight. This is Java construction after native
extraction, not permission to invoke getters or free native throwable storage.
DirectoryIteratorException's final Java class derives a message from its Java
cause, so the adapter must copy the extracted native message separately. A pinned
JDK experiment verifies JNI access to nonfinal Throwable.detailMessage on Java
21-23; production bootstrap and public producer jars validate that integration.
`BridgeExceptionGraphSources` adds private bounded assembly of copied
nodes: shared identities and representable cause/secondary cycles survive;
self edges and explicitly omitted edges use a snapshot marker. Ordinary nodes
precede constructor-required IOException wrappers, followed by edge attachment.
Native frames precede bounded Java call-site frames. D214 records the limits.
The assembler returns every node for JNI to finish message fields before Java
delivery. It neither traverses native storage nor catches Java allocation errors.
The native transport below performs traversal and per-node message completion
inside the public producer's built-in snapshot pipeline.

`BridgeExceptionNativeSources` generates that cold JNI transport as an internal
component. It binds to the exact protected getter projection, validates/caches
Java metadata before native initialization, traverses a bounded pointer queue,
copies UTF-16 results and reclaims only proved fresh getter storage. Trace names
use checked ordinary UTF-8 conversion, including supplementary characters;
they are not passed to JNI's modified-UTF-8 constructor. Pending Java failures
survive local-reference and temporary-storage cleanup. DirectoryIteratorException
alone needs extracted detailMessage completion; overwriting the same field on
file/path exceptions would corrupt their constructor-derived reason/message.
The private JNI fixture validates DateTime transport/allocation fallback, native
cause cycles and copy limits, IOException wrappers and file/path snapshot fields
on Java 21-25. It also checks native secondary order/limits, trace truncation,
retained initializer failures and an implicit OOM secondary followed by another
call. D070/D081's second allocation failure during active implicit-OOM unwinding
remains a documented target-process termination, tested separately in a child;
the bridge does not expand native catchability. Public source/archive producer
jars additionally verify those graph/field/initializer cases and native/Java
exhaustion recovery on Java 21-25, with exact payload identities in the P2 audit.

`BridgeValueNativeSources` generates scalar and copied-String JNI marshalling
from those proved typed entries. The generated registration descriptors retain
their exact callable and entry-symbol identities; mismatched programs and
exception closures are refused. JNI UTF-16 acquisitions are released in reverse
order on success, native failure and partial acquisition failure. Fresh or
selected input-alias String results remain live through `NewString` and are
released even when Java allocation fails; immortal results are never freed.
The generated cold failure helper invokes the protected exception transport.
Scalar calls add no world lookup, thread check, allocation or synchronization.
Focused O0/O3 tests use private registration and separate fault-injected images.

`BridgeBootstrapSources` connects the value adapters to the generated loader.
Native bootstrap checks generation/schema/API/build pairing, then repeats complete
Class identity and signature preflight using its own embedded manifest. The
shared Java reflection helper does not initialize facades or read static fields.
Only validated Class objects reach registration. Exception metadata, a permanent
loader anchor and a private copy of the class set are allocated before binding.
Original-loader repeats are idempotent; another loader, changed class set or
failed binding is refused. Late registration failure unregisters this artifact's
completed classes and the potentially partial failing class, preserving the
original Java failure. The mapped-image flag never resets. O0/O3 integration
fixtures package generated jars, load them automatically on Java 21-25, preserve
signed macOS image bytes, and verify lazy native initialization and allocation
failure containment. Public-producer O0/O3 jars now pass duplicate-class and
package-only collision checks in both resolution/first-use orders, disjoint
loading, mixed-class/signature refusal, explicit bootstrap pairing, GC anchoring
and retained-image reload refusal on Java 21-25. A separate fault producer counts
partial-registration cleanup while preserving a loaded disjoint artifact.
Deployment controls cover unsupported hosts/floors, missing/corrupt resources,
unsafe extraction directories, unchanged corrupt existing files and native-build
mismatch. The P2 audit records these checks with completed distribution and
D203/D209 version evidence. Object and final P6 checks remain separate gates.

`BridgeMacPayload` inspects the final thin baseline ARM64 dylib for its actual
macOS deployment target, SDK and dependency names. Bounded load-command parsing
rejects malformed, wrong-platform or ambiguous deployment records. Packaging
uses the image's minimum OS rather than a guessed host-independent baseline;
successful loading on the current host does not qualify older systems.

`BridgeDistributionInputs` inventories required notices, runtime C/header source
and the exact standard-library source reconstructed for the analyzed program.
It does not substitute a potentially newer installed source tree for archived
library inputs, nor classify application implementation source as library source.
Missing notices or conflicting source units fail packaging. `BridgeJarArchive`
checks entry paths, writes deterministic contents with the Java manifest first,
then reopens and verifies every staged entry before atomic output replacement.
Unsupported atomic replacement fails instead of using a non-atomic fallback.
Used class-path `.ironjar` notices are retained under archive-content identities,
with analyzed source checked against the actual archive members. Unused archives
add no notices. Repeated `--license <file>` supplies application notices or source-
availability statements, preserved byte-for-byte in content-separated directories.
Both input groups are rechecked before publication. Application distributors must
still provide any additional corresponding source required by their dependencies;
the producer does not infer application licensing or copy its implementation.

`BridgeProducerCommand` accepts `--java-bridge`, repeated `--export <exact-package>`,
required `-o <artifact.jar>`, `.iron` inputs, source/class search paths, LLVM home,
optimization, `--license` files and missing-free diagnostic options. It discovers the complete
selected packages and retains the scalar primitive/String route where applicable.
Object surfaces require complete `BridgeObjectAdmission`; object, enum and custom
snapshot routes use its exact final program without another transformation after
proof. Unknown effects and unsafe frees remain errors in every mode. Roots and
borrowed views use the proved lifetime adapters, including bounded independent-root
retention slots with complete final contracts. Callback surfaces separately require
`BridgeCallbackAdmission` or `BridgeOwnedCallbackAdmission`, binding the complete
native closure, listener proxy lifetime, carrier cleanup and owner guard/slot
protocol to paired Java/JNI generation. The retained-owner subset has exact final
primitive/listener layouts, primitive constructors and primitive/void methods.
Borrowed String inputs reuse P0 protected copy cleanup and noncritical JNI
buffers. Exact final owners may be passed into callbacks as stable facades, with
all originating entry inputs guarded and native publication excluded by the complete
closure proof. Java retention does not grant source borrowing or safe-free facts.
Other reference callback values still reject. General inheritance, arrays and optional TLS dependencies remain rejected at their pending
implementation boundaries. Linux payloads use the pinned native support closure.

`--critical-calls=on|off` (default off) selects D241's critical calls for object
projections. `BridgeCriticalCalls` analyzes the final admitted program through
the shared `BridgeCallTargets` edges and admits a native function only when its
complete closure has resolved calls and memory-only operations. The permanent
Java and native generators then add one constant method handle and one adapter
without a `JNIEnv` per selected binding, beside the unchanged JNI declaration
and adapter. The selection is recorded in the generation identity, so it also
names distinct generated support packages. It is a transport choice made after
admission and consumes no lifetime proof. x86-64 shared images receive the
`-mattr=-slow-unaligned-mem-16` tuning of D242 from
`NativeBackend.portableTuning`, which D244 extended to every portable x86-64
image; the recorded `cpu.tuning` input is unchanged.

`BridgeProducer` supports JDK 21, 22, 23, 24 and 25 with compiler/Javadoc tools and JNI headers,
the pinned LLVM toolchain and the matching macOS SDK or Linux support SDK. It uses the existing optimizer and
shared linker, verifies the signed final image and derives its deployment floor
from that image. It stages Java 21 classes, generated Java source/Javadoc (including
the JDK tool's generated legal files), exact library/runtime source and notices,
native payload and content hashes before publishing the jar. Every generated
class must match the bootstrap inventory. A failed build preserves earlier output.
The Java automatic module name derives from the producing jar basename and stays
stable across implementation updates under that name. Use distinct producing
basenames for independent modules. Consumer renaming does not change that name.
`META-INF/ironwood/bridge.properties` records it and the paired build inventory.
Consumers use ordinary Java 21-25 dependency loading without native tools; on Java 24
and 25 the JDK's native-access policy applies to the loader's `System.load` (D245).

`BridgeGeneration` separates the logical Java API hash from the complete analyzed
source-program and producer generation, target-specific native build identity,
and final image byte digest. Generation includes private/native-only dependency
source and compiler/runtime fingerprints; an unchanged API cannot authorize a
different implementation. Canonical length-prefixed UTF-16 encoding preserves
unpaired-surrogate constants. `BridgeProducerInputs` fingerprints the actual
compiler classes/resources and runtime C/header inputs while ignoring jar entry
timestamps and container paths. Native build inputs are separately hashed before
embedding their identity, avoiding a self-referential final image digest. The
producer supplies actual target, SDK, toolchain, JNI-header and generated-input
identities and rechecks compiler/runtime/distribution inputs before publication.
On macOS, `MacNativeTools` selects the SDK and Apple linker from the developer
environment once per bridge build. Adapter/runtime compilation and final linking
share that selection; native identity includes SDK settings/stub hashes and the
linker version/hash. LLVM 23 still performs compilation and optimization. Explicit
SDK failures do not fall back to another installed version.
These identities do not independently qualify a payload or complete P2.

The internal object identity route requires the exact `BridgeObjectAdmission`
and its final proofs. Its API identity additionally covers enum declaration
order, custom snapshot fields and the proved facade role (permanent, root,
view, combined root/view, enum, snapshot or static container). Changing a private
implementation so that generated destruction disappears therefore changes the
Java API identity as well. Object identities cannot enter the static-value
generator. Identity generation does not enable unfinished object adapters.

`BridgeIdentityCacheSources` generates the internal permanent-world weak cache
only for an exact admitted permanent concrete surface. Primitive address keys
avoid boxing on lookup. A live hit allocates nothing; a miss adds one weak entry
in addition to its facade, plus a bucket array when capacity grows. Rehashing
reuses entries after successful array allocation and performs no method calls
while relinking them. Collected-entry removal uses entry identity, preventing
late queue delivery from evicting a replacement. This cache supplies no native
ownership permission and belongs only on object conversion paths. Component
checks are supplemented by generated facade/JNI integration and the recorded
[P4](JAVA_BRIDGE_P4_EVIDENCE.md)/[P6](JAVA_BRIDGE_P6_EVIDENCE.md) allocation
evidence. Numerical performance acceptance and x86-64 hardware remain separate.

`BridgeJavaSources` emits Java 21 facade declarations, runtime-visible identity
annotations, package markers and one matching private JNI binding list. It
requires matching generation and proved typed entries, preserves overloads,
checked declarations and nested names, and emits exact primitive/UTF-16 constants.
Native entry names and the imported one-time bootstrap method avoid producer
method collisions. The runtime support loader is a separate generation step;
there is no inert production fallback. Current javac/reflection tests supply an
explicit test-only support stub and do not establish runnable-jar qualification.

The internal `BridgePermanentJavaSources` route emits proved permanent concrete
facades and static nested classes. Private final address/type metadata supports
Java-only inherited equality, hashing and text; native source overrides retain
private JNI dispatch. Public constructors initialize all immutable metadata
before a separate private native cache-registration helper. Raw conversion
constructors are private, and permanent facades expose no generated destruction
or mutable lifetime state. Loader and manifest inventories distinguish host cache
registration from typed source entries. The value bootstrap refuses these host
helpers. Reclaimable adapters remain outside this declaration route.

`BridgeCustomSnapshotSources` emits Java-only custom throwable hierarchies and
copied primitive/String getters using `BridgeCustomSnapshotLayout`. Generated
non-public constructors accept artifact-local copied data; the factory accesses
them within the same module and never invokes source constructors. Built-in
superclass construction uses valid placeholders, so legal native getter overrides
such as null path/parse text are preserved by the copied getters. Graph assembly
validates covariant cause/secondary types before exposure. An omission marker
that cannot satisfy a narrower custom return type fails with the bounded
LinkageError fallback instead of a later getter cast failure. Snapshots carry no
native handle or cleanup operation. Java-only class/module and heap-failure tests
cover this component; the public permanent-object producer includes the same
generated snapshot classes and transport.

`BridgeCustomSnapshotNativeSources` generates cold extraction of those slots
through their exact protected entries. Custom constructor properties bypass the
built-in carriers and are captured once into copied arrays; transfer counts also
populate the inherited Java field. Floating-point bits are copied without
conversion. Owned native Strings are released after Java copying, including host
allocation failure. Fixed node limits bound JNI array and local-frame lifetimes;
no source-sized C stack arrays or native ownership registry are introduced.
Generated O0/O3 jars cover exact capture counts, throwing/allocating getters,
native budget failures and injected JNI array/String/graph delivery failures.
The built-in-only factory descriptor and transport remain unchanged. Exception
storage is not implicitly reclaimed; later root tests must demonstrate snapshots
after independently eligible native destruction.

`BridgeEnumJavaSources` emits actual Java enums with private final name-paired
tokens, source declaration order and exact constant-specific native dispatch.
Inherited Java enum identity, `values()`, `valueOf()` and type inspection do not
bootstrap or initialize the native world. Empty enums preserve Java's inherited
final `compareTo` rather than attempting a source declaration with no native
target. Native method invocation performs the bridge's ordinary bootstrap check.
No enum receives a native address, concrete facade state or weak-cache entry.

`BridgePermanentNativeSources` binds those declarations to the exact final
admission and emits object/String conversion around its protected typed entries.
Its bootstrap validates source entries and host helpers together, then anchors
facade classes and the weak cache. Facade constructor/field lookup is lazy after
bootstrap, so cold result conversion does not initialize a facade during native
registration. Scalar receivers pass their private final address directly, with
no JNI field lookup or cache operation. Object-return cache hits reuse the live
facade; misses call its private conversion constructor before cache insertion.
The public macOS producer uses this path for exact object/enum/snapshot admission.
Root generation additionally reserves the native index record and state global
reference before entering native code. Registration commits before any Java
facade/cache delivery, and the index preserves the state after delivery failure.
Root facades check receiver liveness in Java; native object argument conversion
checks the shared state before dereference. Approved destruction marks FREEING,
calls the exact nonthrowing typed destructor, marks FREED and removes registration.
Source/class/archive jars retain complete pairing, sources/Javadoc and notices.
The retention generator consumes the final entry's existing typed slot payload,
resolves fixed Java dependency fields before mutation and deduplicates aliased
holders and roots. Its bounded preflight reserves JNI locals and checks incoming
count headroom. Its native commit applies all increments, then decrements, then
slot records on both successful and exceptional returns. Destruction acquires
outgoing dependency references before FREEING and releases their counts after
the typed destructor. The public producer selects this path only after complete
final root admission; unknown effects, slot transfers, child-held slots and cycles
remain rejected in every missing-free mode.
Separately labeled fault jars verify buffer cleanup before target execution,
owned-result cleanup after Java delivery failure, facade/cache retry without
native reallocation, and global-reference cleanup on failed bootstrap. The
production generator contains no allocation-failure hooks.

For uniform permanent objects, selected instance methods also receive private
constant-enum entries (D224). Eligibility requires exactly one nullable,
two-constant enum argument, at least two other primitive arguments, no other
reference argument, and no String or enum result. The public Java signature is
unchanged. The facade selects the exact constant entry, whose private ABI omits
that argument; null keeps the generic entry. Native active use and exception
containment remain inside typed lowering. Generic roots remain mandatory proof
inputs, and every added entry participates in synthesis and final lifetime
validation. Root/view transport keeps its existing path. Shared conversion
helpers appear once in Java/native registration and the packaged inventory.
The artifact-wide permanent weak cache starts with 256 buckets to reduce address
collisions; per-root caches still start with 16. Lookup, weak recreation, growth,
allocation-failure behavior and ownership are unchanged.

`BridgeEnumNativeSources` anchors preflighted enum classes without initialization,
then lazily reads private Java tokens and named Java result singletons. The JNI
carrier mapping must match the typed module's exact conversion inventory, including
constant-specific receiver subsets. Native enum initialization, public constant
field loads, conversion errors and source execution remain inside those protected
typed entries. Focused pure-enum and mixed permanent-object jars cover cold calls,
nullable/empty values, initializer failure, copied Strings and zero-allocation
warmed calls. Separate injected artifacts cover metadata, preparation and delivery
failure cleanup. Java 24 and 25 permit Java-only enum inspection without extraction
and then load normally under the JDK's native-access policy. These checks do not complete P3 or final qualification.

`BridgeLoaderSources` generates the artifact-private multi-target support
class. Its one-time path checks Java 21-25, preflights all resolved identity and
private-native descriptors without initializing facades, checks host constraints,
then verifies/extracts the selected image and private dependencies before loading
and native bootstrap. Linux host packaging audits ELF architecture, eager binding,
relative dependency paths and the glibc 2.17 symbol-version ceiling, preserving
the pinned runtime source/license delivery. macOS retains signed-image checks.
Under D243 the private POSIX cache keys owner, generation, target and a digest of
the selected files' paths and contents. Independent loaders and later JVMs select
one canonical image path for identical payloads; another native build of the same
generation selects its own. Each distinct file is stored once under its SHA-256
name in the owner's `blobs` directory and hard-linked into every build directory
that needs it, so the private Linux C++ runtime is shared. Exclusive partial files
are verified and atomically hard-linked into place without replacing an existing
file. Existing files/directories require the expected owner, mode and digest on
every launch; symlinks and stale partial selection are rejected. The loader never
deletes or repairs cache entries. Source-level tests
exercise concurrent extraction using nonexecutable fixture bytes. Generated-jar
checks also exercise actual registration, anchoring, signatures and launch forms;
final distribution-candidate qualification remains pending.

The P1 `CompilerPipeline.compileBridge` final-link path consumes the same P0
scalar admission and protected entry lowering. `IrProgram.exportRoots` retains
all generated entries independently of a source `main`; executable and library
roots cannot coexist. Every typed-IR reconstruction preserves those identities.
`NativeLinkPipeline` shares initialization and enum specialization, field
forwarding, reachability pruning and unread-store elimination with executables.
Foreign entries receive no assumed initialization or constant-argument facts.
Library pruning preserves the allocation-failure context, initializer/cleanup
closure and protected unwind edges, while removing unreachable source methods.
Export signatures are revalidated after optimization, and pre-optimization
semantic facts are not attached to transformed IR as newly proved contracts.
`NativeOutputKind` selects executable or shared-library linking; the latter
accepts separately compiled adapters and uses eager ELF binding on Linux.
Linux shared links validate the prepared `BridgeNativeSupport` SDK, preserve
the glibc 2.17 sysroot explicitly, and deliver private shared dependencies plus
their source/notices under a relative loader path. A missing or changed SDK
fails before linking, and existing delivered files are verified before reuse.
Linux IDK packaging includes and verifies this complete support SDK, preserving
its source/notices and manifests. Ordinary installed discovery requires no
producer-side support override.
See [native support](JAVA_BRIDGE_NATIVE_SUPPORT.md) for provenance and the
minimal-JVM experiment. P1 dependency closure and macOS signing/extraction
qualification are recorded separately in the progress log.

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
standard-library discovery they serve, are recorded in D276.

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
destructor validation run on the original program; class/archive inputs are
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

Dynamic binary String concatenation return provenance is derived from
provisional typed IR rather than source shape alone. The compiler indexes each
`IrStringConcatInstruction` by callable linkage and source span, including an
instruction carried by an invoke terminator, then supplies those facts to the
fixed-point symbolic return analysis. This preserves a fresh origin through
locals, branches, wrappers, and calls while the existing summary still rejects
publication, live aliases, borrowed or mixed paths, and unknown effects. A
constant-folded concatenation has no such instruction and remains an immortal
literal, so this refinement cannot make it a valid `free` target.

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
Array bounds lowering uses one unsigned i32 comparison between index and
length. Every array producer enforces a nonnegative int length, so negative
indexes convert above every legal length and fail the same comparison as an
oversized positive index. The runtime length slot remains size_t/i64; LLVM
loads and truncates it for this predicate. Null/negative-length checks and
failure allocation, evaluation order and cleanup are unchanged (D176).
Checkedness changes no IR or native ABI: it is a compile-time contract over the
existing exceptional CFG.

D168 Milestone 1 generalizes `FinallyContext` to immutable source-finally,
typed deferred-call, and bound deferred-free actions. `prepareInvocationOperands`
preserves existing planned, ordinary, superclass, and interface-super selection while evaluating
and converting operands once. `emitPreparedInvocation` performs the outer null
check, target initialization, dispatch, and call effects at invocation. Immediate
calls compose both operations; deferred calls retain the typed values, converted
SSA operands, selected contract, substitutions, and source spans.

Each successful capture protects the remaining source block tail without adding
a source scope. A single cleanup-action emitter serves normal, return, exception,
break, continue, and yield paths. Replays emit fresh call IR using dominating
captures and independent ownership snapshots; source-finally replays still read
locals late. Cleanup contexts also retain lexical checked-catch/observation
scopes, so an exited inner handler cannot admit or catch an outer cleanup call.
Pending call operands participate in free, dependent-owner, and missing-free
analysis. Whole-callable source effect walkers include captured inputs, while
typed lowering separates capture effects from delayed invocation effects.
Closed-world destructor analysis follows feasible unwind edges as callee effects
reach their fixed point; unreachable generated rethrows do not invent an escape.

`DeferredFreeStatement` accepts only a local name. Its action stores the resolved
local symbol, type through that symbol, source spans, and the outer locals that
remain live after its block. Registration emits no capture instruction. Pending
write/free guards derive from the active cleanup chain; exit replay restores the
chain together with each ownership/environment snapshot. Explicit-block placement
means paths rejoin only after their block's actions have finished, so no mutable
registration flag is needed. Assignment statements and expression lvalues both
check the binding guard. The common free proof reads the unchanged local on each
cleanup predecessor and checks its current allocation, aliases, escapes, pending
observers, and loop state before emitting an ordinary `IrFreeInstruction`.
Only locals belonging to scopes crossed by that action expire as aliases;
remaining pending-free targets and outer locals are retained. The environment
itself is preserved for SSA/exception joins and mutually exclusive replays.

Existing typed calls, specialization, reachability, and exception IR implement
the feature without a new runtime ABI, action stack, callbacks, or registration.
Format-1 class/archive source reconstruction preserves these rules without a
format change. Older artifacts using `defer` as an identifier fail on re-lexing;
rename that identifier and rebuild. [Performance verification](DEFER_PERFORMANCE_VERIFICATION.md)
covers Milestone 2 step 1; a [focused example](../examples/deferredcleanup/README.md)
covers step 2. [SimpleTcpEcho adoption](DEFER_PROJECT_VERIFICATION.md) covers
steps 3 and 4; the [final audit](DEFER_FINAL_VERIFICATION.md) completes step 5
locally, ready for maintainer review. Integration requires separate direction. See
[Stage 1 evidence](DEFER_CALLS_VERIFICATION.md) and
[combined Stage 2 verification](DEFER_FREE_VERIFICATION.md).

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

Both source compilation and native linking accept `--unfreed=off|warn|error`;
`warn` is the default. Warnings are printed to standard error and do not prevent
class/native output or make the command fail. `error` promotes the same
abandonment findings to errors before writing output; `off` disables only this
check. Repeated selections use the last value. Invalid values are usage errors.
Class and archive source reconstruction reruns the check at final link, so the
selection is per invocation and is not stored in `.ironclass` or `.ironjar`.
The local `@SuppressUnfreed` directive instead survives in preserved source and
exempts its initializer's tracked allocation at either stage, even in strict
mode. Its AST flag is consumed only by the diagnostic tracker; it adds no IR,
LLVM, or runtime metadata and does not alter safe-free state (D145).
Existing warnings, including intentional process-lifetime omissions in examples
and libraries, need not be resolved to build in the default mode.

D140 adds diagnostic severity to the compiler API and IDE transport. A
`CompilerPipeline` defaults to `UnfreedMode.WARN`; callers can construct one with
`OFF` or `ERROR`. Warnings preserve `CompilationArtifact.valid()` and
`successful()` when the corresponding program/LLVM outputs exist. Allocation
findings use source spans and are collected only during final semantic lowering,
after provisional call binding has refined escape and ownership summaries. Since
D278 that refinement also runs after earlier errors; when it does not converge,
final lowering still reports other errors but no ownership verdict or finding.

D184 adds immutable `DiagnosticNote` entries to the shared `Diagnostic` API.
The existing constructors and primary accessors remain available, while full
record equality now includes notes; tests that compare only primary diagnostics
must compare message, source, span, and severity explicitly. The formatter
prints a complete primary block before any `note:` block. A located note uses
its own source and per-location gutter, including when it is in another file;
an unlocated note is a plain line. Missing primary source/span and warnings
retain no notes, and a diagnostic with no notes renders exactly as before.
IronDoc uses this formatter without an ownership-analysis mode. The
`--explain-rejected-free` option enables the internal pipeline setting for one
source compilation or native link. It is off by default, accepts no value, and
may be repeated. It leaves primary diagnostics, status, and artifacts unchanged;
the option is not persisted in classes or archives. Enabled analysis may cost
additional compile time even when compilation succeeds, but success prints no
explanation report. M1c provides eligibility and boundary notes at each
rejected `free`, deferred registration, destructor field, loop back
edge, or owned-element validation site. Completed refinement reports a
category-specific unsupported-detail boundary. Since D278 every shown rejection
follows completed refinement, so the former limited-analysis note for skipped
refinement no longer exists. Parser, name, type, wrong-pool, pending-write,
and standalone use-after-free errors remain note-free. M1d adds located local
alias and earlier-free notes where a unique current path supports them.
M2a adds selected direct field/static/array store sites, a conditional-reference
site, and a known-array-slot store site. Their locations survive supported
snapshots.
M2b adds selected argument, receiver, and constructor call sites from final
local effects, plus a parameter or current expression location when allocation
identity is missing. Multiline operands retain their full source span. This
does not trace into a callee or prove an unknown factory result fresh.
M2c adds selected container/wrapper retention sites, current owner names or
creation locations, dependent-helper source expressions, and attached-field
load sites. Relationship sites follow supported ownership snapshots and end
with successful borrower cleanup. M2d adds the originating pool and checkout
site for a proved pool-owned borrow, including supported aliases. A successful
same-pool return does not destroy the item; destroying the pool reclaims its
checked-out items. If an external object was passed to `release`, a rejected
independent free points to that argument and explains the conservative proof:
the pool does not promise to reclaim external objects. Wrong-pool release errors
remain note-free.
M3 adds bounded incoming-path alternatives, deferred-action and cleanup-exit
sites, and loop back-edge context. M4a retains bounded summary facts in the
selected analyzer. M4b follows a selected final call effect through at most
four supported summary hops and eight notes, including possible dispatch
targets and cross-file stores. A missing, withdrawn, or exhausted callee fact
ends with an explicit boundary. Constructor call notes still identify the
local operand without a callee chain. M4c retains one first failed predicate
per selected private-field checker, including non-fresh writes, sibling or
other publication, return/throw, conservative call and constructor conditions,
and reentrant access. A local loaded from an unproved field keeps its original
identity or attachment primary and can add a separate field cause. A supported
field-call witness follows the final summary to cross-file stores. An actual
empty receiver-flow fallback is labeled only for the selected possible target;
other unproved predicates retain a boundary. M4d locates the selected late
owned-element predicate in the offending function's source, the first
recording store when relevant, and the recognized destructor cleanup. The
field-declaration primary and one-reason-per-checker selection stay fixed;
missing source context retains a boundary. See the [compile and link examples](EXPLAIN_REJECTED_FREE_EXAMPLES.md),
[explanation plan](EXPLAIN_REJECTED_FREE.md), and
[current limits](MEMORY.md#rejected-free-explanations).
Summary and private-field witnesses use the same invocation evidence budget
across initial, rebound, and refinement analyses; superseded maps are retired
before their supplying summaries. The late owned-element validator uses its
existing typed-IR facts without a persistent witness map. The
[verification record](EXPLAIN_REJECTED_FREE_VERIFICATION.md) reports measured
live storage and compile-time cost. M5a checks source/class/archive dependency
compilation and linking with the option off/on, exact accepted class/archive/LLVM
bytes, and native behavior, reclamation, and exception traces. The
[memory guide](MEMORY.md#rejected-free-explanations) publishes the final numeric
evidence and output limits.
Notes add no runtime machinery.

A diagnostic-only tracker observes completed allocation origins and retained
references at statement boundaries, normal scope exits, and completed returns.
It follows separate lifetime snapshots alongside safe-free state, never changes
that state or emitted IR, and suspends statement observations while an enclosing
expression still holds evaluated operands. Constructor/factory failure edges do
not acquire the successful result's diagnostic obligation. Closed-world typed
reclamation effects suppress findings for arguments a callee may reclaim,
including private library cleanup helpers; this does not authorize `free` or
assert that those arguments are dead. Conflicting/unknown states remain outside
the proof. See [memory diagnostics and coverage limits](MEMORY.md) for the first
version's scope.

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
`--unfreed=error`. Each suite runs in a fresh native process; networking uses
loopback sockets and read-only host queries, without live reachability probes.
The runner's final native link requires the optional SDK described in [TLS.md](TLS.md).
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
runtime registry, or general annotation facility. D141 makes the generated
entry point free its runner after `finish()` and before returning the status.
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

Ownership/escape refinement now converges over the actual closed-world target
sets, including reference-returning calls and exception paths. Unknown targets
stay conservative. Constructor summaries use the actual resolved overload and
retain conservative fallback effects when it is unknown. FreshArrayElementAnalysis
and FreshBorrowingFactoryAnalysis
prove the bounded source shapes described in MEMORY.md and OWNED_HELPER_BORROWS.md;
they add no runtime tracking. TypeInitializationAnalysis preserves superclass
and default-interface prerequisites while omitting guards whose entire required
initialization has no work. O3 inspection verifies that the primitive TCP bridges
have no avoidable initialization checks on their ordinary path.
