// SPDX-License-Identifier: MIT OR Apache-2.0

// Generates the Ironwood TextMate grammar consumed by the Eclipse plugin.
//
// The keyword set is not written by hand. Every TokenKind whose lowercase name
// lexes back to that same kind is a keyword, so the generator asks the real
// compiler lexer instead of maintaining a parallel list. The categories below
// only decide which TextMate scope each keyword gets. A keyword the lexer knows
// but no category claims fails the build, so adding a keyword to the compiler
// without coloring it is caught here rather than shipping uncolored.
//
// Run with the compiler classes on the classpath:
//   java -cp compiler/build/ironwoodc.jar \
//       ide/eclipse/tools/GenerateGrammar.java <template.json> <output.json>

import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.lexer.Token;
import ironwood.compiler.lexer.TokenKind;
import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GenerateGrammar {

    // Template placeholder name -> the keywords that placeholder expands to.
    // Every keyword the lexer recognizes must appear in exactly one category.
    private static final Map<String, Set<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        category("reclamation", "free", "destructor");
        category("control", "if", "else", "while", "do", "for", "switch", "case",
                "break", "continue", "return", "yield", "throw", "try", "catch",
                "finally");
        category("modifier", "public", "protected", "private", "abstract",
                "static", "final", "default", "throws");
        category("primitive", "byte", "short", "int", "long", "char", "float",
                "double", "boolean", "void");
        category("declaration", "package", "import", "class", "interface",
                "enum", "extends", "implements", "new", "instanceof");
        category("constant", "true", "false", "null");
        category("languageVariable", "this", "super");
    }

    private static void category(String placeholder, String... keywords) {
        CATEGORIES.put(placeholder, new TreeSet<>(List.of(keywords)));
    }

    // Kinds that are not spelled as words, which would otherwise self-match in
    // the keyword probe below.
    private static final Set<TokenKind> NON_KEYWORD_KINDS = Set.of(
            TokenKind.IDENTIFIER, TokenKind.INTEGER, TokenKind.FLOATING,
            TokenKind.CHARACTER, TokenKind.STRING, TokenKind.EOF);

    private static final Pattern PLACEHOLDER = Pattern.compile("@@([A-Za-z]+)@@");

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: GenerateGrammar <template.json> <output.json>");
            System.exit(2);
        }

        Set<String> lexerKeywords = discoverKeywords();
        verifyCategoriesCoverLexer(lexerKeywords);

        Path template = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String grammar = expand(Files.readString(template, StandardCharsets.UTF_8));

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, grammar, StandardCharsets.UTF_8);
        System.out.println("generated " + output + " from " + lexerKeywords.size()
                + " lexer keywords in " + CATEGORIES.size() + " categories");
    }

    // A TokenKind is a keyword when its own lowercase name lexes back to it.
    // TokenKind.INT lexes "int" to INT, while LEFT_BRACE lexes "left_brace" to
    // IDENTIFIER and is therefore not a keyword.
    private static Set<String> discoverKeywords() {
        Set<String> keywords = new TreeSet<>();
        for (TokenKind kind : TokenKind.values()) {
            if (NON_KEYWORD_KINDS.contains(kind)) {
                continue;
            }
            String candidate = kind.name().toLowerCase(Locale.ROOT);
            LexResult result = new Lexer(SourceFile.of("keyword-probe", candidate)).lex();
            if (!result.diagnostics().isEmpty()) {
                continue;
            }
            List<Token> tokens = result.tokens();
            if (tokens.size() == 2 && tokens.get(0).kind() == kind
                    && tokens.get(0).lexeme().equals(candidate)) {
                keywords.add(candidate);
            }
        }
        if (keywords.isEmpty()) {
            throw new IllegalStateException("lexer probe found no keywords; the probe is broken");
        }
        return keywords;
    }

    private static void verifyCategoriesCoverLexer(Set<String> lexerKeywords) {
        Set<String> categorized = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Set<String> keywords : CATEGORIES.values()) {
            for (String keyword : keywords) {
                if (!categorized.add(keyword)) {
                    duplicates.add(keyword);
                }
            }
        }

        Set<String> uncategorized = new TreeSet<>(lexerKeywords);
        uncategorized.removeAll(categorized);
        Set<String> unknown = new TreeSet<>(categorized);
        unknown.removeAll(lexerKeywords);

        List<String> problems = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            problems.add("keywords claimed by more than one category: " + duplicates);
        }
        if (!uncategorized.isEmpty()) {
            problems.add("lexer keywords missing from GenerateGrammar categories: "
                    + uncategorized);
        }
        if (!unknown.isEmpty()) {
            problems.add("categorized words the lexer does not treat as keywords: " + unknown);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    // Replaces every @@category@@ with its alternation and fails on a
    // placeholder no category defines or a category the template never uses.
    private static String expand(String template) {
        Set<String> used = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            Set<String> keywords = CATEGORIES.get(placeholder);
            if (keywords == null) {
                throw new IllegalStateException(
                        "template uses unknown placeholder @@" + placeholder + "@@");
            }
            used.add(placeholder);
            matcher.appendReplacement(expanded,
                    Matcher.quoteReplacement(String.join("|", keywords)));
        }
        matcher.appendTail(expanded);

        Set<String> unusedCategories = new TreeSet<>(CATEGORIES.keySet());
        unusedCategories.removeAll(used);
        if (!unusedCategories.isEmpty()) {
            throw new IllegalStateException(
                    "categories never referenced by the template: " + unusedCategories);
        }
        return expanded.toString();
    }
}
