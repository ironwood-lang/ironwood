// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.UnfreedMode;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only bridge to the package-private observer constructor. */
public final class SemanticObserverBridge {
    private SemanticObserverBridge() {
    }

    public static SemanticAnalyzer create(UnfreedMode mode, Set<Path> sources,
                                          boolean explain, Counts counts, Path watchedSource) {
        return create(mode, sources, explain, counts, watchedSource, null);
    }

    public static SemanticAnalyzer createWithLimits(UnfreedMode mode, Set<Path> sources,
                                                    boolean explain, Counts counts,
                                                    Path watchedSource, int local,
                                                    int snapshots, int invocation) {
        return create(mode, sources, explain, counts, watchedSource,
                new RejectedFreeEvidence.Limits(local, snapshots, invocation));
    }

    private static SemanticAnalyzer create(UnfreedMode mode, Set<Path> sources,
                                           boolean explain, Counts counts, Path watchedSource,
                                           RejectedFreeEvidence.Limits limits) {
        SemanticAnalysisObserver observer = new SemanticAnalysisObserver() {
            @Override
            public void analyzerCreated(long token, AnalyzerKind kind, AnalyzerPhase phase) {
                counts.analyzerKinds.put(token, kind.name());
                counts.analyzerPhases.put(token, phase.name());
            }

            @Override
            public void analyzerRound(long token, AnalyzerKind kind, int round) {
                counts.analyzerRounds.merge(token, 1, Integer::sum);
            }

            @Override
            public void analyzerSelected(long token, AnalyzerKind kind) {
                counts.selected.add(token);
            }

            @Override
            public void selectedProjection(long token, AnalyzerKind kind,
                                           Map<String, String> facts) {
                counts.projections.put(kind.name(), Map.copyOf(facts));
            }

            @Override
            public void fieldProofCompared(int pass, boolean sameProofs) {
                counts.fieldComparisons++;
            }

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

            @Override
            public void evidenceSnapshot(boolean saved, boolean sharedEmpty, int associations) {
                if (saved && sharedEmpty) counts.emptySaves++;
                else if (saved) counts.evidenceSaves++;
                else if (sharedEmpty) counts.emptyRestores++;
                else counts.evidenceRestores++;
            }

            @Override
            public void evidenceOrigin(SourceFile source) {
                counts.origins++;
            }

            @Override
            public void collectorFinished(String linkageName, int liveHighWater,
                                          int snapshotHighWater, boolean localTruncated,
                                          boolean invocationStopped) {
                counts.collectorHighWater = Math.max(counts.collectorHighWater, liveHighWater);
                counts.snapshotHighWater = Math.max(counts.snapshotHighWater, snapshotHighWater);
                counts.localTruncated |= localTruncated;
                counts.invocationStopped |= invocationStopped;
                counts.collectorsFinished++;
            }
        };
        return new SemanticAnalyzer(mode, sources, explain, observer, limits);
    }

    public static final class Counts {
        private int entered;
        private int outcomes;
        private int stable;
        private int finished;
        private boolean completed;
        private int fieldComparisons;
        private final Map<Long, String> analyzerKinds = new LinkedHashMap<>();
        private final Map<Long, String> analyzerPhases = new LinkedHashMap<>();
        private final Map<Long, Integer> analyzerRounds = new LinkedHashMap<>();
        private final List<Long> selected = new ArrayList<>();
        private final Map<String, Map<String, String>> projections = new LinkedHashMap<>();
        private final List<Lowering> lowerings = new ArrayList<>();
        private int emptySaves;
        private int emptyRestores;
        private int evidenceSaves;
        private int evidenceRestores;
        private int origins;
        private int collectorsFinished;
        private int collectorHighWater;
        private int snapshotHighWater;
        private boolean localTruncated;
        private boolean invocationStopped;

        public int entered() { return entered; }
        public int outcomes() { return outcomes; }
        public int stable() { return stable; }
        public int finished() { return finished; }
        public boolean completed() { return completed; }
        public List<Lowering> lowerings() { return List.copyOf(lowerings); }
        public int fieldComparisons() { return fieldComparisons; }
        public long created(String kind) {
            return analyzerKinds.values().stream().filter(kind::equals).count();
        }
        public int rounds(String kind) {
            return analyzerRounds.entrySet().stream()
                    .filter(entry -> kind.equals(analyzerKinds.get(entry.getKey())))
                    .mapToInt(Map.Entry::getValue).sum();
        }
        public long phases(String phase) {
            return analyzerPhases.values().stream().filter(phase::equals).count();
        }
        public boolean selectedInstancesWereCreated() {
            return !selected.isEmpty() && selected.stream().allMatch(analyzerKinds::containsKey);
        }
        public Map<String, Map<String, String>> projections() {
            return Map.copyOf(projections);
        }
        public int emptySaves() { return emptySaves; }
        public int emptyRestores() { return emptyRestores; }
        public int evidenceSaves() { return evidenceSaves; }
        public int evidenceRestores() { return evidenceRestores; }
        public int origins() { return origins; }
        public int collectorsFinished() { return collectorsFinished; }
        public int collectorHighWater() { return collectorHighWater; }
        public int snapshotHighWater() { return snapshotHighWater; }
        public boolean localTruncated() { return localTruncated; }
        public boolean invocationStopped() { return invocationStopped; }
    }

    public record Lowering(String linkageName, boolean finalPhase,
                           boolean refinementCompleted, boolean collectorPresent) {
    }
}
