// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import org.ironwood.orderbook.Order.Side;
import org.ironwood.orderbook.Order.Type;

/**
 * Minimal single-threaded price-time-priority order book.
 *
 * <p>The caller must supply positive unique IDs, positive sizes and prices,
 * and must use order handles only while they are resting. These obligations
 * avoid runtime bookkeeping in the measured path.</p>
 */
public final class OrderBook {

    private final Order[] orderPool;

    private int availableOrders;

    private final PriceLevel[] priceLevelPool;

    private int availablePriceLevels;

    private final PriceLevel[] head;

    private final PriceLevel[] tail;

    private final int[] levelCount;

    private int restingOrderCount;

    private long matchCount;

    private long matchedVolume;

    private long lastExecutedPrice;

    private long lastMakerOrderId;

    public OrderBook(int orderCapacity, int priceLevelCapacity) {

        if (orderCapacity <= 0) throw new IllegalArgumentException("orderCapacity must be positive");
        if (priceLevelCapacity <= 0) throw new IllegalArgumentException("priceLevelCapacity must be positive");

        this.orderPool = new Order[orderCapacity];
        this.availableOrders = orderCapacity;
        for (int index = 0; index < orderCapacity; index++) {
            this.orderPool[index] = new Order();
        }

        this.priceLevelPool = new PriceLevel[priceLevelCapacity];
        this.availablePriceLevels = priceLevelCapacity;
        for (int index = 0; index < priceLevelCapacity; index++) {
            this.priceLevelPool[index] = new PriceLevel();
        }

        this.head = new PriceLevel[2];
        this.tail = new PriceLevel[2];
        this.levelCount = new int[2];
        this.restingOrderCount = 0;
        this.matchCount = 0L;
        this.matchedVolume = 0L;
        this.lastExecutedPrice = 0L;
        this.lastMakerOrderId = 0L;
    }

    public Order createLimit(long id, Side side, long size, long price) {

        Order order = acquireOrder(id, side, size, price, Type.LIMIT);
        match(order);
        if (order.isTerminal()) {
            releaseOrder(order);
        } else {
            rest(order);
        }
        return order;
    }

    public void createMarket(long id, Side side, long size) {

        Order order = acquireOrder(id, side, size, 0L, Type.MARKET);
        match(order);
        releaseOrder(order);
    }

    void reduce(Order order, long newTotalSize) {

        if (newTotalSize <= order.getExecutedSize()) {
            cancel(order);
            return;
        }
        if (newTotalSize > order.getTotalSize()) newTotalSize = order.getTotalSize();

        long canceledSize = order.getTotalSize() - newTotalSize;
        order.setTotalSize(newTotalSize);
        order.priceLevel().reduceSize(canceledSize);
    }

    void cancel(Order order) {

        PriceLevel priceLevel = order.priceLevel();
        priceLevel.reduceSize(order.getOpenSize());
        order.setTotalSize(order.getExecutedSize());
        removeRestingOrder(order);
    }

    public boolean isEmpty() {

        return this.restingOrderCount == 0
                && this.head[Side.BUY.index()] == null
                && this.head[Side.SELL.index()] == null;
    }

    public boolean hasFullPoolCapacity() {

        return this.availableOrders == this.orderPool.length
                && this.availablePriceLevels == this.priceLevelPool.length;
    }

    public int getRestingOrderCount() {

        return this.restingOrderCount;
    }

    public int getLevelCount(Side side) {

        return this.levelCount[side.index()];
    }

    public long getBestPrice(Side side) {

        return this.head[side.index()].price();
    }

    public long getBestSize(Side side) {

        return this.head[side.index()].size();
    }

    public long getMatchCount() {

        return this.matchCount;
    }

    public long getMatchedVolume() {

        return this.matchedVolume;
    }

    public long getLastExecutedPrice() {

        return this.lastExecutedPrice;
    }

    public long getLastMakerOrderId() {

        return this.lastMakerOrderId;
    }

