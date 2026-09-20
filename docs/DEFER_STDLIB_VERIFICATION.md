<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Standard-library defer adoption verification

Date: 2026-09-20. The maintainer separately selected adoption throughout the
standard library, testing library, and their tests after committing the
[example/project migration](DEFER_ADOPTION_VERIFICATION.md). Baseline:
`b6ce3a8b7062f0af40bb7e198632e5b050ec7c79` on `new-defer-keyword`.
Adoption and focused verification completed locally for review without a rebase,
merge, push, or root `README.md` edit at that checkpoint. The migration was
subsequently committed as `bacf9f5`, now on `main`; see
[integration status](DEFER_FINAL_VERIFICATION.md#integration-and-follow-up-status).

## Migration and retained semantics

The migration changes **53 main-library files, one testing-library file, and
nine existing test files**, replacing **329 finally blocks**. The audit covered
all Ironwood sources under `stdlib`, including packages with no eligible sites.
There are **20 retained finally blocks**, all in the main library. Ordinary
immediate frees, destructor field reclamation, and ownership transfer remain
unchanged. Compiler and runtime implementation code is unchanged.

Deferred operations become active at the original protected region's entry.
Their reverse declaration order preserves the original reclamation order.
Explicit smaller blocks preserve cleanup before subsequent work, including
builder returns, filesystem visitor callbacks, and test-result reporting.
The testing library defers `afterEach` before `beforeEach`, retaining teardown
after a failing setup and before reporting pass/failure.

The subsequent [standalone-block audit](STANDALONE_BLOCK_AUDIT.md) removes
redundant wrappers, including builder-return scopes, while retaining the
filesystem callback and test-result boundaries. The descriptions and results
below record the original migration; the audit records the later changes.

Existing private cleanup helpers for rendered strings, localized messages, and
filesystem visitor results remain compiler-proven operations. Deferring them
does not replace their ownership checks with unconditional frees. Captures use
stable locals or `this`; no migrated helper argument is reassigned before its
cleanup. Source review and structural checks confirmed unchanged counts of
free, close, and private release operations in every migrated file.

Retained sites are deliberate:

| Sites | Blocks | Reason |
| --- | ---: | --- |
| `Files.readAllLines`, `Files.walkEntry` | 2 | Inspect completion flags, pending results, and directory-close state at exit. The inner unconditional directory-stream release now uses defer. |
| `Socket`, `ServerSocket`, `SocketDescriptor`, `TlsClient` | 10 | Roll back only incomplete construction, connection, or transfer using the final state of completion flags. |
| `BufferedOutputStream`, `BufferedWriter`, `OutputStreamWriter`, `PrintStream` | 4 | Read delegate fields at exit, select owned/borrowed delegates, or translate close failures into stream error state. |
| `ResolverQuery`, `InterfaceSnapshot` | 4 | Preserve sequential `close(); free` semantics. Separate deferred actions would also attempt free after a close failure. |

The compiled-code audit exposed different exception paths when the last four
sequences were initially split. Their original sequential cleanup was retained,
then the affected networking checks and code comparison were repeated. No
compiler exception-effect or ownership exemption was introduced.

## Focused behavioral verification

Host: macOS 26.6.2 ARM64; Oracle Java 21.0.1; LLVM 23.1.0. No other-platform
execution or exhaustive API coverage is claimed.

The strict build produced both stdlib and testing-library classes and archives
with `--unfreed=error`. The primary selection passed **36/36 tests**. A later
selection passed **6/6**, adding four coverage areas and rechecking two affected
networking areas: **40 distinct selected tests passed**.

The registered stdlib test invokes `scripts/test-stdlib.sh --skip-build` and
checks its results: **172 passed, one expected skip, 173 cases**, plus the
intentional failure-reporting suite and negative assertion-signature check.
This covers the testing framework, pools, collections, benchmarks, and
networking; the other selections cover library behavior outside that runner.

The new [collection rendering regression](../integration-tests/cases/defer_stdlib_rendering.iron)
checks all 21 migrated collection renderers using populated containers. It
checks exact text, three renderings per container, one live returned String,
reclamation of each result, and full container teardown. Class-owned sentinels
are warmed before measuring teardown. It passes against both library versions
and separately passes strict `--unfreed=error` compilation, class linking, and
native execution with exit 42 and empty stdout/stderr.
Existing tests cover exceptional rendering, fresh versus borrowed results,
receiver escape, allocation failures, source/class/archive reconstruction,
stream and filesystem failures, callbacks, and mandatory negative safety cases.

The benchmark selection includes its 98 managed allocation-failure checks.
Host-networking contracts passed 393 managed failure boundaries without live
reachability probes. TLS passed 139 local scenarios, including native faults,
allocation failures, and its existing allocation/benchmark assertions. These
are focused library checks, not an unfiltered compiler or platform suite.

Reproduce the primary selection from the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm
./scripts/test.sh \
  --test 'standard-library testing module reports deterministic native results' \
  --test 'standard-library test reporting reclaims temporary allocations' \
  --test 'benchmark library reports and reclaims native results' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'deferred standard-library collection rendering preserves text and live counts' \
  --test 'everyday StringBuilder operations match Java' \
  --test 'everyday StringBuilder ownership and allocations are preserved' \
  --test 'everyday StringBuilder allocation failures reclaim temporary storage' \
  --test 'standard-library caller-owned results teardown and rollback run at O3' \
  --test 'Object and collection rendering reclaim temporary text at O3' \
  --test 'everyday String operations match Java' \
  --test 'everyday String ownership and omission boundaries are enforced' \
  --test 'everyday String allocation failures reclaim temporary storage' \
  --test 'floating text overloads match Java and reclaim temporaries' \
  --test 'floating text failures preserve builder state and cleanup' \
  --test 'Throwable rendering preserves typed operations and message ownership' \
  --test 'Throwable rendering matches Java text and reclaims results at O3' \
  --test 'Throwable rendering allocation failures reclaim temporary messages' \
  --test 'Throwable rendering preserves getter failures and receiver escape checks' \
  --test 'Throwable printing survives managed allocation failure' \
  --test 'caller-owned library results survive source class archive and tree-shaking round trips' \
  --test 'U3 streaming ownership and typed native operations are checked' \
  --test 'U3 streaming and failure cleanup run at O3' \
  --test 'U3 construction and result OOM reclaim owned graphs' \
  --test 'U2 paths and whole-file I/O run at O3' \
  --test 'U2 file and path allocation failures roll back at O3' \
  --test 'U5 file tree traversal enforces borrowed visitor callbacks' \
  --test 'U5 file tree traversal controls depth links and cleanup' \
  --test 'native filesystem scratch and resource cleanup survive injected failures' \
  --test 'network address results preserve copied inputs and dependent names' \
  --test 'TCP constructors options and urgent data run at O3' \
  --test 'host networking deterministic native and public contracts' \
  --test 'explicit proxy negotiation and cleanup contracts' \
  --test 'TLS local protocol policy and native cleanup contracts'
```

The follow-up selection was:

```sh
./scripts/test.sh \
  --test 'util compatibility helpers run at O3' \
  --test 'IO and NIO compatibility helpers run at O3' \
  --test 'filesystem mutation and random access helpers run at O3' \
  --test 'Instant epoch and ISO operations match Java' \
  --test 'network address results preserve copied inputs and dependent names' \
  --test 'host networking deterministic native and public contracts'
```

## Paired compiled-code comparison

The pre-migration archive was saved before editing. Both versions were compiled
with the same compiler, LLVM, runtime, fixtures, and O3 settings, selecting the
library through `IRONWOOD_STDLIB_HOME`. The fixture sources are identical within
each pair. Existing process-lifetime test fixtures use `--unfreed=off` for this
comparison; mandatory safety remains enforced, and strict library builds and
negative safety tests are separate checks.

| Fixture under `integration-tests/cases` | Before text bytes | After text bytes | Before / after disassembled instructions | Executable text identical |
| --- | ---: | ---: | ---: | --- |
| `defer_stdlib_rendering.iron` | 124296 | 124296 | 31000 / 31000 | Yes |
| `stdlib_stringbuilder_ownership.iron` | 28232 | 28232 | 6975 / 6975 | Yes |
| `stdlib_rendering_reclamation.iron` | 24136 | 24136 | 5939 / 5939 | Yes |
| `stdlib_throwable_rendering.iron` | 24520 | 24520 | 6034 / 6034 | Yes |
| `StdlibUtilFixture.iron` | 50120 | 50120 | 12458 / 12458 | Yes |
| `stdlib_u3_streams.iron` | 55112 | 55112 | 13645 / 13645 | Yes |
| `tcp_addresses/Ownership.iron` | 45576 | 45512 | 11280 / 11276 | No; 64 bytes smaller |

Text size and instruction counts cover Mach-O `__TEXT,__text`, excluding import
stubs, constants, trace metadata, unwind tables, and debug data. Zero-filled
padding is included in size but not instruction counts. Equality compares actual section bytes,
not just counts or disassembly mnemonics. The six identical pairs also have
matching optimized LLVM function instructions after local value/block renaming
and metadata-ID normalization: respectively **362, 57, 54, 68, 108, and 145**
functions. Renamed `try.landing`/`defer.landing` labels and loop metadata IDs
explain the initial textual LLVM differences.

The networking pair has 77 of 79 matching normalized function bodies.
`InetAddress.getByName` and `main` differ in exceptional cleanup organization
after moving name reclamation around existing catches. Disassembly has five
fewer instructions in `getByName` and one more in `main`; zero-filled padding
shrinks from 456 to 408 bytes, accounting for the remaining 48 bytes of reduction.
The extra exception-taking/rethrow copy
is on an unwind path. Successful paths retain their allocation, resolver,
release, and check operations. Existing startup trace registration has one more
static source entry (2635 versus 2634), without an additional registration call.
This is not a byte-identical networking claim.

Every pair produced matching exit status, stdout, and stderr. The networking
fixture exited 0; the other six exited 42. Existing fixture assertions checked
allocation counts, live counts, returned ownership, and failure cleanup. No
runtime action stack, callback allocation, or registration mechanism was added.
This evidence covers these representative closed-world programs, not every
possible program using the library or a new timing acceptance matrix.

### Reproduction

Generated artifacts remain under ignored `workspace/defer-stdlib/`. To recreate
the baseline archive without changing branches, export only the baseline
library sources and compile them with the current unchanged compiler. In Bash:

```sh
area="$PWD/workspace/defer-stdlib"
mkdir -p "$area/before-source" "$area/before/lib" "$area/after/lib"
git archive b6ce3a8 stdlib/src/main/ironwood | tar -x -C "$area/before-source"
sources=()
while IFS= read -r source; do sources+=("$source"); done < <(
  rg --files "$area/before-source/stdlib/src/main/ironwood" -g '*.iron' | sort
)
java -jar compiler/build/ironwoodc.jar "${sources[@]}" --unfreed=error \
  --source-path "$area/before-source/stdlib/src/main/ironwood" -d "$area/before/classes"
java -cp compiler/build/ironwoodc.jar ironwood.compiler.IronJarMain \
  --create --file "$area/before/lib/ironwood-stdlib.ironjar" \
  --license LICENSE --license LICENSE-MIT --license LICENSE-APACHE \
  --license LICENSES/GPL-2.0-only.txt --license LICENSES/Classpath-exception-2.0.txt \
  --license LICENSES/Unicode-15.0.txt --license LICENSES/MPL-2.0.txt \
  --license docs/LICENSE_MECHANICS --license docs/THIRD_PARTY_NOTICES.md \
  --license docs/SOURCE_PROVENANCE.md "$area/before/classes"
cp compiler/build/ironwood-stdlib.ironjar "$area/after/lib/ironwood-stdlib.ironjar"
```

For each fixture in the table, set `fixture` to its relative path and `main` to
`Main`, except `fixtures.util.StdlibUtilFixture` for the utility fixture and
`AddressOwnership` for the networking fixture. The following is the comparison
pipeline used for each pair:

```sh
fixture=defer_stdlib_rendering.iron
main=Main
name="${fixture##*/}"
name="${name%.iron}"
for variant in before after; do
  out="$area/code/$name/$variant"
  mkdir -p "$out"
  export IRONWOOD_STDLIB_HOME="$area/$variant"
  java -jar compiler/build/ironwoodc.jar "integration-tests/cases/$fixture" \
    --unfreed=off -d "$out/classes"
  java -jar compiler/build/ironwoodc.jar --link -cp "$out/classes" \
    --main-class "$main" --unfreed=off -O3 \
    --emit-llvm "$out/program.ll" -o "$out/program"
  "$IRONWOOD_LLVM_HOME/bin/llvm-as" "$out/program.ll" -o "$out/program.bc"
  "$IRONWOOD_LLVM_HOME/bin/opt" '-passes=default<O3>' -inline-threshold=1000 \
    -enable-partial-inlining -S "$out/program.bc" -o "$out/optimized.ll"
  "$IRONWOOD_LLVM_HOME/bin/llc" -O=3 --relocation-model=pic -filetype=asm \
    "$out/optimized.ll" -o "$out/program.s"
  "$IRONWOOD_LLVM_HOME/bin/llvm-size" -A "$out/program" > "$out/size.txt"
  "$IRONWOOD_LLVM_HOME/bin/llvm-objdump" --disassemble --no-show-raw-insn \
    "$out/program" > "$out/machine.txt"
  "$IRONWOOD_LLVM_HOME/bin/llvm-objdump" --section=__text --full-contents \
    "$out/program" > "$out/text-dump.txt"
done
unset IRONWOOD_STDLIB_HOME
```

Compare section hex bytes independently of the dump filename/address column.
For native execution, the streams fixture additionally takes a disposable file
path and reads `Q` from stdin; all other fixtures take no arguments. The captured
`stdout`/`stderr`, section `text.bin` files, emitted and optimized LLVM, assembly,
and disassembly remain alongside each executable. `code-results.json` records
sizes, instruction/function counts, SHA-256 hashes, and native results.

`focused-tests.log` and `followup-tests.log` record the two exact selections;
`build.log` records the initial strict build, and `strict-regression.log` records
the strict collection regression compilation and link. The final networking comparison
is in `code-network-final.log`; the other comparisons are in
`code-comparison.log`. `git diff --check`, license audit, changed documentation
links, and added-text policy checks passed. The Git index and baseline commit
were unchanged at that checkpoint.
