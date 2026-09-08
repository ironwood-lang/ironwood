// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.diagnostic;

public final class DiagnosticFormatter {
    public String format(Diagnostic diagnostic) {
        StringBuilder output = new StringBuilder("error: ").append(diagnostic.message());
        if (diagnostic.source() == null || diagnostic.span() == null) {
            return output.toString();
        }

        int line = diagnostic.span().start().line();
        int column = diagnostic.span().start().column();
        String lineText = diagnostic.source().lineText(line);
        int lineDigits = Integer.toString(line).length();
        int requestedWidth = diagnostic.span().end().line() == line
                ? diagnostic.span().end().column() - column
                : 1;
        int caretWidth = Math.max(1, Math.min(Math.max(1, requestedWidth), Math.max(1, lineText.length() - column + 2)));

        output.append(System.lineSeparator())
                .append("  --> ").append(diagnostic.source().path()).append(':').append(line).append(':').append(column)
                .append(System.lineSeparator())
                .append(" ".repeat(lineDigits + 1)).append('|').append(System.lineSeparator())
                .append(line).append(" | ").append(lineText).append(System.lineSeparator())
                .append(" ".repeat(lineDigits + 1)).append("| ")
                .append(" ".repeat(Math.max(0, column - 1)))
                .append("^".repeat(caretWidth));
        return output.toString();
    }
}
