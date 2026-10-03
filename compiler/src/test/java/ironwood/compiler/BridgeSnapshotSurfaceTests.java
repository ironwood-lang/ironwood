// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeSnapshotSurfaceTests {
    static final String NAME = "Java Bridge snapshot export closure preserves declarations and final getter effects";
    private static final String API = """
            package snapshotapi;
            public final class Item {
                public Item() {}
                public int read() throws snapshoterrors.Problem { return 7; }
            }
            """;
    private static final String ERRORS = """
            package snapshoterrors;
            public final class Problem extends Base {
                public Problem() {}
                @Override public int getCode() { return 17; }
                @Override public Base getCause() { return null; }
            }
            """;
    private static final String BASE = """
            package snapshoterrors;
            public abstract class Base extends Exception {
                public Base() {}
                public abstract int getCode();
            }
            """;
    private static final List<String> EXPORTS = List.of("snapshotapi", "snapshoterrors");

    private BridgeSnapshotSurfaceTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var sources = sources(API, ERRORS, BASE);
            var artifact = analyze(sources, mode);
            verify(artifact, true);
            var missing = BridgeExportSurface.objectValues(artifact, List.of("snapshotapi"));
            check(missing.surface().isEmpty() && missing.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.message().contains("add --export snapshoterrors")), "missing throws package admitted");
            check(BridgeExportSurface.staticValues(artifact, EXPORTS).surface().isEmpty(), "public value producer admitted snapshots");
            var selected = select(artifact);
            var module = BridgeEntryModule.rootObjects(artifact, selected.roots());
            var partial = new BridgeExportSurface(selected.types().stream().filter(type -> !type.throwable()).toList(), selected.roots());
            check(BridgeExceptionClosure.snapshots(artifact, module, partial).status() != BridgeProof.Status.PROVED,
                    "partial API skipped declared exception getters");
            var changed = analyze(sources(API.replace("return 7", "return 8"), ERRORS, BASE), mode);
            check(BridgeExceptionClosure.snapshots(changed, module, selected).status() != BridgeProof.Status.PROVED,
                    "foreign API/program reused a snapshot closure");
            var harmful = sources(API, ERRORS.replace("return 17;", "snapshotapi.Item item = new snapshotapi.Item(); free item; return 17;"), BASE);
            verify(analyze(harmful, mode), false);
            for (var invalid : List.of(
                    sources(API.replace("read()", "read(snapshoterrors.Problem input)"), ERRORS, BASE),
                    sources(API, ERRORS.replace("@Override public Base getCause() { return null; }", "public Object getData() { return null; }"), BASE),
                    sources(API, ERRORS.replace("@Override public Base getCause() { return null; }", "public int getData(int index) { return index; }"), BASE))) {
                var bad = BridgeExportSurface.objectValues(analyze(invalid, mode), EXPORTS);
                check(bad.surface().isEmpty(), "unsupported snapshot signature admitted");
            }
            if (mode == UnfreedMode.OFF) {
                parity(sources, true);
                parity(harmful, false);
                missingHierarchy();
            }
        }
    }

    private static void verify(CompilationArtifact artifact, boolean safe) {
        var surface = select(artifact);
        check(surface.types().size() == 3 && surface.roots().roots().size() == 2
                && surface.roots().roots().stream().noneMatch(root -> root.callable().owner().startsWith("snapshoterrors.")),
                "snapshot constructors/getters leaked into object entries");
        var module = BridgeEntryModule.rootObjects(artifact, surface.roots());
        var closure = BridgeExceptionClosure.snapshots(artifact, module, surface);
        check(closure.status() == BridgeProof.Status.PROVED, closure.reason());
        var projection = closure.contract().orElseThrow().projection();
        check(projection.customTypes().keySet().containsAll(List.of("snapshoterrors.Base", "snapshoterrors.Problem"))
                && projection.accessors().roots().stream().anyMatch(root -> root.callable().owner().equals("snapshoterrors.Problem")
                && root.callable().name().equals("getCode")), "unthrown declaration/getter absent from closure");
        var result = BridgeFinalRootRetention.prove(artifact, module, surface);
        check((result.status() == BridgeProof.Status.PROVED) == safe, "final root snapshot result: " + result.reason());
        if (!safe) check(result.reason().contains("dealloc"), "hidden getter failed for an unrelated reason: " + result.reason());
        var permanent = BridgeEntryModule.permanentObjects(artifact, surface.roots());
        var permanentResult = BridgeFinalNonReclamation.prove(artifact, permanent, surface);
        check((permanentResult.status() == BridgeProof.Status.PROVED) == safe,
                "final permanent snapshot result: " + permanentResult.reason());
    }

    private static void missingHierarchy() {
        var externalBase = BASE.replace("package snapshoterrors;", "package catchbase;");
        var artifact = analyze(sources(API, ERRORS.replace("extends Base", "extends catchbase.Base")
                .replace("public Base getCause()", "public catchbase.Base getCause()"), externalBase), UnfreedMode.OFF);
        var selected = BridgeExportSurface.objectValues(artifact, EXPORTS);
        check(selected.surface().isEmpty() && selected.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("add --export catchbase")), "catch hierarchy escaped packages");
        var hidden = SourceFile.of("Hidden.iron", "package hidden; public final class Hidden extends RuntimeException {}");
        var reachableSources = new java.util.ArrayList<>(sources(API.replace("return 7;", "throw new hidden.Hidden();"), ERRORS, BASE));
        reachableSources.add(hidden);
        var reachable = analyze(reachableSources, UnfreedMode.OFF);
        var surface = select(reachable);
        var module = BridgeEntryModule.rootObjects(reachable, surface.roots());
        var rejected = BridgeExceptionClosure.snapshots(reachable, module, surface);
        check(rejected.status() == BridgeProof.Status.REJECTED && rejected.reason().contains("--export hidden"),
                "reachable undeclared custom exception escaped packages: " + rejected.reason());
        var getterSources = new java.util.ArrayList<>(sources(API, ERRORS.replace("getCode()", "getCode() throws hidden.Hidden"), BASE));
        getterSources.add(hidden);
        var getter = BridgeExportSurface.objectValues(analyze(getterSources, UnfreedMode.OFF), EXPORTS);
        check(getter.surface().isEmpty() && getter.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("add --export hidden")), "getter throws escaped packages");
    }

    private static BridgeExportSurface select(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.objectValues(artifact, EXPORTS);
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow();
    }

    private static List<SourceFile> sources(String api, String errors, String base) {
        return List.of(SourceFile.of("Item.iron", api), SourceFile.of("Problem.iron", errors), SourceFile.of("Base.iron", base));
    }

    private static CompilationArtifact analyze(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static void parity(List<SourceFile> sources, boolean safe) throws Exception {
        Path directory = Files.createTempDirectory("bridge snapshot surface ");
        try {
            Path classes = directory.resolve("classes");
            for (var source : sources) {
                var unit = SourceParser.parse(source).unit().orElseThrow();
                for (var type : DeclaredTypes.in(unit)) {
                    IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
                }
            }
            Path archive = directory.resolve("snapshots.ironjar");
            IronJar.create(archive, List.of(classes));
            for (var inputs : List.of(List.of(classes), List.of(classes.resolve("snapshotapi/Item.ironclass"),
                    classes.resolve("snapshoterrors/Problem.ironclass"), classes.resolve("snapshoterrors/Base.ironclass")), List.of(archive))) {
                var loaded = new SourceSetLoader(List.of(), inputs).loadBridge(List.of(), EXPORTS);
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                verify(analyze(loaded.sources(), UnfreedMode.OFF), safe);
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
