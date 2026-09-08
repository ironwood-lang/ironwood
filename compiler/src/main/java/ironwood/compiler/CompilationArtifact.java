// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrProgram;

import java.util.List;
import java.util.Optional;

public record CompilationArtifact(
        Optional<IrProgram> program,
        Optional<String> llvmIr,
        List<Diagnostic> diagnostics
) {
    public CompilationArtifact {
        program = program == null ? Optional.empty() : program;
        llvmIr = llvmIr == null ? Optional.empty() : llvmIr;
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean successful() {
        return diagnostics.isEmpty() && program.isPresent() && llvmIr.isPresent();
    }

    public boolean valid() {
        return diagnostics.isEmpty() && program.isPresent();
    }
}
