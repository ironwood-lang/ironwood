// SPDX-License-Identifier: MIT OR Apache-2.0

// Checks that the builder can still read ironwoodc's diagnostics.
//
// The Eclipse builder runs the compiler as a process and recovers errors from
// its printed output, so a change to the compiler's diagnostic rendering would
// silently stop producing markers. This runs the real compiler over a source
// file with known errors and asserts that each one is recovered with its
// message and position intact. Synthetic and real formatter note blocks check
// that related locations never replace a primary marker.
//
//   java -cp ide/eclipse/target/classes \
//       ide/eclipse/tools/VerifyCompilerOutput.java <ironwoodc> <work-directory>

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticFormatter;
import ironwood.compiler.diagnostic.DiagnosticNote;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import ironwood.ide.eclipse.CompilerOutputParser;
import ironwood.ide.eclipse.CompilerOutputParser.CompilerDiagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
                    owned.missingMethod();
                    return missingName;
                }
            }
            """;

    // Related note locations must never become primary markers.
    // Exercise both printed text and the real note-aware formatter.
    private static final String EXPLAINED_OUTPUT = """
            error: method 'inspect' is not applicable to these arguments
              --> /work/AliasDemo.iron:7:14
              |
            7 |         inspect(data);
              |              ^^^^
            note: local 'alias' receives a reference to the same allocation here
              --> /work/AliasDemo.iron:6:24
              |
            6 |         byte[] alias = data;
              |                        ^^^^
            note: related evidence may be in another source file
              --> /work/Other.iron:3:5
              |
            3 |     inspect(data);
              |     ^^^^^^^
            note: the argument type is declared in this related source
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
        verifyNotes(failures);

        if (diagnostics.size() != 2) {
            failures.add("expected 2 diagnostics, parsed " + diagnostics.size()
                    + ": " + diagnostics);
        }

        CompilerDiagnostic free = find(diagnostics, "missingMethod");
        if (free == null) {
            failures.add("the invalid method call was not parsed");
        } else {
            if (!free.message().contains("missingMethod")) {
                failures.add("the invalid method call lost its explanation: " + free.message());
            }
            if (!free.located()) {
                failures.add("the invalid method call lost its location");
            } else {
                if (!free.path().orElseThrow().endsWith("Broken.iron")) {
                    failures.add("the invalid method call has the wrong path: " + free.path());
                }
                int expected = lineOf("owned.missingMethod();");
                if (free.line() != expected) {
                    failures.add("the invalid method call is on line " + free.line()
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
                + diagnostics.size() + " diagnostics recovered with positions; note fixtures preserved primaries");
    }

    private static void verifyNotes(List<String> failures) {
        CompilerDiagnostic primary = new CompilerDiagnostic(
                "method 'inspect' is not applicable to these arguments",
                Optional.of("/work/AliasDemo.iron"), 7, 14);
        for (String text : List.of(EXPLAINED_OUTPUT, actualNoteOutput())) {
            for (String newline : List.of("\n", "\r\n")) {
                String explained = text.replace("\r\n", "\n").replace("\n", newline);
                List<CompilerDiagnostic> parsed = CompilerOutputParser.parse(explained);
                if (!parsed.equals(List.of(primary))) {
                    failures.add("notes changed the primary message, location, or count: " + parsed);
                }
                String surrounded = "error: missing input file" + newline + explained
                        + "error: linking failed" + newline;
                List<CompilerDiagnostic> expected = List.of(
                        new CompilerDiagnostic("missing input file", Optional.empty(), 0, 0),
                        primary,
                        new CompilerDiagnostic("linking failed", Optional.empty(), 0, 0));
                parsed = CompilerOutputParser.parse(surrounded);
                if (!parsed.equals(expected)) {
                    failures.add("notes leaked into neighboring unlocated primaries: " + parsed);
                }
            }
        }
    }

    private static String actualNoteOutput() {
        SourceFile source = SourceFile.of("/work/AliasDemo.iron",
                "\n".repeat(5) + "        byte[] alias = data;\n        inspect(data);\n");
        SourceFile other = SourceFile.of("/work/Other.iron",
                "\n".repeat(2) + "    inspect(data);\n");
        Diagnostic primary = new Diagnostic(
                "method 'inspect' is not applicable to these arguments",
                source, span(7, 14, 7, 18));
        Diagnostic explained = primary.withNotes(List.of(
                new DiagnosticNote("local 'alias' receives a reference here",
                        source, span(6, 24, 6, 28)),
                new DiagnosticNote("related evidence is in another source file",
                        other, span(3, 5, 3, 12)),
                new DiagnosticNote("the remaining path detail is unavailable")));
        return new DiagnosticFormatter().format(explained) + System.lineSeparator();
    }

    private static SourceSpan span(int firstLine, int firstColumn, int lastLine, int lastColumn) {
        return new SourceSpan(new SourcePosition(0, firstLine, firstColumn),
                new SourcePosition(1, lastLine, lastColumn));
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
