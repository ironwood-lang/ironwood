// SPDX-License-Identifier: MIT OR Apache-2.0

import bridgeperf.PerformanceProbe;
import java.util.Arrays;

/** Separate cold, warmed throughput and sampled latency observations. */
public final class PerformanceConsumer {
    private static volatile long sink;
    private static PerformanceProbe receiver;
    private static long address;
    private static native long bare(long seed);
    private static native long address();
    private static native long bareInstance(long pointer, long seed);
    private PerformanceConsumer() {}

    private static long javaScalar(long seed) { return seed * 2862933555777941757L + 3037000493L; }

    private static long operation(String mode, long seed) throws Exception {
        return switch (mode) {
            case "java" -> javaScalar(seed);
            case "bare" -> bare(seed);
            case "scalar" -> PerformanceProbe.scalar(seed);
            case "bare-instance" -> bareInstance(address, seed);
            case "instance" -> receiver.instance(seed);
            case "batch" -> PerformanceProbe.batch(256, seed);
            case "string" -> PerformanceProbe.copy("bridge-\u0000-\ud83d\ude00").length() + seed;
            case "object" -> {
                if (receiver.self() != receiver) throw new AssertionError("cache-hit identity");
                yield seed + 1;
            }
            case "exception" -> {
                try { PerformanceProbe.fail(); throw new AssertionError("missing exception"); }
                catch (java.io.IOException expected) {
                    if (!expected.getMessage().equals("measured failure")) throw expected;
                    yield seed + 1;
                }
            }
            case "clock" -> seed + 1;
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static long loop(String mode, int count, long seed) throws Exception {
        switch (mode) {
            case "java": for (int i = 0; i < count; i++) seed = javaScalar(seed); break;
            case "bare": for (int i = 0; i < count; i++) seed = bare(seed); break;
            case "scalar": for (int i = 0; i < count; i++) seed = PerformanceProbe.scalar(seed); break;
            case "bare-instance": for (int i = 0; i < count; i++) seed = bareInstance(address, seed); break;
            case "instance": for (int i = 0; i < count; i++) seed = receiver.instance(seed); break;
            case "batch": for (int i = 0; i < count; i++) seed = PerformanceProbe.batch(256, seed); break;
            case "string": for (int i = 0; i < count; i++) seed += PerformanceProbe.copy("bridge-\u0000-\ud83d\ude00").length(); break;
            case "object":
                for (int i = 0; i < count; i++) {
                    if (receiver.self() != receiver) throw new AssertionError("cache-hit identity");
                    seed++;
                }
                break;
            case "exception":
                for (int i = 0; i < count; i++) {
                    try { PerformanceProbe.fail(); throw new AssertionError("missing exception"); }
                    catch (java.io.IOException expected) {
                        if (!expected.getMessage().equals("measured failure")) throw expected;
                        seed++;
                    }
                }
                break;
            case "clock": for (int i = 0; i < count; i++) seed++; break;
            default: throw new IllegalArgumentException(mode);
        }
        return seed;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("cold")) {
            long start = System.nanoTime();
            long value = PerformanceProbe.scalar(19);
            long elapsed = System.nanoTime() - start;
            if (value != javaScalar(19)) throw new AssertionError("cold checksum");
            System.out.println("cold-ns=" + elapsed); return;
        }
        if (mode.startsWith("bare")) { System.load(args[1]); address = address(); }
        if (mode.equals("instance") || mode.equals("object")) receiver = new PerformanceProbe(17).publish();
        int count = switch (mode) { case "exception" -> 1000; case "string" -> 20000; case "batch" -> 20000; default -> 1000000; };
        int warm = mode.equals("exception") ? 500 : Math.max(20000, count / 2);
        long value = loop(mode, warm, 19);
        sink = value;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().threadId();
        long[] durations = new long[7]; long[] bytes = new long[7];
        for (int trial = 0; trial < durations.length; trial++) {
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            value = loop(mode, count, 19);
            durations[trial] = System.nanoTime() - start; bytes[trial] = bean.getThreadAllocatedBytes(thread) - before; sink = value;
            long expected = 19;
            if (mode.equals("java") || mode.equals("bare") || mode.equals("scalar") || mode.equals("batch")) {
                int iterations = mode.equals("batch") ? count * 256 : count;
                for (int i = 0; i < iterations; i++) expected = javaScalar(expected);
            } else expected += (long)count * (mode.endsWith("instance") ? 17 : mode.equals("string") ? "bridge-\u0000-\ud83d\ude00".length() : 1);
            if (value != expected) throw new AssertionError("measured checksum: " + mode);
        }
        long[] latency = new long[1001]; value = 19;
        for (int i = 0; i < latency.length; i++) {
            long start = System.nanoTime(); value = operation(mode, value); latency[i] = System.nanoTime() - start;
        }
        sink = value; Arrays.sort(latency);
        System.out.println("mode=" + mode + " count=" + count + " units=" + (mode.equals("batch") ? 256 : 1));
        System.out.println("duration-ns=" + Arrays.toString(durations));
        System.out.println("java-bytes=" + Arrays.toString(bytes));
        System.out.println("sampled-latency-ns=" + latency[500] + "," + latency[950] + "," + latency[990]);
        System.out.println("checksum=" + sink);
        java.lang.ref.Reference.reachabilityFence(receiver);
    }
}
