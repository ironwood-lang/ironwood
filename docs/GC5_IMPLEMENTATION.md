<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC5 integrated compiler, runtime and library cutover

Date: 2026-11-05. **GC5's integrated checkpoint is complete on the migration
branch.** Ordinary native executables and the complete required main/testing
library use one exact, nonmoving managed heap. This is not completion of the GC
project. Native bridge/shared-library output refuses until GC6; repository-wide
consumer migration, performance acceptance and distribution qualification remain
GC7-GC10 work. Self-hosting has not restarted.

Baseline: `23190dcd`, preserving the partial checkpoint and its Linux follow-up,
after GC4 `1b0e7674`/`777a26ff`. All work stays local on
`codex/bdwgc-integration-plan`. The former [implementation checkpoint](GC5_CHECKPOINT_HISTORY.md)
and [Linux partial qualification](GC5_LINUX_X86_64_QUALIFICATION.md) are historical.
Read [MEMORY.md](MEMORY.md), [the pre-change review](GC5_CUTOVER_VERIFICATION.md),
D320 and the [compact evidence](gc-evaluation/gc5-integrated-2026-11-05-manifest.json).

## Compiler and build contract

Canonical public static void ironwood.lang.Memory.collect() validates declaration
identity and signature before lowering to IrGcCollectInstruction. Malformed,
unknown or altered reserved declarations cannot execute an empty library stub.
Format-1 source/class/archive reconstruction retains full semantic revalidation
and useful embedded-source migration paths. Unknown effects remain conservative;
MayCollect survives dispatch, initialization, specialization, pruning and cleanup.

Normal LlvmEmitter/NativeBackend use actual source layouts and runtime ownership,
without experimental projections, unsupported-class filtering or guessed roots.
The Java 21 pipeline remains typed IR, LLVM IR, llvm-as, opt, optimized trace
attribution, the required LLVM 23.1.0 nonmoving statepoint pass and verification,
llvm-as, llc, isolated Clang runtime compilation and native linking. New internal
functions produced by LLVM partial inlining inherit the managed strategy through
validated definition inventories. Original definitions losing it still refuse.

The real type descriptor embeds the exact engine prefix before dispatch and
membership data. Cold startup binds compiler-derived layouts and compares actual
C/LLVM sizes and offsets. The executable binds root ABI 2, runtime-owner ABI 1,
layout/configuration identity, retained map digest and optimized function catalog.
The native main boundary cannot tail-call away its approved frame. Fixed-size
String concatenation scratch is hoisted into the entry block, including invokes;
the dynamic-frame refusal remains intact.

The statepoint pass preserves exceptional-only LLVM-proven base roots in typed
native spills described at their associated collecting invokes. Late verification
requires immediately preceding stores, inventory coverage and no other spill
uses, including fastcc invokes. These are collection-boundary spills, without
continuous maintenance, entry clearing, per-defer registration or heap action lists.

## Allocation, exceptions and failure

Ordinary objects, arrays, Strings and generated heap values use the language
collector domain. Allocation directly uses the qualified zeroing kind, installs
dynamic identity and increments the successful-allocation count. It performs no
collection, polling, TLS update, root registration or registry lookup. Existing
bounds, overflow and allocation-failure precedence remain. Internal metadata,
secondary nodes and pointer-free trace buffers use separate managed sidecar
layouts and are excluded from language counters.

Real Throwable has a reserved managed metadata reference and trace status, with
validated bundled-source authority. Source cause/message/trace/secondary APIs and
optimized trace attribution remain. Native unwind wrappers explicitly own their
live source exceptions. Fixed emergency cells and image-owned exception metadata
have exact native graphs: managed containers can keep those graphs alive, while
dropped graphs lose their children. Traversal is bounded and allocation-free
during marking and replays native edges during mark-stack overflow recovery.
Unreachable fixed emergency payloads and associated native state clear after GC,
without callbacks, pool cleanup or permanent stale roots.

Preparation scratch exhaustion validates all remaining roots, clears/unregisters
scratch and throws cached OutOfMemoryError with no partial reclamation. Corrupt
metadata and returning failure continuations still terminate before collection.
Emergency storage remains bounded. Cached OOM reuse does not detach occurrences
from independently surviving ordinary primaries. Catch/rethrow preserves identity;
D051 preserves the primary and ordered secondary cleanup failures.

