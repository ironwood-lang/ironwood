// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference for integration-tests/cases/compiler_text_helpers.iron. Run with
 * the bootstrap classes on the class path: UTF-8 lengths come from J0's own
 * StringPool.utf8Length by reflection; code points, replaceFirst and join
 * come from Java 21.
 */
public final class TextHelpersReference {
    private static long seed = 271828L;
    private static final char[] BOUNDARY = {'A', '\u007f', '\u0080', '߿', 'ࠀ', '퟿', '\ud800', '\udbff',
        '\udc00', '\udfff', '', '￿'};
    private static Method utf8Length;

    private static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    private static int length(String text) throws Exception {
        return (Integer) utf8Length.invoke(null, text);
    }

    public static void main(String[] args) throws Exception {
        utf8Length = Class.forName("ironwood.compiler.semantic.StringPool").getDeclaredMethod("utf8Length", String.class);
        utf8Length.setAccessible(true);
        long hash = 0;
        for (int unit = 0; unit < 65536; unit++) hash = hash * 31 + length(String.valueOf((char) unit));
        System.out.println("single " + hash);
        for (int first = 0; first < BOUNDARY.length; first++) {
            StringBuilder line = new StringBuilder("pairs " + first);
            for (char second : BOUNDARY) line.append(' ').append(length("" + BOUNDARY[first] + second));
            System.out.println(line);
        }
        for (int round = 0; round < 2000; round++) {
            StringBuilder text = new StringBuilder();
            int size = next(12);
            for (int index = 0; index < size; index++) {
                int pick = next(4);
                text.append(pick == 0 ? BOUNDARY[next(BOUNDARY.length)] : pick == 1 ? (char) next(128) : (char) next(65536));
            }
            String value = text.toString();
            System.out.println("random " + round + " " + value.length() + " " + length(value));
        }
        hash = 0;
        for (int high = 0xD800; high <= 0xDBFF; high++) {
            for (int low = 0xDC00; low <= 0xDFFF; low++) hash = hash * 31 + Character.toCodePoint((char) high, (char) low);
        }
        System.out.println("codepoints " + hash);
        String[] reasons = {"allocation escapes through argument 1 of method 'put'", "no match here",
            "allocation escapes through allocation escapes through twice", "", "allocation escapes through ",
            "prefix allocation escapes through suffix"};
        for (int index = 0; index < reasons.length; index++) {
            System.out.println("replace " + index + " [" + reasons[index].replaceFirst("allocation escapes through ",
                    "the reference is stored in ") + "]");
        }
        List<String> parts = new ArrayList<>();
        for (int count = 0; count <= 4; count++) {
            System.out.println("join " + count + " [" + String.join(" or ", parts) + "] " + String.join("\n", parts).length());
            parts.add(count % 2 == 0 ? "alpha" : "");
        }
    }
}
