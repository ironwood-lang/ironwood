// SPDX-License-Identifier: MIT OR Apache-2.0

import stackprobe.QualificationStack;

/** Public generated entry, with all potentially destructive probes in child JVMs. */
public final class QualificationStackConsumer {
    private QualificationStackConsumer() {}

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
        long value = QualificationStack.recurse(nativeDepth, 19L, false);
        if (value != expected(nativeDepth, 19L)) throw new AssertionError("native checksum");
        try { QualificationStack.recurse(nativeDepth, 19L, true); throw new AssertionError("missing native failure"); }
        catch (NullPointerException failure) {
            if (java.util.Arrays.stream(failure.getStackTrace()).noneMatch(frame -> frame.getClassName().equals("stackprobe.QualificationStack")
                    && frame.getMethodName().equals("recurse"))) throw new AssertionError("missing native frame", failure);
        }
        if (QualificationStack.recurse(1, 19L, false) != expected(1, 19L)) throw new AssertionError("continued call");
        return value;
    }

    public static void main(String[] arguments) {
        if (arguments[0].equals("bounded")) {
            for (int javaDepth : new int[]{0, 64}) {
                for (int nativeDepth : new int[]{1, 8, 32, 64}) {
                    long value = enter(javaDepth, nativeDepth);
                    if (value != expected(nativeDepth, 19L) + 17L * javaDepth * (javaDepth + 1L) / 2L) throw new AssertionError("Java live values");
                    System.out.println("bounded:" + javaDepth + ":" + nativeDepth + ":" + value);
                }
            }
            System.out.println("generated-stack-envelope-ok");
        } else {
            int depth = Integer.parseInt(arguments[0]);
            System.out.println("probe-start:" + depth);
            long value = enter(0, depth);
            System.out.println("probe-ok:" + depth + ":" + value);
        }
    }
}
