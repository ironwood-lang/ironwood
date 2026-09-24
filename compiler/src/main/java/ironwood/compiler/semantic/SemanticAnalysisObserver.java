// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;

/** Nullable test observation seam; callbacks never influence semantic analysis. */
interface SemanticAnalysisObserver {
    enum AnalyzerKind { ESCAPE, SYMBOLIC_RETURN, OWNED_FIELD, EFFECT }

    enum AnalyzerPhase { INITIAL, REBOUND, REFINEMENT, FINAL_VALIDATION }

    void analyzerCreated(long token, AnalyzerKind kind, AnalyzerPhase phase);

    void analyzerRound(long token, AnalyzerKind kind, int round);

    void analyzerSelected(long token, AnalyzerKind kind);

    void fieldProofCompared(int pass, boolean sameProofs);

    void refinementEntered(int pass);

    void refinementOutcome(int pass, boolean stable, boolean fieldsStable);

    void refinementFinished(boolean completed);

    void lowering(String linkageName, SourceFile source, boolean finalPhase,
                  boolean refinementCompleted, boolean collectorPresent);
}
