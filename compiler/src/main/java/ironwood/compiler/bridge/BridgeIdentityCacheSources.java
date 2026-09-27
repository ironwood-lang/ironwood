// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.List;
import java.util.Map;

/** Weak facade identity for the confined permanent world; it grants no native lifetime permission. */
public final class BridgeIdentityCacheSources {
    private BridgeIdentityCacheSources() {}

    public record Sources(Map<String, String> sources, List<String> types) {
        public Sources { sources = Map.copyOf(sources); types = List.copyOf(types); }
    }

    public static Sources generate(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission)) {
            throw new IllegalArgumentException("permanent identity cache requires matching final object admission and generation");
        }
        if (admission.surface().types().stream().noneMatch(type -> type.kind() == BridgeApiFacts.Kind.CLASS && !type.throwable()
                && admission.lifetime().references().containsKey(IrType.reference(type.binaryName())))) {
            throw new IllegalArgumentException("permanent identity cache requires a proved permanent concrete facade");
        }
        String name = generation.supportPackage() + ".PermanentCache";
        String source = TEMPLATE.replace("@PACKAGE@", generation.supportPackage()).replace("@GENERATION@", generation.identity());
        return new Sources(Map.of(name.replace('.', '/') + ".java", source), List.of(name, name + "$Entry"));
    }

    private static final String TEMPLATE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            import java.lang.ref.ReferenceQueue;
            import java.lang.ref.WeakReference;

            @Identity("@GENERATION@")
            final class PermanentCache {
                private static final ReferenceQueue<Object> collected = new ReferenceQueue<>();
                private static Entry[] buckets = new Entry[16];
                private static int size;

                private PermanentCache() {}

                @Identity("@GENERATION@")
                private static final class Entry extends WeakReference<Object> {
                    final long address;
                    Entry next;

                    Entry(long address, Object facade) {
                        super(facade, collected);
                        this.address = address;
                    }
                }

                static Object lookup(long address) {
                    drain();
                    for (Entry entry = buckets[index(address, buckets.length)]; entry != null; entry = entry.next) {
                        if (entry.address != address) continue;
                        Object facade = entry.get();
                        if (facade == null) remove(entry);
                        return facade;
                    }
                    return null;
                }

                // Called only after the facade's immutable metadata is initialized.
                // No caller may publish the candidate before this returns successfully.
                static Object remember(long address, Object facade) {
                    Object existing = lookup(address);
                    if (existing != null) return existing;
                    Entry entry = new Entry(address, facade);
                    if (size >= buckets.length - buckets.length / 4) grow();
                    int bucket = index(address, buckets.length);
                    entry.next = buckets[bucket];
                    buckets[bucket] = entry;
                    size++;
                    return facade;
                }

                private static void drain() {
                    Entry entry;
                    while ((entry = (Entry) collected.poll()) != null) remove(entry);
                }

                private static void remove(Entry removed) {
                    int bucket = index(removed.address, buckets.length);
                    Entry previous = null;
                    for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
                        if (entry == removed) {
                            if (previous == null) buckets[bucket] = entry.next;
                            else previous.next = entry.next;
                            entry.next = null;
                            size--;
                            return;
                        }
                        previous = entry;
                    }
                }

                private static void grow() {
                    if (buckets.length >= 1 << 29) throw new OutOfMemoryError("Ironwood facade identity cache capacity");
                    // Rehash only after allocation succeeds. Keep the mutation loop
                    // free of method calls, including calls that could exhaust stack.
                    Entry[] expanded = new Entry[buckets.length * 2];
                    for (Entry first : buckets) {
                        Entry entry = first;
                        while (entry != null) {
                            Entry next = entry.next;
                            long mixed = entry.address ^ (entry.address >>> 33);
                            mixed *= 0xff51afd7ed558ccdl;
                            mixed ^= mixed >>> 33;
                            int bucket = (int) mixed & (expanded.length - 1);
                            entry.next = expanded[bucket];
                            expanded[bucket] = entry;
                            entry = next;
                        }
                    }
                    buckets = expanded;
                }

                private static int index(long address, int capacity) {
                    long mixed = address ^ (address >>> 33);
                    mixed *= 0xff51afd7ed558ccdl;
                    mixed ^= mixed >>> 33;
                    return (int) mixed & (capacity - 1);
                }
            }
            """;
}
