<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 1 verification

Implementation date: 2026-09-14. Scope: the representative numeric blocking TCP
foundation only. The source/contract matrix is in
[STDLIB_N1_SOURCE_REVIEW.md](STDLIB_N1_SOURCE_REVIEW.md). The milestone's focused
gates passed on the host below. This evidence covers Milestone 1 only. Following
its exit review and regression fixes, the maintainer separately selected
Milestone 2 on 2026-09-14 and Milestone 3 on 2026-09-15. Their separate evidence
is linked below. Milestones 4 through 6 remain unselected and N1 remains pending.

| Milestone 1 criterion | Result and evidence |
| --- | --- |
| Contract, acceptance and provenance record | Pass: selected member/ownership matrix, NP1-NP11 audit, pinned source/header review and independent implementation boundary |
| Numeric TCP vertical slice and family policy | Pass: binary IPv4/IPv6, dual-stack wildcard, controlled IPv4-only fallback, Java 21 differential and two-process exchange |
| Typed native boundary and generic options | Pass: captured errors, attempt/wait separation, boolean/int dispatch and source/class/archive links |
| Extension and non-stream ownership | Pass: default/injected/factory graphs, observing overrides, unsafe-free rejection, inventory loans and synthetic bulk/snapshot/cursor proofs |
| Acquisition, partial results and copied exceptions | Pass: native/wrapper failures, primary failure preservation, low descriptor limits, allocation rollback and copied-message probes |
| Deadlines and untimed transitions | Pass: read/accept recovery, timed connect, EINTR/spurious readiness, partial transfers and direct-path restoration |
| Allocation and optimized native-call budget | Pass: exact 9/11-object accepted graphs, zero steady-state allocation, O3 inspection and traced/untraced workload evidence |
| Documentation and packaging metadata | Pass: license audit, host/IDK build and relocated smoke checks, loose/archive review entries |

## Reproduction and evidence locations

Build with `./scripts/build.sh`. Run `python3 scripts/test-networking.py` for the
focused named fixtures, Java differential/interoperability checks, fault
injection, allocation checks, archive links, benchmark and disassembly. The
script never invokes an unfiltered compiler suite. Detailed outputs and
`report.json` are under ignored `integration-tests/target/networking-m1/`.
Compiler selections and packaging logs are under ignored
`workspace/networking-review/`. Checked-in fixtures and assertions preserve
reproducibility; the measurements below summarize this host run.

The diagnostic interposer is macOS-only test code, built with strict C warnings.
It counts receive/send, poll, monotonic clock, descriptor control, options,
availability, allocator calls and transferred bytes within explicit markers.
It tracks TCP descriptors separately from unrelated platform descriptors and
rejects duplicate closure or live descriptors at exit. It does not change the
production TCP path. Untraced timings and diagnostic counts are separate runs.

## Functional and ownership coverage

- Binary IPv4/IPv6 construction, mapped IPv4 normalization, signed-byte hashes,
  numeric rendering, null/invalid input and widening conversions.
- 45 matching Java 21 contract observations, including linger normalization,
  generic/dedicated options, unsupported-token/closed precedence, cached numeric
  addresses through binding/connection/closure, accepted independence, and
  zero-length bulk I/O after shutdown/close with null/range validation.
- Eight two-process configurations: Ironwood/Ironwood, Ironwood/Java and
  Java/Ironwood for IPv4 and IPv6, plus wildcard dual-stack IPv4 and IPv6.
  Exchanges use 257 binary bytes per round, fragmented writes and EOF/half-close.
- Default, injected and factory-created implementation graphs, a custom owning
  delegate and a separate caller-owned native delegate, observing stream
  overrides, buffered wrappers and protected acceptance. Client/server factory
  globals are tested in separate processes for null and repeated registration.
- Negative compilation for independently freed streams/addresses/inventories,
  surviving aliases, retained buffers, published helpers, cached/published
  factory results, retained factory captures, wrong option/value types, boxed
  option calls and UDP constructors. Primitive options pass through actual
  generic override/delegate/native calls without boxing.
