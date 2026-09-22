// SPDX-License-Identifier: MIT OR Apache-2.0
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "org/ironwood/orderbook/JavaCompat.hpp"
#include "org/ironwood/orderbook/Order.hpp"
#include "org/ironwood/orderbook/OrderBook.hpp"

namespace org::ironwood::orderbook {

/**
 * Demonstrates resting, matching, reduction, cancellation and pool reuse.
 * The program prints primitive state snapshots and exits with status zero.
 */
class Main final {
public:
    Main() = delete;

    static void main(const std::vector<std::string>& args);

private:
    using Side = Order::Side;
};

void Main::main(const std::vector<std::string>& /* args */) {
    OrderBook book(8, 4);
    Order& firstBid = book.createLimit(1, Side::BUY, 60, 99);
    Order& secondBid = book.createLimit(2, Side::BUY, 40, 99);
    book.createLimit(3, Side::SELL, 80, 101);
    Order& secondAsk = book.createLimit(4, Side::SELL, 70, 102);

    // Java prints booleans as true and false.
    std::cout << std::boolalpha;

    std::cout << "initial" << '\n';
    std::cout << book.getBestPrice(Side::BUY) << '\n';
    std::cout << book.getBestSize(Side::BUY) << '\n';
    std::cout << book.getBestPrice(Side::SELL) << '\n';
    std::cout << book.getBestSize(Side::SELL) << '\n';

    book.createMarket(5, Side::BUY, 120);

    std::cout << "after-market" << '\n';
    std::cout << book.getBestPrice(Side::SELL) << '\n';
    std::cout << book.getBestSize(Side::SELL) << '\n';
    std::cout << book.getMatchCount() << '\n';
    std::cout << book.getMatchedVolume() << '\n';

    firstBid.reduceTo(30);
    secondAsk.cancel();
    book.createMarket(6, Side::SELL, 50);
    secondBid.cancel();

    std::cout << "final" << '\n';
    std::cout << book.isEmpty() << '\n';
    std::cout << book.hasFullPoolCapacity() << '\n';
    std::cout << book.getMatchCount() << '\n';
    std::cout << book.getMatchedVolume() << '\n';
    std::cout << book.getLastMakerOrderId() << '\n';

    if (!book.isEmpty() || !book.hasFullPoolCapacity()) throw std::logic_error("demonstration did not finish with an empty book");
    bool totalsCorrect = book.getMatchCount() == 4
            && book.getMatchedVolume() == 170
            && book.getLastMakerOrderId() == 2;
    if (!totalsCorrect) throw std::logic_error("demonstration totals are incorrect");
}

}

int main(int argc, char** argv) {
    return org::ironwood::orderbook::runMain(org::ironwood::orderbook::Main::main, argc, argv);
}
