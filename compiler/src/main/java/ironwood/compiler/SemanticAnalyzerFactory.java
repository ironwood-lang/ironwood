// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.semantic.SemanticAnalyzer;

@FunctionalInterface
interface SemanticAnalyzerFactory {
    SemanticAnalyzer create(UnfreedMode mode, boolean explainRejectedFree);
}
