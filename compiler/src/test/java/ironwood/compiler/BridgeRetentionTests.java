// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRetentionContract;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeRetentionTests {
    private static final String SOURCE = """
            package retentionfixture;
            final class Item { int number; }
            final class Holder {
                Item first;
                Item second;
                Holder child;
                Item[] storage;
                static Item hidden;
                void set(Item value) { first = value; }
                void clear() { first = null; }
                void replace(Item value, boolean enabled) { first = enabled ? value : null; }
                void two(Item value) { first = value; second = value; }
                void observe() { Item value = first; if (value != null) value.number++; }
                void viaHelper(Holder other, Item value) { helper(other, value); }
                static void helper(Holder holder, Item value) { holder.first = value; }
                void setThenFail(Item value) { first = value; throw null; }
                void copy(Holder other) { second = other.first; }
                void move(Holder other) { second = other.first; other.first = null; }
                static Item identity(Item value) { return value; }
                void copyHelper(Holder other) { second = identity(other.first); }
                void unknownBranch(Item value, boolean choose) { first = choose ? hidden : value; }
                void viaDynamic(Writer writer, Item value) { writer.put(this, value); }
                void childWrite(Item value) { child.first = value; }
                void publish() { hidden = first; }
                void arrayPublish() { storage[0] = first; }
                void recurse(Item value, int depth) {
                    if (depth == 0) first = value;
                    else recurse(value, depth - 1);
                }
            }
            interface Writer { void put(Holder holder, Item value); }
            final class GoodWriter implements Writer {
                @Override
                public void put(Holder holder, Item value) { holder.first = value; }
            }
            final class CopyWriter implements Writer {
                @Override
                public void put(Holder holder, Item value) { holder.second = holder.first; }
            }
            """;

    private BridgeRetentionTests() {}

    static void attribution() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            IrProgram program = program(mode);
            var set = proof(program, "set");
            check(set.status() == BridgeProof.Status.PROVED, set.reason());
            var slot = set.contract().orElseThrow().slots().getFirst();
            check(slot.holderInput() == 0 && slot.valueInputs().equals(Set.of(1)) && !slot.mayClear(),
                    "receiver/argument root attribution");
            var clear = proved(program, "clear").slots().getFirst();
            check(clear.holderInput() == 0 && clear.valueInputs().isEmpty() && clear.mayClear(), "clear attribution");
            var replace = proved(program, "replace").slots().getFirst();
            check(replace.valueInputs().equals(Set.of(1)) && replace.mayClear(), "conditional union");
            var two = proved(program, "two");
            check(two.slots().size() == 2 && two.slots().stream().allMatch(value -> value.valueInputs().equals(Set.of(1))),
                    "distinct fields retaining the same input must remain distinct");
            check(proved(program, "observe").slots().isEmpty(), "transient slot loads do not retain");
            var helper = proved(program, "viaHelper").slots().getFirst();
            check(helper.holderInput() == 1 && helper.valueInputs().equals(Set.of(2)), "helper argument substitution");
            check(helper.sites().getFirst().callable().contains("helper"), "callee source attribution lost");
            var failure = proved(program, "setThenFail").slots().getFirst();
            check(failure.holderInput() == 0 && failure.valueInputs().equals(Set.of(1)), "exceptional store lost");
        }
    }

    static void rejections() {
        IrProgram program = program(UnfreedMode.OFF);
        for (String name : List.of("copy", "move", "copyHelper", "childWrite", "publish", "arrayPublish", "viaDynamic")) {
            var proof = proof(program, name);
            check(proof.status() == BridgeProof.Status.REJECTED && proof.contract().isEmpty(), name + ": " + proof);
            check(proof.reason().contains("SourceSpan"), "missing source evidence: " + proof.reason());
        }
        check(proof(program, "recurse").status() == BridgeProof.Status.UNKNOWN, "recursive effect guessed safe");
        check(proof(program, "unknownBranch").status() == BridgeProof.Status.UNKNOWN, "unknown phi arm disappeared");
        IrProgram missingHelper = new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(), program.dispatchSlots(),
                program.functions().stream().filter(function -> !function.sourceName().equals("helper")).toList(),
                program.entryPoint(), program.allocationFailure());
        var unknown = proof(missingHelper, "viaHelper");
        check(unknown.status() == BridgeProof.Status.UNKNOWN && unknown.contract().isEmpty(), "missing target guessed safe");
        check(unknown.reason().contains("unresolved call"), unknown.reason());
    }

    static void artifacts() throws Exception {
        Path temporary = Files.createTempDirectory("bridge retention artifacts ");
        try {
            Path source = temporary.resolve("Retention.iron");
            Files.writeString(source, SOURCE);
            Path classes = temporary.resolve("classes");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Path archive = temporary.resolve("retention.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            IrProgram original = program(UnfreedMode.OFF);
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("retentionfixture/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(temporary.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("retentionfixture.Holder"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                for (String name : List.of("set", "clear", "replace", "observe", "viaHelper", "setThenFail",
                        "copy", "move", "copyHelper", "childWrite", "publish", "arrayPublish", "unknownBranch", "viaDynamic")) {
                    check(proof(original, name).equals(proof(artifact.program().orElseThrow(), name)),
                            "retention proof differs after reconstruction: " + container + ": " + name);
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static BridgeRetentionContract proved(IrProgram program, String name) {
        var proof = proof(program, name);
        check(proof.status() == BridgeProof.Status.PROVED, name + ": " + proof.reason());
        return proof.contract().orElseThrow();
    }

    private static BridgeProof<BridgeRetentionContract> proof(IrProgram program, String name) {
        var callable = BridgeCallableId.of(program.functions().stream()
                .filter(function -> function.ownerClass().equals("retentionfixture.Holder")
                        && function.sourceName().equals(name)).findFirst().orElseThrow());
        Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> result = BridgeRetentionAnalyzer.analyze(
                program, BridgeRootSet.resolve(program, List.of(callable)));
        return result.get(callable);
    }

    private static IrProgram program(UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyze(List.of(SourceFile.of("test/Retention.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact.program().orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
