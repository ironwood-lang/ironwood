// SPDX-License-Identifier: MIT OR Apache-2.0

/** Java 21 reference transcript for compiler_text_blocks.iron using String.stripIndent. */
public final class TextBlocksReference {

    static int state = 20261005;

    static int next(int bound) {
        state = state * 1103515245 + 12345;
        return ((state >>> 16) & 32767) % bound;
    }

    static char pick() {
        int choice = next(13);
        if (choice < 3) return ' ';
        if (choice == 3) return '\t';
        if (choice == 4) return '\f';
        if (choice == 5) return '\u000B';
        if (choice == 6) return ' ';
        if (choice == 7) return ' ';
        if (choice == 8) return '\n';
        if (choice == 9) return '\r';
        if (choice == 10) return 'a';
        if (choice == 11) return 'b';
        return '\\';
    }

    static int hash(String value) {
        int result = -2128831035;
        for (int index = 0; index < value.length(); index++) result = (result ^ value.charAt(index)) * 16777619;
        return result;
    }

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        String[] fixed = {"", "\n", "  a\n  b", "  a\n   b\n  ", "  a\n\n  b\n", "\ta\n\t\tb\n\t", " a \r\n  b\t\r  ",
                "    hello\n    ", "  x\n    \n  y\n ", "a\r\rb\r\n", " a\n b", " a\n b"};
        for (int index = 0; index < fixed.length; index++) {
            String output = fixed[index].stripIndent();
            out.append(-1 - index).append(':').append(output.length()).append(':').append(hash(output)).append('\n');
        }
        for (int index = 0; index < 20000; index++) {
            int length = next(25);
            StringBuilder builder = new StringBuilder();
            for (int position = 0; position < length; position++) builder.append(pick());
            String output = builder.toString().stripIndent();
            out.append(index).append(':').append(output.length()).append(':').append(hash(output)).append('\n');
        }
        System.out.print(out);
    }
}
