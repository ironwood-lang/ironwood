// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/** Actual generated JNI entries, Java callbacks, nested collection and explicit handles. */
public final class Gc6Callbacks {
    private static Class<?> node;
    private static Class<?> listener;
    private static Class<?> roots;
    private static Method run;
    private static Method value;
    private static Method collect;
    private static Method retain;
    private static Method handleValue;
    private static Method close;
    private static Class<?> listeners;
    private static Class<?> registration;
    private static Object escaped;
    private static Object retained;
    private static Object deliveryAlias;
    private static long baselineGlobals;
    private static native long globals();
    private static native void failure(int kind, int after);

    private Gc6Callbacks() {}

    private static void deliveryHook() throws Throwable {
        deliveryAlias = invoke(node.getMethod("saved"), null);
        invoke(collect, null);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Object callback(int depth, boolean keep, Throwable failure) {
        return Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
            if (!method.getName().equals("call")) throw new AssertionError(method);
            if (failure != null) throw failure;
            int number = (Integer) arguments[0];
            Object facade = arguments[1];
            if ((Integer) invoke(value, facade) != number) throw new AssertionError("callback facade lost value");
            invoke(collect, null);
            if (depth > 0) {
                Object nested = invoke(run, null, callback(depth - 1, false, null), number + 10);
                Object nestedValue = invoke(handleValue, nested);
                if ((Integer) invoke(value, nestedValue) != number + 10) throw new AssertionError("nested segment lost value");
                invoke(close, nested);
                invoke(collect, null);
            }
            if (keep) {
                escaped = facade;
                retained = invoke(retain, null, facade);
            }
            if ((Integer) invoke(value, facade) != number) throw new AssertionError("resumed callback lost value");
            return number + 1;
        });
    }

    @FunctionalInterface
    private interface Action { void run(Object facade) throws Throwable; }

    private static Object simple(int offset, Action action) {
        return Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
            if (!method.getName().equals("call")) throw new AssertionError(method);
            if (arguments[1] != null) invoke(value, arguments[1]);
            if (action != null) action.run(arguments[1]);
            return (Integer) arguments[0] + offset;
        });
    }

    private static Method projected(String prefix, Class<?> result, Class<?>... parameters) {
        for (Method method : listeners.getDeclaredMethods()) if (method.getName().startsWith(prefix)
                && method.getReturnType() == result && java.util.Arrays.equals(method.getParameterTypes(), parameters)) return method;
        throw new AssertionError("missing registration projection: " + prefix);
    }

    private static void registrations() throws Throwable {
        Class<?> handle = handleValue.getDeclaringClass();
        Method open = listeners.getMethod("open");
        Method closeRegistration = registration.getMethod("close");
        Method construct = projected("construct_", handle, registration, int.class, listener, boolean.class);
        Method install = projected("install_", int.class, registration, node, listener, boolean.class);
        Method aliasInstall = projected("install_", handle, registration, node, listener);
        Method activeInstall = projected("install_", int.class, registration, node, listener);
        Method fire = node.getMethod("fire", int.class);
        Method unregister = node.getMethod("unregister");
        Method saved = node.getMethod("saved");
        Method clearSaved = node.getMethod("clearSaved");
        Object a = invoke(open, null), b = invoke(open, null);
        Object callback = simple(1, null);
        Object original = invoke(construct, null, a, 40, callback, false);
        Object facade = invoke(handleValue, original);
        if (!invoke(install, null, b, facade, callback, false).equals(40)) throw new AssertionError("retaining result changed");
        invoke(closeRegistration, a);
        if (!invoke(fire, facade, 40).equals(41)) throw new AssertionError("old token detached replacement");
        invoke(close, original);
        invoke(collect, null);
        Object alias = invoke(saved, null);
        Object surviving = invoke(handleValue, alias);
        if (surviving == facade || !invoke(fire, surviving, 40).equals(41)) throw new AssertionError("registration did not retain holder");
        invoke(unregister, surviving);
        if (!invoke(fire, surviving, 40).equals(-1)) throw new AssertionError("source unregistration failed");
        invoke(closeRegistration, b); invoke(closeRegistration, b);
        invoke(close, alias); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("replacement/unregistration JNI balance");

        Object c = invoke(open, null);
        try {
            invoke(construct, null, c, 55, callback, true);
            throw new AssertionError("constructor failure disappeared");
        } catch (IllegalArgumentException expected) {
            if (!"constructed listener".equals(expected.getMessage())) throw expected;
        }
        alias = invoke(saved, null); surviving = invoke(handleValue, alias);
        if (!invoke(fire, surviving, 55).equals(56)) throw new AssertionError("exceptional constructor lost committed listener");
        invoke(closeRegistration, c);
        if (!invoke(value, surviving).equals(55) || !invoke(fire, surviving, 55).equals(-1))
            throw new AssertionError("registration closure destroyed the source graph");
        invoke(close, alias); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("exceptional constructor JNI balance");

        Object d = invoke(open, null);
        original = invoke(construct, null, d, 66, callback, false); facade = invoke(handleValue, original);
        try {
            invoke(install, null, d, facade, callback, true);
            throw new AssertionError("retaining failure disappeared");
        } catch (IllegalArgumentException expected) {
            if (!"committed listener".equals(expected.getMessage())) throw expected;
        }
        if (!invoke(fire, facade, 66).equals(67)) throw new AssertionError("exceptional method lost committed listener");
        alias = invoke(aliasInstall, null, d, facade, callback);
        if (invoke(handleValue, alias) != facade) throw new AssertionError("retaining alias projection changed identity");
        invoke(close, original); invoke(closeRegistration, d);
        if (!invoke(value, facade).equals(66)) throw new AssertionError("registration closed an independent anchor");
        invoke(close, alias); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("retaining result JNI balance");

        Object active = invoke(open, null);
        Object held = invoke(construct, null, active, 88, callback, false);
        facade = invoke(handleValue, held);
        Object second = simple(10, ignored -> {
            invoke(closeRegistration, active); invoke(close, held); invoke(collect, null);
        });
        if (!invoke(node.getMethod("fireBoth", int.class, listener), facade, 88, second).equals(188))
            throw new AssertionError("active listener retired before outer completion");
        alias = invoke(saved, null); surviving = invoke(handleValue, alias);
        if (!invoke(fire, surviving, 88).equals(-1)) throw new AssertionError("closed registration remained installed");
        invoke(close, alias); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("active retirement JNI balance");

        Object inFlight = invoke(open, null);
        Object start = invoke(construct, null, inFlight, 99, callback, false);
        facade = invoke(handleValue, start);
        Object closing = simple(1, ignored -> { invoke(closeRegistration, inFlight); invoke(collect, null); });
        if (!invoke(activeInstall, null, inFlight, facade, closing).equals(100) || !invoke(fire, facade, 99).equals(-1))
            throw new AssertionError("in-flight registration closure lost result or final reconciliation");
        invoke(close, start); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("in-flight closure JNI balance");

        Object older = invoke(open, null), newer = invoke(open, null);
        Object state = invoke(construct, null, older, 123, callback, false);
        Object target = invoke(handleValue, state);
        Method conditional = projected("install_", int.class, registration, node, listener, boolean.class, int.class);
        Object unused = simple(7, null);
        if (!invoke(conditional, null, newer, target, unused, false, 1).equals(124)) throw new AssertionError("conditional result changed");
        invoke(closeRegistration, newer);
        if (!invoke(fire, target, 123).equals(124)) throw new AssertionError("skipped write detached a different listener");
        Object nestedOwner = invoke(open, null);
        Object[] reentrant = {null};
        reentrant[0] = simple(1, ignored -> {
            invoke(install, null, nestedOwner, target, reentrant[0], false);
            invoke(closeRegistration, older);
            invoke(collect, null);
        });
        if (!invoke(activeInstall, null, older, target, reentrant[0]).equals(124)) throw new AssertionError("nested retaining result changed");
        // The nested retaining projection owns this final matching identity.
        invoke(closeRegistration, nestedOwner);
        if (!invoke(fire, target, 123).equals(-1)) throw new AssertionError("older invocation stole the nested registration");
        invoke(close, state); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("nested retaining JNI balance");
    }

    private static void exercise() throws Throwable {
        for (int depth : new int[]{0, 1, 3, 8, 16}) {
            Object result = invoke(run, null, callback(depth, true, null), 100 + depth);
            Object facade = invoke(handleValue, result);
            if (facade != escaped || invoke(handleValue, retained) != facade) throw new AssertionError("callback alias identity changed");
            invoke(close, result);
            invoke(collect, null);
            if ((Integer) invoke(value, facade) != 100 + depth) throw new AssertionError("independent callback anchor lost");
            invoke(close, retained);
            retained = null;
            invoke(collect, null);
            try {
                invoke(value, facade);
                throw new AssertionError("escaped callback facade revived");
            } catch (IllegalStateException expected) { }
            escaped = null;
            if (globals() != baselineGlobals) throw new AssertionError("callback JNI globals leaked");
        }
        Method failure = node.getMethod("failure", listener, int.class);
        RuntimeException original = new IllegalStateException("Java callback identity");
        for (int mode : new int[]{0, 1, 2}) {
            try {
                Object result = invoke(failure, null, callback(0, false, original), mode);
                if (mode != 0 || !result.equals(77)) throw new AssertionError("native catch result changed");
            } catch (RuntimeException actual) {
                if (mode == 0 || mode == 1 && actual != original || mode == 2
                        && (!(actual instanceof IllegalArgumentException) || !"replacement".equals(actual.getMessage())))
                    throw new AssertionError("callback failure ordering changed", actual);
            }
            invoke(collect, null);
            if (globals() != baselineGlobals) throw new AssertionError("callback failure JNI globals leaked");
        }
        RuntimeException deferredFailure = new IllegalStateException("deferred identity");
        for (boolean fail : new boolean[]{false, true}) {
            int[] sequence = {0};
            Object deferred = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
                invoke(collect, null);
                if (sequence[0]++ == 0 && method.getName().equals("call")) return 4;
                if (sequence[0] != 2 || !method.getName().equals("event") || !arguments[0].equals(2L))
                    throw new AssertionError("deferred callback order changed");
                if (fail) throw deferredFailure;
                return null;
            });
            try {
                if (!invoke(node.getMethod("deferred", listener), null, deferred).equals(4) || fail)
                    throw new AssertionError("deferred callback result changed");
            } catch (RuntimeException actual) { if (!fail || actual != deferredFailure) throw actual; }
            if (sequence[0] != 2 || globals() != baselineGlobals) throw new AssertionError("deferred callback JNI ownership/order changed");
        }
        Class<?> viewType = Class.forName("ironwood.bridge.ByteView");
        Method borrowed = node.getMethod("borrowed", listener, viewType, int[].class, String.class, boolean.class);
        Object pendingCallback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
            if (!method.getName().equals("event") || !arguments[0].equals(6L)) throw new AssertionError("pending return defer changed");
            invoke(collect, null);
            Object nested = invoke(run, null, callback(3, false, null), 75);
            invoke(close, nested); invoke(collect, null);
            return null;
        });
        Object pending = invoke(node.getMethod("pending", listener, int.class), null, pendingCallback, 404);
        if (!invoke(value, invoke(handleValue, pending)).equals(404)) throw new AssertionError("pending return lost across deferred Java reentry");
        invoke(close, pending); invoke(collect, null);
        if (globals() != baselineGlobals) throw new AssertionError("pending return JNI ownership leaked");
        Object view = invoke(viewType.getMethod("allocate", int.class), null, 4);
        Method put = viewType.getMethod("put", int.class, byte.class), get = viewType.getMethod("get", int.class);
        String text = new String(new char[]{'x', '\u0000', '\u20ac', '\ud83d', '\ude00'});
        for (boolean fail : new boolean[]{false, true}) {
            invoke(put, view, 0, (byte) 6);
            int[] array = {7};
            Object callback = simple(1, ignored -> {
                invoke(collect, null);
                Object nested = invoke(run, null, callback(2, false, null), 82);
                invoke(close, nested);
                System.gc();
                invoke(put, view, 0, (byte) 20);
            });
            try {
                Object result = invoke(borrowed, null, callback, view, array, text, fail);
                if (fail || !text.equals(result)) throw new AssertionError("borrowed String projection changed");
            } catch (IllegalArgumentException actual) {
                if (!fail || !actual.getMessage().equals("borrowed failure")) throw actual;
            }
            if (array[0] != 12 || !invoke(get, view, 1).equals((byte) 21)) throw new AssertionError("borrow or copy-back lost across reentry");
            if (globals() != baselineGlobals) throw new AssertionError("borrowed callback JNI ownership leaked");
        }
    }

    private static void batches() throws Throwable {
        Method open = listeners.getMethod("open"), closeRegistration = registration.getMethod("close");
        Method construct = projected("construct_", handleValue.getDeclaringClass(), registration, int.class, listener, boolean.class);
        Method burst = node.getMethod("burst", int.class);
        Object owner = invoke(open, null);
        Object[] held = {null, null};
        int[] counts = {0, 0};
        boolean[] nested = {false};
        Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
            if (!method.getName().equals("event")) throw new AssertionError(method);
            int lane = nested[0] ? 1 : 0;
            if (!arguments[0].equals((long) counts[lane]++)) throw new AssertionError("nested batch scratch was overwritten");
            invoke(collect, null);
            if (lane == 0 && counts[0] == 1) {
                nested[0] = true;
                try { invoke(burst, held[1], 3); } finally { nested[0] = false; }
            }
            if (lane == 0 && counts[0] == 1000) {
                invoke(closeRegistration, owner); invoke(close, held[0]); invoke(collect, null);
            }
            return null;
        });
        held[0] = invoke(construct, null, owner, 7, callback, false);
        held[1] = invoke(handleValue, held[0]);
        invoke(burst, held[1], 2051);
        if (counts[0] != 2051 || counts[1] != 3) throw new AssertionError("batch callback count changed");
        invoke(node.getMethod("clearSaved"), null);
        if (globals() != baselineGlobals) throw new AssertionError("batch retirement JNI ownership leaked");

        Object next = invoke(open, null);
        RuntimeException expected = new IllegalStateException("batch identity");
        int[] delivered = {0};
        Object failing = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, arguments) -> {
            if (!method.getName().equals("event")) throw new AssertionError(method);
            invoke(collect, null);
            if (delivered[0]++ == 1050) throw expected;
            return null;
        });
        Object handle = invoke(construct, null, next, 8, failing, false);
        try { invoke(burst, invoke(handleValue, handle), 2051); throw new AssertionError("batch failure disappeared"); }
        catch (RuntimeException actual) { if (actual != expected) throw actual; }
        if (delivered[0] != 1051) throw new AssertionError("batch delivered callbacks after failure");
        invoke(closeRegistration, next); invoke(close, handle); invoke(node.getMethod("clearSaved"), null);
        if (globals() != baselineGlobals) throw new AssertionError("batch failure JNI ownership leaked");
    }

    private static void allocationFailures() throws Throwable {
        Method open = listeners.getMethod("open"), closeRegistration = registration.getMethod("close");
        Method construct = projected("construct_", handleValue.getDeclaringClass(), registration, int.class, listener, boolean.class);
        Method saved = node.getMethod("saved"), clearSaved = node.getMethod("clearSaved");
        Object callback = simple(1, null);
        Object deliveryOwner = invoke(open, null);
        failure(6, 3);
        Object delivered;
        try { delivered = invoke(construct, null, deliveryOwner, 271, callback, false); }
        finally { failure(0, 0); }
        if (deliveryAlias == null || invoke(handleValue, delivered) != invoke(handleValue, deliveryAlias))
            throw new AssertionError("delivery reentry split facade identity");
        invoke(close, delivered);
        if (!invoke(value, invoke(handleValue, deliveryAlias)).equals(271)) throw new AssertionError("nested anchor was released");
        invoke(close, deliveryAlias); deliveryAlias = null;
        invoke(closeRegistration, deliveryOwner); invoke(clearSaved, null);
        if (globals() != baselineGlobals) throw new AssertionError("delivery reentry leaked JNI ownership");
        for (int kind : new int[]{1, 2, 3}) for (int after = 1; after <= (kind == 1 ? 5 : 3); after++) {
            Object owner = invoke(open, null);
            failure(kind, after);
            try {
                Object unexpected = invoke(construct, null, owner, 314, callback, false);
                invoke(close, unexpected);
                throw new AssertionError("allocation failure point did not execute: " + kind + ":" + after);
            } catch (OutOfMemoryError expected) {
                // The injected JNI null has no pending failure. Generated
                // delivery must raise the reserved OOM and release acquisitions.
            } finally { failure(0, 0); }
            Object published = invoke(saved, null);
            boolean committed = kind == 3 || kind == 2 && after > 1;
            if ((published != null) != committed) throw new AssertionError("failure changed publication ordering");
            if (published != null) {
                Object facade = invoke(handleValue, published);
                if (!invoke(value, facade).equals(314) || !invoke(node.getMethod("fire", int.class), facade, 314).equals(315))
                    throw new AssertionError("failed delivery destroyed committed listener graph");
                invoke(closeRegistration, owner);
                if (!invoke(value, facade).equals(314)) throw new AssertionError("registration closed independent anchor");
                invoke(close, published);
            } else invoke(closeRegistration, owner);
            invoke(clearSaved, null);
            if (globals() != baselineGlobals) throw new AssertionError("failed delivery leaked JNI ownership: " + kind + ":" + after);
        }
        Class<?> viewType = Class.forName("ironwood.bridge.ByteView");
        Object view = invoke(viewType.getMethod("allocate", int.class), null, 2);
        int[] copied = {7};
        failure(4, 1);
        try {
            invoke(node.getMethod("borrowed", listener, viewType, int[].class, String.class, boolean.class),
                    null, simple(1, ignored -> invoke(collect, null)), view, copied, "copied", false);
            throw new AssertionError("String delivery failure disappeared");
        } catch (OutOfMemoryError expected) {
            if (copied[0] != 12) throw new AssertionError("String delivery failure undid array copy-back");
        } finally { failure(0, 0); }
        if (globals() != baselineGlobals) throw new AssertionError("String failure leaked JNI ownership");
        copied[0] = 7;
        failure(7, 1);
        try {
            invoke(node.getMethod("borrowed", listener, viewType, int[].class, String.class, boolean.class),
                    null, simple(1, null), view, copied, "input", false);
            throw new AssertionError("String acquisition failure disappeared");
        } catch (OutOfMemoryError expected) {
            if (copied[0] != 7) throw new AssertionError("source ran after JNI acquisition failure");
        } finally { failure(0, 0); }
        copied[0] = 7;
        failure(8, 1);
        try {
            invoke(node.getMethod("borrowed", listener, viewType, int[].class, String.class, boolean.class),
                    null, simple(1, ignored -> invoke(collect, null)), view, copied, "failure", true);
            throw new AssertionError("source/copy-back failures disappeared");
        } catch (IllegalArgumentException expected) {
            if (!"borrowed failure".equals(expected.getMessage()) || expected.getSuppressed().length != 1
                    || !(expected.getSuppressed()[0].getCause() instanceof OutOfMemoryError) || copied[0] != 7)
                throw new AssertionError("copy-back failure replaced source failure or diagnostic", expected);
        } finally { failure(0, 0); }
        boolean[] entered = {false};
        failure(5, 1);
        try {
            invoke(node.getMethod("failure", listener, int.class), null, simple(1, ignored -> entered[0] = true), 1);
            throw new AssertionError("segment reservation failure disappeared");
        } catch (OutOfMemoryError expected) {
            if (entered[0]) throw new AssertionError("Java ran after incomplete segment capture");
        } finally { failure(0, 0); }
        invoke(collect, null);
        if (globals() != baselineGlobals) throw new AssertionError("segment capture failure leaked JNI ownership");
    }

    public static void main(String[] arguments) throws Throwable {
        String support = Files.readString(Path.of(arguments[0])).trim();
        node = Class.forName("gc6callbacks.Node");
        listener = Class.forName("gc6callbacks.Listener");
        roots = Class.forName(support + ".Roots");
        listeners = Class.forName(support + ".Listeners");
        registration = Class.forName(support + ".ListenerRegistration");
        Class<?> handle = Class.forName(support + ".RootHandle");
        run = node.getMethod("run", listener, int.class);
        value = node.getMethod("value");
        collect = node.getMethod("collect");
        retain = roots.getMethod("retain", node);
        handleValue = handle.getMethod("value");
        close = handle.getMethod("close");
        invoke(collect, null);
        long baseline = (Long) invoke(node.getMethod("live"), null);
        baselineGlobals = globals();
        for (int warmup = 0; warmup < 40; warmup++) {
            Object result = invoke(run, null, callback(0, false, null), warmup);
            invoke(close, result);
        }
        exercise();
        registrations();
        batches();
        allocationFailures();
        for (int thread = 0; thread < 4; thread++) {
            Throwable[] failure = {null};
            Thread next = new Thread(() -> {
                try { exercise(); } catch (Throwable actual) { failure[0] = actual; }
            });
            next.start(); next.join();
            if (failure[0] != null) throw failure[0];
        }
        invoke(collect, null);
        if ((Long) invoke(node.getMethod("live"), null) != baseline) throw new AssertionError("callback graph retained after completion");
        if (globals() != baselineGlobals) throw new AssertionError("callback JNI ownership did not balance");
        System.out.println("GC6 generated callbacks passed");
    }
}
