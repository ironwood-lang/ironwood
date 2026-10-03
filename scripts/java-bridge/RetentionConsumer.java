// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import mixedlife.Holder;

/** Measures alternating retained-root updates in the exact P6a roots jar. */
public final class RetentionConsumer {
    private static final int PAIRS = 100000;
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void update(Holder holder, Holder.Item a, Holder.Item b, Holder.Catalog catalog) {
        for (int i = 0; i < PAIRS; i++) {
            holder.change(a, catalog, Holder.Side.SELL);
            holder.change(b, catalog, Holder.Side.BUY);
        }
    }
    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        Holder.Catalog catalog = new Holder.Catalog().remember(); // Proven permanent publication.
        Holder.Item a = new Holder.Item(), b = new Holder.Item();
        Holder holder = new Holder(a, catalog, Holder.Side.SELL, "retention");
        long[] durations = new long[7], allocated = new long[7];
        for (int warm = 0; warm < 5; warm++) update(holder, a, b, catalog);
        for (int trial = 0; trial < durations.length; trial++) {
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            update(holder, a, b, catalog);
            durations[trial] = System.nanoTime() - start;
            allocated[trial] = bean.getThreadAllocatedBytes(thread) - before;
            check(holder.side() == Holder.Side.BUY && holder.catalog() == catalog);
        }
        // The final native write must retain b until clear. Check outside timing.
        try { b.free(); throw new AssertionError("retained root was freed"); }
        catch (IllegalStateException expected) { }
        holder.clear(); holder.free(); a.free(); b.free();
        System.out.println("retention-calls=" + (PAIRS * 2));
        System.out.println("duration-ns=" + Arrays.toString(durations));
        System.out.println("java-bytes=" + Arrays.toString(allocated));
        System.out.println("retention-lifetime-ok");
    }
}
