// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** JNI value marshalling around proved typed entries; no source semantics live in C. */
public record BridgeValueNativeSources(String source, List<Adapter> adapters) {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");

    public BridgeValueNativeSources { adapters = List.copyOf(adapters); }

    public record Adapter(BridgeCallableId callable, String entrySymbol, String functionName, String descriptor) {}

    /** The bootstrap must initialize iw_exceptions before registering these adapters. */
    public static BridgeValueNativeSources generate(CompilationArtifact artifact, BridgeEntryModule module,
            BridgeExceptionProjection projection, BridgeExceptionEntries exceptions) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()
                || !artifact.bridgeConstructionFacts().orElseThrow().matches(artifact.program().orElseThrow())
                || !module.program().functions().containsAll(artifact.program().orElseThrow().functions())
                || !exceptions.program().functions().containsAll(module.program().functions())
                || module.rootRetention().isPresent() || module.permanent().isPresent()) {
            throw new IllegalArgumentException("value adapters require matching proved scalar/String entries");
        }
        var original = artifact.program().orElseThrow();
        var base = module.program();
        var restored = new IrProgram(base.moduleName(), base.classes(), base.staticFields(), base.typeInitializations(),
                base.arrayTypes(), base.stringConstants(), base.dispatchSlots(), original.functions(), original.entryPoint(),
                base.allocationFailure(), original.exportRoots());
        if (!restored.equals(original)) throw new IllegalArgumentException("value adapter program metadata mismatch");
        var text = new StringBuilder(BridgeExceptionNativeSources.generate(artifact, projection, exceptions))
                .append("\nstatic struct iw_exception_metadata iw_exceptions;\n")
                .append("__attribute__((noinline)) static void iw_value_failure(JNIEnv *env, int32_t status, void *exception) {\n")
                .append("    if (status == 1) iw_exception_translate(env, &iw_exceptions, exception);\n")
                .append("    else iw_exception_error(env, &iw_exceptions, status == 2, \"Ironwood protected entry failed\");\n}\n");
        var adapters = new ArrayList<Adapter>();
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            if (id.kind() != IrCallableKind.METHOD || !artifact.bridgeConstructionFacts().orElseThrow().isStatic(id)
                    || !valueType(id.result()) || id.parameters().stream().anyMatch(type -> !valueType(type) || type.equals(IrType.VOID))
                    || id.result().equals(STRING) && !module.stringResults().containsKey(id)) {
                throw new IllegalArgumentException("unsupported value adapter shape: " + id.linkage());
            }
            String function = "iw_value_" + adapters.size();
            String descriptor = "(" + id.parameters().stream().map(BridgeJavaTypes::descriptor).collect(Collectors.joining())
                    + ")" + BridgeJavaTypes.descriptor(id.result());
            adapters.add(new Adapter(id, entry.function().linkageName(), function, descriptor));
            emit(text, entry, module, function, artifact);
        }
        return new BridgeValueNativeSources(text.toString(), adapters);
    }

    private static boolean valueType(IrType type) {
        return type.equals(STRING) || ironwood.compiler.semantic.BridgeArrayInputs.primitiveArray(type)
                || BridgeAbi.carrierFor(type).filter(carrier -> carrier != BridgeAbi.Carrier.OPAQUE_REFERENCE).isPresent();
    }

    private static void emit(StringBuilder text, BridgeEntryModule.Entry entry, BridgeEntryModule module, String function,
                             CompilationArtifact artifact) {
        var id = entry.root().callable();
        var arrays = BridgeArrayValueSources.contract(artifact, id);
        var nativeTypes = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        var strings = new ArrayList<Integer>();
        for (int i = 0; i < id.parameters().size(); i++) {
            var type = id.parameters().get(i);
            if (type.equals(STRING)) {
                nativeTypes.add("int64_t"); nativeTypes.add("int32_t"); strings.add(i);
                arguments.add("(int64_t)(uintptr_t)chars" + i); arguments.add("length" + i);
            } else if (type.isArray()) {
                nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)array" + i);
            } else { nativeTypes.add(cType(type)); arguments.add("arg" + i); }
        }
        nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)&result");
        String exit = id.result().equals(IrType.VOID) ? "return;" : "return 0;";
        text.append("extern int32_t ").append(entry.function().linkageName()).append('(').append(String.join(", ", nativeTypes)).append(");\n")
                .append("static ").append(jniType(id.result())).append(' ').append(function).append("(JNIEnv *env, jclass type");
        for (int i = 0; i < id.parameters().size(); i++) text.append(", ").append(jniType(id.parameters().get(i))).append(" arg").append(i);
        text.append(") {\n    (void)type;\n");
        text.append(BridgeStringInputSources.declarations(strings)).append(BridgeArrayInputSources.declarations(id.parameters()))
                .append(BridgeStringInputSources.acquire(strings)).append(BridgeArrayInputSources.acquire(id.parameters()));
        text.append("    struct ironwood_bridge_result result;\n    int32_t status = ").append(entry.function().linkageName())
                .append('(').append(String.join(", ", arguments)).append(");\n");
        text.append(BridgeStringInputSources.release(strings));
        BridgeArrayValueSources.finish(text, arrays, "result", "iw_value_failure", exit, false,
                module.stringResults().containsKey(id) && module.stringResults().get(id).releaseAfterCopy());
        if (!id.result().isArray()) text.append(BridgeArrayInputSources.release(id.parameters()));
        if (id.result().isArray()) BridgeArrayValueSources.result(text, arrays, "result");
        else if (id.result().equals(STRING)) {
            text.append("    const struct ironwood_string *value = result.value.reference;\n")
                    .append("    jstring copied = value == NULL ? NULL : (*env)->NewString(env, value->units, value->utf16_length);\n");
            if (module.stringResults().get(id).releaseAfterCopy()) {
                text.append("    ironwood_deallocate(result.value.reference);\n");
            }
            text.append("    return copied;\n");
        } else if (id.result().equals(IrType.VOID)) text.append("    return;\n");
        else text.append("    return result.value.").append(field(id.result())).append(";\n");
        if (!strings.isEmpty() || !BridgeArrayInputSources.indices(id.parameters()).isEmpty()) {
            text.append("preparation_failed:\n");
            text.append(BridgeStringInputSources.release(strings)).append(BridgeArrayInputSources.release(id.parameters()));
            text.append("    ").append(exit).append('\n');
        }
        text.append("}\n");
    }

    static String jniType(IrType type) {
        if (type.equals(STRING)) return "jstring";
        if (type.isArray()) return "j" + BridgeJavaTypes.sourceName(type.elementType()) + "Array";
        return type.equals(IrType.VOID) ? "void" : "j" + BridgeJavaTypes.sourceName(type);
    }

    static String cType(IrType type) {
        return switch (type.kind()) {
            case I1 -> "uint8_t"; case I8 -> "int8_t"; case I16 -> "int16_t"; case U16 -> "uint16_t";
            case I32 -> "int32_t"; case I64 -> "int64_t"; case F32 -> "float"; case F64 -> "double";
            default -> throw new IllegalArgumentException("unsupported native value carrier");
        };
    }

    static String field(IrType type) {
        return switch (type.kind()) {
            case I1 -> "boolean"; case I8 -> "byte"; case I16 -> "short_integer"; case U16 -> "character";
            case I32 -> "integer"; case I64 -> "wide"; case F32 -> "single"; case F64 -> "real";
            default -> throw new IllegalArgumentException("unsupported native result carrier");
        };
    }
}
