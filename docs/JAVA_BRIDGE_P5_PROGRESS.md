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

Exception-graph checkpoint review: a native wrapper's cause or secondary failure
can be a retained callback carrier. Extend only the callback-enabled snapshot
factory/transport to carry existing Java throwables as graph leaves. Keep their
identity, Java cause/suppression and stack trace untouched; native snapshot nodes
still use existing bounded traversal and construction. Require matching carrier
entries before enabling the extended factory ABI. Callback-free factory/transport
keeps its current ABI. Test cause/secondary identity and existing native graph
controls; no carrier cleanup permission follows from this translation.

- `ff98eedf`: committed proof-bound temporary cleanup and real proxy transport.
  Estonia physical Linux x86-64 passes the carrier runtime O0/O3, all-mode carrier
  proofs and complete destruction controls at this revision. Pinned image
  `sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7`,
  Java 21.0.12.1+1 / LLVM 23.1.0, isolated CPUs 1-4/9-12, network disabled.
  Payload SHA-256 `ba6d7a10008fa9bc32411a1b2f9dec8f29126b90ef5c987ee9f6235041b01f6d`.
  O0 image `1834bc41519766cb95939fcf96b78a01683eea1f30ff8ed2867d92654dbf7e50`;
  O3 `dbc03357a8fc95c1e06eefba0640b2a7430fbe83fb84320c5565acba4c5ebda9`.
  Remote evidence: `~/temp/java-bridge/p5-ff98eedf/`; local copy:
  `workspace/java-bridge/p5-estonia-ff98eedf/`. Every collected evidence file
  verifies against the remote SHA-256 manifest. Large duplicate pinned support
  source archives stay in the existing support directory and are identified in
  the complete evidence manifest. No installs or pre-existing deletions.
- Callback-enabled native exception graphs now include existing Java throwable
  nodes for native wrapper causes and secondary failures. Java identity, cause,
  suppression and trace survive unchanged. Factory and native transport enable
  the extra graph argument only with matching carrier entries; callback-free
  transport keeps its ABI. No native getters run without protected entries.
- Initial secondary-case test correctly exposed an unclassified context edge.
  Audited `ironwood_exception_add_secondary`: native metadata association with no
  user dispatch. Classified only its callback reachability; allocation/retention
  effects remain conservative and carrier cleanup still rejects secondary chains.
  macOS ARM64 carrier O0/O3 tests pass along with carrier lifetime and callback
  reachability controls. Existing built-in factory and custom native graph tests
  pass. Logs: `p5-callback-exception-graphs.log`, `p5-callback-graphs-retry.log`.
  Evidence: `workspace/java-bridge/evidence/p5/carriers/run-13198360491727219061/`.
  Native additions to a carrier's own cause/secondary metadata still require a
  separate transport/admission decision before public producer enablement; the
  graph test currently covers original Java throwables nested in native snapshots.

Proxy receiver pre-change review: source summaries currently publish every
foreign-body receiver. Prove only the exact typed proxy shape: load a primitive
handle from the receiver, call Java with that handle and the explicit parameters,
then return its primitive/void result. No native receiver reference crosses that
call. Keep reference-parameter retention, allocation, throwing, reentry and
reclamation effects unknown. Any additional operation, receiver argument,
reference result, control flow or unmatched body must retain the conservative
summary. Pair exact-body acceptance with each of those negatives and existing
source/class/archive proxy, foreign effect and pool helper safety controls.

- `fb9405a2`: committed callback identity within native cause/secondary snapshots.
  Its native carrier suite passes O0/O3 on local Linux ARM64 virtualization and
  physical Estonia x86-64, pinned Java 21 / LLVM 23, including `-Xcheck:jni` and
  both allocation-failure children. Payload SHA-256
  `12e1290d5ff16457107d657ee15f71414df1d2f1b1ca56cf01e8d83a9100cbad`.
  Evidence: `workspace/java-bridge/p5-linux-fb9405a2/{arm64,x86_64}/`;
  remote copy `~/temp/java-bridge/p5-fb9405a2/`.
  ARM64 image `sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`;
  native O0 `11a73ec8cef9714f802e00c13ba43ffde94fdf666f9826a49e11df28973f08e3`,
  O3 `691dc02b2e880b43af7603044d657208d5903ef927111a7cedbc7636153e8df8`.
  x86-64 uses the earlier pinned image; native O0
  `09360708ba684b10cd84434a87f43d63e96fba3fb42a9a24808a0aebbb9a4567`,
  O3 `70142d0b2bc8db130a8974742a6c6f48bff7688a4fb28736c46ce09a8108b4a2`.
  Every collected evidence file was checked against its remote/container manifest.
  These are focused runtime checks, not the full P5 platform/JDK matrix.
- Added exact typed-body receiver confinement. Source escape and symbolic-return
  summaries share that proof, rather than trusting a signature or source stub.
  The hidden receiver never crosses the Java call in the accepted shape; explicit
  reference arguments remain retaining and all foreign allocation, throwing,
  reentry and reclamation effects remain unknown. Extra stores/calls/control
  flow, a passed receiver, different handle receiver or reference return refuse
  confinement. No runtime code or callback-free path was changed.
- Pinned Java 21 tests pass: typed foreign effects (with paired confinement
  negatives), generated listener source/class/archive safety (all unfreed modes),
  all three pool helper controls and the native carrier O0/O3 suite. Logs:
  `workspace/java-bridge/p5-proxy-confinement.log` and
  `workspace/java-bridge/p5-proxy-confinement-final.log`. This corrects private
  receiver publication facts; it does not grant arbitrary callback borrowing or
  admit public callbacks. License audit and diff checks pass.

## Checkpoint at 639c9375

Implement retained listener lifecycle and complete carrier enrichment handling
before producer admission. Unknown carrier retention remains process-live under
D227. Guard components still need actual
receiver/dependent-owner placement and native refusal tests in that admission.
Source/class/archive
callback parity, native runtime containment, listener lifetime and performance
qualification remain pending.

