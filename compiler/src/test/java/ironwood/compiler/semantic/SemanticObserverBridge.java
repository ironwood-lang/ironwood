// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.UnfreedMode;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test-only bridge to the package-private observer constructor. */
public final class SemanticObserverBridge {
    private SemanticObserverBridge() {
    }

    public static SemanticAnalyzer create(UnfreedMode mode, Set<Path> sources,
                                          boolean explain, Counts counts, Path watchedSource) {
        SemanticAnalysisObserver observer = new SemanticAnalysisObserver() {
            @Override
            public void refinementEntered(int pass) {
                counts.entered++;
            }

            @Override
            public void refinementOutcome(int pass, boolean stable, boolean fieldsStable) {
                counts.outcomes++;
                if (stable) {
                    counts.stable++;
                }
            }

            @Override
            public void refinementFinished(boolean completed) {
                counts.completed = completed;
                counts.finished++;
            }

            @Override
            public void lowering(String linkageName, SourceFile source, boolean finalPhase,
                                 boolean refinementCompleted, boolean collectorPresent) {
                if (source.path().equals(watchedSource)) {
                    counts.lowerings.add(new Lowering(linkageName, finalPhase,
                            refinementCompleted, collectorPresent));
                }
            }
        };
        return new SemanticAnalyzer(mode, sources, explain, observer);
    }

    public static final class Counts {
        private int entered;
        private int outcomes;
        private int stable;
        private int finished;
        private boolean completed;
        private final List<Lowering> lowerings = new ArrayList<>();

        public int entered() { return entered; }
        public int outcomes() { return outcomes; }
        public int stable() { return stable; }
        public int finished() { return finished; }
        public boolean completed() { return completed; }
        public List<Lowering> lowerings() { return List.copyOf(lowerings); }
    }

    public record Lowering(String linkageName, boolean finalPhase,
                           boolean refinementCompleted, boolean collectorPresent) {
    }
}
