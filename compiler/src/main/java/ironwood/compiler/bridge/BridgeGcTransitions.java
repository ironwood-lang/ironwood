// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Image-local native boundaries and conservative JNI execution transitions. */
public final class BridgeGcTransitions {
    private BridgeGcTransitions() {}
    private static final Pattern JNI = Pattern.compile("\\(\\*[A-Za-z_][A-Za-z0-9_]*(?:->[A-Za-z_][A-Za-z0-9_]*)*\\)->([A-Za-z0-9_]+)\\(");
    private static final Set<String> LEAF = Set.of("ExceptionCheck", "ExceptionOccurred", "ExceptionClear", "DeleteGlobalRef",
            "DeleteLocalRef", "DeleteWeakGlobalRef", "IsSameObject", "IsInstanceOf", "GetArrayLength", "GetStringLength",
            "GetObjectField", "SetObjectField", "GetBooleanField", "GetByteField", "GetCharField", "GetShortField", "GetIntField",
            "GetLongField", "GetFloatField", "GetDoubleField", "SetBooleanField", "SetByteField", "SetCharField", "SetShortField",
            "SetIntField", "SetLongField", "SetFloatField", "SetDoubleField", "ReleaseStringChars", "ReleaseStringUTFChars", "Throw",
            "ReleaseBooleanArrayElements", "ReleaseByteArrayElements", "ReleaseCharArrayElements", "ReleaseShortArrayElements",
            "ReleaseIntArrayElements", "ReleaseLongArrayElements", "ReleaseFloatArrayElements", "ReleaseDoubleArrayElements");

    private record Signature(String result, List<String> parameters, boolean variadic) {
        Signature(String result, String... parameters) { this(result, List.of(parameters), false); }
    }

    private static Signature signature(String name) {
        var calls = Pattern.compile("Call(Static)?(Object|Void|Boolean|Byte|Char|Short|Int|Long|Float|Double)Method(A|V)?").matcher(name);
        if (calls.matches()) {
            var args = new ArrayList<>(List.of("j" + (calls.group(1) == null ? "object" : "class"), "jmethodID"));
            String suffix = calls.group(3);
            if (suffix != null) args.add(suffix.equals("A") ? "const jvalue *" : "va_list");
            return new Signature(calls.group(2).equals("Void") ? "void" : "j" + calls.group(2).toLowerCase(Locale.ROOT), args, suffix == null);
        }
        var arrays = Pattern.compile("(New|Get|Set)(Boolean|Byte|Char|Short|Int|Long|Float|Double)Array(Region|Elements)?").matcher(name);
        if (arrays.matches()) {
            String primitive = "j" + arrays.group(2).toLowerCase(Locale.ROOT), array = primitive + "Array";
            if (arrays.group(1).equals("New")) return new Signature(array, "jsize");
            if ("Region".equals(arrays.group(3))) return new Signature("void", array, "jsize", "jsize",
                    (arrays.group(1).equals("Set") ? "const " : "") + primitive + " *");
            if ("Elements".equals(arrays.group(3))) return new Signature(primitive + " *", array, "jboolean *");
        }
        return switch (name) {
            case "NewObject" -> new Signature("jobject", List.of("jclass", "jmethodID"), true);
            case "NewObjectA" -> new Signature("jobject", "jclass", "jmethodID", "const jvalue *");
            case "FindClass" -> new Signature("jclass", "const char *");
            case "GetObjectClass" -> new Signature("jclass", "jobject");
            case "GetMethodID", "GetStaticMethodID" -> new Signature("jmethodID", "jclass", "const char *", "const char *");
            case "GetFieldID", "GetStaticFieldID" -> new Signature("jfieldID", "jclass", "const char *", "const char *");
            case "GetStaticObjectField" -> new Signature("jobject", "jclass", "jfieldID");
            case "NewString" -> new Signature("jstring", "const jchar *", "jsize");
            case "NewStringUTF" -> new Signature("jstring", "const char *");
            case "GetStringChars" -> new Signature("const jchar *", "jstring", "jboolean *");
            case "GetStringUTFChars" -> new Signature("const char *", "jstring", "jboolean *");
            case "NewObjectArray" -> new Signature("jobjectArray", "jsize", "jclass", "jobject");
            case "GetObjectArrayElement" -> new Signature("jobject", "jobjectArray", "jsize");
            case "SetObjectArrayElement" -> new Signature("void", "jobjectArray", "jsize", "jobject");
            case "NewGlobalRef", "NewLocalRef", "PopLocalFrame" -> new Signature("jobject", "jobject");
            case "EnsureLocalCapacity", "PushLocalFrame" -> new Signature("jint", "jint");
            case "GetDirectBufferAddress" -> new Signature("void *", "jobject");
            case "GetDirectBufferCapacity" -> new Signature("jlong", "jobject");
            case "ThrowNew" -> new Signature("jint", "jclass", "const char *");
            case "RegisterNatives" -> new Signature("jint", "jclass", "const JNINativeMethod *", "jint");
            case "UnregisterNatives" -> new Signature("jint", "jclass");
            default -> throw new IllegalArgumentException("unreviewed generated JNI execution helper: " + name);
        };
    }

