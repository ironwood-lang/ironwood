# OrderBook Throughput and Latency Benchmarks

## Linux throughput results: Ironwood vs Java

For 80 million measured operations, Ironwood completed the workload in
1.011 seconds. It delivered 1.32 times the throughput of Oracle JDK 25 and
1.51 times the throughput of GraalVM 25 in these recorded runs. 
Both Ironwood and Java first completed the same unmeasured warmup pass of
8 million operations before the 80-million-operation measurement began.

| Implementation | Elapsed time | Average elapsed per operation | Throughput | Ironwood throughput advantage |
|---|---:|---:|---:|---:|
| Ironwood `-O3` | 1,011,361,526 ns (1.011 s) | 12.642 ns/op | 79.10 million ops/s | baseline |
| Oracle JDK 25 | 1,334,067,437 ns (1.334 s) | 16.676 ns/op | 59.97 million ops/s | 1.32x |
| GraalVM 25 | 1,525,100,964 ns (1.525 s) | 19.064 ns/op | 52.46 million ops/s | 1.51x |

For the same fixed amount of work, Ironwood used 24.2% less elapsed time than
Oracle JDK and 33.7% less than GraalVM. Each implementation was run several
times in a separate process, never concurrently, and its repeated timings were
very close. The table reports one representative observed result for each.

This is a single-threaded throughput benchmark: it measures how many defined
order-book operations complete per unit of time. The ns/op values divide total
elapsed time by total operations; they are not individual-operation latency
measurements. The [latency benchmark](#latency-benchmark) below records a
distribution of elapsed times for fixed batches of complete cycles.

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

To repeat the recorded throughput workload in either implementation:

```console
$ ./throughput.sh 8 80
```

The arguments are operation counts in millions, not cycle counts. The first
argument performs 8 million untimed operations, which is 1 million warmup
cycles. The second performs 80 million timed operations, which is 10 million
measured cycles.

The benchmark first constructs the book and runs the complete warmup. It then
reads `System.nanoTime()`, calls the same workload method for the measured
operations, and reads `System.nanoTime()` again immediately after that method
returns. Correctness validation and printing occur after the clock stops.

The Java warmup exercises the same code and the same branch pattern that is
later measured. There is no warmup-only path, measurement-only path, or mode
flag. Only the loop bound and starting order ID differ, and order IDs do not
control benchmark behavior. Each cycle restores the same empty-book and
full-pool state, while accumulated statistics are updated but never used to
select a measured branch. Consequently, HotSpot receives 8 million operations
on the actual hot path before timing begins. Alternative error and capacity
exhaustion branches are not warmed, but they are also not executed during the
measurement.

## Reading the throughput result

Each `throughput.sh` invocation runs in a separate process and prints one integer:
the elapsed nanoseconds for the 80 million measured operations. Time per
operation divides that integer by 80 million; throughput divides 80 million by
the elapsed seconds.

## Latency benchmark

Each sample measures **1,000 cycles, or 8,000 operations**, using the same cycle
method as the throughput benchmark. One or ten cycles can be too short for the
clock's granularity. The fixed batch keeps the interval well above clock-read
cost while making results comparable across runs.

```console
$ cd projects/OrderBook
$ ./compile.sh
$ ./link.sh
$ ./java/compile.sh
$ ./latency.sh 10000 50000 1000
$ ./java/latency.sh 10000 50000 1000
```

The arguments are warmup batches, measured batches, and cycles per batch.
The matching defaults execute 80 million warmup operations and 400 million
measured operations, producing 50,000 latency samples. They target less than
ten seconds including warmup, startup, and reporting; fixed counts cannot
guarantee a deadline on every machine. Use the same arguments for comparisons.
Run trials in separate processes without concurrent benchmarks;
retain each run's tail results rather than averaging percentiles together.

Two `System.nanoTime()` reads bracket each batch. The driver stores the elapsed
nanoseconds in a preallocated array after the closing read. Warmup uses the
same path. Only after all batches finish do the reporters exclude warmup,
compute statistics, and print. Final order-book validation and the native
allocation-counter check also happen outside timing.

### Linux latency results: Ironwood vs Java

The command was `./latency.sh 10000 50000 1000`: 10,000 warmup batches,
50,000 measured batches, and 1,000 cycles per batch. The recorded counts match
80 million warmup operations and 400 million measured operations.

The Java latency runs used these runtimes:

- Oracle JDK 25.0.4.1, build `25.0.4.1+1-LTS-5`, HotSpot Server VM.
- Oracle GraalVM 25.0.4+7.1, build `25.0.4+7-LTS-jvmci-b01`, HotSpot Server VM
  with JVMCI. This runs Java on the GraalVM JDK, not Native Image.

| Implementation | Mean batch | Minimum batch | p99 batch | p99.9 batch | p99.99 batch | Maximum batch |
|---|---:|---:|---:|---:|---:|---:|
| Ironwood `-O3` | 102.799 µs | 99.493 µs | 131.224 µs | 146.993 µs | 180.429 µs | 216.611 µs |
| Oracle JDK 25 | 135.617 µs | 132.502 µs | 163.893 µs | 182.132 µs | 206.446 µs | 355.198 µs |
| GraalVM 25 | 154.148 µs | 151.982 µs | 165.493 µs | 194.499 µs | 238.356 µs | 262.085 µs |

In these selected runs, Ironwood's mean batch latency was 24.2% lower than
Oracle JDK's and 33.3% lower than GraalVM's.

The diagnostic averages for two clock reads plus the loop are about 0.02% to
0.03% of the respective mean batch latencies. The measured intervals sum to
approximately 5.140 seconds for Ironwood, 6.781 seconds for Oracle JDK, and
7.707 seconds for GraalVM. Total process runtimes, including warmup and
reporting, were not captured.

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

### Reading latency results

All times describe a complete batch. Each percentile's `max` is the boundary
of the fastest selected fraction of samples; its `avg` is their mean.
Dividing a boundary by 1,000 or 8,000 does not give a cycle or
individual-operation latency percentile: batching
averages over work within the interval and can hide short stalls.

Clock overhead remains included. The initial clock check reports empty-interval
cost and the smallest observed positive delta; use it when evaluating smaller
batches. With 50,000 samples, p99 leaves 500 observations above its boundary,
p99.9 leaves 50, and p99.99 leaves only five. The rounded p99.999 rank is the
maximum; this short run cannot support an independent estimate at that tail.
This workload measures synchronous execution without external arrivals,
queueing, or network delays. See [BENCH.md](BENCH.md) for the reporting API.
