// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

/** Cold copied-slot extraction using only exact protected getter entries. */
final class BridgeCustomSnapshotNativeSources {
    private BridgeCustomSnapshotNativeSources() {}

    static String generate(BridgeExceptionProjection projection, BridgeCustomSnapshotLayout layout, BridgeExceptionEntries entries) {
        var text = new StringBuilder();
        for (var type : projection.types()) {
            var values = layout.values().get(type.nativeName());
            if (values == null) continue;
            text.append("static int iw_exception_custom_").append(type.typeId()).append("(JNIEnv *env,\n")
                    .append("        const struct iw_exception_metadata *metadata, void *node, jobjectArray copied_numbers,\n")
                    .append("        jobjectArray copied_texts, jint index, jint *number) {\n")
                    .append("    (void)node; (void)number;\n")
                    .append("    jlongArray numbers = (*env)->NewLongArray(env, ").append(layout.slots().size()).append(");\n")
                    .append("    if (numbers == NULL) return 0;\n")
                    .append("    jobjectArray texts = (*env)->NewObjectArray(env, ").append(layout.slots().size())
                    .append(", metadata->classes[IW_EX_STRING], NULL);\n")
                    .append("    if (texts == NULL) return 0;\n");
            if (!values.isEmpty()) text.append("    struct ironwood_bridge_result result;\n");
            for (var value : values) {
                var property = value.property();
                int slot = value.slot().index();
                var entry = entries.accessors().get(property);
                if (entry == null) throw new IllegalArgumentException("custom copied slot lacks its exact protected getter");
                text.append("    if (!iw_exception_status(env, metadata, ").append(entry.linkageName())
                        .append("(node, (int64_t)(uintptr_t)&result))) return 0;\n");
                if (property.type().kind() == ironwood.compiler.ir.IrType.Kind.REFERENCE) {
                    text.append("    const struct ironwood_string *string").append(slot).append(" = result.value.reference;\n")
                            .append("    jstring value").append(slot).append(" = string").append(slot).append(" == NULL ? NULL : (*env)->NewString(env, string")
                            .append(slot).append("->units, string").append(slot).append("->utf16_length);\n");
                    if (property.ownedString()) text.append("    ironwood_deallocate(result.value.reference);\n");
                    text.append("    if ((*env)->ExceptionCheck(env)) return 0;\n")
                            .append("    (*env)->SetObjectArrayElement(env, texts, ").append(slot).append(", value").append(slot).append(");\n")
                            .append("    (*env)->DeleteLocalRef(env, value").append(slot).append(");\n");
                } else {
                    String member = switch (property.type().kind()) {
                        case I1 -> "boolean"; case I8 -> "byte"; case I16 -> "short_integer"; case U16 -> "character";
                        case I32 -> "integer"; case I64 -> "wide"; case F32 -> "single"; case F64 -> "real";
                        default -> throw new IllegalArgumentException("unsupported custom copied scalar");
                    };
                    if (property.type().equals(ironwood.compiler.ir.IrType.F32)) {
                        text.append("    uint32_t raw").append(slot).append("; memcpy(&raw").append(slot).append(", &result.value.single, sizeof(raw")
                                .append(slot).append("));\n    jlong bits").append(slot).append(" = raw").append(slot).append(";\n");
                    } else if (property.type().equals(ironwood.compiler.ir.IrType.F64)) {
                        text.append("    jlong bits").append(slot).append("; memcpy(&bits").append(slot).append(", &result.value.real, sizeof(bits")
                                .append(slot).append("));\n");
                    } else text.append("    jlong bits").append(slot).append(" = result.value.").append(member).append(";\n");
                    if (property.name().equals("bytesTransferred")) text.append("    *number = result.value.integer;\n");
                    text.append("    (*env)->SetLongArrayRegion(env, numbers, ").append(slot).append(", 1, &bits").append(slot).append(");\n");
                }
                text.append("    if ((*env)->ExceptionCheck(env)) return 0;\n");
            }
            text.append("    (*env)->SetObjectArrayElement(env, copied_numbers, index, numbers);\n")
                    .append("    if ((*env)->ExceptionCheck(env)) return 0;\n")
                    .append("    (*env)->SetObjectArrayElement(env, copied_texts, index, texts);\n")
                    .append("    return !(*env)->ExceptionCheck(env);\n}\n");
        }
        text.append("static int iw_exception_custom(JNIEnv *env, const struct iw_exception_metadata *metadata,\n")
                .append("        jint type, void *node, jobjectArray numbers, jobjectArray texts, jint index, jint *number) {\n")
                .append("    (void)env; (void)metadata; (void)node; (void)numbers; (void)texts; (void)index; (void)number;\n")
                .append("    switch (type) {\n");
        for (var type : projection.types()) if (layout.values().containsKey(type.nativeName())) {
            text.append("    case ").append(type.typeId()).append(": return iw_exception_custom_").append(type.typeId())
                    .append("(env, metadata, node, numbers, texts, index, number);\n");
        }
        return text.append("    default: return 1;\n    }\n}\n").toString();
    }
}
