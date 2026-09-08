// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.lexer;

import ironwood.compiler.diagnostic.Diagnostic;

import java.util.List;

public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics) {
    public LexResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }
}
