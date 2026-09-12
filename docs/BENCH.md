<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Benchmarking with Ironwood

`ironwood.bench.Bench` measures latency with warmup exclusion and percentile
reports. `ironwood.bench.NanoBench` is a smaller accumulator that reports only
the count, average, minimum, and maximum in nanoseconds. Both compile into the
native executable and use `System.nanoTime()`.

## Start with Bench

This measures a 1,000 ns busy wait, ignoring 1,000 warmup iterations and recording
10,000 measurements. Replace `sleepFor(1000)` with the operation you want to time.

```java
import ironwood.bench.Bench;

public class BenchExample {

    private static void sleepFor(long nanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < nanos);
    }

    public static int main(String[] args) {

        int warmup = 1000;
        int measurements = 10000;
        Bench bench = new Bench(warmup);
        while (bench.getIterations() < warmup + measurements) {
            long start = System.nanoTime();
            sleepFor(1000);
            bench.measure(System.nanoTime() - start);
        }
        bench.printResults();
        free bench;
        return 0;
    }
}
```

`measure(long)` accepts the elapsed nanoseconds from an external clock. You can
also let `Bench` handle the clock by replacing the three timing lines with:

```java
bench.mark();
sleepFor(1000);
bench.measure();
```

Run **warmup plus measurement iterations**. `getIterations()` includes warmup;
`getMeasurements()` excludes it. `new Bench()` starts without warmup.

The report includes the average, minimum, maximum, and six percentiles: 75%, 90%,
99%, 99.9%, 99.99%, and 99.999%. Each percentile reports the average and maximum
among the fastest selected observations. Actual timings depend on the machine.

## Reset and results

```java
bench.reset(true);  // Clear results and repeat the warmup phase
bench.reset();      // Clear results and disable warmup.
```

Reset retains storage for reuse. Once disabled, `reset(true)` does not restore
the original warmup count.

```java
bench.printResults();       // Include percentiles.
bench.printResults(false);  // Omit percentiles.
```

To keep a report, declare and `free` its String:

```java
String report = bench.results();
System.out.println(report);
free report;
```

Optional progress logging: `new Bench(1000L, true, 10000)` logs the first iteration
and every 10,000 iterations. Keep logging off when collecting timings.

## NanoBench

`NanoBench` reports only count, average, minimum, and maximum in nanoseconds,
with no warmup or percentiles. Recording and printing allocate nothing after
construction.

```java
import ironwood.bench.NanoBench;

public class NanoBenchExample {

    public static int main(String[] args) {

        NanoBench bench = new NanoBench();
        for (int i = 0; i < 1000; i++) {
            bench.mark();
            long start = System.nanoTime();
            while (System.nanoTime() - start < 1000);
            bench.measure();
        }
        bench.printResults();
        free bench;
        return 0;
    }
}
```

Use `reset()` to reuse it. `getMeasurements()`, `getAverage()`, `getMinTime()`, and
`getMaxTime()` return its statistics. It also accepts `measure(elapsed)` when you
compute the duration with `System.nanoTime()` as in the first example.

## More examples

See [`examples/bench`](../examples/bench/README.md) for arithmetic, bubble sort,
map operations, and busy waits. With the repository compiler on PATH:

```sh
./examples/bench/compile.sh
./examples/bench/link.sh
./examples/bench/run.sh
```

Run the automated tests with `./scripts/test-bench.sh`. See
[IronDocs](api/README.md) for the full API contracts.
