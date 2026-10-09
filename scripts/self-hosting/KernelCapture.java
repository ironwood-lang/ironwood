// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

/** Test-only adapter around actual seed kernels; input construction is outside the phase. */
public final class KernelCapture {
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType REF = IrType.reference("Node");
    private KernelCapture() {}
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("kernel contract");
    }
    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    private static final class Node {
        @Override public int hashCode() { return 0; }
    }
    private record Slot(Node node, int index) {}
    interface Work {
        void run();
        String verify();
    }
    private static final class EvidenceWork implements Work {
        private static final int ITERATIONS = 64;
        private final RejectedFreeEvidence.Budget budget = new RejectedFreeEvidence.Budget(1_048_576);
        private final RejectedFreeEvidence store = new RejectedFreeEvidence(budget, 1_048_576, 1_048_576);
        private final SourceFile source = SourceFile.of("kernel.iron", "class Kernel {}\n");
        private final Node[] nodes;
        // Strong keys keep every saved version live until explicit close, independent of GC.
        private final Object[] keys = new Object[ITERATIONS * 2];
        private final List<List<Object>> paths = new ArrayList<>();
        private long associations;
        private int exactIntersections;
        EvidenceWork(int size) {
            nodes = new Node[size];
            for (int i = 0; i < size; i++) nodes[i] = new Node();
            for (int i = 0; i < keys.length; i++) keys[i] = new Object();
            for (int i = 0; i < ITERATIONS; i++) paths.add(List.of(keys[2 * i], keys[2 * i + 1]));
            for (int i = 0; i < size; i++) {
                require(store.origin(nodes[i], source, SPAN));
                require(store.bind(nodes[i], nodes[i], source, SPAN));
                require(store.arrayStore(new Slot(nodes[i], i * 65_537), source, SPAN));
                require(store.retain(nodes[i], nodes[(i + 1) % size], source, SPAN));
                require(store.selectedReason(nodes[i], "reason", source, SPAN));
                require(store.joined(nodes[i], new RejectedFreeEvidence.Join("state", "reason", List.of(), 0, "complete", true)));
            }
        }
        @Override public void run() {
            for (int i = 0; i < ITERATIONS; i++) {
                int first = store.save(keys[2 * i]);
                require(first == nodes.length * 6);
                associations += first;
                require(store.selectedReason(nodes[0], "reason", source, SPAN));
                require(store.joined(nodes[0], new RejectedFreeEvidence.Join("state", "reason", List.of(), 0, "complete", true)));
                int second = store.save(keys[2 * i + 1]);
                require(second == first);
                associations += second;
                require(store.merge(paths.get(i)));
                if (store.event(nodes[0]) == null && store.join(nodes[0]) == null
                        && store.event(nodes[1]) != null && store.join(nodes[1]) != null
                        && store.binding(nodes[0]).allocation() == nodes[0]) exactIntersections++;
                require(store.restore(keys[2 * i]));
            }
            store.close();
        }
        @Override public String verify() {
            require(associations == (long) ITERATIONS * 2 * nodes.length * 6);
            require(exactIntersections == ITERATIONS && budget.live() == 0);
            require(!store.localTruncated() && !budget.stopped());
            return "associations=" + associations + "\nexactIntersections=" + exactIntersections
                    + "\nbudgetHighWater=" + budget.highWater() + "\nsnapshotHighWater=" + store.snapshotHighWater()
                    + "\nfinalBudgetLive=" + budget.live() + "\n";
        }
    }
    private static final class EffectWork implements Work {
        private final List<IrFunction> functions = new ArrayList<>();
        private final int width;
        private final Counter observer;
        private ClosedWorldEffectAnalyzer analyzer;
        EffectWork(int count, int width, boolean observed, boolean cyclic) {
            this.width = width;
            observer = observed ? new Counter() : null;
            List<IrParameter> parameters = new ArrayList<>();
            List<IrOperand> arguments = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                IrValueReference value = new IrValueReference(i, REF, SPAN);
                parameters.add(new IrParameter("p" + i, value, SPAN));
                arguments.add(value);
            }
            for (int i = 0; i < count; i++) {
                IrValueReference result = new IrValueReference(width, REF, SPAN);
                IrInstruction call = i + 1 == count
                        ? new IrForeignCallInstruction(Optional.of(result), "ironwood_bridge_callback_effect", REF, arguments, SPAN)
                        : new IrCallInstruction(Optional.of(result), "F" + (i + 1), REF, arguments, SPAN);
                // The foreign seed feeds a real call cycle; every parameter escapes around it.
                List<IrInstruction> calls = cyclic && i + 1 == count
                        ? List.of(call, new IrCallInstruction(Optional.empty(), "F0", REF, arguments, SPAN))
                        : List.of(call);
                functions.add(new IrFunction("Node", "F" + i, "F" + i, REF, parameters,
                        List.of(new IrBasicBlock("entry", calls,
                                new IrReturnTerminator(Optional.of(result), SPAN), SPAN)), SPAN));
            }
        }
        @Override public void run() {
            analyzer = new ClosedWorldEffectAnalyzer(functions, List.of(), observer, 17,
                    SemanticAnalysisObserver.AnalyzerPhase.INITIAL);
            analyzer.analyze();
        }
        @Override public String verify() {
            BitSet all = new BitSet();
            all.set(0, width);
            Map<String, String> facts = analyzer.observerProjection();
            require(facts.size() == functions.size());
            StringBuilder output = new StringBuilder();
            // Explicit input order serializes an extensional projection, not diagnostics or IR.
            for (IrFunction function : functions) {
                String fact = facts.get(function.linkageName());
                for (String field : List.of("publishedParameters", "returnedParameters", "reclaimedParameters")) {
                    require(fact.contains(field + "=" + all));
                }
                require(fact.contains("allocates=true, throwsOutward=true"));
                output.append(function.linkageName()).append('=').append(fact).append('\n');
            }
            if (observer != null) require(observer.created == 1 && observer.rounds == functions.size() + 1);
            return output.toString();
        }
    }
    private static final class Counter implements SemanticAnalysisObserver {
        private int created;
        private int rounds;
        @Override public void analyzerCreated(long token, AnalyzerKind kind, AnalyzerPhase phase) { created++; }
        @Override public void analyzerRound(long token, AnalyzerKind kind, int round) { require(round == rounds++); }
        @Override public void analyzerSelected(long token, AnalyzerKind kind) {}
        @Override public void summaryEvidenceLifecycle(long token, boolean present, boolean retired) {}
        @Override public void fieldEvidenceLifecycle(long token, boolean present, boolean retired) {}
        @Override public void fieldEvidenceFinished(long token, int failures, int units) {}
        @Override public void dispatchEvidenceLifecycle(boolean present, boolean retired) {}
        @Override public void evidenceBudgetFinished(int live, int highWater, boolean stopped) {}
        @Override public void summaryEvidenceFinished(long token, boolean truncated, boolean stopped, int methods, int facts, int units) {}
        @Override public void summaryWitnessProjection(long token, Map<String, String> facts) {}
        @Override public void selectedProjection(long token, AnalyzerKind kind, Map<String, String> facts) {}
        @Override public void fieldProofCompared(int pass, boolean same) {}
        @Override public void refinementEntered(int pass) {}
        @Override public void refinementOutcome(int pass, boolean stable, boolean fieldsStable) {}
        @Override public void refinementFinished(boolean completed) {}
        @Override public void lowering(String linkage, SourceFile source, boolean finalPhase, boolean completed, boolean collector) {}
        @Override public void evidenceSnapshot(boolean saved, boolean sharedEmpty, int associations) {}
        @Override public void evidenceOrigin(SourceFile source) {}
        @Override public void collectorFinished(String linkage, int live, int snapshot, int invocation, boolean truncated, boolean stopped) {}
    }
    private static final class State {
        private volatile boolean finished;
        private Throwable failure;
        private long phaseNanos;
        private long heapAtStart;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("evidence|effect|effect-cycle|ownership size width-or-shape sample-on|sample-off observer-on|observer-off|explain-on|explain-off output");
        require(List.of("evidence", "effect", "effect-cycle", "ownership").contains(args[0]));
        require(List.of("sample-on", "sample-off").contains(args[3]));
        require((args[0].equals("ownership") ? List.of("explain-on", "explain-off") : List.of("observer-on", "observer-off")).contains(args[4]));
        int size = Integer.parseInt(args[1]);
        int width = Integer.parseInt(args[2]);
        require(size >= 2 && size <= 128 && width >= 1 && width <= 257);
        boolean sample = args[3].equals("sample-on");
        boolean observed = args[4].equals("observer-on") || args[4].equals("explain-on");
        require(!args[0].equals("evidence") || !observed);
        Path output = Path.of(args[5]);
        Files.createDirectories(output);
        long constructionStart = System.nanoTime();
        Work work = args[0].equals("evidence") ? new EvidenceWork(size)
                : args[0].startsWith("effect") ? new EffectWork(size, width, observed, args[0].equals("effect-cycle"))
                : new OwnershipWork(size, width, observed);
        long constructionNanos = System.nanoTime() - constructionStart;
        State state = new State();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                state.heapAtStart = usedHeap();
                long began = System.nanoTime();
                try { work.run(); } finally { state.phaseNanos = System.nanoTime() - began; }
            } catch (Throwable failure) { state.failure = failure; }
            finally { state.finished = true; }
        }, "m0-kernel-worker");
        worker.start();
        ready.await();
        long samples = 0, heapHigh = 0, gapHigh = 0, previous = System.nanoTime();
        int frameHigh = 0;
        start.countDown();
        if (sample) while (!state.finished) {
            long now = System.nanoTime();
            gapHigh = Math.max(gapHigh, now - previous);
            previous = now;
            heapHigh = Math.max(heapHigh, usedHeap());
            frameHigh = Math.max(frameHigh, worker.getStackTrace().length);
            samples++;
            LockSupport.parkNanos(1_000_000);
        }
        worker.join();
        if (state.failure != null) throw new IllegalStateException("kernel worker failed", state.failure);
        long verificationStart = System.nanoTime();
        String result = work.verify();
        long verificationNanos = System.nanoTime() - verificationStart;
        Files.writeString(output.resolve("result.txt"), result);
        Files.writeString(output.resolve("metrics.txt"), "constructionNanos=" + constructionNanos
                + "\nphaseNanos=" + state.phaseNanos + "\nverificationNanos=" + verificationNanos
                + "\nheapAtStartBytes=" + state.heapAtStart + "\nsampledHeapHighBytes=" + heapHigh
                + "\nsampledFramesHigh=" + frameHigh + "\nsamples=" + samples
                + "\nmaximumSampleGapNanos=" + gapHigh + "\nobserverRounds="
                + (work instanceof EffectWork effect && effect.observer != null ? effect.observer.rounds : 0) + "\n");
    }
}
