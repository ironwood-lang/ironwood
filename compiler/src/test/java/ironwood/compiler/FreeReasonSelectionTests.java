// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeReasonSelectionTests {
    private static final String TWO_STORES = """
            class TwoStores {

                static byte[] first;
                static byte[] second;

                static void example() {

                    byte[] data = new byte[16];
                    first = data;
                    second = data;
                    free data;
                }
            }
            """;
    private static final String ESCAPE_THEN_MERGE = """
            class EscapeThenMerge {

                static byte[] saved;

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    saved = data;
                    byte[] other = new byte[16];
                    byte[] pick = flag ? data : other;
                    free data;
                }
            }
            """;
    private static final String MERGE_THEN_ARRAY = """
            class MergeThenArray {

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    byte[] other = new byte[16];
                    byte[] pick = flag ? data : other;
                    Object[] holder = new Object[1];
                    if (flag) {
                        holder[0] = data;
                    }
                    free data;
                }
            }
            """;
    private static final String ARRAY_THEN_MERGE = """
            class ArrayThenMerge {

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    Object[] holder = new Object[1];
                    if (flag) {
                        holder[0] = data;
                    }
                    byte[] other = new byte[16];
                    byte[] pick = flag ? data : other;
                    free data;
                }
            }
            """;
    private static final String DIFFERENT_FIELDS = """
            class DifferentFields {

                static byte[] first;
                static byte[] second;

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        first = data;
                    } else {
                        second = data;
                    }
                    free data;
                }
            }
            """;
    private static final String ONE_BRANCH = """
            class OneBranch {

                static byte[] first;

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        first = data;
                    }
                    free data;
                }
            }
            """;
    private static final String FIELD_OR_CALL = """
            class FieldOrCall {

                static byte[] first;

                static void keep(byte[] value) {

                    first = value;
                }

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        first = data;
                    } else {
                        keep(data);
                    }
                    free data;
                }
            }
            """;
    private static final String SAME_FIELD = """
            class SameField {

                static byte[] first;

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        first = data;
                    } else {
                        first = data;
                    }
                    free data;
                }
            }
            """;
    private static final String MERGE = "allocation may still be observed through a merged reference";
    private static final String ARRAY =
            "allocation may still be observed through an array element on an incoming control-flow path";
    private static final String PICK = "byte[] pick = flag ? data : other;";

    private FreeReasonSelectionTests() {}

    static void selectedReasons() {
        // These are the primary-message baselines. The explanation feature must
        // later identify the same winning operation, not the nearest event.
        rejected("TwoStores", TWO_STORES, publication("TwoStores.second"));
        rejected("TwoStores", replace(TWO_STORES,
                "first = data;\n        second = data;", "second = data;\n        first = data;"),
                publication("TwoStores.first"));
        rejected("TwoStores", replace(TWO_STORES, "second = data;", ""), publication("TwoStores.first"));
        rejected("TwoStores", replace(TWO_STORES, "second = data;", "first = data;"),
                publication("TwoStores.first"));
        accepted("TwoStores", replace(replace(TWO_STORES, "first = data;", ""), "second = data;", ""));

        rejected("EscapeThenMerge", ESCAPE_THEN_MERGE, publication("EscapeThenMerge.saved"));
        String noEscape = replace(ESCAPE_THEN_MERGE, "saved = data;", "");
        rejected("EscapeThenMerge", noEscape, MERGE);
        rejected("EscapeThenMerge", replace(noEscape, PICK, PICK + "\n        saved = data;"),
                publication("EscapeThenMerge.saved"));
        accepted("EscapeThenMerge", replace(noEscape, PICK, ""));

        rejected("MergeThenArray", MERGE_THEN_ARRAY, MERGE);
        rejected("MergeThenArray", replace(MERGE_THEN_ARRAY, PICK, ""), ARRAY);
        accepted("MergeThenArray", replace(replace(MERGE_THEN_ARRAY, PICK, ""), "holder[0] = data;", ""));
        rejected("ArrayThenMerge", ARRAY_THEN_MERGE, ARRAY);
        rejected("ArrayThenMerge", replace(ARRAY_THEN_MERGE, "holder[0] = data;", ""), MERGE);
        accepted("ArrayThenMerge", replace(replace(ARRAY_THEN_MERGE, PICK, ""), "holder[0] = data;", ""));

        selectedSite("TwoStores", TWO_STORES, publication("TwoStores.second"), "second = data;");
        selectedSite("TwoStores", replace(TWO_STORES, "second = data;", "first = data;"),
                publication("TwoStores.first"), "first = data;", true);
        selectedSite("EscapeThenMerge", ESCAPE_THEN_MERGE,
                publication("EscapeThenMerge.saved"), "saved = data;");
        selectedSite("MergeThenArray", MERGE_THEN_ARRAY, MERGE, PICK);
        selectedSite("ArrayThenMerge", ARRAY_THEN_MERGE, ARRAY, "holder[0] = data;");
        selectedSite("InstanceStore", """
                class InstanceStore {
                    byte[] saved;
                    void check() {
                        byte[] data = new byte[16];
                        this.saved = data;
                        free data;
                    }
                }
                """, "allocation escapes through field 'saved'", "saved = data;");
        selectedSite("InexactArrayStore", """
                class InexactArrayStore {
                    static void check(int index) {
                        byte[] data = new byte[16];
                        Object[] holder = new Object[2];
                        holder[index] = data;
                        free data;
                    }
                }
                """, "allocation escapes through reference-array element", "holder[index] = data;");
        knownArrayStore();
    }

    static void joinedReasons() {
        String conflict = "allocation has conflicting ownership across if branches";
        rejected("DifferentFields", DIFFERENT_FIELDS, conflict);
        rejected("OneBranch", ONE_BRANCH, conflict);
        rejected("FieldOrCall", FIELD_OR_CALL, conflict);
        rejected("SameField", SAME_FIELD, publication("SameField.first"));
        // Making the branch summaries match changes the reason, not safety.
        rejected("DifferentFields", replace(DIFFERENT_FIELDS, "second = data;", "first = data;"),
                publication("DifferentFields.first"));

        accepted("DifferentFields", replace(replace(DIFFERENT_FIELDS, "first = data;", ""),
                "second = data;", ""));
        accepted("OneBranch", replace(ONE_BRANCH, "first = data;", ""));
        accepted("FieldOrCall", replace(replace(FIELD_OR_CALL, "first = data;", ""), "keep(data);", ""));
        accepted("SameField", replace(SAME_FIELD, "first = data;", ""));
        // The publishing branch exits before this join and does not reach its free.
        accepted("OneBranch", replace(ONE_BRANCH, "first = data;", "first = data;\n            return;"));
    }

    static void joinedExplanations() {
        joinNotes("DifferentFields", DIFFERENT_FIELDS,
                "allocation has conflicting ownership across if branches",
                "first = data;", "second = data;", "static field 'DifferentFields.first'",
                "static field 'DifferentFields.second'");
        joinNotes("OneBranch", ONE_BRANCH,
                "allocation has conflicting ownership across if branches",
                "first = data;", "if (flag)", "static field 'OneBranch.first'",
                "records no escape on this incoming path");
        joinNotes("FieldOrCall", FIELD_OR_CALL,
                "allocation has conflicting ownership across if branches",
                "first = data;", "keep(data);", "static field 'FieldOrCall.first'",
                "final call summary permits this escape");
        joinNotes("SameField", SAME_FIELD, publication("SameField.first"),
                "first = data;", "first = data;", "static field 'SameField.first'",
                "static field 'SameField.first'");
    }

    static void switchExplanations() {
        String classic = """
                class SwitchAlternatives {
                    static byte[] first;
                    static byte[] second;
                    static void check(int value) {
                        byte[] data = new byte[16];
                        switch (value) {
                            case 0: first = data; break;
                            default: second = data; break;
                        }
                        free data;
                    }
                }
                """;
        switchNotes("SwitchAlternatives", classic,
                "from case 0", "first = data;", "from default", "second = data;");
        switchNotes("SwitchAlternatives", replace(classic,
                        "case 0: first = data;", "case 0: case 1: first = data;"),
                "from case 0 or case 1", "first = data;",
                "from default", "second = data;");

        String modern = """
                class SwitchRules {
                    static byte[] first;
                    static byte[] second;
                    static void check(int value) {
                        byte[] data = new byte[16];
                        switch (value) {
                            case 0 -> first = data;
                            default -> second = data;
                        }
                        free data;
                    }
                }
                """;
        switchNotes("SwitchRules", modern,
                "from case 0", "first = data;", "from default", "second = data;");

        String unmatched = """
                class SwitchUnmatched {
                    static byte[] saved;
                    static void check(int value) {
                        byte[] data = new byte[16];
                        switch (value) {
                            case 0: saved = data; break;
                        }
                        free data;
                    }
                }
                """;
        switchBoundaryNotes("SwitchUnmatched", unmatched,
                "from case 0", "saved = data;", "when no case matches", "switch (value)");

        String fallthrough = """
                class SwitchFallthrough {
                    static byte[] saved;
                    static void check(int value) {
                        byte[] data = new byte[16];
                        switch (value) {
                            case 0: saved = data;
                            case 1: free data; break;
                            default: break;
                        }
                    }
                }
                """;
        switchBoundaryNotes("SwitchFallthrough", fallthrough,
                "direct dispatch to case 1", "case 1",
                "fallthrough from case 0", "saved = data;");
    }

    private static void switchBoundaryNotes(String name, String text,
                                            String firstLabel, String firstOperation,
                                            String secondLabel, String secondOperation) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed rejection or emitted artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.notes().isEmpty() && after.notes().size() == 3
                        && after.notes().get(0).message().startsWith(firstLabel)
                        && after.notes().get(1).message().startsWith(secondLabel)
                        && after.notes().get(0).span().start().line()
                        == lineOf(text, text.indexOf(firstOperation))
                        && after.notes().get(1).span().start().line()
                        == lineOf(text, text.indexOf(secondOperation)),
                name + " lost switch boundary paths: " + after);
    }

    private static void switchNotes(String name, String text,
                                    String firstLabel, String firstOperation,
                                    String secondLabel, String secondOperation) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                name + " changed rejection or emitted artifacts: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.severity() == after.severity()
                        && before.notes().isEmpty() && after.notes().size() == 3
                        && after.notes().get(0).message().startsWith(firstLabel)
                        && after.notes().get(1).message().startsWith(secondLabel)
                        && after.notes().get(0).span().start().line()
                        == lineOf(text, text.indexOf(firstOperation))
                        && after.notes().get(1).span().start().line()
                        == lineOf(text, text.indexOf(secondOperation)),
                name + " lost switch path or event sites: " + after);
    }

    private static void joinNotes(String name, String text, String reason,
                                  String firstOperation, String secondOperation,
                                  String firstDetail, String secondDetail) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!off.valid() && !on.valid() && off.program().isEmpty()
                        && on.program().isEmpty() && off.llvmIr().isEmpty()
                        && on.llvmIr().isEmpty() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1,
                name + " changed rejection or emitted an artifact: " + on.diagnostics());
        var before = off.diagnostics().getFirst();
        var after = on.diagnostics().getFirst();
        require(after.message().equals("cannot free 'data': " + reason)
                        && before.message().equals(after.message())
                        && before.span().equals(after.span())
                        && before.source().path().equals(after.source().path())
                        && before.severity() == after.severity()
                        && before.notes().isEmpty() && after.notes().size() == 3,
                name + " changed primary or lost alternatives: " + after);
        int first = text.indexOf(firstOperation);
        int second = firstOperation.equals(secondOperation)
                ? text.indexOf(secondOperation, first + firstOperation.length())
                : text.indexOf(secondOperation);
        require(first >= 0 && second >= 0
                        && after.notes().get(0).message().startsWith("when the condition is true")
                        && after.notes().get(0).message().contains(firstDetail)
                        && after.notes().get(1).message().startsWith("when the condition is false")
                        && after.notes().get(1).message().contains(secondDetail)
                        && after.notes().get(0).source().path().equals(before.source().path())
                        && after.notes().get(1).source().path().equals(before.source().path())
                        && after.notes().get(0).span().start().line() == lineOf(text, first)
                        && after.notes().get(1).span().start().line() == lineOf(text, second),
                name + " chose the wrong incoming branch sites: " + after);
    }

    private static int lineOf(String text, int offset) {
        return 1 + (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
    }

    static void eventLifetimes() {
        String lateEscape = replace(ESCAPE_THEN_MERGE, "saved = data;", "");
        lateEscape = replace(lateEscape, PICK, PICK + "\n        saved = data;");
        selectedSite("EscapeThenMerge", lateEscape,
                publication("EscapeThenMerge.saved"), "saved = data;");

        String freedThenStore = """
                class FreedThenStore {
                    static byte[] saved;
                    static void check() {
                        byte[] data = new byte[16];
                        free data;
                        saved = data;
                        free data;
                    }
                }
                """;
        CompilationArtifact freed = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("FreedThenStore.iron", freedThenStore)));
        var duplicate = freed.diagnostics().stream().filter(diagnostic ->
                diagnostic.message().contains("allocation was already freed"))
                .findFirst().orElseThrow();
        require(duplicate.notes().size() == 1 && duplicate.notes().getFirst().source() != null
                        && duplicate.notes().getFirst().span().start().line() == 5,
                "ignored escape after free displaced the earlier free: " + duplicate);

        String maybeFreedThenStore = """
                class MaybeFreedThenStore {
                    static byte[] saved;
                    static void check(boolean choice) {
                        byte[] data = new byte[16];
                        if (choice) { free data; }
                        saved = data;
                        free data;
                    }
                }
                """;
        CompilationArtifact maybe = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("MaybeFreedThenStore.iron", maybeFreedThenStore)));
        var laterFree = maybe.diagnostics().stream().filter(diagnostic ->
                diagnostic.message().startsWith("cannot free 'data'"))
                .findFirst().orElseThrow();
        require(laterFree.notes().size() == 1 && laterFree.notes().getFirst().source() == null,
                "ignored escape after maybe-freed state gained a false direct store: " + laterFree);

        String separatePaths = """
                class SeparatePaths {
                    static byte[] first;
                    static byte[] second;
                    static void check(boolean choice) {
                        byte[] data = new byte[16];
                        if (choice) {
                            first = data;
                            free data;
                        } else {
                            second = data;
                            free data;
                        }
                    }
                }
                """;
        CompilationArtifact paths = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("SeparatePaths.iron", separatePaths)));
        var freeErrors = paths.diagnostics().stream().filter(diagnostic ->
                diagnostic.message().startsWith("cannot free 'data'"))
                .toList();
        require(freeErrors.size() == 2
                        && freeErrors.get(0).notes().getFirst().span().start().line() == 7
                        && freeErrors.get(1).notes().getFirst().span().start().line() == 10,
                "restored branch used a sibling path's direct event: " + freeErrors);
    }

    static void borrowedOwnerMerges() {
        ownerMerge("TwoOwners", "selected = second.iterator();", 2);
        ownerMerge("MixedOwner", "selected = other;", 1);
        ownerMerge("SameOwner", "selected = first.iterator();", 0);
        ownerMerge("NullableOwner", "selected = null;", 0);

        String exceptional = """
                import ironwood.ds.ArrayList;
                import ironwood.util.Iterator;
                class Failure extends Exception { }
                class Probe {
                    static void mayThrow() throws Failure { throw new Failure(); }
                    static void check(boolean flag) {
                        ArrayList<String> first = new ArrayList<String>();
                        ArrayList<String> second = new ArrayList<String>();
                        Iterator<String> selected = null;
                        try {
                            if (flag) { selected = first.iterator(); mayThrow(); }
                            else { selected = second.iterator(); }
                        } catch (Failure failure) {
                            selected = null;
                            free first;
                            return;
                        }
                        selected = null;
                        free first;
                        free second;
                    }
                }
                """;
        var off = analyze("ExceptionalOwnerMerge", exceptional).diagnostics().stream()
                .filter(diagnostic -> diagnostic.message().startsWith("cannot free"))
                .toList();
        var on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("ExceptionalOwnerMerge.iron", exceptional)))
                .diagnostics().stream().filter(diagnostic ->
                        diagnostic.message().startsWith("cannot free"))
                .toList();
        require(off.size() == 2 && on.size() == 2
                        && on.get(0).span().start().line() == 19
                        && on.get(1).span().start().line() == 20,
                "exceptional predecessor inherited the normal owner conflict: " + on);
        for (int index = 0; index < on.size(); index++) {
            require(off.get(index).message().equals(on.get(index).message())
                            && off.get(index).span().equals(on.get(index).span())
                            && on.get(index).notes().size() == 1
                            && on.get(index).notes().getFirst().source() == null,
                    "exceptional owner merge changed primary or invented a witness: " + on);
        }
    }

    private static void ownerMerge(String name, String otherBranch, int expectedErrors) {
        String text = """
                import ironwood.ds.ArrayList;
                import ironwood.util.Iterator;
                class Probe {
                    static void check(boolean flag, Iterator<String> other) {
                        ArrayList<String> first = new ArrayList<String>();
                        ArrayList<String> second = new ArrayList<String>();
                        Iterator<String> selected = null;
                        if (flag) { selected = first.iterator(); }
                        else { %s }
                        selected = null;
                        free first;
                        free second;
                    }
                }
                """.formatted(otherBranch);
        var off = analyze(name, text).diagnostics().stream().filter(diagnostic ->
                diagnostic.isError()).toList();
        var on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)))
                .diagnostics().stream().filter(diagnostic -> diagnostic.isError()).toList();
        require(off.size() == expectedErrors && on.size() == expectedErrors,
                name + " changed owner-merge safety outcome: " + on);
        for (int index = 0; index < on.size(); index++) {
            var primary = on.get(index);
            require(primary.message().equals(off.get(index).message())
                            && primary.span().equals(off.get(index).span())
                            && primary.message().contains(
                            "allocation has conflicting borrowed-helper ownership across control flow")
                            && primary.notes().size() == 1
                            && primary.notes().getFirst().source() == null,
                    name + " selected a false owner or changed the primary: " + primary);
        }
    }

    private static void knownArrayStore() {
        String text = """
                class KnownArrayStore {
                    static void check() {
                        byte[] data = new byte[16];
                        Object[] holder = new Object[1];
                        holder[0] = data;
                        free data;
                    }
                }
                """;
        CompilationArtifact off = analyze("KnownArrayStore", text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("KnownArrayStore.iron", text)));
        var offError = off.diagnostics().stream().filter(diagnostic -> diagnostic.isError())
                .findFirst().orElseThrow();
        var onError = on.diagnostics().stream().filter(diagnostic -> diagnostic.isError())
                .findFirst().orElseThrow();
        require(offError.message().equals(onError.message())
                        && offError.span().equals(onError.span())
                        && onError.message().contains("known array element [0]")
                        && onError.notes().size() == 1
                        && onError.notes().getFirst().span().start().line() == 5
                        && onError.notes().getFirst().message().contains("array element [0]"),
                "known array element lost its selected store: " + onError);
        accepted("KnownArrayStore", replace(text, "holder[0] = data;",
                "holder[0] = data;\n        holder[0] = null;"));
    }

    private static String publication(String field) {
        return "allocation escapes through static field '" + field + "'";
    }

    private static void rejected(String name, String source, String reason) {
        CompilationArtifact artifact = analyze(name, source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                name + " rejected source produced a program");
        var errors = artifact.diagnostics().stream().filter(diagnostic -> diagnostic.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        require(error.message().equals("cannot free 'data': " + reason),
                name + " wrong selected reason: " + error.message());
        int targetOffset = source.indexOf("free data;") + "free ".length();
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().offset() == targetOffset
                        && error.span().end().offset() == targetOffset + "data".length(),
                name + " primary no longer points at the free target: " + error.span());
    }

    private static void accepted(String name, String source) {
        CompilationArtifact artifact = analyze(name, source);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                name + " safe control rejected: " + artifact.diagnostics());
    }

    private static void selectedSite(String name, String text, String reason, String operation) {
        selectedSite(name, text, reason, operation, false);
    }

    private static void selectedSite(String name, String text, String reason,
                                     String operation, boolean lastOccurrence) {
        CompilationArtifact off = analyze(name, text);
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
        require(!on.valid() && on.diagnostics().size() == off.diagnostics().size(),
                name + " changed rejection count with explanations");
        var error = on.diagnostics().stream().filter(diagnostic -> diagnostic.isError())
                .findFirst().orElseThrow();
        require(error.message().equals("cannot free 'data': " + reason)
                        && error.notes().size() == 1,
                name + " lost selected reason or detail: " + error);
        var note = error.notes().getFirst();
        int operationOffset = lastOccurrence ? text.lastIndexOf(operation) : text.indexOf(operation);
        int expectedLine = 1 + (int) text.substring(0, operationOffset).chars()
                .filter(character -> character == '\n').count();
        require(operationOffset >= 0 && note.source() != null
                        && note.source().path().toString().equals(name + ".iron")
                        && note.span().start().line() == expectedLine
                        && note.message().contains(reason),
                name + " selected the wrong source operation: " + note);
        require(off.diagnostics().stream().allMatch(diagnostic -> diagnostic.notes().isEmpty()),
                name + " disabled mode retained source evidence");
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
