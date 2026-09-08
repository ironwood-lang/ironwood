// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import java.util.ArrayList;
import java.util.List;

/** Stable pre-order traversal of all top-level and member type declarations. */
public final class DeclaredTypes {
    private DeclaredTypes() {
    }

    public static List<DeclaredType> in(CompilationUnit unit) {
        List<DeclaredType> result = new ArrayList<>();
        for (TypeDeclaration declaration : unit.declarations()) {
            collect(unit, declaration, List.of(), null, null, result);
        }
        return List.copyOf(result);
    }

    private static void collect(CompilationUnit unit, TypeDeclaration declaration,
                                List<TypeDeclaration> enclosingTypes,
                                String enclosingSourceName, String enclosingBinaryName,
                                List<DeclaredType> result) {
        String packagePrefix = unit.packageName().isEmpty() ? "" : unit.packageName() + ".";
        String sourceName = enclosingSourceName == null
                ? packagePrefix + declaration.name()
                : enclosingSourceName + "." + declaration.name();
        String binaryName = enclosingBinaryName == null
                ? packagePrefix + declaration.name()
                : enclosingBinaryName + "$" + declaration.name();
        result.add(new DeclaredType(unit, declaration, enclosingTypes, sourceName, binaryName));
        List<TypeDeclaration> childrenEnclosing = new ArrayList<>(enclosingTypes);
        childrenEnclosing.add(declaration);
        for (TypeDeclaration member : declaration.memberTypes()) {
            collect(unit, member, childrenEnclosing, sourceName, binaryName, result);
        }
    }
}
