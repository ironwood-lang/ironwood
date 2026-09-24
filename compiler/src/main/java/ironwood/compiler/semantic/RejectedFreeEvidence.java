// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Optional function-local diagnostic facts, separate from ownership proof snapshots. */
final class RejectedFreeEvidence {
    static final int DEFAULT_LOCAL_LIMIT = 4_096;
    static final int DEFAULT_SNAPSHOT_LIMIT = 2_048;
    static final int DEFAULT_INVOCATION_LIMIT = 1_048_576;

    /** Optional package-private test override; production construction passes null. */
    record Limits(int local, int snapshots, int invocation) {
        Limits {
            if (local < 1 || snapshots < 1 || invocation < 1) {
                throw new IllegalArgumentException("positive evidence limits required");
            }
        }
    }

    record Site(SourceFile source, SourceSpan span) {
    }

    record Retention(Object owner, Object child) {
    }

    record Binding(Object allocation, SourceFile source, SourceSpan span) {
    }

    enum EventKind { REASON, FREE }

    record Event(EventKind kind, String reason, SourceFile source, SourceSpan span) {
    }

    record JoinAlternative(String label, SourceFile anchorSource, SourceSpan anchorSpan,
                           String state, String reason, Event event) {
    }

    record Join(String state, String reason, java.util.List<JoinAlternative> alternatives,
                int omitted, String classification, boolean complete) {
        Join {
            alternatives = java.util.List.copyOf(alternatives);
        }
    }

    static final class Budget {
        private final int limit;
        private int live;
        private int highWater;
        private boolean stopped;

        Budget(int limit) {
            if (limit < 1) throw new IllegalArgumentException("positive evidence limit required");
            this.limit = limit;
        }

        boolean reserve(int units) {
            if (stopped || units > limit - live) {
                stopped = true;
                return false;
            }
            live += units;
            highWater = Math.max(highWater, live);
            return true;
        }

        void release(int units) {
            live -= units;
        }

        int highWater() { return highWater; }
        int live() { return live; }
        boolean stopped() { return stopped; }
    }

    private static final class SnapshotKey extends WeakReference<Object> {
        private final int identityHash;

        SnapshotKey(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof SnapshotKey key
                    && get() != null && get() == key.get();
        }
    }

    private record Saved(Map<Object, Site> origins, Map<Object, Binding> bindings,
                         Map<Object, Event> events, Map<Object, Site> arrayStores,
                         Map<Retention, Site> retentions, Map<Object, Join> joins,
                         int units, int associations) {
    }

    private final Budget invocation;
    private final int localLimit;
    private final int snapshotLimit;
    private final IdentityHashMap<Object, Site> origins = new IdentityHashMap<>();
    private final IdentityHashMap<Site, Integer> siteReferences = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Binding> bindings = new IdentityHashMap<>();
    private final IdentityHashMap<Binding, Integer> bindingReferences = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Event> events = new IdentityHashMap<>();
    private final IdentityHashMap<Event, Integer> eventReferences = new IdentityHashMap<>();
    private final Map<Object, Site> arrayStores = new HashMap<>();
    private final Map<Retention, Site> retentions = new HashMap<>();
    private final IdentityHashMap<Object, Join> joins = new IdentityHashMap<>();
    private final IdentityHashMap<Join, Integer> joinReferences = new IdentityHashMap<>();
    private final ReferenceQueue<Object> retired = new ReferenceQueue<>();
    private final Map<SnapshotKey, Saved> snapshots = new java.util.HashMap<>();
    private int liveUnits;
    private int highWater;
    private int snapshotUnits;
    private int snapshotHighWater;
    private boolean functionTruncated;
    private boolean snapshotTruncated;
    private boolean closed;

    RejectedFreeEvidence(Budget invocation, int localLimit, int snapshotLimit) {
        this.invocation = invocation;
        this.localLimit = localLimit;
        this.snapshotLimit = snapshotLimit;
    }

    boolean reserveTransient(int units) {
        return reserve(units, false, 0);
    }

    void releaseTransient(int units) {
        liveUnits -= units;
        invocation.release(units);
    }

    boolean origin(Object allocation, SourceFile source, SourceSpan span) {
        if (origins.containsKey(allocation) || !reserve(2, false, 0)) return false;
        Site site = new Site(source, span);
        origins.put(allocation, site);
        siteReferences.put(site, 1);
        return true;
    }

    Site origin(Object allocation) {
        return origins.get(allocation);
    }

