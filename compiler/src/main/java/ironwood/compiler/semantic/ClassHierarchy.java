// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrDispatchSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ClassHierarchy {
    private static final String ROOT_OBJECT = "ironwood.lang.Object";

    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final GenericTypeSystem genericTypes;
    private Map<String, IrDispatchSlot> dispatchSlots = Map.of();
    private boolean declaredDispatchReady;
    private final Map<DeclaredDispatchKey, Optional<CallableSymbol>> declaredDispatch = new LinkedHashMap<>();

    private record DeclaredDispatchKey(TypeSymbol type, String dispatchKey) { }

    ClassHierarchy(Map<String, TypeSymbol> types, TypeResolver resolver,
                   GenericTypeSystem genericTypes) {
        this.types = types;
        this.resolver = resolver;
        this.genericTypes = genericTypes;
    }

    Map<String, TypeSymbol> types() {
        return types;
    }

    TypeResolver typeResolver() {
        return resolver;
    }

    Optional<TypeSymbol> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    TypeResolver.Resolution resolveType(String name, TypeSymbol context) {
        return resolver.resolve(name, context);
    }

    TypeResolver.Resolution resolveType(String name, TypeSymbol context,
                                        ironwood.compiler.source.SourceSpan useSpan) {
        return resolver.resolve(name, context, useSpan);
    }

    Optional<TypeSymbol> lexicalTypeFor(Object astNode) {
        return resolver.lexicalTypeFor(astNode);
    }

    Optional<TypeSymbol> lexicalTypeAt(ironwood.compiler.source.SourceFile source,
                                       ironwood.compiler.source.SourceSpan span) {
        return resolver.lexicalTypeAt(source, span);
    }

    Optional<LocalClassSemantics.VariableIdentity> lexicalVariable(
            String name, TypeSymbol context,
            ironwood.compiler.source.SourceSpan useSpan) {
        return resolver.lexicalVariable(name, context, useSpan);
    }

    Optional<IrType> enclosingTypeView(TypeSymbol context, TypeSymbol memberType) {
        return resolver.enclosingTypeView(context, memberType);
    }

    Optional<IrType> exactClassSupertype(IrType actual, TypeSymbol expected) {
        return resolver.exactClassSupertype(actual, expected);
    }

    List<IrType> upperBounds(IrType variable) {
        return genericTypes.upperBounds(variable);
    }

    IrType captureReceiver(IrType receiver, String siteId) {
        return genericTypes.captureReceiver(receiver, siteId);
    }

    GenericTypeSystem.CaptureConversion planCaptureReceiver(IrType receiver, String siteId) {
        return genericTypes.planCaptureReceiver(receiver, siteId);
    }

    void registerCaptures(Map<String, TypeVariableSymbol> captures) {
        genericTypes.registerCaptures(captures);
    }

    void validateInstantiation(TypeSymbol target, List<IrType> arguments,
                               ironwood.compiler.source.SourceFile source,
                               ironwood.compiler.source.SourceSpan span,
                               List<ironwood.compiler.diagnostic.Diagnostic> diagnostics) {
        genericTypes.validateInstantiation(target, arguments, source, span, diagnostics);
    }

    boolean sameNest(String left, String right) {
        TypeSymbol leftType = types.get(left);
        TypeSymbol rightType = types.get(right);
        return leftType != null && leftType.sameNest(rightType);
    }

    boolean isSubtype(String actual, String expected) {
        return isSubtype(actual, expected, new LinkedHashSet<>());
    }

    private boolean isSubtype(String actual, String expected, Set<String> visited) {
        if (actual.equals(expected)) {
            return true;
        }
        if (!visited.add(actual)) {
            return false;
        }
        TypeSymbol actualType = types.get(actual);
        TypeSymbol expectedType = types.get(expected);
        if (actualType == null || expectedType == null) {
            return false;
        }
        if (expected.equals(ROOT_OBJECT)) {
            return true;
        }
        if (!actualType.isInterface()) {
            if (actualType.superclass().isPresent()
                    && isSubtype(actualType.superclass().orElseThrow().name(), expected, visited)) {
                return true;
            }
            return actualType.interfaceClosure().contains(expectedType);
        }
        return actualType.interfaceClosure().contains(expectedType);
    }

    boolean isAssignable(IrType expected, IrType actual) {
        return isAssignable(expected, actual, Map.of());
    }

    boolean isAssignable(IrType expected, IrType actual,
                         Map<String, TypeVariableSymbol> ambientVariables) {
        // A capture of '?' is readable as Object but has no statically known
        // non-null inhabitant. Keeping this check before equality prevents a
        // value read through one wildcard capture from being written back.
        if (expected.isWildcard()) {
            return actual.equals(IrType.NULL);
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (PrimitiveConversions.canWiden(expected, actual)) {
            return true;
        }
        if (expected.isReference() && actual.equals(IrType.NULL)) {
            return true;
        }
        if (expected.isNominalReference() && expected.referenceName().equals(ROOT_OBJECT)
                && actual.isReference()) {
            return true;
        }
        if (expected.isTypeParameter()) {
            TypeVariableSymbol ambient = ambientVariables.get(expected.referenceName());
            if (ambient != null && ambient.kind() == TypeVariableSymbol.Kind.CAPTURE) {
                return ambient.lowerBound().isPresent()
                        && isAssignable(ambient.lowerBound().orElseThrow(), actual,
                        ambientVariables);
            }
            if (genericTypes.isCapture(expected)) {
                return genericTypes.acceptsCapturedWrite(expected, actual);
            }
            return actual.isTypeParameter() && isSubtype(actual, expected, ambientVariables);
        }
        if (actual.isTypeParameter()) {
            return upperBounds(actual, ambientVariables).stream()
                    .anyMatch(bound -> isSubtype(bound, expected, ambientVariables));
        }
        if (actual.isWildcard()) {
            return expected.equals(actual);
        }
        return expected.isNominalReference() && actual.isNominalReference()
                && isSubtype(actual, expected, ambientVariables);
    }

    boolean isSubtype(IrType actual, IrType expected) {
        return isSubtype(actual, expected, Map.of());
    }

    boolean isSubtype(IrType actual, IrType expected,
                      Map<String, TypeVariableSymbol> ambientVariables) {
        return isSubtype(actual, expected, ambientVariables, new LinkedHashSet<>());
    }

    private boolean isSubtype(IrType actual, IrType expected,
                              Map<String, TypeVariableSymbol> ambientVariables,
                              Set<String> visited) {
        if (actual.equals(expected)) {
            return true;
        }
        if (expected.isNominalReference() && expected.referenceName().equals(ROOT_OBJECT)
                && actual.isReference()) {
            return true;
        }
        if (expected.isTypeParameter()) {
            TypeVariableSymbol ambient = ambientVariables.get(expected.referenceName());
            if (ambient != null && ambient.kind() == TypeVariableSymbol.Kind.CAPTURE
                    && ambient.lowerBound().isPresent()) {
                return isSubtype(actual, ambient.lowerBound().orElseThrow(), ambientVariables,
                        new LinkedHashSet<>(visited));
            }
            return actual.equals(expected) || actual.isTypeParameter()
                    && upperBounds(actual, ambientVariables).stream()
                    .anyMatch(bound -> isSubtype(bound, expected,
                            ambientVariables, new LinkedHashSet<>(visited)));
        }
        if (actual.isTypeParameter()) {
            return upperBounds(actual, ambientVariables).stream()
                    .anyMatch(bound -> isSubtype(bound, expected, ambientVariables,
                            new LinkedHashSet<>(visited)));
        }
        if (!actual.isNominalReference() || !expected.isNominalReference()
                || !visited.add(actual.referenceName())) {
            return false;
        }
        if (actual.referenceName().equals(expected.referenceName())) {
            return genericTypes.compatibleTypeArguments(actual, expected);
        }
        for (IrType parent : directParents(actual)) {
            if (isSubtype(parent, expected, ambientVariables, visited)) {
                return true;
            }
        }
        return false;
    }

    private List<IrType> upperBounds(IrType variable,
                                     Map<String, TypeVariableSymbol> ambientVariables) {
        TypeVariableSymbol ambient = ambientVariables.get(variable.referenceName());
        return ambient == null ? genericTypes.upperBounds(variable) : ambient.upperBounds();
    }

    Optional<FieldSymbol> lookupField(String className, String fieldName) {
        TypeSymbol symbol = types.get(className);
        return symbol == null ? Optional.empty() : lookupField(symbol.selfType(), fieldName);
    }

    Optional<FieldSymbol> lookupField(IrType receiverType, String fieldName) {
        return resolveField(receiverType, fieldName).selected();
    }

    FieldResolution resolveField(IrType receiverType, String fieldName) {
        if (receiverType.isTypeParameter()) {
            List<FieldSymbol> candidates = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (IrType bound : genericTypes.upperBounds(receiverType)) {
                FieldResolution resolution = resolveField(bound, fieldName);
                resolution.candidates().stream()
                        .filter(candidate -> seen.add(candidate.ownerClass() + "#"
                                + candidate.declaration().name()))
                        .forEach(candidates::add);
            }
            List<FieldSymbol> maximal = candidates.stream().filter(candidate -> candidates.stream()
                    .noneMatch(other -> !other.ownerClass().equals(candidate.ownerClass())
                            && isSubtype(other.ownerClass(), candidate.ownerClass()))).toList();
            return new FieldResolution(maximal.size() == 1 ? Optional.of(maximal.getFirst())
                    : Optional.empty(), maximal);
        }
        IrType exactType = receiverType;
        TypeSymbol receiver = receiverType.isNominalReference()
                ? types.get(receiverType.referenceName()) : null;
        FieldSymbol inaccessibleClassField = null;
        boolean first = true;
        Set<String> visited = new LinkedHashSet<>();
        while (exactType != null && exactType.isNominalReference()
                && visited.add(exactType.referenceName())) {
            TypeSymbol type = types.get(exactType.referenceName());
            if (type == null || type.isInterface()) {
                break;
            }
            FieldSymbol field = type.declaredFields().get(fieldName);
            if (field != null) {
                FieldSymbol selected = field.substitute(type.substitutionFor(exactType));
                if (first || receiver != null && isFieldInheritedBy(field, receiver)) {
                    return new FieldResolution(Optional.of(selected), List.of(selected));
                }
                // A non-inherited class field stops the class-chain search, but it does not
                // mask a field inherited independently from a superinterface. Retain it only
                // as a fallback so callers still issue the useful access diagnostic when no
                // interface contributes the requested name.
                inaccessibleClassField = selected;
                break;
            }
            exactType = superclassType(exactType).orElse(null);
            first = false;
        }
        List<FieldSymbol> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IrType supertype : exactSupertypes(receiverType)) {
            TypeSymbol owner = types.get(supertype.referenceName());
            if (owner == null || !owner.isInterface()) {
                continue;
            }
            FieldSymbol field = owner.declaredFields().get(fieldName);
            if (field != null && seen.add(owner.name())) {
                candidates.add(field.substitute(owner.substitutionFor(supertype)));
            }
        }
        List<FieldSymbol> maximal = candidates.stream().filter(candidate -> candidates.stream()
                .noneMatch(other -> !other.ownerClass().equals(candidate.ownerClass())
                        && isSubtype(other.ownerClass(), candidate.ownerClass()))).toList();
        if (maximal.isEmpty() && inaccessibleClassField != null) {
            return new FieldResolution(Optional.of(inaccessibleClassField),
                    List.of(inaccessibleClassField));
        }
        return new FieldResolution(maximal.size() == 1 ? Optional.of(maximal.getFirst())
                : Optional.empty(), maximal);
    }

    record FieldResolution(Optional<FieldSymbol> selected, List<FieldSymbol> candidates) {
        boolean ambiguous() {
            return selected.isEmpty() && candidates.size() > 1;
        }
    }

    List<CallableSymbol> lookupMethods(String typeName, String methodName) {
        TypeSymbol type = types.get(typeName);
        return type == null ? List.of() : lookupMethods(type.selfType(), methodName);
    }

    List<CallableSymbol> lookupMethods(IrType receiverType, String methodName) {
        return lookupMethods(receiverType, methodName, Map.of());
    }

    List<CallableSymbol> lookupMethods(IrType receiverType, String methodName,
                                       Map<String, TypeVariableSymbol> ambientVariables) {
        Map<String, CallableSymbol> methods = new LinkedHashMap<>();
        if (receiverType.isTypeParameter()) {
            List<CallableSymbol> candidates = upperBounds(receiverType, ambientVariables).stream()
                    .flatMap(bound -> lookupMethods(bound, methodName, ambientVariables).stream())
                    .toList();
            for (CallableSymbol candidate : candidates) {
                CallableSymbol existing = methods.get(candidate.overrideSignatureKey());
                if (existing == null) {
                    methods.put(candidate.overrideSignatureKey(), candidate);
                    continue;
                }
                TypeSymbol existingOwner = types.get(existing.ownerType());
                TypeSymbol candidateOwner = types.get(candidate.ownerType());
                boolean candidateMoreSpecific = !existing.ownerType().equals(candidate.ownerType())
                        && isSubtype(candidate.ownerType(), existing.ownerType());
                boolean candidateClassDominates = existingOwner != null && existingOwner.isInterface()
                        && candidateOwner != null && !candidateOwner.isInterface();
                boolean existingClassDominates = existingOwner != null && !existingOwner.isInterface()
                        && candidateOwner != null && candidateOwner.isInterface();
                boolean candidateReturnNarrower = isAssignable(existing.returnType(),
                        candidate.returnType(), ambientVariables);
                boolean existingReturnNarrower = isAssignable(candidate.returnType(),
                        existing.returnType(), ambientVariables);
                if (candidateMoreSpecific || candidateClassDominates
                        || !existingClassDominates && candidateReturnNarrower
                        && !existingReturnNarrower) {
                    methods.put(candidate.overrideSignatureKey(), candidate);
                } else if (!existingClassDominates && !candidateClassDominates
                        && !candidateMoreSpecific
                        && !isSubtype(existing.ownerType(), candidate.ownerType())
                        && !candidateReturnNarrower && !existingReturnNarrower) {
                    methods.put(candidate.overrideSignatureKey() + "@" + candidate.ownerType(), candidate);
                }
            }
            return List.copyOf(methods.values());
        }
        if (!receiverType.isNominalReference()) {
            return List.of();
        }
        TypeSymbol receiver = types.get(receiverType.referenceName());
        if (receiver == null) {
            return List.of();
        }
        if (receiver.isInterface()) {
            for (CallableSymbol declared : lookupDeclaredMethods(receiverType, methodName)) {
                if (declared.isStatic()
                        || declared.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE) {
                    methods.put(declared.overrideSignatureKey() + "@" + declared.linkageName(), declared);
                }
            }
        } else {
            collectClassMethods(receiverType, methodName, methods, true);
        }
        Map<String, List<CallableSymbol>> interfaceMethods = maximallySpecificInterfaceMethods(
                receiverType, methodName).stream().collect(java.util.stream.Collectors.groupingBy(
                        CallableSymbol::overrideSignatureKey, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (Map.Entry<String, List<CallableSymbol>> entry : interfaceMethods.entrySet()) {
            if (methods.containsKey(entry.getKey())) {
                continue;
            }
            List<CallableSymbol> defaults = entry.getValue().stream()
                    .filter(this::isDefaultMethod).toList();
            CallableSymbol selected = defaults.size() == 1
                    ? defaults.getFirst() : entry.getValue().getFirst();
            methods.put(entry.getKey(), selected);
        }
        if (receiver.isInterface() && !receiver.name().equals(ROOT_OBJECT)) {
            collectClassMethods(IrType.reference(ROOT_OBJECT), methodName, methods, false);
        }
        return List.copyOf(methods.values());
    }

    List<CallableSymbol> lookupDeclaredMethods(IrType exactType, String methodName) {
        if (!exactType.isNominalReference()) {
            return List.of();
        }
        TypeSymbol type = types.get(exactType.referenceName());
        if (type == null) {
            return List.of();
        }
        Map<String, IrType> substitution = type.substitutionFor(exactType);
        return type.declaredMethodsNamed(methodName).stream()
                .map(method -> method.substitute(substitution)).toList();
    }

    private void collectClassMethods(IrType exactType, String methodName,
                                     Map<String, CallableSymbol> methods,
                                     boolean includePrivate) {
        IrType currentType = exactType;
        boolean first = true;
        Set<String> visited = new LinkedHashSet<>();
        while (currentType.isNominalReference() && visited.add(currentType.referenceName())) {
            TypeSymbol type = types.get(currentType.referenceName());
            if (type == null || type.isInterface()) {
                return;
            }
            Map<String, IrType> substitution = type.substitutionFor(currentType);
            for (CallableSymbol declared : type.declaredMethodsNamed(methodName)) {
                if ((first && includePrivate) || isInheritedBy(declared, types.get(exactType.referenceName()))) {
                    CallableSymbol view = declared.substitute(substitution);
                    CallableSymbol existing = methods.putIfAbsent(view.overrideSignatureKey(), view);
                    if (existing != null && existing.ownerType().equals(view.ownerType())
                            && !existing.linkageName().equals(view.linkageName())) {
                        methods.put(view.overrideSignatureKey() + "@" + view.linkageName(), view);
                    }
                }
            }
            currentType = superclassType(currentType).orElse(null);
            if (currentType == null) {
                return;
            }
            first = false;
        }
    }

    List<CallableSymbol> maximallySpecificInterfaceMethods(IrType receiverType, String methodName) {
        List<CallableSymbol> declarations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IrType exactType : exactSupertypes(receiverType)) {
            TypeSymbol owner = types.get(exactType.referenceName());
            if (owner == null || !owner.isInterface()) {
                continue;
            }
            for (CallableSymbol method : lookupDeclaredMethods(exactType, methodName)) {
                if (method.isStatic()
                        || method.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE) {
                    continue;
                }
                String identity = method.ownerType() + "#" + method.overrideSignatureKey();
                if (seen.add(identity)) {
                    declarations.add(method);
                }
            }
        }
        return declarations.stream().filter(candidate -> declarations.stream().noneMatch(other ->
                !other.ownerType().equals(candidate.ownerType())
                        && other.overrideSignatureKey().equals(candidate.overrideSignatureKey())
                        && isSubtype(other.ownerType(), candidate.ownerType()))).toList();
    }

    private boolean isDefaultMethod(CallableSymbol method) {
        TypeSymbol owner = types.get(method.ownerType());
        return owner != null && owner.isInterface() && !method.isStatic() && !method.isAbstract()
                && method.accessModifier() == ironwood.compiler.ast.AccessModifier.PUBLIC;
    }

    Optional<CallableSymbol> lookupSuperclassMethod(TypeSymbol type, CallableSymbol method) {
        return superclassType(type.selfType()).stream()
                .flatMap(parent -> lookupMethods(parent, method.sourceName()).stream())
                .filter(candidate -> candidate.overrideSignatureKey()
                        .equals(method.overrideSignatureKey()))
                .filter(candidate -> isInheritedBy(candidate, type))
                .findFirst();
    }

    List<CallableSymbol> inheritedSuperclassMethods(TypeSymbol subtype, String methodName) {
        List<CallableSymbol> methods = new ArrayList<>();
        IrType currentType = superclassType(subtype.selfType()).orElse(null);
        Set<String> visited = new LinkedHashSet<>();
        while (currentType != null && currentType.isNominalReference()
                && visited.add(currentType.referenceName())) {
            TypeSymbol owner = types.get(currentType.referenceName());
            if (owner == null || owner.isInterface()) {
                break;
            }
            Map<String, IrType> substitution = owner.substitutionFor(currentType);
            for (CallableSymbol declaration : owner.declaredMethodsNamed(methodName)) {
                if (isInheritedBy(declaration, subtype)) {
                    methods.add(declaration.substitute(substitution));
                }
            }
            currentType = superclassType(currentType).orElse(null);
        }
        return List.copyOf(methods);
    }

    private boolean isInheritedBy(CallableSymbol method, TypeSymbol subtype) {
        return switch (method.accessModifier()) {
            case PRIVATE -> false;
            case PACKAGE_PRIVATE -> {
                TypeSymbol owner = types.get(method.ownerType());
                yield owner != null && owner.packageName().equals(subtype.packageName());
            }
            case PROTECTED, PUBLIC -> true;
        };
    }

    private boolean isFieldInheritedBy(FieldSymbol field, TypeSymbol subtype) {
        return switch (field.accessModifier()) {
            case PRIVATE -> false;
            case PACKAGE_PRIVATE -> {
                TypeSymbol owner = types.get(field.ownerClass());
                yield owner != null && owner.packageName().equals(subtype.packageName());
            }
            case PROTECTED, PUBLIC -> true;
        };
    }

    Optional<CallableSymbol> resolveImplementation(TypeSymbol concreteClass, String signatureKey) {
        return resolveImplementation(concreteClass.selfType(), signatureKey);
    }

    Optional<CallableSymbol> resolveImplementation(IrType concreteType, String signatureKey) {
        String methodName = signatureKey.substring(0, signatureKey.indexOf('('));
        CallableSymbol candidate = lookupMethods(concreteType, methodName).stream()
                .filter(method -> method.signatureKey().equals(signatureKey))
                .filter(method -> !method.isStatic() && !method.isAbstract())
                .filter(method -> method.accessModifier()
                        != ironwood.compiler.ast.AccessModifier.PRIVATE)
                .findFirst().orElse(null);
        return Optional.ofNullable(candidate);
    }

    List<CallableSymbol> constructors(IrType exactType) {
        if (!exactType.isNominalReference()) {
            return List.of();
        }
        TypeSymbol type = types.get(exactType.referenceName());
        if (type == null) {
            return List.of();
        }
        Map<String, IrType> substitution = type.substitutionFor(exactType);
        return type.constructors().stream().map(constructor -> constructor.substitute(substitution)).toList();
    }

    void setDispatchSlots(Map<String, IrDispatchSlot> slots) {
        dispatchSlots = Map.copyOf(slots);
        // Slots are installed after parent/member binding is complete. Cache only
        // declared receiver views; inferred/captured exact types still resolve afresh.
        declaredDispatch.clear();
        declaredDispatchReady = true;
    }

    IrDispatchSlot dispatchSlot(CallableSymbol method) {
        IrDispatchSlot slot = dispatchSlots.get(method.dispatchKey());
        if (slot == null) {
            throw new IllegalStateException("no dispatch slot for " + method.signatureKey());
        }
        return slot;
    }

    List<TypeSymbol> concreteSubtypes(String staticType) {
        return types.values().stream()
                .filter(type -> !type.isAbstract())
                .filter(type -> isSubtype(type.name(), staticType))
                .toList();
    }

    Set<String> dispatchTargets(String staticType, CallableSymbol method) {
        Set<String> targets = new LinkedHashSet<>();
        for (TypeSymbol concrete : concreteSubtypes(staticType)) {
            resolveDispatchImplementation(concrete, method.dispatchKey())
                    .map(CallableSymbol::linkageName).ifPresent(targets::add);
        }
        return targets;
    }

    Set<String> dispatchTargets(IrType staticType, CallableSymbol method) {
        if (staticType.isTypeParameter()) {
            Set<String> targets = new LinkedHashSet<>();
            for (TypeSymbol concrete : types.values()) {
                if (concrete.isAbstract() || genericTypes.upperBounds(staticType).stream()
                        .filter(IrType::isNominalReference)
                        .anyMatch(bound -> !isSubtype(concrete.name(), bound.referenceName()))) {
                    continue;
                }
                resolveDispatchImplementation(concrete, method.dispatchKey())
                        .map(CallableSymbol::linkageName).ifPresent(targets::add);
            }
            return targets;
        }
        return dispatchTargets(staticType.referenceName(), method);
    }

    Optional<CallableSymbol> resolveDispatchImplementation(TypeSymbol concreteClass,
                                                           String dispatchKey) {
        if (!declaredDispatchReady) {
            return resolveDispatch(concreteClass.selfType(), dispatchKey).selected();
        }
        return declaredDispatch.computeIfAbsent(new DeclaredDispatchKey(concreteClass, dispatchKey),
                key -> resolveDispatch(key.type().selfType(), key.dispatchKey()).selected());
    }

    DefaultResolution resolveDispatch(IrType concreteType, String dispatchKey) {
        List<CallableSymbol> maximal = maximallySpecificInterfaceMethodsForDispatch(
                concreteType, dispatchKey);
        IrType currentType = concreteType;
        TypeSymbol receiver = concreteType.isNominalReference()
                ? types.get(concreteType.referenceName()) : null;
        Set<String> visited = new LinkedHashSet<>();
        while (currentType != null && currentType.isNominalReference()
                && visited.add(currentType.referenceName())) {
            TypeSymbol owner = types.get(currentType.referenceName());
            if (owner == null || owner.isInterface()) {
                break;
            }
            Map<String, IrType> substitution = owner.substitutionFor(currentType);
            for (CallableSymbol declaration : owner.declaredMethods().values()) {
                CallableSymbol method = declaration.substitute(substitution);
                boolean matchesSubstitutedInterface = maximal.stream().anyMatch(requirement ->
                        requirement.overrideSignatureKey().equals(method.overrideSignatureKey()));
                boolean matchesSubstitutedSuperclass = overridesSuperclassDispatch(
                        currentType, owner, method, dispatchKey);
                if ((!declaration.dispatchKey().equals(dispatchKey)
                        && !matchesSubstitutedInterface && !matchesSubstitutedSuperclass)
                        || receiver != null && owner != receiver && !isInheritedBy(declaration, receiver)) {
                    continue;
                }
                if (declaration.isStatic()
                        || declaration.accessModifier()
                        == ironwood.compiler.ast.AccessModifier.PRIVATE) {
                    return new DefaultResolution(Optional.empty(), Optional.of(method),
                            List.of(), List.of());
                }
                return new DefaultResolution(method.isAbstract() ? Optional.empty()
                        : Optional.of(method), Optional.of(method), List.of(), List.of());
            }
            currentType = superclassType(currentType).orElse(null);
        }
        List<CallableSymbol> defaults = maximal.stream().filter(this::isDefaultMethod).toList();
        boolean hasAbstract = maximal.stream().anyMatch(CallableSymbol::isAbstract);
        return new DefaultResolution(defaults.size() == 1 && !hasAbstract
                ? Optional.of(defaults.getFirst())
                : Optional.empty(), Optional.empty(), maximal, defaults);
    }

    private boolean overridesSuperclassDispatch(IrType exactOwnerType, TypeSymbol owner,
                                                 CallableSymbol method, String dispatchKey) {
        if (method.isStatic() || method.accessModifier()
                == ironwood.compiler.ast.AccessModifier.PRIVATE) {
            return false;
        }
        IrType currentType = superclassType(exactOwnerType).orElse(null);
        Set<String> visited = new LinkedHashSet<>();
        while (currentType != null && currentType.isNominalReference()
                && visited.add(currentType.referenceName())) {
            TypeSymbol superclass = types.get(currentType.referenceName());
            if (superclass == null || superclass.isInterface()) {
                return false;
            }
            Map<String, IrType> substitution = superclass.substitutionFor(currentType);
            for (CallableSymbol declaration : superclass.declaredMethodsNamed(method.sourceName())) {
                CallableSymbol inherited = declaration.substitute(substitution);
                if (!inherited.isStatic() && isInheritedBy(declaration, owner)
                        && inherited.overrideSignatureKey().equals(method.overrideSignatureKey())
                        && inherited.dispatchKey().equals(dispatchKey)) {
                    return true;
                }
            }
            currentType = superclassType(currentType).orElse(null);
        }
        return false;
    }

    private List<CallableSymbol> maximallySpecificInterfaceMethodsForDispatch(
            IrType receiverType, String dispatchKey) {
        List<CallableSymbol> declarations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IrType exactType : exactSupertypes(receiverType)) {
            TypeSymbol owner = types.get(exactType.referenceName());
            if (owner == null || !owner.isInterface()) {
                continue;
            }
            Map<String, IrType> substitution = owner.substitutionFor(exactType);
            for (CallableSymbol declaration : owner.declaredMethods().values()) {
                CallableSymbol method = declaration.substitute(substitution);
                if (method.isStatic()
                        || method.accessModifier()
                        == ironwood.compiler.ast.AccessModifier.PRIVATE) {
                    continue;
                }
                if (seen.add(method.ownerType() + "#" + method.overrideSignatureKey())) {
                    declarations.add(method);
                }
            }
        }
        Set<String> requestedSignatures = declarations.stream()
                .filter(method -> method.dispatchKey().equals(dispatchKey))
                .map(CallableSymbol::overrideSignatureKey)
                .collect(java.util.stream.Collectors.toSet());
        List<CallableSymbol> overrideEquivalent = declarations.stream()
                .filter(method -> requestedSignatures.contains(method.overrideSignatureKey())).toList();
        return overrideEquivalent.stream().filter(candidate -> overrideEquivalent.stream().noneMatch(other ->
                !other.ownerType().equals(candidate.ownerType())
                        && other.overrideSignatureKey().equals(candidate.overrideSignatureKey())
                        && isSubtype(other.ownerType(), candidate.ownerType()))).toList();
    }

    record DefaultResolution(Optional<CallableSymbol> selected,
                             Optional<CallableSymbol> classDeclaration,
                             List<CallableSymbol> maximallySpecific,
                             List<CallableSymbol> defaults) {
        boolean conflict() {
            return defaults.size() > 1 || abstractDefaultConflict();
        }

        boolean abstractDefaultConflict() {
            return !defaults.isEmpty()
                    && maximallySpecific.stream().anyMatch(CallableSymbol::isAbstract);
        }

        boolean abstractRequirement() {
            return selected.isEmpty() && !conflict()
                    && (classDeclaration.map(CallableSymbol::isAbstract).orElse(false)
                    || !maximallySpecific.isEmpty());
        }
    }

    OverloadResolution resolveOverload(List<CallableSymbol> candidates, List<IrType> argumentTypes) {
        List<CallableSymbol> applicable = candidates.stream()
                .filter(candidate -> candidate.parameterTypes().size() == argumentTypes.size())
                .filter(candidate -> {
                    for (int index = 0; index < argumentTypes.size(); index++) {
                        if (!isAssignable(candidate.parameterTypes().get(index), argumentTypes.get(index))) {
                            return false;
                        }
                    }
                    return true;
                }).toList();
        List<CallableSymbol> mostSpecific = applicable.stream()
                .filter(candidate -> applicable.stream().noneMatch(other -> other != candidate
                        && moreSpecific(other, candidate)))
                .toList();
        return new OverloadResolution(mostSpecific.size() == 1
                ? Optional.of(mostSpecific.getFirst()) : Optional.empty(), applicable, mostSpecific);
    }

    private boolean moreSpecific(CallableSymbol left, CallableSymbol right) {
        boolean strict = false;
        for (int index = 0; index < left.parameterTypes().size(); index++) {
            IrType leftType = left.parameterTypes().get(index);
            IrType rightType = right.parameterTypes().get(index);
            if (!isAssignable(rightType, leftType)) {
                return false;
            }
            strict |= !leftType.equals(rightType);
        }
        return strict;
    }

    record OverloadResolution(Optional<CallableSymbol> selected,
                              List<CallableSymbol> applicable,
                              List<CallableSymbol> mostSpecific) {
    }

    boolean mayOverlap(String left, String right) {
        if (isSubtype(left, right) || isSubtype(right, left)) {
            return true;
        }
        TypeSymbol leftType = types.get(left);
        TypeSymbol rightType = types.get(right);
        if (leftType == null || rightType == null) {
            return false;
        }
        if (!leftType.isInterface() && !rightType.isInterface()) {
            return false;
        }
        return types.values().stream().filter(type -> !type.isAbstract())
                .anyMatch(type -> isSubtype(type.name(), left) && isSubtype(type.name(), right));
    }

    List<TypeSymbol> allSupertypesInclusive(TypeSymbol type) {
        return allSupertypesInclusive(type, new LinkedHashSet<>());
    }

    Optional<IrType> superclassType(IrType exactType) {
        if (!exactType.isNominalReference()) {
            return Optional.empty();
        }
        TypeSymbol symbol = types.get(exactType.referenceName());
        if (symbol == null) {
            return Optional.empty();
        }
        return symbol.superclassType().map(parent -> parent.substitute(symbol.substitutionFor(exactType)));
    }

    List<IrType> directInterfaceTypes(IrType exactType) {
        if (!exactType.isNominalReference()) {
            return List.of();
        }
        TypeSymbol symbol = types.get(exactType.referenceName());
        if (symbol == null) {
            return List.of();
        }
        Map<String, IrType> substitution = symbol.substitutionFor(exactType);
        return symbol.directInterfaceTypes().stream()
                .map(parent -> parent.substitute(substitution)).toList();
    }

    List<IrType> directParents(IrType exactType) {
        if (exactType.isTypeParameter()) {
            return genericTypes.upperBounds(exactType);
        }
        List<IrType> parents = new ArrayList<>();
        superclassType(exactType).ifPresent(parents::add);
        parents.addAll(directInterfaceTypes(exactType));
        return List.copyOf(parents);
    }

    List<IrType> exactSupertypes(IrType exactType) {
        List<IrType> result = new ArrayList<>();
        collectExactSupertypes(exactType, new LinkedHashSet<>(), result);
        return List.copyOf(result);
    }

    Optional<IrType> leastUpperBound(IrType left, IrType right) {
        if (left.equals(right)) {
            return Optional.of(left);
        }
        if (isSubtype(left, right)) {
            return Optional.of(right);
        }
        if (isSubtype(right, left)) {
            return Optional.of(left);
        }
        List<IrType> candidates = exactSupertypes(left).stream()
                .filter(candidate -> isSubtype(right, candidate))
                .distinct().toList();
        List<IrType> minimal = candidates.stream().filter(candidate -> candidates.stream()
                .noneMatch(other -> !other.equals(candidate)
                        && isSubtype(other, candidate)
                        && !isSubtype(candidate, other))).toList();
        return minimal.size() == 1 ? Optional.of(minimal.getFirst()) : Optional.empty();
    }

    List<IrType> exactSupertypes(IrType exactType,
                                 Map<String, TypeVariableSymbol> ambientVariables) {
        if (!exactType.isTypeParameter()
                || !ambientVariables.containsKey(exactType.referenceName())) {
            return exactSupertypes(exactType);
        }
        List<IrType> result = new ArrayList<>();
        result.add(exactType);
        for (IrType bound : ambientVariables.get(exactType.referenceName()).upperBounds()) {
            result.addAll(exactSupertypes(bound, ambientVariables));
        }
        return result.stream().distinct().toList();
    }

    private void collectExactSupertypes(IrType exactType, Set<String> visited,
                                        List<IrType> result) {
        if (exactType.isTypeParameter()) {
            if (!visited.add(exactType.referenceName())) {
                return;
            }
            result.add(exactType);
            genericTypes.upperBounds(exactType).forEach(bound ->
                    collectExactSupertypes(bound, visited, result));
            return;
        }
        if (!exactType.isNominalReference() || !visited.add(exactType.referenceName())) {
            return;
        }
        result.add(exactType);
        directParents(exactType).forEach(parent -> collectExactSupertypes(parent, visited, result));
    }

    private List<TypeSymbol> allSupertypesInclusive(TypeSymbol type, Set<TypeSymbol> visited) {
        List<TypeSymbol> result = new ArrayList<>();
        if (!visited.add(type)) {
            return result;
        }
        result.add(type);
        type.superclass().ifPresent(parent -> result.addAll(allSupertypesInclusive(parent, visited)));
        result.addAll(type.interfaceClosure());
        return result;
    }
}
