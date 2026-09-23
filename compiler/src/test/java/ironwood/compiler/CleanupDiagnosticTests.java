// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class CleanupDiagnosticTests {
    private static final String DEFER = """
            class DupCleanup {

                static byte[] saved;

                static void work() {
                }

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    defer free data;
                    saved = data;
                    work();
                    if (flag) {
                        return;
                    }
                    work();
                }
            }
            """;
    private static final String FINALLY = """
            class FinallyDup {

                static byte[] saved;

                static void work() {
                }

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    saved = data;
                    try {
                        work();
                        if (flag) {
                            return;
                        }
                        work();
                    } finally {
                        free data;
                    }
                }
            }
            """;
    private static final String ONE_EXIT = """
            class OneExit {

                static byte[] saved;

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    defer free data;
                    if (flag) {
                        saved = data;
                        return;
                    }
                }
            }
            """;

    private CleanupDiagnosticTests() {}

    static void cleanupCopies() {
        // Preserve today's multiplicity as the explanation feature baseline.
        // These checks do not imply one exceptional copy per potentially throwing call.
        variants("DupCleanup", DEFER, 11, 20);
        variants("FinallyDup", FINALLY, 19, 18);
        rejected("OneExit", ONE_EXIT, 1, 8, 20);
        accepted("OneExit", replace(ONE_EXIT, "saved = data;", ""));
        // A return with no publication is safe even when another exit can publish.
        String normalOnly = replace(ONE_EXIT, "            saved = data;\n", "")
                .replace("            return;\n        }", "            return;\n        }\n        saved = data;");
        rejected("OneExit", normalOnly, 1, 8, 20);
    }

    private static void variants(String name, String source, int line, int column) {
        rejected(name, source, 3, line, column);
        String noCalls = replace(source, "work();", "");
        rejected(name, noCalls, 2, line, column);
        String noReturn = replace(source, "return;", "");
        rejected(name, noReturn, 2, line, column);
        rejected(name, replace(noCalls, "return;", ""), 1, line, column);
        accepted(name, replace(source, "saved = data;", ""));
    }

    private static void rejected(String name, String source, int count, int line, int column) {
        CompilationArtifact artifact = analyze(name, source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                name + " invalid cleanup produced a program");
        var errors = artifact.diagnostics().stream().filter(diagnostic -> diagnostic.isError()).toList();
        require(errors.size() == count, name + " expected " + count + " errors: " + artifact.diagnostics());
        for (var error : errors) {
            require(error.message().equals("cannot free 'data': allocation escapes through static field '"
                            + name + ".saved'"), name + " unexpected diagnostic: " + error);
            require(error.source().path().toString().equals(name + ".iron")
                            && error.span().start().line() == line && error.span().start().column() == column,
                    name + " cleanup primary moved: " + error);
        }
    }

    private static void accepted(String name, String source) {
        CompilationArtifact artifact = analyze(name, source);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                name + " safe cleanup rejected: " + artifact.diagnostics());
    }

    private static CompilationArtifact analyze(String name, String source) {
        return new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of(name + ".iron", source)));
    }

    private static String replace(String source, String target, String replacement) {
        require(source.contains(target), "missing fixture text: " + target);
        return source.replace(target, replacement);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
