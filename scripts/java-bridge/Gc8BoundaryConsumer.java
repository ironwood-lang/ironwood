// SPDX-License-Identifier: MIT OR Apache-2.0
import gc8.BoundaryCounters;
import org.ironwood.javabridge.listeners.ResultListener;
import org.ironwood.javabridge.listeners.ResultProcessor;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Production facade calls, retained callbacks and explicitly collecting reentry. */
public final class Gc8BoundaryConsumer {
    private static volatile long sink;
    private Gc8BoundaryConsumer() {}
    private static final class Listener implements ResultListener {
        long checksum, events;
        boolean reentry;
        @Override public void onResult(long sequence, long value) {
            if (reentry) BoundaryCounters.collect();
            checksum = (checksum << 7) ^ (checksum >>> 3) ^ value ^ sequence;
            events++;
        }
    }
    private static long oracle(int count, long seed) {
        for (int i = 0; i < count; i++) seed = seed * 2862933555777941757L + 3037000493L;
        return seed;
    }
    public static void main(String[] args) {
        boolean callback = args[0].startsWith("callback"), reentry = args[0].equals("callback-reentry");
        int count = reentry ? 32 : callback ? 200000 : 4000000;
        Listener listener = new Listener(); listener.reentry = reentry;
        try (var anchor = callback ? BridgeLifetime.<ResultProcessor>construct(ResultProcessor.class) : null;
             var registration = callback ? BridgeLifetime.registration(ResultProcessor.class) : null) {
            ResultProcessor processor = anchor == null ? null : anchor.get();
            if (callback) registration.setter(ResultProcessor.class, "setListener", ResultListener.class).set(processor, listener);
            BoundaryCounters.collect();
            for (int trial = -4; trial < 7; trial++) {
                listener.checksum = 0; listener.events = 0;
                long before = BoundaryCounters.total(), live = BoundaryCounters.live(), collects = BoundaryCounters.collections();
                long value = 19, start = System.nanoTime();
                if (callback) value = processor.process(count, value);
                else for (int i = 0; i < count; i++) value = BoundaryCounters.scalar(value);
                long elapsed = System.nanoTime() - start;
                sink = value;
                if (BoundaryCounters.total() != before || BoundaryCounters.live() != live
                        || BoundaryCounters.collections() - collects != (reentry ? count : 0))
                    throw new AssertionError("unexpected allocation/collection");
                long expected = 19, checksum = 0;
                if (callback) {
                    for (int i = 0; i < count; i++) {
                        expected = (expected ^ (expected >>> 13)) * 2862933555777941757L + 3037000493L;
                        checksum = (checksum << 7) ^ (checksum >>> 3) ^ expected ^ i;
                    }
                    if (listener.events != count || listener.checksum != checksum) throw new AssertionError("callback events");
                } else expected = oracle(count, 19);
                if (value != expected) throw new AssertionError("checksum");
                if (trial >= 0) System.out.println("{\"ns\":" + elapsed + ",\"operations\":" + count
                        + ",\"allocations\":0,\"explicit_collections\":" + (reentry ? count : 0) + "}");
            }
        }
    }
}
