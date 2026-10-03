// SPDX-License-Identifier: MIT OR Apache-2.0

import callbackstackprobe.QualificationCallbackStack;
import callbackstackprobe.StackListener;

/** Alternating Java/native recursion through actual public producer adapters. */
public final class QualificationCallbackStackConsumer {
    private static final RuntimeException FAILURE = new RuntimeException("deepest Java callback failure");
    private static final StackListener LISTENER = new Target();
    private QualificationCallbackStackConsumer() {}

    private static final class Target implements StackListener {
        @Override public long step(int depth, long seed, boolean fail) {
            if (depth == 0) {
                if (fail) throw FAILURE;
                return seed + 7L;
            }
            return QualificationCallbackStack.recurse(this, depth - 1, seed + 3L, fail);
        }
    }

    private static long expected(int depth, long seed) {
        long value = seed + 3L * depth + 7L;
        for (int level = 1; level <= depth; level++) value = (value * value + level) ^ (seed + 3L * (depth - level));
        return value;
    }

    private static long enter(int javaDepth, int nativeDepth) {
        if (javaDepth > 0) {
            long live = javaDepth * 17L;
            return enter(javaDepth - 1, nativeDepth) + live;
        }
        long value = QualificationCallbackStack.recurse(LISTENER, nativeDepth, 19L, false);
        if (value != expected(nativeDepth, 19L)) throw new AssertionError("callback checksum");
        try {
            QualificationCallbackStack.recurse(LISTENER, nativeDepth, 19L, true);
            throw new AssertionError("missing callback failure");
        } catch (RuntimeException caught) {
            if (caught != FAILURE) throw new AssertionError("callback identity", caught);
        }
        if (QualificationCallbackStack.recurse(LISTENER, 1, 19L, false) != expected(1, 19L)) {
            throw new AssertionError("continued callback");
        }
        return value;
    }

    public static void main(String[] arguments) {
        if (arguments[0].equals("bounded")) {
            for (int javaDepth : new int[]{0, 64}) {
                for (int nativeDepth : new int[]{1, 8, 32, 64}) {
                    long value = enter(javaDepth, nativeDepth);
                    if (value != expected(nativeDepth, 19L) + 17L * javaDepth * (javaDepth + 1L) / 2L) {
                        throw new AssertionError("Java live values");
                    }
                    System.out.println("bounded:" + javaDepth + ":" + nativeDepth + ":" + value);
                }
            }
            System.out.println("generated-callback-stack-envelope-ok");
        } else {
            int depth = Integer.parseInt(arguments[0]);
            System.out.println("probe-start:" + depth);
            long value = enter(0, depth);
            System.out.println("probe-ok:" + depth + ":" + value);
        }
    }
}
