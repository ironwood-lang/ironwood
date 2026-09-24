// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeEvidenceBaselineTests {
    private static final String TWO_PATH_FREE = """
            class TwoPathFree {

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        free data;
                    } else {
                        free data;
                    }
                    free data;
                }
            }
            """;
    private static final String RETURNED_FREE = """
            class ReturnedFree {

                static void example(boolean flag) {

                    byte[] data = new byte[16];
                    if (flag) {
                        free data;
                        return;
                    }
                    free data;
                    free data;
                }
            }
            """;
    private static final String HOLDER_LOCAL = """
            class HolderLocal {

                private byte[] buffer;

                HolderLocal(byte[] input) {

                    buffer = input;
                }

                void drop() {

                    byte[] local = buffer;
                    free local;
                }
            }
            """;
    private static final String PAIR_LOCAL = """
            class PairLocal {

                private byte[] first = new byte[16];
                private byte[] second;

                void share() {

                    second = first;
                }

                void drop() {

                    byte[] local = first;
                    free local;
                }
            }
            """;

    private FreeEvidenceBaselineTests() {}

    static void evidenceBoundaries() {
        // Both branch frees contribute at the join; the returning branch does
        // not contribute to the later free in the second fixture.
        String alreadyFreed = "cannot free 'data': allocation was already freed";
        rejected("TwoPathFree", TWO_PATH_FREE, alreadyFreed);
        rejected("ReturnedFree", RETURNED_FREE, alreadyFreed);
        accepted("TwoPathFree", removeLastFree(TWO_PATH_FREE));
        accepted("ReturnedFree", removeLastFree(RETURNED_FREE));

        rejected("HolderLocal", HOLDER_LOCAL, "cannot prove free of 'local' safe: value is not a known allocation "
                + "created by new in this method, returned by a proven fresh factory, "
                + "or a proven detached private backing array");
        rejected("PairLocal", PAIR_LOCAL,
                "cannot free 'local': allocation is still reachable through private field 'first'");
        // Proved fresh, unshared storage can be detached before reclamation.
        accepted("HolderLocal", HOLDER_LOCAL.replace("buffer = input;", "buffer = new byte[16];")
                .replace("free local;", "buffer = null;\n        free local;"));
        accepted("PairLocal", PAIR_LOCAL.replace("second = first;", "")
                .replace("free local;", "first = null;\n        free local;"));

        String holderDestructor = destructor(HOLDER_LOCAL, "buffer");
        String pairDestructor = destructor(PAIR_LOCAL, "first");
        rejected("HolderLocal", holderDestructor,
                "cannot prove destructor free of field 'buffer' safe: field ownership is uncertain");
        rejected("PairLocal", pairDestructor,
                "cannot prove destructor free of field 'first' safe: field ownership is uncertain");
        accepted("HolderLocal", holderDestructor.replace("buffer = input;", "buffer = new byte[16];"));
        accepted("PairLocal", pairDestructor.replace("second = first;", ""));
    }

    static void fieldLoadProvenance() {
        SourceFile source = SourceFile.of("HolderLocal.iron", HOLDER_LOCAL);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && off.program().isEmpty()
                        && on.program().isEmpty() && off.llvmIr().isEmpty()
                        && on.llvmIr().isEmpty() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1,
                "HolderLocal field load changed rejection or output: " + on.diagnostics());
        var prior = off.diagnostics().getFirst();
        var explained = on.diagnostics().getFirst();
        int load = HOLDER_LOCAL.indexOf("byte[] local = buffer;")
                + "byte[] local = ".length();
        require(explained.message().equals(prior.message())
                        && explained.span().equals(prior.span())
                        && prior.notes().isEmpty() && explained.notes().size() == 2
                        && explained.notes().get(0).message().contains("no proven fresh allocation origin")
                        && explained.notes().get(1).message().contains("loaded from private field 'buffer'")
                        && explained.notes().get(1).source().path().equals(source.path())
                        && explained.notes().get(1).span().start().offset() == load,
                "HolderLocal field load lost its source association: " + explained);

        SourceFile pair = SourceFile.of("PairLocal.iron", PAIR_LOCAL);
        CompilationArtifact pairOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(pair));
        CompilationArtifact pairOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(pair));
        require(!pairOff.valid() && !pairOn.valid() && pairOff.program().isEmpty()
                        && pairOn.program().isEmpty() && pairOff.llvmIr().isEmpty()
                        && pairOn.llvmIr().isEmpty() && pairOff.diagnostics().size() == 1
                        && pairOn.diagnostics().size() == 1,
                "PairLocal field load changed rejection or output: " + pairOn.diagnostics());
        var pairPrior = pairOff.diagnostics().getFirst();
        var pairExplained = pairOn.diagnostics().getFirst();
        require(pairExplained.message().equals(pairPrior.message())
                        && pairExplained.span().equals(pairPrior.span())
                        && pairExplained.message().contains("private field 'first'")
                        && pairExplained.notes().stream().noneMatch(note ->
                        note.message().contains("private field 'second'")),
                "PairLocal replaced the selected attached-field blocker: " + pairExplained);
    }

    static void earlierFreeExplanations() {
        earlierFree("TwoPathFree", TWO_PATH_FREE, false);
        earlierFree("ReturnedFree", RETURNED_FREE, true);
        String replacement = """
                class FreshReplacement {
                    static void check() {
                        byte[] data = new byte[16];
                        free data;
                        data = new byte[16];
                        free data;
                        free data;
                    }
                }
                """;
        earlierFree("FreshReplacement", replacement, true);
    }

    private static void earlierFree(String name, String text, boolean uniquePredecessor) {
        SourceFile source = SourceFile.of(name + ".iron", text);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && off.program().isEmpty()
                        && on.program().isEmpty() && off.llvmIr().isEmpty()
                        && on.llvmIr().isEmpty() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1,
                name + " changed rejection or emitted an artifact: " + on.diagnostics());
        var primary = on.diagnostics().getFirst();
        var previous = off.diagnostics().getFirst();
        require(primary.message().equals(previous.message())
                        && primary.span().equals(previous.span())
                        && primary.message().equals(
                        "cannot free 'data': allocation was already freed"),
                name + " changed the primary or lost its note: " + primary);
        if (uniquePredecessor) {
            int last = text.lastIndexOf("free data;");
            int earlier = text.lastIndexOf("free data;", last - 1);
            require(primary.notes().size() == 1
                            && primary.notes().getFirst().message().equals(
                            "the same allocation was freed here")
                            && primary.notes().getFirst().source().path().equals(source.path())
                            && primary.notes().getFirst().span().start().offset() == earlier,
                    name + " selected a non-reaching or replaced free: " + primary);
        } else {
            int first = text.indexOf("free data;");
            int second = text.indexOf("free data;", first + 1);
            require(primary.notes().size() == 2
                            && primary.notes().get(0).message().startsWith(
                            "when the condition is true")
                            && primary.notes().get(1).message().startsWith(
                            "when the condition is false")
                            && primary.notes().get(0).span().start().offset() == first
                            && primary.notes().get(1).span().start().offset() == second,
                    name + " lost reaching earlier-free alternatives: " + primary);
        }
    }

    private static String destructor(String source, String field) {
        return source.replace("void drop()", "destructor")
                .replace("        byte[] local = " + field + ";\n", "")
                .replace("free local;", "free " + field + ";");
    }

    private static String removeLastFree(String source) {
        int start = source.lastIndexOf("free data;");
        require(start >= 0, "missing final free in fixture");
        return source.substring(0, start) + source.substring(start + "free data;".length());
    }

    private static void rejected(String name, String source, String message) {
        CompilationArtifact artifact = analyze(name, source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                name + " invalid source produced a program");
        var errors = artifact.diagnostics().stream().filter(diagnostic -> diagnostic.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        require(error.message().equals(message), name + " wrong diagnostic: " + error.message());
        int start = source.lastIndexOf("free ") + "free ".length();
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().offset() == start
                        && error.span().end().offset() == source.indexOf(';', start),
                name + " primary moved: " + error.span());
    }

    private static void accepted(String name, String source) {
        CompilationArtifact artifact = analyze(name, source);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                name + " safe control rejected: " + artifact.diagnostics());
    }

    private static CompilationArtifact analyze(String name, String source) {
        return new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of(name + ".iron", source)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
