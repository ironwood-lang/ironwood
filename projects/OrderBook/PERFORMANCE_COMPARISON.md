<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Reproduce the local performance-branch comparison on Linux

Linux performance of the final candidate is unverified. The local Apple M5
measurements and cross-generated x86 assembly in
[the stage report](../../docs/PERFORMANCE_IMPROVEMENTS.md#stage-4-plan-final-code-validation-and-linux-handoff)
do not establish Linux behavior. Use a quiet Linux host and build both revisions
there with the same Java 21 and LLVM 23 installation, `-O3 -march=native`, no PGO.
The runner needs Python 3.6 or newer and no extra modules. These Bash commands
use fresh directories and do not change an existing checkout or its branches.
An Ubuntu 18-era shell/Python is sufficient for the runner; the chosen Java/LLVM
distribution must independently support the host's libc and kernel.

## Offline transfer from the coordinator, after the final commit

The branch stays local and unpushed. Run this in the canonical macOS checkout
**after committing stage 4**. The compact incremental bundle carries the commits
and changed objects after the fixed baseline, including the new scripts and
instructions. It requires an existing repository containing baseline
`2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e`; it cannot be cloned as a standalone
bundle. Baseline objects plus this bundle reconstruct both pinned source trees,
including their license texts and notices. Ignored evidence and native binaries
are not transferred.

```bash
set -euo pipefail
test "$(git branch --show-current)" = perf-improvements
test -z "$(git status --porcelain)"
stage4_baseline=2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e
stage4_revision=$(git rev-parse refs/heads/perf-improvements)
git merge-base --is-ancestor "$stage4_baseline" "$stage4_revision"
stage4_directory="$PWD/workspace/perf-improvements/stage4"
stage4_name="ironwood-stage4-$stage4_revision.bundle"
test ! -e "$stage4_directory/$stage4_name"
git bundle create "$stage4_directory/$stage4_name" \
    "$stage4_baseline..refs/heads/perf-improvements"
git bundle verify "$stage4_directory/$stage4_name"
git bundle list-heads "$stage4_directory/$stage4_name"
printf '%s\n' "$stage4_revision" > "$stage4_directory/$stage4_name.revision"
(
    cd "$stage4_directory"
    shasum -a 256 "$stage4_name" "$stage4_name.revision" > "$stage4_name.sha256"
)
```

Copy the bundle, `.revision` and `.sha256` files together using removable storage
or another user-chosen transfer method. No remote service or push is required.
For later branch commits, generate a new bundle with its new revision filename.

## Extract pinned sources and build both variants

Set the four paths below, including the user's existing complete Linux clone.
The baseline availability preflight only reads that clone. If the baseline is
absent, stop and select another clone containing it, or obtain a separate
complete clone/history transfer containing that baseline before retrying. Do not
fetch into or otherwise modify the existing clone as part of this procedure.

Run the rest in the same Bash session. `mktemp` creates a new comparison
directory, printed at the end. `--local --no-checkout` creates a fresh repository
using hardlinks to immutable objects when possible, copying them otherwise.
`--dissociate` removes any inherited alternate-object dependency; `--shared` is
not used. Keep the source clone idle while cloning. Bundle verification and
fetch run only in the fresh repository, with the candidate imported into
`FETCH_HEAD` and checked against the transferred full commit hash before
extracting both source snapshots. Existing branches and working files stay intact.

```bash
set -euo pipefail
bundle='/absolute/path/to/ironwood-stage4-FULL_COMMIT_HASH.bundle'
existing_clone='/absolute/path/to/existing-linux-ironwood-clone'
export JAVA_HOME='/absolute/path/to/jdk-21'
export IRONWOOD_LLVM_HOME='/absolute/path/to/llvm-23'
export PATH="$JAVA_HOME/bin:$IRONWOOD_LLVM_HOME/bin:$PATH"
unset IRONWOOD_RUNTIME_HOME IRONWOOD_STDLIB_HOME IRONWOOD_HOME IRONWOOD_VERSION

(
    cd "$(dirname "$bundle")"
    sha256sum -c "$(basename "$bundle").sha256"
)
baseline=2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e
if ! git -C "$existing_clone" cat-file -e "$baseline^{commit}"; then
    printf 'Missing baseline %s; use a separate complete clone containing it.\n' "$baseline" >&2
    exit 1
fi
candidate=$(cat "$bundle.revision")
[[ "$candidate" =~ ^[0-9a-f]{40}$ ]]
work=$(mktemp -d "$PWD/orderbook-comparison.XXXXXX")
git clone --local --no-checkout --dissociate "$existing_clone" "$work/repository"
git -C "$work/repository" bundle verify "$bundle"
git -C "$work/repository" fetch --no-tags "$bundle" refs/heads/perf-improvements
test "$(git -C "$work/repository" rev-parse FETCH_HEAD)" = "$candidate"
git -C "$work/repository" cat-file -e "$baseline^{commit}"
git -C "$work/repository" merge-base --is-ancestor "$baseline" "$candidate"
printf 'baseline %s\ncandidate %s\n' "$baseline" "$candidate" > "$work/revisions.txt"

mkdir "$work/baseline" "$work/candidate" "$work/binaries"
git -C "$work/repository" archive "$baseline" | tar -x -C "$work/baseline"
git -C "$work/repository" archive "$candidate" | tar -x -C "$work/candidate"
{
    uname -a
    cat /etc/os-release
    lscpu
    java -version
    javac -version
    python3 --version
    for tool in llvm-as opt llc clang; do
        "$IRONWOOD_LLVM_HOME/bin/$tool" --version
        sha256sum "$IRONWOOD_LLVM_HOME/bin/$tool"
    done
    sha256sum "$JAVA_HOME/bin/java" "$JAVA_HOME/bin/javac"
} > "$work/environment.txt" 2>&1

for variant in baseline candidate; do
    (
        cd "$work/$variant"
        export PATH="$PWD/bin:$PATH"
        ./scripts/build.sh
        ./projects/OrderBook/compile.sh
        ./projects/OrderBook/link.sh
        ./projects/OrderBook/test.sh
    ) > "$work/$variant-build-test.log" 2>&1
    cp -p "$work/$variant/projects/OrderBook/target/orderbook-bench" \
        "$work/binaries/$variant-bench"
    cp -p "$work/$variant/projects/OrderBook/target/orderbook-latency" \
        "$work/binaries/$variant-latency"
    cp -p "$work/$variant/compiler/build/ironwoodc.jar" \
        "$work/binaries/$variant-compiler.jar"
done
sha256sum "$work/binaries/"* > "$work/binary-sha256.txt"
printf 'Comparison directory: %s\n' "$work"
```

Check `environment.txt` shows Java 21 and LLVM 23, and both build/test logs
finish successfully before measuring. `set -e` stops on failure; fix the cause
and use a new comparison directory rather than accidentally mixing old outputs.
The build scripts produce native Linux executables and keep each source tree's
runtime and standard-library assets together. Saved Java compiler jars are
portable to Java 21, but are not self-contained native toolchains: compilation
also requires compatible stdlib source/class archives, C runtime sources and
headers, LLVM tools and the host linker/system libraries. The macOS Mach-O
executables in stage evidence cannot run on Linux.

## Paired measurements

Run the following after builds/tests finish and other heavy work has stopped.
The optional `--cpu N` argument uses the existing Linux `taskset` command for
each process. Choose an available CPU from the host's permitted affinity set
and keep the same choice for both variants and all runs. Leave `pin=()` for
ordinary scheduler placement; do not change affinity between comparisons.

```bash
runner="$work/candidate/projects/OrderBook/compare.py"
pin=()
# Optional, after selecting an available CPU: pin=(--cpu 2)
python3 "$runner" "$work/binaries/baseline-bench" "$work/binaries/candidate-bench" \
    --pairs 8 --output "$work/throughput-baseline-first" "${pin[@]}"
python3 "$runner" "$work/binaries/baseline-bench" "$work/binaries/candidate-bench" \
    --pairs 8 --first candidate --output "$work/throughput-candidate-first" "${pin[@]}"
python3 "$runner" "$work/binaries/baseline-latency" "$work/binaries/candidate-latency" \
    --mode latency --pairs 4 --output "$work/latency-baseline-first" "${pin[@]}"
```

Each throughput process uses `8 80`: 8 million warmup operations, then 80 million
measured operations. The printed positive integer is internal measured ns.
Each latency process uses `10000 50000 1000`: 10,000 warmup and 50,000 measured
batches, each with 1,000 eight-operation cycles. That is 80 million warmup and
400 million measured operations. Latency reports include per-batch clock cost,
exclude warmup from statistics and print rounded means/tails. They measure
8,000-operation batches, not individual order latency. The clock calibration,
setup, validation and reporting also execute outside those measured intervals.

The runner alternates process order on every pair. It validates exit status,
strict positive integer throughput output, and latency report counts/units;
records SHA-256 hashes before execution and rechecks afterward; preserves each
stdout/stderr and command in `samples.jsonl`; and writes `metadata.json` and
`summary.json`. A failure stops the run, retains partial evidence and writes
`failure.json`. Existing output paths are rejected. No outlier is discarded.
Latency summaries compare rounded average/minimum/maximum batch times; the six
tail reports remain in every raw stdout. Zero minima from clock resolution are
valid; ratios with a zero baseline are `null`, not omitted samples. These few
pairs provide descriptive results, not confidence intervals or significance.

For short sanity runs use `--warmup 0 --measured 1 --pairs 2` for throughput,
or `--mode latency --warmup 2 --measured 5 --cycles 1000 --pairs 2` for latency,
with separate new output directories. Runner fixtures are available through
`python3 "$work/candidate/projects/OrderBook/test-compare.py"`.

## Optional Linux hardware counters

Keep these separate from timing comparisons. With an existing permitted `perf`
installation, use small groups in separate runs, alternating both binaries.
For example, after selecting an available CPU and an unused output filename:

```bash
taskset -c 2 perf stat -e '{cycles,instructions}' \
    -o "$work/baseline-cycles-instructions.txt" -- "$work/binaries/baseline-bench" 8 80
taskset -c 2 perf stat -e '{cycles,instructions}' \
    -o "$work/candidate-cycles-instructions.txt" -- "$work/binaries/candidate-bench" 8 80
```

Repeat separately with `{branches,branch-misses}` and, if `perf list` supports
them on that CPU, `{L1-dcache-loads,L1-dcache-load-misses}`. Preserve failures,
unsupported-event messages and running percentages. If a group multiplexes,
reduce it; do not interpret scaled, differently multiplexed counts as a precise
load/branch comparison. Whole-process counters include all 88 million throughput
operations plus setup, clocks, validation and output. They do not cover only
the printed 80-million-operation interval. Inspect linked Linux disassembly
with the same LLVM 23 `llvm-objdump -d --no-show-raw-insn` used on both binaries.
Keep conclusions specific to the recorded CPU, compiler, workload and samples.
