<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 migration and test disposition ledger

Date: 2026-11-06. **Completed.** Read the [implementation report](GC7_IMPLEMENTATION.md)
and [pre-change review](GC7_PRECHANGE_REVIEW.md). Machine ledgers record
[changed consumers and provenance](gc-evaluation/gc7-consumer-dispositions.json),
[exact retired/mixed test families](gc-evaluation/gc7-test-dispositions.json),
and [focused verification](gc-evaluation/gc7-verification.json).

The previous incomplete checkpoint is preserved
[byte for byte](gc-evaluation/gc7-checkpoint/manifest.json), with its original
commit and hashes. It is superseded, not rewritten as successful evidence.

## Inventory and maintained consumers

GC0 is the semantic inventory baseline; the reproducible lexical comparison uses
`3afea8f8ea9b004ea3d5084b20e1636beea1188d`. Its candidate-inventory SHA-256 is
`919f02225ca961a2e2e7be97b095798b971c3586c5976d5244d7e5c774ba0427`.
`scripts/gc-evaluation/inventory_gc7.py` reads Git objects without checking out
another tree. Counts below include ordinary names, native frees, negatives,
allocation-free descriptions and historical text; they are not obsolete-code
counts. The consumer ledger records actual changed paths and before/after hashes.

| Baseline area | Candidate files / matches | Completed disposition |
| --- | ---: | --- |
| Compiler Java | 79 / 419 | Delete obsolete AST/IR/proofs, metadata, caches and legacy builders; retain actual optimization, GC and native/protocol facts. |
| Checked-in compiler Ironwood | 67 / 704 | Migrate AST/lexer/parser/port consumers; retire heap-proof prototypes and regenerate the reduced IR data catalog. Syntax validation only; no I1/I2 or self-hosting build. |
| Compiler tests | 182 / 3922 | Retire obsolete verdicts by exact family, preserve mixed behavior, add managed graph and paired native/pool/foreign replacements. |
| Runtime | 12 / 76 | Remove managed-release helpers and semantic descriptor fields; reserve physical ABI padding; retain collector/native resource domains. |
| Standard library/reference material | 25 / 408 | Preserve managed graph APIs, explicit resources and pool retention; remove empty release helpers and managed-only file-visitor retention limits. Released reference material stays frozen. |
| Examples | 152 / 342 | Managed syntax, RootHandle/Roots/ListenerRegistration, explicit resource lifecycle and deliberate collection phases. Nine invalid sources remain migration negatives. |
| Projects | 46 / 159 | Preserve resource close, pool operations and workload/allocation expectations; migrate drivers, launch inputs and immediate-reclamation assertions. |
| Integration inputs | 252 / 2646 | Split functional/defer/exception/initialization/resource behavior from old free verdicts. Retain GC2-GC6 controls and add shared/retained managed graphs. |
| Scripts/generators | 63 / 274 | Current generators, benchmark/package smoke programs and source inputs migrate. Old projection-specific P0-P7 runners retire with hashes; their reports stay frozen. |
| IDE | 14 / 89 | Generate current grammar, highlight former words as identifiers, migrate completion/symbols/templates/launch settings and diagnostic parsing. |
| Documentation | 453 / 4275 | Current specifications, guidance, runnable snippets and numbered feature descriptions synchronize. Decisions and old measurement/source-review evidence retain provenance with explicit supersession. |
| Packaging | 1 / 1 | Pointer-free BDWGC configuration is legitimate. Rebuild changed verified inputs and exercise current smoke/package producers. |

Root README/guidance/CLA matches are included in the machine inventory. Benchmarks
live in examples, bridge/project workloads and generators; there is no separate
benchmark tree. The standard library's explicit file/socket/TLS close paths and
pool release/reuse are maintained executable contracts, never historical exemptions.

## Analysis outputs and their consumers

O = obsolete managed reclamation; P = actual optimization; R = resource,
initialization, pool or foreign contract; G = collection effects, roots/layouts.

