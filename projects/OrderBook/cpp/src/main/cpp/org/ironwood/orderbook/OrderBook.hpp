// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_ORDER_BOOK_HPP
#define ORG_IRONWOOD_ORDERBOOK_ORDER_BOOK_HPP

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>

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
 * The whole engine is visible in headers during benchmark compilation.
 * The compiler chooses which calls to inline under its normal -O3 policy.
 */
class OrderBook final {
public:
    using Side = Order::Side;
    using Type = Order::Type;

    OrderBook(std::int32_t orderCapacity, std::int32_t priceLevelCapacity)
            : availableOrders_(orderCapacity),
              availablePriceLevels_(priceLevelCapacity),
              orderCapacity_(orderCapacity),
              priceLevelCapacity_(priceLevelCapacity) {
        if (orderCapacity <= 0) throw std::invalid_argument("orderCapacity must be positive");
        if (priceLevelCapacity <= 0) throw std::invalid_argument("priceLevelCapacity must be positive");

        // Match Java and Ironwood: allocate each pool array, then its objects
        // individually in index order, before starting the next pool.
        try {
            orderPool_ = std::make_unique<Order*[]>(static_cast<std::size_t>(orderCapacity));
            for (std::int32_t index = 0; index < orderCapacity; index++) {
                orderPool_[index] = new Order();
            }

            priceLevelPool_ = std::make_unique<PriceLevel*[]>(static_cast<std::size_t>(priceLevelCapacity));
            for (std::int32_t index = 0; index < priceLevelCapacity; index++) {
                priceLevelPool_[index] = new PriceLevel();
            }

            head_ = std::make_unique<PriceLevel*[]>(2);
            tail_ = std::make_unique<PriceLevel*[]>(2);
            levelCount_ = std::make_unique<std::int32_t[]>(2);

            // Allocate C++ ownership storage only after the original sequence.
            // Pool slots are cleared on acquisition, so they cannot own objects.
            orders_ = std::make_unique<std::unique_ptr<Order>[]>(static_cast<std::size_t>(orderCapacity));
            priceLevels_ = std::make_unique<std::unique_ptr<PriceLevel>[]>(static_cast<std::size_t>(priceLevelCapacity));
        } catch (...) {
            // No ownership has transferred yet. Unfilled pool slots are null.
            if (orderPool_) {
                for (std::int32_t index = 0; index < orderCapacity; index++) delete orderPool_[index];
            }
            if (priceLevelPool_) {
                for (std::int32_t index = 0; index < priceLevelCapacity; index++) delete priceLevelPool_[index];
            }
            throw;
        }

        for (std::int32_t index = 0; index < orderCapacity; index++) {
            orders_[index].reset(orderPool_[index]);
        }
        for (std::int32_t index = 0; index < priceLevelCapacity; index++) {
            priceLevels_[index].reset(priceLevelPool_[index]);
        }
    }

    // Orders keep a pointer to their book, so a book never moves.
    OrderBook(const OrderBook&) = delete;
    OrderBook& operator=(const OrderBook&) = delete;

    Order& createLimit(std::int64_t id, const Side* side, std::int64_t size, std::int64_t price) {
        Order& order = acquireOrder(id, side, size, price, Type::LIMIT);
        match(order);
        if (order.isTerminal()) {
            releaseOrder(order);
        } else {
            rest(order);
        }
        return order;
    }

    void createMarket(std::int64_t id, const Side* side, std::int64_t size) {
        Order& order = acquireOrder(id, side, size, 0, Type::MARKET);
        match(order);
        releaseOrder(order);
    }

    bool isEmpty() const {
        return restingOrderCount_ == 0
                && head_[Side::BUY->index()] == nullptr
                && head_[Side::SELL->index()] == nullptr;
    }

    bool hasFullPoolCapacity() const {
        return availableOrders_ == orderCapacity_
                && availablePriceLevels_ == priceLevelCapacity_;
    }

    std::int32_t getRestingOrderCount() const {
        return restingOrderCount_;
    }

    std::int32_t getLevelCount(const Side* side) const {
        return levelCount_[side->index()];
    }

    std::int64_t getBestPrice(const Side* side) const {
        return head_[side->index()]->price();
    }

    std::int64_t getBestSize(const Side* side) const {
        return head_[side->index()]->size();
    }

    std::int64_t getMatchCount() const {
        return matchCount_;
    }

    std::int64_t getMatchedVolume() const {
        return matchedVolume_;
    }

    std::int64_t getLastExecutedPrice() const {
        return lastExecutedPrice_;
    }

    std::int64_t getLastMakerOrderId() const {
        return lastMakerOrderId_;
    }

private:
    // Order::reduceTo and Order::cancel call the package-private Java methods.
    friend class Order;

