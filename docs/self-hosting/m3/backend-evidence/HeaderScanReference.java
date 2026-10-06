// SPDX-License-Identifier: MIT OR Apache-2.0

/**
 * Java 21 reference for integration-tests/cases/compiler_header_scan.iron:
 * the two String.matches patterns of TlsDependency.discover and
 * BridgeNativeSupport.validate over the same fixed and seeded texts, with
 * the fixture's placeholders (~, |, ^ and ` for vertical tab, form feed,
 * U+2028 and U+00A0) replaced first.
 */
public final class HeaderScanReference {
    private static long seed = 244948L;
    static final String[] TOKENS = {"#define", "#define ", " ", "\t", "\n", "\r", "~", "|", "__GLIBC__",
        "__GLIBC_MINOR__", "2", "17", "27", "x", "#", "define", "__GLIBC_", "_", "^", "`", "#define\t__GLIBC__ 2",
        "#define __GLIBC_MINOR__~17"};
    static final String[] FIXED = {"#define __GLIBC__ 2\n#define __GLIBC_MINOR__ 17\n",
        "#define\t__GLIBC__\t2\t\n#define\t__GLIBC_MINOR__\t17\t",
        "#define __GLIBC__ 2", "#define __GLIBC__ 27\n", "#define  __GLIBC__\n\n2\r", "#  define __GLIBC__ 2\n",
        "#define __GLIBC__ 2`", "#define__GLIBC__ 2 ", "#define __GLIBC_MINOR__ 17 #define __GLIBC__ 2 ",
        "", "#define __GLIBC_MINOR__ 170\n", "x#define|__GLIBC_MINOR__~17\r", "#define __GLIBC__^2 "};

    private HeaderScanReference() { }

    static int next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) (seed >>> 33);
    }

    static void line(StringBuilder out, String label, String placeholders) {
        String features = placeholders.replace('~', '\u000b').replace('|', '\f').replace('^', '\u2028')
                .replace('`', '\u00a0');
        out.append(label).append(' ').append(features.matches("(?s).*#define\\s+__GLIBC__\\s+2\\s.*")).append(' ')
                .append(features.matches("(?s).*#define\\s+__GLIBC_MINOR__\\s+17\\s.*")).append('\n');
    }

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < FIXED.length; index++) line(out, "fixed " + index, FIXED[index]);
        for (int index = 0; index < 4000; index++) {
            StringBuilder text = new StringBuilder();
            int count = next() % 30;
            for (int token = 0; token < count; token++) text.append(TOKENS[next() % TOKENS.length]);
            line(out, "seeded " + index, text.toString());
        }
        System.out.print(out);
    }
}
