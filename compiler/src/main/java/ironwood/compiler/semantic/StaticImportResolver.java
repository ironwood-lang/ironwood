// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ImportDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves value and callable members introduced by static import declarations. */
final class StaticImportResolver {
    private final TypeResolver types;
    private final ClassHierarchy hierarchy;

    StaticImportResolver(TypeResolver types, ClassHierarchy hierarchy) {
        this.types = types;
        this.hierarchy = hierarchy;
    }

    List<FieldSymbol> fields(CompilationUnit unit, String name) {
        List<FieldSymbol> singles = fields(unit, name, false);
        return singles.isEmpty() ? fields(unit, name, true) : singles;
    }

    List<CallableSymbol> methods(CompilationUnit unit, String name) {
        List<CallableSymbol> singles = methods(unit, name, false);
        Set<String> shadowedSignatures = singles.stream()
                .map(CallableSymbol::overrideSignatureKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<CallableSymbol> combined = new ArrayList<>(singles);
        methods(unit, name, true).stream()
                .filter(method -> !shadowedSignatures.contains(method.overrideSignatureKey()))
                .forEach(combined::add);
        return distinctMethods(combined);
    }

    List<FieldSymbol> declaredFields(ImportDeclaration imported, CompilationUnit unit) {
        TypeSymbol owner = owner(imported, unit);
        if (owner == null || imported.wildcard()) {
            return List.of();
        }
        return hierarchy.resolveField(owner.selfType(), imported.importedMemberName())
                .candidates().stream().filter(FieldSymbol::isStatic).toList();
    }

    List<CallableSymbol> declaredMethods(ImportDeclaration imported, CompilationUnit unit) {
        TypeSymbol owner = owner(imported, unit);
        if (owner == null || imported.wildcard()) {
            return List.of();
        }
        return hierarchy.lookupMethods(owner.selfType(), imported.importedMemberName()).stream()
                .filter(CallableSymbol::isStatic).toList();
    }

    Set<TypeSymbol> declaredMemberTypes(ImportDeclaration imported, CompilationUnit unit) {
        TypeSymbol owner = owner(imported, unit);
        return owner == null || imported.wildcard() ? Set.of()
                : types.staticMemberTypes(owner, imported.importedMemberName());
    }

    boolean memberAccessible(AccessModifier access, String ownerName, CompilationUnit unit) {
        TypeSymbol owner = hierarchy.type(ownerName).orElse(null);
        if (owner == null) {
            return false;
        }
        return switch (access) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            case PACKAGE_PRIVATE, PROTECTED -> owner.packageName().equals(unit.packageName());
        };
    }

    private List<FieldSymbol> fields(CompilationUnit unit, String name, boolean onDemand) {
        Map<String, FieldSymbol> result = new LinkedHashMap<>();
        for (ImportDeclaration imported : matchingImports(unit, name, onDemand)) {
            TypeSymbol owner = owner(imported, unit);
            if (owner == null) {
                continue;
            }
            for (FieldSymbol field : hierarchy.resolveField(owner.selfType(), name).candidates()) {
                if (field.isStatic() && memberAccessible(field.accessModifier(),
                        field.ownerClass(), unit)) {
                    result.putIfAbsent(field.ownerClass() + "#" + field.declaration().name(), field);
                }
            }
        }
        return List.copyOf(result.values());
    }

    private List<CallableSymbol> methods(CompilationUnit unit, String name, boolean onDemand) {
        List<CallableSymbol> result = new ArrayList<>();
        for (ImportDeclaration imported : matchingImports(unit, name, onDemand)) {
            TypeSymbol owner = owner(imported, unit);
            if (owner == null) {
                continue;
            }
            hierarchy.lookupMethods(owner.selfType(), name).stream()
                    .filter(CallableSymbol::isStatic)
                    .filter(method -> memberAccessible(method.accessModifier(),
                            method.ownerType(), unit))
                    .forEach(result::add);
        }
        return distinctMethods(result);
    }

    private List<ImportDeclaration> matchingImports(CompilationUnit unit, String name,
                                                     boolean onDemand) {
        return unit.imports().stream().filter(ImportDeclaration::staticImport)
                .filter(imported -> imported.wildcard() == onDemand)
                .filter(imported -> onDemand || imported.importedMemberName().equals(name))
                .toList();
    }

    private TypeSymbol owner(ImportDeclaration imported, CompilationUnit unit) {
        TypeResolver.Resolution resolution = types.resolveStaticImportOwner(imported, unit);
        return resolution.ambiguous() || resolution.inaccessible()
                ? null : resolution.type().orElse(null);
    }

    private static List<CallableSymbol> distinctMethods(List<CallableSymbol> methods) {
        Map<String, CallableSymbol> distinct = new LinkedHashMap<>();
        for (CallableSymbol method : methods) {
            distinct.putIfAbsent(method.linkageName(), method);
        }
        return List.copyOf(distinct.values());
    }
}
