<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P5 listener callbacks: implementation and verification log

Status: authorized and in progress, 2026-09-28. Starting revision `792def85`.
Work stays on local `java-bridge`, without pushes or changes to the official
OrderBook project. The maintainer accepted the previous measured implementation
under D225 and separately authorized P5. P7 remains deferred. Release work
belongs to the maintainer.

## Scope and checkpoints

The callback contracts and exit criteria in `JAVA_BRIDGE_PLAN.md` remain
authoritative. Unsupported callback signatures must remain rejected until the
complete safety and transport path is implemented and tested.

1. Add typed foreign-call foundations and conservative effects. Trace them
   through dispatch, escape/ownership, destructor effects, reachability, CFG
   copying, exception containment and artifact reconstruction.
2. Generate ordinary Java listener interfaces and hidden native proxies for
   synchronous calling-thread callbacks. Pass invocation context only through
   callback-reachable paths; preserve ordinary native specializations.
3. Implement nested frames, original Java throwable identity, catch/replace/
   retain carrier cleanup, and active-use guards for suspended receivers and
   dependent arguments. Reuse existing native failure translation.
4. Add retained-listener registration/removal with explicit global-reference
   ownership and cleanup, then supported String/native argument transport.
   Reject callbacks combined with independent-root retention changes.
5. Add `examples/java-bridge/listeners/`, demonstrating a result processor and
   Java `onResult(long sequence, long value)` listener. Measure equivalent
   native/native, Java/Java and native/Java workloads on Linux. Keep OrderBook
   sources and official benchmark definitions unchanged.
6. Verify source/class/archive parity, supported JDK/target checks, nested
   stack use, failure injection, allocation behavior and optimized machine
   code. Record exact payload identities with measurements.

## Pre-change contract and consumer review

- Mandatory reclamation proofs remain active in every unfreed mode. A foreign
  call may retain, allocate, throw and reenter; an interface signature never
  grants borrowing. D132/D133 forbid new overhead on callback-free native and
  permanent scalar paths. D206 requires independent noncritical String buffers.
- `BridgeApiFacts` inventories interfaces but `BridgeExportSurface` currently
  rejects them. Signature discovery alone cannot grant executable admission.
- Typed call edges feed `BorrowDispatchAnalysis`, `ClosedWorldEffectAnalyzer`,
  temporary/pool borrowing, owned-array analysis and the bridge retention,
  rollback, cleanup and non-reclamation proofs. Unknown edges must stay unknown
  for every proof consumer. A new foreign operation must survive SSA cloning
  and protected invokes without being optimized as pure or nonthrowing.
- Public producer admission, generated entries and final-link validation must
  agree. Hidden proxy reconstruction must precede the relevant semantic proofs;
  adding a JNI call only during LLVM/C emission would bypass those proofs.
- Paired cases: harmless primitive callback versus reference publication;
  callback-free safe cleanup versus unknown foreign cleanup; direct versus
  interface/helper calls; normal versus throwing/nested callbacks; listener
  removal versus retained listener; eligible free versus free during a
  suspended native invocation; unchanged throwable versus catch/replace/retain.
- Focused existing controls: `pool release helper proofs preserve mandatory
  safety`, `pool release helper proofs survive artifact reconstruction`, and
  `pool release helpers preserve borrows and allocation-free reuse`. Select
  bridge retention, String, exception and producer controls as each affected
  path changes. Add exact new test names and outcomes at each checkpoint.
- Crash, stack and allocation-failure probes run in child processes. Hardware
  and translated results remain distinct. Estonia is authorized; its disk is
  nearly full, so use fresh RAM-backed scratch where possible, preserve old
  files/evidence, and ask before any installation.

## Checkpoints

- Canonical root, both origin URLs, clean tree and `java-bridge` verified.
  Read contribution requirements, license mechanics, regression lessons and
  authoritative P5 contracts. D226 records authorization and the separate example.
