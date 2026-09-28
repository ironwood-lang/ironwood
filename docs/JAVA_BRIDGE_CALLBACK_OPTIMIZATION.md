<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Callback performance investigation

Starting revision: `a37804e1`, local `java-bridge`, 2026-09-28. The maintainer
rejects the approximately 105 ns/event Linux x86-64 callback result and requests
optimization toward the pure Java/native results. Linux is the performance judge.
The original measurements remain historical evidence, not numerical acceptance.

## Contract and verification plan

Preserve synchronous event ordering, one callback per event, observable Java
side effects, reentry, listener replacement, active-owner guards and exception
identity/containment. Batching was initially deferred; D231 subsequently
explicitly authorized automatic batching with an equivalence proof. Do not
replace native execution with Java, bypass supported JNI, or weaken conservative
effects and reclamation proofs. Native-only specializations and official OrderBook stay
unchanged. Java 21-23 remains the baseline.

Inspect the native event loop and callback adapter, then compare equivalent
handwritten JNI controls with the same Java listener arithmetic. Distinguish
Java-to-native calls from native-to-Java callbacks. Measure cached interface,
concrete and static dispatch and argument transport alternatives, preserving
exception detection in candidate paths. Diagnostic omissions cannot become
production changes. Keep raw samples, checksums, tool versions, commands,
disassembly and payload identities in ignored workspace evidence.

Primary consumers are `BridgeCallbackNativeSources`, generated proxy/context
lowering, both stateless and retained-owner producers, and Java callback methods.
Before changing shared machinery, expand this map and select corresponding
safe/unsafe controls. Candidate transport changes require primitive return/type
coverage, throwing and nested callbacks, retained slots, owner conversion,
source/class/archive parity, checked JNI and O3 disassembly. Use the focused
P5 native-carrier, owner, stateless producer and proxy tests; do not run a full
compiler suite. Run licenses and diff checks for source changes.

## Checkpoints

- Verified canonical root/origin, clean branch, contribution/license rules,
  regression lessons and P5 contracts. Existing primitive adapter caches its
  method ID and has no per-event listener identity lookup or allocation.
- Estonia has approximately 64 MiB free on its root filesystem. Use existing
  pinned containers with RAM-backed scratch and stream results to the Mac;
  preserve existing files and install nothing.
- The maintainer explicitly authorized deleting our files in the remote
  validation folder. Removed redundant generated `workspace/java-bridge/evidence`,
  `workspace/java-bridge/experiments` and `handoff` under that folder. Local
  archived evidence and remote pinned JDK/support inputs remain. Remote folder
  usage fell from 114 GiB to 3.6 GiB; root filesystem free space is 110 GiB.
- Handwritten JNI controls on isolated Estonia CPU 1, Java 21, three fresh forks
  with eight warmups and nine samples of one million events each: interface
  `CallVoidMethodA` 103.040 ns/event; concrete virtual 98.694; nonvirtual 98.497;
  static Java relay 98.335; interface varargs 105.762; static varargs 100.872;
  `ExceptionOccurred` alternative 102.736; local listener reference 102.655.
  Java-only control 1.291 ns/event; Java-to-native scalar round trip 7.571 ns.
  Every callback case validates count/checksum/result and unchanged thrown
  identity. Separate checked-JNI children pass with no warnings. The first
  control attempt failed before compilation due to an incorrect JNI include
  directory; the corrected run is `control-x86-v2`.
- Native disassembly shows a small recurrence loop and one callback adapter.
  The adapter calls JNI and checks pending exceptions, without allocation or
  listener lookup. Inspected pinned Linux HotSpot machine code as well: JNI
  calls perform thread-state/safepoint transitions and argument handling;
  exception detection is another VM transition, not a free native field test.
  These controls do not support the assumption that Java-to-native and
  native-to-Java crossings have equal costs.
- Candidate: artifact-private static Java relays, cached JNI class/method IDs,
  unchanged typed foreign calls and exception checks. Public source/class/archive
  producer tests pass on macOS ARM64 for stateless and owned routes. The first
  owned build exposed a nominal source-name resolution error in the new relay;
  corrected it using existing resolved surface metadata and reran successfully.
  Compare complete baseline/candidate Linux measurements before accepting it.
- Complete Linux x86-64 production comparison (`static-x86`, three forks,
  five warmups, seven samples of one million events): Java 21 103.021 -> 98.833
  ns/event; Java 22 107.616 -> 100.895; Java 23 108.923 -> 108.407. Pure native
  remains 1.247 and Java-only 1.255 ns/event. Counts, checksums and allocation
  fields pass. This modest improvement does not achieve the requested target.
