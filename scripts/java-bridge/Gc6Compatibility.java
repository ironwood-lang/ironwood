// SPDX-License-Identifier: MIT OR Apache-2.0
/** Load-time compatibility controls must finish before any source body runs. */
public final class Gc6Compatibility {
    private Gc6Compatibility() {}
    public static void main(String[] arguments) throws Exception {
        boolean refuse = arguments[0].equals("refuse");
        try {
            Class<?> type = Class.forName(arguments[1]);
            if (refuse) throw new AssertionError("incompatible pairing reached source initialization");
            type.getMethod("live").invoke(null);
            System.out.println("GC6 compatible image loaded");
        } catch (LinkageError expected) {
            if (!refuse) throw expected;
            System.out.println("GC6 incompatible image refused");
        }
    }
}
