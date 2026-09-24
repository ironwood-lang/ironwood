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
                && observed.llvmIr().equals(plain.llvmIr()),
                "observer changed successful output");
        require(counts.entered() >= 2 && counts.entered() == counts.outcomes()
                && counts.stable() == 1 && counts.finished() == 1 && counts.completed(),
                "completed refinement events were absent or inconsistent");
        require(counts.lowerings().stream().anyMatch(lowering -> !lowering.finalPhase()),
                "provisional lowering was not observed");
        require(counts.lowerings().stream().anyMatch(lowering -> lowering.finalPhase()
                        && lowering.refinementCompleted() && !lowering.collectorPresent()),
                "final completed lowering was not observed");
        require(counts.created("ESCAPE") >= 4
                && counts.created("SYMBOLIC_RETURN") == counts.created("ESCAPE")
                && counts.created("OWNED_FIELD") >= 4
                && counts.created("EFFECT") == 2
                && counts.phases("REFINEMENT") >= 2
                && counts.fieldComparisons() >= 1
                && counts.selectedInstancesWereCreated(),
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
                && counts.selectedInstancesWereCreated(),
                "skipped run created provisional analyzers or lost final selection");
        require(counts.projections().keySet().containsAll(
                        java.util.Set.of("ESCAPE", "SYMBOLIC_RETURN", "OWNED_FIELD")),
                "skipped run lost selected proof projections: " + counts.projections().keySet());
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
