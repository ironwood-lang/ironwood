// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.UnfreedMode;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Diagnostic-only allocation obligations, independent of the safe-free proof. */
final class UnfreedAllocationTracker<A> {
    private final SourceFile source;
    private final UnfreedMode mode;
    private final Map<A, Origin> origins = new LinkedHashMap<>();
    private final Set<A> live = new LinkedHashSet<>();
    private final Map<SourceSpan, Diagnostic> findings = new LinkedHashMap<>();

    UnfreedAllocationTracker(SourceFile source, UnfreedMode mode) {
        this.source = source;
        this.mode = mode;
    }

    void register(A allocation, SourceSpan span, String description, boolean completed) {
        if (allocation == null) return;
        origins.putIfAbsent(allocation, new Origin(span, description));
        if (completed) completed(allocation);
    }

    void name(A allocation, String name) {
        Origin origin = origins.get(allocation);
        if (origin != null && !origin.description().startsWith("allocation assigned to")) {
            origins.put(allocation, new Origin(origin.span(), "allocation assigned to '" + name + "'"));
        }
    }

    void completed(A allocation) {
        if (origins.containsKey(allocation)) live.add(allocation);
    }

    void consumed(A allocation) {
        live.remove(allocation);
    }

    Set<A> snapshot() {
        return Set.copyOf(live);
    }

    void restore(Set<A> snapshot) {
        live.clear();
        live.addAll(snapshot);
    }

    void merge(List<Set<A>> incoming) {
        live.clear();
        if (incoming.isEmpty()) return;
        // A successful result must be established on every merged predecessor.
        // Failure and conflicting paths remain outside this definite diagnostic.
        live.addAll(incoming.getFirst());
        incoming.forEach(live::retainAll);
    }

    void observe(Predicate<A> abandoned, boolean scopeExit) {
        // Origin order keeps diagnostics stable across ownership snapshot copies.
        for (Map.Entry<A, Origin> entry : origins.entrySet()) {
            if (!live.contains(entry.getKey()) || !abandoned.test(entry.getKey())) continue;
            Origin origin = entry.getValue();
            String message = origin.description() + (scopeExit
                    ? " leaves scope without being freed" : " is discarded without being freed");
            findings.putIfAbsent(origin.span(), mode == UnfreedMode.ERROR
                    ? Diagnostic.error(source, origin.span(), message)
                    : Diagnostic.warning(source, origin.span(), message));
        }
    }

    List<Diagnostic> diagnostics() {
        return List.copyOf(findings.values());
    }

    private record Origin(SourceSpan span, String description) {}
}