Retained listener transport pre-change review: bind constructor and descriptor
cleanup to the exact generated proxy source and final P0 root proofs. Add explicit
ownership of a JNI global reference, shared by retained adapter slots and suspended
invocations. Prepare replacement before mutation; release only after the last slot
and active invocation have ended. No JNIEnv is stored in retained state, and no
reference counting occurs per callback within an invocation. This component does
not prove arbitrary native slot publication or enable producer admission. Test
actual proxy-handle dispatch, remove/replace during a suspended invocation, nested
calls, shared holders, same-listener replacement, exceptions and preparation
failure. Retain existing carrier, proxy source/class/archive and destruction
controls; inspect optimized callback code. Native slot attribution and full public
facade guard placement remain separate required proofs.

- Added final-artifact-bound proxy construction and destruction using the existing
  P0/P3 proofs. Exact source inventory and typed proxy bodies are revalidated;
  changed input inventories refuse ownership operations. Source/class/archive
  reconstruction produces the same ownership entries in all unfreed modes.
- Added private listener ownership transport: explicit JNI global references,
  shared slot/invocation ownership, constant-time final unlink/destruction,
  preparation rollback and identity conversion at registration only. An active
  listener whose final slot was removed remains available for identity reuse.
  No JNIEnv is retained and no lookup or ownership update occurs per callback.
- Native tests at O0/O3 pass on macOS ARM64 with `-Xcheck:jni`, including shared
  slots, warmed allocation-free invocation, removal/replacement during callbacks,
  nested calls, throw-after-removal, identity reuse while suspended, JNI reference
  failure with/without a pending exception, host-record allocation failure and
  native proxy allocation failure. Failed replacement preserves the prior slot;
  repeated completion restores native allocation, host-record and JNI counts.
  Tests retain the existing source-confined listener input proof; private adapter
  slots are not evidence of arbitrary native-field attribution.
- Evidence: `workspace/java-bridge/evidence/p5/carriers/run-7811678110787071455/`;
  logs `p5-listener-ownership-controls.log` and `p5-listener-identity.log`. Proxy
  reconstruction and existing nonthrowing destruction controls pass. Optimized
  callback inspection shows JNI dispatch/pending check with outlined carrier
  work, no ownership counters or registry lookup. License/diff checks pass.

Native listener slot pre-change review: reuse P0 retention attribution and protected
final-slot snapshots for callback-free registration/removal methods. Limit this
component to exact known holder fields and null/known listener input origins;
reject transfers of loaded listeners, static/array publication, unknown calls,
other reference stores and callback-bearing mutation. Keep holder ownership,
active invocation protection and admission separate. Test success/throw-after-
store slot snapshots in native execution, helper-equivalent attribution and nearby
unsafe shapes. No shared effect analysis exemptions are needed for these entries.

- `f014e2d6`: retained ownership transport passes O0/O3 on local Linux ARM64 and
  physical Estonia x86-64, Java 21/LLVM 23, all four isolated child modes under
  `-Xcheck:jni`. Payload SHA-256
  `817bbddb4598ae072440dab56ebe4fd78e35ad4bfd2b655b56c77fd5c01d2733`.
  Evidence and per-file verified manifests:
  `workspace/java-bridge/p5-linux-f014e2d6/{arm64,x86_64}/`; remote evidence
  `~/temp/java-bridge/p5-f014e2d6/`. Same pinned images as `fb9405a2` above.
  ARM64 O0 `ee0472c7c026c95731c75ab297e7b34c336bea98030d7d022e2e66f4e15fb7ff`,
  O3 `2c8569360512eed033552001b9c98841b09915eece689066c31afcac43525320`.
  x86-64 O0 `45b9473be1e680429b839c89f662ed0bdf24ae5d684bc83159434a31150e57d8`,
  O3 `a7ddaf90a58c55daa78f240fa4e43df34b145c05d2be20ccdf3c5f0ad2da414e`.
  Estonia has about 200 MB free, so validation uses existing images and tmpfs;
  transferred payloads/evidence and containers are preserved. No installations.
- Added listener-field write entries using P0 retention attribution and existing
  protected final-slot lowering. Exact final holders and known listener inputs or
  null are accepted; callback mutation, hidden/static/array publication, erased
  field stores, child holders and loaded-slot transfers remain rejected. This
  proves writes/snapshots, not whole-surface holder lifetime or free permission.
- Source/class/archive parity and all-unfreed-mode paired tests pass. Native
  O0/O3 tests confirm final snapshots after store-then-throw, no-op store and clear;
  the retained native field still invokes the Java listener and both holder and
  proxy storage are released after clear. Initial compile rejected use of a
  package-private analysis helper; corrected the consumer to use existing exact
  final-class API facts instead of changing shared analysis access or proofs.
  Logs: `p5-listener-slots-retry.log`, `p5-listener-native-slots.log`.
  Evidence: `workspace/java-bridge/evidence/p5/carriers/run-11150105876358111081/`.

Next lifecycle requirement: an active holder may read a newly installed listener
after Java reentry. Protecting only the entry's original listener is insufficient.
Defer all removed slot tokens until every suspended invocation of that holder has
ended, with mutation-time prepared retirement storage and no per-callback work.
Test native old/new listener aliases spanning multiple replacements and removals.
Public admission, carrier-primary enrichment, complete owner guards, string
reentry, listener example and performance/platform qualification remain pending.

- `c4081758`: committed native listener-field write/snapshot proof and paired tests.
- Implemented per-holder deferred slot cleanup. Each prepared slot already owns
  its retirement link. Replacements during an active invocation retire the old
  slot without allocation or Java calls; the last invocation exit releases the
  retired chain. This covers listeners installed after the outer call started,
  without adding per-callback reference counting or lookup. Preparation failure
  preserves the current field and token. Counter headroom checks refuse overflow
  before changing state.
- Native Holder.fire now keeps one listener alias, calls Java to replace it,
  loads the replacement, calls Java to clear it, then calls both old aliases.
  The O0/O3 test passes under `-Xcheck:jni`, including repeated cleanup, nested
  holder invocations, exceptional exits, all four preparation failure points,
  unchanged counts after failure and complete native/JNI/host-slot reclamation.
  Evidence: `workspace/java-bridge/evidence/p5/carriers/run-12762165049828606186/`;
  logs `p5-listener-retirement.log`, `p5-listener-retirement-controls.log`.
  This remains a private invocation/field protocol test. Whole public API
  closure admission and generated facade guard placement are still pending.

