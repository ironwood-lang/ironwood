// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Works out which files belong to the same Ironwood source set as the file
 * being edited.
 *
 * <p>Ironwood follows the Java source-organization model, so a file declaring
 * {@code package a.b} sits at {@code <root>/a/b}. Stripping the package
 * segments from the file's directory therefore recovers the source root, and
 * every {@code .iron} file beneath that root is a candidate for analysis. This
 * is deliberately the same rule a programmer applies by eye, so a project needs
 * no IDE metadata for diagnostics to work.
 */
public final class SourceSetResolver {

    /**
     * Directories that never contain source worth analyzing. Build output in
     * particular can hold generated or stale copies that would produce
     * duplicate type declarations.
     */
    private static final Set<String> IGNORED_DIRECTORIES =
            Set.of("target", "build", "out", "bin", "node_modules");

    /**
     * Upper bound on the files pulled into one analysis. A source root that
     * exceeds it is analyzed as far as the limit allows rather than making the
     * editor unresponsive, and the caller reports the truncation.
     */
    public static final int MAX_SOURCES = 2000;

    private SourceSetResolver() {
    }

    /**
     * Recovers the source root for a file from its own package declaration.
     *
     * <p>Returns the file's directory when the file declares no package, and
     * empty when the declared package does not match the directories the file
     * actually sits in, since guessing a root from a mismatched layout would
     * pull unrelated files into the analysis.
     */
    public static Optional<Path> sourceRootFor(Path file, String content) {
        Path directory = file.getParent();
        if (directory == null) {
            return Optional.empty();
        }

        String packageName = declaredPackage(file, content);
        if (packageName.isEmpty()) {
            return Optional.of(directory);
        }

        Path root = directory;
        String[] segments = packageName.split("\\.");
        for (int index = segments.length - 1; index >= 0; index--) {
            if (root == null || root.getFileName() == null
                    || !root.getFileName().toString().equals(segments[index])) {
                return Optional.empty();
            }
            root = root.getParent();
        }
        return Optional.ofNullable(root);
    }

    /**
     * Reads the package declaration using the compiler's own lexer and parser,
     * so an unusual but legal header is read the same way the compiler reads
     * it. A file that does not parse yet, which is the normal state while
     * typing, reports no package.
     */
    private static String declaredPackage(Path file, String content) {
        SourceFile source = SourceFile.of(file.toString(), content);
        ParseResult parsed = new Parser(source, new Lexer(source).lex().tokens()).parse();
        return parsed.unit().map(CompilationUnit::packageName).orElse("");
    }

    /**
     * Collects the {@code .iron} files under a source root, skipping build
     * output and hidden directories. Results are sorted so that analysis is
     * deterministic across runs.
     */
    public static List<Path> collectSources(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
            return tree
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".iron"))
                    .filter(path -> !isIgnored(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_SOURCES)
                    .toList();
        }
    }

    private static boolean isIgnored(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith(".") || IGNORED_DIRECTORIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the compiler's view of the source set, preferring an open editor
     * buffer over the file on disk. A file that cannot be read is dropped
     * rather than failing the whole analysis, since a file can disappear
     * between the directory walk and the read.
     */
    public static List<SourceFile> toSourceFiles(List<Path> paths, DocumentStore documents) {
        List<SourceFile> sources = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Optional<String> open = documents.contentOf(path);
            if (open.isPresent()) {
                sources.add(SourceFile.of(path.toString(), open.get()));
                continue;
            }
            try {
                sources.add(SourceFile.read(path));
            } catch (IOException ignored) {
                // A file removed mid-analysis simply drops out of the source set.
            }
        }
        return sources;
    }
}
