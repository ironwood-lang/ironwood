// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrBranch;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrUnreachable;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

/** Source reachability and typed-control-flow checks for literal-true loops. */
final class LoopReachabilityTests {
    private LoopReachabilityTests() {}

    static void unreachableTails() {
        for (String loop : List.of(
                "while (true) { }",
                "while ((true)) { continue; }",
                "for (;;) { }",
                "for (; true;) { continue; }",
                "do { } while (true);",
                "do { continue; } while (true);",
                "while (true) { while (true) { break; } }",
                "outer: for (;;) { while (true) { continue outer; } }",
                "while (true) { switch (1) { default: break; } }",
                "while (true) { try { break; } finally { continue; } }",
                "for (;;) { try { break; } finally { return 1; } }",
                "do { return 1; } while (true);")) {
            String source = "class Main { public static int main(String[] args) { return value(); } "
                    + "static int value() { " + loop + " return 42; } }";
            CompilationArtifact artifact = compile(source);
            require(!artifact.successful(), "unreachable tail compiled: " + loop);
            require(artifact.diagnostics().stream().anyMatch(diagnostic -> diagnostic.isError()
                            && diagnostic.message().equals("unreachable statement")
                            && diagnostic.span().start().offset() == source.lastIndexOf("return 42;")),
                    "missing error at the unreachable statement: " + artifact.diagnostics());
        }
    }

    static void completionAndTypedIr() {
        CompilationArtifact artifact = compile("""
                class Main {

                    static int spinWhile() {

                        while (true) { }
                    }

                    static int spinFor() {

                        for (;;) { }
                    }

                    static int spinDo() {

                        do { } while (true);
                    }

                    static int returned() {

                        while (true) { return 42; }
                    }

                    static int breaks() {

                        int value = 0;
                        while (true) { value++; break; }
                        for (;;) { value++; break; }
                        do { value++; break; } while (true);
                        return value;
                    }

                    static int conditionalBreak() {

                        while (true) { if (false) break; }
                        return 42;
                    }

                    static void constantIf() {

                        if (true) return;
                        if (1 == 1) return;
                        while (true) {
                            if (true) break;
                            int accepted = 1;
                        }
                    }

                    static int variableCondition(boolean running) {

                        while (running) { running = false; }
                        for (; running;) { running = false; }
                        do { running = false; } while (running);
                        return 42;
                    }

                    static int methodCondition() {

                        while (condition()) { }
                        return 42;
                    }

                    static boolean condition() {

                        return true;
                    }

                    public static int main(String[] args) {

                        return breaks();
                    }
                }
                """);
        require(artifact.successful(), artifact.diagnostics().toString());
        var functions = artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals("Main")
                        && Set.of("spinWhile", "spinFor", "spinDo").contains(function.sourceName()))
                .toList();
        require(functions.size() == 3, "missing endless-loop functions");
        for (var function : functions) {
            require(function.blocks().stream().noneMatch(block ->
                            block.terminator() instanceof IrReturnTerminator
                                    || block.terminator() instanceof IrBranch),
                    "endless loop retained a false exit or return: " + function.sourceName());
            require(function.blocks().stream().anyMatch(block ->
                            block.terminator() instanceof IrUnreachable),
                    "missing terminated unreachable exit: " + function.sourceName());
        }
    }

    static void reclamationProofs() {
        String prefix = "class Box { int number; } class Main { "
                + "public static int main(String[] args) { return value(); } "
                + "static int value() { Box value = new Box(); ";
        for (String loop : List.of("while (true)", "for (;;)", "do")) {
            String suffix = loop.equals("do") ? " while (true);" : "";
            String freed = prefix + loop + " { free value; break; }" + suffix;
            CompilationArtifact safe = compile(freed + " return 42; } }");
            require(safe.successful(), safe.diagnostics().toString());
            for (String tail : List.of("return value.number;", "free value; return 42;")) {
                CompilationArtifact unsafe = compile(freed + tail + " } }");
                require(!unsafe.successful() && unsafe.diagnostics().stream().anyMatch(diagnostic ->
                                diagnostic.isError() && diagnostic.message().contains("freed")),
                        "reused freed allocation was accepted: " + unsafe.diagnostics());
            }
            CompilationArtifact backEdge = compile(prefix + loop + " { free value; continue; }"
                    + suffix + " } }");
            require(!backEdge.successful() && backEdge.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.isError() && diagnostic.message().contains("loop back edge")),
                    "freed allocation crossed a back edge: " + backEdge.diagnostics());
        }
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.OFF).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
