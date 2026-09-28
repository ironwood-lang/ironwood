// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeArrayInputs;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeArrayInputTests {
    static final String NAME = "Java Bridge array input proofs preserve confinement and artifact parity";
    private BridgeArrayInputTests() {}

    static final String SOURCE = """
            package arrayfixture;
            public final class Values {
                private Values() {}
                private static int read(int[] values, int index) { return values[index]; }
                public static long sum(int[] values) {
                    if (values == null) return -1;
                    long total = 0;
                    for (int index = 0; index < values.length; index++) total += read(values, index);
                    return total;
                }
                public static boolean same(int[] first, int[] second) { return first == second; }
                public static boolean bool(boolean[] values) { return values[0]; }
                public static byte b(byte[] values) { return values[0]; }
                public static short s(short[] values) { return values[0]; }
                public static char c(char[] values) { return values[0]; }
                public static long l(long[] values) { return values[0]; }
                public static float f(float[] values) { return values[0]; }
                public static double d(double[] values) { return values[0]; }
            }
            """;

    private static BridgeProof<BridgeArrayInputs.Contract> proof(CompilationArtifact artifact, String name) {
        check(artifact.valid(), artifact.diagnostics().toString());
        var id = artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals("arrayfixture.Values") && function.sourceName().equals(name))
                .map(BridgeCallableId::of).findFirst().orElseThrow();
        return BridgeArrayInputs.readOnly(artifact, id);
    }

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron", SOURCE)));
            for (String name : List.of("sum", "same", "bool", "b", "s", "c", "l", "f", "d")) {
                var result = proof(artifact, name);
                check(result.status() == BridgeProof.Status.PROVED, name + ": " + result);
            }
            var selected = BridgeExportSurface.valuePreview(artifact, List.of("arrayfixture"));
            check(selected.surface().isPresent(), selected.diagnostics().toString());
            var module = BridgeEntryModule.stringValues(artifact, selected.surface().orElseThrow().roots());
            String llvm = new ironwood.compiler.backend.LlvmEmitter().emit(module);
            check(llvm.contains("invoke ptr @ironwood_bridge_copy_array"), "array allocation escaped protection");
            for (String body : List.of("values[0] = 1; return 0;", "saved = values; return 0;",
                    "retain(values); return 0;", "free values; return 0;",
                    "System.arraycopy(values, 0, values, 1, 1); return 0;")) {
                var unsafe = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                        "package arrayfixture; final class Values { static int[] saved; "
                                + "static void retain(int[] value) { saved = value; } "
                                + "static int bad(int[] values) { " + body + " } }")));
                if (!unsafe.valid()) continue;
                check(proof(unsafe, "bad").status() != BridgeProof.Status.PROVED, "unsafe array input admitted: " + body);
            }
            var returned = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    "package arrayfixture; final class Values { static int[] bad(int[] values) { return values; } }")));
            check(proof(returned, "bad").status() == BridgeProof.Status.REJECTED, "P7b1 admitted array results");
            var changed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    SOURCE.replace("return -1", "return -2"))));
            var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                    artifact.bridgeConstructionFacts(), changed.bridgeApiFacts());
            check(proof(stale, "sum").status() == BridgeProof.Status.UNKNOWN, "stale array borrowing facts admitted");
        }
        artifacts();
    }

    private static void artifacts() throws Exception {
        Path directory = Files.createTempDirectory("bridge-array-inputs-");
        try {
            Path source = directory.resolve("Values.iron");
            Files.writeString(source, SOURCE);
            var expected = proof(new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source))), "sum");
            var bytes = new ByteArrayOutputStream();
            var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            Path classes = directory.resolve("classes");
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, output, output) == 0, bytes.toString());
            Path archive = directory.resolve("arrays.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0,
                    bytes.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("arrayfixture/Values.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container))
                        .load(List.of(), List.of("arrayfixture.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                check(expected.equals(proof(new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources()), "sum")),
                        "array proof changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
