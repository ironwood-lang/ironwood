// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

/** Test-only fresh-process resource adapter; it launches no native child tools. */
public final class ResourceCapture {
    private ResourceCapture() {}

    private record Metrics(String stage, boolean sampled, boolean observed, boolean explain,
                           long requestedSamplePeriodNanos, long samples,
                           long usedHeapAtStartBytes, long sampledUsedHeapHighWaterBytes,
                           int sampledWorkerFrameHighWater, long maximumSampleGapNanos,
                           long phaseWallNanos, long sourceBytes, boolean successful,
                           String failure, long errorCount, long warningCount) {}

    private record ObserverMetrics(int evidenceSaves, int evidenceRestores, int emptySaves,
                                   int emptyRestores, int collectorHighWater, int snapshotHighWater,
                                   int invocationHighWater, int budgetHighWater, int finalBudgetLive,
                                   boolean budgetStopped, int peakLiveRoots, long summaryMethods,
                                   long summaryFacts, long summaryUnits, long fieldFailures,
                                   long fieldUnits, int effectRounds, long effectAnalyzers,
                                   boolean summaryRetired, boolean fieldRetired) {}

    private static final class State {
        private Object tokens;
        private Object ast;
        private List<ironwood.compiler.diagnostic.Diagnostic> diagnostics = List.of();
        private CompilationArtifact artifact;
        private String finalLlvm;
        private Throwable failure;
        private long phaseWallNanos;
        private long usedHeapAtStart;
        private long usedHeapHighWater;
        private int frameHighWater;
        private long samples;
        private long maximumGap;
        private volatile boolean finished;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("input output frontend|complete sample-on|sample-off observer-on|observer-off explain-on|explain-off");
        }
        String stage = args[2];
        if (!stage.equals("frontend") && !stage.equals("complete")) throw new IllegalArgumentException("stage");
        if (!List.of("sample-on", "sample-off").contains(args[3])
                || !List.of("observer-on", "observer-off").contains(args[4])
                || !List.of("explain-on", "explain-off").contains(args[5])) {
            throw new IllegalArgumentException("measurement flags");
        }
        boolean sample = args[3].equals("sample-on");
        boolean observe = args[4].equals("observer-on");
        boolean explain = args[5].equals("explain-on");
        if (stage.equals("frontend") && (observe || explain)) throw new IllegalArgumentException("frontend flags");
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        // Setup and serialization count toward process RSS/wall, outside phase sampling.
        ReferenceCapture.verifyLibrary(output);
        long sourceBytes = Files.size(input);
        SourceFile source = SourceFile.of(input.getFileName().toString(), Files.readString(input, StandardCharsets.UTF_8));
        State state = new State();
        SemanticObserverBridge.Counts counts = observe ? new SemanticObserverBridge.Counts() : null;
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                state.usedHeapAtStart = usedHeap();
                long started = System.nanoTime();
                try {
                    if (stage.equals("frontend")) {
                        var lexed = new Lexer(source).lex();
                        var parsed = new Parser(source, lexed.tokens()).parse();
                        state.tokens = lexed.tokens();
                        state.ast = parsed.unit();
                        var diagnostics = new java.util.ArrayList<>(lexed.diagnostics());
                        diagnostics.addAll(parsed.diagnostics());
                        state.diagnostics = List.copyOf(diagnostics);
                    } else {
                        CompilerPipeline pipeline = new CompilerPipeline(UnfreedMode.WARN, explain,
                                observe ? (mode, paths, enabled) -> SemanticObserverBridge.create(
                                        mode, paths, enabled, counts, source.path()) : null);
                        state.artifact = pipeline.compile(List.of(source));
                        state.diagnostics = state.artifact.diagnostics();
                        if (state.artifact.successful()) {
                            state.finalLlvm = new ironwood.compiler.backend.LlvmEmitter().emit(
                                    NativeLinkPipeline.finish(state.artifact.program().orElseThrow()));
                        }
                    }
                } finally {
                    state.phaseWallNanos = System.nanoTime() - started;
                }
            } catch (Throwable failure) {
                state.failure = failure;
            } finally {
                state.finished = true;
            }
        }, "m0-measured-worker");
        worker.start();
        ready.await();
        long period = 1_000_000;
        long previousSample = System.nanoTime();
        start.countDown();
        if (sample) {
            while (!state.finished) {
                long now = System.nanoTime();
                state.maximumGap = Math.max(state.maximumGap, now - previousSample);
                previousSample = now;
                state.usedHeapHighWater = Math.max(state.usedHeapHighWater, usedHeap());
                state.frameHighWater = Math.max(state.frameHighWater, worker.getStackTrace().length);
                state.samples++;
                LockSupport.parkNanos(period);
            }
        }
        worker.join();
        if (sample) state.usedHeapHighWater = Math.max(state.usedHeapHighWater, usedHeap());
        boolean successful = state.failure == null && (stage.equals("frontend")
                ? !ironwood.compiler.diagnostic.Diagnostic.hasErrors(state.diagnostics)
                : state.artifact.successful());
        String failure = state.failure == null ? "" : state.failure.getClass().getName();
        long errors = state.diagnostics.stream().filter(d -> d.isError()).count();
        Metrics metrics = new Metrics(stage, sample, observe, explain, period, state.samples,
                state.usedHeapAtStart, state.usedHeapHighWater, state.frameHighWater, state.maximumGap,
                state.phaseWallNanos, sourceBytes, successful, failure, errors,
                state.diagnostics.size() - errors);
        ReferenceCapture.save(output, "resource", metrics);
        ReferenceCapture.save(output, "diagnostics", state.diagnostics);
        if (stage.equals("frontend")) {
            ReferenceCapture.save(output, "tokens", state.tokens);
            ReferenceCapture.save(output, "ast", state.ast);
        } else if (state.artifact != null) {
            if (state.artifact.program().isPresent()) {
                var program = state.artifact.program().orElseThrow();
                ReferenceCapture.save(output, "program-counts", List.of(program.classes().size(),
                        program.functions().size(), program.functions().stream().mapToInt(
                                function -> function.blocks().size()).sum()));
            }
            if (state.finalLlvm != null) {
                Files.writeString(output.resolve("output.ll"), state.finalLlvm, StandardCharsets.UTF_8);
            }
        }
        if (counts != null) {
            ReferenceCapture.save(output, "observer", new ObserverMetrics(counts.evidenceSaves(),
                    counts.evidenceRestores(), counts.emptySaves(), counts.emptyRestores(),
                    counts.collectorHighWater(), counts.snapshotHighWater(), counts.invocationHighWater(),
                    counts.budgetHighWater(), counts.finalBudgetLive(), counts.budgetStopped(),
                    counts.peakLiveRoots(), counts.totalSummaryMethods(), counts.totalSummaryFacts(),
                    counts.totalSummaryUnits(), counts.totalFieldFailures(), counts.totalFieldUnits(),
                    counts.rounds("EFFECT"), counts.created("EFFECT"), counts.summaryEvidenceRetired(),
                    counts.fieldEvidenceRetired()));
        }
        if (state.failure != null) throw new IllegalStateException("measured worker failed", state.failure);
    }
}
