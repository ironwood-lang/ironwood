// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class BridgeResultOriginTests {
    private BridgeResultOriginTests() {}

    static final String SOURCE = """
            package resultfixture;
            final class Leaf { int number; }
            final class Node {
                private final Leaf owned = new Leaf();
                static Node published;
                destructor { free owned; }
                Node alias(boolean absent) { return absent ? null : this; }
                Node helper() { return alias(false); }
                static Node argument(Node input, boolean absent) { return absent ? null : input; }
                static Node choose(Node first, Node second, boolean useFirst) { return useFirst ? first : second; }
                static Node fresh(boolean absent) { return absent ? null : new Node(); }
                static Node empty() { return null; }
                Node mixed(boolean allocate) { return allocate ? new Node() : this; }
                static Node escapes() { Node result = new Node(); published = result; return result; }
                static Node unknown() { return published; }
                Leaf view() { return owned; }
                static Leaf viewOf(Node parent) { return parent.view(); }
                static Leaf ambiguous(Node first, Node second, boolean useFirst) {
                    return useFirst ? first.view() : second.view();
                }
                static Node element(Node[] array) { return array[0]; }
            }
            final class Borrower {
                private final Leaf external;
                Borrower(Leaf leaf) { external = leaf; }
                Leaf unprovedOwner() { return external; }
            }
            """;

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var sources = List.of(SourceFile.of("test/Results.iron", SOURCE));
            var ordinary = new CompilerPipeline(mode).analyze(sources);
            var bridge = new CompilerPipeline(mode).analyzeForBridge(sources);
            check(ordinary.valid() && bridge.valid(), bridge.diagnostics().toString());
            check(ordinary.program().equals(bridge.program()) && ordinary.diagnostics().equals(bridge.diagnostics()),
                    "result projection changed ordinary IR or safety diagnostics");
            var facts = bridge.bridgeConstructionFacts().orElseThrow();
            check(facts.matches(bridge.program().orElseThrow()), "result facts are not bound");
            var results = named(facts.resultOrigins());
            accepted(results, "alias", BridgeResultOriginContract.Kind.INPUT_ALIAS, Set.of(0), true);
            accepted(results, "helper", BridgeResultOriginContract.Kind.INPUT_ALIAS, Set.of(0), true);
            accepted(results, "argument", BridgeResultOriginContract.Kind.INPUT_ALIAS, Set.of(0), true);
            accepted(results, "choose", BridgeResultOriginContract.Kind.INPUT_ALIAS, Set.of(0, 1), true);
            accepted(results, "fresh", BridgeResultOriginContract.Kind.FRESH_ROOT, Set.of(), true);
            accepted(results, "empty", BridgeResultOriginContract.Kind.NULL_ONLY, Set.of(), true);
            accepted(results, "view", BridgeResultOriginContract.Kind.DEPENDENT_VIEW, Set.of(0), true);
            accepted(results, "viewOf", BridgeResultOriginContract.Kind.DEPENDENT_VIEW, Set.of(0), true);
            for (String name : List.of("mixed", "escapes", "unknown", "ambiguous", "element", "unprovedOwner")) {
                check(results.get(name).status() != BridgeProof.Status.PROVED && results.get(name).contract().isEmpty(),
                        "unproved result admitted: " + name + " " + results.get(name));
            }
            check(results.get("mixed").status() == BridgeProof.Status.REJECTED
                    && results.get("escapes").status() == BridgeProof.Status.REJECTED, "ownership conflict was not rejected");
        }
        artifacts();
    }

    private static void accepted(Map<String, BridgeProof<BridgeResultOriginContract>> results, String name,
            BridgeResultOriginContract.Kind kind, Set<Integer> inputs, boolean nullable) {
        var proof = results.get(name);
        check(proof.status() == BridgeProof.Status.PROVED, name + ": " + proof.reason());
        var contract = proof.contract().orElseThrow();
        check(contract.kind() == kind && contract.inputs().equals(inputs) && contract.nullable() == nullable,
                "unexpected result origins: " + name + " " + contract);
    }

    private static Map<String, BridgeProof<BridgeResultOriginContract>> named(
            Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> facts) {
        return facts.entrySet().stream().filter(entry -> entry.getKey().owner().startsWith("resultfixture."))
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
    }

    private static void artifacts() throws Exception {
        var directory = Files.createTempDirectory("bridge result origins ");
        try {
            var source = directory.resolve("Results.iron");
            Files.writeString(source, SOURCE);
            var original = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            var expected = named(original.bridgeConstructionFacts().orElseThrow().resultOrigins());
            var expectedLowering = BridgeRootResultTests.module(original);
            var classes = directory.resolve("classes");
            var output = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString());
            var archive = directory.resolve("results.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0, output.toString());
            Files.delete(source);
            for (var container : List.of(classes, classes.resolve("resultfixture/Node.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("resultfixture.Node", "resultfixture.Borrower"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var reconstructed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(reconstructed.valid(), reconstructed.diagnostics().toString());
                check(expected.equals(named(reconstructed.bridgeConstructionFacts().orElseThrow().resultOrigins())),
                        "result origins changed after reconstruction: " + container);
                var actual = BridgeRootResultTests.module(reconstructed);
                check(expectedLowering.entries().equals(actual.entries())
                        && expectedLowering.rootRetention().orElseThrow().resultOrigins().equals(actual.rootRetention().orElseThrow().resultOrigins()),
                        "root result contracts or protected lowering changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
