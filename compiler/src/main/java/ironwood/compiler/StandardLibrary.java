// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class StandardLibrary {
    private static final Set<String> CORE_TYPES = Set.of(
            "ironwood.lang.Object",
            "ironwood.lang.String",
            "ironwood.lang.System",
            "ironwood.io.PrintStream"
    );

    static final String ROOT_OBJECT = "ironwood.lang.Object";

    private final List<Path> classRoots;
    private final List<Path> sourceRoots;
    private final List<Path> archives;
    private final Set<String> ownedTypes;

    private StandardLibrary(List<Path> classRoots, List<Path> sourceRoots, List<Path> archives) {
        this.classRoots = List.copyOf(classRoots);
        this.sourceRoots = List.copyOf(sourceRoots);
        this.archives = List.copyOf(archives);
        LinkedHashSet<String> discovered = new LinkedHashSet<>(CORE_TYPES);
        archives.forEach(archive -> discoverArchiveTypes(archive, discovered));
        classRoots.forEach(root -> discoverTypes(root, IronClass.EXTENSION, discovered));
        sourceRoots.forEach(root -> discoverTypes(root, ".iron", discovered));
        this.ownedTypes = Set.copyOf(discovered);
    }

    static StandardLibrary discover() {
        List<Path> roots = new ArrayList<>();
        String explicitHome = System.getenv("IRONWOOD_STDLIB_HOME");
        if (explicitHome != null && !explicitHome.isBlank()) {
            roots.add(Path.of(explicitHome).toAbsolutePath().normalize());
        } else {
            try {
                Path codeLocation = Path.of(StandardLibrary.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
                roots.add(Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent());
            } catch (URISyntaxException | NullPointerException ignored) {
                // Working-directory discovery below supports ordinary source launches.
            }
            roots.add(Path.of("").toAbsolutePath().normalize());
        }

        List<Path> classRoots = new ArrayList<>();
        List<Path> sourceRoots = new ArrayList<>();
        List<Path> archives = new ArrayList<>();
        for (Path root : roots) {
            for (Path candidate = root; candidate != null; candidate = candidate.getParent()) {
                addFile(archives, candidate.resolve("lib/ironwood-stdlib" + IronJar.EXTENSION));
                addFile(archives, candidate.resolve("compiler/build/ironwood-stdlib"
                        + IronJar.EXTENSION));
                addDirectory(classRoots, candidate.resolve("lib/stdlib"));
                addDirectory(classRoots, candidate.resolve("compiler/build/stdlib"));
                addDirectory(sourceRoots, candidate.resolve("stdlib/src/main/ironwood"));
            }
        }
        return new StandardLibrary(classRoots, sourceRoots, archives);
    }

    boolean owns(String canonicalName) {
        return ownedTypes.contains(canonicalName);
    }

    boolean isBundledSource(SourceFile source) {
        Path path = source.path().toAbsolutePath().normalize();
        return sourceRoots.stream().anyMatch(path::startsWith)
                || classRoots.stream().anyMatch(path::startsWith)
                || archives.stream().map(Path::toString)
                .anyMatch(archive -> source.path().toString().startsWith(archive + "!/"));
    }

    Optional<SourceFile> locate(String canonicalName) throws IOException {
        if (!owns(canonicalName)) {
            return Optional.empty();
        }
        for (Path archive : archives) {
            Optional<SourceFile> source = IronJar.read(archive).source(canonicalName);
            if (source.isPresent()) {
                return source;
            }
        }
        Path relativeClass = Path.of(canonicalName.replace('.', '/') + IronClass.EXTENSION);
        for (Path root : classRoots) {
            Path candidate = root.resolve(relativeClass);
            if (Files.isRegularFile(candidate)) {
                Optional<SourceFile> source = IronClass.read(candidate).source(canonicalName);
                if (source.isPresent()) {
                    return source;
                }
            }
        }
        Path relativeSource = Path.of(canonicalName.replace('.', '/') + ".iron");
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(relativeSource);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(SourceFile.read(candidate));
            }
        }
        return Optional.empty();
    }

    private static void addDirectory(List<Path> directories, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized) && !directories.contains(normalized)) {
            directories.add(normalized);
        }
    }

    private static void addFile(List<Path> files, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized) && !files.contains(normalized)) {
            files.add(normalized);
        }
    }

    private static void discoverArchiveTypes(Path archive, Set<String> types) {
        try {
            types.addAll(IronJar.read(archive).declaredTypes());
        } catch (IOException ignored) {
            // locate() reports an actionable error if a requested bundled type needs this archive.
        }
    }

    private static void discoverTypes(Path root, String extension, Set<String> types) {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(extension))
                    .map(path -> path.substring(0, path.length() - extension.length()))
                    .map(path -> path.replace('\\', '/').replace('/', '.'))
                    .filter(path -> !path.isBlank())
                    .forEach(types::add);
        } catch (IOException ignored) {
            // A missing individual root is handled by normal bundled-type lookup diagnostics.
        }
    }
}
