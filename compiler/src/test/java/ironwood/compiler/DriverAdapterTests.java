// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ironwood.compiler.FilesystemServicesTests.delete;
import static ironwood.compiler.FilesystemServicesTests.entries;
import static ironwood.compiler.FilesystemServicesTests.execute;
import static ironwood.compiler.FilesystemServicesTests.links;
import static ironwood.compiler.FilesystemServicesTests.messages;
import static ironwood.compiler.FilesystemServicesTests.reference;
import static ironwood.compiler.FilesystemServicesTests.require;

/**
 * M4.3's native driver adapters in the compiler port (D273): the Command
 * argument owner, the invocation's Probes with temporary logs and reuse, the
 * port's ExecutableSearch and the LlvmPipeline that runs the actual llvm-as,
 * opt, llc and Clang pipeline through ProcessRunner.
 */
final class DriverAdapterTests {
    private static final String REFERENCE = "docs/self-hosting/m4/driver-evidence/DiscoveryReference.java";
    private static final List<String> ADAPTERS = List.of("Command", "Probes", "ProbeRecord", "TreeDeletion",
            "ExecutableSearch", "LlvmPipeline", "LlvmScan", "Sha256");

    private DriverAdapterTests() { }

    /** Arguments are owned by their Command, outputs lent by their Probes, in every unfreed mode. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = new ArrayList<>();
        for (String helper : ADAPTERS) {
            helpers.add(SourceFile.of("test/" + helper + ".iron",
                    Files.readString(Path.of(FilesystemServicesTests.PORT + helper + ".iron"))));
        }
        String prefix = """
                import ironwood.compiler.port.*;
                import ironwood.nio.file.Path;
                import ironwood.process.ProcessResult;
                class Main { public static int main(String[] args) throws Exception {
                """;
        String holder = """
                class Holder { private String[] values;
                    Holder(String value) { this.values = new String[1]; String fresh = copy(value);
                        this.values[0] = fresh; }
                    private static String copy(String value) { return new String(value); }
                    String joined() { return String.join(",", this.values); }
                    destructor { for (int index = 0; index < this.values.length; index++) { free this.values[index]; }
                                 free this.values; } }
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // The caller frees its own Strings; the command keeps copies.
            expect(mode, helpers, prefix + "String tool = \"/bin/echo\".substring(0); Path out = Path.of(\"o.log\");"
                    + " Command c = new Command(2); c.add(tool).add(out); free tool; ProcessResult r = c.run(null, out);"
                    + " free r; free c; free out; return 0; }}", null);
            expect(mode, helpers, prefix + "Command c = new Command(1); c.add(\"/bin/echo\"); String lent = c.argument(0);"
                    + " free lent; free c; return 0; }}", "cannot free 'lent'");
            expect(mode, helpers, prefix + "Command c = new Command(1); free c; c.add(\"/bin/echo\"); return 0; }}",
                    "after its allocation was freed");
            // A probe's output is lent by its context.
            expect(mode, helpers, prefix + "Probes p = new Probes(null); Command c = new Command(1); c.add(\"/usr/bin/true\");"
                    + " StringBuilder f = new StringBuilder(); String o = p.output(c, f); int r = o == null ? 1 : 0;"
                    + " p.close(); free f; free c; free p; return r; }}", null);
            expect(mode, helpers, prefix + "Probes p = new Probes(null); Command c = new Command(1); c.add(\"/usr/bin/true\");"
                    + " StringBuilder f = new StringBuilder(); String o = p.output(c, f); p.close(); free f; free c;"
                    + " free p; return o == null ? 1 : 0; }}", "cannot use 'o' after its allocation was freed");
            expect(mode, helpers, prefix + "Probes p = new Probes(null); Command c = new Command(1); c.add(\"/usr/bin/true\");"
                    + " StringBuilder f = new StringBuilder(); String o = p.output(c, f); free o; free f; free c; free p;"
                    + " return 0; }}", "cannot free 'o'");
            // Only ProcessRunner.runToFile may receive creation-array storage.
            expect(mode, helpers, prefix + "Holder h = new Holder(\"a\"); free h; return 0; }}\n" + holder,
                    "creation-array storage cannot be passed to an arbitrary call");
        }
    }

    /** The port's ExecutableSearch answers as the Java seed's does on the same trees. */
    static void executableSearch() throws Exception {
        Path root = Files.createTempDirectory("ironwood-port-search-").toRealPath();
        try {
            for (String directory : List.of("plain", "first", "second", "elsewhere/bin", "elsewhere/inner")) {
                Files.createDirectories(root.resolve(directory));
            }
            Files.createDirectories(root.resolve("directory/tool"));
            for (String file : List.of("plain/tool", "first/tool", "second/tool", "tool", "elsewhere/bin/tool")) {
                Files.writeString(root.resolve(file), "#!/bin/sh\n");
                Files.setPosixFilePermissions(root.resolve(file), PosixFilePermissions.fromString(
                        file.startsWith("plain") ? "rw-------" : "rwx------"));
            }
            Files.createSymbolicLink(root.resolve("link"), root.resolve("elsewhere/inner"));
            List<String> pairs = List.of("tool", root.resolve("plain") + ":" + root.resolve("directory") + ":"
                            + root.resolve("first") + ":" + root.resolve("second"),
                    "tool", "::" + root.resolve("second") + "::", "tool", ":", "tool", "second", "tool", "link/../bin",
                    "tool", "", "missing", root.resolve("first").toString(), "dir/tool", root.resolve("first").toString());
            // The working directory holds a tool too: an empty entry must not find it.
            List<String> arguments = new ArrayList<>(List.of("search", root.toString()));
            arguments.addAll(pairs);
            String expected = reference(REFERENCE, FilesystemServicesTests.CLASSES, arguments, null, Map.of())
                    .replace(root + "/", "");
            require(expected.equals("first/tool\nsecond/tool\n<none>\nsecond/tool\nlink/../bin/tool\n<none>\n"
                    + "<none>\n<none>\n"), "reference " + expected);
            List<String> command = new ArrayList<>(List.of(root.toString()));
            command.addAll(pairs);
            for (Path executable : links(root, "compiler_executable_search", List.of("ExecutableSearch"))) {
                List<String> run = new ArrayList<>(List.of(executable.toString()));
                run.addAll(command);
                String output = runCapture(run, null, Map.of()).replace(root + "/", "");
                require(output.equals(expected), "port search:\n" + output + "\nversus\n" + expected);
            }
        } finally {
            delete(root);
        }
    }