- `750bc197`: committed holder-wide retirement and native alias/reentry tests.
- Connected the actual generated RootState/guard/refusal templates to the private
  native Holder fixture. Active callback free now verifies the exact artifact
  refusal class and unchanged native destruction count. Nested native calls stay
  usable; successful close updates shared state; repeated close is a no-op and
  dead-holder invocation refuses before its native-entry counter advances. A real
  producer IllegalStateException is verified not to be a bridge refusal.
- Native O0/O3 and existing allocation-free guard component checks pass. Evidence:
  `workspace/java-bridge/evidence/p5/carriers/run-15717510448830011294/` and
  `workspace/java-bridge/evidence/p5/active-use/run-976924001697301359/`;
  log `p5-listener-native-guards.log`. This is component composition, not public
  producer admission or a claim that all dependent-owner placements are proved.

- `08281b06`: committed native guard/destruction-counter evidence. Full private
  carrier/listener/field/retirement/guard fixture passes O0/O3 on Linux ARM64 and
  Estonia x86-64, four child modes, pinned Java 21 and LLVM 23, `-Xcheck:jni`.
  Payload SHA-256 `088d7ff80697a54961c0149f1032067deffbae7e96898b0767a2baf85b29890c`.
  Evidence: `workspace/java-bridge/p5-linux-08281b06/{arm64,x86_64}/` and
  `~/temp/java-bridge/p5-08281b06/`; every collected file verifies against its
  manifest. ARM64 O0 `7e06cee5c3989286c30cc2be041e5c94914c52956945e3e4b043a1ac6614f683`,
  O3 `77e95dc60f501acdf06ad6be91147c01e6a80a8506b6ddac16e3c5974399e65a`.
  x86-64 O0 `3525dcee60ec5fd6b29820d329fd5c209b8c0f6d55353a5f31746e93a15d39f8`,
  O3 `1c3b69f5c000a446a31265ce84daf8027cd60a933dd3c3c50a922c92d9e8b369`.
- Added generated JNI callback bodies bound to exact typed proxies, with cached
  method IDs, exact descriptors, explicit invocation JNIEnv and mandatory pending
  exception capture. The native fixture now runs these generated bodies instead
  of handwritten JNI dispatch. Long/void currently matches the admitted private
  foreign ABI; narrower primitives and references still reject this component.
  Typed foreign effects and public producer rejection remain unchanged.
- Mac O0/O3 carrier/lifecycle/guard tests pass with generated bodies; proxy
  source/class/archive controls and unsupported-ABI rejection pass in every
  unfreed mode. Log `p5-generated-callbacks.log`; evidence
  `workspace/java-bridge/evidence/p5/carriers/run-14830068004286853686/`.

Next independent validation: D206 outer copied Strings must survive allocating,
reentrant and throwing callbacks, with separately owned nested buffers and exact
partial-failure cleanup. Production admission still requires complete invocation
and owner proofs, carrier-primary native enrichment, additional callback value
ABIs, the example, benchmarks and the full P5 qualification matrix.

D206 pre-change review: reuse protected root-entry String-copy lowering only after
matching original input-borrow proofs and exact callback-context transformation.
Limit references to copied Strings and nonpublishing listener inputs, with a
static primitive/void result entry. This does not admit a public callback surface.
Extract the existing value adapter's noncritical JNI acquisition/release snippets
without changing its output, then reuse them in the native callback fixture.
Pair outer/nested UTF-16 content preservation with allocation, callback throw,
second-buffer preparation failure and native second-copy allocation failure.
Verify no native execution after JNI preparation failure and zero residual JNI
buffers/native copies on all exits; preserve existing scalar String controls.

The first D206 run correctly stopped on an unclassified String.charAt intrinsic.
Audited its LLVM lowering: direct UTF-16 pointer/load, no user dispatch. Classify
only callback reachability for that exact operation; do not change retention,
allocation, exception or reclamation facts. Rerun callback reachability controls
with the native test after adding this audited classification.

- `fa6aa73f`: committed generated long/void JNI callback bodies.
- Added proof-bound copied String entry composition with exact context
  transformation validation. Copied inputs must be neither retained nor
  invalidated; escaped Strings and mismatched contexts reject in all unfreed
  modes and after source/class/archive reconstruction. Uses existing protected
  root-entry copy and acquisition-prefix cleanup, with no new borrowing exemption.
- Extracted the existing scalar value adapter's D206 noncritical JNI snippets
  without changing emitted code. Native callback tests reuse them for two outer
  and two nested String inputs while Java allocates and reenters. UTF-16 hashes
  remain unchanged for null/empty, NUL and unpaired surrogate cases. Callback
  throws, first/second JNI acquisition failures and native second-copy OOM all
  restore buffer/native-copy counts; JNI preparation failure skips native entry.
- Mac O0/O3 carrier/lifecycle/String tests pass in five child modes under
  `-Xcheck:jni`. Existing callback reachability, repeated native String OOM,
  generated value adapters, copied String proof/artifact parity and value-surface
  controls pass. Logs: `p5-callback-strings-retry.log`,
  `p5-callback-string-proofs.log`, `p5-string-adapter-control.log`.
  Evidence: `workspace/java-bridge/evidence/p5/carriers/run-13151588596172180576/`.
  License and diff checks pass. No public callback admission has been enabled.

- `f51d03db`: committed D206 copied-input proof/transport and regressions. The
  complete private fixture at this revision passes O0/O3 on Linux ARM64 and
  physical Estonia x86-64, including all five child modes. Same pinned Java 21
  and LLVM 23 images; no critical-region JNI warnings. Payload SHA-256
  `acc66c4443e89533cfcbfcd36b360443e6c3a46c54e0094d17855a830e5344d8`.
  Evidence: `workspace/java-bridge/p5-linux-f51d03db/{arm64,x86_64}/` and
  `~/temp/java-bridge/p5-f51d03db/`; collected files verify against their manifests.
  ARM64 O0 `b825d67350843bfcbea5fb292e8aac78b69178489376522db7159b7ca177238f`,
  O3 `6df7ba54dfc9f5061c270f1068be237f898d8012caa32aaa1445525dd1b2ac45`.
  x86-64 O0 `5cb3617f0f19dd38f3e4d64df5f753c92bd18fdda3eb915a2fc7b0930ed324d6`,
  O3 `fa9f42606e58507b7b46801c0a0032fab7f239be651d0cf865deaa3692b8c566`.
  Rebuilt the pinned compiler JAR after final source changes and checked its
  compiler entries against the validated payload's input hashes. Build log:
  `workspace/java-bridge/p5-final-build.log`.