- Added a public producer regression for all primitive carriers, zero-argument
  void callbacks, nested calls and checked exception identity at O0/O3. It and
  the owned native lifetime/failure fixture pass on macOS ARM64. The initial
  test compilation used the command helper as varargs; corrected to its actual
  array signature before execution. Licenses and diff checks pass.
- Additional isolated handwritten controls (`alternatives-x86`): putting the
  loop in Java and calling a small native step before the Java listener takes
  8.394 ns/event. A research-only Java 22 FFM upcall with contained listener
  failures takes 23.428 ns/event. Same-run JNI controls remain 102.972 for
  interface dispatch and 98.544 for static relay; Java-only is 1.265. These are
  alternative calling-convention controls, not implementations of the Ironwood
  bridge or qualifications of its ownership/reentry contract. FFM implementation
  remains P7 work and Java 21 artifacts do not acquire preview/native-access
  requirements. Neither alternative reaches the pure Java/native result.

Working evidence: `workspace/java-bridge/callback-optimization/`. Neither a
smaller ratio nor a successful functional test constitutes performance acceptance.

## Authorized automatic batching checkpoint

The maintainer explicitly authorized compiler-proved automatic batching on
2026-09-28, with per-event Java delivery and ordinary-JNI fallback when equivalence
cannot be proved. D231 narrowly supersedes the P7 batching deferral. Implement
without changing developer wiring or relaxing safety/exception contracts.

The handwritten JNI batch control on isolated Estonia CPU 2 measures 132.050,
17.983, 6.103, 3.296 and 2.481 ns/event for batch sizes 1, 8, 32, 128 and 1024.
It preallocates the Java array before timing, copies a pure native recurrence,
calls the Java listener separately for each event, validates every result and
stops at the exact failing listener. Three forks, eight warmups and nine samples
of one million events; checked-JNI child controls pass. This is deliberately not
generalized to arbitrary native side effects. Inputs/results are in `batch-x86`.

Production commit `3984aa66` contains the smaller static relay improvement.
Its measured compiler classes and production sources match 1,567 archived input
files exactly. Standalone native disassembly is identical (4,391 lines).
macOS ARM64 passes both six-cell nested stack matrices across Java 21/22/23.
Linux qualification is still in progress. ARM64 passed four existing fixtures,
then Docker reported OOMKilled (137) during the new primitive dispatch test.
The original evidence archive is preserved; `resume-arm.py` resumes only the
unfinished test and remaining replay/stack checks using disk-backed output.
x86-64 passed the four existing fixtures and is completing the new test and
remaining checks. Do not label those pending checks passed before collecting
their results.

Next implementation review: inspect typed IR for the listener recurrence, define
a narrow loop-equivalence proof, and specialize only the proved callback path.
Select paired refusal/fallback cases for mutation, throwing arithmetic, dynamic
listeners, callback results, branches and post-callback native effects. Nested
calls must never overwrite a suspended batch. Exception handling and active-owner
protection must remain within their already protected boundaries.

Static relay qualification completed: both Linux targets pass all five focused
fixtures, 141 checked-JNI replay children across Java 21/22/23, and both six-cell
stack matrices. ARM64 resumed on disk-backed output after the preserved OOM
failure; a 768 MiB Java heap retry was insufficient, and the successful retry
used 1,536 MiB. The x86 evidence stream accidentally contained 184 bytes of
printed command paths before its gzip header. Preserved that original stream,
recorded the prefix and offset, and verified/extracted the complete archive.
These qualifications cover `3984aa66`, not the pending batch implementation.

Batch implementation uses a separate typed-IR scheduling proof after mandatory
admission. It retains original functions and adds specialized protected entries
only for a counted loop with captured listener, integral local computation,
one void callback per iteration and no observable intervening work. The initial
transport supports one to four long arguments. Every other valid admitted loop
keeps ordinary JNI. Source names and example identities are not proof inputs.

Reusable private direct LongBuffers avoid copying chunks and large native stack
frames. They are generated transport storage, not an exported array/zero-copy
API. Existing active-use depth selects distinct nested buffers. Java holds the
buffer strongly throughout each native call; native code retains no address
after return. A missing buffer or failed optional allocation selects ordinary
JNI. Warm successful invocations must allocate nothing. Per-event Java calls
remain ordered, with the first thrown exception stopping the chunk inside the
existing protected native boundary.

Focused checks: `Java Bridge batches only effect-free counted callback loops`
pairs the pure recurrence with mutation, reload, division, dynamic count,
conditional/extra callbacks, non-unit induction, native catch and early/post
effects. `Java Bridge automatic batches preserve order reentry failure and
artifact parity` checks boundaries, nested replacement/clearing, active/dead
owner refusal, unchanged throwable identity, continued use, forced scratch
allocation failure, O0/O3 and source/class/archive reconstruction. Requalify the
affected P5 fixtures and bounded-stack cases after final transport changes,
inspect O3 code and measure the actual producer output on Estonia. No full suite.

