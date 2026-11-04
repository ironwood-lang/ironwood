<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Isolated GC qualification

These are experimental development fixtures, not the Ironwood runtime or a
language implementation. The evaluated host is macOS arm64 with LLVM 23.1.0,
HotSpot 25.0.4.1 and Rust 1.99.1. Read [the evaluation](../../docs/GC_EVALUATION.md)
for conclusions and unverified boundaries.

From the repository root, choose a new, ignored output directory:

```sh
python3 scripts/gc-evaluation/prepare.py --output workspace/gc-evaluation-run
python3 workspace/gc-evaluation-run/run_experiments.py
python3 workspace/gc-evaluation-run/run_followups.py
```

Preparation downloads checksum-pinned BDWGC 8.2.12 and MMTk source, retains their
source/licenses under the output directory, builds isolated libraries, and
compiles the fixtures. It installs a task-local minimal Rust 1.99.1 toolchain
when needed, without modifying shell startup files. Existing task-local
`cargo/` and `rustup/` directories can be reused with `--rust-tools DIRECTORY`.
Cargo dependency resolution is locked. LLVM tools default to
`/opt/homebrew/opt/llvm@23/bin`; preparation accepts `--llvm-bin`, and runners
accept the matching `GC_PROBE_LLVM_BIN` environment override. Java tools come
from `PATH`.

To validate qualification without repeating timings:

```sh
python3 workspace/gc-evaluation-run/run_experiments.py --qualify-only
python3 workspace/gc-evaluation-run/run_followups.py --qualify-only
```

Runners use fresh child processes, bounded object counts or fixed heaps, a
60-second per-child timeout, and a sampled 16 GiB RSS guard. Very short processes
can finish between RSS samples; recorded sampled peaks are not exact maxima.
Archives, dependencies, binaries, and raw logs remain ignored. Summaries and
hashes for the original run are recorded in
[the evidence manifest](../../docs/gc-evaluation/2026-11-04-manifest.json).

The native throughput workload uses 4096 pooled 64-byte objects and reference
rewrites. The rare-allocation workload allocates once per 4096 operations.
There are five independent processes per case, each with 31 measured batches of
five million operations. Do not interpret batch distributions as individual
operation latency percentiles or compare Java and C as language speed rankings.
The separate allocation probe has approximately 42 ns timer quantization on the
evaluated host; zero measured medians do not mean allocation is free.

`precise_roots.ll` is manually authored LLVM input. Its statepoints are rewritten
after optimization; a fixture-specific Mach-O stack-map reader walks callers
only during collection. The omitted-root case exits before resuming code with
reclaimed objects. The reader is not suitable for production or other targets.
MMTk's fixed-layout binding uses one parked worker and a synchronous calling API.
The compiler-directed BDWGC binding uses the application thread directly.

`large_span_probe.c` tests a 5 GiB virtual allocation and addressing at both ends.
Only two payload pages are explicitly touched. It is not a resident-memory or
self-hosting capacity qualification.

The original fixtures use `MIT OR Apache-2.0`. The MMTk trait binding is adapted
from its pinned DummyVM skeletons under `Apache-2.0`; upstream contributor notices
are retained in `mmtk-binding/COPYRIGHT`, and the complete license is in the
repository's `LICENSE-APACHE`. The dependency source archives preserve their own
licenses. No OpenJDK implementation or test code is imported.

GC0 adds an original `accounting_probe.c` and focused runner, using the prepared
compiler-directed large configuration without changing the production runtime:

```sh
python3 scripts/gc-evaluation/run_gc0.py \
  --evaluation workspace/gc-evaluation-run \
  --output workspace/gc0-run
```

Choose a fresh output directory. The runner verifies the pinned archive hash,
records the reused library/config hashes, compiles with strict warnings, checks
collection-time language/sidecar accounting, type maps and allocation policy,
then runs only the paired pooled/rare-allocation cases. Its children retain the
16 GiB sampled RSS guard and 60-second timeout. It also fingerprints the macOS
arm64 pooled mutation code; it is not a portable production collector adapter.
See [GC0 results and remaining gates](../../docs/GC0_BASELINE_AND_CONTRACTS.md).

GC1 moves the selected engine preparation into the runtime/SDK packaging paths,
without activating it for programs. Use `scripts/prepare-gc.py`,
`scripts/build-gc-fixture.py` and `scripts/test-gc-engine.py` from the checkout
root for the isolated adapter and offline validation. The original evaluation
drivers remain historical experiments. See
[GC1 setup and qualification](../../docs/GC1_ENGINE_PREPARATION.md).
