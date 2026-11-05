<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC6 incomplete implementation checkpoint

Date: 2026-11-05. Baseline: `c76a3a75`, following GC5's completed cutover.
**GC6 is not complete. No native bridge/shared-library output is qualified by
this checkpoint.** Work remains local on `codex/bdwgc-integration-plan`.
The [pre-change review](GC6_PRECHANGE_REVIEW.md) records the accepted contracts,
bridge categories, analysis consumers, safety pairs and verification selection.

## Implemented carrier slice

The compiler-owned `_ForeignFailure` now has a typed `_ForeignFailure`
`nextCarrier` field. The factory and next getter preserve that reference in typed
IR and native signatures. Exact physical layout generation traces the chain and
does not trace the numeric JNI cookie. An integer next link still refuses as
legacy hidden metadata.

`takeReference` clears the carrier's JNI cookie and returns its former value.
Generated invocation completion calls this operation and deletes each nonzero
JNI global exactly once, after translation has consumed it. Completion walks the
typed next result, clears its invocation chain and does not destroy, deallocate,
collect or close managed objects. It remains valid with a pending Java exception.
Managed memory can remain reachable independently after completion.

Carrier cleanup no longer generates a destruction function or an
IrFreeInstruction. Entry composition and its two private native fixtures no
longer append that obsolete function. Exact compiler-owned declaration binding
and the existing complete invocation-lifetime proof remain required. The proof
still refuses retained/returned/unknown carriers and callback-capable initializer
failure caches. This is not yet a migration of the older per-entry policy's
process-lifetime fallback; it cannot be used to qualify a production bridge.
Historical internal policy names `reclaims`/`reclaimableRoots` select JNI
completion, not managed reclamation. Broad terminology/dead-code retirement
remains bounded to the appropriate later work.

No source close/free/value/retain/construct member is reinterpreted. No source
destruction, automatic cleanup, weak-reference fallback, synchronization, TLS,
thread guard or scalar-call administration is added. Runtime, statepoint pass,
collector archive, root ABI 2 and owner ABI 1 are unchanged.

## Verification and limits of the evidence

Qualified for this slice on **macOS arm64 only**, with Java 21.0.1, LLVM/Clang
23.1.0, macOS SDK 27.2, BDWGC 8.2.12/USE_MARK_BYTES=1 and the verified GC5
engine-3 SDK configuration
`fa43f3f0db660f004717b875db2d0fe41691291b1d6eb6ab6812d963bcfddffa`.
The SDK was verified offline against all current payload/source hashes. No
runtime/SDK input changed, so no SDK rebuild or relabeling was appropriate.
An initial SDK verification using the command-line-tools SDK 27.0 correctly
refused; using the existing qualified SDK 27.2 restored the exact identity.

Focused compiler selections:

* `GC6 carriers separate exact managed linkage from JNI completion`: source,
  class/archive user-source reconstruction with regenerated private support,
  exact next references versus integer links, JNI-cookie clearing, all nine
  protected operations and absence of legacy deallocation producers.
* `Java Bridge carrier cleanup requires complete nonescaping handler and initialization proofs`:
  direct/discard/rethrow/replace acceptance versus retaining, returning, helper
  publication, merged aliases, causes, secondary associations, unknown calls and
  callback-capable initializer caches. Removed free syntax remains rejected.
* `GC2 exact layouts follow resolved storage and refuse legacy hidden links`:
  retained physical-layout and hidden-link controls.
* The two selected GC5 normal-emission/final-link controls retain foreign-output
  refusals and unconditional legacy managed-deallocation rejection.

The dedicated driver `scripts/test-gc6-carriers.py` uses only bounded children
with the existing 16 GiB process-group RSS guard and 60-second per-child limit:

* The normal compiler builds actual generated carrier source and an executable
  at O0/O1/O2/O3. Two carriers survive collection through only their exact chain,
  cookies stay unchanged by collection, cookie extraction is idempotent and
  allocation-free, completed carriers remain alive through an independent root,
  and dropping that root permits reclamation. This uses the ordinary GC5
  compiler/runtime pipeline and real source-derived layouts.
