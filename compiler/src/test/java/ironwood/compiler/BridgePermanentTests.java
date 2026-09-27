// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgePermanentAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgePermanentTests {
    private BridgePermanentTests() {}

    private static final String SOURCE = """
            package permanentfixture;
            final class Item { int value; }
            final class Operations {
                static Item saved;
                static Item identity(Item value) { saved = value; return value; }
                static Item unknownOrigin() { return saved; }
                static void reclaim() { Item temporary = new Item(); free temporary; }
                static Item unknownEffect(Item value) { long ignored = System.nanoTime(); return value; }
                static int[] array(int[] input) { return input; }
            }
            """;

    static void proofs() {
        for (var mode : UnfreedMode.values()) {
            var sources = List.of(SourceFile.of("test/Permanent.iron", SOURCE));
            var ordinary = new CompilerPipeline(mode).analyze(sources);
            var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
            check(ordinary.valid() && artifact.valid() && ordinary.program().equals(artifact.program())
                    && ordinary.diagnostics().equals(artifact.diagnostics()), "ordinary proof changed");
            var roots = roots(artifact, Set.of("identity", "unknownOrigin"));
            var module = BridgeEntryModule.permanentObjects(artifact, roots);
            check(module.permanent().orElseThrow().references().keySet().equals(Set.of(IrType.reference("permanentfixture.Item")))
                    && module.destructions().isEmpty() && module.rootRetention().isEmpty(), "permanent classification changed");
            denied(artifact, Set.of("identity", "reclaim"), BridgeProof.Status.REJECTED, "reachable deallocation");
            denied(artifact, Set.of("unknownEffect"), BridgeProof.Status.UNKNOWN, "unclassified deallocation effect");
            check(!roots(artifact, Set.of("array")).resolved(), "array gained a boundary ABI");
            denied(artifact, Set.of("array"), BridgeProof.Status.UNKNOWN, "resolved roots");
            check(BridgePermanentAnalyzer.analyze(ordinary, roots).status() == BridgeProof.Status.UNKNOWN,
                    "ordinary artifact acquired a bridge capability");
        }
        var publication = List.of(SourceFile.of("test/Published.iron", """
                package permanentfixture;
                final class Published { static Published saved; Published() { saved = this; } }
                """));
        for (var mode : UnfreedMode.values()) {
            var ordinary = new CompilerPipeline(mode).analyze(publication);
            var bridge = new CompilerPipeline(mode).analyzeForBridge(publication);
            check(!ordinary.valid() && ordinary.diagnostics().equals(bridge.diagnostics()), "publication safety changed");
            check(BridgePermanentAnalyzer.analyze(bridge, new BridgeRootSet(List.of(), List.of())).status() == BridgeProof.Status.UNKNOWN,
                    "invalid published construction acquired a permanent capability");
        }
        var unknownCleanup = artifact("""
                package permanentfixture;
                final class Child { destructor { long ignored = System.nanoTime(); } }
                final class Root { private final Child child = new Child(); destructor { free child; } }
                """);
        denied(unknownCleanup, Set.of("Root"), BridgeProof.Status.UNKNOWN, "unclassified destruction operation");
        var dispatch = artifact("""
                package permanentfixture;
                class Base { int value() { return 1; } }
                final class Derived extends Base { @Override int value() { return 2; } }
                """);
        denied(dispatch, Set.of("value"), BridgeProof.Status.REJECTED, "direct receiver dispatch");
    }

    private static CompilationArtifact artifact(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Negative.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact, Set<String> names) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream().filter(function -> names.contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
    }

    private static void denied(CompilationArtifact artifact, Set<String> names, BridgeProof.Status status, String reason) {
        var proof = BridgePermanentAnalyzer.analyze(artifact, roots(artifact, names));
        check(proof.status() == status && proof.reason().contains(reason), names + ": " + proof);
        try { BridgeEntryModule.permanentObjects(artifact, roots(artifact, names)); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unproved permanent entry admitted");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
