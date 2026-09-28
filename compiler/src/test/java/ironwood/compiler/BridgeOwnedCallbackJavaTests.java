// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BridgeOwnedCallbackJavaTests {
    static final String NAME = "Java Bridge owner facades bind private metadata and exact callback guard partitions";
    private BridgeOwnedCallbackJavaTests() {}

    static List<SourceFile> sources() {
        return List.of(SourceFile.of("Listener.iron", """
                package ownerfacades;
                public interface Listener { long call(long value); }
                """), SourceFile.of("Holder.iron", """
                package ownerfacades;
                public final class Holder {
                    private Listener listener;
                    private long value;
                    public Holder(long seed) { value = seed; }
                    public void store(Listener input) { listener = input; }
                    public void choose(Listener first, Listener second, boolean fail) {
                        listener = first;
                        if (fail) throw new IllegalStateException("first installed");
                        listener = second;
                    }
                    public static void put(Holder first, Holder second, Listener a, Listener b) {
                        first.listener = a; second.listener = b;
                    }
                    public long value() { return value; }
                    public long textValue(String input) { return hash(input); }
                    public long text(String first, String second, long mode) {
                        long before = hash(first) + hash(second);
                        listener.call(mode);
                        if (before != hash(first) + hash(second)) throw new IllegalStateException("changed copied input");
                        return before;
                    }
                    private static long hash(String input) {
                        if (input == null) return -1L;
                        long result = 1L;
                        for (int i = 0; i < input.length(); i++) result = result * 31L + (long)input.charAt(i);
                        return result;
                    }
                    public long twice(long n) { Listener saved = listener; return saved.call(n) + saved.call(n + 1L); }
                    public long fire(Holder other, OtherOwner foreign, long n) {
                        long result = listener.call(n);
                        return result + other.value + foreign.call(n);
                    }
                    public static long direct(Holder self, Listener input, long n) { return self.value + input.call(n); }
                }
                """), SourceFile.of("OtherOwner.iron", """
                package ownerfacades;
                public final class OtherOwner {
                    private Listener listener;
                    public OtherOwner() {}
                    public void store(Listener input) { listener = input; }
                    public long call(long n) { return listener.call(n); }
                }
                """));
    }

    static BridgeOwnedCallbackAdmission admission() {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var sources = new ArrayList<>(sources());
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var carrier = BridgeCallbackCarrierSources.discover(initial); sources.add(carrier.source());
        var listeners = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("ownerfacades"));
        var artifact = pipeline.analyzeForBridge(sources, listeners);
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeOwnedCallbackAdmission.prove(artifact, listeners, carrier, List.of("ownerfacades"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        return proof.contract().orElseThrow();
    }

    static void projection() throws Exception {
        var admission = admission();
        var generation = BridgeGeneration.createOwnedCallbacks("owners.jar", admission, "test", "1".repeat(64), "2".repeat(64));
        var projected = BridgeOwnedCallbackJavaSources.generate(admission, generation);
        var call = projected.calls().stream().filter(value -> value.binding().method().name().equals("fire")).findFirst().orElseThrow();
        check(call.callback() && call.inputs().stream().map(BridgeOwnedCallbackJavaSources.Input::transport).toList().equals(List.of(
                BridgeOwnedCallbackJavaSources.Transport.LOCAL_OWNER, BridgeOwnedCallbackJavaSources.Transport.LOCAL_OWNER,
                BridgeOwnedCallbackJavaSources.Transport.FOREIGN_OWNER, BridgeOwnedCallbackJavaSources.Transport.VALUE)), "incorrect callback guard partition");
        check(call.inputs().stream().limit(3).allMatch(BridgeOwnedCallbackJavaSources.Input::ownerRecord), "missing callback record");
        var scalar = projected.calls().stream().filter(value -> value.binding().method().name().equals("value")).findFirst().orElseThrow();
        check(!scalar.callback() && scalar.inputs().stream().noneMatch(BridgeOwnedCallbackJavaSources.Input::ownerRecord)
                && scalar.binding().descriptor().equals("(J)J"), "scalar call acquired callback bookkeeping");
        var declarations = projected.declarations();
        var javaSources = new java.util.TreeMap<>(declarations.sources());
        javaSources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "owner-facades")), "11.0", "3".repeat(64))));
        Path base = Path.of("workspace/java-bridge/evidence/p5/owner-facades").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), classes = directory.resolve("classes");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/javac").toString(),
                "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var entry : javaSources.entrySet()) {
            Path file = directory.resolve("sources").resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); command.add(file.toString());
        }
        BridgeEntryTests.run(directory, command, "javac");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (var facade : projected.facades()) {
                Class<?> type = Class.forName(facade.binaryName(), false, loader);
                for (String field : List.of(facade.addressField(), facade.stateField())) {
                    int modifiers = type.getDeclaredField(field).getModifiers();
                    check(java.lang.reflect.Modifier.isPrivate(modifiers) && java.lang.reflect.Modifier.isFinal(modifiers), "mutable or exposed root metadata");
                }
                for (var method : type.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isNative(method.getModifiers())) check(java.lang.reflect.Modifier.isPrivate(method.getModifiers()), "exposed raw native adapter");
                }
            }
        }
        System.out.println("owner facade evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