- `3bac32ee`: typed foreign-call foundation added. Foreign calls remain incomplete edges
  for bridge retention/non-reclamation, preserve protected unwind edges and SSA
  operands, and propagate unknown allocation, publication, return-alias and
  possible reclamation effects through helpers and dispatch. Source escape and
  symbolic-return summaries consult the bound foreign body instead of trusting
  its source stub or an audited borrowing exemption. LLVM emission refuses
  these operations until invocation-context lowering is admitted; the public
  producer still rejects listener interfaces in every unfreed mode.
- Java 21 / LLVM 23 build and seven focused tests pass. New exact test:
  `Java Bridge typed foreign calls preserve conservative effects and unwind edges`.
  Existing controls: the three pool checks listed above, `Java Bridge unwind
  reachability requires complete nonraising helper proofs`, `Java Bridge
  non-reclamation rejects unknown and dynamic deallocation`, and `Java Bridge
  generated non-reclamation checks include destruction and fixed transport effects`.
  Log: `workspace/java-bridge/p5-foundation-test.log`. License audit and
  `git diff --check` pass. No callback runtime or performance result is claimed.
- Callback reachability/context planning now follows complete direct/dispatch,
  initializer and cleanup edges, including recursive components and protected
  invokes. Entry initialization is distinguished from target-body effects.
  Missing targets and unclassified runtime effects require context and leave
  the plan incomplete. This analysis grants no export, borrowing, reclamation
  or exception-containment permission. Ordinary scalar controls remain free of
  callback context. Runtime trace CAPTURE/RELEASE/COMMON were inspected before
  classifying them as fixed native operations.
- Exact focused check passed: `Java Bridge callback reachability covers
  dispatch initialization cleanup and unknown edges`, recorded in
  `workspace/java-bridge/p5-context-test.log`. The initial fixture lacked
  required `@Override` declarations; corrected those. Its first entry-only
  initialization control also read a static field, which correctly introduced
  an initialization edge in the body; changed that control to return a literal
  while preserving the side-effecting class initializer. No proof was relaxed.
  The test pairs pure native and injected foreign/missing targets, covering
  helpers, interface alternatives, recursion, cold initialization, destruction
  and protected calls. License audit and diff checks pass.

- Hidden proxy declarations now have deterministic collision-checked identities.
  Their typed foreign bodies replace source stubs in both provisional and final
  lowering, before mandatory source safety, and survive generic IR copying.
  Exact test `Java Bridge listener proxies participate in mandatory source safety
  and artifact reconstruction` passes under pinned Java 21. It pairs accepted
  native-only borrowing with foreign rejection in every unfreed mode, rejects
  stale source/proxy pairs, and compares source, loose-class and archive IR.
  Log: `workspace/java-bridge/p5-proxy-test.log`.
- The attempted nullable-listener destructor control was already invalid without
  Java callbacks: nullable dispatch may allocate and throw. It cannot serve as
  evidence of a new foreign-effect restriction. Existing typed foreign effect
  tests retain paired allocation-free/nonthrowing cleanup checks and protected
  foreign-call coverage. Those checks, the three pool controls, and `proven
  destructor receivers preserve mandatory safety` pass after proxy integration;
  log: `workspace/java-bridge/p5-proxy-controls.log`. No safety proof was changed
  to admit the invalid control. License audit and diff checks pass.

- `cdcd806c`: committed proxy integration and its verified source/artifact safety
  checkpoint above.
- Explicit context specialization now preserves original native functions and
  slots, adds context-bearing direct/recursive/dispatch alternatives, and binds
  each foreign call to its caller's context parameter. A pure native alternative
  in a mixed dispatch slot receives the matching ABI but its body is unchanged.
  Context is never stored in proxy fields. Foreign effect proofs remain unknown.
  Entry initialization and implicit cleanup requiring callbacks are explicitly
  refused until those ABIs can carry context; unknown graphs and stale plans are
  refused as well. Producer admission remains disabled.
- Pinned Java 21 exact tests pass: `Java Bridge invocation context preserves
  native ABI dispatch recursion and unwind edges` and the existing typed foreign
  effect test. Log: `workspace/java-bridge/p5-context-lowering-test.log`. The new
  check exercises generated source proxies, two dispatch alternatives, recursion,
  protected calls, unchanged native bodies, frame SSA copying, double-binding
  refusal, stale facts, and initializer/cleanup/unknown-edge rejection.