- Synthetic distinct fresh bulk results, partial-result cleanup, flat snapshot
  views with parent/child navigation, query and owning-enumeration roots,
  independent fresh cursors through interface forwarding, and fresh mutable
  lists of borrowed entries. Caller insertion remains a loan. These fixtures
  introduce no DNS or interface-discovery API.

## Exact managed allocation ledger

The sequential-server probe warms process metadata, reuses caller buffers and
measures the complete accepted graph. It excludes the separately measured
client/listener setup. Both address families have the same counts.

| Accepted graph component | Default | Custom owning delegate |
| --- | ---: | ---: |
| Socket facade | 1 | 1 |
| NativeSocketImpl | 1 | 1 |
| Opaque descriptor plus primitive endpoint state | 1 | 1 |
| Custom ObservingImpl | 0 | 1 |
| First native input/output views | 2 | 2 |
| First facade input/output views | 2 | 2 |
| Custom observing input view | 0 | 1 |
| First local/remote numeric address objects | 2 | 2 |
| **Total** | **9** | **11** |

The measured phase deltas are default `3,4,2,0,0` and custom `4,5,2,0,0`:
accept, first streams, first addresses, repeated getters/inventory/I/O, and
post-close/free live delta. Each of four family/implementation configurations
runs 64 cycles under a 32-descriptor limit, opening and closing 129 TCP
descriptors. A controlled IPv4-only host simulation runs another 16 cycles,
opening and closing 33 descriptors. These are allocated connections, not
zero-allocation connections. Established I/O and repeated getters allocate zero.

| Explicit result operation | Allocation count | Explanation |
| --- | ---: | --- |
| Endpoint snapshot | 2 | Endpoint plus copied numeric address |
| InetAddress.copy() | 1 | Primitive-bit address value |
| InetAddress.getAddress() | 1 | Fresh byte array |
| InetAddress.getHostAddress() | 3 | StringBuilder, backing array, result String |
| Address.toString() | 4 | Numeric rendering plus final prefixed String |
| Endpoint.toString() | 4 | Three host-rendering allocations plus one concatenated result String |
| Socket.toString(), listener.toString() | 5 each | Endpoint/address rendering plus enclosing description |

Rendering counts include temporary managed objects, not only retained results.
The builders and intermediate Strings are reclaimed. Historical address
transitions use separate lazy immutable caches: observing an unbound or bound
local value before connection can allocate one additional cached address per
observed state, and close can lazily create a facade wildcard value. These
explicit first observations are outside the accepted-connection ledger above;
old borrows are preserved rather than invalidated to save an allocation.

Built-in inventory storage is process metadata: three ArrayLists each own a
backing array and reusable iterator (9 objects), and three read-only views each
own an iterator (6 objects). Six standard tokens, an independently lazy timeout
token and the two default factories add up to 24 objects when all are
initialized. Custom registered factories add their own objects/captures. This
source accounting is not a per-connection or measured startup claim.

## Failure, deadline and native-heap accounting

Ordinary and controlled-error probes cover zero-length reads, short reads,
EINTR, would-block, partial writes, reset and EOF. Would-block never becomes EOF
or a positive-length zero result. Real read/accept timeout leaves the resource
usable. Controlled read/connect/accept attempts alternate interrupted/spurious
readiness with small delays; one absolute 15 ms deadline must finish within a
250 ms scheduling tolerance. Retries do not restart the deadline. No public
write deadline is added.

Thirty-two failures after native acceptance also make cleanup report another
native error. The original copied Connection reset message survives and all
65 descriptors close exactly once. Each cycle retains the two managed objects
required by the existing thrown-exception lifetime policy: SocketException and
its copied String. Native allocator attribution finds 128 retained blocks,
9,216 bytes overall: per exception, 56-byte object, 48-byte String, 56-byte
exception metadata and 128-byte captured trace storage. Temporary PC buffers
and unwind wrappers are reclaimed. This is exception retention, not an
unexplained connection leak.

