// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.ir.IrProcessInstruction;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ironwood.compiler.FilesystemServicesTests.accept;
import static ironwood.compiler.FilesystemServicesTests.delete;
import static ironwood.compiler.FilesystemServicesTests.execute;
import static ironwood.compiler.FilesystemServicesTests.links;
import static ironwood.compiler.FilesystemServicesTests.messages;
import static ironwood.compiler.FilesystemServicesTests.reject;
import static ironwood.compiler.FilesystemServicesTests.require;

/**
 * M4.3's synchronous process facility (D272): ProcessRunner.runToFile with
 * absolute executables, inherited environment, an optional child directory
 * and merged file output, against a controlled helper program, the native
 * runtime under injected failures and real signals, and the command
 * vector's audited borrowing contract.
 */
final class ProcessRunnerTests {
    private ProcessRunnerTests() { }

    /** The launch lowers to one typed process instruction with its runtime boundary and allocation effect. */
    static void typedOperations() {
        CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", """
                import ironwood.nio.file.Path;
                import ironwood.process.ProcessResult;
                import ironwood.process.ProcessRunner;
                class Main {
                    public static int main(String[] args) throws Exception {
                        String[] command = {"/usr/bin/true"};
                        Path output = Path.of(args[0]);
                        // The unused result must not let pruning drop the launch.
                        ProcessResult result = ProcessRunner.runToFile(command, null, output);
                        free result;
                        free output;
                        free command;
                        return 0;
                    }
                }
                """));
        require(artifact.successful(), messages(artifact));
        long launches = artifact.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream()).flatMap(block -> block.instructions().stream())
                .filter(IrProcessInstruction.class::isInstance).count();
        require(launches == 1, "expected one process instruction, found " + launches);
        String llvm = artifact.llvmIr().orElseThrow();
        require(llvm.contains("declare i64 @ironwood_process_run(ptr, ptr, ptr, ptr)")
                && llvm.contains("@ironwood_process_run(ptr "), "missing process runtime boundary");
        CompilationArtifact destructor = new CompilerPipeline().compile(SourceFile.of("test/Main.iron", """
                import ironwood.nio.file.Path;
                import ironwood.process.ProcessRunner;
                class Cleanup {
                    private Path output;
                    Cleanup(Path output) { this.output = output; }
                    destructor { String[] c = {"/usr/bin/true"}; try { ProcessRunner.runToFile(c, null, output); }
                                 catch (Exception ignored) { } free c; }
                }
                class Main { public static int main(String[] args) { return 0; } }
                """));
        require(!destructor.successful() && messages(destructor).contains("destructor may allocate"),
                "launch allocation effect lost: " + messages(destructor));
    }

    /**
     * The command array, its Strings and both Paths are borrowed for the call
     * in every unfreed mode; the result is fresh. An ordinary method that
     * receives a String[] still exposes its elements.
     */
    static void ownership() {
        String prefix = """
                import ironwood.nio.file.Path;
                import ironwood.process.ProcessResult;
                import ironwood.process.ProcessRunner;
                class Main { static String[] kept;
                static void take(String[] values) { }
                public static int main(String[] args) throws Exception {
                Path output = Path.of("o.log"); Path tool = Path.of("/bin/echo"); String fresh = "a" + args.length;
                """;
        String suffix = " free output; return 0; }}";
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Safe: a Path spelling and a fresh String placed in the command are freed after the call.
            accept(mode, prefix + "String[] c = {tool.toString(), fresh}; Path d = Path.of(\"/tmp\");"
                    + " ProcessResult r = ProcessRunner.runToFile(c, d, output); free r; free c; free d; free fresh;"
                    + " free tool;" + suffix);
            accept(mode, prefix + "String[] c = new String[2]; c[0] = tool.toString(); c[1] = fresh;"
                    + " ProcessResult r = ProcessRunner.runToFile(c, null, output); free r; free c; free fresh;"
                    + " free tool;" + suffix);
            // Unsafe: the result is freed once; an element freed while the array is live stays rejected.
            reject(mode, prefix + "String[] c = {\"/bin/echo\"}; ProcessResult r = ProcessRunner.runToFile(c, null,"
                    + " output); free r; free r; free c; free fresh; free tool;" + suffix, "freed");
            reject(mode, prefix + "String[] c = {\"/bin/echo\"}; ProcessResult r = ProcessRunner.runToFile(c, null,"
                    + " output); free r; free c; free fresh; free tool; return r.exitValue(); }}",
                    "after its allocation was freed");
            reject(mode, prefix + "String[] c = {tool.toString(), fresh}; free fresh; ProcessResult r ="
                    + " ProcessRunner.runToFile(c, null, output); free r; free c; free tool;" + suffix,
                    "fresh");
            // Publishing the array keeps its elements observable.
            reject(mode, prefix + "String[] c = {\"/bin/echo\", fresh}; kept = c; ProcessResult r ="
                    + " ProcessRunner.runToFile(c, null, output); free r; free fresh; free tool;" + suffix,
                    "cannot free 'fresh'");
            // The negative control: the contract covers runToFile alone.
            reject(mode, prefix + "String[] c = {\"/bin/echo\", fresh}; take(c); free c; free fresh; free tool;"
                    + suffix, "can observe reference-array elements");
        }
    }

    /**
     * Twenty-eight cases against the helper from class and archive links,
     * with an inherited variable and a PATH whose first entries are empty or
     * hold a decoy helper that must never run.
     */
    static void launches() throws Exception {
        Path root = Files.createTempDirectory("ironwood-process-runner-").toRealPath();
        try {
            Path helper = helper(root);
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            Path child = Files.createDirectories(scratch.resolve("child"));
            Files.writeString(scratch.resolve("plain.txt"), "plain");
            Files.write(scratch.resolve("garbage.bin"), new byte[]{0x7f, 'X', 'Y', 'Z', ' ', 'g'});
            Files.setPosixFilePermissions(scratch.resolve("garbage.bin"), PosixFilePermissions.fromString("rwx------"));
            Files.writeString(scratch.resolve("script.sh"), "#!/bin/sh\necho \"script:$1\"\n");
            Files.setPosixFilePermissions(scratch.resolve("script.sh"), PosixFilePermissions.fromString("rwx------"));
            Path decoys = Files.createDirectories(root.resolve("decoys"));
            Path marker = root.resolve("decoy-ran");
            for (String name : List.of("process_helper", "echo", "sleep")) {
                Files.writeString(decoys.resolve(name), "#!/bin/sh\ntouch " + marker + "\nexit 99\n");
                Files.setPosixFilePermissions(decoys.resolve(name), PosixFilePermissions.fromString("rwx------"));
            }
            Map<String, String> environment = Map.of("M4_PROBE", "v 🌲", "PATH", "::" + decoys + ":/usr/bin:/bin");
            String expected = String.join("\n", List.of(
                    "exit 0 -> exit 0 output ", "exit 3 -> exit 3 output ", "child exit 127 -> exit 127 output ",
                    "signal 15 -> signal 15 exit 143 output ", "signal 9 -> signal 9 exit 137 output ",
                    "literal argv -> exit 0 output [a b]|['q']|[\"d\"]|[$HOME]|[*]|[;|&]|[back\\slash]|[]|[🌲]|[a�b]|",
                    "NUL argument -> !IllegalArgumentException", "environment -> exit 0 output v 🌲|",
                    "child directory -> exit 0 output <s>/child|", "inherited directory -> exit 0 output <s>|",
                    "relative output -> exit 0 output <s>/child|",
                    "relative output -> written in the parent's directory", "stdin -> exit 0 output 0|",
                    "large output -> exit 0 size 2097152", "open descriptors -> exit 0 output 0|1|2|",
                    "missing executable -> !NoSuchFileException <s>/missing-tool",
                    "not executable -> !AccessDeniedException <s>/plain.txt: Permission denied",
                    "directory executable -> !AccessDeniedException <s>/child: Permission denied",
                    "unknown format -> !FileSystemException <s>/garbage.bin: Exec format error",
                    "shell script -> exit 0 output script:x y|",
                    "missing directory -> !NoSuchFileException <s>/missing-dir",
                    "file as directory -> !FileSystemException <s>/plain.txt: Not a directory",
                    "missing output parent -> !NoSuchFileException missing-dir/out.log",
                    "directory as output -> !FileSystemException child: Is a directory",
                    "empty command -> !IllegalArgumentException", "relative executable -> !IllegalArgumentException",
                    "repeated 300 launches -> descriptors stable")) + "\n";
            for (Path executable : links(root, "stdlib_process_runner", List.of())) {
                execute(List.of(executable.toString(), helper.toString(), scratch.toString(), child.toString(),
                        scratch.resolve("missing-tool").toString(), scratch.resolve("plain.txt").toString(),
                        scratch.resolve("garbage.bin").toString(), scratch.resolve("script.sh").toString(),
                        scratch.resolve("missing-dir").toString()), scratch, environment, null, 42, expected);
                require(Files.size(scratch.resolve("large.log")) == 2 * 1_048_576L, "large output size");
            }
            require(!Files.exists(marker), "a PATH decoy ran");
        } finally {
            delete(root);
        }
    }

    /** The runtime under injected failures, interrupted waits, repeated launches and inherited descriptors. */
    static void nativeHarness() throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-process-harness-").toRealPath();
        try {
            Path helper = helper(root);
            Path object = root.resolve("harness.o");
            Path caseObject = root.resolve("case.o");
            Path executable = root.resolve("harness");
            Path run = Files.createDirectories(root.resolve("run"));
            List<String> sysroot = sysroot();
            List<String> compile = new ArrayList<>(List.of(toolchain.clang().toString()));
            compile.addAll(sysroot);
            List<String> harness = new ArrayList<>(compile);
            harness.addAll(List.of("-std=c11", "-O3", "-c", "integration-tests/runtime/process_services.c", "-o",
                    object.toString()));
            FilesystemServicesTests.tool(harness);
            List<String> cases = new ArrayList<>(compile);
            cases.addAll(List.of("-std=c11", "-O3", "-c", "runtime/src/ironwood_case.c", "-o", caseObject.toString()));
            FilesystemServicesTests.tool(cases);
            List<String> link = new ArrayList<>(compile);
            link.addAll(List.of("--driver-mode=g++", object.toString(), caseObject.toString(), "-o", executable.toString()));
            FilesystemServicesTests.tool(link);
            execute(List.of(executable.toString(), run.toString(), helper.toString()), null, Map.of(), null, 0, "");
        } finally {
            delete(root);
        }
    }

    /**
     * A terminal interrupt delivered to the job's process group ends the
     * program and the tool it waits for; a signal sent to the program alone
     * ends only the program, the recorded boundary.
     */
    static void processGroup() throws Exception {
        Path root = Files.createTempDirectory("ironwood-process-group-").toRealPath();
        List<Long> leftovers = new ArrayList<>();
        try {
            Path helper = helper(root);
            Path program = links(root, "stdlib_process_group", List.of()).getFirst();
            for (String signal : List.of("INT", "TERM")) {
                Path pidFile = root.resolve(signal + ".pid");
                Process job = new ProcessBuilder(helper.toString(), "group", program.toString(), helper.toString(),
                        pidFile.toString()).directory(root.toFile()).redirectErrorStream(true).start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                long tool = Long.parseLong(Files.readString(pidFile).strip());
                leftovers.add(tool);
                long group = job.pid();
                // The group leader is the program; the helper sleeps in its group.
                require(ProcessHandle.of(tool).flatMap(ProcessHandle::parent).map(ProcessHandle::pid)
                        .orElse(-1L) == group, "the tool is not the program's child");
                String target = signal.equals("INT") ? "-" + group : Long.toString(group);
                new ProcessBuilder("/bin/kill", "-" + signal, "--", target).start().waitFor();
                require(job.waitFor(20, TimeUnit.SECONDS), signal + ": the program did not end");
                require(job.exitValue() == (signal.equals("INT") ? 130 : 143), signal + " exit " + job.exitValue());
                boolean toolEnded = waitGone(tool);
                if (signal.equals("INT")) {
                    require(toolEnded, "the interrupt left the tool running");
                } else {
                    require(!toolEnded, "a signal to the program alone also ended the tool");
                }
            }
        } finally {
            for (long tool : leftovers) ProcessHandle.of(tool).ifPresent(ProcessHandle::destroyForcibly);
            delete(root);
        }
    }

    /** Every allocation failure around a launch unwinds to the baseline and leaves no output file. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-process-failure-").toRealPath();
        try {
            Path helper = helper(root);
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            for (Path executable : links(root, "stdlib_process_runner_failure", List.of())) {
                List<String> command = List.of(executable.toString(), helper.toString(), scratch.toString());
                execute(command, root, Map.of(), null, 43, "");
                int limit = 0;
                while (execute(command, root, Map.of(), limit, -1, "") == 42) {
                    require(FilesystemServicesTests.entries(scratch).isEmpty(), "limit " + limit + " left output");
                    limit++;
                }
                require(limit >= 7 && execute(command, root, Map.of(), limit, 43, "") == 43,
                        "process OOM sweep ended at limit " + limit);
            }
        } finally {
            delete(root);
        }
    }

    // Waits up to five seconds for a process to disappear.
    private static boolean waitGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    // Compiles the controlled helper program.
    static Path helper(Path root) throws Exception {
        LlvmToolchain toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path helper = root.resolve("process_helper");
        List<String> command = new ArrayList<>(List.of(toolchain.clang().toString()));
        command.addAll(sysroot());
        command.addAll(List.of("-O2", "integration-tests/runtime/process_helper.c", "-o", helper.toString()));
        FilesystemServicesTests.tool(command);
        return helper;
    }

    // The Apple SDK for a host clang outside Xcode; Linux uses the toolchain default.
    private static List<String> sysroot() throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) return List.of();
        String sdk = System.getenv("SDKROOT");
        if (sdk == null || sdk.isBlank()) {
            Process process = new ProcessBuilder("/usr/bin/xcrun", "--show-sdk-path").start();
            sdk = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            require(process.waitFor() == 0, "xcrun failed");
        }
        return List.of("-isysroot", sdk);
    }
}
