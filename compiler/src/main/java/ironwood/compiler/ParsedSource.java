// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.diagnostic.Diagnostic;

import java.util.List;
import java.util.Optional;

record ParsedSource(Optional<CompilationUnit> unit, List<Diagnostic> diagnostics) {
    ParsedSource {
        unit = unit == null ? Optional.empty() : unit;
        diagnostics = List.copyOf(diagnostics);
    }
}
