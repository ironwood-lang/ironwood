// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;

/** Optional function-local diagnostic facts, separate from ownership proof snapshots. */
final class RejectedFreeEvidence {
    static final int DEFAULT_LOCAL_LIMIT = 4_096;
    static final int DEFAULT_SNAPSHOT_LIMIT = 2_048;
    static final int DEFAULT_INVOCATION_LIMIT = 1_048_576;

    record Site(SourceFile source, SourceSpan span) {
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

    private record Saved(Map<Object, Site> origins, int units, int associations) {
    }

    private final Budget invocation;
    private final int localLimit;
    private final int snapshotLimit;
    private final IdentityHashMap<Object, Site> origins = new IdentityHashMap<>();
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
        origins.put(allocation, new Site(source, span));
        return true;
    }

    Site origin(Object allocation) {
        return origins.get(allocation);
    }

    int save(Object proofSnapshot) {
        retireCollected();
        int units = 2 + origins.size();
        if (!reserve(units, true, origins.size())) return -1;
        snapshots.put(new SnapshotKey(proofSnapshot, retired),
                new Saved(Map.copyOf(origins), units, origins.size()));
        return origins.size();
    }

    boolean restore(Object proofSnapshot) {
        retireCollected();
        Saved saved = snapshots.get(new SnapshotKey(proofSnapshot, null));
        origins.clear();
        if (saved == null) {
            snapshotTruncated = true;
            return false;
        }
        origins.putAll(saved.origins());
        return true;
    }

    boolean merge(Iterable<?> incoming) {
        retireCollected();
        IdentityHashMap<Object, Site> common = null;
        for (Object snapshot : incoming) {
            Saved saved = snapshots.get(new SnapshotKey(snapshot, null));
            if (saved == null) {
                origins.clear();
                snapshotTruncated = true;
                return false;
            }
            if (common == null) {
                common = new IdentityHashMap<>(saved.origins());
            } else {
                Map<Object, Site> selected = saved.origins();
                common.entrySet().removeIf(entry -> !entry.getValue().equals(selected.get(entry.getKey())));
            }
        }
        if (common != null) {
            origins.clear();
            origins.putAll(common);
        }
        return true;
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
            }
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        snapshots.clear();
        origins.clear();
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
