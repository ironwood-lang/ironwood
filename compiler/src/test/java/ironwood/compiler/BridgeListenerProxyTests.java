// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeListenerProxies;
import ironwood.compiler.bridge.BridgeListenerProxyEntries;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeListenerProxyTests {
    static final String NAME = "Java Bridge listener proxies participate in mandatory source safety and artifact reconstruction";
    private static final String SOURCE = """
            package listenerfixture;
            public interface Listener { void onResult(long sequence, long value); }
            final class Processor {
                public Processor() {}
                public void process(Listener listener, long sequence, long value) { listener.onResult(sequence, value); }
            }
            """;

    private BridgeListenerProxyTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var sources = List.of(SourceFile.of("test/Listener.iron", SOURCE));
            var pipeline = new CompilerPipeline(mode);
            var original = pipeline.analyzeForBridge(sources);
            check(original.valid(), original.diagnostics().toString());
            var proxies = BridgeListenerProxies.discover(original, List.of("listenerfixture"));
            check(proxies.proxies().size() == 1, "listener selection");
            var artifact = pipeline.analyzeForBridge(sources, proxies);
            check(artifact.valid(), artifact.diagnostics().toString());
            var ownership = BridgeListenerProxyEntries.create(artifact, proxies);
            check(ownership.operations().size() == 1 && ownership.matches(artifact) && !ownership.matches(original),
                    "proxy ownership operations are not tied to their final artifact");
            var changed = new java.util.ArrayList<>(sources);
            changed.add(SourceFile.of("Extra.iron", "final class Extra {}"));
            changed.addAll(proxies.sources());
            var unbound = pipeline.analyzeForBridge(changed);
            check(unbound.valid(), unbound.diagnostics().toString());
            try {
                BridgeListenerProxyEntries.create(unbound, proxies);
                throw new AssertionError("changed source inventory acquired proxy ownership");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("inventory"), expected.toString());
            }
            var program = artifact.program().orElseThrow();
            var proxy = proxies.proxies().getFirst();
            var method = program.functions().stream().filter(function -> function.ownerClass().equals(proxy.binaryName())
                    && function.sourceName().equals("onResult")).findFirst().orElseThrow();
            check(method.blocks().getFirst().instructions().get(0) instanceof IrFieldLoadInstruction,
                    "Java handle hidden from typed field observers");
            check(method.blocks().getFirst().instructions().get(1) instanceof IrForeignCallInstruction,
                    "proxy source stub survived final lowering");
            var callback = (IrForeignCallInstruction) method.blocks().getFirst().instructions().get(1);
            check(callback.arguments().size() == 3 && callback.arguments().stream().allMatch(value -> value.type().equals(IrType.I64)),
                    "primitive callback exposes a native receiver reference");
            var process = program.functions().stream().filter(function -> function.sourceName().equals("process")).findFirst().orElseThrow();
            var plan = BridgeCallbackReachability.analyze(program);
            check(plan.entries(BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(process))))
                    .get(BridgeCallableId.of(process)).foreign(), "generated proxy missing from native dispatch closure");
            check(artifact.bridgeConstructionFacts().orElseThrow().matches(program), "facts do not match proxy-bearing program");
            check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(BridgeCallableId.of(method), 0),
                    "private proxy receiver was published without crossing the foreign call");
            check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(BridgeCallableId.of(process), 1),
                    "primitive-only interface dispatch falsely published its listener");
            check(BridgeExportSurface.objectValues(artifact, List.of("listenerfixture")).surface().isEmpty(),
                    "proxy synthesis alone enabled incomplete JNI export");
            check(!pipeline.analyzeForBridge(List.of(SourceFile.of("test/Listener.iron", SOURCE.replace("long value", "int value"))), proxies).valid(),
                    "stale source/proxy pairing accepted");
            String collision = SOURCE + "\nfinal class " + proxy.binaryName().substring(proxy.binaryName().lastIndexOf('.') + 1) + " {}";
            var collided = pipeline.analyzeForBridge(List.of(SourceFile.of("test/Listener.iron", collision)));
            check(collided.valid(), collided.diagnostics().toString());
            try {
                BridgeListenerProxies.discover(collided, List.of("listenerfixture"));
                throw new AssertionError("proxy name collision accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("collision"), expected.toString());
            }
            sourceSafety(mode);
        }
        artifacts();
    }

    private static void sourceSafety(UnfreedMode mode) {
        for (var source : List.of("""
                package listenerfixture;
                public interface Listener { void observe(Item value); }
                final class Item {}
                final class Local implements Listener { @Override public void observe(Item value) {} }
                final class Client {
                    static void run(Listener listener) { Item value = new Item(); listener.observe(value); free value; }
                }
                """)) {
            var sources = List.of(SourceFile.of("test/Listener.iron", source));
            var pipeline = new CompilerPipeline(mode);
            var nativeOnly = pipeline.analyzeForBridge(sources);
            check(nativeOnly.valid(), "native control failed: " + nativeOnly.diagnostics());
            var proxies = BridgeListenerProxies.discover(nativeOnly, List.of("listenerfixture"));
            var foreign = pipeline.analyzeForBridge(sources, proxies);
            check(!foreign.valid(), "foreign callback acquired native-only safety proof in " + mode);
            check(foreign.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("cannot free")),
                    "missing callback safety diagnostic: " + foreign.diagnostics());
        }
    }

    private static void artifacts() throws Exception {
        Path directory = Files.createTempDirectory("bridge listener proxies ");
        try {
            Path source = directory.resolve("Listener.iron");
            Files.writeString(source, SOURCE);
            var expected = proxyFunctions(List.of(SourceFile.read(source)));
            Path classes = directory.resolve("classes");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Path archive = directory.resolve("listeners.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("listenerfixture/Listener.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .loadBridge(List.of(), List.of("listenerfixture"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                check(proxyFunctions(loaded.sources()).equals(expected), "proxy IR differs after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static List<IrFunction> proxyFunctions(List<SourceFile> sources) {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var original = pipeline.analyzeForBridge(sources);
        check(original.valid(), original.diagnostics().toString());
        var proxies = BridgeListenerProxies.discover(original, List.of("listenerfixture"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var process = artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.sourceName().equals("process")).findFirst().orElseThrow();
        check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(BridgeCallableId.of(process), 1),
                "artifact reconstruction lost typed receiver confinement");
        var functions = new java.util.ArrayList<>(artifact.program().orElseThrow().functions().stream().filter(function ->
                proxies.proxies().stream().anyMatch(proxy -> proxy.binaryName().equals(function.ownerClass()))).toList());
        functions.addAll(BridgeListenerProxyEntries.create(artifact, proxies).functions());
        return List.copyOf(functions);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
