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

    private FreeBundledSourceTests() {}

    static void retainingOverride() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            CompilationArtifact rejected = analyze(SOURCE, mode);
            require(!rejected.valid() && rejected.program().isEmpty() && rejected.llvmIr().isEmpty(),
                    mode + " retaining override produced a program");
            require(rejected.diagnostics().size() == 1,
                    mode + " unexpected diagnostics: " + rejected.diagnostics());
            var error = rejected.diagnostics().getFirst();
            require(error.isError() && error.message().equals(
                            "cannot prove destructor free of field 'scalar' safe: field ownership is uncertain"),
                    mode + " wrong primary: " + error);
            require(error.source().path().toString().replace('\\', '/').endsWith(
                            "ironwood-stdlib.ironjar!/ironwood/io/Writer.ironclass!/source/Writer.iron"),
                    mode + " lost bundled source identity: " + error.source().path());
            String librarySource = error.source().content();
            int free = librarySource.indexOf("free this.scalar;");
            require(free >= 0 && error.span().start().offset() == free + "free ".length(),
                    mode + " primary moved from Writer's destructor: " + error);

            // With no retained buffer, the same bundled destructor remains provable.
            CompilationArtifact accepted = analyze(SOURCE.replace("kept = buffer;", ""), mode);
            require(accepted.valid() && accepted.diagnostics().isEmpty(),
                    mode + " non-retaining override rejected: " + accepted.diagnostics());
        }
    }

    private static CompilationArtifact analyze(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).analyze(List.of(SourceFile.of("KeepingWriter.iron", source)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
