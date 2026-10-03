// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import bridgeperf.OrderBookBatch;

/** Shares the project's exact workload verification with the per-operation consumer. */
public final class OrderBookBatchConsumer {
    private OrderBookBatchConsumer() {}
    public static void main(String[] args) {
        long warm = Long.parseLong(args[0]) * 125000L, measured = Long.parseLong(args[1]) * 125000L;
        OrderBook book = new OrderBook(8, 4);
        long next = OrderBookBatch.run(book, warm, 1L);
        long start = System.nanoTime();
        next = OrderBookBatch.run(book, measured, next);
        long elapsed = System.nanoTime() - start;
        Bench.verify(book, next, warm + measured);
        System.out.println(elapsed);
    }
}
