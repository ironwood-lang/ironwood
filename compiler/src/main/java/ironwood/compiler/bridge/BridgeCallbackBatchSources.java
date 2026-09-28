// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Private bounded transport for an exact proved callback schedule. */
final class BridgeCallbackBatchSources {
    private BridgeCallbackBatchSources() {}

    static BridgeJavaSources addDispatch(BridgeJavaSources declarations, BridgeCallbackBatching batching,
            BridgeGeneration generation, BridgeExportSurface surface) {
        if (batching.entries().isEmpty()) return declarations;
        var methods = new StringBuilder();
        for (var entry : ordered(batching)) {
            String listener = surface.types().stream().filter(type -> type.binaryName().equals(entry.listener()))
                    .findFirst().orElseThrow().sourceName();
            methods.append("    static void ").append(entry.relay()).append('(').append(listener)
                    .append(" listener, java.nio.LongBuffer buffer, int count) throws java.lang.Throwable {\n")
                    .append("        for (int event = 0; event < count; event++) {\n            listener.").append(entry.method()).append('(');
            var arguments = new ArrayList<String>();
            for (int index = 0; index < entry.arity(); index++) arguments.add("buffer.get(event * " + entry.arity() + " + " + index + ")");
            methods.append(String.join(", ", arguments)).append(");\n        }\n    }\n");
        }
        var sources = new TreeMap<>(declarations.sources());
        String path = BridgeCallbackDispatchSources.binaryName(generation).replace('.', '/') + ".java";
        String original = sources.get(path);
        sources.put(path, original.substring(0, original.lastIndexOf('}')) + methods + "}\n");
        return new BridgeJavaSources(sources, declarations.bindings(), declarations.generatedTypes(), declarations.ensureMethod(),
                declarations.facadeRegistrations(), declarations.rootDestructions());
    }

    static String nativeCallbacks(BridgeCallbackBatching batching) {
        if (batching.entries().isEmpty()) return "";
        var text = new StringBuilder("""
                struct iw_callback_batch_frame {
                    struct iw_callback_frame base;
                    jobject buffer;
                    jlong *data;
                    int32_t remaining;
                    int32_t used;
                };
                """);
        for (var entry : ordered(batching)) {
            text.append("static jmethodID ").append(entry.methodField()).append(";\n")
                    .append("void ").append(entry.callbackSymbol()).append("(int64_t invocation, int64_t handle");
            for (int index = 0; index < entry.arity(); index++) text.append(", int64_t argument").append(index);
            text.append(") {\n    struct iw_callback_batch_frame *frame = (struct iw_callback_batch_frame *)(uintptr_t)invocation;\n")
                    .append("    int32_t offset = frame->used * ").append(entry.arity()).append(";\n");
            for (int index = 0; index < entry.arity(); index++) text.append("    frame->data[offset + ").append(index).append("] = argument").append(index).append(";\n");
            text.append("    frame->used++;\n    if (--frame->remaining == 0 || frame->used == ").append(BridgeCallbackBatching.CAPACITY).append(") {\n")
                    .append("        JNIEnv *env = frame->base.env;\n")
                    .append("        const jvalue arguments[] = {{.l = (jobject)(uintptr_t)handle}, {.l = frame->buffer}, {.i = frame->used}};\n")
                    .append("        (*env)->CallStaticVoidMethodA(env, iw_callback_dispatch, ").append(entry.methodField()).append(", arguments);\n")
                    .append("        if ((*env)->ExceptionCheck(env)) iw_callback_capture(&frame->base);\n")
                    .append("        frame->used = 0;\n    }\n}\n");
        }
        return text.toString();
    }

    static String metadata(BridgeCallbackBatching batching, int dispatch) {
        var text = new StringBuilder();
        for (var entry : ordered(batching)) text.append("    ").append(entry.methodField())
                .append(" = (*env)->GetStaticMethodID(env, classes[").append(dispatch).append("], ")
                .append(BridgeBootstrapSources.cString(entry.relay())).append(", ")
                .append(BridgeBootstrapSources.cString(entry.descriptor())).append(");\n    if (")
                .append(entry.methodField()).append(" == NULL) goto failed;\n");
        return text.toString();
    }

    static String helper(BridgeCallbackBatching.Entry entry, List<String> nativeFormals, String name) {
        int contextIndex = nativeFormals.size() - 2;
        var formals = new ArrayList<String>(); var arguments = new ArrayList<String>();
        for (int index = 0; index < nativeFormals.size(); index++) {
            formals.add(nativeFormals.get(index) + " a" + index);
            arguments.add(index == contextIndex ? "(int64_t)(uintptr_t)&batch" : "a" + index);
        }
        return "extern int32_t " + entry.entrySymbol() + "(" + String.join(", ", nativeFormals) + ");\n"
                + "static int32_t " + name + "(" + String.join(", ", formals) + ", jobject buffer, jlong *data) {\n"
                + "    struct iw_callback_frame *context = (struct iw_callback_frame *)(uintptr_t)a" + contextIndex + ";\n"
                + "    struct iw_callback_batch_frame batch = {*context, buffer, data, a" + entry.countInput() + ", 0};\n"
                + "    int32_t status = " + entry.entrySymbol() + "(" + String.join(", ", arguments) + ");\n"
                + "    *context = batch.base;\n    return status;\n}\n";
    }

    private static List<BridgeCallbackBatching.Entry> ordered(BridgeCallbackBatching batching) {
        return batching.entries().values().stream().sorted(java.util.Comparator.comparingInt(BridgeCallbackBatching.Entry::index)).toList();
    }
}
