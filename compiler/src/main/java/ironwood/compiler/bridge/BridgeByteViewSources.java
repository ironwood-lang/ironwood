// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

/** Original Java-owned storage API, shared by all generated bridge artifacts. */
public final class BridgeByteViewSources {
    private BridgeByteViewSources() {}
    public static final String BINARY_NAME = "ironwood.bridge.ByteView";
    public static final String SOURCE_PATH = "ironwood/bridge/ByteView.java";
    public static final String CLASS_PATH = "ironwood/bridge/ByteView.class";
    public static final String JAR_NAME = "ironwood-bridge-values.jar";
    public static final String RESOURCE = "META-INF/ironwood/java-dependencies/" + JAR_NAME;
    public static final String ABI = "1";
    public static final String SOURCE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package ironwood.bridge;

            /** Bounded Java-owned bytes borrowed synchronously by Ironwood calls.
             * Callers must prevent concurrent access that conflicts with a write.
             * Storage is reclaimed by the JVM; this value has no close/free operation.
             */
            public final class ByteView {
                private final java.nio.ByteBuffer storage;
                private final int offset;
                private final int length;
                private final boolean readOnly;

                private ByteView(java.nio.ByteBuffer storage, int offset, int length, boolean readOnly) {
                    this.storage = storage;
                    this.offset = offset;
                    this.length = length;
                    this.readOnly = readOnly;
                }

                /** Creates zero-filled writable storage. */
                public static ByteView allocate(int length) {
                    if (length < 0) throw new IllegalArgumentException("negative byte-view length");
                    return new ByteView(java.nio.ByteBuffer.allocateDirect(length), 0, length, false);
                }

                /** Creates a distinct view sharing the requested range and permissions. */
                public ByteView slice(int offset, int length) {
                    if (offset < 0 || length < 0 || offset > this.length || length > this.length - offset) {
                        throw new IndexOutOfBoundsException();
                    }
                    return new ByteView(storage, this.offset + offset, length, readOnly);
                }

                /** Creates a distinct read-only view of the same range. */
                public ByteView asReadOnly() { return new ByteView(storage, offset, length, true); }
                public int length() { return length; }
                public boolean isReadOnly() { return readOnly; }
                public byte get(int index) {
                    if (index < 0 || index >= length) throw new IndexOutOfBoundsException();
                    return storage.get(offset + index);
                }
                public void put(int index, byte value) {
                    if (readOnly) throw new UnsupportedOperationException("read-only byte view");
                    if (index < 0 || index >= length) throw new IndexOutOfBoundsException();
                    storage.put(offset + index, value);
                }
            }
            """;

    public static boolean required(BridgeJavaSources declarations) {
        return declarations.nativeDeclarations().stream().anyMatch(method -> method.descriptor().contains("Lironwood/bridge/ByteView;"));
    }
}
