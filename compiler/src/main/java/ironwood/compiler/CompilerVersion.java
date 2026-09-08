// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class CompilerVersion {
    private static final String CURRENT = load();

    private CompilerVersion() {
    }

    static String current() {
        return CURRENT;
    }

    private static String load() {
        try (InputStream input = CompilerVersion.class.getResourceAsStream("/ironwood/compiler/VERSION")) {
            if (input == null) {
                return "unknown";
            }
            String version = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return version.isEmpty() ? "unknown" : version;
        } catch (IOException exception) {
            return "unknown";
        }
    }
}
