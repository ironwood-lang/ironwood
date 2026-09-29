// SPDX-License-Identifier: MIT OR Apache-2.0
package arraybench;

/** Independent Java baseline with the same data operations as ArrayOps.iron. */
public final class ArrayOps {
    private ArrayOps() {}
    public static long read(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
    }
    public static long update(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) { values[i] ^= 0x13579; sum += values[i]; }
        return sum;
    }
    public static int[] fresh(int size, int seed) {
        int[] values = new int[size];
        for (int i = 0; i < size; i++) values[i] = seed ^ i;
        return values;
    }
    public static long allocations() { return 0; }
    public static long live() { return 0; }
}
