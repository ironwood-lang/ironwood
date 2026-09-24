// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.List;

/** Package-local storage and identity checks for the optional collector. */
public final class RejectedFreeEvidenceTests {
    private RejectedFreeEvidenceTests() {
    }

    public static void snapshotsAndBudgets() {
        SourceFile source = SourceFile.of("Evidence.iron", "one\ntwo\n");
        SourceSpan first = SourceSpan.at(new SourcePosition(0, 1, 1));
        SourceSpan second = SourceSpan.at(new SourcePosition(4, 2, 1));
        RejectedFreeEvidence.Budget budget = new RejectedFreeEvidence.Budget(30);
        RejectedFreeEvidence evidence = new RejectedFreeEvidence(budget, 20, 4);
        Object a = new Object();
        Object b = new Object();
        String left = new String("equal proof");
        String right = new String("equal proof");
        require(evidence.origin(a, source, first) && evidence.save(left) == 1,
                "left evidence was not saved");
        require(evidence.origin(b, source, second) && evidence.save(right) == 2,
                "right evidence was not saved");
        require(evidence.restore(left) && evidence.origin(a).span().equals(first)
                        && evidence.origin(b) == null,
                "identity-equal proof snapshots shared diagnostic history");
        require(evidence.restore(right) && evidence.origin(b).span().equals(second),
                "right snapshot lost its origin");
        require(evidence.merge(List.of(left, right)) && evidence.origin(a) != null
                        && evidence.origin(b) == null,
                "merge kept an origin absent on one incoming path");
        require(evidence.merge(List.of()) && evidence.origin(a) != null,
                "empty merge discarded the restored path");
        require(!evidence.restore(new Object()) && evidence.origin(a) == null,
                "missing snapshot reused a stale origin");
        require(evidence.highWater() == 11 && evidence.snapshotHighWater() == 3,
                "collector exceeded local caps");
        evidence.close();
        require(budget.live() == 0, "retired function retained invocation charges");

        RejectedFreeEvidence.Budget releasedBudget = new RejectedFreeEvidence.Budget(10);
        RejectedFreeEvidence released = new RejectedFreeEvidence(releasedBudget, 10, 4);
        require(released.origin(a, source, first) && releasedBudget.live() == 2
                        && !released.restore(new Object()) && releasedBudget.live() == 0,
                "an origin without a retained snapshot kept a stale live charge");
        released.close();

        RejectedFreeEvidence.Budget localBudget = new RejectedFreeEvidence.Budget(30);
        RejectedFreeEvidence local = new RejectedFreeEvidence(localBudget, 8, 1);
        require(local.origin(a, source, first) && local.save(left) == 1
                        && local.save(right) == -1 && local.localTruncated()
                        && local.restore(left) && local.origin(a) != null,
                "snapshot subcap lost retained earlier evidence");
        local.close();
        require(localBudget.live() == 0, "local truncation leaked charges");

        RejectedFreeEvidence.Budget emergency = new RejectedFreeEvidence.Budget(2);
        RejectedFreeEvidence stopped = new RejectedFreeEvidence(emergency, 20, 14);
        require(stopped.origin(a, source, first) && stopped.save(left) == -1
                        && stopped.invocationStopped(),
                "invocation-wide safety stop did not latch");
        stopped.close();
        require(emergency.live() == 0 && emergency.stopped(),
                "retirement reset the invocation stop or leaked charges");

        RejectedFreeEvidence.Budget bindingBudget = new RejectedFreeEvidence.Budget(50);
        RejectedFreeEvidence bindings = new RejectedFreeEvidence(bindingBudget, 40, 10);
        Object localSymbol = new Object();
        Object firstPath = new Object();
        Object secondPath = new Object();
        require(bindings.bind(localSymbol, a, source, first) && bindings.save(firstPath) == 1,
                "first local binding was not saved");
        require(bindings.bind(localSymbol, b, source, second) && bindings.save(secondPath) == 1,
                "replacement binding was not saved");
        require(bindings.restore(firstPath) && bindings.binding(localSymbol).allocation() == a
                        && bindings.binding(localSymbol).span().equals(first),
                "restored binding used the replacement's source or identity");
        require(bindings.merge(List.of(firstPath, secondPath)) && bindings.binding(localSymbol) == null,
                "join retained an arbitrary predecessor's local binding");
        require(bindings.restore(secondPath) && bindings.binding(localSymbol).allocation() == b,
                "second binding was lost after the join");
        bindings.unbind(localSymbol);
        require(bindings.binding(localSymbol) == null && bindingBudget.live() == 8,
                "scope exit retained a current binding or leaked its charge");
        bindings.close();
        require(bindingBudget.live() == 0, "binding snapshots leaked invocation charges");

        RejectedFreeEvidence.Budget eventBudget = new RejectedFreeEvidence.Budget(50);
        RejectedFreeEvidence events = new RejectedFreeEvidence(eventBudget, 40, 10);
        require(events.selectedReason(a, "same reason") && events.save(firstPath) == 1,
                "first selected reason was not saved");
        RejectedFreeEvidence.Event firstEvent = events.event(a);
        require(events.selectedReason(a, "same reason") && events.save(secondPath) == 1
                        && events.event(a) != firstEvent,
                "identical reason text suppressed an accepted replacement");
        require(events.merge(List.of(firstPath, secondPath)) && events.event(a) == null,
                "join retained an arbitrary same-text reason event");
        require(events.restore(firstPath) && events.event(a) == firstEvent,
                "restore lost the first selected reason identity");
        require(events.reclaimed(a, source, first)
                        && events.event(a).kind() == RejectedFreeEvidence.EventKind.FREE
                        && events.event(a).span().equals(first),
                "accepted reclamation did not replace the current reason");
        require(events.restore(secondPath)
                        && events.event(a).kind() == RejectedFreeEvidence.EventKind.REASON,
                "restore reused a later reclamation on an earlier path");
        events.close();
        require(eventBudget.live() == 0, "selected events leaked invocation charges");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
