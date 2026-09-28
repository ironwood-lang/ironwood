// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeOwnedCallbackProof;
import ironwood.compiler.semantic.BridgeOwnedListenerSlots;
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
            public final class Holder {
                private Listener listener;
                private long value;
                public Holder(long seed) { value = seed; }
                public void store(Listener input) { listener = input; }
                public void clear() { listener = null; }
                public long value() { return value; }
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
            accepted(sources(SOURCE), mode);
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
                            "public Holder(Listener input) { listener = input; }"),
                    "callback during slot write", SOURCE.replace("listener = input;", "listener = input; input.call(1L);"),
                    "hidden listener publication", SOURCE.replace("private Listener listener;", "private Listener listener; private static Listener saved;")
                            .replace("listener = input;", "listener = input; saved = input;"),
                    "dynamic listener query", SOURCE.replace("listener = input;", "listener = input; if (input instanceof Other) value = 1L;")
                            + "interface Other { long accept(long n); }"
            ).entrySet()) {
                try {
                    proof(sources(rejected.getValue()), mode, false);
                    throw new AssertionError("owner callback admitted " + rejected.getKey() + " in " + mode);
                } catch (IllegalArgumentException expected) {
                    check(!expected.getMessage().isBlank(), expected.toString());
                }
            }
        }
        Path directory = Files.createTempDirectory("bridge owner callback proof ");
        try {
            Path source = directory.resolve("Listener.iron"), holder = directory.resolve("Holder.iron");
            var sources = sources(SOURCE);
            Files.writeString(source, sources.get(0).content()); Files.writeString(holder, sources.get(1).content());
            Path classes = directory.resolve("classes"), archive = directory.resolve("owners.ironjar");
            var bytes = new ByteArrayOutputStream();
            var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), holder.toString(), "-d", classes.toString()}, output, output) == 0,
                    bytes.toString(StandardCharsets.UTF_8));
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0,
                    bytes.toString(StandardCharsets.UTF_8));
            Files.delete(source); Files.delete(holder);
            for (var containers : List.of(List.of(classes), List.of(classes.resolve("ownercallbacks/Listener.ironclass"),
                    classes.resolve("ownercallbacks/Holder.ironclass")), List.of(archive))) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), containers).loadBridge(List.of(), List.of("ownercallbacks"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) accepted(loaded.sources(), mode);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static List<SourceFile> sources(String source) {
        int start = source.indexOf("public final class Holder");
        return List.of(SourceFile.of("Listener.iron", source.substring(0, start)),
                SourceFile.of("Holder.iron", "package ownercallbacks;\n" + source.substring(start)));
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
        var carrier = BridgeCallbackCarrierSources.discover(original);
        var combined = new java.util.ArrayList<>(sources); combined.add(carrier.source());
        var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(combined), List.of("ownercallbacks"));
        var artifact = pipeline.analyzeForBridge(combined, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var constructors = BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("ownercallbacks.Holder") && function.constructor())
                .map(BridgeCallableId::of).toList());
        var storage = BridgeEntryModule.callbackOwnerStorage(artifact, constructors);
        var proxyEntries = BridgeListenerProxyEntries.create(artifact, proxies);
        check(storage.entrySymbols().stream().noneMatch(symbol -> proxyEntries.functions().stream()
                .anyMatch(function -> function.linkageName().equals(symbol))), "owner and proxy storage symbols collide");
        var finalStorage = BridgeFinalRootRetention.prove(artifact, storage);
        if (finalStorage.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(finalStorage.reason());
        var lifetime = finalStorage.contract().orElseThrow();
        var slotRoots = BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                function.ownerClass().equals("ownercallbacks.Holder") && List.of("store", "clear", "value").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        var slots = BridgeOwnedListenerSlots.prove(artifact, proxies, slotRoots, storage, lifetime);
        check(slots.matches(artifact, slotRoots, storage, lifetime) && !slots.matches(original, slotRoots, storage, lifetime),
                "owner slot proof lost storage binding");
        check(slots.entries().entries().stream().filter(entry -> entry.callable().name().equals("value"))
                .allMatch(entry -> entry.retention().slots().isEmpty()), "scalar getter acquired slot reconciliation");
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                function.ownerClass().equals("ownercallbacks.Holder") && List.of("fire", "direct").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        var proof = BridgeOwnedCallbackProof.prove(artifact, proxies, roots, storage, lifetime);
        if (controls) {
            var composition = BridgeOwnedCallbackAdmission.prove(artifact, proxies, carrier, List.of("ownercallbacks"));
            check(composition.status() == BridgeProof.Status.PROVED, composition.reason());
            var admitted = composition.contract().orElseThrow();
            check(admitted.matches(artifact, admitted.surface()) && !admitted.matches(original, admitted.surface()),
                    "native owner composition lost exact artifact identity");
            check(admitted.entries().entries().size() == admitted.surface().roots().roots().size(), "partial composed surface");
            var generation = BridgeGeneration.createOwnedCallbacks("owners", admitted, "test", "a".repeat(64), "b".repeat(64));
            check(BridgeGeneration.fromManifest(generation.manifest()).matchesOwnedCallbacks(admitted), "owner manifest cannot round trip");
            var state = BridgeRootStateSources.generateOwnedCallbacks(admitted, generation);
            String stateSource = state.sources().get(generation.supportPackage().replace('.', '/') + "/RootState.java");
            check(state.slotCapacity() == 0 && stateSource.contains("private long listenerOwner;")
                    && stateSource.contains("enterCallbackUse"), "owner host state lost callback lifetime guards");
            String rootIndex = BridgeRootIndexSources.generateOwnedCallbacks(admitted, generation).source();
            check(rootIndex.contains("struct iw_listener_slot *listeners[1]")
                    && rootIndex.contains("iw_listener_slot_release(env, record->listeners[index])"), "owner destruction lost listener cleanup");
            check(new ironwood.compiler.backend.LlvmEmitter().emit(admitted.program()).contains("ironwood_bridge_owned_callback_"),
                    "composed owner program cannot emit its protected callbacks");
            var entries = BridgeOwnedCallbackEntries.create(artifact, proxies, roots, storage, lifetime);
            check(entries.matches(artifact, roots) && !entries.matches(original, roots), "owner entries lost final binding");
            for (var entry : entries.entries()) {
                var locals = new java.util.LinkedHashMap<Integer, String>();
                entry.guardedInputs().forEach(index -> locals.put(index, "owner" + index));
                String guarded = entries.guard(artifact, entry.callable(), locals, "return nativeCall();");
                for (var local : locals.values()) check(guarded.contains(local + ".enterCallbackUse()")
                        && guarded.contains(local + ".leaveCallbackUse()"), "missing balanced owner guard");
                try {
                    entries.guard(artifact, entry.callable(), Map.of(), "return nativeCall();");
                    throw new AssertionError("missing owner obligation accepted");
                } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("every proved owner"), expected.toString()); }
                locals.put(entry.guardedInputs().getFirst(), "owner.state()");
                try {
                    entries.guard(artifact, entry.callable(), locals, "return nativeCall();");
                    throw new AssertionError("reevaluated owner expression accepted");
                } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("stable"), expected.toString()); }
            }
            try {
                BridgeOwnedCallbackEntries.create(artifact, proxies, roots, storage, lifetime, constructors, entries.context());
                throw new AssertionError("different specialization roots accepted");
            } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("specialization"), expected.toString()); }
            check(proof.matches(artifact, roots, storage, lifetime), "own proof mismatch");
            check(!proof.matches(original, roots, storage, lifetime), "unbound artifact accepted");
            check(!proof.matches(artifact, constructors, storage, lifetime), "changed invocation accepted");
            var otherStorage = BridgeEntryModule.callbackOwnerStorage(artifact, constructors);
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
