// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeByteViews;
import java.util.List;
import java.util.stream.IntStream;

/** Stack-only borrowed descriptors. JNI locals keep private direct storage reachable. */
public final class BridgeByteViewInputSources {
    private BridgeByteViewInputSources() {}
    public static List<Integer> indices(List<IrType> parameters) {
        return IntStream.range(0, parameters.size()).filter(i -> BridgeByteViews.view(parameters.get(i))).boxed().toList();
    }
    static String declarations(List<IrType> parameters) {
        var text = new StringBuilder();
        for (int i : indices(parameters)) text.append("    struct iw_byteview descriptor").append(i)
                .append("; struct iw_byteview *view").append(i).append(" = NULL;\n");
        return text.toString();
    }
    static String acquire(CompilationArtifact artifact, BridgeCallableId callable) {
        var indices = indices(callable.parameters());
        if (indices.isEmpty()) return "";
        var proof = BridgeByteViews.analyze(artifact, callable);
        if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
        boolean identity = proof.contract().orElseThrow().observesIdentity();
        var text = new StringBuilder();
        for (int i : indices) {
            text.append("    if (arg").append(i).append(" != NULL) {\n");
            if (identity) for (int prior : indices) {
                if (prior >= i) break;
                text.append("        if (view").append(i).append(" == NULL && arg").append(prior)
                        .append(" != NULL && (*env)->IsSameObject(env, arg").append(i).append(", arg").append(prior)
                        .append(")) view").append(i).append(" = view").append(prior).append(";\n");
            }
            text.append("        if (view").append(i).append(" == NULL) {\n")
                    .append("            if (!iw_byteview_input(env, arg").append(i).append(", &descriptor").append(i)
                    .append(")) goto preparation_failed;\n")
                    .append("            view").append(i).append(" = &descriptor").append(i).append(";\n        }\n    }\n");
        }
        return text.toString();
    }
    public static final String HELPERS = """
            struct iw_byteview { void *data; int32_t length; uint8_t read_only; };
            static jfieldID iw_view_storage, iw_view_offset, iw_view_length, iw_view_readonly;
            static int iw_byteviews_init(JNIEnv *env, jclass support) {
                jfieldID field = (*env)->GetStaticFieldID(env, support, "byteViewClass", "Ljava/lang/Class;");
                if (field == NULL) return 0;
                jclass type = (jclass)(*env)->GetStaticObjectField(env, support, field);
                if ((*env)->ExceptionCheck(env) || type == NULL) return 0;
                iw_view_storage = (*env)->GetFieldID(env, type, "storage", "Ljava/nio/ByteBuffer;");
                if (iw_view_storage == NULL) return 0;
                iw_view_offset = (*env)->GetFieldID(env, type, "offset", "I");
                if (iw_view_offset == NULL) return 0;
                iw_view_length = (*env)->GetFieldID(env, type, "length", "I");
                if (iw_view_length == NULL) return 0;
                iw_view_readonly = (*env)->GetFieldID(env, type, "readOnly", "Z");
                return iw_view_readonly != NULL;
            }
            static int iw_byteview_input(JNIEnv *env, jobject input, struct iw_byteview *view) {
                jobject storage = (*env)->GetObjectField(env, input, iw_view_storage);
                if ((*env)->ExceptionCheck(env)) return 0;
                jint offset = (*env)->GetIntField(env, input, iw_view_offset);
                if ((*env)->ExceptionCheck(env)) return 0;
                view->length = (*env)->GetIntField(env, input, iw_view_length);
                if ((*env)->ExceptionCheck(env)) return 0;
                view->read_only = (*env)->GetBooleanField(env, input, iw_view_readonly);
                if ((*env)->ExceptionCheck(env)) return 0;
                void *base = (*env)->GetDirectBufferAddress(env, storage);
                if ((*env)->ExceptionCheck(env)) return 0;
                if (base == NULL && view->length != 0) {
                    jclass failure = (*env)->FindClass(env, "java/lang/UnsupportedOperationException");
                    if (failure != NULL) (*env)->ThrowNew(env, failure, "JVM direct storage is unavailable");
                    return 0;
                }
                view->data = base == NULL ? NULL : (uint8_t *)base + offset;
                // The storage local remains rooted until the JNI frame returns.
                return 1;
            }
            """;
}