* An actual Java consumer loads two private native images at each optimization
  level with `-Xcheck:jni`. The actual generated completion fragment runs against
  **protected-operation doubles** over described managed carrier nodes. It tests
  JNI global balances, repeat completion, partial completion/retry, preservation
  of a pending throwable's identity, collection without JNI cleanup, retained
  carrier memory after JNI release and subsequent reclamation.
* While image A holds a rooted graph and JNI globals, B allocates/collects, then
  the roles reverse. Each image checks its own heap, roots, allocation and
  collection counters. Sequential calls from four additional Java threads run
  under the existing external-serialization obligation.
* Actual Mach-O export tries allow only the corresponding JNI entry. Native
  symbol tables retain GC_init, GC_gcollect and iw_gc_collect internally, with
  no collector/adapter imports. This is direct private-engine isolation evidence
  for these two fixture images, not qualification of the production packager.

The JNI operation doubles isolate completion from compiler shim root admission.
These tests **do not** establish JVM reentry, suspended segment coverage, actual
generated JNI entry roots, listener retirement, ByteView borrows or full native
library compatibility. No ELF or Linux execution is claimed. No hot lowering
changed; no scalar/critical-path or transition-capture performance claim follows.
Raw logs, binaries, source-reconstructing artifacts and generated fragments stay
in ignored workspace output. Compact hashes and results are recorded in
[the checkpoint manifest](gc-evaluation/gc6-carriers-2026-11-05-manifest.json).

Reproduce after a normal focused compiler build, using the qualified SDKROOT,
JAVA_HOME and LLVM home:

```sh
python3 scripts/test-gc6-carriers.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc6-carriers-reproduce
```

## Unmet exit criteria and safety blockers

The current root reader explicitly rejects foreign segments. It has one native
main boundary, no image-local suspended-segment chain and no captured managed
machine state to revisit past JVM execution. Treating foreign calls as leaves,
walking HotSpot frames or merely removing the producer/emitter/backend gates
would permit incomplete root enumeration. None of those changes was made.

The following required GC6 work remains unimplemented or unqualified:

1. Generated RootHandle/Roots constructors, retain and all managed reference
   result projections; independent native anchors, facade closed epochs and
   stale-address reuse protection.
2. ListenerRegistration/Listeners projections, preacquired ownership, committed
   slot reconciliation on exceptional exits, replacement/unregistration and
   active callback retirement with exact JNI ownership.
3. Current/suspended native segments, every supported Java helper transition,
   invocation-local C/JNI roots, outer-local/saved-register/derived-base cases,
   deep reentry and A/Java/B/Java/A qualification at O0-O3.
4. Integrated String, copied-array/copy-back, exception/finally/deferred callback
   and borrowed ByteView/resource paths, including actual native/JNI/Java
   acquisition and delivery failure ordering.
5. Schema 2/objects-gc-v1, mandatory native memory/config/layout/root/owner
   metadata, stale scalar-only image refusal and complete production Mach-O/ELF
   export policy; rebuilt affected support inputs and actual JVM consumers.
6. Linux x86-64 integration, retained GC2-GC5 runtime/corruption/OOM controls for
   changed root machinery, O3 scalar/critical inspection, deterministic bridge
   controls and separate transition-capture measurement.

These are outstanding implementation/qualification gates, not evidence of an
external platform failure or a rejected GC0 design. GC6's exit criterion cannot
be marked satisfied. Blanket output refusals, Linux arm64/arm64e gates, async,
cross-image raw-reference and unloading restrictions remain intact.

GC7 is not started and cannot use this checkpoint as its prerequisite. Once
GC6 actually passes, GC7 may migrate remaining repository consumers and remove
obsolete heap-proof scaffolding after its consumer inventory is empty. Preserve
optimization, initialization, native-resource, pool and foreign confinement
facts. GC8-GC10 and self-hosting remain outside this task.
