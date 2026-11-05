<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC5 incomplete implementation checkpoint

Baseline: `1b0e7674`, following GC4 implementation `777a26ff`. Work is local on
`codex/bdwgc-integration-plan`. **GC5 is incomplete. Normal native collection is
refused.** This record describes a guarded private checkpoint, not the requested
integrated memory-model cutover. Read with the
[integration plan](BDWGC_INTEGRATION_PLAN.md) and
[compact evidence](gc-evaluation/gc5-checkpoint-2026-11-04-manifest.json).

## Pre-change review

GC5 replaces managed reclamation proofs with explicit synchronous collection.
Preserve Java 21, LLVM 23.1.0, pinned BDWGC 8.2.12 engine-2 with USE_MARK_BYTES,
exact roots/layouts, full zeroing and dynamic identity, D051, D132/D133, native
resource cleanup and pool-origin/reuse contracts. Allocation and counter reads
must never collect. No legacy managed destruction may reach a collector image.

Affected contracts and analysis consumers:

- Source reconstruction feeds parsing and full semantic validation for source,
  class and archive inputs. Canonical collection declarations must be validated
  before lowering, including unused malformed declarations. The dedicated
  collection instruction feeds GcEffects, specialization, pruning and LLVM.
- FunctionAnalyzer's temporary, rendered-result and constructor-failure cleanup
  and SemanticAnalyzer's destructor/rollback producers are managed-reclamation
  consumers to retire. Initialization/type checks and native acquisition failure
  cleanup remain. Escape/dispatch facts used by optimization or resource/pool
  protocols remain conservative. GC7 owns deletion of dead proof scaffolding.
- Exact physical fields, inherited/captured/generic fields, array elements,
  static initialization failures, pending return/yield/exception values and
  deferred captures feed layout/root generation. Unknown calls remain unknown.
  The GC4 fixture projection is not a source representation or admission rule.
- Native type descriptors, ordinary allocation, inline Strings, file-result
  growth, Throwable metadata, secondary links, trace rendering, live unwind
  wrappers and bounded emergency state must migrate together. Native scratch
  retains matched native cleanup. Collector storage never reaches realloc/free.
- Reference-container physical slots and retired map links require clearing;
  intentional pool reuse remains reachable. Any retaining reset needs a named
  operation and a cold cleanup contract, without per-operation tracking.
- Native outputs must bind root ABI 2, runtime-owner ABI 1, configuration,
  layouts and the nonmoving statepoint pass. GC6 bridge/shared-library and
  foreign-root paths remain refused, including scalar exports with allocating
  initialization or failure paths. Linux arm64 and arm64e remain gated.

Paired cases: canonical collect versus malformed/spoofed declarations; source,
class and archive parity versus embedded obsolete forms; live aliases/cycles
versus dropped-root reclamation; pointer fields versus pointer-shaped integers;
collecting initialization and published failed construction versus native
acquisition rollback; live exceptions/deferred captures/pending values versus
stale metadata; same-pool reuse versus wrong-pool return; explicit resource
close versus collection without closure; successful preparation versus scratch
OOM with zero collections and corrupt metadata refusal.

Focused verification selection: registered GC5 parser/semantic/typed-IR and
artifact tests; normal source/native programs at O0/O1/O2/O3 for roots,
construction, exceptional/deferred cleanup and accounting; retained GC2 layout,
GC3 root and GC4 owner controls relevant to changed components; exact existing
defer, pool, trace, allocation-failure and resource controls with obsolete heap
expectations explicitly adapted. Inspect O3 noncollecting machine code and run
the deterministic call/pool benchmark if hot lowering changes. All subprocesses
use bounded execution and the existing 16 GiB process-group RSS guard. No full
suite, hosted workflow, push, bridge API migration or self-hosting is authorized.

## Implemented subset and revised verification scope

