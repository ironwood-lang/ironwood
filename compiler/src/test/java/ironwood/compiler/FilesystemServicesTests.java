// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * M4.1 filesystem services (D270): deletion, temporary files and directories
 * with secure names, real paths, access checks and no-follow attributes in
 * the standard library, and the port's post-order tree deletion. Native
 * behavior is compared with Java 21 on a prepared fixture tree; the native
 * runtime is also exercised under injected host failures.
 */
final class FilesystemServicesTests {
    static final String EVIDENCE = "docs/self-hosting/m4/scratch-evidence/";
    static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    static final String CLASSES = "compiler/build/classes";

    private FilesystemServicesTests() { }

    /** Each new member lowers through its typed file operation and runtime boundary. */
    static void typedOperations() {
        CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", """
                import ironwood.nio.file.Files;
                import ironwood.nio.file.Path;
                import ironwood.nio.file.attribute.BasicFileAttributes;
                class Main {
                    public static int main(String[] args) throws Exception {
                        Path dir = Path.of(args[0]);
                        Path file = Files.createTempFile(dir, "a", ".b");
                        Path directory = Files.createTempDirectory(dir, "c");
                        Path fallback = Files.createTempFile(null, null);
                        Path defaulted = Files.createTempDirectory(null);
                        Path real = file.toRealPath();
                        BasicFileAttributes attributes = Files.readAttributesNoFollow(real);
                        int result = Files.isReadable(file) && Files.isExecutable(directory) ? 1 : 0;
                        if (Files.deleteIfExists(file)) result++;
                        free attributes;
                        free real;
                        free defaulted;
                        free fallback;
                        free directory;
                        free file;
                        free dir;
                        return result;
                    }
                }
                """));
        require(artifact.successful(), messages(artifact));
        Set<IrFileInstruction.Operation> operations = artifact.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .filter(IrFileInstruction.class::isInstance).map(IrFileInstruction.class::cast)
                .map(IrFileInstruction::operation).collect(Collectors.toUnmodifiableSet());
        for (IrFileInstruction.Operation operation : List.of(IrFileInstruction.Operation.CREATE_TEMP_FILE,
                IrFileInstruction.Operation.CREATE_TEMP_DIRECTORY, IrFileInstruction.Operation.REAL_PATH,
                IrFileInstruction.Operation.ACCESS, IrFileInstruction.Operation.DELETE,
                IrFileInstruction.Operation.READ_ATTRIBUTES)) {
            require(operations.contains(operation), operation + " did not lower through typed IR");
        }
        String llvm = artifact.llvmIr().orElseThrow();
        for (String boundary : List.of("@ironwood_file_create_temp_file", "@ironwood_file_create_temp_directory",
                "@ironwood_file_real_path", "@ironwood_file_access", "@ironwood_file_delete")) {
            require(llvm.contains(boundary), "missing runtime boundary " + boundary);
        }
        // The operations allocate, so a destructor may not reach them.
        for (String call : List.of("Files.createTempFile(path, null, null)", "path.toRealPath()",
                "Files.isReadable(path)")) {
            CompilationArtifact destructor = new CompilerPipeline().compile(SourceFile.of("test/Main.iron", """
                    import ironwood.nio.file.Files;
                    import ironwood.nio.file.Path;
                    class Cleanup {
                        private Path path;
                        Cleanup(Path path) { this.path = path; }
                        destructor { try { %s; } catch (Exception ignored) { } }
                    }
                    class Main { public static int main(String[] args) { return 0; } }
                    """.formatted(call)));
            require(!destructor.successful() && messages(destructor).contains("destructor may allocate"),
                    "destructor allocation effect lost for " + call + ": " + messages(destructor));
        }
    }

    /** Inputs are borrowed for the call; results are fresh and caller-owned in every mode. */
    static void ownership() {
        String prefix = """
                import ironwood.nio.file.Files;
                import ironwood.nio.file.Path;
                import ironwood.nio.file.attribute.BasicFileAttributes;
                class Main { static Path kept;
                public static int main(String[] args) throws Exception {
                Path dir = Path.of(args[0]);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Safe: inputs freed after each call, every fresh result freed once.
            accept(mode, prefix + "Path t = Files.createTempFile(dir, \"a\", null); Path d = Files.createTempDirectory(dir, null);"
                    + " free dir; boolean gone = Files.deleteIfExists(t); free t; free d; return gone ? 0 : 1; }}");
            accept(mode, prefix + "Path t = Files.createTempFile(null, \"b\"); Path d = Files.createTempDirectory(\"p\");"
                    + " free t; free d; free dir; return 0; }}");
            accept(mode, prefix + "Path r = dir.toRealPath(); free dir; BasicFileAttributes a = Files.readAttributesNoFollow(r);"
                    + " boolean link = a.isSymbolicLink(); free a; free r; return link ? 1 : 0; }}");
            accept(mode, prefix + "boolean r = Files.isReadable(dir) && Files.isExecutable(dir); free dir; return r ? 0 : 1; }}");
            // Unsafe: double free, use after free and freeing a retained result stay rejected.
            reject(mode, prefix + "Path t = Files.createTempFile(dir, null, null); free t; free t; free dir; return 0; }}",
                    "freed");
            reject(mode, prefix + "Path d = Files.createTempDirectory(dir, null); free d; free dir; return d.getNameCount(); }}",
                    "after its allocation was freed");
            reject(mode, prefix + "Path r = dir.toRealPath(); free r; free dir; return r.getNameCount(); }}",
                    "after its allocation was freed");
            reject(mode, prefix + "BasicFileAttributes a = Files.readAttributesNoFollow(dir); free a; free a; free dir;"
                    + " return 0; }}", "freed");
            reject(mode, prefix + "Path t = Files.createTempFile(dir, null, null); Path alias = t; free alias; free t;"
                    + " free dir; return 0; }}", "may still be observed");
            reject(mode, prefix + "Path t = Files.createTempFile(dir, null, null); kept = t; free t; free dir;"
                    + " return 0; }}", "cannot free 't'");
            reject(mode, prefix + "free dir; return Files.isReadable(dir) ? 0 : 1; }}", "after its allocation was freed");
        }
        // A result that is never freed is reported, so each result is classified fresh.
        for (String call : List.of("Files.createTempFile(dir, null, null)", "Files.createTempDirectory(null)",
                "dir.toRealPath()", "Files.readAttributesNoFollow(dir)")) {
            CompilationArtifact leaked = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron",
                    prefix + "Object kept = " + call + "; free dir; return 0; }}"));
            require(!leaked.successful() && messages(leaked).contains("without being freed"),
                    "missing free not reported for " + call + ": " + messages(leaked));
        }
    }

    /**
     * The fixture compares every case with Java 21 from classes and archive
     * links, then the created entries' names, kinds and modes, then the
     * default directory under each TMPDIR setting.
     */
    static void javaDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-scratch-paths-");
        try {
            List<Path> executables = links(root, "stdlib_scratch_paths", List.of());
            Path javaTree = fixture(root.resolve("java"));
            String expected = reference(EVIDENCE + "ScratchPathsReference.java", null,
                    List.of("cases", javaTree.toString()), javaTree, Map.of());
            require(expected.lines().count() == 74, "unexpected reference size " + expected.lines().count());
            String javaEntries = entries(javaTree.resolve("tmp"));
            int run = 0;
            for (Path executable : executables) {
                Path tree = fixture(root.resolve("native-" + run++));
                execute(List.of(executable.toString(), "cases", tree.toString()), tree, Map.of(), null, 42, expected);
                require(entries(tree.resolve("tmp")).equals(javaEntries), "created entries differ: "
                        + entries(tree.resolve("tmp")) + " versus " + javaEntries);
            }
            require(javaEntries.contains("rw-------") && javaEntries.contains("rwx------")
                    && !javaEntries.contains("rw-r"), "temporary permissions: " + javaEntries);
            unlock(root);
            Path tmpdir = Files.createDirectories(root.resolve("tmpdir"));
            Files.createDirectories(root.resolve("rel"));
            record Scenario(Map<String, String> environment, String javaTmpdir) { }
            List<Scenario> scenarios = List.of(new Scenario(Map.of(), "/tmp"), new Scenario(Map.of("TMPDIR", ""), "/tmp"),
                    new Scenario(Map.of("TMPDIR", tmpdir.toString()), tmpdir.toString()),
                    new Scenario(Map.of("TMPDIR", tmpdir + "/"), tmpdir.toString()),
                    new Scenario(Map.of("TMPDIR", "rel"), "rel"),
                    new Scenario(Map.of("TMPDIR", root.resolve("missing").toString()), root.resolve("missing").toString()));
            for (Scenario scenario : scenarios) {
                String defaulted = reference(EVIDENCE + "ScratchPathsReference.java", null, List.of("default",
                        root.toString()), root, scenario.environment(), "-Djava.io.tmpdir=" + scenario.javaTmpdir());
                require(defaulted.startsWith(scenario.javaTmpdir().contains("missing") ? "default !NoSuchFileException"
                        : "default in-tmpdir "), "default scenario " + scenario + ": " + defaulted);
                for (Path executable : executables) {
                    execute(List.of(executable.toString(), "default", root.toString()), root, scenario.environment(),
                            null, 42, defaulted);
                }
            }
            require(entries(tmpdir).isEmpty() && entries(root.resolve("rel")).isEmpty(), "default entries remain");
        } finally {
            unlock(root);
            delete(root);
        }
    }

    /** Every allocation failure unwinds to the baseline and leaves no created entry. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-scratch-failure-");
        try {
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            Map<String, String> environment = Map.of("TMPDIR", scratch.toString());
            for (Path executable : links(root, "stdlib_scratch_paths_failure", List.of())) {
                List<String> command = List.of(executable.toString(), scratch.toString());
                execute(command, root, environment, null, 43, "");
                int limit = 0;
                while (execute(command, root, environment, limit, -1, "") == 42) {
                    require(entries(scratch).isEmpty(), "limit " + limit + " left " + entries(scratch));
                    limit++;
                }
                require(limit >= 20 && execute(command, root, environment, limit, 43, "") == 43
                        && entries(scratch).isEmpty(), "scratch OOM sweep ended at limit " + limit);
            }
        } finally {
            delete(root);
        }
    }

    /** The runtime functions under injected collisions, close, allocation and random-source failures. */
    static void nativeHarness() throws Exception {
        runtimeHarness("filesystem_services.c");
    }

    /** TreeDeletion against NativeBackend.deleteTree and Bridge staging cleanup on the same trees. */
    static void treeDeletion() throws Exception {
        Path root = Files.createTempDirectory("ironwood-tree-deletion-");
        try {
            List<Path> executables = links(root, "compiler_tree_deletion", List.of("TreeDeletion"));
            int run = 0;
            for (String mode : List.of("quiet", "propagate")) {
                for (String scenario : List.of("nested", "empty", "links", "missing", "rootlink", "rootfile",
                        "readonly", "locked")) {
                    Path javaParent = trees(root.resolve("java-" + run), scenario);
                    String expected = reference(EVIDENCE + "TreeDeletionReference.java", CLASSES,
                            List.of(mode, javaParent.resolve("root").toString()), null, Map.of());
                    unlock(javaParent);
                    String javaRemaining = remaining(javaParent);
                    boolean failure = scenario.equals("readonly") || scenario.equals("locked");
                    for (Path executable : executables) {
                        Path parent = trees(root.resolve("native-" + run++), scenario);
                        String outcome = scenario.equals("locked") && mode.equals("quiet") ? "ok\n" : expected;
                        execute(List.of(executable.toString(), mode, parent.resolve("root").toString()), null, Map.of(),
                                null, 42, outcome);
                        unlock(parent);
                        String nativeRemaining = remaining(parent);
                        if (scenario.equals("locked") && mode.equals("quiet")) {
                            // Java's stream raises UncheckedIOException and deletes nothing;
                            // the quiet walk skips the unreadable subtree and deletes the rest.
                            require(expected.equals("failed\n") && javaRemaining.contains("./root/a.txt")
                                    && nativeRemaining.equals(". ./keep.txt ./keepdir ./keepdir/k.txt ./root"
                                    + " ./root/locked ./root/locked/inner ./root/locked/inner/f"),
                                    "quiet locked: " + nativeRemaining);
                        } else if (failure && mode.equals("propagate")) {
                            // Sibling order differs; the failing entry and its ancestors remain.
                            String kept = scenario.equals("readonly") ? "./root/ro/f" : "./root/locked/inner/f";
                            require(expected.equals("failed\n") && nativeRemaining.contains(kept), scenario + ": "
                                    + nativeRemaining);
                        } else {
                            require(nativeRemaining.equals(javaRemaining), mode + " " + scenario + ": "
                                    + nativeRemaining + " versus " + javaRemaining);
                        }
                    }
                    run++;
                }
            }
        } finally {
            unlock(root);
            delete(root);
        }
    }

    // The fixture tree of stdlib_scratch_paths.iron.
    private static Path fixture(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("file.txt"), "text");
        Files.writeString(root.resolve("exec.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(root.resolve("exec.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(root.resolve("secret.txt"), "secret");
        for (String directory : List.of("empty", "full", "nested/deep", "tmp", "🌲 space", "ro", "locked")) {
            Files.createDirectories(root.resolve(directory));
        }
        Files.writeString(root.resolve("full/inner.txt"), "inner");
        Files.writeString(root.resolve("nested/x"), "x");
        Files.writeString(root.resolve("notdir.txt"), "n");
        Files.writeString(root.resolve("locked/child.txt"), "c");
        Files.createSymbolicLink(root.resolve("link-file"), Path.of("file.txt"));
        Files.createSymbolicLink(root.resolve("link-full"), Path.of("full"));
        Files.createSymbolicLink(root.resolve("link-deep"), Path.of("nested/deep"));
        Files.createSymbolicLink(root.resolve("link-exec"), Path.of("exec.sh"));
        Files.createSymbolicLink(root.resolve("dangling"), Path.of("missing-target"));
        Files.createSymbolicLink(root.resolve("loop-a"), Path.of("loop-b"));
        Files.createSymbolicLink(root.resolve("loop-b"), Path.of("loop-a"));
        Files.setPosixFilePermissions(root.resolve("secret.txt"), Set.of());
        Files.setPosixFilePermissions(root.resolve("ro"), PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.setPosixFilePermissions(root.resolve("locked"), Set.of());
        return root;
    }

    // The tree-deletion scenario under parent, with targets outside root.
    private static Path trees(Path parent, String scenario) throws IOException {
        Files.createDirectories(parent.resolve("keepdir"));
        Files.writeString(parent.resolve("keep.txt"), "k");
        Files.writeString(parent.resolve("keepdir/k.txt"), "k");
        Path root = parent.resolve("root");
        switch (scenario) {
            case "nested" -> {
                Files.createDirectories(root.resolve("a/d"));
                Files.createDirectories(root.resolve("a/e/f"));
                for (String file : List.of("a/b", "a/c", "a/e/f/g", "🌲 x")) Files.writeString(root.resolve(file), file);
            }
            case "empty" -> Files.createDirectories(root);
            case "links" -> {
                Files.createDirectories(root);
                Files.createSymbolicLink(root.resolve("link-file"), Path.of("../keep.txt"));
                Files.createSymbolicLink(root.resolve("link-dir"), Path.of("../keepdir"));
                Files.createSymbolicLink(root.resolve("dangling"), Path.of("missing"));
            }
            case "missing" -> { }
            case "rootlink" -> Files.createSymbolicLink(root, Path.of("keepdir"));
            case "rootfile" -> Files.writeString(root, "f");
            case "readonly" -> {
                Files.createDirectories(root.resolve("ro"));
                Files.createDirectories(root.resolve("b"));
                Files.writeString(root.resolve("a.txt"), "1");
                Files.writeString(root.resolve("ro/f"), "2");
                Files.writeString(root.resolve("b/z"), "3");
                Files.setPosixFilePermissions(root.resolve("ro"), PosixFilePermissions.fromString("r-xr-xr-x"));
            }
            case "locked" -> {
                Files.createDirectories(root.resolve("locked/inner"));
                Files.createDirectories(root.resolve("b"));
                Files.writeString(root.resolve("a.txt"), "1");
                Files.writeString(root.resolve("locked/inner/f"), "2");
                Files.setPosixFilePermissions(root.resolve("locked"), Set.of());
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        return parent;
    }

    // Sorted relative entries of a tree, links not followed.
    private static String remaining(Path parent) throws IOException {
        try (var paths = Files.walk(parent)) {
            return paths.map(path -> "." + (path.equals(parent) ? "" : "/" + parent.relativize(path)))
                    .sorted().collect(Collectors.joining(" "));
        }
    }

    // Masked names, kinds and modes of a directory's entries, sorted.
    static String entries(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return "";
        TreeMap<String, Integer> counted = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString().replaceAll("[0-9]{10,}", "<n>");
                String kind = Files.isSymbolicLink(path) ? "link" : Files.isDirectory(path) ? "dir" : "file";
                counted.merge(name + " " + kind + " "
                        + PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), 1, Integer::sum);
            }
        }
        return counted.entrySet().stream().map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    // Restores owner access so a tree can be listed and deleted; a locked
    // directory becomes listable only after its own permissions change.
    static void unlock(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        boolean[] again = {true};
        while (again[0]) {
            again[0] = false;
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isSymbolicLink()) {
                        Set<java.nio.file.attribute.PosixFilePermission> permissions =
                                new java.util.HashSet<>(Files.getPosixFilePermissions(file));
                        permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
                        permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(file, permissions);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure)
                        throws IOException {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
                    again[0] = true;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static void runtimeHarness(String fixture) throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-runtime-harness-");
        try {
            Path object = root.resolve("harness.o");
            Path caseObject = root.resolve("case.o");
            Path executable = root.resolve("harness");
            Path run = Files.createDirectories(root.resolve("run"));
            tool(List.of(toolchain.clang().toString(), "-std=c11", "-O3", "-c", "integration-tests/runtime/" + fixture,
                    "-o", object.toString()));
            tool(List.of(toolchain.clang().toString(), "-std=c11", "-O3", "-c", "runtime/src/ironwood_case.c",
                    "-o", caseObject.toString()));
            tool(List.of(toolchain.clang().toString(), "--driver-mode=g++", object.toString(), caseObject.toString(),
                    "-o", executable.toString()));
            execute(List.of(executable.toString(), run.toString()), null, Map.of(), null, 0, "");
        } finally {
            unlock(root);
            delete(root);
        }
    }

    static List<Path> links(Path root, String fixture, List<String> helpers) throws Exception {
        Path classes = root.resolve(fixture);
        List<String> arguments = new ArrayList<>();
        for (String helper : helpers) arguments.add(PORT + helper + ".iron");
        arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                "-d", classes.toString()));
        run(arguments);
        Path archive = root.resolve(fixture + ".ironjar");
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                new PrintStream(ignored), new PrintStream(ignored)) != 0) {
            throw new AssertionError(fixture + " archive: " + ignored);
        }
        List<Path> executables = new ArrayList<>();
        for (Path input : List.of(classes, archive)) {
            Path executable = root.resolve(input.getFileName() + "-program");
            run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                    "-O3", "-o", executable.toString()));
            executables.add(executable);
        }
        return executables;
    }

    /** Runs a Java reference in a fresh JVM with inherited options cleared. */
    static String reference(String path, String classPath, List<String> arguments, Path directory,
                            Map<String, String> environment, String... options) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        command.addAll(List.of(options));
        if (classPath != null) command.addAll(List.of("-cp", Path.of(classPath).toAbsolutePath().toString()));
        command.add(Path.of(path).toAbsolutePath().toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (directory != null) builder.directory(directory.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "TMPDIR")) {
            builder.environment().remove(variable);
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("reference " + path + " failed: " + output);
        }
        return output;
    }

    static void tool(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(command.getFirst() + " failed: " + output);
        }
    }

    /** Compiles with no diagnostics at all. */
    static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    static int execute(List<String> command, Path directory, Map<String, String> environment, Integer limit,
                       int expectedExit, String expectedOutput) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (directory != null) builder.directory(directory.toFile());
        Map<String, String> variables = builder.environment();
        variables.remove("TMPDIR");
        variables.putAll(environment);
        if (limit == null) variables.remove("IRONWOOD_ALLOCATION_LIMIT");
        else variables.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(command.getFirst() + " timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit || !output.equals(expectedOutput))) {
            throw new AssertionError(Path.of(command.getFirst()).getFileName() + " " + command.subList(1,
                    command.size()) + " limit " + limit + ": exit " + process.exitValue() + "\n" + firstDifference(
                    expectedOutput, output));
        }
        return process.exitValue();
    }

    private static String firstDifference(String expected, String actual) {
        List<String> left = expected.lines().toList();
        List<String> right = actual.lines().toList();
        for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
            String a = index < left.size() ? left.get(index) : "<end>";
            String b = index < right.size() ? right.get(index) : "<end>";
            if (!a.equals(b)) return "line " + (index + 1) + ": expected [" + a + "] actual [" + b + "]";
        }
        return "outputs equal";
    }

    static void accept(UnfreedMode mode, String source) {
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", source));
        require(artifact.successful(), "rejected under " + mode + ": " + messages(artifact) + "\n" + source);
    }

    static void reject(UnfreedMode mode, String source, String rejection) {
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", source));
        require(!artifact.successful() && messages(artifact).contains(rejection), "not rejected under " + mode
                + " with '" + rejection + "': " + messages(artifact) + "\n" + source);
    }

    static String messages(CompilationArtifact artifact) {
        return String.join("; ", artifact.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList());
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void delete(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
