// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeCallExplanationTests {
    private FreeCallExplanationTests() {}

    static void callSitesAndMissingIdentity() {
        String argument = """
                class CallArgument {
                    static byte[] saved;
                    static void retain(byte[] value) { saved = value; }
                    static void borrow(byte[] value) {}
                    static void rejected() {
                        byte[] payload = new byte[1];
                        borrow(payload);
                        retain(
                                (byte[])
                                payload);
                        free payload;
                    }
                    static void accepted() {
                        byte[] data = new byte[1];
                        borrow(data);
                        free data;
                    }
                }
                """;
        selected(argument, "CallArgument", "allocation escapes through argument 1",
                "(byte[])", "final call summary");
        String receiver = """
                class CallReceiver {
                    static CallReceiver saved;
                    void retain() { saved = this; }
                    static void rejected() {
                        CallReceiver value = new CallReceiver();
                        value.retain();
                        free value;
                    }
                }
                """;
        selected(receiver, "CallReceiver", "allocation escapes through receiver",
                "value.retain();", "final call summary");
        String constructorArgument = """
                class CallConstructorArgument {
                    static byte[] saved;
                    CallConstructorArgument(byte[] value) { saved = value; }
                    static void rejected() {
                        byte[] payload = new byte[1];
                        new CallConstructorArgument(
                                (byte[])
                                payload);
                        free payload;
                    }
                }
                """;
        selected(constructorArgument, "CallConstructorArgument",
                "allocation escapes through constructor argument 1", "(byte[])",
                "final call summary");
        String constructorReceiver = """
                class CallConstructorReceiver {
                    static CallConstructorReceiver saved;
                    CallConstructorReceiver() { saved = this; }
                    static void rejected() {
                        CallConstructorReceiver value = new CallConstructorReceiver();
                        free value;
                    }
                }
                """;
        selected(constructorReceiver, "CallConstructorReceiver",
                "allocation escapes from constructor", "new CallConstructorReceiver()",
                "final call summary");
        String replacement = """
                class CallReplacement {
                    static byte[] first;
                    static byte[] second;
                    static void earlier(byte[] value) { first = value; }
                    static void later(byte[] value) { second = value; }
                    static void rejected() {
                        byte[] data = new byte[1];
                        earlier(data);
                        later(data);
                        free data;
                    }
                }
                """;
        selected(replacement, "CallReplacement", "argument 1 of method 'later'",
                "later(data);", "final call summary");
        String fresh = """
                class CallFresh {
                    static byte[] create() { return new byte[1]; }
                    static void accepted() {
                        byte[] value = create();
                        free value;
                    }
                }
                """;
        CompilationArtifact freshOff = compile("CallFresh", fresh, false);
        CompilationArtifact freshOn = compile("CallFresh", fresh, true);
        require(freshOff.valid() && freshOn.valid() && freshOff.diagnostics().isEmpty()
                        && freshOn.diagnostics().isEmpty()
                        && freshOff.llvmIr().equals(freshOn.llvmIr()),
                "proven fresh call changed acceptance or IR");
        String origins = """
                class CallOrigins {
                    static byte[] saved;
                    static byte[] fresh() { return new byte[1]; }
                    static byte[] published() {
                        byte[] result = new byte[1];
                        saved = result;
                        return result;
                    }
                    static void accepted() {
                        byte[] value = fresh();
                        free value;
                    }
                    static void rejected() {
                        byte[] value = published();
                        free value;
                    }
                    static void parameter(byte[] incoming) { free incoming; }
                }
                """;
        missing(origins, "CallOrigins", "published();", "no proven fresh allocation origin");
        missing(origins, "CallOrigins", "incoming) {", "supplied by its caller");
    }

    private static void selected(String text, String name, String reason,
                                 String sourceOperation, String noteText) {
        CompilationArtifact off = compile(name, text, false);
        CompilationArtifact on = compile(name, text, true);
        require(off.diagnostics().stream().allMatch(d -> d.notes().isEmpty()), name + " off notes");
        require(off.diagnostics().stream().map(Diagnostic::message).toList()
                        .equals(on.diagnostics().stream().map(Diagnostic::message).toList()),
                name + " changed primaries");
        Diagnostic error = on.diagnostics().stream().filter(d -> d.isError()
                && d.message().contains(reason)).findFirst().orElseThrow();
        require(!on.valid() && on.program().isEmpty() && on.llvmIr().isEmpty(),
                name + " rejected source produced artifacts");
        require(error.notes().size() == 1, name + " missing call note: " + error);
        var note = error.notes().getFirst();
        require(note.message().contains(noteText) && note.source() != null
                        && note.source().path().toString().equals(name + ".iron")
                        && note.span().start().line() == lineOf(text, sourceOperation),
                name + " incorrect call site: " + note);
        if (sourceOperation.equals("(byte[])")) {
            require(note.span().end().line() == lineOf(text, "    payload);")
                            && note.span().end().line() > note.span().start().line(),
                    name + " lost the multiline operand extent: " + note);
        }
    }

    private static void missing(String text, String name, String sourceOperation,
                                String noteText) {
        CompilationArtifact off = compile(name, text, false);
        CompilationArtifact on = compile(name, text, true);
        var offErrors = off.diagnostics().stream().filter(Diagnostic::isError).toList();
        var onErrors = on.diagnostics().stream().filter(Diagnostic::isError).toList();
        require(offErrors.stream().map(Diagnostic::message).toList()
                        .equals(onErrors.stream().map(Diagnostic::message).toList()),
                name + " identity mode changed primaries");
        require(offErrors.stream().allMatch(d -> d.notes().isEmpty()), name + " off identity notes");
        require(!on.valid() && on.program().isEmpty() && on.llvmIr().isEmpty(),
                name + " identity rejection produced artifacts");
        require(onErrors.stream().anyMatch(error -> error.notes().size() == 1
                        && error.notes().getFirst().message().contains(noteText)
                        && error.notes().getFirst().source() != null
                        && error.notes().getFirst().span().start().line()
                        == lineOf(text, sourceOperation)),
                name + " missing identity origin: " + onErrors);
    }

    private static CompilationArtifact compile(String name, String text, boolean explain) {
        return new CompilerPipeline(UnfreedMode.OFF, explain, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
    }

    private static int lineOf(String text, String fragment) {
        int offset = text.indexOf(fragment);
        require(offset >= 0, "missing source fragment " + fragment);
        return 1 + (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
