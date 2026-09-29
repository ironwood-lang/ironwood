// SPDX-License-Identifier: MIT OR Apache-2.0
import arraybench.ArrayOps;
import java.lang.management.ManagementFactory;

/** One compiled consumer is run with either the generated facade or Java baseline. */
public final class JavaBench {
    private JavaBench() {}
    public static void main(String[] args) {
        int operation = Integer.parseInt(args[0]), size = Integer.parseInt(args[1]), iterations = Integer.parseInt(args[2]);
        String scenario = args[3];
        int[] values = new int[size];
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        System.out.println("scenario,operation,size,sample,calls,elapsed_ns,checksum,native_allocations,java_bytes");
        for (int sample = -3; sample < 7; sample++) {
            int seed = 17;
            long checksum = 0;
            for (int i = 0; i < size; i++) values[i] = i;
            long live = ArrayOps.live(), allocations = ArrayOps.allocations();
            long bytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                seed = (seed * 1664525 + 1013904223) & 0x7fffffff;
                values[i % size] = seed;
                if (operation == 0) checksum += ArrayOps.read(values);
                else if (operation == 1) checksum += ArrayOps.update(values);
                else { int[] result = ArrayOps.fresh(size, seed); checksum += result[size - 1]; }
            }
            long elapsed = System.nanoTime() - start;
            bytes = bean.getThreadAllocatedBytes(thread) - bytes;
            allocations = ArrayOps.allocations() - allocations;
            if (ArrayOps.live() != live) throw new AssertionError("temporary native storage leaked");
            if (sample >= 0) System.out.println(scenario + "," + operation + "," + size + "," + sample + ","
                    + iterations + "," + elapsed + "," + checksum + "," + allocations + "," + bytes);
        }
    }
}
