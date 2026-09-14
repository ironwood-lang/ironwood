// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The same source prerequisites govern initialization metadata and empty-work elimination. */
final class TypeInitializationAnalysis {
    private TypeInitializationAnalysis() {}

    static List<TypeSymbol> prerequisites(TypeSymbol type) {
        if (type.isInterface()) return List.of();
        List<TypeSymbol> result = new ArrayList<>();
        type.superclass().ifPresent(result::add);
        Set<String> visited = new LinkedHashSet<>();
        for (TypeSymbol direct : type.directInterfaces()) collectDefaults(direct, visited, result);
        return List.copyOf(result);
    }

    private static void collectDefaults(TypeSymbol type, Set<String> visited, List<TypeSymbol> result) {
        if (!visited.add(type.name())) return;
        for (TypeSymbol parent : type.directInterfaces()) collectDefaults(parent, visited, result);
        if (type.declaration() instanceof InterfaceDeclaration declaration
                && declaration.methods().stream().anyMatch(InterfaceMethodDeclaration::isDefault)) {
            result.add(type);
        }
    }

    static boolean requiresWork(TypeSymbol type) {
        return requiresWork(type, new LinkedHashSet<>());
    }

    private static boolean requiresWork(TypeSymbol type, Set<String> visited) {
        if (!visited.add(type.name())) return false;
        if (type.staticInitializer().isPresent()) return true;
        return prerequisites(type).stream().anyMatch(parent -> requiresWork(parent, visited));
    }
}
