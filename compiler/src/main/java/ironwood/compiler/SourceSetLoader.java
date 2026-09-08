// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ImportDeclaration;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SourceSetLoader {
    private final List<Path> sourcePath;
    private final List<Path> classPath;
    private final TypeDependencyScanner dependencies = new TypeDependencyScanner();
    private final StandardLibrary standardLibrary = StandardLibrary.discover();
    private final Map<Path, IronJar> archiveCache = new LinkedHashMap<>();

    SourceSetLoader(List<Path> sourcePath, List<Path> classPath) {
        this.sourcePath = sourcePath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        this.classPath = classPath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    SourceLoadResult load(List<Path> explicitInputs) {
        return load(explicitInputs, List.of());
    }

    SourceLoadResult load(List<Path> explicitInputs, List<String> explicitClassTypes) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, SourceFile> sourcesByPath = new LinkedHashMap<>();
        List<CompilationUnit> units = new ArrayList<>();
        Map<String, CompilationUnit> declaredTypes = new LinkedHashMap<>();

        for (Path input : explicitInputs) {
            try {
                add(SourceFile.read(input), null, sourcesByPath, units, declaredTypes, diagnostics);
            } catch (IOException exception) {
                diagnostics.add(Diagnostic.global("cannot read '" + input + "': " + exception.getMessage()));
            }
        }

        for (String type : explicitClassTypes) {
            if (declaredTypes.containsKey(type)) {
                continue;
            }
            Optional<SourceFile> source = locateClass(type, diagnostics);
            if (source.isEmpty()) {
                diagnostics.add(Diagnostic.global("cannot find class '" + type + "' on the class path"));
                continue;
            }
            add(source.orElseThrow(), type, sourcesByPath, units, declaredTypes, diagnostics);
        }

        if (!declaredTypes.containsKey(StandardLibrary.ROOT_OBJECT)) {
            Optional<SourceFile> root = locate(StandardLibrary.ROOT_OBJECT, diagnostics);
            if (root.isPresent()) {
                add(root.orElseThrow(), StandardLibrary.ROOT_OBJECT, sourcesByPath, units,
                        declaredTypes, diagnostics);
            }
        }

        for (int index = 0; index < units.size(); index++) {
            CompilationUnit unit = units.get(index);
            for (String dependency : dependencies.scan(unit)) {
                List<String> candidates = candidateNames(dependency, unit);
                for (String candidate : candidates) {
                    if (declaredTypes.containsKey(candidate)) {
                        if (!isWildcardCandidate(dependency, unit, candidate)) {
                            break;
                        }
                        continue;
                    }
                    Optional<SourceFile> source = locate(candidate, diagnostics);
                    if (source.isEmpty()) {
                        continue;
                    }
                    add(source.orElseThrow(), candidate, sourcesByPath, units, declaredTypes, diagnostics);
                    if (!isWildcardCandidate(dependency, unit, candidate)) {
                        break;
                    }
                }
            }
        }
        return new SourceLoadResult(new ArrayList<>(sourcesByPath.values()), diagnostics);
    }

    private void add(SourceFile source, String expectedType,
                     Map<String, SourceFile> sourcesByPath, List<CompilationUnit> units,
                     Map<String, CompilationUnit> declaredTypes, List<Diagnostic> diagnostics) {
        String identity = source.path().toString();
        if (sourcesByPath.containsKey(identity)) {
            return;
        }
        ParsedSource parsed = SourceParser.parse(source);
        diagnostics.addAll(parsed.diagnostics());
        if (parsed.unit().isEmpty()) {
            return;
        }
        CompilationUnit unit = parsed.unit().orElseThrow();
        sourcesByPath.put(identity, source);
        units.add(unit);
        for (var declaredType : DeclaredTypes.in(unit)) {
            var declaration = declaredType.declaration();
            String canonical = declaredType.binaryName();
            if (standardLibrary.owns(canonical) && !standardLibrary.isBundledSource(source)) {
                diagnostics.add(Diagnostic.error(source, declaration.nameSpan(), "type '"
                        + canonical + "' is reserved by the bundled standard library"));
            }
            declaredTypes.putIfAbsent(canonical, unit);
            declaredTypes.putIfAbsent(declaredType.sourceName(), unit);
        }
        if (expectedType != null && !declaredTypes.containsKey(expectedType)) {
            diagnostics.add(Diagnostic.error(source, unit.span(), "resolved file for type '"
                    + expectedType + "' does not declare that type"));
        }
    }

    private Optional<SourceFile> locate(String canonical, List<Diagnostic> diagnostics) {
        if (standardLibrary.owns(canonical)) {
            try {
                Optional<SourceFile> source = standardLibrary.locate(canonical);
                if (source.isEmpty()) {
                    diagnostics.add(Diagnostic.global("cannot locate bundled standard-library type '"
                            + canonical + "'"));
                }
                return source;
            } catch (IOException exception) {
                diagnostics.add(Diagnostic.global("cannot read bundled standard-library type '"
                        + canonical + "': " + exception.getMessage()));
                return Optional.empty();
            }
        }
        for (Path root : sourcePath) {
            for (String sourceOwner : sourceOwnerCandidates(canonical)) {
                Path candidate = root.resolve(Path.of(sourceOwner.replace('.', '/') + ".iron"));
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Optional.of(SourceFile.read(candidate));
                    } catch (IOException exception) {
                        diagnostics.add(Diagnostic.global("cannot read source-path file '" + candidate
                                + "': " + exception.getMessage()));
                        return Optional.empty();
                    }
                }
            }
        }
        return locateClass(canonical, diagnostics);
    }

    private Optional<SourceFile> locateClass(String canonical, List<Diagnostic> diagnostics) {
        for (Path entry : classPath) {
            if (Files.isRegularFile(entry)
                    && entry.getFileName().toString().endsWith(IronJar.EXTENSION)) {
                try {
                    IronJar archive = archiveCache.get(entry);
                    if (archive == null) {
                        archive = IronJar.read(entry);
                        archiveCache.put(entry, archive);
                    }
                    for (String identity : binaryIdentityCandidates(canonical)) {
                        Optional<SourceFile> source = archive.source(identity);
                        if (source.isPresent()) {
                            return source;
                        }
                    }
                    continue;
                } catch (IOException exception) {
                    diagnostics.add(Diagnostic.global("cannot read class-path archive '" + entry
                            + "': " + exception.getMessage()));
                    return Optional.empty();
                }
            }
            for (String identity : binaryIdentityCandidates(canonical)) {
                Path relativeClass = Path.of(identity.replace('.', '/') + IronClass.EXTENSION);
                Path candidate = Files.isDirectory(entry) ? entry.resolve(relativeClass) : entry;
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    Optional<SourceFile> source = IronClass.read(candidate).source(identity);
                    if (source.isPresent()) {
                        return source;
                    }
                } catch (IOException exception) {
                    diagnostics.add(Diagnostic.global("cannot read class-path file '" + candidate
                            + "': " + exception.getMessage()));
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private List<String> candidateNames(String raw, CompilationUnit unit) {
        if (raw.indexOf('.') >= 0) {
            LinkedHashSet<String> qualified = new LinkedHashSet<>();
            qualified.add(raw);
            qualified.add(canonical(unit.packageName(), raw));
            String first = raw.substring(0, raw.indexOf('.'));
            String suffix = raw.substring(raw.indexOf('.'));
            unit.imports().stream().filter(imported -> !imported.staticImport())
                    .filter(imported -> !imported.wildcard())
                    .filter(imported -> imported.importedSimpleName().equals(first))
                    .map(imported -> imported.name() + suffix).forEach(qualified::add);
            unit.imports().stream().filter(imported -> !imported.staticImport())
                    .filter(ImportDeclaration::wildcard)
                    .map(imported -> imported.name() + "." + raw).forEach(qualified::add);
            unit.imports().stream().filter(ImportDeclaration::staticImport)
                    .filter(imported -> imported.wildcard()
                            || imported.importedMemberName().equals(first))
                    .map(imported -> imported.ownerName() + "." + raw)
                    .forEach(qualified::add);
            return List.copyOf(qualified);
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<ImportDeclaration> ordinarySingles = unit.imports().stream()
                .filter(imported -> !imported.staticImport())
                .filter(imported -> !imported.wildcard())
                .filter(imported -> imported.importedSimpleName().equals(raw))
                .toList();
        ordinarySingles.stream().map(ImportDeclaration::name).forEach(names::add);
        unit.imports().stream().filter(ImportDeclaration::staticImport)
                .filter(imported -> !imported.wildcard())
                .filter(imported -> imported.importedMemberName().equals(raw))
                .map(imported -> imported.ownerName() + "." + raw).forEach(names::add);
        if (!ordinarySingles.isEmpty()) {
            return List.copyOf(names);
        }
        names.add(canonical(unit.packageName(), raw));
        names.add("ironwood.lang." + raw);
        unit.imports().stream().filter(imported -> !imported.staticImport())
                .filter(ImportDeclaration::wildcard)
                .map(imported -> imported.name() + "." + raw).forEach(names::add);
        unit.imports().stream().filter(ImportDeclaration::staticImport)
                .filter(ImportDeclaration::wildcard)
                .map(imported -> imported.ownerName() + "." + raw).forEach(names::add);
        return List.copyOf(names);
    }

    private boolean isWildcardCandidate(String raw, CompilationUnit unit, String candidate) {
        return raw.indexOf('.') < 0 && unit.imports().stream().filter(ImportDeclaration::wildcard)
                .anyMatch(imported -> candidate.equals((imported.staticImport()
                        ? imported.ownerName() : imported.name()) + "." + raw));
    }

    private static String canonical(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static List<String> sourceOwnerCandidates(String identity) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addSourceOwners(candidates, identity);
        if (identity.indexOf('$') >= 0) {
            addSourceOwners(candidates, identity.replace('$', '.'));
        }
        return List.copyOf(candidates);
    }

    private static void addSourceOwners(Set<String> candidates, String identity) {
        String source = identity;
        candidates.add(source);
        int separator = source.lastIndexOf('.');
        while (separator >= 0) {
            source = source.substring(0, separator);
            candidates.add(source);
            separator = source.lastIndexOf('.');
        }
    }

    private static List<String> binaryIdentityCandidates(String sourceName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(sourceName);
        char[] value = sourceName.toCharArray();
        for (int index = value.length - 1; index >= 0; index--) {
            if (value[index] == '.') {
                value[index] = '$';
                candidates.add(new String(value));
            }
        }
        return List.copyOf(candidates);
    }
}
