// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import bridgeperf.OrderBookBatch;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Matches the project's latency sampling with one native call per whole batch. */
public final class OrderBookBatchLatency {
    private OrderBookBatchLatency() {}
    private static int sampleCount(int warmup, int measured, int cycles) {
        long count = (long) warmup + measured;
        if (warmup < 0 || measured <= 0 || cycles <= 0 || count > Integer.MAX_VALUE
                || count * cycles > Long.MAX_VALUE / 250L) throw new IllegalArgumentException("invalid batch sizes");
        return (int) count;
    }
    public static void main(String[] args) {
        int warmup = Integer.parseInt(args[0]), measurements = Integer.parseInt(args[1]), cycles = Integer.parseInt(args[2]);
        int count = sampleCount(warmup, measurements, cycles);
        long[] samples = new long[count];
        try (var root = BridgeLifetime.<OrderBook>construct(OrderBook.class, 8, 4)) {
        OrderBook book = root.get(); long next = 1;
        next = OrderBookBatch.run(book, 1, next);
        long allocations = OrderBookBatch.allocations();
        for (int index = 0; index < count; index++) {
            long start = System.nanoTime();
            next = OrderBookBatch.run(book, cycles, next);
            samples[index] = System.nanoTime() - start;
        }
        if (OrderBookBatch.allocations() != allocations) throw new AssertionError("pool workload allocated");
        BridgeBench.verify(book, next, (long)count * cycles + 1);
        System.out.println("Cycles per batch: " + cycles);
        System.out.println("Operations per batch: " + (long)cycles * 8);
        System.out.println("Measured operations: " + (long)measurements * cycles * 8);
        System.out.println("Batch latency (clock overhead included):");
        System.out.println(LatencyReport.results(samples, warmup));
        }
    }
}