| Producer/output | Disposition | Remaining consumer or replacement |
| --- | --- | --- |
| Owned field/element/temporary return refinement and must-free state | O: delete the outer convergence loop and subordinate implementations, witnesses and caches. | Managed reachability uses G: exact roots/layouts and conservative collection effects. No whole-program free-proof fixed point remains. |
| FieldRetentionAnalysis and exclusive/copy/detached element certificates | O: delete the complete passes, including FreshArrayElementAnalysis and map/set/copy proof fragments. | R/P: FinalFieldOrigins identifies stable initialized reference fields; actual constructor/store/getter/array-slot origins preserve pool facts. No exclusivity or nonretention is inferred. |
| Function-level free/deferred-free/destruction/temporary cleanup builders | O: delete proofs, lowering, rollback and owned-result release bookkeeping. | R/G: general defer captures, pending return/yield/exception roots, assignment failure order, initialization and explicit resource actions remain. |
| EscapeSummaryAnalyzer and SymbolicReturnOriginAnalyzer | R/P: retain actual reference effects and their legitimate fixed points. | Pool freshness/origins and foreign input confinement. Unknown effects remain conservative. Constructor publication, including superclass effects, invalidates unescaped fresh builder origins. |
| Dispatch, generic/value flow, null/init and cold throw facts | P/R: retain actual lowering/optimization contracts. | Exact targets, initialization/final checks, D051, null behavior and small exclusive-predecessor cold-throw matching without rollback. |
| PoolSemantics/PoolReleaseAnalysis and protocol snapshots | R/P: retain known origins, member protocols and allocation-free reuse. | Paired same/wrong-pool, alias/array/helper/final-field wrapper, cached/publishing/unknown builder tests. No runtime misuse registry is introduced. |
| Native acquisition, resource lifecycle and foreign carrier confinement | R: retain, including failure cleanup. | Explicit close, borrowed input/view refusal, snapshot receiver confinement, JNI completion and listener final slots. Managed collection is not foreign capability release. |
| GcEffects, layout/statepoint/root and suspended/foreign segments | G: retain fail-closed verification. | One nonmoving managed heap; allocation never collects, collection is explicit and synchronous. |
| Missing-free modes/options/annotation, suppression/explanation/evidence state | O: delete. | Concise parser/CLI migration diagnostics, including ordinary and bridge option entry paths. |
| Free/destructor AST, deallocation/release/rollback IR and dispatch metadata | O: delete definitions and visitors/producers. | Reduced final-link verifier rejects legacy raw call symbols and malformed metadata without obsolete IR injection records. Ordinary free/destructor methods remain legal. |
| Legacy bridge projection/admission/rollback/builders and declaration inventories | O: delete. | GC6 managed image/facade/root/registration path only; actual source resource close remains independent. |
| TestHarnessSynthesizer and generated IR model | O/G: remove synthetic managed free and suppression premises; regenerate data inventory. | Deterministic suite dispatch and negative declarations remain; 117 IR records / 22 enums, with exactness checks. |
| File-tree visitor borrowing override and private empty rendering releases | O: delete managed temporary-reclamation premises. | Actual callback effects may retain managed paths/attributes; native stream cleanup and rendering behavior remain tested. |

## Test dispositions and behavioral replacements

The machine ledger identifies 165 retired test classes/fixtures, 366 old
registrations, 17 explicitly split/restored mixed registrations, 44 unregistered
legacy methods and eight retired methods. Each retired source has its original
hash and Git revision; every registration or explicit parameterized family links
to named replacement coverage. Repeated mappings use coverage-family identifiers
instead of duplicating large path lists. These counts describe dispositions,
not tests executed or a reduction in safety obligations.

Three additional split families are recorded explicitly: construction publication,
filesystem services and bridge control flow. The last retains every IR/source
shape and actual unwind/phi/reference-effect assertion while deleting test-only
String ownership-result certificates and their production queries.

