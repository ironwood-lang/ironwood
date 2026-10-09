// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.util.List;
import java.util.Set;

/** Test-only calls into frozen original evidence storage, with adversarial keys. */
public final class EvidenceOrderProbe {
    private EvidenceOrderProbe() {}

    private static final class Node {
        @Override public int hashCode() { return 0; }
    }

    private record Slot(Node container, int index) {}

    private static void require(boolean condition, String contract) {
        if (!condition) throw new AssertionError(contract);
    }

    private static void exercise(int size, boolean reverse) {
        var budget = new RejectedFreeEvidence.Budget(100_000);
        var store = new RejectedFreeEvidence(budget, 100_000, 100_000);
        SourceFile source = SourceFile.of("evidence.iron", "class Evidence {}\n");
        SourceFile otherSource = SourceFile.of("evidence.iron", "class Evidence {}\n");
        SourceSpan first = SourceSpan.at(new SourcePosition(0, 1, 1));
        SourceSpan equalFirst = SourceSpan.at(new SourcePosition(0, 1, 1));
        SourceSpan changed = SourceSpan.at(new SourcePosition(1, 1, 2));
        Node[] nodes = new Node[size];
        Slot[] slots = new Slot[size];
        for (int index = 0; index < size; index++) {
            nodes[index] = new Node();
            // After Java's high/low hash spread these indices collide in small buckets.
            slots[index] = new Slot(nodes[index], index * 65_537);
        }
        for (int step = 0; step < size; step++) {
            int index = reverse ? size - step - 1 : step;
            require(store.origin(nodes[index], source, first), "origin insertion");
            require(store.bind(nodes[index], nodes[index], source, first), "identity binding");
            require(store.arrayStore(slots[index], source, first), "slot value key");
            require(store.retain(nodes[index], nodes[(index + 1) % size], source, first), "retention pair");
            require(store.selectedReason(nodes[index], "reason", source, first), "original event");
            require(store.joined(nodes[index], new RejectedFreeEvidence.Join("state", "reason",
                    List.of(), 0, "complete", true)), "original join");
        }
        Object saved = new Object();
        require(store.save(saved) == size * 6, "complete six-map independent snapshot");
        require(store.arrayStore(slots[0], source, changed), "mutate after save");
        require(store.arrayStore(saved, slots[0]).span().equals(first), "snapshot independent of builder");
        require(store.restore(saved), "restore complete snapshot");
        require(store.arrayStore(new Slot(nodes[0], 0)).span().equals(first), "equal slot key lookup");
        require(store.binding(nodes[0]).allocation() == nodes[0], "binding retains node identity");

        // Equal-looking replacement events/joins are distinct identity evidence.
        require(store.selectedReason(nodes[0], "reason", source, equalFirst), "replace event");
        require(store.joined(nodes[0], new RejectedFreeEvidence.Join("state", "reason",
                List.of(), 0, "complete", true)), "replace equal-looking join");
        require(store.arrayStore(slots[0], source, equalFirst), "independently equal Site/span value");
        require(store.retain(nodes[0], nodes[1], source, equalFirst), "independently equal retention Site");
        require(store.bind(nodes[0], nodes[0], otherSource, equalFirst), "replace equal-looking source");
        Object alternative = new Object();
        require(store.save(alternative) == size * 6, "second independent version");
        require(store.merge(reverse ? List.of(alternative, saved) : List.of(saved, alternative)), "common intersection");
        require(store.origin(nodes[0]).source() == source, "value Site equality keeps common origin");
        require(store.binding(nodes[0]) == null, "binding source identity prevents false agreement");
        require(store.event(nodes[0]) == null, "equal event fields do not imply shared event identity");
        require(store.join(nodes[0]) == null, "equal join fields do not imply shared join identity");
        require(store.event(nodes[1]) != null && store.join(nodes[1]) != null, "same identity facts survive");
        require(store.arrayStore(slots[0]).span().equals(first), "equal span values survive intersection");
        store.retainArrayStores(Set.of(slots[0]));
        require(store.arrayStore(slots[0]) != null && store.arrayStore(slots[1]) == null,
                "remove every nonmember, preserve member");
        store.clearRetainingOwner(nodes[0]);
        require(store.retention(nodes[0], nodes[1]) == null, "remove selected owner relation");
        require(store.retention(nodes[1], nodes[2]) != null, "preserve another owner relation");
        store.close();
        require(budget.live() == 0, "close retires all shared payload and associations");
        store.close();
        require(budget.live() == 0, "close idempotence");
    }

    private static void atomicFailure() {
        var budget = new RejectedFreeEvidence.Budget(100);
        var store = new RejectedFreeEvidence(budget, 100, 1);
        SourceFile source = SourceFile.of("budget.iron", "class Budget {}\n");
        SourceSpan span = SourceSpan.at(new SourcePosition(0, 1, 1));
        Node first = new Node();
        Node second = new Node();
        require(store.origin(first, source, span) && store.origin(second, source, span), "two initial facts");
        Object saved = new Object();
        require(store.save(saved) == -1, "snapshot limit fails atomically");
        require(store.origin(first) != null && store.origin(second) != null, "failed save preserves current facts");
        require(!store.restore(saved), "failed save cannot pretend restoration succeeded");
        require(store.origin(first) == null && store.origin(second) == null, "missing restore clears current facts");
        store.close();
        require(budget.live() == 0, "failed-save cleanup");
    }

    public static void main(String[] args) {
        for (int size : new int[] {8, 32, 128}) {
            exercise(size, false);
            exercise(size, true);
        }
        atomicFailure();
        System.out.println("PASS: original evidence copies, identity/value intersections, colliding/resize keys, insertion order and atomic budget cleanup");
    }
}