Native whole-file construction grows native scratch, closes the descriptor,
allocates one final managed result, copies its initialized payload and releases
scratch. Growth and final-result failure explicitly release native storage.
Managed storage is never passed to realloc/free. Explicit native acquisition
rollback and close calls remain separate from collection.

System counters preserve GC0: successful allocations count even if construction
throws; internal sidecars do not count. Live accounting is cached at completed
collection plus allocations since then. Counter reads do not collect.

## Language and complete required library

The lexer admits ordinary free/destructor identifiers. Removed source free,
deferred free, destructors, --unfreed, --explain-rejected-free and SuppressUnfreed
produce migration diagnostics. Normal lowering no longer produces implicit
full-expression destruction, owned-rendering release or managed constructor
rollback. The final verifier unconditionally rejects all seven legacy managed
release families and destructor/rollback metadata or functions, even without a
reachable collection call. No second supported heap mode exists.

All 77 main-library destructors were classified against GC0's individual body
inventory before removal: 76 contained only managed release, and Throwable also
released its old native trace representation, now a managed sidecar. Explicit
close, pool operations and native acquisition rollback remain. All 255 main
sources and six testing sources rebuild into source-reconstructing class/archive
artifacts. The current development IronDocs reference is regenerated, including
Memory and the reuse/reset APIs; frozen historical versions stay unchanged.

Reference lists clear retired physical slots. Pooled map entries clear retired
keys, values and structural links, while copied-key reusable buffers remain
intentionally retained. ArrayList/ArrayLinkedList expose resetForReuse() for
retaining resets and clearUnusedReferences() for explicit cold cleanup. Primitive
clear paths remain cheap. Pool-created reusable objects remain reachable; known
origin conflicts remain compile-time errors and unknown origins retain the
same-pool, at-most-once caller obligation without runtime membership checks.
Populated ArrayObjectPool checkout has an explicit early-return path; growth and
building preserve pointer advancement only after successful construction.

General defer still captures receiver/arguments once immediately, preserves
identity after reassignment, executes block-scoped LIFO on every exit and retains
D051. Captures and pending return/yield/exception references survive collecting
cleanup. Published failed constructions remain valid graphs with installed fields
and dynamic identity. Initialization, native lifetime and pool protocols remain.

## Verification

Qualified hosts are macOS arm64 and Linux x86-64. Both use Java 21 and LLVM
23.1.0 with pinned BDWGC 8.2.12 and USE_MARK_BYTES=1. Regenerated engine-3 SDKs
bind the actual runtime, root reader, pass and recipe source identities. Normal
linking verifies every payload and refuses missing, stale, extra or incompatible
inputs offline. SDK staging includes source, notices, licenses and manifests at
toolchain/ironwood-gc. This is focused staging validation, not GC9 acceptance.

The evidence manifest records exact commands, case selections, report hashes,
source/payload identities, RSS samples, code fingerprints and timing samples.
The focused selections include:

- Eleven compiler selections: four GC5 intrinsic/effect/migration/final-link
  controls, GC2 real-layout proof, GC3 closure and late-verification proofs, GC4
  owners, and three general defer parser/semantic/typed-capture controls. Obsolete
  manual-heap expectations are explicitly replaced; safety negatives remain.
- Normal source-derived executables at O0/O1/O2/O3: aliases, cycles, sharing and
  dropped roots; collecting constructors and class initialization; published
  construction failure and cached initialization failure; real exception traces,
  collecting cleanup, rethrow identity, captured reassignment, nested finally,
  pending return/yield and labeled break/continue; exact counters and slot removal.
- Allocation-budget OOM, preparation OOM with no reclamation, constructor-failure
  accounting, managed-to-native emergency graphs and later dropped-root release.
  Standalone GC2 controls retain actual bounded collector exhaustion/no-auto-GC.
- Explicit file closure on success and preparation OOM, native whole-file scratch
  growth through 20,000 bytes, managed byte/String/line results, pool retention
  and cold stale-slot cleanup, plus null/throwing/collecting pool builders.
- Ordinary class/archive native parity and ten stale SDK negatives, plus three
  unconditional bridge-output refusals. Embedded obsolete source and canonical
  declaration spoof/malformed controls retain source diagnostics.
