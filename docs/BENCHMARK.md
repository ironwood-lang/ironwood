# OrderBook Throughput and Latency Benchmarks

## Linux throughput results: Ironwood vs Java

After 8 million warmup operations, Ironwood completed 80 million measured
operations in 1.011 seconds: 1.32x the throughput of Oracle JDK 25 and 1.51x
that of GraalVM 25.

| Implementation | Elapsed time | Average elapsed per operation | Throughput | Ironwood throughput advantage |
|---|---:|---:|---:|---:|
| Ironwood `-O3` | 1,011,361,526 ns (1.011 s) | 12.642 ns/op | 79.10 million ops/s | baseline |
| Oracle JDK 25 | 1,334,067,437 ns (1.334 s) | 16.676 ns/op | 59.97 million ops/s | 1.32x |
| GraalVM 25 | 1,525,100,964 ns (1.525 s) | 19.064 ns/op | 52.46 million ops/s | 1.51x |

Ironwood used 24.2% less elapsed time than Oracle JDK and 33.7% less than GraalVM.

The ns/op values are total elapsed time divided by operation count. For a
distribution of batch timings, see the [latency benchmark](#latency-benchmark).

## Linux throughput environment

The environment snapshot was recorded on September 11, 2026 at 20:43:56 UTC.
The host ran Ubuntu 18.04.6 LTS with Linux kernel 4.15.0-188-generic, glibc
2.27, and native x86-64 processes.

The processor was an Intel Xeon E-2288G with one socket, eight physical cores,
16 hardware threads, and one NUMA node. Its nominal frequency was 3.70 GHz,
with a reported range of 800 MHz to 5.00 GHz. The `intel_pstate` driver used
the `powersave` governor with turbo enabled. The 900.120 MHz value in the
environment snapshot was an instantaneous sample, not the frequency recorded
throughout the benchmark.

The Java runs used these 64-bit x86-64 runtimes:

- Oracle JDK 25.0.4.1+1-LTS-5, HotSpot Server VM;
- Oracle GraalVM 25.0.4+7.1-LTS-jvmci-b01, HotSpot Server VM with JVMCI.

The GraalVM result is Java running on the GraalVM JDK, not a GraalVM Native
Image executable. The Java sources are compiled with `javac --release 21`,
and the Ironwood benchmark is linked as a native executable with `-O3`.

## The matching engine

OrderBook is a deliberately small price-time-priority matching engine. It
maintains separate bid and ask sides as price-ordered levels, with the best
available price first. Orders resting at the same price execute in FIFO order,
so an earlier order at a price has priority over a later order at that price.

An incoming order matches eligible resting orders on the opposite side at the
resting maker's price. An unmatched limit order remains in the book, while a
market order consumes available opposite-side liquidity without resting.
Orders can be partially filled, reduced, or canceled. The implementation is
single-threaded and uses fixed reusable pools for its orders and price levels.

## Equivalent implementations

The paired implementations are under
[`projects/OrderBook`](../projects/OrderBook/README.md). The five Ironwood and
five Java source files have the same classes, fields, methods, constants,
control flow, benchmark operations, and final validation. They are
line-for-line identical after changing the `.iron` extension to `.java` and
adapting Ironwood's status-returning `int main` to Java's `void main`.

Both languages also provide `LatencyBench`, with identical sample-count checks,
timed collection loops, clock diagnostics, and workload validation. Ironwood
uses `ironwood.bench.Bench` to report results; Java's `LatencyReport` sorts the
measured samples and reproduces the same reporting conventions. Reporting,
native allocation diagnostics, and native cleanup are outside the timed work.

Both implementations construct an order book with capacity for eight orders
and four price levels before warmup begins. Orders and price levels are reused
from those fixed pools, so the measured steady-state workload performs no
allocation.

## Workload

One cycle contains eight operations:

1. Create a buy limit order for 100 units at price 10,000,000,000.
2. Create a buy limit order for 100 units at price 9,900,000,000.
3. Create a sell limit order for 100 units at price 10,200,000,000.
4. Create a sell limit order for 100 units at price 10,300,000,000.
5. Reduce the best bid from 100 units to 50 units.
6. Cancel the best ask at price 10,200,000,000.
7. Submit a market sell for 150 units, consuming the two remaining bids.
8. Submit a market buy for 100 units, consuming the remaining ask.

Every cycle starts and ends with an empty book and full pool capacity. It
consumes six order IDs and produces three matches totaling 250 units. After
the timed work, the benchmark verifies the order count, match count, matched
volume, final execution price, final maker ID, empty book, and full pool
recovery so the optimizer cannot discard the workload.

## Throughput warmup and timing

Run in either implementation's directory:

```console
$ ./throughput.sh 8 80
```

The arguments specify 8 million warmup operations and 80 million measured
operations, equivalent to 1 million and 10 million cycles.

Two `System.nanoTime()` reads bracket the measured workload. Book construction
and warmup happen before timing; validation and printing happen afterward.
Warmup and measurement use the same workload method, with different counts
and starting order IDs.

The script prints elapsed nanoseconds. Divide by 80 million for ns/op, or
divide 80 million by elapsed seconds for operations per second.

## Latency benchmark

Ironwood's mean latency per **1,000-cycle batch (8,000 operations)** was 24.2%
lower than Oracle JDK's and 33.3% lower than GraalVM's on Linux.

| Implementation | Mean batch | Minimum batch | p99 batch | p99.9 batch | p99.99 batch | Maximum batch |
|---|---:|---:|---:|---:|---:|---:|
| Ironwood `-O3` | 102.799 µs | 99.493 µs | 131.224 µs | 146.993 µs | 180.429 µs | 216.611 µs |
| Oracle JDK 25 | 135.617 µs | 132.502 µs | 163.893 µs | 182.132 µs | 206.446 µs | 355.198 µs |
| GraalVM 25 | 154.148 µs | 151.982 µs | 165.493 µs | 194.499 µs | 238.356 µs | 262.085 µs |

### Running the benchmark

```console
$ cd projects/OrderBook
$ ./compile.sh
$ ./link.sh
$ ./java/compile.sh
$ ./latency.sh 10000 50000 1000
$ ./java/latency.sh 10000 50000 1000
```

The arguments specify 10,000 warmup batches, 50,000 measured batches, and
1,000 cycles per batch. These defaults execute 80 million warmup operations
and 400 million measured operations. Use matching arguments for comparisons.

Each batch uses the throughput benchmark's cycle method. Two `System.nanoTime()`
reads bracket the batch; its duration stays well above clock-read cost.
Samples go into a preallocated array after timing. Warmup uses the same path
and is excluded from results. Reporting and validation happen afterward.

Java runtimes:

- Oracle JDK 25.0.4.1, build `25.0.4.1+1-LTS-5`, HotSpot Server VM.
- Oracle GraalVM 25.0.4+7.1, build `25.0.4+7-LTS-jvmci-b01`, HotSpot Server VM
  with JVMCI. This runs Java on the GraalVM JDK, not Native Image.

### Reading latency results

All times describe complete batches, including clock overhead. Each percentile's
`max` is the boundary of the fastest selected fraction of samples; its `avg`
is their mean. Dividing by the batch size cannot give per-operation percentiles.

With 50,000 samples, only five observations remain beyond p99.99; p99.999
rounds to the maximum. Batching can hide short stalls. The workload excludes
external arrivals, queueing, and network delays.

See [BENCH.md](BENCH.md) for the reporting API.

### Raw output

Ironwood output:

```text
Empty interval average (ns): 15.022315
Two clock reads plus loop average (ns): 30.550915
Smallest observed positive clock delta (ns): 12
Cycles per batch: 1000
Operations per batch: 8000
Measured operations: 400000000
Batch latency (clock overhead included):
Measurements: 50,000 | Warm-Up: 10,000 | Iterations: 60,000
Avg Time: 102.799 micros | Min Time: 99.493 micros | Max Time: 216.611 micros
75% = [avg: 102.185 micros, max: 102.575 micros]
90% = [avg: 102.257 micros, max: 102.673 micros]
99% = [avg: 102.451 micros, max: 131.224 micros]
99.9% = [avg: 102.739 micros, max: 146.993 micros]
99.99% = [avg: 102.790 micros, max: 180.429 micros]
99.999% = [avg: 102.799 micros, max: 216.611 micros]
```

Oracle JDK 25 output:

```text
Empty interval average (ns): 16.388843
Two clock reads plus loop average (ns): 34.428202
Smallest observed positive clock delta (ns): 13
Cycles per batch: 1000
Operations per batch: 8000
Measured operations: 400000000
Batch latency (clock overhead included):
Measurements: 50,000 | Warm-Up: 10,000 | Iterations: 60,000
Avg Time: 135.617 micros | Min Time: 132.502 micros | Max Time: 355.198 micros
75% = [avg: 133.781 micros, max: 134.428 micros]
90% = [avg: 134.212 micros, max: 139.035 micros]
99% = [avg: 135.277 micros, max: 163.893 micros]
99.9% = [avg: 135.552 micros, max: 182.132 micros]
99.99% = [avg: 135.603 micros, max: 206.446 micros]
99.999% = [avg: 135.617 micros, max: 355.198 micros]
```

GraalVM 25 output:

```text
Empty interval average (ns): 15.620574
Two clock reads plus loop average (ns): 33.686389
Smallest observed positive clock delta (ns): 12
Cycles per batch: 1000
Operations per batch: 8000
Measured operations: 400000000
Batch latency (clock overhead included):
Measurements: 50,000 | Warm-Up: 10,000 | Iterations: 60,000
Avg Time: 154.148 micros | Min Time: 151.982 micros | Max Time: 262.085 micros
75% = [avg: 152.634 micros, max: 155.453 micros]
90% = [avg: 153.355 micros, max: 158.677 micros]
99% = [avg: 153.870 micros, max: 165.493 micros]
99.9% = [avg: 154.089 micros, max: 194.499 micros]
99.99% = [avg: 154.138 micros, max: 238.356 micros]
99.999% = [avg: 154.148 micros, max: 262.085 micros]
```
