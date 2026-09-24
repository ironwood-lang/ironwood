// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

final class ExplanationObserverTests {
    private ExplanationObserverTests() {
    }

    static void runAll() {
        verifyCompleted();
        verifySkipped();
        verifyCallableKinds();
    }

    static void forcedEvidenceLimits() {
        SourceFile source = SourceFile.of("Limited.iron", """
                class Limited {
                    static void check() {
                        Object value = new Object();
                        free value;
                        free value;
                    }
                }
                """);
        CompilationArtifact baseline = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        SemanticObserverBridge.Counts localCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact local = limited(source, true, localCounts, 1, 100,
                1_048_576);
        SemanticObserverBridge.Counts invocationCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact invocation = limited(source, true, invocationCounts, 100, 100, 1);
        SemanticObserverBridge.Counts disabledCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact disabled = limited(source, false, disabledCounts, 1, 1, 1);
        for (CompilationArtifact artifact : List.of(local, invocation, disabled)) {
            require(!artifact.valid() && artifact.program().isEmpty()
                            && artifact.llvmIr().isEmpty()
                            && samePrimaries(baseline.diagnostics(), artifact.diagnostics()),
                    "forced evidence cap changed mandatory safety: " + artifact.diagnostics());
        }
        require(localCounts.localTruncated() && !localCounts.invocationStopped()
                        && localCounts.collectorsFinished() > 0
                        && localCounts.collectorHighWater() <= 1,
                "local cap did not truncate the real collector");
        require(invocationCounts.invocationStopped() && invocationCounts.collectorsFinished() > 0
                        && invocationCounts.collectorHighWater() <= 1
                        && invocationCounts.invocationHighWater() <= 1,
                "invocation emergency stop did not latch in the real pipeline");
        require(local.diagnostics().getFirst().notes().size() == 1
                        && local.diagnostics().getFirst().notes().getFirst().source() == null
                        && invocation.diagnostics().getFirst().notes().size() == 1
                        && invocation.diagnostics().getFirst().notes().getFirst().source() == null,
                "forced cap preserved a stale earlier-free site");
        require(disabledCounts.collectorsFinished() == 0 && disabledCounts.origins() == 0
                        && disabled.diagnostics().getFirst().notes().isEmpty(),
                "disabled analysis constructed evidence under forced limits");

        SourceFile joined = SourceFile.of("JoinedLimited.iron", """
                class JoinedLimited {
                    static void check(boolean choice) {
                        Object value = new Object();
                        Object alias = null;
                        if (choice) { alias = value; }
                        else { alias = value; }
                        free value;
                    }
                }
                """);
        SemanticObserverBridge.Counts snapshotCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact snapshot = limited(joined, true, snapshotCounts, 100, 1,
                1_048_576);
        CompilationArtifact joinedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(joined));
        require(!snapshot.valid() && samePrimaries(joinedOff.diagnostics(), snapshot.diagnostics())
                        && snapshotCounts.localTruncated()
                        && snapshotCounts.snapshotHighWater() <= 1
                        && snapshot.diagnostics().stream()
                                .filter(d -> d.message().startsWith("cannot free 'value'"))
                                .allMatch(d -> d.notes().size() == 1
                                        && d.notes().getFirst().source() == null),
                "snapshot subcap kept an arbitrary binding site or changed safety");

