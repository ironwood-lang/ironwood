// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import org.ironwood.orderbook.Order.Side;

/** FIFO orders resting at one price. */
final class PriceLevel {

    private Side side;

    private long price;

    private long size;

    private int orderCount;

    private Order head;

    private Order tail;

    PriceLevel next;

    PriceLevel previous;

    PriceLevel() {

    }

    void initialize(Side side, long price) {

        this.side = side;
        this.price = price;
        this.size = 0L;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.previous = null;
    }

    void reset() {

        this.side = null;
        this.price = 0L;
        this.size = 0L;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.previous = null;
    }

    void addOrder(Order order) {

        if (this.head == null) {
            this.head = order;
            this.tail = order;
            order.previous = null;
            order.next = null;
        } else {
            this.tail.next = order;
            order.previous = this.tail;
            order.next = null;
            this.tail = order;
        }
        this.size += order.getOpenSize();
        this.orderCount++;
    }

    void removeOrder(Order order) {

        if (order.previous != null) order.previous.next = order.next;
        if (order.next != null) order.next.previous = order.previous;
        if (this.tail == order) this.tail = order.previous;
        if (this.head == order) this.head = order.next;
        this.orderCount--;
    }

    void reduceSize(long amount) {

        this.size -= amount;
    }

    Side side() {

        return this.side;
    }

    long price() {

        return this.price;
    }

    long size() {

        return this.size;
    }

    int orderCount() {

        return this.orderCount;
    }

    Order head() {

        return this.head;
    }

    boolean isEmpty() {

        return this.orderCount == 0;
    }
}
