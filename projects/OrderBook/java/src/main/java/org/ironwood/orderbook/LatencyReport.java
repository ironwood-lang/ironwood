// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.orderbook;

import java.util.Arrays;
import java.util.Locale;

/**
 * Post-processing for the Java driver, with the same report conventions as
 * ironwood.bench.Bench. Sorting and formatting run after all timed batches.
 */
final class LatencyReport {

    private static final double[] FRACTIONS = {0.75, 0.9, 0.99, 0.999, 0.9999, 0.99999};

    private static final String[] LABELS = {"75%", "90%", "99%", "99.9%", "99.99%", "99.999%"};

    private static final String[] UNITS = {"nano", "micro", "milli", "second"};

    private LatencyReport() {

    }

    static String results(long[] samples, int warmup) {

        if (warmup < 0 || warmup > samples.length) throw new IllegalArgumentException("invalid warmup count");
        int count = samples.length - warmup;
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT,
                "Measurements: %,d | Warm-Up: %,d | Iterations: %,d\n", count, warmup, samples.length));
        if (count == 0) return report.toString();

        long[] sorted = Arrays.copyOfRange(samples, warmup, samples.length);
        Arrays.sort(sorted);
        long sum = 0L;
        for (long sample : sorted) sum += sample;
        double mean = (double) sum / count;
        if (mean <= Long.MAX_VALUE / 100.0) mean = Math.round(mean * 100.0) / 100.0;
        report.append("Avg Time: ").append(time(mean))
                .append(" | Min Time: ").append(time(sorted[0]))
                .append(" | Max Time: ").append(time(sorted[count - 1])).append('\n');

        int consumed = 0;
        long prefixSum = 0L;
        for (int index = 0; index < FRACTIONS.length; index++) {
            int rank = (int) Math.round(FRACTIONS[index] * count);
            while (consumed < rank) prefixSum += sorted[consumed++];
            report.append(LABELS[index]).append(" = [avg: ")
                    .append(time((double) prefixSum / rank))
                    .append(", max: ").append(time(sorted[rank - 1])).append("]\n");
        }
        return report.toString();
    }

    private static String time(double nanos) {

        double divisor = 1.0;
        int unit = 0;
        while (unit < UNITS.length - 1 && nanos >= divisor * 1000.0) {
            divisor *= 1000.0;
            unit++;
        }
        double value = Math.round(nanos / divisor * 1000.0) / 1000.0;
        return String.format(Locale.ROOT, "%.3f %s%s", value, UNITS[unit], value > 1.0 ? "s" : "");
    }
}
