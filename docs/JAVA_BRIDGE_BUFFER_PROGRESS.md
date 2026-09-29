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
Design/source review completed. Implementation has not yet enabled view exports.
The typed descriptor/intrinsics and exact bundled-source identity are implemented.
The focused byte-view proof test passed on macOS ARM64 with pinned Java 21 and
LLVM 23, covering borrowing, unsafe-use rejection, stale/counterfeit facts and
source/class/archive parity. Evidence: `workspace/java-bridge/byteview-proof.log`.
Production transport, packaging and runtime/performance qualification remain.
