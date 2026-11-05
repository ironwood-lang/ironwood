// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Metadata integrity controls. Native liveness uses the source-derived GC3 harness. */
public final class GcRootObjectTests {
    public static final String NAME = "GC3 late verification rejects missing strategies and undescribed stack regions";
    private GcRootObjectTests() {}

    public static void proofs() throws Exception {
        denied(() -> GcLateVerification.strategies("define void @lost() {\n  ret void\n}\n"));
        String function = "define void @root() gc \"statepoint-example\" {\n  %slot = alloca ptr addrspace(1), align 8\n";
        String point = "  %token = call token (...) @llvm.experimental.gc.statepoint.p0()";
        denied(() -> GcLateVerification.stackRegions(function + point + "\n}\n"));
        GcLateVerification.stackRegions(function + point + " [ \"deopt\"(i64 1230455603, i64 1, ptr %slot) ]\n}\n");
        denied(() -> GcLateVerification.stackRegions(function.replace("alloca ptr addrspace(1)", "alloca [2 x ptr addrspace(1)]") + "}\n"));
        denied(() -> GcRootSections.retain(new byte[64]));
    }

    public static void main(String[] arguments) throws Exception {
        proofs();
        Path input = Path.of(arguments[1]), output = Path.of(arguments[2]);
        if (arguments[0].equals("retain")) Files.write(output, GcRootSections.retain(Files.readAllBytes(input)));
        else if (arguments[0].equals("trace")) Files.writeString(output, OptimizedTraceMetadata.injectGc(Files.readString(input), false));
        else throw new IllegalArgumentException("expected retain or trace");
    }

    private interface Check { void run() throws IOException; }
    private static void denied(Check action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("invalid GC3 metadata admitted");
    }
}
