// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Java 21 reference for integration-tests/cases/compiler_bridge_text.iron.
 * Run with the compiler's classes on the class path. It writes the corpus to
 * the file named by its argument (float and double bit patterns, integers and
 * strings as UTF-16 hex) and prints Float.toHexString and Double.toHexString
 * of each bit pattern with BridgeJavaSources.literal's float and double
 * spellings, String.format's "\\%03o" and "\\u%04x" escapes of each integer,
 * and for each string the baseline's own BridgeJavaSources.quote and
 * BridgeBootstrapSources.cString (called by reflection), stripTrailing, and
 * the Bridge's seven String.matches patterns.
 */
public final class BridgeTextReference {
    static final String[] PATTERNS = {"[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*", "[A-Za-z0-9_][A-Za-z0-9_.+-]*",
        "[A-Za-z_$][A-Za-z0-9_$]*", "[0-9a-f]{64}", "[0-9]+(?:\\.[0-9]+){0,2}", "[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*",
        "\\$ironwood\\$ensure\\$*"};

    private BridgeTextReference() { }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (char unit : text.toCharArray()) out.append(String.format("%04x", (int) unit));
        return out.length() == 0 ? "-" : out.toString();
    }

    // BridgeJavaSources.literal's F32 and F64 spellings.
    static String floatLiteral(float real) {
        return Float.isNaN(real) ? "(0.0f / 0.0f)" : real == Float.POSITIVE_INFINITY ? "(1.0f / 0.0f)"
                : real == Float.NEGATIVE_INFINITY ? "(-1.0f / 0.0f)" : Float.toHexString(real) + "f";
    }

    static String doubleLiteral(double real) {
        return Double.isNaN(real) ? "(0.0d / 0.0d)" : real == Double.POSITIVE_INFINITY ? "(1.0d / 0.0d)"
                : real == Double.NEGATIVE_INFINITY ? "(-1.0d / 0.0d)" : Double.toHexString(real);
    }

    public static void main(String[] args) throws Exception {
        Method quote = Class.forName("ironwood.compiler.bridge.BridgeJavaSources").getDeclaredMethod("quote", String.class);
        Method cString = Class.forName("ironwood.compiler.bridge.BridgeBootstrapSources").getDeclaredMethod("cString", String.class);
        cString.setAccessible(true);
        Random random = new Random(20261009);
        List<Integer> floats = new ArrayList<>(List.of(0, 0x80000000, 1, 0x80000001, 0x007fffff, 0x00800000, 0x00400000,
                0x3f800000, 0xbf800000, 0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000, 0x7fc00000, 0xffc00000, 0x7f800001,
                0x7fbfffff, 0x3dcccccd, 0x40490fdb, 0x00000010, 0x00100000, 0x0080000f, 0x4b800000));
        for (int index = 0; index < 4000; index++) floats.add(random.nextInt());
        for (int index = 0; index < 500; index++) floats.add(random.nextInt(0x00800000) | (random.nextBoolean() ? 0x80000000 : 0));
        List<Long> doubles = new ArrayList<>(List.of(0L, 0x8000000000000000L, 1L, 0x000fffffffffffffL, 0x0010000000000000L,
                0x3ff0000000000000L, 0xbff0000000000000L, 0x7fefffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff0000000000001L, 0x3fb999999999999aL, 0x400921fb54442d18L,
                0x0008000000000000L, 0x0000000000000100L, 0x4330000000000000L));
        for (int index = 0; index < 4000; index++) doubles.add(random.nextLong());
        for (int index = 0; index < 500; index++) doubles.add((random.nextLong() & 0x000fffffffffffffL) | (random.nextBoolean() ? Long.MIN_VALUE : 0));
        List<Integer> integers = new ArrayList<>();
        for (int value = 0; value < 600; value++) integers.add(value);
        integers.addAll(List.of(0xfff, 0x1000, 0xffff, 0x10000, 0x10ffff, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0777, 01000));
        for (int index = 0; index < 200; index++) integers.add(random.nextInt());
        List<String> strings = new ArrayList<>(List.of("", "a", "a ", "a\t\n\r\u000b\f\u001c\u001d\u001e\u001f ", " \u2028",
                "x\u00a0", "x\u2007", "x\u3000", "x\u0085", "\"q\"", "\\", "\b\f\n\r\t", "\u0000\u0001\u001f\u007f\u0080",
                "é中😀", "\ud800", "\udc00x", "org.example", "org..example", ".org", "org.", "a-b_c.d9", "1.0", "1.0-SNAPSHOT+b",
                "_x", "+x", "-x", "x+y", "$root", "a$b", "9a", "0".repeat(64), "a".repeat(64), "A".repeat(64), "0".repeat(63),
                "0".repeat(65), "11", "11.0", "11.0.1", "11.0.1.2", "11..0", "11.", "lib/libgcc_s.so.1",
                ".ironwood-bridge-support-0/lib/libstdc++.so.6", "/lib", "lib/", "lib//x", "$ironwood$ensure",
                "$ironwood$ensure$$", "$ironwood$ensurex", "ironwood$ensure", "$ironwood$ensure$x", "１", "a\u0661"));
        String alphabet = "aZ09._-+$/ \t\n\r\u000b\f\u001f\u007f\"\\éf$\u2028\u00a0\ud83d\ude00\ud800";
        for (int index = 0; index < 3000; index++) {
            StringBuilder text = new StringBuilder();
            for (int unit = random.nextInt(14); unit > 0; unit--) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            strings.add(text.toString());
        }
        for (int index = 0; index < 300; index++) strings.add("$ironwood$ensure" + "$".repeat(random.nextInt(3))
                + (random.nextInt(4) == 0 ? "x" : ""));
        StringBuilder corpus = new StringBuilder();
        for (int bits : floats) corpus.append("float ").append(Integer.toHexString(bits)).append('\n');
        for (long bits : doubles) corpus.append("double ").append(Long.toHexString(bits)).append('\n');
        for (int value : integers) corpus.append("integer ").append(Integer.toHexString(value)).append('\n');
        for (String text : strings) corpus.append("string ").append(hex(text)).append('\n');
        Files.writeString(Path.of(args[0]), corpus.toString(), StandardCharsets.UTF_8);
        for (int bits : floats) {
            float real = Float.intBitsToFloat(bits);
            System.out.println("float " + Float.toHexString(real) + " " + floatLiteral(real));
        }
        for (long bits : doubles) {
            double real = Double.longBitsToDouble(bits);
            System.out.println("double " + Double.toHexString(real) + " " + doubleLiteral(real));
        }
        for (int value : integers) {
            System.out.println("integer " + String.format("\\%03o", value) + " " + String.format(Locale.ROOT, "\\%03o", value)
                    + " " + String.format(Locale.ROOT, "\\u%04x", value) + " " + String.format("\\u%04x", value));
        }
        for (String text : strings) {
            StringBuilder matches = new StringBuilder();
            for (String pattern : PATTERNS) matches.append(text.matches(pattern) ? '1' : '0');
            System.out.println("string " + hex((String) quote.invoke(null, text)) + " " + hex((String) cString.invoke(null, text))
                    + " " + hex(text.stripTrailing()) + " " + matches);
        }
    }
}
