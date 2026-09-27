// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgeApiTests {
    static final String NAME = "Java Bridge API projection preserves resolved inherited surface and safety";
    private static final String SOURCE = """
            package inventory;
            interface Contract {
                default long inherited(long input) { return input; }
            }
            class Parent {
                public int mutable;
                public static final int CONSTANT = 41 + 1;
                public static short inheritedStatic(short input) { return input; }
            }
            public final class Api extends Parent implements Contract {
                public Api() {}
                public static int value(int input) throws Exception { return input; }
                public static long value(long input) { return input; }
                public static <T> T generic(T input) { return input; }
                public Hidden hidden(Hidden input) { return input; }
                private static int privateMethod() { return 1; }
                public static final class Nested {
                    private Nested() {}
                    public static int value() { return 3; }
                }
                private static class Secret {
                    public static class PublicWithinPrivate {}
                }
            }
            class Hidden {}
            """;

    private BridgeApiTests() {}

    static void selection() {
        String utility = """
                package selected;
                public final class Utility {
                    private Utility() {}
                    public static final int CONSTANT = 42;
                    public static final String LABEL = "bridge";
                    public static int value(int input) throws Exception { return input; }
                    public static long value(long input) { return input; }
                    public static int length(String input) { return input == null ? -1 : input.length(); }
                    public static final class Nested {
                        private Nested() {}
                        public static boolean test() { return true; }
                    }
                }
                """;
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var source = SourceFile.of("Utility.iron", utility);
        var artifact = compiler.analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selection = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(artifact, List.of("selected"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        var surface = selection.surface().orElseThrow();
        check(surface.types().size() == 2 && surface.roots().roots().size() == 4, "public root union incomplete");
        var module = ironwood.compiler.bridge.BridgeEntryModule.copiedStrings(artifact, surface.roots());
        check(module.entries().size() == 4, "selected roots did not reuse P0 string/scalar proofs");
        check(ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(compiler.analyze(List.of(source)),
                List.of("selected")).surface().isEmpty(), "ordinary artifact supplied API authority");
        var changed = compiler.analyzeForBridge(List.of(SourceFile.of("Utility.iron", utility.replace("return true;", "return false;"))));
        var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                changed.bridgeConstructionFacts(), artifact.bridgeApiFacts());
        check(ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(stale, List.of("selected")).surface().isEmpty(),
                "stale API projection supplied authority");
        for (String member : List.of("public Utility(int value) {}", "public static int mutable;",
                "public static final int runtime = value(1);", "public int instance() { return 1; }",
                "public static int[] array(int[] input) { return input; }",
                "public static <T> T generic(T input) { return input; }",
                "public static String result() { return null; }")) {
            // The unimplemented String-result path must stay closed until its cleanup proof and transport exist.
            String candidate = utility.replace("private Utility() {}", "private Utility() {}\n" + member)
                    .replace("throws Exception", "");
            rejectSelection(compiler, List.of(SourceFile.of("Utility.iron", candidate)), List.of("selected"), "public");
        }
        var hidden = SourceFile.of("Utility.iron", """
                package selected;
                public final class Utility {
                    private Utility() {}
                    public static Hidden expose(Hidden input) { return input; }
                }
                class Hidden {}
                """);
        rejectSelection(compiler, List.of(hidden), List.of("selected"), "inaccessible signature type");
        var other = SourceFile.of("Other.iron", """
                package outside;
                public final class Other {
                    private Other() {}
                    public static int value() { return 1; }
                }
                """);
        var foreign = SourceFile.of("Utility.iron", """
                package selected;
                public final class Utility {
                    private Utility() {}
                    public static outside.Other expose(outside.Other input) { return input; }
                }
                """);
        rejectSelection(compiler, List.of(foreign, other), List.of("selected"), "add --export outside");
        rejectSelection(compiler, List.of(foreign, other), List.of("selected", "outside"), "outside the static scalar");
        rejectSelection(compiler, List.of(SourceFile.of("Api.iron", SOURCE)), List.of("inventory"), "Api.inherited(");
        var collision = SourceFile.of("Utility.iron", utility + "class _IronwoodBridgePackage {}");
        rejectSelection(compiler, List.of(collision), List.of("selected"), "reserved Java Bridge package marker");
        var custom = SourceFile.of("Failure.iron", """
                package failures;
                public class Failure extends Exception {}
                """);
        var throwsCustom = SourceFile.of("Utility.iron", utility.replace("throws Exception", "throws failures.Failure"));
        rejectSelection(compiler, List.of(throwsCustom, custom), List.of("selected"), "add --export failures");
        rejectSelection(compiler, List.of(throwsCustom, custom), List.of("selected", "failures"), "custom exception");
        var retaining = compiler.analyzeForBridge(List.of(SourceFile.of("Utility.iron", utility
                .replace("private Utility() {}", "private Utility() {} private static String retained;")
                .replace("return input == null ? -1 : input.length();", "retained = input; return 1;"))));
        check(retaining.valid(), retaining.diagnostics().toString());
        var retainedSurface = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(retaining, List.of("selected"));
        check(retainedSurface.surface().isPresent(), "signature selection should not invent lifetime facts");
        try {
            ironwood.compiler.bridge.BridgeEntryModule.copiedStrings(retaining, retainedSurface.surface().orElseThrow().roots());
            throw new AssertionError("retaining String input passed executable admission");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("cleanup is not proved"), expected.getMessage());
        }
    }

    private static void rejectSelection(CompilerPipeline compiler, List<SourceFile> sources,
                                        List<String> packages, String message) {
        var artifact = compiler.analyzeForBridge(sources);
        check(artifact.valid(), "invalid negative fixture: " + artifact.diagnostics());
        var selection = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(artifact, packages);
        check(selection.surface().isEmpty() && selection.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.source() != null && diagnostic.message().contains(message)),
                "expected located API rejection containing '" + message + "': " + selection.diagnostics());
    }

    static void projection() throws Exception {
        var source = SourceFile.of("Api.iron", SOURCE);
        var pipeline = new CompilerPipeline(UnfreedMode.OFF);
        var artifact = pipeline.analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        var facts = artifact.bridgeApiFacts().orElseThrow();
        check(facts.matches(artifact.program().orElseThrow()), "API facts not bound to final program");
        var api = facts.types().get("inventory.Api");
        check(api.accessible() && api.finalType() && !api.abstractType() && !api.generic(), "class modifiers lost");
        check(api.callables().stream().filter(method -> method.name().equals("value")).count() == 2, "overloads collapsed");
        var checked = api.callables().stream().filter(method -> method.name().equals("value")
                && method.result().equals(IrType.I32)).findFirst().orElseThrow();
        check(checked.thrownTypes().equals(List.of(IrType.reference("ironwood.lang.Exception"))), "throws lost");
        check(checked.target().isPresent() && checked.isStatic() && checked.parameterNames().equals(List.of("input")),
                "resolved target or source parameter lost");
        check(api.callables().stream().anyMatch(method -> method.kind() == IrCallableKind.CONSTRUCTOR), "constructor lost");
        check(api.callables().stream().anyMatch(method -> method.owner().equals("inventory.Contract")
                && method.name().equals("inherited") && method.target().isPresent()), "default method lost");
        check(api.callables().stream().anyMatch(method -> method.owner().equals("inventory.Parent")
                && method.name().equals("inheritedStatic") && method.isStatic()), "inherited static method lost");
        check(api.callables().stream().anyMatch(method -> method.name().equals("generic") && method.generic()),
                "unsupported generic method silently omitted");
        check(api.callables().stream().noneMatch(method -> method.name().equals("privateMethod")), "private method exposed");
        check(api.fields().stream().anyMatch(field -> field.name().equals("mutable") && !field.isFinal()
                && !field.isStatic() && field.constant().isEmpty()), "unsupported inherited field silently omitted");
        check(api.fields().stream().anyMatch(field -> field.name().equals("CONSTANT")
                && field.constant().orElse(null) instanceof IrConstant value && value.value().intValue() == 42),
                "resolved compile-time constant lost");
        check(!facts.types().get("inventory.Hidden").accessible(), "package-private signature type is accessible");
        check(!facts.types().get("inventory.Api$Secret$PublicWithinPrivate").accessible(), "private enclosing type ignored");
        var nested = facts.types().get("inventory.Api$Nested");
        check(nested.accessible() && nested.staticMember() && nested.sourceName().equals("inventory.Api.Nested")
                && nested.enclosingType().equals(java.util.Optional.of("inventory.Api")), "nested identities lost");
        check(facts.types().get("ironwood.lang.Exception").throwable(), "throwable hierarchy lost");
        try {
            facts.types().clear();
            throw new AssertionError("mutable API map");
        } catch (UnsupportedOperationException expected) {
            // Callers cannot turn this inventory into an edited admission authority.
        }
        var changed = pipeline.analyzeForBridge(List.of(SourceFile.of("Api.iron", SOURCE.replace("return 3;", "return 4;"))));
        check(changed.valid() && !facts.matches(changed.program().orElseThrow()), "changed implementation matched stale API facts");
        for (var mode : UnfreedMode.values()) {
            var compiler = new CompilerPipeline(mode);
            var ordinary = compiler.analyze(List.of(source));
            var bridge = compiler.analyzeForBridge(List.of(source));
            check(ordinary.program().equals(bridge.program()) && ordinary.diagnostics().equals(bridge.diagnostics()),
                    "API projection changed ordinary semantics in " + mode);
            check(ordinary.bridgeApiFacts().isEmpty(), "ordinary compilation collected bridge API");
            var unsafe = SourceFile.of("Unsafe.iron", """
                    public final class Unsafe {
                        public static int bad() {
                            Unsafe value = new Unsafe();
                            free value;
                            return value.read();
                        }
                        int read() { return 1; }
                    }
                    """);
            var rejected = compiler.analyzeForBridge(List.of(unsafe));
            check(!rejected.valid() && rejected.bridgeApiFacts().isEmpty(), "unsafe source produced API facts in " + mode);
        }
        Path directory = Files.createTempDirectory("bridge API inventory ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : ironwood.compiler.ast.DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("inventory.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classes.resolve("inventory/Api.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("inventory"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = pipeline.analyzeForBridge(loaded.sources());
                check(restored.valid(), restored.diagnostics().toString());
                check(shape(facts).equals(shape(restored.bridgeApiFacts().orElseThrow())), "API projection differs for " + input);
                var expectedErrors = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(artifact, List.of("inventory"))
                        .diagnostics().stream().map(diagnostic -> diagnostic.message()).toList();
                var actualErrors = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(restored, List.of("inventory"))
                        .diagnostics().stream().map(diagnostic -> diagnostic.message()).toList();
                check(expectedErrors.equals(actualErrors), "API rejection differs after reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, String> shape(BridgeApiFacts facts) {
        var result = new TreeMap<String, String>();
        facts.types().values().stream().filter(type -> type.packageName().equals("inventory")).forEach(type -> {
            result.put(type.binaryName(), type.sourceName() + type.kind() + type.accessible() + type.supertypes());
            type.callables().forEach(method -> result.put(type.binaryName() + ":" + method.name() + method.parameters(),
                    method.owner() + method.kind() + method.isStatic() + method.generic() + method.result()
                            + method.thrownTypes() + method.target()));
            type.fields().forEach(field -> result.put(type.binaryName() + ":field:" + field.name(),
                    field.owner() + field.type() + field.isStatic() + field.isFinal() + field.constant() + field.ambiguous()));
        });
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
