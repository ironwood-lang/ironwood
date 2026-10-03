// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrProgram;

import java.util.List;
import java.util.Optional;

public record CompilationArtifact(
        Optional<IrProgram> program,
        Optional<String> llvmIr,
        List<Diagnostic> diagnostics,
        Optional<ironwood.compiler.semantic.BridgeConstructionFacts> bridgeConstructionFacts,
        Optional<ironwood.compiler.semantic.BridgeApiFacts> bridgeApiFacts
) {
    public CompilationArtifact(Optional<IrProgram> program, Optional<String> llvmIr,
                               List<Diagnostic> diagnostics,
                               Optional<ironwood.compiler.semantic.BridgeConstructionFacts> bridgeConstructionFacts) {
        this(program, llvmIr, diagnostics, bridgeConstructionFacts, Optional.empty());
    }
    public CompilationArtifact(Optional<IrProgram> program, Optional<String> llvmIr,
                               List<Diagnostic> diagnostics) {
        this(program, llvmIr, diagnostics, Optional.empty());
    }

    public CompilationArtifact {
        program = program == null ? Optional.empty() : program;
        llvmIr = llvmIr == null ? Optional.empty() : llvmIr;
        diagnostics = List.copyOf(diagnostics);
        bridgeConstructionFacts = bridgeConstructionFacts == null ? Optional.empty() : bridgeConstructionFacts;
        bridgeApiFacts = bridgeApiFacts == null ? Optional.empty() : bridgeApiFacts;
    }

    public boolean successful() {
        return !Diagnostic.hasErrors(diagnostics) && program.isPresent() && llvmIr.isPresent();
    }

    public boolean valid() {
        return !Diagnostic.hasErrors(diagnostics) && program.isPresent();
    }
}
