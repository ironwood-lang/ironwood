// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.lexer;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lexer {
    private static final Map<String, TokenKind> KEYWORDS = Map.ofEntries(
            Map.entry("class", TokenKind.CLASS),
            Map.entry("enum", TokenKind.ENUM),
            Map.entry("interface", TokenKind.INTERFACE),
            Map.entry("package", TokenKind.PACKAGE),
            Map.entry("import", TokenKind.IMPORT),
            Map.entry("extends", TokenKind.EXTENDS),
            Map.entry("implements", TokenKind.IMPLEMENTS),
            Map.entry("instanceof", TokenKind.INSTANCEOF),
            Map.entry("public", TokenKind.PUBLIC),
            Map.entry("protected", TokenKind.PROTECTED),
            Map.entry("private", TokenKind.PRIVATE),
            Map.entry("abstract", TokenKind.ABSTRACT),
            Map.entry("default", TokenKind.DEFAULT),
            Map.entry("static", TokenKind.STATIC),
            Map.entry("final", TokenKind.FINAL),
            Map.entry("new", TokenKind.NEW),
            Map.entry("null", TokenKind.NULL),
            Map.entry("this", TokenKind.THIS),
            Map.entry("super", TokenKind.SUPER),
            Map.entry("byte", TokenKind.BYTE),
            Map.entry("short", TokenKind.SHORT),
            Map.entry("int", TokenKind.INT),
            Map.entry("long", TokenKind.LONG),
            Map.entry("char", TokenKind.CHAR),
            Map.entry("float", TokenKind.FLOAT),
            Map.entry("double", TokenKind.DOUBLE),
            Map.entry("boolean", TokenKind.BOOLEAN),
            Map.entry("void", TokenKind.VOID),
            Map.entry("return", TokenKind.RETURN),
            Map.entry("yield", TokenKind.YIELD),
            Map.entry("free", TokenKind.FREE),
            Map.entry("destructor", TokenKind.DESTRUCTOR),
            Map.entry("throw", TokenKind.THROW),
            Map.entry("throws", TokenKind.THROWS),
            Map.entry("try", TokenKind.TRY),
            Map.entry("catch", TokenKind.CATCH),
            Map.entry("finally", TokenKind.FINALLY),
            Map.entry("if", TokenKind.IF),
            Map.entry("else", TokenKind.ELSE),
            Map.entry("while", TokenKind.WHILE),
            Map.entry("do", TokenKind.DO),
            Map.entry("for", TokenKind.FOR),
            Map.entry("switch", TokenKind.SWITCH),
            Map.entry("case", TokenKind.CASE),
            Map.entry("break", TokenKind.BREAK),
            Map.entry("continue", TokenKind.CONTINUE),
            Map.entry("true", TokenKind.TRUE),
            Map.entry("false", TokenKind.FALSE)
    );

    private final SourceFile source;
    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<DocumentationComment> documentationComments = new ArrayList<>();
    private final boolean retainDocumentation;
    private int offset;
    private int line = 1;
    private int column = 1;

    public Lexer(SourceFile source) {
        this(source, false);
    }

    public Lexer(SourceFile source, boolean retainDocumentation) {
        this.source = source;
        this.text = source.content();
        this.retainDocumentation = retainDocumentation;
    }

    public List<DocumentationComment> documentationComments() {
        return List.copyOf(documentationComments);
    }

    public LexResult lex() {
        while (!atEnd()) {
            scanToken();
        }
        SourcePosition end = position();
        tokens.add(new Token(TokenKind.EOF, "", SourceSpan.at(end)));
        return new LexResult(tokens, diagnostics);
    }

    private void scanToken() {
        char current = peek();
        if (Character.isWhitespace(current)) {
            advance();
            return;
        }
        if (current == '/' && peekNext() == '/') {
            skipLineComment();
            return;
        }
        if (current == '/' && peekNext() == '*') {
            skipBlockComment();
            return;
        }
        if (startsWith("r\"\"\"")) {
            scanTextBlock(true);
            return;
        }
        if (startsWith("\"\"\"")) {
            scanTextBlock(false);
            return;
        }
        if (isIdentifierStart(current)) {
            scanIdentifier();
            return;
        }
        if (Character.isDigit(current) || current == '.' && Character.isDigit(peekNext())) {
            scanNumber();
            return;
        }
        if (current == '\'') {
            scanCharacter();
            return;
        }
        if (current == '"') {
            scanString();
            return;
        }

        SourcePosition start = position();
        advance();
        TokenKind kind = switch (current) {
            case '@' -> TokenKind.AT;
            case '{' -> TokenKind.LEFT_BRACE;
            case '}' -> TokenKind.RIGHT_BRACE;
            case '(' -> TokenKind.LEFT_PAREN;
            case ')' -> TokenKind.RIGHT_PAREN;
            case '[' -> TokenKind.LEFT_BRACKET;
            case ']' -> TokenKind.RIGHT_BRACKET;
            case ',' -> TokenKind.COMMA;
            case '.' -> TokenKind.DOT;
            case ';' -> TokenKind.SEMICOLON;
            case '+' -> match('+') ? TokenKind.PLUS_PLUS
                    : match('=') ? TokenKind.PLUS_EQUAL : TokenKind.PLUS;
            case '-' -> match('>') ? TokenKind.ARROW
                    : match('-') ? TokenKind.MINUS_MINUS
                    : match('=') ? TokenKind.MINUS_EQUAL : TokenKind.MINUS;
            case '*' -> match('=') ? TokenKind.STAR_EQUAL : TokenKind.STAR;
            case '/' -> match('=') ? TokenKind.SLASH_EQUAL : TokenKind.SLASH;
            case '%' -> match('=') ? TokenKind.PERCENT_EQUAL : TokenKind.PERCENT;
            case '&' -> match('&') ? TokenKind.AND_AND
                    : match('=') ? TokenKind.AMPERSAND_EQUAL : TokenKind.AMPERSAND;
            case '|' -> match('|') ? TokenKind.OR_OR
                    : match('=') ? TokenKind.PIPE_EQUAL : TokenKind.PIPE;
            case '^' -> match('=') ? TokenKind.CARET_EQUAL : TokenKind.CARET;
            case '~' -> TokenKind.TILDE;
            case '!' -> match('=') ? TokenKind.BANG_EQUAL : TokenKind.BANG;
            case '=' -> match('=') ? TokenKind.EQUAL_EQUAL : TokenKind.EQUAL;
            case '<' -> match('=') ? TokenKind.LESS_EQUAL : TokenKind.LESS;
            case '>' -> match('=') ? TokenKind.GREATER_EQUAL : TokenKind.GREATER;
            case '?' -> TokenKind.QUESTION;
            case ':' -> TokenKind.COLON;
            default -> null;
        };
        if (kind == null) {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "unexpected character '" + printable(current) + "'"));
        } else {
            tokens.add(new Token(kind, text.substring(start.offset(), offset), new SourceSpan(start, position())));
        }
    }

    private boolean match(char expected) {
        if (atEnd() || peek() != expected) {
            return false;
        }
        advance();
        return true;
    }

    private void scanIdentifier() {
        SourcePosition start = position();
        int startOffset = offset;
        while (!atEnd() && isIdentifierPart(peek())) {
            advance();
        }
        String lexeme = text.substring(startOffset, offset);
        TokenKind kind = KEYWORDS.getOrDefault(lexeme, TokenKind.IDENTIFIER);
        tokens.add(new Token(kind, lexeme, new SourceSpan(start, position())));
    }

    private void scanNumber() {
        SourcePosition start = position();
        int startOffset = offset;
        boolean hexadecimal = peek() == '0' && (peekNext() == 'x' || peekNext() == 'X');
        boolean binary = peek() == '0' && (peekNext() == 'b' || peekNext() == 'B');
        boolean floating = false;
        if (hexadecimal || binary) {
            advance();
            advance();
            while (!atEnd() && ((hexadecimal ? isHexDigit(peek()) : isBinaryDigit(peek()))
                    || peek() == '_')) {
                advance();
            }
        } else {
            while (!atEnd() && (Character.isDigit(peek()) || peek() == '_')) {
                advance();
            }
            if (!atEnd() && peek() == '.') {
                floating = true;
                advance();
                while (!atEnd() && (Character.isDigit(peek()) || peek() == '_')) {
                    advance();
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                advance();
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
                while (!atEnd() && (Character.isDigit(peek()) || peek() == '_')) {
                    advance();
                }
            }
        }
        if (!hexadecimal && !binary && !atEnd() && (peek() == 'f' || peek() == 'F'
                || peek() == 'd' || peek() == 'D')) {
            floating = true;
            advance();
        } else if (!atEnd() && (peek() == 'l' || peek() == 'L')) {
            advance();
        }
        while (!atEnd() && isIdentifierPart(peek())) {
            advance();
        }
        String lexeme = text.substring(startOffset, offset);
        String numericBody = hexadecimal || binary
                ? stripIntegralSuffix(lexeme) : stripNumericSuffix(lexeme);
        boolean valid = hexadecimal
                ? validHexIntegral(numericBody)
                : binary ? validBinaryIntegral(numericBody)
                : validDecimalNumber(numericBody, floating);
        if (!valid) {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "malformed numeric literal '" + lexeme + "'"));
        }
        tokens.add(new Token(floating ? TokenKind.FLOATING : TokenKind.INTEGER, lexeme,
                new SourceSpan(start, position())));
    }

    private static String stripNumericSuffix(String lexeme) {
        if (lexeme.isEmpty()) {
            return lexeme;
        }
        char last = lexeme.charAt(lexeme.length() - 1);
        return "fFdDlL".indexOf(last) >= 0
                ? lexeme.substring(0, lexeme.length() - 1) : lexeme;
    }

    private static String stripIntegralSuffix(String lexeme) {
        if (lexeme.isEmpty()) {
            return lexeme;
        }
        char last = lexeme.charAt(lexeme.length() - 1);
        return last == 'l' || last == 'L'
                ? lexeme.substring(0, lexeme.length() - 1) : lexeme;
    }

    private static boolean validHexIntegral(String body) {
        if (body.length() <= 2 || body.charAt(0) != '0'
                || body.charAt(1) != 'x' && body.charAt(1) != 'X') {
            return false;
        }
        return validDigitSequence(body.substring(2), true);
    }

    private static boolean validBinaryIntegral(String body) {
        if (body.length() <= 2 || body.charAt(0) != '0'
                || body.charAt(1) != 'b' && body.charAt(1) != 'B') {
            return false;
        }
        return validBinaryDigitSequence(body.substring(2));
    }

    private static boolean validBinaryDigitSequence(String value) {
        if (value.isEmpty() || value.charAt(0) == '_' || value.charAt(value.length() - 1) == '_') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '_' && !isBinaryDigit(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validDecimalNumber(String body, boolean floating) {
        int exponent = Math.max(body.indexOf('e'), body.indexOf('E'));
        String significand = exponent < 0 ? body : body.substring(0, exponent);
        String exponentPart = exponent < 0 ? null : body.substring(exponent + 1);
        if (exponentPart != null && !exponentPart.isEmpty()
                && (exponentPart.charAt(0) == '+' || exponentPart.charAt(0) == '-')) {
            exponentPart = exponentPart.substring(1);
        }
        if (exponentPart != null && !validDigitSequence(exponentPart, false)) {
            return false;
        }
        int dot = significand.indexOf('.');
        if (dot >= 0) {
            if (significand.indexOf('.', dot + 1) >= 0) {
                return false;
            }
            String before = significand.substring(0, dot);
            String after = significand.substring(dot + 1);
            if (before.isEmpty() && after.isEmpty()) {
                return false;
            }
            return (before.isEmpty() || validDigitSequence(before, false))
                    && (after.isEmpty() || validDigitSequence(after, false));
        }
        return validDigitSequence(significand, false)
                && (!floating || exponent >= 0 || !body.isEmpty());
    }

    private static boolean validDigitSequence(String value, boolean hexadecimal) {
        if (value.isEmpty() || value.charAt(0) == '_' || value.charAt(value.length() - 1) == '_') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '_') {
                continue;
            }
            if (hexadecimal ? !isHexDigit(current) : !Character.isDigit(current)) {
                return false;
            }
        }
        return true;
    }

    private void scanCharacter() {
        SourcePosition start = position();
        advance();
        int value = 0;
        boolean hasValue = false;
        if (!atEnd() && peek() != '\'' && peek() != '\n' && peek() != '\r') {
            if (peek() == '\\') {
                SourcePosition escapeStart = position();
                advance();
                if (!atEnd() && peek() == 'u') {
                    advance();
                    int decoded = 0;
                    boolean valid = true;
                    for (int index = 0; index < 4; index++) {
                        if (atEnd() || !isHexDigit(peek())) {
                            valid = false;
                            break;
                        }
                        decoded = decoded * 16 + Character.digit(advance(), 16);
                    }
                    if (!valid) {
                        diagnostics.add(Diagnostic.error(source,
                                new SourceSpan(escapeStart, position()),
                                "malformed Unicode escape in character literal; expected four hexadecimal digits"));
                    } else {
                        value = decoded;
                        hasValue = true;
                    }
                } else if (!atEnd()) {
                    char escaped = advance();
                    Character decoded = decodeSimpleEscape(escaped, true);
                    if (decoded == null) {
                        diagnostics.add(Diagnostic.error(source,
                                new SourceSpan(escapeStart, position()),
                                "unsupported character escape '\\" + printable(escaped) + "'"));
                    } else {
                        value = decoded;
                        hasValue = true;
                    }
                }
            } else {
                value = advance();
                hasValue = true;
            }
        }
        if (!hasValue) {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "character literal must contain exactly one UTF-16 code unit"));
        }
        if (!atEnd() && peek() == '\'') {
            advance();
        } else {
            while (!atEnd() && peek() != '\'' && peek() != '\n' && peek() != '\r') {
                advance();
            }
            if (!atEnd() && peek() == '\'') {
                advance();
            }
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "unterminated or oversized character literal"));
        }
        tokens.add(new Token(TokenKind.CHARACTER, String.valueOf((char) value),
                new SourceSpan(start, position())));
    }

    private static Character decodeSimpleEscape(char escaped, boolean character) {
        return switch (escaped) {
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '\'' -> character ? '\'' : null;
            case '"' -> '"';
            case '\\' -> '\\';
            default -> null;
        };
    }

    private void scanString() {
        SourcePosition start = position();
        advance();
        StringBuilder value = new StringBuilder();
        boolean terminated = false;
        while (!atEnd()) {
            char current = peek();
            if (current == '"') {
                advance();
                terminated = true;
                break;
            }
            if (current == '\n' || current == '\r') {
                break;
            }
            if (current != '\\') {
                value.append(advance());
                continue;
            }
            SourcePosition escapeStart = position();
            advance();
            if (atEnd()) {
                break;
            }
            char escaped = advance();
            Character decoded = decodeSimpleEscape(escaped, false);
            if (decoded == null) {
                diagnostics.add(Diagnostic.error(source,
                        new SourceSpan(escapeStart, position()),
                        "unsupported string escape '\\" + printable(escaped) + "'"));
                value.append(escaped);
            } else {
                value.append(decoded);
            }
        }
        if (!terminated) {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "unterminated string literal"));
        }
        tokens.add(new Token(TokenKind.STRING, value.toString(), new SourceSpan(start, position())));
    }

    private void scanTextBlock(boolean raw) {
        SourcePosition start = position();
        if (raw) {
            advance();
        }
        advance();
        advance();
        advance();

        while (!atEnd() && isTextBlockOpeningWhitespace(peek())) {
            advance();
        }
        if (atEnd() || peek() != '\n' && peek() != '\r') {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "text block opening delimiter must be followed by a line terminator"));
        } else {
            consumeLineTerminator();
        }

        int contentStart = offset;
        boolean terminated = false;
        while (!atEnd()) {
            if (startsWith("\"\"\"")) {
                terminated = true;
                break;
            }
            if (!raw && peek() == '\\') {
                advance();
                if (!atEnd()) {
                    advance();
                }
                continue;
            }
            advance();
        }

        int contentEnd = offset;
        if (terminated) {
            advance();
            advance();
            advance();
        } else {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    raw ? "unterminated raw text block" : "unterminated text block"));
        }

        String content = normalizeLineTerminators(text.substring(contentStart, contentEnd))
                .stripIndent();
        String value = raw ? content : decodeTextBlockEscapes(content, start);
        tokens.add(new Token(TokenKind.STRING, value, new SourceSpan(start, position())));
    }

    private String decodeTextBlockEscapes(String content, SourcePosition start) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (index + 1 >= content.length()) {
                diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                        "unterminated escape in text block"));
                continue;
            }
            char escaped = content.charAt(++index);
            Character decoded = decodeSimpleEscape(escaped, false);
            if (decoded == null) {
                diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                        "unsupported text block escape '\\" + printable(escaped) + "'"));
                value.append(escaped);
            } else {
                value.append(decoded);
            }
        }
        return value.toString();
    }

    private void consumeLineTerminator() {
        char first = advance();
        if (first == '\r' && !atEnd() && peek() == '\n') {
            advance();
        }
    }

    private static boolean isTextBlockOpeningWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f';
    }

    private static String normalizeLineTerminators(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void skipLineComment() {
        while (!atEnd() && peek() != '\n' && peek() != '\r') {
            advance();
        }
    }

    private void skipBlockComment() {
        SourcePosition start = position();
        boolean documentation = retainDocumentation && startsWith("/**");
        advance();
        advance();
        while (!atEnd() && !(peek() == '*' && peekNext() == '/')) {
            advance();
        }
        if (atEnd()) {
            diagnostics.add(Diagnostic.error(source, new SourceSpan(start, position()),
                    "unterminated block comment"));
            return;
        }
        advance();
        advance();
        if (documentation) {
            documentationComments.add(new DocumentationComment(
                    text.substring(start.offset(), offset), new SourceSpan(start, position())));
        }
    }

    private char advance() {
        char value = text.charAt(offset++);
        if (value == '\r') {
            line++;
            column = 1;
        } else if (value == '\n') {
            if (offset < 2 || text.charAt(offset - 2) != '\r') {
                line++;
            }
            column = 1;
        } else {
            column++;
        }
        return value;
    }

    private char peek() {
        return atEnd() ? '\0' : text.charAt(offset);
    }

    private char peekNext() {
        return offset + 1 >= text.length() ? '\0' : text.charAt(offset + 1);
    }

    private boolean startsWith(String value) {
        return text.startsWith(value, offset);
    }

    private static boolean isHexDigit(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static boolean isBinaryDigit(char value) {
        return value == '0' || value == '1';
    }

    private boolean atEnd() {
        return offset >= text.length();
    }

    private SourcePosition position() {
        return new SourcePosition(offset, line, column);
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value == '$' || isAsciiLetter(value);
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || Character.isDigit(value);
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static String printable(char value) {
        return switch (value) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            default -> Character.toString(value);
        };
    }
}
