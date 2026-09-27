// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class BridgeExceptionClosureTests {
    private BridgeExceptionClosureTests() {}

    private static final String SOURCE = """
            package closure;
            class Hidden extends RuntimeException { Hidden() { super("hidden"); } }
            class Dead { static int fail() { throw new Hidden(); } }
            class Initialized { static int value = fail(); static int fail() { throw new Hidden(); } }
            class Engine {
                static int pure() { return 42; }
                static int parsed() { throw new ironwood.time.format.DateTimeParseException("detail", "text", 2); }
                static int wrapped() { throw new ironwood.io.UncheckedIOException("wrapped", new ironwood.io.IOException("cause")); }
                static int indirect() { return Dead.fail(); }
                static int initialization() { return Initialized.value; }
            }
            """;

    static void discovery() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var pure = module(artifact, "pure");
        var different = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", SOURCE.replace("return 42;", "return 43;"))));
        var mismatch = BridgeExceptionClosure.builtins(different, pure);
        check(mismatch.status() == BridgeProof.Status.UNKNOWN && mismatch.reason().contains("binding"), "stale entry module admitted: " + mismatch);
        for (var mode : UnfreedMode.values()) verify(List.of(SourceFile.of("Engine.iron", SOURCE)), mode);
        Path directory = Files.createTempDirectory("bridge-exception-closure-");
        try {
            Path source = directory.resolve("Engine.iron"); Files.writeString(source, SOURCE);
            var expected = verify(List.of(SourceFile.read(source)), UnfreedMode.OFF);
            var output = new ByteArrayOutputStream(); var print = new PrintStream(output, true, StandardCharsets.UTF_8);
            Path classes = directory.resolve("classes"), archive = directory.resolve("closure.ironjar");
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, print, print) == 0, output.toString());
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, print, print) == 0, output.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("closure/Engine.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("closure.Engine"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) check(expected.equals(verify(loaded.sources(), mode)), "exception closure artifact parity: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, Set<String>> verify(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String method : List.of("pure", "parsed", "wrapped", "indirect", "initialization")) {
            var proof = BridgeExceptionClosure.builtins(artifact, module(artifact, method));
            if (method.equals("indirect") || method.equals("initialization")) {
                check(proof.status() == BridgeProof.Status.REJECTED && proof.reason().contains("closure.Hidden"),
                        "reachable custom exception admitted: " + method + ": " + proof);
                result.put(method, Set.of(proof.reason()));
                continue;
            }
            check(proof.status() == BridgeProof.Status.PROVED, method + ": " + proof.reason());
            var snapshot = proof.contract().orElseThrow();
            var names = snapshot.projection().types().stream().map(BridgeExceptionProjection.Type::nativeName).collect(Collectors.toSet());
            check(names.containsAll(Set.of("ironwood.lang.Throwable", "ironwood.lang.Error", "ironwood.lang.OutOfMemoryError")),
                    "implicit allocation failure/hierarchy omitted: " + names);
            check(names.stream().noneMatch(name -> name.startsWith("closure.")), "unreachable custom type leaked into snapshot projection");
            if (method.equals("parsed")) check(names.contains("ironwood.time.format.DateTimeParseException"), "selected exception omitted");
            if (method.equals("wrapped")) check(names.containsAll(Set.of("ironwood.io.UncheckedIOException", "ironwood.io.IOException")), "cause type omitted");
            if (method.equals("pure")) check(!names.contains("ironwood.time.format.DateTimeParseException"), "unreachable exception retained");
            check(snapshot.entries().matches(snapshot.projection()), "closure lost exact entry binding");
            result.put(method, names);
        }
        return result;
    }

    private static BridgeEntryModule module(CompilationArtifact artifact, String method) {
        var program = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals("closure.Engine")
                && function.sourceName().equals(method)).map(BridgeCallableId::of).toList());
        return BridgeEntryModule.scalars(artifact, roots);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
