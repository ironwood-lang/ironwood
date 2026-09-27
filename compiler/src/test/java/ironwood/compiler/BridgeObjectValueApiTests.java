// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeObjectValueApiTests {
    static final String NAME = "Java Bridge mixed object enum signatures preserve complete public closure";
    private static final String SOURCE = """
            package objectvalues;
            public final class Cell {
                private final Side side;
                public Cell(Side side) { this.side = side; }
                public Side side() { return side; }
                public int read(Side value, String text) { return value.code() + text.length(); }
                public static Cell identity(Cell value, Mode mode) { return value; }
                public enum Side {
                    SELL { @Override public int code() { return 29; }
                        @Override public String toString() { return "sell"; } }, BUY;
                    public int code() { return 11; }
                    public static int values(int value) { return value; }
                    public static Side valueOf(int value) { return value == 0 ? SELL : BUY; }
                    public Cell cell(Cell value) { return value; }
                }
                public enum Mode {
                    FIRST { @Override public int code() { return 1; } },
                    SECOND { @Override public int code() { return 2; } };
                    public abstract int code();
                }
                public enum Empty { ; public String text() { return "empty"; } }
            }
            """;

    private BridgeObjectValueApiTests() {}

    static void signatures() throws Exception {
        var source = SourceFile.of("Cell.iron", SOURCE);
        var artifact = analyze(List.of(source));
        var selected = select(artifact);
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        check(surface.types().size() == 4, "lost concrete or nested enum types");
        var ids = surface.roots().roots().stream().map(BridgeRootSet.Root::callable).toList();
        check(ids.stream().filter(id -> id.kind() == IrCallableKind.CONSTRUCTOR).count() == 1, "enum constructors became public roots");
        check(ids.stream().filter(id -> id.name().equals("code")).count() == 4, "abstract/constant-specific target union incomplete");
        check(ids.stream().filter(id -> id.name().equals("toString")).count() == 1
                && ids.stream().anyMatch(id -> id.name().equals("toString") && id.owner().contains("$Side$")),
                "source enum override lost or inherited identity became native");
        check(ids.stream().noneMatch(id -> List.of("name", "ordinal", "equals", "hashCode", "compareTo", "text").contains(id.name())),
                "Java-only or uninhabited enum body became a native root");
        check(ids.stream().filter(id -> id.name().equals("values") || id.name().equals("valueOf"))
                .allMatch(id -> id.parameters().equals(List.of(IrType.I32)))
                && ids.stream().filter(id -> id.name().equals("values") || id.name().equals("valueOf")).count() == 2,
                "generated enum operations confused with source overloads");
        check(BridgeExportSurface.valuePreview(artifact, List.of("objectvalues")).surface().isEmpty()
                && BridgeExportSurface.concreteObjects(artifact, List.of("objectvalues")).surface().isEmpty(),
                "mixed signature discovery expanded incomplete producer admission");
        try {
            BridgeGeneration.create("mixed", artifact, surface, "test", "a".repeat(64), "b".repeat(64));
            throw new AssertionError("unfinished object generation admitted");
        } catch (IllegalArgumentException expected) { /* Signature facts do not authorize generation. */ }
        parity(source, selected);

        for (String unsupported : List.of(
                SOURCE.replace("public String text() { return \"empty\"; }", "public Object text() { return null; }"),
                SOURCE.replace("public static int values(int value) { return value; }", "public static int[] values(int value) { return null; }"),
                SOURCE.replace("public Cell cell(Cell value) { return value; }", "public <T> T cell(T value) { return value; }"),
                SOURCE.replace("public Cell cell(Cell value) { return value; }", "public Hidden cell() { return null; }") + "class Hidden {}",
                SOURCE.replace("public enum Side {", "public enum Side implements Contract {") + "interface Contract {}",
                SOURCE.replace("public Side side() { return side; }", "public Side side() throws Trouble { return side; }")
                        + "class Trouble extends Exception {}",
                SOURCE.replace("public enum Empty { ;", "public enum Empty { ; public static final Side PUBLIC = Side.SELL;"))) {
            var input = SourceFile.of("Cell.iron", unsupported);
            var rejected = select(analyze(List.of(input)));
            check(rejected.surface().isEmpty() && !rejected.diagnostics().isEmpty(), "unsupported public member silently disappeared");
            parity(input, rejected);
        }
        var foreign = SourceFile.of("Foreign.iron", "package elsewhere; public enum Foreign { ITEM; }");
        var boundary = analyze(List.of(foreign, SourceFile.of("Cell.iron", SOURCE.replace(
                "public Side side() { return side; }", "public elsewhere.Foreign side() { return null; }"))));
        check(select(boundary).diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("add --export elsewhere")),
                "missing enum export package not diagnosed");
        check(BridgeExportSurface.objectValues(boundary, List.of("objectvalues", "elsewhere")).surface().isPresent(),
                "explicit package union did not complete enum closure");
        var stale = new CompilationArtifact(boundary.program(), boundary.llvmIr(), boundary.diagnostics(),
                boundary.bridgeConstructionFacts(), artifact.bridgeApiFacts());
        check(select(stale).surface().isEmpty(), "stale API facts accepted");
    }

    private static CompilationArtifact analyze(List<SourceFile> sources) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeExportSurface.Selection select(CompilationArtifact artifact) {
        return BridgeExportSurface.objectValues(artifact, List.of("objectvalues"));
    }

    private static List<String> shape(BridgeExportSurface.Selection selected) {
        return selected.surface().map(surface -> surface.roots().roots().stream().map(root -> root.callable().toString()).sorted().toList())
                .orElseGet(() -> selected.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList());
    }

    private static void parity(SourceFile source, BridgeExportSurface.Selection expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge object enum API ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("objects.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("objectvalues"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var actual = select(analyze(loaded.sources()));
                check(actual.surface().isPresent() == expected.surface().isPresent() && shape(actual).equals(shape(expected)),
                        "mixed API closure changed after reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
