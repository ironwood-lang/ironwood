// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticFormatter;
import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ExplanationEligibilityTests {
    private static final String LIMITED = "ownership analysis was limited because of earlier errors; "
            + "fix those first and recompile; this rejection may be secondary";

    private ExplanationEligibilityTests() {
    }

    static void localAndReadiness() {
        SourceFile escaped = SourceFile.of("Escaped.iron", """
                class Escaped {
                    static Object saved;
                    static void check() {
                        Object value = new Object();
                        saved = value;
                        free value;
                    }
                }
                """);
        for (UnfreedMode mode : UnfreedMode.values()) {
            CompilationArtifact off = new CompilerPipeline(mode, false, null)
                    .analyze(List.of(escaped));
            CompilationArtifact on = new CompilerPipeline(mode, true, null)
                    .analyze(List.of(escaped));
            require(!off.valid() && !on.valid() && samePrimaries(off, on),
                    "local escape changed rejection or primary under " + mode);
            require(off.diagnostics().stream().allMatch(diagnostic -> diagnostic.notes().isEmpty()),
                    "disabled local explanation retained notes");
            Diagnostic free = oneFree(on);
            require(free.notes().size() == 1
                    && free.notes().getFirst().message().equals(
                    "this operation established the selected ownership reason: "
                            + "allocation escapes through static field 'Escaped.saved'")
                    && free.notes().getFirst().source().path().equals(escaped.path())
                    && free.notes().getFirst().span().start().line() == 5
                    && free.source().path().toString().equals("Escaped.iron")
                    && free.span().start().line() == 6,
                    "completed selected-store note was absent or misplaced: " + free);
            SourceFile suppressed = SourceFile.of("Suppressed.iron",
                    escaped.content().replace("Object value = new Object();",
                            "@SuppressUnfreed Object value = new Object();"));
            CompilationArtifact suppressedOff = new CompilerPipeline(mode, false, null)
                    .analyze(List.of(suppressed));
            CompilationArtifact suppressedOn = new CompilerPipeline(mode, true, null)
                    .analyze(List.of(suppressed));
            require(!suppressedOn.valid() && samePrimaries(suppressedOff, suppressedOn)
                            && oneFree(suppressedOn).notes().size() == 1,
                    "missing-free suppression changed rejected-free safety under " + mode);
        }

        SourceFile skipped = SourceFile.of("Skipped.iron", """
                class Base {
                    void keep(Object value) {
                    }
                }
                class Skipped extends Base {
                    void keep(Object value) {
                    }
                    static Object saved;
                    static void check() {
                        Object value = new Object();
                        saved = value;
                        free value;
                    }
                }
                """);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(skipped));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(skipped));
        require(samePrimaries(off, on) && !on.valid(),
                "skipped analysis changed primary diagnostics");
        require(on.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("@Override") && diagnostic.notes().isEmpty()),
                "unrelated override error gained notes");
        Diagnostic free = oneFree(on);
        require(free.notes().size() == 1 && free.notes().getFirst().message().equals(LIMITED),
                "skipped rejection lacks its exact limited-analysis note");

        SourceFile safe = SourceFile.of("Safe.iron", """
                class Safe {
                    public static int main(String[] args) {
                        Object value = new Object();
                        free value;
                        return 0;
                    }
                }
                """);
        CompilationArtifact safeOff = new CompilerPipeline(UnfreedMode.ERROR, false, null)
                .compile(List.of(safe));
        CompilationArtifact safeOn = new CompilerPipeline(UnfreedMode.ERROR, true, null)
                .compile(List.of(safe));
        require(safeOff.successful() && safeOn.successful()
                && safeOff.llvmIr().equals(safeOn.llvmIr())
                && safeOn.diagnostics().stream().allMatch(diagnostic -> diagnostic.notes().isEmpty()),
                "accepted control changed output or gained notes");
    }

    static void localBindings() {
        bindingCase("Declaration", "Object alias = value;", 0);
        bindingCase("Statement", "Object alias = new Object();\n        alias = value;", 1);
        bindingCase("Expression", "Object alias = new Object();\n        (alias = value);", 1);
        SourceFile common = SourceFile.of("Common.iron", """
                class Common {
                    static void check(boolean choice) {
                        Object value = new Object();
                        Object alias = value;
                        if (choice) { } else { }
                        free value;
                    }
                }
                """);
        SourceFile different = SourceFile.of("Different.iron", """
                class Different {
                    static void check(boolean choice) {
                        Object value = new Object();
                        Object alias = null;
                        if (choice) { alias = value; }
                        else { alias = value; }
                        free value;
                    }
                }
                """);
        joinedBinding(common, true);
        joinedBinding(different, false);
        exceptionalBinding(false);
        exceptionalBinding(true);
    }

    private static void exceptionalBinding(boolean reassigned) {
        String name = reassigned ? "ExceptionalDifferent" : "ExceptionalCommon";
        SourceFile source = SourceFile.of(name + ".iron", """
                class Failure extends Exception { }
                class %s {
                    static void mayThrow() throws Failure { }
                    static void check() {
                        Object value = new Object();
                        Object alias = value;
                        try {
                            mayThrow();
                            %s
                            mayThrow();
                        } catch (Failure failure) {
                            free value;
                        }
                    }
                }
                """.formatted(name, reassigned ? "alias = value;" : ""));
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && samePrimaries(off, on),
                name + " changed exceptional rejection or primary: " + on.diagnostics());
        Diagnostic free = oneFree(on);
        require(free.notes().size() == (reassigned ? 1 : 2),
                name + " lost exceptional note: " + free);
        if (reassigned) {
            require(free.notes().getFirst().source() == null,
                    name + " selected an arbitrary exceptional predecessor: " + free);
        } else {
            require(free.notes().getFirst().source() != null
                            && free.notes().getFirst().span().start().line() == 6,
                    name + " lost common exceptional binding: " + free);
        }
    }

    private static void joinedBinding(SourceFile source, boolean common) {
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && samePrimaries(off, on),
                "join changed the rejection or primary: " + on.diagnostics());
        Diagnostic free = oneFree(on);
        require(free.notes().size() == (common ? 2 : 1),
                "join lost its local note: " + free);
        if (common) {
            require(free.notes().getFirst().source() != null
                            && free.notes().getFirst().span().start().line() == 4,
                    "common binding lost its source through a join: " + free);
        } else {
            require(free.notes().getFirst().source() == null
                            && free.notes().getFirst().message().contains(
                            "did not retain the current alias-producing binding"),
                    "join selected an arbitrary predecessor's binding: " + free);
        }
    }

    private static void bindingCase(String name, String binding, int extraLines) {
        SourceFile source = SourceFile.of(name + ".iron", "class " + name + " {\n"
                + "    static void check() {\n"
                + "        Object value = new Object();\n"
                + "        " + binding + "\n"
                + "        free value;\n"
                + "    }\n"
                + "}\n");
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && samePrimaries(off, on),
                name + " changed the rejection or primary: " + on.diagnostics());
        Diagnostic free = oneFree(on);
        int rightHandOffset = source.content().indexOf("alias = value") + "alias = ".length();
        require(rightHandOffset >= "alias = ".length() && free.notes().size() == 2
                        && free.notes().getFirst().message().equals(
                        "local 'alias' receives a reference to the same allocation here")
                        && free.notes().getFirst().source().path().equals(source.path())
                        && free.notes().getFirst().span().start().offset() == rightHandOffset
                        && free.notes().get(1).source() == null
                        && free.notes().get(1).message().equals(
                        "the ownership analysis still tracks 'alias' as an observer at this free")
                        && free.span().start().line() == 5 + extraLines,
                name + " lost the current right-hand binding location: " + free);
        if (name.equals("Declaration")) {
            String golden = """
                    error: cannot free 'value': allocation may still be observed through local 'alias'
                      --> Declaration.iron:5:14
                      |
                    5 |         free value;
                      |              ^^^^^
                    note: local 'alias' receives a reference to the same allocation here
                      --> Declaration.iron:4:24
                      |
                    4 |         Object alias = value;
                      |                        ^^^^^
                    note: the ownership analysis still tracks 'alias' as an observer at this free
                    """.stripTrailing();
            require(new DiagnosticFormatter().format(free).equals(golden),
                    "local alias rendered block differs from golden: "
                            + new DiagnosticFormatter().format(free));
        }
        SourceFile reassigned = SourceFile.of(name + "Safe.iron",
                source.content().replace("free value;", "alias = null;\n        free value;"));
        CompilationArtifact safeOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(reassigned));
        CompilationArtifact safeOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(reassigned));
        require(safeOff.valid() && safeOn.valid() && samePrimaries(safeOff, safeOn),
                name + " retained a stale alias after reassignment: " + safeOn.diagnostics());
    }

    static void otherEmitters() {
        check("DeferredUnknown", """
                class DeferredUnknown {
                    static void check(Object value) {
                        defer free value;
                    }
                }
                """, Set.of("cannot defer free of 'value': target must be a live, proven owned local reference"));
        check("DeferredType", """
                class DeferredType {
                    static void check() {
                        int value = 1;
                        defer free value;
                    }
                }
                """, Set.of());
        check("DeferredDuplicate", """
                class DeferredDuplicate {
                    static void check() {
                        Object value = new Object();
                        defer free value;
                        defer free value;
                    }
                }
                """, Set.of("allocation already has a pending deferred free"));
        check("DestructorUnknown", """
                class DestructorUnknown {
                    private Object owned;
                    DestructorUnknown(Object value) { owned = value; }
                    destructor { free owned; }
                }
                """, Set.of("cannot prove destructor free of field 'owned' safe: field ownership is uncertain"));
        check("DestructorType", """
                class DestructorType {
                    private int owned;
                    destructor { free owned; }
                }
                """, Set.of());
        check("Loop", """
                class Loop {
                    static void check(int count) {
                        Object value = new Object();
                        for (int i = 0; i < count; i++) {
                            free value;
                        }
                    }
                }
                """, Set.of("cannot carry freed allocation in local 'value' across loop back edge",
                "cannot prove free safe across loop back edge: "
                        + "the next iteration may observe a freed, escaped, or different allocation"));
        check("Owned", """
                class Item { }
                class Owned {
                    private Item[] items = new Item[2];
                    static Item cached = new Item();
                    Owned() {
                        Item value = new Item();
                        items[0] = value;
                        cached = value;
                    }
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """, Set.of("cannot prove owned elements of 'items' safe: "
                + "a creation-array object cannot also escape through a field"), List.of(
                new ExpectedNote("this field store publishes an object recorded in the creation array", 8),
                new ExpectedNote("this object was first recorded in the creation array here", 7),
                new ExpectedNote("the recognized destructor cleanup frees creation-array elements here", 11)));
    }

    static void readinessAndExclusions() {
        SourceFile late = SourceFile.of("Late.iron", """
                class Late {
                    static Object saved;
                    static void bad() {
                        Object value = new Object();
                        saved = value;
                        free value;
                        int unused = missingName;
                    }
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, late.path())).analyze(List.of(late));
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(late));
        require(!on.valid() && samePrimaries(off, on) && counts.completed(),
                "later body error lost completed refinement");
        Diagnostic free = oneFree(on);
        require(free.notes().size() == 1
                        && !free.notes().getFirst().message().equals(LIMITED)
                        && on.diagnostics().stream().filter(d -> d.message().contains("missingName"))
                                .allMatch(d -> d.notes().isEmpty()),
                "later body error changed readiness or gained unrelated note");

        check("FreeName", """
                class FreeName { static void check() { free missing; } }
                """, Set.of());
        check("FreeType", """
                class FreeType { static void check() { int value = 1; free value; } }
                """, Set.of());
        check("FreeParser", """
                class FreeParser { static void check() { free 42; } }
                """, Set.of());
        check("PendingWrite", """
                class PendingWrite {
                    static void check() {
                        Object value = new Object();
                        defer free value;
                        value = new Object();
                    }
                }
                """, Set.of());
        check("UseAfterFree", """
                class UseAfterFree {
                    static void check() {
                        Object value = new Object();
                        free value;
                        value.toString();
                    }
                }
                """, Set.of());
        SourceFile wrongPool = SourceFile.of("Case.iron", """
                import ironwood.pool.*;
                class Item { int value; }
                class Builder implements ObjectBuilder<Item> {
                    @Override public Item newInstance() { return new Item(); }
                }
                class Case {
                    public static int main(String[] args) {
                        ObjectPool<Item> pool = new ArrayObjectPool<Item>(2, new Builder());
                        ObjectPool<Item> other = new MultiArrayObjectPool<Item>(1, new Builder());
                        other.release(pool.get());
                        return 0;
                    }
                }
                """);
        CompilationArtifact wrongPoolOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .compile(List.of(wrongPool));
        CompilationArtifact wrongPoolOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .compile(List.of(wrongPool));
        require(!wrongPoolOn.valid() && samePrimaries(wrongPoolOff, wrongPoolOn)
                        && wrongPoolOn.diagnostics().stream()
                                .filter(d -> d.message().contains("owned by another pool"))
                                .anyMatch(d -> d.notes().isEmpty()),
                "wrong-pool transfer gained a rejected-free note: " + wrongPoolOn.diagnostics());

        skipped("DeferredSkipped", """
                class DeferredSkipped {
                    static void check(Object value) { defer free value; }
                }
                """, "cannot defer free of 'value'");
        skipped("DestructorSkipped", """
                class DestructorSkipped {
                    private Object owned;
                    DestructorSkipped(Object value) { owned = value; }
                    destructor { free owned; }
                }
                """, "cannot prove destructor free of field 'owned'");
        skipped("LoopSkipped", """
                class LoopSkipped {
                    static void check(int count) {
                        Object value = new Object();
                        for (int i = 0; i < count; i++) { free value; }
                    }
                }
                """, "cannot carry freed allocation");
        skipped("OwnedSkipped", """
                class Item { }
                class OwnedSkipped {
                    private Item[] items = new Item[2];
                    static Item cached = new Item();
                    OwnedSkipped() {
                        Item value = new Item();
                        items[0] = value;
                        cached = value;
                    }
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """, "cannot prove owned elements of 'items'");
    }

    private static void skipped(String name, String source, String prefix) {
        String earlier = """
                class EarlyBase { void ping() {} }
                class EarlyChild extends EarlyBase { void ping() {} }
                """;
        SourceFile input = SourceFile.of(name + ".iron", earlier + source);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(input));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(input));
        require(!on.valid() && samePrimaries(off, on), name + " changed skipped primaries");
        List<Diagnostic> matches = on.diagnostics().stream()
                .filter(d -> d.message().startsWith(prefix)).toList();
        require(!matches.isEmpty() && matches.stream().allMatch(d -> d.notes().size() == 1
                        && d.notes().getFirst().message().equals(LIMITED)),
                name + " lost exact skipped note: " + on.diagnostics());
        require(on.diagnostics().stream().filter(d -> d.message().contains("@Override"))
                        .allMatch(d -> d.notes().isEmpty()),
                name + " gave unrelated error a note");
    }

    private static void check(String name, String source, Set<String> explained) {
        check(name, source, explained, List.of());
    }

    private static void check(String name, String source, Set<String> explained,
                              List<ExpectedNote> expectedNotes) {
        SourceFile input = SourceFile.of(name + ".iron", source);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(input));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(input));
        require(!off.valid() && !on.valid() && samePrimaries(off, on),
                name + " changed primary diagnostics: " + on.diagnostics());
        require(off.diagnostics().stream().allMatch(d -> d.notes().isEmpty()),
                name + " disabled notes present");
        require(on.diagnostics().stream().map(Diagnostic::message).collect(java.util.stream.Collectors.toSet())
                .containsAll(explained), name + " missed target: " + on.diagnostics());
        for (Diagnostic diagnostic : on.diagnostics()) {
            int expectedCount = explained.contains(diagnostic.message())
                    ? (expectedNotes.isEmpty() ? 1 : expectedNotes.size()) : 0;
            require(diagnostic.notes().size() == expectedCount,
                    name + " wrong note eligibility: " + diagnostic);
            for (int index = 0; index < expectedNotes.size() && expectedCount > 0; index++) {
                ExpectedNote expected = expectedNotes.get(index);
                var actual = diagnostic.notes().get(index);
                require(actual.message().equals(expected.message())
                                && actual.source() != null
                                && actual.source().path().equals(input.path())
                                && actual.span().start().line() == expected.line(),
                        name + " wrong note location or message: " + diagnostic);
            }
        }
    }

    private record ExpectedNote(String message, int line) {}

    private static Diagnostic oneFree(CompilationArtifact artifact) {
        List<Diagnostic> matches = artifact.diagnostics().stream()
                .filter(diagnostic -> diagnostic.message().startsWith("cannot free 'value'"))
                .toList();
        require(matches.size() == 1, "expected one free rejection: " + artifact.diagnostics());
        return matches.getFirst();
    }

    private static boolean samePrimaries(CompilationArtifact left, CompilationArtifact right) {
        if (left.diagnostics().size() != right.diagnostics().size()) {
            return false;
        }
        for (int index = 0; index < left.diagnostics().size(); index++) {
            Diagnostic a = left.diagnostics().get(index);
            Diagnostic b = right.diagnostics().get(index);
            if (!a.message().equals(b.message()) || a.severity() != b.severity()
                    || !Objects.equals(a.span(), b.span())
                    || !Objects.equals(a.source() == null ? null : a.source().path(),
                            b.source() == null ? null : b.source().path())) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
