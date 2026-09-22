# OrderBook

OrderBook is a deliberately small limit-order matching engine with paired
Ironwood and Java throughput and batch-latency benchmarks. Ironwood uses
`ironwood.bench.Bench` for latency reports. The project keeps the mechanisms
needed for a representative low-latency workload:

- price-time priority across ordered bid and ask levels;
- FIFO order priority within a price level;
- limit and market orders with maker-price execution;
- partial fills, total-size reduction, and cancellation;
- preallocated order and price-level reuse; and
- match-count, volume, and last-price results that verify completed work.

It omits listeners, callback safety, exception aggregation, client metadata,
time-in-force policies, self-trade prevention, expiry, rolling, traversal
views, logging, and formatted book rendering.

The five engine, demonstration, and throughput files have the same classes,
fields, methods, control flow, constants, benchmark operations, and validation
of final results.
Their source is line-for-line identical after changing `.iron` to `.java` and
adapting the entry point from Ironwood's status-returning `int main` to Java's
`void main`.

The two `LatencyBench` drivers also share identical sample-count validation,
timed collection loops, and clock checks. Native allocation diagnostics and
cleanup differ from Java. Java's `LatencyReport` helper reproduces the report
conventions after timing; automated tests compare its output with Ironwood's.

## Build and run

Build and run the native Ironwood demonstration:

```console
$ ./compile.sh
$ ./link.sh
$ ./run.sh
```

`compile.sh` and `link.sh` also build both native performance benchmarks.

Build and run the Java 21 demonstration:

```console
$ cd java
$ ./compile.sh
$ ./run.sh
```

`java/compile.sh` builds both Java performance benchmarks as well.

Build and run the Java sources with GraalVM Native Image:

```console
$ cd java
$ ./compile-native-image.sh
$ ./run-native-image.sh
```

The Native Image benchmark builds use `-O3`, `-march=native`, and the Epsilon
collector, without PGO and with ML profile inference disabled.
Build separately on each target machine because `-march=native`
tunes the executables for the build host. Epsilon is appropriate for these
finite programs because their bounded benchmark state is allocated before the
measured loops.

Build and run the C++17 translation of the Java sources on macOS or Linux:

```console
$ cd cpp
$ ./compile.sh
$ ./link.sh
$ ./run.sh
```

The C++ classes, methods, control flow, constants, workload, and validation
follow the Java files, and `cpp/test.sh` checks that its latency reports match
Java's byte for byte. The engine is defined in headers and its operations are
always inlined into the benchmark loops. `cpp/compile.sh` builds with `-O3` and
the host CPU (`-mcpu=native` on arm64, `-march=native` elsewhere).
`System.nanoTime()` becomes a direct `CLOCK_MONOTONIC` read, the clock the
Ironwood runtime uses.

The scripts compile and link with the `clang++` of the LLVM 23 installation
that `ironwoodc` uses, so both languages share one LLVM optimizer and code
generator. Like `ironwoodc`, they search `IRONWOOD_LLVM_HOME`, the IDK
`toolchain` directory beside `ironwoodc`, Homebrew, `/usr/lib/llvm-23`, and
`llvm-config-23` or `llvm-config` on `PATH`. The IDK's Linux `clang` is a conda
build whose default target has no C++ standard library; for it alone, the
scripts keep the architecture and C library of its target triple, replace the
`conda` vendor with `unknown`, and add `--gcc-toolchain=/usr`, so it uses the
system GCC's C++ standard library and linker. Before building, the scripts
compile and run a one-line C++ program and stop with the compiler's output if
that fails, which usually means the system C++ standard library (such as the
`g++` package) is missing. They also stop when no LLVM 23 `clang++` is found.

All four versions print the same primitive snapshots:

```text
initial
99
100
101
80
after-market
102
30
2
120
final
true
true
4
170
2
```

The scenario rests two bids at the same price and two asks across two prices,
sweeps both ask levels with a market buy, reduces the first bid, cancels the
remaining ask, and sends a market sell through both bids. The final maker ID is
`2`, which verifies FIFO order within the shared bid level. The `true` values
confirm that the book is empty and all preallocated objects have returned to
their pools.

## Shared workload

Each benchmark cycle performs the same eight operations:

1. Create two resting bids.
2. Create two resting asks.
3. Reduce the best bid.
4. Cancel the best ask.
5. Sweep the bids with a market sell.
6. Sweep the remaining ask with a market buy.

