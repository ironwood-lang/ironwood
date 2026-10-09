// SPDX-License-Identifier: MIT OR Apache-2.0

/** Java 21 reference for integration-tests/cases/compiler_floating_text.iron. */
public final class FloatingTextReference {
    static long seed = 99;
    static long next() { seed = seed * 6364136223846793005L + 1442695040888963407L; return seed; }
    static void d(double value) { String t = Double.toString(value); System.out.println("d " + Double.doubleToLongBits(value) + " " + t + " " + Double.doubleToLongBits(Double.parseDouble(t))); }
    static void f(float value) { String t = Float.toString(value); System.out.println("f " + Float.floatToIntBits(value) + " " + t + " " + Float.floatToIntBits(Float.parseFloat(t))); }
    public static void main(String[] args) {
        double[] doubles = {0.0, -0.0, 1.0, -1.0, 0.1, 1e-300, 1e300, Double.MAX_VALUE, Double.MIN_VALUE,
            Double.MIN_NORMAL, 1e7, 9999999.0, 1e-3, 0.001, 1.0e21, 1.0e22, 123456789012345678.0, 2.0e-323,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 5e-324, 4.9e-324, 1.7976931348623157e308};
        for (double v : doubles) d(v);
        float[] floats = {0.0f, -0.0f, 1.0f, 0.1f, 1.0f / 3.0f, Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL,
            1e7f, 9999999.0f, 1e-3f, 16777216.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float v : floats) f(v);
        for (int round = 0; round < 200000; round++) {
            double value = Double.longBitsToDouble(next());
            if (!Double.isNaN(value)) d(value);
            float single = Float.intBitsToFloat((int) (next() >>> 32));
            if (!Float.isNaN(single)) f(single);
        }
    }
}
