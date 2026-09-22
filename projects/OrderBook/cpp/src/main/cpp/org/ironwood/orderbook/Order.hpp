// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef ORG_IRONWOOD_ORDERBOOK_ORDER_HPP
#define ORG_IRONWOOD_ORDERBOOK_ORDER_HPP

#include <cstdint>

namespace org::ironwood::orderbook {

class OrderBook;
class PriceLevel;

/** A reusable order owned by one OrderBook. */
class Order final {
public:
    class Side final {
    public:
        static const Side* const BUY;
        static const Side* const SELL;

        Side(const Side&) = delete;
        Side& operator=(const Side&) = delete;

        std::int32_t index() const noexcept {
            return index_;
        }

        std::int32_t invertedIndex() const noexcept {
            return this == BUY ? SELL->index() : BUY->index();
        }

        bool isOutside(std::int64_t price, std::int64_t marketPrice) const noexcept {
            return this == BUY ? price < marketPrice : price > marketPrice;
        }

    private:
        explicit constexpr Side(std::int32_t index) noexcept : index_(index) {}

        const std::int32_t index_;

        static const Side buy_;
        static const Side sell_;
    };

    class Type final {
    public:
        static const Type* const LIMIT;
        static const Type* const MARKET;

        Type(const Type&) = delete;
        Type& operator=(const Type&) = delete;

    private:
        constexpr Type() noexcept = default;

        static const Type limit_;
        static const Type market_;
    };

    Order(const Order&) = delete;
    Order& operator=(const Order&) = delete;

    std::int64_t getId() const noexcept {
        return id_;
    }

    const Side* getSide() const noexcept {
        return side_;
    }

    std::int64_t getTotalSize() const noexcept {
        return totalSize_;
    }

    std::int64_t getExecutedSize() const noexcept {
        return executedSize_;
    }

    std::int64_t getOpenSize() const noexcept {
        return totalSize_ - executedSize_;
    }

    std::int64_t getPrice() const noexcept {
        return price_;
    }

    const Type* getType() const noexcept {
        return type_;
    }

    bool isResting() const noexcept {
        return resting_;
    }

    bool isTerminal() const noexcept {
        return getOpenSize() == 0;
    }

    /** Reduces the total size while preserving any already executed size. */
    inline void reduceTo(std::int64_t newTotalSize) noexcept;

    /** Cancels all remaining open size. */
    inline void cancel() noexcept;

private:
    // OrderBook and PriceLevel use the members that are package-private in Java.
    friend class OrderBook;
    friend class PriceLevel;
    // Tests inspect pool resets through Java's package-private access.
    friend class BenchmarkTests;

    Order() = default;

    void initialize(OrderBook* orderBook, std::int64_t id, const Side* side, std::int64_t size,
            std::int64_t price, const Type* type) noexcept {
        orderBook_ = orderBook;
        id_ = id;
        side_ = side;
        totalSize_ = size;
        executedSize_ = 0;
        price_ = price;
        type_ = type;
        resting_ = false;
        priceLevel_ = nullptr;
        next_ = nullptr;
        previous_ = nullptr;
    }

    void reset() noexcept {
        orderBook_ = nullptr;
        id_ = 0;
        side_ = nullptr;
        totalSize_ = 0;
        executedSize_ = 0;
        price_ = 0;
        type_ = nullptr;
        resting_ = false;
        priceLevel_ = nullptr;
        next_ = nullptr;
        previous_ = nullptr;
    }

    void restAt(PriceLevel* priceLevel) noexcept {
        priceLevel_ = priceLevel;
        resting_ = true;
    }

    void leaveBook() noexcept {
        priceLevel_ = nullptr;
        resting_ = false;
        next_ = nullptr;
        previous_ = nullptr;
    }

    void execute(std::int64_t size) noexcept {
        executedSize_ += size;
    }

    void setTotalSize(std::int64_t totalSize) noexcept {
        totalSize_ = totalSize;
    }

    PriceLevel* priceLevel() const noexcept {
        return priceLevel_;
    }

    // Fields keep Java's declaration order. Layout decides which fields the
    // optimizer can combine into one memory access, so reordering them would
    // change the machine code this benchmark compares.
    OrderBook* orderBook_ = nullptr;
    std::int64_t id_ = 0;
    const Side* side_ = nullptr;
    std::int64_t totalSize_ = 0;
    std::int64_t executedSize_ = 0;
    std::int64_t price_ = 0;
    const Type* type_ = nullptr;
    bool resting_ = false;
    PriceLevel* priceLevel_ = nullptr;
    Order* next_ = nullptr;
    Order* previous_ = nullptr;
};

// One object per enum constant across translation units, with static lifetime.
// The public constants are pointers, matching Java and Ironwood references.
inline const Order::Side Order::Side::buy_{0};
inline const Order::Side Order::Side::sell_{1};
inline const Order::Side* const Order::Side::BUY = &Order::Side::buy_;
inline const Order::Side* const Order::Side::SELL = &Order::Side::sell_;

inline const Order::Type Order::Type::limit_{};
inline const Order::Type Order::Type::market_{};
inline const Order::Type* const Order::Type::LIMIT = &Order::Type::limit_;
inline const Order::Type* const Order::Type::MARKET = &Order::Type::market_;

}

// Order::reduceTo and Order::cancel are defined in OrderBook.hpp, which
// completes OrderBook.

#endif
