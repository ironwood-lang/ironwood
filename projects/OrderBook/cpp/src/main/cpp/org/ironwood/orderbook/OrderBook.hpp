// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_ORDER_BOOK_HPP
#define ORG_IRONWOOD_ORDERBOOK_ORDER_BOOK_HPP

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>

#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/Order.hpp"
#include "org/ironwood/orderbook/PriceLevel.hpp"

namespace org::ironwood::orderbook {

/**
 * Minimal single-threaded price-time-priority order book.
 *
 * The caller must supply positive unique IDs, positive sizes and prices, and
 * must use order handles only while they are resting. These obligations avoid
 * runtime bookkeeping in the measured path.
 *
 * The whole engine is defined in headers so that the benchmark loops can
 * inline it without link-time optimization. The operations are always inlined,
 * as the JIT and ironwoodc do; the default -O3 heuristics left calls to
 * createLimit, match and cancel in the benchmark loop.
 */
class OrderBook final {
public:
    using Side = Order::Side;
    using Type = Order::Type;

    OrderBook(std::int32_t orderCapacity, std::int32_t priceLevelCapacity)
            : orderCapacity_(orderCapacity),
              availableOrders_(orderCapacity),
              priceLevelCapacity_(priceLevelCapacity),
              availablePriceLevels_(priceLevelCapacity) {
        if (orderCapacity <= 0) throw std::invalid_argument("orderCapacity must be positive");
        if (priceLevelCapacity <= 0) throw std::invalid_argument("priceLevelCapacity must be positive");

        // Java allocates each pooled object separately. Here one array owns
        // them, and the pools hold pointers into it as Java's arrays hold
        // references.
        orders_.reset(new Order[static_cast<std::size_t>(orderCapacity)]);
        orderPool_ = std::make_unique<Order*[]>(static_cast<std::size_t>(orderCapacity));
        for (std::int32_t index = 0; index < orderCapacity; index++) {
            orderPool_[index] = &orders_[index];
        }

        priceLevels_.reset(new PriceLevel[static_cast<std::size_t>(priceLevelCapacity)]);
        priceLevelPool_ = std::make_unique<PriceLevel*[]>(static_cast<std::size_t>(priceLevelCapacity));
        for (std::int32_t index = 0; index < priceLevelCapacity; index++) {
            priceLevelPool_[index] = &priceLevels_[index];
        }
    }

    // Orders keep a pointer to their book, so a book never moves.
    OrderBook(const OrderBook&) = delete;
    OrderBook& operator=(const OrderBook&) = delete;

    [[gnu::always_inline]] Order& createLimit(std::int64_t id, Side side, std::int64_t size, std::int64_t price) {
        Order& order = acquireOrder(id, side, size, price, Type::LIMIT);
        match(order);
        if (order.isTerminal()) {
            releaseOrder(order);
        } else {
            rest(order);
        }
        return order;
    }

    [[gnu::always_inline]] void createMarket(std::int64_t id, Side side, std::int64_t size) {
        Order& order = acquireOrder(id, side, size, 0, Type::MARKET);
        match(order);
        releaseOrder(order);
    }

    bool isEmpty() const noexcept {
        return restingOrderCount_ == 0
                && head_[index(Side::BUY)] == nullptr
                && head_[index(Side::SELL)] == nullptr;
    }

    bool hasFullPoolCapacity() const noexcept {
        return availableOrders_ == orderCapacity_
                && availablePriceLevels_ == priceLevelCapacity_;
    }

    std::int32_t getRestingOrderCount() const noexcept {
        return restingOrderCount_;
    }

    std::int32_t getLevelCount(Side side) const noexcept {
        return levelCount_[index(side)];
    }

    std::int64_t getBestPrice(Side side) const noexcept {
        return head_[index(side)]->price();
    }

    std::int64_t getBestSize(Side side) const noexcept {
        return head_[index(side)]->size();
    }

