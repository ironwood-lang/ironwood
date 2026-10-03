// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
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

final class BridgeFreshGraphTests {
    private BridgeFreshGraphTests() {}

    private static final String SOURCE = """
            package freshgraph;
            final class Node { Object value; Node next; Node() {} Node(Object value) { this.value = value; } }
            final class ArrayHolder { Node[] values; }
            final class Graphs {
                static Node saved;
                static Node direct() { Node root = new Node(); root.next = new Node(); return root; }
                static Node make(Object value) { return new Node(value); }
                static void store(Node holder, Node child) { holder.next = child; }
                static Node helper() { Node root = new Node(); store(root, new Node()); return root; }
                static Node recursive(int depth) { Node root = new Node(); if (depth > 0) root.next = recursive(depth - 1); return root; }
                static Node cycle() { Node first = new Node(); Node second = new Node(); first.next = second; second.next = first; return first; }
                static int exception() {
                    ironwood.io.IOException cause = new ironwood.io.IOException("cause");
                    throw new ironwood.nio.file.DirectoryIteratorException(cause);
                }
                static Node constructorCapture(Object input) { Node child = new Node(input); Node root = new Node(); root.next = child; return root; }
                static Node helperCapture(Object input) { Node child = make(input); Node root = new Node(); store(root, child); return root; }
                static Node mixed(Node input, boolean choose) {
                    Node child = choose ? input : new Node();
                    Node root = new Node(); root.next = child; return root;
                }
                static Node loaded(Node input) { Node root = new Node(); root.value = input.value; return root; }
                static void replace(Node input) { input.next = new Node(); }
                static void publish() { saved = direct(); }
                static void array(Node[] output) { output[0] = direct(); }
                static void arrayPublish(ArrayHolder output) { array(output.values); }
                static Node unknown(String input) { Node root = new Node(); root.value = input.repeat(2); return root; }
            }
            """;

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) verify(List.of(SourceFile.of("Graphs.iron", SOURCE)), mode);
        for (var mode : UnfreedMode.values()) {
            var invalid = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Unsafe.iron", """
                    class Node { Node child; }
                    class Unsafe { static Node fail() { Node root = new Node(); Node child = new Node(); root.child = child; free child; return root; } }
                    """)));
            check(!invalid.valid() && invalid.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("cannot free 'child'")),
                    "fresh graph retention weakened ordinary reclamation: " + invalid.diagnostics());
        }
        Path directory = Files.createTempDirectory("bridge-fresh-graphs-");
        try {
            Path source = directory.resolve("Graphs.iron"); Files.writeString(source, SOURCE);
            var expected = verify(List.of(SourceFile.read(source)), UnfreedMode.OFF);
            var output = new ByteArrayOutputStream(); var print = new PrintStream(output, true, StandardCharsets.UTF_8);
            Path classes = directory.resolve("classes"), archive = directory.resolve("graphs.ironjar");
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, print, print) == 0, output.toString());
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, print, print) == 0, output.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("freshgraph/Graphs.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("freshgraph.Graphs"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) check(expected.equals(verify(loaded.sources(), mode)),
                        "fresh graph attribution changed after reconstruction: " + container + " " + mode);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> verify(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals("freshgraph.Graphs")
                && function.kind() == IrCallableKind.METHOD && !function.sourceName().equals("array"))
                .map(BridgeCallableId::of).toList());
        var proofs = BridgeRetentionAnalyzer.analyze(program, roots, artifact.bridgeConstructionFacts().orElseThrow());
        Set<String> safe = Set.of("direct", "helper", "recursive", "cycle", "exception", "store");
        check(proofs.size() == 15, "missing fresh graph proof: " + proofs.keySet());
        for (var entry : proofs.entrySet()) {
            var expected = safe.contains(entry.getKey().name()) ? BridgeProof.Status.PROVED
                    : entry.getKey().name().equals("replace") ? BridgeProof.Status.UNKNOWN : BridgeProof.Status.REJECTED;
            check(entry.getValue().status() == expected, entry.toString());
            if (safe.contains(entry.getKey().name()) && !entry.getKey().name().equals("store")) {
                check(entry.getValue().contract().orElseThrow().slots().isEmpty(), "independent graph retained an entry input");
            }
        }
        var exception = BridgeRootSet.resolve(program, roots.roots().stream().map(BridgeRootSet.Root::callable)
                .filter(id -> id.name().equals("exception")).toList());
        check(BridgeEntryModule.scalars(artifact, exception).entries().size() == 1, "fresh builtin cause graph entry was not produced");
        return proofs;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
