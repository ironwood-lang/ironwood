// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import org.ironwood.javabridge.support.BridgeLifetime;

/** The project's eight-operation cycle with explicit generated result anchors. */
public final class BridgeBench {
    private BridgeBench() {}

    static long run(OrderBook book, long cycles, long next) {
        for (long cycle = 0; cycle < cycles; cycle++) {
            try (var bidRoot = book.createLimit(next++, Order.Side.BUY, 100L, 10000000000L)) {
                book.createLimit(next++, Order.Side.BUY, 100L, 9900000000L).close();
                try (var askRoot = book.createLimit(next++, Order.Side.SELL, 100L, 10200000000L)) {
                    book.createLimit(next++, Order.Side.SELL, 100L, 10300000000L).close();
                    bidRoot.value().reduceTo(50L);
                    askRoot.value().cancel();
                    book.createMarket(next++, Order.Side.SELL, 150L);
                    book.createMarket(next++, Order.Side.BUY, 100L);
                }
            }
        }
        return next;
    }

    static void verify(OrderBook book, long next, long cycles) {
        if (next != cycles * 6L + 1L || book.getMatchCount() != cycles * 3L
                || book.getMatchedVolume() != cycles * 250L || book.getLastExecutedPrice() != 10300000000L
                || book.getLastMakerOrderId() != next - 3L || !book.isEmpty() || !book.hasFullPoolCapacity()) {
            throw new AssertionError("pooling workload did not complete");
        }
    }

    public static void main(String[] args) {
        long units = Long.getLong("ironwood.measure.cycles", 125000L);
        long warm = Long.parseLong(args[0]) * units, measured = Long.parseLong(args[1]) * units;
        try (var root = BridgeLifetime.<OrderBook>construct(OrderBook.class, 8, 4)) {
            OrderBook book = root.get();
            long next = run(book, warm, 1L), start = System.nanoTime();
            next = run(book, measured, next);
            long elapsed = System.nanoTime() - start;
            verify(book, next, warm + measured);
            System.out.println(elapsed);
        }
    }
}
