<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7c1 bounded byte-view evidence

P7c1 implements and qualifies D234's Java-owned `ironwood.bridge.ByteView` on
macOS ARM64, Linux ARM64 and physical Linux x86-64. The producer admits only
proved synchronous, callback-free borrowed inputs. Storage cannot be retained,
returned, reclaimed, placed in fields/containers or used through Object/type
operations in native code. Unknown effects remain rejected. Java owns allocation,
slicing and read-only views; there is no close/free/address/backing-buffer API.
The [usage guide](JAVA_BRIDGE_USAGE.md) and [accepted design](JAVA_BRIDGE_BUFFER_DESIGN.md)
specify the supported surface. This report records P7c1; later extensions are
tracked in the [P7 qualification record](JAVA_BRIDGE_P7_QUALIFICATION.md). Numerical
performance acceptance remains maintainer review.

## Qualification

Production compiler code is frozen at `46e70578`, following `d32132db` (typed
foundation/proofs) and `27d03709` (transport/shared dependency). The final
qualification commit adds tests, IDK smoke coverage and documentation only.

| Target | Execution | Final focused checks | Java 22/23 replays | Supplemental mixed-failure children |
| --- | --- | --- | ---: | ---: |
| macOS ARM64 | Apple M5, Mac17,2, macOS 26.6.2 | Three view fixtures and two inlining fixtures passed, with producer/packaging rerun after final production changes | 28 | 9 across Java 21-23 |
| Linux ARM64 | Local Colima ARM64 virtualization | Same five fixtures passed | 28 | 9 across Java 21-23 |
| Linux x86-64 | Physical x86host, Xeon E-2288G | Same five fixtures passed | 28 | 9 across Java 21-23 |

Production and benchmark JVM: Temurin 21.0.12.1+1. Replay JVMs: Temurin
22.0.2+9 and 23.0.2+7. Native tools: LLVM 23.1.0. Linux uses the existing pinned
glibc 2.17 bridge support SDK. No translated x86 execution is presented as
hardware evidence. The final artifact of this run refuses Java 24 before native entry (D245
later admits Java 24 and 25).

The three view fixtures cover typed operations and P0 confinement proofs,
producer behavior, and shared-dependency binding/composition. Source, class and
archive inputs preserve positive and negative admission. Unsafe reclamation is
rejected under every `--unfreed` setting; counterfeit library definitions,
stale proof identities, casts, Object operations, storage and escape are rejected.
Source O0 and class/archive O3 consumers cover null, empty and extreme ranges,
read-only exception ordering, empty/no-write read-only paths, same-view identity,
distinct overlapping slices, immediate writes preceding native failure,
String/array/root-receiver composition, GC pressure, direct-memory OOM and
native-allocation failure. A 32-view call passes checked JNI capacity handling.
Owner closure is prevented by API shape, and native free is rejected by proof.

The last supplemental failure assertion uses the frozen producer jars: failure
acquiring a copied array before native entry leaves both the borrowed view and
Java array unchanged. Nine checked-JNI children per target cover all three
producer forms and Java 21-23. Their JSON records source and jar hashes because
this test-only addition follows the final compiler/input snapshot. Existing
write-before-failure checks separately require completed native view writes to
remain visible. Exceptions do not undo direct storage writes.
An additional reverse-overlap assertion passed nine Mac children using the same
frozen source O0/class O3/archive O3 jars on Java 21-23. This test-only addition
is recorded in `backwards-supplement.json`; Linux's recorded overlap checks
exercise the forward-dependent kernel. No production bytes changed.

Independent artifacts share the exact support class on both classpath and
module path. Public API shape, missing/altered support rejection before payload
extraction, byte-identical support generation, incompatible sidecar preservation,
Maven dependency and source/license delivery passed. Mac also passed 11 selected
adjacent regressions spanning arrays, protected exceptions, scalar allocation and
native ByteBuffer reclamation. No unfiltered compiler suite was run.

## Linux measurements

The [focused runner](../examples/java-bridge/byteviews/benchmark.py) compares the
same deterministic byte operations and checksums in five scenarios. Native-only
Ironwood uses the equivalent byte-array kernel because view construction belongs
to Java. Java executes either the byte-array or ByteView overload; the paired
bridge executes those same overloads natively. Read sums signed bytes; update
increments every byte and sums; overlap writes each incremented byte into the
next overlapping position and observes the preceding writes. The driver changes
an input byte on every call. Official OrderBook sources are unchanged.

