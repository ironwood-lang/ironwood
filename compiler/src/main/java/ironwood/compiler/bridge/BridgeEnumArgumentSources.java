// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Paired Java enum carriers; protected native entries still own initialization. */
final class BridgeEnumArgumentSources {
    private BridgeEnumArgumentSources() {}

    record Parameters(List<String> nativeFormals, List<String> arguments, String descriptor, Set<Integer> tokens) {
        Parameters {
            nativeFormals = List.copyOf(nativeFormals); arguments = List.copyOf(arguments); tokens = Set.copyOf(tokens);
        }
    }

    static Parameters generate(StringBuilder text, CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgeApiFacts.Callable method, Set<String> occupied, String indent) {
        var formals = new ArrayList<String>(); var arguments = new ArrayList<String>();
        var descriptor = new StringBuilder(); var tokens = new HashSet<Integer>();
        for (int index = 0; index < method.parameters().size(); index++) {
            var type = method.parameters().get(index); String name = method.parameterNames().get(index);
            String sourceType = BridgePermanentJavaSources.javaType(type, admission.surface());
            boolean enumeration = type.isNominalReference() && admission.surface().types().stream()
                    .anyMatch(candidate -> candidate.binaryName().equals(type.referenceName()) && candidate.kind() == BridgeApiFacts.Kind.ENUM);
            if (!enumeration) {
                formals.add((BridgeGenericDomain.dependent(type) ? BridgePermanentJavaSources.javaErasedType(type, admission.surface())
                        : sourceType) + " " + name);
                arguments.add(name); descriptor.append(BridgeJavaTypes.descriptor(type)); continue;
            }
            var constants = BridgeEnumConstants.discover(artifact, Set.of(type)).constants().get(type);
            String conversion = BridgePermanentJavaSources.unique(occupied, "$ironwood$tokenArgument");
            text.append(indent).append("    private static int ").append(conversion).append('(').append(sourceType).append(" value) {\n")
                    .append(indent).append("        if (value == null) return -1;\n");
            if (constants.isEmpty()) text.append(indent).append("        throw new java.lang.AssertionError(\"empty generated enum\");\n");
            else {
                // Tokens are assigned by name, not necessarily by declaration ordinal.
                text.append(indent).append("        return switch (value.ordinal()) {\n");
                for (int ordinal = 0; ordinal < constants.size(); ordinal++) text.append(indent).append("            case ").append(ordinal)
                        .append(" -> ").append(constants.get(ordinal).token()).append(";\n");
                text.append(indent).append("            default -> throw new java.lang.AssertionError(\"invalid generated enum ordinal\");\n")
                        .append(indent).append("        };\n");
            }
            text.append(indent).append("    }\n");
            formals.add("int " + name); arguments.add(conversion + "(" + name + ")"); descriptor.append('I'); tokens.add(index);
        }
        return new Parameters(formals, arguments, descriptor.toString(), tokens);
    }
}
