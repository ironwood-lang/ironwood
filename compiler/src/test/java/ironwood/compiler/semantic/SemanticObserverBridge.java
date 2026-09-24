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

    public static SemanticAnalyzer createWithSummaryLimits(UnfreedMode mode,
            Set<Path> sources, boolean explain, Counts counts, Path watchedSource,
            int method, int fact, int invocation) {
        return create(mode, sources, explain, counts, watchedSource,
                new RejectedFreeEvidence.Limits(1_024, 1_024, invocation, method, fact));
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
            public void summaryEvidenceLifecycle(long token, boolean present, boolean retired) {
                counts.summaryEvidencePresence.put(token, present);
                if (present && !retired) counts.liveSummaryRoots++;
                if (retired) {
                    counts.retiredSummaryEvidence.add(token);
                    counts.liveSummaryRoots--;
                }
                counts.recordRootPeak();
            }

            @Override
            public void fieldEvidenceLifecycle(long token, boolean present, boolean retired) {
                counts.fieldEvidencePresence.put(token, present);
                if (present && !retired) counts.liveFieldRoots++;
                if (retired) {
                    counts.retiredFieldEvidence.add(token);
                    counts.liveFieldRoots--;
                }
                counts.recordRootPeak();
            }

            @Override
            public void fieldEvidenceFinished(long token, int failures, int units) {
                counts.totalFieldFailures += failures;
                counts.totalFieldUnits += units;
            }

            @Override
            public void dispatchEvidenceLifecycle(boolean present, boolean retired) {
                if (present && !retired) {
                    counts.dispatchEvidencePresent++;
                    counts.liveDispatchRoots++;
                }
                if (present && retired) {
                    counts.dispatchEvidenceRetired++;
                    counts.liveDispatchRoots--;
                }
                counts.recordRootPeak();
            }

            @Override
            public void evidenceBudgetFinished(int live, int highWater, boolean stopped) {
                counts.budgetFinished++;
                counts.finalBudgetLive = live;
                counts.budgetHighWater = highWater;
                counts.budgetStopped = stopped;
            }

            @Override
            public void summaryEvidenceFinished(long token, boolean methodTruncated,
                                                boolean invocationStopped, int methods,
                                                int facts, int units) {
                counts.summaryMethodTruncated |= methodTruncated;
                counts.summaryInvocationStopped |= invocationStopped;
                counts.totalSummaryMethods += methods;
                counts.totalSummaryFacts += facts;
                counts.totalSummaryUnits += units;
                counts.peakSummaryMethods = Math.max(counts.peakSummaryMethods, methods);
                counts.peakSummaryFacts = Math.max(counts.peakSummaryFacts, facts);
                counts.peakSummaryUnits = Math.max(counts.peakSummaryUnits, units);
            }

            @Override
            public void summaryWitnessProjection(long token, Map<String, String> facts) {
                counts.summaryWitnesses.put(token, Map.copyOf(facts));
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
                if (collectorPresent) {
                    counts.liveCollectors++;
                    counts.recordRootPeak();
                }
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
                                          int snapshotHighWater, int invocationHighWater,
                                          boolean localTruncated,
                                          boolean invocationStopped) {
                counts.collectorHighWater = Math.max(counts.collectorHighWater, liveHighWater);
                counts.snapshotHighWater = Math.max(counts.snapshotHighWater, snapshotHighWater);
                counts.invocationHighWater = Math.max(counts.invocationHighWater,
                        invocationHighWater);
                counts.localTruncated |= localTruncated;
                counts.invocationStopped |= invocationStopped;
                counts.collectorsFinished++;
                counts.liveCollectors--;
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
        private final Map<Long, Boolean> summaryEvidencePresence = new LinkedHashMap<>();
        private final List<Long> retiredSummaryEvidence = new ArrayList<>();
        private final Map<Long, Boolean> fieldEvidencePresence = new LinkedHashMap<>();
        private final List<Long> retiredFieldEvidence = new ArrayList<>();
        private final Map<Long, Map<String, String>> summaryWitnesses = new LinkedHashMap<>();
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
        private int invocationHighWater;
        private boolean localTruncated;
        private boolean invocationStopped;
        private boolean summaryMethodTruncated;
        private boolean summaryInvocationStopped;
        private int budgetFinished;
        private int finalBudgetLive;
        private int budgetHighWater;
        private boolean budgetStopped;
        private int liveSummaryRoots;
        private int liveFieldRoots;
        private int liveDispatchRoots;
        private int liveCollectors;
        private int dispatchEvidencePresent;
        private int dispatchEvidenceRetired;
        private int peakLiveSummaryRoots;
        private int peakLiveFieldRoots;
        private int peakLiveRoots;
        private long totalSummaryMethods;
        private long totalSummaryFacts;
        private long totalSummaryUnits;
        private int peakSummaryMethods;
        private int peakSummaryFacts;
        private int peakSummaryUnits;
        private long totalFieldFailures;
        private long totalFieldUnits;

        private void recordRootPeak() {
            peakLiveSummaryRoots = Math.max(peakLiveSummaryRoots, liveSummaryRoots);
            peakLiveFieldRoots = Math.max(peakLiveFieldRoots, liveFieldRoots);
            peakLiveRoots = Math.max(peakLiveRoots,
                    liveSummaryRoots + liveFieldRoots + liveDispatchRoots + liveCollectors);
        }

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
        public long summaryEvidencePresent() {
            return summaryEvidencePresence.values().stream().filter(Boolean::booleanValue).count();
        }
        public boolean summaryEvidenceRetired() {
            return summaryEvidencePresence.entrySet().stream()
                    .filter(entry -> entry.getValue())
                    .allMatch(entry -> retiredSummaryEvidence.contains(entry.getKey()));
        }
        public long fieldEvidencePresent() {
            return fieldEvidencePresence.values().stream().filter(Boolean::booleanValue).count();
        }
        public boolean fieldEvidenceRetired() {
            return fieldEvidencePresence.entrySet().stream()
                    .filter(entry -> entry.getValue())
                    .allMatch(entry -> retiredFieldEvidence.contains(entry.getKey()));
        }
        public int budgetFinished() { return budgetFinished; }
        public int finalBudgetLive() { return finalBudgetLive; }
        public int budgetHighWater() { return budgetHighWater; }
        public boolean budgetStopped() { return budgetStopped; }
        public int peakLiveSummaryRoots() { return peakLiveSummaryRoots; }
        public int peakLiveFieldRoots() { return peakLiveFieldRoots; }
        public int peakLiveRoots() { return peakLiveRoots; }
        public int dispatchEvidencePresent() { return dispatchEvidencePresent; }
        public int dispatchEvidenceRetired() { return dispatchEvidenceRetired; }
        public long totalSummaryMethods() { return totalSummaryMethods; }
        public long totalSummaryFacts() { return totalSummaryFacts; }
        public long totalSummaryUnits() { return totalSummaryUnits; }
        public int peakSummaryMethods() { return peakSummaryMethods; }
        public int peakSummaryFacts() { return peakSummaryFacts; }
        public int peakSummaryUnits() { return peakSummaryUnits; }
        public long totalFieldFailures() { return totalFieldFailures; }
        public long totalFieldUnits() { return totalFieldUnits; }
        public Map<String, String> selectedSummaryWitnesses() {
            return summaryWitnesses.isEmpty() ? Map.of()
                    : summaryWitnesses.values().iterator().next();
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
        public int invocationHighWater() { return invocationHighWater; }
        public boolean localTruncated() { return localTruncated; }
        public boolean invocationStopped() { return invocationStopped; }
        public boolean summaryMethodTruncated() { return summaryMethodTruncated; }
        public boolean summaryInvocationStopped() { return summaryInvocationStopped; }
    }

    public record Lowering(String linkageName, boolean finalPhase,
                           boolean refinementCompleted, boolean collectorPresent) {
    }
}