- `278495cd`: committed explicit context specialization and its focused checks.
- Private long/void callback LLVM emission now requires a bound context and a
  consistent adapter signature. Unbound calls, smaller/reference carriers and
  colliding/inconsistent adapter symbols remain rejected. P0 scalar protected
  entry mechanics were extracted without behavior changes and reused by the
  private transport experiment. Public producer admission remains disabled.
- Exact private native test passes on macOS ARM64 at O0/O3 with pinned Java 21
  and LLVM 23: `Java Bridge private long callbacks preserve nested frames and
  contain native unwinding`. Child JVMs run `-Xcheck:jni`, nested success/failure,
  unchanged Java throwable identity, stopped native iteration after failure,
  zero native allocations on success, and `IRONWOOD_ALLOCATION_LIMIT=0`.
  The test uses a native failure sentinel solely to verify transport containment;
  it does not implement retained carriers or native catch/replace semantics.
  Existing typed foreign effects, scalar protected entries, and production scalar
  O0/O3 allocation/ABI checks also pass. Log:
  `workspace/java-bridge/p5-transport-controls.log`.
- ARM64 transport evidence:
  `workspace/java-bridge/evidence/p5/long-transport/run-17372304349134339783/`.
  O0 SHA-256 `143d609435124f6d1a4a3fc149a9c93a0f6599c42eea55f95701d1b36fb796a7`;
  O3 `21c8bfdf9fbd75d28e91c24ac7aff523da99b82b3dfdf18a5096010c7923bd52`.
  Inspected O3 loop: explicit context register, one adapter call per iteration,
  arithmetic/branch only otherwise; no TLS, allocation or registry work. Commands,
  tool versions, emitted IR, child output and disassembly accompany the payloads.
  Fixed initial C/LLVM symbol spelling mismatch by using an ordinary generated
  wrapper, avoiding platform-specific assembler aliases. License/diff checks pass.

- `160287a0`: committed private callback transport and shared protected entries.
  Estonia physical Linux x86-64 passed proxy source/class/archive safety, context
  specialization, and private callback transport at O0/O3. Java 21.0.12.1+1,
  LLVM 23.1.0, existing Docker image
  `sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7`,
  isolated CPUs 1-4/9-12, network disabled. No tools were installed.
  Source/class payload `f26f1060ddd9d36d7e74ad0b422cf9f7b71b9aa3619ae57e730bb0a8cd875a31`.
  O0 library `043439601e6718d77d2bbe61045fa515385b7424b5b25155d58909ea4099e976`;
  O3 `0f8971a736047c6c758f833e125ba52372b998d1a3247a8c4a7fb4eba15c4040`.
  O3 disassembly confirms a register-passed context and one adapter call per
  iteration, with no TLS or registry work in the native loop. This is transport
  correctness/code-generation evidence, not full P5 qualification or performance.
- Estonia evidence and exact runner are preserved under
  `~/temp/java-bridge/p5-160287a0/`, copied locally to
  `workspace/java-bridge/p5-estonia-160287a02a53/`. Payload manifests, revision,
  commands, child logs, versions, emitted IR and disassembly are included.
  First container extraction failed because its new tmpfs was not writable by
  UID 1001. Retried after setting permissions on only the new scratch mounts,
  then dropped to UID/GID 1001. Both containers and all pre-existing files remain.
  The evidence archive includes the pinned support sources and occupies 202 MB;
  host free space is now about 235 MB. For future runs, stream evidence to this
  Mac or omit duplicated support source archives while retaining their hashes.
- D227 records the maintainer's explicit decision to preserve native exception
  lifetimes: retained/unknown carriers remain process-live; only proved
  nonescaping compiler-owned carriers can be cleaned after all native uses end.
  This resolves the ownership choice without weakening source `free` rules.

- Active-use component now emits counter/check/methods only for a final admitted
  image containing foreign calls. Existing callback-free root state has no new
  field or operation. Generated nested `try/finally` scopes balance successful
  receiver/argument-owner acquisitions, including aliases, null inputs and nested
  invocations. Only stable pre-evaluated root locals are accepted by the wrapper
  generator. Full producer integration remains gated on P5 lifetime admission.
