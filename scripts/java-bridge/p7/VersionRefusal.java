// SPDX-License-Identifier: MIT OR Apache-2.0

/** Each fresh JVM must reject its selected artifact before native extraction. */
public final class VersionRefusal {
    private VersionRefusal() {}
    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "arrays" -> arraybench.ArrayOps.read(new int[0]);
                case "byteviews" -> bytebench.ByteOps.read(new byte[0]);
                case "generics" -> genericbench.Box.quote();
                case "bounded-generics" -> new boundedbench.Value(17);
                case "listeners" -> new org.ironwood.javabridge.listeners.ResultProcessor();
                default -> throw new AssertionError("unknown artifact");
            }
            throw new AssertionError("Java 24 was admitted");
        } catch (LinkageError expected) {
            Throwable cause = expected;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause.getMessage() == null || !cause.getMessage().contains("requires Java 21-23; detected 24")) {
                throw expected;
            }
            System.out.println("java24-refused:" + args[0]);
        }
    }
}
