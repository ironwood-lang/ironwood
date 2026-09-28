// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeSynchronousCallbackTests {
    static final String NAME = "Java Bridge synchronous callback entries require complete borrowed native-state proofs";
    private static final String HEADER = "package synchronous; public interface Listener { long call(long value); }\n";
    private static final String SAFE = HEADER + """
            final class Driver {
                static long run(Listener listener, long count) {
                    long sum = 0L;
                    for (long i = 0L; i < count; i++) sum += helper(listener, i);
                    return sum;
                }
                private static long helper(Listener listener, long value) {
                    try { return listener.call(value); }
                    catch (RuntimeException failure) { return -1L; }
                }
            }
            """;
    private static final String STRINGS = SAFE.replace("long count)", "long count, String text)")
            .replace("long sum = 0L;", "long sum = text == null || text.length() == 0 ? 0L : (long)text.charAt(0);");
    private BridgeSynchronousCallbackTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            accepted(List.of(SourceFile.of("Listener.iron", SAFE)), mode);
            accepted(List.of(SourceFile.of("Listener.iron", STRINGS)), mode);
            for (String body : List.of(
                    "static Listener saved; static long run(Listener l, long n) { saved = l; return l.call(n); }",
                    "static long value; static long run(Listener l, long n) { return value + l.call(n); }",
                    "static long value = 9L; static long run(Listener l, long n) { return l.call(n); }",
                    "static class Local implements Listener { static long value; @Override public long call(long n) { return value; } } static long run(Listener l, long n) { return l.call(n); }",
                    "static long value; static long run(Listener l, long n) { value = n; return l.call(n); }",
                    "long value; long run(Listener l, long n) { return value + l.call(n); }",
                    "static Listener run(Listener l, long n) { l.call(n); return l; }",
                    "static long run(Listener l, long n) { Driver d = new Driver(); free d; return l.call(n); }",
                    "static long run(Listener l, long n) { try { return l.call(n); } finally { l.call(n); } }",
                    "static long run(Listener l, long n) { try { return l.call(n); } catch (RuntimeException f) { f.initCause(null); throw f; } }",
                    "static long run(Listener l, long n) { return nativeOnly(n); } private static long nativeOnly(long n) { return n; }")) {
                var sources = List.of(SourceFile.of("Listener.iron", HEADER + "final class Driver { " + body + " }"));
                var pipeline = new CompilerPipeline(mode);
                var original = pipeline.analyzeForBridge(sources);
                check(original.valid(), original.diagnostics().toString());
                var proxies = BridgeListenerProxies.discover(original, List.of("synchronous"));
                var artifact = pipeline.analyzeForBridge(sources, proxies);
                check(artifact.valid(), artifact.diagnostics().toString());
                try {
                    BridgeSynchronousCallbackEntries.create(artifact, proxies, roots(artifact));
                    throw new AssertionError("unsafe synchronous invocation admitted: " + body);
                } catch (IllegalArgumentException expected) { check(!expected.getMessage().isEmpty(), expected.toString()); }
            }
        }
        artifacts(SAFE);
        artifacts(STRINGS);
    }

    private static BridgeRootSet roots(CompilationArtifact artifact) {
        return BridgeRootSet.resolve(artifact.program().orElseThrow(), artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals("synchronous.Driver") && function.sourceName().equals("run"))
                .map(BridgeCallableId::of).toList());
    }

    private static List<IrFunction> accepted(List<SourceFile> sources, UnfreedMode mode) {
        var pipeline = new CompilerPipeline(mode);
        var original = pipeline.analyzeForBridge(sources);
        check(original.valid(), original.diagnostics().toString());
        var proxies = BridgeListenerProxies.discover(original, List.of("synchronous"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var roots = roots(artifact);
        var entries = BridgeSynchronousCallbackEntries.create(artifact, proxies, roots);
        check(entries.matches(artifact, roots) && !entries.matches(original, roots), "synchronous proof was not bound to final proxy program");
        var helper = BridgeRootSet.resolve(artifact.program().orElseThrow(), artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.sourceName().equals("helper")).map(BridgeCallableId::of).toList());
        check(!entries.matches(artifact, helper), "synchronous proof accepted changed invocation roots");
        check(entries.functions().size() == 1 && entries.proxies().matches(artifact), "missing protected invocation or proxy proof");
        check(BridgeExportSurface.objectValues(artifact, List.of("synchronous")).surface().isEmpty(), "component enabled unfinished producer");
        return entries.functions();
    }

    private static void artifacts(String sourceText) throws Exception {
        Path directory = Files.createTempDirectory("bridge synchronous proof ");
        try {
            Path source = directory.resolve("Listener.iron");
            Files.writeString(source, sourceText);
            var expected = accepted(List.of(SourceFile.read(source)), UnfreedMode.ERROR);
            Path classes = directory.resolve("classes"), archive = directory.resolve("listeners.ironjar");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString(StandardCharsets.UTF_8));
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Files.delete(source);
            for (var container : List.of(classes, classes.resolve("synchronous/Listener.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container)).loadBridge(List.of(), List.of("synchronous"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) check(accepted(loaded.sources(), mode).equals(expected),
                        "synchronous entry changed after reconstruction in " + mode);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