Each scenario/operation/size has its own process. There are four Java warmup
batches, three native warmup batches, then seven measured batches. Sizes 1 and
64 use 1,000,000 calls per batch; size 4096 uses 50,000. Java uses
`-Xms128m -Xmx128m`. Functional children use `-Xcheck:jni`; timings omit it.
Each cell is **median batch-average ns/call / million calls/s**, including driver
work. These are not call-latency percentiles or multi-fork confidence intervals.
Every batch, checksum, allocation count and range is retained in `samples.csv`
and `summary.json`. x86host runs on isolated CPU 1 with the existing powersave
governor and turbo behavior; no fixed-clock claim is made. ARM64 is virtualized.
Mac's final short run is functional smoke only, not performance evidence.

### Linux x86-64, x86host

| Operation | Bytes | Pure Ironwood | Java arrays | Java views | Bridge copied arrays | Bridge borrowed views |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| read | 1 | 1.81 / 551.734 | 8.37 / 119.513 | 8.70 / 115.003 | 104.11 / 9.605 | 81.28 / 12.302 |
| read | 64 | 15.82 / 63.213 | 13.19 / 75.842 | 13.50 / 74.068 | 121.84 / 8.207 | 99.73 / 10.027 |
| read | 4096 | 1016.96 / 0.983 | 231.36 / 4.322 | 235.72 / 4.242 | 1391.98 / 0.718 | 1123.30 / 0.890 |
| update | 1 | 2.23 / 448.369 | 9.77 / 102.346 | 8.56 / 116.796 | 126.73 / 7.891 | 83.71 / 11.946 |
| update | 64 | 28.64 / 34.915 | 83.23 / 12.015 | 33.65 / 29.714 | 150.18 / 6.659 | 111.40 / 8.977 |
| update | 4096 | 1650.45 / 0.606 | 4100.35 / 0.244 | 1447.66 / 0.691 | 1974.33 / 0.507 | 1734.23 / 0.577 |
| overlap | 1 | 1.81 / 553.308 | 7.45 / 134.170 | 4.77 / 209.438 | 126.18 / 7.925 | 153.22 / 6.526 |
| overlap | 64 | 58.24 / 17.172 | 81.77 / 12.229 | 89.89 / 11.125 | 149.61 / 6.684 | 251.71 / 3.973 |
| overlap | 4096 | 3879.31 / 0.258 | 3478.25 / 0.288 | 5437.37 / 0.184 | 1605.95 / 0.623 | 6689.08 / 0.149 |

### Linux ARM64, local virtualization

| Operation | Bytes | Pure Ironwood | Java arrays | Java views | Bridge copied arrays | Bridge borrowed views |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| read | 1 | 0.97 / 1035.237 | 1.66 / 600.659 | 1.90 / 525.117 | 47.52 / 21.043 | 47.90 / 20.875 |
| read | 64 | 4.73 / 211.442 | 11.73 / 85.253 | 11.12 / 89.909 | 47.56 / 21.027 | 49.48 / 20.211 |
| read | 4096 | 229.05 / 4.366 | 964.56 / 1.037 | 1037.22 / 0.964 | 426.26 / 2.346 | 292.24 / 3.422 |
| update | 1 | 1.09 / 916.063 | 1.89 / 530.491 | 2.21 / 453.436 | 59.32 / 16.858 | 46.66 / 21.431 |
| update | 64 | 13.64 / 73.291 | 16.80 / 59.540 | 16.92 / 59.086 | 65.28 / 15.318 | 44.22 / 22.612 |
| update | 4096 | 228.12 / 4.384 | 985.08 / 1.015 | 989.41 / 1.011 | 452.59 / 2.210 | 281.39 / 3.554 |
| overlap | 1 | 0.95 / 1047.985 | 1.19 / 839.716 | 1.20 / 833.418 | 57.27 / 17.461 | 92.65 / 10.793 |
| overlap | 64 | 59.92 / 16.690 | 31.26 / 31.991 | 78.04 / 12.814 | 62.97 / 15.880 | 172.19 / 5.808 |
| overlap | 4096 | 7048.17 / 0.142 | 2670.69 / 0.374 | 6920.80 / 0.144 | 495.26 / 2.019 | 7069.52 / 0.141 |

The zero-copy gate has a useful measured result: x86host 4096-byte view reads
and updates take about 19% and 12% less time than copied-array calls, respectively.
They are about 10% and 5% above pure Ironwood. ARM64 reductions against copied
arrays are about 31% and 38%. Small calls still pay JNI metadata acquisition;
zero-copy is not a promise to outperform copying at every size.

Overlapping views remain slower: 6689 ns versus 1606 ns copied-array on x86host,
and 7070 ns versus 495 ns on ARM64 at 4096 bytes. The array overload exposes the
fixed one-byte recurrence within one array; the two-view overload must support
arbitrary legal aliases and lengths. Assembly shows a scalar dependent loop
and destination bounds enforcement for the view path. This is retained as an
optimization opportunity, not hidden by discarding that workload or adding a
false non-aliasing promise. Java is faster than native on several x86 workloads,
including read; these results do not claim general superiority over Java.

