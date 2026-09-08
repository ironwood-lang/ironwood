// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

final class SourceParser {
    private SourceParser() {
    }

    static ParsedSource parse(SourceFile source) {
        LexResult lexResult = new Lexer(source).lex();
        if (!lexResult.diagnostics().isEmpty()) {
            return new ParsedSource(java.util.Optional.empty(), lexResult.diagnostics());
        }
        ParseResult parseResult = new Parser(source, lexResult.tokens()).parse();
        return new ParsedSource(parseResult.unit(), parseResult.diagnostics());
    }
}
