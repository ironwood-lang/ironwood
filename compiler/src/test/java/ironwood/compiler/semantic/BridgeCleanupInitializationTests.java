// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Optional;

public final class BridgeCleanupInitializationTests {
    private static final String SOURCE = """
            package cleanupinit;
            public final class Root {
                private final String label = new String("root");
                private static int[] values = new int[1];
                private static String saved;
                private static void publish() { saved = "published"; }
                public Root() {}
                destructor { free label; }
                public String text() { return label; }
                private static final class Other {
                    private static String saved = "initialized";
                    static void touch() {}
                }
                private static final class Cold {
                    private static int[] storage = new int[1];
                    static void touch() {}
                }
            }
            """;

    private BridgeCleanupInitializationTests() {}

    public static void proofs() {
        for (var mode : UnfreedMode.values()) {
            for (String operation : List.of("", "saved = \"published\";", "publish();", "Other.touch();", "Cold.touch();",
                    "long ignored = System.nanoTime();")) {
                String content = SOURCE.replace("free label;", operation + " free label;");
                if (!operation.isEmpty()) content = content.replace("values = new int[1]", "values");
                var source = SourceFile.of("Root.iron", content);
                var ordinary = new CompilerPipeline(mode).analyze(List.of(source));
                var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
                check(artifact.program().equals(ordinary.program()) && artifact.diagnostics().equals(ordinary.diagnostics()),
                        "cleanup query changed ordinary source safety");
                if (operation.equals("Cold.touch();")) {
                    check(!artifact.valid() && artifact.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.message().contains("destructor may allocate")),
                            "actual cold initialization lost ordinary cleanup rejection: " + artifact.diagnostics());
                    continue;
                }
                check(artifact.valid() && ordinary.valid(), operation + ": " + artifact.diagnostics());
                var program = artifact.program().orElseThrow();
                var facts = artifact.bridgeConstructionFacts().orElseThrow();
                var surface = BridgeExportSurface.concreteObjects(artifact, List.of("cleanupinit"));
                check(surface.surface().isPresent(), surface.diagnostics().toString());
                var roots = surface.surface().orElseThrow().roots();
                var ctor = roots.roots().stream().map(BridgeRootSet.Root::callable)
                        .filter(id -> id.kind() == IrCallableKind.CONSTRUCTOR).findFirst().orElseThrow();
                var cleanup = BridgeCleanupAnalyzer.analyze(artifact, roots, IrType.reference("cleanupinit.Root"), Optional.empty());
                check((cleanup.status() == BridgeProof.Status.PROVED) == operation.isEmpty(),
                        operation + ": " + cleanup.status() + ": " + cleanup.reason());
                var rollback = BridgeCleanupAnalyzer.analyze(artifact, roots, IrType.reference("cleanupinit.Root"), Optional.of(ctor));
                check(rollback.status() == BridgeProof.Status.PROVED,
                        "unpublished field rollback acquired an uncalled destructor effect: " + rollback.reason());
                var cleanupRoots = BridgeRootSet.resolve(program, program.functions().stream()
                        .filter(function -> function.ownerClass().equals("cleanupinit.Root")
                                && (function.kind() == IrCallableKind.DESTRUCTOR || function.kind() == IrCallableKind.CONSTRUCTOR_ROLLBACK))
                        .map(BridgeCallableId::of).toList());
                check(cleanupRoots.resolved() && !cleanupRoots.roots().isEmpty(), "missing cleanup roots");
                var body = BridgeRetentionAnalyzer.cleanupBodies(program, cleanupRoots, facts);
                check(body.values().stream().allMatch(proof -> proof.status() == BridgeProof.Status.PROVED) == operation.isEmpty(),
                        "cleanup lost an actual effect: " + operation + ": " + body);
                if (operation.equals("Other.touch();")) check(body.values().stream()
                        .anyMatch(proof -> proof.reason().contains("Other.<clinit>")), "actual initializer publication was not traversed");
                var ordinaryEntry = BridgeRetentionAnalyzer.analyze(program, cleanupRoots, facts);
                check(ordinaryEntry.values().stream().anyMatch(proof -> proof.status() != BridgeProof.Status.PROVED),
                        "ordinary entry initialization was removed");
                try {
                    BridgeRetentionAnalyzer.cleanupBodies(program, roots, facts);
                    throw new AssertionError("ordinary method/constructor used cleanup-only query");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("descriptor cleanup roots"), expected.toString());
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
