// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source spans of the operations in each lowered function that may run program code:
 * calls of every kind, class initialization that has an initializer, destructors,
 * construction rollback and foreign calls. Typed IR makes the implicit ones explicit,
 * such as string conversion, enhanced-for iteration and a first static field access.
 * Owned-field analysis rejects any of them while a local alias of an attached field
 * allocation is live, because the code they run could replace and free the field
 * (D041, D281). It also records the frees that run no code.
 */
final class ReentrantOperations {
    /**
     * Exception types that lowering constructs for failed implicit runtime checks. Their
     * constructors only chain to Throwable() or Throwable(String), which store the new
     * exception's own fields and capture its trace, so constructing one cannot reach
     * another object. Any other constructor stays a possible reentry.
     */
    static final Set<String> RUNTIME_CHECK_EXCEPTIONS = Set.of(
            "ironwood.lang.ArithmeticException",
            "ironwood.lang.ArrayIndexOutOfBoundsException",
            "ironwood.lang.ClassCastException",
            "ironwood.lang.NegativeArraySizeException",
            "ironwood.lang.NullPointerException",
            "ironwood.lang.StringIndexOutOfBoundsException");

    /** No facts: before typed IR exists, only explicit source calls are known. */
    static final ReentrantOperations NONE = new ReentrantOperations(Map.of(), Map.of());

    private final Map<String, List<SourceSpan>> codeSpans;
    private final Map<String, Set<SourceSpan>> inertFreeSpans;

    private ReentrantOperations(Map<String, List<SourceSpan>> codeSpans,
                                Map<String, Set<SourceSpan>> inertFreeSpans) {
        this.codeSpans = codeSpans;
        this.inertFreeSpans = inertFreeSpans;
    }

    /**
     * Classifies the typed IR of {@code functions}. {@code rejectedFrees} names, per
     * function, the frees of locals that lowering rejected, which have no instruction:
     * each is inert when no class of its local's static type has a destructor, which
     * holds for any free of that local should a later lowering accept it (D296).
     */
    static ReentrantOperations of(List<IrFunction> functions, Map<String, TypeSymbol> types,
                                  ClosedWorldEffectAnalyzer effects,
                                  Map<String, Map<SourceSpan, IrType>> rejectedFrees) {
        Map<String, String> checkConstructors = new HashMap<>();
        for (String name : RUNTIME_CHECK_EXCEPTIONS) {
            TypeSymbol type = types.get(name);
            if (type != null) {
                type.constructors().forEach(constructor ->
                        checkConstructors.put(constructor.linkageName(), name));
            }
        }
        Map<String, List<SourceSpan>> codeSpans = new LinkedHashMap<>();
        Map<String, Set<SourceSpan>> inertFreeSpans = new LinkedHashMap<>();
        for (IrFunction function : functions) {
            Scan scan = new Scan(function, types, effects, checkConstructors);
            if (!scan.code.isEmpty()) codeSpans.put(function.linkageName(), List.copyOf(scan.code));
            rejectedFrees.getOrDefault(function.linkageName(), Map.of()).forEach((span, type) -> {
                if (!effects.freeMayRunDestructor(type)) scan.inertFrees.add(span);
            });
            if (!scan.inertFrees.isEmpty()) {
                inertFreeSpans.put(function.linkageName(), Set.copyOf(scan.inertFrees));
            }
        }
        return new ReentrantOperations(codeSpans, inertFreeSpans);
    }

