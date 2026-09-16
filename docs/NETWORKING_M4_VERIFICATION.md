<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 4 verification

Status: Milestone 4 complete at its selection checkpoint on 2026-09-16,
following separate maintainer selection. All focused gates below passed.
The [migration plan](NETWORKING_MIGRATION_PLAN.md) and
[contract review](STDLIB_N1_SOURCE_REVIEW.md#milestone-4-implementation-review)
define this milestone. Milestones 5 and 6 and N1's event-loop phase remain
unselected. Work remains local on `socket-tcp-support`, without a push.

| Passed criterion | Evidence |
| --- | --- |
| Explicit SOCKS4/5 and HTTP CONNECT | 96 scripted/native/benchmark scenarios per target, with exact wire checks |
| Configuration and credentials | Copied endpoint/octet storage, validation, no ambient credentials or fallback |
| Ownership, deadlines and cleanup | Positive/negative source proofs, class/archive clients, native faults and allocation-limit sweeps |
| Steady-state performance | Zero managed allocations and 1,000 recv/send calls per traced workload, no proxy/deadline setup work in the loop; O3 inspection |
| Public testing and examples | Ten M4 stdlib tests and strict local authenticated proxy demo |
| Provenance and distribution | Separate derived SOCKS helper, five-file license audit, relocated host/IDK proxy checks and packaged records |

## Scope and reproduction

Explicit SOCKS4/5 and HTTP CONNECT use owned endpoint/credential snapshots,
target endpoint reporting and one connect deadline. Tests exercise actual
public facades, private negotiation helpers, typed native operations and
mandatory ownership analysis. No TLS, downloader or global selector is added.

Use Java 21 and LLVM 23. Run the exact selections in
[LOCAL_TESTING.md](LOCAL_TESTING.md#networking-milestone-4). The proxy driver
supports `--case NAME`, `--skip-build` and `--evidence-only`. It writes generated
classes, archives, Java differentials, LLVM, native disassembly, fault logs and
`report.json` to `integration-tests/target/networking-m4/`. Each platform
overwrites that directory, so reports are retained separately under
`workspace/networking-m4/`.

## Contracts and platform checks

The seven focused selections cover public proxy ownership and API boundaries,
PEEK_BYTES typed lowering, existing primitive options, numeric TCP lifecycle,
factory/constructor ownership, the standard-library runner, and the complete
proxy driver. Initial macOS verification passed all seven. Both Linux targets
passed the six non-driver selections; the driver exposed ENOTCONN during peer
shutdown after the intended timeout. That expected disconnected-peer result is
now handled without disabling client-timeout, fallback or retry assertions.
The final three proxy selections pass on macOS ARM64, Linux ARM64 and
Linux x86-64, including all 96 scenarios on each. Both Linux executables have
no GLIBC requirement newer than 2.17. The local platform VM was stopped afterward.
No unfiltered compiler suite or hosted build was run.

The final driver has 96 scenarios: 65 local wire cases and 31 native, benchmark,
resolver and archive cases, plus separate argument, ownership and allocation
checks. Peers verify every request byte. A second listener detects forbidden
direct fallback, and the proxy listener detects extra connection attempts.
Ambient ALL_PROXY/HTTP_PROXY/NO_PROXY values cannot redirect or bypass the route.

- SOCKS tests cover default V5, explicit V4 with empty/configured user ID,
  resolved IPv4/IPv6, unresolved proxy-side names, domain/IPv6 bound replies,
  fragmented fields, truncated responses and rejected statuses. Malformed V5
  or rejected authentication never changes protocol or connects directly.
- Configured V5 offers only RFC 1929; credential-free V5 offers only no-auth.
  Lengths 1 and 255, high octets and binary credentials including NUL are
  preserved. HTTP Basic covers empty fields, padding boundaries and a 513-byte
  field across scratch-buffer flushes. Stdlib tests reject nulls, invalid
  lengths, controls, DEL, username colon and SOCKS4 user-ID NUL.
- HTTP covers 100/102/103/unknown 199, exactly 16 versus 17 informational heads,
  64 KiB versus overflow, 101/407, interim Content-Length/Transfer-Encoding,
  malformed status/version/header tokens/values and early EOF. Final 2xx ignores
  misleading framing fields and leaves coalesced bytes 42/255 intact for the
  tunnel. The tunneled peer receives only the requested binary payload.
- Delays across successive handshake phases exhaust one connect deadline.
  Test-only clocks and syscalls cover EINTR, would-block, short reads/writes,
  interrupted poll, native read failure, failure restoring flags, write deadline,
  proxy DNS failure and lookup time exhausting the deadline before connection.
  Read-error plus close-error injection preserves the original read failure.
- Actual factory inputs are mutated/freed, and the original Proxy is freed,
  before connecting. Target snapshots survive original target reclamation and
  report correctly after close. Streams and options retain their existing
  behavior. Negative source tests reject freeing borrowed endpoints, using them
  after owner reclamation, retained constructor inputs and published credentials.
  They alter the actual implementation source to invalidate its ownership proof.
- A fresh Java 21 probe agrees on 19 public route/argument results, including
  subclass type/address overrides. The registered-factory check distinguishes
  NO_PROXY from explicit HTTP/SOCKS. Source, class-directory and archive clients
  link and run; wrong option/value types and omitted Authenticator,
  PasswordAuthentication, ProxySelector and credential getters are rejected.
- Native peek checks exercise null, negative, out-of-range, overflow and empty
  ranges through the actual typed operation. Sixteen networking property keys
  return null. The existing general user.name property is preserved; SOCKS4
  wire tests establish that proxy configuration never reads it.

The standard-library runner includes ten M4 tests alongside M1-M3. Its selected
compiler check verifies 167 passed, one intentional skip and 168 total across
11 suites, plus intentional framework-failure reporting. The runnable proxy
example passes strict --unfreed=error compile/link and both authenticated local
peers. No Internet connection or live reachability probe is used.

## Allocation ledger and cleanup

For an unresolved proxy endpoint with two credential arrays, Proxy.socks5
allocates exactly five objects: Proxy, copied endpoint, copied hostname and two
copied arrays. Socket(Proxy) allocates eight: Socket, ProxySocketImpl,
SocketDescriptor and its five-object independent configuration. Twenty repeated
construction/close/free cycles verify those exact counts and zero live delta;
repeated route/inventory getters allocate zero. Numeric endpoints replace the
copied hostname with one copied address. Other address/scope metadata can add
its separately owned copies, as defined in the M2/M3 ledgers.

The successful fixture using a numeric proxy and unresolved target has this
complete allocation ledger after process metadata is warmed:

| Allocation site | Objects |
| --- | ---: |
| Caller proxy endpoint and numeric address | 2 |
| Caller username/password arrays | 2 |
| Original authenticated Proxy graph | 5 |
| Independent Socket/implementation/descriptor/configuration graph | 8 |
| Caller target endpoint and hostname | 2 |
| Socket-owned target endpoint and hostname copy | 2 |
| Call-scoped ProxyExchange and reusable 512-byte scratch | 2 |
| Two fresh remote endpoint snapshots, before and after close | 4 |
| First input/output delegate views and facade views | 4 |
| Reused 257-byte transfer array | 1 |
| **SOCKS5 authenticated workload** | **32** |
| HTTP authority String and UTF-8 encoding, reclaimed during setup | 2 |
| **HTTP Basic workload** | **34** |

SOCKS4 with user ID costs 30 because each configuration has only one credential
array. Credential-free SOCKS5 costs 28; credential-free CONNECT costs 30. The
fixture still allocates its two input arrays in those modes. These are explained
whole-workload totals, not zero-allocation connection claims. Resolved proxy
routes are borrowed during native setup; no redundant temporary endpoint is
created. Unresolved routes allocate a temporary resolved graph and release it
on every exit. No negotiation scratch survives connect.

Managed allocation-limit sweeps cover 31 SOCKS4, 33 authenticated SOCKS5 and
35 HTTP Basic cases after warmup, including success at each upper limit.
Limits are respectively 30..60, 30..62 and 30..64. Every measured failure and
success restores the prior live count. Each sweep additionally exercises the
30 first-use metadata failure limits. Native stream-descriptor counts balance
through every limit and injected native fault. Successful traced clients create
and close four descriptors including existing setup probes. Resolver storage
continues using the M2 explicit release path.

Ordinary caught exceptions retain Ironwood's existing exception lifetime;
managed exhaustion uses the existing emergency error. The tests do not silently
reclaim exceptions, hide owned storage in process globals or weaken a free proof.
No ownership-analysis production changes are required for M4.

## Benchmarks and optimized code

Each traced and separate untraced workload transfers 1,000 rounds of 257 bytes
in each direction through an established authenticated tunnel. Timing/reporting
are outside the transfer loop. Managed allocation delta is zero. Traced runs on all three targets each make 1,000 send and 1,000 recv calls, with zero peek,
poll, clock or descriptor-control calls and 257,000 bytes in each direction.
Negotiation's clock/poll/peek/flag calls are counted in a separate setup window.

| Target | SOCKS5 untraced loop (ns) | HTTP Basic untraced loop (ns) |
| --- | ---: | ---: |
| macos-arm64 | 38,034,000 | 31,905,000 |
| linux-arm64 | 47,004,961 | 44,635,250 |
| linux-x86_64 | 31,531,297 | 31,243,392 |

Counts, byte integrity and cleanup are gates; these scheduling-sensitive times
are workload observations, not comparative speed claims. Linux runs in local
Colima on kernel 6.8.0-117 with glibc 2.39 and the pinned glibc 2.17 build sysroot;
x86-64 uses Rosetta. They are not native Linux hardware benchmarks.

Inspected O3 scalar/bulk SocketDescriptor paths and the separately compiled
native adapter. Untimed reads branch around the clock and call read_bytes
once; successful packed results return without error translation. Bulk writes
call write_bytes, then return after full progress. Retry/wait and exception
construction remain on error or partial-progress paths. The adapters call
recv/send directly on caller storage, capturing errno only after failure.
PEEK_BYTES uses the same checked ABI with MSG_PEEK and is absent from established
stream paths. No extra payload copy, malloc/calloc/realloc, thread-local ownership lookup, registry,
trace maintenance or ownership bookkeeping occurs on these valid I/O paths.
This native heap conclusion follows source and machine-code inspection; managed
allocation counters do not measure OS/kernel or diagnostic-interposer storage.

## Distribution and provenance

Both package builders include the verification record loose and under
META-INF/LICENSES in ironwood-stdlib.ironjar, original facade and private helper
sources/classes, notices, and the runnable example. Relocation tests compile and
run authenticated SOCKS5 and HTTP CONNECT using the shipped example and local
Python peer. The first host smoke completed its earlier checks but exposed that
the examples filter omitted Python support files. Both builders now include
*.py alongside source/shell/readme files, and both smoke scripts require peer.py.
The rebuilt host passes the affected relocation check, including both protocols,
the exact upstream source header and matching loose/archive records. Earlier
host smoke checks passed before that final missing-peer failure, so only the
affected proxy step was repeated. The initial IDK smoke was invalidated by an
edit to its running script; a fresh stable invocation of test-idk.sh passes
completely, including O0-O3 checks and both proxy demos. Final documentation-only
updates are refreshed into loose/archive metadata and checked for exact agreement.

The IDK uses its bundled Java/LLVM and the installed macOS 26.5 SDK via
SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk, retaining the
existing workaround for unsupported SDK 27 arm64e.x1 linker stubs. No global
Xcode setting, license acceptance or host network configuration is changed.
SDK 27 compatibility remains unverified. No Linux distribution archive is built.

SocksProtocol retains the complete Classpath-covered Oracle header and immutable
OpenJDK pin 060c4f7589e7f13febd402f4dac3320f4c032b08. Only its wire algorithm is
derived. Proxy/Socket, HTTP CONNECT, descriptor/deadline logic, typed operations,
native support and tests are independent or original MIT OR Apache-2.0 work.
The source review discloses upstream inspection without claiming clean-room
isolation. The license audit passes with five derived files.

## Evidence locations

- workspace/networking-m4/focused-tests.log: initial seven macOS selections;
  refinement-tests.log and driver-final.log: focused refinements and final driver.
- platform-tests.log: initial Linux selections; linux-arm64-final.log and
  linux-x86_64-final.log: only the affected three proxy selections.
- macos-arm64/, linux-arm64/ and linux-x86_64/: per-target reports, generated
  LLVM, disassembly, native assembly, wire/native/OOM logs and symbol versions.
- package.log, package-smoke.log, idk-package.log and idk-smoke.log: initial
  distribution checks; package-final.log, package-proxy-retry.log,
  idk-package-final.log and idk-smoke-final.log record the successful rebuilds
  and affected relocation checks. metadata-refresh.log records final consistency.
- example-compile.log, example-link.log and example-run.log: strict example
  workflow; final-deadlines.log and stdlib-final.log: final timeout and stdlib
  checks; licenses.log: source/provenance audit.

## Limits

Synchronous proxy DNS cannot be interrupted; elapsed lookup time counts toward
the deadline when it returns. Credentials and tunnels use plain TCP. SOCKS4
requires locally resolved IPv4, and failed SOCKS5 never downgrades. HTTP 407
fails without retry; successful CONNECT stops at the final header terminator
without decoding a body. TLS and downloader behavior require separate selection.

Stop at the Milestone 4 checkpoint. Milestones 5 and 6 each require a new
maintainer selection; N1 remains pending its separate event-loop acceptance.
