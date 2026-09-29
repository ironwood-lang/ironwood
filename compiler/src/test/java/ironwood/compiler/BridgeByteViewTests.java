// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeByteViews;
import ironwood.compiler.semantic.ByteViewIntrinsic;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeByteViewTests {
    static final String NAME = "Java Bridge byte-view proofs preserve typed bounds confinement and artifact parity";
    private BridgeByteViewTests() {}
    static final String SOURCE = """
            package viewfixture;
            import ironwood.bridge.ByteView;
            public final class Values {
                private Values() {}
                private static int read(ByteView view, int index) { return view.get(index); }
                public static long sum(ByteView view) {
                    if (view == null) return -1;
                    long value = 0;
                    for (int i = 0; i < view.length(); i++) value += read(view, i);
                    return value;
                }
                public static int write(ByteView view, int index, byte value, boolean enabled) {
                    if (enabled) view.put(index, value);
                    return view.isReadOnly() ? 1 : 0;
                }
                public static boolean same(ByteView first, ByteView second) { return first == second; }
            }
            """;

    static BridgeProof<BridgeByteViews.Contract> proof(CompilationArtifact artifact, String name) {
        check(artifact.valid(), artifact.diagnostics().toString());
        var id = artifact.program().orElseThrow().functions().stream()
                .filter(f -> f.ownerClass().equals("viewfixture.Values") && f.sourceName().equals(name))
                .map(BridgeCallableId::of).findFirst().orElseThrow();
        return BridgeByteViews.analyze(artifact, id);
    }

    static void proofs() throws Exception {
        var bundled = SourceFile.read(Path.of("stdlib/src/main/ironwood/ironwood/bridge/ByteView.iron"));
        check(ByteViewIntrinsic.trusted(bundled), "bundled view declaration digest is stale");
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron", SOURCE)));
            for (String name : List.of("sum", "write", "same")) {
                var result = proof(artifact, name);
                check(result.status() == BridgeProof.Status.PROVED, name + ": " + result);
                check(result.contract().orElseThrow().observesIdentity() == name.equals("same"), "view identity observation changed");
            }
            long operations = artifact.program().orElseThrow().functions().stream()
                    .flatMap(f -> f.blocks().stream()).flatMap(b -> b.instructions().stream())
                    .filter(IrByteViewInstruction.class::isInstance).count();
            check(operations == 4, "missing typed byte-view descriptor operations: " + operations);
            for (String body : List.of("saved = view; return 0;", "retain(view); return 0;", "free view; return 0;",
                    "Object object = view; return object.hashCode();", "return view.hashCode();",
                    "return view instanceof ByteView ? 1 : 0;")) {
                var bad = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                        "package viewfixture; import ironwood.bridge.ByteView; final class Values {"
                                + "static ByteView saved; static void retain(ByteView v) { saved = v; }"
                                + "static int bad(ByteView view) { " + body + " } }")));
                if (bad.valid()) check(proof(bad, "bad").status() != BridgeProof.Status.PROVED, "unsafe view admitted: " + body);
            }
            var returned = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    "package viewfixture; import ironwood.bridge.ByteView; final class Values {"
                            + "static ByteView bad(ByteView view) { return view; } }")));
            check(proof(returned, "bad").status() == BridgeProof.Status.REJECTED, "view result admitted");
            var changed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron", SOURCE.replace("return -1", "return -2"))));
            var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                    artifact.bridgeConstructionFacts(), changed.bridgeApiFacts());
            check(proof(stale, "sum").status() == BridgeProof.Status.UNKNOWN, "stale view facts admitted");
        }
        var forged = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Values.iron", SOURCE),
                SourceFile.of("ByteView.iron", bundled.content().replace("return false;", "return true;"))));
        check(proof(forged, "sum").status() == BridgeProof.Status.REJECTED, "same-name counterfeit view admitted");
        artifacts();
    }

    private static void artifacts() throws Exception {
        Path directory = Files.createTempDirectory("bridge-byte-view-proof-");
        try {
            Path source = directory.resolve("Values.iron"), classes = directory.resolve("classes"), archive = directory.resolve("values.ironjar");
            Files.writeString(source, SOURCE);
            var before = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            var expected = List.of("sum", "write", "same").stream().map(name -> proof(before, name)).toList();
            BridgeProducerTests.command(directory, "compile", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString()});
            IronJar.create(archive, List.of(classes), List.of());
            Files.delete(source);
            for (Path input : List.of(classes, classes.resolve("viewfixture/Values.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(input)).load(List.of(), List.of("viewfixture.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(expected.equals(List.of("sum", "write", "same").stream().map(name -> proof(restored, name)).toList()),
                        "byte-view proof changed after artifact reconstruction");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
