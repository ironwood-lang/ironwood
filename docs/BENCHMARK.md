# Order Book Matching Engine Benchmark Results

## Linux results: Ironwood vs Java

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
measurements. A latency benchmark would time individual operations or cycles
and report a distribution such as median and tail percentiles.

## Linux environment

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

## Warmup and timing

The command for every recorded result was:

```console
$ ./bench.sh 8 80
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

## Reading the result

Each `bench.sh` invocation runs in a separate process and prints one integer:
the elapsed nanoseconds for the 80 million measured operations. Time per
operation divides that integer by 80 million; throughput divides 80 million by
the elapsed seconds.
