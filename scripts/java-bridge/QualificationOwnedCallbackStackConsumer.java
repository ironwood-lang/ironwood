// SPDX-License-Identifier: MIT OR Apache-2.0
import ownedcallbackstackprobe.QualificationOwnedCallbackStack;
import ownedcallbackstackprobe.OwnedStackListener;

/** Child-only alternating stack checks for retained listeners and stable owner arguments. */
public final class QualificationOwnedCallbackStackConsumer {
    private static final RuntimeException FAILURE = new RuntimeException("deepest Java callback failure");
    private static final QualificationOwnedCallbackStack ROOT = new QualificationOwnedCallbackStack();
    private static final QualificationOwnedCallbackStack PEER = new QualificationOwnedCallbackStack();
    private QualificationOwnedCallbackStackConsumer() {}

    private static final class Target implements OwnedStackListener {
        @Override public long step(QualificationOwnedCallbackStack self, QualificationOwnedCallbackStack peer,
                                   int depth, long seed, boolean fail) {
            if (self != ROOT || peer != PEER) throw new AssertionError("callback owner identity");
            if (depth == 0) {
                refuse(self); refuse(peer);
                if (fail) throw FAILURE;
                return seed + 7L;
            }
            return self.recurse(peer, depth - 1, seed + 3L, fail, "nested");
        }
    }

    private static void refuse(QualificationOwnedCallbackStack owner) {
        try { owner.free(); throw new AssertionError("freed suspended owner"); }
        catch (IllegalStateException failure) {
            if (!failure.getClass().getSimpleName().equals("BridgeLifetimeException")) throw failure;
        }
    }

    private static long expected(int depth, long seed) {
        long value = seed + 3L * depth + 7L;
        for (int level = 1; level <= depth; level++) value = (value * value + level) ^ (seed + 3L * (depth - level));
        return value;
    }

    private static long enter(int javaDepth, int nativeDepth) {
        if (javaDepth > 0) {
            long live = javaDepth * 17L;
            return enter(javaDepth - 1, nativeDepth) + live;
        }
        long value = ROOT.recurse(PEER, nativeDepth, 19L, false, "outer");
        if (value != expected(nativeDepth, 19L)) throw new AssertionError("callback checksum");
        try {
            ROOT.recurse(PEER, nativeDepth, 19L, true, "outer failure");
            throw new AssertionError("missing callback failure");
        } catch (RuntimeException caught) {
            if (caught != FAILURE) throw new AssertionError("callback identity", caught);
        }
        if (ROOT.recurse(PEER, 1, 19L, false, "continued") != expected(1, 19L)) throw new AssertionError("continued callback");
        return value;
    }

    public static void main(String[] arguments) {
        ROOT.setListener(new Target());
        try {
            if (arguments[0].equals("bounded")) {
                for (int javaDepth : new int[]{0, 64}) {
                    for (int nativeDepth : new int[]{1, 8, 32, 64}) {
                        long value = enter(javaDepth, nativeDepth);
                        if (value != expected(nativeDepth, 19L) + 17L * javaDepth * (javaDepth + 1L) / 2L) throw new AssertionError("Java live values");
                        System.out.println("bounded:" + javaDepth + ":" + nativeDepth + ":" + value);
                    }
                }
                System.out.println("generated-owned-callback-stack-envelope-ok");
            } else {
                int depth = Integer.parseInt(arguments[0]);
                System.out.println("probe-start:" + depth);
                long value = enter(0, depth);
                System.out.println("probe-ok:" + depth + ":" + value);
            }
        } finally {
            ROOT.free(); PEER.free();
        }
    }
}
