// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeDistributionTests {
    static final String NAME = "Java Bridge packages exact corresponding library source and required notices";
    private BridgeDistributionTests() {}

    static void inputs() throws Exception {
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var source = SourceFile.of("Engine.iron", """
                package distribution;
                public final class Engine {
                    private Engine() {}
                    public static long value(String input) {
                        ironwood.time.Instant instant = ironwood.time.Instant.parse(input);
                        long result = instant.getEpochSecond();
                        free instant;
                        return result;
                    }
                }
                """);
        var artifact = compiler.analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        var actual = BridgeDistributionInputs.discover(artifact);
        var entries = actual.entries();
        check(entries.containsKey("META-INF/ironwood/licenses/LICENSES/Classpath-exception-2.0.txt")
                && entries.containsKey("META-INF/ironwood/licenses/SOURCE_PROVENANCE.md"), "missing distribution notices");
        check(entries.keySet().stream().noneMatch(name -> name.contains("distribution/Engine")), "application implementation was packaged as library source");
        int derived = 0;
        var library = StandardLibrary.discover();
        for (var type : artifact.bridgeApiFacts().orElseThrow().types().values()) {
            if (!library.isBundledSource(type.source())) continue;
            String path = "META-INF/ironwood/source/stdlib/" + type.packageName().replace('.', '/') + "/" + type.source().path().getFileName();
            check(java.util.Arrays.equals(entries.get(path), type.source().content().getBytes(StandardCharsets.UTF_8)), "library source differs from analyzed input: " + path);
            if (type.source().content().contains("SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0")) derived++;
        }
        check(derived > 0, "fixture did not reach Classpath-covered library source");
        byte[] text = entries.get("META-INF/ironwood/licenses/LICENSE-MIT");
        byte before = text[0]; text[0] ^= 1;
        check(actual.entries().get("META-INF/ironwood/licenses/LICENSE-MIT")[0] == before, "mutable packaged input bytes");
        Path directory = Files.createTempDirectory("bridge distribution inputs ");
        try {
            Path classes = directory.resolve("classes"), classFile = classes.resolve("distribution/Engine.ironclass");
            IronClass.write(classFile, SourceParser.parse(source).unit().orElseThrow(), "distribution.Engine");
            Path archive = directory.resolve("application.ironjar"); IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classFile, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("distribution"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = compiler.analyzeForBridge(loaded.sources());
                check(restored.valid(), restored.diagnostics().toString());
                check(BridgeDistributionInputs.discover(restored).hashes().equals(actual.hashes()), "artifact reconstruction changed corresponding source");
            }
            Path home = directory.resolve("home");
            for (var entry : actual.entries().entrySet()) {
                String name = entry.getKey(); Path destination;
                if (name.startsWith("META-INF/ironwood/licenses/")) destination = home.resolve(name.substring("META-INF/ironwood/licenses/".length()));
                else if (name.startsWith("META-INF/ironwood/source/runtime/")) destination = home.resolve("runtime").resolve(name.substring("META-INF/ironwood/source/runtime/".length()));
                else continue;
                Files.createDirectories(destination.getParent()); Files.write(destination, entry.getValue());
            }
            check(BridgeDistributionInputs.read(home, artifact, library).identity().equals(actual.identity()), "packaged-layout notices changed identity");
            Files.writeString(home.resolve("runtime/include/ironwood_bridge.h"), "changed source");
            check(!BridgeDistributionInputs.read(home, artifact, library).identity().equals(actual.identity()), "runtime source change omitted");
            Files.delete(home.resolve("LICENSES/Classpath-exception-2.0.txt"));
            try { BridgeDistributionInputs.read(home, artifact, library); throw new AssertionError("missing required notice accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("missing Java Bridge distribution input"), expected.toString()); }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void applicationInputs() throws Exception {
        Path directory = Files.createTempDirectory("bridge application notices ");
        try {
            Path first = directory.resolve("dependency/LICENSE"), second = directory.resolve("application/LICENSE");
            Files.createDirectories(first.getParent()); Files.createDirectories(second.getParent());
            Files.writeString(first, "dependency notice\n"); Files.writeString(second, "application notice\n");
            String body = "package notices; public final class Engine { private Engine() {} public static int value() { return 42; } "
                    + "public static final class Nested { private Nested() {} public static int value() { return 43; } } }";
            var source = SourceFile.of("Engine.iron", body);
            Path classFile = directory.resolve("classes/notices/Engine.ironclass");
            IronClass.write(classFile, SourceParser.parse(source).unit().orElseThrow(), "notices.Engine");
            Path archive = directory.resolve("dependency.ironjar"), unused = directory.resolve("unused.ironjar");
            IronJar.create(archive, List.of(classFile), List.of(first));
            Files.copy(archive, unused);
            var loaded = new SourceSetLoader(List.of(), List.of(archive)).loadBridge(List.of(), List.of("notices"));
            check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
            var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
            check(artifact.valid(), artifact.diagnostics().toString());
            var options = new BridgeDistributionInputs.Options(List.of(archive, unused), List.of(first, second, second));
            var inputs = BridgeDistributionInputs.discover(artifact, options);
            var entries = inputs.entries();
            String hash = ironwood.compiler.bridge.BridgeGeneration.bytesDigest(Files.readAllBytes(archive));
            String prefix = "META-INF/ironwood/licenses/dependencies/" + hash + "/";
            check(new String(entries.get(prefix + "licenses/LICENSE"), StandardCharsets.UTF_8).equals("dependency notice\n"), "archive notice changed");
            check(new String(entries.get(prefix + "archive.name"), StandardCharsets.UTF_8).equals("dependency.ironjar"), "unused archive inventory included");
            check(entries.keySet().stream().filter(name -> name.startsWith("META-INF/ironwood/licenses/application/")).count() == 2,
                    "same-name application notices were overwritten or repeated");
            check(entries.keySet().stream().noneMatch(name -> name.contains("source/stdlib/notices")), "application source misclassified");
            Files.writeString(second, "changed application notice\n");
            check(!inputs.identity().equals(BridgeDistributionInputs.discover(artifact, options).identity()), "notice changed without identity change");
            Files.delete(second);
            try { BridgeDistributionInputs.discover(artifact, options); throw new AssertionError("missing application notice accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("missing Java Bridge application notice"), expected.toString()); }
            Files.writeString(second, "application notice\n");
            Files.writeString(first, "new dependency notice\n");
            IronJar.create(archive, List.of(classFile), List.of(first));
            check(!inputs.identity().equals(BridgeDistributionInputs.discover(artifact, options).identity()), "archive notice changed without identity change");
            IronClass.write(classFile, SourceParser.parse(SourceFile.of("Engine.iron", body.replace("return 42", "return 44"))).unit().orElseThrow(), "notices.Engine");
            IronJar.create(archive, List.of(classFile), List.of(first));
            try { BridgeDistributionInputs.discover(artifact, options); throw new AssertionError("changed analyzed archive accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("archive source changed after analysis"), expected.toString()); }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
