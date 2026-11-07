<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC9 target and distribution qualification checkpoint

2026-11-06. **GC9 is incomplete.** Linux arm64 now has an implemented ELF root
reader, direct matching-hardware native/JVM evidence and passing local compiler
package/IDK paths. macOS arm64 passes the refreshed focused native and
restricted distribution checks. Current Linux x86-64 hardware and distribution
qualification remains blocked: this session has Rosetta, without an available
authorized matching-hardware endpoint. Historical physical GC8 results do not
close that gap. No release, push, tag, GC10, self-hosting or I1/I2 work occurred.

Work starts on local `codex/bdwgc-integration-plan` at `e16152d4`, following
`04f09a36`. The checkout root and both canonical origin URLs were verified.
There were no initial human changes or subsequent history changes. See the
[pre-change matrix/review](GC9_QUALIFICATION_PLAN.md), [decision D327](DECISIONS.md#d327---admit-linux-arm64-and-harden-local-gc-distribution-inputs),
[verification manifest](gc-evaluation/gc9-verification.json) and
[artifact manifest](gc-evaluation/gc9-artifacts.json). Large logs, SDKs, native
images, jars, archives and full source inventories remain ignored in
`workspace/gc9/`. The manifests record their hashes, rather than committing them.

## Target and input matrix

| Target | Execution and tools | Native executable | Managed image / distribution |
| --- | --- | --- | --- |
| macOS arm64 | Native Apple M5, 32 GiB, macOS 27.0.1 (26A434); LLVM 23.1.0; Oracle Java 21.0.1 bootstrap; Conda OpenJDK 21.0.10 IDK | Refreshed O0-O3 roots, owners and exception checks pass; actual protected Mach-O maps | Real JVM reentry and independent images pass; relocated host package and IDK, scalar refusals, local TLS/wget and two-target assembled jar pass |
| Linux arm64 | Apple VZ `armvm`, matching ARM hardware, 6 CPUs / 8 GiB; Ubuntu 24.04.5 container, Linux 6.8.0-117-generic aarch64; cached Conda Java 21.0.10-internal / LLVM 23.1.0 | Direct O0-O3 roots, owners, exceptions, bounded failures and ordinary integration pass | Real JVM callback/suspended segments, independent images, restricted host/IDK paths, scalar refusals, local TLS/wget and assembled jar pass |
| Linux x86-64 | Same VM with Rosetta; cached x86 toolchain, Java 21.0.10-internal / LLVM 23.1.0 | Translated O0-O3 root controls and compiler proofs pass on the earlier local implementation | Provisional package/IDK builds only. Host smoke reaches native class/archive execution and glibc 2.17 execution, then bridge production exceeds 60 seconds. No current physical qualification or final three-target assembly |

Both Linux toolchains use their exact pinned glibc 2.17 sysroot receipts. Image
IDs, actual Java/LLVM/linker payload hashes and source inventories are in the
manifests. Matching pinned Temurin 23.0.2+7 producers are used for common
Javadoc content in assembly. Linux arm64 also passes the existing Java 23
critical-call and forced-JNI controls. This does not claim a new complete P6
JDK/target matrix.

Current rebuilt SDK configuration identities are:

| Target/profile | Configuration SHA-256 |
| --- | --- |
| macOS arm64, exact Apple SDK 27.2 | `f6cdb6e12017ba8d203b7ab46c4f870585e4860c7f5de6ea88bad1c0d60b78ab` |
| Linux arm64, exact cached sysroot receipt | `d8434872c7bd54595bc73e03ec9b245dc4c58748f328d2854b7e6ee030120912` |
| Linux x86-64, cached toolchain, translated preparation | `b147ad665f5c5639127bf333342034717793c4e9f53d52680597a39069e0b827` |

Apple SDK settings hash remains `82165b15ee21419d4cffaba8895797391b908ac9499ba70f561f3e1bd16758d6`.
Linux arm64 receipt hash is `2cbb493daf6f0c064e728723fb6341a42f6d32e6c5a09d0fe0fdd7e1c2aad1a4`;
the cached x86 receipt is `82573c10a11db164255da8c2d7fe5a254e46db1e8771866b84c6a1f63ad4a070`.
The historical physical-host profile and its distinct receipt remain recorded.
Its old SDK correctly fails current source freshness after the root-reader
change. No physical-host replacement digest was fabricated.

## Implemented changes and preserved contracts

Linux AArch64 now uses the GNU unwind interface with actual CFA/SP, return PCs,
FDE function identity and DWARF x29 saved state. The existing bounded frame and
stack-map validation covers ordinary collection and suspended ABI-3 segments.
Native discovery requires the exact AArch64 Linux triple, little-endian 64-bit
layout and matching sysroot receipt. The production bridge gate opens only after
the direct arm64 controls pass. arm64e remains unsupported.

Actual Mach-O inspection found that retaining a custom `__LLVM_STACKMAPS`
segment left maps writable. Object preparation now places their section in
`__DATA_CONST`, preserving contents, relocations and executable instructions.
Startup requires `SG_READ_ONLY`; a forked actual store faults under dyld's
post-fixup protection. A re-signed image without the flag is rejected by this
macOS dyld before initialization, or by runtime validation on systems admitting
that image. The runner accepts only those fail-closed outcomes.

The store probe changed a C frame enough to expose compact-unwind coalescing of
`main` and the native boundary. Startup now validates retained
`LC_FUNCTION_STARTS` and caches the declared boundary's exact code extent.
Stripped function starts and malformed overflowing ULEB data refuse before
collection. Managed return-PC/function matching remains exact; this does not
skip unknown native frames. ELF keeps its exact FDE boundary rule.

Restricted relocation exposed cold distribution faults and produced these fixes:

* Linux ordinary links now use the already pinned private GCC support SDK,
  explicit sysroot/configuration and relative `$ORIGIN` lookup. This removes
  their development-toolchain RPATH. Preserve the adjacent manifest-identified
  support/source directory with deployed executables, as for shared images.
* IDKs prepare the unchanged first-party nonmoving LLVM pass once. Its manifest
  binds source, payload, host and LLVM version; relative lookup finds the
  relocated bundled LLVM library. Missing or altered payload/manifest refuses
  linking. No host C++ headers or dependency download is needed at IDK link time.
  Source/host packages still need documented external C++ development inputs.
* Pin lld 23.1.0 for Linux IDKs, resolve selected TLS/support SDK symlinks before
  copying, and restore exact producer sysroot receipt bytes after Conda-pack.
  These receipts are not Conda-unpack relocation inputs. SDK checksums and
  provenance remain unchanged after relocation.
* Preserve the managed macOS deployment floor of 14.0 in the pass, target probe,
  JNI adapter and final TLS/native link. A component's older floor cannot lower
  the managed image requirement. Explicit invalid SDKROOT/DEVELOPER_DIR still
  refuses. No execution on macOS 14 is claimed by this newer-host record.
* Add optional focused `--gc9` package/IDK smoke selectors, preserving the default
  release checks. Correct the macOS Bash 3 empty-array expansion in the new host
  selector; the skipped-check attempt was discarded and the corrected run passes.

BDWGC stays 8.2.12 with USE_MARK_BYTES=1. Collection remains explicit,
synchronous, eager and nonmoving; allocation never collects. Ordinary roots 2 /
owners 1 and managed schema 2 / objects-gc-v1, roots 3 / owners 2 remain unchanged.
D051 and D132/D133 remain in force. No finalizers, managed-free proofs,
conservative fallback, automatic collection or per-call tracing/TLS bookkeeping
was added. Root changes affect cold startup or explicit collection. Existing O3
reentry disassembly and deterministic transition controls were inspected/run;
this checkpoint makes no new performance percentage or GC8 capacity claim.

## Focused evidence and image audit

Initial Linux arm64 evidence includes 13 layout stages, 212 root stages, 173
lifetime stages, 34 real-JVM segment stages, 9 object stages, 9 callback stages,
4 independent-production-image stages, 16 input-integrity stages and 39 ordinary
integrated/OOM/resource stages. Core roots, exceptions and foreign segments run
at O0, O1, O2 and O3. Layout controls cover inherited/generated fields, object and
array alignment, Strings, runtime sidecars, 65,536 numeric pointer lookalikes
and 120 actual mark-stack overflow/recovery events at 4,096 entries.

The initial SDK/source phase is retained separately in the manifests. After
the Mach-O protection/boundary fix, all three source-bound SDKs were rebuilt.
Final Mac checks include the selected nine-case root/protection selection at all
four levels (17 O0 stages plus 43 O1-O3 stages), malformed boundaries at all four
levels (20 stages), owners/exceptions at all four levels (37), real suspended
reentry at all four levels (34), and independent production images (4).
Linux's unchanged ELF path was rechecked with its final SDK using focused O3
roots (19 stages), real reentry (13), and final native/JVM distribution execution.
The retained broad Linux O0-O3 matrix is not relabeled as a final-profile run.

Positive/negative pairs cover optimized caller roots, statics and initialization,
deferred/pending values, real unwinding, construction publication, bounded OOM,
preparation failures without partial collection, anchors, registration closure,
foreign roots, exact suspended boundaries and JNI cleanup. Corrupt/stale PCs,
slots/functions, ABI/owner versions, absent maps/unwind, writable metadata,
invalid boundaries and unsupported foreign protocols refuse. Focused compiler
proofs retain raw shared-IR, legacy-deallocation and unknown foreign-effect
refusals; managed admission was not bypassed for library smoke tests.

Actual final Mach-O ordinary and managed images contain retained stack maps in
`__DATA_CONST` with flags `0x10`; unwind sections are in executable/read-only
`__TEXT`. Function starts are present and the deployment minimum is 14.0.
The store test proves actual map protection after relocations. ELF tests inspect
actual program headers, relocations, `.data.rel.ro` maps, GNU RELRO and
`.eh_frame`/header coverage, with PIE and repeated ASLR execution. C and LLVM
layout/triple probes agree on 64-bit little-endian data and alignment.

Production export/import audits verify explicit entry-point exports, private
collector/adapter definitions and no collector imports. Independent A/B images
keep heaps, anchors, root tables, counters and transition chains disjoint while
nested A/Java/B/Java/A succeeds. Linux deployed executables have a relative
support RUNPATH and no LLVM prefix dependency. Native runtime dependencies are
libc/libm plus the delivered private libstdc++/libgcc support; macOS uses system
libraries. Generated executables run outside the checkout/IDK without Java or
LLVM. The IDK checks also execute Linux binaries using the actual pinned glibc
2.17 loader/libc/libm on the current kernel and verify resolution. This is not a
claim of execution on an older Linux kernel/distribution.

## Distribution, failure and redistribution checks

Final local host package stages pass: macOS 20, Linux arm64 24. Final IDK stages
pass: macOS 28, Linux arm64 33. Each IDK additionally passes 32 scalar-only
load/metadata controls. Preparation/packaging boundaries each reject missing
and altered collector libraries, root-reader source and manifests (12 controls
per ARM target). Exact-profile compiler controls reject missing/mixed receipts
and unknown targets. Normal link controls reject stale SDK versions/sources and
missing/altered pass binary/manifest. JVM controls reject missing, stale and
mismatched metadata before source initialization, including scalar-only APIs.

Packages are built through `package.sh` / `package-idk.sh`, extracted and moved
to paths containing spaces. The first launcher performs relocation, subsequent
launches agree, and GC/TLS/support SDK checksums match before and after. IDK
commands run under `env -i` with PATH restricted to `/usr/bin:/bin`, with no
checkout Java/LLVM/GC/TLS/support overrides. Apple SDK selection remains explicit.
Bundled Java/compiler/LLVM/pass, standard-library/testing archives,
source/class/archive reconstruction, explicit collection, general defer,
exceptions/resources and real JVM handles/registrations/collecting reentry pass.

Both ARM IDKs also pass the existing local TLS echo and class/archive wget
HTTP/HTTPS smoke paths, unreachable-TLS pruning with an absent SDK, static
OpenSSL closure and baseline audits. No external network service is used.
Actual staged license/provenance audits compare 289 shipped source files and
all five derived-source records; offline SDK verification checks the copied
binary/source/license payloads. SDKs retain unmodified BDWGC source, recipe,
headers, adapter/root/runtime sources, manifests and notices. Paired jars carry
collector source/licenses; Linux pairs retain complete private support binaries,
corresponding GCC source/recipes/notices. OpenSSL/CA and existing other notices
remain present. Ordinary deployed fixtures are paired with their complete GC
SDK source/notices/configuration, separately from runtime dependency lookup.

Assembly initially refuses differing Javadoc bytes from the two Conda JDK
variants. Reproduction with matching pinned Temurin 23.0.2+7 producers succeeds;
no common-content check was relaxed. The resulting macOS/arm64 jar preserves
both exact target configurations and loads on both JVMs with handles,
registrations and collection/reentry. Altered target-specific configuration
refuses on each host. The missing x86-64 cell prevents three-target acceptance.

## Reproduction and bounds

Paths below are checkout-relative. `T` is `macos`, `arm64` or `x86_64`, `SDK` is
`workspace/gc9/T/sdk-qualified`, `LLVM` and `JAVA` are the matrix's matching
prefixes, and `OUT` is a fresh ignored directory. The manifest binds the actual
tool binaries and SDK receipts. Initial broad ARM runs used
`workspace/gc9/arm64/sdk`; their identities and limits remain separate.

```sh
python3 scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --cache workspace/gc-downloads --jobs 4
python3 scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --verify
python3 scripts/test-gc-layouts.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --case layouts --case arrays --case sidecars --case metadata --case failure --case overflow --output "$OUT"
python3 scripts/test-gc-roots.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc-lifetimes.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc6-segments.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --diagnostics --output "$OUT"
python3 scripts/test-gc6-objects.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc6-callbacks.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc6-images.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc-inputs.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --output "$OUT"
python3 scripts/test-gc-integrated.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA" --case integrated --case oom --case resources --output "$OUT"
```

Final Mac root selections add cases `allocate exception pending initialize
capture boundary writable-map readonly-map missing-boundaries`, once with
`--level O0`, then O1/O2/O3. `--case bad-boundaries` runs at every level. Final
Mac lifetime selection is `unwind pending published emergency oom
preparation-corrupt` at every level. Final Linux root selection is `allocate
exception pending initialize capture boundary writable-map missing-map
missing-unwind` with `--level O3`; segment recheck uses `--level O3`.
Use one repeated `--case`/`--level` option per value. Object critical controls
select the cached Temurin 23 prefix and add `--critical --level O3`.

```sh
IRONWOOD_GC_HOME="$SDK" IRONWOOD_DIST_DIR="$DIST" bash scripts/package.sh
IRONWOOD_GC_HOME="$SDK" IRONWOOD_DIST_DIR="$DIST" IRONWOOD_IDK_TOOLCHAIN_HOME="$TOOLCHAIN" bash scripts/package-idk.sh 0.6.2-gc9-local
bash scripts/test-package.sh "$HOST_ARCHIVE" --gc9 "$OUT"
bash scripts/test-idk.sh "$IDK_ARCHIVE" --gc9 "$OUT"
python3 scripts/test-tls-package.py "$RELOCATED_IDK"
ironwoodc --java-bridge-assemble -o assembled.jar macos/bridge.jar arm64/bridge.jar
```

Host smoke uses its documented external Java/LLVM/C++ and Linux support SDK;
IDK smoke clears those selections. Linux packaging uses native VM storage for
Conda extraction, then hash-verifies the copied host archive and removes only
its own stage. VirtioFS extraction that could not reproduce toolchain symlinks
was discarded. Interrupted/overlapping candidates and the early wrapper error
are not acceptance evidence. The tested production source inventory and
artifact hashes, rather than mutable raw log names, identify accepted outputs.

Every GC native/compiler/JVM child retains the existing 60-second deadline and
16 GiB summed process-group RSS guard. Preparation is bounded at 600 seconds;
package/extraction/TLS orchestration at 900 seconds with the same RSS guard
(the TLS runner retains its existing child deadlines). The TLS enclosing guard
also accounts for descendants creating separate groups. Final package creation
took about 62 seconds on Mac and 118 on ARM Linux. Sampled peaks remain below
1.7 GiB. Keep the host 30 GiB free floor, own output budget 20 GiB (about 12 GiB
used), VM 8 GiB free floor and 12 GiB entry reserve for IDK stages. VM native
stages/extractions are cleaned after hash verification; about 15 GiB remains.

Diff, license, tracked-file, shell syntax and affected Python parsing checks
pass. No language grammar or IDE generated content changes. No unfiltered
compiler suite or hosted workflow ran. This is focused qualification, not a
release readiness declaration.

## Remaining exit criteria and bounded handoff

Obtain an authorized matching-hardware x86-64 environment, use its exact current
sysroot receipt and rebuild/revalidate the source-bound SDK. Recheck affected
root/exception/foreign O0-O3 selections, actual executable/shared images,
independent-image behavior, final host package and restricted relocated IDK,
scalar/input failures and affected TLS/support paths. Preserve the 60-second
native deadline; the translated bridge-producer timeout is incomplete evidence,
not a reason to lift it. The provisional x86 IDK predates the final Mach-O/source
profile update and has no passing final restricted smoke result.

Then produce the third host jar with matching producer/Javadoc tools, assemble
all three exact target identities and execute it on all three targets. Audit
final source/licenses/payloads and update the manifests before marking GC9
complete. Keep [D205/D213's matching-hardware rule](LOCAL_TESTING.md#java-bridge-validation-hosts)
and the historical [physical GC8 limits](GC7_GC8_LINUX_X86_64_QUALIFICATION.md).
Only after these GC9 criteria pass may separately scoped GC10 acceptance work
begin. No self-hosting/I1/I2 capacity inference follows from this checkpoint.
