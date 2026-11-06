// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import bridgeperf.OrderBookBatch;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Shares the project's exact workload verification with the per-operation consumer. */
public final class OrderBookBatchConsumer {
    private OrderBookBatchConsumer() {}
    public static void main(String[] args) {
        long warm = Long.parseLong(args[0]) * Long.getLong("ironwood.measure.cycles", 125000L), measured = Long.parseLong(args[1]) * Long.getLong("ironwood.measure.cycles", 125000L);
        try (var root = BridgeLifetime.<OrderBook>construct(OrderBook.class, 8, 4)) {
        OrderBook book = root.get();
        long next = OrderBookBatch.run(book, warm, 1L);
        long allocations = OrderBookBatch.allocations();
        long start = System.nanoTime();
        next = OrderBookBatch.run(book, measured, next);
        long elapsed = System.nanoTime() - start;
        if (OrderBookBatch.allocations() != allocations) throw new AssertionError("pool workload allocated");
        BridgeBench.verify(book, next, warm + measured);
        System.out.println(elapsed);
        }
    }
}
