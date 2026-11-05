// SPDX-License-Identifier: MIT OR Apache-2.0

/** Actual JVM consumer for the private generated JNI completion unit fixture. */
public final class Gc6CarrierCompletion {
    private Gc6CarrierCompletion() {}
    private static native void verify(Throwable first, Throwable second, int mode);

    private static final class Second {
        private static native void verify(Throwable first, Throwable second, int mode);
    }

    public static void main(String[] arguments) throws Exception {
        System.load(arguments[0]);
        System.load(arguments[1]);
        RuntimeException first = new RuntimeException("original"), second = new RuntimeException("secondary");
        verify(first, second, 3);
        Second.verify(first, second, 0);
        Second.verify(first, second, 2);
        verify(first, second, 4);
        Second.verify(first, second, 3);
        verify(first, second, 0);
        Second.verify(first, second, 4);
        for (int round = 0; round < 16; round++) {
            verify(first, second, 0);
            verify(first, second, 2);
            Second.verify(first, second, 0);
            try {
                verify(first, second, 1);
                throw new AssertionError("missing pending exception");
            } catch (RuntimeException failure) {
                if (failure != first) throw new AssertionError("exception identity changed", failure);
            }
        }
        // No transition survives a completed call. Calls are serialized, including
        // sequential calls from Java threads other than the loading thread.
        for (int index = 0; index < 4; index++) {
            Thread thread = new Thread(() -> {
                verify(first, second, 0);
                Second.verify(first, second, 0);
            });
            thread.setUncaughtExceptionHandler((ignored, failure) -> Runtime.getRuntime().halt(1));
            thread.start();
            thread.join();
        }
        System.out.println("GC6 JNI carrier completion passed");
    }
}
