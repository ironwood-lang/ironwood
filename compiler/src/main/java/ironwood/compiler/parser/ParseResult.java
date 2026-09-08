// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.parser;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.diagnostic.Diagnostic;

import java.util.List;
import java.util.Optional;

public record ParseResult(Optional<CompilationUnit> unit, List<Diagnostic> diagnostics) {
    public ParseResult {
        unit = unit == null ? Optional.empty() : unit;
        diagnostics = List.copyOf(diagnostics);
    }
}
