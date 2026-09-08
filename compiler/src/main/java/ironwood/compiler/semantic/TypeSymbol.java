// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.DeclaredType;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ir.IrClass;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TypeSymbol {
    static final String ENUM_NAME_FIELD = "<enum:name>";
    static final String ENUM_ORDINAL_FIELD = "<enum:ordinal>";
    private final TypeDeclaration declaration;
    private final CompilationUnit unit;
    private final boolean interfaceType;
    private final String binaryName;
    private final String sourceName;
    private final String nestHostName;
    private final String enclosingBinaryName;
    private final boolean topLevel;
    private final boolean memberType;
    private final boolean staticContextBoundary;
    private final boolean innerClass;
    private final boolean lexicallyScoped;
    private Optional<LocalClassSemantics.ClassIdentity> lexicalIdentity = Optional.empty();
    private Optional<LocalClassSemantics.ClassCapturePlan> capturePlan = Optional.empty();
    private Optional<TypeName> anonymousTarget = Optional.empty();
    private Optional<NewExpression> anonymousAllocation = Optional.empty();
    private Optional<EnumConstant> enumConstant = Optional.empty();
    private final Map<String, TypeSymbol> enumConstantClasses = new LinkedHashMap<>();
    private Optional<AnonymousParentBinding> anonymousParentBinding = Optional.empty();
    private Optional<TypeSymbol> superclass = Optional.empty();
    private Optional<IrType> superclassType = Optional.empty();
    private List<TypeSymbol> directInterfaces = List.of();
    private List<IrType> directInterfaceTypes = List.of();
    private final Map<String, FieldSymbol> declaredFields = new LinkedHashMap<>();
    private final Map<String, CallableSymbol> declaredMethods = new LinkedHashMap<>();
    private final Map<String, List<CallableSymbol>> declaredMethodsByName = new LinkedHashMap<>();
    private List<CallableSymbol> constructors = List.of();
    private Optional<CallableSymbol> destructor = Optional.empty();
    private Optional<String> constructorRollback = Optional.empty();
    private Optional<CallableSymbol> staticInitializer = Optional.empty();
    private List<IrField> layoutFields = List.of();
    private Set<TypeSymbol> interfaceClosure = Set.of();
    private int typeId;
    private IrClass irClass;
    private Optional<IrField> enclosingInstanceField = Optional.empty();
    private Optional<TypeSymbol> enclosingType = Optional.empty();
    private final Map<String, TypeSymbol> memberTypes = new LinkedHashMap<>();
    private final Map<String, TypeVariableSymbol> declaredTypeVariables = new LinkedHashMap<>();
    private final Map<String, TypeVariableSymbol> ambientCallableTypeVariables =
            new LinkedHashMap<>();
    private List<TypeVariableSymbol> syntheticBackingTypeVariables = List.of();
    private final Map<SpanKey, LocalClassSemantics.VariableIdentity> variablesBySpan =
            new LinkedHashMap<>();
    private final Set<String> writtenVariableIds = new LinkedHashSet<>();
    private List<CaptureSlot> captureSlots = List.of();
    private final Map<String, AnonymousConstructorForwarding> anonymousConstructorForwarding =
            new LinkedHashMap<>();

    TypeSymbol(DeclaredType declaredType, boolean interfaceType) {
        this.unit = declaredType.unit();
        this.declaration = declaredType.declaration();
        this.interfaceType = interfaceType;
        this.binaryName = declaredType.binaryName();
        this.sourceName = declaredType.sourceName();
        this.nestHostName = declaredType.nestHostBinaryName();
        this.enclosingBinaryName = declaredType.enclosingBinaryName();
        this.topLevel = declaredType.topLevel();
        this.memberType = !declaredType.topLevel();
        this.staticContextBoundary = !declaredType.topLevel() && declaration.isStatic();
        this.innerClass = !interfaceType && !declaredType.topLevel() && !declaration.isStatic();
        this.lexicallyScoped = false;
        initializeTypeVariables();
    }

    TypeSymbol(CompilationUnit unit, TypeDeclaration declaration, boolean interfaceType,
               LocalClassSemantics.ClassIdentity identity, String nestHostName,
               boolean memberType, boolean staticContextBoundary, boolean innerClass,
               LocalClassSemantics.ClassCapturePlan capturePlan,
               Optional<TypeName> anonymousTarget,
               Optional<NewExpression> anonymousAllocation) {
        this.unit = unit;
        this.declaration = declaration;
        this.interfaceType = interfaceType;
        this.binaryName = identity.binaryName();
        this.sourceName = identity.binaryName();
        this.nestHostName = nestHostName;
        this.enclosingBinaryName = identity.enclosingBinaryName().orElse(null);
        this.topLevel = false;
        this.memberType = memberType;
        this.staticContextBoundary = staticContextBoundary;
        this.innerClass = !interfaceType && innerClass;
        this.lexicallyScoped = true;
        this.lexicalIdentity = Optional.of(identity);
        this.capturePlan = Optional.of(capturePlan);
        this.anonymousTarget = anonymousTarget == null ? Optional.empty() : anonymousTarget;
        this.anonymousAllocation = anonymousAllocation == null
                ? Optional.empty() : anonymousAllocation;
        initializeTypeVariables();
    }

    private void initializeTypeVariables() {
        declaration.typeParameters().forEach(parameter -> declaredTypeVariables.putIfAbsent(
                parameter.name(), TypeVariableSymbol.declared(
                        binaryName + "#" + parameter.name(), parameter)));
    }

    TypeDeclaration declaration() {
        return declaration;
    }

    String name() {
        return binaryName;
    }

    String sourceName() {
        return sourceName;
    }

    String simpleName() {
        return declaration.name();
    }

    String packageName() {
        return unit.packageName();
    }

    boolean isTopLevel() {
        return topLevel;
    }

    boolean isStaticMember() {
        return staticContextBoundary;
    }

    boolean isInnerClass() {
        return innerClass;
    }

    String nestHostName() {
        return nestHostName;
    }

    String enclosingBinaryName() {
        return enclosingBinaryName;
    }

    boolean sameNest(TypeSymbol other) {
        return other != null && nestHostName().equals(other.nestHostName());
    }

    Optional<TypeSymbol> enclosingType() {
        return enclosingType;
    }

    void setEnclosingType(TypeSymbol value) {
        enclosingType = Optional.ofNullable(value);
        if (value != null && memberType) {
            value.memberTypes.put(simpleName(), this);
        }
    }

    boolean isMemberType() {
        return memberType;
    }

    boolean isLexicallyScoped() {
        return lexicallyScoped;
    }

    boolean isLocalClass() {
        return lexicalIdentity.map(identity ->
                identity.kind() == LocalClassSemantics.ClassKind.LOCAL).orElse(false);
    }

    boolean isAnonymousClass() {
        return lexicalIdentity.map(identity ->
                identity.kind() == LocalClassSemantics.ClassKind.ANONYMOUS).orElse(false);
    }

    boolean isEnumConstantClass() {
        return lexicalIdentity.map(identity ->
                identity.kind() == LocalClassSemantics.ClassKind.ENUM_CONSTANT).orElse(false);
    }

    void attachEnumConstant(EnumConstant constant) {
        if (!isEnumConstantClass()) {
            throw new IllegalStateException("only enum-constant classes have enum constants");
        }
        enumConstant = Optional.of(constant);
    }

    Optional<EnumConstant> enumConstantDeclaration() {
        return enumConstant;
    }

    void registerEnumConstantClass(EnumConstant constant, TypeSymbol concreteType) {
        enumConstantClasses.put(constant.name(), concreteType);
    }

    Optional<LocalClassSemantics.ClassIdentity> lexicalIdentity() {
        return lexicalIdentity;
    }

    Optional<LocalClassSemantics.ClassCapturePlan> capturePlan() {
        return capturePlan;
    }

    void registerVariableSpan(SourceSpan span, LocalClassSemantics.VariableIdentity variable) {
        variablesBySpan.putIfAbsent(SpanKey.of(span), variable);
    }

    Optional<LocalClassSemantics.VariableIdentity> variableAt(SourceSpan span) {
        return Optional.ofNullable(variablesBySpan.get(SpanKey.of(span)));
    }

    void registerVariableWrite(String variableId) {
        writtenVariableIds.add(variableId);
    }

    boolean variableWasWritten(LocalClassSemantics.VariableIdentity variable) {
        return writtenVariableIds.contains(variable.id());
    }

    List<CaptureSlot> captureSlots() {
        return captureSlots;
    }

    void setCaptureSlots(List<CaptureSlot> slots) {
        captureSlots = List.copyOf(slots);
    }

    Optional<CaptureSlot> captureSlot(String variableId) {
        return captureSlots.stream().filter(slot -> slot.variable().id().equals(variableId))
                .findFirst();
    }

    void addAnonymousConstructorForwarding(CallableSymbol constructor,
                                           CallableSymbol superConstructor,
                                           IrType superReceiverType) {
        anonymousConstructorForwarding.put(constructor.linkageName(),
                new AnonymousConstructorForwarding(superConstructor, superReceiverType));
    }

    Optional<AnonymousConstructorForwarding> anonymousConstructorForwarding(
            CallableSymbol constructor) {
        return Optional.ofNullable(anonymousConstructorForwarding.get(constructor.linkageName()));
    }

    void attachLexicalMetadata(LocalClassSemantics.ClassIdentity identity,
                               LocalClassSemantics.ClassCapturePlan plan) {
        lexicalIdentity = Optional.of(identity);
        capturePlan = Optional.of(plan);
    }

    Optional<TypeName> anonymousTarget() {
        return anonymousTarget;
    }

    Optional<NewExpression> anonymousAllocation() {
        return anonymousAllocation;
    }

    void bindAnonymousParent(AnonymousParentBinding binding) {
        if (!isAnonymousClass()) {
            throw new IllegalStateException("only anonymous classes have parent bindings");
        }
        if (anonymousParentBinding.isPresent()) {
            throw new IllegalStateException("anonymous parent is already bound for " + name());
        }
        anonymousParentBinding = Optional.of(binding);
        setSyntheticBackingTypeVariables(binding.diamondVariables());
    }

    Optional<AnonymousParentBinding> anonymousParentBinding() {
        return anonymousParentBinding;
    }

    Optional<TypeSymbol> declaredMemberType(String simpleName) {
        return Optional.ofNullable(memberTypes.get(simpleName));
    }

    java.util.Collection<TypeSymbol> declaredMemberTypes() {
        return java.util.Collections.unmodifiableCollection(memberTypes.values());
    }

    Optional<IrField> enclosingInstanceField() {
        return enclosingInstanceField;
    }

    void setEnclosingInstanceField(IrField field) {
        enclosingInstanceField = Optional.ofNullable(field);
    }

    CompilationUnit unit() {
        return unit;
    }

    SourceFile source() {
        return unit.source();
    }

    boolean isInterface() {
        return interfaceType;
    }

    boolean isEnum() {
        return declaration instanceof ClassDeclaration classDeclaration
                && classDeclaration.enumType();
    }

    List<EnumConstantSymbol> enumConstants() {
        if (!(declaration instanceof ClassDeclaration classDeclaration)
                || !classDeclaration.enumType()) {
            return List.of();
        }
        java.util.ArrayList<EnumConstantSymbol> constants = new java.util.ArrayList<>();
        for (int index = 0; index < classDeclaration.enumConstants().size(); index++) {
            EnumConstant constant = classDeclaration.enumConstants().get(index);
            constants.add(new EnumConstantSymbol(constant, index,
                    enumConstantClasses.getOrDefault(constant.name(), this)));
        }
        return List.copyOf(constants);
    }

    Optional<EnumConstantSymbol> enumConstant(String name) {
        return enumConstants().stream()
                .filter(constant -> constant.constant().name().equals(name)).findFirst();
    }

    boolean isAbstract() {
        return interfaceType || declaration instanceof ClassDeclaration classDeclaration
                && (classDeclaration.isAbstract()
                || classDeclaration.enumType() && classDeclaration.enumConstants().stream()
                        .allMatch(constant -> constant.classBody().isPresent()));
    }

    boolean isFinal() {
        return declaration instanceof ClassDeclaration classDeclaration && classDeclaration.isFinal();
    }

    Optional<TypeSymbol> superclass() {
        return superclass;
    }

    void setSuperclass(TypeSymbol value) {
        superclass = Optional.ofNullable(value);
    }

    Optional<IrType> superclassType() {
        return superclassType;
    }

    void setSuperclassType(IrType value) {
        superclassType = Optional.ofNullable(value);
    }

    List<TypeSymbol> directInterfaces() {
        return directInterfaces;
    }

    void setDirectInterfaces(List<TypeSymbol> values) {
        directInterfaces = List.copyOf(values);
    }

    List<IrType> directInterfaceTypes() {
        return directInterfaceTypes;
    }

    void setDirectInterfaceTypes(List<IrType> values) {
        directInterfaceTypes = List.copyOf(values);
    }

    List<TypeVariableSymbol> typeParameters() {
        java.util.ArrayList<TypeVariableSymbol> parameters = new java.util.ArrayList<>();
        if (isInnerClass()) {
            enclosingType.ifPresent(enclosing -> parameters.addAll(enclosing.typeParameters()));
        }
        parameters.addAll(declaredTypeVariables.values());
        parameters.addAll(syntheticBackingTypeVariables);
        return List.copyOf(parameters);
    }

    List<TypeVariableSymbol> declaredTypeParameters() {
        return List.copyOf(declaredTypeVariables.values());
    }

    void setSyntheticBackingTypeVariables(List<TypeVariableSymbol> variables) {
        if (!isAnonymousClass()) {
            throw new IllegalStateException("synthetic type variables are reserved for anonymous classes");
        }
        Map<String, TypeVariableSymbol> byId = new LinkedHashMap<>();
        variables.forEach(variable -> byId.putIfAbsent(variable.id(), variable));
        syntheticBackingTypeVariables = List.copyOf(byId.values());
    }

    void setAmbientCallableTypeVariables(List<TypeVariableSymbol> variables) {
        ambientCallableTypeVariables.clear();
        for (TypeVariableSymbol variable : variables) {
            ambientCallableTypeVariables.put(variable.displayName(), variable);
        }
    }

    List<TypeVariableSymbol> ambientCallableTypeVariables() {
        return List.copyOf(ambientCallableTypeVariables.values());
    }

    Optional<TypeVariableSymbol> typeVariable(String simpleName) {
        TypeVariableSymbol declared = declaredTypeVariables.get(simpleName);
        if (declared != null) {
            return Optional.of(declared);
        }
        TypeVariableSymbol ambient = ambientCallableTypeVariables.get(simpleName);
        if (ambient != null) {
            return Optional.of(ambient);
        }
        if (isInnerClass()) {
            return enclosingType.flatMap(enclosing -> enclosing.typeVariable(simpleName));
        }
        return Optional.empty();
    }

    Optional<IrType> typeParameter(String simpleName) {
        return typeVariable(simpleName).map(TypeVariableSymbol::irType);
    }

    String typeParameterId(String simpleName) {
        return typeVariable(simpleName).map(TypeVariableSymbol::id)
                .orElse(name() + "#" + simpleName);
    }

    IrType selfType() {
        return IrType.reference(name(), typeParameters().stream()
                .map(TypeVariableSymbol::irType).toList());
    }

    Map<String, IrType> substitutionFor(IrType exactType) {
        if (!exactType.isNominalReference() || !exactType.referenceName().equals(name())
                || exactType.typeArguments().size() != typeParameters().size()) {
            return Map.of();
        }
        Map<String, IrType> substitution = new LinkedHashMap<>();
        List<TypeVariableSymbol> variables = typeParameters();
        for (int index = 0; index < variables.size(); index++) {
            substitution.put(variables.get(index).id(), exactType.typeArguments().get(index));
        }
        return Map.copyOf(substitution);
    }

    Map<String, FieldSymbol> declaredFields() {
        return Collections.unmodifiableMap(declaredFields);
    }

    void addField(String name, FieldSymbol field) {
        declaredFields.put(name, field);
    }

    void replaceField(String name, FieldSymbol field) {
        declaredFields.put(name, field);
    }

    Optional<CallableSymbol> staticInitializer() {
        return staticInitializer;
    }

    void setStaticInitializer(CallableSymbol initializer) {
        staticInitializer = Optional.ofNullable(initializer);
    }

    Map<String, CallableSymbol> declaredMethods() {
        return Collections.unmodifiableMap(declaredMethods);
    }

    List<CallableSymbol> declaredMethodsNamed(String name) {
        return declaredMethodsByName.getOrDefault(name, List.of());
    }

    void addMethod(CallableSymbol method) {
        declaredMethods.put(method.overrideSignatureKey(), method);
        List<CallableSymbol> named = new java.util.ArrayList<>(
                declaredMethodsByName.getOrDefault(method.sourceName(), List.of()));
        named.add(method);
        declaredMethodsByName.put(method.sourceName(), List.copyOf(named));
    }

    List<CallableSymbol> constructors() {
        return constructors;
    }

    void setConstructors(List<CallableSymbol> values) {
        constructors = List.copyOf(values);
    }

    Optional<CallableSymbol> destructor() {
        return destructor;
    }

    void setDestructor(CallableSymbol value) {
        destructor = Optional.ofNullable(value);
    }

    Optional<String> constructorRollback() {
        return constructorRollback;
    }

    void setConstructorRollback(String linkageName) {
        constructorRollback = Optional.ofNullable(linkageName);
    }

    List<IrField> layoutFields() {
        return layoutFields;
    }

    void setLayoutFields(List<IrField> fields) {
        layoutFields = List.copyOf(fields);
    }

    Set<TypeSymbol> interfaceClosure() {
        return interfaceClosure;
    }

    void setInterfaceClosure(Set<TypeSymbol> interfaces) {
        interfaceClosure = Collections.unmodifiableSet(new LinkedHashSet<>(interfaces));
    }

    int typeId() {
        return typeId;
    }

    void setTypeId(int value) {
        typeId = value;
    }

    IrClass irClass() {
        return irClass;
    }

    void setIrClass(IrClass value) {
        irClass = value;
    }

    record CaptureSlot(LocalClassSemantics.VariableIdentity variable, IrType type,
                       IrField field) {
    }

    record AnonymousConstructorForwarding(CallableSymbol superConstructor,
                                           IrType superReceiverType) {
    }

    record EnumConstantSymbol(EnumConstant constant, int ordinal, TypeSymbol concreteType) {
    }

    record AnonymousParentBinding(TypeSymbol target, IrType ownerView,
                                  IrType parentTemplate,
                                  List<TypeVariableSymbol> diamondVariables,
                                  Optional<ExpressionTypePlan> enclosingPlan,
                                  Map<String, TypeVariableSymbol> plannedCaptures) {
        AnonymousParentBinding {
            if (target == null || parentTemplate == null) {
                throw new IllegalArgumentException("anonymous parent binding is incomplete");
            }
            diamondVariables = List.copyOf(diamondVariables);
            enclosingPlan = enclosingPlan == null ? Optional.empty() : enclosingPlan;
            plannedCaptures = Map.copyOf(plannedCaptures);
        }

        boolean qualified() {
            return enclosingPlan.isPresent();
        }
    }

    private record SpanKey(int startOffset, int endOffset) {
        private static SpanKey of(SourceSpan span) {
            return new SpanKey(span.start().offset(), span.end().offset());
        }
    }

}
