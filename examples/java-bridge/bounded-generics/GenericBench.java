// SPDX-License-Identifier: MIT OR Apache-2.0
import boundedbench.*;
import java.lang.management.ManagementFactory;

/** Compare identical retaining setters, alternating two independent roots. */
public final class GenericBench {
    private static volatile long sink;
    public static void main(String[] args) {
        boolean generic = args[0].equals("generic");
        int iterations = Integer.parseInt(args[1]);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        Value first = new Value(17), second = new Value(29);
        Holder<Value> holder = new Holder<>(null);
        Plain plain = new Plain(null);
        System.out.println("scenario,sample,calls,elapsed_ns,checksum,native_allocations,java_bytes");
        try {
            for (int sample = -5; sample < 7; sample++) {
                long sum = 0, allocations = Holder.allocations(), bytes = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    Value value = (i & 1) == 0 ? first : second;
                    if (generic) holder.set(value); else plain.set(value);
                    sum++;
                }
                long elapsed = System.nanoTime() - start;
                bytes = bean.getThreadAllocatedBytes(thread) - bytes;
                allocations = Holder.allocations() - allocations;
                sink = sum;
                int expected = (iterations & 1) == 0 ? 29 : 17;
                if ((generic ? holder.read() : plain.read()) != expected || allocations != 0) throw new AssertionError();
                if (sample >= 0) System.out.println(args[0] + "," + sample + "," + iterations + "," + elapsed + ","
                        + sum + "," + allocations + "," + bytes);
            }
        } finally {
            plain.free();
            holder.free();
            second.free();
            first.free();
        }
    }
}
