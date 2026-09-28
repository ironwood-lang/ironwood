// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.BridgeOwnedCallbackAdmission;
import ironwood.compiler.ir.IrForeignCallInstruction;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JNI callback bodies bound to exact generated proxies; loading and lifetime are separate gates. */
public final class BridgeCallbackNativeSources {
    public record Method(String listener, String name, String descriptor, String methodField, String symbol) {}
    public record Sources(String source, List<Method> methods) {
        public Sources { methods = List.copyOf(methods); }
    }
    private BridgeCallbackNativeSources() {}

    public static Sources generate(CompilationArtifact artifact, BridgeListenerProxies proxies) {
        return generate(artifact, proxies, Map.of(), null);
    }

    public static Sources generate(CompilationArtifact artifact, BridgeListenerProxies proxies, BridgeGeneration generation) {
        return generate(artifact, proxies, Map.of(), generation);
    }

    public static Sources generateOwned(BridgeOwnedCallbackAdmission admission, BridgeOwnedCallbackJavaSources.Sources java,
            BridgeGeneration generation) {
        var owners = new java.util.LinkedHashMap<IrType, Integer>();
        for (int index = 0; index < java.facades().size(); index++) {
            var type = IrType.reference(java.facades().get(index).binaryName());
            if (!admission.lifetime().protocol().constructedRootTypes().contains(type)) {
                throw new IllegalArgumentException("callback facade lacks exact owner storage");
            }
            owners.put(type, index);
        }
        return generate(admission.artifact(), admission.listeners(), owners, generation);
    }

