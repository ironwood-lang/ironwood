// SPDX-License-Identifier: MIT OR Apache-2.0
import gc8.Graphs;
import gc8.GraphPhases;
import gc8.Gc8Kernels;
import java.util.Arrays;

/** Cold phase observations; primary native timing uses separate executables. */
public final class Gc8Consumer {
    private Gc8Consumer() {}
    private static boolean quiet;
    private static native long[] observe();
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void emit(String phase, int round, long ns) {
        if (quiet && round >= 4 && round % 10 != 0 && !phase.equals("dropped")) return;
        System.out.println("{\"phase\":\"" + phase + "\",\"round\":" + round + ",\"collect_ns\":" + ns
                + ",\"values\":" + Arrays.toString(observe()) + "}");
    }
    public static void main(String[] args) throws Exception {
        // Trigger the verified generated loader before binding the observer.
        Graphs.initialize();
        if (args[1].equals("internal")) {
            GraphPhases.run(4096, 2, 200);
            emit("internal-warm", 0, 0);
            for (int block = 1; block <= 3; block++) {
                long[] before = observe();
                long ns = GraphPhases.run(4096, 2, 500);
                long[] after = observe();
                check(after[0] == before[0] + 500 && after[2] == before[2], "native block collections/survivors");
                check(Graphs.consume() == 4096L * 4095L / 2 - 4096L * 352L * 2, "native block checksum");
                check(after[14] == before[14] && after[15] == before[15]
                        && after[16] == 0 && after[17] == 0, "native block resources");
                emit("internal", block, ns);
            }
            return;
        }
        if (args[1].equals("kernels")) {
            for (boolean spaced : new boolean[] {false, true}) for (boolean rare : new boolean[] {false, true}) {
                Gc8Kernels.setup(spaced);
                Graphs.collect();
                long[] before = observe();
                Gc8Kernels.reset();
                long checksum = Gc8Kernels.rewrite(1048576, rare, false);
                long[] after = observe();
                check(before[0] == after[0] && after[8] == 1, "noncollecting kernel collected");
                check(after[1] - before[1] == (rare ? 256 : 0), "kernel allocations");
                check(checksum != 0, "kernel checksum");
                emit("kernel", rare ? 1 : 0, 0);
            }
            return;
        }
        int nodes = Integer.parseInt(args[1]), scale = Integer.parseInt(args[2]);
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 14;
        quiet = args.length > 4 && args[4].equals("quiet");
        boolean exercise = args.length <= 5 || !args[5].equals("graphs-only");
        long ns = Graphs.collect();
        emit("baseline", -1, ns);
        long[] resources = null;
        long stableLive = -1, retainedLive = -1;
        for (int round = 0; round < rounds; round++) {
            long[] before = observe();
            Graphs.build(nodes, scale, round == 1);
            long checksum = Graphs.consume();
            check(checksum == (long) nodes * (nodes - 1) / 2 - (long) nodes * 352 * scale, "independent payload checksum");
            long[] built = observe();
            check(built[0] == before[0], "build collected");
            check(built[1] - before[1] == 2L * nodes + (round == 0 ? 2 : 1), "build allocation count");
            emit("built", round, 0);
            Graphs.clearScratch(round >= 3);
            for (int i = 0; i < (exercise ? 32 : 0); i++) {
                Graphs.exceptional();
                check(Graphs.resource(), "file read/close");
                try (var handle = Graphs.anchor()) { check(handle.value() != null, "anchor"); }
            }
            check(observe()[0] == before[0], "cleanup collected");
            ns = Graphs.collect();
            long[] after = observe();
            check(after[0] == before[0] + 1 && after[8] == 1, "explicit collection count/disable");
            check(Graphs.consume() == checksum, "payload/links after collection");
            // Defaults initialized by the first resource/trace/export phase stay cold.
            if (round == 0) resources = after;
            check(after[14] == resources[14] && after[15] == resources[15]
                    && after[16] == 0 && after[17] == 0, "native resource imbalance");
            if (round == 1) retainedLive = after[2];
            if (round == 2) check(after[2] == retainedLive, "retained graph changed");
            if (round == 3) {
                stableLive = after[2];
                check(retainedLive - stableLive == 2L * nodes + 1, "obsolete graph was not reclaimed");
            }
            if (round > 3) check(after[2] == stableLive && after[3] == 0, "survivor/sidecar plateau");
            emit("collected", round, ns);
        }
        Graphs.drop();
        emit("dropped", rounds, Graphs.collect());
        check(observe()[2] < stableLive - 2L * nodes, "current graph still retained");
    }
}
