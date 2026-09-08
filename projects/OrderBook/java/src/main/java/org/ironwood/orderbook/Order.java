// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

/** A reusable order owned by one {@link OrderBook}. */
public final class Order {

    public enum Side {

        BUY(0), SELL(1);

        private final int index;

        Side(int index) {

            this.index = index;
        }

        public int index() {

            return this.index;
        }

        public int invertedIndex() {

            return this == BUY ? SELL.index() : BUY.index();
        }

        public boolean isOutside(long price, long marketPrice) {

            return this == BUY ? price < marketPrice : price > marketPrice;
        }
    }

    public enum Type {

        LIMIT, MARKET
    }

    private OrderBook orderBook;

    private long id;

    private Side side;

    private long totalSize;

    private long executedSize;

    private long price;

    private Type type;

    private boolean resting;

    private PriceLevel priceLevel;

    Order next;

    Order previous;

    Order() {

    }

    void initialize(OrderBook orderBook, long id, Side side, long size, long price, Type type) {

        this.orderBook = orderBook;
        this.id = id;
        this.side = side;
        this.totalSize = size;
        this.executedSize = 0L;
        this.price = price;
        this.type = type;
        this.resting = false;
        this.priceLevel = null;
        this.next = null;
        this.previous = null;
    }

    void reset() {

        this.orderBook = null;
        this.id = 0L;
        this.side = null;
        this.totalSize = 0L;
        this.executedSize = 0L;
        this.price = 0L;
        this.type = null;
        this.resting = false;
        this.priceLevel = null;
        this.next = null;
        this.previous = null;
    }

    void restAt(PriceLevel priceLevel) {

        this.priceLevel = priceLevel;
        this.resting = true;
    }

    void leaveBook() {

        this.priceLevel = null;
        this.resting = false;
        this.next = null;
        this.previous = null;
    }

    void execute(long size) {

        this.executedSize += size;
    }

    void setTotalSize(long totalSize) {

        this.totalSize = totalSize;
    }

    PriceLevel priceLevel() {

        return this.priceLevel;
    }

    public long getId() {

        return this.id;
    }

    public Side getSide() {

        return this.side;
    }

    public long getTotalSize() {

        return this.totalSize;
    }

    public long getExecutedSize() {

        return this.executedSize;
    }

    public long getOpenSize() {

        return this.totalSize - this.executedSize;
    }

    public long getPrice() {

        return this.price;
    }

    public Type getType() {

        return this.type;
    }

    public boolean isResting() {

        return this.resting;
    }

    public boolean isTerminal() {

        return getOpenSize() == 0L;
    }

    /** Reduces the total size while preserving any already executed size. */
    public void reduceTo(long newTotalSize) {

        this.orderBook.reduce(this, newTotalSize);
    }

    /** Cancels all remaining open size. */
    public void cancel() {

        this.orderBook.cancel(this);
    }
}
