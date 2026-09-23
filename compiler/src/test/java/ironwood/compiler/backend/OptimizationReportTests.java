// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.Main;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class OptimizationReportTests {
    private OptimizationReportTests() {}

    public static void reports() throws Exception {
        expect(2, "missing file path", "--optimization-report");
        expect(2, "missing file path", "--optimization-report", "");
        expect(2, "missing file path", "--optimization-report", "--link");
        expect(2, "invalid file path", "--optimization-report", "bad\u0000path");
        expect(2, "--optimization-report requires --link", "--optimization-report", "report.yaml");
        Path root = Files.createTempDirectory("ironwood report '");
        try {
            Path classes = root.resolve("classes");
            expect(0, "built", "integration-tests/cases/selective_inlining.iron", "-d", classes.toString());
            LlvmToolchain tools = LlvmToolchain.discover(null).toolchain().orElseThrow();
            Path home = root.resolve("llvm");
            Path bin = Files.createDirectories(home.resolve("bin"));
            for (String tool : List.of("clang", "llvm-as", "llvm-objcopy", "llvm-config")) {
                Files.createSymbolicLink(bin.resolve(tool), tools.home().resolve("bin").resolve(tool));
            }
            Path optArguments = root.resolve("opt-arguments");
            Path optimized = root.resolve("optimized.ll");
            Path object = root.resolve("program.o");
            captureTool(bin.resolve("opt"), tools.opt(), optArguments, optimized);
            captureTool(bin.resolve("llc"), tools.llc(), root.resolve("llc-arguments"), object);
            Path binary = root.resolve("program");
            Path llvm = root.resolve("program.ll");
            List<String> base = List.of("--link", "-cp", classes.toString(), "--main-class", "Main",
                    "--llvm-home", home.toString(), "--emit-llvm", llvm.toString(), "-o", binary.toString());
            Path report = root.resolve("reports/optimization.yaml");
            for (List<String> settings : List.of(List.of("-O0"), List.of("-O3"),
                    List.of("-O3", "--inline-threshold", "4000", "--partial-inlining=off"))) {
                var arguments = new ArrayList<>(base);
                arguments.addAll(settings);
                expect(0, "built", arguments.toArray(String[]::new));
                List<String> baselineArguments = options(Files.readAllLines(optArguments));
                require(baselineArguments.stream().noneMatch(a -> a.contains("remarks")),
                        "reporting enabled by default");
                String baselineIr = normalizedIr(optimized);
                byte[] baselineObject = normalizedObject(tools, object);
                String baselineFailure = run(binary, 1, "fail");
                require(run(binary, 42).isEmpty(), "unexpected normal output");
                arguments.addAll(List.of("--optimization-report", report.toString()));
                expect(0, "built", arguments.toArray(String[]::new));
                List<String> reportArguments = options(Files.readAllLines(optArguments));
                require(reportArguments.contains("-pass-remarks-output=" + report), "report path lost");
                require(reportArguments.contains("-pass-remarks-format=yaml"), "report format lost");
                require(reportArguments.contains("-remarks-section=false"), "remark metadata not disabled");
                require(reportArguments.stream().filter(a -> !a.startsWith("-pass-remarks-")
                        && !a.startsWith("-remarks-section=")).toList().equals(baselineArguments),
                        "report changed optimization flags");
                require(Files.isRegularFile(report), "report was not written");
                String yaml = Files.readString(report);
                if (settings.contains("-O3")) {
                    require(yaml.startsWith("--- !") && yaml.contains("Pass:") && yaml.contains("Function:"),
                            "missing LLVM YAML remarks: " + yaml);
                }
                require(normalizedIr(optimized).equals(baselineIr), "report changed optimized IR");
                require(Arrays.equals(normalizedObject(tools, object), baselineObject),
                        "report changed the generated native object");
                require(run(binary, 42).isEmpty() && run(binary, 1, "fail").equals(baselineFailure),
                        "report changed execution or exception traces");
                Files.delete(report);
            }
            byte[] savedBinary = Files.readAllBytes(binary);
            String savedLlvm = Files.readString(llvm);
            for (Path collision : List.of(binary, llvm, root.resolve("nested/../program"))) {
                failReport(base, collision, "must differ");
            }
            Path alias = root.resolve("alias");
            Files.createSymbolicLink(alias, root);
            failReport(base, alias.resolve("program"), "must differ");
            Path hardLink = root.resolve("hard-link");
            Files.createLink(hardLink, binary);
            failReport(base, hardLink, "must differ");
            require(Arrays.equals(savedBinary, Files.readAllBytes(binary))
                    && savedLlvm.equals(Files.readString(llvm)), "collision modified another output");
            failReport(base, root.resolve("reports"), "not a regular file");
            Path blocked = root.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            failReport(base, blocked.resolve("report.yaml"), "cannot prepare optimization report");
            Path broken = root.resolve("broken-report");
            Files.createSymbolicLink(broken, root.resolve("absent/report.yaml"));
            failReport(base, broken, "LLVM optimization failed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void captureTool(Path wrapper, Path tool, Path arguments, Path output) throws Exception {
        Files.writeString(wrapper, "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$@\" > " + quote(arguments)
                + "\n" + quote(tool) + " \"$@\"\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  if [ \"$1\" = -o ]; then cp \"$2\" " + quote(output) + "; exit 0; fi\n"
                + "  shift\ndone\nexit 1\n");
        require(wrapper.toFile().setExecutable(true), "cannot make tool wrapper executable");
    }

    private static List<String> options(List<String> arguments) {
        // The final four arguments are -S, the temporary input, -o, and the temporary output.
        return arguments.subList(0, arguments.size() - 4);
    }

    private static String normalizedIr(Path path) throws Exception {
        return Files.readString(path).replaceFirst("(?m)^; ModuleID = .*\\n", "");
    }

    private static byte[] normalizedObject(LlvmToolchain tools, Path object) throws Exception {
        // Probe record ordering can differ between llc processes. Compare all other
        // bytes, including instructions, relocations and unwind tables; execute the
        // unmodified binaries separately to verify the actual exception traces.
        Path normalized = object.resolveSibling("normalized.o");
        Process process = new ProcessBuilder(tools.llvmObjcopy().toString(),
                "--remove-section=__PSEUDO_PROBE,__probes", "--remove-section=.pseudo_probe",
                object.toString(), normalized.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "cannot normalize probe records: " + output);
        return Files.readAllBytes(normalized);
    }

    private static void failReport(List<String> base, Path report, String diagnostic) {
        var arguments = new ArrayList<>(base);
        arguments.addAll(List.of("--optimization-report", report.toString()));
        expect(1, diagnostic, arguments.toArray(String[]::new));
    }

    private static String run(Path binary, int status, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(binary.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == status, "unexpected exit status: " + output);
        return output;
    }

    private static void expect(int status, String diagnostic, String... arguments) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(arguments, stream, stream) == status && output.toString().contains(diagnostic),
                    "unexpected compiler result: " + output);
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
