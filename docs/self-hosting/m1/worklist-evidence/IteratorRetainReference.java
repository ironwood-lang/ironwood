// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Java 21 reference transcript for compiler_iterator_retain.iron, using the
 * exact RejectedFreeEvidence.retainArrayStores loop shape. Logical behavior only.
 */
public final class IteratorRetainReference {

    private static final List<String> KEYS = List.of("AaAaAa", "AaAaBB", "AaBBAa", "AaBBBB",
            "BBAaAa", "BBAaBB", "BBBBAa", "BBBBBB", "x0", "x1", "x2", "x3");

    public static void main(String[] args) {
        if ("AaAaAa".hashCode() != "BBBBBB".hashCode()) throw new AssertionError("collision premise");
        for (int live : new int[]{0, 4095, 1 | 8 | 64 | 512, 2 | 4 | 16 | 32 | 128 | 256 | 1024 | 2048, 7, 7 << 5}) {
            Map<String, String> arrayStores = new HashMap<>();
            KEYS.forEach(key -> arrayStores.put(key, "site"));
            int removed = 0;
            Iterator<Map.Entry<String, String>> stores = arrayStores.entrySet().iterator();
            while (stores.hasNext()) {
                Map.Entry<String, String> entry = stores.next();
                if ((live & (1 << KEYS.indexOf(entry.getKey()))) != 0) continue;
                stores.remove();
                removed++;
            }
            StringBuilder line = new StringBuilder("removed:").append(removed).append(" kept:");
            for (String key : KEYS) {
                if (arrayStores.containsKey(key)) line.append(key).append(' ');
            }
            System.out.println(line.append("size:").append(arrayStores.size()));
        }
    }
}
