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
        for (var mode : UnfreedMode.values()) {
            for (String body : List.of("values[0]++; return values;", "return values;",
                    "return choose ? values : other;", "return null;", "return new int[3];",
                    "int[] result = new int[3]; result[0] = values[0]; return result;")) {
                var artifact = analyze(body, mode);
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
                var artifact = analyze(body, mode);
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
                var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                        "package arrayvalues; public final class Values { private Values() {} " + declaration
                                + " } interface Listener { void event(); }")));
                if (!artifact.valid()) continue;
                check(BridgeArrayInputs.values(artifact, target(artifact)).status() != BridgeProof.Status.PROVED,
                        "unsafe array closure admitted: " + declaration);
                check(BridgeExportSurface.valuePreview(artifact, List.of("arrayvalues")).surface().isEmpty(),
                        "unsafe array export admitted: " + declaration);
            }
            var varargs = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    "package arrayvalues; public final class Values { private Values() {} public static int value(int... a) { return a.length; } }")));
            check(!varargs.valid() && varargs.diagnostics().stream().anyMatch(d -> d.message().contains("varargs")), "varargs boundary lost");
        }
        artifacts();
    }

    private static void artifacts() throws Exception {
        Path directory = Files.createTempDirectory("bridge-array-values-");
        try {
            Path source = directory.resolve("Values.iron"), classes = directory.resolve("classes"), archive = directory.resolve("values.ironjar");
            Files.writeString(source, BridgeArrayValueProducerTests.SOURCE);
            var original = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            check(original.valid(), original.diagnostics().toString());
            var selected = BridgeExportSurface.valuePreview(original, List.of("arrayvalues"));
            check(selected.surface().isPresent(), selected.diagnostics().toString());
            var ids = selected.surface().orElseThrow().roots().roots().stream().map(BridgeRootSet.Root::callable)
                    .filter(id -> id.result().isArray() || id.parameters().stream().anyMatch(ironwood.compiler.ir.IrType::isArray)).toList();
            var expected = ids.stream().map(id -> BridgeArrayInputs.values(original, id)).toList();
            BridgeProducerTests.command(directory, "compile", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString()});
            IronJar.create(archive, List.of(classes), List.of());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("arrayvalues/Values.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("arrayvalues.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(ids.stream().map(id -> BridgeArrayInputs.values(restored, id)).toList().equals(expected), "mutable array artifact proof changed");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(String body, UnfreedMode mode) {
        return new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                "package arrayvalues; final class Values { static int[] saved; "
                        + "static int[] value(int[] values, int[] other, boolean choose) { " + body + " } }")));
    }

    private static BridgeCallableId target(CompilationArtifact artifact) {
        return artifact.program().orElseThrow().functions().stream().filter(function -> function.ownerClass().equals("arrayvalues.Values")
                && function.sourceName().equals("value")).map(BridgeCallableId::of).findFirst().orElseThrow();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
