// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;

/** Independent production jars, exact image chains and copied host transport. */
public final class Gc6Images {
    private Gc6Images() {}
    private static Object call(Method method, Object receiver, Object... arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static final class World {
        final URLClassLoader loader;
        final Class<?> node, listener;
        final Method run, value, collect, live, allocations, handleValue, close, retain;
        World(Path folder, String label, ClassLoader values) throws Exception {
            loader = new URLClassLoader(new java.net.URL[]{folder.resolve("image-" + label + ".jar").toUri().toURL()}, values);
            String support = Files.readString(folder.resolve("support-" + label + ".txt"));
            node = Class.forName("gc6callbacks.Node", true, loader);
            listener = Class.forName("gc6callbacks.Listener", true, loader);
            Class<?> handle = Class.forName(support + ".RootHandle", true, loader);
            run = node.getMethod("run", listener, int.class); value = node.getMethod("value");
            collect = node.getMethod("collect"); live = node.getMethod("live"); allocations = node.getMethod("allocations");
            handleValue = handle.getMethod("value"); close = handle.getMethod("close");
            retain = Class.forName(support + ".Roots", true, loader).getMethod("retain", node);
        }
        void collect() throws Throwable { call(collect, null); }
        long live() throws Throwable { return (Long)call(live, null); }
        long allocations() throws Throwable { return (Long)call(allocations, null); }
        Object run(World other, int depth, int number) throws Throwable {
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, arguments) -> {
                if (!method.getName().equals("call")) throw new AssertionError(method);
                Object facade = arguments[1];
                if (!call(value, facade).equals(arguments[0])) throw new AssertionError("outer facade changed image");
                collect(); other.collect();
                if (depth > 0) {
                    Object nested = other.run(this, depth - 1, number + 100);
                    call(other.close, nested);
                }
                collect(); other.collect();
                if (!call(value, facade).equals(number)) throw new AssertionError("suspended image roots were lost");
                return number + 1;
            });
            return call(run, null, callback, number);
        }
    }
    private static void exercise(World a, World b) throws Throwable {
        a.collect(); b.collect();
        long baseA = a.live(), baseB = b.live();
        for (int depth : new int[]{0, 1, 3, 8, 16}) {
            Object held = a.run(a, depth, 17);
            Object alias = call(a.retain, null, call(a.handleValue, held));
            call(a.close, held);
            long count = a.allocations();
            Object second = b.run(a, depth, 91);
            call(b.close, second); b.collect(); a.collect();
            if (!call(a.value, call(a.handleValue, alias)).equals(17) || a.allocations() != count && depth == 0)
                throw new AssertionError("independent image heap or counters changed");
            try {
                call(b.retain, null, call(a.handleValue, alias));
                throw new AssertionError("cross-image managed facade accepted");
            } catch (IllegalArgumentException expected) { }
            call(a.close, alias); a.collect(); b.collect();
            if (a.live() != baseA || b.live() != baseB) throw new AssertionError("image roots survived their complete lifetimes");
        }
        RuntimeException expected = new IllegalStateException("cross-image callback identity");
        Object failA = Proxy.newProxyInstance(a.loader, new Class<?>[]{a.listener}, (proxy, method, arguments) -> { throw expected; });
        Object failB = Proxy.newProxyInstance(b.loader, new Class<?>[]{b.listener}, (proxy, method, arguments) ->
                call(a.node.getMethod("failure", a.listener, int.class), null, failA, 1));
        Object outer = Proxy.newProxyInstance(a.loader, new Class<?>[]{a.listener}, (proxy, method, arguments) ->
                call(b.node.getMethod("failure", b.listener, int.class), null, failB, 1));
        try { call(a.run, null, outer, 8); throw new AssertionError("cross-image exception disappeared"); }
        catch (RuntimeException actual) { if (actual != expected) throw actual; }
        Class<?> viewType = Class.forName("ironwood.bridge.ByteView", true, a.loader);
        Object view = call(viewType.getMethod("allocate", int.class), null, 2);
        int[] copied = {10}, nestedCopy = {20};
        String text = "copied between images";
        Object leaf = Proxy.newProxyInstance(b.loader, new Class<?>[]{b.listener}, (proxy, method, arguments) -> {
            a.collect(); b.collect(); return 12;
        });
        Method borrowedB = b.node.getMethod("borrowed", b.listener, viewType, int[].class, String.class, boolean.class);
        Object middle = Proxy.newProxyInstance(a.loader, new Class<?>[]{a.listener}, (proxy, method, arguments) -> {
            Object result = call(borrowedB, null, leaf, view, nestedCopy, text, false);
            if (!text.equals(result)) throw new AssertionError("copied String changed image");
            return 12;
        });
        Object result = call(a.node.getMethod("borrowed", a.listener, viewType, int[].class, String.class, boolean.class),
                null, middle, view, copied, text, false);
        if (!text.equals(result) || copied[0] != 15 || nestedCopy[0] != 25)
            throw new AssertionError("copied cross-image transport lost copy-back");
        if (!call(viewType.getMethod("get", int.class), view, 0).equals((byte)2)
                || !call(viewType.getMethod("get", int.class), view, 1).equals((byte)3))
            throw new AssertionError("shared Java storage owner lost its nested borrow");
        a.collect(); b.collect();
        if (a.live() != baseA || b.live() != baseB) throw new AssertionError("exception translation retained image roots");
    }
    public static void main(String[] arguments) throws Throwable {
        Path folder = Path.of(arguments[0]);
        try (var values = new URLClassLoader(new java.net.URL[]{folder.resolve("ironwood-bridge-values.jar").toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            World a = new World(folder, "A", values), b = new World(folder, "B", values);
            exercise(a, b); exercise(b, a);
            for (int i = 0; i < 4; i++) {
                Throwable[] problem = {null};
                Thread next = new Thread(() -> { try { exercise(a, b); } catch (Throwable failure) { problem[0] = failure; } });
                next.start(); next.join();
                if (problem[0] != null) throw problem[0];
            }
            a.loader.close(); b.loader.close();
        }
        System.out.println("GC6 independent production images passed");
    }
}
