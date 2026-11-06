// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.listenerconsumer;

import org.ironwood.javabridge.listeners.ResultListener;
import org.ironwood.javabridge.listeners.ResultProcessor;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Anchors, listener registration, reentry and exception identity have separate lifetimes. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try (var anchor = BridgeLifetime.<ResultProcessor>construct(ResultProcessor.class);
             var registration = BridgeLifetime.registration(ResultProcessor.class)) {
            ResultProcessor processor = anchor.get();
            var setter = registration.setter(ResultProcessor.class, "setListener", ResultListener.class);
            long[] events = {0L};
            setter.set(processor, (sequence, value) -> events[0]++);
            processor.process(8, 17L);
            if (events[0] != 8L) throw new AssertionError("lost result");
            setter.set(processor, (sequence, value) -> {
                // The suspended invocation keeps its captured target. The replacement
                // serves nested invocations and subsequent calls.
                setter.set(processor, (nestedSequence, nestedValue) -> events[0]++);
                processor.process(1, value);
            });
            processor.process(2, 19L);
            if (events[0] != 10L) throw new AssertionError("lost nested result");
            RuntimeException failure = new RuntimeException("listener failure");
            setter.set(processor, (sequence, value) -> { throw failure; });
            try { processor.process(1, 23L); throw new AssertionError("lost failure"); }
            catch (RuntimeException actual) { if (actual != failure) throw actual; }
            setter.set(processor, (sequence, value) -> events[0]++);
            processor.process(1, 29L);
            if (events[0] != 11L) throw new AssertionError("cannot continue after failure");
            setter.set(processor, null);
        }
        System.out.println("listeners-ok: registration, reentry, exception identity, anchors");
    }
}
