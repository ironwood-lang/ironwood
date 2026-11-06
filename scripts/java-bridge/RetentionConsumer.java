// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import mixedlife.Holder;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Measures alternating retained-root updates in the current managed roots jar. */
public final class RetentionConsumer {
    private static final int PAIRS = Integer.getInteger("ironwood.measure.pairs", 100000);
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void update(Holder holder, Holder.Item a, Holder.Item b, Holder.Catalog catalog) {
        for (int i = 0; i < PAIRS; i++) {
            holder.change(a, catalog, Holder.Side.SELL);
            holder.change(b, catalog, Holder.Side.BUY);
        }
    }
    private static void release(BridgeLifetime.Anchor<?> root) { root.close(); }
    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        try (var catalogRoot = BridgeLifetime.<Holder.Catalog>construct(Holder.Catalog.class);
             var aRoot = BridgeLifetime.<Holder.Item>construct(Holder.Item.class);
             var bRoot = BridgeLifetime.<Holder.Item>construct(Holder.Item.class);
             var holderRoot = BridgeLifetime.<Holder>construct(Holder.class, aRoot.get(), catalogRoot.get(), Holder.Side.SELL, "retention")) {
        Holder.Catalog catalog = catalogRoot.get();
        Holder.Item a = aRoot.get(), b = bRoot.get();
        Holder holder = holderRoot.get();
        long[] durations = new long[7], allocated = new long[7];
        for (int warm = 0; warm < 5; warm++) update(holder, a, b, catalog);
        for (int trial = 0; trial < durations.length; trial++) {
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            update(holder, a, b, catalog);
            durations[trial] = System.nanoTime() - start;
            allocated[trial] = bean.getThreadAllocatedBytes(thread) - before;
            try (var retained = holder.catalog()) {
                check(holder.side() == Holder.Side.BUY && retained.value() == catalog);
            }
        }
        // Anchor closure is independent of native graph retention. Check outside timing.
        release(bRoot);
        Holder.collect();
        holder.change(a, catalog, Holder.Side.SELL);
        holder.clear();
        System.out.println("retention-calls=" + (PAIRS * 2));
        System.out.println("duration-ns=" + Arrays.toString(durations));
        System.out.println("java-bytes=" + Arrays.toString(allocated));
        System.out.println("retention-lifetime-ok");
        }
    }
}
