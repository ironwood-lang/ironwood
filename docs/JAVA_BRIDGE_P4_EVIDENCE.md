<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P4 OrderBook implementation gate

The unchanged actual OrderBook engine exports through the macOS ARM64 producer.
P3's exact final non-reclamation proof covers OrderBook, Order, internal PriceLevel
and array storage. No pool destructor, ownership origin, project exemption or
handwritten public bridge API was added. P4 permits P6a under D213; it does not
complete P6b, numerical performance acceptance or release readiness.

## Consumer and artifact selection

`BridgeOrderBookProducerTests` compiles only the engine entry source and resolved
dependencies into a dedicated class directory. Its exact inventory is OrderBook,
Order, PriceLevel and the two nested enums, excluding Main/Bench/LatencyBench.
Generated public classes are OrderBook, Order and its enums, plus the private
package reservation marker. Class O0 and archive O3 builds retain equal
generation/API/program identities and complete content-hash pairing.

The same Java consumer compiles independently against the generated jar and the
paired Java engine. Plain and checked-JNI children verify price/FIFO ordering,
partial fills, reductions, cancellations, fully matched limit returns, pool
recovery and enum values. The 10,000-cycle reference workload reports 30,000
matches, 2,500,000 volume, final price 10,300,000,000 and maker ID 59,998. Order and
price-level exhaustion remain exact Java IllegalStateException producer failures,
not bridge lifetime refusals. The source's lack of rollback after level exhaustion
is preserved and recorded. No released order is used as an active business handle.

## Allocation evidence

`BridgeOrderBookAllocationTests` uses unchanged source, final admission and production
generators. Separately identified Java/C experiment copies count facade allocation,
weak Entry allocation and table growth; a native accessor reads the runtime count.
Counters do not enter producer artifacts or add scalar-path instrumentation.
Each O0/O3 child runs checked JNI with escape analysis disabled. All eight pool
identities are exercised and strongly held before measurements. A separate
production-jar check also measures zero Java bytes for the warmed paired workload
and held-facade scalar operations, with both plain and diagnostic launches.

| Measured case | Result |
| --- | --- |
| 100,000 scalar iterations | Native count unchanged at 26; zero Java bytes, zero facades and zero cache entries. |
| 100,000 workload cycles | Native count unchanged at 26; zero Java bytes, zero facades and zero cache entries; every return matches one of eight held identities. |
| First conversion and seven recreations | Native count unchanged at 42; exactly one facade and one weak Entry per miss; 64 Java bytes per miss on this pinned JVM. |
| Hits after each miss | 10,000 create/cancel iterations preserve facade identity with zero native/Java allocation and no cache growth. |
| Collection and maintenance | Eight bounded runs observe both weak clearing and queue delivery. Maintained entry count is 12, below the 14 distinct exercised identities, with no incarnation accumulation. |

Raw before/after counters are retained in each `consumer.log`. Setup includes class
initialization, pool construction, cache capacity growth, counters and warmup.
An initial experiment measured 208 bytes on the first miss: 64 bytes for the two
objects plus 144 bytes for the bucket expansion. The final fixture performs that
real growth during setup and asserts no further growth. This is not a change to
cache policy or an unexplained positive allocation classified as a pass. Object
sizes are observations of the pinned JVM, not a cross-JVM layout promise.

## Machine code and provenance

O3 review of the public producer's signed payload finds that `getOpenSize` uses
the existing 320-byte protected adapter frame, calls typed entry 10 and branches
to translation only on failure. The typed entry is five instructions: load the
two size fields, subtract, store the result, return success. It has no identity
lookup, liveness state, root index, TLS bookkeeping or allocation. `reduceTo` uses
typed entry 17 without a lifetime preflight or identity-cache call. No lowering
or runtime change was required for P4.

Evidence paths are relative to ignored `workspace/java-bridge/evidence/`:

- Public producer: `p4/producer/run-367321048810317948`, including paired output
  and extracted production O3 disassembly. Signed native SHA-256:
  `421d607b46a39bd865972339bbe2a5561214cc7b7f14e99760839a2382e4138c`.
- Allocation copy: `p4/allocations/run-13847449280766738305`, including raw counts,
  source/adapter overrides, signed payload/jar hashes and O0/O3 disassembly.
- Generation: `6d730c384d3f12a58c410fe1d9627629b87820b4f80e49d3e73296b4ac7583f3`.
- Final LLVM: `53a869fd7969d5409d340c3ffb3b49ba884084ce4c096a36fabde872ed9d3af6`.
- Production adapters: `c2e238ee8264f32663120ca4d1fb3c3877b65945b404e389bab3b682a1dca55b`.
- Compiler: `cf25c2165f7c01ecf60a5b5a429c65afd82ccd1605ffe96e457fc5f7af1c133b`.
- Runtime: `eff7bd01439b5f44bf5b762b4a545ad92d7b145385c0710e433b4afd24d68ef4`.

The final focused primary run, `experiments/p4-orderbook-final.log`, passes both
selectors. Its refreshed producer evidence is
`p4/producer/run-10065716477595806203` (including escape-analysis-disabled
diagnostic launches); allocation evidence is
`p4/allocations/run-12654030600391917350`. No unfiltered suite was run.

These results use the pinned Temurin 21 macOS ARM64 JVM and LLVM 23 on Apple M5.
P6 must qualify matching final distribution payloads across the supported targets
and JDKs. Real x86-64 hardware remains pending; this audit supplies no translated
or hardware x86-64 performance claim. Numerical timing acceptance is reserved for
the user's review after P6 measurements. P5 callbacks and P7 extensions remain deferred.
