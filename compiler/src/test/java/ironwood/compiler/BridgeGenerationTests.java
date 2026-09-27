// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgeJavaTypes;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class BridgeGenerationTests {
    static final String NAME = "Java Bridge identities distinguish API program producer build and payload";
    private static final String COMPILER = "1".repeat(64);
    private static final String RUNTIME = "2".repeat(64);
    private static final String SOURCE = """
            package generation;
            public final class Engine {
                private Engine() {}
                public static final String LABEL = "bridge";
                public static int value(int input) { return helper(input); }
                private static int helper(int input) { return input + 7; }
            }
            """;

    private BridgeGenerationTests() {}

    static void producerInputs() throws Exception {
        var actual = BridgeProducerInputs.discover();
        check(actual.compilerIdentity().length() == 64 && actual.runtimeIdentity().length() == 64,
                "actual producer could not be inventoried");
        Path directory = Files.createTempDirectory("bridge producer inputs ");
        try {
            Path classes = directory.resolve("classes");
            Path version = classes.resolve("ironwood/compiler/VERSION");
            Path main = classes.resolve("ironwood/compiler/Main.class");
            Files.createDirectories(version.getParent());
            Files.writeString(version, "test\n");
            Files.write(main, new byte[]{1, 2, 3});
            Path runtime = directory.resolve("runtime");
            for (String name : List.of("src/ironwood_runtime.c", "include/ironwood_runtime.h", "include/ironwood_bridge.h")) {
                Path file = runtime.resolve(name);
                Files.createDirectories(file.getParent());
                Files.writeString(file, name);
            }
            var expected = BridgeProducerInputs.read("test", classes, runtime);
            for (int pass = 0; pass < 2; pass++) {
                Path archive = directory.resolve("compiler-" + pass + ".jar");
                try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
                    for (Path file : pass == 0 ? List.of(main, version) : List.of(version, main)) {
                        var entry = new java.util.zip.ZipEntry(classes.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
                        entry.setTime(1700000000000L + pass * 10000L);
                        zip.putNextEntry(entry);
                        zip.write(Files.readAllBytes(file));
                        zip.closeEntry();
                    }
                }
                check(BridgeProducerInputs.read("test", archive, runtime).equals(expected), "jar container changed producer identity");
            }
            Files.writeString(runtime.resolve("include/ironwood_bridge.h"), "changed private ABI");
            var changedRuntime = BridgeProducerInputs.read("test", classes, runtime);
            check(!changedRuntime.runtimeIdentity().equals(expected.runtimeIdentity())
                    && changedRuntime.compilerIdentity().equals(expected.compilerIdentity()), "runtime changes omitted or conflated");
            Files.write(main, new byte[]{1, 2, 4});
            check(!BridgeProducerInputs.read("test", classes, runtime).compilerIdentity().equals(expected.compilerIdentity()),
                    "compiler implementation changes omitted");
            Files.writeString(version, "different\n");
            try {
                BridgeProducerInputs.read("test", classes, runtime);
                throw new AssertionError("changed producer version admitted");
            } catch (java.io.IOException expectedFailure) {
                check(expectedFailure.getMessage().contains("version"), expectedFailure.getMessage());
            }
            Files.writeString(version, "test\n");
            Files.delete(runtime.resolve("include/ironwood_bridge.h"));
            try {
                BridgeProducerInputs.read("test", classes, runtime);
                throw new AssertionError("incomplete runtime admitted");
            } catch (java.io.IOException expectedFailure) {
                check(expectedFailure.getMessage().contains("missing Java Bridge runtime input"), expectedFailure.getMessage());
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void identities() throws Exception {
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var artifact = analyze(compiler, SOURCE);
        var surface = surface(artifact);
        var generation = BridgeGeneration.create("engine.jar", artifact, surface, "test", COMPILER, RUNTIME);
        check(generation.manifest().get("java.supported").equals("21,22,23"), "baseline changed");
        check(generation.supportPackage().endsWith(generation.identity()), "support namespace lost complete identity");
        var relocated = compiler.analyzeForBridge(List.of(SourceFile.of("elsewhere/Engine.iron", SOURCE)));
        check(generation.identity().equals(create(relocated).identity()), "source relocation changed generation");
        var implementation = create(analyze(compiler, SOURCE.replace("input + 7", "input + 8")));
        check(generation.apiIdentity().equals(implementation.apiIdentity()), "private implementation changed API identity");
        check(!generation.identity().equals(implementation.identity()), "private implementation reused generation");
        check(!generation.manifest().get("program").equals(implementation.manifest().get("program")), "private program omitted");
        var changedApi = create(analyze(compiler, SOURCE.replace("String LABEL", "String OTHER_LABEL")));
        check(!generation.apiIdentity().equals(changedApi.apiIdentity()), "public API change reused identity");
        var changedCompiler = BridgeGeneration.create("engine.jar", artifact, surface, "test", "3".repeat(64), RUNTIME);
        var changedRuntime = BridgeGeneration.create("engine.jar", artifact, surface, "test", COMPILER, "3".repeat(64));
        var changedProducer = BridgeGeneration.create("other.jar", artifact, surface, "test", COMPILER, RUNTIME);
        for (var changed : List.of(changedCompiler, changedRuntime, changedProducer)) {
            check(changed.apiIdentity().equals(generation.apiIdentity()) && !changed.identity().equals(generation.identity()),
                    "producer identity confused with API identity");
        }
        var firstSurrogate = create(analyze(compiler, SOURCE.replace("\"bridge\"", "\"\" + '\\uD800'")));
        var secondSurrogate = create(analyze(compiler, SOURCE.replace("\"bridge\"", "\"\" + '\\uD801'")));
        check(!firstSurrogate.apiIdentity().equals(secondSurrogate.apiIdentity()), "UTF-16 constants aliased through replacement");
        var options = Map.of("llvm", "23.1.0", "optimization", "O3", "abi", "arm64-64-little");
        var nativeBuild = generation.nativeBuild("macos-arm64", options);
        check(nativeBuild.equals(generation.nativeBuild("macos-arm64", new java.util.TreeMap<>(options))),
                "map iteration changed native build identity");
        check(!nativeBuild.identity().equals(generation.nativeBuild("linux-arm64", options).identity()), "targets aliased");
        check(!nativeBuild.identity().equals(generation.nativeBuild("macos-arm64", Map.of("optimization", "O0")).identity()),
                "build options omitted");
        check(!nativeBuild.identity().equals(implementation.nativeBuild("macos-arm64", options).identity()), "generation omitted from build");
        check(!BridgeGeneration.bytesDigest(new byte[]{1, 2}).equals(BridgeGeneration.bytesDigest(new byte[]{1, 3})),
                "payload digest did not cover final bytes");
        try {
            BridgeGeneration.create("engine.jar", analyze(compiler, SOURCE.replace("input + 7", "input + 9")), surface,
                    "test", COMPILER, RUNTIME);
            throw new AssertionError("stale surface admitted to generation");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("complete current"), expected.getMessage());
        }
        try {
            generation.manifest().clear();
            throw new AssertionError("mutable generation manifest");
        } catch (UnsupportedOperationException expected) {
            // Consumers cannot alter the identities after construction.
        }
        check(BridgeJavaTypes.descriptor(IrType.U16).equals("C") && BridgeJavaTypes.descriptor(IrType.I16).equals("S"),
                "char/short distinction lost");
        check(BridgeJavaTypes.descriptor(IrType.reference("generation.Engine$Nested")).equals("Lgeneration/Engine$Nested;"),
                "nested binary name changed");
        check(BridgeJavaTypes.sourceName(IrType.reference("ironwood.io.IOException")).equals("java.io.IOException"),
                "mapped exception spelling changed");
        Path directory = Files.createTempDirectory("bridge generation ");
        try {
            var unit = SourceParser.parse(SourceFile.of("Engine.iron", SOURCE)).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            Path classFile = classes.resolve("generation/Engine.ironclass");
            IronClass.write(classFile, unit, "generation.Engine");
            Path archive = directory.resolve("engine.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classFile, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("generation"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = compiler.analyzeForBridge(loaded.sources());
                check(restored.valid(), restored.diagnostics().toString());
                check(create(restored).manifest().equals(generation.manifest()), "artifact reconstruction changed generation: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(CompilerPipeline compiler, String source) {
        var artifact = compiler.analyzeForBridge(List.of(SourceFile.of("Engine.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeExportSurface surface(CompilationArtifact artifact) {
        var selection = BridgeExportSurface.scalarPreview(artifact, List.of("generation"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        return selection.surface().orElseThrow();
    }

    private static BridgeGeneration create(CompilationArtifact artifact) {
        return BridgeGeneration.create("engine.jar", artifact, surface(artifact), "test", COMPILER, RUNTIME);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
