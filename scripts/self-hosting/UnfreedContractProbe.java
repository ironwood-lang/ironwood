// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.UnfreedMode;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Original tracker membership copies cannot change ordered diagnostic selection. */
public final class UnfreedContractProbe {
    private static int checks;
    private UnfreedContractProbe() {}
    private static final class Node {
        @Override public int hashCode() { return 0; }
    }
    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("unfreed tracker contract");
    }
    private static SourceSpan span(int index) {
        return SourceSpan.at(new SourcePosition(index, 1, index + 1));
    }
    private static void copies(int size, boolean reverse, UnfreedMode mode) {
        var tracker = new UnfreedAllocationTracker<Node>(SourceFile.of("tracker.iron", "class Tracker {}\n"), mode);
        List<Node> ordered = new ArrayList<>();
        for (int i = 0; i < size; i++) ordered.add(new Node());
        for (int step = 0; step < size; step++) {
            int i = reverse ? size - step - 1 : step;
            tracker.register(ordered.get(i), span(i), "allocation", true);
        }
        Set<Node> saved = tracker.snapshot();
        tracker.consumed(ordered.get(0));
        require(saved.size() == size && saved.contains(ordered.get(0)));
        try { saved.clear(); throw new AssertionError("mutable tracker snapshot"); }
        catch (UnsupportedOperationException expected) { require(saved.size() == size); }
        Set<Node> incoming = new LinkedHashSet<>();
        for (int i = size - 1; i >= 1; i--) incoming.add(ordered.get(i));
        tracker.merge(reverse ? List.of(incoming, saved) : List.of(saved, incoming));
        require(tracker.snapshot().size() == size - 1 && !tracker.snapshot().contains(ordered.get(0)));
        List<Node> visited = new ArrayList<>();
        tracker.observe(node -> { visited.add(node); return true; }, true);
        List<Node> expected = new ArrayList<>();
        for (int step = 0; step < size; step++) {
            int i = reverse ? size - step - 1 : step;
            if (i != 0) expected.add(ordered.get(i));
        }
        require(visited.equals(expected));
        var diagnostics = tracker.diagnostics();
        require(diagnostics.size() == size - 1);
        for (int i = 0; i < diagnostics.size(); i++) {
            require(diagnostics.get(i).span().equals(span(ordered.indexOf(expected.get(i)))));
            require(diagnostics.get(i).isError() == (mode == UnfreedMode.ERROR));
        }
        tracker.restore(saved);
        require(tracker.snapshot().size() == size);
        tracker.merge(List.of());
        require(tracker.snapshot().isEmpty());
        tracker.restore(saved);
        tracker.suppress(ordered.get(1));
        int before = visited.size();
        tracker.observe(node -> { visited.add(node); return false; }, false);
        require(visited.size() == before + size - 1);
        require(tracker.diagnostics().size() == size - 2);
    }
    private static void selection() {
        SourceFile source = SourceFile.of("selection.iron", "class Selection {}\n");
        var tracker = new UnfreedAllocationTracker<String>(source, UnfreedMode.WARN);
        tracker.register(new String("Aa"), span(0), "first", false);
        tracker.register(new String("Aa"), span(1), "replacement", true);
        tracker.register("BB", span(0), "second", true);
        tracker.name("Aa", "firstName");
        tracker.name("Aa", "laterName");
        tracker.abandoned("Aa", "first reason");
        tracker.observe(node -> true, true);
        var first = tracker.diagnostics();
        require(first.size() == 1 && first.getFirst().span().equals(span(0)));
        require(first.getFirst().message().contains("firstName") && !first.getFirst().message().contains("laterName"));
        require(first.getFirst().notes().getFirst().message().endsWith("first reason"));
        // Suppression precedes equal-span deduplication, exposing the next finding.
        tracker.suppress(new String("Aa"));
        var second = tracker.diagnostics();
        require(second.size() == 1 && second.getFirst().message().startsWith("second"));
        require(first.getFirst().notes().size() == 1 && second.getFirst().notes().isEmpty());
        tracker.register(null, span(2), "ignored", true);
        tracker.completed(null);
        require(tracker.snapshot().size() == 1 && tracker.snapshot().contains("BB"));
    }
    public static void main(String[] args) {
        for (int size : new int[]{8, 32, 128}) for (boolean reverse : new boolean[]{false, true}) {
            for (UnfreedMode mode : UnfreedMode.values()) copies(size, reverse, mode);
        }
        selection();
        System.out.println("PASS: " + checks + " original unfreed tracker checks");
    }
}