### Allocation and machine code

Every warmed view sample reports zero Java bytes and zero Ironwood allocations.
Copied-array calls report one Ironwood allocation per call, in addition to JNI
staging `malloc` not counted by the Ironwood counter. Source and final assembly
review of `iw_value_6`, `iw_byteview_input` and `ironwood_bridge_entry_6` confirm
stack descriptors and direct payload access, with no payload copy, native
allocation, lifetime counter, registry or TLS access on their successful paths.
JNI retrieves cached fields and the direct-storage address; its local backing
reference remains rooted until return. This is required transport work, not a
claim of a free crossing. Wide signatures reserve JNI capacity before acquisition.

The initial update loop repeatedly loaded immutable descriptor fields after
writes. Invariant descriptor loads alone lost their metadata during LLVM
argument promotion. Expanding typed accessors before that pass preserves the
facts and permits vectorization without marking payload memory invariant or
non-aliasing. Final `ironwood_bridge_entry_6` loads length/read-only once before
its loop: x86-64 at `0x5dc0/0x5dc9`, with packed updates at `0x5e10`; ARM64 at
`0x5848/0x5854`, with NEON updates at `0x58f4`. Failure paths remain outlined.
The overlap path retains sequential memory dependence; it does not share the
same optimization result. Focused inlining/cleanup/trace regressions passed.

x86host's before/after 4096-byte view-update measurement was 6386.13 to 1734.23
ns/call (about 3.7x). These are separate runs under the stated host conditions;
raw initial data remains available. The final production payload, not the first
prototype, supplies every table above.

Cold allocation is outside warmed timing. One 4096-byte bridge-view update
process reported allocation plus two slices at 4,638,480 ns and 86,616 Java bytes
on x86host, and 2,961,300 ns and 86,504 Java bytes on ARM64. These include first-use
class loading, exclude the direct off-heap payload from the Java-byte counter,
and are diagnostic startup samples rather than steady allocator benchmarks.
Every process retains its cold line separately from measured CSV rows.

## Artifact and evidence identities

All hashes below are SHA-256. Input snapshot `input-final.tar.gz`:
`103981a8c1580383d6d8681f5f479d0548bc8cd4b9905a1c285409d74ddfad83`.
It contains revision `46e70578`, compiled test classes, a per-file verified
manifest and `validation/qualify.sh`. The later supplemental assertions do not
change the compiler or production jars.

| Shared identity | SHA-256 or version |
| --- | --- |
| Compiler jar | `1742d957266bd913eb3d21acd76fc249f5b655fc1808324d0968a895f53b362d` |
| Runtime | `1249f98f575ae2247ae6c306c40eaf84d64c43cb889f2d8832c3393afd7c7264` |
| ByteOps generation | `843e1cbe34698645bc1f76faecebf2665cfbfd148f658522e43bf889620234ce` |
| Shared values jar | `2e1a99e54b68698e79c8136ba91cebef2d27a9edc10adbfe0e0e0d0ee79d29bc` |
| Values ABI / version | `1` / `0.5.5-beta` |
| Combined jar | `374e4c4dc346714d32aa31c244cfdfe1db0c8103fb00dccaee517fe0eab6edda` |

| Target | Paired jar | Native image | Pure-native executable |
| --- | --- | --- | --- |
| macOS ARM64 | `bdfac05861545ec35c9f0477968fe41cb2ac7535b1a98c1e91525423eb1a320c` | `abc18f08aeb163983539c261d7b93816fe9f703da4109051ae9791693c118de2` | `f9716c63ad3da5fe24e5bf159b7b317e2c4dd4c3d482c16c263181e2efe4407e` |
| Linux ARM64 | `6387aa367c17e30d59f6a4cbd742ed81dbc6373f4595df466a06ee738d2b8cf2` | `b81fe395d8558f3d5fcd0cccc07fa1a23ad828ec572ec2acccff52dc1801f77c` | `a0ee4b1fdf72c7f0ece4a6990584d06103e335c92f4715712c9d53ccd6d945b8` |
| Linux x86-64 | `dc5b4a43bdfe08ee64d25bea28175a6fc11b2e6e74202c07d5c85bc7fe1a8dcf` | `4d3034dad4056f62d10ced89112971f1e5790b1d69d0f182af4968dab3130f69` | `596b2ff5f6a0f780b87ac98a904029a454a4c80729d62aff87644167ad813426` |