    boolean bind(Object local, Object allocation, SourceFile source, SourceSpan span) {
        unbind(local);
        if (allocation == null) return true;
        if (!reserve(2, false, 0)) return false;
        Binding binding = new Binding(allocation, source, span);
        bindings.put(local, binding);
        bindingReferences.put(binding, 1);
        return true;
    }

    void unbind(Object local) {
        Binding old = bindings.remove(local);
        if (old != null) {
            liveUnits--;
            invocation.release(1);
            releaseBinding(old);
        }
    }

    Binding binding(Object local) {
        return bindings.get(local);
    }

    Binding binding(Object proofSnapshot, Object local) {
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        return saved == null ? null : saved.bindings().get(local);
    }

    boolean selectedReason(Object allocation, String reason) {
        return replaceEvent(allocation, EventKind.REASON, reason, null, null);
    }

    boolean selectedReason(Object allocation, String reason, SourceFile source, SourceSpan span) {
        return replaceEvent(allocation, EventKind.REASON, reason, source, span);
    }

    boolean reclaimed(Object allocation, SourceFile source, SourceSpan span) {
        return replaceEvent(allocation, EventKind.FREE, null, source, span);
    }

    Event event(Object allocation) {
        return events.get(allocation);
    }

    Event event(Object proofSnapshot, Object allocation) {
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        return saved == null ? null : saved.events().get(allocation);
    }

    Join join(Object allocation) {
        return joins.get(allocation);
    }

    Join join(Object proofSnapshot, Object allocation) {
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        return saved == null ? null : saved.joins().get(allocation);
    }

    boolean joined(Object allocation, Join join) {
        clearJoin(allocation);
        int units = 2 + join.alternatives().size();
        if (!reserve(units, false, 0)) return false;
        joins.put(allocation, join);
        joinReferences.put(join, 1);
        return true;
    }

    private void clearJoin(Object allocation) {
        Join old = joins.remove(allocation);
        if (old == null) return;
        liveUnits--;
        invocation.release(1);
        releaseJoin(old);
    }

    boolean arrayStore(Object slot, SourceFile source, SourceSpan span) {
        clearArrayStore(slot);
        if (!reserve(2, false, 0)) return false;
        Site site = new Site(source, span);
        arrayStores.put(slot, site);
        siteReferences.put(site, 1);
        return true;
    }

    void clearArrayStore(Object slot) {
        Site old = arrayStores.remove(slot);
        if (old != null) {
            liveUnits--;
            invocation.release(1);
            releaseSite(old);
        }
    }

    void retainArrayStores(Set<?> liveSlots) {
        var stores = arrayStores.entrySet().iterator();
        while (stores.hasNext()) {
            Map.Entry<Object, Site> entry = stores.next();
            if (liveSlots.contains(entry.getKey())) continue;
            Site old = entry.getValue();
            stores.remove();
            liveUnits--;
            invocation.release(1);
            releaseSite(old);
        }
    }

    Site arrayStore(Object proofSnapshot, Object slot) {
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        return saved == null ? null : saved.arrayStores().get(slot);
    }

    Site arrayStore(Object slot) {
        return arrayStores.get(slot);
    }

    boolean retain(Object owner, Object child, SourceFile source, SourceSpan span) {
        Retention key = new Retention(owner, child);
        clearRetention(key);
        if (source == null || span == null || !reserve(2, false, 0)) return false;
        Site site = new Site(source, span);
        retentions.put(key, site);
        siteReferences.put(site, 1);
        return true;
    }

    Site retention(Object owner, Object child) {
        return retentions.get(new Retention(owner, child));
    }