Each of the four lazy stream-wrapper allocations is failed separately for
32 cycles after descriptor transfer. All 65 descriptors close and the managed
live delta is zero in every run. The only retained native allocation is one
64-byte macOS dynamic TLS block on first exception delivery. Diagnostic stack
attribution identifies libdyld's ThreadLocalVariables::instantiateVariable from
raise_language_exception/raise_allocation_failure. It belongs to existing
exception delivery, not ordinary TCP I/O. Subsequent failures do not grow it.

Copied-message tests reclaim caller text while the caught message stays valid.
Allocation limits 0, 1 and 2 cover exception/message construction. Partial bulk results pass limits 0 through 5, and snapshot/enumeration/list
construction passes limits 0 through 8 with zero completed-owned-storage
residue. The custom inventory additionally fails each of its eight constructor
allocations, then tests success at limit 8; reverse-order rollback restores the
managed live baseline and acquires no native descriptor. Custom close failures
are secondary to the original connect/accept failure under Ironwood's pending
exception/finally contract; no descriptor is retried or implicitly closed by a
destructor.

## Untimed native-call budget

| Operation/window | Receive | Send | Poll | Clock | Descriptor control | Heap allocations |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Ordinary scalar or bulk read | 1 | 0 | 0 | 0 | 0 | 0 |
| Zero-length read | 0 | 0 | 0 | 0 | 0 | 0 |
| Scalar or complete bulk write | 0 | 1 | 0 | 0 | 0 | 0 |
| Read after timed connect/read, timeout reset or recovered timeout | 1 | 0 | 0 | 0 | 0 | 0 |
| Write with positive read timeout | 0 | 1 | 0 | 0 | 0 | 0 |
| Interrupted read | 2 | 0 | 0 | 0 | 0 | 0 |
| Would-block retry | 2 | 0 | 1 | 0 | 0 | 0 |
| Controlled partial write | 0 | 2 | 0 | 0 | 0 | 0 |

Option/availability calls are zero in these I/O windows. Required error retries
are distinguished from successful ordinary operations. The native code passes
the caller's byte-array payload directly to recv/send and uses a single stack
byte for scalar operations. No temporary payload allocation or copy is present.
EOF uses one receive. errno classification occurs only after failure and uses
the captured value returned with that attempt.

## Optimized code, benchmark and distribution checks

Host: macOS 26.6.2 (25G83), arm64, LLVM/Clang 23.1.0. The final contract and
interoperability pass uses OpenJDK 21.0.10 (Zulu 21.48+17); the compiler's
Java-21 bootstrap target is also checked with that JDK. Earlier development
runs used the host's newer JDK. Put Java 21's `bin` first in PATH when running
the contract group; the driver checks its version explicitly.

The linked O3 Mach-O disassembly is saved as Operations.asm plus
Operations-macho-disassembly.log. The latter resolves native stubs correctly;
Operations-native-symbols.log also records the indirect-symbol mapping.
Inspection follows SocketInput.read through NativeInput and SocketDescriptor to
the native receive/send functions. Facade state/dispatch and mandatory buffer
checks remain; no ownership/trace/TLS/heap helper appears on the valid path.
TcpNative/TcpSupport initialization is absent because they require no work.

Representative scalar read instructions in SocketDescriptor are:

```asm
ldr  w8, [x19, #0x14]          // configured timeout
cbz  w8, 0x1000078a0          // skip monotonic deadline setup
bl   _ironwood_nano_time       // timed branch only
...
cbz  w8, 0x1000078c8          // select the ordinary blocking attempt
...
bl   _ironwood_tcp_read_byte
lsr  x0, x0, #32              // captured error
cbz  x0, 0x10000786c          // success returns directly
```

The native scalar function passes one stack byte to recv with flags zero.
After recv it branches to `___error` only on a negative result. The bulk
function passes the existing array payload directly:

```asm
cbz  w3, zero_length
sxtw x8, w3
add  x9, x1, w2, sxtw
add  x1, x9, #0x20            // caller array payload plus offset
mov  x2, x8
mov  w3, #0
bl   _recv                   // resolved Mach-O symbol stub
```

The write functions likewise call send with caller storage; partial-write
retries advance only by the returned progress. Scalar/bulk paths contain no
payload copying loop, malloc/calloc, poll, clock or fcntl on ordinary success.
The diagnostic transition tests above independently establish which branches
execute, including after timed operations.

The deterministic loopback benchmark performs 512 echo rounds of 257 bytes:
131,584 bytes in each direction per process. Timing calls bracket the loop,
reporting is outside it, buffers are reused, and managed allocation delta is
zero. This is a repeatable workload, not a claim of deterministic elapsed time.

| Untraced case | Server elapsed ns | Client elapsed ns |
| --- | ---: | ---: |
| Untimed blocking | 21,459,000 | 21,499,000 |
| 1,000 ms read deadline | 9,863,000 | 9,876,000 |

Separate diagnostic untimed runs record 512 recv and 512 send per peer, zero
poll/clock/control/options/availability/allocator calls. Timed runs record
1,023 recv, 511 poll and 1,023 clock calls at the server, and 1,024 recv,
512 poll and 1,024 clock calls at the client; both have 512 sends and zero
control/options/availability/allocator calls. Timing noise and scheduling make
these samples unsuitable for concluding that deadlines improve throughput.

The Java 21 focused run passed all 15 selected compiler tests. Selections cover
TCP ownership/options and O3 programs,
owned/retaining delegates, snapshot/cursor proofs, fresh bulk mutation,
closed-world constructor/destructor effects, data-structure release/failed
insertion loans, U3 streaming effects/native cleanup, and static-initialization
IR, execution and archive reconstruction. No unfiltered suite is run.
A packaging-discovered IntSet.add regression was
fixed by preserving the existing D107 map receiver contract through bound
reference calls. Five affected ownership/collection selections then passed,
including a new retaining-map override rejection and the custom inventory.

Both smoke scripts also respect D150: package-info.iron contains package
documentation and intentionally has no loose or archived class artifact.
The previous assertion requiring that nonexistent class was corrected.

The host and IDK smoke scripts check the networking plan, source review and
this record as loose documents and META-INF/LICENSES entries, then compile and
run a numeric TCP bind/close/free program from each extracted distribution.
The host test relocates the package to a path containing spaces; IDK tests clear
Java/LLVM environment overrides and use bundled tools. The IDK's existing Linux
GLIBC 2.17 audit also includes the new TCP executable when run on Linux; this
host run makes no Linux validation claim.

| Focused command | Result |
| --- | --- |
| Java 21 `./scripts/test.sh` with 15 exact selections | Pass; `workspace/networking-review/java21-focused.log` |
| Five affected selections after the map-effect correction | Pass; `workspace/networking-review/collections-focused.log` |
| `python3 scripts/test-networking.py` named groups | Pass; `report.json` and per-fixture logs, with final affected groups in `workspace/networking-review/zero-length-native.log` |
| `./scripts/check-licenses.sh` | Pass; three existing derived files, no derived networking import |
| `./scripts/package.sh` and `./scripts/test-package.sh dist/ironwood-macos-arm64.tar.gz` | Pass; host package build/smoke logs in `workspace/networking-review/` |
| `./scripts/package-idk.sh 0.4.2-beta` and `./scripts/test-idk.sh dist/ironwood-idk-0.4.2-beta-macos-arm64.tar.gz` | Pass with the prepared pinned arm64 toolchain; IDK build/smoke logs in `workspace/networking-review/` |
| `git diff --check`, changed-file text and relative documentation links | Pass |

## Compiler regression follow-up, 2026-09-14