First production batching checkpoint: the proof tests (including callback-result
and helper-call fallback) and generated source/class/archive consumers pass on
macOS ARM64. Consumers exercise one through four long arguments, zero/small/full/
partial chunks, nested listener replacement and clearing, callback failure at
the first/middle/boundary/last event, active/dead owner refusal and continued use.
The first direct-memory-limit child failed during image extraction; corrected
the fixture to exhaust memory after bootstrap, then verified ordinary-JNI
fallback for both success and callback failure. All children use checked JNI.

Actual Estonia producer measurements (`batch-production-x86-v1`, CPU 1, three
forks, five warmups, seven samples of one million events) are 3.064 ns/event on
Java 21 and 22, and 3.082 on Java 23. Java 21 throughput is 326.34 million events/s.
Pure Ironwood is 1.245 ns/event and pure Java 21 is 1.255. Counts/checksums/results
match; all measured JVM samples allocate zero Java bytes. This is approximately
32 times faster than static-relay JNI, still slower than the pure scenarios.
The archived input manifest, paired JAR/native image, disassembly and raw samples
identify this candidate independently of subsequent optimization changes.

Disassembly still shows a native adapter call and its register saves on every
event. Next: outline chunk delivery to remove those saves from ordinary appends,
measure again, then assess whether further typed lowering is justified. Final
batch qualification and performance acceptance remain pending.

Follow-up lowering review: model the bounded append as a compiler-owned typed
instruction with a boolean chunk-complete result, then express delivery with
ordinary typed branches and the existing foreign-call instruction. This keeps
exception reachability explicit and lets LLVM inline buffer stores into the
native recurrence. Its frame ABI must be shared with generated C and checked by
static layout assertions. The operation is introduced only after source safety;
source borrow analysis must refuse it, while unknown-effect defaults remain
conservative. Consumers are the CFG renamer and LLVM emitter, not source syntax
or ownership exemptions. Repeat the existing batching proof/native tests and
matched Linux performance/disassembly checks after this change.

Commit `37ce511b` records the first complete scheduling proof and transport.
Subsequent measured candidates: outlined C delivery 2.928 ns/event on Java 21
(`batch-production-x86-v2`); typed append with explicit counters 2.721
(`batch-production-x86-v3`); typed append using the proved loop index 2.521
(`batch-production-x86-v4`). The final form keeps only buffer stores and index
arithmetic between JNI deliveries. It does not maintain redundant per-event
transport counters. Native-only disassembly remains byte-identical as text
across v1/v4 (4,394 lines including headers).

The v3 short-call experiment filled its container's 1 GiB `/tmp` with successive
JVM extractions. The full three-way measurement had already passed; later short
cells did not run. Preserved that failure and corrected the runner to give each
JVM a fresh temporary directory and remove it after exit. v4 completes every
cell. This was container scratch, not renewed consumption of Estonia's host disk.

### Current Linux x86-64 production measurements

Estonia CPU 1, pinned toolchain/JDKs, three fresh JVM forks, five warmups and seven
one-million-event samples per fork. Latency means elapsed invocation time divided
by event count; these are not individual callback arrival percentiles.

| Scenario | Java | Median ns/event | Million events/s |
| --- | --- | ---: | ---: |
| Pure Ironwood | none | 1.247367 | 801.689 |
| Pure Java | 21 | 1.254810 | 796.933 |
| Ironwood processor / Java listener | 21 | 2.520613 | 396.729 |
| Pure Java | 22 | 1.254849 | 796.909 |
| Ironwood processor / Java listener | 22 | 2.519270 | 396.940 |
| Pure Java | 23 | 1.254806 | 796.936 |
| Ironwood processor / Java listener | 23 | 2.527266 | 395.685 |

The Java 21 bridge is about 41 times faster than the original 103.021 ns/event
JNI baseline, but remains about twice the pure Java/native time. The generated
code computes results in native Ironwood and delivers them in a separate Java
loop. The pure scenarios can optimize computation and listener work together;
that separation explains a remaining cost after amortizing JNI. This is not a
claim that the bridge has surpassed pure Java or that every callback loop batches.
Numerical acceptance remains the maintainer's review.

Short-call controls use three forks, eight warmups and nine samples, validating
every event/result and zero measured Java allocation. Median ns/invocation:

| Events per invocation | v1 (ordinary JNI below 128 events) | Indexed batching |
| ---: | ---: | ---: |
| 1 | 117.492 | 118.769 |
| 2 | 232.080 | 141.421 |
| 4 | 427.113 | 148.279 |
| 8 | 818.330 | 154.465 |
| 16 | 1607.994 | 174.286 |
| 32 | 3183.789 | 210.237 |
| 64 | 6356.316 | 284.280 |
| 128 | 523.193 | 435.671 |
| 1024 | 3177.778 | 2621.182 |

The generated facade therefore selects batching at two events. Single-event
calls keep ordinary JNI. Buffers allocate lazily, reuse storage at each active
depth, and become eligible for Java reclamation when the owner is freed. These
figures exclude initial buffer allocation.

Current indexed verification: macOS passes the scheduling proof, public
source/class/archive O0/O3 cases, checked-JNI reentry/failure/arity cases, optional
scratch-allocation failure, zero warmed Java bytes and a separate native counter
fixture at O0/O3. The counter is a test-only JNI export beside the unchanged
generated transport; an attempted public `System.allocationCount` export was
correctly refused by existing admission and was removed without relaxing that
proof. The six indexed-batch stack cells pass at native depths 1/8/32/64 and Java
depths 0/64 across Java 21/22/23. Separate stack-limit child outcomes remain
diagnostics. Ordinary owner and primitive-dispatch regressions also pass.

Linux qualification is running from disk-backed isolated snapshots:
`batch-qualification-arm64` and `batch-qualification-x86_64`. Both retain input
hashes, commands and outputs. Complete their eight selected fixtures, existing
141 replay children, 20 additional dispatch/batch/allocation children on Java
22/23, and three six-cell stack matrices before calling the new transport
qualified. Main source/classes in the measured v4 archive must match those
snapshots and the final checkout. Update usage/overview/example status, commit
the final lowering and documentation locally, and preserve the numerical gap in
the final report. No push or publishing.

Final input consistency check: all 1,296 production source/class files match
between the measured v4 input, both Linux qualification inputs and the checkout.
The compiler JAR matches its 852 class files and all three archived copies;
its SHA-256 is `85261a7527fb5da44aed09456b0221df37a2be0a23c3dfc9f5b5d93411888c93`.
The input report is `indexed-production-match.json` under the investigation's
workspace directory. The final x86 disassembly has native recurrence and buffer
stores between deliveries; its only callback helper call is reached at full or
final chunks. No native-only code changed.

### Reproduction and artifact identity

Use the pinned Java 21 bootstrap, LLVM 23 and existing target support SDK from
[the producer guide](JAVA_BRIDGE_USAGE.md). The committed runner builds all three
scenarios without changing the listener example or official OrderBook sources:

```sh
python3 scripts/java-bridge/measure-listeners.py \
  --target linux-x86_64 --execution-scope 'x86-64 physical hardware' \
  --java21-prefix /opt/ironwood-bridge-jdk --jdk-root /jdks \
  --llvm-home /opt/ironwood-toolchain \
  --host-notes 'Estonia, isolated CPU 1; pinned toolchain and JDKs' \
  --evidence workspace/java-bridge/evidence/listeners-NEW
```

Run inside the existing pinned x86 container with CPU affinity 1 and the existing
support directory mounted and selected by `IRONWOOD_BRIDGE_SUPPORT_HOME`. The
recorded Docker/SSH command is in `batch-production-x86-v4/command.json`; its
input archive contains the exact source/class manifest and runner. No installation
is needed. Preserve `result.json`, paired JAR/native image, native-only executable,
raw samples, command logs and disassembly together. Check counts/checksums and
allocation fields before comparing numbers. Use matching target/scope/toolchain
options for ARM64; virtualization results are identified separately.

The three focused batching tests are selected by their exact names above through
`./scripts/test.sh --test 'EXACT NAME'`. The stack runner's new
`--batched-callbacks` mode qualifies suspended chunks, reentry and failure using
O0/O3 and Java 21/22/23. Existing `--callbacks` and `--owned-callbacks` qualify
ordinary JNI routes. Child stack-limit failures are retained as diagnostics,
never interpreted as successful recovery.

Measured x86 payload SHA-256 identities:

| Item | SHA-256 |
| --- | --- |
| v4 input archive | `d8fea5953326fc538b7c914d229e5f3673601a0bd5c1a974b2d40f98894b851e` |
| Paired listener JAR | `bf4f5891dd6aa05d4bf56c2127dc26f05899fe0c1f1c99814444b5eb21e00d1d` |
| Native listener image | `2553d82f63835cec5f9f555bd4a1c78a1f7f6ea5e499d5c964d64a28c420d651` |

The evidence archive also retains the generated private relay sources and pairing
manifest. Qualification input hashes are in the consistency report; qualification
outputs remain separate from performance outputs. These identities describe
actual producer artifacts, not the handwritten 2.48 ns research control.
