<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 6 verification

M6 was separately selected on 2026-09-17 under D166 and completed on 2026-09-18
on the local `socket-tcp-support` branch; no push is authorized.
This milestone completes the accepted blocking migration only. N1's event-loop
phase remains pending and unselected.

## Implemented boundary

The original [wget project](../projects/wget/README.md) implements private RFC
3986 URL resolution, HTTP/1.1 GET and response framing, bounded redirects,
verified M5 TLS, explicit M4 SOCKS4/5 and CONNECT routes, binary file/stdout
streaming, and failure cleanup. Compile/link/run/test scripts preserve the
caller directory and reserve stdout for downloaded bytes.

`InetAddress.parseLiteral` is the small independent facade extension used to
select NP3 literals before DNS. Its results are fresh, its input is borrowed,
and nonliteral names return null. Null/empty and ambiguous/malformed literals
retain explicit failure behavior. The derived IP and SOCKS helpers and their
licensing boundaries are unchanged. No compiler semantics, runtime ownership
bookkeeping, public HTTP framework or event loop is added.

## Focused gates

Exact compiler selections:

```sh
./scripts/test.sh --test 'wget URL and response ownership contracts' --test 'wget local HTTP HTTPS and streaming contracts' --test 'TLS optional build and package dependency boundary' --test 'standard-library testing module reports deterministic native results'
```

The project driver is `scripts/test-networking-m6.py`; its named groups are
`protocol`, `tls`, `files`, `allocation`, and `cleanup`. Tests use local scripted
peers and generated private test roots, never live Internet acceptance checks.

- 85 URL assertions cover RFC resolution, relative/absolute/network references,
  dot segments, absent/empty queries, opaque escapes, fragment inheritance,
  DNS/IPv4/IPv6 authorities, NP3 and independent redirect storage. Native hooks
  forbid DNS and Internet socket connections during parser/invalid-URL checks.
- Protocol fixtures cover split/coalesced interim/final heads, 16-interim and
  exact/exceeded metadata limits, one head deadline, 101 rejection, 204/304/205,
  case-insensitive fields, obs-fold, TE+CL in both orders, repeated/list lengths,
  overflows, chunk extensions/trailers, truncation, resets and body deadlines.
- Redirect fixtures cover all five statuses, the 20-hop limit, missing/duplicate
  and empty Location, host/port changes, upgrade, downgrade including after an
  upgrade, new TLS identities, and fresh same-host handshakes without resumption.
- Direct and proxied HTTP/HTTPS cover authenticated SOCKS5 and CONNECT, SOCKS4
  USERID, proxy-side DNS, 407 without retry, credential isolation, wrong roots
  and identities, IPv6, TLS close_notify and abrupt TLS EOF.
- File/stdout checks cover exact binary output, no file truncation for HTTP
  failure, caller-relative paths with spaces, open/write/close errors, closed
  stdout pipes, and primary-error preservation during secondary cleanup.
- Strict source/class/archive builds use `--unfreed=error`. Ownership tests
  accept independent URL/address results and reject freeing borrowed components
  or using a reader after freeing its input. No safety exemption is introduced.
- The standard-library runner now reports 172 passed, one intentional skip,
  173 total across 12 suites (13 runner checks). Its M2 address suite contains
  ten tests including the new literal factory; M1/M2/M3 coverage stays present.

| Platform | Focused behavior checks | Host package / IDK |
| --- | --- | --- |
| macOS ARM64 | All four exact selections pass; 471 downloader cases | Both pass |
| Linux ARM64 | All four exact selections pass; 471 cases plus the 30-case redirect/TLS supplement | Both pass; GLIBC <= 2.17 |
| Linux x86-64 | All four exact selections pass; 472 downloader cases | Both pass; GLIBC <= 2.17 |

Linux has the additional `/dev/full` output-failure case. ARM64's supplement
adds the explicit downgrade-after-upgrade fixture introduced after its original
run; overlapping cases are not added to the unique total. Both Linux targets
therefore cover 472 unique cases. macOS selections ran in focused batches,
with the final downloader selection repeated after the copy-extent optimization.
The shared native interposer also passes M5's `native-reset-and-close-error`
regression. No unfiltered compiler suite or hosted build was run.

## Allocation and cleanup ledger

| Storage / operation | Budget and evidence |
| --- | --- |
| URL/configuration | Owned copies of retained components, paths and explicit proxy credentials. Redirect URLs do not borrow previous URL/header storage. At most 21 URL frames survive; each previous connection and its buffers are reclaimed before the next hop. |
| Response | One 8192-byte input array, one 65536-character line scratch array and primitive framing state. Only a redirect materializes Location text. No payload-sized or per-chunk object. |
| Output | One reusable 8192-byte staging array plus an optional owned file stream. Large contiguous spans bypass staging; 32768 single-byte chunks produce four file writes. |
| Measured reader | Four complete file-reader cycles for 5-byte, 4-MiB and 32768-chunk fixtures. Head/body managed and native allocation deltas are zero; managed live counts return to baseline. |
| Real TCP/TLS body | Managed allocation delta is zero for timed/untimed, small-chunk and 4-MiB transfers. HTTP parsing/staging adds no native allocation. |
| Complete warm cycle | Numeric direct HTTP: 36 managed allocations; HTTPS: 43. Three-hop HTTP: 98; HTTPS: 117. These include configuration, URL, request, connection, response and output setup, not process argument/startup objects. Four cycles check the subsequent warmed cycles against their managed baseline. |
| Native cleanup | Interposition checks balanced socket descriptors, injects file/socket close failures, and checks per-cycle native heaps after first-use OpenSSL process initialization. Repeated three-hop cycles run under a 32-descriptor limit. |
| Allocation failure | Sweeps 0..82 for redirected HTTP, 0..95 for HTTPS and 0..146 for authenticated CONNECT HTTPS: 326 limits including the first complete run for each route. All injected failures reclaim the managed graph and tracked sockets. File-close paths are checked separately by fault injection. |

