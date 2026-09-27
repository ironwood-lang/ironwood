// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrProgram;

import java.util.List;
import java.util.Optional;

public record SemanticResult(Optional<IrProgram> program, List<Diagnostic> diagnostics,
                             Optional<BridgeConstructionFacts> bridgeConstructionFacts) {
    public SemanticResult(Optional<IrProgram> program, List<Diagnostic> diagnostics) {
        this(program, diagnostics, Optional.empty());
    }

    public SemanticResult {
        program = program == null ? Optional.empty() : program;
        diagnostics = List.copyOf(diagnostics);
        bridgeConstructionFacts = bridgeConstructionFacts == null ? Optional.empty() : bridgeConstructionFacts;
    }
}
