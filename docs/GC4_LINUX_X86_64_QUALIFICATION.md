<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC4 Linux x86-64 native qualification


Historical prerequisite record. GC5 replaces its manual-heap production rules;
see [the current managed contract](MEMORY.md) and [GC5 report](GC5_IMPLEMENTATION.md).

Date: 2026-11-04. **The selected GC4 runtime-owner closure is qualified on native
Linux x86-64. Production GC remains disabled.** This extends
[GC4 runtime lifetimes](GC4_RUNTIME_LIFETIMES.md), with separate
[compact evidence](gc-evaluation/gc4-linux-x86_64-2026-11-04-manifest.json).

## Pre-change review

Baseline: `777a26ff`, preserving GC3 `403742ae` and `6860bdfc`. This follow-up
qualifies the same GC4 experimental runtime on native Linux x86-64. Preserve
production allocation, syntax, mandatory free proofs, native/bridge generation,
D051, D132/D133 and GC2 engine-2/USE_MARK_BYTES. Linux arm64 remains unqualified.
No GC5/GC6 implementation, public collection API or self-hosting is in scope.

Affected consumers are NativeBackend's experimental target/pass build, the
ABI-2 native owner gate, ELF root/image integrity checks and the GC4 bounded
qualification driver. No shared ownership-analysis rule changes are planned.
Keep exact Throwable/sidecar/carrier layouts, live wrapper and emergency
ownership, explicit resource rollback and allocation accounting unchanged.

Pair survival with subsequent reclamation for every exception/anchor graph;
test real nested unwinding, catch/rethrow, deferred captures, pending values,
published construction, OOM/emergency states, resource failures and stale or
missing owner/map coverage. Retain ELF unwind/RELRO refusals and the GC3 controls.
Unknown foreign crossings must remain refused before collection; no JNI or
HotSpot-frame qualification follows from native-only tests.

Focused selection: the GC4 compiler proof, both GC3 compiler proofs, the seven
existing production controls; the complete selected GC4 native matrix at
O0/O1/O2/O3; GC3 Linux roots; optimized noncollecting fingerprints; focused macOS
regression for any shared changes. Finish with license/tracked-file/diff checks.
No unfiltered suite is authorized.

Use only a source export under `workspace/gc4-linux/` inside the authorized
remote task directory. Preserve its existing parent checkout and both modified
scripts byte-for-byte. Reuse pinned installed tools and the qualified GC2 SDK.
Bound the enclosing task directory to 8 GiB, retain 20 GiB free space, retain the
16 GiB process-group RSS guard and 60-second child timeout, and bound outer runs
to 900 seconds. Do not download or rebuild dependencies without establishing
that existing inputs are insufficient.

The host pass build also needs explicit review: the installed Conda Clang has
LLVM headers but its selected GCC runtime lacks C++ headers. A direct build
fails on `<functional>`. Selecting `--gcc-toolchain=/usr` finds installed GCC 11
headers and builds the host module without downloads. This selection is for the
Linux compiler-host pass only; generated programs and the C runtime retain the
qualified SDK/sysroot. Native execution must verify that opt loads the resulting
module and that its exceptional statepoints still produce exact root maps.

## Implemented target support

NativeBackend and the runtime now admit ABI-2 owners for the already qualified
LLVM 23.1.0 Linux x86-64 triple/data layout. Linux arm64 and arm64e remain refused.
The same exact Throwable/secondary/trace/carrier representations, native wrappers,
emergency scopes, acquisition records and native anchors run unchanged. No new
root class, runtime API, allocator mode or source operation was introduced.

The Linux compiler-host pass build selects the installed GCC development
toolchain under `/usr`; its LLVM headers/library still come from the pinned
tool prefix. The native program and C runtime continue using the glibc 2.17
SDK/sysroot. This host-module convention fixes the missing C++ headers without
installing packages, altering dependencies or changing the SDK identity. The
resulting module loads into the real pinned opt in every optimization mode.

The existing ELF/PIE, actual RELRO protection, unwind reconstruction, complete
map digest and owner-coverage checks remain mandatory. The driver adds explicit
ABI-2 controls for stripped managed unwind tables and removed PT_GNU_RELRO
protection. The latter keeps the same section name; startup must check actual
program headers. The former terminates before collection when the walk cannot
cover the managed frame. Neither test invokes an unsupported foreign unwinder.

GC2's engine-2 ABI, configuration SHA-256
`443a7d4df11b69f991a7f7eebebe5d18948bd43ea19a727ab9e4d1f6f7a500e6`,
USE_MARK_BYTES and all prepared payloads are unchanged. Root ABI 2 and runtime
owner ABI 1 are unchanged. As in the original GC4 checkpoint, the root/owner
components and host pass are separate source-built development inputs. Current
production native/bridge generations remain unchanged.

## Verification

Executed on x86host with the existing JDK 21.0.10-internal, LLVM 23.1.0,
host GCC 11 headers and qualified GC2 SDK. The task-local pinned lld from GC1 is
first on PATH. No dependency was downloaded, installed or rebuilt.