        SourceFile accepted = SourceFile.of("AcceptedLimited.iron", """
                class AcceptedLimited {
                    public static int main(String[] args) {
                        Object value = new Object();
                        free value;
                        return 0;
                    }
                }
                """);
        SemanticObserverBridge.Counts acceptedCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact acceptedOn = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.createWithLimits(
                        mode, sources, explain, acceptedCounts, accepted.path(), 100, 100, 1))
                .compile(List.of(accepted));
        CompilationArtifact acceptedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .compile(List.of(accepted));
        require(acceptedOn.successful() && acceptedOff.successful()
                        && acceptedOn.llvmIr().equals(acceptedOff.llvmIr())
                        && acceptedCounts.invocationStopped(),
                "forced invocation stop changed accepted LLVM or safety");

        SourceFile chain = SourceFile.of("StorageChain.iron", """
                class StorageChain {
                    static Object saved;
                    static void keep(Object value) { saved = value; }
                    static void check() {
                        Object value = new Object();
                        keep(value);
                        free value;
                    }
                }
                """);
        SourceFile field = SourceFile.of("StorageField.iron", """
                class StorageField {
                    private byte[] buffer = new byte[16];
                    private static byte[] retained;
                    void publish() { retained = buffer; }
                    destructor { free buffer; }
                }
                """);
        SourceFile element = SourceFile.of("StorageElement.iron", """
                class StorageItem {}
                class StorageElement {
                    private StorageItem[] items = new StorageItem[2];
                    StorageElement() {
                        StorageItem value = new StorageItem();
                        items[0] = value;
                        observe(value);
                    }
                    static void observe(StorageItem value) {}
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """);
        List<SourceFile> combined = List.of(chain, field, element);
        CompilationArtifact combinedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(combined);
        SemanticObserverBridge.Counts combinedCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact combinedOn = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, combinedCounts, chain.path())).analyze(combined);
        SemanticObserverBridge.Counts stoppedCombinedCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact stoppedCombined = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.createWithLimits(
                        mode, sources, explain, stoppedCombinedCounts, chain.path(),
                        65_536, 65_536, 1)).analyze(combined);
        require(!combinedOff.valid()
                        && samePrimaries(combinedOff.diagnostics(), combinedOn.diagnostics())
                        && samePrimaries(combinedOff.diagnostics(), stoppedCombined.diagnostics())
                        && combinedOff.diagnostics().stream().anyMatch(d -> d.message()
                                .contains("field ownership is uncertain"))
                        && combinedOff.diagnostics().stream().anyMatch(d -> d.message()
                                .contains("cannot prove owned elements"))
                        && combinedOff.diagnostics().stream().anyMatch(d -> d.message()
                                .contains("allocation escapes through argument")),
                "combined producers changed mandatory diagnostics: " + combinedOff.diagnostics());
        require(combinedCounts.fieldEvidencePresent() > 0
                        && combinedCounts.summaryEvidencePresent() > 0
                        && combinedCounts.peakLiveSummaryRoots() > 0
                        && combinedCounts.peakLiveFieldRoots() > 0
                        && combinedCounts.dispatchEvidencePresent() == 1
                        && combinedCounts.dispatchEvidenceRetired() == 1
                        && combinedCounts.peakLiveRoots() >= 4
                        && combinedCounts.totalSummaryMethods() > 0
                        && combinedCounts.totalSummaryFacts() > 0
                        && combinedCounts.collectorsFinished() > 0
                        && combinedCounts.finalBudgetLive() == 0
                        && stoppedCombinedCounts.finalBudgetLive() == 0
                        && stoppedCombinedCounts.budgetStopped()
                        && stoppedCombinedCounts.projections().equals(combinedCounts.projections())
                        && stoppedCombinedCounts.entered() == combinedCounts.entered()
                        && stoppedCombinedCounts.outcomes() == combinedCounts.outcomes()
                        && stoppedCombined.diagnostics().stream()
                                .filter(d -> d.message().contains("allocation escapes through argument"))
                                .flatMap(d -> d.notes().stream()).anyMatch(n -> n.message()
                                        .contains("invocation evidence storage limit")),
                "combined forced stop changed proofs or lost its boundary");
    }

    private static CompilationArtifact limited(SourceFile source, boolean explain,
                                               SemanticObserverBridge.Counts counts,
                                               int local, int snapshots, int invocation) {
        return new CompilerPipeline(UnfreedMode.OFF, explain,
                (mode, sources, enabled) -> SemanticObserverBridge.createWithLimits(
                        mode, sources, enabled, counts, source.path(),
                        local, snapshots, invocation)).analyze(List.of(source));
    }

    private static void verifyCompleted() {
        SourceFile source = SourceFile.of("Ready.iron", """
                class Ready {
                    public static int main(String[] args) {
                        Object value = new Object();
                        free value;
                        return 0;
                    }
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact observed = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, source.path())).compile(List.of(source));
        SemanticObserverBridge.Counts disabledCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact disabledObserved = new CompilerPipeline(UnfreedMode.OFF, false,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, disabledCounts, source.path())).compile(List.of(source));
        CompilationArtifact plain = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .compile(List.of(source));
        require(observed.valid() && plain.valid() && disabledObserved.valid(),
                "safe control did not compile");
        require(samePrimaries(observed.diagnostics(), plain.diagnostics())
                && observed.llvmIr().equals(plain.llvmIr())
                && observed.llvmIr().equals(disabledObserved.llvmIr()),
                "observer changed successful output");
        require(counts.entered() >= 2 && counts.entered() == counts.outcomes()
                && counts.stable() == 1 && counts.finished() == 1 && counts.completed(),
                "completed refinement events were absent or inconsistent");
        require(counts.lowerings().stream().anyMatch(lowering -> !lowering.finalPhase()),
                "provisional lowering was not observed");
        require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                        && lowering.refinementCompleted() && lowering.collectorPresent()),
                "final completed lowering was not observed");
        require(disabledCounts.lowerings().stream().noneMatch(
                        SemanticObserverBridge.Lowering::collectorPresent)
                        && disabledCounts.emptySaves() > 0 && disabledCounts.evidenceSaves() == 0
                        && disabledCounts.origins() == 0 && counts.origins() > 0
                        && counts.evidenceSaves() > 0 && counts.collectorsFinished() > 0
                        && counts.collectorHighWater() > 0
                        && counts.snapshotHighWater() <= 2_048
                        && counts.invocationHighWater() > 0
                        && counts.invocationHighWater() <= 1_048_576,
                "collector lifecycle or shared disabled snapshots were not observed");
        require(counts.created("ESCAPE") >= 4
                && counts.created("SYMBOLIC_RETURN") == counts.created("ESCAPE")
                && counts.created("OWNED_FIELD") >= 4
                && counts.created("EFFECT") == 2
                && counts.phases("REFINEMENT") >= 2
                && counts.fieldComparisons() >= 1
                && counts.selectedInstancesWereCreated()
                && counts.summaryEvidencePresent() == counts.created("ESCAPE")
                && counts.summaryEvidenceRetired()
                && disabledCounts.summaryEvidencePresent() == 0
                && counts.fieldEvidencePresent() == counts.created("OWNED_FIELD")
                && counts.fieldEvidenceRetired()
                && disabledCounts.fieldEvidencePresent() == 0
                && counts.dispatchEvidencePresent() == 1
                && counts.dispatchEvidenceRetired() == 1
                && disabledCounts.dispatchEvidencePresent() == 0
                && counts.budgetFinished() == 1 && counts.finalBudgetLive() == 0
                && counts.budgetHighWater() > 0 && !counts.budgetStopped()
                && counts.peakLiveRoots() >= 2
                && counts.totalSummaryFacts() > 0
                && disabledCounts.budgetFinished() == 0,
                "analyzer lifecycle or final selection was not observed");
        require(counts.rounds("ESCAPE") > counts.created("ESCAPE")
                && counts.rounds("SYMBOLIC_RETURN") >= counts.created("SYMBOLIC_RETURN")
                && counts.rounds("EFFECT") >= counts.created("EFFECT"),
                "stable inner rounds were not observed");
        require(counts.projections().size() == 4
                        && counts.projections().equals(disabledCounts.projections()),
                "selected proof projections changed with explanation mode");
    }

    private static void verifySkipped() {
        SourceFile source = SourceFile.of("Skip.iron", """
                class Base {
                    void keep(Object value) {
                    }
                }
                class Skip extends Base {
                    void keep(Object value) {
                    }
                    static void check() {
                        Object value = new Object();
                        Skip.saved = value;
                        free value;
                    }
                    static Object saved;
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact observed = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, source.path())).analyze(List.of(source));
        CompilationArtifact plain = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!observed.valid() && samePrimaries(observed.diagnostics(), plain.diagnostics())
                        && sameNotes(observed.diagnostics(), plain.diagnostics()),
                "skipped control changed diagnostics");
        require(counts.entered() == 0 && counts.outcomes() == 0
                && counts.finished() == 1 && !counts.completed(),
                "skipped refinement entered an iteration");
        require(counts.lowerings().stream().noneMatch(lowering -> !lowering.finalPhase()),
                "skipped refinement performed provisional lowering");
        require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                        && !lowering.refinementCompleted() && !lowering.collectorPresent()),
                "skipped final lowering was not observed");
        require(counts.created("ESCAPE") == 2 && counts.created("SYMBOLIC_RETURN") == 2
                && counts.created("OWNED_FIELD") == 2
                && counts.created("EFFECT") == 1
                && counts.phases("REFINEMENT") == 0
                && counts.fieldComparisons() == 0
                && counts.selectedInstancesWereCreated()
                && counts.summaryEvidencePresent() == 0,
                "skipped run created provisional analyzers or lost final selection");
        require(counts.projections().keySet().containsAll(
                        java.util.Set.of("ESCAPE", "SYMBOLIC_RETURN", "OWNED_FIELD")),
                "skipped run lost selected proof projections: " + counts.projections().keySet());
    }

    private static void verifyCallableKinds() {
        SourceFile source = SourceFile.of("Kinds.iron", """
                class Kinds {
                    static Object shared = new Object();
                    private Object owned = new Object();
                    Kinds() { }
                    destructor { free owned; }
                    static void work() { }
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact observed = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, source.path())).analyze(List.of(source));
        CompilationArtifact plain = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(observed.valid() && plain.valid()
                        && samePrimaries(observed.diagnostics(), plain.diagnostics()),
                "callable-kind observer fixture changed analysis");
        for (String callable : List.of("<clinit>", "<init>", "<destructor>", "work")) {
            require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                            && lowering.refinementCompleted() && lowering.collectorPresent()
                            && lowering.linkageName().contains(callable)),
                    "final lowering absent for " + callable);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static boolean samePrimaries(List<Diagnostic> left, List<Diagnostic> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            Diagnostic a = left.get(index);
            Diagnostic b = right.get(index);
            if (!a.message().equals(b.message()) || a.severity() != b.severity()
                    || !java.util.Objects.equals(a.span(), b.span())
                    || !java.util.Objects.equals(a.source() == null ? null : a.source().path(),
                            b.source() == null ? null : b.source().path())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNotes(List<Diagnostic> left, List<Diagnostic> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).notes().equals(right.get(index).notes())) return false;
        }
        return true;
    }
}
