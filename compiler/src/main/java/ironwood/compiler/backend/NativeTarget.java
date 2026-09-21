// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.util.regex.Pattern;

/** Target specifications from the configured Clang, resolved only at native link. */
record NativeTarget(String triple, String dataLayout) {
    static NativeTarget fromLlvm(String llvm) throws IOException {
        return new NativeTarget(specification(llvm, "triple"), specification(llvm, "datalayout"));
    }

    private static String specification(String llvm, String name) throws IOException {
        var matcher = pattern(name).matcher(llvm);
        if (!matcher.find()) throw new IOException("Clang did not emit target " + name);
        String value = matcher.group(1);
        if (matcher.find()) throw new IOException("duplicate target " + name);
        return value;
    }

    private static Pattern pattern(String name) {
        return Pattern.compile("(?m)^target " + name + " = \"([^\"\\r\\n]+)\"[\\t ]*$");
    }

    String applyTo(String llvm) throws IOException {
        return attach(attach(llvm, "triple", triple), "datalayout", dataLayout);
    }

    private static String attach(String llvm, String name, String value) throws IOException {
        var matcher = pattern(name).matcher(llvm);
        if (matcher.find()) {
            if (!matcher.group(1).equals(value) || matcher.find()) {
                throw new IOException("LLVM module target " + name + " disagrees with configured Clang");
            }
            return llvm;
        }
        return "target " + name + " = \"" + value + "\"\n" + llvm;
    }
}
