// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional analyzer-owned derivations, separate from semantic summary values. */
final class SummaryWitnessEvidence {
    static final int FACT_LIMIT = 64;
    static final int METHOD_LIMIT = 2_048;

    enum Effect {
        RAW_ESCAPE, RECEIVER_RETENTION, NON_RETURN_ESCAPE,
        RETURN_ALIAS, BORROWED_RETURN, FRESH_RETURN, FRESH_PUBLICATION
    }

    record Fact(Effect effect, int role, String detail) {
        Fact {
            if (effect == null) throw new IllegalArgumentException("effect is required");
            if (role < -1) throw new IllegalArgumentException("invalid operand role");
        }
    }

    static final class Witness {
        private final String method;
        private final Fact fact;
        private final SourceFile source;
        private final SourceSpan span;
        private final String reason;
        private final Witness dependency;
        private final long ordinal;
        private final int units;
        private final int chainUnits;

        private Witness(String method, Fact fact, SourceFile source, SourceSpan span,
                        String reason, Witness dependency, long ordinal) {
            this.method = method;
            this.fact = fact;
            this.source = source;
            this.span = span;
            this.reason = reason;
            this.dependency = dependency;
            this.ordinal = ordinal;
            units = 3 + (dependency == null ? 0 : 1);
            chainUnits = units + (dependency == null ? 0 : dependency.chainUnits);
        }

        String method() { return method; }
        Fact fact() { return fact; }
        SourceFile source() { return source; }
        SourceSpan span() { return span; }
        String reason() { return reason; }
        Witness dependency() { return dependency; }
        long ordinal() { return ordinal; }
        int chainUnits() { return chainUnits; }
    }

    private static final class Method {
        private final Map<Fact, Witness> roots = new LinkedHashMap<>();
        private int live;
        private boolean truncated;
    }

    private final RejectedFreeEvidence.Budget invocation;
    private final Map<String, Method> methods = new LinkedHashMap<>();
    private final IdentityHashMap<Witness, Integer> references = new IdentityHashMap<>();
    private long nextOrdinal;
    private boolean closed;

    SummaryWitnessEvidence(RejectedFreeEvidence.Budget invocation) {
        this.invocation = invocation;
    }

    Witness first(String method, Fact fact, SourceFile source, SourceSpan span,
                  String reason, Witness dependency) {
        if (closed) throw new IllegalStateException("summary evidence is retired");
        Method state = methods.get(method);
        Witness existing = state == null ? null : state.roots.get(fact);
        if (existing != null) return existing;
        if (invocation.stopped()) return null;
        if (dependency != null && !references.containsKey(dependency)) {
            throw new IllegalArgumentException("dependency belongs to a retired witness");
        }
        boolean newMethod = state == null;
        if (newMethod) {
            if (!invocation.reserve(1)) return null;
            state = new Method();
            state.live = 1;
            methods.put(method, state);
        }
        int nodeUnits = 3 + (dependency == null ? 0 : 1);
        if (nodeUnits + 1 + (dependency == null ? 0 : dependency.chainUnits) > FACT_LIMIT
                || state.live + nodeUnits + 1 > METHOD_LIMIT
                || !invocation.reserve(nodeUnits + 1)) {
            state.truncated = true;
            if (newMethod && invocation.stopped()) {
                methods.remove(method);
                invocation.release(1);
            }
            return null;
        }
        Witness witness = new Witness(method, fact, source, span, reason,
                dependency, ++nextOrdinal);
        state.roots.put(fact, witness);
        state.live += nodeUnits + 1;
        references.put(witness, 1);
        if (dependency != null) {
            references.merge(dependency, 1, Integer::sum);
        }
        return witness;
    }

    Witness get(String method, Fact fact) {
        Method state = methods.get(method);
        return state == null ? null : state.roots.get(fact);
    }

    List<Fact> facts(String method, Effect effect) {
        Method state = methods.get(method);
        return state == null ? List.of() : state.roots.keySet().stream()
                .filter(fact -> fact.effect() == effect).toList();
    }

    List<Fact> facts(String method) {
        Method state = methods.get(method);
        return state == null ? List.of() : List.copyOf(state.roots.keySet());
    }

    void remove(String method, Fact fact) {
        Method state = methods.get(method);
        if (state == null) return;
        Witness root = state.roots.remove(fact);
        if (root == null) return;
        state.live--;
        invocation.release(1);
        release(root);
    }

    private void release(Witness witness) {
        int remaining = references.get(witness) - 1;
        if (remaining != 0) {
            references.put(witness, remaining);
            return;
        }
        references.remove(witness);
        int units = witness.units;
        methods.get(witness.method).live -= units;
        invocation.release(units);
        if (witness.dependency != null) release(witness.dependency);
    }

    void close() {
        if (closed) return;
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            for (Fact fact : new ArrayList<>(entry.getValue().roots.keySet())) {
                remove(entry.getKey(), fact);
            }
            invocation.release(1);
        }
        methods.clear();
        closed = true;
    }

    int methodLive(String method) {
        Method state = methods.get(method);
        return state == null ? 0 : state.live;
    }

    boolean methodTruncated(String method) {
        Method state = methods.get(method);
        return state != null && state.truncated;
    }

    boolean invocationStopped() { return invocation.stopped(); }

    Map<String, String> observerProjection() {
        Map<String, String> result = new java.util.TreeMap<>();
        methods.forEach((method, state) -> state.roots.forEach((fact, witness) -> {
            String key = method + "/" + fact.effect() + "/" + fact.role()
                    + "/" + fact.detail();
            String site = witness.source == null || witness.span == null ? "unlocated"
                    : witness.source.path() + ":" + witness.span.start().line()
                    + ":" + witness.span.start().column();
            result.put(key, site + " " + witness.reason
                    + (witness.dependency == null ? "" : " -> " + witness.dependency.method));
        }));
        return Map.copyOf(result);
    }
}
