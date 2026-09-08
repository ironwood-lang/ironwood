// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import org.ironwood.orderbook.Order.Side;

/**
 * Demonstrates resting, matching, reduction, cancellation and pool reuse.
 * The program prints primitive state snapshots and exits with status zero.
 */
public final class Main {

    private Main() {

    }

    public static void main(String[] args) {

        OrderBook book = new OrderBook(8, 4);
        Order firstBid = book.createLimit(1L, Side.BUY, 60L, 99L);
        Order secondBid = book.createLimit(2L, Side.BUY, 40L, 99L);
        book.createLimit(3L, Side.SELL, 80L, 101L);
        Order secondAsk = book.createLimit(4L, Side.SELL, 70L, 102L);

        System.out.println("initial");
        System.out.println(book.getBestPrice(Side.BUY));
        System.out.println(book.getBestSize(Side.BUY));
        System.out.println(book.getBestPrice(Side.SELL));
        System.out.println(book.getBestSize(Side.SELL));

        book.createMarket(5L, Side.BUY, 120L);

        System.out.println("after-market");
        System.out.println(book.getBestPrice(Side.SELL));
        System.out.println(book.getBestSize(Side.SELL));
        System.out.println(book.getMatchCount());
        System.out.println(book.getMatchedVolume());

        firstBid.reduceTo(30L);
        secondAsk.cancel();
        book.createMarket(6L, Side.SELL, 50L);
        secondBid.cancel();

        System.out.println("final");
        System.out.println(book.isEmpty());
        System.out.println(book.hasFullPoolCapacity());
        System.out.println(book.getMatchCount());
        System.out.println(book.getMatchedVolume());
        System.out.println(book.getLastMakerOrderId());

        if (!book.isEmpty() || !book.hasFullPoolCapacity()) throw new IllegalStateException("demonstration did not finish with an empty book");
        boolean totalsCorrect = book.getMatchCount() == 4L
                && book.getMatchedVolume() == 170L
                && book.getLastMakerOrderId() == 2L;
        if (!totalsCorrect) throw new IllegalStateException("demonstration totals are incorrect");
    }
}
