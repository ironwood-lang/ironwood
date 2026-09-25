// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticFormatter;
import ironwood.compiler.diagnostic.DiagnosticNote;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class DiagnosticNoteTests {
    private DiagnosticNoteTests() {
    }

    static void runAll() throws Exception {
        SourceFile primarySource = SourceFile.of("demo/Main.iron",
                "\n".repeat(13) + "    free item;\n");
        SourceFile relatedSource = SourceFile.of("lib/Owner.iron",
                "\n".repeat(6) + "    hold(item);\n    next();\n");
        SourceSpan primarySpan = span(14, 10, 14, 14);
        SourceSpan relatedSpan = span(7, 10, 7, 14);
        Diagnostic primary = new Diagnostic("cannot free 'item'", primarySource, primarySpan);
        DiagnosticFormatter formatter = new DiagnosticFormatter();
        String noNote = "error: cannot free 'item'" + System.lineSeparator()
                + "  --> demo/Main.iron:14:10" + System.lineSeparator()
                + "   |" + System.lineSeparator()
                + "14 |     free item;" + System.lineSeparator()
                + "   |          ^^^^";
        require(formatter.format(primary).equals(noNote), "legacy no-note primary changed");
        require(!formatter.format(primary).endsWith(System.lineSeparator()),
                "formatter must not add a final newline");
        require(formatter.format(Diagnostic.global("global failure")).equals("error: global failure"),
                "unlocated error changed");
        require(formatter.format(Diagnostic.warning(primarySource, primarySpan, "missing free"))
                .equals(noNote.replaceFirst("error: cannot free 'item'", "warning: missing free")),
                "no-note warning changed");

        List<DiagnosticNote> mutable = new ArrayList<>();
        mutable.add(new DiagnosticNote("reference stored here", relatedSource, relatedSpan));
        mutable.add(new DiagnosticNote("context unavailable"));
        mutable.add(new DiagnosticNote("region crosses a line", relatedSource,
                span(7, 5, 8, 5)));
        Diagnostic explained = primary.withNotes(mutable);
        mutable.clear();
        require(explained.notes().size() == 3, "notes were not copied");
        try {
            explained.notes().add(new DiagnosticNote("late"));
            throw new AssertionError("notes remain mutable");
        } catch (UnsupportedOperationException expected) {
            // The diagnostic owns an immutable note list.
        }
        String expected = noNote + System.lineSeparator()
                + "note: reference stored here" + System.lineSeparator()
                + "  --> lib/Owner.iron:7:10" + System.lineSeparator()
                + "  |" + System.lineSeparator()
                + "7 |     hold(item);" + System.lineSeparator()
                + "  |          ^^^^" + System.lineSeparator()
                + "note: context unavailable" + System.lineSeparator()
                + "note: region crosses a line" + System.lineSeparator()
                + "  --> lib/Owner.iron:7:5" + System.lineSeparator()
                + "  |" + System.lineSeparator()
                + "7 |     hold(item);" + System.lineSeparator()
                + "  |     ^";
        require(formatter.format(explained).equals(expected),
                "full note block or per-location gutters changed: " + formatter.format(explained));
        require(matchesGolden(formatter.format(explained), expected),
                "LF golden did not match");
        require(matchesGolden(expected.replace("\n", "\r\n"), expected),
                "CRLF golden did not normalize");
        require(!matchesGolden(expected.replace("   |", "  |"), expected),
                "gutter difference was hidden");
        require(!matchesGolden(expected.replace("^^^^", "^^^"), expected),
                "caret difference was hidden");
        require(!matchesGolden(expected + "\n", expected),
                "trailing newline difference was hidden");
        require(!explained.equals(primary), "full diagnostic equality ignored notes");
        require(Diagnostic.hasErrors(List.of(explained)), "notes changed primary severity");
        require(Diagnostic.hasErrors(List.of(primary)), "legacy primary lost error severity");

        Diagnostic global = Diagnostic.global("global failure").withNotes(explained.notes());
        Diagnostic warning = Diagnostic.warning(primarySource, primarySpan, "missing free")
                .withNotes(explained.notes());
        Diagnostic missingSpan = new Diagnostic("missing span", primarySource, null)
                .withNotes(explained.notes());
        // A located warning keeps its notes (D185); unlocated primaries never do.
        require(global.notes().isEmpty() && missingSpan.notes().isEmpty(),
                "ineligible primary retained notes");
        require(warning.notes().equals(explained.notes())
                        && !Diagnostic.hasErrors(List.of(warning)),
                "located warning lost its notes or changed severity");
        require(formatter.format(warning).startsWith("warning: missing free")
                        && formatter.format(warning).contains("\nnote: "),
                "warning notes were not rendered");
        require(formatter.format(global).equals("error: global failure"),
                "ineligible unlocated primary changed text");
        rejectBlankNote();
        require(System.lineSeparator().equals("\n") || System.lineSeparator().equals("\r\n"),
                "unexpected platform separator");
        require(formatter.format(explained).contains(System.lineSeparator() + "note:"),
                "formatter did not use platform separators");
        verifyCliFinalNewline();
    }

    private static SourceSpan span(int startLine, int startColumn, int endLine, int endColumn) {
        return new SourceSpan(new SourcePosition(0, startLine, startColumn),
                new SourcePosition(1, endLine, endColumn));
    }

    private static boolean matchesGolden(String actual, String expectedLf) {
        return actual.replace("\r\n", "\n").equals(expectedLf);
    }

    private static void verifyCliFinalNewline() throws Exception {
        Path root = Files.createTempDirectory("ironwood-note-cli-");
        Path source = root.resolve("Bad.iron");
        Files.writeString(source, "class Bad { static void check() { free 42; } }\n");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = Main.run(new String[]{source.toString(), "-d",
                root.resolve("classes").toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        require(status == 1, "invalid free unexpectedly succeeded");
        String output = stderr.toString(StandardCharsets.UTF_8);
        require(output.startsWith("error: "), "CLI lost primary diagnostic");
        require(output.endsWith(System.lineSeparator()), "CLI lost final newline");
    }

    private static void rejectBlankNote() {
        try {
            new DiagnosticNote(" ");
            throw new AssertionError("blank note accepted");
        } catch (IllegalArgumentException expected) {
            // Blank note text is not a diagnostic event.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
