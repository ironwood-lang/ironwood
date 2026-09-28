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

## Next step

Run the private transport checkpoint on Estonia using existing tools and fresh
RAM-backed container scratch. Then implement native foreign-failure carriers,
retained listener lifecycle and active-use guards before producer admission.
Source/class/archive
callback parity, native runtime containment, listener lifetime and performance
qualification remain pending.
