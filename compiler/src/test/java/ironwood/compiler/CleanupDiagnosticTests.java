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

    static void loopBackEdgeExplanations() {
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
        CompilationArtifact off = analyze("LoopDemo", source);
        CompilationArtifact on = explained("LoopDemo", source);
        require(off.diagnostics().size() == 2 && on.diagnostics().size() == 2,
                "loop explanation changed primary count: " + on.diagnostics());
        for (int index = 0; index < 2; index++) {
            var before = off.diagnostics().get(index);
            var after = on.diagnostics().get(index);
            require(before.message().equals(after.message())
                            && before.span().equals(after.span())
                            && before.source().path().equals(after.source().path())
                            && before.notes().isEmpty() && after.notes().size() == 1,
                    "loop explanation changed a primary: " + after);
        }
        require(on.diagnostics().getFirst().notes().getFirst().message().equals(
                        "this predecessor freed the carried allocation here")
                        && on.diagnostics().getFirst().notes().getFirst().span()
                        .start().line() == 5
                        && on.diagnostics().get(1).notes().getFirst().message().startsWith(
                        "this loop back edge carries an already freed allocation")
                        && on.diagnostics().get(1).notes().getFirst().span()
                        .start().line() == 4
                        && on.diagnostics().get(1).notes().getFirst().source().path()
                        .equals(on.diagnostics().get(1).source().path()),
                "loop explanations missed free or back edge: " + on.diagnostics());

        String maybe = replace(source, "free data;", "if (i == 0) free data;");
        CompilationArtifact maybeOff = analyze("LoopDemo", maybe);
        CompilationArtifact maybeOn = explained("LoopDemo", maybe);
        require(maybeOff.diagnostics().size() == maybeOn.diagnostics().size()
                        && maybeOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().startsWith("cannot carry freed allocation")
                        && diagnostic.notes().stream().anyMatch(note ->
                        note.message().contains("may carry a freed allocation")))
                        && maybeOn.diagnostics().stream().noneMatch(diagnostic ->
                        diagnostic.notes().stream().anyMatch(note ->
                        note.message().contains("predecessor freed the carried"))),
                "maybe-freed predecessor gained a definite free: " + maybeOn.diagnostics());

        String deferred = replace(source, "free data;", "defer free data;");
        CompilationArtifact deferredOff = analyze("LoopDemo", deferred);
        CompilationArtifact deferredOn = explained("LoopDemo", deferred);
        require(deferredOff.diagnostics().size() == deferredOn.diagnostics().size()
                        && deferredOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().startsWith("cannot prove free safe across loop")
                        && diagnostic.notes().stream().anyMatch(note ->
                        note.message().startsWith("this free was reached during cleanup for "))),
                "loop validation lost deferred cleanup exit: " + deferredOn.diagnostics());

        accepted("LoopDemo", replace(source, "free data;", "free data; break;"));
        accepted("LoopDemo", replace(source, "byte[] data = new byte[16];", "")
                .replace("free data;", "byte[] data = new byte[16]; free data;"));
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

    static void pendingYieldExplanation() {
        String text = """
                class Box { int number; }
                class YieldPending {
                    static int check(int selector) {
                        Box value = new Box();
                        Box result = switch (selector) {
                            default -> {
                                try { yield value; }
                                finally { free value; }
                            }
                        };
                        return result.number;
                    }
                }
                """;
        CompilationArtifact off = analyze("YieldPending", text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("YieldPending.iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                "pending yield changed rejection or artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.message().contains("pending yield result")
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && before.notes().isEmpty() && after.notes().size() == 2
                        && after.notes().getFirst().message().equals(
                        "this pending yield result still observes the allocation during cleanup")
                        && after.notes().getFirst().span().start().line() == 7
                        && after.notes().getFirst().source().path().equals(before.source().path())
                        && after.notes().getLast().message().equals(
                        "this cleanup is checked for this yield")
                        && after.notes().getLast().span().start().line() == 7,
                "pending yield lost its result site: " + after);
        accepted("YieldPending", replace(text, "yield value;", "yield new Box();"));
    }

    static void deferredRegistrationExplanations() {
        String unknown = """
                class UnknownRegistration {
                    static void check(Object value) { defer free value; }
                }
                """;
        registrationNote("UnknownRegistration", unknown,
                "this local has no compiler-proven allocation identity",
                "defer free value;", 1);
        accepted("UnknownRegistration", """
                class UnknownRegistration {
                    static void check() { Object value = new Object(); defer free value; }
                }
                """);

        String freed = """
                class FreedRegistration {
                    static void check() {
                        Object value = new Object();
                        free value;
                        defer free value;
                    }
                }
                """;
        registrationNote("FreedRegistration", freed,
                "the same allocation was already freed here", "free value;", 1);
        accepted("FreedRegistration", freed.replaceFirst("free value;\\n", ""));

        String maybe = """
                class MaybeRegistration {
                    static void check(boolean flag) {
                        Object value = new Object();
                        if (flag) free value;
                        defer free value;
                    }
                }
                """;
        registrationNote("MaybeRegistration", maybe,
                "when the condition is true, the same allocation was freed here",
                "free value;", 2);
        accepted("MaybeRegistration", replace(maybe, "if (flag) free value;", ""));

        String helper = """
                import ironwood.ds.ArrayList;
                import ironwood.util.Iterator;
                class HelperRegistration {
                    static void check() {
                        ArrayList<Object> list = new ArrayList<>();
                        Iterator<Object> it = list.iterator();
                        defer free it;
                        free list;
                    }
                }
                """;
        registrationNote("HelperRegistration", helper,
                "this dependent helper was acquired here", "list.iterator()", 1);
        accepted("HelperRegistration", replace(helper, "defer free it;", ""));
    }

    private static void registrationNote(String name, String text, String firstDetail,
                                         String sourceOperation, int noteCount) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed registration rejection or artifacts: "
                        + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.message().contains("target must be a live, proven owned")
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && before.notes().isEmpty() && after.notes().size() == noteCount
                        && after.notes().getFirst().message().startsWith(firstDetail)
                        && after.notes().getFirst().span().start().line()
                        == lineOf(text, text.indexOf(sourceOperation))
                        && after.notes().getFirst().source().path().equals(before.source().path()),
                name + " lost the first failed registration check: " + after);
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

    static void returnAndNormalExitExplanations() {
        exitNotes("DupCleanup", DEFER, 3,
                "normal completion of this deferred tail");
        exitNotes("FinallyDup", FINALLY, 3,
                "normal completion of this try body");
        exitNotes("OneExit", ONE_EXIT, 1, null);
        accepted("OneExit", replace(ONE_EXIT, "saved = data;", ""));
    }

    private static void exitNotes(String name, String text, int count,
                                  String normalDescription) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid()
                        && off.diagnostics().size() == count
                        && on.diagnostics().size() == count
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed cleanup count or artifacts: " + on.diagnostics());
        for (int index = 0; index < count; index++) {
            var before = off.diagnostics().get(index);
            var after = on.diagnostics().get(index);
            require(before.message().equals(after.message())
                            && before.span().equals(after.span())
                            && before.severity() == after.severity()
                            && before.source().path().equals(after.source().path())
                            && before.notes().isEmpty(),
                    name + " changed a cleanup primary: " + after);
        }
        var returned = on.diagnostics().getFirst().notes();
        require(returned.size() == 2
                        && returned.getLast().message().equals(
                        "this cleanup is checked for this return")
                        && returned.getLast().span().start().line()
                        == lineOf(text, text.indexOf("return;"))
                        && returned.getLast().source().path()
                        .equals(on.diagnostics().getFirst().source().path()),
                name + " lost return exit: " + returned);
        if (normalDescription != null) {
            var normal = on.diagnostics().get(1).notes();
            require(normal.size() == 2
                            && normal.getLast().message().equals(
                            "this cleanup is checked for " + normalDescription)
                            && text.charAt(normal.getLast().span().start().offset()) == '}'
                            && normal.getLast().source().path()
                            .equals(on.diagnostics().get(1).source().path()),
                    name + " lost closing-brace normal exit: " + normal);
        }
    }

    static void transferAndExceptionalExplanations() {
        exceptionalExit("DupCleanup", DEFER);
        exceptionalExit("FinallyDup", FINALLY);

        String breakText = """
                class BreakCleanup {
                    static byte[] saved;
                    static void check(boolean flag) {
                        byte[] data = new byte[16];
                        outer: while (flag) {
                            defer free data;
                            saved = data;
                            break outer;
                        }
                    }
                }
                """;
        transferExit("BreakCleanup", breakText,
                "this break to label 'outer'", "break outer;");
        accepted("BreakCleanup", replace(breakText, "saved = data;", ""));

        String continueText = """
                class ContinueCleanup {
                    static byte[] saved;
                    static void check() {
                        while (true) {
                            byte[] data = new byte[16];
                            defer free data;
                            saved = data;
                            continue;
                        }
                    }
                }
                """;
        transferExit("ContinueCleanup", continueText,
                "this continue", "continue;");
        accepted("ContinueCleanup", replace(continueText, "saved = data;", ""));

        String yieldText = """
                class YieldCleanup {
                    static byte[] saved;
                    static int check() {
                        byte[] data = new byte[16];
                        int answer = switch (1) {
                            default -> {
                                defer free data;
                                saved = data;
                                yield 1;
                            }
                        };
                        return answer;
                    }
                }
                """;
        transferExit("YieldCleanup", yieldText, "this yield", "yield 1;");
        accepted("YieldCleanup", replace(yieldText, "saved = data;", ""));
    }

    private static void exceptionalExit(String name, String text) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(off.diagnostics().size() == 3 && on.diagnostics().size() == 3,
                name + " changed exceptional cleanup count: " + on.diagnostics());
        var before = off.diagnostics().get(2);
        var after = on.diagnostics().get(2);
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.notes().isEmpty() && after.notes().size() == 2
                        && after.notes().getLast().message().equals(
                        "this cleanup is checked for exceptional unwinding of this "
                                + "protected region; this copy may combine exceptional predecessors")
                        && after.notes().getLast().source().path().equals(before.source().path())
                        && after.notes().size() <= 8,
                name + " lost exceptional region context: " + after);
    }

    private static void transferExit(String name, String text, String description,
                                     String sourceTransfer) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed transfer rejection or artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && before.notes().isEmpty() && after.notes().size() == 2
                        && after.notes().getLast().message().equals(
                        "this cleanup is checked for " + description)
                        && after.notes().getLast().span().start().line()
                        == lineOf(text, text.indexOf(sourceTransfer))
                        && after.notes().getLast().source().path().equals(before.source().path()),
                name + " lost source transfer context: " + after);
    }

    static void deadCatchOrigin() {
        // Even without an incoming exception edge, the catch is checked.
        rejected("DeadCatchCleanup", DEAD_CATCH, 1, 16, 18);
        String direct = replace(DEAD_CATCH, "return;", "free data;");
        rejected("DeadCatchCleanup", direct, 1, 14, 18);
        accepted("DeadCatchCleanup", replace(DEAD_CATCH, "saved = data;", ""));
        accepted("DeadCatchCleanup", replace(direct, "saved = data;", ""));
    }

    static void checkedCatchExplanations() {
        checkedCatchCase(DEAD_CATCH,
                "this cleanup is checked for this return inside a catch "
                        + "with no recorded incoming exception edge",
                "return;", 16);
        String direct = replace(DEAD_CATCH, "return;", "free data;");
        checkedCatchCase(direct,
                "this catch is checked even though no exception edge from its try body "
                        + "was recorded",
                "RuntimeException ignored", 14);
        String incoming = replace(DEAD_CATCH, "static byte[] saved;",
                "static byte[] saved; static void work() { }");
        incoming = replace(incoming, "int unused = 0;", "work();");
        CompilationArtifact incomingOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("DeadCatchCleanup.iron", incoming)));
        CompilationArtifact incomingOff = analyze("DeadCatchCleanup", incoming);
        require(incomingOff.diagnostics().size() == 1
                        && incomingOn.diagnostics().size() == 1
                        && incomingOff.diagnostics().getFirst().message().equals(
                        incomingOn.diagnostics().getFirst().message())
                        && incomingOn.diagnostics().getFirst().notes().size() == 2
                        && incomingOn.diagnostics().getFirst().notes().getLast()
                        .message().equals("this cleanup is checked for this return"),
                "recorded exception edge inherited a dead-catch qualifier: "
                        + incomingOn.diagnostics());
        accepted("DeadCatchCleanup", replace(DEAD_CATCH, "saved = data;", ""));
        accepted("DeadCatchCleanup", replace(direct, "saved = data;", ""));
    }

    static void nestedAndCatchCompletionExplanations() {
        String catchCompletion = """
                class CatchCompletion {
                    static byte[] saved;
                    static void work() { }
                    static void check() {
                        byte[] data = new byte[16];
                        try { work(); }
                        catch (RuntimeException ignored) {
                            saved = data;
                        } finally {
                            free data;
                        }
                    }
                }
                """;
        CompilationArtifact catchOff = analyze("CatchCompletion", catchCompletion);
        CompilationArtifact catchOn = explained("CatchCompletion", catchCompletion);
        require(catchOff.diagnostics().size() == catchOn.diagnostics().size()
                        && catchOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.notes().stream().anyMatch(note -> note.message().equals(
                        "this cleanup is checked for normal completion of this catch body")
                        && catchCompletion.charAt(note.span().start().offset()) == '}')),
                "catch completion lost its body end: " + catchOn.diagnostics());

        String replacement = """
                class ReplacedTransfer {
                    static byte[] saved;
                    static void check(boolean flag) {
                        byte[] data = new byte[16];
                        defer free data;
                        saved = data;
                        while (flag) {
                            try { break; }
                            finally { return; }
                        }
                    }
                }
                """;
        CompilationArtifact replacementOff = analyze("ReplacedTransfer", replacement);
        CompilationArtifact replacementOn = explained("ReplacedTransfer", replacement);
        require(replacementOff.diagnostics().size() == replacementOn.diagnostics().size()
                        && replacementOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.notes().stream().anyMatch(note -> note.message().equals(
                        "this cleanup is checked for this return")
                        && note.span().start().line() == 9))
                        && replacementOn.diagnostics().stream().noneMatch(diagnostic ->
                        diagnostic.notes().stream().anyMatch(note ->
                        note.message().equals("this cleanup is checked for this break"))),
                "replaced transfer kept the old exit: " + replacementOn.diagnostics());
        accepted("ReplacedTransfer", replace(replacement, "saved = data;", ""));

        String siblings = """
                class SiblingReturns {
                    static byte[] saved;
                    static void check(boolean flag) {
                        byte[] data = new byte[16];
                        defer free data;
                        saved = data;
                        if (flag) return;
                        return;
                    }
                }
                """;
        CompilationArtifact siblingsOff = analyze("SiblingReturns", siblings);
        CompilationArtifact siblingsOn = explained("SiblingReturns", siblings);
        require(siblingsOff.diagnostics().size() == 2
                        && siblingsOn.diagnostics().size() == 2,
                "two returns changed cleanup multiplicity: " + siblingsOn.diagnostics());
        for (int index = 0; index < 2; index++) {
            var before = siblingsOff.diagnostics().get(index);
            var after = siblingsOn.diagnostics().get(index);
            require(before.message().equals(after.message()) && before.span().equals(after.span())
                            && before.notes().isEmpty() && after.notes().size() == 2
                            && after.notes().getLast().message().equals(
                            "this cleanup is checked for this return")
                            && after.notes().getLast().span().start().line() == 7 + index,
                    "sibling return reused another exit: " + after);
        }
        accepted("SiblingReturns", replace(siblings, "saved = data;", ""));

        String nestedRegistration = """
                class NestedRegistration {
                    static void check() {
                        byte[] data = new byte[16];
                        try { return; }
                        finally {
                            defer free data;
                            defer free data;
                        }
                    }
                }
                """;
        CompilationArtifact registrationOff = analyze("NestedRegistration", nestedRegistration);
        CompilationArtifact registrationOn = explained("NestedRegistration", nestedRegistration);
        require(registrationOff.diagnostics().size() == 1
                        && registrationOn.diagnostics().size() == 1
                        && registrationOff.diagnostics().getFirst().message().equals(
                        registrationOn.diagnostics().getFirst().message())
                        && registrationOff.diagnostics().getFirst().notes().isEmpty()
                        && registrationOn.diagnostics().getFirst().notes().getLast()
                        .message().equals("this cleanup is checked for this return")
                        && registrationOn.diagnostics().getFirst().notes().getLast()
                        .span().start().line() == 4,
                "inner registration lost its outer cleanup entry: "
                        + registrationOn.diagnostics());
    }

    static void cleanupReadinessAndExclusions() {
        String skipped = """
                class Base { void keep(Object value) { } }
                class SkippedCleanup extends Base {
                    void keep(Object value) { }
                    static byte[] saved;
                    static void check() {
                        byte[] data = new byte[16];
                        saved = data;
                        try { return; }
                        finally { free data; }
                    }
                }
                """;
        CompilationArtifact skippedOff = analyze("SkippedCleanup", skipped);
        CompilationArtifact skippedOn = explained("SkippedCleanup", skipped);
        require(skippedOff.diagnostics().size() == skippedOn.diagnostics().size()
                        && skippedOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().startsWith("cannot free ")
                        && diagnostic.notes().size() == 1
                        && diagnostic.notes().getFirst().message().startsWith(
                        "ownership analysis was limited because of earlier errors"))
                        && skippedOn.diagnostics().stream().noneMatch(diagnostic ->
                        diagnostic.notes().stream().anyMatch(note ->
                        note.message().startsWith("this cleanup is checked for "))),
                "skipped refinement gained an exit note: " + skippedOn.diagnostics());

        String excluded = """
                class ExcludedCleanup {
                    static void check() {
                        try { return; }
                        finally {
                            int number = 1;
                            defer free number;
                            defer free missing;
                        }
                    }
                }
                """;
        CompilationArtifact excludedOff = analyze("ExcludedCleanup", excluded);
        CompilationArtifact excludedOn = explained("ExcludedCleanup", excluded);
        require(excludedOff.diagnostics().size() == excludedOn.diagnostics().size()
                        && excludedOn.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("target must be a live, proven owned"))
                        && excludedOn.diagnostics().stream().allMatch(diagnostic ->
                        diagnostic.notes().isEmpty()),
                "excluded cleanup error gained exit evidence: " + excludedOn.diagnostics());
    }

    static void boundedCleanupCopies() {
        String source = """
                class BoundedCleanup {
                    static byte[] f0;
                    static byte[] f1;
                    static byte[] f2;
                    static byte[] f3;
                    static byte[] f4;
                    static byte[] f5;
                    static byte[] f6;
                    static byte[] f7;
                    static void check(boolean a, boolean b, boolean c, boolean leave) {
                        byte[] data = new byte[16];
                        defer free data;
                        if (a) {
                            if (b) {
                                if (c) f0 = data; else f1 = data;
                            } else {
                                if (c) f2 = data; else f3 = data;
                            }
                        } else {
                            if (b) {
                                if (c) f4 = data; else f5 = data;
                            } else {
                                if (c) f6 = data; else f7 = data;
                            }
                        }
                        if (leave) return;
                    }
                }
                """;
        CompilationArtifact off = analyze("BoundedCleanup", source);
        CompilationArtifact on = explained("BoundedCleanup", source);
        CompilationArtifact again = explained("BoundedCleanup", source);
        require(off.diagnostics().size() == 2 && on.diagnostics().size() == 2
                        && again.diagnostics().size() == 2,
                "bounded copies changed primary multiplicity: " + on.diagnostics());
        for (int index = 0; index < 2; index++) {
            var before = off.diagnostics().get(index);
            var after = on.diagnostics().get(index);
            var repeated = again.diagnostics().get(index);
            require(before.message().equals(after.message())
                            && before.span().equals(after.span())
                            && after.message().equals(repeated.message())
                            && after.span().equals(repeated.span())
                            && before.notes().isEmpty()
                            && after.notes().size() <= 8
                            && after.notes().stream().anyMatch(note ->
                            note.message().contains("omitted"))
                            && after.notes().getLast().message().startsWith(
                            "this cleanup is checked for ")
                            && after.notes().stream().map(note -> note.message() + "@"
                            + (note.span() == null ? -1 : note.span().start().offset())).toList()
                            .equals(repeated.notes().stream().map(note -> note.message() + "@"
                            + (note.span() == null ? -1 : note.span().start().offset())).toList()),
                    "bounded cleanup note cap or order changed: " + after);
        }
        require(on.diagnostics().getFirst().notes().getLast().message().equals(
                        "this cleanup is checked for this return")
                        && on.diagnostics().get(1).notes().getLast().message().equals(
                        "this cleanup is checked for normal completion of this deferred tail"),
                "bounded sibling cleanup exits were mixed: " + on.diagnostics());
        accepted("BoundedCleanup", source.substring(0, source.indexOf("if (a) {"))
                + source.substring(source.indexOf("if (leave) return;")));
    }

    private static CompilationArtifact explained(String name, String text) {
        return new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
    }

    private static void checkedCatchCase(String text, String qualifier,
                                         String qualifierSite, int primaryLine) {
        CompilationArtifact off = analyze("DeadCatchCleanup", text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("DeadCatchCleanup.iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                "checked catch changed rejection or artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.source().path().equals(after.source().path())
                        && before.span().start().line() == primaryLine
                        && before.notes().isEmpty() && after.notes().size() == 2
                        && after.notes().getFirst().span().start().line() == 13
                        && after.notes().getLast().message().equals(qualifier)
                        && after.notes().getLast().span().start().line()
                        == lineOf(text, text.indexOf(qualifierSite))
                        && after.notes().getLast().source().path().equals(before.source().path()),
                "checked catch lost its analysis origin: " + after);
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

    private static int lineOf(String source, int offset) {
        require(offset >= 0, "missing source operation");
        return 1 + (int) source.substring(0, offset).chars()
                .filter(character -> character == '\n').count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
