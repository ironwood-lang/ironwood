// SPDX-License-Identifier: MIT OR Apache-2.0
import bytebench.ByteOps;
import ironwood.bridge.ByteView;

/** Reuses Java-owned storage, including overlapping and read-only views. */
public final class Consumer {
    private Consumer() {}
    public static void main(String[] args) {
        ByteView bytes = ByteView.allocate(4);
        bytes.put(0, (byte)3);
        if (ByteOps.update(bytes) != 7) throw new AssertionError();
        ByteView readOnly = bytes.asReadOnly();
        if (ByteOps.read(readOnly) != 7) throw new AssertionError();
        if (ByteOps.overlap(bytes.slice(0, 3), bytes.slice(1, 3)) != 18) throw new AssertionError();
        if (readOnly.get(3) != 7) throw new AssertionError();
        System.out.println("shared bytes: 4 5 6 7");
    }
}
