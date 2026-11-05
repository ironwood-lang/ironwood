// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail closed if LLVM loses GC strategy or introduces undescribed stack owners. */
final class GcLateVerification {
    private static final Pattern DEFINITION = Pattern.compile("^define .*?(@(?:\"(?:[^\"\\\\]|\\\\.)*\"|[-a-zA-Z$._0-9]+))\\(");
    private static final Pattern SLOT = Pattern.compile("^\\s*(%[-a-zA-Z$._0-9]+) = alloca (.*)$");
    private static final Pattern STATEPOINT_CALL = Pattern.compile(" = (?:(?:tail|musttail|notail) )?(?:call|invoke) ");
    private GcLateVerification() {}

    static String optimizedStrategies(String input, String optimized) throws IOException {
        strategies(input);
        var originals = new HashSet<String>();
        for (String line : input.lines().toList()) {
            var definition = DEFINITION.matcher(line);
            if (definition.find()) originals.add(OptimizedTraceMetadata.decodeSymbol(definition.group(1)));
        }
        var result = new StringBuilder();
        for (String line : optimized.lines().toList()) {
            var definition = DEFINITION.matcher(line);
            if (definition.find() && !originals.contains(OptimizedTraceMetadata.decodeSymbol(definition.group(1)))) {
                if (!line.startsWith("define internal ")) throw new IOException("unexpected optimizer-created external definition: " + line);
                if (!line.contains("gc \"statepoint-example\"")) {
                    int position = line.indexOf(" personality ");
                    if (position < 0) position = line.indexOf(" !dbg ");
                    if (position < 0) position = line.lastIndexOf(" {");
                    if (position < 0) throw new IOException("invalid optimizer-created definition");
                    line = line.substring(0, position) + " gc \"statepoint-example\"" + line.substring(position);
                }
            }
            result.append(line).append('\n');
        }
        strategies(result.toString());
        return result.toString();
    }

    static void strategies(String llvm) throws IOException {
        var leafGroups = new HashSet<String>();
        for (String line : llvm.lines().toList()) {
            if (line.startsWith("attributes #") && line.contains("\"gc-leaf-function\"")) {
                leafGroups.add(line.substring("attributes ".length(), line.indexOf(" =")));
            }
        }
        for (String line : llvm.lines().toList()) {
            if (line.startsWith("define ") && !line.contains("@ironwood_trace_register_current(")
                    && !line.contains("gc \"statepoint-example\"")
                    && !line.contains("\"gc-leaf-function\"")
                    && leafGroups.stream().noneMatch(group -> line.matches(".* " + group + "(?=[ {]).*"))) {
                throw new IOException("LLVM transform lost a function's GC strategy: " + line);
            }
        }
    }

    static void stackRegions(String llvm) throws IOException {
        var body = new ArrayList<String>();
        for (String line : llvm.lines().toList()) {
            body.add(line);
            if (line.equals("}")) {
                functionRegions(body);
                body.clear();
            }
        }
    }

    private static boolean mentions(String text, String slot) {
        return Pattern.compile(Pattern.quote(slot) + "(?=[,)= \\t]|$)").matcher(text).find();
    }

    private static String region(String statepoint) throws IOException {
        int start = statepoint.indexOf("\"deopt\"(");
        int end = start < 0 ? -1 : statepoint.indexOf(')', start);
        return end < 0 ? "" : statepoint.substring(start, end + 1);
    }

    private static void functionRegions(List<String> body) throws IOException {
        Set<String> regular = new HashSet<>(), transientSlots = new HashSet<>();
        for (String line : body) {
            var match = SLOT.matcher(line);
            if (!match.matches() || !match.group(2).contains("addrspace(1)")) continue;
            if (!match.group(2).startsWith("ptr addrspace(1),")) {
                throw new IOException("unsupported aggregate managed stack region after LLVM optimization");
            }
            if (line.contains("!iw.gc.statepoint.spill")) {
                if (!match.group(1).startsWith("%iw.gc.unwind.root")) throw new IOException("invalid transient GC spill identity");
                transientSlots.add(match.group(1));
            } else regular.add(match.group(1));
        }
        Set<String> pending = new HashSet<>(), described = new HashSet<>();
        for (String line : body) {
            boolean statepoint = STATEPOINT_CALL.matcher(line).find()
                    && line.contains("@llvm.experimental.gc.statepoint.");
            String inventory = statepoint ? region(line) : "";
            boolean spillStore = false;
            for (String slot : transientSlots) {
                if (!mentions(line, slot)) continue;
                if (SLOT.matcher(line).matches()) continue;
                boolean store = line.stripLeading().startsWith("store ptr addrspace(1) ")
                        && Pattern.compile(", ptr " + Pattern.quote(slot) + "(?:, align 8)?(?:, !dbg ![0-9]+)?$").matcher(line).find();
                if (store) {
                    if (!pending.add(slot) || described.contains(slot)) throw new IOException("reused transient GC spill");
                    spillStore = true;
                } else if (statepoint && mentions(inventory, slot) && pending.contains(slot)) {
                    described.add(slot);
                } else throw new IOException("transient GC spill used outside its store and statepoint inventory: " + line.strip());
            }
            if (statepoint) {
                for (String slot : regular) if (!mentions(inventory, slot)) {
                    throw new IOException("managed alloca is absent from a collection statepoint's region description");
                }
                for (String slot : pending) if (!mentions(inventory, slot)) {
                    throw new IOException("transient GC spill is absent from its statepoint inventory");
                }
                pending.clear();
            } else if (!pending.isEmpty() && !spillStore) {
                throw new IOException("transient GC spill store is not immediately before its statepoint");
            }
        }
        if (!pending.isEmpty() || !described.equals(transientSlots)) throw new IOException("incomplete transient GC spill coverage");
    }
}
