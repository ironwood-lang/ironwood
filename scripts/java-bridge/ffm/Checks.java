// SPDX-License-Identifier: MIT OR Apache-2.0
package probe;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public final class Checks {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static final Map<Class<?>, Class<?>> PRIMITIVES = Map.of(Boolean.class, boolean.class, Byte.class, byte.class,
            Short.class, short.class, Character.class, char.class, Integer.class, int.class, Long.class, long.class,
            Float.class, float.class, Double.class, double.class);
    static Object call(Class<?> api, String name, Object... args) throws Throwable {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = PRIMITIVES.get(args[i].getClass());
        try { return api.getMethod(name, types).invoke(null, args); }
        catch (InvocationTargetException caught) { throw caught.getCause(); }
    }
    private static void same(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    private static void failure(Class<?> api, String name, String type, Object... args) throws Throwable {
        try { call(api, name, args); throw new AssertionError("missing failure: " + name); }
        catch (RuntimeException caught) {
            if (!caught.getMessage().contains(type)) throw new AssertionError(caught);
        }
    }
    static void verify(Class<?> api) throws Throwable {
        for (boolean v : new boolean[]{false, true}) same(v, call(api, "z", v));
        for (byte v : new byte[]{Byte.MIN_VALUE, -1, 0, Byte.MAX_VALUE}) same(v, call(api, "b", v));
        for (short v : new short[]{Short.MIN_VALUE, -1, 0, Short.MAX_VALUE}) same(v, call(api, "s", v));
        for (char v : new char[]{0, 127, 32768, 65535}) same(v, call(api, "c", v));
        for (int v : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) same(v, call(api, "i", v));
        for (long v : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) same(v, call(api, "j", v));
        for (int v : new int[]{0, 0x80000000, 1, 0x7f800000, 0xff800000, 0x7fc12345, 0xffc23456})
            same(v, Float.floatToRawIntBits((float) call(api, "f", Float.intBitsToFloat(v))));
        for (long v : new long[]{0L, Long.MIN_VALUE, 1L, 0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8123456789abcL, 0xfff823456789abcdL})
            same(v, Double.doubleToRawLongBits((double) call(api, "d", Double.longBitsToDouble(v))));
        same(Integer.MIN_VALUE, call(api, "add", Integer.MAX_VALUE, 1));
        same(0, call(api, "ticks"));
        call(api, "noop"); same(1, call(api, "ticks"));
        same(37, call(api, "initialized")); same(37, call(api, "initialized")); same(2, call(api, "ticks"));
        failure(api, "broken", "NullPointerException");
        failure(api, "broken", "NullPointerException"); same(3, call(api, "ticks"));
        failure(api, "fail", "NullPointerException");
        same(73, call(api, "allocate")); same(42, call(api, "add", 20, 22));
        same(reference(128, 11L), call(api, "recurse", 128, 11L, false));
        failure(api, "recurse", "NullPointerException", 128, 11L, true);
        try { JniProbe.address(-1); throw new AssertionError("unknown entry accepted"); }
        catch (IllegalArgumentException expected) { /* No arbitrary symbol exposure. */ }
        System.out.println("verify-ok");
    }
    static void oom(Class<?> api) throws Throwable {
        failure(api, "allocate", "OutOfMemoryError");
        same(42, call(api, "add", 20, 22));
        System.out.println("oom-ok");
    }
    static void stack(Class<?> api, int depth) throws Throwable {
        System.out.println("stack-start:" + depth);
        call(api, "recurse", depth, 11L, false);
        failure(api, "recurse", "NullPointerException", depth, 11L, true);
        same(42, call(api, "add", 20, 22));
        System.out.println("stack-ok:" + depth);
    }
    private static long reference(int depth, long seed) {
        if (depth == 0) return seed + 7;
        long value = reference(depth - 1, seed + 3);
        return (value * value + depth) ^ seed;
    }
    static long bytes() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    static void report(boolean report, int count, long sum, long elapsed, long nativeDelta, long javaDelta) {
        same((long) count * (count - 1) / 2 + 42L * count, sum);
        sink = sum;
        if (report) {
            if (nativeDelta != 0 || javaDelta != 0) throw new AssertionError("allocations: " + nativeDelta + "/" + javaDelta);
            System.out.println("sample," + count + "," + sum + "," + elapsed + "," + nativeDelta + "," + javaDelta);
        }
    }
}
