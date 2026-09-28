// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.List;

/** Shared D206 noncritical JNI buffer preparation and reverse-prefix cleanup. */
public final class BridgeStringInputSources {
    private BridgeStringInputSources() {}

    public static String declarations(List<Integer> strings) {
        var text = new StringBuilder();
        for (int i : strings) text.append("    const jchar *chars").append(i).append(" = NULL; jsize length").append(i).append(" = -1;\n");
        return text.toString();
    }

    public static String acquire(List<Integer> strings) {
        var text = new StringBuilder();
        for (int i : strings) {
            text.append("    if (arg").append(i).append(" != NULL) {\n")
                    .append("        length").append(i).append(" = (*env)->GetStringLength(env, arg").append(i).append(");\n")
                    .append("        if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("        chars").append(i).append(" = (*env)->GetStringChars(env, arg").append(i).append(", NULL);\n")
                    .append("        if (chars").append(i).append(" == NULL) goto preparation_failed;\n    }\n");
        }
        return text.toString();
    }

    public static String release(List<Integer> strings) {
        var text = new StringBuilder();
        for (int index = strings.size() - 1; index >= 0; index--) {
            int i = strings.get(index);
            text.append("    if (chars").append(i).append(" != NULL) (*env)->ReleaseStringChars(env, arg")
                    .append(i).append(", chars").append(i).append(");\n");
        }
        return text.toString();
    }
}
