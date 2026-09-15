<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Networking Milestone 2 verification

Status: Milestone 2 complete at its selection checkpoint, 2026-09-15.
Implementation, focused verification and distribution checks passed, with the
SDK compatibility limitation below. Milestone 2 was
separately selected after the [Milestone 1 checkpoint](STDLIB_N1_VERIFICATION.md).
Milestones 3 through 6 remain unselected. The
[migration plan](NETWORKING_MIGRATION_PLAN.md) and
[source/contract review](STDLIB_N1_SOURCE_REVIEW.md) define the accepted scope.

| Milestone 2 criterion | Result |
| --- | --- |
| Remaining blocking constructors, options, urgent data and extension hooks | Pass: both address families, Java 21 contracts and actual custom dispatch |
| Literals, scopes, address values and OS DNS policies | Pass: 535 literal cases per target, controlled resolver ordering/defaults/cache behavior and reverse confirmation |
| Result ownership, copied inputs and failure cleanup | Pass: safe and rejected source cases, managed/native allocation injection and balanced descriptors/result lists |
| Preserve TCP I/O, deadlines and performance budget | Pass: focused M1 regression driver, allocation/native-call counts, benchmark and O3 inspection |
| Independent facade and derived helper boundary | Pass: exact pinned header, separate parser classification, license audit and packaged source/notices |
| Documentation, example and distribution metadata | Pass: supported-member records, runnable example, relocated host package and IDK smoke with SDK 26.5 |

## Reproduction

Use Java 21 and pinned LLVM 23.1.0. The focused driver is:

```sh
./scripts/build.sh
python3 scripts/test-networking-m2.py
```

Groups `prepare`, `contracts`, `literals`, `resolver`, `failures`, and
`allocations` can be selected independently. Outputs, generated Java probes,
LLVM IR and `report.json` are written to
`integration-tests/target/networking-m2/`. The diagnostic C interposer is test
code only: dyld on macOS, LD_PRELOAD on Linux. Constructor allocation injection
additionally uses the existing macOS-only TCP interposer. No instrumentation
enters shipped code and no unfiltered compiler suite is run.

## Address, resolver and socket evidence

macOS 26.6.2 (build 25G83) ARM64, Linux ARM64 and Linux x86-64 checks passed
with Java 21.0.10 and LLVM 23.1.0. Linux uses the local Colima VZ VM; x86-64
runs through Rosetta.
Both Linux targets use the pinned glibc 2.17 toolchain/sysroot. These are local
VM/container results, not native Linux hardware measurements:

- 535 Java differential literal cases, including decimal short forms, leading
  zeros, overflow, BSD ambiguity, malformed brackets/compression, mapped IPv4,
  Unicode digit rejection, numeric/named scopes, predicates and hashes.
- 86 differential output lines per family for all four TCP destination
  constructors, explicit local binding, bound listener constructors, binary
  address/name factories, mapped normalization, explicit IPv6 scopes,
  unresolved equality/hash/rendering, option widening and validation.
- Real custom implementation dispatch for OOB-inline and traffic-class typed
  options, urgent data, advisory preferences and both convenience connect
  overloads. Supplied implementations remain caller-owned.
- Controlled mixed/IPv4-only/IPv6-only resolver results preserve OS order within
  IPv4-first groups and remove duplicates. Single lookup chooses the first
  result. Alternating success/failure makes four native queries, with no stale
  result or positive/negative cache. Every acquired OS result list is released.
- Reverse lookup requires a matching forward result; an unconfirmed result
  falls back to numeric text. Each name/canonical getter caches its own result.
  Local hostname lookup, unresolved endpoints, IPv6-only default values and
  pre-resolver rejection of BSD-only literals are controlled at the OS boundary.
- Native ENOMEM/EAI_SYSTEM mapping, partial resolver-error cleanup,
  host-unreachable mapping, traffic-class failure, unavailable reuse-port
  discovery, and urgent EINTR/would-block retries. Both retry cases deliver
  byte 42 after exactly two urgent send attempts.

The Linux glibc 2.17 build initially exposed IPv6 classification macros whose
expansions require fields hidden by POSIX feature selection. Portable byte/word
tests replace those macros; no newer libc requirement or global feature-mode
change is introduced. The eight affected ARM64 tests passed on retry; the
other twelve had already passed. All twenty selected x86-64 tests passed. Both
Linux targets then passed the complete focused M2 native driver.

## Allocation and cleanup ledger

| Operation | Managed allocations measured on all three targets |
| --- | --- |
| Four distinct DNS results | 11: query state, UTF-8 query input, output array, four addresses and four owned hostname copies |
| Two distinct DNS results | 7, following the same `2n + 3` accounting |
| Confirmed reverse-name materialization | 6 temporary/result objects; only the returned cached String remains owned by the address |
| Unconfirmed reverse-name materialization | 8 objects including numeric fallback rendering |
| Sixteen repeated cached name getters | 0 |
| Reclaimed successful bulk result | 0 live-allocation delta |
| First unresolved hash, then sixteen repeated hashes | 1 temporary lowercase String, then 0 |
| IPv4 literal | 1 address; no parser allocation |
| IPv6 literal | 2: caller scratch array and returned address; no parser allocation |
| First numeric host-string access, then sixteen repeats | 3 rendering allocations, then 0; no forward or reverse query |

Private query state holds a native list and a sequential cursor. It uses no
additional native result wrapper or index array. Duplicate detection is
quadratic in result-list length; cursor traversal avoids repeating the entire
prefix for each returned address. This cold resolver work is separate from TCP
I/O. OS resolver allocations and caches belong to the platform; the controlled
fixture counts each owned result block and its matching release.

