// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.diagnostic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Original-seed diagnostic constructor/value and immediate traversal contracts. */
public final class DiagnosticContractProbe {
    private static int checks;
    private DiagnosticContractProbe() {}
    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("diagnostic contract");
    }
    private static void rejects(Class<? extends RuntimeException> type, Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException error) { require(error.getClass() == type); return; }
        throw new AssertionError("missing diagnostic failure");
    }
    private static void rejectsNamedNull(String field, Runnable operation) {
        try { operation.run(); }
        catch (NullPointerException error) { require(field.equals(error.getMessage())); return; }
        throw new AssertionError("missing named null failure");
    }
    public static void main(String[] args) {
        SourceFile source = SourceFile.of("diagnostic.iron", "class D {}\n");
        SourceFile other = SourceFile.of("diagnostic.iron", "class D {}\n");
        SourceSpan span = SourceSpan.at(new SourcePosition(0, 1, 1));
        SourceSpan equalSpan = SourceSpan.at(new SourcePosition(0, 1, 1));
        DiagnosticNote note = new DiagnosticNote("related", source, span);
        List<DiagnosticNote> builder = new ArrayList<>(List.of(note));
        Diagnostic located = new Diagnostic("primary", source, span, Diagnostic.Severity.WARNING, builder);
        builder.clear();
        require(located.notes().equals(List.of(note)) && located.notes().getFirst() == note);
        rejects(UnsupportedOperationException.class, () -> located.notes().clear());
        require(located.equals(new Diagnostic("primary", source, equalSpan, Diagnostic.Severity.WARNING,
                List.of(new DiagnosticNote("related", source, equalSpan)))));
        require(!located.equals(new Diagnostic("primary", other, span, Diagnostic.Severity.WARNING, List.of(note))));
        require(!note.equals(new DiagnosticNote("related", other, span)));
        require(!located.isError() && Diagnostic.error(source, span, "error").isError());
        Diagnostic changed = located.withNotes(List.of(new DiagnosticNote("second")));
        require(located.notes().equals(List.of(note)) && changed.notes().size() == 1);
        require(changed.source() == source && changed.span() == span && changed.severity() == located.severity());
        require(new Diagnostic("global", null, span, Diagnostic.Severity.ERROR, List.of(note)).notes().isEmpty());
        require(new Diagnostic("global", source, null, Diagnostic.Severity.ERROR, List.of(note)).notes().isEmpty());
        require(Diagnostic.global("global").source() == null && Diagnostic.global("global").span() == null);
        require(new DiagnosticNote("global").source() == null && new DiagnosticNote("global").span() == null);
        rejects(IllegalArgumentException.class, () -> new DiagnosticNote("related", null, span));
        rejects(IllegalArgumentException.class, () -> new DiagnosticNote("related", source, null));
        rejects(IllegalArgumentException.class, () -> new Diagnostic("\u2003", null, null));
        rejects(IllegalArgumentException.class, () -> new DiagnosticNote(null));
        rejects(NullPointerException.class, () -> new Diagnostic("global", null, null, Diagnostic.Severity.ERROR, null));
        rejects(NullPointerException.class, () -> new Diagnostic("global", null, null, Diagnostic.Severity.ERROR,
                Arrays.asList((DiagnosticNote) null)));
        rejects(NullPointerException.class, () -> new Diagnostic("primary", source, span, null, List.of()));
        rejectsNamedNull("severity", () -> new Diagnostic("primary", source, span, null, null));
        rejectsNamedNull("notes", () -> new Diagnostic("global", null, null, Diagnostic.Severity.ERROR, null));
        rejects(IllegalArgumentException.class, () -> new Diagnostic(null, source, span, null, null));
        require(!Diagnostic.hasErrors(List.of()) && !Diagnostic.hasErrors(List.of(located)));
        require(Diagnostic.hasErrors(List.of(located, Diagnostic.global("error"))));
        require(Diagnostic.hasErrors(Arrays.asList(Diagnostic.global("error"), null)));
        rejects(NullPointerException.class, () -> Diagnostic.hasErrors(Arrays.asList(located, null)));
        rejects(NullPointerException.class, () -> Diagnostic.hasErrors(null));
        System.out.println("PASS: " + checks + " original diagnostic contract checks");
    }
}
