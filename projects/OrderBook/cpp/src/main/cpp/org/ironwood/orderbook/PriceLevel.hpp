// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_PRICE_LEVEL_HPP
#define ORG_IRONWOOD_ORDERBOOK_PRICE_LEVEL_HPP

#include <cstdint>

#include "org/ironwood/orderbook/Order.hpp"

namespace org::ironwood::orderbook {

/** FIFO orders resting at one price. Only OrderBook uses it, as in Java. */
class PriceLevel final {
public:
    PriceLevel(const PriceLevel&) = delete;
    PriceLevel& operator=(const PriceLevel&) = delete;

private:
    friend class OrderBook;
    // Tests inspect pool resets through Java's package-private access.
    friend class BenchmarkTests;

    using Side = Order::Side;

    PriceLevel() = default;

    void initialize(const Side* side, std::int64_t price) noexcept {
        side_ = side;
        price_ = price;
        size_ = 0;
        orderCount_ = 0;
        head_ = nullptr;
        tail_ = nullptr;
        next_ = nullptr;
        previous_ = nullptr;
    }

    void reset() noexcept {
        side_ = nullptr;
        price_ = 0;
        size_ = 0;
        orderCount_ = 0;
        head_ = nullptr;
        tail_ = nullptr;
        next_ = nullptr;
        previous_ = nullptr;
    }

    void addOrder(Order& order) noexcept {
        if (head_ == nullptr) {
            head_ = &order;
            tail_ = &order;
            order.previous_ = nullptr;
            order.next_ = nullptr;
        } else {
            tail_->next_ = &order;
            order.previous_ = tail_;
            order.next_ = nullptr;
            tail_ = &order;
        }
        size_ += order.getOpenSize();
        orderCount_++;
    }

    void removeOrder(Order& order) noexcept {
        if (order.previous_ != nullptr) order.previous_->next_ = order.next_;
        if (order.next_ != nullptr) order.next_->previous_ = order.previous_;
        if (tail_ == &order) tail_ = order.previous_;
        if (head_ == &order) head_ = order.next_;
        orderCount_--;
    }

    void reduceSize(std::int64_t amount) noexcept {
        size_ -= amount;
    }

    const Side* side() const noexcept {
        return side_;
    }

    std::int64_t price() const noexcept {
        return price_;
    }

    std::int64_t size() const noexcept {
        return size_;
    }

    std::int32_t orderCount() const noexcept {
        return orderCount_;
    }

    Order* head() const noexcept {
        return head_;
    }

    bool isEmpty() const noexcept {
        return orderCount_ == 0;
    }

    // Fields keep Java's declaration order, as in Order.
    const Side* side_ = nullptr;
    std::int64_t price_ = 0;
    std::int64_t size_ = 0;
    std::int32_t orderCount_ = 0;
    Order* head_ = nullptr;
    Order* tail_ = nullptr;
    PriceLevel* next_ = nullptr;
    PriceLevel* previous_ = nullptr;
};

}

#endif
