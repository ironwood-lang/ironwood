// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import org.ironwood.orderbook.Order.Side;

/**
 * Deterministic throughput benchmark for a capacity-stable order-book workload.
 * Arguments are warmup and measured operation counts in millions. Standard
 * output contains only the measured total in nanoseconds.
 */
public final class Bench {

    private static final int OPERATIONS_PER_CYCLE = 8;

    private static final int CYCLES_PER_MILLION = 1_000_000 / OPERATIONS_PER_CYCLE;

    private static final int ORDERS_PER_CYCLE = 6;

    private static final long BID_PRICE_1 = 10_000_000_000L;

    private static final long BID_PRICE_2 = 9_900_000_000L;

    private static final long ASK_PRICE_1 = 10_200_000_000L;

    private static final long ASK_PRICE_2 = 10_300_000_000L;

    private Bench() {

    }

    private static long run(OrderBook book, int millions, long nextOrderId) {

        long cycles = (long) millions * CYCLES_PER_MILLION;

        // Each cycle starts and ends empty, so all pooled capacity is reused.
        for (long cycle = 0L; cycle < cycles; cycle++) {
            Order bestBid = book.createLimit(nextOrderId++, Side.BUY, 100L, BID_PRICE_1);
            book.createLimit(nextOrderId++, Side.BUY, 100L, BID_PRICE_2);
            Order bestAsk = book.createLimit(nextOrderId++, Side.SELL, 100L, ASK_PRICE_1);
            book.createLimit(nextOrderId++, Side.SELL, 100L, ASK_PRICE_2);

            bestBid.reduceTo(50L);
            bestAsk.cancel();

            book.createMarket(nextOrderId++, Side.SELL, 150L);
            book.createMarket(nextOrderId++, Side.BUY, 100L);
        }
        return nextOrderId;
    }

    public static void main(String[] args) {

        if (args.length != 2) throw new IllegalArgumentException("expected warmup and measured operation counts in millions");

        int warmupMillions = Integer.parseInt(args[0]);
        int measuredMillions = Integer.parseInt(args[1]);
        if (warmupMillions < 0) throw new IllegalArgumentException("warmup must not be negative");
        if (measuredMillions <= 0) throw new IllegalArgumentException("measurement must be positive");

        OrderBook book = new OrderBook(8, 4);
        long nextOrderId = run(book, warmupMillions, 1L);

        long start = System.nanoTime();
        nextOrderId = run(book, measuredMillions, nextOrderId);
        long elapsed = System.nanoTime() - start;

        long totalMillions = (long) warmupMillions + measuredMillions;
        long totalCycles = totalMillions * CYCLES_PER_MILLION;
        long expectedNextOrderId = totalCycles * ORDERS_PER_CYCLE + 1L;
        boolean completed = nextOrderId == expectedNextOrderId
                && book.getMatchCount() == totalCycles * 3L
                && book.getMatchedVolume() == totalCycles * 250L
                && book.getLastExecutedPrice() == ASK_PRICE_2
                && book.getLastMakerOrderId() == expectedNextOrderId - 3L
                && book.isEmpty()
                && book.hasFullPoolCapacity();
        if (!completed) throw new IllegalStateException("benchmark workload did not complete correctly");

        System.out.println(elapsed);
    }
}