    void reduce(Order& order, std::int64_t newTotalSize) {
        if (newTotalSize <= order.getExecutedSize()) {
            cancel(order);
            return;
        }
        if (newTotalSize > order.getTotalSize()) newTotalSize = order.getTotalSize();

        std::int64_t canceledSize = order.getTotalSize() - newTotalSize;
        order.setTotalSize(newTotalSize);
        order.priceLevel()->reduceSize(canceledSize);
    }

    void cancel(Order& order) {
        PriceLevel* priceLevel = order.priceLevel();
        priceLevel->reduceSize(order.getOpenSize());
        order.setTotalSize(order.getExecutedSize());
        removeRestingOrder(order);
    }

    void match(Order& order) {
        std::int32_t oppositeIndex = order.getSide()->invertedIndex();
        PriceLevel* nextPriceLevel = nullptr;

        for (PriceLevel* priceLevel = head_[oppositeIndex];
                priceLevel != nullptr; priceLevel = nextPriceLevel) {
            nextPriceLevel = priceLevel->next_;
            if (order.getType() != Type::MARKET
                    && order.getSide()->isOutside(order.getPrice(), priceLevel->price())) break;

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

    void rest(Order& order) {
        PriceLevel& priceLevel = findPriceLevel(order.getSide(), order.getPrice());
        order.restAt(&priceLevel);
        priceLevel.addOrder(order);
        restingOrderCount_++;
    }

    PriceLevel& findPriceLevel(const Side* side, std::int64_t price) {
        std::int32_t sideIndex = side->index();
        PriceLevel* found = nullptr;
        for (PriceLevel* priceLevel = head_[sideIndex]; priceLevel != nullptr; priceLevel = priceLevel->next_) {
            if (!side->isOutside(price, priceLevel->price())) {
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

    void removeRestingOrder(Order& order) {
        PriceLevel* priceLevel = order.priceLevel();
        priceLevel->removeOrder(order);
        order.leaveBook();
        restingOrderCount_--;

        if (priceLevel->isEmpty()) removePriceLevel(*priceLevel);
        releaseOrder(order);
    }

    void removePriceLevel(PriceLevel& priceLevel) {
        std::int32_t sideIndex = priceLevel.side()->index();
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

    Order& acquireOrder(std::int64_t id, const Side* side, std::int64_t size, std::int64_t price, const Type* type) {
        if (availableOrders_ == 0) throw std::logic_error("order capacity exhausted");
        availableOrders_--;
        Order* order = orderPool_[availableOrders_];
        orderPool_[availableOrders_] = nullptr;
        order->initialize(this, id, side, size, price, type);
        return *order;
    }

    void releaseOrder(Order& order) {
        order.reset();
        orderPool_[availableOrders_] = &order;
        availableOrders_++;
    }

    PriceLevel& acquirePriceLevel(const Side* side, std::int64_t price) {
        if (availablePriceLevels_ == 0) throw std::logic_error("price-level capacity exhausted");
        availablePriceLevels_--;
        PriceLevel* priceLevel = priceLevelPool_[availablePriceLevels_];
        priceLevelPool_[availablePriceLevels_] = nullptr;
        priceLevel->initialize(side, price);
        return *priceLevel;
    }

    void releasePriceLevel(PriceLevel& priceLevel) {
        priceLevel.reset();
        priceLevelPool_[availablePriceLevels_] = &priceLevel;
        availablePriceLevels_++;
    }

    // Fields keep Java's declaration order, as in Order.
    std::unique_ptr<Order*[]> orderPool_;
    std::int32_t availableOrders_;
    std::unique_ptr<PriceLevel*[]> priceLevelPool_;
    std::int32_t availablePriceLevels_;
    std::unique_ptr<PriceLevel*[]> head_;
    std::unique_ptr<PriceLevel*[]> tail_;
    std::unique_ptr<std::int32_t[]> levelCount_;
    std::int32_t restingOrderCount_ = 0;
    std::int64_t matchCount_ = 0;
    std::int64_t matchedVolume_ = 0;
    std::int64_t lastExecutedPrice_ = 0;
    std::int64_t lastMakerOrderId_ = 0;

    // C++ only: the storage that owns the pooled objects, and the pool
    // lengths that Java reads from its arrays.
    std::unique_ptr<std::unique_ptr<Order>[]> orders_;
    std::int32_t orderCapacity_;
    std::unique_ptr<std::unique_ptr<PriceLevel>[]> priceLevels_;
    std::int32_t priceLevelCapacity_;
};

inline void Order::reduceTo(std::int64_t newTotalSize) {
    orderBook_->reduce(*this, newTotalSize);
}

inline void Order::cancel() {
    orderBook_->cancel(*this);
}

}

#endif
