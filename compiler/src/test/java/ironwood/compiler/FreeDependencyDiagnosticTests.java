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
import java.util.Arrays;
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
            Path cliOutput = root.resolve("explained-classes");
            String cliNotes = cli(1, "--unfreed=off", "--explain-rejected-free",
                    "-cp", libraryClasses.toString(), "-d", cliOutput.toString(),
                    keeperSource.toString());
            require(cliNotes.contains(libraryClass + "!/source/Sink.iron")
                            && cliNotes.contains(keeperSource.toString())
                            && cliNotes.contains("Keeper.kept")
                            && !Files.exists(cliOutput),
                    "classpath composition lost the cross-file call chain: " + cliNotes);
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
                                && primary.notes().size() >= 2
                                && primary.notes().size() <= 8
                                && primary.notes().getFirst().source().path().toString()
                                .equals(display)
                                && primary.notes().getFirst().span().start().line() == 11
                                && primary.notes().getLast().source().path().equals(keeperSource)
                                && primary.notes().getLast().span().start().line() == 12
                                && primary.notes().getLast().message().contains("Keeper.kept")
                                && off.diagnostics().stream().allMatch(d -> d.notes().isEmpty()),
                        kind + " lost dependency source or eligibility: " + primary);
                require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                                && lowering.refinementCompleted() && lowering.collectorPresent()),
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

    static void artifactMatrix() throws Exception {
        Path target = Path.of("integration-tests/target");
        Files.createDirectories(target);
        Path root = Files.createTempDirectory(target, "free-artifact-matrix-").toAbsolutePath();
        try {
            artifactMatrix(root);
            identicalBasenames(root);
            traceControl(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void artifactMatrix(Path root) throws Exception {
        Path librarySource = write(root.resolve("lib/src/lib/Sink.iron"), LIBRARY);
        Path keeperSource = write(root.resolve("app/src/app/Keeper.iron"), KEEPER);
        String quiet = KEEPER.replace("Keeper", "Quiet").replace("kept = value;", "")
                .replace("public static void main", "public static int main")
                .replace("Sink.use(new Quiet());", """
                        long before = System.liveAllocationCount();
                                Quiet quiet = new Quiet();
                                Sink.use(quiet);
                                free quiet;
                                return System.liveAllocationCount() == before ? 42 : 1;""");
        Path quietSource = write(root.resolve("app/src/app/Quiet.iron"), quiet);
        Path libraryClasses = root.resolve("lib/classes");
        cli(0, "--unfreed=off", "--source-path", root.resolve("lib/src").toString(),
                "-d", libraryClasses.toString(), librarySource.toString());
        Path libraryClass = libraryClasses.resolve("lib/Sink.ironclass");
        Path libraryArchive = root.resolve("lib/lib.ironjar");
        archive(libraryClasses, libraryArchive);
        Path previousSource = write(root.resolve("previous/src/lib/Sink.iron"),
                LIBRARY.replace("sink.accept(data);", ""));
        Path previousClasses = root.resolve("previous/classes");
        cli(0, "--unfreed=off", "-d", previousClasses.toString(), previousSource.toString());
        Path keeperClasses = root.resolve("keeper-built/classes");
        cli(0, "--unfreed=off", "-cp", previousClasses.toString(),
                "-d", keeperClasses.toString(), keeperSource.toString());
        require(!Files.exists(keeperClasses.resolve("lib/Sink.ironclass")),
                "earlier library shadowed the final dependency");

        for (String kind : List.of("source", "class", "archive")) {
            Path dependency = kind.equals("archive") ? libraryArchive : libraryClasses;
            String sourcePath = root.resolve("app/src") + (kind.equals("source")
                    ? File.pathSeparator + root.resolve("lib/src") : "");
            String classPath = kind.equals("source") ? root.resolve("absent").toString()
                    : dependency.toString();
            String libraryDisplay = switch (kind) {
                case "source" -> librarySource.toString();
                case "class" -> libraryClass + "!/source/Sink.iron";
                default -> libraryArchive + "!/lib/Sink.ironclass!/source/Sink.iron";
            };
            Path rejected = root.resolve(kind + "-rejected");
            String off = cli(1, "--unfreed=off", "--source-path", sourcePath,
                    "-cp", classPath, "-d", rejected.resolve("off").toString(),
                    keeperSource.toString());
            String on = cli(1, "--unfreed=off", "--explain-rejected-free",
                    "--source-path", sourcePath, "-cp", classPath,
                    "-d", rejected.resolve("on").toString(), keeperSource.toString());
            rejectionPair(kind + " compile", off, on, libraryDisplay,
                    keeperSource.toString(), "Keeper.kept", 12);
            require(!Files.exists(rejected), kind + " rejected compile emitted classes");

            Path offClasses = root.resolve(kind + "-quiet-off");
            Path onClasses = root.resolve(kind + "-quiet-on");
            cli(0, "--unfreed=off", "--source-path", sourcePath, "-cp", classPath,
                    "-d", offClasses.toString(), quietSource.toString());
            cli(0, "--unfreed=off", "--explain-rejected-free", "--source-path", sourcePath,
                    "-cp", classPath, "-d", onClasses.toString(), quietSource.toString());
            equalTree(offClasses, onClasses, kind + " accepted class bytes");
            Path offArchive = root.resolve(kind + "-quiet-off.ironjar");
            Path onArchive = root.resolve(kind + "-quiet-on.ironjar");
            archive(offClasses, offArchive);
            archive(onClasses, onArchive);
            require(Arrays.equals(Files.readAllBytes(offArchive), Files.readAllBytes(onArchive)),
                    kind + " accepted archive bytes changed");
            Path llvm = root.resolve(kind + "-quiet.ll");
            byte[] offIr = linkAndRun(root.resolve(kind + "-quiet-native"), llvm,
                    offClasses + File.pathSeparator + (kind.equals("source") ? offClasses : dependency),
                    "app.Quiet", false, 42);
            byte[] onIr = linkAndRun(root.resolve(kind + "-quiet-native"), llvm,
                    onClasses + File.pathSeparator + (kind.equals("source") ? onClasses : dependency),
                    "app.Quiet", true, 42);
            require(Arrays.equals(offIr, onIr), kind + " accepted LLVM changed");

            if (!kind.equals("source")) {
                Path executable = root.resolve(kind + "-rejected-native");
                Path rejectedIr = root.resolve(kind + "-rejected.ll");
                String linkPath = keeperClasses + File.pathSeparator + dependency;
                String linkOff = cli(1, "--link", "--unfreed=off", "-cp", linkPath,
                        "--main-class", "app.Keeper", "--emit-llvm", rejectedIr.toString(),
                        "-o", executable.toString());
                String linkOn = cli(1, "--link", "--unfreed=off", "--explain-rejected-free",
                        "-cp", linkPath, "--main-class", "app.Keeper", "--emit-llvm",
                        rejectedIr.toString(), "-o", executable.toString());
                rejectionPair(kind + " link", linkOff, linkOn, libraryDisplay,
                        keeperClasses.resolve("app/Keeper.ironclass") + "!/source/Keeper.iron",
                        "Keeper.kept", 12);
                require(!Files.exists(executable) && !Files.exists(rejectedIr),
                        kind + " rejected link emitted executable or LLVM");
            }
        }
        String invalidSourcePath = cli(2, "--link", "--main-class", "app.Quiet",
                "--source-path", root.resolve("lib/src").toString());
        String invalidSource = cli(2, "--link", "--main-class", "app.Quiet",
                quietSource.toString());
        require(invalidSourcePath.contains("error:") && invalidSource.contains("error:"),
                "invalid link inputs were accepted");
    }

    private static void identicalBasenames(Path root) throws Exception {
        Path library = write(root.resolve("same/lib/src/lib/Same.iron"),
                LIBRARY.replace("Sink", "Same"));
        Path application = write(root.resolve("same/app/src/app/Same.iron"),
                KEEPER.replace("Sink", "Same").replace("Keeper", "Same")
                        .replace("import lib.Same;", "// Fully qualify the dependency with the same basename.")
                        .replace("extends Same", "extends lib.Same")
                        .replace("Same.use(", "lib.Same.use(")
                        .replace("kept = value;", "\n        kept = value;"));
        Path classes = root.resolve("same/lib/classes");
        cli(0, "--unfreed=off", "-d", classes.toString(), library.toString());
        Path archive = root.resolve("same/lib/lib.ironjar");
        archive(classes, archive);
        for (String kind : List.of("source", "class", "archive")) {
            String sourcePath = root.resolve("same/app/src") + (kind.equals("source")
                    ? File.pathSeparator + root.resolve("same/lib/src") : "");
            String classPath = kind.equals("source") ? root.resolve("absent").toString()
                    : (kind.equals("class") ? classes : archive).toString();
            String libraryDisplay = switch (kind) {
                case "source" -> library.toString();
                case "class" -> classes.resolve("lib/Same.ironclass") + "!/source/Same.iron";
                default -> archive + "!/lib/Same.ironclass!/source/Same.iron";
            };
            String off = cli(1, "--unfreed=off", "--source-path", sourcePath,
                    "-cp", classPath, "-d", root.resolve("same/" + kind + "-off").toString(),
                    application.toString());
            String on = cli(1, "--unfreed=off", "--explain-rejected-free",
                    "--source-path", sourcePath, "-cp", classPath,
                    "-d", root.resolve("same/" + kind + "-on").toString(), application.toString());
            rejectionPair("same basename " + kind, off, on, libraryDisplay,
                    application.toString(), "Same.kept", 13);
        }
    }

    private static void traceControl(Path root) throws Exception {
        Path source = write(root.resolve("trace/Trace.iron"), """
                class Trace {

                    static void fail() { throw new RuntimeException("trace control"); }
                    public static void main(String[] args) { fail(); }
                }
                """);
        Path offClasses = root.resolve("trace/off");
        Path onClasses = root.resolve("trace/on");
        cli(0, "--unfreed=off", "-d", offClasses.toString(), source.toString());
        cli(0, "--unfreed=off", "--explain-rejected-free", "-d", onClasses.toString(),
                source.toString());
        equalTree(offClasses, onClasses, "trace class bytes");
        TraceResult first = linkAndRunTrace(root.resolve("trace/native"), root.resolve("trace/out.ll"),
                offClasses, false);
        TraceResult second = linkAndRunTrace(root.resolve("trace/native"), root.resolve("trace/out.ll"),
                onClasses, true);
        require(Arrays.equals(first.ir(), second.ir()) && first.stderr().equals(second.stderr()),
                "trace LLVM or native exception output changed");
    }

    private static void rejectionPair(String label, String off, String on,
                                      String library, String application, String store, int storeLine) {
        rejectedAt(off, library);
        rejectedAt(on, library);
        int note = on.indexOf("note:");
        require(!off.contains("note:") && note >= 0 && on.substring(0, note).equals(off)
                        && on.contains("--> " + library + ":11:21")
                        && on.contains("--> " + application + ":" + storeLine + ":16")
                        && on.contains(store)
                        && on.contains("11 |         sink.accept(data);")
                        && on.contains(storeLine + " |         kept = value;"),
                label + " lost primary parity or per-note source/excerpt: " + on);
    }

    private static void archive(Path classes, Path file) throws Exception {
        var errors = new ByteArrayOutputStream();
        require(IronJarMain.run(new String[]{"--create", "--file", file.toString(),
                        classes.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors)) == 0, "archive failed: " + errors);
    }

    private static void equalTree(Path off, Path on, String label) throws Exception {
        try (var paths = Files.walk(off)) {
            List<Path> files = paths.filter(Files::isRegularFile).map(off::relativize).sorted().toList();
            try (var other = Files.walk(on)) {
                require(files.equals(other.filter(Files::isRegularFile).map(on::relativize)
                                .sorted().toList()), label + " inventory changed");
            }
            for (Path path : files) require(Arrays.equals(Files.readAllBytes(off.resolve(path)),
                    Files.readAllBytes(on.resolve(path))), label + " changed " + path);
        }
    }

    private static byte[] linkAndRun(Path executable, Path llvm, String classPath,
                                     String main, boolean explain, int expected) throws Exception {
        if (explain) cli(0, "--link", "--unfreed=off", "--explain-rejected-free",
                "-cp", classPath, "--main-class", main, "--emit-llvm", llvm.toString(),
                "-o", executable.toString(), "-O3");
        else cli(0, "--link", "--unfreed=off", "-cp", classPath,
                "--main-class", main, "--emit-llvm", llvm.toString(),
                "-o", executable.toString(), "-O3");
        byte[] ir = Files.readAllBytes(llvm);
        Process process = new ProcessBuilder(executable.toString()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == expected && out.isEmpty() && err.isEmpty(),
                "native behavior/reclamation changed: " + out + err);
        return ir;
    }

    private static TraceResult linkAndRunTrace(Path executable, Path llvm, Path classes,
                                               boolean explain) throws Exception {
        if (explain) cli(0, "--link", "--unfreed=off", "--explain-rejected-free",
                "-cp", classes.toString(), "--main-class", "Trace", "--emit-llvm",
                llvm.toString(), "-o", executable.toString(), "-O3");
        else cli(0, "--link", "--unfreed=off", "-cp", classes.toString(),
                "--main-class", "Trace", "--emit-llvm", llvm.toString(),
                "-o", executable.toString(), "-O3");
        byte[] ir = Files.readAllBytes(llvm);
        Process process = new ProcessBuilder(executable.toString()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 1 && out.isEmpty()
                        && err.contains("uncaught Ironwood exception: ironwood.lang.RuntimeException: trace control")
                        && err.contains("Trace.fail(Trace.iron:"),
                "native exception trace changed: " + out + err);
        return new TraceResult(ir, err);
    }

    private record TraceResult(byte[] ir, String stderr) {}

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