The reported `System.arraycopy` rejection failure reproduced locally. Its
intrinsic destination effect was lost when symbolic non-return summaries
replaced the earlier escape summary. The correction preserves that effect
through forwarding helpers, while retaining primitive-buffer borrowing and
private backing-array detachment. New positive and negative cases run inside
the existing arraycopy test; no runtime bookkeeping is added.

The reported StringBuilder mismatch exactly matches four Java 21 versus Java 25
observations. The [StringBuilder review](STDLIB_STRINGBUILDER_REVIEW.md#verification-and-lessons)
records the reproduction and explicit Java 21 expectations. Growth and throwing
callbacks retain native coverage, alongside live Java comparisons for the
remaining everyday operations.

Seven exact selections passed on macOS ARM64 (across focused runs), Linux ARM64,
and Linux x86-64 under Rosetta: the two TCP facade/extension ownership tests,
arraycopy destination rejection, fresh bulk-result mutation, private backing
array detachment, and StringBuilder behavior and allocation ownership. The
updated StringBuilder selection also passed with the macOS Java 25 runtime.
Linux used pinned OpenJDK 21.0.10 and LLVM 23. No unfiltered suite ran.

The Linux logs are `workspace/platform-tests/linux-arm64-20260914-185356.log`
and `workspace/platform-tests/linux-x86_64-20260914-185457.log`. Mac logs are
`workspace/networking-review/arraycopy-fix-macos.log`,
`arraycopy-final-macos.log`, `compiler-followup-java21-macos.log`, and
`stringbuilder-final-java25.log` in that same review directory. These checks
cover the reported regressions; they do not extend the original macOS-only
native interposer or packaging evidence to Linux.

## Primitive-array result correction, 2026-09-14

The preceding arraycopy correction missed primitive element types in symbolic
analysis of local declarations, allocations and casts. Parameters and fields
already retained those types. That gap falsely classified fresh primitive-array
copies as published results, causing the five reported util, IO/NIO and U3
compilation failures. All five failures reproduced on macOS before correction.
Symbolic analysis now uses the existing semantic primitive-type mapping for
these source expressions. The destination restriction and actual publication
effects remain enforced, with no runtime changes or diagnostic suppression.

Nine exact selections passed on macOS ARM64, Linux ARM64 and Linux x86-64 under
Rosetta: the five reported failures, the new primitive-array factory test, and
the existing arraycopy rejection, bulk-result mutation and backing-array
detachment tests. The new test accepts copies of all eight primitive array
kinds and rejects reference-array, multidimensional-array and published-result
reclamation. Native stream checks assert allocation counts and cleanup; the U3
OOM test verifies live-allocation recovery across 26 allocation budgets.

The macOS log is `workspace/networking-review/primitive-array-results-macos.log`;
the refined negative regression also passed in
`workspace/networking-review/primitive-array-results-regression-macos.log`.
Linux logs are `workspace/platform-tests/linux-arm64-20260914-193026.log` and
`workspace/platform-tests/linux-x86_64-20260914-193118.log`. These were focused
Java 21/LLVM 23 checks, with no unfiltered compiler or platform suite.

## Limitations and selection checkpoint

The original milestone measurements remain macOS-only; the follow-up above adds
focused Linux regression coverage. Neither claims a release readiness/full-suite
pass or exhaustive future socket API compatibility. Native counters/faults use
macOS interposition. Blocking calls stall the single application thread.
Thrown exception/trace retention follows the existing language policy and is
accounted separately from reclaimable connection storage. Array/list/cursor
proofs remain conservative for shapes outside the validated contracts.

The separately selected [Milestone 2](NETWORKING_M2_VERIFICATION.md) now
implements blocking sockets, literal/scoped addresses and DNS;
[Milestone 3](NETWORKING_M3_VERIFICATION.md) adds host-network queries and
reachability; 4 would add explicit proxies;
5 would add scoped TLS and optional packaging; 6 would add the downloader.
Milestones 2 and 3 were separately selected after this exit review; Milestones
4 through 6 remain unselected. N1 additionally requires a separately selected
non-blocking/event-loop acceptance program after this migration.