    std::int64_t getMatchCount() const noexcept {
        return matchCount_;
    }

    std::int64_t getMatchedVolume() const noexcept {
        return matchedVolume_;
    }

    std::int64_t getLastExecutedPrice() const noexcept {
        return lastExecutedPrice_;
    }

    std::int64_t getLastMakerOrderId() const noexcept {
        return lastMakerOrderId_;
    }

private:
    // Order::reduceTo and Order::cancel call the package-private Java methods.
    friend class Order;

    [[gnu::always_inline]] void reduce(Order& order, std::int64_t newTotalSize) noexcept {
        if (newTotalSize <= order.getExecutedSize()) {
            cancel(order);
            return;
        }
        if (newTotalSize > order.getTotalSize()) newTotalSize = order.getTotalSize();

        std::int64_t canceledSize = order.getTotalSize() - newTotalSize;
        order.setTotalSize(newTotalSize);
        order.priceLevel()->reduceSize(canceledSize);
    }

    [[gnu::always_inline]] void cancel(Order& order) noexcept {
        PriceLevel* priceLevel = order.priceLevel();
        priceLevel->reduceSize(order.getOpenSize());
        order.setTotalSize(order.getExecutedSize());
        removeRestingOrder(order);
    }

    [[gnu::always_inline]] void match(Order& order) noexcept {
        std::int32_t oppositeIndex = invertedIndex(order.getSide());
        PriceLevel* nextPriceLevel = nullptr;

        for (PriceLevel* priceLevel = head_[oppositeIndex];
                priceLevel != nullptr; priceLevel = nextPriceLevel) {
            nextPriceLevel = priceLevel->next_;
            if (order.getType() != Type::MARKET
                    && isOutside(order.getSide(), order.getPrice(), priceLevel->price())) break;

            Order* nextOrder = nullptr;
            for (Order* restingOrder = priceLevel->head();
                    restingOrder != nullptr; restingOrder = nextOrder) {
                nextOrder = restingOrder->next_;

                std::int64_t executedSize = std::min(order.getOpenSize(), restingOrder->getOpenSize());
                std::int64_t executedPrice = restingOrder->getPrice();

                priceLevel->reduceSize(executedSize);
                restingOrder->execute(executedSize);
                order.execute(executedSize);

                matchCount_++;
                matchedVolume_ += executedSize;
                lastExecutedPrice_ = executedPrice;
                lastMakerOrderId_ = restingOrder->getId();

                if (restingOrder->isTerminal()) removeRestingOrder(*restingOrder);
                // Java breaks out of the labeled outer loop, which ends the method.
                if (order.isTerminal()) return;
            }
        }
    }

    [[gnu::always_inline]] void rest(Order& order) {
        PriceLevel& priceLevel = findPriceLevel(order.getSide(), order.getPrice());
        order.restAt(&priceLevel);
        priceLevel.addOrder(order);
        restingOrderCount_++;
    }

    [[gnu::always_inline]] PriceLevel& findPriceLevel(Side side, std::int64_t price) {
        std::int32_t sideIndex = index(side);
        PriceLevel* found = nullptr;
        for (PriceLevel* priceLevel = head_[sideIndex]; priceLevel != nullptr; priceLevel = priceLevel->next_) {
            if (!isOutside(side, price, priceLevel->price())) {
                found = priceLevel;
                break;
            }
        }

        if (found == nullptr) {
            PriceLevel& priceLevel = acquirePriceLevel(side, price);
            if (head_[sideIndex] == nullptr) {
                head_[sideIndex] = &priceLevel;
                tail_[sideIndex] = &priceLevel;
            } else {
                tail_[sideIndex]->next_ = &priceLevel;
                priceLevel.previous_ = tail_[sideIndex];
                tail_[sideIndex] = &priceLevel;
            }
            levelCount_[sideIndex]++;
            return priceLevel;
        }

        if (found->price() == price) return *found;

        PriceLevel& priceLevel = acquirePriceLevel(side, price);
        priceLevel.previous_ = found->previous_;
        priceLevel.next_ = found;
        if (found->previous_ == nullptr) {
            head_[sideIndex] = &priceLevel;
        } else {
            found->previous_->next_ = &priceLevel;
        }
        found->previous_ = &priceLevel;
        levelCount_[sideIndex]++;
        return priceLevel;
    }

