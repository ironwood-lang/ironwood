// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class BridgeObjectApiTests {
    static final String NAME = "Java Bridge concrete object signatures preserve closure and reconstruction gates";
    private static final String SOURCE = """
            package objectapi;
            public final class Cell {
                private int value;
                public static final int CONSTANT = 42;
                public Cell(int value) { this.value = value; }
                public Cell(long value) { this.value = (int) value; }
                public int read() { return value; }
                public Cell self() { return this; }
                public static Cell identity(Cell value) { return value; }
                public String text(String value) { return value; }
                @Override public int hashCode() { return value; }
                @Override public String toString() { return "cell"; }
                public static final class Nested {
                    public Nested() {}
                    public boolean same(Cell first, Cell second) { return first == second; }
                }
            }
            """;

    private BridgeObjectApiTests() {}

    static void signatures() throws Exception {
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var source = SourceFile.of("Cell.iron", SOURCE);
        var artifact = compiler.analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selected = select(artifact);
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        check(surface.types().size() == 2 && surface.roots().roots().size() == 10,
                "constructor/instance/nested root union incomplete: " + surface.roots());
        check(surface.roots().roots().stream().filter(root -> root.callable().kind() == IrCallableKind.CONSTRUCTOR)
                .count() == 3, "constructor overloads lost");
        check(surface.roots().roots().stream().noneMatch(root -> root.callable().owner().equals("ironwood.lang.Object")),
                "inherited Java identity method became a native root");
        check(surface.roots().roots().stream().anyMatch(root -> root.callable().name().equals("hashCode"))
                && surface.roots().roots().stream().anyMatch(root -> root.callable().name().equals("toString")),
                "source overrides silently projected as Java identity methods");
        check(BridgeExportSurface.staticValues(artifact, List.of("objectapi")).surface().isEmpty(),
                "signature selection enabled incomplete public object production");
        parity(source, selected);

        for (String rejected : List.of(
                SOURCE.replace("final class Cell", "class Cell"),
                SOURCE.replace("static final class Nested", "final class Nested"),
                SOURCE.replace("private int value", "public int value"),
                SOURCE.replace("public Cell self() { return this; }", "public int[] self() { return null; }"),
                SOURCE.replace("public Cell self() { return this; }", "public <T> T self(T value) { return value; }"),
                SOURCE.replace("public Cell self() { return this; }", "@Override public boolean equals(Object value) { return this == value; }"),
                SOURCE.replace("public Cell self() { return this; }", "public Hidden self() { return null; }") + "class Hidden {}",
                SOURCE.replace("class Cell {", "class Cell extends Parent {") + "class Parent {}",
                SOURCE.replace("class Cell {", "class Cell implements Contract {")
                        + "interface Contract { default int inherited() { return 1; } }")) {
            var input = SourceFile.of("Cell.iron", rejected);
            var rejectedArtifact = compiler.analyzeForBridge(List.of(input));
            check(rejectedArtifact.valid(), rejectedArtifact.diagnostics().toString());
            var failed = select(rejectedArtifact);
            check(failed.surface().isEmpty() && !failed.diagnostics().isEmpty(), "unsupported object shape admitted");
            parity(input, failed);
        }

        var foreign = SourceFile.of("Other.iron", "package elsewhere; public final class Other { public Other() {} }");
        var boundary = compiler.analyzeForBridge(List.of(foreign, SourceFile.of("Cell.iron", SOURCE.replace(
                "public Cell self() { return this; }", "public elsewhere.Other self() { return null; }"))));
        var missing = select(boundary);
        check(missing.surface().isEmpty() && missing.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.message().contains("add --export elsewhere")), "missing package not diagnosed");
        check(BridgeExportSurface.concreteObjects(boundary, List.of("objectapi", "elsewhere")).surface().isPresent(),
                "explicit export union did not complete closure");
        var stale = new CompilationArtifact(boundary.program(), boundary.llvmIr(), boundary.diagnostics(),
                boundary.bridgeConstructionFacts(), artifact.bridgeApiFacts());
        check(select(stale).surface().isEmpty(), "stale final-program API facts admitted");
        check(select(compiler.analyze(List.of(source))).surface().isEmpty(), "missing bridge facts admitted");
        for (var mode : UnfreedMode.values()) {
            var unsafe = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Bad.iron", """
                    package objectapi;
                    public final class Bad {
                        public Bad() {}
                        public static int unsafe() {
                            Bad value = new Bad();
                            free value;
                            return value.read();
                        }
                        public int read() { return 1; }
                    }
                    """)));
            check(!unsafe.valid() && select(unsafe).surface().isEmpty(), "unsafe free admitted in " + mode);
        }
    }

    private static BridgeExportSurface.Selection select(CompilationArtifact artifact) {
        return BridgeExportSurface.concreteObjects(artifact, List.of("objectapi"));
    }

    private static void parity(SourceFile source, BridgeExportSurface.Selection expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge object API ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("objects.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("objectapi"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var actual = select(new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources()));
                check(actual.surface().isPresent() == expected.surface().isPresent(), "admission changed for " + input);
                check(actual.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList().equals(
                        expected.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList()),
                        "rejection changed for " + input);
                if (expected.surface().isPresent()) {
                    check(identities(expected).equals(identities(actual)), "resolved object roots changed for " + input);
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Optional<List<String>> identities(BridgeExportSurface.Selection selection) {
        return selection.surface().map(surface -> surface.roots().roots().stream()
                .map(root -> root.callable().toString()).sorted().toList());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