| Old family | Disposition and replacement behavior |
| --- | --- |
| Free proof, missing-free warnings, suppression and explanation/witness readiness | Retire obsolete verdicts. Representative unsafe frees become removed-syntax diagnostics, including embedded source/class/archive paths. General diagnostic source spans/related notes remain. |
| Owned fields, arrays, aliases, detached elements, snapshots and copied keys | Managed graph families now accept aliases/shared elements/cycles and survive collection. Keep array/copy/value behavior, independent caller storage, initialized fields, pool origins and foreign copied-input refusals. |
| Destructor/constructor rollback/publication/reentry | Remove managed destructor counts and rollback eligibility. Keep original construction dispatch/publication shapes as accepted source, final initialization errors, exceptions/partial construction and native acquisition failure cleanup. Native constructor-dispatch collection verifies published partial objects. |
| Deferred free, temporary release and rendering | Preserve operand/capture/assignment order, pending values, exceptions, general defer and explicit resource actions. Native fixtures retain these branches; 21 collection-rendering text families and real allocation assertions remain. |
| Immediate live-count assertions in library/project fixtures | Remove the reclamation premise, retain functional predicates and allocation counts. Reclamation observations use completed phases. No collection is inserted in critical loops or library operations. Steady-state zero-allocation/live-delta controls remain when their workload allocates nothing. |
| Pool/collection destruction | Retain release/reuse, caller items, stale-slot/retention behavior, failure ordering and warmed allocation expectations. PoolRetentionTests and CollectionRetentionTests check rooted survival and explicit phase collection. Known wrong-pool, cached/publishing builders and native misuse remain rejected. |
| Permanent/manual/owned bridge construction/free | Replace with GC6 managed admission and exact anchors/roots. Actual JVM families cover stale handles/facades, aliases/views, enums, generic results, image isolation and source resource closure independent of anchor release. |
| Listener/callback/exception snapshot/foreign input families | Preserve accepted/refused confinement, registration closure, JVM reentry, collecting getters, exact suspended roots and explicit JNI cleanup. Retaining copied/borrowed inputs and escaping invocation carriers remain rejected. |
| Bridge loader/package/assembly/generation families | Current production images, source/class/archive reconstruction, assembled host execution, duplicate-target refusal, 32 metadata cases and 16 native-input cases replace old projection-specific machinery. No stale generation is silently accepted. |
| OrderBook, generic/bounded and bridge measurement runners | Preserve actual pooling operations and Java baselines; scope returned handles and listener registrations explicitly. Quick micro/retention/listener/OrderBook and generic getter/setter controls replace executable old runners; old timing/qualification reports remain frozen. |
| Discarded checked-in compiler proof prototypes | Retire obsolete semantic prototypes and their proof-only fixtures with hashes. Preserve current lexer/parser/AST/port behavior and generated IR catalog; parse consumers without restarting self-hosting. |
| IDE reclamation diagnostics/symbols/templates | Split general diagnostics, related notes, migration errors and clean managed programs. Ordinary free/destructor methods have normal highlighting/completion/symbols; generated tests preserve shared results across collection. |

Mixed tests are not replaced by one generic syntax rejection. Existing functional
methods remain in place where applicable; the ledger records their exact current
selection names. Initializer/dispatch/null/exception/artifact tests remain
registered. Retired proof-only registrations are removed instead of disabled or
made unconditional successes. A skipped full-suite run is not represented as
proof that every retained test passes.

## Intentional negatives and frozen history

The nine sources under `examples/ownedhelperborrows/src/invalid` are unchanged
removed-syntax migration negatives, with individual hashes in the consumer
ledger. Their old comments preserve the original example premise, not a current
managed alias restriction. Compiler/parser/final-link tests intentionally retain
old spellings as negative inputs; native symbol names and ordinary methods are
classified by meaning.

Frozen families include GC0-GC6 reports, source/licensing/verification reviews,
existing GC evaluation/measurement manifests, released API 0.6.0 material,
`docs/self-hosting/**` and stopped `scripts/self-hosting/**`. The indexed path/hash
list is verified against the entry revision. The old GC7 checkpoint reports and
their hashes are preserved separately. Historical P0-P7 scripts removed from
maintained execution have their original paths/hashes recorded; reports are not
rewritten to make old examples appear current. Checked-in compiler Ironwood
sources are migrated repository consumers, not frozen historical evidence.

## Native deallocation search and ABI disposition

Production compiler/IR paths have no managed destruction, deallocation, rollback
or owned-result release producer. No runtime GC_free or libc free of managed
storage remains. Forbidden symbol strings survive only in fail-closed verifiers,
migration-negative tests and historical text. Ordinary user methods are normally
mangled and do not invoke raw libc free.

Legitimate native frees/reallocations remain for:

- collector work/object scratch and exact-root/suspended-frame native records;
- native unwind/owner wrappers and trace-PC scratch;
- UTF/path/name conversion, whole-file reserve_file_result scratch, directory and
  process/host buffers, with managed result storage allocated separately;
- socket/TLS SSL/BIO/context/native state and explicit native handles;
- generated JNI array/string/copy/carrier, anchor and registration records with
  matched explicit cleanup.

The native test interposers' C destructor attributes close/report native
infrastructure; they are not source destructors or managed finalization. The
remaining realloc sites grow suspended-frame storage and native file/path scratch.
Exact source/log hashes and search commands are in compact verification.

Descriptor physical tail slots are reserved null/false padding, not inert semantic
ownership fields. The compiler emits only null/false there; the semantic IR has
no corresponding cleanup fields. The C Throwable member named `owned_message`
mirrors retained causeMessage text and carries no ownership/release behavior.
Ordinary roots ABI 2 / owner ABI 1 and
bridge schema 2 / objects-gc-v1, root ABI 3 / owner ABI 2 are preserved. Changed
runtime inputs have rebuilt SDK identities listed in the implementation report;
stale source/native/package inputs still fail safely. Platform and foreign-lifetime
gates remain unchanged. GC8 capacity/performance and self-hosting are separate.