- Exact new test passes: `Java Bridge active callback guards balance aliases
  failures and refused frees without allocation`. It compiles the actual state
  and guard templates, checks exact artifact-private refusal identity and
  unchanged destruction counts, failure during partial acquisition, nesting
  overflow, Java callback exceptions, aliased owners, and 500,000 warmed guard
  operations with zero allocated Java bytes (`-XX:-DoEscapeAnalysis`). Existing
  root-state weak-cache/fault controls also pass. Logs:
  `workspace/java-bridge/p5-active-use-test.log` and `p5-active-use-final.log`.
  This is Java component evidence, not an admitted native-facade callback test.

- `9321d5a6`: committed active-use components and focused allocation/refusal checks.
- D227 carrier-lifetime analysis now binds to the exact program and root set.
  It traverses complete call/dispatch/initializer/cleanup closures, tracks landed
  exception aliases through casts and phi nodes, and accepts only known
  nonpublishing observations and outward rethrows. Stores, ordinary returns,
  helper/foreign uses and unknown effects retain the carrier. Failed initializer
  caches and callback-capable cleanup/rollback never acquire temporary ownership.
  This analysis grants no source `free`, export, or standalone destruction right.
- Compiler-owned native carrier declaration added, with immutable opaque Java
  reference and invocation-chain fields, a native factory and reference readers.
  Binding requires matching semantic API facts and exact generated source;
  collisions and altered factories are rejected. The representation is an
  internal unchecked native exception; outward identity uses its Java reference.
  Runtime allocation/global-reference/cleanup integration is still pending.
- Exact new test passes in all unfreed modes: `Java Bridge carrier cleanup
  requires complete nonescaping handler and initialization proofs`. Positive
  controls: direct propagation, discard, rethrow and replacement. Negative
  controls: static retention, returned carriers, helper publication, phi aliases,
  unknown helpers, initialization failure caches, stale facts and source free of
  caught objects. The generated declaration compiles under ordinary mandatory
  safety and rejects changed source. Log: `workspace/java-bridge/p5-carrier-lifetime-test.log`.
  Fixed a test assertion to match the existing `cannot prove free` diagnostic;
  the compiler already rejected the source. License audit and diff checks pass.

Runtime checkpoint pre-change review: add protected carrier construction/reference entries
and generated JNI exception capture. Exercise native discard, replacement,
retention and later rethrow, nested frames and native/JNI allocation failures in
child JVMs at O0/O3. The existing native exception translator handles replacement
and allocation failures. Successful callbacks must remain allocation-free.
This checkpoint deliberately grants no cleanup permission: until destruction is
proved, its private harness uses D227's conservative process-live lifetime.
Public callback admission stays rejected. Shared source reclamation and foreign
effects are unchanged; rerun their focused controls with the new carrier tests.

- Protected carrier creation/reference entries and generated cold JNI capture
  now preserve throwable identity on the native object. Capture clears pending
  JNI state before ordinary JNI work, releases the temporary local reference,
  and transfers the global reference only after protected factory success.
  Factory failure deletes the global reference and raises the actual native
  failure inside the enclosing protected invocation. JNI global-reference
  exhaustion raises native OOM; original identity is not promised when retaining
  that identity itself runs out of resources. Native handlers can catch that OOM.
- Exact test `Java Bridge native carriers preserve catch replacement retained
  identity and allocation containment` passes on macOS ARM64, Java 21 / LLVM 23,
  O0/O3. Child JVMs use `-Xcheck:jni`; test native discard, actual replacement
  translated through existing generated exception support, retained rethrow in
  a later invocation, two failures in one frame, nested frames, global-reference
  accounting, native allocation limit zero and injected JNI global-ref failure.
  Ten thousand successful calls allocate zero native objects. Retained and
  discarded carriers in this private harness deliberately stay process-live;
  this is not evidence of temporary cleanup or public callback admission.