    [[gnu::always_inline]] void removeRestingOrder(Order& order) noexcept {
        PriceLevel* priceLevel = order.priceLevel();
        priceLevel->removeOrder(order);
        order.leaveBook();
        restingOrderCount_--;

        if (priceLevel->isEmpty()) removePriceLevel(*priceLevel);
        releaseOrder(order);
    }

    [[gnu::always_inline]] void removePriceLevel(PriceLevel& priceLevel) noexcept {
        std::int32_t sideIndex = index(priceLevel.side());
        if (priceLevel.previous_ == nullptr) {
            head_[sideIndex] = priceLevel.next_;
        } else {
            priceLevel.previous_->next_ = priceLevel.next_;
        }
        if (priceLevel.next_ == nullptr) {
            tail_[sideIndex] = priceLevel.previous_;
        } else {
            priceLevel.next_->previous_ = priceLevel.previous_;
        }
        levelCount_[sideIndex]--;
        releasePriceLevel(priceLevel);
    }

    [[gnu::always_inline]] Order& acquireOrder(std::int64_t id, Side side, std::int64_t size, std::int64_t price, Type type) {
        if (availableOrders_ == 0) throwIllegalState("order capacity exhausted");
        availableOrders_--;
        Order* order = orderPool_[availableOrders_];
        orderPool_[availableOrders_] = nullptr;
        order->initialize(this, id, side, size, price, type);
        return *order;
    }

    [[gnu::always_inline]] void releaseOrder(Order& order) noexcept {
        order.reset();
        orderPool_[availableOrders_] = &order;
        availableOrders_++;
    }

    [[gnu::always_inline]] PriceLevel& acquirePriceLevel(Side side, std::int64_t price) {
        if (availablePriceLevels_ == 0) throwIllegalState("price-level capacity exhausted");
        availablePriceLevels_--;
        PriceLevel* priceLevel = priceLevelPool_[availablePriceLevels_];
        priceLevelPool_[availablePriceLevels_] = nullptr;
        priceLevel->initialize(side, price);
        return *priceLevel;
    }

    [[gnu::always_inline]] void releasePriceLevel(PriceLevel& priceLevel) noexcept {
        priceLevel.reset();
        priceLevelPool_[availablePriceLevels_] = &priceLevel;
        availablePriceLevels_++;
    }

    std::unique_ptr<Order[]> orders_;
    std::unique_ptr<Order*[]> orderPool_;
    std::int32_t orderCapacity_;
    std::int32_t availableOrders_;
    std::unique_ptr<PriceLevel[]> priceLevels_;
    std::unique_ptr<PriceLevel*[]> priceLevelPool_;
    std::int32_t priceLevelCapacity_;
    std::int32_t availablePriceLevels_;
    std::array<PriceLevel*, 2> head_{};
    std::array<PriceLevel*, 2> tail_{};
    std::array<std::int32_t, 2> levelCount_{};
    std::int32_t restingOrderCount_ = 0;
    std::int64_t matchCount_ = 0;
    std::int64_t matchedVolume_ = 0;
    std::int64_t lastExecutedPrice_ = 0;
    std::int64_t lastMakerOrderId_ = 0;
};

[[gnu::always_inline]] inline void Order::reduceTo(std::int64_t newTotalSize) noexcept {
    orderBook_->reduce(*this, newTotalSize);
}

[[gnu::always_inline]] inline void Order::cancel() noexcept {
    orderBook_->cancel(*this);
}

}

#endif
