// SPDX-License-Identifier: MIT OR Apache-2.0

/** Real JVM frames separate native segments; no native walk may enter them. */
public final class Gc6Segments {
    private Gc6Segments() {}
    private static boolean mixed;
    private static int mode;
    private static native long invoke(Class<?> driver, int depth, long seed, int selectedMode);
    private static final class Second {
        private static native long invoke(Class<?> driver, int depth, long seed, int selectedMode);
    }

    private static long reenter(long depth, long seed) {
        if (mode == 5) {
            invoke(Gc6Segments.class, -2, 0L, mode);
            Second.invoke(Gc6Segments.class, -2, 0L, mode);
        }
        return mixed && (depth & 1) == 0 ? Second.invoke(Gc6Segments.class, (int)depth, seed, mode)
                : invoke(Gc6Segments.class, (int)depth, seed, mode);
    }

    private static long expected(int depth, long seed) {
        return depth == 0 ? seed : (mode == 1 || mode == 5 ? seed : 20 * seed + 190) + expected(depth - 1, seed + 100);
    }

    private static void check(int depth) {
        long actual = invoke(Gc6Segments.class, depth, 71L, mode);
        if (actual != expected(depth, 71L)) throw new AssertionError("lost root at depth " + depth + ": " + actual);
        invoke(Gc6Segments.class, 0, 9L, mode);
        Second.invoke(Gc6Segments.class, 0, 9L, mode);
        if (invoke(Gc6Segments.class, -1, 42L, mode) != 42L) throw new AssertionError("scalar entry");
    }

    public static void main(String[] arguments) throws Exception {
        System.load(arguments[0]);
        System.load(arguments[1]);
        if (arguments.length > 2 && arguments[2].equals("performance")) {
            mode = 0;
            final int calls = 20000;
            for (int warm = 0; warm < 2000; warm++) {
                invoke(Gc6Segments.class, -1, warm, mode);
                invoke(Gc6Segments.class, -3, warm, mode);
            }
            long before = invoke(Gc6Segments.class, -4, 0L, mode);
            long start = System.nanoTime(), checksum = 0;
            for (int i = 0; i < calls; i++) checksum += invoke(Gc6Segments.class, -1, i, mode);
            long scalar = System.nanoTime() - start;
            start = System.nanoTime();
            for (int i = 0; i < calls; i++) checksum += invoke(Gc6Segments.class, -3, i, mode);
            long transition = System.nanoTime() - start;
            if (before != invoke(Gc6Segments.class, -4, 0L, mode) || checksum != (long)calls * calls + 99L * calls)
                throw new AssertionError("timed transition collected or changed the checksum");
            System.out.println("{\"calls\":" + calls + ",\"scalar_ns\":" + scalar + ",\"transition_ns\":" + transition + "}");
            return;
        }
        if (arguments.length > 2) {
            mode = Integer.parseInt(arguments[2]);
            check(2);
            throw new AssertionError("missing invalid segment refusal");
        }
        // Exercise Java A/A and A/B/A, enough calls to run reentry through JIT code.
        for (int selectedMode : new int[] {0, 1, 5}) {
            mode = selectedMode;
            for (boolean selectedMix : new boolean[] {false, true}) {
                mixed = selectedMix;
                for (int depth : new int[] {1, 3, 8}) for (int round = 0; round < 16; round++) check(depth);
            }
        }
        for (int index = 0; index < 4; index++) {
            Thread thread = new Thread(() -> check(4));
            thread.setUncaughtExceptionHandler((ignored, failure) -> Runtime.getRuntime().halt(1));
            thread.start();
            thread.join();
        }
        System.out.println("GC6 suspended segments passed");
    }
}