    private static String cType(IrType type) {
        if (type.isReference() || type.equals(IrType.EXCEPTION)) return "void *";
        return switch (type.kind()) {
            case VOID -> "void"; case I1 -> "_Bool"; case I8 -> "int8_t"; case I16 -> "int16_t"; case U16 -> "uint16_t";
            case I32 -> "int32_t"; case I64 -> "int64_t"; case F32 -> "float"; case F64 -> "double";
            default -> throw new IllegalArgumentException("unsupported bridge native carrier: " + type);
        };
    }

    public static String compose(BridgeObjectAdmission admission, String adapters) {

        if (adapters.contains("ironwood_deallocate(")) throw new IllegalArgumentException("GC bridge cannot contain managed deallocation");
        String bootstrap = "new_loader = (*env)->NewGlobalRef(env, loader);";
        if (!adapters.contains(bootstrap)) throw new IllegalArgumentException("foreign transitions require complete generated bootstrap");
        adapters = adapters.replace(bootstrap, "if (!iw_gc_jni_init(env)) goto unbound_failure;\n    " + bootstrap)
                .replace("iw_exception_metadata_dispose(env, &iw_exceptions);", "iw_gc_jni_dispose(env);\n    iw_exception_metadata_dispose(env, &iw_exceptions);");
        var helpers = new TreeSet<String>();
        var matcher = JNI.matcher(adapters);
        StringBuilder instrumented = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (LEAF.contains(name)) continue;
            signature(name);
            helpers.add(name);
            matcher.appendReplacement(instrumented, "iw_gc_jni_" + name + "(");
        }
        matcher.appendTail(instrumented);
        StringBuilder text = new StringBuilder(PREFIX);
        var functions = admission.emissionProgram().functions().stream().filter(f -> admission.emissionProgram().exportRoots().contains(f.linkageName()))
                .sorted(Comparator.comparing(IrFunction::linkageName)).toList();
        var effects = GcEffects.analyzeForeignOwners(admission.emissionProgram());
        var selected = functions.stream().filter(f -> effects.function(f.linkageName()).mayCollect()).toList();
        for (int index = 0; index < selected.size(); index++) {
            var function = selected.get(index);
            if (effects.function(function.linkageName()).unknown()) throw new IllegalArgumentException("unqualified native effect: " + function.linkageName());
            var parameters = function.parameters();
            text.append("extern ").append(cType(function.returnType())).append(" iw_gc_target_").append(index).append('(')
                    .append(parameters.isEmpty() ? "void" : parameters.stream().map(p -> cType(p.value().type())).collect(Collectors.joining(", ")))
                    .append(") __asm__(IW_GC_SYMBOL(\"").append(function.linkageName()).append("\"));\n")
                    .append("struct iw_gc_args_").append(index).append(" { ");
            for (int i = 0; i < parameters.size(); i++) text.append(cType(parameters.get(i).value().type())).append(" p").append(i).append("; ");
            if (!function.returnType().equals(IrType.VOID)) text.append(cType(function.returnType())).append(" result; ");
            text.append("};\n");
        }
        text.append("__attribute__((noinline, disable_tail_calls)) static void iw_gc_bridge_dispatch(unsigned entry, void *arguments) {\n")
                .append("    struct iw_gc_segment segment = {0};\n")
                .append("    if (!iw_gc_segment_enter(&segment, iw_gc_stack_boundary())) iw_gc_transition_refuse();\n    switch (entry) {\n");
        for (int index = 0; index < selected.size(); index++) {
            var function = selected.get(index);
            text.append("    case ").append(index).append(": { struct iw_gc_args_").append(index).append(" *args = arguments; ");
            if (!function.returnType().equals(IrType.VOID)) text.append("args->result = ");
            text.append("iw_gc_target_").append(index).append('(')
                    .append(java.util.stream.IntStream.range(0, function.parameters().size()).mapToObj(i -> "args->p" + i).collect(Collectors.joining(", ")))
                    .append("); break; }\n");
        }
        text.append("    default: (void)arguments; iw_gc_transition_refuse();\n    }\n")
                .append("    if (!iw_gc_segment_leave(&segment)) iw_gc_transition_refuse();\n}\n")
                .append("void (*iw_gc_bridge_boundary)(void) = (void (*)(void))iw_gc_bridge_dispatch;\n");
        for (int index = 0; index < selected.size(); index++) {
            var function = selected.get(index);
            text.append("static ").append(cType(function.returnType())).append(" iw_gc_call_").append(index).append('(');
            var formals = new ArrayList<String>();
            for (int i = 0; i < function.parameters().size(); i++) formals.add(cType(function.parameters().get(i).value().type()) + " p" + i);
            text.append(formals.isEmpty() ? "void" : String.join(", ", formals)).append(") {\n    struct iw_gc_args_").append(index).append(" args = {");
            for (int i = 0; i < function.parameters().size(); i++) text.append(".p").append(i).append(" = p").append(i).append(", ");
            if (!function.returnType().equals(IrType.VOID)) text.append(".result = 0");
            text.append("};\n    iw_gc_bridge_dispatch(").append(index).append(", &args);\n");
            if (!function.returnType().equals(IrType.VOID)) text.append("    return args.result;\n");
            text.append("}\n#define ").append(function.linkageName()).append(" iw_gc_call_").append(index).append('\n');
        }
        for (String helper : helpers) wrapper(text, helper, signature(helper));
        return text.append(instrumented).toString();
    }

    private static void wrapper(StringBuilder text, String name, Signature signature) {
        text.append("__attribute__((noinline, disable_tail_calls)) static ").append(signature.result()).append(" iw_gc_jni_").append(name).append("(JNIEnv *env");
        for (int i = 0; i < signature.parameters().size(); i++) text.append(", ").append(signature.parameters().get(i)).append(" p").append(i);
        if (signature.variadic()) text.append(", ...");
        text.append(") {\n    struct iw_gc_segment *segment = iw_gc_segment_current();\n")
                .append("    int suspend = segment != NULL && segment->state == 1;\n")
                .append("    if (suspend && !iw_gc_segment_suspend(segment, (void (*)(void))iw_gc_jni_").append(name).append(")) {\n")
                .append("        if (iw_gc_segment_failure() != IW_GC_SEGMENT_OOM) iw_gc_transition_refuse();\n")
                .append("        (*env)->Throw(env, iw_gc_java_oom);\n        return").append(signature.result().equals("void") ? "" : " 0").append(";\n    }\n");
        if (signature.variadic()) text.append("    va_list args; va_start(args, p").append(signature.parameters().size() - 1).append(");\n");
        text.append("    ");
        if (!signature.result().equals("void")) text.append(signature.result()).append(" result = ");
        text.append("(*env)->").append(signature.variadic() ? name + "V" : name).append("(env");
        for (int i = 0; i < signature.parameters().size(); i++) text.append(", p").append(i);
        if (signature.variadic()) text.append(", args");
        text.append(");\n");
        if (signature.variadic()) text.append("    va_end(args);\n");
        text.append("    if (suspend && !iw_gc_segment_resume(segment)) iw_gc_transition_refuse();\n");
        if (!signature.result().equals("void")) text.append("    return result;\n");
        text.append("}\n");
    }

    private static final String PREFIX = """
            /* SPDX-License-Identifier: MIT OR Apache-2.0 */
            #include <jni.h>
            #include <stdint.h>
            #include <stdarg.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include "ironwood_gc_roots.h"
            #include "ironwood_bridge.h"
            #if defined(__APPLE__)
            #define IW_GC_SYMBOL(name) "_" name
            #else
            #define IW_GC_SYMBOL(name) name
            #endif
            static jthrowable iw_gc_java_oom;
            __attribute__((always_inline, unused)) static inline int iw_gc_jni_acquired(JNIEnv *env, const void *value) {
                if (value != NULL) return 1;
                if (!(*env)->ExceptionCheck(env)) (*env)->Throw(env, iw_gc_java_oom);
                return 0;
            }
            __attribute__((cold, noinline)) static _Noreturn void iw_gc_transition_refuse_at(const char *function, int line) {
                fprintf(stderr, "Ironwood foreign segment metadata refused before Java execution: %s:%d\\n", function, line);
                exit(86);
            }
            #define iw_gc_transition_refuse() iw_gc_transition_refuse_at(__func__, __LINE__)
            static void iw_gc_jni_dispose(JNIEnv *env) {
                if (iw_gc_java_oom != NULL) (*env)->DeleteGlobalRef(env, iw_gc_java_oom);
                iw_gc_java_oom = NULL;
            }
            static int iw_gc_jni_init(JNIEnv *env) {
                jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (type == NULL) return 0;
                jmethodID constructor = (*env)->GetMethodID(env, type, "<init>", "()V");
                if (constructor == NULL) { (*env)->DeleteLocalRef(env, type); return 0; }
                jobject value = (*env)->NewObject(env, type, constructor);
                (*env)->DeleteLocalRef(env, type);
                if (value == NULL) return 0;
                iw_gc_java_oom = (*env)->NewGlobalRef(env, value);
                (*env)->DeleteLocalRef(env, value);
                return iw_gc_java_oom != NULL;
            }
            """;
}
