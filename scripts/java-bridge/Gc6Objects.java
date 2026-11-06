// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Consumes actual generated handles and source methods through the production bootstrap. */
public final class Gc6Objects {
    private static Class<?> facade, roots;
    private static Method create, retain;
    private static long live() throws Exception { return (Long)call(facade, "live", new Class<?>[0]); }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        try { return (target instanceof Class<?> type ? type : target.getClass()).getMethod(name, parameters).invoke(target instanceof Class<?> ? null : target, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static Object value(Object handle) throws Exception { return call(handle, "value", new Class<?>[0]); }
    private static void close(Object handle) throws Exception { call(handle, "close", new Class<?>[0]); }
    private static int number(Object value) throws Exception { return (Integer)call(value, "number", new Class<?>[0]); }
    private static long address(Object value) throws Exception {
        for (var field : value.getClass().getDeclaredFields()) if (field.getType() == long.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true); return field.getLong(value);
        }
        throw new AssertionError("missing native address");
    }
    private static void stale(Object object, String name) throws Exception {
        try { call(object, name, new Class<?>[0]); throw new AssertionError("stale access accepted"); }
        catch (IllegalStateException expected) { /* Rejected before native dereference. */ }
    }
    private static Object create(int number) throws Exception {
        try { return create.invoke(null, number); }
        catch (InvocationTargetException failure) { throw (Exception)failure.getCause(); }
    }
    private static void exercise() throws Exception {
        call(facade, "clear", new Class<?>[0]); long baseline = live();
        Object first = create(17), firstValue = value(first);
        Object independent = retain.invoke(null, firstValue);
        if (value(independent) != firstValue) throw new AssertionError("open facade identity changed");
        close(first); close(first); stale(first, "value");
        if (number(value(independent)) != 17) throw new AssertionError("independent anchor released");
        if (!call(firstValue, "scalar", new Class<?>[]{long.class}, 71L).equals(88L)) throw new AssertionError("scalar carrier changed");
        if (!call(firstValue, "failure", new Class<?>[]{boolean.class}, false).equals(17L)) throw new AssertionError("wide result changed");
        try { call(firstValue, "failure", new Class<?>[]{boolean.class}, true); throw new AssertionError("scalar failure disappeared"); }
        catch (IllegalArgumentException expected) { /* The failure-only JNI handoff preserves the source exception. */ }
        Class<?> box = Class.forName("gc6objects.Box"), bound = Class.forName("gc6objects.Bound");
        Object boxed = call(box, "counter", new Class<?>[]{facade}, firstValue);
        Object resultHandle = call(value(boxed), "get", new Class<?>[0]);
        if (value(resultHandle) != firstValue) throw new AssertionError("erased result lost facade identity");
        close(boxed); close(resultHandle);
        Object bounded = roots.getMethod("construct_6763366f626a656374732e426f756e64", facade).invoke(null, firstValue);
        Object boundedAlias = call(value(bounded), "self", new Class<?>[0]);
        if (value(boundedAlias) != value(bounded)) throw new AssertionError("generic alias projection changed");
        Object boundedResult = call(value(bounded), "get", new Class<?>[0]);
        if (value(boundedResult) != firstValue) throw new AssertionError("final-bound result changed");
        close(bounded); close(boundedAlias); close(boundedResult);
        Object tradeBox = call(box, "trade", new Class<?>[]{long.class}, 912L);
        Object trade = call(value(tradeBox), "get", new Class<?>[0]); close(tradeBox);
        call(facade, "clear", new Class<?>[0]);
        if (!call(value(trade), "number", new Class<?>[0]).equals(912L)) throw new AssertionError("independent generic result lost after collection");
        close(trade);
        Object owner = create(29), ownerValue = value(owner);
        Object self = call(ownerValue, "retain", new Class<?>[]{facade}, firstValue);
        if (value(self) != ownerValue) throw new AssertionError("alias projection lost identity");
        close(independent); stale(firstValue, "number");
        Object child = call(ownerValue, "value", new Class<?>[0]);
        if (number(value(child)) != 17 || value(child) == firstValue) throw new AssertionError("surviving source alias did not get a new lifetime");
        call(ownerValue, "close", new Class<?>[0]);
        if (number(value(child)) != 17) throw new AssertionError("source collection lost alias");
        call(ownerValue, "free", new Class<?>[0]); // Ordinary source store, separate from handle closure.
        close(owner); close(self); stale(ownerValue, "number");
        call(facade, "clear", new Class<?>[0]);
        if (live() <= baseline) throw new AssertionError("child handle failed to retain graph");
        close(child); call(facade, "clear", new Class<?>[0]);
        if (live() != baseline) throw new AssertionError("released graph was not reclaimed: " + live() + " / " + baseline);
        Object old = create(101), oldValue = value(old); long released = address(oldValue);
        close(old); call(facade, "clear", new Class<?>[0]);
        boolean reused = false;
        for (int i = 0; i < 1024 && !reused; i++) {
            Object next = create(202), nextValue = value(next);
            if (address(nextValue) == released) {
                reused = true; stale(oldValue, "number");
                if (number(nextValue) != 202 || nextValue == oldValue) throw new AssertionError("address reuse revived an old facade");
            }
            close(next); call(facade, "clear", new Class<?>[0]);
        }
        if (!reused) throw new AssertionError("address reuse control did not exercise reuse");
        try { create(-1); throw new AssertionError("constructor exception lost"); }
        catch (IllegalArgumentException expected) { /* Constructor's static publication remains committed. */ }
        Object published = call(facade, "construct", new Class<?>[0]);
        if (number(value(published)) != -1) throw new AssertionError("failed constructor publication was undone");
        close(published); call(facade, "clear", new Class<?>[0]);
        if (live() != baseline) throw new AssertionError("failed constructor graph was retained");
        Class<?> kind = Class.forName("gc6objects.Counter$Kind");
        Object alpha = kind.getField("ALPHA").get(null);
        if (call(alpha, "pick", new Class<?>[]{boolean.class}, true) != alpha
                || call(alpha, "pick", new Class<?>[]{boolean.class}, false) != null) throw new AssertionError("enum value projection changed");
        Object enumHandle = call(alpha, "create", new Class<?>[]{int.class}, 43);
        if (number(value(enumHandle)) != 43) throw new AssertionError("enum result did not receive an anchor");
        close(enumHandle); call(facade, "clear", new Class<?>[0]);
        baseline = live(); // Source enum initialization intentionally retains its singleton graph.
        Object conversions = create(31), converted = value(conversions);
        try { call(converted, "fault", new Class<?>[]{int.class}, 77); throw new AssertionError("snapshot failure disappeared"); }
        catch (RuntimeException failure) {
            if (!failure.getClass().getName().equals("gc6objects.Counter$Fault")
                    || !call(failure, "getCode", new Class<?>[0]).equals(77)
                    || !call(failure, "getLabel", new Class<?>[0]).equals("code=77")
                    || !failure.getMessage().equals("snapshot")) throw new AssertionError("collecting exception snapshot changed", failure);
        }
        String text = new String(new char[]{'x', '\u0000', '\u20ac', '\ud83d', '\ude00'});
        if (!text.equals(call(converted, "text", new Class<?>[]{String.class}, text))
                || !text.equals(call(converted, "text", new Class<?>[0]))) throw new AssertionError("String projection or stored graph lost");
        int[] array = {7, 9};
        if (call(converted, "copy", new Class<?>[]{int[].class, boolean.class}, array, false) != array || array[0] != 38)
            throw new AssertionError("array alias/copy-back changed");
        try { call(converted, "copy", new Class<?>[]{int[].class, boolean.class}, array, true); throw new AssertionError("array failure lost"); }
        catch (IllegalArgumentException expected) { if (array[0] != 69) throw new AssertionError("exceptional copy-back lost"); }
        Class<?> viewType = Class.forName("ironwood.bridge.ByteView");
        Object view = call(viewType, "allocate", new Class<?>[]{int.class}, 4);
        if (!call(facade, "absent", new Class<?>[]{viewType}, (Object)null).equals(true)
                || !call(facade, "absent", new Class<?>[]{viewType}, view).equals(false)) throw new AssertionError("native descriptor null comparison changed");
        Object distinct = call(view, "slice", new Class<?>[]{int.class, int.class}, 0, 4);
        if (!call(facade, "same", new Class<?>[]{viewType, viewType}, view, view).equals(true)
                || !call(facade, "same", new Class<?>[]{viewType, viewType}, view, distinct).equals(false)) throw new AssertionError("borrowed descriptor identity changed");
        if (!call(facade, "borrow", new Class<?>[]{viewType, int.class, byte.class, boolean.class}, view, 2, (byte)23, true).equals(23))
            throw new AssertionError("borrowed write lost");
        Object readOnly = call(view, "asReadOnly", new Class<?>[0]);
        try { call(facade, "borrow", new Class<?>[]{viewType, int.class, byte.class, boolean.class}, readOnly, -1, (byte)0, true);
            throw new AssertionError("read-only borrow accepted write"); }
        catch (UnsupportedOperationException expected) { /* Permission precedes bounds. */ }
        try { call(facade, "borrow", new Class<?>[]{viewType, int.class, byte.class, boolean.class}, view, 4, (byte)0, false);
            throw new AssertionError("borrowed bounds accepted"); }
        catch (IndexOutOfBoundsException expected) { /* Native descriptor keeps the original range. */ }
        close(conversions); call(facade, "clear", new Class<?>[0]);
        if (live() != baseline) throw new AssertionError("conversion graph was not reclaimed");
    }
    private static void resources() throws Exception {
        Class<?> resource = Class.forName("gc6objects.Resource");
        Path path = Files.createTempFile("gc6-resource-", ".bin");
        String tag = java.util.HexFormat.of().formatHex("gc6objects.Resource".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object original = call(roots, "construct_" + tag, new Class<?>[]{String.class}, path.toString());
        Object stale = value(original);
        try {
            call(stale, "write", new Class<?>[]{int.class}, 65);
            close(original);
            call(facade, "clear", new Class<?>[0]);
            Object restored = call(resource, "saved", new Class<?>[0]);
            Object live = value(restored);
            if (live == stale) throw new AssertionError("resource facade epoch revived");
            call(live, "write", new Class<?>[]{int.class}, 66);
            call(live, "close", new Class<?>[0]);
            call(live, "close", new Class<?>[0]);
            try { call(live, "write", new Class<?>[]{int.class}, 67); throw new AssertionError("source close lost native resource checks"); }
            catch (java.io.IOException expected) { }
            close(restored);
            call(resource, "clearSaved", new Class<?>[0]);
            if (!java.util.Arrays.equals(Files.readAllBytes(path), new byte[]{65, 66})) throw new AssertionError("anchor release closed source resource");
        } finally {
            Object cleanup = call(resource, "saved", new Class<?>[0]);
            if (cleanup != null) {
                call(value(cleanup), "close", new Class<?>[0]); close(cleanup);
                call(resource, "clearSaved", new Class<?>[0]);
            }
            Files.delete(path);
        }
    }

    public static void main(String[] args) throws Exception {
        String support = Files.readString(Path.of(args[0]));
        roots = Class.forName(support + ".Roots"); facade = Class.forName("gc6objects.Counter");
        resources();
        if (args.length > 1 && args[1].equals("critical")) {
            var linker = Class.forName(support + ".Critical").getDeclaredField("LINKER"); linker.setAccessible(true);
            if (linker.get(null) == null) throw new AssertionError("critical transport silently fell back to JNI");
            for (var field : facade.getDeclaredFields()) if (field.getType() == java.lang.invoke.MethodHandle.class) {
                field.setAccessible(true); if (field.get(null) == null) throw new AssertionError("critical binding silently fell back to JNI");
            }
        }
        create = roots.getMethod("construct_6763366f626a656374732e436f756e746572", int.class);
        retain = roots.getMethod("retain", facade);
        if (facade.getConstructors().length != 0) throw new AssertionError("public owning constructor");
        exercise();
        for (int i = 0; i < 4; i++) {
            Throwable[] failure = new Throwable[1];
            Thread thread = new Thread(() -> { try { exercise(); } catch (Throwable error) { failure[0] = error; } });
            thread.start(); thread.join(); if (failure[0] != null) throw new AssertionError(failure[0]);
        }
        System.out.println("GC6 generated object handles passed");
    }
}
