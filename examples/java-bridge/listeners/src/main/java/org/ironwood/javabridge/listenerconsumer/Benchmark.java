// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.listenerconsumer;

import java.lang.management.ManagementFactory;
import org.ironwood.javabridge.listeners.ResultListener;
import org.ironwood.javabridge.listeners.ResultProcessor;

/** Identical event recurrence and listener work for Java/Java and native/Java. */
public final class Benchmark {
    private Benchmark() {}

    private static final class Collector implements ResultListener {
        private long checksum;
        private long events;
        @Override public void onResult(long sequence, long value) {
            checksum = (checksum << 7) ^ (checksum >>> 3) ^ value ^ sequence;
            events++;
        }
        private void reset() { checksum = 0L; events = 0L; }
    }

    private static final class JavaProcessor {
        private final ResultListener listener;
        private JavaProcessor(ResultListener listener) { this.listener = listener; }
        private long process(int count, long seed) {
            if (count < 0) throw new IllegalArgumentException("negative count");
            ResultListener current = listener;
            long value = seed;
            for (int sequence = 0; sequence < count; sequence++) {
                value = (value ^ (value >>> 13)) * 2862933555777941757L + 3037000493L;
                current.onResult(sequence, value);
            }
            return value;
        }
    }

    public static void main(String[] args) {
        if (args.length != 4) throw new IllegalArgumentException("scenario events warmups samples");
        boolean bridge = args[0].equals("native-java");
        if (!bridge && !args[0].equals("java-java")) throw new IllegalArgumentException("unknown scenario");
        int count = Integer.parseInt(args[1]), warmups = Integer.parseInt(args[2]), samples = Integer.parseInt(args[3]);
        if (count <= 0 || warmups < 0 || samples <= 0) throw new IllegalArgumentException("invalid sample sizes");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        Collector listener = new Collector();
        JavaProcessor java = new JavaProcessor(listener);
        ResultProcessor nativeProcessor = bridge ? new ResultProcessor() : null;
        try {
            if (bridge) nativeProcessor.setListener(listener);
            System.out.println("scenario,sample,events,elapsed_ns,checksum,last_value,native_allocations,java_bytes");
            for (int sample = -warmups; sample < samples; sample++) {
                listener.reset();
                long before = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                long last = bridge ? nativeProcessor.process(count, 17L + sample) : java.process(count, 17L + sample);
                long elapsed = System.nanoTime() - start;
                long allocated = bean.getThreadAllocatedBytes(thread) - before;
                if (listener.events != count) throw new AssertionError("lost callback");
                // Report after the closing clock read. The runner independently
                // verifies both recurrences and every event count in each sample.
                if (sample >= 0) System.out.println(args[0] + "," + sample + "," + count + "," + elapsed + ","
                        + listener.checksum + "," + last + ",-1," + allocated);
            }
        } finally {
            if (nativeProcessor != null) nativeProcessor.free();
        }
    }
}
