// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.Arrays;

/** Deterministic checksum and native allocation control for scalar JNI/FFM. */
public final class Gc6ScalarControl {
    private Gc6ScalarControl() {}
    public static void main(String[] args) throws Throwable {
        String support = Files.readString(Path.of(args[0]));
        Class<?> counter = Class.forName("gc6objects.Counter"), roots = Class.forName(support + ".Roots");
        Object handle = roots.getMethod("construct_6763366f626a656374732e436f756e746572", int.class).invoke(null, 17);
        Object facade = handle.getClass().getMethod("value").invoke(handle);
        MethodHandle scalar = MethodHandles.publicLookup().unreflect(counter.getMethod("scalar", long.class))
                .asType(MethodType.methodType(long.class, Object.class, long.class));
        if (args[1].equals("critical")) {
            var linker = Class.forName(support + ".Critical").getDeclaredField("LINKER"); linker.setAccessible(true);
            if (linker.get(null) == null) throw new AssertionError("critical transport silently fell back");
        }
        int count = 500_000;
        long expected = (long)count * (count - 1) / 2 + 17L * count;
        long allocated = (Long)counter.getMethod("total").invoke(null);
        long[] samples = new long[7];
        for (int trial = -4; trial < samples.length; trial++) {
            long start = System.nanoTime(), checksum = 0;
            for (int i = 0; i < count; i++) checksum += (long)scalar.invokeExact(facade, (long)i);
            long elapsed = System.nanoTime() - start;
            if (checksum != expected) throw new AssertionError("scalar checksum changed");
            if (trial >= 0) samples[trial] = elapsed;
        }
        if ((Long)counter.getMethod("total").invoke(null) != allocated) throw new AssertionError("scalar path allocated managed objects");
        Arrays.sort(samples);
        handle.getClass().getMethod("close").invoke(handle);
        counter.getMethod("clear").invoke(null);
        System.out.println("{\"transport\":\"" + args[1] + "\",\"calls\":" + count + ",\"checksum\":" + expected
                + ",\"managed_allocations\":0,\"median_ns\":" + samples[3] + "}");
    }
}
