// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Java 21 reference for integration-tests/cases/compiler_bridge_text.iron.
 * Run with the compiler's classes on the class path. It writes the corpus to
 * the file named by its argument (float and double bit patterns, integers and
 * strings as UTF-16 hex) and prints Float.toHexString and Double.toHexString
 * of each bit pattern with BridgeJavaSources.literal's float and double
 * spellings, String.format's "\\%03o" and "\\u%04x" escapes of each integer,
 * for each string the baseline's own BridgeJavaSources.quote and
 * BridgeBootstrapSources.cString (called by reflection), stripTrailing, and
 * the Bridge's seven String.matches patterns, and for each llvm-readelf
 * output BridgeLinuxPayload.auditDynamic's FLAGS search and its rpath,
 * runpath, shared-library and GLIBC version matches.
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

    // BridgeLinuxPayload.matches.
    static String matches(String text, String regex) {
        var result = new TreeSet<String>();
        var matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) result.add(matcher.group(1));
        StringBuilder out = new StringBuilder().append(result.size());
        for (String value : result) out.append(' ').append(hex(value));
        return out.toString();
    }

    static List<String> readelf(Random random) {
        String[] lines = {"Dynamic section at offset 0x1000 contains 30 entries:", "  Tag                Type                 Name/Value",
            "  0x0000000000000001 (NEEDED)             Shared library: [libstdc++.so.6]",
            "  0x0000000000000001 (NEEDED)             Shared library: [libc.so.6]",
            "  0x0000000000000001 (NEEDED)             Shared library: [ld-linux-x86-64.so.2]",
            "  0x000000000000001d (RUNPATH)            Library runpath: [$ORIGIN/.ironwood-bridge-support-x/lib]",
            "  0x000000000000000f (RPATH)              Library rpath: [/opt/lib]", "  0x000000006ffffffb (FLAGS_1)            Flags: NOW",
            "  0x000000000000001e (FLAGS)              BIND_NOW", "  0x000000000000001e (FLAGS)              ORIGIN",
            "Version needs section '.gnu.version_r' contains 2 entries:", " Addr: 0x0000000000000000  Offset: 0x000aa8  Link: 4 (.dynstr)",
            "  0x0000: Version: 1  File: libc.so.6  Cnt: 2", "  0x0010:   Name: GLIBC_2.17  Flags: none  Version: 3",
            "  0x0020:   Name: GLIBC_2.2.5  Flags: none  Version: 2", "   Name: GLIBC_PRIVATE", "Shared library: []",
            "Shared library: [x", "Library rpath: []", "Library runpath: [a]b]", "Name: GLIBC_", "Name: GLIBC_2..17", "Name: GLIBC_.",
            "FLAGS NOW", "FLAGS", "NOW", "FLAGSNOW", "Shared library: [Shared library: [y]", "Library rpath: [Library runpath: [z]]",
            "\r", ""};
        List<String> texts = new ArrayList<>(List.of("", "FLAGS\nNOW", "FLAGS\rNOW", "xFLAGS NOWx", "Shared library: [a]Shared library: [b]",
                "Name: GLIBC_2.17Name: GLIBC_2.18", "Library rpath: [a\nb]"));
        for (int index = 0; index < 600; index++) {
            StringBuilder text = new StringBuilder();
            for (int line = random.nextInt(9); line > 0; line--) {
                text.append(lines[random.nextInt(lines.length)]).append(random.nextInt(5) == 0 ? "" : "\n");
            }
            texts.add(text.toString());
        }
        return texts;
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
        List<String> outputs = readelf(random);
        StringBuilder corpus = new StringBuilder();
        for (int bits : floats) corpus.append("float ").append(Integer.toHexString(bits)).append('\n');
        for (long bits : doubles) corpus.append("double ").append(Long.toHexString(bits)).append('\n');
        for (int value : integers) corpus.append("integer ").append(Integer.toHexString(value)).append('\n');
        for (String text : strings) corpus.append("string ").append(hex(text)).append('\n');
        for (String text : outputs) corpus.append("readelf ").append(hex(text)).append('\n');
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
        for (String text : outputs) {
            System.out.println("readelf " + Pattern.compile("FLAGS[^\\n]*NOW").matcher(text).find()
                    + " " + matches(text, "Library (?:rpath|runpath): \\[([^\\]]*)\\]")
                    + " " + matches(text, "Shared library: \\[([^\\]]+)\\]") + " " + matches(text, "Name: GLIBC_([0-9.]+)"));
        }
    }
}