    /** Whether code may run inside {@code region} of any of {@code functions}. */
    boolean runsCodeWithin(Collection<String> functions, SourceSpan region) {
        for (String function : functions) {
            for (SourceSpan span : codeSpans.getOrDefault(function, List.of())) {
                if (region.start().offset() <= span.start().offset()
                        && span.end().offset() <= region.end().offset()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether typed IR shows that the free statement at {@code statement} releases a value
     * without a destructor in every one of {@code functions} that lowered it.
     */
    boolean inertFree(Collection<String> functions, SourceSpan statement) {
        boolean seen = false;
        for (String function : functions) {
            if (inertFreeSpans.getOrDefault(function, Set.of()).contains(statement)) {
                seen = true;
            } else if (runsCodeWithin(List.of(function), statement)) {
                return false;
            }
        }
        return seen;
    }

    private static final class Scan {
        private final List<SourceSpan> code = new ArrayList<>();
        private final Set<SourceSpan> inertFrees = new HashSet<>();
        private final ClosedWorldEffectAnalyzer effects;
        private final Map<String, String> checkConstructors;
        private final Map<IrValueReference, String> allocated = new HashMap<>();
        private final Set<String> initialized = new HashSet<>();

        private Scan(IrFunction function, Map<String, TypeSymbol> types,
                     ClosedWorldEffectAnalyzer effects, Map<String, String> checkConstructors) {
            this.effects = effects;
            this.checkConstructors = checkConstructors;
            // Running code of a class means the class and its superclasses have started
            // initialization, so a barrier for one of them runs nothing.
            for (TypeSymbol type = types.get(function.ownerClass()); type != null;
                 type = type.superclass().orElse(null)) {
                initialized.add(type.name());
            }
            for (IrBasicBlock block : function.blocks()) {
                operations(block).forEach(instruction -> {
                    if (instruction instanceof IrAllocateInstruction allocation) {
                        allocated.put(allocation.result(), allocation.className());
                    }
                });
            }
            for (IrBasicBlock block : function.blocks()) {
                operations(block).forEach(this::classify);
            }
        }

        /** A block's instructions and, inside a try region, the call its terminator makes. */
        private static List<IrInstruction> operations(IrBasicBlock block) {
            if (!(block.terminator() instanceof IrInvokeTerminator invoke)) {
                return block.instructions();
            }
            List<IrInstruction> operations = new ArrayList<>(block.instructions());
            operations.add(invoke.call());
            return operations;
        }

        private void classify(IrInstruction instruction) {
            if (runsCode(instruction)) {
                code.add(instruction.sourceSpan());
            } else if (instruction instanceof IrFreeInstruction free) {
                inertFrees.add(free.sourceSpan());
            }
        }

        private boolean runsCode(IrInstruction instruction) {
            return switch (instruction) {
                case IrCallInstruction call -> !runtimeCheckConstruction(call);
                case IrEnsureTypeInitializedInstruction ensure ->
                        !initialized.contains(ensure.typeName()) && !effects.targets(ensure).isEmpty();
                case IrFreeInstruction free -> !effects.targets(free).isEmpty();
                case IrDestroyArrayElementsInstruction destroy -> !effects.targets(destroy).isEmpty();
                default -> !fixedNative(instruction);
            };
        }

        /** The construction of a runtime-check exception on its own fresh allocation. */
        private boolean runtimeCheckConstruction(IrCallInstruction call) {
            String type = checkConstructors.get(call.targetLinkageName());
            return type != null && !call.arguments().isEmpty()
                    && call.arguments().getFirst() instanceof IrValueReference receiver
                    && type.equals(allocated.get(receiver));
        }
    }

    /**
     * Operations audited to run only fixed runtime code: no dispatch, initializer,
     * destructor or callback. Anything else, including a new kind of operation, is
     * treated as running program code until it is classified here.
     */
    private static boolean fixedNative(IrInstruction instruction) {
        return switch (instruction) {
            case IrAllocateInstruction ignored -> true;
            case IrArrayAllocateInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction ignored -> true;
            case IrArrayTypeTestInstruction ignored -> true;
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrIdentityHashCodeInstruction ignored -> true;
            case IrObjectHashCodeInstruction ignored -> true;
            case IrObjectToStringInstruction ignored -> true;
            case IrMathUnaryInstruction ignored -> true;
            case IrMathBinaryInstruction ignored -> true;
            case IrCharacterInstruction ignored -> true;
            case IrFloatingBitsInstruction ignored -> true;
            case IrFloatingParseInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrAddSecondaryExceptionInstruction ignored -> true;
            case IrSecondaryExceptionAtInstruction ignored -> true;
            case IrSecondaryExceptionCountInstruction ignored -> true;
            case IrRawDeallocateInstruction ignored -> true;
            case IrReleaseOwnedToStringResultInstruction ignored -> true;
            case IrReleaseOwnedThrowableMessageInstruction ignored -> true;
            case IrThrowableDescriptionInstruction ignored -> true;
            case IrThrowableTraceInstruction trace ->
                    trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            // String and print operations take String or primitive operands only.
            case IrStringCharAtInstruction ignored -> true;
            case IrStringConcatInstruction ignored -> true;
            case IrStringEqualsInstruction ignored -> true;
            case IrStringEqualsIgnoreCaseInstruction ignored -> true;
            case IrStringCaseInstruction ignored -> true;
            case IrStringRepeatInstruction ignored -> true;
            case IrStringReplaceCharInstruction ignored -> true;
            case IrStringReplaceTextInstruction ignored -> true;
            case IrStringJoinInstruction ignored -> true;
            case IrStringCopyInstruction ignored -> true;
            case IrStringFromCharsInstruction ignored -> true;
            case IrStringFromUtf8Instruction ignored -> true;
            case IrStringFromCharRangeInstruction ignored -> true;
            case IrStringFromRangeInstruction ignored -> true;
            case IrStringFromIntegerInstruction ignored -> true;
            case IrStringFromCharacterInstruction ignored -> true;
            case IrStringHashCodeInstruction ignored -> true;
            case IrPrintStreamPrintlnInstruction ignored -> true;
            case IrPrintStreamWriteInstruction ignored -> true;
            case IrPrintStreamFlushInstruction ignored -> true;
            case IrPrintStreamCheckErrorInstruction ignored -> true;
            case IrSystemArrayCopyInstruction ignored -> true;
            case IrSystemClockInstruction ignored -> true;
            case IrSystemGetenvInstruction ignored -> true;
            case IrSystemPropertyInstruction ignored -> true;
            case IrSystemExitInstruction ignored -> true;
            // Native descriptor, process and socket operations call no Ironwood code.
            case IrFileInstruction ignored -> true;
            case IrProcessInstruction ignored -> true;
            case IrStreamInstruction ignored -> true;
            case IrTcpInstruction ignored -> true;
            case IrTlsInstruction ignored -> true;
            case IrByteViewInstruction ignored -> true;
            case IrBridgeBatchAppendInstruction ignored -> true;
            case IrBridgeResultStoreInstruction ignored -> true;
            case IrBridgeSlotStoreInstruction ignored -> true;
            case IrBridgeStringCopyInstruction ignored -> true;
            case IrBridgeArrayCopyInstruction ignored -> true;
            default -> false;
        };
    }
}
