// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.Main;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class InliningOptionsTests {
    private InliningOptionsTests() {}

    public static void linkControls() throws Exception {
        reject("missing integer", "--inline-threshold");
        for (String value : List.of("-1", "2147483648", "1.5", "abc", "")) {
            reject("invalid --inline-threshold", "--link", "--inline-threshold", value);
        }
        reject("--inline-threshold requires --link", "--inline-threshold", "0");
        for (String mode : List.of("on", "off")) {
            reject("--selective-inlining requires --link", "--selective-inlining=" + mode);
        }
        reject("invalid --selective-inlining", "--link", "--selective-inlining=maybe");

        Path root = Files.createTempDirectory("ironwood-inline-options-");
        try {
            Path classes = root.resolve("classes");
            cli("integration-tests/cases/enum_argument_specialization.iron", "-d", classes.toString());
            LlvmToolchain tools = LlvmToolchain.discover(null).toolchain().orElseThrow();
            Path home = root.resolve("llvm");
            Path bin = Files.createDirectories(home.resolve("bin"));
            for (String tool : List.of("clang", "llvm-as", "llc", "llvm-objcopy", "llvm-config")) {
                Files.createSymbolicLink(bin.resolve(tool), tools.home().resolve("bin").resolve(tool));
            }
            Path capture = root.resolve("opt-args");
            Path wrapper = bin.resolve("opt");
            Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$@\" > " + quote(capture)
                    + "\nexec " + quote(tools.opt()) + " \"$@\"\n");
            require(wrapper.toFile().setExecutable(true), "cannot make opt wrapper executable");
            for (String budget : List.of("default", "0", "2000")) {
                Path llvm = root.resolve("program.ll");
                Path binary = root.resolve("program");
                var arguments = new ArrayList<>(List.of("--link", "-cp", classes.toString(),
                        "--main-class", "Main", "-O3", "--llvm-home", home.toString(),
                        "--selective-inlining=off", "--emit-llvm", llvm.toString(), "-o", binary.toString()));
                if (!budget.equals("default")) arguments.addAll(List.of("--inline-threshold", budget));
                cli(arguments.toArray(String[]::new));
                List<String> actual = Files.readAllLines(capture);
                require(actual.stream().filter(a -> a.startsWith("-inline-threshold=")).toList()
                        .equals(List.of("-inline-threshold=" + (budget.equals("default") ? "1000" : budget))),
                        "CLI budget did not reach opt exactly once: " + actual);
                require(actual.contains("-enable-partial-inlining"), "O3 partial inlining lost");
                String emitted = Files.readString(llvm);
                require(emitted.contains(".$enumarg."), "disabling 3B disabled 3A");
                require(emitted.contains("alwaysinline"), "initialization helper inlining lost");
                Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                require(process.waitFor() == 42 && output.isEmpty(), "enum fallback behavior: " + output);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static void cli(String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(args, stream, stream) == 0, output.toString());
        }
    }

    private static void reject(String diagnostic, String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(args, stream, stream) == 2 && output.toString().contains(diagnostic),
                    "missing option diagnostic: " + output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
