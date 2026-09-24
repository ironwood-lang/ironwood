// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;

/** Nullable test observation seam; callbacks never influence semantic analysis. */
interface SemanticAnalysisObserver {
    void refinementEntered(int pass);

    void refinementOutcome(int pass, boolean stable, boolean fieldsStable);

    void refinementFinished(boolean completed);

    void lowering(String linkageName, SourceFile source, boolean finalPhase,
                  boolean refinementCompleted, boolean collectorPresent);
}
