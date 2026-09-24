// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.diagnostic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

public final class DiagnosticFormatter {
    public String format(Diagnostic diagnostic) {
        StringBuilder output = new StringBuilder(diagnostic.isError() ? "error: " : "warning: ")
                .append(diagnostic.message());
        if (diagnostic.source() != null && diagnostic.span() != null) {
            appendLocation(output, diagnostic.source(), diagnostic.span());
        }
        for (DiagnosticNote note : diagnostic.notes()) {
            output.append(System.lineSeparator()).append("note: ").append(note.message());
            if (note.source() != null) {
                appendLocation(output, note.source(), note.span());
            }
        }
        return output.toString();
    }

    private void appendLocation(StringBuilder output, SourceFile source, SourceSpan span) {
        int line = span.start().line();
        int column = span.start().column();
        String lineText = source.lineText(line);
        int lineDigits = Integer.toString(line).length();
        int requestedWidth = span.end().line() == line
                ? span.end().column() - column
                : 1;
        int caretWidth = Math.max(1, Math.min(Math.max(1, requestedWidth), Math.max(1, lineText.length() - column + 2)));

        output.append(System.lineSeparator())
                .append("  --> ").append(source.path()).append(':').append(line).append(':').append(column)
                .append(System.lineSeparator())
                .append(" ".repeat(lineDigits + 1)).append('|').append(System.lineSeparator())
                .append(line).append(" | ").append(lineText).append(System.lineSeparator())
                .append(" ".repeat(lineDigits + 1)).append("| ")
                .append(" ".repeat(Math.max(0, column - 1)))
                .append("^".repeat(caretWidth));
    }
}
