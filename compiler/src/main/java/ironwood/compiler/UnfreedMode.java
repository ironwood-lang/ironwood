// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

/** Severity of proven local allocation-abandonment findings. */
public enum UnfreedMode {
    OFF, WARN, ERROR;

    public static UnfreedMode parse(String value) {
        return switch (value) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "error" -> ERROR;
            default -> throw new IllegalArgumentException("expected off, warn, or error");
        };
    }
}
