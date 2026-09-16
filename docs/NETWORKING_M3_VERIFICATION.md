<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 3 verification

Status: Milestone 3 complete at its selection checkpoint, 2026-09-15, following
separate maintainer selection. Focused platform and distribution verification
passed, with the existing SDK compatibility limitation below. The
[migration plan](NETWORKING_MIGRATION_PLAN.md) and
[contract review](STDLIB_N1_SOURCE_REVIEW.md) define the scope. Milestones 4
through 6 and the later N1 event-loop phase remain unselected.

| Passed criterion | Evidence |
| --- | --- |
| Interface discovery, structural snapshots and live metadata | Controlled names/indices, parent/child navigation, address/prefix/broadcast values, hardware snapshots, empty/missing results and changing flags |
| Scoped IPv6 factory, lookup and copied results | Independent copied interface graphs; named scopes; missing or incompatible scope failures; borrowed scoped-interface getter |
| Both reachability overloads | 108 deterministic native scenarios on the shipped adapter, plus public argument and error tests; no live boolean acceptance gate |
| Ownership, generic bounds and cleanup | Source, class-directory and archive inputs; safe and rejected borrows; managed failure sweep; native allocation and descriptor balances |
| Allocation and native performance | Exact-size result lists, allocation-free metadata/probe paths, native-call counts, deterministic workloads and optimized-code inspection |
| Documentation, examples, provenance and packaging | Original host implementation, separate derived parser boundary, read-only example and opt-in probe; distribution checks recorded below |

## Reproduction and platform scope

