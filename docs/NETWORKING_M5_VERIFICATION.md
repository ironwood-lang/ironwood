<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 5 verification

M5 was separately selected on 2026-09-16 and completed on 2026-09-17.
Implementation, focused verification and distribution checks are complete.
M6 and N1's event-loop phase remain unselected. Work stays local on
`socket-tcp-support`, without a push.

## Implemented contract

The original `ironwood.net.tls.TlsClient` owns a native transport, copied
proxy/CA-path configuration, native TLS state and lazy borrowed stream views.
It supports direct connections and the M4 SOCKS4, authenticated SOCKS5 and
HTTP Basic CONNECT routes. It verifies TLS 1.2/1.3 chains, validity, server
purpose and DNS/IP identities, with DNS SNI and no numeric SNI. A custom PEM
replaces bundled roots; invalid input fails before connecting. See the
[API and ownership guide](TLS.md), [contract review](STDLIB_N1_SOURCE_REVIEW.md#milestone-5-implementation-review)
and D165 for deadline, close and failure behavior.

Typed TLS operations select an immutable native dependency requirement after
closed-world pruning. Only retained TLS operations select the isolated C
adapter, CA data and static OpenSSL archives. The public API exposes neither
native handles nor OpenSSL types. Existing IP/SOCKS helper provenance remains
separate. No ownership-analysis exemptions or runtime ownership bookkeeping
were introduced.

## Focused verification

These exact compiler selections pass on macOS ARM64, Linux ARM64 and Linux
x86-64. Linux runs used the local Colima VZ VM; x86-64 ran through Rosetta.
There were no hosted builds or unfiltered compiler runs.

```sh
./scripts/test.sh \
  --test 'TLS client preserves owned configuration and stream borrows' \
  --test 'TLS dependency selection follows pruned typed operations' \
  --test 'TLS local protocol policy and native cleanup contracts' \
  --test 'TLS optional build and package dependency boundary'
./scripts/test.sh --test 'standard-library testing module reports deterministic native results'
```

The last selection passes on macOS: 171 passed, one intentional skip, 172 total
across 12 stdlib suites, with 13 suite checks including intentional framework
failure reporting. M5 contributes four native stdlib tests; M1-M4 remain in
`scripts/test-stdlib.sh`. The TLS example passes strict source/class compilation,
O3 linking and its local peer with exit 42.

The final TLS driver covers 139 local scenarios: 44 protocol/native/benchmark
cases and 95 managed-allocation cases. These include IPv6 SANs, incorrect numeric
identity, direct hostname resolution and unresolved hostname routing through
both proxies. All 139 cases pass together on each of the three platforms.
Separate C allocation/trust checks and build-boundary checks supplement those counts.

- Local leaves cover trusted and untrusted chains, expired certificates, wrong
  host, wrong purpose, numeric IP SANs, DNS SNI, custom roots, combined roots,
  malformed/empty/unreadable overrides and bundled rejection of private roots.
  A second native fixture compiles the real adapter with controlled default A:
  default accepts A/rejects B, custom B accepts B/rejects A. The ordinary OpenSSL
  default loader demonstrably sees ambient B while the adapter ignores it.
  No installed host trust store is modified.
- An otherwise valid revoked leaf is accepted with zero requests to controlled
  CRL/OCSP endpoints, documenting D158's explicit exclusion. Environment and
  OpenSSL configuration cannot add trust or disable verification.
- Thirty reconnections each to TLS 1.2 and 1.3 peers perform full handshakes.
  Peers report no session reuse, observe no resumption PSK or early-data
  extensions, and send 30 TLS 1.2 tickets / 60 TLS 1.3 post-handshake tickets.
  Managed and native connection retention is zero after process warmup.
- Scalar/bulk transfer, zero-length calls, decrypted availability, borrowed
  getters, buffered wrappers, close propagation, authenticated EOF, truncation,
  connect/read/write deadlines and repeated close are exercised. Test-only
  syscall hooks inject short I/O, EINTR, would-block, interrupted polling,
  native read plus close failure, and deadline exhaustion through real retries.
- Credential inputs are mutated and freed after construction. Ownership tests
  accept safe caller-buffer and client reclamation, and reject independent
  view frees, escaped views and use after owner free. Mutating the actual TLS
  input implementation to publish the buffer invalidates its free proof.
  Native access and omitted verification/session/revocation APIs are rejected.
  Final access review found that a package-private bridge admitted forged raw
  handles from same-package code. Moving it into a private nested class closes
  that gap using existing visibility rules. Semantic tests reject both nested
  and binary-name access; CLI and relocated-package checks reject a same-package
  caller of the nested bridge. No runtime check or ownership exemption was added.
- Source, class-directory and archive inputs exercise pruning and dependency
  selection, including dispatch, static initialization and finally paths.
  Plain TCP links/runs with an absent SDK and pruned TLS callers. Class-only
  TLS compilation needs no SDK. Trace logs show no TLS compiler inputs or
  OpenSSL flags for plain links; binaries contain no TLS symbols or CA payload.
  Missing/wrong-platform/checksum-mismatched SDKs fail before native compilation.
  Static order is adapter, libssl.a, libcrypto.a, without whole-archive loading.
  A same-JVM cache probe reuses unchanged objects, then recompiles exactly the
  TLS adapter after an isolated header/build-identity change.

## Allocation and native cleanup ledger

A direct numeric-route, DNS-identity connection with a custom CA path allocates
exactly eight managed objects after process metadata is warm:

| Site | Allocations |
| --- | ---: |
| TlsClient, native delegate and SocketDescriptor | 3 |
| Copied CA-path String | 1 |
| Temporary UTF-8 path and identity arrays | 2 |
| First input/output views | 2 |
| **Direct connection total** | **8** |

The fixture's caller endpoint and reusable payload buffer are outside the
connection interval. Its first connection additionally initializes the shared
NO_PROXY object. Numeric verification adds an address and its byte-array copy,
for 10. Repeated getters and established scalar/bulk I/O add zero managed
allocations. Every successful close/free returns to the warmed live baseline.

The authenticated SOCKS5 fixture costs 26: the direct eight plus caller proxy
endpoint/address (2), caller credential arrays (2), original Proxy graph (5),
independent copied configuration (5), copied target endpoint/address (2), and
ProxyExchange/scratch (2). SOCKS4 costs 24 because both configuration graphs
omit the password copy. Numeric HTTP Basic CONNECT costs 31: SOCKS5's 26 plus
numeric host rendering (builder, backing storage and cached String, 3), an
authority String and encoded array (2). These are complete fixture totals,
including caller configuration; they are not zero-allocation setup claims.

Managed OOM sweeps cover limits 0..17 direct, 0..35 SOCKS5 and 0..40 HTTP,
including buffered stream construction and result arrays. All 95 failure/success
cases either stop before main or reclaim the complete owned graph; descriptor
accounting also balances. Ordinary thrown language exceptions retain the
existing MEMORY/D154 lifetime. For example, ten invalid-CA attempts retain ten
exception/message pairs, not client/transport graphs.

The C allocator fixture includes the real adapter and installs OpenSSL's
allocator callbacks before initialization. It warms shared algorithm/OID data,
then fails each allocation position through create/configure/attach/close.
It checks borrowed-descriptor survival, custom/default root counts, zero
per-cycle native retention, and zero tracked blocks after OPENSSL_cleanup.

| Platform | Allocation positions injected | Rejected setup results | Warm shared blocks | Roots default/custom | Retained per completed cycle |
| --- | ---: | ---: | ---: | --- | ---: |
| macOS ARM64 | 6028 | 499 | 4371 | 121 / 1 | 0 |
| Linux ARM64 | 6028 | 499 | 4371 | 121 / 1 | 0 |
| Linux x86-64 | 6096 | 499 | 4379 | 121 / 1 | 0 |

Some upstream optional allocations can fail while setup still succeeds; all
outcomes must clean up. Injection exposed a void SSL_set_connect_state failure
that left an incomplete record layer. The adapter now checks its error queue
immediately and releases that connection. The test releases reusable upstream
thread-error buffers before comparing failure baselines; production reuses
those buffers. Native accounting lives only in test fixtures.

## O3 and fixed-workload evidence

Inspected the final linked disassembly and separate O3 adapter assembly on
ARM64 and x86-64. Client stream calls reach direct typed adapter operations.
Untimed branches bypass the clock, while readiness waits occur on WANT results.
Bulk calls pass caller storage plus offset directly to SSL_read_ex/SSL_write_ex;
scalar calls use a stack byte. Successful adapter paths clear the OpenSSL error
queue, perform the SSL operation and return its primitive result. No adapter
malloc, payload copy, ownership registry, trace maintenance or descriptor-mode
change occurs on those paths. Failure mapping follows SSL_get_error immediately.

Each measured workload has 1000 iterations of one-byte and 257-byte echoes:
2000 writes, 2000 reads and 258000 application bytes in each direction, excluding
setup/warmup/close. Separate diagnostic runs count 2000 sends, about 6000 receives
and 2000 waits (readiness-dependent), zero descriptor-control calls, zero managed
allocations and 8000 upstream native allocations. Untimed runs make exactly two
clock calls, both fixture timing boundaries. Timed runs charge clocks to their
operation deadlines. Encrypted TLS 1.3 traffic is 302000 bytes each direction.

Those eight native allocations per iteration belong to OpenSSL WPACKET record
framing/cipher operations, confirmed with allocation stack attribution. The
adapter adds none. TLS 1.2's small reconnect workload instead measures four
native allocations per iteration. This does not change the M1 plain-TCP budget,
which explicitly excludes TLS record processing. There is no claim that TLS
I/O has zero total native allocation. Repeated connections restore the native
baseline after shared first-use metadata.

Untraced O3 elapsed milliseconds, three runs, with timing outside the loop:

| Platform | Timed runs (median) | Untimed runs (median) |
| --- | --- | --- |
| macOS ARM64 | 44.087, 43.815, 46.415 (44.087) | 42.938, 47.280, 50.427 (47.280) |
| Linux ARM64 | 121.127, 97.508, 81.064 (97.508) | 69.834, 108.217, 120.520 (108.217) |
| Linux x86-64, Rosetta | 86.010, 86.190, 86.139 (86.139) | 86.011, 85.436, 85.148 (85.436) |

These are fixed-workload evidence, not cross-platform speed rankings. Local
Python peers, scheduling and emulation affect timing; the separate allocation,
call-count and machine-code checks establish the structural costs.

## Dependency and distribution evidence

Prepared OpenSSL 3.5.8 and the curl Mozilla CA export dated 2026-08-13 with
`packaging/tls-dependencies.properties` on all three platforms. The recipe pins
LLVM 23.1.0, Perl 5.32.1, GNU Make 4.4.1 and Python 3.14, records all installed
file checksums and keeps the unmodified source archive and CA export. Linux
archives, runtime adapter and final links use the matching glibc 2.17 sysroot.
macOS used SDK 26.5 and deployment target 11.0. No shared providers/configuration
or system OpenSSL development files are selected.

Host packages and IDKs pass their complete smoke paths on all three platforms.
Final TLS follow-ups include paths with spaces, private-bridge rejection, local
verified peers, SDK/CA/checksum/TSV identity, packaged sources and notices, and
no shared OpenSSL dependency. Both Linux TLS executables satisfy GLIBC <= 2.17;
the IDK checks also retain the existing plain-program O0 through O3 gates.

Linux packaging ran in the local VM on Linux filesystem staging volumes.
The initial host-mounted staging attempt encountered a VirtioFS symlink
extraction failure; Linux staging resolved it without changing package behavior.
The final Linux logs end with successful relocated TLS and GLIBC_2.17 checks.
macOS uses the repository's documented Command Line Tools/SDK selection.
License auditing passes for all five existing OpenJDK-derived source files,
and the mutable IronDocs snapshot, relative documentation links and diff checks
pass. No release was published and no branch was pushed.

## Platform-runner correction

The initial Linux verification selected prepared SDK prefixes manually and
missed the ordinary platform runner's dependency setup. The maintainer's later
run exposed three failures on both Linux targets: the stdlib and TLS protocol
tests selected the mounted checkout's macOS SDK, and the TLS build fixture
encountered a dangling toolchain symlink left by another platform.

On 2026-09-17, `scripts/test-platforms.sh --setup` was updated to build the pinned
TLS SDK inside each Linux image. Its cache identity includes the recipe, pins
and license inputs. Linux containers explicitly select `/opt/ironwood-tls` and
isolate compiler, integration and stdlib outputs by platform. The build fixture
recreates its owned wrapper, including valid and dangling old links, without
modifying the original toolchain. Compiler/runtime semantics and strict SDK
validation are unchanged.

After rebuilding the images, this ordinary retry passes all three previously
failing selections on both Linux ARM64 and Linux x86-64:

```sh
./scripts/test-platforms.sh --setup
./scripts/test-platforms.sh --platform linux-arm64 --platform linux-x86_64 --failed
```

Each retry verifies the stdlib's 171 passes and one intentional skip, all 139
local TLS scenarios, native allocation sweeps (6028 ARM64 / 6096 x86-64, zero
retained per completed cycle), O3 inspection, benchmarks and dependency-boundary
checks. Thirteen focused workflow tests also pass, including actual replacement
of dangling links and an old clang symlink while preserving the original tool.
License and diff checks pass. No full compiler suite was rerun.

Original failure reports and setup/retry logs are retained under
`workspace/networking-m5-runner-fix/`; the new per-platform reports and isolated
native evidence are under `workspace/platform-tests/`. See
[local testing](LOCAL_TESTING.md#networking-milestone-5) for named selections when
there are no recorded failures to retry.

## Reproduction and remaining boundaries

Prepare/select a matching SDK using [TLS.md](TLS.md). The local checkout's
ignored `toolchain/ironwood-tls` points to its prepared macOS SDK. Manual Linux
runs must select a matching prefix; the normal platform runner selects the SDK
prepared in its image by `--setup`. Compiler tests use the
selected LLVM directory for objdump, avoiding a PATH-only tool lookup.

The protocol driver writes logs, report.json, disassembly.log and tls.s under
`integration-tests/target/networking-m5/`; build/cache evidence is under
`networking-m5-build/`. This working session preserves per-platform reports and
logs under `workspace/networking-m5/`, including focused compiler results,
native fault sweeps, untraced benchmarks, example and packaging checks.
Those ignored raw artifacts are local evidence; this checked-in record and
fixtures provide the durable summary and reproduction path.

This is a single-threaded TLS client, with explicit ASCII identities, bundled
or explicit roots, no verification bypass, no revocation/staple validation,
no session reuse and no early data. DNS/local trust loading are synchronous and
charge elapsed time when they return. Close attempts one nonblocking notification;
it cannot promise delivery on an unwritable transport. Updating OpenSSL/CA pins
requires new verification. TLS servers, JSSE, HTTP/HTTPS downloading, redirect
handling and event loops were not implemented. M6 requires separate selection.
