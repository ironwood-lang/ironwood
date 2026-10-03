// SPDX-License-Identifier: MIT OR Apache-2.0
import bytebench.ByteOps;
import ironwood.bridge.ByteView;
import java.lang.management.ManagementFactory;

/** Same consumer and kernels for Java and JNI; allocation stays outside timed calls. */
public final class JavaBench {
    private JavaBench() {}
    public static void main(String[] args) {
        int operation = Integer.parseInt(args[0]), size = Integer.parseInt(args[1]), iterations = Integer.parseInt(args[2]);
        String scenario = args[3];
        boolean viewed = scenario.endsWith("view");
        byte[] array = new byte[size];
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long coldBytes = bean.getThreadAllocatedBytes(thread), coldStart = System.nanoTime();
        ByteView view = ByteView.allocate(size), first = view.slice(0, size - 1), second = view.slice(1, size - 1);
        long coldTime = System.nanoTime() - coldStart;
        coldBytes = bean.getThreadAllocatedBytes(thread) - coldBytes;
        System.err.println("cold storage and two slices: ns=" + coldTime + " java_bytes=" + coldBytes);
        System.out.println("scenario,operation,size,sample,calls,elapsed_ns,checksum,native_allocations,java_bytes");
        for (int sample = -4; sample < 7; sample++) {
            int seed = 17;
            long checksum = 0;
            for (int i = 0; i < size; i++) { array[i] = (byte)i; view.put(i, (byte)i); }
            long allocations = ByteOps.allocations(), bytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                seed = (seed * 1664525 + 1013904223) & 0x7fffffff;
                if (viewed) {
                    view.put(i % size, (byte)seed);
                    if (operation == 0) checksum += ByteOps.read(view);
                    else if (operation == 1) checksum += ByteOps.update(view);
                    else checksum += ByteOps.overlap(first, second);
                } else {
                    array[i % size] = (byte)seed;
                    if (operation == 0) checksum += ByteOps.read(array);
                    else if (operation == 1) checksum += ByteOps.update(array);
                    else checksum += ByteOps.overlap(array);
                }
            }
            long elapsed = System.nanoTime() - start;
            bytes = bean.getThreadAllocatedBytes(thread) - bytes;
            allocations = ByteOps.allocations() - allocations;
            if (sample >= 0) System.out.println(scenario + "," + operation + "," + size + "," + sample + ","
                    + iterations + "," + elapsed + "," + checksum + "," + allocations + "," + bytes);
        }
    }
}
