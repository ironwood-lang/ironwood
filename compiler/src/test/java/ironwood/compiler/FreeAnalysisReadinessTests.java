// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeAnalysisReadinessTests {
    private static final String SOURCE = """
            class Sink {

                void accept(byte[] value) {
                }
            }

            class Quiet extends Sink {

                void accept(byte[] value) {
                }
            }

            class Main {

                static void use(Sink sink) {

                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }

                public static void main(String[] args) {

                    use(new Quiet());
                }
            }
            """;

    private FreeAnalysisReadinessTests() {}

    static void earlierErrorsAndRefinement() {
        CompilationArtifact missingOverride = analyze(SOURCE);
        rejected(missingOverride);
        require(hasError(missingOverride, "must be declared @Override"),
                "missing override diagnostic not found: " + missingOverride.diagnostics());
        // Capture today's fallback behavior for the explanation feature baseline.
        // Removing secondary diagnostics is a separate error-recovery change.
        require(hasError(missingOverride,
                        "cannot prove argument 1 of polymorphic method 'accept' does not escape"),
                "expected limited-analysis rejection: " + missingOverride.diagnostics());

        String corrected = SOURCE.replace("class Quiet extends Sink {\n",
                "class Quiet extends Sink {\n\n    @Override");
        CompilationArtifact accepted = analyze(corrected);
        require(accepted.valid() && accepted.diagnostics().isEmpty(),
                "non-retaining dispatch rejected: " + accepted.diagnostics());

        String retaining = corrected.replace("class Sink {", "class Sink {\n    static byte[] saved;")
                .replace("void accept(byte[] value) {", "void accept(byte[] value) {\n        saved = value;");
        CompilationArtifact unsafe = analyze(retaining);
        rejected(unsafe);
        require(hasError(unsafe, "cannot free 'data':"),
                "retaining dispatch must prevent reclamation: " + unsafe.diagnostics());

        // A later body error must not be mistaken for skipped refinement merely
        // because the final compilation contains errors.
        CompilationArtifact bodyError = analyze(corrected.replace("use(new Quiet());",
                "use(new Quiet());\n        int invalid = missingName;"));
        rejected(bodyError);
        require(hasError(bodyError, "missingName"),
                "body error not found: " + bodyError.diagnostics());
        require(!hasError(bodyError, "cannot free 'data':"),
                "body error lost refined dispatch: " + bodyError.diagnostics());

        // Import checks only report, so an unresolved import must not skip
        // refinement. Bundled Throwable stays provable in every mode, and the
        // import error is the only diagnostic.
        String emptyMain = "class Main { public static int main(String[] args) { return 0; } }\n";
        List<String> missingImports = List.of("import ironwood.util.Missing;\n",
                "import static ironwood.util.Missing.value;\n");
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String missingImport : missingImports) {
                CompilationArtifact importOnly = analyze(mode, false, missingImport + emptyMain);
                rejected(importOnly);
                require(importOnly.diagnostics().size() == 1
                                && hasError(importOnly, "type 'ironwood.util.Missing' does not exist"),
                        mode + " unresolved import reported secondary diagnostics: "
                                + importOnly.diagnostics());
            }
        }
        // The refined dispatch facts are complete: the non-retaining override
        // stays accepted, and a retaining one is still rejected without the
        // limited-analysis note used after skipped refinement.
        for (String missingImport : missingImports) {
            CompilationArtifact importSafe = analyze(missingImport + corrected);
            rejected(importSafe);
            require(importSafe.diagnostics().size() == 1 && !hasError(importSafe, "cannot free 'data':"),
                    "unresolved import lost refined dispatch: " + importSafe.diagnostics());
            CompilationArtifact importUnsafe = analyze(UnfreedMode.OFF, true, missingImport + retaining);
            rejected(importUnsafe);
            require(importUnsafe.diagnostics().size() == 2 && hasError(importUnsafe, "cannot free 'data':")
                            && importUnsafe.diagnostics().stream().flatMap(d -> d.notes().stream())
                                    .noneMatch(note -> note.message().contains("earlier errors")),
                    "unresolved import changed retaining dispatch rejection: " + importUnsafe.diagnostics());
        }
    }

    private static CompilationArtifact analyze(String source) {
        return analyze(UnfreedMode.OFF, false, source);
    }

    private static CompilationArtifact analyze(UnfreedMode mode, boolean explain, String source) {
        return new CompilerPipeline(mode, explain, null).analyze(List.of(SourceFile.of("Main.iron", source)));
    }

    private static void rejected(CompilationArtifact artifact) {
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                "invalid source produced a program: " + artifact.diagnostics());
    }

    private static boolean hasError(CompilationArtifact artifact, String text) {
        return artifact.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.isError() && diagnostic.message().contains(text));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
