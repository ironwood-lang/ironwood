// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

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
 * Shared steps of the M5 differential tests: compile a fixture with port or
 * library sources, link it from its class directory and from an archive at
 * -O3, run it, run a Java 21 reference in a fresh JVM, compile ownership
 * controls in every unfreed mode, and sweep allocation limits.
 */
final class PortFixtures {
    static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    static final String CLASSES = "compiler/build/classes";

    private PortFixtures() { }

    /** Compiles with no diagnostics at all; port code compiles under --unfreed=warn. */
    static void compile(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    /** Compiles the fixture with its sources once, then links it from classes and from an archive. */
    static List<Path> links(Path root, String fixture, List<String> sources) throws Exception {
        Path classes = root.resolve(fixture);
        List<String> arguments = new ArrayList<>(sources);
        arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                "-d", classes.toString()));
        compile(arguments);
        Path archive = root.resolve(fixture + ".ironjar");
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                new PrintStream(ignored), new PrintStream(ignored)) != 0) {
            throw new AssertionError(fixture + " archive: " + ignored);
        }
        List<Path> executables = new ArrayList<>();
        for (Path input : List.of(classes, archive)) {
            Path executable = root.resolve(input.getFileName() + "-program");
            compile(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                    "-O3", "-o", executable.toString()));
            executables.add(executable);
        }
        return executables;
    }

    /** Runs a single-file Java reference in a fresh JVM with inherited options cleared. */
    static String reference(String path, List<String> classPath, List<String> arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        if (!classPath.isEmpty()) command.addAll(List.of("-cp", String.join(":", classPath)));
        command.add(path);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(600, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("reference " + path + " failed: " + output);
        }
        return output;
    }

    /**
     * Runs a native program and returns its exit status; with an expected exit
     * status of zero or more, the status and merged output must match.
     */
    static int execute(List<String> command, Integer limit, int expectedExit, String expectedOutput)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        if (limit == null) environment.remove("IRONWOOD_ALLOCATION_LIMIT");
        else environment.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(command.getFirst() + " timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit
                || expectedOutput != null && !output.equals(expectedOutput))) {
            throw new AssertionError(Path.of(command.getFirst()).getFileName() + " limit " + limit + ": exit "
                    + process.exitValue() + ", output " + output.length() + " characters: "
                    + output.substring(0, Math.min(output.length(), 2000)));
        }
        return process.exitValue();
    }

    /**
     * Raises the allocation limit from zero until the program completes: every
     * limit below that must report a handled failure (42) and the first one
     * reaching completion must report success (43).
     */
    static int sweep(Path executable, List<String> arguments, int minimum) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable.toString()));
        command.addAll(arguments);
        execute(command, null, 43, null);
        int limit = 0;
        while (execute(command, limit, -1, null) == 42) limit++;
        if (limit < minimum || execute(command, limit, 43, null) != 43) {
            throw new AssertionError(executable.getFileName() + " allocation sweep ended at limit " + limit);
        }
        return limit;
    }

    /** Compiles a control with helper sources: accepted when rejection is null, else rejected with that text. */
    static void require(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("ownership control under " + mode + ": " + messages + "\n" + source);
        }
    }

    /** Reads port helper sources as in-memory compilation inputs. */
    static List<SourceFile> portSources(List<String> names) throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        for (String name : names) {
            sources.add(SourceFile.of("test/" + name + ".iron", Files.readString(Path.of(PORT + name + ".iron"))));
        }
        return sources;
    }

    static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