    void clearRetainingOwner(Object owner) {
        var entries = retentions.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Retention, Site> entry = entries.next();
            if (entry.getKey().owner() != owner) continue;
            Site site = entry.getValue();
            entries.remove();
            liveUnits--;
            invocation.release(1);
            releaseSite(site);
        }
    }

    private void clearRetention(Retention key) {
        Site old = retentions.remove(key);
        if (old == null) return;
        liveUnits--;
        invocation.release(1);
        releaseSite(old);
    }

    private boolean replaceEvent(Object allocation, EventKind kind, String reason,
                                 SourceFile source, SourceSpan span) {
        clearJoin(allocation);
        Event old = events.remove(allocation);
        if (old != null) {
            liveUnits--;
            invocation.release(1);
            releaseEvent(old);
        }
        if (!reserve(2, false, 0)) return false;
        Event event = new Event(kind, reason, source, span);
        events.put(allocation, event);
        eventReferences.put(event, 1);
        return true;
    }

    int save(Object proofSnapshot) {
        retireCollected();
        int associations = origins.size() + bindings.size() + events.size()
                + arrayStores.size() + retentions.size() + joins.size();
        int units = 2 + associations;
        if (!reserve(units, true, associations)) return -1;
        snapshots.put(new SnapshotKey(proofSnapshot, retired),
                new Saved(Map.copyOf(origins), Map.copyOf(bindings), Map.copyOf(events),
                        Map.copyOf(arrayStores), Map.copyOf(retentions), Map.copyOf(joins),
                        units, associations));
        origins.values().forEach(this::retainSite);
        bindings.values().forEach(this::retainBinding);
        events.values().forEach(this::retainEvent);
        arrayStores.values().forEach(this::retainSite);
        retentions.values().forEach(this::retainSite);
        joins.values().forEach(this::retainJoin);
        return associations;
    }

    boolean restore(Object proofSnapshot) {
        retireCollected();
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        clearCurrent();
        if (saved == null) {
            snapshotTruncated = true;
            return false;
        }
        return copyIntoCurrent(saved.origins(), saved.bindings(), saved.events(),
                saved.arrayStores(), saved.retentions(), saved.joins());
    }

    boolean merge(Iterable<?> incoming) {
        retireCollected();
        IdentityHashMap<Object, Site> common = null;
        IdentityHashMap<Object, Binding> commonBindings = null;
        IdentityHashMap<Object, Event> commonEvents = null;
        Map<Object, Site> commonArrayStores = null;
        Map<Retention, Site> commonRetentions = null;
        IdentityHashMap<Object, Join> commonJoins = null;
        for (Object snapshot : incoming) {
            Saved saved = snapshots.get(new SnapshotKey(snapshot, null));
            if (saved == null) {
                clearCurrent();
                snapshotTruncated = true;
                return false;
            }
            if (common == null) {
                common = new IdentityHashMap<>(saved.origins());
                commonBindings = new IdentityHashMap<>(saved.bindings());
                commonEvents = new IdentityHashMap<>(saved.events());
                commonArrayStores = new HashMap<>(saved.arrayStores());
                commonRetentions = new HashMap<>(saved.retentions());
                commonJoins = new IdentityHashMap<>(saved.joins());
            } else {
                Map<Object, Site> selected = saved.origins();
                common.entrySet().removeIf(entry -> !entry.getValue().equals(selected.get(entry.getKey())));
                Map<Object, Binding> selectedBindings = saved.bindings();
                commonBindings.entrySet().removeIf(entry -> {
                    Binding next = selectedBindings.get(entry.getKey());
                    return next == null || entry.getValue().allocation() != next.allocation()
                            || entry.getValue().source() != next.source()
                            || !entry.getValue().span().equals(next.span());
                });
                Map<Object, Event> selectedEvents = saved.events();
                commonEvents.entrySet().removeIf(entry ->
                        entry.getValue() != selectedEvents.get(entry.getKey()));
                Map<Object, Site> selectedStores = saved.arrayStores();
                commonArrayStores.entrySet().removeIf(entry ->
                        !entry.getValue().equals(selectedStores.get(entry.getKey())));
                Map<Retention, Site> selectedRetentions = saved.retentions();
                commonRetentions.entrySet().removeIf(entry ->
                        !entry.getValue().equals(selectedRetentions.get(entry.getKey())));
                Map<Object, Join> selectedJoins = saved.joins();
                commonJoins.entrySet().removeIf(entry ->
                        entry.getValue() != selectedJoins.get(entry.getKey()));
            }
        }
        if (common != null) {
            clearCurrent();
            return copyIntoCurrent(common, commonBindings, commonEvents, commonArrayStores,
                    commonRetentions, commonJoins);
        }
        return true;
    }

    private boolean copyIntoCurrent(Map<Object, Site> selected,
                                    Map<Object, Binding> selectedBindings,
                                    Map<Object, Event> selectedEvents,
                                    Map<Object, Site> selectedStores,
                                    Map<Retention, Site> selectedRetentions,
                                    Map<Object, Join> selectedJoins) {
        if (!reserve(selected.size() + selectedBindings.size() + selectedEvents.size()
                + selectedStores.size() + selectedRetentions.size() + selectedJoins.size(),
                false, 0)) return false;
        origins.putAll(selected);
        bindings.putAll(selectedBindings);
        events.putAll(selectedEvents);
        arrayStores.putAll(selectedStores);
        retentions.putAll(selectedRetentions);
        joins.putAll(selectedJoins);
        selected.values().forEach(this::retainSite);
        selectedBindings.values().forEach(this::retainBinding);
        selectedEvents.values().forEach(this::retainEvent);
        selectedStores.values().forEach(this::retainSite);
        selectedRetentions.values().forEach(this::retainSite);
        selectedJoins.values().forEach(this::retainJoin);
        return true;
    }

    private void clearCurrent() {
        int associations = origins.size() + bindings.size() + events.size()
                + arrayStores.size() + retentions.size() + joins.size();
        for (Site site : origins.values()) releaseSite(site);
        for (Binding binding : bindings.values()) releaseBinding(binding);
        for (Event event : events.values()) releaseEvent(event);
        for (Site site : arrayStores.values()) releaseSite(site);
        for (Site site : retentions.values()) releaseSite(site);
        for (Join join : joins.values()) releaseJoin(join);
        origins.clear();
        bindings.clear();
        events.clear();
        arrayStores.clear();
        retentions.clear();
        joins.clear();
        liveUnits -= associations;
        invocation.release(associations);
    }

    private void retainSite(Site site) {
        siteReferences.merge(site, 1, Integer::sum);
    }

    private void releaseSite(Site site) {
        int remaining = siteReferences.get(site) - 1;
        if (remaining == 0) {
            siteReferences.remove(site);
            liveUnits--;
            invocation.release(1);
        } else {
            siteReferences.put(site, remaining);
        }
    }

    private void retainBinding(Binding binding) {
        bindingReferences.merge(binding, 1, Integer::sum);
    }

    private void releaseBinding(Binding binding) {
        int remaining = bindingReferences.get(binding) - 1;
        if (remaining == 0) {
            bindingReferences.remove(binding);
            liveUnits--;
            invocation.release(1);
        } else {
            bindingReferences.put(binding, remaining);
        }
    }

    private void retainEvent(Event event) {
        eventReferences.merge(event, 1, Integer::sum);
    }

    private void releaseEvent(Event event) {
        int remaining = eventReferences.get(event) - 1;
        if (remaining == 0) {
            eventReferences.remove(event);
            liveUnits--;
            invocation.release(1);
        } else {
            eventReferences.put(event, remaining);
        }
    }

    private void retainJoin(Join join) {
        joinReferences.merge(join, 1, Integer::sum);
    }

    private void releaseJoin(Join join) {
        int remaining = joinReferences.get(join) - 1;
        if (remaining == 0) {
            joinReferences.remove(join);
            int units = 1 + join.alternatives().size();
            liveUnits -= units;
            invocation.release(units);
        } else {
            joinReferences.put(join, remaining);
        }
    }

    private boolean reserve(int units, boolean snapshot, int associations) {
        if (closed || functionTruncated || snapshot && snapshotTruncated) return false;
        if (units > localLimit - liveUnits) {
            functionTruncated = true;
            return false;
        }
        if (snapshot && associations > snapshotLimit - snapshotUnits) {
            snapshotTruncated = true;
            return false;
        }
        if (!invocation.reserve(units)) return false;
        liveUnits += units;
        highWater = Math.max(highWater, liveUnits);
        if (snapshot) {
            snapshotUnits += associations;
            snapshotHighWater = Math.max(snapshotHighWater, snapshotUnits);
        }
        return true;
    }

    private void retireCollected() {
        SnapshotKey key;
        while ((key = (SnapshotKey) retired.poll()) != null) {
            Saved saved = snapshots.remove(key);
            if (saved != null) {
                liveUnits -= saved.units();
                snapshotUnits -= saved.associations();
                invocation.release(saved.units());
                saved.origins().values().forEach(this::releaseSite);
                saved.bindings().values().forEach(this::releaseBinding);
                saved.events().values().forEach(this::releaseEvent);
                saved.arrayStores().values().forEach(this::releaseSite);
                saved.retentions().values().forEach(this::releaseSite);
                saved.joins().values().forEach(this::releaseJoin);
            }
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        snapshots.clear();
        origins.clear();
        bindings.clear();
        events.clear();
        arrayStores.clear();
        retentions.clear();
        joins.clear();
        siteReferences.clear();
        bindingReferences.clear();
        eventReferences.clear();
        joinReferences.clear();
        invocation.release(liveUnits);
        liveUnits = 0;
        snapshotUnits = 0;
    }

    int highWater() { return highWater; }
    int snapshotHighWater() { return snapshotHighWater; }
    boolean localTruncated() { return functionTruncated || snapshotTruncated; }
    boolean invocationStopped() { return invocation.stopped(); }
    int invocationHighWater() { return invocation.highWater(); }
}
