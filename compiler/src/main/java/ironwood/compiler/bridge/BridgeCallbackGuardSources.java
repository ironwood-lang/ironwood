// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.List;

/** Allocation-free guard scopes for already evaluated owning-root locals. */
final class BridgeCallbackGuardSources {
    private BridgeCallbackGuardSources() {}

    static String wrap(String body, List<String> roots) {
        String result = body;
        for (int index = roots.size() - 1; index >= 0; index--) {
            String root = roots.get(index);
            if (!root.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("callback guards require stable root-state locals");
            }
            result = "if (" + root + " != null) " + root + ".enterCallbackUse();\ntry {\n"
                    + result + "\n} finally {\nif (" + root + " != null) " + root + ".leaveCallbackUse();\n}\n";
        }
        return result;
    }
}
