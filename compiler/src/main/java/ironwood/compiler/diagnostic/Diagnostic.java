// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.diagnostic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;

public record Diagnostic(String message, SourceFile source, SourceSpan span,
                         Severity severity, List<DiagnosticNote> notes) {
    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
        Objects.requireNonNull(severity, "severity");
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        // Notes belong to a located primary of either severity (D185); a global
        // diagnostic has no location for them to relate to.
        if (source == null || span == null) {
            notes = List.of();
        }
    }

    public Diagnostic(String message, SourceFile source, SourceSpan span, Severity severity) {
        this(message, source, span, severity, List.of());
    }

    public Diagnostic(String message, SourceFile source, SourceSpan span) {
        this(message, source, span, Severity.ERROR);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public Diagnostic withNotes(List<DiagnosticNote> related) {
        return new Diagnostic(message, source, span, severity, related);
    }

    public static boolean hasErrors(java.util.Collection<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    public static Diagnostic warning(SourceFile source, SourceSpan span, String message) {
        return new Diagnostic(message, source, span, Severity.WARNING);
    }

    public static Diagnostic error(SourceFile source, SourceSpan span, String message) {
        return new Diagnostic(message, source, span);
    }

    public static Diagnostic global(String message) {
        return new Diagnostic(message, null, null);
    }
}
