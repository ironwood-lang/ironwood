// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ImportDeclaration;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TypeResolver {
    private final Map<String, TypeSymbol> types;
    private final Map<String, TypeSymbol> sourceTypes;
    private final LexicalTypeScopes lexicalTypes;

    TypeResolver(Map<String, TypeSymbol> types) {
        this(types, LexicalTypeScopes.empty());
    }

    TypeResolver(Map<String, TypeSymbol> types, LexicalTypeScopes lexicalTypes) {
        this.types = types;
        this.lexicalTypes = lexicalTypes;
        Map<String, TypeSymbol> bySource = new java.util.LinkedHashMap<>();
        types.values().stream().filter(type -> !type.isLexicallyScoped())
                .forEach(type -> bySource.putIfAbsent(type.sourceName(), type));
        this.sourceTypes = Map.copyOf(bySource);
    }

    Resolution resolve(String name, CompilationUnit unit) {
        return resolve(name, unit, null);
    }

    Resolution resolve(String name, TypeSymbol context) {
        return resolve(name, context, context.declaration().nameSpan());
    }

    Resolution resolve(String name, TypeSymbol context, SourceSpan useSpan) {
        return resolve(name, context.unit(), context, useSpan);
    }

    private Resolution resolve(String name, CompilationUnit unit, TypeSymbol context) {
        return resolve(name, unit, context,
                context == null ? null : context.declaration().nameSpan());
    }

    private Resolution resolve(String name, CompilationUnit unit, TypeSymbol context,
                               SourceSpan useSpan) {
        if (context != null && name.indexOf('.') < 0) {
            for (TypeSymbol lexical = context; lexical != null;
                 lexical = lexical.enclosingType().orElse(null)) {
                if (lexical.simpleName().equals(name)) {
                    return accessible(lexical, unit, context);
                }
            }
            Optional<TypeSymbol> local = lexicalTypes.resolve(name, context, useSpan);
            if (local.isPresent()) {
                return accessible(local.orElseThrow(), unit, context);
            }
            for (TypeSymbol lexical = context; lexical != null;
                 lexical = lexical.enclosingType().orElse(null)) {
                Set<TypeSymbol> members = memberTypes(lexical, name);
                if (members.size() == 1) {
                    return accessible(members.iterator().next(), unit, context);
                }
                if (members.size() > 1) {
                    return Resolution.ambiguous(name, new ArrayList<>(members));
                }
            }
        }

        if (name.indexOf('.') >= 0) {
            LinkedHashSet<TypeSymbol> qualified = new LinkedHashSet<>();
            if (context != null) {
                String first = name.substring(0, name.indexOf('.'));
                Optional<TypeSymbol> lexical = lexicalTypes.resolve(first, context, useSpan);
                if (lexical.isPresent()) {
                    Set<TypeSymbol> current = Set.of(lexical.orElseThrow());
                    for (String segment : name.substring(name.indexOf('.') + 1).split("\\.")) {
                        Set<TypeSymbol> next = new LinkedHashSet<>();
                        for (TypeSymbol candidate : current) {
                            next.addAll(memberTypes(candidate, segment));
                        }
                        current = next;
                        if (current.isEmpty()) {
                            break;
                        }
                    }
                    qualified.addAll(current);
                }
            }
            addQualified(qualified, name);
            addQualified(qualified, unit.packageName().isEmpty() ? name : unit.packageName() + "." + name);
            String first = name.substring(0, name.indexOf('.'));
            String suffix = name.substring(name.indexOf('.'));
            unit.imports().stream().filter(imported -> !imported.staticImport())
                    .filter(imported -> !imported.wildcard())
                    .filter(imported -> imported.importedSimpleName().equals(first))
                    .forEach(imported -> addQualified(qualified, imported.name() + suffix));
            unit.imports().stream().filter(imported -> !imported.staticImport())
                    .filter(ImportDeclaration::wildcard)
                    .forEach(imported -> addQualified(qualified, imported.name() + "." + name));
            for (ImportDeclaration imported : unit.imports()) {
                if (!imported.staticImport()
                        || !imported.wildcard()
                        && !imported.importedMemberName().equals(first)) {
                    continue;
                }
                Resolution owner = resolveStaticImportOwner(imported, unit);
                if (owner.type().isEmpty()) {
                    continue;
                }
                Set<TypeSymbol> current = staticMemberTypes(owner.type().orElseThrow(), first);
                for (String segment : name.substring(name.indexOf('.') + 1).split("\\.")) {
                    Set<TypeSymbol> next = new LinkedHashSet<>();
                    for (TypeSymbol candidate : current) {
                        next.addAll(memberTypes(candidate, segment));
                    }
                    current = next;
                    if (current.isEmpty()) {
                        break;
                    }
                }
                qualified.addAll(current);
            }
            if (qualified.size() == 1) {
                return accessible(qualified.iterator().next(), unit, context);
            }
            if (qualified.size() > 1) {
                return Resolution.ambiguous(name, new ArrayList<>(qualified));
            }
            return Resolution.missing(name);
        }

        String currentPackageName = unit.packageName().isEmpty() ? name : unit.packageName() + "." + name;
        TypeSymbol currentPackageType = types.get(currentPackageName);
        boolean declaredInUnit = unit.declarations().stream()
                .anyMatch(declaration -> declaration.name().equals(name));
        if (declaredInUnit && currentPackageType != null) {
            return accessible(currentPackageType, unit, context);
        }

        LinkedHashSet<TypeSymbol> singleImportMatches = new LinkedHashSet<>(unit.imports().stream()
                .filter(imported -> !imported.staticImport())
                .filter(imported -> !imported.wildcard())
                .filter(imported -> imported.importedSimpleName().equals(name))
                .map(imported -> sourceTypes.getOrDefault(imported.name(), types.get(imported.name())))
                .filter(java.util.Objects::nonNull)
                .toList());
        for (ImportDeclaration imported : unit.imports()) {
            if (!imported.staticImport() || imported.wildcard()
                    || !imported.importedMemberName().equals(name)) {
                continue;
            }
            resolveStaticImportOwner(imported, unit).type()
                    .ifPresent(owner -> singleImportMatches.addAll(staticMemberTypes(owner, name)));
        }
        List<TypeSymbol> singleImports = List.copyOf(singleImportMatches);
        if (singleImports.size() == 1) {
            return accessible(singleImports.getFirst(), unit, context);
        }
        if (singleImports.size() > 1) {
            return Resolution.ambiguous(name, singleImports);
        }

        if (currentPackageType != null) {
            return accessible(currentPackageType, unit, context);
        }

        TypeSymbol implicitCoreType = types.get("ironwood.lang." + name);
        if (implicitCoreType != null) {
            return accessible(implicitCoreType, unit, context);
        }

        Set<TypeSymbol> wildcardMatches = new LinkedHashSet<>();
        for (ImportDeclaration imported : unit.imports()) {
            if (!imported.wildcard() || imported.staticImport()) {
                continue;
            }
            TypeSymbol match = types.get(imported.name() + "." + name);
            if (match != null) {
                wildcardMatches.add(match);
            }
        }
        for (ImportDeclaration imported : unit.imports()) {
            if (!imported.staticImport() || !imported.wildcard()) {
                continue;
            }
            resolveStaticImportOwner(imported, unit).type()
                    .ifPresent(owner -> wildcardMatches.addAll(staticMemberTypes(owner, name)));
        }
        if (wildcardMatches.size() == 1) {
            return accessible(wildcardMatches.iterator().next(), unit, context);
        }
        if (wildcardMatches.size() > 1) {
            return Resolution.ambiguous(name, new ArrayList<>(wildcardMatches));
        }
        return Resolution.missing(name);
    }

    Optional<TypeSymbol> lexicalTypeFor(Object astNode) {
        return lexicalTypes.typeFor(astNode);
    }

    Optional<TypeSymbol> lexicalTypeAt(SourceFile source, SourceSpan span) {
        return lexicalTypes.typeAt(source, span);
    }

    Optional<LocalClassSemantics.VariableIdentity> lexicalVariable(
            String name, TypeSymbol context, SourceSpan useSpan) {
        return lexicalTypes.resolveVariable(name, context, useSpan);
    }

    Optional<TypeSymbol> resolveIfUnique(String name, CompilationUnit unit) {
        Resolution resolution = resolve(name, unit);
        return resolution.ambiguous() ? Optional.empty() : resolution.type();
    }

    Resolution resolveStaticImportOwner(ImportDeclaration imported, CompilationUnit unit) {
        LinkedHashSet<TypeSymbol> matches = new LinkedHashSet<>();
        if (!imported.ownerName().isEmpty()) {
            addQualified(matches, imported.ownerName());
        }
        if (matches.size() == 1) {
            return accessible(matches.iterator().next(), unit, null);
        }
        if (matches.size() > 1) {
            return Resolution.ambiguous(imported.ownerName(), new ArrayList<>(matches));
        }
        return Resolution.missing(imported.ownerName());
    }

    Set<TypeSymbol> staticMemberTypes(TypeSymbol owner, String name) {
        Set<TypeSymbol> result = new LinkedHashSet<>();
        for (TypeSymbol member : memberTypes(owner, name)) {
            if (member.isStaticMember()) {
                result.add(member);
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    Resolution accessibility(TypeSymbol type, CompilationUnit unit) {
        return accessible(type, unit, null);
    }

    Optional<IrType> enclosingTypeView(TypeSymbol context, TypeSymbol memberType) {
        TypeSymbol expected = memberType.enclosingType().orElse(null);
        if (expected == null) {
            return Optional.empty();
        }
        for (TypeSymbol lexical = context; lexical != null;
             lexical = lexical.enclosingType().orElse(null)) {
            Optional<IrType> projected = exactClassSupertype(lexical.selfType(), expected);
            if (projected.isPresent()) {
                return projected;
            }
            if (lexical.isStaticMember()) {
                break;
            }
        }
        return Optional.empty();
    }

    Optional<IrType> exactClassSupertype(IrType actual, TypeSymbol expected) {
        IrType current = actual;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && current.isNominalReference()
                && visited.add(current.referenceName())) {
            if (current.referenceName().equals(expected.name())) {
                return Optional.of(current);
            }
            TypeSymbol symbol = types.get(current.referenceName());
            if (symbol == null) {
                break;
            }
            Map<String, IrType> substitution = symbol.substitutionFor(current);
            current = symbol.superclassType()
                    .map(parent -> parent.substitute(substitution))
                    .orElse(null);
        }
        return Optional.empty();
    }

    private void addQualified(Set<TypeSymbol> matches, String sourceName) {
        TypeSymbol exact = types.get(sourceName);
        if (exact != null) {
            matches.add(exact);
        }
        TypeSymbol source = sourceTypes.get(sourceName);
        if (source != null) {
            matches.add(source);
        }
        int separator = sourceName.lastIndexOf('.');
        while (separator >= 0) {
            String ownerName = sourceName.substring(0, separator);
            TypeSymbol owner = sourceTypes.getOrDefault(ownerName, types.get(ownerName));
            if (owner != null) {
                Set<TypeSymbol> current = Set.of(owner);
                String[] segments = sourceName.substring(separator + 1).split("\\.");
                for (String segment : segments) {
                    Set<TypeSymbol> next = new LinkedHashSet<>();
                    for (TypeSymbol candidate : current) {
                        next.addAll(memberTypes(candidate, segment));
                    }
                    current = next;
                    if (current.isEmpty()) {
                        break;
                    }
                }
                matches.addAll(current);
            }
            separator = sourceName.lastIndexOf('.', separator - 1);
        }
    }

    private static Set<TypeSymbol> memberTypes(TypeSymbol owner, String name) {
        return memberTypes(owner, owner, name, true, new LinkedHashSet<>());
    }

    private static Set<TypeSymbol> memberTypes(TypeSymbol owner, TypeSymbol receiver,
                                               String name, boolean direct,
                                               Set<TypeSymbol> visited) {
        if (!visited.add(owner)) {
            return Set.of();
        }
        TypeSymbol declared = owner.declaredMemberType(name).orElse(null);
        if (declared != null && (direct || isInheritedBy(declared, receiver))) {
            return Set.of(declared);
        }
        Set<TypeSymbol> fromClass = owner.superclass()
                .map(parent -> memberTypes(parent, receiver, name, false, visited))
                .orElse(Set.of());
        if (!fromClass.isEmpty()) {
            return fromClass;
        }
        Set<TypeSymbol> candidates = new LinkedHashSet<>();
        for (TypeSymbol implemented : owner.directInterfaces()) {
            candidates.addAll(memberTypes(implemented, receiver, name, false, visited));
        }
        Set<TypeSymbol> maximal = new LinkedHashSet<>();
        for (TypeSymbol candidate : candidates) {
            TypeSymbol candidateOwner = candidate.enclosingType().orElse(null);
            boolean hidden = candidates.stream().anyMatch(other -> other != candidate
                    && candidateOwner != null
                    && other.enclosingType().map(otherOwner -> isSubtype(otherOwner, candidateOwner))
                    .orElse(false));
            if (!hidden) {
                maximal.add(candidate);
            }
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(maximal));
    }

    private static boolean isInheritedBy(TypeSymbol member, TypeSymbol subtype) {
        return switch (member.declaration().accessModifier()) {
            case PRIVATE -> false;
            case PACKAGE_PRIVATE -> member.packageName().equals(subtype.packageName());
            case PROTECTED, PUBLIC -> true;
        };
    }

    private static Resolution accessible(TypeSymbol type, CompilationUnit unit,
                                         TypeSymbol context) {
        boolean accessible = true;
        for (TypeSymbol current = type; current != null;
             current = current.enclosingType().orElse(null)) {
            if (!directlyAccessible(current, unit, context)) {
                accessible = false;
                break;
            }
        }
        return new Resolution(Optional.of(type), false, !accessible, List.of(type), type.simpleName());
    }

    private static boolean directlyAccessible(TypeSymbol type, CompilationUnit unit,
                                              TypeSymbol context) {
        var access = type.declaration().accessModifier();
        return access == ironwood.compiler.ast.AccessModifier.PUBLIC
                || access == ironwood.compiler.ast.AccessModifier.PRIVATE
                && context != null && type.sameNest(context)
                || access != ironwood.compiler.ast.AccessModifier.PRIVATE
                && type.packageName().equals(unit.packageName())
                || access == ironwood.compiler.ast.AccessModifier.PROTECTED
                && context != null && type.enclosingType().isPresent()
                && isSubclass(context, type.enclosingType().orElseThrow());
    }

    private static boolean isSubclass(TypeSymbol possibleSubclass, TypeSymbol target) {
        for (TypeSymbol current = possibleSubclass; current != null;
             current = current.superclass().orElse(null)) {
            if (current.name().equals(target.name())) {
                return true;
            }
        }
        return possibleSubclass.enclosingType()
                .map(enclosing -> isSubclass(enclosing, target)).orElse(false);
    }

    private static boolean isSubtype(TypeSymbol actual, TypeSymbol expected) {
        return isSubtype(actual, expected, new LinkedHashSet<>());
    }

    private static boolean isSubtype(TypeSymbol actual, TypeSymbol expected,
                                     Set<TypeSymbol> visited) {
        if (actual == null || expected == null || !visited.add(actual)) {
            return false;
        }
        if (actual.name().equals(expected.name())) {
            return true;
        }
        if (actual.superclass().map(parent -> isSubtype(parent, expected, visited)).orElse(false)) {
            return true;
        }
        return actual.directInterfaces().stream()
                .anyMatch(parent -> isSubtype(parent, expected, visited));
    }

    record Resolution(Optional<TypeSymbol> type, boolean ambiguous, boolean inaccessible,
                      List<TypeSymbol> candidates, String requestedName) {
        Resolution {
            type = type == null ? Optional.empty() : type;
            candidates = List.copyOf(candidates);
        }

        private static Resolution missing(String name) {
            return new Resolution(Optional.empty(), false, false, List.of(), name);
        }

        private static Resolution ambiguous(String name, List<TypeSymbol> candidates) {
            return new Resolution(Optional.empty(), true, false, candidates, name);
        }
    }
}
