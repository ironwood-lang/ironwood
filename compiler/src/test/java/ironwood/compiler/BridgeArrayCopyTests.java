// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeArrayCopyTests {
    private BridgeArrayCopyTests() {}

    private static final String SOURCE = "package copyeffects;\nfinal class Holder { int[] primitives; char[] characters; Object[] references; }\nfinal class Copies {\n    static Holder saved;\n    static void copy(Object source, Object destination) { System.arraycopy(source, 0, destination, 0, 1); }\n    static void helper(Object source, Object destination) { copy(source, destination); }\n    static Object local() {\n        int[] source = new int[1];\n        int[] destination = new int[1];\n        helper(source, destination);\n        ;\n        return destination;\n    }\n    static Object field(Holder holder) { return holder.primitives; }\n    static void fieldCopy(Holder holder) { helper(field(holder), holder.primitives); }\n    static void recursive(Holder holder, int depth) {\n        if (depth <= 0) fieldCopy(holder);\n        else recursive(holder, depth - 1);\n    }\n    static void references(Holder holder) { helper(holder.references, holder.references); }\n    static void mixed(Holder holder, boolean choose) {\n        Object source = holder.primitives;\n        if (choose) source = holder.references;\n        helper(source, holder.primitives);\n    }\n    static void mismatch(Holder holder) { helper(holder.primitives, holder.characters); }\n    static void unknown(Holder holder, Object destination) { helper(holder.primitives, destination); }\n    static void publication(Holder holder) { fieldCopy(holder); saved = holder; }\n}\n";

    static void proofs() throws Exception {
        // This fixture scope preserves independent local names.
{
            verify(List.of(SourceFile.of("Copies.iron", SOURCE)));
            var managed = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Copies.iron",
                    SOURCE.replace("return destination;", "return null;"))));
            check(managed.valid(), managed.diagnostics().toString());
        }
        Path directory = Files.createTempDirectory("bridge-array-copy-");
        try {
            Path source = directory.resolve("Copies.iron");
            Files.writeString(source, SOURCE);
            var expected = verify(List.of(SourceFile.read(source)));
            var output = new ByteArrayOutputStream();
            var print = new PrintStream(output, true, StandardCharsets.UTF_8);
            Path classes = directory.resolve("classes"), archive = directory.resolve("copy.ironjar");
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, print, print) == 0, output.toString());
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, print, print) == 0,
                    output.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("copyeffects/Copies.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container))
                        .load(List.of(), List.of("copyeffects.Copies"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                check(expected.equals(verify(loaded.sources())), "arraycopy proof changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> verify(List<SourceFile> sources) {
        var artifact = new CompilerPipeline().analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals("copyeffects.Copies")
                && function.kind() == ironwood.compiler.ir.IrCallableKind.METHOD).map(BridgeCallableId::of).toList());
        var proofs = BridgeRetentionAnalyzer.analyze(program, roots);
        check(proofs.size() == 11, "missing copy proof");
        Set<String> safe = Set.of("local", "field", "fieldCopy", "recursive");
        for (var entry : proofs.entrySet()) {
            var expected = safe.contains(entry.getKey().name()) ? BridgeProof.Status.PROVED
                    : entry.getKey().name().equals("publication") ? BridgeProof.Status.REJECTED : BridgeProof.Status.UNKNOWN;
            check(entry.getValue().status() == expected, entry.toString());
            if (expected == BridgeProof.Status.PROVED) check(entry.getValue().contract().orElseThrow().slots().isEmpty(), "primitive copy retained an input");
        }
        return proofs;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