    private static Sources generate(CompilationArtifact artifact, BridgeListenerProxies proxies, Map<IrType, Integer> owners,
            BridgeGeneration generation) {
        proxies.validateArtifact(artifact);
        var methods = new ArrayList<Method>();
        var source = new StringBuilder();
        if (generation != null) source.append("static jclass iw_callback_dispatch;\n");
        var program = artifact.program().orElseThrow();
        for (var proxy : proxies.proxies()) {
            for (var method : proxy.methods()) {
                if ((!method.result().isPrimitive() && !method.result().equals(IrType.VOID))
                        || method.parameters().stream().anyMatch(type -> !type.isPrimitive() && !owners.containsKey(type))) {
                    throw new IllegalArgumentException("callback JNI transport requires proved argument conversion and primitive/void result");
                }
                var function = program.functions().stream().filter(candidate -> candidate.ownerClass().equals(proxy.binaryName())
                        && candidate.sourceName().equals(method.name()) && candidate.returnType().equals(method.result())
                        && candidate.parameters().stream().skip(1).map(parameter -> parameter.value().type()).toList()
                        .equals(method.parameters())).findFirst().orElseThrow();
                var foreign = function.blocks().stream().flatMap(block -> block.instructions().stream())
                        .filter(IrForeignCallInstruction.class::isInstance).map(IrForeignCallInstruction.class::cast)
                        .findFirst().orElseThrow();
                String field = "iw_callback_method_" + methods.size();
                boolean returns = !method.result().equals(IrType.VOID);
                String descriptor = "(" + method.parameters().stream().map(BridgeJavaTypes::descriptor)
                        .collect(java.util.stream.Collectors.joining()) + ")" + primitive(method.result()).descriptor();
                if (generation == null) {
                    methods.add(new Method(proxy.listener().binaryName(), method.name(), descriptor, field, foreign.targetLinkageName()));
                } else {
                    methods.add(new Method(BridgeCallbackDispatchSources.binaryName(generation),
                            BridgeCallbackDispatchSources.methodName(methods.size()),
                            "(L" + proxy.listener().binaryName().replace('.', '/') + ";" + descriptor.substring(1),
                            field, foreign.targetLinkageName()));
                }
                source.append("static jmethodID ").append(field).append(";\n")
                        .append(carrierC(method.result())).append(' ').append(foreign.targetLinkageName())
                        .append("(int64_t invocation, int64_t handle");
                for (int index = 0; index < method.parameters().size(); index++) {
                    source.append(", ").append(carrierC(method.parameters().get(index))).append(" argument").append(index);
                }
                source.append(") {\n    struct iw_callback_frame *frame = (struct iw_callback_frame *)(uintptr_t)invocation;\n")
                        .append("    JNIEnv *env = frame->env;\n");
                var references = new ArrayList<Integer>();
                for (int index = 0; index < method.parameters().size(); index++) {
                    if (owners.containsKey(method.parameters().get(index))) {
                        references.add(index);
                        source.append("    jobject reference").append(index).append(" = NULL;\n");
                    }
                }
                for (int index : references) {
                    source.append("    reference").append(index).append(" = iw_owned_wrap(env, ")
                            .append(owners.get(method.parameters().get(index))).append(", argument").append(index).append(");\n")
                            .append("    if ((*env)->ExceptionCheck(env)) goto conversion_failed;\n");
                }
                if (generation != null || !method.parameters().isEmpty()) {
                    source.append("    const jvalue arguments[] = {\n");
                    if (generation != null) source.append("        { .l = (jobject)(uintptr_t)handle },\n");
                    for (int index = 0; index < method.parameters().size(); index++) {
                        if (references.contains(index)) {
                            source.append("        { .l = reference").append(index).append(" },\n");
                            continue;
                        }
                        var parameter = primitive(method.parameters().get(index));
                        source.append("        { .").append(parameter.descriptor().toLowerCase(java.util.Locale.ROOT))
                                .append(" = (").append(parameter.jniType()).append(")argument").append(index).append(" },\n");
                    }
                    source.append("    };\n");
                }
                source.append("    ").append(returns ? primitive(method.result()).jniType() + " result = " : "")
                        .append("(*env)->Call").append(generation == null ? "" : "Static").append(primitive(method.result()).name())
                        .append("MethodA(env, ").append(generation == null ? "(jobject)(uintptr_t)handle" : "iw_callback_dispatch")
                        .append(", ").append(field)
                        .append(generation == null && method.parameters().isEmpty() ? ", NULL);\n" : ", arguments);\n");
                releaseReferences(source, references);
                source.append("    if ((*env)->ExceptionCheck(env)) iw_callback_capture(frame);\n");
                if (returns) source.append("    return (").append(carrierC(method.result())).append(")result;\n");
                else if (!references.isEmpty()) source.append("    return;\n");
                if (!references.isEmpty()) {
                    source.append("conversion_failed:\n");
                    releaseReferences(source, references);
                    source.append("    iw_callback_capture(frame);\n");
                }
                source.append("}\n");
            }
        }
        return new Sources(source.toString(), methods);
    }

    private record Primitive(String descriptor, String name, String jniType) {}

    private static void releaseReferences(StringBuilder source, List<Integer> references) {
        for (int index : references.reversed()) source.append("    if (reference").append(index)
                .append(" != NULL) (*env)->DeleteLocalRef(env, reference").append(index).append(");\n");
    }

    private static Primitive primitive(IrType type) {
        return switch (type.kind()) {
            case I1 -> new Primitive("Z", "Boolean", "jboolean");
            case I8 -> new Primitive("B", "Byte", "jbyte");
            case I16 -> new Primitive("S", "Short", "jshort");
            case U16 -> new Primitive("C", "Char", "jchar");
            case I32 -> new Primitive("I", "Int", "jint");
            case I64 -> new Primitive("J", "Long", "jlong");
            case F32 -> new Primitive("F", "Float", "jfloat");
            case F64 -> new Primitive("D", "Double", "jdouble");
            case VOID -> new Primitive("V", "Void", "void");
            default -> throw new IllegalArgumentException("callback value requires primitive type");
        };
    }

    private static String carrierC(IrType type) {
        if (type.isReference()) return "void *";
        return switch (BridgeCallbackAbi.carrier(type).kind()) {
            case I64 -> "int64_t";
            case F32 -> "float";
            case F64 -> "double";
            case VOID -> "void";
            default -> throw new IllegalArgumentException("callback value requires primitive carrier");
        };
    }
}
