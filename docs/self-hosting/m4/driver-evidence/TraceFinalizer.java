// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for S4's native trace-finalization mode in the M4.3 pipeline
 * test: applies the Java baseline's own OptimizedTraceMetadata.inject (by
 * reflection on the compiler classes on the class path) to the optimized
 * module in argument 0 and writes the result to argument 1, as NativeBackend
 * does between opt and the second llvm-as for an executable.
 */
public final class TraceFinalizer {
    public static void main(String[] args) throws Exception {
        Method inject = Class.forName("ironwood.compiler.backend.OptimizedTraceMetadata")
                .getDeclaredMethod("inject", String.class, boolean.class, boolean.class);
        inject.setAccessible(true);
        String optimized = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        Files.writeString(Path.of(args[1]), (String) inject.invoke(null, optimized, mac, false), StandardCharsets.UTF_8);
    }
}