Managed allocation limits 0 through 15 cover partial bulk results. Another 84
runs cover failures during reverse/canonical getters, scoped copies, unresolved
endpoints and copied endpoint rendering. All return to their live-allocation
baseline. Thirty-two macOS constructor runs inject failure after warm-up and
verify that acquired descriptors close and owned graphs return to baseline.

The native allocator fixture compiles the actual C adapter at O3 on all three
platforms. It verifies long-hostname allocation failure, matching free after
lookup failure, embedded-NUL rejection before allocation/query, and the
short-hostname stack path without native heap allocation. These substitutions
exist only in the test translation unit. Linux resolver and socket executables
also pass symbol-version inspection with no GLIBC requirement newer than 2.17.

An explicit native ENOMEM creates one ordinary `OutOfMemoryError`; that thrown
object follows existing language exception lifetime. Managed allocation
exhaustion uses the runtime's preallocated error. These are counted separately,
not treated as leaked resolver or socket storage. Unknown-host failures likewise
retain thrown exceptions and their copied messages under that existing policy.

## Preserved TCP performance

The entire focused Milestone 1 driver passed after the M2 additions. Its graph
ledger, Java interoperability, deadline and cleanup checks remain valid. The
numeric accepted graph still uses nine objects, or eleven with the custom
wrapper, after materializing the same views. Repeated I/O and option/inventory
access retain their zero-allocation budgets.

The diagnostic 512-round untimed exchange sent 131,584 bytes in each direction.
Each side made 512 receive and 512 send calls, with zero polling, clocks,
descriptor-control calls, option calls, availability probes or allocations.
The corresponding untraced client measurement was 22,316,000 ns; the timed
client measurement was 13,694,000 ns. These local scheduling-sensitive samples
are evidence that the benchmark ran, not comparative throughput claims.

The O3 `Operations.asm` inspection follows the timeout-zero branch directly to
`ironwood_tcp_read_bytes`, then its single `recv` and successful return. The
clock and wait branches are bypassed; errno is read only after syscall failure.
Scalar receive has the same one-call success path. The diagnostic traces
independently verify those paths before and after timeout transitions.

## Compiler, example and distribution checks

Twenty focused compiler tests passed on macOS: new typed resolver/ownership
checks, copying-constructor overload effects, real delegation, destructor
effects, detached factory-created array elements, and the previously reported
array-copy, utility and U3 stream regressions. A further three-test run passed
after the resolver cursor and convenience connect additions.

A final focused ownership run also accepts dependent hostname/canonical borrows
and rejects their independent free or use after address reclamation.

The new `examples/tcpnames` workflow compiles and links with `--unfreed=error`,
prints `Resolved localhost and exchanged byte 42.`, and exits with status 42.
The existing numeric example remains a separate Milestone 1 demonstration.
`scripts/package.sh` and `scripts/package-idk.sh 0.4.2-beta` both passed. The
relocated host archive compiled/linked/ran `tcpnames`; the relocated IDK passed
its standard smoke at O0 through O3 and the added literal/DNS/listener program.
The IDK used only its bundled Java/LLVM tools with PATH restricted by the smoke
script. Source, notices, provenance and all four networking documents are present
in the package; required review/evidence documents also reside in the stdlib
archive's `META-INF/LICENSES`. The parser's embedded ironclass source exactly
matches its checked-in source and complete pinned upstream header. License audit
passes with four derived source files. No Linux IDK archives were rebuilt in
this milestone; their native outputs and glibc baseline were checked locally.

The host's default Xcode selection requires accepting a new license; commands
instead selected existing Command Line Tools using `DEVELOPER_DIR`, without
changing global configuration or accepting terms. The bundled IDK linker then
rejected SDK 27.0's new `arm64e.x1` text-stub tag in the existing streaming smoke,
before any networking code. Setting `SDKROOT` to the installed macOS 26.5 SDK
made the complete IDK smoke pass. SDK 27 compatibility remains a bundled-toolchain
limitation, not a claimed pass. The reproduction command is in [IDK.md](IDK.md).

## Evidence locations

- `workspace/networking-m2/focused-regression.log`: twenty macOS compiler tests.
- `workspace/networking-m2/final-semantic.log` and `final-borrow-tests.log`:
  targeted follow-ups for cursor, convenience connects and dependent names.
- `workspace/networking-m2/linux-verification.log` and `linux-retry.log`: initial
  portability failure, focused retry, both twenty-test selections and M2 drivers.
- `workspace/networking-m2/final-probes.log` and `final-linux-probes.log`: literal
  storage, cached host strings, native allocator and Linux symbol-version checks.
- `integration-tests/target/networking-m2/report.json`: macOS driver evidence.
- `workspace/networking-m2/linux-arm64/report.json` and
  `workspace/networking-m2/linux-x86_64/report.json`: isolated Linux driver evidence.
- `workspace/networking-m2/package.log`, `package-idk.log`, `relocated-host.log`
  and `idk-smoke.log`: package builds and relocated checks;
  `idk-smoke-sdk27-failure.log` preserves the SDK mismatch.
- `workspace/networking-m2/m1-regression.log` and
  `integration-tests/target/networking-m1/`: unchanged M1 gates, benchmark,
  native-call reports and optimized assembly.

Generated evidence stays in ignored output directories; the checked-in fixtures
and scripts reproduce the checks. No full compiler suite or hosted builds ran.

## Scope checkpoint

Blocking OS DNS can outlast a connect timeout. There is no application
multiplexing, connection racing, resolver provider, or Ironwood DNS cache.
NetworkInterface-valued APIs and reachability belong to Milestone 3; proxies,
TLS and the downloader belong to later separate selections. N1 remains pending
its eventual non-blocking/event-loop acceptance program.
