// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class FieldAliasTests {
    private static final Path FIXTURE = Path.of("integration-tests/cases/field_aliases.iron");

    private FieldAliasTests() {}

    static void safety() throws Exception {
        String safe = Files.readString(FIXTURE);
        var covariance = new CompilerPipeline().compile(SourceFile.of("Covariance.iron", """
                class Main {
                    public static int main(String[] args) {
                        Object[] values = new String[1];
                        return values.length;
                    }
                }
                """));
        require(!covariance.valid() && covariance.diagnostics().stream().anyMatch(d -> d.isError()
                && d.message().contains("Object[]") && d.message().contains("String[]")),
                "array covariance crossed its compile-time boundary");
        var result = new CompilerPipeline().compile(SourceFile.of(FIXTURE.toString(), safe));
        require(result.successful(), result.diagnostics().toString());
    }

    static void nativeArtifacts() throws Exception {
        Path root = Path.of("integration-tests/target/field-aliases").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(FIXTURE.toString(), "-d", classes.toString());
        Path archive = root.resolve("fields.ironjar");
        IronJar.create(archive, List.of(classes));
        for (Path input : List.of(classes, archive)) {
            Path executable = root.resolve(input.equals(classes) ? "classes-program" : "archive-program");
            cli("--link", "-cp", input.toString(), "--main-class", "Main", "-O3", "-march=native", "-o", executable.toString());
            for (List<String> args : List.of(List.of(executable.toString()),
                    List.of(executable.toString(), "one", "two"))) {
                Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                require(exit == 42 && output.isEmpty(), args + " exited " + exit + ": " + output);
            }
        }
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(result == 0, String.join(" ", arguments) + ": " + errors.toString(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
