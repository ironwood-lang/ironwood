// SPDX-License-Identifier: MIT OR Apache-2.0

import batchstackprobe.BatchStackListener;
import batchstackprobe.QualificationBatchStack;

/** Child-only stack checks with suspended, distinct buffers at every recursion depth. */
public final class QualificationBatchStackConsumer implements BatchStackListener {
    private static final RuntimeException FAILURE = new RuntimeException("deepest batched callback failure");
    private static final QualificationBatchStack ROOT = new QualificationBatchStack();
    private static final QualificationBatchStackConsumer LISTENER = new QualificationBatchStackConsumer();
    private int depth;
    private long delivered;
    private long value;
    private boolean fail;

    private QualificationBatchStackConsumer() {}
    private static long next(long value) { return (value ^ (value >>> 13)) * 2862933555777941757L + 3037000493L; }

    @Override public void event(long sequence, long actual) {
        value = next(value);
        if (sequence != delivered++ || actual != value) throw new AssertionError("suspended batch changed");
        if (sequence != 17) return;
        if (depth == 0) {
            if (fail) throw FAILURE;
            return;
        }
        int previousDepth = depth; long previousCount = delivered, previousValue = value;
        try { invoke(depth - 1, previousValue); }
        finally { depth = previousDepth; delivered = previousCount; value = previousValue; }
    }

    private long invoke(int depth, long seed) {
        this.depth = depth; delivered = 0; value = seed;
        long result = ROOT.process(1025, seed);
        if (result != value || delivered != 1025) throw new AssertionError("batch result/count");
        return result;
    }

    private static long expected() {
        long value = 19L;
        for (int index = 0; index < 1025; index++) value = next(value);
        return value;
    }

    private static long enter(int javaDepth, int nativeDepth) {
        if (javaDepth > 0) return enter(javaDepth - 1, nativeDepth) + 17L * javaDepth;
        LISTENER.fail = false;
        long value = LISTENER.invoke(nativeDepth, 19L);
        if (value != expected()) throw new AssertionError("outer recurrence");
        LISTENER.fail = true;
        try { LISTENER.invoke(nativeDepth, 19L); throw new AssertionError("missing failure"); }
        catch (RuntimeException actual) { if (actual != FAILURE) throw new AssertionError("throwable identity", actual); }
        LISTENER.fail = false;
        if (LISTENER.invoke(1, 19L) != expected()) throw new AssertionError("continued use");
        return value;
    }

    public static void main(String[] arguments) {
        ROOT.setListener(LISTENER);
        try {
            if (arguments[0].equals("bounded")) {
                for (int javaDepth : new int[]{0, 64}) {
                    for (int nativeDepth : new int[]{1, 8, 32, 64}) {
                        long value = enter(javaDepth, nativeDepth);
                        if (value != expected() + 17L * javaDepth * (javaDepth + 1L) / 2L) throw new AssertionError("Java live values");
                        System.out.println("bounded:" + javaDepth + ":" + nativeDepth + ":" + value);
                    }
                }
                System.out.println("generated-batch-stack-envelope-ok");
            } else {
                int depth = Integer.parseInt(arguments[0]);
                System.out.println("probe-start:" + depth);
                long value = enter(0, depth);
                System.out.println("probe-ok:" + depth + ":" + value);
            }
        } finally { ROOT.free(); }
    }
}
