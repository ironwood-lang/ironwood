// SPDX-License-Identifier: MIT OR Apache-2.0
package probe;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicReference;
import static java.lang.foreign.ValueLayout.*;

public final class FfmProbe {
    // The runner loads the matched image before this class initializes. No
    // per-call arena, lookup, result allocation or TLS access is introduced.
    private static final Arena ARENA = Arena.ofConfined();
    private static final MemorySegment FRAME = ARENA.allocate(288, 8);
    private static MethodHandle handle(int index, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(JniProbe.address(index)), descriptor);
    }
    private static void check(int status) {
        if (status != 0) JniProbe.failure(FRAME.address(), status);
    }
    // GENERATED_METHODS
    public static void run(String[] args) throws Throwable {
        try {
            if (args[1].equals("verify")) { Checks.verify(FfmProbe.class); lifetime(); }
            else if (args[1].equals("oom")) Checks.oom(FfmProbe.class);
            else if (args[1].equals("stack")) Checks.stack(FfmProbe.class, Integer.parseInt(args[2]));
            else if (args[1].equals("bench")) {
                for (int i = 0; i < 8; i++) measure(false);
                for (int i = 0; i < 7; i++) measure(true);
            } else throw new IllegalArgumentException(args[1]);
        } finally { ARENA.close(); }
    }
    private static void lifetime() throws Throwable {
        int before = JniProbe.ticks();
        var caught = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { noop(); } catch (Throwable failure) { caught.set(failure); }
        });
        thread.start(); thread.join();
        if (!(caught.get() instanceof WrongThreadException) || JniProbe.ticks() != before)
            throw new AssertionError("confined frame reached native code", caught.get());
        MemorySegment closed;
        try (Arena temporary = Arena.ofConfined()) { closed = temporary.allocate(288, 8); }
        try { int ignored = (int) H_noop.invokeExact(closed); throw new AssertionError("closed frame reached native code: " + ignored); }
        catch (IllegalStateException expected) { /* Rejected by FFM before invoking the native entry. */ }
        if (JniProbe.ticks() != before) throw new AssertionError("closed frame side effect");
        System.out.println("lifetime-ok");
    }
    private static void measure(boolean report) throws Throwable {
        long bytes = Checks.bytes(), allocations = JniProbe.allocations(), sum = 0;
        int count = 5_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) sum += add(i, 42);
        long elapsed = System.nanoTime() - start;
        long nativeDelta = JniProbe.allocations() - allocations, javaDelta = Checks.bytes() - bytes;
        Checks.report(report, count, sum, elapsed, nativeDelta, javaDelta);
    }
}
