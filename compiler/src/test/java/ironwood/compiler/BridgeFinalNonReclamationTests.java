// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class BridgeFinalNonReclamationTests {
    static final String NAME = "Java Bridge final lifetime includes custom getter closure and refuses hidden reclamation";
    private static final String CATALOG = """
            package finalproof;
            public final class Catalog {
                private final String label = new String("catalog");
                private static Catalog saved;
                public Catalog() {}
                destructor { free label; }
                public String text() { saved = this; return label; }
                public static Catalog current() { return saved; }
                public static void fail() { throw new snapshoterrors.Problem(); }
            }
            """;
    private static final String PROBLEM = """
            package snapshoterrors;
            public final class Problem extends RuntimeException {
                public Problem() {}
                public int getCode() { return 17; }
            }
            """;
    private static final String EXTRA = """
            package snapshoterrors;
            public final class Extra extends RuntimeException {
                public Extra() {}
                public int getDetail() { return 29; }
            }
            """;

    private BridgeFinalNonReclamationTests() {}

    static void proofs() throws Exception {
        var sources = sources(PROBLEM, EXTRA);
        for (var mode : UnfreedMode.values()) {
            verify(analyze(sources, mode));
            var bad = analyze(sources(PROBLEM.replace("return 17;", "finalproof.Catalog value = new finalproof.Catalog(); free value; return 17;"), EXTRA), mode);
            var unsafe = BridgeFinalNonReclamation.prove(bad, module(bad));
            check(unsafe.status() == BridgeProof.Status.REJECTED && unsafe.reason().contains("deallocation"),
                    "custom getter cleanup hidden from final lifetime: " + unsafe.reason());
            var expanded = analyze(sources(PROBLEM.replace("return 17;", "throw new Extra();"), EXTRA), mode);
            var expansion = BridgeFinalNonReclamation.prove(expanded, module(expanded));
            check(expansion.status() == BridgeProof.Status.PROVED, expansion.reason());
            check(expansion.contract().orElseThrow().exceptions().projection().customTypes().keySet()
                    .equals(Set.of("snapshoterrors.Problem", "snapshoterrors.Extra")), "getter-created exception absent from fixed point");
            var unsupported = analyze(sources(PROBLEM.replace("return 17;", "throw new Extra();"),
                    EXTRA.replace("public int getDetail() { return 29; }", "public Object getDetail() { return null; }")), mode);
            var refused = BridgeFinalNonReclamation.prove(unsupported, module(unsupported));
            check(refused.status() != BridgeProof.Status.PROVED && refused.reason().contains("getDetail"),
                    "unsupported transitively discovered getter admitted: " + refused.reason());
        }
        parity(sources, null);
        parity(sources(PROBLEM.replace("return 17;", "finalproof.Catalog value = new finalproof.Catalog(); free value; return 17;"), EXTRA),
                "deallocation");
        parity(sources(PROBLEM.replace("return 17;", "throw new Extra();"),
                EXTRA.replace("public int getDetail() { return 29; }", "public Object getDetail() { return null; }")), "getDetail");
    }

    private static void parity(List<SourceFile> sources, String refusal) throws Exception {
        Path temporary = Files.createTempDirectory("bridge final lifetime ");
        try {
            Path classes = temporary.resolve("classes");
            for (var source : sources) {
                var unit = SourceParser.parse(source).unit().orElseThrow();
                for (var type : DeclaredTypes.in(unit)) {
                    IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
                }
            }
            Path archive = temporary.resolve("final.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classes.resolve("finalproof/Catalog.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(), input.toString().endsWith(IronClass.EXTENSION)
                        ? List.of(input, classes) : List.of(input)).loadBridge(List.of(), List.of("finalproof"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), input + ": " + artifact.diagnostics());
                if (refusal == null) verify(artifact);
                else {
                    var result = BridgeFinalNonReclamation.prove(artifact, module(artifact));
                    check(result.status() == BridgeProof.Status.REJECTED && result.reason().contains(refusal),
                            "reconstruction lost final lifetime refusal: " + input + ": " + result.reason());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verify(CompilationArtifact artifact) {
        var module = module(artifact);
        var builtin = BridgeExceptionClosure.builtins(artifact, module);
        check(builtin.status() == BridgeProof.Status.REJECTED && builtin.reason().contains("custom exception"), "P2 producer boundary broadened");
        var proof = BridgeFinalNonReclamation.prove(artifact, module);
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var contract = proof.contract().orElseThrow();
        check(contract.references().keySet().equals(Set.of(IrType.reference("finalproof.Catalog"))), "final permanent inventory changed");
        check(contract.exceptions().projection().customTypes().keySet().equals(Set.of("snapshoterrors.Problem")),
                "unreachable custom type added to closure");
        var checked = contract.references().values().iterator().next();
        check(checked.exportRoots().size() == contract.program().exportRoots().size(), "emitted roots omitted from final proof");
        check(checked.checkedClosure().stream().anyMatch(id -> id.owner().equals("snapshoterrors.Problem") && id.name().equals("getCode")),
                "custom getter body omitted from lifetime closure");
        check(checked.unpublishedCleanups().stream().anyMatch(cleanup -> cleanup.caller().linkage().startsWith("ironwood_bridge_entry_")),
                "final constructor rollback attribution missing");
        check(contract.matches(module, contract.program()) && !contract.matches(module(artifact), contract.program())
                && !contract.matches(module, module.program()), "final proof accepted unrelated entries or pre-link IR");
        new ironwood.compiler.backend.LlvmEmitter().emit(contract.program());
    }

    private static BridgeEntryModule module(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.concreteObjects(artifact, List.of("finalproof"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return BridgeEntryModule.permanentObjects(artifact, selected.surface().orElseThrow().roots());
    }

    private static List<SourceFile> sources(String problem, String extra) {
        return List.of(SourceFile.of("Catalog.iron", CATALOG), SourceFile.of("Problem.iron", problem), SourceFile.of("Extra.iron", extra));
    }

    private static CompilationArtifact analyze(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