- Evidence: `workspace/java-bridge/evidence/p5/carriers/run-4578553936106605441/`.
  O0 image SHA-256 `8d99b3e3152448f50f2d4baac019e06682db7fea5a4d38efbc5272fc9412dfab`;
  O3 `8589cd12e116bada35ffdd555c5b3b5830060da9f7d0eaad49505a6ac503b898`.
  O3 adapter contains the Java call and mandatory pending-exception check on
  success; carrier work is in the outlined failure path, without TLS or registry
  access. Commands, generated IR/C/Java, hashes and disassembly accompany images.
  Logs: `p5-native-carriers.log`, `p5-carrier-controls.log` in
  `workspace/java-bridge/`. Carrier lifetime and conservative foreign effect
  controls pass. License audit and diff checks pass.

Temporary cleanup pre-change review: `BridgeCleanupAnalyzer` currently refuses
the native Throwable trace-release operation as unclassified. Runtime inspection
confirms RELEASE frees private trace/secondary metadata without allocating,
throwing, invoking callbacks or reclaiming another Throwable. Classify only
RELEASE, preserving existing rejection of capture/array/unknown effects. Require
matching carrier source, fresh nonnullable factory result, proved construction,
complete nonthrowing cleanup and nonescaping invocation lifetime together before
generating destruction. Test native cleanup after discard/propagation/replacement
and contrast retained/unknown/initializer/secondary-chain uses; retain source-free
rejection. Existing destruction and foreign-effect controls cover shared consumers.

- `095a31b9`: committed native carrier construction and identity transport.
- Temporary carrier destruction now requires the exact analyzed invocation,
  compiler-owned declaration, nonnullable fresh factory result, construction
  ownership and complete destructor proof. The shared cleanup analyzer recognizes
  only the fixed trace RELEASE operation; allocating or unknown cleanup still
  fails. Generated cleanup walks only the current invocation's new-carrier chain,
  after outward throwable restoration or native snapshot translation, and releases
  both native descriptor/trace storage and its JNI global reference. Extraction
  failure conservatively leaves the remaining chain live.
- Native tests now use real generated proxies bound before mandatory source
  analysis, with the existing proved root constructor entry. No foreign body is
  injected into test IR. A separate temporary root passes the new proof; a
  retaining root deliberately preserves process lifetime. One hundred repeated
  discard/propagation/two-failure/nested cycles restore native live allocations
  and global-reference counts exactly. Native replacement reclaims its carrier
  while preserving the ordinary replacement exception's process lifetime.
  A temporary invocation rethrowing an older retained carrier leaves it alive.
- macOS ARM64 O0/O3 pass with `-Xcheck:jni`, including native allocation exhaustion
  after the single fixture proxy allocation (limit 1) and injected JNI ref failure.
  Evidence: `workspace/java-bridge/evidence/p5/carriers/run-9275035310677381337/`.
  O0 SHA-256 `5977e70884a9df6c3a92d91a6f04f0e3ee5e3277a9ac19acc4b74db5963623f6`;
  O3 `43730a30808e558487581f29d6ac8f2a5d3f116b993c0dadda480c1167ecebe0`.
  Inspected optimized carrier destructor calls trace release, then descriptor
  destruction deallocates storage. The fixture proxy remains process-live;
  this test does not claim retained-listener removal/destruction coverage.
- All-unfreed-mode carrier proof tests pass, including new cause and secondary
  failure retention controls, stale-root refusal and unchanged source-free
  rejection. Existing complete destruction, listener source/class/archive safety
  and generated non-reclamation controls pass. Logs in `workspace/java-bridge/`:
  `p5-carrier-cleanup-proofs.log`, `p5-native-carrier-cleanup.log`,
  `p5-carrier-cleanup-controls.log`. Corrected the fixture source filename to
  match its public Listener type; no production proof was relaxed.

## Next step

Validate the carrier runtime on Linux, then implement retained listener lifecycle
before producer admission. Unknown carrier retention remains process-live under
D227. Guard components still need actual
receiver/dependent-owner placement and native refusal tests in that admission.
Source/class/archive
callback parity, native runtime containment, listener lifetime and performance
qualification remain pending.
