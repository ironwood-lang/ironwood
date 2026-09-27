// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.stream.Collectors;

/** Bounded Java assembly of copied exception data, with no native calls or ownership. */
final class BridgeExceptionGraphSources {
    static final int NODE_LIMIT = 32;
    static final int SECONDARY_LIMIT = 32;
    static final int NATIVE_FRAME_LIMIT = 32;
    static final int JAVA_FRAME_LIMIT = 64;

    private BridgeExceptionGraphSources() {}

    static String generate(BridgeExceptionProjection projection) {
        return generate(projection, null);
    }

    static String generate(BridgeExceptionProjection projection, BridgeCustomSnapshotSources.Context custom) {
        String required = projection.types().stream().filter(type -> requiresCause(type.nativeName())
                        || custom != null && requiresCause(custom.layout().builtinBases().getOrDefault(type.nativeName(), "")))
                .map(type -> Integer.toString(type.typeId())).collect(Collectors.joining(", "));
        String predicate = required.isEmpty() ? "return false;"
                : "return switch (type) { case " + required + " -> true; default -> false; };";
        return SOURCE.replace("@NODES@", Integer.toString(NODE_LIMIT))
                .replace("@SECONDARY@", Integer.toString(SECONDARY_LIMIT))
                .replace("@NATIVE_FRAMES@", Integer.toString(NATIVE_FRAME_LIMIT))
                .replace("@JAVA_FRAMES@", Integer.toString(JAVA_FRAME_LIMIT))
                .replace("@REQUIRED@", predicate)
                .replace("@CUSTOM_PARAMETERS@", custom == null ? "" : ", long[][] copiedNumbers, String[][] copiedTexts")
                .replace("@CUSTOM_VALIDATE@", custom == null ? "" : "if (!snapshotArray(copiedNumbers, count) || !snapshotArray(copiedTexts, count)) throw invalidGraph();")
                .replace("@CUSTOM_DATA@", custom == null ? "" : "SnapshotData[] copied = new SnapshotData[count];\n"
                        + "        for (int i = 0; i < count; i++) copied[i] = snapshotData(types[i], messages[i], copiedNumbers[i], copiedTexts[i]);")
                .replace("@CUSTOM_ARGUMENT@", custom == null ? "" : ", copied[i]")
                .replace("@CUSTOM_BUILTIN@", custom == null ? "" : "copied[i] == null && ")
                .replace("@CUSTOM_EDGES@", custom == null ? "" : """
                        if (copied[i] != null) {
                            copied[i].cause = edge(values, causes[i], i, marker);
                            copied[i].secondary = new Throwable[secondary[i].length];
                            for (int j = 0; j < secondary[i].length; j++) copied[i].secondary[j] = edge(values, secondary[i][j], i, marker);
                            validateSnapshot(types[i], copied[i]);
                        }
                        """);
    }

    private static boolean requiresCause(String name) {
        return name.equals("ironwood.nio.file.DirectoryIteratorException") || name.equals("ironwood.io.UncheckedIOException");
    }

    private static final String SOURCE = """
                // Indices: -1 is no cause; -2 is an explicitly omitted edge.
                // All nodes are copied snapshots. The returned array lets JNI finish
                // nonfinal message fields before any throwable reaches user code.
                private static Throwable[] graph(int[] types, String[] messages, String[] first,
                        String[] second, String[] third, int[] numbers, int[] causes,
                        int[][] secondary, StackTraceElement[][] frames@CUSTOM_PARAMETERS@) {
                    if (types == null || types.length == 0 || types.length > @NODES@) throw invalidGraph();
                    int count = types.length;
                    if (!snapshotArray(messages, count) || !snapshotArray(first, count)
                            || !snapshotArray(second, count) || !snapshotArray(third, count)
                            || numbers == null || numbers.length != count || causes == null || causes.length != count
                            || !snapshotArray(secondary, count) || !snapshotArray(frames, count)) {
                        throw invalidGraph();
                    }
                    @CUSTOM_VALIDATE@
                    boolean omitted = false;
                    for (int i = 0; i < count; i++) {
                        if (causes[i] < -2 || causes[i] >= count || secondary[i] == null
                                || secondary[i].length > @SECONDARY@ || frames[i] == null
                                || frames[i].length > @NATIVE_FRAMES@) throw invalidGraph();
                        omitted |= causes[i] == -2 || causes[i] == i;
                        for (int edge : secondary[i]) {
                            if (edge < -2 || edge == -1 || edge >= count) throw invalidGraph();
                            omitted |= edge == -2 || edge == i;
                        }
                        for (StackTraceElement frame : frames[i]) if (frame == null) throw invalidGraph();
                    }
                    Throwable marker = null;
                    if (omitted) {
                        marker = new java.io.IOException("Ironwood exception snapshot: self-reference or copy limit");
                        marker.setStackTrace(new StackTraceElement[0]);
                    }
                    Throwable[] values = new Throwable[count];
                    @CUSTOM_DATA@
                    for (int i = 0; i < count; i++) {
                        if (!requiredCause(types[i])) {
                            values[i] = create(types[i], messages[i], null, first[i], second[i], third[i], numbers[i]@CUSTOM_ARGUMENT@);
                        }
                    }
                    for (int i = 0; i < count; i++) {
                        if (requiredCause(types[i])) {
                            Throwable cause = edge(values, causes[i], i, marker);
                            if (@CUSTOM_BUILTIN@!(cause instanceof java.io.IOException)) throw invalidGraph();
                            values[i] = create(types[i], messages[i], cause, first[i], second[i], third[i], numbers[i]@CUSTOM_ARGUMENT@);
                        }
                    }
                    StackTraceElement[] javaFrames = new Throwable().getStackTrace();
                    int start = 0;
                    while (start < javaFrames.length && javaFrames[start].getClassName().equals(ExceptionFactory.class.getName())) start++;
                    int javaCount = Math.min(javaFrames.length - start, @JAVA_FRAMES@);
                    boolean javaTruncated = javaFrames.length - start > javaCount;
                    for (int i = 0; i < count; i++) {
                        Throwable value = values[i];
                        @CUSTOM_EDGES@
                        if (@CUSTOM_BUILTIN@!requiredCause(types[i]) && causes[i] != -1) value.initCause(edge(values, causes[i], i, marker));
                        for (int index : secondary[i]) value.addSuppressed(edge(values, index, i, marker));
                        StackTraceElement[] trace = java.util.Arrays.copyOf(frames[i], frames[i].length + javaCount);
                        System.arraycopy(javaFrames, start, trace, frames[i].length, javaCount);
                        if (javaTruncated) trace[trace.length - 1] = new StackTraceElement("ironwood.bridge.Snapshot",
                                "javaFramesTruncated", null, -1);
                        value.setStackTrace(trace);
                    }
                    return values;
                }
                private static boolean requiredCause(int type) { @REQUIRED@ }
                private static boolean snapshotArray(Object[] values, int count) {
                    return values != null && values.length >= count && values.length <= @NODES@;
                }
                private static Throwable edge(Throwable[] values, int index, int self, Throwable marker) {
                    return index == -1 ? null : index == -2 || index == self ? marker : values[index];
                }
                private static LinkageError invalidGraph() {
                    return new LinkageError("invalid Ironwood exception snapshot graph");
                }
            """;
}
