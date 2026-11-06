<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 migration and test disposition ledger

Date: 2026-11-06. Baseline `3afea8f8ea9b004ea3d5084b20e1636beea1188d`.
**This is an incomplete checkpoint, not GC7 acceptance.** Read the
[implementation report](GC7_IMPLEMENTATION.md) and
[pre-change review](GC7_PRECHANGE_REVIEW.md). No historical report, provenance
entry or frozen hash was rewritten. No self-hosted compiler was built.

## Reproducible candidate inventory

`scripts/gc-evaluation/inventory_gc7.py` scans tracked source, scripts, IDE,
launch/configuration, packaging and documentation inputs and compares their
contents with the baseline through a read-only Git object reader. It does not
check out another tree. Run with an ignored output path. The baseline candidate
inventory digest is
`919f02225ca961a2e2e7be97b095798b971c3586c5976d5244d7e5c774ba0427`.
These are lexical candidates, not obsolete-code counts or completed dispositions.
Ordinary method names, native frees, allocation-free descriptions and migration
errors are included deliberately.

| Baseline area | Candidate files | Matches | Current disposition |
| --- | ---: | ---: | --- |
| Bootstrap compiler Java | 79 | 419 | Outer free-proof refinement and principal function-level free lowering retired here; remaining AST/IR, metadata and legacy bridge consumers pending. |
| Checked-in compiler Ironwood | 67 | 704 | Repository consumers requiring migration, including AST/proof and port helpers. Pending. No I1/I2 or self-hosting work authorized. |
| Compiler tests | 182 | 3922 | Mixed executable/proof/negative families require individual or explicit parameterized dispositions. Only the focused rows below are migrated here. |
| Runtime | 12 | 76 | GC5/GC6 allocation/resource domains retained. Legacy descriptor/helper declarations still require retirement review. |
| Standard library and reference material | 25 | 408 | Main/testing source already migrated by GC5; current documentation programs and frozen references still need separate classification. |
| Examples | 152 | 342 | Ordinary demonstrations, intentional invalid programs, Java bridge consumers and benchmarks still require migration. |
| Projects | 46 | 159 | Preserve file/socket/TLS close and pool reuse; source, launch settings and immediate live-count expectations remain pending. |
| Integration inputs | 252 | 2646 | GC2-GC6 controls retained; legacy and mixed families require ledger migration. Pending. |
| Scripts/generators | 63 | 274 | GC5 blanket bridge refusals replaced below. Other generators, packaging smoke inputs and benchmark drivers pending. |
| IDE | 14 | 89 | Grammar, highlighting, symbols, templates, output parser checks and LSP diagnostics migrated here. |
| Documentation | 453 | 4275 | Separate current specifications/programs from historical decisions/evidence. Broad migration pending. |
| Packaging | 1 | 1 | BDWGC configuration's pointer-free description is legitimate, not a reclamation producer. |

Root guidance/README/CLA matches are included in the machine inventory too.
No standalone benchmarks directory exists; benchmark consumers include
`examples/bench`, bridge benchmark programs, project workloads and test generators.
The frozen GC0 manifest remains the detailed destructor/provenance baseline.

## Analysis outputs and consumers

O = obsolete managed reclamation; P = actual optimization; R = resource,
initialization, pool or foreign contract; G = collection effects, roots or layouts.

| Producer/output | Consumers and disposition | Checkpoint result |
| --- | --- | --- |
| SemanticAnalyzer field/return/temporary refinement | O: repeated owned-field construction, TemporaryBorrowAnalysis/TemporaryListBorrowAnalysis convergence, argument-reclaiming relowering and readiness verdicts | Removed from ordinary and bridge semantic entry paths. No replacement managed-free fixed point. |
| FunctionAnalyzer free proof and generated releases | O: deferred-free action, operand proof, accepted frees, loop-back reclamation checks, full-expression temporary candidates, rendering release lists and free-specific witnesses | Principal producers and computation removed. Remaining field/alias/protocol state still needs narrower extraction before wholesale deletion. |
| OwnedArrayFieldAnalyzer exclusive field precomputation | O: owned fields/borrowed returns for managed destruction | Eager computation removed. Lazy encapsulation queries remain for actual retention consumers. Entire legacy container and checker are not yet retired. |
| EscapeSummaryAnalyzer, SymbolicReturnOriginAnalyzer | R: pool helper origins, foreign input confinement; P: resolved value/dispatch consumers | Retained conservative reference effects. Their internal escape/return fixed points are distinct from the removed outer free-proof loop. Ownership-specific helpers and evidence state still pending. |
| BorrowDispatchAnalysis, initialization/null facts | P/R: dispatch, lowering, initialization and exception behavior | Retained. No broad nonretaining summary or runtime bookkeeping introduced. |
| ClosedWorldEffectAnalyzer | O: reclaimed-argument/destructor analysis in normal semantic lowering; remaining legacy bridge/diagnostic callers | Removed from SemanticAnalyzer's pipeline. Class and residual callers remain pending; final GC effects use GcEffects. |
| GcEffects and exact root/layout analyses | G: final native program, initialization, foreign transitions and statepoints | Retained, including unknown MayCollect. No runtime/root ABI change. |
| BridgeConstructionFacts ownership rollback capability | O: legacy BridgeRollbackAnalysis/cleanup/root-retention paths | No longer projects a proved empty ownership contract. Legacy constructor rollback capability is unknown; managed admission is verified separately. |
| PoolReleaseAnalysis/PoolSemantics and reference provenance | R/P: known wrong-pool rejection, helper extraction and allocation-free reuse | Retained. GC5 paired same/wrong-pool controls and native pool controls pass. |
| TestHarnessSynthesizer | O: synthetic runner FreeStatement and missing-free suppression premise | Removed. Runner/suite use managed lifetime; dispatch and registration order preserved. |
| Free/destructor AST, release IR, descriptor flags, caches, CLI enums and legacy builders | O: parser recovery, visitors, cloning, LLVM emission, legacy bridge paths and tests | Private SemanticAnalyzer destructor/rollback builders removed. Broad structural retirement is still pending. Final-link rejection remains mandatory. |

