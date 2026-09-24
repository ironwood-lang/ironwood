// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.semantic.SemanticAnalyzer;

import java.nio.file.Path;
import java.util.Set;

@FunctionalInterface
interface SemanticAnalyzerFactory {
    SemanticAnalyzer create(UnfreedMode mode, Set<Path> originalSources,
                            boolean explainRejectedFree);
}
