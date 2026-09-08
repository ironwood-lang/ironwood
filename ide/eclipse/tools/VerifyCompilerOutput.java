// SPDX-License-Identifier: MIT OR Apache-2.0

// Checks that the builder can still read ironwoodc's diagnostics.
//
// The Eclipse builder runs the compiler as a process and recovers errors from
// its printed output, so a change to the compiler's diagnostic rendering would
// silently stop producing markers. This runs the real compiler over a source
// file with known errors and asserts that each one is recovered with its
// message and position intact.
//
//   java -cp ide/eclipse/target/classes \
//       ide/eclipse/tools/VerifyCompilerOutput.java <ironwoodc> <work-directory>

import ironwood.ide.eclipse.CompilerOutputParser;
import ironwood.ide.eclipse.CompilerOutputParser.CompilerDiagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class VerifyCompilerOutput {

    // Two errors with distinct shapes: one whose message quotes names and one
    // that is a plain unresolved reference.
    private static final String BROKEN_SOURCE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0

            package check;

            public class Broken {

                public static int main(String[] args) {

                    Broken owned = new Broken();
                    Broken alias = owned;
                    free owned;
                    return missingName;
                }
            }
            """;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.err.println("usage: VerifyCompilerOutput <ironwoodc> <work-directory>");
            System.exit(2);
        }

        Path compiler = Path.of(args[0]);
        Path work = Path.of(args[1]);
        Path source = work.resolve("check/Broken.iron");
        Files.createDirectories(source.getParent());
        Files.writeString(source, BROKEN_SOURCE, StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(compiler.toString(),
                "--source-path", work.toString(),
                "-d", work.resolve("classes").toString(),
                source.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        List<CompilerDiagnostic> diagnostics = CompilerOutputParser.parse(output);
        List<String> failures = new ArrayList<>();

        if (diagnostics.size() != 2) {
            failures.add("expected 2 diagnostics, parsed " + diagnostics.size()
                    + ": " + diagnostics);
        }

        CompilerDiagnostic free = find(diagnostics, "cannot free");
        if (free == null) {
            failures.add("the rejected free was not parsed");
        } else {
            if (!free.message().contains("alias")) {
                failures.add("the rejected free lost its explanation: " + free.message());
            }
            if (!free.located()) {
                failures.add("the rejected free lost its location");
            } else {
                if (!free.path().orElseThrow().endsWith("Broken.iron")) {
                    failures.add("the rejected free has the wrong path: " + free.path());
                }
                int expected = lineOf("free owned;");
                if (free.line() != expected) {
                    failures.add("the rejected free is on line " + free.line()
                            + ", expected " + expected);
                }
            }
        }

        CompilerDiagnostic unknown = find(diagnostics, "missingName");
        if (unknown == null) {
            failures.add("the unresolved name was not parsed");
        } else if (unknown.line() != lineOf("return missingName;")) {
            failures.add("the unresolved name is on line " + unknown.line()
                    + ", expected " + lineOf("return missingName;"));
        }

        if (!failures.isEmpty()) {
            System.err.println("compiler output parsing failed with "
                    + failures.size() + " problem(s):");
            failures.forEach(failure -> System.err.println("  " + failure));
            System.err.println("raw compiler output:");
            output.lines().forEach(line -> System.err.println("  " + line));
            System.exit(1);
        }

        System.out.println("compiler output parsing passed: "
                + diagnostics.size() + " diagnostics recovered with positions");
    }

    /** The one-based line of a fragment in the fixture, so edits cannot desync. */
    private static int lineOf(String fragment) {
        String[] lines = BROKEN_SOURCE.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(fragment)) {
                return index + 1;
            }
        }
        throw new IllegalStateException("fixture no longer contains: " + fragment);
    }

    private static CompilerDiagnostic find(List<CompilerDiagnostic> diagnostics, String fragment) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.message().contains(fragment))
                .findFirst()
                .orElse(null);
    }
}
