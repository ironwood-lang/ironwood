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
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
