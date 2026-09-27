// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class BridgePackageTests {
    static final String NAME = "Java Bridge exact packages preserve source class and archive discovery";

    private BridgePackageTests() {}

    static void discovery() throws Exception {
        Path directory = Files.createTempDirectory("bridge packages ");
        try {
            Path sources = directory.resolve("sources");
            Path engine = write(sources, "api/Engine.iron", """
                    package api;
                    import internal.Helper;
                    public final class Engine {
                        private Engine() {}
                        public static int value(int input) { return Helper.value(input); }
                        public static long value(long input) { return input; }
                        public static final class Nested {
                            private Nested() {}
                            public static int answer() { return 42; }
                        }
                    }
                    final class Hidden {}
                    """);
            Path helper = write(sources, "internal/Helper.iron", """
                    package internal;
                    public final class Helper {
                        public static int value(int input) { return input + 7; }
                    }
                    """);
            Path second = write(sources, "other/Second.iron", """
                    package other;
                    public final class Second {
                        private Second() {}
                        public static boolean test() { return true; }
                    }
                    """);
            write(sources, "api/child/Excluded.iron", "this is deliberately not valid source");
            write(sources, "apiExtra/Excluded.iron", "this is deliberately not valid source");
            var exports = List.of("other", "api", "api");
            var sourceLoader = new SourceSetLoader(List.of(sources), List.of());
            var loaded = sourceLoader.loadBridge(List.of(), exports);
            check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
            Set<String> expected = Set.of("api.Engine", "api.Engine$Nested", "api.Hidden",
                    "other.Second", "internal.Helper");
            check(userTypes(loaded).equals(expected), "exact union, nesting or dependency closure changed");
            check(userTypes(sourceLoader.loadBridge(List.of(engine), exports)).equals(expected),
                    "explicit source was duplicated");
            check(sourceLoader.loadBridge(List.of(), List.of("api", "other")).sources().stream()
                            .map(SourceFile::path).toList().equals(loaded.sources().stream().map(SourceFile::path).toList()),
                    "export order or repeated exports affect discovery");
            var pipeline = new CompilerPipeline(UnfreedMode.OFF);
            var analyzed = pipeline.analyzeForBridge(loaded.sources());
            check(analyzed.valid(), analyzed.diagnostics().toString());
            var signatures = signatures(analyzed);
            var selection = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(analyzed, exports);
            check(selection.surface().isPresent(), selection.diagnostics().toString());

            Path classes = directory.resolve("classes");
            var individualClasses = new ArrayList<Path>();
            for (Path source : List.of(engine, helper, second)) {
                var unit = SourceParser.parse(SourceFile.read(source)).unit().orElseThrow();
                for (var type : DeclaredTypes.in(unit)) {
                    Path output = classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION);
                    IronClass.write(output, unit, type.binaryName());
                    individualClasses.add(output);
                }
            }
            Path archive = directory.resolve("api.ironjar");
            IronJar.create(archive, List.of(classes));
            // No source lookup is possible during the reconstruction checks.
            for (var classPath : List.of(List.of(classes), individualClasses, List.of(archive))) {
                var restored = new SourceSetLoader(List.of(), classPath).loadBridge(List.of(), exports);
                check(restored.diagnostics().isEmpty(), restored.diagnostics().toString());
                check(userTypes(restored).equals(expected), "artifact package discovery changed: " + classPath);
                var artifact = pipeline.analyzeForBridge(restored.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(signatures(artifact).equals(signatures), "artifact overload/nested identities changed");
                var selected = ironwood.compiler.bridge.BridgeExportSurface.scalarPreview(artifact, exports);
                check(selected.surface().isPresent(), selected.diagnostics().toString());
                check(selected.surface().orElseThrow().roots().roots().stream().map(root -> root.callable()).toList()
                                .equals(selection.surface().orElseThrow().roots().roots().stream().map(root -> root.callable()).toList()),
                        "complete public root selection changed after reconstruction");
            }
            reject(sourceLoader.loadBridge(List.of(), List.of()), "at least one");
            for (String invalid : List.of("api.*", "../api", "api..bad", "int", "api.class", "")) {
                reject(sourceLoader.loadBridge(List.of(), List.of(invalid)), "invalid Java Bridge export package");
            }
            reject(sourceLoader.loadBridge(List.of(), List.of("missing")), "cannot find Java Bridge export package");
            reject(sourceLoader.loadBridge(List.of(), List.of("api.child")), "");
            write(sources, "broken/Bad.iron", "package different; public class Bad {}");
            reject(sourceLoader.loadBridge(List.of(), List.of("broken")), "declaring package 'different'");
            Path corrupt = write(directory, "corrupt.ironjar", "not an archive");
            reject(new SourceSetLoader(List.of(), List.of(corrupt)).loadBridge(List.of(), exports),
                    "cannot read export archive");
            Path corruptClass = write(directory, "badclasses/api/Bad.ironclass", "not a class");
            reject(new SourceSetLoader(List.of(), List.of(corruptClass.getParent().getParent()))
                    .loadBridge(List.of(), List.of("api")), "cannot read export class");
            // Package selection does not authorize shadowing bundled library classes.
            Path shadow = write(sources, "ironwood/lang/Object.iron",
                    "package ironwood.lang; public class Object {}");
            reject(sourceLoader.loadBridge(List.of(shadow), List.of("api")), "reserved by the bundled");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Set<BridgeCallableId> signatures(CompilationArtifact artifact) {
        return artifact.program().orElseThrow().functions().stream()
                .filter(function -> Set.of("api.Engine", "api.Engine$Nested", "other.Second")
                        .contains(function.ownerClass()))
                .map(BridgeCallableId::of).collect(Collectors.toSet());
    }

    private static Set<String> userTypes(SourceLoadResult loaded) {
        var result = new TreeSet<String>();
        loaded.sources().forEach(source -> SourceParser.parse(source).unit().ifPresent(unit ->
                DeclaredTypes.in(unit).stream().map(type -> type.binaryName())
                        .filter(name -> !name.startsWith("ironwood.")).forEach(result::add)));
        return result;
    }

    private static Path write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    private static void reject(SourceLoadResult loaded, String message) {
        check(!loaded.diagnostics().isEmpty() && loaded.diagnostics().toString().contains(message),
                "expected rejection containing '" + message + "': " + loaded.diagnostics());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
