// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java reference for integration-tests/cases/compiler_llvm_scan.iron. Run with
 * the bootstrap classes on the class path and the corpus files as arguments:
 * NativeTarget's private specification and attach run by reflection, and
 * OptimizedTraceMetadata's own REGISTER_DECLARATION and FUNCTION patterns
 * supply the declaration removal and the function matches.
 */
public final class LlvmScanReference {
    private static final StringBuilder OUT = new StringBuilder();
    private static Method specification;
    private static Method attach;
    private static Pattern register;
    private static Pattern function;

    private LlvmScanReference() { }

    static void digest(String label, String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        OUT.append(label).append(' ').append(HexFormat.of().formatHex(hash)).append('\n');
    }

    static void attach(String llvm, String name, String label, String value) throws Exception {
        try {
            digest("attach " + name + " " + label, (String) attach.invoke(null, llvm, name, value));
        } catch (InvocationTargetException failure) {
            OUT.append("attach ").append(name).append(' ').append(label).append(" error ")
                    .append(failure.getCause().getMessage()).append('\n');
        }
    }

    static void target(String llvm, String name) throws Exception {
        String found = null;
        try {
            found = (String) specification.invoke(null, llvm, name);
            OUT.append("specification ").append(name).append(' ').append(found).append('\n');
        } catch (InvocationTargetException failure) {
            OUT.append("specification ").append(name).append(" error ").append(failure.getCause().getMessage())
                    .append('\n');
        }
        attach(llvm, name, "found", found == null ? "none" : found);
        attach(llvm, name, "configured", "x86_64-unknown-linux-gnu");
    }

    public static void main(String[] args) throws Exception {
        Class<?> target = Class.forName("ironwood.compiler.backend.NativeTarget");
        specification = target.getDeclaredMethod("specification", String.class, String.class);
        specification.setAccessible(true);
        attach = target.getDeclaredMethod("attach", String.class, String.class, String.class);
        attach.setAccessible(true);
        Class<?> metadata = Class.forName("ironwood.compiler.backend.OptimizedTraceMetadata");
        Field registerField = metadata.getDeclaredField("REGISTER_DECLARATION");
        registerField.setAccessible(true);
        register = (Pattern) registerField.get(null);
        Field functionField = metadata.getDeclaredField("FUNCTION");
        functionField.setAccessible(true);
        function = (Pattern) functionField.get(null);
        for (int index = 0; index < args.length; index++) {
            String llvm = Files.readString(Path.of(args[index]));
            OUT.append("file ").append(index).append('\n');
            target(llvm, "triple");
            target(llvm, "datalayout");
            digest("register", register.matcher(llvm).replaceFirst(""));
            Matcher matcher = function.matcher(llvm);
            while (matcher.find()) {
                OUT.append("function ").append(matcher.start(1)).append(' ').append(matcher.end(1)).append(' ')
                        .append(matcher.end()).append('\n');
            }
        }
        System.out.print(OUT);
    }
}
