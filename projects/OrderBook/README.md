# OrderBook

OrderBook is a deliberately small limit-order matching engine and paired
Ironwood versus Java throughput benchmark. The project keeps the mechanisms
needed for a representative low-latency workload:

- price-time priority across ordered bid and ask levels;
- FIFO order priority within a price level;
- limit and market orders with maker-price execution;
- partial fills, total-size reduction, and cancellation;
- preallocated order and price-level reuse; and
- match-count, volume, and last-price results that verify completed work.

It omits listeners, callback safety, exception aggregation, client metadata,
time-in-force policies, self-trade prevention, expiry, rolling, traversal
views, logging, and formatted book rendering. The result is five production
source files in each language.

The Ironwood and Java implementations have the same classes, fields, methods,
control flow, constants, benchmark operations, and validation of final results.
Their source is line-for-line identical after changing `.iron` to `.java` and
adapting the entry point from Ironwood's status-returning `int main` to Java's
`void main`.

## Build and run

Build and run the native Ironwood demonstration:

```console
$ ./compile.sh
$ ./link.sh
$ ./run.sh
```

Build and run the Java 21 demonstration:

```console
$ cd java
$ ./compile.sh
$ ./run.sh
```

Both versions print the same primitive snapshots:

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

## Benchmark

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

The two arguments are warmup and measured operation counts in millions. Both
scripts default to 10 million warmup operations and 100 million measured
operations:

```console
$ ./bench.sh 10 100
$ java/bench.sh 10 100
```

Each command prints one integer: the elapsed nanoseconds for the measured
operations. Run the commands as separate processes, alternate their order over
multiple trials, and compare medians. Avoid running them concurrently because
they would compete for the same processor resources.

The benchmark performs no allocation after construction. Both implementations
preallocate eight orders and four price levels, remove each acquired object
from its pool slot, reset it after use, and return it to that slot. Ironwood's
pool graph intentionally has process lifetime because the benchmark executable
owns it until exit.

The public hot-path methods assume positive unique IDs, positive sizes and
prices, sufficient configured capacity, and active order handles. Those caller
obligations avoid adding state registries or continuous misuse checks to the
measured path.

Build outputs are ignored under `target/` and `java/target/`.
