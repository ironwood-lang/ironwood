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

    record Binding(Object allocation, SourceFile source, SourceSpan span) {
    }

    enum EventKind { REASON, FREE }

    record Event(EventKind kind, String reason, SourceFile source, SourceSpan span) {
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

    private boolean replaceEvent(Object allocation, EventKind kind, String reason,
                                 SourceFile source, SourceSpan span) {
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
        int associations = origins.size() + bindings.size() + events.size() + arrayStores.size();
        int units = 2 + associations;
        if (!reserve(units, true, associations)) return -1;
        snapshots.put(new SnapshotKey(proofSnapshot, retired),
                new Saved(Map.copyOf(origins), Map.copyOf(bindings), Map.copyOf(events),
                        Map.copyOf(arrayStores),
                        units, associations));
        origins.values().forEach(this::retainSite);
        bindings.values().forEach(this::retainBinding);
        events.values().forEach(this::retainEvent);
        arrayStores.values().forEach(this::retainSite);
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
        return copyIntoCurrent(saved.origins(), saved.bindings(), saved.events(), saved.arrayStores());
    }

    boolean merge(Iterable<?> incoming) {
        retireCollected();
        IdentityHashMap<Object, Site> common = null;
        IdentityHashMap<Object, Binding> commonBindings = null;
        IdentityHashMap<Object, Event> commonEvents = null;
        Map<Object, Site> commonArrayStores = null;
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
            }
        }
        if (common != null) {
            clearCurrent();
            return copyIntoCurrent(common, commonBindings, commonEvents, commonArrayStores);
        }
        return true;
    }

    private boolean copyIntoCurrent(Map<Object, Site> selected,
                                    Map<Object, Binding> selectedBindings,
                                    Map<Object, Event> selectedEvents,
                                    Map<Object, Site> selectedStores) {
        if (!reserve(selected.size() + selectedBindings.size() + selectedEvents.size()
                + selectedStores.size(),
                false, 0)) return false;
        origins.putAll(selected);
        bindings.putAll(selectedBindings);
        events.putAll(selectedEvents);
        arrayStores.putAll(selectedStores);
        selected.values().forEach(this::retainSite);
        selectedBindings.values().forEach(this::retainBinding);
        selectedEvents.values().forEach(this::retainEvent);
        selectedStores.values().forEach(this::retainSite);
        return true;
    }

    private void clearCurrent() {
        int associations = origins.size() + bindings.size() + events.size() + arrayStores.size();
        for (Site site : origins.values()) releaseSite(site);
        for (Binding binding : bindings.values()) releaseBinding(binding);
        for (Event event : events.values()) releaseEvent(event);
        for (Site site : arrayStores.values()) releaseSite(site);
        origins.clear();
        bindings.clear();
        events.clear();
        arrayStores.clear();
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
        siteReferences.clear();
        bindingReferences.clear();
        eventReferences.clear();
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