The combined jar preserves all native entries byte-for-byte: one macOS entry,
109 Linux ARM64 entries and 109 Linux x86-64 entries including private runtime
support and corresponding source. Distribution preserves the paired jar and
shared dependency bytes; every distribution inventory hash, dependency POM and
shared source/license archive was checked. Assembly evidence is
`assembly-identity.json`; the adjacent replay logs record the actual consumers.
The same combined jar passed six checked-JNI consumers on each target: Java
21, 22 and 23 on both classpath and automatic module path, 18 children total.

The macOS ARM64 IDK `ironwood-idk-0.5.5-beta-macos-arm64.tar.gz` has hash
`6ed720d304c7e0c4757625caef90f6bde22283fdc03946ef9c51c6129b5771d5`.
The installed support jar has the same shared hash above. The packaging smoke
passed installed ByteView behavior, relocated compiler regeneration and exact
comparison, plus existing O0-O3, launcher, TCP and TLS-pruning checks. Packaging
used the existing bundled Zulu Java 21.0.10+7 and LLVM 23.1.0 SDK, distinct from
the Temurin producer/performance baseline. No toolchain was installed.

Raw evidence is retained under `workspace/java-bridge/byteviews/` in the canonical
checkout, outside version control:

- `linux-arm64-final/work/evidence/` and `x86host-final/evidence/`: five-test
  logs, 28-replay records, versions, manifest verification and full performance
  commands, samples, summaries, payloads and disassembly.
- Each target's `mixed-failure-supplement.json`: nine supplemental
  children and exact source/jar identities. Mac's is directly under `byteviews/`;
  Linux copies are inside their collected `workspace/java-bridge/byteviews/`.
- `mac-final/`, `mac-final-replay.json`, `java24-refusal.log`,
  `assembly-identity.json`, `assembled-final/`, `distribution-final/`,
  `idk-package-final.log` and `idk-smoke.log`.
- `backwards-supplement.json` and `.log`: nine Mac reverse-overlap checks on
  frozen O0/O3 producer jars after the final snapshot.
- `x86host-evidence-final.tar.gz`, verified/extracted on Mac, hash
  `f4a8b2383806877e2dac9b118fd1b94aba21205500bb001fdeb88ec2f866abfb`.
- Initial data: `linux-arm64-1/work/evidence/`, `x86host-1/evidence/`,
  `x86host-evidence-1.tar.gz`; it is not substituted for final qualification.

## Reproduction

Use the pinned JDKs/LLVM and existing Linux support SDK from
[JAVA_BRIDGE_NATIVE_SUPPORT.md](JAVA_BRIDGE_NATIVE_SUPPORT.md). Build with Java
21. Run only these focused checks via `scripts/test.sh --test`:

```sh
./scripts/test.sh \
  --test 'Java Bridge byte-view proofs preserve typed bounds confinement and artifact parity' \
  --test 'Java Bridge byte-view producer preserves shared storage bounds identity and artifact parity' \
  --test 'Java Bridge byte-view dependency binds exactly and composes across artifacts and modules' \
  --test 'selective inlining bounds loop candidates and preserves fallbacks' \
  --test 'selective inlining preserves native checks cleanup and traces'
python3 examples/java-bridge/byteviews/benchmark.py --cpu 1 --output workspace/byteview-measurement
```

Omit `--cpu 1` on Mac or where that Linux CPU is unavailable. Use a new output
directory. Expected: five tests pass; benchmark exits zero with 315 measured
rows, identical checksums, zero warmed view allocation and the stated copied
allocation counts. The runner retains exact build/run commands and identities.
For frozen-input replay, `validation/qualify.sh` checks the manifest before
running precompiled tests, consumer replays and the same benchmark.

Linux images are preexisting, not installed by this task:

| Target | Image | Image identity |
| --- | --- | --- |
| ARM64 | `ironwood-bridge-linux-arm64:05d5199baf46c922` | `sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767` |
| x86-64 | `ironwood-bridge-linux-x86_64:1a18fe26577fb8c5` | `sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7` |

Frozen runs mount the extracted snapshot at `/work`, existing support at
`/support:ro`, and existing consumer JDKs at `/jdks:ro`. Set
`QUAL_TARGET=linux-arm64` or `linux-x86_64`, use `/work` as the container working
directory, and run `sh validation/qualify.sh`. x86host work stayed under
`~/temp/java-bridge/p7c1-20260928-final`; the previous run used the sibling
`p7c1-20260928-1`. Preserve the input archives and copy evidence back before
cleaning task-created output. After collection and archive/payload verification,
the two task-created remote `work` trees and the remote assembled-jar copy were
removed, recovering about 3.4 GiB. Input archives and an evidence-location note
remain there. Preexisting SDKs, JDKs, Docker images and unrelated files were
preserved. No installation or paid infrastructure was used.
