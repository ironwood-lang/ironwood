// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Java counterparts of the native workload tests, plus report checks. */
public final class BenchmarkTests {

    private BenchmarkTests() {

    }

    private static void check(boolean condition) {

        if (!condition) throw new AssertionError("benchmark check failed");
    }

    private static void workloadPreservesCountsAndReusesPools() {

        OrderBook book = new OrderBook(8, 4);
        long next = Bench.run(book, 0L, 1L);
        check(next == 1L && book.isEmpty());
        next = Bench.run(book, 1L, next);
        Bench.verify(book, next, 1L);
        next = Bench.run(book, 10L, next);
        Bench.verify(book, next, 11L);
        check(next == 67L && book.getMatchCount() == 33L);
        check(book.getMatchedVolume() == 2750L && book.getLastMakerOrderId() == 64L);
    }

    private static void collectionWritesEverySampleWithoutAllocating() {

        com.sun.management.ThreadMXBean allocations = ManagementFactory.getPlatformMXBean(
                com.sun.management.ThreadMXBean.class);
        check(allocations != null && allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        // Initialize classes and compile the workload before observing allocation.
        LatencyBench.collect(new OrderBook(8, 4), new long[10000], 1000);
        allocations.getThreadAllocatedBytes(threadId);
        long[] samples = new long[8];
        for (int cycles = 1; cycles <= 1000; cycles *= 10) {
            Arrays.fill(samples, -1L);
            OrderBook book = new OrderBook(8, 4);
            long before = allocations.getThreadAllocatedBytes(threadId);
            long next = LatencyBench.collect(book, samples, cycles);
            check(allocations.getThreadAllocatedBytes(threadId) == before);
            Bench.verify(book, next, (long) samples.length * cycles);
            for (long sample : samples) check(sample >= 0L);
        }
    }

    private static void acceptsZeroWarmupAndDefaultSampleCounts() {

        check(LatencyBench.sampleCount(0, 1, 1) == 1);
        check(LatencyBench.sampleCount(10000, 50000, 1000) == 60000);
    }

    private static boolean rejects(int warmup, int measurements, int cycles) {

        try {
            LatencyBench.sampleCount(warmup, measurements, cycles);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void rejectsInvalidCountsAndCounterOverflow() {

        check(rejects(-1, 1, 1));
        check(rejects(0, 0, 1));
        check(rejects(0, -1, 1));
        check(rejects(0, 1, 0));
        check(rejects(0, 1, -1));
        check(rejects(Integer.MAX_VALUE, 1, 1));
        check(rejects(0, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static void reportExcludesWarmupAndHandlesEmptySamples() {

        String report = LatencyReport.results(new long[] {999, 10, 11}, 1);
        check(report.startsWith("Measurements: 2 | Warm-Up: 1 | Iterations: 3\n"));
        check(report.contains("Avg Time: 10.500 nanos | Min Time: 10.000 nanos | Max Time: 11.000 nanos"));
        check(report.contains("75% = [avg: 10.500 nanos, max: 11.000 nanos]"));
        check(LatencyReport.results(new long[] {99, 88}, 2)
                .equals("Measurements: 0 | Warm-Up: 2 | Iterations: 2\n"));
    }

    private static void reportPreservesSamplesAndSelectsPartialBuckets() {

        long[] samples = {999, 20, 10, 10, 10, 10, 10, 0, 0};
        long[] original = samples.clone();
        String report = LatencyReport.results(samples, 1);
        check(Arrays.equals(samples, original));
        check(report.contains("75% = [avg: 6.667 nanos, max: 10.000 nanos]"));
    }

    private static void printReports() {

        System.out.println(LatencyReport.results(new long[] {999, 10, 11}, 1));
        System.out.println(LatencyReport.results(new long[] {999, 20, 10, 10, 10, 10, 10, 0, 0}, 1));
        System.out.println(LatencyReport.results(new long[] {Long.MAX_VALUE, Long.MAX_VALUE,
                0, 999, 1000, 1000, 1999, 2000, 1000000, 1000000000}, 2));
        System.out.println(LatencyReport.results(new long[] {100, 42}, 1));
        System.out.println(LatencyReport.results(new long[] {99, 88}, 2));
    }

    private static void run(String name, Runnable test) {

        System.out.println("RUN - " + name);
        test.run();
        System.out.println("ok - " + name);
    }

    public static void main(String[] args) {

        if (args.length == 1 && args[0].equals("reports")) {
            printReports();
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("unexpected test arguments");
        run("workloadPreservesCountsAndReusesPools", BenchmarkTests::workloadPreservesCountsAndReusesPools);
        run("collectionWritesEverySampleWithoutAllocating", BenchmarkTests::collectionWritesEverySampleWithoutAllocating);
        run("acceptsZeroWarmupAndDefaultSampleCounts", BenchmarkTests::acceptsZeroWarmupAndDefaultSampleCounts);
        run("rejectsInvalidCountsAndCounterOverflow", BenchmarkTests::rejectsInvalidCountsAndCounterOverflow);
        run("reportExcludesWarmupAndHandlesEmptySamples", BenchmarkTests::reportExcludesWarmupAndHandlesEmptySamples);
        run("reportPreservesSamplesAndSelectsPartialBuckets", BenchmarkTests::reportPreservesSamplesAndSelectsPartialBuckets);
        System.out.println("PASS: 6 Java benchmark tests");
    }
}
