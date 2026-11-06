// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.support;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Resolves an image's generated lifetime API during example setup. */
public final class BridgeLifetime {
    private BridgeLifetime() {}

    private static Class<?> support(Class<?> facade, String name) {
        for (var annotation : facade.getAnnotations()) {
            Class<?> type = annotation.annotationType();
            if (type.getSimpleName().equals("Identity") && type.getPackageName().startsWith("ironwood.bridge.generated.g")) {
                try { return Class.forName(type.getPackageName() + "." + name, true, facade.getClassLoader()); }
                catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
            }
        }
        throw new IllegalArgumentException("not a generated bridge facade: " + facade.getName());
    }

    public static final class Anchor<T> implements AutoCloseable {
        private final AutoCloseable handle;
        private final MethodHandle getter;
        private Anchor(Object handle) throws ReflectiveOperationException {
            this.handle = (AutoCloseable) handle;
            getter = MethodHandles.publicLookup().unreflect(handle.getClass().getMethod("value"))
                    .bindTo(handle).asType(MethodType.methodType(Object.class));
        }
        @SuppressWarnings("unchecked")
        public T get() {
            try { return (T) (Object) getter.invokeExact(); }
            catch (Throwable failure) { throw propagate(failure); }
        }
        @Override public void close() {
            try { handle.close(); }
            catch (Exception failure) { throw propagate(failure); }
        }
    }

    public static <T> Anchor<T> construct(Class<?> facade, Object... arguments) {
        String tag = HexFormat.of().formatHex(facade.getName().getBytes(StandardCharsets.UTF_8));
        Method selected = null;
        for (var method : support(facade, "Roots").getMethods()) {
            if (method.getName().equals("construct_" + tag) && accepts(method.getParameterTypes(), arguments)) {
                if (selected != null) throw new IllegalArgumentException("ambiguous constructor; use the generated Roots overload directly");
                selected = method;
            }
        }
        if (selected == null) throw new IllegalArgumentException("no generated Roots constructor for " + facade.getName());
        try { return new Anchor<>(selected.invoke(null, arguments)); }
        catch (ReflectiveOperationException failure) { throw propagate(failure); }
    }

    public static final class Registration implements AutoCloseable {
        private final Object registration;
        private final Class<?> listeners;
        private Registration(Class<?> facade) throws ReflectiveOperationException {
            listeners = support(facade, "Listeners");
            registration = listeners.getMethod("open").invoke(null);
        }
        public <T, L> Installer<T, L> setter(Class<T> receiver, String name, Class<L> listener) {
            Method selected = null;
            for (var method : listeners.getMethods()) {
                if (!method.getName().startsWith("install_")) continue;
                String signature = new String(HexFormat.of().parseHex(method.getName().substring(8)), StandardCharsets.UTF_8);
                var parameters = method.getParameterTypes();
                if (signature.contains("\"name\":\"" + name + "\"") && parameters.length == 3
                        && parameters[1].equals(receiver) && parameters[2].equals(listener)) {
                    if (selected != null) throw new IllegalArgumentException("ambiguous listener setter");
                    selected = method;
                }
            }
            if (selected == null) throw new IllegalArgumentException("no generated listener setter: " + name);
            try {
                return new Installer<>(MethodHandles.publicLookup().unreflect(selected).bindTo(registration)
                        .asType(MethodType.methodType(void.class, Object.class, Object.class)));
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
        @Override public void close() {
            try { ((AutoCloseable) registration).close(); }
            catch (Exception failure) { throw propagate(failure); }
        }
    }

    public static final class Installer<T, L> {
        private final MethodHandle method;
        private Installer(MethodHandle method) { this.method = method; }
        public void set(T receiver, L listener) {
            try { method.invokeExact((Object) receiver, (Object) listener); }
            catch (Throwable failure) { throw propagate(failure); }
        }
    }

    public static Registration registration(Class<?> facade) {
        try { return new Registration(facade); }
        catch (ReflectiveOperationException failure) { throw propagate(failure); }
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        if (parameters.length != arguments.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i];
            if (arguments[i] == null) { if (type.isPrimitive()) return false; else continue; }
            if (type.isPrimitive()) type = type == int.class ? Integer.class : type == long.class ? Long.class
                    : type == boolean.class ? Boolean.class : type == byte.class ? Byte.class : type == short.class ? Short.class
                    : type == char.class ? Character.class : type == float.class ? Float.class : Double.class;
            if (!type.isInstance(arguments[i])) return false;
        }
        return true;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException target) failure = target.getCause();
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }
}
