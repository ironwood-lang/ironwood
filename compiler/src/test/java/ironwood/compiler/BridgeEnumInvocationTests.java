// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class BridgeEnumInvocationTests {
    static final String NAME = "Java Bridge enum invocation proves exact targets and copied value confinement";
    private static final String SOURCE = """
            package enuminvocation;
            public enum Mode {
                FIRST { @Override public int code() { return 11; } },
                SECOND { @Override public int code() { return 29; } };
                private static Mode saved;
                public abstract int code();
                public String alias(String input) { saved = this; return input; }
                public String copied(String input) { saved = this; return new String(input); }
                public static int select(Mode value, String input) {
                    saved = value; return (value == null ? -1 : value.code()) + (input == null ? -1 : input.length());
                }
                public static Mode result() { return saved; }
                public static boolean absent(Empty value) { return value == null; }
                public enum Empty { ; }
            }
            """;

    private BridgeEnumInvocationTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Mode.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var proof = prove(artifact);
            check(proof.entries().roots().size() == 6, "missing concrete enum entry bodies");
            check(proof.matches(artifact.program().orElseThrow(), proof.entries()), "unbound invocation proof");
            check(proof.stringResults().size() == 2, "String conversion contracts lost");
            proof.stringResults().forEach((id, result) -> check(result.kind() == (id.name().equals("alias")
                    ? BridgeStringResultContract.Kind.INPUT_ALIAS : BridgeStringResultContract.Kind.FRESH), "wrong String cleanup kind"));
            proof.parameters().forEach((id, parameters) -> {
                if (id.name().equals("code")) check(parameters.size() == 1 && !parameters.getFirst().nullable()
                        && parameters.getFirst().constants().size() == 1, "constant body accepts another receiver");
                if (id.name().equals("alias")) check(parameters.getFirst().constants().size() == 2 && !parameters.getFirst().nullable(),
                        "shared enum body lost receiver alternatives");
                if (id.name().equals("select")) check(parameters.getFirst().nullable() && parameters.getFirst().constants().size() == 2,
                        "ordinary enum argument lost nullable conversion");
                if (id.name().equals("absent")) check(parameters.getFirst().nullable() && parameters.getFirst().constants().isEmpty(),
                        "empty enum lost its null-only argument conversion");
            });
            check(proof.lifetime().rollbacks().isEmpty() && !proof.lifetime().references().containsKey(IrType.reference("ironwood.lang.String")),
                    "copied value gained enum lifetime or construction permission");
            if (mode == UnfreedMode.OFF) parity(source, shape(proof));
            for (var badSource : List.of(
                    SOURCE.replace("return 11;", "long ignored = System.nanoTime(); return 11;"),
                    SOURCE.replace("private static Mode saved;", "private static Mode saved; private static String retained;")
                            .replace("return input;", "retained = input; return input;"),
                    SOURCE.replace("return new String(input);", "throw new IllegalArgumentException(input);"))) {
                var badInput = SourceFile.of("Mode.iron", badSource);
                var bad = analyze(badInput, mode);
                denied(() -> prove(bad));
                if (mode == UnfreedMode.OFF) parity(badInput, outcome(bad));
            }
            var type = IrType.reference("enuminvocation.Mode");
            var facts = artifact.bridgeApiFacts().orElseThrow();
            var declaration = facts.types().get(type.referenceName());
            var constants = BridgeEnumConstants.discover(artifact, Set.of(type, IrType.reference("enuminvocation.Mode$Empty")));
            var result = declaration.callables().stream().filter(method -> method.name().equals("result")).findFirst().orElseThrow();
            denied(() -> BridgeEnumInvocation.prove(artifact, constants, List.of(), List.of(result.target().orElseThrow())));
            var code = declaration.callables().stream().filter(method -> method.name().equals("code")).findFirst().orElseThrow();
            var dispatch = BridgeEnumDispatch.prove(artifact, type, code, constants);
            var swapped = BridgeEnumConstants.prove(artifact, Map.of(type, Map.of("FIRST", 91, "SECOND", 17)));
            denied(() -> BridgeEnumInvocation.prove(artifact, swapped, List.of(dispatch), List.of()));
            var changed = analyze(SourceFile.of("Mode.iron", SOURCE.replace("return 11;", "return 12;")), mode);
            check(!proof.matches(changed.program().orElseThrow(), prove(changed).entries()), "stale program accepted");
            denied(() -> BridgeEnumInvocation.prove(changed, constants, List.of(dispatch), List.of()));
            var unsafeSource = List.of(SourceFile.of("Mode.iron", SOURCE.replace("saved = value;", "free value; saved = value;")));
            var unsafe = new CompilerPipeline(mode).analyzeForBridge(unsafeSource);
            var ordinary = new CompilerPipeline(mode).analyze(unsafeSource);
            check(!unsafe.valid() && unsafe.diagnostics().equals(ordinary.diagnostics()), "enum lifetime proof bypassed mandatory free safety");
            denied(() -> BridgeEnumInvocation.prove(unsafe, constants, List.of(dispatch), List.of()));
        }
    }

    private static BridgeEnumInvocation prove(CompilationArtifact artifact) {
        var type = IrType.reference("enuminvocation.Mode");
        var constants = BridgeEnumConstants.discover(artifact, Set.of(type, IrType.reference("enuminvocation.Mode$Empty")));
        var methods = artifact.bridgeApiFacts().orElseThrow().types().get(type.referenceName()).callables();
        var dispatches = methods.stream().filter(method -> Set.of("code", "alias", "copied", "name", "ordinal").contains(method.name()))
                .map(method -> BridgeEnumDispatch.prove(artifact, type, method, constants)).toList();
        var statics = methods.stream().filter(method -> Set.of("select", "absent").contains(method.name()))
                .map(method -> method.target().orElseThrow()).toList();
        return BridgeEnumInvocation.prove(artifact, constants, dispatches, statics);
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static Map<String, String> shape(BridgeEnumInvocation proof) {
        var result = new TreeMap<String, String>();
        proof.parameters().forEach((id, parameters) -> result.put(id.toString(), parameters.toString()));
        proof.stringResults().forEach((id, contract) -> result.put("String:" + id, contract.toString()));
        result.put("permanent", proof.lifetime().references().keySet().stream().map(IrType::displayName).sorted().toList().toString());
        result.put("closure", proof.lifetime().roots().roots().stream().map(root -> root.callable().toString()).toList().toString());
        return result;
    }

    private static Map<String, String> outcome(CompilationArtifact artifact) {
        try { return shape(prove(artifact)); }
        catch (IllegalArgumentException denied) { return Map.of("rejected", denied.getMessage()); }
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge enum invocation ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("enums.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("enuminvocation"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(outcome(artifact).equals(expected), "enum invocation changed after reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unproved enum invocation admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
