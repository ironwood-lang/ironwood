// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Qualification-only cold heap cap; production runtime and SDK stay byte exact. */
public final class GcAuditLink {
    private GcAuditLink() {}

    public static void main(String[] args) throws Exception {
        Path llvm = Path.of(args[0]), input = Path.of(args[1]), output = Path.of(args[2]);
        String original = Files.readString(input);
        String start = "  call void @ironwood_gc_start()\n";
        if (original.indexOf(start) < 0 || original.indexOf(start) != original.lastIndexOf(start)) {
            throw new AssertionError("expected one normal executable startup");
        }
        String bounded = original.replace(start, start + "  call void @GC_set_max_heap_size(i64 16777216)\n")
                + "\ndeclare void @GC_set_max_heap_size(i64) \"gc-leaf-function\"\n";
        Path qualified = input.resolveSibling("bounded.ll");
        Files.writeString(qualified, bounded);
        var tools = LlvmToolchain.discover(llvm).toolchain().orElseThrow();
        var result = new NativeBackend().link(tools, qualified, output, OptimizationLevel.valueOf(args[3]));
        if (!result.success()) throw new AssertionError(result.output());
    }
}
