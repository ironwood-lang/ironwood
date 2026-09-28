<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Callback performance investigation

Starting revision: `a37804e1`, local `java-bridge`, 2026-09-28. The maintainer
rejects the approximately 105 ns/event Linux x86-64 callback result and requests
optimization toward the pure Java/native results. Linux is the performance judge.
The original measurements remain historical evidence, not numerical acceptance.

## Contract and verification plan

Preserve synchronous event ordering, one callback per event, observable Java
side effects, reentry, listener replacement, active-owner guards and exception
identity/containment. Do not batch or defer callbacks silently, replace native
execution with Java, bypass supported JNI, or weaken conservative effects and
reclamation proofs. Native-only specializations and official OrderBook stay
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
