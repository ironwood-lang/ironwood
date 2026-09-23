// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

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
