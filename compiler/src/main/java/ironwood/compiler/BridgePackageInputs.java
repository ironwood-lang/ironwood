// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Exact-package seeds for the ordinary closed-world source/dependency loader. */
record BridgePackageInputs(List<Path> sources, List<String> classes, List<Diagnostic> diagnostics) {
    BridgePackageInputs {
        sources = List.copyOf(sources);
        classes = List.copyOf(classes);
        diagnostics = List.copyOf(diagnostics);
    }

    static BridgePackageInputs discover(List<Path> explicitInputs, List<Path> sourcePath,
            List<Path> classPath, List<String> exports, StandardLibrary standardLibrary) {
        var diagnostics = new ArrayList<Diagnostic>();
        var packages = new TreeSet<String>();
        for (String name : exports) {
            if (name == null || !SourceVersion.isName(name, SourceVersion.RELEASE_21)) {
                diagnostics.add(Diagnostic.global("invalid Java Bridge export package: '" + name + "'"));
            } else {
                packages.add(name);
            }
        }
        if (packages.isEmpty() && diagnostics.isEmpty()) {
            diagnostics.add(Diagnostic.global("Java Bridge requires at least one exact export package"));
        }
        if (!diagnostics.isEmpty()) return new BridgePackageInputs(List.of(), List.of(), diagnostics);
        var sources = new LinkedHashSet<Path>();
        explicitInputs.stream().map(path -> path.toAbsolutePath().normalize()).forEach(sources::add);
        var classes = new TreeSet<String>();
        for (String name : packages) {
            Path relative = Path.of(name.replace('.', '/'));
            for (Path root : sourcePath) {
                for (Path source : files(root.resolve(relative), ".iron", diagnostics)) {
                    try {
                        var parsed = SourceParser.parse(SourceFile.read(source));
                        diagnostics.addAll(parsed.diagnostics());
                        if (parsed.unit().isPresent()) {
                            var unit = parsed.unit().orElseThrow();
                            if (!unit.packageName().equals(name)) {
                                diagnostics.add(Diagnostic.error(unit.source(), unit.span(),
                                        "export package '" + name + "' contains source declaring package '"
                                                + unit.packageName() + "'"));
                            } else {
                                sources.add(source.toAbsolutePath().normalize());
                            }
                        }
                    } catch (IOException exception) {
                        diagnostics.add(Diagnostic.global("cannot read export source '" + source
                                + "': " + exception.getMessage()));
                    }
                }
            }
            for (Path entry : classPath) {
                if (Files.isDirectory(entry)) {
                    for (Path file : files(entry.resolve(relative), IronClass.EXTENSION, diagnostics)) {
                        readClass(file, Set.of(name), classes, diagnostics);
                    }
                }
            }
        }
        for (Path entry : classPath) {
            if (!Files.isRegularFile(entry)) continue;
            if (entry.toString().endsWith(IronJar.EXTENSION)) {
                try {
                    addClasses(IronJar.read(entry).declaredTypes(), packages, classes);
                } catch (IOException exception) {
                    diagnostics.add(Diagnostic.global("cannot read export archive '" + entry
                            + "': " + exception.getMessage()));
                }
            } else if (entry.toString().endsWith(IronClass.EXTENSION)) {
                readClass(entry, packages, classes, diagnostics);
            }
        }
        addClasses(standardLibrary.ownedTypes(), packages, classes);
        return new BridgePackageInputs(new ArrayList<>(sources), new ArrayList<>(classes), diagnostics);
    }

    private static void readClass(Path file, Set<String> packages, Set<String> classes,
                                  List<Diagnostic> diagnostics) {
        try {
            addClasses(IronClass.read(file).declaredTypes(), packages, classes);
        } catch (IOException exception) {
            diagnostics.add(Diagnostic.global("cannot read export class '" + file
                    + "': " + exception.getMessage()));
        }
    }

    private static void addClasses(Set<String> candidates, Set<String> packages, Set<String> classes) {
        candidates.stream().filter(name -> packages.contains(packageName(name))).forEach(classes::add);
    }

    private static String packageName(String binaryName) {
        int separator = binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }

    private static List<Path> files(Path directory, String extension, List<Diagnostic> diagnostics) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(extension)).sorted().toList();
        } catch (IOException exception) {
            diagnostics.add(Diagnostic.global("cannot enumerate export package directory '" + directory
                    + "': " + exception.getMessage()));
            return List.of();
        }
    }
}
