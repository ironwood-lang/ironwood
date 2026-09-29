// SPDX-License-Identifier: MIT OR Apache-2.0
import genericbench.*;
import java.lang.management.ManagementFactory;

/** Compare cached generic and nongeneric reference getters, including JNI entry. */
public final class GenericBench {
    private static volatile long sink;
    public static void main(String[] args) {
        boolean generic = args[0].equals("generic");
        int iterations = Integer.parseInt(args[1]);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        Box<Quote> box = Box.quote();
        long coldBytes = bean.getThreadAllocatedBytes(thread), coldStart = System.nanoTime();
        Quote quote = box.get();
        long coldNs = System.nanoTime() - coldStart;
        coldBytes = bean.getThreadAllocatedBytes(thread) - coldBytes;
        Plain plain = new Plain(quote);
        if (plain.get() != quote) throw new AssertionError();
        System.err.println("first generic get: ns=" + coldNs + " java_bytes=" + coldBytes);
        System.out.println("scenario,sample,calls,elapsed_ns,checksum,native_allocations,java_bytes");
        try {
            for (int sample = -5; sample < 7; sample++) {
                long sum = 0, allocations = Box.allocations(), bytes = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    Quote value = generic ? box.get() : plain.get();
                    if (value == quote) sum++;
                }
                long elapsed = System.nanoTime() - start;
                bytes = bean.getThreadAllocatedBytes(thread) - bytes;
                allocations = Box.allocations() - allocations;
                sink = sum;
                if (sum != iterations || allocations != 0) throw new AssertionError("getter changed its result or allocated");
                if (sample >= 0) System.out.println(args[0] + "," + sample + "," + iterations + "," + elapsed + ","
                        + sum + "," + allocations + "," + bytes);
            }
        } finally {
            plain.free();
            box.free();
        }
    }
}
