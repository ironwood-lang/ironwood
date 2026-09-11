// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CharacterLiteralExpression;
import ironwood.compiler.ast.CatchClause;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.EnumConstantInitialization;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FloatingLiteralExpression;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.PatternFlow;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchGroup;
import ironwood.compiler.ast.SwitchLabel;
import ironwood.compiler.ast.SwitchRule;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrThrowableTraceInstruction;
import ironwood.compiler.ir.IrStreamInstruction;
import ironwood.compiler.ir.IrAllocateInstruction;
import ironwood.compiler.ir.IrAddSecondaryExceptionInstruction;
import ironwood.compiler.ir.IrAllocationCountInstruction;
import ironwood.compiler.ir.IrLiveAllocationCountInstruction;
import ironwood.compiler.ir.IrArrayAllocateInstruction;
import ironwood.compiler.ir.IrArrayBoundsCheckInstruction;
import ironwood.compiler.ir.IrArrayLengthCheckInstruction;
import ironwood.compiler.ir.IrArrayLengthInstruction;
import ironwood.compiler.ir.IrArrayLoadInstruction;
import ironwood.compiler.ir.IrArrayStoreInstruction;
import ironwood.compiler.ir.IrArrayTypeTestInstruction;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrBinaryInstruction;
import ironwood.compiler.ir.IrBinaryOperator;
import ironwood.compiler.ir.IrBranch;
import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrEnumConstant;
import ironwood.compiler.ir.IrPrintStreamCheckErrorInstruction;
import ironwood.compiler.ir.IrPrintStreamFlushInstruction;
import ironwood.compiler.ir.IrPrintStreamWriteInstruction;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.ir.IrFloatingParseInstruction;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.ir.IrDestroyArrayElementsInstruction;
import ironwood.compiler.ir.IrIdentityHashCodeInstruction;
import ironwood.compiler.ir.IrExceptionLandingPadInstruction;
import ironwood.compiler.ir.IrExceptionCaughtInstruction;
import ironwood.compiler.ir.IrEnsureTypeInitializedInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstanceOfInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInterfaceCallInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrJump;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrNullCheckInstruction;
import ironwood.compiler.ir.IrNumericConversionInstruction;
import ironwood.compiler.ir.IrObjectHashCodeInstruction;
import ironwood.compiler.ir.IrObjectToStringInstruction;
import ironwood.compiler.ir.IrThrowableDescriptionInstruction;
import ironwood.compiler.ir.IrReleaseOwnedThrowableMessageInstruction;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrParameter;
import ironwood.compiler.ir.IrPhiIncoming;
import ironwood.compiler.ir.IrPhiInstruction;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrRawDeallocateInstruction;
import ironwood.compiler.ir.IrReleaseOwnedToStringResultInstruction;
import ironwood.compiler.ir.IrRollbackInstruction;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrSecondaryExceptionAtInstruction;
import ironwood.compiler.ir.IrSecondaryExceptionCountInstruction;
import ironwood.compiler.ir.IrStaticFieldLoadInstruction;
import ironwood.compiler.ir.IrStaticFieldStoreInstruction;
import ironwood.compiler.ir.IrStringCharAtInstruction;
import ironwood.compiler.ir.IrStringConcatInstruction;
import ironwood.compiler.ir.IrStringConcatPart;
import ironwood.compiler.ir.IrStringConcatPartKind;
import ironwood.compiler.ir.IrStringCopyInstruction;
import ironwood.compiler.ir.IrStringEqualsInstruction;
import ironwood.compiler.ir.IrStringFromCharRangeInstruction;
import ironwood.compiler.ir.IrStringFromUtf8Instruction;
import ironwood.compiler.ir.IrStringJoinInstruction;
import ironwood.compiler.ir.IrStringEqualsIgnoreCaseInstruction;
import ironwood.compiler.ir.IrStringReplaceTextInstruction;
import ironwood.compiler.ir.IrStringReplaceCharInstruction;
import ironwood.compiler.ir.IrStringRepeatInstruction;
import ironwood.compiler.ir.IrStringCaseInstruction;
import ironwood.compiler.ir.IrStringFromIntegerInstruction;
import ironwood.compiler.ir.IrStringFromCharacterInstruction;
import ironwood.compiler.ir.IrStringFromCharsInstruction;
import ironwood.compiler.ir.IrStringFromRangeInstruction;
import ironwood.compiler.ir.IrStringHashCodeInstruction;
import ironwood.compiler.ir.IrSwitchCase;
import ironwood.compiler.ir.IrSwitchTerminator;
import ironwood.compiler.ir.IrSystemArrayCopyInstruction;
import ironwood.compiler.ir.IrSystemClockInstruction;
import ironwood.compiler.ir.IrSystemExitInstruction;
import ironwood.compiler.ir.IrSystemGetenvInstruction;
import ironwood.compiler.ir.IrSystemPropertyInstruction;
import ironwood.compiler.ir.IrCharacterInstruction;
import ironwood.compiler.ir.IrFloatingBitsInstruction;
import ironwood.compiler.ir.IrMathBinaryInstruction;
import ironwood.compiler.ir.IrMathUnaryInstruction;
import ironwood.compiler.ir.IrTerminator;
import ironwood.compiler.ir.IrThrowTerminator;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrUnaryInstruction;
import ironwood.compiler.ir.IrUnaryOperator;
import ironwood.compiler.ir.IrUnreachable;
import ironwood.compiler.ir.IrValueReference;
import ironwood.compiler.ir.IrVirtualCallInstruction;
import ironwood.compiler.ir.IrCallKind;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

final class FunctionAnalyzer {
    private static final IrType STRING_TYPE = IrType.reference("ironwood.lang.String");

    private final SourceFile source;
    private final CallableSymbol function;
    private final ClassHierarchy hierarchy;
    private final EscapeSummaryAnalyzer escapeSummaries;
    private final OwnedArrayFieldAnalyzer ownedArrayFields;
    private final StringPool stringPool;
    private final TypeSymbol currentClass;
    private final StaticImportResolver staticImports;
    private final FunctionPlanningContext planningContext;
    private final InvocationPlanner invocationPlanner;
    private final List<Diagnostic> diagnostics;
    private final Map<String, String> constructorDelegations;
    private final List<IrParameter> parameters = new ArrayList<>();
    private final Map<String, MutableBlock> blocks = new LinkedHashMap<>();
    private final Deque<Map<String, LocalSymbol>> scopes = new ArrayDeque<>();
    private final Deque<ExceptionRegion> exceptionRegions = new ArrayDeque<>();
    private final Deque<List<IrType>> checkedCatchScopes = new ArrayDeque<>();
    private final Deque<Set<IrType>> observedTryBodyExceptions = new ArrayDeque<>();
    private final Deque<FinallyContext> finallyContexts = new ArrayDeque<>();
    private final Deque<LoopContext> loopContexts = new ArrayDeque<>();
    private final Deque<BreakContext> breakContexts = new ArrayDeque<>();
    private final Deque<LabeledContext> labeledContexts = new ArrayDeque<>();
    private final Deque<SwitchExpressionContext> switchExpressionContexts = new ArrayDeque<>();
    private LinkedHashMap<LocalSymbol, IrOperand> environment = new LinkedHashMap<>();
    private final Map<LocalSymbol, CaseConstant> localConstants = new LinkedHashMap<>();
    private MutableBlock currentBlock;
    private IrOperand thisOperand;
    private IrOperand enclosingInstanceOperand;
    private IrOperand forwardedSuperEnclosingOperand;
    private final Map<String, IrOperand> captureParameterOperands = new LinkedHashMap<>();
    private int nextValueId;
    private int nextSymbolId;
    private int nextBlockId;
    private int nextCaptureId;
    private int nextSyntheticLocalId;
    private boolean evaluatingConstructorArguments;
    private boolean loweringInstanceInitializer;
    private final List<AllocationInfo> pendingYieldAllocations = new ArrayList<>();
    private final List<Reclamation> reclamations = new ArrayList<>();
    private final Map<IrOperand, AllocationInfo> allocationsByOperand = new LinkedHashMap<>();
    private final Set<IrOperand> ownedHelperBorrows = new LinkedHashSet<>();
    private final Map<IrOperand, String> ownedHelperBorrowTypes = new LinkedHashMap<>();
    private final List<AllocationInfo> allocations = new ArrayList<>();
    private final Map<ArraySlot, AllocationInfo> knownArraySlots = new LinkedHashMap<>();
    private final Map<AllocationInfo, Set<AllocationInfo>> constructorBorrows = new IdentityHashMap<>();
    private final Set<AllocationInfo> exposedContainerContents = new LinkedHashSet<>();
    private AllocationInfo pendingContainerClear;
    private WrapperBorrow pendingWrapperBorrow;
    private SourceSpan discardedCallSpan;
    private UnfreedAllocationTracker<AllocationInfo> unfreed;
    private ClosedWorldEffectAnalyzer reclamationEffects;
    private final Set<IrOperand> unfreedFreshResults = new LinkedHashSet<>();
    private int expressionDepth;
    private final Map<String, AllocationInfo> borrowedOwnedFields = new LinkedHashMap<>();
    private final Map<AllocationInfo, AllocationInfo> poolOwners = new IdentityHashMap<>();
    private final Map<IrOperand, AllocationInfo> poolValueOwners = new LinkedHashMap<>();
    private PoolTransfer pendingPoolTransfer;
    private final Map<LocalSymbol, List<IrType>> preciseRethrowTypes = new LinkedHashMap<>();
    private final Set<LocalSymbol> constructorParameterSymbols = new java.util.LinkedHashSet<>();
    private final Set<String> declaredInstanceInitializerFields = new java.util.LinkedHashSet<>();
    private final IdentityHashMap<InstanceOfExpression, PatternLocal> patternLocals =
            new IdentityHashMap<>();
    private final Set<String> reportedPatternConflicts = new LinkedHashSet<>();
    private int controlFlowDepth;

    FunctionAnalyzer(SourceFile source, CallableSymbol function, ClassHierarchy hierarchy,
                     EscapeSummaryAnalyzer escapeSummaries, OwnedArrayFieldAnalyzer ownedArrayFields,
                     StringPool stringPool,
                     List<Diagnostic> diagnostics, Map<String, String> constructorDelegations) {
        this.source = source;
        this.function = function;
        this.hierarchy = hierarchy;
        this.escapeSummaries = escapeSummaries;
        this.ownedArrayFields = ownedArrayFields;
        this.stringPool = stringPool;
        this.currentClass = hierarchy.type(function.ownerType()).orElseThrow();
        this.staticImports = new StaticImportResolver(hierarchy.typeResolver(), hierarchy);
        this.planningContext = new FunctionPlanningContext();
        this.invocationPlanner = new InvocationPlanner(planningContext);
        this.diagnostics = diagnostics;
        this.constructorDelegations = constructorDelegations;
    }

    FunctionAnalyzer withUnfreedChecks(ironwood.compiler.UnfreedMode mode,
                                       ClosedWorldEffectAnalyzer reclamationEffects) {
        this.reclamationEffects = reclamationEffects;
        if (mode != ironwood.compiler.UnfreedMode.OFF) {
            unfreed = new UnfreedAllocationTracker<>(source, mode);
        }
        return this;
    }

    AnonymousParentBinder.PrimaryPlanningContext anonymousParentPlanningContext() {
        return planningContext;
    }

    IrFunction analyze() {
        int diagnosticStart = diagnostics.size();
        Block body = function.body().orElseThrow();
        currentBlock = createNamedBlock("entry", body.span());
        enterScope();
        if (!function.isStatic()) {
            IrValueReference value = newValue(currentClass.selfType(), function.nameSpan());
            parameters.add(new IrParameter("this", value, function.nameSpan()));
            thisOperand = value;
        }
        Optional<TypeSymbol.AnonymousConstructorForwarding> anonymousForwarding =
                currentClass.anonymousConstructorForwarding(function);
        Optional<IrType> lexicalEnclosingType = function.isConstructor()
                ? currentClass.enclosingInstanceField().map(IrField::type) : Optional.empty();
        if (function.isConstructor() && lexicalEnclosingType.isEmpty()
                && anonymousForwarding.isEmpty()) {
            lexicalEnclosingType = function.enclosingInstanceType();
        }
        if (function.isConstructor() && lexicalEnclosingType.isPresent()) {
            IrType enclosingType = lexicalEnclosingType.orElseThrow();
            IrValueReference value = newValue(enclosingType, function.nameSpan());
            parameters.add(new IrParameter("<enclosing>", value, function.nameSpan()));
            enclosingInstanceOperand = value;
        }
        if (function.isConstructor()) {
            for (TypeSymbol.CaptureSlot capture : currentClass.captureSlots()) {
                IrValueReference value = newValue(capture.type(), capture.variable().nameSpan());
                parameters.add(new IrParameter(captureParameterName(capture), value,
                        capture.variable().nameSpan()));
                captureParameterOperands.put(capture.variable().id(), value);
            }
        }
        if (anonymousForwarding.isPresent()
                && anonymousForwarding.orElseThrow().superConstructor()
                .enclosingInstanceType().isPresent()) {
            IrType type = anonymousForwarding.orElseThrow().superConstructor()
                    .enclosingInstanceType().orElseThrow();
            IrValueReference value = newValue(type, function.nameSpan());
            parameters.add(new IrParameter("<super-enclosing>", value, function.nameSpan()));
            forwardedSuperEnclosingOperand = value;
        }
        for (int index = 0; index < function.parameters().size(); index++) {
            Parameter parameter = function.parameters().get(index);
            IrType type = function.parameterTypes().get(index);
            IrValueReference value = newValue(type, parameter.nameSpan());
            parameters.add(new IrParameter(parameter.name(), value, parameter.span()));
            LocalSymbol symbol = declare(parameter.name(), type, parameter.nameSpan(),
                    "parameter", parameter.isFinal());
            if (symbol != null) {
                environment.put(symbol, value);
                if (function.isConstructor()) {
                    constructorParameterSymbols.add(symbol);
                }
            }
        }

        IrThrowableTraceInstruction.Operation traceOperation = throwableTraceOperation();
        if (traceOperation != null) {
            Optional<IrValueReference> result = function.returnType().equals(IrType.VOID)
                    ? Optional.empty() : Optional.of(newValue(function.returnType(), function.span()));
            IrThrowableTraceInstruction instruction = new IrThrowableTraceInstruction(result, traceOperation,
                    parameters.stream().map(parameter -> (IrOperand) parameter.value()).toList(), function.span());
            if (traceOperation == IrThrowableTraceInstruction.Operation.ARRAY) {
                emitCall(instruction, function.span());
            } else {
                currentBlock.addInstruction(instruction);
            }
            currentBlock.terminate(new IrReturnTerminator(result.map(value -> (IrOperand) value), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isPrintStreamWriteIntrinsic()) {
            Optional<IrStringConcatPart> value = function.parameterTypes().isEmpty()
                    ? Optional.empty() : Optional.of(new IrStringConcatPart(
                    printValueKind(function.parameterTypes().getFirst()), parameters.get(1).value()));
            currentBlock.addInstruction(new IrPrintStreamWriteInstruction(
                    parameters.getFirst().value(), value,
                    function.sourceName().equals("nativePrintln"), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isPrintStreamFlushIntrinsic()) {
            currentBlock.addInstruction(new IrPrintStreamFlushInstruction(
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isPrintStreamCheckErrorIntrinsic()) {
            IrValueReference result = newValue(IrType.I1, function.span());
            currentBlock.addInstruction(new IrPrintStreamCheckErrorInstruction(result,
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isReleaseOwnedThrowableMessageIntrinsic()) {
            currentBlock.addInstruction(new IrReleaseOwnedThrowableMessageInstruction(
                    parameters.get(0).value(), parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isThrowableDescription(function)) {
            IrValueReference result = newValue(STRING_TYPE, function.span());
            emitCall(new IrThrowableDescriptionInstruction(result,
                    parameters.get(0).value(), parameters.get(1).value(), function.span()),
                    function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isReleaseOwnedToStringResultIntrinsic()) {
            currentBlock.addInstruction(new IrReleaseOwnedToStringResultInstruction(
                    parameters.get(0).value(), parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isReleaseOwnedFileAllocationIntrinsic()) {
            currentBlock.addInstruction(new IrFreeInstruction(
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemIdentityHashCodeIntrinsic()) {
            IrValueReference result = newValue(IrType.I32, function.span());
            currentBlock.addInstruction(new IrIdentityHashCodeInstruction(result,
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemAllocationCountIntrinsic()) {
            IrValueReference result = newValue(IrType.I64, function.span());
            currentBlock.addInstruction(new IrAllocationCountInstruction(result, function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemLiveAllocationCountIntrinsic()) {
            IrValueReference result = newValue(IrType.I64, function.span());
            currentBlock.addInstruction(new IrLiveAllocationCountInstruction(result,
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemArrayCopyIntrinsic()) {
            currentBlock.addInstruction(new IrSystemArrayCopyInstruction(
                    parameters.get(0).value(), parameters.get(1).value(),
                    parameters.get(2).value(), parameters.get(3).value(),
                    parameters.get(4).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemGetenvIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"),
                    function.span());
            emitCall(new IrSystemGetenvInstruction(result, parameters.getFirst().value(),
                    function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemPropertyIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"),
                    function.span());
            emitCall(new IrSystemPropertyInstruction(result, parameters.getFirst().value(),
                    function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isSystemExitIntrinsic()) {
            currentBlock.addInstruction(new IrSystemExitInstruction(
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.empty(), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (systemClockIntrinsic().isPresent()) {
            IrValueReference result = newValue(IrType.I64, function.span());
            currentBlock.addInstruction(new IrSystemClockInstruction(result,
                    systemClockIntrinsic().orElseThrow(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrType> floatingParseType = floatingParseIntrinsic();
        if (floatingParseType.isPresent()) {
            IrValueReference result = newValue(floatingParseType.orElseThrow(), function.span());
            currentBlock.addInstruction(new IrFloatingParseInstruction(result,
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrStreamInstruction.Operation> streamOperation = streamIntrinsicOperation();
        if (streamOperation.isPresent()) {
            IrValueReference result = newValue(function.returnType(), function.span());
            IrStreamInstruction instruction = new IrStreamInstruction(result,
                    streamOperation.orElseThrow(), parameters.stream()
                            .map(parameter -> (IrOperand) parameter.value()).toList(), function.span());
            if (streamOperation.orElseThrow() == IrStreamInstruction.Operation.OPEN) {
                emitCall(instruction, function.span());
            } else {
                currentBlock.addInstruction(instruction);
            }
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrFileInstruction.Operation> fileOperation = fileIntrinsicOperation();
        if (fileOperation.isPresent()) {
            IrValueReference result = newValue(function.returnType(), function.span());
            Optional<IrOperand> path = parameters.isEmpty() ? Optional.empty()
                    : Optional.of(parameters.getFirst().value());
            Optional<IrOperand> value = parameters.size() < 2 ? Optional.empty()
                    : Optional.of(parameters.get(1).value());
            IrFileInstruction instruction = new IrFileInstruction(result,
                    fileOperation.orElseThrow(), path, value, function.span());
            if (fileOperation.orElseThrow() == IrFileInstruction.Operation.LAST_ERROR) {
                currentBlock.addInstruction(instruction);
            } else {
                emitCall(instruction, function.span());
            }
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrCharacterInstruction.Operation> characterOperation = characterIntrinsic();
        if (characterOperation.isPresent()) {
            IrValueReference result = newValue(IrType.I32, function.span());
            currentBlock.addInstruction(new IrCharacterInstruction(result,
                    characterOperation.orElseThrow(), parameters.getFirst().value(),
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrFloatingBitsInstruction.Operation> floatingBitsOperation =
                floatingBitsIntrinsic();
        if (floatingBitsOperation.isPresent()) {
            IrValueReference result = newValue(function.returnType(), function.span());
            currentBlock.addInstruction(new IrFloatingBitsInstruction(result,
                    floatingBitsOperation.orElseThrow(), parameters.getFirst().value(),
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrMathUnaryInstruction.Operation> mathUnaryOperation = mathUnaryIntrinsic();
        if (mathUnaryOperation.isPresent()) {
            IrValueReference result = newValue(IrType.F64, function.span());
            currentBlock.addInstruction(new IrMathUnaryInstruction(result,
                    mathUnaryOperation.orElseThrow(), parameters.getFirst().value(),
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        Optional<IrMathBinaryInstruction.Operation> mathBinaryOperation = mathBinaryIntrinsic();
        if (mathBinaryOperation.isPresent()) {
            IrValueReference result = newValue(IrType.F64, function.span());
            currentBlock.addInstruction(new IrMathBinaryInstruction(result,
                    mathBinaryOperation.orElseThrow(), parameters.get(0).value(),
                    parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isThrowableSecondaryExceptionCountIntrinsic()) {
            IrValueReference result = newValue(IrType.I32, function.span());
            currentBlock.addInstruction(new IrSecondaryExceptionCountInstruction(result,
                    parameters.getFirst().value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isThrowableSecondaryExceptionAtIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.Throwable"),
                    function.span());
            currentBlock.addInstruction(new IrSecondaryExceptionAtInstruction(result,
                    parameters.getFirst().value(), parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isObjectHashCodeIntrinsic()) {
            IrValueReference result = newValue(IrType.I32, function.span());
            currentBlock.addInstruction(new IrObjectHashCodeInstruction(result, thisOperand,
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isObjectToStringIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"),
                    function.span());
            emitCall(new IrObjectToStringInstruction(result, thisOperand,
                    function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringCharAtIntrinsic()) {
            IrValueReference result = newValue(IrType.U16, function.span());
            currentBlock.addInstruction(new IrStringCharAtInstruction(result, thisOperand,
                    parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringEqualsIntrinsic()) {
            IrValueReference result = newValue(IrType.I1, function.span());
            currentBlock.addInstruction(new IrStringEqualsInstruction(result, thisOperand,
                    parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringHashCodeIntrinsic()) {
            IrValueReference result = newValue(IrType.I32, function.span());
            currentBlock.addInstruction(new IrStringHashCodeInstruction(result, thisOperand,
                    function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringCase(function)) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringCaseInstruction(result, parameters.get(0).value(), parameters.get(1).value(), function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringRepeat(function)) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringRepeatInstruction(result, parameters.get(0).value(), parameters.get(1).value(), function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringReplaceChar(function)) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringReplaceCharInstruction(result, parameters.get(0).value(), parameters.get(1).value(), parameters.get(2).value(), function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringReplaceText(function)) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringReplaceTextInstruction(result, parameters.get(0).value(), parameters.get(1).value(), parameters.get(2).value(), function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringEqualsIgnoreCase(function)) {
            IrValueReference result = newValue(IrType.I1, function.span());
            currentBlock.addInstruction(new IrStringEqualsIgnoreCaseInstruction(result, parameters.get(0).value(), parameters.get(1).value(), function.span()));
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringJoin(function)) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringJoinInstruction(result, parameters.get(0).value(), parameters.get(1).value(), function.span()), function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isByteStreamSnapshot(function)) {
            IrValueReference result = newValue(STRING_TYPE, function.span());
            emitCall(new IrStringFromUtf8Instruction(result,
                    parameters.get(0).value(), parameters.get(1).value(), function.span()),
                    function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringFromCharsIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringFromCharsInstruction(result,
                    parameters.get(0).value(), parameters.get(1).value(), function.span()),
                    function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringFromCharRangeIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringFromCharRangeInstruction(result, parameters.get(0).value(),
                    parameters.get(1).value(), parameters.get(2).value(), function.span()),
                    function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringFromRangeIntrinsic()) {
            IrValueReference result = newValue(IrType.reference("ironwood.lang.String"), function.span());
            emitCall(new IrStringFromRangeInstruction(result, parameters.get(0).value(),
                    parameters.get(1).value(), parameters.get(2).value(), function.span()),
                    function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (AllocationResultSemantics.isStringFromInteger(function)
                || AllocationResultSemantics.isStringFromCharacter(function)) {
            IrValueReference result = newValue(STRING_TYPE, function.span());
            IrInstruction formatting = AllocationResultSemantics.isStringFromInteger(function)
                    ? new IrStringFromIntegerInstruction(result, parameters.get(0).value(),
                            parameters.get(1).value(), function.span())
                    : new IrStringFromCharacterInstruction(result, parameters.get(0).value(),
                            function.span());
            emitCall(formatting, function.span());
            currentBlock.terminate(new IrReturnTerminator(Optional.of(result), function.span()));
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (isStringBoundsFailureIntrinsic()) {
            emitBundledException("ironwood.lang.StringIndexOutOfBoundsException",
                    "String.charAt bounds", function.span());
            exitScope();
            return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                    function.returnType(), parameters,
                    blocks.values().stream().map(MutableBlock::freeze).toList(), function.span());
        }

        if (function.isConstructor()) {
            if (enclosingInstanceOperand != null
                    && function.thisInvocation().isEmpty()) {
                currentBlock.addInstruction(new IrFieldStoreInstruction(thisOperand,
                        currentClass.enclosingInstanceField().orElseThrow(),
                        enclosingInstanceOperand, function.nameSpan()));
            }
            if (function.thisInvocation().isEmpty()) {
                for (TypeSymbol.CaptureSlot capture : currentClass.captureSlots()) {
                    if (!capture.field().ownerClass().equals(currentClass.name())) {
                        continue;
                    }
                    currentBlock.addInstruction(new IrFieldStoreInstruction(thisOperand,
                            capture.field(), captureParameterOperands.get(capture.variable().id()),
                            function.nameSpan()));
                }
            }
            lowerConstructorInvocation();
        }

        boolean reachable = lowerBlock(body, false);
        if (reachable) {
            if (function.isDestructor()) {
                emitSuperclassDestructorChain(body.span());
            }
            if (function.returnType().equals(IrType.VOID)) {
                observeUnfreed(true, true);
                currentBlock.terminate(new IrReturnTerminator(Optional.empty(), body.span()));
            } else {
                diagnostics.add(error(function.nameSpan(),
                        "method '" + function.sourceName() + "' does not return a value on every path"));
                currentBlock.terminate(new IrUnreachable(body.span()));
            }
        }
        exitScope();

        if (unfreed != null && !Diagnostic.hasErrors(diagnostics.subList(diagnosticStart, diagnostics.size()))) {
            diagnostics.addAll(unfreed.diagnostics());
        }
        List<IrBasicBlock> frozenBlocks = blocks.values().stream().map(MutableBlock::freeze).toList();
        return new IrFunction(function.ownerType(), function.sourceName(), function.linkageName(),
                function.returnType(), parameters, frozenBlocks, function.span());
    }

    private void emitSuperclassDestructorChain(SourceSpan span) {
        TypeSymbol superclass = currentClass.superclass().orElse(null);
        if (superclass == null || superclass.destructor().isEmpty()) {
            return;
        }
        CallableSymbol destructor = superclass.destructor().orElseThrow();
        IrType receiverType = hierarchy.superclassType(currentClass.selfType())
                .orElse(superclass.selfType());
        IrOperand receiver = convertReference(thisOperand, receiverType, span);
        currentBlock.addInstruction(new IrCallInstruction(Optional.empty(),
                destructor.linkageName(), IrType.VOID, List.of(receiver), span));
    }

    private void lowerConstructorInvocation() {
        TypeSymbol.AnonymousConstructorForwarding anonymous = currentClass
                .anonymousConstructorForwarding(function).orElse(null);
        if (anonymous != null) {
            lowerAnonymousSuperConstructor(anonymous);
            lowerInstanceInitializations();
            return;
        }
        if (function.thisInvocation().isPresent()) {
            ThisConstructorInvocation invocation = function.thisInvocation().orElseThrow();
            evaluatingConstructorArguments = true;
            try {
                InvocationPlanningResult<InvocationPlan> planned =
                        invocationPlanner.planConstructorDelegation(currentClass.selfType(),
                                hierarchy.constructors(currentClass.selfType()),
                                invocation.arguments(), invocation.typeArguments(), invocation.span());
                if (!planned.isResolved()) {
                    reportPlanningFailure(planned, invocation.span(),
                            "constructor for class '" + currentClass.name() + "'");
                    return;
                }
                planningContext.commitCaptures();
                InvocationPlan.CandidatePlan selected = planned.resolvedValue().selected();
                CallableSymbol constructor = selected.candidate().callable()
                        .substitute(selected.inference().substitutions());
                checkCheckedExceptions(constructor, invocation.span());
                LoweredInvocationArguments arguments = lowerInvocationArguments(selected,
                        "constructor argument");
                List<IrOperand> operands = new ArrayList<>();
                operands.add(thisOperand);
                if (constructor.enclosingInstanceType().isPresent()) {
                    IrType required = constructor.enclosingInstanceType().orElseThrow();
                    if (enclosingInstanceOperand == null) {
                        diagnostics.add(error(invocation.span(), "constructor requires enclosing instance '"
                                + required.displayName() + "'"));
                        operands.add(new IrNull(required, invocation.span()));
                    } else {
                        operands.add(convertReference(enclosingInstanceOperand, required,
                                invocation.span()));
                    }
                }
                operands.addAll(constructorCaptureOperands(currentClass, invocation.span()));
                operands.addAll(arguments.operands());
                emitCall(new IrCallInstruction(Optional.empty(), constructor.linkageName(),
                        IrType.VOID, operands, invocation.span()), invocation.span());
                markConstructorArgumentsEscaped(arguments.values());
                constructorDelegations.put(function.linkageName(), constructor.linkageName());
            } finally {
                evaluatingConstructorArguments = false;
            }
            return;
        }
        if (currentClass.superclass().isEmpty()) {
            if (function.superInvocation().isPresent()) {
                SuperConstructorInvocation invocation = function.superInvocation().orElseThrow();
                invocation.enclosingInstance().ifPresent(this::lowerExpression);
                invocation.arguments().forEach(this::lowerExpression);
                diagnostics.add(error(invocation.span(),
                        "root class '" + currentClass.name() + "' has no superclass constructor"));
            }
            lowerInstanceInitializations();
            return;
        }
        TypeSymbol superclass = currentClass.superclass().orElseThrow();
        IrType superclassType = hierarchy.superclassType(currentClass.selfType())
                .orElse(IrType.reference(superclass.name()));
        List<Expression> expressions = function.superInvocation()
                .map(SuperConstructorInvocation::arguments).orElse(List.of());
        List<TypeName> typeArguments = function.superInvocation()
                .map(SuperConstructorInvocation::typeArguments).orElse(List.of());
        SourceSpan invocationSpan = function.superInvocation()
                .map(SuperConstructorInvocation::span).orElse(function.nameSpan());
        List<CallableSymbol> superclassConstructors = preferEligible(
                hierarchy.constructors(superclassType), candidate -> isAccessible(
                        candidate.accessModifier(), superclass.name(), null, true));
        evaluatingConstructorArguments = true;
        try {
            InvocationPlanningResult<InvocationPlan> planned =
                    invocationPlanner.planConstructorDelegation(superclassType,
                            superclassConstructors, expressions,
                            typeArguments, invocationSpan);
            if (!planned.isResolved()) {
                reportPlanningFailure(planned, invocationSpan,
                        "constructor for class '" + superclass.name() + "'");
                return;
            }
            planningContext.commitCaptures();
            InvocationPlan.CandidatePlan selected = planned.resolvedValue().selected();
            CallableSymbol constructor = selected.candidate().callable()
                    .substitute(selected.inference().substitutions());
            checkCheckedExceptions(constructor, invocationSpan);
            TypedValue explicitSuperEnclosing = function.superInvocation()
                    .flatMap(SuperConstructorInvocation::enclosingInstance)
                    .map(this::lowerExpression).orElse(null);
            LoweredInvocationArguments arguments = lowerInvocationArguments(selected,
                    "constructor argument");
            List<IrOperand> operands = new ArrayList<>();
            operands.add(convertReference(thisOperand, superclassType, function.nameSpan()));
            if (constructor.enclosingInstanceType().isPresent()) {
                IrType required = constructor.enclosingInstanceType().orElseThrow();
                if (explicitSuperEnclosing != null
                        && hierarchy.isAssignable(required, explicitSuperEnclosing.type())) {
                    IrOperand enclosing = requireValue(explicitSuperEnclosing, required,
                            invocationSpan, "qualified superclass enclosing instance");
                    emitNullCheck(enclosing, invocationSpan);
                    operands.add(enclosing);
                } else if (explicitSuperEnclosing != null) {
                    diagnostics.add(error(invocationSpan,
                            "qualified superclass enclosing instance must have type '"
                                    + required.displayName() + "', not '"
                                    + explicitSuperEnclosing.type().displayName() + "'"));
                    operands.add(new IrNull(required, function.nameSpan()));
                } else if (enclosingInstanceOperand != null
                        && hierarchy.isAssignable(required, enclosingInstanceOperand.type())) {
                    operands.add(convertReference(enclosingInstanceOperand, required,
                            function.nameSpan()));
                } else {
                    diagnostics.add(error(function.nameSpan(),
                            "superclass constructor requires enclosing instance '"
                                    + required.displayName() + "'"));
                    operands.add(new IrNull(required, function.nameSpan()));
                }
            } else if (explicitSuperEnclosing != null) {
                diagnostics.add(error(invocationSpan,
                        "qualified super constructor invocation requires a non-static inner superclass"));
            }
            operands.addAll(constructorCaptureOperands(superclass, invocationSpan));
            operands.addAll(arguments.operands());
            emitCall(new IrCallInstruction(Optional.empty(), constructor.linkageName(),
                    IrType.VOID, operands, invocationSpan), invocationSpan);
            markConstructorArgumentsEscaped(arguments.values());
        } finally {
            evaluatingConstructorArguments = false;
        }
        lowerInstanceInitializations();
    }

    private void lowerAnonymousSuperConstructor(
            TypeSymbol.AnonymousConstructorForwarding forwarding) {
        CallableSymbol target = forwarding.superConstructor();
        checkCheckedExceptions(target, function.nameSpan());
        List<IrOperand> operands = new ArrayList<>();
        operands.add(convertReference(thisOperand, forwarding.superReceiverType(), function.nameSpan()));
        if (target.enclosingInstanceType().isPresent()) {
            if (forwardedSuperEnclosingOperand == null) {
                diagnostics.add(error(function.nameSpan(), "anonymous superclass constructor requires an "
                        + "enclosing instance of '"
                        + target.enclosingInstanceType().orElseThrow().displayName() + "'"));
                operands.add(new IrNull(target.enclosingInstanceType().orElseThrow(), function.nameSpan()));
            } else {
                operands.add(forwardedSuperEnclosingOperand);
            }
        }
        TypeSymbol targetOwner = hierarchy.type(target.ownerType()).orElse(null);
        if (targetOwner != null) {
            operands.addAll(constructorCaptureOperands(targetOwner, function.nameSpan()));
        }
        List<TypedValue> sourceArguments = new ArrayList<>();
        for (int index = 0; index < function.parameters().size(); index++) {
            Parameter parameter = function.parameters().get(index);
            LocalSymbol symbol = resolve(parameter.name());
            IrOperand value = symbol == null ? defaultValue(function.parameterTypes().get(index),
                    parameter.nameSpan()) : environment.get(symbol);
            operands.add(value);
            sourceArguments.add(new TypedValue(function.parameterTypes().get(index), value));
        }
        emitCall(new IrCallInstruction(Optional.empty(), target.linkageName(), IrType.VOID,
                operands, function.nameSpan()), function.nameSpan());
        markConstructorArgumentsEscaped(sourceArguments);
    }

    private List<IrOperand> constructorCaptureOperands(TypeSymbol target, SourceSpan span) {
        List<IrOperand> operands = new ArrayList<>();
        for (TypeSymbol.CaptureSlot capture : target.captureSlots()) {
            IrOperand operand = captureParameterOperands.get(capture.variable().id());
            if (operand == null) {
                diagnostics.add(error(span, "constructor for lexical class '" + target.sourceName()
                        + "' requires captured variable '" + capture.variable().name() + "'"));
                operand = defaultValue(capture.type(), span);
            }
            operands.add(operand);
        }
        return operands;
    }

    private List<IrOperand> constructionCaptureOperands(TypeSymbol target, IrType exactType,
                                                        SourceSpan span) {
        Map<String, IrType> substitutions = target.substitutionFor(exactType);
        List<IrOperand> operands = new ArrayList<>();
        for (TypeSymbol.CaptureSlot capture : target.captureSlots()) {
            IrType requiredType = capture.type().substitute(substitutions);
            IrOperand operand = capturedVariableOperand(capture.variable(), span);
            if (operand == null) {
                diagnostics.add(error(span, "cannot supply captured variable '"
                        + capture.variable().name() + "' to lexical class '"
                        + target.sourceName() + "'"));
                operand = defaultValue(requiredType, span);
            } else if (!operand.type().equals(requiredType)) {
                if (operand.type().isReference() && requiredType.isReference()) {
                    operand = convertReference(operand, requiredType, span);
                } else if (operand.type().isNumeric() && requiredType.isNumeric()) {
                    operand = convertNumeric(operand, requiredType, span);
                }
            }
            if (requiredType.isReference()) {
                markEscaped(operand, "allocation is retained as captured variable '"
                        + capture.variable().name() + "' by '" + target.sourceName() + "'");
            }
            operands.add(operand);
        }
        return operands;
    }

    private IrOperand capturedVariableOperand(LocalClassSemantics.VariableIdentity variable,
                                              SourceSpan span) {
        for (Map<String, LocalSymbol> scope : scopes) {
            for (LocalSymbol symbol : scope.values()) {
                if (symbol.variableIdentity() != null
                        && symbol.variableIdentity().id().equals(variable.id())) {
                    return environment.get(symbol);
                }
            }
        }
        IrOperand parameter = captureParameterOperands.get(variable.id());
        if (parameter != null && evaluatingConstructorArguments) {
            return parameter;
        }
        TypeSymbol.CaptureSlot capture = currentClass.captureSlot(variable.id()).orElse(null);
        if (capture != null && thisOperand != null) {
            IrValueReference loaded = newValue(capture.type(), span);
            currentBlock.addInstruction(new IrFieldLoadInstruction(loaded, thisOperand,
                    capture.field(), span));
            return loaded;
        }
        return parameter;
    }

    private static String captureParameterName(TypeSymbol.CaptureSlot capture) {
        return "<capture:" + capture.variable().declarationOrdinal() + ":"
                + capture.variable().name() + ">";
    }

    private void lowerInstanceInitializations() {
        ClassDeclaration declaration = (ClassDeclaration) currentClass.declaration();
        for (InstanceInitialization initialization : declaration.instanceInitializations()) {
            if (initialization instanceof FieldDeclaration fieldDeclaration) {
                if (fieldDeclaration.initializer().isPresent()) {
                    FieldSymbol field = currentClass.declaredFields().get(fieldDeclaration.name());
                    Expression initializer = fieldDeclaration.initializer().orElseThrow();
                    loweringInstanceInitializer = true;
                    try {
                        storeField(thisOperand, field, initializer, initializer.span());
                    } finally {
                        loweringInstanceInitializer = false;
                    }
                }
                declaredInstanceInitializerFields.add(fieldDeclaration.name());
                continue;
            }
            Block block = (Block) initialization;
            loweringInstanceInitializer = true;
            boolean reachable;
            try {
                reachable = lowerBlock(block, true);
            } finally {
                loweringInstanceInitializer = false;
            }
            if (!reachable) {
                diagnostics.add(error(block.span(),
                        "instance initializer must be able to complete normally"));
                currentBlock = createBlock("initializer.unreachable", block.span());
            }
        }
    }

    private boolean lowerBlock(Block block, boolean createScope) {
        if (createScope) {
            enterScope();
        }
        boolean reachable = true;
        for (Statement statement : block.statements()) {
            if (!reachable) {
                diagnostics.add(error(statement.span(), "unreachable statement"));
                continue;
            }
            reachable = lowerStatement(statement);
        }
        if (createScope) {
            exitScope();
        }
        return reachable;
    }

    private boolean lowerStatement(Statement statement) {
        boolean reachable = lowerStatementValue(statement);
        if (reachable && expressionDepth == 0 && currentBlock.terminator == null) {
            observeUnfreed(false, false);
        }
        return reachable;
    }

    private boolean lowerStatementValue(Statement statement) {
        if (statement instanceof EmptyStatement) {
            return true;
        }
        if (statement instanceof Block block) {
            return lowerBlock(block, true);
        }
        if (statement instanceof LocalVariableDeclaration declaration) {
            lowerLocalVariable(declaration);
            return true;
        }
        if (statement instanceof LocalClassDeclaration) {
            return true;
        }
        if (statement instanceof EnumConstantInitialization initialization) {
            lowerEnumConstantInitialization(initialization);
            return true;
        }
        if (statement instanceof AssignmentStatement assignment) {
            lowerAssignment(assignment);
            return true;
        }
        if (statement instanceof ExpressionStatement expressionStatement) {
            lowerExpressionStatement(expressionStatement);
            return true;
        }
        if (statement instanceof FreeStatement freeStatement) {
            lowerFree(freeStatement);
            return true;
        }
        if (statement instanceof ReturnStatement returnStatement) {
            lowerReturn(returnStatement);
            return false;
        }
        if (statement instanceof ThrowStatement throwStatement) {
            lowerThrow(throwStatement);
            return false;
        }
        if (statement instanceof TryStatement tryStatement) {
            return lowerTryFlow(() -> lowerTry(tryStatement));
        }
        if (statement instanceof IfStatement ifStatement) {
            return lowerPathSensitiveFlow(() -> lowerIf(ifStatement));
        }
        if (statement instanceof WhileStatement whileStatement) {
            return lowerPathSensitiveFlow(() -> lowerWhile(whileStatement, null));
        }
        if (statement instanceof DoWhileStatement doWhileStatement) {
            return lowerPathSensitiveFlow(() -> lowerDoWhile(doWhileStatement, null));
        }
        if (statement instanceof ForStatement forStatement) {
            FieldSymbol ownedElements = function.isDestructor()
                    ? OwnedArrayElementAnalyzer.fieldFor(currentClass, forStatement) : null;
            if (ownedElements != null && ownedArrayFields.isOwned(ownedElements)
                    && function.body().orElseThrow().statements().contains(forStatement)
                    && resolve(((LocalVariableDeclaration) forStatement.initializer().orElseThrow()).name()) == null) {
                IrValueReference array = newValue(ownedElements.type(), statement.span());
                currentBlock.addInstruction(new IrFieldLoadInstruction(array, thisOperand,
                        ownedElements.irField(), statement.span()));
                currentBlock.addInstruction(new IrDestroyArrayElementsInstruction(array, statement.span()));
                return true;
            }
            return lowerPathSensitiveFlow(() -> lowerFor(forStatement, null));
        }
        if (statement instanceof EnhancedForStatement enhancedForStatement) {
            return lowerPathSensitiveFlow(() -> lowerEnhancedFor(enhancedForStatement, null));
        }
        if (statement instanceof LabeledStatement labeledStatement) {
            return lowerPathSensitiveFlow(() -> lowerLabeled(labeledStatement));
        }
        if (statement instanceof SwitchStatement switchStatement) {
            return lowerPathSensitiveFlow(() -> lowerSwitch(switchStatement));
        }
        if (statement instanceof ModernSwitchStatement switchStatement) {
            return lowerPathSensitiveFlow(() -> lowerModernSwitch(switchStatement));
        }
        if (statement instanceof YieldStatement yieldStatement) {
            return lowerYield(yieldStatement);
        }
        if (statement instanceof BreakStatement breakStatement) {
            return lowerBreak(breakStatement);
        }
        if (statement instanceof ContinueStatement continueStatement) {
            return lowerContinue(continueStatement);
        }
        if (statement instanceof SuperConstructorInvocation invocation) {
            invocation.enclosingInstance().ifPresent(this::lowerExpression);
            invocation.arguments().forEach(this::lowerExpression);
            diagnostics.add(error(invocation.span(),
                    "super(...) must be the first statement in a constructor"));
            return true;
        }
        if (statement instanceof ThisConstructorInvocation invocation) {
            invocation.arguments().forEach(this::lowerExpression);
            diagnostics.add(error(invocation.span(),
                    "this(...) must be the first statement in a constructor"));
            return true;
        }
        throw new IllegalStateException("unsupported statement " + statement.getClass().getSimpleName());
    }

    private void lowerEnumConstantInitialization(EnumConstantInitialization initialization) {
        FieldSymbol field = currentClass.declaredFields().get(initialization.constant().name());
        if (field == null || field.staticField() == null
                || !(field.staticField().initialValue() instanceof IrEnumConstant constant)) {
            diagnostics.add(error(initialization.span(), "cannot lower enum constant '"
                    + initialization.constant().name() + "'"));
            return;
        }
        List<TypedValue> arguments = initialization.constant().arguments().stream()
                .map(this::lowerExpression).toList();
        TypeSymbol storageClass = hierarchy.type(constant.storageType().referenceName())
                .orElse(currentClass);
        if (storageClass != currentClass) {
            ensureTypeInitialized(storageClass.name(), initialization.span());
        }
        CallableSymbol constructor = selectOverload(hierarchy.constructors(storageClass.selfType()),
                arguments, "constructor for enum '" + currentClass.name() + "'",
                initialization.span());
        if (constructor == null) {
            return;
        }
        checkCheckedExceptions(constructor, initialization.span());
        List<IrOperand> operands = new ArrayList<>();
        IrOperand receiver = constant;
        if (!constant.storageType().equals(constant.type())) {
            IrValueReference concreteReceiver = newValue(constant.storageType(),
                    initialization.span());
            currentBlock.addInstruction(new IrReferenceConversionInstruction(
                    concreteReceiver, constant, initialization.span()));
            receiver = concreteReceiver;
        }
        operands.add(receiver);
        operands.addAll(checkArguments(initialization.constant().name(),
                initialization.constant().arguments(), arguments,
                constructor.parameterTypes(), true, initialization.span()));
        emitCall(new IrCallInstruction(Optional.empty(), constructor.linkageName(),
                IrType.VOID, operands, initialization.span()), initialization.span());
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).type().isReference()) {
                markEscaped(arguments.get(index).operand(),
                        "allocation escapes through enum constructor argument " + (index + 1));
            }
        }
        currentBlock.addInstruction(new IrStaticFieldStoreInstruction(
                field.staticField(), constant, initialization.span()));
    }

    private boolean lowerPathSensitiveFlow(java.util.function.BooleanSupplier lowering) {
        controlFlowDepth++;
        try {
            return lowering.getAsBoolean();
        } finally {
            controlFlowDepth--;
        }
    }

    private boolean lowerTryFlow(java.util.function.BooleanSupplier lowering) {
        controlFlowDepth++;
        try {
            return lowering.getAsBoolean();
        } finally {
            controlFlowDepth--;
        }
    }

    private void lowerLocalVariable(LocalVariableDeclaration declaration) {
        IrType declaredType = resolveType(declaration.type());
        if (declaration.hasSuppressUnfreedDirective() && !declaredType.isReference()) {
            diagnostics.add(error(declaration.type().span(),
                    "the @SuppressUnfreed directive requires a reference local variable"));
        }
        if (declaredType.equals(IrType.VOID)) {
            diagnostics.add(error(declaration.nameSpan(), "local variable cannot have type void"));
            declaredType = IrType.I32;
        }
        TypedValue initializer = lowerExpression(declaration.initializer(), Optional.of(declaredType));
        if (!isAssignmentConvertible(declaredType, initializer)) {
            diagnostics.add(error(declaration.initializer().span(),
                    "cannot initialize " + typeName(declaredType) + " variable '" + declaration.name()
                            + "' with " + typeName(initializer.type()) + " value"));
        }
        IrOperand value = assignmentValue(initializer, declaredType, declaration.initializer().span(),
                "local variable initializer");
        LocalSymbol symbol = declare(declaration.name(), declaredType, declaration.nameSpan(),
                "local variable", declaration.isFinal());
        if (symbol != null) {
            environment.put(symbol, value);
            if (unfreed != null) {
                AllocationInfo allocation = allocationOf(value);
                unfreed.name(allocation, declaration.name());
                if (declaration.hasSuppressUnfreedDirective()) unfreed.suppress(allocation);
            }
            if (declaration.isFinal() && (declaredType.isIntegral()
                    || declaredType.equals(IrType.I1))
                    && initializer.integralConstant() != null
                    && isAssignmentConvertible(declaredType, initializer)) {
                localConstants.put(symbol, new CaseConstant(declaredType,
                        declaredType.equals(IrType.I1)
                                ? (initializer.integralConstant().signum() == 0
                                ? BigInteger.ZERO : BigInteger.ONE)
                                : wrapIntegral(initializer.integralConstant(), declaredType)));
            }
        }
    }

    private void lowerAssignment(AssignmentStatement assignment) {
        if (assignment.target() instanceof NameExpression name) {
            LocalSymbol symbol = resolve(name.name());
            if (symbol != null) {
                TypedValue value = lowerExpression(assignment.value(), Optional.of(symbol.type()));
                if (symbol.isFinal()) {
                    diagnostics.add(error(name.span(), "cannot assign to final variable '"
                            + name.name() + "'"));
                    return;
                }
                if (!isAssignmentConvertible(symbol.type(), value)) {
                    diagnostics.add(error(assignment.value().span(),
                            "cannot assign " + typeName(value.type()) + " value to " + typeName(symbol.type())
                                    + " variable '" + name.name() + "'"));
                }
                environment.put(symbol, assignmentValue(value, symbol.type(), assignment.value().span(), "assignment"));
                return;
            }
            FieldSymbol field = resolveField(currentClass.selfType(), name.name(), name.span());
            if (field == null) {
                FieldTarget lexical = resolveLexicalField(name.name(), name.span());
                if (lexical == null) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(name.span(), "unknown local variable '" + name.name() + "'"));
                    return;
                }
                field = lexical.field();
                if (field.isFinal()) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(name.span(), "cannot assign to final field '" + name.name() + "'"));
                    return;
                }
                if (field.isStatic()) {
                    storeStaticField(field, assignment.value(), assignment.span());
                } else if (lexical.receiver() == null) {
                    lowerExpression(assignment.value());
                } else {
                    storeField(lexical.receiver(), field, assignment.value(), assignment.span());
                }
                return;
            }
            if (field.isFinal()) {
                if (!canAssignBlankFinal(field, true)) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(name.span(), "cannot assign to final field '" + name.name() + "'"));
                    return;
                }
            }
            if (!isAccessible(field.accessModifier(), field.ownerClass(), currentClass.name(), false)) {
                diagnostics.add(error(name.span(), memberAccessMessage("field", name.name(),
                        field.accessModifier(), field.ownerClass())));
            }
            if (field.isStatic()) {
                storeStaticField(field, assignment.value(), assignment.span());
                return;
            }
            if (function.isStatic()) {
                lowerExpression(assignment.value());
                diagnostics.add(error(name.span(),
                        "instance field '" + name.name() + "' cannot be referenced from a static method"));
                return;
            }
            storeField(thisOperand, field, assignment.value(), assignment.span());
            return;
        }
        if (assignment.target() instanceof FieldAccessExpression access) {
            if (access.receiver() instanceof SuperExpression superExpression) {
                FieldTarget target = resolveSuperFieldTarget(access, superExpression);
                if (target == null) {
                    lowerExpression(assignment.value());
                    return;
                }
                if (target.field().isFinal()) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(access.fieldNameSpan(), "cannot assign to final field '"
                            + access.fieldName() + "'"));
                    return;
                }
                storeField(target.receiver(), target.field(), assignment.value(), assignment.span());
                return;
            }
            ClassFieldResolution qualified = resolveClassField(access);
            if (qualified.classQualifier()) {
                if (qualified.field() == null) {
                    lowerExpression(assignment.value());
                    return;
                }
                if (!qualified.field().isStatic()) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(access.fieldNameSpan(), "instance field '"
                            + access.fieldName() + "' cannot be accessed through type '"
                            + qualified.qualifier().name() + "'"));
                    return;
                }
                if (qualified.field().isFinal()) {
                    lowerExpression(assignment.value());
                    diagnostics.add(error(access.fieldNameSpan(), "cannot assign to final field '"
                            + access.fieldName() + "'"));
                    return;
                }
                storeStaticField(qualified.field(), assignment.value(), assignment.span());
                return;
            }
            FieldTarget target = resolveFieldTarget(access);
            if (target == null) {
                lowerExpression(assignment.value());
                return;
            }
            if (target.field().isFinal() && (!(access.receiver() instanceof ThisExpression)
                    || !canAssignBlankFinal(target.field(), true))) {
                lowerExpression(assignment.value());
                diagnostics.add(error(access.fieldNameSpan(), "cannot assign to final field '"
                        + access.fieldName() + "'"));
                return;
            }
            storeField(target.receiver(), target.field(), assignment.value(), assignment.span());
            return;
        }
        if (assignment.target() instanceof ArrayAccessExpression access) {
            ArrayTarget target = resolveArrayTarget(access);
            if (target == null) {
                lowerExpression(assignment.value());
                return;
            }
            IrType elementType = target.array().type().elementType();
            TypedValue value = lowerExpression(assignment.value(), Optional.of(elementType));
            if (!isAssignmentConvertible(elementType, value)) {
                diagnostics.add(error(assignment.value().span(), "cannot assign "
                        + typeName(value.type()) + " value to " + typeName(elementType)
                        + " array element"));
            }
            IrOperand operand = isAssignmentConvertible(elementType, value)
                    ? assignmentValue(value, elementType, assignment.value().span(),
                    "array element assignment")
                    : defaultValue(elementType, assignment.value().span());
            trackArrayElementStore(target.array(), target.index(), operand);
            currentBlock.addInstruction(new IrArrayStoreInstruction(target.array(), target.index(),
                    operand, assignment.span()));
            return;
        }
        lowerExpression(assignment.target());
        lowerExpression(assignment.value());
        diagnostics.add(error(assignment.equalsSpan(),
                "left side of assignment must be a local variable or instance field"));
    }

    private void storeField(IrOperand receiver, FieldSymbol field, Expression valueExpression, SourceSpan span) {
        TypedValue value = lowerExpression(valueExpression, Optional.of(field.type()));
        if (!isAssignmentConvertible(field.type(), value)) {
            diagnostics.add(error(valueExpression.span(),
                    "cannot assign " + typeName(value.type()) + " value to " + typeName(field.type())
                            + " field '" + field.declaration().name() + "'"));
        }
        IrOperand operand = assignmentValue(value, field.type(), valueExpression.span(), "field assignment");
        checkNotFreed(receiver, span);
        detachOwnedField(receiver, field, operand);
        markEscaped(operand, "allocation escapes through field '"
                + field.declaration().name() + "'");
        currentBlock.addInstruction(new IrFieldStoreInstruction(receiver, field.irField(), operand, span));
    }

    private void storeStaticField(FieldSymbol field, Expression valueExpression, SourceSpan span) {
        TypedValue value = lowerExpression(valueExpression, Optional.of(field.type()));
        if (!isAssignmentConvertible(field.type(), value)) {
            diagnostics.add(error(valueExpression.span(), "cannot assign " + typeName(value.type())
                    + " value to " + typeName(field.type()) + " static field '"
                    + field.declaration().name() + "'"));
        }
        IrOperand operand = isAssignmentConvertible(field.type(), value)
                ? assignmentValue(value, field.type(), valueExpression.span(), "static field assignment")
                : defaultValue(field.type(), valueExpression.span());
        if (field.staticField().triggersInitialization()) {
            ensureTypeInitialized(field.ownerClass(), span);
        }
        markEscaped(operand, "allocation escapes through static field '"
                + field.ownerClass() + "." + field.declaration().name() + "'");
        currentBlock.addInstruction(new IrStaticFieldStoreInstruction(field.staticField(), operand, span));
    }

    private void lowerExpressionStatement(ExpressionStatement statement) {
        if (!(statement.expression() instanceof CallExpression)
                && !(statement.expression() instanceof NewExpression)
                && !(statement.expression() instanceof AssignmentExpression)
                && !(statement.expression() instanceof UpdateExpression)) {
            diagnostics.add(error(statement.expression().span(),
                    "expression statement must be an assignment, increment, decrement, method call, or object creation"));
        }
        SourceSpan previous = discardedCallSpan;
        discardedCallSpan = statement.expression() instanceof CallExpression
                ? statement.expression().span() : null;
        try {
            lowerExpression(statement.expression());
        } finally {
            discardedCallSpan = previous;
        }
    }

    private void lowerFree(FreeStatement statement) {
        if (function.isDestructor()) {
            FieldSymbol attached = destructorFreeField(statement.value());
            if (attached != null) {
                lowerDestructorFieldFree(attached, statement);
                return;
            }
        }
        LocalSymbol symbol = null;
        IrOperand operand;
        IrType targetType;
        String targetName;
        SourceSpan targetSpan = statement.value().span();
        if (statement.value() instanceof NameExpression name) {
            symbol = resolve(name.name());
            if (symbol == null) {
                diagnostics.add(error(name.span(),
                        "free target must be a local variable, not a field or type name"));
                return;
            }
            operand = environment.get(symbol);
            targetType = symbol.type();
            targetName = "'" + name.name() + "'";
        } else {
            TypedValue value = lowerExpression(statement.value());
            operand = value.operand();
            targetType = value.type();
            targetName = "expression";
        }
        if (!targetType.isReference() && !targetType.equals(IrType.NULL)) {
            diagnostics.add(error(targetSpan,
                    "free target must have a class, interface, or array reference type, not "
                            + typeName(targetType)));
            return;
        }
        AllocationInfo allocation = allocationOf(operand);
        if (allocation == null) {
            String reason = symbol == null
                    ? "free target must be a local variable created by new in this method "
                    + "or a proven fresh expression"
                    : "value is not a known allocation created by new in this method, "
                    + "returned by a proven fresh factory, or a proven detached private "
                    + "backing array";
            diagnostics.add(error(targetSpan, "cannot prove free of " + targetName
                    + " safe: " + reason));
            return;
        }
        if (isDependentBorrow(operand)) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": value is a borrowed helper owned by another object"));
            return;
        }
        AllocationInfo retainingOwner = constructorBorrows.entrySet().stream()
                .filter(entry -> entry.getKey() != allocation && entry.getValue().contains(allocation))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (retainingOwner != null) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": allocation is still borrowed by a live "
                    + (isKnownContainer(retainingOwner) ? "container" : "wrapper")));
            return;
        }
        if (pendingYieldAllocations.contains(allocation)) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": allocation is retained by a pending yield result"));
            return;
        }
        if (allocation.state == AllocationState.FREED) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": allocation was already freed"));
            return;
        }
        if (allocation.state == AllocationState.ESCAPED
                || allocation.state == AllocationState.UNCERTAIN
                || allocation.state == AllocationState.MAYBE_FREED) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName + ": "
                    + allocation.blockingReason));
            return;
        }
        if (allocation.origin == AllocationOrigin.OWNED_FIELD && !allocation.detached) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": allocation is still reachable through private field '"
                    + allocation.ownedFieldName + "'"));
            return;
        }
        ArraySlot storedAlias = knownArraySlots.entrySet().stream()
                .filter(entry -> entry.getValue() == allocation)
                .map(Map.Entry::getKey)
                .filter(slot -> slot.container() != allocation)
                .findFirst().orElse(null);
        if (storedAlias != null) {
            diagnostics.add(error(targetSpan, "cannot free " + targetName
                    + ": allocation is still reachable through known array element ["
                    + storedAlias.index() + "]"));
            return;
        }
        LocalSymbol freedSymbol = symbol;
        LocalSymbol alias = environment.entrySet().stream()
                .filter(entry -> freedSymbol == null || !entry.getKey().equals(freedSymbol))
                .filter(entry -> allocationOf(entry.getValue()) == allocation)
                .filter(entry -> !isDependentBorrow(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (alias != null) {
            String reason = "allocation may still be observed through local '" + alias.name() + "'";
            diagnostics.add(error(targetSpan, "cannot free " + targetName + ": " + reason));
            return;
        }
        currentBlock.addInstruction(new IrFreeInstruction(operand, statement.span()));
        reclamations.add(new Reclamation(allocation, statement.span()));
        allocation.state = AllocationState.FREED;
        constructorBorrows.remove(allocation);
        knownArraySlots.keySet().removeIf(slot -> slot.container() == allocation);
    }

    private FieldSymbol destructorFreeField(Expression expression) {
        String fieldName;
        if (expression instanceof NameExpression name) {
            if (resolve(name.name()) != null) {
                return null;
            }
            fieldName = name.name();
        } else if (expression instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression) {
            fieldName = access.fieldName();
        } else {
            return null;
        }
        FieldSymbol field = currentClass.declaredFields().get(fieldName);
        return field != null && !field.isStatic() ? field : null;
    }

    private void lowerDestructorFieldFree(FieldSymbol field, FreeStatement statement) {
        if (!field.type().isReference()) {
            diagnostics.add(error(statement.value().span(),
                    "destructor free target must have a reference type, not "
                            + typeName(field.type())));
            return;
        }
        if (!ownedArrayFields.isOwned(field)) {
            diagnostics.add(error(statement.value().span(), "cannot prove destructor free of field '"
                    + field.declaration().name() + "' safe: field ownership is uncertain"));
            return;
        }
        IrValueReference value = newValue(field.type(), statement.value().span());
        currentBlock.addInstruction(new IrFieldLoadInstruction(value, thisOperand,
                field.irField(), statement.value().span()));
        currentBlock.addInstruction(new IrFieldStoreInstruction(thisOperand, field.irField(),
                defaultValue(field.type(), statement.value().span()), statement.value().span()));
        currentBlock.addInstruction(new IrFreeInstruction(value, statement.span()));
    }

    private void lowerReturn(ReturnStatement statement) {
        if (function.isDestructor()) {
            statement.value().ifPresent(this::lowerExpression);
            diagnostics.add(error(statement.span(),
                    "return is not permitted in a destructor"));
            completeReturn(Optional.empty(), statement.span());
            return;
        }
        if (function.sourceName().equals("<clinit>")) {
            statement.value().ifPresent(this::lowerExpression);
            diagnostics.add(error(statement.span(),
                    "return is not permitted in a static initializer"));
            completeReturn(Optional.empty(), statement.span());
            return;
        }
        if (loweringInstanceInitializer) {
            statement.value().ifPresent(this::lowerExpression);
            diagnostics.add(error(statement.span(),
                    "return is not permitted in an instance initializer"));
            completeReturn(Optional.empty(), statement.span());
            return;
        }
        if (function.returnType().equals(IrType.VOID)) {
            if (statement.value().isPresent()) {
                lowerExpression(statement.value().get());
                diagnostics.add(error(statement.span(),
                        function.isConstructor()
                                ? "a constructor cannot return a value"
                                : "a void method cannot return a value"));
            }
            completeReturn(Optional.empty(), statement.span());
            return;
        }

        if (statement.value().isEmpty()) {
            diagnostics.add(error(statement.span(),
                    "a " + typeName(function.returnType()) + " method must return a value"));
            completeReturn(Optional.of(defaultValue(function.returnType(), statement.span())),
                    statement.span());
            return;
        }
        TypedValue value = lowerExpression(statement.value().get(), Optional.of(function.returnType()));
        if (!isAssignmentConvertible(function.returnType(), value)) {
            diagnostics.add(error(statement.value().get().span(),
                    "cannot return " + typeName(value.type()) + " from " + typeName(function.returnType())
                            + " method '" + function.sourceName() + "'"));
        }
        IrOperand operand = assignmentValue(value, function.returnType(), statement.value().get().span(), "return");
        markEscaped(operand, "allocation escapes through the method return value");
        completeReturn(Optional.of(operand), statement.span());
    }

    private void completeReturn(Optional<IrOperand> value, SourceSpan span) {
        completeReturnThrough(List.copyOf(finallyContexts), 0, value, span);
    }

    private void completeReturnThrough(List<FinallyContext> pending, int index,
                                       Optional<IrOperand> value, SourceSpan span) {
        if (index >= pending.size()) {
            observeUnfreed(true, true);
            currentBlock.terminate(new IrReturnTerminator(value, span));
            return;
        }
        FinallyContext context = pending.get(index);
        // Sibling cleanup copies are mutually exclusive runtime paths.
        LinkedHashMap<LocalSymbol, IrOperand> beforeEnvironment = copyEnvironment();
        OwnershipSnapshot beforeOwnership = snapshotOwnership();
        List<ExceptionRegion> savedExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> savedFinally = List.copyOf(finallyContexts);
        try {
            restoreDeque(exceptionRegions, context.outerExceptionRegions());
            restoreDeque(finallyContexts, context.outerFinallyContexts());
            boolean reachable = lowerBlock(context.body(), true);
            if (reachable) {
                completeReturnThrough(pending, index + 1, value, span);
            }
        } finally {
            environment = beforeEnvironment;
            restoreOwnership(beforeOwnership);
            restoreDeque(exceptionRegions, savedExceptions);
            restoreDeque(finallyContexts, savedFinally);
        }
    }

    private void lowerThrow(ThrowStatement statement) {
        LocalSymbol rethrownCatch = statement.value() instanceof NameExpression name
                ? resolve(name.name()) : null;
        TypedValue value = lowerExpression(statement.value());
        IrOperand exception;
        if (value.type().equals(IrType.NULL)) {
            exception = new IrNull(IrType.reference("ironwood.lang.Throwable"),
                    statement.value().span());
        } else if (!value.type().isReference()) {
            diagnostics.add(error(statement.value().span(),
                    "thrown value must be an object reference, not " + typeName(value.type())));
            exception = new IrNull(IrType.reference(currentClass.name()), statement.value().span());
        } else if (hierarchy.type("ironwood.lang.Throwable").isPresent()
                && !hierarchy.isAssignable(IrType.reference("ironwood.lang.Throwable"),
                value.type())) {
            diagnostics.add(error(statement.value().span(), "thrown type '"
                    + typeName(value.type()) + "' must extend ironwood.lang.Throwable"));
            exception = new IrNull(IrType.reference("ironwood.lang.Throwable"),
                    statement.value().span());
        } else {
            List<IrType> preciseTypes = preciseRethrowTypes.get(rethrownCatch);
            if (preciseTypes == null) {
                checkCheckedException(value.type(), statement.value().span(), "thrown exception");
            } else {
                preciseTypes.forEach(type -> checkCheckedException(type,
                        statement.value().span(), "precisely rethrown exception"));
            }
            exception = requireValue(value, value.type(), statement.value().span(), "throw statement");
            markEscaped(exception, "allocation escapes through a thrown exception");
        }
        boolean knownNonNull = statement.value() instanceof ThisExpression;
        if (!knownNonNull && (value.type().equals(IrType.NULL) || (value.type().isReference()
                && hierarchy.type("ironwood.lang.Throwable").isPresent()
                && hierarchy.isAssignable(IrType.reference("ironwood.lang.Throwable"), value.type())))) {
            emitNullCheck(exception, statement.span());
        }
        emitThrow(exception, statement.span());
    }

    private void emitThrow(IrOperand exception, SourceSpan span) {
        MutableBlock predecessor = currentBlock;
        MutableBlock impossible = createBlock("throw.unreachable", span);
        ExceptionRegion region = exceptionRegions.peek();
        Optional<String> unwindTarget = region == null
                ? Optional.empty() : Optional.of(region.landingPad.label);
        predecessor.terminate(new IrThrowTerminator(exception, impossible.label, unwindTarget, span));
        if (region != null) {
            region.addEdge(new ExceptionEdge(predecessor, copyEnvironment(),
                    snapshotOwnership()));
        }
        impossible.terminate(new IrUnreachable(span));
        currentBlock = impossible;
    }

    private boolean lowerTry(TryStatement statement) {
        List<CatchTarget> catchTargets = resolveCatchTargets(statement.catches());
        List<IrType> catchTypes = catchTargets.stream()
                .flatMap(target -> target.alternatives().stream())
                .filter(CatchAlternative::valid)
                .map(CatchAlternative::type).toList();
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        List<ExceptionRegion> outerExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> outerFinally = List.copyOf(finallyContexts);

        MutableBlock tryLanding = createBlock("try.landing", statement.span());
        ExceptionRegion tryRegion = new ExceptionRegion(tryLanding);
        ExceptionRegion catchEscapeRegion = null;
        if (!statement.catches().isEmpty() && statement.finallyBlock().isPresent()) {
            catchEscapeRegion = new ExceptionRegion(createBlock("catch.landing", statement.span()));
        }
        FinallyContext finallyContext = statement.finallyBlock()
                .map(block -> new FinallyContext(block, outerExceptions, outerFinally))
                .orElse(null);

        exceptionRegions.push(tryRegion);
        checkedCatchScopes.push(catchTypes);
        observedTryBodyExceptions.push(new LinkedHashSet<>());
        if (finallyContext != null) {
            finallyContexts.push(finallyContext);
        }
        boolean tryReachable = lowerBlock(statement.body(), true);
        OwnershipSnapshot tryOwnership = snapshotOwnership();
        Set<IrType> observed = observedTryBodyExceptions.pop();
        checkedCatchScopes.pop();
        diagnoseUnreachableCheckedCatches(statement, catchTargets, observed);
        List<List<IrType>> preciseCatchTypes = preciseCatchTypes(catchTargets, observed);
        observed.stream()
                .filter(thrown -> catchTypes.stream().noneMatch(caught ->
                        hierarchy.isSubtype(thrown, caught)))
                .forEach(this::propagateObservedException);
        restoreDeque(exceptionRegions, outerExceptions);
        restoreDeque(finallyContexts, outerFinally);

        List<BranchFlow> normalFlows = new ArrayList<>();
        List<OwnershipSnapshot> normalOwnerships = new ArrayList<>();
        if (tryReachable) {
            restoreOwnership(tryOwnership);
            boolean afterFinally = finallyContext == null || lowerFinallyBody(finallyContext);
            if (afterFinally) {
                normalFlows.add(new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership()));
                normalOwnerships.add(snapshotOwnership());
            }
        }

        if (tryRegion.edges.isEmpty()) {
            tryLanding.terminate(new IrUnreachable(statement.span()));
            for (int index = 0; index < statement.catches().size(); index++) {
                restoreOwnership(ownershipBefore);
                analyzeDeadCatch(statement.catches().get(index), catchTargets.get(index), before,
                        finallyContext, preciseCatchTypes.get(index));
            }
            restoreOwnership(tryOwnership);
        } else {
            IrOperand exception = beginExceptionHandler(tryRegion, before, ownershipBefore,
                    statement.span());
            LinkedHashMap<LocalSymbol, IrOperand> handlerEnvironment = copyEnvironment();
            OwnershipSnapshot handlerOwnership = snapshotOwnership();
            if (statement.catches().isEmpty()) {
                if (finallyContext == null) {
                    emitThrow(exception, statement.span());
                } else {
                    lowerFinallyForPendingException(finallyContext, exception, statement.span());
                }
            } else {
                lowerCatchDispatch(statement, catchTargets, exception, handlerEnvironment,
                        handlerOwnership, finallyContext, catchEscapeRegion, normalFlows,
                        normalOwnerships,
                        outerExceptions, outerFinally, preciseCatchTypes);
            }
        }

        if (catchEscapeRegion != null) {
            if (catchEscapeRegion.edges.isEmpty()) {
                catchEscapeRegion.landingPad.terminate(new IrUnreachable(statement.span()));
            } else {
                restoreDeque(exceptionRegions, outerExceptions);
                restoreDeque(finallyContexts, outerFinally);
                IrOperand exception = beginExceptionHandler(catchEscapeRegion, before,
                        ownershipBefore, statement.span());
                lowerFinallyForPendingException(finallyContext, exception, statement.span());
            }
        }

        restoreDeque(exceptionRegions, outerExceptions);
        restoreDeque(finallyContexts, outerFinally);
        MutableBlock merge = createBlock("try.merge", statement.span());
        if (normalFlows.isEmpty()) {
            merge.terminate(new IrUnreachable(statement.span()));
            currentBlock = merge;
            environment = before;
            return false;
        }
        for (BranchFlow flow : normalFlows) {
            flow.block().terminate(new IrJump(merge.label, statement.span()));
        }
        currentBlock = merge;
        mergeOwnership(ownershipBefore, normalOwnerships,
                "allocation has conflicting ownership across try/catch paths");
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, normalFlows, statement.span(), merge));
        }
        return true;
    }

    private void diagnoseUnreachableCheckedCatches(TryStatement statement,
                                                    List<CatchTarget> targets,
                                                    Set<IrType> observed) {
        for (int index = 0; index < targets.size(); index++) {
            CatchTarget target = targets.get(index);
            CatchClause clause = statement.catches().get(index);
            for (int alternativeIndex = 0;
                 alternativeIndex < target.alternatives().size(); alternativeIndex++) {
                CatchAlternative alternative = target.alternatives().get(alternativeIndex);
                if (!alternative.valid()) {
                    continue;
                }
                IrType caught = alternative.type();
                IrType runtimeException = IrType.reference("ironwood.lang.RuntimeException");
                IrType error = IrType.reference("ironwood.lang.Error");
                boolean admitsUnchecked = hierarchy.isSubtype(caught, runtimeException)
                        || hierarchy.isSubtype(runtimeException, caught)
                        || hierarchy.isSubtype(caught, error)
                        || hierarchy.isSubtype(error, caught);
                boolean reachable = admitsUnchecked || observed.stream()
                        .anyMatch(thrown -> hierarchy.isSubtype(thrown, caught)
                                || hierarchy.isSubtype(caught, thrown));
                if (!reachable) {
                    TypeName sourceType = clause.types().get(alternativeIndex);
                    diagnostics.add(error(sourceType.span(), "checked exception '"
                            + caught.displayName()
                            + "' is never thrown in the corresponding try block"));
                }
            }
        }
    }

    private List<List<IrType>> preciseCatchTypes(List<CatchTarget> targets,
                                                 Set<IrType> observed) {
        List<List<IrType>> result = new ArrayList<>();
        List<IrType> earlierAlternatives = new ArrayList<>();
        for (CatchTarget target : targets) {
            LinkedHashSet<IrType> precise = new LinkedHashSet<>();
            for (IrType thrown : observed) {
                if (earlierAlternatives.stream()
                        .anyMatch(earlier -> hierarchy.isSubtype(thrown, earlier))) {
                    continue;
                }
                for (CatchAlternative alternative : target.alternatives()) {
                    if (!alternative.valid()) {
                        continue;
                    }
                    if (hierarchy.isSubtype(thrown, alternative.type())) {
                        precise.add(thrown);
                    } else if (hierarchy.isSubtype(alternative.type(), thrown)) {
                        precise.add(alternative.type());
                    }
                }
            }
            result.add(List.copyOf(precise));
            target.alternatives().stream().filter(CatchAlternative::valid)
                    .map(CatchAlternative::type).forEach(earlierAlternatives::add);
        }
        return List.copyOf(result);
    }

    private void propagateObservedException(IrType type) {
        if (!observedTryBodyExceptions.isEmpty()) {
            observedTryBodyExceptions.peek().add(type);
        }
    }

    private void checkCheckedExceptions(CallableSymbol target, SourceSpan span) {
        target.thrownTypes().forEach(type -> checkCheckedException(type, span,
                "call to '" + target.sourceName() + "'"));
    }

    private void checkCheckedException(IrType type, SourceSpan span, String operation) {
        if (!isCheckedException(type)) {
            return;
        }
        propagateObservedException(type);
        boolean caught = checkedCatchScopes.stream().flatMap(List::stream)
                .anyMatch(catchType -> hierarchy.isSubtype(type, catchType));
        boolean declared = function.thrownTypes().stream()
                .anyMatch(declaration -> hierarchy.isSubtype(type, declaration));
        if (!caught && !declared) {
            diagnostics.add(error(span, "unreported checked exception " + type.displayName()
                    + " from " + operation + "; catch it or declare it with throws"));
        }
    }

    private boolean isCheckedException(IrType type) {
        return hierarchy.isSubtype(type, IrType.reference("ironwood.lang.Throwable"))
                && !hierarchy.isSubtype(type, IrType.reference("ironwood.lang.RuntimeException"))
                && !hierarchy.isSubtype(type, IrType.reference("ironwood.lang.Error"));
    }

    private void lowerCatchDispatch(TryStatement statement, List<CatchTarget> targets,
                                    IrOperand exception,
                                    LinkedHashMap<LocalSymbol, IrOperand> handlerEnvironment,
                                    OwnershipSnapshot handlerOwnership,
                                    FinallyContext finallyContext,
                                    ExceptionRegion catchEscapeRegion,
                                    List<BranchFlow> normalFlows,
                                    List<OwnershipSnapshot> normalOwnerships,
                                    List<ExceptionRegion> outerExceptions,
                                    List<FinallyContext> outerFinally,
                                    List<List<IrType>> preciseCatchTypes) {
        for (int index = 0; index < statement.catches().size(); index++) {
            CatchClause clause = statement.catches().get(index);
            CatchTarget target = targets.get(index);
            MutableBlock caught = createBlock("catch.body", clause.span());
            MutableBlock next = createBlock("catch.next", clause.span());
            IrOperand matches = lowerCatchMatch(target, exception, clause.span());
            currentBlock.terminate(new IrBranch(matches, caught.label, next.label, clause.span()));

            currentBlock = caught;
            environment = new LinkedHashMap<>(handlerEnvironment);
            restoreOwnership(handlerOwnership);
            currentBlock.addInstruction(new IrExceptionCaughtInstruction(exception, clause.span()));
            enterScope();
            IrType catchType = target.bindingType();
            LocalSymbol variable = declare(clause.variableName(), catchType,
                    clause.variableNameSpan(), "catch variable", clause.isFinal());
            IrOperand caughtValue = convertReference(exception, catchType, clause.span());
            if (variable != null) {
                environment.put(variable, caughtValue);
                if (clause.isFinal() || variable.variableIdentity() != null
                        && !currentClass.variableWasWritten(variable.variableIdentity())) {
                    preciseRethrowTypes.put(variable, preciseCatchTypes.get(index));
                }
            }
            restoreDeque(exceptionRegions, outerExceptions);
            restoreDeque(finallyContexts, outerFinally);
            if (catchEscapeRegion != null) {
                exceptionRegions.push(catchEscapeRegion);
            }
            if (finallyContext != null) {
                finallyContexts.push(finallyContext);
            }
            boolean reachable = lowerBlock(clause.body(), false);
            exitScope();
            restoreDeque(exceptionRegions, outerExceptions);
            restoreDeque(finallyContexts, outerFinally);
            if (reachable) {
                boolean afterFinally = finallyContext == null || lowerFinallyBody(finallyContext);
                if (afterFinally) {
                    normalFlows.add(new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership()));
                    normalOwnerships.add(snapshotOwnership());
                }
            }
            currentBlock = next;
            environment = new LinkedHashMap<>(handlerEnvironment);
            restoreOwnership(handlerOwnership);
        }
        if (finallyContext == null) {
            emitThrow(exception, statement.span());
        } else {
            lowerFinallyForPendingException(finallyContext, exception, statement.span());
        }
    }

    private IrOperand lowerCatchMatch(CatchTarget target, IrOperand exception, SourceSpan span) {
        IrOperand matches = null;
        for (CatchAlternative alternative : target.alternatives()) {
            IrValueReference alternativeMatch = newValue(IrType.I1, alternative.span());
            currentBlock.addInstruction(new IrInstanceOfInstruction(alternativeMatch, exception,
                    alternative.typeName(), alternative.typeId(), alternative.span()));
            if (matches == null) {
                matches = alternativeMatch;
            } else {
                IrValueReference combined = newValue(IrType.I1, span);
                currentBlock.addInstruction(new IrBinaryInstruction(combined,
                        IrBinaryOperator.BITWISE_OR, matches, alternativeMatch, span));
                matches = combined;
            }
        }
        return matches == null ? new IrConstant(IrType.I1, 0, span) : matches;
    }

    private void analyzeDeadCatch(CatchClause clause, CatchTarget target,
                                  LinkedHashMap<LocalSymbol, IrOperand> before,
                                  FinallyContext finallyContext,
                                  List<IrType> preciseTypes) {
        List<ExceptionRegion> savedExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> savedFinally = List.copyOf(finallyContexts);
        exceptionRegions.clear();
        MutableBlock dead = createBlock("catch.unreachable", clause.span());
        currentBlock = dead;
        environment = new LinkedHashMap<>(before);
        enterScope();
        IrType catchType = target.bindingType();
        LocalSymbol variable = declare(clause.variableName(), catchType,
                clause.variableNameSpan(), "catch variable", clause.isFinal());
        if (variable != null) {
            environment.put(variable, new IrNull(catchType, clause.variableNameSpan()));
            if (clause.isFinal() || variable.variableIdentity() != null
                    && !currentClass.variableWasWritten(variable.variableIdentity())) {
                preciseRethrowTypes.put(variable, preciseTypes);
            }
        }
        if (finallyContext != null) {
            finallyContexts.push(finallyContext);
        }
        boolean reachable = lowerBlock(clause.body(), false);
        exitScope();
        if (reachable) {
            currentBlock.terminate(new IrUnreachable(clause.span()));
        }
        restoreDeque(exceptionRegions, savedExceptions);
        restoreDeque(finallyContexts, savedFinally);
    }

    private IrOperand beginExceptionHandler(ExceptionRegion region,
                                            LinkedHashMap<LocalSymbol, IrOperand> before,
                                            OwnershipSnapshot ownershipBefore,
                                            SourceSpan span) {
        currentBlock = region.landingPad;
        mergeOwnership(ownershipBefore, region.edges.stream()
                        .map(ExceptionEdge::ownership).toList(),
                "allocation has conflicting ownership across exceptional paths");
        environment = mergeExceptionalEnvironment(before, region, span);
        IrValueReference handle = newValue(IrType.EXCEPTION, span);
        IrValueReference object = newValue(IrType.EXCEPTION, span);
        currentBlock.addInstruction(new IrExceptionLandingPadInstruction(handle, object, span));
        return object;
    }

    private LinkedHashMap<LocalSymbol, IrOperand> mergeExceptionalEnvironment(
            LinkedHashMap<LocalSymbol, IrOperand> before, ExceptionRegion region,
            SourceSpan span) {
        LinkedHashMap<LocalSymbol, IrOperand> merged = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            IrOperand first = region.edges.getFirst().environment().get(symbol);
            boolean same = region.edges.stream()
                    .allMatch(edge -> edge.environment().get(symbol).equals(first));
            if (same || region.edges.size() == 1) {
                merged.put(symbol, first);
                continue;
            }
            IrValueReference result = newValue(symbol.type(), span);
            List<IrPhiIncoming> incoming = region.edges.stream()
                    .map(edge -> new IrPhiIncoming(edge.block().label,
                            edge.environment().get(symbol))).toList();
            region.landingPad.addPhi(new MutablePhi(result, incoming, span));
            List<IrOperand> sources = incoming.stream().map(IrPhiIncoming::value).toList();
            propagateCommonOwnedHelperBorrow(result, sources);
            mergeAllocationIdentity(result, sources);
            merged.put(symbol, result);
        }
        return merged;
    }

    private boolean lowerFinallyBody(FinallyContext context) {
        List<ExceptionRegion> savedExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> savedFinally = List.copyOf(finallyContexts);
        restoreDeque(exceptionRegions, context.outerExceptionRegions());
        restoreDeque(finallyContexts, context.outerFinallyContexts());
        boolean reachable = lowerBlock(context.body(), true);
        restoreDeque(exceptionRegions, savedExceptions);
        restoreDeque(finallyContexts, savedFinally);
        return reachable;
    }

    private void lowerFinallyForPendingException(FinallyContext context, IrOperand primary,
                                                 SourceSpan span) {
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        MutableBlock secondaryLanding = createBlock("finally.secondary", span);
        ExceptionRegion secondaryRegion = new ExceptionRegion(secondaryLanding);
        List<ExceptionRegion> cleanupExceptions = new ArrayList<>();
        cleanupExceptions.add(secondaryRegion);
        cleanupExceptions.addAll(context.outerExceptionRegions());
        FinallyContext protectedContext = new FinallyContext(context.body(), cleanupExceptions,
                context.outerFinallyContexts());

        boolean afterFinally = lowerFinallyBody(protectedContext);
        if (afterFinally) {
            emitThrow(primary, span);
        }

        if (secondaryRegion.edges.isEmpty()) {
            secondaryLanding.terminate(new IrUnreachable(span));
            return;
        }
        IrOperand secondary = beginExceptionHandler(secondaryRegion, before,
                ownershipBefore, span);
        currentBlock.addInstruction(new IrAddSecondaryExceptionInstruction(primary, secondary, span));
        emitThrow(primary, span);
    }

    private List<CatchTarget> resolveCatchTargets(List<CatchClause> catches) {
        List<CatchTarget> targets = new ArrayList<>();
        List<TypeSymbol> resolvedEarlier = new ArrayList<>();
        TypeSymbol throwable = hierarchy.type("ironwood.lang.Throwable").orElse(null);
        for (CatchClause clause : catches) {
            List<CatchAlternative> alternatives = new ArrayList<>();
            List<TypeSymbol> resolvedInClause = new ArrayList<>();
            for (TypeName sourceType : clause.types()) {
                TypeSymbol target = resolveCatchAlternative(sourceType, throwable);
                boolean valid = target != null;
                if (target != null) {
                    for (TypeSymbol other : resolvedInClause) {
                        if (hierarchy.isSubtype(target.name(), other.name())
                                || hierarchy.isSubtype(other.name(), target.name())) {
                            diagnostics.add(error(sourceType.span(), "multi-catch alternatives '"
                                    + other.name() + "' and '" + target.name()
                                    + "' cannot be related by subclassing"));
                            valid = false;
                            break;
                        }
                    }
                    for (TypeSymbol earlier : resolvedEarlier) {
                        if (hierarchy.isSubtype(target.name(), earlier.name())) {
                            diagnostics.add(error(sourceType.span(), "catch for '" + target.name()
                                    + "' is unreachable because earlier catch for '" + earlier.name()
                                    + "' already handles it"));
                            valid = false;
                            break;
                        }
                    }
                    resolvedInClause.add(target);
                }
                TypeSymbol recovery = target != null ? target
                        : throwable == null ? currentClass : throwable;
                alternatives.add(new CatchAlternative(recovery.name(), recovery.typeId(),
                        IrType.reference(recovery.name()), sourceType.span(), valid));
            }
            resolvedEarlier.addAll(resolvedInClause);
            List<IrType> validTypes = alternatives.stream().filter(CatchAlternative::valid)
                    .map(CatchAlternative::type).toList();
            IrType bindingType = validTypes.isEmpty()
                    ? IrType.reference(throwable == null ? currentClass.name() : throwable.name())
                    : validTypes.getFirst();
            for (int index = 1; index < validTypes.size(); index++) {
                bindingType = hierarchy.leastUpperBound(bindingType, validTypes.get(index))
                        .orElse(IrType.reference(throwable == null
                                ? currentClass.name() : throwable.name()));
            }
            targets.add(new CatchTarget(alternatives, bindingType));
        }
        return targets;
    }

    private TypeSymbol resolveCatchAlternative(TypeName sourceType, TypeSymbol throwable) {
        if (sourceType.kind() != TypeName.Kind.REFERENCE) {
            diagnostics.add(error(sourceType.span(),
                    "catch type must be a class or interface reference"));
            return null;
        }
        IrType type = resolveType(sourceType);
        if (type.isTypeParameter()) {
            diagnostics.add(error(sourceType.span(), "catch type cannot be type parameter '"
                    + sourceType.displayName() + "'"));
            return null;
        }
        if (!type.typeArguments().isEmpty()) {
            diagnostics.add(error(sourceType.span(),
                    "catch type must be a non-parameterized reifiable class"));
            return null;
        }
        TypeSymbol target = type.isReference()
                ? hierarchy.type(type.referenceName()).orElse(null) : null;
        if (target != null && throwable != null
                && !hierarchy.isSubtype(target.name(), throwable.name())) {
            diagnostics.add(error(sourceType.span(), "catch type '" + target.name()
                    + "' must extend ironwood.lang.Throwable"));
            return null;
        }
        return target;
    }

    private boolean lowerIf(IfStatement statement) {
        PatternFlow.Result patternFlow = PatternFlow.analyze(statement.condition());
        TypedValue condition = lowerExpression(statement.condition());
        IrOperand conditionOperand = requireCondition(condition, statement.condition().span(), "if");
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();

        MutableBlock thenBlock = createBlock("if.then", statement.thenBranch().span());
        MutableBlock elseBlock = createBlock("if.else",
                statement.elseBranch().map(Statement::span).orElse(statement.span()));
        MutableBlock mergeBlock = createBlock("if.merge", statement.span());
        currentBlock.terminate(new IrBranch(conditionOperand, thenBlock.label, elseBlock.label, statement.span()));

        BranchFlow thenFlow = lowerBranch(thenBlock, statement.thenBranch(), before,
                patternFlow.whenTrue());
        OwnershipSnapshot thenOwnership = snapshotOwnership();
        restoreOwnership(ownershipBefore);
        BranchFlow elseFlow;
        if (statement.elseBranch().isPresent()) {
            elseFlow = lowerBranch(elseBlock, statement.elseBranch().get(), before,
                    patternFlow.whenFalse());
        } else {
            currentBlock = elseBlock;
            environment = new LinkedHashMap<>(before);
            elseFlow = new BranchFlow(true, elseBlock, copyEnvironment(), snapshotOwnership());
        }
        OwnershipSnapshot elseOwnership = snapshotOwnership();

        List<BranchFlow> incoming = new ArrayList<>();
        if (thenFlow.reachable()) {
            thenFlow.block().terminate(new IrJump(mergeBlock.label, statement.span()));
            incoming.add(thenFlow);
        }
        if (elseFlow.reachable()) {
            elseFlow.block().terminate(new IrJump(mergeBlock.label, statement.span()));
            incoming.add(elseFlow);
        }

        currentBlock = mergeBlock;
        if (incoming.isEmpty()) {
            restoreOwnership(ownershipBefore);
            environment = before;
            mergeBlock.terminate(new IrUnreachable(statement.span()));
            return false;
        }

        List<OwnershipSnapshot> incomingOwnership = new ArrayList<>();
        if (thenFlow.reachable()) {
            incomingOwnership.add(thenOwnership);
        }
        if (elseFlow.reachable()) {
            incomingOwnership.add(elseOwnership);
        }
        mergeOwnership(ownershipBefore, incomingOwnership,
                "allocation has conflicting ownership across if branches");

        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, incoming, statement.span(), mergeBlock));
        }
        if (thenFlow.reachable() && !elseFlow.reachable()) {
            activatePatternBindings(patternFlow.whenTrue());
        } else if (!thenFlow.reachable() && elseFlow.reachable()) {
            activatePatternBindings(patternFlow.whenFalse());
        }
        return true;
    }

    private BranchFlow lowerBranch(MutableBlock block, Statement statement,
                                   LinkedHashMap<LocalSymbol, IrOperand> before) {
        return lowerBranch(block, statement, before, List.of());
    }

    private BranchFlow lowerBranch(MutableBlock block, Statement statement,
                                   LinkedHashMap<LocalSymbol, IrOperand> before,
                                   List<PatternFlow.Binding> bindings) {
        currentBlock = block;
        environment = new LinkedHashMap<>(before);
        enterScope();
        activatePatternBindings(bindings);
        boolean reachable = lowerStatement(statement);
        LinkedHashMap<LocalSymbol, IrOperand> branchEnvironment = copyEnvironment();
        exitScope();
        return new BranchFlow(reachable, currentBlock, branchEnvironment, snapshotOwnership());
    }

    private IrOperand mergeValue(LocalSymbol symbol, List<BranchFlow> incoming,
                                 SourceSpan span, MutableBlock mergeBlock) {
        IrOperand first = incoming.getFirst().environment().get(symbol);
        boolean same = incoming.stream().allMatch(flow -> flow.environment().get(symbol).equals(first));
        if (same || incoming.size() == 1) {
            return first;
        }
        IrValueReference result = newValue(symbol.type(), span);
        List<IrPhiIncoming> values = incoming.stream()
                .map(flow -> new IrPhiIncoming(flow.block().label, flow.environment().get(symbol)))
                .toList();
        mergeBlock.addPhi(new MutablePhi(result, values, span));
        List<IrOperand> sources = values.stream().map(IrPhiIncoming::value).toList();
        propagateCommonOwnedHelperBorrow(result, sources);
        List<AllocationInfo> borrowedOwners = sources.stream()
                .filter(this::isDependentBorrow)
                .map(this::allocationOf)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        boolean everySourceIsBorrowed = sources.stream().allMatch(this::isDependentBorrow);
        if (!borrowedOwners.isEmpty()
                && (!everySourceIsBorrowed || borrowedOwners.size() != 1)) {
            borrowedOwners.forEach(owner -> owner.makeUncertain(
                    "allocation has conflicting borrowed-helper ownership across control flow"));
        }
        mergeAllocationIdentity(result, sources);
        return result;
    }

    private void mergeAllocationIdentity(IrOperand result, List<IrOperand> sources) {
        propagateCommonOwnedHelperBorrow(result, sources);
        AllocationInfo first = allocationOf(sources.getFirst());
        if (first != null && sources.stream().allMatch(value -> allocationOf(value) == first)) {
            allocationsByOperand.put(result, first);
            return;
        }
        List<AllocationInfo> alternatives = sources.stream().map(this::allocationOf)
                .filter(java.util.Objects::nonNull).distinct().toList();
        // Losing a phi's exact identity must not lose its possible aliases or
        // turn a potentially dangling reference back into an unchecked value.
        boolean possiblyFreed = alternatives.stream().anyMatch(value -> value.state.mayBeFreed());
        alternatives.forEach(value -> value.blockReclamation(
                "allocation may still be observed through a merged reference"));
        if (possiblyFreed) {
            AllocationInfo merged = new AllocationInfo(controlFlowDepth);
            merged.state = AllocationState.MAYBE_FREED;
            merged.blockingReason = "merged reference may designate an allocation that was freed";
            allocations.add(merged);
            allocationsByOperand.put(result, merged);
        }
    }

    private boolean lowerSwitch(SwitchStatement statement) {
        SwitchSelection selection = lowerSwitchSelector(statement.selector());
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        MutableBlock exit = createBlock("switch.exit", statement.span());
        List<MutableBlock> groupBlocks = statement.groups().stream()
                .map(group -> createBlock("switch.group", group.span())).toList();
        List<LabeledSwitchTarget> targets = new ArrayList<>();
        for (int index = 0; index < statement.groups().size(); index++) {
            targets.add(new LabeledSwitchTarget(statement.groups().get(index).labels(),
                    groupBlocks.get(index)));
        }
        ValidatedSwitchLabels labels = validateSwitchLabels(selection, targets,
                "duplicate default label in switch statement", true);
        String defaultTarget = labels.defaultTarget() == null
                ? exit.label : labels.defaultTarget();
        DispatchEdges dispatchEdges = emitSwitchDispatch(selection, labels,
                defaultTarget, statement.span());

        enterScope();
        BreakContext switchContext = new BreakContext(exit.label, List.copyOf(finallyContexts));
        breakContexts.push(switchContext);
        BranchFlow fallthrough = null;
        for (int index = 0; index < statement.groups().size(); index++) {
            SwitchGroup group = statement.groups().get(index);
            MutableBlock groupBlock = groupBlocks.get(index);
            List<BranchFlow> incoming = new ArrayList<>(dispatchIncoming(
                    dispatchEdges, groupBlock.label, before));
            if (fallthrough != null && fallthrough.reachable()) {
                fallthrough.block().terminate(new IrJump(groupBlock.label, group.span()));
                incoming.add(fallthrough);
            }

            currentBlock = groupBlock;
            environment = mergeEnvironment(before, incoming, group.span(), groupBlock);
            boolean reachable = true;
            for (Statement child : group.statements()) {
                if (!reachable) {
                    diagnostics.add(error(child.span(), "unreachable statement"));
                    continue;
                }
                reachable = lowerStatement(child);
            }
            fallthrough = new BranchFlow(reachable, currentBlock, copyEnvironment(), snapshotOwnership());
        }
        breakContexts.pop();
        exitScope();

        List<BranchFlow> exits = new ArrayList<>(switchContext.breakFlows);
        if (fallthrough != null && fallthrough.reachable()) {
            fallthrough.block().terminate(new IrJump(exit.label, statement.span()));
            exits.add(fallthrough);
        }
        if (labels.defaultTarget() == null) {
            exits.addAll(dispatchIncoming(dispatchEdges, exit.label, before));
        }

        currentBlock = exit;
        if (exits.isEmpty()) {
            environment = before;
            exit.terminate(new IrUnreachable(statement.span()));
            return false;
        }
        mergeFlowOwnership(exits);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, exits, statement.span(), exit));
        }
        return true;
    }

    private boolean lowerModernSwitch(ModernSwitchStatement statement) {
        SwitchSelection selection = lowerSwitchSelector(statement.selector());
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        MutableBlock exit = createBlock("switch.exit", statement.span());
        List<MutableBlock> ruleBlocks = statement.rules().stream()
                .map(rule -> createBlock("switch.rule", rule.span())).toList();
        List<LabeledSwitchTarget> targets = new ArrayList<>();
        for (int index = 0; index < statement.rules().size(); index++) {
            targets.add(new LabeledSwitchTarget(statement.rules().get(index).labels(),
                    ruleBlocks.get(index)));
        }
        ValidatedSwitchLabels labels = validateSwitchLabels(selection, targets,
                "duplicate default label in switch", false);
        String defaultTarget = labels.defaultTarget() == null
                ? exit.label : labels.defaultTarget();
        DispatchEdges dispatchEdges = emitSwitchDispatch(selection, labels,
                defaultTarget, statement.span());

        BreakContext switchContext = new BreakContext(exit.label, List.copyOf(finallyContexts));
        breakContexts.push(switchContext);
        List<BranchFlow> exits = new ArrayList<>();
        enterScope();
        for (int index = 0; index < statement.rules().size(); index++) {
            SwitchRule rule = statement.rules().get(index);
            MutableBlock ruleBlock = ruleBlocks.get(index);
            List<BranchFlow> incoming = dispatchIncoming(
                    dispatchEdges, ruleBlock.label, before);
            currentBlock = ruleBlock;
            environment = mergeEnvironment(before, incoming, rule.span(), ruleBlock);
            boolean reachable;
            if (rule.body() instanceof SwitchRuleExpression expression) {
                lowerExpressionStatement(new ExpressionStatement(expression.expression(),
                        expression.span()));
                reachable = true;
            } else if (rule.body() instanceof SwitchRuleBlock block) {
                reachable = lowerBlock(block.block(), true);
            } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                lowerThrow(thrown.statement());
                reachable = false;
            } else {
                throw new IllegalStateException("unsupported switch rule body");
            }
            if (reachable) {
                BranchFlow flow = new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership());
                currentBlock.terminate(new IrJump(exit.label, rule.span()));
                exits.add(flow);
            }
        }
        exitScope();
        breakContexts.pop();
        exits.addAll(switchContext.breakFlows);
        if (labels.defaultTarget() == null) {
            exits.addAll(dispatchIncoming(dispatchEdges, exit.label, before));
        }

        currentBlock = exit;
        if (exits.isEmpty()) {
            environment = before;
            exit.terminate(new IrUnreachable(statement.span()));
            return false;
        }
        environment = mergeEnvironment(before, exits, statement.span(), exit);
        return true;
    }

    private TypedValue lowerSwitchExpression(SwitchExpression expression,
                                             Optional<IrType> expectedType) {
        SwitchSelection selection = lowerSwitchSelector(expression.selector());
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        MutableBlock merge = createBlock("switch.expression.merge", expression.span());
        MutableBlock noMatch = createBlock("switch.expression.nomatch", expression.span());
        List<MutableBlock> bodyBlocks;
        List<LabeledSwitchTarget> targets = new ArrayList<>();
        if (expression.arrowRules()) {
            bodyBlocks = expression.rules().stream()
                    .map(rule -> createBlock("switch.expression.rule", rule.span())).toList();
            for (int index = 0; index < expression.rules().size(); index++) {
                targets.add(new LabeledSwitchTarget(expression.rules().get(index).labels(),
                        bodyBlocks.get(index)));
            }
        } else {
            bodyBlocks = expression.groups().stream()
                    .map(group -> createBlock("switch.expression.group", group.span())).toList();
            for (int index = 0; index < expression.groups().size(); index++) {
                targets.add(new LabeledSwitchTarget(expression.groups().get(index).labels(),
                        bodyBlocks.get(index)));
            }
        }
        ValidatedSwitchLabels labels = validateSwitchLabels(selection, targets,
                "duplicate default label in switch", false);
        String defaultTarget = labels.defaultTarget() == null
                ? noMatch.label : labels.defaultTarget();
        DispatchEdges dispatchEdges = emitSwitchDispatch(selection, labels,
                defaultTarget, expression.span());
        boolean exhaustive = labels.defaultTarget() != null
                || selection.kind() == SwitchSelectorKind.ENUM
                && labels.enumOrdinals().size() == selection.enumType().enumConstants().size();
        if (!exhaustive) {
            diagnostics.add(error(expression.span(), selection.kind() == SwitchSelectorKind.ENUM
                    ? "switch expression must cover every enum constant or declare default"
                    : "switch expression must declare a default rule"));
        }

        SwitchExpressionContext context = new SwitchExpressionContext(merge,
                List.copyOf(finallyContexts), expectedType, before);
        switchExpressionContexts.push(context);
        BreakContext expressionBoundary = new BreakContext(null,
                List.copyOf(finallyContexts));
        breakContexts.push(expressionBoundary);
        enterScope();
        if (expression.arrowRules()) {
            lowerSwitchExpressionRules(expression, bodyBlocks, dispatchEdges, before, context);
        } else {
            lowerSwitchExpressionGroups(expression, bodyBlocks, dispatchEdges, before, context);
        }
        exitScope();
        breakContexts.pop();
        switchExpressionContexts.pop();

        currentBlock = noMatch;
        environment = new LinkedHashMap<>(before);
        restoreOwnership(dispatchEdges.ownership());
        if (labels.defaultTarget() != null || exhaustive) {
            noMatch.terminate(new IrUnreachable(expression.span()));
        } else {
            TypedValue recovery = switchRecoveryValue(expectedType, expression.span());
            context.yields.add(new YieldFlow(noMatch, copyEnvironment(), recovery,
                    expression.span(), snapshotOwnership()));
        }
        if (context.yields.isEmpty()) {
            if (expectedType.isEmpty() || expectedType.orElseThrow().equals(IrType.VOID)) {
                diagnostics.add(error(expression.span(),
                        "switch expression must have at least one result expression or yield "
                                + "when no target type is available"));
            }
            MutableBlock recovery = createBlock("switch.expression.recovery", expression.span());
            context.yields.add(new YieldFlow(recovery, new LinkedHashMap<>(before),
                    switchRecoveryValue(expectedType, expression.span()), expression.span(),
                    snapshotOwnership()));
        }
        return finishSwitchExpression(expression, context);
    }

    private void lowerSwitchExpressionRules(SwitchExpression expression,
                                            List<MutableBlock> ruleBlocks,
                                            DispatchEdges dispatchEdges,
                                            LinkedHashMap<LocalSymbol, IrOperand> before,
                                            SwitchExpressionContext context) {
        for (int index = 0; index < expression.rules().size(); index++) {
            SwitchRule rule = expression.rules().get(index);
            MutableBlock ruleBlock = ruleBlocks.get(index);
            List<BranchFlow> incoming = dispatchIncoming(
                    dispatchEdges, ruleBlock.label, before);
            currentBlock = ruleBlock;
            environment = mergeEnvironment(before, incoming, rule.span(), ruleBlock);
            if (rule.body() instanceof SwitchRuleExpression result) {
                TypedValue value = lowerExpression(result.expression(), context.expectedType);
                context.yields.add(new YieldFlow(currentBlock, copyEnvironment(), value,
                        result.expression().span(), snapshotOwnership()));
            } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                lowerThrow(thrown.statement());
            } else if (rule.body() instanceof SwitchRuleBlock block) {
                boolean reachable = lowerBlock(block.block(), true);
                if (reachable) {
                    diagnostics.add(error(block.span(),
                            "switch expression rule block must yield a value or complete abruptly"));
                    context.yields.add(new YieldFlow(currentBlock, copyEnvironment(),
                            switchRecoveryValue(context.expectedType, block.span()), block.span(),
                            snapshotOwnership()));
                }
            }
        }
    }

    private void lowerSwitchExpressionGroups(SwitchExpression expression,
                                             List<MutableBlock> groupBlocks,
                                             DispatchEdges dispatchEdges,
                                             LinkedHashMap<LocalSymbol, IrOperand> before,
                                             SwitchExpressionContext context) {
        BranchFlow fallthrough = null;
        for (int index = 0; index < expression.groups().size(); index++) {
            SwitchGroup group = expression.groups().get(index);
            MutableBlock groupBlock = groupBlocks.get(index);
            List<BranchFlow> incoming = new ArrayList<>(dispatchIncoming(
                    dispatchEdges, groupBlock.label, before));
            if (fallthrough != null && fallthrough.reachable()) {
                fallthrough.block().terminate(new IrJump(groupBlock.label, group.span()));
                incoming.add(fallthrough);
            }
            currentBlock = groupBlock;
            environment = mergeEnvironment(before, incoming, group.span(), groupBlock);
            boolean reachable = true;
            for (Statement child : group.statements()) {
                if (!reachable) {
                    diagnostics.add(error(child.span(), "unreachable statement"));
                    continue;
                }
                reachable = lowerStatement(child);
            }
            fallthrough = new BranchFlow(reachable, currentBlock, copyEnvironment(), snapshotOwnership());
        }
        if (fallthrough != null && fallthrough.reachable()) {
            diagnostics.add(error(expression.span(),
                    "switch expression may complete without yielding a value"));
            context.yields.add(new YieldFlow(fallthrough.block(), fallthrough.environment(),
                    switchRecoveryValue(context.expectedType, expression.span()), expression.span(),
                    fallthrough.ownership()));
        }
    }

    private boolean lowerYield(YieldStatement statement) {
        SwitchExpressionContext context = switchExpressionContexts.peek();
        if (context == null) {
            lowerExpression(statement.value());
            diagnostics.add(error(statement.span(),
                    "yield is only valid inside a switch expression"));
            currentBlock.terminate(new IrUnreachable(statement.span()));
            return false;
        }
        TypedValue value = lowerExpression(statement.value(), context.expectedType);
        List<FinallyContext> current = List.copyOf(finallyContexts);
        int cleanupCount = current.size() - context.targetFinallyContexts.size();
        if (cleanupCount < 0 || !current.subList(cleanupCount, current.size())
                .equals(context.targetFinallyContexts)) {
            diagnostics.add(error(statement.span(),
                    "cannot resolve yield cleanup path across active finally blocks"));
            context.yields.add(new YieldFlow(currentBlock, copyEnvironment(), value,
                    statement.value().span(), snapshotOwnership()));
            return false;
        }
        AllocationInfo retained = allocationOf(value.operand());
        pendingYieldAllocations.add(retained);
        try {
            completeYieldThrough(current.subList(0, cleanupCount), 0, context, value,
                    statement.value().span());
        } finally {
            pendingYieldAllocations.removeLast();
        }
        return false;
    }

    private void completeYieldThrough(List<FinallyContext> pending, int index,
                                      SwitchExpressionContext context, TypedValue value,
                                      SourceSpan span) {
        if (index >= pending.size()) {
            context.yields.add(new YieldFlow(currentBlock, copyEnvironment(), value, span,
                    snapshotOwnership()));
            return;
        }
        FinallyContext cleanup = pending.get(index);
        List<ExceptionRegion> savedExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> savedFinally = List.copyOf(finallyContexts);
        LinkedHashMap<LocalSymbol, IrOperand> savedEnvironment = copyEnvironment();
        OwnershipSnapshot savedOwnership = snapshotOwnership();
        restoreDeque(exceptionRegions, cleanup.outerExceptionRegions());
        restoreDeque(finallyContexts, cleanup.outerFinallyContexts());
        try {
            if (lowerBlock(cleanup.body(), true)) {
                completeYieldThrough(pending, index + 1, context, value, span);
            }
        } finally {
            environment = savedEnvironment;
            restoreOwnership(savedOwnership);
            restoreDeque(exceptionRegions, savedExceptions);
            restoreDeque(finallyContexts, savedFinally);
        }
    }

    private TypedValue finishSwitchExpression(SwitchExpression expression,
                                              SwitchExpressionContext context) {
        IrType resultType = switchExpressionType(context.yields, context.expectedType,
                expression.span());
        List<BranchFlow> incoming = new ArrayList<>();
        List<IrPhiIncoming> resultIncoming = new ArrayList<>();
        for (YieldFlow flow : context.yields) {
            currentBlock = flow.block();
            environment = new LinkedHashMap<>(flow.environment());
            restoreOwnership(flow.ownership());
            TypedValue value = flow.value();
            boolean convertible = isAssignmentConvertible(resultType, value);
            if (!convertible) {
                diagnostics.add(error(flow.span(), "switch expression result of type "
                        + typeName(value.type()) + " is not compatible with merged type "
                        + typeName(resultType)));
            }
            IrOperand operand = convertible
                    ? assignmentValue(value, resultType, flow.span(), "switch expression result")
                    : defaultValue(resultType, flow.span());
            currentBlock.terminate(new IrJump(context.merge.label, flow.span()));
            incoming.add(new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership()));
            resultIncoming.add(new IrPhiIncoming(currentBlock.label, operand));
        }
        currentBlock = context.merge;
        environment = mergeEnvironment(context.before, incoming, expression.span(), context.merge);
        IrValueReference result = newValue(resultType, expression.span());
        context.merge.addPhi(new MutablePhi(result, resultIncoming, expression.span()));
        mergeAllocationIdentity(result, resultIncoming.stream().map(IrPhiIncoming::value).toList());
        return new TypedValue(resultType, result);
    }

    private IrType switchExpressionType(List<YieldFlow> yields,
                                        Optional<IrType> expectedType,
                                        SourceSpan span) {
        if (expectedType.isPresent() && !expectedType.orElseThrow().equals(IrType.VOID)
                && yields.stream().allMatch(flow ->
                isAssignmentConvertible(expectedType.orElseThrow(), flow.value()))) {
            return expectedType.orElseThrow();
        }
        IrType result = yields.getFirst().value().type();
        for (int index = 1; index < yields.size(); index++) {
            IrType next = yields.get(index).value().type();
            if (result.equals(next)) {
                continue;
            }
            if (result.isNumeric() && next.isNumeric()) {
                result = PrimitiveConversions.binaryPromotion(result, next);
            } else if (result.equals(IrType.NULL) && next.isReference()) {
                result = next;
            } else if (next.equals(IrType.NULL) && result.isReference()) {
                continue;
            } else if (result.isReference() && next.isReference()) {
                IrType left = result;
                result = hierarchy.leastUpperBound(result, next).orElseGet(() -> {
                    diagnostics.add(error(span,
                            "switch expression result branches have incompatible types "
                                    + typeName(left) + " and " + typeName(next)));
                    return left;
                });
            } else {
                diagnostics.add(error(span,
                        "switch expression result branches have incompatible types "
                                + typeName(result) + " and " + typeName(next)));
            }
        }
        if (result.equals(IrType.VOID)) {
            diagnostics.add(error(span, "switch expression results cannot have type void"));
            return IrType.I32;
        }
        return result.equals(IrType.NULL)
                ? expectedType.filter(IrType::isReference)
                .orElse(IrType.reference("ironwood.lang.Object")) : result;
    }

    private TypedValue switchRecoveryValue(Optional<IrType> expectedType, SourceSpan span) {
        IrType type = expectedType.filter(value -> !value.equals(IrType.VOID))
                .orElse(IrType.I32);
        return new TypedValue(type, defaultValue(type, span));
    }

    private SwitchSelection lowerSwitchSelector(Expression selectorExpression) {
        TypedValue selected = lowerExpression(selectorExpression);
        IrType selectorType = selected.type();
        TypeSymbol enumType = selectorType.isNominalReference()
                ? hierarchy.type(selectorType.referenceName()).orElse(null) : null;
        if (enumType != null && !enumType.isEnum()) {
            enumType = null;
        }
        SwitchSelectorKind kind;
        if (isSwitchSelectorType(selectorType)) {
            kind = SwitchSelectorKind.INTEGRAL;
        } else if (enumType != null) {
            kind = SwitchSelectorKind.ENUM;
        } else if (selectorType.equals(STRING_TYPE)) {
            kind = SwitchSelectorKind.STRING;
        } else {
            diagnostics.add(error(selectorExpression.span(),
                    "switch selector must have type byte, short, char, or int, "
                            + "an enum, or ironwood.lang.String, not " + typeName(selectorType)));
            kind = SwitchSelectorKind.INTEGRAL;
            selectorType = IrType.I32;
        }
        IrOperand operand = kind == SwitchSelectorKind.INTEGRAL
                && !isSwitchSelectorType(selected.type())
                ? new IrConstant(IrType.I32, 0, selectorExpression.span())
                : requireValue(selected, selected.type(), selectorExpression.span(),
                "switch selector");
        return new SwitchSelection(kind, selectorType, operand, enumType, currentBlock);
    }

    private ValidatedSwitchLabels validateSwitchLabels(
            SwitchSelection selection, List<LabeledSwitchTarget> targets,
            String duplicateDefaultMessage, boolean classicStatement) {
        List<ValidatedSwitchCase> cases = new ArrayList<>();
        Map<BigInteger, SwitchLabel> integralValues = new LinkedHashMap<>();
        Map<String, SwitchLabel> stringValues = new LinkedHashMap<>();
        Set<Integer> enumOrdinals = new LinkedHashSet<>();
        SwitchLabel defaultLabel = null;
        SwitchLabel nullLabel = null;
        String defaultTarget = null;
        String nullTarget = null;
        for (LabeledSwitchTarget target : targets) {
            for (SwitchLabel label : target.labels()) {
                if (label.isDefault()) {
                    if (defaultLabel != null) {
                        diagnostics.add(error(label.span(), duplicateDefaultMessage));
                    } else {
                        defaultLabel = label;
                        defaultTarget = target.block().label;
                    }
                    continue;
                }
                Expression valueExpression = label.value().orElseThrow();
                if (valueExpression instanceof NullLiteralExpression) {
                    if (selection.kind() != SwitchSelectorKind.ENUM
                            && selection.kind() != SwitchSelectorKind.STRING) {
                        diagnostics.add(error(valueExpression.span(),
                                "case null requires an enum or ironwood.lang.String selector"));
                    } else if (nullLabel != null) {
                        diagnostics.add(error(label.span(), "duplicate case null label"));
                    } else {
                        nullLabel = label;
                        nullTarget = target.block().label;
                    }
                    continue;
                }
                if (selection.kind() == SwitchSelectorKind.STRING) {
                    CompileTimeValue constant;
                    if (valueExpression instanceof FieldAccessExpression access) {
                        ClassFieldResolution qualified = resolveClassField(access);
                        constant = qualified.classQualifier()
                                ? constantFieldValue(qualified.field())
                                : compileTimeValue(valueExpression);
                    } else {
                        constant = compileTimeValue(valueExpression);
                    }
                    if (constant == null || !constant.type().equals(STRING_TYPE)) {
                        diagnostics.add(error(valueExpression.span(),
                                "String case label must be a compile-time String constant expression"));
                        continue;
                    }
                    String value = (String) constant.value();
                    if (stringValues.putIfAbsent(value, label) != null) {
                        diagnostics.add(error(label.span(),
                                "duplicate String case label value '" + value + "'"));
                        continue;
                    }
                    cases.add(ValidatedSwitchCase.string(value, target.block().label,
                            label.span()));
                    continue;
                }
                if (selection.kind() == SwitchSelectorKind.ENUM) {
                    CaseConstant constant = evaluateEnumCaseConstant(valueExpression,
                            selection.enumType());
                    if (constant == null) {
                        diagnostics.add(error(valueExpression.span(),
                                "enum case label must be an unqualified constant of '"
                                        + selection.enumType().sourceName() + "'"));
                        continue;
                    }
                    int ordinal = constant.value().intValue();
                    if (!enumOrdinals.add(ordinal)) {
                        diagnostics.add(error(label.span(), classicStatement
                                ? "duplicate case label value " + ordinal
                                : "duplicate enum case label ordinal " + ordinal));
                        continue;
                    }
                    cases.add(ValidatedSwitchCase.integral(constant.value(),
                            target.block().label, label.span()));
                    continue;
                }
                CaseConstant constant = evaluateCaseConstant(valueExpression);
                if (constant == null) {
                    diagnostics.add(error(valueExpression.span(),
                            "case label must be a compile-time integral constant expression"));
                    continue;
                }
                TypedValue typed = new TypedValue(constant.type(),
                        new IrConstant(constant.type(), constant.value(), valueExpression.span()),
                        constant.value());
                if (!isAssignmentConvertible(selection.selectorType(), typed)) {
                    diagnostics.add(error(valueExpression.span(), "case label constant of type "
                            + typeName(constant.type()) + " with value " + constant.value()
                            + " is not compatible with switch selector type "
                            + typeName(selection.selectorType())));
                    continue;
                }
                BigInteger converted = wrapIntegral(constant.value(), selection.selectorType());
                if (integralValues.putIfAbsent(converted, label) != null) {
                    diagnostics.add(error(label.span(),
                            "duplicate case label value " + converted));
                    continue;
                }
                cases.add(ValidatedSwitchCase.integral(converted,
                        target.block().label, label.span()));
            }
        }
        return new ValidatedSwitchLabels(cases, defaultTarget, nullTarget,
                Set.copyOf(enumOrdinals));
    }

    private DispatchEdges emitSwitchDispatch(SwitchSelection selection,
                                             ValidatedSwitchLabels labels,
                                             String defaultTarget,
                                             SourceSpan span) {
        Map<String, List<MutableBlock>> edges = new LinkedHashMap<>();
        currentBlock = selection.dispatchBlock();
        IrOperand selector = selection.operand();
        if (selection.kind() == SwitchSelectorKind.ENUM
                || selection.kind() == SwitchSelectorKind.STRING) {
            if (labels.nullTarget() != null) {
                MutableBlock nonNull = createBlock("switch.nonnull", span);
                IrValueReference isNull = newValue(IrType.I1, span);
                currentBlock.addInstruction(new IrBinaryInstruction(isNull,
                        IrBinaryOperator.EQUAL, selector,
                        new IrNull(selector.type(), span), span));
                MutableBlock nullPredecessor = currentBlock;
                currentBlock.terminate(new IrBranch(isNull, labels.nullTarget(),
                        nonNull.label, span));
                addDispatchEdge(edges, labels.nullTarget(), nullPredecessor);
                addDispatchEdge(edges, nonNull.label, nullPredecessor);
                currentBlock = nonNull;
            } else {
                emitNullCheck(selector, span);
            }
        }
        if (selection.kind() == SwitchSelectorKind.ENUM) {
            FieldSymbol ordinal = selection.enumType().declaredFields()
                    .get(TypeSymbol.ENUM_ORDINAL_FIELD);
            IrValueReference ordinalValue = newValue(IrType.I32, span);
            currentBlock.addInstruction(new IrFieldLoadInstruction(ordinalValue,
                    selector, ordinal.irField(), span));
            emitIntegralSwitch(ordinalValue, labels, defaultTarget, edges, span);
        } else if (selection.kind() == SwitchSelectorKind.STRING) {
            List<ValidatedSwitchCase> stringCases = labels.cases().stream()
                    .filter(value -> value.stringValue() != null).toList();
            for (ValidatedSwitchCase stringCase : stringCases) {
                MutableBlock next = createBlock("switch.string.next", stringCase.span());
                IrValueReference equal = newValue(IrType.I1, stringCase.span());
                IrOperand constant = stringPool.intern(stringCase.stringValue(),
                        stringCase.span());
                IrOperand objectConstant = convertReference(constant,
                        IrType.reference("ironwood.lang.Object"), stringCase.span());
                currentBlock.addInstruction(new IrStringEqualsInstruction(equal,
                        selector, objectConstant, stringCase.span()));
                MutableBlock predecessor = currentBlock;
                currentBlock.terminate(new IrBranch(equal, stringCase.target(),
                        next.label, stringCase.span()));
                addDispatchEdge(edges, stringCase.target(), predecessor);
                addDispatchEdge(edges, next.label, predecessor);
                currentBlock = next;
            }
            MutableBlock predecessor = currentBlock;
            currentBlock.terminate(new IrJump(defaultTarget, span));
            addDispatchEdge(edges, defaultTarget, predecessor);
        } else {
            emitIntegralSwitch(selector, labels, defaultTarget, edges, span);
        }
        return new DispatchEdges(edges, snapshotOwnership());
    }

    private void emitIntegralSwitch(IrOperand selector,
                                    ValidatedSwitchLabels labels,
                                    String defaultTarget,
                                    Map<String, List<MutableBlock>> edges,
                                    SourceSpan span) {
        List<IrSwitchCase> cases = labels.cases().stream()
                .filter(value -> value.integralValue() != null)
                .map(value -> new IrSwitchCase(new IrConstant(selector.type(),
                        value.integralValue(), value.span()), value.target(), value.span()))
                .toList();
        MutableBlock predecessor = currentBlock;
        currentBlock.terminate(new IrSwitchTerminator(selector, cases, defaultTarget, span));
        cases.forEach(value -> addDispatchEdge(edges, value.target(), predecessor));
        addDispatchEdge(edges, defaultTarget, predecessor);
    }

    private static void addDispatchEdge(Map<String, List<MutableBlock>> edges,
                                        String target, MutableBlock predecessor) {
        List<MutableBlock> values = edges.computeIfAbsent(target,
                ignored -> new ArrayList<>());
        if (!values.contains(predecessor)) {
            values.add(predecessor);
        }
    }

    private List<BranchFlow> dispatchIncoming(DispatchEdges edges, String target,
                                              LinkedHashMap<LocalSymbol, IrOperand> before) {
        return edges.predecessors().getOrDefault(target, List.of()).stream()
                .map(block -> new BranchFlow(true, block, new LinkedHashMap<>(before),
                        edges.ownership()))
                .toList();
    }

    private LinkedHashMap<LocalSymbol, IrOperand> mergeEnvironment(
            LinkedHashMap<LocalSymbol, IrOperand> before,
            List<BranchFlow> incoming, SourceSpan span, MutableBlock block) {
        mergeFlowOwnership(incoming);
        LinkedHashMap<LocalSymbol, IrOperand> merged = new LinkedHashMap<>();
        if (incoming.isEmpty()) {
            merged.putAll(before);
            return merged;
        }
        for (LocalSymbol symbol : before.keySet()) {
            merged.put(symbol, mergeValue(symbol, incoming, span, block));
        }
        return merged;
    }

    private static boolean isSwitchSelectorType(IrType type) {
        return type.equals(IrType.I8) || type.equals(IrType.I16)
                || type.equals(IrType.U16) || type.equals(IrType.I32);
    }

    private CaseConstant evaluateEnumCaseConstant(Expression expression, TypeSymbol enumType) {
        if (!(expression instanceof NameExpression name)) {
            return null;
        }
        TypeSymbol.EnumConstantSymbol constant = enumType.enumConstant(name.name()).orElse(null);
        if (constant == null) {
            return null;
        }
        return new CaseConstant(IrType.I32, BigInteger.valueOf(constant.ordinal()));
    }

    private CaseConstant evaluateCaseConstant(Expression expression) {
        if (expression instanceof IntegerLiteralExpression literal) {
            return caseIntegerLiteral(literal, false);
        }
        if (expression instanceof CharacterLiteralExpression literal) {
            return new CaseConstant(IrType.U16, BigInteger.valueOf(literal.value()));
        }
        if (expression instanceof BooleanLiteralExpression literal) {
            return new CaseConstant(IrType.I1,
                    literal.value() ? BigInteger.ONE : BigInteger.ZERO);
        }
        if (expression instanceof NameExpression name) {
            LocalSymbol local = resolve(name.name());
            if (local != null) {
                return localConstants.get(local);
            }
            FieldSymbol field = resolveField(currentClass.selfType(), name.name(), name.span());
            if (field == null) {
                field = resolveLexicalConstantField(name.name(), name.span());
            }
            if (field == null) {
                field = resolveStaticImportedField(name.name(), name.span(), true);
            }
            return caseFieldConstant(field, name.span(), currentClass.name());
        }
        if (expression instanceof FieldAccessExpression access) {
            ClassFieldResolution qualified = resolveClassField(access);
            if (!qualified.classQualifier()) {
                return null;
            }
            return caseFieldConstant(qualified.field(), access.fieldNameSpan(),
                    qualified.qualifier() == null ? null : qualified.qualifier().name());
        }
        if (expression instanceof UnaryExpression unary) {
            if (unary.operator() == ironwood.compiler.ast.UnaryOperator.NEGATE
                    && unary.operand() instanceof IntegerLiteralExpression literal) {
                CaseConstant minimum = caseIntegerLiteral(literal, true);
                if (minimum != null) {
                    return minimum;
                }
            }
            CaseConstant operand = evaluateCaseConstant(unary.operand());
            if (operand == null) {
                return null;
            }
            if (unary.operator() == ironwood.compiler.ast.UnaryOperator.NOT) {
                return operand.type().equals(IrType.I1)
                        ? new CaseConstant(IrType.I1,
                        operand.value().signum() == 0 ? BigInteger.ONE : BigInteger.ZERO)
                        : null;
            }
            if (!operand.type().isIntegral()) {
                return null;
            }
            IrType promoted = PrimitiveConversions.unaryPromotion(operand.type());
            BigInteger value = switch (unary.operator()) {
                case POSITIVE -> operand.value();
                case NEGATE -> operand.value().negate();
                case BITWISE_COMPLEMENT -> operand.value().not();
                case NOT -> throw new IllegalStateException();
            };
            return new CaseConstant(promoted, wrapIntegral(value, promoted));
        }
        if (expression instanceof BinaryExpression binary) {
            CaseConstant left = evaluateCaseConstant(binary.left());
            CaseConstant right = evaluateCaseConstant(binary.right());
            return left == null || right == null
                    ? null : evaluateCaseBinary(binary.operator(), left, right);
        }
        if (expression instanceof ConditionalExpression conditional) {
            CaseConstant condition = evaluateCaseConstant(conditional.condition());
            CaseConstant whenTrue = evaluateCaseConstant(conditional.whenTrue());
            CaseConstant whenFalse = evaluateCaseConstant(conditional.whenFalse());
            if (condition == null || !condition.type().equals(IrType.I1)
                    || whenTrue == null || whenFalse == null) {
                return null;
            }
            IrType resultType;
            if (whenTrue.type().equals(whenFalse.type())) {
                resultType = whenTrue.type();
            } else if (whenTrue.type().isIntegral() && whenFalse.type().isIntegral()) {
                resultType = PrimitiveConversions.binaryPromotion(
                        whenTrue.type(), whenFalse.type());
            } else {
                return null;
            }
            CaseConstant chosen = condition.value().signum() == 0 ? whenFalse : whenTrue;
            return convertCaseConstant(chosen, resultType);
        }
        if (expression instanceof CastExpression cast) {
            IrType target = resolveType(cast.targetType());
            CaseConstant operand = evaluateCaseConstant(cast.operand());
            if (operand == null) {
                return null;
            }
            if (target.equals(IrType.I1) && operand.type().equals(IrType.I1)) {
                return operand;
            }
            return target.isIntegral() && operand.type().isIntegral()
                    ? convertCaseConstant(operand, target) : null;
        }
        return null;
    }

    private FieldSymbol resolveLexicalConstantField(String name, SourceSpan span) {
        for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
             lexical != null; lexical = lexical.enclosingType().orElse(null)) {
            FieldSymbol field = resolveField(lexical.selfType(), name, span);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private CaseConstant caseFieldConstant(FieldSymbol field, SourceSpan span,
                                            String receiverType) {
        if (field == null || !field.isStatic() || !field.isFinal()
                || field.constantValue() == null
                || !(field.type().isIntegral() || field.type().equals(IrType.I1))) {
            return null;
        }
        if (!isAccessible(field.accessModifier(), field.ownerClass(), receiverType, false)) {
            diagnostics.add(error(span, memberAccessMessage("field",
                    field.declaration().name(), field.accessModifier(), field.ownerClass())));
            return null;
        }
        return new CaseConstant(field.type(),
                BigInteger.valueOf(field.constantValue().value().longValue()));
    }

    private CaseConstant caseIntegerLiteral(IntegerLiteralExpression literal,
                                            boolean negated) {
        IntegerLiteralDecoder.Result result = IntegerLiteralDecoder.decode(literal.text(), negated);
        return result.successful() ? new CaseConstant(result.type(), result.value()) : null;
    }

    private CaseConstant evaluateCaseBinary(BinaryOperator operator,
                                            CaseConstant left,
                                            CaseConstant right) {
        if (operator == BinaryOperator.LOGICAL_AND || operator == BinaryOperator.LOGICAL_OR) {
            if (!left.type().equals(IrType.I1) || !right.type().equals(IrType.I1)) {
                return null;
            }
            boolean leftValue = left.value().signum() != 0;
            boolean rightValue = right.value().signum() != 0;
            boolean result = operator == BinaryOperator.LOGICAL_AND
                    ? leftValue && rightValue : leftValue || rightValue;
            return new CaseConstant(IrType.I1, result ? BigInteger.ONE : BigInteger.ZERO);
        }
        if ((operator == BinaryOperator.BITWISE_AND || operator == BinaryOperator.BITWISE_XOR
                || operator == BinaryOperator.BITWISE_OR)
                && left.type().equals(IrType.I1) && right.type().equals(IrType.I1)) {
            boolean l = left.value().signum() != 0;
            boolean r = right.value().signum() != 0;
            boolean result = switch (operator) {
                case BITWISE_AND -> l & r;
                case BITWISE_XOR -> l ^ r;
                case BITWISE_OR -> l | r;
                default -> throw new IllegalStateException();
            };
            return new CaseConstant(IrType.I1, result ? BigInteger.ONE : BigInteger.ZERO);
        }
        if (!left.type().isIntegral() || !right.type().isIntegral()) {
            return null;
        }
        if (operator == BinaryOperator.SHIFT_LEFT || operator == BinaryOperator.SHIFT_RIGHT
                || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT) {
            IrType resultType = PrimitiveConversions.unaryPromotion(left.type());
            BigInteger value = evaluateIntegralBinary(operator,
                    constantTypedValue(left), constantTypedValue(right), resultType);
            return value == null ? null : new CaseConstant(resultType, value);
        }
        IrType promoted = PrimitiveConversions.binaryPromotion(left.type(), right.type());
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL
                || operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL
                || operator == BinaryOperator.GREATER || operator == BinaryOperator.GREATER_EQUAL) {
            int comparison = left.value().compareTo(right.value());
            boolean result = switch (operator) {
                case EQUAL -> comparison == 0;
                case NOT_EQUAL -> comparison != 0;
                case LESS -> comparison < 0;
                case LESS_EQUAL -> comparison <= 0;
                case GREATER -> comparison > 0;
                case GREATER_EQUAL -> comparison >= 0;
                default -> throw new IllegalStateException();
            };
            return new CaseConstant(IrType.I1, result ? BigInteger.ONE : BigInteger.ZERO);
        }
        BigInteger value = evaluateIntegralBinary(operator,
                constantTypedValue(left), constantTypedValue(right), promoted);
        return value == null ? null : new CaseConstant(promoted, value);
    }

    private static TypedValue constantTypedValue(CaseConstant constant) {
        return new TypedValue(constant.type(), null, constant.value());
    }

    private static CaseConstant convertCaseConstant(CaseConstant constant, IrType target) {
        return new CaseConstant(target, target.equals(IrType.I1)
                ? (constant.value().signum() == 0 ? BigInteger.ZERO : BigInteger.ONE)
                : wrapIntegral(constant.value(), target));
    }

    private static boolean isLiteralTrue(Expression expression) {
        return expression instanceof BooleanLiteralExpression literal && literal.value();
    }

    private boolean lowerWhile(WhileStatement statement, LabeledStatement label) {
        boolean alwaysTrue = isLiteralTrue(statement.condition());
        PatternFlow.Result patternFlow = PatternFlow.analyze(statement.condition());
        MutableBlock preheader = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot loopOwnership = snapshotOwnership();
        int firstReclamation = reclamations.size();
        MutableBlock header = createBlock("while.header", statement.condition().span());
        MutableBlock body = createBlock("while.body", statement.body().span());
        MutableBlock exit = createBlock("while.exit", statement.span());
        preheader.terminate(new IrJump(header.label, statement.span()));

        LinkedHashMap<LocalSymbol, MutablePhi> loopPhis = new LinkedHashMap<>();
        LinkedHashMap<LocalSymbol, IrOperand> headerEnvironment = new LinkedHashMap<>();
        for (Map.Entry<LocalSymbol, IrOperand> entry : before.entrySet()) {
            IrValueReference result = newValue(entry.getKey().type(), statement.span());
            MutablePhi phi = new MutablePhi(result,
                    List.of(new IrPhiIncoming(preheader.label, entry.getValue())), statement.span());
            header.addPhi(phi);
            loopPhis.put(entry.getKey(), phi);
            headerEnvironment.put(entry.getKey(), result);
            AllocationInfo allocation = allocationOf(entry.getValue());
            if (allocation != null) {
                allocationsByOperand.put(result, allocation);
                propagateOwnedHelperBorrow(result, entry.getValue());
            }
        }

        currentBlock = header;
        environment = headerEnvironment;
        TypedValue condition = lowerExpression(statement.condition());
        IrOperand conditionOperand = requireCondition(condition, statement.condition().span(), "while");
        MutableBlock conditionEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> conditionEnvironment = copyEnvironment();
        OwnershipSnapshot conditionOwnership = snapshotOwnership();
        conditionEnd.terminate(alwaysTrue
                ? new IrJump(body.label, statement.span())
                : new IrBranch(conditionOperand, body.label, exit.label, statement.span()));

        LoopContext loop = new LoopContext(exit.label, header.label, List.copyOf(finallyContexts));
        breakContexts.push(loop);
        loopContexts.push(loop);
        if (label != null) {
            pushLabeledContext(label, loop, loop);
        }
        BranchFlow bodyFlow = lowerBranch(body, statement.body(), conditionEnvironment,
                patternFlow.whenTrue());
        if (label != null) {
            labeledContexts.pop();
        }
        loopContexts.pop();
        breakContexts.pop();
        if (bodyFlow.reachable()) {
            bodyFlow.block().terminate(new IrJump(header.label, statement.span()));
            loop.continueFlows.add(bodyFlow);
        }
        for (BranchFlow flow : loop.continueFlows) {
            for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
                entry.getValue().addIncoming(new IrPhiIncoming(
                        flow.block().label, flow.environment().get(entry.getKey())));
            }
        }
        validateLoopBackEdges(loopOwnership, before, loop.continueFlows, firstReclamation);
        List<BranchFlow> backEdges = loop.continueFlows;
        for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
            AllocationInfo initial = allocationOf(before.get(entry.getKey()));
            if (initial != null && loop.continueFlows.stream()
                    .anyMatch(flow -> allocationOf(flow.environment().get(entry.getKey())) != initial)) {
                allocationsByOperand.remove(entry.getValue().result);
            }
        }

        currentBlock = exit;
        List<BranchFlow> exits = new ArrayList<>();
        if (!alwaysTrue) {
            exits.add(new BranchFlow(true, conditionEnd, conditionEnvironment, conditionOwnership));
        }
        exits.addAll(loop.breakFlows);
        if (exits.isEmpty()) {
            exit.terminate(new IrUnreachable(statement.span()));
            environment = before;
            return false;
        }
        mergeLoopOwnership(exits, backEdges);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, exits, statement.span(), exit));
        }
        if (loop.breakFlows.isEmpty()) {
            activatePatternBindings(patternFlow.whenFalse());
        }
        return true;
    }

    private boolean lowerDoWhile(DoWhileStatement statement, LabeledStatement label) {
        boolean alwaysTrue = isLiteralTrue(statement.condition());
        MutableBlock preheader = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot loopOwnership = snapshotOwnership();
        int firstReclamation = reclamations.size();
        MutableBlock body = createBlock("do.body", statement.body().span());
        MutableBlock conditionBlock = createBlock("do.condition", statement.condition().span());
        MutableBlock exit = createBlock("do.exit", statement.span());
        preheader.terminate(new IrJump(body.label, statement.span()));

        LinkedHashMap<LocalSymbol, MutablePhi> loopPhis = new LinkedHashMap<>();
        LinkedHashMap<LocalSymbol, IrOperand> bodyEnvironment = new LinkedHashMap<>();
        for (Map.Entry<LocalSymbol, IrOperand> entry : before.entrySet()) {
            IrValueReference result = newValue(entry.getKey().type(), statement.span());
            MutablePhi phi = new MutablePhi(result,
                    List.of(new IrPhiIncoming(preheader.label, entry.getValue())), statement.span());
            body.addPhi(phi);
            loopPhis.put(entry.getKey(), phi);
            bodyEnvironment.put(entry.getKey(), result);
            AllocationInfo allocation = allocationOf(entry.getValue());
            if (allocation != null) {
                allocationsByOperand.put(result, allocation);
                propagateOwnedHelperBorrow(result, entry.getValue());
            }
        }

        LoopContext loop = new LoopContext(exit.label, conditionBlock.label,
                List.copyOf(finallyContexts));
        breakContexts.push(loop);
        loopContexts.push(loop);
        if (label != null) {
            pushLabeledContext(label, loop, loop);
        }
        BranchFlow bodyFlow = lowerBranch(body, statement.body(), bodyEnvironment);
        if (label != null) {
            labeledContexts.pop();
        }
        loopContexts.pop();
        breakContexts.pop();
        if (bodyFlow.reachable()) {
            bodyFlow.block().terminate(new IrJump(conditionBlock.label, statement.span()));
            loop.continueFlows.add(bodyFlow);
        }

        PatternFlow.Result patternFlow = PatternFlow.analyze(statement.condition());
        BranchFlow conditionFlow = null;
        List<BranchFlow> backEdges = new ArrayList<>();
        if (loop.continueFlows.isEmpty()) {
            conditionBlock.terminate(new IrUnreachable(statement.span()));
        } else {
            currentBlock = conditionBlock;
            mergeFlowOwnership(loop.continueFlows);
            environment = new LinkedHashMap<>();
            for (LocalSymbol symbol : before.keySet()) {
                environment.put(symbol, mergeValue(symbol, loop.continueFlows,
                        statement.span(), conditionBlock));
            }
            TypedValue condition = lowerExpression(statement.condition());
            IrOperand conditionOperand = requireCondition(condition, statement.condition().span(),
                    "do-while");
            MutableBlock conditionEnd = currentBlock;
            LinkedHashMap<LocalSymbol, IrOperand> conditionEnvironment = copyEnvironment();
            OwnershipSnapshot conditionOwnership = snapshotOwnership();
            conditionEnd.terminate(alwaysTrue
                    ? new IrJump(body.label, statement.span())
                    : new IrBranch(conditionOperand, body.label, exit.label, statement.span()));
            conditionFlow = new BranchFlow(true, conditionEnd, conditionEnvironment, conditionOwnership);
            if (!(conditionOperand instanceof IrConstant constant && constant.value().intValue() == 0)) {
                backEdges.add(conditionFlow);
            }
            for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
                entry.getValue().addIncoming(new IrPhiIncoming(conditionEnd.label,
                        conditionEnvironment.get(entry.getKey())));
            }
        }

        validateLoopBackEdges(loopOwnership, before, backEdges, firstReclamation);
        for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
            AllocationInfo initial = allocationOf(before.get(entry.getKey()));
            if (initial != null && conditionFlow != null
                    && allocationOf(conditionFlow.environment().get(entry.getKey())) != initial) {
                allocationsByOperand.remove(entry.getValue().result);
            }
        }

        currentBlock = exit;
        List<BranchFlow> exits = new ArrayList<>();
        if (conditionFlow != null && !alwaysTrue) {
            exits.add(conditionFlow);
        }
        exits.addAll(loop.breakFlows);
        if (exits.isEmpty()) {
            exit.terminate(new IrUnreachable(statement.span()));
            environment = before;
            return false;
        }
        mergeFlowOwnership(exits);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, exits, statement.span(), exit));
        }
        if (conditionFlow != null && loop.breakFlows.isEmpty()) {
            activatePatternBindings(patternFlow.whenFalse());
        }
        return true;
    }

    private boolean lowerFor(ForStatement statement, LabeledStatement label) {
        enterScope();
        statement.initializer().ifPresent(this::lowerStatement);
        MutableBlock preheader = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot loopOwnership = snapshotOwnership();
        int firstReclamation = reclamations.size();
        MutableBlock header = createBlock("for.header",
                statement.condition().map(Expression::span).orElse(statement.span()));
        MutableBlock body = createBlock("for.body", statement.body().span());
        MutableBlock update = createBlock("for.update", statement.span());
        MutableBlock exit = createBlock("for.exit", statement.span());
        preheader.terminate(new IrJump(header.label, statement.span()));

        LinkedHashMap<LocalSymbol, MutablePhi> loopPhis = new LinkedHashMap<>();
        LinkedHashMap<LocalSymbol, IrOperand> headerEnvironment = new LinkedHashMap<>();
        for (Map.Entry<LocalSymbol, IrOperand> entry : before.entrySet()) {
            IrValueReference result = newValue(entry.getKey().type(), statement.span());
            MutablePhi phi = new MutablePhi(result,
                    List.of(new IrPhiIncoming(preheader.label, entry.getValue())), statement.span());
            header.addPhi(phi);
            loopPhis.put(entry.getKey(), phi);
            headerEnvironment.put(entry.getKey(), result);
            AllocationInfo allocation = allocationOf(entry.getValue());
            if (allocation != null) {
                allocationsByOperand.put(result, allocation);
                propagateOwnedHelperBorrow(result, entry.getValue());
            }
        }

        currentBlock = header;
        environment = headerEnvironment;
        PatternFlow.Result patternFlow = statement.condition().map(PatternFlow::analyze)
                .orElseGet(() -> PatternFlow.analyze(
                        new BooleanLiteralExpression(true, statement.span())));
        boolean alwaysTrue = statement.condition().map(FunctionAnalyzer::isLiteralTrue).orElse(true);
        IrOperand condition = statement.condition().map(value ->
                requireCondition(lowerExpression(value), value.span(), "for"))
                .orElseGet(() -> new IrConstant(IrType.I1, 1, statement.span()));
        MutableBlock conditionEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> conditionEnvironment = copyEnvironment();
        OwnershipSnapshot conditionOwnership = snapshotOwnership();
        conditionEnd.terminate(alwaysTrue
                ? new IrJump(body.label, statement.span())
                : new IrBranch(condition, body.label, exit.label, statement.span()));

        LoopContext loop = new LoopContext(exit.label, update.label, List.copyOf(finallyContexts));
        breakContexts.push(loop);
        loopContexts.push(loop);
        if (label != null) {
            pushLabeledContext(label, loop, loop);
        }
        BranchFlow bodyFlow = lowerBranch(body, statement.body(), conditionEnvironment,
                patternFlow.whenTrue());
        if (label != null) {
            labeledContexts.pop();
        }
        loopContexts.pop();
        breakContexts.pop();
        if (bodyFlow.reachable()) {
            bodyFlow.block().terminate(new IrJump(update.label, statement.span()));
            loop.continueFlows.add(bodyFlow);
        }

        currentBlock = update;
        LinkedHashMap<LocalSymbol, IrOperand> updateEnvironment = null;
        List<BranchFlow> backEdges = new ArrayList<>();
        if (loop.continueFlows.isEmpty()) {
            update.terminate(new IrUnreachable(statement.span()));
        } else {
            mergeFlowOwnership(loop.continueFlows);
            environment = new LinkedHashMap<>();
            for (LocalSymbol symbol : before.keySet()) {
                environment.put(symbol, mergeValue(symbol, loop.continueFlows,
                        statement.span(), update));
            }
            enterScope();
            activatePatternBindings(patternFlow.whenTrue());
            for (PatternFlow.Binding binding : patternFlow.whenTrue()) {
                PatternLocal local = patternLocals.get(binding.expression());
                if (local != null && loop.continueFlows.stream()
                        .allMatch(flow -> flow.environment().containsKey(local.symbol))) {
                    environment.put(local.symbol, mergeValue(local.symbol,
                            loop.continueFlows, statement.span(), update));
                }
            }
            for (Expression updateExpression : statement.updates()) {
                if (!(updateExpression instanceof AssignmentExpression)
                        && !(updateExpression instanceof UpdateExpression)
                        && !(updateExpression instanceof CallExpression)
                        && !(updateExpression instanceof NewExpression)) {
                    diagnostics.add(error(updateExpression.span(),
                            "for update must be an assignment, increment, decrement, method call, or object creation"));
                }
                lowerExpression(updateExpression);
            }
            updateEnvironment = copyEnvironment();
            exitScope();
            MutableBlock updateEnd = currentBlock;
            backEdges.add(new BranchFlow(true, updateEnd, copyEnvironment(), snapshotOwnership()));
            updateEnd.terminate(new IrJump(header.label, statement.span()));
            for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
                entry.getValue().addIncoming(new IrPhiIncoming(updateEnd.label,
                        environment.get(entry.getKey())));
            }
        }
        validateLoopBackEdges(loopOwnership, before, backEdges, firstReclamation);
        for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
            AllocationInfo initial = allocationOf(before.get(entry.getKey()));
            if (initial != null && updateEnvironment != null
                    && allocationOf(updateEnvironment.get(entry.getKey())) != initial) {
                allocationsByOperand.remove(entry.getValue().result);
            }
        }

        currentBlock = exit;
        List<BranchFlow> exits = new ArrayList<>();
        if (!alwaysTrue) {
            exits.add(new BranchFlow(true, conditionEnd, conditionEnvironment, conditionOwnership));
        }
        exits.addAll(loop.breakFlows);
        if (exits.isEmpty()) {
            exit.terminate(new IrUnreachable(statement.span()));
            environment = before;
            exitScope();
            return false;
        }
        mergeLoopOwnership(exits, backEdges);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, exits, statement.span(), exit));
        }
        exitScope();
        if (loop.breakFlows.isEmpty()) {
            activatePatternBindings(patternFlow.whenFalse());
        }
        return true;
    }

    private boolean lowerEnhancedFor(EnhancedForStatement statement,
                                     LabeledStatement label) {
        enterScope();
        TypedValue sourceValue = lowerExpression(statement.iterable());
        IrOperand source = sourceValue.operand();
        boolean array = sourceValue.type().isArray();
        boolean iterable = !array && iterableView(sourceValue.type()) != null;
        if (!array && !iterable) {
            diagnostics.add(error(statement.iterable().span(), "enhanced-for expression must have "
                    + "an array type or implement ironwood.lang.Iterable, not "
                    + typeName(sourceValue.type())));
        }

        IrType sourceStorageType = sourceValue.type().equals(IrType.VOID)
                ? IrType.I32 : sourceValue.type();
        String sourceName = bindSyntheticLocal("enhanced.source", sourceStorageType,
                source == null ? defaultValue(sourceStorageType, statement.iterable().span()) : source,
                statement.iterable().span());
        IrOperand arrayLength = null;
        String iteratorName = null;
        if (array) {
            IrOperand arrayOperand = environment.get(resolve(sourceName));
            emitNullCheck(arrayOperand, statement.iterable().span());
            IrValueReference length = newValue(IrType.I32, statement.iterable().span());
            currentBlock.addInstruction(new IrArrayLengthInstruction(length, arrayOperand,
                    statement.iterable().span()));
            arrayLength = length;
        } else if (iterable) {
            TypedValue iterator = lowerSyntheticCall(sourceName, "iterator", statement.iterable().span());
            if (!iterator.type().isNominalReference()
                    || !iterator.type().referenceName().equals("ironwood.util.Iterator")) {
                diagnostics.add(error(statement.iterable().span(),
                        "ironwood.lang.Iterable.iterator() must return ironwood.util.Iterator"));
            }
            IrOperand iteratorOperand = iterator.operand() == null
                    ? defaultValue(iterator.type(), statement.iterable().span()) : iterator.operand();
            iteratorName = bindSyntheticLocal("enhanced.iterator", iterator.type(),
                    iteratorOperand, statement.iterable().span());
        }

        MutableBlock preheader = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot loopOwnership = snapshotOwnership();
        int firstReclamation = reclamations.size();
        MutableBlock header = createBlock("enhanced.header", statement.iterable().span());
        MutableBlock body = createBlock("enhanced.body", statement.body().span());
        MutableBlock update = createBlock("enhanced.update", statement.span());
        MutableBlock exit = createBlock("enhanced.exit", statement.span());
        preheader.terminate(new IrJump(header.label, statement.span()));

        LinkedHashMap<LocalSymbol, MutablePhi> loopPhis = new LinkedHashMap<>();
        LinkedHashMap<LocalSymbol, IrOperand> headerEnvironment = new LinkedHashMap<>();
        for (Map.Entry<LocalSymbol, IrOperand> entry : before.entrySet()) {
            IrValueReference result = newValue(entry.getKey().type(), statement.span());
            MutablePhi phi = new MutablePhi(result,
                    List.of(new IrPhiIncoming(preheader.label, entry.getValue())), statement.span());
            header.addPhi(phi);
            loopPhis.put(entry.getKey(), phi);
            headerEnvironment.put(entry.getKey(), result);
            AllocationInfo allocation = allocationOf(entry.getValue());
            if (allocation != null) {
                allocationsByOperand.put(result, allocation);
                propagateOwnedHelperBorrow(result, entry.getValue());
            }
        }

        MutablePhi indexPhi = null;
        IrOperand index = null;
        currentBlock = header;
        environment = headerEnvironment;
        IrOperand condition;
        if (array) {
            IrValueReference indexValue = newValue(IrType.I32, statement.span());
            indexPhi = new MutablePhi(indexValue,
                    List.of(new IrPhiIncoming(preheader.label,
                            new IrConstant(IrType.I32, 0, statement.span()))), statement.span());
            header.addPhi(indexPhi);
            index = indexValue;
            IrValueReference less = newValue(IrType.I1, statement.span());
            header.addInstruction(new IrBinaryInstruction(less, IrBinaryOperator.SIGNED_LESS,
                    index, arrayLength, statement.span()));
            condition = less;
        } else if (iterable) {
            TypedValue hasNext = lowerSyntheticCall(iteratorName, "hasNext", statement.iterable().span());
            condition = requireCondition(hasNext, statement.iterable().span(), "enhanced-for iterator");
        } else {
            condition = new IrConstant(IrType.I1, 0, statement.span());
        }
        MutableBlock conditionEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> conditionEnvironment = copyEnvironment();
        OwnershipSnapshot conditionOwnership = snapshotOwnership();
        conditionEnd.terminate(new IrBranch(condition, body.label, exit.label, statement.span()));

        LoopContext loop = new LoopContext(exit.label, update.label, List.copyOf(finallyContexts));
        breakContexts.push(loop);
        loopContexts.push(loop);
        if (label != null) {
            pushLabeledContext(label, loop, loop);
        }
        currentBlock = body;
        environment = new LinkedHashMap<>(conditionEnvironment);
        enterScope();
        IrType declaredType = resolveType(statement.variableType());
        TypedValue element;
        if (array) {
            LocalSymbol sourceSymbol = resolve(sourceName);
            IrOperand arrayOperand = environment.get(sourceSymbol);
            emitArrayBoundsCheck(arrayOperand, index, statement.span());
            IrValueReference loaded = newValue(sourceValue.type().elementType(), statement.span());
            currentBlock.addInstruction(new IrArrayLoadInstruction(loaded, arrayOperand, index,
                    statement.span()));
            trackArrayElementLoad(loaded, arrayOperand, index);
            element = new TypedValue(sourceValue.type().elementType(), loaded);
        } else if (iterable) {
            element = lowerSyntheticCall(iteratorName, "next", statement.iterable().span());
        } else {
            element = new TypedValue(declaredType, defaultValue(declaredType, statement.span()));
        }
        if (!isAssignmentConvertible(declaredType, element)) {
            diagnostics.add(error(statement.variableNameSpan(), "cannot assign enhanced-for element of type "
                    + typeName(element.type()) + " to " + typeName(declaredType)
                    + " variable '" + statement.variableName() + "'"));
        }
        IrOperand elementOperand = assignmentValue(element, declaredType,
                statement.variableNameSpan(), "enhanced-for variable");
        LocalSymbol variable = declare(statement.variableName(), declaredType,
                statement.variableNameSpan(), "enhanced-for variable", statement.variableFinal());
        if (variable != null) {
            environment.put(variable, elementOperand);
        }
        boolean bodyReachable = lowerStatement(statement.body());
        BranchFlow bodyFlow = new BranchFlow(bodyReachable, currentBlock, copyEnvironment(), snapshotOwnership());
        exitScope();
        if (label != null) {
            labeledContexts.pop();
        }
        loopContexts.pop();
        breakContexts.pop();
        if (bodyFlow.reachable()) {
            bodyFlow.block().terminate(new IrJump(update.label, statement.span()));
            loop.continueFlows.add(bodyFlow);
        }

        MutableBlock updateEnd = null;
        LinkedHashMap<LocalSymbol, IrOperand> updateEnvironment = null;
        List<BranchFlow> backEdges = new ArrayList<>();
        currentBlock = update;
        if (loop.continueFlows.isEmpty()) {
            update.terminate(new IrUnreachable(statement.span()));
        } else {
            mergeFlowOwnership(loop.continueFlows);
            environment = new LinkedHashMap<>();
            for (LocalSymbol symbol : before.keySet()) {
                environment.put(symbol, mergeValue(symbol, loop.continueFlows,
                        statement.span(), update));
            }
            IrOperand nextIndex = null;
            if (array) {
                IrValueReference incremented = newValue(IrType.I32, statement.span());
                currentBlock.addInstruction(new IrBinaryInstruction(incremented,
                        IrBinaryOperator.ADD, index,
                        new IrConstant(IrType.I32, 1, statement.span()), statement.span()));
                nextIndex = incremented;
            }
            updateEnvironment = copyEnvironment();
            updateEnd = currentBlock;
            backEdges.add(new BranchFlow(true, updateEnd, copyEnvironment(), snapshotOwnership()));
            updateEnd.terminate(new IrJump(header.label, statement.span()));
            for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
                entry.getValue().addIncoming(new IrPhiIncoming(updateEnd.label,
                        updateEnvironment.get(entry.getKey())));
            }
            if (indexPhi != null) {
                indexPhi.addIncoming(new IrPhiIncoming(updateEnd.label, nextIndex));
            }
        }
        validateLoopBackEdges(loopOwnership, before, backEdges, firstReclamation);
        for (Map.Entry<LocalSymbol, MutablePhi> entry : loopPhis.entrySet()) {
            AllocationInfo initial = allocationOf(before.get(entry.getKey()));
            if (initial != null && updateEnvironment != null
                    && allocationOf(updateEnvironment.get(entry.getKey())) != initial) {
                allocationsByOperand.remove(entry.getValue().result);
            }
        }

        currentBlock = exit;
        List<BranchFlow> exits = new ArrayList<>();
        exits.add(new BranchFlow(true, conditionEnd, conditionEnvironment, conditionOwnership));
        exits.addAll(loop.breakFlows);
        mergeLoopOwnership(exits, backEdges);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, exits, statement.span(), exit));
        }
        exitScope();
        return true;
    }

    private boolean lowerLabeled(LabeledStatement statement) {
        if (statement.body() instanceof WhileStatement loop) {
            diagnoseDuplicateLabel(statement);
            return lowerWhile(loop, statement);
        }
        if (statement.body() instanceof DoWhileStatement loop) {
            diagnoseDuplicateLabel(statement);
            return lowerDoWhile(loop, statement);
        }
        if (statement.body() instanceof ForStatement loop) {
            diagnoseDuplicateLabel(statement);
            return lowerFor(loop, statement);
        }
        if (statement.body() instanceof EnhancedForStatement loop) {
            diagnoseDuplicateLabel(statement);
            return lowerEnhancedFor(loop, statement);
        }

        diagnoseDuplicateLabel(statement);
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        MutableBlock exit = createBlock("label." + statement.label(), statement.span());
        BreakContext target = new BreakContext(exit.label, List.copyOf(finallyContexts));
        pushLabeledContext(statement, target, null);
        boolean reachable = lowerStatement(statement.body());
        labeledContexts.pop();
        List<BranchFlow> incoming = new ArrayList<>(target.breakFlows);
        if (reachable) {
            currentBlock.terminate(new IrJump(exit.label, statement.span()));
            incoming.add(new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership()));
        }
        currentBlock = exit;
        if (incoming.isEmpty()) {
            exit.terminate(new IrUnreachable(statement.span()));
            environment = before;
            return false;
        }
        mergeFlowOwnership(incoming);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, incoming, statement.span(), exit));
        }
        return true;
    }

    private void diagnoseDuplicateLabel(LabeledStatement statement) {
        if (labeledContexts.stream().anyMatch(context -> context.label.equals(statement.label()))) {
            diagnostics.add(error(statement.labelSpan(), "duplicate active statement label '"
                    + statement.label() + "'"));
        }
    }

    private void pushLabeledContext(LabeledStatement statement, BreakContext breakContext,
                                    LoopContext continueContext) {
        labeledContexts.push(new LabeledContext(statement.label(), breakContext, continueContext));
    }

    private IrType iterableView(IrType type) {
        if (!type.isReference()) {
            return null;
        }
        return hierarchy.exactSupertypes(type).stream()
                .filter(candidate -> candidate.isNominalReference()
                        && candidate.referenceName().equals("ironwood.lang.Iterable"))
                .findFirst().orElse(null);
    }

    private String bindSyntheticLocal(String prefix, IrType type, IrOperand value,
                                      SourceSpan span) {
        String name = "\u0000" + prefix + "." + nextSyntheticLocalId++;
        LocalSymbol symbol = new LocalSymbol(nextSymbolId++, name, type, true,
                controlFlowDepth, null);
        scopes.peek().put(name, symbol);
        environment.put(symbol, value);
        return name;
    }

    private TypedValue lowerSyntheticCall(String receiverName, String methodName,
                                          SourceSpan span) {
        NameExpression receiver = new NameExpression(receiverName, span);
        return lowerExpression(new CallExpression(Optional.of(receiver), methodName,
                span, List.of(), span));
    }

    private boolean lowerBreak(BreakStatement statement) {
        BreakContext target = statement.label().isPresent()
                ? labeledContext(statement.label().orElseThrow()).map(context -> context.breakContext)
                .orElse(null)
                : breakContexts.peek();
        if (target == null) {
            diagnostics.add(error(statement.labelSpan().orElse(statement.span()),
                    statement.label().isPresent()
                            ? "unknown statement label '" + statement.label().orElseThrow() + "'"
                            : "break statement is only valid inside a loop or switch"));
            return true;
        }
        if (target.breakTarget == null) {
            diagnostics.add(error(statement.span(),
                    "unlabeled break is not permitted inside a switch expression; use yield"));
            return true;
        }
        return lowerTransfer(target, target.breakTarget, target.breakFlows, "break",
                statement.span());
    }

    private boolean lowerContinue(ContinueStatement statement) {
        LabeledContext labeled = statement.label().isPresent()
                ? labeledContext(statement.label().orElseThrow()).orElse(null) : null;
        LoopContext target = statement.label().isPresent()
                ? labeled == null ? null : labeled.continueContext
                : loopContexts.peek();
        if (target == null) {
            SourceSpan errorSpan = statement.labelSpan().orElse(statement.span());
            if (statement.label().isPresent() && labeled == null) {
                diagnostics.add(error(errorSpan, "unknown statement label '"
                        + statement.label().orElseThrow() + "'"));
            } else if (statement.label().isPresent()) {
                diagnostics.add(error(errorSpan, "continue label '"
                        + statement.label().orElseThrow() + "' does not denote a loop"));
            } else {
                diagnostics.add(error(errorSpan,
                        "continue statement is only valid inside a loop"));
            }
            return true;
        }
        return lowerTransfer(target, target.continueTarget, target.continueFlows,
                "continue", statement.span());
    }

    private Optional<LabeledContext> labeledContext(String label) {
        return labeledContexts.stream().filter(context -> context.label.equals(label)).findFirst();
    }

    private boolean lowerTransfer(BreakContext context, String target,
                                  List<BranchFlow> flows, String keyword,
                                  SourceSpan span) {
        List<FinallyContext> current = List.copyOf(finallyContexts);
        List<FinallyContext> targetContexts = context.targetFinallyContexts;
        int cleanupCount = current.size() - targetContexts.size();
        if (cleanupCount < 0
                || !current.subList(cleanupCount, current.size()).equals(targetContexts)) {
            diagnostics.add(error(span, "cannot resolve " + keyword
                    + " cleanup path across active finally blocks"));
            return true;
        }
        completeTransferThrough(current.subList(0, cleanupCount), 0,
                target, flows, span);
        return false;
    }

    private void completeTransferThrough(List<FinallyContext> pending, int index,
                                         String target, List<BranchFlow> flows,
                                         SourceSpan span) {
        if (index >= pending.size()) {
            BranchFlow flow = new BranchFlow(true, currentBlock, copyEnvironment(), snapshotOwnership());
            currentBlock.terminate(new IrJump(target, span));
            flows.add(flow);
            return;
        }
        FinallyContext context = pending.get(index);
        List<ExceptionRegion> savedExceptions = List.copyOf(exceptionRegions);
        List<FinallyContext> savedFinally = List.copyOf(finallyContexts);
        LinkedHashMap<LocalSymbol, IrOperand> savedEnvironment = copyEnvironment();
        OwnershipSnapshot savedOwnership = snapshotOwnership();
        restoreDeque(exceptionRegions, context.outerExceptionRegions());
        restoreDeque(finallyContexts, context.outerFinallyContexts());
        try {
            if (lowerBlock(context.body(), true)) {
                completeTransferThrough(pending, index + 1, target, flows, span);
            }
        } finally {
            environment = savedEnvironment;
            restoreOwnership(savedOwnership);
            restoreDeque(exceptionRegions, savedExceptions);
            restoreDeque(finallyContexts, savedFinally);
        }
    }

    private TypedValue lowerExpression(Expression expression) {
        return lowerExpression(expression, Optional.empty());
    }

    private TypedValue lowerExpression(Expression expression, Optional<IrType> expectedType) {
        expressionDepth++;
        try {
            TypedValue value = lowerExpressionValue(expression, expectedType);
            // Constructors may roll back on failure. Register only the completed result.
            if (unfreed != null && expression instanceof NewExpression) {
                unfreed.register(allocationsByOperand.get(value.operand()), expression.span(),
                        "new allocation", true);
            }
            return value;
        } finally {
            expressionDepth--;
        }
    }

    private TypedValue lowerExpressionValue(Expression expression, Optional<IrType> expectedType) {
        diagnosePatternConflicts(expression);
        if (expression instanceof IntegerLiteralExpression integerLiteral) {
            return lowerIntegerLiteral(integerLiteral);
        }
        if (expression instanceof FloatingLiteralExpression floatingLiteral) {
            return lowerFloatingLiteral(floatingLiteral);
        }
        if (expression instanceof CharacterLiteralExpression characterLiteral) {
            BigInteger value = BigInteger.valueOf(characterLiteral.value());
            return new TypedValue(IrType.U16,
                    new IrConstant(IrType.U16, characterLiteral.value(), characterLiteral.span()), value);
        }
        if (expression instanceof BooleanLiteralExpression booleanLiteral) {
            return new TypedValue(IrType.I1,
                    new IrConstant(IrType.I1, booleanLiteral.value() ? 1 : 0, booleanLiteral.span()),
                    booleanLiteral.value() ? BigInteger.ONE : BigInteger.ZERO);
        }
        if (expression instanceof NullLiteralExpression nullLiteral) {
            return new TypedValue(IrType.NULL, new IrNull(IrType.NULL, nullLiteral.span()));
        }
        if (expression instanceof StringLiteralExpression stringLiteral) {
            if (hierarchy.type("ironwood.lang.String").isEmpty()) {
                diagnostics.add(error(stringLiteral.span(),
                        "string literal requires bundled type 'ironwood.lang.String'"));
            }
            return new TypedValue(IrType.reference("ironwood.lang.String"),
                    stringPool.intern(stringLiteral.value(), stringLiteral.span()));
        }
        if (expression instanceof ThisExpression thisExpression) {
            if (evaluatingConstructorArguments) {
                diagnostics.add(error(thisExpression.span(),
                        "'this' cannot be used before superclass construction"));
            }
            if (function.isStatic()) {
                diagnostics.add(error(thisExpression.span(), "'this' cannot be used in a static method"));
                IrType type = currentClass.selfType();
                return new TypedValue(type, new IrNull(type, thisExpression.span()));
            }
            return new TypedValue(currentClass.selfType(), thisOperand);
        }
        if (expression instanceof QualifiedThisExpression qualifiedThis) {
            TypeResolver.Resolution resolution = hierarchy.resolveType(qualifiedThis.typeName(), currentClass,
                    qualifiedThis.typeNameSpan());
            TypeSymbol target = resolution.type().orElse(null);
            if (target == null) {
                diagnostics.add(error(qualifiedThis.typeNameSpan(), "unknown enclosing type '"
                        + qualifiedThis.typeName() + "'"));
                return new TypedValue(currentClass.selfType(), new IrNull(currentClass.selfType(),
                        qualifiedThis.span()));
            }
            TypedValue enclosing = enclosingInstanceFor(target, qualifiedThis.span());
            if (enclosing == null) {
                diagnostics.add(error(qualifiedThis.span(), "'" + qualifiedThis.typeName()
                        + ".this' is not available in this static or lexical context"));
                return new TypedValue(target.selfType(), new IrNull(target.selfType(), qualifiedThis.span()));
            }
            return enclosing;
        }
        if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            lowerExpression(invocation.enclosingInstance());
            invocation.arguments().forEach(this::lowerExpression);
            diagnostics.add(error(invocation.span(),
                    "qualified super constructor invocation must be the first statement in a constructor"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, invocation.span()));
        }
        if (expression instanceof SuperExpression superExpression) {
            SuperTarget target = resolveSuperTarget(superExpression.span());
            diagnostics.add(error(superExpression.span(), "'super' must be followed by a field or method name"));
            if (target == null) {
                return new TypedValue(currentClass.selfType(), new IrNull(currentClass.selfType(),
                        superExpression.span()));
            }
            return new TypedValue(target.type(), target.receiver());
        }
        if (expression instanceof InterfaceSuperExpression interfaceSuperExpression) {
            diagnostics.add(error(interfaceSuperExpression.span(),
                    "qualified interface super must be followed by a method name"));
            IrType type = currentClass.selfType();
            return new TypedValue(type, new IrNull(type, interfaceSuperExpression.span()));
        }
        if (expression instanceof NameExpression nameExpression) {
            return lowerName(nameExpression);
        }
        if (expression instanceof FieldAccessExpression fieldAccess) {
            return lowerFieldAccess(fieldAccess);
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return lowerArrayAccess(arrayAccess);
        }
        if (expression instanceof InstanceOfExpression instanceOfExpression) {
            return lowerInstanceOf(instanceOfExpression);
        }
        if (expression instanceof NewExpression newExpression) {
            return lowerPlannedNew(newExpression, expectedType);
        }
        if (expression instanceof ArrayCreationExpression arrayCreation) {
            return lowerArrayCreation(arrayCreation);
        }
        if (expression instanceof ArrayInitializerExpression arrayInitializer) {
            if (expectedType.isEmpty() || !expectedType.orElseThrow().isArray()) {
                arrayInitializer.elements().forEach(this::lowerExpression);
                diagnostics.add(error(arrayInitializer.span(),
                        "array initializer requires an array declaration target"));
                IrType fallback = IrType.array(IrType.I32);
                return new TypedValue(fallback, new IrNull(fallback, arrayInitializer.span()));
            }
            return lowerArrayInitializer(arrayInitializer, expectedType.orElseThrow());
        }
        if (expression instanceof UnaryExpression unaryExpression) {
            return lowerUnary(unaryExpression);
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return lowerBinary(binaryExpression);
        }
        if (expression instanceof AssignmentExpression assignmentExpression) {
            return lowerAssignmentExpression(assignmentExpression);
        }
        if (expression instanceof UpdateExpression updateExpression) {
            return lowerUpdate(updateExpression);
        }
        if (expression instanceof ConditionalExpression conditionalExpression) {
            return lowerConditional(conditionalExpression, expectedType);
        }
        if (expression instanceof SwitchExpression switchExpression) {
            return lowerSwitchExpression(switchExpression, expectedType);
        }
        if (expression instanceof CastExpression castExpression) {
            return lowerCast(castExpression);
        }
        if (expression instanceof CallExpression callExpression) {
            return requiresCallablePlanning(callExpression)
                    ? lowerPlannedCall(callExpression, expectedType)
                    : lowerCall(callExpression);
        }
        throw new IllegalStateException("unsupported expression " + expression.getClass().getSimpleName());
    }

    private TypedValue lowerName(NameExpression expression) {
        LocalSymbol symbol = resolve(expression.name());
        if (symbol != null) {
            IrOperand operand = readLocal(symbol, expression.span());
            AllocationInfo allocation = allocationOf(operand);
            if (allocation != null && allocation.state.mayBeFreed()) {
                diagnostics.add(error(expression.span(), "cannot use '" + expression.name()
                        + "' after its allocation was freed"));
            }
            return new TypedValue(symbol.type(), operand);
        }
        FieldSymbol field = resolveField(currentClass.selfType(), expression.name(), expression.span());
        if (field != null) {
            diagnoseIllegalForwardInstanceFieldRead(field, expression.name(), expression.span());
            diagnoseIllegalForwardStaticFieldRead(field, expression.name(), expression.span());
            if (!isAccessible(field.accessModifier(), field.ownerClass(), currentClass.name(), false)) {
                diagnostics.add(error(expression.span(), memberAccessMessage("field", expression.name(),
                        field.accessModifier(), field.ownerClass())));
            }
            if (field.isStatic()) {
                return loadStaticField(field, expression.span());
            }
            if (evaluatingConstructorArguments) {
                diagnostics.add(error(expression.span(), "instance field '" + expression.name()
                        + "' cannot be read before superclass construction"));
            }
            if (function.isStatic()) {
                diagnostics.add(error(expression.span(),
                        "instance field '" + expression.name() + "' cannot be referenced from a static method"));
                return new TypedValue(field.type(), defaultValue(field.type(), expression.span()));
            }
            IrValueReference result = newValue(field.type(), expression.span());
            currentBlock.addInstruction(new IrFieldLoadInstruction(result, thisOperand,
                    field.irField(), expression.span()));
            trackOwnedFieldLoad(result, thisOperand, field);
            return new TypedValue(field.type(), result);
        }
        LocalClassSemantics.VariableIdentity captured = currentClass.variableAt(expression.span())
                .orElse(null);
        if (captured != null) {
            TypeSymbol.CaptureSlot capture = currentClass.captureSlot(captured.id()).orElse(null);
            IrOperand operand = capturedVariableOperand(captured, expression.span());
            if (capture != null && operand != null) {
                return new TypedValue(capture.type(), operand);
            }
        }
        FieldTarget lexical = resolveLexicalField(expression.name(), expression.span());
        if (lexical != null) {
            if (lexical.field().isStatic()) {
                return loadStaticField(lexical.field(), expression.span());
            }
            if (lexical.receiver() == null) {
                return new TypedValue(lexical.field().type(),
                        defaultValue(lexical.field().type(), expression.span()));
            }
            IrValueReference result = newValue(lexical.field().type(), expression.span());
            currentBlock.addInstruction(new IrFieldLoadInstruction(result, lexical.receiver(),
                    lexical.field().irField(), expression.span()));
            trackOwnedFieldLoad(result, lexical.receiver(), lexical.field());
            return new TypedValue(lexical.field().type(), result);
        }
        FieldSymbol imported = resolveStaticImportedField(expression.name(), expression.span(), true);
        if (imported != null) {
            return loadStaticField(imported, expression.span());
        }
        diagnostics.add(error(expression.span(), "unknown local variable '" + expression.name() + "'"));
        return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
    }

    private TypedValue lowerFieldAccess(FieldAccessExpression expression) {
        if (expression.receiver() instanceof SuperExpression superExpression) {
            FieldTarget target = resolveSuperFieldTarget(expression, superExpression);
            if (target == null) {
                return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
            }
            IrValueReference result = newValue(target.field().type(), expression.span());
            currentBlock.addInstruction(new IrFieldLoadInstruction(result, target.receiver(),
                    target.field().irField(), expression.span()));
            trackOwnedFieldLoad(result, target.receiver(), target.field());
            return new TypedValue(target.field().type(), result);
        }
        ClassFieldResolution qualified = resolveClassField(expression);
        if (qualified.classQualifier()) {
            if (qualified.field() == null) {
                return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
            }
            if (!qualified.field().isStatic()) {
                diagnostics.add(error(expression.fieldNameSpan(), "instance field '"
                        + expression.fieldName() + "' cannot be accessed through type '"
                        + qualified.qualifier().name() + "'"));
                return new TypedValue(qualified.field().type(),
                        defaultValue(qualified.field().type(), expression.span()));
            }
            return loadStaticField(qualified.field(), expression.span());
        }
        TypedValue possibleArray = lowerExpression(expression.receiver());
        if (possibleArray.type().isArray()) {
            if (!expression.fieldName().equals("length")) {
                diagnostics.add(error(expression.fieldNameSpan(), "array type '"
                        + typeName(possibleArray.type()) + "' has no field '"
                        + expression.fieldName() + "'"));
                return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
            }
            IrOperand array = requireValue(possibleArray, possibleArray.type(),
                    expression.receiver().span(), "array length receiver");
            emitNullCheck(array, expression.receiver().span());
            IrValueReference result = newValue(IrType.I32, expression.span());
            currentBlock.addInstruction(new IrArrayLengthInstruction(result, array, expression.span()));
            return new TypedValue(IrType.I32, result);
        }
        return lowerFieldAccess(expression, possibleArray);
    }

    private TypedValue lowerFieldAccess(FieldAccessExpression expression, TypedValue receiver) {
        FieldTarget target = resolveFieldTarget(expression, receiver);
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        IrValueReference result = newValue(target.field().type(), expression.span());
        currentBlock.addInstruction(new IrFieldLoadInstruction(result, target.receiver(),
                target.field().irField(), expression.span()));
        trackOwnedFieldLoad(result, target.receiver(), target.field());
        return new TypedValue(target.field().type(), result);
    }

    private SuperTarget resolveSuperTarget(SourceSpan span) {
        if (function.isStatic()) {
            diagnostics.add(error(span, "'super' cannot be used in a static method"));
            return null;
        }
        if (evaluatingConstructorArguments) {
            diagnostics.add(error(span, "'super' members cannot be used before superclass construction"));
        }
        TypeSymbol superclass = currentClass.superclass().orElse(null);
        if (superclass == null) {
            diagnostics.add(error(span, "root class '" + currentClass.name() + "' has no superclass members"));
            return null;
        }
        IrType exactType = hierarchy.superclassType(currentClass.selfType())
                .orElse(IrType.reference(superclass.name()));
        return new SuperTarget(superclass, exactType,
                convertReference(thisOperand, exactType, span));
    }

    private FieldTarget resolveSuperFieldTarget(FieldAccessExpression expression,
                                                SuperExpression superExpression) {
        SuperTarget target = resolveSuperTarget(superExpression.span());
        if (target == null) {
            return null;
        }
        FieldSymbol field = resolveField(target.type(), expression.fieldName(),
                expression.fieldNameSpan());
        if (field == null) {
            diagnostics.add(error(expression.fieldNameSpan(), "superclass '" + target.symbol().name()
                    + "' has no field '" + expression.fieldName() + "'"));
            return null;
        }
        if (field.isStatic()) {
            diagnostics.add(error(expression.fieldNameSpan(), "static field '" + expression.fieldName()
                    + "' must be accessed through type '" + field.ownerClass() + "'"));
            return null;
        }
        if (!isAccessible(field.accessModifier(), field.ownerClass(), null, false)) {
            diagnostics.add(error(expression.fieldNameSpan(), memberAccessMessage("field",
                    expression.fieldName(), field.accessModifier(), field.ownerClass())));
        }
        return new FieldTarget(target.receiver(), field);
    }

    private TypedValue loadStaticField(FieldSymbol field, SourceSpan span) {
        if (field.constantValue() != null && field.type().equals(STRING_TYPE)
                && field.constantValue().stringValue() != null) {
            return new TypedValue(STRING_TYPE, field.constantValue().stringValue());
        }
        if (field.staticField().triggersInitialization()) {
            ensureTypeInitialized(field.ownerClass(), span);
        }
        IrValueReference result = newValue(field.type(), span);
        currentBlock.addInstruction(new IrStaticFieldLoadInstruction(result, field.staticField(), span));
        return new TypedValue(field.type(), result,
                field.constantValue() != null && field.type().isIntegral()
                        ? BigInteger.valueOf(field.constantValue().value().longValue()) : null);
    }

    private FieldTarget resolveFieldTarget(FieldAccessExpression expression) {
        TypedValue receiver = lowerExpression(expression.receiver());
        return resolveFieldTarget(expression, receiver);
    }

    private ClassFieldResolution resolveClassField(FieldAccessExpression expression) {
        String qualifierName = qualifiedName(expression.receiver());
        String root = rootName(expression.receiver());
        if (qualifierName == null || root != null && (resolve(root) != null
                || hierarchy.lookupField(currentClass.selfType(), root).isPresent()
                || hasLexicalField(root) || hasStaticImportedField(root))) {
            return new ClassFieldResolution(false, null, null);
        }
        TypeResolver.Resolution resolution = hierarchy.resolveType(qualifierName, currentClass,
                expression.receiver().span());
        if (resolution.ambiguous()) {
            diagnostics.add(error(expression.receiver().span(), "ambiguous type '" + qualifierName
                    + "' in static field access"));
            return new ClassFieldResolution(true, null, null);
        }
        TypeSymbol qualifier = resolution.type().orElse(null);
        if (qualifier == null) {
            return new ClassFieldResolution(false, null, null);
        }
        if (resolution.inaccessible()) {
            diagnostics.add(error(expression.receiver().span(), "type '" + qualifier.name()
                    + "' is not accessible from package '" + currentClass.packageName() + "'"));
        }
        FieldSymbol field = resolveField(qualifier.selfType(), expression.fieldName(),
                expression.fieldNameSpan());
        if (field == null) {
            diagnostics.add(error(expression.fieldNameSpan(), "type '" + qualifier.name()
                    + "' has no field '" + expression.fieldName() + "'"));
            return new ClassFieldResolution(true, qualifier, null);
        }
        if (!isAccessible(field.accessModifier(), field.ownerClass(), qualifier.name(), false)) {
            diagnostics.add(error(expression.fieldNameSpan(), memberAccessMessage("field",
                    expression.fieldName(), field.accessModifier(), field.ownerClass())));
        }
        return new ClassFieldResolution(true, qualifier, field);
    }

    private FieldTarget resolveFieldTarget(FieldAccessExpression expression, TypedValue receiver) {
        if (receiver.type().isArray()) {
            diagnostics.add(error(expression.fieldNameSpan(), expression.fieldName().equals("length")
                    ? "array length is read-only"
                    : "array type '" + typeName(receiver.type()) + "' has no field '"
                    + expression.fieldName() + "'"));
            return null;
        }
        IrType receiverType = receiver.type().isWildcard()
                ? IrType.reference("ironwood.lang.Object") : receiver.type();
        if (!receiverType.isNominalReference() && !receiverType.isTypeParameter()) {
            diagnostics.add(error(expression.receiver().span(),
                    "field access requires an object reference, not " + typeName(receiver.type())));
            return null;
        }
        TypeSymbol owner = receiverType.isTypeParameter()
                ? hierarchy.type(receiverType.erasure().referenceName()).orElse(null)
                : hierarchy.type(receiverType.referenceName()).orElse(null);
        if (owner == null) {
            diagnostics.add(error(expression.fieldNameSpan(),
                    "unknown class type '" + receiverType.referenceName() + "'"));
            return null;
        }
        IrType memberReceiverType = captureReceiverType(receiverType);
        FieldSymbol field = resolveField(memberReceiverType, expression.fieldName(),
                expression.fieldNameSpan());
        if (field == null) {
            diagnostics.add(error(expression.fieldNameSpan(),
                    "type '" + owner.name() + "' has no field '" + expression.fieldName() + "'"));
            return null;
        }
        if (field.isStatic()) {
            diagnostics.add(error(expression.fieldNameSpan(), "static field '"
                    + expression.fieldName() + "' must be accessed through type '"
                    + field.ownerClass() + "'"));
            return null;
        }
        if (!isAccessible(field.accessModifier(), field.ownerClass(), owner.name(), false)) {
            diagnostics.add(error(expression.fieldNameSpan(), memberAccessMessage("field",
                    expression.fieldName(), field.accessModifier(), field.ownerClass())));
        }
        IrOperand receiverOperand = requireValue(receiver, receiverType, expression.receiver().span(),
                "field receiver");
        emitNullCheck(receiverOperand, expression.receiver().span());
        return new FieldTarget(receiverOperand, field);
    }

    private TypedValue lowerArrayCreation(ArrayCreationExpression expression) {
        IrType elementType = resolveType(expression.elementType());
        IrType arrayType = IrType.array(elementType);
        if (expression.initializer().isPresent()) {
            if (containsWildcard(elementType)) {
                diagnostics.add(error(expression.elementType().span(),
                        "cannot create an array with wildcard-dependent element type '"
                                + typeName(elementType) + "'"));
            }
            return lowerArrayInitializer(expression.initializer().orElseThrow(), arrayType);
        }
        Expression lengthExpression = expression.length().orElseThrow();
        TypedValue length = lowerExpression(lengthExpression);
        if (!isIntIndexType(length.type())) {
            diagnostics.add(error(lengthExpression.span(), "array length must have type int, not "
                    + typeName(length.type())));
        }
        IrOperand lengthOperand = isIntIndexType(length.type())
                ? requireValue(length, IrType.I32, lengthExpression.span(), "array length")
                : defaultValue(IrType.I32, lengthExpression.span());
        if (containsWildcard(elementType)) {
            diagnostics.add(error(expression.elementType().span(),
                    "cannot create an array with wildcard-dependent element type '"
                            + typeName(elementType) + "'"));
            return new TypedValue(arrayType, new IrNull(arrayType, expression.span()));
        }
        emitArrayLengthCheck(lengthOperand, lengthExpression.span());
        IrValueReference result = newValue(arrayType, expression.span());
        emitCall(new IrArrayAllocateInstruction(result, elementType,
                lengthOperand, expression.span()), expression.span());
        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        if (unfreed != null) unfreed.register(allocation, expression.span(), "array allocation", true);
        return new TypedValue(arrayType, result);
    }

    private TypedValue lowerArrayInitializer(ArrayInitializerExpression expression,
                                             IrType arrayType) {
        IrType elementType = arrayType.elementType();
        IrConstant length = new IrConstant(IrType.I32, expression.elements().size(),
                expression.span());
        IrValueReference result = newValue(arrayType, expression.span());
        emitCall(new IrArrayAllocateInstruction(result, elementType,
                length, expression.span()), expression.span());
        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        if (unfreed != null) unfreed.register(allocation, expression.span(), "array allocation", true);

        for (int index = 0; index < expression.elements().size(); index++) {
            Expression elementExpression = expression.elements().get(index);
            TypedValue element;
            if (elementExpression instanceof ArrayInitializerExpression nested) {
                if (!elementType.isArray()) {
                    nested.elements().forEach(this::lowerExpression);
                    diagnostics.add(error(nested.span(),
                            "nested array initializer requires array element type, not "
                                    + typeName(elementType)));
                    element = new TypedValue(elementType,
                            defaultValue(elementType, nested.span()));
                } else {
                    element = lowerArrayInitializer(nested, elementType);
                }
            } else {
                element = lowerExpression(elementExpression, Optional.of(elementType));
            }
            if (!isAssignmentConvertible(elementType, element)) {
                diagnostics.add(error(elementExpression.span(), "cannot initialize "
                        + typeName(elementType) + " array element with "
                        + typeName(element.type()) + " value"));
            }
            IrOperand value = assignmentValue(element, elementType,
                    elementExpression.span(), "array initializer element");
            IrConstant position = new IrConstant(IrType.I32, index, elementExpression.span());
            trackArrayElementStore(result, position, value);
            currentBlock.addInstruction(new IrArrayStoreInstruction(result, position,
                    value, elementExpression.span()));
        }
        return new TypedValue(arrayType, result);
    }

    private TypedValue lowerArrayAccess(ArrayAccessExpression expression) {
        ArrayTarget target = resolveArrayTarget(expression);
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        IrType elementType = target.array().type().elementType();
        IrValueReference result = newValue(elementType, expression.span());
        currentBlock.addInstruction(new IrArrayLoadInstruction(result, target.array(), target.index(),
                expression.span()));
        trackArrayElementLoad(result, target.array(), target.index());
        return new TypedValue(elementType, result);
    }

    private ArrayTarget resolveArrayTarget(ArrayAccessExpression expression) {
        TypedValue arrayValue = lowerExpression(expression.array());
        TypedValue indexValue = lowerExpression(expression.index());
        if (!arrayValue.type().isArray()) {
            diagnostics.add(error(expression.array().span(), "indexed access requires an array, not "
                    + typeName(arrayValue.type())));
            return null;
        }
        if (!isIntIndexType(indexValue.type())) {
            diagnostics.add(error(expression.index().span(), "array index must have type int, not "
                    + typeName(indexValue.type())));
        }
        IrOperand array = requireValue(arrayValue, arrayValue.type(), expression.array().span(),
                "array access");
        IrOperand index = isIntIndexType(indexValue.type())
                ? requireValue(indexValue, IrType.I32, expression.index().span(), "array index")
                : defaultValue(IrType.I32, expression.index().span());
        emitNullCheck(array, expression.array().span());
        emitArrayBoundsCheck(array, index, expression.span());
        return new ArrayTarget(array, index);
    }

    private TypedValue lowerPlannedNew(NewExpression expression,
                                       Optional<IrType> expectedType) {
        TypeResolver.Resolution targetResolution = hierarchy.resolveType(
                expression.className(), currentClass, expression.classNameSpan());
        TypeSymbol declaredTarget = targetResolution.type().orElse(null);
        boolean genericConstructor = declaredTarget != null
                && declaredTarget.constructors().stream()
                .anyMatch(candidate -> !candidate.typeVariables().isEmpty());
        boolean requiresPlanning = expression.diamond()
                || !expression.constructorTypeArguments().isEmpty() || genericConstructor
                || expression.anonymousClassBody().isPresent();
        if (!requiresPlanning) {
            return lowerNew(expression);
        }
        InvocationPlanningResult<ExpressionTypePlan> planned = invocationPlanner.plan(
                expression, expectedType);
        if (!planned.isResolved()) {
            reportPlanningFailure(planned, expression.span(), "constructor invocation");
            IrType type = declaredTarget == null ? IrType.reference(expression.className())
                    : declaredTarget.selfType();
            return new TypedValue(type, new IrNull(type, expression.span()));
        }
        InvocationPlan invocation = planned.resolvedValue().invocation().orElseThrow();
        return lowerSelectedConstructor(expression, invocation);
    }

    private TypedValue lowerSelectedConstructor(NewExpression expression,
                                                InvocationPlan invocation) {
        planningContext.commitCaptures();
        markAttachedOwnedFieldLoansUncertain(
                "cannot prove object construction before private-field detachment is non-reentrant");
        InvocationPlan.CandidatePlan selected = invocation.selected();
        CallableSymbol constructor = selected.candidate().callable()
                .substitute(selected.inference().substitutions());
        checkCheckedExceptions(constructor, expression.span());
        IrType referenceType = selected.resultType();
        TypeSymbol targetClass = hierarchy.type(referenceType.referenceName()).orElse(null);
        if (targetClass == null) {
            diagnostics.add(error(expression.classNameSpan(), "unknown class '"
                    + expression.className() + "' in object creation"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (targetClass.isEnum()) {
            diagnostics.add(error(expression.classNameSpan(), "cannot create an instance of enum '"
                    + targetClass.sourceName() + "'"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (!targetClass.matchesAnonymousDiamondArguments(referenceType)) {
            diagnostics.add(error(expression.classNameSpan(),
                    "anonymous diamond construction conflicts with its inferred body type"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }

        ExpressionTypePlan enclosingPlan = selected.enclosingInstance().orElse(null);
        TypedValue explicitEnclosing = enclosingPlan == null ? null
                : lowerExpression(enclosingPlan.expression(), enclosingPlan.expectedType());
        if (explicitEnclosing != null && !explicitEnclosing.type().equals(enclosingPlan.type())) {
            explicitEnclosing = new TypedValue(enclosingPlan.type(), explicitEnclosing.operand());
        }
        if (explicitEnclosing != null) {
            IrOperand enclosingOperand = requireValue(explicitEnclosing,
                    explicitEnclosing.type(),
                    enclosingPlan.expression().span(),
                    "qualified member-class enclosing instance");
            emitNullCheck(enclosingOperand, enclosingPlan.expression().span());
        }
        LoweredInvocationArguments arguments = lowerInvocationArguments(selected,
                "constructor argument");

        ensureTypeInitialized(targetClass.name(), expression.span());
        IrValueReference result = newValue(referenceType, expression.span());
        emitCall(new IrAllocateInstruction(result, targetClass.name(), expression.span()),
                expression.span());
        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocation.constructedType = referenceType;
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        List<IrOperand> operands = new ArrayList<>();
        operands.add(result);
        TypeSymbol.AnonymousConstructorForwarding anonymous = targetClass
                .anonymousConstructorForwarding(constructor).orElse(null);
        if (anonymous != null && targetClass.enclosingInstanceField().isPresent()) {
            IrType required = targetClass.enclosingInstanceField().orElseThrow().type();
            TypedValue enclosing = enclosingInstanceFor(targetClass.enclosingType().orElseThrow(),
                    expression.span());
            if (enclosing == null) {
                diagnostics.add(error(expression.classNameSpan(), "anonymous class requires an enclosing "
                        + "instance of '" + required.displayName() + "'"));
                operands.add(new IrNull(required, expression.span()));
            } else {
                IrOperand captured = assignmentValue(enclosing, required, expression.span(),
                        "anonymous lexical enclosing instance");
                operands.add(captured);
                markEscaped(captured, "allocation is retained as an enclosing instance by '"
                        + targetClass.sourceName() + "'");
            }
        } else if (anonymous == null && constructor.enclosingInstanceType().isPresent()) {
            IrType required = constructor.enclosingInstanceType().orElseThrow();
            TypedValue enclosing = explicitEnclosing != null ? explicitEnclosing
                    : enclosingInstanceFor(targetClass.enclosingType().orElseThrow(), expression.span());
            if (enclosing == null) {
                diagnostics.add(error(expression.classNameSpan(), "member class '"
                        + targetClass.sourceName() + "' requires an enclosing instance"));
                operands.add(new IrNull(required, expression.span()));
            } else {
                IrOperand captured = assignmentValue(enclosing, required, expression.span(),
                        "enclosing instance");
                operands.add(captured);
                markEscaped(captured, "allocation is retained as an enclosing instance by '"
                        + targetClass.sourceName() + "'");
            }
        }
        operands.addAll(constructionCaptureOperands(targetClass, referenceType, expression.span()));
        if (anonymous != null && constructor.enclosingInstanceType().isPresent()) {
            IrType required = constructor.enclosingInstanceType().orElseThrow();
            TypedValue superEnclosing = explicitEnclosing;
            if (superEnclosing == null && required.isNominalReference()) {
                TypeSymbol requiredOwner = hierarchy.type(required.referenceName()).orElse(null);
                if (requiredOwner != null) {
                    superEnclosing = enclosingInstanceFor(requiredOwner, expression.span());
                }
            }
            if (superEnclosing == null) {
                diagnostics.add(error(expression.classNameSpan(), "anonymous superclass constructor "
                        + "requires an enclosing instance of '" + required.displayName() + "'"));
                operands.add(new IrNull(required, expression.span()));
            } else {
                IrOperand enclosing = assignmentValue(superEnclosing, required, expression.span(),
                        "anonymous superclass enclosing instance");
                operands.add(enclosing);
                markEscaped(enclosing, "allocation is retained as the anonymous superclass "
                        + "enclosing instance");
            }
        }
        operands.addAll(arguments.operands());
        markConstructorPublications(constructor, targetClass, arguments.values());
        emitConstructorCallWithRollback(new IrCallInstruction(Optional.empty(), constructor.linkageName(),
                IrType.VOID, operands, IrCallKind.DIRECT, Optional.empty(),
                selected.inference().substitutions(), expression.span()), result,
                expression.span());
        if (escapeSummaries.summary(constructor).thisEscapes()) {
            allocation.escape("allocation escapes from constructor '"
                    + targetClass.name() + "'");
        }
        for (TypeSymbol constructedType = targetClass.superclass().orElse(null);
             constructedType != null;
             constructedType = constructedType.superclass().orElse(null)) {
            if (constructedType.constructors().stream()
                    .anyMatch(candidate -> escapeSummaries.summary(candidate).thisEscapes())) {
                allocation.escape("allocation escapes from constructor '"
                        + constructedType.name() + "'");
                break;
            }
        }
        recordConstructorBorrows(constructor, allocation, arguments.values());
        return new TypedValue(referenceType, result);
    }

    private TypedValue lowerNew(NewExpression expression) {
        markAttachedOwnedFieldLoansUncertain(
                "cannot prove object construction before private-field detachment is non-reentrant");
        TypedValue explicitEnclosing = expression.enclosingInstance()
                .map(this::lowerExpression).orElse(null);
        if (explicitEnclosing != null && explicitEnclosing.type().isNominalReference()) {
            IrOperand qualifier = requireValue(explicitEnclosing, explicitEnclosing.type(),
                    expression.enclosingInstance().orElseThrow().span(),
                    "qualified member-class enclosing instance");
            emitNullCheck(qualifier, expression.enclosingInstance().orElseThrow().span());
        }
        List<TypedValue> arguments = expression.arguments().stream().map(this::lowerExpression).toList();
        TypeSymbol targetClass;
        TypeResolver.Resolution resolution = null;
        IrType referenceType;
        if (explicitEnclosing != null && explicitEnclosing.type().isNominalReference()) {
            TypeSymbol enclosingType = hierarchy.type(explicitEnclosing.type().referenceName()).orElse(null);
            targetClass = enclosingType == null ? null
                    : lookupMemberType(enclosingType, expression.className());
            if (targetClass == null) {
                referenceType = IrType.reference(expression.className());
            } else {
                IrType ownerView = targetClass.isInnerClass()
                        ? hierarchy.exactClassSupertype(explicitEnclosing.type(),
                                targetClass.enclosingType().orElseThrow())
                        .orElse(explicitEnclosing.type())
                        : explicitEnclosing.type();
                List<IrType> typeArguments = new ArrayList<>(ownerView.typeArguments());
                typeArguments.addAll(expression.classType().typeArguments().stream()
                        .map(this::resolveType).toList());
                referenceType = IrType.reference(targetClass.name(), typeArguments);
            }
        } else {
            referenceType = resolveType(expression.classType());
            resolution = hierarchy.resolveType(expression.className(), currentClass,
                    expression.classNameSpan());
            targetClass = resolution.type().orElse(null);
        }
        if (referenceType.isTypeParameter()) {
            diagnostics.add(error(expression.classNameSpan(), "cannot create an instance of type parameter '"
                    + referenceType.displayName() + "'; new T() is not supported"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        String resolvedName = targetClass == null ? expression.className() : targetClass.name();
        if (!referenceType.isNominalReference()) {
            referenceType = IrType.reference(resolvedName);
        }
        if (resolution != null && resolution.ambiguous()) {
            diagnostics.add(error(expression.classNameSpan(), "ambiguous class '" + expression.className()
                    + "' in object creation"));
        }
        if (targetClass == null) {
            diagnostics.add(error(expression.classNameSpan(),
                    "unknown class '" + expression.className() + "' in object creation"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (explicitEnclosing != null
                && expression.classType().typeArguments().size()
                != targetClass.declaredTypeParameters().size()) {
            diagnostics.add(error(expression.classNameSpan(), "generic member class '"
                    + targetClass.sourceName() + "' expects "
                    + targetClass.declaredTypeParameters().size() + " member type argument(s) but received "
                    + expression.classType().typeArguments().size()));
        }
        if (containsWildcard(referenceType)) {
            diagnostics.add(error(expression.classNameSpan(),
                    "cannot create an instance of wildcard-parameterized type '"
                            + typeName(referenceType) + "'"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (resolution != null && resolution.inaccessible()) {
            diagnostics.add(error(expression.classNameSpan(), "class '" + targetClass.name()
                    + "' is not accessible from package '" + currentClass.packageName() + "'"));
        }
        if (!targetClass.isTopLevel() && !isAccessible(targetClass.declaration().accessModifier(),
                targetClass.enclosingType().orElseThrow().name(),
                explicitEnclosing == null ? currentClass.name() : explicitEnclosing.type().referenceName(),
                false)) {
            diagnostics.add(error(expression.classNameSpan(), "member class '" + targetClass.sourceName()
                    + "' is not accessible"));
        }
        if (targetClass.isInterface()) {
            diagnostics.add(error(expression.classNameSpan(),
                    "cannot create an instance of interface '" + expression.className() + "'"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (targetClass.isEnum()) {
            diagnostics.add(error(expression.classNameSpan(), "cannot create an instance of enum '"
                    + targetClass.sourceName() + "'"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }
        if (targetClass.isAbstract()) {
            diagnostics.add(error(expression.classNameSpan(),
                    "cannot create an instance of abstract class '" + expression.className() + "'"));
            return new TypedValue(referenceType, new IrNull(referenceType, expression.span()));
        }

        List<CallableSymbol> accessibleConstructors = preferEligible(
                hierarchy.constructors(referenceType), candidate -> isAccessible(
                        candidate.accessModifier(), targetClass.name(), targetClass.name(), false));
        CallableSymbol constructor = selectOverload(accessibleConstructors, arguments,
                "constructor for class '" + targetClass.name() + "'", expression.span());

        if (constructor != null && isStringStorageConstructor(targetClass, constructor)) {
            return lowerStringStorageConstructor(expression, referenceType, constructor, arguments);
        }

        ensureTypeInitialized(targetClass.name(), expression.span());
        IrValueReference result = newValue(referenceType, expression.span());
        emitCall(new IrAllocateInstruction(result, targetClass.name(), expression.span()),
                expression.span());
        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocation.constructedType = referenceType;
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        if (constructor == null) {
            return new TypedValue(referenceType, result);
        }
        checkCheckedExceptions(constructor, expression.span());
        if (!isAccessible(constructor.accessModifier(), targetClass.name(), targetClass.name(), false)) {
            diagnostics.add(error(expression.classNameSpan(),
                    constructor.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                            ? "constructor for class '" + targetClass.name() + "' is private"
                            : "constructor for class '" + targetClass.name() + "' is not accessible"));
        }
        List<IrOperand> operands = new ArrayList<>();
        operands.add(result);
        if (targetClass.isInnerClass()) {
            TypeSymbol requiredEnclosingSymbol = targetClass.enclosingType().orElseThrow();
            IrType requiredEnclosing = constructor.enclosingInstanceType()
                    .orElse(requiredEnclosingSymbol.selfType());
            TypedValue enclosing = explicitEnclosing != null
                    ? explicitEnclosing : enclosingInstanceFor(requiredEnclosingSymbol, expression.span());
            if (enclosing == null || !hierarchy.isAssignable(requiredEnclosing, enclosing.type())) {
                diagnostics.add(error(expression.classNameSpan(), "member class '" + targetClass.sourceName()
                        + "' requires an enclosing instance of '" + requiredEnclosingSymbol.sourceName() + "'"));
                operands.add(new IrNull(requiredEnclosing, expression.span()));
            } else {
                IrOperand captured = requireValue(enclosing, requiredEnclosing,
                        expression.span(), "enclosing instance");
                operands.add(captured);
                markEscaped(captured, "allocation is retained as an enclosing instance by '"
                        + targetClass.sourceName() + "'");
            }
        } else if (explicitEnclosing != null) {
            diagnostics.add(error(expression.classNameSpan(), "static nested class '"
                    + targetClass.sourceName() + "' cannot be created with an enclosing instance"));
        }
        operands.addAll(constructionCaptureOperands(targetClass, referenceType, expression.span()));
        operands.addAll(checkArguments(expression.className(), expression.arguments(), arguments,
                constructor.parameterTypes(), true, expression.span()));
        markConstructorPublications(constructor, targetClass, arguments);
        emitConstructorCallWithRollback(new IrCallInstruction(Optional.empty(),
                constructor.linkageName(), IrType.VOID, operands, expression.span()), result,
                expression.span());
        if (escapeSummaries.summary(constructor).thisEscapes()) {
            allocation.escape("allocation escapes from constructor '"
                    + targetClass.name() + "'");
        }
        for (TypeSymbol constructedType = targetClass.superclass().orElse(null);
             constructedType != null;
             constructedType = constructedType.superclass().orElse(null)) {
            if (constructedType.constructors().stream()
                    .anyMatch(candidate -> escapeSummaries.summary(candidate).thisEscapes())) {
                allocation.escape("allocation escapes from constructor '"
                        + constructedType.name() + "'");
                break;
            }
        }
        recordConstructorBorrows(constructor, allocation, arguments);
        return new TypedValue(referenceType, result);
    }

    private static boolean isStringStorageConstructor(TypeSymbol targetClass,
                                                      CallableSymbol constructor) {
        if (!targetClass.name().equals("ironwood.lang.String")) {
            return false;
        }
        List<IrType> parameters = constructor.parameterTypes();
        return parameters.equals(List.of(IrType.reference("ironwood.lang.String")))
                || parameters.equals(List.of(IrType.array(IrType.I8)))
                || parameters.equals(List.of(IrType.array(IrType.U16)))
                || parameters.equals(List.of(IrType.array(IrType.U16), IrType.I32, IrType.I32));
    }

    private TypedValue lowerStringStorageConstructor(NewExpression expression, IrType referenceType,
                                                     CallableSymbol constructor,
                                                     List<TypedValue> arguments) {
        checkCheckedExceptions(constructor, expression.span());
        ensureTypeInitialized("ironwood.lang.String", expression.span());
        IrValueReference result = newValue(referenceType, expression.span());
        List<IrType> parameterTypes = constructor.parameterTypes();
        IrOperand source = requireValue(arguments.getFirst(), parameterTypes.getFirst(),
                expression.arguments().getFirst().span(), "String constructor argument");
        emitNullCheck(source, expression.arguments().getFirst().span());

        if (parameterTypes.equals(List.of(IrType.reference("ironwood.lang.String")))) {
            emitCall(new IrStringCopyInstruction(result, source, expression.span()),
                    expression.span());
        } else if (parameterTypes.size() == 1) {
            IrValueReference length = newValue(IrType.I32, expression.span());
            currentBlock.addInstruction(new IrArrayLengthInstruction(length, source,
                    expression.span()));
            if (source.type().equals(IrType.array(IrType.I8))) {
                emitCall(new IrStringFromUtf8Instruction(result, source, length, expression.span()),
                        expression.span());
            } else {
                emitCall(new IrStringFromCharRangeInstruction(result, source,
                        new IrConstant(IrType.I32, 0, expression.span()), length,
                        expression.span()), expression.span());
            }
        } else {
            IrOperand offset = requireValue(arguments.get(1), IrType.I32,
                    expression.arguments().get(1).span(), "String constructor offset");
            IrOperand count = requireValue(arguments.get(2), IrType.I32,
                    expression.arguments().get(2).span(), "String constructor count");
            emitStringConstructorRangeCheck(source, offset, count, expression.span());
            emitCall(new IrStringFromCharRangeInstruction(result, source, offset, count,
                    expression.span()), expression.span());
        }

        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        return new TypedValue(referenceType, result);
    }

    private void emitStringConstructorRangeCheck(IrOperand array, IrOperand offset,
                                                 IrOperand count, SourceSpan span) {
        IrValueReference length = newValue(IrType.I32, span);
        currentBlock.addInstruction(new IrArrayLengthInstruction(length, array, span));
        IrConstant zero = new IrConstant(IrType.I32, 0, span);
        IrValueReference offsetNonnegative = newValue(IrType.I1, span);
        IrValueReference countNonnegative = newValue(IrType.I1, span);
        IrValueReference countWithinLength = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(offsetNonnegative,
                IrBinaryOperator.SIGNED_GREATER_EQUAL, offset, zero, span));
        currentBlock.addInstruction(new IrBinaryInstruction(countNonnegative,
                IrBinaryOperator.SIGNED_GREATER_EQUAL, count, zero, span));
        currentBlock.addInstruction(new IrBinaryInstruction(countWithinLength,
                IrBinaryOperator.SIGNED_LESS_EQUAL, count, length, span));
        IrValueReference remaining = newValue(IrType.I32, span);
        currentBlock.addInstruction(new IrBinaryInstruction(remaining,
                IrBinaryOperator.SUBTRACT, length, count, span));
        IrValueReference offsetWithinRange = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(offsetWithinRange,
                IrBinaryOperator.SIGNED_LESS_EQUAL, offset, remaining, span));
        IrValueReference nonnegative = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(nonnegative,
                IrBinaryOperator.BITWISE_AND, offsetNonnegative, countNonnegative, span));
        IrValueReference within = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(within,
                IrBinaryOperator.BITWISE_AND, countWithinLength, offsetWithinRange, span));
        IrValueReference valid = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(valid,
                IrBinaryOperator.BITWISE_AND, nonnegative, within, span));
        emitRuntimeSafetyBranch(valid, "string.constructor.range.valid",
                "string.constructor.range.failure",
                "ironwood.lang.StringIndexOutOfBoundsException",
                "String(char[], int, int) bounds", span);
    }

    private TypeSymbol lookupMemberType(TypeSymbol owner, String simpleName) {
        for (TypeSymbol current = owner; current != null; current = current.superclass().orElse(null)) {
            TypeSymbol member = current.declaredMemberType(simpleName).orElse(null);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    private TypedValue enclosingInstanceFor(TypeSymbol required, SourceSpan span) {
        if (function.isStatic() || thisOperand == null) {
            return null;
        }
        TypeSymbol current;
        IrOperand operand;
        if (evaluatingConstructorArguments && currentClass.isInnerClass()
                && enclosingInstanceOperand != null) {
            current = currentClass.enclosingType().orElseThrow();
            operand = enclosingInstanceOperand;
        } else {
            current = currentClass;
            operand = thisOperand;
        }
        while (current != null) {
            Optional<IrType> projected = hierarchy.exactClassSupertype(operand.type(), required);
            if (projected.isPresent()) {
                IrType exactRequired = projected.orElseThrow();
                return new TypedValue(exactRequired,
                        convertReference(operand, exactRequired, span));
            }
            if (!current.isInnerClass()) {
                return null;
            }
            var field = current.enclosingInstanceField().orElseThrow();
            IrValueReference loaded = newValue(field.type(), span);
            currentBlock.addInstruction(new IrFieldLoadInstruction(loaded, operand, field, span));
            operand = loaded;
            current = current.enclosingType().orElse(null);
        }
        return null;
    }

    private TypedValue lowerInstanceOf(InstanceOfExpression expression) {
        TypedValue value = lowerExpression(expression.operand());
        if (expression.targetType().kind() == TypeName.Kind.ARRAY) {
            IrType targetType = resolveType(expression.targetType());
            if (!isReifiableType(targetType)) {
                diagnostics.add(error(expression.targetType().span(),
                        "array instanceof target must be reifiable; concrete generic arguments, "
                                + "bounded wildcards, and type parameters have no runtime identity"));
            }
            if (!value.type().isReference() && !value.type().equals(IrType.NULL)) {
                diagnostics.add(error(expression.operatorSpan(),
                        "left operand of instanceof must be an object reference or null, not "
                                + typeName(value.type())));
                bindPattern(expression, targetType, new IrNull(targetType, expression.span()));
                return new TypedValue(IrType.I1,
                        new IrConstant(IrType.I1, 0, expression.span()));
            }
            IrOperand operand = value.operand() == null
                    ? new IrNull(IrType.NULL, expression.operand().span()) : value.operand();
            IrValueReference result = newValue(IrType.I1, expression.span());
            currentBlock.addInstruction(new IrArrayTypeTestInstruction(result, operand,
                    targetType.erasure(), expression.span()));
            bindPattern(expression, targetType, operand);
            return new TypedValue(IrType.I1, result);
        }
        if (expression.targetType().kind() != TypeName.Kind.REFERENCE) {
            IrType targetType = resolveType(expression.targetType());
            diagnostics.add(error(expression.targetType().span(), expression.binding().isPresent()
                    ? "instanceof type pattern target must be a reference type, not "
                    + typeName(targetType)
                    : "instanceof target must be a class, interface, or array type, not "
                    + typeName(targetType)));
            bindPattern(expression, IrType.reference("ironwood.lang.Object"),
                    new IrNull(IrType.reference("ironwood.lang.Object"), expression.span()));
            return new TypedValue(IrType.I1,
                    new IrConstant(IrType.I1, 0, expression.span()));
        }
        boolean parameterized = !expression.targetType().typeArguments().isEmpty();
        boolean reifiable = parameterized && expression.targetType().typeArguments().stream()
                .allMatch(argument -> argument.kind() == TypeName.Kind.WILDCARD
                        && argument.wildcardKind() == TypeName.WildcardKind.UNBOUNDED);
        if (parameterized && !reifiable) {
            diagnostics.add(error(expression.targetType().span(),
                    "instanceof target must be reifiable; use unbounded wildcards for generic types"));
        }
        if (currentClass.typeParameter(expression.targetType().referenceName()).isPresent()) {
            diagnostics.add(error(expression.targetType().span(),
                    "type-parameter instanceof targets are not supported; runtime generic types are erased"));
            bindPattern(expression, IrType.reference("ironwood.lang.Object"),
                    new IrNull(IrType.reference("ironwood.lang.Object"), expression.span()));
            return new TypedValue(IrType.I1, new IrConstant(IrType.I1, 0, expression.span()));
        }
        TypeResolver.Resolution resolution = hierarchy.resolveType(expression.targetType().referenceName(), currentClass,
                expression.targetType().span());
        TypeSymbol target = resolution.type().orElse(null);
        if (resolution.ambiguous()) {
            diagnostics.add(error(expression.targetType().span(),
                    "ambiguous type '" + expression.targetType().referenceName() + "' in instanceof test"));
        }
        if (target == null) {
            diagnostics.add(error(expression.targetType().span(),
                    "unknown type '" + expression.targetType().referenceName() + "' in instanceof test"));
        } else if (target.typeParameters().size() > 0
                && expression.targetType().typeArguments().isEmpty()) {
            diagnostics.add(error(expression.targetType().span(), "raw generic type '"
                    + target.name() + "' is not supported as an instanceof target"));
        }
        if (target != null && resolution.inaccessible()) {
            diagnostics.add(error(expression.targetType().span(), "type '" + target.name()
                    + "' is not accessible from package '" + currentClass.packageName() + "'"));
        }
        boolean permittedArrayTest = value.type().isArray() && target != null
                && target.name().equals("ironwood.lang.Object");
        if (!value.type().isReference() && !value.type().equals(IrType.NULL)
                && !permittedArrayTest) {
            diagnostics.add(error(expression.operatorSpan(),
                    "left operand of instanceof must be an object reference or null, not "
                            + typeName(value.type())));
            IrType bindingType = resolveType(expression.targetType());
            bindPattern(expression, bindingType,
                    new IrNull(bindingType, expression.span()));
            return new TypedValue(IrType.I1, new IrConstant(IrType.I1, 0, expression.span()));
        }
        if (target == null) {
            bindPattern(expression, IrType.reference("ironwood.lang.Object"),
                    new IrNull(IrType.reference("ironwood.lang.Object"), expression.span()));
            return new TypedValue(IrType.I1, new IrConstant(IrType.I1, 0, expression.span()));
        }
        IrOperand operand = value.operand() == null
                ? new IrNull(IrType.NULL, expression.operand().span()) : value.operand();
        IrValueReference result = newValue(IrType.I1, expression.span());
        currentBlock.addInstruction(new IrInstanceOfInstruction(result, operand,
                target.name(), target.typeId(), expression.span()));
        bindPattern(expression, resolveType(expression.targetType()), operand);
        return new TypedValue(IrType.I1, result);
    }

    private void bindPattern(InstanceOfExpression expression, IrType targetType,
                             IrOperand testedOperand) {
        expression.binding().ifPresent(binding -> {
            if (binding.name().equals("_")) {
                diagnostics.add(error(binding.nameSpan(),
                        "unnamed pattern variables are not supported; use a named binding"));
            }
            LocalSymbol existing = resolve(binding.name());
            if (existing != null) {
                diagnostics.add(error(binding.nameSpan(), "pattern variable '" + binding.name()
                        + "' conflicts with an active local or parameter"));
            }
            IrType bindingType = targetType.isReference()
                    ? targetType : IrType.reference("ironwood.lang.Object");
            IrOperand value;
            if (testedOperand == null || testedOperand.type().equals(IrType.NULL)) {
                value = new IrNull(bindingType, binding.nameSpan());
            } else if (testedOperand.type().isReference()) {
                value = convertReference(testedOperand, bindingType, binding.nameSpan());
            } else {
                value = new IrNull(bindingType, binding.nameSpan());
            }
            LocalSymbol symbol = new LocalSymbol(nextSymbolId++, binding.name(), bindingType,
                    binding.isFinal(), controlFlowDepth,
                    currentClass.variableAt(binding.nameSpan()).orElse(null));
            patternLocals.put(expression, new PatternLocal(symbol, value));
        });
    }

    private TypedValue lowerIntegerLiteral(IntegerLiteralExpression expression) {
        IntegerLiteralDecoder.Result result = IntegerLiteralDecoder.decode(expression.text(), false);
        if (!result.successful()) {
            diagnostics.add(error(expression.span(), result.error()));
            return new TypedValue(result.type(), defaultValue(result.type(), expression.span()));
        }
        Number value;
        if (result.type().equals(IrType.I64)) {
            value = Long.valueOf(result.value().longValue());
        } else {
            value = Integer.valueOf(result.value().intValue());
        }
        return new TypedValue(result.type(),
                new IrConstant(result.type(), value, expression.span()), result.value());
    }

    private TypedValue lowerFloatingLiteral(FloatingLiteralExpression expression) {
        String text = expression.text().replace("_", "");
        char suffix = text.charAt(text.length() - 1);
        boolean single = suffix == 'f' || suffix == 'F';
        if (single || suffix == 'd' || suffix == 'D') {
            text = text.substring(0, text.length() - 1);
        }
        try {
            if (single) {
                float value = Float.parseFloat(text);
                return new TypedValue(IrType.F32,
                        new IrConstant(IrType.F32, value, expression.span()));
            }
            double value = Double.parseDouble(text);
            return new TypedValue(IrType.F64,
                    new IrConstant(IrType.F64, value, expression.span()));
        } catch (NumberFormatException failure) {
            diagnostics.add(error(expression.span(), "malformed floating-point literal '"
                    + expression.text() + "'"));
            return new TypedValue(single ? IrType.F32 : IrType.F64,
                    defaultValue(single ? IrType.F32 : IrType.F64, expression.span()));
        }
    }

    private TypedValue lowerUnary(UnaryExpression expression) {
        if (expression.operator() == ironwood.compiler.ast.UnaryOperator.NEGATE
                && expression.operand() instanceof IntegerLiteralExpression literal) {
            IntegerLiteralDecoder.Result ordinary = IntegerLiteralDecoder.decode(
                    literal.text(), false);
            IntegerLiteralDecoder.Result negated = IntegerLiteralDecoder.decode(
                    literal.text(), true);
            if (!ordinary.successful() && negated.successful()) {
                Number value;
                if (negated.type().equals(IrType.I64)) {
                    value = Long.valueOf(negated.value().longValue());
                } else {
                    value = Integer.valueOf(negated.value().intValue());
                }
                return new TypedValue(negated.type(),
                        new IrConstant(negated.type(), value, expression.span()), negated.value());
            }
        }

        TypedValue operand = lowerExpression(expression.operand());
        if (expression.operator() == ironwood.compiler.ast.UnaryOperator.NOT) {
            if (!operand.type().equals(IrType.I1)) {
                diagnostics.add(error(expression.operatorSpan(), "operator '!' requires boolean operand"));
            }
            IrOperand value = requireValue(operand, IrType.I1, expression.operand().span(), "unary operator");
            IrValueReference result = newValue(IrType.I1, expression.span());
            currentBlock.addInstruction(new IrUnaryInstruction(result, IrUnaryOperator.NOT, value,
                    expression.span()));
            return new TypedValue(IrType.I1, result);
        }
        boolean integralOnly = expression.operator()
                == ironwood.compiler.ast.UnaryOperator.BITWISE_COMPLEMENT;
        if (!operand.type().isNumeric() || integralOnly && !operand.type().isIntegral()) {
            diagnostics.add(error(expression.operatorSpan(),
                    "operator '" + unaryOperatorText(expression.operator()) + "' requires "
                            + (integralOnly ? "integral" : "numeric") + " operand"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        IrType promoted = PrimitiveConversions.unaryPromotion(operand.type());
        IrOperand value = convertNumeric(operand.operand(), promoted, expression.operand().span());
        if (expression.operator() == ironwood.compiler.ast.UnaryOperator.POSITIVE) {
            return new TypedValue(promoted, value, operand.integralConstant());
        }
        IrValueReference result = newValue(promoted, expression.span());
        IrUnaryOperator operator = switch (expression.operator()) {
            case POSITIVE -> throw new IllegalStateException("unary plus handled without an instruction");
            case NEGATE -> IrUnaryOperator.NEGATE;
            case NOT -> IrUnaryOperator.NOT;
            case BITWISE_COMPLEMENT -> IrUnaryOperator.BITWISE_COMPLEMENT;
        };
        currentBlock.addInstruction(new IrUnaryInstruction(result, operator, value, expression.span()));
        BigInteger constant = operand.integralConstant();
        if (constant != null) {
            constant = expression.operator() == ironwood.compiler.ast.UnaryOperator.NEGATE
                    ? constant.negate() : constant.not();
            constant = wrapIntegral(constant, promoted);
        }
        return new TypedValue(promoted, result, constant);
    }

    private TypedValue lowerBinary(BinaryExpression expression) {
        if (expression.operator() == BinaryOperator.LOGICAL_AND
                || expression.operator() == BinaryOperator.LOGICAL_OR) {
            return lowerShortCircuit(expression);
        }
        if (expression.operator() == BinaryOperator.ADD
                && plannedExpressionType(expression).filter(STRING_TYPE::equals).isPresent()) {
            return lowerStringConcatenation(expression);
        }
        TypedValue left = lowerExpression(expression.left());
        TypedValue right = lowerExpression(expression.right());
        return emitBinary(expression.operator(), left, right, expression.left().span(),
                expression.right().span(), expression.operatorSpan(), expression.span());
    }

    private TypedValue emitBinary(BinaryOperator operator, TypedValue left, TypedValue right,
                                  SourceSpan leftSpan, SourceSpan rightSpan,
                                  SourceSpan operatorSpan, SourceSpan expressionSpan) {
        if (operator == BinaryOperator.ADD
                && (left.type().equals(STRING_TYPE) || right.type().equals(STRING_TYPE))) {
            return emitStringConcatenation(List.of(left, right), expressionSpan);
        }
        boolean arithmetic = operator == BinaryOperator.ADD
                || operator == BinaryOperator.SUBTRACT
                || operator == BinaryOperator.MULTIPLY
                || operator == BinaryOperator.DIVIDE
                || operator == BinaryOperator.REMAINDER;
        boolean shift = operator == BinaryOperator.SHIFT_LEFT
                || operator == BinaryOperator.SHIFT_RIGHT
                || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT;
        boolean bitwise = operator == BinaryOperator.BITWISE_AND
                || operator == BinaryOperator.BITWISE_XOR
                || operator == BinaryOperator.BITWISE_OR;
        boolean ordering = operator == BinaryOperator.LESS
                || operator == BinaryOperator.LESS_EQUAL
                || operator == BinaryOperator.GREATER
                || operator == BinaryOperator.GREATER_EQUAL;

        if (shift) {
            if (!left.type().isIntegral() || !right.type().isIntegral()) {
                diagnostics.add(error(operatorSpan, "operator '" + operatorText(operator)
                        + "' requires integral operands"));
                return new TypedValue(IrType.I32, defaultValue(IrType.I32, expressionSpan));
            }
            IrType leftType = PrimitiveConversions.unaryPromotion(left.type());
            IrType rightType = PrimitiveConversions.unaryPromotion(right.type());
            IrOperand leftOperand = convertNumeric(left.operand(), leftType, leftSpan);
            IrOperand rightOperand = convertNumeric(right.operand(), rightType, rightSpan);
            int distanceMask = leftType.equals(IrType.I64) ? 63 : 31;
            IrValueReference masked = newValue(rightType, rightSpan);
            currentBlock.addInstruction(new IrBinaryInstruction(masked, IrBinaryOperator.BITWISE_AND,
                    rightOperand, new IrConstant(rightType, distanceMask, rightSpan), rightSpan));
            IrOperand shiftDistance = convertNumeric(masked, leftType, rightSpan);
            IrValueReference result = newValue(leftType, expressionSpan);
            currentBlock.addInstruction(new IrBinaryInstruction(result, irOperator(operator),
                    leftOperand, shiftDistance, expressionSpan));
            BigInteger constant = evaluateIntegralBinary(operator, left, right, leftType);
            return new TypedValue(leftType, result, constant);
        }
        if (arithmetic || ordering) {
            if (!left.type().isNumeric() || !right.type().isNumeric()) {
                diagnostics.add(error(operatorSpan, "operator '" + operatorText(operator)
                        + "' requires int operands or other numeric operands"));
                return new TypedValue(ordering ? IrType.I1 : IrType.I32,
                        defaultValue(ordering ? IrType.I1 : IrType.I32, expressionSpan));
            }
            IrType operandType = PrimitiveConversions.binaryPromotion(left.type(), right.type());
            IrOperand leftOperand = convertNumeric(left.operand(), operandType, leftSpan);
            IrOperand rightOperand = convertNumeric(right.operand(), operandType, rightSpan);
            if ((operator == BinaryOperator.DIVIDE || operator == BinaryOperator.REMAINDER)
                    && operandType.isIntegral()) {
                return lowerCheckedDivision(operator, operandType, leftOperand, rightOperand,
                        expressionSpan, evaluateIntegralBinary(operator, left, right, operandType));
            }
            IrType resultType = ordering ? IrType.I1 : operandType;
            IrValueReference result = newValue(resultType, expressionSpan);
            currentBlock.addInstruction(new IrBinaryInstruction(result, irOperator(operator),
                    leftOperand, rightOperand, expressionSpan));
            BigInteger constant = ordering || operandType.isFloating() ? null
                    : evaluateIntegralBinary(operator, left, right, operandType);
            return new TypedValue(resultType, result, constant);
        }
        if (bitwise) {
            if (left.type().equals(IrType.I1) && right.type().equals(IrType.I1)) {
                IrValueReference result = newValue(IrType.I1, expressionSpan);
                currentBlock.addInstruction(new IrBinaryInstruction(result, irOperator(operator),
                        left.operand(), right.operand(), expressionSpan));
                return new TypedValue(IrType.I1, result);
            }
            if (!left.type().isIntegral() || !right.type().isIntegral()) {
                diagnostics.add(error(operatorSpan, "operator '" + operatorText(operator)
                        + "' requires two integral operands or two boolean operands"));
                return new TypedValue(IrType.I32, defaultValue(IrType.I32, expressionSpan));
            }
            IrType operandType = PrimitiveConversions.binaryPromotion(left.type(), right.type());
            IrOperand leftOperand = convertNumeric(left.operand(), operandType, leftSpan);
            IrOperand rightOperand = convertNumeric(right.operand(), operandType, rightSpan);
            IrValueReference result = newValue(operandType, expressionSpan);
            currentBlock.addInstruction(new IrBinaryInstruction(result, irOperator(operator),
                    leftOperand, rightOperand, expressionSpan));
            return new TypedValue(operandType, result,
                    evaluateIntegralBinary(operator, left, right, operandType));
        }

        IrType operandType;
        if (left.type().isNumeric() && right.type().isNumeric()) {
            operandType = PrimitiveConversions.binaryPromotion(left.type(), right.type());
        } else {
            operandType = equalityOperandType(left.type(), right.type());
            if (!validEquality(left.type(), right.type())) {
                diagnostics.add(error(operatorSpan, "operator '" + operatorText(operator)
                        + "' requires operands of the same non-void type or numerically promotable operands"));
            }
        }
        IrOperand leftOperand = requireValue(left, operandType, leftSpan, "binary operator");
        IrOperand rightOperand = requireValue(right, operandType, rightSpan, "binary operator");
        IrValueReference result = newValue(IrType.I1, expressionSpan);
        currentBlock.addInstruction(new IrBinaryInstruction(result, irOperator(operator),
                leftOperand, rightOperand, expressionSpan));
        return new TypedValue(IrType.I1, result);
    }

    private TypedValue lowerStringConcatenation(BinaryExpression expression) {
        String constant = constantStringExpression(expression);
        if (constant != null) {
            return new TypedValue(STRING_TYPE, stringPool.intern(constant, expression.span()));
        }
        List<Expression> expressions = new ArrayList<>();
        collectStringConcatenationParts(expression, expressions);
        List<TypedValue> values = new ArrayList<>();
        for (Expression part : expressions) {
            values.add(lowerExpression(part));
        }
        return emitStringConcatenation(values, expression.span());
    }

    private String constantStringExpression(Expression expression) {
        CompileTimeValue value = compileTimeValue(expression);
        return value != null && value.type().equals(STRING_TYPE)
                ? (String) value.value() : null;
    }

    private CompileTimeValue compileTimeValue(Expression expression) {
        if (expression instanceof StringLiteralExpression literal) {
            return new CompileTimeValue(STRING_TYPE, literal.value());
        }
        if (expression instanceof BooleanLiteralExpression literal) {
            return new CompileTimeValue(IrType.I1, literal.value());
        }
        if (expression instanceof CharacterLiteralExpression literal) {
            return new CompileTimeValue(IrType.U16,
                    BigInteger.valueOf(literal.value()));
        }
        if (expression instanceof IntegerLiteralExpression literal) {
            CaseConstant value = caseIntegerLiteral(literal, false);
            return value == null ? null : new CompileTimeValue(value.type(), value.value());
        }
        if (expression instanceof FloatingLiteralExpression literal) {
            String text = literal.text().replace("_", "");
            char suffix = text.charAt(text.length() - 1);
            boolean single = suffix == 'f' || suffix == 'F';
            if (single || suffix == 'd' || suffix == 'D') {
                text = text.substring(0, text.length() - 1);
            }
            try {
                return single
                        ? new CompileTimeValue(IrType.F32, Float.parseFloat(text))
                        : new CompileTimeValue(IrType.F64, Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expression instanceof NameExpression name) {
            if (resolve(name.name()) != null) {
                return null;
            }
            FieldSymbol field = hierarchy.resolveField(currentClass.selfType(), name.name())
                    .selected().orElse(null);
            if (field == null) {
                field = resolveStaticImportedField(name.name(), name.span(), false);
            }
            return constantFieldValue(field);
        }
        if (expression instanceof FieldAccessExpression access) {
            String qualifier = qualifiedName(access.receiver());
            TypeResolver.Resolution resolution = qualifier == null ? null
                    : hierarchy.resolveType(qualifier, currentClass, access.receiver().span());
            TypeSymbol owner = resolution == null ? null : resolution.type().orElse(null);
            FieldSymbol field = owner == null ? null
                    : hierarchy.resolveField(owner.selfType(), access.fieldName())
                    .selected().orElse(null);
            return constantFieldValue(field);
        }
        if (expression instanceof UnaryExpression unary) {
            if (unary.operator() == ironwood.compiler.ast.UnaryOperator.NEGATE
                    && unary.operand() instanceof IntegerLiteralExpression literal) {
                CaseConstant minimum = caseIntegerLiteral(literal, true);
                if (minimum != null) {
                    return new CompileTimeValue(minimum.type(), minimum.value());
                }
            }
            CompileTimeValue operand = compileTimeValue(unary.operand());
            return operand == null ? null : constantUnary(unary.operator(), operand);
        }
        if (expression instanceof BinaryExpression binary) {
            CompileTimeValue left = compileTimeValue(binary.left());
            if (left == null) {
                return null;
            }
            if (binary.operator() == BinaryOperator.LOGICAL_AND
                    && left.type().equals(IrType.I1) && !(Boolean) left.value()
                    || binary.operator() == BinaryOperator.LOGICAL_OR
                    && left.type().equals(IrType.I1) && (Boolean) left.value()) {
                return left;
            }
            CompileTimeValue right = compileTimeValue(binary.right());
            return right == null ? null : constantBinary(binary.operator(), left, right);
        }
        if (expression instanceof CastExpression cast) {
            CompileTimeValue operand = compileTimeValue(cast.operand());
            IrType target = resolveType(cast.targetType());
            return operand == null ? null : constantCast(operand, target);
        }
        if (expression instanceof ConditionalExpression conditional) {
            CompileTimeValue condition = compileTimeValue(conditional.condition());
            if (condition == null || !condition.type().equals(IrType.I1)) {
                return null;
            }
            return compileTimeValue((Boolean) condition.value()
                    ? conditional.whenTrue() : conditional.whenFalse());
        }
        return null;
    }

    private CompileTimeValue constantFieldValue(FieldSymbol field) {
        if (field == null || !field.isStatic() || !field.isFinal()
                || field.constantValue() == null) {
            return null;
        }
        ConstantValue value = field.constantValue();
        if (value.type().equals(STRING_TYPE) && value.stringValue() != null) {
            return new CompileTimeValue(STRING_TYPE, value.stringValue().value());
        }
        if (value.value() == null) {
            return null;
        }
        Object constant = value.type().equals(IrType.I1)
                ? value.value().intValue() != 0
                : value.type().isIntegral() ? BigInteger.valueOf(value.value().longValue())
                : value.value();
        return new CompileTimeValue(value.type(), constant);
    }

    private CompileTimeValue constantUnary(ironwood.compiler.ast.UnaryOperator operator,
                                           CompileTimeValue operand) {
        if (operator == ironwood.compiler.ast.UnaryOperator.NOT) {
            return operand.type().equals(IrType.I1)
                    ? new CompileTimeValue(IrType.I1, !(Boolean) operand.value()) : null;
        }
        if (!operand.type().isNumeric()) {
            return null;
        }
        IrType promoted = PrimitiveConversions.unaryPromotion(operand.type());
        CompileTimeValue value = constantCast(operand, promoted);
        if (value == null || operator == ironwood.compiler.ast.UnaryOperator.POSITIVE) {
            return value;
        }
        if (promoted.isFloating()) {
            double result = -((Number) value.value()).doubleValue();
            return new CompileTimeValue(promoted,
                    promoted.equals(IrType.F32) ? Float.valueOf((float) result)
                            : Double.valueOf(result));
        }
        BigInteger integer = (BigInteger) value.value();
        BigInteger result = operator == ironwood.compiler.ast.UnaryOperator.NEGATE
                ? integer.negate() : integer.not();
        return new CompileTimeValue(promoted, wrapIntegral(result, promoted));
    }

    private CompileTimeValue constantBinary(BinaryOperator operator,
                                            CompileTimeValue left,
                                            CompileTimeValue right) {
        if (operator == BinaryOperator.ADD
                && (left.type().equals(STRING_TYPE) || right.type().equals(STRING_TYPE))) {
            String leftText = constantString(left);
            String rightText = constantString(right);
            return leftText == null || rightText == null ? null
                    : new CompileTimeValue(STRING_TYPE, leftText + rightText);
        }
        if (operator == BinaryOperator.LOGICAL_AND || operator == BinaryOperator.LOGICAL_OR) {
            if (!left.type().equals(IrType.I1) || !right.type().equals(IrType.I1)) {
                return null;
            }
            boolean l = (Boolean) left.value();
            boolean r = (Boolean) right.value();
            return new CompileTimeValue(IrType.I1,
                    operator == BinaryOperator.LOGICAL_AND ? l && r : l || r);
        }
        if ((operator == BinaryOperator.BITWISE_AND || operator == BinaryOperator.BITWISE_XOR
                || operator == BinaryOperator.BITWISE_OR)
                && left.type().equals(IrType.I1) && right.type().equals(IrType.I1)) {
            boolean l = (Boolean) left.value();
            boolean r = (Boolean) right.value();
            return new CompileTimeValue(IrType.I1, switch (operator) {
                case BITWISE_AND -> l & r;
                case BITWISE_XOR -> l ^ r;
                case BITWISE_OR -> l | r;
                default -> throw new IllegalStateException();
            });
        }
        if (!left.type().isNumeric() || !right.type().isNumeric()) {
            if ((operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL)
                    && left.type().equals(STRING_TYPE) && right.type().equals(STRING_TYPE)) {
                boolean equal = left.value().equals(right.value());
                return new CompileTimeValue(IrType.I1,
                        operator == BinaryOperator.EQUAL ? equal : !equal);
            }
            return null;
        }
        if (operator == BinaryOperator.SHIFT_LEFT || operator == BinaryOperator.SHIFT_RIGHT
                || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT) {
            if (!left.type().isIntegral() || !right.type().isIntegral()) {
                return null;
            }
            IrType type = PrimitiveConversions.unaryPromotion(left.type());
            BigInteger l = (BigInteger) constantCast(left, type).value();
            int distance = ((BigInteger) constantCast(right,
                    PrimitiveConversions.unaryPromotion(right.type())).value()).intValue()
                    & (type.equals(IrType.I64) ? 63 : 31);
            long raw = l.longValue();
            long shifted = switch (operator) {
                case SHIFT_LEFT -> raw << distance;
                case SHIFT_RIGHT -> raw >> distance;
                case UNSIGNED_SHIFT_RIGHT -> type.equals(IrType.I64)
                        ? raw >>> distance : (int) raw >>> distance;
                default -> throw new IllegalStateException();
            };
            return new CompileTimeValue(type,
                    wrapIntegral(BigInteger.valueOf(shifted), type));
        }
        IrType promoted = PrimitiveConversions.binaryPromotion(left.type(), right.type());
        CompileTimeValue l = constantCast(left, promoted);
        CompileTimeValue r = constantCast(right, promoted);
        if (promoted.isFloating()) {
            double a = ((Number) l.value()).doubleValue();
            double b = ((Number) r.value()).doubleValue();
            if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL
                    || operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL
                    || operator == BinaryOperator.GREATER || operator == BinaryOperator.GREATER_EQUAL) {
                boolean result = switch (operator) {
                    case EQUAL -> a == b;
                    case NOT_EQUAL -> a != b;
                    case LESS -> a < b;
                    case LESS_EQUAL -> a <= b;
                    case GREATER -> a > b;
                    case GREATER_EQUAL -> a >= b;
                    default -> throw new IllegalStateException();
                };
                return new CompileTimeValue(IrType.I1, result);
            }
            double result = switch (operator) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case REMAINDER -> a % b;
                default -> Double.NaN;
            };
            return Double.isNaN(result) && operator != BinaryOperator.DIVIDE
                    && operator != BinaryOperator.REMAINDER ? null
                    : new CompileTimeValue(promoted, promoted.equals(IrType.F32)
                    ? Float.valueOf((float) result) : Double.valueOf(result));
        }
        BigInteger a = (BigInteger) l.value();
        BigInteger b = (BigInteger) r.value();
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL
                || operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL
                || operator == BinaryOperator.GREATER || operator == BinaryOperator.GREATER_EQUAL) {
            int comparison = a.compareTo(b);
            boolean result = switch (operator) {
                case EQUAL -> comparison == 0;
                case NOT_EQUAL -> comparison != 0;
                case LESS -> comparison < 0;
                case LESS_EQUAL -> comparison <= 0;
                case GREATER -> comparison > 0;
                case GREATER_EQUAL -> comparison >= 0;
                default -> throw new IllegalStateException();
            };
            return new CompileTimeValue(IrType.I1, result);
        }
        if ((operator == BinaryOperator.DIVIDE || operator == BinaryOperator.REMAINDER)
                && b.signum() == 0) {
            return null;
        }
        BigInteger result = switch (operator) {
            case ADD -> a.add(b);
            case SUBTRACT -> a.subtract(b);
            case MULTIPLY -> a.multiply(b);
            case DIVIDE -> a.divide(b);
            case REMAINDER -> a.remainder(b);
            case BITWISE_AND -> a.and(b);
            case BITWISE_XOR -> a.xor(b);
            case BITWISE_OR -> a.or(b);
            default -> null;
        };
        return result == null ? null
                : new CompileTimeValue(promoted, wrapIntegral(result, promoted));
    }

    private CompileTimeValue constantCast(CompileTimeValue value, IrType target) {
        if (target.equals(value.type())) {
            return value;
        }
        if (!target.isNumeric() || !value.type().isNumeric()) {
            return null;
        }
        if (target.isFloating()) {
            double numeric = value.type().isIntegral()
                    ? ((BigInteger) value.value()).longValue()
                    : ((Number) value.value()).doubleValue();
            return new CompileTimeValue(target, target.equals(IrType.F32)
                    ? Float.valueOf((float) numeric) : Double.valueOf(numeric));
        }
        long numeric;
        if (value.type().isFloating()) {
            double floating = ((Number) value.value()).doubleValue();
            if (Double.isNaN(floating)) {
                numeric = 0;
            } else if (target.equals(IrType.I64)) {
                numeric = floating <= Long.MIN_VALUE ? Long.MIN_VALUE
                        : floating >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) floating;
            } else {
                numeric = floating <= Integer.MIN_VALUE ? Integer.MIN_VALUE
                        : floating >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) floating;
            }
        } else {
            numeric = ((BigInteger) value.value()).longValue();
        }
        return new CompileTimeValue(target,
                wrapIntegral(BigInteger.valueOf(numeric), target));
    }

    private static String constantString(CompileTimeValue value) {
        if (value.type().equals(STRING_TYPE)) {
            return (String) value.value();
        }
        if (value.type().equals(IrType.I1)) {
            return (Boolean) value.value() ? "true" : "false";
        }
        if (value.type().equals(IrType.U16)) {
            return Character.toString((char) ((BigInteger) value.value()).intValue());
        }
        if (value.type().equals(IrType.F32)) {
            return Float.toString(((Number) value.value()).floatValue());
        }
        if (value.type().equals(IrType.F64)) {
            return Double.toString(((Number) value.value()).doubleValue());
        }
        if (value.type().equals(IrType.I64)) {
            return Long.toString(((BigInteger) value.value()).longValue());
        }
        if (value.type().isIntegral()) {
            return Integer.toString(((BigInteger) value.value()).intValue());
        }
        return null;
    }

    private void collectStringConcatenationParts(Expression expression,
                                                 List<Expression> parts) {
        if (expression instanceof BinaryExpression binary
                && binary.operator() == BinaryOperator.ADD
                && plannedExpressionType(binary).filter(STRING_TYPE::equals).isPresent()) {
            if (plannedExpressionType(binary.left()).filter(STRING_TYPE::equals).isPresent()) {
                collectStringConcatenationParts(binary.left(), parts);
            } else {
                parts.add(binary.left());
            }
            if (plannedExpressionType(binary.right()).filter(STRING_TYPE::equals).isPresent()) {
                collectStringConcatenationParts(binary.right(), parts);
            } else {
                parts.add(binary.right());
            }
            return;
        }
        parts.add(expression);
    }

    private Optional<IrType> plannedExpressionType(Expression expression) {
        Map<String, TypeVariableSymbol> captureCheckpoint = planningContext.captureCheckpoint();
        try {
            InvocationPlanningResult<ExpressionTypePlan> planned = invocationPlanner.plan(expression);
            return planned.isResolved() ? Optional.of(planned.resolvedValue().type())
                    : Optional.empty();
        } finally {
            planningContext.restoreCaptureCheckpoint(captureCheckpoint);
        }
    }

    private TypedValue emitStringConcatenation(List<TypedValue> values, SourceSpan span) {
        List<IrStringConcatPart> parts = new ArrayList<>();
        List<RenderedStringCleanup> renderedStrings = new ArrayList<>();
        for (TypedValue value : values) {
            parts.add(stringConcatenationPart(value, span, renderedStrings));
        }
        IrValueReference result = newValue(STRING_TYPE, span);
        emitWithRenderedStringCleanup(renderedStrings, () -> {
            emitCall(new IrStringConcatInstruction(result, parts, span), span);
            return null;
        }, span);
        releaseRenderedStrings(renderedStrings, span);
        AllocationInfo allocation = new AllocationInfo(controlFlowDepth);
        allocations.add(allocation);
        allocationsByOperand.put(result, allocation);
        if (unfreed != null) unfreed.register(allocation, span, "concatenation result", true);
        return new TypedValue(STRING_TYPE, result);
    }

    private IrStringConcatPart stringConcatenationPart(
            TypedValue value, SourceSpan span,
            List<RenderedStringCleanup> renderedStrings) {
        checkNotFreed(value.operand(), span);
        if (value.type().equals(STRING_TYPE)) {
            return new IrStringConcatPart(IrStringConcatPartKind.STRING, value.operand());
        }
        if (value.type().equals(IrType.NULL)) {
            return new IrStringConcatPart(IrStringConcatPartKind.STRING,
                    new IrNull(STRING_TYPE, span));
        }
        if (value.type().equals(IrType.I1)) {
            return new IrStringConcatPart(IrStringConcatPartKind.BOOLEAN, value.operand());
        }
        if (value.type().equals(IrType.U16)) {
            return new IrStringConcatPart(IrStringConcatPartKind.CHARACTER, value.operand());
        }
        if (value.type().isIntegral()) {
            return new IrStringConcatPart(IrStringConcatPartKind.INTEGER, value.operand());
        }
        if (value.type().equals(IrType.F32)) {
            return new IrStringConcatPart(IrStringConcatPartKind.FLOAT, value.operand());
        }
        if (value.type().equals(IrType.F64)) {
            return new IrStringConcatPart(IrStringConcatPartKind.DOUBLE, value.operand());
        }
        if (value.type().isReference()) {
            RenderedStringConversion conversion = emitWithRenderedStringCleanup(
                    renderedStrings, () -> lowerReferenceStringConversion(value, span), span);
            renderedStrings.add(new RenderedStringCleanup(
                    conversion.object(), conversion.result()));
            return new IrStringConcatPart(IrStringConcatPartKind.STRING,
                    conversion.result());
        }
        diagnostics.add(error(span, "String concatenation cannot convert "
                + typeName(value.type()) + " value"));
        return new IrStringConcatPart(IrStringConcatPartKind.STRING,
                new IrNull(STRING_TYPE, span));
    }

    private RenderedStringConversion lowerReferenceStringConversion(
            TypedValue value, SourceSpan span) {
        IrOperand receiver = requireValue(value, value.type(), span,
                "String concatenation operand");
        IrValueReference isNull = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(isNull, IrBinaryOperator.EQUAL,
                receiver, new IrNull(value.type(), span), span));
        MutableBlock nullBlock = createBlock("string.value.null", span);
        MutableBlock valueBlock = createBlock("string.value.object", span);
        MutableBlock merge = createBlock("string.value.merge", span);
        currentBlock.terminate(new IrBranch(isNull, nullBlock.label, valueBlock.label, span));

        currentBlock = valueBlock;
        IrOperand converted = emitToStringConversion(value, receiver, span);
        MutableBlock valueEnd = currentBlock;
        valueEnd.terminate(new IrJump(merge.label, span));

        currentBlock = nullBlock;
        IrOperand nullText = stringPool.intern("null", span);
        nullBlock.terminate(new IrJump(merge.label, span));

        currentBlock = merge;
        IrValueReference result = newValue(STRING_TYPE, span);
        merge.addPhi(new MutablePhi(result, List.of(
                new IrPhiIncoming(valueEnd.label, converted),
                new IrPhiIncoming(nullBlock.label, nullText)), span));
        return new RenderedStringConversion(receiver, result);
    }

    private <T> T emitWithRenderedStringCleanup(
            List<RenderedStringCleanup> renderedStrings,
            Supplier<T> emitter, SourceSpan span) {
        if (renderedStrings.isEmpty()) {
            return emitter.get();
        }
        List<ExceptionRegion> outerRegions = List.copyOf(exceptionRegions);
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        ExceptionRegion cleanup = new ExceptionRegion(
                createBlock("string.render.cleanup", span));
        exceptionRegions.push(cleanup);
        T result = emitter.get();
        MutableBlock normal = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> normalEnvironment = copyEnvironment();
        OwnershipSnapshot normalOwnership = snapshotOwnership();
        restoreDeque(exceptionRegions, outerRegions);
        if (cleanup.edges.isEmpty()) {
            cleanup.landingPad.terminate(new IrUnreachable(span));
        } else {
            IrOperand exception = beginExceptionHandler(cleanup, before,
                    ownershipBefore, span);
            releaseRenderedStrings(renderedStrings, span);
            emitThrow(exception, span);
        }
        currentBlock = normal;
        environment = normalEnvironment;
        restoreOwnership(normalOwnership);
        return result;
    }

    private void releaseRenderedStrings(
            List<RenderedStringCleanup> renderedStrings, SourceSpan span) {
        for (int index = renderedStrings.size() - 1; index >= 0; index--) {
            RenderedStringCleanup rendered = renderedStrings.get(index);
            currentBlock.addInstruction(new IrReleaseOwnedToStringResultInstruction(
                    rendered.object(), rendered.result(), span));
        }
    }

    private IrOperand emitToStringConversion(TypedValue value, IrOperand receiver,
                                             SourceSpan span) {
        IrType lookupType = value.type().isTypeParameter()
                ? value.type().erasure() : dispatchReceiverType(value.type());
        TypeSymbol owner = lookupType.isNominalReference()
                ? hierarchy.type(lookupType.referenceName()).orElse(null) : null;
        List<CallableSymbol> candidates = owner == null ? List.of()
                : hierarchy.lookupMethods(value.type().isArray() ? lookupType : value.type(),
                        "toString").stream()
                .filter(candidate -> !candidate.isStatic()
                        && candidate.parameterTypes().isEmpty())
                .toList();
        CallableSymbol target = candidates.isEmpty() ? null : candidates.getFirst();
        if (target == null || !hierarchy.isAssignable(STRING_TYPE, target.returnType())) {
            diagnostics.add(error(span, "String concatenation requires an accessible "
                    + "toString() method returning String"));
            return new IrNull(STRING_TYPE, span);
        }
        checkCheckedExceptions(target, span);
        IrValueReference callResult = newValue(target.returnType(), span);
        Optional<IrValueReference> result = Optional.of(callResult);
        Set<String> targets = value.type().isArray()
                ? Set.of(target.linkageName())
                : hierarchy.dispatchTargets(value.type(), target);
        if (targets.size() == 1) {
            String linkageName = targets.iterator().next();
            EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(linkageName);
            recordResolvedCall(summary == null
                            ? EscapeSummaryAnalyzer.EscapeSummary.unknown(target) : summary,
                    linkageName, receiver, List.of(), result, target.sourceName());
            emitCall(new IrCallInstruction(result, linkageName, target.returnType(),
                    List.of(receiver), owner.isInterface()
                    ? IrCallKind.DEVIRTUALIZED_INTERFACE : IrCallKind.DEVIRTUALIZED_VIRTUAL,
                    Optional.of(lookupType.referenceName() + "." + target.signatureKey()), span),
                    span);
        } else if (owner.isInterface()) {
            recordUnknownCallEscapes(receiver, List.of(), target.sourceName(), span);
            emitCall(new IrInterfaceCallInstruction(result, owner.name(),
                    hierarchy.dispatchSlot(target), target.returnType(), List.of(receiver), span),
                    span);
        } else {
            recordUnknownCallEscapes(receiver, List.of(), target.sourceName(), span);
            emitCall(new IrVirtualCallInstruction(result, hierarchy.dispatchSlot(target),
                    target.returnType(), List.of(receiver), span), span);
        }
        if (unfreed != null && unfreedFreshResults.contains(callResult)) {
            // This is an implicit rendering with compiler-emitted conditional cleanup.
            unfreed.consumed(allocationsByOperand.get(callResult));
        }
        return target.returnType().equals(STRING_TYPE) ? callResult
                : convertReference(callResult, STRING_TYPE, span);
    }

    private TypedValue lowerShortCircuit(BinaryExpression expression) {
        TypedValue left = lowerExpression(expression.left());
        IrOperand condition = requireCondition(left, expression.left().span(),
                "operator '" + operatorText(expression.operator()) + "'");
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        MutableBlock rightBlock = createBlock("logical.right", expression.right().span());
        MutableBlock shortBlock = createBlock("logical.short", expression.left().span());
        MutableBlock merge = createBlock("logical.merge", expression.span());
        boolean and = expression.operator() == BinaryOperator.LOGICAL_AND;
        currentBlock.terminate(new IrBranch(condition,
                and ? rightBlock.label : shortBlock.label,
                and ? shortBlock.label : rightBlock.label, expression.operatorSpan()));

        currentBlock = rightBlock;
        environment = new LinkedHashMap<>(before);
        enterScope();
        activatePatternBindings(PatternFlow.bindingsForRightOperand(expression));
        TypedValue right = lowerExpression(expression.right());
        IrOperand rightOperand = requireCondition(right, expression.right().span(),
                "operator '" + operatorText(expression.operator()) + "'");
        MutableBlock rightEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> rightEnvironment = copyEnvironment();
        OwnershipSnapshot rightOwnership = snapshotOwnership();
        exitScope();

        currentBlock = shortBlock;
        restoreOwnership(ownershipBefore);
        environment = new LinkedHashMap<>(before);
        IrOperand shortValue = new IrConstant(IrType.I1, and ? 0 : 1, expression.left().span());
        LinkedHashMap<LocalSymbol, IrOperand> shortEnvironment = copyEnvironment();

        rightEnd.terminate(new IrJump(merge.label, expression.span()));
        shortBlock.terminate(new IrJump(merge.label, expression.span()));
        currentBlock = merge;
        List<BranchFlow> incoming = List.of(
                new BranchFlow(true, rightEnd, rightEnvironment, rightOwnership),
                new BranchFlow(true, shortBlock, shortEnvironment, ownershipBefore));
        mergeFlowOwnership(incoming);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, incoming, expression.span(), merge));
        }
        IrValueReference result = newValue(IrType.I1, expression.span());
        merge.addPhi(new MutablePhi(result, List.of(
                new IrPhiIncoming(rightEnd.label, rightOperand),
                new IrPhiIncoming(shortBlock.label, shortValue)), expression.span()));
        for (PatternFlow.Binding binding : PatternFlow.bindingsForRightOperand(expression)) {
            PatternLocal local = patternLocals.get(binding.expression());
            if (local == null) {
                continue;
            }
            IrOperand rightValue = rightEnvironment.getOrDefault(local.symbol, local.operand);
            IrValueReference merged = newValue(local.symbol.type(), binding.variable().nameSpan());
            merge.addPhi(new MutablePhi(merged, List.of(
                    new IrPhiIncoming(rightEnd.label, rightValue),
                    new IrPhiIncoming(shortBlock.label,
                            new IrNull(local.symbol.type(), binding.variable().nameSpan()))),
                    binding.variable().nameSpan()));
            AllocationInfo allocation = allocationOf(rightValue);
            if (allocation != null) {
                allocationsByOperand.put(merged, allocation);
            }
            local.operand = merged;
        }
        PatternFlow.Result rightFlow = PatternFlow.analyze(expression.right());
        List<PatternFlow.Binding> introducedFromRight = and
                ? rightFlow.whenTrue() : rightFlow.whenFalse();
        for (PatternFlow.Binding binding : introducedFromRight) {
            PatternLocal local = patternLocals.get(binding.expression());
            if (local == null) {
                continue;
            }
            IrValueReference merged = newValue(local.symbol.type(), binding.variable().nameSpan());
            merge.addPhi(new MutablePhi(merged, List.of(
                    new IrPhiIncoming(rightEnd.label, local.operand),
                    new IrPhiIncoming(shortBlock.label,
                            new IrNull(local.symbol.type(), binding.variable().nameSpan()))),
                    binding.variable().nameSpan()));
            AllocationInfo allocation = allocationOf(local.operand);
            if (allocation != null) {
                allocationsByOperand.put(merged, allocation);
            }
            local.operand = merged;
        }
        return new TypedValue(IrType.I1, result);
    }

    private TypedValue lowerConditional(ConditionalExpression expression,
                                        Optional<IrType> expectedType) {
        TypedValue conditionValue = lowerExpression(expression.condition());
        IrOperand condition = requireCondition(conditionValue, expression.condition().span(),
                "conditional expression");
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        MutableBlock trueBlock = createBlock("conditional.true", expression.whenTrue().span());
        MutableBlock falseBlock = createBlock("conditional.false", expression.whenFalse().span());
        MutableBlock merge = createBlock("conditional.merge", expression.span());
        currentBlock.terminate(new IrBranch(condition, trueBlock.label, falseBlock.label,
                expression.questionSpan()));

        currentBlock = trueBlock;
        environment = new LinkedHashMap<>(before);
        PatternFlow.Result patternFlow = PatternFlow.analyze(expression.condition());
        enterScope();
        activatePatternBindings(patternFlow.whenTrue());
        TypedValue whenTrue = lowerExpression(expression.whenTrue(), expectedType);
        MutableBlock trueEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> trueEnvironment = copyEnvironment();
        OwnershipSnapshot trueOwnership = snapshotOwnership();
        exitScope();

        currentBlock = falseBlock;
        restoreOwnership(ownershipBefore);
        environment = new LinkedHashMap<>(before);
        enterScope();
        activatePatternBindings(patternFlow.whenFalse());
        TypedValue whenFalse = lowerExpression(expression.whenFalse(), expectedType);
        MutableBlock falseEnd = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> falseEnvironment = copyEnvironment();
        OwnershipSnapshot falseOwnership = snapshotOwnership();
        exitScope();

        IrType resultType = conditionalType(whenTrue.type(), whenFalse.type(), expression);
        currentBlock = trueEnd;
        restoreOwnership(trueOwnership);
        environment = trueEnvironment;
        IrOperand trueOperand = requireValue(whenTrue, resultType,
                expression.whenTrue().span(), "conditional expression");
        trueOwnership = snapshotOwnership();
        trueEnd.terminate(new IrJump(merge.label, expression.span()));
        currentBlock = falseEnd;
        restoreOwnership(falseOwnership);
        environment = falseEnvironment;
        IrOperand falseOperand = requireValue(whenFalse, resultType,
                expression.whenFalse().span(), "conditional expression");
        falseEnd.terminate(new IrJump(merge.label, expression.span()));

        currentBlock = merge;
        List<BranchFlow> incoming = List.of(
                new BranchFlow(true, trueEnd, trueEnvironment, trueOwnership),
                new BranchFlow(true, falseEnd, falseEnvironment, snapshotOwnership()));
        mergeFlowOwnership(incoming);
        environment = new LinkedHashMap<>();
        for (LocalSymbol symbol : before.keySet()) {
            environment.put(symbol, mergeValue(symbol, incoming, expression.span(), merge));
        }
        IrValueReference result = newValue(resultType, expression.span());
        merge.addPhi(new MutablePhi(result, List.of(
                new IrPhiIncoming(trueEnd.label, trueOperand),
                new IrPhiIncoming(falseEnd.label, falseOperand)), expression.span()));
        mergeAllocationIdentity(result, List.of(trueOperand, falseOperand));
        BigInteger constant = null;
        if (conditionValue.integralConstant() != null) {
            constant = conditionValue.integralConstant().signum() != 0
                    ? whenTrue.integralConstant() : whenFalse.integralConstant();
        } else if (whenTrue.integralConstant() != null
                && whenTrue.integralConstant().equals(whenFalse.integralConstant())) {
            constant = whenTrue.integralConstant();
        }
        return new TypedValue(resultType, result, constant);
    }

    private IrType conditionalType(IrType whenTrue, IrType whenFalse,
                                   ConditionalExpression expression) {
        if (whenTrue.equals(IrType.VOID) || whenFalse.equals(IrType.VOID)) {
            diagnostics.add(error(expression.questionSpan(),
                    "conditional expression branches cannot have type void"));
            return IrType.I32;
        }
        if (whenTrue.equals(whenFalse)) {
            return whenTrue;
        }
        if (whenTrue.isNumeric() && whenFalse.isNumeric()) {
            return PrimitiveConversions.binaryPromotion(whenTrue, whenFalse);
        }
        if (whenTrue.equals(IrType.NULL) && whenFalse.isReference()) {
            return whenFalse;
        }
        if (whenFalse.equals(IrType.NULL) && whenTrue.isReference()) {
            return whenTrue;
        }
        if (whenTrue.isReference() && whenFalse.isReference()) {
            if (hierarchy.isAssignable(whenTrue, whenFalse)) {
                return whenTrue;
            }
            if (hierarchy.isAssignable(whenFalse, whenTrue)) {
                return whenFalse;
            }
        }
        diagnostics.add(error(expression.questionSpan(), "conditional expression branches have incompatible types "
                + typeName(whenTrue) + " and " + typeName(whenFalse)));
        return whenTrue.equals(IrType.VOID) ? IrType.I32 : whenTrue;
    }

    private TypedValue lowerAssignmentExpression(AssignmentExpression expression) {
        LValue target = resolveLValue(expression.target(), expression.operatorSpan(),
                expression.operator() == AssignmentOperator.ASSIGN);
        TypedValue value;
        if (expression.operator() == AssignmentOperator.ASSIGN) {
            value = lowerExpression(expression.value(), target == null
                    ? Optional.empty() : Optional.of(target.type()));
        } else {
            IrOperand previous = target == null
                    ? defaultValue(IrType.I32, expression.target().span()) : target.read().get();
            IrType targetType = target == null ? IrType.I32 : target.type();
            TypedValue right = lowerExpression(expression.value());
            if (expression.operator() == AssignmentOperator.ADD
                    && targetType.equals(STRING_TYPE)) {
                value = emitStringConcatenation(
                        List.of(new TypedValue(targetType, previous), right), expression.span());
            } else if (targetType.equals(STRING_TYPE)) {
                diagnostics.add(error(expression.operatorSpan(), "operator '"
                        + assignmentOperatorText(expression.operator())
                        + "' is not defined for " + typeName(targetType) + " values"));
                value = new TypedValue(targetType, previous);
            } else if (expression.operator() == AssignmentOperator.ADD
                    && right.type().equals(STRING_TYPE)) {
                diagnostics.add(error(expression.operatorSpan(), "cannot assign "
                        + typeName(STRING_TYPE) + " value to " + typeName(targetType)
                        + " " + (target == null ? "assignment target" : target.description())));
                value = new TypedValue(targetType, previous);
            } else {
                value = emitBinary(compoundOperator(expression.operator()),
                        new TypedValue(targetType, previous), right, expression.target().span(),
                        expression.value().span(), expression.operatorSpan(), expression.span());
            }
        }
        if (target == null) {
            return value;
        }
        boolean compound = expression.operator() != AssignmentOperator.ASSIGN;
        if (!compound && !isAssignmentConvertible(target.type(), value)) {
            diagnostics.add(error(expression.operatorSpan(), "cannot assign " + typeName(value.type())
                    + " value to " + typeName(target.type()) + " " + target.description()));
        }
        IrOperand operand;
        if (compound) {
            operand = target.type().equals(STRING_TYPE)
                    ? value.operand()
                    : convertNumeric(value.operand(), target.type(), expression.span());
        } else if (isAssignmentConvertible(target.type(), value)) {
            operand = assignmentValue(value, target.type(), expression.value().span(), "assignment");
        } else {
            operand = defaultValue(target.type(), expression.value().span());
        }
        target.write().accept(operand, expression.span());
        return new TypedValue(target.type(), operand);
    }

    private TypedValue lowerUpdate(UpdateExpression expression) {
        LValue target = resolveLValue(expression.target(), expression.operatorSpan(), false);
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        IrOperand previous = target.read().get();
        if (!target.type().isNumeric()) {
            diagnostics.add(error(expression.operatorSpan(), "operator '"
                    + (expression.operator() == ironwood.compiler.ast.UpdateOperator.INCREMENT ? "++" : "--")
                    + "' requires an int variable, field, or array element, or another numeric target"));
            return new TypedValue(target.type(), previous);
        }
        IrType promoted = PrimitiveConversions.unaryPromotion(target.type());
        IrOperand promotedPrevious = convertNumeric(previous, promoted, expression.target().span());
        TypedValue updated = emitBinary(
                expression.operator() == ironwood.compiler.ast.UpdateOperator.INCREMENT
                        ? BinaryOperator.ADD : BinaryOperator.SUBTRACT,
                new TypedValue(promoted, promotedPrevious),
                new TypedValue(IrType.I32, new IrConstant(IrType.I32, 1, expression.operatorSpan()),
                        BigInteger.ONE),
                expression.target().span(), expression.operatorSpan(), expression.operatorSpan(), expression.span());
        IrOperand narrowed = convertNumeric(updated.operand(), target.type(), expression.span());
        target.write().accept(narrowed, expression.span());
        return expression.prefix() ? new TypedValue(target.type(), narrowed)
                : new TypedValue(target.type(), previous);
    }

    private TypedValue lowerCast(CastExpression expression) {
        IrType target = resolveType(expression.targetType());
        TypedValue value = lowerExpression(expression.operand(), Optional.of(target));
        if (target.equals(IrType.VOID)) {
            diagnostics.add(error(expression.targetType().span(), "cast target cannot be void"));
            return value;
        }
        if (target.isNumeric() && value.type().isNumeric()) {
            IrOperand converted = convertNumeric(value.operand(), target, expression.span());
            BigInteger constant = value.integralConstant();
            if (constant != null && target.isIntegral()) {
                constant = wrapIntegral(constant, target);
            } else {
                constant = null;
            }
            return new TypedValue(target, converted, constant);
        }
        if (isAssignable(target, value.type())) {
            return new TypedValue(target, requireValue(value, target, expression.operand().span(), "cast"));
        }
        if (target.isTypeParameter() || value.type().isTypeParameter()) {
            diagnostics.add(error(expression.span(), "checked casts involving type parameters are not supported; "
                    + "cannot cast " + typeName(value.type()) + " to " + typeName(target)));
            return new TypedValue(target, defaultValue(target, expression.span()));
        }
        if (target.isArray()) {
            if (!isReifiableType(target)) {
                diagnostics.add(error(expression.span(), "array cast target must be reifiable; cannot cast "
                        + typeName(value.type()) + " to " + typeName(target)));
                return new TypedValue(target, defaultValue(target, expression.span()));
            }
            if (!value.type().isReference()) {
                diagnostics.add(error(expression.span(), "cannot cast " + typeName(value.type())
                        + " to " + typeName(target)));
                return new TypedValue(target, defaultValue(target, expression.span()));
            }
            if (value.type().isArray()) {
                diagnostics.add(error(expression.span(), "statically impossible array cast from "
                        + typeName(value.type()) + " to " + typeName(target)
                        + ": invariant array descriptors are distinct"));
                return new TypedValue(target, defaultValue(target, expression.span()));
            }
            if (!value.type().isNominalReference()
                    || !value.type().referenceName().equals("ironwood.lang.Object")) {
                diagnostics.add(error(expression.span(), "statically impossible cast from "
                        + typeName(value.type()) + " to " + typeName(target)
                        + ": arrays have only ironwood.lang.Object as a nominal supertype"));
                return new TypedValue(target, defaultValue(target, expression.span()));
            }
            return lowerCheckedArrayCast(value, target, expression);
        }
        boolean reifiableTarget = target.isNominalReference() && !target.typeArguments().isEmpty()
                && target.typeArguments().stream().allMatch(argument -> argument.isWildcard()
                        && argument.wildcardKind() == IrType.WildcardKind.UNBOUNDED);
        if (target.isNominalReference() && !target.typeArguments().isEmpty()
                && !reifiableTarget && value.type().isReference()) {
            GenericCastSafety.Result proof = new GenericCastSafety(hierarchy)
                    .prove(value.type(), target);
            if (proof.outcome() == GenericCastSafety.Outcome.SAFE) {
                return lowerCheckedReferenceCast(value, target, expression);
            }
            if (proof.outcome() == GenericCastSafety.Outcome.IMPOSSIBLE) {
                diagnostics.add(error(expression.span(), "statically impossible generic cast from "
                        + typeName(value.type()) + " to " + typeName(target)
                        + ": the closed program has no concrete type compatible with both"));
            } else {
                GenericCastSafety.Witness witness = proof.witness().orElseThrow();
                diagnostics.add(error(expression.span(), "unchecked generic casts are not supported; cannot cast "
                        + typeName(value.type()) + " to " + typeName(target) + ": concrete type '"
                        + witness.concreteType() + "' " + witness.reason()));
            }
            return new TypedValue(target, defaultValue(target, expression.span()));
        }
        if (value.type().isArray()) {
            diagnostics.add(error(expression.span(), "statically impossible cast from "
                    + typeName(value.type()) + " to " + typeName(target)
                    + ": arrays have only ironwood.lang.Object as a nominal supertype"));
            return new TypedValue(target, defaultValue(target, expression.span()));
        }
        if (!target.isNominalReference()
                || !value.type().isNominalReference() && !value.type().isWildcard()) {
            diagnostics.add(error(expression.span(), "cannot cast " + typeName(value.type())
                    + " to " + typeName(target)));
            return new TypedValue(target, defaultValue(target, expression.span()));
        }
        String sourceName = value.type().isWildcard()
                ? "ironwood.lang.Object" : value.type().referenceName();
        if (!hierarchy.mayOverlap(sourceName, target.referenceName())) {
            diagnostics.add(error(expression.span(), "statically impossible cast from "
                    + typeName(value.type()) + " to " + typeName(target)
                    + ": the closed program has no concrete type compatible with both"));
            return new TypedValue(target, defaultValue(target, expression.span()));
        }
        return lowerCheckedReferenceCast(value, target, expression);
    }

    private TypedValue lowerCheckedReferenceCast(TypedValue value, IrType target,
                                                 CastExpression expression) {
        IrOperand operand = requireValue(value, value.type(), expression.operand().span(), "cast");
        IrValueReference isNull = newValue(IrType.I1, expression.operand().span());
        currentBlock.addInstruction(new IrBinaryInstruction(isNull, IrBinaryOperator.EQUAL,
                operand, new IrNull(value.type(), expression.operand().span()), expression.span()));

        MutableBlock typeTest = createBlock("cast.type.test", expression.span());
        MutableBlock success = createBlock("cast.success", expression.span());
        MutableBlock failure = createBlock("cast.failure", expression.span());
        currentBlock.terminate(new IrBranch(isNull, success.label, typeTest.label, expression.span()));

        currentBlock = typeTest;
        TypeSymbol targetType = hierarchy.type(target.referenceName()).orElseThrow();
        IrValueReference matches = newValue(IrType.I1, expression.span());
        currentBlock.addInstruction(new IrInstanceOfInstruction(matches, operand,
                targetType.name(), targetType.typeId(), Optional.of(target), expression.span()));
        currentBlock.terminate(new IrBranch(matches, success.label, failure.label, expression.span()));

        currentBlock = failure;
        emitClassCastException(expression.span());

        currentBlock = success;
        return new TypedValue(target, convertReference(operand, target, expression.span()));
    }

    private TypedValue lowerCheckedArrayCast(TypedValue value, IrType target,
                                             CastExpression expression) {
        IrOperand operand = requireValue(value, value.type(), expression.operand().span(), "cast");
        IrValueReference isNull = newValue(IrType.I1, expression.operand().span());
        currentBlock.addInstruction(new IrBinaryInstruction(isNull, IrBinaryOperator.EQUAL,
                operand, new IrNull(value.type(), expression.operand().span()), expression.span()));

        MutableBlock typeTest = createBlock("array.cast.type.test", expression.span());
        MutableBlock success = createBlock("array.cast.success", expression.span());
        MutableBlock failure = createBlock("array.cast.failure", expression.span());
        currentBlock.terminate(new IrBranch(isNull, success.label, typeTest.label, expression.span()));

        currentBlock = typeTest;
        IrValueReference matches = newValue(IrType.I1, expression.span());
        currentBlock.addInstruction(new IrArrayTypeTestInstruction(matches, operand,
                target.erasure(), expression.span()));
        currentBlock.terminate(new IrBranch(matches, success.label, failure.label,
                expression.span()));

        currentBlock = failure;
        emitClassCastException(expression.span());

        currentBlock = success;
        return new TypedValue(target, convertReference(operand, target, expression.span()));
    }

    private LValue resolveLValue(Expression expression, SourceSpan operatorSpan,
                                 boolean plainAssignment) {
        if (expression instanceof NameExpression name) {
            LocalSymbol symbol = resolve(name.name());
            if (symbol != null) {
                if (symbol.isFinal()) {
                    diagnostics.add(error(name.span(),
                            "cannot assign to or update final variable '" + name.name() + "'"));
                    return null;
                }
                return new LValue(symbol.type(), () -> readLocal(symbol, name.span()),
                        (value, span) -> environment.put(symbol, value),
                        "variable '" + name.name() + "'");
            }
            FieldSymbol field = resolveField(currentClass.selfType(), name.name(), name.span());
            if (field != null) {
                if (!plainAssignment) {
                    diagnoseIllegalForwardInstanceFieldRead(field, name.name(), name.span());
                    diagnoseIllegalForwardStaticFieldRead(field, name.name(), name.span());
                }
                if (!isAccessible(field.accessModifier(), field.ownerClass(),
                        currentClass.name(), false)) {
                    diagnostics.add(error(name.span(), memberAccessMessage("field", name.name(),
                            field.accessModifier(), field.ownerClass())));
                }
                if (field.isFinal()) {
                    if (!plainAssignment || !canAssignBlankFinal(field, true)) {
                        diagnostics.add(error(name.span(), "cannot assign to or update final field '"
                                + name.name() + "'"));
                        return null;
                    }
                }
                if (field.isStatic()) {
                    return staticFieldLValue(field, "static field '" + name.name() + "'",
                            expression.span());
                }
                if (evaluatingConstructorArguments) {
                    diagnostics.add(error(name.span(), "instance field '" + name.name()
                            + "' cannot be written before superclass construction"));
                }
                if (function.isStatic()) {
                    diagnostics.add(error(name.span(), "instance field '" + name.name()
                            + "' cannot be referenced from a static method"));
                    return null;
                }
                return fieldLValue(thisOperand, field, "field '" + name.name() + "'", expression.span());
            }
            FieldTarget lexical = resolveLexicalField(name.name(), name.span());
            if (lexical != null) {
                if (lexical.field().isFinal()) {
                    diagnostics.add(error(name.span(), "cannot assign to or update final field '"
                            + name.name() + "'"));
                    return null;
                }
                if (lexical.field().isStatic()) {
                    return staticFieldLValue(lexical.field(), "static field '" + name.name() + "'",
                            expression.span());
                }
                if (lexical.receiver() == null) {
                    return null;
                }
                return fieldLValue(lexical.receiver(), lexical.field(),
                        "field '" + name.name() + "'", expression.span());
            }
            FieldSymbol imported = resolveStaticImportedField(name.name(), name.span(), true);
            if (imported != null) {
                if (imported.isFinal()) {
                    diagnostics.add(error(name.span(), "cannot assign to or update final field '"
                            + name.name() + "'"));
                    return null;
                }
                return staticFieldLValue(imported, "static field '" + name.name() + "'",
                        expression.span());
            }
            diagnostics.add(error(name.span(), "unknown local variable '" + name.name() + "'"));
            return null;
        }
        if (expression instanceof FieldAccessExpression access) {
            if (access.receiver() instanceof SuperExpression superExpression) {
                FieldTarget field = resolveSuperFieldTarget(access, superExpression);
                if (field == null) {
                    return null;
                }
                if (field.field().isFinal()) {
                    diagnostics.add(error(access.fieldNameSpan(),
                            "cannot assign to or update final field '" + access.fieldName() + "'"));
                    return null;
                }
                return fieldLValue(field.receiver(), field.field(),
                        "field '" + access.fieldName() + "'", expression.span());
            }
            ClassFieldResolution qualified = resolveClassField(access);
            if (qualified.classQualifier()) {
                FieldSymbol field = qualified.field();
                if (field == null) {
                    return null;
                }
                if (!field.isStatic()) {
                    diagnostics.add(error(access.fieldNameSpan(), "instance field '"
                            + access.fieldName() + "' cannot be accessed through type '"
                            + qualified.qualifier().name() + "'"));
                    return null;
                }
                if (field.isFinal()) {
                    diagnostics.add(error(access.fieldNameSpan(),
                            "cannot assign to or update final field '" + access.fieldName() + "'"));
                    return null;
                }
                return staticFieldLValue(field, "static field '" + access.fieldName() + "'",
                        expression.span());
            }
            FieldTarget field = resolveFieldTarget(access);
            if (field == null) {
                return null;
            }
            if (field.field().isFinal()) {
                if (!plainAssignment || !(access.receiver() instanceof ThisExpression)
                        || !canAssignBlankFinal(field.field(), true)) {
                    diagnostics.add(error(access.fieldNameSpan(),
                            "cannot assign to or update final field '" + access.fieldName() + "'"));
                    return null;
                }
            }
            return fieldLValue(field.receiver(), field.field(),
                    "field '" + access.fieldName() + "'", expression.span());
        }
        if (expression instanceof ArrayAccessExpression access) {
            ArrayTarget array = resolveArrayTarget(access);
            if (array == null) {
                return null;
            }
            IrType elementType = array.array().type().elementType();
            return new LValue(elementType, () -> {
                IrValueReference result = newValue(elementType, expression.span());
                currentBlock.addInstruction(new IrArrayLoadInstruction(result, array.array(),
                        array.index(), expression.span()));
                trackArrayElementLoad(result, array.array(), array.index());
                return result;
            }, (value, span) -> {
                trackArrayElementStore(array.array(), array.index(), value);
                currentBlock.addInstruction(new IrArrayStoreInstruction(array.array(), array.index(),
                        value, span));
            }, "array element");
        }
        lowerExpression(expression);
        diagnostics.add(error(operatorSpan,
                "left side of assignment or update must be a local variable, field, or array element"));
        return null;
    }

    private LValue fieldLValue(IrOperand receiver, FieldSymbol field, String description,
                               SourceSpan span) {
        return new LValue(field.type(), () -> {
            IrValueReference result = newValue(field.type(), span);
            currentBlock.addInstruction(new IrFieldLoadInstruction(result, receiver,
                    field.irField(), span));
            trackOwnedFieldLoad(result, receiver, field);
            return result;
        }, (value, writeSpan) -> {
            checkNotFreed(receiver, writeSpan);
            detachOwnedField(receiver, field, value);
            markEscaped(value, "allocation escapes through field '"
                    + field.declaration().name() + "'");
            currentBlock.addInstruction(new IrFieldStoreInstruction(receiver, field.irField(),
                    value, writeSpan));
        }, description);
    }

    private LValue staticFieldLValue(FieldSymbol field, String description, SourceSpan span) {
        return new LValue(field.type(), () -> {
            if (field.staticField().triggersInitialization()) {
                ensureTypeInitialized(field.ownerClass(), span);
            }
            IrValueReference result = newValue(field.type(), span);
            currentBlock.addInstruction(new IrStaticFieldLoadInstruction(result,
                    field.staticField(), span));
            return result;
        }, (value, writeSpan) -> {
            if (field.staticField().triggersInitialization()) {
                ensureTypeInitialized(field.ownerClass(), writeSpan);
            }
            markEscaped(value, "allocation escapes through static field '"
                    + field.ownerClass() + "." + field.declaration().name() + "'");
            currentBlock.addInstruction(new IrStaticFieldStoreInstruction(
                    field.staticField(), value, writeSpan));
        }, description);
    }

    private TypedValue lowerCheckedDivision(BinaryOperator operator, IrType type, IrOperand left,
                                            IrOperand right, SourceSpan span,
                                            BigInteger integralConstant) {
        IrValueReference zero = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrBinaryInstruction(zero, IrBinaryOperator.EQUAL,
                right, new IrConstant(type, 0, span), span));
        MutableBlock throwBlock = createBlock("division.zero", span);
        MutableBlock operationBlock = createBlock("division.nonzero", span);
        currentBlock.terminate(new IrBranch(zero, throwBlock.label, operationBlock.label, span));

        currentBlock = throwBlock;
        emitArithmeticException(span);

        currentBlock = operationBlock;
        IrValueReference result = newValue(type, span);
        currentBlock.addInstruction(new IrBinaryInstruction(result,
                operator == BinaryOperator.DIVIDE ? IrBinaryOperator.DIVIDE : IrBinaryOperator.REMAINDER,
                left, right, span));
        return new TypedValue(type, result, integralConstant);
    }

    private void emitArithmeticException(SourceSpan span) {
        emitBundledException("ironwood.lang.ArithmeticException", "division and remainder", span, "/ by zero");
    }

    private void emitClassCastException(SourceSpan span) {
        emitBundledException("ironwood.lang.ClassCastException", "checked reference casts", span);
    }

    private void emitNullCheck(IrOperand reference, SourceSpan span) {
        checkNotFreed(reference, span);
        IrValueReference valid = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrNullCheckInstruction(valid, reference, span));
        emitRuntimeSafetyBranch(valid, "null.valid", "null.failure",
                "ironwood.lang.NullPointerException", "null checks", span);
    }

    private void emitArrayBoundsCheck(IrOperand array, IrOperand index, SourceSpan span) {
        checkNotFreed(array, span);
        IrValueReference valid = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrArrayBoundsCheckInstruction(valid, array, index, span));
        emitRuntimeSafetyBranch(valid, "array.bounds.valid", "array.bounds.failure",
                "ironwood.lang.ArrayIndexOutOfBoundsException", "array bounds checks", span);
    }

    private void emitArrayLengthCheck(IrOperand length, SourceSpan span) {
        IrValueReference valid = newValue(IrType.I1, span);
        currentBlock.addInstruction(new IrArrayLengthCheckInstruction(valid, length, span));
        emitRuntimeSafetyBranch(valid, "array.length.valid", "array.length.failure",
                "ironwood.lang.NegativeArraySizeException", "array length checks", span);
    }

    private void emitRuntimeSafetyBranch(IrOperand valid, String validPrefix,
                                         String failurePrefix, String exceptionType,
                                         String feature, SourceSpan span) {
        MutableBlock validBlock = createBlock(validPrefix, span);
        MutableBlock failureBlock = createBlock(failurePrefix, span);
        currentBlock.terminate(new IrBranch(valid, validBlock.label, failureBlock.label, span));
        currentBlock = failureBlock;
        emitBundledException(exceptionType, feature, span);
        currentBlock = validBlock;
    }

    private void emitBundledException(String typeName, String feature, SourceSpan span) {
        emitBundledException(typeName, feature, span, null);
    }

    private void emitBundledException(String typeName, String feature, SourceSpan span, String message) {
        TypeSymbol exceptionType = hierarchy.type(typeName).orElse(null);
        IrType type = IrType.reference(typeName);
        IrValueReference exception = newValue(type, span);
        emitCall(new IrAllocateInstruction(exception, typeName, span), span);
        if (exceptionType == null) {
            diagnostics.add(error(span, feature + " require bundled type '" + typeName + "'"));
        } else {
            CallableSymbol constructor = hierarchy.constructors(type).stream()
                    .filter(candidate -> candidate.parameterTypes().equals(message == null ? List.of() : List.of(STRING_TYPE)))
                    .findFirst().orElse(null);
            if (constructor == null) {
                diagnostics.add(error(span, "bundled type '" + typeName + "' "
                        + (message == null ? "requires a no-argument constructor"
                        : "requires a String constructor")));
            } else {
                emitCall(new IrCallInstruction(Optional.empty(), constructor.linkageName(),
                        IrType.VOID, message == null ? List.of(exception)
                                : List.of(exception, stringPool.intern(message, span)), span), span);
            }
        }
        emitThrow(exception, span);
    }

    private LoweredInvocationArguments lowerInvocationArguments(
            InvocationPlan.CandidatePlan selected, String context) {
        List<TypedValue> values = new ArrayList<>();
        List<IrOperand> operands = new ArrayList<>();
        for (InvocationPlan.ArgumentPlan argument : selected.arguments()) {
            TypedValue value = lowerPlannedInvocationArgument(selected, argument);
            values.add(value);
            operands.add(assignmentValue(value, argument.parameterType(),
                    argument.expression().span(), context));
        }
        // A later argument can execute switch/yield cleanup after an earlier
        // reference argument was evaluated. Check the saved values at use time.
        operands.forEach(operand -> checkNotFreed(operand, operand.sourceSpan()));
        return new LoweredInvocationArguments(values, operands);
    }

    private TypedValue lowerPlannedInvocationArgument(
            InvocationPlan.CandidatePlan selected, InvocationPlan.ArgumentPlan argument) {
        TypedValue lowered = lowerExpression(argument.expression(),
                Optional.of(argument.parameterType()));
        IrType plannedSource = argument.conversion().sourceType();
        if (lowered.type().equals(plannedSource)
                || !isPlannedCaptureReinterpretation(
                lowered.type(), plannedSource, selected.plannedCaptures())) {
            return lowered;
        }
        IrOperand operand = lowered.type().equals(IrType.NULL)
                ? new IrNull(plannedSource, argument.expression().span())
                : convertReference(lowered.operand(), plannedSource,
                argument.expression().span());
        return new TypedValue(plannedSource, operand, lowered.integralConstant());
    }

    private static boolean isPlannedCaptureReinterpretation(
            IrType actual, IrType plannedSource,
            Map<String, TypeVariableSymbol> plannedCaptures) {
        return actual.isReference() && plannedSource.isReference()
                && actual.erasure().equals(plannedSource.erasure())
                && containsPlannedCapture(plannedSource, plannedCaptures);
    }

    private static boolean containsPlannedCapture(
            IrType type, Map<String, TypeVariableSymbol> plannedCaptures) {
        if (type.isTypeParameter()) {
            return plannedCaptures.containsKey(type.referenceName());
        }
        if (type.isArray()) {
            return containsPlannedCapture(type.elementType(), plannedCaptures);
        }
        if (type.isWildcard() && type.wildcardBound() != null
                && containsPlannedCapture(type.wildcardBound(), plannedCaptures)) {
            return true;
        }
        return type.typeArguments().stream()
                .anyMatch(argument -> containsPlannedCapture(argument, plannedCaptures));
    }

    private void markConstructorPublications(CallableSymbol constructor, TypeSymbol target,
                                             List<TypedValue> arguments) {
        arguments.forEach(argument -> exposeContainerContents(argument.operand(),
                "constructor can observe stored data-structure references"));
        EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(constructor);
        boolean publishesReceiver = summary.thisEscapes();
        for (TypeSymbol parent = target.superclass().orElse(null); parent != null;
             parent = parent.superclass().orElse(null)) {
            publishesReceiver |= parent.constructors().stream()
                    .anyMatch(candidate -> escapeSummaries.summary(candidate).thisEscapes());
        }
        // Publication may happen before the constructor throws. Record it before
        // the invoke captures exceptional ownership; receiver-only borrows are
        // installed after success and disappear with a rolled-back wrapper.
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).type().isReference()
                    && (summary.parameterEscapesOutsideReceiver(index)
                    || publishesReceiver && summary.parameterEscapes(index))) {
                markEscaped(arguments.get(index).operand(),
                        "allocation escapes through constructor argument " + (index + 1));
            }
        }
    }

    private void recordConstructorBorrows(CallableSymbol constructor, AllocationInfo owner,
                                          List<TypedValue> arguments) {
        EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(constructor);
        for (int index = 0; index < arguments.size(); index++) {
            AllocationInfo argument = allocationOf(arguments.get(index).operand());
            if (argument == null || !arguments.get(index).type().isReference()) { continue; }
            FieldSymbol field = escapeSummaries.retainedParameterField(constructor, index);
            if (owner.state == AllocationState.ACTIVE
                    && summary.parameterRetainedByReceiverOnly(index)
                    && field != null && ownedArrayFields.isEncapsulated(field)) {
                Set<AllocationInfo> borrowed = new LinkedHashSet<>(
                        constructorBorrows.getOrDefault(owner, Set.of()));
                borrowed.add(argument);
                constructorBorrows.put(owner, Set.copyOf(borrowed));
            } else if (summary.parameterEscapes(index)) {
                markEscaped(arguments.get(index).operand(),
                        "allocation escapes through constructor argument " + (index + 1));
            }
        }
    }

    private void markConstructorArgumentsEscaped(List<TypedValue> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            TypedValue argument = arguments.get(index);
            if (argument.type().isReference()) {
                markEscaped(argument.operand(),
                        "allocation escapes through constructor argument " + (index + 1));
            }
        }
    }

    private TypedValue lowerPlannedCall(CallExpression expression,
                                        Optional<IrType> expectedType) {
        markAttachedOwnedFieldLoansUncertain(
                "cannot prove a call made before private-field detachment is non-reentrant");
        InvocationPlanningResult<ExpressionTypePlan> planned = invocationPlanner.plan(
                expression, expectedType);
        if (!planned.isResolved()) {
            reportPlanningFailure(planned, expression.span(),
                    "method '" + expression.methodName() + "'");
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        InvocationPlan invocation = planned.resolvedValue().invocation().orElseThrow();
        return lowerSelectedCall(expression, invocation);
    }

    private boolean requiresCallablePlanning(CallExpression expression) {
        Map<String, TypeVariableSymbol> captureCheckpoint = planningContext.captureCheckpoint();
        try {
            return invocationPlanner.requiresCallablePlanning(expression);
        } finally {
            planningContext.restoreCaptureCheckpoint(captureCheckpoint);
        }
    }

    private TypedValue lowerSelectedCall(CallExpression expression,
                                         InvocationPlan invocation) {
        planningContext.commitCaptures();
        InvocationPlan.CandidatePlan selected = invocation.selected();
        CallableSymbol target = selected.candidate().callable()
                .substitute(selected.inference().substitutions());
        checkCheckedExceptions(target, expression.span());
        InvocationPlan.ReceiverPlan receiverPlan = invocation.receiver();
        TypedValue receiverValue = null;
        IrOperand receiverOperand = null;
        if (receiverPlan.kind() == InvocationPlan.ReceiverKind.INSTANCE) {
            ExpressionTypePlan valuePlan = receiverPlan.valuePlan().orElseThrow();
            receiverValue = lowerExpression(valuePlan.expression(), valuePlan.expectedType());
            receiverOperand = requireValue(receiverValue, receiverValue.type(),
                    valuePlan.expression().span(), "method receiver");
        } else if (!target.isStatic()) {
            receiverOperand = implicitOrSpecialReceiver(receiverPlan, target, expression);
        }

        LoweredInvocationArguments arguments = lowerInvocationArguments(selected,
                "method argument");
        validateFileTreeVisitor(target, arguments.values(), expression.span());

        // Java evaluates the receiver and every argument before testing a null receiver.
        if (!target.isStatic() && receiverPlan.kind() == InvocationPlan.ReceiverKind.INSTANCE
                && receiverOperand != null) {
            emitNullCheck(receiverOperand,
                    receiverPlan.valuePlan().orElseThrow().expression().span());
        }

        if (target.isStatic()) {
            ensureTypeInitialized(target.ownerType(), expression.span());
        }

        List<IrOperand> operands = new ArrayList<>();
        if (!target.isStatic()) {
            operands.add(receiverOperand == null
                    ? new IrNull(IrType.reference(target.ownerType()), expression.span())
                    : receiverOperand);
        }
        operands.addAll(arguments.operands());
        IrType resultType = selected.resultType();
        Optional<IrValueReference> result = resultType.equals(IrType.VOID)
                ? Optional.empty() : Optional.of(newValue(resultType, expression.span()));

        boolean directSpecial = receiverPlan.kind() == InvocationPlan.ReceiverKind.SUPER
                || receiverPlan.kind() == InvocationPlan.ReceiverKind.INTERFACE_SUPER;
        if (target.isStatic() || target.accessModifier() == AccessModifier.PRIVATE || directSpecial) {
            recordResolvedCall(specializedCallSummary(target, target.linkageName(),
                            arguments.values()),
                    target.linkageName(),
                    target.isStatic() ? null : receiverOperand, arguments.values(),
                    result, target.sourceName());
            emitCall(new IrCallInstruction(result, target.linkageName(), resultType,
                    operands, IrCallKind.DIRECT, Optional.empty(),
                    selected.inference().substitutions(), expression.span()), expression.span());
            return new TypedValue(resultType, result.orElse(null));
        }

        IrType exactReceiver = dispatchReceiverType(receiverPlan.lookupType());
        String receiverStaticType = exactReceiver.referenceName();
        TypeSymbol targetType = hierarchy.type(receiverStaticType)
                .orElseGet(() -> hierarchy.type(target.ownerType()).orElseThrow());
        Set<String> targets = hierarchy.dispatchTargets(exactReceiver, target);
        if (targets.size() == 1) {
            String linkageName = targets.iterator().next();
            recordResolvedCall(specializedCallSummary(target, linkageName,
                            arguments.values()),
                    linkageName, receiverOperand, arguments.values(), result,
                    target.sourceName());
            emitCall(new IrCallInstruction(result, linkageName, resultType, operands,
                    targetType.isInterface() ? IrCallKind.DEVIRTUALIZED_INTERFACE
                            : IrCallKind.DEVIRTUALIZED_VIRTUAL,
                    Optional.of(receiverStaticType + "." + target.signatureKey()),
                    selected.inference().substitutions(),
                    expression.span()), expression.span());
        } else if (targetType.isInterface()) {
            if (!recordKnownBorrowDispatch(target, receiverOperand,
                    arguments.values(), result)) {
                recordUnknownCallEscapes(receiverOperand, arguments.values(),
                        target.sourceName(), expression.span());
            }
            emitCall(new IrInterfaceCallInstruction(result, targetType.name(),
                    hierarchy.dispatchSlot(target), resultType, operands,
                    selected.inference().substitutions(), expression.span()),
                    expression.span());
        } else {
            if (!recordKnownBorrowDispatch(target, receiverOperand,
                    arguments.values(), result)) {
                recordUnknownCallEscapes(receiverOperand, arguments.values(),
                        target.sourceName(), expression.span());
            }
            emitCall(new IrVirtualCallInstruction(result, hierarchy.dispatchSlot(target),
                    resultType, operands, selected.inference().substitutions(),
                    expression.span()), expression.span());
        }
        return new TypedValue(resultType, result.orElse(null));
    }

    private EscapeSummaryAnalyzer.EscapeSummary specializedCallSummary(
            CallableSymbol selectedTarget, String resolvedLinkageName,
            List<TypedValue> arguments) {
        CallableSymbol resolved = escapeSummaries.callable(resolvedLinkageName);
        EscapeSummaryAnalyzer.EscapeSummary summary =
                escapeSummaries.summary(resolvedLinkageName);
        if (summary == null) {
            summary = EscapeSummaryAnalyzer.EscapeSummary.unknown(selectedTarget);
        }
        CallableSymbol callable = resolved == null ? selectedTarget : resolved;
        if (callable.ownerType().equals("ironwood.lang.Throwable")
                && callable.sourceName().equals("printStackTrace") && !callable.isStatic()
                && callable.returnType().equals(IrType.VOID)
                && (callable.parameterTypes().isEmpty() || callable.parameterTypes().equals(
                        List.of(IrType.reference("ironwood.io.PrintStream"))))
                && throwableTraceCallbacksDoNotPublish()) {
            // The visited list borrows graph nodes and is destroyed before return.
            // Preserve publication by virtual descriptions, causes or destination overloads.
            return summary.withBorrowingContract(true, true);
        }
        if (callable.ownerType().equals("ironwood.time.Instant")
                && callable.sourceName().equals("parse") && callable.isStatic()
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.CharSequence")))
                && arguments.size() == 1
                && toStringDoesNotPublishReceiver(arguments.getFirst().type())
                && callbackDoesNotPublishReceiver(arguments.getFirst().type(), "length", List.of(), IrType.I32)
                && callbackDoesNotPublishReceiver(arguments.getFirst().type(), "charAt", List.of(IrType.I32), IrType.U16)) {
            // Success retains only primitives; failure snapshots diagnostic text.
            // Each user callback must still be proved not to publish its source.
            return summary.withBorrowingContract(false, true);
        }
        if (isPrintStreamObjectRendering(callable) && arguments.size() == 1
                && toStringDoesNotPublishReceiver(arguments.getFirst().type())) {
            return summary.withBorrowingContract(false, true);
        }
        if (callable.ownerType().equals("ironwood.lang.StringBuilder")
                && callable.sourceName().equals("insert") && !callable.isStatic()
                && callable.parameterTypes().equals(List.of(IrType.I32,
                        IrType.reference("ironwood.lang.Object")))
                && toStringDoesNotPublishReceiver(arguments.get(1).type())) {
            // Insertion consumes the rendering without retaining its source.
            // Keep publication effects from every possible toString override.
            return summary.withBorrowingContract(false, true);
        }
        if (callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("replace") && !callable.isStatic()
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.CharSequence"),
                        IrType.reference("ironwood.lang.CharSequence")))
                && arguments.stream().allMatch(argument -> toStringDoesNotPublishReceiver(argument.type()))) {
            // The facade snapshots each argument once, copies into a fresh result,
            // and retains neither input. Preserve real publication by overrides.
            return summary.withBorrowingContract(true, true);
        }
        if (isBorrowingStringJoin(callable, arguments)) {
            return summary.withBorrowingContract(false, true);
        }
        if (isFileTreeWalk(callable) && (function.ownerType().equals("ironwood.nio.file.Files")
                || fileVisitorCallbacksBorrow(arguments.getLast().type()))) {
            // The traversal owns temporary paths and attributes. Every possible
            // closed-world visitor target must borrow those callback values.
            return summary.withBorrowingContract(false, true);
        }
        return summary;
    }

    private static boolean isFileTreeWalk(CallableSymbol callable) {
        if (!callable.ownerType().equals("ironwood.nio.file.Files")
                || !callable.sourceName().equals("walkFileTree") || !callable.isStatic()) {
            return false;
        }
        List<IrType> parameters = callable.parameterTypes();
        return parameters.size() == 2 || parameters.size() == 4;
    }

    private void validateFileTreeVisitor(CallableSymbol callable,
                                         List<TypedValue> arguments, SourceSpan span) {
        if (isFileTreeWalk(callable) && !function.ownerType().equals("ironwood.nio.file.Files")
                && !fileVisitorCallbacksBorrow(arguments.getLast().type())) {
            diagnostics.add(error(span, "Files.walkFileTree visitor callbacks "
                    + "must not retain callback paths or attributes"));
        }
    }

    private boolean fileVisitorCallbacksBorrow(IrType type) {
        IrType path = IrType.reference("ironwood.nio.file.Path");
        IrType attributes = IrType.reference("ironwood.nio.file.attribute.BasicFileAttributes");
        IrType failure = IrType.reference("ironwood.io.IOException");
        return fileVisitorCallbackBorrows(type, "preVisitDirectory",
                List.of(path, attributes), 0, 1)
                && fileVisitorCallbackBorrows(type, "visitFile",
                List.of(path, attributes), 0, 1)
                && fileVisitorCallbackBorrows(type, "visitFileFailed",
                List.of(path, failure), 0)
                && fileVisitorCallbackBorrows(type, "postVisitDirectory",
                List.of(path, failure), 0);
    }

    private boolean fileVisitorCallbackBorrows(IrType type, String name,
                                               List<IrType> parameters,
                                               int... borrowedParameters) {
        if (!type.isNominalReference()) { return false; }
        IrType result = IrType.reference("ironwood.nio.file.FileVisitResult");
        List<CallableSymbol> methods = hierarchy.lookupMethods(type, name).stream()
                .filter(method -> !method.isStatic()
                        && method.parameterTypes().equals(parameters)
                        && method.returnType().equals(result)).toList();
        if (methods.size() != 1) { return false; }
        Set<String> targets = hierarchy.dispatchTargets(type, methods.getFirst());
        if (targets.isEmpty()) { return false; }
        for (String target : targets) {
            EscapeSummaryAnalyzer.EscapeSummary effects = escapeSummaries.summary(target);
            if (effects == null) { return false; }
            for (int index : borrowedParameters) {
                if (effects.parameterEscapes(index)) return false;
            }
        }
        return true;
    }

    private boolean throwableTraceCallbacksDoNotPublish() {
        IrType throwable = IrType.reference("ironwood.lang.Throwable");
        if (!toStringDoesNotPublishReceiver(throwable)
                || !callbackDoesNotPublishReceiver(throwable, "getCause", List.of(), throwable)) return false;
        for (CallableSymbol method : hierarchy.lookupMethods(throwable, "printStackTrace")) {
            if (!method.parameterTypes().equals(List.of(IrType.reference("ironwood.io.PrintStream")))) continue;
            for (String target : hierarchy.dispatchTargets(throwable, method)) {
                CallableSymbol resolved = escapeSummaries.callable(target);
                if (resolved != null && resolved.ownerType().equals("ironwood.lang.Throwable")) continue;
                EscapeSummaryAnalyzer.EscapeSummary effects = escapeSummaries.summary(target);
                if (effects == null || effects.thisEscapesWithoutReturn()
                        || effects.parameterEscapesWithoutReturn(0)) return false;
            }
        }
        return true;
    }

    private boolean isBorrowingStringJoin(CallableSymbol callable, List<TypedValue> arguments) {
        if (callable == null || !callable.isStatic()
                || !callable.ownerType().equals("ironwood.lang.String")
                || !callable.sourceName().equals("join")) return false;
        IrType sequence = IrType.reference("ironwood.lang.CharSequence");
        // Iterable callbacks have independent effects and are not covered here.
        if (callable.parameterTypes().stream().anyMatch(type -> !type.equals(sequence) && !type.isArray())) {
            return false;
        }
        for (TypedValue argument : arguments) {
            IrType type = argument.type().isArray() ? argument.type().elementType() : argument.type();
            if (!toStringDoesNotPublishReceiver(type)) return false;
        }
        return true;
    }

    private static boolean isPrintStreamObjectRendering(CallableSymbol callable) {
        return !callable.isStatic()
                && (callable.ownerType().equals("ironwood.io.PrintStream")
                || callable.ownerType().equals("ironwood.io.PrintWriter"))
                && (callable.sourceName().equals("print")
                || callable.sourceName().equals("println"))
                && callable.returnType().equals(IrType.VOID)
                && callable.parameterTypes().equals(List.of(
                        IrType.reference("ironwood.lang.Object")));
    }

    private boolean toStringDoesNotPublishReceiver(IrType staticType) {
        return callbackDoesNotPublishReceiver(staticType, "toString", List.of(), STRING_TYPE);
    }

    private boolean callbackDoesNotPublishReceiver(IrType staticType, String name,
                                                   List<IrType> parameters, IrType returnType) {
        if (staticType.equals(IrType.NULL)) {
            return true;
        }
        if (!staticType.isReference()) {
            return false;
        }
        IrType lookupType = staticType.isTypeParameter()
                ? staticType.erasure() : dispatchReceiverType(staticType);
        TypeSymbol owner = lookupType.isNominalReference()
                ? hierarchy.type(lookupType.referenceName()).orElse(null) : null;
        List<CallableSymbol> candidates = owner == null ? List.of()
                : hierarchy.lookupMethods(staticType.isArray() ? lookupType : staticType,
                        name).stream()
                .filter(candidate -> !candidate.isStatic()
                        && candidate.parameterTypes().equals(parameters)
                        && hierarchy.isAssignable(returnType, candidate.returnType()))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        CallableSymbol target = candidates.getFirst();
        Set<String> targets = staticType.isArray()
                ? Set.of(target.linkageName())
                : hierarchy.dispatchTargets(staticType, target);
        if (targets.isEmpty()) {
            return false;
        }
        for (String linkageName : targets) {
            EscapeSummaryAnalyzer.EscapeSummary effects =
                    escapeSummaries.summary(linkageName);
            if (effects == null || effects.thisEscapesWithoutReturn()) {
                return false;
            }
        }
        return true;
    }

    private IrOperand implicitOrSpecialReceiver(InvocationPlan.ReceiverPlan receiver,
                                                CallableSymbol target,
                                                CallExpression expression) {
        if (evaluatingConstructorArguments) {
            diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                    + expression.methodName()
                    + "' cannot be called before constructor delegation"));
        }
        if (receiver.kind() == InvocationPlan.ReceiverKind.SUPER) {
            SuperTarget superclass = resolveSuperTarget(expression.receiver().orElseThrow().span());
            return superclass == null ? new IrNull(IrType.reference(target.ownerType()), expression.span())
                    : convertReference(superclass.receiver(), IrType.reference(target.ownerType()),
                    expression.receiver().orElseThrow().span());
        }
        if (receiver.kind() == InvocationPlan.ReceiverKind.INTERFACE_SUPER) {
            return convertReference(thisOperand, IrType.reference(target.ownerType()),
                    expression.receiver().orElseThrow().span());
        }
        TypeSymbol owner = hierarchy.type(target.ownerType()).orElse(null);
        if (owner != null && hierarchy.isSubtype(currentClass.name(), owner.name())) {
            return thisOperand;
        }
        TypedValue enclosing = owner == null ? null
                : enclosingInstanceFor(owner, expression.methodNameSpan());
        if (enclosing != null) {
            return requireValue(enclosing, owner.selfType(), expression.methodNameSpan(),
                    "implicit enclosing method receiver");
        }
        diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                + expression.methodName()
                + "' cannot be referenced from this static or lexical context"));
        return new IrNull(IrType.reference(target.ownerType()), expression.span());
    }

    private static IrType dispatchReceiverType(IrType receiver) {
        if (receiver.isArray() || receiver.isWildcard()) {
            return IrType.reference("ironwood.lang.Object");
        }
        return receiver;
    }

    private void reportPlanningFailure(InvocationPlanningResult<?> result, SourceSpan fallbackSpan,
                                       String operation) {
        if (!result.isRejected() || result.rejections().isEmpty()) {
            diagnostics.add(error(fallbackSpan, "cannot resolve " + operation));
            return;
        }
        InvocationPlanningResult.PlanningRejection rejection = result.rejections().getFirst();
        String details = deepestPlanningMessage(rejection);
        diagnostics.add(error(rejection.span(), "cannot resolve " + operation + ": " + details));
    }

    private static String deepestPlanningMessage(
            InvocationPlanningResult.PlanningRejection rejection) {
        if (rejection.causes().isEmpty()) {
            return rejection.message();
        }
        return rejection.message() + "; " + deepestPlanningMessage(rejection.causes().getFirst());
    }

    private TypedValue lowerCall(CallExpression expression) {
        markAttachedOwnedFieldLoansUncertain(
                "cannot prove a call made before private-field detachment is non-reentrant");
        if (expression.receiver().orElse(null) instanceof SuperExpression superExpression) {
            return lowerSuperCall(expression, superExpression);
        }
        if (expression.receiver().orElse(null) instanceof InterfaceSuperExpression interfaceSuper) {
            return lowerInterfaceSuperCall(expression, interfaceSuper);
        }
        List<CallableSymbol> candidates = List.of();
        IrOperand receiverOperand = null;
        TypeSymbol targetType = currentClass;
        String receiverStaticType = currentClass.name();
        IrType receiverExactType = currentClass.selfType();
        boolean classQualifier = false;
        boolean arrayReceiver = false;
        boolean variableReceiver = false;
        TypeSymbol lexicalMethodOwner = null;

        if (expression.receiver().isPresent()) {
            Expression receiverExpression = expression.receiver().get();
            String qualifierName = qualifiedName(receiverExpression);
            String rootName = rootName(receiverExpression);
            TypeResolver.Resolution qualifierResolution = qualifierName == null
                    ? null : hierarchy.resolveType(qualifierName, currentClass,
                    receiverExpression.span());
            classQualifier = qualifierResolution != null
                    && qualifierResolution.type().isPresent()
                    && (rootName == null || resolve(rootName) == null
                    && hierarchy.lookupField(currentClass.name(), rootName).isEmpty()
                    && !hasLexicalField(rootName) && !hasStaticImportedField(rootName));
            if (classQualifier) {
                targetType = qualifierResolution.type().orElseThrow();
                if (qualifierResolution.inaccessible()) {
                    diagnostics.add(error(receiverExpression.span(), "type '" + targetType.name()
                            + "' is not accessible from package '" + currentClass.packageName() + "'"));
                }
                receiverStaticType = targetType.name();
                receiverExactType = targetType.selfType();
                candidates = targetType.isInterface()
                        ? hierarchy.lookupDeclaredMethods(receiverExactType, expression.methodName())
                        : hierarchy.lookupMethods(receiverExactType, expression.methodName());
            } else {
                TypedValue receiver = lowerExpression(receiverExpression);
                if (receiver.type().isArray()) {
                    arrayReceiver = true;
                    receiverStaticType = "ironwood.lang.Object";
                    receiverExactType = IrType.reference(receiverStaticType);
                    targetType = hierarchy.type(receiverStaticType).orElse(null);
                    if (targetType != null) {
                        candidates = hierarchy.lookupMethods(receiverExactType, expression.methodName());
                    }
                    receiverOperand = requireValue(receiver,
                            IrType.reference(receiverStaticType), receiverExpression.span(),
                            "method receiver");
                } else if (receiver.type().isWildcard()) {
                    receiverStaticType = "ironwood.lang.Object";
                    receiverExactType = IrType.reference(receiverStaticType);
                    targetType = hierarchy.type(receiverStaticType).orElse(null);
                    if (targetType != null) {
                        candidates = hierarchy.lookupMethods(receiverExactType, expression.methodName());
                    }
                    receiverOperand = requireValue(receiver, receiverExactType,
                            receiverExpression.span(), "method receiver");
                } else if (receiver.type().isTypeParameter()) {
                    variableReceiver = true;
                    receiverExactType = receiver.type();
                    IrType erasure = receiver.type().erasure();
                    receiverStaticType = erasure.referenceName();
                    targetType = hierarchy.type(receiverStaticType).orElse(null);
                    candidates = hierarchy.lookupMethods(receiverExactType, expression.methodName());
                    receiverOperand = requireValue(receiver, receiver.type(),
                            receiverExpression.span(), "method receiver");
                } else if (!receiver.type().isNominalReference()) {
                    diagnostics.add(error(receiverExpression.span(),
                            "method call receiver must be an object reference, not "
                                    + typeName(receiver.type())));
                } else {
                    receiverStaticType = receiver.type().referenceName();
                    receiverExactType = captureReceiverType(receiver.type());
                    targetType = hierarchy.type(receiverStaticType).orElse(null);
                    if (targetType != null) {
                        candidates = hierarchy.lookupMethods(receiverExactType, expression.methodName());
                    }
                    receiverOperand = requireValue(receiver, receiver.type(), receiverExpression.span(),
                            "method receiver");
                }
            }
        } else {
            candidates = hierarchy.lookupMethods(currentClass.selfType(), expression.methodName());
            if (candidates.isEmpty()) {
                for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
                     lexical != null; lexical = lexical.enclosingType().orElse(null)) {
                    List<CallableSymbol> lexicalCandidates = hierarchy.lookupMethods(
                            lexical.selfType(), expression.methodName());
                    if (!lexicalCandidates.isEmpty()) {
                        candidates = lexicalCandidates;
                        lexicalMethodOwner = lexical;
                        targetType = lexical;
                        receiverStaticType = lexical.name();
                        receiverExactType = lexical.selfType();
                        break;
                    }
                }
            }
            if (candidates.isEmpty()) {
                candidates = staticImports.methods(currentClass.unit(), expression.methodName());
            }
        }

        List<TypedValue> arguments = expression.arguments().stream().map(this::lowerExpression).toList();
        if (candidates.isEmpty()) {
            String message = expression.receiver().isEmpty() && function.isStatic()
                    ? "unknown static method '" + expression.methodName() + "'"
                    : "type '" + receiverStaticType + "' has no method '"
                            + expression.methodName() + "'";
            diagnostics.add(error(expression.methodNameSpan(), message));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        if (classQualifier) {
            candidates = preferEligible(candidates, CallableSymbol::isStatic);
        } else if (expression.receiver().isEmpty() && function.isStatic()) {
            candidates = preferEligible(candidates, CallableSymbol::isStatic);
        }
        String accessReceiverType = receiverStaticType;
        candidates = preferEligible(candidates, candidate -> isAccessible(
                candidate.accessModifier(), candidate.ownerType(), accessReceiverType, false));
        CallableSymbol target = selectOverload(candidates, arguments,
                "method '" + expression.methodName() + "'", expression.span());
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        checkCheckedExceptions(target, expression.span());
        validateFileTreeVisitor(target, arguments, expression.span());
        if (variableReceiver) {
            targetType = hierarchy.type(target.ownerType()).orElse(targetType);
        }
        if (classQualifier && !target.isStatic()) {
            diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                    + expression.methodName() + "' requires an object receiver"));
            receiverOperand = new IrNull(IrType.reference(targetType.name()), expression.span());
        } else if (expression.receiver().isPresent() && !classQualifier && target.isStatic()
                && hierarchy.type(target.ownerType()).map(TypeSymbol::isInterface).orElse(false)) {
            diagnostics.add(error(expression.methodNameSpan(), "static interface method '"
                    + expression.methodName() + "' must be called through its interface name"));
        } else if (expression.receiver().isEmpty() && !target.isStatic()) {
            if (evaluatingConstructorArguments) {
                diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                        + expression.methodName() + "' cannot be called before constructor delegation"));
            }
            if (lexicalMethodOwner != null) {
                TypedValue enclosing = enclosingInstanceFor(lexicalMethodOwner,
                        expression.methodNameSpan());
                if (enclosing == null) {
                    diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                            + expression.methodName()
                            + "' cannot be referenced from this static or lexical context"));
                    receiverOperand = new IrNull(lexicalMethodOwner.selfType(), expression.span());
                } else {
                    receiverOperand = requireValue(enclosing, lexicalMethodOwner.selfType(),
                            expression.methodNameSpan(), "implicit enclosing method receiver");
                }
            } else if (function.isStatic()) {
                diagnostics.add(error(expression.methodNameSpan(), "instance method '"
                        + expression.methodName()
                        + "' cannot be called from a static method without an object receiver"));
                receiverOperand = new IrNull(currentClass.selfType(), expression.span());
            } else {
                receiverOperand = thisOperand;
            }
        }
        if (!isAccessible(target.accessModifier(), target.ownerType(), receiverStaticType, false)) {
            diagnostics.add(error(expression.methodNameSpan(), memberAccessMessage("method",
                    expression.methodName(), target.accessModifier(), target.ownerType())));
        }

        // Java evaluates the receiver expression and all arguments before a required null check.
        if (!target.isStatic() && expression.receiver().isPresent() && !classQualifier
                && receiverOperand != null) {
            emitNullCheck(receiverOperand, expression.receiver().orElseThrow().span());
        }

        if (target.isStatic()) {
            ensureTypeInitialized(target.ownerType(), expression.span());
        }

        List<IrOperand> operands = new ArrayList<>();
        if (!target.isStatic()) {
            operands.add(receiverOperand == null
                    ? new IrNull(IrType.reference(target.ownerType()), expression.span())
                    : receiverOperand);
        }
        operands.addAll(checkArguments(expression.methodName(), expression.arguments(), arguments,
                target.parameterTypes(), false, expression.span()));

        Optional<IrValueReference> result = target.returnType().equals(IrType.VOID)
                ? Optional.empty()
                : Optional.of(newValue(target.returnType(), expression.span()));
        if (target.isStatic() || target.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE) {
            recordResolvedCall(specializedCallSummary(target, target.linkageName(), arguments),
                    target.linkageName(),
                    target.isStatic() ? null : receiverOperand, arguments,
                    result, target.sourceName());
            emitCall(new IrCallInstruction(result, target.linkageName(), target.returnType(),
                    operands, expression.span()), expression.span());
        } else {
            Set<String> targets = arrayReceiver
                    ? Set.of(target.linkageName())
                    : hierarchy.dispatchTargets(receiverExactType, target);
            if (targets.size() == 1) {
                String linkageName = targets.iterator().next();
                recordResolvedCall(specializedCallSummary(target, linkageName, arguments),
                        linkageName, receiverOperand, arguments, result,
                        target.sourceName());
                boolean interfaceDispatch = targetType.isInterface();
                emitCall(new IrCallInstruction(result, linkageName,
                        target.returnType(), operands,
                        interfaceDispatch ? IrCallKind.DEVIRTUALIZED_INTERFACE
                                : IrCallKind.DEVIRTUALIZED_VIRTUAL,
                        Optional.of(receiverStaticType + "." + target.signatureKey()), expression.span()),
                        expression.span());
            } else if (targetType.isInterface()) {
                if (!recordKnownBorrowDispatch(target, receiverOperand, arguments, result)) {
                    recordUnknownCallEscapes(receiverOperand, arguments,
                            target.sourceName(), expression.span());
                }
                emitCall(new IrInterfaceCallInstruction(result, targetType.name(),
                        hierarchy.dispatchSlot(target), target.returnType(), operands, expression.span()),
                        expression.span());
            } else {
                if (!recordKnownBorrowDispatch(target, receiverOperand, arguments, result)) {
                    recordUnknownCallEscapes(receiverOperand, arguments,
                            target.sourceName(), expression.span());
                }
                emitCall(new IrVirtualCallInstruction(result,
                        hierarchy.dispatchSlot(target), target.returnType(), operands, expression.span()),
                        expression.span());
            }
        }
        return new TypedValue(target.returnType(), result.orElse(null));
    }

    private TypedValue lowerSuperCall(CallExpression expression, SuperExpression superExpression) {
        SuperTarget superclass = resolveSuperTarget(superExpression.span());
        List<TypedValue> arguments = expression.arguments().stream().map(this::lowerExpression).toList();
        if (superclass == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        List<CallableSymbol> candidates = hierarchy.lookupMethods(superclass.type(), expression.methodName());
        if (candidates.isEmpty()) {
            diagnostics.add(error(expression.methodNameSpan(), "superclass '" + superclass.symbol().name()
                    + "' has no method '" + expression.methodName() + "'"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        candidates = preferEligible(candidates, candidate -> !candidate.isStatic());
        candidates = preferEligible(candidates, candidate -> isAccessible(
                candidate.accessModifier(), candidate.ownerType(), null, false));
        CallableSymbol target = selectOverload(candidates, arguments,
                "super method '" + expression.methodName() + "'", expression.span());
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        checkCheckedExceptions(target, expression.span());
        if (target.isStatic()) {
            diagnostics.add(error(expression.methodNameSpan(), "static method '"
                    + expression.methodName() + "' must be called through its class name"));
        }
        if (target.isAbstract()) {
            diagnostics.add(error(expression.methodNameSpan(), "abstract superclass method '"
                    + expression.methodName() + "' cannot be called directly"));
        }
        if (!isAccessible(target.accessModifier(), target.ownerType(), null, false)) {
            diagnostics.add(error(expression.methodNameSpan(), memberAccessMessage("method",
                    expression.methodName(), target.accessModifier(), target.ownerType())));
        }
        IrType targetOwnerType = hierarchy.exactSupertypes(superclass.type()).stream()
                .filter(candidate -> candidate.referenceName().equals(target.ownerType()))
                .findFirst().orElse(IrType.reference(target.ownerType()));
        IrOperand targetReceiver = convertReference(superclass.receiver(), targetOwnerType,
                superExpression.span());
        List<IrOperand> operands = new ArrayList<>();
        if (!target.isStatic()) {
            operands.add(targetReceiver);
        }
        operands.addAll(checkArguments(expression.methodName(), expression.arguments(), arguments,
                target.parameterTypes(), false, expression.span()));
        Optional<IrValueReference> result = target.returnType().equals(IrType.VOID)
                ? Optional.empty() : Optional.of(newValue(target.returnType(), expression.span()));
        recordResolvedCall(escapeSummaries.summary(target), target.linkageName(),
                targetReceiver, arguments, result, target.sourceName());
        emitCall(new IrCallInstruction(result, target.linkageName(), target.returnType(),
                operands, expression.span()), expression.span());
        return new TypedValue(target.returnType(), result.orElse(null));
    }

    private TypedValue lowerInterfaceSuperCall(CallExpression expression,
                                               InterfaceSuperExpression interfaceSuper) {
        List<TypedValue> arguments = expression.arguments().stream().map(this::lowerExpression).toList();
        if (function.isStatic()) {
            diagnostics.add(error(interfaceSuper.span(),
                    "qualified interface super cannot be used in a static method"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        if (evaluatingConstructorArguments) {
            diagnostics.add(error(interfaceSuper.span(),
                    "qualified interface super cannot be used before superclass construction"));
        }
        TypeResolver.Resolution qualifierResolution = hierarchy.resolveType(
                interfaceSuper.interfaceName(), currentClass,
                interfaceSuper.interfaceNameSpan());
        if (qualifierResolution.ambiguous()) {
            diagnostics.add(error(interfaceSuper.interfaceNameSpan(), "ambiguous interface type '"
                    + interfaceSuper.interfaceName() + "' in qualified super call"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        TypeSymbol qualifier = qualifierResolution.type().orElse(null);
        if (qualifier == null || !qualifier.isInterface()) {
            diagnostics.add(error(interfaceSuper.interfaceNameSpan(), "qualified super type '"
                    + interfaceSuper.interfaceName() + "' must name a direct superinterface"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        if (qualifierResolution.inaccessible()) {
            diagnostics.add(error(interfaceSuper.interfaceNameSpan(), "interface '"
                    + qualifier.name() + "' is not accessible from package '"
                    + currentClass.packageName() + "'"));
        }
        IrType exactQualifier = hierarchy.directInterfaceTypes(currentClass.selfType()).stream()
                .filter(candidate -> candidate.referenceName().equals(qualifier.name()))
                .findFirst().orElse(null);
        if (exactQualifier == null) {
            diagnostics.add(error(interfaceSuper.interfaceNameSpan(), "interface '"
                    + qualifier.name() + "' is not a direct superinterface of '"
                    + currentClass.name() + "'"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        IrType redundantThrough = hierarchy.directParents(currentClass.selfType()).stream()
                .filter(parent -> !parent.referenceName().equals(qualifier.name()))
                .filter(parent -> hierarchy.isSubtype(parent.referenceName(), qualifier.name()))
                .findFirst().orElse(null);
        if (redundantThrough != null) {
            diagnostics.add(error(interfaceSuper.interfaceNameSpan(), "interface '"
                    + qualifier.name() + "' is a redundant qualified superinterface of '"
                    + currentClass.name() + "' because direct supertype '"
                    + redundantThrough.referenceName() + "' already extends it"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        List<CallableSymbol> allCandidates = hierarchy.maximallySpecificInterfaceMethods(
                exactQualifier, expression.methodName());
        List<CallableSymbol> candidates = allCandidates.stream()
                .filter(method -> !method.isAbstract() && !method.isStatic()
                        && method.accessModifier() == ironwood.compiler.ast.AccessModifier.PUBLIC)
                .toList();
        if (candidates.isEmpty()) {
            diagnostics.add(error(expression.methodNameSpan(), "interface '" + qualifier.name()
                    + "' has no applicable default method '" + expression.methodName() + "'"));
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        CallableSymbol target = selectOverload(candidates, arguments,
                "interface super method '" + expression.methodName() + "'", expression.span());
        if (target == null) {
            return new TypedValue(IrType.I32, defaultValue(IrType.I32, expression.span()));
        }
        checkCheckedExceptions(target, expression.span());
        IrType targetOwnerType = hierarchy.exactSupertypes(exactQualifier).stream()
                .filter(candidate -> candidate.referenceName().equals(target.ownerType()))
                .findFirst().orElse(IrType.reference(target.ownerType()));
        IrOperand targetReceiver = convertReference(thisOperand, targetOwnerType,
                interfaceSuper.span());
        List<IrOperand> operands = new ArrayList<>();
        operands.add(targetReceiver);
        operands.addAll(checkArguments(expression.methodName(), expression.arguments(), arguments,
                target.parameterTypes(), false, expression.span()));
        Optional<IrValueReference> result = target.returnType().equals(IrType.VOID)
                ? Optional.empty() : Optional.of(newValue(target.returnType(), expression.span()));
        recordResolvedCall(escapeSummaries.summary(target), target.linkageName(),
                targetReceiver, arguments, result, target.sourceName());
        emitCall(new IrCallInstruction(result, target.linkageName(), target.returnType(),
                operands, expression.span()), expression.span());
        return new TypedValue(target.returnType(), result.orElse(null));
    }

    private void recordResolvedCall(EscapeSummaryAnalyzer.EscapeSummary summary,
                                    String resolvedLinkageName,
                                    IrOperand receiver, List<TypedValue> arguments,
                                    Optional<IrValueReference> result, String methodName) {
        CallableSymbol resolved = escapeSummaries.callable(resolvedLinkageName);
        if (resolved != null && recordListViewFactory(resolved, summary, arguments, result)) {
            return;
        }
        Set<Integer> handledArguments = resolved == null ? null
                : recordContainerCall(resolved, receiver, arguments, result);
        if (handledArguments == null) {
            // This call can expose an element, an iterator, or a callback alias.
            exposeContainerContents(receiver, "method '" + methodName
                    + "' can expose stored data-structure references");
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (handledArguments == null || !handledArguments.contains(index)) {
                exposeContainerContents(arguments.get(index).operand(), "argument to method '"
                        + methodName + "' can expose stored data-structure references");
            }
        }
        if (resolved != null && PoolSemantics.isRelease(resolved)
                && receiver != null && arguments.size() == 1) {
            pendingPoolTransfer = new PoolTransfer(receiver, arguments.getFirst().operand());
            return;
        }
        if (result.filter(value -> value.type().isReference()).isEmpty()
                && escapeSummaries.isNonRetaining(resolvedLinkageName)) {
            exposeArrayElements(receiver, "borrowed call can observe reference-array elements");
            arguments.forEach(argument -> exposeArrayElements(argument.operand(),
                    "borrowed call can observe reference-array elements"));
            return;
        }
        if (!isBorrowingStringJoin(resolved, arguments)) {
            exposeArrayElements(receiver, "call to method '" + methodName
                    + "' can observe reference-array elements");
            arguments.forEach(argument -> exposeArrayElements(argument.operand(),
                    "call to method '" + methodName + "' can observe reference-array elements"));
        }
        ReturnOrigin exactOrigin = exactDirectReturnOrigin(summary);
        SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin exactBorrow =
                exactBorrowedReturnOrigin(summary);
        boolean preciseReturn = exactOrigin != null || exactBorrow != null;
        if (receiver != null && (preciseReturn
                ? summary.thisEscapesWithoutReturn()
                : summary.thisEscapes() || summary.thisEscapesWithoutReturn())) {
            markEscaped(receiver, "allocation escapes through receiver of method '" + methodName + "'");
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (handledArguments != null && handledArguments.contains(index)) { continue; }
            if (preciseReturn
                    ? summary.parameterEscapesWithoutReturn(index)
                    : summary.parameterEscapes(index)
                    || summary.parameterEscapesWithoutReturn(index)) {
                markEscaped(arguments.get(index).operand(), "allocation escapes through argument "
                        + (index + 1) + " of method '" + methodName + "'");
            }
        }
        FieldSymbol borrowedField = ownedArrayFields.borrowedReturnField(resolvedLinkageName);
        if (result.isPresent() && receiver != null && borrowedField != null) {
            AllocationInfo owner = allocationOf(receiver);
            if (owner != null) {
                IrValueReference borrowed = result.orElseThrow();
                allocationsByOperand.put(borrowed, owner);
                ownedHelperBorrows.add(borrowed);
                if (borrowedField.type().isNominalReference()) {
                    ownedHelperBorrowTypes.put(borrowed,
                            borrowedField.type().referenceName());
                }
            }
            return;
        }
        if (result.isPresent() && exactBorrow != null) {
            ReturnOrigin ownerOrigin = exactBorrow.ownerOrigin();
            IrOperand source = switch (ownerOrigin.kind()) {
                case THIS -> receiver;
                case PARAMETER -> ownerOrigin.parameterIndex() < arguments.size()
                        ? arguments.get(ownerOrigin.parameterIndex()).operand() : null;
                case ELEMENT_OF_PARAMETER -> null;
            };
            AllocationInfo owner = allocationOf(source);
            if (owner != null) {
                IrValueReference borrowed = result.orElseThrow();
                allocationsByOperand.put(borrowed, owner);
                ownedHelperBorrows.add(borrowed);
                if (exactBorrow.helperType().equals(PoolSemantics.POOL_VALUE_BORROW)) {
                    // A nested pool reached through a borrow has no independently
                    // proved pool identity. Keep its lifetime dependency only.
                    if (!isDependentBorrow(source)) { poolValueOwners.put(borrowed, owner); }
                } else if (!exactBorrow.helperType().equals(PoolSemantics.DYNAMIC_BORROW)) {
                    ownedHelperBorrowTypes.put(borrowed, exactBorrow.helperType());
                }
            }
            return;
        }
        if (result.isPresent() && summary.returnsOwnedFresh()) {
            AllocationInfo allocation = AllocationInfo.freshCall(controlFlowDepth);
            allocations.add(allocation);
            allocationsByOperand.put(result.orElseThrow(), allocation);
            if (unfreed != null && !summary.mayReturnNull()) {
                unfreed.register(allocation, result.orElseThrow().sourceSpan(),
                        "fresh result of '" + methodName + "'", false);
                unfreedFreshResults.add(result.orElseThrow());
            }
            return;
        }
        if (exactOrigin == null || result.isEmpty()) {
            return;
        }
        IrOperand source = switch (exactOrigin.kind()) {
            case THIS -> receiver;
            case PARAMETER -> exactOrigin.parameterIndex() < arguments.size()
                    ? arguments.get(exactOrigin.parameterIndex()).operand() : null;
            case ELEMENT_OF_PARAMETER -> null;
        };
        AllocationInfo allocation = allocationOf(source);
        if (allocation != null) {
            allocationsByOperand.put(result.orElseThrow(), allocation);
            propagateOwnedHelperBorrow(result.orElseThrow(), source);
        }
    }

    private static boolean isKnownContainer(AllocationInfo allocation) {
        return allocation.constructedType != null && allocation.constructedType.isNominalReference()
                && DataStructureSemantics.isBorrowingContainer(allocation.constructedType.referenceName());
    }

    /** Returns handled argument indices, or null for unaudited contents effects. */
    private Set<Integer> recordContainerCall(CallableSymbol method, IrOperand receiver,
                                             List<TypedValue> arguments,
                                             Optional<IrValueReference> result) {
        AllocationInfo owner = allocationOf(receiver);
        if (owner == null || owner.state != AllocationState.ACTIVE || isDependentBorrow(receiver)
                || owner.constructedType == null
                || !owner.constructedType.referenceName().equals(method.ownerType())) { return null; }
        if (method.ownerType().equals("ironwood.ds.UnmodifiableList")
                && DataStructureSemantics.isSizeQuery(method)) { return Set.of(); }
        if (!isKnownContainer(owner)) { return null; }
        if (DataStructureSemantics.isSizeQuery(method)) { return Set.of(); }
        if (DataStructureSemantics.clearsBorrowedItems(method)) {
            pendingContainerClear = owner;
            return Set.of();
        }
        if (method.ownerType().equals("ironwood.ds.ArrayLinkedList")
                && method.sourceName().equals("clear")) {
            return Set.of(); // Preserve loans to potentially readable inactive slots.
        }
        Set<Integer> retained = DataStructureSemantics.retainedArguments(method);
        boolean removal = DataStructureSemantics.isRemoval(method);
        if ((retained.isEmpty() && !removal) || (DataStructureSemantics.invokesKeyCallbacks(method)
                && !hasBorrowingKeyCallbacks(owner.constructedType))) { return null; }
        if (DataStructureSemantics.invokesKeyCallbacks(method)) {
            exposeContainerContents(arguments.getFirst().operand(),
                    "key callbacks can observe nested data-structure contents");
        }
        if (result.isPresent() && result.orElseThrow().type().isReference()
                && !result.orElseThrow().sourceSpan().equals(discardedCallSpan)) {
            exposeContainerContents(receiver, "returned value can alias a stored data-structure reference");
        }
        // Insertion may retain before throwing. Keep these edges on exceptional
        // paths too; only a successful clear or destruction can discharge them.
        for (int index : retained) {
            IrOperand value = arguments.get(index).operand();
            if (exposedContainerContents.contains(owner)) {
                markEscaped(value, "allocation is stored in a data structure whose contents were exposed");
            } else {
                AllocationInfo child = allocationOf(value);
                if (child != null) { addWrapperBorrow(owner, child); }
            }
        }
        if (removal && (DataStructureSemantics.invokesKeyCallbacks(method)
                || method.ownerType().equals("ironwood.ds.IdentityHashMap")
                || method.ownerType().equals("ironwood.ds.IdentityHashSet"))) { return Set.of(0); }
        boolean borrowedByteKey = method.ownerType().equals("ironwood.ds.ByteBufferMap")
                && (method.parameterTypes().getFirst().isArray()
                || hasBorrowingCallbacks(arguments.getFirst().type(), Map.of(
                        "remaining", List.of(), "position", List.of(), "get", List.of(IrType.I32))));
        boolean borrowedTextKey = method.ownerType().equals("ironwood.ds.CharSequenceMap")
                && hasBorrowingCallbacks(arguments.getFirst().type(), Map.of(
                        "length", List.of(), "charAt", List.of(IrType.I32)));
        if (borrowedByteKey || borrowedTextKey) {
            Set<Integer> handled = new LinkedHashSet<>(retained);
            handled.add(0); // Copied keys are observed, never retained by the map.
            return Set.copyOf(handled);
        }
        return retained;
    }

    private boolean hasBorrowingKeyCallbacks(IrType containerType) {
        if (containerType.typeArguments().isEmpty()) { return false; }
        return hasBorrowingCallbacks(containerType.typeArguments().getFirst(), Map.of(
                "hashCode", List.of(), "equals", List.of(IrType.reference("ironwood.lang.Object"))));
    }

    private boolean hasBorrowingCallbacks(IrType type, Map<String, List<IrType>> signatures) {
        if (!type.isNominalReference()) { return false; }
        for (var signature : signatures.entrySet()) {
            List<CallableSymbol> methods = hierarchy.lookupMethods(type, signature.getKey()).stream()
                    .filter(method -> !method.isStatic() && method.parameterTypes().equals(signature.getValue()))
                    .filter(method -> !method.returnType().isReference()).toList();
            if (methods.size() != 1) { return false; }
            Set<String> targets = hierarchy.dispatchTargets(type, methods.getFirst());
            if (targets.isEmpty()) { return false; }
            for (String target : targets) {
                EscapeSummaryAnalyzer.EscapeSummary effects = escapeSummaries.summary(target);
                if (effects == null || effects.thisEscapes() || effects.thisEscapesWithoutReturn()) { return false; }
                for (int index = 0; index < signature.getValue().size(); index++) {
                    if (effects.parameterEscapes(index) || effects.parameterEscapesWithoutReturn(index)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean recordListViewFactory(CallableSymbol method,
                                          EscapeSummaryAnalyzer.EscapeSummary summary,
                                          List<TypedValue> arguments,
                                          Optional<IrValueReference> result) {
        if (!DataStructureSemantics.isListViewFactory(method) || !summary.returnsOwnedFresh()
                || result.isEmpty()) { return false; }
        TypeSymbol view = hierarchy.type("ironwood.ds.UnmodifiableList").orElse(null);
        if (view == null || view.constructors().size() != 1) { return false; }
        CallableSymbol constructor = view.constructors().getFirst();
        EscapeSummaryAnalyzer.EscapeSummary effects = escapeSummaries.summary(constructor);
        FieldSymbol field = escapeSummaries.retainedParameterField(constructor, 0);
        if (effects.thisEscapes() || !effects.parameterRetainedByReceiverOnly(0)
                || field == null || !ownedArrayFields.isEncapsulated(field)) { return false; }
        AllocationInfo owner = AllocationInfo.freshCall(controlFlowDepth);
        owner.constructedType = result.orElseThrow().type();
        allocations.add(owner);
        allocationsByOperand.put(result.orElseThrow(), owner);
        AllocationInfo backing = allocationOf(arguments.getFirst().operand());
        if (backing != null) { pendingWrapperBorrow = new WrapperBorrow(owner, backing); }
        return true;
    }

    private void addWrapperBorrow(AllocationInfo owner, AllocationInfo child) {
        Set<AllocationInfo> borrowed = new LinkedHashSet<>(constructorBorrows.getOrDefault(owner, Set.of()));
        borrowed.add(child);
        constructorBorrows.put(owner, Set.copyOf(borrowed));
    }

    private void finishContainerCall(AllocationInfo cleared, WrapperBorrow wrapper) {
        if (cleared != null) { constructorBorrows.remove(cleared); }
        if (wrapper != null) { addWrapperBorrow(wrapper.owner(), wrapper.child()); }
    }

    private record WrapperBorrow(AllocationInfo owner, AllocationInfo child) {}

    private void exposeContainerContents(IrOperand operand, String reason) {
        AllocationInfo allocation = allocationOf(operand);
        if (allocation != null) {
            exposeContainerContents(allocation, reason,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    private void exposeContainerContents(AllocationInfo allocation, String reason,
                                         Set<AllocationInfo> visited) {
        if (!visited.add(allocation)) { return; }
        Set<AllocationInfo> children = constructorBorrows.getOrDefault(allocation, Set.of());
        if (isKnownContainer(allocation)) {
            exposedContainerContents.add(allocation);
            for (AllocationInfo child : children) {
                markEscaped(child, reason, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        } else {
            children.forEach(child -> exposeContainerContents(child, reason, visited));
        }
        knownArraySlots.entrySet().stream()
                .filter(entry -> entry.getKey().container() == allocation)
                .map(Map.Entry::getValue)
                .forEach(child -> exposeContainerContents(child, reason, visited));
    }

    private static ReturnOrigin exactDirectReturnOrigin(
            EscapeSummaryAnalyzer.EscapeSummary summary) {
        if (summary.mayReturnNonOrigin() || summary.mayReturnFresh()
                || summary.mayReturnNull()
                || !summary.borrowedReturnedOrigins().isEmpty()
                || summary.returnedOrigins().size() != 1) {
            return null;
        }
        ReturnOrigin origin = summary.returnedOrigins().iterator().next();
        return origin.kind() == ReturnOrigin.Kind.ELEMENT_OF_PARAMETER ? null : origin;
    }

    private static SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin
            exactBorrowedReturnOrigin(EscapeSummaryAnalyzer.EscapeSummary summary) {
        if (summary.mayReturnNonOrigin() || summary.mayReturnFresh()
                || !summary.returnedOrigins().isEmpty()
                || summary.borrowedReturnedOrigins().size() != 1) {
            return null;
        }
        SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin origin =
                summary.borrowedReturnedOrigins().iterator().next();
        return origin.ownerOrigin().kind() == ReturnOrigin.Kind.ELEMENT_OF_PARAMETER
                ? null : origin;
    }

    private void recordUnknownCallEscapes(IrOperand receiver, List<TypedValue> arguments,
                                          String methodName, SourceSpan span) {
        exposeContainerContents(receiver, "polymorphic call can expose stored data-structure references");
        arguments.forEach(argument -> exposeContainerContents(argument.operand(),
                "polymorphic call can expose stored data-structure references"));
        if (escapeSummaries.isNonRetainingPrimitiveCall(function.linkageName(), span, methodName)) {
            exposeArrayElements(receiver, "borrowed call can observe reference-array elements");
            arguments.forEach(argument -> exposeArrayElements(argument.operand(),
                    "borrowed call can observe reference-array elements"));
            return;
        }
        markEscaped(receiver, "cannot prove receiver of polymorphic method '" + methodName
                + "' does not escape");
        for (int index = 0; index < arguments.size(); index++) {
            markEscaped(arguments.get(index).operand(), "cannot prove argument " + (index + 1)
                    + " of polymorphic method '" + methodName + "' does not escape");
        }
    }

    private boolean recordKnownBorrowDispatch(CallableSymbol target, IrOperand receiver,
                                              List<TypedValue> arguments,
                                              Optional<IrValueReference> result) {
        String concreteType = ownedHelperBorrowTypes.get(receiver);
        if (concreteType == null) {
            Set<String> poolTargets = hierarchy.dispatchTargets(receiver.type(), target);
            List<CallableSymbol> methods = poolTargets.stream().map(escapeSummaries::callable).toList();
            if (methods.isEmpty() || methods.stream().anyMatch(java.util.Objects::isNull)
                    || !(methods.stream().allMatch(PoolSemantics::isCheckout)
                    || methods.stream().allMatch(PoolSemantics::isRelease))) {
                return false;
            }
            // The source remains polymorphic. Every closed-world target must
            // have the same audited ownership contract before it can be used.
            CallableSymbol representative = methods.getFirst();
            recordResolvedCall(escapeSummaries.summary(representative), representative.linkageName(),
                    receiver, arguments, result, target.sourceName());
            return true;
        }
        Set<String> targets = hierarchy.dispatchTargets(
                IrType.reference(concreteType), target);
        if (targets.size() != 1) {
            return false;
        }
        String linkageName = targets.iterator().next();
        EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(linkageName);
        recordResolvedCall(summary == null
                        ? EscapeSummaryAnalyzer.EscapeSummary.unknown(target) : summary,
                linkageName, receiver, arguments, result, target.sourceName());
        return true;
    }

    private List<IrOperand> checkArguments(String targetName, List<Expression> expressions,
                                           List<TypedValue> arguments, List<IrType> expectedTypes,
                                           boolean constructor, SourceSpan callSpan) {
        if (arguments.size() != expectedTypes.size()) {
            diagnostics.add(error(expressions.isEmpty() ? callSpan
                            : new SourceSpan(expressions.getFirst().span().start(), expressions.getLast().span().end()),
                    (constructor ? "constructor for class '" + targetName + "'" : "method '" + targetName + "'")
                            + " expects " + expectedTypes.size() + " argument(s) but received " + arguments.size()));
        }
        List<IrOperand> operands = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            TypedValue argument = arguments.get(index);
            IrType expected = index < expectedTypes.size() ? expectedTypes.get(index) : argument.type();
            if (index < expectedTypes.size() && !isAssignable(expected, argument.type())) {
                diagnostics.add(error(expressions.get(index).span(),
                        "argument " + (index + 1) + " of '" + targetName + "' must be "
                                + typeName(expected) + " but is " + typeName(argument.type())));
            }
            operands.add(requireValue(argument, expected, expressions.get(index).span(), "method argument"));
        }
        return operands;
    }

    private CallableSymbol selectOverload(List<CallableSymbol> candidates,
                                          List<TypedValue> arguments,
                                          String targetDescription,
                                          SourceSpan callSpan) {
        List<IrType> argumentTypes = arguments.stream().map(TypedValue::type).toList();
        ClassHierarchy.OverloadResolution resolution = hierarchy.resolveOverload(candidates, argumentTypes);
        if (resolution.selected().isPresent()) {
            return resolution.selected().orElseThrow();
        }
        if (candidates.size() == 1 && resolution.applicable().isEmpty()) {
            CallableSymbol candidate = candidates.getFirst();
            if (arguments.size() != candidate.parameterTypes().size()) {
                String callable = candidate.isConstructor()
                        ? targetDescription : "method '" + candidate.sourceName() + "'";
                diagnostics.add(error(callSpan, callable + " expects "
                        + candidate.parameterTypes().size() + " argument(s) but received "
                        + arguments.size()));
            }
            int checkedArguments = Math.min(arguments.size(), candidate.parameterTypes().size());
            for (int index = 0; index < checkedArguments; index++) {
                IrType expected = candidate.parameterTypes().get(index);
                IrType actual = argumentTypes.get(index);
                if (!isAssignable(expected, actual)) {
                    diagnostics.add(error(callSpan, "argument " + (index + 1) + " of '"
                            + candidate.sourceName() + "' must be " + typeName(expected)
                            + " but is " + typeName(actual)));
                }
            }
            return null;
        }
        String supplied = argumentTypes.stream().map(IrType::displayName)
                .reduce((left, right) -> left + ", " + right).orElse("");
        if (resolution.applicable().isEmpty()) {
            diagnostics.add(error(callSpan, "no applicable " + targetDescription
                    + " for argument types (" + supplied + "); candidates are "
                    + candidates.stream().map(CallableSymbol::signatureKey)
                    .reduce((left, right) -> left + ", " + right).orElse("none")));
        } else {
            diagnostics.add(error(callSpan, "ambiguous " + targetDescription
                    + " for argument types (" + supplied + "); matching candidates are "
                    + resolution.mostSpecific().stream().map(CallableSymbol::signatureKey)
                    .reduce((left, right) -> left + ", " + right).orElse("none")));
        }
        return null;
    }

    private static List<CallableSymbol> preferEligible(
            List<CallableSymbol> candidates,
            java.util.function.Predicate<CallableSymbol> eligibility) {
        List<CallableSymbol> eligible = candidates.stream().filter(eligibility).toList();
        return eligible.isEmpty() ? candidates : eligible;
    }

    private IrOperand requireCondition(TypedValue value, SourceSpan span, String construct) {
        if (!value.type().equals(IrType.I1)) {
            diagnostics.add(error(span, construct + " condition must have type boolean, not " + typeName(value.type())));
        }
        return requireValue(value, IrType.I1, span, construct + " condition");
    }

    private IrOperand requireValue(TypedValue value, IrType expectedType, SourceSpan span, String context) {
        checkNotFreed(value.operand(), span);
        if (value.type().equals(IrType.VOID) || value.operand() == null) {
            diagnostics.add(error(span, "void expression cannot be used as a value in " + context));
            return defaultValue(expectedType, span);
        }
        if (value.type().equals(IrType.NULL) && expectedType.isReference()) {
            return new IrNull(expectedType, value.operand().sourceSpan());
        }
        if (value.type().equals(expectedType)) {
            return value.operand();
        }
        if (value.type().isNumeric() && expectedType.isNumeric()) {
            return convertNumeric(value.operand(), expectedType, span);
        }
        if (value.type().isReference() && expectedType.isReference()
                && !value.type().equals(expectedType)
                && hierarchy.isAssignable(expectedType, value.type())) {
            return convertReference(value.operand(), expectedType, span);
        }
        return defaultValue(expectedType, span);
    }

    private IrOperand assignmentValue(TypedValue value, IrType expectedType,
                                      SourceSpan span, String context) {
        if (value.type().equals(IrType.VOID) || value.operand() == null) {
            return requireValue(value, expectedType, span, context);
        }
        return isAssignmentConvertible(expectedType, value)
                ? requireValue(value, expectedType, span, context)
                : defaultValue(expectedType, span);
    }

    private IrOperand convertNumeric(IrOperand value, IrType targetType, SourceSpan span) {
        if (value == null) {
            return defaultValue(targetType, span);
        }
        if (value.type().equals(targetType)) {
            return value;
        }
        if (!value.type().isNumeric() || !targetType.isNumeric()) {
            return defaultValue(targetType, span);
        }
        if (value.type().isFloating()
                && (targetType.equals(IrType.I8) || targetType.equals(IrType.I16)
                || targetType.equals(IrType.U16))) {
            value = convertNumeric(value, IrType.I32, span);
        }
        IrValueReference result = newValue(targetType, span);
        currentBlock.addInstruction(new IrNumericConversionInstruction(result, value, span));
        return result;
    }

    private IrOperand convertReference(IrOperand value, IrType targetType, SourceSpan span) {
        if (value.type().equals(targetType)) {
            return value;
        }
        IrValueReference result = newValue(targetType, span);
        currentBlock.addInstruction(new IrReferenceConversionInstruction(result, value, span));
        AllocationInfo allocation = allocationOf(value);
        if (allocation != null) {
            allocationsByOperand.put(result, allocation);
            propagateOwnedHelperBorrow(result, value);
        }
        return result;
    }

    private IrType resolveType(TypeName type) {
        if (type.kind() == TypeName.Kind.WILDCARD) {
            return switch (type.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(resolveType(type.wildcardBound()));
                case SUPER -> IrType.wildcardSuper(resolveType(type.wildcardBound()));
            };
        }
        if (type.kind() == TypeName.Kind.ARRAY) {
            return IrType.array(resolveType(type.elementType()));
        }
        if (type.kind() != TypeName.Kind.REFERENCE) {
            return SemanticAnalyzer.irType(type);
        }
        Optional<IrType> callableTypeParameter = function.typeVariable(type.referenceName())
                .map(TypeVariableSymbol::irType);
        if (callableTypeParameter.isPresent()) {
            if (!type.typeArguments().isEmpty()) {
                diagnostics.add(error(type.span(), "type parameter '" + type.referenceName()
                        + "' cannot have type arguments"));
            }
            return callableTypeParameter.orElseThrow();
        }
        Optional<IrType> typeParameter = currentClass.typeParameter(type.referenceName());
        if (typeParameter.isPresent()) {
            if (!type.typeArguments().isEmpty()) {
                diagnostics.add(error(type.span(), "type parameter '" + type.referenceName()
                        + "' cannot have type arguments"));
            }
            if (function.isStatic()) {
                diagnostics.add(error(type.span(), "class type parameter '" + type.referenceName()
                        + "' is not available in a static context"));
            }
            return typeParameter.orElseThrow();
        }
        TypeResolver.Resolution resolution = hierarchy.resolveType(type.referenceName(), currentClass,
                type.span());
        if (resolution.ambiguous()) {
            diagnostics.add(error(type.span(), "ambiguous type '" + type.referenceName() + "'"));
            return IrType.reference(type.referenceName(), type.typeArguments().stream()
                    .map(this::resolveType).toList());
        }
        TypeSymbol resolved = resolution.type().orElse(null);
        if (resolved == null) {
            diagnostics.add(error(type.span(), "unknown class type '" + type.referenceName() + "'"));
            return IrType.reference(type.referenceName(), type.typeArguments().stream()
                    .map(this::resolveType).toList());
        }
        if (resolution.inaccessible()) {
            diagnostics.add(error(type.span(), "type '" + resolved.name()
                    + "' is package-private in package '" + resolved.packageName() + "'"));
        }
        int segmentArity = type.lastSegmentTypeArgumentCount();
        List<TypeName> finalTypeArguments = type.typeArguments().subList(
                type.typeArguments().size() - segmentArity, type.typeArguments().size());
        List<IrType> explicitArguments = finalTypeArguments.stream().map(this::resolveType).toList();
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
                    IrType qualifierType = resolveType(qualifier);
                    enclosingView = hierarchy.exactClassSupertype(qualifierType,
                            resolved.enclosingType().orElseThrow());
                    if (enclosingView.isEmpty()) {
                        diagnostics.add(error(type.span(), "type qualifier '" + qualifier.displayName()
                                + "' does not provide an enclosing instance for member type '"
                                + resolved.sourceName() + "'"));
                    }
                } else {
                    enclosingView = hierarchy.enclosingTypeView(currentClass, resolved);
                }
                if (enclosingView.isPresent()) {
                    if (function.isStatic() && containsTypeVariable(enclosingView.orElseThrow())) {
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
        for (int index = 0; index < Math.min(explicitArguments.size(),
                resolved.declaredTypeParameters().size()); index++) {
            IrType argument = explicitArguments.get(index);
            TypeVariableSymbol parameter = resolved.declaredTypeParameters().get(index);
            if (!argument.isReference()
                    && !(argument.isPrimitive() && parameter.permitsPrimitive())) {
                diagnostics.add(error(finalTypeArguments.get(index).span(), "generic type argument '"
                        + finalTypeArguments.get(index).displayName()
                        + "' requires an unbounded type parameter"));
            }
        }
        if (segmentArityValid && resolved.typeParameters().size() == arguments.size()) {
            hierarchy.validateInstantiation(resolved, arguments, source, type.span(), diagnostics);
        }
        return IrType.reference(resolved.name(), arguments);
    }

    private IrType captureReceiverType(IrType receiver) {
        return hierarchy.captureReceiver(receiver, function.linkageName() + ":" + nextCaptureId++);
    }

    private static boolean containsTypeVariable(IrType type) {
        if (type.isTypeParameter()) {
            return true;
        }
        if (type.isArray()) {
            return containsTypeVariable(type.elementType());
        }
        return type.typeArguments().stream().anyMatch(FunctionAnalyzer::containsTypeVariable);
    }

    private static boolean containsWildcard(IrType type) {
        if (type.isWildcard()) {
            return true;
        }
        if (type.isArray()) {
            return containsWildcard(type.elementType());
        }
        return type.typeArguments().stream().anyMatch(FunctionAnalyzer::containsWildcard);
    }

    private static boolean isReifiableType(IrType type) {
        if (type.isArray()) {
            return isReifiableType(type.elementType());
        }
        if (type.isTypeParameter()) {
            return false;
        }
        if (type.isWildcard()) {
            return type.wildcardKind() == IrType.WildcardKind.UNBOUNDED;
        }
        if (!type.isNominalReference()) {
            return !type.equals(IrType.VOID) && !type.equals(IrType.NULL)
                    && !type.equals(IrType.EXCEPTION);
        }
        return type.typeArguments().isEmpty() || type.typeArguments().stream()
                .allMatch(argument -> argument.isWildcard()
                        && argument.wildcardKind() == IrType.WildcardKind.UNBOUNDED);
    }

    /** Pure semantic adapter used while overload candidates are only being explored. */
    private final class FunctionPlanningContext
            implements AnonymousParentBinder.PrimaryPlanningContext {
        private static final IrType ROOT_OBJECT = IrType.reference("ironwood.lang.Object");
        private final Map<String, TypeVariableSymbol> planningCaptures = new LinkedHashMap<>();

        private Map<String, TypeVariableSymbol> captureCheckpoint() {
            return new LinkedHashMap<>(planningCaptures);
        }

        private void restoreCaptureCheckpoint(Map<String, TypeVariableSymbol> checkpoint) {
            planningCaptures.clear();
            planningCaptures.putAll(checkpoint);
        }

        private void commitCaptures() {
            hierarchy.registerCaptures(planningCaptures);
        }

        @Override
        public InvocationPlanningResult<IrType> lexicalNameType(NameExpression expression) {
            LocalSymbol local = resolve(expression.name());
            if (local != null) {
                return InvocationPlanningResult.resolved(local.type());
            }
            LocalClassSemantics.VariableIdentity snapshot = hierarchy.lexicalVariable(
                    expression.name(), currentClass, expression.span()).orElse(null);
            if (snapshot != null) {
                return planVariableType(snapshot);
            }
            ClassHierarchy.FieldResolution current = hierarchy.resolveField(
                    currentClass.selfType(), expression.name());
            if (current.ambiguous()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_FIELD,
                        "ambiguous inherited field '" + expression.name() + "'", expression.span());
            }
            FieldSymbol field = current.selected().orElse(null);
            if (field != null) {
                return lexicalFieldType(field, expression.span());
            }
            LocalClassSemantics.VariableIdentity captured = currentClass.variableAt(expression.span())
                    .orElse(null);
            if (captured != null) {
                TypeSymbol.CaptureSlot capture = currentClass.captureSlot(captured.id()).orElse(null);
                if (capture != null) {
                    return InvocationPlanningResult.resolved(capture.type());
                }
            }
            for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
                 lexical != null; lexical = lexical.enclosingType().orElse(null)) {
                ClassHierarchy.FieldResolution inherited = hierarchy.resolveField(
                        lexical.selfType(), expression.name());
                if (inherited.ambiguous()) {
                    return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_FIELD,
                            "ambiguous inherited field '" + expression.name() + "'", expression.span());
                }
                field = inherited.selected().orElse(null);
                if (field != null) {
                    return lexicalFieldType(field, expression.span());
                }
                if (lexical.isStaticMember()) {
                    break;
                }
            }
            FieldSymbol imported = resolveStaticImportedField(expression.name(),
                    expression.span(), false);
            if (imported != null) {
                return lexicalFieldType(imported, expression.span());
            }
            if (staticImports.fields(currentClass.unit(), expression.name()).size() > 1) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_FIELD,
                        "ambiguous statically imported field '" + expression.name() + "'",
                        expression.span());
            }
            return InvocationPlanningResult.notFound();
        }

        @Override
        public TypeSymbol accessingType() {
            return currentClass;
        }

        @Override
        public Map<String, TypeVariableSymbol> plannedCaptures() {
            return Map.copyOf(planningCaptures);
        }

        @Override
        public void restorePlannedCaptures(Map<String, TypeVariableSymbol> captures) {
            restoreCaptureCheckpoint(captures);
        }

        @Override
        public void mergePlannedCaptures(Map<String, TypeVariableSymbol> captures) {
            planningCaptures.putAll(captures);
        }

        private InvocationPlanningResult<IrType> lexicalFieldType(FieldSymbol field,
                                                                   SourceSpan span) {
            if (!isAccessible(field.accessModifier(), field.ownerClass(), null, false)) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                        memberAccessMessage("field", field.declaration().name(),
                                field.accessModifier(), field.ownerClass()), span);
            }
            if (!field.isStatic() && (function.isStatic() || evaluatingConstructorArguments)) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "instance field '" + field.declaration().name()
                                + "' is not available in this context", span);
            }
            return InvocationPlanningResult.resolved(field.type());
        }

        @Override
        public InvocationPlanningResult<IrType> resolveType(TypeName typeName, TypeUse use) {
            if (use == TypeUse.DIAMOND_TARGET) {
                return planDiamondType(typeName);
            }
            InvocationPlanningResult<IrType> result = planType(typeName);
            if (!result.isResolved()) {
                return result;
            }
            IrType type = result.resolvedValue();
            if ((use == TypeUse.EXPLICIT_CLASS_ARGUMENT
                    || use == TypeUse.EXPLICIT_CALLABLE_ARGUMENT)
                    && (!(type.isReference() || type.isPrimitive()) || type.isWildcard())) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "explicit type argument '" + typeName.displayName()
                                + "' must be a proper reference or primitive type", typeName.span());
            }
            if (use == TypeUse.CONSTRUCTION_TARGET && type.isWildcard()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "cannot construct wildcard type '" + typeName.displayName() + "'",
                        typeName.span());
            }
            return result;
        }

        @Override
        public InvocationPlanningResult<IrType> resolveConstructionTarget(
                NewExpression expression, TypeUse use) {
            if (expression.enclosingInstance().isEmpty()) {
                return resolveType(expression.classType(), use);
            }
            InvocationPlanningResult<ExpressionTypePlan> enclosing = invocationPlanner.plan(
                    expression.enclosingInstance().orElseThrow());
            if (!enclosing.isResolved()) {
                return propagate(enclosing);
            }
            return resolveQualifiedConstructionTarget(expression,
                    enclosing.resolvedValue().type());
        }

        @Override
        public InvocationPlanningResult<ConstructionTargetPlan> planConstructionTarget(
                NewExpression expression, TypeUse use) {
            TypeSymbol anonymous = hierarchy.lexicalTypeFor(expression)
                    .filter(TypeSymbol::isAnonymousClass).orElse(null);
            if (anonymous != null && anonymous.anonymousParentBinding().isPresent()) {
                TypeSymbol.AnonymousParentBinding binding = anonymous
                        .anonymousParentBinding().orElseThrow();
                planningCaptures.putAll(binding.plannedCaptures());
                return InvocationPlanningResult.resolved(new ConstructionTargetPlan(
                        binding.parentTemplate(), binding.enclosingPlan(), true));
            }
            if (expression.enclosingInstance().isEmpty()) {
                InvocationPlanningResult<IrType> target = resolveType(expression.classType(), use);
                if (target.isResolved()) {
                    return InvocationPlanningResult.resolved(new ConstructionTargetPlan(
                            target.resolvedValue(), Optional.empty(), false));
                }
                return propagate(target);
            }
            InvocationPlanningResult<ExpressionTypePlan> enclosing = invocationPlanner.plan(
                    expression.enclosingInstance().orElseThrow());
            if (!enclosing.isResolved()) {
                return propagate(enclosing);
            }
            InvocationPlanningResult<IrType> target = resolveQualifiedConstructionTarget(
                    expression, enclosing.resolvedValue().type());
            if (!target.isResolved()) {
                return propagate(target);
            }
            return InvocationPlanningResult.resolved(new ConstructionTargetPlan(
                    target.resolvedValue(), Optional.of(enclosing.resolvedValue()), false));
        }

        private InvocationPlanningResult<IrType> resolveQualifiedConstructionTarget(
                NewExpression expression, IrType enclosingType) {
            if (!enclosingType.isNominalReference()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "qualified object creation requires a class enclosing instance",
                        expression.enclosingInstance().orElseThrow().span());
            }
            TypeSymbol enclosingSymbol = hierarchy.type(enclosingType.referenceName()).orElse(null);
            TypeSymbol target = enclosingSymbol == null ? null
                    : lookupMemberType(enclosingSymbol, expression.className());
            if (target == null) {
                return InvocationPlanningResult.notFound();
            }
            if (!target.isInnerClass()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "static member class '" + target.sourceName()
                                + "' cannot be created with an enclosing instance",
                        expression.classNameSpan());
            }
            Optional<IrType> ownerView = hierarchy.exactClassSupertype(enclosingType,
                    target.enclosingType().orElseThrow());
            if (ownerView.isEmpty()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "qualified object creation requires an enclosing instance of '"
                                + target.enclosingType().orElseThrow().sourceName() + "'",
                        expression.enclosingInstance().orElseThrow().span());
            }
            List<IrType> arguments = new ArrayList<>(ownerView.orElseThrow().typeArguments());
            if (expression.diamond()) {
                if (target.declaredTypeParameters().isEmpty()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            "diamond construction requires a generic class",
                            expression.classNameSpan());
                }
                target.declaredTypeParameters().stream().map(TypeVariableSymbol::irType)
                        .forEach(arguments::add);
            } else {
                if (expression.classType().typeArguments().size()
                        != target.declaredTypeParameters().size()) {
                    return rejected(InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                            "generic member class '" + target.sourceName() + "' expects "
                                    + target.declaredTypeParameters().size()
                                    + " member type argument(s) but received "
                                    + expression.classType().typeArguments().size(),
                            expression.classNameSpan());
                }
                for (TypeName argument : expression.classType().typeArguments()) {
                    InvocationPlanningResult<IrType> resolved = planType(argument);
                    if (!resolved.isResolved()) {
                        return propagate(resolved);
                    }
                    arguments.add(resolved.resolvedValue());
                }
            }
            return InvocationPlanningResult.resolved(IrType.reference(target.name(), arguments));
        }

        private InvocationPlanningResult<IrType> planDiamondType(TypeName type) {
            if (type.kind() != TypeName.Kind.REFERENCE) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "diamond construction requires a class type", type.span());
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(
                    type.referenceName(), currentClass, type.span());
            if (resolution.ambiguous()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                        "ambiguous type '" + type.referenceName() + "'", type.span());
            }
            TypeSymbol resolved = resolution.type().orElse(null);
            if (resolved == null) {
                return InvocationPlanningResult.notFound();
            }
            if (resolution.inaccessible()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                        "type '" + resolved.sourceName() + "' is not accessible", type.span());
            }
            if (type.lastSegmentTypeArgumentCount() != 0) {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "diamond cannot be combined with explicit class type arguments",
                        type.span());
            }
            if (resolved.declaredTypeParameters().isEmpty()) {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "diamond construction requires a generic class", type.span());
            }
            if (resolved.enclosingType().isPresent() && type.hasParameterizedQualifier()) {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "static member type '" + resolved.sourceName()
                                + "' cannot use a parameterized qualifier", type.span());
            }
            if (resolved.enclosingType().isEmpty() && type.hasParameterizedQualifier()) {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "top-level type '" + resolved.sourceName()
                                + "' cannot use a parameterized qualifier", type.span());
            }
            List<IrType> placeholders = resolved.declaredTypeParameters().stream()
                    .map(TypeVariableSymbol::irType).toList();
            return InvocationPlanningResult.resolved(
                    IrType.reference(resolved.name(), placeholders));
        }

        private InvocationPlanningResult<IrType> planType(TypeName type) {
            if (type.kind() == TypeName.Kind.ARRAY) {
                InvocationPlanningResult<IrType> element = planType(type.elementType());
                return element.isResolved()
                        ? InvocationPlanningResult.resolved(IrType.array(element.resolvedValue()))
                        : propagate(element);
            }
            if (type.kind() == TypeName.Kind.WILDCARD) {
                if (type.wildcardKind() == TypeName.WildcardKind.UNBOUNDED) {
                    return InvocationPlanningResult.resolved(IrType.wildcard());
                }
                InvocationPlanningResult<IrType> bound = planType(type.wildcardBound());
                if (!bound.isResolved()) {
                    return propagate(bound);
                }
                return InvocationPlanningResult.resolved(
                        type.wildcardKind() == TypeName.WildcardKind.EXTENDS
                                ? IrType.wildcardExtends(bound.resolvedValue())
                                : IrType.wildcardSuper(bound.resolvedValue()));
            }
            if (type.kind() != TypeName.Kind.REFERENCE) {
                return InvocationPlanningResult.resolved(SemanticAnalyzer.irType(type));
            }
            Optional<IrType> callableVariable = function.typeVariable(type.referenceName())
                    .map(TypeVariableSymbol::irType);
            if (callableVariable.isPresent()) {
                return type.typeArguments().isEmpty()
                        ? InvocationPlanningResult.resolved(callableVariable.orElseThrow())
                        : rejected(InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "type parameter '" + type.referenceName()
                                + "' cannot have type arguments", type.span());
            }
            Optional<IrType> classVariable = currentClass.typeParameter(type.referenceName());
            if (classVariable.isPresent()) {
                if (!type.typeArguments().isEmpty()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            "type parameter '" + type.referenceName()
                                    + "' cannot have type arguments", type.span());
                }
                if (function.isStatic()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                            "class type parameter '" + type.referenceName()
                                    + "' is unavailable in a static context", type.span());
                }
                return InvocationPlanningResult.resolved(classVariable.orElseThrow());
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(type.referenceName(), currentClass,
                    type.span());
            if (resolution.ambiguous()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                        "ambiguous type '" + type.referenceName() + "'", type.span());
            }
            TypeSymbol resolved = resolution.type().orElse(null);
            if (resolved == null) {
                return InvocationPlanningResult.notFound();
            }
            if (resolution.inaccessible()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                        "type '" + resolved.sourceName() + "' is not accessible", type.span());
            }
            int segmentArity = type.lastSegmentTypeArgumentCount();
            int declaredArity = resolved.declaredTypeParameters().size();
            if (segmentArity != declaredArity) {
                String message = declaredArity > 0 && segmentArity == 0
                        ? "raw generic type '" + resolved.sourceName() + "' is not supported"
                        : "generic type '" + resolved.sourceName() + "' expects "
                        + declaredArity + " type argument(s) but received " + segmentArity;
                return rejected(InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                        message, type.span());
            }
            List<TypeName> finalArguments = type.typeArguments().subList(
                    type.typeArguments().size() - segmentArity, type.typeArguments().size());
            List<IrType> explicitArguments = new ArrayList<>();
            for (TypeName argument : finalArguments) {
                InvocationPlanningResult<IrType> planned = planType(argument);
                if (!planned.isResolved()) {
                    return propagate(planned);
                }
                if (!planned.resolvedValue().isReference()
                        && !planned.resolvedValue().isPrimitive()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            "generic type argument '" + argument.displayName()
                                    + "' must be a proper reference or primitive type", argument.span());
                }
                explicitArguments.add(planned.resolvedValue());
            }
            List<IrType> arguments = List.copyOf(explicitArguments);
            if (resolved.enclosingType().isPresent()) {
                if (resolved.isInnerClass()) {
                    Optional<IrType> enclosingView;
                    if (type.hasExplicitMemberQualifier(resolved.simpleName())) {
                        InvocationPlanningResult<IrType> qualifier = planType(
                                type.qualifierReference().orElseThrow());
                        if (!qualifier.isResolved()) {
                            return propagate(qualifier);
                        }
                        enclosingView = hierarchy.exactClassSupertype(
                                qualifier.resolvedValue(), resolved.enclosingType().orElseThrow());
                    } else {
                        enclosingView = hierarchy.enclosingTypeView(currentClass, resolved);
                    }
                    if (enclosingView.isEmpty()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                                "member type '" + resolved.sourceName()
                                        + "' has no enclosing type view", type.span());
                    }
                    List<IrType> combined = new ArrayList<>(
                            enclosingView.orElseThrow().typeArguments());
                    combined.addAll(explicitArguments);
                    arguments = List.copyOf(combined);
                } else if (type.hasParameterizedQualifier()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            "static member type '" + resolved.sourceName()
                                    + "' cannot use a parameterized qualifier", type.span());
                }
            } else if (type.hasParameterizedQualifier()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "top-level type '" + resolved.sourceName()
                                + "' cannot use a parameterized qualifier", type.span());
            }
            if (arguments.size() != resolved.typeParameters().size()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                        "generic type '" + resolved.sourceName() + "' expects "
                                + resolved.typeParameters().size()
                                + " complete type argument(s) but received " + arguments.size(),
                        type.span());
            }
            Map<String, IrType> substitutions = new LinkedHashMap<>();
            for (int index = 0; index < arguments.size(); index++) {
                substitutions.put(resolved.typeParameters().get(index).id(), arguments.get(index));
            }
            for (int index = 0; index < resolved.typeParameters().size(); index++) {
                TypeVariableSymbol variable = resolved.typeParameters().get(index);
                IrType argument = arguments.get(index);
                if (argument.isPrimitive()) {
                    if (!variable.permitsPrimitive()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                "primitive argument " + argument.displayName()
                                        + " requires unbounded type parameter "
                                        + variable.displayName(), type.span());
                    }
                    continue;
                }
                for (IrType bound : variable.upperBounds()) {
                    IrType instantiatedBound = bound.substitute(substitutions);
                    if (!planningIsSubtype(argument, instantiatedBound)) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                argument.displayName() + " does not satisfy bound "
                                        + instantiatedBound.displayName(), type.span());
                    }
                }
            }
            return InvocationPlanningResult.resolved(IrType.reference(resolved.name(), arguments));
        }

        private InvocationPlanningResult<IrType> planVariableType(
                LocalClassSemantics.VariableIdentity variable) {
            InvocationPlanningResult<IrType> first = planType(variable.types().getFirst());
            if (!first.isResolved()) {
                return first;
            }
            IrType result = first.resolvedValue();
            for (int index = 1; index < variable.types().size(); index++) {
                InvocationPlanningResult<IrType> next = planType(variable.types().get(index));
                if (!next.isResolved()) {
                    return next;
                }
                result = leastUpperBound(result, next.resolvedValue())
                        .orElse(IrType.reference("ironwood.lang.Throwable"));
            }
            return InvocationPlanningResult.resolved(result);
        }

        @Override
        public InvocationPlanningResult<IrType> resolveTypeQualifier(Expression expression) {
            String qualified = qualifiedName(expression);
            if (qualified == null) {
                return InvocationPlanningResult.notFound();
            }
            String root = rootName(expression);
            if (root != null && (resolve(root) != null
                    || hierarchy.resolveField(currentClass.selfType(), root).selected().isPresent()
                    || hasLexicalField(root) || hasStaticImportedField(root))) {
                return InvocationPlanningResult.notFound();
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(qualified, currentClass,
                    expression.span());
            if (resolution.ambiguous()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                        "ambiguous type qualifier '" + qualified + "'", expression.span());
            }
            if (resolution.type().isEmpty()) {
                return InvocationPlanningResult.notFound();
            }
            if (resolution.inaccessible()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                        "type qualifier '" + qualified + "' is not accessible", expression.span());
            }
            return InvocationPlanningResult.resolved(resolution.type().orElseThrow().selfType());
        }

        @Override
        public InvocationPlanningResult<IrType> implicitReceiverType(SourceSpan span) {
            return InvocationPlanningResult.resolved(currentClass.selfType());
        }

        @Override
        public InvocationPlanningResult<IrType> implicitEnclosingInstanceType(IrType required,
                                                                               SourceSpan span) {
            if (function.isStatic() || evaluatingConstructorArguments
                    || !required.isNominalReference()) {
                return InvocationPlanningResult.notFound();
            }
            TypeSymbol requiredType = hierarchy.type(required.referenceName()).orElse(null);
            if (requiredType == null) {
                return InvocationPlanningResult.notFound();
            }
            for (TypeSymbol lexical = currentClass; lexical != null;
                 lexical = lexical.enclosingType().orElse(null)) {
                Optional<IrType> view = hierarchy.exactClassSupertype(lexical.selfType(),
                        requiredType);
                if (view.isPresent()) {
                    return InvocationPlanningResult.resolved(view.orElseThrow());
                }
                if (lexical.isStaticMember()) {
                    break;
                }
            }
            return InvocationPlanningResult.notFound();
        }

        @Override
        public InvocationPlanningResult<IrType> currentThisType(SourceSpan span) {
            if (function.isStatic() || evaluatingConstructorArguments) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "this is not available in this context", span);
            }
            return InvocationPlanningResult.resolved(currentClass.selfType());
        }

        @Override
        public InvocationPlanningResult<IrType> qualifiedThisType(
                QualifiedThisExpression expression) {
            if (function.isStatic()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "qualified this is not available in this context", expression.span());
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(
                    expression.typeName(), currentClass, expression.typeNameSpan());
            TypeSymbol target = resolution.type().orElse(null);
            if (target == null || !lexicallyEncloses(target)) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "'" + expression.typeName()
                                + ".this' does not name a lexically enclosing type", expression.span());
            }
            return InvocationPlanningResult.resolved(target.selfType());
        }

        @Override
        public InvocationPlanningResult<IrType> superType(SourceSpan span) {
            if (function.isStatic() || evaluatingConstructorArguments) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "super is not available in this context", span);
            }
            return hierarchy.superclassType(currentClass.selfType())
                    .map(InvocationPlanningResult::resolved)
                    .orElseGet(() -> rejected(
                            InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                            "class has no superclass", span));
        }

        @Override
        public InvocationPlanningResult<IrType> interfaceSuperType(
                InterfaceSuperExpression expression) {
            if (function.isStatic() || evaluatingConstructorArguments) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "interface super is not available in this context", expression.span());
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(
                    expression.interfaceName(), currentClass, expression.interfaceNameSpan());
            TypeSymbol target = resolution.type().orElse(null);
            if (target == null || !target.isInterface()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                        "'" + expression.interfaceName() + "' is not an interface",
                        expression.span());
            }
            Optional<IrType> direct = hierarchy.directInterfaceTypes(currentClass.selfType()).stream()
                    .filter(type -> type.referenceName().equals(target.name())).findFirst();
            return direct.map(InvocationPlanningResult::resolved)
                    .orElseGet(() -> rejected(
                            InvocationPlanningResult.PlanningRejection.Code.INVALID_RECEIVER,
                            "interface is not a direct superinterface", expression.span()));
        }

        private boolean lexicallyEncloses(TypeSymbol target) {
            for (TypeSymbol lexical = currentClass; lexical != null;
                 lexical = lexical.enclosingType().orElse(null)) {
                if (lexical.name().equals(target.name())) {
                    return true;
                }
                if (lexical.isStaticMember()) {
                    break;
                }
            }
            return false;
        }

        @Override
        public InvocationPlanningResult<ResolvedField> resolveField(FieldLookup lookup) {
            ClassHierarchy.FieldResolution resolution = hierarchy.resolveField(
                    dispatchReceiverType(lookup.receiver().lookupType()),
                    lookup.expression().fieldName());
            if (resolution.ambiguous()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_FIELD,
                        "ambiguous inherited field '" + lookup.expression().fieldName() + "'",
                        lookup.expression().fieldNameSpan());
            }
            FieldSymbol field = resolution.selected().orElse(null);
            if (field == null) {
                return InvocationPlanningResult.notFound();
            }
            String receiverType = nominalName(lookup.receiver().lookupType());
            if (!isAccessible(field.accessModifier(), field.ownerClass(), receiverType, false)) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                        memberAccessMessage("field", lookup.expression().fieldName(),
                                field.accessModifier(), field.ownerClass()),
                        lookup.expression().fieldNameSpan());
            }
            if (lookup.receiver().kind() == InvocationPlan.ReceiverKind.INSTANCE
                    && field.isStatic()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "static field '" + lookup.expression().fieldName()
                                + "' must be accessed through a type",
                        lookup.expression().fieldNameSpan());
            }
            return InvocationPlanningResult.resolved(new ResolvedField(field.type(), field.isStatic(),
                    !field.isFinal(), field.ownerClass()));
        }

        @Override
        public InvocationPlanningResult<List<InvocationCandidate>> methodCandidates(
                MethodLookup lookup) {
            IrType receiver = dispatchReceiverType(lookup.receiver().lookupType());
            GenericTypeSystem.CaptureConversion capture = hierarchy.planCaptureReceiver(receiver,
                    function.linkageName() + ":call@"
                            + lookup.expression().span().start().offset());
            planningCaptures.putAll(capture.variables());
            receiver = capture.type();
            List<CallableSymbol> methods;
            switch (lookup.receiver().kind()) {
                case TYPE -> {
                    TypeSymbol type = hierarchy.type(receiver.referenceName()).orElse(null);
                    methods = type != null && type.isInterface()
                            ? hierarchy.lookupDeclaredMethods(receiver, lookup.expression().methodName())
                            : hierarchy.lookupMethods(receiver, lookup.expression().methodName());
                }
                case INTERFACE_SUPER -> methods = hierarchy.maximallySpecificInterfaceMethods(
                        receiver, lookup.expression().methodName()).stream()
                        .filter(method -> !method.isStatic() && !method.isAbstract()).toList();
                case IMPLICIT -> methods = lexicalMethods(lookup.expression().methodName());
                default -> methods = hierarchy.lookupMethods(receiver,
                        lookup.expression().methodName(), planningCaptures);
            }
            if (methods.isEmpty()) {
                return InvocationPlanningResult.notFound();
            }
            return InvocationPlanningResult.resolved(methods.stream()
                    .map(InvocationCandidate::method).toList());
        }

        private List<CallableSymbol> lexicalMethods(String name) {
            List<CallableSymbol> methods = hierarchy.lookupMethods(currentClass.selfType(), name);
            if (!methods.isEmpty()) {
                return methods;
            }
            for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
                 lexical != null; lexical = lexical.enclosingType().orElse(null)) {
                methods = hierarchy.lookupMethods(lexical.selfType(), name);
                if (!methods.isEmpty()) {
                    return methods;
                }
                if (lexical.isStaticMember()) {
                    break;
                }
            }
            return staticImports.methods(currentClass.unit(), name);
        }

        @Override
        public InvocationPlanningResult<List<InvocationCandidate>> constructorCandidates(
                ConstructorLookup lookup) {
            if (!lookup.targetType().isNominalReference()) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "construction target must be a class type", lookup.expression().classNameSpan());
            }
            TypeSymbol target = hierarchy.type(lookup.targetType().referenceName()).orElse(null);
            if (target == null) {
                return InvocationPlanningResult.notFound();
            }
            TypeSymbol anonymous = hierarchy.lexicalTypeFor(lookup.expression())
                    .filter(TypeSymbol::isAnonymousClass).orElse(null);
            if (anonymous == null && (target.isInterface() || target.isAbstract())) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INVALID_CONSTRUCTION,
                        "cannot instantiate " + (target.isInterface() ? "interface" : "abstract class")
                                + " '" + target.sourceName() + "'",
                        lookup.expression().classNameSpan());
            }
            List<TypeVariableSymbol> constructionVariables;
            if (anonymous != null) {
                constructionVariables = lookup.expression().diamond()
                        ? anonymous.anonymousParentBinding()
                        .map(TypeSymbol.AnonymousParentBinding::diamondVariables)
                        .orElseGet(() -> anonymous.typeParameters().stream()
                                .filter(variable -> variable.kind()
                                        == TypeVariableSymbol.Kind.SYNTHETIC).toList())
                        : List.of();
            } else {
                constructionVariables = lookup.expression().diamond()
                        ? target.declaredTypeParameters() : target.typeParameters();
            }
            List<InvocationCandidate.CallableTypeParameter> classParameters =
                    constructionVariables.stream().map(variable ->
                            new InvocationCandidate.CallableTypeParameter(variable.id(),
                                    variable.displayName(), variable.upperBounds(),
                                    variable.permitsPrimitive())).toList();
            List<CallableSymbol> constructors = anonymous == null
                    ? hierarchy.constructors(lookup.targetType()) : anonymous.constructors();
            if (constructors.isEmpty()) {
                return InvocationPlanningResult.notFound();
            }
            return InvocationPlanningResult.resolved(constructors.stream()
                    .map(constructor -> InvocationCandidate.constructor(constructor,
                            classParameters, anonymous == null
                                    ? lookup.targetType() : anonymous.constructionType())).toList());
        }

        @Override
        public AccessDecision checkAccess(AccessRequest request) {
            CallableSymbol candidate = request.candidate().callable();
            TypeSymbol candidateOwner = hierarchy.type(candidate.ownerType()).orElse(null);
            if (request.kind() == InvocationPlan.InvocationKind.CONSTRUCTOR
                    && candidateOwner != null && candidateOwner.isAnonymousClass()) {
                candidate = candidateOwner.anonymousConstructorForwarding(candidate)
                        .map(TypeSymbol.AnonymousConstructorForwarding::superConstructor)
                        .orElse(candidate);
            }
            InvocationPlan.ReceiverKind form = request.receiver().kind();
            if (request.kind() == InvocationPlan.InvocationKind.CONSTRUCTOR) {
                if (!candidate.isConstructor()) {
                    return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                            "candidate is not a constructor", request.span());
                }
            } else if (form == InvocationPlan.ReceiverKind.TYPE && !candidate.isStatic()) {
                return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "instance method '" + candidate.sourceName()
                                + "' cannot be called through a type", request.span());
            } else if (form == InvocationPlan.ReceiverKind.INSTANCE && candidate.isStatic()
                    && hierarchy.type(candidate.ownerType()).map(TypeSymbol::isInterface)
                    .orElse(false)) {
                return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "static interface method '" + candidate.sourceName()
                                + "' must be called through its interface name", request.span());
            } else if ((form == InvocationPlan.ReceiverKind.SUPER
                    || form == InvocationPlan.ReceiverKind.INTERFACE_SUPER)
                    && candidate.isStatic()) {
                return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "static method cannot be selected through super", request.span());
            } else if (form == InvocationPlan.ReceiverKind.IMPLICIT && !candidate.isStatic()
                    && !canSupplyImplicitReceiver(candidate.ownerType())) {
                return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "instance method '" + candidate.sourceName()
                                + "' is unavailable in this context", request.span());
            }
            if (form == InvocationPlan.ReceiverKind.SUPER && candidate.isAbstract()) {
                return denied(InvocationPlanningResult.PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "abstract method cannot be invoked through super", request.span());
            }
            String receiverType = nominalName(request.receiver().lookupType());
            boolean accessible = isAccessible(candidate.accessModifier(), candidate.ownerType(),
                    receiverType, request.kind() == InvocationPlan.InvocationKind.CONSTRUCTOR);
            return accessible ? AccessDecision.allowed()
                    : denied(InvocationPlanningResult.PlanningRejection.Code.INACCESSIBLE_MEMBER,
                    memberAccessMessage(request.kind() == InvocationPlan.InvocationKind.CONSTRUCTOR
                                    ? "constructor" : "method", candidate.sourceName(),
                            candidate.accessModifier(), candidate.ownerType()), request.span());
        }

        private boolean canSupplyImplicitReceiver(String ownerType) {
            if (function.isStatic() || evaluatingConstructorArguments) {
                return false;
            }
            if (hierarchy.isSubtype(currentClass.name(), ownerType)) {
                return true;
            }
            for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
                 lexical != null; lexical = lexical.enclosingType().orElse(null)) {
                if (hierarchy.isSubtype(lexical.name(), ownerType)) {
                    return true;
                }
                if (lexical.isStaticMember()) {
                    break;
                }
            }
            return false;
        }

        @Override
        public InvocationPlanningResult<InvocationPlan.InferenceSolution> infer(
                InferenceRequest request) {
            if (request.diamond()) {
                return inferDiamond(request);
            }
            Map<String, IrType> classArguments = new LinkedHashMap<>();
            if (request.explicitClassTypeArguments().size()
                    != request.candidate().classTypeParameters().size()) {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                        "class type argument count does not match the declaration", request.span());
            }
            for (int index = 0; index < request.candidate().classTypeParameters().size(); index++) {
                InvocationCandidate.CallableTypeParameter variable =
                        request.candidate().classTypeParameters().get(index);
                classArguments.put(variable.id(), request.explicitClassTypeArguments().get(index));
            }
            for (InvocationCandidate.CallableTypeParameter variable
                    : request.candidate().classTypeParameters()) {
                IrType actual = classArguments.get(variable.id());
                if (actual.isPrimitive()) {
                    if (!variable.permitsPrimitive()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                "primitive argument " + actual.displayName()
                                        + " requires unbounded type parameter "
                                        + variable.displayName(), request.span());
                    }
                    continue;
                }
                for (IrType bound : variable.upperBounds()) {
                    IrType instantiated = bound.substitute(classArguments);
                    if (!planningIsSubtype(actual, instantiated)) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                actual.displayName() + " does not satisfy bound "
                                        + instantiated.displayName(), request.span());
                    }
                }
            }

            Map<String, IrType> explicitCallable = new LinkedHashMap<>();
            if (!request.explicitCallableTypeArguments().isEmpty()) {
                if (request.explicitCallableTypeArguments().size()
                        != request.candidate().callableTypeParameters().size()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                            "callable type argument count does not match the declaration",
                            request.span());
                }
                for (int index = 0;
                     index < request.candidate().callableTypeParameters().size(); index++) {
                    explicitCallable.put(
                            request.candidate().callableTypeParameters().get(index).id(),
                            request.explicitCallableTypeArguments().get(index));
                }
            }
            Set<String> callableIds = request.candidate().callableTypeParameters().stream()
                    .map(InvocationCandidate.CallableTypeParameter::id)
                    .collect(java.util.stream.Collectors.toSet());
            CallableSymbol callable = request.candidate().callable().substitute(classArguments);
            Optional<IrType> expected = callable.isConstructor()
                    ? Optional.empty() : request.expectedResultType();
            if (request.explicitCallableTypeArguments().isEmpty() && expected.isPresent()
                    && request.arguments().isEmpty()) {
                collectExpectedBindings(callable.returnType(), expected.orElseThrow(),
                        callableIds, explicitCallable);
            }
            List<IrType> actualTypes = new ArrayList<>();
            Map<String, TypeVariableSymbol> argumentCaptures = new LinkedHashMap<>();
            for (int index = 0; index < request.arguments().size(); index++) {
                IrType template = request.unresolvedEffectiveParameterTypes().get(index)
                        .substitute(classArguments).substitute(explicitCallable);
                Set<String> unresolvedIds = new java.util.LinkedHashSet<>(callableIds);
                unresolvedIds.removeAll(explicitCallable.keySet());
                if (request.arguments().get(index) instanceof CallExpression
                        && expected.isPresent()
                        && containsAnyTypeVariable(template, unresolvedIds)) {
                    collectExpectedBindings(callable.returnType(), expected.orElseThrow(),
                            callableIds, explicitCallable);
                    template = request.unresolvedEffectiveParameterTypes().get(index)
                            .substitute(classArguments).substitute(explicitCallable);
                    unresolvedIds = new java.util.LinkedHashSet<>(callableIds);
                    unresolvedIds.removeAll(explicitCallable.keySet());
                }
                Optional<IrType> argumentExpected = containsAnyTypeVariable(template, unresolvedIds)
                        ? Optional.empty() : Optional.of(template);
                InvocationPlanningResult<ExpressionTypePlan> argument = request.expressionProbe()
                        .plan(request.arguments().get(index), argumentExpected);
                if (!argument.isResolved()
                        && request.explicitCallableTypeArguments().isEmpty()
                        && expected.isPresent()) {
                    collectExpectedBindings(callable.returnType(), expected.orElseThrow(),
                            callableIds, explicitCallable);
                    template = request.unresolvedEffectiveParameterTypes().get(index)
                            .substitute(classArguments).substitute(explicitCallable);
                    unresolvedIds = new java.util.LinkedHashSet<>(callableIds);
                    unresolvedIds.removeAll(explicitCallable.keySet());
                    argumentExpected = containsAnyTypeVariable(template, unresolvedIds)
                            ? Optional.empty() : Optional.of(template);
                    argument = request.expressionProbe().plan(
                            request.arguments().get(index), argumentExpected);
                }
                if (!argument.isResolved()) {
                    List<InvocationPlanningResult.PlanningRejection> causes = argument.isRejected()
                            ? argument.rejections() : List.of();
                    return InvocationPlanningResult.rejected(
                            InvocationPlanningResult.PlanningRejection.causedBy(
                                    InvocationPlanningResult.PlanningRejection.Code.ARGUMENT_REJECTED,
                                    "cannot type argument " + (index + 1),
                                    request.arguments().get(index).span(), causes));
                }
                actualTypes.add(captureInferenceArgument(request, index,
                        argument.resolvedValue().type(), argumentCaptures));
            }
            if (request.candidate().callableTypeParameters().isEmpty()) {
                return InvocationPlanningResult.resolved(
                        new InvocationPlan.InferenceSolution(classArguments, Map.of(),
                                actualTypes, argumentCaptures));
            }
            List<IrType> invocationParameterTypes =
                    request.unresolvedEffectiveParameterTypes().stream()
                            .map(type -> type.substitute(classArguments)).toList();
            CallableSymbol invocationCallable = callable.withInvocationParameterTypes(
                    invocationParameterTypes);
            GenericInferenceSolver inference = new GenericInferenceSolver(hierarchy,
                    planningCaptures);
            if (!request.explicitCallableTypeArguments().isEmpty()) {
                GenericInferenceSolver.Result inferred = inference.instantiate(invocationCallable,
                        request.explicitCallableTypeArguments(), actualTypes, expected);
                if (!inferred.successful()) {
                    GenericInferenceSolver.Failure failure = inferred.failure().orElseThrow();
                    return rejected(InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                            failure.message(), request.span());
                }
                return InvocationPlanningResult.resolved(new InvocationPlan.InferenceSolution(
                        classArguments,
                        inferred.instantiation().orElseThrow().substitutions(),
                        actualTypes, argumentCaptures));
            }

            Set<String> evidenced = new java.util.LinkedHashSet<>();
            for (int index = 0; index < actualTypes.size(); index++) {
                if (!actualTypes.get(index).equals(IrType.NULL)) {
                    collectTypeVariableIds(invocationCallable.parameterTypes().get(index), callableIds,
                            evidenced);
                }
            }
            if (expected.isPresent()) {
                collectTypeVariableIds(callable.returnType(), callableIds, evidenced);
            }
            evidenced.removeAll(explicitCallable.keySet());
            for (TypeVariableSymbol variable : callable.typeVariables()) {
                if (!explicitCallable.containsKey(variable.id())
                        && !evidenced.contains(variable.id())) {
                    explicitCallable.put(variable.id(), defaultCallableType(variable,
                            explicitCallable, callableIds));
                }
            }

            List<TypeVariableSymbol> remaining = callable.typeVariables().stream()
                    .filter(variable -> !explicitCallable.containsKey(variable.id())).toList();
            if (!remaining.isEmpty()) {
                CallableSymbol partiallyInstantiated =
                        invocationCallable.substitute(explicitCallable);
                List<TypeVariableSymbol> substitutedRemaining = partiallyInstantiated.typeVariables()
                        .stream().filter(variable -> remaining.stream()
                                .anyMatch(original -> original.id().equals(variable.id()))).toList();
                GenericInferenceSolver.Result inferred = inference.infer(partiallyInstantiated,
                        substitutedRemaining, actualTypes, expected);
                if (!inferred.successful()) {
                    GenericInferenceSolver.Failure failure = inferred.failure().orElseThrow();
                    return rejected(InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                            failure.message(), request.span());
                }
                explicitCallable.putAll(
                        inferred.instantiation().orElseThrow().substitutions());
            }
            for (TypeVariableSymbol variable : callable.typeVariables()) {
                IrType actual = explicitCallable.get(variable.id());
                if (actual != null && actual.isPrimitive()) {
                    if (!variable.permitsPrimitive()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                                "primitive argument " + actual.displayName()
                                        + " requires unbounded type parameter "
                                        + variable.displayName(), request.span());
                    }
                    continue;
                }
                for (IrType bound : variable.upperBounds()) {
                    IrType instantiatedBound = bound.substitute(explicitCallable);
                    if (!planningIsSubtype(actual, instantiatedBound)) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                                actual.displayName() + " does not satisfy bound "
                                        + instantiatedBound.displayName(), request.span());
                    }
                }
            }
            IrType instantiatedResult = callable.returnType().substitute(explicitCallable);
            if (expected.isPresent()
                    && !planningIsAssignable(expected.orElseThrow(), instantiatedResult)) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                        instantiatedResult.displayName() + " is not assignable to expected type "
                                + expected.orElseThrow().displayName(), request.span());
            }
            return InvocationPlanningResult.resolved(new InvocationPlan.InferenceSolution(
                    classArguments, explicitCallable, actualTypes, argumentCaptures));
        }

        private IrType captureInferenceArgument(InferenceRequest request, int argumentIndex,
                                                IrType actual,
                                                Map<String, TypeVariableSymbol> argumentCaptures) {
            GenericTypeSystem.CaptureConversion capture = hierarchy.planCaptureReceiver(actual,
                    function.linkageName() + ":inference@" + request.span().start().offset()
                            + ":" + request.candidate().identity()
                            + ":argument-" + argumentIndex);
            argumentCaptures.putAll(capture.variables());
            planningCaptures.putAll(capture.variables());
            return capture.type();
        }

        private InvocationPlanningResult<InvocationPlan.InferenceSolution> inferDiamond(
                InferenceRequest request) {
            InvocationCandidate candidate = request.candidate();
            TypeSymbol target = hierarchy.type(candidate.callable().ownerType()).orElse(null);
            if (target == null) {
                return rejected(InvocationPlanningResult.PlanningRejection.Code.UNRESOLVED_TYPE,
                        "cannot resolve diamond construction target", request.span());
            }

            Map<String, TypeVariableSymbol> targetVariables = target.typeParameters().stream()
                    .collect(java.util.stream.Collectors.toMap(TypeVariableSymbol::id,
                            variable -> variable, (left, right) -> left, LinkedHashMap::new));
            List<TypeVariableSymbol> classVariables = new ArrayList<>();
            for (InvocationCandidate.CallableTypeParameter parameter
                    : candidate.classTypeParameters()) {
                TypeVariableSymbol variable = targetVariables.get(parameter.id());
                if (variable == null) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                            "diamond class variable '" + parameter.displayName()
                                    + "' is not declared by the construction target",
                            request.span());
                }
                classVariables.add(variable);
            }

            Map<String, IrType> explicitCallable = new LinkedHashMap<>();
            if (!request.explicitCallableTypeArguments().isEmpty()) {
                if (request.explicitCallableTypeArguments().size()
                        != candidate.callableTypeParameters().size()) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                            "constructor type argument count does not match the declaration",
                            request.span());
                }
                for (int index = 0; index < candidate.callableTypeParameters().size(); index++) {
                    IrType argument = request.explicitCallableTypeArguments().get(index);
                    InvocationCandidate.CallableTypeParameter parameter =
                            candidate.callableTypeParameters().get(index);
                    if (!(argument.isReference() || argument.isPrimitive())
                            || argument.equals(IrType.NULL)
                            || argument.isWildcard()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                "explicit constructor type argument must be a proper reference or primitive type",
                                request.span());
                    }
                    if (argument.isPrimitive() && !parameter.permitsPrimitive()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                                "primitive constructor argument " + argument.displayName()
                                        + " requires unbounded type parameter "
                                        + parameter.displayName(), request.span());
                    }
                    explicitCallable.put(parameter.id(), argument);
                }
            }

            Set<String> classIds = candidate.classTypeParameters().stream()
                    .map(InvocationCandidate.CallableTypeParameter::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> callableIds = candidate.callableTypeParameters().stream()
                    .map(InvocationCandidate.CallableTypeParameter::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> inferenceIds = new LinkedHashSet<>(classIds);
            if (explicitCallable.isEmpty()) {
                inferenceIds.addAll(callableIds);
            }

            Map<String, IrType> probeBindings = new LinkedHashMap<>(explicitCallable);
            request.expectedResultType().ifPresent(expected -> seedExpectedBindings(
                    candidate.resultTypeTemplate().substitute(explicitCallable), expected,
                    classIds, probeBindings));

            List<IrType> actualTypes = new ArrayList<>();
            Map<String, TypeVariableSymbol> argumentCaptures = new LinkedHashMap<>();
            for (int index = 0; index < request.arguments().size(); index++) {
                IrType template = request.unresolvedEffectiveParameterTypes().get(index)
                        .substitute(probeBindings);
                Set<String> unresolved = new LinkedHashSet<>(inferenceIds);
                unresolved.removeAll(probeBindings.keySet());
                Optional<IrType> argumentExpected = containsAnyTypeVariable(template, unresolved)
                        ? Optional.empty() : Optional.of(template);
                InvocationPlanningResult<ExpressionTypePlan> argument = request.expressionProbe()
                        .plan(request.arguments().get(index), argumentExpected);
                if (!argument.isResolved()) {
                    List<InvocationPlanningResult.PlanningRejection> causes = argument.isRejected()
                            ? argument.rejections() : List.of();
                    return InvocationPlanningResult.rejected(
                            InvocationPlanningResult.PlanningRejection.causedBy(
                                    InvocationPlanningResult.PlanningRejection.Code.ARGUMENT_REJECTED,
                                    "cannot type constructor argument " + (index + 1),
                                    request.arguments().get(index).span(), causes));
                }
                actualTypes.add(captureInferenceArgument(request, index,
                        argument.resolvedValue().type(), argumentCaptures));
            }
            List<IrType> capturedArgumentTypes = List.copyOf(actualTypes);

            CallableSymbol callable = candidate.callable().substitute(explicitCallable);
            List<TypeVariableSymbol> variables = new ArrayList<>();
            classVariables.stream().map(variable -> variable.substitute(explicitCallable))
                    .forEach(variables::add);
            if (explicitCallable.isEmpty()) {
                variables.addAll(callable.typeVariables());
            }
            List<IrType> formalTypes = new ArrayList<>(
                    request.unresolvedEffectiveParameterTypes().stream()
                            .map(type -> type.substitute(explicitCallable)).toList());
            if (!explicitCallable.isEmpty()) {
                for (InvocationCandidate.CallableTypeParameter variable
                        : candidate.callableTypeParameters()) {
                    IrType explicit = explicitCallable.get(variable.id());
                    if (explicit.isPrimitive() && variable.permitsPrimitive()) {
                        continue;
                    }
                    for (IrType bound : variable.upperBounds()) {
                        formalTypes.add(bound.substitute(explicitCallable));
                        actualTypes.add(explicit);
                    }
                }
            }
            IrType resultTemplate = candidate.resultTypeTemplate().substitute(explicitCallable);
            GenericInferenceSolver.Result inferred = new GenericInferenceSolver(hierarchy,
                    planningCaptures).infer(callable, variables, formalTypes, actualTypes,
                    resultTemplate, request.expectedResultType(), classIds);
            if (!inferred.successful()) {
                GenericInferenceSolver.Failure failure = inferred.failure().orElseThrow();
                return rejected(InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                        failure.message(), request.span());
            }

            Map<String, IrType> inferredArguments =
                    inferred.instantiation().orElseThrow().substitutions();
            Map<String, IrType> classArguments = new LinkedHashMap<>();
            for (InvocationCandidate.CallableTypeParameter parameter
                    : candidate.classTypeParameters()) {
                IrType argument = inferredArguments.get(parameter.id());
                if (argument == null) {
                    return rejected(
                            InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                            "inference did not instantiate diamond type argument '"
                                    + parameter.displayName() + "'", request.span());
                }
                classArguments.put(parameter.id(), argument);
            }
            Map<String, IrType> callableArguments = new LinkedHashMap<>(explicitCallable);
            if (explicitCallable.isEmpty()) {
                for (InvocationCandidate.CallableTypeParameter parameter
                        : candidate.callableTypeParameters()) {
                    IrType argument = inferredArguments.get(parameter.id());
                    if (argument == null) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                                "inference did not instantiate constructor type argument '"
                                        + parameter.displayName() + "'", request.span());
                    }
                    callableArguments.put(parameter.id(), argument);
                }
            }

            Map<String, IrType> substitutions = new LinkedHashMap<>(classArguments);
            substitutions.putAll(callableArguments);
            InvocationPlanningResult<InvocationPlan.InferenceSolution> classBounds =
                    validateDiamondBounds(candidate.classTypeParameters(), classArguments,
                            substitutions, "class", request.span());
            if (classBounds != null) {
                return classBounds;
            }
            InvocationPlanningResult<InvocationPlan.InferenceSolution> callableBounds =
                    validateDiamondBounds(candidate.callableTypeParameters(), callableArguments,
                            substitutions, "constructor", request.span());
            if (callableBounds != null) {
                return callableBounds;
            }
            return InvocationPlanningResult.resolved(
                    new InvocationPlan.InferenceSolution(classArguments, callableArguments,
                            capturedArgumentTypes, argumentCaptures));
        }

        private InvocationPlanningResult<InvocationPlan.InferenceSolution> validateDiamondBounds(
                List<InvocationCandidate.CallableTypeParameter> variables,
                Map<String, IrType> arguments, Map<String, IrType> substitutions,
                String kind, SourceSpan span) {
            for (InvocationCandidate.CallableTypeParameter variable : variables) {
                IrType actual = arguments.get(variable.id());
                if (actual == null) {
                    return rejected(InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                            "missing inferred " + kind + " type argument '"
                                    + variable.displayName() + "'", span);
                }
                if (actual.isPrimitive()) {
                    if (!variable.permitsPrimitive()) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                                "primitive argument " + actual.displayName()
                                        + " requires unbounded " + kind + " type parameter "
                                        + variable.displayName(), span);
                    }
                    continue;
                }
                for (IrType bound : variable.upperBounds()) {
                    IrType instantiated = bound.substitute(substitutions);
                    if (!planningIsSubtype(actual, instantiated)) {
                        return rejected(
                                InvocationPlanningResult.PlanningRejection.Code.INFERENCE_FAILED,
                                actual.displayName() + " does not satisfy " + kind + " bound "
                                        + instantiated.displayName(), span);
                    }
                }
            }
            return null;
        }

        private void seedExpectedBindings(IrType resultTemplate, IrType expected,
                                          Set<String> ids, Map<String, IrType> bindings) {
            if (!resultTemplate.isNominalReference() || !expected.isNominalReference()) {
                return;
            }
            List<IrType> views = new ArrayList<>();
            if (resultTemplate.referenceName().equals(expected.referenceName())) {
                views.add(resultTemplate);
            }
            hierarchy.exactSupertypes(resultTemplate, planningCaptures).stream()
                    .filter(type -> type.isNominalReference()
                            && type.referenceName().equals(expected.referenceName()))
                    .filter(type -> !views.contains(type)).forEach(views::add);
            if (views.size() == 1) {
                collectExpectedBindings(views.getFirst(), expected, ids, bindings);
            }
        }

        private void collectExpectedBindings(IrType template, IrType expected, Set<String> ids,
                                             Map<String, IrType> bindings) {
            if (template.isTypeParameter() && ids.contains(template.referenceName())
                    && expected.isReference() && !expected.equals(IrType.NULL)
                    && !expected.isWildcard()) {
                bindings.putIfAbsent(template.referenceName(), expected);
                return;
            }
            if (template.isArray() && expected.isArray()) {
                collectExpectedBindings(template.elementType(), expected.elementType(), ids, bindings);
                return;
            }
            if (template.isNominalReference() && expected.isNominalReference()
                    && template.referenceName().equals(expected.referenceName())
                    && template.typeArguments().size() == expected.typeArguments().size()) {
                for (int index = 0; index < template.typeArguments().size(); index++) {
                    collectExpectedBindings(template.typeArguments().get(index),
                            expected.typeArguments().get(index), ids, bindings);
                }
            }
        }

        private void collectTypeVariableIds(IrType type, Set<String> ids, Set<String> result) {
            if (type.isTypeParameter()) {
                if (ids.contains(type.referenceName())) {
                    result.add(type.referenceName());
                }
                return;
            }
            if (type.isArray()) {
                collectTypeVariableIds(type.elementType(), ids, result);
                return;
            }
            if (type.isWildcard() && type.wildcardBound() != null) {
                collectTypeVariableIds(type.wildcardBound(), ids, result);
                return;
            }
            type.typeArguments().forEach(argument -> collectTypeVariableIds(argument, ids, result));
        }

        private IrType defaultCallableType(TypeVariableSymbol variable,
                                           Map<String, IrType> substitutions,
                                           Set<String> callableIds) {
            IrType bound = variable.upperBounds().isEmpty()
                    ? IrType.reference("ironwood.lang.Object")
                    : variable.upperBounds().getFirst().substitute(substitutions);
            Set<String> unresolved = new java.util.LinkedHashSet<>(callableIds);
            unresolved.removeAll(substitutions.keySet());
            unresolved.remove(variable.id());
            return containsAnyTypeVariable(bound, unresolved)
                    ? variable.firstBoundErasure().substitute(substitutions) : bound;
        }

        private boolean containsAnyTypeVariable(IrType type, Set<String> ids) {
            if (type.isTypeParameter()) {
                return ids.contains(type.referenceName());
            }
            if (type.isArray()) {
                return containsAnyTypeVariable(type.elementType(), ids);
            }
            if (type.isWildcard() && type.wildcardBound() != null) {
                return containsAnyTypeVariable(type.wildcardBound(), ids);
            }
            return type.typeArguments().stream().anyMatch(argument ->
                    containsAnyTypeVariable(argument, ids));
        }

        @Override
        public InvocationPlanningResult<InvocationPlan.InvocationConversion> classifyConversion(
                ConversionRequest request) {
            IrType target = request.targetType();
            IrType actual = request.sourceType();
            InvocationPlan.ConversionKind kind;
            if (target.equals(actual)) {
                kind = InvocationPlan.ConversionKind.IDENTITY;
            } else if (target.isReference() && actual.equals(IrType.NULL)) {
                kind = InvocationPlan.ConversionKind.NULL_TO_REFERENCE;
            } else if (PrimitiveConversions.canWiden(target, actual)) {
                kind = InvocationPlan.ConversionKind.PRIMITIVE_WIDENING;
            } else if (target.isReference() && actual.isReference()
                    && planningIsAssignable(target, actual)) {
                kind = InvocationPlan.ConversionKind.REFERENCE_WIDENING;
            } else {
                return rejected(
                        InvocationPlanningResult.PlanningRejection.Code.CONVERSION_REJECTED,
                        actual.displayName() + " is not convertible to " + target.displayName(),
                        request.span());
            }
            return InvocationPlanningResult.resolved(
                    new InvocationPlan.InvocationConversion(kind, actual, target));
        }

        @Override
        public InvocationPlanningResult<InvocationPlan.CandidatePlan> selectMostSpecific(
                SelectionRequest request) {
            return new GenericMostSpecificSelector(hierarchy, planningCaptures).select(request);
        }

        @Override
        public Optional<IrType> leastUpperBound(IrType left, IrType right) {
            if (left.equals(right)) {
                return Optional.of(left);
            }
            if (left.equals(IrType.NULL) && right.isReference()) {
                return Optional.of(right);
            }
            if (right.equals(IrType.NULL) && left.isReference()) {
                return Optional.of(left);
            }
            if (!left.isReference() || !right.isReference()) {
                return Optional.empty();
            }
            if (planningIsAssignable(left, right)) {
                return Optional.of(left);
            }
            if (planningIsAssignable(right, left)) {
                return Optional.of(right);
            }
            List<IrType> leftSupers = referenceSupertypes(left);
            List<IrType> candidates = leftSupers.stream()
                    .filter(candidate -> planningIsSubtype(right, candidate)).distinct().toList();
            List<IrType> minimal = candidates.stream().filter(candidate -> candidates.stream()
                    .noneMatch(other -> !other.equals(candidate)
                            && planningIsSubtype(other, candidate)
                            && !planningIsSubtype(candidate, other))).toList();
            return minimal.size() == 1 ? Optional.of(minimal.getFirst()) : Optional.empty();
        }

        private List<IrType> referenceSupertypes(IrType type) {
            if (type.isArray() || type.isWildcard()) {
                return List.of(type, ROOT_OBJECT);
            }
            if (type.isTypeParameter()) {
                List<IrType> result = new ArrayList<>();
                result.add(type);
                TypeVariableSymbol capture = planningCaptures.get(type.referenceName());
                List<IrType> bounds = capture == null
                        ? hierarchy.upperBounds(type) : capture.upperBounds();
                for (IrType bound : bounds) {
                    result.addAll(referenceSupertypes(bound));
                }
                return result.stream().distinct().toList();
            }
            List<IrType> result = new ArrayList<>();
            result.add(type);
            result.addAll(hierarchy.exactSupertypes(type, planningCaptures));
            return result.stream().distinct().toList();
        }

        private boolean planningIsSubtype(IrType actual, IrType expected) {
            return hierarchy.isSubtype(actual, expected, planningCaptures);
        }

        private boolean planningIsAssignable(IrType expected, IrType actual) {
            return hierarchy.isAssignable(expected, actual, planningCaptures);
        }

        private String nominalName(IrType type) {
            IrType dispatched = dispatchReceiverType(type);
            return dispatched.isNominalReference() ? dispatched.referenceName() : null;
        }

        private AccessDecision denied(InvocationPlanningResult.PlanningRejection.Code code,
                                      String message, SourceSpan span) {
            return AccessDecision.denied(
                    InvocationPlanningResult.PlanningRejection.of(code, message, span));
        }

        private <T> InvocationPlanningResult<T> rejected(
                InvocationPlanningResult.PlanningRejection.Code code,
                String message, SourceSpan span) {
            return InvocationPlanningResult.rejected(
                    InvocationPlanningResult.PlanningRejection.of(code, message, span));
        }

        private <T> InvocationPlanningResult<T> propagate(InvocationPlanningResult<?> result) {
            if (result.isRejected()) {
                return InvocationPlanningResult.rejected(result.rejections());
            }
            return InvocationPlanningResult.notFound();
        }
    }

    private FieldSymbol resolveField(IrType receiverType, String fieldName, SourceSpan span) {
        ClassHierarchy.FieldResolution resolution = hierarchy.resolveField(receiverType, fieldName);
        if (resolution.ambiguous()) {
            diagnostics.add(error(span, "ambiguous inherited interface constant '" + fieldName
                    + "'; candidates are " + resolution.candidates().stream()
                    .map(FieldSymbol::ownerClass).reduce((left, right) -> left + ", " + right)
                    .orElse("")));
        }
        return resolution.selected().orElse(null);
    }

    private FieldSymbol resolveStaticImportedField(String fieldName, SourceSpan span,
                                                   boolean reportAmbiguity) {
        List<FieldSymbol> fields = staticImports.fields(currentClass.unit(), fieldName);
        if (fields.size() > 1 && reportAmbiguity) {
            diagnostics.add(error(span, "ambiguous statically imported field '" + fieldName
                    + "'; candidates are " + fields.stream()
                    .map(field -> field.ownerClass() + "." + field.declaration().name())
                    .reduce((left, right) -> left + ", " + right).orElse("")));
        }
        return fields.size() == 1 ? fields.getFirst() : null;
    }

    private FieldTarget resolveLexicalField(String fieldName, SourceSpan span) {
        for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
             lexical != null; lexical = lexical.enclosingType().orElse(null)) {
            FieldSymbol field = resolveField(lexical.selfType(), fieldName, span);
            if (field == null) {
                continue;
            }
            if (!isAccessible(field.accessModifier(), field.ownerClass(), null, false)) {
                diagnostics.add(error(span, memberAccessMessage("field", fieldName,
                        field.accessModifier(), field.ownerClass())));
            }
            if (field.isStatic()) {
                return new FieldTarget(null, field);
            }
            TypedValue enclosing = enclosingInstanceFor(lexical, span);
            if (enclosing == null) {
                diagnostics.add(error(span, "instance field '" + fieldName
                        + "' cannot be referenced from this static or lexical context"));
                return new FieldTarget(null, field);
            }
            IrOperand receiver = requireValue(enclosing, lexical.selfType(), span,
                    "implicit enclosing field receiver");
            return new FieldTarget(receiver, field);
        }
        return null;
    }

    private boolean hasLexicalField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        for (TypeSymbol lexical = currentClass.enclosingType().orElse(null);
             lexical != null; lexical = lexical.enclosingType().orElse(null)) {
            if (hierarchy.lookupField(lexical.selfType(), fieldName).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStaticImportedField(String fieldName) {
        return fieldName != null && !staticImports.fields(currentClass.unit(), fieldName).isEmpty();
    }

    private boolean isAccessible(ironwood.compiler.ast.AccessModifier access, String ownerClass,
                                 String receiverType, boolean superclassConstructor) {
        TypeSymbol owner = hierarchy.type(ownerClass).orElse(null);
        if (owner == null) {
            return false;
        }
        return switch (access) {
            case PUBLIC -> true;
            case PRIVATE -> hierarchy.sameNest(ownerClass, function.ownerType());
            case PACKAGE_PRIVATE -> owner.packageName().equals(currentClass.packageName());
            case PROTECTED -> {
                if (owner.packageName().equals(currentClass.packageName())) {
                    yield true;
                }
                if (!hierarchy.isSubtype(currentClass.name(), ownerClass)) {
                    yield false;
                }
                if (superclassConstructor || receiverType == null) {
                    yield true;
                }
                yield hierarchy.isSubtype(receiverType, currentClass.name());
            }
        };
    }

    private boolean canAssignBlankFinal(FieldSymbol field, boolean directThisReceiver) {
        if (!directThisReceiver || !field.ownerClass().equals(currentClass.name())) {
            return false;
        }
        if (field.isStatic()) {
            return function.sourceName().equals("<clinit>");
        }
        return function.isConstructor() && !evaluatingConstructorArguments
                && field.declaration().initializer().isEmpty();
    }

    private void ensureTypeInitialized(String typeName, SourceSpan span) {
        emitCall(new IrEnsureTypeInitializedInstruction(typeName, span), span);
    }

    private static String qualifiedName(Expression expression) {
        if (expression instanceof NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            String receiver = qualifiedName(access.receiver());
            return receiver == null ? null : receiver + "." + access.fieldName();
        }
        return null;
    }

    private static String rootName(Expression expression) {
        if (expression instanceof NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            return rootName(access.receiver());
        }
        return null;
    }

    private static String memberAccessMessage(String kind, String name,
                                              ironwood.compiler.ast.AccessModifier access,
                                              String owner) {
        return kind + " '" + name + "' is "
                + (access == ironwood.compiler.ast.AccessModifier.PRIVATE ? "private" : "not accessible")
                + " in class '" + owner + "'";
    }

    private boolean isAssignable(IrType expected, IrType actual) {
        return hierarchy.isAssignable(expected, actual);
    }

    private IrOperand readLocal(LocalSymbol symbol, SourceSpan span) {
        IrOperand value = environment.get(symbol);
        if (value == null) {
            diagnostics.add(error(span, "local variable '" + symbol.name()
                    + "' might not have been initialized on every path to this switch label"));
            return defaultValue(symbol.type(), span);
        }
        return value;
    }

    private static boolean isIntIndexType(IrType type) {
        return type.equals(IrType.I8) || type.equals(IrType.I16)
                || type.equals(IrType.U16) || type.equals(IrType.I32);
    }

    private boolean isAssignmentConvertible(IrType expected, TypedValue actual) {
        return isAssignable(expected, actual.type()) || constantNarrowingFits(expected, actual);
    }

    private static boolean constantNarrowingFits(IrType expected, TypedValue actual) {
        BigInteger constant = actual.integralConstant();
        if (constant == null || !(actual.type().equals(IrType.I8)
                || actual.type().equals(IrType.I16) || actual.type().equals(IrType.U16)
                || actual.type().equals(IrType.I32))) {
            return false;
        }
        BigInteger minimum;
        BigInteger maximum;
        if (expected.equals(IrType.I8)) {
            minimum = BigInteger.valueOf(Byte.MIN_VALUE);
            maximum = BigInteger.valueOf(Byte.MAX_VALUE);
        } else if (expected.equals(IrType.I16)) {
            minimum = BigInteger.valueOf(Short.MIN_VALUE);
            maximum = BigInteger.valueOf(Short.MAX_VALUE);
        } else if (expected.equals(IrType.U16)) {
            minimum = BigInteger.ZERO;
            maximum = BigInteger.valueOf(Character.MAX_VALUE);
        } else {
            return false;
        }
        return constant.compareTo(minimum) >= 0 && constant.compareTo(maximum) <= 0;
    }

    private static BigInteger wrapIntegral(BigInteger value, IrType type) {
        int bits = switch (type.kind()) {
            case I8 -> 8;
            case I16, U16 -> 16;
            case I32 -> 32;
            case I64 -> 64;
            default -> throw new IllegalArgumentException("not an integral type: " + type.displayName());
        };
        BigInteger modulus = BigInteger.ONE.shiftLeft(bits);
        value = value.mod(modulus);
        if (!type.equals(IrType.U16) && value.testBit(bits - 1)) {
            value = value.subtract(modulus);
        }
        return value;
    }

    private static BigInteger evaluateIntegralBinary(BinaryOperator operator, TypedValue left,
                                                     TypedValue right, IrType resultType) {
        BigInteger leftValue = left.integralConstant();
        BigInteger rightValue = right.integralConstant();
        if (leftValue == null || rightValue == null || !resultType.isIntegral()) {
            return null;
        }
        BigInteger result;
        try {
            result = switch (operator) {
                case ADD -> leftValue.add(rightValue);
                case SUBTRACT -> leftValue.subtract(rightValue);
                case MULTIPLY -> leftValue.multiply(rightValue);
                case DIVIDE -> rightValue.signum() == 0 ? null : leftValue.divide(rightValue);
                case REMAINDER -> rightValue.signum() == 0 ? null : leftValue.remainder(rightValue);
                case BITWISE_AND -> leftValue.and(rightValue);
                case BITWISE_XOR -> leftValue.xor(rightValue);
                case BITWISE_OR -> leftValue.or(rightValue);
                case SHIFT_LEFT -> leftValue.shiftLeft(rightValue.intValue()
                        & (resultType.equals(IrType.I64) ? 63 : 31));
                case SHIFT_RIGHT -> leftValue.shiftRight(rightValue.intValue()
                        & (resultType.equals(IrType.I64) ? 63 : 31));
                case UNSIGNED_SHIFT_RIGHT -> {
                    int bits = resultType.equals(IrType.I64) ? 64 : 32;
                    BigInteger unsigned = leftValue.signum() < 0
                            ? leftValue.add(BigInteger.ONE.shiftLeft(bits)) : leftValue;
                    yield unsigned.shiftRight(rightValue.intValue() & (bits - 1));
                }
                default -> null;
            };
        } catch (ArithmeticException failure) {
            return null;
        }
        return result == null ? null : wrapIntegral(result, resultType);
    }

    private boolean validEquality(IrType left, IrType right) {
        if (left.equals(IrType.VOID) || right.equals(IrType.VOID)) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        if (left.isReference() && right.equals(IrType.NULL)
                || right.isReference() && left.equals(IrType.NULL)) {
            return true;
        }
        if (left.isReference() && right.isReference()
                && (left.isTypeParameter() || right.isTypeParameter()
                || left.isWildcard() || right.isWildcard())) {
            return true;
        }
        return left.isReference() && right.isReference()
                && (hierarchy.isAssignable(left, right) || hierarchy.isAssignable(right, left));
    }

    private IrType equalityOperandType(IrType left, IrType right) {
        if (left.isReference() && right.isReference()) {
            if (left.isTypeParameter() || right.isTypeParameter()
                    || left.isWildcard() || right.isWildcard()) {
                return IrType.reference("ironwood.lang.Object");
            }
            return hierarchy.isAssignable(left, right) ? left : right;
        }
        if (left.isReference()) {
            return left;
        }
        if (right.isReference()) {
            return right;
        }
        if (left.equals(IrType.NULL) && right.equals(IrType.NULL)) {
            return IrType.NULL;
        }
        if (left.equals(IrType.VOID)) {
            return IrType.I32;
        }
        return left;
    }

    private void diagnosePatternConflicts(Expression expression) {
        for (PatternFlow.Conflict conflict : PatternFlow.analyze(expression).conflicts()) {
            String key = conflict.earlier().variable().nameSpan().start().offset()
                    + ":" + conflict.later().variable().nameSpan().start().offset();
            if (reportedPatternConflicts.add(key)) {
                diagnostics.add(error(conflict.span(), "pattern variable '"
                        + conflict.later().variable().name()
                        + "' is declared on overlapping boolean paths"));
            }
        }
    }

    private void activatePatternBindings(List<PatternFlow.Binding> bindings) {
        for (PatternFlow.Binding binding : bindings) {
            PatternLocal local = patternLocals.get(binding.expression());
            if (local == null) {
                continue;
            }
            LocalSymbol existing = resolve(binding.variable().name());
            if (existing != null && existing != local.symbol) {
                String key = "scope:" + binding.variable().nameSpan().start().offset()
                        + ":" + existing.id();
                if (reportedPatternConflicts.add(key)) {
                    diagnostics.add(error(binding.variable().nameSpan(), "pattern variable '"
                            + binding.variable().name()
                            + "' conflicts with an active local or parameter"));
                }
            }
            scopes.peek().put(binding.variable().name(), local.symbol);
            environment.put(local.symbol, local.operand);
        }
    }

    private LocalSymbol declare(String name, IrType type, SourceSpan span, String kind) {
        return declare(name, type, span, kind, false);
    }

    private LocalSymbol declare(String name, IrType type, SourceSpan span, String kind,
                                boolean isFinal) {
        if (resolve(name) != null) {
            diagnostics.add(error(span, kind + " '" + name + "' conflicts with an active local or parameter"));
            return null;
        }
        LocalSymbol symbol = new LocalSymbol(nextSymbolId++, name, type, isFinal, controlFlowDepth,
                currentClass.variableAt(span).orElse(null));
        scopes.peek().put(name, symbol);
        return symbol;
    }

    private LocalSymbol resolve(String name) {
        for (Map<String, LocalSymbol> scope : scopes) {
            LocalSymbol symbol = scope.get(name);
            if (symbol != null && !(loweringInstanceInitializer
                    && constructorParameterSymbols.contains(symbol))) {
                return symbol;
            }
        }
        return null;
    }

    private void diagnoseIllegalForwardInstanceFieldRead(FieldSymbol field, String sourceName,
                                                           SourceSpan span) {
        if (loweringInstanceInitializer && !field.isStatic()
                && field.ownerClass().equals(currentClass.name())
                && !declaredInstanceInitializerFields.contains(field.declaration().name())) {
            diagnostics.add(error(span, "illegal forward reference to instance field '"
                    + sourceName + "'"));
        }
    }

    private void diagnoseIllegalForwardStaticFieldRead(FieldSymbol field, String sourceName,
                                                        SourceSpan span) {
        if (!function.sourceName().equals("<clinit>") || !field.isStatic()
                || !field.ownerClass().equals(currentClass.name())) {
            return;
        }
        boolean declaredLater = field.declaration().nameSpan().start().offset()
                > span.start().offset();
        boolean selfReference = field.declaration().initializer()
                .map(initializer -> initializer.span().start().offset() <= span.start().offset()
                        && initializer.span().end().offset() >= span.end().offset())
                .orElse(false);
        if (declaredLater || selfReference) {
            diagnostics.add(error(span, "illegal forward reference to static field '"
                    + sourceName + "'"));
        }
    }

    private void enterScope() {
        scopes.push(new LinkedHashMap<>());
    }

    private void exitScope() {
        Map<String, LocalSymbol> removed = scopes.pop();
        removed.values().forEach(environment::remove);
        if (expressionDepth == 0 && currentBlock.terminator == null) observeUnfreed(true, false);
    }

    private void observeUnfreed(boolean scopeExit, boolean methodExit) {
        if (unfreed == null) return;
        Set<AllocationInfo> retained = new LinkedHashSet<>(knownArraySlots.values());
        constructorBorrows.values().forEach(retained::addAll);
        retained.addAll(poolOwners.keySet());
        retained.addAll(pendingYieldAllocations);
        if (!methodExit) environment.values().stream().map(this::allocationOf)
                .filter(java.util.Objects::nonNull).forEach(retained::add);
        unfreed.observe(allocation -> allocation.present && allocation.state == AllocationState.ACTIVE
                && allocation.origin != AllocationOrigin.OWNED_FIELD && !retained.contains(allocation), scopeExit);
    }

    private void completeUnfreedCall(IrInstruction call) {
        if (unfreed == null) return;
        Optional<IrValueReference> result = switch (call) {
            case IrCallInstruction direct -> direct.result();
            case IrVirtualCallInstruction virtual -> virtual.result();
            case IrInterfaceCallInstruction itf -> itf.result();
            default -> Optional.empty();
        };
        result.filter(unfreedFreshResults::contains)
                .ifPresent(value -> unfreed.completed(allocationsByOperand.get(value)));
    }

    private void emitCall(IrInstruction call, SourceSpan span) {
        PoolTransfer transfer = pendingPoolTransfer;
        pendingPoolTransfer = null;
        AllocationInfo cleared = pendingContainerClear;
        pendingContainerClear = null;
        WrapperBorrow wrapper = pendingWrapperBorrow;
        pendingWrapperBorrow = null;
        List<IrOperand> operands = switch (call) {
            case IrCallInstruction direct -> direct.arguments();
            case IrVirtualCallInstruction virtual -> virtual.arguments();
            case IrInterfaceCallInstruction itf -> itf.arguments();
            case IrStringConcatInstruction concat -> concat.parts().stream()
                    .map(IrStringConcatPart::value).toList();
            default -> List.of();
        };
        operands.forEach(operand -> checkNotFreed(operand, span));
        if (unfreed != null && reclamationEffects != null) {
            java.util.BitSet reclaimed = reclamationEffects.possiblyReclaimedArguments(call);
            for (int index = reclaimed.nextSetBit(0); index >= 0;
                 index = reclaimed.nextSetBit(index + 1)) {
                if (index < operands.size()) unfreed.consumed(allocationOf(operands.get(index)));
            }
        }
        ExceptionRegion region = exceptionRegions.peek();
        if (region == null) {
            currentBlock.addInstruction(call);
            finishPoolTransfer(transfer);
            finishContainerCall(cleared, wrapper);
            completeUnfreedCall(call);
            return;
        }
        MutableBlock predecessor = currentBlock;
        MutableBlock normal = createBlock("invoke.continue", span);
        predecessor.terminate(new IrInvokeTerminator(call, normal.label,
                region.landingPad.label, span));
        region.addEdge(new ExceptionEdge(predecessor, copyEnvironment(),
                snapshotOwnership()));
        currentBlock = normal;
        finishPoolTransfer(transfer);
        finishContainerCall(cleared, wrapper);
        completeUnfreedCall(call);
    }

    private void emitConstructorCallWithRollback(IrInstruction call,
                                                 IrOperand allocation,
                                                 SourceSpan span) {
        List<ExceptionRegion> outerRegions = List.copyOf(exceptionRegions);
        LinkedHashMap<LocalSymbol, IrOperand> before = copyEnvironment();
        OwnershipSnapshot ownershipBefore = snapshotOwnership();
        ExceptionRegion rollback = new ExceptionRegion(
                createBlock("constructor.rollback", span));
        exceptionRegions.push(rollback);
        emitCall(call, span);
        MutableBlock normal = currentBlock;
        LinkedHashMap<LocalSymbol, IrOperand> normalEnvironment = copyEnvironment();
        OwnershipSnapshot normalOwnership = snapshotOwnership();
        restoreDeque(exceptionRegions, outerRegions);
        if (rollback.edges.isEmpty()) {
            rollback.landingPad.terminate(new IrUnreachable(span));
        } else {
            IrOperand exception = beginExceptionHandler(rollback, before,
                    ownershipBefore, span);
            currentBlock.addInstruction(new IrRollbackInstruction(allocation, span));
            emitThrow(exception, span);
        }
        currentBlock = normal;
        environment = normalEnvironment;
        restoreOwnership(normalOwnership);
    }

    private MutableBlock createBlock(String prefix, SourceSpan span) {
        return createNamedBlock(prefix + "." + nextBlockId++, span);
    }

    private MutableBlock createNamedBlock(String label, SourceSpan span) {
        MutableBlock block = new MutableBlock(label, span);
        blocks.put(label, block);
        return block;
    }

    private IrValueReference newValue(IrType type, SourceSpan span) {
        return new IrValueReference(nextValueId++, type, span);
    }

    private AllocationInfo allocationOf(IrOperand operand) {
        AllocationInfo allocation = operand == null ? null : allocationsByOperand.get(operand);
        // Most references have no pool owner. Avoid allocating traversal state
        // for this frequently queried identity; the bound also stops join cycles.
        int remaining = poolOwners.size();
        while (allocation != null && remaining-- > 0) {
            AllocationInfo parent = poolOwners.get(allocation);
            if (parent == null) { break; }
            allocation = parent;
        }
        return allocation;
    }

    private boolean isDependentBorrow(IrOperand operand) {
        return ownedHelperBorrows.contains(operand)
                || poolOwners.containsKey(allocationsByOperand.get(operand));
    }

    private void finishPoolTransfer(PoolTransfer transfer) {
        if (transfer == null || transfer.value() instanceof IrNull) { return; }
        AllocationInfo owner = allocationOf(transfer.owner());
        AllocationInfo value = allocationOf(transfer.value());
        if (isDependentBorrow(transfer.value())) {
            AllocationInfo registry = poolValueOwner(transfer.value());
            AllocationInfo receiver = !isDependentBorrow(transfer.owner())
                    ? allocationsByOperand.get(transfer.owner()) : null;
            if (registry != null && registry == receiver) { return; }
            if (owner == null) {
                markEscaped(transfer.value(), "pooled object returned through an unknown pool");
            } else {
                diagnostics.add(error(transfer.value().sourceSpan(),
                        "cannot transfer an object owned by another pool or containing object; "
                                + "release must return a value checked out from this pool"));
            }
            return;
        }
        if (owner == null || value == null || owner == value
                || value.state != AllocationState.ACTIVE || value.origin == AllocationOrigin.OWNED_FIELD
                || knownArraySlots.containsValue(value)
                || constructorBorrows.values().stream().anyMatch(values -> values.contains(value))) {
            markEscaped(transfer.owner(), "pool received an object whose exclusive ownership is unproved");
            markEscaped(transfer.value(), "allocation escapes through argument 1 of method 'release'");
            return;
        }
        poolOwners.put(value, owner);
        Set<AllocationInfo> dependencies = new LinkedHashSet<>(constructorBorrows.getOrDefault(owner, Set.of()));
        dependencies.addAll(constructorBorrows.getOrDefault(value, Set.of()));
        constructorBorrows.remove(value);
        if (!dependencies.isEmpty()) { constructorBorrows.put(owner, Set.copyOf(dependencies)); }
    }

    private AllocationInfo poolValueOwner(IrOperand value) {
        AllocationInfo adoptedOwner = poolOwners.get(allocationsByOperand.get(value));
        return adoptedOwner != null ? adoptedOwner : poolValueOwners.get(value);
    }

    private record PoolTransfer(IrOperand owner, IrOperand value) {}

    private void propagateOwnedHelperBorrow(IrOperand target, IrOperand source) {
        if (isDependentBorrow(source)) {
            ownedHelperBorrows.add(target);
            AllocationInfo registry = poolValueOwner(source);
            if (registry != null) { poolValueOwners.put(target, registry); }
            String concreteType = ownedHelperBorrowTypes.get(source);
            if (concreteType != null) {
                ownedHelperBorrowTypes.put(target, concreteType);
            }
        }
    }

    private void propagateCommonOwnedHelperBorrow(IrOperand target,
                                                  List<IrOperand> sources) {
        if (sources.isEmpty() || sources.stream()
                .anyMatch(source -> !isDependentBorrow(source))) {
            return;
        }
        ownedHelperBorrows.add(target);
        AllocationInfo registry = poolValueOwner(sources.getFirst());
        if (registry != null && sources.stream().allMatch(source -> poolValueOwner(source) == registry)) {
            poolValueOwners.put(target, registry);
        }
        String concreteType = ownedHelperBorrowTypes.get(sources.getFirst());
        if (concreteType != null && sources.stream().allMatch(source ->
                concreteType.equals(ownedHelperBorrowTypes.get(source)))) {
            ownedHelperBorrowTypes.put(target, concreteType);
        }
    }

    private void trackOwnedFieldLoad(IrOperand loaded, IrOperand receiver, FieldSymbol field) {
        // References reached through a pooled value or helper must not outlive
        // the owner, including internal nodes and segment arrays.
        if (loaded.type().isReference() && isDependentBorrow(receiver)) {
            AllocationInfo owner = allocationOf(receiver);
            if (owner != null) {
                allocationsByOperand.put(loaded, owner);
                ownedHelperBorrows.add(loaded);
            }
            return;
        }
        if (thisOperand == null || !receiver.equals(thisOperand)) {
            return;
        }
        if (!ownedArrayFields.isOwned(field)) {
            String rejectionReason = ownedArrayFields.rejectionReason(field);
            if (rejectionReason != null) {
                AllocationInfo allocation = AllocationInfo.borrowedField(controlFlowDepth,
                        field.declaration().name());
                allocation.makeUncertain(rejectionReason);
                allocations.add(allocation);
                allocationsByOperand.put(loaded, allocation);
            }
            return;
        }
        String key = ownedFieldKey(field);
        AllocationInfo allocation = borrowedOwnedFields.get(key);
        if (allocation == null) {
            allocation = AllocationInfo.borrowedField(controlFlowDepth,
                    field.declaration().name());
            borrowedOwnedFields.put(key, allocation);
            allocations.add(allocation);
        }
        allocationsByOperand.put(loaded, allocation);
    }

    private void detachOwnedField(IrOperand receiver, FieldSymbol field, IrOperand replacement) {
        if (thisOperand == null || !receiver.equals(thisOperand) || !ownedArrayFields.isOwned(field)) {
            return;
        }
        String key = ownedFieldKey(field);
        AllocationInfo previous = borrowedOwnedFields.get(key);
        if (previous == null || allocationOf(replacement) == previous) {
            return;
        }
        previous.detached = true;
        borrowedOwnedFields.remove(key);
    }

    private void markAttachedOwnedFieldLoansUncertain(String reason) {
        var fields = borrowedOwnedFields.entrySet().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, AllocationInfo> field = fields.next();
            AllocationInfo allocation = field.getValue();
            boolean liveLocalAlias = environment.values().stream()
                    .anyMatch(value -> allocationOf(value) == allocation);
            if (!liveLocalAlias) {
                fields.remove();
            } else if (!allocation.detached) {
                allocation.makeUncertain(reason);
            }
        }
    }

    private static String ownedFieldKey(FieldSymbol field) {
        return field.ownerClass() + "#" + field.declaration().name();
    }

    private Map<AllocationInfo, AllocationStateSnapshot> snapshotAllocationStates() {
        Map<AllocationInfo, AllocationStateSnapshot> result = new IdentityHashMap<>();
        for (AllocationInfo allocation : allocations) {
            if (allocation.present) {
                result.put(allocation, new AllocationStateSnapshot(allocation.state,
                        allocation.blockingReason, allocation.detached));
            }
        }
        return result;
    }

    private OwnershipSnapshot snapshotOwnership() {
        return new OwnershipSnapshot(snapshotAllocationStates(),
                new LinkedHashMap<>(knownArraySlots),
                new LinkedHashMap<>(borrowedOwnedFields), new IdentityHashMap<>(constructorBorrows),
                new IdentityHashMap<>(poolOwners), Set.copyOf(exposedContainerContents),
                unfreed == null ? Set.of() : unfreed.snapshot());
    }

    private void restoreOwnership(OwnershipSnapshot snapshot) {
        if (unfreed != null) unfreed.restore(snapshot.unfreedLive());
        allocations.forEach(allocation -> allocation.present = false);
        for (Map.Entry<AllocationInfo, AllocationStateSnapshot> entry
                : snapshot.states().entrySet()) {
            AllocationInfo allocation = entry.getKey();
            AllocationStateSnapshot state = entry.getValue();
            allocation.present = true;
            allocation.state = state.state();
            allocation.blockingReason = state.blockingReason();
            allocation.detached = state.detached();
        }
        knownArraySlots.clear();
        knownArraySlots.putAll(snapshot.knownArraySlots());
        borrowedOwnedFields.clear();
        borrowedOwnedFields.putAll(snapshot.borrowedOwnedFields());
        constructorBorrows.clear();
        constructorBorrows.putAll(snapshot.constructorBorrows());
        poolOwners.clear();
        poolOwners.putAll(snapshot.poolOwners());
        exposedContainerContents.clear();
        exposedContainerContents.addAll(snapshot.exposedContainerContents());
    }

    private void mergeOwnership(OwnershipSnapshot before,
                                List<OwnershipSnapshot> incoming,
                                String conflictReason) {
        restoreOwnership(before);
        Set<AllocationInfo> joined = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        incoming.forEach(snapshot -> joined.addAll(snapshot.states().keySet()));
        for (AllocationInfo allocation : joined) {
            List<AllocationStateSnapshot> states = incoming.stream()
                    .map(snapshot -> snapshot.states().get(allocation))
                    .filter(java.util.Objects::nonNull).toList();
            if (states.isEmpty()) {
                continue;
            }
            AllocationStateSnapshot first = states.getFirst();
            allocation.present = true;
            if (states.stream().allMatch(first::equals)) {
                allocation.state = first.state();
                allocation.blockingReason = first.blockingReason();
                allocation.detached = first.detached();
            } else {
                allocation.state = states.stream().anyMatch(state -> state.state().mayBeFreed())
                        ? AllocationState.MAYBE_FREED : AllocationState.UNCERTAIN;
                allocation.blockingReason = allocation.state == AllocationState.MAYBE_FREED
                        ? "allocation may have been freed on an incoming control-flow path"
                        : conflictReason;
                allocation.detached = states.stream().allMatch(AllocationStateSnapshot::detached);
            }
        }
        knownArraySlots.entrySet().removeIf(entry -> incoming.stream().anyMatch(snapshot ->
                snapshot.knownArraySlots().get(entry.getKey()) != entry.getValue()));
        borrowedOwnedFields.entrySet().removeIf(entry -> incoming.stream().anyMatch(snapshot ->
                snapshot.borrowedOwnedFields().get(entry.getKey()) != entry.getValue()));
        if (!incoming.isEmpty()) {
            for (Map.Entry<ArraySlot, AllocationInfo> entry
                    : incoming.getFirst().knownArraySlots().entrySet()) {
                if (incoming.stream().allMatch(snapshot ->
                        snapshot.knownArraySlots().get(entry.getKey()) == entry.getValue())) {
                    knownArraySlots.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, AllocationInfo> entry
                    : incoming.getFirst().borrowedOwnedFields().entrySet()) {
                if (incoming.stream().allMatch(snapshot ->
                        snapshot.borrowedOwnedFields().get(entry.getKey()) == entry.getValue())) {
                    borrowedOwnedFields.put(entry.getKey(), entry.getValue());
                }
            }
        }
        poolOwners.clear();
        for (OwnershipSnapshot path : incoming) {
            path.poolOwners().forEach((value, owner) -> {
                AllocationInfo previous = poolOwners.putIfAbsent(value, owner);
                if (previous != null && previous != owner) {
                    previous.blockReclamation("object has conflicting pool owners across control flow");
                    owner.blockReclamation("object has conflicting pool owners across control flow");
                }
            });
        }
        constructorBorrows.clear();
        exposedContainerContents.clear();
        for (OwnershipSnapshot path : incoming) {
            exposedContainerContents.addAll(path.exposedContainerContents());
            path.constructorBorrows().forEach((owner, borrowed) -> {
                Set<AllocationInfo> merged = new LinkedHashSet<>(constructorBorrows.getOrDefault(owner, Set.of()));
                merged.addAll(borrowed);
                constructorBorrows.put(owner, Set.copyOf(merged));
            });
        }
        // An inexact slot loses load identity, not the fact that it may retain
        // an alias. Otherwise a branch-local store could make a later free unsafe.
        for (OwnershipSnapshot path : incoming) {
            path.knownArraySlots().forEach((slot, allocation) -> {
                if (knownArraySlots.get(slot) != allocation) {
                    allocation.blockReclamation("allocation may still be observed through "
                            + "an array element on an incoming control-flow path");
                }
            });
        }
        if (unfreed != null) unfreed.merge(incoming.stream().map(OwnershipSnapshot::unfreedLive).toList());
    }

    private void mergeFlowOwnership(List<BranchFlow> incoming) {
        if (!incoming.isEmpty()) {
            mergeOwnership(incoming.getFirst().ownership(),
                    incoming.stream().map(BranchFlow::ownership).toList(),
                    "allocation has conflicting ownership across control-flow paths");
        }
    }

    private void mergeLoopOwnership(List<BranchFlow> exits, List<BranchFlow> backEdges) {
        List<BranchFlow> paths = new ArrayList<>(exits);
        paths.addAll(backEdges);
        mergeFlowOwnership(paths);
    }

    // A body-local allocation is recreated on each iteration. An allocation already
    // visible at entry is not: replaying a free requires the same live ownership
    // and identity on every back edge, including edges from nested labeled jumps.
    private void validateLoopBackEdges(OwnershipSnapshot entry,
                                       LinkedHashMap<LocalSymbol, IrOperand> before,
                                       List<BranchFlow> backEdges, int firstReclamation) {
        for (BranchFlow flow : backEdges) {
            for (LocalSymbol local : before.keySet()) {
                AllocationInfo carried = allocationOf(flow.environment().get(local));
                AllocationStateSnapshot state = carried == null ? null
                        : flow.ownership().states().get(carried);
                AllocationStateSnapshot initial = carried == null ? null : entry.states().get(carried);
                boolean alreadyDead = initial != null && initial.state().mayBeFreed()
                        && allocationOf(before.get(local)) == carried;
                if (state != null && state.state().mayBeFreed() && !alreadyDead) {
                    diagnostics.add(error(flow.block().span, "cannot carry freed allocation in local '"
                            + local.name() + "' across loop back edge"));
                }
            }
        }
        for (Reclamation reclamation : reclamations.subList(firstReclamation, reclamations.size())) {
            AllocationInfo allocation = reclamation.allocation();
            AllocationStateSnapshot initial = entry.states().get(allocation);
            if (initial == null) {
                continue;
            }
            boolean invalid = backEdges.stream().anyMatch(flow ->
                    !initial.equals(flow.ownership().states().get(allocation))
                    || before.entrySet().stream().anyMatch(local ->
                    (allocationOf(local.getValue()) == allocation)
                    != (allocationOf(flow.environment().get(local.getKey())) == allocation))
                    || flow.ownership().knownArraySlots().containsValue(allocation));
            if (invalid) {
                diagnostics.add(error(reclamation.span(), "cannot prove free safe across loop back edge: "
                        + "the next iteration may observe a freed, escaped, or different allocation"));
            }
        }
    }

    private void trackArrayElementStore(IrOperand array, IrOperand index, IrOperand value) {
        checkNotFreed(array, array.sourceSpan());
        if (!array.type().isArray() || !array.type().elementType().isReference()) {
            return;
        }
        AllocationInfo container = allocationOf(array);
        Integer constantIndex = constantArrayIndex(index);
        AllocationInfo stored = allocationOf(value);
        if (container == null || constantIndex == null
                || container.state != AllocationState.ACTIVE) {
            markEscaped(value, "allocation escapes through reference-array element");
            return;
        }
        ArraySlot slot = new ArraySlot(container, constantIndex);
        knownArraySlots.remove(slot);
        if (stored != null) {
            knownArraySlots.put(slot, stored);
        }
    }

    private void checkNotFreed(IrOperand operand, SourceSpan span) {
        AllocationInfo allocation = allocationOf(operand);
        if (allocation != null && allocation.state.mayBeFreed()) {
            diagnostics.add(error(span, "cannot use evaluated reference after its allocation was freed"));
        }
    }

    private void trackArrayElementLoad(IrOperand result, IrOperand array, IrOperand index) {
        if (result.type().isReference() && isDependentBorrow(array)) {
            AllocationInfo owner = allocationOf(array);
            if (owner != null) {
                allocationsByOperand.put(result, owner);
                ownedHelperBorrows.add(result);
            }
            return;
        }
        AllocationInfo container = allocationOf(array);
        Integer constantIndex = constantArrayIndex(index);
        if (container == null || constantIndex == null) {
            exposeContainerContents(array, "inexact array load can expose stored data-structure references");
            return;
        }
        AllocationInfo stored = knownArraySlots.get(new ArraySlot(container, constantIndex));
        if (stored != null) {
            allocationsByOperand.put(result, stored);
        }
    }

    private static Integer constantArrayIndex(IrOperand index) {
        return index instanceof IrConstant constant && constant.type().equals(IrType.I32)
                ? constant.value().intValue() : null;
    }

    private void exposeArrayElements(IrOperand array, String reason) {
        AllocationInfo container = allocationOf(array);
        if (container == null) {
            return;
        }
        Set<AllocationInfo> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        knownArraySlots.entrySet().stream()
                .filter(entry -> entry.getKey().container() == container)
                .map(Map.Entry::getValue)
                .forEach(allocation -> markEscaped(allocation, reason, visited));
    }

    private void markEscaped(IrOperand operand, String reason) {
        AllocationInfo allocation = allocationOf(operand);
        if (allocation != null) {
            markEscaped(allocation, reason,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    private void markEscaped(AllocationInfo allocation, String reason,
                             Set<AllocationInfo> visited) {
        if (!visited.add(allocation)) {
            return;
        }
        allocation.escape(reason);
        constructorBorrows.getOrDefault(allocation, Set.of()).forEach(child ->
                markEscaped(child, "allocation is borrowed by an escaped wrapper", visited));
        knownArraySlots.entrySet().stream()
                .filter(entry -> entry.getKey().container() == allocation)
                .map(Map.Entry::getValue)
                .forEach(child -> markEscaped(child,
                        "allocation escapes through an element of an escaped array", visited));
    }

    private LinkedHashMap<LocalSymbol, IrOperand> copyEnvironment() {
        return new LinkedHashMap<>(environment);
    }

    private static <T> void restoreDeque(Deque<T> deque, List<T> values) {
        deque.clear();
        values.forEach(deque::addLast);
    }

    private IrOperand defaultValue(IrType type, SourceSpan span) {
        if (type.isReference() || type.equals(IrType.NULL)) {
            return new IrNull(type, span);
        }
        if (type.equals(IrType.F32)) {
            return new IrConstant(type, 0.0f, span);
        }
        if (type.equals(IrType.F64)) {
            return new IrConstant(type, 0.0d, span);
        }
        IrType valueType = type.equals(IrType.VOID) || type.equals(IrType.EXCEPTION)
                ? IrType.I32 : type;
        return new IrConstant(valueType, 0, span);
    }

    private Diagnostic error(SourceSpan span, String message) {
        return Diagnostic.error(source, span, message);
    }

    private boolean isPrintStreamWriteIntrinsic() {
        return function.ownerType().equals("ironwood.io.PrintStream")
                && (function.sourceName().equals("nativePrint")
                || function.sourceName().equals("nativePrintln"))
                && !function.isStatic()
                && function.returnType().equals(IrType.VOID)
                && (function.parameterTypes().isEmpty()
                ? function.sourceName().equals("nativePrintln")
                : function.parameterTypes().size() == 1
                && printableIntrinsicType(function.parameterTypes().getFirst()));
    }

    private static boolean printableIntrinsicType(IrType type) {
        return type.equals(IrType.reference("ironwood.lang.String"))
                || type.equals(IrType.I1) || type.equals(IrType.U16)
                || type.equals(IrType.I32) || type.equals(IrType.I64)
                || type.equals(IrType.F32) || type.equals(IrType.F64);
    }

    private static IrStringConcatPartKind printValueKind(IrType type) {
        if (type.equals(IrType.reference("ironwood.lang.String"))) {
            return IrStringConcatPartKind.STRING;
        }
        if (type.equals(IrType.I1)) {
            return IrStringConcatPartKind.BOOLEAN;
        }
        if (type.equals(IrType.U16)) {
            return IrStringConcatPartKind.CHARACTER;
        }
        if (type.equals(IrType.F32)) {
            return IrStringConcatPartKind.FLOAT;
        }
        if (type.equals(IrType.F64)) {
            return IrStringConcatPartKind.DOUBLE;
        }
        return IrStringConcatPartKind.INTEGER;
    }

    private boolean isPrintStreamFlushIntrinsic() {
        return function.ownerType().equals("ironwood.io.PrintStream")
                && function.sourceName().equals("nativeFlush") && !function.isStatic()
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().isEmpty();
    }

    private boolean isPrintStreamCheckErrorIntrinsic() {
        return function.ownerType().equals("ironwood.io.PrintStream")
                && function.sourceName().equals("nativeCheckError") && !function.isStatic()
                && function.returnType().equals(IrType.I1)
                && function.parameterTypes().isEmpty();
    }

    private boolean isReleaseOwnedThrowableMessageIntrinsic() {
        return function.ownerType().equals("ironwood.lang.Throwable")
                && function.sourceName().equals("releaseLocalizedMessage") && function.isStatic()
                && function.accessModifier() == AccessModifier.PRIVATE
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().equals(List.of(
                        IrType.reference("ironwood.lang.Throwable"), STRING_TYPE));
    }

    private boolean isReleaseOwnedToStringResultIntrinsic() {
        return (function.ownerType().equals("ironwood.lang.StringBuilder")
                || function.ownerType().equals("ironwood.lang.String")
                || function.ownerType().equals("ironwood.lang.Throwable")
                || function.ownerType().equals("ironwood.time.format.DateTimeParseException")
                || function.ownerType().equals("ironwood.io.PrintStream")
                || function.ownerType().equals("ironwood.io.PrintWriter"))
                && function.sourceName().equals("releaseRenderedString")
                && function.isStatic()
                && function.accessModifier() == AccessModifier.PRIVATE
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.Object"),
                IrType.reference("ironwood.lang.String")));
    }

    private boolean isReleaseOwnedFileAllocationIntrinsic() {
        if (!function.ownerType().equals("ironwood.nio.file.Files")
                || !function.isStatic()
                || function.accessModifier() != AccessModifier.PRIVATE
                || !function.returnType().equals(IrType.VOID)) {
            return false;
        }
        if (function.sourceName().equals("releaseOwnedLine")
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.String")))) {
            return true;
        }
        if (function.sourceName().equals("releaseVisitedPath")
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.nio.file.Path")))) {
            return true;
        }
        if (function.sourceName().equals("releaseVisitedAttributes")
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.nio.file.attribute.BasicFileAttributes")))) {
            return true;
        }
        if (function.sourceName().equals("releaseVisitedDirectoryStream")
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.nio.file.UnixDirectoryStream")))) {
            return true;
        }
        return function.sourceName().equals("releaseOwnedLineList")
                && function.isStatic()
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.ds.ArrayList",
                        List.of(IrType.reference("ironwood.lang.String")))));
    }

    private boolean isSystemIdentityHashCodeIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("identityHashCode")
                && function.isStatic()
                && function.returnType().equals(IrType.I32)
                && function.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.Object")));
    }

    private boolean isSystemAllocationCountIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("allocationCount")
                && function.isStatic()
                && function.returnType().equals(IrType.I64)
                && function.parameterTypes().isEmpty();
    }

    private boolean isSystemArrayCopyIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("arraycopy")
                && function.isStatic()
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.Object"), IrType.I32,
                IrType.reference("ironwood.lang.Object"), IrType.I32, IrType.I32));
    }

    private boolean isSystemGetenvIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("environmentValue") && function.isStatic()
                && function.returnType().equals(IrType.reference("ironwood.lang.String"))
                && function.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.String")));
    }

    private Optional<IrSystemClockInstruction.Clock> systemClockIntrinsic() {
        if (!function.ownerType().equals("ironwood.lang.System") || !function.isStatic()
                || !function.returnType().equals(IrType.I64)
                || !function.parameterTypes().isEmpty()) {
            return Optional.empty();
        }
        return switch (function.sourceName()) {
            case "currentTimeMillis" -> Optional.of(
                    IrSystemClockInstruction.Clock.CURRENT_TIME_MILLIS);
            case "nanoTime" -> Optional.of(IrSystemClockInstruction.Clock.NANO_TIME);
            default -> Optional.empty();
        };
    }

    private Optional<IrStreamInstruction.Operation> streamIntrinsicOperation() {
        if (!function.ownerType().equals("ironwood.io.StreamSupport") || !function.isStatic()) {
            return Optional.empty();
        }
        return java.util.Arrays.stream(IrStreamInstruction.Operation.values())
                .filter(operation -> operation.sourceName().equals(function.sourceName())
                        && operation.resultType().equals(function.returnType())
                        && operation.parameterTypes().equals(function.parameterTypes())).findFirst();
    }

    private Optional<IrFileInstruction.Operation> fileIntrinsicOperation() {
        if (!function.isStatic()) {
            return Optional.empty();
        }
        IrType string = IrType.reference("ironwood.lang.String");
        IrType bytes = IrType.array(IrType.I8);
        if (function.ownerType().equals("ironwood.nio.file.Files")) {
            return switch (function.sourceName()) {
                case "readAllBytesValue" -> fileIntrinsic(bytes, List.of(string),
                        IrFileInstruction.Operation.READ_ALL_BYTES);
                case "readStringValue" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.READ_STRING);
                case "writeBytesValue" -> fileIntrinsic(IrType.I32, List.of(string, bytes),
                        IrFileInstruction.Operation.WRITE_BYTES);
                case "writeStringValue" -> fileIntrinsic(IrType.I32, List.of(string, string),
                        IrFileInstruction.Operation.WRITE_STRING);
                case "writeCharsValue" -> fileIntrinsic(IrType.I32,
                        List.of(string, IrType.array(IrType.U16)), IrFileInstruction.Operation.WRITE_CHARS);
                case "deleteValue" -> fileIntrinsic(IrType.I32, List.of(string),
                        IrFileInstruction.Operation.DELETE);
                case "createDirectoriesValue" -> fileIntrinsic(IrType.I32, List.of(string),
                        IrFileInstruction.Operation.CREATE_DIRECTORIES);
                case "copyValue" -> fileIntrinsic(IrType.I32, List.of(string, string),
                        IrFileInstruction.Operation.COPY);
                case "moveValue" -> fileIntrinsic(IrType.I32, List.of(string, string),
                        IrFileInstruction.Operation.MOVE);
                case "openDirectoryValue" -> fileIntrinsic(IrType.I64, List.of(string),
                        IrFileInstruction.Operation.OPEN_DIRECTORY);
                case "directoryHasNextValue" -> fileIntrinsic(IrType.I32, List.of(IrType.I64),
                        IrFileInstruction.Operation.DIRECTORY_HAS_NEXT);
                case "nextDirectoryEntryValue" -> fileIntrinsic(string, List.of(IrType.I64),
                        IrFileInstruction.Operation.NEXT_DIRECTORY_ENTRY);
                case "closeDirectoryValue" -> fileIntrinsic(IrType.I32, List.of(IrType.I64),
                        IrFileInstruction.Operation.CLOSE_DIRECTORY);
                case "readAttributesValue" -> fileIntrinsic(IrType.array(IrType.I64),
                        List.of(string, IrType.I1), IrFileInstruction.Operation.READ_ATTRIBUTES);
                case "fileKind" -> fileIntrinsic(IrType.I32, List.of(string),
                        IrFileInstruction.Operation.FILE_KIND);
                case "fileKindNoFollow" -> fileIntrinsic(IrType.I32, List.of(string),
                        IrFileInstruction.Operation.FILE_KIND_NOFOLLOW);
                case "isSameFileValue" -> fileIntrinsic(IrType.I32, List.of(string, string),
                        IrFileInstruction.Operation.SAME_FILE);
                case "fileSize" -> fileIntrinsic(IrType.I64, List.of(string),
                        IrFileInstruction.Operation.FILE_SIZE);
                case "lastError" -> fileIntrinsic(IrType.I32, List.of(),
                        IrFileInstruction.Operation.LAST_ERROR);
                default -> Optional.empty();
            };
        }
        if (function.ownerType().equals("ironwood.nio.file.Paths")) {
            return switch (function.sourceName()) {
                case "currentDirectoryValue" -> fileIntrinsic(string, List.of(),
                        IrFileInstruction.Operation.CURRENT_DIRECTORY);
                case "normalizeSyntax" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.NORMALIZE_SYNTAX);
                case "normalizePath" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.NORMALIZE_PATH);
                case "fileName" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.FILE_NAME);
                case "parent" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.PARENT);
                case "resolve" -> fileIntrinsic(string, List.of(string, string),
                        IrFileInstruction.Operation.RESOLVE);
                case "resolveSibling" -> fileIntrinsic(string, List.of(string, string),
                        IrFileInstruction.Operation.RESOLVE_SIBLING);
                case "absolutePathValue" -> fileIntrinsic(string, List.of(string),
                        IrFileInstruction.Operation.ABSOLUTE_PATH);
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private Optional<IrType> floatingParseIntrinsic() {
        if (!function.sourceName().equals("parseValidated") || !function.isStatic()
                || !function.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.String")))) {
            return Optional.empty();
        }
        if (function.ownerType().equals("ironwood.lang.Float")
                && function.returnType().equals(IrType.F32)) {
            return Optional.of(IrType.F32);
        }
        if (function.ownerType().equals("ironwood.lang.Double")
                && function.returnType().equals(IrType.F64)) {
            return Optional.of(IrType.F64);
        }
        return Optional.empty();
    }

    private Optional<IrFileInstruction.Operation> fileIntrinsic(
            IrType returnType, List<IrType> parameterTypes,
            IrFileInstruction.Operation operation) {
        return function.returnType().equals(returnType)
                && function.parameterTypes().equals(parameterTypes)
                ? Optional.of(operation) : Optional.empty();
    }

    private Optional<IrCharacterInstruction.Operation> characterIntrinsic() {
        if (!function.ownerType().equals("ironwood.lang.Character") || !function.isStatic()
                || !function.returnType().equals(IrType.I32)
                || !function.parameterTypes().equals(List.of(IrType.I32))) {
            return Optional.empty();
        }
        return switch (function.sourceName()) {
            case "digitProperty" -> Optional.of(IrCharacterInstruction.Operation.DIGIT);
            case "letterProperty" -> Optional.of(IrCharacterInstruction.Operation.LETTER);
            case "upperProperty" -> Optional.of(IrCharacterInstruction.Operation.UPPER);
            case "lowerProperty" -> Optional.of(IrCharacterInstruction.Operation.LOWER);
            case "upperMapping" -> Optional.of(IrCharacterInstruction.Operation.TO_UPPER);
            case "lowerMapping" -> Optional.of(IrCharacterInstruction.Operation.TO_LOWER);
            case "digitValue" -> Optional.of(IrCharacterInstruction.Operation.DIGIT_VALUE);
            case "numericValue" -> Optional.of(IrCharacterInstruction.Operation.NUMERIC_VALUE);
            default -> Optional.empty();
        };
    }

    private Optional<IrFloatingBitsInstruction.Operation> floatingBitsIntrinsic() {
        if (!function.isStatic() || function.parameterTypes().size() != 1) {
            return Optional.empty();
        }
        if (function.ownerType().equals("ironwood.lang.Float")) {
            if (function.sourceName().equals("rawBits")
                    && function.returnType().equals(IrType.I32)
                    && function.parameterTypes().equals(List.of(IrType.F32))) {
                return Optional.of(IrFloatingBitsInstruction.Operation.FLOAT_TO_RAW_INT);
            }
            if (function.sourceName().equals("fromBits")
                    && function.returnType().equals(IrType.F32)
                    && function.parameterTypes().equals(List.of(IrType.I32))) {
                return Optional.of(IrFloatingBitsInstruction.Operation.INT_TO_FLOAT);
            }
        }
        if (function.ownerType().equals("ironwood.lang.Double")) {
            if (function.sourceName().equals("rawBits")
                    && function.returnType().equals(IrType.I64)
                    && function.parameterTypes().equals(List.of(IrType.F64))) {
                return Optional.of(IrFloatingBitsInstruction.Operation.DOUBLE_TO_RAW_LONG);
            }
            if (function.sourceName().equals("fromBits")
                    && function.returnType().equals(IrType.F64)
                    && function.parameterTypes().equals(List.of(IrType.I64))) {
                return Optional.of(IrFloatingBitsInstruction.Operation.LONG_TO_DOUBLE);
            }
        }
        return Optional.empty();
    }

    private Optional<IrMathUnaryInstruction.Operation> mathUnaryIntrinsic() {
        if (!function.ownerType().equals("ironwood.lang.Math") || !function.isStatic()
                || !function.returnType().equals(IrType.F64)
                || !function.parameterTypes().equals(List.of(IrType.F64))) {
            return Optional.empty();
        }
        return switch (function.sourceName()) {
            case "sin" -> Optional.of(IrMathUnaryInstruction.Operation.SIN);
            case "cos" -> Optional.of(IrMathUnaryInstruction.Operation.COS);
            case "tan" -> Optional.of(IrMathUnaryInstruction.Operation.TAN);
            case "exp" -> Optional.of(IrMathUnaryInstruction.Operation.EXP);
            case "log" -> Optional.of(IrMathUnaryInstruction.Operation.LOG);
            case "log10" -> Optional.of(IrMathUnaryInstruction.Operation.LOG10);
            case "sqrt" -> Optional.of(IrMathUnaryInstruction.Operation.SQRT);
            case "cbrt" -> Optional.of(IrMathUnaryInstruction.Operation.CBRT);
            case "rint" -> Optional.of(IrMathUnaryInstruction.Operation.RINT);
            default -> Optional.empty();
        };
    }

    private Optional<IrMathBinaryInstruction.Operation> mathBinaryIntrinsic() {
        if (!function.ownerType().equals("ironwood.lang.Math") || !function.isStatic()
                || !function.returnType().equals(IrType.F64)
                || !function.parameterTypes().equals(List.of(IrType.F64, IrType.F64))) {
            return Optional.empty();
        }
        return switch (function.sourceName()) {
            case "atan2" -> Optional.of(IrMathBinaryInstruction.Operation.ATAN2);
            case "pow" -> Optional.of(IrMathBinaryInstruction.Operation.POW);
            case "hypot" -> Optional.of(IrMathBinaryInstruction.Operation.HYPOT);
            default -> Optional.empty();
        };
    }

    private boolean isSystemLiveAllocationCountIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("liveAllocationCount")
                && function.isStatic()
                && function.returnType().equals(IrType.I64)
                && function.parameterTypes().isEmpty();
    }

    private boolean isSystemPropertyIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("propertyValue")
                && function.isStatic()
                && function.accessModifier() == AccessModifier.PRIVATE
                && function.returnType().equals(IrType.reference("ironwood.lang.String"))
                && function.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.String")));
    }

    private boolean isSystemExitIntrinsic() {
        return function.ownerType().equals("ironwood.lang.System")
                && function.sourceName().equals("terminate")
                && function.isStatic()
                && function.accessModifier() == AccessModifier.PRIVATE
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().equals(List.of(IrType.I32));
    }

    private IrThrowableTraceInstruction.Operation throwableTraceOperation() {
        if (!function.ownerType().equals("ironwood.lang.Throwable")) return null;
        if (!function.isStatic()
                && function.accessModifier() == AccessModifier.PUBLIC
                && function.sourceName().equals("getStackTrace")
                && function.returnType().equals(IrType.array(
                IrType.reference("ironwood.lang.StackTraceElement")))
                && function.parameterTypes().isEmpty()) {
            return IrThrowableTraceInstruction.Operation.ARRAY;
        }
        if (!function.isStatic() || function.accessModifier() != AccessModifier.PRIVATE) return null;
        for (IrThrowableTraceInstruction.Operation operation : IrThrowableTraceInstruction.Operation.values()) {
            if (function.sourceName().equals(operation.sourceName())
                    && function.returnType().equals(operation.returnType())
                    && function.parameterTypes().equals(operation.parameterTypes())) return operation;
        }
        return null;
    }

    private boolean isThrowableSecondaryExceptionCountIntrinsic() {
        return function.ownerType().equals("ironwood.lang.Throwable")
                && function.sourceName().equals("getSecondaryExceptionCount")
                && !function.isStatic()
                && function.returnType().equals(IrType.I32)
                && function.parameterTypes().isEmpty();
    }

    private boolean isThrowableSecondaryExceptionAtIntrinsic() {
        return function.ownerType().equals("ironwood.lang.Throwable")
                && function.sourceName().equals("getSecondaryException")
                && !function.isStatic()
                && function.returnType().equals(IrType.reference("ironwood.lang.Throwable"))
                && function.parameterTypes().equals(List.of(IrType.I32));
    }

    private boolean isObjectHashCodeIntrinsic() {
        return function.ownerType().equals("ironwood.lang.Object")
                && function.sourceName().equals("hashCode")
                && !function.isStatic()
                && function.returnType().equals(IrType.I32)
                && function.parameterTypes().isEmpty();
    }

    private boolean isObjectToStringIntrinsic() {
        return AllocationResultSemantics.isObjectToString(function);
    }

    private boolean isStringCharAtIntrinsic() {
        return function.ownerType().equals("ironwood.lang.String")
                && function.sourceName().equals("uncheckedCharAt")
                && !function.isStatic()
                && function.returnType().equals(IrType.U16)
                && function.parameterTypes().equals(List.of(IrType.I32));
    }

    private boolean isStringEqualsIntrinsic() {
        return function.ownerType().equals("ironwood.lang.String")
                && function.sourceName().equals("contentEquals")
                && !function.isStatic()
                && function.returnType().equals(IrType.I1)
                && function.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.Object")));
    }

    private boolean isStringHashCodeIntrinsic() {
        return function.ownerType().equals("ironwood.lang.String")
                && function.sourceName().equals("contentHashCode")
                && !function.isStatic()
                && function.returnType().equals(IrType.I32)
                && function.parameterTypes().isEmpty();
    }

    private boolean isStringFromCharsIntrinsic() {
        return AllocationResultSemantics.isStringFromChars(function);
    }

    private boolean isStringFromCharRangeIntrinsic() {
        return AllocationResultSemantics.isStringFromCharRange(function);
    }

    private boolean isStringFromRangeIntrinsic() {
        return AllocationResultSemantics.isStringFromRange(function);
    }

    private boolean isStringBoundsFailureIntrinsic() {
        return hierarchy.type("ironwood.lang.StringIndexOutOfBoundsException").isPresent()
                && function.ownerType().equals("ironwood.lang.String")
                && function.sourceName().equals("boundsFailure")
                && !function.isStatic()
                && function.returnType().equals(IrType.VOID)
                && function.parameterTypes().isEmpty();
    }

    private static IrBinaryOperator irOperator(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> IrBinaryOperator.ADD;
            case SUBTRACT -> IrBinaryOperator.SUBTRACT;
            case MULTIPLY -> IrBinaryOperator.MULTIPLY;
            case DIVIDE -> IrBinaryOperator.DIVIDE;
            case REMAINDER -> IrBinaryOperator.REMAINDER;
            case SHIFT_LEFT -> IrBinaryOperator.SHIFT_LEFT;
            case SHIFT_RIGHT -> IrBinaryOperator.SHIFT_RIGHT;
            case UNSIGNED_SHIFT_RIGHT -> IrBinaryOperator.UNSIGNED_SHIFT_RIGHT;
            case BITWISE_AND -> IrBinaryOperator.BITWISE_AND;
            case BITWISE_XOR -> IrBinaryOperator.BITWISE_XOR;
            case BITWISE_OR -> IrBinaryOperator.BITWISE_OR;
            case EQUAL -> IrBinaryOperator.EQUAL;
            case NOT_EQUAL -> IrBinaryOperator.NOT_EQUAL;
            case LESS -> IrBinaryOperator.SIGNED_LESS;
            case LESS_EQUAL -> IrBinaryOperator.SIGNED_LESS_EQUAL;
            case GREATER -> IrBinaryOperator.SIGNED_GREATER;
            case GREATER_EQUAL -> IrBinaryOperator.SIGNED_GREATER_EQUAL;
            case LOGICAL_AND, LOGICAL_OR -> throw new IllegalArgumentException(
                    "short-circuit operators lower through control flow");
        };
    }

    private static BinaryOperator compoundOperator(AssignmentOperator operator) {
        return switch (operator) {
            case ADD -> BinaryOperator.ADD;
            case SUBTRACT -> BinaryOperator.SUBTRACT;
            case MULTIPLY -> BinaryOperator.MULTIPLY;
            case DIVIDE -> BinaryOperator.DIVIDE;
            case REMAINDER -> BinaryOperator.REMAINDER;
            case SHIFT_LEFT -> BinaryOperator.SHIFT_LEFT;
            case SHIFT_RIGHT -> BinaryOperator.SHIFT_RIGHT;
            case UNSIGNED_SHIFT_RIGHT -> BinaryOperator.UNSIGNED_SHIFT_RIGHT;
            case BITWISE_AND -> BinaryOperator.BITWISE_AND;
            case BITWISE_XOR -> BinaryOperator.BITWISE_XOR;
            case BITWISE_OR -> BinaryOperator.BITWISE_OR;
            case ASSIGN -> throw new IllegalArgumentException("plain assignment has no binary operator");
        };
    }

    private static String assignmentOperatorText(AssignmentOperator operator) {
        return switch (operator) {
            case ASSIGN -> "=";
            case ADD -> "+=";
            case SUBTRACT -> "-=";
            case MULTIPLY -> "*=";
            case DIVIDE -> "/=";
            case REMAINDER -> "%=";
            case SHIFT_LEFT -> "<<=";
            case SHIFT_RIGHT -> ">>=";
            case UNSIGNED_SHIFT_RIGHT -> ">>>=";
            case BITWISE_AND -> "&=";
            case BITWISE_XOR -> "^=";
            case BITWISE_OR -> "|=";
        };
    }

    private static String operatorText(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case REMAINDER -> "%";
            case SHIFT_LEFT -> "<<";
            case SHIFT_RIGHT -> ">>";
            case UNSIGNED_SHIFT_RIGHT -> ">>>";
            case BITWISE_AND -> "&";
            case BITWISE_XOR -> "^";
            case BITWISE_OR -> "|";
            case LOGICAL_AND -> "&&";
            case LOGICAL_OR -> "||";
            case EQUAL -> "==";
            case NOT_EQUAL -> "!=";
            case LESS -> "<";
            case LESS_EQUAL -> "<=";
            case GREATER -> ">";
            case GREATER_EQUAL -> ">=";
        };
    }

    private static String unaryOperatorText(ironwood.compiler.ast.UnaryOperator operator) {
        return switch (operator) {
            case POSITIVE -> "+";
            case NEGATE -> "-";
            case NOT -> "!";
            case BITWISE_COMPLEMENT -> "~";
        };
    }

    private enum SwitchSelectorKind {
        INTEGRAL,
        ENUM,
        STRING
    }

    private record SwitchSelection(SwitchSelectorKind kind, IrType selectorType,
                                   IrOperand operand, TypeSymbol enumType,
                                   MutableBlock dispatchBlock) {
    }

    private record LabeledSwitchTarget(List<SwitchLabel> labels, MutableBlock block) {
        private LabeledSwitchTarget {
            labels = List.copyOf(labels);
        }
    }

    private record ValidatedSwitchCase(BigInteger integralValue, String stringValue,
                                       String target, SourceSpan span) {
        private static ValidatedSwitchCase integral(BigInteger value, String target,
                                                    SourceSpan span) {
            return new ValidatedSwitchCase(value, null, target, span);
        }

        private static ValidatedSwitchCase string(String value, String target,
                                                  SourceSpan span) {
            return new ValidatedSwitchCase(null, value, target, span);
        }
    }

    private record ValidatedSwitchLabels(List<ValidatedSwitchCase> cases,
                                         String defaultTarget, String nullTarget,
                                         Set<Integer> enumOrdinals) {
        private ValidatedSwitchLabels {
            cases = List.copyOf(cases);
            enumOrdinals = Set.copyOf(enumOrdinals);
        }
    }

    private record DispatchEdges(Map<String, List<MutableBlock>> predecessors,
                                 OwnershipSnapshot ownership) {
        private DispatchEdges {
            Map<String, List<MutableBlock>> copy = new LinkedHashMap<>();
            predecessors.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            predecessors = Map.copyOf(copy);
        }
    }

    private record YieldFlow(MutableBlock block,
                             LinkedHashMap<LocalSymbol, IrOperand> environment,
                             TypedValue value, SourceSpan span, OwnershipSnapshot ownership) {
        private YieldFlow {
            environment = new LinkedHashMap<>(environment);
        }
    }

    private static final class SwitchExpressionContext {
        private final MutableBlock merge;
        private final List<FinallyContext> targetFinallyContexts;
        private final Optional<IrType> expectedType;
        private final LinkedHashMap<LocalSymbol, IrOperand> before;
        private final List<YieldFlow> yields = new ArrayList<>();

        private SwitchExpressionContext(MutableBlock merge,
                                        List<FinallyContext> targetFinallyContexts,
                                        Optional<IrType> expectedType,
                                        LinkedHashMap<LocalSymbol, IrOperand> before) {
            this.merge = merge;
            this.targetFinallyContexts = List.copyOf(targetFinallyContexts);
            this.expectedType = expectedType;
            this.before = new LinkedHashMap<>(before);
        }
    }

    private record CaseConstant(IrType type, BigInteger value) {
    }

    private record CompileTimeValue(IrType type, Object value) {
    }

    private static String typeName(IrType type) {
        return type.displayName();
    }

    private record TypedValue(IrType type, IrOperand operand, BigInteger integralConstant) {
        private TypedValue(IrType type, IrOperand operand) {
            this(type, operand, null);
        }
    }

    private record RenderedStringConversion(IrOperand object, IrOperand result) {
    }

    private record RenderedStringCleanup(IrOperand object, IrOperand result) {
    }

    private record LoweredInvocationArguments(List<TypedValue> values,
                                              List<IrOperand> operands) {
        private LoweredInvocationArguments {
            values = List.copyOf(values);
            operands = List.copyOf(operands);
            if (values.size() != operands.size()) {
                throw new IllegalArgumentException(
                        "lowered invocation requires one ABI value per operand");
            }
        }
    }

    private record LocalSymbol(int id, String name, IrType type, boolean isFinal,
                               int declarationControlFlowDepth,
                               LocalClassSemantics.VariableIdentity variableIdentity) {
    }

    private static final class PatternLocal {
        private final LocalSymbol symbol;
        private IrOperand operand;

        private PatternLocal(LocalSymbol symbol, IrOperand operand) {
            this.symbol = symbol;
            this.operand = operand;
        }
    }

    private enum AllocationState {
        ACTIVE,
        UNCERTAIN,
        ESCAPED,
        FREED,
        MAYBE_FREED;

        private boolean mayBeFreed() {
            return this == FREED || this == MAYBE_FREED;
        }
    }

    private enum AllocationOrigin {
        LOCAL_NEW,
        FRESH_CALL,
        OWNED_FIELD
    }

    private static final class AllocationInfo {
        private final int creationControlFlowDepth;
        private final AllocationOrigin origin;
        private final String ownedFieldName;
        private IrType constructedType;
        private boolean detached;
        private boolean present = true;
        private AllocationState state = AllocationState.ACTIVE;
        private String blockingReason = "compiler could not prove allocation identity";

        private AllocationInfo(int creationControlFlowDepth) {
            this(creationControlFlowDepth, AllocationOrigin.LOCAL_NEW, null);
        }

        private AllocationInfo(int creationControlFlowDepth, AllocationOrigin origin,
                               String ownedFieldName) {
            this.creationControlFlowDepth = creationControlFlowDepth;
            this.origin = origin;
            this.ownedFieldName = ownedFieldName;
        }

        private static AllocationInfo borrowedField(int controlFlowDepth, String fieldName) {
            return new AllocationInfo(controlFlowDepth, AllocationOrigin.OWNED_FIELD, fieldName);
        }

        private static AllocationInfo freshCall(int controlFlowDepth) {
            return new AllocationInfo(controlFlowDepth, AllocationOrigin.FRESH_CALL, null);
        }

        private void makeUncertain(String reason) {
            if (origin == AllocationOrigin.OWNED_FIELD) {
                return;
            }
            blockReclamation(reason);
        }

        private void blockReclamation(String reason) {
            if (state == AllocationState.ACTIVE) {
                state = AllocationState.UNCERTAIN;
                blockingReason = reason;
            }
        }

        private void escape(String reason) {
            if (!state.mayBeFreed()) {
                state = AllocationState.ESCAPED;
                blockingReason = reason;
            }
        }
    }

    private record AllocationStateSnapshot(AllocationState state, String blockingReason,
                                           boolean detached) {
    }

    private record OwnershipSnapshot(
            Map<AllocationInfo, AllocationStateSnapshot> states,
            Map<ArraySlot, AllocationInfo> knownArraySlots,
            Map<String, AllocationInfo> borrowedOwnedFields,
            Map<AllocationInfo, Set<AllocationInfo>> constructorBorrows,
            Map<AllocationInfo, AllocationInfo> poolOwners,
            Set<AllocationInfo> exposedContainerContents,
            Set<AllocationInfo> unfreedLive) {
        private OwnershipSnapshot {
            states = Map.copyOf(states);
            knownArraySlots = Map.copyOf(knownArraySlots);
            borrowedOwnedFields = Map.copyOf(borrowedOwnedFields);
            constructorBorrows = Map.copyOf(constructorBorrows);
            poolOwners = Map.copyOf(poolOwners);
            exposedContainerContents = Set.copyOf(exposedContainerContents);
            unfreedLive = Set.copyOf(unfreedLive);
        }
    }

    private record FieldTarget(IrOperand receiver, FieldSymbol field) {
    }

    private record ArraySlot(AllocationInfo container, int index) {
    }

    private record SuperTarget(TypeSymbol symbol, IrType type, IrOperand receiver) {
    }

    private record ClassFieldResolution(boolean classQualifier, TypeSymbol qualifier,
                                        FieldSymbol field) {
    }

    private record ArrayTarget(IrOperand array, IrOperand index) {
    }

    private record LValue(IrType type, java.util.function.Supplier<IrOperand> read,
                          java.util.function.BiConsumer<IrOperand, SourceSpan> write,
                          String description) {
    }

    private record BranchFlow(boolean reachable, MutableBlock block,
                              LinkedHashMap<LocalSymbol, IrOperand> environment,
                              OwnershipSnapshot ownership) {
    }

    private record Reclamation(AllocationInfo allocation, SourceSpan span) {
    }

    private record ExceptionEdge(MutableBlock block,
                                 LinkedHashMap<LocalSymbol, IrOperand> environment,
                                 OwnershipSnapshot ownership) {
    }

    private record CatchAlternative(String typeName, int typeId, IrType type,
                                    SourceSpan span, boolean valid) {
    }

    private record CatchTarget(List<CatchAlternative> alternatives, IrType bindingType) {
        private CatchTarget {
            alternatives = List.copyOf(alternatives);
        }
    }

    private record FinallyContext(Block body, List<ExceptionRegion> outerExceptionRegions,
                                  List<FinallyContext> outerFinallyContexts) {
        private FinallyContext {
            outerExceptionRegions = List.copyOf(outerExceptionRegions);
            outerFinallyContexts = List.copyOf(outerFinallyContexts);
        }
    }

    private static final class ExceptionRegion {
        private final MutableBlock landingPad;
        private final List<ExceptionEdge> edges = new ArrayList<>();

        private ExceptionRegion(MutableBlock landingPad) {
            this.landingPad = landingPad;
        }

        private void addEdge(ExceptionEdge edge) {
            edges.add(edge);
        }
    }

    private static class BreakContext {
        protected final String breakTarget;
        protected final List<FinallyContext> targetFinallyContexts;
        protected final List<BranchFlow> breakFlows = new ArrayList<>();

        private BreakContext(String breakTarget,
                             List<FinallyContext> targetFinallyContexts) {
            this.breakTarget = breakTarget;
            this.targetFinallyContexts = List.copyOf(targetFinallyContexts);
        }
    }

    private record LabeledContext(String label, BreakContext breakContext,
                                  LoopContext continueContext) {
    }

    private static final class LoopContext extends BreakContext {
        private final String continueTarget;
        private final List<BranchFlow> continueFlows = new ArrayList<>();

        private LoopContext(String breakTarget, String continueTarget,
                            List<FinallyContext> targetFinallyContexts) {
            super(breakTarget, targetFinallyContexts);
            this.continueTarget = continueTarget;
        }
    }

    private static final class MutablePhi {
        private final IrValueReference result;
        private final List<IrPhiIncoming> incoming;
        private final SourceSpan span;

        private MutablePhi(IrValueReference result, List<IrPhiIncoming> incoming, SourceSpan span) {
            this.result = result;
            this.incoming = new ArrayList<>(incoming);
            this.span = span;
        }

        private void addIncoming(IrPhiIncoming value) {
            incoming.add(value);
        }

        private IrPhiInstruction freeze() {
            return new IrPhiInstruction(result, incoming, span);
        }
    }

    private static final class MutableBlock {
        private final String label;
        private final SourceSpan span;
        private final List<MutablePhi> phis = new ArrayList<>();
        private final List<IrInstruction> instructions = new ArrayList<>();
        private IrTerminator terminator;

        private MutableBlock(String label, SourceSpan span) {
            this.label = label;
            this.span = span;
        }

        private void addPhi(MutablePhi phi) {
            phis.add(phi);
        }

        private void addInstruction(IrInstruction instruction) {
            instructions.add(instruction);
        }

        private void terminate(IrTerminator value) {
            if (terminator != null) {
                throw new IllegalStateException("basic block '" + label + "' already has a terminator");
            }
            terminator = value;
        }

        private IrBasicBlock freeze() {
            if (terminator == null) {
                throw new IllegalStateException("basic block '" + label + "' has no terminator");
            }
            List<IrInstruction> allInstructions = new ArrayList<>();
            phis.stream().map(MutablePhi::freeze).forEach(allInstructions::add);
            allInstructions.addAll(instructions);
            return new IrBasicBlock(label, allInstructions, terminator, span);
        }
    }
}