## Next step

Complete stateful invocation admission using these components. The public
producer now accepts the proved synchronous primitive/borrowed-listener subset.
Prove the complete exposed native closure, dependent-owner guard placement and
listener-field lifecycle before enabling retained/stateful signatures; preserve the initial rejection of callbacks combined with
independent-root retention mutations. Never reinterpret foreign effects as pure
or non-retaining to make ordinary root analysis pass.

Carrier-primary native cause/secondary enrichment is implemented under D228,
with original Java identity preserved for unchanged carriers and wrappers for
native additions. The bounded synchronous primitive invocation proof is available;
broader holder/owner admission and reference callback transport remain pending.
The generated callback ABI accepts primitive arguments and primitive/void results
after typed normalization; reference callbacks still need separate value/ownership
proofs. Callback-bearing implicit initialization/cleanup
also remains rejected. Keep D227 retained carrier ownership unchanged.

Then finish the dedicated listener example, equivalent Linux native/native,
Java/Java and native/Java measurements, nested-stack qualification and P5's
Java 21-23 platform matrix. Current cross-platform evidence is the private Java 21
component fixture. It does not complete P5 or its performance qualification.

Primitive callback ABI pre-change review: normalize boolean and small integral
values to I64 in typed IR; preserve float/double as F32/F64. JNI uses exact method
descriptors and typed jvalue arrays, avoiding C variadic promotion ambiguity.
Reuse canonical typed proxy bodies for receiver-confinement matching, including
boolean branches; explicit reference arguments remain conservatively retaining.
No reference transport or public admission is added. Verify primitive extrema,
unsigned char, signed zero, infinities, NaNs, boolean combinations, void and mixed
signatures at O0/O3 with -Xcheck:jni. Pair canonical-body acceptance with malformed
body rejection and source/class/archive parity in every unfreed mode. Inspect
optimized native code for eliminated primitive normalization branches. Existing
carrier/String/lifecycle and foreign-effects controls remain required.

- Implemented canonical typed primitive callback normalization and generated
  exact-descriptor JNI `MethodA` bodies. Narrow integral and boolean values cross
  the private C ABI as I64, float/double as F32/F64. Receiver confinement matches
  every canonical instruction and edge; hidden publication in either boolean arm
  rejects. Explicit reference arguments retain unknown effects and JNI rejection.
- Mac Java 21/LLVM 23 O0/O3 primitive tests pass for extrema, unsigned chars,
  dynamic boolean combinations, signed zero, infinities, NaNs, mixed/empty
  argument lists, void, nested invocation and original exception identities from
  every callback. Live native allocation counts return to baseline. Optimized
  boolean normalization folds into bit extraction; no normalization branches.
  Evidence: `workspace/java-bridge/evidence/p5/primitives/run-6404525868145730306/`.
- Source/class/archive reconstruction in all unfreed modes, foreign effects,
  existing native carrier/lifecycle/String tests, private long transport and the
  three pool release-helper regressions pass. License and diff checks pass.
  Logs: `p5-primitives-final.log`, `p5-primitives-dynamic-retry.log`,
  `p5-primitives-native.log` (existing two controls pass; its original primitive
  fixture failure was fixed), `p5-primitives-pool-controls.log`. Initial fixture
  failures came from selecting a constructor rollback as an invocation root,
  a mismatched public source filename, and unsupported multiple-local syntax;
  corrected test inputs without changing safety requirements.
- Linux primitive ABI validation and comparative JNI-body timing are next.
  Public producer integration and carrier-primary enrichment remain pending.

Synchronous primitive invocation pre-change review: prove a deliberately bounded
closure of static primitive-result entries with borrowed listener inputs. Traverse
all direct/dispatch/initialization edges, including protected calls; accept only
canonical primitive foreign proxy bodies. Exclude native heap/static state,
allocation, reclamation, exception graph mutation and unknown operations. This
establishes that Java reentry cannot invalidate native-owned state used by this
closure; it does not mark Java effects non-retaining or confer general ownership
permission. Reuse P0/P3 proxy construction/destruction, callback context lowering
and protected entries. Pair scalar loops and helper/dispatch/catch equivalence
with retained inputs, reference results, static/field access, allocation/free,
secondary failures and unbound foreign bodies. Revalidate source/class/archive
facts and run the proof-generated loop through the existing primitive fixture.
Retained listeners and stateful holders still require their separate owner guards
and full lifecycle admission. Public producer wiring remains a later gate.

Scope refinement from the first proof run: ordinary interface null checks include
typed native throwable allocation and constructors even for a scalar loop. Permit
native built-in throwable allocation, receiver-confined constructor field writes
with matching P0 constructor facts, and the audited native trace capture operation.
These internal exceptions follow existing process lifetime and never become native
facade roots. Keep arbitrary allocations, other field access, static state, free,
cause mutation and secondary attachment rejected. Traverse all constructor and
trace helper targets; unknown dispatch is still a refusal. Add null-listener
failure coverage, alongside the existing native allocation-failure controls.

- `0d9c1cbf` passes the primitive and full carrier/lifecycle/String fixtures on
  Linux ARM64 and Estonia x86-64 at O0/O3. Payload SHA-256
  `d5babb4156f8ec297044578c219dbdaccad086c608e34f9a386097f3b7cfd2df`.
  All archived evidence members verify against their manifest; files are under
  `workspace/java-bridge/p5-linux-0d9c1cbf/{arm64,x86_64}/`, with the remote
  originals preserved at `~/temp/java-bridge/p5-0d9c1cbf/`.
  Primitive ARM64 O3 `801a2fbf0af54cb597f29121036794f6fd18806578ddc07bbe2cf0533cf53b59`;
  primitive x86-64 O3 `b8f1a3e88f7b5ef84c98aacf659d586b5fd239fddb6b68c60f69284adec8fc91`.
  The one-long callback diagnostic measured about 45.7 ns on Linux ARM64 and
  92.0 ns on Estonia CPU 1. These are primitive callback-loop measurements, not
  application throughput or P5 performance acceptance.