The new `ironwood.lang.Memory` source declares public static void collect() with
no overloads. This is an original Ironwood API, not an OpenJDK translation or a
Java System.gc compatibility claim. It promises explicit synchronous collection
with no cleanup callbacks and no allocation-triggered collections. Its no-argument
signature admits no numeric widening cases. Final/private class construction and
the canonical declaration are validated before ordinary source lowering.

Declaration authority uses an exact bundled-source fingerprint, following the
existing ByteView precedent. Malformed signatures, unknown members, altered
bodies and forged declarations diagnose before executing a stub, including unused
declarations reconstructed from class/archive sources. Noncanonical user classes
named Memory keep ordinary method behavior. Format-1 containers are unchanged.
The complete main/testing libraries and their artifacts rebuild; the main archive
contains all 255 source files, including identical embedded Memory source. This
rebuild does not mean the other library sources have migrated to GC semantics.

FunctionAnalyzer emits IrGcCollectInstruction through ordinary analysis.
BorrowDispatchAnalysis correctly records no new reference flow, without granting
borrowing exemptions. Primitive specialization preserves the operation. GcEffects
recomputes transitive MayCollect through calls, dispatch, initialization and
cleanup; unknown effects remain conservative. ClosedWorldEffectAnalyzer records
catchable preparation failure without inventing language allocation, publication
or parameter deallocation. Old destructor exceptional-path restrictions remain
active in this incomplete checkpoint.

GcManagedHeapVerifier protects final GC linking against seven legacy release
families, destructor/rollback descriptors and cleanup functions. The admitted GC3
and GC4 native closures call it unconditionally. Normal final linking calls it
when collection survives reachability pruning; ordinary LLVM emission still
refuses collection altogether and returns a diagnostic. When the normal managed
allocator is eventually activated, its final verifier must run for every GC image,
including images with no reachable collection instruction. No mixed image or
second supported memory mode is introduced here.

The source-backed GC3 enum-specialization effect test previously passed legacy
destruction metadata plus injected collection through final linking. That is now
an intentional negative case. It still runs the actual optimization and pruning
passes separately to verify cloned effects. Its admitted native closure and all
root controls remain unchanged. No test or safety check was disabled.

## Collection preparation failure

ABI-2 callers can select one image-owned runtime OOM continuation during cold
setup. The reader checks that its address belongs to executable image storage;
null, duplicate and ABI-1 setup cannot select it. No ordinary call or reference
operation consults this state.

Scratch exhaustion now records capacity failure and validates the rest of the
complete root inventory. A later invalid root still takes the fatal refusal,
not the OOM continuation. Once complete validation establishes capacity as the
failure, the reader unregisters/zeros scratch with GC disabled and delivers OOM.
No mark/sweep operation has run. A returning continuation refuses with exit 86.
ABI-1 and ABI-2 callers without the new continuation retain fatal capacity refusal.

The new typed invoke control catches the original immortal OOM through GC4's
actual native unwind transport. Internal/native allocation is deliberately
exhausted, forcing its bounded emergency wrapper. Both successful language
allocations remain accounted for; zero collections occur; native resources
remain open until explicit acquisition abort. Closing the anchors and collecting
again succeeds and reports zero language/internal live objects. Corruption after
capacity exhaustion and a returning continuation both refuse before collection
while explicit resource cleanup still balances.

The engine-2 SDK, pinned collector, configuration, USE_MARK_BYTES and payloads are
unchanged. Root ABI 2 and runtime-owner ABI 1 remain separate source-built inputs;
their new hashes are recorded in this checkpoint's evidence. They have not been
silently added to an old SDK or advertised as an integrated native generation.

## Verification

Executed locally on machost, macOS arm64, Java 21.0.1, LLVM 23.1.0, SDK 27.2,
deployment 14.0 for the GC native matrices. No dependency download/rebuild occurred.
Raw reports and generated files remain ignored under `workspace/gc5-*`.

- Four registered GC5 selections pass: canonical source/class/archive resolution,
  source effect propagation, incomplete native-output refusal and final-link
  rejection of each legacy release family and stale cleanup descriptors.
