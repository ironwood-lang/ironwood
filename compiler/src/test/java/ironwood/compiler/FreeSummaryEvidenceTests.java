// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;

final class FreeSummaryEvidenceTests {
    private static final String CHAIN = """
            class Chain {

                static byte[] saved;

                static void first(byte[] value) {

                    second(value);
                }

                static void second(byte[] value) {

                    third(value);
                }

                static void third(byte[] value) {

                    saved = value;
                }

                static void example() {

                    byte[] data = new byte[16];
                    first(data);
                    free data;
                }
            }
            """;
    private static final String CYCLE = """
            class Cycle {

                static byte[] saved;

                static void ping(byte[] value, int count) {

                    if (count > 0) {
                        pong(value, count - 1);
                    }
                }

                static void pong(byte[] value, int count) {

                    if (count == 0) {
                        saved = value;
                    } else {
                        ping(value, count - 1);
                    }
                }

                static void safePing(byte[] value, int count) {

                    if (count > 0) {
                        safePong(value, count - 1);
                    }
                }

                static void safePong(byte[] value, int count) {

                    if (count > 0) {
                        safePing(value, count - 1);
                    }
                }

                static void example() {

                    byte[] data = new byte[16];
                    ping(data, 3);
                    free data;
                }

                static void safeExample() {

                    byte[] data = new byte[16];
                    safePing(data, 3);
                    free data;
                }
            }
            """;
    private static final String TEMPORARY_BORROW = """
            class Item {

                int value;
            }

            class Wrapper {

                private final Item item;

                Wrapper(Item item) {

                    this.item = item;
                }

                void touch() {

                    this.item.value++;
                }
            }

            class Case {

                static void use(Item item) {

                    Wrapper wrapper = new Wrapper(item);
                    defer free wrapper;
                    wrapper.touch();
                }

                static int check() {

                    Item item = new Item();
                    defer free item;
                    use(item);
                    return item.value;
                }
            }
            """;
    private static final String MISSING_OVERRIDE = """
            class Parent {
                void touch() {}
            }
            class Child extends Parent {
                void touch() {}
            }
            """;

    private FreeSummaryEvidenceTests() {}

    static void summaryBaselines() {
        rejectedCall("Chain", CHAIN, "first", 24, 14);
        accepted("Chain", CHAIN.replace("saved = value;", ""));
        rejectedCall("Cycle", CYCLE, "ping", 39, 14);
        // Keep the publishing helpers, but make both callers enter the safe cycle.
        accepted("Cycle", CYCLE.replace("ping(data, 3);", "safePing(data, 3);"));
        accepted("Cycle", CYCLE.replace("saved = value;", ""));

        accepted("Case", TEMPORARY_BORROW);
        CompilationArtifact earlyError = analyze("Case", TEMPORARY_BORROW,
                SourceFile.of("OverrideError.iron", MISSING_OVERRIDE));
        requireRejected(earlyError);
        require(earlyError.diagnostics().stream().anyMatch(d -> d.isError()
                        && d.message().contains("must be declared @Override")),
                "missing override error: " + earlyError.diagnostics());
        String reason = "cannot free 'item': allocation escapes through argument 1 of method 'use'";
        var secondary = earlyError.diagnostics().stream()
                .filter(d -> d.isError() && d.message().equals(reason)).toList();
        require(secondary.size() == 2, "expected two fallback cleanup rejections: " + earlyError.diagnostics());
        for (var error : secondary) {
            require(error.source().path().toString().equals("Case.iron")
                            && error.span().start().line() == 33 && error.span().start().column() == 20,
                    "fallback primary moved: " + error);
        }
        CompilationArtifact corrected = analyze("Case", TEMPORARY_BORROW,
                SourceFile.of("OverrideError.iron", MISSING_OVERRIDE.replace(
                        "class Child extends Parent {", "class Child extends Parent {\n    @Override")));
        requireAccepted(corrected);
        // Completed refinement must still reject real publication by the helper.
        String retaining = TEMPORARY_BORROW.replace("class Case {", "class Case {\n    static Item saved;")
                .replace("wrapper.touch();", "wrapper.touch();\n        saved = item;");
        CompilationArtifact unsafe = analyze("Case", retaining);
        requireRejected(unsafe);
        require(unsafe.diagnostics().stream().anyMatch(d -> d.isError() && d.message().equals(reason)),
                "retaining helper was not rejected: " + unsafe.diagnostics());
    }

    private static void rejectedCall(String name, String source, String callee, int line, int column) {
        CompilationArtifact artifact = analyze(name, source);
        requireRejected(artifact);
        var errors = artifact.diagnostics().stream().filter(d -> d.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        require(error.message().equals("cannot free 'data': allocation escapes through argument 1 of method '"
                        + callee + "'"), name + " wrong primary: " + error);
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().line() == line && error.span().start().column() == column,
                name + " primary moved: " + error);
    }

    private static void accepted(String name, String source) {
        requireAccepted(analyze(name, source));
    }

    private static void requireAccepted(CompilationArtifact artifact) {
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                "safe control rejected: " + artifact.diagnostics());
    }

    private static void requireRejected(CompilationArtifact artifact) {
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                "invalid source produced a program: " + artifact.diagnostics());
    }

    private static CompilationArtifact analyze(String name, String source, SourceFile... additional) {
        List<SourceFile> sources = new ArrayList<>();
        sources.add(SourceFile.of(name + ".iron", source));
        sources.addAll(List.of(additional));
        return new CompilerPipeline(UnfreedMode.OFF).analyze(sources);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
