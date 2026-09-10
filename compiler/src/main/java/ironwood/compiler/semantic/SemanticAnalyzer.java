// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ConstructorDeclaration;
import ironwood.compiler.ast.EnumConstantInitialization;
import ironwood.compiler.ast.DeclaredType;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import ironwood.compiler.ast.ImportDeclaration;
import ironwood.compiler.ast.MethodDeclaration;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ast.TypeReference;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrThrowableTraceInstruction;
import ironwood.compiler.ir.IrClass;
import ironwood.compiler.ir.IrArrayType;
import ironwood.compiler.ir.IrDispatchEntry;
import ironwood.compiler.ir.IrDispatchSlot;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrImmortalObject;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.ir.IrDestroyArrayElementsInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrParameter;
import ironwood.compiler.ir.IrRawDeallocateInstruction;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrValueReference;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrTypeInitialization;
import ironwood.compiler.ir.IrTypeKind;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SemanticAnalyzer {
    private final ironwood.compiler.UnfreedMode unfreedMode;
    private final Set<Path> unfreedSources;

    private static final String ROOT_OBJECT = "ironwood.lang.Object";
    private static final String ROOT_ENUM = "ironwood.lang.Enum";
    private static final IrType MAIN_ARGUMENTS_TYPE =
            IrType.array(IrType.reference("ironwood.lang.String"));

    private SourceFile source;
    private ClosedWorldEffectAnalyzer reclamationEffects;
    private GenericTypeSystem genericTypes;
    private LexicalTypeScopes lexicalTypeScopes = LexicalTypeScopes.empty();
    private boolean lexicalTypesRequireFunctionLowering;
    private final Map<String, List<TypeVariableSymbol>> callableTypeVariablesById =
            new LinkedHashMap<>();
    private final Set<String> overrideDirectiveDeclarationIds = new LinkedHashSet<>();

    public SemanticAnalyzer(SourceFile source) {
        this();
        this.source = source;
    }

    public SemanticAnalyzer() {
        this(ironwood.compiler.UnfreedMode.WARN, null);
    }

    public SemanticAnalyzer(ironwood.compiler.UnfreedMode unfreedMode, Set<Path> unfreedSources) {
        this.unfreedMode = java.util.Objects.requireNonNull(unfreedMode);
        this.unfreedSources = unfreedSources == null ? null : Set.copyOf(unfreedSources);
    }

    public SemanticResult analyze(CompilationUnit unit) {
        return analyze(List.of(unit));
    }

    public SemanticResult analyze(List<CompilationUnit> units) {
        return analyze(units, true);
    }

    public SemanticResult analyze(List<CompilationUnit> units, boolean requireMain) {
        return analyze(units, requireMain, Optional.empty());
    }

    public SemanticResult analyze(List<CompilationUnit> units, String mainClass) {
        return analyze(units, true, Optional.of(mainClass));
    }

    private void initializeLexicalCallableTypeVariables(Map<String, TypeSymbol> types,
                                                        List<Diagnostic> diagnostics) {
        List<CallableTypeScope> callableScopes = new ArrayList<>();
        for (TypeSymbol owner : types.values()) {
            if (owner.declaration() instanceof ClassDeclaration declaration) {
                for (ConstructorDeclaration constructor : declaration.constructors()) {
                    callableScopes.add(new CallableTypeScope(owner, "<init>",
                            constructor.typeParameters(), false, constructor.span()));
                }
                for (MethodDeclaration method : declaration.methods()) {
                    callableScopes.add(new CallableTypeScope(owner, method.name(),
                            method.typeParameters(), method.isStatic(), method.span()));
                }
            } else if (owner.declaration() instanceof InterfaceDeclaration declaration) {
                for (InterfaceMethodDeclaration method : declaration.methods()) {
                    callableScopes.add(new CallableTypeScope(owner, method.name(),
                            method.typeParameters(), method.isStatic(), method.span()));
                }
            }
        }
        for (TypeSymbol lexical : types.values()) {
            if (!lexical.isLexicallyScoped()) {
                continue;
            }
            SourceSpan lexicalSpan = lexical.lexicalIdentity().orElseThrow().span();
            CallableTypeScope scope = callableScopes.stream()
                    .filter(candidate -> candidate.owner().source().path()
                            .equals(lexical.source().path()))
                    .filter(candidate -> contains(candidate.span(), lexicalSpan))
                    .min(java.util.Comparator.comparingInt(candidate ->
                            candidate.span().end().offset() - candidate.span().start().offset()))
                    .orElse(null);
            if (scope == null) {
                lexical.setAmbientCallableTypeVariables(List.of());
                continue;
            }
            String declarationId = callableDeclarationId(scope.owner(), scope.name(), scope.span());
            List<TypeVariableSymbol> variables = callableTypeVariablesById.computeIfAbsent(
                    declarationId, ignored -> genericTypes.initializeCallableTypeParameters(
                            scope.owner(), declarationId, scope.typeParameters(),
                            scope.staticContext(), diagnostics));
            lexical.setAmbientCallableTypeVariables(variables);
        }
    }

    private static boolean contains(SourceSpan container, SourceSpan nested) {
        return container.start().offset() <= nested.start().offset()
                && container.end().offset() >= nested.end().offset();
    }

    private SemanticResult analyze(List<CompilationUnit> units, boolean requireMain,
                                   Optional<String> mainClass) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (units.isEmpty()) {
            return new SemanticResult(Optional.empty(), List.of(Diagnostic.global(
                    "program does not contain any compilation units")));
        }
        source = units.getFirst().source();
        reclamationEffects = null;
        lexicalTypeScopes = LexicalTypeScopes.empty();
        lexicalTypesRequireFunctionLowering = false;
        callableTypeVariablesById.clear();
        overrideDirectiveDeclarationIds.clear();
        Map<String, TypeSymbol> types = collectTypeHeaders(units, diagnostics);
        TypeResolver resolver = new TypeResolver(types, lexicalTypeScopes);
        genericTypes = new GenericTypeSystem(types, resolver);
        initializeLexicalCallableTypeVariables(types, new ArrayList<>());
        validateImports(units, resolver, diagnostics);
        genericTypes.initializeDeclaredBounds(diagnostics);
        ClassHierarchy hierarchy = new ClassHierarchy(types, resolver, genericTypes);
        genericTypes.attachHierarchy(hierarchy);
        Set<TypeSymbol> hierarchyResolved = Collections.newSetFromMap(new IdentityHashMap<>());
        resolveHierarchy(types, resolver, hierarchy, diagnostics, hierarchyResolved);
        callableTypeVariablesById.clear();
        initializeLexicalCallableTypeVariables(types, diagnostics);
        computeInterfaceClosures(types);
        genericTypes.markHierarchyReady(diagnostics);

        Set<TypeSymbol> collected = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TypeSymbol> collecting = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeSymbol type : types.values()) {
            if (hierarchyResolved.contains(type)) {
                collectMembers(type, resolver, hierarchy, diagnostics, collected, collecting);
            }
        }
        bindDeferredAnonymousParents(types, resolver, hierarchy, diagnostics,
                hierarchyResolved, collected, collecting);
        if (!validateAcyclicHierarchy(types, diagnostics)) {
            // Ownership and dispatch analysis require a DAG. A diagnosed cycle
            // must not reach superclass walks that assume this invariant.
            return new SemanticResult(Optional.empty(), diagnostics);
        }
        validateEnumBaseUsage(types, hierarchy, diagnostics);
        computeInterfaceClosures(types);
        validateStaticImportMembers(units, resolver, hierarchy, diagnostics);
        validateGenericSupertypeConsistency(types, hierarchy, diagnostics);
        validateThrowableTraceLayout(types, diagnostics);
        validateStackTraceElementLayout(types, diagnostics);
        StringPool stringPool = new StringPool();
        List<IrStaticField> staticFields = new StaticConstantEvaluator(types, hierarchy,
                diagnostics, stringPool).evaluate();
        types.values().forEach(this::buildStaticInitializer);
        validateOverridesAndInterfaces(types, hierarchy, diagnostics);
        types.values().stream().filter(type -> !type.isInterface()).forEach(type ->
                new FinalFieldAssignmentAnalyzer(type.source(), type, diagnostics).analyze());
        types.values().forEach(type ->
                new FinalFieldAssignmentAnalyzer(type.source(), type, diagnostics, true).analyze());

        List<IrDispatchSlot> dispatchSlots = buildDispatchSlots(types);
        Map<String, IrDispatchSlot> slotsByKey = new LinkedHashMap<>();
        dispatchSlots.forEach(slot -> slotsByKey.put(slot.key(), slot));
        hierarchy.setDispatchSlots(slotsByKey);

        CallableSymbol main = findAndValidateMain(types, diagnostics, requireMain, mainClass);
        EscapeSummaryAnalyzer initialEscapeSummaries = new EscapeSummaryAnalyzer(types, resolver);
        OwnedArrayFieldAnalyzer initialOwnedFields = new OwnedArrayFieldAnalyzer(
                types, hierarchy, initialEscapeSummaries);
        EscapeSummaryAnalyzer escapeSummaries = new EscapeSummaryAnalyzer(
                types, resolver, initialOwnedFields);
        OwnedArrayFieldAnalyzer ownedArrayFields = new OwnedArrayFieldAnalyzer(
                types, hierarchy, escapeSummaries);
        buildIrTypes(types, hierarchy, dispatchSlots, escapeSummaries);
        if (!Diagnostic.hasErrors(diagnostics)) {
            // Bind calls before granting ownership. Provisional ownership failures
            // are reconsidered after receiver flow; final lowering validates all
            // source diagnostics and emits the actual reclamation instructions.
            List<IrFunction> boundFunctions = lowerFunctions(types, hierarchy, escapeSummaries,
                    ownedArrayFields, stringPool, new ArrayList<>(), new LinkedHashMap<>(), false);
            if (unfreedMode != ironwood.compiler.UnfreedMode.OFF) {
                reclamationEffects = new ClosedWorldEffectAnalyzer(boundFunctions,
                        types.values().stream().map(TypeSymbol::irClass).toList());
                reclamationEffects.analyze();
            }
            BorrowDispatchAnalysis borrowDispatch = new BorrowDispatchAnalysis(types, hierarchy,
                    boundFunctions, staticFields, main != null);
            initialEscapeSummaries = new EscapeSummaryAnalyzer(types, resolver, null, borrowDispatch);
            initialOwnedFields = new OwnedArrayFieldAnalyzer(types, hierarchy, initialEscapeSummaries);
            escapeSummaries = new EscapeSummaryAnalyzer(types, resolver, initialOwnedFields, borrowDispatch);
            ownedArrayFields = new OwnedArrayFieldAnalyzer(types, hierarchy, escapeSummaries);
        }
        Map<String, String> constructorDelegations = new LinkedHashMap<>();
        List<IrFunction> functions = new ArrayList<>(buildConstructorRollbackFunctions(types, ownedArrayFields));
        // Rollback construction installs descriptor linkage. Publish the final
        // type metadata only after that linkage and refined ownership are ready.
        buildIrTypes(types, hierarchy, dispatchSlots, escapeSummaries);
        functions.addAll(lowerFunctions(types, hierarchy, escapeSummaries, ownedArrayFields,
                stringPool, diagnostics, constructorDelegations, true));
        validateConstructorDelegationCycles(types, constructorDelegations, diagnostics);
        validatePoolBuilders(types, hierarchy, escapeSummaries, diagnostics);
        OwnedArrayElementAnalyzer.validate(types, functions, ownedArrayFields, escapeSummaries, diagnostics);
        new ClosedWorldEffectAnalyzer(functions,
                types.values().stream().map(TypeSymbol::irClass).toList())
                .validate(types, diagnostics);

        if (Diagnostic.hasErrors(diagnostics) || requireMain && main == null) {
            return new SemanticResult(Optional.empty(), diagnostics);
        }
        Optional<IrFunction> entryPoint = main == null ? Optional.empty() : functions.stream()
                .filter(function -> function.linkageName().equals(main.linkageName())).findFirst();
        List<IrClass> irTypes = types.values().stream().map(TypeSymbol::irClass).toList();
        List<IrTypeInitialization> typeInitializations = types.values().stream()
                .map(type -> new IrTypeInitialization(type.name(),
                        initializationPrerequisites(type),
                        type.staticInitializer().map(CallableSymbol::linkageName),
                        type.declaration().span()))
                .toList();
        String moduleName = main == null ? types.keySet().stream().findFirst().orElse("library")
                : main.ownerType();
        Optional<IrImmortalObject> allocationFailure = Optional.ofNullable(
                        types.get("ironwood.lang.OutOfMemoryError"))
                .map(type -> new IrImmortalObject("implicit allocation failure",
                        type.selfType(), type.declaration().span()));
        IrProgram rawProgram = new IrProgram(moduleName, irTypes, staticFields,
                typeInitializations, List.of(), stringPool.constants(), dispatchSlots, functions,
                entryPoint, allocationFailure);
        IrProgram specializedProgram = new PrimitiveGenericSpecializer(types, hierarchy,
                diagnostics).specialize(rawProgram);
        if (Diagnostic.hasErrors(diagnostics)) {
            return new SemanticResult(Optional.empty(), diagnostics);
        }
        List<IrArrayType> irArrayTypes = buildIrArrayTypes(specializedProgram.functions(),
                specializedProgram.classes());
        return new SemanticResult(Optional.of(new IrProgram(specializedProgram.moduleName(),
                specializedProgram.classes(), specializedProgram.staticFields(),
                specializedProgram.typeInitializations(), irArrayTypes,
                specializedProgram.stringConstants(), specializedProgram.dispatchSlots(),
                specializedProgram.functions(), specializedProgram.entryPoint(),
                specializedProgram.allocationFailure())), diagnostics);
    }

    private List<IrFunction> lowerFunctions(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                                             EscapeSummaryAnalyzer escapeSummaries,
                                             OwnedArrayFieldAnalyzer ownedArrayFields,
                                             StringPool stringPool, List<Diagnostic> diagnostics,
                                             Map<String, String> constructorDelegations, boolean checkUnfreed) {
        List<IrFunction> functions = new ArrayList<>();
        for (TypeSymbol type : types.values()) {
            ironwood.compiler.UnfreedMode mode = checkUnfreed
                    && (unfreedSources == null || unfreedSources.contains(type.source().path()))
                    ? unfreedMode : ironwood.compiler.UnfreedMode.OFF;
            type.staticInitializer().ifPresent(initializer -> functions.add(
                    new FunctionAnalyzer(type.source(), initializer, hierarchy, escapeSummaries,
                            ownedArrayFields, stringPool, diagnostics, constructorDelegations).withUnfreedChecks(mode, reclamationEffects).analyze()
                            .withSourceIdentity(sourceFileName(type.source()),
                                    IrCallableKind.CLASS_INITIALIZER)));
            if (!type.isInterface()) {
                for (CallableSymbol constructor : type.constructors()) {
                    functions.add(new FunctionAnalyzer(type.source(), constructor, hierarchy, escapeSummaries,
                            ownedArrayFields, stringPool, diagnostics, constructorDelegations).withUnfreedChecks(mode, reclamationEffects).analyze()
                            .withSourceIdentity(sourceFileName(type.source()),
                                    IrCallableKind.CONSTRUCTOR));
                }
                type.destructor().ifPresent(destructor -> functions.add(
                        new FunctionAnalyzer(type.source(), destructor, hierarchy, escapeSummaries,
                                ownedArrayFields, stringPool, diagnostics,
                                constructorDelegations).withUnfreedChecks(mode, reclamationEffects).analyze()
                                .withSourceIdentity(sourceFileName(type.source()),
                                        IrCallableKind.DESTRUCTOR)));
            }
            for (CallableSymbol method : type.declaredMethods().values()) {
                if (!method.isAbstract()) {
                    functions.add(new FunctionAnalyzer(type.source(), method, hierarchy, escapeSummaries,
                            ownedArrayFields, stringPool, diagnostics, constructorDelegations).withUnfreedChecks(mode, reclamationEffects).analyze()
                            .withSourceIdentity(sourceFileName(type.source()),
                                    IrCallableKind.METHOD));
                }
            }
        }
        return functions;
    }

    private void buildStaticInitializer(TypeSymbol type) {
        List<ironwood.compiler.ast.StaticInitialization> declarations =
                type.declaration() instanceof ClassDeclaration classDeclaration
                        ? classDeclaration.staticInitializations()
                        : ((InterfaceDeclaration) type.declaration()).fields().stream()
                        .map(field -> (ironwood.compiler.ast.StaticInitialization) field).toList();
        List<ironwood.compiler.ast.Statement> actions = new ArrayList<>();
        if (type.isEnum()) {
            ((ClassDeclaration) type.declaration()).enumConstants().forEach(constant ->
                    actions.add(new EnumConstantInitialization(constant, constant.span())));
        }
        for (var initialization : declarations) {
            if (initialization instanceof FieldDeclaration declaration) {
                FieldSymbol field = type.declaredFields().get(declaration.name());
                if (field == null || declaration.initializer().isEmpty()
                        || field.staticField() == null
                        || !field.staticField().triggersInitialization()) {
                    continue;
                }
                actions.add(new AssignmentStatement(
                        new NameExpression(declaration.name(), declaration.nameSpan()),
                        declaration.initializer().orElseThrow().span(),
                        declaration.initializer().orElseThrow(), declaration.span()));
            } else {
                actions.add((Block) initialization);
            }
        }
        if (actions.isEmpty()) {
            return;
        }
        Block body = new Block(actions, type.declaration().span());
        type.setStaticInitializer(new CallableSymbol(type.name(), "<clinit>",
                AccessModifier.PRIVATE, true, IrCallableKind.CLASS_INITIALIZER,
                IrType.VOID, List.of(), List.of(), Optional.of(body), Optional.empty(),
                Optional.empty(), false, true, true, type.declaration().nameSpan(),
                type.declaration().span(), "ironwood." + type.name() + ".<clinit>",
                Optional.empty(), null, null, List.of(), List.of()));
    }

    private List<String> initializationPrerequisites(TypeSymbol type) {
        if (type.isInterface()) {
            return List.of();
        }
        List<String> prerequisites = new ArrayList<>();
        type.superclass().ifPresent(superclass -> prerequisites.add(superclass.name()));
        Set<String> visited = new LinkedHashSet<>();
        for (TypeSymbol directInterface : type.directInterfaces()) {
            collectDefaultMethodInterfaces(directInterface, visited, prerequisites);
        }
        return List.copyOf(prerequisites);
    }

    private void collectDefaultMethodInterfaces(TypeSymbol type, Set<String> visited,
                                                List<String> prerequisites) {
        if (!visited.add(type.name())) {
            return;
        }
        for (TypeSymbol parent : type.directInterfaces()) {
            collectDefaultMethodInterfaces(parent, visited, prerequisites);
        }
        if (type.declaration() instanceof InterfaceDeclaration declaration
                && declaration.methods().stream().anyMatch(InterfaceMethodDeclaration::isDefault)) {
            prerequisites.add(type.name());
        }
    }

    private static String sourceFileName(SourceFile source) {
        Path fileName = source.path().getFileName();
        return fileName == null ? "<unknown>.iron" : fileName.toString();
    }

    private List<IrArrayType> buildIrArrayTypes(List<IrFunction> functions,
                                                List<IrClass> classes) {
        IrClass root = classes.stream().filter(type -> type.name().equals(ROOT_OBJECT))
                .findFirst().orElse(null);
        if (root == null) {
            return List.of();
        }
        LinkedHashSet<IrType> arrayTypes = new LinkedHashSet<>();
        functions.forEach(function -> collectArrayTypes(function, arrayTypes));
        classes.forEach(type -> collectArrayTypes(type, arrayTypes));
        List<IrArrayType> result = new ArrayList<>();
        for (IrType arrayType : arrayTypes.stream()
                .sorted(java.util.Comparator.comparing(IrType::displayName)).toList()) {
            // Exact array tests compare descriptor addresses. Negative ids remain private,
            // deterministic descriptor identities and are never membership-table indexes.
            result.add(new IrArrayType(arrayType, -1 - result.size(),
                    List.of(root.typeId()), root.dispatchEntries(),
                    root.toStringReturnsOwnedFresh()));
        }
        return List.copyOf(result);
    }

    private static void collectArrayTypes(Object value, Set<IrType> result) {
        if (value == null) {
            return;
        }
        if (value instanceof IrType type) {
            if (type.isArray()) {
                IrType erased = type.erasure();
                result.add(erased);
                collectArrayTypes(erased.elementType(), result);
            } else {
                type.typeArguments().forEach(argument -> collectArrayTypes(argument, result));
            }
            return;
        }
        if (value instanceof ironwood.compiler.ir.IrOperand operand) {
            collectArrayTypes(operand.type(), result);
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(item -> collectArrayTypes(item, result));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectArrayTypes(item, result));
            return;
        }
        Class<?> valueClass = value.getClass();
        if (!valueClass.isRecord()
                || !valueClass.getPackageName().startsWith("ironwood.compiler.ir")) {
            return;
        }
        for (var component : valueClass.getRecordComponents()) {
            try {
                collectArrayTypes(component.getAccessor().invoke(value), result);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("cannot inspect typed IR array types", exception);
            }
        }
    }

    private Map<String, TypeSymbol> collectTypeHeaders(List<CompilationUnit> units,
                                                        List<Diagnostic> diagnostics) {
        Map<String, TypeSymbol> types = new LinkedHashMap<>();
        for (CompilationUnit unit : units) {
            source = unit.source();
            int publicTypes = 0;
            for (DeclaredType declaredType : DeclaredTypes.in(unit)) {
                TypeDeclaration declaration = declaredType.declaration();
                if (declaredType.enclosingTypes().stream()
                        .anyMatch(enclosing -> enclosing.name().equals(declaration.name()))) {
                    diagnostics.add(error(declaration.nameSpan(), "member type '"
                            + declaration.name() + "' cannot have the same name as an enclosing type"));
                }
                if (declaredType.topLevel() && (declaration.accessModifier() == AccessModifier.PRIVATE
                        || declaration.accessModifier() == AccessModifier.PROTECTED)) {
                    diagnostics.add(error(declaration.nameSpan(), "top-level "
                            + (declaration instanceof InterfaceDeclaration ? "interface"
                            : ((ClassDeclaration) declaration).enumType() ? "enum" : "class")
                            + " '" + declaration.name() + "' may only be public or package-private"));
                }
                if (declaredType.topLevel() && declaration.accessModifier() == AccessModifier.PUBLIC) {
                    publicTypes++;
                    String expectedFile = declaration.name() + ".iron";
                    Path fileName = unit.source().path().getFileName();
                    if (fileName == null || !fileName.toString().equals(expectedFile)) {
                        diagnostics.add(error(declaration.nameSpan(), "public top-level type '"
                                + declaration.name() + "' must be declared in " + expectedFile));
                    }
                }
                if (declaredType.topLevel() && publicTypes > 1
                        && declaration.accessModifier() == AccessModifier.PUBLIC) {
                    diagnostics.add(error(declaration.nameSpan(),
                            "a compilation unit may declare at most one public top-level type"));
                }
                TypeSymbol symbol = new TypeSymbol(declaredType,
                        declaration instanceof InterfaceDeclaration);
                if (declaration instanceof ClassDeclaration classDeclaration
                        && classDeclaration.isAbstract() && classDeclaration.isFinal()) {
                    diagnostics.add(error(declaration.nameSpan(), "class '" + declaration.name()
                            + "' cannot be both abstract and final"));
                }
                Set<String> typeParameters = new LinkedHashSet<>();
                declaration.typeParameters().forEach(parameter -> {
                    if (!typeParameters.add(parameter.name())) {
                        diagnostics.add(error(parameter.span(), "duplicate type parameter '"
                                + parameter.name() + "' in type '" + declaration.name() + "'"));
                    }
                    if (parameter.name().equals(declaration.name())) {
                        diagnostics.add(error(parameter.span(), "type parameter '" + parameter.name()
                                + "' cannot have the same name as its declaring type"));
                    }
                });
                TypeSymbol previous = types.putIfAbsent(symbol.name(), symbol);
                if (previous != null) {
                    String kind = !previous.isInterface() && !symbol.isInterface() ? "class" : "type";
                    diagnostics.add(error(declaration.nameSpan(),
                            "duplicate " + kind + " '" + symbol.name() + "'"));
                }
            }
        }
        LocalTypeCollector.Result lexicalCollection = new LocalTypeCollector()
                .collect(units, types, diagnostics);
        lexicalTypeScopes = lexicalCollection.lexicalScopes();
        lexicalTypesRequireFunctionLowering = lexicalCollection.requiresFunctionLowering();
        for (TypeSymbol type : types.values()) {
            if (type.enclosingBinaryName() != null) {
                type.setEnclosingType(types.get(type.enclosingBinaryName()));
            }
        }
        for (TypeSymbol type : types.values()) {
            if (type.isEnumConstantClass()) {
                TypeSymbol enumType = type.enclosingType().orElse(null);
                type.enumConstantDeclaration().ifPresent(constant -> {
                    if (enumType != null) {
                        enumType.registerEnumConstantClass(constant, type);
                    }
                });
            }
        }
        int typeId = 0;
        for (TypeSymbol type : types.values()) {
            type.setTypeId(typeId++);
        }
        return types;
    }

    private void validateImports(List<CompilationUnit> units, TypeResolver resolver,
                                 List<Diagnostic> diagnostics) {
        for (CompilationUnit unit : units) {
            source = unit.source();
            Map<String, String> singleImports = new LinkedHashMap<>();
            for (var imported : unit.imports()) {
                if (imported.staticImport()) {
                    TypeResolver.Resolution owner = resolver.resolveStaticImportOwner(imported, unit);
                    if (owner.ambiguous()) {
                        diagnostics.add(error(imported.nameSpan(), "static import owner type '"
                                + imported.ownerName() + "' is ambiguous"));
                    } else if (owner.type().isEmpty()) {
                        diagnostics.add(error(imported.nameSpan(), "static import owner type '"
                                + imported.ownerName() + "' does not exist"));
                    } else if (owner.inaccessible()) {
                        diagnostics.add(error(imported.nameSpan(), "static import owner type '"
                                + imported.ownerName() + "' is not accessible from package '"
                                + unit.packageName() + "'"));
                    }
                    continue;
                }
                if (imported.wildcard()) {
                    continue;
                }
                boolean conflictsWithDeclaration = unit.declarations().stream()
                        .anyMatch(declaration -> declaration.name()
                                .equals(imported.importedSimpleName()));
                if (conflictsWithDeclaration) {
                    diagnostics.add(error(imported.nameSpan(), "single-type import '"
                            + imported.name() + "' conflicts with a top-level type declared in this file"));
                }
                String previous = singleImports.putIfAbsent(imported.importedSimpleName(), imported.name());
                if (previous != null && !previous.equals(imported.name())) {
                    diagnostics.add(error(imported.nameSpan(), "single-type imports for '"
                            + imported.importedSimpleName() + "' conflict: '" + previous
                            + "' and '" + imported.name() + "'"));
                    continue;
                }
                TypeResolver.Resolution resolution = resolver.resolve(imported.name(), unit);
                if (resolution.type().isEmpty()) {
                    diagnostics.add(error(imported.nameSpan(), "imported type '"
                            + imported.name() + "' does not exist"));
                } else if (resolution.inaccessible()) {
                    diagnostics.add(error(imported.nameSpan(), "type '" + imported.name()
                            + "' is not public and cannot be imported from package '"
                            + unit.packageName() + "'"));
                }
            }
        }
    }

    private void validateStaticImportMembers(List<CompilationUnit> units, TypeResolver resolver,
                                             ClassHierarchy hierarchy,
                                             List<Diagnostic> diagnostics) {
        StaticImportResolver imports = new StaticImportResolver(resolver, hierarchy);
        for (CompilationUnit unit : units) {
            source = unit.source();
            Map<String, Set<String>> importedTypes = new LinkedHashMap<>();
            for (ImportDeclaration imported : unit.imports()) {
                if (!imported.staticImport() || imported.wildcard()) {
                    continue;
                }
                TypeResolver.Resolution owner = resolver.resolveStaticImportOwner(imported, unit);
                if (owner.type().isEmpty() || owner.ambiguous() || owner.inaccessible()) {
                    continue;
                }
                List<FieldSymbol> fields = imports.declaredFields(imported, unit);
                List<CallableSymbol> methods = imports.declaredMethods(imported, unit);
                Set<TypeSymbol> memberTypes = imports.declaredMemberTypes(imported, unit);
                boolean hasStaticMember = !fields.isEmpty() || !methods.isEmpty()
                        || !memberTypes.isEmpty();
                List<FieldSymbol> accessibleFields = fields.stream()
                        .filter(field -> imports.memberAccessible(field.accessModifier(),
                                field.ownerClass(), unit)).toList();
                List<CallableSymbol> accessibleMethods = methods.stream()
                        .filter(method -> imports.memberAccessible(method.accessModifier(),
                                method.ownerType(), unit)).toList();
                Set<TypeSymbol> accessibleTypes = memberTypes.stream()
                        .filter(type -> !resolver.accessibility(type, unit).inaccessible())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (!hasStaticMember) {
                    diagnostics.add(error(imported.nameSpan(), "type '" + imported.ownerName()
                            + "' has no static member named '"
                            + imported.importedMemberName() + "'"));
                    continue;
                }
                if (accessibleFields.isEmpty() && accessibleMethods.isEmpty()
                        && accessibleTypes.isEmpty()) {
                    diagnostics.add(error(imported.nameSpan(), "static member '"
                            + imported.name() + "' is not accessible from package '"
                            + unit.packageName() + "'"));
                    continue;
                }
                if (accessibleTypes.isEmpty()) {
                    continue;
                }
                String simpleName = imported.importedMemberName();
                Set<String> identities = accessibleTypes.stream().map(TypeSymbol::name)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                Set<String> previous = importedTypes.putIfAbsent(simpleName, identities);
                if (previous != null && !previous.equals(identities)) {
                    diagnostics.add(error(imported.nameSpan(), "single-static imports for member type '"
                            + simpleName + "' conflict: " + previous + " and " + identities));
                }
                if (unit.declarations().stream()
                        .anyMatch(declaration -> declaration.name().equals(simpleName))) {
                    diagnostics.add(error(imported.nameSpan(), "single-static import of member type '"
                            + simpleName + "' conflicts with a top-level type declared in this file"));
                }
                for (ImportDeclaration ordinary : unit.imports()) {
                    if (ordinary.staticImport() || ordinary.wildcard()
                            || !ordinary.importedSimpleName().equals(simpleName)) {
                        continue;
                    }
                    TypeResolver.Resolution ordinaryType = resolver.resolve(ordinary.name(), unit);
                    String ordinaryIdentity = ordinaryType.type().map(TypeSymbol::name).orElse(null);
                    if (ordinaryIdentity != null && !identities.contains(ordinaryIdentity)) {
                        diagnostics.add(error(imported.nameSpan(), "single-static import of member type '"
                                + simpleName + "' conflicts with single-type import '"
                                + ordinary.name() + "'"));
                    }
                }
            }
        }
    }

    private void resolveHierarchy(Map<String, TypeSymbol> types, TypeResolver resolver,
                                  ClassHierarchy hierarchy, List<Diagnostic> diagnostics,
                                  Set<TypeSymbol> resolvedTypes) {
        TypeSymbol rootObject = types.get(ROOT_OBJECT);
        TypeSymbol rootEnum = types.get(ROOT_ENUM);
        if (resolvedTypes.isEmpty() && (rootObject == null || rootObject.isInterface())) {
            diagnostics.add(Diagnostic.global("program is missing bundled root class '"
                    + ROOT_OBJECT + "'"));
        }
        for (TypeSymbol type : types.values()) {
            if (resolvedTypes.contains(type) || hasUnboundQualifiedAnonymousAncestor(type)) {
                continue;
            }
            source = type.source();
            if (type.isEnumConstantClass()) {
                TypeSymbol enumType = type.enclosingType().orElse(null);
                if (enumType != null && enumType.isEnum()) {
                    type.setSuperclass(enumType);
                    type.setSuperclassType(enumType.selfType());
                    type.setDirectInterfaces(List.of());
                    type.setDirectInterfaceTypes(List.of());
                } else if (rootObject != null && !rootObject.isInterface()) {
                    type.setSuperclass(rootObject);
                    type.setSuperclassType(rootObject.selfType());
                }
                resolvedTypes.add(type);
                continue;
            }
            if (type.isAnonymousClass()) {
                resolveAnonymousHierarchy(type, rootObject, resolver, hierarchy, diagnostics);
                resolvedTypes.add(type);
                continue;
            }
            if (type.declaration() instanceof ClassDeclaration declaration) {
                if (type.name().equals(ROOT_OBJECT)) {
                    if (declaration.superclass().isPresent()) {
                        diagnostics.add(error(declaration.superclass().orElseThrow().span(),
                                "root class '" + ROOT_OBJECT + "' cannot extend another class"));
                    }
                    ResolvedParents interfaces = resolveInterfaces(type,
                            declaration.implementedInterfaces(), resolver, diagnostics, "implement");
                    type.setDirectInterfaces(interfaces.symbols());
                    type.setDirectInterfaceTypes(interfaces.types());
                    continue;
                }
                declaration.superclass().ifPresent(reference -> {
                    ResolvedParent parent = resolveParent(type, reference, resolver, diagnostics, "superclass");
                    if (parent != null) {
                        if (parent.symbol().isInterface()) {
                            diagnostics.add(error(reference.span(), "class '" + declaration.name()
                                    + "' cannot extend interface '" + parent.symbol().name() + "'"));
                        } else {
                            if (parent.symbol().isFinal()) {
                                diagnostics.add(error(reference.span(), "class '" + declaration.name()
                                        + "' cannot extend final class '" + parent.symbol().name() + "'"));
                            }
                            type.setSuperclass(parent.symbol());
                            type.setSuperclassType(parent.type());
                        }
                    }
                });
                if (declaration.superclass().isEmpty()) {
                    if (type.isEnum() && rootEnum != null && !rootEnum.isInterface()) {
                        type.setSuperclass(rootEnum);
                        type.setSuperclassType(IrType.reference(ROOT_ENUM,
                                List.of(type.selfType())));
                    } else if (rootObject != null && !rootObject.isInterface()) {
                        type.setSuperclass(rootObject);
                        type.setSuperclassType(IrType.reference(ROOT_OBJECT));
                    }
                }
                ResolvedParents interfaces = resolveInterfaces(type, declaration.implementedInterfaces(),
                        resolver, diagnostics, "implement");
                type.setDirectInterfaces(interfaces.symbols());
                type.setDirectInterfaceTypes(interfaces.types());
            } else {
                InterfaceDeclaration declaration = (InterfaceDeclaration) type.declaration();
                ResolvedParents interfaces = resolveInterfaces(type, declaration.extendedInterfaces(),
                        resolver, diagnostics, "extend");
                type.setDirectInterfaces(interfaces.symbols());
                type.setDirectInterfaceTypes(interfaces.types());
            }
            resolvedTypes.add(type);
        }
    }

    private static boolean hasUnboundQualifiedAnonymousAncestor(TypeSymbol type) {
        for (TypeSymbol lexical = type; lexical != null;
             lexical = lexical.enclosingType().orElse(null)) {
            if (lexical.isAnonymousClass()
                    && lexical.anonymousAllocation()
                    .flatMap(NewExpression::enclosingInstance).isPresent()
                    && lexical.anonymousParentBinding().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void bindDeferredAnonymousParents(
            Map<String, TypeSymbol> types, TypeResolver resolver,
            ClassHierarchy hierarchy, List<Diagnostic> diagnostics,
            Set<TypeSymbol> hierarchyResolved, Set<TypeSymbol> collected,
            Set<TypeSymbol> collecting) {
        AnonymousParentBinder binder = new AnonymousParentBinder(hierarchy,
                (anonymous, allocation) -> anonymousPrimaryPlanningContext(
                        anonymous, allocation, hierarchy));
        List<TypeSymbol> pending = types.values().stream()
                .filter(TypeSymbol::isAnonymousClass)
                .filter(type -> type.anonymousAllocation()
                        .flatMap(NewExpression::enclosingInstance).isPresent())
                .filter(type -> type.anonymousParentBinding().isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        while (!pending.isEmpty()) {
            List<TypeSymbol> ready = pending.stream()
                    .filter(type -> qualifiedAnonymousEnclosingReady(type, hierarchyResolved))
                    .toList();
            if (ready.isEmpty()) {
                TypeSymbol blocked = pending.getFirst();
                source = blocked.source();
                diagnostics.add(error(blocked.declaration().span(),
                        "cannot establish lexical dependencies for anonymous class '"
                                + blocked.name() + "'"));
                installAnonymousRecoveryBinding(blocked, types.get(ROOT_OBJECT), hierarchy);
                hierarchyResolved.add(blocked);
                pending.remove(blocked);
            } else {
                TypeSymbol anonymous = binder.bindingOrder(ready).getFirst();
                source = anonymous.source();
                InvocationPlanningResult<AnonymousParentBinder.Binding> planned =
                        binder.bindOne(anonymous);
                if (planned.isResolved()) {
                    installAnonymousBinding(anonymous,
                            planned.resolvedValue().parentBinding(), hierarchy);
                } else {
                    InvocationPlanningResult.PlanningRejection rejection = planned.isRejected()
                            ? planned.rejections().getFirst() : null;
                    if (rejection == null) {
                        diagnostics.add(error(anonymous.anonymousAllocation().orElseThrow().span(),
                                "cannot resolve qualified anonymous class parent"));
                    } else {
                        diagnostics.add(error(rejection.span(), rejection.message()));
                    }
                    installAnonymousRecoveryBinding(anonymous, types.get(ROOT_OBJECT), hierarchy);
                }
                hierarchyResolved.add(anonymous);
                pending.remove(anonymous);
            }

            resolveHierarchy(types, resolver, hierarchy, diagnostics, hierarchyResolved);
            computeInterfaceClosures(types);
            for (TypeSymbol type : types.values()) {
                if (hierarchyResolved.contains(type) && !collected.contains(type)) {
                    collectMembers(type, resolver, hierarchy, diagnostics, collected, collecting);
                }
            }
        }
    }

    private static boolean qualifiedAnonymousEnclosingReady(
            TypeSymbol type, Set<TypeSymbol> hierarchyResolved) {
        for (TypeSymbol lexical = type.enclosingType().orElse(null); lexical != null;
             lexical = lexical.enclosingType().orElse(null)) {
            if (!hierarchyResolved.contains(lexical)) {
                return false;
            }
        }
        return true;
    }

    private AnonymousParentBinder.PrimaryPlanningContext anonymousPrimaryPlanningContext(
            TypeSymbol anonymous, NewExpression allocation, ClassHierarchy hierarchy) {
        TypeSymbol owner = anonymous.enclosingType().orElse(null);
        if (owner == null) {
            return null;
        }
        CallableSymbol callable = owner.constructors().stream()
                .filter(candidate -> contains(candidate.span(), allocation.span()))
                .min(java.util.Comparator.comparingInt(candidate ->
                        candidate.span().end().offset() - candidate.span().start().offset()))
                .orElseGet(() -> owner.declaredMethods().values().stream()
                        .filter(candidate -> contains(candidate.span(), allocation.span()))
                        .min(java.util.Comparator.comparingInt(candidate ->
                                candidate.span().end().offset()
                                        - candidate.span().start().offset()))
                        .orElse(null));
        if (callable == null) {
            boolean staticContext = anonymous.capturePlan()
                    .flatMap(LocalClassSemantics.ClassCapturePlan::directEnclosingInstance)
                    .isEmpty();
            callable = new CallableSymbol(owner.name(), "<anonymous-primary>",
                    AccessModifier.PRIVATE, staticContext, IrCallableKind.METHOD, IrType.VOID,
                    List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                    false, false, true, allocation.classNameSpan(), allocation.span(),
                    "ironwood." + owner.name() + ".<anonymous-primary>@"
                            + allocation.span().start().offset(), Optional.empty(), null,
                    owner.name() + "#anonymous-primary@"
                            + allocation.span().start().offset(),
                    anonymous.ambientCallableTypeVariables());
        }
        return new FunctionAnalyzer(owner.source(), callable, hierarchy,
                null, null, null, new ArrayList<>(), new LinkedHashMap<>())
                .anonymousParentPlanningContext();
    }

    private void installAnonymousBinding(TypeSymbol anonymous,
                                         TypeSymbol.AnonymousParentBinding binding,
                                         ClassHierarchy hierarchy) {
        anonymous.bindAnonymousParent(binding);
        genericTypes.registerSyntheticVariables(binding.diamondVariables());
        hierarchy.registerCaptures(binding.plannedCaptures());
        TypeSymbol target = binding.target();
        if (target.isInterface()) {
            TypeSymbol root = hierarchy.type(ROOT_OBJECT).orElse(null);
            anonymous.setSuperclass(root);
            if (root != null) {
                anonymous.setSuperclassType(root.selfType());
            }
            anonymous.setDirectInterfaces(List.of(target));
            anonymous.setDirectInterfaceTypes(List.of(binding.parentTemplate()));
        } else {
            anonymous.setSuperclass(target);
            anonymous.setSuperclassType(binding.parentTemplate());
            anonymous.setDirectInterfaces(List.of());
            anonymous.setDirectInterfaceTypes(List.of());
        }
    }

    private void installAnonymousRecoveryBinding(TypeSymbol anonymous, TypeSymbol root,
                                                  ClassHierarchy hierarchy) {
        if (root == null) {
            return;
        }
        NewExpression allocation = anonymous.anonymousAllocation().orElseThrow();
        ExpressionTypePlan enclosingPlan = allocation.enclosingInstance()
                .map(expression -> ExpressionTypePlan.simple(expression, root.selfType(),
                        Optional.empty())).orElse(null);
        TypeSymbol.AnonymousParentBinding recovery = new TypeSymbol.AnonymousParentBinding(
                root, root.selfType(), root.selfType(), List.of(),
                Optional.ofNullable(enclosingPlan), Map.of());
        installAnonymousBinding(anonymous, recovery, hierarchy);
    }

    private void resolveAnonymousHierarchy(TypeSymbol type, TypeSymbol rootObject,
                                           TypeResolver resolver, ClassHierarchy hierarchy,
                                           List<Diagnostic> diagnostics) {
        if (type.anonymousParentBinding().isPresent()) {
            TypeSymbol.AnonymousParentBinding binding = type.anonymousParentBinding().orElseThrow();
            if (binding.target().isInterface()) {
                if (rootObject != null && !rootObject.isInterface()) {
                    type.setSuperclass(rootObject);
                    type.setSuperclassType(rootObject.selfType());
                }
                type.setDirectInterfaces(List.of(binding.target()));
                type.setDirectInterfaceTypes(List.of(binding.parentTemplate()));
            } else {
                type.setSuperclass(binding.target());
                type.setSuperclassType(binding.parentTemplate());
                type.setDirectInterfaces(List.of());
                type.setDirectInterfaceTypes(List.of());
            }
            return;
        }
        TypeName target = type.anonymousTarget().orElseThrow();
        NewExpression allocation = type.anonymousAllocation().orElseThrow();
        ResolvedParent resolved = resolveAnonymousParent(type, target, allocation, resolver,
                diagnostics);
        if (resolved == null) {
            if (rootObject != null && !rootObject.isInterface()) {
                type.setSuperclass(rootObject);
                type.setSuperclassType(IrType.reference(ROOT_OBJECT));
            }
            return;
        }
        TypeSymbol.AnonymousParentBinding binding = unqualifiedAnonymousBinding(
                type, allocation, resolved, resolver, diagnostics);
        if (binding != null) {
            type.bindAnonymousParent(binding);
            genericTypes.registerSyntheticVariables(binding.diamondVariables());
            hierarchy.registerCaptures(binding.plannedCaptures());
            resolved = new ResolvedParent(binding.target(), binding.parentTemplate());
        }
        if (resolved.symbol().isInterface()) {
            if (rootObject != null && !rootObject.isInterface()) {
                type.setSuperclass(rootObject);
                type.setSuperclassType(IrType.reference(ROOT_OBJECT));
            }
            type.setDirectInterfaces(List.of(resolved.symbol()));
            type.setDirectInterfaceTypes(List.of(resolved.type()));
            if (!allocation.arguments().isEmpty()) {
                diagnostics.add(error(allocation.span(), "anonymous implementation of interface '"
                        + resolved.symbol().sourceName() + "' cannot pass constructor arguments"));
            }
            return;
        }
        if (resolved.symbol().isFinal()) {
            diagnostics.add(error(target.span(), "anonymous class cannot extend final class '"
                    + resolved.symbol().sourceName() + "'"));
        }
        type.setSuperclass(resolved.symbol());
        type.setSuperclassType(resolved.type());
        type.setDirectInterfaces(List.of());
        type.setDirectInterfaceTypes(List.of());
    }

    private ResolvedParent resolveAnonymousParent(TypeSymbol type, TypeName target,
                                                  NewExpression allocation,
                                                  TypeResolver resolver,
                                                  List<Diagnostic> diagnostics) {
        TypeResolver.Resolution direct = resolver.resolve(target.referenceName(), type,
                target.span());
        if (allocation.diamond() && direct.type().isPresent()) {
            TypeSymbol parent = direct.type().orElseThrow();
            List<IrType> arguments = new ArrayList<>();
            if (parent.isInnerClass()) {
                resolver.enclosingTypeView(type, parent)
                        .ifPresent(view -> arguments.addAll(view.typeArguments()));
            }
            parent.declaredTypeParameters().stream().map(TypeVariableSymbol::irType)
                    .forEach(arguments::add);
            return new ResolvedParent(parent, IrType.reference(parent.name(), arguments));
        }
        TypeReference reference = new TypeReference(target.referenceName(),
                target.typeArguments(), target.typeArgumentSegmentCounts(), target.span());
        return resolveParent(type, reference, resolver, diagnostics,
                "anonymous class target");
    }

    private TypeSymbol.AnonymousParentBinding unqualifiedAnonymousBinding(
            TypeSymbol anonymous, NewExpression allocation, ResolvedParent resolved,
            TypeResolver resolver, List<Diagnostic> diagnostics) {
        TypeSymbol target = resolved.symbol();
        IrType parentTemplate = resolved.type();
        IrType initialParentTemplate = parentTemplate;
        IrType ownerView = target.enclosingType()
                .flatMap(owner -> resolver.exactClassSupertype(initialParentTemplate, owner))
                .orElse(initialParentTemplate);
        List<TypeVariableSymbol> diamondVariables = new ArrayList<>();
        if (allocation.diamond()) {
            if (target.declaredTypeParameters().isEmpty()) {
                diagnostics.add(error(allocation.classNameSpan(),
                        "diamond construction requires a generic class"));
            } else {
                int ownerCount = target.typeParameters().size()
                        - target.declaredTypeParameters().size();
                List<IrType> arguments = new ArrayList<>(parentTemplate.typeArguments());
                Map<String, IrType> substitutions = new LinkedHashMap<>();
                for (int index = 0; index < ownerCount && index < arguments.size(); index++) {
                    substitutions.put(target.typeParameters().get(index).id(), arguments.get(index));
                }
                while (arguments.size() > ownerCount) {
                    arguments.removeLast();
                }
                for (int index = 0; index < target.declaredTypeParameters().size(); index++) {
                    TypeVariableSymbol declared = target.declaredTypeParameters().get(index);
                    List<IrType> bounds = declared.upperBounds().stream()
                            .map(bound -> bound.substitute(substitutions)).toList();
                    TypeVariableSymbol synthetic = TypeVariableSymbol.synthetic(
                            anonymous.name() + "#diamond-" + index + "#"
                                    + declared.displayName(), declared.displayName(), bounds);
                    diamondVariables.add(synthetic);
                    arguments.add(synthetic.irType());
                    substitutions.put(declared.id(), synthetic.irType());
                }
                parentTemplate = IrType.reference(target.name(), arguments);
            }
        }
        return new TypeSymbol.AnonymousParentBinding(target, ownerView, parentTemplate,
                diamondVariables, Optional.empty(), Map.of());
    }

    private ResolvedParent resolveParent(TypeSymbol owner, TypeReference reference, TypeResolver resolver,
                                     List<Diagnostic> diagnostics, String role) {
        if (reference.typeArguments().stream().anyMatch(SemanticAnalyzer::containsWildcard)) {
            diagnostics.add(error(reference.span(), "wildcard-parameterized types cannot be used as a "
                    + role));
        }
        TypeResolver.Resolution resolution = resolver.resolve(reference.name(), owner,
                reference.span());
        if (resolution.ambiguous()) {
            diagnostics.add(error(reference.span(), "ambiguous " + role + " '" + reference.name()
                    + "'; candidates are " + resolution.candidates().stream().map(TypeSymbol::name)
                    .reduce((left, right) -> left + ", " + right).orElse("")));
            return null;
        }
        TypeSymbol parent = resolution.type().orElse(null);
        if (parent == null) {
            diagnostics.add(error(reference.span(), "unknown " + role + " '" + reference.name() + "'"));
        } else if (resolution.inaccessible()) {
            diagnostics.add(error(reference.span(), "type '" + parent.name()
                    + "' is package-private in package '" + parent.packageName() + "'"));
        }
        if (parent == null) {
            return null;
        }
        IrType exactType = resolveType(owner, TypeName.reference(reference.name(),
                reference.typeArguments(), reference.typeArgumentSegmentCounts(), reference.span()),
                resolver, diagnostics, false);
        return new ResolvedParent(parent, exactType);
    }

    private ResolvedParents resolveInterfaces(TypeSymbol owner, List<TypeReference> references,
                                               TypeResolver resolver,
                                               List<Diagnostic> diagnostics, String verb) {
        List<TypeSymbol> resolved = new ArrayList<>();
        List<IrType> exactTypes = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (TypeReference reference : references) {
            ResolvedParent parent = resolveParent(owner, reference, resolver, diagnostics, "interface");
            TypeSymbol target = parent == null ? null : parent.symbol();
            String identity = target == null ? reference.name() : target.name();
            if (!names.add(identity)) {
                diagnostics.add(error(reference.span(), "duplicate interface '" + reference.name()
                        + "' in " + verb + " list for '" + owner.name() + "'"));
                continue;
            }
            if (target == null) {
                continue;
            }
            if (!target.isInterface()) {
                diagnostics.add(error(reference.span(), (owner.isInterface() ? "interface" : "class")
                        + " '" + owner.name() + "' cannot " + verb + " class '" + target.name() + "'"));
                continue;
            }
            resolved.add(target);
            exactTypes.add(parent.type());
        }
        return new ResolvedParents(resolved, exactTypes);
    }

    private boolean validateAcyclicHierarchy(Map<String, TypeSymbol> types, List<Diagnostic> diagnostics) {
        int before = diagnostics.size();
        Map<TypeSymbol, VisitState> states = new IdentityHashMap<>();
        for (TypeSymbol type : types.values()) {
            visitHierarchy(type, states, new ArrayList<>(), diagnostics);
        }
        return diagnostics.size() == before;
    }

    private void visitHierarchy(TypeSymbol type, Map<TypeSymbol, VisitState> states,
                                List<TypeSymbol> path, List<Diagnostic> diagnostics) {
        VisitState state = states.get(type);
        if (state == VisitState.DONE) {
            return;
        }
        if (state == VisitState.ACTIVE) {
            source = type.source();
            int start = path.indexOf(type);
            List<TypeSymbol> cycle = start < 0 ? List.of(type) : path.subList(start, path.size());
            String names = cycle.stream().map(TypeSymbol::name)
                    .reduce((left, right) -> left + " -> " + right).orElse(type.name());
            diagnostics.add(error(type.declaration().nameSpan(),
                    (type.isInterface() ? "interface" : "class")
                            + " inheritance cycle: " + names + " -> " + type.name()));
            return;
        }
        states.put(type, VisitState.ACTIVE);
        path.add(type);
        if (type.isInterface()) {
            for (TypeSymbol parent : type.directInterfaces()) {
                visitHierarchy(parent, states, path, diagnostics);
            }
        } else {
            type.superclass().ifPresent(parent -> visitHierarchy(parent, states, path, diagnostics));
        }
        path.removeLast();
        states.put(type, VisitState.DONE);
    }

    private void computeInterfaceClosures(Map<String, TypeSymbol> types) {
        Set<TypeSymbol> computing = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TypeSymbol> computed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeSymbol type : types.values()) {
            computeInterfaceClosure(type, computing, computed);
        }
    }

    private void validateGenericSupertypeConsistency(Map<String, TypeSymbol> types,
                                                     ClassHierarchy hierarchy,
                                                     List<Diagnostic> diagnostics) {
        for (TypeSymbol type : types.values()) {
            Map<String, IrType> seen = new LinkedHashMap<>();
            collectGenericSupertypes(type.selfType(), hierarchy, seen, new LinkedHashSet<>(),
                    type, diagnostics);
        }
    }

    private void collectGenericSupertypes(IrType exactType, ClassHierarchy hierarchy,
                                          Map<String, IrType> seen, Set<String> path,
                                          TypeSymbol owner, List<Diagnostic> diagnostics) {
        if (!exactType.isNominalReference()) {
            return;
        }
        IrType previous = seen.putIfAbsent(exactType.referenceName(), exactType);
        if (previous != null && !previous.equals(exactType)) {
            source = owner.source();
            diagnostics.add(error(owner.declaration().nameSpan(), "type '" + owner.name()
                    + "' inherits generic type '" + exactType.referenceName()
                    + "' with conflicting arguments " + previous.displayName() + " and "
                    + exactType.displayName()));
            return;
        }
        if (!path.add(exactType.referenceName())) {
            return;
        }
        for (IrType parent : hierarchy.directParents(exactType)) {
            collectGenericSupertypes(parent, hierarchy, seen, path, owner, diagnostics);
        }
        path.remove(exactType.referenceName());
    }

    private Set<TypeSymbol> computeInterfaceClosure(TypeSymbol type, Set<TypeSymbol> computing,
                                                    Set<TypeSymbol> computed) {
        if (computed.contains(type)) {
            return type.interfaceClosure();
        }
        if (!computing.add(type)) {
            return Set.of();
        }
        LinkedHashSet<TypeSymbol> closure = new LinkedHashSet<>();
        type.superclass().ifPresent(parent -> closure.addAll(
                computeInterfaceClosure(parent, computing, computed)));
        for (TypeSymbol direct : type.directInterfaces()) {
            closure.add(direct);
            closure.addAll(computeInterfaceClosure(direct, computing, computed));
        }
        computing.remove(type);
        computed.add(type);
        type.setInterfaceClosure(closure);
        return closure;
    }

    private void collectMembers(TypeSymbol type, TypeResolver resolver,
                                ClassHierarchy hierarchy, List<Diagnostic> diagnostics,
                                Set<TypeSymbol> collected, Set<TypeSymbol> collecting) {
        if (collected.contains(type) || !collecting.add(type)) {
            return;
        }
        type.superclass().ifPresent(parent -> collectMembers(parent, resolver, hierarchy, diagnostics,
                collected, collecting));
        source = type.source();
        if (type.declaration() instanceof ClassDeclaration declaration) {
            List<IrField> layout = new ArrayList<>();
            type.superclass().ifPresent(parent -> layout.addAll(parent.layoutFields()));
            if (type.isInnerClass()) {
                TypeSymbol enclosing = type.enclosingType().orElseThrow();
                IrField enclosingField = new IrField(type.name(), "<enclosing>",
                        enclosing.selfType(), layout.size(), declaration.nameSpan());
                type.setEnclosingInstanceField(enclosingField);
                layout.add(enclosingField);
            }
            List<TypeSymbol.CaptureSlot> captureSlots = new ArrayList<>();
            type.superclass().ifPresent(parent -> captureSlots.addAll(parent.captureSlots()));
            Set<String> capturedVariableIds = captureSlots.stream()
                    .map(slot -> slot.variable().id())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<LocalClassSemantics.VariableIdentity> requiredCaptures = type.capturePlan()
                    .map(plan -> plan.requiredCaptures().stream()
                            .sorted(java.util.Comparator.comparingInt(
                                    LocalClassSemantics.VariableIdentity::declarationOrdinal))
                            .toList()).orElse(List.of());
            for (LocalClassSemantics.VariableIdentity variable : requiredCaptures) {
                if (!capturedVariableIds.add(variable.id())) {
                    continue;
                }
                IrType captureType = resolveVariableType(type, variable, resolver, hierarchy,
                        diagnostics);
                IrField captureField = new IrField(type.name(),
                        "<capture:" + variable.declarationOrdinal() + ":" + variable.name() + ">",
                        captureType, layout.size(), variable.nameSpan());
                captureSlots.add(new TypeSymbol.CaptureSlot(variable, captureType, captureField));
                layout.add(captureField);
            }
            type.setCaptureSlots(captureSlots);
            if (type.isEnum()) {
                addEnumFields(type, declaration, layout, diagnostics);
            }
            for (FieldDeclaration field : declaration.fields()) {
                IrType fieldType = resolveType(type, field.type(), resolver, diagnostics, field.isStatic());
                if (field.isStatic() && fieldType.isTypeParameter()) {
                    // The resolver already emitted the source diagnostic. Keep later recovery
                    // IR well-formed so an invalid program never crashes semantic analysis.
                    fieldType = IrType.reference(ROOT_OBJECT);
                }
                if (fieldType.equals(IrType.VOID)) {
                    diagnostics.add(error(field.nameSpan(),
                            "field '" + field.name() + "' cannot have type void"));
                    fieldType = IrType.I32;
                }
                if (type.declaredFields().containsKey(field.name())) {
                    diagnostics.add(error(field.nameSpan(), "duplicate field '" + field.name()
                            + "' in class '" + type.name() + "'"));
                    continue;
                }
                if (field.isStatic()) {
                    type.addField(field.name(), new FieldSymbol(field, field.accessModifier(),
                            fieldType, type.name(), null, null, null));
                    continue;
                }
                IrField irField = new IrField(type.name(), field.name(), fieldType,
                        layout.size(), field.span());
                type.addField(field.name(), new FieldSymbol(field, field.accessModifier(),
                        fieldType, type.name(), irField, null, null));
                layout.add(irField);
            }
            type.setLayoutFields(layout);
            collectConstructor(type, declaration, resolver, hierarchy, diagnostics);
            collectDestructor(type, declaration);
            if (type.isEnum()) {
                for (MethodDeclaration method : enumSyntheticMethods(declaration)) {
                    collectClassMethod(type, method, resolver, hierarchy, diagnostics, true);
                }
            }
            for (MethodDeclaration method : declaration.methods()) {
                collectClassMethod(type, method, resolver, hierarchy, diagnostics);
            }
            for (MethodDeclaration method : TestHarnessSynthesizer.synthesize(
                    type, declaration, hierarchy, diagnostics)) {
                collectClassMethod(type, method, resolver, hierarchy, diagnostics, true);
            }
        } else {
            InterfaceDeclaration declaration = (InterfaceDeclaration) type.declaration();
            type.setLayoutFields(List.of());
            for (FieldDeclaration field : declaration.fields()) {
                IrType fieldType = resolveType(type, field.type(), resolver, diagnostics, true);
                if (fieldType.isTypeParameter()) {
                    fieldType = IrType.reference(ROOT_OBJECT);
                }
                if (fieldType.equals(IrType.VOID)) {
                    diagnostics.add(error(field.nameSpan(),
                            "field '" + field.name() + "' cannot have type void"));
                    fieldType = IrType.I32;
                }
                if (type.declaredFields().containsKey(field.name())) {
                    diagnostics.add(error(field.nameSpan(), "duplicate field '" + field.name()
                            + "' in interface '" + type.name() + "'"));
                    continue;
                }
                type.addField(field.name(), new FieldSymbol(field, AccessModifier.PUBLIC,
                        fieldType, type.name(), null, null, null));
            }
            for (InterfaceMethodDeclaration method : declaration.methods()) {
                collectInterfaceMethod(type, method, resolver, hierarchy, diagnostics);
            }
        }
        collecting.remove(type);
        collected.add(type);
    }

    private void collectConstructor(TypeSymbol type, ClassDeclaration declaration,
                                    TypeResolver resolver, ClassHierarchy hierarchy,
                                    List<Diagnostic> diagnostics) {
        if (type.isAnonymousClass() || type.isEnumConstantClass()) {
            collectAnonymousConstructors(type, declaration, hierarchy, diagnostics);
            return;
        }
        if (declaration.constructors().isEmpty()) {
            Block body = new Block(List.of(), declaration.span());
            AccessModifier constructorAccess = type.isEnum()
                    ? AccessModifier.PRIVATE : declaration.accessModifier();
            CallableSymbol constructor = new CallableSymbol(type.name(), type.simpleName(), constructorAccess,
                    false, IrCallableKind.CONSTRUCTOR, IrType.VOID, List.of(), List.of(), Optional.of(body), Optional.empty(),
                    Optional.empty(), false, false, true, declaration.nameSpan(), declaration.span(),
                    "ironwood." + type.name() + ".<init>");
            if (type.superclassType().isPresent()) {
                CallableSymbol implicitSuper = hierarchy.constructors(
                                type.superclassType().orElseThrow()).stream()
                        .filter(candidate -> candidate.parameterTypes().isEmpty())
                        .findFirst().orElse(null);
                if (implicitSuper != null) {
                    constructor = constructor.withThrownTypes(implicitSuper.thrownTypes());
                }
            }
            if (type.isInnerClass()) {
                constructor = constructor.withEnclosingInstance(
                        type.enclosingType().orElseThrow().selfType());
            }
            type.setConstructors(List.of(constructor));
            return;
        }
        List<CallableSymbol> constructors = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        boolean overloaded = declaration.constructors().size() > 1;
        for (ConstructorDeclaration constructor : declaration.constructors()) {
            if (type.isEnum()) {
                if (constructor.accessModifier() == AccessModifier.PUBLIC
                        || constructor.accessModifier() == AccessModifier.PROTECTED) {
                    diagnostics.add(error(constructor.nameSpan(),
                            "enum constructors are implicitly private"));
                }
                if (!constructor.typeParameters().isEmpty()) {
                    diagnostics.add(error(constructor.typeParameters().getFirst().span(),
                            "enum constructors cannot declare type parameters"));
                }
                if (constructor.superInvocation().isPresent()) {
                    diagnostics.add(error(constructor.superInvocation().orElseThrow().span(),
                            "enum constructors cannot invoke super(...) explicitly"));
                }
            }
            String declarationId = callableDeclarationId(type, "<init>", constructor.span());
            List<TypeVariableSymbol> typeVariables = callableTypeVariables(type, declarationId,
                    constructor.typeParameters(), false, diagnostics);
            List<IrType> parameterTypes = resolveParameterTypes(type, constructor.parameters(), resolver,
                    diagnostics, false, typeVariables);
            List<IrType> thrownTypes = resolveThrownTypes(type, constructor.thrownTypes(), resolver,
                    hierarchy, diagnostics, false, typeVariables);
            String signature = signatureKey(type.simpleName(), parameterTypes);
            String erasedSignature = signatureKey(type.simpleName(),
                    parameterTypes.stream().map(IrType::erasure).toList());
            if (!signatures.add(erasedSignature)) {
                diagnostics.add(error(constructor.nameSpan(), "duplicate constructor '" + signature
                        + "' in class '" + declaration.name()
                        + "' after generic erasure (" + erasedSignature + ")"));
                continue;
            }
            AccessModifier constructorAccess = type.isEnum()
                    ? AccessModifier.PRIVATE : constructor.accessModifier();
            CallableSymbol symbol = new CallableSymbol(type.name(), type.simpleName(), constructorAccess,
                    false, IrCallableKind.CONSTRUCTOR, IrType.VOID, parameterTypes, constructor.parameters(),
                    Optional.of(constructor.body()), constructor.superInvocation(), constructor.thisInvocation(),
                    false, false, false, constructor.nameSpan(), constructor.span(),
                    linkageName("ironwood." + type.name() + ".<init>", parameterTypes, overloaded),
                    Optional.empty(), null, declarationId, typeVariables, thrownTypes);
            if (type.isInnerClass()) {
                symbol = symbol.withEnclosingInstance(type.enclosingType().orElseThrow().selfType());
            }
            constructors.add(symbol);
        }
        type.setConstructors(constructors);
    }

    private void collectDestructor(TypeSymbol type, ClassDeclaration declaration) {
        if (type.isEnum() || type.isEnumConstantClass()) {
            return;
        }
        boolean inherited = type.superclass().flatMap(TypeSymbol::destructor).isPresent();
        if (declaration.destructor().isEmpty() && !inherited) {
            return;
        }
        var sourceDestructor = declaration.destructor().orElse(null);
        Block body = sourceDestructor == null
                ? new Block(List.of(), declaration.span()) : sourceDestructor.body();
        SourceSpan nameSpan = sourceDestructor == null
                ? declaration.nameSpan() : sourceDestructor.keywordSpan();
        SourceSpan span = sourceDestructor == null
                ? declaration.span() : sourceDestructor.span();
        type.setDestructor(new CallableSymbol(type.name(), "<destructor>",
                AccessModifier.PRIVATE, false, IrCallableKind.DESTRUCTOR, IrType.VOID,
                List.of(), List.of(), Optional.of(body), Optional.empty(), Optional.empty(),
                false, true, sourceDestructor == null, nameSpan, span,
                "ironwood." + type.name() + ".<destructor>", Optional.empty(), null,
                type.name() + "#<destructor>", List.of(), List.of()));
    }

    private void addEnumFields(TypeSymbol type, ClassDeclaration declaration,
                               List<IrField> layout, List<Diagnostic> diagnostics) {
        SourceSpan span = declaration.nameSpan();
        TypeName stringType = TypeName.reference("ironwood.lang.String", span);
        FieldDeclaration nameDeclaration = new FieldDeclaration(AccessModifier.PRIVATE,
                false, false, stringType, TypeSymbol.ENUM_NAME_FIELD, span,
                Optional.empty(), span);
        IrField nameField = new IrField(type.name(), TypeSymbol.ENUM_NAME_FIELD,
                IrType.reference("ironwood.lang.String"), layout.size(), span);
        type.addField(TypeSymbol.ENUM_NAME_FIELD, new FieldSymbol(nameDeclaration,
                AccessModifier.PRIVATE, nameField.type(), type.name(), nameField, null, null));
        layout.add(nameField);

        TypeName ordinalType = TypeName.primitive(TypeName.Kind.INT, span);
        FieldDeclaration ordinalDeclaration = new FieldDeclaration(AccessModifier.PRIVATE,
                false, false, ordinalType, TypeSymbol.ENUM_ORDINAL_FIELD, span,
                Optional.empty(), span);
        IrField ordinalField = new IrField(type.name(), TypeSymbol.ENUM_ORDINAL_FIELD,
                IrType.I32, layout.size(), span);
        type.addField(TypeSymbol.ENUM_ORDINAL_FIELD, new FieldSymbol(ordinalDeclaration,
                AccessModifier.PRIVATE, IrType.I32, type.name(), ordinalField, null, null));
        layout.add(ordinalField);

        Set<String> constants = new LinkedHashSet<>();
        for (var constant : declaration.enumConstants()) {
            if (!constants.add(constant.name())) {
                diagnostics.add(error(constant.nameSpan(), "duplicate enum constant '"
                        + constant.name() + "' in enum '" + type.name() + "'"));
                continue;
            }
            if (type.declaredFields().containsKey(constant.name())) {
                diagnostics.add(error(constant.nameSpan(), "enum constant '"
                        + constant.name() + "' conflicts with another member"));
                continue;
            }
            FieldDeclaration field = new FieldDeclaration(AccessModifier.PUBLIC,
                    true, true, TypeName.reference(type.sourceName(), constant.nameSpan()),
                    constant.name(), constant.nameSpan(), Optional.empty(), constant.span());
            type.addField(constant.name(), new FieldSymbol(field, AccessModifier.PUBLIC,
                    type.selfType(), type.name(), null, null, null));
        }
    }

    private List<MethodDeclaration> enumSyntheticMethods(ClassDeclaration declaration) {
        SourceSpan span = declaration.nameSpan();
        TypeName stringType = TypeName.reference("ironwood.lang.String", span);
        TypeName intType = TypeName.primitive(TypeName.Kind.INT, span);
        TypeName enumType = TypeName.reference(declaration.name(), span);
        List<MethodDeclaration> methods = new ArrayList<>();
        methods.add(enumMethod(false, true, stringType, "name", List.of(),
                List.of(new ReturnStatement(Optional.of(
                        new NameExpression(TypeSymbol.ENUM_NAME_FIELD, span)), span)), span));
        methods.add(enumMethod(false, true, intType, "ordinal", List.of(),
                List.of(new ReturnStatement(Optional.of(
                        new NameExpression(TypeSymbol.ENUM_ORDINAL_FIELD, span)), span)), span));
        boolean declaresToString = declaration.methods().stream()
                .anyMatch(method -> method.name().equals("toString")
                        && method.parameters().isEmpty());
        if (!declaresToString) {
            methods.add(enumMethod(false, false, stringType, "toString", List.of(),
                    List.of(new ReturnStatement(Optional.of(
                            new NameExpression(TypeSymbol.ENUM_NAME_FIELD, span)), span)), span));
        }
        methods.add(enumMethod(true, true, intType, "valueCount", List.of(),
                List.of(new ReturnStatement(Optional.of(new ironwood.compiler.ast.IntegerLiteralExpression(
                        Integer.toString(declaration.enumConstants().size()), span)), span)), span));
        List<ironwood.compiler.ast.Expression> constants = declaration.enumConstants().stream()
                .map(constant -> (ironwood.compiler.ast.Expression) new NameExpression(
                        constant.name(), constant.nameSpan()))
                .toList();
        ArrayCreationExpression values = new ArrayCreationExpression(enumType, Optional.empty(),
                Optional.of(new ArrayInitializerExpression(constants, span)), span);
        methods.add(enumMethod(true, true, TypeName.array(enumType, span), "values", List.of(),
                List.of(new ReturnStatement(Optional.of(values), span)), span));

        Parameter ordinal = new Parameter(intType, "ordinal", span, span);
        List<ironwood.compiler.ast.Statement> valueAtBody = new ArrayList<>();
        for (int index = 0; index < declaration.enumConstants().size(); index++) {
            var constant = declaration.enumConstants().get(index);
            BinaryExpression matches = new BinaryExpression(new NameExpression("ordinal", span),
                    BinaryOperator.EQUAL,
                    new ironwood.compiler.ast.IntegerLiteralExpression(Integer.toString(index), span),
                    span, span);
            valueAtBody.add(new IfStatement(matches,
                    new ReturnStatement(Optional.of(new NameExpression(constant.name(),
                            constant.nameSpan())), constant.span()), Optional.empty(), constant.span()));
        }
        valueAtBody.add(enumInvalidLookup(span));
        methods.add(enumMethod(true, true, enumType, "valueAt", List.of(ordinal),
                valueAtBody, span));

        Parameter name = new Parameter(stringType, "name", span, span);
        List<ironwood.compiler.ast.Statement> valueOfBody = new ArrayList<>();
        BinaryExpression nullName = new BinaryExpression(new NameExpression("name", span),
                BinaryOperator.EQUAL, new NullLiteralExpression(span), span, span);
        valueOfBody.add(new IfStatement(nullName, enumInvalidLookup(span),
                Optional.empty(), span));
        for (var constant : declaration.enumConstants()) {
            CallExpression matches = new CallExpression(Optional.of(new NameExpression("name", span)),
                    "equals", span, List.of(new StringLiteralExpression(constant.name(),
                    constant.nameSpan())), constant.span());
            valueOfBody.add(new IfStatement(matches,
                    new ReturnStatement(Optional.of(new NameExpression(constant.name(),
                            constant.nameSpan())), constant.span()), Optional.empty(), constant.span()));
        }
        valueOfBody.add(enumInvalidLookup(span));
        methods.add(enumMethod(true, true, enumType, "valueOf", List.of(name),
                valueOfBody, span));
        return List.copyOf(methods);
    }

    private MethodDeclaration enumMethod(boolean isStatic, boolean isFinal, TypeName returnType,
                                         String name, List<Parameter> parameters,
                                         List<ironwood.compiler.ast.Statement> statements,
                                         SourceSpan span) {
        return new MethodDeclaration(AccessModifier.PUBLIC, isStatic, false, isFinal,
                false, false, List.of(), returnType, name, span, parameters, List.of(),
                Optional.of(new Block(statements, span)), span);
    }

    private ThrowStatement enumInvalidLookup(SourceSpan span) {
        return new ThrowStatement(new NewExpression("ironwood.lang.IllegalArgumentException",
                span, List.of(), span), span);
    }

    private void collectAnonymousConstructors(TypeSymbol type, ClassDeclaration declaration,
                                              ClassHierarchy hierarchy,
                                              List<Diagnostic> diagnostics) {
        IrType superType = type.superclassType().orElse(null);
        if (superType == null) {
            diagnostics.add(error(declaration.nameSpan(),
                    "anonymous class has no resolved superclass constructor target"));
            type.setConstructors(List.of());
            return;
        }
        List<CallableSymbol> superConstructors = hierarchy.constructors(superType);
        if (superConstructors.isEmpty()) {
            diagnostics.add(error(declaration.nameSpan(), "anonymous class target '"
                    + superType.displayName() + "' has no constructor"));
            type.setConstructors(List.of());
            return;
        }
        boolean overloaded = superConstructors.size() > 1;
        List<CallableSymbol> constructors = new ArrayList<>();
        for (int index = 0; index < superConstructors.size(); index++) {
            CallableSymbol target = superConstructors.get(index);
            if (!anonymousSuperclassConstructorAccessible(type, target, hierarchy)) {
                continue;
            }
            String declarationId = type.name() + "#anonymous-constructor:" + index;
            String linkage = linkageName("ironwood." + type.name() + ".<init>",
                    target.parameterTypes(), overloaded);
            CallableSymbol constructor = new CallableSymbol(type.name(), type.simpleName(),
                    target.accessModifier(), false, IrCallableKind.CONSTRUCTOR, IrType.VOID,
                    target.parameterTypes(), target.parameters(),
                    Optional.of(new Block(List.of(), declaration.span())), Optional.empty(),
                    Optional.empty(), false, false, true, declaration.nameSpan(),
                    declaration.span(), linkage, target.enclosingInstanceType(), null,
                    declarationId, target.typeVariables(), target.thrownTypes());
            constructors.add(constructor);
            type.addAnonymousConstructorForwarding(constructor, target, superType);
        }
        if (constructors.isEmpty()) {
            diagnostics.add(error(declaration.nameSpan(), "anonymous class target '"
                    + superType.displayName() + "' has no accessible constructor"));
        }
        type.setConstructors(constructors);
    }

    private static boolean anonymousSuperclassConstructorAccessible(TypeSymbol anonymous,
                                                                    CallableSymbol target,
                                                                    ClassHierarchy hierarchy) {
        TypeSymbol owner = hierarchy.type(target.ownerType()).orElse(null);
        if (owner == null) {
            return false;
        }
        return switch (target.accessModifier()) {
            case PUBLIC -> true;
            case PRIVATE -> hierarchy.sameNest(owner.name(), anonymous.name());
            case PACKAGE_PRIVATE -> owner.packageName().equals(anonymous.packageName());
            case PROTECTED -> owner.packageName().equals(anonymous.packageName())
                    || hierarchy.isSubtype(anonymous.name(), owner.name());
        };
    }

    private void collectClassMethod(TypeSymbol type, MethodDeclaration method,
                                    TypeResolver resolver, ClassHierarchy hierarchy,
                                    List<Diagnostic> diagnostics) {
        collectClassMethod(type, method, resolver, hierarchy, diagnostics, false);
    }

    private void collectClassMethod(TypeSymbol type, MethodDeclaration method,
                                    TypeResolver resolver, ClassHierarchy hierarchy,
                                    List<Diagnostic> diagnostics, boolean synthetic) {
        if (method.isAbstract() && !type.isAbstract()) {
            diagnostics.add(error(method.nameSpan(), "abstract method '" + method.name()
                    + "' may only be declared in an abstract class"));
        }
        if (method.isAbstract() && method.isStatic()) {
            diagnostics.add(error(method.nameSpan(), "abstract method '" + method.name()
                    + "' cannot be static"));
        }
        if (method.isAbstract() && method.isFinal()) {
            diagnostics.add(error(method.nameSpan(), "abstract method '" + method.name()
                    + "' cannot be final"));
        }
        if (method.isAbstract() && method.accessModifier() == AccessModifier.PRIVATE) {
            diagnostics.add(error(method.nameSpan(), "abstract method '" + method.name()
                    + "' cannot be private"));
        }
        String declarationId = callableDeclarationId(type, method.name(), method.span());
        List<TypeVariableSymbol> typeVariables = callableTypeVariables(type, declarationId,
                method.typeParameters(), method.isStatic(), diagnostics);
        IrType returnType = resolveType(type, method.returnType(), resolver, diagnostics,
                method.isStatic(), typeVariables);
        List<IrType> parameterTypes = resolveParameterTypes(type, method.parameters(), resolver,
                diagnostics, method.isStatic(), typeVariables);
        List<IrType> thrownTypes = resolveThrownTypes(type, method.thrownTypes(), resolver,
                hierarchy, diagnostics, method.isStatic(), typeVariables);
        ClassDeclaration ownerDeclaration = (ClassDeclaration) type.declaration();
        long overloadCount = ownerDeclaration.methods().stream()
                .filter(candidate -> candidate.name().equals(method.name())).count();
        if (ownerDeclaration.enumType()) {
            overloadCount += enumSyntheticMethods(ownerDeclaration).stream()
                    .filter(candidate -> candidate.name().equals(method.name())).count();
        }
        boolean overloaded = overloadCount > 1;
        CallableSymbol symbol = new CallableSymbol(type.name(), method.name(),
                method.accessModifier(), method.isStatic(), IrCallableKind.METHOD, returnType, parameterTypes,
                method.parameters(), method.body(), Optional.empty(), Optional.empty(),
                method.isAbstract(), method.isFinal(), synthetic, method.nameSpan(), method.span(),
                linkageName("ironwood." + type.name() + "." + method.name(), parameterTypes, overloaded),
                Optional.empty(), null, declarationId, typeVariables, thrownTypes);
        if (type.declaredMethods().values().stream().anyMatch(candidate ->
                candidate.overrideSignatureKey().equals(symbol.overrideSignatureKey()))) {
            diagnostics.add(error(method.nameSpan(), "duplicate method '" + symbol.signatureKey()
                    + "' in class '" + type.name() + "'"));
            return;
        }
        CallableSymbol erasedClash = type.declaredMethods().values().stream()
                .filter(candidate -> candidate.erasedSignatureKey().equals(symbol.erasedSignatureKey()))
                .findFirst().orElse(null);
        if (erasedClash != null) {
            diagnostics.add(error(method.nameSpan(), "method '" + symbol.signatureKey()
                    + "' has the same erased signature as '" + erasedClash.signatureKey()
                    + "' in class '" + type.name() + "'"));
            return;
        }
        type.addMethod(symbol);
        if (method.hasOverrideDirective()) {
            overrideDirectiveDeclarationIds.add(symbol.declarationId());
        }
    }

    private void collectInterfaceMethod(TypeSymbol type, InterfaceMethodDeclaration method,
                                        TypeResolver resolver, ClassHierarchy hierarchy,
                                        List<Diagnostic> diagnostics) {
        String declarationId = callableDeclarationId(type, method.name(), method.span());
        List<TypeVariableSymbol> typeVariables = callableTypeVariables(type, declarationId,
                method.typeParameters(), method.isStatic(), diagnostics);
        List<IrType> parameterTypes = resolveParameterTypes(type, method.parameters(), resolver,
                diagnostics, method.isStatic(), typeVariables);
        List<IrType> thrownTypes = resolveThrownTypes(type, method.thrownTypes(), resolver,
                hierarchy, diagnostics, method.isStatic(), typeVariables);
        boolean overloaded = ((InterfaceDeclaration) type.declaration()).methods().stream()
                .filter(candidate -> candidate.name().equals(method.name())).count() > 1;
        CallableSymbol symbol = new CallableSymbol(type.name(), method.name(),
                method.accessModifier(), method.isStatic(), IrCallableKind.METHOD,
                resolveType(type, method.returnType(), resolver, diagnostics, method.isStatic(),
                        typeVariables),
                parameterTypes, method.parameters(), method.body(), Optional.empty(), Optional.empty(),
                method.isAbstract(), false, false, method.nameSpan(), method.span(),
                linkageName("ironwood.interface." + type.name() + "." + method.name(),
                        parameterTypes, overloaded), Optional.empty(), null, declarationId,
                typeVariables, thrownTypes);
        if (type.declaredMethods().values().stream().anyMatch(candidate ->
                candidate.overrideSignatureKey().equals(symbol.overrideSignatureKey()))) {
            diagnostics.add(error(method.nameSpan(), "duplicate interface method '"
                    + symbol.signatureKey() + "' in interface '" + type.name() + "'"));
            return;
        }
        CallableSymbol erasedClash = type.declaredMethods().values().stream()
                .filter(candidate -> candidate.erasedSignatureKey().equals(symbol.erasedSignatureKey()))
                .findFirst().orElse(null);
        if (erasedClash != null) {
            diagnostics.add(error(method.nameSpan(), "interface method '" + symbol.signatureKey()
                    + "' has the same erased signature as '" + erasedClash.signatureKey()
                    + "' in interface '" + type.name() + "'"));
            return;
        }
        type.addMethod(symbol);
        if (method.hasOverrideDirective()) {
            overrideDirectiveDeclarationIds.add(symbol.declarationId());
        }
    }

    private void validateOverridesAndInterfaces(Map<String, TypeSymbol> types,
                                                ClassHierarchy hierarchy,
                                                List<Diagnostic> diagnostics) {
        validateInheritedMethodErasureClashes(types, hierarchy, diagnostics);
        for (TypeSymbol type : types.values()) {
            source = type.source();
            for (CallableSymbol method : type.declaredMethods().values()) {
                Optional<CallableSymbol> superclassMethod = type.isInterface()
                        ? Optional.empty() : hierarchy.lookupSuperclassMethod(type, method);
                List<CallableSymbol> interfaceMethods = inheritedInterfaceMethods(
                        type, method, hierarchy);
                Optional<CallableSymbol> objectMethod = type.isInterface()
                        ? publicObjectOverrideMethod(method, hierarchy) : Optional.empty();
                if (type.isAnonymousClass()
                        && type.anonymousAllocation().map(NewExpression::diamond).orElse(false)
                        && method.accessModifier() != AccessModifier.PRIVATE) {
                    boolean overrides = superclassMethod.isPresent() || !interfaceMethods.isEmpty();
                    if (!overrides) {
                        diagnostics.add(error(method.nameSpan(), "anonymous class created with diamond "
                                + "cannot declare non-private method '" + method.signatureKey()
                                + "' unless it overrides an inherited method"));
                    }
                }
                int structuralDiagnosticCount = diagnostics.size();
                superclassMethod.ifPresent(parent ->
                        validateOverride(method, parent, hierarchy, diagnostics));
                for (CallableSymbol inherited : interfaceMethods) {
                    validateOverride(method, inherited, hierarchy, diagnostics);
                }
                objectMethod.ifPresent(parent ->
                        validateOverride(method, parent, hierarchy, diagnostics));
                if (type.isInterface() && isDefaultMethod(type, method)) {
                    validateDefaultDoesNotOverrideObject(method, hierarchy, diagnostics);
                }
                validateOverrideDirective(method, superclassMethod, interfaceMethods,
                        objectMethod, diagnostics.size() == structuralDiagnosticCount,
                        diagnostics);
            }
        }
        for (TypeSymbol type : types.values()) {
            source = type.source();
            validateUnifiedImplementations(type, hierarchy, diagnostics);
        }
    }

    private void validateInheritedMethodErasureClashes(Map<String, TypeSymbol> types,
                                                       ClassHierarchy hierarchy,
                                                       List<Diagnostic> diagnostics) {
        for (TypeSymbol type : types.values()) {
            source = type.source();
            for (CallableSymbol method : type.declaredMethods().values()) {
                List<CallableSymbol> inherited = new ArrayList<>();
                if (!type.isInterface()) {
                    inherited.addAll(hierarchy.inheritedSuperclassMethods(type, method.sourceName()));
                }
                Set<String> seen = new LinkedHashSet<>();
                for (IrType exactType : hierarchy.exactSupertypes(type.selfType())) {
                    if (exactType.referenceName().equals(type.name())) {
                        continue;
                    }
                    TypeSymbol owner = hierarchy.type(exactType.referenceName()).orElse(null);
                    if (owner == null || !owner.isInterface()) {
                        continue;
                    }
                    for (CallableSymbol candidate : hierarchy.lookupDeclaredMethods(
                            exactType, method.sourceName())) {
                        if (!candidate.isStatic()
                                && candidate.accessModifier() != AccessModifier.PRIVATE
                                && seen.add(candidate.declarationId())) {
                            inherited.add(candidate);
                        }
                    }
                }
                CallableSymbol erasedClash = inherited.stream()
                        .filter(candidate -> method.erasedSignatureKey().equals(
                                candidate.dispatchKey()))
                        .filter(candidate -> !candidate.overrideSignatureKey().equals(
                                method.overrideSignatureKey()))
                        .findFirst().orElse(null);
                if (erasedClash != null) {
                    diagnostics.add(error(method.nameSpan(), "method '" + method.signatureKey()
                            + "' in class '" + type.name()
                            + "' has the same erased signature as inherited method '"
                            + erasedClash.signatureKey() + "' from '" + erasedClash.ownerType()
                            + "' but does not override it"));
                }
            }
        }
    }

    private List<CallableSymbol> inheritedInterfaceMethods(TypeSymbol type, CallableSymbol method,
                                                           ClassHierarchy hierarchy) {
        List<CallableSymbol> inherited = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IrType exactType : hierarchy.exactSupertypes(type.selfType())) {
            if (exactType.referenceName().equals(type.name())) {
                continue;
            }
            TypeSymbol owner = hierarchy.type(exactType.referenceName()).orElse(null);
            if (owner == null || !owner.isInterface()) {
                continue;
            }
            for (CallableSymbol candidate : hierarchy.lookupDeclaredMethods(
                    exactType, method.sourceName())) {
                if (candidate.isStatic() || candidate.accessModifier() == AccessModifier.PRIVATE
                        || !candidate.overrideSignatureKey().equals(method.overrideSignatureKey())) {
                    continue;
                }
                if (seen.add(candidate.ownerType() + "#" + candidate.overrideSignatureKey())) {
                    inherited.add(candidate);
                }
            }
        }
        return List.copyOf(inherited);
    }

    private Optional<CallableSymbol> publicObjectOverrideMethod(CallableSymbol method,
                                                                 ClassHierarchy hierarchy) {
        if (method.isStatic()) {
            return Optional.empty();
        }
        return hierarchy.type(ROOT_OBJECT).stream()
                .flatMap(object -> object.declaredMethodsNamed(method.sourceName()).stream())
                .filter(candidate -> !candidate.isStatic()
                        && candidate.accessModifier() == AccessModifier.PUBLIC)
                .filter(candidate -> candidate.overrideSignatureKey()
                        .equals(method.overrideSignatureKey()))
                .findFirst();
    }

    private void validateOverrideDirective(CallableSymbol method,
                                           Optional<CallableSymbol> superclassMethod,
                                           List<CallableSymbol> interfaceMethods,
                                           Optional<CallableSymbol> objectMethod,
                                           boolean structurallyValid,
                                           List<Diagnostic> diagnostics) {
        if (method.isSynthetic()) {
            return;
        }
        boolean hasDirective = overrideDirectiveDeclarationIds.contains(method.declarationId());
        boolean hasInstanceTarget = !method.isStatic()
                && (superclassMethod.filter(candidate -> !candidate.isStatic()).isPresent()
                || interfaceMethods.stream().anyMatch(candidate -> !candidate.isStatic())
                || objectMethod.isPresent());
        if (hasDirective && !hasInstanceTarget && structurallyValid) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.signatureKey()
                    + "' declared @Override does not override or implement an inherited instance method"));
        } else if (!hasDirective && hasInstanceTarget && structurallyValid) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.signatureKey()
                    + "' overrides or implements an inherited method and must be declared @Override"));
        }
    }

    private void validateOverride(CallableSymbol method, CallableSymbol inherited,
                                  ClassHierarchy hierarchy, List<Diagnostic> diagnostics) {
        if (inherited.accessModifier() == AccessModifier.PRIVATE) {
            return;
        }
        if (inherited.isFinal()) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.sourceName()
                    + "' cannot override final method inherited from '" + inherited.ownerType() + "'"));
        }
        if (method.isStatic() != inherited.isStatic()) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.sourceName()
                    + "' cannot change between static and instance form when inherited from '"
                    + inherited.ownerType() + "'"));
        }
        IrType adaptedReturnType = method.adaptTypeVariablesTo(method.returnType(), inherited);
        if (!returnTypeCompatible(inherited.returnType(), adaptedReturnType, hierarchy)) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.sourceName()
                    + "' has incompatible return type " + method.returnType().displayName()
                    + "; inherited declaration in '" + inherited.ownerType() + "' returns "
                    + inherited.returnType().displayName()));
        }
        if (accessRank(method.accessModifier()) < accessRank(inherited.accessModifier())) {
            diagnostics.add(error(method.nameSpan(), "method '" + method.sourceName()
                    + "' cannot narrow " + inherited.accessModifier().name().toLowerCase().replace('_', '-')
                    + " access inherited from '" + inherited.ownerType() + "'"));
        }
        validateThrowsCompatibility(method, inherited, hierarchy, diagnostics);
    }

    private void validateThrowsCompatibility(CallableSymbol method, CallableSymbol inherited,
                                             ClassHierarchy hierarchy,
                                             List<Diagnostic> diagnostics) {
        for (IrType declared : method.thrownTypes()) {
            IrType adapted = method.adaptTypeVariablesTo(declared, inherited);
            if (!isCheckedException(adapted, hierarchy)) {
                continue;
            }
            boolean compatible = inherited.thrownTypes().stream()
                    .anyMatch(allowed -> hierarchy.isSubtype(adapted, allowed));
            if (!compatible) {
                diagnostics.add(error(method.nameSpan(), "method '" + method.sourceName()
                        + "' cannot throw checked exception " + declared.displayName()
                        + "; inherited declaration in '" + inherited.ownerType()
                        + "' does not permit it"));
            }
        }
    }

    private static boolean isCheckedException(IrType type, ClassHierarchy hierarchy) {
        return hierarchy.isSubtype(type, IrType.reference("ironwood.lang.Throwable"))
                && !hierarchy.isSubtype(type, IrType.reference("ironwood.lang.RuntimeException"))
                && !hierarchy.isSubtype(type, IrType.reference("ironwood.lang.Error"));
    }

    private static boolean isDefaultMethod(TypeSymbol owner, CallableSymbol method) {
        return owner.isInterface() && !method.isStatic() && !method.isAbstract()
                && method.accessModifier() == AccessModifier.PUBLIC;
    }

    private void validateDefaultDoesNotOverrideObject(CallableSymbol method,
                                                      ClassHierarchy hierarchy,
                                                      List<Diagnostic> diagnostics) {
        TypeSymbol object = hierarchy.type(ROOT_OBJECT).orElse(null);
        if (object == null) {
            return;
        }
        CallableSymbol match = object.declaredMethodsNamed(method.sourceName()).stream()
                .filter(candidate -> !candidate.isStatic()
                        && candidate.accessModifier() == AccessModifier.PUBLIC)
                .filter(candidate -> candidate.overrideSignatureKey()
                        .equals(method.overrideSignatureKey()))
                .findFirst().orElse(null);
        if (match != null) {
            diagnostics.add(error(method.nameSpan(), "default interface method '"
                    + method.signatureKey() + "' cannot override public Object method declared in '"
                    + ROOT_OBJECT + "'"));
        }
    }

    private void validateUnifiedImplementations(TypeSymbol type, ClassHierarchy hierarchy,
                                                List<Diagnostic> diagnostics) {
        Map<String, List<CallableSymbol>> interfaceRequirements = new LinkedHashMap<>();
        Map<String, CallableSymbol> uniqueRequirements = new LinkedHashMap<>();
        for (IrType exactType : hierarchy.exactSupertypes(type.selfType())) {
            TypeSymbol owner = hierarchy.type(exactType.referenceName()).orElse(null);
            if (owner == null || !owner.isInterface()) {
                continue;
            }
            for (CallableSymbol method : owner.declaredMethods().values()) {
                CallableSymbol requirement = method.substitute(owner.substitutionFor(exactType));
                if (requirement.isStatic()
                        || requirement.accessModifier() == AccessModifier.PRIVATE) {
                    continue;
                }
                uniqueRequirements.putIfAbsent(requirement.declarationId(), requirement);
                // Override equivalence is a source-level property after applying the exact
                // generic supertype substitution. The original erased dispatch keys remain
                // distinct ABI slots, but all source-equivalent requirements must be checked
                // as one contract.
                interfaceRequirements.computeIfAbsent(requirement.overrideSignatureKey(), ignored ->
                        new ArrayList<>()).add(requirement);
            }
        }
        List<CallableSymbol> inheritedRequirements = List.copyOf(uniqueRequirements.values());
        for (int left = 0; left < inheritedRequirements.size(); left++) {
            for (int right = left + 1; right < inheritedRequirements.size(); right++) {
                CallableSymbol first = inheritedRequirements.get(left);
                CallableSymbol second = inheritedRequirements.get(right);
                if (!first.ownerType().equals(type.name())
                        && !second.ownerType().equals(type.name())
                        && first.dispatchKey().equals(second.dispatchKey())
                        && !first.overrideSignatureKey().equals(second.overrideSignatureKey())) {
                    diagnostics.add(error(type.declaration().nameSpan(), "type '" + type.name()
                            + "' inherits methods '" + first.signatureKey() + "' from '"
                            + first.ownerType() + "' and '" + second.signatureKey() + "' from '"
                            + second.ownerType() + "' with the same erased signature but neither "
                            + "method overrides the other"));
                }
            }
        }
        for (Map.Entry<String, List<CallableSymbol>> entry : interfaceRequirements.entrySet()) {
            List<CallableSymbol> requirements = entry.getValue();
            for (int left = 0; left < requirements.size(); left++) {
                for (int right = left + 1; right < requirements.size(); right++) {
                    CallableSymbol first = requirements.get(left);
                    CallableSymbol second = requirements.get(right);
                    IrType secondReturn = second.adaptTypeVariablesTo(second.returnType(), first);
                    IrType firstReturn = first.adaptTypeVariablesTo(first.returnType(), second);
                    if (!returnTypeCompatible(first.returnType(), secondReturn, hierarchy)
                            && !returnTypeCompatible(second.returnType(), firstReturn, hierarchy)) {
                        diagnostics.add(error(type.declaration().nameSpan(),
                                "incompatible inherited interface method '" + first.signatureKey()
                                        + "' from '" + first.ownerType() + "' and '"
                                        + second.ownerType() + "'"));
                    }
                }
            }
            ClassHierarchy.DefaultResolution resolution = hierarchy.resolveDispatch(
                    type.selfType(), requirements.getFirst().dispatchKey());
            if (resolution.conflict()) {
                String conflictKinds = resolution.abstractDefaultConflict()
                        ? "unrelated abstract and default methods"
                        : "unrelated default methods";
                diagnostics.add(error(type.declaration().nameSpan(), "type '" + type.name()
                        + "' inherits " + conflictKinds + " '"
                        + requirements.getFirst().signatureKey() + "' from "
                        + resolution.maximallySpecific().stream().map(CallableSymbol::ownerType)
                        .distinct().reduce((left, right) -> left + " and " + right)
                        .orElse("interfaces")
                        + "; declare an override to resolve the conflict"));
                continue;
            }
            CallableSymbol implementation = resolution.selected().orElse(null);
            if (implementation == null) {
                CallableSymbol invalidCandidate = resolution.classDeclaration().orElseGet(() ->
                        hierarchy.lookupDeclaredMethods(type.selfType(),
                                requirements.getFirst().sourceName()).stream()
                        .filter(candidate -> candidate.overrideSignatureKey().equals(
                                requirements.getFirst().overrideSignatureKey()))
                        .findFirst().orElse(null));
                if (invalidCandidate != null && !invalidCandidate.isAbstract()) {
                    diagnostics.add(error(invalidCandidate.nameSpan(), "method '"
                            + invalidCandidate.sourceName() + "' in type '"
                            + invalidCandidate.ownerType()
                            + "' does not provide the required public instance signature '"
                            + requirements.getFirst().signatureKey() + "' from interface '"
                            + requirements.getFirst().ownerType() + "'"));
                    continue;
                }
                if (!type.isInterface() && !type.isAbstract()) {
                    diagnostics.add(error(type.declaration().nameSpan(), "class '" + type.name()
                            + "' does not implement interface method '"
                            + requirements.getFirst().signatureKey() + "'"));
                }
                continue;
            }
            for (CallableSymbol requirement : requirements) {
                IrType implementationReturn = implementation.adaptTypeVariablesTo(
                        implementation.returnType(), requirement);
                if (implementation.accessModifier() != AccessModifier.PUBLIC
                        || implementation.isStatic()
                        || !returnTypeCompatible(requirement.returnType(),
                        implementationReturn, hierarchy)) {
                    diagnostics.add(error(implementation.nameSpan(), "method '"
                            + implementation.sourceName() + "' in type '"
                            + implementation.ownerType()
                            + "' does not provide the required public instance signature '"
                            + requirement.signatureKey() + "' from interface '"
                            + requirement.ownerType() + "'"));
                }
                if (!implementation.ownerType().equals(type.name())) {
                    validateThrowsCompatibility(implementation, requirement, hierarchy, diagnostics);
                }
            }
        }

        if (!type.isInterface() && !type.isAbstract()) {
            Map<String, CallableSymbol> abstractRequirements = new LinkedHashMap<>();
            for (IrType exactType : hierarchy.exactSupertypes(type.selfType())) {
                TypeSymbol owner = hierarchy.type(exactType.referenceName()).orElse(null);
                if (owner == null || owner.isInterface()) {
                    continue;
                }
                Map<String, IrType> substitution = owner.substitutionFor(exactType);
                for (CallableSymbol declaration : owner.declaredMethods().values()) {
                    if (declaration.isAbstract()) {
                        CallableSymbol requirement = declaration.substitute(substitution);
                        abstractRequirements.putIfAbsent(requirement.dispatchKey(), requirement);
                    }
                }
            }
            for (CallableSymbol requirement : abstractRequirements.values()) {
                if (hierarchy.resolveDispatch(type.selfType(), requirement.dispatchKey())
                        .selected().isPresent()) {
                    continue;
                }
                diagnostics.add(error(type.declaration().nameSpan(), "concrete class '" + type.name()
                        + "' does not implement abstract method '" + requirement.signatureKey()
                        + "' inherited from '" + requirement.ownerType() + "'"));
            }
        }
    }

    private void validateThrowableTraceLayout(Map<String, TypeSymbol> types, List<Diagnostic> diagnostics) {
        TypeSymbol throwable = types.get("ironwood.lang.Throwable");
        if (throwable == null) return;
        List<IrField> fields = throwable.layoutFields();
        if (throwable.declaredFields().values().stream().anyMatch(field -> field.accessModifier() != AccessModifier.PRIVATE)
                || !fields.stream().map(IrField::name).toList().equals(List.of(
                "message", "cause", "traceState", "ownedMessage", "causeInitialized"))
                || !fields.stream().map(IrField::type).toList().equals(List.of(
                        IrType.reference("ironwood.lang.String"), IrType.reference("ironwood.lang.Throwable"),
                        IrType.I64, IrType.reference("ironwood.lang.String"), IrType.I1))) {
            diagnostics.add(Diagnostic.error(throwable.source(), throwable.declaration().span(),
                    "bundled Throwable requires the current message, cause, traceState, ownedMessage and causeInitialized layout; rebuild the standard library"));
        }
    }

    private void validateStackTraceElementLayout(Map<String, TypeSymbol> types,
                                                 List<Diagnostic> diagnostics) {
        TypeSymbol element = types.get("ironwood.lang.StackTraceElement");
        if (element == null) return;
        List<IrField> fields = element.layoutFields();
        if (element.declaredFields().values().stream()
                .anyMatch(field -> field.accessModifier() != AccessModifier.PRIVATE)
                || !fields.stream().map(IrField::name).toList().equals(List.of(
                "declaringClass", "methodName", "fileName", "lineNumber"))
                || !fields.stream().map(IrField::type).toList().equals(List.of(
                IrType.reference("ironwood.lang.String"),
                IrType.reference("ironwood.lang.String"),
                IrType.reference("ironwood.lang.String"), IrType.I32))) {
            diagnostics.add(Diagnostic.error(element.source(), element.declaration().span(),
                    "bundled StackTraceElement requires the current immutable frame layout; rebuild the standard library"));
        }
    }

    private void validateEnumBaseUsage(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                                       List<Diagnostic> diagnostics) {
        for (TypeSymbol type : types.values()) {
            if (type.isInterface() || type.isEnum() || type.isEnumConstantClass()
                    || type.name().equals(ROOT_ENUM)) {
                continue;
            }
            if (hierarchy.isSubtype(type.name(), ROOT_ENUM)) {
                diagnostics.add(Diagnostic.error(type.source(), type.declaration().nameSpan(),
                        "only enum declarations may extend bundled base class '"
                                + ROOT_ENUM + "'"));
            }
        }
    }

    private List<IrDispatchSlot> buildDispatchSlots(Map<String, TypeSymbol> types) {
        Map<String, IrDispatchSlot> slots = new LinkedHashMap<>();
        for (TypeSymbol type : types.values()) {
            for (CallableSymbol method : type.declaredMethods().values()) {
                if (method.isStatic() || method.accessModifier() == AccessModifier.PRIVATE) {
                    continue;
                }
                slots.computeIfAbsent(method.dispatchKey(), key -> new IrDispatchSlot(slots.size(), key,
                        method.sourceName(), method.returnType().erasure(),
                        method.parameterTypes().stream().map(IrType::erasure).toList(), method.nameSpan()));
            }
        }
        return List.copyOf(slots.values());
    }

    private void buildIrTypes(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                              List<IrDispatchSlot> slots,
                              EscapeSummaryAnalyzer escapeSummaries) {
        for (TypeSymbol type : types.values()) {
            List<Integer> membership = hierarchy.allSupertypesInclusive(type).stream()
                    .map(TypeSymbol::typeId).distinct().sorted().toList();
            List<IrDispatchEntry> entries = new ArrayList<>();
            if (!type.isInterface()) {
                for (IrDispatchSlot slot : slots) {
                    hierarchy.resolveDispatchImplementation(type, slot.key())
                            .ifPresent(method -> entries.add(new IrDispatchEntry(slot,
                                    method.linkageName())));
                }
            }
            type.setIrClass(new IrClass(type.name(),
                    type.isInterface() ? IrTypeKind.INTERFACE : IrTypeKind.CLASS,
                    type.superclass().map(TypeSymbol::name),
                    type.directInterfaces().stream().map(TypeSymbol::name).toList(),
                    type.layoutFields(), type.typeId(), membership, entries,
                    type.destructor().map(CallableSymbol::linkageName),
                    type.constructorRollback(),
                    toStringReturnsOwnedFresh(entries, escapeSummaries),
                    localizedMessageReturnsOwnedFresh(type, escapeSummaries),
                    type.declaration().span()));
        }
    }

    private static boolean toStringReturnsOwnedFresh(
            List<IrDispatchEntry> entries, EscapeSummaryAnalyzer escapeSummaries) {
        for (IrDispatchEntry entry : entries) {
            IrDispatchSlot slot = entry.slot();
            if (!slot.methodName().equals("toString") || !slot.parameterTypes().isEmpty()
                    || !slot.returnType().equals(
                    IrType.reference("ironwood.lang.String"))) {
                continue;
            }
            EscapeSummaryAnalyzer.EscapeSummary summary =
                    escapeSummaries.summary(entry.targetLinkageName());
            return summary != null && summary.returnsOwnedFresh();
        }
        return false;
    }

    private static boolean localizedMessageReturnsOwnedFresh(TypeSymbol type,
            EscapeSummaryAnalyzer escapeSummaries) {
        if (!ThrowableSemantics.isThrowable(type)) {
            return false;
        }
        CallableSymbol getter = ThrowableSemantics.messageGetter(type);
        return getter != null && escapeSummaries.summary(getter).returnsOwnedFresh();
    }

    private List<IrFunction> buildConstructorRollbackFunctions(
            Map<String, TypeSymbol> types, OwnedArrayFieldAnalyzer ownership) {
        List<IrFunction> result = new ArrayList<>();
        for (TypeSymbol type : types.values()) {
            if (type.isInterface() || type.isEnum() || type.isEnumConstantClass()) {
                continue;
            }
            String linkage = "ironwood." + type.name() + ".<constructor-rollback>";
            type.setConstructorRollback(linkage);
            IrValueReference receiver = new IrValueReference(0, type.selfType(),
                    type.declaration().nameSpan());
            List<IrInstruction> instructions = new ArrayList<>();
            int nextValue = 1;
            List<FieldSymbol> fields = new ArrayList<>(ownership.ownedInstanceFields(type));
            Collections.reverse(fields);
            for (FieldSymbol field : fields) {
                IrValueReference value = new IrValueReference(nextValue++, field.type(),
                        field.declaration().nameSpan());
                instructions.add(new IrFieldLoadInstruction(value, receiver,
                        field.irField(), field.declaration().span()));
                if (OwnedArrayElementAnalyzer.fields(types.get(field.ownerClass())).contains(field)) {
                    instructions.add(new IrDestroyArrayElementsInstruction(value, field.declaration().span()));
                }
                instructions.add(new IrFreeInstruction(value,
                        field.declaration().span()));
            }
            if (ThrowableSemantics.isThrowable(type)) {
                instructions.add(new IrThrowableTraceInstruction(Optional.empty(),
                        IrThrowableTraceInstruction.Operation.RELEASE, List.of(receiver), type.declaration().span()));
            }
            instructions.add(new IrRawDeallocateInstruction(receiver,
                    type.declaration().span()));
            IrBasicBlock entry = new IrBasicBlock("entry", instructions,
                    new IrReturnTerminator(Optional.empty(), type.declaration().span()),
                    type.declaration().span());
            result.add(new IrFunction(type.name(), "<constructor-rollback>", linkage,
                    IrType.VOID, List.of(new IrParameter("this", receiver,
                    type.declaration().nameSpan())), List.of(entry), type.declaration().span(),
                    sourceFileName(type.source()), IrCallableKind.CONSTRUCTOR_ROLLBACK));
        }
        return List.copyOf(result);
    }

    private CallableSymbol findAndValidateMain(Map<String, TypeSymbol> types,
                                               List<Diagnostic> diagnostics,
                                               boolean required,
                                               Optional<String> requestedMainClass) {
        List<CallableSymbol> namedCandidates = types.values().stream()
                .filter(type -> !type.isInterface())
                .flatMap(type -> type.declaredMethodsNamed("main").stream()).toList();
        List<CallableSymbol> candidates = namedCandidates.stream()
                .filter(this::hasMainArgumentsParameter).toList();
        for (CallableSymbol candidate : candidates) {
            validateMainSignature(candidate, types, diagnostics);
        }
        if (candidates.isEmpty() && !namedCandidates.isEmpty()) {
            namedCandidates.forEach(candidate -> validateMainSignature(candidate, types, diagnostics));
        }
        if (requestedMainClass.isPresent()) {
            String requestedName = requestedMainClass.orElseThrow();
            TypeSymbol requested = types.get(requestedName);
            if (requested == null) {
                requested = types.values().stream()
                        .filter(type -> type.sourceName().equals(requestedName))
                        .findFirst().orElse(null);
            }
            if (requested == null || requested.isInterface()) {
                diagnostics.add(Diagnostic.global("main class '" + requestedName
                        + "' is not a declared class"));
                return null;
            }
            CallableSymbol selected = requested.declaredMethodsNamed("main").stream()
                    .filter(this::hasMainArgumentsParameter).findFirst().orElse(null);
            if (selected == null) {
                source = requested.source();
                diagnostics.add(error(requested.declaration().nameSpan(), "main class '"
                        + requestedName + "' does not declare main(String[] args)"));
            }
            return selected;
        }
        if (candidates.isEmpty()) {
            if (!required) {
                return null;
            }
            types.values().stream().findFirst().ifPresent(first -> {
                source = first.source();
                diagnostics.add(error(first.declaration().nameSpan(),
                        "program does not declare main(String[] args)"));
            });
            return null;
        }
        if (candidates.size() > 1) {
            if (required) {
                for (int index = 1; index < candidates.size(); index++) {
                    CallableSymbol candidate = candidates.get(index);
                    source = types.get(candidate.ownerType()).source();
                    diagnostics.add(error(candidate.nameSpan(),
                            "program declares more than one main(String[] args) method"));
                }
            }
            return null;
        }
        return candidates.getFirst();
    }

    private void validateMainSignature(CallableSymbol main, Map<String, TypeSymbol> types,
                                       List<Diagnostic> diagnostics) {
        source = types.get(main.ownerType()).source();
        if (main.accessModifier() != AccessModifier.PUBLIC) {
            diagnostics.add(error(main.nameSpan(), "main(String[] args) must be public"));
        }
        if (!main.isStatic()) {
            diagnostics.add(error(main.nameSpan(), "main(String[] args) must be static"));
        }
        if (!main.returnType().equals(IrType.I32)
                && !main.returnType().equals(IrType.VOID)) {
            diagnostics.add(error(main.nameSpan(), "main(String[] args) must return int or void"));
        }
        if (!main.typeVariables().isEmpty()) {
            diagnostics.add(error(main.nameSpan(), "main(String[] args) cannot declare type parameters"));
        }
        if (!hasMainArgumentsParameter(main)) {
            diagnostics.add(error(main.nameSpan(),
                    "main must declare exactly one String[] parameter"));
        }
    }

    private boolean hasMainArgumentsParameter(CallableSymbol method) {
        return method.parameterTypes().equals(List.of(MAIN_ARGUMENTS_TYPE));
    }

    private List<IrType> resolveParameterTypes(TypeSymbol owner, List<Parameter> parameters,
                                               TypeResolver resolver,
                                               List<Diagnostic> diagnostics,
                                               boolean staticContext) {
        return resolveParameterTypes(owner, parameters, resolver, diagnostics, staticContext,
                List.of());
    }

    private List<IrType> resolveParameterTypes(TypeSymbol owner, List<Parameter> parameters,
                                               TypeResolver resolver,
                                               List<Diagnostic> diagnostics,
                                               boolean staticContext,
                                               List<TypeVariableSymbol> callableVariables) {
        List<IrType> result = new ArrayList<>();
        for (Parameter parameter : parameters) {
            IrType type = resolveType(owner, parameter.type(), resolver, diagnostics,
                    staticContext, callableVariables);
            if (type.equals(IrType.VOID)) {
                diagnostics.add(error(parameter.span(), "parameter '" + parameter.name()
                        + "' cannot have type void"));
                type = IrType.I32;
            }
            result.add(type);
        }
        return List.copyOf(result);
    }

    private List<IrType> resolveThrownTypes(TypeSymbol owner, List<TypeName> declarations,
                                            TypeResolver resolver,
                                            ClassHierarchy hierarchy,
                                            List<Diagnostic> diagnostics,
                                            boolean staticContext,
                                            List<TypeVariableSymbol> callableVariables) {
        List<IrType> result = new ArrayList<>();
        IrType throwable = IrType.reference("ironwood.lang.Throwable");
        for (TypeName declaration : declarations) {
            IrType type = resolveType(owner, declaration, resolver, diagnostics,
                    staticContext, callableVariables);
            if (!type.isReference() || type.isArray() || type.isWildcard()
                    || type.equals(IrType.NULL)) {
                diagnostics.add(error(declaration.span(), "throws type '"
                        + declaration.displayName()
                        + "' must be a class type or a Throwable-bounded type parameter"));
                continue;
            }
            if (type.isNominalReference() && !type.typeArguments().isEmpty()) {
                diagnostics.add(error(declaration.span(), "throws type '"
                        + declaration.displayName()
                        + "' must be a non-parameterized exception class"));
                continue;
            }
            if (!hierarchy.isSubtype(type, throwable)) {
                diagnostics.add(error(declaration.span(), "throws type '"
                        + declaration.displayName()
                        + "' must extend ironwood.lang.Throwable"));
                continue;
            }
            result.add(type);
        }
        return List.copyOf(result);
    }

    private IrType resolveType(TypeSymbol owner, TypeName type, TypeResolver resolver,
                               List<Diagnostic> diagnostics, boolean staticContext) {
        return resolveType(owner, type, resolver, diagnostics, staticContext, List.of());
    }

    private IrType resolveVariableType(TypeSymbol owner,
                                       LocalClassSemantics.VariableIdentity variable,
                                       TypeResolver resolver, ClassHierarchy hierarchy,
                                       List<Diagnostic> diagnostics) {
        List<IrType> types = variable.types().stream()
                .map(type -> resolveType(owner, type, resolver, diagnostics, false))
                .toList();
        IrType result = types.getFirst();
        for (int index = 1; index < types.size(); index++) {
            result = hierarchy.leastUpperBound(result, types.get(index))
                    .orElse(IrType.reference("ironwood.lang.Throwable"));
        }
        return result;
    }

    private IrType resolveType(TypeSymbol owner, TypeName type, TypeResolver resolver,
                               List<Diagnostic> diagnostics, boolean staticContext,
                               List<TypeVariableSymbol> callableVariables) {
        if (type.kind() == TypeName.Kind.WILDCARD) {
            return switch (type.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(resolveType(owner,
                        type.wildcardBound(), resolver, diagnostics, staticContext,
                        callableVariables));
                case SUPER -> IrType.wildcardSuper(resolveType(owner,
                        type.wildcardBound(), resolver, diagnostics, staticContext,
                        callableVariables));
            };
        }
        if (type.kind() == TypeName.Kind.ARRAY) {
            return IrType.array(resolveType(owner, type.elementType(), resolver, diagnostics,
                    staticContext, callableVariables));
        }
        if (type.kind() != TypeName.Kind.REFERENCE) {
            return irType(type);
        }
        Optional<IrType> callableTypeParameter = callableVariables.stream()
                .filter(variable -> variable.displayName().equals(type.referenceName()))
                .findFirst().map(TypeVariableSymbol::irType);
        if (callableTypeParameter.isPresent()) {
            if (!type.typeArguments().isEmpty()) {
                diagnostics.add(error(type.span(), "type parameter '" + type.referenceName()
                        + "' cannot have type arguments"));
            }
            return callableTypeParameter.orElseThrow();
        }
        Optional<IrType> typeParameter = owner.typeParameter(type.referenceName());
        if (typeParameter.isPresent()) {
            if (!type.typeArguments().isEmpty()) {
                diagnostics.add(error(type.span(), "type parameter '" + type.referenceName()
                        + "' cannot have type arguments"));
            }
            if (staticContext) {
                diagnostics.add(error(type.span(), "class type parameter '" + type.referenceName()
                        + "' is not available in a static context"));
            }
            return typeParameter.orElseThrow();
        }
        TypeResolver.Resolution resolution = resolver.resolve(type.referenceName(), owner,
                type.span());
        if (resolution.ambiguous()) {
            diagnostics.add(error(type.span(), "ambiguous type '" + type.referenceName()
                    + "'; candidates are " + resolution.candidates().stream().map(TypeSymbol::name)
                    .reduce((left, right) -> left + ", " + right).orElse("")));
            return IrType.reference(type.referenceName(), type.typeArguments().stream()
                    .map(argument -> resolveType(owner, argument, resolver, diagnostics,
                            staticContext, callableVariables)).toList());
        }
        TypeSymbol resolved = resolution.type().orElse(null);
        if (resolved == null) {
            diagnostics.add(error(type.span(), "unknown class type '" + type.referenceName() + "'"));
            return IrType.reference(type.referenceName(), type.typeArguments().stream()
                    .map(argument -> resolveType(owner, argument, resolver, diagnostics,
                            staticContext, callableVariables)).toList());
        }
        if (resolution.inaccessible()) {
            diagnostics.add(error(type.span(), "type '" + resolved.name()
                    + "' is package-private in package '" + resolved.packageName() + "'"));
        }
        int segmentArity = type.lastSegmentTypeArgumentCount();
        List<TypeName> finalTypeArguments = type.typeArguments().subList(
                type.typeArguments().size() - segmentArity, type.typeArguments().size());
        List<IrType> explicitArguments = finalTypeArguments.stream()
                .map(argument -> resolveType(owner, argument, resolver, diagnostics,
                        staticContext, callableVariables)).toList();
        int declaredArity = resolved.declaredTypeParameters().size();
        boolean segmentArityValid = declaredArity == segmentArity;
        if (declaredArity > 0 && segmentArity == 0) {
            diagnostics.add(error(type.span(), "raw generic type '" + resolved.name()
                    + "' is not supported; provide " + declaredArity
                    + " type argument(s) on its final name segment"));
        } else if (!segmentArityValid) {
            diagnostics.add(error(type.span(), "generic type '" + resolved.name() + "' expects "
                    + declaredArity + " type argument(s) but received " + segmentArity
                    + " on its final name segment"));
        }
        List<IrType> declaredArguments = explicitArguments;
        List<IrType> arguments = declaredArguments;
        if (resolved.enclosingType().isPresent()) {
            if (resolved.isInnerClass()) {
                Optional<IrType> enclosingView;
                if (type.hasExplicitMemberQualifier(resolved.simpleName())) {
                    TypeName qualifier = type.qualifierReference().orElseThrow();
                    IrType qualifierType = resolveType(owner, qualifier, resolver, diagnostics,
                            staticContext, callableVariables);
                    enclosingView = resolver.exactClassSupertype(qualifierType,
                            resolved.enclosingType().orElseThrow());
                    if (enclosingView.isEmpty()) {
                        diagnostics.add(error(type.span(), "type qualifier '" + qualifier.displayName()
                                + "' does not provide an enclosing instance for member type '"
                                + resolved.sourceName() + "'"));
                    }
                } else {
                    enclosingView = resolver.enclosingTypeView(owner, resolved);
                }
                if (enclosingView.isPresent()) {
                    if (staticContext && containsTypeParameter(enclosingView.orElseThrow())) {
                        diagnostics.add(error(type.span(), "non-static member type '"
                                + resolved.sourceName() + "' depends on enclosing type parameters and "
                                + "cannot be used in a static context"));
                    }
                    List<IrType> combined = new ArrayList<>(
                            enclosingView.orElseThrow().typeArguments());
                    combined.addAll(declaredArguments);
                    arguments = List.copyOf(combined);
                }
            } else if (type.hasParameterizedQualifier()) {
                diagnostics.add(error(type.span(), "static member type '" + resolved.sourceName()
                        + "' cannot be selected from a parameterized type"));
            }
        } else if (type.hasParameterizedQualifier()) {
            diagnostics.add(error(type.span(), "type arguments cannot qualify top-level type '"
                    + resolved.sourceName() + "'"));
        }
        if (segmentArityValid && resolved.typeParameters().size() != arguments.size()) {
            diagnostics.add(error(type.span(), "generic type '" + resolved.name() + "' expects "
                    + resolved.typeParameters().size() + " complete type argument(s) but received "
                    + arguments.size()));
        }
        for (int index = 0; index < explicitArguments.size(); index++) {
            if (!explicitArguments.get(index).isReference()
                    && !explicitArguments.get(index).isPrimitive()) {
                diagnostics.add(error(finalTypeArguments.get(index).span(), "generic type argument '"
                        + finalTypeArguments.get(index).displayName()
                        + "' must be a proper reference or primitive type"));
            }
        }
        if (segmentArityValid && resolved.typeParameters().size() == arguments.size()) {
            genericTypes.validateInstantiation(resolved, arguments, owner.source(), type.span(), diagnostics);
        }
        return IrType.reference(resolved.name(), arguments);
    }

    private static boolean containsTypeParameter(IrType type) {
        if (type.isTypeParameter()) {
            return true;
        }
        if (type.isArray()) {
            return containsTypeParameter(type.elementType());
        }
        return type.typeArguments().stream().anyMatch(SemanticAnalyzer::containsTypeParameter);
    }

    static IrType irType(TypeName type) {
        return switch (type.kind()) {
            case BYTE -> IrType.I8;
            case SHORT -> IrType.I16;
            case INT -> IrType.I32;
            case LONG -> IrType.I64;
            case CHAR -> IrType.U16;
            case FLOAT -> IrType.F32;
            case DOUBLE -> IrType.F64;
            case BOOLEAN -> IrType.I1;
            case VOID -> IrType.VOID;
            case WILDCARD -> switch (type.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(irType(type.wildcardBound()));
                case SUPER -> IrType.wildcardSuper(irType(type.wildcardBound()));
            };
            case REFERENCE -> IrType.reference(type.referenceName(), type.typeArguments().stream()
                    .map(SemanticAnalyzer::irType).toList());
            case ARRAY -> IrType.array(irType(type.elementType()));
        };
    }

    private void validateConstructorDelegationCycles(Map<String, TypeSymbol> types,
                                                     Map<String, String> delegations,
                                                     List<Diagnostic> diagnostics) {
        Map<String, CallableSymbol> constructors = new LinkedHashMap<>();
        types.values().forEach(type -> type.constructors().forEach(
                constructor -> constructors.put(constructor.linkageName(), constructor)));
        Set<String> reported = new LinkedHashSet<>();
        for (String start : delegations.keySet()) {
            Map<String, Integer> path = new LinkedHashMap<>();
            String current = start;
            while (current != null && !path.containsKey(current)) {
                path.put(current, path.size());
                current = delegations.get(current);
            }
            if (current == null || !path.containsKey(current)) {
                continue;
            }
            int cycleStart = path.get(current);
            for (Map.Entry<String, Integer> entry : path.entrySet()) {
                if (entry.getValue() < cycleStart || !reported.add(entry.getKey())) {
                    continue;
                }
                CallableSymbol constructor = constructors.get(entry.getKey());
                if (constructor == null) {
                    continue;
                }
                source = types.get(constructor.ownerType()).source();
                diagnostics.add(error(constructor.thisInvocation()
                                .map(ironwood.compiler.ast.ThisConstructorInvocation::span)
                                .orElse(constructor.nameSpan()),
                        "recursive constructor delegation involving '"
                                + constructor.signatureKey() + "'"));
            }
        }
    }

    private static String signatureKey(String name, List<IrType> parameterTypes) {
        return name + "(" + parameterTypes.stream().map(IrType::displayName)
                .reduce((left, right) -> left + "," + right).orElse("") + ")";
    }

    private static String callableDeclarationId(TypeSymbol owner, String name, SourceSpan span) {
        return owner.name() + "#callable:" + name + "@" + span.start().offset();
    }

    private List<TypeVariableSymbol> callableTypeVariables(TypeSymbol owner,
                                                           String declarationId,
                                                           List<TypeParameter> declarations,
                                                           boolean staticContext,
                                                           List<Diagnostic> diagnostics) {
        return callableTypeVariablesById.computeIfAbsent(declarationId,
                ignored -> genericTypes.initializeCallableTypeParameters(owner, declarationId,
                        declarations, staticContext, diagnostics));
    }

    private static String linkageName(String base, List<IrType> parameterTypes, boolean overloaded) {
        if (!overloaded) {
            return base;
        }
        return base + "$" + (parameterTypes.isEmpty() ? "void" : parameterTypes.stream()
                .map(SemanticAnalyzer::linkageTypeName)
                .reduce((left, right) -> left + "$" + right).orElseThrow());
    }

    private static String linkageTypeName(IrType type) {
        IrType erased = type.erasure();
        return switch (erased.kind()) {
            case I1 -> "boolean";
            case I8 -> "byte";
            case I16 -> "short";
            case U16 -> "char";
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> "double";
            case REFERENCE -> "ref_" + erased.referenceName().replace("_", "__").replace(".", "_d");
            case ARRAY -> "array_" + linkageTypeName(erased.elementType());
            case VOID, NULL, EXCEPTION, TYPE_PARAMETER, WILDCARD -> throw new IllegalArgumentException(
                    "invalid callable parameter type " + erased.displayName());
        };
    }

    private static boolean containsWildcard(TypeName type) {
        if (type.kind() == TypeName.Kind.WILDCARD) {
            return true;
        }
        if (type.kind() == TypeName.Kind.ARRAY) {
            return containsWildcard(type.elementType());
        }
        return type.typeArguments().stream().anyMatch(SemanticAnalyzer::containsWildcard);
    }

    private static int accessRank(AccessModifier access) {
        return switch (access) {
            case PRIVATE -> 0;
            case PACKAGE_PRIVATE -> 1;
            case PROTECTED -> 2;
            case PUBLIC -> 3;
        };
    }

    private static boolean returnTypeCompatible(IrType expected, IrType actual,
                                                ClassHierarchy hierarchy) {
        if (expected.isNumeric() || actual.isNumeric()
                || expected.equals(IrType.I1) || actual.equals(IrType.I1)) {
            return expected.equals(actual);
        }
        return hierarchy.isAssignable(expected, actual);
    }

    private static void validatePoolBuilders(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                                            EscapeSummaryAnalyzer summaries, List<Diagnostic> diagnostics) {
        java.util.Set<String> checked = new java.util.HashSet<>();
        for (TypeSymbol type : types.values()) {
            if (type.isInterface() || !hierarchy.isSubtype(type.name(), "ironwood.pool.ObjectBuilder")) {
                continue;
            }
            for (CallableSymbol method : hierarchy.lookupMethods(type.selfType(), "newInstance")) {
                if (method.isAbstract() || method.isStatic() || !method.parameterTypes().isEmpty()
                        || !checked.add(method.linkageName())) { continue; }
                var contract = summaries.summary(method);
                if (contract.thisEscapesWithoutReturn()
                        || contract.mayReturnNonOrigin() || contract.freshEscapes()
                        || !contract.returnedOrigins().isEmpty()
                        || !contract.borrowedReturnedOrigins().isEmpty()) {
                    TypeSymbol owner = types.get(method.ownerType());
                    diagnostics.add(Diagnostic.error(owner.source(), method.nameSpan(),
                            "ObjectBuilder.newInstance must return a fresh unescaped object or null"));
                }
            }
        }
    }

    private Diagnostic error(SourceSpan span, String message) {
        return Diagnostic.error(source, span, message);
    }

    private enum VisitState {
        ACTIVE,
        DONE
    }

    private record ResolvedParent(TypeSymbol symbol, IrType type) {
    }

    private record ResolvedParents(List<TypeSymbol> symbols, List<IrType> types) {
        private ResolvedParents {
            symbols = List.copyOf(symbols);
            types = List.copyOf(types);
        }
    }

    private record CallableTypeScope(TypeSymbol owner, String name,
                                     List<TypeParameter> typeParameters,
                                     boolean staticContext, SourceSpan span) {
        private CallableTypeScope {
            typeParameters = List.copyOf(typeParameters);
        }
    }
}