    private void match(Order order) {

        int oppositeIndex = order.getSide().invertedIndex();
        PriceLevel nextPriceLevel = null;

        OUTER: for (PriceLevel priceLevel = this.head[oppositeIndex];
                priceLevel != null; priceLevel = nextPriceLevel) {
            nextPriceLevel = priceLevel.next;
            if (order.getType() != Type.MARKET
                    && order.getSide().isOutside(order.getPrice(), priceLevel.price())) break;

            Order nextOrder = null;
            for (Order restingOrder = priceLevel.head();
                    restingOrder != null; restingOrder = nextOrder) {
                nextOrder = restingOrder.next;

                long executedSize = Math.min(order.getOpenSize(), restingOrder.getOpenSize());
                long executedPrice = restingOrder.getPrice();

                priceLevel.reduceSize(executedSize);
                restingOrder.execute(executedSize);
                order.execute(executedSize);

                this.matchCount++;
                this.matchedVolume += executedSize;
                this.lastExecutedPrice = executedPrice;
                this.lastMakerOrderId = restingOrder.getId();

                if (restingOrder.isTerminal()) removeRestingOrder(restingOrder);
                if (order.isTerminal()) break OUTER;
            }
        }
    }

    private void rest(Order order) {

        PriceLevel priceLevel = findPriceLevel(order.getSide(), order.getPrice());
        order.restAt(priceLevel);
        priceLevel.addOrder(order);
        this.restingOrderCount++;
    }

    private PriceLevel findPriceLevel(Side side, long price) {

        int sideIndex = side.index();
        PriceLevel found = null;
        for (PriceLevel priceLevel = this.head[sideIndex]; priceLevel != null; priceLevel = priceLevel.next) {
            if (!side.isOutside(price, priceLevel.price())) {
                found = priceLevel;
                break;
            }
        }

        if (found == null) {
            PriceLevel priceLevel = acquirePriceLevel(side, price);
            if (this.head[sideIndex] == null) {
                this.head[sideIndex] = priceLevel;
                this.tail[sideIndex] = priceLevel;
            } else {
                this.tail[sideIndex].next = priceLevel;
                priceLevel.previous = this.tail[sideIndex];
                this.tail[sideIndex] = priceLevel;
            }
            this.levelCount[sideIndex]++;
            return priceLevel;
        }

        if (found.price() == price) return found;

        PriceLevel priceLevel = acquirePriceLevel(side, price);
        priceLevel.previous = found.previous;
        priceLevel.next = found;
        if (found.previous == null) {
            this.head[sideIndex] = priceLevel;
        } else {
            found.previous.next = priceLevel;
        }
        found.previous = priceLevel;
        this.levelCount[sideIndex]++;
        return priceLevel;
    }

    private void removeRestingOrder(Order order) {

        PriceLevel priceLevel = order.priceLevel();
        priceLevel.removeOrder(order);
        order.leaveBook();
        this.restingOrderCount--;

        if (priceLevel.isEmpty()) removePriceLevel(priceLevel);
        releaseOrder(order);
    }

    private void removePriceLevel(PriceLevel priceLevel) {

        int sideIndex = priceLevel.side().index();
        if (priceLevel.previous == null) {
            this.head[sideIndex] = priceLevel.next;
        } else {
            priceLevel.previous.next = priceLevel.next;
        }
        if (priceLevel.next == null) {
            this.tail[sideIndex] = priceLevel.previous;
        } else {
            priceLevel.next.previous = priceLevel.previous;
        }
        this.levelCount[sideIndex]--;
        releasePriceLevel(priceLevel);
    }

    private Order acquireOrder(long id, Side side, long size, long price, Type type) {

        if (this.availableOrders == 0) throw new IllegalStateException("order capacity exhausted");
        this.availableOrders--;
        Order order = this.orderPool[this.availableOrders];
        this.orderPool[this.availableOrders] = null;
        order.initialize(this, id, side, size, price, type);
        return order;
    }

    private void releaseOrder(Order order) {

        order.reset();
        this.orderPool[this.availableOrders] = order;
        this.availableOrders++;
    }

    private PriceLevel acquirePriceLevel(Side side, long price) {

        if (this.availablePriceLevels == 0) throw new IllegalStateException("price-level capacity exhausted");
        this.availablePriceLevels--;
        PriceLevel priceLevel = this.priceLevelPool[this.availablePriceLevels];
        this.priceLevelPool[this.availablePriceLevels] = null;
        priceLevel.initialize(side, price);
        return priceLevel;
    }

    private void releasePriceLevel(PriceLevel priceLevel) {

        priceLevel.reset();
        this.priceLevelPool[this.availablePriceLevels] = priceLevel;
        this.availablePriceLevels++;
    }
}
