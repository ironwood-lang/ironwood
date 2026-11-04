// SPDX-License-Identifier: MIT OR Apache-2.0
// Original synthetic comparison within HotSpot, not a Java/native speed ranking.
import java.lang.management.ManagementFactory;
public class PoolBench {
    static final class Node {
        Node left, right;
        long value, noise, p0, p1, p2, p3;
    }
    static volatile long sink;
    static long pooled(Node[] pool, int n) {
        long sum = 0;
        for (int i = 0; i < n; ++i) {
            Node p = pool[(i * 17) & 4095];
            Node q = pool[(i * 13 + 5) & 4095];
            p.left = q; p.right = q.left;
            p.value += i ^ q.value; sum += p.value;
        }
        return sum;
    }
    static long rare(Node[] pool, int n) {
        long sum = 0;
        for (int i = 0; i < n; ++i) {
            Node p = pool[(i * 17) & 4095];
            Node q = pool[(i * 13 + 5) & 4095];
            p.left = q;
            if ((i & 4095) == 0) { Node fresh = new Node(); fresh.value = i; p.right = fresh; }
            p.value += i ^ q.value; sum += p.value;
        }
        return sum;
    }
    static long collections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }
    public static void main(String[] args) {
        boolean allocate = Boolean.parseBoolean(args[0]);
        int n = Integer.parseInt(args[1]);
        Node[] pool = new Node[4096];
        for (int i = 0; i < pool.length; ++i) { pool[i] = new Node(); pool[i].value = i; }
        for (int i = 0; i < 30; ++i) sink = allocate ? rare(pool, 2000000) : pooled(pool, 2000000);
        System.gc();
        long before = collections();
        long[] samples = new long[31];
        long checksum = 0;
        for (int s = 0; s < samples.length; ++s) {
            long t = System.nanoTime();
            checksum += allocate ? rare(pool, n) : pooled(pool, n);
            samples[s] = System.nanoTime() - t;
        }
        sink = checksum;
        System.out.printf("{\"case\":\"%s\",\"iterations\":%d,\"gc_events\":%d,\"checksum\":%d,\"ns\":[", allocate ? "rare" : "pooled", n, collections()-before, checksum);
        for (int i = 0; i < samples.length; ++i) System.out.print((i == 0 ? "" : ",") + samples[i]);
        System.out.println("]}");
    }
}
