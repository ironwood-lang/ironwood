<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P3b permanent and value projection gate

This is a historical phase checkpoint. Subsequent [P6 evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
and [Estonia hardware results](JAVA_BRIDGE_X86_EVIDENCE.md) record completed
P0-P4/P6a implementation and D213 hardware work. P5/P7 remain deferred; final
numerical acceptance remains open.

P3b passes for continuation to P3c on local `java-bridge`, through producer
integration commit `c456d5d6`. The macOS ARM64 preview now exposes proved permanent
concrete objects, static nested types, enums and custom exception snapshots.
Reclaimable root/view adapters remain rejected. This is not completion of P3,
OrderBook acceptance, the final target/JDK matrix or release readiness.

The [implementation plan](JAVA_BRIDGE_PLAN.md) remains authoritative. P3b uses
the exact immutable [P3a admission](JAVA_BRIDGE_P3A_EVIDENCE.md), its final linked
program and protected typed entries. It adds no source ownership exemptions,
unknown-effect assumptions or unproved destruction capability.

## Contract and evidence map

Evidence paths below are relative to ignored `workspace/java-bridge/evidence/`;
focused test logs are in `workspace/java-bridge/experiments/`.

| Contract | Generated implementation and focused evidence |
| --- | --- |
| Permanent identity | Private immutable facade address/type metadata, Java-only inherited identity methods and world-level primitive-key weak cache. O0/O3 cold construction, repeated identity, actual weak collection/recreation, String cleanup and native allocation refusal: `p3b/permanent-facades/run-17248281164719719299`. |
| Host delivery failures | Separate injected artifacts preserve production payload identities. Preparation runs no target code; failed object/cache/String delivery preserves storage and permits retry. Failed bootstrap releases global references: `p3b/permanent-host-failures/run-1231220470280440748`. |
| Exclusive package/loader ownership | Object duplicate-class and package-only collisions fail before losing extraction; mixed classes and altered private helpers fail signature preflight. Disjoint worlds and permanent loader anchoring pass: `p3b/permanent-loaders/run-7952143850616138321`. |
| Enums | Actual Java enum order/identity, paired name tokens, exact constant dispatch and protected native initialization. Cold receiver/argument calls, nullable/empty values, initialization failure, copied Strings and permanent-object mixing pass: `p3b/enum-facades/run-15098101126293455454`. |
| Enum host failures | Metadata, token and constant conversion faults preserve cleanup, effect timing and retry: `p3b/enum-host-failures/run-13801104487880174287`. |
| Custom Java snapshots | Checked/unchecked and abstract catch hierarchy, copied primitive bits/UTF-16, non-public data constructors, module access and actual child-heap exhaustion/recovery: `p3b/custom-java/run-12998293082463857044`. D216/D217 document unrepresentable Java ancestry and copied constructor data. |
| Custom native extraction | Exact protected getter calls, one capture per property, owned String release, throwing getters, native budgets and five JNI failure sites at O0/O3: `p3b/custom-native/run-8733042541817429769`. |
| Bounded custom graphs | Inherited path/parse/filesystem/transfer data, shared covariant cause cycles, self/limit fallback, shared secondary nodes and post-failure continuation: `p3b/custom-native-graphs/run-11262729232239988153`. |
| Public packaging | Source O0 and class/archive O3 producer jars match generation/API/program/module identities. Complete content inventory, sources/Javadoc/notices, private helper registration, ordinary launch forms, Java 22/23 use, Java 24 pre-extraction refusal and all-mode pending-capability rejection: `p3b/object-producer/run-9509884079546552524`. |

The final producer selection passes all three focused checks in
`p3b-object-producer-final.log`, including the existing P2 scalar and exception/
exhaustion producers. The documented value compile/link/run smoke path passes
in `p3b-value-example.log`. Source changes pass strict Java/C compilation,
codesign, the license audit and `git diff --check`. No unfiltered suite was run.

## Allocation, machine code and remaining qualification

Permanent 100,000-call scalar and live object-return loops report zero Java bytes
and zero native allocations. O3 scalar and object-hit diagnostic times were
1,513,667 ns and 17,952,292 ns. The typed scalar entry uses four instructions;
the JNI adapter keeps its typed call/status branch and outlined failure with no
cache lookup, JNI field read, TLS, allocation or liveness state on success.
The enum/permanent paired loop likewise reports zero Java/native allocations.
These are component measurements, not the user's final numerical acceptance.

Production and fault-injected payloads have separate identities. The custom
transport's final LLVM SHA-256 is
`55b9d7eb2657c10222cab8ab43a19ba65c8cb97da4b10d65ab6c61af02431860` and its adapter
SHA-256 is `9c0e5df5aadce6f5723c824e3bba0c11938d4523f0ddb93a6bc4cf139da6d24e`.
The producer jars carry exact compiler/runtime, native-input, signed payload and
complete-content hashes in `META-INF/ironwood/bridge.properties`.

P3c must now implement complete root registration, native index/global references,
shared Java state, root/view identity and nonthrowing explicit destruction.
P3d adds proved independent-root retention and the combined safety gate. A static
field cleanup experiment was correctly refused because allocation ownership was
unknown; exception-storage cleanup must instead use a proved root fixture.
No native exception storage is implicitly freed to satisfy a snapshot test.

P4 actual OrderBook workload/allocation acceptance and P6 distribution/ARM64
qualification remain required. Java 21-23 remains the authorized baseline;
D209's Java 25 findings are recorded separately without broadening support.
Real x86-64 hardware qualification remains pending under D213. P5/P7 stay deferred.
