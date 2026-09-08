// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

record SourceLoadResult(List<SourceFile> sources, List<Diagnostic> diagnostics) {
    SourceLoadResult {
        sources = List.copyOf(sources);
        diagnostics = List.copyOf(diagnostics);
    }
}
