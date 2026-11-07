// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeArrayInputs;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

final class BridgeArrayValueTests {
    static final String NAME = "Java Bridge array value proofs distinguish mutation aliases fresh and retained results";
    private BridgeArrayValueTests() {}

    static void proofs() throws Exception {
        // This fixture scope preserves independent local names.
{
            for (String body : List.of("values[0]++; return values;", "return values;",
                    "return choose ? values : other;", "return null;", "return new int[3];",
                    "int[] result = new int[3]; result[0] = values[0]; return result;")) {
                var artifact = analyze(body);
                check(artifact.valid(), artifact.diagnostics().toString());
                var id = target(artifact);
                var proof = BridgeArrayInputs.values(artifact, id);
                check(proof.status() == BridgeProof.Status.PROVED, body + ": " + proof);
                var expected = body.contains("new int") ? BridgeResultOriginContract.Kind.FRESH_ROOT
                        : body.equals("return null;") ? BridgeResultOriginContract.Kind.NULL_ONLY : BridgeResultOriginContract.Kind.INPUT_ALIAS;
                check(proof.contract().orElseThrow().result().orElseThrow().kind() == expected, "wrong array result ownership");
                check(BridgeArrayInputs.readOnly(artifact, id).status() != BridgeProof.Status.PROVED, "read-only stage admitted results");
            }
            for (String body : List.of("saved = values; return values;", "return saved;",
                    "int[] result = new int[3]; saved = result; return result;", "free values; return null;",
                    "return choose ? values : new int[3];")) {
                var artifact = analyze(body);
                if (body.startsWith("free ")) {
                    check(!artifact.valid() && artifact.diagnostics().stream().anyMatch(d -> d.message().contains("removed")),
                            "removed reclamation syntax admitted");
                    continue;
                }
                if (!artifact.valid()) continue;
                var proof = BridgeArrayInputs.values(artifact, target(artifact));
                check(proof.status() != BridgeProof.Status.PROVED, "unproved array value admitted: " + body);
            }
            for (String declaration : List.of(
                    "static int[] saved; public static void value(int[] values) { retain(values); } static void retain(int[] a) { saved = a; }",
                    "private int[] saved; public int[] value(int[] values) { saved = values; return values; }",
                    "private int[] saved; public int[] value() { return saved; }",
                    "public static void value(int[] values, Listener listener) { listener.event(); values[0]++; }",
                    "public static int[] value(int[] values) { System.getenv(\"HOME\"); return values; }",
                    "public static void value(int[] values) { System.arraycopy(values, 0, values, 0, 1); }",
                    "public static int[][] value(int[][] values) { return values; }",
                    "public static Object[] value(Object[] values) { return values; }")) {
                var artifact = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Values.iron",
                        "package arrayvalues; public final class Values { private Values() {} " + declaration
                                + " } interface Listener { void event(); }")));
                if (!artifact.valid()) continue;
                check(BridgeArrayInputs.values(artifact, target(artifact)).status() != BridgeProof.Status.PROVED,
                        "unsafe array closure admitted: " + declaration);
                check(BridgeExportSurface.staticValues(artifact, List.of("arrayvalues")).surface().isEmpty(),
                        "unsafe array export admitted: " + declaration);
            }
            var varargs = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    "package arrayvalues; public final class Values { private Values() {} public static int value(int... a) { return a.length; } }")));
            check(!varargs.valid() && varargs.diagnostics().stream().anyMatch(d -> d.message().contains("varargs")), "varargs boundary lost");
        }
        artifacts();
    }

    private static void artifacts() throws Exception {
        Path directory = Files.createTempDirectory("bridge-array-values-");
        try {
            Path source = directory.resolve("Values.iron"), classes = directory.resolve("classes"), archive = directory.resolve("values.ironjar");
            Files.writeString(source, "package arrayvalues;\npublic final class Values {\n    private Values() {}\n    private static int entered;\n    public static long live() { return System.liveAllocationCount(); }\n    public static long allocations() { return System.allocationCount(); }\n    public static int calls() { return entered; }\n    public static int[] two(int[] first, int[] second) {\n        entered++; first[0] += 10; second[0] += 20; return first;\n    }\n    public static int[] choose(int[] first, int[] second, boolean left) { return left ? first : second; }\n    public static int[] absent() { return null; }\n    public static int[] fresh(int size) {\n        int[] result = new int[size];\n        for (int i = 0; i < size; i++) result[i] = i * 3;\n        return result;\n    }\n    public static int[] result(int[] values) { values[0]++; return fresh(4); }\n    public static void fail(int[] first, int[] second) {\n        first[0] = 11; second[0] = 22; first[first.length] = 33;\n    }\n    public static boolean[] bool(boolean[] a, boolean v) { a[0] = v; return a; }\n    public static byte[] b(byte[] a, byte v) { a[0] = v; return a; }\n    public static short[] s(short[] a, short v) { a[0] = v; return a; }\n    public static char[] c(char[] a, char v) { a[0] = v; return a; }\n    public static long[] l(long[] a, long v) { a[0] = v; return a; }\n    public static float[] f(float[] a, float v) { a[0] = v; return a; }\n    public static double[] d(double[] a, double v) { a[0] = v; return a; }\n    public static boolean[] newBool(boolean v) { return new boolean[]{v}; }\n    public static byte[] newB(byte v) { return new byte[]{v}; }\n    public static short[] newS(short v) { return new short[]{v}; }\n    public static char[] newC(char v) { return new char[]{v}; }\n    public static long[] newL(long v) { return new long[]{v}; }\n    public static float[] newF(float v) { return new float[]{v}; }\n    public static double[] newD(double v) { return new double[]{v}; }\n}\n");
            var original = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.read(source)));
            check(original.valid(), original.diagnostics().toString());
            var selected = BridgeExportSurface.staticValues(original, List.of("arrayvalues"));
            check(selected.surface().isPresent(), selected.diagnostics().toString());
            var ids = selected.surface().orElseThrow().roots().roots().stream().map(BridgeRootSet.Root::callable)
                    .filter(id -> id.result().isArray() || id.parameters().stream().anyMatch(ironwood.compiler.ir.IrType::isArray)).toList();
            var expected = ids.stream().map(id -> BridgeArrayInputs.values(original, id)).toList();
            GcTestProcesses.command(directory, "compile", 0, new String[]{"-d", classes.toString(), source.toString()});
            IronJar.create(archive, List.of(classes), List.of());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("arrayvalues/Values.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("arrayvalues.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = new CompilerPipeline().analyzeForBridge(loaded.sources());
                check(ids.stream().map(id -> BridgeArrayInputs.values(restored, id)).toList().equals(expected), "mutable array artifact proof changed");
            }
            Files.writeString(source, """
                    package arrayvalues;
                    public final class Values {
                        private Values() {}
                        private static int[] saved;
                        public static int[] value(int[] input) { saved = input; return input; }
                    }
                    """);
            var unsafe = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.read(source)));
            check(unsafe.valid(), unsafe.diagnostics().toString());
            var rejected = BridgeArrayInputs.values(unsafe, target(unsafe));
            check(rejected.status() == BridgeProof.Status.REJECTED && rejected.reason().contains("escape"), "retaining result was admitted");
            Path negativeClasses = directory.resolve("negative-classes"), negativeArchive = directory.resolve("negative.ironjar");
            GcTestProcesses.command(directory, "compile-negative", 0, new String[]{"-d", negativeClasses.toString(), source.toString()});
            IronJar.create(negativeArchive, List.of(negativeClasses), List.of());
            Files.delete(source);
            for (Path container : List.of(negativeClasses, negativeClasses.resolve("arrayvalues/Values.ironclass"), negativeArchive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("arrayvalues.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = new CompilerPipeline().analyzeForBridge(loaded.sources());
                check(rejected.equals(BridgeArrayInputs.values(restored, target(restored))), "retaining result changed after reconstruction");
                check(BridgeExportSurface.staticValues(restored, List.of("arrayvalues")).surface().isEmpty(), "archive bypassed array confinement");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(String body) {
        return new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Values.iron",
                "package arrayvalues; final class Values { static int[] saved; "
                        + "static int[] value(int[] values, int[] other, boolean choose) { " + body + " } }")));
    }

    private static BridgeCallableId target(CompilationArtifact artifact) {
        return artifact.program().orElseThrow().functions().stream().filter(function -> function.ownerClass().equals("arrayvalues.Values")
                && function.sourceName().equals("value")).map(BridgeCallableId::of).findFirst().orElseThrow();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
