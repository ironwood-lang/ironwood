// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Calls original J0 on adversarial effect graphs, not a replacement analyzer. */
public final class EffectContractProbe {
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType REF = IrType.reference("Node");
    private static final IrReturnTerminator DONE = new IrReturnTerminator(Optional.empty(), SPAN);
    private static int checks;

    private EffectContractProbe() {}

    private static void require(boolean condition, String contract) {
        checks++;
        if (!condition) throw new AssertionError(contract);
    }

    private static IrValueReference value(int id) {
        return new IrValueReference(id, REF, SPAN);
    }

    private static List<IrParameter> parameters(int size) {
        List<IrParameter> result = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            result.add(new IrParameter("p" + index, value(index), SPAN));
        }
        return result;
    }

    private static IrFunction function(String name, List<IrParameter> parameters,
                                       List<IrInstruction> body, Optional<IrOperand> returned) {
        return new IrFunction("Node", name, name, returned.isPresent() ? REF : IrType.VOID,
                parameters, List.of(new IrBasicBlock("entry", body,
                new IrReturnTerminator(returned, SPAN), SPAN)), SPAN);
    }

    private static IrClass type(String name, List<String> interfaces, List<IrDispatchEntry> dispatch) {
        return new IrClass(name, IrTypeKind.CLASS, Optional.empty(), interfaces, List.of(), 1,
                List.of(1), dispatch, SPAN);
    }

    private static void bits(Map<String, String> facts, String name, String field, BitSet expected) {
        String fact = facts.get(name);
        require(fact != null && fact.contains(field + "=" + expected), name + " " + field);
    }

    private static BitSet bits(int... positions) {
        BitSet result = new BitSet();
        for (int position : positions) result.set(position);
        return result;
    }

    private static void targetUnions(int size) {
        List<IrParameter> parameters = parameters(size);
        List<IrOperand> arguments = new ArrayList<>();
        parameters.forEach(parameter -> arguments.add(parameter.value()));
        List<IrInstruction> release = List.of(new IrFreeInstruction(value(0), SPAN),
                new IrFreeInstruction(value(size - 1), SPAN));
        IrFunction first = function("Aa", parameters, release, Optional.of(value(size - 1)));
        IrValueReference foreignResult = value(size);
        IrForeignCallInstruction foreign = new IrForeignCallInstruction(Optional.of(foreignResult),
                "ironwood_bridge_callback_effect", REF, arguments, SPAN);
        IrFunction second = function("BB", parameters, List.of(foreign), Optional.of(foreignResult));
        IrDispatchSlot slot = new IrDispatchSlot(0, "dispatch", "dispatch", REF,
                Collections.nCopies(size, REF), SPAN);
        IrVirtualCallInstruction virtual = new IrVirtualCallInstruction(Optional.of(value(size)),
                slot, REF, arguments, SPAN);
        IrFunction caller = function("Caller", parameters, List.of(virtual), Optional.of(value(size)));
        IrClass firstType = type("Aa", List.of(), List.of(new IrDispatchEntry(slot, "Aa")));
        IrClass secondType = type("BB", List.of(), List.of(new IrDispatchEntry(slot, "BB"),
                new IrDispatchEntry(slot, "BB")));
        BitSet all = new BitSet();
        all.set(0, size);
        Map<String, String> baseline = null;
        for (boolean reverseFunctions : new boolean[]{false, true}) {
            for (boolean reverseClasses : new boolean[]{false, true}) {
                List<IrFunction> functions = new ArrayList<>(List.of(caller, first, second));
                List<IrClass> classes = new ArrayList<>(List.of(firstType, secondType));
                if (reverseFunctions) Collections.reverse(functions);
                if (reverseClasses) Collections.reverse(classes);
                Observer retained = new Observer();
                ClosedWorldEffectAnalyzer analyzer = observed(functions, classes, retained);
                require(retained.created == 1 && retained.rounds == 0, "constructor observer timing");
                analyzer.analyze();
                require(retained.rounds >= 2, "retained observer called in later fixed-point rounds");
                Map<String, String> facts = analyzer.observerProjection();
                if (baseline == null) baseline = facts;
                require(baseline.equals(facts), "target/function permutation preserves extensional facts");
                ClosedWorldEffectAnalyzer plain = new ClosedWorldEffectAnalyzer(functions, classes);
                plain.analyze();
                require(plain.observerProjection().equals(facts), "observer does not change effect facts");
                bits(facts, "Aa", "reclaimedParameters", bits(0, size - 1));
                bits(facts, "Aa", "returnedParameters", bits(size - 1));
                for (String name : List.of("BB", "Caller")) {
                    bits(facts, name, "publishedParameters", all);
                    bits(facts, name, "returnedParameters", all);
                    bits(facts, name, "reclaimedParameters", all);
                    require(facts.get(name).contains("allocates=true, throwsOutward=true"),
                            "foreign conservative flags propagate");
                }
                require(analyzer.mayUnwind(virtual), "cached targets use current summaries");
                BitSet reclaimed = analyzer.possiblyReclaimedArguments(virtual);
                require(reclaimed.equals(all), "may-reclaim target union");
                reclaimed.clear();
                require(analyzer.possiblyReclaimedArguments(virtual).equals(all), "returned bit vector independent");
                IrVirtualCallInstruction equalCall = new IrVirtualCallInstruction(Optional.of(value(size)),
                        slot, REF, arguments, SPAN);
                require(equalCall.equals(virtual) && equalCall != virtual && analyzer.mayUnwind(equalCall),
                        "distinct equal instruction accepted by identity cache");
            }
        }
    }

    // Return the holder before analyze: the observer must survive this helper call.
    private static ClosedWorldEffectAnalyzer observed(List<IrFunction> functions,
                                                       List<IrClass> classes, Observer observer) {
        return new ClosedWorldEffectAnalyzer(functions, classes, observer, 17,
                SemanticAnalysisObserver.AnalyzerPhase.INITIAL);
    }

    private static void renderedRelease() {
        List<IrParameter> two = parameters(2);
        IrFunction release = function("Release", two,
                List.of(new IrReleaseOwnedToStringResultInstruction(value(0), value(1), SPAN)), Optional.empty());
        IrFunction realFree = function("Free", two,
                List.of(new IrFreeInstruction(value(0), SPAN)), Optional.empty());
        IrCallInstruction direct = new IrCallInstruction(Optional.empty(), "Release", IrType.VOID,
                List.of(value(0), value(0)), SPAN);
        IrFunction same = function("Same", parameters(1), List.of(direct), Optional.empty());
        IrDispatchSlot slot = new IrDispatchSlot(0, "release", "release", IrType.VOID, List.of(REF, REF), SPAN);
        IrVirtualCallInstruction mixed = new IrVirtualCallInstruction(Optional.empty(), slot, IrType.VOID,
                List.of(value(0), value(0)), SPAN);
        IrFunction actual = function("Actual", parameters(1), List.of(mixed), Optional.empty());
        IrValueReference converted = value(2);
        IrFunction convertedSame = function("Converted", parameters(1), List.of(
                new IrReferenceConversionInstruction(converted, value(0), SPAN),
                new IrCallInstruction(Optional.empty(), "Release", IrType.VOID,
                        List.of(converted, value(0)), SPAN)), Optional.empty());
        for (boolean reverse : new boolean[]{false, true}) {
            List<IrClass> classes = new ArrayList<>(List.of(
                    type("R", List.of(), List.of(new IrDispatchEntry(slot, "Release"))),
                    type("F", List.of(), List.of(new IrDispatchEntry(slot, "Free")))));
            if (reverse) Collections.reverse(classes);
            ClosedWorldEffectAnalyzer analyzer = new ClosedWorldEffectAnalyzer(
                    List.of(actual, same, convertedSame, release, realFree), classes);
            analyzer.analyze();
            Map<String, String> facts = analyzer.observerProjection();
            bits(facts, "Release", "reclaimedParameters", bits(1));
            bits(facts, "Same", "reclaimedParameters", bits());
            bits(facts, "Converted", "reclaimedParameters", bits());
            bits(facts, "Actual", "reclaimedParameters", bits(0));
        }
    }

    private static void reachability() {
        IrFunction safe = function("Safe", parameters(1), List.of(), Optional.empty());
        IrFunction throwsOut = new IrFunction("Node", "Throws", "Throws", IrType.VOID, parameters(1),
                List.of(new IrBasicBlock("entry", List.of(),
                new IrThrowTerminator(value(0), "unused", Optional.empty(), SPAN), SPAN)), SPAN);
        for (String target : List.of("Safe", "Throws", "Unknown")) {
            IrCallInstruction call = new IrCallInstruction(Optional.empty(), target, IrType.VOID,
                    List.of(value(0)), SPAN);
            IrFunction caller = new IrFunction("Node", "Invoke", "Invoke", IrType.VOID, parameters(1),
                    List.of(new IrBasicBlock("entry", List.of(),
                            new IrInvokeTerminator(call, "normal", "failure", SPAN), SPAN),
                            new IrBasicBlock("normal", List.of(), DONE, SPAN),
                            new IrBasicBlock("failure", List.of(new IrFreeInstruction(value(0), SPAN)), DONE, SPAN)), SPAN);
            ClosedWorldEffectAnalyzer analyzer = new ClosedWorldEffectAnalyzer(List.of(caller, safe, throwsOut), List.of());
            require(!analyzer.nonThrowingAndAllocationFree("Safe"), "proof unavailable before analyze");
            analyzer.analyze();
            require(analyzer.mayUnwind(call) == !target.equals("Safe"), "ordinary unknown remains unknown");
            bits(analyzer.observerProjection(), "Invoke", "reclaimedParameters",
                    target.equals("Safe") ? bits() : bits(0));
            require(analyzer.nonThrowingAndAllocationFree("Safe"), "safe known callable proof");
            require(!analyzer.nonThrowingAndAllocationFree("Throws"), "outward throw rejects proof");
            require(!analyzer.nonThrowingAndAllocationFree("Unknown"), "unknown rejects proof");
            require(!analyzer.mayUnwind(new IrEnsureTypeInitializedInstruction("Absent", SPAN)),
                    "empty initialization target has no user code");
        }
        IrFunction initializer = new IrFunction("I", "init", "Init", IrType.VOID, parameters(1),
                List.of(new IrBasicBlock("entry", List.of(),
                        new IrThrowTerminator(value(0), "unused", Optional.empty(), SPAN), SPAN)),
                SPAN, "Effect.iron", IrCallableKind.CLASS_INITIALIZER);
        ClosedWorldEffectAnalyzer cyclic = new ClosedWorldEffectAnalyzer(List.of(initializer), List.of(
                type("I", List.of("J"), List.of()), type("J", List.of("I"), List.of())));
        cyclic.analyze();
        require(cyclic.mayUnwind(new IrEnsureTypeInitializedInstruction("J", SPAN)),
                "interface cycle terminates and reaches initializer effect");
    }

    private static final class Observer implements SemanticAnalysisObserver {
        private int created;
        private int rounds;

        @Override public void analyzerCreated(long token, AnalyzerKind kind, AnalyzerPhase phase) {
            require(token == 17 && kind == AnalyzerKind.EFFECT && phase == AnalyzerPhase.INITIAL,
                    "retained observer constructor arguments");
            created++;
        }
        @Override public void analyzerRound(long token, AnalyzerKind kind, int round) {
            require(token == 17 && kind == AnalyzerKind.EFFECT && round == rounds,
                    "retained observer round order");
            rounds++;
        }
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

    public static void main(String[] args) {
        require("Aa".hashCode() == "BB".hashCode(), "adversarial text collision");
        for (int size : new int[]{8, 65, 257}) targetUnions(size);
        renderedRelease();
        reachability();
        System.out.println("PASS: " + checks + " original effect contract checks");
    }
}
