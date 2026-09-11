// SPDX-License-Identifier: MIT OR Apache-2.0

// Verifies the generated Ironwood TextMate grammar by running it through the
// same TM4E engine Eclipse uses, with no workbench and no display.
//
// Each expectation names a line, the exact text of a token on that line, and
// the TextMate scope that token must carry. Tokenization walks the file line by
// line and carries the rule stack forward, so multi-line constructs such as
// text blocks and block comments are exercised the way the editor sees them.
//
//   java -cp <tm4e jars> ide/eclipse/tools/VerifyGrammar.java \
//       <grammar.json> <fixture.iron>

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VerifyGrammar {

    // Token text -> the scope that token must carry somewhere in its scope
    // stack. Scope matching is by prefix, matching how TextMate themes select.
    private static final Map<String, String> EXPECTED_SCOPES = new LinkedHashMap<>();

    static {
        // The Ironwood-specific reclamation keywords are the point of the
        // exercise: a Java grammar would color neither.
        EXPECTED_SCOPES.put("free", "keyword.control.reclamation.ironwood");
        EXPECTED_SCOPES.put("destructor", "keyword.control.reclamation.ironwood");

        EXPECTED_SCOPES.put("package", "keyword.other.ironwood");
        EXPECTED_SCOPES.put("class", "keyword.other.ironwood");
        EXPECTED_SCOPES.put("instanceof", "keyword.other.ironwood");
        EXPECTED_SCOPES.put("public", "storage.modifier.ironwood");
        EXPECTED_SCOPES.put("private", "storage.modifier.ironwood");
        EXPECTED_SCOPES.put("static", "storage.modifier.ironwood");
        EXPECTED_SCOPES.put("final", "storage.modifier.ironwood");
        EXPECTED_SCOPES.put("int", "storage.type.primitive.ironwood");
        EXPECTED_SCOPES.put("char", "storage.type.primitive.ironwood");
        EXPECTED_SCOPES.put("double", "storage.type.primitive.ironwood");
        EXPECTED_SCOPES.put("return", "keyword.control.ironwood");
        EXPECTED_SCOPES.put("switch", "keyword.control.ironwood");
        EXPECTED_SCOPES.put("finally", "keyword.control.ironwood");
        EXPECTED_SCOPES.put("continue", "keyword.control.ironwood");
        EXPECTED_SCOPES.put("this", "variable.language.ironwood");

        // Ironwood has three built-in directives and no general
        // annotations, so @Override is a directive rather than an annotation
        // of arbitrary shape.
        EXPECTED_SCOPES.put("@Override", "storage.type.annotation.ironwood");
        EXPECTED_SCOPES.put("@SuppressUnfreed", "storage.type.annotation.ironwood");

        EXPECTED_SCOPES.put("0xFF_FF", "constant.numeric.hex.ironwood");
        EXPECTED_SCOPES.put("0b1010_0101", "constant.numeric.binary.ironwood");
        EXPECTED_SCOPES.put("9_000_000_000L", "constant.numeric.integer.ironwood");
        EXPECTED_SCOPES.put("1.5e-3", "constant.numeric.float.ironwood");
        EXPECTED_SCOPES.put("2.0f", "constant.numeric.float.ironwood");

        EXPECTED_SCOPES.put("Highlighting", "entity.name.type");
    }

    // Text that must appear inside a scope, checked by scanning every token on
    // the line rather than by exact token text, since strings and comments are
    // split into several tokens.
    private static final List<ContainmentExpectation> CONTAINMENTS = List.of(
            new ContainmentExpectation("escaped:", "string.quoted.triple.ironwood",
                    "cooked text block body"),
            new ContainmentExpectation("stays literal", "string.quoted.triple.raw.ironwood",
                    "raw text block body"),
            new ContainmentExpectation("Ordinary block comment", "comment.block.ironwood",
                    "block comment body"),
            new ContainmentExpectation("Documented type", "comment.block.documentation.ironwood",
                    "IronDocs comment body"),
            new ContainmentExpectation("Syntax-highlighting fixture",
                    "comment.line.double-slash.ironwood", "line comment body"),
            new ContainmentExpectation("tab", "string.quoted.double.ironwood",
                    "double-quoted string body"));

    private record ContainmentExpectation(String text, String scope, String description) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: VerifyGrammar <grammar.json> <fixture.iron>");
            System.exit(2);
        }

        Path grammarPath = Path.of(args[0]);
        Path fixturePath = Path.of(args[1]);

        Registry registry = new Registry();
        IGrammar grammar = registry.addGrammar(IGrammarSource.fromFile(grammarPath));
        if (grammar == null) {
            System.err.println("error: grammar failed to load: " + grammarPath);
            System.exit(1);
        }

        List<String> lines = Files.readAllLines(fixturePath, StandardCharsets.UTF_8);
        List<ScopedToken> scopedTokens = tokenize(grammar, lines);

        List<String> failures = new ArrayList<>();
        checkTokenScopes(scopedTokens, failures);
        checkContainments(scopedTokens, failures);

        if (!failures.isEmpty()) {
            System.err.println("grammar verification failed with "
                    + failures.size() + " problem(s):");
            failures.forEach(failure -> System.err.println("  " + failure));
            System.exit(1);
        }

        System.out.println("grammar verification passed: "
                + EXPECTED_SCOPES.size() + " token scopes and "
                + CONTAINMENTS.size() + " span scopes over "
                + lines.size() + " lines");
    }

    private record ScopedToken(int line, String text, List<String> scopes) {
    }

    private static List<ScopedToken> tokenize(IGrammar grammar, List<String> lines) {
        List<ScopedToken> scopedTokens = new ArrayList<>();
        IStateStack ruleStack = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            ITokenizeLineResult<IToken[]> result =
                    grammar.tokenizeLine(line, ruleStack, null);
            ruleStack = result.getRuleStack();
            for (IToken token : result.getTokens()) {
                int start = Math.min(token.getStartIndex(), line.length());
                int end = Math.min(token.getEndIndex(), line.length());
                if (start >= end) {
                    continue;
                }
                scopedTokens.add(new ScopedToken(index + 1,
                        line.substring(start, end), token.getScopes()));
            }
        }
        return scopedTokens;
    }

    // Every expected token must appear at least once, and every occurrence of
    // it as a standalone token must carry the expected scope. A keyword that
    // the fixture never contains is a broken expectation, not a pass.
    private static void checkTokenScopes(List<ScopedToken> scopedTokens, List<String> failures) {
        for (Map.Entry<String, String> expectation : EXPECTED_SCOPES.entrySet()) {
            String text = expectation.getKey();
            String scope = expectation.getValue();
            int matches = 0;
            for (ScopedToken token : scopedTokens) {
                if (!token.text().trim().equals(text)) {
                    continue;
                }
                matches++;
                if (!hasScope(token, scope)) {
                    failures.add("line " + token.line() + ": '" + text
                            + "' has scopes " + token.scopes()
                            + " but expected one starting with '" + scope + "'");
                }
            }
            if (matches == 0) {
                failures.add("the fixture never produces a standalone token '" + text
                        + "', so its expected scope '" + scope + "' is untested");
            }
        }
    }

    private static void checkContainments(List<ScopedToken> scopedTokens, List<String> failures) {
        for (ContainmentExpectation expectation : CONTAINMENTS) {
            boolean satisfied = scopedTokens.stream()
                    .filter(token -> token.text().contains(expectation.text()))
                    .anyMatch(token -> hasScope(token, expectation.scope()));
            if (!satisfied) {
                failures.add("no token containing '" + expectation.text() + "' ("
                        + expectation.description() + ") carries scope '"
                        + expectation.scope() + "'");
            }
        }
    }

    private static boolean hasScope(ScopedToken token, String expectedScope) {
        return token.scopes().stream().anyMatch(scope -> scope.startsWith(expectedScope));
    }
}
