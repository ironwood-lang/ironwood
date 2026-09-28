// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ImportDeclaration;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.semantic.SemanticAnalyzer;
import ironwood.compiler.semantic.SemanticResult;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class CompilerPipeline {
    private final UnfreedMode unfreedMode;
    private final boolean explainRejectedFree;
    private final SemanticAnalyzerFactory analyzerFactory;

    public CompilerPipeline() {
        this(UnfreedMode.WARN);
    }

    public CompilerPipeline(UnfreedMode unfreedMode) {
        this(unfreedMode, false, null);
    }

    CompilerPipeline(UnfreedMode unfreedMode, boolean explainRejectedFree,
                     SemanticAnalyzerFactory analyzerFactory) {
        this.unfreedMode = java.util.Objects.requireNonNull(unfreedMode);
        this.explainRejectedFree = explainRejectedFree;
        this.analyzerFactory = analyzerFactory;
    }

    public CompilationArtifact compile(SourceFile source) {
        return compile(List.of(source));
    }

    public CompilationArtifact compile(List<SourceFile> sources) {
        return compile(sources, true, Optional.empty());
    }

    public CompilationArtifact compile(List<SourceFile> sources, String mainClass) {
        return compile(sources, true, Optional.of(mainClass));
    }

    public CompilationArtifact analyze(List<SourceFile> sources) {
        return compile(sources, false, Optional.empty());
    }

    /** Internal analysis-only entry; emits no public bridge artifacts. */
    public CompilationArtifact analyzeForBridge(List<SourceFile> sources) {
        return compile(sources, false, Optional.empty(), true);
    }

    /** Rebind generated listener declarations before the ordinary mandatory proofs. */
    public CompilationArtifact analyzeForBridge(List<SourceFile> sources,
            ironwood.compiler.bridge.BridgeListenerProxies proxies) {
        var combined = new ArrayList<>(sources);
        combined.addAll(proxies.sources());
        try {
            return compile(combined, false, Optional.empty(), true, proxies);
        } catch (IllegalArgumentException invalid) {
            return new CompilationArtifact(Optional.empty(), Optional.empty(),
                    List.of(Diagnostic.global("cannot analyze listener proxies: " + invalid.getMessage())));
        }
    }

    /** Final-link scalar library entry; package discovery and Java/JNI generation are separate. */
    public CompilationArtifact compileBridge(CompilationArtifact analyzed,
            ironwood.compiler.bridge.BridgeRootSet roots) {
        if (!analyzed.valid()) return analyzed;
        try {
            var module = ironwood.compiler.bridge.BridgeEntryModule.scalars(analyzed, roots);
            var entryIds = module.entries().stream().map(entry ->
                    ironwood.compiler.bridge.BridgeCallableId.of(entry.function())).toList();
            var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program()));
            var resolved = ironwood.compiler.bridge.BridgeRootSet.resolve(program, entryIds);
            if (!resolved.resolved() || !program.exportRoots().equals(module.entrySymbols())) {
                throw new IllegalArgumentException("native optimization changed an admitted bridge entry signature");
            }
            // Source semantic facts stay bound to their original analysis. Do not
            // attach them to transformed IR as if fresh ownership had been proved.
            return new CompilationArtifact(Optional.of(program), Optional.of(new LlvmEmitter().emit(program)), analyzed.diagnostics());
        } catch (IllegalArgumentException exception) {
            var diagnostics = new ArrayList<>(analyzed.diagnostics());
            diagnostics.add(Diagnostic.global("cannot build bridge library: " + exception.getMessage()));
            return new CompilationArtifact(Optional.empty(), Optional.empty(), diagnostics);
        }
    }

    private CompilationArtifact compile(List<SourceFile> sources, boolean requireMain,
                                        Optional<String> mainClass) {
        return compile(sources, requireMain, mainClass, false);
    }

    private CompilationArtifact compile(List<SourceFile> sources, boolean requireMain,
                                        Optional<String> mainClass, boolean bridgeAnalysis) {
        return compile(sources, requireMain, mainClass, bridgeAnalysis, null);
    }

    private CompilationArtifact compile(List<SourceFile> sources, boolean requireMain,
                                        Optional<String> mainClass, boolean bridgeAnalysis,
                                        ironwood.compiler.bridge.BridgeListenerProxies proxies) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<ironwood.compiler.ast.CompilationUnit> units = new ArrayList<>();

        StandardLibrary standardLibrary = StandardLibrary.discover();

        for (SourceFile source : sources) {
            ParsedSource parsed = SourceParser.parse(source);
            diagnostics.addAll(parsed.diagnostics());
            parsed.unit().ifPresent(unit -> {
                boolean declaresRoot = unit.declarations().stream().anyMatch(declaration ->
                        canonical(unit.packageName(), declaration.name())
                                .equals(StandardLibrary.ROOT_OBJECT));
                if (declaresRoot && !standardLibrary.isBundledSource(source)) {
                    diagnostics.add(Diagnostic.error(source,
                            unit.declarations().stream().filter(declaration ->
                                            canonical(unit.packageName(), declaration.name())
                                                    .equals(StandardLibrary.ROOT_OBJECT))
                                    .findFirst().orElseThrow().nameSpan(),
                            "type '" + StandardLibrary.ROOT_OBJECT
                                    + "' is reserved by the bundled standard library"));
                }
                units.add(unit);
            });
        }

        addBundledType(StandardLibrary.ROOT_OBJECT, standardLibrary, units, diagnostics);
        addBundledType("ironwood.lang.String", standardLibrary, units, diagnostics);
        addBundledDependencyClosure(standardLibrary, units, diagnostics);
        if (Diagnostic.hasErrors(diagnostics)) {
            return new CompilationArtifact(Optional.empty(), Optional.empty(), diagnostics);
        }

        Set<java.nio.file.Path> originalSources = sources.stream().map(SourceFile::path)
                .collect(java.util.stream.Collectors.toSet());
        SemanticAnalyzer analyzer = analyzerFactory == null
                ? new SemanticAnalyzer(unfreedMode, originalSources, explainRejectedFree)
                : analyzerFactory.create(unfreedMode, originalSources, explainRejectedFree);
        SemanticResult semanticResult = proxies != null ? analyzer.analyzeForBridge(units, proxies)
                : bridgeAnalysis ? analyzer.analyzeForBridge(units) : mainClass.isPresent()
                ? analyzer.analyze(units, mainClass.orElseThrow())
                : analyzer.analyze(units, requireMain);
        diagnostics.addAll(semanticResult.diagnostics());
        if (Diagnostic.hasErrors(diagnostics) || semanticResult.program().isEmpty()) {
            return new CompilationArtifact(Optional.empty(), Optional.empty(), diagnostics);
        }

        if (!requireMain) {
            return new CompilationArtifact(semanticResult.program(), Optional.empty(), diagnostics,
                    semanticResult.bridgeConstructionFacts(), semanticResult.bridgeApiFacts());
        }
        var program = NativeLinkPipeline.optimize(semanticResult.program().orElseThrow());
        String llvmIr = new LlvmEmitter().emit(program);
        return new CompilationArtifact(Optional.of(program), Optional.of(llvmIr), diagnostics);
    }

    private static String canonical(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static void addBundledType(String canonicalName, StandardLibrary standardLibrary,
                                       List<ironwood.compiler.ast.CompilationUnit> units,
                                       List<Diagnostic> diagnostics) {
        boolean present = units.stream().anyMatch(unit ->
                standardLibrary.isBundledSource(unit.source())
                        && unit.declarations().stream().anyMatch(declaration ->
                        canonical(unit.packageName(), declaration.name()).equals(canonicalName)));
        if (present) {
            return;
        }
        try {
            Optional<SourceFile> source = standardLibrary.locate(canonicalName);
            if (source.isEmpty()) {
                diagnostics.add(Diagnostic.global("cannot locate bundled standard-library type '"
                        + canonicalName + "'"));
                return;
            }
            ParsedSource parsed = SourceParser.parse(source.orElseThrow());
            diagnostics.addAll(parsed.diagnostics());
            parsed.unit().ifPresent(units::add);
        } catch (java.io.IOException exception) {
            diagnostics.add(Diagnostic.global("cannot read bundled standard-library type '"
                    + canonicalName + "': " + exception.getMessage()));
        }
    }

    private static void addBundledDependencyClosure(StandardLibrary standardLibrary,
                                                    List<CompilationUnit> units,
                                                    List<Diagnostic> diagnostics) {
        TypeDependencyScanner scanner = new TypeDependencyScanner();
        Set<String> declared = new LinkedHashSet<>();
        units.forEach(unit -> DeclaredTypes.in(unit).forEach(declaration -> {
            declared.add(declaration.binaryName());
            declared.add(declaration.sourceName());
        }));
        for (int index = 0; index < units.size(); index++) {
            CompilationUnit unit = units.get(index);
            for (String dependency : scanner.scan(unit)) {
                for (String candidate : bundledCandidates(dependency, unit)) {
                    if (!standardLibrary.owns(candidate)) {
                        continue;
                    }
                    if (!declared.contains(candidate)) {
                        int previousSize = units.size();
                        addBundledType(candidate, standardLibrary, units, diagnostics);
                        if (units.size() > previousSize) {
                            CompilationUnit added = units.getLast();
                            DeclaredTypes.in(added).forEach(declaration -> {
                                declared.add(declaration.binaryName());
                                declared.add(declaration.sourceName());
                            });
                        }
                    }
                    break;
                }
            }
        }
    }

    private static List<String> bundledCandidates(String raw, CompilationUnit unit) {
        if (raw.indexOf('.') >= 0) {
            return List.of(raw);
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<ImportDeclaration> ordinarySingles = unit.imports().stream()
                .filter(imported -> !imported.staticImport())
                .filter(imported -> !imported.wildcard())
                .filter(imported -> imported.importedSimpleName().equals(raw))
                .toList();
        ordinarySingles.stream().map(ImportDeclaration::name).forEach(candidates::add);
        unit.imports().stream().filter(ImportDeclaration::staticImport)
                .filter(imported -> !imported.wildcard())
                .filter(imported -> imported.importedMemberName().equals(raw))
                .map(imported -> imported.ownerName() + "." + raw).forEach(candidates::add);
        if (!ordinarySingles.isEmpty()) {
            return List.copyOf(candidates);
        }
        candidates.add(canonical(unit.packageName(), raw));
        candidates.add("ironwood.lang." + raw);
        unit.imports().stream().filter(imported -> !imported.staticImport())
                .filter(ImportDeclaration::wildcard)
                .map(imported -> imported.name() + "." + raw).forEach(candidates::add);
        unit.imports().stream().filter(ImportDeclaration::staticImport)
                .filter(ImportDeclaration::wildcard)
                .map(imported -> imported.ownerName() + "." + raw).forEach(candidates::add);
        return List.copyOf(candidates);
    }
}