- Thirteen exact existing controls pass, covering GC2 layouts, GC3 effects/late
  verification, GC4 owners, paired safe/unsafe free proofs, three pool helper
  contracts, deferred/finally ordering, catchable allocation failure and native
  Throwable trace behavior. The old heap tests validate the still-active legacy
  normal runtime; they do not qualify its GC replacement.
- The GC4 native matrix passes **132 cases**, 33 scenarios at O0/O1/O2/O3:
  all 120 existing cases plus 12 new catchable/preparation-refusal executions.
  The GC3 matrix retains **148 native cases** and both structural ELF controls.
  Structural objects do not qualify native Linux execution.
- O3 Node.read (**8 bytes**), Main.scalar (**48 bytes**) and collecting Node.step
  (**32 bytes**) are byte-identical to the GC4 macOS baseline. Read/scalar contain
  no calls, polls, TLS or root registration. The call/pool deterministic controls
  run two alternating rounds of 32 million operations per variant, with zero
  warmed allocations/live delta and identical deferred/handwritten assembly.
- Every native child retains its 60-second bound and sampled **16 GiB**
  process-group RSS guard. The primary GC4 matrix's largest sample is 901,008 KiB;
  GC3's is 787,696 KiB. These are sampled values, not resident-capacity claims.
  Compiler build/test and benchmark selections also run under that guard.

The evidence records exact names, commands, successful/failing run disposition,
report/input hashes, counters, fingerprints and benchmark timings. License,
tracked-file, Python syntax, local documentation-link, forbidden-character and
diff checks accompany the commits. No full compiler suite was run.

## Unmet integrated exit criteria and bounded continuation

**The normal compiler and required library do not yet build and run with the GC
model. GC5's exit criterion is unmet.** The concrete unresolved integration is:

| Required contract | Remaining implementation |
| --- | --- |
| Actual dynamic type/layout identity | Normal ironwood_type_info and GC2 iw_gc_type are different descriptors. Replacing the allocator without unifying them breaks dispatch, instance tests and exact scanning. No type filtering/projection is an acceptable normal path. |
| Real Throwable/trace ownership | Source traceState is still long; production metadata, trace rendering and emergency associations retain native ownership. GC4's projected typed metadata and raw PC snapshots cannot replace the full source APIs by relabeling them. |
| Managed allocation domains | Ordinary allocation still uses calloc. File-result growth still passes its result to realloc, and production trace/result/rendering paths still free storage. These require coordinated domain-safe replacement and failure cleanup before enabling reclamation. |
| Source and generated reclamation | free/defer-free/destructor syntax, obsolete options/directives, temporary destruction, rendered-result release and constructor rollback remain active. Ordinary free/destructor identifiers and published failed construction are not yet admitted under the new rules. |
| Required library and containers | The complete library's source reclamation forms, stale reference slots/entry links and measured retaining-reset contracts still require migration. Rebuilding artifacts alone is insufficient. |
| Native inputs and boundaries | The normal pipeline does not yet bind actual root-2/owner-1/layout/pass identities into verified GC native inputs. GC6 bridge/shared-library refusals must accompany activation, including scalar initialization/failure paths. |

No free/destructor AST or proof infrastructure was broadly deleted. Its current
normal-runtime role remains active, so it cannot yet be called dead scaffolding.
GC7 deletion follows actual producer retirement; retain optimization,
initialization, pool-origin and native/resource facts during GC5.

Continue **GC5**, first unifying actual type/Throwable representations and native
allocator domains, then retiring managed lowering while migrating all required
library sources and enabling the normal verified root pipeline. Carry forward
these paired controls and GC0 counters. Do not start GC6/GC7 or self-hosting from
this checkpoint. Linux x86-64 retains its earlier GC4 qualification, but this new
subset was not executed there. Linux arm64 and arm64e gates remain closed.
GC6's RootHandle/listener API, JNI retirement, foreign transitions, JVM reentry
and multi-image qualification remain later work; no GC6-ready handoff is claimed.
