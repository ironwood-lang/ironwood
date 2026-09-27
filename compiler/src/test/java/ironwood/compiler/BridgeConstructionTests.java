// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeConstructionTests {
    private static final String SOURCE = """
            package constructionfixture;
            final class Item { }
            final class Holder {
                private final int[] owned;
                private final Item borrowed;
                Holder(Item value) { owned = new int[2]; borrowed = value; }
                Item observe() { return borrowed; }
            }
            """;

    private BridgeConstructionTests() {}

    static void isolation() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String source : List.of(SOURCE,
                    "class Unsafe { static Unsafe saved; Unsafe() { saved = this; } }",
                    "class Unsafe { static void run() { Unsafe value = new Unsafe(); free value; free value; } }",
                    "class Missing { static void run() { Missing value = new Missing(); } }")) {
                var sources = List.of(SourceFile.of("test/Construction.iron", source));
                var pipeline = new CompilerPipeline(mode);
                var ordinary = pipeline.analyze(sources);
                var bridge = pipeline.analyzeForBridge(sources);
                check(ordinary.diagnostics().equals(bridge.diagnostics()), "bridge changed diagnostics: " + mode);
                check(ordinary.program().equals(bridge.program()), "bridge changed typed IR: " + mode);
                check(ordinary.bridgeConstructionFacts().isEmpty(), "ordinary analysis produced bridge facts");
                check(bridge.llvmIr().isEmpty(), "analysis-only entry emitted executable code");
                if (source.equals(SOURCE)) check(bridge.valid(), bridge.diagnostics().toString());
                if (source.startsWith("class Unsafe")) check(!bridge.valid(), "mandatory safety error was disabled");
                if (!bridge.valid()) {
                    check(bridge.bridgeConstructionFacts().isEmpty(), "invalid program acquired construction facts");
                } else if (source.equals(SOURCE)) {
                    var facts = bridge.bridgeConstructionFacts().orElseThrow();
                    var proof = facts.constructors().entrySet().stream()
                            .filter(entry -> entry.getKey().owner().equals("constructionfixture.Holder"))
                            .findFirst().orElseThrow().getValue();
                    check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
                    var contract = proof.contract().orElseThrow();
                    check(contract.ownedStorageFields().stream().map(field -> field.name()).toList().equals(List.of("owned")),
                            "borrowed storage classified as fresh owned storage: " + contract);
                    check(contract.ownedElementFields().isEmpty(), "primitive array acquired element destruction");
                    IrProgram program = bridge.program().orElseThrow();
                    check(facts.matches(program), "facts not bound to result IR");
                    var changed = new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                            program.typeInitializations(), program.arrayTypes(), program.stringConstants(),
                            program.dispatchSlots(), program.functions().subList(1, program.functions().size()),
                            program.entryPoint(), program.allocationFailure());
                    check(!facts.matches(changed), "stale facts survived a changed cleanup closure");
                }
            }
        }
    }

    static void artifacts() throws Exception {
        Path temporary = Files.createTempDirectory("bridge construction artifacts ");
        try {
            Path source = temporary.resolve("Holder.iron");
            Files.writeString(source, SOURCE);
            var expected = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)))
                    .bridgeConstructionFacts().orElseThrow().constructors();
            Path classes = temporary.resolve("classes");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Path archive = temporary.resolve("construction.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("constructionfixture/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(temporary.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("constructionfixture.Holder"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(expected.equals(artifact.bridgeConstructionFacts().orElseThrow().constructors()),
                        "construction facts differ after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
