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

    private static final String DEAD_CATCH = """
            // SPDX-License-Identifier: MIT OR Apache-2.0

            class DeadCatchCleanup {

                static byte[] saved;

                static void example() {

                    byte[] data = new byte[16];
                    try {
                        int unused = 0;
                    } catch (RuntimeException ignored) {
                        saved = data;
                        return;
                    } finally {
                        free data;
                    }
                }
            }
            """;

    private static final String CALL_CAPTURE = """
            class CallCapture {

                static void inspect(byte[] value) {
                }

                static void example() {

                    byte[] data = new byte[16];
                    defer inspect(data);
                    data = new byte[32];
                    free data;
                }
            }
            """;
    private static final String CALL_CAPTURE_REJECTED = """
            class CallCaptureRejected {

                static void inspect(byte[] value) {
                }

                static void example() {

                    byte[] data = new byte[16];
                    byte[] first = data;
                    defer inspect(data);
                    data = new byte[32];
                    free first;
                }
            }
            """;
    private static final String FREE_BINDING = """
            class FreeBinding {

                static void example() {

                    byte[] data = new byte[16];
                    defer free data;
                    data = new byte[32];
                }
            }
            """;
    private static final String PENDING_FREE = """
            class PendingFree {

                static void example() {

                    byte[] data = new byte[16];
                    byte[] alias = data;
                    defer free data;
                    free alias;
                }
            }
            """;

    private CleanupDiagnosticTests() {}

    static void loopBackEdgePrimaries() {
        String source = """
                class LoopDemo {

                    static void example(int count) {

                        byte[] data = new byte[16];
                        for (int i = 0; i < count; i++) {
                            free data;
                        }
                    }
                }
                """;
        CompilationArtifact artifact = analyze("LoopDemo", source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                "invalid loop produced a program");
        List<String> messages = List.of(
                "cannot carry freed allocation in local 'data' across loop back edge",
                "cannot prove free safe across loop back edge: "
                        + "the next iteration may observe a freed, escaped, or different allocation");
        require(artifact.diagnostics().size() == messages.size(),
                "unexpected loop diagnostics: " + artifact.diagnostics());
        for (int index = 0; index < messages.size(); index++) {
            var error = artifact.diagnostics().get(index);
            require(error.isError() && error.message().equals(messages.get(index)),
                    "loop primary text or order changed: " + artifact.diagnostics());
            require(error.source().path().toString().equals("LoopDemo.iron")
                            && error.span().start().line() == (index == 0 ? 6 : 7)
                            && error.span().start().column() == (index == 0 ? 9 : 13),
                    "loop primary moved: " + error);
        }
        // No freed value reaches a back edge after an unconditional break.
        accepted("LoopDemo", replace(source, "free data;", "free data;\n            break;"));
        // A fresh allocation inside each iteration is independent of prior iterations.
        String local = replace(source, "        byte[] data = new byte[16];\n", "")
                .replace("            free data;", "            byte[] data = new byte[16];\n            free data;");
        accepted("LoopDemo", local);
    }

    static void deferredTargets() {
        // The old array is intentionally unreclaimed in this unfreed=off fixture.
        // Capturing it for inspection must not retain the replacement allocation.
        accepted("CallCapture", CALL_CAPTURE);
        rejectedTarget("CallCaptureRejected", CALL_CAPTURE_REJECTED,
                "cannot free 'first': allocation is retained by a pending deferred call", 12, 14);
        rejectedTarget("FreeBinding", FREE_BINDING,
                "cannot assign to or update local 'data' while its deferred free is pending", 7, 9);
        rejectedTarget("PendingFree", PENDING_FREE,
                "cannot free 'alias': allocation has a pending deferred free", 8, 14);
        accepted("FreeBinding", replace(FREE_BINDING, "data = new byte[32];", ""));
        accepted("PendingFree", replace(PENDING_FREE, "free alias;", ""));
    }

    static void pendingFreeExplanations() {
        pendingBinding("PendingFree", PENDING_FREE,
                "cannot free 'alias': allocation has a pending deferred free",
                "this deferred free is bound to 'data'", 8);
        String duplicate = replace(PENDING_FREE, "free alias;", "defer free alias;");
        pendingBinding("PendingFree", duplicate,
                "allocation already has a pending deferred free",
                "this earlier deferred free is bound to 'data'", 8);
        String sameLocal = replace(PENDING_FREE, "free alias;", "defer free data;");
        pendingBinding("PendingFree", sameLocal,
                "allocation already has a pending deferred free",
                "this earlier deferred free is bound to 'data'", 8);
        accepted("PendingFree", replace(PENDING_FREE, "free alias;", ""));
    }

    static void pendingCallExplanations() {
        captureNote("CallCaptureRejected", CALL_CAPTURE_REJECTED,
                "argument 1 of this deferred call captured the allocation here, "
                        + "when 'data' still referred to it", "inspect(data)", "data");
        String receiver = """
                class ReceiverCapture {
                    void inspect() { }
                    static void check() {
                        ReceiverCapture pending = new ReceiverCapture();
                        ReceiverCapture first = pending;
                        defer pending.inspect();
                        pending = new ReceiverCapture();
                        free first;
                    }
                }
                """;
        captureNote("ReceiverCapture", receiver,
                "receiver of this deferred call captured the allocation here, "
                        + "when 'pending' still referred to it", "pending.inspect()", "pending");
        accepted("ReceiverCapture", replace(receiver, "defer pending.inspect();", ""));

        String destructor = """
                final class Leaf { }
                class DestructorCapture {
                    private Leaf owned = new Leaf();
                    static void consume(Leaf value) { }
                    destructor {
                        defer consume(owned);
                        free this.owned;
                    }
                }
                """;
        captureNote("DestructorCapture", destructor,
                "argument 1 of this deferred call captured the allocation here, "
                        + "when 'owned' still referred to it", "consume(owned)", "owned");
        accepted("DestructorCapture", replace(destructor, "defer consume(owned);", ""));
        accepted("CallCapture", CALL_CAPTURE);
    }

    private static void captureNote(String name, String text, String detail,
                                    String call, String capturedName) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed deferred-call rejection or artifacts: "
                        + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        int offset = text.indexOf(call) + call.indexOf(capturedName);
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && before.notes().isEmpty() && after.notes().size() == 1
                        && after.notes().getFirst().message().equals(detail)
                        && after.notes().getFirst().span().start().offset() == offset
                        && after.notes().getFirst().source().path().equals(before.source().path()),
                name + " lost the original captured operand: " + after);
    }

    private static void pendingBinding(String name, String text, String primary,
                                       String detail, int primaryLine) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                "pending free changed rejection or artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(primary.equals(before.message()) && before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && after.span().start().line() == primaryLine
                        && before.notes().isEmpty() && after.notes().size() == 1
                        && after.notes().getFirst().message().startsWith(detail)
                        && after.notes().getFirst().span().start().line() == 7
                        && after.notes().getFirst().source().path().equals(before.source().path()),
                "pending free lost matched binding: " + after);
    }

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

    static void deadCatchOrigin() {
        // Even without an incoming exception edge, the catch is checked.
        rejected("DeadCatchCleanup", DEAD_CATCH, 1, 16, 18);
        String direct = replace(DEAD_CATCH, "return;", "free data;");
        rejected("DeadCatchCleanup", direct, 1, 14, 18);
        accepted("DeadCatchCleanup", replace(DEAD_CATCH, "saved = data;", ""));
        accepted("DeadCatchCleanup", replace(direct, "saved = data;", ""));
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

    private static void rejectedTarget(String name, String source, String message, int line, int column) {
        CompilationArtifact artifact = analyze(name, source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                name + " invalid source produced a program");
        var errors = artifact.diagnostics().stream().filter(diagnostic -> diagnostic.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        require(error.message().equals(message), name + " wrong diagnostic: " + error.message());
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().line() == line && error.span().start().column() == column,
                name + " primary moved: " + error.span());
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
