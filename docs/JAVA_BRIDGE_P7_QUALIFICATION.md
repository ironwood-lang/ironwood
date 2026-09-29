<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7f combined JNI qualification

**Complete on all three targets.** JNI is retained, Java 21-23 remains the
baseline, and FFM remains unimplemented. The [functional matrix](#functional-qualification)
passes; [new performance measurements](#performance-and-allocation-record)
remain subject to numerical review. No production behavior changed in P7f.

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
  `mac-host-launch`.
- The first 13 Mac tests and their Java 22/23 child replays pass. Test 14
  exposed a stale P7d1 diagnostic assertion: P7d2 correctly refuses an unbounded
  public generic constructor with its newer final-bound diagnostic. Updated
  only that assertion, preserving failure exit status and unchanged-output
  checks for source, classes and archive inputs. Production code is unchanged.
  The corrected test and remaining four pass in `mac-tests-followup`. Together
  with the original passing tests, Mac has 18 passes and 218 successful Java
  22/23 child replays. The Linux runs received the same test-only supplement
  before reaching that test. No passed checks were repeated unnecessarily.
- Local commit `38fda699` records D238 and the initial runner. Frozen Linux
  input archive SHA-256 is
  `5c25d99b269d1ea2c9928d6a5cc29fb11e12bf74967f228981a0dcfbc00403a6`.
  The unchanged production compiler JAR is
  `14c59cd2f7cda9b71dde336ffe7ba7164db755b25ba1cb1bf53463ff2bd9f836`.
  Test-only supplement archive:
  `f04c739866683071969b75504662ccc9a514ad03dbfa46a5087b30e8cbaa2c11`.
  Measurement/audit runner supplement archive:
  `39fe68920a744488c90cdd0f58bd24a765e2f9a7356aeaf9949f98dfd44042c4`.
- All five host producers pass on both Linux targets. Initial x86 host transfer
  failed on root-owned generated files; its partial archive is retained.
  Changing ownership of only this task's completed `evidence/host` enabled a
  complete transfer, whose five artifact hashes were verified before assembly.
- `combined-candidate` assembles all five artifacts in both input orders with
  identical output bytes, preserves all 15 native images and produces local
  sources/Javadoc/POM distributions. Candidate transport archive:
  `67012f5d77ed43450af3a4cdb025aa79112be39b5fa54dd2fa5d41ca279d3aa7`.
  All 18 final Mac and all 18 final Linux ARM64 launch forms pass. The Mac audit
  verifies all 15 images, signatures, ELF baselines/dependencies and Java 21
  class versions. Ten fresh Java 24 children refuse before native extraction.
- Benchmark runners can now reuse qualified single-target host JARs. This keeps
  measured images identical to the images preserved in the combined packages;
  native-only/Java-only benchmark definitions and example APIs are unchanged.
- Commit `3f2e3652` adds the payload audit, matched-host benchmark reuse and
  corrected generic rejection assertion. All three focused selections now pass.
  Estonia final launches, minimal-JVM checks and all measurements also pass.
  Final candidate extraction used the task container after the host could not
  write its root-owned evidence directory; the transport hash remained exact.
- Linux ARM64 measurements also pass. All 15 measured images match their
  combined-package payloads; the final records and remaining review are below.

## Functional qualification

Temurin 21.0.12.1+1 produces Java 21 facades with LLVM 23.1.0. Consumers use
the pinned Temurin 21, 22.0.2+9 and 23.0.2+7 installations. Linux uses the
existing glibc 2.17 native support SDK. No translated timing or new installation
is included.

| Target | Execution | Exact focused checks | Java 22/23 fixture replays | Combined launch forms |
| --- | --- | ---: | ---: | ---: |
| macOS ARM64 | Apple M5, macOS 26.6.2 | 18 | 218 | 18 |
| Linux ARM64 | Local Colima ARM64 virtualization | 18 | 218 | 18 |
| Linux x86-64 | Physical Estonia, Xeon E-2288G | 18 | 218 | 18 |

Every row passes. The 54 combined launches cover class path, module path and
executable JAR on all three supported JVMs, each with and without `-Xcheck:jni`.
They load all five independent generated artifacts and the shared ByteView
dependency together. Callback reentry exercises arrays, views and both generic
shapes; retention, active-owner/free refusals, exception identity and recovery
remain enforced. Generated child replays preserve original failure-injection
settings and exact assertions. This is a focused P7 selection, not a rerun of
the unfiltered compiler suite or every historical P0-P6 experiment.

Both Linux targets also pass checked and unchecked consumers in the existing
minimal-JVM images, without the producer SDK. Image identities are
`da9117595e95447d1cc27409f701b270518568644c92f7771fc1fbf489a961a2`
(ARM64) and
`12d78d656643d28873367f95ff40ed046af423c183615a7ff8bb92e7ea549c4f`
(x86-64). Ten separate Mac Java 24.0.2+12 children refuse all five artifacts,
with and without checked JNI, before extracting native files.

## Package and code inspection

The [artifact catalog](JAVA_BRIDGE_P7_ARTIFACTS.json) records the five combined
JARs, common API/program/generation identities, all 15 native build identities
and SHA-256 digests, shared ByteView dependency and local distribution files.
Assembly is byte-identical in both input orders. All target images retain their
exact host-produced bytes. Source/Javadoc companions are populated, main JARs
are unchanged by distribution, and source/notice and tampered-package checks
pass. No arbitrary payload substitution or unsupported API admission was used.

The image audit retains optimized disassembly, strict Mac signature checks,
Linux ELF architecture/dependency/version reports, eager binding and relative
runtime paths. Generated classes target Java 21 and do not acquire FFM calls.
Inspected x86-64 ByteView loops directly access bounded storage; array adapters
retain their explicit copies. The callback batching helper calls its generated
Java batch dispatcher and checks the returned exception state, with capture on
the failure path. No production hot-path change was introduced by P7f.

## Performance and allocation record

Each target contributes 735 recorded timing samples: 189 copied-array, 315
byte-array/view, 42 generic getter, 42 bounded setter and 147 listener samples.
The four array/view/generic workloads use Java 21. Listener comparisons cover
Java 21, 22 and 23. Full rows, sample ranges, commands, disassembly and payload
hashes are retained in the evidence catalogs below. These are warmed throughput
and amortized batch timings, not individual-operation tail-latency percentiles.

Estonia is the numerical reference: physical x86-64, isolated CPU 1, existing
powersave governor unchanged, with the qualification compiler tests finished
before timing. Mac measurements shared host resources with the ARM64 VM tests;
Linux ARM64 timings began after those tests finished. The manually supplied
listener host notes incorrectly said M2; `sysctl` and the captured CPU report
identify this host as **Apple M5**. Original logs are preserved with this correction.

Timings reuse the qualified single-target host JARs. Every measured native image
has the exact digest of its target image in the combined packages. The final
combined packages separately pass the complete launch matrix. Production code
and benchmark algorithms were unchanged; no new speedup is claimed.

### Estonia copied int arrays

Cells are **ns/call / million calls per second**. Size is the number of int
elements. Raw evidence also includes size 64. Copying remains costly, especially
for tiny operations; passing these qualification checks does not imply a
performance advantage over Java.

| Operation | Size | Pure Ironwood | Pure Java | Java Bridge |
| --- | ---: | ---: | ---: | ---: |
| read | 1 | 2.14 / 467.308 | 3.56 / 280.867 | 104.74 / 9.547 |
| read | 4096 | 621.72 / 1.608 | 218.07 / 4.586 | 1724.58 / 0.580 |
| update | 1 | 3.77 / 264.994 | 4.27 / 234.356 | 127.68 / 7.832 |
| update | 4096 | 830.31 / 1.204 | 1184.95 / 0.844 | 2494.18 / 0.401 |
| fresh | 1 | 31.12 / 32.133 | 6.23 / 160.623 | 101.10 / 9.891 |
| fresh | 4096 | 418.28 / 2.391 | 1634.50 / 0.612 | 1881.45 / 0.532 |

### Estonia byte storage

Cells use the same units. Native uses the byte-array kernel; Java array, Java
ByteView, bridge array and bridge ByteView execute equivalent checked workloads.
Raw evidence also includes size 64. Overlap measures ordered overlapping writes.

| Operation | Bytes | Pure Ironwood | Java array | Java ByteView | Bridge array | Bridge ByteView |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| read | 1 | 1.72 / 580.966 | 8.31 / 120.336 | 8.68 / 115.207 | 104.35 / 9.583 | 81.68 / 12.243 |
| read | 4096 | 1016.01 / 0.984 | 229.07 / 4.365 | 260.44 / 3.840 | 1388.88 / 0.720 | 1113.36 / 0.898 |
| update | 1 | 2.23 / 448.348 | 9.76 / 102.504 | 8.53 / 117.173 | 126.86 / 7.883 | 84.08 / 11.894 |
| update | 4096 | 1649.26 / 0.606 | 4107.10 / 0.243 | 1444.37 / 0.692 | 1958.04 / 0.511 | 1736.28 / 0.576 |
| overlap | 1 | 1.81 / 553.331 | 7.45 / 134.181 | 4.69 / 213.196 | 126.19 / 7.924 | 155.26 / 6.441 |
| overlap | 4096 | 3879.94 / 0.258 | 3466.46 / 0.288 | 5438.93 / 0.184 | 1615.22 / 0.619 | 6648.92 / 0.150 |

### Generic facade comparisons

These compare generic and equivalent concrete Java Bridge APIs, not three
different language implementations. Cells are **ns/call / million calls/s**.
Both forms retain the same required lifetime behavior.

| Target | Operation | Generic facade | Concrete facade |
| --- | --- | ---: | ---: |
| Linux x86-64 | getter | 11.66 / 85.764 | 11.25 / 88.924 |
| Linux x86-64 | retaining setter | 132.11 / 7.570 | 131.95 / 7.579 |
| Linux ARM64 | getter | 4.81 / 208.068 | 4.72 / 211.998 |
| Linux ARM64 | retaining setter | 64.76 / 15.441 | 65.02 / 15.380 |
| macOS ARM64 | getter | 5.99 / 166.989 | 5.65 / 177.115 |
| macOS ARM64 | retaining setter | 311.97 / 3.205 | 311.61 / 3.209 |

### Listener comparison

Cells are **ns/event / million events per second**, using the proved automatic
batch path. Every event still reaches its ordinary Java listener. The remaining
gap is recorded for later numerical review, not treated as an optimization pass.

| Target | Java | Pure Ironwood | Pure Java | Java Bridge |
| --- | ---: | ---: | ---: | ---: |
| Linux x86-64 | 21 | 1.247 / 801.73 | 1.255 / 796.97 | 2.521 / 396.73 |
| Linux x86-64 | 22 | 1.247 / 801.73 | 1.255 / 796.96 | 2.522 / 396.58 |
| Linux x86-64 | 23 | 1.247 / 801.73 | 1.255 / 796.93 | 2.527 / 395.71 |
| Linux ARM64 | 21 | 1.405 / 711.76 | 1.421 / 703.72 | 2.367 / 422.41 |
| Linux ARM64 | 22 | 1.405 / 711.76 | 1.417 / 705.63 | 2.360 / 423.68 |
| Linux ARM64 | 23 | 1.405 / 711.76 | 1.415 / 706.75 | 2.368 / 422.26 |
| macOS ARM64 | 21 | 1.432 / 698.32 | 1.437 / 695.69 | 2.503 / 399.48 |
| macOS ARM64 | 22 | 1.432 / 698.32 | 1.440 / 694.48 | 2.517 / 397.36 |
| macOS ARM64 | 23 | 1.432 / 698.32 | 1.434 / 697.43 | 2.505 / 399.15 |

Allocation and copy contracts passed unchanged:

- Copied int-array reads stage `4N` bytes; updates stage and copy back `8N`
  bytes in total. Fresh results copy `4N` bytes to a new Java array and reclaim
  the transferred native result. These fixtures use one native allocation per
  bridge call; fresh native-only results also allocate. Warmed input operations
  allocate no Java objects; fresh results necessarily allocate Java arrays.
- Copied byte-array reads copy `N` bytes; updates/overlap copy `2N`. ByteView
  kernels copy no data and allocate neither native nor Java objects on warmed
  calls. Storage and slices are allocated before timing.
- Generic/concrete getter and setter loops allocate zero native objects and
  zero Java bytes after warmup. Retaining setters still reconcile dependencies.
- Listener JVM loops allocate zero Java bytes after warmup. The standalone
  driver records native counts; the separate production batching regression
  proves zero native object allocation in warmed callback calls. The listener
  JVM timing field `native_allocations=-1` means unmeasured, not zero.

## Evidence retention and completion

Local evidence root: `workspace/java-bridge/p7f/`. Exact commands and stage
outputs are retained there. `mac-catalog`, `linux-arm64-catalog` and
`estonia-catalog` each contain `files.json` with byte counts/SHA-256 hashes,
`fixtures.json`, and a readable evidence archive. The catalogs cover all
reported fixture directories as well as the selected stage outputs. Full
fixture binaries remain in their original validation workspace; production
packages and the 15-image audit are also preserved on this Mac.

| Readable archive | SHA-256 |
| --- | --- |
| mac-catalog/readable.tar.gz | `88957b9688fb25063d67b30bbd3444be6554e16f68a4ee52e60b685093f6c4be` |
| linux-arm64-catalog/readable.tar.gz | `934233adaf24c73ff6732009ca92645ede0ad18eba2cfd4279896da4df059dfe` |
| estonia-catalog/readable.tar.gz | `14ebb04b5a2fa5d9b2c94c30654c3dcaa3d66b276ec47c4b6d3c6728eb2d9972` |

Estonia inputs, generated files and original evidence remain under
`~/temp/java-bridge/p7f-38fda699/`; its catalog is under `p7f-catalog/`.
No pre-existing host file, image or container was removed. P7f task containers
finished successfully. No installations, unfiltered suites, hosted jobs,
worktrees, main changes, pushes or publication occurred.

**P7f implementation and focused qualification are complete.** D238 closes P7e
with JNI retained; optional FFM remains unimplemented. P7a-P7d stay within their
accepted bounded contracts. Unsupported array/object shapes, unrestricted
generics and callback patterns outside the proofs remain rejected. Java 21-23
remains the supported baseline, with Java 24+ refusal unchanged.

No P7 implementation or selected x86-64 hardware validation remains pending.
Numerical acceptance of the new extension measurements remains the maintainer's
review, and further performance tuning remains deferred. This record does not
assert an overall speedup, acceptance of those numbers, or release readiness.
License and whitespace checks pass; the completion commit contains documentation
and recorded identities only.
