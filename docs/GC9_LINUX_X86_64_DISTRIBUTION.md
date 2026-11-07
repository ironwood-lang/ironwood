<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC9 supported-target and distribution qualification

2026-11-07. **GC9 is complete within the focused target and distribution bounds
below.** Current physical Linux x86-64 execution closes the initial hardware
gate. Native executables, managed images, SDK inputs, local host packages,
restricted relocated IDKs and a three-target assembled jar pass on every official
target. No target was removed or qualified through cross-compilation alone.
GC10, self-hosting and I1/I2 were not started; no release or publication occurred.

Read the [initial implementation record](GC9_IMPLEMENTATION.md),
[pre-change plan and continuation review](GC9_QUALIFICATION_PLAN.md),
[completion manifest](gc-evaluation/gc9-native-distribution.json) and
[decision D328](DECISIONS.md#d328---complete-gc9-supported-target-and-distribution-qualification).
The initial manifests and GC8 records retain their original measurements and
limits. They are not retroactively relabeled as current physical-host results.

## Execution, source and budgets

The maintainer authorized `x86host` SSH work inside `~/temp/Ironwood-GC/`.
Both canonical roots and origin fetch/push URLs were verified. Local work
continues from `e5cabbed` on `codex/bdwgc-integration-plan`. The remote checkout
remains at old GC1 `e113c12`, with its two existing script edits preserved.
No branch, remote, history or unrelated cache was changed on that host.

Exact local `e5cabbed` sources were archived into ignored
`workspace/gc9-native-20261107/source/`, with archive and all 5,278 file hashes
verified. This is source staging, not a Git checkout/worktree. The manifest
records the three later code/configuration overlays: the exact native SDK
profile, host smoke PATH/version checks and host package VERSION handling.
An inherited parent Git revision in older probe reports is not the tested source
identity. A second source staging directory isolates IDK builds from native
compiler output. Compiler/runtime implementation remains the initial GC9 code;
no runtime, backend, shared analysis or LLVM-pass implementation changed here.

`x86host` is physical Intel Xeon E-2288G hardware with 8 cores/16 threads,
about 16 GiB RAM, Ubuntu 18.04.6, Linux 4.15.0-188 and glibc 2.27. No
virtualization is detected. Its inherited affinity covers eight logical CPUs;
no pinning, governor or host configuration was changed. Native tools use cached
Conda Java 21.0.10-internal, LLVM 23.1.0 and pinned lld 23.1.0. Matching producer
Javadoc uses the verified Temurin 23.0.2+7 binaries. Their SHA-256 identities are
recorded, not inferred from a version string.

The cached Linux development image supplies missing IDK preparation inputs,
including Python 3.14.7, TLS and Conda-pack. A task-owned, network-disabled
container adds the complete cached pinned lld package and builds the IDK through
the real recipe. It does not modify the cached image or shared toolchain. The
finished IDK is extracted, relocated and executed on the bare Ubuntu 18 host,
not only in its build container. All dependencies were reused from verified
caches; ordinary linking and offline SDK verification download nothing.

Every native/compiler/JVM child retains its 60-second deadline and 16 GiB summed
process-group RSS guard. Preparation has a separate 600-second bound;
packaging/extraction orchestration has 900 seconds. The TLS runner retains its
existing inner deadlines, with the outer guard also counting descendants that
create their own groups. Minimal Docker controls additionally impose a 4 GiB
memory cgroup and disabled network; container JVM memory is not inferred from
the Docker client's RSS. Sampled peaks are observations, not exact high-water
measurements. The largest focused native group sampled about 1.56 GiB.

Disk initially had about 95 GiB free. The new-work cap is 16 GiB with a 50 GiB
free floor, checked before staging/package/extraction/copy operations. Recorded
owned output is about 12 GiB and free space remains above 82 GiB before final
cleanup. Removing the verified redundant IDK stage leaves about 85 GiB free
and 9.6 GiB of owned evidence/artifacts. The temporary build container is removed
and the ARM VM is restored to its initially stopped state. Prior checkouts, SDKs,
archives, caches and human edits remain intact.

## Completed target and artifact matrix

| Target | Direct execution | Native executable | Managed image | Host package / IDK | Assembled jar |
| --- | --- | --- | --- | --- | --- |
| macOS arm64 | Native Apple M5, macOS 27.0.1 | Initial GC9 refreshed O0-O3 controls pass | Real JVM/reentry, protection and independent images pass | Focused host 20 / IDK 28 stages, scalar 32 controls, local TLS pass | Current three-target jar loads; altered target metadata refuses |
| Linux arm64 | Matching ARM hardware through Apple VZ, Ubuntu 24.04.5 | Initial GC9 O0-O3 controls pass | Real JVM/reentry and independent images pass | Focused host 24 / IDK 33 stages, scalar 32 controls, local TLS pass | Current three-target jar loads; altered target metadata refuses |
| Linux x86-64 | Physical hardware, Ubuntu 18.04.6 | Current O0-O3 controls and deployed binaries pass | Current real JVM/reentry and independent images pass | Focused host 24 / bare-host IDK 33 stages, scalar 32 controls, local TLS pass | Same three-target jar loads; altered target metadata refuses |

The host smokes were rerun on all three targets after the host PATH correction.
ARM native/IDK implementation evidence remains valid: production code did not
change. The added version assertion is also checked against the unchanged ARM
archives, their compiler VERSION resources and actual recorded launcher output.
IDK and overridden native-host versions agree at `0.6.2-gc9-local`; default ARM
host packages agree at `0.6.2-beta`.

| SDK profile | Exact configuration SHA-256 |
| --- | --- |
| macOS arm64, Apple SDK 27.2 | `f6cdb6e12017ba8d203b7ab46c4f870585e4860c7f5de6ea88bad1c0d60b78ab` |
| Linux arm64 | `d8434872c7bd54595bc73e03ec9b245dc4c58748f328d2854b7e6ee030120912` |
| Linux x86-64, native-host compiler package | `5e536f53ea4a5a695f0176a8133b4168443d09225521e13f0881ba1d4a3ef347` |
| Linux x86-64, cached IDK toolchain | `b147ad665f5c5639127bf333342034717793c4e9f53d52680597a39069e0b827` |

The current native-host rebuild retains exact sysroot receipt
`f1dcba2c3ef87eec42905fdd16aab8e399919c7b565e615aa027bf3da1dddb68`.
The IDK uses its separate exact receipt `82573c10a11db164255da8c2d7fe5a254e46db1e8771866b84c6a1f63ad4a070`.
Both are executed directly. The stale `97dad8a3...` SDK is not reused or silently
accepted; its historical GC8 record remains unchanged. Full collector, adapter,
root-reader object/source, runtime/header, prepared-pass and tool hashes are in
the manifests. No target-independent configuration digest or unknown SDK profile
was introduced.

## Native and foreign qualification

The current native-host source/SDK passes these focused selections:

| Selection | Stages | Scope |
| --- | ---: | --- |
| Layouts | 13 | Actual C/LLVM objects, arrays, Strings, inheritance/generated fields, sidecars, invalid metadata and allocation failure |
| Roots | 212 | O0-O3 optimized callers, statics/init, pending/deferred values, exact locations and invalid map/unwind/boundary controls |
| Lifetime/exception owners | 173 | O0-O3 real exceptions, publication, pending results, emergency/preparation/OOM and corrupt owner/PC/slot controls |
| Suspended JVM segments | 34 | O0-O3 checked-JNI A/Java/A and A/Java/B/Java/A; saved state and boundary refusals; O3 transition control/disassembly |
| Ordinary integrated programs | 57 | O0-O3 collection, general defer, resources, pools, no automatic collection, allocation/preparation/construction failure |
| Managed objects / callbacks | 9 / 9 | O0-O3 generated images and real JVM consumers, anchors and registrations |
| Independent production images | 4 | Separate image heaps, roots, anchors, counters and transition chains |
| Current SDK input integrity | 16 | Class/archive parity, ABI/source/configuration and extra-payload refusal |
| Java 23 critical/JNI fallback | 4 | O3 supported critical path and forced JNI |
| Carrier/JNI completion | 14 | O3 carrier graph, exact exports/imports, actual collector and JNI cleanup |
| Preparation/package refusals | 12 | Missing/altered collector, root source and manifest before staging success |

Layout controls check 65,536 numeric pointer lookalikes and recover from 120
actual exact-mark-stack overflows at 4,096 entries. The retained development
probe labels are not a replacement for normal output qualification: ordinary
programs exercise roots 2 / owners 1, and actual produced/loaded bridges exercise
roots 3 / owners 2. Six exact compiler selections retain layout/effect/owner,
versioned foreign-context, configuration-profile and legacy-deallocation
refusals. A direct raw shared-IR control refuses before toolchain/input access.
Unsupported foreign lifetimes and raw library admission were not bypassed.

Actual ELF inspection checks ELF64/little-endian x86-64 layouts, PIE/ASLR,
relocations and image extents, retained `.data.rel.ro` maps, GNU RELRO and unwind
headers/frames. Production exports are exactly the two explicit JNI entry
points; collector/adapter symbols remain private and have no accidental imports.
Private support lookup uses `$ORIGIN` and eager binding, without a development
LLVM prefix. The source/SDK hashes bind actual read code and payloads.

Class/archive executables run outside the checkout and IDK with environment
cleared and only documented native dependencies. Both package modes also execute
through the actual pinned glibc 2.17 loader/libc/libm and verify resolution on the
current kernel. This is not an assertion about an untested old Linux kernel.
A deployed executable and the assembled managed jar also run in the cached
JVM-only scratch image, whose inventory has no ambient libstdc++/libgcc support
or compiler toolchain. The delivered private support closure supplies them.

## Packaging, failures and redistribution

Real `package.sh` and `package-idk.sh` routes produce the accepted archives.
First-launch relocation and subsequent launches run from paths containing
spaces. IDK commands clear development Java/LLVM/GC/TLS/support selections and
restrict PATH to `/usr/bin:/bin`. The separately copied SDK payload inventories
and provenance remain byte-identical across relocation. Bundled Java/compiler,
LLVM/pass, standard-library/testing archives, source/class/archive reconstruction,
collection, exceptions/defer/resources and representative library use pass.

Three issues discovered during qualification were corrected or rejected:

* The new host smoke initially removed the externally supplied lld from PATH.
  Host checks now retain explicit external-tool PATH while clearing compiler/SDK
  overrides. The recorded missing-linker case refuses. IDK isolation stays
  restricted, and all three host smokes pass afterward.
* Host packaging copied repository VERSION despite an explicit build override.
  It now copies the built compiler's authoritative VERSION resource. The focused
  smoke asserts version output agrees; the rebuilt native archive passes, and
  unchanged default ARM/IDK labels agree with their compiler resources.
* Preparation initially copied only the lld binary/receipt. Conda-pack correctly
  refused incomplete package contents. The complete pinned cached package was
  copied into the task-owned container before the accepted IDK build. Root-owned
  intermediate build archives needed read permission for the separate host
  producer; shipped archives already run correctly under their extracting user.

A minimal-image fixture with a nonexecutable temporary mount correctly failed
mapping its library before JNI entry. The accepted retry uses an explicitly
executable native cache; no loader fallback, metadata check or lifetime rule was
relaxed. Docker setup warnings are captured separately from strict empty checked
JNI stderr. This filesystem requirement does not add steady-state bookkeeping.

Each IDK's 32 scalar-only compatibility cases cover initialization paths even
when source APIs return only scalars. Stale/missing/altered metadata, SDK inputs,
pass binary/manifest and assembled target configuration fail before admitted
execution. Allocation and preparation failures do not cause automatic or partial
collection. Native deadlines remain unchanged after the earlier Rosetta timeout.

Bare-host relocated TLS echo and class/archive wget local HTTP/HTTPS fixtures
pass, including unreachable TLS with an absent SDK, static OpenSSL closure and
baseline audits. No external service is required. Final staged audits compare
289 first-party/derived source files and all five provenance records. GC/TLS/
support SDK checks retain actual libraries, corresponding unmodified source,
recipes, manifests, licenses and notices. Paired jars carry full collector inputs
and Linux GCC support/source obligations. OpenSSL/CA and existing distribution
obligations remain intact. Executable deployment fixtures are separately paired
with their exact GC SDK source/notices/configuration.

The same pinned Temurin 23.0.2+7 producer/Javadoc binaries generate common content.
Strict assembly preserves all three exact target configurations. The assembled
jar SHA-256 is `dd99206401639c9900e1a91b2319ef44a5d70cc9ca36ce3b6d057a1e3d361c2e`.
It executes handles, retain/release, registration and explicit collecting JVM
reentry on every target, with nearby target-configuration mutation refusals.
No common-content check was weakened.

## Commands, artifacts and handoff

The initial report records the ARM/native selections and distribution commands.
The completion manifest binds exact source overlays, tools, SDK manifests,
archives, individual report hashes and bounded command records. Current native
selection commands use the staged source and fresh output directories:

```sh
python scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --target linux-x86_64 --cache "$VERIFIED_CACHE" --jobs 4
python scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --verify
python scripts/test-gc-roots.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT"
python scripts/test-gc-lifetimes.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT"
python scripts/test-gc6-segments.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --diagnostics --output "$OUT"
python scripts/test-gc-integrated.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case integrated --case oom --case resources --case no_collect --case pools --output "$OUT"
python scripts/test-gc6-carriers.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --level O3 --output "$OUT"
IRONWOOD_VERSION=0.6.2-gc9-local IRONWOOD_GC_HOME="$SDK" IRONWOOD_DIST_DIR="$DIST" bash scripts/package.sh
bash scripts/test-package.sh "$HOST_ARCHIVE" --gc9 "$OUT"
bash scripts/test-idk.sh "$IDK_ARCHIVE" --gc9 "$OUT"
ironwoodc --java-bridge-assemble -o assembled.jar macos/bridge.jar arm64/bridge.jar x86/bridge.jar
```

Native setup/package/IDK/TLS durations and sampled peaks are recorded separately.
The full qualification remains focused; no unfiltered compiler suite, hosted
workflow, release package publication, tag or push ran. Final diff, license,
tracked-file, shell/Python syntax, source/profile/version and status-link checks
pass. Local commits contain only this milestone's changes. The old remote
checkout stays deliberately dirty with its original human edits.

No GC9 exit criterion remains open within these recorded scopes. Preserve
BDWGC 8.2.12/USE_MARK_BYTES=1, explicit synchronous eager collection, allocation
that never collects, exact descriptors, nonmoving statepoints and fail-closed
admission. Ordinary roots 2 / owners 1 and managed schema 2 / objects-gc-v1,
roots 3 / owners 2 stay unchanged. D051 and D132/D133 remain mandatory; no new
hot-path maintenance or performance claim is introduced by this continuation.

GC10 receives the bounded acceptance/documentation handoff from the plan, with
these target/toolchain/OS bounds and unsupported arm64e/foreign lifetimes intact.
It requires separate work. This qualification does not authorize self-hosting,
I1/I2, a broader JDK matrix or a compiler-capacity claim.