- Retained GC2: seven native cases, including 120 observed mark-stack overflows
  at 4,096 entries with recovery. Collector diagnostic time was 3.100 ms for
  this stress, within historical retained samples of 2.783-3.473 ms. These times
  exclude root reconstruction and post-collection accounting. Native emergency
  graph stress separately
  observes overflow while preserving 131,073 exact live objects and later zero;
  its two collector diagnostic times were 1.107 and 1.061 ms.
- Retained GC3: 72 selected native cases on macOS, 80 on Linux including writable
  map and stripped-unwind refusals. GC4: 64 selected native cases on macOS, 72
  on Linux. All modes preserve stale PC/slot/function, ABI/owner, boundary and
  foreign-transition refusals before collection.
- Linux normal images retain PIE, protected RELRO maps, unwind headers, no text
  relocations, no dynamic collector definitions and GLIBC requirements <=2.17.
- O3 inspection and deterministic call/pool defer-versus-finally controls use two
  alternating rounds of 32 million operations. Both have identical normalized
  application code including ironwood_run, with zero warmed allocations/live
  delta. The call's timed native kernel retains the prior 25 instructions after
  relocated-branch normalization. Pool code has no new TLS, polls or registration;
  legacy deallocation disappears from cold growth. The pool helper's native
  frame is 48 bytes versus the prior 32 bytes, with extra register saves rather
  than root maintenance. The longer alternating control below records its timing
  implication instead of asserting byte-identical pool code across heap models.
- A separate same-path GC4/GC5/A-A control uses four alternating rounds of 320
  million operations: call medians 358,154,000/354,446,500 ns; pool medians
  756,908,500/760,252,500 ns. The identical GC5 label measures 359,653,500 and
  760,737,000 ns respectively. These bounded samples do not establish a stable
  throughput regression or replace GC8 performance acceptance.

Every compiler/native child remains bounded with the 16 GiB process-group RSS
guard and 60-second limit. Remote orchestration also enforces an 8 GiB enclosing
checkout cap, at least 20 GiB free disk and 900 seconds per run. Remote use remains
about 1 GiB with over 100 GiB free; its two pre-existing script edits and Git
state are preserved. Raw logs, SDKs, archives, binaries and exports stay ignored.
No unfiltered suite, hosted job, push, main integration or branch/worktree change
occurred. License, tracked-file, generated-doc consistency and diff checks apply.

## Remaining scaffolding and bounded handoff

GC7 retains dead FreeStatement/DeferredFreeStatement/destructor AST shapes,
reclamation IR records, ownership/free proof structures, old option enums,
private legacy lowering/rollback builders and inert descriptor slots. Parser and
semantic entry points reject their source forms; normal producers are disabled
and final linking refuses injected legacy IR. Broad deletion is deliberately
not part of this milestone. Do not delete shared facts still consumed by
optimization, initialization, pools, native acquisitions or resource protocols.

GC6 may qualify generated native/JNI roots, foreign transitions, source/generated
cleanup, library image ownership and the planned RootHandle/listener surface.
All such outputs remain refused, including scalar-only bridge entry points with
allocating initialization/failure paths. No RootHandle/listener API or JVM reentry
qualification is claimed. Linux arm64 and arm64e remain gated. GC7 migrates old
repository consumers/tests; GC8 qualifies critical-path and resident memory;
GC9 qualifies distributions. Stop here without starting those milestones.


Focused reproduction uses fresh ignored output directories and an already
verified SDK, with IRONWOOD_GC_HOME, JAVA_HOME and the qualified native toolchain:

```sh
python3 scripts/test-gc-integrated.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc5-normal --case integrated --case oom \
  --case no_collect --case resources --case pools --case graphs
python3 scripts/test-gc-inputs.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc5-inputs
python3 scripts/test-gc-performance.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc5-performance
```

The performance driver currently inspects macOS arm64 assembly. The evidence
manifest supplies the exact retained GC2-GC4 case selections and compiler test
names; never substitute an unfiltered compiler suite. macOS uses the qualified
SDKROOT, and Linux uses the qualified glibc 2.17 sysroot and pinned lld. SDK
preparation and full distribution acceptance remain distinct from ordinary links.
