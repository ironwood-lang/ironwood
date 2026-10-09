// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Java reference for integration-tests/cases/compiler_llvm_text.iron. Run with
 * the bootstrap classes on the class path: constants come from the baseline's
 * own LlvmEmitter.floatingConstant and escapeBytes and
 * OptimizedTraceMetadata.decodeSymbol, called by reflection, and the raw-bit
 * spelling from the static initializer's expression.
 */
public final class LlvmTextReference {
    private static long seed = 141421L;
    private static final StringBuilder OUT = new StringBuilder();
    private static Method floating;
    private static Method escape;
    private static Method decode;

    static final long[] RAW_BITS = {0L, 0x8000000000000000L, 0x3ff0000000000000L, 0xbff0000000000000L, 1L,
        0x8000000000000001L, 0x0010000000000000L, 0x000fffffffffffffL, 0x7fefffffffffffffL, 0x7ff0000000000000L,
        0xfff0000000000000L, 0x7ff8000000000000L, 0x7ff0000000000001L, 0xfff8000000000000L, 0x7ff8000000000001L,
        0xffffffffffffffffL, 0x3fb999999999999aL, 0x4024000000000000L};
    static final int[] FLOAT_BITS = {0, 0x80000000, 0x3f800000, 1, 0x00800000, 0x7f7fffff, 0x7f800000, 0xff800000,
        0x7fc00000, 0x7fa00001, 0xffc00001, 0x3dcccccd};
    static final double[] SCALES = {1e-320, 1e-300, 1e-20, 1e-5, 0.1, 1.0, 10.0, 1e5, 1e20, 1e300};
    static final char[] PLAIN = {'a', 'Z', '0', '.', '$', '-', '_', ' ', '\u00e9', '\u4e2d', '\ud83d', '\ude00',
        '\ud800', '\udc00', '"', '\\', 'g'};
    static final String HEX_DIGITS = "0123456789ABCDEFabcdef";

    private LlvmTextReference() { }

    static int next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) (seed >>> 33);
    }

    static long nextLong() {
        return ((long) next() << 33) ^ ((long) next() << 2) ^ next();
    }

    static void both(String label, double value) throws Exception {
        OUT.append("bits ").append(label).append(' ')
                .append(String.format(Locale.ROOT, "0x%016X", Double.doubleToRawLongBits(value))).append('\n');
        OUT.append("constant ").append(label).append(' ').append((String) floating.invoke(null, value)).append('\n');
    }

    static void escape(String label, byte[] bytes) throws Exception {
        OUT.append(label).append(' ').append((String) escape.invoke(null, (Object) bytes)).append('\n');
    }

    static void decode(String label, String symbol) throws Exception {
        String decoded = (String) decode.invoke(null, symbol);
        OUT.append(label);
        for (int index = 0; index < decoded.length(); index++) OUT.append(' ').append((int) decoded.charAt(index));
        OUT.append('\n');
    }

    public static void main(String[] args) throws Exception {
        Class<?> emitter = Class.forName("ironwood.compiler.backend.LlvmEmitter");
        floating = emitter.getDeclaredMethod("floatingConstant", double.class);
        floating.setAccessible(true);
        escape = emitter.getDeclaredMethod("escapeBytes", byte[].class);
        escape.setAccessible(true);
        decode = Class.forName("ironwood.compiler.backend.OptimizedTraceMetadata")
                .getDeclaredMethod("decodeSymbol", String.class);
        decode.setAccessible(true);
        for (int index = 0; index < RAW_BITS.length; index++) both("raw " + index, Double.longBitsToDouble(RAW_BITS[index]));
        for (int index = 0; index < FLOAT_BITS.length; index++) {
            both("float " + index, (double) Float.intBitsToFloat(FLOAT_BITS[index]));
        }
        for (int index = 0; index < 60000; index++) both("seeded", Double.longBitsToDouble(nextLong()));
        for (int index = 0; index < 20000; index++) both("widened", (double) Float.intBitsToFloat(next() ^ (next() << 16)));
        for (int index = 0; index < 20000; index++) {
            both("decimal", (double) (next() % 2000001 - 1000000) * SCALES[next() % SCALES.length]);
        }
        for (int value = 0; value < 256; value++) escape("escape " + value, new byte[] {(byte) value});
        escape("escape empty", new byte[0]);
        for (int index = 0; index < 2000; index++) {
            byte[] bytes = new byte[next() % 21];
            for (int at = 0; at < bytes.length; at++) bytes[at] = (byte) next();
            escape("escape seeded", bytes);
        }
        String[] fixed = {"@main", "@a.b$c_d-1", "@\"x\"", "@\"a\\22b\"", "@\"\\5C\\5c\"", "@\"\\zz\\5\"", "@\"\\\"",
            "@\"\\C3\\A9\\E4\\B8\\AD\"", "@\"\\C3\\E4\\B8\\F0\\9F\\98\\ED\\A0\\80\\C0\\AF\\FF\"",
            "@\"\\F0\\9F\\98\\80\\F4\\90\\80\\80\"", "@\"\"", "@\"\ud83d\ude00\\41\"", "@\"\ud800x\udc00\u00e9\"",
            "@\u4e2d"};
        for (int index = 0; index < fixed.length; index++) decode("decode " + index, fixed[index]);
        for (int index = 0; index < 3000; index++) {
            StringBuilder symbol = new StringBuilder("@\"");
            int tokens = next() % 12;
            for (int token = 0; token < tokens; token++) {
                int kind = next() % 4;
                if (kind == 0) {
                    symbol.append('\\').append(HEX_DIGITS.charAt(next() % 22)).append(HEX_DIGITS.charAt(next() % 22));
                } else if (kind == 1) {
                    symbol.append('\\').append(PLAIN[next() % PLAIN.length]);
                } else {
                    symbol.append(PLAIN[next() % PLAIN.length]);
                }
            }
            symbol.append('"');
            decode("decode seeded " + index, symbol.toString());
        }
        System.out.print(OUT);
    }
}