Pinned OpenSSL 3.5.8 TLS 1.3 record processing retains the M5/D165 measured
native cost: its WPACKET subpacket allocates per decrypted record. The 4-MiB
fixture observes 256 native allocations after the head has decrypted the first
record; the chunk fixture similarly follows record count. This is not a zero
native-allocation TLS claim. A diagnostic stack trace attributed this existing
cost to `tls13_cipher`/`WPACKET_init_static_len`; no upstream source was copied
or modified. Managed OOM sweeps do not claim exhaustive OpenSSL allocator fault
coverage; M5's native failure/cleanup tests remain the transport evidence.

A supplementary macOS probe combines premature EOF, file/socket close errors
and managed allocation limit 49, the first limit permitting the primary EOF
exception in that invocation. Both close-fault cases retain the EOF diagnostic
and exit 74 with balanced tracked sockets (`secondary-oom-review.log`). D051's
ordinary `finally` first-exception behavior supplies the fallback when cleanup
itself cannot construct another exception; no broader catch or runtime change
was needed.

## Optimized code and deterministic workloads

The driver emits LLVM and captures `llvm-objdump` output for the actual O3 wget
and reader probe. Reviewed response refill/exact-body and Sink.write paths keep
buffer bounds/framing/overflow checks, conditional refills and actual I/O calls.
ARM64 uses primitive `cmp`/`csel`, x86-64 `cmp`/`cmov` for copy extents. The buffered path
has no allocation, ownership registry, reference counting or trace maintenance.
Head deadline clocks are behind the head-mode guard, not called per body byte.
The executable retains TCP/TLS stream dispatch at refill boundaries; this is not
a claim that every call is devirtualized. Native stream writes remain necessary.

For a 4-MiB body, the untimed plain-TCP measured interval has 512 recv and 512
file-write calls, zero peek/poll/control calls and zero native allocations.
The head already buffered 8148 payload bytes; measured recv bytes are 4186156.
The 32768-chunk extension workload uses 72 recv calls and four file writes.
The two clock calls in those intervals are the probe's timing boundaries.
Timed reads and TLS add their transport readiness/deadline/record costs; those
are reported separately rather than counted as HTTP ownership overhead.

The fixed decoder and network workloads run both with and without native
interposition. Timing values are local regression evidence, not a comparison
with other downloaders or a throughput guarantee. Untraced milliseconds:

| Platform | Decode 4 MiB, median of 4 | Decode 32768 chunks, median of 4 | TCP 4 MiB | TCP chunks | TLS 4 MiB | TLS chunks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| macOS ARM64 | 0.470 | 1.270 | 1.103 | 2.695 | 2.183 | 2.104 |
| Linux ARM64 | 1.692 | 1.483 | 1.990 | 3.083 | 2.164 | 2.900 |
| Linux x86-64 | 0.857 | 4.263 | 0.685 | 4.938 | 1.973 | 5.208 |

Network samples are single fixed transfers after response-head setup, with
body deadlines disabled and output to `/dev/null`. Setup/handshakes are outside
the interval; these payload sizes fill the output buffer exactly. macOS runs
natively on the local ARM64 host; Linux runs in the local Colima VM, with
x86-64 under Rosetta. Concurrent validation and VM scheduling affect timings.
Use counters and the repeated allocation assertions for deterministic budgets.

## Distribution gate and evidence locations

Host packages and self-contained IDKs include the project source/test/scripts,
protocol driver, native test interposer, verification document and existing
source/provenance/notices. Relocated smoke tests use paths with spaces, compile
and run class-directory and archive downloaders against local HTTP/HTTPS, and
check dependency/CA identities and static OpenSSL closure. They also run real
plain TCP with unreachable TLS and a deliberately absent SDK. Existing M5
source/class/archive selection tests inspect commands, cache invalidation,
missing/mismatched prefixes, and removal of TLS symbols and CA data.

All three platforms passed `scripts/package.sh`, `scripts/test-package.sh`,
`scripts/package-idk.sh` and `scripts/test-idk.sh`. The macOS host smoke's final
TLS/downloader step initially exposed a test-only output-name collision; after
fixing it, that step passed on a fresh relocated extraction. Its preceding
checks had already passed. Both Linux host smokes and all three IDK smokes
passed in complete runs. License checks, generated API consistency and
`git diff --check` also pass. This is the completed M6 selection checkpoint;
N1 remains pending.

Local evidence is under `workspace/networking-m6/` (focused and delivery logs)
and `integration-tests/target/networking-m6/` (case logs, `report.json`, LLVM,
disassembly and allocation/native counters). Linux equivalents are isolated in
`workspace/platform-tests/integration-target/linux-arm64/networking-m6/` and
`workspace/platform-tests/integration-target/linux-x86_64/networking-m6/`.
These ignored artifacts are reproducible by the checked-in focused drivers.

## Remaining limits

DNS is synchronous and can exceed its deadline before the next stage checks it.
Plain request writes retain blocking TCP semantics. Output is streamed and can
remain partial after failure; a closed stdout pipe can terminate with SIGPIPE.
Plain TCP close-delimited HTTP cannot prove representation completeness. URL
input is ASCII URI text without automatic IRI/IDNA conversion or IPv6 URI zones.
TLS retains the explicit M5 trust/revocation/session/client-certificate exclusions.
No cookies, resume, recursive mirroring, HTTP/2 or public URI/HTTP subsystem is
implemented. None of these limits selects N1's later event-loop work.
