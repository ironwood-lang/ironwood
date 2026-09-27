// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeExceptionProjection;

/** Child-JVM consumer for the production generated graph assembler. */
final class BridgeExceptionGraphTests {
    private BridgeExceptionGraphTests() {}

    static String consumer(String supportPackage, BridgeExceptionProjection projection) {
        String source = CONSUMER.replace("@PACKAGE@", supportPackage);
        for (var type : projection.types()) source = source.replace("@" + type.javaName() + "@", Integer.toString(type.typeId()));
        return source;
    }

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            import java.io.*;
            import java.lang.reflect.*;
            import java.nio.file.DirectoryIteratorException;
            import java.util.*;
            public final class GraphConsumer {
                private static Method graph;
                private static final int IO = @java.io.IOException@;
                private static final int BAD = @java.lang.IllegalArgumentException@;
                private static final int DIRECTORY = @java.nio.file.DirectoryIteratorException@;
                private static final int UNCHECKED = @java.io.UncheckedIOException@;
                private static final StackTraceElement NATIVE = new StackTraceElement("app.Native", "fail", "Native.iron", 27);
                private static final class Input {
                    final int[] types, numbers, causes;
                    final String[] messages, first, second, third;
                    final int[][] secondary;
                    final StackTraceElement[][] frames;
                    Input(int count) {
                        types = new int[count]; Arrays.fill(types, IO);
                        numbers = new int[count]; causes = new int[count]; Arrays.fill(causes, -1);
                        messages = new String[count]; first = new String[count]; second = new String[count]; third = new String[count];
                        secondary = new int[count][]; frames = new StackTraceElement[count][];
                        for (int i = 0; i < count; i++) {
                            messages[i] = "node" + i + "\\u0000\\ud800";
                            secondary[i] = new int[0]; frames[i] = new StackTraceElement[]{NATIVE};
                        }
                    }
                    Throwable[] assemble() throws Exception {
                        return (Throwable[]) graph.invoke(null, types, messages, first, second, third, numbers, causes, secondary, frames);
                    }
                }
                private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
                private static void bad(Input input) throws Exception {
                    try { input.assemble(); throw new AssertionError("invalid graph accepted"); }
                    catch (InvocationTargetException expected) { check(expected.getCause() instanceof LinkageError, expected.toString()); }
                }
                private static void traces(Throwable[] values) {
                    for (Throwable value : values) {
                        StackTraceElement[] trace = value.getStackTrace();
                        check(trace[0].equals(NATIVE), "native frame lost");
                        check(trace.length <= 96 && Arrays.stream(trace).anyMatch(frame -> frame.getClassName().equals("GraphConsumer")),
                                "Java call site lost");
                        check(Arrays.stream(trace).noneMatch(frame -> frame.getClassName().equals("@PACKAGE@.ExceptionFactory")),
                                "factory frame exposed");
                    }
                }
                private static Throwable[] deep(int depth, Input input) throws Exception {
                    return depth == 0 ? input.assemble() : deep(depth - 1, input);
                }
                private static void pressure(Input input) throws Throwable {
                    var handle = java.lang.invoke.MethodHandles.lookup().unreflect(graph);
                    Throwable[][] retained = new Throwable[100000][];
                    boolean failed = false;
                    for (int i = 0; i < retained.length; i++) {
                        try {
                            retained[i] = (Throwable[]) handle.invokeExact(input.types, input.messages, input.first, input.second,
                                    input.third, input.numbers, input.causes, input.secondary, input.frames);
                        } catch (OutOfMemoryError expected) { failed = true; break; }
                    }
                    Arrays.fill(retained, null);
                    System.gc();
                    check(failed, "graph failed to exhaust the bounded child heap");
                    check(input.assemble()[0].getMessage().equals(input.messages[0]), "graph did not recover after Java OOM");
                }
                public static void main(String[] args) throws Throwable {
                    graph = Class.forName("@PACKAGE@.ExceptionFactory").getDeclaredMethod("graph", int[].class, String[].class,
                            String[].class, String[].class, String[].class, int[].class, int[].class, int[][].class,
                            StackTraceElement[][].class);
                    check(Modifier.isPrivate(graph.getModifiers()) && Modifier.isStatic(graph.getModifiers()), "graph visibility");
                    graph.setAccessible(true);
                    if (args.length != 0) {
                        pressure(new Input(32));
                        System.out.println("graph-oom-ok:" + Runtime.version());
                        return;
                    }
                    Input shared = new Input(3);
                    shared.types[0] = BAD; shared.causes[0] = 1; shared.causes[1] = 2;
                    shared.secondary[0] = new int[]{2, 1, 2};
                    Throwable[] values = shared.assemble();
                    check(values[0].getClass() == IllegalArgumentException.class && values[0].getCause() == values[1]
                            && values[1].getCause() == values[2], "cause identity/type lost");
                    check(Arrays.equals(values[0].getSuppressed(), new Throwable[]{values[2], values[1], values[2]}), "secondary order/identity lost");
                    check(values[0].getMessage().equals(shared.messages[0]), "UTF-16 message changed");
                    traces(values);
                    shared.causes[2] = 0;
                    values = shared.assemble();
                    check(values[2].getCause() == values[0], "representable cause cycle lost");
                    shared.secondary[2] = new int[]{0};
                    values = shared.assemble();
                    check(values[2].getSuppressed()[0] == values[0], "representable secondary cycle lost");
                    Input required = new Input(3);
                    required.types[0] = DIRECTORY; required.types[1] = UNCHECKED;
                    required.causes[0] = 2; required.causes[1] = 2; required.causes[2] = 0;
                    values = required.assemble();
                    check(values[0].getClass() == DirectoryIteratorException.class && values[1].getClass() == UncheckedIOException.class,
                            "required cause wrapper types");
                    check(values[0].getCause() == values[2] && values[1].getCause() == values[2] && values[2].getCause() == values[0],
                            "wrapper construction dependency or cycle lost");
                    required.causes[0] = -2; required.causes[1] = -2;
                    values = required.assemble();
                    check(values[0].getCause() instanceof IOException && values[0].getCause() == values[1].getCause()
                            && values[0].getCause().getMessage().contains("copy limit"), "required omitted-cause marker");
                    required.causes[0] = -1; bad(required);
                    required.causes[0] = 1; bad(required);
                    Input self = new Input(1); self.causes[0] = 0; self.secondary[0] = new int[]{0, -2};
                    values = self.assemble();
                    check(values[0].getCause() != values[0] && values[0].getCause().getMessage().contains("self-reference")
                            && values[0].getSuppressed()[0] == values[0].getCause(), "self reference representation");
                    check(values[0].getCause().getStackTrace().length == 0, "marker acquired unrelated trace");
                    Input boundary = new Input(32);
                    for (int i = 0; i < 32; i++) {
                        boundary.secondary[i] = new int[32]; Arrays.fill(boundary.secondary[i], (i + 1) % 32);
                        boundary.frames[i] = new StackTraceElement[32]; Arrays.fill(boundary.frames[i], NATIVE);
                    }
                    values = deep(100, boundary);
                    for (Throwable value : values) check(value.getSuppressed().length == 32 && value.getStackTrace().length == 96,
                            "bounded graph/trace boundary changed");
                    check(values[0].getStackTrace()[95].getMethodName().equals("javaFramesTruncated"), "silent Java trace truncation");
                    bad(new Input(0)); bad(new Input(33));
                    Input invalid = new Input(1); invalid.causes[0] = 1; bad(invalid);
                    invalid.causes[0] = -3; bad(invalid); invalid.causes[0] = -1;
                    invalid.secondary[0] = new int[]{-1}; bad(invalid);
                    invalid.secondary[0] = new int[]{1}; bad(invalid);
                    invalid.secondary[0] = new int[33]; bad(invalid);
                    invalid.secondary[0] = null; bad(invalid); invalid.secondary[0] = new int[0];
                    invalid.frames[0] = new StackTraceElement[]{null}; bad(invalid);
                    invalid.frames[0] = new StackTraceElement[33]; bad(invalid);
                    invalid.frames[0] = null; bad(invalid); invalid.frames[0] = new StackTraceElement[0];
                    invalid.types[0] = -1; bad(invalid);
                    try {
                        graph.invoke(null, null, null, null, null, null, null, null, null, null);
                        throw new AssertionError("null graph accepted");
                    } catch (InvocationTargetException expected) { check(expected.getCause() instanceof LinkageError, "null graph failure"); }
                    System.out.println("graph-ok:" + Runtime.version());
                }
            }
            """;
}
