// SPDX-License-Identifier: MIT OR Apache-2.0
import genericbench.*;

/** Ordinary generated types and methods, with explicit RootHandle anchors. */
public final class Consumer {
    public static void main(String[] args) {
        try (var quoteRoot = Box.quote(); var tradeRoot = Box.trade()) {
            Box<Quote> quote = quoteRoot.value();
            Box<Trade> trade = tradeRoot.value();
            Box<?> view = trade;
            try (var viewRoot = view.get(); var quoteValue = quote.get(); var tradeValue = trade.get()) {
            if (!(viewRoot.value() instanceof Trade) || quoteValue.value().value() != 17 || tradeValue.value().value() != 29) {
                throw new AssertionError();
            }
            }
            System.out.println("generic values: 17 29");
        }
    }
}
