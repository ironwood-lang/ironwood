// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.ArrayList;
import java.util.TreeMap;
import java.util.TreeSet;

/** Artifact-private Java dispatch lets HotSpot inline the listener implementation. */
public final class BridgeCallbackDispatchSources {
    private BridgeCallbackDispatchSources() {}

    public static String binaryName(BridgeGeneration generation) {
        return generation.supportPackage() + ".CallbackDispatch";
    }

    public static String methodName(int index) { return "invoke" + index; }

    public static BridgeJavaSources add(BridgeJavaSources declarations, BridgeListenerProxies proxies,
            BridgeGeneration generation, BridgeExportSurface surface) {
        String name = binaryName(generation);
        var source = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n\npackage ")
                .append(generation.supportPackage()).append(";\n\n@Identity(")
                .append(BridgeJavaSources.quote(generation.identity())).append(")\n")
                .append("final class CallbackDispatch {\n    private CallbackDispatch() {}\n");
        int index = 0;
        for (var proxy : proxies.proxies()) {
            for (var method : proxy.methods()) {
                source.append("    static ").append(BridgePermanentJavaSources.javaType(method.result(), surface)).append(' ')
                        .append(methodName(index++)).append('(').append(proxy.listener().sourceName()).append(" listener");
                var arguments = new ArrayList<String>();
                for (int parameter = 0; parameter < method.parameters().size(); parameter++) {
                    String argument = "argument" + parameter;
                    arguments.add(argument);
                    source.append(", ").append(BridgePermanentJavaSources.javaType(method.parameters().get(parameter), surface))
                            .append(' ').append(argument);
                }
                // Preserve checked failures without wrapping or changing identity.
                source.append(") throws java.lang.Throwable { ")
                        .append(method.result().equals(ironwood.compiler.ir.IrType.VOID) ? "" : "return ")
                        .append("listener.").append(method.name()).append('(').append(String.join(", ", arguments))
                        .append("); }\n");
            }
        }
        source.append("}\n");
        var sources = new TreeMap<>(declarations.sources());
        var types = new TreeSet<>(declarations.generatedTypes());
        if (sources.putIfAbsent(name.replace('.', '/') + ".java", source.toString()) != null || !types.add(name)) {
            throw new IllegalArgumentException("callback dispatch collides with generated declarations");
        }
        return new BridgeJavaSources(sources, declarations.bindings(), new ArrayList<>(types), declarations.ensureMethod(),
                declarations.facadeRegistrations(), declarations.rootDestructions());
    }
}
