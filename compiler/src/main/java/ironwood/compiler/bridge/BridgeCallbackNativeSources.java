// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrForeignCallInstruction;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;

/** JNI callback bodies bound to exact generated proxies; loading and lifetime are separate gates. */
public final class BridgeCallbackNativeSources {
    public record Method(String listener, String name, String descriptor, String methodField, String symbol) {}
    public record Sources(String source, List<Method> methods) {
        public Sources { methods = List.copyOf(methods); }
    }
    private BridgeCallbackNativeSources() {}

    public static Sources generate(CompilationArtifact artifact, BridgeListenerProxies proxies) {
        proxies.validateArtifact(artifact);
        var methods = new ArrayList<Method>();
        var source = new StringBuilder();
        var program = artifact.program().orElseThrow();
        for (var proxy : proxies.proxies()) {
            for (var method : proxy.methods()) {
                // Match the current typed foreign ABI. Other primitive widths
                // and references need explicit normalization/lifetime lowering.
                if ((!method.result().equals(IrType.I64) && !method.result().equals(IrType.VOID))
                        || method.parameters().stream().anyMatch(type -> !type.equals(IrType.I64))) {
                    throw new IllegalArgumentException("callback JNI transport currently requires long parameters and long/void result");
                }
                var function = program.functions().stream().filter(candidate -> candidate.ownerClass().equals(proxy.binaryName())
                        && candidate.sourceName().equals(method.name()) && candidate.returnType().equals(method.result())
                        && candidate.parameters().stream().skip(1).map(parameter -> parameter.value().type()).toList()
                        .equals(method.parameters())).findFirst().orElseThrow();
                var foreign = (IrForeignCallInstruction) function.blocks().getFirst().instructions().get(1);
                String field = "iw_callback_method_" + methods.size();
                boolean returns = method.result().equals(IrType.I64);
                methods.add(new Method(proxy.listener().binaryName(), method.name(), "(" + "J".repeat(method.parameters().size())
                        + ")" + (returns ? "J" : "V"), field, foreign.targetLinkageName()));
                source.append("static jmethodID ").append(field).append(";\n")
                        .append(returns ? "int64_t " : "void ").append(foreign.targetLinkageName())
                        .append("(int64_t invocation, int64_t handle");
                for (int index = 0; index < method.parameters().size(); index++) source.append(", int64_t argument").append(index);
                source.append(") {\n    struct iw_callback_frame *frame = (struct iw_callback_frame *)(uintptr_t)invocation;\n")
                        .append("    JNIEnv *env = frame->env;\n    ")
                        .append(returns ? "jlong result = " : "").append("(*env)->Call").append(returns ? "Long" : "Void")
                        .append("Method(env, (jobject)(uintptr_t)handle, ").append(field);
                for (int index = 0; index < method.parameters().size(); index++) source.append(", (jlong)argument").append(index);
                source.append(");\n    if ((*env)->ExceptionCheck(env)) iw_callback_capture(frame);\n");
                if (returns) source.append("    return (int64_t)result;\n");
                source.append("}\n");
            }
        }
        return new Sources(source.toString(), methods);
    }
}