Use Java 21 and LLVM 23.1.0. Run exact compiler selections from
[LOCAL_TESTING.md](LOCAL_TESTING.md#networking-milestone-3), or build and run the
focused native/public driver:

```sh
./scripts/build.sh
python3 scripts/test-networking-m3.py
```

The driver writes commands, generated Java, LLVM, disassembly and `report.json`
to `integration-tests/target/networking-m3/`. It runs the actual original C
adapter against test-only syscall/clock replacements and links public Ironwood
callers against a test-only dyld/LD_PRELOAD interposer. Test dispatch does not
enter production code. It creates no real ICMP traffic or TCP port-7 connection.

The 28 selected ownership, array, delegation, generic and networking tests pass
across focused runs on macOS 26.6.2 ARM64, Linux ARM64 and Linux x86-64.
Linux runs in local Colima containers on kernel 6.8.0-117 with runtime glibc
2.39 and the pinned glibc 2.17 build sysroot; x86-64 uses Rosetta. These are local
VM/translation results, not native Linux hardware benchmarks. All three targets
pass the final M3 driver. Linux symbol-version inspection of HostProbe and
HostControlled finds no GLIBC requirement newer than 2.17.

The initial Linux ARM64 run passed 22 of 28 selections; six native tests could
not build because glibc 2.17 requires the legacy BSD feature declarations for
interface flags and `ifreq`. The host translation unit and its native fixture
now enable both legacy and current declarations. Focused retries verify the
fix; no sysroot upgrade or application ABI change was needed. Later focused
runs include the exact-capacity list proof and independent-cursor regression.
No unfiltered compiler suite or hosted build was run.

## Behavioral and ownership evidence

- Three synthetic interfaces exercise sorted lookup, absent names, index zero,
  unknown native index, parent/subinterface navigation, IPv4 and IPv6 addresses,
  prefixes and broadcasts. Structural data remains captured while flags, MTU
  and hardware access query the native interface again.
- Lookup owners and owning enumerations reclaim flat graphs. Independent
  cursors start at the first element; `asIterator()` advances its enumeration.
  Cursor/list cleanup leaves retained entries usable under the original root.
  Exhaustion and iterator removal preserve their exception contracts.
- Mutable result lists support clear and reinsertion. A caller-added entry from
  a second query keeps that separate owner alive until the list is reclaimed.
  Ordinary D107 restrictions still apply to exposed or mixed-root containers;
  this milestone does not assume arbitrary collection reads are non-retaining.
- IPv6 factories copy caller bytes, hostnames and interface structure. Copies
  and named literals retain their scoped-interface graph after inputs are freed.
  Nested address-to-interface navigation stays within the same flat graph and
  never exposes its private storage owner. Missing/no-IPv6 scopes are rejected.
- Negative compilation checks reject freeing a borrowed entry/name/parent or
  scoped-interface view, freeing roots before cursors/lists, using entries after
  the root, publication, hidden-owner access, duplicate/borrowed creation-array
  entries, and effectful factory count/index expressions. Altering the actual
  InterfaceAddress constructor to publish itself or its storage invalidates the
  ownership proof.
- Reference-bounded Enumeration and its private generic adapter accept reference
  arguments and reject primitive arguments at caller sites through source,
  class-directory and archive inputs. Existing boolean/int socket options and
  actual custom/factory delegation retain primitive specialization.
- Ten public argument-result lines agree with Java 21, including nulls, index
  zero, negative index/TTL/timeout, default interface and numeric IPv6 scope.
  These checks require no interface lookup or probe after validation. The
  existing InetAddress preferred-family initializer creates one setup socket.

The 108 native scenarios cover both IP families: matching and rejected echo
replies (address, scope, type, code, identifier/nonce, length and IPv4 checksum),
raw-socket permission/protocol fallback, immediate and asynchronous TCP refusal
as true, successful connection, unreachable/timeout/error results, zero timeout,
TTL/source binding, nonblocking/CLOEXEC setup, EINTR and would-block retries,
clock/wait failures, and exactly-once descriptor cleanup. One monotonic deadline
covers ICMP and fallback. No heap allocation is needed by the probe adapter.

The interface native fixture checks failed index allocation after OS capture,
empty/error capture, bounds and matching release. A 20-byte hardware address
regression covers the extended glibc getifaddrs storage; a too-small destination
reports an error before copying. Hardware results are not limited to Ethernet
length. The glibc 2.17 ABI review is recorded in the source review.

## Allocation and native-call ledger

| Operation | Measured or source-audited cost |
| --- | --- |
| Repeated `isUp()` and `getMTU()` | Zero managed allocations; one native status query each |
| Public reachability calls after address construction | Zero managed allocations on successful/refused/false paths; bounded native stack state |
| Fresh address or child cursor; `Enumeration.asIterator()` | One managed cursor per call, retaining only a borrow |
| Two-entry `getInterfaceAddresses()` | Three managed allocations: list, exact backing array and its ordinary reusable iterator; no backing-array growth during population |
| Hardware snapshot | One temporary managed byte buffer and the exact fresh result array when non-null; native OS snapshot released before return |
| Structural native capture | One owned getifaddrs result plus one index allocation; in-place heapsort uses no heap scratch |
| Copied scope metadata | A fresh independent flat graph, including required names/address values; no shared owner registry or reference count |
| Successful query/copy/cleanup workload and every injected managed failure | Zero live-allocation delta |

The complete ownership workload allocates 392 managed objects. All 393 limits
from 0 through 392 return to the prior live-allocation baseline on all three
targets. It includes lookup/enumeration roots, list/cursor mutation and cleanup,
independent cursors, caller insertion from a second query, hardware copies,
named scopes and deep scope-copy construction. The successful controlled run
makes ten OS queries with ten matching releases. Native allocation failure and
ordinary exception paths are checked separately.

Native capture/index ownership, descriptors and language exception allocation
are counted separately. Explicit native ENOMEM creates an escaping ordinary
OutOfMemoryError; the test allows that one error allocation while requiring all
partial query storage to be reclaimed. Managed allocation exhaustion uses the
runtime's existing emergency error and returns to the prior live baseline.
Other caught exceptions retain the existing language exception lifetime.
No exception-lifetime exemption or silent reclamation was added.

Each deterministic status benchmark executes 1,000 pairs of `isUp()`/`getMTU()`:
2,000 ioctl operations and 2,000 corresponding setup sockets/closes, plus one
existing InetAddress preferred-family initialization socket. Each interface
capture benchmark performs 10,000 captures/releases: 10,000 index allocations
and zero extra sorting allocations. The OS's internal allocator behavior is
not represented as an Ironwood zero-allocation claim.

| Target | 1,000 status pairs (ns) | 10,000 native captures (ns) |
| --- | ---: | ---: |
| macos-arm64 | 7,903,000 | 10,806,000 |
| linux-arm64 | 2,938,122 | 1,844,815 |
| linux-x86_64 | 3,075,826 | 2,669,404 |

These scheduling-sensitive times describe the stated workloads and environments;
counts and cleanup invariants are the acceptance gates, not timing thresholds.

## Optimized code and preserved TCP behavior

Inspected the final O3 HostProbe/HostControlled disassemblies and the separate
host adapter assembly, plus the Linux ARM64/x86-64 status benchmark loops.
Successful status calls return through a shift/test of captured error bits;
error-kind translation and exception construction are on failure branches.
The benchmark loop contains the two required native queries and integer work,
without managed allocation, trace maintenance, owner tracking or repeated
initialization. Native status success uses socket/ioctl/close and no errno
lookup. The probe uses bounded stack buffers, direct syscalls and deadline
helpers, with no malloc/calloc/realloc or ownership registry. Cursor traversal
keeps primitive position updates and ordinary source bounds/null checks;
allocating cursors and copied graphs is outside those traversal paths.

The focused M1 `prepare metrics operations benchmark disassembly` groups pass
on macOS ARM64 after M3. Default/custom IPv4/IPv6 connection ledgers remain
unchanged, including nine/eleven managed allocations for accepted numeric
graphs. Each mode completes 64 cleanup cycles and 131 descriptor creations
under a descriptor limit of 32. Ordinary scalar/bulk reads and writes each
perform one recv/send, with zero managed/native allocation, poll, clock,
descriptor-control or option calls. EINTR, would-block, partial progress,
deadline, timeout recovery and cleanup checks also pass.

The 512-round, 131,584-byte-per-direction untimed transfer records zero managed
allocations; traced peers each make 512 recv and 512 send calls with no poll,
clock or setup work. The untraced client/server loops take 19,303,000/19,262,000
ns. With a 1,000 ms read timeout, both loops still allocate zero; traced peers
each make 1,024 recv, 512 send, 512 poll and 1,024 clock calls. Untraced loops
take 12,368,000/12,353,000 ns. These separate runs are count/behavior evidence,
not a relative-speed claim. Reinspection of the O3 Operations binary confirms
direct untimed recv/send and errno access only on native failure, with no new
host-networking or ownership bookkeeping on the TCP path. Results and machine
code are in `integration-tests/target/networking-m1/`; only the named groups
were rerun, not every historical entry retained in its report.

## Distribution and provenance

The host package and IDK include `ironwood_host.c`, host-networking example
source/scripts, the M3 review/verification record loose and in standard-library
archive metadata, and the existing literal-parser source/notices. Relocated
smoke checks compile and run interface enumeration and cleanup alongside the
TCP fixture, with no live reachability. Both `scripts/package.sh` and
`scripts/package-idk.sh 0.4.2-beta` pass. Their relocated `test-package.sh` and
`test-idk.sh` smoke checks pass at O0 through O3, including the new interface
enumeration and cleanup. The default host-networking example also passes its
strict `--unfreed=error` compile/link/run workflow. No Linux distribution archives
were rebuilt; Linux native execution and the glibc baseline were verified above.

Commands select existing Command Line Tools with `DEVELOPER_DIR`. The IDK smoke
uses its bundled Java/LLVM tools and the installed macOS 26.5 SDK through
`SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk`. This preserves
the M2 workaround for the bundled linker's unsupported SDK 27 `arm64e.x1` text
stub. SDK 27 compatibility remains unverified; no global Xcode setting or license
acceptance was changed. See [M2's packaging limitation](NETWORKING_M2_VERIFICATION.md#compiler-example-and-distribution-checks).

`git diff --check`, changed-script syntax checks, local documentation links and
the license audit pass. The audit still reports four OpenJDK-derived files.

NetworkInterface, InterfaceAddress, Enumeration, private snapshot/cursor helpers,
typed operations, native interface/ICMP/TCP code and tests are original or
independent compatible work under `MIT OR Apache-2.0`. The previously introduced
private IP literal parser retains its pinned OpenJDK header, GPLv2/Classpath
classification, source and notices. No new derived implementation or external
native dependency is introduced.

## Evidence locations

- `workspace/networking-m3/selected-tests.json` records the 28 exact selections;
  `platforms.log`, `platform-retry.log` and `platform-final.log` preserve the
  initial diagnostics, focused fixes and passing platform runs.
- `workspace/networking-m3/macos-arm64/`, `linux-arm64/` and `linux-x86_64/`
  retain each target's M3 report, generated code, deterministic native/public
  logs and allocation-failure results. The driver normally writes these under
  `integration-tests/target/networking-m3/`.
- `workspace/networking-m3/tcp-regressions.log` records the preserved M1 groups;
  their report and disassembly remain in `integration-tests/target/networking-m1/`.
- `workspace/networking-m3/package.log`, `package-smoke.log`, `idk-package.log`
  and `idk-smoke.log` record the distribution builds and relocation checks.
- `workspace/networking-m3/example-compile.log`, `example-link.log` and
  `example-run.log` record the strict compile/link/read-only example run;
  `licenses.log` records the provenance audit.

## Limitations and checkpoint

Live ICMP and live port-7 behavior were not run. They remain separate opt-in host
smoke checks under D156, with environment/privilege information recorded by the
operator and no required boolean. The example's default mode only reads local
interface metadata; `examples/hostnetworking/probe.sh` opts into a live probe.
There is no claim of privileged ICMP verification on real hardware. Deterministic
fixtures cover its implementation without changing privileges or network settings.

Interface snapshots capture structure at query time; live status queries can
subsequently fail if an interface disappears. Scope copies have independent
lifetimes and do not track later structural changes. Arbitrary unknown or
published borrowing graphs remain conservative compile-time rejections.
Blocking reachability is not event-loop integration and must not be used as a
prerequisite for connecting to an application service.

Stop at the Milestone 3 selection checkpoint. Proxies, TLS, the downloader and
event-loop work require separate selection.