- Implemented program/root-bound synchronous native-state proof and protected
  entries, reusing proved proxy ownership and exact context specialization.
  Complete direct, virtual/interface and initializer closures are inspected.
  Source/class/archive positives and paired refusals pass in every unfreed mode,
  including unfavorable native dispatch alternatives and static initialization.
  The primitive native fixture now executes these proof-generated entries.
- Mac O0/O3 round trips, nested calls, original throwable identities, allocation
  cleanup and null-listener native failure containment pass. A native null fault
  remains process-live as required; temporary proxies/carriers return to baseline.
  Logs: `p5-synchronous-dispatch.log`, `p5-synchronous-final.log` (native fixture
  passed; its added dispatch test's missing @Override was corrected), and
  `p5-synchronous-native-null.log`. License and diff checks pass. Added an
  equivalent handwritten JNI callback loop for the next Linux comparison.
- The maintainer approved wrapping callback exceptions modified by native code,
  preserving the original Java throwable unchanged. Unchanged exceptions must
  still retain their original identity. Implement this before public producer
  admission; D227 lifetime rules remain in force.

D228 pre-change review: distinguish unchanged from enriched carriers using
protected native cause/secondary getters. Preserve unchanged identity; translate
modified carriers through the existing bounded graph assembler, with the original
as wrapper cause and native additions on the wrapper only. Never mutate original
Java cause, suppression or trace. Pair primary and embedded enriched carriers,
cause-only and finally-secondary paths, repeated retained rethrows, disabled Java
suppression, graph cycles/limits and snapshot failure containment. Reuse existing
protected getter/graph machinery; keep D227 carrier cleanup proofs unchanged.
Verify callback-free graph behavior remains unchanged and run existing graph and
carrier lifetime controls as well as the native fixture.

- Implemented D228 protected unchanged/cause/secondary accessors and bounded graph
  translation. Unchanged carriers take the identity-preserving path; enriched
  primary or embedded carriers wrap the untouched Java original. Native causes
  and secondary failures remain distinguishable. Repeated retained rethrows do
  not duplicate or mutate the Java original's suppression, cause or trace.
- Mac O0/O3 native tests pass for native finally failures, cause additions,
  combined additions, embedded modified carriers, cycles, copy limits, disabled
  Java suppression and deterministic Java graph-allocation failure in a child.
  The full fixture runs six child modes with `-Xcheck:jni`; unchanged carrier,
  lifecycle, String and allocation-failure controls still pass. Evidence:
  `workspace/java-bridge/evidence/p5/carriers/run-11322582982536984628/`.
- Primitive/proof-generated entries, mandatory carrier lifetime refusals,
  callback-free generated exception factories and custom native graph controls
  also pass. Log `p5-enriched-controls.log`; license/diff checks pass. Rebuilt the
  pinned compiler JAR in `p5-enriched-build.log`. Cross-platform verification of
  this combined revision and the handwritten JNI timing comparison are next.

- `50e8ec9d`: all three focused tests pass on Linux ARM64 and Estonia x86-64:
  six carrier child modes and primitive/proof entries at O0/O3, plus all-mode
  source/class/archive synchronous admission controls. Payload SHA-256
  `2dcad6a89a03b6b8e3fdd06ce091c52251d76c2e822d0f59caf3a6d011d868f2`.
  Evidence and checked manifests: `workspace/java-bridge/p5-linux-50e8ec9d/`;
  remote originals: `~/temp/java-bridge/p5-50e8ec9d/`. Every archived file verified.
  ARM64 carrier O3 `f6069c1fe5d6fdf036b6a8faedd92b2c5b488a5e92c8929d78d326321d201ac1`;
  x86 carrier O3 `77d93f0433dc8d380fc70a0717132ecbb739b8ccef5c8c2d614f9b711c2adc29`.
  ARM64 primitive O3 `1c4ed6fec304a60f3b420b952fa961594f19a24e5540437b6949cb871c22f369`;
  x86 primitive O3 `8ea6f6d8ec968a2b957cbc3177e636f3ba60d1a268187d548be463af742d7353`.
- Seven interleaved one-million-call samples after five warmups, same checksum:
  generated/handwritten JNI medians 92.105/91.672 ns per callback on Estonia CPU1,
  45.601/51.478 ns on Linux ARM64. This measures native-to-Java long callbacks,
  not Java-to-native crossings or application acceptance. O3 disassembly shows
  cached method dispatch plus mandatory exception check, no per-callback method
  lookup, allocation or TLS. Proxy allocation is once per outer invocation.
- Maintainer explicitly authorized proceeding through all remaining P5 checkpoints
  without routine confirmation. No current blocker. Retained/stateful admission,
  full producer integration, dedicated example and final platform/stack matrix
  remain incomplete.

Producer integration pre-change review: compose the existing synchronous native
state proof, exact proxy ownership, explicit invocation context and carrier
lifetime proof into a bound admission. Generate ordinary Java listener interfaces
from selected API facts, include them in generation identity and bootstrap class
validation, and cache method IDs only after validated loader binding. Preserve
callback-free producer behavior and reject stateful/retained/reference transport
until separately proved. Reuse protected entries and bounded exception snapshots;
all adapter exits must destroy temporary proxies and release proved carriers only
after outward translation. Pair complete static primitive/listener source and
artifact builds with unsafe state/retention, interface/default/generic/reference
signatures, metadata mismatch, nested/throwing/null callbacks and allocation failure.
Run focused producer, identity/packaging, JNI and negative admission checks.


- Added bound synchronous producer admission, ordinary Java listener interfaces,
  paired callback generation identity and validated load-time JNI method caching.
  All native adapter exits reclaim proved temporary proxies, including failure
  after earlier listener preparation. Proved carriers are released only after
  outward translation. Callback-free generation retains its previous path.
- New focused producer test covers O0/O3 source/class/archive builds with original
  source files removed, two listener types, class/module consumers, nested calls,
  native catch, Java exception identity, null/arithmetic failures, void/double
  values and native allocation limits 0/1 in child processes with `-Xcheck:jni`.
  Retention/static-state/reference-result refusals run in every unfreed mode;
  unsupported reference/default/static/generic/inherited listener shapes reject
  without replacing the existing jar. Packaged classes, source, Javadoc and every
  payload hash are checked. Initial failure exposed missing nominal listener
  spelling in Java generation; fixed using the selected semantic declaration.
- Mac Java21/LLVM23 new producer test and four controls pass: existing producer,
  generation identity, native bootstrap and host assembly. Evidence:
  `workspace/java-bridge/evidence/p5/producer/run-11619755797938096035/` and
  `workspace/java-bridge/p5-producer-controls.log`. License and diff checks pass.
  Linux verification of the packaged callback path is next; no P5 completion claim.

Copied-input producer pre-change review: extend the same bounded invocation proof
only for borrowed copied String inputs, immutable String reads and the existing
proved D206 input-copy/cleanup lowering. Do not admit passing native String
references to Java callbacks or retaining the copies. Both outer and nested JNI
buffers must use noncritical acquisition and reverse-prefix release, including
partial preparation, native failure and callback throw. Pair repeated reads after
allocating/reentrant callbacks with retained String rejection in all unfreed modes.
Reuse existing String callback and primitive producer controls; inspect generated
adapters for critical access and run child allocation failures at copy boundaries.
- `8f7f45d3` packaged primitive listeners also pass on Linux ARM64 and Estonia
  x86-64, including source/class/archive identity, both consumer launch forms and
  allocation/refusal controls. Compiler payload SHA-256
  `399ce8fd8e79f062179e0c8facb0d56865b41dac5bb8a646f0aad10aa4f71b7f`;
  supplemental distribution input SHA-256
  `d4e36f90c6ee27f8fb9b0e1afb056dd61c6547b6234dd1c71ab1f20c153cb76c`.
  Initial Linux packaging failed because the validation bundle omitted required
  license files; added the exact repository inputs, without changing production
  code. All 97 archived evidence files per platform verify against their manifests.
  Evidence: `workspace/java-bridge/p5-linux-8f7f45d3/{arm64-packaged,x86_64-packaged}/`.
  O3 archive-input jar hashes: ARM64
  `157b1ea8f624348b26de7af88029f470f3584801ab07e2a49d0382cb1e19c972`;
  x86-64 `97f3cd51fa227e51d390042a5c52744857d2e2cd10f73c8274226c628d0d3e3e`.
  Large Linux jars were identified by hash but omitted from the compact evidence
  archive; subsequent tests separately preserve the exact native image and paired
  manifest, without duplicating the mounted corresponding runtime sources.
- Integrated D206 copied String input admission with immutable length/character
  reads and existing typed cleanup. Mac O0/O3 production consumers pass nested
  allocating and throwing callbacks, null/empty/NUL/unpaired-surrogate values,
  void/double results, and native allocation limits 0/1/2 before callback execution.
  Retained String inputs remain rejected in all unfreed modes. Source/class/archive
  proof reconstruction passes for primitive and copied String inputs in all modes.
  Existing complete carrier/lifecycle/String fixture passes. Logs:
  `p5-copied-producer.log`, `p5-copied-controls.log`; producer evidence
  `workspace/java-bridge/evidence/p5/producer/run-13274670539715165237/`.
  Paired native images/manifests now accompany direct evidence; O3 image inspected.
  License and diff checks pass. Linux copied-input checks remain pending.

Nested-stack pre-change review: qualify the actual packaged synchronous callback
adapter with alternating Java/native recursion, live values across the callback,
and unchanged deepest-frame Java exception identity. Reuse D201's default-stack
reference depths 1/8/32/64 from Java depths 0/64 at O0/O3, followed by another call.
Use disposable child JVMs for all cases and separate 512k/1m limit diagnostics;
record JVM rejection, Java overflow and native crash distinctly. No signal recovery,
stack checks or required consumer flags. Retain image hashes, paired manifest,
commands, logs, JVM flags and disassembly. This does not qualify stateful callbacks
until those adapters also have their own complete proof and validation.

Stack scope refinement: the first Mac matrix passed all six bounded cells, but O3
inlined carrier cleanup's two temporary result frames into the normal adapter.
Its frame was 1232 bytes versus 704 at O0 (excluding called/JVM frames). Outline
only carrier-bearing cleanup behind the existing empty-chain check, and share the
outer result frame across sequential proxy preparation. Keep individual proxy
addresses for reverse cleanup; publish an address only after successful creation.
This reduces stack reservation without adding a normal-path call, changing proof
permissions or moving cleanup before translation. Recheck partial allocation,
throw/catch/retain, nested calls and O3 callback timing/disassembly, then rerun the
packaged stack matrix and characterize reduced-stack limits from depth 64 upward.

Listener alias audit: reproduced a public producer correctness bug in
`p5-listener-alias-reproduction.log`: passing one Java listener to two parameters
created distinct native proxies, so native reference equality returned false.
Canonicalize same-interface aliases during preparation with JNI identity checks,
and destroy each successful proxy once. No identity lookup belongs inside the
callback loop. Pair same/distinct/null aliases, throwing aliases and one-allocation
alias success. Reject cross-interface/erased reference equality and dynamic
listener type queries until multi-interface proxy identity is represented; neither
signature discovery nor an unchecked cast grants that capability. Ordinary
primitive operations, null tests and native exception handlers remain unchanged.
- Same-interface listener aliases now share a proxy prepared by JNI identity;
  reverse cleanup destroys each unique proxy once. Same/distinct/null/throwing
  aliases and one-allocation-budget success pass. Cross-interface/erased equality
  and dynamic interface queries reject in every unfreed mode through the native
  state proof. Log `p5-listener-alias-controls.log`; producer evidence
  `workspace/java-bridge/evidence/p5/producer/run-16793633157405077365/`.
- Outlined nonempty carrier cleanup and reused the sequential preparation frame.
  All native carrier modes, the primitive round-trip/timing fixture and the full
  producer tests pass (`p5-stack-footprint-controls.log`). The actual packaged
  Mac adapter frame falls from 1232 to 384 bytes at O3, and 704 to 416 at O0;
  called/JVM frames are additional. No empty-chain slow call is introduced.
- Added `check-stack.py --callbacks` with public producer fixtures and pinned
  three-JDK checks. All six Mac O0/O3 cells pass default-stack depths 1/8/32/64
  from Java depths 0/64, deepest Java identity and continued calls. Reduced-stack
  diagnostic children succeed through 128/256 for 512k/1m respectively, then
  report Java StackOverflowError at 256/512 on all three JDKs. No native crash was
  observed; these limits are not supported-depth promises. Evidence:
  `workspace/java-bridge/evidence/p5/stack-macos-compact-alias/`; O0 image
  `8a1398eeec724e1b0bca61a8b038e2765ab4148e3684e5cce251f6097badb7fa`, O3 image
  `c0fd881fe85aa2934305cbe695e9d91d0b3a329a922509ca620a6ddadcc3bbda`.
  Source/runtime/producer identities, commands, settings and disassembly are
  retained. License/diff checks pass. Linux qualification of this revision is next.

Owner invocation pre-change review: reuse the synchronous closure walker with a
separate, bound owner proof. Eligible roots must already have final P0 construction,
rollback and destruction proofs; their exact final classes contain only primitive
and listener fields. Constructors start listener fields empty. Invocation inputs
remain borrowed, results primitive/void, and all owner inputs become explicit
guard obligations. Permit primitive field mutation and listener reads, but reject
listener writes, independent-root graphs, static state, ordinary allocations/free
and unknown dispatch in callback-bearing calls. Slot mutation remains the separate
callback-free attribution protocol. No public admission follows from this component.
Pair instance/static/helper acceptance with retained-input, hidden-state,
listener-mutation, nonempty-construction and stale-proof refusals in all unfreed
modes and reconstructed source/class/archive inputs. Run existing synchronous,
listener-slot and foreign-effect controls. No shared source reclamation rule or
foreign borrowing exemption changes.

Linux synchronous checkpoint at `35ab7f41`: packaged source/class/archive,
copied Strings and listener aliases pass on both architectures. All six bounded
stack cells also pass on each. Limit diagnostics exposed validation scratch
exhaustion late in JDK23 O3: repeated child payload extraction filled the 1GiB
container tmpfs, producing `LinkageError: No space left on device`, not a stack
limit. Preserve those logs, scope each child to its own disposable scratch
directory, then rerun the matrix against the same archived compiler. The earlier
missing-JAR archive error was corrected using a separately hashed compiler
supplement; no production compiler fix was needed for either harness issue.

Owner proof checkpoint: `BridgeOwnedCallbackProof` binds exact final P0 storage
to the complete invocation closure and immutable receiver/argument guard indices.
Safe instance/static/helper forms pass in every unfreed mode and source, loose
class and archive reconstruction. Listener mutation, native owner graphs, static
state, owner publication, ordinary allocation/free, nonempty listener construction
and reference constructor inputs reject. Stale artifact/module/root bindings also
reject. Existing synchronous, listener-proxy/slot and foreign-effect controls pass
(`p5-owner-proof-controls.log`, four tests). The first new test run caught a missing
`@Override` in a negative fixture; fixed the fixture before testing its proof.
License and diff checks pass. Protected owner entries and host guard integration
are next; public stateful producer signatures remain rejected.

Linux matrix rerun with per-child scratch cleanup passes all six bounded cells on
each target. All reduced-stack failures are now observed Java StackOverflowError,
with no native crash: Linux ARM64 O0/O3 succeeds through 128/256 for 512k/1m,
then fails at 256/512. Estonia O0 matches that; O3 succeeds through 128/512 and
fails at 256/1024. These fixture observations do not establish general limits.
Evidence is `workspace/java-bridge/p5-linux-35ab7f41/`, with retained remote data
in `~/temp/java-bridge/p5-35ab7f41/`. Verified every archived member against its
full evidence manifest: 176 packaged-run files per architecture, 188 ARM64 and
197 x86-64 corrected stack files. Both corrected runs reproduce the prior native
image identities: ARM64 O0 `aff22e9b1ed885be6a93b735d6764bcab48cc50c4fffe480c176b8ce6c0e4c53`,
O3 `39eba56a0ee08dcb77c61192c1c32280c9f15281761fd8df6c2be2cbb0f58cd3`;
x86-64 O0 `a792d4e676d2eb9352c9bb6eb9b514de2a2cbb08bd5783286b0d4c295f3f2fed`,
O3 `a175643b2400deaabc6bb239f34de0ffdfda04cd1804ecd485ab61ed908b3f96`.
Payload SHA-256 `f3320f7f331184f6273632dd05362225be5b2e74d2152456836174497c901003`;
separate compiler and runner supplements each have verified member hashes.
No software was installed and no pre-existing remote files were deleted.

Owner entry checkpoint: added protected entries bound to both final storage and
exact callback context specialization. Guard generation requires precisely the
proved receiver/argument indices and stable evaluated root-state locals; missing
owners, changed contexts and reevaluated expressions reject. The native holder
fixture now consumes these entries and generated guards rather than a hand-selected
holder guard. Its complete O0/O3 lifecycle/carrier/allocation/String/enrichment
controls pass, including active-free refusal and reentrant slot retirement. The
owner source/class/archive test and allocation-free Java guard controls pass too
(`p5-owner-entry-controls.log`, three tests). Evidence: carrier
`workspace/java-bridge/evidence/p5/carriers/run-2221806404523836752/`, guards
`workspace/java-bridge/evidence/p5/active-use/run-8642052811359335787/`.
Public holder composition, copied owner-call inputs and reference callback values
remain unfinished. The existing private fixture is not a packaged holder API.

Holder composition review: callback-free scalar and listener-slot methods also
need the exact owner-storage proof. Pair P0's complete slot attribution with the
bounded native-state closure and non-reclamation of both owners and generated
proxies. Permit only exact owner fields, primitive results and primitive/owner/
listener inputs; reject hidden publication, implicit callbacks, native listener
construction and arbitrary heap operations. Reuse the closure walker without
changing unknown foreign effects. Constructor, callback and slot helper symbols
must remain disjoint in the combined module. Verify these component bindings and
safe/unsafe slot methods before connecting any public producer signature.

Native holder composition checkpoint: `BridgeOwnedListenerSlots` now binds
callback-free scalar/slot methods to exact final root storage, complete P0 write
attribution and owner/proxy non-reclamation. Hidden listener publication, callback
execution during a setter and dynamic interface queries reject. Scalar getters
have no slot payload. Owner storage helpers use a separate symbol namespace.
`BridgeOwnedCallbackAdmission` composes the selected constructor, slot and callback
roots with carrier cleanup and bounded exception projection, then emits a finished
native program. This is internal admission only; no public producer route is enabled.

The first integration run exposed a missing bounded-proof case for failed native
exception construction. A trace-only shortcut was insufficient because Throwable
owns message storage. The corrected case requires P0's exact unpublished unwind
match plus its complete nonthrowing, allocation-free rollback proof, including
owned-message cleanup. It grants no ordinary or caught-exception reclamation.
Public fixture files and loose-class inputs were also corrected to include both
separate public declarations. Preserved failure logs and the IR inspection in
`workspace/java-bridge/owner-debug/`.

Final focused controls pass (`p5-owner-composition-final.log`, three tests): full
native carrier/holder modes at O0/O3, prior synchronous acceptance/refusal, and
owner/native composition with source/class/archive parity in all unfreed modes.
The existing final-root/slot/getter/cleanup control also passed in
`p5-owner-composition-controls.log`; only the two failing tests and affected
synchronous proof were rerun after fixes. Carrier evidence:
`workspace/java-bridge/evidence/p5/carriers/run-11871365283071531441/`.
License and diff checks pass. Paired Java facade/native holder adapters are next.

Host composition review: retain generation/API/class-loader binding and the P3
authoritative root index. Extend only the proved holder route with listener-owner
storage allocated before native construction, fixed listener slots and callback
active-use state. Registration and mutation may prepare identity/global references;
callback dispatch may not perform identity searches. Reuse the existing weak facade
cache and native destruction protocol. Callback-free scalar calls must not acquire
active guards or slot reconciliation. Keep unsupported reference results and
cross-interface identity conversions rejected. Pair preparation failures with
unchanged native/host state, and nested replacement/free attempts with balanced
slot retirement and guards before enabling the public producer route.

Owner host storage checkpoint: generation manifests, weak caches and RootState
now bind the complete native owner admission. The authoritative P3 root record
holds fixed listener slots and callback activity, reserved before construction;
typed owner destruction also releases installed listeners. The native carrier
fixture consumes this real registration/destruction path. Record, index growth
and global-reference failures leave no native construction, host publication or
retained references. Nested replacement, active-free refusal and final installed
listener cleanup pass at O0/O3 (`p5-owner-host-controls.log`, evidence
`p5/carriers/run-1706956805512077054`). Source/class/archive owner proofs and the
existing root-state control pass (`p5-owner-host-regression.log`).

The root-index control contained a stale rejection from before P3 retention was
implemented. Recompiling the unchanged HEAD generator separately reproduced the
same failure (`workspace/java-bridge/p5-root-index-baseline/result.log`). Updated
that expectation to require outgoing dependency release, preserving stale-artifact
rejection and every native registration/failure/destruction case. Both that test
and the native exact-retention-on-every-exit control pass
(`p5-owner-host-index-recheck.log`; root-index `run-9394789013531833868`, retention
`run-1109006426518887604`). License and diff checks pass. Public owner producer
adapters remain gated; next is complete nominal listener transport and Java/JNI
adapter composition.

Nominal listener transport checkpoint: one artifact registry now supports all
proved listener interfaces. Registration preserves identity within each nominal
interface and retains distinct proxies across interfaces; callback dispatch does
not search the registry. The native carrier fixture uses two real typed listener
proxies for one Java object, verifies repeated registration sharing, invokes the
second interface and propagates its original exception. Counts return to zero.
All O0/O3 carrier/holder/failure controls pass
(`p5-nominal-listener-controls.log`, `p5/carriers/run-6660307779449218699`).
License and diff checks pass. The single-interface component API remains available.

Facade adapter review: use private immutable addresses and private root state,
with the existing weak cache and identity methods. Same-class owner inputs can
use allocation-free Java guards and pass addresses directly. Different owner
classes require cached JNI field access, liveness checks and balanced native-side
guards; the exact input partition must remain bound to the invocation proof.
Scalar calls without callbacks or listener writes need no owner-record argument.
Keep the public producer closed until paired declarations, adapters, bootstrap
validation and positive/negative end-to-end tests cover this partition.

Paired owner adapter checkpoint: generated Java facades now keep native addresses
and root state private/final, balance the proved same-class guards, and pass a
bound different-class guard partition to JNI. JNI caches exact field metadata,
balances both lifetime and listener-retirement guards, reserves all slot ownership
before mutation, and reconciles protected final snapshots on every exit. Bootstrap
validates the complete paired inventory. Scalar getters carry only their address.
These remain internal generated artifacts until public producer integration.

The first paired run found an adapter alias bug: two holder inputs can identify
the same field, so the final value may come from either input's writes. A child
JVM reproduction (`owner-native/run-2319135694248216226/O0/alias-diagnostic.log`)
confirmed the abort at the aliased setter. Preparation now reserves the complete
candidate union for each identical field, without changing native attribution or
unknown effects. Aliased and distinct holders both pass.

Paired O0/O3 consumers cover setters that throw after committing, replacement in a
suspended callback, nested calls, unchanged Java exception identity, null listener
failure, exact lifetime refusal, local/different-class active free, dead arguments,
guard-counter exhaustion and repeated free. Separately identified fault artifacts
cover first/second slot-allocation failure, unchanged installed state, zero final
slot/root/listener records and allocation-free repeated callback invocation.
Partial guard partitions reject. Java facade compilation/private metadata checks
pass (`p5-owner-native-fault-controls.log`).

O3 inspection found an avoidable owner-leave helper on the normal path. Its counter
decrement is now always inline; retired cleanup remains outlined behind a nonempty
list check. Both paired/fault consumers and prior native carrier controls pass
afterward (`p5-owner-inline-controls.log`, carrier `run-16435408613696775077`,
paired `run-10598967831426867459`). The receiver-only adapter has a 368-byte ARM64
frame and no normal-path identity search, allocation or JNI field access
(`workspace/java-bridge/p5-owner-twice-inline-O3.asm`). A local Mac diagnostic with
5 warmups and 7 samples of 100,000 checked invocations measured medians 13.103 ns
for the scalar getter and 242.774 ns for an invocation containing two callbacks
(`O3/owner-performance.log` in that paired run). This is an internal Mac diagnostic,
not Linux qualification or the required application performance comparison.
License and diff checks pass. Public producer packaging/parity is the next gate.
