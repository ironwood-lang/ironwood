<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native benchmark examples

Read [the benchmark guide](../../docs/BENCH.md) for timing semantics, warmup,
percentiles, and ownership. Each program prints a report and exits 0.

With `ironwoodc` on PATH, run `./compile.sh`, `./link.sh`, and `./run.sh`.
The scripts compile with strict missing-free diagnostics and link at `-O3`.
`run.sh` uses small deterministic smoke workloads and checks counts/checksums;
latencies vary by machine and are not compared to fixed thresholds.

| Executable under `target/` | Workload | Optional arguments and defaults |
| --- | --- | --- |
| `SleepBenchmark` | A 1,000 ns busy wait | warmup `1000000`, measurements `2000000` |
| `MathBenchmark` | Repeated arithmetic, retaining a checksum | warmup `1000000`, measurements `9000000` |
| `BubbleSortBenchmark` | Fill and bubble-sort 60 integers, retaining their sum | warmup `1000000`, measurements `10000000` |
| `IntMapBenchmark` | Insert, look up, and remove keys from `ironwood.ds.IntMap` | warmup `0`, measurements `3000000`, initial capacity `1000000` |
| `NanoBenchExample` | A 1,500 ns busy wait using the minimal accumulator | measurements `1000000` |

Pass nonnegative iteration counts and a positive map capacity. The arithmetic and
map examples require warmup plus measurements to fit in an int. The map repeats
warmup for each phase. The sort reuses one local array, and all benchmarks reclaim
their storage outside the measured operation. Native optimization can simplify
arithmetic or sorting work; these examples are demonstrations rather than promises
of equivalent machine instructions across languages or compilers.
