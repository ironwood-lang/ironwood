// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeOwnedCallbackProof;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class BridgeOwnedCallbackTests {
    static final String NAME = "Java Bridge owner callbacks bind guards to final storage and complete invocation proofs";
    private static final String SOURCE = """
            package ownercallbacks;
            public interface Listener { long call(long value); }
            final class Holder {
                private Listener listener;
                private long value;
                public Holder(long seed) { value = seed; }
                public void store(Listener input) { listener = input; }
                public long fire(Holder other, long n) { return helper(this, other, n); }
                private static long helper(Holder self, Holder other, long n) {
                    long before = self.value + other.value;
                    long result = self.listener.call(n);
                    self.value = before + result;
                    return self.value + other.value;
                }
                public static long direct(Holder self, Listener input, long n) {
                    return self.value + input.call(n) + self.value;
                }
            }
            """;
    private BridgeOwnedCallbackTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            accepted(List.of(SourceFile.of("Listener.iron", SOURCE)), mode);
            for (var rejected : Map.of(
                    "listener write", SOURCE.replace("self.value = before + result;", "self.listener = null;"),
                    "root retention", SOURCE.replace("private Listener listener;", "private Listener listener; private Holder saved;")
                            .replace("self.value = before + result;", "self.saved = other;"),
                    "static state", SOURCE.replace("private long value;", "private long value; private static long hidden;")
                            .replace("long before = self.value + other.value;", "long before = hidden;"),
                    "owner escape", SOURCE.replace("private long value;", "private long value; private static Holder saved;")
                            .replace("self.value = before + result;", "saved = other;"),
                    "ordinary allocation", SOURCE.replace("self.value = before + result;", "Holder local = new Holder(1L); free local;"),
                    "native initialized listener", SOURCE.replace("public Holder(long seed) { value = seed; }",
                            "public Holder(long seed) { value = seed; listener = new Local(); }")
                            + "final class Local implements Listener { @Override public long call(long n) { return n; } }",
                    "reference constructor", SOURCE.replace("public Holder(long seed) { value = seed; }",
                            "public Holder(Listener input) { listener = input; }")
            ).entrySet()) {
                try {
                    proof(List.of(SourceFile.of("Listener.iron", rejected.getValue())), mode, false);
                    throw new AssertionError("owner callback admitted " + rejected.getKey() + " in " + mode);
                } catch (IllegalArgumentException expected) {
                    check(!expected.getMessage().isBlank(), expected.toString());
                }
            }
        }
        Path directory = Files.createTempDirectory("bridge owner callback proof ");
        try {
            Path source = directory.resolve("Listener.iron"); Files.writeString(source, SOURCE);
            Path classes = directory.resolve("classes"), archive = directory.resolve("owners.ironjar");
            var bytes = new ByteArrayOutputStream();
            var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, output, output) == 0, bytes.toString(StandardCharsets.UTF_8));
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0,
                    bytes.toString(StandardCharsets.UTF_8));
            Files.delete(source);
            for (var container : List.of(classes, classes.resolve("ownercallbacks/Listener.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container)).loadBridge(List.of(), List.of("ownercallbacks"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) accepted(loaded.sources(), mode);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void accepted(List<SourceFile> sources, UnfreedMode mode) {
        var proof = proof(sources, mode, true);
        check(proof.guardedInputs().size() == 2, "missing instance/static owner obligations");
        for (var entry : proof.guardedInputs().entrySet()) {
            check(entry.getValue().equals(entry.getKey().name().equals("fire") ? List.of(0, 1) : List.of(0)),
                    "incorrect guarded owners " + entry);
        }
    }

    private static BridgeOwnedCallbackProof proof(List<SourceFile> sources, UnfreedMode mode, boolean controls) {
        var pipeline = new CompilerPipeline(mode);
        var original = pipeline.analyzeForBridge(sources);
        check(original.valid(), original.diagnostics().toString());
        var proxies = BridgeListenerProxies.discover(original, List.of("ownercallbacks"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var constructors = BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("ownercallbacks.Holder") && function.constructor())
                .map(BridgeCallableId::of).toList());
        var storage = BridgeEntryModule.rootObjects(artifact, constructors);
        var finalStorage = BridgeFinalRootRetention.prove(artifact, storage);
        if (finalStorage.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(finalStorage.reason());
        var lifetime = finalStorage.contract().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                function.ownerClass().equals("ownercallbacks.Holder") && List.of("fire", "direct").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        var proof = BridgeOwnedCallbackProof.prove(artifact, proxies, roots, storage, lifetime);
        if (controls) {
            check(proof.matches(artifact, roots, storage, lifetime), "own proof mismatch");
            check(!proof.matches(original, roots, storage, lifetime), "unbound artifact accepted");
            check(!proof.matches(artifact, constructors, storage, lifetime), "changed invocation accepted");
            var otherStorage = BridgeEntryModule.rootObjects(artifact, constructors);
            check(!proof.matches(artifact, roots, otherStorage, lifetime), "different storage proof accepted");
            try {
                BridgeOwnedCallbackProof.prove(artifact, proxies, roots, otherStorage, lifetime);
                throw new AssertionError("mismatched final storage admitted");
            } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
            try {
                BridgeSynchronousCallbackEntries.create(artifact, proxies, roots);
                throw new AssertionError("stateless path silently admitted owners");
            } catch (IllegalArgumentException expected) { check(!expected.getMessage().isBlank(), expected.toString()); }
            check(BridgeExportSurface.synchronousCallbacks(artifact, List.of("ownercallbacks")).surface().isEmpty(),
                    "component enabled incomplete public owner adapters");
        }
        return proof;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
