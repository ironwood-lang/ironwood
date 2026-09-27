// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

/** JNI carriers use the same named tokens as the compiler-owned protected entries. */
final class BridgeEnumNativeSources {
    private BridgeEnumNativeSources() {}

    static void metadata(StringBuilder text, BridgePermanentJavaSources.Sources java) {
        int count = java.enums().size();
        if (count == 0) return;
        text.append("static jclass iw_enum_types[").append(count).append("];\n")
                .append("static jfieldID iw_enum_tokens[").append(count).append("];\n");
        for (int index = 0; index < count; index++) {
            int constants = java.enums().get(index).constants().size();
            if (constants > 0) text.append("static jfieldID iw_enum_constants_").append(index).append('[').append(constants).append("];\n");
        }
        text.append("static void iw_enum_metadata_dispose(JNIEnv *env) {\n")
                .append("    for (int index = 0; index < ").append(count).append("; index++) {\n")
                .append("        if (iw_enum_types[index] != NULL) (*env)->DeleteGlobalRef(env, iw_enum_types[index]);\n")
                .append("        iw_enum_types[index] = NULL; iw_enum_tokens[index] = NULL;\n    }\n");
        for (int index = 0; index < count; index++) {
            if (!java.enums().get(index).constants().isEmpty()) text.append("    memset(iw_enum_constants_").append(index)
                    .append(", 0, sizeof(iw_enum_constants_").append(index).append("));\n");
        }
        text.append("}\nstatic int iw_enum_metadata_init(JNIEnv *env, jclass *classes) {\n");
        for (int index = 0; index < count; index++) {
            int registered = java.declarations().generatedTypes().indexOf(java.enums().get(index).binaryName());
            if (registered < 0) throw new IllegalArgumentException("enum missing from complete bootstrap inventory");
            text.append("    iw_enum_types[").append(index).append("] = (*env)->NewGlobalRef(env, classes[").append(registered).append("]);\n")
                    .append("    if (iw_enum_types[").append(index).append("] == NULL) { iw_enum_metadata_dispose(env); return 0; }\n");
        }
        text.append("    return 1;\n}\n");
        for (int index = 0; index < count; index++) {
            var type = java.enums().get(index);
            text.append("__attribute__((unused)) static int iw_enum_input_").append(index).append("(JNIEnv *env, jobject value, int32_t *token) {\n")
                    .append("    if (value == NULL) { *token = -1; return 1; }\n")
                    .append("    if (iw_enum_tokens[").append(index).append("] == NULL) {\n")
                    .append("        jfieldID field = (*env)->GetFieldID(env, iw_enum_types[").append(index).append("], ")
                    .append(BridgeJavaSources.quote(type.tokenField())).append(", \"I\");\n")
                    .append("        if (field == NULL) return 0; iw_enum_tokens[").append(index).append("] = field;\n    }\n")
                    .append("    *token = (*env)->GetIntField(env, value, iw_enum_tokens[").append(index).append("]);\n")
                    .append("    return !(*env)->ExceptionCheck(env);\n}\n")
                    .append("__attribute__((unused)) static jobject iw_enum_output_").append(index).append("(JNIEnv *env, int32_t token) {\n")
                    .append("    if (token == -1) return NULL;\n    switch (token) {\n");
            for (int value = 0; value < type.constants().size(); value++) {
                var constant = type.constants().get(value);
                text.append("    case ").append(constant.token()).append(":\n")
                        .append("        if (iw_enum_constants_").append(index).append('[').append(value).append("] == NULL) {\n")
                        .append("            jfieldID field = (*env)->GetStaticFieldID(env, iw_enum_types[").append(index).append("], ")
                        .append(BridgeJavaSources.quote(constant.field().name())).append(", ")
                        .append(BridgeJavaSources.quote("L" + type.binaryName().replace('.', '/') + ";")).append(");\n")
                        .append("            if (field == NULL) return NULL; iw_enum_constants_").append(index).append('[').append(value).append("] = field;\n        }\n")
                        .append("        return (*env)->GetStaticObjectField(env, iw_enum_types[").append(index).append("], iw_enum_constants_")
                        .append(index).append('[').append(value).append("]);\n");
            }
            text.append("    default: iw_exception_error(env, &iw_exceptions, 0, \"invalid paired enum result token\"); return NULL;\n    }\n}\n");
        }
    }
}
