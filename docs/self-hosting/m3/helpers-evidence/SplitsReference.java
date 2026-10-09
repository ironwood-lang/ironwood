// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.regex.Pattern;

/**
 * Java 21 reference for integration-tests/cases/compiler_splits.iron:
 * String.split with a quoted one-character regex over the same inputs.
 */
public final class SplitsReference {
    private static String text(int code, int length) {
        StringBuilder text = new StringBuilder();
        int rest = code;
        for (int index = 0; index < length; index++) {
            int digit = rest % 4;
            rest = rest / 4;
            text.append(digit == 0 ? 'a' : digit == 1 ? 'b' : digit == 2 ? '.' : '/');
        }
        return text.toString();
    }

    private static void line(String text, char delimiter, int limit) {
        String[] fields = text.split(Pattern.quote(String.valueOf(delimiter)), limit);
        StringBuilder out = new StringBuilder();
        out.append('"').append(text).append("\" ").append(delimiter).append(' ').append(limit).append(" [");
        for (int field = 0; field < fields.length; field++) {
            if (field > 0) out.append(',');
            out.append('"').append(fields[field]).append('"');
        }
        System.out.println(out.append(']'));
    }

    public static void main(String[] args) {
        int[] limits = {-1, 0, 1, 2, 3};
        int codes = 1;
        for (int length = 0; length <= 5; length++) {
            for (int code = 0; code < codes; code++) {
                String value = text(code, length);
                for (int limit : limits) {
                    line(value, '.', limit);
                    line(value, '/', limit);
                }
            }
            codes *= 4;
        }
    }
}
