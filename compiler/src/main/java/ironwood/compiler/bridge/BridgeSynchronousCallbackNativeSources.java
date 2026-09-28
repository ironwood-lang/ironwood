// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeCallbackAdmission;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** JNI boundaries for exactly admitted borrowed primitive listeners. */
public final class BridgeSynchronousCallbackNativeSources {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private final BridgeGeneration generation;
    private final BridgeJavaSources java;
    private final String source;
    private final Map<BridgeJavaSources.NativeDeclaration, String> functions;

    private BridgeSynchronousCallbackNativeSources(BridgeGeneration generation, BridgeJavaSources java, String source,
            Map<BridgeJavaSources.NativeDeclaration, String> functions) {
        this.generation = generation;
        this.java = java;
        this.source = source;
        this.functions = Map.copyOf(functions);
    }

    public String source() { return source; }
    public Map<BridgeJavaSources.NativeDeclaration, String> functions() { return functions; }
    public boolean matches(BridgeJavaSources declarations, BridgeGeneration identity) {
        return java.equals(declarations) && generation.manifest().equals(identity.manifest());
    }

    public static BridgeSynchronousCallbackNativeSources generate(BridgeCallbackAdmission admission,
            BridgeGeneration generation, BridgeJavaSources java) {
        if (!generation.matchesCallbacks(admission) || !java.equals(BridgeJavaSources.generateCallbacks(admission, generation))) {
            throw new IllegalArgumentException("callback adapters require exact proved Java declarations");
        }
        var artifact = admission.artifact();
        var callbacks = BridgeCallbackNativeSources.generate(artifact, admission.listeners());
        var exceptions = admission.exceptions();
        var text = new StringBuilder(BridgeExceptionNativeSources.generate(artifact, exceptions.projection(),
                exceptions.entries(), admission.carriers()))
                .append("\nstatic struct iw_exception_metadata iw_exceptions;\n")
                .append(BridgeCallbackCarrierNativeSources.generate(artifact, admission.carriers()))
                .append(BridgeCallbackCarrierNativeSources.cleanup(artifact, admission.carriers(), admission.surface().roots(), admission.cleanup()))
                .append(callbacks.source());
        var owners = admission.invocations().proxies().operations().stream().collect(Collectors.toMap(
                operation -> operation.listener(), operation -> operation));
        for (var owner : owners.values()) {
            text.append("extern int32_t ").append(owner.create().linkageName()).append("(int64_t, struct ironwood_bridge_result *);\n")
                    .append("extern void ").append(owner.destroy().linkageName()).append("(void *);\n");
        }
        text.append("static int iw_callback_metadata_init(JNIEnv *env, jclass *classes) {\n");
        for (var method : callbacks.methods()) {
            int index = java.generatedTypes().indexOf(method.listener());
            if (index < 0) throw new IllegalArgumentException("listener is absent from the preflight class inventory");
            text.append("    ").append(method.methodField()).append(" = (*env)->GetMethodID(env, classes[").append(index)
                    .append("], ").append(BridgeBootstrapSources.cString(method.name())).append(", ")
                    .append(BridgeBootstrapSources.cString(method.descriptor())).append(");\n")
                    .append("    if (").append(method.methodField()).append(" == NULL) return 0;\n");
        }
        text.append("    return 1;\n}\nstatic void iw_callback_metadata_dispose(JNIEnv *env) { (void)env; }\n")
                .append("__attribute__((noinline)) static void iw_callback_failure(JNIEnv *env, int32_t status, struct ironwood_bridge_result *result) {\n")
                .append("    if (status == 1) {\n        if (!iw_callback_restore(env, result)) iw_exception_translate(env, &iw_exceptions, result->exception);\n    }\n")
                .append("    else iw_exception_error(env, &iw_exceptions, status == 2, \"Ironwood protected callback entry failed\");\n}\n");
        var functions = new java.util.LinkedHashMap<BridgeJavaSources.NativeDeclaration, String>();
        for (var binding : java.bindings()) {
            var id = binding.method().target().orElseThrow();
            var types = id.parameters();
            String function = "iw_synchronous_" + functions.size();
            functions.put(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function);
            var nativeTypes = new ArrayList<String>();
            var arguments = new ArrayList<String>();
            var listeners = new ArrayList<Integer>();
            var strings = new ArrayList<Integer>();
            for (int index = 0; index < types.size(); index++) {
                if (types.get(index).equals(STRING)) {
                    nativeTypes.add("int64_t"); nativeTypes.add("int32_t"); strings.add(index);
                    arguments.add("(int64_t)(uintptr_t)chars" + index); arguments.add("length" + index);
                } else if (types.get(index).isReference()) {
                    nativeTypes.add("void *"); arguments.add("proxy" + index + ".value.reference"); listeners.add(index);
                } else {
                    nativeTypes.add(BridgeValueNativeSources.cType(types.get(index))); arguments.add("arg" + index);
                }
            }
            nativeTypes.add("int64_t"); nativeTypes.add("struct ironwood_bridge_result *");
            arguments.add("(int64_t)(uintptr_t)&frame"); arguments.add("&result");
            text.append("extern int32_t ").append(binding.entrySymbol()).append('(').append(String.join(", ", nativeTypes)).append(");\n")
                    .append("static ").append(BridgeValueNativeSources.jniType(id.result())).append(' ').append(function)
                    .append("(JNIEnv *env, jclass type");
            for (int index = 0; index < types.size(); index++) {
                text.append(", ").append(types.get(index).isReference() ? "jobject" : BridgeValueNativeSources.jniType(types.get(index)))
                        .append(" arg").append(index);
            }
            text.append(") {\n    (void)type;\n    struct iw_callback_frame frame = {env, NULL};\n")
                    .append("    struct ironwood_bridge_result result = {0};\n    int32_t status = 0;\n");
            for (int index : listeners) text.append("    struct ironwood_bridge_result proxy").append(index).append(" = {0};\n");
            text.append(BridgeStringInputSources.declarations(strings)).append(BridgeStringInputSources.acquire(strings));
            for (int index : listeners) {
                var owner = owners.get(types.get(index).referenceName());
                if (owner == null) throw new IllegalArgumentException("callback input has no proved proxy ownership");
                text.append("    if (arg").append(index).append(" != NULL) {\n        status = ").append(owner.create().linkageName())
                        .append("((int64_t)(uintptr_t)arg").append(index).append(", &proxy").append(index).append(");\n")
                        .append("        if (status != 0) { result = proxy").append(index).append("; goto cleanup; }\n    }\n");
            }
            text.append("    status = ").append(binding.entrySymbol()).append('(').append(String.join(", ", arguments)).append(");\ncleanup:\n");
            for (int index : listeners.reversed()) {
                text.append("    if (proxy").append(index).append(".value.reference != NULL) ")
                        .append(owners.get(types.get(index).referenceName()).destroy().linkageName())
                        .append("(proxy").append(index).append(".value.reference);\n");
            }
            text.append(BridgeStringInputSources.release(strings));
            // JNI arguments are local strong references for the whole outer call.
            // Their proved native proxies cannot escape; carriers outlive translation.
            text.append("    if (status != 0) iw_callback_failure(env, status, &result);\n    iw_callback_release(&frame);\n");
            text.append(id.result().equals(IrType.VOID) ? "    return;\n" : "    return status == 0 ? result.value."
                    + BridgeValueNativeSources.field(id.result()) + " : 0;\n");
            if (!strings.isEmpty()) {
                text.append("preparation_failed:\n").append(BridgeStringInputSources.release(strings))
                        .append(id.result().equals(IrType.VOID) ? "    return;\n" : "    return 0;\n");
            }
            text.append("}\n");
        }
        return new BridgeSynchronousCallbackNativeSources(generation, java, text.toString(), functions);
    }
}
