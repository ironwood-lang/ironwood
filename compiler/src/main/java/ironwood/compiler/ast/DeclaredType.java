// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import java.util.List;

/** One nominal type declaration together with its lexical and binary identity. */
public record DeclaredType(CompilationUnit unit, TypeDeclaration declaration,
                           List<TypeDeclaration> enclosingTypes,
                           String sourceName, String binaryName) {
    public DeclaredType {
        enclosingTypes = List.copyOf(enclosingTypes);
    }

    public boolean topLevel() {
        return enclosingTypes.isEmpty();
    }

    public String nestHostBinaryName() {
        TypeDeclaration host = enclosingTypes.isEmpty() ? declaration : enclosingTypes.getFirst();
        String packagePrefix = unit.packageName().isEmpty() ? "" : unit.packageName() + ".";
        return packagePrefix + host.name();
    }

    public String enclosingBinaryName() {
        if (enclosingTypes.isEmpty()) {
            return null;
        }
        String packagePrefix = unit.packageName().isEmpty() ? "" : unit.packageName() + ".";
        return packagePrefix + enclosingTypes.stream().map(TypeDeclaration::name)
                .collect(java.util.stream.Collectors.joining("$"));
    }

    public TypeDeclaration enclosingType() {
        return enclosingTypes.isEmpty() ? null : enclosingTypes.getLast();
    }
}