Each cycle starts and ends with an empty book. The benchmark verifies six
consumed order IDs, three matches, 250 units of matched volume, the final
execution price, an empty book, and full pool recovery. These results keep the
matching work observable to both optimizing compilers.

## Throughput

The two arguments are warmup and measured operation counts in millions. All
four scripts default to 10 million warmup operations and 100 million measured
operations:

```console
$ ./throughput.sh 10 100
$ java/throughput.sh 10 100
$ java/throughput-native-image.sh 10 100
$ cpp/throughput.sh 10 100
```

Each command prints one integer: the elapsed nanoseconds for the measured
operations. Run the commands as separate processes, alternate their order over
multiple trials, and compare medians. Avoid running them concurrently because
they would compete for the same processor resources.

## Latency

```console
$ ./latency.sh 10000 50000 1000
$ java/latency.sh 10000 50000 1000
$ java/latency-native-image.sh 10000 50000 1000
$ cpp/latency.sh 10000 50000 1000
```

The arguments, also the defaults, are **warmup batches, measured batches, and
cycles per batch**. One sample times 1,000 complete cycles, or 8,000 operations,
between two `System.nanoTime()` calls. This gives 80 million warmup operations
and 400 million measured operations. These defaults target a run below ten
seconds; elapsed time depends on the machine. A quick smoke run is
`./latency.sh 10 100 1000`.

Ironwood and Native Image have no JIT, but all three versions retain the same
80-million-operation warmup. A Java 25 diagnostic run compiled the shared
workload before warmup ended, with no further application compilation events
during measurement. Recheck warmup on the target JDK when collecting official
results.

Batches keep the measured interval well above clock-read cost and granularity.
The driver prints an empty-interval clock check before warmup. Keep the batch
size fixed when comparing runs; the reported distribution is batch latency.
Dividing a percentile by 1,000 or 8,000 does not produce a cycle or operation
latency percentile. The benchmark includes clock overhead without subtracting
an estimated baseline, and measures synchronous execution without queueing or
external arrivals.

All raw samples, including warmup, go into an array allocated before timing.
Each duration is stored after its closing clock read. After collection and
workload validation, `ironwood.bench.Bench` excludes the warmup and prints its
report. Java sorts its measured samples and formats the equivalent report at
the same stage. With 50,000 measurements, p99 has 500 observations beyond its
boundary, p99.9 has 50, and p99.99 has only five. The rounded p99.999 rank is
the maximum, so treat it as a maximum rather than an independent tail estimate.

See [the benchmark results](../../docs/BENCHMARK.md) for the protocol and
recorded output. After compiling and linking, run `./test.sh` for four native
`ironwood.testing` tests, six Java tests, command-line checks, and report
equivalence. `java/test.sh` runs the Java checks independently after
`java/compile.sh`; its allocation test uses the JDK's thread-allocation counter.

## Allocation and ownership

The steady-state throughput workload and latency sample collection allocate
nothing. The native latency driver checks the allocation counter around the
complete collection loop, outside individual timed batches. Java tests verify
allocation-free collection after class initialization and warmup. Both implementations
preallocate eight orders and four price levels, remove each acquired object
from its pool slot, reset it after use, and return it to that slot. Ironwood's
pool graph intentionally has process lifetime because the benchmark executable
owns it until exit.

The C++ constructor allocates the order pool array and each order individually,
then the price-level pool array and each price level individually, followed by
separate zero-initialized two-element `head`, `tail`, and `levelCount` arrays.
This matches the allocation sequence in Ironwood and Java. It then allocates two
C++ ownership arrays that reclaim all pooled objects when the book is destroyed,
including resting objects whose free-pool slots are null. This ownership storage
is initialized before timing and is not accessed by the benchmark cycle.

The C++ `Side` and `Type` constants point to immutable singleton objects with
static lifetime. `Side` retains its integer `index` field and instance methods.
Orders and price levels store enum pointers in the original field order, with
null initial values and null resets when returned to their pools. Enum access
does not allocate.

The native latency driver frees its raw sample array and report accumulator after use.

The public hot-path methods assume positive unique IDs, positive sizes and
prices, sufficient configured capacity, and active order handles. Those caller
obligations avoid adding state registries or continuous misuse checks to the
measured path.

Build outputs are ignored under `target/`, `java/target/`, and `cpp/target/`.
