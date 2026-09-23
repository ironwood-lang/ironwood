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
