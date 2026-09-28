// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared Java state only; registration, retention commit and destruction remain native adapter duties. */
public final class BridgeRootStateSources {
    private BridgeRootStateSources() {}

    public record Sources(Map<String, String> sources, List<String> types, int slotCapacity) {
        public Sources { sources = Map.copyOf(sources); types = List.copyOf(types); }
    }

    public static Sources generate(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission) || admission.roots().isEmpty()) {
            throw new IllegalArgumentException("root state requires matching final root admission and generation");
        }
        var protocol = admission.roots().orElseThrow().protocol();
        int capacity = protocol.rootSlots().values().stream().mapToInt(List::size).max().orElse(0);
        boolean callbacks = ironwood.compiler.semantic.BridgeCallbackReachability.analyze(admission.program())
                .functions().values().stream().anyMatch(ironwood.compiler.semantic.BridgeCallbackReachability.Effects::foreign);
        var cache = BridgeIdentityCacheSources.generateRoots(artifact, admission, generation);
        return sources(generation, cache, capacity, state(capacity, callbacks));
    }

    public static Sources generateOwnedCallbacks(ironwood.compiler.BridgeOwnedCallbackAdmission admission, BridgeGeneration generation) {
        return generateOwnedCallbacks(admission, generation, BridgeCallbackBatching.prove(admission));
    }

    static Sources generateOwnedCallbacks(ironwood.compiler.BridgeOwnedCallbackAdmission admission, BridgeGeneration generation,
            BridgeCallbackBatching batching) {
        if (!generation.matchesOwnedCallbacks(admission)
                || admission.lifetime().protocol().rootSlots().values().stream().anyMatch(slots -> !slots.isEmpty())) {
            throw new IllegalArgumentException("callback root state requires matching storage without independent-root slots");
        }
        var cache = BridgeIdentityCacheSources.generateOwnedCallbacks(admission, generation);
        String state = state(0, true).replace("    private RootCache cache;", """
                    // Set once by native root publication. Liveness guards protect
                    // access after native record destruction; identity methods do not use it.
                    private long listenerOwner;
                    public long listenerOwner() { return listenerOwner; }
                    private RootCache cache;
                """.stripTrailing());
        if (!batching.entries().isEmpty()) {
            int elements = batching.entries().values().stream().mapToInt(BridgeCallbackBatching.Entry::arity).max().orElseThrow()
                    * BridgeCallbackBatching.CAPACITY;
            state = state.replace("    private RootCache cache;", BATCH_BUFFERS.replace("@BYTES@", Integer.toString(elements * Long.BYTES))
                    + "    private RootCache cache;");
        }
        return sources(generation, cache, 0, state);
    }

    private static Sources sources(BridgeGeneration generation, BridgeIdentityCacheSources.Sources cache,
            int capacity, String state) {
        var sources = new TreeMap<>(cache.sources()); var types = new ArrayList<>(cache.types());
        for (var entry : Map.of("RootState", state, "BridgeLifetimeException", REFUSAL).entrySet()) {
            String name = generation.supportPackage() + "." + entry.getKey();
            sources.put(name.replace('.', '/') + ".java", entry.getValue().replace("@PACKAGE@", generation.supportPackage())
                    .replace("@GENERATION@", generation.identity()));
            types.add(name);
        }
        return new Sources(sources, types.stream().sorted().toList(), capacity);
    }

    /** Component template; enabling this state never admits a callback export. */
    static String state(int capacity, boolean callbacks) {
        var slots = new StringBuilder();
        for (int index = 0; index < capacity; index++) slots.append("    private RootState dependency").append(index).append(";\n");
        return STATE.replace("@SLOTS@", slots)
                .replace("@ACTIVE_FIELD@", callbacks ? "    private long activeUses;\n" : "")
                .replace("@ACTIVE_CHECK@", callbacks ? "        if (activeUses != 0) throw new BridgeLifetimeException(\"native root is active in a callback invocation\");\n" : "")
                .replace("@ACTIVE_METHODS@", callbacks ? ACTIVE_METHODS : "");
    }

    private static final String STATE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            @Identity("@GENERATION@")
            public final class RootState {
                // Native commit owns these fields. Address remains stable after free.
                private long address;
                private int status;
                private long incoming;
            @ACTIVE_FIELD@
            @SLOTS@
                private RootCache cache;

                public RootState() {}

                public long address() { return address; }

                public void checkLive() {
                    if (status != 0) throw new BridgeLifetimeException("native root is not live");
                }

                // This validates eligibility without beginning native destruction.
                // Only a successful adapter commit changes the shared status.
                public boolean prepareFree(long object) {
                    if (object != address) throw new BridgeLifetimeException("borrowed native object cannot be freed");
                    if (status == 2) return false;
                    if (status != 0) throw new BridgeLifetimeException("native root is not live");
                    if (incoming != 0) throw new BridgeLifetimeException("native root is retained");
            @ACTIVE_CHECK@
                    return true;
                }

            @ACTIVE_METHODS@

                public Object lookup(long object) { return cache == null ? null : cache.lookup(object); }

                public Object remember(long object, Object facade) {
                    if (cache == null) cache = new RootCache();
                    return cache.remember(object, facade);
                }
            }
            """;

    private static final String ACTIVE_METHODS = """
                // Generated callback-capable entries guard the receiver and every
                // native argument's owning root, including dependent facades.
                public void enterCallbackUse() {
                    checkLive();
                    if (activeUses == Long.MAX_VALUE) throw new BridgeLifetimeException("native callback nesting limit");
                    activeUses++;
                }

                // Generated nested finally scopes balance successful enters only.
                // Aliased arguments and nested invocations hold separate counts.
                public void leaveCallbackUse() { activeUses--; }
            """;

    private static final String BATCH_BUFFERS = """
                // Private transport scratch, strongly held throughout native use.
                // Existing active-use depth gives nested invocations distinct storage.
                private java.nio.LongBuffer[] callbackBuffers;
                public java.nio.LongBuffer callbackBuffer() {
                    if (activeUses > Integer.MAX_VALUE) return null;
                    int depth = (int) activeUses - 1;
                    try {
                        if (callbackBuffers == null) callbackBuffers = new java.nio.LongBuffer[4];
                        if (depth >= callbackBuffers.length) callbackBuffers = java.util.Arrays.copyOf(callbackBuffers, depth + 1);
                        java.nio.LongBuffer buffer = callbackBuffers[depth];
                        if (buffer == null) {
                            buffer = java.nio.ByteBuffer.allocateDirect(@BYTES@).order(java.nio.ByteOrder.nativeOrder()).asLongBuffer();
                            callbackBuffers[depth] = buffer;
                        }
                        return buffer;
                    } catch (java.lang.OutOfMemoryError unavailable) {
                        // Scratch is optional; ordinary JNI requires none of it.
                        return null;
                    }
                }

            """;

    private static final String REFUSAL = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            @Identity("@GENERATION@")
            final class BridgeLifetimeException extends java.lang.IllegalStateException {
                private static final long serialVersionUID = 1L;
                BridgeLifetimeException(String message) { super(message); }
            }
            """;
}
