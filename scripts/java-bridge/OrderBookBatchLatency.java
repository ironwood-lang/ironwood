// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import bridgeperf.OrderBookBatch;

/** Matches the project's latency sampling with one native call per whole batch. */
public final class OrderBookBatchLatency {
    private OrderBookBatchLatency() {}
    public static void main(String[] args) {
        int warmup = Integer.parseInt(args[0]), measurements = Integer.parseInt(args[1]), cycles = Integer.parseInt(args[2]);
        int count = LatencyBench.sampleCount(warmup, measurements, cycles);
        long[] samples = new long[count]; OrderBook book = new OrderBook(8, 4); long next = 1;
        for (int index = 0; index < count; index++) {
            long start = System.nanoTime();
            next = OrderBookBatch.run(book, cycles, next);
            samples[index] = System.nanoTime() - start;
        }
        Bench.verify(book, next, (long)count * cycles);
        System.out.println("Cycles per batch: " + cycles);
        System.out.println("Operations per batch: " + (long)cycles * 8);
        System.out.println("Measured operations: " + (long)measurements * cycles * 8);
        System.out.println("Batch latency (clock overhead included):");
        System.out.println(LatencyReport.results(samples, warmup));
    }
}
