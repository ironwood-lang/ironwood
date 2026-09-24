// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
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
                    "the compiler did not retain the selected ownership reason's source operation "
                            + "needed to explain this rejection")
                    && free.source().path().toString().equals("Escaped.iron")
                    && free.span().start().line() == 6,
                    "completed local boundary was absent or misplaced");
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
                + "a creation-array object cannot also escape through a field"));
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
            require(diagnostic.notes().size() == (explained.contains(diagnostic.message()) ? 1 : 0),
                    name + " wrong note eligibility: " + diagnostic);
        }
    }

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
