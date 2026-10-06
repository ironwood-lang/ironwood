// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * M3.3 backend helpers (D268) and installation and build identity inputs
 * (D269): MD5 and pseudo-probe GUIDs, unsigned and binary helpers with the
 * trace-root order, LLVM text and scanners, the Properties subset, the
 * sysroot header check, launcher-provided installation discovery and the
 * generated build identity, each against the Java baseline's own code.
 */
final class BackendHelperTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final String EVIDENCE = "docs/self-hosting/m3/backend-evidence/";
    private static final String CLASSES = "compiler/build/classes";
    private static final List<String> HELPERS = List.of("Bytes", "HeaderScan", "Installation", "LibraryRoots",
            "LlvmScan", "LlvmText", "Md5", "PropertiesText", "Sha256");

    private BackendHelperTests() { }

    static void md5() throws Exception {
        Path root = Files.createTempDirectory("ironwood-md5-");
        try {
            compare(root, "compiler_md5", List.of("Md5"), reference(EVIDENCE + "Md5Reference.java", CLASSES,
                    List.of(), null, Map.of()), List.of(), 42);
        } finally {
            delete(root);
        }
    }

    static void binary() throws Exception {
        Path root = Files.createTempDirectory("ironwood-binary-");
        try {
            compare(root, "compiler_binary_helpers", List.of("Bytes"), reference(EVIDENCE + "BinaryReference.java",
                    null, List.of(), null, Map.of()), List.of(), 42);
        } finally {
            delete(root);
        }
    }

    static void llvmText() throws Exception {
        Path root = Files.createTempDirectory("ironwood-llvm-text-");
        try {
            compare(root, "compiler_llvm_text", List.of("LlvmText"), reference(EVIDENCE + "LlvmTextReference.java",
                    CLASSES, List.of(), null, Map.of()), List.of(), 42);
        } finally {
            delete(root);
        }
    }

    /**
     * The corpus holds a Clang target probe, an emitted module and its O3
     * optimization, their line-terminator variants, adversarial and seeded
     * texts; the reference applies the baseline's own patterns to each file.
     */
    static void llvmScan() throws Exception {
        Path root = Files.createTempDirectory("ironwood-llvm-scan-");
        try {
            LlvmToolchain tools = LlvmToolchain.discover(null).toolchain().orElseThrow();
            Path real = Files.createDirectories(root.resolve("real"));
            Path empty = real.resolve("empty.c");
            Files.writeString(empty, "");
            Path target = real.resolve("target.ll");
            tool(List.of(tools.clang().toString(), "-std=c11", "-S", "-emit-llvm", "-x", "c", empty.toString(), "-o",
                    target.toString()));
            Path classes = root.resolve("module");
            run(List.of(PORT + "LlvmScan.iron", PORT + "Sha256.iron", "integration-tests/cases/compiler_llvm_scan.iron",
                    "--unfreed=warn", "-d", classes.toString()));
            Path emitted = real.resolve("emitted.ll");
            run(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "--unfreed=warn", "-O3",
                    "--emit-llvm", emitted.toString(), "-o", real.resolve("module-program").toString()));
            Path optimized = real.resolve("optimized.ll");
            tool(List.of(tools.opt().toString(), "-passes=default<O3>", "-S", emitted.toString(), "-o",
                    optimized.toString()));
            List<String> corpus = corpus(root, "LlvmScanCorpus.java", List.of(target.toString(), emitted.toString(),
                    optimized.toString()));
            compare(root, "compiler_llvm_scan", List.of("LlvmScan", "Sha256"), reference(EVIDENCE
                    + "LlvmScanReference.java", CLASSES, corpus, null, Map.of()), corpus, 42);
        } finally {
            delete(root);
        }
    }

    static void properties() throws Exception {
        Path root = Files.createTempDirectory("ironwood-properties-");
        try {
            List<String> corpus = corpus(root, "PropertiesCorpus.java", List.of(
                    "packaging/tls-dependencies.properties", "packaging/java-bridge-support.properties"));
            compare(root, "compiler_properties_text", List.of("PropertiesText"), reference(EVIDENCE
                    + "PropertiesReference.java", null, corpus, null, Map.of()), corpus, 42);
        } finally {
            delete(root);
        }
    }

    static void headerScan() throws Exception {
        Path root = Files.createTempDirectory("ironwood-header-scan-");
        try {
            compare(root, "compiler_header_scan", List.of("HeaderScan"), reference(EVIDENCE
                    + "HeaderScanReference.java", null, List.of(), null, Map.of()), List.of(), 42);
        } finally {
            delete(root);
        }
    }

    /**
     * Each scenario runs the reference with the scenario's jar or class
     * directory as its class path and the native program with the canonical
     * location a launcher passes, in the same directory and environment.
     */
    static void installation() throws Exception {
        Path root = Files.createTempDirectory("ironwood-installation-");
        try {
            Path checkout = Path.of("").toAbsolutePath();
            Path classes = checkout.resolve(CLASSES);
            Path jar = checkout.resolve("compiler/build/ironwoodc.jar");
            // Canonical, as the launcher passes the location and Java's class path is.
            Path layouts = Files.createDirectories(root.resolve("layouts")).toRealPath();
            Path installed = Files.createDirectories(layouts.resolve("installed"));
            Files.createDirectories(installed.resolve("lib"));
            Files.createDirectories(installed.resolve("runtime/src"));
            Files.createDirectories(installed.resolve("stdlib/src/main/ironwood"));
            Files.writeString(installed.resolve("runtime/src/ironwood_runtime.c"), "");
            Path installedJar = Files.copy(jar, installed.resolve("lib/ironwoodc.jar"));
            Path bare = Files.createDirectories(layouts.resolve("bare"));
            Path bareJar = Files.copy(jar, bare.resolve("ironwoodc.jar"));
            Path empty = Files.createDirectories(layouts.resolve("empty"));
            record Scenario(Path directory, Path classPath, String location, Map<String, String> environment) { }
            List<Scenario> scenarios = List.of(
                    new Scenario(checkout, classes, classes.toString(), Map.of()),
                    new Scenario(layouts, jar, jar.toString(), Map.of()),
                    new Scenario(layouts, installedJar, installedJar.toString(), Map.of()),
                    new Scenario(checkout, installedJar, installedJar.toString(), Map.of()),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_RUNTIME_HOME",
                            installed.toString(), "IRONWOOD_STDLIB_HOME", installed.toString())),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_RUNTIME_HOME",
                            empty.toString(), "IRONWOOD_STDLIB_HOME", empty.toString())),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_RUNTIME_HOME", "  ",
                            "IRONWOOD_STDLIB_HOME", "")),
                    new Scenario(checkout.resolve("compiler"), classes, classes.toString(), Map.of(
                            "IRONWOOD_RUNTIME_HOME", "..", "IRONWOOD_STDLIB_HOME", "../compiler/./..")),
                    new Scenario(bare, bareJar, bareJar.toString(), Map.of()),
                    // An unknown location searches the current directory alone, as a
                    // code source that holds no installation does.
                    new Scenario(checkout, bareJar, "-", Map.of()));
            List<Path> executables = links(root, "compiler_installation", List.of("Installation", "LibraryRoots"));
            for (Scenario scenario : scenarios) {
                String expected = reference(EVIDENCE + "InstallationReference.java", scenario.classPath().toString(),
                        List.of(), scenario.directory(), scenario.environment());
                for (Path executable : executables) {
                    execute(List.of(executable.toString(), scenario.location()), scenario.directory(),
                            scenario.environment(), null, 42, expected);
                }
            }
        } finally {
            delete(root);
        }
    }

    /**
     * The generated identity equals CompilerVersion.current() of this build;
     * generation is byte-identical, honors IRONWOOD_VERSION, and fails with
     * build.sh's messages on an invalid or missing version.
     */
    static void buildIdentity() throws Exception {
        Path root = Files.createTempDirectory("ironwood-build-identity-");
        try {
            Path generated = root.resolve("BuildIdentity.iron");
            Path again = root.resolve("again.iron");
            String script = "scripts/self-hosting/build-identity.sh";
            execute(List.of(script, generated.toString()), null, Map.of(), null, 0, "");
            execute(List.of(script, again.toString()), null, Map.of(), null, 0, "");
            if (!java.util.Arrays.equals(Files.readAllBytes(generated), Files.readAllBytes(again))) {
                throw new AssertionError("build identity generation is not reproducible");
            }
            Path override = root.resolve("override.iron");
            execute(List.of(script, override.toString()), null, Map.of("IRONWOOD_VERSION", "1.2.3-rc.1"), null, 0, "");
            if (!Files.readString(override).contains("return \"1.2.3-rc.1\";")) {
                throw new AssertionError("build identity ignores IRONWOOD_VERSION");
            }
            execute(List.of(script, root.resolve("invalid.iron").toString()), null, Map.of("IRONWOOD_VERSION", "1.2"),
                    null, 1, "error: invalid compiler version '1.2'\n");
            Path missing = Files.createDirectories(root.resolve("missing"));
            execute(List.of(script, root.resolve("missing.iron").toString(), missing.toString()), null, Map.of(), null,
                    1, "error: missing compiler version file: " + missing + "/VERSION\n");
            String expected = "ironwoodc " + CompilerVersion.current() + "\n";
            Path classes = root.resolve("compiler_build_identity");
            run(List.of(generated.toString(), "integration-tests/cases/compiler_build_identity.iron", "--unfreed=warn",
                    "-d", classes.toString()));
            for (Path executable : archived(root, "compiler_build_identity", classes)) {
                execute(List.of(executable.toString()), null, Map.of(), null, 42, expected);
            }
        } finally {
            delete(root);
        }
    }

    static void ownership() throws Exception {
        List<SourceFile> helpers = new ArrayList<>();
        for (String helper : HELPERS) {
            helpers.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        String prefix = """
                import ironwood.compiler.port.*;
                import ironwood.nio.file.Path;
                class Main { public static int main(String[] args) throws ironwood.io.IOException {
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Digests own their state; results belong to the caller.
            require(mode, helpers, prefix + "Md5 digest = new Md5(); digest.updateUtf8(\"a\"); byte[] raw = digest.digest();"
                    + " free raw; long guid = digest.linkageGuid(\"main\"); free digest; return (int) guid; }}", null);
            require(mode, helpers, prefix + "Md5 digest = new Md5(); free digest; digest.update(1); return 0; }}",
                    "after its allocation was freed");
            require(mode, helpers, prefix + "Md5 digest = new Md5(); byte[] raw = digest.digest(); free raw; free raw;"
                    + " free digest; return 0; }}", "freed");
            // Slices are independent of their source.
            require(mode, helpers, prefix + "byte[] source = new byte[4]; byte[] slice = Bytes.slice(source, 1, 3);"
                    + " free source; int r = slice.length; free slice; return r; }}", null);
            require(mode, helpers, prefix + "byte[] source = new byte[4]; byte[] slice = Bytes.slice(source, 1, 3);"
                    + " free slice; free source; return slice.length; }}", "after its allocation was freed");
            // Text and scan results are fresh.
            require(mode, helpers, prefix + "String text = LlvmText.scientific(1.5); String hex = LlvmText.hexBits(1L);"
                    + " int r = text.length() + hex.length(); free hex; free text; return r; }}", null);
            require(mode, helpers, prefix + "String text = LlvmText.decodeSymbol(\"@main\"); free text;"
                    + " return text.length(); }}", "after its allocation was freed");
            require(mode, helpers, prefix + "String value = LlvmScan.specification(\"target triple = \\\"t\\\"\", \"triple\");"
                    + " free value; free value; return 0; }}", "freed");
            // A parse owns a copy: the caller frees its text at once, and keys outlive the parse.
            require(mode, helpers, prefix + "String text = \"k=v\".substring(0); PropertiesText parsed = PropertiesText.parse(text);"
                    + " free text; String key = parsed.key(0); free parsed; int r = key.length(); free key; return r; }}", null);
            require(mode, helpers, prefix + "PropertiesText parsed = PropertiesText.parse(\"k=v\"); free parsed;"
                    + " return parsed.size(); }}", "after its allocation was freed");
            // Library roots are lent by their owner.
            require(mode, helpers, prefix + "LibraryRoots roots = new LibraryRoots(); Installation.librarySourceRoots(null, roots);"
                    + " int r = roots.size() > 0 ? roots.root(0).toString().length() : 0; free roots; return r; }}", null);
            require(mode, helpers, prefix + "LibraryRoots roots = new LibraryRoots(); Installation.librarySourceRoots(null, roots);"
                    + " Path first = roots.root(0); free first; free roots; return 0; }}", "cannot free 'first'");
            require(mode, helpers, prefix + "StringBuilder error = new StringBuilder(); Path runtime ="
                    + " Installation.runtimeSource(null, error); free error; if (runtime != null) free runtime;"
                    + " return 0; }}", null);
        }
    }

    /** Every allocation failure unwinds to the baseline; a large enough limit succeeds. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-backend-helpers-failure-");
        try {
            for (Path executable : links(root, "compiler_backend_helpers_failure", List.of("Md5", "Bytes", "LlvmText",
                    "LlvmScan", "PropertiesText", "Installation", "LibraryRoots"))) {
                execute(List.of(executable.toString()), null, Map.of(), null, 43, "");
                int limit = 0;
                while (execute(List.of(executable.toString()), null, Map.of(), limit, -1, "") == 42) limit++;
                if (limit < 50 || execute(List.of(executable.toString()), null, Map.of(), limit, 43, "") != 43) {
                    throw new AssertionError("backend helper OOM sweep ended at limit " + limit);
                }
            }
        } finally {
            delete(root);
        }
    }

    private static void compare(Path root, String fixture, List<String> helpers, String expected,
                                List<String> arguments, int exit) throws Exception {
        for (Path executable : links(root, fixture, helpers)) {
            List<String> command = new ArrayList<>(List.of(executable.toString()));
            command.addAll(arguments);
            execute(command, null, Map.of(), null, exit, expected);
        }
    }

    private static List<String> corpus(Path root, String generator, List<String> inputs) throws Exception {
        Path directory = Files.createDirectories(root.resolve("corpus"));
        List<String> arguments = new ArrayList<>(List.of(directory.toString()));
        arguments.addAll(inputs);
        return List.of(reference(EVIDENCE + generator, null, arguments, null, Map.of()).split("\n"));
    }

    private static List<Path> links(Path root, String fixture, List<String> helpers) throws Exception {
        Path classes = root.resolve(fixture);
        List<String> arguments = new ArrayList<>();
        for (String helper : helpers) arguments.add(PORT + helper + ".iron");
        arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                "-d", classes.toString()));
        run(arguments);
        return archived(root, fixture, classes);
    }

    // Links the classes and their archive at O3.
    private static List<Path> archived(Path root, String fixture, Path classes) throws Exception {
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

    /** Runs a reference in a fresh JVM with inherited options cleared. */
    private static String reference(String path, String classPath, List<String> arguments, Path directory,
                                    Map<String, String> environment) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        if (classPath != null) command.addAll(List.of("-cp", classPath));
        command.add(Path.of(path).toAbsolutePath().toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "IRONWOOD_RUNTIME_HOME", "IRONWOOD_STDLIB_HOME")) {
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

    private static void tool(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(command.getFirst() + " failed: " + output);
        }
    }

    /** Compiles with no diagnostics at all. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("backend helper compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static int execute(List<String> command, Path directory, Map<String, String> environment, Integer limit,
                               int expectedExit, String expectedOutput) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        Map<String, String> variables = builder.environment();
        for (String variable : List.of("IRONWOOD_RUNTIME_HOME", "IRONWOOD_STDLIB_HOME", "IRONWOOD_VERSION")) {
            variables.remove(variable);
        }
        variables.putAll(environment);
        if (limit == null) variables.remove("IRONWOOD_ALLOCATION_LIMIT");
        else variables.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("backend helper program timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit || !output.equals(expectedOutput))) {
            throw new AssertionError(Path.of(command.getFirst()).getFileName() + " limit " + limit + ": exit "
                    + process.exitValue() + ", output " + output.length() + " characters, expected "
                    + expectedOutput.length());
        }
        return process.exitValue();
    }

    private static void require(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("backend helper ownership under " + mode + ": " + messages + "\n" + source);
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