    /**
     * The probes answer as LlvmToolchain and MacNativeTools do; a repeated
     * probe launches nothing, a new invocation probes again, failures are
     * never kept, oversized output fails explicitly, and no log or scratch
     * directory remains.
     */
    static void discoveryProbes() throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-discovery-probes-").toRealPath();
        try {
            Path helper = ProcessRunnerTests.helper(root);
            Path temporary = Files.createDirectories(root.resolve("tmp"));
            String answers = reference(REFERENCE, FilesystemServicesTests.CLASSES,
                    List.of("toolchain", toolchain.home().toString()), null, Map.of());
            int probes = System.getProperty("os.name").startsWith("Mac") ? 6 : 2;
            String counters = "cold: launches " + probes + " logs " + probes + "/" + probes + " bytes read retained some\n"
                    + "repeated: launches " + probes + " logs " + probes + "/" + probes + " bytes read retained some\n"
                    + "fresh invocation: launches " + probes + " logs " + probes + "/" + probes
                    + " bytes read retained some\nmissing tool -> failed\nfailing probe twice -> launches 2\n"
                    + "oversized output -> failed explicitly\nafter failures: launches " + (probes + 3) + " logs "
                    + (probes + 4) + "/" + (probes + 4) + " bytes read retained some\n";
            for (Path executable : links(root, "compiler_discovery_probes", List.of("Command", "Probes", "ProbeRecord",
                    "TreeDeletion"))) {
                String output = runCapture(List.of(executable.toString(), toolchain.home().toString(), helper.toString()),
                        null, Map.of("TMPDIR", temporary.toString()));
                require(output.equals(answers + counters), "probes:\n" + output + "\nversus\n" + answers + counters);
                require(entries(temporary).isEmpty(), "probe scratch remains: " + entries(temporary));
            }
        } finally {
            delete(root);
        }
    }

    /**
     * The port's pipeline links the module the Java compiler emits into an
     * executable whose output, status and stack trace equal the Java link's,
     * from class and archive links of the adapter, and leaves nothing behind.
     */
    static void pipeline() throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-llvm-pipeline-").toRealPath();
        try {
            Path sample = Files.createDirectories(root.resolve("sample"));
            Files.writeString(sample.resolve("Main.iron"), SAMPLE);
            FilesystemServicesTests.run(List.of(sample.resolve("Main.iron").toString(), "-d",
                    sample.resolve("classes").toString()));
            Path module = sample.resolve("program.ll");
            Path javaProgram = sample.resolve("java-program");
            FilesystemServicesTests.run(List.of("--link", "-O3", "-cp", sample.resolve("classes").toString(),
                    "--main-class", "Main", "--emit-llvm", module.toString(), "-o", javaProgram.toString()));
            String expected = runAll(javaProgram);
            require(expected.startsWith("exit 42\n") && expected.contains("at Main.depth(Main.iron:"), expected);
            Path finalizer = finalizer(root);
            Path temporary = Files.createDirectories(root.resolve("tmp"));
            int run = 0;
            for (Path adapter : links(root, "compiler_llvm_pipeline", ADAPTERS)) {
                Path program = sample.resolve("native-program-" + run++);
                String report = runCapture(List.of(adapter.toString(), toolchain.home().toString(),
                        Path.of("runtime/src").toAbsolutePath().toString(), module.toString(), finalizer.toString(),
                        program.toString()), null, Map.of("TMPDIR", temporary.toString()));
                int probes = System.getProperty("os.name").startsWith("Mac") ? 2 : 0;
                require(report.equals("linked\nprobes " + probes + " logs " + probes + "/" + probes + "\n"), report);
                require(runAll(program).equals(expected), "native pipeline output differs:\n" + runAll(program));
                require(entries(temporary).isEmpty() && noStaging(sample), "pipeline leftovers");
            }
        } finally {
            delete(root);
        }
    }

    /** A failing stage is named with the tool's output; no output and no staging remain. */
    static void pipelineFailures() throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-llvm-pipeline-failure-").toRealPath();
        try {
            Path work = Files.createDirectories(root.resolve("work"));
            Path temporary = Files.createDirectories(root.resolve("tmp"));
            Files.writeString(work.resolve("broken.ll"), "garbage\n");
            Path badFinalizer = work.resolve("bad-final.sh");
            Files.writeString(badFinalizer, "#!/bin/sh\necho finalizer broke\nexit 1\n");
            Files.setPosixFilePermissions(badFinalizer, PosixFilePermissions.fromString("rwx------"));
            Path finalizer = finalizer(root);
            Path adapter = links(root, "compiler_llvm_pipeline", ADAPTERS).getFirst();
            String runtime = Path.of("runtime/src").toAbsolutePath().toString();
            String home = toolchain.home().toString();
            record Failure(String home, String module, Path finalizer, String start) { }
            for (Failure failure : List.of(
                    new Failure(home, work.resolve("broken.ll").toString(), finalizer,
                            "LLVM IR assembly failed with exit code 1:\n"),
                    new Failure("/no/llvm", work.resolve("broken.ll").toString(), finalizer,
                            "cannot run native target discovery: /no/llvm/bin/clang\n"),
                    new Failure(home, emptyModule(root), badFinalizer,
                            "stack-trace metadata finalization failed with exit code 1:\nfinalizer broke\n"))) {
                Path program = work.resolve("program");
                String report = runCapture(List.of(adapter.toString(), failure.home(), runtime, failure.module(),
                        failure.finalizer().toString(), program.toString()), null, Map.of("TMPDIR", temporary.toString()));
                require(report.startsWith(failure.start()) && !Files.exists(program) && noStaging(work)
                        && entries(temporary).isEmpty(), "failure report:\n" + report);
            }
        } finally {
            delete(root);
        }
    }

    /** Every allocation failure in the adapters unwinds to the baseline and leaves no log or scratch directory. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-driver-adapters-failure-").toRealPath();
        try {
            Path helper = ProcessRunnerTests.helper(root);
            Path temporary = Files.createDirectories(root.resolve("tmp"));
            Map<String, String> environment = Map.of("TMPDIR", temporary.toString());
            for (Path executable : links(root, "compiler_driver_adapters_failure", List.of("Command", "Probes",
                    "ProbeRecord", "TreeDeletion", "ExecutableSearch"))) {
                List<String> command = List.of(executable.toString(), helper.toString());
                execute(command, root, environment, null, 43, "");
                int limit = 0;
                while (execute(command, root, environment, limit, -1, "") == 42) {
                    require(entries(temporary).isEmpty(), "limit " + limit + " left " + entries(temporary));
                    limit++;
                }
                require(limit >= 40 && execute(command, root, environment, limit, 43, "") == 43
                        && entries(temporary).isEmpty(), "adapter OOM sweep ended at limit " + limit);
            }
        } finally {
            delete(root);
        }
    }

    private static final String SAMPLE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            class Main {
                static int depth(int value) {
                    if (value == 0) throw new IllegalStateException("bottom");
                    return depth(value - 1) + 1;
                }
                public static int main(String[] args) {
                    StringBuilder text = new StringBuilder();
                    for (int index = 0; index < 5; index++) text.append(index * 7).append(' ');
                    System.out.println(text.toString());
                    free text;
                    try {
                        depth(3);
                    } catch (IllegalStateException failure) {
                        failure.printStackTrace();
                    }
                    return 42;
                }
            }
            """;

    // S4's native finalization mode stands here as the Java baseline's own inject.
    private static Path finalizer(Path root) throws Exception {
        Path script = root.resolve("finalize.sh");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Files.writeString(script, "#!/bin/sh\nexec '" + java + "' -cp '"
                + Path.of(FilesystemServicesTests.CLASSES).toAbsolutePath() + "' '"
                + Path.of("docs/self-hosting/m4/driver-evidence/TraceFinalizer.java").toAbsolutePath() + "' \"$1\" \"$2\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    // A valid module, so the failing finalizer is the first stage to fail.
    private static String emptyModule(Path root) throws Exception {
        Path module = root.resolve("empty.ll");
        Files.writeString(module, "define i32 @main() {\n  ret i32 0\n}\n");
        return module.toString();
    }

    private static boolean noStaging(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.noneMatch(path -> path.getFileName().toString().startsWith(".ironwoodc-native-"));
        }
    }

    private static String runAll(Path program) throws Exception {
        Process process = new ProcessBuilder(program.toString()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(60, TimeUnit.SECONDS), "program timed out");
        return "exit " + process.exitValue() + "\n" + out + "--\n" + err;
    }

    private static String runCapture(List<String> command, Path directory, Map<String, String> environment)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().remove("TMPDIR");
        builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(600, TimeUnit.SECONDS) && process.exitValue() == 42,
                command.getFirst() + " exit " + process.exitValue() + ": " + output);
        return output;
    }

    private static void expect(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = messages(artifact);
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("driver adapter ownership under " + mode + ": " + messages + "\n" + source);
        }
    }
}
