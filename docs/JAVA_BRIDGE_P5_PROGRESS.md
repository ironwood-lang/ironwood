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

## Next step

Build hidden proxy synthesis before final source escape/ownership analysis,
then invocation-context lowering and synchronous primitive listener transport.
Keep the producer and LLVM rejection until that complete path is safe.
Source/class/archive
callback parity, native runtime containment, listener lifetime and performance
qualification remain pending.
