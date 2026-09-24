// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.semantic.SemanticObserverBridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class FreeDependencyDiagnosticTests {
    private static final String LIBRARY = """
            package lib;

            public class Sink {

                public void accept(byte[] value) {
                }

                public static void use(Sink sink) {

                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }
            }
            """;
    private static final String KEEPER = """
            package app;

            import lib.Sink;

            public class Keeper extends Sink {

                static byte[] kept;

                @Override
                public void accept(byte[] value) {

                    kept = value;
                }

                public static void main(String[] args) {

                    Sink.use(new Keeper());
                }
            }
            """;
    private static final String REJECTION =
            "error: cannot free 'data': allocation escapes through argument 1 of method 'accept'";

    private FreeDependencyDiagnosticTests() {}

    static void dependencySources() throws Exception {
        Path target = Path.of("integration-tests/target");
        Files.createDirectories(target);
        Path root = Files.createTempDirectory(target, "free-dependency-").toAbsolutePath();
        try {
            dependencySources(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void explanationSourceScope() throws Exception {
        Path target = Path.of("integration-tests/target");
        Files.createDirectories(target);
        Path root = Files.createTempDirectory(target, "free-explanation-dependency-").toAbsolutePath();
        try {
            Path librarySource = write(root.resolve("lib/src/lib/Sink.iron"), LIBRARY);
            Path keeperSource = write(root.resolve("app/src/app/Keeper.iron"), KEEPER);
            Path skippedSource = write(root.resolve("app-skipped/src/app/Keeper.iron"),
                    KEEPER.replace("    @Override\n", ""));
            Path libraryClasses = root.resolve("lib/classes");
            cli(0, "--unfreed=off", "-d", libraryClasses.toString(), librarySource.toString());
            Path libraryClass = libraryClasses.resolve("lib/Sink.ironclass");
            Path archive = root.resolve("lib/lib.ironjar");
            require(IronJarMain.run(new String[]{"--create", "--file", archive.toString(),
                            libraryClasses.toString()}, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())) == 0,
                    "archive creation failed");
            for (String kind : List.of("source", "class", "archive")) {
                Path dependency = kind.equals("archive") ? archive : libraryClasses;
                String display = switch (kind) {
                    case "source" -> librarySource.toString();
                    case "class" -> libraryClass + "!/source/Sink.iron";
                    default -> archive + "!/lib/Sink.ironclass!/source/Sink.iron";
                };
                SourceLoadResult loaded = new SourceSetLoader(
                        kind.equals("source")
                                ? List.of(root.resolve("app/src"), root.resolve("lib/src"))
                                : List.of(root.resolve("app/src")),
                        kind.equals("source") ? List.of() : List.of(dependency))
                        .load(List.of(keeperSource));
                require(loaded.diagnostics().isEmpty(), kind + " load failed: " + loaded.diagnostics());
                CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                        .analyze(loaded.sources());
                SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
                CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true,
                        (mode, sources, explain) -> SemanticObserverBridge.create(
                                mode, sources, explain, counts, Path.of(display)))
                        .analyze(loaded.sources());
                require(!off.valid() && !on.valid()
                                && off.diagnostics().stream().map(Diagnostic::message).toList().equals(
                                on.diagnostics().stream().map(Diagnostic::message).toList()),
                        kind + " changed primary diagnostics: " + on.diagnostics());
                Diagnostic primary = on.diagnostics().stream()
                        .filter(d -> d.message().startsWith("cannot free 'data':"))
                        .findFirst().orElseThrow();
                require(primary.source().path().toString().equals(display)
                                && primary.span().start().line() == 12
                                && primary.notes().size() == 1
                                && off.diagnostics().stream().allMatch(d -> d.notes().isEmpty()),
                        kind + " lost dependency source or eligibility: " + primary);
                require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                                && lowering.refinementCompleted() && !lowering.collectorPresent()),
                        kind + " dependency final lowering was not observed");

                SourceLoadResult skippedLoaded = new SourceSetLoader(
                        kind.equals("source")
                                ? List.of(root.resolve("app-skipped/src"), root.resolve("lib/src"))
                                : List.of(root.resolve("app-skipped/src")),
                        kind.equals("source") ? List.of() : List.of(dependency))
                        .load(List.of(skippedSource));
                require(skippedLoaded.diagnostics().isEmpty(),
                        kind + " skipped load failed: " + skippedLoaded.diagnostics());
                CompilationArtifact skippedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                        .analyze(skippedLoaded.sources());
                CompilationArtifact skippedOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                        .analyze(skippedLoaded.sources());
                require(skippedOff.diagnostics().stream().map(Diagnostic::message).toList().equals(
                                skippedOn.diagnostics().stream().map(Diagnostic::message).toList()),
                        kind + " skipped dependency changed primaries");
                Diagnostic limited = skippedOn.diagnostics().stream()
                        .filter(d -> d.message().startsWith("cannot free 'data':"))
                        .findFirst().orElseThrow();
                require(limited.source().path().toString().equals(display)
                                && limited.notes().size() == 1
                                && limited.notes().getFirst().message().equals(
                                "ownership analysis was limited because of earlier errors; "
                                        + "fix those first and recompile; this rejection may be secondary")
                                && skippedOn.diagnostics().stream()
                                        .filter(d -> d.message().contains("@Override"))
                                        .allMatch(d -> d.notes().isEmpty()),
                        kind + " dependency missed skipped-refinement boundary: "
                                + skippedOn.diagnostics());
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void dependencySources(Path root) throws Exception {
        Path librarySource = write(root.resolve("lib/src/lib/Sink.iron"), LIBRARY);
        Path keeperSource = write(root.resolve("app/src/app/Keeper.iron"), KEEPER);
        Path quietSource = write(root.resolve("app/src/app/Quiet.iron"),
                KEEPER.replace("Keeper", "Quiet").replace("kept = value;", ""));
        Path libraryClasses = root.resolve("lib/classes");
        Path appSourcePath = root.resolve("app/src");
        Path librarySourcePath = root.resolve("lib/src");
        cli(0, "--unfreed=off", "--source-path", librarySourcePath.toString(),
                "-d", libraryClasses.toString(), librarySource.toString());
        Path libraryClass = libraryClasses.resolve("lib/Sink.ironclass");
        require(Files.isRegularFile(libraryClass), "library did not compile independently");
        Path archive = root.resolve("lib/lib.ironjar");
        var archiveErrors = new ByteArrayOutputStream();
        require(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), libraryClasses.toString()},
                        new PrintStream(new ByteArrayOutputStream()), new PrintStream(archiveErrors)) == 0,
                "archive creation failed: " + archiveErrors.toString(StandardCharsets.UTF_8));

        // An earlier compatible library version does not invoke the override.
        // Both components compile legitimately; the final composition must recheck safety.
        Path previousSource = write(root.resolve("previous/src/lib/Sink.iron"),
                LIBRARY.replace("sink.accept(data);", ""));
        Path previousClasses = root.resolve("previous/classes");
        cli(0, "--unfreed=off", "-d", previousClasses.toString(), previousSource.toString());
        Path keeperClasses = root.resolve("keeper-built/classes");
        cli(0, "--unfreed=off", "--source-path", appSourcePath.toString(),
                "-cp", previousClasses.toString(), "-d", keeperClasses.toString(), keeperSource.toString());
        require(Files.isRegularFile(keeperClasses.resolve("app/Keeper.ironclass")),
                "Keeper did not compile against the earlier library");
        require(!Files.exists(keeperClasses.resolve("lib/Sink.ironclass")),
                "application output unexpectedly shadows the replacement dependency");

        for (String kind : List.of("source", "class", "archive")) {
            Path dependency = kind.equals("archive") ? archive : libraryClasses;
            String sourcePath = appSourcePath + (kind.equals("source")
                    ? File.pathSeparator + librarySourcePath : "");
            String classPath = kind.equals("source") ? root.resolve("absent").toString() : dependency.toString();
            String libraryDisplay = switch (kind) {
                case "source" -> librarySource.toString();
                case "class" -> libraryClass + "!/source/Sink.iron";
                default -> archive + "!/lib/Sink.ironclass!/source/Sink.iron";
            };
            Path rejectedClasses = root.resolve(kind + "-rejected");
            String errors = cli(1, "--unfreed=off", "--source-path", sourcePath, "-cp", classPath,
                    "-d", rejectedClasses.toString(), keeperSource.toString());
            rejectedAt(errors, libraryDisplay);
            require(!Files.exists(rejectedClasses), kind + " failure wrote class output");

            // Source identities already exist separately; future notes must preserve both.
            List<Path> sourceRoots = kind.equals("source")
                    ? List.of(appSourcePath, librarySourcePath) : List.of(appSourcePath);
            SourceLoadResult loaded = new SourceSetLoader(sourceRoots,
                    kind.equals("source") ? List.of() : List.of(dependency)).load(List.of(keeperSource));
            require(loaded.diagnostics().isEmpty(), "dependency load failed: " + loaded.diagnostics());
            require(loaded.sources().stream().anyMatch(source -> source.path().toString().equals(libraryDisplay)
                            && source.content().equals(licensed(LIBRARY))), kind + " lost library source identity/content");
            require(loaded.sources().stream().anyMatch(source -> source.path().equals(keeperSource)
                            && source.content().equals(licensed(KEEPER))), kind + " lost application source identity/content");

            Path quietClasses = root.resolve(kind + "-quiet");
            cli(0, "--unfreed=off", "--source-path", sourcePath, "-cp", classPath,
                    "-d", quietClasses.toString(), quietSource.toString());
            require(Files.isRegularFile(quietClasses.resolve("app/Quiet.ironclass")), kind + " missing Quiet class");
            String quietClassPath = quietClasses + File.pathSeparator + dependency;
            Path executable = root.resolve(kind + "-quiet-program");
            cli(0, "--link", "--unfreed=off", "-cp", quietClassPath,
                    "--main-class", "app.Quiet", "-o", executable.toString());
            Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(process.waitFor() == 0 && output.isEmpty(), kind + " Quiet failed: " + output);

            if (!kind.equals("source")) {
                Path rejectedExecutable = root.resolve(kind + "-keeper-program");
                Path rejectedLlvm = root.resolve(kind + "-keeper.ll");
                String linkErrors = cli(1, "--link", "--unfreed=off", "-cp",
                        keeperClasses + File.pathSeparator + dependency, "--main-class", "app.Keeper",
                        "--emit-llvm", rejectedLlvm.toString(), "-o", rejectedExecutable.toString());
                rejectedAt(linkErrors, libraryDisplay);
                require(!Files.exists(rejectedExecutable) && !Files.exists(rejectedLlvm),
                        kind + " unsafe link emitted output");
            }
        }
    }

    private static Path write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, licensed(source));
        return path;
    }

    private static String licensed(String source) {
        // Use the blank line after the package so diagnostic line numbers stay unchanged.
        require(source.contains("\n\n"), "fixture has no header space");
        return source.replaceFirst("\n\n", "\n// SPDX-License-Identifier: MIT OR Apache-2.0\n");
    }

    private static void rejectedAt(String errors, String source) {
        require(errors.lines().filter(line -> line.startsWith("error:")).count() == 1
                        && errors.contains(REJECTION) && errors.contains("--> " + source + ":12:14"),
                "wrong dependency diagnostic: " + errors);
        require(errors.contains("12 |         free data;"), "missing dependency source excerpt: " + errors);
    }

    private static String cli(int expected, String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        String text = errors.toString(StandardCharsets.UTF_8);
        require(result == expected, String.join(" ", arguments) + ": " + text);
        if (expected == 0) require(text.isEmpty(), "unexpected successful-command diagnostics: " + text);
        return text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
