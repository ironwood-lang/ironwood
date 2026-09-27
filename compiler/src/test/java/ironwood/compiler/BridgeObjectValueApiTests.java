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
        var conversions = BridgeEnumConversions.forSurface(artifact, surface);
        check(conversions.matches(artifact.program().orElseThrow(), surface.roots()) && conversions.enumTypes().size() == 3,
                "mixed enum conversion metadata lost exact binding or used-type closure");
        check(conversions.results().size() == 5 && !conversions.initializers().isEmpty(), "enum results or initialization roots lost");
        var constructor = surface.roots().roots().stream().filter(root -> root.callable().kind() == IrCallableKind.CONSTRUCTOR)
                .findFirst().orElseThrow().callable();
        check(conversions.parameters().get(constructor).size() == 1
                && conversions.parameters().get(constructor).getFirst().input() == 1,
                "constructor enum argument confused with the object receiver");
        var emptyMapping = BridgeEnumConstants.discover(artifact, java.util.Set.of());
        denied(() -> BridgeEnumConversions.prove(artifact, surface.roots(), emptyMapping, List.of()));
        var mapping = BridgeEnumConstants.discover(artifact, surface.types().stream()
                .filter(type -> type.kind() == ironwood.compiler.semantic.BridgeApiFacts.Kind.ENUM)
                .map(type -> IrType.reference(type.binaryName())).collect(java.util.stream.Collectors.toSet()));
        denied(() -> BridgeEnumConversions.prove(artifact, surface.roots(), mapping, List.of()));
        var onlyConstructor = BridgeRootSet.resolve(artifact.program().orElseThrow(), List.of(constructor));
        denied(() -> BridgeEnumConversions.forSurface(artifact, new BridgeExportSurface(surface.types(), onlyConstructor)));
        var side = surface.types().stream().filter(type -> type.binaryName().endsWith("$Side")).findFirst().orElseThrow();
        var code = side.callables().stream().filter(method -> method.name().equals("code")).findFirst().orElseThrow();
        var dispatch = BridgeEnumDispatch.prove(artifact, IrType.reference(side.binaryName()), code, mapping);
        denied(() -> BridgeEnumConversions.prove(artifact, onlyConstructor, mapping, List.of(dispatch)));
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
        for (String pureJava : List.of("public enum Only { SECOND, FIRST; }", "public enum Only { ; }",
                "public final class Only { private Only() {} public static final int COUNT = 2; }")) {
            var input = SourceFile.of("Only.iron", "package objectvalues; " + pureJava);
            var javaArtifact = analyze(List.of(input));
            var javaSelection = select(javaArtifact);
            check(javaSelection.surface().isPresent(), javaSelection.diagnostics().toString());
            var javaSurface = javaSelection.surface().orElseThrow();
            var javaConversions = BridgeEnumConversions.forSurface(javaArtifact, javaSurface);
            if (pureJava.contains("enum")) {
                check(javaSurface.roots().roots().stream().map(root -> root.callable().name()).sorted().toList()
                        .equals(List.of("valueAt", "valueCount")) && javaConversions.enumTypes().size() == 1,
                        "Ironwood enum traversal helpers lost native semantics");
            } else check(javaSurface.roots().roots().isEmpty() && javaConversions.initializers().isEmpty()
                    && javaConversions.enumTypes().isEmpty(), "constant-only API acquired native conversion roots");
            parity(input, javaSelection);
        }

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
        check(!conversions.matches(boundary.program().orElseThrow(), surface.roots()), "stale conversion inventory accepted");
        denied(() -> BridgeEnumConversions.forSurface(boundary, surface));
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
                var artifact = analyze(loaded.sources());
                var actual = select(artifact);
                check(actual.surface().isPresent() == expected.surface().isPresent() && shape(actual).equals(shape(expected)),
                        "mixed API closure changed after reconstruction: " + input);
                if (actual.surface().isPresent()) {
                    var original = analyze(List.of(source));
                    check(conversionShape(BridgeEnumConversions.forSurface(original, select(original).surface().orElseThrow()))
                            .equals(conversionShape(BridgeEnumConversions.forSurface(artifact, actual.surface().orElseThrow()))),
                            "enum conversion inventory changed after reconstruction: " + input);
                }
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

    private static java.util.Map<String, String> conversionShape(BridgeEnumConversions conversions) {
        var result = new java.util.TreeMap<String, String>();
        conversions.parameters().forEach((id, parameters) -> result.put("input:" + id, parameters.toString()));
        conversions.results().forEach((id, mapping) -> result.put("result:" + id, mapping.toString()));
        result.put("initializers", conversions.initializers().toString());
        return result;
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("incomplete enum conversion inventory admitted");
    }
}
