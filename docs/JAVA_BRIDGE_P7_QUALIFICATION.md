<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7f combined JNI qualification

## Authority and current checkpoint

D238 records the maintainer's explicit choice of JNI only and authorization to
proceed to P7f in this task. P7e closes through its allowed keep-JNI outcome;
P7e1/P7e2 remain deliberately unimplemented. Work starts at `20cae6f1` on local
`java-bridge`, with a clean canonical checkout and both origin URLs verified.
No push, worktree, installation or publication is authorized. Estonia work
stays under `~/temp/java-bridge`. Keep progress here across context compaction.

## Pre-change review and exit selection

Contracts: mandatory reclamation proofs and conservative unknown effects;
callback identity, retained listener cleanup and batching equivalence; copied
array alias/copy-back behavior; byte-view bounds/storage ownership; finite
and final-bounded generic signatures; class/archive reconstruction; flag-free
JNI deployment and Java 21-23; exact payload/package identity and notices.
Production implementation is currently unchanged from the P7d2 candidate.
No shared proof change is planned. Any discovered failure blocks its dependent
qualification and requires a focused correction and paired regressions.

Qualification consumers and safe/unsafe pairs:

- Existing focused callback batching/retained-listener tests, including reentry,
  throwing listeners, unsafe frees, unknown-effect refusals and artifact parity.
- Array input/value proofs and producers, staging and copy-back failure tests:
  accepted nonretaining values versus retained/escaping inputs, normal and
  exceptional writes, aliases, OOM and preservation of the original failure.
- Byte-view proof/producer/dependency tests: bounded live storage versus invalid
  bounds, read-only writes, retention, incompatible companion and package mix.
- Read-only/final-bounded generic proofs and producer tests: concrete/raw/wildcard
  clients versus unsupported bounds, unsafe frees, failed construction/mutation.
- Assembly/distribution tests: matched payloads/sources/notices versus tampered,
  mismatched or incomplete artifacts. Keep fault-injected fixtures separate
  from unmodified generated deliverables.
- A new ordinary Java composition consumer loads all enabled example APIs
  together, reenters array/view/generic code from a retained listener, preserves
  Java exception identity and checks invalid lifetime operations. It imports
  generated facades without developer-written JNI, loaders or native flags.
- Fresh three-target assembly and distribution of those artifacts, with byte
  identity checks and Java 21/22/23 class-path, module-path and executable-JAR
  launches. Verify Java 24 refusal separately on this Mac.
- Re-run the existing deterministic array/view/listener three-scenario workloads
  and generic-versus-plain facade comparisons, retaining exact payload hashes,
  copy/allocation policies, throughput/amortized latency and disassembly.
  No new tail-latency claim or official OrderBook source change.

Use the smallest exact-name compiler selection for these contracts, not an
unfiltered suite. Replay generated children on 22/23 with their original fault
settings and assertions. Source/class/archive coverage remains in the existing
producer fixtures. Run license and whitespace checks. Stop after applicable
checks pass; further numerical tuning remains outside P7f.

## Progress

- Repository policy, contribution rules, P7 plan and shared-analysis regression
  lessons read. Existing qualification tools reviewed; the old P6 manifest
  explicitly excludes callbacks and cannot serve as P7f evidence unchanged.
- Added the [exact 18-test selection](../scripts/java-bridge/p7/tests.json),
  [staged runner](../scripts/java-bridge/p7/README.md) and combined Java consumer.
  The first consumer incorrectly classified the generic example's static Quote
  as a borrowed value. Existing runtime behavior exposed that test error; the
  corrected consumer asserts its process lifetime and refusal to access its
  freed Box. No production code or safety proof changed.
- Mac host jars were produced in `workspace/java-bridge/p7f/mac-produce`.
  Preserve that directory's original failing consumer log. The corrected
  consumer passes all 18 Java 21/22/23 launches against those unchanged jars in
  `mac-host-launch`. The focused tests/replays are running in `mac-tests`.
- Pending: finish platform tests, package assembly, final launches, measurements
  and completion report. Do not claim P7f complete from this checkpoint.
