// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/** Package-local smoke coverage for exact generic views inherited by intersection bounds. */
public final class GenericIntersectionConsistencySmoke {
    private static final String CONFLICT = "conflicting exact arguments";

    private GenericIntersectionConsistencySmoke() {
    }

    public static void main(String[] arguments) {
        assertConflict("direct", """
                interface G<T> {}
                class A {}
                class B {}
                interface Left extends G<A> {}
                interface Right extends G<B> {}
                class Broken<T extends Left & Right> {}
                """);
        assertConflict("dependent-transitive", """
                interface G<T> {}
                interface Mid<T> extends G<T> {}
                class A {}
                class B {}
                interface DeepLeft extends Mid<A> {}
                interface DeepRight extends G<B> {}
                class Broken<U extends DeepLeft, T extends U & DeepRight> {}
                """);
        assertNoConflict("same-argument", """
                interface G<T> {}
                class A {}
                interface Left extends G<A> {}
                interface Right extends G<A> {}
                class Legal<T extends Left & Right> {}
                """);
    }

    private static void assertConflict(String name, String source) {
        List<Diagnostic> diagnostics = analyze(name, source);
        long count = diagnostics.stream().filter(diagnostic ->
                diagnostic.message().contains(CONFLICT)).count();
        if (count != 1) {
            throw new AssertionError(name + " expected one intersection conflict but received "
                    + count + ": " + diagnostics);
        }
    }

    private static void assertNoConflict(String name, String source) {
        List<Diagnostic> diagnostics = analyze(name, source);
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(CONFLICT))) {
            throw new AssertionError(name + " unexpectedly failed: " + diagnostics);
        }
    }

    private static List<Diagnostic> analyze(String name, String text) {
        SourceFile source = SourceFile.of("test/" + name + ".iron", text);
        LexResult lexed = new Lexer(source).lex();
        if (!lexed.diagnostics().isEmpty()) {
            throw new AssertionError(name + " did not lex: " + lexed.diagnostics());
        }
        ParseResult parsed = new Parser(source, lexed.tokens()).parse();
        if (!parsed.diagnostics().isEmpty() || parsed.unit().isEmpty()) {
            throw new AssertionError(name + " did not parse: " + parsed.diagnostics());
        }
        CompilationUnit unit = parsed.unit().orElseThrow();
        return new SemanticAnalyzer().analyze(List.of(unit), false).diagnostics();
    }
}
