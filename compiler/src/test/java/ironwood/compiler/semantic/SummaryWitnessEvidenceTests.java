// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

/** Storage, identity, and retirement checks for analyzer-owned summary evidence. */
public final class SummaryWitnessEvidenceTests {
    private SummaryWitnessEvidenceTests() {
    }

    public static void boundedStore() {
        SourceFile source = SourceFile.of("Summary.iron", "value");
        SourceSpan span = SourceSpan.at(new SourcePosition(0, 1, 1));
        RejectedFreeEvidence.Budget budget = new RejectedFreeEvidence.Budget(1_000);
        SummaryWitnessEvidence store = new SummaryWitnessEvidence(budget);
        var first = new SummaryWitnessEvidence.Fact(
                SummaryWitnessEvidence.Effect.NON_RETURN_ESCAPE, 0, null);
        var second = new SummaryWitnessEvidence.Fact(
                SummaryWitnessEvidence.Effect.NON_RETURN_ESCAPE, 1, null);
        var cause = store.first("B.store", first, source, span, "store", null);
        var forwarding = store.first("A.forward", second, source, span, "call", cause);
        require(cause != null && forwarding != null && cause.ordinal() < forwarding.ordinal()
                        && forwarding.dependency() == cause && budget.live() == 11,
                "first-discovery dependency was not charged or ordered");
        require(store.first("B.store", first, source, span, "later", null) == cause
                        && cause.reason().equals("store") && budget.live() == 11,
                "later derivation replaced the first witness");
        store.remove("B.store", first);
        require(store.get("B.store", first) == null && store.methodLive("B.store") == 4
                        && budget.live() == 10,
                "removing a root retired a dependency still used by its caller");
        var replacement = store.first("B.store", first, source, span, "new store", null);
        require(replacement != null && replacement != cause
                        && replacement.ordinal() > forwarding.ordinal()
                        && forwarding.dependency() == cause
                        && store.get("B.store", first) == replacement,
                "a reappearing fact overwrote an earlier immutable dependency");
        store.remove("A.forward", second);
        require(budget.live() == 6 && store.methodLive("B.store") == 5,
                "removing the last dependency did not retire the producing node");
        store.remove("B.store", first);
        require(budget.live() == 2, "replacement root retained its node after removal");
        store.close();
        require(budget.live() == 0, "closing a retired store retained units");

        RejectedFreeEvidence.Budget localBudget = new RejectedFreeEvidence.Budget(10_000);
        SummaryWitnessEvidence local = new SummaryWitnessEvidence(localBudget);
        for (int index = 0; index < (SummaryWitnessEvidence.METHOD_LIMIT - 1) / 4; index++) {
            var fact = new SummaryWitnessEvidence.Fact(
                    SummaryWitnessEvidence.Effect.RAW_ESCAPE, index, null);
            require(local.first("Crowded", fact, source, span, "store", null) != null,
                    "local fact failed before the declared method cap");
        }
        require(local.methodLive("Crowded") == SummaryWitnessEvidence.METHOD_LIMIT - 3
                        && local.first("Crowded", new SummaryWitnessEvidence.Fact(
                                SummaryWitnessEvidence.Effect.RAW_ESCAPE,
                                SummaryWitnessEvidence.METHOD_LIMIT, null),
                                source, span, "store", null) == null
                        && local.methodTruncated("Crowded")
                        && local.first("Other", first, source, span, "store", null) != null,
                "one method's local cap consumed another method's allowance");
        local.close();
        require(localBudget.live() == 0, "method retirement kept aggregate charges");

        RejectedFreeEvidence.Budget chainBudget = new RejectedFreeEvidence.Budget(1_000);
        SummaryWitnessEvidence chain = new SummaryWitnessEvidence(chainBudget);
        SummaryWitnessEvidence.Witness previous = null;
        boolean exhausted = false;
        for (int index = 0; index < 30; index++) {
            var fact = new SummaryWitnessEvidence.Fact(
                    SummaryWitnessEvidence.Effect.NON_RETURN_ESCAPE, index, null);
            SummaryWitnessEvidence.Witness next = chain.first("Link" + index,
                    fact, source, span, "call", previous);
            if (next == null) {
                exhausted = chain.methodTruncated("Link" + index);
                break;
            }
            previous = next;
        }
        require(exhausted && previous != null
                        && previous.chainUnits() <= SummaryWitnessEvidence.FACT_LIMIT,
                "dependency chain exceeded the fact cap");
        chain.close();
        require(chainBudget.live() == 0, "dependency retirement retained ancestor nodes");

        RejectedFreeEvidence.Budget emergency = new RejectedFreeEvidence.Budget(5);
        SummaryWitnessEvidence stopped = new SummaryWitnessEvidence(emergency);
        require(stopped.first("A", first, source, span, "store", null) != null
                        && stopped.first("B", first, source, span, "store", null) == null
                        && stopped.invocationStopped(),
                "aggregate emergency stop did not latch separately from local caps");
        stopped.close();
        require(emergency.live() == 0 && emergency.stopped(),
                "retirement cleared the emergency stop or retained live units");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
