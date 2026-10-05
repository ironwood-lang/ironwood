// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Java 21 oracle for the selected private snapshot contracts, not reclamation. */
public final class SnapshotReference {
    private static void require(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) {
        for (int count : new int[]{0, 8, 32, 128, 512}) {
            ArrayList<String> builder = new ArrayList<>();
            for (int index = 0; index < count; index++) builder.add("item");
            var cursor = builder.iterator();
            if (count > 0) cursor.next();
            List<String> snapshot = List.copyOf(builder);
            require(snapshot.size() == count && snapshot.isEmpty() == (count == 0));
            if (count > 1) require(cursor.next() == "item");
            builder.clear();
            require(snapshot.size() == count);
            if (count > 0) require(snapshot.get(count - 1) == "item");
        }
        for (int bit : new int[]{-1, 63, 64, 65, 4096, Integer.MAX_VALUE}) {
            BitSet source = new BitSet();
            if (bit >= 0) source.set(bit);
            int capacity = source.isEmpty() ? 64 : source.size();
            BitSet snapshot = new BitSet(capacity < 64 ? 64 : capacity);
            snapshot.or(source);
            require(snapshot.equals(source));
            source.clear();
            if (bit >= 0) require(snapshot.get(bit) && snapshot.length() == bit + 1);
            snapshot.clear();
            snapshot.set(2);
            require(source.isEmpty());
        }
        BitSet trailing = new BitSet(8192);
        trailing.set(4096);
        trailing.clear(4096);
        BitSet empty = new BitSet();
        empty.or(trailing);
        require(empty.isEmpty() && empty.equals(trailing));
        try {
            List.copyOf(null);
            throw new AssertionError();
        } catch (NullPointerException expected) {
            // The selected native builder rejects null before copying too.
        }
        try {
            new BitSet().or(null);
            throw new AssertionError();
        } catch (NullPointerException expected) {
            // Primitive logical copying has the same null-input boundary.
        }
        System.out.println("selected snapshot contracts pass");
    }
}
