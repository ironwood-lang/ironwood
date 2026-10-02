<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7c1 bounded byte-view implementation log

P7c0 is accepted under D234; the maintainer authorized P7c1 after the documented
shared-dependency gate. Work stays on local `java-bridge` in the canonical
checkout, starting at `dce65587`. P7d-P7f are outside this task. Preserve Java
21-23, the Java 24+ refusal and all P0 lifetime proofs.

## Pre-change review and verification selection

Affected contracts: a dedicated bounded representation must never be treated as
an ordinary object/array header. Typed intrinsic operations, semantic borrowing,
non-retention/non-reclamation, exact declaration identities, source/class/archive
reconstruction, JNI lifetime, shared-class binding and distribution must agree.
Keep unknown effects conservative. No per-call allocation/copy or lifetime
bookkeeping; bounds/permission semantics remain mandatory. Aliased backing
regions must not acquire false `noalias` facts. Scalar/array paths stay unchanged.

Implementation checkpoints: (1) trusted library identity and typed operations,
(2) full confinement proof and protected transport, (3) shared Java support and
producer/distribution wiring, (4) composition, failures, platform qualification,
allocation measurements and Linux byte-array/view benchmarks.

Paired cases include null versus access; valid versus overflowing ranges;
read-only conditional no-write versus actual write; same-view identity versus
distinct overlapping slices; helper borrowing versus fields/statics/returns,
casts, unknown effects and free; valid shared dependency versus counterfeit or
missing support. Test source/class/archive parity and optimized alias ordering.

Focused new checks will cover byte-view typed operations/proofs, host API,
producer parity, GC-pressure lifetime/failures and shared dependency packaging.
Adjacent selection: the six P7b array proof/producer/fault checks, native
ByteBuffer safe-free coverage, protected exception delivery and scalar adapter
checks when their shared paths change. Revisit this selection when scope changes.
No unfiltered compiler suites. Use child processes for lifetime/failure risks.

## Current checkpoint

Canonical root, exact origin URLs, `java-bridge` and clean baseline verified.
Design/source review completed. Foundation committed as `d32132db`.
The typed descriptor/intrinsics and exact bundled-source identity are implemented.
The focused byte-view proof test passed on macOS ARM64 with pinned Java 21 and
LLVM 23, covering borrowing, unsafe-use rejection, stale/counterfeit facts and
source/class/archive parity. Evidence: `workspace/java-bridge/byteview-proof.log`.
Protected transport and view admission now pass the focused producer test on
macOS ARM64 at O0/O3 across source/class/archive inputs. Evidence:
`workspace/java-bridge/byteview-producer.log`, retained artifacts under
`workspace/java-bridge/byteviews/producer/run-9487848804573499463`. Positive and
negative bounds/permissions, null/empty, identity, overlapping writes, immediate
writes after exception, String/array/root-receiver composition, zero warmed
Ironwood allocation, GC pressure and Java direct-memory OOM children passed.
Shared dependency binding, exact-byte rejection before payload extraction,
independent artifact classpath/module-path composition, CLI companion generation
and Maven dependency/source/license packaging passed the focused packaging test
(`byteview-packaging-retry.log`). The example compile/link/run and five-scenario
benchmark smoke passed. The 11 selected Mac adjacent regressions passed
(`byteview-regressions.log`), followed by unsafe source/class/archive admission
and native OOM-after-write checks (`byteview-extra-failures.log`). License audit
and whitespace checks passed.

Linux qualification runs from input archive `byteviews/input-1.tar.gz`, SHA-256
`21f2579ec672a589a55ea139283d7770b257c5c0dafeaf989101af79cca65571`, with
per-file identities in `validation/contents.sha256`. Local ARM64's three new
checks and 22 Java 22/23 replays passed. Estonia uses the existing image/SDK and
`~/temp/java-bridge/p7c1-20260928-1`, with timing children on isolated CPU 1.
Both timing runs are pending; the input predates only the additional negative
artifact and native-OOM tests, which will receive follow-up platform validation.

Assembly review found repeated immutable-descriptor reloads after byte writes.
Next: preserve descriptor immutability in lowering, without any payload noalias
claim; inspect optimized loops and repeat relevant qualification on final bytes.
Finish matched Linux measurements, IDK packaging smoke, assembly, documentation
and evidence before claiming P7c1 complete.

