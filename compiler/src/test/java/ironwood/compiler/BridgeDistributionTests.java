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

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
