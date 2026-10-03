// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.listenerconsumer;

import org.ironwood.javabridge.listeners.ResultProcessor;

/** Exercises retained registration, reentry, failure identity and explicit cleanup. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        ResultProcessor processor = new ResultProcessor();
        long[] events = {0L};
        try {
            processor.setListener((sequence, value) -> events[0]++);
            processor.process(8, 17L);
            if (events[0] != 8L) throw new AssertionError("lost result");
            processor.setListener((sequence, value) -> {
                try { processor.free(); throw new AssertionError("freed active processor"); }
                catch (IllegalStateException refused) {
                    if (!refused.getClass().getSimpleName().equals("BridgeLifetimeException")) throw refused;
                }
                // The suspended call keeps its original registration. This new
                // registration is used by the nested invocation and future calls.
                processor.setListener((nestedSequence, nestedValue) -> events[0]++);
                processor.process(1, value);
            });
            processor.process(2, 19L);
            if (events[0] != 10L) throw new AssertionError("lost nested result");
            RuntimeException failure = new RuntimeException("listener failure");
            processor.setListener((sequence, value) -> { throw failure; });
            try { processor.process(1, 23L); throw new AssertionError("lost failure"); }
            catch (RuntimeException actual) { if (actual != failure) throw actual; }
            processor.setListener((sequence, value) -> events[0]++);
            processor.process(1, 29L);
            if (events[0] != 11L) throw new AssertionError("cannot continue after failure");
            processor.setListener(null);
        } finally {
            processor.free();
        }
        processor.free();
        System.out.println("listeners-ok: retained registration, reentry, exception identity, cleanup");
    }
}
