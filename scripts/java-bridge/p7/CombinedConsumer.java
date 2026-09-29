// SPDX-License-Identifier: MIT OR Apache-2.0
import arraybench.ArrayOps;
import bytebench.ByteOps;
import boundedbench.Holder;
import boundedbench.Value;
import genericbench.Box;
import genericbench.Quote;
import ironwood.bridge.ByteView;
import org.ironwood.javabridge.listeners.ResultProcessor;

/** Exercises ordinary APIs from independently packaged native worlds in one JVM. */
public final class CombinedConsumer {
    private CombinedConsumer() {}
    private static void check(boolean value) {
        if (!value) throw new AssertionError("combined bridge result");
    }
    private static void lifetime(Runnable action) {
        try { action.run(); throw new AssertionError("unsafe operation accepted"); }
        catch (IllegalStateException expected) {
            check(expected.getClass().getSimpleName().equals("BridgeLifetimeException"));
        }
    }
    public static void main(String[] args) {
        ByteView bytes = ByteView.allocate(4);
        bytes.put(0, (byte)3);
        int[] array = {1, 2};
        Value first = new Value(17), second = new Value(29);
        Holder<Value> holder = new Holder<>(first);
        Box<Quote> box = Box.quote();
        Quote shared = box.get();
        ResultProcessor processor = new ResultProcessor();
        long[] events = {0};
        try {
            lifetime(first::free);
            check(holder.echo(first) == first && shared.value() == 17);
            processor.setListener((sequence, value) -> {
                // Reentry crosses independent native worlds on the same Java thread.
                lifetime(processor::free);
                ArrayOps.update(array);
                ByteOps.update(bytes);
                holder.set((sequence & 1) == 0 ? first : second);
                check(holder.read() == ((sequence & 1) == 0 ? 17 : 29));
                check(box.get() == shared);
                events[0]++;
            });
            processor.process(4, 17L);
            check(events[0] == 4 && ArrayOps.read(array) == 3 && ByteOps.read(bytes) == 19);
            check(java.util.Arrays.equals(ArrayOps.fresh(3, 7), new int[]{7, 6, 5}));
            check(ByteOps.overlap(bytes.slice(0, 3), bytes.slice(1, 3)) == 27);
            check(bytes.get(3) == 10);
            try { ByteOps.update(bytes.asReadOnly()); throw new AssertionError("read-only write"); }
            catch (UnsupportedOperationException expected) { check(bytes.get(0) == 7); }
            RuntimeException failure = new RuntimeException("cross-world listener failure");
            processor.setListener((sequence, value) -> {
                holder.set(first);
                ByteOps.update(bytes);
                throw failure;
            });
            try { processor.process(3, 19); throw new AssertionError("missing callback failure"); }
            catch (RuntimeException actual) { check(actual == failure); }
            check(holder.read() == 17 && ByteOps.read(bytes) == 38);
            processor.setListener((sequence, value) -> events[0]++);
            processor.process(1, 23);
            check(events[0] == 5);
            processor.setListener(null);
        } finally {
            // Owners/holders release their dependencies before dependent roots.
            processor.free();
            holder.free();
            box.free();
            second.free();
            first.free();
        }
        processor.free();
        lifetime(() -> processor.process(1, 0));
        lifetime(box::get);
        // The factory's static Quote has process lifetime, independent of Box.
        check(shared.value() == 17);
        lifetime(holder::read);
        System.out.println("p7-combined-ok");
    }
}
