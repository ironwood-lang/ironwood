// SPDX-License-Identifier: MIT OR Apache-2.0
import genericbench.*;

/** Ordinary generated types and methods, with explicit root cleanup. */
public final class Consumer {
    public static void main(String[] args) {
        Box<Quote> quote = Box.quote();
        Box<Trade> trade = Box.trade();
        try {
            Box<?> view = trade;
            if (!(view.get() instanceof Trade) || quote.get().value() != 17 || trade.get().value() != 29) {
                throw new AssertionError();
            }
            System.out.println("generic values: 17 29");
        } finally {
            trade.free();
            quote.free();
        }
    }
}
