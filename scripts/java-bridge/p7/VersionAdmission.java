// SPDX-License-Identifier: MIT OR Apache-2.0

/**
 * Each fresh Java 24/25 JVM must admit its selected artifact: the first native use
 * loads the image instead of raising the loader's version refusal. Under
 * --illegal-native-access=deny the same JVM must fail cleanly before native use.
 */
public final class VersionAdmission {
    private VersionAdmission() {}
    public static void main(String[] args) {
        boolean deny = args.length > 1 && args[1].equals("deny");
        try {
            switch (args[0]) {
                case "arrays" -> arraybench.ArrayOps.read(new int[0]);
                case "byteviews" -> bytebench.ByteOps.read(new byte[0]);
                case "generics" -> genericbench.Box.quote();
                case "bounded-generics" -> new boundedbench.Value(17);
                case "listeners" -> new org.ironwood.javabridge.listeners.ResultProcessor();
                default -> throw new AssertionError("unknown artifact");
            }
        } catch (LinkageError | IllegalCallerException refused) {
            // A denied launch fails inside the facade initializer before any native use.
            Throwable cause = refused;
            while (cause.getCause() != null) cause = cause.getCause();
            if (!deny || !(cause instanceof IllegalCallerException) || !cause.getMessage().contains("native access")) throw refused;
            System.out.println("java" + Runtime.version().feature() + "-denied:" + args[0]);
            return;
        } catch (RuntimeException executed) {
            // The entry ran natively; its own argument checks are not under test here.
        }
        if (deny) throw new AssertionError("native access admitted under deny");
        System.out.println("java" + Runtime.version().feature() + "-admitted:" + args[0]);
    }
}
