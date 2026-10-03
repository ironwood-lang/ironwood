// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.stream.IntStream;

/** Noncritical JNI regions and one owned conversion state per Java input identity. */
final class BridgeArrayInputSources {
    private BridgeArrayInputSources() {}

    static List<Integer> indices(List<IrType> parameters) {
        return IntStream.range(0, parameters.size()).filter(index -> parameters.get(index).isArray()).boxed().toList();
    }

    static String declarations(List<IrType> parameters) {
        var text = new StringBuilder();
        for (int index : indices(parameters)) {
            text.append("    struct ironwood_bridge_array_input array_state").append(index)
                    .append(" = { .length = -1 };\n")
                    .append("    struct ironwood_bridge_array_input *array").append(index).append(" = &array_state")
                    .append(index).append(";\n");
        }
        return text.toString();
    }

    static String acquire(List<IrType> parameters) {
        var text = new StringBuilder();
        var arrays = indices(parameters);
        for (int index : arrays) {
            String element = BridgeJavaTypes.sourceName(parameters.get(index).elementType());
            text.append("    if (arg").append(index).append(" != NULL) {\n");
            for (int previous : arrays) {
                if (previous >= index) break;
                if (!parameters.get(previous).equals(parameters.get(index))) continue;
                text.append("        if ((*env)->IsSameObject(env, arg").append(index).append(", arg").append(previous)
                        .append(")) array").append(index).append(" = array").append(previous).append(";\n        else\n");
            }
            text.append("        {\n            array_state").append(index).append(".length = (*env)->GetArrayLength(env, arg")
                    .append(index).append(");\n            if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("            if (array_state").append(index).append(".length > 0) {\n")
                    .append("                void *elements = malloc((size_t)array_state").append(index).append(".length * sizeof(j")
                    .append(element).append("));\n")
                    .append("                if (elements == NULL) { iw_exception_error(env, &iw_exceptions, 1, \"Ironwood array preparation failed\"); goto preparation_failed; }\n")
                    .append("                array_state").append(index).append(".elements = elements;\n")
                    .append("                (*env)->Get").append(regionKind(parameters.get(index))).append("ArrayRegion(env, arg")
                    .append(index).append(", 0, array_state").append(index).append(".length, elements);\n")
                    .append("                if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n            }\n        }\n    }\n");
        }
        return text.toString();
    }

    static String release(List<IrType> parameters) {
        var text = new StringBuilder();
        for (int index : indices(parameters).reversed()) {
            // Alias state stays empty; each allocation is released exactly once.
            text.append("    ironwood_deallocate(array_state").append(index).append(".converted);\n")
                    .append("    free((void *)array_state").append(index).append(".elements);\n");
        }
        return text.toString();
    }

    static String regionKind(IrType array) {
        String name = BridgeJavaTypes.sourceName(array.elementType());
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
