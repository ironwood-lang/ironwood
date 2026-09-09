// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

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
import ironwood.compiler.ir.IrArrayType;
import ironwood.compiler.ir.IrArrayTypeTestInstruction;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrBinaryInstruction;
import ironwood.compiler.ir.IrBranch;
import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrClass;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrDispatchEntry;
import ironwood.compiler.ir.IrEnumConstant;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.ir.IrFloatingParseInstruction;
import ironwood.compiler.ir.IrExceptionLandingPadInstruction;
import ironwood.compiler.ir.IrExceptionCaughtInstruction;
import ironwood.compiler.ir.IrEnsureTypeInitializedInstruction;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.ir.IrDestroyArrayElementsInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrIdentityHashCodeInstruction;
import ironwood.compiler.ir.IrInstanceOfInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInterfaceCallInstruction;
import ironwood.compiler.ir.IrImmortalObject;
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
import ironwood.compiler.ir.IrPrintStreamPrintlnInstruction;
import ironwood.compiler.ir.IrPrintStreamCheckErrorInstruction;
import ironwood.compiler.ir.IrPrintStreamFlushInstruction;
import ironwood.compiler.ir.IrPrintStreamWriteInstruction;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrRawDeallocateInstruction;
import ironwood.compiler.ir.IrReleaseOwnedToStringResultInstruction;
import ironwood.compiler.ir.IrRollbackInstruction;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrSecondaryExceptionAtInstruction;
import ironwood.compiler.ir.IrSecondaryExceptionCountInstruction;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.ir.IrStringCharAtInstruction;
import ironwood.compiler.ir.IrStringConcatInstruction;
import ironwood.compiler.ir.IrStringCopyInstruction;
import ironwood.compiler.ir.IrStringConcatPart;
import ironwood.compiler.ir.IrStringEqualsInstruction;
import ironwood.compiler.ir.IrStringFromCharsInstruction;
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
import ironwood.compiler.ir.IrStringFromRangeInstruction;
import ironwood.compiler.ir.IrStringHashCodeInstruction;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrStaticFieldLoadInstruction;
import ironwood.compiler.ir.IrStaticFieldStoreInstruction;
import ironwood.compiler.ir.IrSystemArrayCopyInstruction;
import ironwood.compiler.ir.IrSystemClockInstruction;
import ironwood.compiler.ir.IrSystemExitInstruction;
import ironwood.compiler.ir.IrSystemGetenvInstruction;
import ironwood.compiler.ir.IrSystemPropertyInstruction;
import ironwood.compiler.ir.IrCharacterInstruction;
import ironwood.compiler.ir.IrFloatingBitsInstruction;
import ironwood.compiler.ir.IrMathBinaryInstruction;
import ironwood.compiler.ir.IrMathUnaryInstruction;
import ironwood.compiler.ir.IrSwitchTerminator;
import ironwood.compiler.ir.IrTerminator;
import ironwood.compiler.ir.IrThrowTerminator;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrTypeInitialization;
import ironwood.compiler.ir.IrUnaryInstruction;
import ironwood.compiler.ir.IrUnreachable;
import ironwood.compiler.ir.IrValueReference;
import ironwood.compiler.ir.IrVirtualCallInstruction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class LlvmEmitter {
    /*
     * Closed-world dispatch guards: a virtual or interface call whose slot has at
     * most this many instantiable receiver classes and distinct targets lowers to
     * descriptor comparisons plus direct calls, with the ordinary table dispatch
     * as the fallback. Direct calls let LLVM inline the small bodies that Java's
     * JIT would inline through its type profile.
     */
    private static final int MAX_GUARDED_RECEIVERS = 4;
    private static final int MAX_GUARDED_TARGETS = 3;
    private static final String THROW_HELPER_PREFIX = "ironwood.throw.";

    private TracePlan tracePlan;
    private Map<Integer, List<DispatchReceiver>> slotReceivers = Map.of();
    private final LinkedHashMap<String, ThrowHelper> throwHelpers = new LinkedHashMap<>();
    private FunctionLayout layout;
    private String currentBlockLabel;
    private int guardOrdinal;

    public String emit(IrProgram program) {
        tracePlan = new TracePlan(program);
        throwHelpers.clear();
        planDispatchReceivers(program);
        StringBuilder output = new StringBuilder();
        output.append("; ModuleID = '").append(escapeComment(program.moduleName())).append("'\n");
        output.append("source_filename = \"").append(escapeString(program.moduleName())).append("\"\n\n");
        output.append("%\"ironwood.typeinfo\" = type { i32, ptr, ptr, ptr, i32, ptr, ptr, i1, i1 }\n");
        output.append("%\"ironwood.array\" = type { ptr, i64, i64, i32, i32, [0 x i8] }\n");
        output.append("%\"ironwood.string\" = type { ptr, i32, i32, [0 x i16] }\n");
        output.append("%\"ironwood.string.concat.part\" = type { i32, i32, i64 }\n");
        for (IrClass irClass : program.classes()) {
            if (irClass.isClass()) {
                emitClass(output, irClass);
            }
        }
        output.append('\n');
        int typeCount = program.classes().stream().mapToInt(IrClass::typeId).max().orElse(-1) + 1;
        for (IrClass irClass : program.classes()) {
            if (irClass.isClass()) {
                emitTypeMetadata(output, irClass, typeCount,
                        program.dispatchSlots().size());
            }
        }
        for (IrArrayType arrayType : program.arrayTypes()) {
            emitArrayTypeMetadata(output, arrayType, typeCount,
                    program.dispatchSlots().size());
        }
        output.append('\n');
        for (IrStringConstant constant : program.stringConstants()) {
            emitStringConstant(output, constant);
        }
        if (!program.stringConstants().isEmpty()) {
            output.append('\n');
        }
        List<IrImmortalObject> immortalObjects = Stream.concat(
                        program.allocationFailure().stream(),
                        program.staticFields().stream()
                                .map(IrStaticField::initialValue)
                                .filter(IrImmortalObject.class::isInstance)
                                .map(IrImmortalObject.class::cast))
                .distinct()
                .toList();
        for (IrImmortalObject object : immortalObjects) {
            IrClass irClass = program.classes().stream()
                    .filter(candidate -> candidate.name().equals(object.storageType().referenceName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "missing class for immortal object " + object.symbol()));
            emitImmortalObject(output, object, irClass);
        }
        if (!immortalObjects.isEmpty()) {
            output.append('\n');
        }
        List<IrEnumConstant> enumConstants = program.staticFields().stream()
                .map(IrStaticField::initialValue)
                .filter(IrEnumConstant.class::isInstance)
                .map(IrEnumConstant.class::cast)
                .distinct()
                .toList();
        for (IrEnumConstant constant : enumConstants) {
            IrClass irClass = program.classes().stream()
                    .filter(candidate -> candidate.name().equals(
                            constant.storageType().referenceName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "missing class for enum constant " + constant.symbol()));
            emitEnumConstant(output, constant, irClass);
        }
        if (!enumConstants.isEmpty()) {
            output.append('\n');
        }
        for (IrStaticField field : program.staticFields()) {
            output.append(staticFieldName(field)).append(" = internal ")
                    .append(field.constantStorage() ? "constant " : "global ")
                    .append(llvmType(field.type())).append(' ')
                    .append(staticInitializer(field)).append('\n');
        }
        if (!program.staticFields().isEmpty()) {
            output.append('\n');
        }
        for (IrTypeInitialization initialization : program.typeInitializations()) {
            output.append(initializationStateName(initialization.typeName()))
                    .append(" = internal global i8 0\n")
                    .append(initializationFailureName(initialization.typeName()))
                    .append(" = internal global ptr null\n");
        }
        if (!program.typeInitializations().isEmpty()) {
            output.append('\n');
        }
        for (IrFunction function : program.functions()) {
            emitTraceMetadata(output, function);
        }
        if (!program.functions().isEmpty()) {
            output.append('\n');
        }
        emitThrowableTraceMetadata(output, program);
        for (IrThrowableTraceInstruction.Operation operation : IrThrowableTraceInstruction.Operation.values()) {
            output.append("declare ").append(llvmType(operation.returnType())).append(" @")
                    .append(operation.runtimeName()).append('(')
                    .append(operation.parameterTypes().stream().map(LlvmEmitter::llvmType).collect(Collectors.joining(", ")));
            if (operation == IrThrowableTraceInstruction.Operation.ARRAY) {
                output.append(", ptr, ptr");
            }
            output.append(")\n");
        }
        // Both allocators return fresh storage or raise the allocation failure;
        // they never return null, so LLVM may fold null checks on new objects.
        output.append("declare noalias nonnull ptr @ironwood_allocate(i64, ptr, ptr)\n");
        output.append("declare noalias nonnull ptr @ironwood_allocate_array(i32, i64, i32, ptr, ptr)\n");
        output.append("declare i64 @ironwood_allocation_count()\n");
        output.append("declare i64 @ironwood_live_allocation_count()\n");
        output.append("declare i32 @ironwood_identity_hash_code(ptr)\n");
        output.append("declare i32 @ironwood_object_hash_code(ptr)\n");
        output.append("declare ptr @ironwood_object_to_string(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_throwable_description(ptr, ptr, ptr, ptr)\n");
        output.append("declare void @ironwood_release_owned_throwable_message(ptr, ptr)\n");
        output.append("declare void @ironwood_release_owned_to_string_result(ptr, ptr)\n");
        output.append("declare i1 @ironwood_string_equals(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_string_hash_code(ptr)\n");
        output.append("declare ptr @ironwood_string_copy(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_chars(ptr, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_case(ptr, i1, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_repeat(ptr, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_replace_char(ptr, i16, i16, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_replace_text(ptr, ptr, ptr, ptr, ptr)\n");
        output.append("declare i1 @ironwood_string_equals_ignore_case(ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_join(ptr, ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_utf8(ptr, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_char_range(ptr, i32, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_range(ptr, i32, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_integer(i64, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_from_character(i16, ptr, ptr)\n");
        output.append("declare ptr @ironwood_string_concat(ptr, i32, ptr, ptr)\n");
        output.append("declare ptr @ironwood_process_arguments(i32, ptr, ptr, ptr)\n");
        output.append("declare void @ironwood_system_arraycopy(ptr, i32, ptr, i32, i32)\n");
        output.append("declare ptr @ironwood_system_getenv(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_system_get_property(ptr, ptr, ptr)\n");
        output.append("declare void @ironwood_system_exit(i32) noreturn\n");
        output.append("declare i64 @ironwood_current_time_millis()\n");
        output.append("declare i64 @ironwood_nano_time()\n");
        output.append("declare float @ironwood_parse_float(ptr)\n");
        output.append("declare double @ironwood_parse_double(ptr)\n");
        for (IrCharacterInstruction.Operation operation : IrCharacterInstruction.Operation.values()) {
            output.append("declare i32 @").append(operation.functionName()).append("(i32)\n");
        }
        for (IrStreamInstruction.Operation stream : IrStreamInstruction.Operation.values()) {
            output.append("declare ").append(llvmType(stream.resultType())).append(" @")
                    .append(stream.runtimeName()).append('(');
            output.append(stream.parameterTypes().stream().map(LlvmEmitter::llvmType)
                    .collect(java.util.stream.Collectors.joining(", ")));
            if (stream == IrStreamInstruction.Operation.OPEN) { output.append(", ptr"); }
            output.append(")\n");
        }
        output.append("declare i32 @ironwood_file_same(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_file_read_all_bytes(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_file_read_string(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_write_bytes(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_write_string(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_write_chars(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_delete(ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_create_directories(ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_copy(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_move(ptr, ptr, ptr)\n");
        output.append("declare i64 @ironwood_directory_open(ptr, ptr)\n");
        output.append("declare i32 @ironwood_directory_has_next(i64)\n");
        output.append("declare ptr @ironwood_directory_next(i64, ptr, ptr)\n");
        output.append("declare i32 @ironwood_directory_close(i64)\n");
        output.append("declare ptr @ironwood_file_read_attributes(ptr, i1, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_resolve_sibling(ptr, ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_absolute(ptr, ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_kind(ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_kind_nofollow(ptr, ptr)\n");
        output.append("declare i64 @ironwood_file_size(ptr, ptr)\n");
        output.append("declare i32 @ironwood_file_last_error()\n");
        output.append("declare ptr @ironwood_path_current_directory(ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_normalize_syntax(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_normalize(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_file_name(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_parent(ptr, ptr, ptr)\n");
        output.append("declare ptr @ironwood_path_resolve(ptr, ptr, ptr, ptr)\n");
        output.append("declare void @ironwood_deallocate(ptr)\n");
        output.append("declare void @ironwood_destructor_failed() noreturn\n");
        output.append("declare void @ironwood_stdout_println(ptr)\n");
        output.append("declare void @ironwood_print_stream_write(ptr, i32, i64, i1)\n");
        output.append("declare void @ironwood_print_stream_flush(ptr)\n");
        output.append("declare i1 @ironwood_print_stream_check_error(ptr)\n");
        output.append("declare double @llvm.sin.f64(double)\n");
        output.append("declare double @llvm.cos.f64(double)\n");
        output.append("declare double @tan(double)\n");
        output.append("declare double @llvm.exp.f64(double)\n");
        output.append("declare double @llvm.log.f64(double)\n");
        output.append("declare double @llvm.log10.f64(double)\n");
        output.append("declare double @llvm.sqrt.f64(double)\n");
        output.append("declare double @cbrt(double)\n");
        output.append("declare double @llvm.rint.f64(double)\n");
        output.append("declare double @atan2(double, double)\n");
        output.append("declare double @llvm.pow.f64(double, double)\n");
        output.append("declare double @hypot(double, double)\n");
        output.append("declare void @ironwood_throw(ptr) noreturn cold\n");
        output.append("declare ptr @ironwood_exception_take(ptr)\n");
        output.append("declare void @ironwood_exception_caught(ptr)\n");
        output.append("declare void @ironwood_exception_add_secondary(ptr, ptr)\n");
        output.append("declare i32 @ironwood_exception_secondary_count(ptr)\n");
        output.append("declare ptr @ironwood_exception_secondary_at(ptr, i32)\n");
        output.append("declare void @llvm.pseudoprobe(i64, i64, i32, i64)\n");
        output.append("declare i1 @llvm.expect.i1(i1, i1)\n");
        output.append("declare void @ironwood_trace_register_current(ptr, i32)\n");
        output.append("declare void @ironwood_trace_register(ptr, i32, ptr, i32)\n");
        output.append("declare void @ironwood_uncaught_exception(ptr, i64) noreturn\n");
        output.append("declare i32 @__gxx_personality_v0(...)\n\n");
        emitInstanceOfHelper(output);
        output.append('\n');
        emitExactArrayTypeHelper(output);
        output.append('\n');
        emitDestroyHelper(output);
        emitDestroyArrayElementsHelper(output);
        output.append('\n');
        emitRollbackHelper(output);
        output.append('\n');
        for (IrTypeInitialization initialization : program.typeInitializations()) {
            emitTypeInitializer(output, initialization);
            output.append('\n');
        }
        for (int index = 0; index < program.functions().size(); index++) {
            emitFunction(output, program.functions().get(index));
            if (index + 1 < program.functions().size() || program.entryPoint().isPresent()) {
                output.append('\n');
            }
        }
        program.entryPoint().ifPresent(entryPoint -> emitNativeEntryPoint(output, entryPoint, program));
        emitThrowHelpers(output);
        tracePlan.emitDebugMetadata(output);
        return output.toString();
    }

    private void emitClass(StringBuilder output, IrClass irClass) {
        output.append(classType(irClass.name())).append(" = type { ptr");
        if (!irClass.fields().isEmpty()) {
            output.append(", ");
        }
        output.append(irClass.fields().stream()
                .map(field -> llvmType(field.type()))
                .collect(Collectors.joining(", ")));
        output.append(" }\n");
    }

    private void emitTypeMetadata(StringBuilder output, IrClass irClass,
                                  int typeCount, int dispatchSlotCount) {
        emitTypeMetadata(output, irClass.name(), irClass.typeId(), irClass.typeMembership(),
                irClass.dispatchEntries(), irClass.destructorChain(), typeCount,
                irClass.constructorRollback(), irClass.toStringReturnsOwnedFresh(),
                irClass.localizedMessageReturnsOwnedFresh(),
                dispatchSlotCount, false);
    }

    private void emitArrayTypeMetadata(StringBuilder output, IrArrayType arrayType,
                                       int typeCount, int dispatchSlotCount) {
        emitTypeMetadata(output, arrayType.name(), arrayType.typeId(), arrayType.typeMembership(),
                arrayType.dispatchEntries(), java.util.Optional.empty(), typeCount,
                java.util.Optional.empty(), arrayType.toStringReturnsOwnedFresh(),
                false,
                dispatchSlotCount, true);
    }

    private void emitTypeMetadata(StringBuilder output, String name, int typeId,
                                  java.util.List<Integer> typeMembership,
                                  java.util.List<ironwood.compiler.ir.IrDispatchEntry> dispatchEntries,
                                  java.util.Optional<String> destructorChain,
                                  int typeCount,
                                  java.util.Optional<String> constructorRollback,
                                  boolean toStringReturnsOwnedFresh,
                                  boolean localizedMessageReturnsOwnedFresh,
                                  int dispatchSlotCount, boolean arrayType) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        output.append(typeNameConstant(name)).append(" = private unnamed_addr constant [")
                .append(nameBytes.length + 1).append(" x i8] c\"")
                .append(escapeBytes(nameBytes)).append("\\00\"\n");
        Map<Integer, String> targets = new HashMap<>();
        dispatchEntries.forEach(entry -> targets.put(entry.slot().index(),
                entry.targetLinkageName()));
        output.append(dispatchTableName(name)).append(" = private constant [")
                .append(dispatchSlotCount).append(" x ptr] ");
        if (dispatchSlotCount == 0) {
            output.append("zeroinitializer\n");
        } else {
            output.append('[');
            for (int index = 0; index < dispatchSlotCount; index++) {
                if (index > 0) {
                    output.append(", ");
                }
                String target = targets.get(index);
                output.append("ptr ").append(target == null ? "null" : functionName(target));
            }
            output.append("]\n");
        }

        output.append(membershipTableName(name)).append(" = private constant [")
                .append(typeCount).append(" x i8] [");
        for (int index = 0; index < typeCount; index++) {
            if (index > 0) {
                output.append(", ");
            }
            output.append("i8 ").append(typeMembership.contains(index) ? '1' : '0');
        }
        output.append("]\n");
        output.append(typeInfoName(name)).append(" = private constant %\"ironwood.typeinfo\" { i32 ")
                .append(typeId).append(", ptr ").append(dispatchTableName(name))
                .append(", ptr ").append(membershipTableName(name)).append(", ptr ")
                .append(typeNameConstant(name)).append(", i32 ")
                .append(arrayType ? 1 : 0).append(", ptr ")
                .append(destructorChain.map(this::functionName).orElse("null"))
                .append(", ptr ")
                .append(constructorRollback.map(this::functionName).orElse("null"))
                .append(", i1 ").append(toStringReturnsOwnedFresh ? "true" : "false")
                .append(", i1 ").append(localizedMessageReturnsOwnedFresh ? "true" : "false")
                .append(" }\n");
    }

    private void emitInstanceOfHelper(StringBuilder output) {
        output.append("define internal i1 @\"ironwood.is_instance\"(ptr %object, i32 %target) {\n")
                .append("entry:\n")
                .append("  %is.null = icmp eq ptr %object, null\n")
                .append("  br i1 %is.null, label %null, label %non.null\n")
                .append("null:\n")
                .append("  ret i1 false\n")
                .append("non.null:\n")
                .append("  %typeinfo = load ptr, ptr %object\n")
                .append("  %membership.address = getelementptr inbounds %\"ironwood.typeinfo\", ptr %typeinfo, i32 0, i32 2\n")
                .append("  %membership = load ptr, ptr %membership.address\n")
                .append("  %membership.entry = getelementptr inbounds i8, ptr %membership, i32 %target\n")
                .append("  %membership.value = load i8, ptr %membership.entry\n")
                .append("  %matches = icmp ne i8 %membership.value, 0\n")
                .append("  ret i1 %matches\n")
                .append("}\n");
    }

    private void emitDestroyHelper(StringBuilder output) {
        output.append("define internal void @\"ironwood.destroy\"(ptr %object) ")
                .append("personality ptr @__gxx_personality_v0 {\n")
                .append("entry:\n")
                .append("  %is.null = icmp eq ptr %object, null\n")
                .append("  br i1 %is.null, label %done, label %non.null\n")
                .append("non.null:\n")
                .append("  %typeinfo = load ptr, ptr %object\n")
                .append("  %destructor.address = getelementptr inbounds %\"ironwood.typeinfo\", ptr %typeinfo, i32 0, i32 5\n")
                .append("  %destructor = load ptr, ptr %destructor.address\n")
                .append("  %has.destructor = icmp ne ptr %destructor, null\n")
                .append("  br i1 %has.destructor, label %destroy, label %deallocate\n")
                .append("destroy:\n")
                .append("  invoke void %destructor(ptr %object) to label %deallocate unwind label %failed\n")
                .append("deallocate:\n")
                .append("  call void @ironwood_deallocate(ptr %object)\n")
                .append("  br label %done\n")
                .append("done:\n")
                .append("  ret void\n")
                .append("failed:\n")
                .append("  %failure = landingpad { ptr, i32 } catch ptr null\n")
                .append("  call void @ironwood_destructor_failed()\n")
                .append("  unreachable\n")
                .append("}\n");
    }

    private void emitDestroyArrayElementsHelper(StringBuilder output) {
        // Ownership and uniqueness are checked in typed analysis. This loop only
        // consumes those proven elements; it performs no identity bookkeeping.
        output.append("""
                define internal void @"ironwood.destroy_array_elements"(ptr %array) {
                entry:
                  %is.null = icmp eq ptr %array, null
                  br i1 %is.null, label %done, label %start
                start:
                  %length.ptr = getelementptr inbounds %"ironwood.array", ptr %array, i32 0, i32 1
                  %length = load i64, ptr %length.ptr
                  %data = getelementptr inbounds %"ironwood.array", ptr %array, i32 0, i32 5
                  br label %loop
                loop:
                  %index = phi i64 [ 0, %start ], [ %next, %body ]
                  %more = icmp ult i64 %index, %length
                  br i1 %more, label %body, label %done
                body:
                  %slot = getelementptr inbounds ptr, ptr %data, i64 %index
                  %value = load ptr, ptr %slot
                  store ptr null, ptr %slot
                  call void @"ironwood.destroy"(ptr %value)
                  %next = add i64 %index, 1
                  br label %loop
                done:
                  ret void
                }
                """);
    }

    private void emitRollbackHelper(StringBuilder output) {
        output.append("define internal void @\"ironwood.rollback\"(ptr %object) {\n")
                .append("entry:\n")
                .append("  %is.null = icmp eq ptr %object, null\n")
                .append("  br i1 %is.null, label %done, label %non.null\n")
                .append("non.null:\n")
                .append("  %typeinfo = load ptr, ptr %object\n")
                .append("  %rollback.address = getelementptr inbounds %\"ironwood.typeinfo\", ptr %typeinfo, i32 0, i32 6\n")
                .append("  %rollback = load ptr, ptr %rollback.address\n")
                .append("  %has.rollback = icmp ne ptr %rollback, null\n")
                .append("  br i1 %has.rollback, label %structured, label %raw\n")
                .append("structured:\n")
                .append("  call void %rollback(ptr %object)\n")
                .append("  br label %done\n")
                .append("raw:\n")
                .append("  call void @ironwood_deallocate(ptr %object)\n")
                .append("  br label %done\n")
                .append("done:\n")
                .append("  ret void\n")
                .append("}\n");
    }

    private void emitExactArrayTypeHelper(StringBuilder output) {
        output.append("define internal i1 @\"ironwood.is_exact_array\"(ptr %object, ptr %target) {\n")
                .append("entry:\n")
                .append("  %is.null = icmp eq ptr %object, null\n")
                .append("  br i1 %is.null, label %null, label %non.null\n")
                .append("null:\n")
                .append("  ret i1 false\n")
                .append("non.null:\n")
                .append("  %typeinfo = load ptr, ptr %object\n")
                .append("  %matches = icmp eq ptr %typeinfo, %target\n")
                .append("  ret i1 %matches\n")
                .append("}\n");
    }

    private void emitStringConstant(StringBuilder output, IrStringConstant constant) {
        int storageLength = constant.value().length();
        output.append(stringLiteralName(constant.id())).append(" = private constant { ptr, i32, i32, [")
                .append(storageLength).append(" x i16] } { ptr ")
                .append(typeInfoName("ironwood.lang.String"))
                .append(", i32 ").append(constant.utf16Length())
                .append(", i32 ").append(constant.utf8Length())
                .append(", [").append(storageLength).append(" x i16] ");
        if (storageLength == 0) {
            output.append("zeroinitializer");
        } else {
            output.append('[');
            for (int index = 0; index < storageLength; index++) {
                if (index > 0) {
                    output.append(", ");
                }
                output.append("i16 ").append((int) constant.value().charAt(index));
            }
            output.append(']');
        }
        output.append(" }\n");
    }

    private void emitImmortalObject(StringBuilder output, IrImmortalObject object,
                                    IrClass irClass) {
        output.append(immortalObjectName(object)).append(irClass.name().equals("ironwood.lang.OutOfMemoryError")
                        ? " = private global " : " = private constant ")
                .append(classType(irClass.name())).append(" { ptr ")
                .append(typeInfoName(irClass.name()));
        for (var field : irClass.fields()) {
            output.append(", ").append(llvmType(field.type())).append(' ')
                    .append(object.fieldValues().containsKey(field.name())
                            ? operand(object.fieldValues().get(field.name()))
                            : zeroInitializer(field.type()));
        }
        output.append(" }\n");
    }

    private void emitEnumConstant(StringBuilder output, IrEnumConstant constant,
                                  IrClass irClass) {
        output.append(enumConstantName(constant)).append(" = private global ")
                .append(classType(irClass.name())).append(" { ptr ")
                .append(typeInfoName(irClass.name()));
        for (var field : irClass.fields()) {
            output.append(", ").append(llvmType(field.type())).append(' ');
            if (field.name().equals("<enum:name>")) {
                output.append(operand(constant.nameValue()));
            } else if (field.name().equals("<enum:ordinal>")) {
                output.append(constant.ordinal());
            } else {
                output.append(zeroInitializer(field.type()));
            }
        }
        output.append(" }\n");
    }

    private void emitFunction(StringBuilder output, IrFunction function) {
        output.append("define internal ").append(llvmType(function.returnType())).append(' ')
                .append(functionName(function.linkageName())).append('(');
        output.append(function.parameters().stream()
                .map(this::parameter)
                .collect(Collectors.joining(", ")));
        output.append(") \"disable-tail-calls\"=\"true\" personality ptr @__gxx_personality_v0 !dbg !")
                .append(tracePlan.function(function).subprogramMetadata()).append(" {\n");
        ScratchNames scratchNames = new ScratchNames();
        layout = planFunctionLayout(function);
        for (int blockIndex = 0; blockIndex < function.blocks().size(); blockIndex++) {
            IrBasicBlock block = function.blocks().get(blockIndex);
            if (layout.absorbed().contains(block.label())) {
                continue;
            }
            output.append(block.label()).append(":\n");
            if (blockIndex == 0) {
                output.append("  ");
                emitTraceProbe(output, tracePlan.function(function).entry());
                output.append('\n');
            }
            OutlinedThrow outlined = layout.outlined().get(block.label());
            if (outlined != null) {
                emitOutlinedThrow(output, function, outlined);
                continue;
            }
            currentBlockLabel = block.label();
            guardOrdinal = 0;
            for (IrInstruction instruction : block.instructions()) {
                output.append("  ");
                emitInstruction(output, function, instruction, scratchNames);
                output.append('\n');
            }
            output.append("  ");
            emitTerminator(output, function, block.terminator(), scratchNames);
            output.append('\n');
        }
        output.append("}\n");
        layout = null;
    }

    private void emitNativeEntryPoint(StringBuilder output, IrFunction entryPoint,
                                      IrProgram program) {
        if (entryPoint.parameters().size() != 1
                || !entryPoint.parameters().getFirst().value().type().equals(
                        IrType.array(IrType.reference("ironwood.lang.String")))) {
            throw new IllegalArgumentException(
                    "native entry point must accept exactly one ironwood.lang.String[] parameter");
        }
        TraceScope traceScope = tracePlan.nativeEntryScope();
        output.append("define i32 @main(i32 %argc, ptr %argv) \"disable-tail-calls\"=\"true\" ")
                .append("personality ptr @__gxx_personality_v0 !dbg !")
                .append(traceScope.subprogramMetadata()).append(" {\n")
                .append("entry:\n")
                .append("  call void @ironwood_trace_register_current(ptr @ironwood_trace_sites, i32 ")
                .append(tracePlan.sites().size()).append(")\n")
                .append("  %arguments = call ptr @ironwood_process_arguments(i32 %argc, ptr %argv, ptr ")
                .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                .append(typeInfoName("ironwood.lang.String[]")).append(")\n")
                .append("  invoke void ")
                .append(typeInitializerName(entryPoint.ownerClass()))
                .append("() to label %initialized unwind label %uncaught, !dbg !")
                .append(traceScope.locationMetadata()).append("\n")
                .append("initialized:\n");
        if (entryPoint.returnType().equals(IrType.I32)) {
            output.append("  %result = invoke i32 ")
                    .append(functionName(entryPoint.linkageName()))
                    .append("(ptr %arguments) to label %completed unwind label %uncaught, !dbg !")
                    .append(traceScope.locationMetadata()).append("\n")
                    .append("completed:\n")
                    .append("  ret i32 %result\n");
        } else if (entryPoint.returnType().equals(IrType.VOID)) {
            output.append("  invoke void ")
                    .append(functionName(entryPoint.linkageName()))
                    .append("(ptr %arguments) to label %completed unwind label %uncaught, !dbg !")
                    .append(traceScope.locationMetadata()).append("\n")
                    .append("completed:\n")
                    .append("  ret i32 0\n");
        } else {
            throw new IllegalArgumentException("native entry point must return int or void");
        }
        output.append("uncaught:\n")
                .append("  %landing = landingpad { ptr, i32 } catch ptr null\n")
                .append("  %exception = extractvalue { ptr, i32 } %landing, 0\n")
                .append("  %object = call ptr @ironwood_exception_take(ptr %exception)\n");
        IrField messageField = throwableMessageField(program);
        if (messageField == null) {
            output.append("  call void @ironwood_uncaught_exception(ptr %object, i64 0)\n");
        } else {
            output.append("  %message.offset.pointer = getelementptr ")
                    .append(classType("ironwood.lang.Throwable"))
                    .append(", ptr null, i32 0, i32 ")
                    .append(messageField.layoutIndex() + 1).append("\n")
                    .append("  %message.offset = ptrtoint ptr %message.offset.pointer to i64\n")
                    .append("  call void @ironwood_uncaught_exception(ptr %object, i64 %message.offset)\n");
        }
        output
                .append("  unreachable\n")
                .append("}\n");
    }

    private IrField throwableMessageField(IrProgram program) {
        return program.classes().stream()
                .filter(irClass -> irClass.name().equals("ironwood.lang.Throwable"))
                .flatMap(irClass -> irClass.fields().stream())
                .filter(field -> field.ownerClass().equals("ironwood.lang.Throwable")
                        && field.name().equals("message"))
                .findFirst().orElse(null);
    }

    private String parameter(IrParameter parameter) {
        String type = llvmType(parameter.value().type());
        if (parameter.name().equals("this") && parameter.value().type().isReference()) {
            // Every receiver is null-checked before dispatch and every allocation
            // is non-null, so the receiver parameter never carries null.
            return type + " nonnull noundef " + operand(parameter.value());
        }
        return type + " " + operand(parameter.value());
    }

    private void emitInstruction(StringBuilder output, IrFunction function, IrInstruction instruction,
                                 ScratchNames scratchNames) {
        if (writesTraceLine(instruction)) {
            emitTraceProbe(output, tracePlan.site(function, instruction));
            output.append("\n  ");
        }
        if (instruction instanceof IrEnsureTypeInitializedInstruction ensure) {
            output.append("call void ").append(typeInitializerName(ensure.typeName()))
                    .append("(), !dbg !")
                    .append(tracePlan.site(function, instruction).callLocationMetadata());
            return;
        }
        if (instruction instanceof IrAllocateInstruction allocate) {
            String sizePointer = scratchNames.next("alloc.size.ptr");
            String size = scratchNames.next("alloc.size");
            output.append(sizePointer).append(" = getelementptr ")
                    .append(classType(allocate.className())).append(", ptr null, i32 1\n  ")
                    .append(size).append(" = ptrtoint ptr ").append(sizePointer).append(" to i64\n  ")
                    .append(operand(allocate.result())).append(" = call ptr @ironwood_allocate(i64 ")
                    .append(size).append(", ptr ").append(typeInfoName(allocate.className()))
                    .append(", ptr ").append(allocationFailureName()).append("), !dbg !")
                    .append(tracePlan.site(function, instruction).callLocationMetadata());
            return;
        }
        if (instruction instanceof IrArrayAllocateInstruction allocate) {
            String sizePointer = scratchNames.next("array.element.size.ptr");
            String size = scratchNames.next("array.element.size");
            output.append(sizePointer).append(" = getelementptr ")
                    .append(llvmType(allocate.elementType())).append(", ptr null, i32 1\n  ")
                    .append(size).append(" = ptrtoint ptr ").append(sizePointer).append(" to i64\n  ")
                    .append(operand(allocate.result())).append(" = call ptr @ironwood_allocate_array(i32 ")
                    .append(operand(allocate.length())).append(", i64 ").append(size)
                    .append(", i32 ").append(arrayElementKind(allocate.elementType()))
                    .append(", ptr ")
                    .append(typeInfoName(allocate.result().type().erasure().displayName()))
                    .append(", ptr ").append(allocationFailureName()).append("), !dbg !")
                    .append(tracePlan.site(function, instruction).callLocationMetadata());
            return;
        }
        if (instruction instanceof IrAllocationCountInstruction count) {
            output.append(operand(count.result()))
                    .append(" = call i64 @ironwood_allocation_count()");
            return;
        }
        if (instruction instanceof IrLiveAllocationCountInstruction count) {
            output.append(operand(count.result()))
                    .append(" = call i64 @ironwood_live_allocation_count()");
            return;
        }
        if (instruction instanceof IrArrayLengthInstruction length) {
            String lengthPointer = scratchNames.next("array.length.ptr");
            String wideLength = scratchNames.next("array.length.wide");
            output.append(lengthPointer).append(" = getelementptr inbounds %\"ironwood.array\", ptr ")
                    .append(operand(length.array())).append(", i32 0, i32 1\n  ")
                    .append(wideLength).append(" = load i64, ptr ").append(lengthPointer).append("\n  ")
                    .append(operand(length.result())).append(" = trunc i64 ").append(wideLength)
                    .append(" to i32");
            return;
        }
        if (instruction instanceof IrArrayBoundsCheckInstruction check) {
            String lengthPointer = scratchNames.next("array.bounds.length.ptr");
            String length = scratchNames.next("array.bounds.length");
            String nonnegative = scratchNames.next("array.index.nonnegative");
            String wideIndex = scratchNames.next("array.index.wide");
            String belowLength = scratchNames.next("array.index.below.length");
            output.append(lengthPointer).append(" = getelementptr inbounds %\"ironwood.array\", ptr ")
                    .append(operand(check.array())).append(", i32 0, i32 1\n  ")
                    .append(length).append(" = load i64, ptr ").append(lengthPointer).append("\n  ")
                    .append(nonnegative).append(" = icmp sge i32 ")
                    .append(operand(check.index())).append(", 0\n  ")
                    .append(wideIndex).append(" = zext i32 ").append(operand(check.index()))
                    .append(" to i64\n  ")
                    .append(belowLength).append(" = icmp ult i64 ").append(wideIndex)
                    .append(", ").append(length).append("\n  ")
                    .append(operand(check.result())).append(" = and i1 ")
                    .append(nonnegative).append(", ").append(belowLength);
            return;
        }
        if (instruction instanceof IrArrayLengthCheckInstruction check) {
            output.append(operand(check.result())).append(" = icmp sge i32 ")
                    .append(operand(check.length())).append(", 0");
            return;
        }
        if (instruction instanceof IrArrayLoadInstruction load) {
            String data = scratchNames.next("array.data");
            String element = scratchNames.next("array.element.ptr");
            output.append(data).append(" = getelementptr inbounds %\"ironwood.array\", ptr ")
                    .append(operand(load.array())).append(", i32 0, i32 5\n  ")
                    .append(element).append(" = getelementptr inbounds ")
                    .append(llvmType(load.result().type())).append(", ptr ").append(data)
                    .append(", i32 ").append(operand(load.index())).append("\n  ")
                    .append(operand(load.result())).append(" = load ")
                    .append(llvmType(load.result().type())).append(", ptr ").append(element);
            return;
        }
        if (instruction instanceof IrArrayStoreInstruction store) {
            String data = scratchNames.next("array.data");
            String element = scratchNames.next("array.element.ptr");
            output.append(data).append(" = getelementptr inbounds %\"ironwood.array\", ptr ")
                    .append(operand(store.array())).append(", i32 0, i32 5\n  ")
                    .append(element).append(" = getelementptr inbounds ")
                    .append(llvmType(store.value().type())).append(", ptr ").append(data)
                    .append(", i32 ").append(operand(store.index())).append("\n  store ")
                    .append(llvmType(store.value().type())).append(' ')
                    .append(operand(store.value())).append(", ptr ").append(element);
            return;
        }
        if (instruction instanceof IrSystemArrayCopyInstruction copy) {
            output.append("call void @ironwood_system_arraycopy(ptr ")
                    .append(operand(copy.source())).append(", i32 ")
                    .append(operand(copy.sourcePosition())).append(", ptr ")
                    .append(operand(copy.destination())).append(", i32 ")
                    .append(operand(copy.destinationPosition())).append(", i32 ")
                    .append(operand(copy.length())).append(')');
            return;
        }
        if (instruction instanceof IrStreamInstruction stream) {
            emitStreamInstruction(output, stream, "call", "");
            return;
        }
        if (instruction instanceof IrFileInstruction file) {
            emitFileInstruction(output, file, "call", "");
            return;
        }
        if (instruction instanceof IrFloatingParseInstruction parse) {
            emitFloatingParseInstruction(output, parse);
            return;
        }
        if (instruction instanceof IrSystemGetenvInstruction getenv) {
            output.append(operand(getenv.result()))
                    .append(" = call ptr @ironwood_system_getenv(ptr ")
                    .append(operand(getenv.name())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrSystemPropertyInstruction property) {
            output.append(operand(property.result()))
                    .append(" = call ptr @ironwood_system_get_property(ptr ")
                    .append(operand(property.name())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrSystemExitInstruction exit) {
            output.append("call void @ironwood_system_exit(i32 ")
                    .append(operand(exit.status())).append(')');
            return;
        }
        if (instruction instanceof IrSystemClockInstruction clock) {
            output.append(operand(clock.result())).append(" = call i64 ")
                    .append(clock.clock() == IrSystemClockInstruction.Clock.CURRENT_TIME_MILLIS
                            ? "@ironwood_current_time_millis()" : "@ironwood_nano_time()");
            return;
        }
        if (instruction instanceof IrPrintStreamPrintlnInstruction println) {
            output.append("call void @ironwood_stdout_println(ptr ")
                    .append(operand(println.value())).append(')');
            return;
        }
        if (instruction instanceof IrPrintStreamWriteInstruction write) {
            int kind = write.value().map(part -> part.kind().ordinal()).orElse(-1);
            String payload = write.value().map(part ->
                    concatPayload(output, part, scratchNames)).orElse("0");
            output.append("call void @ironwood_print_stream_write(ptr ")
                    .append(operand(write.stream())).append(", i32 ").append(kind)
                    .append(", i64 ").append(payload).append(", i1 ")
                    .append(write.newline() ? "true" : "false").append(')');
            return;
        }
        if (instruction instanceof IrPrintStreamFlushInstruction flush) {
            output.append("call void @ironwood_print_stream_flush(ptr ")
                    .append(operand(flush.stream())).append(')');
            return;
        }
        if (instruction instanceof IrPrintStreamCheckErrorInstruction error) {
            output.append(operand(error.result()))
                    .append(" = call i1 @ironwood_print_stream_check_error(ptr ")
                    .append(operand(error.stream())).append(')');
            return;
        }
        if (instruction instanceof IrCharacterInstruction property) {
            output.append(operand(property.result()))
                    .append(" = call i32 @").append(property.operation().functionName())
                    .append("(i32 ")
                    .append(operand(property.codePoint())).append(')');
            return;
        }
        if (instruction instanceof IrFloatingBitsInstruction bits) {
            output.append(operand(bits.result())).append(" = bitcast ")
                    .append(llvmType(bits.value().type())).append(' ')
                    .append(operand(bits.value())).append(" to ")
                    .append(llvmType(bits.result().type()));
            return;
        }
        if (instruction instanceof IrMathUnaryInstruction math) {
            output.append(operand(math.result())).append(" = call double @")
                    .append(math.operation().functionName()).append("(double ")
                    .append(operand(math.value())).append(')');
            return;
        }
        if (instruction instanceof IrMathBinaryInstruction math) {
            output.append(operand(math.result())).append(" = call double @")
                    .append(math.operation().functionName()).append("(double ")
                    .append(operand(math.left())).append(", double ")
                    .append(operand(math.right())).append(')');
            return;
        }
        if (instruction instanceof IrFieldLoadInstruction load) {
            String fieldPointer = scratchNames.next("field.ptr");
            output.append(fieldPointer).append(" = getelementptr inbounds ")
                    .append(classType(load.field().ownerClass())).append(", ptr ")
                    .append(operand(load.receiver())).append(", i32 0, i32 ")
                    .append(load.field().layoutIndex() + 1).append("\n  ")
                    .append(operand(load.result())).append(" = load ")
                    .append(llvmType(load.field().type())).append(", ptr ").append(fieldPointer);
            return;
        }
        if (instruction instanceof IrFieldStoreInstruction store) {
            String fieldPointer = scratchNames.next("field.ptr");
            output.append(fieldPointer).append(" = getelementptr inbounds ")
                    .append(classType(store.field().ownerClass())).append(", ptr ")
                    .append(operand(store.receiver())).append(", i32 0, i32 ")
                    .append(store.field().layoutIndex() + 1).append("\n  store ")
                    .append(llvmType(store.field().type())).append(' ')
                    .append(operand(store.value())).append(", ptr ").append(fieldPointer);
            return;
        }
        if (instruction instanceof IrStaticFieldLoadInstruction load) {
            output.append(operand(load.result())).append(" = load ")
                    .append(llvmType(load.field().type())).append(", ptr ")
                    .append(staticFieldName(load.field()));
            return;
        }
        if (instruction instanceof IrStaticFieldStoreInstruction store) {
            output.append("store ").append(llvmType(store.field().type())).append(' ')
                    .append(operand(store.value())).append(", ptr ")
                    .append(staticFieldName(store.field()));
            return;
        }
        if (instruction instanceof IrDestroyArrayElementsInstruction destroy) {
            output.append("call void @\"ironwood.destroy_array_elements\"(ptr ")
                    .append(operand(destroy.array())).append(')');
            return;
        }
        if (instruction instanceof IrFreeInstruction free) {
            output.append("call void @\"ironwood.destroy\"(ptr ")
                    .append(operand(free.allocation())).append(')');
            return;
        }
        if (instruction instanceof IrRawDeallocateInstruction raw) {
            output.append("call void @ironwood_deallocate(ptr ")
                    .append(operand(raw.allocation())).append(')');
            return;
        }
        if (instruction instanceof IrReleaseOwnedToStringResultInstruction release) {
            output.append("call void @ironwood_release_owned_to_string_result(ptr ")
                    .append(operand(release.object())).append(", ptr ")
                    .append(operand(release.result())).append(')');
            return;
        }
        if (instruction instanceof IrRollbackInstruction rollback) {
            output.append("call void @\"ironwood.rollback\"(ptr ")
                    .append(operand(rollback.allocation())).append(')');
            return;
        }
        if (instruction instanceof IrNullCheckInstruction check) {
            output.append(operand(check.result())).append(" = icmp ne ptr ")
                    .append(operand(check.receiver())).append(", null");
            return;
        }
        if (instruction instanceof IrIdentityHashCodeInstruction hashCode) {
            output.append(operand(hashCode.result()))
                    .append(" = call i32 @ironwood_identity_hash_code(ptr ")
                    .append(operand(hashCode.object())).append(')');
            return;
        }
        if (instruction instanceof IrObjectHashCodeInstruction hashCode) {
            output.append(operand(hashCode.result())).append(" = call i32 @ironwood_object_hash_code(ptr ")
                    .append(operand(hashCode.object())).append(')');
            return;
        }
        if (instruction instanceof IrReleaseOwnedThrowableMessageInstruction release) {
            output.append("call void @ironwood_release_owned_throwable_message(ptr ")
                    .append(operand(release.throwable())).append(", ptr ")
                    .append(operand(release.message())).append(')');
            return;
        }
        if (instruction instanceof IrThrowableDescriptionInstruction description) {
            output.append(operand(description.result()))
                    .append(" = call ptr @ironwood_throwable_description(ptr ")
                    .append(operand(description.throwable())).append(", ptr ")
                    .append(operand(description.message())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrObjectToStringInstruction toString) {
            output.append(operand(toString.result())).append(" = call ptr @ironwood_object_to_string(ptr ")
                    .append(operand(toString.object())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringCharAtInstruction charAt) {
            // Unchecked UTF-16 unit access: the runtime string layout places the
            // units directly after the descriptor and the two length fields.
            String unitPointer = scratchNames.next("string.unit.ptr");
            output.append(unitPointer).append(" = getelementptr inbounds %\"ironwood.string\", ptr ")
                    .append(operand(charAt.string())).append(", i64 0, i32 3, i32 ")
                    .append(operand(charAt.index())).append("\n  ")
                    .append(operand(charAt.result())).append(" = load i16, ptr ").append(unitPointer);
            return;
        }
        if (instruction instanceof IrStringEqualsInstruction equals) {
            output.append(operand(equals.result())).append(" = call i1 @ironwood_string_equals(ptr ")
                    .append(operand(equals.string())).append(", ptr ")
                    .append(operand(equals.other())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(')');
            return;
        }
        if (instruction instanceof IrStringHashCodeInstruction hashCode) {
            output.append(operand(hashCode.result())).append(" = call i32 @ironwood_string_hash_code(ptr ")
                    .append(operand(hashCode.string())).append(')');
            return;
        }
        if (instruction instanceof IrStringCopyInstruction copy) {
            output.append(operand(copy.result())).append(" = call ptr @ironwood_string_copy(ptr ")
                    .append(operand(copy.source())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringCaseInstruction value) {
            output.append(operand(value.result())).append(" = call ptr @ironwood_string_case(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i1 ").append(operand(value.upper()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringRepeatInstruction value) {
            output.append(operand(value.result())).append(" = call ptr @ironwood_string_repeat(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i32 ").append(operand(value.count()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringReplaceCharInstruction value) {
            output.append(operand(value.result())).append(" = call ptr @ironwood_string_replace_char(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i16 ").append(operand(value.oldChar()))
                    .append(", i16 ").append(operand(value.newChar()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringReplaceTextInstruction value) {
            output.append(operand(value.result())).append(" = call ptr @ironwood_string_replace_text(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", ptr ").append(operand(value.target()))
                    .append(", ptr ").append(operand(value.replacement()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringEqualsIgnoreCaseInstruction value) {
            output.append(operand(value.result())).append(" = call i1 @ironwood_string_equals_ignore_case(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", ptr ").append(operand(value.other()))
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringJoinInstruction value) {
            output.append(operand(value.result())).append(" = call ptr @ironwood_string_join(")
                    .append("ptr ").append(operand(value.delimiter()))
                    .append(", ptr ").append(operand(value.elements()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')');
            return;
        }
        if (instruction instanceof IrStringFromUtf8Instruction snapshot) {
            output.append(operand(snapshot.result())).append(" = call ptr @ironwood_string_from_utf8(ptr ")
                    .append(operand(snapshot.bytes())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringFromCharsInstruction snapshot) {
            output.append(operand(snapshot.result())).append(" = call ptr @ironwood_string_from_chars(ptr ")
                    .append(operand(snapshot.characters())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringFromCharRangeInstruction snapshot) {
            output.append(operand(snapshot.result()))
                    .append(" = call ptr @ironwood_string_from_char_range(ptr ")
                    .append(operand(snapshot.characters())).append(", i32 ")
                    .append(operand(snapshot.offset())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringFromRangeInstruction snapshot) {
            output.append(operand(snapshot.result())).append(" = call ptr @ironwood_string_from_range(ptr ")
                    .append(operand(snapshot.source())).append(", i32 ")
                    .append(operand(snapshot.beginIndex())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            return;
        }
        if (instruction instanceof IrStringConcatInstruction concatenation) {
            emitStringConcatenation(output, concatenation, scratchNames, "call", "");
            return;
        }
        if (instruction instanceof IrStringFromIntegerInstruction formatting) {
            emitIntegerString(output, formatting, "call", "");
            return;
        }
        if (instruction instanceof IrStringFromCharacterInstruction formatting) {
            emitCharacterString(output, formatting, "call", "");
            return;
        }
        if (instruction instanceof IrExceptionCaughtInstruction caught) {
            output.append("call void @ironwood_exception_caught(ptr ")
                    .append(operand(caught.exception())).append(')');
            return;
        }
        if (instruction instanceof IrExceptionLandingPadInstruction landingPad) {
            String aggregate = scratchNames.next("exception.landing");
            output.append(aggregate).append(" = landingpad { ptr, i32 } catch ptr null\n  ")
                    .append(operand(landingPad.exceptionHandle()))
                    .append(" = extractvalue { ptr, i32 } ").append(aggregate).append(", 0\n  ")
                    .append(operand(landingPad.exceptionObject()))
                    .append(" = call ptr @ironwood_exception_take(ptr ")
                    .append(operand(landingPad.exceptionHandle())).append(')');
            return;
        }
        if (instruction instanceof IrAddSecondaryExceptionInstruction secondary) {
            output.append("call void @ironwood_exception_add_secondary(ptr ")
                    .append(operand(secondary.primary())).append(", ptr ")
                    .append(operand(secondary.secondary())).append(')');
            return;
        }
        if (instruction instanceof IrThrowableTraceInstruction trace) {
            trace.result().ifPresent(result -> output.append(operand(result)).append(" = "));
            output.append(trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                            ? "notail call " : "call ")
                    .append(llvmType(trace.operation().returnType())).append(" @")
                    .append(trace.operation().runtimeName()).append('(')
                    .append(trace.arguments().stream().map(argument -> llvmType(argument.type()) + " " + operand(argument))
                            .collect(Collectors.joining(", ")));
            if (trace.operation() == IrThrowableTraceInstruction.Operation.ARRAY) {
                output.append(", ptr ")
                        .append(typeInfoName("ironwood.lang.StackTraceElement[]"))
                        .append(", ptr ").append(allocationFailureName());
            }
            output.append(')');
            if (trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.ARRAY) {
                output.append(", !dbg !")
                        .append(tracePlan.site(function, instruction).callLocationMetadata());
            }
            return;
        }
        if (instruction instanceof IrSecondaryExceptionCountInstruction count) {
            output.append(operand(count.result()))
                    .append(" = call i32 @ironwood_exception_secondary_count(ptr ")
                    .append(operand(count.primary())).append(')');
            return;
        }
        if (instruction instanceof IrSecondaryExceptionAtInstruction secondary) {
            output.append(operand(secondary.result()))
                    .append(" = call ptr @ironwood_exception_secondary_at(ptr ")
                    .append(operand(secondary.primary())).append(", i32 ")
                    .append(operand(secondary.index())).append(')');
            return;
        }
        if (instruction instanceof IrBinaryInstruction binary) {
            if ((binary.operator() == ironwood.compiler.ir.IrBinaryOperator.DIVIDE
                    || binary.operator() == ironwood.compiler.ir.IrBinaryOperator.REMAINDER)
                    && binary.left().type().isIntegral()) {
                emitSafeDivision(output, binary, scratchNames);
                return;
            }
            IrType operandType = binary.left().type();
            boolean floating = operandType.isFloating();
            output.append(operand(binary.result())).append(" = ");
            switch (binary.operator()) {
                case ADD -> output.append(floating ? "fadd " : "add ").append(llvmType(operandType)).append(' ');
                case SUBTRACT -> output.append(floating ? "fsub " : "sub ").append(llvmType(operandType)).append(' ');
                case MULTIPLY -> output.append(floating ? "fmul " : "mul ").append(llvmType(operandType)).append(' ');
                case DIVIDE -> output.append("fdiv ").append(llvmType(operandType)).append(' ');
                case REMAINDER -> output.append("frem ").append(llvmType(operandType)).append(' ');
                case SHIFT_LEFT -> output.append("shl ").append(llvmType(operandType)).append(' ');
                case SHIFT_RIGHT -> output.append("ashr ").append(llvmType(operandType)).append(' ');
                case UNSIGNED_SHIFT_RIGHT -> output.append("lshr ").append(llvmType(operandType)).append(' ');
                case BITWISE_AND -> output.append("and ").append(llvmType(operandType)).append(' ');
                case BITWISE_XOR -> output.append("xor ").append(llvmType(operandType)).append(' ');
                case BITWISE_OR -> output.append("or ").append(llvmType(operandType)).append(' ');
                case EQUAL -> output.append(floating ? "fcmp oeq " : "icmp eq ")
                        .append(llvmType(operandType)).append(' ');
                case NOT_EQUAL -> output.append(floating ? "fcmp une " : "icmp ne ")
                        .append(llvmType(operandType)).append(' ');
                case SIGNED_LESS -> output.append(floating ? "fcmp olt " : "icmp slt ")
                        .append(llvmType(operandType)).append(' ');
                case SIGNED_LESS_EQUAL -> output.append(floating ? "fcmp ole " : "icmp sle ")
                        .append(llvmType(operandType)).append(' ');
                case SIGNED_GREATER -> output.append(floating ? "fcmp ogt " : "icmp sgt ")
                        .append(llvmType(operandType)).append(' ');
                case SIGNED_GREATER_EQUAL -> output.append(floating ? "fcmp oge " : "icmp sge ")
                        .append(llvmType(operandType)).append(' ');
            }
            output.append(operand(binary.left())).append(", ").append(operand(binary.right()));
            return;
        }
        if (instruction instanceof IrUnaryInstruction unary) {
            output.append(operand(unary.result())).append(" = ");
            switch (unary.operator()) {
                case NEGATE -> {
                    if (unary.operand().type().isFloating()) {
                        output.append("fneg ").append(llvmType(unary.operand().type())).append(' ')
                                .append(operand(unary.operand()));
                    } else {
                        output.append("sub ").append(llvmType(unary.operand().type())).append(" 0, ")
                                .append(operand(unary.operand()));
                    }
                }
                case NOT -> output.append("xor i1 ").append(operand(unary.operand())).append(", true");
                case BITWISE_COMPLEMENT -> output.append("xor ")
                        .append(llvmType(unary.operand().type())).append(' ')
                        .append(operand(unary.operand())).append(", -1");
            }
            return;
        }
        if (instruction instanceof IrCallInstruction call) {
            call.result().ifPresent(result -> output.append(operand(result)).append(" = "));
            output.append(isStackCaptureCall(call.targetLinkageName()) ? "notail call " : "call ")
                    .append(llvmType(call.returnType())).append(' ')
                    .append(functionName(call.targetLinkageName())).append('(');
            output.append(call.arguments().stream()
                    .map(argument -> llvmType(argument.type()) + " " + operand(argument))
                    .collect(Collectors.joining(", ")));
            output.append("), !dbg !")
                    .append(tracePlan.site(function, instruction).callLocationMetadata());
            return;
        }
        if (instruction instanceof IrVirtualCallInstruction call) {
            String operation = call.slot().methodName().equals("fillInStackTrace") ? "notail call" : "call";
            String debugSuffix = ", !dbg !" + tracePlan.site(function, instruction).callLocationMetadata();
            List<DispatchReceiver> guarded = guardPlan(call);
            if (guarded != null) {
                emitGuardedDispatch(output, call.result(), call.slot().index(), call.returnType(),
                        call.arguments(), guarded, scratchNames, operation, debugSuffix, null);
                return;
            }
            emitIndirectCall(output, call.result(), call.slot().index(), call.returnType(),
                    call.arguments(), scratchNames, "virtual", operation, debugSuffix);
            return;
        }
        if (instruction instanceof IrInterfaceCallInstruction call) {
            String operation = call.slot().methodName().equals("fillInStackTrace") ? "notail call" : "call";
            String debugSuffix = ", !dbg !" + tracePlan.site(function, instruction).callLocationMetadata();
            List<DispatchReceiver> guarded = guardPlan(call);
            if (guarded != null) {
                emitGuardedDispatch(output, call.result(), call.slot().index(), call.returnType(),
                        call.arguments(), guarded, scratchNames, operation, debugSuffix, null);
                return;
            }
            emitIndirectCall(output, call.result(), call.slot().index(), call.returnType(),
                    call.arguments(), scratchNames, "interface", operation, debugSuffix);
            return;
        }
        if (instruction instanceof IrReferenceConversionInstruction conversion) {
            output.append(operand(conversion.result())).append(" = getelementptr i8, ptr ")
                    .append(operand(conversion.value())).append(", i64 0");
            return;
        }
        if (instruction instanceof IrNumericConversionInstruction conversion) {
            emitNumericConversion(output, conversion, scratchNames);
            return;
        }
        if (instruction instanceof IrInstanceOfInstruction typeTest) {
            output.append(operand(typeTest.result())).append(" = call i1 @\"ironwood.is_instance\"(ptr ")
                    .append(operand(typeTest.value())).append(", i32 ")
                    .append(typeTest.targetTypeId()).append(')');
            return;
        }
        if (instruction instanceof IrArrayTypeTestInstruction typeTest) {
            output.append(operand(typeTest.result()))
                    .append(" = call i1 @\"ironwood.is_exact_array\"(ptr ")
                    .append(operand(typeTest.value())).append(", ptr ")
                    .append(typeInfoName(typeTest.targetType().displayName())).append(')');
            return;
        }
        if (instruction instanceof IrPhiInstruction phi) {
            output.append(operand(phi.result())).append(" = phi ")
                    .append(llvmType(phi.result().type())).append(' ');
            output.append(phi.incoming().stream()
                    .map(incoming -> phiIncoming(incoming, currentBlockLabel))
                    .collect(Collectors.joining(", ")));
            return;
        }
        throw new IllegalStateException("unsupported IR instruction " + instruction.getClass().getSimpleName());
    }

    private void emitStringConcatenation(StringBuilder output,
                                         IrStringConcatInstruction concatenation,
                                         ScratchNames scratchNames,
                                         String operation,
                                         String suffix) {
        int count = concatenation.parts().size();
        String descriptors = scratchNames.next("string.concat.parts");
        output.append(descriptors).append(" = alloca [").append(count)
                .append(" x %\"ironwood.string.concat.part\"]");
        for (int index = 0; index < count; index++) {
            IrStringConcatPart part = concatenation.parts().get(index);
            String descriptor = scratchNames.next("string.concat.part");
            String kindAddress = scratchNames.next("string.concat.kind");
            String payloadAddress = scratchNames.next("string.concat.payload");
            output.append("\n  ").append(descriptor).append(" = getelementptr inbounds [")
                    .append(count).append(" x %\"ironwood.string.concat.part\"], ptr ")
                    .append(descriptors).append(", i32 0, i32 ").append(index)
                    .append("\n  ").append(kindAddress)
                    .append(" = getelementptr inbounds %\"ironwood.string.concat.part\", ptr ")
                    .append(descriptor).append(", i32 0, i32 0\n  store i32 ")
                    .append(part.kind().ordinal()).append(", ptr ").append(kindAddress)
                    .append("\n  ").append(payloadAddress)
                    .append(" = getelementptr inbounds %\"ironwood.string.concat.part\", ptr ")
                    .append(descriptor).append(", i32 0, i32 2\n  ");
            String payload = concatPayload(output, part, scratchNames);
            output.append("store i64 ").append(payload).append(", ptr ").append(payloadAddress);
        }
        output.append("\n  ").append(operand(concatenation.result()))
                .append(" = ").append(operation)
                .append(" ptr @ironwood_string_concat(ptr ").append(descriptors)
                .append(", i32 ").append(count).append(", ptr ")
                .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                .append(allocationFailureName()).append(')').append(suffix);
    }

    private String concatPayload(StringBuilder output, IrStringConcatPart part,
                                 ScratchNames scratchNames) {
        IrOperand value = part.value();
        if (part.kind() == ironwood.compiler.ir.IrStringConcatPartKind.STRING) {
            String converted = scratchNames.next("string.concat.pointer.bits");
            output.append(converted).append(" = ptrtoint ptr ").append(operand(value))
                    .append(" to i64\n  ");
            return converted;
        }
        if (part.kind() == ironwood.compiler.ir.IrStringConcatPartKind.FLOAT) {
            String bits = scratchNames.next("string.concat.float.bits");
            String extended = scratchNames.next("string.concat.float.extended");
            output.append(bits).append(" = bitcast float ").append(operand(value))
                    .append(" to i32\n  ").append(extended).append(" = zext i32 ")
                    .append(bits).append(" to i64\n  ");
            return extended;
        }
        if (part.kind() == ironwood.compiler.ir.IrStringConcatPartKind.DOUBLE) {
            String bits = scratchNames.next("string.concat.double.bits");
            output.append(bits).append(" = bitcast double ").append(operand(value))
                    .append(" to i64\n  ");
            return bits;
        }
        if (value.type().equals(IrType.I64)) {
            return operand(value);
        }
        String extended = scratchNames.next("string.concat.integer.extended");
        boolean signed = part.kind() == ironwood.compiler.ir.IrStringConcatPartKind.INTEGER;
        output.append(extended).append(" = ").append(signed ? "sext " : "zext ")
                .append(llvmType(value.type())).append(' ').append(operand(value))
                .append(" to i64\n  ");
        return extended;
    }

    private void emitSafeDivision(StringBuilder output, IrBinaryInstruction binary,
                                  ScratchNames scratchNames) {
        IrType type = binary.left().type();
        String llvmType = llvmType(type);
        String minimumValue = type.equals(IrType.I64)
                ? "-9223372036854775808" : "-2147483648";
        String minimum = scratchNames.next("division.minimum");
        String negativeOne = scratchNames.next("division.negative.one");
        String overflow = scratchNames.next("division.overflow");
        String safeDivisor = scratchNames.next("division.safe.divisor");
        String rawResult = scratchNames.next("division.raw.result");
        output.append(minimum).append(" = icmp eq ").append(llvmType).append(' ')
                .append(operand(binary.left())).append(", ").append(minimumValue).append("\n  ")
                .append(negativeOne).append(" = icmp eq ").append(llvmType).append(' ')
                .append(operand(binary.right()))
                .append(", -1\n  ")
                .append(overflow).append(" = and i1 ").append(minimum).append(", ")
                .append(negativeOne).append("\n  ")
                .append(safeDivisor).append(" = select i1 ").append(overflow)
                .append(", ").append(llvmType).append(" 1, ").append(llvmType).append(' ')
                .append(operand(binary.right())).append("\n  ")
                .append(rawResult).append(binary.operator()
                        == ironwood.compiler.ir.IrBinaryOperator.DIVIDE
                        ? " = sdiv " + llvmType + " " : " = srem " + llvmType + " ")
                .append(operand(binary.left())).append(", ").append(safeDivisor).append("\n  ")
                .append(operand(binary.result())).append(" = select i1 ").append(overflow)
                .append(binary.operator() == ironwood.compiler.ir.IrBinaryOperator.DIVIDE
                        ? ", " + llvmType + " " + minimumValue + ", " + llvmType + " "
                        : ", " + llvmType + " 0, " + llvmType + " ")
                .append(rawResult);
    }

    private void emitNumericConversion(StringBuilder output, IrNumericConversionInstruction conversion,
                                       ScratchNames scratchNames) {
        IrType source = conversion.value().type();
        IrType target = conversion.result().type();
        String result = operand(conversion.result());
        String value = operand(conversion.value());
        if (source.isIntegral() && target.isIntegral()) {
            int sourceBits = integralBits(source);
            int targetBits = integralBits(target);
            if (sourceBits < targetBits) {
                output.append(result).append(source.equals(IrType.U16) ? " = zext " : " = sext ")
                        .append(llvmType(source)).append(' ').append(value).append(" to ")
                        .append(llvmType(target));
            } else if (sourceBits > targetBits) {
                output.append(result).append(" = trunc ").append(llvmType(source)).append(' ')
                        .append(value).append(" to ").append(llvmType(target));
            } else {
                output.append(result).append(" = add ").append(llvmType(target)).append(' ')
                        .append(value).append(", 0");
            }
            return;
        }
        if (source.isIntegral() && target.isFloating()) {
            output.append(result).append(source.equals(IrType.U16) ? " = uitofp " : " = sitofp ")
                    .append(llvmType(source)).append(' ').append(value).append(" to ")
                    .append(llvmType(target));
            return;
        }
        if (source.isFloating() && target.isFloating()) {
            output.append(result).append(target.equals(IrType.F64) ? " = fpext " : " = fptrunc ")
                    .append(llvmType(source)).append(' ').append(value).append(" to ")
                    .append(llvmType(target));
            return;
        }
        if (source.isFloating() && (target.equals(IrType.I32) || target.equals(IrType.I64))) {
            emitSaturatingFloatingToIntegral(output, conversion, scratchNames);
            return;
        }
        throw new IllegalStateException("unsupported numeric conversion "
                + source.displayName() + " to " + target.displayName());
    }

    private void emitSaturatingFloatingToIntegral(StringBuilder output,
                                                  IrNumericConversionInstruction conversion,
                                                  ScratchNames scratchNames) {
        IrType source = conversion.value().type();
        IrType target = conversion.result().type();
        String sourceType = llvmType(source);
        String targetType = llvmType(target);
        String value = operand(conversion.value());
        String nan = scratchNames.next("numeric.nan");
        String below = scratchNames.next("numeric.below.minimum");
        String above = scratchNames.next("numeric.above.maximum");
        String safeNan = scratchNames.next("numeric.safe.nan");
        String safeLow = scratchNames.next("numeric.safe.low");
        String safe = scratchNames.next("numeric.safe.high");
        String raw = scratchNames.next("numeric.raw");
        String lowResult = scratchNames.next("numeric.low.result");
        String rangeResult = scratchNames.next("numeric.range.result");
        String zeroFloat = source.equals(IrType.F32) ? "0.000000000e+00" : "0.00000000000000000e+00";
        String minimumFloat = target.equals(IrType.I32)
                ? (source.equals(IrType.F32) ? "-2.147483648e+09" : "-2.14748364800000000e+09")
                : (source.equals(IrType.F32) ? "-9.223372037e+18" : "-9.22337203685477581e+18");
        // The upper boundary is one past the largest result. That value is exactly
        // representable and avoids rounding Long.MAX_VALUE to 2^63 in binary FP.
        String maximumBoundary = target.equals(IrType.I32)
                ? (source.equals(IrType.F32) ? "2.147483648e+09" : "2.14748364800000000e+09")
                : (source.equals(IrType.F32) ? "9.223372037e+18" : "9.22337203685477581e+18");
        String minimumInteger = target.equals(IrType.I32)
                ? "-2147483648" : "-9223372036854775808";
        String maximumInteger = target.equals(IrType.I32)
                ? "2147483647" : "9223372036854775807";
        output.append(nan).append(" = fcmp uno ").append(sourceType).append(' ')
                .append(value).append(", ").append(value).append("\n  ")
                .append(below).append(" = fcmp ole ").append(sourceType).append(' ')
                .append(value).append(", ").append(minimumFloat).append("\n  ")
                .append(above).append(" = fcmp oge ").append(sourceType).append(' ')
                .append(value).append(", ").append(maximumBoundary).append("\n  ")
                .append(safeNan).append(" = select i1 ").append(nan).append(", ")
                .append(sourceType).append(' ').append(zeroFloat).append(", ")
                .append(sourceType).append(' ').append(value).append("\n  ")
                .append(safeLow).append(" = select i1 ").append(below).append(", ")
                .append(sourceType).append(' ').append(zeroFloat).append(", ")
                .append(sourceType).append(' ').append(safeNan).append("\n  ")
                .append(safe).append(" = select i1 ").append(above).append(", ")
                .append(sourceType).append(' ').append(zeroFloat).append(", ")
                .append(sourceType).append(' ').append(safeLow).append("\n  ")
                .append(raw).append(" = fptosi ").append(sourceType).append(' ').append(safe)
                .append(" to ").append(targetType).append("\n  ")
                .append(lowResult).append(" = select i1 ").append(below).append(", ")
                .append(targetType).append(' ').append(minimumInteger).append(", ")
                .append(targetType).append(' ').append(raw).append("\n  ")
                .append(rangeResult).append(" = select i1 ").append(above).append(", ")
                .append(targetType).append(' ').append(maximumInteger).append(", ")
                .append(targetType).append(' ').append(lowResult).append("\n  ")
                .append(operand(conversion.result())).append(" = select i1 ").append(nan)
                .append(", ").append(targetType).append(" 0, ").append(targetType).append(' ')
                .append(rangeResult);
    }

    private static int integralBits(IrType type) {
        return switch (type.kind()) {
            case I8 -> 8;
            case I16, U16 -> 16;
            case I32 -> 32;
            case I64 -> 64;
            default -> throw new IllegalArgumentException("not an integral type: " + type.displayName());
        };
    }

    private void emitIndirectCall(StringBuilder output, java.util.Optional<IrValueReference> result,
                                  int slot, IrType returnType, java.util.List<IrOperand> arguments,
                                  ScratchNames scratchNames, String kind, String operation,
                                  String suffix) {
        IrOperand receiver = arguments.getFirst();
        String typeInfo = scratchNames.next(kind + ".typeinfo");
        String dispatchAddress = scratchNames.next(kind + ".dispatch.address");
        String dispatch = scratchNames.next(kind + ".dispatch");
        String slotAddress = scratchNames.next(kind + ".slot.address");
        String callee = scratchNames.next(kind + ".callee");
        output.append(typeInfo).append(" = load ptr, ptr ").append(operand(receiver)).append("\n  ")
                .append(dispatchAddress).append(" = getelementptr inbounds %\"ironwood.typeinfo\", ptr ")
                .append(typeInfo).append(", i32 0, i32 1\n  ")
                .append(dispatch).append(" = load ptr, ptr ").append(dispatchAddress).append("\n  ")
                .append(slotAddress).append(" = getelementptr inbounds ptr, ptr ").append(dispatch)
                .append(", i32 ").append(slot).append("\n  ")
                .append(callee).append(" = load ptr, ptr ").append(slotAddress).append("\n  ");
        result.ifPresent(value -> output.append(operand(value)).append(" = "));
        output.append(operation).append(' ').append(llvmType(returnType)).append(' ').append(callee).append('(')
                .append(arguments.stream()
                        .map(argument -> llvmType(argument.type()) + " " + operand(argument))
                        .collect(Collectors.joining(", ")))
                .append(')').append(suffix);
    }

    private String phiIncoming(IrPhiIncoming incoming, String phiBlockLabel) {
        String value = operand(incoming.value());
        BlockEdges edges = layout == null ? null : layout.edges().get(incoming.predecessor());
        if (edges == null) {
            return "[ " + value + ", %" + incoming.predecessor() + " ]";
        }
        // A guarded dispatch splits the predecessor block: its normal successors
        // are reached from the join block, while the unwind edge of a guarded
        // invoke comes from every direct-call block and the table fallback.
        IrBasicBlock predecessor = layout.blocks().get(incoming.predecessor());
        boolean unwindEdge = predecessor != null
                && predecessor.terminator() instanceof IrInvokeTerminator invoke
                && invoke.unwindTarget().equals(phiBlockLabel);
        List<String> labels = unwindEdge ? edges.unwindPredecessors() : List.of(edges.normalPredecessor());
        return labels.stream()
                .map(label -> "[ " + value + ", %" + label + " ]")
                .collect(Collectors.joining(", "));
    }

    private void emitTerminator(StringBuilder output, IrFunction function, IrTerminator terminator,
                                ScratchNames scratchNames) {
        if (terminator instanceof IrReturnTerminator returnTerminator) {
            if (returnTerminator.value().isEmpty()) {
                output.append("ret void");
            } else {
                IrOperand value = returnTerminator.value().get();
                output.append("ret ").append(llvmType(value.type())).append(' ').append(operand(value));
            }
            return;
        }
        if (terminator instanceof IrJump jump) {
            output.append("br label %").append(jump.target());
            return;
        }
        if (terminator instanceof IrBranch branch) {
            output.append("br i1 ").append(operand(branch.condition()))
                    .append(", label %").append(branch.trueTarget())
                    .append(", label %").append(branch.falseTarget());
            return;
        }
        if (terminator instanceof IrSwitchTerminator switchTerminator) {
            String type = llvmType(switchTerminator.selector().type());
            output.append("switch ").append(type).append(' ')
                    .append(operand(switchTerminator.selector()))
                    .append(", label %").append(switchTerminator.defaultTarget())
                    .append(" [");
            for (var branch : switchTerminator.cases()) {
                output.append("\n    ").append(type).append(' ')
                        .append(operand(branch.value())).append(", label %")
                        .append(branch.target());
            }
            if (!switchTerminator.cases().isEmpty()) {
                output.append('\n');
            }
            output.append("  ]");
            return;
        }
        if (terminator instanceof IrInvokeTerminator invoke) {
            TraceSite site = tracePlan.site(function, invoke);
            emitTraceProbe(output, site);
            output.append("\n  ");
            emitInvoke(output, invoke, scratchNames, site);
            return;
        }
        if (terminator instanceof IrThrowTerminator thrown) {
            emitTraceProbe(output, tracePlan.site(function, thrown));
            output.append("\n  ");
            if (thrown.unwindTarget().isPresent()) {
                output.append("invoke void @ironwood_throw(ptr ").append(operand(thrown.exception()))
                        .append(") to label %").append(thrown.normalTarget())
                        .append(" unwind label %").append(thrown.unwindTarget().orElseThrow())
                        .append(", !dbg !").append(tracePlan.site(function, thrown).callLocationMetadata());
            } else {
                output.append("call void @ironwood_throw(ptr ").append(operand(thrown.exception()))
                        .append("), !dbg !").append(tracePlan.site(function, thrown).callLocationMetadata())
                        .append("\n  unreachable");
            }
            return;
        }
        if (terminator instanceof IrUnreachable) {
            output.append("unreachable");
            return;
        }
        throw new IllegalStateException("unsupported IR terminator " + terminator.getClass().getSimpleName());
    }

    private static void emitTraceProbe(StringBuilder output, TraceSite site) {
        emitTraceProbe(output, site.guid(), site.probeIndex(), site.probeType(),
                site.locationMetadata());
    }

    private static void emitTraceProbe(StringBuilder output, long guid, int probeIndex,
                                       int probeType, int locationMetadata) {
        output.append("call void @llvm.pseudoprobe(i64 ").append(guid)
                .append(", i64 ").append(probeIndex)
                .append(", i32 ").append(probeType)
                .append(", i64 -1), !dbg !").append(locationMetadata);
    }

    private void emitInvoke(StringBuilder output, IrInvokeTerminator invoke,
                            ScratchNames scratchNames, TraceSite traceSite) {
        String suffix = " to label %" + invoke.normalTarget()
                + " unwind label %" + invoke.unwindTarget()
                + ", !dbg !" + traceSite.callLocationMetadata();
        IrInstruction call = invoke.call();
        if (call instanceof IrEnsureTypeInitializedInstruction ensure) {
            output.append("invoke void ").append(typeInitializerName(ensure.typeName()))
                    .append("()").append(suffix);
            return;
        }
        if (call instanceof IrAllocateInstruction allocate) {
            String sizePointer = scratchNames.next("alloc.size.ptr");
            String size = scratchNames.next("alloc.size");
            output.append(sizePointer).append(" = getelementptr ")
                    .append(classType(allocate.className())).append(", ptr null, i32 1\n  ")
                    .append(size).append(" = ptrtoint ptr ").append(sizePointer).append(" to i64\n  ")
                    .append(operand(allocate.result())).append(" = invoke ptr @ironwood_allocate(i64 ")
                    .append(size).append(", ptr ").append(typeInfoName(allocate.className()))
                    .append(", ptr ").append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrArrayAllocateInstruction allocate) {
            String sizePointer = scratchNames.next("array.element.size.ptr");
            String size = scratchNames.next("array.element.size");
            output.append(sizePointer).append(" = getelementptr ")
                    .append(llvmType(allocate.elementType())).append(", ptr null, i32 1\n  ")
                    .append(size).append(" = ptrtoint ptr ").append(sizePointer).append(" to i64\n  ")
                    .append(operand(allocate.result()))
                    .append(" = invoke ptr @ironwood_allocate_array(i32 ")
                    .append(operand(allocate.length())).append(", i64 ").append(size)
                    .append(", i32 ").append(arrayElementKind(allocate.elementType()))
                    .append(", ptr ")
                    .append(typeInfoName(allocate.result().type().erasure().displayName()))
                    .append(", ptr ").append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrThrowableDescriptionInstruction description) {
            output.append(operand(description.result()))
                    .append(" = invoke ptr @ironwood_throwable_description(ptr ")
                    .append(operand(description.throwable())).append(", ptr ")
                    .append(operand(description.message())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrObjectToStringInstruction toString) {
            output.append(operand(toString.result()))
                    .append(" = invoke ptr @ironwood_object_to_string(ptr ")
                    .append(operand(toString.object())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringCopyInstruction copy) {
            output.append(operand(copy.result()))
                    .append(" = invoke ptr @ironwood_string_copy(ptr ")
                    .append(operand(copy.source())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringCaseInstruction value) {
            output.append(operand(value.result())).append(" = invoke ptr @ironwood_string_case(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i1 ").append(operand(value.upper()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringRepeatInstruction value) {
            output.append(operand(value.result())).append(" = invoke ptr @ironwood_string_repeat(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i32 ").append(operand(value.count()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringReplaceCharInstruction value) {
            output.append(operand(value.result())).append(" = invoke ptr @ironwood_string_replace_char(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", i16 ").append(operand(value.oldChar()))
                    .append(", i16 ").append(operand(value.newChar()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringReplaceTextInstruction value) {
            output.append(operand(value.result())).append(" = invoke ptr @ironwood_string_replace_text(")
                    .append("ptr ").append(operand(value.source()))
                    .append(", ptr ").append(operand(value.target()))
                    .append(", ptr ").append(operand(value.replacement()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringJoinInstruction value) {
            output.append(operand(value.result())).append(" = invoke ptr @ironwood_string_join(")
                    .append("ptr ").append(operand(value.delimiter()))
                    .append(", ptr ").append(operand(value.elements()))
                    .append(", ptr ").append(typeInfoName("ironwood.lang.String"))
                    .append(", ptr ").append(allocationFailureName())
                    .append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringFromUtf8Instruction snapshot) {
            output.append(operand(snapshot.result()))
                    .append(" = invoke ptr @ironwood_string_from_utf8(ptr ")
                    .append(operand(snapshot.bytes())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringFromCharsInstruction snapshot) {
            output.append(operand(snapshot.result()))
                    .append(" = invoke ptr @ironwood_string_from_chars(ptr ")
                    .append(operand(snapshot.characters())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringFromCharRangeInstruction snapshot) {
            output.append(operand(snapshot.result()))
                    .append(" = invoke ptr @ironwood_string_from_char_range(ptr ")
                    .append(operand(snapshot.characters())).append(", i32 ")
                    .append(operand(snapshot.offset())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringFromRangeInstruction snapshot) {
            output.append(operand(snapshot.result()))
                    .append(" = invoke ptr @ironwood_string_from_range(ptr ")
                    .append(operand(snapshot.source())).append(", i32 ")
                    .append(operand(snapshot.beginIndex())).append(", i32 ")
                    .append(operand(snapshot.length())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrStringConcatInstruction concatenation) {
            emitStringConcatenation(output, concatenation, scratchNames, "invoke", suffix);
            return;
        }
        if (call instanceof IrStringFromIntegerInstruction formatting) {
            emitIntegerString(output, formatting, "invoke", suffix);
            return;
        }
        if (call instanceof IrStringFromCharacterInstruction formatting) {
            emitCharacterString(output, formatting, "invoke", suffix);
            return;
        }
        if (call instanceof IrSystemGetenvInstruction getenv) {
            output.append(operand(getenv.result()))
                    .append(" = invoke ptr @ironwood_system_getenv(ptr ")
                    .append(operand(getenv.name())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrSystemPropertyInstruction property) {
            output.append(operand(property.result()))
                    .append(" = invoke ptr @ironwood_system_get_property(ptr ")
                    .append(operand(property.name())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')').append(suffix);
            return;
        }
        if (call instanceof IrThrowableTraceInstruction trace
                && trace.operation() == IrThrowableTraceInstruction.Operation.ARRAY) {
            output.append(operand(trace.result().orElseThrow()))
                    .append(" = invoke ptr @ironwood_throwable_trace_array(ptr ")
                    .append(operand(trace.arguments().getFirst()))
                    .append(", ptr ")
                    .append(typeInfoName("ironwood.lang.StackTraceElement[]"))
                    .append(", ptr ").append(allocationFailureName()).append(')')
                    .append(suffix);
            return;
        }
        if (call instanceof IrStreamInstruction stream) {
            emitStreamInstruction(output, stream, "invoke", suffix);
            return;
        }
        if (call instanceof IrFileInstruction file) {
            emitFileInstruction(output, file, "invoke", suffix);
            return;
        }
        if (call instanceof IrCallInstruction direct) {
            direct.result().ifPresent(result -> output.append(operand(result)).append(" = "));
            output.append("invoke ").append(llvmType(direct.returnType())).append(' ')
                    .append(functionName(direct.targetLinkageName())).append('(')
                    .append(direct.arguments().stream()
                            .map(argument -> llvmType(argument.type()) + " " + operand(argument))
                            .collect(Collectors.joining(", ")))
                    .append(')').append(suffix);
            return;
        }
        String debugSuffix = ", !dbg !" + traceSite.callLocationMetadata();
        if (call instanceof IrVirtualCallInstruction virtual) {
            List<DispatchReceiver> guarded = guardPlan(virtual);
            if (guarded != null) {
                emitGuardedDispatch(output, virtual.result(), virtual.slot().index(), virtual.returnType(),
                        virtual.arguments(), guarded, scratchNames, "invoke", debugSuffix, invoke);
                return;
            }
            emitIndirectCall(output, virtual.result(), virtual.slot().index(), virtual.returnType(),
                    virtual.arguments(), scratchNames, "virtual.invoke", "invoke", suffix);
            return;
        }
        IrInterfaceCallInstruction interfaceCall = (IrInterfaceCallInstruction) call;
        List<DispatchReceiver> guarded = guardPlan(interfaceCall);
        if (guarded != null) {
            emitGuardedDispatch(output, interfaceCall.result(), interfaceCall.slot().index(),
                    interfaceCall.returnType(), interfaceCall.arguments(), guarded, scratchNames,
                    "invoke", debugSuffix, invoke);
            return;
        }
        emitIndirectCall(output, interfaceCall.result(), interfaceCall.slot().index(),
                interfaceCall.returnType(), interfaceCall.arguments(), scratchNames,
                "interface.invoke", "invoke", suffix);
    }

    private static boolean canFailAllocation(IrInstruction instruction) {
        return instruction instanceof IrAllocateInstruction
                || instruction instanceof IrArrayAllocateInstruction
                || instruction instanceof IrObjectToStringInstruction
                || instruction instanceof IrThrowableDescriptionInstruction
                || instruction instanceof IrStringCopyInstruction
                || instruction instanceof IrStringFromCharsInstruction
                || instruction instanceof IrStringCaseInstruction
                || instruction instanceof IrStringRepeatInstruction
                || instruction instanceof IrStringReplaceCharInstruction
                || instruction instanceof IrStringReplaceTextInstruction
                || instruction instanceof IrStringJoinInstruction
                || instruction instanceof IrStringFromUtf8Instruction
                || instruction instanceof IrStringFromCharRangeInstruction
                || instruction instanceof IrStringFromRangeInstruction
                || instruction instanceof IrStringFromIntegerInstruction
                || instruction instanceof IrStringFromCharacterInstruction
                || instruction instanceof IrStringConcatInstruction
                || instruction instanceof IrSystemGetenvInstruction
                || instruction instanceof IrSystemPropertyInstruction
                || instruction instanceof IrThrowableTraceInstruction trace
                && trace.operation() == IrThrowableTraceInstruction.Operation.ARRAY
                || instruction instanceof IrFileInstruction
                || instruction instanceof IrStreamInstruction;
    }

    private void emitIntegerString(StringBuilder output, IrStringFromIntegerInstruction formatting,
                                    String operation, String suffix) {
        output.append(operand(formatting.result())).append(" = ").append(operation)
                .append(" ptr @ironwood_string_from_integer(i64 ")
                .append(operand(formatting.value())).append(", i32 ")
                .append(operand(formatting.radix())).append(", ptr ")
                .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                .append(allocationFailureName()).append(')').append(suffix);
    }

    private void emitCharacterString(StringBuilder output, IrStringFromCharacterInstruction formatting,
                                      String operation, String suffix) {
        output.append(operand(formatting.result())).append(" = ").append(operation)
                .append(" ptr @ironwood_string_from_character(i16 ")
                .append(operand(formatting.value())).append(", ptr ")
                .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                .append(allocationFailureName()).append(')').append(suffix);
    }

    private void emitFloatingParseInstruction(StringBuilder output,
                                              IrFloatingParseInstruction parse) {
        boolean single = parse.result().type().equals(IrType.F32);
        output.append(operand(parse.result())).append(" = call ")
                .append(single ? "float @ironwood_parse_float(ptr "
                        : "double @ironwood_parse_double(ptr ")
                .append(operand(parse.text())).append(')');
    }

    private void emitStreamInstruction(StringBuilder output, IrStreamInstruction stream,
                                       String callKind, String suffix) {
        output.append(operand(stream.result())).append(" = ").append(callKind)
                .append(' ').append(llvmType(stream.result().type())).append(" @")
                .append(stream.operation().runtimeName()).append('(');
        for (int index = 0; index < stream.arguments().size(); index++) {
            if (index > 0) { output.append(", "); }
            IrOperand argument = stream.arguments().get(index);
            output.append(llvmType(argument.type())).append(' ').append(operand(argument));
        }
        if (stream.operation() == IrStreamInstruction.Operation.OPEN) {
            output.append(", ptr ").append(allocationFailureName());
        }
        output.append(')').append(suffix);
    }

    private void emitFileInstruction(StringBuilder output, IrFileInstruction file,
                                     String callKind, String suffix) {
        output.append(operand(file.result())).append(" = ").append(callKind).append(' ');
        switch (file.operation()) {
            case READ_ALL_BYTES -> output.append("ptr @ironwood_file_read_all_bytes(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName(IrType.array(IrType.I8).erasure().displayName()))
                    .append(", ptr ").append(allocationFailureName()).append(')');
            case READ_STRING -> output.append("ptr @ironwood_file_read_string(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case WRITE_BYTES -> output.append("i32 @ironwood_file_write_bytes(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case WRITE_STRING -> output.append("i32 @ironwood_file_write_string(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case WRITE_CHARS -> output.append("i32 @ironwood_file_write_chars(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case DELETE -> output.append("i32 @ironwood_file_delete(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case CREATE_DIRECTORIES -> output.append("i32 @ironwood_file_create_directories(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case COPY -> output.append("i32 @ironwood_file_copy(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case MOVE -> output.append("i32 @ironwood_file_move(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case OPEN_DIRECTORY -> output.append("i64 @ironwood_directory_open(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case DIRECTORY_HAS_NEXT -> output.append("i32 @ironwood_directory_has_next(i64 ")
                    .append(operand(file.path().orElseThrow())).append(')');
            case NEXT_DIRECTORY_ENTRY -> output.append("ptr @ironwood_directory_next(i64 ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case CLOSE_DIRECTORY -> output.append("i32 @ironwood_directory_close(i64 ")
                    .append(operand(file.path().orElseThrow())).append(')');
            case READ_ATTRIBUTES -> output.append("ptr @ironwood_file_read_attributes(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", i1 ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(typeInfoName(IrType.array(IrType.I64).erasure().displayName()))
                    .append(", ptr ").append(allocationFailureName()).append(')');
            case SAME_FILE -> output.append("i32 @ironwood_file_same(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case FILE_KIND -> output.append("i32 @ironwood_file_kind(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case FILE_KIND_NOFOLLOW -> output.append("i32 @ironwood_file_kind_nofollow(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case FILE_SIZE -> output.append("i64 @ironwood_file_size(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case LAST_ERROR -> output.append("i32 @ironwood_file_last_error()");
            case CURRENT_DIRECTORY -> output.append("ptr @ironwood_path_current_directory(ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case NORMALIZE_SYNTAX -> output.append("ptr @ironwood_path_normalize_syntax(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case NORMALIZE_PATH -> output.append("ptr @ironwood_path_normalize(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case FILE_NAME -> output.append("ptr @ironwood_path_file_name(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case PARENT -> output.append("ptr @ironwood_path_parent(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case RESOLVE -> output.append("ptr @ironwood_path_resolve(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case RESOLVE_SIBLING -> output.append("ptr @ironwood_path_resolve_sibling(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(operand(file.value().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
            case ABSOLUTE_PATH -> output.append("ptr @ironwood_path_absolute(ptr ")
                    .append(operand(file.path().orElseThrow())).append(", ptr ")
                    .append(typeInfoName("ironwood.lang.String")).append(", ptr ")
                    .append(allocationFailureName()).append(')');
        }
        output.append(suffix);
    }

    private String operand(IrOperand value) {
        if (value instanceof IrConstant constant) {
            if (constant.type().equals(IrType.I1)) {
                return constant.value().intValue() == 0 ? "false" : "true";
            }
            if (constant.type().equals(IrType.F32)) {
                return floatingConstant(constant.value().floatValue());
            }
            if (constant.type().equals(IrType.F64)) {
                return floatingConstant(constant.value().doubleValue());
            }
            return Long.toString(constant.value().longValue());
        }
        if (value instanceof IrNull) {
            return "null";
        }
        if (value instanceof IrStringConstant constant) {
            return stringLiteralName(constant.id());
        }
        if (value instanceof IrEnumConstant constant) {
            return enumConstantName(constant);
        }
        if (value instanceof IrImmortalObject object) {
            return immortalObjectName(object);
        }
        if (value instanceof IrValueReference reference) {
            return "%v" + reference.id();
        }
        throw new IllegalStateException("unsupported IR operand " + value.getClass().getSimpleName());
    }

    private String functionName(String linkageName) {
        return "@\"" + escapeString(linkageName) + "\"";
    }

    private void emitThrowableTraceMetadata(StringBuilder output, IrProgram program) {
        Map<String, IrClass> classes = program.classes().stream()
                .collect(Collectors.toMap(IrClass::name, type -> type));
        IrClass throwable = classes.get("ironwood.lang.Throwable");
        List<TracePublicEntry> publicEntries = classes.containsKey("ironwood.lang.StackTraceElement")
                ? tracePublicEntries(program) : List.of();
        LinkedHashSet<IrFunction> publicFunctions = publicEntries.stream()
                .map(TracePublicEntry::function)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (IrFunction function : publicFunctions) {
            emitTraceString(output, tracePublicClassNameConstant(function), function.ownerClass());
            emitTraceString(output, tracePublicMethodNameConstant(function), traceMethodName(function));
            emitTraceString(output, tracePublicFileNameConstant(function), function.sourceFileName());
        }
        for (TracePublicEntry entry : publicEntries) {
            output.append(tracePublicElementName(entry)).append(" = private constant ")
                    .append(classType("ironwood.lang.StackTraceElement"))
                    .append(" { ptr ").append(typeInfoName("ironwood.lang.StackTraceElement"))
                    .append(", ptr ").append(tracePublicClassNameConstant(entry.function()))
                    .append(", ptr ").append(tracePublicMethodNameConstant(entry.function()))
                    .append(", ptr ").append(tracePublicFileNameConstant(entry.function()))
                    .append(", i32 ").append(entry.line()).append(" }\n");
        }
        Map<String, TracePublicEntry> publicBySite = publicEntries.stream()
                .collect(Collectors.toMap(entry -> tracePublicKey(entry.function(), entry.line()),
                        entry -> entry));
        output.append("@ironwood_trace_sites = private constant [")
                .append(tracePlan.sites().size())
                .append(" x { i64, ptr, ptr, ptr, i32, i32, i32, i32 }] ");
        if (tracePlan.sites().isEmpty()) {
            output.append("zeroinitializer\n");
        } else {
            output.append('[').append(tracePlan.sites().stream().map(site -> {
                IrFunction function = site.function();
                IrClass owner = classes.get(function.ownerClass());
                boolean throwableHelper = throwable != null && owner != null
                        && owner.typeMembership().contains(throwable.typeId())
                        && (function.sourceName().equals("fillInStackTrace")
                        || function.sourceName().equals("captureTrace"));
                boolean hidden = function.constructor() || throwableHelper;
                TracePublicEntry publicEntry = publicBySite.get(tracePublicKey(function, site.line()));
                return "{ i64, ptr, ptr, ptr, i32, i32, i32, i32 } { i64 " + site.guid()
                        + ", ptr " + traceCallableNameConstant(function)
                        + ", ptr " + traceFileNameConstant(function)
                        + ", ptr " + (publicEntry == null ? "null" : tracePublicElementName(publicEntry))
                        + ", i32 " + site.probeIndex()
                        + ", i32 " + site.line()
                        + ", i32 " + (owner == null ? 0 : owner.typeId())
                        + ", i32 " + (hidden ? 1 : 0) + " }";
            }).collect(Collectors.joining(", "))).append("]\n");
        }
    }

    private static String tracePublicKey(IrFunction function, int line) {
        return function.linkageName() + "\n" + line;
    }

    private List<TracePublicEntry> tracePublicEntries(IrProgram program) {
        List<TracePublicEntry> result = new ArrayList<>();
        int index = 0;
        for (IrFunction function : program.functions()) {
            LinkedHashSet<Integer> lines = new LinkedHashSet<>();
            lines.add(function.sourceSpan().start().line());
            for (IrBasicBlock block : function.blocks()) {
                block.instructions().stream().filter(LlvmEmitter::writesTraceLine)
                        .map(instruction -> instruction.sourceSpan().start().line())
                        .forEach(lines::add);
                if (block.terminator() instanceof IrInvokeTerminator
                        || block.terminator() instanceof IrThrowTerminator) {
                    lines.add(block.terminator().sourceSpan().start().line());
                }
            }
            for (int line : lines) {
                result.add(new TracePublicEntry(index++, function, line));
            }
        }
        return List.copyOf(result);
    }

    private void emitTraceString(StringBuilder output, String symbol, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.append(symbol).append(" = private constant { ptr, i32, i32, [")
                .append(value.length()).append(" x i16] } { ptr ")
                .append(typeInfoName("ironwood.lang.String"))
                .append(", i32 ").append(value.length())
                .append(", i32 ").append(utf8.length)
                .append(", [").append(value.length()).append(" x i16] ");
        if (value.isEmpty()) {
            output.append("zeroinitializer");
        } else {
            output.append('[');
            for (int index = 0; index < value.length(); index++) {
                if (index > 0) output.append(", ");
                output.append("i16 ").append((int) value.charAt(index));
            }
            output.append(']');
        }
        output.append(" }\n");
    }

    private static boolean writesTraceLine(IrInstruction instruction) {
        return instruction instanceof IrCallInstruction
                || instruction instanceof IrVirtualCallInstruction
                || instruction instanceof IrInterfaceCallInstruction
                || instruction instanceof IrEnsureTypeInitializedInstruction
                || instruction instanceof IrThrowableTraceInstruction trace
                && trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                || canFailAllocation(instruction);
    }

    private static String traceMethodName(IrFunction function) {
        return switch (function.kind()) {
            case CONSTRUCTOR -> "<init>";
            case DESTRUCTOR -> "<destructor>";
            case CLASS_INITIALIZER -> "<clinit>";
            case CONSTRUCTOR_ROLLBACK -> "<constructor-rollback>";
            case METHOD -> function.sourceName();
        };
    }

    private static boolean isStackCaptureCall(String linkageName) {
        return linkageName.contains(".fillInStackTrace")
                || linkageName.contains(".captureTrace");
    }

    private String tracePublicClassNameConstant(IrFunction function) {
        return "@\"ironwood.trace.public.class."
                + escapeString(function.linkageName()) + "\"";
    }

    private String tracePublicMethodNameConstant(IrFunction function) {
        return "@\"ironwood.trace.public.method."
                + escapeString(function.linkageName()) + "\"";
    }

    private String tracePublicFileNameConstant(IrFunction function) {
        return "@\"ironwood.trace.public.file."
                + escapeString(function.linkageName()) + "\"";
    }

    private String tracePublicElementName(TracePublicEntry entry) {
        return "@\"ironwood.trace.public.element." + entry.index() + "\"";
    }

    private void emitTraceMetadata(StringBuilder output, IrFunction function) {
        emitCString(output, traceCallableNameConstant(function), function.traceCallableName());
        emitCString(output, traceFileNameConstant(function), function.sourceFileName());
    }

    private void emitCString(StringBuilder output, String symbol, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.append(symbol).append(" = private unnamed_addr constant [")
                .append(bytes.length + 1).append(" x i8] c\"")
                .append(escapeBytes(bytes)).append("\\00\"\n");
    }

    private String traceCallableNameConstant(IrFunction function) {
        return "@\"ironwood.trace.callable." + escapeString(function.linkageName()) + "\"";
    }

    private String traceFileNameConstant(IrFunction function) {
        return "@\"ironwood.trace.file." + escapeString(function.linkageName()) + "\"";
    }

    private String classType(String className) {
        return "%\"ironwood.class." + escapeString(className) + "\"";
    }

    private String typeInfoName(String className) {
        return "@\"ironwood.typeinfo." + escapeString(className) + "\"";
    }

    private String typeNameConstant(String className) {
        return "@\"ironwood.typename." + escapeString(className) + "\"";
    }

    private String dispatchTableName(String className) {
        return "@\"ironwood.dispatch." + escapeString(className) + "\"";
    }

    private String membershipTableName(String className) {
        return "@\"ironwood.membership." + escapeString(className) + "\"";
    }

    private String stringLiteralName(int id) {
        return "@\"ironwood.string." + id + "\"";
    }

    private String immortalObjectName(IrImmortalObject object) {
        return "@\"ironwood.immortal." + escapeString(object.symbol()) + "\"";
    }

    private String allocationFailureName() {
        return "@\"ironwood.immortal.implicit allocation failure\"";
    }

    private String enumConstantName(IrEnumConstant constant) {
        return "@\"ironwood.enum." + escapeString(constant.symbol()) + "\"";
    }

    private static String zeroInitializer(IrType type) {
        return switch (type.kind()) {
            case I1 -> "false";
            case I8, I16, U16, I32, I64 -> "0";
            case F32, F64 -> "0.000000e+00";
            case NULL, EXCEPTION, REFERENCE, TYPE_PARAMETER, WILDCARD, ARRAY -> "null";
            case VOID -> throw new IllegalArgumentException("void field has no initializer");
        };
    }

    private static String staticFieldName(IrStaticField field) {
        return "@\"ironwood.static." + escapeString(field.ownerClass()) + "."
                + escapeString(field.name()) + "\"";
    }

    private void emitTypeInitializer(StringBuilder output,
                                     IrTypeInitialization initialization) {
        InitializerTraceScope initializerTrace = tracePlan.initializerScope(initialization.typeName());
        TraceScope traceScope = initializerTrace.scope();
        output.append("define internal void ")
                .append(typeInitializerName(initialization.typeName()))
                .append("() alwaysinline {\n")
                .append("entry:\n")
                .append("  %initialization.state = load i8, ptr ")
                .append(initializationStateName(initialization.typeName())).append("\n")
                .append("  %initialization.complete = icmp eq i8 %initialization.state, 2\n")
                .append("  %initialization.expected = call i1 @llvm.expect.i1(")
                .append("i1 %initialization.complete, i1 true)\n")
                .append("  br i1 %initialization.expected, label %already.initialized, ")
                .append("label %initialize\n")
                .append("initialize:\n")
                .append("  call void ")
                .append(slowTypeInitializerName(initialization.typeName()))
                .append("(i8 %initialization.state)\n")
                .append("  ret void\n")
                .append("already.initialized:\n")
                .append("  ret void\n")
                .append("}\n\n");

        // Metadata-only probes preserve source callers around the cold initialization path.
        output.append("define internal void ")
                .append(slowTypeInitializerName(initialization.typeName()))
                .append("(i8 %initialization.state) noinline ")
                .append("personality ptr @__gxx_personality_v0 !dbg !")
                .append(traceScope.subprogramMetadata()).append(" {\n")
                .append("entry:\n")
                .append("  ");
        emitTraceProbe(output, initializerTrace.guid(), 1, 0,
                traceScope.locationMetadata());
        output.append("\n  switch i8 %initialization.state, label %already.initialized [\n")
                .append("    i8 0, label %initialize\n")
                .append("    i8 3, label %previously.failed\n")
                .append("  ]\n")
                .append("initialize:\n")
                .append("  store i8 1, ptr ")
                .append(initializationStateName(initialization.typeName())).append("\n");

        int operation = 0;
        int callIndex = 0;
        for (String prerequisite : initialization.prerequisiteTypes()) {
            String next = "initialization.continue." + operation++;
            SyntheticTraceSite site = initializerTrace.callSites().get(callIndex++);
            output.append("  ");
            emitTraceProbe(output, initializerTrace.guid(), site.probeIndex(), 0,
                    site.locationMetadata());
            output.append("\n  invoke void ").append(typeInitializerName(prerequisite))
                    .append("() to label %").append(next)
                    .append(" unwind label %initialization.failed, !dbg !")
                    .append(site.callLocationMetadata()).append("\n")
                    .append(next).append(":\n");
        }
        if (initialization.initializerLinkageName().isPresent()) {
            String next = "initialization.continue." + operation;
            SyntheticTraceSite site = initializerTrace.callSites().get(callIndex);
            output.append("  ");
            emitTraceProbe(output, initializerTrace.guid(), site.probeIndex(), 0,
                    site.locationMetadata());
            output.append("\n  invoke void ")
                    .append(functionName(initialization.initializerLinkageName().orElseThrow()))
                    .append("() to label %").append(next)
                    .append(" unwind label %initialization.failed, !dbg !")
                    .append(site.callLocationMetadata()).append("\n")
                    .append(next).append(":\n");
        }
        output.append("  store i8 2, ptr ")
                .append(initializationStateName(initialization.typeName())).append("\n")
                .append("  ret void\n")
                .append("already.initialized:\n")
                .append("  ret void\n")
                .append("previously.failed:\n")
                .append("  %previous.failure = load ptr, ptr ")
                .append(initializationFailureName(initialization.typeName())).append("\n")
                .append("  call void @ironwood_throw(ptr %previous.failure)\n")
                .append("  unreachable\n")
                .append("initialization.failed:\n")
                .append("  %initialization.landing = landingpad { ptr, i32 } catch ptr null\n")
                .append("  %initialization.handle = extractvalue { ptr, i32 } ")
                .append("%initialization.landing, 0\n")
                .append("  %initialization.failure = call ptr @ironwood_exception_take(ptr ")
                .append("%initialization.handle)\n")
                .append("  store ptr %initialization.failure, ptr ")
                .append(initializationFailureName(initialization.typeName())).append("\n")
                .append("  store i8 3, ptr ")
                .append(initializationStateName(initialization.typeName())).append("\n")
                .append("  call void @ironwood_throw(ptr %initialization.failure)\n")
                .append("  unreachable\n")
                .append("}\n");
    }

    private String typeInitializerName(String typeName) {
        return functionName("ironwood.initialize." + typeName);
    }

    private String slowTypeInitializerName(String typeName) {
        return functionName("ironwood.initialize.slow." + typeName);
    }

    private static String initializationStateName(String typeName) {
        return "@\"ironwood.initialization.state." + escapeString(typeName) + "\"";
    }

    private static String initializationFailureName(String typeName) {
        return "@\"ironwood.initialization.failure." + escapeString(typeName) + "\"";
    }

    private String staticInitializer(IrStaticField field) {
        if (field.initialValue() instanceof IrEnumConstant) {
            return "null";
        }
        if (field.initialValue() instanceof IrConstant constant) {
            if (field.type().equals(IrType.F32)) {
                long bits = Double.doubleToRawLongBits((double) constant.value().floatValue());
                return String.format(java.util.Locale.ROOT, "0x%016X", bits);
            }
            if (field.type().equals(IrType.F64)) {
                long bits = Double.doubleToRawLongBits(constant.value().doubleValue());
                return String.format(java.util.Locale.ROOT, "0x%016X", bits);
            }
        }
        return operand(field.initialValue());
    }

    private static int arrayElementKind(IrType elementType) {
        return switch (elementType.erasure().kind()) {
            case I1 -> 1;
            case I8 -> 2;
            case I16 -> 3;
            case U16 -> 4;
            case I32 -> 5;
            case I64 -> 6;
            case F32 -> 7;
            case F64 -> 8;
            case REFERENCE, TYPE_PARAMETER, WILDCARD, ARRAY -> 9;
            case VOID, NULL, EXCEPTION -> throw new IllegalArgumentException(
                    "unsupported array element type " + elementType.displayName());
        };
    }

    private static String llvmType(IrType type) {
        return switch (type.kind()) {
            case I1 -> "i1";
            case I8 -> "i8";
            case I16, U16 -> "i16";
            case I32 -> "i32";
            case I64 -> "i64";
            case F32 -> "float";
            case F64 -> "double";
            case VOID -> "void";
            case NULL, EXCEPTION, REFERENCE, TYPE_PARAMETER, WILDCARD, ARRAY -> "ptr";
        };
    }

    private static String floatingConstant(double value) {
        if (Double.isNaN(value)) {
            return "0x7FF8000000000000";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "0x7FF0000000000000";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "0xFFF0000000000000";
        }
        return String.format(java.util.Locale.ROOT, "%.17e", value);
    }

    private static String escapeComment(String value) {
        return value.replace("'", "_").replace("\n", "_").replace("\r", "_");
    }

    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\22");
    }

    private static String escapeBytes(byte[] bytes) {
        StringBuilder escaped = new StringBuilder();
        for (byte raw : bytes) {
            int value = Byte.toUnsignedInt(raw);
            if (value >= 0x20 && value <= 0x7e && value != '"' && value != '\\') {
                escaped.append((char) value);
            } else {
                escaped.append('\\');
                String hex = Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT);
                if (hex.length() == 1) {
                    escaped.append('0');
                }
                escaped.append(hex);
            }
        }
        return escaped.toString();
    }

    private record TracePublicEntry(int index, IrFunction function, int line) {
    }

    private static final class TracePlan {
        private final IdentityHashMap<IrFunction, TraceFunction> functions = new IdentityHashMap<>();
        private final List<TraceFunction> orderedFunctions = new ArrayList<>();
        private final List<TraceSite> sites = new ArrayList<>();
        private final LinkedHashMap<String, Integer> fileMetadata = new LinkedHashMap<>();
        private final Map<String, InitializerTraceScope> initializerScopes = new LinkedHashMap<>();
        private final TraceScope nativeEntryScope;
        private final int moduleFlagMetadata;
        private final int emptyTypesMetadata;
        private final int subroutineTypeMetadata;
        private final int compileUnitMetadata;
        private int nextMetadata;

        private TracePlan(IrProgram program) {
            moduleFlagMetadata = nextMetadata++;
            emptyTypesMetadata = nextMetadata++;
            subroutineTypeMetadata = nextMetadata++;
            for (IrFunction function : program.functions()) {
                fileMetadata.computeIfAbsent(function.sourceFileName(), ignored -> nextMetadata++);
            }
            if (fileMetadata.isEmpty()) {
                fileMetadata.put("<unknown>.iron", nextMetadata++);
            }
            compileUnitMetadata = nextMetadata++;
            Map<Long, String> guidOwners = new HashMap<>();
            for (IrFunction function : program.functions()) {
                long guid = linkageGuid(function.linkageName());
                String collision = guidOwners.putIfAbsent(guid, function.linkageName());
                if (collision != null && !collision.equals(function.linkageName())) {
                    throw new IllegalArgumentException("stack-trace GUID collision between "
                            + collision + " and " + function.linkageName());
                }
                int subprogram = nextMetadata++;
                int descriptor = nextMetadata++;
                IdentityHashMap<Object, TraceSite> operationSites = new IdentityHashMap<>();
                TraceSite entry = createSite(function, guid, 1, 0,
                        function.sourceSpan(), operationSites, null);
                int probeIndex = 2;
                for (IrBasicBlock block : function.blocks()) {
                    for (IrInstruction instruction : block.instructions()) {
                        if (writesTraceLine(instruction)) {
                            validateProbeIndex(probeIndex, function);
                            createSite(function, guid, probeIndex++, 0,
                                    instruction.sourceSpan(),
                                    operationSites, instruction);
                        }
                    }
                    if (block.terminator() instanceof IrInvokeTerminator
                            || block.terminator() instanceof IrThrowTerminator) {
                        validateProbeIndex(probeIndex, function);
                        createSite(function, guid, probeIndex++, 0,
                                block.terminator().sourceSpan(),
                                operationSites, block.terminator());
                    }
                }
                TraceFunction traceFunction = new TraceFunction(function, guid, subprogram,
                        descriptor, entry, operationSites);
                functions.put(function, traceFunction);
                orderedFunctions.add(traceFunction);
            }
            int primaryFile = fileMetadata.values().iterator().next();
            for (IrTypeInitialization initialization : program.typeInitializations()) {
                IrFunction sourceFunction = program.functions().stream()
                        .filter(function -> function.ownerClass().equals(initialization.typeName()))
                        .findFirst().orElse(null);
                int file = sourceFunction == null ? primaryFile
                        : fileMetadata.get(sourceFunction.sourceFileName());
                int line = sourceFunction == null ? 1 : sourceFunction.sourceSpan().start().line();
                String name = "ironwood.initialize.slow." + initialization.typeName();
                int callCount = initialization.prerequisiteTypes().size()
                        + (initialization.initializerLinkageName().isPresent() ? 1 : 0);
                initializerScopes.put(initialization.typeName(),
                        createInitializerScope(name, file, line, callCount));
            }
            IrFunction entryPoint = program.entryPoint().orElse(null);
            nativeEntryScope = entryPoint == null ? null : createScope("main", "main",
                    fileMetadata.get(entryPoint.sourceFileName()),
                    entryPoint.sourceSpan().start().line());
        }

        private TraceScope createScope(String name, String linkageName, int file, int line) {
            return new TraceScope(name, linkageName, file, line, nextMetadata++, nextMetadata++);
        }

        private InitializerTraceScope createInitializerScope(String name, int file, int line,
                                                              int callCount) {
            TraceScope scope = createScope(name, name, file, line);
            long guid = linkageGuid(name);
            int descriptor = nextMetadata++;
            List<SyntheticTraceSite> callSites = new ArrayList<>();
            for (int index = 0; index < callCount; index++) {
                callSites.add(new SyntheticTraceSite(index + 2, nextMetadata++,
                        nextMetadata++, nextMetadata++));
            }
            return new InitializerTraceScope(scope, guid, descriptor, List.copyOf(callSites));
        }

        private TraceSite createSite(IrFunction function, long guid, int probeIndex, int probeType,
                                     ironwood.compiler.source.SourceSpan span,
                                     IdentityHashMap<Object, TraceSite> operationSites, Object operation) {
            int location = nextMetadata++;
            int callScope = nextMetadata++;
            int callLocation = nextMetadata++;
            TraceSite site = new TraceSite(function, guid, probeIndex, probeType,
                    span.start().line(), span.start().column(), location, callScope, callLocation);
            sites.add(site);
            if (operation != null) {
                operationSites.put(operation, site);
            }
            return site;
        }

        private static void validateProbeIndex(int probeIndex, IrFunction function) {
            if (probeIndex > 0xffff) {
                throw new IllegalArgumentException("too many trace sites in " + function.linkageName());
            }
        }

        private TraceFunction function(IrFunction function) {
            TraceFunction result = functions.get(function);
            if (result == null) {
                throw new IllegalArgumentException("missing trace function " + function.linkageName());
            }
            return result;
        }

        private TraceSite site(IrFunction function, Object operation) {
            TraceSite result = function(function).operationSites().get(operation);
            if (result == null) {
                throw new IllegalArgumentException("missing trace site in " + function.linkageName());
            }
            return result;
        }

        private List<TraceSite> sites() {
            return List.copyOf(sites);
        }

        private InitializerTraceScope initializerScope(String typeName) {
            InitializerTraceScope result = initializerScopes.get(typeName);
            if (result == null) {
                throw new IllegalArgumentException("missing initializer trace scope for " + typeName);
            }
            return result;
        }

        private TraceScope nativeEntryScope() {
            if (nativeEntryScope == null) {
                throw new IllegalArgumentException("missing native entry trace scope");
            }
            return nativeEntryScope;
        }

        private void emitDebugMetadata(StringBuilder output) {
            output.append("\n!llvm.module.flags = !{!").append(moduleFlagMetadata).append("}\n")
                    .append("!llvm.dbg.cu = !{!").append(compileUnitMetadata).append("}\n")
                    .append("!llvm.pseudo_probe_desc = !{")
                    .append(orderedFunctions.stream().map(function -> "!" + function.descriptorMetadata())
                            .collect(Collectors.joining(", ")));
            if (!orderedFunctions.isEmpty() && !initializerScopes.isEmpty()) {
                output.append(", ");
            }
            output.append(initializerScopes.values().stream()
                            .map(scope -> "!" + scope.descriptorMetadata())
                            .collect(Collectors.joining(", ")))
                    .append("}\n\n")
                    .append('!').append(moduleFlagMetadata)
                    .append(" = !{i32 2, !\"Debug Info Version\", i32 3}\n")
                    .append('!').append(emptyTypesMetadata).append(" = !{}\n")
                    .append('!').append(subroutineTypeMetadata)
                    .append(" = !DISubroutineType(types: !").append(emptyTypesMetadata).append(")\n");
            for (Map.Entry<String, Integer> file : fileMetadata.entrySet()) {
                output.append('!').append(file.getValue()).append(" = !DIFile(filename: \"")
                        .append(escapeString(file.getKey())).append("\", directory: \"\")\n");
            }
            int primaryFile = fileMetadata.values().iterator().next();
            output.append('!').append(compileUnitMetadata)
                    .append(" = distinct !DICompileUnit(language: DW_LANG_Java, file: !")
                    .append(primaryFile)
                    .append(", producer: \"Ironwood\", isOptimized: true, runtimeVersion: 0, "
                            + "emissionKind: NoDebug)\n");
            for (TraceFunction function : orderedFunctions) {
                IrFunction irFunction = function.function();
                int file = fileMetadata.get(irFunction.sourceFileName());
                int line = irFunction.sourceSpan().start().line();
                output.append('!').append(function.subprogramMetadata())
                        .append(" = distinct !DISubprogram(name: \"")
                        .append(escapeString(irFunction.traceCallableName()))
                        .append("\", linkageName: \"").append(escapeString(irFunction.linkageName()))
                        .append("\", scope: !").append(file).append(", file: !").append(file)
                        .append(", line: ").append(line).append(", type: !")
                        .append(subroutineTypeMetadata).append(", scopeLine: ").append(line)
                        .append(", flags: DIFlagPrototyped, spFlags: DISPFlagLocalToUnit | "
                                + "DISPFlagDefinition | DISPFlagOptimized, unit: !")
                        .append(compileUnitMetadata).append(")\n");
                output.append('!').append(function.descriptorMetadata())
                        .append(" = !{i64 ").append(function.guid())
                        .append(", i64 0, !\"").append(escapeString(irFunction.linkageName()))
                        .append("\"}\n");
                for (TraceSite site : sites.stream()
                        .filter(candidate -> candidate.function() == irFunction).toList()) {
                    output.append('!').append(site.locationMetadata())
                            .append(" = !DILocation(line: ").append(site.line())
                            .append(", column: ").append(site.column())
                            .append(", scope: !").append(function.subprogramMetadata()).append(")\n")
                            .append('!').append(site.callScopeMetadata())
                            .append(" = !DILexicalBlockFile(scope: !")
                            .append(function.subprogramMetadata()).append(", file: !").append(file)
                            .append(", discriminator: ")
                            .append(callSiteDiscriminator(site.probeIndex(), site.probeType()))
                            .append(")\n")
                            .append('!').append(site.callLocationMetadata())
                            .append(" = !DILocation(line: ").append(site.line())
                            .append(", column: ").append(site.column())
                            .append(", scope: !").append(site.callScopeMetadata()).append(")\n");
                }
            }
            initializerScopes.values().forEach(initializer -> {
                TraceScope scope = initializer.scope();
                emitScope(output, scope, true);
                output.append('!').append(initializer.descriptorMetadata())
                        .append(" = !{i64 ").append(initializer.guid())
                        .append(", i64 0, !\"").append(escapeString(scope.linkageName()))
                        .append("\"}\n");
                for (SyntheticTraceSite site : initializer.callSites()) {
                    output.append('!').append(site.locationMetadata())
                            .append(" = !DILocation(line: ").append(scope.line())
                            .append(", column: 1, scope: !")
                            .append(scope.subprogramMetadata()).append(")\n")
                            .append('!').append(site.callScopeMetadata())
                            .append(" = !DILexicalBlockFile(scope: !")
                            .append(scope.subprogramMetadata()).append(", file: !")
                            .append(scope.fileMetadata()).append(", discriminator: ")
                            .append(callSiteDiscriminator(site.probeIndex(), 0)).append(")\n")
                            .append('!').append(site.callLocationMetadata())
                            .append(" = !DILocation(line: ").append(scope.line())
                            .append(", column: 1, scope: !")
                            .append(site.callScopeMetadata()).append(")\n");
                }
            });
            if (nativeEntryScope != null) { emitScope(output, nativeEntryScope, false); }
        }

        private void emitScope(StringBuilder output, TraceScope scope, boolean local) {
            output.append('!').append(scope.subprogramMetadata())
                    .append(" = distinct !DISubprogram(name: \"")
                    .append(escapeString(scope.name())).append("\", linkageName: \"")
                    .append(escapeString(scope.linkageName())).append("\", scope: !")
                    .append(scope.fileMetadata()).append(", file: !").append(scope.fileMetadata())
                    .append(", line: ").append(scope.line()).append(", type: !")
                    .append(subroutineTypeMetadata).append(", scopeLine: ").append(scope.line())
                    .append(", flags: DIFlagPrototyped, spFlags: ")
                    .append(local ? "DISPFlagLocalToUnit | " : "")
                    .append("DISPFlagDefinition | DISPFlagOptimized, unit: !")
                    .append(compileUnitMetadata).append(")\n")
                    .append('!').append(scope.locationMetadata())
                    .append(" = !DILocation(line: ").append(scope.line())
                    .append(", column: 1, scope: !").append(scope.subprogramMetadata()).append(")\n");
        }

        private static int callSiteDiscriminator(int probeIndex, int probeType) {
            return (probeIndex << 3) | (100 << 19) | (probeType << 26) | 0x7;
        }

        private static long linkageGuid(String linkageName) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(linkageName.getBytes(StandardCharsets.UTF_8));
                long guid = 0;
                for (int index = 0; index < Long.BYTES; index++) {
                    guid |= (long) Byte.toUnsignedInt(digest[index]) << (index * Byte.SIZE);
                }
                return guid;
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("MD5 is required for LLVM pseudo probes", exception);
            }
        }
    }

    private record TraceFunction(IrFunction function, long guid, int subprogramMetadata,
                                 int descriptorMetadata, TraceSite entry,
                                 IdentityHashMap<Object, TraceSite> operationSites) {
    }

    private record TraceSite(IrFunction function, long guid, int probeIndex, int probeType,
                             int line, int column,
                             int locationMetadata, int callScopeMetadata,
                             int callLocationMetadata) {
    }

    private record TraceScope(String name, String linkageName, int fileMetadata, int line,
                              int subprogramMetadata, int locationMetadata) {
    }

    private record InitializerTraceScope(TraceScope scope, long guid, int descriptorMetadata,
                                         List<SyntheticTraceSite> callSites) {
    }

    private record SyntheticTraceSite(int probeIndex, int locationMetadata,
                                      int callScopeMetadata, int callLocationMetadata) {
    }

    /*
     * Cold-path outlining and guarded dispatch.
     *
     * Implicit failure paths (null, bounds, length, division, cast checks) and
     * explicit `throw new X(...)` statements lower to allocation, construction,
     * and throw sequences. Left inline, those cold sequences dominate LLVM's
     * inline cost for small hot methods such as list access or pool reuse, so
     * the emitter moves each such sequence into one shared cold, noinline,
     * noreturn helper per constructor. The helper carries no trace probes, so
     * the runtime skips its frame and the throw site keeps its source line.
     */

    private record DispatchReceiver(String typeName, String targetLinkageName) {
    }

    private record ThrowHelper(String name, String className, String constructorLinkageName,
                               List<String> parameterTypes, boolean ensureInitialized,
                               boolean rollback) {
    }

    private record OutlinedThrow(ThrowHelper helper, List<IrOperand> arguments,
                                 List<Object> probeOperations) {
    }

    private record ExplicitThrow(OutlinedThrow outlined, List<String> absorbed) {
    }

    private record BlockEdges(String normalPredecessor, List<String> unwindPredecessors) {
    }

    private record FunctionLayout(Map<String, OutlinedThrow> outlined, Set<String> absorbed,
                                  Map<String, BlockEdges> edges, Map<String, IrBasicBlock> blocks) {
    }

    private void planDispatchReceivers(IrProgram program) {
        Set<String> instantiable = new HashSet<>();
        instantiable.add("ironwood.lang.String");
        instantiable.add("ironwood.lang.StackTraceElement");
        program.allocationFailure().ifPresent(failure ->
                instantiable.add(failure.storageType().referenceName()));
        for (IrStaticField field : program.staticFields()) {
            if (field.initialValue() instanceof IrImmortalObject object) {
                instantiable.add(object.storageType().referenceName());
            } else if (field.initialValue() instanceof IrEnumConstant constant) {
                instantiable.add(constant.storageType().referenceName());
            }
        }
        for (IrFunction function : program.functions()) {
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction instanceof IrAllocateInstruction allocate) {
                        instantiable.add(allocate.className());
                    }
                }
                if (block.terminator() instanceof IrInvokeTerminator invoke
                        && invoke.call() instanceof IrAllocateInstruction allocate) {
                    instantiable.add(allocate.className());
                }
            }
        }
        Map<Integer, List<DispatchReceiver>> receivers = new HashMap<>();
        for (IrClass irClass : program.classes()) {
            if (!irClass.isClass() || !instantiable.contains(irClass.name())) {
                continue;
            }
            for (IrDispatchEntry entry : irClass.dispatchEntries()) {
                receivers.computeIfAbsent(entry.slot().index(), ignored -> new ArrayList<>())
                        .add(new DispatchReceiver(irClass.name(), entry.targetLinkageName()));
            }
        }
        for (IrArrayType arrayType : program.arrayTypes()) {
            for (IrDispatchEntry entry : arrayType.dispatchEntries()) {
                receivers.computeIfAbsent(entry.slot().index(), ignored -> new ArrayList<>())
                        .add(new DispatchReceiver(arrayType.name(), entry.targetLinkageName()));
            }
        }
        slotReceivers = receivers;
    }

    private List<DispatchReceiver> guardPlan(IrInstruction call) {
        int slot;
        if (call instanceof IrVirtualCallInstruction virtual) {
            slot = virtual.slot().index();
        } else if (call instanceof IrInterfaceCallInstruction interfaceCall) {
            slot = interfaceCall.slot().index();
        } else {
            return null;
        }
        List<DispatchReceiver> receivers = slotReceivers.get(slot);
        if (receivers == null || receivers.isEmpty() || receivers.size() > MAX_GUARDED_RECEIVERS) {
            return null;
        }
        long targets = receivers.stream().map(DispatchReceiver::targetLinkageName).distinct().count();
        return targets > MAX_GUARDED_TARGETS ? null : receivers;
    }

    private static String guardBase(String blockLabel, int ordinal) {
        return blockLabel + ".guard." + ordinal;
    }

    private static List<String> guardCallLabels(String blockLabel, int ordinal,
                                                List<DispatchReceiver> receivers) {
        String base = guardBase(blockLabel, ordinal);
        long targets = receivers.stream().map(DispatchReceiver::targetLinkageName).distinct().count();
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < targets; index++) {
            labels.add(base + ".call." + index);
        }
        labels.add(base + ".fallback");
        return labels;
    }

    private FunctionLayout planFunctionLayout(IrFunction function) {
        Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        Map<String, Integer> references = new HashMap<>();
        for (IrBasicBlock block : function.blocks()) {
            blocks.put(block.label(), block);
            for (String successor : successors(block.terminator())) {
                references.merge(successor, 1, Integer::sum);
            }
        }
        Map<String, OutlinedThrow> outlined = new LinkedHashMap<>();
        Set<String> absorbed = new HashSet<>();
        for (IrBasicBlock block : function.blocks()) {
            ExplicitThrow explicit = matchExplicitThrow(block, blocks, references);
            if (explicit != null) {
                outlined.put(block.label(), explicit.outlined());
                absorbed.addAll(explicit.absorbed());
            }
        }
        for (IrBasicBlock block : function.blocks()) {
            if (absorbed.contains(block.label()) || outlined.containsKey(block.label())) {
                continue;
            }
            OutlinedThrow bundled = matchBundledThrow(block);
            if (bundled != null) {
                outlined.put(block.label(), bundled);
            }
        }
        Map<String, BlockEdges> edges = new HashMap<>();
        for (IrBasicBlock block : function.blocks()) {
            if (absorbed.contains(block.label()) || outlined.containsKey(block.label())) {
                continue;
            }
            String current = block.label();
            int ordinal = 0;
            for (IrInstruction instruction : block.instructions()) {
                if (guardPlan(instruction) != null) {
                    current = guardBase(block.label(), ordinal++) + ".join";
                }
            }
            String normal = current;
            List<String> unwind = List.of(current);
            if (block.terminator() instanceof IrInvokeTerminator invoke) {
                List<DispatchReceiver> guarded = guardPlan(invoke.call());
                if (guarded != null) {
                    normal = guardBase(block.label(), ordinal) + ".join";
                    unwind = guardCallLabels(block.label(), ordinal, guarded);
                }
            }
            edges.put(block.label(), new BlockEdges(normal, unwind));
        }
        return new FunctionLayout(outlined, absorbed, edges, blocks);
    }

    private static List<String> successors(IrTerminator terminator) {
        if (terminator instanceof IrJump jump) {
            return List.of(jump.target());
        }
        if (terminator instanceof IrBranch branch) {
            return List.of(branch.trueTarget(), branch.falseTarget());
        }
        if (terminator instanceof IrSwitchTerminator switchTerminator) {
            List<String> result = new ArrayList<>();
            result.add(switchTerminator.defaultTarget());
            switchTerminator.cases().forEach(branch -> result.add(branch.target()));
            return result;
        }
        if (terminator instanceof IrInvokeTerminator invoke) {
            return List.of(invoke.normalTarget(), invoke.unwindTarget());
        }
        if (terminator instanceof IrThrowTerminator thrown && thrown.unwindTarget().isPresent()) {
            return List.of(thrown.normalTarget(), thrown.unwindTarget().orElseThrow());
        }
        return List.of();
    }

    private static boolean sameValue(IrOperand operand, IrValueReference reference) {
        return operand instanceof IrValueReference value && value.id() == reference.id();
    }

    private static boolean isConstructorCall(IrAllocateInstruction allocate, IrCallInstruction call) {
        String prefix = "ironwood." + allocate.className() + ".<init>";
        String target = call.targetLinkageName();
        return call.result().isEmpty()
                && !call.arguments().isEmpty()
                && sameValue(call.arguments().getFirst(), allocate.result())
                && (target.equals(prefix) || target.startsWith(prefix + "$"));
    }

    private static List<IrOperand> constructorArguments(IrCallInstruction constructor) {
        return constructor.arguments().subList(1, constructor.arguments().size());
    }

    /** Matches `allocate; construct; throw` with no local handler. */
    private OutlinedThrow matchBundledThrow(IrBasicBlock block) {
        if (block.instructions().size() != 2
                || !(block.instructions().get(0) instanceof IrAllocateInstruction allocate)
                || !(block.instructions().get(1) instanceof IrCallInstruction constructor)
                || !(block.terminator() instanceof IrThrowTerminator thrown)
                || thrown.unwindTarget().isPresent()
                || !isConstructorCall(allocate, constructor)
                || !sameValue(thrown.exception(), allocate.result())) {
            return null;
        }
        ThrowHelper helper = throwHelper(allocate.className(), constructor, false, false);
        if (helper == null) {
            return null;
        }
        return new OutlinedThrow(helper, constructorArguments(constructor),
                List.of(allocate, constructor, thrown));
    }

    /**
     * Matches the lowering of `throw new X(...)` outside any local handler:
     * optional type-initialization barrier and allocation, a constructor invoke
     * whose unwind edge rolls the allocation back and rethrows, and a normal
     * edge that null-checks the fresh object before throwing it.
     */
    private ExplicitThrow matchExplicitThrow(IrBasicBlock block, Map<String, IrBasicBlock> blocks,
                                             Map<String, Integer> references) {
        List<IrInstruction> instructions = block.instructions();
        IrEnsureTypeInitializedInstruction ensure = null;
        int index = 0;
        if (!instructions.isEmpty()
                && instructions.getFirst() instanceof IrEnsureTypeInitializedInstruction candidate) {
            ensure = candidate;
            index++;
        }
        if (index != instructions.size() - 1
                || !(instructions.get(index) instanceof IrAllocateInstruction allocate)
                || (ensure != null && !ensure.typeName().equals(allocate.className()))
                || !(block.terminator() instanceof IrInvokeTerminator invoke)
                || !(invoke.call() instanceof IrCallInstruction constructor)
                || !isConstructorCall(allocate, constructor)) {
            return null;
        }
        IrBasicBlock rollback = blocks.get(invoke.unwindTarget());
        IrBasicBlock continuation = blocks.get(invoke.normalTarget());
        if (rollback == null || continuation == null
                || rollback.instructions().size() != 2
                || !(rollback.instructions().get(0) instanceof IrExceptionLandingPadInstruction landing)
                || !(rollback.instructions().get(1) instanceof IrRollbackInstruction rollbackInstruction)
                || !sameValue(rollbackInstruction.allocation(), allocate.result())
                || !(rollback.terminator() instanceof IrThrowTerminator rethrow)
                || rethrow.unwindTarget().isPresent()
                || !sameValue(rethrow.exception(), landing.exceptionObject())) {
            return null;
        }
        if (continuation.instructions().size() != 1
                || !(continuation.instructions().getFirst() instanceof IrNullCheckInstruction check)
                || !sameValue(check.receiver(), allocate.result())
                || !(continuation.terminator() instanceof IrBranch branch)
                || !sameValue(branch.condition(), check.result())) {
            return null;
        }
        IrBasicBlock valid = blocks.get(branch.trueTarget());
        IrBasicBlock failure = blocks.get(branch.falseTarget());
        if (valid == null || failure == null
                || !valid.instructions().isEmpty()
                || !(valid.terminator() instanceof IrThrowTerminator thrown)
                || thrown.unwindTarget().isPresent()
                || !sameValue(thrown.exception(), allocate.result())
                || matchBundledThrow(failure) == null) {
            return null;
        }
        List<String> absorbed = List.of(rollback.label(), continuation.label(),
                valid.label(), failure.label());
        for (String label : absorbed) {
            if (references.getOrDefault(label, 0) != 1) {
                return null;
            }
        }
        ThrowHelper helper = throwHelper(allocate.className(), constructor, ensure != null, true);
        if (helper == null) {
            return null;
        }
        List<Object> operations = new ArrayList<>();
        if (ensure != null) {
            operations.add(ensure);
        }
        operations.add(allocate);
        operations.add(invoke);
        operations.add(rethrow);
        operations.add(thrown);
        return new ExplicitThrow(new OutlinedThrow(helper, constructorArguments(constructor),
                operations), absorbed);
    }

    private ThrowHelper throwHelper(String className, IrCallInstruction constructor,
                                    boolean ensureInitialized, boolean rollback) {
        List<String> parameterTypes = constructorArguments(constructor).stream()
                .map(argument -> llvmType(argument.type()))
                .toList();
        String name = THROW_HELPER_PREFIX + constructor.targetLinkageName()
                + (ensureInitialized ? "$ensure" : "") + (rollback ? "$rollback" : "");
        ThrowHelper existing = throwHelpers.get(name);
        if (existing != null) {
            return existing.parameterTypes().equals(parameterTypes) ? existing : null;
        }
        ThrowHelper helper = new ThrowHelper(name, className, constructor.targetLinkageName(),
                parameterTypes, ensureInitialized, rollback);
        throwHelpers.put(name, helper);
        return helper;
    }

    private void emitOutlinedThrow(StringBuilder output, IrFunction function, OutlinedThrow outlined) {
        // The original sites keep their probes so traces resolve to the throw
        // line; the last probe is the throw statement itself.
        for (Object operation : outlined.probeOperations()) {
            output.append("  ");
            emitTraceProbe(output, tracePlan.site(function, operation));
            output.append('\n');
        }
        // `nomerge` keeps every call distinct: merging identical helper calls
        // into one shared block would detach them from their site probes.
        Object throwSite = outlined.probeOperations().getLast();
        output.append("  call void ").append(functionName(outlined.helper().name())).append('(')
                .append(outlined.arguments().stream()
                        .map(argument -> llvmType(argument.type()) + " " + operand(argument))
                        .collect(Collectors.joining(", ")))
                .append(") nomerge, !dbg !").append(tracePlan.site(function, throwSite).callLocationMetadata())
                .append("\n  unreachable\n");
    }

    private void emitThrowHelpers(StringBuilder output) {
        for (ThrowHelper helper : throwHelpers.values()) {
            output.append("\ndefine internal void ").append(functionName(helper.name())).append('(');
            StringBuilder arguments = new StringBuilder("ptr %exception");
            for (int index = 0; index < helper.parameterTypes().size(); index++) {
                if (index > 0) {
                    output.append(", ");
                }
                output.append(helper.parameterTypes().get(index)).append(" %a").append(index);
                arguments.append(", ").append(helper.parameterTypes().get(index)).append(" %a").append(index);
            }
            output.append(") noinline cold noreturn personality ptr @__gxx_personality_v0 {\n")
                    .append("entry:\n");
            if (helper.ensureInitialized()) {
                output.append("  call void ").append(typeInitializerName(helper.className())).append("()\n");
            }
            output.append("  %size.ptr = getelementptr ").append(classType(helper.className()))
                    .append(", ptr null, i32 1\n")
                    .append("  %size = ptrtoint ptr %size.ptr to i64\n")
                    .append("  %exception = call ptr @ironwood_allocate(i64 %size, ptr ")
                    .append(typeInfoName(helper.className())).append(", ptr ")
                    .append(allocationFailureName()).append(")\n");
            if (helper.rollback()) {
                output.append("  invoke void ").append(functionName(helper.constructorLinkageName()))
                        .append('(').append(arguments).append(") to label %throw unwind label %rollback\n")
                        .append("rollback:\n")
                        .append("  %landing = landingpad { ptr, i32 } catch ptr null\n")
                        .append("  %handle = extractvalue { ptr, i32 } %landing, 0\n")
                        .append("  %pending = call ptr @ironwood_exception_take(ptr %handle)\n")
                        .append("  call void @\"ironwood.rollback\"(ptr %exception)\n")
                        .append("  call void @ironwood_throw(ptr %pending)\n")
                        .append("  unreachable\n")
                        .append("throw:\n");
            } else {
                output.append("  call void ").append(functionName(helper.constructorLinkageName()))
                        .append('(').append(arguments).append(")\n");
            }
            output.append("  call void @ironwood_throw(ptr %exception)\n")
                    .append("  unreachable\n")
                    .append("}\n");
        }
    }

    private void emitGuardedDispatch(StringBuilder output, Optional<IrValueReference> result,
                                     int slot, IrType returnType, List<IrOperand> arguments,
                                     List<DispatchReceiver> receivers, ScratchNames scratchNames,
                                     String operation, String debugSuffix, IrInvokeTerminator invoke) {
        String base = guardBase(currentBlockLabel, guardOrdinal++);
        String join = base + ".join";
        String fallback = base + ".fallback";
        List<String> targets = receivers.stream()
                .map(DispatchReceiver::targetLinkageName)
                .distinct()
                .toList();
        String argumentList = arguments.stream()
                .map(argument -> llvmType(argument.type()) + " " + operand(argument))
                .collect(Collectors.joining(", "));
        String typeInfo = scratchNames.next("guard.typeinfo");
        output.append(typeInfo).append(" = load ptr, ptr ").append(operand(arguments.getFirst())).append('\n');
        for (int index = 0; index < receivers.size(); index++) {
            DispatchReceiver receiver = receivers.get(index);
            String test = scratchNames.next("guard.test");
            String next = index + 1 < receivers.size() ? base + ".test." + (index + 1) : fallback;
            if (index > 0) {
                output.append(base).append(".test.").append(index).append(":\n");
            }
            output.append("  ").append(test).append(" = icmp eq ptr ").append(typeInfo).append(", ")
                    .append(typeInfoName(receiver.typeName())).append("\n  br i1 ").append(test)
                    .append(", label %").append(base).append(".call.")
                    .append(targets.indexOf(receiver.targetLinkageName()))
                    .append(", label %").append(next).append('\n');
        }
        List<String> phiEntries = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            String label = base + ".call." + index;
            output.append(label).append(":\n  ");
            String value = null;
            if (result.isPresent()) {
                value = scratchNames.next("guard.result");
                output.append(value).append(" = ");
            }
            output.append(operation).append(' ').append(llvmType(returnType)).append(' ')
                    .append(functionName(targets.get(index))).append('(').append(argumentList).append(')');
            if (invoke != null) {
                output.append(" to label %").append(join).append(" unwind label %").append(invoke.unwindTarget());
            }
            output.append(debugSuffix).append('\n');
            if (invoke == null) {
                output.append("  br label %").append(join).append('\n');
            }
            if (value != null) {
                phiEntries.add("[ " + value + ", %" + label + " ]");
            }
        }
        output.append(fallback).append(":\n");
        String dispatchAddress = scratchNames.next("guard.dispatch.address");
        String dispatch = scratchNames.next("guard.dispatch");
        String slotAddress = scratchNames.next("guard.slot.address");
        String callee = scratchNames.next("guard.callee");
        output.append("  ").append(dispatchAddress)
                .append(" = getelementptr inbounds %\"ironwood.typeinfo\", ptr ").append(typeInfo)
                .append(", i32 0, i32 1\n  ")
                .append(dispatch).append(" = load ptr, ptr ").append(dispatchAddress).append("\n  ")
                .append(slotAddress).append(" = getelementptr inbounds ptr, ptr ").append(dispatch)
                .append(", i32 ").append(slot).append("\n  ")
                .append(callee).append(" = load ptr, ptr ").append(slotAddress).append("\n  ");
        String fallbackValue = null;
        if (result.isPresent()) {
            fallbackValue = scratchNames.next("guard.result");
            output.append(fallbackValue).append(" = ");
        }
        output.append(operation).append(' ').append(llvmType(returnType)).append(' ').append(callee)
                .append('(').append(argumentList).append(')');
        if (invoke != null) {
            output.append(" to label %").append(join).append(" unwind label %").append(invoke.unwindTarget());
        }
        output.append(debugSuffix).append('\n');
        if (invoke == null) {
            output.append("  br label %").append(join).append('\n');
        }
        if (fallbackValue != null) {
            phiEntries.add("[ " + fallbackValue + ", %" + fallback + " ]");
        }
        output.append(join).append(':');
        if (result.isPresent()) {
            output.append("\n  ").append(operand(result.orElseThrow())).append(" = phi ")
                    .append(llvmType(returnType)).append(' ').append(String.join(", ", phiEntries));
        }
        if (invoke != null) {
            output.append("\n  br label %").append(invoke.normalTarget());
        }
    }

    private static final class ScratchNames {
        private int next;

        private String next(String prefix) {
            return "%ironwood." + prefix + "." + next++;
        }
    }
}
