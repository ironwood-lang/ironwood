// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeBundledSourceTests {
    private static final String SOURCE = """
            import ironwood.io.Writer;

            class KeepingWriter extends Writer {

                static char[] kept;

                @Override
                public void write(char[] buffer, int offset, int length) {

                    kept = buffer;
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            }
            """;

    private static final String EMPTY_MAIN = """
            class Main {

                public static int main(String[] args) {

                    return 0;
                }
            }
            """;

    private static final String STRING_WRITER_MAIN = """
            class Main {

                public static int main(String[] args) {

                    // This concrete override does not call Writer.writeScalar.
                    ironwood.io.StringWriter writer = new ironwood.io.StringWriter();
                    defer free writer;
                    writer.write(65);
                    return 0;
                }
            }
            """;

    private FreeBundledSourceTests() {}

    static void retainingOverride() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String entryPoint : List.of("", EMPTY_MAIN, STRING_WRITER_MAIN)) {
                String context = mode + (entryPoint.isEmpty() ? " without main"
                        : entryPoint.equals(EMPTY_MAIN) ? " with empty main" : " with StringWriter main");
                rejected(SOURCE + entryPoint, mode, context);
                // Without the store, the same bundled destructor remains provable.
                accepted(SOURCE.replace("kept = buffer;", "") + entryPoint, mode, context);
            }
            // Merely adding the retaining declaration changes this accepted program.
            accepted(STRING_WRITER_MAIN, mode, mode + " without KeepingWriter");
        }
    }

    static void explanationSourceScope() {
        SourceFile user = SourceFile.of("KeepingWriter.iron", SOURCE + EMPTY_MAIN);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(user));
        require(!off.valid() && off.diagnostics().size() == 1,
                "bundled retaining control changed: " + off.diagnostics());
        var prior = off.diagnostics().getFirst();
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, prior.source().path()))
                .analyze(List.of(user));
        require(!on.valid() && on.diagnostics().size() == 1,
                "bundled explanation changed rejection count: " + on.diagnostics());
        var primary = on.diagnostics().getFirst();
        require(primary.message().equals(prior.message())
                        && primary.span().equals(prior.span())
                        && primary.source().path().equals(prior.source().path())
                        && prior.notes().isEmpty() && primary.notes().size() >= 4
                        && primary.notes().getFirst().message().contains("ownership proof for field 'scalar'")
                        && primary.notes().getLast().message().contains("KeepingWriter.write")
                        && primary.notes().getLast().source().path().equals(user.path())
                        && primary.notes().getLast().span().start().offset()
                        == user.content().indexOf("kept = buffer;") + "kept = ".length(),
                "bundled explanation lost source or readiness: " + primary);
        require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                        && lowering.refinementCompleted() && lowering.collectorPresent()
                        && lowering.linkageName().contains("Writer")),
                "bundled Writer final lowering was not observed");

        SourceFile skippedUser = SourceFile.of("KeepingWriter.iron",
                SOURCE.replaceFirst("@Override\\n", "") + EMPTY_MAIN);
        CompilationArtifact skippedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(skippedUser));
        CompilationArtifact skippedOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(skippedUser));
        require(skippedOff.diagnostics().stream().map(d -> d.message()).toList().equals(
                        skippedOn.diagnostics().stream().map(d -> d.message()).toList()),
                "bundled skipped run changed primaries");
        var limited = skippedOn.diagnostics().stream()
                .filter(d -> d.message().startsWith("cannot prove destructor free of field 'scalar'"))
                .findFirst().orElseThrow();
        require(limited.source().path().equals(prior.source().path())
                        && limited.notes().size() == 1
                        && limited.notes().getFirst().message().equals(
                        "ownership analysis was limited because of earlier errors; "
                                + "fix those first and recompile; this rejection may be secondary")
                        && skippedOn.diagnostics().stream()
                                .filter(d -> d.message().contains("@Override"))
                                .allMatch(d -> d.notes().isEmpty()),
                "bundled skipped run missed limited-analysis boundary");
    }

    static void fieldCallExplanations() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String entryPoint : List.of("", EMPTY_MAIN, STRING_WRITER_MAIN)) {
                String context = mode + (entryPoint.isEmpty() ? " without main"
                        : entryPoint.equals(EMPTY_MAIN) ? " with empty main"
                        : " with StringWriter main");
                SourceFile source = SourceFile.of("KeepingWriter.iron", SOURCE + entryPoint);
                CompilationArtifact off = new CompilerPipeline(mode, false, null)
                        .analyze(List.of(source));
                CompilationArtifact on = new CompilerPipeline(mode, true, null)
                        .analyze(List.of(source));
                require(!off.valid() && !on.valid() && off.program().isEmpty()
                                && on.program().isEmpty() && off.llvmIr().isEmpty()
                                && on.llvmIr().isEmpty() && off.diagnostics().size() == 1
                                && on.diagnostics().size() == 1,
                        context + " changed rejection or output: " + on.diagnostics());
                var prior = off.diagnostics().getFirst();
                var detailed = on.diagnostics().getFirst();
                boolean fallback = entryPoint.equals(STRING_WRITER_MAIN);
                boolean fallbackNote = detailed.notes().stream().anyMatch(note ->
                        note.message().contains("a lowering of this call had no receiver targets"));
                require(detailed.message().equals(prior.message())
                                && detailed.source().path().equals(prior.source().path())
                                && detailed.span().equals(prior.span())
                                && prior.notes().isEmpty()
                                && detailed.notes().size() == (fallback ? 5 : 4)
                                && fallbackNote == fallback
                                && detailed.notes().get(0).message().contains(
                                "ownership proof for field 'scalar'")
                                && detailed.notes().get(1).message().contains("Writer.writeScalar")
                                && detailed.notes().get(2).message().contains("KeepingWriter.write")
                                && detailed.notes().getLast().message().contains("static field 'KeepingWriter.kept'")
                                && detailed.notes().getLast().source().path().equals(source.path())
                                && detailed.notes().getLast().span().start().offset()
                                == source.content().indexOf("kept = buffer;") + "kept = ".length()
                                && detailed.notes().stream().noneMatch(note ->
                                note.message().contains("adding a main")
                                        || note.message().contains("StringWriter.write(65)")),
                        context + " lost selected bundled-to-user chain: " + detailed);
                String safe = SOURCE.replace("kept = buffer;", "") + entryPoint;
                CompilationArtifact safeOff = new CompilerPipeline(mode, false, null)
                        .analyze(List.of(SourceFile.of("KeepingWriter.iron", safe)));
                CompilationArtifact safeOn = new CompilerPipeline(mode, true, null)
                        .analyze(List.of(SourceFile.of("KeepingWriter.iron", safe)));
                require(safeOff.valid() && safeOn.valid() && safeOff.diagnostics().isEmpty()
                                && safeOn.diagnostics().isEmpty(),
                        context + " non-retaining control changed: " + safeOn.diagnostics());
            }
            SourceFile noOverride = SourceFile.of("KeepingWriter.iron", STRING_WRITER_MAIN);
            require(new CompilerPipeline(mode, true, null).analyze(List.of(noOverride)).valid(),
                    mode + " StringWriter-only control changed");
        }
    }

    private static void rejected(String source, UnfreedMode mode, String context) {
        CompilationArtifact rejected = analyze(source, mode);
        require(!rejected.valid() && rejected.program().isEmpty() && rejected.llvmIr().isEmpty(),
                context + " retaining override produced a program");
        require(rejected.diagnostics().size() == 1,
                context + " unexpected diagnostics: " + rejected.diagnostics());
        var error = rejected.diagnostics().getFirst();
        require(error.isError() && error.message().equals(
                        "cannot prove destructor free of field 'scalar' safe: field ownership is uncertain"),
                context + " wrong primary: " + error);
        require(error.source().path().toString().replace('\\', '/').endsWith(
                        "ironwood-stdlib.ironjar!/ironwood/io/Writer.ironclass!/source/Writer.iron"),
                context + " lost bundled source identity: " + error.source().path());
        String librarySource = error.source().content();
        int free = librarySource.indexOf("free this.scalar;");
        require(free >= 0 && error.span().start().offset() == free + "free ".length(),
                context + " primary moved from Writer's destructor: " + error);
    }

    private static void accepted(String source, UnfreedMode mode, String context) {
        CompilationArtifact accepted = analyze(source, mode);
        require(accepted.valid() && accepted.diagnostics().isEmpty(),
                context + " non-retaining control rejected: " + accepted.diagnostics());
    }

    private static CompilationArtifact analyze(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).analyze(List.of(SourceFile.of("KeepingWriter.iron", source)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
