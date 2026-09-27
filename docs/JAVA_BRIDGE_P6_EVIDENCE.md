<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge distribution and qualification evidence

## P6a candidate

P6a is complete for implementation. P6b and release qualification are open.
Java 21-23 remain the supported baseline under the recorded D209 product
decision. P5 callbacks and P7 extensions remain rejected/deferred.

The candidate was built with production compiler revision `2559e145` on local
branch `java-bridge`. Later test/documentation changes do not change those
production inputs. No release, push or remote publication was performed.

Evidence root: `workspace/java-bridge/evidence/p6a/candidate-2559e145`.
Each case's `evidence.json` records full manifests, program inputs, consumer
hashes, target native-build identities and individual image hashes. Combined
jars are in each case's `combined/` directory. Both optimization variants of
VersionProbe use identical source and generation identity.

| Case | Assembled jar SHA-256 |
| --- | --- |
| values-O3 | `8f3c987a74a57ddc498534aa3f2358f3db739ce255d5e5828b8e6005fcc1f051` |
| roots-O3 | `7c0dc3533c6a0afaea875dccb70f9fb1b9585a805903c652678df2ebc2f1f73f` |
| orderbook-O3 | `e1ee3ac21c8e9639d837f5957d6644e67ef796f054f6ee08bd743babe2348478` |
| version-O0 | `6944696192b2860c85409b5124f9947ec5aeaa329f66ccb7fafd3a897e4aed43` |
| version-O3 | `f70926def67ae67a7342d95731d325289ed1750249dce564931d793fa3a598d1` |

Compiler content identity:
`bc5628de106fc3a450195881a1ccb9166bd18f30a9c5d8545dc3a006f17dc7a7`.
Runtime content identity:
`eff7bd01439b5f44bf5b762b4a545ad92d7b145385c0710e433b4afd24d68ef4`.

All five cases pass input-order-independent three-target assembly and 18 Java
21 launches each: class path, automatic module and executable jar, with and
without checked JNI. Linux launches use the minimal JVM images. OrderBook
matches the paired workload and records zero warmed Java allocated bytes with
escape analysis disabled. Linux x86-64 execution is Rosetta functional evidence.

`audit-{macos-arm64,linux-arm64,linux-x86_64}/result.json` binds final images to
the candidate hashes. Audits preserve macOS ad-hoc signatures and stable install
names; Linux images retain eager binding, private dependency resolution and
complete producer-audited glibc 2.17 support. Target disassembly is saved for
inspection. Earlier focused assembly regressions reject mismatched/tampered
inputs and require byte-identical repeated host production; see the progress log.

`build-tools/evidence.json` records Maven 3.8.6 and Gradle 8.14.3 local workflows:
two producers, both direct consumers and both cross-tool consumers. Installed
main/source/Javadoc jars retain exact producer bytes; the assembled value jar
is used without rebuilding native payloads. Standard POMs and companion
inventories are preserved in the evidence's isolated local repositories.

## P6b status

Fixed candidate launch checks pass: 94 macOS ARM64 children (90 supported and
four Java 24 refusal checks) and 90 Linux ARM64 virtualization children. Evidence
is `workspace/java-bridge/evidence/p6b/candidate-{macos-arm64,linux-arm64}`.
All pinned JDK installations are checked offline against preparation metadata.

Loader qualification passes in `p6b/loaders-macos-arm64` (120 children) and
`p6b/loaders-linux-arm64` (114). Linux records six macOS deployment-floor cases
as not applicable, retaining every other scenario. Runs use O0/O3 and all three
supported JDKs. Negative registration/mixed/signature fixtures remain identified
test controls, never production payloads.

Java 21 generated fixture checks pass on both ARM64 hosts. Mac runs all 17
selected tests in `p6b/fixtures-macos-arm64/tests.log`. Linux's same coverage is
split across two initial passing root/allocation cases, three port follow-up
tests and twelve remaining selections in `p6b/fixtures-linux-arm64/tests.log`;
the exact selections, commands and per-test artifact paths are retained.
Assertions cover registration reservations, delivery failures, weak collection,
forced native address reuse, exact retention commits, permanent identity, enums,
custom snapshots, exception exhaustion, OrderBook allocation and native stack.
These are generated fixtures with explicit test instrumentation where needed;
they supplement the unmodified public candidate checks.

Both hosts pass six public generated-entry bounded stack cells in
`p6b/generated-stack-{macos-arm64,linux-arm64}` using the candidate's compiler
and runtime identities. Separate 512k/1m adaptive child probes record limits and
failures as diagnostics. They do not establish general stack-overflow recovery.
Java 22/23 fixture replays pass on the same generated images: 194 children per
Mac JDK and 196 per Linux JDK (the extra two are passive enum access checks).
Final evidence is `p6b/replay-{macos-arm64,linux-arm64}-{22,23}-paired`; earlier
runner-comparison failures remain preserved separately. Replays retain every
consumer assertion, fault setting and expected exit, and verify extracted
native bytes against the selected jar payloads. The fifteen selected compiler
proof/producer-guard cases pass separately in `p6b/proofs/tests.log`.

Performance measurements and hardware handoff preparation remain in progress.
No supported matrix cell is yet declared complete here.
Final numerical performance acceptance remains the maintainer's review.
All real Linux x86-64 JVM, stack, allocation and timing qualification remains
**pending x86-64 hardware**, including D213's deferred P0-10 probes.
