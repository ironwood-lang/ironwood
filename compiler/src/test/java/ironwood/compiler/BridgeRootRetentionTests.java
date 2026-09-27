// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgeRootRetentionTests {
    private BridgeRootRetentionTests() {}

    static final String SOURCE = """
            package rootfixture;
            final class Item { int number; Item() {} }
            final class Holder {
                Item first;
                Item second;
                Holder child;
                Holder(Item item) { first = item; }
                void set(Item item) { first = item; }
                void two(Item item) { first = item; second = item; }
                void clear() { first = null; second = null; }
                void setThenFail(Item item) { first = item; throw null; }
                static void helper(Holder holder, Item item) { holder.first = item; }
                void other(Holder holder, Item item) { helper(holder, item); }
                void childSlot(Item item) { child.first = item; }
                void copy(Holder holder) { second = holder.first; }
                Holder alias() { return this; }
            }
            """;
    private static final Set<String> METHODS = Set.of("set", "two", "clear", "setThenFail", "other");

    static CompilationArtifact artifact(String source, UnfreedMode mode) {
        var result = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("test/RootRetention.iron", source)));
        check(result.valid(), result.diagnostics().toString());
        return result;
    }

    static BridgeRootSet roots(CompilationArtifact artifact, Set<String> methods, Set<String> constructors) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().startsWith("rootfixture.") &&
                        (function.kind() == IrCallableKind.CONSTRUCTOR && constructors.contains(function.ownerClass())
                        || function.kind() == IrCallableKind.METHOD && methods.contains(function.sourceName())))
                .map(BridgeCallableId::of).toList());
    }

    static void proofs() throws Exception {
        var types = Set.of("rootfixture.Item", "rootfixture.Holder");
        for (var mode : UnfreedMode.values()) {
            var artifact = artifact(SOURCE, mode);
            var roots = roots(artifact, METHODS, types);
            var proof = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
            check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
            var contract = proof.contract().orElseThrow();
            var holder = IrType.reference("rootfixture.Holder");
            var item = IrType.reference("rootfixture.Item");
            check(contract.constructedRootTypes().equals(Set.of(holder, item)), "wrong origin types");
            check(contract.dependencies().get(holder).equals(Set.of(item)) && contract.dependencies().get(item).isEmpty(),
                    "constructor/method dependency graph lost");
            check(contract.rootSlots().get(holder).stream().map(IrField::name).toList().equals(List.of("first", "second")),
                    "persistent slot layout missing");
            check(contract.matches(artifact.program().orElseThrow(), roots), "unbound contract");
            check(!contract.matches(artifact.program().orElseThrow(), roots(artifact, Set.of("clear"), types)),
                    "changed export surface reused origin proof");
            for (String name : List.of("childSlot", "copy", "alias")) {
                var denied = BridgeRootRetentionAnalyzer.analyze(artifact, roots(artifact, Set.of(name), types));
                check(denied.status() != BridgeProof.Status.PROVED && denied.contract().isEmpty(), name + " admitted: " + denied);
            }
            var missing = BridgeRootRetentionAnalyzer.analyze(artifact, roots(artifact, METHODS, Set.of("rootfixture.Holder")));
            check(missing.status() == BridgeProof.Status.REJECTED, "unconstructed input accepted");
            var unsafeSources = List.of(SourceFile.of("test/UnsafeRoot.iron", SOURCE.replace(
                    "Holder alias()", "static void consume(Item item) { free item; } Holder alias()")));
            var ordinary = new CompilerPipeline(mode).analyze(unsafeSources);
            var bridge = new CompilerPipeline(mode).analyzeForBridge(unsafeSources);
            check(!ordinary.valid() && ordinary.diagnostics().equals(bridge.diagnostics()),
                    "bridge changed mandatory unsafe argument free diagnostics");
        }
        var cyclic = artifact(SOURCE.replace("int number; Item() {}",
                "int number; Holder holder; Item() {} void retain(Holder holder) { this.holder = holder; }"), UnfreedMode.OFF);
        var methods = new java.util.HashSet<>(METHODS);
        for (String declaration : List.of("abstract class Forbidden { Forbidden() {} }", "enum Forbidden { ONE; }")) {
            var forbidden = artifact("package rootfixture; " + declaration, UnfreedMode.OFF);
            var rejected = BridgeRootRetentionAnalyzer.analyze(forbidden,
                    roots(forbidden, Set.of(), Set.of("rootfixture.Forbidden")));
            check(rejected.status() == BridgeProof.Status.REJECTED && rejected.reason().contains("concrete non-enum"),
                    "non-constructible class gained a native allocation entry: " + rejected);
        }
        methods.add("retain");
        var denied = BridgeRootRetentionAnalyzer.analyze(cyclic, roots(cyclic, methods, types));
        check(denied.status() == BridgeProof.Status.REJECTED && denied.reason().contains("cycle"), denied.toString());
        var self = artifact(SOURCE.replace("void set(Item item)", "void self(Holder holder) { child = holder; } void set(Item item)"), UnfreedMode.OFF);
        denied = BridgeRootRetentionAnalyzer.analyze(self, roots(self, Set.of("self"), types));
        check(denied.status() == BridgeProof.Status.REJECTED && denied.reason().contains("cycle"), denied.toString());
        String allocatingSource = SOURCE.replace("Holder(Item item) { first = item; }",
                "private int[] storage; Holder(Item item, boolean fail) { first = item; storage = new int[2]; if (fail) throw null; }");
        var allocating = artifact(allocatingSource, UnfreedMode.OFF);
        var allocatingRoots = roots(allocating, METHODS, types);
        var allocatingProof = BridgeRootRetentionAnalyzer.analyze(allocating, allocatingRoots);
        check(allocatingProof.status() == BridgeProof.Status.PROVED, allocatingProof.reason());
        check(allocatingProof.contract().orElseThrow().rootSlots().get(IrType.reference("rootfixture.Holder"))
                .stream().noneMatch(field -> field.name().equals("storage")), "private owned storage became an external dependency");
        var withoutFacts = ironwood.compiler.semantic.BridgeRetentionAnalyzer.analyze(
                allocating.program().orElseThrow(), allocatingRoots);
        check(withoutFacts.entrySet().stream().anyMatch(entry -> entry.getKey().kind() == IrCallableKind.CONSTRUCTOR
                && entry.getValue().status() != BridgeProof.Status.PROVED), "owned-field exemption did not require final facts");
        var changed = allocating.program().orElseThrow();
        var altered = new IrProgram(changed.moduleName() + "-changed", changed.classes(), changed.staticFields(),
                changed.typeInitializations(), changed.arrayTypes(), changed.stringConstants(), changed.dispatchSlots(),
                changed.functions(), changed.entryPoint(), changed.allocationFailure());
        try {
            ironwood.compiler.semantic.BridgeRetentionAnalyzer.analyze(altered, allocatingRoots,
                    allocating.bridgeConstructionFacts().orElseThrow());
            throw new AssertionError("stale owned-field facts admitted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("do not match"), expected.toString());
        }
        var published = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Published.iron",
                SOURCE.replace("Holder(Item item) { first = item; }",
                        "static Holder leaked; Holder(Item item) { first = item; leaked = this; }"))));
        check(!published.valid() && published.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("publish")),
                "ordinary construction publication proof was bypassed");
        denied = BridgeRootRetentionAnalyzer.analyze(published, allocatingRoots);
        check(denied.status() != BridgeProof.Status.PROVED, "published constructor was treated as unpublished");
        artifacts(allocatingSource, types);
    }

    private static void artifacts(String text, Set<String> types) throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("bridge root artifacts ");
        try {
            var source = directory.resolve("Roots.iron");
            java.nio.file.Files.writeString(source, text);
            var original = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            var expected = BridgeRootRetentionAnalyzer.analyze(original, roots(original, METHODS, types)).contract().orElseThrow();
            var expectedLowering = BridgeEntryModule.rootObjects(original, roots(original, METHODS, types));
            var classes = directory.resolve("classes");
            var output = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString());
            var archive = directory.resolve("roots.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0, output.toString());
            java.nio.file.Files.delete(source);
            for (var container : List.of(classes, classes.resolve("rootfixture/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("rootfixture.Holder", "rootfixture.Item"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var reconstructed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                var proof = BridgeRootRetentionAnalyzer.analyze(reconstructed, roots(reconstructed, METHODS, types));
                check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
                var actual = proof.contract().orElseThrow();
                check(expected.constructedRootTypes().equals(actual.constructedRootTypes())
                        && expected.entries().equals(actual.entries()) && expected.rootSlots().equals(actual.rootSlots())
                        && expected.dependencies().equals(actual.dependencies()), "root contract changed after reconstruction: " + container);
                var actualLowering = BridgeEntryModule.rootObjects(reconstructed, roots(reconstructed, METHODS, types));
                check(expectedLowering.entries().equals(actualLowering.entries())
                        && expectedLowering.destructions().stream().map(BridgeEntryModule.Destruction::function).toList()
                        .equals(actualLowering.destructions().stream().map(BridgeEntryModule.Destruction::function).toList()),
                        "protected root/destruction lowering changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
