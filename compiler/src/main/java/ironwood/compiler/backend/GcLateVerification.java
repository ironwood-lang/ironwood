// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Fail closed if LLVM introduces an undescribed managed stack owner or loses GC strategy. */
final class GcLateVerification {
    private static final Pattern SLOT = Pattern.compile("^\\s*(%[-a-zA-Z$._0-9]+) = alloca (.*)$");
    private GcLateVerification() {}

    static void strategies(String llvm) throws IOException {
        for (String line : llvm.lines().toList()) {
            if (line.startsWith("define ") && !line.contains("@ironwood_trace_register_current(")
                    && !line.contains("gc \"statepoint-example\"")) {
                throw new IOException("GC3 LLVM transform lost a function's GC strategy");
            }
        }
    }

    static void stackRegions(String llvm) throws IOException {
        var slots = new ArrayList<String>();
        var statepoints = new ArrayList<String>();
        for (String line : llvm.lines().toList()) {
            var match = SLOT.matcher(line);
            if (match.matches() && match.group(2).contains("addrspace(1)")) {
                if (!match.group(2).startsWith("ptr addrspace(1),")) {
                    throw new IOException("GC3 unsupported aggregate managed stack region after LLVM optimization");
                }
                slots.add(match.group(1));
            }
            if (line.contains(" = call token ") && line.contains("@llvm.experimental.gc.statepoint.")) statepoints.add(line);
            if (line.equals("}")) {
                for (String slot : slots) {
                    var described = Pattern.compile("ptr " + Pattern.quote(slot) + "(?=[,) ])");
                    for (String statepoint : statepoints) {
                        int bundle = statepoint.indexOf("\"deopt\"(");
                        int end = bundle < 0 ? -1 : statepoint.indexOf(')', bundle);
                        if (end < 0 || !described.matcher(statepoint.substring(bundle, end + 1)).find()) {
                            throw new IOException("GC3 managed alloca is absent from a collection statepoint's region description");
                        }
                    }
                }
                slots.clear(); statepoints.clear();
            }
        }
    }
}
