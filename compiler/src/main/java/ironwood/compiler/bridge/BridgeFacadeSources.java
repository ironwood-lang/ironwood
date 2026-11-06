// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Concrete Java projections. Each generation route requires its matching complete native adapters. */
public final class BridgeFacadeSources {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n\n";
    private BridgeFacadeSources() {}

    public record Facade(String binaryName, String addressField, String typeNameField, String stateField, String stateType) {
        public String constructorDescriptor() { return "(JL" + stateType.replace('.', '/') + ";Ljava/lang/Void;)V"; }
    }

    public record EnumFacade(String binaryName, String tokenField, List<BridgeEnumConstants.Constant> constants) {
        public EnumFacade { constants = List.copyOf(constants); }
    }

    public record Sources(BridgeJavaSources declarations, List<Facade> facades, List<EnumFacade> enums) {
        public Sources { facades = List.copyOf(facades); enums = List.copyOf(enums); }
        public Sources(BridgeJavaSources declarations, List<Facade> facades) { this(declarations, facades, List.of()); }
    }

    public static Sources generateRoots(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission) || admission.roots().isEmpty())
            throw new IllegalArgumentException("root Java facades require matching final root admission and generation");
        return BridgeGcJavaSources.generate(artifact, admission, generation);
    }

    static void identity(StringBuilder text, BridgeApiFacts.Type type, String address, String typeName, Set<String> occupied, String indent) {
        var inherited = type.callables().stream().filter(method -> method.owner().equals("ironwood.lang.Object"))
                .map(BridgeApiFacts.Callable::name).collect(Collectors.toSet());
        if (inherited.contains("equals")) text.append(indent).append("    @Override public boolean equals(java.lang.Object other) { return this == other; }\n");
        if (!inherited.contains("hashCode") && !inherited.contains("toString")) return;
        String hash = unique(occupied, "$ironwood$identityHash");
        text.append(indent).append("    private int ").append(hash).append("() {\n")
                .append(indent).append("        long mixed = this.").append(address).append(";\n")
                .append(indent).append("        mixed ^= mixed >>> 33; mixed *= 0xff51afd7ed558ccdL;\n")
                .append(indent).append("        mixed ^= mixed >>> 33; mixed *= 0xc4ceb9fe1a85ec53L;\n")
                .append(indent).append("        mixed ^= mixed >>> 33; return (int) (mixed ^ (mixed >>> 32));\n")
                .append(indent).append("    }\n");
        if (inherited.contains("hashCode")) text.append(indent).append("    @Override public int hashCode() { return ").append(hash).append("(); }\n");
        if (inherited.contains("toString")) {
            text.append(indent).append("    @Override public java.lang.String toString() {\n")
                    .append(indent).append("        int value = ").append(hash).append("();\n")
                    .append(indent).append("        char[] digits = new char[8]; int start = 8;\n")
                    .append(indent).append("        do { digits[--start] = \"0123456789abcdef\".charAt(value & 15); value >>>= 4; } while (value != 0);\n")
                    .append(indent).append("        return this.").append(typeName).append(" + \"@\" + new java.lang.String(digits, start, 8 - start);\n")
                    .append(indent).append("    }\n");
        }
    }

    static String unique(Set<String> occupied, String name) {
        while (!occupied.add(name)) name += "$";
        return name;
    }

    static String javaType(IrType type, BridgeExportSurface surface) {
        if (type.isTypeParameter()) return surface.types().stream().flatMap(declaration -> declaration.typeParameters().stream())
                .filter(variable -> variable.id().equals(type.referenceName())).findFirst().orElseThrow().name();
        if (type.isNominalReference()) {
            var declared = surface.types().stream().filter(candidate -> candidate.binaryName().equals(type.referenceName())).findFirst();
            if (declared.isPresent()) return declared.orElseThrow().sourceName() + (type.typeArguments().isEmpty() ? "" : "<"
                    + type.typeArguments().stream().map(argument -> javaType(argument, surface)).collect(Collectors.joining(", ")) + ">");
        }
        return BridgeJavaTypes.sourceName(type);
    }

    static String javaErasedType(IrType type, BridgeExportSurface surface) {
        var erased = type.erasure();
        var declaration = surface.types().stream().filter(candidate -> candidate.binaryName().equals(erased.referenceName())).findFirst();
        return javaType(erased, surface) + declaration.filter(BridgeApiFacts.Type::generic)
                .map(value -> "<" + value.typeParameters().stream().map(ignored -> "?").collect(Collectors.joining(", ")) + ">")
                .orElse("");
    }
}
