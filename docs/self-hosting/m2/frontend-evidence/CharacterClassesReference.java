// SPDX-License-Identifier: MIT OR Apache-2.0

/** Java 21 reference for CharacterClasses.iron. */
public final class CharacterClassesReference {
    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        for (int property = 0; property < 3; property++) {
            int start = 0;
            int previous = value(property, 0);
            for (int unit = 1; unit <= 65536; unit++) {
                int current = unit == 65536 ? -2 : value(property, unit);
                if (current != previous) {
                    out.append(property).append(' ').append(start).append(' ').append(unit - 1).append(' ')
                            .append(previous).append('\n');
                    start = unit;
                    previous = current;
                }
            }
        }
        System.out.print(out);
    }

    private static int value(int property, int unit) {
        char current = (char) unit;
        if (property == 0) return Character.isDigit(current) ? 1 : 0;
        if (property == 1) return Character.isWhitespace(current) ? 1 : 0;
        return Character.digit(current, 16);
    }
}