| Focused check | Result |
| --- | --- |
| GC4 native owners | **128 checks**, 32 cases at O0/O1/O2/O3: 80 positives and 48 precollection refusals. |
| Real managed exceptions | Live and two simultaneous wrappers, nested finally/defer cleanup, catch/rethrow identity, primary/secondary order, cycles, trace capture/refill/failure and later zero language/internal live counts pass. |
| Pending and published values | Deferred captures survive reassignment; pending return/yield references survive; successful and failed typed constructors preserve their published child graphs and original exceptions. Clearing stores permits reclamation. |
| Emergency and resources | Actual bounded heap exhaustion performs zero implicit collections. Managed/immortal emergency roots and failure budgets 0 through 4 preserve nested exception order. File-descriptor/native-scratch acquisitions balance on failure, commit and preparation refusal; collection invokes no resource release. |
| Native anchors/carriers | One hundred independent creation/replacement/release cycles per mode reclaim expected graphs. Exact carrier links survive; pointer-shaped native-cookie integers do not retain targets. |
| Refusals | Missing/stale ABI/owner coverage, foreign segments, scratch exhaustion, stale PCs/spills/functions, stripped unwind tables and writable maps refuse before collection. |
| Retained GC3 | **156 checks**, all 39 existing Linux cases in every mode. Source/class/archive parity and GC3 compiler proofs also run in the native generator. |
| Compiler and production controls | **10 exact tests** pass: the GC4 proof, both GC3 proofs and the seven existing GC2/production controls. Allocation/free ABI, safe/unsafe mandatory proofs, O3 allocation failure, trace accounting and pool reuse remain intact. |
| Offline SDK relocation | All 31 payloads verify at a path containing a space with network access forbidden. Eight focused O3 GC4 cases pass using that SDK; stale GC1 identity refuses. |
| macOS regression | Three exact compiler proofs and all **120 GC4 native cases** pass. All four generated program text and stack-map sections are byte-identical to the GC4 macOS baseline. |
| ELF isolation | Each native executable is PIE with protected root maps, native unwind header, no text relocations and no dynamic collector definitions. Its GLIBC requirements remain at or below 2.17. |

O3 Node.read remains **5 bytes** and Main.scalar **48 bytes**, with no calls,
spills, TLS, polls or root registration. The collecting Node.step remains
**16 bytes** with its one 8-byte root spill. All three are byte-identical to
both the retained GC3 control and the previously qualified Linux GC3 object.
No hot lowering changed; there is no new steady-state benchmark claim. The
original GC4 macOS deterministic call/pool fingerprints remain intact.

The main GC4 matrix's largest sampled process-group RSS is **1,070,140 KiB**,
including compiler/pass-build children. GC3 controls peak at **718,936 KiB**.
Both are below the unchanged **16 GiB** guard. Each native-driver child retains
its 60-second limit; the outer task guard enforces 900 seconds. These samples
are not continuous maxima or a resident-capacity claim.

## Source preservation and disk bounds

The authorized remote parent stays on `codex/bdwgc-integration-plan` at
`e113c12f`, with canonical origin fetch/push URLs. Its two modified scripts,
complete diff and status remain byte-for-byte unchanged. No remote Git write,
branch/worktree change, fetch, push or hosted workflow occurred.

The source is a plain export of local GC4 commit `777a26ff`, with five reviewed
overlays: NativeBackend, GcLifetimeTests, the root reader, native probe and GC4
driver. The manifest hashes the archive, every overlay and exact reports, and
records comparison of all other archived files. Raw reports can name the old
enclosing Git parent; the archive/overlay identities identify the executed code.
macOS tar metadata initially added `._` files; those export-only files were
removed and a metadata-free Python archive supplied the final overlay.

The enclosing remote task directory grew from **660,631,552** to approximately
**769,630,208 bytes**, an increase of about **104 MiB**. It remains about **0.72
GiB** total against the **8 GiB** task cap, with over **103 GiB** free against
the **20 GiB** reserve. The guard remained active during focused builds and
native runs. Binaries, SDK copies, source exports, archives and raw logs stay
ignored under `workspace/gc4-linux/`; only compact evidence is committed.

## Reproduction and bounded handoff

Use an exact source snapshot and the existing qualified Linux GC2 SDK. Set the
pinned JDK/tool prefix and place the existing pinned lld first on PATH. Run
compiler rebuilds and native generators sequentially against that source export.

```sh
./scripts/test.sh --test 'GC4 runtime owners require exact exception layouts and effects' \
  --test 'GC3 collection effects and explicit closure fail closed' \
  --test 'GC3 late verification rejects missing strategies and undescribed stack regions'
python3 scripts/test-gc-lifetimes.py --target linux-x86_64 \
  --sdk /path/to/gc2-sdk --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc4-results
python3 scripts/test-gc-roots.py --target linux-x86_64 \
  --sdk /path/to/gc2-sdk --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc4-gc3-controls
```

Use fresh output paths. `--case`/`--level` select bounded follow-ups. The manifest
records exact production selections, hashes, code fingerprints, offline controls,
source preservation and disk measurements. License, tracked-file, Python syntax,
documentation consistency and diff checks accompany the local commit. No full
suite or hosted job ran.

GC4 now covers its selected macOS arm64 and Linux x86-64 runtime closures.
Linux arm64's engine/layout/root-reader gates remain closed. General source
activation, full stdlib/trace API integration, generated bridge/JNI lifetimes,
JVM reentry, shared libraries/two images and final SDK/IDK integration remain
later gates. No JNI path was exercised and no JNI balance or GC6 qualification
is claimed. No unwinding through HotSpot frames is assumed.

The next bounded task remains **GC5 only**, integrating the qualified runtime
into the coordinated language/stdlib cutover. This follow-up did not add public
Memory.collect, remove free/destructors, begin GC5/GC6 or restart self-hosting.
