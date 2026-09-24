// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeOwnerExplanationTests {
    private FreeOwnerExplanationTests() {}

    static void ownerSitesAndLifetimes() {
        String container = """
                import ironwood.ds.ArrayList;
                class OwnerContainer {
                    static void rejected() {
                        ArrayList<Object> pending = new ArrayList<>();
                        Object value = new Object();
                        pending.add(value);
                        free value;
                    }
                }
                """;
        rejected("OwnerContainer", container, "live container", "container 'pending'",
                "pending.add(value);");
        accepted("OwnerContainer", container.replace("free value;",
                "free pending;\n        free value;"));

        String wrapper = """
                class OwnerHolder {
                    private Object value;
                    void set(Object value) { this.value = value; }
                }
                class OwnerWrapper {
                    static void rejected() {
                        OwnerHolder holder = new OwnerHolder();
                        Object value = new Object();
                        holder.set(value);
                        free value;
                    }
                }
                """;
        rejected("OwnerWrapper", wrapper, "live wrapper", "wrapper 'holder'",
                "holder.set(value);");
        accepted("OwnerWrapper", wrapper.replace("free value;",
                "free holder;\n        free value;"));

        String two = wrapper.replace("holder.set(value);\n        free value;",
                "OwnerHolder other = new OwnerHolder();\n        holder.set(value);\n"
                        + "        other.set(value);\n        free holder;\n        free value;");
        rejected("OwnerWrapper", two, "live wrapper", "wrapper 'other'",
                "other.set(value);");
        accepted("OwnerWrapper", two.replace("free value;",
                "free other;\n        free value;"));

        String reassigned = wrapper.replace("holder.set(value);\n        free value;",
                "OwnerHolder alias = holder;\n        holder.set(value);\n"
                        + "        holder = new OwnerHolder();\n        free value;");
        rejected("OwnerWrapper", reassigned, "live wrapper", "wrapper 'alias'",
                "holder.set(value);");
        String unnamed = wrapper.replace("holder.set(value);\n        free value;",
                "holder.set(value);\n        holder = new OwnerHolder();\n        free value;");
        CompilationArtifact unnamedOn = compile("OwnerWrapper", unnamed, true);
        Diagnostic unnamedError = freeError(unnamedOn, "live wrapper");
        require(unnamedError.notes().size() == 2
                        && unnamedError.notes().getFirst().message()
                        .contains("wrapper of type 'OwnerHolder'")
                        && unnamedError.notes().getFirst().span().start().line()
                        == lineOf(unnamed, "holder.set(value);")
                        && unnamedError.notes().get(1).span().start().line()
                        == lineOf(unnamed, "new OwnerHolder();"),
                "reassigned owner name or creation lost: " + unnamedError);

        String helper = """
                import ironwood.ds.ArrayList;
                import ironwood.util.Iterator;
                class OwnerHelper {
                    static void rejected() {
                        ArrayList<Object> list = new ArrayList<>();
                        Iterator<Object> it = list.iterator();
                        free it;
                        free list;
                    }
                }
                """;
        rejected("OwnerHelper", helper, "borrowed helper", "container 'list'",
                "list.iterator();");
        accepted("OwnerHelper", helper.replace("free it;", ""));

        String view = """
                import ironwood.ds.ArrayList;
                import ironwood.ds.Collections;
                import ironwood.ds.UnmodifiableList;
                class OwnerView {
                    static void rejected() {
                        ArrayList<Object> list = new ArrayList<>();
                        UnmodifiableList<Object> view = Collections.unmodifiableList(list);
                        free list;
                    }
                }
                """;
        rejected("OwnerView", view, "live wrapper", "wrapper 'view'",
                "Collections.unmodifiableList(list);");
        accepted("OwnerView", view.replace("free list;", "free view;\n        free list;"));

        String attached = """
                class OwnerAttached {
                    private byte[] values = new byte[16];
                    void rejected() {
                        byte[] local = values;
                        free local;
                    }
                }
                """;
        rejected("OwnerAttached", attached, "private field 'values'",
                "private field 'values'", "byte[] local = values;");
        accepted("OwnerAttached", attached.replace("free local;",
                "values = null;\n        free local;"));
    }

    private static void rejected(String name, String text, String reason,
                                 String noteText, String operation) {
        CompilationArtifact off = compile(name, text, false);
        CompilationArtifact on = compile(name, text, true);
        Diagnostic error = freeError(on, reason);
        require(off.diagnostics().stream().map(Diagnostic::message).toList()
                        .equals(on.diagnostics().stream().map(Diagnostic::message).toList())
                        && off.diagnostics().stream().allMatch(d -> d.notes().isEmpty()),
                name + " changed primary or disabled notes");
        require(!on.valid() && on.program().isEmpty() && on.llvmIr().isEmpty(),
                name + " rejected source produced artifacts");
        require(!error.notes().isEmpty() && error.notes().getFirst().source() != null
                        && error.notes().getFirst().message().contains(noteText)
                        && error.notes().getFirst().span().start().line()
                        == lineOf(text, operation),
                name + " owner site mismatch: " + error);
    }

    private static Diagnostic freeError(CompilationArtifact artifact, String reason) {
        return artifact.diagnostics().stream().filter(d -> d.isError()
                && d.message().startsWith("cannot free ") && d.message().contains(reason))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + reason
                        + " in " + artifact.diagnostics()));
    }

    private static void accepted(String name, String text) {
        CompilationArtifact off = compile(name, text, false);
        CompilationArtifact on = compile(name, text, true);
        require(off.valid() && on.valid() && off.diagnostics().isEmpty()
                        && on.diagnostics().isEmpty() && off.llvmIr().equals(on.llvmIr()),
                name + " accepted cleanup changed: " + on.diagnostics());
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
