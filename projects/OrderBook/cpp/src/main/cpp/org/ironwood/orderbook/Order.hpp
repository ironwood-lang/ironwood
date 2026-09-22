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
    enum class Side : std::uint8_t {
        BUY = 0,
        SELL = 1
    };

    enum class Type : std::uint8_t {
        LIMIT,
        MARKET
    };

    Order(const Order&) = delete;
    Order& operator=(const Order&) = delete;

    std::int64_t getId() const noexcept {
        return id_;
    }

    Side getSide() const noexcept {
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

    Type getType() const noexcept {
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

    Order() = default;

    void initialize(OrderBook* orderBook, std::int64_t id, Side side, std::int64_t size,
            std::int64_t price, Type type) noexcept {
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

    // Java clears side and type to null. C++ enums have no null, so they
    // return to their zero values.
    void reset() noexcept {
        orderBook_ = nullptr;
        id_ = 0;
        side_ = Side{};
        totalSize_ = 0;
        executedSize_ = 0;
        price_ = 0;
        type_ = Type{};
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

    // Eight-byte members come first so the three small members share one word.
    OrderBook* orderBook_ = nullptr;
    std::int64_t id_ = 0;
    std::int64_t totalSize_ = 0;
    std::int64_t executedSize_ = 0;
    std::int64_t price_ = 0;
    PriceLevel* priceLevel_ = nullptr;
    Order* next_ = nullptr;
    Order* previous_ = nullptr;
    Side side_ = Side{};
    Type type_ = Type{};
    bool resting_ = false;
};

// The methods of Java's Order.Side enum.

constexpr std::int32_t index(Order::Side side) noexcept {
    return static_cast<std::int32_t>(side);
}

constexpr std::int32_t invertedIndex(Order::Side side) noexcept {
    return side == Order::Side::BUY ? index(Order::Side::SELL) : index(Order::Side::BUY);
}

constexpr bool isOutside(Order::Side side, std::int64_t price, std::int64_t marketPrice) noexcept {
    return side == Order::Side::BUY ? price < marketPrice : price > marketPrice;
}

}

// Order::reduceTo and Order::cancel are defined in OrderBook.hpp, which
// completes OrderBook.

#endif
