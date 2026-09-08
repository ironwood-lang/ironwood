// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.doc;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticFormatter;
import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source-only documentation entry point; neither LLVM nor native linking is involved. */
public final class IronDoc {
    private IronDoc() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            IronDocOptions options = IronDocOptions.parse(args);
            if (options.help) { out.print(HELP); return 0; }
            if (options.version) {
                try (var resource = IronDoc.class.getResourceAsStream("/ironwood/compiler/VERSION")) {
                    out.println("irondoc " + (resource == null ? "development"
                            : new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip()));
                }
                return 0;
            }
            List<Path> sources = sources(options);
            if (sources.isEmpty()) throw new IllegalArgumentException("no Ironwood source files selected; use irondoc --help");
            List<Diagnostic> diagnostics = new ArrayList<>();
            List<DocModel.Type> types = new ArrayList<>();
            DocModel model = new DocModel(options.visibility, diagnostics);
            for (Path source : sources) types.addAll(model.parse(SourceFile.read(source)));
            types.sort(Comparator.comparing(DocModel.Type::qualifiedName));
            Map<String, DocModel.Type> names = new TreeMap<>();
            for (var type : types) {
                if (names.putIfAbsent(type.qualifiedName(), type) != null) {
                    diagnostics.add(Diagnostic.error(type.unit().source(), type.declaration().span(),
                            "duplicate documented type " + type.qualifiedName()));
                }
            }
            if (!diagnostics.isEmpty()) return report(diagnostics, err);
            if (types.isEmpty()) throw new IllegalArgumentException("no types match the selected visibility");
            Map<Path, String> pages = new MarkdownDoclet(types, options, diagnostics).render();
            if (!diagnostics.isEmpty()) return report(diagnostics, err);
            Path output = options.output.toAbsolutePath().normalize();
            // Preflight every destination before writing: default -d must never overwrite
            // a project's hand-written README, and generated links cannot escape the root.
            for (Path page : pages.keySet()) {
                Path target = output.resolve(page).normalize();
                if (!target.startsWith(output)) throw new IOException("output path escapes destination: " + page);
                for (Path parent = target; parent != null && parent.startsWith(output); parent = parent.getParent()) {
                    if (Files.isSymbolicLink(parent)) throw new IOException("refusing symbolic-link output: " + parent);
                }
                if (Files.exists(target) && (!Files.isRegularFile(target)
                        || !Files.readString(target).startsWith(MarkdownDoclet.GENERATED))) {
                    throw new IOException("refusing to overwrite non-generated file: " + target);
                }
            }
            for (var page : pages.entrySet()) {
                Path target = output.resolve(page.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, page.getValue(), StandardCharsets.UTF_8);
            }
            if (!options.quiet) out.println("IronDocs: generated " + types.size() + " type(s) in " + output.resolve("README.md"));
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            err.println("error: " + exception.getMessage());
            return 1;
        }
    }

    private static int report(List<Diagnostic> diagnostics, PrintStream err) {
        DiagnosticFormatter formatter = new DiagnosticFormatter();
        diagnostics.forEach(diagnostic -> err.println(formatter.format(diagnostic)));
        return 1;
    }

    private static List<Path> sources(IronDocOptions options) throws IOException {
        TreeSet<Path> result = new TreeSet<>();
        for (String input : options.inputs) {
            if (input.endsWith(".iron")) {
                Path file = Path.of(input);
                if (!Files.isRegularFile(file)) throw new IOException("source file not found: " + file);
                result.add(file.toRealPath());
            } else selectPackage(options, input, false, result);
        }
        for (String exclude : options.excludes) validatePackage(exclude);
        for (String pkg : options.subpackages) selectPackage(options, pkg, true, result);
        if (options.sourcePathSpecified && options.inputs.isEmpty() && options.subpackages.isEmpty()) {
            for (Path root : options.sourcePath) {
                if (!Files.isDirectory(root)) continue;
                try (var paths = Files.walk(root)) {
                    for (Path file : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".iron"))
                            .sorted().toList()) {
                        result.add(file.toRealPath());
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static void selectPackage(IronDocOptions options, String pkg, boolean recursive, TreeSet<Path> result)
            throws IOException {
        validatePackage(pkg);
        boolean found = false;
        for (Path root : options.sourcePath) {
            Path directory = root.resolve(pkg.replace('.', '/'));
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.walk(directory, recursive ? Integer.MAX_VALUE : 1)) {
                for (Path file : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".iron")).sorted().toList()) {
                    String relativePackage = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()
                            .getParent()).toString().replace(java.io.File.separatorChar, '.');
                    if (recursive && options.excludes.stream().anyMatch(exclude -> relativePackage.equals(exclude)
                            || relativePackage.startsWith(exclude + "."))) continue;
                    // Javadoc package discovery ignores sources declaring a different package.
                    SourceFile source = SourceFile.read(file);
                    var lexed = new ironwood.compiler.lexer.Lexer(source).lex();
                    var parsed = new ironwood.compiler.parser.Parser(source, lexed.tokens()).parse();
                    if (parsed.unit().isPresent() && parsed.diagnostics().isEmpty() && lexed.diagnostics().isEmpty()
                            && !parsed.unit().orElseThrow().packageName().equals(relativePackage)) continue;
                    result.add(file.toRealPath());
                    found = true;
                }
            }
        }
        if (!found) throw new IllegalArgumentException("no source files found for package " + pkg);
    }

    private static void validatePackage(String name) {
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("invalid package name: " + name);
        }
    }

    private static final String HELP = """
            Usage: irondoc [options] [packagenames] [sourcefiles.iron] [@files]
            Generate GitHub-friendly IronDocs Markdown from Ironwood declarations.

              -d <directory>                 Output directory (default: current directory)
              -sourcepath, --source-path <path>
                                             Source roots, separated by the OS path separator;
                                             alone, recursively document every .iron source
              -subpackages <pkg:pkg>         Recursively document packages
              -exclude <pkg:pkg>             Exclude packages from -subpackages
              -public | -protected | -package | -private
                                             Declaration visibility (default: -protected)
              -doctitle <text>               Plain-text title (default: Ironwood API)
              --doc-version <text>          Version of the documented API (optional)
              -encoding <UTF-8>              Source encoding; UTF-8 only
              -docencoding <UTF-8>           Output encoding; UTF-8 only
              -charset <UTF-8>               Output character set; UTF-8 only
              -author                       Include @author sections
              -version                      Include @version sections
              -quiet                        Suppress progress messages
              --version, -v                 Print tool version
              --help, -help, -h, -?          Show this help

            Source paths in @files are relative to the working directory. Quoted paths
            and # comment lines are supported. Unsupported options and tags are errors.
            Initial subset: /** */ comments, declared members, common Javadoc tags.
            No HTML output, inherited documentation, DocLint, modules, or custom doclets.
            """;
}