## Optimized descriptor checkpoint

Transport/shared dependency/example checkpoint committed as `27d03709`.
Both initial Linux qualifications passed three focused tests, 22 Java 22/23
replays and every benchmark checksum/allocation assertion. Original data remains
in `byteviews/linux-arm64-1/work/evidence` and `byteviews/estonia-1/evidence`.
The first immutable-load experiment lost metadata during LLVM argument promotion.
Typed view accessors now expand before that pass; descriptor loads are invariant
for their invocation lifetime. Payload loads/stores remain ordinary aliasing
memory operations, with no disjointness claim. The O0/O3 producer test and two
focused inlining/trace tests passed (`byteview-inline.log`), including read-only
empty loops and completed writes before read-only/native-allocation failures.
The final optimized payloads still require Linux timing and final qualification.

The optimized Mac benchmark smoke (`byteviews/mac-smoke-3`) has matching checksums
and vectorized write loops with permission/length outside the loop. Additional
admission checks reject view fields even on entries without a view parameter.
Descriptor offsets are compile-time C assertions. Wide signatures reserve JNI
local capacity only above seven view inputs; a 32-view child passed checked JNI
in all producer input forms (`byteview-wide.log`). The final three-test Mac
qualification passed (`byteview-final-mac.log`), followed by the wide-signature
addition's focused producer rerun. Compiler sources are now ready for final Linux
and installed-IDK packaging validation; no final performance claim yet.

## Final artifact qualification

Production/compiler implementation is frozen at `46e70578`. The final input
archive `byteviews/input-final.tar.gz` has SHA-256
`103981a8c1580383d6d8681f5f479d0548bc8cd4b9905a1c285409d74ddfad83`;
it includes the installed-IDK smoke addition but predates only a supplementary
mixed-argument allocation-failure assertion. All final Linux builds verify the
per-file input manifest. Both Linux hosts passed five selected tests, 28 Java
22/23 replays and every full benchmark checksum/allocation assertion. Mac final
producer/packaging and 28 Java 22/23 replays passed; Java 24 was refused at that
revision (D245 later admits it).
The supplementary mixed-failure check passed nine children on each target using
the exact final source/class/archive producer jars. Its JSON records jar/source
identities and commands separately from the frozen input archive.

The final `0.5.5-beta` macOS ARM64 IDK passed its packaging smoke, including the
installed shared jar and byte-identical regeneration by the relocated compiler.
The IDK uses its existing bundled Zulu Java 21.0.10+7; producer/timing runs use
Temurin Java 21.0.12.1+1. No toolchain was installed. Final raw Linux evidence is
under `byteviews/linux-arm64-final/work/evidence` and is being collected from
`~/temp/java-bridge/p7c1-20260928-final/work` on Estonia. Next: assembled-jar
payload preservation/replays, distribution checks, final evidence/status docs,
license/whitespace checks and local commit. Do not advance to P7d.

## Completion checkpoint

P7c1 implementation and qualification are complete within D234. The
[evidence report](JAVA_BRIDGE_BUFFER_EVIDENCE.md) records the final five-scenario
Linux tables, exact artifacts, raw locations, machine-code review, remaining
overlap gap and reproduction commands. Numerical acceptance remains maintainer
review; P7d-P7f are untouched.

The assembled three-target jar preserves every native entry and shared support
byte. It passed 18 checked-JNI consumers across Java 21-23 and classpath/module
path. Distribution payloads, dependency POM, sources/licenses and every inventory
hash passed verification. Nine additional Mac reverse-overlap children passed on
the frozen O0/O3 producer jars, supplementing the forward-dependent Linux checks.
These two supplemental test additions do not change production artifacts.
Final license audit and `git diff --check` passed. Final qualification changes
are committed separately from production as tests, IDK smoke and documentation.

Estonia final evidence was copied back, archive inventories and payload hashes
verified, and all workers stopped before cleaning only this task's two remote
`work` trees and assembled copy (about 3.4 GiB). Inputs and an evidence-location
note remain. Preexisting SDKs/JDKs/images and unrelated files were preserved.
No further implementation is pending within P7c1. Next phase requires the
maintainer's separate instruction; do not automatically start P7d.