The remaining field/return consumers cannot safely be replaced by invented
nonretaining summaries or by treating managed reachability as foreign capability
lifetime. This dependency closure is the unresolved implementation boundary.
The checkpoint does not certify that all retained analysis is necessary.

## Executable tests and parameterized families

| Existing test or explicit family | Disposition | Replacement/retained evidence |
| --- | --- | --- |
| `@Test synthesizes deterministic TestSuite runners` | Migrate runner-free count to zero and accept ordinary abandoned managed locals. Preserve dispatch order, registration count, nested suite identity and diagnostics. | Same exact test; generated wizard suites compiled and run at O0/O3. |
| `@Test rejects invalid suites and methods` | Preserve declaration, signature, static/nesting and generated-member boundaries. | Same exact test. |
| GC5 unconditional bridge option family `{--java-bridge-library, --java-bridge-assemble, --java-bridge-distribution}` in `test-gc-inputs.py` | Retire three GC5-only blanket refusal expectations, superseded by GC6. Do not reinterpret invalid argument invocation as managed lifetime qualification. | Actual GC6 generated object/callback JVM controls and existing GC6 production-image controls. Object/callback O3 rerun here; image controls not rerun here. |
| GC5 class/archive SDK corruption family | Preserve ten stale/missing/mismatched payload/configuration refusals and class/archive native parity. | `scripts/test-gc-inputs.py`, all 16 remaining child checks. |
| IDE rejected-free diagnostic/warning fixtures | Split into general unresolved-method/name errors, related-note parsing, removed-syntax migration error and clean managed allocation. | Eclipse output parser and stdio LSP tests pass. No missing-free warning is expected. |
| IDE destructor symbol/highlighting fixture | Replace with ordinary `free`/`destructor` methods and a captured deferred call. Remove destructor AST symbol branches. | TM4E scopes, compiler fixture compilation and LSP method symbols. |
| IDE wizard immediate-reclamation example/test | Replace with ordinary managed greeting and shared result surviving explicit collection. Fix assertion argument order to the actual testing API. | Template generator verification and O0/O3 native programs. |
| Legacy rollback projection | Add a nearby safety control: no legacy capability while managed object admission remains proved. | `GC7 managed construction never grants legacy rollback capabilities`. |
| Free ownership/explanation/readiness, destructor, temporary, deferred-free and mixed native families | Pending full disposition. Do not silently delete or re-register all of them as one syntax test. | Existing GC5 migration/graph/exception/defer controls retained, but not sufficient to declare every old family migrated. |

No old compiler test family was disabled to make the focused run pass. Old
manual-model selections remain registered and are not claimed to pass. No
unfiltered suite, or reconstruction of one through all groups, was executed.

## Native domains and historical evidence

The current search still finds release-IR cloning in IrCfgRenamer and
PrimitiveGenericSpecializer, a legacy BridgeEntryModule destruction builder,
parser recovery constructing FreeStatement, and legacy AST/IR record definitions.
These are pending retirement, not additional supported memory modes. Final
managed verification rejects reachable legacy releases and cleanup metadata.

Runtime sources contain no `GC_free`, `ironwood_deallocate` or owned-rendering
release implementation. Libc frees remain for native unwind wrappers, native
String/path/UTF conversion scratch, whole-file construction scratch, directory
handles, process/host buffers, JNI records and TLS/socket resources. In
`ironwood_runtime.c` the whole-file `result` frees release reserve_file_result's
native scratch; the distinct `managed` result is never freed by libc. Runtime
sources and physical descriptors are unchanged in this checkpoint. Generated
foreign native records still require their explicit matched releases.

Historical GC0-GC6 reports, manifests, archives/hashes and stopped self-hosting
records remain frozen evidence. Historical status does not exempt checked-in
compiler `.iron` sources or maintained generators from their pending migration.
